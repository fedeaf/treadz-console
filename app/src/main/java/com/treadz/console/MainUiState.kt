package com.treadz.console

import com.treadz.console.controls.ControlsState
import com.treadz.console.controls.ManualControls
import com.treadz.console.controls.SessionControls
import com.treadz.console.sync.SyncError
import com.treadz.console.treadmill.BeltStatus
import com.treadz.console.treadmill.CommandResult
import com.treadz.console.treadmill.ConnectionProblem
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
    /**
     * Name of the last treadmill used, `null` if it never had one. Never replaced by the address: the address is not
     * something to show the user.
     */
    val rememberedName: String? = null,
    val rememberedAddress: String? = null,
    /** Why the last scan or connection started from the connection screen failed, if it did. */
    val problem: ConnectionProblem? = null,
    /** Whether Bluetooth is on. While it is off nothing is scanned or connected. */
    val bluetoothEnabled: Boolean = true,
    /** Why the controller's latest (re)connection attempt failed; explains a long "Reconnecting". */
    val reconnectProblem: ConnectionProblem? = null,
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
    /** The last synchronisation attempt failed with this (only while linked): a discreet mark on the settings button. */
    val syncError: SyncError? = null,
    /** Pending targets, commands in flight and the last failure of the manual controls. */
    val controls: ControlsState = ControlsState(),
    /** What a program or fitness test in progress allows the manual controls to do. */
    val session: SessionControls = SessionControls(),
) {
    val isConnected: Boolean get() = connection is ConnectionState.Connected

    val isReconnecting: Boolean get() = connection is ConnectionState.Reconnecting

    /** Reconnecting with Bluetooth off: the controller waits and resumes by itself when it is turned on. */
    val waitingForBluetooth: Boolean get() = isReconnecting && !bluetoothEnabled

    /**
     * The reconnection is not working out: the treadmill was not found or several attempts have failed. The UI makes the
     * explanation and the way out ("Stop reconnecting") more prominent then.
     */
    val reconnectStuck: Boolean
        get() = (connection as? ConnectionState.Reconnecting)?.let { c ->
            bluetoothEnabled && (reconnectProblem == ConnectionProblem.DeviceNotFound || c.attempt >= STUCK_RECONNECT_ATTEMPTS)
        } ?: false

    /** Live data visible: connected, or reconnecting (the last known values are shown). */
    val showsLiveData: Boolean
        get() = connection is ConnectionState.Connected || connection is ConnectionState.Reconnecting

    /**
     * Name to show for the connected treadmill. Android gives no name when it reconnects by itself, so the remembered one
     * (same address) is used then.
     */
    val connectedName: String?
        get() = (connection as? ConnectionState.Connected)?.let { c ->
            c.name ?: rememberedName.takeIf { c.address == rememberedAddress }
        }

    val canStart: Boolean
        get() = activeUser != null && isConnected && !beltActive && !startInProgress

    /** The active user can only change while the belt is stopped (a workout belongs to one person). */
    val canSwitchUser: Boolean
        get() = treadmill.status == BeltStatus.Idle && !startInProgress

    /** Settings are reachable only with the belt at rest, like switching user. */
    val canOpenSettings: Boolean get() = canSwitchUser

    /** There is a remembered treadmill and the belt is at rest. */
    val canForgetTreadmill: Boolean get() = rememberedAddress != null && !beltActive && !startInProgress

    /**
     * The belt is not at rest: a session is on (starting, running, paused, key removed) or it is still decelerating
     * after a stop. While it holds, STOP is on every screen.
     */
    val beltActive: Boolean
        get() = treadmill.status != BeltStatus.Idle || treadmill.speedKmh > ManualControls.STOPPED_SPEED_KMH

    /** STOP can be pressed: the belt is active and the connection is up. Never depends on the active user. */
    val showStop: Boolean get() = beltActive && isConnected

    /** The belt may be moving but the app cannot reach the treadmill: STOP is replaced by a message. */
    val stopUnreachable: Boolean get() = beltActive && !isConnected

    /** Commands other than STOP: an active user, a connection and current data. */
    val canOperate: Boolean get() = activeUser != null && isConnected && !treadmill.isStale

    /** Speed and incline controls: only with the belt running. */
    val canAdjust: Boolean get() = canOperate && treadmill.status == BeltStatus.Running

    /** The speed controls: also locked by an interrupted program or a fitness test (see [session]). */
    val canAdjustSpeed: Boolean get() = canAdjust && session.speed

    val canAdjustIncline: Boolean get() = canAdjust && session.incline

    val canPause: Boolean get() = canOperate && treadmill.status == BeltStatus.Running && !controls.pauseInFlight && session.pause

    /** Resume only once the belt has stopped: the treadmill refuses before that. */
    val canResume: Boolean
        get() = canOperate && treadmill.status == BeltStatus.Paused &&
            treadmill.speedKmh <= ManualControls.STOPPED_SPEED_KMH && !controls.pauseInFlight

    /** Paused but the belt is still slowing down: Resume waits. */
    val resumeWaitsForBelt: Boolean
        get() = treadmill.status == BeltStatus.Paused && treadmill.speedKmh > ManualControls.STOPPED_SPEED_KMH

    val isPaused: Boolean get() = treadmill.status == BeltStatus.Paused

    /** Target speed to show next to the real one: what the user is asking for, or what the treadmill is heading to. */
    val targetSpeed: Double?
        get() = if (treadmill.status == BeltStatus.Running) controls.pendingSpeed ?: treadmill.targetSpeedKmh else null

    val targetIncline: Double?
        get() = if (treadmill.status == BeltStatus.Running) controls.pendingIncline else null

    /** The screen must stay on while the belt is not stopped. */
    val keepScreenOn: Boolean get() = beltActive

    companion object {
        /** From this reconnection attempt on, the treadmill is assumed to be off or out of range. */
        const val STUCK_RECONNECT_ATTEMPTS = 4
    }
}
