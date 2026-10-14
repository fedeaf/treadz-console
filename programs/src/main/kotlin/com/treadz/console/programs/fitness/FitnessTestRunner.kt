package com.treadz.console.programs.fitness

import com.treadz.console.programs.heartrate.ControllerHeartRateSource
import com.treadz.console.programs.heartrate.HeartRateReading
import com.treadz.console.programs.heartrate.HeartRateSource
import com.treadz.console.programs.heartrate.HeartRateValidity
import com.treadz.console.treadmill.BeltStatus
import com.treadz.console.treadmill.CommandResult
import com.treadz.console.treadmill.ConnectionState
import com.treadz.console.treadmill.FailureCode
import com.treadz.console.treadmill.SafetyLimits
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
import java.util.UUID
import kotlin.math.abs
import kotlin.math.max

/**
 * Runs the fitness tests ([EbbelingProtocol], [MaxIncrementalProtocol], [MaxHeartRateWalkProtocol]) on the treadmill.
 *
 * A dedicated runner rather than [com.treadz.console.programs.engine.ProgramRunner]: the tests are adaptive (the
 * Ebbeling speed follows the heart rate, stage 2 is extended until the heart rate is steady, the maximal test ends when
 * the user says so), so a fixed plan does not describe them. The protocol logic is a pure state machine on test time;
 * this class only feeds it the treadmill data and applies its decisions.
 *
 * **Rules**
 *  - Every command goes through the [TreadmillController], which verifies it; the transport is never touched.
 *  - The test clock is the injected [clock], from the moment the belt is confirmed running; the treadmill's
 *    time/distance targets are never used.
 *  - **Interruptions** (safety key, connection lost, the belt stopped/paused from elsewhere, a command not executed or
 *    failed, frozen data) end the test as invalid. The runner sends no more commands and never restarts the belt or
 *    resumes the test: a new test has to be started.
 *  - [stop] (the app's STOP) always stops the belt; the test is invalid (except as said above). During the cool-down it
 *    just cuts it short.
 *  - [endTest] ends the test early (invalid) with a cool-down walk; [exhausted] is the normal end of the maximal test.
 *  - Manual speed/incline changes (console buttons; while a test runs the UI must not call the controller's
 *    `setSpeed`/`setIncline`) break both protocols (see each protocol), except in the maximal test's warm-up, in
 *    the cool-down, and speed changes in the first part of Ebbeling stage 1, where they are respected. The app's own
 *    way to change the Ebbeling stage 1 speed is [adjustSpeed].
 *  - [stop] in a maximal test (running or walking) after at least 3 min of the incremental phase (or the belt stopped
 *    from the console) counts as exhaustion: the result is valid and there is no cool-down. Everywhere else a STOP
 *    makes the test invalid.
 *  - The heart rate comes from [heartRate] (normally an `AutomaticHeartRateSource`); the result records the source of
 *    most valid readings ([FitnessTestInputs.heartRateSource]), else [heartRateSourceKind].
 *  - At the end of the cool-down the belt is stopped.
 *
 * Thread safety: all state lives behind one lock and is only changed in short non-suspending sections; controller calls
 * are made outside it. Suspending user actions run in [scope] and are not cancelled with the caller.
 */
class FitnessTestRunner(
    private val controller: TreadmillController,
    private val scope: CoroutineScope,
    private val clock: Clock = Clock.System,
    private val heartRate: HeartRateSource = ControllerHeartRateSource(controller),
    private val config: FitnessTestConfig = FitnessTestConfig(),
    private val heartRateSourceKind: HeartRateSourceKind = HeartRateSourceKind.TreadmillConsole,
    private val idFactory: () -> String = { UUID.randomUUID().toString() },
) {
    private val _progress = MutableStateFlow(FitnessTestProgress())

    /** What the UI shows. Updated with every treadmill packet and at least every [FitnessTestConfig.tickMs]. */
    val progress: StateFlow<FitnessTestProgress> = _progress.asStateFlow()

    private val _events = MutableSharedFlow<FitnessTestEvent>(extraBufferCapacity = 512)

    /** Events of the test, in order. No replay. */
    val events: SharedFlow<FitnessTestEvent> = _events.asSharedFlow()

    private val lock = Any()
    private val wake = Channel<Unit>(Channel.CONFLATED)

    @Volatile
    private var runJob: Job? = null

    // -- guarded by [lock]
    private var protocol: FitnessProtocol? = null

    /** From the call to start() until the belt is confirmed running. */
    private var starting = false

    /** The test clock runs (the protocol has begun) and the run is not finished. */
    private var running = false

    /** The runner is stopping the belt itself: an Idle belt is expected. */
    private var stopping = false
    private var epoch = 0L
    private var startedAt = 0L
    private var appliedSpeed: Double? = null
    private var appliedIncline: Double? = null

    // ------------------------------------------------------------------ user actions

    /**
     * Starts the Ebbeling walking test: needs a current, non-zero heart rate and limits allowing 3.2 km/h and 5 %.
     * Returns when the belt is confirmed running and the test clock has started.
     */
    suspend fun startEbbeling(parameters: EbbelingParameters): FitnessActionResult = scope.async {
        startBody(requireHeartRate = true, parameters.resultId) { id, limits ->
            if ((limits.maxInclinePercent ?: Double.MAX_VALUE) < config.stage2GradePercent - 1e-9 ||
                (limits.maxSpeedKmh ?: Double.MAX_VALUE) < config.ebbelingMinSpeedKmh - 1e-9
            ) {
                return@startBody null
            }
            EbbelingProtocol(
                id, parameters.subject, config, parameters.startSpeedKmh, limits.maxSpeedKmh, limits.maxInclinePercent,
                heartRateSourceKind, ::clampSpeed,
            )
        }
    }.await()

    /** Starts the maximal incremental test (no heart rate needed). */
    suspend fun startMaxTest(parameters: MaxTestParameters): FitnessActionResult = scope.async {
        startBody(requireHeartRate = false, parameters.resultId) { id, limits ->
            val cap = clampSpeed(Double.MAX_VALUE)
            if (cap < config.maxTestMinStartSpeedKmh - 1e-9) return@startBody null
            MaxIncrementalProtocol(
                id, parameters.subject, config, parameters.startSpeedKmh, limits.maxSpeedKmh, limits.maxInclinePercent,
                heartRateSourceKind, ::clampSpeed, cap,
            )
        }
    }.await()

    /**
     * Starts the maximal heart-rate walking test (modified Balke): needs a current, non-zero heart rate (its only purpose
     * is the maximum heart rate) and a speed cap of at least 3 km/h.
     */
    suspend fun startMaxHeartRateWalk(parameters: MaxHeartRateWalkParameters): FitnessActionResult = scope.async {
        startBody(requireHeartRate = true, parameters.resultId) { id, limits ->
            val speedCap = clampSpeed(Double.MAX_VALUE)
            if (speedCap < config.walkTestMinStartSpeedKmh - 1e-9) return@startBody null
            val inclineCap = clampIncline(config.walkTestMaxInclinePercent)
            MaxHeartRateWalkProtocol(
                id, parameters.subject, config, parameters.startSpeedKmh, parameters.startInclinePercent,
                limits.maxSpeedKmh, limits.maxInclinePercent, heartRateSourceKind, ::clampSpeed, ::clampIncline,
                speedCap, inclineCap,
            )
        }
    }.await()

    private suspend fun startBody(
        requireHeartRate: Boolean,
        resultId: String?,
        create: (String, SafetyLimits) -> FitnessProtocol?,
    ): FitnessActionResult {
        val beltAction: BeltAction
        synchronized(lock) {
            val current = protocol
            if (starting || running || (current != null && !current.isFinished)) {
                return FitnessActionResult.NotAllowed(FitnessRefusal.AlreadyActive, "A test is already active")
            }
            if (controller.connection.value !is ConnectionState.Connected) {
                return FitnessActionResult.NotAllowed(FitnessRefusal.NotConnected, "The treadmill is not connected")
            }
            val state = controller.state.value
            if (state.isStale) return FitnessActionResult.NotAllowed(FitnessRefusal.DataNotCurrent, "The treadmill data is not current")
            beltAction = when (state.status) {
                BeltStatus.Idle -> BeltAction.Start
                BeltStatus.Paused -> BeltAction.Resume
                BeltStatus.Running -> BeltAction.None
                BeltStatus.Starting -> return FitnessActionResult.NotAllowed(FitnessRefusal.BeltBusy, "The belt is starting")
                BeltStatus.SafetyKeyRemoved -> return FitnessActionResult.NotAllowed(FitnessRefusal.SafetyKeyRemoved, "The safety key is removed")
            }
            if (requireHeartRate && readHeartRate() == null) {
                return FitnessActionResult.NotAllowed(FitnessRefusal.NoHeartRate, "No current heart rate: put the chest strap on")
            }
            val p = create(resultId ?: idFactory(), controller.limits.value)
                ?: return FitnessActionResult.NotAllowed(FitnessRefusal.LimitsTooLow, "The safety limits do not allow this test")
            if (!validSubject(p.subject)) {
                return FitnessActionResult.NotAllowed(FitnessRefusal.InvalidSubject, "Age, weight or maximum heart rate out of range")
            }
            protocol = p
            starting = true
            running = false
            stopping = false
            epoch++
            publish()
        }
        launchRun()

        val result = when (beltAction) {
            BeltAction.None -> CommandResult.Success
            BeltAction.Resume -> controller.resume()
            BeltAction.Start -> controller.start()
        }

        synchronized(lock) {
            starting = false
            val p = protocol
            if (result != CommandResult.Success || p == null) {
                protocol = null
                running = false
                epoch++
                runJob?.cancel()
                runJob = null
                publish()
                return if (result is CommandResult.NotConnected) {
                    FitnessActionResult.NotAllowed(FitnessRefusal.NotConnected, "The connection was lost while starting")
                } else {
                    FitnessActionResult.CommandFailed(result)
                }
            }
            startedAt = clock.nowMillis()
            p.begin(startedAt)
            running = true
            appliedSpeed = null
            appliedIncline = controller.state.value.inclinePercent
            emit(FitnessTestEvent.Started(startedAt, p.testType, inputsOf(p)))
            drainEvents(p)
            checkInterruptions()
            publish()
        }
        wake()
        return FitnessActionResult.Success
    }

    private enum class BeltAction { None, Resume, Start }

    /** Maximal tests (running or walking): the user cannot keep up any more. Ends the effort; the cool-down follows. */
    fun exhausted(): FitnessActionResult {
        synchronized(lock) {
            val p = protocol
            if (p == null || !running) return FitnessActionResult.NotAllowed(FitnessRefusal.NotActive, "No test is running")
            if (!p.exhausted(testTime())) {
                return FitnessActionResult.NotAllowed(FitnessRefusal.WrongState, "Exhaustion does not apply now")
            }
            drainEvents(p)
            publish()
        }
        wake()
        return FitnessActionResult.Success
    }

    /**
     * Ebbeling, first part of stage 1 (while [FitnessTestProgress.speedLocked] is false): the user sets the walking
     * speed. It is clamped to 3.2-7.2 km/h and the safety limits, becomes the base speed and is applied by the run
     * loop through the controller (within a tick), so the result is "accepted", not "verified": watch
     * [FitnessTestProgress.targetSpeedKmh] / `actualSpeedKmh`. Refused ([FitnessRefusal.WrongState]) once the speed is
     * frozen, in stage 2 and in the maximal test.
     */
    fun adjustSpeed(speedKmh: Double): FitnessActionResult {
        synchronized(lock) {
            val p = protocol
            if (p == null || !running || stopping) return FitnessActionResult.NotAllowed(FitnessRefusal.NotActive, "No test is running")
            if (!speedKmh.isFinite() || p.adjustSpeedByApp(testTime(), speedKmh) == null) {
                return FitnessActionResult.NotAllowed(FitnessRefusal.WrongState, "The speed cannot be changed now")
            }
            drainEvents(p)
            publish()
        }
        wake()
        return FitnessActionResult.Success
    }

    /**
     * Walking heart-rate test, during the warm-up: changes the ramp start speed and/or incline (`null` keeps a value).
     * Clamped to 3-7.5 km/h, 0-15 % and the caps; the warm-up speeds follow (unless changed by hand in the current
     * warm-up minute). Refused ([FitnessRefusal.WrongState]) in any other phase or test.
     */
    fun adjustRampStart(speedKmh: Double?, inclinePercent: Double?): FitnessActionResult {
        synchronized(lock) {
            val p = protocol
            if (p == null || !running || stopping) return FitnessActionResult.NotAllowed(FitnessRefusal.NotActive, "No test is running")
            if ((speedKmh != null && !speedKmh.isFinite()) || (inclinePercent != null && !inclinePercent.isFinite()) ||
                p !is MaxHeartRateWalkProtocol || !p.adjustRampStart(speedKmh, inclinePercent)
            ) {
                return FitnessActionResult.NotAllowed(FitnessRefusal.WrongState, "The ramp start cannot be changed now")
            }
            drainEvents(p)
            publish()
        }
        wake()
        return FitnessActionResult.Success
    }

    /** Ends the test early (invalid); the cool-down walk follows. */
    fun endTest(): FitnessActionResult {
        synchronized(lock) {
            val p = protocol
            if (p == null || !running) return FitnessActionResult.NotAllowed(FitnessRefusal.NotActive, "No test is running")
            if (!p.endByUser(testTime())) return FitnessActionResult.NotAllowed(FitnessRefusal.WrongState, "The test is already over")
            drainEvents(p)
            publish()
        }
        wake()
        return FitnessActionResult.Success
    }

    /**
     * STOP: stops the belt. During the test the result is invalid; during the cool-down the result stands and the
     * cool-down is cut short. While the belt is still starting, the start is cancelled and no test is recorded.
     */
    suspend fun stop(): FitnessActionResult = scope.async {
        val duringStart: Boolean
        synchronized(lock) {
            val p = protocol ?: return@async FitnessActionResult.NotAllowed(FitnessRefusal.NotActive, "No test is active")
            duringStart = starting
            if (!duringStart) {
                if (!running) return@async FitnessActionResult.NotAllowed(FitnessRefusal.NotActive, "No test is active")
                p.stopByUser(testTime())
                stopping = true
                epoch++
                drainEvents(p)
                publish()
            }
        }
        val result = controller.stop()
        if (!duringStart) synchronized(lock) { finishRun(coolDownCompleted = false) }
        if (result == CommandResult.Success) FitnessActionResult.Success else FitnessActionResult.CommandFailed(result)
    }.await()

    // ------------------------------------------------------------------ run loop

    private fun launchRun() {
        runJob?.cancel()
        runJob = scope.launch(start = CoroutineStart.UNDISPATCHED) {
            launch(start = CoroutineStart.UNDISPATCHED) { controller.events.collect(::onControllerEvent) }
            launch(start = CoroutineStart.UNDISPATCHED) { controller.state.collect(::onTreadmillState) }
            launch(start = CoroutineStart.UNDISPATCHED) { controller.connection.collect { onConnection() } }
            driverLoop()
        }
    }

    private sealed interface Step {
        data object Done : Step
        data class Wait(val ms: Long) : Step
        data class Apply(val speedKmh: Double?, val inclinePercent: Double?, val epoch: Long) : Step
        data class StopBelt(val epoch: Long) : Step
    }

    private suspend fun driverLoop() {
        while (true) {
            when (val step = nextStep()) {
                Step.Done -> return
                is Step.Wait -> withTimeoutOrNull(max(1L, step.ms)) { wake.receive() }
                is Step.Apply -> apply(step)
                is Step.StopBelt -> {
                    val result = controller.stop()
                    synchronized(lock) {
                        // A failed final stop is the controller's business (it raises its alert); the test itself is over.
                        if (epoch == step.epoch) finishRun(coolDownCompleted = true)
                    }
                    return
                }
            }
        }
    }

    private fun nextStep(): Step = synchronized(lock) {
        val p = protocol ?: return Step.Done
        if (p.isFinished) return Step.Done
        if (!running || stopping) return Step.Wait(config.tickMs)
        checkInterruptions()
        if (!running) return Step.Done
        val t = testTime()
        val directive = p.update(t, observation())
        drainEvents(p)
        publish()
        when (directive) {
            Directive.Finish -> {
                p.beginFinalStop(t)
                stopping = true
                epoch++
                drainEvents(p)
                publish()
                Step.StopBelt(epoch)
            }
            is Directive.Targets -> {
                val s = directive.speedKmh?.let(::clampSpeed)
                val i = directive.inclinePercent?.let(::clampIncline)
                val sendSpeed = s != null && (appliedSpeed?.let { abs(it - s) > SPEED_TOLERANCE } ?: true)
                val sendIncline = i != null && (appliedIncline?.let { abs(it - i) >= INCLINE_TOLERANCE } ?: true)
                if (sendSpeed || sendIncline) {
                    Step.Apply(if (sendSpeed) s else null, if (sendIncline) i else null, epoch)
                } else {
                    Step.Wait(config.tickMs)
                }
            }
        }
    }

    private suspend fun apply(step: Step.Apply) {
        step.speedKmh?.let { s ->
            if (!commandWorked("setSpeed", controller.setSpeed(s), step.epoch)) return
            synchronized(lock) { appliedSpeed = s }
        }
        step.inclinePercent?.let { i ->
            if (!commandWorked("setIncline", controller.setIncline(i), step.epoch)) return
            synchronized(lock) { appliedIncline = i }
        }
        synchronized(lock) {
            if (epoch != step.epoch || !running) return
            emit(FitnessTestEvent.TargetsApplied(clock.nowMillis(), (testTime() / 1000).toInt(), step.speedKmh, step.inclinePercent))
        }
    }

    /** True when the command worked and nothing changed meanwhile; a real failure ends the test. */
    private fun commandWorked(name: String, result: CommandResult, epochAtIssue: Long): Boolean = synchronized(lock) {
        if (epoch != epochAtIssue || !running || stopping) return false
        when {
            result == CommandResult.Success -> true
            result is CommandResult.NotVerified && result.code == FailureCode.Preempted -> false
            result is CommandResult.NotConnected -> {
                interrupt(FitnessEndReason.ConnectionLost, "$name: not connected")
                false
            }
            else -> {
                val code = (result as? CommandResult.Failure)?.code
                interrupt(FitnessEndReason.CommandFailed, "$name: $code: ${(result as? CommandResult.Failure)?.reason}")
                false
            }
        }
    }

    // ------------------------------------------------------------------ inputs from the controller

    private fun onTreadmillState(@Suppress("UNUSED_PARAMETER") s: TreadmillState) {
        synchronized(lock) {
            val p = protocol ?: return
            if (running && !p.isFinished) {
                p.observe(testTime(), observation())
                checkInterruptions()
            }
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
            if (!running || starting) return
            when (e) {
                TreadmillEvent.SafetyKeyRemoved -> interrupt(FitnessEndReason.SafetyKeyRemoved, null)
                TreadmillEvent.ConnectionLost -> interrupt(FitnessEndReason.ConnectionLost, null)
                TreadmillEvent.DataStalled -> interrupt(FitnessEndReason.DataStalled, null)
                is TreadmillEvent.CommandNotExecuted -> interrupt(FitnessEndReason.CommandNotExecuted, e.command)
                is TreadmillEvent.ManualChange -> onManualChange(e)
                TreadmillEvent.SafetyKeyRestored -> Unit
            }
            publish()
        }
        wake()
    }

    private fun onManualChange(e: TreadmillEvent.ManualChange) {
        val p = protocol ?: return
        if (stopping || p.isFinished) return
        // What the treadmill now has is what the user chose: never send it again for nothing.
        e.speedKmh?.let { appliedSpeed = it }
        e.inclinePercent?.let { appliedIncline = it }
        p.manualChange(testTime(), e.speedKmh, e.inclinePercent)
        epoch++
        drainEvents(p)
    }

    /** Looks at the connection and the belt for changes the runner did not ask for. Under the lock. */
    private fun checkInterruptions() {
        if (!running || starting || stopping) return
        if (controller.connection.value !is ConnectionState.Connected) {
            interrupt(FitnessEndReason.ConnectionLost, "connection state ${controller.connection.value}")
            return
        }
        when (controller.state.value.status) {
            BeltStatus.SafetyKeyRemoved -> interrupt(FitnessEndReason.SafetyKeyRemoved, null)
            BeltStatus.Idle, BeltStatus.Paused -> interrupt(FitnessEndReason.BeltStoppedExternally, "the belt is ${controller.state.value.status}")
            else -> Unit
        }
    }

    /** Ends the run without any further command. Under the lock. */
    private fun interrupt(reason: FitnessEndReason, detail: String?) {
        val p = protocol ?: return
        if (!running || p.isFinished) return
        p.abort(testTime(), reason, detail)
        endRun(p)
    }

    /** Under the lock. */
    private fun finishRun(coolDownCompleted: Boolean) {
        val p = protocol ?: return
        if (p.isFinished) return
        p.finish(testTime(), coolDownCompleted)
        endRun(p)
    }

    private fun endRun(p: FitnessProtocol) {
        running = false
        stopping = false
        epoch++
        drainEvents(p)
        publish()
        runJob?.cancel()
        runJob = null
    }

    // ------------------------------------------------------------------ helpers (under the lock)

    private fun testTime(): Long = max(0L, clock.nowMillis() - startedAt)

    private fun readHeartRate(reading: HeartRateReading = heartRate.latest()): Int? {
        val bpm = reading.bpm ?: return null
        if (reading.validity == HeartRateValidity.Invalid || bpm < config.minPlausibleBpm) return null
        return bpm
    }

    private fun observation(): Observation {
        val s = controller.state.value
        val reading = heartRate.latest()
        val bpm = readHeartRate(reading)
        return Observation(s.speedKmh, s.inclinePercent, bpm, if (bpm != null) reading.source else null)
    }

    private fun clampSpeed(kmh: Double): Double =
        SafetyClamp.speed(kmh, controller.capabilities.value?.speed ?: SafetyClamp.DEFAULT_SPEED_RANGE, controller.limits.value)

    private fun clampIncline(percent: Double): Double =
        SafetyClamp.incline(percent, controller.capabilities.value?.incline ?: SafetyClamp.DEFAULT_INCLINE_RANGE, controller.limits.value)

    private fun validSubject(s: FitnessSubject) =
        s.ageYears in 10..100 && s.weightKg in 25.0..280.0 && s.hrMaxBpm in 100..240

    private fun inputsOf(p: FitnessProtocol) = FitnessTestInputs(
        ageYears = p.subject.ageYears,
        sex = p.subject.sex,
        weightKg = p.subject.weightKg,
        hrMaxBpm = p.subject.hrMaxBpm,
        agePredictedHrMaxBpm = FitnessFormulas.agePredictedHrMax(p.subject.ageYears),
        heartRateSource = heartRate.latest().let { r -> if (readHeartRate(r) != null) r.source ?: heartRateSourceKind else null },
        requestedStartSpeedKmh = p.requestedStartSpeedKmh,
        startSpeedKmh = p.startSpeedKmh,
        maxSpeedLimitKmh = p.maxSpeedLimitKmh,
        maxInclineLimitPercent = p.maxInclineLimitPercent,
    )

    private fun drainEvents(p: FitnessProtocol) {
        if (p.pendingEvents.isEmpty()) return
        p.pendingEvents.forEach(::emit)
        p.pendingEvents.clear()
    }

    private fun emit(e: FitnessTestEvent) {
        _events.tryEmit(e)
    }

    private fun wake() {
        wake.trySend(Unit)
    }

    private fun publish() {
        val p = protocol
        if (p == null) {
            _progress.value = FitnessTestProgress()
            return
        }
        val reading = heartRate.latest()
        val t = if (running || p.isFinished) testTime() else 0L
        _progress.value = p.progress(t, controller.state.value, reading)
    }

    private companion object {
        const val SPEED_TOLERANCE = 0.05
        const val INCLINE_TOLERANCE = 0.5
    }
}
