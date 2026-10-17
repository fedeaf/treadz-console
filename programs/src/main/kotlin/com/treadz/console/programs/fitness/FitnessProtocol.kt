package com.treadz.console.programs.fitness

import com.treadz.console.analysis.fitness.FitnessFormulas
import com.treadz.console.analysis.samples.RrInterval
import com.treadz.console.analysis.samples.RrSeries
import com.treadz.console.analysis.samples.SampleHeartRateSource
import com.treadz.console.analysis.samples.WorkoutSample
import com.treadz.console.analysis.thresholds.ThresholdResult
import com.treadz.console.programs.heartrate.HeartRateReading
import com.treadz.console.treadmill.TreadmillState
import kotlin.math.max
import kotlin.math.min

/** What the protocol wants from the treadmill now. */
internal sealed interface Directive {
    /** Hold these targets; `null` = leave that value as it is. */
    data class Targets(val speedKmh: Double?, val inclinePercent: Double?) : Directive

    /** The cool-down is over: stop the belt and finish. */
    data object Finish : Directive
}

/**
 * What the treadmill reports, as the protocol sees it. [heartRateBpm] is `null` for an invalid reading; [source] is
 * where a valid one came from (strap connected to the app or console).
 */
internal data class Observation(
    val speedKmh: Double,
    val inclinePercent: Double,
    val heartRateBpm: Int?,
    val source: HeartRateSourceKind? = null,
)

/**
 * A fitness test protocol as a pure state machine on test time `t` (ms since the belt was running for the test). It
 * never talks to the treadmill: [FitnessTestRunner] feeds it observations, applies its [Directive]s through the
 * controller and reports user actions and interruptions. Not thread-safe: the runner calls it under its lock.
 *
 * Common lifecycle: [begin] → effort phases (protocol specific) → [endEffort] (the result is known) → cool-down
 * (when the belt can still be driven) → [finish]. An interruption or a STOP skips the cool-down.
 */
internal abstract class FitnessProtocol(
    val testType: FitnessTestType,
    val protocolVersion: Int,
    val id: String,
    val subject: FitnessSubject,
    val config: FitnessTestConfig,
    val requestedStartSpeedKmh: Double,
    val maxSpeedLimitKmh: Double?,
    val maxInclineLimitPercent: Double?,
    private val heartRateKind: HeartRateSourceKind,
    /** Clamps a speed to the treadmill range and the user's limits, on the 0.1 km/h grid. */
    protected val clampSpeed: (Double) -> Double,
) {
    var startedAtMillis = 0L
        private set
    var phase = FitnessTestPhase.Starting
        protected set
    protected var phaseStartT = 0L
    var targetSpeed: Double? = null
        protected set
    var targetIncline: Double? = null
        protected set

    val flags = linkedSetOf<FitnessTestFlag>()
    var endReason: FitnessEndReason? = null
        private set
    private var endDetail: String? = null
    var effortEndT: Long? = null
        private set
    private var coolDownStartT: Long? = null
    private var coolDownEndT: Long? = null
    private var coolDownSpeed = 0.0
    private var coolDownManual = false
    private var endT: Long? = null
    private var outputs: FitnessTestOutputs? = null
    private var thresholds: ThresholdResult? = null

    /** The load and heart rate seen during the effort, on the epoch clock (for the derived values at the end). */
    protected val effortSamples = ArrayList<WorkoutSample>()

    /** RR intervals of a strap connected to the app received during the effort, on the epoch clock. */
    protected val rrIntervals = ArrayList<RrInterval>()

    val hr = HeartRateLog()

    /** Valid readings per source, to record where the test's heart rate came from. */
    private val sourceCounts = mutableMapOf<HeartRateSourceKind, Int>()
    private val minutes = mutableListOf<MinuteRecord>()
    private var phaseMinutes = 0

    /** The minutes of the current phase are closed (its last, partial minute was recorded). */
    private var phaseClosed = false

    /** Events produced since the runner last drained them. */
    val pendingEvents = mutableListOf<FitnessTestEvent>()

    /** Known once the effort ended; final after [finish]. */
    var result: FitnessTestResult? = null
        private set

    /** Start speed after clamping; set by the subclass. */
    abstract val startSpeedKmh: Double
    protected abstract val coolDownSeconds: Int
    protected abstract val coolDownMaxSpeedKmh: Double

    val isEffortPhase: Boolean
        get() = phase == FitnessTestPhase.Stage1 || phase == FitnessTestPhase.Stage2 ||
            phase == FitnessTestPhase.WarmUp || phase == FitnessTestPhase.Incremental

    val isFinished: Boolean get() = phase == FitnessTestPhase.Finished

    // ------------------------------------------------------------------ lifecycle

    /** The belt is running: the test clock starts at t = 0. */
    fun begin(startedAt: Long) {
        startedAtMillis = startedAt
        onBegin()
    }

    protected abstract fun onBegin()

    /** Records a reading (called with every treadmill packet). */
    fun observe(t: Long, obs: Observation) {
        val bpm = plausibleHeartRate(t, obs.heartRateBpm)
        hr.add(t, bpm)
        val source = if (bpm != null) obs.source ?: heartRateKind else null
        if (source != null) sourceCounts[source] = (sourceCounts[source] ?: 0) + 1
        if (isEffortPhase && (effortSamples.isEmpty() || at(t) > effortSamples.last().timeMillis)) {
            effortSamples += WorkoutSample(
                at(t), obs.speedKmh, obs.inclinePercent, bpm,
                when (source) {
                    HeartRateSourceKind.DirectStrap -> SampleHeartRateSource.Strap
                    HeartRateSourceKind.TreadmillConsole -> SampleHeartRateSource.Console
                    null -> null
                },
            )
        }
        recordMinutes(t)
    }

    /**
     * RR intervals of one notification of a strap connected to the app, received at [t]. Kept only during the effort
     * (they feed the thresholds of the maximal tests).
     */
    fun addRrIntervals(t: Long, rrMs: List<Int>) {
        if (isEffortPhase) RrSeries.append(rrIntervals, at(t), rrMs)
    }

    /** Hook for protocols that discard impossible readings; the default keeps every valid reading. */
    protected open fun plausibleHeartRate(t: Long, bpm: Int?): Int? = bpm

    /** The source of most valid readings (the runner's default kind when the readings did not say). */
    protected val dominantSource: HeartRateSourceKind
        get() = sourceCounts.maxByOrNull { it.value }?.key ?: heartRateKind

    /** Advances the protocol to [t] and says what the treadmill should do. */
    fun update(t: Long, obs: Observation): Directive {
        observe(t, obs)
        if (isEffortPhase) effortUpdate(t, obs)
        return when (phase) {
            FitnessTestPhase.CoolDown -> {
                val start = coolDownStartT ?: t
                if (t - start >= coolDownSeconds * 1000L) {
                    Directive.Finish
                } else if (coolDownManual) {
                    Directive.Targets(null, null)
                } else {
                    Directive.Targets(coolDownSpeed, 0.0)
                }
            }
            FitnessTestPhase.Stage1, FitnessTestPhase.Stage2, FitnessTestPhase.WarmUp, FitnessTestPhase.Incremental ->
                Directive.Targets(targetSpeed, targetIncline)
            else -> Directive.Targets(null, null)
        }
    }

    protected abstract fun effortUpdate(t: Long, obs: Observation)

    /** A manual change of speed/incline. Returns true when it broke the test. */
    fun manualChange(t: Long, speedKmh: Double?, inclinePercent: Double?): Boolean {
        val broke = when {
            phase == FitnessTestPhase.CoolDown -> {
                coolDownManual = true
                false
            }
            isEffortPhase -> onManualChange(t, speedKmh, inclinePercent)
            else -> false
        }
        pendingEvents += FitnessTestEvent.ManualChange(at(t), speedKmh, inclinePercent, broke)
        return broke
    }

    /** Default: a manual change breaks the protocol (cool-down follows). */
    protected open fun onManualChange(t: Long, speedKmh: Double?, inclinePercent: Double?): Boolean {
        flags += FitnessTestFlag.ManualChange
        endEffort(t, FitnessEndReason.ManualChange, "speed=$speedKmh incline=$inclinePercent", coolDown = true)
        return true
    }

    /**
     * The user sets the speed with the app's controls ([FitnessTestRunner.adjustSpeed]). Returns the speed accepted
     * (clamped), or `null` when the protocol does not allow a speed change now. Default: not allowed.
     */
    open fun adjustSpeedByApp(t: Long, speedKmh: Double): Double? = null

    /** A manual change now does not break the test ([FitnessTestProgress.manualChangesAllowed]). Default: cool-down. */
    open val manualChangesAllowed: Boolean get() = phase == FitnessTestPhase.CoolDown

    /** "Exhausted" button. Returns false when it does not apply. */
    open fun exhausted(t: Long): Boolean = false

    /** "End test": ends the effort early, the result is invalid and the cool-down follows. */
    fun endByUser(t: Long): Boolean {
        if (!isEffortPhase) return false
        flags += FitnessTestFlag.EndedByUser
        endEffort(t, FitnessEndReason.EndedByUser, null, coolDown = true)
        return true
    }

    /** STOP: the belt is being stopped. Returns false when the test is already over. */
    fun stopByUser(t: Long): Boolean {
        if (isEffortPhase) {
            if (!endEffortOnStop(t, fromConsole = false)) {
                flags += FitnessTestFlag.StoppedByUser
                endEffort(t, FitnessEndReason.StoppedByUser, null, coolDown = false)
            }
        } else if (phase != FitnessTestPhase.CoolDown) {
            return false
        }
        leavePhase(t)
        phase = FitnessTestPhase.Stopping
        return true
    }

    /** Something outside the test stopped it (key, connection, failed command...): no cool-down, no more commands. */
    fun abort(t: Long, reason: FitnessEndReason, detail: String?) {
        if (isEffortPhase && !(reason == FitnessEndReason.BeltStoppedExternally && endEffortOnStop(t, fromConsole = true))) {
            flags += FitnessTestFlag.Interrupted
            endEffort(t, reason, detail, coolDown = false)
        }
        finish(t, coolDownCompleted = false)
    }

    /**
     * The belt is being stopped by the app's STOP ([fromConsole] false) or was stopped/paused from elsewhere
     * ([fromConsole] true) during the effort. A protocol for which that is a valid end of the effort (the maximal test
     * after enough incremental time) ends the effort here, without a cool-down, and returns true. Default: false (the
     * stop invalidates the test).
     */
    protected open fun endEffortOnStop(t: Long, fromConsole: Boolean): Boolean = false

    /** The belt is being stopped at the end of the cool-down. */
    fun beginFinalStop(t: Long) {
        leavePhase(t)
        phase = FitnessTestPhase.Stopping
    }

    /** Closes the current phase's minutes (and the cool-down time). */
    private fun leavePhase(t: Long) {
        if (phase == FitnessTestPhase.CoolDown && coolDownEndT == null) coolDownEndT = t
        closePartialMinute(t)
    }

    /** The run is over. */
    fun finish(t: Long, coolDownCompleted: Boolean) {
        if (phase == FitnessTestPhase.Finished) return
        if (effortEndT == null) {
            // Cannot normally happen (the effort always ends first); never leave a run without a result.
            flags += FitnessTestFlag.Interrupted
            endEffort(t, FitnessEndReason.CommandFailed, "finished without an end of effort", coolDown = false)
        }
        leavePhase(t)
        if (!coolDownCompleted) flags += FitnessTestFlag.CoolDownIncomplete
        endT = t
        phase = FitnessTestPhase.Finished
        val r = buildResult()
        result = r
        pendingEvents += FitnessTestEvent.Finished(at(t), r)
    }

    /**
     * Ends the measuring part: the result is computed now. With [coolDown] the cool-down walk follows (the belt can
     * still be driven); otherwise the caller stops or abandons the belt.
     */
    protected fun endEffort(t: Long, reason: FitnessEndReason, detail: String?, coolDown: Boolean) {
        if (effortEndT != null) return
        effortEndT = t
        endReason = reason
        endDetail = detail
        outputs = computeOutputs(t, reason)
        closeMeasurements(t)
        if (flags.none { it.severity == FlagSeverity.Invalidating }) thresholds = computeThresholds(t, reason, outputs)
        if (coolDown) {
            coolDownSpeed = clampSpeed(min(coolDownMaxSpeedKmh, targetSpeed ?: coolDownMaxSpeedKmh))
            enterPhase(t, FitnessTestPhase.CoolDown)
            coolDownStartT = t
            targetSpeed = coolDownSpeed
            targetIncline = 0.0
        } else {
            closePartialMinute(t)
        }
        val r = buildResult()
        result = r
        pendingEvents += FitnessTestEvent.EffortEnded(at(t), reason, r)
    }

    /** Outputs when the effort ends (they are kept only if the result is valid). May add flags. */
    protected abstract fun computeOutputs(t: Long, reason: FitnessEndReason): FitnessTestOutputs?

    /** Closes the protocol's own records at the end of the effort. */
    protected open fun closeMeasurements(t: Long) = Unit

    /**
     * Thresholds of a valid result, computed when the effort ends (after [computeOutputs] and [closeMeasurements]).
     * Default: none (Ebbeling).
     */
    protected open fun computeThresholds(t: Long, reason: FitnessEndReason, outputs: FitnessTestOutputs?): ThresholdResult? = null

    /**
     * The thresholds of the incremental phase from [incrementalStartT] to [t] with the RR intervals and samples of the
     * effort ([FitnessTestRecalculation.thresholds]).
     */
    protected fun incrementalThresholds(incrementalStartT: Long, t: Long, outputs: FitnessTestOutputs?): ThresholdResult =
        FitnessTestRecalculation.thresholds(
            rr = rrIntervals,
            samples = effortSamples,
            incrementalStartMillis = at(incrementalStartT),
            incrementalEndMillis = at(t),
            maxHeartRateBpm = subject.hrMaxBpm,
            restingHeartRateBpm = subject.restingHeartRateBpm,
            vo2maxMlKgMin = outputs?.vo2maxMlKgMin,
        )

    protected abstract fun measurements(): FitnessTestMeasurements

    // ------------------------------------------------------------------ phases and minutes

    protected fun enterPhase(t: Long, newPhase: FitnessTestPhase) {
        closePartialMinute(t)
        phase = newPhase
        phaseStartT = t
        phaseMinutes = 0
        phaseClosed = false
        pendingEvents += FitnessTestEvent.PhaseStarted(at(t), (t / 1000).toInt(), newPhase)
    }

    private fun recordsMinutes(p: FitnessTestPhase) = p == FitnessTestPhase.Stage1 || p == FitnessTestPhase.Stage2 ||
        p == FitnessTestPhase.WarmUp || p == FitnessTestPhase.Incremental || p == FitnessTestPhase.CoolDown

    private fun recordMinutes(t: Long) {
        if (!recordsMinutes(phase) || phaseClosed) return
        while (t - phaseStartT >= (phaseMinutes + 1) * 60_000L) {
            addMinute(phaseStartT + (phaseMinutes + 1) * 60_000L, 60)
        }
    }

    /** Records the last, shorter minute of the phase (if at least a second long). */
    private fun closePartialMinute(t: Long) {
        if (!recordsMinutes(phase) || phaseClosed) return
        recordMinutes(t)
        val rest = t - phaseStartT - phaseMinutes * 60_000L
        if (rest >= 1_000L) addMinute(t, (rest / 1000).toInt())
        phaseClosed = true
    }

    private fun addMinute(endT: Long, durationSeconds: Int) {
        phaseMinutes++
        minutes += MinuteRecord(
            phase = phase,
            minute = phaseMinutes,
            endSecond = (endT / 1000).toInt(),
            durationSeconds = durationSeconds,
            speedKmh = targetSpeed ?: 0.0,
            inclinePercent = targetIncline ?: 0.0,
            heartRateBpm = hr.mean(endT - 10_000, endT)?.let(FitnessFormulas::round1),
        )
    }

    // ------------------------------------------------------------------ result and progress

    protected fun at(t: Long) = startedAtMillis + t

    private fun buildResult(): FitnessTestResult {
        val valid = flags.none { it.severity == FlagSeverity.Invalidating }
        val effortEnd = effortEndT ?: 0L
        val end = endT ?: effortEnd
        return FitnessTestResult(
            id = id,
            testType = testType,
            protocolVersion = protocolVersion,
            startedAtMillis = startedAtMillis,
            effortEndedAtMillis = at(effortEnd),
            endedAtMillis = at(end),
            validity = if (valid) Validity.Valid else Validity.Invalid,
            flags = flags.toList(),
            endReason = endReason ?: FitnessEndReason.CommandFailed,
            endDetail = endDetail,
            inputs = FitnessTestInputs(
                ageYears = subject.ageYears,
                sex = subject.sex,
                weightKg = subject.weightKg,
                hrMaxBpm = subject.hrMaxBpm,
                agePredictedHrMaxBpm = FitnessFormulas.agePredictedHrMax(subject.ageYears),
                heartRateSource = if (hr.hasAnyValid) dominantSource else null,
                requestedStartSpeedKmh = requestedStartSpeedKmh,
                startSpeedKmh = startSpeedKmh,
                maxSpeedLimitKmh = maxSpeedLimitKmh,
                maxInclineLimitPercent = maxInclineLimitPercent,
            ),
            measurements = measurements(),
            minutes = minutes.toList(),
            coolDownSeconds = coolDownStartT?.let { (max(0L, (coolDownEndT ?: end) - it) / 1000).toInt() } ?: 0,
            outputs = if (valid) outputs else null,
            thresholds = if (valid) thresholds else null,
        )
    }

    /** Common part of the progress; subclasses add their own fields in [decorate]. */
    fun progress(t: Long, state: TreadmillState, reading: HeartRateReading): FitnessTestProgress {
        val phaseElapsed = if (phase == FitnessTestPhase.Starting) 0 else ((t - phaseStartT) / 1000).toInt()
        val coolDownLeft = coolDownStartT?.let { max(0L, it + coolDownSeconds * 1000L - t) }
        val base = FitnessTestProgress(
            testType = testType,
            phase = phase,
            instruction = when (phase) {
                FitnessTestPhase.Starting -> FitnessInstruction.BeltStarting
                FitnessTestPhase.CoolDown -> FitnessInstruction.CoolDownWalk
                FitnessTestPhase.Stopping -> FitnessInstruction.Stopping
                FitnessTestPhase.Finished -> FitnessInstruction.Finished
                else -> null
            },
            elapsedSeconds = if (phase == FitnessTestPhase.Starting) 0 else (max(0L, (endT ?: t)) / 1000).toInt(),
            phaseElapsedSeconds = max(0, phaseElapsed),
            phaseRemainingSeconds = if (phase == FitnessTestPhase.CoolDown) coolDownLeft?.let { ceilSeconds(it) } else null,
            targetSpeedKmh = targetSpeed,
            targetInclinePercent = targetIncline,
            actualSpeedKmh = state.speedKmh,
            actualInclinePercent = state.inclinePercent,
            heartRateBpm = reading.bpm,
            heartRateValidity = reading.validity,
            canEndTest = isEffortPhase,
            manualChangesAllowed = manualChangesAllowed,
            endReason = endReason,
            result = result,
        )
        return if (isEffortPhase) decorate(base, t) else decorateAfterEffort(base)
    }

    protected abstract fun decorate(p: FitnessTestProgress, t: Long): FitnessTestProgress

    protected open fun decorateAfterEffort(p: FitnessTestProgress): FitnessTestProgress = p

    protected fun ceilSeconds(ms: Long): Int = ((ms + 999) / 1000).toInt()
}
