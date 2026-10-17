package com.treadz.console.analysis.samples

import com.treadz.console.analysis.fitness.Acsm
import kotlin.math.max
import kotlin.math.min

/**
 * The treadmill load over a time span: time-weighted means of the speed, the incline and the oxygen demand.
 *
 * @property coveredMillis How much of the span the samples covered.
 * @property running Whether the ACSM running equation was used (mean speed above
 *   [Acsm.RUNNING_SPEED_THRESHOLD_KMH]).
 * @property vo2MlKgMin Mean oxygen demand with that equation, ml·kg⁻¹·min⁻¹.
 */
data class MeanLoad(
    val speedKmh: Double,
    val inclinePercent: Double,
    val running: Boolean,
    val vo2MlKgMin: Double,
    val coveredMillis: Long,
)

/** Time-weighted load of a span of samples. */
object LoadAverage {

    /**
     * Mean load of [samples] (in time order) over (fromMillis, toMillis]. Each sample holds its speed and incline until
     * the next sample (sample-and-hold), so the result does not depend on the sampling rate; the last sample holds
     * until [toMillis]; the span before the first sample is not covered. The equation (walking or running) is chosen
     * by the mean speed of the span, and the mean oxygen demand is the time-weighted mean of that equation over the
     * span (both equations are linear in S and S·G, so it is exact for a changing load).
     *
     * Returns `null` when the samples do not cover any of the span.
     */
    fun over(samples: List<WorkoutSample>, fromMillis: Long, toMillis: Long): MeanLoad? {
        if (toMillis <= fromMillis || samples.isEmpty()) return null
        var covered = 0L
        var sumS = 0.0
        var sumG = 0.0
        var sumSG = 0.0
        for (i in samples.indices) {
            val s = samples[i]
            val start = max(s.timeMillis, fromMillis)
            val end = min(if (i + 1 < samples.size) samples[i + 1].timeMillis else toMillis, toMillis)
            if (end <= start) continue
            val dt = (end - start).toDouble()
            covered += end - start
            sumS += s.speedKmh * dt
            sumG += s.inclinePercent * dt
            sumSG += s.speedKmh * s.inclinePercent * dt
        }
        if (covered <= 0L) return null
        val c = covered.toDouble()
        val speed = sumS / c
        val incline = sumG / c
        val running = Acsm.isRunning(speed)
        // Linear in S and S·G: the mean of the equation is the equation of the means of S and S·G.
        val mPerMin = 1000.0 / 60.0
        val meanS = speed * mPerMin
        val meanSG = sumSG / c * mPerMin / 100.0
        val vo2 = if (running) Acsm.REST_VO2 + 0.2 * meanS + 0.9 * meanSG else Acsm.REST_VO2 + 0.1 * meanS + 1.8 * meanSG
        return MeanLoad(speed, incline, running, vo2, covered)
    }
}
