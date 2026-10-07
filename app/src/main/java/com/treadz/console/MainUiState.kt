package com.treadz.console

import com.treadz.console.treadmill.BeltStatus
import com.treadz.console.treadmill.CommandResult
import com.treadz.console.treadmill.ConnectionState
import com.treadz.console.treadmill.DiscoveredTreadmill
import com.treadz.console.treadmill.RecordingStatus
import com.treadz.console.treadmill.TreadmillAlert
import com.treadz.console.treadmill.TreadmillState
import com.treadz.console.userdata.UserProfile

data class MainUiState(
    val connection: ConnectionState = ConnectionState.Disconnected,
    val treadmill: TreadmillState = TreadmillState(),
    val scanning: Boolean = false,

    /** At least one scan has finished (so that "no treadmills" is only shown then). */
    val scanFinished: Boolean = false,
    val devices: List<DiscoveredTreadmill> = emptyList(),
    /** Name (or address) of the last treadmill used, if any. */
    val rememberedName: String? = null,
    val rememberedAddress: String? = null,
    /** Connection (or scan) error to show on the connection screen. */
    val connectFailure: CommandResult? = null,
    val scanFailure: String? = null,
    val startInProgress: Boolean = false,
    val startFailure: CommandResult? = null,
    /** Full-screen safety alert: the most recent of the controller's pending alerts. */
    val alert: TreadmillAlert? = null,
    val recording: RecordingStatus = RecordingStatus.Idle,
    /** Final recording notice (saved or failed) the user has not closed yet. */
    val recordingNotice: RecordingStatus? = null,
    val darkTheme: Boolean = true,
    /** Who is training. `null` while loading or when nobody is selected: the treadmill cannot be started then. */
    val activeUser: UserProfile? = null,
    /** Workouts recorded with no active user, waiting to be assigned. */
    val unassignedWorkoutCount: Int = 0,
) {
    val isConnected: Boolean get() = connection is ConnectionState.Connected

    /** Live data visible: connected, or reconnecting (the last known values are shown). */
    val showsLiveData: Boolean
        get() = connection is ConnectionState.Connected || connection is ConnectionState.Reconnecting

    val canStart: Boolean
        get() = activeUser != null && isConnected && treadmill.status == BeltStatus.Idle && !startInProgress

    /** The active user can only change while the belt is stopped (a workout belongs to one person). */
    val canSwitchUser: Boolean
        get() = treadmill.status == BeltStatus.Idle && !startInProgress

    /** The screen must stay on while the belt is not stopped. */
    val keepScreenOn: Boolean get() = treadmill.status != BeltStatus.Idle
}
