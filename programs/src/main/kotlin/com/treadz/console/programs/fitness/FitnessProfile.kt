package com.treadz.console.programs.fitness

import kotlin.math.pow
import kotlin.math.sqrt

/**
 * Rules of the fitness profile (kept in the document so a reader knows how it was computed).
 *
 * The defaults were **approved by the user (2026-10-08)**: a 90-day half-life and a 365-day maximum age. The factor for
 * results with a warning is a design choice of the same weighting. The SEE of the maximal test (4.0, see
 * [FitnessFormulas.MAS_VO2MAX_SEE]) is an **assumption** that the user also accepted; the Ebbeling SEE (4.85) is the
 * published one.
 */
data class FitnessProfileRules(
    /** A result loses half its weight every this many days (approved: 90). */
    val halfLifeDays: Double = 90.0,
    /** Results older than this are ignored (approved: 365). */
    val maxAgeDays: Int = 365,
    /** The SEE of a result with a warning flag is multiplied by this (it weighs 1 / factor² as much). */
    val warningSeeFactor: Double = 1.5,
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
)

data class Vo2MaxEstimate(
    val valueMlKgMin: Double,
    /** Uncertainty of the combined value, ml·kg⁻¹·min⁻¹ (never below the best single test's SEE). */
    val seeMlKgMin: Double,
    /** For the age and sex given to the calculator; `null` when they were not given. */
    val classification: Vo2MaxClassification?,
    val sources: List<Vo2MaxSource>,
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
) {
    companion object {
        const val SCHEMA_VERSION = 1
    }
}

/**
 * Builds the [FitnessProfile] from test results (a pure function).
 *
 * Only [Validity.Valid] results taken at most [FitnessProfileRules.maxAgeDays] before `now` are used; VO2max and MAS
 * only come from results with outputs (Ebbeling and the maximal running test). The maximal heart-rate walking test
 * contributes only to the measured maximum heart rate.
 * The 90-day half-life, the 365-day maximum age and the SEE of 4.0 for the maximal test were approved by the user
 * (2026-10-08); the SEE is an assumption, not a published figure.
 *
 * **VO2max**: inverse-variance weighting with recency. Each result i has a weight
 * `w_i = 0.5^(age_i / halfLife) / SEE_i²`, where SEE_i is the method's SEE (4.85 for Ebbeling, published; **4.0 for the
 * maximal test, an assumption** — see [FitnessFormulas.MAS_VO2MAX_SEE]) multiplied by 1.5 when the result has a warning flag (outside the validated population, band not reached, speed
 * cap, effort not maximal...). Combined = Σ w_i·v_i / Σ w_i. Its uncertainty is √(Σ w_i²·SEE_i²) / Σ w_i, but never
 * below the smallest SEE_i used: the error of these equations is mostly a person-specific bias (economy, heart-rate
 * response), which repeating a test does not average out.
 *
 * **MAS**: the latest valid maximal test; without one, VO2max / 3.5 marked as estimated.
 *
 * **Measured maximum heart rate**: the highest peak heart rate among valid maximal tests (the running test and the
 * walking heart-rate test) whose effort was [EffortAssessment.Maximal] (a peak from a submaximal effort is not a
 * maximum). A maximal effort is either a peak
 * close to the maximum used by the test or a confirmed exhaustion with a heart-rate plateau (see
 * [MaxIncrementalProtocol]), so the measured value may be below the estimate. How it competes with the manual value
 * and the estimate: [HeartRateMaxPolicy]. A peak more than [FitnessFormulas.HR_MAX_FLOOR_BELOW_ESTIMATE_BPM] below the
 * Tanaka estimate for the age at the test is not adopted (the result itself still counts for VO2max and MAS).
 */
object FitnessProfileCalculator {

    private const val DAY_MS = 86_400_000L

    fun compute(
        results: List<FitnessTestResult>,
        nowMillis: Long,
        ageYears: Int? = null,
        sex: Sex? = null,
        rules: FitnessProfileRules = FitnessProfileRules(),
    ): FitnessProfile {
        val usable = results
            .filter { it.validity == Validity.Valid && it.outputs != null }
            .map { it to ageDays(it, nowMillis) }
            .filter { (_, days) -> days <= rules.maxAgeDays }

        // -- VO2max
        data class Weighted(val r: FitnessTestResult, val days: Double, val see: Double, val w: Double)
        val weighted = usable.map { (r, days) ->
            val o = checkNotNull(r.outputs)
            val warned = r.flags.any { it.severity == FlagSeverity.Warning } || o.effort == EffortAssessment.NotMaximal
            val see = o.vo2maxSeeMlKgMin * if (warned) rules.warningSeeFactor else 1.0
            Weighted(r, days, see, 0.5.pow(days / rules.halfLifeDays) / (see * see))
        }
        val vo2 = if (weighted.isEmpty()) {
            null
        } else {
            val total = weighted.sumOf { it.w }
            val value = weighted.sumOf { it.w * checkNotNull(it.r.outputs).vo2maxMlKgMin } / total
            val spread = sqrt(weighted.sumOf { it.w * it.w * it.see * it.see }) / total
            val see = maxOf(spread, weighted.minOf { it.see })
            val rounded = FitnessFormulas.round1(value)
            Vo2MaxEstimate(
                valueMlKgMin = rounded,
                seeMlKgMin = FitnessFormulas.round2(see),
                classification = if (ageYears != null && sex != null) Vo2MaxNorms.classify(rounded, ageYears, sex) else null,
                sources = weighted.sortedByDescending { it.r.startedAtMillis }.map {
                    val o = checkNotNull(it.r.outputs)
                    Vo2MaxSource(
                        resultId = it.r.id,
                        testType = it.r.testType,
                        testedAtMillis = it.r.startedAtMillis,
                        vo2maxMlKgMin = o.vo2maxMlKgMin,
                        seeMlKgMin = FitnessFormulas.round2(it.see),
                        ageDays = it.days.toInt(),
                        weight = FitnessFormulas.round2(it.w / total),
                        lowerBound = o.vo2maxIsLowerBound,
                    )
                },
            )
        }

        // -- MAS
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

        // -- measured maximum heart rate: both maximal tests (the walking one has no outputs, only measurements)
        val hrMax = results
            .filter { it.validity == Validity.Valid && ageDays(it, nowMillis) <= rules.maxAgeDays }
            .filter { it.effortAssessment == EffortAssessment.Maximal && it.measuredHrMaxCandidateBpm != null }
            .filter { isAdoptableHrMax(checkNotNull(it.measuredHrMaxCandidateBpm), it.inputs.ageYears) }
            .maxWithOrNull(compareBy<FitnessTestResult> { it.measuredHrMaxCandidateBpm }.thenBy { it.startedAtMillis })
            ?.let { HrMaxEstimate(checkNotNull(it.measuredHrMaxCandidateBpm), it.id, it.startedAtMillis) }

        return FitnessProfile(nowMillis, vo2, mas, hrMax, rules)
    }

    /**
     * Whether a peak heart rate of a maximal effort is plausible enough to become the measured maximum of a person of
     * [ageYears] (see [FitnessFormulas.HR_MAX_FLOOR_BELOW_ESTIMATE_BPM]).
     */
    fun isAdoptableHrMax(peakBpm: Int, ageYears: Int): Boolean =
        peakBpm >= FitnessFormulas.tanakaHrMax(ageYears) - FitnessFormulas.HR_MAX_FLOOR_BELOW_ESTIMATE_BPM

    /** Days between the test and [now]; a test dated in the future (clock differences between devices) counts as 0. */
    private fun ageDays(r: FitnessTestResult, now: Long): Double = maxOf(0L, now - r.startedAtMillis).toDouble() / DAY_MS
}
