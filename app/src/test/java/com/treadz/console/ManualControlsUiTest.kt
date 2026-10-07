package com.treadz.console

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.center
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTouchInput
import com.treadz.console.fakes.FakeApp
import com.treadz.console.nav.Screen
import com.treadz.console.treadmill.BeltStatus
import com.treadz.console.treadmill.CommandResult
import com.treadz.console.treadmill.FailureCode
import com.treadz.console.ui.MainActions
import com.treadz.console.ui.PermissionsUi
import com.treadz.console.ui.TreadZRoot
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The manual controls as the user meets them: touches on the real screens over a fake treadmill. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "en-w1280dp-h800dp-land-hdpi")
class ManualControlsUiTest {

    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val app = FakeApp()
    private val controller get() = app.controller

    private fun show(vararg then: Screen) {
        app.viewModel
        val navigation = app.navigation
        rule.setContent {
            TreadZRoot(app, app.viewModel, PermissionsUi(), MainActions(), navigation)
        }
        rule.waitForIdle()
        then.forEach(navigation::push)
        rule.waitForIdle()
    }

    private fun running() {
        controller.showConnected()
        controller.showRunning()
        controller.showState { it.copy(speedKmh = 8.0, targetSpeedKmh = 8.0, inclinePercent = 3.0) }
    }

    @Test fun stopIsOneTapAndNeedsNoConfirmation() {
        running()
        show()
        rule.onNodeWithTag("stop_button").performClick()
        rule.waitForIdle()
        assertEquals(1, controller.stopCalls)
        rule.onNodeWithTag("confirm_dialog").assertDoesNotExist()
    }

    @Test fun stopActsOnTouchDown() {
        running()
        show()
        rule.onNodeWithTag("stop_button").performTouchInput { down(center) }
        rule.waitForIdle()
        assertEquals(1, controller.stopCalls) // before the finger is lifted
    }

    @Test fun stopIsThereWhileStartingAndGoneWhenIdle() {
        controller.showConnected()
        show()
        rule.onNodeWithTag("stop_button").assertDoesNotExist()
        controller.showState { it.copy(status = BeltStatus.Starting) }
        rule.waitForIdle()
        rule.onNodeWithTag("stop_button").assertIsDisplayed()
        // Stopped but the belt is still slowing down: STOP stays.
        controller.showState { it.copy(status = BeltStatus.Idle, speedKmh = 4.0) }
        rule.waitForIdle()
        rule.onNodeWithTag("stop_button").assertIsDisplayed()
        controller.showState { it.copy(speedKmh = 0.0) }
        rule.waitForIdle()
        rule.onNodeWithTag("stop_button").assertDoesNotExist()
        rule.onNodeWithTag("start_button").assertIsEnabled()
    }

    @Test fun stopWorksWithoutAnActiveUserButTheControlsDoNot() {
        running()
        kotlinx.coroutines.runBlocking { app.userData.profiles.setActiveUser(null) }
        show(Screen.Main)
        rule.onNodeWithTag("speed_plus").assertIsNotEnabled()
        rule.onNodeWithTag("pause_resume_button").assertIsNotEnabled()
        rule.onNodeWithTag("stop_button").performClick()
        assertEquals(1, controller.stopCalls)
    }

    @Test fun withoutConnectionStopIsReplacedByTheConsoleMessage() {
        running()
        show()
        rule.onNodeWithTag("stop_button").assertIsDisplayed()
        controller.showReconnecting()
        rule.waitForIdle()
        rule.onNodeWithTag("stop_button").assertDoesNotExist()
        rule.onNodeWithTag("stop_unreachable").assertIsDisplayed()
        rule.onNodeWithText("Use the console's STOP or pull the safety key.").assertIsDisplayed()
    }

    @Test fun plusAndMinusStepTheSpeedAndTheIncline() {
        running()
        show()
        rule.onNodeWithTag("speed_plus").performClick()
        rule.onNodeWithTag("speed_minus").performClick()
        rule.onNodeWithTag("incline_plus").performClick()
        rule.waitForIdle()
        assertEquals(listOf(8.1, 8.0), controller.speedCommands)
        assertEquals(listOf(4.0), controller.inclineCommands)
    }

    @Test fun pressingAndLiftingSendsOneStepAndStopsTheRepeat() {
        running()
        show()
        rule.onNodeWithTag("speed_plus").performTouchInput { down(center); up() }
        rule.waitForIdle()
        assertEquals(listOf(8.1), controller.speedCommands)
    }

    @Test fun presetsAndZeroInclineSetTheirValue() {
        running()
        show()
        rule.onNodeWithTag("speed_preset_12").performClick()
        rule.onNodeWithTag("incline_zero").performClick()
        rule.waitForIdle()
        assertEquals(listOf(12.0), controller.speedCommands)
        assertEquals(listOf(0.0), controller.inclineCommands)
    }

    @Test fun controlsAreDisabledWhenPausedAndResumeWaitsForTheBelt() {
        running()
        controller.showState { it.copy(status = BeltStatus.Paused, speedKmh = 2.0) }
        show()
        rule.onNodeWithTag("speed_plus").assertIsNotEnabled()
        rule.onNodeWithTag("speed_preset_6").assertIsNotEnabled()
        rule.onNodeWithTag("incline_zero").assertIsNotEnabled()
        rule.onNodeWithTag("pause_resume_button").assertIsNotEnabled()
        rule.onNodeWithText("RESUME").assertIsDisplayed()
        rule.onNodeWithTag("control_hint").assertTextContains("Wait for the belt to stop", substring = true)
        controller.showState { it.copy(speedKmh = 0.0) }
        rule.waitForIdle()
        rule.onNodeWithTag("pause_resume_button").assertIsEnabled()
        rule.onNodeWithTag("pause_resume_button").performClick()
        rule.waitForIdle()
        assertEquals(1, controller.resumeCalls)
    }

    @Test fun pauseButtonPausesTheBelt() {
        running()
        show()
        rule.onNodeWithTag("pause_resume_button").performClick()
        rule.waitForIdle()
        assertEquals(1, controller.pauseCalls)
    }

    @Test fun requestedTargetShowsNextToTheRealSpeedAndAFailureShowsAMessage() {
        running()
        controller.setSpeedHandler = { kotlinx.coroutines.awaitCancellation() }
        show()
        rule.onNodeWithTag("speed_plus").performClick()
        rule.onNodeWithTag("speed_plus").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("speed_value").assertTextEquals("8.0")
        rule.onNodeWithTag("speed_target").assertTextEquals("→ 8.2")
        rule.onNodeWithTag("speed_busy").assertIsDisplayed()
    }

    @Test fun aCommandNotExecutedIsTheFullScreenAlertWithoutAnExtraMessage() {
        running()
        controller.setSpeedHandler = { CommandResult.NotVerified(FailureCode.TargetNotReached, "x") }
        show()
        rule.onNodeWithTag("speed_plus").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("alert_overlay").assertIsDisplayed()
        rule.onNodeWithTag("speed_failure").assertDoesNotExist()
        // The alert does not stand between the user and STOP.
        rule.onNodeWithTag("alert_stop_button").performClick()
        rule.waitForIdle()
        assertEquals(1, controller.stopCalls)
    }

    @Test fun aFailedCommandShowsATranslatedMessageButAPreemptedOneDoesNot() {
        running()
        controller.setSpeedHandler = { CommandResult.Rejected(FailureCode.RejectedByTreadmill, "x") }
        show()
        rule.onNodeWithTag("speed_plus").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("speed_failure").assertTextContains("Could not change the speed: the treadmill rejected the command.")

        app.viewModel.controls.dismissFailure()
        controller.setSpeedHandler = { CommandResult.NotVerified(FailureCode.Preempted, "x") }
        rule.onNodeWithTag("speed_plus").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("speed_failure").assertDoesNotExist()
    }

    // ---- STOP bar on the other screens ----

    @Test fun otherScreensShowTheStopBarWhileTheBeltIsOn() {
        running()
        show(Screen.Weight)
        rule.onNodeWithTag("session_bar").assertIsDisplayed()
        rule.onNodeWithTag("session_speed").assertTextEquals("8.0 km/h")
        rule.onNodeWithTag("session_stop_button").performClick()
        rule.waitForIdle()
        assertEquals(1, controller.stopCalls)
        // The belt is stopped: the bar goes away.
        rule.onNodeWithTag("session_bar").assertDoesNotExist()
    }

    @Test fun theBarIsNotOnTheMainScreenNorWithTheBeltAtRest() {
        controller.showConnected()
        show(Screen.Weight)
        rule.onNodeWithTag("session_bar").assertDoesNotExist()
        running()
        rule.waitForIdle()
        rule.onNodeWithTag("session_bar").assertIsDisplayed()
        rule.onNodeWithTag("session_back_button").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("session_bar").assertDoesNotExist()
        rule.onNodeWithTag("stop_button").assertIsDisplayed()
    }

    @Test fun theBarOffersTheConsoleInstructionWithoutConnection() {
        running()
        show(Screen.History)
        controller.showReconnecting()
        rule.waitForIdle()
        rule.onNodeWithTag("session_stop_button").assertDoesNotExist()
        rule.onNodeWithTag("session_unreachable").assertIsDisplayed()
    }

    @Test fun theBarIsOnEveryOtherScreen() {
        running()
        show()
        for (screen in listOf(Screen.UserSelection, Screen.Weight, Screen.History, Screen.ProfileEditor(null, 1))) {
            app.navigation.push(screen)
            rule.waitForIdle()
            rule.onNodeWithTag("session_stop_button").assertIsDisplayed()
            app.navigation.pop()
            rule.waitForIdle()
        }
    }
}
