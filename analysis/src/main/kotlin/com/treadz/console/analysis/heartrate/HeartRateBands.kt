package com.treadz.console.analysis.heartrate

import kotlin.math.roundToInt

/**
 * A heart-rate range in beats per minute. [usedReserve] is what was really used: a range asked as a percentage of the
 * heart-rate reserve falls back to a percentage of the maximum when there is no usable resting heart rate.
 */
data class BpmRange(val lowBpm: Int, val highBpm: Int, val usedReserve: Boolean)

/** Conversion of a percentage band of the maximum heart rate or of the heart-rate reserve (Karvonen) to bpm. */
object HeartRateBands {

    /**
     * Resting heart rate assumed, as a fraction of the maximum, to turn a % of the reserve into a % of the maximum when
     * the resting heart rate is unknown: a third (60 bpm for a maximum of 180). See [reserveToMaxPercent].
     */
    const val ASSUMED_RESTING_FRACTION = 1.0 / 3.0

    /**
     * The % of the maximum equivalent to [reservePercent] % of the reserve for a resting heart rate of
     * [ASSUMED_RESTING_FRACTION] of the maximum, rounded (60 % of the reserve → 73 % of the maximum; 70 → 80; 80 → 87).
     */
    fun reserveToMaxPercent(reservePercent: Int): Int =
        ((ASSUMED_RESTING_FRACTION + reservePercent / 100.0 * (1.0 - ASSUMED_RESTING_FRACTION)) * 100.0).roundToInt()

    /**
     * The heart rate at [reservePercent] % of the reserve: Karvonen with [restingBpm], or without it (`null`) the
     * equivalent % of the maximum ([reserveToMaxPercent]). Rounded to the nearest bpm.
     */
    fun reserveBpm(maxBpm: Int, restingBpm: Int?, reservePercent: Int): Int =
        if (restingBpm != null) {
            (restingBpm + (maxBpm - restingBpm) * reservePercent / 100.0).roundToInt()
        } else {
            (maxBpm * reserveToMaxPercent(reservePercent) / 100.0).roundToInt()
        }

    /**
     * [bpm] as the nearest integer % of the reserve (with [restingBpm]) or of the maximum (without it): the inverse of
     * [toBpm], to express a zone given in bpm in a program, which stores percentages.
     */
    fun percentOf(bpm: Int, maxBpm: Int, restingBpm: Int?): Int =
        if (restingBpm != null && restingBpm < maxBpm) {
            ((bpm - restingBpm) * 100.0 / (maxBpm - restingBpm)).roundToInt()
        } else {
            (bpm * 100.0 / maxBpm).roundToInt()
        }

    /**
     * The range between [lowPercent] and [highPercent] for a person with a maximum of [maxBpm]: that percentage of the
     * maximum, or, when [ofReserve] is asked and [restingBpm] is not `null`, of the reserve (Karvonen,
     * `resting + reserve · percent`, with reserve = maximum − resting). [restingBpm] must already be a usable value
     * (plausible and below the maximum); `null` means there is none. Bounds are rounded to the nearest bpm.
     */
    fun toBpm(maxBpm: Int, restingBpm: Int?, ofReserve: Boolean, lowPercent: Int, highPercent: Int): BpmRange {
        val rest = restingBpm
        return if (ofReserve && rest != null) {
            val reserve = maxBpm - rest
            BpmRange(
                (rest + reserve * lowPercent / 100.0).roundToInt(),
                (rest + reserve * highPercent / 100.0).roundToInt(),
                usedReserve = true,
            )
        } else {
            BpmRange(
                (maxBpm * lowPercent / 100.0).roundToInt(),
                (maxBpm * highPercent / 100.0).roundToInt(),
                usedReserve = false,
            )
        }
    }
}
