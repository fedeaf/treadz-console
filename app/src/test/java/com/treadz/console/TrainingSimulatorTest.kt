package com.treadz.console

import com.treadz.console.controls.ControlsNote
import com.treadz.console.controls.SessionKind
import com.treadz.console.fakes.SimTrainingRig
import com.treadz.console.programs.engine.RunOutcome
import com.treadz.console.programs.engine.RunState
import com.treadz.console.programs.fitness.EbbelingParameters
import com.treadz.console.programs.fitness.FitnessSubject
import com.treadz.console.programs.fitness.FitnessTestPhase
import com.treadz.console.programs.fitness.Sex
import com.treadz.console.programs.fitness.Validity
import com.treadz.console.programs.plan.ProgramResolver
import com.treadz.console.programs.standard.StandardPrograms
import com.treadz.console.training.RunViewModel
import com.treadz.console.training.StartOutcome
import com.treadz.console.treadmill.BeltStatus
import com.treadz.console.treadmill.CommandResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The coordinator on the real controller and the simulated treadmill, in virtual time. */
@OptIn(ExperimentalCoroutinesApi::class)
class TrainingSimulatorTest {

    @After fun tearDown() = Dispatchers.resetMain()

    private suspend fun SimTrainingRig.activeUser() = userData.profiles.activeUserId()!!

    @Test fun aProgramRunsOnTheSimulatorAndTheControlsGoThroughTheRunner() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val rig = SimTrainingRig(this)
        rig.connect()
        val std = checkNotNull(StandardPrograms.byId("standard-pyramid"))
        val plan = ProgramResolver.resolve(std.program, std.defaultParameters)

        assertEquals(StartOutcome.Started, rig.training.startProgram(rig.activeUser(), plan, plan.parameters))
        assertEquals(plan, rig.links.linked.single().plan)
        delay(60_000)
        assertEquals(RunState.Running, rig.programRunner.progress.value.state)
        assertEquals(plan.blocks[0].speedKmh, rig.sim.currentSpeedKmh, 0.15)

        // A manual speed change from the app: the runner knows and keeps it until the next block.
        assertEquals(CommandResult.Success, rig.training.commands.setSpeed(7.0))
        delay(20_000)
        assertEquals(7.0, rig.sim.currentSpeedKmh, 0.001)
        assertTrue(rig.programRunner.progress.value.manualOverride)

        // Pause and resume through the runner: the program clock stops and the block's values come back.
        assertEquals(CommandResult.Success, rig.training.commands.pause())
        delay(20_000)
        assertEquals(RunState.Paused, rig.programRunner.progress.value.state)
        assertEquals(BeltStatus.Paused, rig.controller.state.value.status)
        assertEquals(CommandResult.Success, rig.training.commands.resume())
        delay(30_000)
        assertEquals(RunState.Running, rig.programRunner.progress.value.state)
        assertEquals(7.0, rig.sim.currentSpeedKmh, 0.001) // the manual change survived the pause

        assertEquals(CommandResult.Success, rig.training.commands.stop())
        delay(30_000)
        assertEquals(RunOutcome.StoppedByUser, rig.programRunner.progress.value.outcome)
        assertEquals(0.0, rig.sim.currentSpeedKmh, 0.001)
        assertEquals(BeltStatus.Idle, rig.controller.state.value.status)
        assertTrue(rig.sim.forbiddenCommands.isEmpty())
    }

    @Test fun anEbbelingTestRunsToTheEndAndItsResultIsStoredUnderTheLinkedId() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val rig = SimTrainingRig(this)
        rig.connect()
        val subject = FitnessSubject(ageYears = 40, sex = Sex.Male, weightKg = 80.0, hrMaxBpm = 180)

        assertEquals(StartOutcome.Started, rig.training.startEbbeling(rig.activeUser(), EbbelingParameters(subject, 4.8)))
        val id = checkNotNull(rig.links.linked.single().fitnessTestId)

        // The speed may be changed in the first part of stage 1 and is then locked, like everything else.
        delay(30_000)
        assertTrue(rig.training.controls.value.speed)
        assertEquals(ControlsNote.TestSpeedOnly, rig.training.controls.value.note)
        delay(170_000)
        assertTrue(rig.fitnessRunner.progress.value.speedLocked)
        assertTrue(!rig.training.controls.value.speed)
        assertEquals(ControlsNote.TestLocked, rig.training.controls.value.note)

        // The result is stored as soon as the effort is over, during the cool-down, and the screen already shows it.
        rig.awaitTrue(message = "the cool-down") { rig.fitnessRunner.progress.value.phase == FitnessTestPhase.CoolDown }
        delay(1_000)
        assertNotNull("stored during the cool-down", rig.userData.fitnessTests.get(id))
        assertNotNull(rig.fitnessRunner.progress.value.result)

        rig.awaitTrue(message = "the end of the test") { rig.fitnessRunner.progress.value.phase == FitnessTestPhase.Finished }
        delay(1_000)

        val stored = checkNotNull(rig.userData.fitnessTests.get(id)) { "the result was not stored" }
        assertEquals(id, stored.result.id)
        assertEquals(Validity.Valid, stored.result.validity)
        val vo2 = checkNotNull(stored.result.outputs).vo2maxMlKgMin
        assertTrue("VO2max $vo2", vo2 in 15.0..70.0)

        // The run screen's state shows the result, with no previous test to compare with, until it is closed.
        val vm = RunViewModel(rig.training, rig.userData)
        delay(100)
        assertEquals(SessionKind.FitnessTest, vm.uiState.value.kind)
        assertTrue(vm.uiState.value.testResultShown)
        assertEquals(id, vm.uiState.value.test.result?.id)
        vm.dismiss()
        delay(100)
        assertEquals(SessionKind.None, vm.uiState.value.kind)

        // A second test compares with the first one.
        rig.awaitTrue(message = "the belt at rest") { rig.controller.state.value.status == BeltStatus.Idle && rig.sim.currentSpeedKmh == 0.0 }
        delay(5_000)
        assertEquals(StartOutcome.Started, rig.training.startEbbeling(rig.activeUser(), EbbelingParameters(subject, 4.8)))
        rig.awaitTrue(message = "the end of the second test") { rig.fitnessRunner.progress.value.phase == FitnessTestPhase.Finished }
        delay(1_000)
        val comparison = vm.uiState.value.comparison
        assertNotNull(comparison)
        assertEquals(id, comparison!!.previousResultId)
        assertEquals(2, rig.userData.fitnessTests.results(rig.activeUser()).first().size)
        assertEquals(2, rig.links.linked.map { it.fitnessTestId }.distinct().size)
    }

    @Test fun theSafetyKeyInterruptsAProgramAndContinuingIsRefusedUntilItIsBack() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val rig = SimTrainingRig(this)
        rig.connect()
        val std = checkNotNull(StandardPrograms.byId("standard-hill"))
        val plan = ProgramResolver.resolve(std.program, std.defaultParameters)
        assertEquals(StartOutcome.Started, rig.training.startProgram(rig.activeUser(), plan, plan.parameters))
        delay(30_000)

        rig.sim.removeSafetyKey()
        delay(3_000)
        val vm = RunViewModel(rig.training, rig.userData)
        delay(100)
        assertEquals(RunState.Interrupted, vm.uiState.value.program.state)

        vm.continueProgram()
        delay(100)
        val refused = vm.uiState.value.continueFailure
        assertTrue("$refused", refused is com.treadz.console.training.ContinueFailure.Refused)

        rig.sim.restoreSafetyKey()
        delay(5_000)
        vm.continueProgram()
        delay(15_000)
        assertEquals(RunState.Running, vm.uiState.value.program.state)
        assertEquals(null, vm.uiState.value.continueFailure)

        // Abandoning leaves the belt as it is.
        vm.abandon()
        delay(100)
        assertEquals(RunOutcome.Abandoned, vm.uiState.value.program.outcome)
        assertTrue(rig.sim.isBeltMoving)
    }
}
