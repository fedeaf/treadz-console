package com.treadz.console.treadmill.recording

import com.treadz.console.treadmill.CommandResult
import com.treadz.console.treadmill.ConnectionState
import com.treadz.console.treadmill.TreadmillEvent
import com.treadz.console.treadmill.TreadmillState
import com.treadz.console.treadmill.ValueRange
import com.treadz.console.treadmill.control.TraceRecord
import com.treadz.console.treadmill.ftms.MachineStatus
import com.treadz.console.treadmill.ftms.toHex

/** Turns every [TraceRecord] into a JSON line. All of them carry `t` (epoch ms) and `type`. */
internal object RecordSerializer {

    fun line(record: TraceRecord): String = Json.stringify(fields(record))

    fun fields(r: TraceRecord): Map<String, Any?> {
        val m = linkedMapOf<String, Any?>("t" to r.atMillis)
        when (r) {
            is TraceRecord.RawReceived -> {
                m["type"] = "rx"; m["ch"] = r.characteristic.shortName; m["hex"] = r.value.toHex()
            }
            is TraceRecord.RawSent -> {
                m["type"] = "tx"; m["ch"] = r.characteristic.shortName; m["hex"] = r.value.toHex()
            }
            is TraceRecord.RawRead -> {
                m["type"] = "read"; m["ch"] = r.characteristic.shortName; m["hex"] = r.value?.toHex()
            }
            is TraceRecord.Malformed -> {
                m["type"] = "malformed"; m["ch"] = r.characteristic.shortName; m["hex"] = r.value.toHex(); m["reason"] = r.reason
            }
            is TraceRecord.Connection -> {
                m["type"] = "connection"
                putConnection(m, r.state)
                m["detail"] = r.detail
            }
            is TraceRecord.Bluetooth -> {
                m["type"] = "bluetooth"; m["enabled"] = r.enabled
            }
            is TraceRecord.Device -> {
                m["type"] = "device"
                m.putAll(deviceFields(r))
            }
            is TraceRecord.Sample -> {
                m["type"] = "data"
                m["speed_kmh"] = r.sample.speedKmh
                m["distance_m"] = r.sample.distanceMeters
                m["incline_pct"] = r.sample.inclinePercent
                m["kcal"] = r.sample.calories
                m["elapsed_s"] = r.sample.elapsedSeconds
                m["stopwatch_s"] = r.sample.stopwatchSeconds
                m["hr_bpm"] = r.sample.heartRateBpm
                m["discarded"] = r.discarded
            }
            is TraceRecord.Status -> {
                m["type"] = "status"
                m["op"] = r.status.opCode
                m["name"] = r.status.displayName
                when (val s = r.status) {
                    is MachineStatus.TargetSpeedChanged -> m["kmh"] = s.kmh
                    is MachineStatus.TargetInclineChanged -> m["pct"] = s.percent
                    is MachineStatus.TargetDistanceChanged -> m["meters"] = s.meters
                    is MachineStatus.TargetTimeChanged -> m["seconds"] = s.seconds
                    is MachineStatus.StoppedOrPaused -> m["param"] = s.param
                    is MachineStatus.Other -> m["params_hex"] = s.params.toHex()
                    else -> Unit
                }
            }
            is TraceRecord.Training -> {
                m["type"] = "training"; m["code"] = r.status.code; m["name"] = r.status.name; m["flags"] = r.status.flags
                m["text"] = r.status.text
            }
            is TraceRecord.ControlResponse -> {
                m["type"] = "cp_response"
                m["request_op"] = r.response.requestOpCode
                m["request"] = r.response.requestName
                m["result_code"] = r.response.resultCode
                m["result"] = r.response.result.name
            }
            is TraceRecord.StateChanged -> {
                m["type"] = "state"; m["reason"] = r.reason
                m.putAll(stateFields(r.state))
            }
            is TraceRecord.Event -> {
                m["type"] = "event"
                m.putAll(eventFields(r.event))
            }
            is TraceRecord.CommandIssued -> {
                m["type"] = "command"; m["id"] = r.id; m["name"] = r.name; m["args"] = r.args
            }
            is TraceRecord.CommandFinished -> {
                m["type"] = "command_result"; m["id"] = r.id; m["name"] = r.name
                m.putAll(resultFields(r.result))
            }
            is TraceRecord.Note -> {
                m["type"] = "note"; m["text"] = r.text
            }
        }
        return m
    }

    fun stateFields(s: TreadmillState): Map<String, Any?> = linkedMapOf(
        "status" to s.status.name,
        "speed_kmh" to s.speedKmh,
        "target_speed_kmh" to s.targetSpeedKmh,
        "incline_pct" to s.inclinePercent,
        "distance_m" to s.distanceMeters,
        "elapsed_s" to s.elapsedSeconds,
        "kcal" to s.calories,
        "hr_bpm" to s.heartRateBpm,
        "stale" to s.isStale,
    )

    fun deviceFields(d: TraceRecord.Device): Map<String, Any?> = linkedMapOf(
        "address" to d.info.address,
        "name" to d.info.name,
        "manufacturer" to d.info.manufacturer,
        "model" to d.info.model,
        "firmware" to d.info.firmware,
        "hardware" to d.info.hardware,
        "capabilities" to linkedMapOf(
            "speed" to range(d.capabilities.speed),
            "incline" to range(d.capabilities.incline),
            "ranges_are_defaults" to d.info.rangesAreDefaults,
            "features_hex" to d.info.featuresHex,
            "machine_features" to d.info.features?.machineFeatureNames,
            "target_settings" to d.info.features?.targetSettingNames,
        ),
    )

    private fun range(r: ValueRange) = linkedMapOf("min" to r.min, "max" to r.max, "step" to r.step)

    private fun putConnection(m: MutableMap<String, Any?>, s: ConnectionState) {
        when (s) {
            is ConnectionState.Disconnected -> m["state"] = "Disconnected"
            is ConnectionState.Connecting -> { m["state"] = "Connecting"; m["address"] = s.address }
            is ConnectionState.Connected -> { m["state"] = "Connected"; m["address"] = s.address; m["name"] = s.name }
            is ConnectionState.Reconnecting -> { m["state"] = "Reconnecting"; m["address"] = s.address; m["attempt"] = s.attempt }
        }
    }

    private fun eventFields(e: TreadmillEvent): Map<String, Any?> = when (e) {
        is TreadmillEvent.SafetyKeyRemoved -> linkedMapOf("name" to "SafetyKeyRemoved")
        is TreadmillEvent.SafetyKeyRestored -> linkedMapOf("name" to "SafetyKeyRestored")
        is TreadmillEvent.ConnectionLost -> linkedMapOf("name" to "ConnectionLost")
        is TreadmillEvent.CommandNotExecuted -> linkedMapOf("name" to "CommandNotExecuted", "command" to e.command)
        is TreadmillEvent.DataStalled -> linkedMapOf("name" to "DataStalled")
        is TreadmillEvent.ManualChange ->
            linkedMapOf("name" to "ManualChange", "speed_kmh" to e.speedKmh, "incline_pct" to e.inclinePercent)
    }

    fun resultFields(r: CommandResult): Map<String, Any?> = when (r) {
        is CommandResult.Success -> linkedMapOf("result" to "Success")
        is CommandResult.NotConnected -> linkedMapOf("result" to "NotConnected")
        is CommandResult.Rejected -> linkedMapOf<String, Any?>("result" to "Rejected", "code" to r.code.name, "reason" to r.reason).also {
            if (r.problem != null) it["problem"] = r.problem?.name
        }
        is CommandResult.NotVerified -> linkedMapOf("result" to "NotVerified", "code" to r.code.name, "reason" to r.reason)
        is CommandResult.NotAllowed -> linkedMapOf("result" to "NotAllowed", "code" to r.code.name, "reason" to r.reason)
    }
}
