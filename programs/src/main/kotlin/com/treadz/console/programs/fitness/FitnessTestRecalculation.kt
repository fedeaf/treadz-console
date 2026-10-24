package com.treadz.console.programs.fitness

import com.treadz.console.analysis.fitness.FitnessFormulas
import com.treadz.console.analysis.fitness.Sex
import com.treadz.console.analysis.fitness.StageLoad
import com.treadz.console.analysis.fitness.Vo2MaxNorms
import com.treadz.console.analysis.fitness.WalkingTestVo2max
import com.treadz.console.analysis.fitness.WalkingTestVo2maxEstimate
import com.treadz.console.analysis.heartrate.StrapWindowJudge
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
 * **Peak under 85 % of the maximum in use** (version 3): the effort was not maximal, so there are no outputs at all (no VO2max
 * and no MAS, not even as lower bounds), the result stays valid and says why in [FitnessTestResult.outputsWithheld]; the
 * thresholds are computed as usual (without the % of VO2max) and the walking test's effort is not maximal. Without a usable
 * heart rate (no peak) the rule does not apply. It is evaluated with the final peak, the one that includes the cool-down.
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
     * with the seconds after the effort; since version 3, no outputs when the peak is under 85 % of the maximum in use),
     * stored in the thresholds block. It is the threshold engine's version: raising it makes every device recalculate its
     * stored results once.
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
        // The final peak decides whether the effort was maximal at all: under 85 % of the maximum in use there are no outputs.
        val withheld = withheldReason(peaked.measurements, peaked.endReason, peaked.inputs.hrMaxBpm)
        val outputs = if (withheld != null) null else when (val m = peaked.measurements) {
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
        val recalculated = peaked.copy(outputs = outputs, thresholds = thresholds, schemaVersion = FitnessTestResult.SCHEMA_VERSION, outputsWithheld = withheld)
        return if (withheld != null) notMaximal(recalculated) else recalculated
    }

    /**
     * Why a valid result gives no VO2max and no MAS even though the test ended by exhaustion: its peak heart rate (the
     * final one) is under [FitnessTestConfig.minPeakFractionOfHrMax] of [hrMaxBpm], the maximum in use. `null` without a
     * peak (no usable heart rate: the rule does not apply), for the other end reasons and for Ebbeling.
     */
    internal fun withheldReason(
        measurements: FitnessTestMeasurements,
        endReason: FitnessEndReason,
        hrMaxBpm: Int,
        config: FitnessTestConfig = FitnessTestConfig(),
    ): OutputsWithheldReason? {
        if (endReason != FitnessEndReason.Exhausted) return null
        val peak = when (measurements) {
            is MaxTestMeasurements -> measurements.peakHeartRateBpm
            is MaxHeartRateWalkMeasurements -> measurements.peakHeartRateBpm
            is EbbelingMeasurements -> null
        } ?: return null
        return if (peak < hrMaxBpm * config.minPeakFractionOfHrMax) OutputsWithheldReason.PeakHeartRateBelowMinimum else null
    }

    /** [result] with the flags and the effort of a peak too low to be a maximal effort (a stored plateau verdict no longer holds). */
    private fun notMaximal(result: FitnessTestResult): FitnessTestResult {
        val flags = result.flags.filter { it !in PEAK_FLAGS } + FitnessTestFlag.PeakHeartRateLow
        val measurements = when (val m = result.measurements) {
            is MaxHeartRateWalkMeasurements -> m.copy(effort = EffortAssessment.NotMaximal)
            else -> m
        }
        return result.copy(flags = flags, measurements = measurements)
    }

    /**
     * [result] with the peak heart rate the live test gives since algorithm version 2: the highest 5-s mean from 2 min
     * before the end of the effort to the end of the test, now including the seconds after the exhaustion, from the
     * heart rate of [samples] (the walking test's impossible readings discarded as in the live test, and, as in the live test
     * since the strap check of the peak, without the windows the strap's RR do not confirm: [peakFromSamples]; this changes
     * no algorithm version, so a stored result is only affected if it is recalculated for another reason). Only for a result
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
        val peak = peakFromSamples(result, samples, config)
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

    /**
     * The peak heart rate of [result] from [samples], as the live test computes it (the highest 5-s mean from 2 min before
     * the end of the effort to the end of the test), without the windows the strap's RR do not confirm
     * ([FitnessTestConfig.strapWindow]; switch it off in [config] to see the peak without the rule).
     * The walking test discards impossible readings all along, the running test after the effort. `null` without a usable
     * window.
     */
    internal fun peakFromSamples(result: FitnessTestResult, samples: List<WorkoutSample>, config: FitnessTestConfig = FitnessTestConfig()): Double? {
        val filter = peakFilter()
        val filterAll = result.measurements is MaxHeartRateWalkMeasurements
        val effortEnd = result.effortEndedAtMillis - result.startedAtMillis
        val log = HeartRateLog()
        val inTest = samples.filter { it.timeMillis <= result.endedAtMillis }
        for (sample in inTest) {
            val t = sample.timeMillis - result.startedAtMillis
            val bpm = sample.heartRateBpm?.takeIf { it > 0 }
            val accepted = filter.accept(t, bpm)
            log.add(t, if (filterAll || t > effortEnd) accepted else bpm)
        }
        val judge = StrapWindowJudge.fromSamples(inTest, config.strapWindow)
        return log.maxSlidingMean(effortEnd - config.peakWindowSeconds * 1000L, result.endedAtMillis - result.startedAtMillis, config.peakMeanSeconds * 1000L) { from, end ->
            !judge.judge(result.startedAtMillis + from, result.startedAtMillis + end).isDoubtful
        }?.let(FitnessFormulas::round1)
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
     * A peak under [FitnessTestConfig.minPeakFractionOfHrMax] (85 %) of the maximum is never maximal, plateau or not
     * ([OutputsWithheldReason.PeakHeartRateBelowMinimum]).
     */
    internal fun effortVerdict(peak: Double?, plateau: Boolean, short: Boolean, hrMaxBpm: Int, config: FitnessTestConfig): EffortVerdict {
        val peakLow = peak != null && peak < hrMaxBpm - config.effortHrMarginBpm
        // Under 85 % of the maximum in use no plateau makes it a maximal effort.
        val belowMinimum = peak != null && peak < hrMaxBpm * config.minPeakFractionOfHrMax
        val flag = when {
            !peakLow && !belowMinimum -> null
            plateau && !short && !belowMinimum -> FitnessTestFlag.MaximalByHeartRatePlateau
            else -> FitnessTestFlag.PeakHeartRateLow
        }
        val effort = when {
            short -> EffortAssessment.NotMaximal
            peak == null -> EffortAssessment.Unknown
            belowMinimum -> EffortAssessment.NotMaximal
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
