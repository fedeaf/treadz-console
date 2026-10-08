package com.treadz.console

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import com.treadz.console.fakes.FakeApp
import com.treadz.console.fakes.FakeTreadmillController
import com.treadz.console.settings.RememberedTreadmill
import com.treadz.console.treadmill.ConnectionProblem
import com.treadz.console.treadmill.ConnectionState
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

/** Connection screen and reconnection banner on the real UI: Bluetooth off, problems, no addresses, the way out. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "en-w1280dp-h800dp-land-hdpi")
class ConnectionUiTest {

    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val app = FakeApp()
    private val controller get() = app.controller
    private val address = FakeTreadmillController.TREADMILL_ADDRESS
    private var bluetoothRequests = 0
    private var permissionRequests = 0

    private fun show() {
        app.viewModel
        rule.setContent {
            TreadZRoot(
                app,
                app.viewModel,
                PermissionsUi(),
                MainActions(onEnableBluetooth = { bluetoothRequests++ }, onRequestPermissions = { permissionRequests++ }),
                app.navigation,
            )
        }
        rule.waitForIdle()
    }

    private fun assertNoText(text: String) {
        assertEquals("Unexpected text on screen: $text", 0, rule.onAllNodesWithText(text, substring = true, ignoreCase = true).fetchSemanticsNodes().size)
    }

    // ---- Bluetooth off ----

    @Test fun bluetoothOffIsOneClearPromptWithAButtonToTurnItOn() {
        // The real case: Bluetooth off with a remembered treadmill used to show two errors, one of them in raw English.
        app.settings.lastTreadmill = RememberedTreadmill(address, "REEBOK 0517")
        controller.showBluetoothOff()
        app.viewModel.onPermissionsGranted()
        show()
        rule.onNodeWithTag("bluetooth_off_prompt").assertIsDisplayed()
        rule.onNodeWithText("Bluetooth is off").assertIsDisplayed()
        assertNoText("Could not connect")
        assertNoText("Search failed")
        assertNoText("unavailable")
        rule.onNodeWithTag("bluetooth_enable_button").performClick()
        assertEquals(1, bluetoothRequests)
    }

    @Test fun scanAndReconnectAreDisabledWhileBluetoothIsOff() {
        app.settings.lastTreadmill = RememberedTreadmill(address, "REEBOK 0517")
        controller.showBluetoothOff()
        show()
        rule.onNodeWithTag("scan_button").assertIsNotEnabled()
        rule.onNodeWithTag("reconnect_button").assertIsNotEnabled()
        controller.bluetoothEnabled.value = true
        rule.waitForIdle()
        rule.onNodeWithTag("scan_button").assertIsEnabled()
        rule.onNodeWithTag("reconnect_button").assertIsEnabled()
        rule.onNodeWithTag("bluetooth_off_prompt").assertDoesNotExist()
    }

    @Test fun aStaleBluetoothErrorDoesNotAppearNextToThePrompt() {
        controller.showBluetoothOff()
        controller.connectResult = com.treadz.console.treadmill.CommandResult.Rejected(
            com.treadz.console.treadmill.FailureCode.ConnectionFailed, "Bluetooth off", ConnectionProblem.BluetoothOff,
        )
        show()
        rule.onNodeWithTag("bluetooth_off_prompt").assertIsDisplayed()
        rule.onNodeWithTag("connect_problem").assertDoesNotExist()
    }

    // ---- Problems ----

    @Test fun deviceNotFoundHasItsOwnTranslatedMessage() {
        app.settings.lastTreadmill = RememberedTreadmill(address, "REEBOK 0517")
        controller.connectResult = com.treadz.console.treadmill.CommandResult.Rejected(
            com.treadz.console.treadmill.FailureCode.ConnectionFailed, "raw backend text", ConnectionProblem.DeviceNotFound,
        )
        show()
        app.viewModel.reconnectToRemembered()
        rule.waitForIdle()
        rule.onNodeWithText("Treadmill not found: is it on and nearby?").assertIsDisplayed()
        assertNoText("raw backend text")
    }

    @Test fun aMissingPermissionOffersToAskAgain() {
        controller.connectResult = com.treadz.console.treadmill.CommandResult.Rejected(
            com.treadz.console.treadmill.FailureCode.ConnectionFailed, "x", ConnectionProblem.PermissionMissing,
        )
        show()
        app.viewModel.connect(address, null)
        rule.waitForIdle()
        rule.onNodeWithTag("problem_permission_button").performClick()
        assertEquals(1, permissionRequests)
    }

    @Test fun anyOtherScanErrorIsGenericAndNeverRawText() {
        controller.scanError = IllegalStateException("java.lang.IllegalStateException: something technical")
        show()
        app.viewModel.startScan()
        rule.waitForIdle()
        rule.onNodeWithText("Could not reach the treadmill. Please try again.").assertIsDisplayed()
        assertNoText("IllegalState")
        assertNoText("technical")
    }

    // ---- No addresses ----

    @Test fun theReconnectButtonNeverShowsTheAddress() {
        app.settings.lastTreadmill = RememberedTreadmill(address, null)
        show()
        rule.onNodeWithTag("reconnect_button").assertTextEquals("Reconnect to the treadmill")
        assertNoText(address)
    }

    @Test fun theReconnectButtonShowsTheNameWhenThereIsOne() {
        app.settings.lastTreadmill = RememberedTreadmill(address, "REEBOK 0517")
        show()
        rule.onNodeWithTag("reconnect_button").assertTextEquals("Reconnect to REEBOK 0517")
    }

    @Test fun theScanListMayShowAddressesAsSecondaryText() {
        controller.scanResults = FakeTreadmillController.SAMPLE_DEVICES
        show()
        app.viewModel.startScan()
        rule.waitForIdle()
        rule.onNodeWithText(address).assertIsDisplayed()
        rule.onNodeWithText("REEBOK 0517").assertIsDisplayed()
    }

    // ---- Reconnecting ----

    @Test fun reconnectingOffersToStopAndThatLeadsBackToScanning() {
        controller.scanResults = FakeTreadmillController.SAMPLE_DEVICES
        controller.showConnected()
        show()
        controller.showReconnecting(attempt = 2)
        rule.waitForIdle()
        rule.onNodeWithTag("reconnecting_banner").assertIsDisplayed()
        rule.onNodeWithTag("stop_reconnecting_button").assertTextEquals("Stop reconnecting")
        // Not yet the loud version.
        assertNoText("Treadmill not found")

        rule.onNodeWithTag("stop_reconnecting_button").performClick()
        rule.waitForIdle()

        assertEquals(1, controller.disconnectCalls)
        assertEquals(ConnectionState.Disconnected, app.viewModel.uiState.value.connection)
        rule.onNodeWithText("Connect to your treadmill").assertIsDisplayed()
        rule.onNodeWithTag("scanning_label").assertIsDisplayed()
        rule.onNodeWithText("REEBOK 0517").assertIsDisplayed()
    }

    @Test fun afterSeveralAttemptsTheExplanationAndTheActionAreMoreProminent() {
        controller.showReconnecting(attempt = MainUiState.STUCK_RECONNECT_ATTEMPTS)
        show()
        rule.onNodeWithText("Treadmill not found").assertIsDisplayed()
        rule.onNodeWithTag("stop_reconnecting_button").assertTextEquals("Stop reconnecting and search")
    }

    @Test fun whenTheTreadmillIsNotFoundItIsProminentAtOnce() {
        controller.showReconnectingNotFound(attempt = 1)
        show()
        rule.onNodeWithText("Treadmill not found").assertIsDisplayed()
        assertTrue(rule.onAllNodesWithText("off or out of range", substring = true).fetchSemanticsNodes().isNotEmpty())
    }

    @Test fun reconnectingWithBluetoothOffSaysWaitingForBluetooth() {
        controller.showReconnecting(attempt = 12)
        controller.showBluetoothOff()
        show()
        rule.onNodeWithTag("connection_chip").assertIsDisplayed()
        rule.onNode(hasText("Waiting for Bluetooth…")).assertIsDisplayed()
        assertNoText("attempt")
        rule.onNodeWithTag("bluetooth_enable_button").performClick()
        assertEquals(1, bluetoothRequests)
        // Giving up is still possible.
        rule.onNodeWithTag("stop_reconnecting_button").assertIsDisplayed()
    }

    @Test fun theStopInstructionStaysWhileReconnectingWithTheBeltMoving() {
        controller.showConnected()
        controller.showRunning()
        controller.showReconnectingNotFound()
        show()
        rule.onNodeWithTag("stop_unreachable").assertIsDisplayed()
        rule.onNodeWithText("Use the console's STOP or pull the safety key.").assertIsDisplayed()
        rule.onNodeWithTag("stop_reconnecting_button").assertIsDisplayed()
    }

    @Test fun afterGivingUpWithTheBeltPossiblyMovingTheInstructionIsStillOnTheConnectionScreen() {
        controller.showConnected()
        controller.showRunning()
        controller.showReconnecting()
        show()
        rule.onNodeWithTag("stop_reconnecting_button").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Connect to your treadmill").assertIsDisplayed()
        rule.onNodeWithTag("stop_unreachable").assertIsDisplayed()
    }
}
