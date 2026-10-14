package com.treadz.console

import com.treadz.console.fakes.MainDispatcherRule
import com.treadz.console.fakes.TrainingRig
import com.treadz.console.programs.engine.RunState
import com.treadz.console.programs.model.Block
import com.treadz.console.programs.model.BlockEnd
import com.treadz.console.programs.model.BlockTarget
import com.treadz.console.programs.model.ProgramKind
import com.treadz.console.programs.plan.ProgramResolver
import com.treadz.console.programs.model.Repeat
import com.treadz.console.programs.plan.IntensityParameter
import com.treadz.console.programs.plan.StartParameters
import com.treadz.console.programs.standard.StandardPrograms
import com.treadz.console.training.ParameterProblem
import com.treadz.console.training.ProgramStartViewModel
import com.treadz.console.training.StartBlocker
import com.treadz.console.training.StartOutcome
import com.treadz.console.treadmill.BeltStatus
import com.treadz.console.treadmill.CommandResult
import com.treadz.console.treadmill.ConnectionState
import com.treadz.console.treadmill.FailureCode
import com.treadz.console.treadmill.TreadmillState
import com.treadz.console.userdata.memory.DemoData
import com.treadz.console.userdata.memory.DemoPrograms
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class ProgramStartViewModelTest {

    @get:Rule val main = MainDispatcherRule()

    private val rig = TrainingRig()
    private val app get() = rig.app
    private val controller get() = app.controller
    private val ana by lazy { runBlocking { DemoData.populate(app.userData, app.date) }.first }

    private fun restingBelt() {
        controller.state.value = TreadmillState()
    }

    private fun viewModel(programId: String): ProgramStartViewModel {
        ana // the demo users exist before the screen opens
        return ProgramStartViewModel(programId, app.userData, app.training, controller)
    }

    private val ProgramStartViewModel.state get() = uiState.value

    // ---- What the form starts with ----

    @Test fun startsFromTheCataloguesDefaultsWhenTheProgramWasNeverUsed() {
        restingBelt()
        val vm = viewModel("standard-intervals")
        val s = vm.state
        assertEquals(1_800, s.form.durationSeconds)
        assertEquals("5.0", s.form.easySpeed)
        assertEquals("12.0", s.form.maxSpeed)
        assertEquals("8", s.form.maxIncline)
        assertEquals(0, s.form.adjustmentPercent)
        assertEquals(setOf(IntensityParameter.EasySpeed, IntensityParameter.MaxSpeed, IntensityParameter.MaxIncline), s.uses)
        assertEquals("intervals", s.standardKey)
        assertEquals(1_800, s.plan!!.estimatedDurationSeconds)
        assertNull(s.blocker)
    }

    @Test fun whatTheUserUsedLastTimeFillsTheForm() {
        restingBelt()
        val s = viewModel("standard-hill").state // the demo data remembers 40 min, 5-10 km/h, 8 %, +5 % for Ana
        assertEquals(2_400, s.form.durationSeconds)
        assertEquals("5.0", s.form.easySpeed)
        assertEquals("10.0", s.form.maxSpeed)
        assertEquals("8", s.form.maxIncline)
        assertEquals(5, s.form.adjustmentPercent)
        assertEquals(2_400, s.plan!!.estimatedDurationSeconds)
        assertEquals(5, s.plan!!.parameters.intensityAdjustmentPercent)
    }

    @Test fun restoreDefaultsPutsBackTheCataloguesValuesIgnoringTheLastUsed() {
        restingBelt()
        val vm = viewModel("standard-hill") // Ana used 40 min, 5-10 km/h, 8 %, +5 % last time
        assertFalse(vm.state.atDefaults)
        vm.stepMaxSpeed(+1)
        vm.stepAdjustment(+1)
        vm.restoreDefaults()
        rig.advance(10)
        val s = vm.state
        val defaults = ProgramStartViewModel.initialForm(StandardPrograms.byId("standard-hill")!!.program, StandardPrograms.byId("standard-hill"), last = null)
        assertEquals(defaults, s.form)
        assertEquals(0, s.form.adjustmentPercent)
        assertTrue(s.atDefaults)
        vm.stepMaxIncline(+1)
        rig.advance(10)
        assertFalse(vm.state.atDefaults)
    }

    @Test fun anOldDurationIsBroughtIntoTheCataloguesRangeAndStep() {
        val std = checkNotNull(StandardPrograms.byId("standard-hill"))
        fun duration(last: Int) = ProgramStartViewModel.initialForm(std.program, std, StartParameters(totalDurationSeconds = last)).durationSeconds
        assertEquals(2_100, duration(2_130)) // snapped to the 5 min step
        assertEquals(3_600, duration(7_200))
        assertEquals(1_200, duration(300))
    }

    @Test fun anAdjustmentOutsideTheScreensRangeIsBroughtIntoIt() {
        val std = checkNotNull(StandardPrograms.byId("standard-hill"))
        assertEquals(30, ProgramStartViewModel.initialForm(std.program, std, StartParameters(intensityAdjustmentPercent = 50)).adjustmentPercent)
        assertEquals(-10, ProgramStartViewModel.initialForm(std.program, std, StartParameters(intensityAdjustmentPercent = -9)).adjustmentPercent)
    }

    @Test fun onlyTheReferenceValuesTheProgramUsesAreAsked() {
        restingBelt()
        val s = viewModel("standard-glutes").state
        assertEquals(setOf(IntensityParameter.EasySpeed, IntensityParameter.MaxIncline), s.uses)
        assertNull(s.plan!!.parameters.maxSpeedKmh)
        assertEquals(12.0, s.plan!!.parameters.maxInclinePercent!!, 0.0)
    }

    @Test fun aFreeProgramRunsAsWrittenAndAsksForNothingButTheAdjustment() {
        restingBelt()
        val s = viewModel(DemoPrograms.NORWEGIAN_ID).state
        assertEquals(ProgramKind.Free, s.kind)
        assertNull(s.durationOptions)
        assertTrue(s.uses.isEmpty())
        assertNull(s.plan!!.parameters.totalDurationSeconds)
        assertEquals(43 * 60, s.plan!!.estimatedDurationSeconds)
        assertNull(s.standardKey)
    }

    @Test fun anUnknownProgramIsNotFound() {
        val s = viewModel("standard-unknown").state
        assertTrue(s.notFound)
        assertNull(s.plan)
    }

    // ---- Changing it ----

    @Test fun theDurationMovesInTheCataloguesStepsWithinItsLimits() {
        restingBelt()
        val vm = viewModel("standard-pyramid")
        vm.stepDuration(+1)
        assertEquals(2_100, vm.state.form.durationSeconds)
        assertEquals(2_100, vm.state.plan!!.estimatedDurationSeconds)
        repeat(10) { vm.stepDuration(+1) }
        assertEquals(3_600, vm.state.form.durationSeconds)
        repeat(10) { vm.stepDuration(-1) }
        assertEquals(1_200, vm.state.form.durationSeconds)
    }

    @Test fun theAdjustmentMovesInFivePercentStepsFromMinusThirtyToPlusThirtyAndOnlyChangesTheSpeed() {
        restingBelt()
        val vm = viewModel("standard-pyramid")
        val base = vm.state.plan!!
        vm.stepAdjustment(+1)
        assertEquals(5, vm.state.form.adjustmentPercent)
        val adjusted = vm.state.plan!!
        assertEquals(base.blocks.size, adjusted.blocks.size)
        val b = base.blocks[5]
        val a = adjusted.blocks[5]
        assertEquals(b.speedKmh * 1.05, a.speedKmh, 0.1)
        assertEquals(b.inclinePercent, a.inclinePercent, 0.0)
        repeat(20) { vm.stepAdjustment(+1) }
        assertEquals(30, vm.state.form.adjustmentPercent)
        repeat(20) { vm.stepAdjustment(-1) }
        assertEquals(-30, vm.state.form.adjustmentPercent)
    }

    @Test fun theReferenceValuesAreCheckedFieldByField() {
        restingBelt()
        val vm = viewModel("standard-intervals")
        vm.setEasySpeed("abc")
        assertEquals(ParameterProblem.NotANumber, vm.state.problems[IntensityParameter.EasySpeed])
        assertNull(vm.state.plan)
        assertEquals(StartBlocker.InvalidValues, vm.state.blocker)
        assertFalse(vm.state.canStart)

        vm.setEasySpeed("")
        assertEquals(ParameterProblem.Blank, vm.state.problems[IntensityParameter.EasySpeed])

        vm.setEasySpeed("13")
        assertEquals(ParameterProblem.EasyAboveMax, vm.state.problems[IntensityParameter.EasySpeed])

        vm.setEasySpeed("5")
        vm.setMaxSpeed("25")
        assertEquals(ParameterProblem.OutOfRange, vm.state.problems[IntensityParameter.MaxSpeed])

        vm.setMaxSpeed("12,5") // a comma is fine
        vm.setMaxIncline("30")
        assertEquals(ParameterProblem.OutOfRange, vm.state.problems[IntensityParameter.MaxIncline])
        vm.setMaxIncline("9")
        assertTrue(vm.state.problems.isEmpty())
        assertEquals(12.5, vm.state.plan!!.parameters.maxSpeedKmh!!, 0.0)
    }

    @Test fun theSpeedButtonsMoveInHalfKilometresAndTheInclineInWholePercents() {
        restingBelt()
        val vm = viewModel("standard-intervals")
        vm.stepEasySpeed(+1)
        vm.stepMaxSpeed(-1)
        vm.stepMaxIncline(+1)
        assertEquals("5.5", vm.state.form.easySpeed)
        assertEquals("11.5", vm.state.form.maxSpeed)
        assertEquals("9", vm.state.form.maxIncline)
    }

    @Test fun valuesBeyondTheTreadmillsRangeProduceTranslatableWarnings() {
        restingBelt()
        val vm = viewModel("standard-intervals")
        vm.setMaxSpeed("19")
        repeat(6) { vm.stepAdjustment(+1) }
        val warnings = vm.state.plan!!.warnings
        assertTrue(warnings.isNotEmpty())
        assertTrue(warnings.all { it.code == com.treadz.console.programs.plan.WarningCode.SpeedClamped })
    }

    // ---- Why START may be disabled ----

    @Test fun startIsDisabledWithoutAUserAConnectionOrWhileTheBeltIsNotReady() {
        restingBelt()
        val vm = viewModel("standard-intervals")
        assertTrue(vm.state.canStart)

        controller.connection.value = ConnectionState.Disconnected
        assertEquals(StartBlocker.NotConnected, vm.state.blocker)
        controller.showConnected()

        controller.showState { it.copy(status = BeltStatus.Starting) }
        assertEquals(StartBlocker.BeltNotReady, vm.state.blocker)
        controller.showState { it.copy(status = BeltStatus.SafetyKeyRemoved) }
        assertEquals(StartBlocker.BeltNotReady, vm.state.blocker)
        controller.showState { it.copy(status = BeltStatus.Idle, speedKmh = 3.0) } // still slowing down
        assertEquals(StartBlocker.BeltNotReady, vm.state.blocker)
        controller.showState { it.copy(status = BeltStatus.Running, speedKmh = 8.0) } // running: the program takes over
        assertNull(vm.state.blocker)

        runBlocking { app.userData.profiles.setActiveUser(null) }
        assertEquals(StartBlocker.NoUser, vm.state.blocker)
    }

    // ---- Starting ----

    @Test fun startingRunsTheProgramRemembersTheValuesAndLinksTheWorkout() {
        restingBelt()
        val vm = viewModel("standard-intervals")
        vm.stepAdjustment(+1)
        var started = 0
        vm.start { started++ }
        assertTrue(vm.state.starting)
        rig.advance(100)

        assertEquals(1, started)
        assertFalse(vm.state.starting)
        assertNull(vm.state.failure)
        val plan = app.links.linked.single().plan!!
        assertEquals("standard-intervals", plan.source.programId)
        assertEquals(5, plan.parameters.intensityAdjustmentPercent)
        assertEquals(RunState.Running, app.programRunner.progress.value.state)
        val remembered = runBlocking { app.userData.startParameters.lastUsed(ana.id, "standard-intervals").first() }
        assertEquals(plan.parameters, remembered)
    }

    @Test fun aStartThatTheTreadmillRefusesShowsWhyAndWithdrawsTheLink() {
        restingBelt()
        controller.startResult = CommandResult.Rejected(FailureCode.SafetyKeyRemoved, "key")
        val vm = viewModel("standard-intervals")
        var started = 0
        vm.start { started++ }
        rig.advance(100)

        assertEquals(0, started)
        assertFalse(vm.state.starting)
        assertTrue(vm.state.failure is StartOutcome.CommandFailed)
        assertEquals(1, app.links.clears)
        assertNull(runBlocking { app.userData.startParameters.lastUsed(ana.id, "standard-intervals").first() })
        // Changing a value clears the message.
        vm.stepAdjustment(+1)
        assertNull(vm.state.failure)
    }

    @Test fun startDoesNothingWhileItIsBlocked() {
        restingBelt()
        controller.connection.value = ConnectionState.Disconnected
        val vm = viewModel("standard-intervals")
        vm.start { error("must not start") }
        rig.advance(100)
        assertEquals(RunState.Idle, app.programRunner.progress.value.state)
        assertTrue(app.links.linked.isEmpty())
    }

    // ---- Saving the plan as a program of the user ----

    @Test fun thePlanCanBeSavedAsAFreeProgramInAbsoluteValues() {
        restingBelt()
        val vm = viewModel("standard-intervals")
        vm.stepAdjustment(+1)
        var savedId: String? = null
        vm.saveAsFreeProgram("My intervals") { savedId = it }

        val saved = checkNotNull(runBlocking { app.userData.programs.get(checkNotNull(savedId)) }).program
        assertEquals("My intervals", saved.name)
        assertEquals(ProgramKind.Free, saved.kind)
        // The five identical rounds stay a repeat.
        assertTrue(saved.steps.any { it is Repeat && it.count == 5 && it.blocks.size == 2 })
        assertTrue(saved.steps.all { s -> (s as? Block)?.target?.let { it is BlockTarget.Absolute } ?: ((s as Repeat).blocks.all { it.target is BlockTarget.Absolute }) })
        // Resolving it again gives the same blocks the screen showed.
        val again = ProgramResolver.resolve(saved)
        val shown = vm.state.plan!!
        assertEquals(shown.blocks.map { it.speedKmh to it.inclinePercent }, again.blocks.map { it.speedKmh to it.inclinePercent })
        assertEquals(shown.blocks.map { it.end }, again.blocks.map { it.end })
        assertEquals(shown.blocks.map { it.phase }, again.blocks.map { it.phase })
        assertNotNull(again)
        assertTrue(shown.blocks.all { it.end is BlockEnd.Duration })
    }
}
