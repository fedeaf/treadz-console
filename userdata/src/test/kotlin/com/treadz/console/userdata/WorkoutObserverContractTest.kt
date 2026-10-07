@file:OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)

package com.treadz.console.userdata

import com.treadz.console.treadmill.BeltStatus
import com.treadz.console.treadmill.ConnectionState
import com.treadz.console.treadmill.TreadmillEvent
import com.treadz.console.treadmill.control.Clock
import com.treadz.console.userdata.memory.InMemoryUserData
import com.treadz.console.userdata.workout.WorkoutHistoryObserver
import com.treadz.console.userdata.workout.WorkoutObserverConfig
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.After
import org.junit.Test
import org.junit.runner.RunWith
import org.junit.runners.JUnit4
import org.robolectric.RobolectricTestRunner
import java.time.ZoneOffset

/**
 * The workout history observer driven by a fake [FakeTreadmill] on virtual time. Run against Room
 * (Robolectric, in-memory database) and against the in-memory implementation.
 */
@RunWith(RobolectricTestRunner::class)
abstract class WorkoutObserverContractTest {

    @After fun closeDatabases() = closeRoomDatabases()

    protected abstract fun create(clock: Clock, scheduler: TestCoroutineScheduler): UserData

    /** Everything a scenario needs; time only moves when the test says so. */
    private inner class Rig(val test: TestScope, config: WorkoutObserverConfig = WorkoutObserverConfig()) {
        val clock = Clock { T0 + test.testScheduler.currentTime }
        val data = create(clock, test.testScheduler)
        val controller = FakeTreadmill()
        val errors = mutableListOf<Throwable>()
        val scope = CoroutineScope(StandardTestDispatcher(test.testScheduler) + SupervisorJob())
        val observer: WorkoutHistoryObserver = data.workoutObserver(controller, clock, config) { errors += it }
        var userId: Long? = null

        suspend fun init(withUser: Boolean = true): Rig {
            if (withUser) userId = (data.profiles.create(validInput("Ana")) as DataResult.Success).value.id
            observer.start(scope)
            test.runCurrent()
            return this
        }

        suspend fun settle() = test.runCurrent()

        suspend fun push(s: com.treadz.console.treadmill.TreadmillState) {
            controller.state.value = s
            test.runCurrent()
        }

        suspend fun wait(seconds: Int) {
            test.advanceTimeBy(seconds * 1000L)
            test.runCurrent()
        }

        /** One state per second, `seconds` times, with the elapsed time and distance growing. */
        suspend fun run(seconds: Int, startElapsed: Int = 0, speed: Double = 8.0, hr: Int? = null, incline: Double = 0.0) {
            for (i in 1..seconds) {
                val e = startElapsed + i
                push(state(BeltStatus.Running, speed, incline, distance = (e * speed / 3.6).toInt(), elapsed = e, calories = e / 10, hr = hr))
                wait(1)
            }
        }

        suspend fun stopBelt() {
            push(state(BeltStatus.Idle, speed = 2.0)) // decelerating
            wait(1)
            push(state(BeltStatus.Idle, speed = 0.0))
        }

        suspend fun all(): List<WorkoutSummary> = data.workouts.workouts(userId!!).first()

        suspend fun only(): WorkoutSummary = all().single()
    }

    private fun scenario(config: WorkoutObserverConfig = WorkoutObserverConfig(), withUser: Boolean = true, body: suspend Rig.() -> Unit) =
        runTest {
            val rig = Rig(this, config).init(withUser)
            try {
                rig.body()
                assertEquals("the observer must never send commands", emptyList<String>(), rig.controller.commands)
                assertEquals(emptyList<Throwable>(), rig.errors)
            } finally {
                rig.scope.cancel()
            }
        }

    // ---- Normal session ----

    @Test fun `normal session stores samples and the summary`() = scenario {
        push(state(BeltStatus.Starting, speed = 0.0))
        wait(1)
        // 5 s at 6 km/h then 5 s at 10 km/h, 3 % incline, heart rate 120..129
        for (i in 1..10) {
            val speed = if (i <= 5) 6.0 else 10.0
            push(state(BeltStatus.Running, speed, incline = if (i > 8) 5.0 else 3.0, distance = i * 2, elapsed = i, calories = i, hr = 119 + i))
            wait(1)
        }
        assertEquals(1, all().size)
        assertTrue(only().isInProgress)
        stopBelt()

        val w = only()
        assertFalse(w.isInProgress)
        assertEquals(userId, w.userId)
        assertEquals(WorkoutEndReason.Stopped, w.endReason)
        assertEquals(T0, w.startedAtMillis)
        assertEquals(T0 + 12_000, w.endedAtMillis)
        assertEquals(10, w.stats.durationSeconds)
        assertEquals(20, w.stats.distanceMeters)
        assertEquals(10, w.stats.calories)
        // moving average of the samples with the belt moving: five at 6, five at 10 and one decelerating at 2
        assertEquals((5 * 6.0 + 5 * 10.0 + 2.0) / 11, w.stats.averageSpeedKmh, 1e-9)
        assertEquals(10.0, w.stats.maxSpeedKmh, 0.0)
        assertEquals(5.0, w.stats.maxInclinePercent, 0.0)
        assertEquals(125, w.stats.averageHeartRateBpm) // mean of 120..129 = 124.5, rounded
        assertEquals(129, w.stats.maxHeartRateBpm)

        val detail = data.workouts.detail(w.id)!!
        assertEquals(w, detail.summary)
        // Starting + 10 running + 1 decelerating; the final Idle/0 state is not a sample
        assertEquals(12, detail.samples.size)
        assertEquals((0..11).toList(), detail.samples.map { it.seq })
        assertEquals(BeltStatus.Starting, detail.samples.first().beltStatus)
        assertEquals(BeltStatus.Idle, detail.samples.last().beltStatus)
        assertEquals(2.0, detail.samples.last().speedKmh, 0.0)
        val fifth = detail.samples[5]
        assertEquals(6.0, fifth.speedKmh, 0.0)
        assertEquals(5, fifth.elapsedSeconds)
        assertEquals(10, fifth.distanceMeters)
        assertEquals(124, fifth.heartRateBpm)
        assertEquals(T0 + 5_000, fifth.timestampMillis)
        assertEquals(detail.samples.map { it.timestampMillis }, detail.samples.map { it.timestampMillis }.sorted())
        assertNull(observer.activeWorkoutId.value)
    }

    @Test fun `heart rate is null in the summary without a monitor`() = scenario {
        push(state(BeltStatus.Starting, speed = 0.0))
        run(5, hr = null)
        stopBelt()
        assertNull(only().stats.averageHeartRateBpm)
        assertNull(only().stats.maxHeartRateBpm)
        assertNull(data.workouts.detail(only().id)!!.samples[2].heartRateBpm)
    }

    @Test fun `the workout is visible while it runs and flushed at the configured interval`() = scenario {
        push(state(BeltStatus.Starting, speed = 0.0))
        run(10)
        val live = data.workouts.inProgressWorkout().first()
        assertNotNull(live)
        assertEquals(live!!.id, observer.activeWorkoutId.value)
        assertTrue(live.isInProgress)
        val stored = data.workouts.detail(live.id)!!.samples
        // flushes every 3 s: at t=10 s the last flush was at 9 s, so at most the last second is still buffered
        assertTrue("stored ${stored.size}", stored.size in 9..11)
        assertTrue(live.stats.durationSeconds in 8..10)
        stopBelt()
        assertEquals(12, data.workouts.detail(live.id)!!.samples.size)
    }

    @Test fun `paused session keeps going and averages ignore the standing time`() = scenario {
        push(state(BeltStatus.Starting, speed = 0.0))
        run(4, speed = 8.0)
        for (e in 5..8) { push(state(BeltStatus.Paused, speed = 0.0, elapsed = e, distance = 8)); wait(1) }
        run(2, startElapsed = 8, speed = 8.0)
        stopBelt()
        val w = only()
        assertEquals(10, w.stats.durationSeconds)
        // 6 s at 8 km/h plus the decelerating sample at 2
        assertEquals((6 * 8.0 + 2.0) / 7, w.stats.averageSpeedKmh, 1e-9)
        assertEquals(WorkoutEndReason.Stopped, w.endReason)
    }

    @Test fun `states closer than the minimum spacing are one sample unless the status changes`() = scenario {
        push(state(BeltStatus.Starting, speed = 0.0))
        wait(1)
        push(state(BeltStatus.Running, 5.0, elapsed = 1))
        controller.state.value = state(BeltStatus.Running, 5.0, elapsed = 1, distance = 1) // 0 ms later: dropped
        settle()
        wait(1)
        push(state(BeltStatus.Running, 5.0, elapsed = 2))
        push(state(BeltStatus.Paused, 0.0, elapsed = 2)) // same instant but a status change: kept
        wait(1)
        stopBelt()
        val statuses = data.workouts.detail(only().id)!!.samples.map { it.beltStatus }
        assertEquals(
            listOf(BeltStatus.Starting, BeltStatus.Running, BeltStatus.Running, BeltStatus.Paused, BeltStatus.Idle),
            statuses,
        )
    }

    // ---- How sessions end ----

    @Test fun `connection lost ends the session and the replayed stale state does not start another`() = scenario {
        push(state(BeltStatus.Starting, speed = 0.0))
        run(5)
        controller.connection.value = ConnectionState.Reconnecting("AA:BB", 1)
        controller.state.value = state(BeltStatus.Running, 8.0, distance = 11, elapsed = 5, stale = true)
        settle()
        val w = only()
        assertEquals(WorkoutEndReason.ConnectionLost, w.endReason)
        assertEquals(5, w.stats.durationSeconds)
        assertEquals(T0 + 5_000, w.endedAtMillis)
        wait(10)
        controller.state.value = state(BeltStatus.Running, 8.0, elapsed = 6, stale = true)
        settle()
        assertEquals(1, all().size) // no new session from stale data

        // After reconnecting, the treadmill is idle; a new session starts normally.
        controller.connection.value = ConnectionState.Connected("AA:BB", "FAKE")
        push(state(BeltStatus.Idle, speed = 0.0))
        push(state(BeltStatus.Starting, speed = 0.0))
        run(3)
        stopBelt()
        assertEquals(2, all().size)
    }

    @Test fun `connection lost event alone also ends the session`() = scenario {
        push(state(BeltStatus.Starting, speed = 0.0))
        run(4)
        controller.events.emit(TreadmillEvent.ConnectionLost)
        settle()
        assertEquals(WorkoutEndReason.ConnectionLost, only().endReason)
        // The connection state reporting the same loss afterwards changes nothing.
        controller.connection.value = ConnectionState.Reconnecting("AA:BB", 1)
        settle()
        assertEquals(1, all().size)
    }

    @Test fun `closing the connection ends the session`() = scenario {
        push(state(BeltStatus.Starting, speed = 0.0))
        run(4)
        controller.connection.value = ConnectionState.Disconnected
        settle()
        assertEquals(WorkoutEndReason.Disconnected, only().endReason)
    }

    @Test fun `safety key removal ends as SafetyKeyRemoved once the treadmill is idle again`() = scenario {
        push(state(BeltStatus.Starting, speed = 0.0))
        run(6)
        push(state(BeltStatus.SafetyKeyRemoved, speed = 0.0, elapsed = 6, distance = 13))
        wait(5)
        assertTrue(only().isInProgress) // still the same session while the key is out
        push(state(BeltStatus.Idle, speed = 0.0)) // key put back: the treadmill resets
        val w = only()
        assertEquals(WorkoutEndReason.SafetyKeyRemoved, w.endReason)
        assertEquals(6, w.stats.durationSeconds)
        assertEquals(13, w.stats.distanceMeters)
        assertEquals(BeltStatus.SafetyKeyRemoved, data.workouts.detail(w.id)!!.samples.last().beltStatus)
    }

    @Test fun `data stored before a safety key removal is not lost if the connection drops during it`() = scenario {
        push(state(BeltStatus.Starting, speed = 0.0))
        run(6)
        push(state(BeltStatus.SafetyKeyRemoved, speed = 0.0, elapsed = 6))
        controller.connection.value = ConnectionState.Reconnecting("AA:BB", 1)
        settle()
        val w = only()
        assertEquals(WorkoutEndReason.ConnectionLost, w.endReason)
        // starting + 6 running + key removed
        assertEquals(8, data.workouts.detail(w.id)!!.samples.size)
    }

    @Test fun `two sessions in a row make two workouts`() = scenario {
        push(state(BeltStatus.Starting, speed = 0.0))
        run(5)
        stopBelt()
        // the next session starts right away
        push(state(BeltStatus.Starting, speed = 0.0))
        run(3, speed = 6.0)
        stopBelt()
        val list = all() // newest first
        assertEquals(2, list.size)
        val (second, first) = list
        assertEquals(5, first.stats.durationSeconds)
        assertEquals(3, second.stats.durationSeconds)
        assertEquals(6.0, second.stats.maxSpeedKmh, 0.0)
        assertEquals(WorkoutEndReason.Stopped, first.endReason)
        assertEquals(WorkoutEndReason.Stopped, second.endReason)
        assertTrue(second.startedAtMillis >= first.endedAtMillis!!)
        assertEquals(0, data.workouts.detail(second.id)!!.samples.first().seq)
        assertEquals(5 + 1 + 1, data.workouts.detail(first.id)!!.samples.size)
        assertEquals(3 + 1 + 1, data.workouts.detail(second.id)!!.samples.size)
    }

    @Test fun `a session that never moved the belt is discarded`() = scenario {
        push(state(BeltStatus.Starting, speed = 0.0))
        wait(2)
        push(state(BeltStatus.Idle, speed = 0.0)) // start aborted
        assertTrue(all().isEmpty())
        assertTrue(data.workouts.unassignedWorkouts().first().isEmpty())
        assertNull(data.workouts.inProgressWorkout().first())
    }

    @Test fun `stop closes the session in progress as Closed`() = scenario {
        push(state(BeltStatus.Starting, speed = 0.0))
        run(5)
        observer.stop()
        val w = only()
        assertEquals(WorkoutEndReason.Closed, w.endReason)
        assertEquals(5, w.stats.durationSeconds)
        assertEquals(6, data.workouts.detail(w.id)!!.samples.size)
        assertNull(observer.activeWorkoutId.value)
    }

    @Test fun `the observer can be started again after stop`() = scenario {
        observer.stop()
        observer.start(scope)
        settle()
        push(state(BeltStatus.Starting, speed = 0.0))
        run(3)
        stopBelt()
        assertEquals(1, all().size)
    }

    // ---- Owner ----

    @Test fun `without an active user the workout is saved unassigned and can be assigned later`() = scenario(withUser = false) {
        push(state(BeltStatus.Starting, speed = 0.0))
        run(5)
        stopBelt()
        val list = data.workouts.unassignedWorkouts().first()
        assertEquals(1, list.size)
        assertNull(list.single().userId)
        assertEquals(5, list.single().stats.durationSeconds)
        assertEquals(7, data.workouts.detail(list.single().id)!!.samples.size)

        val ana = (data.profiles.create(validInput("Ana")) as DataResult.Success).value
        assertEquals(DataResult.Success(Unit), data.workouts.assign(list.single().id, ana.id))
        assertTrue(data.workouts.unassignedWorkouts().first().isEmpty())
        assertEquals(list.single().id, data.workouts.workouts(ana.id).first().single().id)
        assertEquals(DataResult.NotFound, data.workouts.assign(list.single().id, 999))
        assertEquals(DataResult.NotFound, data.workouts.assign(999, ana.id))
    }

    @Test fun `the owner is the user active when the session starts`() = scenario {
        val ana = userId!!
        val beto = (data.profiles.create(validInput("Beto")) as DataResult.Success).value
        push(state(BeltStatus.Starting, speed = 0.0))
        run(3)
        data.profiles.setActiveUser(beto.id)
        run(3, startElapsed = 3)
        stopBelt()
        assertEquals(1, data.workouts.workouts(ana).first().size)
        assertTrue(data.workouts.workouts(beto.id).first().isEmpty())
        // the next session goes to the new active user
        push(state(BeltStatus.Starting, speed = 0.0))
        run(3)
        stopBelt()
        assertEquals(1, data.workouts.workouts(beto.id).first().size)
        assertEquals(1, data.workouts.workouts(ana).first().size)
    }

    @Test fun `deleting the owner during the session drops it without errors and recording resumes afterwards with the recreated generic profile`() = scenario {
        push(state(BeltStatus.Starting, speed = 0.0))
        run(5)
        assertEquals(DataResult.Success(Unit), data.profiles.delete(userId!!))
        run(10, startElapsed = 5) // flushes find the workout gone
        assertNull(observer.activeWorkoutId.value)
        assertTrue(data.workouts.unassignedWorkouts().first().isEmpty())
        assertNull(data.workouts.inProgressWorkout().first())
        stopBelt()
        // deleting the only profile recreated the generic one and made it active: the next session is its
        val generic = data.profiles.activeUser.first()!!
        assertTrue(generic.isGeneric)
        push(state(BeltStatus.Starting, speed = 0.0))
        run(3)
        stopBelt()
        assertEquals(1, data.workouts.workouts(generic.id).first().size)
        assertTrue(data.workouts.unassignedWorkouts().first().isEmpty())
    }

    @Test fun `deleting the workout in progress stops its recording`() = scenario {
        push(state(BeltStatus.Starting, speed = 0.0))
        run(4)
        val id = observer.activeWorkoutId.value!!
        assertEquals(DataResult.Success(Unit), data.workouts.delete(id))
        run(5, startElapsed = 4)
        assertNull(observer.activeWorkoutId.value)
        assertTrue(all().isEmpty())
    }

    // ---- Management of stored workouts ----

    @Test fun `workouts are listed newest first, per user, and can be deleted with their samples`() = scenario {
        val ana = userId!!
        val beto = (data.profiles.create(validInput("Beto")) as DataResult.Success).value
        push(state(BeltStatus.Starting, speed = 0.0)); run(3); stopBelt()
        wait(60)
        push(state(BeltStatus.Starting, speed = 0.0)); run(4); stopBelt()
        data.profiles.setActiveUser(beto.id)
        push(state(BeltStatus.Starting, speed = 0.0)); run(2); stopBelt()

        val anas = data.workouts.workouts(ana).first()
        assertEquals(listOf(4, 3), anas.map { it.stats.durationSeconds })
        assertEquals(listOf(2), data.workouts.workouts(beto.id).first().map { it.stats.durationSeconds })

        assertEquals(DataResult.Success(Unit), data.workouts.delete(anas[0].id))
        assertNull(data.workouts.detail(anas[0].id))
        assertEquals(listOf(3), data.workouts.workouts(ana).first().map { it.stats.durationSeconds })
        assertEquals(DataResult.NotFound, data.workouts.delete(anas[0].id))
    }

    @Test fun `deleting a profile deletes its workouts and samples but not the others`() = scenario {
        val ana = userId!!
        val beto = (data.profiles.create(validInput("Beto")) as DataResult.Success).value
        push(state(BeltStatus.Starting, speed = 0.0)); run(3); stopBelt()
        val anaWorkout = only()
        data.profiles.setActiveUser(beto.id)
        push(state(BeltStatus.Starting, speed = 0.0)); run(3); stopBelt()
        val betoWorkout = data.workouts.workouts(beto.id).first().single()

        data.profiles.delete(ana)
        assertNull(data.workouts.detail(anaWorkout.id))
        assertTrue(data.workouts.workouts(ana).first().isEmpty())
        assertEquals(5, data.workouts.detail(betoWorkout.id)?.samples?.size)
    }

    // ---- Crash safety ----

    @Test fun `data already flushed survives a dead process and the workout is closed as Interrupted`() = scenario {
        push(state(BeltStatus.Starting, speed = 0.0))
        run(7, hr = 140)
        // The process "dies": nothing ends the session, the observer's coroutines just stop existing.
        scope.cancel()
        settle()
        val id = data.workouts.inProgressWorkout().first()!!.id
        val survived = data.workouts.detail(id)!!
        assertTrue(survived.summary.isInProgress)
        // flushes happened at t = 3 s and 6 s: everything up to the second 5 or 6 is safe, the rest was only in memory
        assertTrue("survived ${survived.samples.size}", survived.samples.size in 6..7)
        assertEquals((0 until survived.samples.size).toList(), survived.samples.map { it.seq })
        assertEquals(140, survived.summary.stats.maxHeartRateBpm)
        assertEquals(survived.samples.last().elapsedSeconds, survived.summary.stats.durationSeconds)

        // Next process start: a new observer closes the orphan with what was flushed.
        val next = data.workoutObserver(controller, clock)
        val nextScope = CoroutineScope(StandardTestDispatcher(test.testScheduler) + SupervisorJob())
        controller.state.value = state(BeltStatus.Idle, speed = 0.0)
        next.start(nextScope)
        settle()
        val closed = data.workouts.workouts(userId!!).first().single()
        assertEquals(WorkoutEndReason.Interrupted, closed.endReason)
        assertEquals(survived.samples.last().timestampMillis, closed.endedAtMillis)
        assertEquals(survived.summary.stats, closed.stats)
        assertEquals(survived.samples, data.workouts.detail(id)!!.samples)
        assertNull(data.workouts.inProgressWorkout().first())
        nextScope.cancel()
    }

    @Test fun `no commands are sent even when everything happens`() = scenario {
        push(state(BeltStatus.Starting, speed = 0.0))
        run(3)
        controller.events.emit(TreadmillEvent.SafetyKeyRestored)
        controller.events.emit(TreadmillEvent.DataStalled)
        controller.events.emit(TreadmillEvent.ManualChange(9.0, 2.0))
        controller.state.value = state(BeltStatus.Running, 8.0, elapsed = 4, stale = true)
        settle()
        observer.stop()
        assertTrue(controller.commands.isEmpty())
    }
}

class RoomWorkoutObserverTest : WorkoutObserverContractTest() {
    override fun create(clock: Clock, scheduler: TestCoroutineScheduler): UserData =
        newRoomUserData(clock, StandardTestDispatcher(scheduler))
}

@RunWith(JUnit4::class)
class InMemoryWorkoutObserverTest : WorkoutObserverContractTest() {
    override fun create(clock: Clock, scheduler: TestCoroutineScheduler): UserData =
        InMemoryUserData(clock, ZoneOffset.UTC, seedGenericProfile = false)
}
