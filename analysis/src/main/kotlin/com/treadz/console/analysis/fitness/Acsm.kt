package com.treadz.console.analysis.fitness

/**
 * ACSM metabolic equations (ACSM's Guidelines for Exercise Testing and Prescription), with S the speed in m/min and G
 * the grade as a fraction, VO2 in ml·kg⁻¹·min⁻¹:
 *  - walking: `VO2 = 3.5 + 0.1·S + 1.8·S·G`
 *  - running: `VO2 = 3.5 + 0.2·S + 0.9·S·G`
 *
 * Which one applies is decided by the speed: above [RUNNING_SPEED_THRESHOLD_KMH] the running equation, at or below it
 * the walking one (ACSM: the walking equation is meant for 50-100 m/min, the running one above 134 m/min or for real
 * jogging at lower speeds, so between both the gait is unknown). The cut, 7 km/h, is the one of the energy expenditure
 * ([com.treadz.console.analysis.energy.EnergyExpenditure]): one gait for both (decided with the user, 2026-10-26; it was
 * 7.5 km/h here until then). Even walked, 7-7.5 km/h costs about what the running equation gives (Looney et al. 2019:
 * about 6.5 METs at 7.2 km/h; ACSM walking 4.4, running 7.9). Above 11.1 % of grade the running equation gives less than
 * the walking one, so the demand steps down when the speed passes the cut: that is the change of gait the cut assumes.
 * A test that is walked by definition (the maximal walking test) uses the walking equation at any speed ([walkingVo2],
 * [walkingVo2OfMeans]): its VO2max must never fall because the speed rose.
 */
object Acsm {

    /** Resting oxygen consumption, 1 MET: 3.5 ml·kg⁻¹·min⁻¹. */
    const val REST_VO2 = 3.5

    /** Metres per minute in one km/h. */
    private const val M_PER_MIN = 1000.0 / 60.0

    /** Above this speed (km/h) the running equation is used, at or below it the walking one. */
    const val RUNNING_SPEED_THRESHOLD_KMH = 7.0

    /** Whether [speedKmh] is counted as running for the choice of equation. */
    fun isRunning(speedKmh: Double): Boolean = speedKmh > RUNNING_SPEED_THRESHOLD_KMH

    /** The walking or running equation, chosen by [speedKmh] ([isRunning]). */
    fun vo2(speedKmh: Double, inclinePercent: Double): Double =
        if (isRunning(speedKmh)) runningVo2(speedKmh, inclinePercent) else walkingVo2(speedKmh, inclinePercent)

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

    /**
     * The equation ([isRunning] by [meanSpeedKmh]) applied to time-weighted means: it is linear in S and S·G, so the
     * mean oxygen demand of a stretch is the equation of the mean speed and of the mean product speed × incline
     * ([meanSpeedTimesInclinePercent], km/h × %).
     */
    fun vo2OfMeans(meanSpeedKmh: Double, meanSpeedTimesInclinePercent: Double): Double =
        if (isRunning(meanSpeedKmh)) runningVo2OfMeans(meanSpeedKmh, meanSpeedTimesInclinePercent)
        else walkingVo2OfMeans(meanSpeedKmh, meanSpeedTimesInclinePercent)

    /** The walking equation of time-weighted means, at any speed (see [vo2OfMeans]). */
    fun walkingVo2OfMeans(meanSpeedKmh: Double, meanSpeedTimesInclinePercent: Double): Double =
        REST_VO2 + 0.1 * meanSpeedKmh * M_PER_MIN + 1.8 * meanSpeedTimesInclinePercent * M_PER_MIN / 100.0

    private fun runningVo2OfMeans(meanSpeedKmh: Double, meanSpeedTimesInclinePercent: Double): Double =
        REST_VO2 + 0.2 * meanSpeedKmh * M_PER_MIN + 0.9 * meanSpeedTimesInclinePercent * M_PER_MIN / 100.0

    /** Running speed (km/h) that gives [vo2] at [grade] (a fraction), from the running equation. */
    fun runningSpeedFor(vo2: Double, grade: Double): Double = (vo2 - REST_VO2) / (0.2 + 0.9 * grade) * 60.0 / 1000.0

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
