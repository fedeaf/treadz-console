package com.treadz.console.analysis.fitness

/**
 * ACSM metabolic equations (ACSM's Guidelines for Exercise Testing and Prescription), with S the speed in m/min and G
 * the grade as a fraction, VO2 in ml·kg⁻¹·min⁻¹:
 *  - walking: `VO2 = 3.5 + 0.1·S + 1.8·S·G`
 *  - running: `VO2 = 3.5 + 0.2·S + 0.9·S·G`
 */
object Acsm {

    /** Resting oxygen consumption, 1 MET: 3.5 ml·kg⁻¹·min⁻¹. */
    const val REST_VO2 = 3.5

    fun walkingVo2(speedKmh: Double, inclinePercent: Double): Double {
        val s = speedKmh * 1000.0 / 60.0
        return REST_VO2 + 0.1 * s + 1.8 * s * inclinePercent / 100.0
    }

    fun runningVo2(speedKmh: Double, inclinePercent: Double): Double {
        val s = speedKmh * 1000.0 / 60.0
        return REST_VO2 + 0.2 * s + 0.9 * s * inclinePercent / 100.0
    }

    /** Walking grade (a fraction, may be negative or above any limit) that gives [vo2] at [speedKmh]. */
    fun walkingGradeFor(vo2: Double, speedKmh: Double): Double {
        val s = speedKmh * 1000.0 / 60.0
        return (vo2 - REST_VO2 - 0.1 * s) / (1.8 * s)
    }

    /** Walking speed (km/h) that gives [vo2] at [grade] (a fraction); with 0 it is the flat-walking speed. */
    fun walkingSpeedFor(vo2: Double, grade: Double): Double = (vo2 - REST_VO2) / (0.1 + 1.8 * grade) * 60.0 / 1000.0

    /**
     * Speed increase that raises the oxygen demand of walking as much as [gradeStepPercent] more grade, at [speedKmh]
     * and [gradePercent], with the ACSM walking equation VO2 = 3.5 + 0.1·S + 1.8·S·G (S in m/min, G a fraction):
     * ΔS = 1.8·S·ΔG / (0.1 + 1.8·G). Rounded to 0.1 km/h, at least 0.1. At 5.5 km/h and 18 %: +0.2 km/h per 1 %.
     */
    fun walkingSpeedStepForGrade(speedKmh: Double, gradePercent: Double, gradeStepPercent: Double = 1.0): Double {
        val s = speedKmh * 1000.0 / 60.0
        val g = gradePercent / 100.0
        val ds = 1.8 * s * (gradeStepPercent / 100.0) / (0.1 + 1.8 * g)
        return maxOf(0.1, FitnessFormulas.round1(ds * 60.0 / 1000.0))
    }
}
