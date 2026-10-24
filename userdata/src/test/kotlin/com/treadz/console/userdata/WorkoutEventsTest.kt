package com.treadz.console.userdata

import com.treadz.console.userdata.memory.InMemoryUserData
import com.treadz.console.userdata.room.RoomUserData
import com.treadz.console.userdata.sync.RecordVersion
import com.treadz.console.userdata.sync.RemoteChanges
import com.treadz.console.userdata.sync.SyncWorkoutData
import com.treadz.console.userdata.sync.SyncedRecords
import com.treadz.console.userdata.sync.SyncedRef
import com.treadz.console.userdata.workout.WorkoutSessionStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import org.robolectric.RobolectricTestRunner
import java.time.ZoneOffset

internal val EVENTS = listOf(
    WorkoutEvent(12, WorkoutEventType.ManualChange, WorkoutEventQuantity.Speed, 6.5),
    WorkoutEvent(12, WorkoutEventType.ManualChange, WorkoutEventQuantity.Incline, 3.0),
    WorkoutEvent(301, WorkoutEventType.HeartRateDecrease, WorkoutEventQuantity.Speed, 6.2),
    WorkoutEvent(480, WorkoutEventType.Alpha1Reduction, WorkoutEventQuantity.Incline, 2.0),
    WorkoutEvent(500, WorkoutEventType.HeartRateControlResumed),
)

/** The events of a workout: set, replaced and read back the same by the real data layer and the in-memory one. */
@RunWith(RobolectricTestRunner::class)
abstract class WorkoutEventsContractTest {
    protected val clock = MutableClock()
    @After fun closeDatabases() = closeRoomDatabases()

    protected abstract fun create(): UserData
    protected val data by lazy { create() }

    protected fun <T> DataResult<T>.ok(): T = (this as? DataResult.Success)?.value ?: error("expected success but was $this")

    private suspend fun ana() = data.profiles.create(validInput("Ana")).ok()

    @Test fun `a workout starts without events and reads back what was set, in order`() = runBlocking<Unit> {
        val id = data.addFinishedWorkout(ana().id)
        assertEquals(emptyList<WorkoutEvent>(), data.workouts.detail(id)!!.events)
        data.workouts.setEvents(id, EVENTS).ok()
        assertEquals(EVENTS, data.workouts.detail(id)!!.events)
    }

    @Test fun `setting events replaces the list and is idempotent`() = runBlocking<Unit> {
        val id = data.addFinishedWorkout(ana().id)
        data.workouts.setEvents(id, EVENTS).ok()
        data.workouts.setEvents(id, EVENTS).ok()
        assertEquals(EVENTS, data.workouts.detail(id)!!.events)
        data.workouts.setEvents(id, EVENTS.take(2)).ok()
        assertEquals(EVENTS.take(2), data.workouts.detail(id)!!.events)
        data.workouts.setEvents(id, emptyList()).ok()
        assertEquals(emptyList<WorkoutEvent>(), data.workouts.detail(id)!!.events)
    }

    @Test fun `an unknown workout is not found`() = runBlocking<Unit> {
        assertEquals(DataResult.NotFound, data.workouts.setEvents(999, EVENTS))
    }

    @Test fun `a quantity and a value go together, an event that changes nothing has neither`() = runBlocking<Unit> {
        val id = data.addFinishedWorkout(ana().id)
        data.workouts.setEvents(
            id,
            listOf(
                WorkoutEvent(5, WorkoutEventType.MaxHeartRateReached, WorkoutEventQuantity.Speed, null),
                WorkoutEvent(6, WorkoutEventType.NoHeartRateFallback, null, 5.0),
                WorkoutEvent(7, WorkoutEventType.MaxHeartRateReached, WorkoutEventQuantity.Incline, 4.0),
            ),
        ).ok()
        assertEquals(
            listOf(
                WorkoutEvent(5, WorkoutEventType.MaxHeartRateReached),
                WorkoutEvent(6, WorkoutEventType.NoHeartRateFallback),
                WorkoutEvent(7, WorkoutEventType.MaxHeartRateReached, WorkoutEventQuantity.Incline, 4.0),
            ),
            data.workouts.detail(id)!!.events,
        )
    }

    @Test fun `every type has a stable code`() {
        assertEquals(
            listOf(
                "manual_change", "heart_rate_increase", "heart_rate_decrease", "alpha1_reduction", "ceiling_reduction",
                "max_heart_rate_reached", "no_heart_rate_fallback", "heart_rate_control_resumed", "block_started",
            ),
            WorkoutEventType.entries.map { it.code },
        )
        assertEquals(listOf("speed", "incline"), WorkoutEventQuantity.entries.map { it.code })
        assertEquals(WorkoutEventType.Alpha1Reduction, WorkoutEventType.ofCode("alpha1_reduction"))
        assertNull(WorkoutEventType.ofCode("something_new"))
    }

    @Test fun `deleting a workout drops its events`() = runBlocking<Unit> {
        val id = data.addFinishedWorkout(ana().id)
        data.workouts.setEvents(id, EVENTS).ok()
        data.workouts.delete(id).ok()
        assertNull(data.workouts.detail(id))
        assertEquals(DataResult.NotFound, data.workouts.setEvents(id, EVENTS))
    }
}

class RoomWorkoutEventsTest : WorkoutEventsContractTest() {
    override fun create(): UserData = newRoomUserData(clock)
}

@RunWith(JUnit4::class)
class InMemoryWorkoutEventsTest : WorkoutEventsContractTest() {
    override fun create(): UserData = InMemoryUserData(clock, ZoneOffset.UTC, seedGenericProfile = false)
}

/** The events travel with the workout record: they mark it for upload, win and lose with its version, and a tombstone has none. */
@RunWith(RobolectricTestRunner::class)
class WorkoutEventsSyncTest {
    private val clock = MutableClock()
    @After fun closeDatabases() = closeRoomDatabases()

    private var counter = 0
    private val db = newRoomDatabase()
    private val data = RoomUserData(db, clock, ZoneOffset.UTC, seedGenericProfile = false, deviceId = "tablet", uuidSource = { "uuid-${++counter}" })
    private val store = data.workouts as WorkoutSessionStore

    private suspend fun ana(): UserProfile = data.profiles.create(validInput("Ana")).ok().also { syncAll() }

    private suspend fun syncAll() {
        val pending = data.sync.pendingChanges()
        data.sync.markSynced(
            SyncedRecords(
                profiles = pending.profiles.map { SyncedRef(it.uuid, it.version) },
                workouts = pending.workouts.map { SyncedRef(it.uuid, it.version) },
            ),
        )
    }

    @Test fun `setting events marks the finished workout for upload with a new version, setting the same list does not`() = runBlocking<Unit> {
        val user = ana()
        val id = data.addFinishedWorkout(user.id)
        syncAll()
        assertTrue(data.sync.pendingChanges().workouts.isEmpty())

        data.workouts.setEvents(id, EVENTS).ok()
        val first = data.sync.pendingChanges().workouts.single()
        assertEquals(EVENTS, first.events)
        syncAll()

        data.workouts.setEvents(id, EVENTS).ok()
        assertTrue("the same list changes nothing", data.sync.pendingChanges().workouts.isEmpty())

        data.workouts.setEvents(id, EVENTS.take(3)).ok()
        val second = data.sync.pendingChanges().workouts.single()
        assertEquals(EVENTS.take(3), second.events)
        assertTrue("the clock did not move, the version still did", second.version > first.version)
        assertEquals("tablet", second.version.deviceId)
    }

    private fun <T> DataResult<T>.ok(): T = (this as DataResult.Success).value

    @Test fun `a set during the workout followed by a set at the end uploads the final list`() = runBlocking<Unit> {
        val user = ana()
        val id = store.begin(user.id, T0)
        data.workouts.setEvents(id, EVENTS.take(2)).ok()
        assertEquals(EVENTS.take(2), data.workouts.detail(id)!!.events)
        assertTrue(data.workouts.detail(id)!!.summary.isInProgress)
        assertTrue("a workout in progress is never uploaded", data.sync.pendingChanges().workouts.isEmpty())
        store.finish(id, emptyList(), WorkoutStats(durationSeconds = 600), T0 + 600_000, WorkoutEndReason.Stopped)
        assertEquals("what was set during the workout is still there", EVENTS.take(2), data.sync.pendingChanges().workouts.single().events)
        data.workouts.setEvents(id, EVENTS).ok()
        val uploaded = data.sync.pendingChanges().workouts.single()
        assertEquals(EVENTS, uploaded.events)
        assertEquals(EVENTS, data.sync.pendingChanges().workouts.single().events)
    }

    @Test fun `a held workout uploads its events after the release`() = runBlocking<Unit> {
        val user = ana()
        val id = store.begin(user.id, T0)
        data.workouts.holdUpload(id)
        data.workouts.setEvents(id, EVENTS).ok()
        store.finish(id, emptyList(), WorkoutStats(durationSeconds = 600), T0 + 600_000, WorkoutEndReason.Stopped)
        assertTrue(data.sync.pendingChanges().workouts.isEmpty())
        data.workouts.releaseUpload(id)
        assertEquals(EVENTS, data.sync.pendingChanges().workouts.single().events)
    }

    @Test fun `events from the repository follow the version of the workout record`() = runBlocking<Unit> {
        val user = ana()
        val id = data.addFinishedWorkout(user.id)
        data.workouts.setEvents(id, EVENTS.take(2)).ok()
        val local = data.sync.pendingChanges().workouts.single()
        syncAll()

        val older = local.copy(version = RecordVersion(local.version.updatedAtMillis - 1, "phone"), events = EVENTS)
        data.sync.applyRemote(RemoteChanges(workouts = listOf(SyncWorkoutData(older, emptyList()))))
        assertEquals("an older record loses", EVENTS.take(2), data.workouts.detail(id)!!.events)

        val newer = local.copy(version = RecordVersion(local.version.updatedAtMillis + 1, "phone"), events = EVENTS)
        data.sync.applyRemote(RemoteChanges(workouts = listOf(SyncWorkoutData(newer, emptyList()))))
        assertEquals("a newer record wins", EVENTS, data.workouts.detail(id)!!.events)
        assertTrue("and is not uploaded back", data.sync.pendingChanges().workouts.isEmpty())

        // a new workout that arrives with events, and one without (a file from an older app)
        val arriving = newer.copy(uuid = "remote-1", startedAtMillis = T0 + DAY_MS, endedAtMillis = T0 + DAY_MS + 1000)
        val plain = newer.copy(uuid = "remote-2", startedAtMillis = T0 + 2 * DAY_MS, endedAtMillis = T0 + 2 * DAY_MS + 1000, events = emptyList())
        data.sync.applyRemote(RemoteChanges(workouts = listOf(SyncWorkoutData(arriving, emptyList()), SyncWorkoutData(plain, emptyList()))))
        val summaries = data.workouts.workouts(user.id).first()
        assertEquals(EVENTS, data.workouts.detail(summaries.first { it.startedAtMillis == T0 + DAY_MS }.id)!!.events)
        assertEquals(emptyList<WorkoutEvent>(), data.workouts.detail(summaries.first { it.startedAtMillis == T0 + 2 * DAY_MS }.id)!!.events)
    }

    @Test fun `a deleted workout is a tombstone without events`() = runBlocking<Unit> {
        val user = ana()
        val id = data.addFinishedWorkout(user.id)
        data.workouts.setEvents(id, EVENTS).ok()
        syncAll()
        data.workouts.delete(id).ok()
        val tombstone = data.sync.pendingChanges().workouts.single()
        assertTrue(tombstone.deleted)
        assertEquals(emptyList<WorkoutEvent>(), tombstone.events)
    }
}
