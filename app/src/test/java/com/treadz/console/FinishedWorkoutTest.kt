package com.treadz.console

import com.treadz.console.fakes.MainDispatcherRule
import com.treadz.console.fakes.TrainingRig
import com.treadz.console.treadmill.BeltStatus
import com.treadz.console.treadmill.TreadmillState
import com.treadz.console.treadmill.control.Clock
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
 * The workout that has just ended: the coordinator offers it to be kept ("Done") or discarded. Runs the real workout
 * history observer on the fake treadmill in virtual time.
 */
class FinishedWorkoutTest {

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

    /** The observer runs in the rig's virtual time: cancelling its scope ends it (stop() would wait for that time). */
    @After fun stopObserver() = rig.scope.cancel()

    /** A manual session of [seconds] seconds, still running. */
    private fun runFor(seconds: Int) {
        for (i in 1..seconds) {
            app.controller.state.value = TreadmillState(
                status = BeltStatus.Running,
                speedKmh = 6.0,
                targetSpeedKmh = 6.0,
                distanceMeters = i * 2,
                elapsedSeconds = i,
                updatedAtMillis = rig.scheduler.currentTime,
            )
            rig.advance(1_000)
        }
    }

    private fun stopBelt() {
        app.controller.state.value = TreadmillState(status = BeltStatus.Idle, updatedAtMillis = rig.scheduler.currentTime)
        rig.advance(1_000)
    }

    private fun workouts() = runBlocking { app.userData.workouts.workouts(userId).first() }

    @Test fun aWorkoutThatEndsIsOfferedAndDoneKeepsIt() {
        runFor(5)
        assertNull("not while it is recorded", training.finishedWorkout.value)
        stopBelt()
        val finished = training.finishedWorkout.value
        assertNotNull(finished)
        assertEquals(workouts().single().id, finished!!.workoutId)
        assertTrue(finished.distanceMeters > 0)

        training.keepFinishedWorkout()
        rig.advance(10)
        assertNull(training.finishedWorkout.value)
        assertEquals(1, workouts().size)
    }

    @Test fun discardDeletesTheWorkout() {
        runFor(5)
        stopBelt()
        assertNotNull(training.finishedWorkout.value)

        training.discardFinishedWorkout()
        rig.advance(10)
        assertNull(training.finishedWorkout.value)
        assertTrue(workouts().isEmpty())
    }

    @Test fun doneOnAResultWhileTheBeltSlowsDownKeepsTheWorkoutWithoutAsking() {
        runFor(5)
        // The program has finished and the belt is stopping: "Done" is pressed before the workout is closed.
        app.controller.state.value = TreadmillState(status = BeltStatus.Idle, speedKmh = 3.0, updatedAtMillis = rig.scheduler.currentTime)
        training.dismissResult()
        stopBelt()
        assertNull(training.finishedWorkout.value)
        assertEquals(1, workouts().size)
    }

    @Test fun doneOnAnAbandonedProgramWithTheBeltRunningStillOffersTheWorkoutAtTheEnd() {
        runFor(5)
        training.dismissResult() // the belt keeps running: the session goes on by hand
        stopBelt()
        assertNotNull(training.finishedWorkout.value)
    }

    @Test fun aNewSessionKeepsTheWorkoutStillWaitingForAnAnswer() {
        runFor(5)
        stopBelt()
        val first = training.finishedWorkout.value!!.workoutId
        runFor(3)
        assertNull(training.finishedWorkout.value)
        assertTrue(workouts().any { it.id == first })
    }
}
