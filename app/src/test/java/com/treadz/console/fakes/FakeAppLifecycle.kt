package com.treadz.console.fakes

import com.treadz.console.treadmill.ConnectionState
import com.treadz.console.treadmill.TreadmillController
import com.treadz.console.treadmill.lifecycle.AppLifecycle
import com.treadz.console.treadmill.lifecycle.ExitRefusedReason
import com.treadz.console.treadmill.lifecycle.ExitResult
import com.treadz.console.treadmill.lifecycle.isBeltIdle
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Fake of the backend's app lifecycle: it records the visibility the UI reports and answers `exitApp()` with the
 * backend's rule (refused only while connected with the belt not idle), disconnecting the fake controller on success.
 */
class FakeAppLifecycle(private val controller: FakeTreadmillController) : AppLifecycle {

    private val _foreground = MutableStateFlow(true)
    override val appInForeground: StateFlow<Boolean> = _foreground.asStateFlow()

    /** Every visibility report received, in order. */
    val reports = mutableListOf<Boolean>()

    override fun setAppInForeground(foreground: Boolean) {
        reports += foreground
        _foreground.value = foreground
    }

    private val _exitRequests = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    override val exitRequests: SharedFlow<Unit> = _exitRequests

    /** If set, `exitApp()` answers this instead of applying the rule (to test a belt that moves under the dialog). */
    var forcedExitResult: ExitResult? = null

    var exitCalls = 0

    override suspend fun exitApp(): ExitResult {
        exitCalls++
        forcedExitResult?.let { return it }
        val connected = controller.connection.value is ConnectionState.Connected
        if (connected && !controller.state.value.isBeltIdle()) return ExitResult.Refused(ExitRefusedReason.BeltNotIdle)
        controller.disconnect()
        _exitRequests.tryEmit(Unit)
        return ExitResult.Exiting
    }

    /** The notification's Exit action: the backend disconnects and announces the exit. */
    fun requestExitFromNotification() {
        _exitRequests.tryEmit(Unit)
    }
}
