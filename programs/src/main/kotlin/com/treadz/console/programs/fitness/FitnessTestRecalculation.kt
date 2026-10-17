package com.treadz.console.programs.fitness

import com.treadz.console.analysis.fitness.FitnessFormulas
import com.treadz.console.analysis.fitness.Sex
import com.treadz.console.analysis.fitness.StageLoad
import com.treadz.console.analysis.fitness.Vo2MaxNorms
import com.treadz.console.analysis.fitness.WalkingTestVo2max
import com.treadz.console.analysis.fitness.WalkingTestVo2maxEstimate
import com.treadz.console.analysis.samples.RrInterval
import com.treadz.console.analysis.samples.RrSeries
import com.treadz.console.analysis.samples.WorkoutSample
import com.treadz.console.analysis.thresholds.ThresholdEngine
import com.treadz.console.analysis.thresholds.ThresholdInput
import com.treadz.console.analysis.thresholds.ThresholdMetrics
import com.treadz.console.analysis.thresholds.ThresholdPoint
import com.treadz.console.analysis.thresholds.ThresholdReason
import com.treadz.console.analysis.thresholds.ThresholdResult
import kotlin.math.roundToLong

/**
 * The derived values of a stored fitness test result, computed again from the workout it belongs to: the single, pure
 * and idempotent entry point to bring old results up to date ([recalculate]). The live test runner uses the same
 * functions when a test ends, so a result is complete the moment it is saved.
 *
 * What is (re)computed, for a **valid** result:
 *
 * | Test | VO2max | Thresholds |
 * |---|---|---|
 * | Maximal walking test | From the samples (mean real load of the last 60 s, [WalkingTestVo2max.fromSamples]); without samples, from the stage records ([WalkingTestVo2max.fromStages]) | DFA-α1 ([ThresholdEngine]) when the samples carry RR intervals from a strap; otherwise the reason (`noRrIntervals`, `noWorkoutSamples`) |
 * | Maximal running test | Unchanged (Léger-Mercier from the MAS) | As the walking test |
 * | Ebbeling, invalid results | Unchanged | None |
 *
 * Heart rate from an unknown source (imported workouts without a source or RR) counts as the console's: it never
 * provides RR intervals. Missing RR intervals or samples are data (the reason), never an error.
 *
 * **Idempotent**: a result whose thresholds carry the current [ALGORITHM_VERSION] is not recalculated
 * ([needsRecalculation] is false, [recalculate] returns `null`), except a result marked `noWorkoutSamples` when samples
 * are now given (the workout arrived later). The same inputs always give the same result, so two devices recalculating
 * the same result write the same document.
 */
object FitnessTestRecalculation {

    /** Version of the derived values (VO2max of the walking test and thresholds), stored in the thresholds block. */
    const val ALGORITHM_VERSION = ThresholdEngine.ALGORITHM_VERSION

    /** The test types whose results carry derived values. */
    val ANALYSED_TESTS: Set<FitnessTestType> = setOf(FitnessTestType.MaxHeartRateWalk, FitnessTestType.MaxIncrementalRun)

    /**
     * Whether [recalculate] would change [result]: a valid maximal test (walking or running) without thresholds or with
     * an older algorithm version; or marked `noWorkoutSamples` while [samplesAvailable].
     */
    fun needsRecalculation(result: FitnessTestResult, samplesAvailable: Boolean = false): Boolean {
        if (result.testType !in ANALYSED_TESTS || result.validity != Validity.Valid) return false
        val t = result.thresholds ?: return true
        if (t.algorithmVersion < ALGORITHM_VERSION) return true
        return samplesAvailable && t.reason == ThresholdReason.NoWorkoutSamples
    }

    /**
     * [result] with its derived values computed from [workoutSamples] (the samples of the workout linked to the test,
     * in time order, on the epoch clock of the result; `null` when there is no linked workout), or `null` when nothing
     * changes ([needsRecalculation] is false). The result keeps everything else (id, flags, measurements...) and its
     * format version becomes [FitnessTestResult.SCHEMA_VERSION].
     *
     * @param restingHeartRateBpm The user's resting heart rate, for the % of the reserve at each threshold (optional).
     */
    fun recalculate(result: FitnessTestResult, workoutSamples: List<WorkoutSample>?, restingHeartRateBpm: Int? = null): FitnessTestResult? {
        if (!needsRecalculation(result, workoutSamples != null)) return null
        val samples = workoutSamples
        val outputs = when (val m = result.measurements) {
            is MaxHeartRateWalkMeasurements -> {
                val estimate = samples?.let { WalkingTestVo2max.fromSamples(it, result.effortEndedAtMillis) }
                    ?: WalkingTestVo2max.fromStages(m.stages.map { StageLoad(it.speedKmh, it.inclinePercent, it.durationSeconds) })
                estimate?.let { walkOutputs(it, result.inputs.ageYears, result.inputs.sex, m.effort, m.capReached, m.hrMaxCandidateBpm) }
                    ?: result.outputs
            }
            else -> result.outputs
        }
        val warmUpSeconds = when (val m = result.measurements) {
            is MaxHeartRateWalkMeasurements -> m.warmUpSeconds
            is MaxTestMeasurements -> m.warmUpSeconds
            is EbbelingMeasurements -> return null
        }
        val thresholds = if (samples == null) {
            ThresholdResult.none(ThresholdReason.NoWorkoutSamples)
        } else {
            thresholds(
                rr = RrSeries.fromSamples(samples),
                samples = samples,
                incrementalStartMillis = result.startedAtMillis + warmUpSeconds * 1000L,
                incrementalEndMillis = result.effortEndedAtMillis,
                maxHeartRateBpm = result.inputs.hrMaxBpm,
                restingHeartRateBpm = restingHeartRateBpm,
                vo2maxMlKgMin = outputs?.vo2maxMlKgMin,
            )
        }
        return result.copy(outputs = outputs, thresholds = thresholds, schemaVersion = FitnessTestResult.SCHEMA_VERSION)
    }

    // ------------------------------------------------------------------ shared with the live protocols

    /**
     * The outputs of a valid maximal walking test from its VO2max [estimate]. The VO2max is a lower bound when the
     * effort was not maximal or both caps were reached (the load stopped rising); the SEE stored is the method's (3.5,
     * an assumption): the fitness profile applies its warning factor through the flags and the effort, as for the other
     * tests. MAS = VO2max / 3.5, marked estimated.
     */
    internal fun walkOutputs(
        estimate: WalkingTestVo2maxEstimate,
        ageYears: Int,
        sex: Sex,
        effort: EffortAssessment?,
        capReached: Boolean,
        hrMaxCandidateBpm: Int?,
    ): FitnessTestOutputs {
        val vo2 = FitnessFormulas.round1(estimate.vo2maxMlKgMin)
        val lowerBound = effort == EffortAssessment.NotMaximal || capReached
        return FitnessTestOutputs(
            vo2maxMlKgMin = vo2,
            vo2maxSeeMlKgMin = WalkingTestVo2max.SEE_ML_KG_MIN,
            method = Vo2MaxMethod.AcsmWalkRamp,
            vo2maxIsLowerBound = lowerBound,
            classification = Vo2MaxNorms.classify(vo2, ageYears, sex),
            masKmh = FitnessFormulas.round2(FitnessFormulas.masFromVo2max(vo2)),
            masIsLowerBound = lowerBound,
            hrMaxCandidateBpm = hrMaxCandidateBpm,
            effort = effort,
            masIsEstimated = true,
        )
    }

    /** The thresholds of an incremental phase, rounded for storage; `noRrIntervals` without RR intervals. */
    internal fun thresholds(
        rr: List<RrInterval>,
        samples: List<WorkoutSample>,
        incrementalStartMillis: Long,
        incrementalEndMillis: Long,
        maxHeartRateBpm: Int,
        restingHeartRateBpm: Int?,
        vo2maxMlKgMin: Double?,
    ): ThresholdResult {
        if (rr.isEmpty()) return ThresholdResult.none(ThresholdReason.NoRrIntervals)
        val analysis = ThresholdEngine.analyse(
            ThresholdInput(rr, samples, incrementalStartMillis, incrementalEndMillis, maxHeartRateBpm, restingHeartRateBpm, vo2maxMlKgMin),
        )
        return rounded(analysis.result)
    }

    /** [t] rounded as stored (so a document read back equals the result in memory). */
    fun rounded(t: ThresholdResult): ThresholdResult = t.copy(
        vt1 = t.vt1?.let(::rounded),
        vt2 = t.vt2?.let(::rounded),
        metrics = t.metrics?.let {
            ThresholdMetrics(
                stretchWindows = it.stretchWindows,
                validWindows = it.validWindows,
                totalWindows = it.totalWindows,
                rSquared = FitnessFormulas.round3(it.rSquared),
                meanArtifactPercent = FitnessFormulas.round2(it.meanArtifactPercent),
                slopePerBpm = (it.slopePerBpm * 100_000.0).roundToLong() / 100_000.0,
                interceptAlpha1 = FitnessFormulas.round3(it.interceptAlpha1),
            )
        },
    )

    private fun rounded(p: ThresholdPoint) = ThresholdPoint(
        heartRateBpm = FitnessFormulas.round1(p.heartRateBpm),
        speedKmh = p.speedKmh?.let(FitnessFormulas::round2),
        inclinePercent = p.inclinePercent?.let(FitnessFormulas::round2),
        vo2MlKgMin = p.vo2MlKgMin?.let(FitnessFormulas::round1),
        percentVo2max = p.percentVo2max?.let(FitnessFormulas::round1),
        percentHeartRateReserve = p.percentHeartRateReserve?.let(FitnessFormulas::round1),
    )
}
