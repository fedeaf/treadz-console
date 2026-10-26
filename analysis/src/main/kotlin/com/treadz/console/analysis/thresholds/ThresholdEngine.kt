package com.treadz.console.analysis.thresholds

import com.treadz.console.analysis.fitness.Acsm
import com.treadz.console.analysis.rr.Dfa
import com.treadz.console.analysis.rr.RrCleaning
import com.treadz.console.analysis.samples.RrInterval
import com.treadz.console.analysis.samples.WorkoutSample
import kotlin.math.abs

/** How much a threshold detection can be trusted. Low-quality results are kept but do not feed the profile. */
enum class ThresholdQuality { High, Medium, Low }

/** Why a threshold analysis has no thresholds. Missing data is a normal outcome, never an error. */
enum class ThresholdReason {
    /** The test has no linked workout samples (deleted or not synced): nothing to analyse. */
    NoWorkoutSamples,

    /**
     * No RR intervals from a strap connected to the app in the analysed part (the console's heart rate, an
     * unknown or imported source, or a strap that does not send them).
     */
    NoRrIntervals,

    /** Too few valid windows (≥ 100 beats, ≤ 5 % artifacts) in the stretch where α1 falls. */
    NotEnoughValidWindows,

    /** α1 does not fall with the heart rate (regression slope ≥ 0). */
    NoAlpha1Decline,

    /** The heart rate where α1 = 0.75 is outside the observed range: VT1 was not crossed (or was already passed). */
    Vt1OutOfRange,
}

/**
 * A threshold: the heart rate where α1 crosses its value, and the load there.
 *
 * @property heartRateBpm Unrounded.
 * @property speedKmh Treadmill load at the threshold (interpolated between the windows that bracket the heart rate);
 *   `null` without samples.
 * @property vo2MlKgMin ACSM oxygen demand of that load; `null` without samples.
 * @property percentVo2max [vo2MlKgMin] as % of the VO2max given to the analysis (an aerobic base indicator).
 * @property percentHeartRateReserve [heartRateBpm] as % of the heart-rate reserve, with the resting heart rate given.
 */
data class ThresholdPoint(
    val heartRateBpm: Double,
    val speedKmh: Double? = null,
    val inclinePercent: Double? = null,
    val vo2MlKgMin: Double? = null,
    val percentVo2max: Double? = null,
    val percentHeartRateReserve: Double? = null,
)

/**
 * Quality metrics of a threshold analysis.
 *
 * @property stretchWindows Valid windows in the regression stretch.
 * @property validWindows Valid windows in the whole analysed part.
 * @property totalWindows Windows in the analysed part.
 * @property rSquared R² of the α1-heart-rate regression.
 * @property meanArtifactPercent Mean share of corrected beats of the stretch windows, %.
 * @property slopePerBpm Slope of the regression, α1 per bpm (negative).
 * @property interceptAlpha1 Intercept of the regression (α1 at 0 bpm).
 */
data class ThresholdMetrics(
    val stretchWindows: Int,
    val validWindows: Int,
    val totalWindows: Int,
    val rSquared: Double,
    val meanArtifactPercent: Double,
    val slopePerBpm: Double,
    val interceptAlpha1: Double,
)

/**
 * The stored outcome of a threshold analysis: VT1 and VT2 (either may be missing), their quality, or the [reason] for
 * having none.
 *
 * @property algorithmVersion Version of the algorithm that produced it ([ThresholdEngine.ALGORITHM_VERSION]): a result
 *   with an older version is recalculated.
 * @property quality `null` when the regression could not be done ([reason] set).
 * @property reason Set when there is no VT1 (VT2 alone is never given).
 */
data class ThresholdResult(
    val algorithmVersion: Int,
    val vt1: ThresholdPoint? = null,
    val vt2: ThresholdPoint? = null,
    val quality: ThresholdQuality? = null,
    val metrics: ThresholdMetrics? = null,
    val reason: ThresholdReason? = null,
) {
    companion object {
        /** A result without thresholds for [reason], at the current algorithm version. */
        fun none(reason: ThresholdReason) = ThresholdResult(ThresholdEngine.ALGORITHM_VERSION, reason = reason)
    }
}

/**
 * One analysis window.
 *
 * @property valid At least [ThresholdRules.minBeats] beats and at most [ThresholdRules.maxArtifactFraction] artifacts.
 * @property alpha1 Only for valid windows.
 * @property heartRateBpm 60 000 / mean corrected RR of the window.
 * @property speedKmh Mean load of the window; `null` without samples.
 */
data class Alpha1Window(
    val startMillis: Long,
    val endMillis: Long,
    val beats: Int,
    val artifactFraction: Double,
    val valid: Boolean,
    val alpha1: Double?,
    val heartRateBpm: Double?,
    val speedKmh: Double? = null,
    val inclinePercent: Double? = null,
)

/** The analysis: the stored [result] and the windows behind it (for charts and later analyses; not stored). */
data class ThresholdAnalysis(
    val result: ThresholdResult,
    val windows: List<Alpha1Window>,
    /** Indices into [windows] of the regression stretch. */
    val stretch: List<Int> = emptyList(),
)

/**
 * What to analyse.
 *
 * @property rr The RR series with beat times (from a strap connected to the app only), in time order; it may cover
 *   more than the analysed part.
 * @property samples Workout samples with the treadmill load, in time order (for the load at each threshold); may be
 *   empty.
 * @property fromMillis Start of the analysed part of the test, on the clock of [rr] and [samples]: the start of the
 *   warm-up, so a threshold crossed before the ramp is still found (since algorithm version 2).
 * @property toMillis End of the effort (the cool-down is never analysed: α1 recovers differently once the load drops).
 * @property maxHeartRateBpm The user's maximum heart rate (windows at or above 95 % of it end the stretch).
 * @property restingHeartRateBpm For the % of the reserve at each threshold; `null` = not given.
 * @property vo2maxMlKgMin VO2max of the test (or of the profile) for the % of VO2max at each threshold.
 * @property walked The test is walked by definition (the maximal walking test): the oxygen demand at each threshold uses
 *   the walking equation at any speed, as its VO2max does ([com.treadz.console.analysis.fitness.WalkingTestVo2max]);
 *   otherwise the equation of the speed ([Acsm.vo2]).
 */
data class ThresholdInput(
    val rr: List<RrInterval>,
    val samples: List<WorkoutSample>,
    val fromMillis: Long,
    val toMillis: Long,
    val maxHeartRateBpm: Int,
    val restingHeartRateBpm: Int? = null,
    val vo2maxMlKgMin: Double? = null,
    val walked: Boolean = false,
)

/** Parameters of [ThresholdEngine] (the defaults are the published method; see the engine). */
data class ThresholdRules(
    val windowSeconds: Int = 120,
    val stepSeconds: Int = 10,
    val minBeats: Int = 100,
    val maxArtifactFraction: Double = 0.05,
    /**
     * A window is valid only when its beats cover it: no stretch without beats longer than this from its start to its
     * first beat, between two beats or from its last beat to its end. α1 over a series spliced across a dropout of the
     * strap means nothing, however many beats are left (since algorithm version 5; the real-time estimator always had it).
     */
    val maxRrGapSeconds: Int = 15,
    /** The stretch starts at the last window above this α1 (confirmed, see [stretchStartConfirmWindows]). */
    val stretchStartAlpha1: Double = 1.0,
    /**
     * The start must be the last of this many consecutive valid windows above [stretchStartAlpha1] (or the series starts
     * before them): a single noisy window above it late in the ramp does not cut the stretch short. The same rule as the
     * end ([stretchEndConfirmWindows]), since algorithm version 5: in a real walking ramp one window at 1.016, after seven
     * at 0.85-0.92, started the stretch there and left out the windows from 122 bpm up.
     */
    val stretchStartConfirmWindows: Int = 2,
    /** The stretch ends at the first window below this α1 (confirmed, see [stretchEndConfirmWindows])... */
    val stretchEndAlpha1: Double = 0.4,
    /**
     * ...when this many consecutive valid windows are below [stretchEndAlpha1] (or the series ends): a single noisy
     * window below it does not cut the stretch short. Assumption, decided with the user (2026-10-17): with 1, one
     * window at 0.39 in a real ramp ended the stretch at the start of the fall and lost VT2.
     */
    val stretchEndConfirmWindows: Int = 2,
    /** ...or before the first window at or above this fraction of the maximum heart rate. */
    val stretchEndHeartRateFraction: Double = 0.95,
    val vt1Alpha1: Double = 0.75,
    val vt2Alpha1: Double = 0.5,
    /** VT2 only when some valid window of the stretch is below this α1. */
    val vt2MinObservedAlpha1: Double = 0.55,
    /** Fewer stretch windows than this: no regression. */
    val minStretchWindows: Int = 3,
    val highMinWindows: Int = 8,
    val highMinRSquared: Double = 0.7,
    val highMaxArtifactPercent: Double = 3.0,
    val mediumMinWindows: Int = 6,
    val mediumMinRSquared: Double = 0.5,
    val mediumMaxArtifactPercent: Double = 5.0,
)

/**
 * Ventilatory thresholds from the short-term scaling exponent α1 of the heart-rate variability (DFA-α1) during an
 * incremental test, from the start of its warm-up to the end of the effort. A pure function: [analyse].
 *
 * 1. **RR cleaning** ([RrCleaning]): outside 300-2000 ms or more than 20 % from the local median of 11 beats is an
 *    artifact, replaced by interpolation; the share of artifacts is counted per window.
 * 2. **Windows** ([Alpha1Windows]) of 120 s every 10 s, entirely inside the analysed part. α1 by DFA over 4-16 beats ([Dfa]). A window
 *    is valid with ≥ 100 beats, ≤ 5 % artifacts (3-5 % counts but lowers the quality, step 5) and no hole without beats
 *    longer than 15 s. Its heart rate is 60 000 / its mean corrected RR, its load the time-weighted mean of the samples.
 * 3. **Regression of α1 against the heart rate** on the falling stretch: from the last valid window with α1 > 1.0 that
 *    follows another valid window above 1.0 (one noisy window does not start it; the first valid window needs none) to
 *    the first with α1 < 0.4 followed by another valid window below 0.4 (one noisy window does not end it), or to the
 *    last one below 95 % of the maximum heart rate (α1 rises again near the maximum).
 *    **VT1** = heart rate at α1 = 0.75; **VT2** = heart rate at α1 = 0.5, only when some valid window of the stretch is
 *    below 0.55. Neither is extrapolated beyond the heart-rate range of the stretch.
 * 4. **Load at each threshold**: interpolated between the windows whose heart rate brackets it; its ACSM oxygen demand
 *    (the walking equation for a walked test, [ThresholdInput.walked]) and its % of the VO2max given.
 * 5. **Quality**: high with ≥ 8 stretch windows, R² ≥ 0.7 and mean artifacts < 3 %; medium with ≥ 6, R² ≥ 0.5 and < 5 %;
 *    low otherwise (stored, but not used for the profile).
 *
 * Sources: Peng et al. 1995 (DFA, see [Dfa]); Gronwald T, Rogers B, Hoos O. Fractal correlation properties of heart rate
 * variability: a new biomarker for intensity distribution in endurance exercise and training prescription? Front
 * Physiol. 2020;11:550572; Rogers B, Giles D, Draper N, Hoos O, Gronwald T. A new detection method defining the aerobic
 * threshold for endurance exercise and training prescription based on fractal correlation properties of heart rate
 * variability. Front Physiol. 2021;11:596567 (α1 = 0.75 ≈ VT1); Rogers B, Giles D, Draper N, Mourot L, Gronwald T.
 * Detection of the anaerobic threshold in endurance sports: validation of a new method using correlation properties of
 * heart rate variability. J Funct Morphol Kinesiol. 2021;6(2):38 (α1 = 0.5 ≈ VT2). The agreement is good for groups;
 * for one person the thresholds can be several bpm off, hence the quality and the combination of several tests in the
 * profile.
 */
object ThresholdEngine {

    /**
     * Version of this algorithm (and of the derived values of the tests); stored with every result. 2 (2026-10-21): the
     * tests analyse from the start of the warm-up instead of the start of the ramp, and their peak heart rate includes
     * the seconds after the end of the effort. 3 (2026-10-23): a maximal test whose peak heart rate is under 85 % of the
     * maximum in use gives no VO2max and no MAS (the engine itself is unchanged; the version makes the stored results be
     * recalculated once with the new rule). 4 (2026-10-25): the RR cleaning also corrects the pause after a premature
     * beat ([RrCleaning]). 5 (2026-10-26): a window with a hole of more than 15 s without beats is not valid
     * ([ThresholdRules.maxRrGapSeconds]); the beats of a sample without a usable reading count
     * ([com.treadz.console.analysis.samples.WorkoutSample.hasStrapRr]); the start of the stretch is confirmed by the
     * window before it ([ThresholdRules.stretchStartConfirmWindows]); the oxygen demand changes to the running equation
     * above 7 km/h instead of 7.5 ([Acsm.vo2]), and the maximal walking test uses the walking one at any speed for its
     * VO2max and the load at its thresholds ([ThresholdInput.walked]).
     */
    const val ALGORITHM_VERSION = 5

    fun analyse(input: ThresholdInput, rules: ThresholdRules = ThresholdRules()): ThresholdAnalysis {
        val start = input.fromMillis
        val end = input.toMillis
        val inPhase = input.rr.count { it.timeMillis in (start + 1)..end }
        if (inPhase == 0) return ThresholdAnalysis(ThresholdResult.none(ThresholdReason.NoRrIntervals), emptyList())

        val times = LongArray(input.rr.size) { input.rr[it].timeMillis }
        val cleaned = RrCleaning.clean(input.rr.map { it.rrMs })
        val windows = Alpha1Windows.compute(times, cleaned, input.samples, start, end, rules)
        val valid = windows.indices.filter { windows[it].valid }
        val stretch = stretch(windows, valid, input.maxHeartRateBpm, rules)
        if (stretch.size < rules.minStretchWindows) {
            return ThresholdAnalysis(ThresholdResult.none(ThresholdReason.NotEnoughValidWindows), windows, stretch)
        }

        val hr = stretch.map { checkNotNull(windows[it].heartRateBpm) }
        val alpha = stretch.map { checkNotNull(windows[it].alpha1) }
        val fit = linearFit(hr, alpha)
        val metrics = ThresholdMetrics(
            stretchWindows = stretch.size,
            validWindows = valid.size,
            totalWindows = windows.size,
            rSquared = fit.rSquared,
            meanArtifactPercent = stretch.map { windows[it].artifactFraction }.average() * 100.0,
            slopePerBpm = fit.slope,
            interceptAlpha1 = fit.intercept,
        )
        val quality = quality(metrics, rules)
        if (fit.slope >= 0.0) {
            return ThresholdAnalysis(
                ThresholdResult(ALGORITHM_VERSION, quality = quality, metrics = metrics, reason = ThresholdReason.NoAlpha1Decline),
                windows, stretch,
            )
        }
        val lo = hr.min()
        val hi = hr.max()
        val vt1Hr = ((rules.vt1Alpha1 - fit.intercept) / fit.slope).takeIf { it in lo..hi }
        val vt2Hr = if (alpha.any { it < rules.vt2MinObservedAlpha1 }) {
            ((rules.vt2Alpha1 - fit.intercept) / fit.slope).takeIf { it in lo..hi }
        } else {
            null
        }
        if (vt1Hr == null) {
            return ThresholdAnalysis(
                ThresholdResult(ALGORITHM_VERSION, quality = quality, metrics = metrics, reason = ThresholdReason.Vt1OutOfRange),
                windows, stretch,
            )
        }
        val ordered = stretch.map { windows[it] }
        return ThresholdAnalysis(
            ThresholdResult(
                algorithmVersion = ALGORITHM_VERSION,
                vt1 = point(vt1Hr, ordered, input),
                vt2 = vt2Hr?.let { point(it, ordered, input) },
                quality = quality,
                metrics = metrics,
            ),
            windows, stretch,
        )
    }

    // ------------------------------------------------------------------ steps

    /** The falling stretch (indices into windows, time order) among the [valid] ones. */
    internal fun stretch(windows: List<Alpha1Window>, valid: List<Int>, maxHr: Int, rules: ThresholdRules): List<Int> {
        if (valid.isEmpty()) return emptyList()
        val hrLimit = rules.stretchEndHeartRateFraction * maxHr
        fun belowEnd(pos: Int): Boolean {
            val w = windows[valid[pos]]
            return checkNotNull(w.heartRateBpm) < hrLimit && checkNotNull(w.alpha1) < rules.stretchEndAlpha1
        }
        var endPos = valid.size - 1
        for ((pos, i) in valid.withIndex()) {
            val w = windows[i]
            if (checkNotNull(w.heartRateBpm) >= hrLimit) {
                endPos = pos - 1
                break
            }
            if (belowEnd(pos)) {
                // Confirmed by the next windows below the end value, or by the series (or the stretch) ending first.
                val confirm = (pos + 1 until minOf(valid.size, pos + rules.stretchEndConfirmWindows)).all { next ->
                    belowEnd(next) || checkNotNull(windows[valid[next]].heartRateBpm) >= hrLimit
                }
                if (confirm) {
                    endPos = pos
                    break
                }
            }
        }
        if (endPos < 0) return emptyList()
        fun aboveStart(pos: Int): Boolean = checkNotNull(windows[valid[pos]].alpha1) > rules.stretchStartAlpha1
        var startPos = 0
        for (pos in endPos downTo 0) {
            // Confirmed by the previous windows above the start value, or by the series starting first.
            if (aboveStart(pos) && (maxOf(0, pos - rules.stretchStartConfirmWindows + 1) until pos).all(::aboveStart)) {
                startPos = pos
                break
            }
        }
        return valid.subList(startPos, endPos + 1)
    }

    private fun quality(m: ThresholdMetrics, rules: ThresholdRules): ThresholdQuality = when {
        m.stretchWindows >= rules.highMinWindows && m.rSquared >= rules.highMinRSquared && m.meanArtifactPercent < rules.highMaxArtifactPercent ->
            ThresholdQuality.High
        m.stretchWindows >= rules.mediumMinWindows && m.rSquared >= rules.mediumMinRSquared && m.meanArtifactPercent < rules.mediumMaxArtifactPercent ->
            ThresholdQuality.Medium
        else -> ThresholdQuality.Low
    }

    /** The threshold at [heartRate]: the load interpolated between the first pair of windows that brackets it. */
    private fun point(heartRate: Double, windows: List<Alpha1Window>, input: ThresholdInput): ThresholdPoint {
        var speed: Double? = null
        var incline: Double? = null
        for (k in 0 until windows.size - 1) {
            val a = windows[k]
            val b = windows[k + 1]
            val ha = checkNotNull(a.heartRateBpm)
            val hb = checkNotNull(b.heartRateBpm)
            if (heartRate < minOf(ha, hb) || heartRate > maxOf(ha, hb)) continue
            val f = if (abs(hb - ha) < 1e-9) 0.0 else (heartRate - ha) / (hb - ha)
            if (a.speedKmh != null && b.speedKmh != null && a.inclinePercent != null && b.inclinePercent != null) {
                speed = a.speedKmh + f * (b.speedKmh - a.speedKmh)
                incline = a.inclinePercent + f * (b.inclinePercent - a.inclinePercent)
            }
            break
        }
        if (speed == null) {
            val nearest = windows.filter { it.speedKmh != null }.minByOrNull { abs(checkNotNull(it.heartRateBpm) - heartRate) }
            speed = nearest?.speedKmh
            incline = nearest?.inclinePercent
        }
        val vo2 = if (speed != null && incline != null) {
            if (input.walked) Acsm.walkingVo2(speed, incline) else Acsm.vo2(speed, incline)
        } else {
            null
        }
        val rest = input.restingHeartRateBpm?.takeIf { it < input.maxHeartRateBpm }
        return ThresholdPoint(
            heartRateBpm = heartRate,
            speedKmh = speed,
            inclinePercent = incline,
            vo2MlKgMin = vo2,
            percentVo2max = if (vo2 != null && input.vo2maxMlKgMin != null && input.vo2maxMlKgMin > 0) vo2 / input.vo2maxMlKgMin * 100.0 else null,
            percentHeartRateReserve = rest?.let { (heartRate - it) / (input.maxHeartRateBpm - it) * 100.0 },
        )
    }

    private class Fit(val slope: Double, val intercept: Double, val rSquared: Double)

    private fun linearFit(x: List<Double>, y: List<Double>): Fit {
        val mx = x.average()
        val my = y.average()
        var sxy = 0.0
        var sxx = 0.0
        var syy = 0.0
        for (i in x.indices) {
            sxy += (x[i] - mx) * (y[i] - my)
            sxx += (x[i] - mx) * (x[i] - mx)
            syy += (y[i] - my) * (y[i] - my)
        }
        if (sxx <= 0.0) return Fit(0.0, my, 0.0)
        val slope = sxy / sxx
        val r2 = if (syy <= 0.0) 0.0 else sxy * sxy / (sxx * syy)
        return Fit(slope, my - slope * mx, r2)
    }
}
