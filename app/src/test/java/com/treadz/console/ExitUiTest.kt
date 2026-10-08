package com.treadz.console

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.treadz.console.fakes.FakeApp
import com.treadz.console.nav.Screen
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

/** The Back button through the real UI: the exit dialog on the root screen, normal navigation elsewhere. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "en-w360dp-h780dp-port-xhdpi")
class ExitUiTest {

    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val app = FakeApp()
    private var finished = 0
    private var movedToBack = 0

    private fun show(vararg then: Screen) {
        val navigation = app.newNavigation()
        rule.setContent {
            TreadZRoot(
                app,
                app.viewModel,
                PermissionsUi(),
                MainActions(onFinish = { finished++ }, onMoveToBackground = { movedToBack++ }),
                navigation,
            )
        }
        rule.waitForIdle()
        then.forEach(navigation::push)
        rule.waitForIdle()
    }

    private fun pressBack() {
        rule.runOnUiThread { rule.activity.onBackPressedDispatcher.onBackPressed() }
        rule.waitForIdle()
    }

    private fun click(tag: String) {
        rule.onNodeWithTag(tag).performClick()
        rule.waitForIdle()
    }

    @Test fun backOnTheMainScreenWithTheBeltIdleAsksBeforeExiting() {
        app.controller.showConnected()
        show()
        pressBack()
        rule.onNodeWithTag("exit_dialog").assertIsDisplayed()
        rule.onNodeWithText("Exit TreadZ?").assertIsDisplayed()
        rule.onNodeWithText("It will disconnect from the treadmill.").assertIsDisplayed()
    }

    @Test fun withoutAConnectionTheDialogHasNoDisconnectText() {
        show()
        pressBack()
        rule.onNodeWithText("Exit TreadZ?").assertIsDisplayed()
        rule.onNodeWithText("It will disconnect from the treadmill.").assertDoesNotExist()
    }

    @Test fun exitFinishesTheTask() {
        app.controller.showConnected()
        show()
        pressBack()
        click("exit_confirm_button")
        assertEquals(1, finished)
        assertEquals(1, app.appLifecycle.exitCalls)
        assertEquals(0, movedToBack)
    }

    @Test fun keepInBackgroundMovesTheTaskToTheBack() {
        app.controller.showConnected()
        show()
        pressBack()
        click("exit_keep_button")
        assertEquals(1, movedToBack)
        assertEquals(0, finished)
        assertEquals(0, app.appLifecycle.exitCalls)
        rule.onNodeWithTag("exit_dialog").assertDoesNotExist()
    }

    @Test fun cancelClosesTheDialog() {
        app.controller.showConnected()
        show()
        pressBack()
        click("exit_cancel_button")
        rule.onNodeWithTag("exit_dialog").assertDoesNotExist()
        assertEquals(0, finished + movedToBack)
    }

    @Test fun backWhileTheBeltRunsShowsTheStopMessageAndNoDialog() {
        app.controller.showConnected()
        app.controller.showRunning()
        show()
        pressBack()
        rule.onNodeWithTag("exit_dialog").assertDoesNotExist()
        rule.onNodeWithText("The belt is moving: press STOP before exiting").assertIsDisplayed()
        rule.onNodeWithTag("stop_button").assertIsDisplayed() // STOP stays reachable
        assertEquals(0, app.appLifecycle.exitCalls)
        click("exit_belt_moving_ok")
        rule.onNodeWithTag("exit_belt_moving").assertDoesNotExist()
    }

    @Test fun backOnAnotherScreenStillGoesBack() {
        app.controller.showConnected()
        show(Screen.Weight)
        pressBack()
        rule.onNodeWithTag("exit_dialog").assertDoesNotExist()
        rule.onNodeWithTag("user_chip").assertIsDisplayed()
    }
}
