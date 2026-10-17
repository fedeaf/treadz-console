package com.treadz.console.analysis.workout

import com.treadz.console.analysis.fitness.Acsm
import kotlin.math.ceil

/** Heart-rate recovery after the peak (see [WorkoutAnalyzer], item 6). */
internal object RecoverySection {

    fun compute(tl: WorkoutTimeline, rules: AnalysisRules): Section<RecoveryAnalysis> {
        val w = rules.peakWindowSeconds
        val size = tl.size
        if (size < w) return Section.missing(AnalysisReason.NoRecoveryData)
        // Prefix sums of the heart rate over every second (0 = missing) and of its availability.
        val count = IntArray(size + 1)
        val sum = DoubleArray(size + 1)
        for (k in 0 until size) {
            count[k + 1] = count[k] + if (tl.hr[k] > 0) 1 else 0
            sum[k + 1] = sum[k] + tl.hr[k]
        }
        val minCount = ceil(rules.minHeartRateCoverage * w).toInt()
        var peak = -1.0
        var windowStart = -1
        for (a in 0..size - w) {
            val c = count[a + w] - count[a]
            if (c < minCount) continue
            val mean = (sum[a + w] - sum[a]) / c
            // >= : on a plateau the peak is its latest window; the end of the effort is searched from its start.
            if (mean >= peak) {
                peak = mean
                windowStart = a
            }
        }
        if (windowStart < 0) return Section.missing(AnalysisReason.LowHeartRateCoverage)

        // Mean net oxygen demand over [from, to] (covered seconds only), or null with fewer than minCovered of them.
        fun meanNet(from: Int, to: Int, minCovered: Int): Double? {
            var n = 0
            var s = 0.0
            for (k in maxOf(0, from)..minOf(size - 1, to)) {
                if (!tl.covered[k]) continue
                n++
                s += tl.vo2[k] - Acsm.REST_VO2
            }
            return if (n >= minCovered) s / n else null
        }

        // The end of the effort: the first second of the peak window, or shortly after it, where the load drops.
        val lastCandidate = minOf(size - 1, windowStart + w - 1 + rules.recoveryMaxDropDelaySeconds)
        var drop = -1
        var dataAfter = false
        for (k in windowStart + 1..lastCandidate) {
            if (!tl.covered[k]) continue
            val before = meanNet(k - 60, k - 1, 30) ?: continue
            if (before < rules.recoveryMinNetVo2) continue
            val limit = rules.recoveryLoadDropFraction * before
            if (tl.vo2[k] - Acsm.REST_VO2 > limit) continue
            val after = meanNet(k + 15, k + 60, 20) ?: continue
            dataAfter = true
            if (after <= limit) {
                drop = k
                break
            }
        }
        if (drop < 0) return Section.missing(if (dataAfter || hasDataAt(tl, windowStart + w + 60)) AnalysisReason.NoLoadDrop else AnalysisReason.NoRecoveryData)
        val t0 = drop - 1
        val peakFrom = maxOf(0, t0 - w + 1)
        val peakCount = count[t0 + 1] - count[peakFrom]
        if (peakCount < minCount) return Section.missing(AnalysisReason.LowHeartRateCoverage)
        val effortPeak = (sum[t0 + 1] - sum[peakFrom]) / peakCount

        fun heartRateAround(k: Int): Double? {
            if (k > size - 1) return null
            val from = maxOf(0, k - rules.recoverySmoothingSeconds)
            val to = minOf(size - 1, k + rules.recoverySmoothingSeconds)
            val c = count[to + 1] - count[from]
            return if (c >= rules.recoverySmoothingSeconds + 1) (sum[to + 1] - sum[from]) / c else null
        }

        val h60 = heartRateAround(t0 + 60) ?: return Section.missing(AnalysisReason.NoRecoveryData)
        val h120 = heartRateAround(t0 + 120)

        var covered = 0
        var moving = 0
        var vo2 = 0.0
        for (k in t0 + 1..minOf(size - 1, t0 + 120)) {
            if (!tl.covered[k]) continue
            covered++
            if (tl.moving[k]) moving++
            vo2 += tl.vo2[k]
        }
        return Section.of(
            RecoveryAnalysis(
                peakHeartRateBpm = effortPeak,
                peakEndMillis = tl.timeOf(drop),
                drop60Bpm = effortPeak - h60,
                drop120Bpm = h120?.let { effortPeak - it },
                context = if (moving * 2 >= covered) RecoveryContext.BeltMoving else RecoveryContext.BeltStopped,
                meanVo2MlKgMin = vo2 / covered,
            ),
        )
    }

    private fun hasDataAt(tl: WorkoutTimeline, k: Int): Boolean = k in 0 until tl.size && tl.covered[k]
}
