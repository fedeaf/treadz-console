package com.treadz.console.fakes

import com.treadz.console.treadmill.BeltStatus
import com.treadz.console.treadmill.CommandResult
import com.treadz.console.treadmill.ConnectionProblem
import com.treadz.console.treadmill.ConnectionState
import com.treadz.console.treadmill.DiscoveredTreadmill
import com.treadz.console.treadmill.FailureCode
import com.treadz.console.treadmill.SafetyLimits
import com.treadz.console.treadmill.TreadmillAlert
import com.treadz.console.treadmill.TreadmillCapabilities
import com.treadz.console.treadmill.TreadmillController
import com.treadz.console.treadmill.TreadmillEvent
import com.treadz.console.treadmill.TreadmillState
import com.treadz.console.treadmill.ValueRange
import com.treadz.console.treadmill.ftms.TransportException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow

/**
 * Fake treadmill for tests and screenshots. All flows are mutable and there are shortcuts to put it in each
 * of the states worth seeing (connected, running, key removed, reconnecting…).
 */
class FakeTreadmillController : TreadmillController {

    override val connection = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    override val state = MutableStateFlow(TreadmillState())
    override val capabilities = MutableStateFlow<TreadmillCapabilities?>(null)
    override val events = MutableSharedFlow<TreadmillEvent>(extraBufferCapacity = 64)
    override val limits = MutableStateFlow(SafetyLimits())
    override val pendingAlerts = MutableStateFlow<List<TreadmillAlert>>(emptyList())
    override val bluetoothEnabled = MutableStateFlow(true)
    override val connectionProblem = MutableStateFlow<ConnectionProblem?>(null)

    override fun acknowledgeAlert(alert: TreadmillAlert) {
        pendingAlerts.value = pendingAlerts.value - alert
    }

    /** Treadmills the scan "finds". */
    var scanResults: List<DiscoveredTreadmill> = emptyList()

    /** If true the scan never ends (like the real one, until it is cancelled). */
    var scanNeverEnds = true
    var scanError: Exception? = null

    var connectResult: CommandResult = CommandResult.Success

    /** The connection reports no name, as Android does when it reconnects by itself. */
    var connectWithoutName = false

    /** If not null, `connect` waits for it to complete (to show "Connecting"). */
    var connectGate: CompletableDeferred<Unit>? = null
    var startResult: CommandResult = CommandResult.Success

    // Call log
    val connectCalls = mutableListOf<String>()
    var scanCalls = 0
    var startCalls = 0

    var disconnectCalls = 0

    override fun setLimits(limits: SafetyLimits) {
        this.limits.value = limits
    }

    override fun scan(): Flow<DiscoveredTreadmill> = flow {
        scanCalls++
        if (!bluetoothEnabled.value) throw TransportException("Bluetooth off or unavailable", problem = ConnectionProblem.BluetoothOff)
        scanError?.let { throw it }
        scanResults.forEach { emit(it) }
        if (scanNeverEnds) awaitCancellation()
    }

    override suspend fun connect(address: String): CommandResult {
        connectCalls += address
        if (!bluetoothEnabled.value) {
            return CommandResult.Rejected(FailureCode.ConnectionFailed, "Bluetooth off", ConnectionProblem.BluetoothOff)
        }
        connection.value = ConnectionState.Connecting(address)
        connectGate?.await()
        return if (connectResult is CommandResult.Success) {
            val name = if (connectWithoutName) null else scanResults.firstOrNull { it.address == address }?.name ?: TREADMILL_NAME
            connection.value = ConnectionState.Connected(address, name)
            capabilities.value = DEFAULT_CAPABILITIES
            CommandResult.Success
        } else {
            connection.value = ConnectionState.Disconnected
            connectResult
        }
    }

    override suspend fun disconnect() {
        disconnectCalls++
        // Like the real controller: abandons a reconnection (or closes the connection) and leaves Disconnected.
        connection.value = ConnectionState.Disconnected
        connectionProblem.value = null
    }

    override suspend fun start(): CommandResult {
        startCalls++
        if (startResult is CommandResult.Success) state.value = state.value.copy(status = BeltStatus.Starting)
        return startResult
    }

    // ---- Manual controls ----

    var pauseResult: CommandResult = CommandResult.Success
    var resumeResult: CommandResult = CommandResult.Success
    var stopResult: CommandResult = CommandResult.Success
    var pauseCalls = 0
    var resumeCalls = 0
    var stopCalls = 0

    /** Every speed / incline command received, in order. */
    val speedCommands = mutableListOf<Double>()
    val inclineCommands = mutableListOf<Double>()

    /** Commands of any kind sent besides scan/connect/start (for "the UI never sends anything else" checks). */
    val manualCommandCount: Int get() = pauseCalls + resumeCalls + stopCalls + speedCommands.size + inclineCommands.size

    /**
     * What a speed command does. The default confirms it at once and moves the target, like a verified command;
     * tests replace it to make it slow (suspend) or to fail.
     */
    var setSpeedHandler: suspend (Double) -> CommandResult = { kmh ->
        state.value = state.value.copy(targetSpeedKmh = kmh)
        CommandResult.Success
    }
    var setInclineHandler: suspend (Double) -> CommandResult = { percent ->
        state.value = state.value.copy(inclinePercent = percent)
        CommandResult.Success
    }

    override suspend fun pause(): CommandResult {
        pauseCalls++
        if (pauseResult is CommandResult.Success) state.value = state.value.copy(status = BeltStatus.Paused)
        return alertIfNotExecuted("pause", pauseResult)
    }

    override suspend fun resume(): CommandResult {
        resumeCalls++
        if (resumeResult is CommandResult.Success) state.value = state.value.copy(status = BeltStatus.Running)
        return alertIfNotExecuted("resume", resumeResult)
    }

    override suspend fun stop(): CommandResult {
        stopCalls++
        if (stopResult is CommandResult.Success) state.value = state.value.copy(status = BeltStatus.Idle, speedKmh = 0.0, targetSpeedKmh = null)
        return alertIfNotExecuted("stop", stopResult)
    }

    override suspend fun setSpeed(kmh: Double): CommandResult {
        speedCommands += kmh
        return alertIfNotExecuted("setSpeed", setSpeedHandler(kmh))
    }

    override suspend fun setIncline(percent: Double): CommandResult {
        inclineCommands += percent
        return alertIfNotExecuted("setIncline", setInclineHandler(percent))
    }

    /** Like the real controller: a command accepted but not verified raises the critical alert (a stop-preempted one does not). */
    private fun alertIfNotExecuted(name: String, result: CommandResult): CommandResult {
        val critical = when (result) {
            is CommandResult.NotVerified -> result.code != FailureCode.Preempted
            is CommandResult.Rejected -> name == "stop" || name == "pause"
            else -> false
        }
        if (critical) emit(TreadmillEvent.CommandNotExecuted(name))
        return result
    }

    // ---- Scenario shortcuts ----

    fun showConnected(address: String = TREADMILL_ADDRESS, name: String? = TREADMILL_NAME) {
        connection.value = ConnectionState.Connected(address, name)
        capabilities.value = DEFAULT_CAPABILITIES
    }

    fun showReconnecting(attempt: Int = 2, address: String = TREADMILL_ADDRESS) {
        connection.value = ConnectionState.Reconnecting(address, attempt)
    }

    /** Reconnection that keeps failing: the treadmill is not found. */
    fun showReconnectingNotFound(attempt: Int = 6) {
        showReconnecting(attempt)
        connectionProblem.value = ConnectionProblem.DeviceNotFound
    }

    /** Bluetooth turned off: the controller waits (and the scan / connect would fail). */
    fun showBluetoothOff() {
        bluetoothEnabled.value = false
    }

    fun showState(transform: (TreadmillState) -> TreadmillState) {
        state.value = transform(state.value)
    }

    /** Typical running session: 8.5 km/h at 3 %, halfway through a workout. */
    fun showRunning(heartRate: Int? = 142) {
        state.value = TreadmillState(
            status = BeltStatus.Running,
            speedKmh = 8.5,
            targetSpeedKmh = 8.5,
            inclinePercent = 3.0,
            distanceMeters = 2350,
            elapsedSeconds = 1_005,
            calories = 187,
            heartRateBpm = heartRate,
            updatedAtMillis = 1_000,
        )
    }

    /** Emits the event and, like the real controller, keeps alerts pending until acknowledged. */
    fun emit(event: TreadmillEvent) {
        val pending = pendingAlerts.value
        when {
            event is TreadmillAlert && event !in pending -> pendingAlerts.value = pending + event
            event is TreadmillEvent.SafetyKeyRestored -> pendingAlerts.value = pending - TreadmillEvent.SafetyKeyRemoved
        }
        check(events.tryEmit(event)) { "event buffer full" }
    }

    companion object {
        const val TREADMILL_ADDRESS = "D5:F3:C2:06:5C:8B"
        const val TREADMILL_NAME = "REEBOK 0517"

        val DEFAULT_CAPABILITIES = TreadmillCapabilities(
            speed = ValueRange(0.5, 20.0, 0.1),
            incline = ValueRange(0.0, 18.0, 1.0),
            manufacturer = "ChangYow",
            model = "SI3019",
            firmware = "07",
        )

        val SAMPLE_DEVICES = listOf(
            DiscoveredTreadmill(TREADMILL_ADDRESS, TREADMILL_NAME, rssi = -48),
            DiscoveredTreadmill("A4:C1:38:5E:90:12", "SW-BLE", rssi = -71),
            DiscoveredTreadmill("7C:9E:BD:11:22:33", null, rssi = -86),
        )
    }
}
