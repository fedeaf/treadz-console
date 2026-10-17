package com.treadz.console.analysis.estimate

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
 */
data class EstimateInput(
    val value: Double,
    val see: Double,
    val ageDays: Double,
    val warned: Boolean = false,
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
 * Each input i has a weight `w_i = 0.5^(age_i / halfLife) / SEE_i²`, where SEE_i is the method's SEE multiplied by the
 * warning factor when the input has a warning. Combined = Σ w_i·v_i / Σ w_i. Its uncertainty is
 * √(Σ w_i²·SEE_i²) / Σ w_i, but never below the smallest SEE_i used: the error of these equations is mostly a
 * person-specific bias (economy, heart-rate response), which repeating a measurement does not average out.
 */
object EstimateCombiner {

    /** The combined estimate, or `null` when there are no [inputs]. */
    fun combine(inputs: List<EstimateInput>, rules: CombinationRules): CombinedEstimate? {
        class Weighted(val value: Double, val see: Double, val w: Double)

        val weighted = inputs.map {
            val see = it.see * if (it.warned) rules.warningSeeFactor else 1.0
            Weighted(it.value, see, 0.5.pow(it.ageDays / rules.halfLifeDays) / (see * see))
        }
        if (weighted.isEmpty()) return null
        val total = weighted.sumOf { it.w }
        val value = weighted.sumOf { it.w * it.value } / total
        val spread = sqrt(weighted.sumOf { it.w * it.w * it.see * it.see }) / total
        val see = maxOf(spread, weighted.minOf { it.see })
        return CombinedEstimate(value, see, weighted.map { CombinedInput(it.see, it.w / total) })
    }
}
