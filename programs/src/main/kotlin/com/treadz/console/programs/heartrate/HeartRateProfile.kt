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
 */
data class HeartRateProfile(val maxBpm: Int, val restingBpm: Int? = null) {
    /** A maximum outside 100-240 bpm cannot drive heart-rate control (the blocks then run at their start values). */
    val isUsable: Boolean get() = maxBpm in 100..240

    /** The resting heart rate when plausible (30-120 bpm and below the maximum), else `null`. */
    val usableRestingBpm: Int? get() = restingBpm?.takeIf { it in 30..120 && it < maxBpm }
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
