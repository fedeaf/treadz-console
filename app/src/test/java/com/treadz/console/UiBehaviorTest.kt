package com.treadz.console

import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.activity.ComponentActivity
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.treadz.console.fakes.FakeApp
import com.treadz.console.fakes.FakeTreadmillController
import com.treadz.console.treadmill.BeltStatus
import com.treadz.console.treadmill.CommandResult
import com.treadz.console.treadmill.FailureCode
import com.treadz.console.treadmill.RecordingInfo
import com.treadz.console.treadmill.TreadmillEvent
import com.treadz.console.ui.MainActions
import com.treadz.console.ui.PermissionsUi
import com.treadz.console.ui.TreadZRoot
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Checks the behaviour of the real UI (Compose + ViewModel) on top of fake treadmills. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "en-w1280dp-h800dp-land-hdpi")
class UiBehaviorTest {

    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val app = FakeApp()
    private var shared: RecordingInfo? = null

    private fun show() {
        app.viewModel
        rule.setContent {
            TreadZRoot(app, app.viewModel, PermissionsUi(), MainActions(onShare = { shared = it }), app.navigation)
        }
        rule.waitForIdle()
    }

    @Test fun startButtonEnabledOnlyWhenConnectedAndIdle() {
        app.controller.showConnected()
        show()
        rule.onNodeWithTag("start_button").assertIsEnabled()
        app.controller.showRunning()
        rule.waitForIdle()
        // Once the belt is on, START gives way to the controls.
        rule.onNodeWithTag("start_button").assertDoesNotExist()
        rule.onNodeWithTag("stop_button").assertIsDisplayed()
    }

    @Test fun connectionChipShowsTheRememberedNameAfterANamelessReconnection() {
        app.settings.lastTreadmill = com.treadz.console.settings.RememberedTreadmill(FakeTreadmillController.TREADMILL_ADDRESS, "REEBOK 0517")
        app.controller.showConnected(name = null)
        show()
        rule.onNodeWithText("Connected: REEBOK 0517").assertIsDisplayed()
    }

    @Test fun clickingStartCallsController() {
        app.controller.showConnected()
        show()
        rule.onNodeWithTag("start_button").performClick()
        rule.waitForIdle()
        assertEquals(1, app.controller.startCalls)
        rule.onNodeWithTag("belt_status").assertTextEquals("Starting…")
    }

    @Test fun startFailureIsShown() {
        app.controller.showConnected()
        app.controller.startResult = CommandResult.Rejected(FailureCode.RejectedByTreadmill, "busy")
        show()
        rule.onNodeWithTag("start_button").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("start_failure").assertIsDisplayed()
    }

    @Test fun heartRateShowsDashWithoutMonitor() {
        app.controller.showConnected()
        app.controller.showRunning(heartRate = null)
        show()
        rule.onNodeWithTag("metric_value_Heart rate", useUnmergedTree = true).assertTextEquals("—")
    }

    @Test fun alertCoversScreenAndCanBeDismissed() {
        app.controller.showConnected()
        show()
        app.controller.emit(TreadmillEvent.ConnectionLost)
        rule.waitForIdle()
        rule.onNodeWithTag("alert_overlay").assertIsDisplayed()
        rule.onNodeWithTag("alert_dismiss").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("alert_overlay").assertDoesNotExist()
    }

    @Test fun recordingIndicatorAndShare() {
        app.controller.showConnected()
        app.recorder.showRecording()
        show()
        rule.onNodeWithTag("recording_indicator").assertIsDisplayed()
        app.recorder.showSaved()
        rule.waitForIdle()
        rule.onNodeWithTag("recording_saved").assertIsDisplayed()
        rule.onNodeWithTag("recording_indicator").assertDoesNotExist()
        rule.onNodeWithText("Share").performClick()
        assertTrue(shared != null)
    }

    private fun anyViewKeepsScreenOn(): Boolean {
        fun check(v: android.view.View): Boolean =
            v.keepScreenOn || (v is android.view.ViewGroup && (0 until v.childCount).any { check(v.getChildAt(it)) })
        return check(rule.activity.window.decorView)
    }

    @Test fun keepScreenOnWhileBeltRuns() {
        app.controller.showConnected()
        show()
        assertEquals(false, anyViewKeepsScreenOn())
        app.controller.showState { it.copy(status = BeltStatus.Running) }
        rule.waitForIdle()
        assertEquals(true, anyViewKeepsScreenOn())
        app.controller.showState { it.copy(status = BeltStatus.Idle) }
        rule.waitForIdle()
        assertEquals(false, anyViewKeepsScreenOn())
    }
}
