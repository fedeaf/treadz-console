package com.treadz.console

import com.treadz.console.fakes.FakeApp
import com.treadz.console.fakes.FakeTreadmillController
import com.treadz.console.fakes.InMemorySettingsStore
import com.treadz.console.settings.RememberedTreadmill
import com.treadz.console.treadmill.CommandResult
import com.treadz.console.treadmill.ConnectionState
import com.treadz.console.treadmill.FailureCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
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
 * Coming back to the app after the connection ended by itself (the backend lets go of an idle treadmill after a while
 * in the background): it reconnects like at app start. Not after the user ended the connection, and a connection attempt
 * refused because the app is in the background is never an error.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ForegroundReconnectTest {

    @Before fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
    }

    @After fun tearDown() = Dispatchers.resetMain()

    private val address = FakeTreadmillController.TREADMILL_ADDRESS
    private val app = FakeApp(settings = InMemorySettingsStore(RememberedTreadmill(address, "REEBOK 0517")))
    private val controller get() = app.controller
    private val lifecycle get() = app.appLifecycle
    private val ui get() = app.viewModel.uiState.value

    /** The app opened, got its permissions and connected to the remembered treadmill by itself. */
    private fun connectedAtStart() {
        app.viewModel.onPermissionsGranted()
        assertTrue(ui.isConnected)
        assertEquals(1, controller.connectCalls.size)
    }

    private fun goToBackground() = lifecycle.setAppInForeground(false)

    private fun comeBack() = lifecycle.setAppInForeground(true)

    /** The backend's idle disconnect: the connection ends without the app asking. */
    private fun backendDisconnectsIdleTreadmill() {
        controller.connection.value = ConnectionState.Disconnected
    }

    @Test fun reconnectsOnReturnAfterTheAutomaticDisconnect() {
        connectedAtStart()
        goToBackground()
        backendDisconnectsIdleTreadmill()
        assertEquals(1, controller.connectCalls.size) // nothing in the background

        comeBack()

        assertEquals(listOf(address, address), controller.connectCalls)
        assertTrue(ui.isConnected)
        assertNull(ui.problem)
    }

    @Test fun reconnectsOnlyOncePerDisconnection() {
        connectedAtStart()
        goToBackground()
        backendDisconnectsIdleTreadmill()
        comeBack()
        goToBackground()
        comeBack()
        assertEquals(2, controller.connectCalls.size)
    }

    @Test fun doesNotReconnectAfterStopReconnecting() {
        connectedAtStart()
        controller.showReconnecting(attempt = 5)
        app.viewModel.stopReconnecting()
        goToBackground()
        comeBack()
        assertEquals(1, controller.connectCalls.size)
        assertFalse(ui.isConnected)
    }

    @Test fun doesNotReconnectAfterExit() {
        connectedAtStart()
        runBlocking { lifecycle.exitApp() }
        assertEquals(1, controller.disconnectCalls)
        goToBackground()
        comeBack()
        assertEquals(1, controller.connectCalls.size)
    }

    @Test fun doesNotReconnectAfterExitFromTheNotification() {
        connectedAtStart()
        backendDisconnectsIdleTreadmill()
        lifecycle.requestExitFromNotification()
        goToBackground()
        comeBack()
        assertEquals(1, controller.connectCalls.size)
    }

    @Test fun reconnectsAgainIfTheUserConnectedAgainAfterEnding() {
        connectedAtStart()
        controller.showReconnecting()
        app.viewModel.stopReconnecting()
        app.viewModel.connect(address, "REEBOK 0517")
        assertEquals(2, controller.connectCalls.size)
        goToBackground()
        backendDisconnectsIdleTreadmill()
        comeBack()
        assertEquals(3, controller.connectCalls.size)
    }

    @Test fun aNewWindowStartsOnEveryReturnEvenIfTheLastOneFoundNothing() {
        controller.connectResult = CommandResult.Rejected(FailureCode.ConnectionFailed, "raw")
        app.viewModel.onPermissionsGranted()
        assertEquals(1, controller.connectCalls.size)
        goToBackground()
        assertFalse(ui.autoConnecting) // nothing is attempted in the background
        comeBack()
        assertEquals(2, controller.connectCalls.size)
        assertNull(ui.problem)
    }

    @Test fun waitsForBluetoothWhenItIsOffOnReturn() {
        connectedAtStart()
        goToBackground()
        backendDisconnectsIdleTreadmill()
        controller.showBluetoothOff()
        comeBack()
        assertEquals(1, controller.connectCalls.size)
        assertNull(ui.problem)

        controller.bluetoothEnabled.value = true

        assertEquals(2, controller.connectCalls.size)
        assertTrue(ui.isConnected)
    }

    @Test fun waitsForPermissionsBeforeReconnecting() {
        // Opened without permissions: nothing to resume yet, and nothing attempted on returning.
        goToBackground()
        comeBack()
        assertTrue(controller.connectCalls.isEmpty())
    }

    // ---- A connection refused in the background is not an error ----

    @Test fun aConnectRefusedInTheBackgroundShowsNoErrorAndTriesAgainOnReturn() {
        lifecycle.setAppInForeground(false)
        controller.connectResult = CommandResult.NotAllowed(FailureCode.ConnectionFailed, "The app is in the background")

        app.viewModel.onPermissionsGranted()

        // Nothing is even attempted while the app is away.
        assertEquals(0, controller.connectCalls.size)
        assertNull(ui.problem)
        assertEquals(0, controller.scanCalls) // no "treadmill not found" scan either
        assertFalse(ui.scanning)

        controller.connectResult = CommandResult.Success
        comeBack()

        assertEquals(1, controller.connectCalls.size)
        assertTrue(ui.isConnected)
    }

    @Test fun anAttemptAbandonedBecauseTheAppWentToTheBackgroundShowsNoError() {
        lifecycle.setAppInForeground(false)
        controller.connectResult = CommandResult.NotConnected

        app.viewModel.onPermissionsGranted()

        assertNull(ui.problem)
        assertEquals(0, controller.scanCalls)
    }

    @Test fun aManualConnectRefusedInTheBackgroundShowsNoError() {
        lifecycle.setAppInForeground(false)
        controller.connectResult = CommandResult.NotAllowed(FailureCode.ConnectionFailed, "The app is in the background")
        app.viewModel.connect(address, "REEBOK 0517")
        assertNull(ui.problem)
    }

    @Test fun realConnectionFailuresInTheForegroundAreStillShown() {
        controller.connectResult = CommandResult.Rejected(
            FailureCode.ConnectionFailed,
            "raw",
            com.treadz.console.treadmill.ConnectionProblem.DeviceNotFound,
        )
        app.viewModel.connect(address, "REEBOK 0517")
        assertEquals(com.treadz.console.treadmill.ConnectionProblem.DeviceNotFound, ui.problem)
    }
}
