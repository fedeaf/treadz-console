package com.treadz.console.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.res.stringResource
import com.treadz.console.R
import com.treadz.console.treadmill.FailureCode

/** Translated text for a failure reason; the backend's `reason` is only kept for the recordings. */
@Composable
fun failureText(code: FailureCode): String = stringResource(
    when (code) {
        FailureCode.ConnectionFailed -> R.string.failure_connection_failed
        FailureCode.ControlNotGranted -> R.string.failure_control_not_granted
        FailureCode.AlreadyRunning -> R.string.failure_already_running
        FailureCode.AlreadyStarting -> R.string.failure_already_starting
        FailureCode.SafetyKeyRemoved -> R.string.failure_safety_key_removed
        FailureCode.BeltStillStopping -> R.string.failure_belt_still_stopping
        FailureCode.NothingToResume -> R.string.failure_nothing_to_resume
        FailureCode.BeltNotRunning -> R.string.failure_belt_not_running
        FailureCode.InvalidValue -> R.string.failure_invalid_value
        FailureCode.NoResponse -> R.string.failure_no_response
        FailureCode.RejectedByTreadmill -> R.string.failure_rejected_by_treadmill
        FailureCode.WriteFailed -> R.string.failure_write_failed
        FailureCode.NotStarted -> R.string.failure_not_started
        FailureCode.StopNotConfirmed -> R.string.failure_stop_not_confirmed
        FailureCode.StillMoving -> R.string.failure_still_moving
        FailureCode.TargetNotReached -> R.string.failure_target_not_reached
        FailureCode.BeltStopped -> R.string.failure_belt_stopped
        FailureCode.DataLost -> R.string.failure_data_lost
        FailureCode.ConnectionLost -> R.string.failure_connection_lost
        FailureCode.Preempted -> R.string.failure_preempted
        FailureCode.InternalError -> R.string.failure_internal_error
    },
)
