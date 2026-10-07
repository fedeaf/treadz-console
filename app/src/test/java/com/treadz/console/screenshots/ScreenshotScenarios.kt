package com.treadz.console.screenshots

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import com.treadz.console.fakes.FakeApp
import com.treadz.console.fakes.FakeTreadmillController
import com.treadz.console.fakes.FakeTreadmillController.Companion.SAMPLE_DEVICES
import com.treadz.console.settings.RememberedTreadmill
import com.treadz.console.treadmill.BeltStatus
import com.treadz.console.treadmill.CommandResult
import com.treadz.console.treadmill.FailureCode
import com.treadz.console.treadmill.ConnectionState
import com.treadz.console.treadmill.TreadmillEvent
import com.treadz.console.treadmill.TreadmillState
import com.treadz.console.ui.BluetoothPermission
import com.treadz.console.ui.MainActions
import com.treadz.console.ui.PermissionsUi
import com.treadz.console.ui.TreadZRoot
import kotlinx.coroutines.CompletableDeferred
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

/**
 * Each scenario builds the real app (ViewModel + UI) on fake treadmills and renders it to PNG.
 * Subclasses set the language and screen size with `@Config(qualifiers = ...)`.
 *
 * Images are only written with `./gradlew :app:recordRoborazziDebug`, to `app/build/outputs/roborazzi/`.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
abstract class ScreenshotScenarios(private val prefix: String) {

    @get:Rule val rule = createComposeRule()

    private val app = FakeApp()
    private val controller get() = app.controller
    private val recorder get() = app.recorder

    init {
        // The ViewModel is created before emitting anything, as when the app opens.
        app.viewModel
    }

    private fun capture(
        name: String,
        permissions: PermissionsUi = PermissionsUi(),
    ) {
        rule.setContent {
            TreadZRoot(dependencies = app, viewModel = app.viewModel, permissions = permissions, actions = MainActions(), navigation = app.navigation)
        }
        rule.waitForIdle()
        rule.onRoot().captureRoboImage("build/outputs/roborazzi/${prefix}_$name.png")
    }

    @Test fun s01_disconnected() = capture("01_disconnected")

    @Test fun s02_scanning() {
        controller.scanResults = SAMPLE_DEVICES
        app.viewModel.startScan()
        capture("02_scanning")
    }

    @Test fun s03_connecting() {
        controller.scanResults = SAMPLE_DEVICES
        controller.connectGate = CompletableDeferred()
        app.viewModel.connect(FakeTreadmillController.TREADMILL_ADDRESS, FakeTreadmillController.TREADMILL_NAME)
        capture("03_connecting")
    }

    @Test fun s04_connected_idle() {
        controller.showConnected()
        capture("04_connected_idle")
    }

    @Test fun s05_running() {
        controller.showConnected()
        controller.showRunning()
        recorder.showRecording()
        capture("05_running")
    }

    @Test fun s06_running_without_heart_rate() {
        controller.showConnected()
        controller.showRunning(heartRate = null)
        recorder.showRecording()
        capture("06_running_no_hr")
    }

    @Test fun s07_key_removed() {
        controller.showConnected()
        controller.showRunning()
        controller.showState { it.copy(status = BeltStatus.SafetyKeyRemoved, speedKmh = 0.0, targetSpeedKmh = null) }
        controller.emit(TreadmillEvent.SafetyKeyRemoved)
        capture("07_key_removed")
    }

    @Test fun s08_reconnecting() {
        controller.showConnected()
        controller.showRunning()
        controller.showReconnecting(attempt = 2)
        capture("08_reconnecting")
    }

    @Test fun s09_recording_saved() {
        controller.showConnected()
        recorder.showSaved()
        capture("09_recording_saved")
    }

    @Test fun s10_connection_lost() {
        controller.showConnected()
        controller.emit(TreadmillEvent.ConnectionLost)
        capture("10_connection_lost")
    }

    @Test fun s11_data_stalled() {
        controller.showConnected()
        controller.showRunning()
        controller.emit(TreadmillEvent.DataStalled)
        capture("11_data_stalled")
    }

    @Test fun s12_command_not_executed() {
        controller.showConnected()
        controller.showRunning()
        controller.emit(TreadmillEvent.CommandNotExecuted("stop"))
        capture("12_command_not_executed")
    }

    @Test fun s13_start_failed() {
        controller.showConnected()
        controller.startResult = CommandResult.NotVerified(FailureCode.NotStarted, "the belt did not start moving")
        app.viewModel.start()
        capture("13_start_failed")
    }

    @Test fun s14_starting() {
        controller.showConnected()
        controller.showState { it.copy(status = BeltStatus.Starting, speedKmh = 0.0) }
        capture("14_starting")
    }

    @Test fun s15_light_theme_running() {
        controller.showConnected()
        controller.showRunning()
        app.viewModel.setDarkTheme(false)
        capture("15_light_running")
    }

    @Test fun s16_remembered_treadmill_failed() {
        app.settings.lastTreadmill = RememberedTreadmill(FakeTreadmillController.TREADMILL_ADDRESS, FakeTreadmillController.TREADMILL_NAME)
        controller.connectResult = CommandResult.NotConnected
        app.viewModel.onPermissionsGranted()
        capture("16_remembered_failed")
    }

    @Test fun s17_permission_needed() = capture("17_permission_needed", PermissionsUi(BluetoothPermission.NeedsRequest))

    @Test fun s18_permission_denied_permanently() =
        capture("18_permission_denied", PermissionsUi(BluetoothPermission.DeniedPermanently))

    @Test fun s19_notifications_denied() {
        controller.showConnected()
        capture("19_notifications_denied", PermissionsUi(notificationsDenied = true))
    }

    @Test fun s20_light_disconnected() {
        app.viewModel.setDarkTheme(false)
        controller.scanResults = SAMPLE_DEVICES
        app.viewModel.startScan()
        capture("20_light_scanning")
    }

    @Test fun s21_scan_finished_empty() {
        controller.scanNeverEnds = false
        app.viewModel.startScan()
        capture("21_scan_empty")
    }

    @Test fun s22_long_session() {
        controller.showConnected()
        controller.showState {
            TreadmillState(
                status = BeltStatus.Running,
                speedKmh = 12.5,
                inclinePercent = 12.0,
                distanceMeters = 12_350,
                elapsedSeconds = 5_025,
                calories = 1_043,
                heartRateBpm = 168,
                updatedAtMillis = 1_000,
            )
        }
        recorder.showRecording()
        capture("22_long_session")
    }
}
