package com.treadz.console.treadmill.android

import android.content.Context
import android.content.Intent
import com.treadz.console.treadmill.RecordingInfo
import com.treadz.console.treadmill.TreadmillController
import com.treadz.console.treadmill.WorkoutRecorder

/**
 * The app's access point to the real backend. Keeps a single instance of the controller and the recorder,
 * tied to a foreground service that keeps the connection alive with the screen off.
 *
 * The service ([TreadZService]) starts when the controller's `connect()` is called and stops with `disconnect()`.
 * The [BlePermissions] permissions must be granted before connecting.
 */
object TreadZTreadmill {

    @Volatile
    private var backend: AndroidBackend? = null

    private fun backend(context: Context): AndroidBackend =
        backend ?: synchronized(this) { backend ?: AndroidBackend(context.applicationContext).also { backend = it } }

    fun controller(context: Context): TreadmillController = backend(context).controller

    fun recorder(context: Context): WorkoutRecorder = backend(context).recorder

    /**
     * Android Share menu Intent for a saved recording's file. It is already the chooser
     * (`Intent.createChooser`) with read permission on the file for the target app: just
     * `startActivity(shareIntent(...))`.
     */
    fun shareIntent(context: Context, recording: RecordingInfo): Intent =
        RecordingShare.intent(context.applicationContext, recording)
}
