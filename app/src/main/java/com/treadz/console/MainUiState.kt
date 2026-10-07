package com.treadz.console

import com.treadz.console.treadmill.BeltStatus
import com.treadz.console.treadmill.CommandResult
import com.treadz.console.treadmill.ConnectionState
import com.treadz.console.treadmill.DiscoveredTreadmill
import com.treadz.console.treadmill.RecordingStatus
import com.treadz.console.treadmill.TreadmillAlert
import com.treadz.console.treadmill.TreadmillState

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
) {
    val isConnected: Boolean get() = connection is ConnectionState.Connected

    /** Live data visible: connected, or reconnecting (the last known values are shown). */
    val showsLiveData: Boolean
        get() = connection is ConnectionState.Connected || connection is ConnectionState.Reconnecting

    val canStart: Boolean
        get() = isConnected && treadmill.status == BeltStatus.Idle && !startInProgress

    /** The screen must stay on while the belt is not stopped. */
    val keepScreenOn: Boolean get() = treadmill.status != BeltStatus.Idle
}
