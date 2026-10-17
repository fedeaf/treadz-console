package com.treadz.console.analysis.estimate

import kotlin.math.abs
import kotlin.math.pow
import kotlin.math.sqrt

/**
 * One estimate of the same quantity from one measurement.
 *
 * @property value The estimated value.
 * @property see Standard error of estimate of the method that produced it, in the units of [value].
 * @property ageDays Days since the measurement (0 or more).
 * @property warned Whether the measurement carries a warning: its SEE is then multiplied by
 *   [CombinationRules.warningSeeFactor].
 * @property weightFactor Extra factor on the weight (1 = none), for rules outside the SEE: e.g. a submaximal test when
 *   a recent maximal one exists, the quality of a threshold or a damped jump. 0 leaves the input out of the value.
 */
data class EstimateInput(
    val value: Double,
    val see: Double,
    val ageDays: Double,
    val warned: Boolean = false,
    val weightFactor: Double = 1.0,
)

/**
 * @property halfLifeDays An estimate loses half its weight every this many days.
 * @property warningSeeFactor The SEE of an estimate with a warning is multiplied by this (it weighs 1 / factor² as much).
 */
data class CombinationRules(
    val halfLifeDays: Double,
    val warningSeeFactor: Double,
)

/**
 * What the combination did with one input.
 *
 * @property seeUsed The SEE used for the weighting (the input's, times the warning factor with a warning).
 * @property weight Share of this input in the combined value (0-1, the shares of all inputs add up to 1).
 */
data class CombinedInput(val seeUsed: Double, val weight: Double)

/**
 * @property value The weighted mean, unrounded.
 * @property see Uncertainty of the combined value, unrounded (never below the smallest SEE used).
 * @property inputs One entry per input, in the order the inputs were given.
 */
data class CombinedEstimate(val value: Double, val see: Double, val inputs: List<CombinedInput>)

/**
 * Combination of several estimates of the same quantity: inverse-variance weighting with recency.
 *
 * Each input i has a weight `w_i = f_i · 0.5^(age_i / halfLife) / SEE_i²`, where SEE_i is the method's SEE multiplied by
 * the warning factor when the input has a warning and f_i its [EstimateInput.weightFactor] (1 by default). Combined = Σ w_i·v_i / Σ w_i. Its uncertainty is
 * √(Σ w_i²·SEE_i²) / Σ w_i, but never below the smallest SEE_i used: the error of these equations is mostly a
 * person-specific bias (economy, heart-rate response), which repeating a measurement does not average out.
 */
object EstimateCombiner {

    /** Two estimates are discrepant when they differ by more than this many times their combined SEE. */
    const val DISCREPANCY_FACTOR = 1.5

    /** The combined estimate, or `null` when there are no [inputs] (or all of them weigh 0). */
    fun combine(inputs: List<EstimateInput>, rules: CombinationRules): CombinedEstimate? {
        class Weighted(val value: Double, val see: Double, val w: Double)

        val weighted = inputs.map {
            val see = it.see * if (it.warned) rules.warningSeeFactor else 1.0
            Weighted(it.value, see, it.weightFactor.coerceAtLeast(0.0) * 0.5.pow(it.ageDays / rules.halfLifeDays) / (see * see))
        }
        if (weighted.isEmpty()) return null
        val total = weighted.sumOf { it.w }
        if (total <= 0.0) return null
        val value = weighted.sumOf { it.w * it.value } / total
        val spread = sqrt(weighted.sumOf { it.w * it.w * it.see * it.see }) / total
        val see = maxOf(spread, weighted.filter { it.w > 0.0 }.minOf { it.see })
        return CombinedEstimate(value, see, weighted.map { CombinedInput(it.see, it.w / total) })
    }

    /**
     * The pairs of estimates (indices into [values], first < second) that differ by more than
     * [factor]·√(SEE₁² + SEE₂²): the difference is then far beyond what the two errors explain, so one of the two
     * measurements is probably wrong. With the default 1.5 only gross discrepancies are marked (e.g. 43.6 ± 3.5 and
     * 48.9 ± 4.85 differ by 5.3, below 1.5·5.98 = 9.0). [sees] are the SEEs used for the weighting.
     */
    fun discrepantPairs(values: List<Double>, sees: List<Double>, factor: Double = DISCREPANCY_FACTOR): List<Pair<Int, Int>> {
        require(values.size == sees.size) { "values and sees must have the same size" }
        val pairs = mutableListOf<Pair<Int, Int>>()
        for (i in values.indices) {
            for (j in i + 1 until values.size) {
                if (abs(values[i] - values[j]) > factor * sqrt(sees[i] * sees[i] + sees[j] * sees[j])) pairs += i to j
            }
        }
        return pairs
    }
}
