package com.treadz.console.userdata

import com.treadz.console.programs.fitness.EffortAssessment
import com.treadz.console.programs.fitness.FitnessProfileCalculator
import com.treadz.console.programs.fitness.FitnessTestFlag
import com.treadz.console.programs.fitness.HeartRateMaxOrigin
import com.treadz.console.programs.fitness.Validity
import com.treadz.console.userdata.memory.InMemoryUserData
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import org.robolectric.RobolectricTestRunner
import java.time.ZoneOffset
import com.treadz.console.programs.fitness.Sex as FitnessSex

/**
 * Fitness test results, the fitness profile (snapshot and live), the maximum heart rate decision and its two answers:
 * behaviour every [UserData] implementation must have.
 */
@RunWith(RobolectricTestRunner::class)
abstract class FitnessContractTest {
    protected val clock = MutableClock()
    @After fun closeDatabases() = closeRoomDatabases()

    protected abstract fun create(): UserData
    private val data by lazy { create() }

    private fun <T> DataResult<T>.ok(): T = (this as? DataResult.Success)?.value ?: error("expected success but was $this")

    /** Ana: female, born 1986-10-08 (39 on the clock's day, T0 = 2026-10-07). */
    private fun ana(maxHr: Int? = null) = runBlocking { data.profiles.create(validInput().copy(maxHeartRateBpm = maxHr)).ok() }

    // ---- results ----

    @Test fun `results are stored, listed newest first and read back unchanged`() = runBlocking<Unit> {
        val u = ana()
        val old = ebbelingResult(TEST_1, T0 - 30 * DAY_MS)
        val recent = maxTestResult(TEST_2, T0 - DAY_MS)
        data.fitnessTests.save(u.id, old).ok()
        data.fitnessTests.save(u.id, recent).ok()

        val list = data.fitnessTests.results(u.id).first()
        assertEquals(listOf(TEST_2, TEST_1), list.map { it.id })
        assertEquals(recent, list[0].result)
        assertEquals(old, data.fitnessTests.get(TEST_1)!!.result)
        assertEquals(u.id, data.fitnessTests.get(TEST_1)!!.userId)
        assertEquals(emptyList<FitnessTestRecord>(), data.fitnessTests.results(999).first())
        assertNull(data.fitnessTests.get("0b6f8f9e-4d0c-4d43-9f43-2f0c6f1a7bff"))
    }

    @Test fun `results of one user are not seen by another`() = runBlocking<Unit> {
        val a = ana()
        val b = data.profiles.create(validInput("Beto")).ok()
        data.fitnessTests.save(a.id, ebbelingResult()).ok()
        assertEquals(emptyList<FitnessTestRecord>(), data.fitnessTests.results(b.id).first())
        assertTrue(data.fitnessTests.save(b.id, ebbelingResult()) is DataResult.StorageError) // the id is taken
        assertEquals(a.id, data.fitnessTests.get(TEST_1)!!.userId)
    }

    @Test fun `saving the same result again changes nothing and a changed one replaces it`() = runBlocking<Unit> {
        val u = ana()
        val first = data.fitnessTests.save(u.id, ebbelingResult()).ok()
        clock.nowMillis = T0 + 5_000
        assertEquals(first, data.fitnessTests.save(u.id, ebbelingResult()).ok())
        val changed = data.fitnessTests.save(u.id, ebbelingResult(vo2max = 43.0)).ok()
        assertEquals(43.0, changed.result.outputs!!.vo2maxMlKgMin, 0.0)
        assertEquals(1, data.fitnessTests.results(u.id).first().size)
    }

    @Test fun `an id that is not a plain identifier is refused, and an unknown user is not found`() = runBlocking<Unit> {
        val u = ana()
        for (bad in listOf("", "a/b", "../x", "x y", "a".repeat(65))) {
            try {
                data.fitnessTests.save(u.id, ebbelingResult(id = bad))
                fail("'$bad' should be refused")
            } catch (expected: IllegalArgumentException) {
            }
        }
        assertEquals(DataResult.NotFound, data.fitnessTests.save(999, ebbelingResult()))
    }

    @Test fun `deleting a result removes it, and deleting a profile removes its results`() = runBlocking<Unit> {
        val u = ana()
        data.fitnessTests.save(u.id, ebbelingResult(TEST_1)).ok()
        data.fitnessTests.save(u.id, ebbelingResult(TEST_3, T0 + 1)).ok()
        data.fitnessTests.delete(TEST_1).ok()
        assertNull(data.fitnessTests.get(TEST_1))
        assertEquals(listOf(TEST_3), data.fitnessTests.results(u.id).first().map { it.id })
        assertEquals(DataResult.NotFound, data.fitnessTests.delete(TEST_1))
        data.profiles.delete(u.id).ok()
        assertNull(data.fitnessTests.get(TEST_3))
    }

    @Test fun `an invalid result is stored but does not count`() = runBlocking<Unit> {
        val u = ana()
        val invalid = ebbelingResult(TEST_1, T0, validity = Validity.Invalid, flags = listOf(FitnessTestFlag.EndedByUser))
        data.fitnessTests.save(u.id, invalid).ok()
        assertEquals(invalid, data.fitnessTests.results(u.id).first().single().result)
        assertNull(data.fitnessTests.fitnessProfile(u.id).first())
        assertNull(data.fitnessTests.liveFitnessProfile(u.id).first()!!.vo2max)
    }

    // ---- fitness profile ----

    @Test fun `the snapshot is the calculator at the time of the newest valid result`() = runBlocking<Unit> {
        val u = ana()
        assertNull(data.fitnessTests.fitnessProfile(u.id).first())
        val a = ebbelingResult(TEST_1, T0 - 40 * DAY_MS, vo2max = 40.0)
        val b = maxTestResult(TEST_2, T0 - 10 * DAY_MS, mas = 13.0)
        data.fitnessTests.save(u.id, a).ok()
        data.fitnessTests.save(u.id, b).ok()

        val snapshot = data.fitnessTests.fitnessProfile(u.id).first()!!
        val expected = FitnessProfileCalculator.compute(listOf(a, b), b.startedAtMillis, 39, FitnessSex.Female)
        assertEquals(expected, snapshot)
        assertEquals(b.startedAtMillis, snapshot.computedAtMillis)
        assertEquals(13.0, snapshot.mas!!.valueKmh, 0.0)
        assertFalse(snapshot.mas!!.estimated)
        assertEquals(185, snapshot.hrMax!!.measuredBpm)
        assertEquals(listOf(TEST_2, TEST_1), snapshot.vo2max!!.sources.map { it.resultId })
    }

    @Test fun `the snapshot does not depend on the clock, the time zone or the order the results were saved`() = runBlocking<Unit> {
        val results = listOf(
            ebbelingResult(TEST_1, T0 - 40 * DAY_MS, vo2max = 40.0),
            maxTestResult(TEST_2, T0 - 10 * DAY_MS, mas = 13.0),
            ebbelingResult(TEST_3, T0 - 10 * DAY_MS, vo2max = 44.0), // same start time as the other: ties are broken by id
        )
        val a = create()
        val b = create()
        val ua = a.profiles.create(validInput()).ok()
        val ub = b.profiles.create(validInput()).ok()
        results.forEach { a.fitnessTests.save(ua.id, it).ok() }
        results.reversed().forEach { b.fitnessTests.save(ub.id, it).ok() }
        clock.nowMillis = T0 + 700 * DAY_MS // time passes: the snapshot does not age

        val sa = a.fitnessTests.fitnessProfile(ua.id).first()!!
        val sb = b.fitnessTests.fitnessProfile(ub.id).first()!!
        assertEquals(sa, sb)
        assertEquals(T0 - 10 * DAY_MS, sa.computedAtMillis)
        assertNotNull(sa.vo2max)
    }

    @Test fun `the live profile ages with the clock and drops results older than a year`() = runBlocking<Unit> {
        val u = ana()
        val a = ebbelingResult(TEST_1, T0 - 10 * DAY_MS, vo2max = 40.0)
        data.fitnessTests.save(u.id, a).ok()
        val now = data.fitnessTests.liveFitnessProfile(u.id).first()!!
        assertEquals(T0, now.computedAtMillis)
        assertEquals(10, now.vo2max!!.sources.single().ageDays)

        clock.nowMillis = T0 + 400 * DAY_MS
        val later = data.fitnessTests.liveFitnessProfile(u.id).first()!!
        assertNull(later.vo2max)
        assertNull(later.hrMax)
        // the snapshot keeps describing the data
        assertNotNull(data.fitnessTests.fitnessProfile(u.id).first()!!.vo2max)
        assertNull(data.fitnessTests.liveFitnessProfile(999).first())
    }

    @Test fun `deleting a result recomputes the profile`() = runBlocking<Unit> {
        val u = ana()
        data.fitnessTests.save(u.id, ebbelingResult(TEST_1, T0 - 10 * DAY_MS, vo2max = 40.0)).ok()
        data.fitnessTests.save(u.id, ebbelingResult(TEST_3, T0 - 5 * DAY_MS, vo2max = 50.0)).ok()
        assertEquals(2, data.fitnessTests.fitnessProfile(u.id).first()!!.vo2max!!.sources.size)
        data.fitnessTests.delete(TEST_3).ok()
        val after = data.fitnessTests.fitnessProfile(u.id).first()!!
        assertEquals(listOf(TEST_1), after.vo2max!!.sources.map { it.resultId })
        assertEquals(T0 - 10 * DAY_MS, after.computedAtMillis)
        data.fitnessTests.delete(TEST_1).ok()
        assertNull(data.fitnessTests.fitnessProfile(u.id).first())
    }

    // ---- the time of the manual maximum heart rate ----

    @Test fun `the time of the manual maximum heart rate follows the value`() = runBlocking<Unit> {
        val u = data.profiles.create(validInput()).ok()
        assertNull(u.maxHeartRateSetAtMillis)

        clock.nowMillis = T0 + 1_000
        val set = data.profiles.update(u.id, validInput().copy(maxHeartRateBpm = 185)).ok()
        assertEquals(T0 + 1_000, set.maxHeartRateSetAtMillis)

        clock.nowMillis = T0 + 2_000 // another field changes: the value was not touched
        val renamed = data.profiles.update(u.id, validInput().copy(maxHeartRateBpm = 185, heightCm = 170)).ok()
        assertEquals(T0 + 1_000, renamed.maxHeartRateSetAtMillis)

        clock.nowMillis = T0 + 3_000
        val changed = data.profiles.update(u.id, validInput().copy(maxHeartRateBpm = 180)).ok()
        assertEquals(T0 + 3_000, changed.maxHeartRateSetAtMillis)

        val cleared = data.profiles.update(u.id, validInput()).ok()
        assertNull(cleared.maxHeartRateBpm)
        assertNull(cleared.maxHeartRateSetAtMillis)

        clock.nowMillis = T0 + 4_000
        val created = data.profiles.create(validInput("Beto").copy(maxHeartRateBpm = 190)).ok()
        assertEquals(T0 + 4_000, created.maxHeartRateSetAtMillis)
    }

    // ---- the maximum heart rate decision ----

    @Test fun `without tests the estimate is used`() = runBlocking<Unit> {
        val u = ana()
        val d = data.fitnessTests.heartRateMax(u.id).first()!!
        assertEquals(HeartRateMaxOrigin.Estimated, d.origin)
        assertEquals(181, d.bpm) // Tanaka at 39: 208 - 0.7 * 39
        assertFalse(d.askUser)
        assertNull(data.fitnessTests.heartRateMax(999).first())
    }

    @Test fun `a measured maximum replaces the estimate`() = runBlocking<Unit> {
        val u = ana()
        data.fitnessTests.save(u.id, maxTestResult(TEST_2, T0 - DAY_MS, peak = 188)).ok()
        val d = data.fitnessTests.heartRateMax(u.id).first()!!
        assertEquals(HeartRateMaxOrigin.Measured, d.origin)
        assertEquals(188, d.bpm)
        assertFalse(d.askUser)
    }

    @Test fun `a peak of a test that was not a maximal effort is not a maximum`() = runBlocking<Unit> {
        val u = ana()
        data.fitnessTests.save(u.id, maxTestResult(TEST_2, T0 - DAY_MS, peak = 160, effort = EffortAssessment.NotMaximal)).ok()
        assertEquals(HeartRateMaxOrigin.Estimated, data.fitnessTests.heartRateMax(u.id).first()!!.origin)
    }

    @Test fun `the manual value wins, and a newer different measurement makes the app ask`() = runBlocking<Unit> {
        val u = ana(maxHr = 170) // entered at T0
        // a test older than the manual value: no question, whatever it measured
        data.fitnessTests.save(u.id, maxTestResult(TEST_1, T0 - DAY_MS, peak = 168)).ok()
        var d = data.fitnessTests.heartRateMax(u.id).first()!!
        assertEquals(HeartRateMaxOrigin.Manual, d.origin)
        assertEquals(170, d.bpm)
        assertFalse(d.askUser)

        // a newer test that differs by more than 3 bpm
        clock.nowMillis = T0 + 2 * DAY_MS
        data.fitnessTests.save(u.id, maxTestResult(TEST_2, T0 + DAY_MS, peak = 184)).ok()
        d = data.fitnessTests.heartRateMax(u.id).first()!!
        assertEquals(HeartRateMaxOrigin.Manual, d.origin)
        assertEquals(170, d.bpm)
        assertTrue(d.askUser)
        assertEquals(14, d.differenceBpm)
        assertEquals(184, d.measured!!.measuredBpm)
    }

    @Test fun `a newer measurement within 3 bpm of the manual value does not ask`() = runBlocking<Unit> {
        val u = ana(maxHr = 182)
        clock.nowMillis = T0 + 2 * DAY_MS
        data.fitnessTests.save(u.id, maxTestResult(TEST_2, T0 + DAY_MS, peak = 185)).ok()
        assertFalse(data.fitnessTests.heartRateMax(u.id).first()!!.askUser)
    }

    @Test fun `using the measured value clears the manual one`() = runBlocking<Unit> {
        val u = ana(maxHr = 170)
        clock.nowMillis = T0 + 2 * DAY_MS
        data.fitnessTests.save(u.id, maxTestResult(TEST_2, T0 + DAY_MS, peak = 184)).ok()
        assertTrue(data.fitnessTests.heartRateMax(u.id).first()!!.askUser)

        data.fitnessTests.useMeasuredHeartRateMax(u.id).ok()
        val d = data.fitnessTests.heartRateMax(u.id).first()!!
        assertEquals(HeartRateMaxOrigin.Measured, d.origin)
        assertEquals(184, d.bpm)
        assertFalse(d.askUser)
        val profile = data.profiles.profile(u.id).first()!!
        assertNull(profile.maxHeartRateBpm)
        assertNull(profile.maxHeartRateSetAtMillis)
    }

    @Test fun `keeping the manual value stops the question until a newer test`() = runBlocking<Unit> {
        val u = ana(maxHr = 170)
        clock.nowMillis = T0 + 2 * DAY_MS
        data.fitnessTests.save(u.id, maxTestResult(TEST_2, T0 + DAY_MS, peak = 184)).ok()

        data.fitnessTests.keepManualHeartRateMax(u.id).ok()
        var d = data.fitnessTests.heartRateMax(u.id).first()!!
        assertEquals(170, d.bpm)
        assertEquals(HeartRateMaxOrigin.Manual, d.origin)
        assertFalse(d.askUser)
        assertEquals(T0 + 2 * DAY_MS, data.profiles.profile(u.id).first()!!.maxHeartRateSetAtMillis)

        // a later test that differs again asks again
        clock.nowMillis = T0 + 10 * DAY_MS
        data.fitnessTests.save(u.id, maxTestResult(TEST_3, T0 + 9 * DAY_MS, peak = 190)).ok()
        d = data.fitnessTests.heartRateMax(u.id).first()!!
        assertTrue(d.askUser)
        assertEquals(190, d.measured!!.measuredBpm)
    }

    @Test fun `keeping is newer than the test even when this clock is behind the test`() = runBlocking<Unit> {
        val u = ana(maxHr = 170)
        // the test comes from a device whose clock is ahead of this one
        data.fitnessTests.save(u.id, maxTestResult(TEST_2, T0 + 3 * DAY_MS, peak = 184)).ok()
        assertTrue(data.fitnessTests.heartRateMax(u.id).first()!!.askUser)
        data.fitnessTests.keepManualHeartRateMax(u.id).ok()
        assertFalse(data.fitnessTests.heartRateMax(u.id).first()!!.askUser)
    }

    @Test fun `the answers need something to answer`() = runBlocking<Unit> {
        val noManual = ana()
        assertEquals(DataResult.NotFound, data.fitnessTests.useMeasuredHeartRateMax(noManual.id)) // nothing measured
        assertEquals(DataResult.NotFound, data.fitnessTests.keepManualHeartRateMax(noManual.id)) // nothing manual
        assertEquals(DataResult.NotFound, data.fitnessTests.useMeasuredHeartRateMax(999))
        assertEquals(DataResult.NotFound, data.fitnessTests.keepManualHeartRateMax(999))
        data.fitnessTests.save(noManual.id, maxTestResult(TEST_2, T0 - DAY_MS, peak = 184)).ok()
        data.fitnessTests.useMeasuredHeartRateMax(noManual.id).ok() // already using it: nothing to clear
        assertEquals(HeartRateMaxOrigin.Measured, data.fitnessTests.heartRateMax(noManual.id).first()!!.origin)
    }

    @Test fun `the active user's decision follows the active user`() = runBlocking<Unit> {
        val a = ana()
        val b = data.profiles.create(validInput("Beto").copy(maxHeartRateBpm = 175)).ok()
        assertEquals(181, data.activeUserHeartRateMax.first()!!.bpm)
        data.profiles.setActiveUser(b.id).ok()
        assertEquals(175, data.activeUserHeartRateMax.first()!!.bpm)
        data.profiles.setActiveUser(null).ok()
        assertNull(data.activeUserHeartRateMax.first())
    }
}

class RoomFitnessTest : FitnessContractTest() {
    override fun create(): UserData = newRoomUserData(clock)
}

@RunWith(JUnit4::class)
class InMemoryFitnessTest : FitnessContractTest() {
    override fun create(): UserData = InMemoryUserData(clock, ZoneOffset.UTC, seedGenericProfile = false)
}
