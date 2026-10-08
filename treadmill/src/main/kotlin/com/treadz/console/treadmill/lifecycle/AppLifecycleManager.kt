package com.treadz.console.treadmill.lifecycle

import com.treadz.console.treadmill.BeltStatus
import com.treadz.console.treadmill.ConnectionState
import com.treadz.console.treadmill.TreadmillController
import com.treadz.console.treadmill.TreadmillState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * The app lifecycle policy, on top of a [TreadmillController] and the controller's [AppVisibility]. Pure logic: time
 * comes from coroutine delays (virtual time in the tests), and the Android layer only reacts to what it exposes:
 *
 *  - [serviceWanted]: the foreground service and its notification exist only while connected to the treadmill, plus
 *    the short "connection lost" notice after a loss in the background.
 *  - [lossNotice]: what that notice says.
 *  - the idle auto-disconnect: connected, in the background and with the belt idle for
 *    [LifecycleConfig.backgroundIdleDisconnectMs] -> `disconnect()`.
 *
 * The scanning/reconnection pause in the background lives in the controller itself, which owns those operations.
 */
class AppLifecycleManager(
    private val controller: TreadmillController,
    private val visibility: AppVisibility,
    scope: CoroutineScope,
    private val config: LifecycleConfig = LifecycleConfig(),
) : AppLifecycle {

    override val appInForeground: StateFlow<Boolean> get() = visibility.appInForeground

    override fun setAppInForeground(foreground: Boolean) = visibility.setAppInForeground(foreground)

    private val _exitRequests = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    override val exitRequests: SharedFlow<Unit> = _exitRequests.asSharedFlow()

    private val _lossNotice = MutableStateFlow<ConnectionLossNotice?>(null)

    /** Set for [LifecycleConfig.backgroundLossNoticeMs] after the connection was lost in the background. */
    val lossNotice: StateFlow<ConnectionLossNotice?> = _lossNotice.asStateFlow()

    /** Whether the foreground service (and with it the notification) should be running. */
    val serviceWanted: Flow<Boolean> =
        combine(controller.connection, _lossNotice) { connection, notice -> connection is ConnectionState.Connected || notice != null }
            .distinctUntilChanged()

    private var noticeJob: Job? = null

    init {
        // Only this collector touches `previous` and `noticeJob`: it handles one change at a time.
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            var previous = controller.connection.value
            combine(controller.connection, visibility.appInForeground) { connection, foreground -> connection to foreground }
                .collect { (connection, foreground) ->
                    val lost = previous is ConnectionState.Connected && connection is ConnectionState.Reconnecting
                    previous = connection
                    when {
                        lost && !foreground -> startLossNotice(scope)
                        foreground || connection is ConnectionState.Connected || connection is ConnectionState.Disconnected -> clearLossNotice()
                    }
                }
        }
        scope.launch(start = CoroutineStart.UNDISPATCHED) {
            combine(controller.connection, controller.state, visibility.appInForeground) { connection, state, foreground ->
                !foreground && connection is ConnectionState.Connected && state.isBeltIdle()
            }
                .distinctUntilChanged()
                .collectLatest { idleInBackground ->
                    if (!idleInBackground) return@collectLatest
                    delay(config.backgroundIdleDisconnectMs)
                    // Defensive: the condition is re-read; it cannot have changed without cancelling this wait.
                    val stillIdle = !visibility.appInForeground.value &&
                        controller.connection.value is ConnectionState.Connected &&
                        controller.state.value.isBeltIdle()
                    if (!stillIdle) return@collectLatest
                    visibility.traceNote(
                        "Idle in the background for ${config.backgroundIdleDisconnectMs / 1000} s: disconnecting from the treadmill",
                    )
                    // The disconnection itself changes the condition and would cancel this block half way.
                    withContext(NonCancellable) { controller.disconnect() }
                }
        }
    }

    private fun startLossNotice(scope: CoroutineScope) {
        val wasActive = controller.state.value.let { !it.isBeltIdle() }
        val notice = if (wasActive) ConnectionLossNotice.BeltStoppedByItself else ConnectionLossNotice.Neutral
        visibility.traceNote("Connection lost in the background ($notice): notification for ${config.backgroundLossNoticeMs} ms")
        noticeJob?.cancel()
        _lossNotice.value = notice
        noticeJob = scope.launch {
            delay(config.backgroundLossNoticeMs)
            _lossNotice.value = null
        }
    }

    private fun clearLossNotice() {
        noticeJob?.cancel()
        noticeJob = null
        _lossNotice.value = null
    }

    override suspend fun exitApp(): ExitResult {
        val connected = controller.connection.value is ConnectionState.Connected
        if (connected && !controller.state.value.isBeltIdle()) {
            visibility.traceNote("Exit refused: the belt is not idle (${controller.state.value.status}, ${controller.state.value.speedKmh} km/h)")
            return ExitResult.Refused(ExitRefusedReason.BeltNotIdle)
        }
        visibility.traceNote("Exit requested: disconnecting from the treadmill")
        controller.disconnect()
        _exitRequests.tryEmit(Unit)
        return ExitResult.Exiting
    }
}

/** Status Idle and the belt not moving: the only situation in which leaving or letting go of the treadmill is safe. */
fun TreadmillState.isBeltIdle(): Boolean = status == BeltStatus.Idle && speedKmh <= 0.0
