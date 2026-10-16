package com.treadz.console.fakes

import com.treadz.console.treadmill.RecordingInfo
import com.treadz.console.treadmill.RecordingStatus
import com.treadz.console.treadmill.WorkoutRecorder
import kotlinx.coroutines.flow.MutableStateFlow

/** [enabled] `false` stands for a release build, which records nothing. */
class FakeWorkoutRecorder(override val enabled: Boolean = true) : WorkoutRecorder {
    override val status = MutableStateFlow<RecordingStatus>(RecordingStatus.Idle)

    fun showRecording() {
        status.value = RecordingStatus.Recording(INFO)
    }

    fun showSaved() {
        status.value = RecordingStatus.Saved(INFO, "Download/TreadZ/${INFO.fileName}")
    }

    fun showFailed(reason: String = "No space left on device") {
        status.value = RecordingStatus.Failed(reason)
    }

    companion object {
        val INFO = RecordingInfo(fileName = "treadz_20261007_183000.jsonl", startedAtMillis = 1_791_397_800_000)
    }
}
