package com.treadz.console.userdata

import com.treadz.console.programs.fitness.FitnessTestJson
import com.treadz.console.programs.json.PlanJson
import com.treadz.console.programs.json.ProgramJson
import com.treadz.console.programs.plan.StartParameters
import com.treadz.console.treadmill.BeltStatus
import com.treadz.console.userdata.room.RoomUserData
import com.treadz.console.userdata.room.RoomWorkoutRepository
import com.treadz.console.userdata.room.Stamper
import com.treadz.console.userdata.room.UserDataDatabase
import com.treadz.console.userdata.sync.FitnessTestDocument
import com.treadz.console.userdata.sync.ProgramDocument
import com.treadz.console.userdata.sync.RecordVersion
import com.treadz.console.userdata.sync.RemoteChanges
import com.treadz.console.userdata.sync.SyncProfile
import com.treadz.console.userdata.sync.SyncWorkout
import com.treadz.console.userdata.sync.SyncWorkoutData
import com.treadz.console.userdata.sync.SyncedRecords
import com.treadz.console.userdata.sync.SyncedRef
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * The Room data layer as the `:sync` module sees it for programs and fitness tests: pending flags, last writer wins,
 * tombstones, the cascade of a deleted profile, the read-only fitness block of the profile and the plan of a workout.
 */
@RunWith(RobolectricTestRunner::class)
class DocumentSyncStoreTest {

    @After fun closeDatabases() = closeRoomDatabases()

    private val clock = MutableClock()
    private var counter = 0
    private val db: UserDataDatabase = newRoomDatabase()
    private val data = RoomUserData(db, clock, ZoneOffset.UTC, seedGenericProfile = false, deviceId = "dev-a", uuidSource = { "uuid-${++counter}" })
    private val programs get() = data.programDocuments
    private val tests get() = data.fitnessTestDocuments
    private val sessions = RoomWorkoutRepository(db, Stamper(clock, "dev-a") { "w-${++counter}" })

    private fun <T> ok(r: DataResult<T>): T = (r as DataResult.Success).value

    private suspend fun createAna() = ok(data.profiles.create(validInput("Ana")))

    private suspend fun anaUuid() = data.sync.pendingChanges().profiles.first { it.name == "Ana" }.uuid

    private fun programDoc(id: String, user: String, at: Long, device: String = "dev-b", deleted: Boolean = false, name: String = "Remote") =
        ProgramDocument(id, user, RecordVersion(at, device), deleted, if (deleted) "" else ProgramJson.encode(norwegian(id, name)))

    private fun testDoc(id: String, user: String, at: Long, device: String = "dev-b", deleted: Boolean = false, vo2: Double = 41.0, startedAt: Long = T0 - DAY_MS) =
        FitnessTestDocument(id, user, RecordVersion(at, device), deleted, if (deleted) "" else FitnessTestJson.encode(ebbelingResult(id, startedAt, vo2)))

    // ---- programs: local changes ----

    @Test fun `a saved program is a pending document of its owner, and markSynced clears it`() = runBlocking<Unit> {
        val ana = createAna()
        data.programs.save(ana.id, norwegian())
        assertEquals(1, programs.pendingCount.first())
        val doc = programs.pending().single()
        assertEquals(PROGRAM_1, doc.uuid)
        assertEquals(anaUuid(), doc.userUuid)
        assertEquals(RecordVersion(T0, "dev-a"), doc.version)
        assertFalse(doc.deleted)
        assertEquals(norwegian(), ProgramJson.decode(doc.json))

        programs.markSynced(listOf(SyncedRef(PROGRAM_1, doc.version)))
        assertEquals(emptyList<ProgramDocument>(), programs.pending())
        assertEquals(0, programs.pendingCount.first())
    }

    @Test fun `a change after the upload stays pending, and an old reference does not clear it`() = runBlocking<Unit> {
        val ana = createAna()
        data.programs.save(ana.id, norwegian())
        val first = programs.pending().single()
        clock.nowMillis = T0 + 1_000
        data.programs.save(ana.id, norwegian(name = "Edited"))
        programs.markSynced(listOf(SyncedRef(PROGRAM_1, first.version)))
        assertEquals(1, programs.pending().size)
        val second = programs.pending().single()
        assertEquals(RecordVersion(T0 + 1_000, "dev-a"), second.version)
        programs.markSynced(listOf(SyncedRef(PROGRAM_1, second.version)))
        assertTrue(programs.pending().isEmpty())
    }

    @Test fun `saving an identical program is not a change`() = runBlocking<Unit> {
        val ana = createAna()
        data.programs.save(ana.id, norwegian())
        programs.markSynced(listOf(SyncedRef(PROGRAM_1, programs.pending().single().version)))
        clock.nowMillis = T0 + 1_000
        data.programs.save(ana.id, norwegian())
        assertTrue(programs.pending().isEmpty())
    }

    @Test fun `deleting a program leaves a pending tombstone without the document`() = runBlocking<Unit> {
        val ana = createAna()
        data.programs.save(ana.id, norwegian())
        programs.markSynced(listOf(SyncedRef(PROGRAM_1, programs.pending().single().version)))
        clock.nowMillis = T0 + 2_000
        data.programs.delete(PROGRAM_1)
        val tomb = programs.pending().single()
        assertTrue(tomb.deleted)
        assertEquals("", tomb.json)
        assertEquals(RecordVersion(T0 + 2_000, "dev-a"), tomb.version)
        assertEquals(anaUuid(), tomb.userUuid)
    }

    @Test fun `programs of the generic profile are not uploaded until it is personalised`() = runBlocking<Unit> {
        val seeded = RoomUserData(newRoomDatabase(), clock, ZoneOffset.UTC, seedGenericProfile = true, deviceId = "dev-g")
        val generic = seeded.profiles.activeUser.first()!!
        seeded.programs.save(generic.id, norwegian())
        seeded.fitnessTests.save(generic.id, ebbelingResult())
        assertTrue(seeded.programDocuments.pending().isEmpty())
        assertTrue(seeded.fitnessTestDocuments.pending().isEmpty())
        seeded.profiles.personalize(generic.id, validInput("Ana"), 61.0)
        assertEquals(1, seeded.programDocuments.pending().size)
        assertEquals(1, seeded.fitnessTestDocuments.pending().size)
    }

    // ---- programs: remote changes ----

    @Test fun `a remote program appears in the repository and is not pending`() = runBlocking<Unit> {
        val ana = createAna()
        val applied = programs.applyRemote(listOf(programDoc(PROGRAM_1, anaUuid(), T0 + 5_000, name = "From phone")))
        assertEquals(1, applied)
        val p = data.programs.programs(ana.id).first().single()
        assertEquals("From phone", p.program.name)
        assertEquals(PROGRAM_1, p.id)
        assertTrue(programs.pending().isEmpty())
        assertEquals(T0 + 5_000, p.updatedAtMillis)
    }

    @Test fun `the later version of a program wins in both directions, and equal versions are in agreement`() = runBlocking<Unit> {
        val ana = createAna()
        val user = anaUuid()
        data.programs.save(ana.id, norwegian(name = "Mine")) // T0, dev-a, pending

        // older remote: ignored, ours stays pending
        assertEquals(0, programs.applyRemote(listOf(programDoc(PROGRAM_1, user, T0 - 1, name = "Old"))))
        assertEquals("Mine", data.programs.get(PROGRAM_1)!!.program.name)
        assertEquals(1, programs.pending().size)

        // same time, greater device id wins ("dev-b" > "dev-a")
        assertEquals(1, programs.applyRemote(listOf(programDoc(PROGRAM_1, user, T0, "dev-b", name = "Theirs"))))
        assertEquals("Theirs", data.programs.get(PROGRAM_1)!!.program.name)
        assertTrue(programs.pending().isEmpty())

        // identical version again: nothing applied
        assertEquals(0, programs.applyRemote(listOf(programDoc(PROGRAM_1, user, T0, "dev-b", name = "Theirs"))))

        // a local edit after that is newer than what we applied
        clock.nowMillis = T0 + 10
        data.programs.save(ana.id, norwegian(name = "Mine again"))
        assertEquals(1, programs.pending().size)
    }

    @Test fun `an equal version clears our pending flag`() = runBlocking<Unit> {
        val ana = createAna()
        data.programs.save(ana.id, norwegian())
        val mine = programs.pending().single()
        assertEquals(0, programs.applyRemote(listOf(mine)))
        assertTrue(programs.pending().isEmpty())
    }

    @Test fun `a remote tombstone deletes the program and its last used parameters`() = runBlocking<Unit> {
        val ana = createAna()
        data.programs.save(ana.id, norwegian())
        data.startParameters.save(ana.id, PROGRAM_1, StartParameters(totalDurationSeconds = 1800))
        assertEquals(1, programs.applyRemote(listOf(programDoc(PROGRAM_1, anaUuid(), T0 + 100, deleted = true))))
        assertNull(data.programs.get(PROGRAM_1))
        assertNull(data.startParameters.lastUsed(ana.id, PROGRAM_1).first())
        assertTrue(programs.pending().isEmpty())
        // a later remote version brings it back
        assertEquals(1, programs.applyRemote(listOf(programDoc(PROGRAM_1, anaUuid(), T0 + 200, name = "Back"))))
        assertEquals("Back", data.programs.get(PROGRAM_1)!!.program.name)
    }

    @Test fun `the tombstone of an unknown program and documents of unknown or deleted owners are skipped`() = runBlocking<Unit> {
        val ana = createAna()
        val user = anaUuid()
        assertEquals(0, programs.applyRemote(listOf(programDoc(PROGRAM_1, user, T0, deleted = true))))
        assertEquals(0, programs.applyRemote(listOf(programDoc(PROGRAM_2, "nobody", T0))))
        data.profiles.delete(ana.id)
        assertEquals(0, programs.applyRemote(listOf(programDoc(PROGRAM_2, user, T0 + 1))))
        assertTrue(programs.pending().isEmpty())
    }

    @Test fun `a document that cannot be read is kept for the sync but not listed`() = runBlocking<Unit> {
        val ana = createAna()
        val newer = """{"schemaVersion": 99, "id": "$PROGRAM_1", "name": "From the future", "kind": "free", "steps": []}"""
        assertEquals(1, programs.applyRemote(listOf(ProgramDocument(PROGRAM_1, anaUuid(), RecordVersion(T0 + 1, "dev-b"), false, newer))))
        assertTrue(data.programs.programs(ana.id).first().isEmpty())
        assertNull(data.programs.get(PROGRAM_1))
        // the user can still not delete what they cannot see, but the document is there for the next sync round
        assertTrue(programs.pending().isEmpty())
    }

    // ---- deleting a profile ----

    @Test fun `deleting a profile tombstones its programs and tests without uploading them one by one`() = runBlocking<Unit> {
        val ana = createAna()
        data.programs.save(ana.id, norwegian())
        data.fitnessTests.save(ana.id, ebbelingResult())
        data.startParameters.save(ana.id, "standard-hill", StartParameters(totalDurationSeconds = 1800))
        data.profiles.delete(ana.id)
        assertTrue(programs.pending().isEmpty())
        assertTrue(tests.pending().isEmpty())
        assertEquals(0, programs.pendingCount.first())
        assertEquals(0, tests.pendingCount.first())
        assertNull(data.programs.get(PROGRAM_1))
        assertNull(data.fitnessTests.get(TEST_1))
    }

    @Test fun `a remote deletion of the profile takes its programs and tests with it`() = runBlocking<Unit> {
        val ana = createAna()
        val user = anaUuid()
        data.programs.save(ana.id, norwegian())
        data.fitnessTests.save(ana.id, ebbelingResult())
        data.startParameters.save(ana.id, "standard-hill", StartParameters(totalDurationSeconds = 1800))
        data.sync.applyRemote(
            RemoteChanges(profiles = listOf(SyncProfile(user, RecordVersion(T0 + 10_000, "dev-b"), true, "Ana", Sex.Male, 0, LocalDate.EPOCH))),
        )
        assertNull(data.programs.get(PROGRAM_1))
        assertNull(data.fitnessTests.get(TEST_1))
        assertTrue(programs.pending().isEmpty())
        assertTrue(tests.pending().isEmpty())
    }

    // ---- fitness tests ----

    @Test fun `a saved test is a pending document, and a deletion a tombstone`() = runBlocking<Unit> {
        val ana = createAna()
        data.fitnessTests.save(ana.id, ebbelingResult())
        val doc = tests.pending().single()
        assertEquals(TEST_1, doc.uuid)
        assertEquals(anaUuid(), doc.userUuid)
        assertEquals(ebbelingResult(), FitnessTestJson.decode(doc.json))
        tests.markSynced(listOf(SyncedRef(TEST_1, doc.version)))
        assertTrue(tests.pending().isEmpty())

        clock.nowMillis = T0 + 1_000
        data.fitnessTests.delete(TEST_1)
        val tomb = tests.pending().single()
        assertTrue(tomb.deleted)
        assertEquals("", tomb.json)
        assertEquals(T0 + 1_000, tomb.version.updatedAtMillis)
    }

    @Test fun `remote tests merge by version, with tombstones and unknown owners skipped`() = runBlocking<Unit> {
        val ana = createAna()
        val user = anaUuid()
        assertEquals(1, tests.applyRemote(listOf(testDoc(TEST_1, user, T0 + 100, vo2 = 43.0))))
        assertEquals(43.0, data.fitnessTests.get(TEST_1)!!.result.outputs!!.vo2maxMlKgMin, 0.0)
        assertTrue(tests.pending().isEmpty())
        assertEquals(0, tests.applyRemote(listOf(testDoc(TEST_1, user, T0 + 50, vo2 = 30.0)))) // older
        assertEquals(1, tests.applyRemote(listOf(testDoc(TEST_1, user, T0 + 200, vo2 = 44.0)))) // newer
        assertEquals(44.0, data.fitnessTests.get(TEST_1)!!.result.outputs!!.vo2maxMlKgMin, 0.0)
        assertEquals(1, tests.applyRemote(listOf(testDoc(TEST_1, user, T0 + 300, deleted = true))))
        assertNull(data.fitnessTests.get(TEST_1))
        assertEquals(0, tests.applyRemote(listOf(testDoc(TEST_2, user, T0, deleted = true))))
        assertEquals(0, tests.applyRemote(listOf(testDoc(TEST_2, "nobody", T0))))
        assertTrue(data.fitnessTests.results(ana.id).first().isEmpty())
    }

    @Test fun `a downloaded test changes the fitness profile`() = runBlocking<Unit> {
        val ana = createAna()
        assertNull(data.fitnessTests.fitnessProfile(ana.id).first())
        tests.applyRemote(listOf(testDoc(TEST_1, anaUuid(), T0, vo2 = 43.0)))
        assertEquals(43.0, data.fitnessTests.fitnessProfile(ana.id).first()!!.vo2max!!.valueMlKgMin, 0.0)
    }

    // ---- the profile record ----

    @Test fun `the sync profile carries the manual maximum heart rate time, and the fitness snapshot is derived apart`() = runBlocking<Unit> {
        val ana = ok(data.profiles.create(validInput().copy(maxHeartRateBpm = 185)))
        val user = anaUuid()
        assertNull(data.sync.fitness(user))
        assertEquals(T0, data.sync.profile(user)!!.maxHeartRateSetAtMillis)

        data.fitnessTests.save(ana.id, ebbelingResult(vo2max = 42.0))
        val derived = data.sync.fitness(user)!!
        assertEquals(data.fitnessTests.fitnessProfile(ana.id).first(), derived.profile)
        assertEquals(42.0, derived.profile.vo2max!!.valueMlKgMin, 0.0)
        assertEquals(user, derived.userUuid)
    }

    @Test fun `a change of the results does not change the version of the profile`() = runBlocking<Unit> {
        val ana = createAna()
        val user = anaUuid()
        data.sync.markSynced(SyncedRecords(profiles = listOf(SyncedRef(user, data.sync.profile(user)!!.version))))
        assertTrue(data.sync.pendingChanges().profiles.isEmpty())
        val before = data.sync.profile(user)!!.version

        clock.nowMillis = T0 + 9_000
        data.fitnessTests.save(ana.id, ebbelingResult())
        data.fitnessTests.delete(TEST_1)
        data.fitnessTests.save(ana.id, ebbelingResult(TEST_3))
        assertEquals(before, data.sync.profile(user)!!.version)
        assertTrue(data.sync.pendingChanges().profiles.isEmpty())
    }

    @Test fun `a downloaded profile brings the time of the manual maximum heart rate`() = runBlocking<Unit> {
        val remote = SyncProfile(
            "u-remote", RecordVersion(T0 + 1_000, "dev-b"), false, "Beto", Sex.Male, 180, LocalDate.of(1980, 1, 1),
            maxHeartRateBpm = 190, createdAtMillis = T0, maxHeartRateSetAtMillis = T0 + 500,
        )
        data.sync.applyRemote(RemoteChanges(profiles = listOf(remote)))
        val beto = data.profiles.profiles.first().single()
        assertEquals(190, beto.maxHeartRateBpm)
        assertEquals(T0 + 500, beto.maxHeartRateSetAtMillis)
        // the fitness profile comes from the results (there are none)
        assertNull(data.sync.fitness("u-remote"))
        assertNull(data.fitnessTests.fitnessProfile(beto.id).first())
        assertTrue(data.sync.pendingChanges().profiles.isEmpty())
    }

    @Test fun `answering the heart rate question is a profile change`() = runBlocking<Unit> {
        val ana = ok(data.profiles.create(validInput().copy(maxHeartRateBpm = 170)))
        val user = anaUuid()
        clock.nowMillis = T0 + 3 * DAY_MS
        data.fitnessTests.save(ana.id, maxTestResult(TEST_2, T0 + DAY_MS, peak = 186))
        data.sync.markSynced(SyncedRecords(profiles = listOf(SyncedRef(user, data.sync.profile(user)!!.version))))

        clock.nowMillis = T0 + 4 * DAY_MS
        data.fitnessTests.keepManualHeartRateMax(ana.id)
        val kept = data.sync.pendingChanges().profiles.single()
        assertEquals(170, kept.maxHeartRateBpm)
        assertEquals(T0 + 4 * DAY_MS, kept.maxHeartRateSetAtMillis)
        data.sync.markSynced(SyncedRecords(profiles = listOf(SyncedRef(user, kept.version))))

        clock.nowMillis = T0 + 5 * DAY_MS
        data.fitnessTests.useMeasuredHeartRateMax(ana.id)
        val adopted = data.sync.pendingChanges().profiles.single()
        assertNull(adopted.maxHeartRateBpm)
        assertNull(adopted.maxHeartRateSetAtMillis)
        assertTrue(adopted.version > kept.version)
    }

    @Test fun `a manual value without a time, as migrated, counts as older than a test`() = runBlocking<Unit> {
        val ana = ok(data.profiles.create(validInput().copy(maxHeartRateBpm = 170)))
        clock.nowMillis = T0 + 3 * DAY_MS
        data.fitnessTests.save(ana.id, maxTestResult(TEST_2, T0 - DAY_MS, peak = 186))
        assertFalse(data.fitnessTests.heartRateMax(ana.id).first()!!.askUser) // the value (T0) is newer than the test

        db.openHelper.writableDatabase.execSQL("UPDATE users SET maxHeartRateSetAtMillis = NULL")
        val decision = data.fitnessTests.heartRateMax(ana.id).first()!!
        assertTrue(decision.askUser)
        assertEquals(170, decision.bpm)
        assertEquals(16, decision.differenceBpm)
    }

    // ---- workouts ----

    private suspend fun finishedWorkout(userId: Long, link: WorkoutLink? = null): Long {
        val id = sessions.begin(userId, T0, link)
        val sample = WorkoutSample(0, T0, 1, 8.0, 8.0, 0.0, 2, 0, null, BeltStatus.Running)
        sessions.finish(id, listOf(sample), WorkoutStats(durationSeconds = 1, distanceMeters = 2), T0 + 1000, WorkoutEndReason.Stopped)
        return id
    }

    @Test fun `the plan and the test of a workout are exposed to the sync`() = runBlocking<Unit> {
        val ana = createAna()
        val plan = hillPlan()
        finishedWorkout(ana.id, WorkoutLink(plan = plan, fitnessTestId = TEST_1))
        val w = data.sync.pendingChanges().workouts.single()
        assertEquals(TEST_1, w.fitnessTestId)
        assertEquals(plan, PlanJson.decode(w.planJson!!))
    }

    @Test fun `linking a finished workout uploads it again with the link`() = runBlocking<Unit> {
        val ana = createAna()
        val id = finishedWorkout(ana.id)
        val first = data.sync.pendingChanges().workouts.single()
        data.sync.markSynced(SyncedRecords(workouts = listOf(SyncedRef(first.uuid, first.version))))
        assertTrue(data.sync.pendingChanges().workouts.isEmpty())

        clock.nowMillis = T0 + 60_000
        data.workouts.link(id, WorkoutLink(plan = hillPlan()))
        val second = data.sync.pendingChanges().workouts.single()
        assertEquals(first.uuid, second.uuid)
        assertTrue(second.version > first.version)
        assertNotNull(second.planJson)

        // linking what is there changes nothing
        data.sync.markSynced(SyncedRecords(workouts = listOf(SyncedRef(second.uuid, second.version))))
        clock.nowMillis = T0 + 120_000
        data.workouts.link(id, WorkoutLink(plan = hillPlan()))
        assertTrue(data.sync.pendingChanges().workouts.isEmpty())
    }

    @Test fun `a downloaded workout carries its plan and test, and an older one without them stays valid`() = runBlocking<Unit> {
        val ana = createAna()
        val user = anaUuid()
        val plan = hillPlan()
        fun remote(uuid: String, planJson: String?, testId: String?) = SyncWorkoutData(
            SyncWorkout(
                uuid, user, "Ana", RecordVersion(T0 + 100, "dev-b"), false, T0, T0 + 2000, WorkoutEndReason.Stopped,
                WorkoutStats(durationSeconds = 2, distanceMeters = 4), planJson, testId,
            ),
            listOf(WorkoutSample(0, T0, 1, 8.0, 8.0, 0.0, 2, 0, null, BeltStatus.Running)),
        )
        data.sync.applyRemote(
            RemoteChanges(workouts = listOf(remote("w-new", PlanJson.encode(plan), TEST_1), remote("w-old", null, null))),
        )
        val list = data.workouts.workouts(ana.id).first()
        assertEquals(2, list.size)
        val withPlan = list.first { it.programId != null }
        assertEquals("standard-hill", withPlan.programId)
        assertEquals(plan.source.programName, withPlan.programName)
        assertEquals(TEST_1, withPlan.fitnessTestId)
        assertEquals(plan, data.workouts.detail(withPlan.id)!!.plan)
        val plain = list.first { it.programId == null }
        assertNull(plain.fitnessTestId)
        assertNull(data.workouts.detail(plain.id)!!.plan)
        assertTrue(data.sync.pendingChanges().workouts.isEmpty())
    }

    @Test fun `an unreadable plan is kept but not exposed`() = runBlocking<Unit> {
        val ana = createAna()
        val future = """{"planSchemaVersion": 99, "source": {}}"""
        data.sync.applyRemote(
            RemoteChanges(
                workouts = listOf(
                    SyncWorkoutData(
                        SyncWorkout(
                            "w-future", anaUuid(), "Ana", RecordVersion(T0 + 100, "dev-b"), false, T0, T0 + 2000,
                            WorkoutEndReason.Stopped, WorkoutStats(durationSeconds = 2), future,
                        ),
                        emptyList(),
                    ),
                ),
            ),
        )
        val w = data.workouts.workouts(ana.id).first().single()
        assertNull(w.programId)
        assertNull(data.workouts.detail(w.id)!!.plan)
    }

    @Test fun `a deleted workout drops its stored plan`() = runBlocking<Unit> {
        val ana = createAna()
        val id = finishedWorkout(ana.id, WorkoutLink(plan = hillPlan()))
        data.workouts.delete(id)
        val tomb = data.sync.pendingChanges().workouts.single()
        assertTrue(tomb.deleted)
        assertNull(tomb.planJson)
    }
}
