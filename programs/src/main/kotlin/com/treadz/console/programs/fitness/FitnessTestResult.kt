package com.treadz.console.programs.fitness

import com.treadz.console.analysis.fitness.Sex
import com.treadz.console.analysis.fitness.Vo2MaxClassification
import com.treadz.console.analysis.fitness.WalkingTestVo2max
import com.treadz.console.analysis.thresholds.ThresholdResult

/** The fitness tests the app can run. */
enum class FitnessTestType {
    /**
     * Ebbeling single-stage treadmill walking test (submaximal): 4 min of flat walking at a speed that brings the heart
     * rate to 50-70 % of the age-predicted maximum, then 4 min at the same speed and 5 % grade.
     */
    EbbelingWalk,

    /**
     * Maximal incremental running test: warm-up, then +0.5 km/h every minute at 1 % grade until voluntary exhaustion
     * (VAMEVAL / UMTT style). Gives the maximal aerobic speed (MAS).
     */
    MaxIncrementalRun,

    /**
     * Maximal walking test (modified Balke): 2-min warm-up, then walking from 5.5 km/h and 5 % with +1 % per minute up
     * to 18 % (or the user's incline limit) and then the speed equivalent of +1 % per minute, until exhaustion. It gives
     * the maximum heart rate, the VO2max (ACSM at the mean load of the last minute, [Vo2MaxMethod.AcsmWalkRamp]) and,
     * with RR intervals from a strap, the thresholds ([FitnessTestResult.thresholds]). Wire name `maxHeartRateWalk`
     * (kept from when it only measured the maximum heart rate).
     */
    MaxHeartRateWalk,
}

/** Where the heart rate came from. */
enum class HeartRateSourceKind {
    /** A chest strap paired with the treadmill console, relayed through FTMS (no contact indicator). */
    TreadmillConsole,

    /** A strap connected directly to the app. */
    DirectStrap,
}

/** Is the result usable? */
enum class Validity { Valid, Invalid }

/** How much a flag matters. */
enum class FlagSeverity {
    /** Worth knowing; the result is fine. */
    Info,

    /** The result is usable but less certain (it weighs less in the fitness profile). */
    Warning,

    /** The result must not be used: [FitnessTestResult.validity] is [Validity.Invalid]. */
    Invalidating,
}

/**
 * Stable codes describing what happened in a test, for the UI to translate. Unknown codes found when reading a
 * document written by a newer app are dropped (the stored [FitnessTestResult.validity] is kept as written).
 */
enum class FitnessTestFlag(val severity: FlagSeverity) {
    // --- both tests
    /** Ebbeling: age outside 20-59, the population the equation was validated on. */
    OutsideValidatedPopulation(FlagSeverity.Warning),

    /** The start speed asked for was outside the allowed range (or the safety limits) and was clamped. */
    StartSpeedClamped(FlagSeverity.Info),

    /** The user ended the test early ("end test"): cool-down followed. */
    EndedByUser(FlagSeverity.Invalidating),

    /** The user pressed STOP: the belt was stopped. */
    StoppedByUser(FlagSeverity.Invalidating),

    /** Speed or incline was changed by hand (console buttons or app controls), which breaks the protocol. */
    ManualChange(FlagSeverity.Invalidating),

    /** Safety key, connection lost, belt stopped from elsewhere, command not executed or failed, frozen data. */
    Interrupted(FlagSeverity.Invalidating),

    /** The cool-down was cut short (STOP, interruption). It does not affect the result. */
    CoolDownIncomplete(FlagSeverity.Info),

    // --- Ebbeling
    /** Stage 1 speed was changed automatically to bring the heart rate into the 50-70 % band. */
    SpeedAdjustedToHeartRateBand(FlagSeverity.Info),

    /**
     * The user changed the stage 1 speed (app controls or console buttons) before the speed was frozen. The speed
     * the user chose became the test speed; see the `origin` of the speed adjustments. After it, the automatic
     * mid-stage corrections stopped.
     */
    SpeedAdjustedByUser(FlagSeverity.Info),

    /** The heart rate stayed below the band even at the fastest walking speed allowed: the test went on at that speed. */
    HeartRateBelowBand(FlagSeverity.Warning),

    /** The heart rate stayed above the band even at the slowest walking speed: the test cannot be done safely. */
    HeartRateAboveBand(FlagSeverity.Invalidating),

    /** Stage 2 was extended by at least one minute to reach a steady state. */
    SteadyStateExtended(FlagSeverity.Info),

    /** No steady state even after the allowed extensions. */
    NoSteadyState(FlagSeverity.Invalidating),

    /** The heart rate did not rise when the grade went from 0 to 5 %: the strap is probably not measuring. */
    HeartRateNotResponsive(FlagSeverity.Invalidating),

    /** The heart rate did not change at all during stage 2: not a real measurement. */
    HeartRateFlat(FlagSeverity.Invalidating),

    /** No valid heart rate for several seconds during the test. */
    HeartRateLost(FlagSeverity.Invalidating),

    /** Heart rate above 85 % of the user's maximum: safety stop. */
    HeartRateAboveSafetyLimit(FlagSeverity.Invalidating),

    // --- maximal test
    /** The speed reached the treadmill maximum or the user's safety limit: the MAS is a lower bound. */
    SpeedCapReached(FlagSeverity.Warning),

    /** Exhaustion within 3 min of the incremental phase: no meaningful MAS (start speed far too high?). */
    IncrementalPhaseTooShort(FlagSeverity.Invalidating),

    /** Exhaustion in less than 5 min of the incremental phase: the effort may not have been maximal. */
    ShortIncrementalPhase(FlagSeverity.Warning),

    /** The peak heart rate was more than 10 bpm below the user's maximum (and there was no plateau): the effort may not have been maximal. */
    PeakHeartRateLow(FlagSeverity.Warning),

    /**
     * The peak heart rate was more than 10 bpm below the user's maximum, but the heart rate stopped rising while the
     * speed kept increasing and the user confirmed exhaustion: the effort is accepted as maximal and the peak is a
     * measured maximum heart rate (probably lower than the estimate).
     */
    MaximalByHeartRatePlateau(FlagSeverity.Info),

    /**
     * The test ended with the belt being stopped (STOP in the app, or stopped from the console) after at least 3 min
     * of the incremental phase; it counts as exhaustion. The belt stopped, so there was no cool-down.
     */
    EndedWithStop(FlagSeverity.Info),

    /** No usable heart rate during the incremental phase: no peak heart rate and the effort cannot be judged. */
    NoValidHeartRate(FlagSeverity.Info),

    /** There was a heart rate but with gaps, or it did not rise with the speed: it is not used. */
    HeartRateUnreliable(FlagSeverity.Info),
}

/** Why the test (its measuring part) ended. */
enum class FitnessEndReason {
    /** Ebbeling: stage 2 finished (the result can still be invalid, see the flags). */
    Completed,

    /**
     * Maximal test: the user reported exhaustion, or stopped the belt (STOP or console) after at least 3 min of the
     * incremental phase (then the result carries [FitnessTestFlag.EndedWithStop]).
     */
    Exhausted,

    /** The user ended the test early (cool-down followed). */
    EndedByUser,

    /** The user pressed STOP. */
    StoppedByUser,
    HeartRateAboveLimit,
    HeartRateLost,

    /** Ebbeling: the heart rate could not be brought into the band. */
    HeartRateBandUnreachable,
    NoSteadyState,
    ManualChange,
    SafetyKeyRemoved,
    ConnectionLost,

    /** The belt stopped or paused without the test asking (console STOP/pause...). */
    BeltStoppedExternally,
    CommandNotExecuted,
    CommandFailed,
    DataStalled,
}

/** Who was tested and with what reference values. */
data class FitnessTestInputs(
    val ageYears: Int,
    val sex: Sex,
    val weightKg: Double,
    /** The user's maximum heart rate used for the safety limit and the effort criterion (profile value). */
    val hrMaxBpm: Int,
    /** 220 − age: the reference of the Ebbeling 50-70 % band (kept for fidelity with the original study). */
    val agePredictedHrMaxBpm: Int,
    /** `null` when no valid heart rate was seen. */
    val heartRateSource: HeartRateSourceKind?,
    /** Start speed the caller asked for. */
    val requestedStartSpeedKmh: Double,
    /** Start speed actually used (after clamping). */
    val startSpeedKmh: Double,
    /** User safety limits in force (`null` = none). */
    val maxSpeedLimitKmh: Double? = null,
    val maxInclineLimitPercent: Double? = null,
)

/** Phases of a test run. */
enum class FitnessTestPhase {
    Idle,

    /** The belt is starting. */
    Starting,

    /** Ebbeling stage 1: flat walk, the speed is tuned to the heart-rate band and then held. */
    Stage1,

    /** Ebbeling stage 2: same speed, 5 % grade, until a steady state. */
    Stage2,

    /** Warm-up of the maximal tests. */
    WarmUp,

    /**
     * Incremental phase until exhaustion: +0.5 km/h every minute (running test) or the incline/speed ramp (walking
     * heart-rate test).
     */
    Incremental,

    /** Easy walk after the test, then the belt is stopped. */
    CoolDown,

    /** The belt is being stopped. */
    Stopping,
    Finished,
}

/** One minute of a phase (the last one can be shorter). Heart rate is the mean of the minute's last 10 s. */
data class MinuteRecord(
    val phase: FitnessTestPhase,
    /** 1-based minute within the phase. */
    val minute: Int,
    /** Test time (seconds since the test started) at the end of this minute. */
    val endSecond: Int,
    val durationSeconds: Int,
    val speedKmh: Double,
    val inclinePercent: Double,
    val heartRateBpm: Double?,
)

/** Who changed the speed in Ebbeling stage 1. */
enum class SpeedAdjustmentOrigin {
    /** The protocol, to bring the heart rate into the band. */
    Automatic,

    /** The user, with the app's controls ([FitnessTestRunner.adjustSpeed]). */
    App,

    /** The user, with the console's buttons. */
    Console,
}

/** A speed change in Ebbeling stage 1: automatic, or made by the user ([origin]). */
data class SpeedAdjustment(
    val atSecond: Int,
    val fromKmh: Double,
    val toKmh: Double,
    /** Mean of the last 10 s before the change; `null` without a reading. */
    val heartRateBpm: Double?,
    val origin: SpeedAdjustmentOrigin = SpeedAdjustmentOrigin.Automatic,
)

/** One stage of a maximal test (the running test, or the ramp of the walking heart-rate test). */
data class StageRecord(
    /** 1-based. */
    val number: Int,
    val speedKmh: Double,
    val inclinePercent: Double,
    val durationSeconds: Int,
    val completed: Boolean,
    /** Mean of the last 10 s of the stage. */
    val heartRateEndBpm: Double?,
)

sealed interface FitnessTestMeasurements

data class EbbelingMeasurements(
    val heartRateBandLowBpm: Int,
    val heartRateBandHighBpm: Int,
    val heartRateSafetyLimitBpm: Int,
    /** The constant speed of stage 2 (`null` if the test ended in stage 1). */
    val testSpeedKmh: Double?,
    val testSpeedMph: Double?,
    val stage1Seconds: Int,
    val stage2Seconds: Int,
    /** Stage 1 extensions used to bring the heart rate into the band. */
    val stage1Extensions: Int,
    /** Stage 2 one-minute extensions used to reach a steady state. */
    val stage2Extensions: Int,
    val speedAdjustments: List<SpeedAdjustment>,
    /** Mean of the last 30 s of stage 1. */
    val stage1EndHeartRateBpm: Double?,
    /** Mean of the last 30 s of stage 2 when steady. */
    val steadyStateHeartRateBpm: Double?,
    /** Highest minus lowest 5-s mean heart rate over the last 2 min of stage 2. */
    val steadyStateVariationBpm: Double?,
    /** Steady-state (or last 30 s) stage 2 heart rate minus the stage 1 end heart rate. */
    val heartRateRiseBpm: Double?,
) : FitnessTestMeasurements

data class MaxTestMeasurements(
    val warmUpSeconds: Int,
    val incrementalSeconds: Int,
    val startSpeedKmh: Double,
    val speedIncrementKmh: Double,
    val stageSeconds: Int,
    val inclinePercent: Double,
    /** Highest speed allowed (treadmill maximum and user limit). */
    val capKmh: Double,
    val capReached: Boolean,
    val stages: List<StageRecord>,
    /** Speed of the last completed stage (`null` when none was completed). */
    val lastCompletedStageSpeedKmh: Double?,
    /** Seconds run in the stage that was not completed. */
    val secondsIntoLastStage: Int?,
    /** Highest 5-s mean heart rate from 2 min before the end of the effort to the end of the test (since algorithm version 2; before, up to the end of the effort); `null` without a reliable heart rate. */
    val peakHeartRateBpm: Double?,
    /** Fraction of the incremental phase with a valid heart rate (0-1). */
    val heartRateCoverage: Double?,
    /** The heart rate stopped rising while the speed kept increasing (rule in [MaxIncrementalProtocol]). */
    val heartRatePlateau: Boolean = false,
    /**
     * Heart rate at the end of the last completed stage minus that of the stage two stages earlier (bpm); `null` when
     * it could not be evaluated (no usable heart rate, too few stages, speed cap).
     */
    val plateauRiseBpm: Double? = null,
) : FitnessTestMeasurements

/**
 * Measurements of the maximal walking test ([FitnessTestType.MaxHeartRateWalk]). The measured maximum is
 * [hrMaxCandidateBpm], meaningful only for a valid result (see [FitnessTestResult.measuredHrMaxCandidateBpm]); a valid
 * result also has [FitnessTestResult.outputs] with the VO2max ([Vo2MaxMethod.AcsmWalkRamp]), which repeat
 * [hrMaxCandidateBpm] and [effort]. Results stored before version 2 of the result format have no outputs until they
 * are recalculated ([FitnessTestRecalculation]).
 */
data class MaxHeartRateWalkMeasurements(
    val warmUpSeconds: Int,
    /** Time in the ramp (the incremental phase) until the end of the effort. */
    val rampSeconds: Int,
    val startSpeedKmh: Double,
    val startInclinePercent: Double,
    val inclineIncrementPercent: Double,
    /** Highest incline of the ramp: 18 %, or the user's incline limit, or the treadmill maximum. */
    val inclineCapPercent: Double,
    /** Speed added every minute once the incline cap is reached (equivalent of +1 % grade). */
    val speedIncrementKmh: Double,
    /** Highest speed allowed (treadmill maximum and user limit). */
    val speedCapKmh: Double,
    val stageSeconds: Int,
    /** Both the incline cap and the speed cap were reached (the effort is then less likely to be maximal). */
    val capReached: Boolean,
    val stages: List<StageRecord>,
    /** Highest 5-s mean heart rate from 2 min before the end of the effort to the end of the test (since algorithm version 2; before, up to the end of the effort); `null` without a reliable heart rate. */
    val peakHeartRateBpm: Double?,
    /** Fraction of the ramp with a valid heart rate (0-1). */
    val heartRateCoverage: Double?,
    val heartRatePlateau: Boolean = false,
    val plateauRiseBpm: Double? = null,
    /** The measured maximum heart rate (the peak, rounded), when the effort ended properly with a reliable heart rate. */
    val hrMaxCandidateBpm: Int? = null,
    /** Effort assessment with the rules of the running test; `null` when the effort did not end by exhaustion. */
    val effort: EffortAssessment? = null,
) : FitnessTestMeasurements

enum class Vo2MaxMethod {
    /** VO2max = 15.1 + 21.8·v − 0.327·HR − 0.263·v·age + 0.00504·HR·age + 5.98·sex (Ebbeling et al. 1991). */
    Ebbeling1991,

    /** VO2max ≈ 3.5 · MAS (Léger and Mercier 1984). */
    LegerMercier1984,

    /**
     * Maximal walking test: the ACSM walking equation (running above 7.5 km/h) at the mean real load of the last 60 s of
     * the effort; SEE 3.5 assumed. See [WalkingTestVo2max].
     */
    AcsmWalkRamp,
}

/** Whether the maximal test looks like a maximal effort. */
enum class EffortAssessment {
    /**
     * At least 5 min of incremental phase, exhaustion confirmed by the user (button or STOP), and either a peak heart
     * rate within 10 bpm of the user's maximum or a heart-rate plateau.
     */
    Maximal,

    /** Less than 5 min of incremental phase, or a low peak without a plateau (see the flags). */
    NotMaximal,

    /** No reliable heart rate: the effort cannot be judged. */
    Unknown,
}

data class FitnessTestOutputs(
    val vo2maxMlKgMin: Double,
    /** Standard error of the estimate of the method, ml·kg⁻¹·min⁻¹. */
    val vo2maxSeeMlKgMin: Double,
    val method: Vo2MaxMethod,
    /** The real value is at least this (maximal test that reached the speed cap). */
    val vo2maxIsLowerBound: Boolean = false,
    val classification: Vo2MaxClassification?,
    /** Maximal aerobic speed: measured by the running test; VO2max / 3.5 for the walking test ([masIsEstimated]). */
    val masKmh: Double? = null,
    val masIsLowerBound: Boolean = false,
    /** Measured maximum heart rate candidate (peak heart rate of a maximal test), when reliable. Adopted when [effort] is [EffortAssessment.Maximal]. */
    val hrMaxCandidateBpm: Int? = null,
    /** Maximal test only. */
    val effort: EffortAssessment? = null,
    /** [masKmh] is not measured but VO2max / 3.5 (Léger and Mercier): the walking test. */
    val masIsEstimated: Boolean = false,
)

/**
 * The result of one fitness test. JSON: [FitnessTestJson]. Time values are epoch milliseconds (ISO-8601 in JSON).
 * [outputs] is present only for a [Validity.Valid] result (Ebbeling, maximal running test, maximal walking test; a
 * walking test stored before format version 2 has none until it is recalculated, see [FitnessTestRecalculation]).
 *
 * [thresholds] (format version 2): the DFA-α1 thresholds of a valid maximal test (walking or running) with the
 * algorithm version, or the reason there are none (no RR intervals, no workout samples...). `null` for Ebbeling, for
 * invalid results and for results not analysed yet.
 *
 * [schemaVersion] is, by default, the lowest format version that can hold the result ([schemaVersionFor]): 1 without
 * thresholds and without a walking-test VO2max, so older apps keep reading those documents.
 */
data class FitnessTestResult(
    val id: String,
    val testType: FitnessTestType,
    val protocolVersion: Int,
    /** When the belt was confirmed running for the test. */
    val startedAtMillis: Long,
    /** When the measuring part ended (the cool-down follows). */
    val effortEndedAtMillis: Long,
    /** When the run ended (after the cool-down, or the stop). */
    val endedAtMillis: Long,
    val validity: Validity,
    val flags: List<FitnessTestFlag>,
    val endReason: FitnessEndReason,
    /** English text for people reading the document (what interrupted the test, which command...). */
    val endDetail: String? = null,
    val inputs: FitnessTestInputs,
    val measurements: FitnessTestMeasurements,
    /** Every minute of every phase, in order (cool-down included). */
    val minutes: List<MinuteRecord>,
    val coolDownSeconds: Int,
    val outputs: FitnessTestOutputs?,
    val thresholds: ThresholdResult? = null,
    val schemaVersion: Int = schemaVersionFor(outputs, thresholds),
) {
    val effortSeconds: Int get() = ((effortEndedAtMillis - startedAtMillis) / 1000).toInt()

    /**
     * The measured maximum heart rate this result offers (both maximal tests), only when the result is valid: the
     * running test's [FitnessTestOutputs.hrMaxCandidateBpm] or the walking test's
     * [MaxHeartRateWalkMeasurements.hrMaxCandidateBpm].
     */
    val measuredHrMaxCandidateBpm: Int?
        get() = if (validity != Validity.Valid) null
        else outputs?.hrMaxCandidateBpm ?: (measurements as? MaxHeartRateWalkMeasurements)?.hrMaxCandidateBpm

    /** Effort assessment of a maximal test (either kind); `null` for Ebbeling or an effort that did not end properly. */
    val effortAssessment: EffortAssessment?
        get() = outputs?.effort ?: (measurements as? MaxHeartRateWalkMeasurements)?.effort

    companion object {
        /** Newest format version this code reads and writes. Version 2 added `thresholds` and `acsmWalkRamp`. */
        const val SCHEMA_VERSION = 2

        /** The format version without the additions of version 2. */
        const val BASE_SCHEMA_VERSION = 1

        /** The lowest format version that can hold a result with these [outputs] and [thresholds]. */
        fun schemaVersionFor(outputs: FitnessTestOutputs?, thresholds: ThresholdResult?): Int =
            if (thresholds != null || outputs?.method == Vo2MaxMethod.AcsmWalkRamp || outputs?.masIsEstimated == true) SCHEMA_VERSION
            else BASE_SCHEMA_VERSION
    }
}
