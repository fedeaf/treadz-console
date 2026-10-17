package com.treadz.console.analysis.workout

import com.treadz.console.analysis.fitness.Sex
import com.treadz.console.analysis.samples.RrInterval
import com.treadz.console.analysis.samples.SampleHeartRateSource
import com.treadz.console.analysis.samples.WorkoutSample
import com.treadz.console.analysis.zones.TrainingZones
import com.treadz.console.analysis.zones.ZoneSet

/** Synthetic workouts for the tests (no real data in the repository). */
object WorkoutFixtures {

    const val T0 = 1_700_000_000_000L

    /**
     * Samples every [step] seconds for [seconds] seconds from [start]; [heartRate] returns `null` for no heart rate.
     */
    fun session(
        seconds: Int,
        step: Int = 1,
        start: Long = T0,
        speed: (Int) -> Double = { 5.0 },
        incline: (Int) -> Double = { 0.0 },
        heartRate: (Int) -> Int? = { 120 },
        source: SampleHeartRateSource = SampleHeartRateSource.Strap,
    ): List<WorkoutSample> = (0 until seconds step step).map { t ->
        val hr = heartRate(t)
        WorkoutSample(start + t * 1000L, speed(t), incline(t), hr, if (hr != null) source else null)
    }

    /** The RR intervals given to the sample that received them: those ending in (previous sample, this sample]. */
    fun withRr(samples: List<WorkoutSample>, rr: List<RrInterval>): List<WorkoutSample> {
        var next = 0
        return samples.map { s ->
            val list = ArrayList<Int>()
            while (next < rr.size && rr[next].timeMillis <= s.timeMillis) list += rr[next++].rrMs
            s.copy(rrIntervalsMs = list)
        }
    }

    val zones: ZoneSet = TrainingZones.compute(maxBpm = 180, restingBpm = 60)

    fun context(
        sex: Sex = Sex.Male,
        weightKg: Double? = 70.0,
        resting: Int? = 60,
        max: Int = 180,
        zones: ZoneSet? = WorkoutFixtures.zones,
        vo2max: Double? = null,
    ) = WorkoutAnalysisContext(sex, weightKg, resting, max, zones, vo2max)
}
