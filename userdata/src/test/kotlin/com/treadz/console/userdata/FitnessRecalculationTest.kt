package com.treadz.console.userdata

import com.treadz.console.analysis.samples.SampleHeartRateSource
import com.treadz.console.analysis.thresholds.ThresholdMetrics
import com.treadz.console.analysis.thresholds.ThresholdPoint
import com.treadz.console.analysis.thresholds.ThresholdQuality
import com.treadz.console.analysis.thresholds.ThresholdReason
import com.treadz.console.analysis.thresholds.ThresholdResult
import com.treadz.console.programs.fitness.FitnessTestJson
import com.treadz.console.programs.fitness.FitnessTestRecalculation
import com.treadz.console.programs.fitness.FitnessTestResult
import com.treadz.console.programs.fitness.HeartRateSourceKind
import com.treadz.console.programs.fitness.Validity
import com.treadz.console.programs.fitness.Vo2MaxMethod
import com.treadz.console.treadmill.BeltStatus
import com.treadz.console.userdata.memory.InMemoryUserData
import com.treadz.console.userdata.room.RoomUserData
import com.treadz.console.userdata.workout.WorkoutSessionStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import org.robolectric.RobolectricTestRunner
import java.time.ZoneOffset

/**
 * Recalculation of the stored fitness test results from their workouts (`FitnessRecalculator`), with synthetic data
 * only: the maximal walking test (VO2max and thresholds) and the maximal running test (thresholds), with RR intervals,
 * without them (console, unknown source), without the workout and when it arrives later.
 */
@RunWith(RobolectricTestRunner::class)
abstract class FitnessRecalculationTest {
    protected val clock = MutableClock()
    @After fun closeDatabases() = closeRoomDatabases()

    protected abstract fun create(): UserData
    protected val data by lazy { create() }

    protected fun <T> DataResult<T>.ok(): T = (this as? DataResult.Success)?.value ?: error("expected success but was $this")

    protected suspend fun UserData.ana() = profiles.create(validInput()).ok()

    protected suspend fun UserData.finishedWorkoutOf(user: UserProfile, testId: String, samples: List<WorkoutSample>, startedAt: Long = T0): Long {
        val id = addFinishedWorkout(user.id, startedAt, samples, WorkoutStats(durationSeconds = samples.size, distanceMeters = 100))
        workouts.link(id, WorkoutLink(fitnessTestId = testId)).ok()
        return id
    }

    /** What the pure calculation gives for [result] and the stored [samples] (and [resting]). */
    protected fun expected(result: FitnessTestResult, samples: List<WorkoutSample>?, resting: Int? = null): FitnessTestResult =
        FitnessTestRecalculation.recalculate(result, samples?.let(FitnessRecalculationInputs::samples), resting)!!

    protected suspend fun UserData.stored(testId: String) = fitnessTests.get(testId)!!

    protected fun FitnessTestResult.reason() = thresholds?.reason

    // ------------------------------------------------------------------ the maximal walking test

    @Test fun `a walking result gets its VO2max and thresholds from the samples with RR, once`() = runBlocking<Unit> {
        val u = data.ana()
        val old = balkeResult(TEST_3, T0)
        assertNull(old.outputs)
        assertNull(old.thresholds)
        assertEquals(1, old.schemaVersion)
        data.fitnessTests.save(u.id, old).ok()
        val samples = SyntheticTestWorkout.walking()
        data.finishedWorkoutOf(u, TEST_3, samples)

        assertEquals(1, data.fitnessRecalculator.recalculate())
        val record = data.stored(TEST_3)
        val result = record.result
        assertEquals(expected(old, samples), result)
        assertEquals(FitnessTestResult.SCHEMA_VERSION, result.schemaVersion)

        val outputs = checkNotNull(result.outputs)
        assertEquals(Vo2MaxMethod.AcsmWalkRamp, outputs.method)
        assertTrue(outputs.masIsEstimated)
        assertEquals(3.5, outputs.vo2maxSeeMlKgMin, 0.0)
        assertEquals(outputs.vo2maxMlKgMin / 3.5, checkNotNull(outputs.masKmh), 0.01)
        assertEquals(186, outputs.hrMaxCandidateBpm)
        // the mean real load of the last minute: 5.5 km/h at about 14.5 % incline
        assertEquals(36.6, outputs.vo2maxMlKgMin, 1.0)
        // everything else is untouched
        assertEquals(old.copy(outputs = null, thresholds = null, schemaVersion = 2), result.copy(outputs = null, thresholds = null))

        val thresholds = checkNotNull(result.thresholds)
        assertEquals(FitnessTestRecalculation.ALGORITHM_VERSION, thresholds.algorithmVersion)
        assertNull(thresholds.reason)
        val vt1 = checkNotNull(thresholds.vt1)
        assertTrue("${vt1.heartRateBpm}", vt1.heartRateBpm in 120.0..160.0)
        assertNotNull(vt1.speedKmh)
        assertNotNull(vt1.percentVo2max)
        assertNull("no resting heart rate measured", vt1.percentHeartRateReserve)

        // idempotent: the second run changes nothing, not even the version of the record
        clock.nowMillis += 60_000
        assertEquals(0, data.fitnessRecalculator.recalculate())
        assertEquals(record, data.stored(TEST_3))
    }

    @Test fun `without RR intervals the thresholds have their reason and the walking VO2max still comes from the samples`() = runBlocking<Unit> {
        val cases = listOf(
            "console" to SyntheticTestWorkout.walking(source = HeartRateSourceKind.TreadmillConsole),
            "unknown source (imported)" to SyntheticTestWorkout.walking(source = null),
            "strap without RR" to SyntheticTestWorkout.walking(withRr = false),
        )
        for ((name, samples) in cases) {
            val d = create()
            val u = d.ana()
            val old = balkeResult(TEST_3, T0)
            d.fitnessTests.save(u.id, old).ok()
            d.finishedWorkoutOf(u, TEST_3, samples)
            assertEquals(name, 1, d.fitnessRecalculator.recalculate())
            val result = d.stored(TEST_3).result
            assertEquals(name, ThresholdReason.NoRrIntervals, result.reason())
            assertNull(name, result.thresholds!!.vt1)
            assertEquals(name, expected(old, samples), result)
            assertEquals(name, Vo2MaxMethod.AcsmWalkRamp, result.outputs!!.method)
            assertEquals(name, 36.6, result.outputs!!.vo2maxMlKgMin, 1.0)
            assertEquals(name, 0, d.fitnessRecalculator.recalculate())
        }
    }

    @Test fun `a result without its workout is analysed from the stage records and again when the workout arrives`() = runBlocking<Unit> {
        val u = data.ana()
        val old = balkeResult(TEST_3, T0)
        data.fitnessTests.save(u.id, old).ok()

        assertEquals(1, data.fitnessRecalculator.recalculate())
        val first = data.stored(TEST_3)
        assertEquals(ThresholdReason.NoWorkoutSamples, first.result.reason())
        assertEquals(expected(old, null), first.result)
        val fromStages = checkNotNull(first.result.outputs)
        assertEquals(Vo2MaxMethod.AcsmWalkRamp, fromStages.method)
        // the last minute of the stored stages: 5.5 km/h at 6 %
        assertEquals(22.6, fromStages.vo2maxMlKgMin, 0.3)

        // nothing to do, and nothing retried, while the workout is missing
        clock.nowMillis += 60_000
        assertEquals(0, data.fitnessRecalculator.recalculate())
        assertEquals(first, data.stored(TEST_3))

        // the workout arrives (a synchronisation brought it): the full analysis replaces the first one
        val samples = SyntheticTestWorkout.walking()
        data.finishedWorkoutOf(u, TEST_3, samples)
        assertEquals(1, data.fitnessRecalculator.recalculate())
        val second = data.stored(TEST_3)
        assertEquals(expected(first.result, samples), second.result)
        assertNull(second.result.reason())
        assertNotNull(second.result.thresholds!!.vt1)
        assertNotEquals(fromStages.vo2maxMlKgMin, second.result.outputs!!.vo2maxMlKgMin, 5.0)
        assertTrue(second.updatedAtMillis > first.updatedAtMillis)

        clock.nowMillis += 60_000
        assertEquals(0, data.fitnessRecalculator.recalculate())
        assertEquals(second, data.stored(TEST_3))
    }

    @Test fun `stale samples are left out`() = runBlocking<Unit> {
        val u = data.ana()
        val old = balkeResult(TEST_3, T0)
        data.fitnessTests.save(u.id, old).ok()
        val clean = SyntheticTestWorkout.walking(source = HeartRateSourceKind.TreadmillConsole)
        // the last 40 s of the effort are replayed values of a lost connection: a standstill at 0 incline
        val withStale = clean.map { if (it.seq >= 680) it.copy(speedKmh = 0.0, inclinePercent = 0.0, isStale = true) else it }
        data.finishedWorkoutOf(u, TEST_3, withStale)
        assertEquals(1, data.fitnessRecalculator.recalculate())
        val result = data.stored(TEST_3).result
        assertEquals(expected(old, withStale.filter { !it.isStale }), result)
        // if the stale samples counted, the load of the last minute would be far lower
        val counted = FitnessTestRecalculation.recalculate(old, FitnessRecalculationInputs.samples(withStale.map { it.copy(isStale = false) }), null)!!
        assertTrue(counted.outputs!!.vo2maxMlKgMin < result.outputs!!.vo2maxMlKgMin - 5)
    }

    // ------------------------------------------------------------------ the maximal running test

    @Test fun `a running result gets thresholds only and keeps its outputs`() = runBlocking<Unit> {
        val u = data.ana()
        val old = maxTestResult(TEST_2, T0)
        data.fitnessTests.save(u.id, old).ok()
        val samples = SyntheticTestWorkout.running()
        data.finishedWorkoutOf(u, TEST_2, samples)

        assertEquals(1, data.fitnessRecalculator.recalculate())
        val result = data.stored(TEST_2).result
        assertEquals(expected(old, samples), result)
        assertEquals(old.outputs, result.outputs)
        assertEquals(Vo2MaxMethod.LegerMercier1984, result.outputs!!.method)
        assertEquals(false, result.outputs!!.masIsEstimated)
        assertNull(result.reason())
        assertNotNull(result.thresholds!!.vt1)
        assertEquals(FitnessTestResult.SCHEMA_VERSION, result.schemaVersion)
        assertEquals(0, data.fitnessRecalculator.recalculate())
    }

    @Test fun `a running result without RR or without a workout has the reason`() = runBlocking<Unit> {
        val u = data.ana()
        data.fitnessTests.save(u.id, maxTestResult(TEST_2, T0)).ok()
        data.fitnessTests.save(u.id, maxTestResult(TEST_1, T0 - 10 * DAY_MS)).ok()
        data.finishedWorkoutOf(u, TEST_2, SyntheticTestWorkout.running(source = HeartRateSourceKind.TreadmillConsole))
        assertEquals(2, data.fitnessRecalculator.recalculate())
        assertEquals(ThresholdReason.NoRrIntervals, data.stored(TEST_2).result.reason())
        assertEquals(ThresholdReason.NoWorkoutSamples, data.stored(TEST_1).result.reason())
        assertEquals(maxTestResult(TEST_1, T0 - 10 * DAY_MS).outputs, data.stored(TEST_1).result.outputs)
    }

    // ------------------------------------------------------------------ what is left alone

    @Test fun `results that need nothing are not touched`() = runBlocking<Unit> {
        val u = data.ana()
        val current = ThresholdResult.none(ThresholdReason.NoRrIntervals)
        val keep = listOf(
            ebbelingResult(TEST_1, T0 - 5 * DAY_MS),
            balkeResult(TEST_2, T0 - 4 * DAY_MS, validity = Validity.Invalid),
            balkeResult(TEST_3, T0 - 3 * DAY_MS).copy(thresholds = current),
            maxTestResult("0b6f8f9e-4d0c-4d43-9f43-2f0c6f1a7b04", T0 - 2 * DAY_MS)
                .copy(thresholds = ThresholdResult(FitnessTestRecalculation.ALGORITHM_VERSION + 1, reason = ThresholdReason.NoAlpha1Decline)),
        )
        keep.forEach { data.fitnessTests.save(u.id, it).ok() }
        data.finishedWorkoutOf(u, TEST_3, SyntheticTestWorkout.walking())
        val before = data.fitnessTests.results(u.id).first()
        clock.nowMillis += 60_000
        assertEquals(0, data.fitnessRecalculator.recalculate())
        assertEquals(before, data.fitnessTests.results(u.id).first())
    }

    @Test fun `a result with an older algorithm version is recalculated`() = runBlocking<Unit> {
        val u = data.ana()
        val old = balkeResult(TEST_3, T0).copy(thresholds = ThresholdResult(FitnessTestRecalculation.ALGORITHM_VERSION - 1, reason = ThresholdReason.NoRrIntervals))
        data.fitnessTests.save(u.id, old).ok()
        val samples = SyntheticTestWorkout.walking()
        data.finishedWorkoutOf(u, TEST_3, samples)
        assertEquals(1, data.fitnessRecalculator.recalculate())
        val result = data.stored(TEST_3).result
        assertEquals(FitnessTestRecalculation.ALGORITHM_VERSION, result.thresholds!!.algorithmVersion)
        assertNotNull(result.thresholds!!.vt1)
        assertEquals(0, data.fitnessRecalculator.recalculate())
    }

    @Test fun `a deleted result is not recalculated and the deleted workout does not count`() = runBlocking<Unit> {
        val u = data.ana()
        data.fitnessTests.save(u.id, balkeResult(TEST_3, T0)).ok()
        data.fitnessTests.save(u.id, balkeResult(TEST_2, T0 - DAY_MS)).ok()
        val workout = data.finishedWorkoutOf(u, TEST_2, SyntheticTestWorkout.walking(T0 - DAY_MS))
        data.workouts.delete(workout).ok()
        data.fitnessTests.delete(TEST_3).ok()
        assertEquals(1, data.fitnessRecalculator.recalculate())
        assertEquals(ThresholdReason.NoWorkoutSamples, data.stored(TEST_2).result.reason())
        assertNull(data.fitnessTests.get(TEST_3))
    }

    // ------------------------------------------------------------------ deterministic resting heart rate

    private suspend fun recalculatedWith(restingMeasurements: List<Pair<Long, Int>>): FitnessTestResult {
        val d = create()
        val u = d.ana()
        clock.nowMillis = T0 + 30 * DAY_MS
        for ((at, bpm) in restingMeasurements) d.restingHeartRates.add(u.id, RestingHeartRateInput(at, bpm, RestingHeartRateSource.Manual)).ok()
        d.fitnessTests.save(u.id, balkeResult(TEST_3, T0)).ok()
        d.finishedWorkoutOf(u, TEST_3, SyntheticTestWorkout.walking())
        assertEquals(1, d.fitnessRecalculator.recalculate())
        return d.stored(TEST_3).result
    }

    private fun FitnessTestResult.reserve() = checkNotNull(thresholds!!.vt1).percentHeartRateReserve

    private fun FitnessTestResult.expectedReserve(resting: Int): Double {
        val vt1 = checkNotNull(thresholds!!.vt1).heartRateBpm
        return (vt1 - resting) / (inputs.hrMaxBpm - resting) * 100.0
    }

    @Test fun `the resting heart rate is the reference at the start of the test, whatever was measured afterwards`() = runBlocking<Unit> {
        val before = T0 - 5 * DAY_MS
        val a = recalculatedWith(listOf(before to 60))
        // a later measurement on another device (or the same one, later) does not change the document
        val b = recalculatedWith(listOf(before to 60, T0 + 3 * DAY_MS to 80))
        assertEquals(a, b)
        assertEquals(a.expectedReserve(60), checkNotNull(a.reserve()), 0.15)

        // the median of the measurements of the 30 days up to the test
        val median = recalculatedWith(listOf(T0 - 20 * DAY_MS to 70, T0 - 10 * DAY_MS to 60, before to 64, T0 + DAY_MS to 90))
        assertEquals(median.expectedReserve(64), checkNotNull(median.reserve()), 0.15)

        // only measurements after the test: the earliest one stands for the user then
        val after = recalculatedWith(listOf(T0 + 9 * DAY_MS to 77, T0 + 3 * DAY_MS to 80))
        assertEquals(after.expectedReserve(80), checkNotNull(after.reserve()), 0.15)
        assertEquals(after, recalculatedWith(listOf(T0 + 3 * DAY_MS to 80)))

        // no measurement: no % of the reserve
        assertNull(recalculatedWith(emptyList()).reserve())
    }

    // ------------------------------------------------------------------ persistence and the profile

    @Test fun `thresholds and the walking outputs round trip through storage`() = runBlocking<Unit> {
        val u = data.ana()
        val point = ThresholdPoint(132.4, 5.5, 11.3, 31.6, 72.5, 61.0)
        val thresholds = ThresholdResult(
            algorithmVersion = 1,
            vt1 = point,
            vt2 = ThresholdPoint(151.2, 5.5, 14.2, 38.4, 88.1),
            quality = ThresholdQuality.Medium,
            metrics = ThresholdMetrics(31, 48, 50, 0.82, 1.2, -0.021, 3.53),
        )
        val walking = expected(balkeResult(TEST_3, T0), null).copy(thresholds = thresholds)
        assertEquals(2, walking.schemaVersion)
        val reasons = ThresholdReason.entries.mapIndexed { i, reason ->
            maxTestResult("0b6f8f9e-4d0c-4d43-9f43-2f0c6f1a8${i}00", T0 - (i + 1) * DAY_MS)
                .copy(thresholds = ThresholdResult(1, quality = ThresholdQuality.Low.takeIf { reason == ThresholdReason.NotEnoughValidWindows }, reason = reason), schemaVersion = 2)
        }
        (listOf(walking) + reasons).forEach { r ->
            val saved = data.fitnessTests.save(u.id, r).ok()
            assertEquals(r, saved.result)
            assertEquals(r, data.fitnessTests.get(r.id)!!.result)
            assertEquals(r, FitnessTestJson.decode(FitnessTestJson.encode(r)))
        }
        val read = data.fitnessTests.results(u.id).first().associateBy { it.id }
        assertEquals(walking, read.getValue(TEST_3).result)
        assertEquals(Vo2MaxMethod.AcsmWalkRamp, read.getValue(TEST_3).result.outputs!!.method)
        assertTrue(read.getValue(TEST_3).result.outputs!!.masIsEstimated)
        assertEquals(thresholds, read.getValue(TEST_3).result.thresholds)
    }

    @Test fun `the fitness profile combines the walking VO2max, the submaximal factor, the discrepancy and the thresholds`() = runBlocking<Unit> {
        val u = data.ana()
        data.fitnessTests.save(u.id, balkeResult(TEST_3, T0)).ok()
        data.finishedWorkoutOf(u, TEST_3, SyntheticTestWorkout.walking())
        data.fitnessTests.save(u.id, ebbelingResult(TEST_1, T0 - 2 * DAY_MS, vo2max = 60.0)).ok()
        assertEquals(1, data.fitnessRecalculator.recalculate())
        val walking = data.stored(TEST_3).result

        val profile = checkNotNull(data.fitnessTests.fitnessProfile(u.id).first())
        val vo2 = checkNotNull(profile.vo2max)
        // the Ebbeling source weighs 1/4 while a valid maximal test of the last 90 days exists
        assertEquals(listOf(1.0, 0.25), vo2.sources.map { it.weightFactor })
        assertEquals(listOf(TEST_3, TEST_1), vo2.sources.map { it.resultId })
        assertTrue(vo2.discrepancy)
        assertEquals(listOf(true, true), vo2.sources.map { it.discrepant })
        val w = vo2.sources.first().weight
        assertTrue("$w", w > 0.5)
        // the walking MAS is an estimate and never the profile's measured MAS
        assertEquals(true, checkNotNull(profile.mas).estimated)
        val vt1 = checkNotNull(profile.thresholds?.vt1)
        assertEquals(checkNotNull(walking.thresholds!!.vt1).heartRateBpm, vt1.heartRateBpm.toDouble(), 0.5)
        assertEquals(listOf(TEST_3), vt1.sources.map { it.resultId })
        assertEquals(1, profile.thresholds!!.history.size)

        // the document written to profile.json carries the new optional fields
        val json = FitnessTestJson.encodeProfile(profile)
        assertTrue(json, json.contains("\"discrepancy\": true"))
        assertTrue(json, json.contains("\"thresholds\""))
        assertTrue(json, json.contains("\"submaximalFactorWithMaximal\""))
        assertEquals(profile.thresholds, FitnessTestJson.decodeProfile(json).thresholds)
        assertTrue(FitnessTestJson.decodeProfile(json).vo2max!!.discrepancy)

        val live = checkNotNull(data.fitnessTests.liveFitnessProfile(u.id).first())
        assertEquals(vo2.sources.map { it.weightFactor }, live.vo2max!!.sources.map { it.weightFactor })
        assertEquals(profile.thresholds?.vt1?.heartRateBpm, live.thresholds?.vt1?.heartRateBpm)
    }

    @Test fun `a result with only thresholds is usable for the snapshot`() {
        val onlyThresholds = balkeResult(TEST_3, T0, peak = null).copy(
            thresholds = ThresholdResult(1, vt1 = ThresholdPoint(130.0), quality = ThresholdQuality.High),
        )
        assertNull(onlyThresholds.outputs)
        assertNull(onlyThresholds.measuredHrMaxCandidateBpm)
        val profile = checkNotNull(FitnessProfiles.snapshot(listOf(onlyThresholds), Sex.Male, java.time.LocalDate.of(1986, 10, 8)))
        assertEquals(130, profile.thresholds!!.vt1!!.heartRateBpm)
        assertNull(profile.vo2max)
        assertNull(FitnessProfiles.snapshot(listOf(onlyThresholds.copy(thresholds = ThresholdResult.none(ThresholdReason.NoRrIntervals))), Sex.Male, java.time.LocalDate.of(1986, 10, 8)))
    }
}

class RoomFitnessRecalculationTest : FitnessRecalculationTest() {
    override fun create(): UserData = newRoomUserData(clock)

    @Test fun `a workout still in progress postpones the result and the recalculated document is the one to upload`() = runBlocking<Unit> {
        val room = data as RoomUserData
        val u = data.ana()
        val old = balkeResult(TEST_3, T0)
        data.fitnessTests.save(u.id, old).ok()
        val store = data.workouts as WorkoutSessionStore
        val id = store.begin(u.id, T0, WorkoutLink(fitnessTestId = TEST_3))
        assertEquals(0, data.fitnessRecalculator.recalculate())
        assertEquals(old, data.stored(TEST_3).result)

        val samples = SyntheticTestWorkout.walking()
        store.finish(id, samples, WorkoutStats(durationSeconds = 720, distanceMeters = 100), T0 + 720_000, WorkoutEndReason.Stopped)
        assertEquals(1, data.fitnessRecalculator.recalculate())
        val result = data.stored(TEST_3).result
        assertNull(result.reason())
        // the pending document is the recalculated result, saved once
        val doc = room.fitnessTestDocuments.pending().single()
        assertEquals(result, FitnessTestJson.decode(doc.json))
        assertTrue(doc.json, doc.json.contains("\"schemaVersion\": 2"))
        assertTrue(doc.json, doc.json.contains("\"acsmWalkRamp\""))
        assertTrue(doc.json, doc.json.contains("\"masIsEstimated\": true"))
        assertTrue(doc.json, doc.json.contains("\"thresholds\""))
    }

    @Test fun `a result written by a newer app is skipped and kept as it is`() = runBlocking<Unit> {
        val room = data as RoomUserData
        val u = data.ana()
        val userUuid = room.sync.pendingChanges().profiles.single { it.name == u.name }.uuid
        val newer = FitnessTestJson.encode(balkeResult(TEST_3, T0)).replace("\"schemaVersion\": 1", "\"schemaVersion\": 99")
        room.fitnessTestDocuments.applyRemote(
            listOf(com.treadz.console.userdata.sync.FitnessTestDocument(TEST_3, userUuid, com.treadz.console.userdata.sync.RecordVersion(T0 + 10_000, "other"), false, newer)),
        )
        assertNull(data.fitnessTests.get(TEST_3))
        assertEquals(0, data.fitnessRecalculator.recalculate())
        assertNull(data.fitnessTests.get(TEST_3))
        assertTrue(room.fitnessTestDocuments.pending().isEmpty())
    }
}

@RunWith(JUnit4::class)
class InMemoryFitnessRecalculationTest : FitnessRecalculationTest() {
    override fun create(): UserData = InMemoryUserData(clock, ZoneOffset.UTC, seedGenericProfile = false)
}

/** The pure inputs of the recalculation. */
class FitnessRecalculationInputsTest {

    private fun stored(seq: Int, hr: Int?, source: HeartRateSourceKind?, stale: Boolean = false, rr: List<Int> = emptyList()) = WorkoutSample(
        seq, T0 + seq * 1000L, seq + 1, 6.0, 6.0, 8.0, seq, 0, hr, BeltStatus.Running, isStale = stale, heartRateSource = source, rrIntervalsMs = rr,
    )

    @Test fun `samples are mapped to the neutral type, without the stale ones`() {
        val mapped = FitnessRecalculationInputs.samples(
            listOf(
                stored(0, 140, HeartRateSourceKind.DirectStrap, rr = listOf(430, 428)),
                stored(1, 141, HeartRateSourceKind.TreadmillConsole),
                stored(2, 142, null),
                stored(3, 0, HeartRateSourceKind.DirectStrap, rr = listOf(500)),
                stored(4, null, null),
                stored(5, 150, HeartRateSourceKind.DirectStrap, stale = true),
            ),
        )
        assertEquals(listOf(0, 1, 2, 3, 4).map { T0 + it * 1000L }, mapped.map { it.timeMillis })
        assertEquals(listOf(140, 141, 142, null, null), mapped.map { it.heartRateBpm })
        assertEquals(
            listOf(SampleHeartRateSource.Strap, SampleHeartRateSource.Console, SampleHeartRateSource.Unknown, null, null),
            mapped.map { it.heartRateSource },
        )
        assertEquals(listOf(430, 428), mapped[0].rrIntervalsMs)
        assertEquals(6.0, mapped[0].speedKmh, 0.0)
        assertEquals(8.0, mapped[0].inclinePercent, 0.0)
    }

    private fun p(daysFromTest: Double, bpm: Int) = RestingPoint(T0 + (daysFromTest * DAY_MS).toLong(), bpm)

    @Test fun `the resting heart rate is evaluated at the start of the test`() {
        fun at(vararg points: RestingPoint) = FitnessRecalculationInputs.restingAt(points.toList(), T0)
        assertNull(at())
        // the median of the 30 days up to the test; later measurements are ignored
        assertEquals(62, at(p(-2.0, 60), p(-9.0, 64), p(-20.0, 62), p(1.0, 90)))
        // 31 days before the test is out of the window: the latest one before it (stale reference)
        assertEquals(66, at(p(-40.0, 70), p(-31.0, 66), p(2.0, 50)))
        // exactly at the start counts
        assertEquals(58, at(p(0.0, 58), p(-1.0, 70), p(-2.0, 50), p(5.0, 99)))
        // only later measurements: the earliest (and the lowest of the same instant)
        assertEquals(80, at(p(9.0, 77), p(3.0, 80), p(3.0, 85)))
        // the order of the list does not matter
        assertEquals(at(p(-2.0, 60), p(-9.0, 64), p(1.0, 90)), FitnessRecalculationInputs.restingAt(listOf(p(1.0, 90), p(-9.0, 64), p(-2.0, 60)), T0))
    }
}
