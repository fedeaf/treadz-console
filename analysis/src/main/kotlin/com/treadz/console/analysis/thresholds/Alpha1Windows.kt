package com.treadz.console.analysis.thresholds

import com.treadz.console.analysis.rr.CleanedRr
import com.treadz.console.analysis.rr.Dfa
import com.treadz.console.analysis.rr.RrCleaning
import com.treadz.console.analysis.samples.LoadAverage
import com.treadz.console.analysis.samples.RrInterval
import com.treadz.console.analysis.samples.WorkoutSample

/**
 * Sliding DFA-α1 windows over any time span of an RR series (the windowing of [ThresholdEngine], made reusable for the
 * analysis of a whole workout).
 *
 * Windows of [ThresholdRules.windowSeconds] (120 s) start at `fromMillis` and advance by [ThresholdRules.stepSeconds]
 * (10 s) while they end at or before `toMillis`. A window holds the beats whose time is in (start, end]. It is *valid*
 * with at least [ThresholdRules.minBeats] (100) beats, at most [ThresholdRules.maxArtifactFraction] (5 %) of them
 * artifacts ([RrCleaning]) and a computable α1 ([Dfa.alpha1]); windows that are not valid carry no α1. Its heart rate
 * is 60 000 / the mean corrected RR; its load is the time-weighted mean of the given samples (sample-and-hold, see
 * [LoadAverage]), `null` without samples.
 */
object Alpha1Windows {

    /** Cleans [rr] and returns its windows between [fromMillis] and [toMillis] (see the object description). */
    fun compute(
        rr: List<RrInterval>,
        samples: List<WorkoutSample>,
        fromMillis: Long,
        toMillis: Long,
        rules: ThresholdRules = ThresholdRules(),
    ): List<Alpha1Window> {
        val times = LongArray(rr.size) { rr[it].timeMillis }
        return compute(times, RrCleaning.clean(rr.map { it.rrMs }), samples, fromMillis, toMillis, rules)
    }

    /**
     * The same with an RR series already cleaned: [times] are the beat times (in order) and [cleaned] their corrected
     * intervals, one per beat.
     */
    fun compute(
        times: LongArray,
        cleaned: CleanedRr,
        samples: List<WorkoutSample>,
        fromMillis: Long,
        toMillis: Long,
        rules: ThresholdRules = ThresholdRules(),
    ): List<Alpha1Window> {
        val length = rules.windowSeconds * 1000L
        val step = rules.stepSeconds * 1000L
        val out = ArrayList<Alpha1Window>()
        var s = fromMillis
        while (s + length <= toMillis) {
            val e = s + length
            val from = firstAfter(times, s)
            val to = firstAfter(times, e)
            val beats = to - from
            var artifacts = 0
            var sum = 0.0
            for (i in from until to) {
                if (cleaned.artifact[i]) artifacts++
                sum += cleaned.rrMs[i]
            }
            val fraction = if (beats > 0) artifacts.toDouble() / beats else 1.0
            val valid = beats >= rules.minBeats && fraction <= rules.maxArtifactFraction + 1e-12
            val alpha = if (valid) Dfa.alpha1(cleaned.rrMs, from, to) else null
            val load = LoadAverage.over(samples, s, e)
            out += Alpha1Window(
                startMillis = s,
                endMillis = e,
                beats = beats,
                artifactFraction = fraction,
                valid = valid && alpha != null,
                alpha1 = alpha,
                heartRateBpm = if (beats > 0) 60_000.0 / (sum / beats) else null,
                speedKmh = load?.speedKmh,
                inclinePercent = load?.inclinePercent,
            )
            s += step
        }
        return out
    }

    /** Index of the first beat with time > [t]. */
    private fun firstAfter(times: LongArray, t: Long): Int {
        var lo = 0
        var hi = times.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (times[mid] <= t) lo = mid + 1 else hi = mid
        }
        return lo
    }
}
