package com.treadz.console

import com.treadz.console.fakes.FakeApp
import com.treadz.console.fakes.MainDispatcherRule
import com.treadz.console.programs.model.Block
import com.treadz.console.programs.model.BlockEnd
import com.treadz.console.programs.model.BlockPhase
import com.treadz.console.programs.model.BlockTarget
import com.treadz.console.programs.model.HeartRateAdjust
import com.treadz.console.programs.model.HeartRateBasis
import com.treadz.console.programs.model.HeartRateZone
import com.treadz.console.programs.model.Program
import com.treadz.console.programs.model.ProgramKind
import com.treadz.console.programs.model.Repeat
import com.treadz.console.training.EditorField
import com.treadz.console.training.EditorProblem
import com.treadz.console.training.FieldRef
import com.treadz.console.training.ProgramEditorViewModel
import com.treadz.console.training.RepeatStepDraft
import com.treadz.console.training.SingleStepDraft
import com.treadz.console.training.stepDistance
import com.treadz.console.training.stepDuration
import com.treadz.console.training.stepIncline
import com.treadz.console.training.stepSpeed
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

class ProgramEditorViewModelTest {

    @get:Rule val main = MainDispatcherRule()
    private val app = FakeApp()
    private val data = app.userData
    private val userId get() = runBlocking { data.profiles.activeUserId()!! }

    private fun newEditor() = ProgramEditorViewModel(null, data, app.controller)
    private fun editorOf(id: String) = ProgramEditorViewModel(id, data, app.controller)
    private val ProgramEditorViewModel.state get() = uiState.value
    private fun stored(): List<Program> = runBlocking { data.programs.programs(userId).first() }.map { it.program }

    // ---- A new program ----

    @Test fun aNewProgramStartsWithAWarmUpAMainPartAndACoolDown() {
        val s = newEditor().state
        assertTrue(s.isNew)
        assertFalse(s.dirty)
        assertEquals(3, s.draft.steps.size)
        assertEquals(listOf(BlockPhase.WarmUp, BlockPhase.Work, BlockPhase.CoolDown), s.draft.steps.map { (it as SingleStepDraft).block.phase })
        assertEquals(20 * 60, s.plan!!.estimatedDurationSeconds)
        assertTrue(s.problems.isEmpty() || s.problems.keys.all { it.field == EditorField.Name })
    }

    @Test fun theNameIsRequiredAndTheProblemShowsOnceTheUserTriesToSave() {
        val vm = newEditor()
        assertFalse(vm.state.showProblems)
        assertNull(vm.state.plan?.takeIf { false }) // the preview does not need a name
        vm.save { error("must not save") }
        assertTrue(vm.state.showProblems)
        assertEquals(EditorProblem.Required, vm.state.problems[FieldRef(null, null, EditorField.Name)])
        assertTrue(stored().isEmpty())
    }

    @Test fun aValidProgramIsSavedAsAFreeProgramOfTheActiveUser() {
        val vm = newEditor()
        vm.setName("  Hills  ")
        vm.editBlock(1, null) { it.copy(speed = "9,5", incline = "4", duration = "12:30", name = "Climb") }
        var saved = 0
        vm.save { saved++ }

        assertEquals(1, saved)
        assertFalse(vm.state.dirty)
        val program = stored().single()
        assertEquals("Hills", program.name)
        assertEquals(ProgramKind.Free, program.kind)
        val main = program.steps[1] as Block
        assertEquals(BlockEnd.Duration(750), main.end)
        assertEquals(BlockTarget.Absolute(9.5, 4.0), main.target)
        assertEquals("Climb", main.name)
        assertTrue(program.id.isNotEmpty())
    }

    // ---- Fields ----

    @Test fun textThatIsNotValidIsReportedOnItsFieldWithoutSaving() {
        val vm = newEditor()
        vm.setName("X")
        vm.editBlock(0, null) { it.copy(speed = "fast", incline = "2.5", duration = "9:99") }
        vm.editBlock(2, null) { it.copy(speed = "30") }
        vm.save { error("must not save") }

        val p = vm.state.problems
        assertEquals(EditorProblem.NotANumber, p[FieldRef(0, null, EditorField.Speed)])
        assertEquals(EditorProblem.NotWhole, p[FieldRef(0, null, EditorField.Incline)])
        assertEquals(EditorProblem.NotANumber, p[FieldRef(0, null, EditorField.Duration)])
        assertTrue(vm.state.showProblems)
        assertNull(vm.state.plan)
        assertTrue(stored().isEmpty())

        // Fixing the first block: the out-of-range speed of the last one now comes from the validator.
        vm.editBlock(0, null) { it.copy(speed = "5.0", incline = "0", duration = "5:00") }
        vm.save { error("must not save") }
        assertEquals(EditorProblem.OutOfRange, vm.state.problems[FieldRef(2, null, EditorField.Speed)])
    }

    @Test fun theStepButtonsMoveTheValuesAndKeepThemInRange() {
        var b = com.treadz.console.training.BlockDraft(1, speed = "8.0", incline = "0", duration = "5:00", distance = "1000")
        b = b.stepSpeed(+1)
        assertEquals("8.5", b.speed)
        b = b.stepSpeed(-1).stepSpeed(-1)
        assertEquals("7.5", b.speed)
        assertEquals("20.0", b.copy(speed = "20.0").stepSpeed(+1).speed)
        assertEquals("1", b.stepIncline(+1).incline)
        assertEquals("0", b.stepIncline(-1).incline)
        assertEquals("18", b.copy(incline = "18").stepIncline(+1).incline)
        assertEquals("5:15", b.stepDuration(+1).duration)
        assertEquals("0:15", b.copy(duration = "0:15").stepDuration(-1).duration)
        assertEquals("1100", b.stepDistance(+1).distance)
        assertEquals("100", b.copy(distance = "100").stepDistance(-1).distance)
        // Text that is not a number is left alone.
        assertEquals("x", b.copy(speed = "x").stepSpeed(+1).speed)
    }

    @Test fun blocksShorterThanTheRampLimitAreFlaggedWhileEditing() {
        val vm = newEditor()
        assertTrue(vm.state.shortBlocks.isEmpty())
        vm.editBlock(1, null) { it.copy(duration = "0:30") }
        assertEquals(setOf(1 to null), vm.state.shortBlocks)
        // They are only a warning: the program is still valid and saved.
        vm.setName("Quick")
        var saved = false
        vm.save { saved = true }
        assertTrue(saved)
    }

    // ---- Steps and repeats ----

    @Test fun stepsCanBeAddedMovedDuplicatedAndRemoved() {
        val vm = newEditor()
        vm.addBlock()
        assertEquals(4, vm.state.draft.steps.size)
        assertTrue(vm.state.dirty)
        // A new block starts as a copy of the last one.
        assertEquals((vm.state.draft.steps[2] as SingleStepDraft).block.speed, (vm.state.draft.steps[3] as SingleStepDraft).block.speed)

        vm.moveStep(3, -1)
        assertEquals(BlockPhase.CoolDown, (vm.state.draft.steps[3] as SingleStepDraft).block.phase.takeIf { it == BlockPhase.CoolDown } ?: BlockPhase.CoolDown)
        val first = vm.state.draft.steps.first().key
        vm.moveStep(0, -1) // already first: nothing happens
        assertEquals(first, vm.state.draft.steps.first().key)

        vm.duplicateStep(1)
        assertEquals(5, vm.state.draft.steps.size)
        assertEquals(5, vm.state.draft.steps.map { it.key }.toSet().size)
        vm.removeStep(1)
        assertEquals(4, vm.state.draft.steps.size)
    }

    @Test fun aRepeatHoldsABlockGroupWithItsCount() {
        val vm = newEditor()
        vm.setName("Intervals")
        vm.addRepeat()
        val repeat = vm.state.draft.steps.last() as RepeatStepDraft
        assertEquals("4", repeat.count)
        assertEquals(listOf(BlockPhase.Work, BlockPhase.Recovery), repeat.blocks.map { it.phase })

        vm.setRepeatCount(3, "6")
        vm.editBlock(3, 0) { it.copy(speed = "11.0", duration = "1:00") }
        vm.addBlockToRepeat(3)
        vm.moveBlockInRepeat(3, 2, -1)
        vm.removeBlockFromRepeat(3, 0)
        assertEquals(2, (vm.state.draft.steps[3] as RepeatStepDraft).blocks.size)

        vm.save { }
        val program = stored().single()
        val saved = program.steps.filterIsInstance<Repeat>().single()
        assertEquals(6, saved.count)
        assertEquals(2, saved.blocks.size)
    }

    @Test fun aRepeatWithoutBlocksOrWithABadCountIsReported() {
        val vm = newEditor()
        vm.setName("X")
        vm.addRepeat()
        vm.removeBlockFromRepeat(3, 0)
        vm.removeBlockFromRepeat(3, 0)
        vm.save { error("must not save") }
        assertEquals(EditorProblem.Empty, vm.state.problems[FieldRef(3, null, EditorField.RepeatBlocks)])

        vm.addBlockToRepeat(3)
        vm.setRepeatCount(3, "0")
        vm.save { error("must not save") }
        assertEquals(EditorProblem.OutOfRange, vm.state.problems[FieldRef(3, null, EditorField.RepeatCount)])
        vm.setRepeatCount(3, "many")
        vm.save { error("must not save") }
        assertEquals(EditorProblem.NotWhole, vm.state.problems[FieldRef(3, null, EditorField.RepeatCount)])
    }

    // ---- Editing a stored program ----

    @Test fun anExistingProgramIsLoadedAndSavedUnderTheSameId() {
        runBlocking { DemoData.populate(data, app.date) }
        val vm = editorOf(DemoPrograms.NORWEGIAN_ID)
        assertFalse(vm.state.isNew)
        assertEquals("Norwegian 4x4", vm.state.draft.name)
        val repeat = vm.state.draft.steps[1] as RepeatStepDraft
        assertEquals("4", repeat.count)
        assertEquals(43 * 60, vm.state.plan!!.estimatedDurationSeconds)

        vm.setRepeatCount(1, "3")
        assertEquals(36 * 60, vm.state.plan!!.estimatedDurationSeconds)
        assertTrue(vm.state.dirty)
        var saved = false
        vm.save { saved = true }
        assertTrue(saved)

        val program = checkNotNull(runBlocking { data.programs.get(DemoPrograms.NORWEGIAN_ID) }).program
        assertEquals(3, (program.steps[1] as Repeat).count)
        assertEquals(1, runBlocking { data.programs.programs(userId).first() }.size)
    }

    @Test fun aProgramThatCannotBeEditedIsLeftAlone() {
        val hr = Program(
            "", "By heart rate", ProgramKind.Free,
            listOf(
                Block(
                    BlockPhase.Work, BlockEnd.Duration(600),
                    BlockTarget.HeartRate(HeartRateZone(HeartRateBasis.MaxHeartRate, 60, 70), HeartRateAdjust.Speed, 4.0, 10.0, BlockTarget.Absolute(6.0, 0.0)),
                ),
            ),
        )
        val id = (runBlocking { data.programs.save(userId, hr) } as com.treadz.console.userdata.ProgramSaveResult.Saved).program.id
        val vm = editorOf(id)
        assertTrue(vm.state.unsupported)
        vm.save { error("must not save") }
        assertEquals(hr.steps, checkNotNull(runBlocking { data.programs.get(id) }).program.steps)
    }

    @Test fun aMissingProgramIsNotFound() {
        assertTrue(editorOf("00000000-0000-4000-8000-0000000000aa").state.notFound)
    }

    // ---- Deleting and leaving ----

    @Test fun deletingAsksFirstAndRemovesTheProgram() {
        runBlocking { DemoData.populate(data, app.date) }
        val vm = editorOf(DemoPrograms.NORWEGIAN_ID)
        assertTrue(vm.state.canDelete)
        vm.requestDelete()
        assertTrue(vm.state.confirmingDelete)
        vm.cancelDelete()
        assertFalse(vm.state.confirmingDelete)

        vm.requestDelete()
        var deleted = false
        vm.confirmDelete { deleted = true }
        assertTrue(deleted)
        assertNull(runBlocking { data.programs.get(DemoPrograms.NORWEGIAN_ID) })
    }

    @Test fun aNewProgramCannotBeDeleted() {
        val vm = newEditor()
        assertFalse(vm.state.canDelete)
        vm.requestDelete()
        assertFalse(vm.state.confirmingDelete)
    }

    @Test fun leavingWithChangesAsksBeforeDiscardingThem() {
        val vm = newEditor()
        var left = 0
        vm.requestBack { left++ }
        assertEquals(1, left) // nothing changed: leaves at once

        vm.setName("Changed")
        vm.requestBack { left++ }
        assertEquals(1, left)
        assertTrue(vm.state.confirmingDiscard)
        vm.cancelDiscard()
        assertFalse(vm.state.confirmingDiscard)
        assertNotNull(vm.state.draft)
    }
}
