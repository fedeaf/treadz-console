package com.treadz.console.userdata

import com.treadz.console.programs.fitness.EffortAssessment
import com.treadz.console.programs.fitness.FitnessTestJson
import com.treadz.console.programs.fitness.FitnessTestType
import com.treadz.console.programs.fitness.HeartRateMaxOrigin
import com.treadz.console.programs.fitness.HeartRateSourceKind
import com.treadz.console.programs.fitness.MaxHeartRateWalkMeasurements
import com.treadz.console.programs.fitness.Validity
import com.treadz.console.userdata.memory.InMemoryUserData
import com.treadz.console.userdata.room.RoomUserData
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
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
import java.time.ZoneOffset

/**
 * The maximal heart-rate walking test (modified Balke) is stored, listed, deleted and used for the maximum heart rate
 * like the other tests, and its workout keeps the source of the heart rate and the RR intervals.
 */
@RunWith(RobolectricTestRunner::class)
abstract class MaxHeartRateWalkStorageTest {
    protected val clock = MutableClock()
    @After fun closeDatabases() = closeRoomDatabases()

    protected abstract fun create(): UserData
    protected val data by lazy { create() }

    protected fun <T> DataResult<T>.ok(): T = (this as? DataResult.Success)?.value ?: error("expected success but was $this")

    private suspend fun ana(maxHr: Int? = null) = data.profiles.create(validInput().copy(maxHeartRateBpm = maxHr)).ok()

    @Test fun `the result is stored and read back unchanged, with its source and measurements`() = runBlocking<Unit> {
        val u = ana()
        val result = balkeResult()
        val record = data.fitnessTests.save(u.id, result).ok()
        assertEquals(FitnessTestType.MaxHeartRateWalk, record.result.testType)
        assertEquals(result, record.result)
        assertEquals(result, data.fitnessTests.get(TEST_3)!!.result)
        assertEquals(HeartRateSourceKind.DirectStrap, record.result.inputs.heartRateSource)
        val measurements = record.result.measurements as MaxHeartRateWalkMeasurements
        assertEquals(186, measurements.hrMaxCandidateBpm)
        assertEquals(EffortAssessment.Maximal, measurements.effort)
        assertNull(record.result.outputs)
        // saving the same result again changes nothing
        assertEquals(record, data.fitnessTests.save(u.id, result).ok())
        // the console as the source is stored too
        val console = data.fitnessTests.save(u.id, balkeResult(TEST_1, T0 - DAY_MS, source = HeartRateSourceKind.TreadmillConsole)).ok()
        assertEquals(HeartRateSourceKind.TreadmillConsole, console.result.inputs.heartRateSource)
        assertEquals(listOf(TEST_3, TEST_1), data.fitnessTests.results(u.id).first().map { it.id })
    }

    @Test fun `a valid result gives the measured maximum heart rate and an invalid one does not`() = runBlocking<Unit> {
        val u = ana()
        assertEquals(HeartRateMaxOrigin.Estimated, data.fitnessTests.heartRateMax(u.id).first()!!.origin)
        data.fitnessTests.save(u.id, balkeResult(TEST_1, T0 - 10 * DAY_MS, peak = 180, validity = Validity.Invalid)).ok()
        assertEquals(HeartRateMaxOrigin.Estimated, data.fitnessTests.heartRateMax(u.id).first()!!.origin)

        data.fitnessTests.save(u.id, balkeResult(TEST_3, T0 - DAY_MS, peak = 188)).ok()
        val decision = data.fitnessTests.heartRateMax(u.id).first()!!
        assertEquals(HeartRateMaxOrigin.Measured, decision.origin)
        assertEquals(188, decision.bpm)
        assertEquals(188, data.fitnessTests.liveFitnessProfile(u.id).first()!!.hrMax!!.measuredBpm)
        assertEquals(188, data.fitnessTests.fitnessProfile(u.id).first()!!.hrMax!!.measuredBpm)

        data.fitnessTests.delete(TEST_3).ok()
        assertEquals(HeartRateMaxOrigin.Estimated, data.fitnessTests.heartRateMax(u.id).first()!!.origin)
        assertNull(data.fitnessTests.get(TEST_3))
    }

    @Test fun `the walking test competes with the running test for the highest measured maximum`() = runBlocking<Unit> {
        val u = ana()
        data.fitnessTests.save(u.id, maxTestResult(TEST_2, T0 - 20 * DAY_MS, peak = 182)).ok()
        data.fitnessTests.save(u.id, balkeResult(TEST_3, T0 - DAY_MS, peak = 187)).ok()
        assertEquals(187, data.fitnessTests.heartRateMax(u.id).first()!!.bpm)
    }

    @Test fun `a manual maximum that differs from the walking test is asked about, and the answers work`() = runBlocking<Unit> {
        val u = ana(maxHr = 180)
        clock.nowMillis = T0
        data.fitnessTests.save(u.id, balkeResult(TEST_3, T0 + DAY_MS, peak = 190)).ok()
        clock.nowMillis = T0 + 2 * DAY_MS
        val ask = data.fitnessTests.heartRateMax(u.id).first()!!
        assertEquals(HeartRateMaxOrigin.Manual, ask.origin)
        assertTrue(ask.askUser)
        assertEquals(10, ask.differenceBpm)
        data.fitnessTests.useMeasuredHeartRateMax(u.id).ok()
        assertEquals(190, data.fitnessTests.heartRateMax(u.id).first()!!.bpm)
        assertNull(data.profiles.profile(u.id).first()!!.maxHeartRateBpm)
    }

    @Test fun `the workout of the test keeps the heart rate source and the RR intervals of every sample`() = runBlocking<Unit> {
        val u = ana()
        data.fitnessTests.save(u.id, balkeResult()).ok()
        val samples = strapSamples(T0, seconds = 8)
        val id = data.addFinishedWorkout(u.id, T0, samples, WorkoutStats(durationSeconds = 8, distanceMeters = 14))
        data.workouts.link(id, WorkoutLink(fitnessTestId = TEST_3)).ok()
        val detail = data.workouts.detail(id)!!
        assertEquals(TEST_3, detail.summary.fitnessTestId)
        assertEquals(samples.map { it.heartRateSource }, detail.samples.map { it.heartRateSource })
        assertEquals(samples.flatMap { it.rrIntervalsMs }, detail.samples.flatMap { it.rrIntervalsMs })
        assertEquals(samples, detail.samples.map { it.copy(seq = it.seq) })
    }

    @Test fun `discarding the test workout removes the walking test result too`() = runBlocking<Unit> {
        val u = ana()
        data.fitnessTests.save(u.id, balkeResult()).ok()
        val id = data.addFinishedWorkout(u.id)
        data.workouts.link(id, WorkoutLink(fitnessTestId = TEST_3)).ok()
        data.workouts.discardFinished(id).ok()
        assertNull(data.fitnessTests.get(TEST_3))
        assertNull(data.workouts.detail(id))
    }
}

class RoomMaxHeartRateWalkStorageTest : MaxHeartRateWalkStorageTest() {
    override fun create(): UserData = newRoomUserData(clock)

    @Test fun `the document that is synchronised is the walking test's JSON and its workout keeps source and RR`() = runBlocking<Unit> {
        val room = data as RoomUserData
        val u = data.profiles.create(validInput()).ok()
        data.fitnessTests.save(u.id, balkeResult()).ok()
        val doc = room.fitnessTestDocuments.pending().single()
        assertTrue(doc.json.contains("\"maxHeartRateWalk\""))
        assertEquals(balkeResult(), FitnessTestJson.decode(doc.json))

        val id = data.addFinishedWorkout(u.id, T0, strapSamples(), WorkoutStats(durationSeconds = 6, distanceMeters = 10))
        data.workouts.link(id, WorkoutLink(fitnessTestId = TEST_3)).ok()
        val workout = room.sync.pendingChanges().workouts.single()
        assertEquals(TEST_3, workout.fitnessTestId)
        assertEquals(strapSamples(), room.sync.workoutSamples(workout.uuid).map { it.copy() })
        assertFalse(workout.deleted)
        assertNotNull(workout.startedAtMillis)
    }
}

@RunWith(JUnit4::class)
class InMemoryMaxHeartRateWalkStorageTest : MaxHeartRateWalkStorageTest() {
    override fun create(): UserData = InMemoryUserData(clock, ZoneOffset.UTC, seedGenericProfile = false)
}
