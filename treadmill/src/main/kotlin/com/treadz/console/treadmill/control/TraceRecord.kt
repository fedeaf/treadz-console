package com.treadz.console.treadmill.control

import com.treadz.console.treadmill.CommandResult
import com.treadz.console.treadmill.ConnectionState
import com.treadz.console.treadmill.TreadmillCapabilities
import com.treadz.console.treadmill.TreadmillEvent
import com.treadz.console.treadmill.TreadmillState
import com.treadz.console.treadmill.ftms.ControlPointResponse
import com.treadz.console.treadmill.ftms.FtmsCharacteristic
import com.treadz.console.treadmill.ftms.FtmsDeviceInfo
import com.treadz.console.treadmill.ftms.MachineStatus
import com.treadz.console.treadmill.ftms.TrainingStatus
import com.treadz.console.treadmill.ftms.TreadmillSample

/**
 * Everything that happens inside the controller, in order, so it can be recorded and debugged.
 * The controller hands it to the registered [ControllerObserver]s (the recorder is one of them).
 */
sealed interface TraceRecord {
    val atMillis: Long

    /** BLE packet received as is (notification or indication). */
    class RawReceived(override val atMillis: Long, val characteristic: FtmsCharacteristic, val value: ByteArray) : TraceRecord

    /** BLE packet sent as is (always to the Control Point). */
    class RawSent(override val atMillis: Long, val characteristic: FtmsCharacteristic, val value: ByteArray) : TraceRecord

    /** Read of a characteristic (`value == null` if it does not exist or failed). */
    class RawRead(override val atMillis: Long, val characteristic: FtmsCharacteristic, val value: ByteArray?) : TraceRecord

    /** Packet that could not be decoded. */
    class Malformed(
        override val atMillis: Long,
        val characteristic: FtmsCharacteristic,
        val value: ByteArray,
        val reason: String,
    ) : TraceRecord

    data class Connection(override val atMillis: Long, val state: ConnectionState, val detail: String?) : TraceRecord

    /** The Bluetooth adapter was turned on or off (also the initial state when the controller starts). */
    data class Bluetooth(override val atMillis: Long, val enabled: Boolean) : TraceRecord

    /** Device data and capabilities, right after connecting. */
    data class Device(
        override val atMillis: Long,
        val info: FtmsDeviceInfo,
        val capabilities: TreadmillCapabilities,
    ) : TraceRecord

    /** Decoded Treadmill Data. `discarded`: first packet after connecting (stale data), not used. */
    data class Sample(override val atMillis: Long, val sample: TreadmillSample, val discarded: Boolean) : TraceRecord

    data class Status(override val atMillis: Long, val status: MachineStatus) : TraceRecord

    data class Training(override val atMillis: Long, val status: TrainingStatus) : TraceRecord

    data class ControlResponse(override val atMillis: Long, val response: ControlPointResponse) : TraceRecord

    /** Public controller state after processing a message; `reason` says what caused it. */
    data class StateChanged(override val atMillis: Long, val state: TreadmillState, val reason: String) : TraceRecord

    data class Event(override val atMillis: Long, val event: TreadmillEvent) : TraceRecord

    /** [origin]: who issued it ([CommandOrigin] of the caller's coroutine context), when known. */
    data class CommandIssued(
        override val atMillis: Long,
        val id: Long,
        val name: String,
        val args: Map<String, Any?>,
        val origin: CommandOrigin? = null,
    ) : TraceRecord

    data class CommandFinished(
        override val atMillis: Long,
        val id: Long,
        val name: String,
        val result: CommandResult,
    ) : TraceRecord

    /** Free-form controller note (decisions, clamping, discarded data…). */
    data class Note(override val atMillis: Long, val text: String) : TraceRecord

    /**
     * A record from outside the controller that belongs in the same session recording, e.g. the heart-rate strap
     * connected to the app (raw packets, readings, connection) or the heart-rate source in use. It is written as a line
     * of [type] with [fields] (JSON values: strings, numbers, booleans, null, lists and maps). [type] must not be one
     * of the controller's own line types; the convention is a prefix per origin (`hrm_…`, `hr_…`).
     */
    data class External(override val atMillis: Long, val type: String, val fields: Map<String, Any?>) : TraceRecord
}

fun interface ControllerObserver {
    /** Called synchronously and in order. Must not block or call back into the controller. */
    fun onTrace(record: TraceRecord)
}
