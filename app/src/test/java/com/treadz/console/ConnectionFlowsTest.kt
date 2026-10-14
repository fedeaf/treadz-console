package com.treadz.console

import com.treadz.console.fakes.FakeApp
import com.treadz.console.fakes.FakeTreadmillController
import com.treadz.console.fakes.InMemorySettingsStore
import com.treadz.console.settings.RememberedTreadmill
import com.treadz.console.treadmill.BeltStatus
import com.treadz.console.treadmill.CommandResult
import com.treadz.console.treadmill.ConnectionProblem
import com.treadz.console.treadmill.ConnectionState
import com.treadz.console.treadmill.DiscoveredTreadmill
import com.treadz.console.treadmill.FailureCode
import com.treadz.console.treadmill.ftms.TransportException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Connection rules of the main screen: the way out of "Reconnecting", Bluetooth off, typed problems instead of raw
 * texts, and the remembered treadmill's name (the address is never shown).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ConnectionFlowsTest {

    @Before fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After fun tearDown() = Dispatchers.resetMain()

    private val address = FakeTreadmillController.TREADMILL_ADDRESS

    private fun appWith(remembered: RememberedTreadmill?) = FakeApp(settings = InMemorySettingsStore(remembered)).also { it.viewModel }

    // ---- Way out of "Reconnecting" ----

    @Test fun stopReconnectingDisconnectsAndStartsAScan() {
        val app = appWith(RememberedTreadmill(address, "REEBOK 0517"))
        app.controller.scanResults = FakeTreadmillController.SAMPLE_DEVICES
        app.controller.showReconnecting(attempt = 7)

        app.viewModel.stopReconnecting()

        assertEquals(1, app.controller.disconnectCalls)
        assertEquals(ConnectionState.Disconnected, app.viewModel.uiState.value.connection)
        assertEquals(1, app.controller.scanCalls)
        assertTrue(app.viewModel.uiState.value.scanning)
        assertEquals(3, app.viewModel.uiState.value.devices.size)
    }

    @Test fun stopReconnectingDoesNothingWhenNotReconnecting() {
        // Closing a live connection with the belt running would stop it: only a pending reconnection is abandoned.
        val app = appWith(null)
        app.controller.showConnected()
        app.controller.showRunning()
        app.viewModel.stopReconnecting()
        assertEquals(0, app.controller.disconnectCalls)
        assertEquals(0, app.controller.scanCalls)
        assertTrue(app.viewModel.uiState.value.isConnected)
        assertEquals(0, app.controller.stopCalls)
    }

    @Test fun stopReconnectingWithBluetoothOffLeavesTheConnectionButDoesNotScan() {
        val app = appWith(RememberedTreadmill(address, "REEBOK 0517"))
        app.controller.showReconnecting()
        app.controller.showBluetoothOff()
        app.viewModel.stopReconnecting()
        assertEquals(1, app.controller.disconnectCalls)
        assertEquals(0, app.controller.scanCalls)
        assertFalse(app.viewModel.uiState.value.scanning)
    }

    @Test fun theReconnectionIsStuckAfterSeveralAttemptsOrWhenTheTreadmillIsNotFound() {
        val app = appWith(null)
        val ui = { app.viewModel.uiState.value }
        app.controller.showReconnecting(attempt = 1)
        assertFalse(ui().reconnectStuck)
        app.controller.showReconnecting(attempt = MainUiState.STUCK_RECONNECT_ATTEMPTS - 1)
        assertFalse(ui().reconnectStuck)
        app.controller.showReconnecting(attempt = MainUiState.STUCK_RECONNECT_ATTEMPTS)
        assertTrue(ui().reconnectStuck)
        // Not found is enough, whatever the attempt.
        app.controller.showReconnecting(attempt = 1)
        app.controller.connectionProblem.value = ConnectionProblem.DeviceNotFound
        assertTrue(ui().reconnectStuck)
        assertEquals(ConnectionProblem.DeviceNotFound, ui().reconnectProblem)
        // Only while reconnecting.
        app.controller.showConnected()
        assertFalse(ui().reconnectStuck)
    }

    @Test fun reconnectingWithBluetoothOffWaitsForBluetoothInsteadOfBeingStuck() {
        val app = appWith(null)
        app.controller.showReconnecting(attempt = 30)
        app.controller.showBluetoothOff()
        val ui = app.viewModel.uiState.value
        assertTrue(ui.waitingForBluetooth)
        assertFalse(ui.reconnectStuck)
        app.controller.bluetoothEnabled.value = true
        assertFalse(app.viewModel.uiState.value.waitingForBluetooth)
        assertTrue(app.viewModel.uiState.value.reconnectStuck)
    }

    @Test fun theBeltMayStillBeMovingAfterGivingUpOnTheReconnection() {
        val app = appWith(null)
        app.controller.showConnected()
        app.controller.showRunning()
        app.controller.showReconnecting()
        app.viewModel.stopReconnecting()
        val ui = app.viewModel.uiState.value
        assertEquals(BeltStatus.Running, ui.treadmill.status)
        // The "use the console's STOP" message stays for as long as the belt may be moving.
        assertTrue(ui.stopUnreachable)
    }

    // ---- Bluetooth off ----

    @Test fun nothingIsScannedOrConnectedWhileBluetoothIsOff() {
        val app = appWith(RememberedTreadmill(address, "REEBOK 0517"))
        app.controller.showBluetoothOff()
        app.viewModel.onPermissionsGranted()
        app.viewModel.startScan()
        app.viewModel.connect(address, "REEBOK 0517")
        app.viewModel.reconnectToRemembered()
        assertEquals(0, app.controller.scanCalls)
        assertTrue(app.controller.connectCalls.isEmpty())
        val ui = app.viewModel.uiState.value
        assertFalse(ui.bluetoothEnabled)
        assertFalse(ui.scanning)
        // The real case: no "could not connect" and no "search failed" on top of the Bluetooth prompt.
        assertNull(ui.problem)
    }

    @Test fun theAutomaticConnectionWaitsForBluetooth() {
        val app = appWith(RememberedTreadmill(address, "REEBOK 0517"))
        app.controller.showBluetoothOff()
        app.viewModel.onPermissionsGranted()
        assertTrue(app.controller.connectCalls.isEmpty())

        app.controller.bluetoothEnabled.value = true

        assertEquals(listOf(address), app.controller.connectCalls)
        assertTrue(app.viewModel.uiState.value.isConnected)
    }

    @Test fun withNoRememberedTreadmillNothingIsAttemptedWhenBluetoothComesOn() {
        // No automatic scan list: the search is something the user asks for in Devices.
        val app = appWith(null)
        app.controller.showBluetoothOff()
        app.viewModel.onPermissionsGranted()
        app.controller.bluetoothEnabled.value = true
        assertEquals(0, app.controller.scanCalls)
        assertTrue(app.controller.connectCalls.isEmpty())
        assertFalse(app.viewModel.uiState.value.scanning)
        assertEquals(TreadmillIndicator.NotSetUp, app.viewModel.uiState.value.treadmillIndicator)
    }

    @Test fun theAutomaticConnectionRunsOnlyOnce() {
        val app = appWith(RememberedTreadmill(address, "REEBOK 0517"))
        app.viewModel.onPermissionsGranted()
        app.controller.showBluetoothOff()
        app.controller.bluetoothEnabled.value = true
        assertEquals(1, app.controller.connectCalls.size)
    }

    @Test fun turningBluetoothOffStopsTheScanAndOnForgetsTheBluetoothProblem() {
        val app = appWith(null)
        app.controller.scanResults = FakeTreadmillController.SAMPLE_DEVICES
        app.viewModel.startScan()
        assertTrue(app.viewModel.uiState.value.scanning)
        app.controller.showBluetoothOff()
        assertFalse(app.viewModel.uiState.value.scanning)

        // A scan that failed because of Bluetooth leaves no stale error behind once it is on again.
        app.controller.bluetoothEnabled.value = true
        app.controller.bluetoothEnabled.value = false
        app.controller.bluetoothEnabled.value = true
        app.controller.scanError = TransportException("raw text", problem = ConnectionProblem.BluetoothOff)
        app.viewModel.startScan()
        assertEquals(ConnectionProblem.BluetoothOff, app.viewModel.uiState.value.problem)
        app.controller.bluetoothEnabled.value = false
        app.controller.bluetoothEnabled.value = true
        assertNull(app.viewModel.uiState.value.problem)
    }

    // ---- Problems, never raw texts ----

    @Test fun scanExceptionsMapToTheirProblem() {
        for (problem in ConnectionProblem.entries) {
            val app = appWith(null)
            app.controller.scanError = TransportException("English text for the log", problem = problem)
            app.viewModel.startScan()
            assertEquals(problem, app.viewModel.uiState.value.problem)
            assertFalse(app.viewModel.uiState.value.scanning)
            assertTrue(app.viewModel.uiState.value.scanFinished)
        }
    }

    @Test fun anyOtherScanExceptionIsAGenericProblem() {
        val app = appWith(null)
        app.controller.scanError = IllegalStateException("something technical")
        app.viewModel.startScan()
        assertEquals(ConnectionProblem.Other, app.viewModel.uiState.value.problem)
    }

    @Test fun connectResultsMapToTheirProblem() {
        for (problem in ConnectionProblem.entries) {
            val app = appWith(null)
            app.controller.connectResult = CommandResult.Rejected(FailureCode.ConnectionFailed, "English text", problem)
            app.viewModel.connect(address, "REEBOK 0517")
            assertEquals(problem, app.viewModel.uiState.value.problem)
        }
    }

    @Test fun connectFailuresWithoutAProblemAreGeneric() {
        for (result in listOf(
            CommandResult.NotConnected,
            CommandResult.Rejected(FailureCode.ConnectionFailed, "no problem given"),
            CommandResult.NotVerified(FailureCode.DataLost, "x"),
        )) {
            val app = appWith(null)
            app.controller.connectResult = result
            app.viewModel.connect(address, null)
            assertEquals(ConnectionProblem.Other, app.viewModel.uiState.value.problem)
        }
    }

    @Test fun theProblemClearsWhenTheNextAttemptStartsOrSucceeds() {
        val app = appWith(null)
        app.controller.connectResult = CommandResult.Rejected(FailureCode.ConnectionFailed, "x", ConnectionProblem.DeviceNotFound)
        app.viewModel.connect(address, null)
        assertEquals(ConnectionProblem.DeviceNotFound, app.viewModel.uiState.value.problem)
        app.controller.connectResult = CommandResult.Success
        app.viewModel.connect(address, null)
        assertNull(app.viewModel.uiState.value.problem)
    }

    @Test fun aFailedAutomaticAttemptRaisesNoProblemAndNeverScans() {
        val app = appWith(RememberedTreadmill(address, "REEBOK 0517"))
        app.controller.connectResult = CommandResult.Rejected(FailureCode.ConnectionFailed, "x", ConnectionProblem.DeviceNotFound)
        app.controller.scanResults = FakeTreadmillController.SAMPLE_DEVICES
        app.viewModel.onPermissionsGranted()
        val ui = app.viewModel.uiState.value
        // Problems belong to what the user asked for (Devices), not to the quiet automatic connection.
        assertNull(ui.problem)
        assertFalse(ui.scanning)
        assertEquals(0, app.controller.scanCalls)
        assertTrue(ui.autoConnecting)
    }

    @Test fun permissionProblemIsKeptSoTheUiCanAskAgain() {
        val app = appWith(null)
        app.controller.connectResult = CommandResult.Rejected(FailureCode.ConnectionFailed, "x", ConnectionProblem.PermissionMissing)
        app.viewModel.connect(address, null)
        assertEquals(ConnectionProblem.PermissionMissing, app.viewModel.uiState.value.problem)
    }

    // ---- The remembered treadmill: a name, never the address ----

    @Test fun aRememberedTreadmillWithoutNameHasNoNameToShow() {
        val app = appWith(RememberedTreadmill(address, null))
        val ui = app.viewModel.uiState.value
        assertNull(ui.rememberedName)
        assertEquals(address, ui.rememberedAddress) // kept to reconnect, not to be shown
    }

    @Test fun aScanThatReportsTheNameOfTheRememberedAddressUpdatesIt() {
        val app = appWith(RememberedTreadmill(address, null))
        app.controller.scanResults = listOf(
            DiscoveredTreadmill("11:22:33:44:55:66", "OTHER", rssi = -40),
            DiscoveredTreadmill(address, "REEBOK 0517", rssi = -50),
        )
        app.viewModel.startScan()
        assertEquals("REEBOK 0517", app.viewModel.uiState.value.rememberedName)
        assertEquals(RememberedTreadmill(address, "REEBOK 0517"), app.settings.lastTreadmill)
    }

    @Test fun aScanNeverReplacesTheNameWithNothingOrAnotherDevice() {
        val app = appWith(RememberedTreadmill(address, "REEBOK 0517"))
        app.controller.scanResults = listOf(
            DiscoveredTreadmill(address, null, rssi = -50),
            DiscoveredTreadmill("11:22:33:44:55:66", "OTHER", rssi = -40),
        )
        app.viewModel.startScan()
        assertEquals("REEBOK 0517", app.viewModel.uiState.value.rememberedName)
        assertEquals(RememberedTreadmill(address, "REEBOK 0517"), app.settings.lastTreadmill)
    }

    @Test fun aConnectionThatReportsTheNameUpdatesTheRememberedOne() {
        val app = appWith(RememberedTreadmill(address, null))
        app.viewModel.reconnectToRemembered()
        assertEquals("REEBOK 0517", app.viewModel.uiState.value.rememberedName)
        assertEquals(RememberedTreadmill(address, "REEBOK 0517"), app.settings.lastTreadmill)
    }

    @Test fun aConnectionWithoutNameKeepsTheKnownOne() {
        val app = appWith(RememberedTreadmill(address, "REEBOK 0517"))
        app.controller.connectWithoutName = true
        app.viewModel.reconnectToRemembered()
        assertEquals("REEBOK 0517", app.viewModel.uiState.value.rememberedName)
        assertEquals("REEBOK 0517", app.viewModel.uiState.value.connectedName)
    }

    @Test fun theConnectedNameFallsBackToTheRememberedNameNeverToTheAddress() {
        val app = appWith(RememberedTreadmill(address, null))
        app.controller.showConnected(address, name = null)
        assertNull(app.viewModel.uiState.value.connectedName)
    }
}
