package com.treadz.console

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import com.treadz.console.controls.ControlsNote
import com.treadz.console.controls.SessionControls
import com.treadz.console.controls.SessionKind
import com.treadz.console.fakes.FakeApp
import com.treadz.console.fakes.GeneratedFixtures
import com.treadz.console.fakes.TrainingFixtures
import com.treadz.console.history.WorkoutDetailUiState
import com.treadz.console.programs.engine.ProgramProgress
import com.treadz.console.programs.engine.RunState
import com.treadz.console.programs.plan.ProgramResolver
import com.treadz.console.training.ActualSample
import com.treadz.console.training.ProgramSeries
import com.treadz.console.training.RunUiState
import com.treadz.console.treadmill.BeltStatus
import com.treadz.console.treadmill.ConnectionState
import com.treadz.console.treadmill.TreadmillState
import com.treadz.console.ui.ControlActions
import com.treadz.console.ui.FitnessRunScreen
import com.treadz.console.ui.ProgramRunScreen
import com.treadz.console.ui.RunActions
import com.treadz.console.ui.WorkoutDetailActions
import com.treadz.console.ui.WorkoutDetailScreen
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The live screens of this batch, drawn from hand-made states: "+1" (when it is offered, its label, what it calls, and
 * disabled in the fitness tests), the real targets in the block panel, and the α1 lines of a session record.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "en-w1280dp-h800dp-land-hdpi")
class LiveScreensUiTest {

    @get:Rule val rule = createComposeRule()

    /** Warm-up, 1 km, a recovery that keeps the speed, a block that keeps the incline, cool-down. */
    private val plan = ProgramResolver.resolve(TrainingFixtures.keepProgram())

    private val ui = MainUiState(
        connection = ConnectionState.Connected("AA", "REEBOK"),
        treadmill = TreadmillState(status = BeltStatus.Running, speedKmh = 9.6, targetSpeedKmh = 9.6, inclinePercent = 2.0, updatedAtMillis = 1_000),
        session = SessionControls(SessionKind.Program),
    )

    private fun progress(state: RunState = RunState.Running, block: Int = 1, targetSpeed: Double? = 9.6) = ProgramProgress(
        state = state, plan = plan, currentBlockIndex = block, currentBlock = plan.blocks[block], nextBlock = plan.blocks.getOrNull(block + 1),
        blockElapsedSeconds = 100, blockRemainingSeconds = 260, blockElapsedMeters = 280, blockRemainingMeters = if (block == 1) 720 else null,
        totalElapsedSeconds = 400, totalRemainingSeconds = 1_100, totalElapsedMeters = 700,
        targetSpeedKmh = targetSpeed, targetInclinePercent = 2.0, actualSpeedKmh = 9.6, actualInclinePercent = 2.0,
    )

    private var extended = 0

    private fun showProgram(progress: ProgramProgress, series: ProgramSeries = ProgramSeries()) {
        rule.setContent {
            ProgramRunScreen(ui, RunUiState(kind = SessionKind.Program, program = progress, programSeries = series), ControlActions(), RunActions(onExtendBlock = { extended++ }))
        }
        rule.waitForIdle()
    }

    // ---- "+1" ----

    @Test fun plusOneOnADistanceBlockAddsAHundredMetresAndCallsTheAction() {
        showProgram(progress(block = 1))
        rule.onNodeWithTag("extend_block_button").assertIsEnabled().assertTextEquals("+100 m")
        rule.onNode(hasContentDescription("Add 100 m to this block")).assertExists()
        rule.onNodeWithTag("extend_block_button").performClick()
        assertEquals(1, extended)
    }

    @Test fun plusOneOnATimeBlockAddsAMinute() {
        showProgram(progress(block = 2))
        rule.onNodeWithTag("extend_block_button").assertIsEnabled().assertTextEquals("+1 min")
    }

    @Test fun plusOneIsAlsoOfferedWhilePaused() {
        showProgram(progress(state = RunState.Paused))
        rule.onNodeWithTag("extend_block_button").assertIsEnabled()
    }

    @Test fun plusOneWaitsWhileTheProgramStartsOrStops() {
        showProgram(progress(state = RunState.Starting))
        rule.onNodeWithTag("extend_block_button").assertIsNotEnabled()
        rule.onNodeWithTag("skip_next_button").assertIsNotEnabled()
    }

    @Test fun anInterruptedProgramHasNoBlockButtons() {
        showProgram(progress(state = RunState.Interrupted))
        rule.onNodeWithTag("extend_block_button").assertDoesNotExist()
        rule.onNodeWithTag("stop_button").assertExists()
    }

    @Test fun aFitnessTestShowsPlusOneDisabled() {
        val locked = SessionControls(SessionKind.FitnessTest, speed = false, incline = false, pause = false, note = ControlsNote.TestLocked)
        val test = TrainingFixtures.walkRamp(200)
        rule.setContent {
            FitnessRunScreen(ui.copy(session = locked), RunUiState(kind = SessionKind.FitnessTest, test = test), ControlActions(), RunActions(onExtendBlock = { extended++ }))
        }
        rule.waitForIdle()
        rule.onNodeWithTag("extend_block_button").assertIsNotEnabled().assertTextEquals("+1 min")
        rule.onNodeWithTag("exhausted_button").assertExists()
        rule.onNodeWithTag("stop_button").assertExists()
        assertEquals(0, extended)
    }

    // ---- The block panel uses the engine's real targets ----

    @Test fun theBlockShowsTheRealTargetAndTheNextBlockSaysKeep() {
        // The engine asks for 9.3 km/h (a manual change, a heart-rate adjustment...), not the plan's 10.0.
        showProgram(progress(block = 1, targetSpeed = 9.3))
        rule.onNodeWithTag("block_target").assertTextEquals("9.3 km/h · 2 %")
        rule.onNodeWithTag("block_next").assertTextContains("keep speed", substring = true)
        rule.onNodeWithTag("block_next").assertTextContains("0 %", substring = true)
    }

    @Test fun aBlockThatKeepsShowsWhatTheEngineKept() {
        // The recovery keeps the speed: the panel says what the treadmill has (9.6), the next block keeps the incline.
        showProgram(progress(block = 2, targetSpeed = 9.6).copy(targetInclinePercent = 0.0))
        rule.onNodeWithTag("block_target").assertTextEquals("9.6 km/h · 0 %")
        rule.onNodeWithTag("block_next").assertTextContains("9.0 km/h · keep incline", substring = true)
    }

    @Test fun theLiveChartIsDrawnWithTheRealSeries() {
        val series = (0..400 step 5).fold(ProgramSeries()) { s, t -> s.appended(ActualSample(t, 9.0, 1.0)) }
        showProgram(progress(), series)
        rule.onNodeWithTag("run_chart").assertExists()
        rule.onNodeWithTag("legend_kept").assertExists()
    }

    // ---- The α1 brake in the record of a generated session ----

    private fun showRecord(alpha1: Boolean) {
        val app = FakeApp()
        val user = runBlocking { app.userData.profiles.activeUser.first()!! }
        val base = GeneratedFixtures.record(generatorId = "zone2", startedAtMillis = 1_700_000_000_000, inputs = mapOf("mode" to "run"), settledValue = 9.1)
        val record = if (!alpha1) base else base.copy(
            blocks = base.blocks.map { it.copy(heartRate = it.heartRate!!.copy(alpha1Reductions = 2, alpha1Mean = 0.684, alpha1Windows = 54)) },
        )
        val id = GeneratedFixtures.addSession(app.userData, user.id, record)
        val detail = runBlocking { app.userData.workouts.detail(id) }
        rule.setContent { WorkoutDetailScreen(WorkoutDetailUiState(loading = false, detail = detail, owner = user), WorkoutDetailActions()) }
        rule.waitForIdle()
    }

    @Test fun theRecordShowsTheReductionsAndTheMeanAlpha1() {
        showRecord(alpha1 = true)
        rule.onNodeWithTag("record_alpha1_reductions_1", useUnmergedTree = true).assertTextEquals("Reductions by α1: 2")
        rule.onNodeWithTag("record_alpha1_mean_1", useUnmergedTree = true).assertTextEquals("Mean α1: 0.68 (54 windows)")
    }

    @Test fun withoutAlpha1DataTheRecordSaysNothingAboutIt() {
        showRecord(alpha1 = false)
        rule.onNodeWithTag("record_block_1").assertExists()
        rule.onNodeWithTag("record_alpha1_reductions_1", useUnmergedTree = true).assertDoesNotExist()
        rule.onNodeWithTag("record_alpha1_mean_1", useUnmergedTree = true).assertDoesNotExist()
    }
}
