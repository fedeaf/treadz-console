package com.treadz.console.treadmill.notification

import com.treadz.console.treadmill.BeltStatus
import com.treadz.console.treadmill.CommandResult
import com.treadz.console.treadmill.ConnectionState
import com.treadz.console.treadmill.TreadmillAlert
import com.treadz.console.treadmill.TreadmillEvent
import com.treadz.console.treadmill.TreadmillState
import com.treadz.console.treadmill.lifecycle.ConnectionLossNotice

/**
 * What the notification of the foreground service says. The Android layer maps each one to a string resource.
 * There is no message for "not connected": the notification exists only while connected (plus the short notice after
 * a connection loss in the background).
 */
enum class NotificationMessage {
    /** Connected, belt stopped. */
    Idle,
    Starting,
    Running,
    Paused,

    /** A pause is decelerating the belt. */
    Pausing,

    /** A stop is decelerating the belt (or has just been requested). */
    Stopping,
    KeyRemoved,

    /** A stop or pause was not carried out and the belt may still be moving: use the console's STOP or the safety key. */
    StopFailed,

    /** No current data while the belt may be moving: use the console's STOP or the safety key. */
    DataStalled,

    /** The connection was lost while the app was in the background and the belt was not idle: it stopped by itself. */
    ConnectionLostBeltStopped,

    /** The connection was lost while the app was in the background and the belt was idle. */
    ConnectionLost,
}

/**
 * Stop requested from the notification, as the service tracks it. The controller's own critical alert
 * (`CommandNotExecuted`) is what raises the alarm; this only covers the gap until the controller reports it and the
 * results that raise no alert (e.g. a stop that was not allowed).
 */
enum class StopRequest {
    None,

    /** The STOP action was tapped and the result has not arrived yet. */
    InFlight,

    /** The last stop from the notification failed. */
    Failed,
    ;

    companion object {
        /** The request state after `stop()` returned [result]. */
        fun afterResult(result: CommandResult): StopRequest = when (result) {
            CommandResult.Success, CommandResult.NotConnected -> None
            is CommandResult.Failure -> Failed
        }
    }

    /** A failed stop is only relevant while the belt is moving: once it is not, the next session starts clean. */
    fun settle(state: TreadmillState): StopRequest = if (this == Failed && !state.isBeltMoving()) None else this
}

/** The notification content, independent of Android. Equal models need no notification update. */
data class ServiceNotificationModel(
    val message: NotificationMessage,
    /** Belt speed in km/h rounded to 0.1, for the messages that show it. */
    val speedKmh: Double? = null,
    /** Show the STOP action (it calls `TreadmillController.stop()`). */
    val showStopAction: Boolean = false,
    /**
     * Show the Exit action (it calls `AppLifecycle.exitApp()`). Only while the belt is idle, and never together with
     * [showStopAction].
     */
    val showExitAction: Boolean = false,
    /** The user must notice this now (failure to stop): the Android layer also raises a high-priority alert. */
    val urgent: Boolean = false,
)

object ServiceNotificationMapper {

    /**
     * The notification for the given situation, or `null` when there must be none: the notification is shown only
     * while [ConnectionState.Connected], plus the [lossNotice] after a connection loss in the background (the
     * lifecycle manager removes that one after a few seconds). While scanning, connecting, reconnecting, disconnected
     * or with Bluetooth off there is no notification.
     */
    fun map(
        connection: ConnectionState,
        state: TreadmillState,
        alerts: List<TreadmillAlert>,
        stopRequest: StopRequest,
        lossNotice: ConnectionLossNotice? = null,
    ): ServiceNotificationModel? {
        if (connection !is ConnectionState.Connected) {
            return when (lossNotice) {
                ConnectionLossNotice.BeltStoppedByItself -> ServiceNotificationModel(NotificationMessage.ConnectionLostBeltStopped)
                ConnectionLossNotice.Neutral -> ServiceNotificationModel(NotificationMessage.ConnectionLost)
                null -> null
            }
        }
        val speed = Math.round(state.speedKmh * 10) / 10.0
        val moving = state.isBeltMoving()
        val stopCanBeSent = state.status == BeltStatus.Starting || state.status == BeltStatus.Running || state.status == BeltStatus.Paused

        if (state.status == BeltStatus.SafetyKeyRemoved) return ServiceNotificationModel(NotificationMessage.KeyRemoved)

        // Failures while the belt may be moving come first and ask for the console's STOP / the safety key.
        val stopFailed = stopRequest == StopRequest.Failed || alerts.any { it is TreadmillEvent.CommandNotExecuted && it.command in STOP_COMMANDS }
        if (moving && stopFailed) {
            return ServiceNotificationModel(NotificationMessage.StopFailed, speedKmh = speed, showStopAction = true, urgent = true)
        }
        val dataStalled = TreadmillEvent.DataStalled in alerts || state.isStale
        if (dataStalled && (moving || state.status == BeltStatus.Running)) {
            return ServiceNotificationModel(NotificationMessage.DataStalled, speedKmh = speed, showStopAction = stopCanBeSent, urgent = true)
        }

        val stopping = stopRequest == StopRequest.InFlight
        val message = when (state.status) {
            BeltStatus.Idle -> if (moving) NotificationMessage.Stopping else NotificationMessage.Idle
            BeltStatus.Starting -> if (stopping) NotificationMessage.Stopping else NotificationMessage.Starting
            BeltStatus.Running -> if (stopping) NotificationMessage.Stopping else NotificationMessage.Running
            BeltStatus.Paused -> when {
                stopping -> NotificationMessage.Stopping
                moving -> NotificationMessage.Pausing
                else -> NotificationMessage.Paused
            }
            BeltStatus.SafetyKeyRemoved -> NotificationMessage.KeyRemoved
        }
        val showsSpeed = message == NotificationMessage.Running || message == NotificationMessage.Pausing || message == NotificationMessage.Stopping
        return ServiceNotificationModel(
            message,
            speedKmh = if (showsSpeed) speed else null,
            showStopAction = stopCanBeSent,
            // Exit only when the belt is completely idle; STOP and Exit are mutually exclusive by construction.
            showExitAction = message == NotificationMessage.Idle && !stopCanBeSent,
        )
    }

    private val STOP_COMMANDS = setOf("stop", "pause")
}

private fun TreadmillState.isBeltMoving(): Boolean = speedKmh > 0.0 || status == BeltStatus.Starting
