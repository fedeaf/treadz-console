package com.treadz.console.performance

import com.treadz.console.analysis.thresholds.ThresholdQuality
import com.treadz.console.analysis.trends.AbsoluteVo2
import com.treadz.console.analysis.trends.AbsoluteVo2Point
import com.treadz.console.analysis.trends.DatedValue
import com.treadz.console.analysis.trends.EpochDays
import com.treadz.console.analysis.trends.FitnessIndexTrend
import com.treadz.console.analysis.trends.FitnessIndexTrends
import com.treadz.console.analysis.trends.LoadDay
import com.treadz.console.analysis.trends.LoadTrends
import com.treadz.console.analysis.trends.LoadWeek
import com.treadz.console.analysis.trends.MeasurementTrends
import com.treadz.console.analysis.trends.RecoveryPoint
import com.treadz.console.analysis.trends.RecoveryTrends
import com.treadz.console.analysis.trends.TestSeries
import com.treadz.console.analysis.trends.TestTrends
import com.treadz.console.analysis.trends.TrailingMean
import com.treadz.console.analysis.trends.ZoneDistributionTrends
import com.treadz.console.analysis.trends.ZoneWeek
import com.treadz.console.analysis.workout.RecoveryContext
import com.treadz.console.userdata.DatedWorkoutAnalysis
import com.treadz.console.userdata.PerformanceInputs

/**
 * Everything the «Performance» screen draws, computed from the stored workout analyses and the other inputs of one user by
 * the pure trend functions of `:analysis`. The lists are in date order and may be empty: the screen shows a short
 * passive line for each card without data.
 *
 * The load and the zone distribution show a window (the last [Windows.LOAD_DAYS] days of the fitness-fatigue model, the last
 * [Windows.WEEKS] weeks of weekly bars): a choice of the screen, the model itself always starts at the first workout.
 *
 * @property analysedWorkouts Workouts with an analysis; [workoutsWithTrimp] those among them with a heart rate (a TRIMP).
 * @property loadDays The fitness-fatigue series of the last [Windows.LOAD_DAYS] days up to today.
 * @property loadWeeks Weekly TRIMP of the last [Windows.WEEKS] calendar weeks, the current (partial) one included.
 */
data class PerformanceTrends(
    val todayEpochDay: Long,
    val tests: TestSeries,
    val fitnessIndex: FitnessIndexTrend,
    val loadDays: List<LoadDay>,
    val loadWeeks: List<LoadWeek>,
    val zoneWeeks: List<ZoneWeek>,
    val recovery: Map<RecoveryContext, List<RecoveryPoint>>,
    val restingMean: List<TrailingMean>,
    val rmssdMean: List<TrailingMean>,
    val absoluteVo2: List<AbsoluteVo2Point>,
    val weights: List<DatedValue>,
    val analysedWorkouts: Int,
    val workoutsWithTrimp: Int,
) {
    /** TRIMP of the days after [afterDay] up to [untilDay] (both inclusive of the later one). */
    private fun trimpBetween(afterDay: Long, untilDay: Long): Double = loadDays.filter { it.epochDay in (afterDay + 1)..untilDay }.sumOf { it.trimp }

    /** TRIMP of the last 7 days (today included). */
    val trimpLast7Days: Double get() = trimpBetween(todayEpochDay - 7, todayEpochDay)

    /** TRIMP of the 7 days before those. */
    val trimpPrevious7Days: Double get() = trimpBetween(todayEpochDay - 14, todayEpochDay - 7)

    /** Workouts of the last 7 days that have no heart rate, so no TRIMP (they count as 0). */
    val workoutsWithoutTrimpLast7Days: Int
        get() = loadDays.filter { it.epochDay in (todayEpochDay - 6)..todayEpochDay }.sumOf { it.workoutsWithoutTrimp }

    /** Workouts of the weeks shown without a TRIMP. */
    val workoutsWithoutTrimpShown: Int get() = loadWeeks.sumOf { it.workoutsWithoutTrimp }

    val hasLoad: Boolean get() = workoutsWithTrimp > 0

    /** VO2max of the latest test minus the one before it, lower bounds left out; `null` with fewer than two such tests. */
    val vo2maxChange: Double?
        get() = tests.vo2max.filter { !it.isLowerBound }.takeLast(2).takeIf { it.size == 2 }?.let { (before, last) -> last.value - before.value }

    /**
     * VT1 of the latest test with one minus the one before it (bpm), counting only the tests whose thresholds reach the
     * profile (not low quality); `null` with fewer than two.
     */
    val vt1Change: Double?
        get() = tests.thresholds.filter { it.quality != ThresholdQuality.Low }.mapNotNull { it.vt1Bpm }.takeLast(2).takeIf { it.size == 2 }?.let { (before, last) -> last - before }

    /** Absolute VO2 (L/min) of the latest test minus the one before it; `null` with fewer than two. */
    val absoluteVo2Change: Double?
        get() = absoluteVo2.takeLast(2).takeIf { it.size == 2 }?.let { (before, last) -> last.litresPerMinute - before.litresPerMinute }

    /** The most recent recovery of any context: the one the card headlines. */
    val latestRecovery: Pair<RecoveryContext, RecoveryPoint>?
        get() = recovery.flatMap { (context, points) -> points.map { context to it } }.maxByOrNull { it.second.epochDay }

    /** The recovery before [latestRecovery] in the same context, to compare only equal cases. */
    val previousRecoverySameContext: RecoveryPoint?
        get() = latestRecovery?.let { (context, _) -> recovery[context].orEmpty().dropLast(1).lastOrNull() }

    /** Last 7-day mean of the resting heart rate and its difference with the mean a week earlier. */
    val restingChange: Double? get() = weekChange(restingMean)

    val rmssdChange: Double? get() = weekChange(rmssdMean)

    companion object {
        /** The mean at the latest day minus the mean at the latest day at least a week earlier; `null` without one. */
        fun weekChange(means: List<TrailingMean>): Double? {
            val last = means.lastOrNull() ?: return null
            val before = means.lastOrNull { it.epochDay <= last.epochDay - 7 } ?: return null
            return last.mean - before.mean
        }
    }
}

object Windows {
    /** Days of the fitness-fatigue chart. */
    const val LOAD_DAYS = 180

    /** Weeks of the weekly bars. */
    const val WEEKS = 12
}

/** Wires the stored analyses and inputs to the trend functions of `:analysis`. Pure: tested without a view model. */
object PerformanceTrendsCalculator {

    fun compute(analyses: List<DatedWorkoutAnalysis>, inputs: PerformanceInputs, todayEpochDay: Long): PerformanceTrends {
        val dated = analyses.map { it.dated }
        val firstShownWeek = EpochDays.weekStart(todayEpochDay) - 7L * (Windows.WEEKS - 1)
        val firstLoadDay = todayEpochDay - Windows.LOAD_DAYS + 1
        // One run of the model for both windows.
        val days = LoadTrends.daily(dated, todayEpochDay, fromEpochDay = minOf(firstLoadDay, firstShownWeek))
        return PerformanceTrends(
            todayEpochDay = todayEpochDay,
            tests = TestTrends.series(inputs.tests),
            fitnessIndex = FitnessIndexTrends.compute(dated),
            loadDays = days.filter { it.epochDay >= firstLoadDay },
            loadWeeks = LoadTrends.weekly(days.filter { it.epochDay >= firstShownWeek }),
            zoneWeeks = ZoneDistributionTrends.weekly(dated, todayEpochDay).filter { it.weekStartEpochDay >= firstShownWeek },
            recovery = RecoveryTrends.byContext(dated),
            restingMean = MeasurementTrends.trailingMean(inputs.restingHeartRate),
            rmssdMean = MeasurementTrends.trailingMean(inputs.rmssd),
            absoluteVo2 = AbsoluteVo2.compute(inputs.tests, inputs.weights),
            weights = inputs.weights,
            analysedWorkouts = analyses.size,
            workoutsWithTrimp = analyses.count { it.analysis.trimp.value != null },
        )
    }
}
