package com.treadz.console.treadmill.android

import android.content.Context
import android.content.Intent
import com.treadz.console.treadmill.RecordingInfo
import com.treadz.console.treadmill.CommandResult
import com.treadz.console.treadmill.DisabledWorkoutRecorder
import com.treadz.console.treadmill.TreadmillController
import com.treadz.console.treadmill.WorkoutRecorder
import com.treadz.console.treadmill.control.ControllerObserver
import com.treadz.console.treadmill.lifecycle.AppLifecycle

/**
 * The app's access point to the real backend. Keeps a single instance of the controller and the recorder,
 * tied to a foreground service that keeps the connection alive with the screen off.
 *
 * The service ([TreadZService]) runs only while connected to the treadmill (and for a few seconds after a connection
 * loss in the background); the backend starts and stops it by itself. The [BlePermissions] permissions must be granted
 * before connecting.
 */
object TreadZTreadmill {

    @Volatile
    private var backend: AndroidBackend? = null

    internal fun backend(context: Context): AndroidBackend =
        backend ?: synchronized(this) { backend ?: AndroidBackend(context.applicationContext).also { backend = it } }

    fun controller(context: Context): TreadmillController = backend(context).controller

    /** The debug recording of the sessions; [DisabledWorkoutRecorder] in release builds, which record nothing. */
    fun recorder(context: Context): WorkoutRecorder = backend(context).recorder ?: DisabledWorkoutRecorder

    /**
     * Where other parts of the app add records to the session recording (`TraceRecord.External`), e.g. the heart-rate
     * source in use: `AutomaticHeartRateSource(..., trace = TreadZTreadmill.recordingSink(context))`. Thread-safe. The
     * strap connected to the app (`TreadZHeartRate`) is recorded automatically. In release builds it discards everything.
     */
    fun recordingSink(context: Context): ControllerObserver = backend(context).recorder ?: ControllerObserver {}

    /**
     * Sends the notification's STOP through the app instead of straight to [TreadmillController.stop], e.g. to the
     * program or fitness test in progress so that it ends as with the app's own STOP. [stop] must stop the belt in every
     * case (through the controller); if it throws, the controller is stopped directly. `null` restores the direct STOP.
     */
    fun routeNotificationStop(context: Context, stop: (suspend () -> CommandResult)?) {
        backend(context).notificationStop = stop
    }

    /**
     * App visibility and exit: the UI reports [AppLifecycle.setAppInForeground] (from `ProcessLifecycleOwner`) and
     * calls [AppLifecycle.exitApp] from its Back dialog; the notification's Exit action calls the same function.
     */
    fun lifecycle(context: Context): AppLifecycle = backend(context).lifecycle

    /**
     * Android Share menu Intent for a saved recording's file. It is already the chooser
     * (`Intent.createChooser`) with read permission on the file for the target app: just
     * `startActivity(shareIntent(...))`.
     */
    fun shareIntent(context: Context, recording: RecordingInfo): Intent =
        RecordingShare.intent(context.applicationContext, recording)
}
