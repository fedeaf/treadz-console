package com.treadz.console.screenshots

import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import com.treadz.console.fakes.FakeApp
import com.treadz.console.lifecycle.ExitViewModel
import com.treadz.console.ui.MainActions
import com.treadz.console.ui.PermissionsUi
import com.treadz.console.ui.TreadZRoot
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

/**
 * Leaving the app with Back on the main screen: the exit dialog (connected and not connected) and the "press STOP"
 * message. Subclasses set the language and screen size with `@Config(qualifiers = ...)`. Images are only written with
 * `./gradlew :app:recordRoborazziDebug`.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
abstract class ExitScreenshotScenarios(private val prefix: String) {

    @get:Rule val rule = createComposeRule()

    private val app = FakeApp()

    /** Opens the main screen and presses Back on it. [dialog] captures the dialog window as well as the screen. */
    private fun pressBackAndCapture(name: String, dialog: Boolean) {
        val navigation = app.newNavigation()
        val exit = ExitViewModel(app.appLifecycle, app.controller)
        rule.setContent {
            TreadZRoot(app, app.viewModel, PermissionsUi(), MainActions(), navigation, exit)
        }
        rule.waitForIdle()
        exit.onBack()
        rule.waitForIdle()
        rule.onRoot().captureRoboImage("build/outputs/roborazzi/${prefix}_${name}.png")
        if (dialog) rule.onNode(isDialog()).captureRoboImage("build/outputs/roborazzi/${prefix}_${name}_dialog.png")
    }

    @Test fun q01_exit_dialog_connected() {
        app.controller.showConnected()
        pressBackAndCapture("q01_exit_connected", dialog = true)
    }

    @Test fun q02_exit_dialog_not_connected() {
        pressBackAndCapture("q02_exit_not_connected", dialog = true)
    }

    @Test fun q03_exit_belt_moving_message() {
        app.controller.showConnected()
        app.controller.showRunning()
        app.recorder.showRecording()
        pressBackAndCapture("q03_exit_belt_moving", dialog = false)
    }
}
