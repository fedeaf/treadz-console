package com.treadz.console

import com.treadz.console.devices.StrapController
import com.treadz.console.fakes.FakeApp
import com.treadz.console.fakes.FakeHeartRateMonitor
import com.treadz.console.fakes.FakeTreadmillController
import com.treadz.console.fakes.InMemorySettingsStore
import com.treadz.console.settings.RememberedTreadmill
import com.treadz.console.treadmill.CommandResult
import com.treadz.console.treadmill.ConnectionProblem
import com.treadz.console.treadmill.FailureCode
import com.treadz.console.treadmill.control.Clock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestCoroutineScheduler
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
 * The quiet automatic connection, in virtual time: when the app opens (and each time it returns to the foreground while
 * the treadmill is not connected) it tries the remembered treadmill, again and again, for two minutes at most, then stops
 * without a word. Never a scan, never a problem, nothing after the user ended the connection, nothing without a
 * remembered treadmill.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class AutoConnectWindowTest {

    private val scheduler = TestCoroutineScheduler()
    private val address = FakeTreadmillController.TREADMILL_ADDRESS
    private val notFound = CommandResult.Rejected(FailureCode.ConnectionFailed, "raw", ConnectionProblem.DeviceNotFound)

    @Before fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher(scheduler))
    }

    @After fun tearDown() = Dispatchers.resetMain()

    private fun appWith(remembered: RememberedTreadmill? = RememberedTreadmill(address, "REEBOK 0517")) =
        FakeApp(settings = InMemorySettingsStore(remembered), mainClock = Clock { scheduler.currentTime }).also { it.viewModel }

    private fun FakeApp.opened() {
        controller.connectResult = notFound
        viewModel.onPermissionsGranted()
    }

    private fun advance(millis: Long) {
        scheduler.advanceTimeBy(millis)
        scheduler.runCurrent()
    }

    private val FakeApp.ui get() = viewModel.uiState.value

    @Test fun retriesEveryFewSecondsAndStopsAtTwoMinutes() {
        val app = appWith()
        app.opened()
        assertEquals(1, app.controller.connectCalls.size)
        assertTrue(app.ui.autoConnecting)
        assertEquals(TreadmillIndicator.Searching, app.ui.treadmillIndicator)

        advance(MainViewModel.RETRY_PAUSE_MILLIS)
        assertEquals(2, app.controller.connectCalls.size)

        advance(MainViewModel.WINDOW_MILLIS)
        val attempts = app.controller.connectCalls.size
        // One try every pause, the last one before the two minutes are over.
        assertEquals((MainViewModel.WINDOW_MILLIS / MainViewModel.RETRY_PAUSE_MILLIS).toInt(), attempts)
        assertFalse(app.ui.autoConnecting)
        assertTrue(app.ui.autoConnectGaveUp)
        assertEquals(TreadmillIndicator.NotFound, app.ui.treadmillIndicator)

        // And then nothing, silently.
        advance(10 * 60_000)
        assertEquals(attempts, app.controller.connectCalls.size)
        assertNull(app.ui.problem)
        assertFalse(app.ui.scanning)
        assertEquals(0, app.controller.scanCalls)
    }

    @Test fun connectsAsSoonAsTheTreadmillAnswersDuringTheWindow() {
        val app = appWith()
        app.opened()
        advance(30_000)
        assertFalse(app.ui.isConnected)
        app.controller.connectResult = CommandResult.Success
        advance(MainViewModel.RETRY_PAUSE_MILLIS)
        assertTrue(app.ui.isConnected)
        assertFalse(app.ui.autoConnecting)
        assertFalse(app.ui.autoConnectGaveUp)
        val attempts = app.controller.connectCalls.size
        advance(60_000)
        assertEquals(attempts, app.controller.connectCalls.size)
    }

    @Test fun aNewWindowStartsEachTimeTheAppReturnsToTheForegroundWhileNotConnected() {
        val app = appWith()
        app.opened()
        advance(MainViewModel.WINDOW_MILLIS + 5_000)
        val first = app.controller.connectCalls.size
        assertTrue(app.ui.autoConnectGaveUp)

        app.appLifecycle.setAppInForeground(false)
        advance(60_000)
        assertEquals("nothing is attempted in the background", first, app.controller.connectCalls.size)
        app.appLifecycle.setAppInForeground(true)
        assertEquals(first + 1, app.controller.connectCalls.size)
        assertTrue(app.ui.autoConnecting)
        assertFalse(app.ui.autoConnectGaveUp)

        advance(MainViewModel.WINDOW_MILLIS + 5_000)
        assertEquals(first + MainViewModel.WINDOW_MILLIS.toInt() / MainViewModel.RETRY_PAUSE_MILLIS.toInt(), app.controller.connectCalls.size)
        assertTrue(app.ui.autoConnectGaveUp)
    }

    @Test fun goingToTheBackgroundEndsTheWindowWithoutGivingUp() {
        val app = appWith()
        app.opened()
        app.appLifecycle.setAppInForeground(false)
        assertFalse(app.ui.autoConnecting)
        assertFalse(app.ui.autoConnectGaveUp)
    }

    @Test fun nothingIsAttemptedWithoutARememberedTreadmill() {
        val app = appWith(remembered = null)
        app.opened()
        advance(MainViewModel.WINDOW_MILLIS)
        assertTrue(app.controller.connectCalls.isEmpty())
        assertEquals(0, app.controller.scanCalls)
        assertEquals(TreadmillIndicator.NotSetUp, app.ui.treadmillIndicator)
        app.appLifecycle.setAppInForeground(false)
        app.appLifecycle.setAppInForeground(true)
        assertTrue(app.controller.connectCalls.isEmpty())
    }

    @Test fun nothingMoreAfterExit() {
        val app = appWith()
        app.opened()
        advance(10_000)
        val before = app.controller.connectCalls.size
        runBlocking { app.appLifecycle.exitApp() }
        // The strap is let go of too: Exit is the one place that happens.
        assertEquals(1, app.strapMonitor.releaseCalls)
        advance(MainViewModel.WINDOW_MILLIS)
        assertEquals(before, app.controller.connectCalls.size)
        app.appLifecycle.setAppInForeground(false)
        app.appLifecycle.setAppInForeground(true)
        assertEquals(before, app.controller.connectCalls.size)
        assertFalse(app.ui.autoConnecting)
    }

    @Test fun nothingMoreAfterForgettingTheTreadmill() {
        val app = appWith()
        app.opened()
        advance(10_000)
        val before = app.controller.connectCalls.size
        app.viewModel.forgetTreadmill()
        assertFalse(app.ui.autoConnecting)
        advance(MainViewModel.WINDOW_MILLIS)
        app.appLifecycle.setAppInForeground(false)
        app.appLifecycle.setAppInForeground(true)
        assertEquals(before, app.controller.connectCalls.size)
        assertEquals(TreadmillIndicator.NotSetUp, app.ui.treadmillIndicator)
    }

    @Test fun nothingMoreAfterStopReconnecting() {
        val app = appWith()
        app.controller.connectResult = CommandResult.Success
        app.viewModel.onPermissionsGranted()
        app.controller.showReconnecting(attempt = 4)
        app.viewModel.stopReconnecting()
        app.controller.connectResult = notFound
        val before = app.controller.connectCalls.size
        app.appLifecycle.setAppInForeground(false)
        app.appLifecycle.setAppInForeground(true)
        advance(MainViewModel.WINDOW_MILLIS)
        assertEquals(before, app.controller.connectCalls.size)
    }

    @Test fun aConnectionTheUserAsksForTakesOverFromTheWindow() {
        val app = appWith()
        app.opened()
        advance(10_000)
        app.controller.connectResult = CommandResult.Success
        app.viewModel.connect(address, "REEBOK 0517")
        assertTrue(app.ui.isConnected)
        assertFalse(app.ui.autoConnecting)
    }

    @Test fun waitsForBluetoothAndForPermissionsInsteadOfFailing() {
        val app = appWith()
        app.controller.connectResult = notFound
        app.controller.showBluetoothOff()
        app.viewModel.onPermissionsGranted()
        assertTrue(app.controller.connectCalls.isEmpty())
        assertEquals(TreadmillIndicator.BluetoothOff, app.ui.treadmillIndicator)
        app.controller.bluetoothEnabled.value = true
        assertEquals(1, app.controller.connectCalls.size)

        // Bluetooth off in the middle of a window: it stops, and starts again when Bluetooth is back.
        app.controller.bluetoothEnabled.value = false
        advance(30_000)
        assertEquals(1, app.controller.connectCalls.size)
        app.controller.bluetoothEnabled.value = true
        assertEquals(2, app.controller.connectCalls.size)
    }

    @Test fun noAttemptBeforeThePermissionsAreGranted() {
        val app = appWith()
        app.controller.connectResult = notFound
        advance(30_000)
        assertTrue(app.controller.connectCalls.isEmpty())
        app.viewModel.onPermissionsGranted()
        assertEquals(1, app.controller.connectCalls.size)
    }

    @Test fun theControllersOwnReconnectionIsLeftAlone() {
        val app = appWith()
        app.controller.connectResult = CommandResult.Success
        app.viewModel.onPermissionsGranted()
        assertTrue(app.ui.isConnected)
        app.controller.showReconnecting(attempt = 3)
        advance(MainViewModel.WINDOW_MILLIS)
        // The controller reconnects by itself while a session may be on: the window never interferes.
        assertEquals(1, app.controller.connectCalls.size)
        assertTrue(app.ui.isReconnecting)
    }

    // ---- The strap goes first ----

    private fun withUnstartedStrap(): Triple<FakeApp, StrapController, MainViewModel> {
        val app = FakeApp(settings = InMemorySettingsStore(RememberedTreadmill(address, "REEBOK 0517")), mainClock = Clock { scheduler.currentTime })
        val id = runBlocking { app.userData.profiles.activeUserId()!! }
        runBlocking { app.userData.profiles.setStrap(id, FakeHeartRateMonitor.STRAP_ADDRESS, FakeHeartRateMonitor.STRAP_NAME) }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined)
        val strap = StrapController(app.strapMonitor, app.heartRate, app.controller.state, app.userData.profiles, app.settings, scope)
        val vm = MainViewModel(app.controller, app.recorder, app.settings, app.userData, app.appLifecycle, app.sync, app.training, strap, Clock { scheduler.currentTime })
        app.controller.connectResult = notFound
        return Triple(app, strap, vm)
    }

    @Test fun theTreadmillIsTriedOnlyAfterTheStrapWasHandedToTheMonitor() {
        val (app, strap, vm) = withUnstartedStrap()
        vm.onPermissionsGranted()
        assertTrue("the console must not take the strap first", app.controller.connectCalls.isEmpty())
        assertTrue(app.strapMonitor.useCalls.isEmpty())

        strap.start()

        assertEquals(listOf(FakeHeartRateMonitor.STRAP_ADDRESS), app.strapMonitor.useCalls)
        assertEquals(1, app.controller.connectCalls.size)
    }

    @Test fun aStrapThatNeverStartsDoesNotHoldTheTreadmillBackForLong() {
        val (app, _, vm) = withUnstartedStrap()
        vm.onPermissionsGranted()
        assertTrue(app.controller.connectCalls.isEmpty())
        advance(MainViewModel.STRAP_WAIT_MILLIS)
        assertEquals(1, app.controller.connectCalls.size)
    }
}
