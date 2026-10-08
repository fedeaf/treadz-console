package com.treadz.console

import android.app.Application
import android.util.Log
import androidx.lifecycle.ProcessLifecycleOwner
import com.treadz.console.lifecycle.ForegroundReporter
import com.treadz.console.treadmill.android.TreadZTreadmill
import com.treadz.console.userdata.TreadZUserData
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class TreadZApplication : Application() {
    val dependencies: AppDependencies by lazy { RealDependencies(this) }

    /** Lives as long as the process: it hosts the workout history observer. */
    private val processScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    override fun onCreate() {
        super.onCreate()
        // Records every treadmill session into the workout history, whatever screen is open. It only observes the
        // controller and is never stopped on disconnect.
        TreadZUserData.get(this)
            .workoutObserver(TreadZTreadmill.controller(this), onError = { Log.e(TAG, "Workout history error", it) })
            .start(processScope)
        // The backend needs to know whether the app is visible (no scanning or connecting in the background).
        ForegroundReporter(dependencies.appLifecycle).attachTo(ProcessLifecycleOwner.get())
    }

    private companion object {
        const val TAG = "TreadZ"
    }
}
