package com.treadz.console.analysis.workout

import com.treadz.console.analysis.energy.FuelAnchors
import com.treadz.console.analysis.energy.FuelRules
import com.treadz.console.analysis.energy.FuelTracker
import com.treadz.console.analysis.samples.WorkoutSample
import com.treadz.console.analysis.zones.ZoneSet
import com.treadz.console.analysis.zones.ZoneSource

/**
 * The estimated split of the gross energy of a workout between fat and carbohydrate (item 10 of [WorkoutAnalyzer]); the
 * model is in [com.treadz.console.analysis.energy.SubstrateModel] and the second-by-second work in [FuelTracker].
 *
 * **When there is a split**: the person has measured thresholds (the zones of the context come from
 * [ZoneSource.MeasuredThresholds]; VT2 may come from the table), a weight, a usable maximum heart rate and anchors that
 * increase (resting < VT1 < VT2 < maximum); and the valid strap heart rate covers at least
 * [AnalysisRules.minHeartRateCoverage] of the seconds with the belt moving. Otherwise the section carries the reason
 * ([AnalysisReason.NoWeight], [AnalysisReason.InvalidMaxHeartRate], [AnalysisReason.NoMeasuredThresholds],
 * [AnalysisReason.NoReliableStrapHeartRate]) after the ones that every section shares.
 *
 * **Live and final are the same calculation**: [tracker] gives the [FuelTracker] the live display feeds with the
 * samples one by one; [compute] feeds a tracker with every sample and closes it.
 */
object FuelSection {

    /** The person's side of the eligibility: what the tracker is built with, or the reason there is none. */
    private sealed interface Profile {
        class Eligible(val anchors: FuelAnchors, val weightKg: Double, val zones: ZoneSet) : Profile
        class Ineligible(val reason: AnalysisReason) : Profile
    }

    private fun profile(context: WorkoutAnalysisContext, rules: AnalysisRules): Profile {
        val weight = context.weightKg?.takeIf { it > 0.0 } ?: return Profile.Ineligible(AnalysisReason.NoWeight)
        if (context.maxHeartRateBpm <= rules.minHeartRateBpm) return Profile.Ineligible(AnalysisReason.InvalidMaxHeartRate)
        val zones = context.zones?.takeIf { it.source == ZoneSource.MeasuredThresholds }
            ?: return Profile.Ineligible(AnalysisReason.NoMeasuredThresholds)
        val (resting, assumed) = WorkoutAnalyzer.restingFor(context)
        val anchors = FuelAnchors(resting, assumed, zones.vt1Bpm, zones.vt2Bpm, zones.vt2FromTable, context.maxHeartRateBpm, context.sex)
        if (!anchors.increasing) return Profile.Ineligible(AnalysisReason.NoMeasuredThresholds)
        return Profile.Eligible(anchors, weight, zones)
    }

    /**
     * A tracker for the live display, or `null` when the profile is not eligible (no measured VT1, no weight, an
     * unusable maximum, anchors that do not increase).
     */
    fun tracker(
        context: WorkoutAnalysisContext,
        rules: AnalysisRules = AnalysisRules(),
        fuelRules: FuelRules = FuelRules(),
    ): FuelTracker? = when (val profile = profile(context, rules)) {
        is Profile.Eligible -> FuelTracker(profile.anchors, profile.weightKg, fuelRules, rules, profile.zones)
        is Profile.Ineligible -> null
    }

    /** The section for [samples] (any order): a [tracker] fed with every sample in time order, then closed. */
    fun compute(
        samples: List<WorkoutSample>,
        context: WorkoutAnalysisContext,
        rules: AnalysisRules = AnalysisRules(),
        fuelRules: FuelRules = FuelRules(),
    ): Section<FuelAnalysis> {
        val sorted = if (samples.zipWithNext().all { (a, b) -> a.timeMillis <= b.timeMillis }) samples else samples.sortedBy { it.timeMillis }
        return compute(WorkoutTimeline.build(sorted, rules), sorted, context, rules, fuelRules)
    }

    /** As the public one, for a caller that already has the timeline of [sorted] (the analyzer). */
    internal fun compute(
        tl: WorkoutTimeline,
        sorted: List<WorkoutSample>,
        context: WorkoutAnalysisContext,
        rules: AnalysisRules,
        fuelRules: FuelRules = FuelRules(),
    ): Section<FuelAnalysis> {
        tl.problem?.let { return Section.missing(it) }
        if (tl.movingSeconds == 0) return Section.missing(AnalysisReason.NoBeltMovement)
        val tracker = when (val profile = profile(context, rules)) {
            is Profile.Eligible -> FuelTracker(profile.anchors, profile.weightKg, fuelRules, rules, profile.zones)
            is Profile.Ineligible -> return Section.missing(profile.reason)
        }
        for (sample in sorted) tracker.add(sample)
        return tracker.finish()
    }
}

/**
 * The fat/carbohydrate split of a workout, in gross kcal (fat + carbohydrate = [EnergyAnalysis.grossKcal] of the same
 * analysis). An estimate, not a measurement.
 *
 * @property fatShare [fatKcal] / ([fatKcal] + [carbKcal]).
 * @property movingSeconds Seconds with the belt moving; [validSeconds] of them had a valid strap heart rate
 *   ([FuelTracker]); [validCoverage] = [validSeconds] / [movingSeconds], at least [AnalysisRules.minHeartRateCoverage].
 * @property byZone One entry per zone of the context, with **only the valid seconds** (the others took the share of the
 *   last valid one and have no heart rate to place them in a zone), by the zone of the model heart rate; so the zones add
 *   up to the totals only when every moving second was valid.
 * @property minutes One entry per minute of the timeline from the first second with the belt moving (for the chart),
 *   up to the last such second; a minute without movement has zeros. They add up to the totals.
 * @property anchors The heart rates the curve was anchored on, so that the stored analysis explains itself.
 */
data class FuelAnalysis(
    val fatKcal: Double,
    val carbKcal: Double,
    val fatShare: Double,
    val movingSeconds: Int,
    val validSeconds: Int,
    val validCoverage: Double,
    val byZone: List<FuelZone>,
    val minutes: List<FuelMinute>,
    val anchors: FuelAnchors,
)

/** The valid seconds whose model heart rate was in zone [number] (1-5) of the context, and their energy. */
data class FuelZone(val number: Int, val seconds: Int, val fatKcal: Double, val carbKcal: Double)

/**
 * One minute of the moving timeline: [startMillis] on the clock of the samples (the first minute starts at the first
 * second with the belt moving, the next ones every 60 s), and its energy.
 */
data class FuelMinute(val startMillis: Long, val fatKcal: Double, val carbKcal: Double)
