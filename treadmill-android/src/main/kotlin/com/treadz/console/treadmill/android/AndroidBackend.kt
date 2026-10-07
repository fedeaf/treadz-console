package com.treadz.console.treadmill.android

import android.content.Context
import android.os.Build
import android.util.Log
import com.treadz.console.treadmill.CommandResult
import com.treadz.console.treadmill.ConnectionState
import com.treadz.console.treadmill.TreadmillController
import com.treadz.console.treadmill.control.Clock
import com.treadz.console.treadmill.control.ControllerConfig
import com.treadz.console.treadmill.control.FtmsTreadmillController
import com.treadz.console.treadmill.recording.JsonlWorkoutRecorder
import com.treadz.console.treadmill.recording.RecorderConfig
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * The whole backend as a single per-process instance: BLE transport, controller with its safety
 * layer and recorder. It lives as long as the process; the foreground service ([TreadZService]) is what
 * prevents Android from killing it with the screen off.
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

    /** What the UI sees: the controller, with the foreground service tied to the connection. */
    val controller: TreadmillController = ServiceBoundController(inner, appContext, recorder)

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
 * Delegates everything to the real controller and keeps the foreground service running while there is a connection (or
 * an attempt): it starts before connecting and stops on disconnect or if the connection fails.
 */
internal class ServiceBoundController(
    private val inner: FtmsTreadmillController,
    private val context: Context,
    private val recorder: JsonlWorkoutRecorder,
) : TreadmillController by inner {

    override suspend fun connect(address: String): CommandResult {
        TreadZService.start(context)
        val result = inner.connect(address)
        if (inner.connection.value is ConnectionState.Disconnected) TreadZService.stop(context)
        return result
    }

    override suspend fun disconnect() {
        inner.disconnect()
        recorder.finish("disconnected")
        TreadZService.stop(context)
    }
}
