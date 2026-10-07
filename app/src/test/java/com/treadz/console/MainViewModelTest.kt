package com.treadz.console

import com.treadz.console.fakes.FakeApp
import com.treadz.console.fakes.FakeTreadmillController
import com.treadz.console.fakes.FakeWorkoutRecorder
import com.treadz.console.fakes.InMemorySettingsStore
import com.treadz.console.fakes.createProfile
import com.treadz.console.settings.RememberedTreadmill
import com.treadz.console.treadmill.BeltStatus
import com.treadz.console.treadmill.CommandResult
import com.treadz.console.treadmill.FailureCode
import com.treadz.console.treadmill.ConnectionState
import com.treadz.console.treadmill.RecordingStatus
import com.treadz.console.treadmill.TreadmillEvent
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

@OptIn(ExperimentalCoroutinesApi::class)
class MainViewModelTest {

    @Before fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        vm // creates the ViewModel right away, as when the app opens
    }

    @After fun tearDown() = Dispatchers.resetMain()

    private val app = FakeApp()
    private val controller get() = app.controller
    private val vm get() = app.viewModel
    private val ui get() = vm.uiState.value

    @Test fun startOnlyWhenConnectedAndIdle() {
        assertFalse(ui.canStart)
        controller.showConnected()
        assertTrue(ui.canStart)
        controller.showState { it.copy(status = BeltStatus.Running) }
        assertFalse(ui.canStart)
        controller.showState { it.copy(status = BeltStatus.Idle) }
        controller.showReconnecting()
        assertFalse(ui.canStart)
    }

    @Test fun startCallsControllerAndReportsFailure() {
        controller.showConnected()
        controller.startResult = CommandResult.NotVerified(FailureCode.NotStarted, "belt did not move")
        vm.start()
        assertEquals(1, controller.startCalls)
        assertEquals(CommandResult.NotVerified(FailureCode.NotStarted, "belt did not move"), ui.startFailure)
        assertFalse(ui.startInProgress)
    }

    @Test fun startFailureClearsWhenBeltStarts() {
        controller.showConnected()
        controller.startResult = CommandResult.Rejected(FailureCode.RejectedByTreadmill, "busy")
        vm.start()
        assertNotNull(ui.startFailure)
        controller.showState { it.copy(status = BeltStatus.Starting) }
        assertNull(ui.startFailure)
    }

    @Test fun startIgnoredWhenNotAllowed() {
        vm.start()
        assertEquals(0, controller.startCalls)
    }

    @Test fun startInProgressDisablesButton() = runTest {
        controller.showConnected()
        val gate = CompletableDeferred<CommandResult>()
        // A slow start is simulated by replacing the result after the wait.
        val slow = object : com.treadz.console.treadmill.TreadmillController by controller {
            override suspend fun start(): CommandResult = gate.await()
        }
        val slowVm = MainViewModel(slow, app.recorder, app.settings, app.userData)
        slowVm.start()
        assertTrue(slowVm.uiState.value.startInProgress)
        assertFalse(slowVm.uiState.value.canStart)
        gate.complete(CommandResult.Success)
        assertFalse(slowVm.uiState.value.startInProgress)
    }

    @Test fun safetyEventsRaiseAlerts() {
        controller.emit(TreadmillEvent.ConnectionLost)
        assertEquals(TreadmillEvent.ConnectionLost, ui.alert)
        controller.emit(TreadmillEvent.DataStalled)
        assertEquals(TreadmillEvent.DataStalled, ui.alert)
        controller.emit(TreadmillEvent.CommandNotExecuted("stop"))
        assertEquals(TreadmillEvent.CommandNotExecuted("stop"), ui.alert)
        vm.dismissAlert()
        assertEquals(TreadmillEvent.DataStalled, ui.alert)
        vm.dismissAlert()
        vm.dismissAlert()
        assertNull(ui.alert)
    }

    @Test fun manualChangeHasNoAlert() {
        controller.emit(TreadmillEvent.ManualChange(speedKmh = 9.0, inclinePercent = null))
        assertNull(ui.alert)
    }

    @Test fun alertRaisedBeforeTheScreenExistsIsStillShown() {
        // The alert happened with nobody listening to events (e.g. with the app in the background).
        controller.emit(TreadmillEvent.ConnectionLost)
        val lateVm = MainViewModel(controller, app.recorder, app.settings, app.userData)
        assertEquals(TreadmillEvent.ConnectionLost, lateVm.uiState.value.alert)
    }

    @Test fun dismissingAnAlertAcknowledgesItInTheController() {
        controller.emit(TreadmillEvent.SafetyKeyRemoved)
        assertEquals(TreadmillEvent.SafetyKeyRemoved, ui.alert)
        vm.dismissAlert()
        assertNull(ui.alert)
        assertTrue(controller.pendingAlerts.value.isEmpty())
    }

    @Test fun keyRestoredEventClearsKeyAlert() {
        controller.emit(TreadmillEvent.SafetyKeyRemoved)
        assertEquals(TreadmillEvent.SafetyKeyRemoved, ui.alert)
        controller.emit(TreadmillEvent.SafetyKeyRestored)
        assertNull(ui.alert)
    }

    @Test fun keepScreenOnWhileBeltNotIdle() {
        assertFalse(ui.keepScreenOn)
        controller.showState { it.copy(status = BeltStatus.Starting) }
        assertTrue(ui.keepScreenOn)
        controller.showState { it.copy(status = BeltStatus.Paused) }
        assertTrue(ui.keepScreenOn)
        controller.showState { it.copy(status = BeltStatus.Idle) }
        assertFalse(ui.keepScreenOn)
    }

    @Test fun rememberedTreadmillIsSavedOnConnect() {
        controller.scanResults = FakeTreadmillController.SAMPLE_DEVICES
        vm.connect(FakeTreadmillController.TREADMILL_ADDRESS, FakeTreadmillController.TREADMILL_NAME)
        assertEquals(
            RememberedTreadmill(FakeTreadmillController.TREADMILL_ADDRESS, FakeTreadmillController.TREADMILL_NAME),
            app.settings.lastTreadmill,
        )
        assertTrue(ui.isConnected)
    }

    @Test fun reconnectsAutomaticallyToRememberedTreadmill() {
        val app = FakeApp(settings = InMemorySettingsStore(RememberedTreadmill("AA:BB", "REEBOK 0517")))
        app.viewModel.onPermissionsGranted()
        assertEquals(listOf("AA:BB"), app.controller.connectCalls)
        // Only once, even if notified again.
        app.viewModel.onPermissionsGranted()
        assertEquals(1, app.controller.connectCalls.size)
    }

    @Test fun failedAutoReconnectFallsBackToScan() {
        val app = FakeApp(settings = InMemorySettingsStore(RememberedTreadmill("AA:BB", "REEBOK 0517")))
        app.controller.connectResult = CommandResult.NotConnected
        app.controller.scanResults = FakeTreadmillController.SAMPLE_DEVICES
        app.viewModel.onPermissionsGranted()
        assertEquals(CommandResult.NotConnected, app.viewModel.uiState.value.connectFailure)
        assertTrue(app.viewModel.uiState.value.scanning)
        assertEquals(3, app.viewModel.uiState.value.devices.size)
    }

    @Test fun scansWhenNoRememberedTreadmill() {
        controller.scanResults = FakeTreadmillController.SAMPLE_DEVICES
        vm.onPermissionsGranted()
        assertTrue(ui.scanning)
        // Sorted by signal, strongest first.
        assertEquals(listOf(-48, -71, -86), ui.devices.map { it.rssi })
        vm.stopScan()
        assertFalse(ui.scanning)
    }

    @Test fun scanErrorIsReported() {
        controller.scanError = IllegalStateException("Bluetooth is off")
        vm.startScan()
        assertEquals("Bluetooth is off", ui.scanFailure)
        assertFalse(ui.scanning)
    }

    @Test fun recordingNoticeCanBeDismissed() {
        app.recorder.showRecording()
        assertNull(ui.recordingNotice)
        app.recorder.showSaved()
        assertTrue(ui.recordingNotice is RecordingStatus.Saved)
        vm.dismissRecordingNotice()
        assertNull(ui.recordingNotice)
        // A later recording shows its notice again.
        app.recorder.showRecording()
        app.recorder.status.value = RecordingStatus.Saved(FakeWorkoutRecorder.INFO.copy(fileName = "other.jsonl"), "Download/x")
        assertNotNull(ui.recordingNotice)
    }

    @Test fun darkThemeIsDefaultAndPersists() {
        assertTrue(ui.darkTheme)
        vm.setDarkTheme(false)
        assertFalse(ui.darkTheme)
        assertFalse(app.settings.darkTheme)
    }

    @Test fun neverSendsAnyOtherCommand() {
        controller.showConnected()
        vm.start()
        vm.startScan()
        vm.stopScan()
        assertTrue(controller.forbiddenCalls.isEmpty())
    }

    @Test fun connectionStateIsExposed() {
        controller.showReconnecting(attempt = 3)
        assertEquals(ConnectionState.Reconnecting(FakeTreadmillController.TREADMILL_ADDRESS, 3), ui.connection)
        assertTrue(ui.showsLiveData)
    }

    // ---- Milestone 2: users ----

    @Test fun freshInstallStartsWithTheGenericUserSoStartIsPossible() {
        controller.showConnected()
        assertTrue(ui.activeUser!!.isGeneric)
        assertTrue(ui.canStart)
    }

    @Test fun startIsDisabledWithoutAnActiveUser() = runTest {
        controller.showConnected()
        app.userData.profiles.setActiveUser(null)
        assertNull(ui.activeUser)
        assertFalse(ui.canStart)
        vm.start()
        assertEquals(0, controller.startCalls)
        app.userData.profiles.setActiveUser(app.userData.profiles.profiles.first().first().id)
        assertTrue(ui.canStart)
    }

    @Test fun deletingTheActiveUserDisablesStart() = runTest {
        val ana = app.userData.createProfile("Ana")
        app.userData.profiles.setActiveUser(ana.id)
        controller.showConnected()
        assertTrue(ui.canStart)
        app.userData.profiles.delete(ana.id)
        assertFalse(ui.canStart)
    }

    @Test fun theUserCanOnlyBeSwitchedWhileTheBeltIsStopped() {
        controller.showConnected()
        assertTrue(ui.canSwitchUser)
        for (status in listOf(BeltStatus.Starting, BeltStatus.Running, BeltStatus.Paused, BeltStatus.SafetyKeyRemoved)) {
            controller.showState { it.copy(status = status) }
            assertFalse("$status", ui.canSwitchUser)
        }
        controller.showState { it.copy(status = BeltStatus.Idle) }
        assertTrue(ui.canSwitchUser)
    }

    @Test fun unassignedWorkoutsAreCounted() {
        assertEquals(0, ui.unassignedWorkoutCount)
        app.userData.addWorkout(null, com.treadz.console.userdata.WorkoutStats(durationSeconds = 5), emptyList(), 1_000)
        assertEquals(1, ui.unassignedWorkoutCount)
    }
}
