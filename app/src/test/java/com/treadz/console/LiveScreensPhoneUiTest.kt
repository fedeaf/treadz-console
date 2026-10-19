package com.treadz.console

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import com.treadz.console.controls.ControlsNote
import com.treadz.console.controls.SessionControls
import com.treadz.console.controls.SessionKind
import com.treadz.console.fakes.TrainingFixtures
import com.treadz.console.programs.engine.ProgramProgress
import com.treadz.console.programs.engine.RunState
import com.treadz.console.programs.plan.ProgramResolver
import com.treadz.console.training.RunUiState
import com.treadz.console.treadmill.BeltStatus
import com.treadz.console.treadmill.ConnectionState
import com.treadz.console.treadmill.TreadmillState
import com.treadz.console.ui.ControlActions
import com.treadz.console.ui.FitnessRunScreen
import com.treadz.console.ui.LayoutInfo
import com.treadz.console.ui.LocalLayout
import com.treadz.console.ui.ProgramRunScreen
import com.treadz.console.ui.RunActions
import androidx.compose.runtime.CompositionLocalProvider
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** On the phone "+1" sits in the row of Pause, between ◀ and ▶; a fitness test shows it disabled next to its button. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "en-w360dp-h780dp-port-xhdpi")
class LiveScreensPhoneUiTest {

    @get:Rule val rule = createComposeRule()

    private val phone = LayoutInfo(compact = true, wide = false)
    private val ui = MainUiState(
        connection = ConnectionState.Connected("AA", "REEBOK"),
        treadmill = TreadmillState(status = BeltStatus.Running, speedKmh = 5.0, targetSpeedKmh = 5.0, updatedAtMillis = 1_000),
        session = SessionControls(SessionKind.Program),
    )

    @Test fun plusOneIsInTheRowOfPause() {
        val plan = ProgramResolver.resolve(TrainingFixtures.keepProgram())
        val progress = ProgramProgress(
            state = RunState.Running, plan = plan, currentBlockIndex = 0, currentBlock = plan.blocks[0], nextBlock = plan.blocks[1],
            blockElapsedSeconds = 60, blockRemainingSeconds = 240, targetSpeedKmh = 5.0, targetInclinePercent = 0.0,
        )
        var extended = 0
        rule.setContent {
            CompositionLocalProvider(LocalLayout provides phone) {
                ProgramRunScreen(ui, RunUiState(kind = SessionKind.Program, program = progress), ControlActions(), RunActions(onExtendBlock = { extended++ }))
            }
        }
        rule.waitForIdle()
        rule.onNodeWithTag("extend_block_button").assertIsEnabled().assertTextEquals("+1 min").performClick()
        rule.onNodeWithTag("pause_resume_button").assertExists()
        rule.onNodeWithTag("stop_button").assertExists()
        assertEquals(1, extended)
    }

    @Test fun aFitnessTestShowsItDisabled() {
        val locked = SessionControls(SessionKind.FitnessTest, speed = false, incline = false, pause = false, note = ControlsNote.TestLocked)
        rule.setContent {
            CompositionLocalProvider(LocalLayout provides phone) {
                FitnessRunScreen(ui.copy(session = locked), RunUiState(kind = SessionKind.FitnessTest, test = TrainingFixtures.walkRamp(200)), ControlActions(), RunActions())
            }
        }
        rule.waitForIdle()
        rule.onNodeWithTag("extend_block_button").assertIsNotEnabled()
        rule.onNodeWithTag("exhausted_button").assertIsEnabled()
    }
}
