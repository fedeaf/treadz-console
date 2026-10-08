package com.treadz.console.treadmill.android

import android.app.ActivityManager
import android.content.Context
import android.os.Build
import android.util.Log
import com.treadz.console.treadmill.TreadmillController
import com.treadz.console.treadmill.control.Clock
import com.treadz.console.treadmill.control.ControllerConfig
import com.treadz.console.treadmill.control.FtmsTreadmillController
import com.treadz.console.treadmill.lifecycle.AppLifecycleManager
import com.treadz.console.treadmill.recording.JsonlWorkoutRecorder
import com.treadz.console.treadmill.recording.RecorderConfig
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * The whole backend as a single per-process instance: BLE transport, controller with its safety
 * layer, recorder and app lifecycle policy. It lives as long as the process; the foreground service ([TreadZService]),
 * present only while connected, is what prevents Android from killing it with the screen off.
 */
internal class AndroidBackend(context: Context) {
    private val appContext = context.applicationContext

    val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, e -> Log.e(TAG, "Unhandled error", e) },
    )

    val recorder = JsonlWorkoutRecorder(
        storage = MediaStoreRecordingStorage(appContext),
        clock = Clock.System,
        config = RecorderConfig(
            appName = "TreadZ Console",
            appVersion = appVersion(),
            phoneModel = "${Build.MANUFACTURER} ${Build.MODEL}",
            androidVersion = "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
        ),
    )

    private val inner = FtmsTreadmillController(
        transport = AndroidBleTransport(appContext),
        scope = scope,
        clock = Clock.System,
        config = ControllerConfig(),
        observers = listOf(recorder),
    )

    /** What the UI sees: the controller, closing the recording when the user disconnects. */
    val controller: TreadmillController = RecordingController(inner, recorder)

    /** Visibility, exit and the background policy; its decisions drive the foreground service below. */
    val lifecycle = AppLifecycleManager(controller, inner, scope)

    init {
        // The foreground service (and its notification) exists only while connected to the treadmill, plus the short
        // "connection lost" notice after a loss in the background. It is started while the app is visible, which is
        // when connections are made (nothing connects in the background), as Android 14+ requires.
        scope.launch {
            // Changes only (the flow is distinct); the first value is "not wanted" and needs no stop.
            var first = true
            lifecycle.serviceWanted.collect { wanted ->
                if (wanted) TreadZService.start(appContext) else if (!first) TreadZService.stop(appContext)
                first = false
            }
        }
        // Whoever asked for the exit (Back dialog or notification), the app's task is closed as well: this covers an
        // activity that is stopped and not collecting `exitRequests`.
        scope.launch { lifecycle.exitRequests.collect { closeAppTasks() } }
    }

    private fun closeAppTasks() {
        try {
            appContext.getSystemService(ActivityManager::class.java).appTasks.forEach { it.finishAndRemoveTask() }
        } catch (e: Exception) {
            Log.e(TAG, "Could not close the app's task", e)
        }
    }

    private fun appVersion(): String = try {
        appContext.packageManager.getPackageInfo(appContext.packageName, 0).versionName ?: "?"
    } catch (_: Exception) {
        "?"
    }

    private companion object {
        const val TAG = "TreadZ"
    }
}

/**
 * Delegates everything to the real controller and closes the recording's session when the user disconnects. It does
 * not touch the foreground service: that follows the connection state ([AppLifecycleManager.serviceWanted]).
 */
internal class RecordingController(
    private val inner: FtmsTreadmillController,
    private val recorder: JsonlWorkoutRecorder,
) : TreadmillController by inner {

    override suspend fun disconnect() {
        inner.disconnect()
        recorder.finish("disconnected")
    }
}
