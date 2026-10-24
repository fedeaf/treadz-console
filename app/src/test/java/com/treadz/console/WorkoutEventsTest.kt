package com.treadz.console

import com.treadz.console.fakes.MainDispatcherRule
import com.treadz.console.fakes.TrainingRig
import com.treadz.console.programs.engine.RunState
import com.treadz.console.training.EVENTS_STORE_MILLIS
import com.treadz.console.training.toWorkoutEvent
import com.treadz.console.training.toWorkoutEvents
import com.treadz.console.treadmill.BeltStatus
import com.treadz.console.treadmill.TreadmillEvent
import com.treadz.console.treadmill.TreadmillState
import com.treadz.console.treadmill.control.Clock
import com.treadz.console.treadmill.events.LoadEvent
import com.treadz.console.treadmill.events.LoadEventType
import com.treadz.console.treadmill.events.LoadQuantity
import com.treadz.console.userdata.WorkoutEvent
import com.treadz.console.userdata.WorkoutEventQuantity
import com.treadz.console.userdata.WorkoutEventType
import com.treadz.console.userdata.workout.WorkoutObserverConfig
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * The events of a workout (what changed its load) are stored with it: the list of the program or the test that runs in it, or,
 * for a workout without either, the changes of the app's buttons and of the console's. They are stored while the workout lasts
 * and once more, final, when it ends; a discarded workout stores nothing. Runs the real workout history observer and the real
 * runners on the fake treadmill in virtual time.
 */
class WorkoutEventsTest {

    @get:Rule val main = MainDispatcherRule()

    private val rig = TrainingRig()
    private val app get() = rig.app
    private val training get() = app.training
    private val userId get() = runBlocking { app.userData.profiles.activeUserId()!! }
    private val observer = app.userData.workoutObserver(
        app.controller,
        Clock { rig.scheduler.currentTime },
        WorkoutObserverConfig(flushIntervalMs = 20, minSampleSpacingMs = 0),
        onError = { throw it },
    )

    init {
        observer.start(rig.scope)
        rig.advance(10)
    }

    @After fun stopObserver() = rig.scope.cancel()

    private fun belt(status: BeltStatus = BeltStatus.Running, speed: Double = 6.0, elapsed: Int = 0) {
        app.controller.state.value = TreadmillState(
            status = status, speedKmh = speed, targetSpeedKmh = speed, distanceMeters = elapsed * 2, elapsedSeconds = elapsed,
            heartRateBpm = 120, updatedAtMillis = rig.scheduler.currentTime,
        )
    }

    /** The belt runs for [seconds] seconds, second by second. */
    private fun runFor(seconds: Int, from: Int = 0) {
        for (i in 1..seconds) {
            belt(elapsed = from + i)
            rig.advance(1_000)
        }
    }

    private fun stopBelt() {
        belt(BeltStatus.Idle, 0.0)
        rig.advance(1_000)
    }

    /** The newest workout of the user. */
    private fun workoutId() = runBlocking { app.userData.workouts.workouts(userId).first() }.first().id

    private fun events(id: Long = workoutId()) = runBlocking { app.userData.workouts.detail(id)!!.events }

    // ---- A workout without a program ----

    @Test fun theAppsButtonsAndTheConsolesAreStoredAsManualChangesWithTheTimeSinceTheStart() {
        runFor(10)
        assertEquals(CommandOk, rig.call { training.commands.setSpeed(8.0) })
        runFor(5, from = 10)
        app.controller.emit(TreadmillEvent.ManualChange(speedKmh = 7.5, inclinePercent = null))
        rig.advance(10)
        assertEquals(CommandOk, rig.call { training.commands.setIncline(3.0) })
        runFor(2, from = 15)
        stopBelt()

        val stored = events()
        assertEquals(listOf(8.0, 7.5), stored.filter { it.quantity == WorkoutEventQuantity.Speed }.map { it.value })
        assertEquals(listOf(3.0), stored.filter { it.quantity == WorkoutEventQuantity.Incline }.map { it.value })
        assertTrue(stored.all { it.type == WorkoutEventType.ManualChange })
        // Seconds since the start of the workout: the first change was made about 10 s in.
        assertTrue("${stored.first().seconds}", stored.first().seconds in 9..11)
        assertEquals(stored.map { it.seconds }.sorted(), stored.map { it.seconds })
        // The app's own commands go through the controller as before.
        assertEquals(listOf(8.0), app.controller.speedCommands)
        assertEquals(listOf(3.0), app.controller.inclineCommands)
    }

    @Test fun aManualWorkoutWithoutChangesStoresNoEvents() {
        runFor(5)
        stopBelt()
        assertEquals(emptyList<WorkoutEvent>(), events())
    }

    @Test fun aChangeThatTheTreadmillDidNotCarryOutIsNotAnEvent() {
        runFor(5)
        app.controller.setSpeedHandler = { com.treadz.console.treadmill.CommandResult.Rejected(com.treadz.console.treadmill.FailureCode.RejectedByTreadmill, "no") }
        rig.call { training.commands.setSpeed(9.0) }
        stopBelt()
        assertEquals(emptyList<WorkoutEvent>(), events())
    }

    @Test fun theEventsAreStoredWhileTheWorkoutLastsNotOnlyAtTheEnd() {
        runFor(5)
        rig.call { training.commands.setSpeed(8.0) }
        // Not at once: at most every few seconds.
        assertEquals(emptyList<WorkoutEvent>(), events())
        runFor(((EVENTS_STORE_MILLIS / 1000) + 1).toInt(), from = 5)
        val during = events()
        assertEquals(1, during.size)
        assertEquals(8.0, during.single().value!!, 0.001)
        // The workout is still in progress.
        assertNull(training.finishedWorkout.value)
        stopBelt()
        assertEquals(during, events())
    }

    @Test fun theFinalListIsStoredWhenTheWorkoutEndsEvenIfAChangeCameJustBefore() {
        runFor(5)
        rig.call { training.commands.setSpeed(8.0) }
        runFor(1, from = 5)
        rig.call { training.commands.setIncline(2.0) }
        stopBelt()
        assertEquals(listOf(8.0, 2.0), events().map { it.value })
    }

    @Test fun aDiscardedWorkoutStoresNothing() {
        runFor(5)
        rig.call { training.commands.setSpeed(8.0) }
        runFor(5, from = 5)
        // STOP, and Discard while the belt is still slowing down: the workout is deleted when it ends.
        belt(BeltStatus.Idle, 3.0)
        training.discardFinishedWorkout()
        stopBelt()
        assertTrue(runBlocking { app.userData.workouts.workouts(userId).first() }.isEmpty())
        assertNull(training.finishedWorkout.value)
    }

    @Test fun aDiscardAfterTheEndLeavesNothingEither() {
        runFor(5)
        rig.call { training.commands.setSpeed(8.0) }
        stopBelt()
        val id = workoutId()
        assertEquals(1, events(id).size)
        training.discardFinishedWorkout()
        rig.advance(10)
        assertTrue(runBlocking { app.userData.workouts.workouts(userId).first() }.isEmpty())
        assertNull(runBlocking { app.userData.workouts.detail(id) })
    }

    @Test fun eachWorkoutHasItsOwnEvents() {
        runFor(5)
        rig.call { training.commands.setSpeed(8.0) }
        stopBelt()
        training.keepFinishedWorkout()
        rig.advance(10)
        val first = workoutId()

        runFor(5)
        rig.call { training.commands.setSpeed(9.0) }
        runFor(2, from = 5)
        stopBelt()
        val all = runBlocking { app.userData.workouts.workouts(userId).first() }
        val second = all.first { it.id != first }.id
        assertEquals(listOf(8.0), events(first).map { it.value })
        assertEquals(listOf(9.0), events(second).map { it.value })
    }

    // ---- A workout with a program ----

    @Test fun theEventsOfAProgramAreItsListWithTheProgramClock() {
        app.controller.state.value = TreadmillState(heartRateBpm = 72, updatedAtMillis = rig.scheduler.currentTime)
        val plan = rig.standardPlan("standard-pyramid")
        assertEquals(com.treadz.console.training.StartOutcome.Started, rig.startProgram(plan, userId))
        runForProgram(40)
        assertEquals(CommandOk, rig.call { training.commands.setSpeed(9.5) })
        runForProgram(40, from = 40)
        assertEquals(RunState.Running, app.programRunner.progress.value.state)

        val expected = app.programRunner.loadEvents.value
        assertTrue(expected.isNotEmpty())
        assertTrue(expected.all { it.type == LoadEventType.ManualChange })
        // The program, not the manual workout events, is where they come from: one event, not two.
        assertEquals(1, expected.count { it.quantity == LoadQuantity.Speed })
        stopBelt()
        assertEquals(expected.toWorkoutEvents(), events())
        assertEquals(9.5, events().single { it.quantity == WorkoutEventQuantity.Speed }.value!!, 0.001)
    }

    // ---- A workout that mixes manual changes and a program: one list on the clock of the workout ----

    @Test fun aProgramStartedOnAMovingBeltContinuesTheListOfTheManualWorkoutOnItsClock() {
        runFor(10)
        assertEquals(CommandOk, rig.call { training.commands.setSpeed(7.0) })
        runFor(20, from = 10)
        val workout = workoutId()
        assertEquals(com.treadz.console.training.StartOutcome.Started, rig.startProgram(rig.standardPlan("standard-pyramid"), userId))
        runForProgram(20, from = 30)
        assertEquals(CommandOk, rig.call { training.commands.setSpeed(9.5) })
        runForProgram(5, from = 50)
        stopBelt()

        assertEquals(workout, workoutId())
        val speeds = events(workout).filter { it.type == WorkoutEventType.ManualChange && it.quantity == WorkoutEventQuantity.Speed }
        assertEquals(listOf(7.0, 9.5), speeds.map { it.value })
        // The manual change about 10 s into the workout; the program's about 20 s into the program, which started 30 s in.
        assertTrue("${speeds[0].seconds}", speeds[0].seconds in 9..11)
        assertTrue("${speeds[1].seconds}", speeds[1].seconds in 45..55)
    }

    @Test fun afterAnAbandonedProgramTheManualChangesGoOnInTheSameListOnTheClockOfTheWorkout() {
        app.controller.state.value = TreadmillState(heartRateBpm = 72, updatedAtMillis = rig.scheduler.currentTime)
        assertEquals(com.treadz.console.training.StartOutcome.Started, rig.startProgram(rig.standardPlan("standard-pyramid"), userId))
        runForProgram(20)
        assertEquals(CommandOk, rig.call { training.commands.setSpeed(9.5) })
        runForProgram(10, from = 20)
        // Paused and started again from the console: the program is interrupted, the user abandons it, the belt goes on.
        belt(BeltStatus.Paused, 0.0, elapsed = 30)
        rig.advance(2_000)
        belt(elapsed = 31)
        rig.advance(1_000)
        assertEquals(RunState.Interrupted, app.programRunner.progress.value.state)
        assertTrue(app.programRunner.abandon() is com.treadz.console.programs.engine.RunnerResult.Success)
        rig.advance(100)
        runFor(10, from = 31)
        assertEquals(CommandOk, rig.call { training.commands.setSpeed(6.5) })
        runFor(5, from = 41)
        stopBelt()

        val speeds = events().filter { it.type == WorkoutEventType.ManualChange && it.quantity == WorkoutEventQuantity.Speed }
        assertEquals(listOf(9.5, 6.5), speeds.map { it.value })
        assertTrue("${speeds.map { it.seconds }}", speeds[0].seconds < speeds[1].seconds && speeds[1].seconds in 38..48)
    }

    /** The belt follows the program for [seconds] seconds (the fake treadmill reports what the program asks for). */
    private fun runForProgram(seconds: Int, from: Int = 0) {
        for (i in 1..seconds) {
            val target = app.programRunner.progress.value.targetSpeedKmh ?: 5.0
            belt(speed = target, elapsed = from + i)
            rig.advance(1_000)
        }
    }

    // ---- The mapping ----

    @Test fun everyEventOfTheEngineHasItsCounterpartInTheDataLayer() {
        for (type in LoadEventType.entries) {
            val stored = LoadEvent(12, type, LoadQuantity.Incline, 4.0).toWorkoutEvent()
            assertNotNull("$type", stored)
            assertEquals(type.code, stored!!.type.code)
            assertEquals(WorkoutEventQuantity.Incline, stored.quantity)
            assertEquals(4.0, stored.value!!, 0.0)
            assertEquals(12, stored.seconds)
        }
        assertEquals(LoadEventType.entries.size, WorkoutEventType.entries.size)
        assertEquals(WorkoutEvent(30, WorkoutEventType.HeartRateControlResumed), LoadEvent(30, LoadEventType.HeartRateControlResumed).toWorkoutEvent())
        assertEquals(WorkoutEvent(5, WorkoutEventType.Alpha1Reduction, WorkoutEventQuantity.Speed, 6.4), LoadEvent(5, LoadEventType.Alpha1Reduction, LoadQuantity.Speed, 6.4).toWorkoutEvent())
    }

    private companion object {
        val CommandOk = com.treadz.console.treadmill.CommandResult.Success
    }
}
