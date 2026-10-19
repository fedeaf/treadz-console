package com.treadz.console

import com.treadz.console.fakes.FakeApp
import com.treadz.console.fakes.MainDispatcherRule
import com.treadz.console.fakes.TrainingFixtures
import com.treadz.console.programs.model.Block
import com.treadz.console.programs.model.BlockEnd
import com.treadz.console.programs.model.BlockPhase
import com.treadz.console.programs.model.BlockTarget
import com.treadz.console.programs.model.HeartRateAdjust
import com.treadz.console.programs.model.HeartRateBasis
import com.treadz.console.programs.model.HeartRateZone
import com.treadz.console.programs.model.Program
import com.treadz.console.programs.model.ProgramKind
import com.treadz.console.training.BlockDraft
import com.treadz.console.training.EditorField
import com.treadz.console.training.EditorProblem
import com.treadz.console.training.FieldRef
import com.treadz.console.training.ProgramDraft
import com.treadz.console.training.ProgramDrafts
import com.treadz.console.training.ProgramEditorViewModel
import com.treadz.console.training.SingleStepDraft
import com.treadz.console.userdata.ProgramSaveResult
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** "Keep" in the program editor: a block's speed or incline left to what the treadmill has when it starts. */
class ProgramKeepEditorTest {

    @get:Rule val main = MainDispatcherRule()

    private var keys = 0L
    private fun key() = ++keys

    private fun program(vararg blocks: Block) = Program("22222222-2222-4222-8222-222222222222", "Keep", ProgramKind.Free, blocks.toList())

    // ---- Drafts ----

    @Test fun aProgramThatKeepsValuesCanBeEditedAndComesBackUnchanged() {
        val program = TrainingFixtures.keepProgram()
        assertTrue(ProgramDrafts.isEditable(program))
        val draft = ProgramDrafts.draftOf(program, ::key)
        val recovery = (draft.steps[2] as SingleStepDraft).block
        assertTrue(recovery.keepSpeed)
        assertFalse(recovery.keepIncline)
        // Behind "Keep" the value of the block before, which is what switching it off gives.
        assertEquals("10.0", recovery.speed)
        val steady = (draft.steps[3] as SingleStepDraft).block
        assertTrue(steady.keepIncline)
        assertEquals("0", steady.incline)

        assertEquals(program, ProgramDrafts.parse(draft, id = program.id).program)
    }

    @Test fun theStartOfAHeartRateBlockCanKeepToo() {
        val hr = BlockTarget.HeartRate(HeartRateZone(HeartRateBasis.MaxHeartRate, 60, 70), HeartRateAdjust.Speed, 5.0, 9.0, BlockTarget.Absolute(null, 1.0))
        val program = program(
            Block(BlockPhase.WarmUp, BlockEnd.Duration(300), BlockTarget.Absolute(5.0, 0.0)),
            Block(BlockPhase.Work, BlockEnd.Duration(1200), hr),
        )
        assertTrue(ProgramDrafts.isEditable(program))
        val draft = ProgramDrafts.draftOf(program, ::key)
        assertTrue((draft.steps[1] as SingleStepDraft).block.keepSpeed)
        assertEquals(program, ProgramDrafts.parse(draft, id = program.id).program)
    }

    @Test fun aKeptValueIsNotReadEvenIfWhatIsBehindItIsNotANumber() {
        val draft = ProgramDraft(
            "Mine", "",
            listOf(
                SingleStepDraft(key(), BlockDraft(key(), speed = "6.0", incline = "1")),
                SingleStepDraft(key(), BlockDraft(key(), speed = "x", incline = "y", keepSpeed = true, keepIncline = true)),
            ),
        )
        val parsed = ProgramDrafts.parse(draft)
        assertTrue(parsed.problems.toString(), parsed.problems.isEmpty())
        assertEquals(BlockTarget.Absolute(null, null), (parsed.program!!.steps[1] as Block).target)
    }

    @Test fun theFirstBlockCannotKeepAndTheProblemIsShownOnItsField() {
        val draft = ProgramDraft(
            "Mine", "",
            listOf(
                SingleStepDraft(key(), BlockDraft(key(), keepIncline = true)),
                SingleStepDraft(key(), BlockDraft(key())),
            ),
        )
        val parsed = ProgramDrafts.parse(draft)
        assertNull(parsed.program)
        assertEquals(EditorProblem.FirstBlockKeeps, parsed.problems[FieldRef(0, null, EditorField.Incline)])
        assertNull(parsed.problems[FieldRef(0, null, EditorField.Speed)])
    }

    @Test fun aDistanceBlockThatKeepsTheSpeedIsEstimatedAtTheValueBehindItsKeep() {
        assertEquals(360.0, ProgramDrafts.blockSeconds(BlockDraft(1, byDistance = true, distance = "1000", speed = "10.0", keepSpeed = true))!!, 0.01)
        assertEquals(360.0, ProgramDrafts.blockSeconds(BlockDraft(1, byDistance = true, distance = "1000", speed = "10.0"))!!, 0.01)
    }

    // ---- The editor ----

    private val app = FakeApp()
    private val data = app.userData
    private val userId get() = runBlocking { data.profiles.activeUserId()!! }
    private val ProgramEditorViewModel.state get() = uiState.value

    @Test fun keepIsRefusedOnTheFirstBlockAndSavedOnTheOthers() {
        val vm = ProgramEditorViewModel(null, data, app.controller)
        vm.setName("Kept")
        vm.setKeep(0, null, speed = true, keep = true)
        assertFalse((vm.state.draft.steps[0] as SingleStepDraft).block.keepSpeed)

        vm.setKeep(2, null, speed = true, keep = true)
        vm.setKeep(2, null, speed = false, keep = true)
        val cooldown = (vm.state.draft.steps[2] as SingleStepDraft).block
        assertTrue(cooldown.keepSpeed && cooldown.keepIncline)
        // The preview has the block as kept, at the value expected from the block before.
        val previewed = vm.state.plan!!.blocks[2]
        assertTrue(previewed.keepsSpeed && previewed.keepsIncline)
        assertEquals(vm.state.plan!!.blocks[1].speedKmh, previewed.speedKmh, 0.001)

        var saved = 0
        vm.save { saved++ }
        assertEquals(1, saved)
        val stored = runBlocking { data.programs.programs(userId).first() }.single().program
        assertEquals(BlockTarget.Absolute(null, null), (stored.steps[2] as Block).target)

        // Switching it off brings back the value that was behind it.
        vm.setKeep(2, null, speed = true, keep = false)
        assertEquals("4.0", (vm.state.draft.steps[2] as SingleStepDraft).block.speed)
    }

    @Test fun aKeptBlockMovedToTheFirstPlaceIsFlaggedWhenSaving() {
        val stored = (runBlocking { data.programs.save(userId, TrainingFixtures.keepProgram()) } as ProgramSaveResult.Saved).program
        val vm = ProgramEditorViewModel(stored.id, data, app.controller)
        assertFalse(vm.state.unsupported)
        repeat(2) { vm.moveStep(2 - it, -1) }
        vm.save { error("must not save") }
        assertTrue(vm.state.showProblems)
        assertEquals(EditorProblem.FirstBlockKeeps, vm.state.problems[FieldRef(0, null, EditorField.Speed)])
        // Switching it off is still possible there.
        vm.setKeep(0, null, speed = true, keep = false)
        assertFalse((vm.state.draft.steps[0] as SingleStepDraft).block.keepSpeed)
    }
}
