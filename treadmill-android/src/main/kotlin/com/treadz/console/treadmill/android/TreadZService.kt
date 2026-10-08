package com.treadz.console.treadmill.android

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.graphics.drawable.Icon
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.treadz.console.treadmill.CommandResult
import com.treadz.console.treadmill.FailureCode
import com.treadz.console.treadmill.TreadmillController
import com.treadz.console.treadmill.notification.NotificationMessage
import com.treadz.console.treadmill.notification.ServiceNotificationMapper
import com.treadz.console.treadmill.notification.ServiceNotificationModel
import com.treadz.console.treadmill.notification.StopRequest
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch

/**
 * Foreground service (`connectedDevice` type) that keeps the process, the BLE connection and the
 * controller alive with the screen off. The controller lives in [AndroidBackend]; the service only prevents
 * Android from killing the process, keeps the CPU awake (partial wake lock) and shows the state in the
 * notification. [AndroidBackend] starts and stops it: it runs only while connected to the treadmill (plus a few seconds
 * with a "connection lost" notice after a loss while the app is in the background), never while scanning, connecting,
 * reconnecting or disconnected. If it finds nothing to show it stops itself.
 *
 * The notification (public on the lock screen) carries a STOP action while the belt is not idle, so the belt can be
 * stopped with the screen off or while the app shows another screen, and an Exit action while the belt is idle
 * (disconnect, remove the notification and close the app). Never both at once. The action calls
 * [TreadmillController.stop] (never the Control Point directly) and, if the stop cannot be verified, the notification
 * says so and a high-priority alert asks the user to press the console's STOP or pull the safety key. What the
 * notification says is decided by [ServiceNotificationMapper] (pure logic, tested in `:treadmill`).
 *
 * Without the POST_NOTIFICATIONS permission the service still runs but Android does not show the notification, so
 * neither the STOP action nor the alert is available: the app's own screens and the console are then the only controls.
 */
class TreadZService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var wakeLock: PowerManager.WakeLock? = null
    private var controller: TreadmillController? = null
    private var backend: AndroidBackend? = null

    /** The STOP requested from the notification: a tap while one is in flight is ignored. */
    private val stopRequest = MutableStateFlow(StopRequest.None)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.treadz_service_channel), NotificationManager.IMPORTANCE_LOW).apply {
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                setShowBadge(false)
            },
        )
        manager.createNotificationChannel(
            NotificationChannel(ALERT_CHANNEL_ID, getString(R.string.treadz_alert_channel), NotificationManager.IMPORTANCE_HIGH).apply {
                lockscreenVisibility = Notification.VISIBILITY_PUBLIC
                setShowBadge(false)
            },
        )
        val backend = TreadZTreadmill.backend(this)
        this.backend = backend
        val controller = backend.controller
        this.controller = controller
        // startForeground must follow startForegroundService at once, so the first notification comes from the current
        // state; if there is nothing to show any more, a bare notification stands in until the service stops itself.
        val initial = ServiceNotificationMapper.map(
            controller.connection.value, controller.state.value, controller.pendingAlerts.value, StopRequest.None,
            backend.lifecycle.lossNotice.value,
        )
        try {
            ServiceCompat.startForeground(
                this, NOTIFICATION_ID, initial?.let(::notification) ?: placeholderNotification(),
                ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE,
            )
        } catch (e: Exception) {
            // Android forbids it without BLUETOOTH_CONNECT or with the app in the background: without the service
            // it keeps working while the app is visible.
            Log.e(TAG, "Could not start the foreground service", e)
            stopSelf()
            return
        }
        val power = getSystemService(PowerManager::class.java)
        wakeLock = power.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "TreadZ:connection").apply {
            setReferenceCounted(false)
            acquire()
        }
        scope.launch {
            combine(
                controller.connection, controller.state, controller.pendingAlerts, stopRequest, backend.lifecycle.lossNotice,
            ) { connection, state, alerts, request, lossNotice ->
                // A failed stop is forgotten once the belt no longer moves, so it cannot reappear in the next session.
                val settled = request.settle(state)
                if (settled != request) stopRequest.compareAndSet(request, settled)
                ServiceNotificationMapper.map(connection, state, alerts, settled, lossNotice)
            }.distinctUntilChanged().collect { model ->
                if (model == null) {
                    // Nothing to show (not connected): the service has no reason to exist.
                    Log.i(TAG, "No notification to show: stopping the service")
                    stopSelf()
                } else {
                    render(manager, model)
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> stopFromNotification()
            ACTION_EXIT -> exitFromNotification()
        }
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        getSystemService(NotificationManager::class.java).cancel(ALERT_NOTIFICATION_ID)
        scope.cancel()
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        super.onDestroy()
    }

    /**
     * The STOP action: asks the controller to stop the belt. A second tap while the first stop is still being
     * verified does nothing (the controller would also join them into a single command).
     */
    private fun stopFromNotification() {
        val controller = controller ?: return
        if (!stopRequest.compareAndSet(StopRequest.None, StopRequest.InFlight) &&
            !stopRequest.compareAndSet(StopRequest.Failed, StopRequest.InFlight)
        ) {
            Log.i(TAG, "STOP tapped while a stop is already in progress: ignored")
            return
        }
        scope.launch {
            val result = try {
                controller.stop()
            } catch (e: Exception) {
                Log.e(TAG, "stop() from the notification failed", e)
                CommandResult.NotVerified(FailureCode.InternalError, "stop() threw: $e")
            }
            Log.i(TAG, "STOP from the notification: $result")
            stopRequest.value = StopRequest.afterResult(result)
        }
    }

    /**
     * The Exit action: the same `exitApp()` as the app's Back dialog (it refuses while the belt is not idle). It runs
     * in the backend's scope because the service goes away as soon as the treadmill is disconnected.
     */
    private fun exitFromNotification() {
        val backend = backend ?: return
        backend.scope.launch {
            val result = try {
                backend.lifecycle.exitApp()
            } catch (e: Exception) {
                Log.e(TAG, "exitApp() from the notification failed", e)
                return@launch
            }
            Log.i(TAG, "Exit from the notification: $result")
        }
    }

    private fun render(manager: NotificationManager, model: ServiceNotificationModel) {
        manager.notify(NOTIFICATION_ID, notification(model))
        if (model.urgent) manager.notify(ALERT_NOTIFICATION_ID, alertNotification(model)) else manager.cancel(ALERT_NOTIFICATION_ID)
    }

    private fun text(model: ServiceNotificationModel): String = when (model.message) {
        NotificationMessage.Idle -> getString(R.string.treadz_service_idle)
        NotificationMessage.Starting -> getString(R.string.treadz_service_starting)
        NotificationMessage.Running -> getString(R.string.treadz_service_running, model.speedKmh ?: 0.0)
        NotificationMessage.Paused -> getString(R.string.treadz_service_paused)
        NotificationMessage.Pausing -> getString(R.string.treadz_service_pausing, model.speedKmh ?: 0.0)
        NotificationMessage.Stopping -> getString(R.string.treadz_service_stopping, model.speedKmh ?: 0.0)
        NotificationMessage.KeyRemoved -> getString(R.string.treadz_service_key_removed)
        NotificationMessage.StopFailed -> getString(R.string.treadz_service_stop_failed)
        NotificationMessage.DataStalled -> getString(R.string.treadz_service_data_stalled)
        NotificationMessage.ConnectionLostBeltStopped -> getString(R.string.treadz_service_connection_lost_stopped)
        NotificationMessage.ConnectionLost -> getString(R.string.treadz_service_connection_lost)
    }

    private fun openAppIntent(): PendingIntent? {
        val launch = packageManager.getLaunchIntentForPackage(packageName) ?: return null
        return PendingIntent.getActivity(this, 0, launch, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    /**
     * The STOP action. It targets this service (not an activity), so it works from the lock screen without unlocking.
     */
    private fun stopAction(): Notification.Action {
        val intent = Intent(this, TreadZService::class.java).setAction(ACTION_STOP)
        val pending = PendingIntent.getService(this, REQUEST_STOP, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val icon = Icon.createWithResource(this, android.R.drawable.ic_menu_close_clear_cancel)
        return Notification.Action.Builder(icon, getString(R.string.treadz_action_stop), pending).build()
    }

    /** The Exit action. It targets this service, like STOP. */
    private fun exitAction(): Notification.Action {
        val intent = Intent(this, TreadZService::class.java).setAction(ACTION_EXIT)
        val pending = PendingIntent.getService(this, REQUEST_EXIT, intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        val icon = Icon.createWithResource(this, android.R.drawable.ic_menu_close_clear_cancel)
        return Notification.Action.Builder(icon, getString(R.string.treadz_action_exit), pending).build()
    }

    /** Stands in for the first notification when there is nothing to show; the service stops right after. */
    private fun placeholderNotification(): Notification =
        Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle(getString(R.string.treadz_service_title))
            .setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_DEFERRED)
            .build()

    private fun notification(model: ServiceNotificationModel): Notification {
        val text = text(model)
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle(getString(R.string.treadz_service_title))
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(if (model.urgent) Notification.CATEGORY_ALARM else Notification.CATEGORY_SERVICE)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .setForegroundServiceBehavior(Notification.FOREGROUND_SERVICE_IMMEDIATE)
            .apply {
                openAppIntent()?.let { setContentIntent(it) }
                if (model.showStopAction) addAction(stopAction())
                if (model.showExitAction) addAction(exitAction())
            }
            .build()
    }

    /** Heads-up alert for a stop that could not be verified: it makes sound and shows on the lock screen. */
    private fun alertNotification(model: ServiceNotificationModel): Notification {
        val text = text(model)
        return Notification.Builder(this, ALERT_CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_warning)
            .setContentTitle(getString(R.string.treadz_alert_title))
            .setContentText(text)
            .setStyle(Notification.BigTextStyle().bigText(text))
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_ALARM)
            .setVisibility(Notification.VISIBILITY_PUBLIC)
            .apply {
                openAppIntent()?.let { setContentIntent(it) }
                if (model.showStopAction) addAction(stopAction())
            }
            .build()
    }

    companion object {
        private const val TAG = "TreadZService"
        private const val CHANNEL_ID = "treadz_connection"
        private const val ALERT_CHANNEL_ID = "treadz_alerts"
        private const val NOTIFICATION_ID = 1
        private const val ALERT_NOTIFICATION_ID = 2
        private const val REQUEST_STOP = 1
        private const val REQUEST_EXIT = 2
        private const val ACTION_STOP = "com.treadz.console.treadmill.android.action.STOP"
        private const val ACTION_EXIT = "com.treadz.console.treadmill.android.action.EXIT"

        internal fun start(context: Context) {
            try {
                ContextCompat.startForegroundService(context, Intent(context, TreadZService::class.java))
            } catch (e: Exception) {
                Log.e(TAG, "Could not start the service", e)
            }
        }

        internal fun stop(context: Context) {
            context.stopService(Intent(context, TreadZService::class.java))
        }
    }
}
