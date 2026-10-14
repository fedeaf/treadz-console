package com.treadz.console.hrm.android

import android.content.Context
import android.util.Log
import com.treadz.console.hrm.HeartRateMonitor
import com.treadz.console.hrm.StrapHeartRateMonitor
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

/**
 * The app's access point to the heart-rate strap connected directly to the app: a single [HeartRateMonitor] per
 * process, over the real BLE transport. Independent of the treadmill.
 *
 * Usage: as soon as the app opens (before the treadmill console can grab the strap) call
 * `TreadZHeartRate.monitor(context).use(address)` with the active user's strap (stored by the user data layer);
 * `use(null)` when the user has none or changes user. The monitor searches and reconnects by itself, silently; a strap
 * held by the console is just "searching". [HrmPermissions] must be granted (the same Bluetooth permissions as the
 * treadmill).
 *
 * **Screen off**: the connection lives in this process. While the treadmill is connected its foreground service keeps
 * the process alive, so the strap stays connected during treadmill workouts. Workouts without the treadmill will need
 * their own foreground service (not part of this module yet).
 */
object TreadZHeartRate {

    @Volatile
    private var monitor: StrapHeartRateMonitor? = null

    private val scope = CoroutineScope(
        SupervisorJob() + Dispatchers.Default + CoroutineExceptionHandler { _, e -> Log.e("TreadZHrm", "Unhandled error", e) },
    )

    fun monitor(context: Context): HeartRateMonitor =
        monitor ?: synchronized(this) {
            monitor ?: StrapHeartRateMonitor(AndroidHrmTransport(context.applicationContext), scope).also { monitor = it }
        }
}
