package com.treadz.console.treadmill.recording

import java.io.File

/** A `.jsonl` recording already read: a list of records (JSON objects) in order. */
class RecordedSession(val records: List<Map<String, Any?>>) {
    val header: Map<String, Any?>? get() = records.firstOrNull()?.takeIf { it["type"] == "header" }
    val end: Map<String, Any?>? get() = records.lastOrNull()?.takeIf { it["type"] == "end" }

    fun ofType(type: String): List<Map<String, Any?>> = records.filter { it["type"] == type }

    /** Session events by name (`SafetyKeyRemoved`, `ConnectionLost`…). */
    fun events(name: String): List<Map<String, Any?>> = ofType("event").filter { it["name"] == name }
}

/** Reads JSON Lines recordings (`treadz-session` format). */
object RecordingReader {

    /** Throws [IllegalArgumentException] if any line is not a valid JSON object with `t` and `type`. */
    fun parse(text: String): RecordedSession {
        val records = text.lineSequence().filter { it.isNotBlank() }.mapIndexed { i, line ->
            val obj = try {
                Json.parseObject(line)
            } catch (e: Exception) {
                throw IllegalArgumentException("Invalid line ${i + 1}: ${e.message}", e)
            }
            require(obj["t"] is Long && obj["type"] is String) { "Line ${i + 1} has no 't' or 'type'" }
            obj
        }.toList()
        return RecordedSession(records)
    }

    fun read(file: File): RecordedSession = parse(file.readText(Charsets.UTF_8))
}
