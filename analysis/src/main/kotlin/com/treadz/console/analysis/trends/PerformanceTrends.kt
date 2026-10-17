package com.treadz.console.analysis.trends

import com.treadz.console.analysis.thresholds.ThresholdQuality
import com.treadz.console.analysis.workout.RecoveryContext
import com.treadz.console.analysis.workout.Stats

/**
 * One session of the fitness-index trend.
 *
 * @property estimate The session's index (ml·kg⁻¹·min⁻¹).
 * @property rollingMedian Median of this and the previous sessions up to the window size.
 * @property sessionsInWindow Sessions in that median (fewer than the window at the start of the history).
 */
data class FitnessIndexPoint(val epochDay: Long, val estimate: Double, val rollingMedian: Double, val sessionsInWindow: Int)

/** @property slopePer30Days Change of the rolling median, ml·kg⁻¹·min⁻¹ **per 30 days**; `null` if not computable. */
data class FitnessIndexTrend(val points: List<FitnessIndexPoint>, val slopePer30Days: Double?)

/**
 * Trend of the aerobic fitness index of the workouts. It is a trend of a submaximal estimate: it follows changes, not
 * the absolute VO2max.
 */
object FitnessIndexTrends {

    /** Sessions in the rolling median: 5-7 are suitable; 5 by default (*assumption*). */
    const val DEFAULT_WINDOW = 5

    /** The slope needs this many points with at least [MIN_SESSIONS_FOR_SLOPE] sessions in their window. */
    const val MIN_SESSIONS_FOR_SLOPE = 3

    /**
     * The index of each session with a fitness index, in date order, its rolling median over the last [window] sessions
     * and the **slope of the rolling median per 30 days** (least squares over the points with ≥ 3 sessions in their
     * window, at least 3 of them and 14 days apart).
     */
    fun compute(analyses: List<DatedAnalysis>, window: Int = DEFAULT_WINDOW): FitnessIndexTrend {
        val sessions = analyses.sortedBy { it.epochDay }.mapNotNull { a -> a.analysis.fitnessIndex.value?.let { a.epochDay to it.vo2maxEstimate } }
        val size = window.coerceAtLeast(1)
        val points = sessions.mapIndexed { i, (day, estimate) ->
            val recent = sessions.subList(maxOf(0, i - size + 1), i + 1).map { it.second }
            FitnessIndexPoint(day, estimate, Stats.median(recent), recent.size)
        }
        val forSlope = points.filter { it.sessionsInWindow >= minOf(MIN_SESSIONS_FOR_SLOPE, size) }
        return FitnessIndexTrend(points, TrendMath.slopePer30Days(forSlope.map { it.epochDay }, forSlope.map { it.rollingMedian }))
    }
}

data class DecouplingPoint(val epochDay: Long, val decouplingPercent: Double, val analysedSeconds: Int)

/** @property slopePer30Days Change of the decoupling, percentage points **per 30 days**; `null` if not computable. */
data class DecouplingTrend(val points: List<DecouplingPoint>, val slopePer30Days: Double?)

/** Decoupling of the long steady zone 2 sessions (see `DecouplingAnalysis.longSteadyZone2`), the comparable ones. */
object DecouplingTrends {

    fun compute(analyses: List<DatedAnalysis>): DecouplingTrend {
        val points = analyses.sortedBy { it.epochDay }.mapNotNull { a ->
            a.analysis.decoupling.value?.takeIf { it.longSteadyZone2 }?.let { DecouplingPoint(a.epochDay, it.decouplingPercent, it.analysedSeconds) }
        }
        return DecouplingTrend(points, TrendMath.slopePer30Days(points.map { it.epochDay }, points.map { it.decouplingPercent }))
    }
}

/** One heart-rate recovery. */
data class RecoveryPoint(
    val epochDay: Long,
    val peakHeartRateBpm: Double,
    val drop60Bpm: Double?,
    val drop120Bpm: Double?,
    val meanVo2MlKgMin: Double,
)

/** Heart-rate recovery grouped by context: only recoveries of the same context are comparable. */
object RecoveryTrends {

    /** Both contexts are always present (possibly empty), each in date order. */
    fun byContext(analyses: List<DatedAnalysis>): Map<RecoveryContext, List<RecoveryPoint>> {
        val out = RecoveryContext.entries.associateWith { ArrayList<RecoveryPoint>() }
        for (a in analyses.sortedBy { it.epochDay }) {
            val r = a.analysis.recovery.value ?: continue
            out.getValue(r.context) += RecoveryPoint(a.epochDay, r.peakHeartRateBpm, r.drop60Bpm, r.drop120Bpm, r.meanVo2MlKgMin)
        }
        return out
    }
}

/**
 * A trailing mean of a measurement.
 *
 * @property mean Mean of the measurements in the [EpochDays] window ending on [epochDay].
 * @property count Measurements in it.
 */
data class TrailingMean(val epochDay: Long, val mean: Double, val count: Int)

/** Resting heart rate and RMSSD: informative trailing means (no model on top). */
object MeasurementTrends {

    const val DEFAULT_WINDOW_DAYS = 7

    /** For each day with a measurement, the mean of the measurements of that day and the previous [windowDays] - 1. */
    fun trailingMean(values: List<DatedValue>, windowDays: Int = DEFAULT_WINDOW_DAYS): List<TrailingMean> {
        val sorted = values.sortedBy { it.epochDay }
        return sorted.map { it.epochDay }.distinct().map { day ->
            val inWindow = sorted.filter { it.epochDay in (day - windowDays + 1)..day }
            TrailingMean(day, inWindow.map { it.value }.average(), inWindow.size)
        }
    }
}

/**
 * VO2max of a test in absolute terms.
 *
 * @property weightKg The weight used and [weightEpochDay] its date (the caller can tell how stale it is).
 * @property litresPerMinute VO2max · weight / 1000.
 */
data class AbsoluteVo2Point(
    val epochDay: Long,
    val vo2maxMlKgMin: Double,
    val weightKg: Double,
    val weightEpochDay: Long,
    val litresPerMinute: Double,
)

/** Absolute VO2 (L/min): separates an improvement in fitness from a change of weight. */
object AbsoluteVo2 {

    /**
     * For each test with a VO2max, the absolute VO2 with the weight **as of the test's date**: the latest weight on or
     * before it; if there is none before, the earliest one after (*assumption*, the weight date is in the result). Tests
     * are skipped when there is no weight at all.
     */
    fun compute(tests: List<TestPoint>, weights: List<DatedValue>): List<AbsoluteVo2Point> {
        val byDay = weights.filter { it.value > 0.0 }.sortedBy { it.epochDay }
        if (byDay.isEmpty()) return emptyList()
        return tests.sortedBy { it.epochDay }.mapNotNull { t ->
            val vo2max = t.vo2max ?: return@mapNotNull null
            val w = byDay.lastOrNull { it.epochDay <= t.epochDay } ?: byDay.first()
            AbsoluteVo2Point(t.epochDay, vo2max, w.value, w.epochDay, vo2max * w.value / 1000.0)
        }
    }
}

/** VO2max with the band of its standard error. */
data class Vo2maxTrendPoint(val epochDay: Long, val value: Double, val low: Double?, val high: Double?, val isLowerBound: Boolean)

/** The thresholds of a test and their quality. */
data class ThresholdTrendPoint(val epochDay: Long, val vt1Bpm: Double?, val vt2Bpm: Double?, val quality: ThresholdQuality?)

data class TestSeries(val vo2max: List<Vo2maxTrendPoint>, val thresholds: List<ThresholdTrendPoint>)

/** VO2max, VT1 and VT2 of the tests over time, with the SEE band and the quality. */
object TestTrends {

    fun series(tests: List<TestPoint>): TestSeries {
        val sorted = tests.sortedBy { it.epochDay }
        return TestSeries(
            vo2max = sorted.mapNotNull { t ->
                t.vo2max?.let { Vo2maxTrendPoint(t.epochDay, it, t.vo2maxSee?.let { see -> it - see }, t.vo2maxSee?.let { see -> it + see }, t.vo2maxIsLowerBound) }
            },
            thresholds = sorted.filter { it.vt1Bpm != null || it.vt2Bpm != null }.map { ThresholdTrendPoint(it.epochDay, it.vt1Bpm, it.vt2Bpm, it.thresholdQuality) },
        )
    }
}
