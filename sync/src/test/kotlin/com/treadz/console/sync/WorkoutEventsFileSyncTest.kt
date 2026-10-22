package com.treadz.console.sync

import com.treadz.console.userdata.DataResult
import com.treadz.console.userdata.WorkoutEndReason
import com.treadz.console.userdata.WorkoutEvent
import com.treadz.console.userdata.WorkoutEventQuantity
import com.treadz.console.userdata.WorkoutEventType
import com.treadz.console.userdata.WorkoutSample
import com.treadz.console.userdata.WorkoutStats
import com.treadz.console.userdata.workout.WorkoutSessionStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** The events of a workout travel in its `.json` file with the workout record, both ways. */
@RunWith(RobolectricTestRunner::class)
class WorkoutEventsFileSyncTest {

    private val github = FakeGitHub().apply { validTokens += TOKEN }
    private val repo = github.addRepo(FakeGitHub.Repo("fede", "treadz-data"))
    private fun device(name: String, seed: Long) = Device(name, github, seed, seedGenericProfile = false)

    private fun <T> ok(r: DataResult<T>): T = (r as DataResult.Success).value
    private fun workoutJson() = repo.files().keys.first { it.contains("/workouts/") && it.endsWith(".json") }

    private val first = listOf(
        WorkoutEvent(12, WorkoutEventType.ManualChange, WorkoutEventQuantity.Speed, 6.5),
        WorkoutEvent(12, WorkoutEventType.ManualChange, WorkoutEventQuantity.Incline, 3.0),
    )
    private val final = first + listOf(
        WorkoutEvent(301, WorkoutEventType.HeartRateDecrease, WorkoutEventQuantity.Speed, 6.2),
        WorkoutEvent(500, WorkoutEventType.HeartRateControlResumed),
    )

    /** A workout that is recorded in two steps like a real one: it begins, runs, and ends. */
    private fun Device.record(userId: Long, duringTheWorkout: List<WorkoutEvent>?, atTheEnd: List<WorkoutEvent>?): Long = runBlocking {
        val store = data.workouts as WorkoutSessionStore
        val id = store.begin(userId, T0)
        if (duringTheWorkout != null) ok(data.workouts.setEvents(id, duringTheWorkout))
        val samples = (0 until 3).map { WorkoutSample(it, T0 + it * 1000L, it + 1, 8.0, 8.0, 1.0, 10 * it, 0, 120, com.treadz.console.treadmill.BeltStatus.Running) }
        store.finish(id, samples, WorkoutStats(durationSeconds = 3, distanceMeters = 30), T0 + 3_000, WorkoutEndReason.Stopped)
        if (atTheEnd != null) ok(data.workouts.setEvents(id, atTheEnd))
        id
    }

    @Test fun `a set during the workout followed by a set at the end uploads the final list, and the other device reads it`() {
        val a = device("tablet", 1)
        val ana = a.createUser("Ana")
        a.sync()
        a.record(ana.id, duringTheWorkout = first, atTheEnd = final)
        a.sync()

        val file = repo.text(workoutJson())!!
        assertTrue(file, file.contains("\"events\": [\n    {\"seconds\": 12, \"type\": \"manual_change\", \"quantity\": \"speed\", \"value\": 6.5},"))
        assertTrue(file, file.contains("{\"seconds\": 500, \"type\": \"heart_rate_control_resumed\"}\n  ]"))
        assertEquals(4, Regex("\"seconds\"").findAll(file).count())

        val b = device("phone", 2)
        b.sync()
        val ids = runBlocking { b.data.workouts.workouts(b.profiles().single().id).first() }
        assertEquals(final, runBlocking { b.data.workouts.detail(ids.single().id) }!!.events)
        val commits = repo.log().size
        assertNull(b.sync().commitSha)
        assertNull(a.sync().commitSha)
        assertEquals(commits, repo.log().size)
    }

    @Test fun `only the events set during the workout are uploaded if nothing is set at the end`() {
        val a = device("tablet", 1)
        val ana = a.createUser("Ana")
        a.record(ana.id, duringTheWorkout = first, atTheEnd = null)
        a.sync()
        assertEquals(2, Regex("\"seconds\"").findAll(repo.text(workoutJson())!!).count())
    }

    @Test fun `a workout without events has no events field, and an older file without it reads as no events`() {
        val a = device("tablet", 1)
        val ana = a.createUser("Ana")
        a.record(ana.id, duringTheWorkout = null, atTheEnd = null)
        a.sync()
        assertFalse(repo.text(workoutJson())!!.contains("events"))
        val b = device("phone", 2)
        b.sync()
        val id = runBlocking { b.data.workouts.workouts(b.profiles().single().id).first() }.single().id
        assertEquals(emptyList<WorkoutEvent>(), runBlocking { b.data.workouts.detail(id) }!!.events)
    }

    @Test fun `the latest edit of the workout record wins, events included, in both directions`() {
        val a = device("tablet", 1)
        val ana = a.createUser("Ana")
        val id = a.record(ana.id, duringTheWorkout = null, atTheEnd = first)
        a.sync()
        val b = device("phone", 2)
        b.at(T0 + 1_000).sync()
        val idB = runBlocking { b.data.workouts.workouts(b.profiles().single().id).first() }.single().id

        // the phone changes them, the tablet follows
        b.at(T0 + 2_000)
        ok(runBlocking { b.data.workouts.setEvents(idB, final) })
        b.sync()
        a.at(T0 + 3_000).sync()
        assertEquals(final, runBlocking { a.data.workouts.detail(id) }!!.events)

        // both change them offline: the later one wins everywhere
        a.at(T0 + 4_000)
        ok(runBlocking { a.data.workouts.setEvents(id, first) })
        b.at(T0 + 5_000)
        ok(runBlocking { b.data.workouts.setEvents(idB, final.take(3)) })
        a.sync()
        b.sync()
        a.at(T0 + 6_000).sync()
        assertEquals(final.take(3), runBlocking { a.data.workouts.detail(id) }!!.events)
        assertEquals(final.take(3), runBlocking { b.data.workouts.detail(idB) }!!.events)
        // and clearing them removes the field
        a.at(T0 + 7_000)
        ok(runBlocking { a.data.workouts.setEvents(id, emptyList()) })
        a.sync()
        assertFalse(repo.text(workoutJson())!!.contains("events"))
        b.at(T0 + 8_000).sync()
        assertEquals(emptyList<WorkoutEvent>(), runBlocking { b.data.workouts.detail(idB) }!!.events)
    }
}
