package com.treadz.console.fakes

import com.treadz.console.analysis.thresholds.ThresholdMetrics
import com.treadz.console.analysis.thresholds.ThresholdPoint
import com.treadz.console.analysis.thresholds.ThresholdQuality
import com.treadz.console.analysis.thresholds.ThresholdReason
import com.treadz.console.analysis.thresholds.ThresholdResult

/** Synthetic threshold results (no real data) for the tests and the screenshots. */
object ThresholdFixtures {

    private val metrics = ThresholdMetrics(
        stretchWindows = 9, validWindows = 24, totalWindows = 31, rSquared = 0.78, meanArtifactPercent = 1.9,
        slopePerBpm = -0.021, interceptAlpha1 = 3.6,
    )

    /** VT1 and VT2 found, at the heart rates given, with the load and VO2 of a walking ramp. */
    fun found(
        quality: ThresholdQuality = ThresholdQuality.Medium,
        vt1Bpm: Double = 132.4,
        vt2Bpm: Double? = 149.1,
        reservePercent: Boolean = true,
    ) = ThresholdResult(
        algorithmVersion = 1,
        vt1 = ThresholdPoint(vt1Bpm, speedKmh = 5.5, inclinePercent = 9.0, vo2MlKgMin = 27.6, percentVo2max = 62.4, percentHeartRateReserve = if (reservePercent) 61.0 else null),
        vt2 = vt2Bpm?.let { ThresholdPoint(it, speedKmh = 5.5, inclinePercent = 14.0, vo2MlKgMin = 36.2, percentVo2max = 81.9, percentHeartRateReserve = if (reservePercent) 82.0 else null) },
        quality = quality,
        metrics = metrics,
    )

    /** No thresholds, for [reason]. */
    fun none(reason: ThresholdReason) = ThresholdResult.none(reason)
}
