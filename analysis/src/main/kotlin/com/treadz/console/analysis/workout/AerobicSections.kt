package com.treadz.console.analysis.workout

import com.treadz.console.analysis.fitness.Acsm
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.roundToInt

/** Efficiency-based section of [WorkoutAnalyzer]: the aerobic fitness index. */
internal object AerobicSections {

    fun fitnessIndex(tl: WorkoutTimeline, context: WorkoutAnalysisContext, rules: AnalysisRules): Section<FitnessIndexAnalysis> {
        val (rest, assumed) = WorkoutAnalyzer.restingFor(context)
        val reserve = (context.maxHeartRateBpm - rest).toDouble()
        val estimates = ArrayList<Double>()
        var found = 0
        var k = 0
        while (k < tl.size) {
            if (!tl.moving[k]) {
                k++
                continue
            }
            val s0 = k
            var e = k + 1
            while (e < tl.size && tl.moving[e] &&
                abs(tl.speed[e] - tl.speed[s0]) <= rules.steadySpeedToleranceKmh + 1e-9 &&
                abs(tl.incline[e] - tl.incline[s0]) <= rules.steadyInclineTolerancePercent + 1e-9
            ) e++
            val length = e - s0
            if (length >= rules.steadyMinSeconds) {
                found++
                val tail = s0 + length - (length * rules.steadyTailFraction).roundToInt()
                var n = 0
                var sumVo2 = 0.0
                var sumHr = 0.0
                for (i in tail until e) {
                    if (tl.hr[i] == 0) continue
                    n++
                    sumVo2 += tl.vo2[i]
                    sumHr += tl.hr[i]
                }
                if (n >= ceil(rules.minHeartRateCoverage * (e - tail)) && n > 0) {
                    val hrr = (sumHr / n - rest) / reserve
                    if (hrr in rules.fitnessMinReserveFraction..rules.fitnessMaxReserveFraction) {
                        estimates += Acsm.REST_VO2 + (sumVo2 / n - Acsm.REST_VO2) / hrr
                    }
                }
            }
            k = e
        }
        if (estimates.isEmpty()) return Section.missing(AnalysisReason.NoSteadyStretch)
        return Section.of(FitnessIndexAnalysis(Stats.median(estimates), estimates.size, found, estimates, assumed))
    }
}
