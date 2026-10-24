package com.treadz.console.programs.engine

import com.treadz.console.analysis.heartrate.HeartRateBands
import com.treadz.console.analysis.thresholds.Alpha1Evaluation
import com.treadz.console.programs.fitness.HeartRateSourceKind
import com.treadz.console.programs.heartrate.HeartRatePlausibilityFilter
import com.treadz.console.programs.heartrate.HeartRateProfile
import com.treadz.console.programs.heartrate.HeartRateReading
import com.treadz.console.programs.heartrate.HeartRateValidity
import com.treadz.console.programs.heartrate.HeartRateZoneBpm
import com.treadz.console.programs.heartrate.PlausibilityConfig
import com.treadz.console.programs.model.HeartRateAdjust
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * How a heart-rate block is controlled.
 *
 * The mode is derived from the plan: a block inside a repeat that lasts less than
 * [HeartRateControlConfig.continuousMinBlockSeconds] (6 min) is a short work interval (e.g. a 4x4) and is controlled
 * [BetweenRepetitions]; every other heart-rate block is [Continuous].
 */
enum class HeartRateControlMode {
    /** The adjusted quantity follows the heart rate during the block (decision every 20 s). */
    Continuous,

    /**
     * Within a repetition only safety reductions happen; the next repetition of the same block starts corrected by the
     * heart rate at the end of the previous one.
     */
    BetweenRepetitions,
}

/** What the heart-rate control of the current block is doing, for the UI. */
enum class HeartRateControlState {
    /** Controlling: the heart rate is in the zone, or the last decision corrected it. */
    Active,

    /** Outside the zone but waiting: the first 90 s of the block (or after a safety reduction) allow no increase, or
     *  the 30-s trend forecast says the heart rate is already entering the zone. */
    Waiting,

    /** No valid heart rate: everything is held (after 60 s the block's start value is restored if it is lower). The
     *  control resumes after 10 s of valid readings. */
    HoldingNoHeartRate,

    /** The user changed speed or incline: no automatic changes until the next block (or [ProgramRunner.resumeHeartRateControl]). */
    OffByManualChange,

    /** The heart rate is above the ceiling (zone top + 8 bpm) or reached the maximum: safety reductions. */
    Ceiling,

    /** The block asks for heart-rate control but the run has no heart-rate profile: it runs at its start values. */
    Unavailable,
}

/** Why the control changed the adjusted quantity. */
enum class HeartRateAdjustReason {
    /** Continuous mode: the 10-s mean was above the zone. */
    AboveZone,

    /** Continuous mode: the 10-s mean was below the zone. */
    BelowZone,

    /** More than 10 s above the ceiling (zone top + 8 bpm): sharp reduction (−1 km/h or −3 %). */
    CeilingExceeded,

    /** The heart rate reached the effective maximum: the block's minimum (recovery value). */
    MaxHeartRateReached,

    /** 60 s without a valid heart rate: back to the block's start value, which was lower. */
    NoHeartRateFallback,

    /** Between repetitions: the repetition starts corrected by the heart rate at the end of the previous one. */
    NextRepetition,

    /**
     * The real-time α1 brake: the median of the last six α1 evaluations (all counting, all after the previous reduction)
     * was below the trigger while the heart rate was in or above the zone, so the load went one small step down (see
     * [Alpha1BrakeConfig]). It only ever lowers the load.
     */
    Alpha1Brake,
}

/** Rules of the heart-rate control (decided 2026-10-14). Times are milliseconds of the injected clock. */
data class HeartRateControlConfig(
    /** The heart rate used for decisions is the mean of the valid readings of this window. */
    val meanWindowMs: Long = 10_000,
    /** One decision every this long (continuous mode). */
    val decisionIntervalMs: Long = 20_000,
    /** No upward corrections during this first part of a block (block time), nor for this long after a safety reduction. */
    val noUpwardMs: Long = 90_000,
    /** Trend: least-squares slope of the valid readings of this window, projected [forecastMs] ahead. */
    val trendWindowMs: Long = 30_000,
    val forecastMs: Long = 30_000,
    /** The trend needs readings spanning at least this long. */
    val minTrendSpanMs: Long = 15_000,
    /** Step proportional to the error (bpm outside the zone); downward gains are larger: faster down than up. */
    val speedGainUpKmhPerBpm: Double = 0.03,
    val speedGainDownKmhPerBpm: Double = 0.05,
    val speedStepMinKmh: Double = 0.1,
    val speedStepMaxKmh: Double = 0.5,
    val inclineGainUpPercentPerBpm: Double = 0.15,
    val inclineGainDownPercentPerBpm: Double = 0.25,
    val inclineStepMinPercent: Double = 0.5,
    val inclineStepMaxPercent: Double = 2.0,
    /** Ceiling = zone top + this. Above it for [ceilingHoldMs] → sharp reduction. */
    val ceilingMarginBpm: Int = 8,
    val ceilingHoldMs: Long = 10_000,
    val ceilingSpeedDropKmh: Double = 1.0,
    val ceilingInclineDropPercent: Double = 3.0,
    /** Two safety reductions are at least this far apart (the heart rate needs time to respond). */
    val minSafetyIntervalMs: Long = 20_000,
    /** The maximum counts as reached when the readings stay at or above it this long (a single packet is not enough). */
    val maxReachedHoldMs: Long = 2_000,
    /** No valid reading for this long = no heart rate. */
    val missingAfterMs: Long = 3_000,
    /** Without heart rate: hold this long, then restore the start value if it is lower. */
    val noHeartRateHoldMs: Long = 60_000,
    /** Control resumes after this long of continuous valid readings. */
    val validResumeMs: Long = 10_000,
    /** Short blocks inside a repeat are controlled between repetitions. */
    val continuousMinBlockSeconds: Int = 360,
    /** The settled value of a continuous block needs at least this much time in the zone (after the first 90 s). */
    val settledMinInZoneMs: Long = 60_000,
    val plausibility: PlausibilityConfig = PlausibilityConfig(),
    /** The real-time DFA-α1 brake. */
    val alpha1Brake: Alpha1BrakeConfig = Alpha1BrakeConfig(),
)

/**
 * The real-time DFA-α1 brake of heart-rate-guided blocks (phase F3 of the thresholds plan). It is one more input of
 * the block control and only ever lowers the load; the order goes through the same path as every other heart-rate
 * adjustment. Times are milliseconds of the injected clock.
 *
 * **Where it is active**: continuous heart-rate blocks that are easy enough and only with RR from a strap connected to
 * the app (never with the console's heart rate, which carries no RR). Easy enough means the zone ceiling is at or below
 * VT1 ([HeartRateProfile.vt1Bpm]: measured, else the typical one of the user's level, i.e. the top of zone 2 of the
 * zone model). [vt1ToleranceBpm] covers zones stored as whole percentages of the reserve and converted back with a
 * resting rate that has changed a little since (a 5-bpm change moves the top of zone 2 by about 2 bpm); zone 3 ends at
 * VT2, far above. Without any VT1, at or below [zone2CeilingReservePercent] % of the heart-rate reserve (the top of
 * zone 2 of the highest fitness level, 65 %; without a resting heart rate the equivalent % of the maximum). The fitness tests run on their own runner and never
 * get the brake.
 *
 * **Trigger**: the **median of the last [medianEvaluations] (6) evaluations** is below [triggerAlpha1] (the estimator
 * evaluates every 10 s over the last 120 s). Those evaluations must be consecutive and **all counting**: each one a
 * window of at most [maxArtifactFraction] artifacts, entirely inside the running block, with the 10-s heart rate at or
 * above the zone's lower limit, a strap connected to the app and the control on. They must also all have been made
 * **after the last reduction**: a reduction empties the list, so the next trigger looks at evaluations that already see
 * the effect of the load that was lowered, and never comes before [minReductionIntervalMs] (180 s: 120 s for the
 * window to hold only beats after the reduction plus about 60 s for the heart rate to respond). Then one reduction:
 * [inclineStepPercent] of incline when the block adjusts the incline (walking) or [speedStepKmh] when it adjusts the
 * speed (running), never below the block's minimum. Any evaluation that does not count (a window that is not valid or
 * has too many artifacts, no RR, heart rate under the zone, control off) empties the list, so the brake is off while RR
 * are missing or noisy and the heart-rate regulation goes on alone. A median rather than a run of low values: one
 * high evaluation among six does not cancel the signal, and one low episode that is already fading does not trigger a
 * second reduction.
 *
 * Sources of the thresholds: Rogers B, Giles D, Draper N, Hoos O, Gronwald T. A new detection method defining the
 * aerobic threshold for endurance exercise and training prescription based on fractal correlation properties of heart
 * rate variability. Front Physiol. 2021;12:596567 (α1 = 0.75 at the aerobic threshold, 0.5 at the anaerobic one); the
 * 0.70 trigger, the count of 6, the 180 s and the steps are design assumptions (a margin under the population cut of
 * 0.75; the waiting time follows from the 120-s window and the response of the heart rate).
 */
data class Alpha1BrakeConfig(
    val enabled: Boolean = true,
    val triggerAlpha1: Double = 0.70,
    /** The brake looks at the median of this many consecutive counting evaluations (all made after the last reduction). */
    val medianEvaluations: Int = 6,
    val maxArtifactFraction: Double = 0.03,
    /** After a reduction, the next one cannot come earlier than this (the window must see the effect of the first). */
    val minReductionIntervalMs: Long = 180_000,
    val inclineStepPercent: Double = 1.0,
    val speedStepKmh: Double = 0.3,
    val vt1ToleranceBpm: Int = 3,
    val zone2CeilingReservePercent: Int = 65,
)

/** What the UI shows about the heart-rate control of the current block. */
data class HeartRateControlStatus(
    val state: HeartRateControlState,
    val mode: HeartRateControlMode,
    val adjust: HeartRateAdjust,
    /** The zone in bpm (`null` when [state] is [HeartRateControlState.Unavailable]). */
    val zoneLowBpm: Int?,
    val zoneHighBpm: Int?,
    /** Zone top + 8 bpm. */
    val ceilingBpm: Int?,
    /** The effective maximum heart rate used. */
    val maxBpm: Int?,
    /** Bounds of the adjusted quantity (km/h or %), already within the treadmill and the safety limits. */
    val minimum: Double,
    val maximum: Double,
    /** The value the control wants now for the adjusted quantity. */
    val targetValue: Double?,
    /** Mean of the valid readings of the last 10 s. */
    val heartRateMeanBpm: Double?,
    val source: HeartRateSourceKind?,
    /** Seconds to the next regular decision (continuous mode, while controlling). */
    val secondsToNextDecision: Int?,
)

/**
 * What happened with the heart-rate control of one block, reported when the block ends (and in the run's final
 * event). The generators of personalised programs use [settledValue] to start next time where the heart rate stayed in
 * the zone.
 */
data class HeartRateBlockSummary(
    val blockIndex: Int,
    val adjust: HeartRateAdjust,
    val mode: HeartRateControlMode,
    val zoneLowBpm: Int,
    val zoneHighBpm: Int,
    /** The plan's start value of the adjusted quantity. */
    val startValue: Double,
    /** What the block actually started at (a repetition may start corrected). */
    val initialValue: Double,
    val endValue: Double,
    /**
     * The value of the adjusted quantity at which the heart rate settled in the zone. Continuous: the time-weighted mean
     * of the target while the 10-s heart rate was in the zone (after the first 90 s), when that lasted at least 60 s.
     * Between repetitions: the repetition's value when its final 10-s heart rate was in the zone. `null` otherwise.
     */
    val settledValue: Double?,
    val secondsInZone: Int,
    val meanHeartRateBpm: Double?,
    /** Mean of the last 10 s of the block. */
    val endHeartRateBpm: Double?,
    /** Changes made by the control (all reasons). */
    val adjustments: Int,
    val safetyReductions: Int,
    /** The user changed speed or incline during the block. */
    val manualOverride: Boolean,
    val secondsWithoutHeartRate: Int,
    /** The source that provided most of the block's valid readings. */
    val source: HeartRateSourceKind?,
    /**
     * Reductions made by the real-time α1 brake ([HeartRateAdjustReason.Alpha1Brake]; they are also counted in
     * [adjustments]). `null` when the brake could not act in this block (zone above VT1 / zone 2, between repetitions,
     * no valid α1 window from a strap), `0` when it was active, had valid windows and never fired.
     */
    val alpha1Reductions: Int? = null,
    /**
     * Mean DFA-α1 of the valid 120-s windows evaluated during the block (only windows entirely inside the running
     * block); `null` without any. Recorded for every heart-rate block with strap RR, whether the brake was active or not.
     */
    val alpha1Mean: Double? = null,
    /** Number of valid windows behind [alpha1Mean] (evaluations every 10 s, so they overlap); `null` without any. */
    val alpha1Windows: Int? = null,
)

/** Something the control did, for the runner to turn into program events. */
internal sealed interface HeartRateSignal {
    data class Adjusted(val from: Double, val to: Double, val reason: HeartRateAdjustReason, val heartRateBpm: Double?) : HeartRateSignal
    data class CeilingExceeded(val heartRateBpm: Int, val ceilingBpm: Int, val reduced: Boolean) : HeartRateSignal
    data class MaxReached(val heartRateBpm: Int, val maxBpm: Int, val reduced: Boolean) : HeartRateSignal
    data class StateChanged(val state: HeartRateControlState) : HeartRateSignal

    /** The control is active again: the user switched it back on after a manual change, or the heart rate came back. */
    data object ControlResumed : HeartRateSignal
}

/**
 * The run's heart-rate history (independent of blocks): plausibility-filtered readings with their time. Not
 * thread-safe: the runner guards it.
 */
internal class HeartRateTracker(profile: HeartRateProfile, private val config: HeartRateControlConfig) {
    private val filter = HeartRatePlausibilityFilter(profile.maxBpm, config.plausibility)
    private val samples = ArrayDeque<Pair<Long, Int>>()
    private var validStreakStart: Long? = null

    var lastValidAt: Long? = null
        private set
    var lastSource: HeartRateSourceKind? = null
        private set

    /** Feeds a reading; returns the plausible bpm or `null`. */
    fun onReading(t: Long, reading: HeartRateReading): Int? {
        if (reading.source != lastSource && reading.source != null && lastSource != null) filter.reset()
        if (reading.source != null) lastSource = reading.source
        val raw = if (reading.validity == HeartRateValidity.Invalid) null else reading.bpm
        val bpm = filter.accept(t, raw)
        if (bpm == null) {
            validStreakStart = null
            return null
        }
        if (validStreakStart == null || (lastValidAt?.let { t - it > config.missingAfterMs } == true)) validStreakStart = t
        lastValidAt = t
        samples.addLast(t to bpm)
        while (samples.isNotEmpty() && samples.first().first < t - KEEP_MS) samples.removeFirst()
        return bpm
    }

    fun hasValid(t: Long): Boolean = lastValidAt?.let { t - it <= config.missingAfterMs } == true

    /** How long the readings have been continuously valid at [t] (0 without a valid heart rate). */
    fun validStreakMs(t: Long): Long = if (!hasValid(t)) 0 else validStreakStart?.let { t - it } ?: 0

    fun mean(t: Long, windowMs: Long): Double? {
        var sum = 0.0
        var n = 0
        for ((st, v) in samples) if (st > t - windowMs && st <= t) {
            sum += v
            n++
        }
        return if (n == 0) null else sum / n
    }

    /** Least-squares slope in bpm per ms of the readings in (t − window, t]; `null` when they span less than [minSpanMs]. */
    fun slope(t: Long, windowMs: Long, minSpanMs: Long): Double? {
        val pts = samples.filter { it.first > t - windowMs && it.first <= t }
        if (pts.size < 3 || pts.last().first - pts.first().first < minSpanMs) return null
        val mx = pts.sumOf { it.first.toDouble() } / pts.size
        val my = pts.sumOf { it.second.toDouble() } / pts.size
        var num = 0.0
        var den = 0.0
        for ((x, y) in pts) {
            num += (x - mx) * (y - my)
            den += (x - mx) * (x - mx)
        }
        return if (den == 0.0) 0.0 else num / den
    }

    private companion object {
        const val KEEP_MS = 60_000L
    }
}

/** Which blocks get the α1 brake (see [Alpha1BrakeConfig]). */
internal object Alpha1BrakePolicy {

    fun appliesTo(zone: HeartRateZoneBpm, mode: HeartRateControlMode, profile: HeartRateProfile, config: Alpha1BrakeConfig): Boolean {
        if (!config.enabled || mode != HeartRateControlMode.Continuous) return false
        val ceiling = profile.usableVt1Bpm?.let { it + config.vt1ToleranceBpm }
            ?: HeartRateBands.reserveBpm(profile.maxBpm, profile.usableRestingBpm, config.zone2CeilingReservePercent)
        return zone.highBpm <= ceiling
    }
}

/**
 * Heart-rate control of one block (pure logic on the injected clock). The runner feeds it the filtered readings and
 * asks it at every tick what the adjusted quantity should be; it never talks to the treadmill.
 *
 * **Continuous mode**: a decision every 20 s on the 10-s mean. Inside the zone nothing changes. Outside, when the 30-s
 * trend forecast already enters the zone it waits; otherwise the step is proportional to the error (speed 0.1–0.5 km/h,
 * incline 0.5–2 %, rounded to the treadmill step), with larger gains downwards. No upward correction during the first
 * 90 s of the block nor in the 90 s after a safety reduction.
 *
 * **Between repetitions**: no regular decisions inside the repetition; [nextRepetitionValue] corrects the next one.
 *
 * **Safety, both modes** (while the control is on): more than 10 s above the ceiling (zone top + 8) → −1 km/h or −3 %
 * and an alert; reaching the maximum heart rate → the block's minimum (recovery value). Two safety reductions are at
 * least 20 s apart. The belt is never stopped.
 *
 * **No valid heart rate** (no plausible reading for 3 s): everything is held; after 60 s the block's start value is
 * restored if it is lower than the current one; the control resumes after 10 s of valid readings.
 *
 * **Manual change**: [manualOverride] switches it off until the block ends or [reEnable] is called.
 *
 * **α1 brake** (when [alpha1BrakeActive], see [Alpha1BrakeConfig]): the runner passes every real-time α1 evaluation to
 * [onAlpha1]. It is one more input of this control, evaluated in [decide] after the safety rules and the no-heart-rate
 * hold and before the regular decision, so it combines with the other decisions like this:
 *  - Safety wins: a ceiling or maximum-heart-rate reduction returns first, and the brake waits at least a decision
 *    interval (20 s) after *any* change made by this control (a regular correction counts too), so two reductions never
 *    pile up on the same stale heart rate.
 *  - An α1 reduction counts as a decision: the next regular decision waits a full interval, and, like a safety
 *    reduction, it restarts the 90-s window without upward corrections, so the load is not raised back at once while
 *    the heart rate settles.
 *  - It never raises the load, never goes below the block's minimum, and is off while the control is off after a
 *    manual change (until the next block or [reEnable]), while the heart rate is missing and while the last evaluations
 *    were not valid.
 *  - Only windows entirely inside the running block count (not the warm-up before it, nor the time before a pause,
 *    a resume or a re-enable): the heart-rate regulation alone covers the first 2 min.
 */
internal class HeartRateBlockControl(
    val blockIndex: Int,
    val adjust: HeartRateAdjust,
    val mode: HeartRateControlMode,
    val zone: HeartRateZoneBpm,
    val maxBpm: Int,
    val minimum: Double,
    val maximum: Double,
    /** Treadmill step of the adjusted quantity (0.1 km/h, 1 %). */
    private val grid: Double,
    val startValue: Double,
    val initialValue: Double,
    private val tracker: HeartRateTracker,
    private val config: HeartRateControlConfig,
    startedAt: Long,
    /** The α1 brake applies to this block ([Alpha1BrakePolicy]). The evaluations in [onAlpha1] are recorded either way. */
    private val alpha1BrakeActive: Boolean = false,
) {
    val ceilingBpm: Int = zone.highBpm + config.ceilingMarginBpm

    val pending = mutableListOf<HeartRateSignal>()

    private var off = false
    private var holding = false
    private var holdSince = 0L
    private var fallbackDone = false
    private var waiting = false
    private var nextDecisionAt = startedAt + config.decisionIntervalMs
    private var noUpwardUntilBlockMs = config.noUpwardMs
    private var aboveCeilingSince: Long? = null
    private var atMaxSince: Long? = null
    /** An alert without a reduction (already at the minimum) is given once per episode above the ceiling / at the maximum. */
    private var ceilingAlerted = false
    private var maxAlerted = false
    private var lastSafetyAt: Long? = null
    private var lastState: HeartRateControlState? = null

    // -- α1 brake
    /** Windows must start at or after this time (block start, last resume or re-enable). */
    private var alpha1FloorMs = startedAt
    /** The α1 of the last counting evaluations, oldest first; emptied by anything that does not count and by a reduction. */
    private val alpha1Recent = ArrayDeque<Double>()
    private var lastAlpha1ReductionAt: Long? = null
    private var lastChangeAt: Long? = null
    private var alpha1Reductions = 0
    private var alpha1Sum = 0.0
    private var alpha1Count = 0

    /** The value the control last asked for (kept through a manual change). */
    var controlValue: Double = initialValue
        private set

    // -- summary accumulators
    private var lastAccountT = startedAt
    private var inZoneMs = 0L
    private var noHrMs = 0L
    private var hrSum = 0.0
    private var hrCount = 0
    private var adjustments = 0
    private var safetyReductions = 0
    private var manual = false
    private val sourceCounts = mutableMapOf<HeartRateSourceKind, Int>()

    /**
     * A run's first block starts before any reading has arrived: it is not "without heart rate" until the readings had
     * time to come ([HeartRateControlConfig.missingAfterMs]).
     */
    private val noHistoryGraceUntil = if (tracker.lastValidAt == null) startedAt + config.missingAfterMs else Long.MIN_VALUE

    init {
        if (tracker.lastValidAt != null && !tracker.hasValid(startedAt)) {
            holding = true
            holdSince = tracker.lastValidAt ?: startedAt
        }
    }

    /** A filtered reading ([bpm] `null` = not plausible or none). */
    fun onSample(t: Long, bpm: Int?) {
        if (bpm == null) return
        hrSum += bpm
        hrCount++
        tracker.lastSource?.let { sourceCounts[it] = (sourceCounts[it] ?: 0) + 1 }
        if (bpm > ceilingBpm) {
            if (aboveCeilingSince == null) aboveCeilingSince = t
        } else {
            aboveCeilingSince = null
            ceilingAlerted = false
        }
        if (bpm >= maxBpm) {
            if (atMaxSince == null) atMaxSince = t
        } else {
            atMaxSince = null
            maxAlerted = false
        }
    }

    /** The user took over. */
    fun manualOverride() {
        if (off) return
        off = true
        manual = true
        waiting = false
        alpha1Recent.clear()
        signalState()
    }

    /** The user gives control back; it continues from [current]. */
    fun reEnable(t: Long, current: Double) {
        if (!off) return
        off = false
        controlValue = current
        nextDecisionAt = t
        aboveCeilingSince = null
        atMaxSince = null
        alpha1FloorMs = t
        alpha1Recent.clear()
        if (!tracker.hasValid(t)) {
            holding = true
            holdSince = tracker.lastValidAt ?: t
            fallbackDone = false
        } else {
            // Without a heart rate it is not really on yet: the end of the holding reports it.
            pending += HeartRateSignal.ControlResumed
        }
        signalState()
    }

    val isOff: Boolean get() = off

    /** After a pause or an interruption: timers start again. */
    fun onResume(t: Long) {
        nextDecisionAt = max(nextDecisionAt, t + config.decisionIntervalMs)
        aboveCeilingSince = null
        atMaxSince = null
        lastAccountT = t
        alpha1FloorMs = t
        alpha1Recent.clear()
        if (holding) holdSince = t
    }

    /**
     * A real-time α1 evaluation ([e]) at [t]. It is recorded for the block summary when it is valid and its window lies
     * inside the running block; it counts towards the brake only if the brake is active, the control is on and has a
     * heart rate from a strap connected to the app, the window has few artifacts and the 10-s heart rate is at or above
     * the zone's lower limit. Anything else empties the list of counting evaluations.
     */
    fun onAlpha1(t: Long, e: Alpha1Evaluation) {
        val alpha = e.alpha1
        if (e.windowStartMillis < alpha1FloorMs || !e.valid || alpha == null) {
            alpha1Recent.clear()
            return
        }
        alpha1Sum += alpha
        alpha1Count++
        val brake = config.alpha1Brake
        val hr = tracker.mean(t, config.meanWindowMs)
        val counts = alpha1BrakeActive && !off && !holding && tracker.hasValid(t) &&
            tracker.lastSource == HeartRateSourceKind.DirectStrap &&
            e.artifactFraction <= brake.maxArtifactFraction + EPS &&
            hr != null && hr >= zone.lowBpm
        if (!counts) {
            alpha1Recent.clear()
            return
        }
        alpha1Recent.addLast(alpha)
        while (alpha1Recent.size > brake.medianEvaluations) alpha1Recent.removeFirst()
    }

    /**
     * Advances the control to [t] ([blockMs] = block time). Returns the new value of the adjusted quantity when it
     * changes, `null` otherwise. [current] is what the engine currently targets.
     */
    fun update(t: Long, blockMs: Long, current: Double): Double? {
        account(t, blockMs, current)
        val result = if (off) {
            alertWhileOff(t)
            null
        } else {
            decide(t, blockMs, current)
        }
        signalState()
        return result
    }

    private fun decide(t: Long, blockMs: Long, current: Double): Double? {
        // ---- safety first
        val mayReduce = lastSafetyAt?.let { t - it >= config.minSafetyIntervalMs } ?: true
        val atMax = atMaxSince
        if (atMax != null && t - atMax >= config.maxReachedHoldMs && tracker.hasValid(t) && mayReduce) {
            val reduced = current > minimum + EPS
            if (reduced || !maxAlerted) {
                pending += HeartRateSignal.MaxReached(tracker.mean(t, config.maxReachedHoldMs)?.roundToInt() ?: maxBpm, maxBpm, reduced)
                maxAlerted = true
            }
            lastSafetyAt = t
            noUpwardUntilBlockMs = blockMs + config.noUpwardMs
            if (reduced) return change(current, minimum, HeartRateAdjustReason.MaxHeartRateReached, t, safety = true)
        }
        val above = aboveCeilingSince
        if (above != null && t - above >= config.ceilingHoldMs && tracker.hasValid(t) && mayReduce && lastSafetyAt != t) {
            val drop = if (adjust == HeartRateAdjust.Speed) config.ceilingSpeedDropKmh else config.ceilingInclineDropPercent
            val target = snap(max(minimum, current - drop))
            val reduced = target < current - EPS
            if (reduced || !ceilingAlerted) {
                pending += HeartRateSignal.CeilingExceeded(tracker.mean(t, config.meanWindowMs)?.roundToInt() ?: ceilingBpm, ceilingBpm, reduced)
                ceilingAlerted = true
            }
            aboveCeilingSince = t
            lastSafetyAt = t
            noUpwardUntilBlockMs = blockMs + config.noUpwardMs
            if (reduced) return change(current, target, HeartRateAdjustReason.CeilingExceeded, t, safety = true)
        }

        // ---- no heart rate
        if (!tracker.hasValid(t) && !holding && t >= noHistoryGraceUntil) {
            holding = true
            holdSince = tracker.lastValidAt ?: t
            fallbackDone = false
        }
        if (!tracker.hasValid(t) && !holding) return null
        if (holding) {
            if (tracker.validStreakMs(t) >= config.validResumeMs) {
                holding = false
                fallbackDone = false
                pending += HeartRateSignal.ControlResumed
                // Decide now if a decision was due meanwhile (never earlier than the block's own schedule).
                nextDecisionAt = max(nextDecisionAt, t)
            } else {
                if (!fallbackDone && t - holdSince >= config.noHeartRateHoldMs) {
                    fallbackDone = true
                    if (startValue < current - EPS) return change(current, startValue, HeartRateAdjustReason.NoHeartRateFallback, t, safety = false)
                }
                return null
            }
        }

        // ---- α1 brake: one more input, below the safety rules and above the regular decision
        alpha1Reduction(t, blockMs, current)?.let { return it }

        // ---- regular decision
        if (mode == HeartRateControlMode.BetweenRepetitions) return null
        if (t < nextDecisionAt) return null
        nextDecisionAt = t + config.decisionIntervalMs
        val mean = tracker.mean(t, config.meanWindowMs) ?: return null
        if (mean >= zone.lowBpm && mean <= zone.highBpm) {
            waiting = false
            return null
        }
        val isAbove = mean > zone.highBpm
        val slope = tracker.slope(t, config.trendWindowMs, config.minTrendSpanMs)
        if (slope != null) {
            val forecast = mean + slope * config.forecastMs
            if ((isAbove && forecast <= zone.highBpm) || (!isAbove && forecast >= zone.lowBpm)) {
                waiting = true
                return null
            }
        }
        if (!isAbove && blockMs < noUpwardUntilBlockMs) {
            waiting = true
            return null
        }
        waiting = false
        val error = if (isAbove) mean - zone.highBpm else zone.lowBpm - mean
        val step = step(error, down = isAbove)
        val target = if (isAbove) max(minimum, current - step) else min(maximum, current + step)
        if (abs(target - current) < EPS) return null
        return change(current, snap(target), if (isAbove) HeartRateAdjustReason.AboveZone else HeartRateAdjustReason.BelowZone, t, safety = false)
    }

    private fun alpha1Reduction(t: Long, blockMs: Long, current: Double): Double? {
        val brake = config.alpha1Brake
        if (!alpha1BrakeActive || alpha1Recent.size < brake.medianEvaluations) return null
        if (median(alpha1Recent) >= brake.triggerAlpha1) return null
        if (lastAlpha1ReductionAt?.let { t - it < brake.minReductionIntervalMs } == true) return null
        if (lastChangeAt?.let { t - it < config.decisionIntervalMs } == true) return null
        val step = if (adjust == HeartRateAdjust.Incline) brake.inclineStepPercent else brake.speedStepKmh
        val target = snap(max(minimum, current - step))
        if (target >= current - EPS) return null
        // The next trigger needs evaluations made after this reduction.
        alpha1Recent.clear()
        lastAlpha1ReductionAt = t
        alpha1Reductions++
        // It counts as a decision: the regular one waits a full interval and no upward correction follows at once.
        nextDecisionAt = max(nextDecisionAt, t + config.decisionIntervalMs)
        noUpwardUntilBlockMs = max(noUpwardUntilBlockMs, blockMs + config.noUpwardMs)
        return change(current, target, HeartRateAdjustReason.Alpha1Brake, t, safety = false)
    }

    private fun median(values: Collection<Double>): Double {
        val sorted = values.sorted()
        val n = sorted.size
        return if (n % 2 == 1) sorted[n / 2] else (sorted[n / 2 - 1] + sorted[n / 2]) / 2.0
    }

    /** With the control off (manual change) nothing is reduced, but the user is still told once per episode. */
    private fun alertWhileOff(t: Long) {
        if (!tracker.hasValid(t)) return
        val atMax = atMaxSince
        if (atMax != null && t - atMax >= config.maxReachedHoldMs && !maxAlerted) {
            pending += HeartRateSignal.MaxReached(tracker.mean(t, config.maxReachedHoldMs)?.roundToInt() ?: maxBpm, maxBpm, reduced = false)
            maxAlerted = true
        }
        val above = aboveCeilingSince
        if (above != null && t - above >= config.ceilingHoldMs && !ceilingAlerted) {
            pending += HeartRateSignal.CeilingExceeded(tracker.mean(t, config.meanWindowMs)?.roundToInt() ?: ceilingBpm, ceilingBpm, reduced = false)
            ceilingAlerted = true
        }
    }

    private fun change(from: Double, to: Double, reason: HeartRateAdjustReason, t: Long, safety: Boolean): Double {
        adjustments++
        lastChangeAt = t
        if (safety) {
            safetyReductions++
            // The heart rate needs time to respond: the next regular decision waits a full interval.
            nextDecisionAt = t + config.decisionIntervalMs
        }
        controlValue = to
        pending += HeartRateSignal.Adjusted(from, to, reason, tracker.mean(t, config.meanWindowMs))
        return to
    }

    /** Step for an error of [errorBpm], proportional, bounded and on the treadmill grid (at least one step). */
    fun step(errorBpm: Double, down: Boolean): Double {
        val raw = when (adjust) {
            HeartRateAdjust.Speed -> (errorBpm * if (down) config.speedGainDownKmhPerBpm else config.speedGainUpKmhPerBpm)
                .coerceIn(config.speedStepMinKmh, config.speedStepMaxKmh)
            HeartRateAdjust.Incline -> (errorBpm * if (down) config.inclineGainDownPercentPerBpm else config.inclineGainUpPercentPerBpm)
                .coerceIn(config.inclineStepMinPercent, config.inclineStepMaxPercent)
        }
        if (grid <= 0.0) return raw
        return max(grid, (raw / grid).roundToInt() * grid).let { (it * 100).roundToInt() / 100.0 }
    }

    private fun snap(v: Double): Double {
        val s = if (grid <= 0.0) v else (v / grid).roundToInt() * grid
        return ((s * 100).roundToInt() / 100.0).coerceIn(minimum, maximum)
    }

    /**
     * Between repetitions (and for a long block repeated): the value the next repetition of this block starts at.
     * Continuous blocks carry their final value. Short repetitions are corrected by the 10-s heart rate at the end of
     * this one: inside the zone the value is kept, outside it moves by a proportional step. Without a heart rate at
     * the end the lower of the current and the start value is used. After a manual change the control's own value is
     * carried, without correction.
     */
    fun nextRepetitionValue(t: Long, current: Double): Double {
        if (off) return controlValue
        if (mode == HeartRateControlMode.Continuous) return snap(current)
        val end = tracker.mean(t, config.meanWindowMs)
        if (end == null || !tracker.hasValid(t)) return snap(min(current, startValue))
        return when {
            end > zone.highBpm -> snap(max(minimum, current - step(end - zone.highBpm, down = true)))
            end < zone.lowBpm -> snap(min(maximum, current + step(zone.lowBpm - end, down = false)))
            else -> snap(current)
        }
    }

    fun state(t: Long): HeartRateControlState = when {
        off -> HeartRateControlState.OffByManualChange
        aboveCeilingSince != null || (lastSafetyAt?.let { t - it < config.minSafetyIntervalMs } == true) -> HeartRateControlState.Ceiling
        holding -> HeartRateControlState.HoldingNoHeartRate
        waiting -> HeartRateControlState.Waiting
        else -> HeartRateControlState.Active
    }

    private fun signalState() {
        val s = state(lastAccountT)
        if (s != lastState) {
            lastState = s
            pending += HeartRateSignal.StateChanged(s)
        }
    }

    private fun account(t: Long, blockMs: Long, current: Double) {
        val dt = max(0L, t - lastAccountT)
        lastAccountT = t
        if (!tracker.hasValid(t)) {
            noHrMs += dt
            return
        }
        val mean = tracker.mean(t, config.meanWindowMs) ?: return
        if (off || holding || mean < zone.lowBpm || mean > zone.highBpm) return
        inZoneMs += dt
        // The settled value ignores the first 90 s of the block (the heart rate is still coming to the zone).
        if (blockMs >= config.noUpwardMs) {
            settledMs += dt
            settledValueMs += current * dt
        }
    }

    private var settledMs = 0L
    private var settledValueMs = 0.0

    fun status(t: Long, target: Double?): HeartRateControlStatus = HeartRateControlStatus(
        state = state(t),
        mode = mode,
        adjust = adjust,
        zoneLowBpm = zone.lowBpm,
        zoneHighBpm = zone.highBpm,
        ceilingBpm = ceilingBpm,
        maxBpm = maxBpm,
        minimum = minimum,
        maximum = maximum,
        targetValue = target,
        heartRateMeanBpm = tracker.mean(t, config.meanWindowMs)?.let { (it * 10).roundToInt() / 10.0 },
        source = tracker.lastSource,
        secondsToNextDecision = if (mode == HeartRateControlMode.Continuous && !off && !holding) {
            max(0L, (nextDecisionAt - t + 999) / 1000).toInt()
        } else {
            null
        },
    )

    fun summary(t: Long, endValue: Double): HeartRateBlockSummary {
        val end = tracker.mean(t, config.meanWindowMs)?.takeIf { tracker.hasValid(t) }
        val settled = when (mode) {
            HeartRateControlMode.Continuous ->
                if (settledMs >= config.settledMinInZoneMs) round1(settledValueMs / settledMs) else null
            HeartRateControlMode.BetweenRepetitions ->
                if (!off && end != null && end >= zone.lowBpm && end <= zone.highBpm) endValue else null
        }
        return HeartRateBlockSummary(
            blockIndex = blockIndex,
            adjust = adjust,
            mode = mode,
            zoneLowBpm = zone.lowBpm,
            zoneHighBpm = zone.highBpm,
            startValue = startValue,
            initialValue = initialValue,
            endValue = endValue,
            settledValue = settled,
            secondsInZone = (inZoneMs / 1000).toInt(),
            meanHeartRateBpm = if (hrCount == 0) null else round1(hrSum / hrCount),
            endHeartRateBpm = end?.let(::round1),
            adjustments = adjustments,
            safetyReductions = safetyReductions,
            manualOverride = manual,
            secondsWithoutHeartRate = (noHrMs / 1000).toInt(),
            source = sourceCounts.maxByOrNull { it.value }?.key,
            alpha1Reductions = if (alpha1BrakeActive && alpha1Count > 0) alpha1Reductions else null,
            alpha1Mean = if (alpha1Count > 0) (alpha1Sum / alpha1Count * 100).roundToInt() / 100.0 else null,
            alpha1Windows = alpha1Count.takeIf { it > 0 },
        )
    }

    private fun round1(v: Double) = (v * 10).roundToInt() / 10.0

    companion object {
        private const val EPS = 1e-6

        /** The mode of a block: short blocks inside a repeat are controlled between repetitions. */
        fun modeOf(inRepeat: Boolean, durationSeconds: Int, config: HeartRateControlConfig): HeartRateControlMode =
            if (inRepeat && durationSeconds < config.continuousMinBlockSeconds) HeartRateControlMode.BetweenRepetitions
            else HeartRateControlMode.Continuous
    }
}
