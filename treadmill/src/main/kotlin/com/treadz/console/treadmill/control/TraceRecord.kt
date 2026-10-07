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

    data class CommandIssued(
        override val atMillis: Long,
        val id: Long,
        val name: String,
        val args: Map<String, Any?>,
    ) : TraceRecord

    data class CommandFinished(
        override val atMillis: Long,
        val id: Long,
        val name: String,
        val result: CommandResult,
    ) : TraceRecord

    /** Free-form controller note (decisions, clamping, discarded data…). */
    data class Note(override val atMillis: Long, val text: String) : TraceRecord
}

fun interface ControllerObserver {
    /** Called synchronously and in order. Must not block or call back into the controller. */
    fun onTrace(record: TraceRecord)
}
