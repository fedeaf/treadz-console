package com.treadz.console.treadmill.android

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import com.treadz.console.treadmill.BeltStatus
import com.treadz.console.treadmill.ConnectionState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch

/**
 * Foreground service (`connectedDevice` type) that keeps the process, the BLE connection and the
 * controller alive with the screen off. It holds no logic: the controller lives in [AndroidBackend]; the
 * service only prevents Android from killing the process, keeps the CPU awake (partial wake lock) and shows
 * the state in the notification. [ServiceBoundController] starts and stops it.
 */
class TreadZService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private var wakeLock: PowerManager.WakeLock? = null
    private var lastText: String? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        val manager = getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL_ID, getString(R.string.treadz_service_channel), NotificationManager.IMPORTANCE_LOW),
        )
        try {
            ServiceCompat.startForeground(
                this, NOTIFICATION_ID, notification(getString(R.string.treadz_service_connecting)),
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
        val controller = TreadZTreadmill.controller(this)
        scope.launch {
            combine(controller.connection, controller.state) { c, s -> describe(c, s) }.collect { text ->
                if (text != lastText) {
                    lastText = text
                    manager.notify(NOTIFICATION_ID, notification(text))
                }
            }
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int = START_NOT_STICKY

    override fun onDestroy() {
        scope.cancel()
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        super.onDestroy()
    }

    private fun describe(c: ConnectionState, s: com.treadz.console.treadmill.TreadmillState): String = when (c) {
        is ConnectionState.Disconnected -> getString(R.string.treadz_service_disconnected)
        is ConnectionState.Connecting -> getString(R.string.treadz_service_connecting)
        is ConnectionState.Reconnecting -> getString(R.string.treadz_service_reconnecting, c.attempt)
        is ConnectionState.Connected -> when (s.status) {
            BeltStatus.Idle -> getString(R.string.treadz_service_idle)
            BeltStatus.Starting -> getString(R.string.treadz_service_starting)
            BeltStatus.Running -> getString(R.string.treadz_service_running, s.speedKmh)
            BeltStatus.Paused -> getString(R.string.treadz_service_paused)
            BeltStatus.SafetyKeyRemoved -> getString(R.string.treadz_service_key_removed)
        }
    }

    private fun notification(text: String): Notification {
        val launch = packageManager.getLaunchIntentForPackage(packageName)
        val pending = launch?.let {
            PendingIntent.getActivity(this, 0, it, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
        }
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
            .setContentTitle("TreadZ Console")
            .setContentText(text)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setCategory(Notification.CATEGORY_SERVICE)
            .apply { if (pending != null) setContentIntent(pending) }
            .build()
    }

    companion object {
        private const val TAG = "TreadZService"
        private const val CHANNEL_ID = "treadz_connection"
        private const val NOTIFICATION_ID = 1

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
