package com.treadz.console.analysis.workout

import com.treadz.console.analysis.fitness.Acsm
import com.treadz.console.analysis.samples.SampleHeartRateSource
import com.treadz.console.analysis.samples.WorkoutSample

/**
 * The samples of a workout resampled to a 1 Hz grid by sample-and-hold, as dense arrays indexed by the second `k` since
 * the first sample (tick `k` is the instant `startMillis + 1000·k`).
 *
 * Each sample holds its speed, incline and heart rate until the next sample, but at most
 * [AnalysisRules.maxHoldSeconds]: beyond that the time is a **gap**, not covered, and counts for nothing (the heart rate
 * of a sample with none is missing, never carried over from an earlier one). The last sample holds for the length of the
 * interval before it. The result does not depend on the sampling rate (1 Hz app workouts and 3 s imported ones give the
 * same thing for the same signal); the error is under one second per gap.
 *
 * A console or unknown-source heart rate repeated without change for longer than [AnalysisRules.maxFrozenHeartRateSeconds]
 * is a frozen reading and is dropped (missing) for its whole length (a strap connected to the app sends every beat).
 *
 * Belt *moving* = speed above 0. The oxygen demand of a covered second is the ACSM equation of its speed and incline
 * (walking or running by that second's speed); with the belt stopped it is the resting 3.5.
 */
internal class WorkoutTimeline(
    val startMillis: Long,
    val size: Int,
    val covered: BooleanArray,
    val moving: BooleanArray,
    val speed: DoubleArray,
    val incline: DoubleArray,
    /** Valid heart rate, 0 = none. */
    val hr: IntArray,
    val source: Array<SampleHeartRateSource?>,
    /** NaN where not covered. */
    val vo2: DoubleArray,
    val sampleCount: Int,
    /** Moving seconds whose heart rate was dropped as frozen. */
    val frozenHeartRateSeconds: Int,
    /** Why the timeline is empty, if it is. */
    val problem: AnalysisReason?,
) {
    val coveredSeconds: Int = covered.count { it }
    val movingSeconds: Int = moving.count { it }
    val heartRateSeconds: Int = (0 until size).count { moving[it] && hr[it] > 0 }

    /** Millisecond time of tick [k]. */
    fun timeOf(k: Int): Long = startMillis + 1000L * k

    /** Tick index containing [millis] (floor), unclamped. */
    fun tickOf(millis: Long): Long = Math.floorDiv(millis - startMillis, 1000L)

    companion object {
        private fun empty(count: Int, problem: AnalysisReason) = WorkoutTimeline(
            0L, 0, BooleanArray(0), BooleanArray(0), DoubleArray(0), DoubleArray(0), IntArray(0), arrayOfNulls(0), DoubleArray(0), count, 0, problem,
        )

        fun build(input: List<WorkoutSample>, rules: AnalysisRules): WorkoutTimeline {
            val samples = if (input.zipWithNext().all { (a, b) -> a.timeMillis <= b.timeMillis }) input else input.sortedBy { it.timeMillis }
            val n = samples.size
            if (n < 2) return empty(n, AnalysisReason.NoSamples)
            val hold = rules.maxHoldSeconds * 1000L
            val dur = LongArray(n)
            for (i in 0 until n - 1) dur[i] = minOf(samples[i + 1].timeMillis - samples[i].timeMillis, hold)
            dur[n - 1] = dur[n - 2]
            val t0 = samples[0].timeMillis
            val endMillis = samples[n - 1].timeMillis + dur[n - 1]
            if (endMillis - t0 > rules.maxSpanSeconds * 1000L) return empty(n, AnalysisReason.ImplausibleDuration)
            if (endMillis <= t0) return empty(n, AnalysisReason.NoSamples)
            val size = Math.floorDiv(endMillis - t0 + 999L, 1000L).toInt()
            val covered = BooleanArray(size)
            val moving = BooleanArray(size)
            val speed = DoubleArray(size)
            val incline = DoubleArray(size)
            val hr = IntArray(size)
            val source = arrayOfNulls<SampleHeartRateSource>(size)
            val vo2 = DoubleArray(size) { Double.NaN }
            for (i in 0 until n) {
                if (dur[i] <= 0L) continue
                val s = samples[i]
                val from = ceilSeconds(s.timeMillis - t0)
                val to = ceilSeconds(s.timeMillis + dur[i] - t0)
                val bpm = s.heartRateBpm?.takeIf { it in rules.minHeartRateBpm..rules.maxHeartRateBpm } ?: 0
                val isMoving = s.speedKmh > 0.0
                val demand = if (isMoving) Acsm.vo2(s.speedKmh, s.inclinePercent) else Acsm.REST_VO2
                for (k in from until minOf(to, size)) {
                    covered[k] = true
                    moving[k] = isMoving
                    speed[k] = s.speedKmh
                    incline[k] = s.inclinePercent
                    hr[k] = bpm
                    source[k] = if (bpm > 0) s.heartRateSource ?: SampleHeartRateSource.Unknown else null
                    vo2[k] = demand
                }
            }
            val frozen = dropFrozenHeartRate(hr, source, covered, moving, rules.maxFrozenHeartRateSeconds)
            return WorkoutTimeline(t0, size, covered, moving, speed, incline, hr, source, vo2, n, frozen, null)
        }

        /**
         * Clears every run of covered seconds with the same non-strap heart rate longer than [maxSeconds]; returns how many of the
         * cleared seconds had the belt moving.
         */
        private fun dropFrozenHeartRate(
            hr: IntArray,
            source: Array<SampleHeartRateSource?>,
            covered: BooleanArray,
            moving: BooleanArray,
            maxSeconds: Int,
        ): Int {
            var dropped = 0
            var k = 0
            while (k < hr.size) {
                if (hr[k] == 0 || !covered[k] || source[k] == SampleHeartRateSource.Strap) {
                    k++
                    continue
                }
                var e = k + 1
                while (e < hr.size && covered[e] && hr[e] == hr[k] && source[e] != SampleHeartRateSource.Strap) e++
                if (e - k > maxSeconds) {
                    for (i in k until e) {
                        if (moving[i]) dropped++
                        hr[i] = 0
                        source[i] = null
                    }
                }
                k = e
            }
            return dropped
        }

        private fun ceilSeconds(millis: Long): Int = Math.floorDiv(millis + 999L, 1000L).toInt()
    }
}
