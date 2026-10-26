package com.treadz.console.analysis.thresholds

import com.treadz.console.analysis.rr.RrCleaning
import com.treadz.console.analysis.samples.RrInterval
import com.treadz.console.analysis.samples.RrSeries

/**
 * One real-time evaluation of DFA-α1: the window of [ThresholdRules.windowSeconds] (120 s) that ends at [atMillis].
 *
 * @property windowStartMillis Start of the window (`atMillis` − 120 s): the beats in (start, at] were used.
 * @property beats Beats in the window.
 * @property artifactFraction Share of the window's beats that were artifacts (corrected); 1.0 for a window without
 *   beats.
 * @property valid The window has enough beats and few enough artifacts ([ThresholdRules.minBeats],
 *   [ThresholdRules.maxArtifactFraction]), the beats cover the window from its start to its end (see
 *   [ThresholdRules.maxRrGapSeconds]) and α1 is computable.
 * @property alpha1 Only when [valid].
 * @property heartRateBpm 60 000 / the mean corrected RR of the window; `null` without beats.
 */
data class Alpha1Evaluation(
    val atMillis: Long,
    val windowStartMillis: Long,
    val beats: Int,
    val artifactFraction: Double,
    val valid: Boolean,
    val alpha1: Double?,
    val heartRateBpm: Double? = null,
)

/**
 * DFA-α1 in real time: a small stateful estimator fed with the RR intervals of a strap as they arrive.
 *
 * It uses the same rules as the threshold analysis ([ThresholdRules], [RrCleaning], [Alpha1Windows]): every
 * [evaluationIntervalMs] (10 s) it evaluates the last 120 s of beats, which are valid with at least 100 beats and at
 * most 5 % artifacts. It has no clock of its own: every time comes in as an argument, on one clock shared by
 * [append] and [poll] (RR are placed in time as [RrSeries.append] does).
 *
 * The buffer is bounded: beats older than the window plus [CONTEXT_MS] (a few beats of context for the local median
 * of the artifact correction at the start of the window) are dropped, and it never holds more than [MAX_BEATS]. An
 * evaluation cleans and analyses a few hundred beats, so running it every 10 s on a phone is cheap.
 *
 * **Missing RR**: the beats must cover the whole window ([ThresholdRules.maxRrGapSeconds], the rule of every α1 window).
 * An evaluation is not valid when the first beat of the window is more than 15 s after its start (the strap has not
 * been delivering RR for the full 2 min yet, e.g. just connected), the newest beat is more than 15 s before the
 * evaluation time (the strap stopped delivering RR) or two consecutive beats are more than 15 s apart (a dropout in the
 * middle: α1 over the spliced series would mean nothing). Not thread-safe: the caller guards it.
 *
 * α1 from a 2-min window lags the effort by about a minute and a half and is sensitive to artifacts; see
 * [ThresholdEngine] for the sources.
 */
class RealTimeAlpha1(
    private val rules: ThresholdRules = ThresholdRules(),
    /** Time between evaluations of [poll]. */
    val evaluationIntervalMs: Long = rules.stepSeconds * 1000L,
) {
    private val windowMs = rules.windowSeconds * 1000L
    private val beats = ArrayDeque<RrInterval>()
    private var nextEvaluationAt = Long.MIN_VALUE

    /** Beats currently held (for tests and diagnostics). */
    val bufferedBeats: Int get() = beats.size

    /**
     * Adds the RR intervals ([rrMs], oldest first) of one strap notification received at [receivedAtMillis]. The last
     * one ends at [receivedAtMillis]; the earlier ones are placed back from it by their own lengths.
     */
    fun append(receivedAtMillis: Long, rrMs: List<Int>) {
        if (rrMs.isEmpty()) return
        RrSeries.append(beats, receivedAtMillis, rrMs)
        val newest = beats.last().timeMillis
        while (beats.isNotEmpty() && (beats.size > MAX_BEATS || beats.first().timeMillis <= newest - windowMs - CONTEXT_MS)) beats.removeFirst()
    }

    /** Forgets every beat and restarts the evaluation schedule. */
    fun reset() {
        beats.clear()
        nextEvaluationAt = Long.MIN_VALUE
    }

    /**
     * The evaluation due at [nowMillis], or `null` when the last one was less than [evaluationIntervalMs] ago. The
     * first call evaluates at once; a late call evaluates once (no catching up) and the next one is due
     * [evaluationIntervalMs] after it.
     */
    fun poll(nowMillis: Long): Alpha1Evaluation? {
        if (nowMillis < nextEvaluationAt) return null
        nextEvaluationAt = nowMillis + evaluationIntervalMs
        return evaluate(nowMillis)
    }

    /** Evaluates the window ending at [nowMillis] (whatever the schedule). */
    fun evaluate(nowMillis: Long): Alpha1Evaluation {
        val start = nowMillis - windowMs
        // Beats after "now" (clock jitter) and beats before the window's context are left out of the series.
        val series = beats.filter { it.timeMillis <= nowMillis && it.timeMillis > start - CONTEXT_MS }
        val times = LongArray(series.size) { series[it].timeMillis }
        val cleaned = RrCleaning.clean(series.map { it.rrMs })
        // Exactly one window: it starts at `start` and the next one would end after `now`.
        val window = Alpha1Windows.compute(times, cleaned, emptyList(), start, nowMillis, rules).single()
        return Alpha1Evaluation(
            atMillis = nowMillis,
            windowStartMillis = start,
            beats = window.beats,
            artifactFraction = window.artifactFraction,
            valid = window.valid,
            alpha1 = window.alpha1,
            heartRateBpm = window.heartRateBpm,
        )
    }

    companion object {
        /** Beats kept before the start of the window as context for the artifact correction. */
        const val CONTEXT_MS = 10_000L

        /** Hard bound of the buffer (120 s + context at 250 bpm is about 540 beats). */
        const val MAX_BEATS = 1_000
    }
}
