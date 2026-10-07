@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.treadz.console.userdata

import android.content.Context
import androidx.room.Room
import com.treadz.console.treadmill.BeltStatus
import com.treadz.console.treadmill.control.Clock
import com.treadz.console.userdata.room.RoomUserData
import com.treadz.console.userdata.room.UserDataDatabase
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.time.LocalDate
import java.time.ZoneOffset
import kotlin.coroutines.CoroutineContext

/** What must survive closing the database and opening it again (an app restart, or a dead process). */
@RunWith(RobolectricTestRunner::class)
class RoomPersistenceTest {

    @get:Rule val folder = TemporaryFolder()

    private fun open(context: CoroutineContext? = null): UserDataDatabase {
        val app = RuntimeEnvironment.getApplication() as Context
        return Room.databaseBuilder(app, UserDataDatabase::class.java, folder.root.resolve("userdata.db").absolutePath)
            .allowMainThreadQueries()
            .apply { if (context != null) setQueryCoroutineContext(context) }
            .build()
    }

    private fun dataOver(db: UserDataDatabase, clock: Clock = MutableClock(), seed: Boolean = false) =
        RoomUserData(db, clock, ZoneOffset.UTC, seed)

    @Test fun `profiles, active user, weights and target weight persist across a restart`() = runBlocking<Unit> {
        var db = open()
        var data = dataOver(db)
        val ana = (data.profiles.create(validInput("Ana").copy(maxHeartRateBpm = 190, goal = Goal.ImproveFitness)) as DataResult.Success).value
        val beto = (data.profiles.create(validInput("Beto")) as DataResult.Success).value
        data.profiles.setActiveUser(beto.id)
        data.weights.add(ana.id, LocalDate.of(2026, 10, 1), 61.5)
        data.weights.add(ana.id, LocalDate.of(2026, 10, 5), 61.0)
        data.weights.setTargetWeight(ana.id, 58.0)
        db.close()

        db = open()
        data = dataOver(db)
        assertEquals(listOf(ana, beto), data.profiles.profiles.first())
        assertEquals(beto, data.profiles.activeUser.first())
        assertEquals(listOf(61.5, 61.0), data.weights.entries(ana.id).first().map { it.weightKg })
        assertEquals(58.0, data.weights.targetWeightKg(ana.id).first()!!, 0.0)
        // deleting the active user is also remembered
        data.profiles.delete(beto.id)
        db.close()

        db = open()
        assertNull(dataOver(db).profiles.activeUser.first())
        assertNull(dataOver(db).profiles.activeUserId())
        db.close()
    }

    @Test fun `the generic profile is seeded on the first open only and its personalisation persists`() = runBlocking<Unit> {
        var db = open()
        var data = dataOver(db, seed = true)
        val generic = data.profiles.activeUser.first()!!
        assertTrue(generic.isGeneric)
        assertEquals(80.0, data.weights.latestWeight(generic.id).first()!!.weightKg, 0.0)
        db.close()

        // Opening again (an app restart) neither duplicates it nor resets it.
        db = open()
        data = dataOver(db, seed = true)
        assertEquals(listOf(generic), data.profiles.profiles.first())
        assertEquals(1, data.weights.entries(generic.id).first().size)
        val ana = (data.profiles.personalize(generic.id, validInput("Ana"), 71.0) as DataResult.Success).value
        db.close()

        db = open()
        data = dataOver(db, seed = true)
        assertEquals(listOf(ana), data.profiles.profiles.first())
        assertEquals(ana, data.profiles.activeUser.first())
        assertEquals(listOf(71.0), data.weights.entries(ana.id).first().map { it.weightKg })
        assertEquals(1, data.profiles.profiles.first().size) // not seeded again
        data.profiles.delete(ana.id)
        db.close()

        // The invariant survives a restart: a fresh generic profile is there.
        db = open()
        data = dataOver(db, seed = true)
        assertTrue(data.profiles.activeUser.first()!!.isGeneric)
        assertEquals(1, data.profiles.profiles.first().size)
        db.close()
    }

    @Test fun `a session cut off mid-way keeps what was flushed and is closed as Interrupted at the next start`() = runTest {
        val clock = Clock { T0 + testScheduler.currentTime }
        val queries = StandardTestDispatcher(testScheduler)
        var db = open(queries)
        var data = dataOver(db, clock)
        data.profiles.create(validInput("Ana"))
        val controller = FakeTreadmill()
        val scope = CoroutineScope(StandardTestDispatcher(testScheduler) + SupervisorJob())
        data.workoutObserver(controller, clock).start(scope)
        runCurrent()

        controller.state.value = state(BeltStatus.Starting, speed = 0.0)
        runCurrent()
        for (i in 1..8) {
            advanceTimeBy(1000)
            controller.state.value = state(BeltStatus.Running, 9.0, distance = i * 2, elapsed = i, calories = i, hr = 130 + i)
            runCurrent()
        }
        advanceTimeBy(500)
        runCurrent()

        // Power loss: the process disappears with no chance to write anything else.
        scope.cancel()
        db.close()

        db = open(queries)
        data = dataOver(db, clock)
        val workout = data.workouts.inProgressWorkout().first()!!
        val stored = data.workouts.detail(workout.id)!!
        assertTrue(workout.isInProgress)
        assertTrue("only ${stored.samples.size} samples survived", stored.samples.size in 6..9)
        assertEquals((0 until stored.samples.size).toList(), stored.samples.map { it.seq })
        assertEquals(9.0, stored.summary.stats.maxSpeedKmh, 0.0)
        assertTrue(stored.summary.stats.distanceMeters > 0)

        // The app starts again and the observer recovers the orphan.
        val scope2 = CoroutineScope(StandardTestDispatcher(testScheduler) + SupervisorJob())
        data.workoutObserver(FakeTreadmill(), clock).start(scope2)
        runCurrent()
        val closed = data.workouts.workouts(data.profiles.activeUserId()!!).first().single()
        assertEquals(WorkoutEndReason.Interrupted, closed.endReason)
        assertEquals(stored.samples.last().timestampMillis, closed.endedAtMillis)
        assertEquals(stored.summary.stats, closed.stats)
        scope2.cancel()
        db.close()
    }
}
