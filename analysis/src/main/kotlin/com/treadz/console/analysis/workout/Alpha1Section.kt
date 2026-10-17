package com.treadz.console.analysis.workout

import com.treadz.console.analysis.rr.CleanedRr
import com.treadz.console.analysis.samples.RrInterval
import com.treadz.console.analysis.thresholds.Alpha1Windows
import com.treadz.console.analysis.thresholds.ThresholdRules

/** α1 over the whole session from the strap RR (see [WorkoutAnalyzer], item 7). */
internal object Alpha1Section {

    fun compute(tl: WorkoutTimeline, rr: List<RrInterval>, cleaned: CleanedRr?, rules: AnalysisRules): Section<SessionAlpha1> {
        if (rr.isEmpty() || cleaned == null) return Section.missing(AnalysisReason.NoRrIntervals)
        val windowRules = ThresholdRules()
        val times = LongArray(rr.size) { rr[it].timeMillis }
        val windows = Alpha1Windows.compute(times, cleaned, emptyList(), times.first(), times.last(), windowRules)
        val movingPrefix = IntArray(tl.size + 1)
        for (k in 0 until tl.size) movingPrefix[k + 1] = movingPrefix[k] + if (tl.moving[k]) 1 else 0
        fun movingFraction(startMillis: Long, endMillis: Long): Double {
            val a = tl.tickOf(startMillis).coerceIn(0L, tl.size.toLong()).toInt()
            val b = tl.tickOf(endMillis).coerceIn(0L, tl.size.toLong()).toInt()
            val seconds = (endMillis - startMillis) / 1000.0
            return if (b <= a || seconds <= 0.0) 0.0 else minOf(1.0, (movingPrefix[b] - movingPrefix[a]) / seconds)
        }
        val counted = windows.filter { movingFraction(it.startMillis, it.endMillis) >= rules.alpha1MinMovingFraction - 1e-9 }
        val valid = counted.filter { it.valid }
        if (valid.isEmpty()) return Section.missing(AnalysisReason.NoValidWindows)
        val above = valid.count { checkNotNull(it.alpha1) >= rules.alpha1Cut }
        return Section.of(
            SessionAlpha1(
                totalWindows = counted.size,
                validWindows = valid.size,
                secondsAtOrAbove = above * windowRules.stepSeconds,
                secondsBelow = (valid.size - above) * windowRules.stepSeconds,
                meanAlpha1 = valid.map { checkNotNull(it.alpha1) }.average(),
            ),
        )
    }
}
