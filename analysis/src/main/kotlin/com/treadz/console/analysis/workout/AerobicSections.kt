package com.treadz.console.analysis.workout

import com.treadz.console.analysis.fitness.Acsm
import com.treadz.console.analysis.zones.ZoneSet
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.roundToInt

/** Efficiency-based sections of [WorkoutAnalyzer]: the decoupling and the aerobic fitness index. */
internal object AerobicSections {

    fun decoupling(tl: WorkoutTimeline, zones: ZoneSet?, rules: AnalysisRules): Section<DecouplingAnalysis> {
        val first = tl.moving.indexOfFirst { it }
        val start = first + rules.warmUpSeconds
        // Typical net demand of the session after the warm-up: the median is not moved by a short cool-down.
        val nets = (start until tl.size).filter { tl.moving[it] }.map { tl.vo2[it] - Acsm.REST_VO2 }
        if (nets.isEmpty()) return Section.missing(AnalysisReason.TooShort)
        val typical = Stats.median(nets)
        var blockEnd = -1
        for (k in tl.size - 1 downTo first) {
            if (tl.moving[k] && tl.vo2[k] - Acsm.REST_VO2 >= rules.coolDownFraction * typical) {
                blockEnd = k
                break
            }
        }
        val span = blockEnd - start + 1
        if (blockEnd < 0 || span < rules.minDecouplingSeconds) return Section.missing(AnalysisReason.TooShort)
        val ticks = (start..blockEnd).filter { tl.moving[it] && tl.hr[it] > 0 }
        if (ticks.size < rules.minBlockContinuity * span - 1e-9) return Section.missing(AnalysisReason.Interrupted)
        val half = ticks.size / 2
        val ef1 = ef(tl, ticks.subList(0, half))
        val ef2 = ef(tl, ticks.subList(ticks.size - half, ticks.size))
        val zone12 = zones?.let { z -> ticks.count { WorkoutAnalyzer.zoneIndex(z, tl.hr[it]) <= 1 }.toDouble() / ticks.size }
        return Section.of(
            DecouplingAnalysis(
                mainBlockSeconds = blockEnd - first + 1,
                analysedSeconds = span,
                ef1 = ef1,
                ef2 = ef2,
                decouplingPercent = (ef1 - ef2) / ef1 * 100.0,
                zone12Fraction = zone12,
                longSteadyZone2 = zone12 != null && zone12 >= rules.zone2MinFraction,
            ),
        )
    }

    private fun ef(tl: WorkoutTimeline, ticks: List<Int>): Double =
        ticks.sumOf { tl.vo2[it] } / ticks.size / (ticks.sumOf { tl.hr[it].toDouble() } / ticks.size)

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
