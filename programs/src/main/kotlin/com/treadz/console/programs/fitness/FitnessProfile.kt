package com.treadz.console.programs.fitness

import com.treadz.console.analysis.estimate.CombinationRules
import com.treadz.console.analysis.estimate.EstimateCombiner
import com.treadz.console.analysis.estimate.EstimateInput
import com.treadz.console.analysis.fitness.FitnessFormulas
import com.treadz.console.analysis.fitness.Sex
import com.treadz.console.analysis.fitness.Vo2MaxClassification
import com.treadz.console.analysis.fitness.Vo2MaxNorms
import com.treadz.console.analysis.thresholds.ThresholdPoint
import com.treadz.console.analysis.thresholds.ThresholdQuality
import com.treadz.console.analysis.thresholds.ThresholdResult
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * Rules of the fitness profile (kept in the document so a reader knows how it was computed).
 *
 * The defaults were **approved by the user (2026-10-08)**: a 90-day half-life and a 365-day maximum age. The factor for
 * results with a warning is a design choice of the same weighting. The SEE of the maximal test (4.0, see
 * [FitnessFormulas.MAS_VO2MAX_SEE]) is an **assumption** that the user also accepted; the Ebbeling SEE (4.85) is the
 * published one. The submaximal factor and the discrepancy rule were decided with the user on 2026-10-17.
 */
data class FitnessProfileRules(
    /** A result loses half its weight every this many days (approved: 90). */
    val halfLifeDays: Double = 90.0,
    /** Results older than this are ignored (approved: 365). */
    val maxAgeDays: Int = 365,
    /** The SEE of a result with a warning flag is multiplied by this (it weighs 1 / factor² as much). */
    val warningSeeFactor: Double = 1.5,
    /**
     * The weight of a submaximal test (Ebbeling) is multiplied by this when a valid maximal test (effort maximal, not a
     * lower bound) is at most [maximalRecencyDays] old. An assumption: maximal tests measure the capacity, submaximal
     * ones extrapolate it with a personal bias (heart-rate response, economy) that repeating the test does not remove.
     */
    val submaximalFactorWithMaximal: Double = 0.25,
    val maximalRecencyDays: Int = 90,
    /** Two sources used differ by more than this times √(SEE₁² + SEE₂²): [Vo2MaxEstimate.discrepancy]. */
    val discrepancyFactor: Double = 1.5,
)

/** One test result that contributed to the combined VO2max. */
data class Vo2MaxSource(
    val resultId: String,
    val testType: FitnessTestType,
    val testedAtMillis: Long,
    val vo2maxMlKgMin: Double,
    /** SEE used for the weighting (the method's, times [FitnessProfileRules.warningSeeFactor] with warnings). */
    val seeMlKgMin: Double,
    val ageDays: Int,
    /** Share of this result in the combined value (0-1, the shares add up to 1). */
    val weight: Double,
    val lowerBound: Boolean,
    /** Extra factor applied to its weight: [FitnessProfileRules.submaximalFactorWithMaximal] or 1. */
    val weightFactor: Double = 1.0,
    /** It is one of a pair of sources that disagree grossly (see [Vo2MaxEstimate.discrepancy]). */
    val discrepant: Boolean = false,
)

data class Vo2MaxEstimate(
    val valueMlKgMin: Double,
    /** Uncertainty of the combined value, ml·kg⁻¹·min⁻¹ (never below the best single test's SEE). */
    val seeMlKgMin: Double,
    /** For the age and sex given to the calculator; `null` when they were not given. */
    val classification: Vo2MaxClassification?,
    val sources: List<Vo2MaxSource>,
    /**
     * Two of the sources differ by more than [FitnessProfileRules.discrepancyFactor]·√(SEE₁² + SEE₂²) (their SEEs as
     * used): one of the measurements is probably wrong. Data only, shown passively; the sources involved are marked
     * [Vo2MaxSource.discrepant].
     */
    val discrepancy: Boolean = false,
)

data class MasEstimate(
    val valueKmh: Double,
    /** True when no valid maximal test is available and the value is VO2max / 3.5. */
    val estimated: Boolean,
    /** The maximal test reached the speed cap: the real MAS is at least this. */
    val lowerBound: Boolean,
    /** The maximal test it comes from (`null` when [estimated]). */
    val sourceResultId: String?,
    val testedAtMillis: Long?,
    val effort: EffortAssessment?,
)

data class HrMaxEstimate(
    /** Highest reliable peak heart rate of a maximal-effort test. */
    val measuredBpm: Int,
    val sourceResultId: String,
    val testedAtMillis: Long,
)

/**
 * The user's current fitness estimates, computed from the history of test results by [FitnessProfileCalculator].
 * JSON: [FitnessTestJson.encodeProfile].
 */
data class FitnessProfile(
    val computedAtMillis: Long,
    val vo2max: Vo2MaxEstimate?,
    val mas: MasEstimate?,
    val hrMax: HrMaxEstimate?,
    val rules: FitnessProfileRules = FitnessProfileRules(),
    val schemaVersion: Int = SCHEMA_VERSION,
    /** VT1 and VT2 combined from the maximal tests with thresholds; `null` when no test has any. */
    val thresholds: ThresholdProfile? = null,
) {
    companion object {
        const val SCHEMA_VERSION = 1
    }
}

/**
 * Rules of the threshold profile, decided with the user on 2026-10-17 (assumptions: thresholds change faster than the
 * VO2max, and a single test can be several bpm off).
 *
 * @property halfLifeDays A test loses half its weight every this many days (30).
 * @property maxAgeDays Tests older than this are ignored (180).
 * @property highQualityWeight Weight of a high-quality detection (1); [mediumQualityWeight] of a medium one (0.5); low
 *   ones are left out.
 * @property jumpBpm A test more than this away from the profile value before it (6 bpm) is a jump...
 * @property jumpFactor ...and weighs this much (0.5)...
 * @property corroborationDays ...until another test of the following this many days (60)...
 * @property corroborationBpm ...is at most this far from it (4 bpm).
 */
data class ThresholdProfileRules(
    val halfLifeDays: Double = 30.0,
    val maxAgeDays: Int = 180,
    val highQualityWeight: Double = 1.0,
    val mediumQualityWeight: Double = 0.5,
    val jumpBpm: Double = 6.0,
    val jumpFactor: Double = 0.5,
    val corroborationDays: Int = 60,
    val corroborationBpm: Double = 4.0,
)

/**
 * One test that contributed to a profile threshold.
 *
 * @property weight Share in the combined value (0-1).
 * @property jump It differed by more than [ThresholdProfileRules.jumpBpm] from the profile value before it.
 * @property corroborated A later test confirmed the jump: it weighs fully again.
 */
data class ThresholdSource(
    val resultId: String,
    val testType: FitnessTestType,
    val testedAtMillis: Long,
    val heartRateBpm: Double,
    val vo2MlKgMin: Double?,
    val quality: ThresholdQuality,
    val ageDays: Int,
    val weight: Double,
    val jump: Boolean = false,
    val corroborated: Boolean = false,
)

/**
 * A combined threshold.
 *
 * @property heartRateBpm The combined heart rate, rounded.
 * @property vo2MlKgMin The ACSM oxygen demand at the threshold, combined with the same weights (sources that have it).
 * @property quality The quality of the source that weighs most.
 */
data class ThresholdEstimate(
    val heartRateBpm: Int,
    val vo2MlKgMin: Double?,
    val quality: ThresholdQuality,
    val sources: List<ThresholdSource>,
)

/** One test with thresholds, for the history (any age and quality, used or not). */
data class ThresholdHistoryEntry(
    val resultId: String,
    val testType: FitnessTestType,
    val testedAtMillis: Long,
    val vt1Bpm: Double?,
    val vt2Bpm: Double?,
    val quality: ThresholdQuality?,
)

/**
 * The user's thresholds, combined from the maximal tests ([FitnessTestResult.thresholds]).
 *
 * @property vt1 `null` when no test of sufficient quality in the last [ThresholdProfileRules.maxAgeDays] has one.
 * @property history Every valid test with a VT1 or VT2, newest first.
 */
data class ThresholdProfile(
    val vt1: ThresholdEstimate?,
    val vt2: ThresholdEstimate?,
    val history: List<ThresholdHistoryEntry>,
    val rules: ThresholdProfileRules = ThresholdProfileRules(),
)

/**
 * Builds the [FitnessProfile] from test results (a pure function).
 *
 * Only [Validity.Valid] results taken at most [FitnessProfileRules.maxAgeDays] before `now` are used; VO2max and MAS
 * only come from results with outputs (Ebbeling, the maximal running test and the maximal walking test).
 * The 90-day half-life, the 365-day maximum age and the SEE of 4.0 for the maximal test were approved by the user
 * (2026-10-08); the SEE is an assumption, not a published figure.
 *
 * **VO2max**: inverse-variance weighting with recency ([EstimateCombiner]). Each result i has a weight
 * `w_i = f_i · 0.5^(age_i / halfLife) / SEE_i²`, where SEE_i is the method's SEE (4.85 for Ebbeling, published; **4.0
 * for the maximal running test and 3.5 for the maximal walking test, assumptions** — see
 * [FitnessFormulas.MAS_VO2MAX_SEE] and `WalkingTestVo2max`) multiplied by 1.5 when the result has a warning flag
 * (outside the validated population, band not reached, speed cap, effort not maximal...). f_i is
 * [FitnessProfileRules.submaximalFactorWithMaximal] (0.25) for a submaximal test (Ebbeling) when a valid maximal test
 * (effort maximal, not a lower bound) of the last [FitnessProfileRules.maximalRecencyDays] (90) days exists, else 1.
 * Combined = Σ w_i·v_i / Σ w_i. Its uncertainty is √(Σ w_i²·SEE_i²) / Σ w_i, but never below the smallest SEE_i used:
 * the error of these equations is mostly a person-specific bias (economy, heart-rate response), which repeating a
 * test does not average out. [Vo2MaxEstimate.discrepancy] marks two sources that differ by more than
 * 1.5·√(SEE₁² + SEE₂²) (data only).
 *
 * **MAS**: the latest valid maximal running test; without one, VO2max / 3.5 marked as estimated (the walking test's
 * MAS is itself an estimate).
 *
 * **Measured maximum heart rate**: the highest peak heart rate among valid maximal tests (the running test and the
 * walking test) whose effort was [EffortAssessment.Maximal] (a peak from a submaximal effort is not a maximum). A
 * maximal effort is either a peak close to the maximum used by the test or a confirmed exhaustion with a heart-rate
 * plateau (see [MaxIncrementalProtocol]), so the measured value may be below the estimate. How it competes with the
 * manual value and the estimate: [HeartRateMaxPolicy]. A peak more than [FitnessFormulas.HR_MAX_FLOOR_BELOW_ESTIMATE_BPM]
 * below the Tanaka estimate for the age at the test is not adopted (the result itself still counts for VO2max and MAS).
 *
 * **Thresholds** ([ThresholdProfile]): VT1 and VT2 of the valid maximal tests with a high or medium quality detection,
 * combined with the same algorithm: weight = quality weight (high 1, medium 0.5) · 0.5^(age / 30 d), tests older than
 * 180 days left out. A test more than 6 bpm away from the profile value computed from the tests before it is a jump and
 * weighs half, until a test of the following 60 days confirms it (at most 4 bpm from it). No question to the user.
 */
object FitnessProfileCalculator {

    private const val DAY_MS = 86_400_000L

    fun compute(
        results: List<FitnessTestResult>,
        nowMillis: Long,
        ageYears: Int? = null,
        sex: Sex? = null,
        rules: FitnessProfileRules = FitnessProfileRules(),
        thresholdRules: ThresholdProfileRules = ThresholdProfileRules(),
    ): FitnessProfile {
        val usable = results
            .filter { it.validity == Validity.Valid && it.outputs != null }
            .map { it to ageDays(it, nowMillis) }
            .filter { (_, days) -> days <= rules.maxAgeDays }

        // -- VO2max
        val recentMaximal = usable.any { (r, days) ->
            val o = checkNotNull(r.outputs)
            r.testType != FitnessTestType.EbbelingWalk && o.effort == EffortAssessment.Maximal && !o.vo2maxIsLowerBound &&
                days <= rules.maximalRecencyDays
        }
        val factors = usable.map { (r, _) ->
            if (r.testType == FitnessTestType.EbbelingWalk && recentMaximal) rules.submaximalFactorWithMaximal else 1.0
        }
        val combined = EstimateCombiner.combine(
            usable.zip(factors).map { (u, factor) ->
                val (r, days) = u
                val o = checkNotNull(r.outputs)
                val warned = r.flags.any { it.severity == FlagSeverity.Warning } || o.effort == EffortAssessment.NotMaximal
                EstimateInput(value = o.vo2maxMlKgMin, see = o.vo2maxSeeMlKgMin, ageDays = days, warned = warned, weightFactor = factor)
            },
            CombinationRules(halfLifeDays = rules.halfLifeDays, warningSeeFactor = rules.warningSeeFactor),
        )
        val vo2 = if (combined == null) {
            null
        } else {
            val rounded = FitnessFormulas.round1(combined.value)
            val discrepant = EstimateCombiner.discrepantPairs(
                usable.map { checkNotNull(it.first.outputs).vo2maxMlKgMin },
                combined.inputs.map { it.seeUsed },
                rules.discrepancyFactor,
            ).flatMap { listOf(it.first, it.second) }.toSet()
            Vo2MaxEstimate(
                valueMlKgMin = rounded,
                seeMlKgMin = FitnessFormulas.round2(combined.see),
                classification = if (ageYears != null && sex != null) Vo2MaxNorms.classify(rounded, ageYears, sex) else null,
                sources = usable.indices.sortedByDescending { usable[it].first.startedAtMillis }.map { i ->
                    val (r, days) = usable[i]
                    val c = combined.inputs[i]
                    val o = checkNotNull(r.outputs)
                    Vo2MaxSource(
                        resultId = r.id,
                        testType = r.testType,
                        testedAtMillis = r.startedAtMillis,
                        vo2maxMlKgMin = o.vo2maxMlKgMin,
                        seeMlKgMin = FitnessFormulas.round2(c.seeUsed),
                        ageDays = days.toInt(),
                        weight = FitnessFormulas.round2(c.weight),
                        lowerBound = o.vo2maxIsLowerBound,
                        weightFactor = factors[i],
                        discrepant = i in discrepant,
                    )
                },
                discrepancy = discrepant.isNotEmpty(),
            )
        }

        // -- MAS: measured only by the running test
        val maxTests = usable.map { it.first }.filter { it.testType == FitnessTestType.MaxIncrementalRun && it.outputs?.masKmh != null }
        val latestMax = maxTests.maxByOrNull { it.startedAtMillis }
        val mas = when {
            latestMax != null -> {
                val o = checkNotNull(latestMax.outputs)
                MasEstimate(checkNotNull(o.masKmh), false, o.masIsLowerBound, latestMax.id, latestMax.startedAtMillis, o.effort)
            }
            vo2 != null -> MasEstimate(FitnessFormulas.round2(FitnessFormulas.masFromVo2max(vo2.valueMlKgMin)), true, false, null, null, null)
            else -> null
        }

        // -- measured maximum heart rate: both maximal tests (a walking result stored before version 2 has no outputs)
        val hrMax = results
            .filter { it.validity == Validity.Valid && ageDays(it, nowMillis) <= rules.maxAgeDays }
            .filter { it.effortAssessment == EffortAssessment.Maximal && it.measuredHrMaxCandidateBpm != null }
            .filter { isAdoptableHrMax(checkNotNull(it.measuredHrMaxCandidateBpm), it.inputs.ageYears) }
            .maxWithOrNull(compareBy<FitnessTestResult> { it.measuredHrMaxCandidateBpm }.thenBy { it.startedAtMillis })
            ?.let { HrMaxEstimate(checkNotNull(it.measuredHrMaxCandidateBpm), it.id, it.startedAtMillis) }

        return FitnessProfile(nowMillis, vo2, mas, hrMax, rules, thresholds = thresholdProfile(results, nowMillis, thresholdRules))
    }

    /**
     * Whether a peak heart rate of a maximal effort is plausible enough to become the measured maximum of a person of
     * [ageYears] (see [FitnessFormulas.HR_MAX_FLOOR_BELOW_ESTIMATE_BPM]).
     */
    fun isAdoptableHrMax(peakBpm: Int, ageYears: Int): Boolean =
        peakBpm >= FitnessFormulas.tanakaHrMax(ageYears) - FitnessFormulas.HR_MAX_FLOOR_BELOW_ESTIMATE_BPM

    /** Days between the test and [now]; a test dated in the future (clock differences between devices) counts as 0. */
    private fun ageDays(r: FitnessTestResult, now: Long): Double = maxOf(0L, now - r.startedAtMillis).toDouble() / DAY_MS

    // ------------------------------------------------------------------ thresholds

    /** The threshold profile of [results] at [nowMillis] (see the class); `null` when no valid test has a threshold. */
    fun thresholdProfile(results: List<FitnessTestResult>, nowMillis: Long, rules: ThresholdProfileRules = ThresholdProfileRules()): ThresholdProfile? {
        val withThresholds = results.filter { it.validity == Validity.Valid && (it.thresholds?.vt1 != null || it.thresholds?.vt2 != null) }
        if (withThresholds.isEmpty()) return null
        val history = withThresholds.sortedWith(compareByDescending<FitnessTestResult> { it.startedAtMillis }.thenBy { it.id }).map {
            val t = checkNotNull(it.thresholds)
            ThresholdHistoryEntry(it.id, it.testType, it.startedAtMillis, t.vt1?.heartRateBpm, t.vt2?.heartRateBpm, t.quality)
        }
        return ThresholdProfile(
            vt1 = combineThreshold(withThresholds, nowMillis, rules) { it.vt1 },
            vt2 = combineThreshold(withThresholds, nowMillis, rules) { it.vt2 },
            history = history,
            rules = rules,
        )
    }

    private class Candidate(val result: FitnessTestResult, val point: ThresholdPoint, val quality: ThresholdQuality, val qualityWeight: Double) {
        val t: Long get() = result.startedAtMillis
        val bpm: Double get() = point.heartRateBpm
    }

    private fun combineThreshold(
        results: List<FitnessTestResult>,
        nowMillis: Long,
        rules: ThresholdProfileRules,
        pick: (ThresholdResult) -> ThresholdPoint?,
    ): ThresholdEstimate? {
        val candidates = results.mapNotNull { r ->
            val t = checkNotNull(r.thresholds)
            val point = pick(t) ?: return@mapNotNull null
            val weight = when (t.quality) {
                ThresholdQuality.High -> rules.highQualityWeight
                ThresholdQuality.Medium -> rules.mediumQualityWeight
                ThresholdQuality.Low, null -> return@mapNotNull null
            }
            Candidate(r, point, checkNotNull(t.quality), weight)
        }.filter { ageDays(it.result, nowMillis) <= rules.maxAgeDays }
            .sortedWith(compareBy<Candidate> { it.t }.thenBy { it.result.id })
        if (candidates.isEmpty()) return null

        val combination = CombinationRules(halfLifeDays = rules.halfLifeDays, warningSeeFactor = 1.0)
        val corroborationMs = rules.corroborationDays * DAY_MS

        /** Whether candidate [i] is confirmed by another one seen at [asOf] at the latest. */
        fun corroborated(i: Int, asOf: Long): Boolean = candidates.indices.any { j ->
            j != i && candidates[j].t > candidates[i].t && candidates[j].t <= candidates[i].t + corroborationMs &&
                candidates[j].t <= asOf && abs(candidates[j].bpm - candidates[i].bpm) <= rules.corroborationBpm
        }

        val jump = BooleanArray(candidates.size)
        fun factor(i: Int, asOf: Long): Double =
            candidates[i].qualityWeight * if (jump[i] && !corroborated(i, asOf)) rules.jumpFactor else 1.0

        for (k in candidates.indices) {
            val before = (0 until k).filter { (candidates[k].t - candidates[it].t) <= rules.maxAgeDays * DAY_MS }
            val prior = EstimateCombiner.combine(
                before.map { i ->
                    EstimateInput(candidates[i].bpm, 1.0, (candidates[k].t - candidates[i].t).toDouble() / DAY_MS, weightFactor = factor(i, candidates[k].t - 1))
                },
                combination,
            )
            jump[k] = prior != null && abs(candidates[k].bpm - prior.value) > rules.jumpBpm
        }

        val factors = candidates.indices.map { factor(it, nowMillis) }
        val combined = EstimateCombiner.combine(
            candidates.indices.map { i -> EstimateInput(candidates[i].bpm, 1.0, ageDays(candidates[i].result, nowMillis), weightFactor = factors[i]) },
            combination,
        ) ?: return null
        val withVo2 = candidates.indices.filter { candidates[it].point.vo2MlKgMin != null }
        val vo2Weight = withVo2.sumOf { combined.inputs[it].weight }
        val vo2 = if (withVo2.isEmpty() || vo2Weight <= 0.0) null
        else FitnessFormulas.round1(withVo2.sumOf { combined.inputs[it].weight * checkNotNull(candidates[it].point.vo2MlKgMin) } / vo2Weight)
        val top = candidates.indices.maxWith(compareBy<Int> { combined.inputs[it].weight }.thenBy { candidates[it].t })
        return ThresholdEstimate(
            heartRateBpm = combined.value.roundToInt(),
            vo2MlKgMin = vo2,
            quality = candidates[top].quality,
            sources = candidates.indices.sortedByDescending { candidates[it].t }.map { i ->
                val c = candidates[i]
                ThresholdSource(
                    resultId = c.result.id,
                    testType = c.result.testType,
                    testedAtMillis = c.t,
                    heartRateBpm = c.bpm,
                    vo2MlKgMin = c.point.vo2MlKgMin,
                    quality = c.quality,
                    ageDays = ageDays(c.result, nowMillis).toInt(),
                    weight = FitnessFormulas.round2(combined.inputs[i].weight),
                    jump = jump[i],
                    corroborated = jump[i] && corroborated(i, nowMillis),
                )
            },
        )
    }
}
