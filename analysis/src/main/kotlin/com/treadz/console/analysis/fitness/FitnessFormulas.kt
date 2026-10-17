package com.treadz.console.analysis.fitness

import kotlin.math.floor
import kotlin.math.roundToInt

/** Equations and constants of the fitness tests. All VO2 values in ml·kg⁻¹·min⁻¹. */
object FitnessFormulas {

    /** Kilometres per mile (international mile). */
    const val KM_PER_MILE = 1.609344

    fun kmhToMph(kmh: Double): Double = kmh / KM_PER_MILE

    /**
     * Age-predicted maximum heart rate, 220 − age. It is the reference the Ebbeling study used for its 50-70 % band,
     * kept for fidelity with the original protocol (the app's own maximum, e.g. Tanaka or measured, is used for the
     * safety limit instead). Its error is large (SD ~10-12 bpm), which is one more reason the band is wide.
     */
    fun agePredictedHrMax(ageYears: Int): Int = 220 - ageYears

    /** Maximum heart rate estimated from the age with the Tanaka formula, `208 − 0.7 · age` (the profile's estimate). */
    fun tanakaHrMax(ageYears: Int): Int = (208.0 - 0.7 * ageYears).roundToInt()

    /**
     * A measured maximum more than this below the Tanaka estimate is not adopted (approved by the user, 2026-10-12): it is
     * about twice the spread of the estimate, so it more likely comes from a strap or effort problem than from the person.
     */
    const val HR_MAX_FLOOR_BELOW_ESTIMATE_BPM = 25

    // ------------------------------------------------------------------ Ebbeling

    /** Standard error of estimate of the Ebbeling equation (Ebbeling et al. 1991). */
    const val EBBELING_SEE = 4.85

    /** Ages of the population the Ebbeling equation was validated on. */
    val EBBELING_VALIDATED_AGES = 20..59

    /**
     * Ebbeling single-stage treadmill walking test:
     *
     * VO2max = 15.1 + 21.8·v − 0.327·HR − 0.263·v·age + 0.00504·HR·age + 5.98·sex
     *
     * with v the walking speed in **mph**, HR the steady-state heart rate of the 5 % stage in bpm, age in years and
     * sex 1 for men, 0 for women. SEE 4.85 ml·kg⁻¹·min⁻¹.
     *
     * Source: Ebbeling CB, Ward A, Puleo EM, Widrick J, Rippe JM. Development of a single-stage submaximal treadmill
     * walking test. Med Sci Sports Exerc. 1991;23(8):966-973.
     */
    fun ebbelingVo2max(speedMph: Double, steadyStateHr: Double, ageYears: Int, sex: Sex): Double {
        val v = speedMph
        val hr = steadyStateHr
        val age = ageYears.toDouble()
        val s = if (sex == Sex.Male) 1.0 else 0.0
        return 15.1 + 21.8 * v - 0.327 * hr - 0.263 * v * age + 0.00504 * hr * age + 5.98 * s
    }

    // ------------------------------------------------------------------ maximal incremental test

    /**
     * Ratio between VO2max and the maximal aerobic speed: VO2max ≈ 3.5 · MAS (km/h).
     *
     * Source: Léger L, Mercier D. Gross energy cost of horizontal treadmill and track running. Sports Med.
     * 1984;1(4):270-277 (running costs about 3.5 ml·kg⁻¹·min⁻¹ per km/h, i.e. ~1 kcal·kg⁻¹·km⁻¹ gross).
     *
     * Limits: it assumes an average running economy. Economy varies between people by ~5-10 %, so the estimate is
     * off by that much for an economical or uneconomical runner; it applies to track running or to a treadmill at
     * 1 % grade (which compensates the missing air resistance, Jones and Doust 1996); and the MAS of an incremental
     * test depends on the protocol (stage length and increment), here 1 min and 0.5 km/h.
     */
    const val LEGER_MERCIER_RATIO = 3.5

    /**
     * Standard error assumed for the VO2max derived from the MAS, ml·kg⁻¹·min⁻¹. Not a published figure for this
     * exact protocol: it reflects the ~7-8 % between-person variation of running economy at typical VO2max values
     * (45-55). **This is an assumption, not a measured value** (the user approved it on 2026-10-08, together with the
     * rest of the weighting of the fitness profile). Used only to weigh the tests against each other in the
     * fitness profile: with it a maximal test weighs about 1.5 times an Ebbeling test of the same age.
     */
    const val MAS_VO2MAX_SEE = 4.0

    fun vo2maxFromMas(masKmh: Double): Double = LEGER_MERCIER_RATIO * masKmh

    fun masFromVo2max(vo2max: Double): Double = vo2max / LEGER_MERCIER_RATIO

    /**
     * Maximal aerobic speed of an incremental test whose stages last [stageSeconds] and add [incrementKmh]:
     * the speed of the last completed stage plus the fraction of the incomplete stage run, times the increment.
     *
     * [incrementalSeconds] is the time run in the incremental phase, the first stage being at [startSpeedKmh].
     * Returns `null` when not even one stage was completed.
     */
    fun mas(startSpeedKmh: Double, incrementKmh: Double, stageSeconds: Int, incrementalSeconds: Double): Double? {
        val completed = floor(incrementalSeconds / stageSeconds).toInt()
        if (completed < 1) return null
        val lastCompletedSpeed = startSpeedKmh + incrementKmh * (completed - 1)
        val intoIncomplete = incrementalSeconds - completed * stageSeconds
        return lastCompletedSpeed + intoIncomplete / stageSeconds * incrementKmh
    }

    /** Rounds to one decimal. */
    fun round1(v: Double): Double = (v * 10).roundToInt() / 10.0

    /** Rounds to two decimals. */
    fun round2(v: Double): Double = (v * 100).roundToInt() / 100.0

    /** Rounds to three decimals. */
    fun round3(v: Double): Double = (v * 1000).roundToInt() / 1000.0
}
