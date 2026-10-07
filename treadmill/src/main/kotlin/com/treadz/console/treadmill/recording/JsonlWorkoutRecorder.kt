package com.treadz.console.treadmill.recording

import com.treadz.console.treadmill.BeltStatus
import com.treadz.console.treadmill.ConnectionState
import com.treadz.console.treadmill.RecordingInfo
import com.treadz.console.treadmill.RecordingStatus
import com.treadz.console.treadmill.TreadmillState
import com.treadz.console.treadmill.WorkoutRecorder
import com.treadz.console.treadmill.control.Clock
import com.treadz.console.treadmill.control.ControllerObserver
import com.treadz.console.treadmill.control.TraceRecord
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

data class RecorderConfig(
    val appName: String = "TreadZ Console",
    val appVersion: String = "dev",
    /** Phone/tablet model (e.g. `Build.MODEL`), to know which device made the recording. */
    val phoneModel: String? = null,
    val androidVersion: String? = null,
    /** What happened before the session start (Start command, Idle packets…) that is dumped at the beginning. */
    val prerollMs: Long = 15_000,
    val prerollMaxRecords: Int = 600,
    /** After returning to Idle the session stays open this long to capture the last packets and events. */
    val endGraceMs: Long = 3_000,
    val syncIntervalMs: Long = 1_000,
    val zone: ZoneId = ZoneId.systemDefault(),
)

/**
 * Session recorder in JSON Lines (one JSON line per record, with `t` in epoch ms).
 *
 * A session starts when the belt leaves [BeltStatus.Idle] and ends when it returns to Idle (with the speed
 * already at 0) or the connection is lost. Everything is recorded so the session can be rebuilt on the PC: header,
 * every raw BLE packet received/sent, every decoded state, events, commands and their results, and
 * connection changes. The last [RecorderConfig.prerollMs] ms before the start are included too.
 *
 * The logic is independent of the storage ([RecordingStorage]). Every line is handed to the system
 * as it is written, so an unexpected app shutdown does not lose what was recorded.
 *
 * Line types: `header`, `rx`, `tx`, `read`, `malformed`, `connection`, `device`, `data`, `status`,
 * `training`, `cp_response`, `state`, `event`, `command`, `command_result`, `note`, `end`.
 */
class JsonlWorkoutRecorder(
    private val storage: RecordingStorage,
    private val clock: Clock = Clock.System,
    private val config: RecorderConfig = RecorderConfig(),
) : WorkoutRecorder, ControllerObserver {

    private val _status = MutableStateFlow<RecordingStatus>(RecordingStatus.Idle)
    override val status: StateFlow<RecordingStatus> = _status.asStateFlow()

    private class Session(val info: RecordingInfo, val file: RecordingFile) {
        var records = 0
        var idleSince: Long? = null
        var lastState: TreadmillState? = null
        var lastT = 0L
    }

    private val lock = Any()
    private var session: Session? = null
    private val preroll = ArrayDeque<Pair<Long, String>>()
    private var device: TraceRecord.Device? = null
    private var failedUntilIdle = false
    private var lastSyncAt = 0L

    override fun onTrace(record: TraceRecord) {
        synchronized(lock) {
            val line = RecordSerializer.line(record)
            if (record is TraceRecord.Device) device = record

            // Deferred end: once the grace period after returning to Idle has passed, the session closes with the next record.
            session?.let { s ->
                val idleAt = s.idleSince
                if (idleAt != null && record.atMillis - idleAt >= config.endGraceMs) endSession("idle", idleAt)
            }

            if (record is TraceRecord.StateChanged) onState(record)

            val s = session
            if (s != null && writeLine(s, line, forceSync = record.needsSync())) s.lastT = maxOf(s.lastT, record.atMillis)

            preroll.addLast(record.atMillis to line)
            while (preroll.size > config.prerollMaxRecords ||
                (preroll.isNotEmpty() && preroll.first().first < record.atMillis - config.prerollMs)
            ) {
                preroll.removeFirst()
            }

            if (record is TraceRecord.Connection && session != null) {
                when (record.state) {
                    is ConnectionState.Reconnecting -> endSession("connection_lost", record.atMillis)
                    is ConnectionState.Disconnected -> endSession("disconnected", record.atMillis)
                    else -> Unit
                }
            }
        }
    }

    private fun onState(record: TraceRecord.StateChanged) {
        val state = record.state
        session?.lastState = state
        if (state.status == BeltStatus.Idle) failedUntilIdle = false
        val s = session
        // Real "Idle": no session on the treadmill and the belt already stopped (after Stop it decelerates for a few seconds).
        val idle = state.status == BeltStatus.Idle && state.speedKmh == 0.0
        when {
            s == null -> if (state.status != BeltStatus.Idle && !failedUntilIdle) startSession(record)
            idle -> if (s.idleSince == null) s.idleSince = record.atMillis
            state.status != BeltStatus.Idle && s.idleSince != null -> {
                // Another session starts before the previous one was closed.
                endSession("idle", s.idleSince ?: record.atMillis)
                startSession(record)
            }
            else -> Unit
        }
    }

    private fun startSession(trigger: TraceRecord.StateChanged) {
        val startedAt = trigger.atMillis
        val fileName = uniqueFileName(startedAt)
        val file = try {
            storage.create(fileName)
        } catch (e: Exception) {
            failedUntilIdle = true
            _status.value = RecordingStatus.Failed("Could not create $fileName: ${e.message ?: e::class.simpleName}")
            return
        }
        val info = RecordingInfo(fileName, startedAt)
        val s = Session(info, file).also { it.lastState = trigger.state }
        session = s
        val replay = preroll.filter { it.first >= startedAt - config.prerollMs }
        // The header carries the timestamp of the file's first record so that times never decrease.
        val headerT = replay.firstOrNull()?.first ?: trigger.atMillis
        if (!writeLine(s, Json.stringify(header(trigger, replay.size, headerT)), forceSync = true)) return
        for ((_, l) in replay) if (!writeLine(s, l, forceSync = false)) return
        _status.value = RecordingStatus.Recording(info)
    }

    private fun header(trigger: TraceRecord.StateChanged, prerollRecords: Int, headerT: Long): Map<String, Any?> {
        val dev = device
        return linkedMapOf(
            "t" to headerT,
            "type" to "header",
            "format" to "treadz-session",
            "version" to FORMAT_VERSION,
            "app" to linkedMapOf("name" to config.appName, "version" to config.appVersion),
            "phone" to linkedMapOf("model" to config.phoneModel, "android" to config.androidVersion),
            "device" to dev?.let(RecordSerializer::deviceFields),
            "started_at" to Instant.ofEpochMilli(trigger.atMillis).toString(),
            "start_state" to RecordSerializer.stateFields(trigger.state),
            "start_reason" to trigger.reason,
            "preroll_records" to prerollRecords,
            "preroll_ms" to config.prerollMs,
        )
    }

    /** Writes a line. If the storage fails, the session is considered lost and reported with `Failed`. */
    private fun writeLine(s: Session, line: String, forceSync: Boolean): Boolean {
        try {
            s.file.appendLine(line)
            s.records++
            val now = clock.nowMillis()
            if (forceSync || now - lastSyncAt >= config.syncIntervalMs) {
                s.file.sync()
                lastSyncAt = now
            }
            return true
        } catch (e: Exception) {
            runCatching { s.file.close() }
            session = null
            failedUntilIdle = true
            _status.value = RecordingStatus.Failed("Error writing ${s.info.fileName}: ${e.message ?: e::class.simpleName}")
            return false
        }
    }

    private fun endSession(reason: String, endedAt: Long) {
        val s = session ?: return
        session = null
        val last = s.lastState
        val end = linkedMapOf<String, Any?>(
            "t" to maxOf(clock.nowMillis(), endedAt, s.lastT),
            "type" to "end",
            "reason" to reason,
            "ended_at_ms" to endedAt,
            "duration_ms" to (endedAt - s.info.startedAtMillis),
            "records" to s.records + 1,
            "last_state" to last?.let(RecordSerializer::stateFields),
        )
        try {
            s.file.appendLine(Json.stringify(end))
            s.file.sync()
            s.file.close()
            _status.value = RecordingStatus.Saved(s.info, s.file.location)
        } catch (e: Exception) {
            runCatching { s.file.close() }
            _status.value = RecordingStatus.Failed("Error closing ${s.info.fileName}: ${e.message ?: e::class.simpleName}")
        }
    }

    /** Closes the session in progress (e.g. when the service stops). Does nothing if there is no session. */
    fun finish(reason: String = "closed") {
        synchronized(lock) { endSession(reason, clock.nowMillis()) }
    }

    private fun uniqueFileName(startedAt: Long): String {
        val stamp = FILE_FORMAT.format(Instant.ofEpochMilli(startedAt).atZone(config.zone))
        var name = "treadz_$stamp.jsonl"
        var n = 2
        while (storage.exists(name)) name = "treadz_${stamp}_${n++}.jsonl"
        return name
    }

    private fun TraceRecord.needsSync(): Boolean = this is TraceRecord.Event || this is TraceRecord.CommandFinished ||
        this is TraceRecord.Connection

    companion object {
        const val FORMAT_VERSION = 1
        private val FILE_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss")
    }
}
