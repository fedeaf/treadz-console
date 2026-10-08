package com.treadz.console.programs.engine

import com.treadz.console.programs.heartrate.ControllerHeartRateSource
import com.treadz.console.programs.heartrate.HeartRateSource
import com.treadz.console.programs.model.BlockEnd
import com.treadz.console.programs.plan.ResolvedBlock
import com.treadz.console.programs.plan.ResolvedPlan
import com.treadz.console.treadmill.BeltStatus
import com.treadz.console.treadmill.CommandResult
import com.treadz.console.treadmill.ConnectionState
import com.treadz.console.treadmill.FailureCode
import com.treadz.console.treadmill.TreadmillController
import com.treadz.console.treadmill.TreadmillEvent
import com.treadz.console.treadmill.TreadmillState
import com.treadz.console.treadmill.control.Clock
import com.treadz.console.treadmill.control.SafetyClamp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Executes a [ResolvedPlan] on the treadmill through a [TreadmillController].
 *
 * **Rules**
 *  - Every command goes through the controller, which verifies it; the runner never touches the transport.
 *  - The program clock is the runner's own (injected [Clock]); the treadmill's time/distance countdowns are not used.
 *    The clock runs only while the state is [RunState.Running], so a pause, an interruption or the belt starting do not
 *    count. A block's time includes the speed ramp towards its targets (it counts from the block's start).
 *  - Distance blocks use the treadmill's distance counter (10 m resolution): the distance travelled is the sum of its
 *    increases while running; a decrease (the treadmill reset its session) is ignored.
 *  - **Manual changes** (console buttons, or the app's controls through [adjustSpeed]/[adjustIncline]) are reported
 *    and respected until the next block, which applies its own targets again (only the values that differ from what
 *    the treadmill already has are sent). While a program is active the UI must not call the controller's
 *    `setSpeed`/`setIncline` directly.
 *  - **Interruptions** (safety key, connection lost, the belt stopped/paused/restarted from elsewhere, a command not
 *    executed, a command of the runner that failed, frozen data) stop all commands and move to
 *    [RunState.Interrupted]. The runner never restarts the belt on its own; [continueProgram] or [abandon] are
 *    explicit user actions. While a program is active, pause/resume/stop must go through the runner: a pause or stop
 *    given directly to the controller looks to the runner like the belt changing externally.
 *  - At the end of the last block the belt is stopped ([EndAction.StopBelt]) or left running
 *    ([EndAction.HandOverToManual]).
 *
 * Thread safety: all state lives behind one lock and is changed only in short non-suspending sections; controller
 * calls are made outside it. User actions run in [scope] (they are not cancelled with the caller) and are serialised
 * with the engine through an epoch counter: whatever the engine was doing when an action changed the state is dropped.
 *
 * Only one program runs at a time. After [RunState.Finished] a new one can be started.
 */
class ProgramRunner(
    private val controller: TreadmillController,
    private val scope: CoroutineScope,
    private val clock: Clock = Clock.System,
    private val config: RunnerConfig = RunnerConfig(),
    private val heartRate: HeartRateSource = ControllerHeartRateSource(controller),
) {

    private val _progress = MutableStateFlow(ProgramProgress())

    /** What the UI shows. Updated at least every [RunnerConfig.tickMs] while running. */
    val progress: StateFlow<ProgramProgress> = _progress.asStateFlow()

    private val _events = MutableSharedFlow<ProgramEvent>(extraBufferCapacity = 256)

    /** Events of the run, in order. No replay. */
    val events: SharedFlow<ProgramEvent> = _events.asSharedFlow()

    private val lock = Any()
    private val wake = Channel<Unit>(Channel.CONFLATED)
    @Volatile
    private var runJob: Job? = null

    // -- state guarded by [lock]
    private var plan: ResolvedPlan? = null
    private var options = RunOptions()
    private var runState = RunState.Idle
    private var outcome: RunOutcome? = null
    private var interruption: Interruption? = null

    /** True from the call to start() until the belt is confirmed running: the command result tells what happened. */
    private var initialStart = false

    /** A pause/resume/continue command of the user is in flight: the belt status is expected to change. */
    private var busy = false
    private var epoch = 0L
    private var blockIndex = 0
    private var blockElapsedMs = 0L
    private var totalElapsedMs = 0L
    private var blockMeters = 0
    private var totalMeters = 0
    private var lastAccountAt = 0L
    private var lastDistance: Int? = null
    private var targetSpeed: Double? = null
    private var targetIncline: Double? = null
    private var manualOverride = false
    private var needsApply = false

    // ------------------------------------------------------------------ user actions

    /**
     * Starts [plan]: starts the belt if needed (or resumes a paused one; a belt already running is used as it is) and
     * begins block 1. Returns when the belt is confirmed running and the program clock has started.
     */
    suspend fun start(plan: ResolvedPlan, options: RunOptions = RunOptions()): RunnerResult =
        scope.async { startBody(plan, options) }.await()

    private suspend fun startBody(newPlan: ResolvedPlan, newOptions: RunOptions): RunnerResult {
        val action: StartAction
        synchronized(lock) {
            if (runState != RunState.Idle && runState != RunState.Finished) {
                return RunnerResult.NotAllowed(Refusal.AlreadyActive, "A program is already active")
            }
            if (newPlan.blocks.isEmpty() || newPlan.blocks.withIndex().any { (i, b) -> b.index != i }) {
                return RunnerResult.NotAllowed(Refusal.InvalidPlan, "The plan has no blocks or its block indexes are not 0..n-1")
            }
            if (controller.connection.value !is ConnectionState.Connected) {
                return RunnerResult.NotAllowed(Refusal.NotConnected, "The treadmill is not connected")
            }
            action = when (controller.state.value.status) {
                BeltStatus.Running -> StartAction.None
                BeltStatus.Paused -> StartAction.Resume
                BeltStatus.Idle -> StartAction.Start
                BeltStatus.Starting -> return RunnerResult.NotAllowed(Refusal.BeltBusy, "The belt is already starting")
                BeltStatus.SafetyKeyRemoved -> return RunnerResult.NotAllowed(Refusal.SafetyKeyRemoved, "The safety key is removed")
            }
            plan = newPlan
            options = newOptions
            runState = RunState.Starting
            outcome = null
            interruption = null
            initialStart = true
            busy = false
            epoch++
            blockIndex = 0
            blockElapsedMs = 0
            totalElapsedMs = 0
            blockMeters = 0
            totalMeters = 0
            lastDistance = controller.state.value.distanceMeters
            lastAccountAt = clock.nowMillis()
            setBlockTargets(newPlan.blocks[0])
            needsApply = false
            publish()
        }
        launchRun()

        val result = when (action) {
            StartAction.None -> CommandResult.Success
            StartAction.Resume -> controller.resume()
            StartAction.Start -> controller.start()
        }

        synchronized(lock) {
            if (runState != RunState.Starting) {
                // Cannot happen while initialStart protects the state, but never leave a half-started run behind.
                return RunnerResult.CommandFailed(result)
            }
            initialStart = false
            if (result != CommandResult.Success) {
                resetToIdle()
                return if (result is CommandResult.NotConnected) {
                    RunnerResult.NotAllowed(Refusal.NotConnected, "The connection was lost while starting")
                } else {
                    RunnerResult.CommandFailed(result)
                }
            }
            lastAccountAt = clock.nowMillis()
            lastDistance = controller.state.value.distanceMeters
            runState = RunState.Running
            val p = checkNotNull(plan)
            emit(ProgramEvent.Started(clock.nowMillis(), 0, p, options))
            emit(ProgramEvent.BlockStarted(clock.nowMillis(), 0, 0, p.blocks[0], BlockChangeReason.ProgramStart))
            needsApply = true
            checkInterruptions()
            publish()
        }
        wake()
        return RunnerResult.Success
    }

    private enum class StartAction { None, Resume, Start }

    /** Pauses the belt and stops the program clock. */
    suspend fun pause(): RunnerResult = scope.async {
        synchronized(lock) {
            if (runState != RunState.Running) return@async notAllowedState("pause needs a running program")
            accumulate()
            runState = RunState.Paused
            busy = true
            epoch++
            emit(ProgramEvent.Paused(clock.nowMillis(), programSeconds(), blockIndex))
            publish()
        }
        val result = controller.pause()
        synchronized(lock) {
            busy = false
            if (result != CommandResult.Success && runState == RunState.Paused) failedCommand("pause", result) else checkInterruptions()
            publish()
        }
        wake()
        resultOf(result)
    }.await()

    /** Resumes after [pause]; the targets of the current block (or the user's manual change in it) are applied again. */
    suspend fun resume(): RunnerResult = scope.async {
        synchronized(lock) {
            if (runState != RunState.Paused) return@async notAllowedState("resume needs a paused program")
            refuseIfNotReady()?.let { return@async it }
            accumulate()
            runState = RunState.Starting
            busy = true
            epoch++
            publish()
        }
        val result = controller.resume()
        synchronized(lock) {
            busy = false
            if (runState == RunState.Starting) {
                if (result == CommandResult.Success) {
                    enterRunning(ProgramEvent.Resumed(clock.nowMillis(), programSeconds(), blockIndex))
                } else if (result is CommandResult.NotAllowed) {
                    // The command had no effect: still paused.
                    runState = RunState.Paused
                } else {
                    failedCommand("resume", result)
                }
            }
            publish()
        }
        wake()
        resultOf(result)
    }.await()

    /**
     * Continues an interrupted program from where it was: resumes or starts the belt if needed and applies the targets
     * of the current block again. The remaining time of the block is preserved. Refused while the safety key is out,
     * the connection is down or the data is not current.
     */
    suspend fun continueProgram(): RunnerResult = scope.async {
        val action: StartAction
        synchronized(lock) {
            if (runState != RunState.Interrupted) return@async notAllowedState("continue needs an interrupted program")
            refuseIfNotReady()?.let { return@async it }
            action = when (controller.state.value.status) {
                BeltStatus.Running -> StartAction.None
                BeltStatus.Paused -> StartAction.Resume
                BeltStatus.Idle -> StartAction.Start
                BeltStatus.Starting -> return@async RunnerResult.NotAllowed(Refusal.BeltBusy, "The belt is starting")
                BeltStatus.SafetyKeyRemoved -> return@async RunnerResult.NotAllowed(Refusal.SafetyKeyRemoved, "The safety key is removed")
            }
            runState = RunState.Starting
            busy = true
            epoch++
            publish()
        }
        val result = when (action) {
            StartAction.None -> CommandResult.Success
            StartAction.Resume -> controller.resume()
            StartAction.Start -> controller.start()
        }
        synchronized(lock) {
            busy = false
            if (runState == RunState.Starting) {
                if (result == CommandResult.Success) {
                    interruption = null
                    enterRunning(ProgramEvent.Continued(clock.nowMillis(), programSeconds(), blockIndex))
                } else {
                    // Still interrupted; the original interruption stands.
                    runState = RunState.Interrupted
                    if (result is CommandResult.NotConnected) interruptionNow(InterruptionReason.ConnectionLost, "continue: not connected")
                }
            }
            publish()
        }
        wake()
        resultOf(result)
    }.await()

    /**
     * Manual speed change from the app's own controls while a program is active. **While a program is active the UI
     * must use this (and [adjustIncline]) instead of `TreadmillController.setSpeed`/`setIncline`**, otherwise the
     * runner does not know about the change and would undo it after a pause.
     *
     * The value is clamped like any command and sent through the controller. It is then kept, like a change made with
     * the console buttons, until the next block applies its own values (a [ProgramEvent.ManualChange] with
     * [ManualChangeSource.App] is emitted and [ProgramProgress.manualOverride] turns on). Only valid while
     * [RunState.Running]. Repeated calls are fine: each one replaces the previous adjustment.
     */
    suspend fun adjustSpeed(kmh: Double): RunnerResult = adjust(kmh, null)

    /** Same as [adjustSpeed] for the incline, in percent. */
    suspend fun adjustIncline(percent: Double): RunnerResult = adjust(null, percent)

    private suspend fun adjust(speed: Double?, incline: Double?): RunnerResult = scope.async<RunnerResult> {
        val requested = speed ?: incline ?: return@async RunnerResult.NotAllowed(Refusal.InvalidValue, "Nothing to adjust")
        if (requested.isNaN()) return@async RunnerResult.NotAllowed(Refusal.InvalidValue, "The value is not a number")
        val value: Double
        val previousSpeed: Double?
        val previousIncline: Double?
        val previousOverride: Boolean
        synchronized(lock) {
            if (runState != RunState.Running) return@async notAllowedState("adjusting needs a running program")
            val caps = controller.capabilities.value
            value = if (speed != null) {
                SafetyClamp.speed(speed, caps?.speed ?: SafetyClamp.DEFAULT_SPEED_RANGE, controller.limits.value)
            } else {
                SafetyClamp.incline(requested, caps?.incline ?: SafetyClamp.DEFAULT_INCLINE_RANGE, controller.limits.value)
            }
            previousSpeed = targetSpeed
            previousIncline = targetIncline
            previousOverride = manualOverride
            if (speed != null) targetSpeed = value else targetIncline = value
            manualOverride = true
            // A block application in flight is dropped; if the block's values are still pending they are redone
            // around this adjustment.
            epoch++
            publish()
        }
        val result = if (speed != null) controller.setSpeed(value) else controller.setIncline(value)
        synchronized(lock) {
            if (result == CommandResult.Success) {
                if (runState == RunState.Running || runState == RunState.Paused) {
                    emit(
                        ProgramEvent.ManualChange(
                            clock.nowMillis(), programSeconds(), blockIndex,
                            speed?.let { value }, incline?.let { value }, ManualChangeSource.App,
                        ),
                    )
                }
            } else {
                // The adjustment did not happen: back to what the engine wanted before.
                if (speed != null && targetSpeed == value) targetSpeed = previousSpeed
                if (incline != null && targetIncline == value) targetIncline = previousIncline
                manualOverride = previousOverride
                val preempted = result is CommandResult.NotVerified && result.code == FailureCode.Preempted
                if (!preempted && runState == RunState.Running) failedCommand(if (speed != null) "setSpeed" else "setIncline", result)
            }
            publish()
        }
        wake()
        resultOf(result)
    }.await()

    /** Moves to the next block (the program ends if it was the last one). Valid while running or paused. */
    suspend fun skipToNext(): RunnerResult = scope.async {
        val endNow: Boolean
        synchronized(lock) {
            if (runState != RunState.Running && runState != RunState.Paused) return@async notAllowedState("skip needs a running or paused program")
            accumulate()
            val p = checkNotNull(plan)
            if (blockIndex == p.blocks.lastIndex) {
                emit(ProgramEvent.BlockCompleted(clock.nowMillis(), programSeconds(), blockIndex, (blockElapsedMs / 1000).toInt(), blockMeters, BlockChangeReason.SkippedNext))
                runState = RunState.Stopping
                epoch++
                publish()
                endNow = true
            } else {
                advanceTo(blockIndex + 1, BlockChangeReason.SkippedNext, carryOver = false)
                publish()
                endNow = false
            }
        }
        wake()
        if (endNow) completeProgram()
        RunnerResult.Success
    }.await()

    /**
     * Goes back: restarts the current block if it has been running for at least
     * [RunnerConfig.previousRestartsBlockAfterMs] (or it is the first one), otherwise returns to the previous block.
     */
    suspend fun skipToPrevious(): RunnerResult = scope.async {
        synchronized(lock) {
            if (runState != RunState.Running && runState != RunState.Paused) return@async notAllowedState("skip needs a running or paused program")
            accumulate()
            if (blockIndex == 0 || blockElapsedMs >= config.previousRestartsBlockAfterMs) {
                advanceTo(blockIndex, BlockChangeReason.Restarted, carryOver = false)
            } else {
                advanceTo(blockIndex - 1, BlockChangeReason.SkippedPrevious, carryOver = false)
            }
            publish()
        }
        wake()
        RunnerResult.Success
    }.await()

    /**
     * Stops the belt and ends the program ([RunOutcome.StoppedByUser]). Valid in any active state; it ends the
     * program even if the stop command fails (then the result says so and the controller raises its alert).
     */
    suspend fun stop(): RunnerResult = scope.async {
        val alreadyEnding: Boolean
        synchronized(lock) {
            if (runState == RunState.Idle || runState == RunState.Finished) return@async RunnerResult.NotAllowed(Refusal.NotActive, "No active program")
            alreadyEnding = runState == RunState.Stopping
            if (!alreadyEnding) {
                accumulate()
                runState = RunState.Stopping
                busy = false
                epoch++
                publish()
            }
        }
        val result = controller.stop()
        if (!alreadyEnding) synchronized(lock) { finish(RunOutcome.StoppedByUser, null, result) }
        resultOf(result)
    }.await()

    /**
     * Gives up an active program without sending any command: the belt is left as it is (the user stops it with the
     * controller or hands over to manual). Ends with [RunOutcome.Abandoned].
     */
    fun abandon(): RunnerResult = synchronized(lock) {
        when (runState) {
            RunState.Running, RunState.Paused, RunState.Interrupted -> {
                accumulate()
                finish(RunOutcome.Abandoned, null, null)
                RunnerResult.Success
            }
            RunState.Idle, RunState.Finished -> RunnerResult.NotAllowed(Refusal.NotActive, "No active program")
            else -> RunnerResult.NotAllowed(Refusal.WrongState, "The program is $runState")
        }
    }

    // ------------------------------------------------------------------ run loop

    private fun launchRun() {
        runJob?.cancel()
        runJob = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            // Subscribed before any command is sent, so no event of the run is missed.
            launch(start = CoroutineStart.UNDISPATCHED) { controller.events.collect(::onControllerEvent) }
            launch(start = CoroutineStart.UNDISPATCHED) { controller.state.collect(::onTreadmillState) }
            launch(start = CoroutineStart.UNDISPATCHED) { controller.connection.collect { onConnection() } }
            driverLoop()
        }
    }

    private sealed interface Step {
        data object Done : Step
        data class Wait(val ms: Long) : Step
        data class Apply(val blockIndex: Int, val speedKmh: Double, val inclinePercent: Double, val epoch: Long) : Step
        data object EndProgram : Step
    }

    private suspend fun driverLoop() {
        while (true) {
            when (val step = nextStep()) {
                Step.Done -> return
                is Step.Wait -> withTimeoutOrNull(max(1L, step.ms)) { wake.receive() }
                is Step.Apply -> applyTargets(step)
                Step.EndProgram -> {
                    completeProgram()
                    return
                }
            }
        }
    }

    private fun nextStep(): Step = synchronized(lock) {
        when (runState) {
            RunState.Idle, RunState.Finished -> return Step.Done
            RunState.Running -> Unit
            else -> return Step.Wait(1_000)
        }
        accumulate()
        checkInterruptions()
        if (runState != RunState.Running) return Step.Wait(config.tickMs)

        val p = checkNotNull(plan)
        val block = p.blocks[blockIndex]
        if (blockDone(block)) {
            if (blockIndex == p.blocks.lastIndex) {
                emit(ProgramEvent.BlockCompleted(clock.nowMillis(), programSeconds(), blockIndex, (blockElapsedMs / 1000).toInt(), blockMeters, BlockChangeReason.Completed))
                runState = RunState.Stopping
                epoch++
                publish()
                return Step.EndProgram
            }
            advanceTo(blockIndex + 1, BlockChangeReason.Completed, carryOver = true)
            publish()
            return Step.Wait(0)
        }
        publish()
        if (needsApply) return Step.Apply(blockIndex, targetSpeed ?: block.speedKmh, targetIncline ?: block.inclinePercent, epoch)
        val remaining = (block.end as? BlockEnd.Duration)?.let { it.seconds * 1000L - blockElapsedMs }
        Step.Wait(if (remaining != null) min(config.tickMs, remaining) else config.tickMs)
    }

    private fun blockDone(block: ResolvedBlock): Boolean = when (val end = block.end) {
        is BlockEnd.Duration -> blockElapsedMs >= end.seconds * 1000L
        is BlockEnd.Distance -> blockMeters >= end.meters
    }

    /** Sends the targets that differ from what the treadmill already has. Called outside the lock. */
    private suspend fun applyTargets(step: Step.Apply) {
        var sentSpeed: Double? = null
        var sentIncline: Double? = null

        // The speed is skipped only when the treadmill's own target is known and already the wanted one. The controller
        // publishes no target while the console buttons are changing the speed, and then the speed is always sent: the
        // actual speed says nothing about where the belt is heading.
        val knownTarget = controller.state.value.targetSpeedKmh
        val speedDiffers = knownTarget == null || abs(knownTarget - step.speedKmh) > config.speedToleranceKmh
        if (speedDiffers) {
            if (!commandSucceeded("setSpeed", controller.setSpeed(step.speedKmh), step.epoch)) return
            sentSpeed = step.speedKmh
        }

        val s2 = controller.state.value
        if (abs(s2.inclinePercent - step.inclinePercent) >= config.inclineTolerancePercent) {
            if (!commandSucceeded("setIncline", controller.setIncline(step.inclinePercent), step.epoch)) return
            sentIncline = step.inclinePercent
        }

        synchronized(lock) {
            if (epoch != step.epoch || runState != RunState.Running) return
            needsApply = false
            if (sentSpeed != null || sentIncline != null) {
                emit(ProgramEvent.TargetsApplied(clock.nowMillis(), programSeconds(), step.blockIndex, sentSpeed, sentIncline))
            }
            publish()
        }
    }

    /** True when the command worked and the engine is still in the state it was issued in. */
    private fun commandSucceeded(name: String, result: CommandResult, epochAtIssue: Long): Boolean = synchronized(lock) {
        if (epoch != epochAtIssue || runState != RunState.Running) return false
        when {
            result == CommandResult.Success -> true
            result is CommandResult.NotVerified && result.code == FailureCode.Preempted -> false
            else -> {
                failedCommand(name, result)
                publish()
                false
            }
        }
    }

    /** The last block is over: stop the belt (or hand over) and finish. Runs in the driver or in a user action. */
    private suspend fun completeProgram() {
        val endAction = synchronized(lock) { options.endAction }
        val result = if (endAction == EndAction.StopBelt) controller.stop() else null
        synchronized(lock) { finish(RunOutcome.Completed, endAction, result) }
    }

    // ------------------------------------------------------------------ inputs from the controller

    private fun onTreadmillState(s: TreadmillState) {
        synchronized(lock) {
            if (runState == RunState.Idle || runState == RunState.Finished) return
            val previous = lastDistance
            if (runState == RunState.Running) {
                accumulate()
                if (previous != null && s.distanceMeters > previous) {
                    val delta = s.distanceMeters - previous
                    blockMeters += delta
                    totalMeters += delta
                }
            }
            lastDistance = s.distanceMeters
            checkInterruptions()
            publish()
        }
        wake()
    }

    private fun onConnection() {
        synchronized(lock) {
            checkInterruptions()
            publish()
        }
        wake()
    }

    private fun onControllerEvent(e: TreadmillEvent) {
        synchronized(lock) {
            when (e) {
                TreadmillEvent.SafetyKeyRemoved -> interruptionNow(InterruptionReason.SafetyKeyRemoved, null)
                TreadmillEvent.ConnectionLost -> interruptionNow(InterruptionReason.ConnectionLost, null)
                TreadmillEvent.DataStalled -> interruptionNow(InterruptionReason.DataStalled, null)
                is TreadmillEvent.CommandNotExecuted -> interruptionNow(InterruptionReason.CommandNotExecuted, e.command)
                is TreadmillEvent.ManualChange -> onManualChange(e)
                TreadmillEvent.SafetyKeyRestored -> Unit
            }
            publish()
        }
        wake()
    }

    private fun onManualChange(e: TreadmillEvent.ManualChange) {
        if (runState != RunState.Running || busy) return
        emit(ProgramEvent.ManualChange(clock.nowMillis(), programSeconds(), blockIndex, e.speedKmh, e.inclinePercent))
        // While the block's own targets are still pending they win: the block "applies its own values".
        if (needsApply) return
        e.speedKmh?.let { targetSpeed = it }
        e.inclinePercent?.let { targetIncline = it }
        manualOverride = true
    }

    // ------------------------------------------------------------------ state changes (all under the lock)

    /** Credits the time since the last call to the clock, but only while running. */
    private fun accumulate() {
        val t = clock.nowMillis()
        if (runState == RunState.Running) {
            val dt = max(0L, t - lastAccountAt)
            blockElapsedMs += dt
            totalElapsedMs += dt
        }
        lastAccountAt = t
    }

    private fun programSeconds(): Int = (currentTotalMs() / 1000).toInt()

    private fun currentBlockMs(): Long = blockElapsedMs + pendingMs()

    private fun currentTotalMs(): Long = totalElapsedMs + pendingMs()

    private fun pendingMs(): Long = if (runState == RunState.Running) max(0L, clock.nowMillis() - lastAccountAt) else 0L

    private fun setBlockTargets(block: ResolvedBlock) {
        targetSpeed = block.speedKmh
        targetIncline = block.inclinePercent
        manualOverride = false
    }

    /**
     * Moves to block [index]. Its own targets replace any manual change. With [carryOver] (a block that ended by
     * itself) the overshoot of the finished block is credited to the new one so the program does not drift.
     */
    private fun advanceTo(index: Int, reason: BlockChangeReason, carryOver: Boolean) {
        val p = checkNotNull(plan)
        val old = p.blocks[blockIndex]
        emit(ProgramEvent.BlockCompleted(clock.nowMillis(), programSeconds(), blockIndex, (currentBlockMs() / 1000).toInt(), blockMeters, reason))
        var carryMs = 0L
        var carryMeters = 0
        if (carryOver) {
            when (val end = old.end) {
                is BlockEnd.Duration -> carryMs = max(0L, blockElapsedMs - end.seconds * 1000L)
                is BlockEnd.Distance -> carryMeters = max(0, blockMeters - end.meters)
            }
        }
        blockIndex = index
        blockElapsedMs = carryMs
        blockMeters = carryMeters
        setBlockTargets(p.blocks[index])
        needsApply = true
        epoch++
        emit(ProgramEvent.BlockStarted(clock.nowMillis(), programSeconds(), index, p.blocks[index], reason))
    }

    /** Back to running after a resume or continue: the clock restarts and the block's targets are checked again. */
    private fun enterRunning(event: ProgramEvent) {
        lastAccountAt = clock.nowMillis()
        lastDistance = controller.state.value.distanceMeters
        runState = RunState.Running
        needsApply = true
        epoch++
        emit(event)
        checkInterruptions()
    }

    private fun refuseIfNotReady(): RunnerResult? {
        val state = controller.state.value
        return when {
            controller.connection.value !is ConnectionState.Connected -> RunnerResult.NotAllowed(Refusal.NotConnected, "The treadmill is not connected")
            state.status == BeltStatus.SafetyKeyRemoved -> RunnerResult.NotAllowed(Refusal.SafetyKeyRemoved, "The safety key is removed")
            state.isStale -> RunnerResult.NotAllowed(Refusal.DataNotCurrent, "The treadmill data is not current")
            else -> null
        }
    }

    private fun notAllowedState(message: String) = RunnerResult.NotAllowed(Refusal.WrongState, "$message (state: $runState)")

    private fun resultOf(result: CommandResult): RunnerResult =
        if (result == CommandResult.Success) RunnerResult.Success else RunnerResult.CommandFailed(result)

    /** A command of the engine or of a user action failed: the program is interrupted. */
    private fun failedCommand(name: String, result: CommandResult) {
        when (result) {
            is CommandResult.NotConnected -> interruptionNow(InterruptionReason.ConnectionLost, "$name: not connected")
            is CommandResult.Failure -> interruptionNow(InterruptionReason.CommandFailed, "$name: ${result.code}: ${result.reason}")
            CommandResult.Success -> Unit
        }
    }

    /** Looks at the connection and at the belt for things the engine did not ask for. */
    private fun checkInterruptions() {
        if (initialStart) return
        if (runState != RunState.Running && runState != RunState.Paused && runState != RunState.Starting) return
        if (controller.connection.value !is ConnectionState.Connected) {
            interruptionNow(InterruptionReason.ConnectionLost, "connection state ${controller.connection.value}")
            return
        }
        val status = controller.state.value.status
        if (status == BeltStatus.SafetyKeyRemoved) {
            interruptionNow(InterruptionReason.SafetyKeyRemoved, null)
            return
        }
        if (busy) return
        when (runState) {
            RunState.Running -> if (status == BeltStatus.Idle || status == BeltStatus.Paused) {
                interruptionNow(InterruptionReason.BeltChangedExternally, "the belt is $status while the program is running")
            }
            RunState.Paused -> if (status != BeltStatus.Paused) {
                interruptionNow(InterruptionReason.BeltChangedExternally, "the belt is $status while the program is paused")
            }
            else -> Unit
        }
    }

    private fun interruptionNow(reason: InterruptionReason, detail: String?) {
        if (initialStart) return
        if (runState != RunState.Running && runState != RunState.Paused && runState != RunState.Starting) return
        accumulate()
        val i = Interruption(reason, detail, blockIndex, clock.nowMillis())
        interruption = i
        runState = RunState.Interrupted
        busy = false
        epoch++
        emit(ProgramEvent.Interrupted(clock.nowMillis(), programSeconds(), i))
        publish()
    }

    private fun finish(result: RunOutcome, endAction: EndAction?, stopResult: CommandResult?) {
        if (runState == RunState.Finished || runState == RunState.Idle) return
        accumulate()
        runState = RunState.Finished
        outcome = result
        busy = false
        epoch++
        emit(ProgramEvent.Finished(clock.nowMillis(), programSeconds(), result, endAction, stopResult))
        publish()
        runJob?.cancel()
        runJob = null
    }

    /** The start failed before the program began: nothing happened, the runner is idle again. */
    private fun resetToIdle() {
        runState = RunState.Idle
        plan = null
        interruption = null
        initialStart = false
        busy = false
        epoch++
        publish()
        runJob?.cancel()
        runJob = null
    }

    private fun emit(event: ProgramEvent) {
        _events.tryEmit(event)
    }

    private fun wake() {
        wake.trySend(Unit)
    }

    // ------------------------------------------------------------------ progress

    private fun publish() {
        val s = controller.state.value
        val hr = heartRate.latest()
        val p = plan
        if (p == null) {
            _progress.value = ProgramProgress(state = runState, outcome = outcome)
            return
        }
        val index = blockIndex.coerceIn(0, p.blocks.lastIndex)
        val block = p.blocks[index]
        val blockMs = currentBlockMs()
        val blockElapsedS = (blockMs / 1000).toInt()
        val remainingMeters = (block.end as? BlockEnd.Distance)?.let { max(0, it.meters - blockMeters) }
        val blockRemainingS = when (val end = block.end) {
            is BlockEnd.Duration -> max(0, ceil((end.seconds * 1000L - blockMs) / 1000.0).toInt())
            is BlockEnd.Distance -> (max(0, end.meters - blockMeters) / (block.speedKmh / 3.6)).roundToInt()
        }
        val rest = p.blocks.drop(index + 1).sumOf { it.estimatedDurationSeconds }
        _progress.value = ProgramProgress(
            state = runState,
            outcome = outcome,
            interruption = interruption,
            plan = p,
            currentBlockIndex = index,
            currentBlock = block,
            nextBlock = p.blocks.getOrNull(index + 1),
            blockElapsedSeconds = blockElapsedS,
            blockRemainingSeconds = if (runState == RunState.Finished && outcome == RunOutcome.Completed) 0 else blockRemainingS,
            blockElapsedMeters = blockMeters,
            blockRemainingMeters = remainingMeters,
            totalElapsedSeconds = (currentTotalMs() / 1000).toInt(),
            totalRemainingSeconds = if (runState == RunState.Finished && outcome == RunOutcome.Completed) 0 else blockRemainingS + rest,
            totalElapsedMeters = totalMeters,
            targetSpeedKmh = targetSpeed,
            targetInclinePercent = targetIncline,
            manualOverride = manualOverride,
            actualSpeedKmh = s.speedKmh,
            actualInclinePercent = s.inclinePercent,
            heartRateBpm = hr.bpm,
            heartRateValidity = hr.validity,
        )
    }
}
