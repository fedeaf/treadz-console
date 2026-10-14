package com.treadz.console.programs.engine

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
)

/** Something the control did, for the runner to turn into program events. */
internal sealed interface HeartRateSignal {
    data class Adjusted(val from: Double, val to: Double, val reason: HeartRateAdjustReason, val heartRateBpm: Double?) : HeartRateSignal
    data class CeilingExceeded(val heartRateBpm: Int, val ceilingBpm: Int, val reduced: Boolean) : HeartRateSignal
    data class MaxReached(val heartRateBpm: Int, val maxBpm: Int, val reduced: Boolean) : HeartRateSignal
    data class StateChanged(val state: HeartRateControlState) : HeartRateSignal
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
        if (!tracker.hasValid(t)) {
            holding = true
            holdSince = tracker.lastValidAt ?: t
            fallbackDone = false
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
        if (holding) holdSince = t
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
