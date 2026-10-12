package com.treadz.console.controls

import com.treadz.console.treadmill.BeltStatus
import com.treadz.console.treadmill.CommandResult
import com.treadz.console.treadmill.ConnectionState
import com.treadz.console.treadmill.FailureCode
import com.treadz.console.treadmill.TreadmillController
import com.treadz.console.treadmill.TreadmillEvent
import com.treadz.console.treadmill.TreadmillState
import com.treadz.console.treadmill.ValueRange
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToLong

/** Which of the two adjustable values a command is about. */
enum class ControlAxis { Speed, Incline }

/** What the user was doing when a command failed (the message names it). */
enum class ControlAction { Speed, Incline, Pause, Resume }

/**
 * A command the treadmill did not carry out, to show for a few seconds. [id] is unique, so the same failure twice in a
 * row is still a new one.
 */
data class ControlFailure(val id: Long, val action: ControlAction, val result: CommandResult)

/** What the manual controls need to draw themselves, besides the treadmill state. */
data class ControlsState(
    /** Speed the user asked for and the treadmill has not confirmed yet (km/h); `null` = nothing pending. */
    val pendingSpeed: Double? = null,
    val pendingIncline: Double? = null,
    /** A speed/incline command is being verified by the treadmill. */
    val speedInFlight: Boolean = false,
    val inclineInFlight: Boolean = false,
    /** A pause or resume is being verified. */
    val pauseInFlight: Boolean = false,
    val stopInFlight: Boolean = false,
    val failure: ControlFailure? = null,
    val speedRange: ValueRange = ManualControls.DEFAULT_SPEED_RANGE,
    val inclineRange: ValueRange = ManualControls.DEFAULT_INCLINE_RANGE,
)

/**
 * The manual controls: speed and incline steps with auto-repeat and presets, pause/resume and STOP. Every command goes
 * through the [TreadmillController]; this class decides what to send and when, and keeps what the screen shows.
 *
 * ## Coalescing
 * The treadmill verifies every speed/incline command (it can take seconds while the belt ramps), but the user taps much
 * faster. So the UI keeps its **own pending target** per axis:
 * - A tap moves the pending target from the pending target itself (not from `TreadmillState.targetSpeedKmh`, which only
 *   changes after verification and is stale while commands are queued), clamped to the treadmill range, and shows it
 *   right away.
 * - Only one command per axis is in flight. When it finishes, if the pending target is different from what was sent,
 *   the latest one is sent next: the values in between are skipped.
 * - A failed command drops the pending target (the screen goes back to what the treadmill reports) and shows a message.
 * - The pending targets are dropped when the console's own buttons change the value, when the belt leaves Running
 *   (pause, stop, ...) and when the connection changes.
 *
 * Everything is meant to be called from the main thread; [scope] must run on it.
 *
 * @param canOperate whether treadmill commands other than STOP are allowed (there is an active user).
 * @param commands where the commands go: the controller, or the runner of the program / fitness test in progress.
 * @param session what the program / fitness test in progress allows (STOP is always allowed).
 */
class ManualControls(
    private val controller: TreadmillController,
    private val scope: CoroutineScope,
    private val canOperate: () -> Boolean = { true },
    private val commands: TreadmillCommands = ControllerCommands(controller),
    private val session: () -> SessionControls = { SessionControls() },
) {

    private data class Local(
        val pendingSpeed: Double? = null,
        val pendingIncline: Double? = null,
        val speedInFlight: Boolean = false,
        val inclineInFlight: Boolean = false,
        val pauseInFlight: Boolean = false,
        val stopInFlight: Boolean = false,
        val failure: ControlFailure? = null,
    ) {
        fun pending(axis: ControlAxis) = if (axis == ControlAxis.Speed) pendingSpeed else pendingIncline
        fun inFlight(axis: ControlAxis) = if (axis == ControlAxis.Speed) speedInFlight else inclineInFlight
        fun withPending(axis: ControlAxis, value: Double?) =
            if (axis == ControlAxis.Speed) copy(pendingSpeed = value) else copy(pendingIncline = value)

        fun withInFlight(axis: ControlAxis, value: Boolean) =
            if (axis == ControlAxis.Speed) copy(speedInFlight = value) else copy(inclineInFlight = value)
    }

    private val local = MutableStateFlow(Local())
    private var failureIds = 0L
    private var repeatJob: Job? = null

    val state: StateFlow<ControlsState> = combine(local, controller.capabilities) { s, capabilities ->
        ControlsState(
            pendingSpeed = s.pendingSpeed,
            pendingIncline = s.pendingIncline,
            speedInFlight = s.speedInFlight,
            inclineInFlight = s.inclineInFlight,
            pauseInFlight = s.pauseInFlight,
            stopInFlight = s.stopInFlight,
            failure = s.failure,
            speedRange = capabilities?.speed ?: DEFAULT_SPEED_RANGE,
            inclineRange = capabilities?.incline ?: DEFAULT_INCLINE_RANGE,
        )
    }.stateIn(scope, SharingStarted.Eagerly, ControlsState())

    init {
        // The console's buttons changed the value: what the user was asking for is obsolete.
        scope.launch {
            controller.events.collect { event ->
                if (event is TreadmillEvent.ManualChange) {
                    if (event.speedKmh != null) clearPending(ControlAxis.Speed)
                    if (event.inclinePercent != null) clearPending(ControlAxis.Incline)
                }
            }
        }
        // Pause, stop, countdown...: the targets are not applicable any more.
        scope.launch {
            controller.state.map { it.status }.distinctUntilChanged().collect { status ->
                if (status != BeltStatus.Running) reset()
            }
        }
        // Lost or recovered connection: start from what the treadmill reports.
        scope.launch {
            controller.connection.map { it is ConnectionState.Connected }.distinctUntilChanged().collect { reset() }
        }
    }

    // ------------------------------------------------------------------ speed and incline

    /** One speed step (0.1 km/h) up (`+1`) or down (`-1`). */
    fun stepSpeed(direction: Int) = step(ControlAxis.Speed, direction)

    /** One incline step (1 %) up (`+1`) or down (`-1`). */
    fun stepIncline(direction: Int) = step(ControlAxis.Incline, direction)

    /** Sets the speed target (a preset), clamped to the treadmill range. */
    fun setSpeed(kmh: Double) = setTarget(ControlAxis.Speed, kmh)

    fun setIncline(percent: Double) = setTarget(ControlAxis.Incline, percent)

    /**
     * The user keeps pressing a −/+ button: one step now, then more with growing frequency until [stopRepeat]. It also
     * ends by itself when adjusting stops being possible, and after [REPEAT_MAX_MILLIS] in case the release was lost.
     */
    fun startRepeat(axis: ControlAxis, direction: Int) {
        repeatJob?.cancel()
        repeatJob = null
        if (!canAdjustNow(axis)) return
        step(axis, direction) // the first step is immediate: a short tap is one step
        repeatJob = scope.launch {
            var interval = REPEAT_FIRST_INTERVAL_MILLIS
            var held = REPEAT_START_DELAY_MILLIS
            delay(REPEAT_START_DELAY_MILLIS)
            while (held < REPEAT_MAX_MILLIS && canAdjustNow(axis)) {
                step(axis, direction)
                delay(interval)
                held += interval
                interval = max(REPEAT_MIN_INTERVAL_MILLIS, (interval * REPEAT_ACCELERATION).toLong())
            }
        }
    }

    fun stopRepeat() {
        repeatJob?.cancel()
        repeatJob = null
    }

    private fun step(axis: ControlAxis, direction: Int) {
        if (!canAdjustNow(axis)) return
        val base = local.value.pending(axis) ?: currentTarget(axis) ?: return
        setTarget(axis, base + direction * stepOf(axis))
    }

    private fun setTarget(axis: ControlAxis, requested: Double) {
        if (!canAdjustNow(axis)) return
        val target = clamp(axis, requested)
        local.update { it.withPending(axis, target).copy(failure = null) }
        pump(axis)
    }

    /** Sends the pending target if nothing is in flight for [axis]. */
    private fun pump(axis: ControlAxis) {
        val s = local.value
        val target = s.pending(axis) ?: return
        if (s.inFlight(axis)) return
        if (!canAdjustNow(axis)) {
            clearPending(axis)
            return
        }
        val current = currentTarget(axis)
        if (current != null && same(current, target)) {
            clearPending(axis)
            return
        }
        local.update { it.withInFlight(axis, true) }
        scope.launch {
            val result = try {
                when (axis) {
                    ControlAxis.Speed -> commands.setSpeed(target)
                    ControlAxis.Incline -> commands.setIncline(target)
                }
            } catch (e: CancellationException) {
                local.update { it.withInFlight(axis, false) }
                throw e
            }
            local.update { it.withInFlight(axis, false) }
            if (result is CommandResult.Success) {
                val latest = local.value.pending(axis)
                when {
                    latest == null -> Unit // dropped meanwhile (console button, pause...)
                    same(latest, target) -> clearPending(axis) // reached: the screen follows the treadmill again
                    else -> pump(axis) // the user kept tapping: send only the latest value
                }
            } else {
                clearPending(axis)
                reportFailure(if (axis == ControlAxis.Speed) ControlAction.Speed else ControlAction.Incline, result)
            }
        }
    }

    private fun currentTarget(axis: ControlAxis): Double? {
        val t = controller.state.value
        return when (axis) {
            ControlAxis.Speed -> t.targetSpeedKmh ?: t.speedKmh
            ControlAxis.Incline -> t.inclinePercent
        }
    }

    private fun clearPending(axis: ControlAxis) = local.update { it.withPending(axis, null) }

    private fun reset() {
        stopRepeat()
        local.update { it.copy(pendingSpeed = null, pendingIncline = null) }
    }

    private fun range(axis: ControlAxis): ValueRange {
        val capabilities = controller.capabilities.value
        return when (axis) {
            ControlAxis.Speed -> capabilities?.speed ?: DEFAULT_SPEED_RANGE
            ControlAxis.Incline -> capabilities?.incline ?: DEFAULT_INCLINE_RANGE
        }
    }

    private fun clamp(axis: ControlAxis, value: Double): Double {
        val r = range(axis)
        val step = stepOf(axis)
        return round(min(r.max, max(r.min, round(value, step))), step)
    }

    // ------------------------------------------------------------------ pause, resume, stop

    /** Pause with the belt running. */
    fun pause() {
        if (!canOperateNow() || !session().pause || controller.state.value.status != BeltStatus.Running) return
        launchBeltCommand(ControlAction.Pause) { commands.pause() }
    }

    /** Resume a pause. Only once the belt has stopped (the treadmill refuses before that). */
    fun resume() {
        val t = controller.state.value
        if (!canOperateNow() || t.status != BeltStatus.Paused || t.speedKmh > STOPPED_SPEED_KMH) return
        launchBeltCommand(ControlAction.Resume) { commands.resume() }
    }

    private fun launchBeltCommand(action: ControlAction, command: suspend () -> CommandResult) {
        if (local.value.pauseInFlight) return
        reset()
        local.update { it.copy(pauseInFlight = true, failure = null) }
        scope.launch {
            val result = try {
                command()
            } finally {
                local.update { it.copy(pauseInFlight = false) }
            }
            if (result !is CommandResult.Success) reportFailure(action, result)
        }
    }

    /**
     * STOP. Always sent, whatever the screen thinks: no active user needed, nothing pending is waited for. A stop the
     * treadmill does not carry out reaches the user as a full-screen alert from the controller, so no message here.
     */
    fun stop() {
        reset()
        local.update { it.copy(stopInFlight = true, failure = null) }
        scope.launch {
            try {
                commands.stop()
            } finally {
                local.update { it.copy(stopInFlight = false) }
            }
        }
    }

    fun dismissFailure() = local.update { it.copy(failure = null) }

    // ------------------------------------------------------------------ rules and failures

    private fun connected() = controller.connection.value is ConnectionState.Connected

    private fun canOperateNow(): Boolean = canOperate() && connected() && !controller.state.value.isStale

    private fun canAdjustNow(axis: ControlAxis): Boolean =
        canOperateNow() && session().allows(axis) && controller.state.value.status == BeltStatus.Running

    private fun reportFailure(action: ControlAction, result: CommandResult) {
        // A stop that superseded the command is not an error.
        if (result is CommandResult.Failure && result.code == FailureCode.Preempted) return
        // A command the treadmill accepted but did not carry out is already a full-screen alert: not repeated here.
        val command = commandName(action)
        if (controller.pendingAlerts.value.any { it is TreadmillEvent.CommandNotExecuted && it.command == command }) return
        val failure = ControlFailure(++failureIds, action, result)
        local.update { it.copy(failure = failure) }
        scope.launch {
            delay(FAILURE_VISIBLE_MILLIS)
            local.update { if (it.failure?.id == failure.id) it.copy(failure = null) else it }
        }
    }

    companion object {
        val DEFAULT_SPEED_RANGE = ValueRange(0.5, 20.0, 0.1)
        val DEFAULT_INCLINE_RANGE = ValueRange(0.0, 18.0, 1.0)

        const val SPEED_STEP_KMH = 0.1
        const val INCLINE_STEP_PERCENT = 1.0

        /** Speeds offered as presets (km/h). */
        val SPEED_PRESETS = listOf(4.0, 6.0, 8.0, 10.0, 12.0)

        /** Inclines offered as presets (%). */
        val INCLINE_PRESETS = listOf(0.0, 3.0, 6.0, 9.0, 12.0)

        /** The belt counts as stopped below this speed (km/h): the contract's [TreadmillState.STOPPED_SPEED_KMH]. */
        const val STOPPED_SPEED_KMH = TreadmillState.STOPPED_SPEED_KMH

        const val FAILURE_VISIBLE_MILLIS = 6_000L

        /** Auto-repeat: pause before the first repeat, then the interval shrinks down to the minimum. */
        const val REPEAT_START_DELAY_MILLIS = 400L
        const val REPEAT_FIRST_INTERVAL_MILLIS = 200L
        const val REPEAT_MIN_INTERVAL_MILLIS = 60L
        const val REPEAT_ACCELERATION = 0.85

        /** A press never repeats for longer than this, in case the release is lost. */
        const val REPEAT_MAX_MILLIS = 20_000L

        /** The name the controller gives the command in its `CommandNotExecuted` alert. */
        private fun commandName(action: ControlAction) = when (action) {
            ControlAction.Speed -> "setSpeed"
            ControlAction.Incline -> "setIncline"
            ControlAction.Pause -> "pause"
            ControlAction.Resume -> "resume"
        }

        private fun stepOf(axis: ControlAxis) = if (axis == ControlAxis.Speed) SPEED_STEP_KMH else INCLINE_STEP_PERCENT

        /** Rounds to the step grid, free of binary noise (8.3 and not 8.300000000000001). */
        private fun round(value: Double, step: Double): Double {
            val steps = (value / step).roundToLong()
            return (steps * step * 1_000).roundToLong() / 1_000.0
        }

        private fun same(a: Double, b: Double) = kotlin.math.abs(a - b) < 0.005
    }
}
