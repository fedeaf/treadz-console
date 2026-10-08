package com.treadz.console.lifecycle

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.treadz.console.treadmill.ConnectionState
import com.treadz.console.treadmill.TreadmillController
import com.treadz.console.treadmill.lifecycle.AppLifecycle
import com.treadz.console.treadmill.lifecycle.ExitResult
import com.treadz.console.treadmill.lifecycle.isBeltIdle
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

/** What the Back button on the root screen asks the user. */
sealed interface ExitPrompt {
    /**
     * The belt is at rest (or there is no connection): ask whether to exit, keep the app in the background or stay.
     * [connected] adds that exiting disconnects from the treadmill.
     */
    data class Confirm(val connected: Boolean) : ExitPrompt

    /** The belt is moving: nothing is closed, the user is told to press STOP first. */
    data object BeltMoving : ExitPrompt
}

/**
 * Back on the root screen. The rule is the backend's: with a connection, the app only exits while the belt is idle
 * (status Idle and speed 0); the backend's `exitApp()` enforces it again, so a belt that starts moving while the
 * dialog is open still turns the answer into the "press STOP" message.
 */
class ExitViewModel(
    private val appLifecycle: AppLifecycle,
    private val controller: TreadmillController,
) : ViewModel() {

    private val _prompt = MutableStateFlow<ExitPrompt?>(null)

    /** The dialog or message to show, `null` for none. */
    val prompt: StateFlow<ExitPrompt?> = _prompt.asStateFlow()

    private var exiting = false
    private var noticeJob: Job? = null

    /** Back was pressed on the root screen. */
    fun onBack() {
        val connected = controller.connection.value is ConnectionState.Connected
        if (connected && !controller.state.value.isBeltIdle()) showBeltMoving() else {
            noticeJob?.cancel()
            _prompt.value = ExitPrompt.Confirm(connected)
        }
    }

    /** "Exit": [onExiting] runs once the backend has disconnected and announced the exit (the UI finishes its task). */
    fun exit(onExiting: () -> Unit) {
        if (exiting) return
        exiting = true
        viewModelScope.launch {
            try {
                when (appLifecycle.exitApp()) {
                    ExitResult.Exiting -> {
                        _prompt.value = null
                        onExiting()
                    }
                    is ExitResult.Refused -> showBeltMoving()
                }
            } finally {
                exiting = false
            }
        }
    }

    /** "Keep in background": the app leaves the screen and stays connected. */
    fun keepInBackground(moveToBack: () -> Unit) {
        _prompt.value = null
        moveToBack()
    }

    fun dismiss() {
        noticeJob?.cancel()
        _prompt.value = null
    }

    private fun showBeltMoving() {
        noticeJob?.cancel()
        _prompt.value = ExitPrompt.BeltMoving
        noticeJob = viewModelScope.launch {
            delay(BELT_MOVING_NOTICE_MILLIS)
            if (_prompt.value == ExitPrompt.BeltMoving) _prompt.value = null
        }
    }

    companion object {
        /** How long the "press STOP before exiting" message stays on screen. */
        const val BELT_MOVING_NOTICE_MILLIS = 6_000L
    }
}
