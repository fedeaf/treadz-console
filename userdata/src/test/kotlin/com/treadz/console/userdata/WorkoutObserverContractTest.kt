@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.treadz.console.userdata

import com.treadz.console.treadmill.BeltStatus
import com.treadz.console.treadmill.ConnectionState
import com.treadz.console.treadmill.TreadmillEvent
import com.treadz.console.treadmill.control.Clock
import com.treadz.console.userdata.memory.InMemoryUserData
import com.treadz.console.userdata.workout.WorkoutHistoryObserver
import com.treadz.console.userdata.workout.WorkoutObserverConfig
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
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import org.robolectric.RobolectricTestRunner
import java.time.ZoneOffset

/**
 * The workout history observer driven by a fake [FakeTreadmill] on virtual time. Run against Room
 * (Robolectric, in-memory database) and against the in-memory implementation.
 */
@RunWith(RobolectricTestRunner::class)
abstract class WorkoutObserverContractTest {

    @After fun closeDatabases() = closeRoomDatabases()

    protected abstract fun create(clock: Clock, scheduler: TestCoroutineScheduler): UserData

    /** Everything a scenario needs; time only moves when the test says so. */
    private inner class Rig(val test: TestScope, config: WorkoutObserverConfig = WorkoutObserverConfig()) {
        val clock = Clock { T0 + test.testScheduler.currentTime }
        val data = create(clock, test.testScheduler)
        val controller = FakeTreadmill()
        val errors = mutableListOf<Throwable>()
        val scope = CoroutineScope(StandardTestDispatcher(test.testScheduler) + SupervisorJob())
        val observer: WorkoutHistoryObserver = data.workoutObserver(controller, clock, config) { errors += it }
        var userId: Long? = null

        suspend fun init(withUser: Boolean = true): Rig {
            if (withUser) userId = (data.profiles.create(validInput("Ana")) as DataResult.Success).value.id
            observer.start(scope)
            test.runCurrent()
            return this
        }

        suspend fun settle() = test.runCurrent()

        suspend fun push(s: com.treadz.console.treadmill.TreadmillState) {
            controller.state.value = s
            test.runCurrent()
        }

        private var stamp = 0L

        /** One data packet of the real controller at ~2 Hz: every packet is a different state, even with equal values. */
        suspend fun packet(s: com.treadz.console.treadmill.TreadmillState) {
            push(s.copy(updatedAtMillis = ++stamp))
            test.advanceTimeBy(500)
            test.runCurrent()
        }

        suspend fun wait(seconds: Int) {
            test.advanceTimeBy(seconds * 1000L)
            test.runCurrent()
        }

        /** One state per second, `seconds` times, with the elapsed time and distance growing. */
        suspend fun run(seconds: Int, startElapsed: Int = 0, speed: Double = 8.0, hr: Int? = null, incline: Double = 0.0) {
            for (i in 1..seconds) {
                val e = startElapsed + i
                push(state(BeltStatus.Running, speed, incline, distance = (e * speed / 3.6).toInt(), elapsed = e, calories = e / 10, hr = hr))
                wait(1)
            }
        }

        suspend fun stopBelt() {
            push(state(BeltStatus.Idle, speed = 2.0)) // decelerating
            wait(1)
            push(state(BeltStatus.Idle, speed = 0.0))
        }

        suspend fun all(): List<WorkoutSummary> = data.workouts.workouts(userId!!).first()

        suspend fun only(): WorkoutSummary = all().single()
    }

    private fun scenario(config: WorkoutObserverConfig = WorkoutObserverConfig(), withUser: Boolean = true, body: suspend Rig.() -> Unit) =
        runTest {
            val rig = Rig(this, config).init(withUser)
            try {
                rig.body()
                assertEquals("the observer must never send commands", emptyList<String>(), rig.controller.commands)
                assertEquals(emptyList<Throwable>(), rig.errors)
            } finally {
                rig.scope.cancel()
            }
        }

    // ---- Normal session ----

    @Test fun `normal session stores samples and the summary`() = scenario {
        push(state(BeltStatus.Starting, speed = 0.0))
        wait(1)
        // 5 s at 6 km/h then 5 s at 10 km/h, 3 % incline, heart rate 120..129
        for (i in 1..10) {
            val speed = if (i <= 5) 6.0 else 10.0
            push(state(BeltStatus.Running, speed, incline = if (i > 8) 5.0 else 3.0, distance = i * 2, elapsed = i, calories = i, hr = 119 + i))
            wait(1)
        }
        assertEquals(1, all().size)
        assertTrue(only().isInProgress)
        stopBelt()

        val w = only()
        assertFalse(w.isInProgress)
        assertEquals(userId, w.userId)
        assertEquals(WorkoutEndReason.Stopped, w.endReason)
        assertEquals(T0, w.startedAtMillis)
        assertEquals(T0 + 12_000, w.endedAtMillis)
        assertEquals(10, w.stats.durationSeconds)
        assertEquals(20, w.stats.distanceMeters)
        assertEquals(10, w.stats.calories)
        // moving average of the samples with the belt moving: five at 6, five at 10 and one decelerating at 2
        assertEquals((5 * 6.0 + 5 * 10.0 + 2.0) / 11, w.stats.averageSpeedKmh, 1e-9)
        assertEquals(10.0, w.stats.maxSpeedKmh, 0.0)
        assertEquals(5.0, w.stats.maxInclinePercent, 0.0)
        assertEquals(125, w.stats.averageHeartRateBpm) // mean of 120..129 = 124.5, rounded
        assertEquals(129, w.stats.maxHeartRateBpm)

        val detail = data.workouts.detail(w.id)!!
        assertEquals(w, detail.summary)
        // Starting + 10 running + 1 decelerating + the packet that reports the belt stopped
        assertEquals(13, detail.samples.size)
        assertEquals((0..12).toList(), detail.samples.map { it.seq })
        assertEquals(BeltStatus.Starting, detail.samples.first().beltStatus)
        assertEquals(2.0, detail.samples[11].speedKmh, 0.0)
        val last = detail.samples.last()
        assertEquals(BeltStatus.Idle, last.beltStatus)
        assertEquals(0.0, last.speedKmh, 0.0)
        // the treadmill zeroes distance, time and calories in that packet: the sample keeps what the session reached
        assertEquals(10, last.elapsedSeconds)
        assertEquals(20, last.distanceMeters)
        assertEquals(10, last.calories)
        val fifth = detail.samples[5]
        assertEquals(6.0, fifth.speedKmh, 0.0)
        assertEquals(5, fifth.elapsedSeconds)
        assertEquals(10, fifth.distanceMeters)
        assertEquals(124, fifth.heartRateBpm)
        assertEquals(T0 + 5_000, fifth.timestampMillis)
        assertEquals(detail.samples.map { it.timestampMillis }, detail.samples.map { it.timestampMillis }.sorted())
        assertNull(observer.activeWorkoutId.value)
    }

    // ---- Plan and fitness test link ----

    private val plan = hillPlan()

    @Test fun `a plan announced before the belt moves is attached to the session that starts`() = scenario {
        observer.linkNextSession(WorkoutLink(plan = plan))
        settle()
        push(state(BeltStatus.Starting, speed = 0.0))
        run(5)
        // while it runs, the summary already says what it is
        val live = all().single()
        assertEquals("standard-hill", live.programId)
        assertEquals(plan.source.programName, live.programName)
        stopBelt()

        val w = only()
        assertEquals("standard-hill", w.programId)
        assertNull(w.fitnessTestId)
        val detail = data.workouts.detail(w.id)!!
        assertEquals(plan, detail.plan)
        assertEquals(plan.source, detail.plan!!.source)
        assertEquals(plan.parameters, detail.plan.parameters)
    }

    @Test fun `a link announced during the session is attached to it`() = scenario {
        push(state(BeltStatus.Starting, speed = 0.0))
        run(4)
        observer.linkNextSession(WorkoutLink(fitnessTestId = TEST_1))
        run(2, startElapsed = 4)
        observer.linkNextSession(WorkoutLink(plan = plan)) // a second part merges with the first
        settle()
        stopBelt()

        val w = only()
        assertEquals(TEST_1, w.fitnessTestId)
        assertEquals("standard-hill", w.programId)
        assertEquals(plan, data.workouts.detail(w.id)!!.plan)
    }

    @Test fun `a link is for one session only`() = scenario {
        observer.linkNextSession(WorkoutLink(plan = plan))
        settle()
        push(state(BeltStatus.Starting, speed = 0.0))
        run(3)
        stopBelt()
        wait(5)
        push(state(BeltStatus.Starting, speed = 0.0))
        run(3)
        stopBelt()

        val (second, first) = all()
        assertNull(second.programId)
        assertNull(data.workouts.detail(second.id)!!.plan)
        assertEquals("standard-hill", first.programId)
    }

    @Test fun `a link that waits too long, or is cleared, is dropped`() = scenario(WorkoutObserverConfig(linkValidityMs = 30_000)) {
        observer.linkNextSession(WorkoutLink(plan = plan))
        wait(31)
        push(state(BeltStatus.Starting, speed = 0.0))
        run(3)
        stopBelt()
        assertNull(only().programId)

        wait(5)
        observer.linkNextSession(WorkoutLink(plan = plan))
        settle()
        observer.clearLink()
        settle()
        push(state(BeltStatus.Starting, speed = 0.0))
        run(3)
        stopBelt()
        assertTrue(all().all { it.programId == null })
    }

    @Test fun `a link is kept when the session ends by a lost connection`() = scenario {
        observer.linkNextSession(WorkoutLink(plan = plan, fitnessTestId = TEST_2))
        settle()
        push(state(BeltStatus.Starting, speed = 0.0))
        run(3)
        controller.connection.value = com.treadz.console.treadmill.ConnectionState.Reconnecting("AA:BB", 1)
        settle()
        val w = only()
        assertEquals(WorkoutEndReason.ConnectionLost, w.endReason)
        assertEquals("standard-hill", w.programId)
        assertEquals(TEST_2, w.fitnessTestId)
    }

    @Test fun `a workout can also be linked later by id, and linking twice changes nothing`() = scenario {
        push(state(BeltStatus.Starting, speed = 0.0))
        run(3)
        stopBelt()
        val id = only().id
        assertEquals(DataResult.Success(Unit), data.workouts.link(id, WorkoutLink(plan = plan)))
        assertEquals(DataResult.Success(Unit), data.workouts.link(id, WorkoutLink(fitnessTestId = TEST_1)))
        assertEquals(DataResult.Success(Unit), data.workouts.link(id, WorkoutLink(fitnessTestId = TEST_1)))
        assertEquals(DataResult.Success(Unit), data.workouts.link(id, WorkoutLink()))
        val w = only()
        assertEquals("standard-hill", w.programId)
        assertEquals(TEST_1, w.fitnessTestId)
        assertEquals(plan, data.workouts.detail(id)!!.plan)
        assertEquals(DataResult.NotFound, data.workouts.link(999, WorkoutLink(plan = plan)))
    }

    @Test fun `deleting a workout deletes its plan`() = scenario {
        observer.linkNextSession(WorkoutLink(plan = plan))
        settle()
        push(state(BeltStatus.Starting, speed = 0.0))
        run(3)
        stopBelt()
        val id = only().id
        data.workouts.delete(id)
        assertNull(data.workouts.detail(id))
        assertTrue(all().isEmpty())
    }

    @Test fun `a link announced before the observer started is delivered when it does`() = runTest {
        val clock = Clock { T0 + testScheduler.currentTime }
        val data = create(clock, testScheduler)
        val controller = FakeTreadmill()
        val scope = CoroutineScope(StandardTestDispatcher(testScheduler) + SupervisorJob())
        val observer = data.workoutObserver(controller, clock)
        val user = (data.profiles.create(validInput("Ana")) as DataResult.Success).value
        observer.linkNextSession(WorkoutLink(plan = plan))
        observer.start(scope)
        runCurrent()
        controller.state.value = state(BeltStatus.Starting, speed = 0.0)
        runCurrent()
        controller.state.value = state(BeltStatus.Running, 8.0, elapsed = 1, distance = 2)
        runCurrent()
        assertEquals("standard-hill", data.workouts.workouts(user.id).first().single().programId)
        observer.stop()
        scope.cancel()
    }

    @Test fun `heart rate is null in the summary without a monitor`() = scenario {
        push(state(BeltStatus.Starting, speed = 0.0))
        run(5, hr = null)
        stopBelt()
        assertNull(only().stats.averageHeartRateBpm)
        assertNull(only().stats.maxHeartRateBpm)
        assertNull(data.workouts.detail(only().id)!!.samples[2].heartRateBpm)
    }

    @Test fun `the workout is visible while it runs and flushed at the configured interval`() = scenario {
        push(state(BeltStatus.Starting, speed = 0.0))
        run(10)
        val live = data.workouts.inProgressWorkout().first()
        assertNotNull(live)
        assertEquals(live!!.id, observer.activeWorkoutId.value)
        assertTrue(live.isInProgress)
        val stored = data.workouts.detail(live.id)!!.samples
        // flushes every 3 s: at t=10 s the last flush was at 9 s, so at most the last second is still buffered
        assertTrue("stored ${stored.size}", stored.size in 9..11)
        assertTrue(live.stats.durationSeconds in 8..10)
        stopBelt()
        assertEquals(13, data.workouts.detail(live.id)!!.samples.size)
    }

    @Test fun `paused session keeps going and averages ignore the standing time`() = scenario {
        push(state(BeltStatus.Starting, speed = 0.0))
        run(4, speed = 8.0)
        for (e in 5..8) { push(state(BeltStatus.Paused, speed = 0.0, elapsed = e, distance = 8)); wait(1) }
        run(2, startElapsed = 8, speed = 8.0)
        stopBelt()
        val w = only()
        assertEquals(10, w.stats.durationSeconds)
        // 6 s at 8 km/h plus the decelerating sample at 2
        assertEquals((6 * 8.0 + 2.0) / 7, w.stats.averageSpeedKmh, 1e-9)
        assertEquals(WorkoutEndReason.Stopped, w.endReason)
    }

    @Test fun `states closer than the minimum spacing are one sample unless the status changes`() = scenario {
        push(state(BeltStatus.Starting, speed = 0.0))
        wait(1)
        push(state(BeltStatus.Running, 5.0, elapsed = 1))
        controller.state.value = state(BeltStatus.Running, 5.0, elapsed = 1, distance = 1) // 0 ms later: dropped
        settle()
        wait(1)
        push(state(BeltStatus.Running, 5.0, elapsed = 2))
        push(state(BeltStatus.Paused, 0.0, elapsed = 2)) // same instant but a status change: kept
        wait(1)
        stopBelt()
        val statuses = data.workouts.detail(only().id)!!.samples.map { it.beltStatus }
        assertEquals(
            listOf(BeltStatus.Starting, BeltStatus.Running, BeltStatus.Running, BeltStatus.Paused, BeltStatus.Idle, BeltStatus.Idle),
            statuses,
        )
    }

    // ---- The last sample is the real stop ----

    /** Running for `seconds` at 4 km/h with every packet repeated, as the real treadmill reports it. */
    private suspend fun Rig.runAt2Hz(seconds: Int, speed: Double = 4.0) {
        for (second in 1..seconds) {
            repeat(2) { packet(state(BeltStatus.Running, speed, distance = second, elapsed = second, calories = second / 5)) }
        }
    }

    @Test fun `a stop recorded from a 2 Hz stream ends with the belt really stopped`() = scenario {
        packet(state(BeltStatus.Starting, speed = 0.0))
        runAt2Hz(10)
        // Real stop: the belt reports Idle while it decelerates 0.5 km/h per second, keeping distance and time; the
        // packet that finally reports 0 km/h resets distance, time and calories.
        for (speed in listOf(3.5, 3.0, 2.5, 2.0, 1.5, 1.0, 0.5, 0.1)) {
            repeat(2) { packet(state(BeltStatus.Idle, speed, distance = 10, elapsed = 10, calories = 2).copy(targetSpeedKmh = null)) }
            assertTrue("still recording while the belt moves at $speed", only().isInProgress)
        }
        val stopAt = clock.nowMillis()
        packet(state(BeltStatus.Idle, 0.0).copy(targetSpeedKmh = null))

        val w = only()
        assertEquals(WorkoutEndReason.Stopped, w.endReason)
        val samples = data.workouts.detail(w.id)!!.samples
        val last = samples.last()
        assertEquals(BeltStatus.Idle, last.beltStatus)
        assertEquals(0.0, last.speedKmh, 0.0)
        assertEquals(stopAt, last.timestampMillis)
        assertEquals(stopAt, w.endedAtMillis)
        assertEquals("the sample before the stop is the last one still moving", 0.1, samples[samples.size - 2].speedKmh, 0.0)
        assertEquals(10, last.elapsedSeconds)
        assertEquals(10, last.distanceMeters)
        assertEquals(2, last.calories)
        assertEquals(10, w.stats.durationSeconds)
        assertEquals(10, w.stats.distanceMeters)
        assertEquals("belt standing still does not count in the average", 4.0, samples.filter { it.beltStatus == BeltStatus.Running }.map { it.speedKmh }.average(), 0.0)
        // Only the packets until the stop are stored: the Idle packets after it do not start anything.
        repeat(6) { packet(state(BeltStatus.Idle, 0.0)) }
        assertEquals(1, all().size)
        assertEquals(samples.size, data.workouts.detail(w.id)!!.samples.size)
    }

    @Test fun `the stopped sample keeps the last heart rate, not the drop the console reports in it`() = scenario {
        packet(state(BeltStatus.Starting, speed = 0.0))
        run(5, hr = 98)
        push(state(BeltStatus.Idle, speed = 2.0, distance = 10, elapsed = 5, hr = 98))
        wait(1)
        push(state(BeltStatus.Idle, speed = 0.0, hr = 77))

        val w = only()
        val last = data.workouts.detail(w.id)!!.samples.last()
        assertEquals(0.0, last.speedKmh, 0.0)
        assertEquals(98, last.heartRateBpm)
        assertEquals(98, w.stats.averageHeartRateBpm)
    }

    @Test fun `a long deceleration from a high speed is recorded to the end`() = scenario {
        packet(state(BeltStatus.Starting, speed = 0.0))
        runAt2Hz(5, speed = 18.0)
        // 18 km/h at 0.5 km/h per second takes 36 s, much longer than the time without progress that ends a session
        var speed = 17.5
        while (speed > 0.0) {
            repeat(2) { packet(state(BeltStatus.Idle, speed, distance = 5, elapsed = 5)) }
            speed = maxOf(0.0, (speed * 10 - 5).toDouble() / 10)
            if (speed == 0.0) break
        }
        assertTrue("still in progress at the end of the deceleration", only().isInProgress)
        packet(state(BeltStatus.Idle, 0.0))
        val samples = data.workouts.detail(only().id)!!.samples
        assertEquals(0.0, samples.last().speedKmh, 0.0)
        assertEquals(WorkoutEndReason.Stopped, only().endReason)
    }

    @Test fun `the belt that never reports speed 0 ends the session after a few seconds without progress`() = scenario {
        packet(state(BeltStatus.Starting, speed = 0.0))
        runAt2Hz(5)
        repeat(2) { packet(state(BeltStatus.Idle, 0.3, distance = 5, elapsed = 5)) }
        val firstIdleAt = clock.nowMillis() - 1_000
        repeat(8) { packet(state(BeltStatus.Idle, 0.3, distance = 5, elapsed = 5)) }
        assertTrue("4 s without progress: still recording", only().isInProgress)
        repeat(4) { packet(state(BeltStatus.Idle, 0.3, distance = 5, elapsed = 5)) }

        val w = only()
        assertEquals(WorkoutEndReason.Stopped, w.endReason)
        val samples = data.workouts.detail(w.id)!!.samples
        assertEquals(0.3, samples.last().speedKmh, 0.0)
        assertTrue("ended ${w.endedAtMillis} after the first idle packet at $firstIdleAt", w.endedAtMillis!! in (firstIdleAt + 5_000)..(firstIdleAt + 7_000))
        val storedAtEnd = samples.size
        repeat(4) { packet(state(BeltStatus.Idle, 0.3)) } // nothing more is stored
        assertEquals(storedAtEnd, data.workouts.detail(w.id)!!.samples.size)
    }

    @Test fun `a silent controller does not keep a decelerating session open`() = scenario {
        packet(state(BeltStatus.Starting, speed = 0.0))
        runAt2Hz(5)
        packet(state(BeltStatus.Idle, 1.0, distance = 5, elapsed = 5))
        wait(10) // no more states; the flush ticks notice
        assertEquals(WorkoutEndReason.Stopped, only().endReason)
        assertEquals(1.0, data.workouts.detail(only().id)!!.samples.last().speedKmh, 0.0)
    }

    @Test fun `a safety key removal that decelerates to 0 ends as SafetyKeyRemoved with the stopped sample last`() = scenario {
        packet(state(BeltStatus.Starting, speed = 0.0))
        runAt2Hz(5)
        repeat(2) { packet(state(BeltStatus.SafetyKeyRemoved, 3.0, distance = 5, elapsed = 5)) }
        repeat(2) { packet(state(BeltStatus.Idle, 1.5, distance = 5, elapsed = 5)) }
        packet(state(BeltStatus.Idle, 0.0))
        val w = only()
        assertEquals(WorkoutEndReason.SafetyKeyRemoved, w.endReason)
        assertEquals(0.0, data.workouts.detail(w.id)!!.samples.last().speedKmh, 0.0)
    }

    @Test fun `a connection lost while the belt decelerates still ends as ConnectionLost`() = scenario {
        packet(state(BeltStatus.Starting, speed = 0.0))
        runAt2Hz(5)
        repeat(2) { packet(state(BeltStatus.Idle, 1.5, distance = 5, elapsed = 5)) }
        controller.connection.value = ConnectionState.Reconnecting("AA:BB", 1)
        settle()
        val w = only()
        assertEquals(WorkoutEndReason.ConnectionLost, w.endReason)
        assertEquals(1.5, data.workouts.detail(w.id)!!.samples.last().speedKmh, 0.0)
    }

    // ---- How sessions end ----

    @Test fun `connection lost ends the session and the replayed stale state does not start another`() = scenario {
        push(state(BeltStatus.Starting, speed = 0.0))
        run(5)
        controller.connection.value = ConnectionState.Reconnecting("AA:BB", 1)
        controller.state.value = state(BeltStatus.Running, 8.0, distance = 11, elapsed = 5, stale = true)
        settle()
        val w = only()
        assertEquals(WorkoutEndReason.ConnectionLost, w.endReason)
        assertEquals(5, w.stats.durationSeconds)
        assertEquals(T0 + 5_000, w.endedAtMillis)
        wait(10)
        controller.state.value = state(BeltStatus.Running, 8.0, elapsed = 6, stale = true)
        settle()
        assertEquals(1, all().size) // no new session from stale data

        // After reconnecting, the treadmill is idle; a new session starts normally.
        controller.connection.value = ConnectionState.Connected("AA:BB", "FAKE")
        push(state(BeltStatus.Idle, speed = 0.0))
        push(state(BeltStatus.Starting, speed = 0.0))
        run(3)
        stopBelt()
        assertEquals(2, all().size)
    }

    @Test fun `connection lost event alone also ends the session`() = scenario {
        push(state(BeltStatus.Starting, speed = 0.0))
        run(4)
        controller.events.emit(TreadmillEvent.ConnectionLost)
        settle()
        assertEquals(WorkoutEndReason.ConnectionLost, only().endReason)
        // The connection state reporting the same loss afterwards changes nothing.
        controller.connection.value = ConnectionState.Reconnecting("AA:BB", 1)
        settle()
        assertEquals(1, all().size)
    }

    @Test fun `closing the connection ends the session`() = scenario {
        push(state(BeltStatus.Starting, speed = 0.0))
        run(4)
        controller.connection.value = ConnectionState.Disconnected
        settle()
        assertEquals(WorkoutEndReason.Disconnected, only().endReason)
    }

    @Test fun `safety key removal ends as SafetyKeyRemoved once the treadmill is idle again`() = scenario {
        push(state(BeltStatus.Starting, speed = 0.0))
        run(6)
        push(state(BeltStatus.SafetyKeyRemoved, speed = 0.0, elapsed = 6, distance = 13))
        wait(5)
        assertTrue(only().isInProgress) // still the same session while the key is out
        push(state(BeltStatus.Idle, speed = 0.0)) // key put back: the treadmill resets
        val w = only()
        assertEquals(WorkoutEndReason.SafetyKeyRemoved, w.endReason)
        assertEquals(6, w.stats.durationSeconds)
        assertEquals(13, w.stats.distanceMeters)
        val samples = data.workouts.detail(w.id)!!.samples
        assertEquals(BeltStatus.SafetyKeyRemoved, samples[samples.size - 2].beltStatus)
        assertEquals(BeltStatus.Idle, samples.last().beltStatus) // the treadmill is idle again: the last sample
        assertEquals(0.0, samples.last().speedKmh, 0.0)
    }

    @Test fun `data stored before a safety key removal is not lost if the connection drops during it`() = scenario {
        push(state(BeltStatus.Starting, speed = 0.0))
        run(6)
        push(state(BeltStatus.SafetyKeyRemoved, speed = 0.0, elapsed = 6))
        controller.connection.value = ConnectionState.Reconnecting("AA:BB", 1)
        settle()
        val w = only()
        assertEquals(WorkoutEndReason.ConnectionLost, w.endReason)
        // starting + 6 running + key removed
        assertEquals(8, data.workouts.detail(w.id)!!.samples.size)
    }

    @Test fun `two sessions in a row make two workouts`() = scenario {
        push(state(BeltStatus.Starting, speed = 0.0))
        run(5)
        stopBelt()
        // the next session starts right away
        push(state(BeltStatus.Starting, speed = 0.0))
        run(3, speed = 6.0)
        stopBelt()
        val list = all() // newest first
        assertEquals(2, list.size)
        val (second, first) = list
        assertEquals(5, first.stats.durationSeconds)
        assertEquals(3, second.stats.durationSeconds)
        assertEquals(6.0, second.stats.maxSpeedKmh, 0.0)
        assertEquals(WorkoutEndReason.Stopped, first.endReason)
        assertEquals(WorkoutEndReason.Stopped, second.endReason)
        assertTrue(second.startedAtMillis >= first.endedAtMillis!!)
        assertEquals(0, data.workouts.detail(second.id)!!.samples.first().seq)
        assertEquals(5 + 1 + 1 + 1, data.workouts.detail(first.id)!!.samples.size)
        assertEquals(3 + 1 + 1 + 1, data.workouts.detail(second.id)!!.samples.size)
    }

    @Test fun `a session that never moved the belt is discarded`() = scenario {
        push(state(BeltStatus.Starting, speed = 0.0))
        wait(2)
        push(state(BeltStatus.Idle, speed = 0.0)) // start aborted
        assertTrue(all().isEmpty())
        assertTrue(data.workouts.unassignedWorkouts().first().isEmpty())
        assertNull(data.workouts.inProgressWorkout().first())
    }

    @Test fun `stop closes the session in progress as Closed`() = scenario {
        push(state(BeltStatus.Starting, speed = 0.0))
        run(5)
        observer.stop()
        val w = only()
        assertEquals(WorkoutEndReason.Closed, w.endReason)
        assertEquals(5, w.stats.durationSeconds)
        assertEquals(6, data.workouts.detail(w.id)!!.samples.size)
        assertNull(observer.activeWorkoutId.value)
    }

    @Test fun `the observer can be started again after stop`() = scenario {
        observer.stop()
        observer.start(scope)
        settle()
        push(state(BeltStatus.Starting, speed = 0.0))
        run(3)
        stopBelt()
        assertEquals(1, all().size)
    }

    // ---- Owner ----

    @Test fun `without an active user the workout is saved unassigned and can be assigned later`() = scenario(withUser = false) {
        push(state(BeltStatus.Starting, speed = 0.0))
        run(5)
        stopBelt()
        val list = data.workouts.unassignedWorkouts().first()
        assertEquals(1, list.size)
        assertNull(list.single().userId)
        assertEquals(5, list.single().stats.durationSeconds)
        assertEquals(8, data.workouts.detail(list.single().id)!!.samples.size)

        val ana = (data.profiles.create(validInput("Ana")) as DataResult.Success).value
        assertEquals(DataResult.Success(Unit), data.workouts.assign(list.single().id, ana.id))
        assertTrue(data.workouts.unassignedWorkouts().first().isEmpty())
        assertEquals(list.single().id, data.workouts.workouts(ana.id).first().single().id)
        assertEquals(DataResult.NotFound, data.workouts.assign(list.single().id, 999))
        assertEquals(DataResult.NotFound, data.workouts.assign(999, ana.id))
    }

    @Test fun `the owner is the user active when the session starts`() = scenario {
        val ana = userId!!
        val beto = (data.profiles.create(validInput("Beto")) as DataResult.Success).value
        push(state(BeltStatus.Starting, speed = 0.0))
        run(3)
        data.profiles.setActiveUser(beto.id)
        run(3, startElapsed = 3)
        stopBelt()
        assertEquals(1, data.workouts.workouts(ana).first().size)
        assertTrue(data.workouts.workouts(beto.id).first().isEmpty())
        // the next session goes to the new active user
        push(state(BeltStatus.Starting, speed = 0.0))
        run(3)
        stopBelt()
        assertEquals(1, data.workouts.workouts(beto.id).first().size)
        assertEquals(1, data.workouts.workouts(ana).first().size)
    }

    @Test fun `deleting the owner during the session drops it without errors and recording resumes afterwards with the recreated generic profile`() = scenario {
        push(state(BeltStatus.Starting, speed = 0.0))
        run(5)
        assertEquals(DataResult.Success(Unit), data.profiles.delete(userId!!))
        run(10, startElapsed = 5) // flushes find the workout gone
        assertNull(observer.activeWorkoutId.value)
        assertTrue(data.workouts.unassignedWorkouts().first().isEmpty())
        assertNull(data.workouts.inProgressWorkout().first())
        stopBelt()
        // deleting the only profile recreated the generic one and made it active: the next session is its
        val generic = data.profiles.activeUser.first()!!
        assertTrue(generic.isGeneric)
        push(state(BeltStatus.Starting, speed = 0.0))
        run(3)
        stopBelt()
        assertEquals(1, data.workouts.workouts(generic.id).first().size)
        assertTrue(data.workouts.unassignedWorkouts().first().isEmpty())
    }

    @Test fun `deleting the workout in progress stops its recording`() = scenario {
        push(state(BeltStatus.Starting, speed = 0.0))
        run(4)
        val id = observer.activeWorkoutId.value!!
        assertEquals(DataResult.Success(Unit), data.workouts.delete(id))
        run(5, startElapsed = 4)
        assertNull(observer.activeWorkoutId.value)
        assertTrue(all().isEmpty())
    }

    // ---- Management of stored workouts ----

    @Test fun `workouts are listed newest first, per user, and can be deleted with their samples`() = scenario {
        val ana = userId!!
        val beto = (data.profiles.create(validInput("Beto")) as DataResult.Success).value
        push(state(BeltStatus.Starting, speed = 0.0)); run(3); stopBelt()
        wait(60)
        push(state(BeltStatus.Starting, speed = 0.0)); run(4); stopBelt()
        data.profiles.setActiveUser(beto.id)
        push(state(BeltStatus.Starting, speed = 0.0)); run(2); stopBelt()

        val anas = data.workouts.workouts(ana).first()
        assertEquals(listOf(4, 3), anas.map { it.stats.durationSeconds })
        assertEquals(listOf(2), data.workouts.workouts(beto.id).first().map { it.stats.durationSeconds })

        assertEquals(DataResult.Success(Unit), data.workouts.delete(anas[0].id))
        assertNull(data.workouts.detail(anas[0].id))
        assertEquals(listOf(3), data.workouts.workouts(ana).first().map { it.stats.durationSeconds })
        assertEquals(DataResult.NotFound, data.workouts.delete(anas[0].id))
    }

    @Test fun `deleting a profile deletes its workouts and samples but not the others`() = scenario {
        val ana = userId!!
        val beto = (data.profiles.create(validInput("Beto")) as DataResult.Success).value
        push(state(BeltStatus.Starting, speed = 0.0)); run(3); stopBelt()
        val anaWorkout = only()
        data.profiles.setActiveUser(beto.id)
        push(state(BeltStatus.Starting, speed = 0.0)); run(3); stopBelt()
        val betoWorkout = data.workouts.workouts(beto.id).first().single()

        data.profiles.delete(ana)
        assertNull(data.workouts.detail(anaWorkout.id))
        assertTrue(data.workouts.workouts(ana).first().isEmpty())
        assertEquals(6, data.workouts.detail(betoWorkout.id)?.samples?.size)
    }

    // ---- Crash safety ----

    @Test fun `data already flushed survives a dead process and the workout is closed as Interrupted`() = scenario {
        push(state(BeltStatus.Starting, speed = 0.0))
        run(7, hr = 140)
        // The process "dies": nothing ends the session, the observer's coroutines just stop existing.
        scope.cancel()
        settle()
        val id = data.workouts.inProgressWorkout().first()!!.id
        val survived = data.workouts.detail(id)!!
        assertTrue(survived.summary.isInProgress)
        // flushes happened at t = 3 s and 6 s: everything up to the second 5 or 6 is safe, the rest was only in memory
        assertTrue("survived ${survived.samples.size}", survived.samples.size in 6..7)
        assertEquals((0 until survived.samples.size).toList(), survived.samples.map { it.seq })
        assertEquals(140, survived.summary.stats.maxHeartRateBpm)
        assertEquals(survived.samples.last().elapsedSeconds, survived.summary.stats.durationSeconds)

        // Next process start: a new observer closes the orphan with what was flushed.
        val next = data.workoutObserver(controller, clock)
        val nextScope = CoroutineScope(StandardTestDispatcher(test.testScheduler) + SupervisorJob())
        controller.state.value = state(BeltStatus.Idle, speed = 0.0)
        next.start(nextScope)
        settle()
        val closed = data.workouts.workouts(userId!!).first().single()
        assertEquals(WorkoutEndReason.Interrupted, closed.endReason)
        assertEquals(survived.samples.last().timestampMillis, closed.endedAtMillis)
        assertEquals(survived.summary.stats, closed.stats)
        assertEquals(survived.samples, data.workouts.detail(id)!!.samples)
        assertNull(data.workouts.inProgressWorkout().first())
        nextScope.cancel()
    }

    @Test fun `no commands are sent even when everything happens`() = scenario {
        push(state(BeltStatus.Starting, speed = 0.0))
        run(3)
        controller.events.emit(TreadmillEvent.SafetyKeyRestored)
        controller.events.emit(TreadmillEvent.DataStalled)
        controller.events.emit(TreadmillEvent.ManualChange(9.0, 2.0))
        controller.state.value = state(BeltStatus.Running, 8.0, elapsed = 4, stale = true)
        settle()
        observer.stop()
        assertTrue(controller.commands.isEmpty())
    }
}

class RoomWorkoutObserverTest : WorkoutObserverContractTest() {
    override fun create(clock: Clock, scheduler: TestCoroutineScheduler): UserData =
        newRoomUserData(clock, StandardTestDispatcher(scheduler))
}

@RunWith(JUnit4::class)
class InMemoryWorkoutObserverTest : WorkoutObserverContractTest() {
    override fun create(clock: Clock, scheduler: TestCoroutineScheduler): UserData =
        InMemoryUserData(clock, ZoneOffset.UTC, seedGenericProfile = false)
}
