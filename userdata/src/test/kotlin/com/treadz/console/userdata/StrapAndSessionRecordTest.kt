package com.treadz.console.userdata

import com.treadz.console.programs.fitness.HeartRateSourceKind
import com.treadz.console.programs.json.ProgramJson
import com.treadz.console.programs.model.ProgramKind
import com.treadz.console.programs.personalised.GeneratedSessionJson
import com.treadz.console.userdata.memory.InMemoryUserData
import com.treadz.console.userdata.room.RoomUserData
import com.treadz.console.userdata.sync.RecordVersion
import com.treadz.console.userdata.sync.RemoteChanges
import com.treadz.console.userdata.sync.SyncProfile
import com.treadz.console.userdata.sync.SyncedRecords
import com.treadz.console.userdata.sync.SyncedRef
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
import java.time.LocalDate
import java.time.ZoneOffset

/** The strap of each user: address validation, set and clear, and that editing the profile leaves it alone. */
@RunWith(RobolectricTestRunner::class)
abstract class StrapContractTest {
    protected val clock = MutableClock()
    @After fun closeDatabases() = closeRoomDatabases()

    protected abstract fun create(): UserData
    protected val data by lazy { create() }

    protected fun <T> DataResult<T>.ok(): T = (this as? DataResult.Success)?.value ?: error("expected success but was $this")

    private suspend fun ana() = data.profiles.create(validInput("Ana")).ok()

    @Test fun `a new profile has no strap`() = runBlocking<Unit> {
        val u = ana()
        assertNull(u.strapAddress)
        assertNull(u.strapName)
    }

    @Test fun `the strap is stored with a normalised address and name and read back`() = runBlocking<Unit> {
        val u = ana()
        val saved = (data.profiles.setStrap(u.id, " aa:bb:cc:dd:ee:0f ", "  Polar H10 1234AB  ") as StrapSaveResult.Saved).profile
        assertEquals("AA:BB:CC:DD:EE:0F", saved.strapAddress)
        assertEquals("Polar H10 1234AB", saved.strapName)
        assertEquals(saved, data.profiles.profile(u.id).first())
        assertEquals("AA:BB:CC:DD:EE:0F", data.profiles.activeUser.first()!!.strapAddress)
        // a strap without a name, then replaced by another one
        val noName = (data.profiles.setStrap(u.id, "11:22:33:44:55:66", "   ") as StrapSaveResult.Saved).profile
        assertEquals("11:22:33:44:55:66", noName.strapAddress)
        assertNull(noName.strapName)
        assertNull((data.profiles.setStrap(u.id, "11:22:33:44:55:66", null) as StrapSaveResult.Saved).profile.strapName)
    }

    @Test fun `an address that is not six pairs of hex digits is rejected`() = runBlocking<Unit> {
        val u = ana()
        for (bad in listOf("", "AA:BB", "GG:BB:CC:DD:EE:FF", "AABBCCDDEEFF", "AA-BB-CC-DD-EE-FF", "AA:BB:CC:DD:EE:FF:00", "A:B:C:D:E:F", "AA:BB:CC:DD:EE:FFF")) {
            assertEquals(bad, StrapSaveResult.InvalidAddress, data.profiles.setStrap(u.id, bad, "x"))
        }
        assertEquals(StrapSaveResult.NameTooLong, data.profiles.setStrap(u.id, "AA:BB:CC:DD:EE:FF", "x".repeat(StrapRules.NAME_MAX_LENGTH + 1)))
        assertEquals(StrapSaveResult.NotFound, data.profiles.setStrap(999, "AA:BB:CC:DD:EE:FF", null))
        assertNull("nothing was stored", data.profiles.profile(u.id).first()!!.strapAddress)
        assertTrue(data.profiles.setStrap(u.id, "AA:BB:CC:DD:EE:FF", "x".repeat(StrapRules.NAME_MAX_LENGTH)) is StrapSaveResult.Saved)
    }

    @Test fun `clearing forgets the strap and is harmless when there is none`() = runBlocking<Unit> {
        val u = ana()
        data.profiles.setStrap(u.id, "AA:BB:CC:DD:EE:FF", "Polar").let { assertTrue(it is StrapSaveResult.Saved) }
        data.profiles.clearStrap(u.id).ok()
        val p = data.profiles.profile(u.id).first()!!
        assertNull(p.strapAddress)
        assertNull(p.strapName)
        data.profiles.clearStrap(u.id).ok()
        assertEquals(DataResult.NotFound, data.profiles.clearStrap(999))
    }

    @Test fun `editing or personalising the profile keeps the strap`() = runBlocking<Unit> {
        val u = ana()
        data.profiles.setStrap(u.id, "AA:BB:CC:DD:EE:FF", "Polar").let { assertTrue(it is StrapSaveResult.Saved) }
        val edited = data.profiles.update(u.id, validInput("Ana").copy(heightCm = 172)).ok()
        assertEquals("AA:BB:CC:DD:EE:FF", edited.strapAddress)
        assertEquals("Polar", edited.strapName)
        val personalised = data.profiles.personalize(u.id, validInput("Ana B"), 62.0).ok()
        assertEquals("AA:BB:CC:DD:EE:FF", personalised.strapAddress)
    }

    @Test fun `each user has their own strap and deleting the profile deletes it`() = runBlocking<Unit> {
        val a = ana()
        val b = data.profiles.create(validInput("Beto")).ok()
        data.profiles.setStrap(a.id, "AA:BB:CC:DD:EE:01", "Ana's").let { assertTrue(it is StrapSaveResult.Saved) }
        data.profiles.setStrap(b.id, "AA:BB:CC:DD:EE:02", "Beto's").let { assertTrue(it is StrapSaveResult.Saved) }
        assertEquals(listOf("AA:BB:CC:DD:EE:01", "AA:BB:CC:DD:EE:02"), data.profiles.profiles.first().map { it.strapAddress })
        data.profiles.delete(a.id).ok()
        assertEquals(listOf("AA:BB:CC:DD:EE:02"), data.profiles.profiles.first().map { it.strapAddress })
    }
}

class RoomStrapTest : StrapContractTest() {
    override fun create(): UserData = newRoomUserData(clock)

    private val room get() = data as RoomUserData

    @Test fun `the strap is part of the synchronised profile and changing it is a change of the profile`() = runBlocking<Unit> {
        val u = data.profiles.create(validInput("Ana")).ok()
        val first = room.sync.pendingChanges().profiles.single()
        assertNull(first.strapAddress)
        room.sync.markSynced(SyncedRecords(profiles = listOf(SyncedRef(first.uuid, first.version))))
        assertTrue(room.sync.pendingChanges().isEmpty)

        clock.nowMillis += 1_000
        data.profiles.setStrap(u.id, "aa:bb:cc:dd:ee:ff", "Polar H10").let { assertTrue(it is StrapSaveResult.Saved) }
        val paired = room.sync.pendingChanges().profiles.single()
        assertEquals("AA:BB:CC:DD:EE:FF", paired.strapAddress)
        assertEquals("Polar H10", paired.strapName)
        assertTrue(paired.version > first.version)
        room.sync.markSynced(SyncedRecords(profiles = listOf(SyncedRef(paired.uuid, paired.version))))

        // the same strap again is not a change
        clock.nowMillis += 1_000
        data.profiles.setStrap(u.id, "AA:BB:CC:DD:EE:FF", "Polar H10")
        assertTrue(room.sync.pendingChanges().isEmpty)

        clock.nowMillis += 1_000
        data.profiles.clearStrap(u.id).ok()
        assertNull(room.sync.pendingChanges().profiles.single().strapAddress)
    }

    @Test fun `the second device finds the strap of a profile it receives`() = runBlocking<Unit> {
        val remote = SyncProfile(
            uuid = "user-remote", version = RecordVersion(T0 + 10, "phone"), deleted = false, name = "Beto", sex = Sex.Male, heightCm = 180,
            birthDate = LocalDate.of(1980, 1, 1), createdAtMillis = T0, strapAddress = "AA:BB:CC:DD:EE:FF", strapName = "Polar H10",
        )
        room.sync.applyRemote(RemoteChanges(profiles = listOf(remote)))
        val beto = data.profiles.profiles.first().single { it.name == "Beto" }
        assertEquals("AA:BB:CC:DD:EE:FF", beto.strapAddress)
        assertEquals("Polar H10", beto.strapName)
        // the other device changes its strap later
        room.sync.applyRemote(RemoteChanges(profiles = listOf(remote.copy(version = RecordVersion(T0 + 20, "phone"), strapAddress = "11:22:33:44:55:66", strapName = null))))
        assertEquals("11:22:33:44:55:66", data.profiles.profile(beto.id).first()!!.strapAddress)
        assertNull(data.profiles.profile(beto.id).first()!!.strapName)
        assertTrue(room.sync.pendingChanges().isEmpty)
    }
}

@RunWith(JUnit4::class)
class InMemoryStrapTest : StrapContractTest() {
    override fun create(): UserData = InMemoryUserData(clock, ZoneOffset.UTC, seedGenericProfile = false)
}

/** The record of a generated session next to its workout, the generator filter, and the history by generator. */
@RunWith(RobolectricTestRunner::class)
abstract class SessionRecordContractTest {
    protected val clock = MutableClock()
    @After fun closeDatabases() = closeRoomDatabases()

    protected abstract fun create(): UserData
    protected val data by lazy { create() }

    protected fun <T> DataResult<T>.ok(): T = (this as? DataResult.Success)?.value ?: error("expected success but was $this")

    protected suspend fun ana() = data.profiles.create(validInput("Ana")).ok()

    @Test fun `the record is stored with the workout and read back identical`() = runBlocking<Unit> {
        val u = ana()
        val id = data.addFinishedWorkout(u.id)
        assertNull(data.workouts.detail(id)!!.sessionRecord)
        assertNull(data.workouts.workouts(u.id).first().single().generatorId)

        val record = sessionRecord(session = 4)
        data.workouts.attachSessionRecord(id, record).ok()
        val detail = data.workouts.detail(id)!!
        assertEquals(record, detail.sessionRecord)
        assertEquals(GENERATOR_4X4, detail.summary.generatorId)
        assertEquals(GENERATOR_4X4, data.workouts.workouts(u.id).first().single().generatorId)
        assertEquals(DataResult.NotFound, data.workouts.attachSessionRecord(999, record))
    }

    @Test fun `the generator id of the workout is that of the plan origin`() = runBlocking<Unit> {
        val u = ana()
        val id = data.addFinishedWorkout(u.id)
        data.workouts.link(id, WorkoutLink(plan = planOf(personalised(generatorId = GENERATOR_C25K)))).ok()
        assertEquals(GENERATOR_C25K, data.workouts.workouts(u.id).first().single().generatorId)
        assertEquals(PROGRAM_2, data.workouts.workouts(u.id).first().single().programId)
        // an ordinary program has none
        val other = data.addFinishedWorkout(u.id, T0 + 1_000)
        data.workouts.link(other, WorkoutLink(plan = hillPlan())).ok()
        assertNull(data.workouts.workouts(u.id).first().first { it.id == other }.generatorId)
    }

    @Test fun `the records of a generator come newest first, for that user and generator only`() = runBlocking<Unit> {
        val a = ana()
        val b = data.profiles.create(validInput("Beto")).ok()
        val w1 = data.addFinishedWorkout(a.id, T0)
        val w2 = data.addFinishedWorkout(a.id, T0 + 3 * DAY_MS)
        val w3 = data.addFinishedWorkout(a.id, T0 + 6 * DAY_MS)
        val wOther = data.addFinishedWorkout(a.id, T0 + 7 * DAY_MS)
        val wBeto = data.addFinishedWorkout(b.id, T0 + 8 * DAY_MS)
        val noRecord = data.addFinishedWorkout(a.id, T0 + 9 * DAY_MS)
        data.workouts.attachSessionRecord(w1, sessionRecord(GENERATOR_C25K, T0, session = 1)).ok()
        data.workouts.attachSessionRecord(w2, sessionRecord(GENERATOR_C25K, T0 + 3 * DAY_MS, session = 2)).ok()
        data.workouts.attachSessionRecord(w3, sessionRecord(GENERATOR_C25K, T0 + 6 * DAY_MS, session = 3)).ok()
        data.workouts.attachSessionRecord(wOther, sessionRecord(GENERATOR_4X4, T0 + 7 * DAY_MS)).ok()
        data.workouts.attachSessionRecord(wBeto, sessionRecord(GENERATOR_C25K, T0 + 8 * DAY_MS, session = 9)).ok()
        assertNotNull(noRecord)

        val records = data.workouts.sessionRecords(a.id, GENERATOR_C25K).first()
        assertEquals(listOf(w3, w2, w1), records.map { it.workoutId })
        assertEquals(listOf("3", "2", "1"), records.map { it.record.origin.inputs["session"] })
        assertEquals(listOf(T0 + 6 * DAY_MS, T0 + 3 * DAY_MS, T0), records.map { it.workoutStartedAtMillis })
        assertEquals(listOf(wOther), data.workouts.sessionRecords(a.id, GENERATOR_4X4).first().map { it.workoutId })
        assertEquals(listOf(wBeto), data.workouts.sessionRecords(b.id, GENERATOR_C25K).first().map { it.workoutId })
        assertTrue(data.workouts.sessionRecords(a.id, "unknown").first().isEmpty())

        // a deleted workout takes its record away
        data.workouts.delete(w3).ok()
        assertEquals(listOf(w2, w1), data.workouts.sessionRecords(a.id, GENERATOR_C25K).first().map { it.workoutId })
    }

    @Test fun `attaching a different record replaces it`() = runBlocking<Unit> {
        val u = ana()
        val id = data.addFinishedWorkout(u.id)
        data.workouts.attachSessionRecord(id, sessionRecord(outcome = com.treadz.console.programs.personalised.SessionOutcome.Stopped)).ok()
        data.workouts.attachSessionRecord(id, sessionRecord()).ok()
        assertEquals(com.treadz.console.programs.personalised.SessionOutcome.Completed, data.workouts.detail(id)!!.sessionRecord!!.outcome)
        assertEquals(1, data.workouts.sessionRecords(u.id, GENERATOR_4X4).first().size)
    }

    @Test fun `deleting the profile deletes the records`() = runBlocking<Unit> {
        val u = ana()
        val id = data.addFinishedWorkout(u.id)
        data.workouts.attachSessionRecord(id, sessionRecord()).ok()
        data.profiles.delete(u.id).ok()
        assertNull(data.workouts.detail(id))
        assertTrue(data.workouts.sessionRecords(u.id, GENERATOR_4X4).first().isEmpty())
    }

    @Test fun `personalised programs are stored with their origin intact`() = runBlocking<Unit> {
        val u = ana()
        val program = personalised()
        val saved = (data.programs.save(u.id, program) as ProgramSaveResult.Saved).program
        assertEquals(ProgramKind.Personalised, saved.program.kind)
        assertEquals(origin(), saved.program.origin)
        assertEquals(program, data.programs.get(PROGRAM_2)!!.program)
        assertEquals(listOf(origin()), data.programs.programs(u.id).first().map { it.program.origin })
        // a free program has none
        assertNull((data.programs.save(u.id, norwegian()) as ProgramSaveResult.Saved).program.program.origin)
    }
}

class RoomSessionRecordTest : SessionRecordContractTest() {
    override fun create(): UserData = newRoomUserData(clock)

    private val room get() = data as RoomUserData

    private suspend fun syncProfiles() {
        val p = room.sync.pendingChanges().profiles
        room.sync.markSynced(SyncedRecords(profiles = p.map { SyncedRef(it.uuid, it.version) }))
    }

    private suspend fun beginHeld(userId: Long, link: WorkoutLink? = null): Long {
        val store = data.workouts as com.treadz.console.userdata.workout.WorkoutSessionStore
        val id = store.begin(userId, T0, link)
        data.workouts.holdUpload(id)
        return id
    }

    private suspend fun finish(id: Long) {
        val store = data.workouts as com.treadz.console.userdata.workout.WorkoutSessionStore
        store.finish(id, strapSamples(), WorkoutStats(durationSeconds = 6, distanceMeters = 12, peakHeartRate = HeartRatePeak(150, HeartRateSourceKind.DirectStrap, T0 + 5_000)), T0 + 6_000, WorkoutEndReason.Stopped)
    }

    @Test fun `the record can be attached to the workout in progress`() = runBlocking<Unit> {
        val u = ana()
        val id = (data.workouts as com.treadz.console.userdata.workout.WorkoutSessionStore).begin(u.id, T0)
        data.workouts.attachSessionRecord(id, sessionRecord()).ok()
        assertNotNull(data.workouts.detail(id)!!.sessionRecord)
        assertTrue(data.workouts.workouts(u.id).first().single().isInProgress)
        assertEquals(listOf(id), data.workouts.sessionRecords(u.id, GENERATOR_4X4).first().map { it.workoutId })
    }

    @Test fun `a held workout keeps its record out of the upload until it is released, then uploads it`() = runBlocking<Unit> {
        val u = ana()
        syncProfiles()
        val id = beginHeld(u.id, WorkoutLink(plan = planOf(personalised())))
        // the program finished while the belt was still stopping: the record is attached to the workout in progress
        data.workouts.attachSessionRecord(id, sessionRecord()).ok()
        finish(id)
        assertTrue(room.sync.pendingChanges().workouts.isEmpty())
        assertEquals(0, room.sync.pendingCount.first())

        data.workouts.releaseUpload(id)
        val w = room.sync.pendingChanges().workouts.single()
        assertEquals(sessionRecord(), GeneratedSessionJson.decode(w.sessionRecordJson!!))
        assertEquals(150, w.stats.peakHeartRate!!.bpm)
        assertEquals(HeartRateSourceKind.DirectStrap, w.stats.peakHeartRate!!.source)
        assertNotNull(w.planJson)
    }

    @Test fun `a record attached after the workout was kept and uploaded makes it upload again with the record`() = runBlocking<Unit> {
        val u = ana()
        syncProfiles()
        val id = beginHeld(u.id)
        finish(id)
        data.workouts.releaseUpload(id)
        val first = room.sync.pendingChanges().workouts.single()
        assertNull(first.sessionRecordJson)
        room.sync.markSynced(SyncedRecords(workouts = listOf(SyncedRef(first.uuid, first.version))))
        assertTrue(room.sync.pendingChanges().workouts.isEmpty())

        clock.nowMillis += 1_000
        data.workouts.attachSessionRecord(id, sessionRecord()).ok()
        val second = room.sync.pendingChanges().workouts.single()
        assertNotNull(second.sessionRecordJson)
        assertTrue(second.version > first.version)
        // the same record again changes nothing
        room.sync.markSynced(SyncedRecords(workouts = listOf(SyncedRef(second.uuid, second.version))))
        data.workouts.attachSessionRecord(id, sessionRecord()).ok()
        assertTrue(room.sync.pendingChanges().workouts.isEmpty())
    }

    @Test fun `discarding a held workout leaves no record and nothing to upload`() = runBlocking<Unit> {
        val u = ana()
        syncProfiles()
        val id = beginHeld(u.id, WorkoutLink(plan = planOf(personalised())))
        data.workouts.attachSessionRecord(id, sessionRecord()).ok()
        finish(id)
        data.workouts.discardFinished(id).ok()
        assertNull(data.workouts.detail(id))
        assertTrue(data.workouts.sessionRecords(u.id, GENERATOR_4X4).first().isEmpty())
        assertTrue(room.sync.pendingChanges().workouts.isEmpty())
        assertTrue(room.sync.pendingChanges().isEmpty)
    }

    @Test fun `a deleted workout is a tombstone without plan or record`() = runBlocking<Unit> {
        val u = ana()
        syncProfiles()
        val id = data.addFinishedWorkout(u.id)
        data.workouts.attachSessionRecord(id, sessionRecord()).ok()
        val uploaded = room.sync.pendingChanges().workouts.single()
        assertNotNull(uploaded.sessionRecordJson)
        room.sync.markSynced(SyncedRecords(workouts = listOf(SyncedRef(uploaded.uuid, uploaded.version))))
        clock.nowMillis += 1_000
        data.workouts.delete(id).ok()
        val tombstone = room.sync.pendingChanges().workouts.single()
        assertTrue(tombstone.deleted)
        assertNull(tombstone.sessionRecordJson)
        assertNull(tombstone.planJson)
    }

    @Test fun `a record written by a newer app is kept for the sync but skipped by the generators`() = runBlocking<Unit> {
        val u = ana()
        val older = data.addFinishedWorkout(u.id, T0)
        val newer = data.addFinishedWorkout(u.id, T0 + DAY_MS)
        data.workouts.attachSessionRecord(older, sessionRecord(startedAt = T0)).ok()
        data.workouts.attachSessionRecord(newer, sessionRecord(startedAt = T0 + DAY_MS, schemaVersion = 99)).ok()
        assertNull(data.workouts.detail(newer)!!.sessionRecord)
        assertEquals(listOf(older), data.workouts.sessionRecords(u.id, GENERATOR_4X4).first().map { it.workoutId })
        // still part of the workout document that is uploaded
        val doc = room.sync.pendingChanges().workouts.first { it.startedAtMillis == T0 + DAY_MS }
        assertTrue(doc.sessionRecordJson!!.contains("\"schemaVersion\": 99"))
        assertEquals(GENERATOR_4X4, data.workouts.workouts(u.id).first().first { it.id == newer }.generatorId)
    }

    @Test fun `personalised programs are synchronised as documents with their origin`() = runBlocking<Unit> {
        val u = ana()
        data.programs.save(u.id, personalised())
        val doc = room.programDocuments.pending().single()
        assertEquals(origin(), ProgramJson.decode(doc.json, validate = false).origin)
        assertEquals(ProgramKind.Personalised, ProgramJson.decode(doc.json, validate = false).kind)
        assertTrue(doc.json.contains("\"generatorId\": \"$GENERATOR_4X4\""))
        assertFalse(doc.deleted)
    }
}

@RunWith(JUnit4::class)
class InMemorySessionRecordTest : SessionRecordContractTest() {
    override fun create(): UserData = InMemoryUserData(clock, ZoneOffset.UTC, seedGenericProfile = false)
}
