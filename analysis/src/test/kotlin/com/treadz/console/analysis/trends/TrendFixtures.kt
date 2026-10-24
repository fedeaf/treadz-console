package com.treadz.console.analysis.trends

import com.treadz.console.analysis.workout.AnalysisReason
import com.treadz.console.analysis.workout.FitnessIndexAnalysis
import com.treadz.console.analysis.workout.HeartRateSourceMix
import com.treadz.console.analysis.workout.RecoveryAnalysis
import com.treadz.console.analysis.workout.RecoveryContext
import com.treadz.console.analysis.workout.Section
import com.treadz.console.analysis.workout.SignalQuality
import com.treadz.console.analysis.workout.TrimpAnalysis
import com.treadz.console.analysis.workout.WorkoutAnalysis
import com.treadz.console.analysis.workout.WorkoutAnalyzer
import com.treadz.console.analysis.workout.WorkoutFixtures
import com.treadz.console.analysis.workout.ZoneTimeAnalysis

/** Hand-made analyses for the trend tests. */
object TrendFixtures {

    private val signal = SignalQuality(0, 0, 0, 0, 0, 0, null, HeartRateSourceMix(0, 0, 0), 0, null)

    fun analysis(
        fitness: Double? = null,
        trimp: Double? = null,
        recovery: RecoveryAnalysis? = null,
        zoneSeconds: List<Int>? = null,
    ): WorkoutAnalysis {
        val missing = AnalysisReason.NoHeartRate
        return WorkoutAnalysis(
            algorithmVersion = WorkoutAnalyzer.ALGORITHM_VERSION,
            signal = signal,
            energy = Section.missing(missing),
            zoneTime = zoneSeconds?.let { Section.of(ZoneTimeAnalysis(WorkoutFixtures.zones, it, it.sum())) } ?: Section.missing(missing),
            trimp = trimp?.let { Section.of(TrimpAnalysis(it, 30.0, 60, false)) } ?: Section.missing(missing),
            efficiency = Section.missing(missing),
            fitnessIndex = fitness?.let { Section.of(FitnessIndexAnalysis(it, 1, 1, listOf(it), false)) } ?: Section.missing(missing),
            recovery = recovery?.let { Section.of(it) } ?: Section.missing(missing),
            alpha1 = Section.missing(missing),
        )
    }

    fun dated(day: Long, a: WorkoutAnalysis) = DatedAnalysis(day, a)

    fun recovery(context: RecoveryContext, drop60: Double?, drop120: Double? = null, vo2: Double = 3.5) =
        RecoveryAnalysis(150.0, 0L, drop60, drop120, context, vo2)
}
