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
