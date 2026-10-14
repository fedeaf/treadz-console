package com.treadz.console.hrm

/**
 * Turns a [HrmTraceRecord] into a line type and JSON fields for a debug recording (the treadmill session `.jsonl`
 * writes them as `{"t": ..., "type": <type>, ...fields}`). All values are JSON-friendly (strings, numbers, booleans,
 * lists). Line types: `hrm_rx`, `hrm_read`, `hrm_malformed`, `hrm_reading`, `hrm_connection`, `hrm_note`.
 */
object HrmTraceFields {

    fun of(record: HrmTraceRecord): Pair<String, Map<String, Any?>> = when (record) {
        is HrmTraceRecord.RawReceived -> "hrm_rx" to linkedMapOf("ch" to record.characteristic.shortName, "hex" to record.value.toHrmHex())
        is HrmTraceRecord.RawRead -> "hrm_read" to linkedMapOf("ch" to record.characteristic.shortName, "hex" to record.value?.toHrmHex())
        is HrmTraceRecord.Malformed -> "hrm_malformed" to linkedMapOf(
            "ch" to record.characteristic.shortName, "hex" to record.value.toHrmHex(), "reason" to record.reason,
        )
        is HrmTraceRecord.Reading -> "hrm_reading" to linkedMapOf(
            "hr_bpm" to record.reading.bpm,
            "contact" to record.reading.sensorContact.name,
            "rr_ms" to record.reading.rrIntervalsMs,
            "rr_1024" to record.rrIntervalsRaw,
            "energy_kj" to record.reading.energyExpendedKj,
        )
        is HrmTraceRecord.Connection -> "hrm_connection" to connectionFields(record.state).also { it["detail"] = record.detail }
        is HrmTraceRecord.Note -> "hrm_note" to linkedMapOf("text" to record.text)
    }

    fun connectionFields(s: StrapConnectionState): LinkedHashMap<String, Any?> = when (s) {
        StrapConnectionState.Inactive -> linkedMapOf("state" to "Inactive")
        is StrapConnectionState.Searching -> linkedMapOf("state" to "Searching", "address" to s.address)
        is StrapConnectionState.Connecting -> linkedMapOf("state" to "Connecting", "address" to s.address)
        is StrapConnectionState.Connected -> linkedMapOf("state" to "Connected", "address" to s.address, "name" to s.name)
    }
}
