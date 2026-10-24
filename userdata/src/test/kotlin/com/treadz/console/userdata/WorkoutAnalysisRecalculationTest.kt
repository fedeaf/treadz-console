package com.treadz.console.userdata

import com.treadz.console.analysis.workout.AnalysisReason
import com.treadz.console.analysis.workout.WorkoutAnalysis
import com.treadz.console.analysis.workout.WorkoutAnalyzer
import com.treadz.console.programs.fitness.HeartRateSourceKind
import com.treadz.console.programs.fitness.Validity
import com.treadz.console.userdata.memory.InMemoryUserData
import com.treadz.console.userdata.room.RoomUserData
import com.treadz.console.userdata.room.UserDataDatabase
import com.treadz.console.userdata.workout.WorkoutSessionStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import org.robolectric.RobolectricTestRunner
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * The recalculation of the workout analyses (`WorkoutAnalysisRecalculator`) with synthetic workouts, against Room
 * (Robolectric) and against the in-memory implementation: first run, idempotence, recomputation when the context of a
 * past workout changes (and only then), what gets no analysis, deletion and the trend inputs of the UI.
 */
@RunWith(RobolectricTestRunner::class)
abstract class WorkoutAnalysisRecalculationTest {
    protected val clock = MutableClock()
    protected val utc: ZoneId = ZoneOffset.UTC

    @After fun closeDatabases() = closeRoomDatabases()

    protected abstract fun create(): UserData
    protected val data by lazy { create() }

    protected fun <T> DataResult<T>.ok(): T = (this as? DataResult.Success)?.value ?: error("expected success but was $this")

    protected suspend fun ana() = data.profiles.create(validInput()).ok()

    protected suspend fun finished(userId: Long?, startedAt: Long, seconds: Int = 600, samples: List<WorkoutSample> = steadySamples(startedAt, seconds)): Long =
        data.addFinishedWorkout(userId, startedAt, samples, WorkoutStats(durationSeconds = seconds, distanceMeters = seconds * 2))

    protected suspend fun recalculate() = data.workoutAnalysisRecalculator.recalculate()

    protected suspend fun analysisOf(workoutId: Long) = data.workoutAnalysis.analysis(workoutId).first()

    /** What the pure functions give for the stored data: the recalculation must store exactly this. */
    protected suspend fun expectedAnalysis(userId: Long, workoutId: Long): WorkoutAnalysis {
        val detail = data.workouts.detail(workoutId)!!
        val inputs = AnalysisUserInputs(
            data.profiles.profile(userId).first()!!,
            data.weights.entries(userId).first(),
            data.fitnessTests.results(userId).first().map { it.result },
            data.restingHeartRates.measurements(userId).first().map { RestingPoint(it.measuredAtMillis, it.bpm) },
        )
        return WorkoutAnalyzer.analyse(FitnessRecalculationInputs.samples(detail.samples), WorkoutAnalysisContexts.build(inputs, detail.summary.startedAtMillis, utc))
    }

    private fun epochDay(millis: Long) = java.time.Instant.ofEpochMilli(millis).atZone(utc).toLocalDate().toEpochDay()

    // ------------------------------------------------------------------ first run and idempotence

    @Test fun `the first run computes the analysis of every finished workout, with the context of its date`() = runBlocking<Unit> {
        val u = ana()
        data.weights.add(u.id, LocalDate.of(2026, 8, 1), 70.0).ok()
        val w1 = finished(u.id, T0 - 30 * DAY_MS)
        val w2 = finished(u.id, T0 - 10 * DAY_MS, seconds = 900)
        assertNull("not computed yet", analysisOf(w1))
        assertEquals(emptyList<DatedWorkoutAnalysis>(), data.workoutAnalysis.analyses(u.id).first())

        assertEquals(2, recalculate())

        val first = analysisOf(w1)!!
        assertEquals(w1, first.workoutId)
        assertEquals(T0, first.computedAtMillis)
        assertEquals(WorkoutAnalyzer.ALGORITHM_VERSION, first.analysis.algorithmVersion)
        assertEquals(expectedAnalysis(u.id, w1), first.analysis)
        assertEquals(expectedAnalysis(u.id, w2), analysisOf(w2)!!.analysis)
        // the weight of that date gave the energy in kcal, the zones used are in the analysis
        assertNotNull(first.analysis.energy.value!!.grossKcal)
        assertNotNull(first.analysis.zoneTime.value!!.zones)
        assertEquals(600, first.analysis.signal.sampleCount)

        // the user's list: oldest first, with the local day, ready for the trends
        val list = data.workoutAnalysis.analyses(u.id).first()
        assertEquals(listOf(w1, w2), list.map { it.workoutId })
        assertEquals(listOf(T0 - 30 * DAY_MS, T0 - 10 * DAY_MS), list.map { it.startedAtMillis })
        assertEquals(listOf(epochDay(T0 - 30 * DAY_MS), epochDay(T0 - 10 * DAY_MS)), list.map { it.epochDay })
        assertEquals(list.map { com.treadz.console.analysis.trends.DatedAnalysis(it.epochDay, it.analysis) }, list.map { it.dated })
        assertEquals(emptyList<DatedWorkoutAnalysis>(), data.workoutAnalysis.analyses(u.id + 1).first())
        assertNull(analysisOf(999))
    }

    @Test fun `the second run computes nothing and leaves the stored analyses as they are`() = runBlocking<Unit> {
        val u = ana()
        val w = finished(u.id, T0 - DAY_MS)
        finished(u.id, T0)
        assertEquals(2, recalculate())
        val before = analysisOf(w)
        clock.nowMillis += 60_000
        assertEquals(0, recalculate())
        assertEquals(0, recalculate())
        assertEquals(before, analysisOf(w))
    }

    @Test fun `missing data is data, not an error`() = runBlocking<Unit> {
        val u = ana()
        val noHr = finished(u.id, T0 - DAY_MS, samples = samplesWithoutHeartRate(T0 - DAY_MS))
        val console = finished(u.id, T0 - 2 * DAY_MS, samples = steadySamples(T0 - 2 * DAY_MS, 300, source = HeartRateSourceKind.TreadmillConsole))
        val tiny = finished(u.id, T0 - 3 * DAY_MS, samples = steadySamples(T0 - 3 * DAY_MS, 1))
        assertEquals(3, recalculate())
        val a = analysisOf(noHr)!!.analysis
        assertNotNull("the energy needs no heart rate", a.energy.value)
        assertEquals(AnalysisReason.NoHeartRate, a.trimp.reason)
        assertEquals(AnalysisReason.NoHeartRate, a.zoneTime.reason)
        assertEquals(AnalysisReason.NoHeartRate, a.alpha1.reason)
        val c = analysisOf(console)!!.analysis
        assertNotNull(c.trimp.value)
        assertEquals("a console has no RR intervals", AnalysisReason.NoRrIntervals, c.alpha1.reason)
        assertEquals(AnalysisReason.NoSamples, analysisOf(tiny)!!.analysis.energy.reason)
        assertEquals(0, recalculate())
    }

    // ------------------------------------------------------------------ what recomputes it

    @Test fun `a change that alters the context of a past workout recomputes it, and nothing else does`() = runBlocking<Unit> {
        val u = ana()
        data.weights.add(u.id, LocalDate.of(2026, 8, 1), 80.0).ok()
        val w1 = finished(u.id, T0 - 30 * DAY_MS) // 2026-09-07
        val w2 = finished(u.id, T0 - 10 * DAY_MS) // 2026-09-27
        val w3 = finished(u.id, T0) // 2026-10-07
        assertEquals(3, recalculate())
        val original = listOf(w1, w2, w3).map { analysisOf(it)!!.analysis }

        // a weight between the first and the second workout: the other two have it as their latest weight
        data.weights.add(u.id, LocalDate.of(2026, 9, 20), 78.0).ok()
        assertEquals(2, recalculate())
        assertEquals(original[0], analysisOf(w1)!!.analysis)
        assertEquals(78.0 * (original[1].energy.value!!.grossKcal!! / 80.0) / 1.0, analysisOf(w2)!!.analysis.energy.value!!.grossKcal!!, 1e-6)
        assertEquals(expectedAnalysis(u.id, w3), analysisOf(w3)!!.analysis)

        // an older weight that is nobody's nearest one
        data.weights.add(u.id, LocalDate.of(2026, 7, 1), 90.0).ok()
        assertEquals(0, recalculate())

        // the first resting measurement is the best figure for every workout before it too (the earliest one)
        data.restingHeartRates.add(u.id, RestingHeartRateInput(T0 - 5 * DAY_MS, 60, RestingHeartRateSource.Manual)).ok()
        assertEquals(3, recalculate())
        // a second one changes the reference at the last workout only
        data.restingHeartRates.add(u.id, RestingHeartRateInput(T0 - DAY_MS, 50, RestingHeartRateSource.Manual)).ok()
        assertEquals(1, recalculate())
        assertEquals(55, expectedContext(u.id, w3).restingHeartRateBpm)

        // a test taken between the first and the second workout: the maximum heart rate and VO2max of the other two
        data.fitnessTests.save(u.id, maxTestResult(TEST_2, T0 - 20 * DAY_MS, peak = 185)).ok()
        assertEquals(2, recalculate())
        assertEquals(185, expectedContext(u.id, w3).maxHeartRateBpm)
        assertEquals(185, analysisOf(w3)!!.analysis.zoneTime.value!!.zones.zones.last().highBpm)

        // a test after the last workout: nothing that was analysed knew about it
        data.fitnessTests.save(u.id, maxTestResult(TEST_3, T0 + DAY_MS, peak = 199)).ok()
        assertEquals(0, recalculate())

        // renaming the user changes nothing; a manual maximum heart rate changes the context of every workout
        data.profiles.update(u.id, validInput("Anita")).ok()
        assertEquals(0, recalculate())
        data.profiles.update(u.id, validInput("Anita").copy(maxHeartRateBpm = 190)).ok()
        assertEquals(3, recalculate())
        assertEquals(0, recalculate())
        listOf(w1, w2, w3).forEach { assertEquals(expectedAnalysis(u.id, it), analysisOf(it)!!.analysis) }
    }

    private suspend fun expectedContext(userId: Long, workoutId: Long): com.treadz.console.analysis.workout.WorkoutAnalysisContext {
        val start = data.workouts.detail(workoutId)!!.summary.startedAtMillis
        val inputs = AnalysisUserInputs(
            data.profiles.profile(userId).first()!!,
            data.weights.entries(userId).first(),
            data.fitnessTests.results(userId).first().map { it.result },
            data.restingHeartRates.measurements(userId).first().map { RestingPoint(it.measuredAtMillis, it.bpm) },
        )
        return WorkoutAnalysisContexts.build(inputs, start, utc)
    }

    @Test fun `an invalid result or one that is deleted does not change the context`() = runBlocking<Unit> {
        val u = ana()
        val w = finished(u.id, T0)
        assertEquals(1, recalculate())
        data.fitnessTests.save(u.id, maxTestResult(TEST_2, T0 - DAY_MS).copy(validity = Validity.Invalid)).ok()
        assertEquals(0, recalculate())
        data.fitnessTests.save(u.id, maxTestResult(TEST_3, T0 - DAY_MS)).ok()
        assertEquals(1, recalculate())
        data.fitnessTests.delete(TEST_3).ok()
        assertEquals("back to the context without it", 1, recalculate())
        assertEquals(expectedAnalysis(u.id, w), analysisOf(w)!!.analysis)
    }

    // ------------------------------------------------------------------ what gets no analysis

    @Test fun `workouts without an owner or without samples get no analysis until they can`() = runBlocking<Unit> {
        val u = ana()
        val unassigned = finished(null, T0 - DAY_MS)
        val empty = data.addFinishedWorkout(u.id, T0 - 2 * DAY_MS, emptyList(), WorkoutStats(durationSeconds = 0))
        assertEquals(0, recalculate())
        assertNull(analysisOf(unassigned))
        assertNull(analysisOf(empty))

        data.workouts.assign(unassigned, u.id).ok()
        assertEquals(1, recalculate())
        assertEquals(expectedAnalysis(u.id, unassigned), analysisOf(unassigned)!!.analysis)
        assertEquals(listOf(unassigned), data.workoutAnalysis.analyses(u.id).first().map { it.workoutId })
        assertEquals(0, recalculate())
    }

    @Test fun `moving a workout to another user computes it with that user's context`() = runBlocking<Unit> {
        val u = ana()
        val beto = data.profiles.create(validInput("Beto").copy(sex = Sex.Male, birthDate = LocalDate.of(1980, 1, 1))).ok()
        data.weights.add(u.id, LocalDate.of(2026, 8, 1), 60.0).ok()
        data.weights.add(beto.id, LocalDate.of(2026, 8, 1), 90.0).ok()
        val w = finished(u.id, T0)
        assertEquals(1, recalculate())
        val before = analysisOf(w)!!.analysis.energy.value!!.grossKcal!!
        data.workouts.assign(w, beto.id).ok()
        assertEquals(1, recalculate())
        assertEquals(before * 90.0 / 60.0, analysisOf(w)!!.analysis.energy.value!!.grossKcal!!, 1e-6)
        assertEquals(emptyList<DatedWorkoutAnalysis>(), data.workoutAnalysis.analyses(u.id).first())
        assertEquals(listOf(w), data.workoutAnalysis.analyses(beto.id).first().map { it.workoutId })
    }

    // ------------------------------------------------------------------ deletion

    @Test fun `a deleted workout loses its analysis, and so does a deleted user`() = runBlocking<Unit> {
        val u = ana()
        val beto = data.profiles.create(validInput("Beto")).ok()
        val w1 = finished(u.id, T0 - DAY_MS)
        val w2 = finished(u.id, T0)
        val w3 = finished(beto.id, T0)
        assertEquals(3, recalculate())

        data.workouts.delete(w1).ok()
        assertNull(analysisOf(w1))
        assertEquals(listOf(w2), data.workoutAnalysis.analyses(u.id).first().map { it.workoutId })

        data.profiles.delete(u.id).ok()
        assertNull(analysisOf(w2))
        assertEquals(emptyList<DatedWorkoutAnalysis>(), data.workoutAnalysis.analyses(u.id).first())
        assertNotNull("the other user is not touched", analysisOf(w3))
        assertEquals(0, recalculate())
        assertNotNull(analysisOf(w3))
    }

    @Test fun `a discarded workout loses its analysis`() = runBlocking<Unit> {
        val u = ana()
        val held = finished(u.id, T0 - DAY_MS)
        val plain = finished(u.id, T0)
        assertEquals(2, recalculate())
        data.workouts.holdUpload(held)
        data.workouts.discardFinished(held).ok()
        data.workouts.discardFinished(plain).ok()
        assertNull(analysisOf(held))
        assertNull(analysisOf(plain))
        assertEquals(emptyList<DatedWorkoutAnalysis>(), data.workoutAnalysis.analyses(u.id).first())
        assertEquals(0, recalculate())
    }

    // ------------------------------------------------------------------ the inputs of the other trends

    @Test fun `the trend inputs map the tests, the resting heart rate, the RMSSD and the weights`() = runBlocking<Unit> {
        val u = ana()
        assertEquals(PerformanceInputs(), data.workoutAnalysis.performanceInputs(u.id).first())

        data.weights.add(u.id, LocalDate.of(2026, 9, 10), 71.5).ok()
        data.weights.add(u.id, LocalDate.of(2026, 8, 20), 73.0).ok()
        data.restingHeartRates.add(u.id, RestingHeartRateInput(T0 - 3 * DAY_MS, 57, RestingHeartRateSource.Strap, rmssdMs = 41.5)).ok()
        data.restingHeartRates.add(u.id, RestingHeartRateInput(T0 - 8 * DAY_MS, 60, RestingHeartRateSource.Manual)).ok()
        val ebbeling = ebbelingResult(TEST_1, T0 - 40 * DAY_MS)
        val maximal = maxTestResult(TEST_2, T0 - 5 * DAY_MS).let {
            it.copy(
                thresholds = com.treadz.console.analysis.thresholds.ThresholdResult(
                    1,
                    vt1 = com.treadz.console.analysis.thresholds.ThresholdPoint(131.0),
                    vt2 = com.treadz.console.analysis.thresholds.ThresholdPoint(162.0),
                    quality = com.treadz.console.analysis.thresholds.ThresholdQuality.Medium,
                ),
                outputs = it.outputs!!.copy(vo2maxIsLowerBound = true),
            )
        }
        data.fitnessTests.save(u.id, ebbeling).ok()
        data.fitnessTests.save(u.id, maximal).ok()
        data.fitnessTests.save(u.id, maxTestResult(TEST_3, T0 - DAY_MS).copy(validity = Validity.Invalid)).ok()

        val inputs = data.workoutAnalysis.performanceInputs(u.id).first()
        assertEquals(
            listOf(
                com.treadz.console.analysis.trends.TestPoint(epochDay(T0 - 40 * DAY_MS), vo2max = 41.0, vo2maxSee = 4.85),
                com.treadz.console.analysis.trends.TestPoint(
                    epochDay(T0 - 5 * DAY_MS), vo2max = 3.5 * 13.5, vo2maxSee = 4.0, vo2maxIsLowerBound = true, vt1Bpm = 131.0, vt2Bpm = 162.0,
                    thresholdQuality = com.treadz.console.analysis.thresholds.ThresholdQuality.Medium,
                ),
            ),
            inputs.tests,
        )
        assertEquals(
            listOf(
                com.treadz.console.analysis.trends.DatedValue(epochDay(T0 - 8 * DAY_MS), 60.0),
                com.treadz.console.analysis.trends.DatedValue(epochDay(T0 - 3 * DAY_MS), 57.0),
            ),
            inputs.restingHeartRate,
        )
        assertEquals(listOf(com.treadz.console.analysis.trends.DatedValue(epochDay(T0 - 3 * DAY_MS), 41.5)), inputs.rmssd)
        assertEquals(
            listOf(
                com.treadz.console.analysis.trends.DatedValue(LocalDate.of(2026, 8, 20).toEpochDay(), 73.0),
                com.treadz.console.analysis.trends.DatedValue(LocalDate.of(2026, 9, 10).toEpochDay(), 71.5),
            ),
            inputs.weights,
        )
        // another user sees none of it
        assertEquals(PerformanceInputs(), data.workoutAnalysis.performanceInputs(u.id + 1).first())
    }

    // ------------------------------------------------------------------ cost

    @Test fun `fifty-two workouts of half an hour are analysed in seconds, one at a time`() = runBlocking<Unit> {
        val u = ana()
        data.weights.add(u.id, LocalDate.of(2026, 8, 1), 70.0).ok()
        data.restingHeartRates.add(u.id, RestingHeartRateInput(T0 - 60 * DAY_MS, 58, RestingHeartRateSource.Manual)).ok()
        data.fitnessTests.save(u.id, maxTestResult(TEST_2, T0 - 100 * DAY_MS)).ok()
        repeat(52) { finished(u.id, T0 - (52 - it) * DAY_MS / 2, seconds = 1800) }
        val t0 = System.nanoTime()
        assertEquals(52, recalculate())
        val first = (System.nanoTime() - t0) / 1_000_000
        val t1 = System.nanoTime()
        assertEquals(0, recalculate())
        val second = (System.nanoTime() - t1) / 1_000_000
        println("workout analysis (${this@WorkoutAnalysisRecalculationTest::class.java.simpleName}): 52 workouts of 1800 samples in $first ms (${first / 52} ms each); nothing to do in $second ms")
        assertTrue("first run took $first ms", first < 60_000)
        assertTrue("a run with nothing to do took $second ms", second < 5_000)
    }
}

@RunWith(RobolectricTestRunner::class)
class RoomWorkoutAnalysisRecalculationTest : WorkoutAnalysisRecalculationTest() {
    private val db: UserDataDatabase = newRoomDatabase()
    override fun create(): UserData = RoomUserData(db, clock, ZoneOffset.UTC, seedGenericProfile = false)

    private suspend fun rowCount(): Int = db.query("SELECT COUNT(*) FROM workout_analysis", null).use { c -> c.moveToFirst(); c.getInt(0) }

    @Test fun `the stored row has the document and the scalar columns the trends read`() = runBlocking<Unit> {
        val u = ana()
        val w = finished(u.id, T0, seconds = 600)
        assertEquals(1, recalculate())
        val stored = analysisOf(w)!!.analysis
        val row = db.workoutAnalysis().observe(w).first()!!
        assertEquals(WorkoutAnalyzer.ALGORITHM_VERSION, row.algorithmVersion)
        assertEquals(32, row.fingerprint.length)
        assertEquals(T0, row.computedAtMillis)
        assertEquals(stored, WorkoutAnalysisJson.decode(row.json))
        assertEquals(stored.trimp.value?.trimp, row.trimp)
        assertNotNull(row.trimp)
        assertEquals(stored.fitnessIndex.value?.vo2maxEstimate, row.fitnessIndex)
        assertEquals(stored.recovery.value?.drop60Bpm, row.recoveryDrop60Bpm)
        assertEquals(stored.recovery.value?.drop120Bpm, row.recoveryDrop120Bpm)
        assertEquals(stored.recovery.value?.context?.name, row.recoveryContext)
    }

    @Test fun `scalar columns are empty for the sections that are missing and set for the ones that are not`() = runBlocking<Unit> {
        val u = ana()
        val noHr = finished(u.id, T0 - DAY_MS, samples = samplesWithoutHeartRate(T0 - DAY_MS))
        assertEquals(1, recalculate())
        val row = db.workoutAnalysis().observe(noHr).first()!!
        assertNull(row.trimp)
        assertNull(row.fitnessIndex)
        assertNull(row.recoveryContext)
    }

    @Test fun `an analysis of another algorithm version is recomputed`() = runBlocking<Unit> {
        val u = ana()
        val w = finished(u.id, T0)
        assertEquals(1, recalculate())
        val row = db.workoutAnalysis().observe(w).first()!!
        db.workoutAnalysis().upsert(row.copy(algorithmVersion = WorkoutAnalyzer.ALGORITHM_VERSION - 1))
        assertEquals(1, recalculate())
        assertEquals(WorkoutAnalyzer.ALGORITHM_VERSION, db.workoutAnalysis().observe(w).first()!!.algorithmVersion)
        assertEquals(0, recalculate())
    }

    @Test fun `a document that cannot be read is not shown, and a changed context replaces it`() = runBlocking<Unit> {
        val u = ana()
        val w = finished(u.id, T0)
        assertEquals(1, recalculate())
        val row = db.workoutAnalysis().observe(w).first()!!
        db.workoutAnalysis().upsert(row.copy(json = "{\"algorithmVersion\": 99}"))
        assertNull(analysisOf(w))
        assertEquals(emptyList<DatedWorkoutAnalysis>(), data.workoutAnalysis.analyses(u.id).first())
        data.weights.add(u.id, LocalDate.of(2026, 10, 1), 70.0).ok()
        assertEquals(1, recalculate())
        assertNotNull(analysisOf(w))
    }

    @Test fun `a workout in progress gets no analysis`() = runBlocking<Unit> {
        val u = ana()
        val store = data.workouts as WorkoutSessionStore
        val id = store.begin(u.id, T0)
        store.append(id, steadySamples(T0, 30), WorkoutStats(durationSeconds = 30))
        assertEquals(0, recalculate())
        assertNull(analysisOf(id))
        store.finish(id, emptyList(), WorkoutStats(durationSeconds = 30), T0 + 30_000, WorkoutEndReason.Stopped)
        assertEquals(1, recalculate())
        assertNotNull(analysisOf(id))
    }

    @Test fun `deleting the workout row deletes the analysis with it, and a tombstone is pruned`() = runBlocking<Unit> {
        val u = ana()
        val hard = finished(u.id, T0 - DAY_MS)
        val tomb = finished(u.id, T0)
        assertEquals(2, recalculate())
        assertEquals(2, rowCount())
        db.workouts().hardDelete(hard)
        assertEquals("the foreign key cascades", 1, rowCount())
        // a tombstone (what a synchronised deletion leaves) hides the analysis at once and the next run removes it
        db.workouts().markDeleted(tomb, T0 + 1, "other-device")
        assertNull(analysisOf(tomb))
        assertEquals(1, rowCount())
        assertEquals(0, recalculate())
        assertEquals(0, rowCount())
    }

    @Test fun `a workout deleted while it is analysed is not stored`() = runBlocking<Unit> {
        val u = ana()
        val w = finished(u.id, T0)
        val source = com.treadz.console.userdata.room.RoomAnalysisSource(db)
        val candidate = source.candidates().single()
        db.workouts().markDeleted(w, T0 + 1, "x")
        val record = AnalysisRecord(candidate.workoutId, "f", T0, WorkoutAnalyzer.analyse(emptyList(), WorkoutAnalysisContexts.build(source.inputs(u.id)!!, T0, utc)))
        assertEquals(false, source.store(record))
        assertEquals(0, rowCount())
    }

    @Test fun `unassigned and in-progress rows never reach the analysis table`() = runBlocking<Unit> {
        val u = ana()
        finished(null, T0)
        (data.workouts as WorkoutSessionStore).begin(u.id, T0 + 1)
        assertEquals(0, recalculate())
        assertEquals(0, rowCount())
    }
}

@RunWith(JUnit4::class)
class InMemoryWorkoutAnalysisRecalculationTest : WorkoutAnalysisRecalculationTest() {
    override fun create(): UserData = InMemoryUserData(clock, ZoneOffset.UTC, seedGenericProfile = false)
}

/** The in-memory implementation gives what Room gives, for the same data. */
@RunWith(RobolectricTestRunner::class)
class WorkoutAnalysisAgreementTest {
    private val clock = MutableClock()
    @After fun closeDatabases() = closeRoomDatabases()

    private fun <T> DataResult<T>.ok(): T = (this as? DataResult.Success)?.value ?: error("expected success but was $this")

    private data class Outcome(
        val analyses: List<DatedWorkoutAnalysis>,
        val analysis: List<StoredWorkoutAnalysis?>,
        val inputs: PerformanceInputs,
        val recalculated: List<Int>,
    )

    private suspend fun run(data: UserData): Outcome {
        val u = data.profiles.create(validInput()).ok()
        data.weights.add(u.id, LocalDate.of(2026, 8, 1), 80.0).ok()
        data.restingHeartRates.add(u.id, RestingHeartRateInput(T0 - 5 * DAY_MS, 60, RestingHeartRateSource.Strap, rmssdMs = 38.0)).ok()
        val ids = listOf(
            data.addFinishedWorkout(u.id, T0 - 30 * DAY_MS, steadySamples(T0 - 30 * DAY_MS, 1500), WorkoutStats(durationSeconds = 1500)),
            data.addFinishedWorkout(u.id, T0 - 10 * DAY_MS, SyntheticTestWorkout.walking(T0 - 10 * DAY_MS), WorkoutStats(durationSeconds = 720)),
            data.addFinishedWorkout(u.id, T0, samplesWithoutHeartRate(T0), WorkoutStats(durationSeconds = 300)),
        )
        val first = data.workoutAnalysisRecalculator.recalculate()
        data.fitnessTests.save(u.id, maxTestResult(TEST_2, T0 - 20 * DAY_MS)).ok()
        data.fitnessTests.save(u.id, ebbelingResult(TEST_1, T0 - 40 * DAY_MS)).ok()
        val second = data.workoutAnalysisRecalculator.recalculate()
        val third = data.workoutAnalysisRecalculator.recalculate()
        return Outcome(
            data.workoutAnalysis.analyses(u.id).first(),
            ids.map { data.workoutAnalysis.analysis(it).first() },
            data.workoutAnalysis.performanceInputs(u.id).first(),
            listOf(first, second, third),
        )
    }

    @Test fun `Room and the in-memory implementation produce the same analyses and trend inputs`() = runBlocking<Unit> {
        val room = run(newRoomUserData(clock))
        val memory = run(InMemoryUserData(clock, ZoneOffset.UTC, seedGenericProfile = false))
        assertEquals(listOf(3, 3, 0), room.recalculated)
        assertEquals(room.recalculated, memory.recalculated)
        assertEquals(3, room.analyses.size)
        assertEquals(room.analyses.map { it.analysis to it.epochDay to it.startedAtMillis }, memory.analyses.map { it.analysis to it.epochDay to it.startedAtMillis })
        assertEquals(room.analysis.map { it?.analysis }, memory.analysis.map { it?.analysis })
        assertEquals(room.inputs, memory.inputs)
        assertEquals(2, room.inputs.tests.size)
        assertNotNull(room.analysis[0]!!.analysis.zoneTime.value)
        assertEquals(AnalysisReason.NoHeartRate, room.analysis[2]!!.analysis.zoneTime.reason)
    }

    @Test fun `the placeholder weight of the generic profile is a weight for the analysis but not a measurement for the trends`() = runBlocking<Unit> {
        for (data in listOf(newRoomUserData(clock, seed = true), InMemoryUserData(clock, ZoneOffset.UTC, seedGenericProfile = true))) {
            val generic = data.profiles.profiles.first().single()
            assertTrue(data.weights.entries(generic.id).first().single().isPlaceholder)
            assertEquals(emptyList<com.treadz.console.analysis.trends.DatedValue>(), data.workoutAnalysis.performanceInputs(generic.id).first().weights)
            data.addFinishedWorkout(generic.id, T0, steadySamples(T0, 300), WorkoutStats(durationSeconds = 300))
            assertEquals(1, data.workoutAnalysisRecalculator.recalculate())
            val kcal = data.workoutAnalysis.analyses(generic.id).first().single().analysis.energy.value!!.grossKcal
            assertNotNull("the 80 kg the app uses for the generic profile", kcal)
        }
    }
}
