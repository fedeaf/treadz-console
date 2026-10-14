package com.treadz.console

import com.treadz.console.fakes.FakeApp
import com.treadz.console.fakes.FakeTreadmillController
import com.treadz.console.fakes.InMemorySettingsStore
import com.treadz.console.fakes.MainDispatcherRule
import com.treadz.console.settings.RememberedTreadmill
import com.treadz.console.treadmill.BeltStatus
import com.treadz.console.treadmill.CommandResult
import com.treadz.console.treadmill.ConnectionProblem
import com.treadz.console.treadmill.ConnectionState
import com.treadz.console.treadmill.FailureCode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * When the main screen shows the home and when today's live data: the live data while the treadmill is connected (or
 * reconnecting), and, whatever the connection, while the belt may be moving. Plus the words of the treadmill indicator.
 */
class HomeLiveSwitchTest {

    @get:Rule val main = MainDispatcherRule()

    private val address = FakeTreadmillController.TREADMILL_ADDRESS
    private var app = FakeApp()
    private val ui get() = app.viewModel.uiState.value

    @Test fun theHomeIsShownWhileTheTreadmillIsNotConnected() {
        assertFalse(ui.showsLiveData)
    }

    @Test fun connectingSwitchesToTheLiveDataAndDisconnectingAtRestSwitchesBack() {
        app.controller.showConnected()
        assertTrue(ui.showsLiveData)
        app.controller.connection.value = ConnectionState.Disconnected
        assertFalse(ui.showsLiveData)
    }

    @Test fun reconnectingKeepsTheLiveData() {
        app.controller.showConnected()
        app.controller.showReconnecting()
        assertTrue(ui.showsLiveData)
    }

    @Test fun neverLeavesTheLiveDataWhileTheBeltMayBeMoving() {
        app.controller.showConnected()
        app.controller.showRunning()
        for (state in listOf(
            ConnectionState.Reconnecting(address, 3),
            ConnectionState.Disconnected,
            ConnectionState.Connecting(address),
        )) {
            app.controller.connection.value = state
            assertTrue("$state", ui.showsLiveData)
        }
        // Paused, starting and key-removed are workouts too.
        for (status in listOf(BeltStatus.Paused, BeltStatus.Starting, BeltStatus.SafetyKeyRemoved)) {
            app.controller.showState { it.copy(status = status, speedKmh = 0.0) }
            app.controller.connection.value = ConnectionState.Disconnected
            assertTrue("$status", ui.showsLiveData)
        }
        // Back at rest: the home.
        app.controller.showState { it.copy(status = BeltStatus.Idle, speedKmh = 0.0) }
        assertFalse(ui.showsLiveData)
    }

    @Test fun aBeltStillSlowingDownCountsAsAWorkout() {
        app.controller.showConnected()
        app.controller.showRunning()
        app.controller.showState { it.copy(status = BeltStatus.Idle, speedKmh = 3.0) }
        app.controller.connection.value = ConnectionState.Disconnected
        assertTrue(ui.showsLiveData)
    }

    // ---- The indicator ----

    private fun indicator() = ui.treadmillIndicator

    @Test fun theIndicatorSaysNotSetUpWithoutARememberedTreadmillElseNotConnected() {
        assertEquals(TreadmillIndicator.NotSetUp, indicator())
        app = FakeApp(settings = InMemorySettingsStore(RememberedTreadmill(address, "REEBOK 0517")))
        assertEquals(TreadmillIndicator.NotConnected, indicator())
    }

    @Test fun theIndicatorFollowsTheConnection() {
        app.controller.showConnected()
        assertEquals(TreadmillIndicator.Connected, indicator())
        app.controller.showReconnecting()
        assertEquals(TreadmillIndicator.Reconnecting, indicator())
        app.controller.showBluetoothOff()
        assertEquals(TreadmillIndicator.WaitingForBluetooth, indicator())
        app.controller.connection.value = ConnectionState.Connecting(address)
        assertEquals(TreadmillIndicator.Connecting, indicator())
    }

    @Test fun theIndicatorSaysBluetoothOffWhenDisconnected() {
        app.controller.showBluetoothOff()
        assertEquals(TreadmillIndicator.BluetoothOff, indicator())
    }

    @Test fun aManualAttemptThatDidNotFindItSaysNotFound() {
        app = FakeApp(settings = InMemorySettingsStore(RememberedTreadmill(address, "REEBOK 0517")))
        app.controller.connectResult = CommandResult.Rejected(FailureCode.ConnectionFailed, "raw", ConnectionProblem.DeviceNotFound)
        app.viewModel.reconnectToRemembered()
        assertEquals(TreadmillIndicator.NotFound, indicator())
        // A new attempt that works clears it.
        app.controller.connectResult = CommandResult.Success
        app.viewModel.reconnectToRemembered()
        assertEquals(TreadmillIndicator.Connected, indicator())
    }

    @Test fun theAutomaticWindowShowsSearchingThenNotFound() {
        app = FakeApp(settings = InMemorySettingsStore(RememberedTreadmill(address, "REEBOK 0517")))
        app.controller.connectResult = CommandResult.Rejected(FailureCode.ConnectionFailed, "raw", ConnectionProblem.DeviceNotFound)
        app.viewModel.onPermissionsGranted()
        assertEquals(TreadmillIndicator.Searching, indicator())
    }
}
