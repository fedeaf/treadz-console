package com.treadz.console

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
import com.treadz.console.programs.model.ValidationIssue
import com.treadz.console.training.BlockDraft
import com.treadz.console.training.EditorField
import com.treadz.console.training.EditorProblem
import com.treadz.console.training.FieldRef
import com.treadz.console.training.ProgramDraft
import com.treadz.console.training.ProgramDrafts
import com.treadz.console.training.RepeatStepDraft
import com.treadz.console.training.SingleStepDraft
import com.treadz.console.userdata.memory.DemoPrograms
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The pure parts of the program editor: reading what the user typed and placing problems on fields. */
class ProgramDraftsTest {

    private var keys = 0L
    private fun key() = ++keys

    private fun block(speed: String = "8.0", incline: String = "0", duration: String = "5:00") =
        BlockDraft(key(), speed = speed, incline = incline, duration = duration)

    private fun draft(vararg blocks: BlockDraft, name: String = "Mine") =
        ProgramDraft(name, "", blocks.map { SingleStepDraft(key(), it) })

    // ---- Durations ----

    @Test fun durationsAreMinutesAndSeconds() {
        assertEquals(270, ProgramDrafts.parseDuration("4:30"))
        assertEquals(300, ProgramDrafts.parseDuration("5"))
        assertEquals(3_665, ProgramDrafts.parseDuration("1:01:05"))
        assertEquals(15, ProgramDrafts.parseDuration("0:15"))
        assertEquals(" 4 : 30 ".let { 270 }, ProgramDrafts.parseDuration(" 4 : 30 "))
    }

    @Test fun badDurationsAreNotNumbers() {
        listOf("", "x", "4:75", "4:30:75", "1:2:3:4", "-1:00", "4,5", "4:3x").forEach { assertNull(it, ProgramDrafts.parseDuration(it)) }
    }

    @Test fun durationsAreWrittenAsMinutesAndSeconds() {
        assertEquals("4:30", ProgramDrafts.formatDuration(270))
        assertEquals("0:05", ProgramDrafts.formatDuration(5))
        assertEquals("1:01:05", ProgramDrafts.formatDuration(3_665))
    }

    // ---- Reading a draft ----

    @Test fun aValidDraftBecomesAFreeProgramWithAbsoluteTargets() {
        val parsed = ProgramDrafts.parse(draft(block("8,5", "3", "10:00")), id = "11111111-1111-4111-8111-111111111111")
        val program = checkNotNull(parsed.program)
        assertTrue(parsed.problems.isEmpty())
        assertEquals(ProgramKind.Free, program.kind)
        val b = program.steps.single() as Block
        assertEquals(BlockEnd.Duration(600), b.end)
        assertEquals(BlockTarget.Absolute(8.5, 3.0), b.target)
        assertEquals(BlockPhase.Work, b.phase)
        assertNull(b.name)
    }

    @Test fun theSpeedIsRoundedToTheTreadmillStep() {
        val parsed = ProgramDrafts.parse(draft(block(speed = "8.46")), id = "11111111-1111-4111-8111-111111111111")
        assertEquals(8.5, ((parsed.program!!.steps.single() as Block).target as BlockTarget.Absolute).speedKmh, 1e-9)
    }

    @Test fun textThatIsNotANumberIsReportedOnItsField() {
        val parsed = ProgramDrafts.parse(draft(block(speed = "fast", incline = "3.5", duration = "4:75")))
        assertNull(parsed.program)
        assertEquals(EditorProblem.NotANumber, parsed.problems[FieldRef(0, null, EditorField.Speed)])
        assertEquals(EditorProblem.NotWhole, parsed.problems[FieldRef(0, null, EditorField.Incline)])
        assertEquals(EditorProblem.NotANumber, parsed.problems[FieldRef(0, null, EditorField.Duration)])
    }

    @Test fun blankFieldsAreRequired() {
        val parsed = ProgramDrafts.parse(draft(block(speed = "", incline = "", duration = ""), name = " "))
        assertEquals(EditorProblem.Required, parsed.problems[FieldRef(null, null, EditorField.Name)])
        assertEquals(EditorProblem.Required, parsed.problems[FieldRef(0, null, EditorField.Speed)])
        assertEquals(EditorProblem.Required, parsed.problems[FieldRef(0, null, EditorField.Incline)])
        assertEquals(EditorProblem.Required, parsed.problems[FieldRef(0, null, EditorField.Duration)])
    }

    @Test fun outOfRangeValuesComeFromTheValidatorAndLandOnTheirField() {
        val parsed = ProgramDrafts.parse(draft(block(speed = "25", incline = "30")), id = "11111111-1111-4111-8111-111111111111")
        assertNull(parsed.program)
        assertEquals(EditorProblem.OutOfRange, parsed.problems[FieldRef(0, null, EditorField.Speed)])
        assertEquals(EditorProblem.OutOfRange, parsed.problems[FieldRef(0, null, EditorField.Incline)])
    }

    @Test fun aDistanceBlockNeedsAWholeNumberOfMetres() {
        val d = ProgramDraft("Mine", "", listOf(SingleStepDraft(1, BlockDraft(2, byDistance = true, distance = "12.5"))))
        assertEquals(EditorProblem.NotWhole, ProgramDrafts.parse(d).problems[FieldRef(0, null, EditorField.Distance)])
        val tooLong = d.copy(steps = listOf(SingleStepDraft(1, BlockDraft(2, byDistance = true, distance = "5"))))
        assertEquals(EditorProblem.OutOfRange, ProgramDrafts.parse(tooLong, id = "11111111-1111-4111-8111-111111111111").problems[FieldRef(0, null, EditorField.Distance)])
        val ok = d.copy(steps = listOf(SingleStepDraft(1, BlockDraft(2, byDistance = true, distance = "1500"))))
        assertEquals(BlockEnd.Distance(1500), (ProgramDrafts.parse(ok, id = "11111111-1111-4111-8111-111111111111").program!!.steps.single() as Block).end)
    }

    @Test fun repeatsAreCheckedForTheirCountAndTheirBlocks() {
        val bad = ProgramDraft("Mine", "", listOf(RepeatStepDraft(1, "x", emptyList()), RepeatStepDraft(2, "0", listOf(block()))))
        val parsed = ProgramDrafts.parse(bad)
        assertEquals(EditorProblem.NotWhole, parsed.problems[FieldRef(0, null, EditorField.RepeatCount)])
        assertEquals(EditorProblem.Empty, parsed.problems[FieldRef(0, null, EditorField.RepeatBlocks)])
        val range = ProgramDrafts.parse(ProgramDraft("Mine", "", listOf(RepeatStepDraft(2, "0", listOf(block())))), id = "11111111-1111-4111-8111-111111111111")
        assertEquals(EditorProblem.OutOfRange, range.problems[FieldRef(0, null, EditorField.RepeatCount)])
    }

    @Test fun aProgramWithoutStepsIsEmpty() {
        assertEquals(EditorProblem.Empty, ProgramDrafts.parse(ProgramDraft("Mine")).problems[FieldRef(null, null, EditorField.Steps)])
    }

    @Test fun namesHaveALimit() {
        val parsed = ProgramDrafts.parse(draft(block(), name = "x".repeat(81)))
        assertEquals(EditorProblem.TooLong, parsed.problems[FieldRef(null, null, EditorField.Name)])
    }

    // ---- From a stored program and back ----

    @Test fun aStoredProgramComesBackUnchangedAfterBeingEdited() {
        val program = DemoPrograms.norwegian4x4()
        val back = checkNotNull(ProgramDrafts.parse(ProgramDrafts.draftOf(program, ::key), id = program.id).program)
        assertEquals(program, back)
    }

    @Test fun onlyAbsoluteAndHeartRateTargetsWithAnAbsoluteStartCanBeEdited() {
        assertTrue(ProgramDrafts.isEditable(DemoPrograms.norwegian4x4()))
        val hr = Program(
            "11111111-1111-4111-8111-111111111111", "HR", ProgramKind.Free,
            listOf(
                Block(
                    BlockPhase.Work, BlockEnd.Duration(300),
                    BlockTarget.HeartRate(HeartRateZone(HeartRateBasis.MaxHeartRate, 60, 70), HeartRateAdjust.Speed, 4.0, 10.0, BlockTarget.Absolute(6.0, 0.0)),
                ),
            ),
        )
        assertTrue(ProgramDrafts.isEditable(hr))
        val relativeStart = hr.copy(
            steps = listOf(
                Block(
                    BlockPhase.Work, BlockEnd.Duration(300),
                    BlockTarget.HeartRate(HeartRateZone(HeartRateBasis.MaxHeartRate, 60, 70), HeartRateAdjust.Speed, 40.0, 100.0, BlockTarget.Relative(50.0, 0.0)),
                ),
            ),
        )
        assertFalse(ProgramDrafts.isEditable(relativeStart))
        assertFalse(ProgramDrafts.isEditable(hr.copy(kind = ProgramKind.Personalised)))
        val relative = hr.copy(steps = listOf(Repeat(2, listOf(Block(BlockPhase.Work, BlockEnd.Duration(60), BlockTarget.Relative(50.0, 0.0))))))
        assertFalse(ProgramDrafts.isEditable(relative))
    }

    // ---- Placing issues ----

    @Test fun issuesOfTheRepositoryAreMappedByTheirPath() {
        val d = ProgramDraft("", "", listOf(SingleStepDraft(1, block()), RepeatStepDraft(2, "2", listOf(block(), block()))))
        val placed = ProgramDrafts.placeIssues(
            listOf(
                ValidationIssue("name", "must not be blank"),
                ValidationIssue("steps[0].target.speedKmh", "range"),
                ValidationIssue("steps[1].blocks[1].durationSeconds", "range"),
                ValidationIssue("steps[1].blocks[0].target.inclinePercent", "range"),
                ValidationIssue("steps[1].count", "range"),
                ValidationIssue("steps[1].blocks[1].name", "too long"),
                ValidationIssue("kind", "a standard program cannot be stored"),
            ),
            d,
        )
        assertEquals(EditorProblem.Required, placed[FieldRef(null, null, EditorField.Name)])
        assertEquals(EditorProblem.OutOfRange, placed[FieldRef(0, null, EditorField.Speed)])
        assertEquals(EditorProblem.OutOfRange, placed[FieldRef(1, 1, EditorField.Duration)])
        assertEquals(EditorProblem.OutOfRange, placed[FieldRef(1, 0, EditorField.Incline)])
        assertEquals(EditorProblem.OutOfRange, placed[FieldRef(1, null, EditorField.RepeatCount)])
        assertEquals(EditorProblem.TooLong, placed[FieldRef(1, 1, EditorField.BlockName)])
        assertNotNull(placed[FieldRef(null, null, EditorField.Steps)]) // anything else is about the program as a whole
    }

    // ---- Short blocks ----

    @Test fun blocksUnderTheRampLimitAreFlagged() {
        val d = ProgramDraft(
            "Mine", "",
            listOf(
                SingleStepDraft(1, block(duration = "0:30")),
                SingleStepDraft(2, block(duration = "0:45")),
                RepeatStepDraft(3, "3", listOf(block(duration = "0:20"), block(duration = "2:00"))),
                SingleStepDraft(4, BlockDraft(5, byDistance = true, distance = "50", speed = "10.0")),
                SingleStepDraft(6, BlockDraft(7, byDistance = true, distance = "1000", speed = "10.0")),
            ),
        )
        assertEquals(setOf(0 to null, 2 to 0, 3 to null), ProgramDrafts.shortBlocks(d, 45))
    }
}
