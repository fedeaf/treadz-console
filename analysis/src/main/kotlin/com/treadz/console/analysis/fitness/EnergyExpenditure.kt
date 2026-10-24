package com.treadz.console.analysis.fitness

import kotlin.math.pow

/**
 * Gross energy expenditure on a treadmill (the resting part included), in kcal, from the speed, the incline and the
 * body weight.
 *
 * - **Walking** (up to [WALKING_MAX_KMH]): the Load Carriage Decision Aid equation with the grade term of Looney et al.,
 *   in W·kg⁻¹, S the speed in m/s and G the grade in %:
 *   `M = 1.44 + 1.94·S^0.43 + 0.24·S^4 + 0.34·S·G·(1 − 1.05^(1 − 1.1^(G + 32)))`.
 *   Fitted on 95 people of 11 studies, up to 1.96 m/s and from −40 % to 45 % (Looney DP et al., Estimating energy
 *   expenditure during level, uphill, and downhill walking. Med Sci Sports Exerc 2019;51:1954-60; level part: Looney DP
 *   et al., Med Sci Sports Exerc 2019;51:346-51). It was the most accurate empirical equation on level, uphill and
 *   downhill walking in an independent comparison (Cruz & Yang, PLoS One 2022), while the ACSM walking equation
 *   underestimates level walking (Ludlow & Weyand, J Appl Physiol 2016). 1 W = 1 J/s, 4184 J = 1 kcal.
 * - **Faster** (running): the ACSM running equation, `VO2 = 3.5 + 0.2·S + 0.9·S·G` (S in m/min, G a fraction), with
 *   [KCAL_PER_LITRE_OXYGEN].
 *
 * Only while the belt moves: a stopped belt adds nothing.
 */
object EnergyExpenditure {

    /** Up to this speed (km/h) the walking equation is used, above it the running one. */
    const val WALKING_MAX_KMH = 7.0

    /** Energy of one litre of oxygen (kcal), the usual round figure (ACSM). */
    const val KCAL_PER_LITRE_OXYGEN = 5.0

    private const val JOULES_PER_KCAL = 4184.0

    /** Metabolic rate of walking at [speedKmh] and [inclinePercent], in W·kg⁻¹ (Looney et al. 2019). */
    fun walkingWattsPerKg(speedKmh: Double, inclinePercent: Double): Double {
        val s = speedKmh / 3.6
        val g = inclinePercent
        return 1.44 + 1.94 * s.pow(0.43) + 0.24 * s.pow(4) + 0.34 * s * g * (1 - 1.05.pow(1 - 1.1.pow(g + 32)))
    }

    /** Gross kcal per minute for [weightKg] at [speedKmh] and [inclinePercent]; 0 with the belt stopped. */
    fun kcalPerMinute(speedKmh: Double, inclinePercent: Double, weightKg: Double): Double = when {
        speedKmh <= 0.0 || weightKg <= 0.0 -> 0.0
        speedKmh <= WALKING_MAX_KMH -> walkingWattsPerKg(speedKmh, inclinePercent) * weightKg * 60.0 / JOULES_PER_KCAL
        else -> Acsm.runningVo2(speedKmh, inclinePercent) * weightKg / 1000.0 * KCAL_PER_LITRE_OXYGEN
    }
}

/**
 * Adds up the gross energy of a workout as it goes: each stretch at a speed and incline counts for its duration
 * ([EnergyExpenditure.kcalPerMinute]). Not thread-safe.
 */
class EnergyCounter(private val weightKg: Double) {

    /** Gross kcal so far. */
    var kcal: Double = 0.0
        private set

    /** [seconds] at [speedKmh] and [inclinePercent]. */
    fun add(seconds: Double, speedKmh: Double, inclinePercent: Double) {
        if (seconds <= 0.0) return
        kcal += EnergyExpenditure.kcalPerMinute(speedKmh, inclinePercent, weightKg) * seconds / 60.0
    }
}
