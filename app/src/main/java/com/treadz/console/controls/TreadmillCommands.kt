package com.treadz.console.controls

import com.treadz.console.treadmill.CommandResult
import com.treadz.console.treadmill.TreadmillController

/**
 * Where the manual controls send their commands. Normally straight to the [TreadmillController]; while a training
 * program or a fitness test is running they go through that runner instead (see `TrainingCoordinator`), so the runner
 * knows about every change and the test keeps its protocol.
 */
interface TreadmillCommands {
    suspend fun setSpeed(kmh: Double): CommandResult
    suspend fun setIncline(percent: Double): CommandResult
    suspend fun pause(): CommandResult
    suspend fun resume(): CommandResult
    suspend fun stop(): CommandResult
}

/** The plain case: every command goes directly to the controller. */
class ControllerCommands(private val controller: TreadmillController) : TreadmillCommands {
    override suspend fun setSpeed(kmh: Double) = controller.setSpeed(kmh)
    override suspend fun setIncline(percent: Double) = controller.setIncline(percent)
    override suspend fun pause() = controller.pause()
    override suspend fun resume() = controller.resume()
    override suspend fun stop() = controller.stop()
}

/** What kind of session drives the belt. */
enum class SessionKind { None, Program, FitnessTest }

/** Why some controls are locked during a session (the UI says it next to the controls). */
enum class ControlsNote {
    /** The program was interrupted: it has to be continued or abandoned from the program screen first. */
    ProgramInterrupted,

    /** Fitness test: only the speed may change, and only until it is fixed. */
    TestSpeedOnly,

    /** Fitness test: speed, incline and pause are locked so as not to spoil the measurement. */
    TestLocked,
}

/**
 * What the manual controls may do right now. Without a session everything is allowed (the other rules, such as the belt
 * running and an active user, are checked separately). STOP is never restricted.
 */
data class SessionControls(
    val kind: SessionKind = SessionKind.None,
    val speed: Boolean = true,
    val incline: Boolean = true,
    val pause: Boolean = true,
    val note: ControlsNote? = null,
) {
    fun allows(axis: ControlAxis) = if (axis == ControlAxis.Speed) speed else incline
}
