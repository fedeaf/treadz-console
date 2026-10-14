package com.treadz.console.screenshots

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onRoot
import com.github.takahirom.roborazzi.captureRoboImage
import com.treadz.console.fakes.FakeApp
import com.treadz.console.fakes.FakeTreadmillController
import com.treadz.console.settings.RememberedTreadmill
import com.treadz.console.treadmill.CommandResult
import com.treadz.console.treadmill.ConnectionProblem
import com.treadz.console.treadmill.FailureCode
import com.treadz.console.nav.Screen
import com.treadz.console.ui.MainActions
import com.treadz.console.ui.PermissionsUi
import com.treadz.console.ui.TreadZRoot
import kotlinx.coroutines.awaitCancellation
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

/**
 * Connection problems and the incline presets: Bluetooth off, the way out of "Reconnecting", treadmill not found, a
 * remembered treadmill without a name. Subclasses set the language and screen size with `@Config(qualifiers = ...)`.
 * Images are only written with `./gradlew :app:recordRoborazziDebug`.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
abstract class ConnectionScreenshotScenarios(private val prefix: String) {

    @get:Rule val rule = createComposeRule()

    private val app = FakeApp()
    private val controller get() = app.controller
    private val address = FakeTreadmillController.TREADMILL_ADDRESS

    /** The connection controls live in Devices ([devices]); the reconnection banners are on the live screen. */
    private fun capture(name: String, devices: Boolean = false, interact: () -> Unit = {}) {
        val navigation = app.newNavigation()
        rule.setContent {
            TreadZRoot(dependencies = app, viewModel = app.viewModel, permissions = PermissionsUi(), actions = MainActions(), navigation = navigation)
        }
        rule.waitForIdle()
        if (devices) navigation.push(Screen.Devices)
        rule.waitForIdle()
        interact()
        rule.waitForIdle()
        rule.onRoot().captureRoboImage("build/outputs/roborazzi/${prefix}_$name.png")
    }

    private fun remembered(name: String?) {
        // The ViewModel reads it when it is created, which happens on first use.
        app.settings.lastTreadmill = RememberedTreadmill(address, name)
    }

    private fun running() {
        controller.showConnected()
        controller.showRunning()
        app.recorder.showRecording()
    }

    // ---- Bluetooth off ----

    /** The real case: Bluetooth off with a remembered treadmill (no name). One prompt, nothing else. */
    @Test fun n01_bluetooth_off() {
        remembered(null)
        controller.showBluetoothOff()
        app.viewModel.onPermissionsGranted()
        capture("n01_bluetooth_off", devices = true)
    }

    @Test fun n02_bluetooth_off_without_remembered_treadmill() {
        controller.showBluetoothOff()
        app.viewModel.onPermissionsGranted()
        capture("n02_bluetooth_off_nothing_remembered", devices = true)
    }

    @Test fun n03_reconnecting_waiting_for_bluetooth() {
        controller.showConnected()
        controller.showReconnecting(attempt = 12)
        controller.showBluetoothOff()
        capture("n03_reconnecting_bluetooth_off")
    }

    // ---- Reconnecting ----

    @Test fun n04_reconnecting_idle_with_stop_reconnecting() {
        controller.showConnected()
        controller.showReconnecting(attempt = 2)
        capture("n04_reconnecting_idle")
    }

    @Test fun n05_reconnecting_belt_moving_keeps_the_stop_instruction() {
        running()
        controller.showReconnecting(attempt = 2)
        capture("n05_reconnecting_running")
    }

    @Test fun n06_reconnecting_not_found_idle() {
        controller.showConnected()
        controller.showReconnectingNotFound(attempt = 7)
        capture("n06_reconnecting_not_found_idle")
    }

    @Test fun n07_reconnecting_not_found_belt_moving() {
        running()
        controller.showReconnectingNotFound(attempt = 7)
        capture("n07_reconnecting_not_found_running")
    }

    // ---- Connection screen ----

    @Test fun n08_treadmill_not_found_after_reconnect_attempt() {
        remembered("REEBOK 0517")
        controller.connectResult = CommandResult.Rejected(FailureCode.ConnectionFailed, "raw backend text", ConnectionProblem.DeviceNotFound)
        controller.scanResults = emptyList()
        app.viewModel.onPermissionsGranted()
        app.viewModel.reconnectToRemembered()
        capture("n08_treadmill_not_found", devices = true)
    }

    @Test fun n09_reconnect_button_without_name() {
        remembered(null)
        capture("n09_reconnect_without_name", devices = true)
    }

    @Test fun n10_scan_list_keeps_addresses_as_secondary_text() {
        remembered("REEBOK 0517")
        controller.scanResults = FakeTreadmillController.SAMPLE_DEVICES
        app.viewModel.startScan()
        capture("n10_scan_list", devices = true)
    }

    @Test fun n11_permission_missing() {
        controller.connectResult = CommandResult.Rejected(FailureCode.ConnectionFailed, "x", ConnectionProblem.PermissionMissing)
        remembered("REEBOK 0517")
        app.viewModel.reconnectToRemembered()
        capture("n11_permission_missing", devices = true)
    }

    @Test fun n12_generic_problem() {
        controller.scanError = IllegalStateException("raw exception text that must never be shown")
        app.viewModel.startScan()
        capture("n12_generic_problem", devices = true)
    }

    // ---- Incline presets ----

    @Test fun i01_running_with_incline_presets() {
        running()
        capture("i01_incline_presets")
    }

    @Test fun i02_incline_target_pending_on_a_preset() {
        running()
        controller.setInclineHandler = { awaitCancellation() }
        app.viewModel.controls.setIncline(9.0)
        capture("i02_incline_preset_pending")
    }

    @Test fun i03_incline_presets_paused() {
        running()
        controller.showState { it.copy(status = com.treadz.console.treadmill.BeltStatus.Paused, speedKmh = 0.0, inclinePercent = 0.0) }
        capture("i03_incline_presets_paused")
    }
}
