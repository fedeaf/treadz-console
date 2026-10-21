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
import com.treadz.console.programs.heartrate.HeartRatePlausibilityFilter
import com.treadz.console.programs.heartrate.PlausibilityConfig
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/**
 * The derived values of a stored fitness test result, computed again from the workout it belongs to: the single, pure
 * and idempotent entry point to bring old results up to date ([recalculate]). The live test runner uses the same
 * functions when a test ends, so a result is complete the moment it is saved.
 *
 * What is (re)computed, for a **valid** result:
 *
 * | Test | VO2max | Thresholds | Peak heart rate |
 * |---|---|---|---|
 * | Maximal walking test | From the samples (mean real load of the last 60 s, [WalkingTestVo2max.fromSamples]); without samples, from the stage records ([WalkingTestVo2max.fromStages]) | DFA-α1 ([ThresholdEngine]) from the start of the warm-up to the end of the effort, when the samples carry RR intervals from a strap; otherwise the reason (`noRrIntervals`, `noWorkoutSamples`) | With the samples up to the end of the test ([refinedPeak]) |
 * | Maximal running test | Unchanged (Léger-Mercier from the MAS) | As the walking test | As the walking test |
 * | Ebbeling, invalid results | Unchanged | None | Unchanged |
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

    /**
     * Version of the derived values (VO2max of the walking test, thresholds and, since version 2, the peak heart rate
     * with the seconds after the effort), stored in the thresholds block.
     */
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
        if (result.measurements is EbbelingMeasurements) return null
        val samples = workoutSamples
        val peaked = samples?.let { refinedPeak(result, it) } ?: result
        val outputs = when (val m = peaked.measurements) {
            is MaxHeartRateWalkMeasurements -> {
                val estimate = samples?.let { WalkingTestVo2max.fromSamples(it, result.effortEndedAtMillis) }
                    ?: WalkingTestVo2max.fromStages(m.stages.map { StageLoad(it.speedKmh, it.inclinePercent, it.durationSeconds) })
                estimate?.let { walkOutputs(it, result.inputs.ageYears, result.inputs.sex, m.effort, m.capReached, m.hrMaxCandidateBpm) }
                    ?: peaked.outputs
            }
            else -> peaked.outputs
        }
        val thresholds = if (samples == null) {
            ThresholdResult.none(ThresholdReason.NoWorkoutSamples)
        } else {
            thresholds(
                rr = RrSeries.fromSamples(samples),
                samples = samples,
                fromMillis = result.startedAtMillis,
                toMillis = result.effortEndedAtMillis,
                maxHeartRateBpm = result.inputs.hrMaxBpm,
                restingHeartRateBpm = restingHeartRateBpm,
                vo2maxMlKgMin = outputs?.vo2maxMlKgMin,
            )
        }
        return peaked.copy(outputs = outputs, thresholds = thresholds, schemaVersion = FitnessTestResult.SCHEMA_VERSION)
    }

    /**
     * [result] with the peak heart rate the live test gives since algorithm version 2: the highest 5-s mean from 2 min
     * before the end of the effort to the end of the test, now including the seconds after the exhaustion, from the
     * heart rate of [samples] (the walking test's impossible readings discarded as in the live test). Only for a result
     * that ended by exhaustion with a usable heart rate (it has a peak). The peak never goes down (the live test saw
     * every reading, the samples fewer); when it rises, the candidate maximum heart rate and the effort assessment
     * follow it (as at the end of a live test). [result] itself when nothing changes.
     */
    internal fun refinedPeak(result: FitnessTestResult, samples: List<WorkoutSample>, config: FitnessTestConfig = FitnessTestConfig()): FitnessTestResult {
        if (result.endReason != FitnessEndReason.Exhausted) return result
        val m = result.measurements
        val old = when (m) {
            is MaxTestMeasurements -> m.peakHeartRateBpm
            is MaxHeartRateWalkMeasurements -> m.peakHeartRateBpm
            is EbbelingMeasurements -> null
        } ?: return result
        // As the live tests: the walking test discards impossible readings all along, the running test after the effort.
        val filter = peakFilter()
        val filterAll = m is MaxHeartRateWalkMeasurements
        val effortEnd = result.effortEndedAtMillis - result.startedAtMillis
        val log = HeartRateLog()
        for (sample in samples) {
            if (sample.timeMillis > result.endedAtMillis) break
            val t = sample.timeMillis - result.startedAtMillis
            val bpm = sample.heartRateBpm?.takeIf { it > 0 }
            val accepted = filter.accept(t, bpm)
            log.add(t, if (filterAll || t > effortEnd) accepted else bpm)
        }
        val peak = log.maxSlidingMean(effortEnd - config.peakWindowSeconds * 1000L, result.endedAtMillis - result.startedAtMillis, config.peakMeanSeconds * 1000L)
            ?.let(FitnessFormulas::round1)
        if (peak == null || peak <= old) return result
        val candidate = peak.roundToInt()
        val hrMax = result.inputs.hrMaxBpm
        val flags = result.flags.filter { it !in PEAK_FLAGS }
        return when (m) {
            is MaxTestMeasurements -> {
                val verdict = effortVerdict(peak, m.heartRatePlateau, m.incrementalSeconds < config.maximalMinIncrementalSeconds, hrMax, config)
                result.copy(
                    flags = flags + listOfNotNull(verdict.flag),
                    measurements = m.copy(peakHeartRateBpm = peak),
                    outputs = result.outputs?.copy(hrMaxCandidateBpm = candidate, effort = verdict.effort),
                )
            }
            is MaxHeartRateWalkMeasurements -> {
                val verdict = effortVerdict(peak, m.heartRatePlateau, m.rampSeconds < config.maximalMinIncrementalSeconds, hrMax, config)
                result.copy(
                    flags = flags + listOfNotNull(verdict.flag),
                    measurements = m.copy(peakHeartRateBpm = peak, hrMaxCandidateBpm = candidate, effort = verdict.effort),
                    outputs = result.outputs?.let { withWalkEffort(it, verdict.effort, m.capReached, candidate) },
                )
            }
            is EbbelingMeasurements -> result
        }
    }

    // ------------------------------------------------------------------ shared with the live protocols

    /**
     * Discards impossible heart-rate readings for the peak of a maximal test (spikes, the drop of a strap taken off,
     * < 40, > 240), without the "maximum + 15" rule: these tests measure the maximum.
     */
    internal fun peakFilter() = HeartRatePlausibilityFilter(null, PlausibilityConfig(maxAboveHrMaxBpm = null))

    /** The flags that explain the effort assessment from the peak heart rate ([effortVerdict]). */
    internal val PEAK_FLAGS: Set<FitnessTestFlag> = setOf(FitnessTestFlag.PeakHeartRateLow, FitnessTestFlag.MaximalByHeartRatePlateau)

    /** The effort assessment of a maximal test that ended by exhaustion, and the flag that explains it (or none). */
    internal class EffortVerdict(val effort: EffortAssessment, val flag: FitnessTestFlag?)

    /**
     * The effort of a maximal test that ended by exhaustion ([EffortAssessment]): not maximal when [short] (less than
     * 5 min of ramp); unknown without a [peak]; maximal when the peak is within [FitnessTestConfig.effortHrMarginBpm] of
     * the user's maximum or there was a heart-rate [plateau]. A peak below that margin carries
     * [FitnessTestFlag.MaximalByHeartRatePlateau] (accepted thanks to the plateau) or [FitnessTestFlag.PeakHeartRateLow].
     */
    internal fun effortVerdict(peak: Double?, plateau: Boolean, short: Boolean, hrMaxBpm: Int, config: FitnessTestConfig): EffortVerdict {
        val peakLow = peak != null && peak < hrMaxBpm - config.effortHrMarginBpm
        val flag = when {
            !peakLow -> null
            plateau && !short -> FitnessTestFlag.MaximalByHeartRatePlateau
            else -> FitnessTestFlag.PeakHeartRateLow
        }
        val effort = when {
            short -> EffortAssessment.NotMaximal
            peak == null -> EffortAssessment.Unknown
            peakLow && !plateau -> EffortAssessment.NotMaximal
            else -> EffortAssessment.Maximal
        }
        return EffortVerdict(effort, flag)
    }

    /** [outputs] of a walking test with a new [effort] and candidate maximum heart rate (the VO2max bound follows the effort). */
    internal fun withWalkEffort(outputs: FitnessTestOutputs, effort: EffortAssessment?, capReached: Boolean, hrMaxCandidateBpm: Int?): FitnessTestOutputs {
        val lowerBound = walkLowerBound(effort, capReached)
        return outputs.copy(
            vo2maxIsLowerBound = lowerBound,
            masIsLowerBound = lowerBound,
            hrMaxCandidateBpm = hrMaxCandidateBpm,
            effort = effort,
        )
    }

    private fun walkLowerBound(effort: EffortAssessment?, capReached: Boolean) = effort == EffortAssessment.NotMaximal || capReached

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
        val lowerBound = walkLowerBound(effort, capReached)
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

    /**
     * The thresholds of a test from [fromMillis] (the start of the warm-up) to [toMillis] (the end of the effort),
     * rounded for storage; `noRrIntervals` without RR intervals.
     */
    internal fun thresholds(
        rr: List<RrInterval>,
        samples: List<WorkoutSample>,
        fromMillis: Long,
        toMillis: Long,
        maxHeartRateBpm: Int,
        restingHeartRateBpm: Int?,
        vo2maxMlKgMin: Double?,
    ): ThresholdResult {
        if (rr.isEmpty()) return ThresholdResult.none(ThresholdReason.NoRrIntervals)
        val analysis = ThresholdEngine.analyse(
            ThresholdInput(rr, samples, fromMillis, toMillis, maxHeartRateBpm, restingHeartRateBpm, vo2maxMlKgMin),
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
