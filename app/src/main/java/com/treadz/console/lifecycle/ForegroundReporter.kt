package com.treadz.console.lifecycle

import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleOwner
import com.treadz.console.treadmill.lifecycle.AppLifecycle

/**
 * Tells the backend whether the app is visible. It observes the process lifecycle (`ProcessLifecycleOwner`), whose
 * `ON_START` / `ON_STOP` already ignore configuration changes and moves between the app's own screens.
 *
 * Register it once per process: `ProcessLifecycleOwner.get().lifecycle.addObserver(ForegroundReporter(...))`.
 */
class ForegroundReporter(private val appLifecycle: AppLifecycle) : DefaultLifecycleObserver {

    override fun onStart(owner: LifecycleOwner) = appLifecycle.setAppInForeground(true)

    override fun onStop(owner: LifecycleOwner) = appLifecycle.setAppInForeground(false)

    /**
     * Starts reporting. The first report is explicit: a process that starts without any screen (for example, to
     * handle the notification) is in the background, whatever the backend assumes by default.
     */
    fun attachTo(owner: LifecycleOwner) {
        appLifecycle.setAppInForeground(owner.lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
        owner.lifecycle.addObserver(this)
    }
}
