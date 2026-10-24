package com.treadz.console.userdata.workout

import com.treadz.console.analysis.fitness.EnergyCounter
import com.treadz.console.userdata.WorkoutSample
import kotlin.math.roundToInt

/**
 * The calories of recorded samples, with the rule [WorkoutHistoryObserver] applies while it records: each speed and
 * incline lasts until the next sample that is not stale (a gap counts for at most [MAX_GAP_MS]), with the gross energy
 * of `EnergyExpenditure` and [weightKg]. For a workout recorded without its owner's weight, once it is assigned.
 */
internal object WorkoutCalories {

    /** A gap between two samples counts for at most this long (the speed is unknown in it). */
    const val MAX_GAP_MS = 10_000L

    /** [samples] (in order) with their accumulated calories. */
    fun withCalories(samples: List<WorkoutSample>, weightKg: Double): List<WorkoutSample> {
        val energy = EnergyCounter(weightKg)
        var previous: WorkoutSample? = null
        return samples.map { s ->
            if (!s.isStale) {
                previous?.let { p -> energy.add(minOf(s.timestampMillis - p.timestampMillis, MAX_GAP_MS) / 1000.0, p.speedKmh, p.inclinePercent) }
                previous = s
            }
            s.copy(calories = energy.kcal.roundToInt())
        }
    }
}
