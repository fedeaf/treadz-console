package com.treadz.console.analysis.trends

import kotlin.math.exp

/**
 * One day of the training load.
 *
 * @property trimp Sum of the TRIMP of the day's workouts that have one; 0 for a day without workouts or without heart rate.
 * @property workouts Workouts of the day; [workoutsWithoutTrimp] those without a TRIMP (no heart rate): a day with
 *   [hasMissingTrimp] has a load that is at least what [trimp] says.
 * @property fitness Banister fitness (chronic load): exponential mean of the daily TRIMP with a 42-day time constant.
 * @property fatigue Banister fatigue (acute load): the same with 7 days.
 * @property balance [fitness] − [fatigue] of the same day.
 */
data class LoadDay(
    val epochDay: Long,
    val trimp: Double,
    val workouts: Int,
    val workoutsWithoutTrimp: Int,
    val fitness: Double,
    val fatigue: Double,
    val balance: Double,
) {
    val hasMissingTrimp: Boolean get() = workoutsWithoutTrimp > 0
}

/**
 * One calendar week (Monday to Sunday) of the load.
 *
 * @property daysInSeries Days of the week inside the series (fewer than 7 for the first and the last week).
 */
data class LoadWeek(
    val weekStartEpochDay: Long,
    val trimp: Double,
    val workouts: Int,
    val workoutsWithoutTrimp: Int,
    val daysInSeries: Int,
)

/**
 * Training load from the TRIMP of the workouts: daily and weekly TRIMP and the fitness-fatigue model.
 *
 * Model: Banister EW, Calvert TW, Savage MV, Bach TM. A systems model of training for athletic performance. Aust J Sports
 * Med. 1975;7:57-61, in its common exponentially weighted form (the chronic and acute training loads of Coggan): with
 * `d` the daily TRIMP, `L(t) = L(t−1) · e^(−1/τ) + d(t) · (1 − e^(−1/τ))`, with τ = [FITNESS_DAYS] (42) for the fitness
 * and [FATIGUE_DAYS] (7) for the fatigue. *Assumptions*: both start at 0 the day before the first workout (so the first
 * weeks underestimate the fitness: it is a warm-up of the model) and the balance is the difference of the same day. Days
 * without workouts count as 0 TRIMP.
 */
object LoadTrends {

    const val FITNESS_DAYS = 42
    const val FATIGUE_DAYS = 7

    /**
     * The daily series from the day of the first workout (or [fromEpochDay], if later: the model still starts at the first
     * workout, only the output is cut) up to [untilEpochDay], one entry per day. Workouts after [untilEpochDay] are
     * ignored. Empty without workouts up to [untilEpochDay].
     */
    fun daily(analyses: List<DatedAnalysis>, untilEpochDay: Long, fromEpochDay: Long? = null): List<LoadDay> {
        val used = analyses.filter { it.epochDay <= untilEpochDay }
        if (used.isEmpty()) return emptyList()
        val first = used.minOf { it.epochDay }
        val byDay = used.groupBy { it.epochDay }
        val keepFitness = exp(-1.0 / FITNESS_DAYS)
        val keepFatigue = exp(-1.0 / FATIGUE_DAYS)
        var fitness = 0.0
        var fatigue = 0.0
        val out = ArrayList<LoadDay>()
        for (day in first..untilEpochDay) {
            val sessions = byDay[day].orEmpty()
            val trimps = sessions.mapNotNull { it.analysis.trimp.value?.trimp }
            val d = trimps.sum()
            fitness = fitness * keepFitness + d * (1.0 - keepFitness)
            fatigue = fatigue * keepFatigue + d * (1.0 - keepFatigue)
            if (fromEpochDay == null || day >= fromEpochDay) {
                out += LoadDay(day, d, sessions.size, sessions.size - trimps.size, fitness, fatigue, fitness - fatigue)
            }
        }
        return out
    }

    /** The TRIMP of each calendar week of a [daily] series (the weeks that have days in it, in order). */
    fun weekly(days: List<LoadDay>): List<LoadWeek> =
        days.groupBy { EpochDays.weekStart(it.epochDay) }.toSortedMap().map { (start, list) ->
            LoadWeek(start, list.sumOf { it.trimp }, list.sumOf { it.workouts }, list.sumOf { it.workoutsWithoutTrimp }, list.size)
        }
}

/**
 * Seconds in each zone in one week.
 *
 * @property secondsByZone Zone 1 first. Zones are those in force on each workout's day.
 * @property sessionsWithZones Workouts counted; [sessionsWithoutZones] workouts of the week with no time in zones.
 */
data class ZoneWeek(
    val weekStartEpochDay: Long,
    val secondsByZone: List<Int>,
    val sessionsWithZones: Int,
    val sessionsWithoutZones: Int,
) {
    val totalSeconds: Int get() = secondsByZone.sum()

    /** Share of each zone in the week's counted time; all 0 for a week without any. */
    val fractions: List<Double> get() = secondsByZone.map { if (totalSeconds > 0) it.toDouble() / totalSeconds else 0.0 }
}

/** Weekly distribution of the time in zones. */
object ZoneDistributionTrends {

    /**
     * One entry per calendar week from that of the first workout to that of [untilEpochDay] (weeks without workouts
     * included, all zeros). Zones are added by number; the workouts' own zone boundaries may differ.
     */
    fun weekly(analyses: List<DatedAnalysis>, untilEpochDay: Long): List<ZoneWeek> {
        val used = analyses.filter { it.epochDay <= untilEpochDay }
        if (used.isEmpty()) return emptyList()
        val zoneCount = maxOf(5, used.maxOf { it.analysis.zoneTime.value?.secondsInZone?.size ?: 0 })
        val byWeek = used.groupBy { EpochDays.weekStart(it.epochDay) }
        val firstWeek = EpochDays.weekStart(used.minOf { it.epochDay })
        val lastWeek = EpochDays.weekStart(untilEpochDay)
        val out = ArrayList<ZoneWeek>()
        var week = firstWeek
        while (week <= lastWeek) {
            val seconds = IntArray(zoneCount)
            var with = 0
            var without = 0
            for (a in byWeek[week].orEmpty()) {
                val z = a.analysis.zoneTime.value
                if (z == null) {
                    without++
                } else {
                    with++
                    z.secondsInZone.forEachIndexed { i, s -> seconds[i] += s }
                }
            }
            out += ZoneWeek(week, seconds.toList(), with, without)
            week += 7
        }
        return out
    }
}
