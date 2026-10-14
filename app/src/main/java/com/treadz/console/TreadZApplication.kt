package com.treadz.console

import android.app.Application
import androidx.lifecycle.ProcessLifecycleOwner
import com.treadz.console.lifecycle.ForegroundReporter
import com.treadz.console.sync.TreadZSync
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class TreadZApplication : Application() {
    /** Lives as long as the process: it hosts the workout history observer and the program and fitness test runners. */
    private val processScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    val dependencies: RealDependencies by lazy { RealDependencies(this, processScope) }

    override fun onCreate() {
        super.onCreate()
        // The user's heart-rate strap comes first: the app looks for it before the treadmill console can take it.
        dependencies.strap.start()
        // Records every treadmill session into the workout history, whatever screen is open. It only observes the
        // controller and is never stopped on disconnect.
        dependencies.workoutObserver.start(processScope)
        // The runners and the coordinator that stores fitness test results exist from the start, not with the first screen.
        dependencies.training
        // Resumes the uploads that were pending when the process died and starts downloading changes whenever the app
        // comes to the foreground. Nothing touches the network until the user links a GitHub token.
        TreadZSync.get(this)
        // The backend needs to know whether the app is visible (no scanning or connecting in the background).
        ForegroundReporter(dependencies.appLifecycle).attachTo(ProcessLifecycleOwner.get())
    }
}
