package com.treadz.console.treadmill

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Automatic recording of every session for debugging (debug builds only; release builds use [DisabledWorkoutRecorder]).
 *
 * A session starts when the belt leaves [BeltStatus.Idle] and ends when it returns to Idle
 * (stop, safety key put back) or the connection is lost. All raw BLE traffic is recorded,
 * along with decoded states, events and the commands sent, with timestamps.
 */
interface WorkoutRecorder {
    val status: StateFlow<RecordingStatus>

    /** `false` when sessions are not recorded at all (release builds): the UI then offers nothing about recordings. */
    val enabled: Boolean get() = true
}

/** The recorder of builds that record nothing: never leaves [RecordingStatus.Idle] and writes no file. */
object DisabledWorkoutRecorder : WorkoutRecorder {
    override val status: StateFlow<RecordingStatus> = MutableStateFlow<RecordingStatus>(RecordingStatus.Idle).asStateFlow()
    override val enabled: Boolean get() = false
}

data class RecordingInfo(
    /** File name, without spaces (e.g. `treadz_20261007_183000.jsonl`). */
    val fileName: String,
    val startedAtMillis: Long,
)

sealed interface RecordingStatus {
    data object Idle : RecordingStatus
    data class Recording(val info: RecordingInfo) : RecordingStatus

    /** Session finished and saved. [location] is the path shown to the user (e.g. `Download/TreadZ/…`). */
    data class Saved(val info: RecordingInfo, val location: String) : RecordingStatus
    data class Failed(val reason: String) : RecordingStatus
}
