@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.treadz.console.userdata

import com.treadz.console.analysis.energy.FuelSnapshot
import com.treadz.console.analysis.energy.FuelTracker
import com.treadz.console.analysis.thresholds.ThresholdPoint
import com.treadz.console.analysis.thresholds.ThresholdQuality
import com.treadz.console.analysis.thresholds.ThresholdResult
import com.treadz.console.programs.fitness.HeartRateSourceKind
import com.treadz.console.treadmill.BeltStatus
import com.treadz.console.treadmill.control.Clock
import com.treadz.console.userdata.memory.InMemoryUserData
import com.treadz.console.userdata.workout.HeartRatePeakTracker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import org.robolectric.RobolectricTestRunner
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * The heart rate data of the workout history observer: source, RR intervals and peak, with and without a heart rate
 * source. Run against Room (Robolectric, in-memory database) and against the in-memory implementation.
 */
@RunWith(RobolectricTestRunner::class)
abstract class HeartRateObserverContractTest {

    @After fun closeDatabases() = closeRoomDatabases()

    protected abstract fun create(clock: Clock, scheduler: TestCoroutineScheduler): UserData

    private inner class Rig(val test: TestScope, withSource: Boolean, val maxHeartRate: Int?) {
        val clock = Clock { T0 + test.testScheduler.currentTime }
        val data = create(clock, test.testScheduler)
        val controller = FakeTreadmill()
        val source = if (withSource) FakeHeartRateSource() else null
        val errors = mutableListOf<Throwable>()
        val scope = CoroutineScope(StandardTestDispatcher(test.testScheduler) + SupervisorJob())
        val observer = data.workoutObserver(controller, clock, heartRateSource = source, onError = { errors += it })
        var userId = 0L
        private var stamp = 0L

        suspend fun init(): Rig {
            userId = (data.profiles.create(validInput("Ana").copy(maxHeartRateBpm = maxHeartRate)) as DataResult.Success).value.id
            observer.start(scope)
            test.runCurrent()
            return this
        }

        /** The treadmill's state for second [elapsed]: the heart rate of the console is [consoleHr]. */
        suspend fun push(status: BeltStatus = BeltStatus.Running, speed: Double = 8.0, elapsed: Int, consoleHr: Int? = null) {
            controller.state.value = state(status, speed, distance = elapsed * 2, elapsed = elapsed, hr = consoleHr).copy(updatedAtMillis = ++stamp)
            test.runCurrent()
        }

        suspend fun wait(ms: Long = 1000) {
            test.advanceTimeBy(ms)
            test.runCurrent()
        }

        /** One second: the strap notifies first, then the treadmill reports. */
        suspend fun second(elapsed: Int, bpm: Int?, rr: List<Int> = emptyList(), consoleHr: Int? = null) {
            source?.emit(bpm, rr)
            push(elapsed = elapsed, consoleHr = consoleHr)
            wait()
        }

        suspend fun stopBelt(elapsed: Int) {
            push(BeltStatus.Idle, 2.0, elapsed)
            wait()
            push(BeltStatus.Idle, 0.0, elapsed)
        }

        suspend fun workout(): WorkoutDetail {
            val summary = data.workouts.workouts(userId).first().single()
            return data.workouts.detail(summary.id)!!
        }
    }

    private fun scenario(withSource: Boolean = true, maxHeartRate: Int? = null, body: suspend Rig.() -> Unit) = runTest {
        val rig = Rig(this, withSource, maxHeartRate).init()
        try {
            rig.body()
            assertEquals("the observer must never send commands", emptyList<String>(), rig.controller.commands)
            assertEquals(emptyList<Throwable>(), rig.errors)
        } finally {
            rig.scope.cancel()
        }
    }

    // ---- Without a heart rate source ----

    @Test fun `without a source the console heart rate is stored with the console as its source and there are no RR intervals`() =
        scenario(withSource = false) {
            push(BeltStatus.Starting, 0.0, 0)
            wait()
            for (i in 1..5) {
                push(elapsed = i, consoleHr = if (i == 3) null else 120 + i)
                wait()
            }
            stopBelt(5)
            val samples = workout().samples
            // the countdown, five seconds (one without reading), the belt decelerating and stopped (no reading either)
            assertEquals(listOf(null, 121, 122, null, 124, 125, null, null), samples.map { it.heartRateBpm })
            assertEquals(
                samples.map { if (it.heartRateBpm != null) HeartRateSourceKind.TreadmillConsole else null },
                samples.map { it.heartRateSource },
            )
            assertTrue(samples.all { it.rrIntervalsMs.isEmpty() })
        }

    // ---- With a heart rate source ----

    @Test fun `with a source the samples carry its heart rate and source and every RR interval once, in order`() = scenario {
        source!!.emit(null, rr = listOf(999)) // before the session: not part of it
        wait()
        val expected = mutableListOf<Int>()
        push(BeltStatus.Starting, 0.0, 0, consoleHr = 90)
        wait()
        for (i in 1..10) {
            if (i == 5) {
                // two notifications in the same second: nothing is lost
                source.emit(130, rr = listOf(701))
                source.emit(131, rr = listOf(702))
                expected += listOf(701, 702)
            }
            val rr = listOf(800 - i, 790 - i)
            expected += rr
            second(i, 120 + i, rr, consoleHr = 90)
        }
        stopBelt(10)

        val w = workout()
        val samples = w.samples
        assertEquals("the closing sample repeats the last reading", 130, samples.last().heartRateBpm)
        assertEquals(expected, samples.flatMap { it.rrIntervalsMs })
        assertTrue(samples.drop(1).all { it.heartRateSource == HeartRateSourceKind.DirectStrap })
        assertEquals((1..10).map { 120 + it }, samples.filter { it.beltStatus == BeltStatus.Running }.map { it.heartRateBpm })
        // the console's 90 is not used: the statistics are those of the strap
        // (ten seconds and the sample of the belt decelerating, which has the last reading)
        assertEquals(126, w.summary.stats.averageHeartRateBpm)
        assertEquals(130, w.summary.stats.maxHeartRateBpm)
        // the intervals that arrive in a second go with that second's sample
        assertEquals(listOf(799, 789), samples[1].rrIntervalsMs)
    }

    @Test fun `a missing or invalid reading gives a sample without heart rate and source`() = scenario {
        push(BeltStatus.Starting, 0.0, 0)
        wait()
        second(1, 130)
        second(2, null) // the strap lost contact
        second(3, 131, consoleHr = 80)
        stopBelt(3)
        val running = workout().samples.filter { it.beltStatus == BeltStatus.Running }
        assertEquals(listOf(130, null, 131), running.map { it.heartRateBpm })
        assertEquals(listOf(HeartRateSourceKind.DirectStrap, null, HeartRateSourceKind.DirectStrap), running.map { it.heartRateSource })
    }

    @Test fun `the console as the source of the heart rate has no RR intervals`() = scenario {
        push(BeltStatus.Starting, 0.0, 0)
        wait()
        for (i in 1..3) {
            source!!.emit(140 + i, source = HeartRateSourceKind.TreadmillConsole)
            push(elapsed = i)
            wait()
        }
        stopBelt(3)
        val samples = workout().samples.filter { it.heartRateBpm != null }
        assertEquals("the belt decelerating and stopped repeat the last reading", listOf(141, 142, 143, 143, 143), samples.map { it.heartRateBpm })
        assertTrue(samples.all { it.heartRateSource == HeartRateSourceKind.TreadmillConsole && it.rrIntervalsMs.isEmpty() })
    }

    @Test fun `intervals that arrive between two states too close to be two samples go with the next sample`() = scenario {
        push(BeltStatus.Starting, 0.0, 0)
        wait()
        source!!.emit(120, listOf(800))
        push(elapsed = 1)
        // two more states less than 400 ms later: no samples (the treadmill sends ~1 Hz), but their intervals are kept
        wait(100)
        source.emit(121, listOf(790))
        push(elapsed = 1)
        wait(100)
        source.emit(121, listOf(780))
        push(elapsed = 1)
        wait(800)
        second(2, 122, listOf(770))
        stopBelt(2)
        val samples = workout().samples
        assertEquals(listOf(800, 790, 780, 770), samples.flatMap { it.rrIntervalsMs })
        assertEquals(listOf(800), samples[1].rrIntervalsMs)
        assertEquals(listOf(790, 780, 770), samples[2].rrIntervalsMs)
    }

    @Test fun `a fitness test workout records the source and the RR intervals the same`() = scenario {
        observer.linkNextSession(WorkoutLink(fitnessTestId = TEST_1))
        push(BeltStatus.Starting, 0.0, 0)
        wait()
        second(1, 150, listOf(400, 401))
        second(2, 151, listOf(399))
        stopBelt(2)
        val w = workout()
        assertEquals(TEST_1, w.summary.fitnessTestId)
        assertEquals(listOf(400, 401, 399), w.samples.flatMap { it.rrIntervalsMs })
        assertEquals(HeartRateSourceKind.DirectStrap, w.samples[1].heartRateSource)
    }

    // ---- Peak ----

    @Test fun `the peak is the highest 5 second mean of plausible readings and a spike does not count`() = scenario {
        push(BeltStatus.Starting, 0.0, 0)
        wait()
        for (i in 1..10) second(i, 140)
        for (i in 11..20) second(i, 160)
        second(21, 210) // +50 in one second: impossible, rejected
        for (i in 22..26) second(i, 160)
        stopBelt(26)
        val peak = workout().summary.stats.peakHeartRate!!
        assertEquals(160, peak.bpm)
        assertEquals(HeartRateSourceKind.DirectStrap, peak.source)
        assertTrue("the peak was reached after the step up", peak.atMillis >= T0 + 15_000)
        assertEquals(peak, data.workouts.highestHeartRatePeak(userId).first()!!.peak)
    }

    @Test fun `the peak is flushed with the totals while the session runs`() = scenario {
        push(BeltStatus.Starting, 0.0, 0)
        wait()
        for (i in 1..12) second(i, 150)
        val running = data.workouts.inProgressWorkout().first()!!
        assertTrue(running.isInProgress)
        assertEquals(150, running.stats.peakHeartRate?.bpm)
        // finished workouts only: this one is still in progress
        assertNull(data.workouts.highestHeartRatePeak(userId).first())
        stopBelt(12)
        assertEquals(150, data.workouts.highestHeartRatePeak(userId).first()!!.bpm)
    }

    @Test fun `a reading above the user's maximum plus 15 is not a peak`() = scenario(maxHeartRate = 170) {
        push(BeltStatus.Starting, 0.0, 0)
        wait()
        for (i in 1..8) second(i, 150)
        for (i in 9..16) second(i, 180)
        for (i in 17..26) second(i, 190) // above 170 + 15
        stopBelt(26)
        assertEquals(180, workout().summary.stats.peakHeartRate!!.bpm)
    }

    @Test fun `without a maximum in the profile the estimate from the age is used`() = scenario {
        // Ana is 39 on the clock's day: Tanaka gives 181, so up to 196 is plausible
        push(BeltStatus.Starting, 0.0, 0)
        wait()
        for (i in 1..8) second(i, 150)
        for (i in 9..16) second(i, 180)
        for (i in 17..26) second(i, 190)
        stopBelt(26)
        assertEquals(190, workout().summary.stats.peakHeartRate!!.bpm)
    }

    @Test fun `the highest peak is looked for in the last 365 days only`() = scenario {
        push(BeltStatus.Starting, 0.0, 0)
        wait()
        for (i in 1..8) second(i, 170)
        stopBelt(8)
        assertEquals(170, data.workouts.highestHeartRatePeak(userId).first()!!.bpm)
        test.advanceTimeBy(364L * 86_400_000L)
        assertEquals(170, data.workouts.highestHeartRatePeak(userId).first()!!.bpm)
        test.advanceTimeBy(2L * 86_400_000L)
        assertNull(data.workouts.highestHeartRatePeak(userId).first())
        assertEquals(170, data.workouts.highestHeartRatePeak(userId, withinDays = 400).first()!!.bpm)
        assertNull(data.workouts.highestHeartRatePeak(userId + 99).first())
    }

    @Test fun `a workout without heart rate has no peak`() = scenario(withSource = false) {
        push(BeltStatus.Starting, 0.0, 0)
        wait()
        for (i in 1..6) {
            push(elapsed = i)
            wait()
        }
        stopBelt(6)
        assertNull(workout().summary.stats.peakHeartRate)
        assertNull(data.workouts.highestHeartRatePeak(userId).first())
    }

    @Test fun `the last workout id stays after the session ends`() = scenario {
        assertNull(observer.lastWorkoutId.value)
        push(BeltStatus.Starting, 0.0, 0)
        wait()
        second(1, 120)
        val id = observer.activeWorkoutId.value!!
        assertEquals(id, observer.lastWorkoutId.value)
        stopBelt(1)
        assertNull(observer.activeWorkoutId.value)
        assertEquals(id, observer.lastWorkoutId.value)
    }

    // ---- Fat and carbohydrate ----

    /** The user gets what a split needs: a weight, a resting heart rate and a maximal test with a measured VT1, before T0. */
    private suspend fun Rig.eligibleProfile() {
        assertTrue(data.weights.add(userId, LocalDate.ofEpochDay(T0 / DAY_MS), 70.0) is DataResult.Success)
        assertTrue(data.restingHeartRates.add(userId, RestingHeartRateInput(T0 - 3 * DAY_MS, 58, RestingHeartRateSource.Strap)) is DataResult.Success)
        val test = maxTestResult(TEST_2, T0 - 5 * DAY_MS).copy(
            thresholds = ThresholdResult(1, vt1 = ThresholdPoint(131.0), vt2 = ThresholdPoint(160.0), quality = ThresholdQuality.High),
        )
        assertTrue(data.fitnessTests.save(userId, test) is DataResult.Success)
    }

    /** [seconds] seconds of a steady strap heart rate of 120 bpm whose RR agree; the live snapshot after each one. */
    private suspend fun Rig.strapSeconds(from: Int, seconds: Int, bpm: Int? = 120): List<FuelSnapshot?> = (from until from + seconds).map { e ->
        second(e, bpm, rr = if (bpm == null) emptyList() else listOf(500, 500))
        observer.liveFuel.value
    }

    @Test fun `the live fat and carbohydrate split is fed the stored rows and leads to the stored analysis`() = scenario {
        eligibleProfile()
        assertNull(observer.liveFuel.value)
        push(BeltStatus.Starting, 0.0, 0)
        wait()
        val live = strapSeconds(1, 90).map { assertNotNull(it); it!! }
        assertNull("nothing is decided before the first window closes", live.first().fatShare)
        assertTrue(live.last().showable)
        assertTrue(live.last().currentlyValid)
        stopBelt(91)
        assertNull("the live split goes with the stop", observer.liveFuel.value)

        data.workoutAnalysisRecalculator.recalculate()
        val detail = workout()
        val stored = data.workoutAnalysis.analysis(detail.summary.id).first()!!.analysis
        val fuel = stored.fuel.value
        assertNotNull("the stored analysis has the split: ${stored.fuel.reason}", fuel)
        // The rows the observer stored, fed to a tracker of the same profile: up to the last running second they give
        // the last live snapshot (the two stopping rows came after it), and closed, the stored section.
        val rows = FitnessRecalculationInputs.samples(detail.samples)
        val tracker = FuelTracker(fuel!!.anchors, 70.0, zones = stored.zoneTime.value!!.zones)
        rows.dropLast(2).forEach(tracker::add)
        assertEquals(live.last(), tracker.snapshot())
        rows.takeLast(2).forEach(tracker::add)
        assertEquals(fuel, tracker.finish().value)
    }

    @Test fun `the live split becomes showable once the valid strap heart rate covers 80 percent of the moving time`() = scenario {
        eligibleProfile()
        push(BeltStatus.Starting, 0.0, 0)
        wait()
        val live = (strapSeconds(1, 30, bpm = null) + strapSeconds(31, 150)).map { it!! }
        val first = live.indexOfFirst { it.showable }
        assertTrue("it becomes showable: ${live.last()}", first > 0)
        assertTrue(live[first].validCoverage!! >= 0.8)
        assertTrue(live.subList(0, first).all { !it.showable && (it.validCoverage ?: 0.0) < 0.8 })
        assertNotNull("there was a share before it could be shown", live[first - 1].fatShare)
        stopBelt(181)
    }

    @Test fun `without a measured VT1 there is no live split`() = scenario {
        assertTrue(data.weights.add(userId, LocalDate.ofEpochDay(T0 / DAY_MS), 70.0) is DataResult.Success)
        push(BeltStatus.Starting, 0.0, 0)
        wait()
        assertTrue(strapSeconds(1, 30).all { it == null })
        stopBelt(31)
    }

    @Test fun `with the console heart rate the live split is never showable`() = scenario(withSource = false) {
        eligibleProfile()
        push(BeltStatus.Starting, 0.0, 0)
        wait()
        val live = (1..60).map { e ->
            second(e, bpm = null, consoleHr = 120)
            observer.liveFuel.value!!
        }
        assertTrue(live.none { it.showable })
        assertFalse(live.last().currentlyValid)
        assertNull(live.last().fatShare)
        stopBelt(61)
    }
}

class RoomHeartRateObserverTest : HeartRateObserverContractTest() {
    override fun create(clock: Clock, scheduler: TestCoroutineScheduler): UserData =
        newRoomUserData(clock, StandardTestDispatcher(scheduler))
}

@RunWith(JUnit4::class)
class InMemoryHeartRateObserverTest : HeartRateObserverContractTest() {
    override fun create(clock: Clock, scheduler: TestCoroutineScheduler): UserData =
        InMemoryUserData(clock, ZoneOffset.UTC, seedGenericProfile = false)
}

/** [HeartRatePeakTracker] on its own (pure). */
class HeartRatePeakTrackerTest {

    private fun track(maxHr: Int? = null, readings: List<Pair<Long, Int?>>, source: HeartRateSourceKind? = HeartRateSourceKind.DirectStrap): HeartRatePeakTracker {
        val t = HeartRatePeakTracker(maxHr)
        readings.forEach { (at, bpm) -> t.add(at, bpm, source) }
        return t
    }

    private fun steady(bpm: Int, from: Long, seconds: Int) = (0 until seconds).map { (from + it * 1000L) to bpm }

    @Test fun `no peak without enough readings spread over the window`() {
        assertNull(track(readings = steady(150, 0, 3)).peak)
        // four readings crowded in 1.5 s are not a 5 second mean
        assertNull(track(readings = listOf(0L to 150, 500L to 150, 1000L to 150, 1500L to 150)).peak)
        assertNotNull(track(readings = steady(150, 0, 4)).peak)
    }

    @Test fun `the peak is the highest mean and keeps the earliest of equal windows`() {
        val t = track(readings = steady(140, 0, 10) + steady(165, 10_000, 8) + steady(150, 18_000, 8))
        assertEquals(165, t.peak!!.bpm)
        assertEquals(HeartRateSourceKind.DirectStrap, t.peak!!.source)
        assertEquals(14_000L, t.peak!!.atMillis) // first window entirely at 165: 10 s..14 s
    }

    @Test fun `the mean is rounded and mixes the readings of the window`() {
        val t = track(readings = listOf(0L to 150, 1000L to 151, 2000L to 152, 3000L to 153, 4000L to 154))
        assertEquals(152, t.peak!!.bpm)
    }

    @Test fun `a gap leaves too few readings for a window and impossible readings are discarded`() {
        // a missing reading is tolerated (4 of the 5 seconds), a long gap is not
        assertNotNull(track(readings = steady(150, 0, 3) + listOf(3000L to null) + steady(150, 4000, 2)).peak)
        val gap = track(readings = steady(150, 0, 3) + listOf(3000L to null, 4000L to null) + steady(150, 6000, 2))
        assertNull(gap.peak)
        // below 40 and above the absolute ceiling are not heart rates
        assertNull(track(readings = steady(35, 0, 10)).peak)
        assertNull(track(readings = steady(250, 0, 10)).peak)
        // the strap taken off: the console falls to the low 70s and keeps plausible values, which must not become a "peak" or hide it
        val off = track(readings = steady(170, 0, 10) + steady(75, 10_000, 15))
        assertEquals(170, off.peak!!.bpm)
    }

    @Test fun `the user's maximum plus 15 bounds the plausible readings`() {
        val readings = steady(150, 0, 5) + steady(176, 5_000, 6)
        assertEquals(176, track(maxHr = null, readings = readings).peak!!.bpm)
        assertEquals(150, track(maxHr = 160, readings = readings).peak!!.bpm) // 176 > 160 + 15
    }

    @Test fun `from stored samples stale samples and the closing sample are left out`() {
        fun sample(seq: Int, bpm: Int, status: BeltStatus = BeltStatus.Running, speed: Double = 8.0, stale: Boolean = false) = WorkoutSample(
            seq, T0 + seq * 1000L, seq, speed, 8.0, 0.0, 0, 0, bpm, status, stale, HeartRateSourceKind.TreadmillConsole,
        )
        val samples = (0 until 8).map { sample(it, 150) } +
            listOf(sample(8, 190, stale = true), sample(9, 200, BeltStatus.Idle, speed = 0.0)) // not counted
        val peak = HeartRatePeakTracker.of(samples.shuffled())
        assertEquals(150, peak!!.bpm)
        assertEquals(HeartRateSourceKind.TreadmillConsole, peak.source)
        assertNull(HeartRatePeakTracker.of(emptyList()))
    }

    /** One strap reading per second with the RR that arrived with it. */
    private fun strap(t: HeartRatePeakTracker, from: Long, seconds: Int, bpm: Int, rr: List<Int>, source: HeartRateSourceKind = HeartRateSourceKind.DirectStrap) =
        repeat(seconds) { t.add(from + it * 1000L, bpm, source, rr) }

    @Test fun `a strap reading its RR do not confirm is not a peak`() {
        val t = HeartRatePeakTracker()
        strap(t, 0, 60, 127, listOf(472, 472))
        // 12 s of 137 from the strap while the beats are a mix of impossible intervals
        strap(t, 60_000, 12, 137, listOf(472, 300, 260))
        strap(t, 72_000, 30, 127, listOf(472, 472))
        assertEquals(127, t.peak!!.bpm)
    }

    @Test fun `a real rise confirmed by the RR is the peak`() {
        val t = HeartRatePeakTracker()
        strap(t, 0, 60, 127, listOf(472, 472))
        strap(t, 60_000, 30, 138, listOf(435, 435))
        assertEquals(138, t.peak!!.bpm)
    }

    @Test fun `without RR every window counts, as before`() {
        val t = HeartRatePeakTracker()
        strap(t, 0, 60, 127, emptyList(), HeartRateSourceKind.TreadmillConsole)
        strap(t, 60_000, 12, 137, emptyList(), HeartRateSourceKind.TreadmillConsole)
        assertEquals(137, t.peak!!.bpm)
    }

    @Test fun `from stored samples the RR of the strap are used too`() {
        fun sample(seq: Int, bpm: Int, rr: List<Int>) = WorkoutSample(
            seq, T0 + seq * 1000L, seq, 5.6, 5.6, 7.0, 0, 0, bpm, BeltStatus.Running, false, HeartRateSourceKind.DirectStrap, rr,
        )
        val samples = (0 until 60).map { sample(it, 127, listOf(472, 472)) } +
            (60 until 72).map { sample(it, 137, listOf(472, 300, 260)) } +
            (72 until 100).map { sample(it, 127, listOf(472, 472)) }
        assertEquals(127, HeartRatePeakTracker.of(samples)!!.bpm)
    }
}
