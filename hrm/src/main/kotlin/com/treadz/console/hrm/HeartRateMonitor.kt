package com.treadz.console.hrm

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * The app's access to a heart-rate strap connected directly to it (standard Heart Rate service 0x180D). Independent of
 * the treadmill.
 *
 * Which strap belongs to which user is decided elsewhere: [use] takes the address. While an address is set the monitor
 * keeps looking for the strap and reconnects by itself, without errors or prompts: a strap holds a single BLE
 * connection, so while the treadmill console has it the strap does not advertise and is simply "not found yet"
 * ([StrapConnectionState.Searching]). The only reported problems are those the user can fix ([problem]).
 */
interface HeartRateMonitor {

    val connection: StateFlow<StrapConnectionState>

    /** Bluetooth off or a missing permission; `null` otherwise (also while merely searching). */
    val problem: StateFlow<StrapProblem?>

    /**
     * The current reading of the connected strap: `null` when not connected or when no notification arrived for a few
     * seconds. A reading with [SensorContact.NotDetected] (strap taken off) is kept here: the consumer decides.
     */
    val latest: StateFlow<StrapReading?>

    /** Every reading, as it arrives (hot, no replay). */
    val readings: SharedFlow<StrapReading>

    /** Battery level of the connected strap (%), when it offers it. */
    val batteryPercent: StateFlow<Int?>

    val sensorLocation: StateFlow<BodySensorLocation?>

    /** The address set with [use], or `null`. */
    val address: StateFlow<String?>

    /**
     * Scans for nearby straps, for pairing (the UI offers "use this strap?" once). A strap that is connected to the
     * console or to the app does not advertise and will not appear. Fails with an [HrmTransportException] carrying a
     * [StrapProblem] when scanning is impossible. Stops when the collection is cancelled.
     */
    fun scan(): Flow<DiscoveredStrap>

    /**
     * Keeps the strap with [address] connected (searching for it and reconnecting as needed). `null` disconnects and
     * stops looking ([StrapConnectionState.Inactive]). Setting the address already in use does nothing; a different
     * one drops the current strap first. Returns immediately.
     */
    fun use(address: String?)

    /** Same as `use(null)` but waits until the strap is disconnected (e.g. before the app closes). */
    suspend fun release()

    /** Registers an observer of everything the monitor does (raw packets, readings, connection), for the recording. */
    fun addObserver(observer: HrmObserver)

    fun removeObserver(observer: HrmObserver)
}

/** Everything the monitor does, in order, so it can be recorded. */
sealed interface HrmTraceRecord {
    val atMillis: Long

    /** A notification as received. */
    class RawReceived(override val atMillis: Long, val characteristic: HrmCharacteristic, val value: ByteArray) : HrmTraceRecord

    /** A read (`value == null` when the strap does not have the characteristic). */
    class RawRead(override val atMillis: Long, val characteristic: HrmCharacteristic, val value: ByteArray?) : HrmTraceRecord

    class Malformed(override val atMillis: Long, val characteristic: HrmCharacteristic, val value: ByteArray, val reason: String) : HrmTraceRecord

    data class Reading(override val atMillis: Long, val reading: StrapReading, val rrIntervalsRaw: List<Int>) : HrmTraceRecord

    data class Connection(override val atMillis: Long, val state: StrapConnectionState, val detail: String?) : HrmTraceRecord

    /** Free-form note (search results, failures, decisions). */
    data class Note(override val atMillis: Long, val text: String) : HrmTraceRecord
}

fun interface HrmObserver {
    /** Called synchronously and in order; must not block or call back into the monitor. */
    fun onTrace(record: HrmTraceRecord)
}
