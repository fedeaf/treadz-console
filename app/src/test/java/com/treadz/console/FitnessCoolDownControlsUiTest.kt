package com.treadz.console

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import com.treadz.console.controls.ControlsNote
import com.treadz.console.controls.SessionControls
import com.treadz.console.controls.SessionKind
import com.treadz.console.fakes.TrainingFixtures
import com.treadz.console.fakes.TrainingRig
import com.treadz.console.programs.fitness.FitnessTestProgress
import com.treadz.console.training.RunUiState
import com.treadz.console.treadmill.BeltStatus
import com.treadz.console.treadmill.ConnectionState
import com.treadz.console.treadmill.TreadmillState
import com.treadz.console.ui.ControlActions
import com.treadz.console.ui.FitnessRunScreen
import com.treadz.console.ui.RunActions
import com.treadz.console.userdata.memory.DemoData
import com.treadz.console.userdata.memory.DemoPrograms
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The fitness test screen offers − / + for speed and incline only while manual changes do not spoil the test. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "en-w1280dp-h800dp-land-hdpi")
class FitnessCoolDownControlsUiTest {

    @get:Rule val rule = createComposeRule()
    private val rig = TrainingRig()
    private val ana by lazy { runBlocking { DemoData.populate(rig.app.userData, rig.app.date).first } }

    private val speedSteps = mutableListOf<Int>()
    private val inclineSteps = mutableListOf<Int>()

    private fun show(test: FitnessTestProgress, session: SessionControls) {
        val ui = MainUiState(
            connection = ConnectionState.Connected("AA", "REEBOK"),
            treadmill = TreadmillState(
                status = BeltStatus.Running, speedKmh = test.actualSpeedKmh, targetSpeedKmh = test.targetSpeedKmh,
                inclinePercent = test.actualInclinePercent, heartRateBpm = test.heartRateBpm, updatedAtMillis = 1_000,
            ),
            activeUser = ana, session = session,
        )
        val controls = ControlActions(onSpeedPress = { speedSteps += it }, onInclinePress = { inclineSteps += it })
        rule.setContent { FitnessRunScreen(ui, RunUiState(kind = SessionKind.FitnessTest, test = test), controls, RunActions()) }
        rule.waitForIdle()
    }

    @Test fun theCoolDownOffersSpeedAndIncline() {
        show(TrainingFixtures.coolDown(DemoPrograms.maxTest(startedAtMillis = 1_000_000)), SessionControls(SessionKind.FitnessTest, pause = false))

        rule.onNodeWithTag("speed_plus").assertIsEnabled()
        rule.onNodeWithTag("incline_plus").assertIsEnabled().performClick()
        rule.onNodeWithTag("speed_minus").performClick()
        assertEquals(listOf(+1), inclineSteps)
        assertEquals(listOf(-1), speedSteps)
    }

    @Test fun duringTheTestTheFiguresHaveNoButtons() {
        val locked = SessionControls(SessionKind.FitnessTest, speed = false, incline = false, pause = false, note = ControlsNote.TestLocked)
        show(TrainingFixtures.maxIncremental(), locked)

        rule.onNodeWithTag("speed_figure").assertExists()
        rule.onNodeWithTag("incline_figure").assertExists()
        rule.onNodeWithTag("speed_plus").assertDoesNotExist()
        rule.onNodeWithTag("incline_plus").assertDoesNotExist()
    }
}
