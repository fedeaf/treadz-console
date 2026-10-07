package com.treadz.console.treadmill.ftms

import com.treadz.console.treadmill.ConnectionProblem
import com.treadz.console.treadmill.DiscoveredTreadmill
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * Abstract GATT operations on the treadmill. The control logic (`FtmsTreadmillController`) only knows
 * this interface; there is an Android implementation (`BluetoothGatt`) and a simulator for tests and demos.
 *
 * Only the [FtmsCharacteristic] characteristics can be addressed: neither the iConsole channel nor the OTA service.
 *
 * Implementation contract:
 *  - [incoming] is a hot flow: it emits even if nobody collects it, without losing packets in bursts.
 *  - [disconnections] emits **only** unrequested connection losses (never after [disconnect]).
 *  - `suspend` functions throw [TransportException] on failure (and must have their own timeout). They must be
 *    cancellable: cancelling a [connect] or [locate] in progress leaves nothing half open.
 */
interface FtmsTransport {

    /** Notifications and indications of the subscribed characteristics, in order of arrival. */
    val incoming: SharedFlow<GattPacket>

    /** Unrequested connection losses. */
    val disconnections: SharedFlow<TransportDisconnect>

    /** BLE name of the connected device, if known (from the scan result or from the system). */
    val deviceName: String?

    /**
     * Whether the Bluetooth adapter is on. Turning it off drops the connection (reported in [disconnections] if one
     * was established) and makes [scan], [locate] and [connect] fail with [ConnectionProblem.BluetoothOff].
     * Transports that cannot be switched off report `true`.
     */
    val bluetoothEnabled: StateFlow<Boolean> get() = ALWAYS_ENABLED

    /**
     * Scans for nearby FTMS treadmills (filtering by the 0x1826 service). Stops when the collection is cancelled.
     * Fails with a [TransportException] carrying the [TransportException.problem].
     */
    fun scan(): Flow<DiscoveredTreadmill>

    /**
     * Looks for the device with [address] with a short scan that ends as soon as it is seen, or after [timeoutMs].
     * Returns what the scan saw, or `null` if it did not appear (not an error). The sighting is remembered, so that a
     * following [connect] uses the system's own device object (with the right address type) instead of guessing.
     *
     * Throws [TransportException] with [ConnectionProblem.BluetoothOff] / [ConnectionProblem.PermissionMissing] when
     * scanning is impossible; other scan failures may be reported as `null`. Implementations must keep within the
     * platform's scan throttling. The default does not scan: it assumes the device is reachable.
     */
    suspend fun locate(address: String, timeoutMs: Long): DiscoveredTreadmill? =
        DiscoveredTreadmill(address, deviceName, rssi = 0)

    /** Connects, discovers services and checks that FTMS exists. */
    suspend fun connect(address: String)

    /** Enables notifications (or indications, on the Control Point) for the characteristic. */
    suspend fun subscribe(characteristic: FtmsCharacteristic)

    /** Reads a characteristic; `null` if the device does not have it. */
    suspend fun read(characteristic: FtmsCharacteristic): ByteArray?

    /** Writes to the Control Point with response (the GATT acknowledgement, not the FTMS indication). */
    suspend fun writeControlPoint(value: ByteArray)

    /** Closes the connection at our request. Also abandons a [connect] in progress. */
    suspend fun disconnect()
}

/**
 * Transport failure. The [message] is English text for the log; [problem] is the stable reason the UI translates.
 */
class TransportException(
    message: String,
    cause: Throwable? = null,
    val problem: ConnectionProblem = ConnectionProblem.Other,
) : Exception(message, cause)

data class TransportDisconnect(val reason: String)

private val ALWAYS_ENABLED: StateFlow<Boolean> = kotlinx.coroutines.flow.MutableStateFlow(true)
