package com.treadz.console.userdata

import com.treadz.console.treadmill.BeltStatus
import com.treadz.console.userdata.room.RoomUserData
import com.treadz.console.userdata.room.RoomWorkoutRepository
import com.treadz.console.userdata.room.Stamper
import com.treadz.console.userdata.room.UserDataDatabase
import com.treadz.console.userdata.sync.RecordVersion
import com.treadz.console.userdata.sync.RemoteChanges
import com.treadz.console.userdata.sync.SyncProfile
import com.treadz.console.userdata.sync.SyncWeightEntry
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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.LocalDate
import java.time.ZoneOffset

/** Change tracking, tombstones and merging of remote records in the Room data layer (the `:sync` port). */
@RunWith(RobolectricTestRunner::class)
class SyncStoreTest {

    @After fun closeDatabases() = closeRoomDatabases()

    private val clock = MutableClock()
    private var counter = 0
    private val db: UserDataDatabase = newRoomDatabase()
    private val data = RoomUserData(db, clock, ZoneOffset.UTC, seedGenericProfile = false, deviceId = "dev-a", uuidSource = { "uuid-${++counter}" })
    private val sync get() = data.sync
    private val sessions = RoomWorkoutRepository(db, Stamper(clock, "dev-a") { "w-${++counter}" })

    private fun <T> ok(r: DataResult<T>): T = (r as DataResult.Success).value

    private suspend fun createAna() = ok(data.profiles.create(validInput("Ana")))

    private suspend fun pending() = sync.pendingChanges()

    /** Finishes a recorded workout for [userId] and returns its local id. */
    private suspend fun finishedWorkout(userId: Long?, startedAt: Long = T0): Long {
        val id = sessions.begin(userId, startedAt)
        val sample = WorkoutSample(0, startedAt, 1, 8.0, 8.0, 0.0, 2, 0, null, BeltStatus.Running)
        sessions.finish(id, listOf(sample), WorkoutStats(durationSeconds = 1, distanceMeters = 2), startedAt + 1000, WorkoutEndReason.Stopped)
        return id
    }

    private fun profile(uuid: String, name: String, at: Long, device: String = "dev-b", deleted: Boolean = false) = SyncProfile(
        uuid = uuid, version = RecordVersion(at, device), deleted = deleted, name = name, sex = Sex.Female, heightCm = 170,
        birthDate = LocalDate.of(1990, 1, 1), targetWeightKg = null, createdAtMillis = at,
    )

    private fun weight(uuid: String, user: String, at: Long, kg: Double, device: String = "dev-b", deleted: Boolean = false) =
        SyncWeightEntry(uuid, user, RecordVersion(at, device), deleted, LocalDate.of(2026, 10, 1), kg)

    private fun workout(uuid: String, user: String, at: Long, device: String = "dev-b", deleted: Boolean = false) = SyncWorkoutData(
        SyncWorkout(
            uuid, user, "Remote", RecordVersion(at, device), deleted, startedAtMillis = T0, endedAtMillis = T0 + 2000,
            endReason = WorkoutEndReason.Stopped, stats = WorkoutStats(durationSeconds = 2, distanceMeters = 4),
        ),
        if (deleted) emptyList() else listOf(WorkoutSample(0, T0, 1, 8.0, 8.0, 0.0, 2, 0, null, BeltStatus.Running)),
    )

    // ---- local changes become pending ----

    @Test fun `new records are pending, with a uuid and the device that made them`() = runBlocking<Unit> {
        val ana = createAna()
        data.weights.add(ana.id, LocalDate.of(2026, 10, 1), 61.0)
        val workoutId = finishedWorkout(ana.id)
        val p = pending()
        assertEquals(listOf("Ana"), p.profiles.map { it.name })
        assertEquals(1, p.weights.size)
        assertEquals(1, p.workouts.size)
        assertEquals(listOf(workoutId), sync.pendingWorkoutIds.first().toList())
        assertEquals(3, sync.pendingCount.first())
        assertEquals("dev-a", p.profiles.single().version.deviceId)
        assertEquals(p.profiles.single().uuid, p.weights.single().userUuid)
        assertEquals(p.profiles.single().uuid, p.workouts.single().userUuid)
        assertEquals(3, setOf(p.profiles.single().uuid, p.weights.single().uuid, p.workouts.single().uuid).size)
    }

    @Test fun `the current weight follows the synchronised creation time, not the local id`() = runBlocking<Unit> {
        val ana = createAna()
        val anaUuid = pending().profiles.single().uuid
        val day = LocalDate.of(2026, 10, 7)
        clock.nowMillis = T0 + 5_000
        data.weights.add(ana.id, day, 88.0) // local id 1, created at T0 + 5 s
        // two entries of the same day come from another device: one created before ours, one after; the latest by
        // local id would be whichever was applied last
        fun remote(uuid: String, kg: Double, createdAt: Long) =
            SyncWeightEntry(uuid, anaUuid, RecordVersion(createdAt + 100, "dev-b"), false, day, kg, createdAtMillis = createdAt)
        sync.applyRemote(RemoteChanges(weights = listOf(remote("r-late", 88.4, T0 + 9_000), remote("r-early", 87.9, T0 + 1_000))))
        assertEquals(listOf(87.9, 88.0, 88.4), data.weights.entries(ana.id).first().map { it.weightKg })
        assertEquals(88.4, data.weights.latestWeight(ana.id).first()!!.weightKg, 0.0)
        assertEquals(88.4, data.activeUserWeightKg.first())
        // creation times survive in what is exposed for upload
        assertEquals(
            mapOf("r-late" to T0 + 9_000, "r-early" to T0 + 1_000),
            sync.weightEntries(anaUuid).filter { it.uuid.startsWith("r-") }.associate { it.uuid to it.createdAtMillis },
        )
        // a newer version of an entry from the other device replaces its creation time too
        sync.applyRemote(RemoteChanges(weights = listOf(remote("r-early", 87.9, T0 + 9_500).copy(version = RecordVersion(T0 + 20_000, "dev-b")))))
        assertEquals(87.9, data.weights.latestWeight(ana.id).first()!!.weightKg, 0.0)
        // editing our own entry keeps its place
        clock.nowMillis = T0 + 30_000
        val own = data.weights.entries(ana.id).first().first { it.weightKg == 88.0 }
        data.weights.update(own.id, day, 88.1)
        assertEquals(T0 + 5_000, data.weights.entries(ana.id).first().first { it.id == own.id }.createdAtMillis)
        assertEquals(87.9, data.weights.latestWeight(ana.id).first()!!.weightKg, 0.0)
    }

    @Test fun `the avatar of a profile is part of it when it is pending and when a remote version is applied`() = runBlocking<Unit> {
        val ana = createAna()
        val uuid = pending().profiles.single().uuid
        assertNull(pending().profiles.single().avatar)
        sync.markSynced(SyncedRecords(profiles = pending().profiles.map { SyncedRef(it.uuid, it.version) }))

        clock.nowMillis += 1_000
        ok(data.profiles.update(ana.id, validInput("Ana").copy(avatarId = "woman")))
        assertEquals("woman", pending().profiles.single().avatar)
        sync.markSynced(SyncedRecords(profiles = pending().profiles.map { SyncedRef(it.uuid, it.version) }))

        // another device picks a different one, later
        val remote = profile(uuid, "Ana", T0 + 10_000).copy(avatar = "man")
        sync.applyRemote(RemoteChanges(profiles = listOf(remote)))
        assertEquals("man", data.profiles.profile(ana.id).first()!!.avatarId)
        assertTrue(pending().isEmpty)

        // and removes it
        sync.applyRemote(RemoteChanges(profiles = listOf(remote.copy(version = RecordVersion(T0 + 20_000, "dev-b"), avatar = null))))
        assertNull(data.profiles.profile(ana.id).first()!!.avatarId)
    }

    @Test fun `markSynced clears pending, an edit makes the record pending again, an edit that changes nothing does not`() = runBlocking<Unit> {
        val ana = createAna()
        fun all(p: com.treadz.console.userdata.sync.PendingChanges) =
            SyncedRecords(
                p.profiles.map { SyncedRef(it.uuid, it.version) },
                p.weights.map { SyncedRef(it.uuid, it.version) },
                p.workouts.map { SyncedRef(it.uuid, it.version) },
            )
        sync.markSynced(all(pending()))
        assertTrue(pending().isEmpty)
        assertEquals(0, sync.pendingCount.first())

        // same data: nothing changes
        data.profiles.update(ana.id, validInput("Ana"))
        assertTrue(pending().isEmpty)

        clock.nowMillis += 1000
        data.profiles.update(ana.id, validInput("Ana").copy(heightCm = 171))
        val p = pending()
        assertEquals(171, p.profiles.single().heightCm)
        assertEquals(T0 + 1000, p.profiles.single().version.updatedAtMillis)

        // a record that changed again while it was being uploaded stays pending
        val stale = all(p)
        clock.nowMillis += 1000
        data.weights.setTargetWeight(ana.id, 60.0)
        sync.markSynced(stale)
        assertEquals(60.0, pending().profiles.single().targetWeightKg!!, 0.0)
    }

    @Test fun `versions never go backwards even if the clock does`() = runBlocking<Unit> {
        val ana = createAna()
        val first = pending().profiles.single().version.updatedAtMillis
        clock.nowMillis -= 60_000
        data.profiles.update(ana.id, validInput("Ana").copy(heightCm = 175))
        assertTrue(pending().profiles.single().version.updatedAtMillis > first)
    }

    @Test fun `the generic profile with its weight and workouts is not uploaded until it is personalised`() = runBlocking<Unit> {
        val generic = RoomUserData(newRoomDatabase(), clock, ZoneOffset.UTC, seedGenericProfile = true, deviceId = "dev-a")
        val g = generic.profiles.activeUser.first()!!
        assertTrue(generic.sync.pendingChanges().isEmpty)
        assertEquals(0, generic.sync.pendingCount.first())

        ok(generic.profiles.personalize(g.id, validInput("Ana"), weightKg = null))
        // personalised: the profile is pending, the placeholder weight (still kept) is not
        val p = generic.sync.pendingChanges()
        assertEquals(listOf("Ana"), p.profiles.map { it.name })
        assertTrue(p.weights.isEmpty())
        assertTrue(generic.sync.weightEntries(p.profiles.single().uuid).isEmpty())

        // a real weight replaces the placeholder and is pending
        generic.weights.add(g.id, LocalDate.of(2026, 10, 1), 61.0)
        assertEquals(1, generic.sync.pendingChanges().weights.size)
    }

    @Test fun `workouts of the generic profile become pending once it is personalised`() = runBlocking<Unit> {
        val database = newRoomDatabase()
        val local = RoomUserData(database, clock, ZoneOffset.UTC, seedGenericProfile = true, deviceId = "dev-a")
        val store = RoomWorkoutRepository(database, Stamper(clock, "dev-a") { "gw-${++counter}" })
        val g = local.profiles.activeUser.first()!!
        val id = store.begin(g.id, T0)
        store.finish(id, listOf(WorkoutSample(0, T0, 1, 8.0, 8.0, 0.0, 2, 0, null, BeltStatus.Running)), WorkoutStats(durationSeconds = 1), T0 + 1000, WorkoutEndReason.Stopped)
        assertTrue(local.sync.pendingChanges().isEmpty)
        ok(local.profiles.personalize(g.id, validInput("Ana"), 61.0))
        assertEquals(listOf(id), local.sync.pendingWorkoutIds.first().toList())
        assertEquals(1, local.sync.pendingChanges().workouts.size)
    }

    @Test fun `a workout in progress or without an owner is not pending until it ends or is assigned`() = runBlocking<Unit> {
        val ana = createAna()
        val running = sessions.begin(ana.id, T0)
        val orphan = finishedWorkout(null, T0 + 10_000)
        assertTrue(pending().workouts.isEmpty())
        assertTrue(sync.pendingWorkoutIds.first().isEmpty())

        sessions.append(running, listOf(WorkoutSample(0, T0, 1, 8.0, 8.0, 0.0, 2, 0, null, BeltStatus.Running)), WorkoutStats(durationSeconds = 1))
        assertTrue("running totals are not changes", pending().workouts.isEmpty())

        sessions.finish(running, emptyList(), WorkoutStats(durationSeconds = 1), T0 + 1000, WorkoutEndReason.Stopped)
        assertEquals(listOf(running), sync.pendingWorkoutIds.first().toList())

        ok(data.workouts.assign(orphan, ana.id))
        assertEquals(setOf(running, orphan), sync.pendingWorkoutIds.first())
    }

    @Test fun `an interrupted workout found at the next start becomes pending`() = runBlocking<Unit> {
        val ana = createAna()
        val id = sessions.begin(ana.id, T0)
        sessions.append(id, listOf(WorkoutSample(0, T0, 1, 8.0, 8.0, 0.0, 2, 0, null, BeltStatus.Running)), WorkoutStats(durationSeconds = 1))
        assertTrue(pending().workouts.isEmpty())
        assertEquals(1, sessions.closeInterrupted())
        assertEquals(WorkoutEndReason.Interrupted, pending().workouts.single().endReason)
    }

    // ---- deletions ----

    @Test fun `deleting is invisible to the UI but leaves a pending tombstone`() = runBlocking<Unit> {
        val ana = createAna()
        val entry = ok(data.weights.add(ana.id, LocalDate.of(2026, 10, 1), 61.0))
        val workoutId = finishedWorkout(ana.id)
        sync.markSynced(SyncedRecords(pending().profiles.map { SyncedRef(it.uuid, it.version) }, pending().weights.map { SyncedRef(it.uuid, it.version) }, pending().workouts.map { SyncedRef(it.uuid, it.version) }))
        assertTrue(pending().isEmpty)

        clock.nowMillis += 1000
        ok(data.weights.delete(entry.id))
        ok(data.workouts.delete(workoutId))
        assertTrue(data.weights.entries(ana.id).first().isEmpty())
        assertNull(data.weights.latestWeight(ana.id).first())
        assertTrue(data.workouts.workouts(ana.id).first().isEmpty())
        assertNull(data.workouts.detail(workoutId))
        assertEquals(DataResult.NotFound, data.weights.delete(entry.id))
        assertEquals(DataResult.NotFound, data.workouts.delete(workoutId))

        val p = pending()
        assertTrue(p.weights.single().deleted)
        assertTrue(p.workouts.single().deleted)
        // the log of the user keeps the tombstone (the weight file is rebuilt from it) and the samples are gone
        assertTrue(sync.weightEntries(p.profiles.firstOrNull()?.uuid ?: p.weights.single().userUuid).single().deleted)
        assertTrue(sync.workoutSamples(p.workouts.single().uuid).isEmpty())
    }

    @Test fun `deleting a profile tombstones it with its data, which is not pending one by one, and clears the active user`() = runBlocking<Unit> {
        val ana = createAna()
        val beto = ok(data.profiles.create(validInput("Beto")))
        data.profiles.setActiveUser(ana.id)
        data.weights.add(ana.id, LocalDate.of(2026, 10, 1), 61.0)
        finishedWorkout(ana.id)
        val anaUuid = pending().profiles.first { it.name == "Ana" }.uuid
        sync.markSynced(SyncedRecords(pending().profiles.map { SyncedRef(it.uuid, it.version) }, pending().weights.map { SyncedRef(it.uuid, it.version) }, pending().workouts.map { SyncedRef(it.uuid, it.version) }))

        clock.nowMillis += 1000
        ok(data.profiles.delete(ana.id))
        assertNull(data.profiles.activeUser.first())
        assertEquals(listOf("Beto"), data.profiles.profiles.first().map { it.name })
        assertNull(data.profiles.profile(ana.id).first())
        assertTrue(data.weights.entries(ana.id).first().isEmpty())
        assertTrue(data.workouts.workouts(ana.id).first().isEmpty())
        // the name can be reused while the tombstone exists
        assertTrue(data.profiles.create(validInput("Ana")) is DataResult.Success)

        val p = pending()
        val tombstone = p.profiles.single { it.uuid == anaUuid }
        assertTrue(tombstone.deleted)
        assertEquals("Ana", tombstone.name)
        assertTrue(p.weights.isEmpty())
        assertTrue(p.workouts.isEmpty())
        assertTrue(beto.id != ana.id)
    }

    @Test fun `deleting the last profile still recreates the generic one`() = runBlocking<Unit> {
        val seeded = RoomUserData(newRoomDatabase(), clock, ZoneOffset.UTC, seedGenericProfile = true)
        val generic = seeded.profiles.activeUser.first()!!
        ok(seeded.profiles.delete(generic.id))
        val fresh = seeded.profiles.activeUser.first()!!
        assertTrue(fresh.isGeneric)
        assertNotEquals(generic.id, fresh.id)
    }

    // ---- remote records ----

    @Test fun `remote records are created without being pending, and are not uploaded back`() = runBlocking<Unit> {
        val result = sync.applyRemote(
            RemoteChanges(
                profiles = listOf(profile("u-1", "Remote", 5000)),
                weights = listOf(weight("w-1", "u-1", 5000, 70.0)),
                workouts = listOf(workout("k-1", "u-1", 5000)),
            ),
        )
        assertEquals(3, result.applied)
        assertTrue(pending().isEmpty)
        val remote = data.profiles.profiles.first().single { it.name == "Remote" }
        assertEquals(70.0, data.weights.latestWeight(remote.id).first()!!.weightKg, 0.0)
        val w = data.workouts.workouts(remote.id).first().single()
        assertEquals(1, data.workouts.detail(w.id)!!.samples.size)
        assertEquals(1, sync.workoutSamples("k-1").size)
    }

    @Test fun `last writer wins per record, ties broken by device id`() = runBlocking<Unit> {
        val ana = createAna()
        clock.nowMillis += 10_000 // local version at T0 + 10000, device dev-a
        data.profiles.update(ana.id, validInput("Ana").copy(heightCm = 180))
        val local = pending().profiles.single()

        // older remote edit: local kept (and still pending)
        sync.applyRemote(RemoteChanges(profiles = listOf(profile(local.uuid, "Ana", T0 + 5000).copy(heightCm = 150))))
        assertEquals(180, data.profiles.profile(ana.id).first()!!.heightCm)
        assertEquals(1, pending().profiles.size)

        // newer remote edit: applied, no longer pending
        sync.applyRemote(RemoteChanges(profiles = listOf(profile(local.uuid, "Ana", T0 + 20_000).copy(heightCm = 190))))
        assertEquals(190, data.profiles.profile(ana.id).first()!!.heightCm)
        assertTrue(pending().isEmpty)

        // same time: the greater device id wins
        clock.nowMillis = T0 + 20_000
        val at = T0 + 20_000
        sync.applyRemote(RemoteChanges(profiles = listOf(profile(local.uuid, "Ana", at, device = "dev-a").copy(heightCm = 111))))
        assertEquals("dev-a < dev-b so the stored one (dev-b) stays", 190, data.profiles.profile(ana.id).first()!!.heightCm)
        sync.applyRemote(RemoteChanges(profiles = listOf(profile(local.uuid, "Ana", at, device = "dev-c").copy(heightCm = 222))))
        assertEquals(222, data.profiles.profile(ana.id).first()!!.heightCm)
    }

    @Test fun `an edit made after applying a record from a device with a faster clock wins`() = runBlocking<Unit> {
        val ana = createAna()
        val uuid = pending().profiles.single().uuid
        val future = clock.nowMillis + 3_600_000
        sync.applyRemote(RemoteChanges(profiles = listOf(profile(uuid, "Ana", future).copy(heightCm = 150))))
        data.profiles.update(ana.id, validInput("Ana").copy(heightCm = 160))
        assertTrue(pending().profiles.single().version > RecordVersion(future, "dev-b"))
    }

    @Test fun `remote deletions of weights and workouts remove them from the UI and are not pending`() = runBlocking<Unit> {
        val ana = createAna()
        val entry = ok(data.weights.add(ana.id, LocalDate.of(2026, 10, 1), 61.0))
        val id = finishedWorkout(ana.id)
        val p = pending()
        val ref = p.weights.single()
        val wk = p.workouts.single()
        sync.markSynced(SyncedRecords(p.profiles.map { SyncedRef(it.uuid, it.version) }, listOf(SyncedRef(ref.uuid, ref.version)), listOf(SyncedRef(wk.uuid, wk.version))))

        sync.applyRemote(
            RemoteChanges(
                weights = listOf(weight(ref.uuid, ref.userUuid, T0 + 99_000, 0.0, deleted = true)),
                workouts = listOf(workout(wk.uuid, wk.userUuid, T0 + 99_000, deleted = true)),
            ),
        )
        assertTrue(data.weights.entries(ana.id).first().isEmpty())
        assertNull(data.workouts.detail(id))
        assertTrue(pending().isEmpty)
        assertTrue(entry.id > 0)
    }

    @Test fun `a remote profile tombstone wins, takes the data with it and clears the active user`() = runBlocking<Unit> {
        val ana = createAna()
        val beto = ok(data.profiles.create(validInput("Beto")))
        data.profiles.setActiveUser(ana.id)
        data.weights.add(ana.id, LocalDate.of(2026, 10, 1), 61.0)
        finishedWorkout(ana.id)
        val uuid = pending().profiles.first { it.name == "Ana" }.uuid
        clock.nowMillis += 5000
        data.profiles.update(ana.id, validInput("Ana").copy(heightCm = 171)) // pending local edit

        // the tombstone is *older* than the local edit and still wins
        sync.applyRemote(RemoteChanges(profiles = listOf(profile(uuid, "Ana", T0 + 1, deleted = true))))
        assertNull(data.profiles.activeUser.first())
        assertEquals(listOf(beto.id), data.profiles.profiles.first().map { it.id })
        assertTrue(data.weights.entries(ana.id).first().isEmpty())
        assertTrue(data.workouts.workouts(ana.id).first().isEmpty())
        assertTrue(pending().profiles.none { it.uuid == uuid })

        // and a later remote edit does not resurrect it
        sync.applyRemote(RemoteChanges(profiles = listOf(profile(uuid, "Ana", T0 + 900_000))))
        assertEquals(listOf(beto.id), data.profiles.profiles.first().map { it.id })
    }

    @Test fun `records of an unknown or deleted owner are skipped and tombstones of unknown records are ignored`() = runBlocking<Unit> {
        val result = sync.applyRemote(
            RemoteChanges(
                profiles = listOf(profile("gone", "Ghost", 1, deleted = true)),
                weights = listOf(weight("w-1", "nobody", 5000, 70.0)),
                workouts = listOf(workout("k-1", "nobody", 5000)),
            ),
        )
        assertEquals(0, result.applied)
        assertEquals(3, result.skipped)
        // nothing was created (the generic profile only appears because the database must never be left empty)
        assertTrue(data.profiles.profiles.first().all { it.isGeneric })
    }

    @Test fun `remote profiles may share a name with a local one`() = runBlocking<Unit> {
        createAna()
        sync.applyRemote(RemoteChanges(profiles = listOf(profile("u-9", "Ana", 5000))))
        assertEquals(listOf("Ana", "Ana"), data.profiles.profiles.first().map { it.name })
        // but creating another Ana on this device is still refused
        assertTrue(data.profiles.create(validInput("ana")) is DataResult.Invalid)
    }

    @Test fun `deleting the last profile by a remote tombstone recreates the generic profile`() = runBlocking<Unit> {
        val seeded = RoomUserData(newRoomDatabase(), clock, ZoneOffset.UTC, seedGenericProfile = true, deviceId = "dev-a")
        seeded.sync.applyRemote(RemoteChanges(profiles = listOf(profile("u-1", "Ana", 5000))))
        // the unused generic profile made way for the real one
        assertEquals(listOf("Ana"), seeded.profiles.profiles.first().map { it.name })
        assertNull(seeded.profiles.activeUser.first())
        seeded.sync.applyRemote(RemoteChanges(profiles = listOf(profile("u-1", "Ana", 6000, deleted = true))))
        val only = seeded.profiles.profiles.first().single()
        assertTrue(only.isGeneric)
        assertEquals(only, seeded.profiles.activeUser.first())
    }

    @Test fun `the unused generic profile is kept when it has workouts or when dropping is turned off`() = runBlocking<Unit> {
        val database = newRoomDatabase()
        val local = RoomUserData(database, clock, ZoneOffset.UTC, seedGenericProfile = true, deviceId = "dev-a")
        val g = local.profiles.activeUser.first()!!
        local.sync.applyRemote(RemoteChanges(profiles = listOf(profile("u-1", "Ana", 5000))), dropUnusedGeneric = false)
        assertEquals(2, local.profiles.profiles.first().size)

        val store = RoomWorkoutRepository(database, Stamper(clock, "dev-a") { "gw-${++counter}" })
        val id = store.begin(g.id, T0)
        store.finish(id, emptyList(), WorkoutStats(durationSeconds = 1), T0 + 1000, WorkoutEndReason.Stopped)
        local.sync.applyRemote(RemoteChanges(profiles = listOf(profile("u-2", "Beto", 5000))))
        assertTrue(local.profiles.profiles.first().any { it.isGeneric })
        assertFalse(local.profiles.profiles.first().isEmpty())
    }

    @Test fun `a merge that creates no profile leaves the generic profile alone`() = runBlocking<Unit> {
        val local = RoomUserData(newRoomDatabase(), clock, ZoneOffset.UTC, seedGenericProfile = true, deviceId = "dev-a")
        val ana = ok(local.profiles.create(validInput("Ana")))
        val uuid = local.sync.pendingChanges().profiles.first { it.name == "Ana" }.uuid
        clock.nowMillis += 1000
        local.sync.applyRemote(RemoteChanges(profiles = listOf(profile(uuid, "Ana", clock.nowMillis + 5000))))
        assertEquals(listOf("", "Ana"), local.profiles.profiles.first().map { it.name })
        assertTrue(local.profiles.profile(ana.id).first() != null)
    }
}
