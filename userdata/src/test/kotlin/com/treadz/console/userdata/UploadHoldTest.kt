package com.treadz.console.userdata

import com.treadz.console.treadmill.BeltStatus
import com.treadz.console.userdata.room.RoomUserData
import com.treadz.console.userdata.room.RoomWorkoutRepository
import com.treadz.console.userdata.room.Stamper
import com.treadz.console.userdata.room.UserDataDatabase
import com.treadz.console.userdata.sync.SyncedRecords
import com.treadz.console.userdata.sync.SyncedRef
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.ZoneOffset

/** A workout (and its fitness test result) held while the user decides whether to keep it, and discarded for good. */
@RunWith(RobolectricTestRunner::class)
class UploadHoldTest {

    @After fun closeDatabases() = closeRoomDatabases()

    private val clock = MutableClock()
    private var counter = 0
    private val db: UserDataDatabase = newRoomDatabase()
    private val data = RoomUserData(db, clock, ZoneOffset.UTC, seedGenericProfile = false, deviceId = "dev-a", uuidSource = { "uuid-${++counter}" })
    private val sessions = RoomWorkoutRepository(db, Stamper(clock, "dev-a") { "w-${++counter}" })

    private fun <T> ok(r: DataResult<T>): T = (r as DataResult.Success).value

    private suspend fun createAna() = ok(data.profiles.create(validInput("Ana")))

    private suspend fun beginWorkout(userId: Long, link: WorkoutLink? = null): Long = sessions.begin(userId, T0, link)

    private suspend fun finish(id: Long) {
        val sample = WorkoutSample(0, T0, 1, 8.0, 8.0, 0.0, 2, 0, null, BeltStatus.Running)
        sessions.finish(id, listOf(sample), WorkoutStats(durationSeconds = 1, distanceMeters = 2), T0 + 1000, WorkoutEndReason.Stopped)
    }

    /** The profile already uploaded: only what happens next is pending. */
    private suspend fun syncProfiles() {
        data.sync.markSynced(SyncedRecords(profiles = data.sync.pendingChanges().profiles.map { SyncedRef(it.uuid, it.version) }))
    }

    @Test fun `a held workout and its test result are not pending until released`() = runBlocking<Unit> {
        val ana = createAna()
        syncProfiles()
        val id = beginWorkout(ana.id, WorkoutLink(fitnessTestId = TEST_1))
        data.workouts.holdUpload(id)
        data.fitnessTests.holdUpload(TEST_1)
        finish(id)
        data.fitnessTests.save(ana.id, ebbelingResult(TEST_1, T0))

        assertTrue(data.sync.pendingChanges().workouts.isEmpty())
        assertEquals(0, data.sync.pendingCount.first())
        assertTrue(data.sync.pendingWorkoutIds.first().isEmpty())
        assertTrue(data.fitnessTestDocuments.pending().isEmpty())
        assertEquals(0, data.fitnessTestDocuments.pendingCount.first())
        // Not even through the fitness block of a profile uploaded meanwhile.
        data.profiles.update(ana.id, validInput("Ana").copy(heightCm = 171))
        assertNull(data.sync.pendingChanges().profiles.single().fitness?.vo2max)

        data.workouts.releaseUpload(id)
        data.fitnessTests.releaseUpload(TEST_1)
        assertEquals(1, data.sync.pendingChanges().workouts.size)
        assertEquals(setOf(id), data.sync.pendingWorkoutIds.first())
        assertEquals(1, data.fitnessTestDocuments.pending().size)
        assertEquals(1, data.fitnessTestDocuments.pendingCount.first())
    }

    @Test fun `discarding a held workout leaves no trace, its test result included`() = runBlocking<Unit> {
        val ana = createAna()
        val id = beginWorkout(ana.id, WorkoutLink(fitnessTestId = TEST_1))
        data.workouts.holdUpload(id)
        data.fitnessTests.holdUpload(TEST_1)
        finish(id)
        data.fitnessTests.save(ana.id, ebbelingResult(TEST_1, T0))

        assertEquals(DataResult.Success(Unit), data.workouts.discardFinished(id))
        data.fitnessTests.releaseUpload(TEST_1)

        assertNull(data.workouts.detail(id))
        assertTrue(data.workouts.workouts(ana.id).first().isEmpty())
        assertTrue(data.fitnessTests.results(ana.id).first().isEmpty())
        // No tombstone: nothing to upload, now that the holds are gone.
        assertTrue(data.sync.pendingChanges().workouts.isEmpty())
        assertTrue(data.fitnessTestDocuments.pending().isEmpty())
        assertEquals(0L, db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM workouts").use { it.moveToFirst(); it.getLong(0) })
        assertEquals(0L, db.openHelper.readableDatabase.query("SELECT COUNT(*) FROM fitness_tests").use { it.moveToFirst(); it.getLong(0) })
    }

    @Test fun `discarding a workout that is no longer held is a normal deletion`() = runBlocking<Unit> {
        val ana = createAna()
        val id = beginWorkout(ana.id)
        finish(id)
        val uuid = data.sync.pendingChanges().workouts.single().uuid

        assertEquals(DataResult.Success(Unit), data.workouts.discardFinished(id))

        assertNull(data.workouts.detail(id))
        val tombstone = data.sync.pendingChanges().workouts.single()
        assertEquals(uuid, tombstone.uuid)
        assertTrue(tombstone.deleted)
    }

    @Test fun `a tombstone still counts as pending`() = runBlocking<Unit> {
        val ana = createAna()
        val id = beginWorkout(ana.id)
        finish(id)
        data.workouts.delete(id)
        assertEquals(2, data.sync.pendingCount.first()) // the profile and the tombstone
    }

    @Test fun `discarding an unknown workout is not found`() = runBlocking<Unit> {
        assertEquals(DataResult.NotFound, data.workouts.discardFinished(42))
    }
}
