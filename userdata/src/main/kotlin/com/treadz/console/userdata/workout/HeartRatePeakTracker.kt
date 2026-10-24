package com.treadz.console.userdata.workout

import com.treadz.console.analysis.heartrate.StrapWindowJudge
import com.treadz.console.programs.fitness.HeartRateSourceKind
import com.treadz.console.programs.heartrate.HeartRatePlausibilityFilter
import com.treadz.console.treadmill.BeltStatus
import com.treadz.console.userdata.HeartRatePeak
import com.treadz.console.userdata.WorkoutSample
import kotlin.math.roundToInt

/**
 * Finds the **highest 5-second mean of plausible heart-rate readings** of a workout, one reading at a time (the
 * observer feeds it while the session runs) or from stored samples ([of]).
 *
 * - A reading is plausible when `HeartRatePlausibilityFilter` accepts it: not below 40 bpm, not above the user's
 *   maximum + 15 bpm (or 240 without a known maximum), no jump of more than 30 bpm in 1.5 s, and no fall of more than
 *   25 bpm in 5 s (what the console reports when the strap is taken off: it stays suspicious until the readings recover).
 *   A missing reading (`null`) resets the filter.
 * - The window is the accepted readings of the last 5 seconds (`(t - 5 s, t]`). It counts only with at least
 *   [MIN_READINGS] readings spread over at least [MIN_SPAN_MS], so a single spike, or a few readings crowded together,
 *   never makes a peak. The mean is compared unrounded and the peak keeps the earliest of equal windows.
 * - With a strap connected to the app and its RR intervals, a window the RR do not confirm (`StrapWindowJudge`: many
 *   artifacts, or the strap's heart rate and the RR disagree) is not a peak: a strap can report a few bpm too many for
 *   10-15 s while its RR say otherwise. Without RR (the console's heart rate) every window counts, as before.
 *
 * Not thread-safe.
 */
class HeartRatePeakTracker(maxHeartRateBpm: Int? = null) {

    private class Reading(val atMillis: Long, val bpm: Int, val source: HeartRateSourceKind?)

    private val filter = HeartRatePlausibilityFilter(maxHeartRateBpm)
    private val strapJudge = StrapWindowJudge()
    private val window = ArrayDeque<Reading>()
    private var bestMean = 0.0

    /** The highest window so far, `null` while there was none. */
    var peak: HeartRatePeak? = null
        private set

    /**
     * Adds the reading taken at [atMillis] (times must not go back). [bpm] `null` or 0 means no reading. [rrIntervalsMs]
     * are the RR intervals that arrived with it (each interval once), used for a strap connected to the app.
     */
    fun add(atMillis: Long, bpm: Int?, source: HeartRateSourceKind?, rrIntervalsMs: List<Int> = emptyList()) {
        val accepted = filter.accept(atMillis, bpm?.takeIf { it > 0 })
        if (source == HeartRateSourceKind.DirectStrap) {
            // The beats go in even when the reading is rejected: the cleaning judges them.
            strapJudge.addRr(atMillis, rrIntervalsMs)
            if (accepted != null) strapJudge.addReading(atMillis, accepted)
            strapJudge.trimBefore(atMillis - JUDGE_KEEP_MS)
        }
        while (window.isNotEmpty() && window.first().atMillis <= atMillis - WINDOW_MS) window.removeFirst()
        if (accepted == null) return
        window.addLast(Reading(atMillis, accepted, source))
        if (window.size < MIN_READINGS || atMillis - window.first().atMillis < MIN_SPAN_MS) return
        val mean = window.sumOf { it.bpm }.toDouble() / window.size
        if (peak == null || mean > bestMean) {
            if (strapJudge.judge(atMillis - WINDOW_MS, atMillis).isDoubtful) return
            bestMean = mean
            peak = HeartRatePeak(mean.roundToInt(), source, atMillis)
        }
    }

    companion object {
        const val WINDOW_MS = 5_000L
        const val MIN_READINGS = 4
        const val MIN_SPAN_MS = 3_000L

        /** Readings and beats kept for the RR check: the window plus context for the artifact correction. */
        private const val JUDGE_KEEP_MS = 30_000L

        /**
         * The peak of stored [samples] (any order). Stale samples and the closing sample of a workout (the packet that
         * reports the belt stopped, which repeats the last reading) are left out, as the live computation does.
         */
        fun of(samples: List<WorkoutSample>, maxHeartRateBpm: Int? = null): HeartRatePeak? {
            val tracker = HeartRatePeakTracker(maxHeartRateBpm)
            for (s in samples.sortedBy { it.seq }) {
                if (s.isStale || (s.beltStatus == BeltStatus.Idle && s.speedKmh == 0.0)) continue
                tracker.add(s.timestampMillis, s.heartRateBpm, s.heartRateSource, s.rrIntervalsMs)
            }
            return tracker.peak
        }
    }
}
