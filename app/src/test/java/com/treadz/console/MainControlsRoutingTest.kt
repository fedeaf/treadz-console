package com.treadz.console

import com.treadz.console.controls.ControlsNote
import com.treadz.console.controls.SessionKind
import com.treadz.console.fakes.MainDispatcherRule
import com.treadz.console.fakes.TrainingRig
import com.treadz.console.programs.engine.RunOutcome
import com.treadz.console.programs.engine.RunState
import com.treadz.console.programs.fitness.EbbelingParameters
import com.treadz.console.programs.fitness.FitnessSubject
import com.treadz.console.programs.fitness.Sex
import com.treadz.console.treadmill.BeltStatus
import com.treadz.console.treadmill.TreadmillState
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** The live screen's state and its buttons while a program or a fitness test runs: what is enabled and where it goes. */
class MainControlsRoutingTest {

    @get:Rule val main = MainDispatcherRule()
    private val rig = TrainingRig()
    private val app get() = rig.app
    private val vm get() = app.viewModel
    private val ui get() = vm.uiState.value
    private val userId get() = runBlocking { app.userData.profiles.activeUserId()!! }

    private fun startProgram() {
        rig.startProgram(rig.standardPlan("standard-pyramid"), userId)
        app.controller.showRunning()
        rig.advance(2_000)
    }

    @Test fun withoutASessionTheButtonsWorkAsBefore() {
        vm // created
        rig.advance(10)
        assertEquals(SessionKind.None, ui.session.kind)
        assertTrue(ui.canAdjustSpeed)
        assertTrue(ui.canAdjustIncline)
        assertTrue(ui.canPause)
        vm.controls.stepSpeed(+1)
        rig.advance(10)
        assertEquals(listOf(8.6), app.controller.speedCommands)
        assertEquals(RunState.Idle, app.programRunner.progress.value.state)
    }

    @Test fun duringAProgramTheStepButtonsGoThroughTheRunnerSoItKnowsTheChange() {
        startProgram()
        assertEquals(SessionKind.Program, ui.session.kind)
        assertTrue(ui.canAdjustSpeed)

        vm.controls.stepSpeed(+1)
        vm.controls.stepIncline(+1)
        rig.advance(100)

        val progress = app.programRunner.progress.value
        assertTrue(progress.manualOverride)
        assertTrue(progress.targetSpeedKmh!! > progress.currentBlock!!.speedKmh - 5)
        assertTrue(app.controller.speedCommands.isNotEmpty())
    }

    @Test fun stopFromTheButtonEndsTheProgramThroughTheRunner() {
        startProgram()
        vm.controls.stop()
        rig.advance(100)
        assertEquals(RunOutcome.StoppedByUser, app.programRunner.progress.value.outcome)
        assertEquals(1, app.controller.stopCalls)
    }

    @Test fun pauseAndResumeFromTheButtonGoThroughTheRunner() {
        startProgram()
        vm.controls.pause()
        rig.advance(100)
        assertEquals(RunState.Paused, app.programRunner.progress.value.state)
        app.controller.showState { it.copy(speedKmh = 0.0) }
        assertTrue(ui.canResume)
        vm.controls.resume()
        rig.advance(100)
        assertEquals(RunState.Running, app.programRunner.progress.value.state)
    }

    @Test fun anInterruptedProgramLocksSpeedInclineAndPause() {
        startProgram()
        app.controller.showState { it.copy(status = BeltStatus.Paused, speedKmh = 2.0) }
        rig.advance(500)
        assertEquals(RunState.Interrupted, app.programRunner.progress.value.state)

        assertFalse(ui.canAdjustSpeed)
        assertFalse(ui.canAdjustIncline)
        assertEquals(ControlsNote.ProgramInterrupted, ui.session.note)
        val speedCommands = app.controller.speedCommands.size
        vm.controls.stepSpeed(+1)
        rig.advance(100)
        assertEquals(speedCommands, app.controller.speedCommands.size)
        // STOP is never locked.
        vm.controls.stop()
        rig.advance(100)
        assertEquals(RunOutcome.StoppedByUser, app.programRunner.progress.value.outcome)
    }

    @Test fun duringATestOnlyTheSpeedOfTheFirstStageCanBeChangedAndNothingCanBePaused() {
        app.controller.state.value = TreadmillState(heartRateBpm = 72, updatedAtMillis = 1)
        val subject = FitnessSubject(40, Sex.Male, 80.0, 180)
        rig.call { app.training.startEbbeling(userId, EbbelingParameters(subject, 4.8)) }
        app.controller.showState { it.copy(status = BeltStatus.Running, speedKmh = 4.8, targetSpeedKmh = 4.8, heartRateBpm = 100, updatedAtMillis = rig.scheduler.currentTime) }
        rig.advance(5_000)

        assertEquals(SessionKind.FitnessTest, ui.session.kind)
        assertTrue(ui.canAdjustSpeed)
        assertFalse(ui.canAdjustIncline)
        assertFalse(ui.canPause)
        assertEquals(ControlsNote.TestSpeedOnly, ui.session.note)

        vm.controls.pause()
        vm.controls.stepIncline(+1)
        rig.advance(100)
        assertEquals(0, app.controller.pauseCalls)
        assertTrue(app.controller.inclineCommands.isEmpty())

        vm.controls.stepSpeed(+1)
        rig.advance(100)
        assertEquals(4.9, app.fitnessRunner.progress.value.targetSpeedKmh!!, 0.001)
    }
}
