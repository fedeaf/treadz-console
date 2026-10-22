package com.treadz.console.userdata

import com.treadz.console.userdata.room.RoomUserData
import com.treadz.console.userdata.sync.RecordVersion
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * Every record the app synchronises gets a new version (`updated_at`, by the device clock, and `updated_by`) whenever its
 * content changes, and keeps it when nothing changes. The clock never moves here, so a new version can only come from the
 * change itself (a version is at least one millisecond after the previous one). One test per kind of record.
 */
@RunWith(RobolectricTestRunner::class)
class UpdatedAtRuleTest {

    private val clock = MutableClock()
    @After fun closeDatabases() = closeRoomDatabases()

    private var counter = 0
    private val db = newRoomDatabase()
    private val data = RoomUserData(db, clock, ZoneOffset.UTC, seedGenericProfile = false, deviceId = "tablet", uuidSource = { "uuid-${++counter}" })

    private fun <T> ok(r: DataResult<T>): T = (r as DataResult.Success).value

    private suspend fun ana(): UserProfile = ok(data.profiles.create(validInput("Ana")))

    private suspend fun profileVersion(user: UserProfile): RecordVersion = data.sync.profile(db.users().get(user.id)!!.uuid)!!.version

    private fun RecordVersion.laterThan(before: RecordVersion) {
        assertTrue("$this should be later than $before", this > before)
        assertEquals("tablet", deviceId)
    }

    @Test fun `profile`() = runBlocking<Unit> {
        val ana = ana()
        var last = profileVersion(ana)
        suspend fun changed(block: suspend () -> Unit) {
            block()
            profileVersion(ana).laterThan(last)
            last = profileVersion(ana)
        }
        changed { ok(data.profiles.update(ana.id, validInput("Ana").copy(heightCm = 171))) }
        changed { ok(data.profiles.update(ana.id, validInput("Ana María").copy(heightCm = 171))) } // a rename
        changed { ok(data.profiles.update(ana.id, validInput("Ana María").copy(heightCm = 171, maxHeartRateBpm = 180))) }
        changed { ok(data.weights.setTargetWeight(ana.id, 60.0)) }
        changed { data.profiles.setStrap(ana.id, "AA:BB:CC:DD:EE:FF", "H9Z") }
        changed { ok(data.profiles.clearStrap(ana.id)) }
        // what is not a change of content does not produce a version
        ok(data.profiles.update(ana.id, validInput("Ana María").copy(heightCm = 171, maxHeartRateBpm = 180)))
        ok(data.weights.setTargetWeight(ana.id, 60.0))
        ok(data.profiles.clearStrap(ana.id))
        assertEquals(last, profileVersion(ana))
        // results and resting measurements are derived data of the profile file: its version does not move
        ok(data.fitnessTests.save(ana.id, ebbelingResult(TEST_1, T0)))
        ok(data.restingHeartRates.add(ana.id, RestingHeartRateInput(T0 - DAY_MS, 56, RestingHeartRateSource.Manual)))
        assertEquals(last, profileVersion(ana))
    }

    @Test fun `weight entries`() = runBlocking<Unit> {
        val ana = ana()
        val entry = ok(data.weights.add(ana.id, LocalDate.of(2026, 10, 1), 70.0))
        val uuid = db.weights().get(entry.id)!!.uuid
        suspend fun version() = db.weights().getByUuid(uuid)!!.let { RecordVersion(it.updatedAtMillis, it.updatedBy) }
        val created = version()
        ok(data.weights.update(entry.id, LocalDate.of(2026, 10, 1), 70.0))
        assertEquals("an edit that changes nothing is not a change", created, version())
        ok(data.weights.update(entry.id, LocalDate.of(2026, 10, 1), 69.5))
        val edited = version().also { it.laterThan(created) }
        ok(data.weights.delete(entry.id))
        version().laterThan(edited)
    }

    @Test fun `resting heart rate measurements`() = runBlocking<Unit> {
        val ana = ana()
        val input = RestingHeartRateInput(T0 - DAY_MS, 56, RestingHeartRateSource.Manual)
        val m = ok(data.restingHeartRates.add(ana.id, input))
        val uuid = db.restingHeartRates().get(m.id)!!.uuid
        suspend fun version() = db.restingHeartRates().getByUuid(uuid)!!.let { RecordVersion(it.updatedAtMillis, it.updatedBy) }
        val created = version()
        ok(data.restingHeartRates.update(m.id, input))
        assertEquals(created, version())
        ok(data.restingHeartRates.update(m.id, input.copy(bpm = 58)))
        val edited = version().also { it.laterThan(created) }
        ok(data.restingHeartRates.delete(m.id))
        version().laterThan(edited)
    }

    @Test fun `workouts`() = runBlocking<Unit> {
        val ana = ana()
        val beto = ok(data.profiles.create(validInput("Beto")))
        val id = data.addFinishedWorkout(ana.id)
        val uuid = db.workouts().get(id)!!.uuid
        suspend fun version() = db.workouts().getByUuid(uuid)!!.let { RecordVersion(it.updatedAtMillis, it.updatedBy) }
        var last = version()
        suspend fun changed(block: suspend () -> Unit) {
            block()
            version().laterThan(last)
            last = version()
        }
        changed { ok(data.workouts.link(id, WorkoutLink(plan = hillPlan()))) }
        changed { ok(data.workouts.link(id, WorkoutLink(fitnessTestId = TEST_1))) }
        changed { ok(data.workouts.attachSessionRecord(id, sessionRecord())) }
        changed { ok(data.workouts.setEvents(id, EVENTS)) }
        changed { ok(data.workouts.setEvents(id, EVENTS.take(1))) }
        changed { ok(data.workouts.assign(id, beto.id)) }
        // the same again is no change
        ok(data.workouts.link(id, WorkoutLink(fitnessTestId = TEST_1)))
        ok(data.workouts.attachSessionRecord(id, sessionRecord()))
        ok(data.workouts.setEvents(id, EVENTS.take(1)))
        ok(data.workouts.assign(id, beto.id))
        assertEquals(last, version())
        changed { ok(data.workouts.delete(id)) }
    }

    @Test fun `a workout that ends and one found open at the next start`() = runBlocking<Unit> {
        val ana = ana()
        val store = data.workouts as com.treadz.console.userdata.workout.WorkoutSessionStore
        val id = store.begin(ana.id, T0)
        val started = db.workouts().get(id)!!.let { RecordVersion(it.updatedAtMillis, it.updatedBy) }
        store.finish(id, emptyList(), WorkoutStats(durationSeconds = 60), T0 + 60_000, WorkoutEndReason.Stopped)
        val ended = db.workouts().get(id)!!.let { RecordVersion(it.updatedAtMillis, it.updatedBy) }.also { it.laterThan(started) }
        val open = store.begin(ana.id, T0 + DAY_MS)
        val openVersion = db.workouts().get(open)!!.let { RecordVersion(it.updatedAtMillis, it.updatedBy) }
        assertEquals(1, store.closeInterrupted())
        db.workouts().get(open)!!.let { RecordVersion(it.updatedAtMillis, it.updatedBy) }.laterThan(openVersion)
        assertEquals(ended, db.workouts().get(id)!!.let { RecordVersion(it.updatedAtMillis, it.updatedBy) })
    }

    @Test fun `programs`() = runBlocking<Unit> {
        val ana = ana()
        assertTrue(data.programs.save(ana.id, norwegian()) is ProgramSaveResult.Saved)
        suspend fun version() = db.programs().getByUuid(PROGRAM_1)!!.let { RecordVersion(it.updatedAtMillis, it.updatedBy) }
        val created = version()
        data.programs.save(ana.id, norwegian())
        assertEquals("saving the same program is not a change", created, version())
        data.programs.save(ana.id, norwegian(name = "Norwegian 4x4 hard"))
        val edited = version().also { it.laterThan(created) }
        data.programs.delete(PROGRAM_1)
        version().laterThan(edited)
    }

    @Test fun `fitness test results`() = runBlocking<Unit> {
        val ana = ana()
        ok(data.fitnessTests.save(ana.id, ebbelingResult(TEST_1, T0)))
        suspend fun version() = db.fitnessTests().getByUuid(TEST_1)!!.let { RecordVersion(it.updatedAtMillis, it.updatedBy) }
        val created = version()
        ok(data.fitnessTests.save(ana.id, ebbelingResult(TEST_1, T0)))
        assertEquals(created, version())
        ok(data.fitnessTests.save(ana.id, ebbelingResult(TEST_1, T0, vo2max = 42.0))) // a recalculated result
        val recalculated = version().also { it.laterThan(created) }
        ok(data.fitnessTests.delete(TEST_1))
        version().laterThan(recalculated)
    }
}
