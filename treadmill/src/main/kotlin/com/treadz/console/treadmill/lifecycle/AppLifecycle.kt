package com.treadz.console.treadmill.lifecycle

import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow

/**
 * App lifecycle as the backend needs it: whether the app is visible, and how to leave the app for good.
 *
 * The backend never scans and never (re)connects while the app is in the background, shows the foreground-service
 * notification only while connected to the treadmill, and lets go of an idle treadmill after a while in the
 * background (see [LifecycleConfig]). That is all driven by [setAppInForeground] and by the connection state: the UI
 * only has to report visibility and call [exitApp].
 */
interface AppLifecycle {

    /** Whether the app is visible. `true` until the UI says otherwise. */
    val appInForeground: StateFlow<Boolean>

    /**
     * The UI reports visibility: `true` when the app comes to the foreground, `false` when its last screen is
     * hidden (e.g. from `ProcessLifecycleOwner` `onStart` / `onStop`, which already ignores configuration changes).
     *
     * Going to the background stops a user-started scan, abandons a connection attempt in progress and pauses an
     * automatic reconnection; coming back resumes the reconnection to the same treadmill.
     */
    fun setAppInForeground(foreground: Boolean)

    /**
     * The one way to leave the app, shared by the Back dialog and the notification's Exit action. Refused while the
     * belt is not idle ([ExitResult.Refused]): the user must press STOP first. Otherwise it disconnects from the
     * treadmill (abandoning any reconnection), removes the notification and emits on [exitRequests], and returns
     * [ExitResult.Exiting]. Calling it while not connected is allowed.
     */
    suspend fun exitApp(): ExitResult

    /**
     * Emitted once per accepted [exitApp], whichever path asked for it (the Back dialog or the notification): the
     * UI reacts by finishing its task (`finishAndRemoveTask()`). No replay: an exit requested while no activity is
     * collecting has nothing to close. Collect it for as long as the activity exists (`lifecycleScope`, not
     * `repeatOnLifecycle`), so that an Exit tapped in the notification also reaches an activity that is stopped.
     */
    val exitRequests: SharedFlow<Unit>
}

/** Result of [AppLifecycle.exitApp]. */
sealed interface ExitResult {

    /** Disconnected and the exit announced on [AppLifecycle.exitRequests]: the UI finishes its task. */
    data object Exiting : ExitResult

    /** Nothing was done. */
    data class Refused(val reason: ExitRefusedReason) : ExitResult
}

/** Why an exit was refused; stable values for the UI to translate. */
enum class ExitRefusedReason {
    /** The belt is moving or a session is open (running, paused, starting, stopping, key removed): press STOP first. */
    BeltNotIdle,
}

/**
 * Timings of the background policy, in milliseconds, measured with the injected clock/virtual time.
 */
data class LifecycleConfig(
    /**
     * Connection lost while the app is in the background: the notification says so for this long, then the
     * service (and its notification) goes away.
     */
    val backgroundLossNoticeMs: Long = 10_000,
    /**
     * Connected, in the background and with the belt idle (status Idle and speed 0) for this long without
     * interruption: disconnect cleanly. Any activity, or the app coming to the foreground, restarts the count.
     */
    val backgroundIdleDisconnectMs: Long = 10 * 60_000,
)

/**
 * What the notification says after the connection was lost while the app was in the background. It is shown for
 * [LifecycleConfig.backgroundLossNoticeMs] and then the service goes away.
 */
enum class ConnectionLossNotice {
    /** The belt was moving or a session was open: the treadmill has stopped by itself. */
    BeltStoppedByItself,

    /** The belt was idle: nothing was happening. */
    Neutral,
}

/** What the controller needs to know about visibility; implemented by `FtmsTreadmillController`. */
interface AppVisibility {
    val appInForeground: StateFlow<Boolean>
    fun setAppInForeground(foreground: Boolean)

    /** Adds a line to the session recording, so that lifecycle decisions can be reconstructed when debugging. */
    fun traceNote(text: String) {}
}
