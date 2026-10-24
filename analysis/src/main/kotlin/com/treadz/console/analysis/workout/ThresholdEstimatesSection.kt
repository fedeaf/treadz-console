package com.treadz.console.analysis.workout

import com.treadz.console.analysis.rr.CleanedRr
import com.treadz.console.analysis.samples.RrInterval
import com.treadz.console.analysis.samples.SampleHeartRateSource
import com.treadz.console.analysis.thresholds.Alpha1Windows
import com.treadz.console.analysis.thresholds.ThresholdRules
import kotlin.math.abs
import kotlin.math.min

/**
 * The thresholds a workout implies (item 9 of [WorkoutAnalyzer]): a section **for the recommendation of a test only**.
 * A workout never changes the profile, because a workout can start tired (weights, a long day), which lowers α1 at the
 * same load; the test is done rested and knowing what it is for.
 *
 * **Stable stretch**: at least [AnalysisRules.stretchMinSeconds] (5 min) with the belt moving and the speed and the
 * incline unchanged ([AnalysisRules.stretchSpeedToleranceKmh], [AnalysisRules.stretchInclineTolerancePercent]), inside the
 * first [AnalysisRules.stretchWithinSeconds] (40 min) of the workout (counted from the first second with the belt moving;
 * a stretch that goes on is cut there). Its first [AnalysisRules.stretchSettleSeconds] (2 min) are left out because the
 * heart rate is settling; after them the heart rate (mean of the last [AnalysisRules.stretchHeartRateSmoothingSeconds]
 * seconds) must stay within [AnalysisRules.stretchHeartRateBandBpm] (±4 bpm) of its mean, in the strap's readings
 * (at least [AnalysisRules.minHeartRateCoverage] of the seconds have one). *Assumption*: when the heart rate drifts out of
 * the band later in a long stretch, the stretch ends where it was last inside it (at least 3 min after the settling
 * are needed). Not after a hard effort: more than [AnalysisRules.stretchHardEffortSeconds] (1 min) above VT2 earlier in
 * the workout (`WorkoutAnalysisContext.vt2Bpm`, else the VT2 of the context zones; without either the rule is not applied).
 *
 * **Clean RR**: α1 windows of 120 s every 10 s ([Alpha1Windows]) entirely inside the analysed part of the stretch, valid
 * with at most [AnalysisRules.stretchMaxArtifactFraction] (3 %) artifacts and 100 beats. *Assumption*: the stretch needs
 * at least [AnalysisRules.stretchMinCleanWindows] clean windows and half of its windows. Its α1 and heart rate are the
 * mean of the clean windows (the same engine as the thresholds of a test, F1b).
 *
 * **Implied threshold**, with the α1-heart-rate slope *s* (α1 per bpm, negative) of the user's latest test with
 * thresholds: VT1 = heart rate + (0.75 − α1) / *s*, for any stretch with clean RR; VT2 = heart rate + (0.5 − α1) / *s*
 * only when running (speed ≥ [AnalysisRules.vt2MinSpeedKmh]) and with the stretch α1 at or below
 * [AnalysisRules.vt2MaxAlpha1]. A value outside 30 bpm to the maximum heart rate is discarded. Its weight is the duration
 * of the stretch. Without a slope, no estimate ([AnalysisReason.NoThresholdReference]).
 */
internal object ThresholdEstimatesSection {

    fun compute(
        tl: WorkoutTimeline,
        rr: List<RrInterval>,
        cleaned: CleanedRr?,
        context: WorkoutAnalysisContext,
        rules: AnalysisRules,
    ): Section<ThresholdEstimatesAnalysis> {
        val slope = context.alpha1SlopePerBpm?.takeIf { it.isFinite() && it < 0.0 }
            ?: return Section.missing(AnalysisReason.NoThresholdReference)
        if (rr.isEmpty() || cleaned == null) return Section.missing(AnalysisReason.NoRrIntervals)
        val stretches = stretches(tl, context, rules)
        if (stretches.isEmpty()) return Section.missing(AnalysisReason.NoSteadyStretch)

        val times = LongArray(rr.size) { rr[it].timeMillis }
        val windowRules = ThresholdRules(maxArtifactFraction = rules.stretchMaxArtifactFraction)
        val thresholds = ThresholdRules()
        val estimates = ArrayList<WorkoutThresholdEstimate>()
        for (st in stretches) {
            val from = tl.timeOf(st.analysedStart)
            val to = tl.timeOf(st.end)
            val windows = Alpha1Windows.compute(times, cleaned, emptyList(), from, to, windowRules)
            val clean = windows.filter { it.valid && it.alpha1 != null && it.heartRateBpm != null }
            if (clean.size < rules.stretchMinCleanWindows || clean.size < rules.stretchMinCleanFraction * windows.size - 1e-9) continue
            val alpha = clean.map { checkNotNull(it.alpha1) }.average()
            val hr = clean.map { checkNotNull(it.heartRateBpm) }.average()
            fun estimate(kind: ThresholdKind, target: Double): WorkoutThresholdEstimate? {
                val implied = hr + (target - alpha) / slope
                if (!implied.isFinite() || implied < rules.minHeartRateBpm || implied > context.maxHeartRateBpm) return null
                return WorkoutThresholdEstimate(
                    kind = kind,
                    startMillis = tl.timeOf(st.start),
                    endMillis = tl.timeOf(st.end),
                    speedKmh = st.speedKmh,
                    inclinePercent = st.inclinePercent,
                    cleanWindows = clean.size,
                    alpha1 = alpha,
                    heartRateBpm = hr,
                    slopePerBpm = slope,
                    impliedBpm = implied,
                    weightSeconds = st.end - st.start,
                )
            }
            estimate(ThresholdKind.Vt1, thresholds.vt1Alpha1)?.let { estimates += it }
            if (st.speedKmh >= rules.vt2MinSpeedKmh - 1e-9 && alpha <= rules.vt2MaxAlpha1 + 1e-9) {
                estimate(ThresholdKind.Vt2, thresholds.vt2Alpha1)?.let { estimates += it }
            }
        }
        if (estimates.isEmpty()) return Section.missing(AnalysisReason.NoValidWindows)
        return Section.of(ThresholdEstimatesAnalysis(stretches.size, estimates))
    }

    /** A stable stretch: ticks [start, end) of the timeline, the first part [start, analysedStart) left out. */
    internal class Stretch(val start: Int, val analysedStart: Int, val end: Int, val speedKmh: Double, val inclinePercent: Double)

    /** The stable stretches of the workout (see the object), in time order. */
    internal fun stretches(tl: WorkoutTimeline, context: WorkoutAnalysisContext, rules: AnalysisRules): List<Stretch> {
        val first = (0 until tl.size).firstOrNull { tl.moving[it] } ?: return emptyList()
        val limit = min(tl.size.toLong(), first + rules.stretchWithinSeconds.toLong()).toInt()
        val hard = context.vt2Bpm ?: context.zones?.vt2Bpm?.toDouble()
        val out = ArrayList<Stretch>()
        var k = first
        while (k < limit) {
            if (!tl.moving[k]) {
                k++
                continue
            }
            val s0 = k
            var e = k + 1
            while (e < limit && tl.moving[e] &&
                abs(tl.speed[e] - tl.speed[s0]) <= rules.stretchSpeedToleranceKmh + 1e-9 &&
                abs(tl.incline[e] - tl.incline[s0]) <= rules.stretchInclineTolerancePercent + 1e-9
            ) e++
            if (e - s0 >= rules.stretchMinSeconds && !afterHardEffort(tl, first, s0, hard, rules)) {
                val end = steadyEnd(tl, s0 + rules.stretchSettleSeconds, e, rules)
                if (end != null) out += Stretch(s0, s0 + rules.stretchSettleSeconds, end, tl.speed[s0], tl.incline[s0])
            }
            k = e
        }
        return out
    }

    /** More than [AnalysisRules.stretchHardEffortSeconds] seconds above [vt2] between [from] and [until]. */
    private fun afterHardEffort(tl: WorkoutTimeline, from: Int, until: Int, vt2: Double?, rules: AnalysisRules): Boolean {
        if (vt2 == null) return false
        var seconds = 0
        for (i in from until until) if (tl.hr[i] > 0 && tl.hr[i] > vt2) seconds++
        return seconds > rules.stretchHardEffortSeconds
    }

    /**
     * The end of the steady part of [from, to): the largest end (at least [AnalysisRules.stretchMinSeconds] −
     * [AnalysisRules.stretchSettleSeconds] after [from]) up to which the smoothed strap heart rate stays within the band
     * around its mean, or `null` when there is none or the heart rate is missing.
     */
    private fun steadyEnd(tl: WorkoutTimeline, from: Int, to: Int, rules: AnalysisRules): Int? {
        val minLength = rules.stretchMinSeconds - rules.stretchSettleSeconds
        if (to - from < minLength) return null
        val smoothing = rules.stretchHeartRateSmoothingSeconds
        var n = 0
        var sum = 0.0
        var lo = Double.MAX_VALUE
        var hi = -Double.MAX_VALUE
        var best: Int? = null
        var withHeartRate = 0
        for (i in from until to) {
            // Only the strap's readings count: its RR are what the section analyses.
            if (tl.hr[i] > 0 && tl.source[i] == SampleHeartRateSource.Strap) withHeartRate++
            val smoothed = smoothedHeartRate(tl, i, smoothing, from)
            if (smoothed != null) {
                n++
                sum += smoothed
                lo = minOf(lo, smoothed)
                hi = maxOf(hi, smoothed)
            }
            val length = i + 1 - from
            if (length >= minLength && n > 0 && withHeartRate >= rules.minHeartRateCoverage * length - 1e-9) {
                val mean = sum / n
                if (hi - mean <= rules.stretchHeartRateBandBpm + 1e-9 && mean - lo <= rules.stretchHeartRateBandBpm + 1e-9) best = i + 1
            }
        }
        return best
    }

    /** Mean of the strap's readings of the [seconds] up to [i] (inclusive) and not before [from]; `null` when there is none. */
    private fun smoothedHeartRate(tl: WorkoutTimeline, i: Int, seconds: Int, from: Int): Double? {
        var n = 0
        var sum = 0.0
        for (j in maxOf(from, i - seconds + 1)..i) {
            if (tl.hr[j] > 0 && tl.source[j] == SampleHeartRateSource.Strap) {
                n++
                sum += tl.hr[j]
            }
        }
        return if (n == 0) null else sum / n
    }
}
