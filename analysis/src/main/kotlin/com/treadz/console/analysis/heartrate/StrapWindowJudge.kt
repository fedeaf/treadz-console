package com.treadz.console.analysis.heartrate

import com.treadz.console.analysis.rr.RrCleaning
import com.treadz.console.analysis.samples.RrInterval
import com.treadz.console.analysis.samples.RrSeries
import com.treadz.console.analysis.samples.SampleHeartRateSource
import com.treadz.console.analysis.samples.WorkoutSample
import kotlin.math.abs

/**
 * Thresholds of [StrapWindowJudge].
 *
 * @property enabled `false` turns the judgement off: every window is [StrapWindowVerdict.NotJudged].
 * @property maxArtifactFraction The window is doubtful when more than this share of its beats are artifacts (same
 *   correction as the strap check and the threshold engine, [RrCleaning]). The strap check of the maximal tests warns at
 *   5 % of a whole warm-up; a 10-s window holds only about 20 beats, so 10 % tolerates two bad beats in it and not
 *   three.
 * @property maxDifferenceBpm The window is doubtful when the heart rate the strap reports (mean of its readings in the
 *   window) and the heart rate of the RR (60 000 / median of the window's clean RR) differ by more than this. A healthy
 *   strap agrees with its own RR within a couple of bpm; the audit's false readings were 8-11 bpm off.
 * @property minBeats Fewer beats than this in the window and it is not judged: with 5 beats one artifact is already 20 %
 *   and one beat decides the median. A 10-s window has 20+ beats from 120 bpm up and a 5-s window 10+ from 120 bpm up,
 *   so this only leaves out very slow rates and windows with missing RR.
 */
data class StrapWindowConfig(
    val enabled: Boolean = true,
    val maxArtifactFraction: Double = 0.10,
    val maxDifferenceBpm: Double = 4.0,
    val minBeats: Int = 6,
)

/** The outcome of judging a window of strap readings against the RR of the same window. */
enum class StrapWindowVerdict {
    /** There is not enough RR (console heart rate, a strap without RR, a dropout, too few beats) to judge: use the readings as they are. */
    NotJudged,

    /** The RR confirm the readings. */
    Reliable,

    /** The readings cannot be trusted: see [StrapWindowJudgement.reasons]. */
    Doubtful,
}

/** Why a window is [StrapWindowVerdict.Doubtful]. */
enum class StrapDoubt {
    /** More than [StrapWindowConfig.maxArtifactFraction] of the window's beats are artifacts. */
    TooManyArtifacts,

    /** The strap's heart rate and the heart rate of the RR differ by more than [StrapWindowConfig.maxDifferenceBpm]. */
    RateMismatch,
}

/**
 * What [StrapWindowJudge.judge] found in a window `(from, to]`.
 *
 * @property beats RR intervals (beats) of the window.
 * @property artifactFraction Share of them that are artifacts; `null` without beats.
 * @property rrHeartRateBpm 60 000 / the median of the window's clean (non-artifact) RR; `null` without any clean beat.
 * @property strapMeanBpm Mean of the strap readings of the window; `null` without readings.
 * @property reasons Empty unless the window is doubtful.
 */
data class StrapWindowJudgement(
    val verdict: StrapWindowVerdict,
    val beats: Int,
    val artifactFraction: Double?,
    val rrHeartRateBpm: Double?,
    val strapMeanBpm: Double?,
    val reasons: Set<StrapDoubt> = emptySet(),
) {
    val isDoubtful: Boolean get() = verdict == StrapWindowVerdict.Doubtful

    companion object {
        val NOT_JUDGED = StrapWindowJudgement(StrapWindowVerdict.NotJudged, 0, null, null, null)
    }
}

/**
 * Judges whether the heart rate a chest strap reports over a window of time can be trusted, by checking it against the
 * strap's own RR intervals of that window (decision of 2026-10-24, from the audit of the zone-2 workout of that day: the
 * strap showed 135-137 bpm for 10-15 s while the clean RR said 127-131, and the control lowered the load for it).
 *
 * A window is **doubtful** when
 *  - more than [StrapWindowConfig.maxArtifactFraction] of its beats are artifacts ([RrCleaning]), or
 *  - the heart rate from the RR (60 000 / the median of the clean RR) and the mean of the strap readings differ by more
 *    than [StrapWindowConfig.maxDifferenceBpm].
 *
 * It is **not judged** ([StrapWindowVerdict.NotJudged]) with fewer than [StrapWindowConfig.minBeats] beats in the window,
 * and so without RR at all (the heart rate of the treadmill console, a strap that sends no RR): the caller then
 * behaves as it did before this check existed.
 *
 * **Feeding it**: [addReading] with every strap reading (time, bpm) and [addRr] with the RR intervals of every strap
 * notification and the time the notification arrived (the beats are placed in time as [RrSeries.append] does). Times
 * are one clock of the caller's choice (epoch ms, test time...) and must not go back. [trimBefore] bounds the memory in
 * a long session. The artifact correction runs over everything held (with its neighbours as context, as the threshold
 * engine does) and is kept until something is added, so judging many windows of the same data (the sliding windows of a
 * peak search) is cheap. [fromSamples] builds one from stored samples (time, bpm and RR of each sample).
 *
 * Not thread-safe: the caller guards it.
 */
class StrapWindowJudge(val config: StrapWindowConfig = StrapWindowConfig()) {

    private var readingTimes = LongArray(16)
    private var readingBpm = IntArray(16)
    private var readingCount = 0
    private var readingStart = 0

    private val beats = ArrayList<RrInterval>()
    private var cleaned: BooleanArray? = null

    /** Readings held (for tests and diagnostics). */
    val readingsHeld: Int get() = readingCount - readingStart

    /** Beats held (for tests and diagnostics). */
    val beatsHeld: Int get() = beats.size

    /** A strap reading: [bpm] taken at [timeMillis]. */
    fun addReading(timeMillis: Long, bpm: Int) {
        if (readingCount == readingTimes.size) {
            if (readingStart > 0) {
                // Reuse the room freed by trimBefore.
                readingTimes.copyInto(readingTimes, 0, readingStart, readingCount)
                readingBpm.copyInto(readingBpm, 0, readingStart, readingCount)
                readingCount -= readingStart
                readingStart = 0
            } else {
                readingTimes = readingTimes.copyOf(readingCount * 2)
                readingBpm = readingBpm.copyOf(readingCount * 2)
            }
        }
        readingTimes[readingCount] = timeMillis
        readingBpm[readingCount] = bpm
        readingCount++
    }

    /**
     * The RR intervals ([rrMs], oldest first) of one strap notification received at [receivedAtMillis]; the last one ends
     * at that time and the earlier ones are placed back from it by their own lengths.
     */
    fun addRr(receivedAtMillis: Long, rrMs: List<Int>) {
        if (rrMs.isEmpty()) return
        RrSeries.append(beats, receivedAtMillis, rrMs)
        cleaned = null
    }

    /** Forgets the readings and beats at or before [timeMillis]. Keep some context before the windows still to judge. */
    fun trimBefore(timeMillis: Long) {
        while (readingStart < readingCount && readingTimes[readingStart] <= timeMillis) readingStart++
        var drop = 0
        while (drop < beats.size && beats[drop].timeMillis <= timeMillis) drop++
        if (drop > 0) {
            beats.subList(0, drop).clear()
            cleaned = null
        }
    }

    /** Forgets everything (the source changed, or the series starts again). */
    fun reset() {
        readingCount = 0
        readingStart = 0
        beats.clear()
        cleaned = null
    }

    /**
     * Judges the window `(fromMillis, toMillis]`: the readings and beats whose time lies in it.
     */
    fun judge(fromMillis: Long, toMillis: Long): StrapWindowJudgement {
        if (!config.enabled) return StrapWindowJudgement.NOT_JUDGED
        val first = firstBeatAfter(fromMillis)
        var last = first
        while (last < beats.size && beats[last].timeMillis <= toMillis) last++
        val n = last - first
        if (n < config.minBeats) return StrapWindowJudgement.NOT_JUDGED.copy(beats = n)

        val artifact = cleaned ?: RrCleaning.clean(beats.map { it.rrMs }).artifact.also { cleaned = it }
        val clean = ArrayList<Int>(n)
        for (i in first until last) if (!artifact[i]) clean += beats[i].rrMs
        val fraction = (n - clean.size).toDouble() / n
        val rrRate = if (clean.isEmpty()) null else 60_000.0 / median(clean)
        val strapMean = strapMean(fromMillis, toMillis)

        val reasons = LinkedHashSet<StrapDoubt>()
        if (fraction > config.maxArtifactFraction) reasons += StrapDoubt.TooManyArtifacts
        if (rrRate != null && strapMean != null && abs(strapMean - rrRate) > config.maxDifferenceBpm) reasons += StrapDoubt.RateMismatch
        return StrapWindowJudgement(
            verdict = if (reasons.isEmpty()) StrapWindowVerdict.Reliable else StrapWindowVerdict.Doubtful,
            beats = n,
            artifactFraction = fraction,
            rrHeartRateBpm = rrRate,
            strapMeanBpm = strapMean,
            reasons = reasons,
        )
    }

    private fun firstBeatAfter(timeMillis: Long): Int {
        var lo = 0
        var hi = beats.size
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (beats[mid].timeMillis <= timeMillis) lo = mid + 1 else hi = mid
        }
        return lo
    }

    private fun strapMean(fromMillis: Long, toMillis: Long): Double? {
        var lo = readingStart
        var hi = readingCount
        while (lo < hi) {
            val mid = (lo + hi) ushr 1
            if (readingTimes[mid] <= fromMillis) lo = mid + 1 else hi = mid
        }
        var sum = 0.0
        var count = 0
        var i = lo
        while (i < readingCount && readingTimes[i] <= toMillis) {
            sum += readingBpm[i]
            count++
            i++
        }
        return if (count == 0) null else sum / count
    }

    private fun median(values: List<Int>): Double {
        val sorted = values.sorted()
        val n = sorted.size
        return if (n % 2 == 1) sorted[n / 2].toDouble() else (sorted[n / 2 - 1] + sorted[n / 2]) / 2.0
    }

    companion object {
        /**
         * A judge over stored [samples] (any order): the heart rate and the RR of the samples whose heart rate comes from a
         * strap connected to the app ([SampleHeartRateSource.Strap]); the other samples (console, unknown) carry no
         * trustworthy RR and are left out, so a window made of them is not judged. The times of the samples are the clock
         * of [StrapWindowJudge.judge].
         */
        fun fromSamples(samples: List<WorkoutSample>, config: StrapWindowConfig = StrapWindowConfig()): StrapWindowJudge {
            val judge = StrapWindowJudge(config)
            for (s in samples.sortedBy { it.timeMillis }) {
                if (s.heartRateSource != SampleHeartRateSource.Strap) continue
                s.heartRateBpm?.takeIf { it > 0 }?.let { judge.addReading(s.timeMillis, it) }
                judge.addRr(s.timeMillis, s.rrIntervalsMs)
            }
            return judge
        }
    }
}
