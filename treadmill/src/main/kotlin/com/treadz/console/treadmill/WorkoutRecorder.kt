package com.treadz.console.treadmill

import kotlinx.coroutines.flow.StateFlow

/**
 * Automatic recording of every session for debugging.
 *
 * A session starts when the belt leaves [BeltStatus.Idle] and ends when it returns to Idle
 * (stop, safety key put back) or the connection is lost. All raw BLE traffic is recorded,
 * along with decoded states, events and the commands sent, with timestamps.
 */
interface WorkoutRecorder {
    val status: StateFlow<RecordingStatus>
}

data class RecordingInfo(
    /** File name, without spaces (e.g. `treadz_20261007_183000.jsonl`). */
    val fileName: String,
    val startedAtMillis: Long,
)

sealed interface RecordingStatus {
    data object Idle : RecordingStatus
    data class Recording(val info: RecordingInfo) : RecordingStatus

    /** Session finished and saved. [location] is the path shown to the user (e.g. `Download/TreadZConsole/…`). */
    data class Saved(val info: RecordingInfo, val location: String) : RecordingStatus
    data class Failed(val reason: String) : RecordingStatus
}
