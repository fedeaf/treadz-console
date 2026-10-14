package com.treadz.console

import com.treadz.console.fakes.FakeApp
import com.treadz.console.fakes.GeneratedFixtures
import com.treadz.console.fakes.MainDispatcherRule
import com.treadz.console.fakes.createProfile
import com.treadz.console.programs.engine.HeartRateControlMode
import com.treadz.console.programs.heartrate.HeartRateProfile
import com.treadz.console.programs.heartrate.HeartRateZones
import com.treadz.console.programs.model.Block
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
import com.treadz.console.training.SingleStepDraft
import com.treadz.console.training.segments
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** Heart-rate blocks in the free program editor: zone, basis, adjusted quantity, bounds, validation, warnings and the control mode. */
class ProgramEditorHeartRateTest {

    @get:Rule val main = MainDispatcherRule()
    private val app = FakeApp()
    private val data = app.userData
    private val userId get() = runBlocking { data.profiles.activeUserId()!! }

    private fun newEditor() = ProgramEditorViewModel(null, data, app.controller)
    private val ProgramEditorViewModel.state get() = uiState.value
    private fun ProgramEditorViewModel.block(step: Int) = (state.draft.steps[step] as SingleStepDraft).block
    private val maxBpm get() = runBlocking { data.fitnessTests.heartRateMax(userId).first()!!.bpm }

    /** The editor with its main block (step 1) switched to heart rate. */
    private fun heartRateEditor(): ProgramEditorViewModel = newEditor().also { it.setHeartRateTarget(1, null, true) }

    @Test fun switchingToHeartRateKeepsSpeedAndInclineAsTheStartAndSuggestsBoundsAroundIt() {
        val vm = heartRateEditor()
        val hr = checkNotNull(vm.block(1).heartRate)

        assertEquals(1, hr.standardZone) // zone 2
        assertEquals(HeartRateAdjust.Speed, hr.adjust)
        assertFalse(hr.reserve)
        assertEquals("8.0", vm.block(1).speed)
        assertEquals("6.0", hr.minimum)
        assertEquals("10.0", hr.maximum)
        assertTrue(hr.boundsAuto)
        assertTrue(vm.state.problems.keys.none { it.field != EditorField.Name })

        // Back to speed and incline: the numbers stay, the heart-rate part goes.
        vm.setHeartRateTarget(1, null, false)
        assertNull(vm.block(1).heartRate)
        assertEquals("8.0", vm.block(1).speed)
    }

    @Test fun theBoundsFollowTheStartUntilTheUserTypesOne() {
        val vm = heartRateEditor()
        vm.editBlock(1, null) { it.copy(speed = "9.0") }
        assertEquals("7.0", vm.block(1).heartRate!!.minimum)
        assertEquals("11.0", vm.block(1).heartRate!!.maximum)

        vm.setHeartRateBound(1, null, maximum = true, text = "12")
        assertFalse(vm.block(1).heartRate!!.boundsAuto)
        vm.editBlock(1, null) { it.copy(speed = "8.0") }
        assertEquals("7.0", vm.block(1).heartRate!!.minimum)
        assertEquals("12", vm.block(1).heartRate!!.maximum)
    }

    @Test fun theDefaultBoundsAreClampedToTheTreadmill() {
        val vm = heartRateEditor()
        vm.editBlock(1, null) { it.copy(speed = "19.5") }
        assertEquals("20.0", vm.block(1).heartRate!!.maximum)
        vm.editBlock(1, null) { it.copy(speed = "1.0") }
        assertEquals("0.5", vm.block(1).heartRate!!.minimum)
    }

    @Test fun walkingAdjustsTheIncline() {
        val vm = heartRateEditor()
        vm.setAdjust(1, null, HeartRateAdjust.Incline)
        val hr = vm.block(1).heartRate!!
        assertEquals(HeartRateAdjust.Incline, hr.adjust)
        assertEquals("0", hr.minimum) // 1 % − 3, not below flat
        assertEquals("6", hr.maximum) // 1 % + 5
        assertEquals("8.0", vm.block(1).speed) // the speed is the fixed walking speed
        val parsed = vm.state.plan!!.blocks[1].heartRate!!
        assertEquals(HeartRateAdjust.Incline, parsed.adjust)
        assertEquals(6.0, parsed.maximum, 0.001)
    }

    @Test fun theZoneIsShownInBpmForTheActiveUserAndFollowsTheValues() {
        val vm = heartRateEditor()
        val profile = HeartRateProfile(maxBpm, null)
        assertEquals(HeartRateZones.toBpm(HeartRateZones.STANDARD[1], profile), vm.state.zoneBpm[1 to null])

        vm.setZone(1, null, 3) // zone 4
        assertEquals(HeartRateZones.toBpm(HeartRateZones.STANDARD[3], profile), vm.state.zoneBpm[1 to null])

        // A zone of the user's own starts from the zone that was set.
        vm.setZone(1, null, null)
        val own = vm.block(1).heartRate!!
        assertNull(own.standardZone)
        assertEquals("80", own.customLow)
        assertEquals("90", own.customHigh)
        vm.editHeartRate(1, null) { it.copy(customLow = "55", customHigh = "65") }
        assertEquals(HeartRateZones.toBpm(HeartRateZone(HeartRateBasis.MaxHeartRate, 55, 65), profile), vm.state.zoneBpm[1 to null])

        // While a percentage is not a number there is no zone to show.
        vm.editHeartRate(1, null) { it.copy(customLow = "") }
        assertNull(vm.state.zoneBpm[1 to null])
    }

    @Test fun theReserveIsOffUntilTheUserHasARestingHeartRate() {
        val vm = heartRateEditor()
        assertFalse(vm.state.hasResting)
        vm.setReserve(1, null, true)
        assertFalse(vm.block(1).heartRate!!.reserve)

        val ana = data.createProfile("Ana") // her profile has a resting heart rate of 60
        runBlocking { data.profiles.setActiveUser(ana.id) }
        assertTrue(vm.state.hasResting)
        vm.setReserve(1, null, true)
        assertTrue(vm.block(1).heartRate!!.reserve)

        val profile = HeartRateProfile(maxBpm, 60)
        val zone = HeartRateZone(HeartRateBasis.HeartRateReserve, 60, 70)
        assertEquals(HeartRateZones.toBpm(zone, profile), vm.state.zoneBpm[1 to null])
        val program = checkNotNull(vm.state.plan).blocks[1].heartRate!!
        assertEquals(HeartRateBasis.HeartRateReserve, program.zone.basis)
    }

    @Test fun boundsAndStartProblemsAreMappedToTheirFields() {
        val vm = heartRateEditor()
        vm.setName("Hr")

        vm.setHeartRateBound(1, null, maximum = true, text = "5.0") // below the minimum (6.0)
        assertEquals(EditorProblem.MinAboveMax, vm.state.problems[FieldRef(1, null, EditorField.HeartRateMax)])

        vm.setHeartRateBound(1, null, maximum = false, text = "9.0")
        vm.setHeartRateBound(1, null, maximum = true, text = "12")
        // The start (8.0) is below the minimum (9.0).
        assertEquals(EditorProblem.StartOutsideBounds, vm.state.problems[FieldRef(1, null, EditorField.Speed)])

        vm.setHeartRateBound(1, null, maximum = true, text = "25")
        assertEquals(EditorProblem.OutOfRange, vm.state.problems[FieldRef(1, null, EditorField.HeartRateMax)])

        vm.setHeartRateBound(1, null, maximum = true, text = "")
        assertEquals(EditorProblem.Required, vm.state.problems[FieldRef(1, null, EditorField.HeartRateMax)])
        vm.setHeartRateBound(1, null, maximum = true, text = "x")
        assertEquals(EditorProblem.NotANumber, vm.state.problems[FieldRef(1, null, EditorField.HeartRateMax)])

        vm.save { error("must not save") }
        assertTrue(vm.state.showProblems)
    }

    @Test fun zoneProblemsAreMappedToTheirFields() {
        val vm = heartRateEditor()
        vm.setName("Zone") // the whole program is only checked once the text of every field is fine
        vm.setZone(1, null, null)
        vm.editHeartRate(1, null) { it.copy(customLow = "70", customHigh = "60") }
        assertEquals(EditorProblem.ZoneOrder, vm.state.problems[FieldRef(1, null, EditorField.ZoneHigh)])

        vm.editHeartRate(1, null) { it.copy(customLow = "x", customHigh = "120") }
        assertEquals(EditorProblem.NotWhole, vm.state.problems[FieldRef(1, null, EditorField.ZoneLow)])

        vm.editHeartRate(1, null) { it.copy(customLow = "50", customHigh = "120") }
        assertEquals(EditorProblem.OutOfRange, vm.state.problems[FieldRef(1, null, EditorField.ZoneHigh)])
    }

    @Test fun aHeartRateBlockUnderThreeMinutesWarnsButCanBeSaved() {
        val vm = heartRateEditor()
        vm.setName("Short")
        assertTrue(vm.state.shortHeartRateBlocks.isEmpty())
        vm.editBlock(1, null) { it.copy(duration = "2:30") }
        assertEquals(setOf(1 to null), vm.state.shortHeartRateBlocks)

        var saved = false
        vm.save { saved = true }
        assertTrue(saved)

        // A block that is not guided by heart rate gets no such warning.
        vm.setHeartRateTarget(1, null, false)
        assertTrue(vm.state.shortHeartRateBlocks.isEmpty())
    }

    @Test fun theControlModeFollowsTheSixMinuteRule() {
        val vm = heartRateEditor()
        assertEquals(HeartRateControlMode.Continuous, vm.state.heartRateModes[1 to null]) // 10 min, alone

        vm.addRepeat()
        val repeat = vm.state.draft.steps.lastIndex
        vm.setHeartRateTarget(repeat, 0, true)
        vm.editBlock(repeat, 0) { it.copy(duration = "4:00") }
        assertEquals(HeartRateControlMode.BetweenRepetitions, vm.state.heartRateModes[repeat to 0])

        vm.editBlock(repeat, 0) { it.copy(duration = "7:00") }
        assertEquals(HeartRateControlMode.Continuous, vm.state.heartRateModes[repeat to 0])

        // A short block that is not inside a repeat is controlled continuously.
        vm.editBlock(1, null) { it.copy(duration = "4:00") }
        assertEquals(HeartRateControlMode.Continuous, vm.state.heartRateModes[1 to null])
    }

    @Test fun aHeartRateProgramIsSavedAndOpensAgainAsItWas() {
        val vm = heartRateEditor()
        vm.setName("Zone 3 base")
        vm.setZone(1, null, 2)
        vm.setAdjust(1, null, HeartRateAdjust.Incline)
        vm.editBlock(1, null) { it.copy(speed = "5.5", incline = "4") }
        vm.setHeartRateBound(1, null, maximum = true, text = "9")
        var saved = false
        vm.save { saved = true }
        assertTrue(saved)

        val program = runBlocking { data.programs.programs(userId).first() }.single().program
        val target = (program.steps[1] as Block).target as BlockTarget.HeartRate
        assertEquals(HeartRateZone(HeartRateBasis.MaxHeartRate, 70, 80), target.zone)
        assertEquals(HeartRateAdjust.Incline, target.adjust)
        assertEquals(1.0, target.minimum, 0.001) // 4 % − 3
        assertEquals(9.0, target.maximum, 0.001)
        assertEquals(BlockTarget.Absolute(5.5, 4.0), target.start)

        val reopened = ProgramEditorViewModel(program.id, data, app.controller)
        val hr = checkNotNull(reopened.block(1).heartRate)
        assertEquals(2, hr.standardZone)
        assertEquals(HeartRateAdjust.Incline, hr.adjust)
        assertEquals("1", hr.minimum)
        assertEquals("9", hr.maximum)
        assertFalse(hr.boundsAuto)
        assertEquals("5.5", reopened.block(1).speed)
        assertEquals("4", reopened.block(1).incline)
    }

    @Test fun theChartAndThePlanMarkTheHeartRateBlocks() {
        val vm = heartRateEditor()
        val plan = checkNotNull(vm.state.plan)
        assertNotNull(plan.blocks[1].heartRate)
        assertNull(plan.blocks[0].heartRate)
        val segments = plan.segments()
        assertEquals(listOf(false, true, false), segments.map { it.heartRate != null })
        // The block is drawn at its starting values.
        assertEquals(8.0, segments[1].speedKmh, 0.001)
    }

    @Test fun aGeneratedProgramIsNotEditedHereButCanBeDuplicatedAsAFreeOne() {
        val user = runBlocking { data.profiles.activeUser.first()!! }
        val generated = GeneratedFixtures.generate(
            data, user, "zone2", today = app.date, nowMillis = app.nowMillis(),
        )
        val id = (runBlocking { data.programs.save(user.id, generated.program) } as com.treadz.console.userdata.ProgramSaveResult.Saved).program.id

        val editor = ProgramEditorViewModel(id, data, app.controller)
        assertTrue(editor.state.unsupported)
        assertTrue(editor.state.generated)
        editor.save { error("must not save") }

        val programs = com.treadz.console.training.ProgramsViewModel(data) { "$it (copy)" }
        var copyId: String? = null
        programs.duplicate(id) { copyId = it }
        val copy = checkNotNull(runBlocking { data.programs.get(checkNotNull(copyId)) }).program
        assertEquals(ProgramKind.Free, copy.kind)
        assertNull(copy.origin)
        assertTrue(copy.name.endsWith("(copy)"))
        assertEquals(generated.program.steps, copy.steps)
        // The copy opens in the editor with its heart-rate block.
        val copyEditor = ProgramEditorViewModel(copyId, data, app.controller)
        assertFalse(copyEditor.state.unsupported)
        assertTrue(copyEditor.state.draft.steps.any { (it as? SingleStepDraft)?.block?.heartRate != null })
    }

    @Test fun aRepeatKeepsItsHeartRateBlocksInTheSavedProgram() {
        val vm = newEditor()
        vm.setName("Intervals")
        vm.addRepeat()
        val repeat = vm.state.draft.steps.lastIndex
        vm.setHeartRateTarget(repeat, 0, true)
        var saved = false
        vm.save { saved = true }
        assertTrue(saved)
        val program: Program = runBlocking { data.programs.programs(userId).first() }.single().program
        val block = (program.steps[repeat] as Repeat).blocks[0]
        assertTrue(block.target is BlockTarget.HeartRate)
        assertTrue((program.steps[repeat] as Repeat).blocks[1].target is BlockTarget.Absolute)
    }
}
