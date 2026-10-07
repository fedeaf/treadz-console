package com.treadz.console.treadmill.ftms

import com.treadz.console.treadmill.DiscoveredTreadmill
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.SharedFlow

/**
 * Abstract GATT operations on the treadmill. The control logic (`FtmsTreadmillController`) only knows
 * this interface; there is an Android implementation (`BluetoothGatt`) and a simulator for tests and demos.
 *
 * Only the [FtmsCharacteristic] characteristics can be addressed: neither the iConsole channel nor the OTA service.
 *
 * Implementation contract:
 *  - [incoming] is a hot flow: it emits even if nobody collects it, without losing packets in bursts.
 *  - [disconnections] emits **only** unrequested connection losses (never after [disconnect]).
 *  - `suspend` functions throw [TransportException] on failure (and must have their own timeout).
 */
interface FtmsTransport {

    /** Notifications and indications of the subscribed characteristics, in order of arrival. */
    val incoming: SharedFlow<GattPacket>

    /** Unrequested connection losses. */
    val disconnections: SharedFlow<TransportDisconnect>

    /** BLE name of the connected device, if known. */
    val deviceName: String?

    /** Scans for nearby FTMS treadmills (filtering by the 0x1826 service). Stops when the collection is cancelled. */
    fun scan(): Flow<DiscoveredTreadmill>

    /** Connects, discovers services and checks that FTMS exists. */
    suspend fun connect(address: String)

    /** Enables notifications (or indications, on the Control Point) for the characteristic. */
    suspend fun subscribe(characteristic: FtmsCharacteristic)

    /** Reads a characteristic; `null` if the device does not have it. */
    suspend fun read(characteristic: FtmsCharacteristic): ByteArray?

    /** Writes to the Control Point with response (the GATT acknowledgement, not the FTMS indication). */
    suspend fun writeControlPoint(value: ByteArray)

    /** Closes the connection at our request. */
    suspend fun disconnect()
}

class TransportException(message: String, cause: Throwable? = null) : Exception(message, cause)

data class TransportDisconnect(val reason: String)
