package com.treadz.console.analysis.workout

import com.treadz.console.analysis.fitness.Sex
import com.treadz.console.analysis.zones.ZoneSet

/**
 * Why a section of a [WorkoutAnalysis] could not be computed. Missing data is a normal outcome, never an error and never
 * a user-facing text: the UI maps each reason to its own wording (or shows nothing).
 */
enum class AnalysisReason {
    /** No usable samples (none, or a single one). */
    NoSamples,

    /** The samples span an implausible time ([AnalysisRules.maxSpanSeconds]): probably corrupt timestamps. */
    ImplausibleDuration,

    /** The belt never moved (speed 0 in every sample). */
    NoBeltMovement,

    /** The belt moved but no valid heart rate was recorded. */
    NoHeartRate,

    /** The heart rate covers less than [AnalysisRules.minHeartRateCoverage] of the time the belt moved. */
    LowHeartRateCoverage,

    /** The context gives a maximum heart rate that cannot be used (not above [AnalysisRules.minHeartRateBpm]). */
    InvalidMaxHeartRate,

    /** No zone set was given in the context. */
    NoZones,

    /** No stretch of steady load long enough, or none with a usable heart-rate reserve fraction. */
    NoSteadyStretch,

    /** Not enough data after the heart-rate peak to measure the recovery. */
    NoRecoveryData,

    /** The load did not drop after the heart-rate peak, so there is no recovery to measure. */
    NoLoadDrop,

    /** No RR intervals from a strap connected to the app (console or unknown heart rate, or a strap without RR). */
    NoRrIntervals,

    /** RR intervals exist but no α1 window is valid (too few beats or more than 5 % artifacts). */
    NoValidWindows,

    /**
     * The context has no α1-heart-rate slope: the user has no test with thresholds yet (or it was taken after this
     * workout), so the thresholds cannot be estimated from the workout.
     */
    NoThresholdReference,

    /** The context has no body weight (or not above 0), so the energy cannot be split between fat and carbohydrate. */
    NoWeight,

    /**
     * The zones of the context do not come from measured thresholds (or the anchors resting < VT1 < VT2 < maximum do not
     * increase), so the heart rate cannot be placed on the person's own RER curve.
     */
    NoMeasuredThresholds,

    /**
     * The strap heart rate (a strap connected to the app, in range, confirmed by its RR) covers less than
     * [AnalysisRules.minHeartRateCoverage] of the time the belt moved, or there is no such heart rate at all.
     */
    NoReliableStrapHeartRate,
}

/**
 * One section of the analysis: the [value], or the [reason] it could not be computed (exactly one of them is set).
 */
data class Section<out T : Any>(val value: T?, val reason: AnalysisReason?) {
    companion object {
        fun <T : Any> of(value: T): Section<T> = Section(value, null)
        fun missing(reason: AnalysisReason): Section<Nothing> = Section(null, reason)
    }
}

/**
 * What the analysis needs to know about the person **as of the workout's date** (the data layer builds it).
 *
 * @property weightKg Body weight; `null` = energy without kcal.
 * @property restingHeartRateBpm A resting heart rate, or `null`: TRIMP and the fitness index then use
 *   `ASSUMED_RESTING_FRACTION` of the maximum, like the zones, and say so in their result.
 * @property maxHeartRateBpm The effective maximum heart rate.
 * @property zones The zone set in force that day; `null` = no time in zones.
 * @property vo2maxMlKgMin The profile VO2max that day, only to express the mean oxygen demand as % of it.
 * @property alpha1SlopePerBpm The slope of the α1-heart-rate regression (α1 per bpm, negative) of the user's latest test
 *   with thresholds before the workout (`ThresholdMetrics.slopePerBpm`); `null` = no such test, and then the thresholds
 *   cannot be estimated from the workout ([AnalysisReason.NoThresholdReference]). A value that is not negative is ignored.
 * @property vt2Bpm VT2 of the profile that day, to recognise a hard effort earlier in the workout (more than a minute
 *   above it); `null` = the one of [zones] ([ZoneSet.vt2Bpm]), and without any the rule is not applied. VT1 is not needed:
 *   the estimates are computed from the slope and the workout itself.
 */
data class WorkoutAnalysisContext(
    val sex: Sex,
    val weightKg: Double? = null,
    val restingHeartRateBpm: Int? = null,
    val maxHeartRateBpm: Int,
    val zones: ZoneSet? = null,
    val vo2maxMlKgMin: Double? = null,
    val alpha1SlopePerBpm: Double? = null,
    val vt2Bpm: Double? = null,
)

/** Parameters of [WorkoutAnalyzer] (the defaults are the documented method; tests may change them). */
data class AnalysisRules(
    /** A sample holds its values for at most this long: beyond it the time is a gap and does not count. */
    val maxHoldSeconds: Int = 10,
    /** More span than this between the first and the last sample is rejected as corrupt. */
    val maxSpanSeconds: Int = 24 * 3600,
    /** Heart rates outside this range are treated as missing. */
    val minHeartRateBpm: Int = 30,
    val maxHeartRateBpm: Int = 250,
    /**
     * The whole-session heart-rate sections (zones, TRIMP, efficiency) need it for at least this share of
     * the time the belt moved; the local ones (fitness index, recovery) need it for this share of the span they use.
     */
    val minHeartRateCoverage: Double = 0.8,
    /**
     * The same console or unknown-source heart rate for longer than this is a frozen reading (a console or an imported
     * recording repeating its last value), treated as missing for its whole length. *Assumption*: real readings, even at
     * a steady load, change within a few minutes (the longest real run seen is under 2 min). Strap readings are exempt.
     */
    val maxFrozenHeartRateSeconds: Int = 300,

    /** Fitness index: minimum length of a steady-load stretch. */
    val steadyMinSeconds: Int = 180,
    /** Steady load: speed within this of the speed at the start of the stretch. */
    val steadySpeedToleranceKmh: Double = 0.3,
    /** Steady load: incline within this of the incline at the start of the stretch (percentage points). */
    val steadyInclineTolerancePercent: Double = 1.0,
    /** Fitness index uses the last part of a stretch (the heart rate needs time to settle). */
    val steadyTailFraction: Double = 0.6,
    /**
     * Fitness index: the stretch's heart rate as a share of the reserve must lie in this range. Above about 75 % the
     * equivalence of % of the heart-rate reserve and % of the VO2 reserve it relies on no longer holds well (hard
     * efforts gave estimates far too low); it was 85 % up to version 2 of the analysis.
     */
    val fitnessMinReserveFraction: Double = 0.40,
    val fitnessMaxReserveFraction: Double = 0.75,

    /** Recovery: length of the window whose mean heart rate defines the peak. */
    val peakWindowSeconds: Int = 30,
    /** Recovery: half-width of the window averaged around the 60 s and 120 s marks. */
    val recoverySmoothingSeconds: Int = 2,
    /** Recovery: the load before the peak must be at least this net oxygen demand (ml/kg/min)... */
    val recoveryMinNetVo2: Double = 3.0,
    /**
     * ...and the load must drop (net demand below this share of the one in the minute before) during the peak window or
     * at most [recoveryMaxDropDelaySeconds] after it, and stay below it 15-60 s later.
     */
    val recoveryLoadDropFraction: Double = 0.6,
    val recoveryMaxDropDelaySeconds: Int = 30,
    /**
     * The drop is also searched up to this long before the peak window: after a short hard effort the heart rate keeps
     * rising for a while once the load drops, so its highest 30 s can come after the end of the effort.
     */
    val recoveryMaxHeartRateLagSeconds: Int = 60,

    /** Session α1: a window counts only if the belt moved during this share of it. */
    val alpha1MinMovingFraction: Double = 0.8,
    /** Session α1: the cut between the aerobic and the above-threshold side (Rogers 2021, ≈ VT1). */
    val alpha1Cut: Double = 0.75,

    // ---- stable stretches and the thresholds they imply (see [ThresholdEstimatesSection])
    /** A stable stretch lasts at least this long (speed and incline unchanged)... */
    val stretchMinSeconds: Int = 300,
    /** ...of which the first part is left out (the heart rate is still settling)... */
    val stretchSettleSeconds: Int = 120,
    /** ...and after it the heart rate (smoothed over [stretchHeartRateSmoothingSeconds]) stays within this of its mean. */
    val stretchHeartRateBandBpm: Double = 4.0,
    val stretchHeartRateSmoothingSeconds: Int = 10,
    /** "Unchanged": the speed and the incline stay within these of their values at the start of the stretch. */
    val stretchSpeedToleranceKmh: Double = 0.05,
    val stretchInclineTolerancePercent: Double = 0.05,
    /** Only stretches inside this first part of the workout (from the first second with the belt moving). */
    val stretchWithinSeconds: Int = 2400,
    /** Not after a hard effort: more than this many seconds above VT2 earlier in the workout. */
    val stretchHardEffortSeconds: Int = 60,
    /** Clean RR: windows with at most this share of artifacts (stricter than the 5 % of the threshold engine). */
    val stretchMaxArtifactFraction: Double = 0.03,
    /** A stretch needs at least this many clean windows, and at least [stretchMinCleanFraction] of its windows. */
    val stretchMinCleanWindows: Int = 4,
    val stretchMinCleanFraction: Double = 0.5,
    /** VT2 only when running (the walking-to-running speed of the ACSM equations) and with the stretch α1 at or below this. */
    val vt2MinSpeedKmh: Double = 7.5,
    val vt2MaxAlpha1: Double = 0.6,
)

/** How the heart-rate seconds are split by source (seconds with the belt moving and a heart rate). */
data class HeartRateSourceMix(val strapSeconds: Int, val consoleSeconds: Int, val unknownSeconds: Int)

/**
 * Quality of the recorded signal; always present.
 *
 * Seconds come from the 1 Hz resampled timeline (see [WorkoutAnalyzer]).
 *
 * @property sampleCount Samples given.
 * @property coveredSeconds Seconds covered by samples (gaps longer than the hold time are not covered).
 * @property gapSeconds Seconds between the first and the last covered second not covered by samples.
 * @property movingSeconds Covered seconds with the belt moving: the basis of the sections.
 * @property heartRateSeconds Moving seconds with a valid heart rate.
 * @property frozenHeartRateSeconds Moving seconds whose heart rate was dropped as frozen
 *   ([AnalysisRules.maxFrozenHeartRateSeconds]); they are not in [heartRateSeconds].
 * @property heartRateCoverage [heartRateSeconds] / [movingSeconds]; `null` if the belt never moved.
 * @property rrBeats Beats of the strap RR series; [rrArtifactPercent] the share of them corrected as artifacts (whole
 *   series, `null` without RR).
 */
data class SignalQuality(
    val sampleCount: Int,
    val coveredSeconds: Int,
    val gapSeconds: Int,
    val movingSeconds: Int,
    val heartRateSeconds: Int,
    val frozenHeartRateSeconds: Int,
    val heartRateCoverage: Double?,
    val heartRateSources: HeartRateSourceMix,
    val rrBeats: Int,
    val rrArtifactPercent: Double?,
)

/**
 * Energy by the ACSM equations, independent of the treadmill's own kcal. Only the seconds with the belt moving count.
 *
 * @property percentOfVo2max Mean oxygen demand as % of the context VO2max; `null` without it.
 * @property grossKcal VO2 · weight · 5 kcal/L, including the resting 3.5; [netKcal] without it. `null` without weight.
 */
data class EnergyAnalysis(
    val movingSeconds: Int,
    val meanSpeedKmh: Double,
    val distanceKm: Double,
    val meanVo2MlKgMin: Double,
    val meanMets: Double,
    val percentOfVo2max: Double?,
    val grossKcal: Double?,
    val netKcal: Double?,
)

/**
 * Seconds with the belt moving and a heart rate in each zone, and the zones used (so the stored analysis is
 * self-contained). [secondsInZone] has one entry per zone of [zones] (index 0 = zone 1).
 */
data class ZoneTimeAnalysis(val zones: ZoneSet, val secondsInZone: List<Int>, val analysedSeconds: Int)

/**
 * Banister's TRIMP.
 *
 * @property restingHeartRateBpm The resting heart rate used; [restingAssumed] = it is not the person's (the assumption of
 *   the zones).
 */
data class TrimpAnalysis(
    val trimp: Double,
    val minutes: Double,
    val restingHeartRateBpm: Int,
    val restingAssumed: Boolean,
)

/** Efficiency factor: mean oxygen demand per beat/min, over the moving seconds with a heart rate. */
data class EfficiencyAnalysis(
    val meanVo2MlKgMin: Double,
    val meanHeartRateBpm: Double,
    val efficiencyFactor: Double,
    val seconds: Int,
)

/**
 * The aerobic fitness index: median of the VO2max estimates of the steady stretches (trend only, never the profile
 * VO2max).
 *
 * @property estimates The estimate of each stretch used (ml·kg⁻¹·min⁻¹), in time order.
 * @property stretchesFound Steady stretches of the minimum length, used or not.
 */
data class FitnessIndexAnalysis(
    val vo2maxEstimate: Double,
    val stretchCount: Int,
    val stretchesFound: Int,
    val estimates: List<Double>,
    val restingAssumed: Boolean,
)

/** Whether the belt was moving or stopped during the recovery: only the same context is comparable. */
enum class RecoveryContext { BeltMoving, BeltStopped }

/**
 * Heart-rate recovery after the peak.
 *
 * @property peakHeartRateBpm Mean heart rate of the last [AnalysisRules.peakWindowSeconds] seconds of the effort that
 *   reached the highest such mean of the session.
 * @property peakEndMillis End of the effort (the load drops): the zero of the recovery, on the clock of the samples.
 * @property drop60Bpm Peak minus the heart rate 60 s later; `null` if the data ends before.
 * @property drop120Bpm The same at 120 s.
 * @property meanVo2MlKgMin Mean oxygen demand during the 120 s of recovery (3.5 with the belt stopped).
 */
data class RecoveryAnalysis(
    val peakHeartRateBpm: Double,
    val peakEndMillis: Long,
    val drop60Bpm: Double?,
    val drop120Bpm: Double?,
    val context: RecoveryContext,
    val meanVo2MlKgMin: Double,
)

/**
 * α1 over the whole session (strap RR only), from the clean windows.
 *
 * @property totalWindows Windows (120 s every 10 s) with the belt moving.
 * @property validWindows Of them, the valid ones (≥ 100 beats, ≤ 5 % artifacts); the rest do not count.
 * @property secondsAtOrAbove Each valid window counts [step] seconds (the 10 s step around its centre): those with
 *   α1 ≥ the cut (0.75); [secondsBelow] those below it.
 */
data class SessionAlpha1(
    val totalWindows: Int,
    val validWindows: Int,
    val secondsAtOrAbove: Int,
    val secondsBelow: Int,
    val meanAlpha1: Double,
)

/** Which threshold a [WorkoutThresholdEstimate] is of. */
enum class ThresholdKind { Vt1, Vt2 }

/**
 * A threshold implied by one stable stretch of a workout, with where it comes from: **a recommendation input only, it
 * never changes the profile**. The workout it belongs to is the one this analysis belongs to.
 *
 * @property startMillis Start and end of the stretch (speed and incline unchanged), on the clock of the samples;
 *   its first [AnalysisRules.stretchSettleSeconds] are not analysed.
 * @property speedKmh Load of the stretch.
 * @property cleanWindows Clean α1 windows (120 s, at most 3 % artifacts) of the analysed part behind [alpha1] and [heartRateBpm].
 * @property alpha1 Mean α1 of those windows; [heartRateBpm] their mean heart rate (from the RR).
 * @property slopePerBpm The α1-heart-rate slope of the user's test that the value was computed with.
 * @property impliedBpm VT1: [heartRateBpm] + (0.75 − [alpha1]) / [slopePerBpm]; VT2: the same with 0.5.
 * @property weightSeconds Weight of the estimate: the duration of the stretch.
 */
data class WorkoutThresholdEstimate(
    val kind: ThresholdKind,
    val startMillis: Long,
    val endMillis: Long,
    val speedKmh: Double,
    val inclinePercent: Double,
    val cleanWindows: Int,
    val alpha1: Double,
    val heartRateBpm: Double,
    val slopePerBpm: Double,
    val impliedBpm: Double,
    val weightSeconds: Int,
)

/**
 * The thresholds the stable stretches of a workout imply (see [ThresholdEstimatesSection]).
 *
 * @property stretchesFound Stable stretches found (long enough, steady heart rate), used or not.
 * @property estimates At least one; VT1 and VT2 of the same stretch are two entries.
 */
data class ThresholdEstimatesAnalysis(val stretchesFound: Int, val estimates: List<WorkoutThresholdEstimate>)

/**
 * The analysis of one workout: independent sections, each with its value or the reason it is missing.
 *
 * @property algorithmVersion [WorkoutAnalyzer.ALGORITHM_VERSION]: an analysis with an older version is recalculated.
 * @property thresholdEstimates Version 2. Defaults to "no threshold reference" so that code that builds an analysis by
 *   hand (and a stored analysis written before it existed) still works.
 * @property fuel Version 4: the split of the energy between fat and carbohydrate ([FuelSection]). Defaults to "no
 *   measured thresholds" for the same reason.
 */
data class WorkoutAnalysis(
    val algorithmVersion: Int,
    val signal: SignalQuality,
    val energy: Section<EnergyAnalysis>,
    val zoneTime: Section<ZoneTimeAnalysis>,
    val trimp: Section<TrimpAnalysis>,
    val efficiency: Section<EfficiencyAnalysis>,
    val fitnessIndex: Section<FitnessIndexAnalysis>,
    val recovery: Section<RecoveryAnalysis>,
    val alpha1: Section<SessionAlpha1>,
    val thresholdEstimates: Section<ThresholdEstimatesAnalysis> = Section.missing(AnalysisReason.NoThresholdReference),
    val fuel: Section<FuelAnalysis> = Section.missing(AnalysisReason.NoMeasuredThresholds),
)
