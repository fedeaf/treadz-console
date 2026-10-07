package com.treadz.console.treadmill

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The app's single entry point for talking to the treadmill.
 *
 * No screen writes to the FTMS Control Point directly: every command goes through here,
 * and the implementation verifies its effect with the data coming from the treadmill (Treadmill Data at ~1 Hz)
 * before considering the command done.
 *
 * All `suspend` functions are safe to call from the main thread.
 */
interface TreadmillController {

    /** BLE connection state. */
    val connection: StateFlow<ConnectionState>

    /** Last known treadmill state. Updated with every data packet (~1 Hz). */
    val state: StateFlow<TreadmillState>

    /** Capabilities read from the treadmill on connect; `null` while there is no connection. */
    val capabilities: StateFlow<TreadmillCapabilities?>

    /**
     * One-off events (safety alerts, manual changes…). No replay: an event emitted with nobody
     * listening is lost. For alerts, use [pendingAlerts].
     */
    val events: SharedFlow<TreadmillEvent>

    /**
     * Safety alerts not yet acknowledged, oldest first. Unlike [events], they are not lost
     * if the UI was not listening when they happened. An alert equal to a pending one is not duplicated.
     */
    val pendingAlerts: StateFlow<List<TreadmillAlert>>

    /**
     * The user has seen the alert: it is removed from [pendingAlerts]. [TreadmillEvent.SafetyKeyRemoved] is also
     * removed automatically when the key is put back.
     */
    fun acknowledgeAlert(alert: TreadmillAlert)

    /** Safety limits of the active user. Commands exceeding them are clamped. */
    val limits: StateFlow<SafetyLimits>

    fun setLimits(limits: SafetyLimits)

    /** Scans for nearby FTMS treadmills. Scanning stops when the Flow collection is cancelled. */
    fun scan(): Flow<DiscoveredTreadmill>

    /** Connects, reads capabilities, subscribes to data/status and requests control (Request Control). */
    suspend fun connect(address: String): CommandResult

    suspend fun disconnect()

    /** Starts the belt (~3–4 s countdown on the treadmill; starts at 0.5 km/h and 0 %). */
    suspend fun start(): CommandResult

    /** Pause: the belt decelerates to 0, the treadmill keeps time/distance and lowers the incline to 0. */
    suspend fun pause(): CommandResult

    /** Resumes after a pause and re-applies the previous target speed/incline. */
    suspend fun resume(): CommandResult

    /** Stops the belt; the treadmill resets its session. */
    suspend fun stop(): CommandResult

    /** Target speed in km/h. Clamped to the treadmill range and to [SafetyLimits.maxSpeedKmh]. */
    suspend fun setSpeed(kmh: Double): CommandResult

    /** Target incline in %. Only effective with the belt running. Clamped to the range and to the limits. */
    suspend fun setIncline(percent: Double): CommandResult
}
