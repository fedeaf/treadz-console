package com.treadz.console.lifecycle

import com.treadz.console.treadmill.lifecycle.AppLifecycle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch

/**
 * Calls [finish] for every accepted exit, whether it came from the Back dialog or from the notification. Launch it in a
 * scope that lives as long as the activity (`lifecycleScope`), not one that stops with the screen: an Exit tapped in the
 * notification must also reach an activity that is not visible. A second `finish` for the same exit is harmless.
 *
 * The subscription exists when this returns (the exit events have no replay).
 */
fun finishOnExit(scope: CoroutineScope, appLifecycle: AppLifecycle, finish: () -> Unit): Job =
    scope.launch(start = CoroutineStart.UNDISPATCHED) {
        appLifecycle.exitRequests.collect { finish() }
    }
