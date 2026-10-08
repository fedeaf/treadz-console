package com.treadz.console

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.treadz.console.controls.ControlsState
import com.treadz.console.controls.ManualControls
import com.treadz.console.settings.RememberedTreadmill
import com.treadz.console.settings.SettingsStore
import com.treadz.console.treadmill.BeltStatus
import com.treadz.console.treadmill.CommandResult
import com.treadz.console.treadmill.ConnectionProblem
import com.treadz.console.treadmill.ConnectionState
import com.treadz.console.treadmill.DiscoveredTreadmill
import com.treadz.console.treadmill.RecordingStatus
import com.treadz.console.treadmill.TreadmillController
import com.treadz.console.treadmill.WorkoutRecorder
import com.treadz.console.treadmill.ftms.TransportException
import com.treadz.console.userdata.UserData
import com.treadz.console.userdata.UserProfile
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull

/**
 * Observes the [TreadmillController] and the [WorkoutRecorder] and exposes a single [MainUiState].
 * Treadmill commands: `scan()`, `connect()` and `start()` here, and the manual controls (pause, resume, STOP, speed and
 * incline) through [controls]. Everything but STOP needs an active user.
 */
class MainViewModel(
    private val controller: TreadmillController,
    private val recorder: WorkoutRecorder,
    private val settings: SettingsStore,
    private val userData: UserData,
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
    )

    private val local = MutableStateFlow(
        Local(remembered = settings.lastTreadmill, darkTheme = settings.darkTheme),
    )

    /** The manual controls of the live screen (and the STOP of every screen). */
    val controls = ManualControls(controller, viewModelScope, canOperate = { uiState.value.activeUser != null })

    private val people = combine(
        userData.profiles.activeUser,
        userData.workouts.unassignedWorkouts().map { it.size },
    ) { user, unassigned -> user to unassigned }

    /** Everything the state needs besides the controller's connection, treadmill, recording and alerts. */
    private data class Extras(
        val local: Local,
        val user: UserProfile?,
        val unassigned: Int,
        val controls: ControlsState,
        val bluetoothEnabled: Boolean,
        val reconnectProblem: ConnectionProblem?,
    )

    private val extras = combine(
        local,
        people,
        controls.state,
        controller.bluetoothEnabled,
        controller.connectionProblem,
    ) { local, people, controls, bluetooth, reconnectProblem ->
        Extras(local, people.first, people.second, controls, bluetooth, reconnectProblem)
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
            darkTheme = local.darkTheme,
            activeUser = extras.user,
            unassignedWorkoutCount = extras.unassigned,
            controls = extras.controls,
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, MainUiState(darkTheme = local.value.darkTheme))

    private var scanJob: Job? = null
    private var connectJob: Job? = null
    private var permissionsGranted = false
    private var autoConnectDone = false

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

        // Bluetooth off: nothing is scanned or attempted. Back on: forget the old "Bluetooth is off" error and do the
        // automatic connection that was waiting for it.
        viewModelScope.launch {
            controller.bluetoothEnabled.collect { enabled ->
                if (enabled) {
                    local.update { if (it.problem == ConnectionProblem.BluetoothOff) it.copy(problem = null) else it }
                    autoConnect()
                } else {
                    stopScan()
                }
            }
        }
    }

    private fun saveRemembered(treadmill: RememberedTreadmill) {
        settings.lastTreadmill = treadmill
        local.update { it.copy(remembered = treadmill) }
    }

    /** Called once the app has the Bluetooth permissions: reconnects by itself or starts scanning. */
    fun onPermissionsGranted() {
        permissionsGranted = true
        autoConnect()
    }

    /** Once, when the app has its permissions and Bluetooth is on (it waits for either). */
    private fun autoConnect() {
        if (autoConnectDone || !permissionsGranted || !controller.bluetoothEnabled.value) return
        autoConnectDone = true
        val remembered = local.value.remembered
        if (remembered != null && controller.connection.value is ConnectionState.Disconnected) {
            connect(remembered.address, remembered.name, scanOnFailure = true)
        } else if (remembered == null) {
            startScan()
        }
    }

    fun startScan() = startScan(keepProblem = false)

    private fun startScan(keepProblem: Boolean) {
        if (scanJob?.isActive == true || !controller.bluetoothEnabled.value) return
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

    fun connect(address: String, name: String?) = connect(address, name, scanOnFailure = false)

    private fun connect(address: String, name: String?, scanOnFailure: Boolean) {
        if (connectJob?.isActive == true || !controller.bluetoothEnabled.value) return
        stopScan()
        if (name != null) pickedName = address to name
        local.update { it.copy(problem = null) }
        connectJob = viewModelScope.launch {
            val result = controller.connect(address)
            if (result !is CommandResult.Success) {
                val problem = (result as? CommandResult.Failure)?.problem ?: ConnectionProblem.Other
                local.update { it.copy(problem = problem) }
                if (scanOnFailure && problem != ConnectionProblem.BluetoothOff) startScan(keepProblem = true)
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
                controller.start()
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

    fun dismissRecordingNotice() {
        local.update { it.copy(dismissedRecording = recorder.status.value) }
    }

    fun setDarkTheme(dark: Boolean) {
        settings.darkTheme = dark
        local.update { it.copy(darkTheme = dark) }
    }

    companion object {
        const val SCAN_DURATION_MILLIS = 20_000L
    }
}
