package com.treadz.console.analysis.trends

import com.treadz.console.analysis.thresholds.ThresholdQuality
import com.treadz.console.analysis.workout.Stats
import com.treadz.console.analysis.workout.WorkoutAnalysis
import java.util.Collections

/**
 * A workout analysis with its date as an **epoch day** (days since 1970-01-01). The caller converts the workout's date
 * to a day in the user's time zone, so that "a day" means the same everywhere in the trends.
 */
data class DatedAnalysis(val epochDay: Long, val analysis: WorkoutAnalysis)

/** A measurement of one value on an epoch day (resting heart rate, RMSSD, weight...). */
data class DatedValue(val epochDay: Long, val value: Double)

/**
 * The result of a fitness test, as the trends see it.
 *
 * @property vo2maxSee Standard error of the estimate of [vo2max] (ml·kg⁻¹·min⁻¹), for the band.
 * @property vo2maxIsLowerBound The test was not maximal: [vo2max] is a lower bound.
 * @property vt1Bpm Ventilatory thresholds from the test (bpm), [thresholdQuality] their quality.
 */
data class TestPoint(
    val epochDay: Long,
    val vo2max: Double? = null,
    val vo2maxSee: Double? = null,
    val vo2maxIsLowerBound: Boolean = false,
    val vt1Bpm: Double? = null,
    val vt2Bpm: Double? = null,
    val thresholdQuality: ThresholdQuality? = null,
)

/** Calendar helpers on epoch days. */
object EpochDays {

    /** The Monday of the week of [epochDay] (weeks run Monday to Sunday; day 0, 1970-01-01, was a Thursday). */
    fun weekStart(epochDay: Long): Long = epochDay - Math.floorMod(epochDay + 3, 7L)
}

internal object TrendMath {

    /**
     * Least-squares slope of [values] against their days, per 30 days; `null` with fewer than [minPoints] points or a span
     * under [minSpanDays].
     */
    fun slopePer30Days(days: List<Long>, values: List<Double>, minPoints: Int = 3, minSpanDays: Long = 14): Double? {
        if (days.size < minPoints || days.isEmpty()) return null
        if (Collections.max(days) - Collections.min(days) < minSpanDays) return null
        val slope = Stats.slope(days.map { it.toDouble() }, values) ?: return null
        return slope * 30.0
    }
}
