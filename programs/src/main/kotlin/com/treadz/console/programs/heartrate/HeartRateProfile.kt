package com.treadz.console.programs.heartrate

import com.treadz.console.analysis.heartrate.HeartRateBands
import com.treadz.console.programs.model.HeartRateBasis
import com.treadz.console.programs.model.HeartRateZone

/**
 * The user's heart-rate references for heart-rate-guided blocks.
 *
 * @property maxBpm The effective maximum heart rate (manual > measured > estimated, see
 *   `com.treadz.console.programs.fitness.HeartRateMaxPolicy`).
 * @property restingBpm The measured resting heart rate, when the user has one. Without it, zones written as a
 *   percentage of the reserve are computed as a percentage of the maximum (lower values, the safe side).
 * @property vt1Bpm The heart rate at the first ventilatory threshold (VT1) of the user's zone model: measured (the
 *   profile thresholds) or, without a measured one, the typical one of the user's level (the top of zone 2). The
 *   real-time α1 brake uses it to know which blocks are easy enough (zone ceiling at or below VT1); without it, the
 *   brake falls back to a fixed share of the reserve (see `Alpha1BrakeConfig`).
 */
data class HeartRateProfile(val maxBpm: Int, val restingBpm: Int? = null, val vt1Bpm: Int? = null) {
    /** A maximum outside 100-240 bpm cannot drive heart-rate control (the blocks then run at their start values). */
    val isUsable: Boolean get() = maxBpm in 100..240

    /** The resting heart rate when plausible (30-120 bpm and below the maximum), else `null`. */
    val usableRestingBpm: Int? get() = restingBpm?.takeIf { it in 30..120 && it < maxBpm }

    /** The VT1 heart rate when plausible (60 bpm up to below the maximum), else `null`. */
    val usableVt1Bpm: Int? get() = vt1Bpm?.takeIf { it in 60 until maxBpm }
}

/** A zone in beats per minute. [basis] is what was really used (reserve falls back to maximum without a resting rate). */
data class HeartRateZoneBpm(val lowBpm: Int, val highBpm: Int, val basis: HeartRateBasis)

object HeartRateZones {

    /** The five standard zones, in % of the maximum heart rate: 50-60, 60-70, 70-80, 80-90, 90-100. */
    val STANDARD: List<HeartRateZone> = listOf(50, 60, 70, 80, 90).map { HeartRateZone(HeartRateBasis.MaxHeartRate, it, it + 10) }

    /** The zone in bpm for [profile]: % of the maximum, or of the reserve (Karvonen) when asked and possible. */
    fun toBpm(zone: HeartRateZone, profile: HeartRateProfile): HeartRateZoneBpm {
        val range = HeartRateBands.toBpm(
            maxBpm = profile.maxBpm,
            restingBpm = profile.usableRestingBpm,
            ofReserve = zone.basis == HeartRateBasis.HeartRateReserve,
            lowPercent = zone.lowPercent,
            highPercent = zone.highPercent,
        )
        return HeartRateZoneBpm(
            range.lowBpm,
            range.highBpm,
            if (range.usedReserve) HeartRateBasis.HeartRateReserve else HeartRateBasis.MaxHeartRate,
        )
    }
}
