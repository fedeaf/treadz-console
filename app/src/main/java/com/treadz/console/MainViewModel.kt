package com.treadz.console

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.treadz.console.controls.ControllerCommands
import com.treadz.console.controls.ControlsState
import com.treadz.console.controls.ManualControls
import com.treadz.console.controls.SessionControls
import com.treadz.console.devices.LiveHeartRate
import com.treadz.console.devices.StrapController
import com.treadz.console.devices.StrapUiState
import com.treadz.console.programs.fitness.HeartRateSourceKind
import com.treadz.console.settings.RememberedTreadmill
import com.treadz.console.settings.SettingsStore
import com.treadz.console.sync.SyncError
import com.treadz.console.sync.service.SyncService
import com.treadz.console.sync.service.SyncSetup
import com.treadz.console.training.FinishedWorkout
import com.treadz.console.training.TrainingCoordinator
import com.treadz.console.treadmill.BeltStatus
import com.treadz.console.treadmill.CommandResult
import com.treadz.console.treadmill.ConnectionProblem
import com.treadz.console.treadmill.ConnectionState
import com.treadz.console.treadmill.DiscoveredTreadmill
import com.treadz.console.treadmill.RecordingStatus
import com.treadz.console.treadmill.TreadmillController
import com.treadz.console.treadmill.WorkoutRecorder
import com.treadz.console.treadmill.control.CommandOrigin
import com.treadz.console.treadmill.ftms.TransportException
import com.treadz.console.treadmill.control.Clock
import com.treadz.console.treadmill.lifecycle.AppLifecycle
import com.treadz.console.userdata.UserData
import com.treadz.console.userdata.UserProfile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Observes the [TreadmillController] and the [WorkoutRecorder] and exposes a single [MainUiState].
 * Treadmill commands: `scan()`, `connect()` and `start()` here, and the manual controls (pause, resume, STOP, speed and
 * incline) through [controls]. Everything but STOP needs an active user.
 *
 * **Quiet automatic connection.** When the app opens, and each time it comes back to the foreground while the treadmill
 * is not connected (unless the user ended the connection on purpose: Exit, "Stop reconnecting"), it tries to connect to
 * the remembered treadmill, again and again, for at most [WINDOW_MILLIS]; then it stops silently (the state says "not
 * found" and Devices offers Connect). It never starts a scan by itself, never raises a problem outside Devices, and does
 * nothing without a remembered treadmill. The controller's own reconnection during a session is not touched.
 */
class MainViewModel(
    private val controller: TreadmillController,
    private val recorder: WorkoutRecorder,
    private val settings: SettingsStore,
    private val userData: UserData,
    private val appLifecycle: AppLifecycle,
    /** Only used to show a discreet error indicator on the settings button; `null` when there is no synchronisation. */
    sync: SyncService? = null,
    /**
     * Routes the manual controls through the program / fitness test runner while one is active; `null` sends every
     * command straight to the controller.
     */
    private val training: TrainingCoordinator? = null,
    /** The heart-rate strap of the active user; started before the treadmill is touched. `null` without a strap. */
    private val strap: StrapController? = null,
    /** Time of the automatic connection window. */
    private val clock: Clock = Clock.System,
) : ViewModel() {

    /** Part of the state that only lives in the UI. */
    private data class Local(
        val scanning: Boolean = false,
        val scanFinished: Boolean = false,
        val devices: List<DiscoveredTreadmill> = emptyList(),
        val remembered: RememberedTreadmill? = null,
        /** Why the last scan or connection started from this screen failed. */
        val problem: ConnectionProblem? = null,
        val startInProgress: Boolean = false,
        val startFailure: CommandResult? = null,
        val dismissedRecording: RecordingStatus? = null,
        val darkTheme: Boolean = true,
        val autoConnecting: Boolean = false,
        val autoConnectGaveUp: Boolean = false,
    )

    private val local = MutableStateFlow(
        Local(remembered = settings.lastTreadmill, darkTheme = settings.darkTheme),
    )

    /** The manual controls of the live screen (and the STOP of every screen). */
    val controls = ManualControls(
        controller,
        viewModelScope,
        canOperate = { uiState.value.activeUser != null },
        commands = training?.commands ?: ControllerCommands(controller),
        session = { training?.controls?.value ?: SessionControls() },
    )

    private val controlsAndSession = combine(controls.state, training?.controls ?: flowOf(SessionControls())) { c, s -> c to s }

    /** The error of the last synchronisation attempt, only while the device is linked. */
    private val syncError: Flow<SyncError?> =
        sync?.status?.map { status -> status.error.takeIf { status.setup == SyncSetup.Linked } }?.distinctUntilChanged()
            ?: flowOf(null)

    private data class People(
        val user: UserProfile?,
        val unassigned: Int,
        val syncError: SyncError?,
        val finishedWorkout: FinishedWorkout?,
        val strap: StrapUiState,
    )

    private val people = combine(
        userData.profiles.activeUser,
        userData.workouts.unassignedWorkouts().map { it.size },
        syncError,
        training?.finishedWorkout ?: flowOf(null),
        strap?.state ?: flowOf(StrapUiState()),
    ) { user, unassigned, syncError, finished, strap -> People(user, unassigned, syncError, finished, strap) }

    /** Everything the state needs besides the controller's connection, treadmill, recording and alerts. */
    private data class Extras(
        val local: Local,
        val user: UserProfile?,
        val unassigned: Int,
        val syncError: SyncError?,
        val controls: ControlsState,
        val session: SessionControls,
        val finishedWorkout: FinishedWorkout?,
        val bluetoothEnabled: Boolean,
        val reconnectProblem: ConnectionProblem?,
        val strap: StrapUiState,
    )

    private val extras = combine(
        local,
        people,
        controlsAndSession,
        controller.bluetoothEnabled,
        controller.connectionProblem,
    ) { local, people, controls, bluetooth, reconnectProblem ->
        Extras(local, people.user, people.unassigned, people.syncError, controls.first, controls.second, people.finishedWorkout, bluetooth, reconnectProblem, people.strap)
    }

    val uiState: StateFlow<MainUiState> = combine(
        controller.connection,
        controller.state,
        recorder.status,
        controller.pendingAlerts,
        extras,
    ) { connection, treadmill, recording, alerts, extras ->
        val local = extras.local
        MainUiState(
            connection = connection,
            treadmill = treadmill,
            scanning = local.scanning,
            scanFinished = local.scanFinished,
            devices = local.devices,
            rememberedName = local.remembered?.name,
            rememberedAddress = local.remembered?.address,
            problem = local.problem,
            bluetoothEnabled = extras.bluetoothEnabled,
            reconnectProblem = extras.reconnectProblem,
            startInProgress = local.startInProgress,
            startFailure = local.startFailure,
            alert = alerts.lastOrNull(),
            recording = recording,
            recordingNotice = recording.takeIf {
                (it is RecordingStatus.Saved || it is RecordingStatus.Failed) && it != local.dismissedRecording
            },
            recordingsEnabled = recorder.enabled,
            darkTheme = local.darkTheme,
            activeUser = extras.user,
            unassignedWorkoutCount = extras.unassigned,
            syncError = extras.syncError,
            controls = extras.controls,
            session = extras.session,
            finishedWorkout = extras.finishedWorkout,
            autoConnecting = local.autoConnecting,
            autoConnectGaveUp = local.autoConnectGaveUp,
            // Without a strap controller the heart rate is the console's, as before.
            strap = if (strap != null) extras.strap else StrapUiState(
                heartRate = LiveHeartRate(treadmill.heartRateBpm, if (treadmill.heartRateBpm != null) HeartRateSourceKind.TreadmillConsole else null),
            ),
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, MainUiState(darkTheme = local.value.darkTheme, recordingsEnabled = recorder.enabled))

    private var scanJob: Job? = null
    private var connectJob: Job? = null
    private var permissionsGranted = false

    /** The automatic connection window that is running, if any, and when it started. */
    private var windowJob: Job? = null
    private var windowStartedAt = 0L

    /**
     * An automatic connection is due: the app has just opened, came back to the foreground, or an earlier try had to
     * wait for something (permissions, Bluetooth, the foreground). Cleared when a window starts.
     */
    private var pendingAutoConnect = true

    /** The user closed the connection (Exit, "Stop reconnecting"): nothing to resume until they connect again. */
    private var userEndedConnection = false

    /** The name the user picked from the scan list: Android gives no name when it connects by address alone. */
    private var pickedName: Pair<String, String>? = null

    init {
        // Remember the last treadmill connected to and clear old connection errors. A name is never overwritten with
        // nothing: Android gives `null` when it reconnects by itself.
        viewModelScope.launch {
            controller.connection.collect { connection ->
                if (connection is ConnectionState.Connected) {
                    val name = connection.name
                        ?: pickedName?.takeIf { it.first == connection.address }?.second
                        ?: local.value.remembered?.takeIf { it.address == connection.address }?.name
                    saveRemembered(RememberedTreadmill(connection.address, name))
                    local.update { it.copy(problem = null) }
                }
            }
        }

        // A Start failure stops being relevant as soon as the belt leaves Idle.
        viewModelScope.launch {
            controller.state.map { it.status }.distinctUntilChanged().collect { status ->
                if (status != BeltStatus.Idle) local.update { it.copy(startFailure = null) }
            }
        }

        // Tells apart a connection that ended by itself (to connect again when the user is back) from one the user ended.
        // It only notes it: whatever starts the new window (foreground, Bluetooth) decides when.
        viewModelScope.launch {
            var previous = controller.connection.value
            controller.connection.collect { connection ->
                when (connection) {
                    is ConnectionState.Connecting, is ConnectionState.Connected -> {
                        pendingAutoConnect = false
                        userEndedConnection = false
                        if (connection is ConnectionState.Connected) local.update { it.copy(autoConnectGaveUp = false) }
                    }
                    ConnectionState.Disconnected ->
                        if ((previous is ConnectionState.Connected || previous is ConnectionState.Reconnecting) && !userEndedConnection) {
                            pendingAutoConnect = true
                        }
                    is ConnectionState.Reconnecting -> {}
                }
                previous = connection
            }
        }

        // Exit (from the Back dialog or the notification) disconnects on purpose: the app is not to reconnect, and the
        // strap, which is only let go of here, is released.
        viewModelScope.launch {
            appLifecycle.exitRequests.collect {
                userEnded()
                strap?.release()
            }
        }

        // Back in the foreground: a new window to find the treadmill. Away: nothing is attempted in the background (the
        // running window ends).
        viewModelScope.launch {
            appLifecycle.appInForeground.collect { foreground ->
                if (foreground) {
                    strap?.onForeground()
                    pendingAutoConnect = true
                    evaluateAutoConnect()
                } else {
                    windowJob?.cancel()
                }
            }
        }

        // Bluetooth off: nothing is scanned or attempted. Back on: forget the old "Bluetooth is off" error and do the
        // automatic connection that was waiting for it.
        viewModelScope.launch {
            controller.bluetoothEnabled.collect { enabled ->
                if (enabled) {
                    local.update { if (it.problem == ConnectionProblem.BluetoothOff) it.copy(problem = null) else it }
                    evaluateAutoConnect()
                } else {
                    stopScan()
                    if (windowJob?.isActive == true) {
                        windowJob?.cancel()
                        pendingAutoConnect = true
                    }
                }
            }
        }
    }

    private fun saveRemembered(treadmill: RememberedTreadmill) {
        settings.lastTreadmill = treadmill
        local.update { it.copy(remembered = treadmill) }
    }

    /** Called once the app has the Bluetooth permissions: the automatic connection and the strap's pairing question may go. */
    fun onPermissionsGranted() {
        permissionsGranted = true
        strap?.onPermissionsGranted()
        evaluateAutoConnect()
    }

    /**
     * Starts the automatic connection window if one is due and nothing is missing: permissions, Bluetooth, the app in
     * the foreground, a remembered treadmill, no connection yet and no decision of the user to stay disconnected.
     * Whatever is missing, it is tried again when it arrives.
     */
    private fun evaluateAutoConnect() {
        if (!pendingAutoConnect || !permissionsGranted || userEndedConnection) return
        if (!appLifecycle.appInForeground.value || !controller.bluetoothEnabled.value) return
        if (local.value.remembered == null) {
            pendingAutoConnect = false
            return
        }
        if (windowJob?.isActive == true || controller.connection.value !is ConnectionState.Disconnected) return
        pendingAutoConnect = false
        windowStartedAt = clock.nowMillis()
        local.update { it.copy(autoConnecting = true, autoConnectGaveUp = false) }
        windowJob = viewModelScope.launch {
            try {
                runWindow()
            } finally {
                local.update { it.copy(autoConnecting = false) }
            }
        }
    }

    private fun windowExpired() = clock.nowMillis() - windowStartedAt >= WINDOW_MILLIS

    /** Tries the remembered treadmill until it answers or the window is over. Nothing here is ever shown as an error. */
    private suspend fun runWindow() {
        // The user's strap first: the console must not take it before the app has looked for it.
        strap?.let { withTimeoutOrNull(STRAP_WAIT_MILLIS) { it.awaitStarted() } }
        while (true) {
            val remembered = local.value.remembered ?: return
            if (userEndedConnection || controller.connection.value !is ConnectionState.Disconnected) return
            if (!appLifecycle.appInForeground.value || !controller.bluetoothEnabled.value) {
                pendingAutoConnect = true
                return
            }
            pickedName = remembered.name?.let { remembered.address to it } ?: pickedName
            when (val result = controller.connect(remembered.address)) {
                is CommandResult.Success -> return
                // Refused or abandoned because the app is in the background (or Bluetooth is off): the next window
                // takes over when that changes.
                is CommandResult.NotAllowed, CommandResult.NotConnected -> {
                    pendingAutoConnect = true
                    return
                }
                else -> if ((result as? CommandResult.Failure)?.problem == ConnectionProblem.BluetoothOff) {
                    pendingAutoConnect = true
                    return
                }
            }
            if (windowExpired()) break
            delay(RETRY_PAUSE_MILLIS)
            if (windowExpired()) break
        }
        local.update { it.copy(autoConnectGaveUp = true) }
    }

    private fun userEnded() {
        userEndedConnection = true
        pendingAutoConnect = false
        windowJob?.cancel()
    }

    fun startScan() = startScan(keepProblem = false)

    private fun startScan(keepProblem: Boolean) {
        if (scanJob?.isActive == true || !controller.bluetoothEnabled.value) return
        windowJob?.cancel()
        local.update {
            it.copy(
                scanning = true,
                scanFinished = false,
                devices = emptyList(),
                problem = if (keepProblem) it.problem else null,
            )
        }
        scanJob = viewModelScope.launch {
            try {
                withTimeoutOrNull(SCAN_DURATION_MILLIS) {
                    controller.scan().collect { found ->
                        updateRememberedName(found)
                        local.update { state ->
                            val others = state.devices.filterNot { it.address == found.address }
                            state.copy(devices = (others + found).sortedByDescending { it.rssi })
                        }
                    }
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Never the exception's own text: it is English and only meant for the log.
                local.update { it.copy(problem = (e as? TransportException)?.problem ?: ConnectionProblem.Other) }
            } finally {
                local.update { it.copy(scanning = false, scanFinished = true) }
            }
        }
    }

    /** The treadmill we remember showed up with a name: keep it (old versions saved it without one). */
    private fun updateRememberedName(found: DiscoveredTreadmill) {
        val remembered = local.value.remembered ?: return
        if (found.address == remembered.address && found.name != null && found.name != remembered.name) {
            saveRemembered(remembered.copy(name = found.name))
        }
    }

    fun stopScan() {
        scanJob?.cancel()
    }

    /** A connection the user asked for (Devices): unlike the automatic one, its failure is explained. */
    fun connect(address: String, name: String?) {
        if (connectJob?.isActive == true || !controller.bluetoothEnabled.value) return
        stopScan()
        windowJob?.cancel()
        if (name != null) pickedName = address to name
        local.update { it.copy(problem = null, autoConnectGaveUp = false) }
        connectJob = viewModelScope.launch {
            val result = controller.connect(address)
            val inBackground = !appLifecycle.appInForeground.value
            when {
                result is CommandResult.Success -> {}
                // Refused, or abandoned, because the app is in the background: not an error to show, and nothing to
                // fall back to. If the app was away, try again when it is back.
                result is CommandResult.NotAllowed || (result is CommandResult.NotConnected && inBackground) -> {
                    if (inBackground && !userEndedConnection) pendingAutoConnect = true
                }
                else -> {
                    val problem = (result as? CommandResult.Failure)?.problem ?: ConnectionProblem.Other
                    local.update { it.copy(problem = problem) }
                }
            }
        }
    }

    fun reconnectToRemembered() {
        local.value.remembered?.let { connect(it.address, it.name) }
    }

    /**
     * The way out of "Reconnecting": gives up the automatic reconnection (the controller leaves it at once) and
     * starts looking for treadmills. Only while reconnecting: closing a live connection would stop the belt.
     */
    fun stopReconnecting() {
        if (controller.connection.value !is ConnectionState.Reconnecting) return
        userEnded()
        viewModelScope.launch {
            controller.disconnect()
            local.update { it.copy(problem = null) }
            startScan()
        }
    }

    fun start() {
        if (!uiState.value.canStart) return
        local.update { it.copy(startInProgress = true, startFailure = null) }
        viewModelScope.launch {
            val result = try {
                withContext(CommandOrigin.App) { controller.start() }
            } finally {
                local.update { it.copy(startInProgress = false) }
            }
            if (result !is CommandResult.Success) local.update { it.copy(startFailure = result) }
        }
    }

    /** The user acknowledges the visible alert; the controller removes it from its pending ones. */
    fun dismissAlert() {
        uiState.value.alert?.let(controller::acknowledgeAlert)
    }

    /** Keeps the workout that has just ended (it is uploaded as usual). */
    fun keepFinishedWorkout() {
        training?.keepFinishedWorkout()
    }

    /** Deletes the workout that has just ended; it is never uploaded. */
    fun discardFinishedWorkout() {
        training?.discardFinishedWorkout()
    }

    fun dismissRecordingNotice() {
        local.update { it.copy(dismissedRecording = recorder.status.value) }
    }

    /**
     * Forgets the remembered treadmill, so the app scans next time instead of reconnecting. Only while the belt is at
     * rest. An open connection is left alone: it is only the memory of it that goes.
     */
    fun forgetTreadmill() {
        if (!uiState.value.canForgetTreadmill) return
        settings.lastTreadmill = null
        pickedName = null
        windowJob?.cancel()
        local.update { it.copy(remembered = null, autoConnectGaveUp = false) }
    }

    fun setDarkTheme(dark: Boolean) {
        settings.darkTheme = dark
        local.update { it.copy(darkTheme = dark) }
    }

    companion object {
        const val SCAN_DURATION_MILLIS = 20_000L

        /** How long the app keeps trying to reach the remembered treadmill on its own after opening or returning. */
        const val WINDOW_MILLIS = 120_000L

        /** Pause between two automatic attempts (each attempt itself looks for the treadmill for several seconds). */
        const val RETRY_PAUSE_MILLIS = 2_000L

        /** The most the first attempt waits for the strap to be handed to the heart-rate monitor. */
        const val STRAP_WAIT_MILLIS = 3_000L
    }
}
