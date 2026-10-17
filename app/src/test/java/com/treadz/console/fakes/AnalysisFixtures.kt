package com.treadz.console.fakes

import com.treadz.console.analysis.workout.AnalysisReason
import com.treadz.console.analysis.workout.DecouplingAnalysis
import com.treadz.console.analysis.workout.EfficiencyAnalysis
import com.treadz.console.analysis.workout.EnergyAnalysis
import com.treadz.console.analysis.workout.FitnessIndexAnalysis
import com.treadz.console.analysis.workout.HeartRateSourceMix
import com.treadz.console.analysis.workout.RecoveryAnalysis
import com.treadz.console.analysis.workout.RecoveryContext
import com.treadz.console.analysis.workout.Section
import com.treadz.console.analysis.workout.SessionAlpha1
import com.treadz.console.analysis.workout.SignalQuality
import com.treadz.console.analysis.workout.TrimpAnalysis
import com.treadz.console.analysis.workout.WorkoutAnalysis
import com.treadz.console.analysis.workout.ZoneTimeAnalysis
import com.treadz.console.analysis.zones.TrainingZones

/** Hand-made workout analyses (synthetic) for the tests of what the UI does with each section and reason. */
object AnalysisFixtures {

    val signal = SignalQuality(
        sampleCount = 2_000, coveredSeconds = 2_000, gapSeconds = 0, movingSeconds = 1_900, heartRateSeconds = 1_880, frozenHeartRateSeconds = 0,
        heartRateCoverage = 0.99, heartRateSources = HeartRateSourceMix(1_880, 0, 0), rrBeats = 2_400, rrArtifactPercent = 1.2,
    )

    val energy = EnergyAnalysis(1_900, 5.5, 2.9, 20.4, 5.8, 55.0, 210.0, 150.0)
    val zoneTime = ZoneTimeAnalysis(TrainingZones.compute(maxBpm = 183, restingBpm = 58), listOf(120, 900, 700, 160, 0), 1_880)
    val trimp = TrimpAnalysis(61.0, 31.0, 58, restingAssumed = false)
    val efficiency = EfficiencyAnalysis(20.4, 128.0, 0.159, 1_880)
    val decoupling = DecouplingAnalysis(1_900, 1_600, 0.16, 0.15, 6.2, 0.9, longSteadyZone2 = true)
    val fitnessIndex = FitnessIndexAnalysis(43.8, 3, 4, listOf(43.0, 43.8, 44.5), restingAssumed = false)
    val recovery = RecoveryAnalysis(132.0, 1_800_000L, 24.0, 41.0, RecoveryContext.BeltStopped, 3.5)
    val alpha1 = SessionAlpha1(150, 120, 700, 500, 0.81)

    /** A complete analysis: every section has its value. */
    fun full() = WorkoutAnalysis(
        1, signal, Section.of(energy), Section.of(zoneTime), Section.of(trimp), Section.of(efficiency), Section.of(decoupling),
        Section.of(fitnessIndex), Section.of(recovery), Section.of(alpha1),
    )

    /** Every section missing for [reason] (the signal quality is always there). */
    fun missing(reason: AnalysisReason) = WorkoutAnalysis(
        1, signal, Section.missing(reason), Section.missing(reason), Section.missing(reason), Section.missing(reason), Section.missing(reason),
        Section.missing(reason), Section.missing(reason), Section.missing(reason),
    )

    /** [full] with only the sections given replaced by a missing one with its reason. */
    fun with(
        energy: AnalysisReason? = null,
        zoneTime: AnalysisReason? = null,
        trimp: AnalysisReason? = null,
        efficiency: AnalysisReason? = null,
        decoupling: AnalysisReason? = null,
        fitnessIndex: AnalysisReason? = null,
        recovery: AnalysisReason? = null,
        alpha1: AnalysisReason? = null,
    ): WorkoutAnalysis {
        val f = full()
        return f.copy(
            energy = energy?.let { Section.missing(it) } ?: f.energy,
            zoneTime = zoneTime?.let { Section.missing(it) } ?: f.zoneTime,
            trimp = trimp?.let { Section.missing(it) } ?: f.trimp,
            efficiency = efficiency?.let { Section.missing(it) } ?: f.efficiency,
            decoupling = decoupling?.let { Section.missing(it) } ?: f.decoupling,
            fitnessIndex = fitnessIndex?.let { Section.missing(it) } ?: f.fitnessIndex,
            recovery = recovery?.let { Section.missing(it) } ?: f.recovery,
            alpha1 = alpha1?.let { Section.missing(it) } ?: f.alpha1,
        )
    }

    /** A session of [trimp] load (or none) with nothing else: for the load and zone trends. */
    fun loadOnly(trimp: Double?, zoneSeconds: List<Int>? = null) = missing(AnalysisReason.NoHeartRate).copy(
        trimp = trimp?.let { Section.of(TrimpAnalysis(it, 30.0, 58, false)) } ?: Section.missing(AnalysisReason.NoHeartRate),
        zoneTime = zoneSeconds?.let { Section.of(ZoneTimeAnalysis(zoneTime.zones, it, it.sum())) } ?: Section.missing(AnalysisReason.NoHeartRate),
    )
}
