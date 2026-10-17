package com.treadz.console.history

import com.treadz.console.analysis.workout.AnalysisReason
import com.treadz.console.analysis.workout.DecouplingAnalysis
import com.treadz.console.analysis.workout.EfficiencyAnalysis
import com.treadz.console.analysis.workout.EnergyAnalysis
import com.treadz.console.analysis.workout.FitnessIndexAnalysis
import com.treadz.console.analysis.workout.RecoveryAnalysis
import com.treadz.console.analysis.workout.SessionAlpha1
import com.treadz.console.analysis.workout.SignalQuality
import com.treadz.console.analysis.workout.TrimpAnalysis
import com.treadz.console.analysis.workout.WorkoutAnalysis
import com.treadz.console.analysis.workout.ZoneTimeAnalysis

/**
 * The one-line passive remarks the analysis section can show where a metric is missing **because of the data** (as opposed
 * to a metric that simply does not apply to the workout, which is hidden).
 */
enum class AnalysisNote {
    /** The belt moved but no heart rate was recorded. */
    NoHeartRate,

    /** The heart rate covers too little of the time the belt moved for the whole-session metrics. */
    LowHeartRateCoverage,

    /** The maximum heart rate of the profile cannot be used, so the load and the fitness index are missing. */
    InvalidMaxHeartRate,

    /** No beat-to-beat (RR) data: the heart rate came from the console, an import or a strap without RR. */
    NoRrIntervals,

    /** Beat-to-beat data exist but no window is clean enough for α1. */
    NoValidWindows,
}

/**
 * What the «Workout analysis» section of the summary shows: the values of the sections that were computed, and the passive
 * [notes] for the ones missing because of the data. A section that does not apply has neither (it is hidden).
 */
data class WorkoutAnalysisView(
    val signal: SignalQuality,
    val energy: EnergyAnalysis?,
    val zoneTime: ZoneTimeAnalysis?,
    val trimp: TrimpAnalysis?,
    val efficiency: EfficiencyAnalysis?,
    val decoupling: DecouplingAnalysis?,
    val fitnessIndex: FitnessIndexAnalysis?,
    val recovery: RecoveryAnalysis?,
    val alpha1: SessionAlpha1?,
    val notes: Set<AnalysisNote>,
) {
    /** Remarks that belong to the heart-rate metrics (shown with them). */
    val heartRateNotes: List<AnalysisNote>
        get() = listOf(AnalysisNote.NoHeartRate, AnalysisNote.LowHeartRateCoverage, AnalysisNote.InvalidMaxHeartRate).filter { it in notes }

    /** Remark that belongs to α1 (shown in its place). */
    val alpha1Note: AnalysisNote?
        get() = listOf(AnalysisNote.NoRrIntervals, AnalysisNote.NoValidWindows).firstOrNull { it in notes }
}

/**
 * Maps a stored [WorkoutAnalysis] to what the summary shows. The mapping from the reason of a missing section:
 *
 * | Reason | Shown as |
 * |---|---|
 * | `NoSamples`, `ImplausibleDuration`, `NoBeltMovement` (the energy is missing too) | the whole section is hidden |
 * | `NoHeartRate` | note [AnalysisNote.NoHeartRate] (once, for all the heart-rate metrics) |
 * | `LowHeartRateCoverage` on a whole-session metric (zones, load, efficiency, decoupling) | note [AnalysisNote.LowHeartRateCoverage]; on a local one (fitness index, recovery) the metric is just hidden |
 * | `InvalidMaxHeartRate` | note [AnalysisNote.InvalidMaxHeartRate] |
 * | `NoRrIntervals` | note [AnalysisNote.NoRrIntervals] in place of α1 |
 * | `NoValidWindows` | note [AnalysisNote.NoValidWindows] in place of α1 |
 * | `NoZones`, `TooShort`, `Interrupted`, `NoSteadyStretch`, `NoRecoveryData`, `NoLoadDrop` | the metric is hidden (it does not apply to this workout) |
 */
object WorkoutAnalysisPresenter {

    /** Reasons of a workout with nothing to analyse: the whole section is hidden. */
    private val nothingToAnalyse = setOf(AnalysisReason.NoSamples, AnalysisReason.ImplausibleDuration, AnalysisReason.NoBeltMovement)

    /** `null` while the analysis is not computed ([analysis] `null`) or when the workout has nothing to analyse. */
    fun present(analysis: WorkoutAnalysis?): WorkoutAnalysisView? {
        if (analysis == null) return null
        if (analysis.energy.reason in nothingToAnalyse) return null
        val notes = linkedSetOf<AnalysisNote>()
        // Whole-session heart-rate metrics: their reasons describe the recording as a whole.
        listOf(analysis.zoneTime.reason, analysis.trimp.reason, analysis.efficiency.reason, analysis.decoupling.reason).forEach { reason ->
            when (reason) {
                AnalysisReason.NoHeartRate -> notes += AnalysisNote.NoHeartRate
                AnalysisReason.LowHeartRateCoverage -> notes += AnalysisNote.LowHeartRateCoverage
                AnalysisReason.InvalidMaxHeartRate -> notes += AnalysisNote.InvalidMaxHeartRate
                else -> Unit
            }
        }
        // Local metrics: a heart rate missing altogether or an unusable maximum is the same remark; local coverage is not.
        listOf(analysis.fitnessIndex.reason, analysis.recovery.reason).forEach { reason ->
            when (reason) {
                AnalysisReason.NoHeartRate -> notes += AnalysisNote.NoHeartRate
                AnalysisReason.InvalidMaxHeartRate -> notes += AnalysisNote.InvalidMaxHeartRate
                else -> Unit
            }
        }
        // α1 has no heart-rate note of its own: without a heart rate the remark is the one above.
        when (analysis.alpha1.reason) {
            AnalysisReason.NoRrIntervals -> notes += AnalysisNote.NoRrIntervals
            AnalysisReason.NoValidWindows -> notes += AnalysisNote.NoValidWindows
            AnalysisReason.NoHeartRate -> notes += AnalysisNote.NoHeartRate
            else -> Unit
        }
        // The coverage remark is only true as a whole-recording statement and goes before a "no heart rate" one.
        if (AnalysisNote.NoHeartRate in notes) notes -= AnalysisNote.LowHeartRateCoverage
        return WorkoutAnalysisView(
            signal = analysis.signal,
            energy = analysis.energy.value,
            zoneTime = analysis.zoneTime.value,
            trimp = analysis.trimp.value,
            efficiency = analysis.efficiency.value,
            decoupling = analysis.decoupling.value,
            fitnessIndex = analysis.fitnessIndex.value,
            recovery = analysis.recovery.value,
            alpha1 = analysis.alpha1.value,
            notes = notes,
        )
    }
}
