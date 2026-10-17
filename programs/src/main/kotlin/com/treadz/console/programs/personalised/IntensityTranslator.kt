package com.treadz.console.programs.personalised

import com.treadz.console.analysis.fitness.Acsm
import com.treadz.console.programs.model.ProgramLimits
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Treadmill values for an intensity. [capped] is true when walking cannot reach the requested oxygen demand within
 * [IntensityTranslator.MAX_WALKING_INCLINE_PERCENT] and [IntensityTranslator.MAX_WALKING_SPEED_KMH].
 */
data class TreadmillTarget(
    val speedKmh: Double,
    val inclinePercent: Double,
    /** The oxygen demand asked for, ml·kg⁻¹·min⁻¹. */
    val vo2MlKgMin: Double,
    val capped: Boolean = false,
)

/**
 * The single translator from an intensity (% of VO2max, % of MAS, or a heart rate) to speed and incline. It gives the
 * start values of a session; heart-rate-guided blocks correct them during the run.
 *
 * ACSM metabolic equations ([Acsm.walkingVo2], [Acsm.runningVo2]; ACSM's Guidelines for Exercise Testing and
 * Prescription), with S the speed in m/min and G the grade as a fraction, VO2 in ml·kg⁻¹·min⁻¹:
 *  - walking: `VO2 = 3.5 + 0.1·S + 1.8·S·G`
 *  - running: `VO2 = 3.5 + 0.2·S + 0.9·S·G`
 *
 * **Walking** keeps the comfortable speed and solves the incline. When the demand is below flat walking at that speed
 * the speed is lowered (flat, at least [MIN_WALKING_SPEED_KMH]); when it needs more than [MAX_WALKING_INCLINE_PERCENT],
 * the incline stays there and the speed rises up to [MAX_WALKING_SPEED_KMH] (beyond that the target is [capped]).
 *
 * **Running** is at [RUNNING_INCLINE_PERCENT] (1 %, which makes up for the missing air resistance) and the speed comes
 * from the MAS: the ACSM running equation is linear in the speed above the resting 3.5, so with the MAS as the speed
 * of VO2max, `speed = MAS · (VO2 − 3.5) / (VO2max − 3.5)`. A fraction of the MAS is simply that fraction of it.
 *
 * Speeds are rounded to 0.1 km/h and inclines to 1 % (the treadmill's steps).
 */
object IntensityTranslator {

    const val RUNNING_INCLINE_PERCENT = 1.0
    const val DEFAULT_WALKING_SPEED_KMH = 5.0
    const val MIN_WALKING_SPEED_KMH = 3.0
    const val MAX_WALKING_SPEED_KMH = 6.5
    const val MAX_WALKING_INCLINE_PERCENT = 15.0

    /** Slowest speed generated for running work (slower than this is walking for most adults). */
    const val MIN_RUNNING_SPEED_KMH = 6.0

    /**
     * Walking at [walkingSpeedKmh] with the incline that gives [vo2] (see the class rules). [maxInclinePercent] lowers
     * the incline limit (the speed then rises sooner, up to [MAX_WALKING_SPEED_KMH]).
     */
    fun walking(vo2: Double, walkingSpeedKmh: Double, maxInclinePercent: Double = MAX_WALKING_INCLINE_PERCENT): TreadmillTarget {
        val w = walkingSpeedKmh
        val grade = Acsm.walkingGradeFor(vo2, w)
        return when {
            grade <= 0.0 -> {
                val flat = Acsm.walkingSpeedFor(vo2, 0.0)
                TreadmillTarget(round1(flat.coerceIn(min(MIN_WALKING_SPEED_KMH, w), w)), 0.0, vo2)
            }
            grade * 100.0 <= maxInclinePercent -> TreadmillTarget(round1(w), (grade * 100.0).roundToInt().toDouble(), vo2)
            else -> {
                val needed = Acsm.walkingSpeedFor(vo2, maxInclinePercent / 100.0)
                TreadmillTarget(round1(min(max(needed, w), max(w, MAX_WALKING_SPEED_KMH))), maxInclinePercent, vo2, capped = needed > max(w, MAX_WALKING_SPEED_KMH) + 0.05)
            }
        }
    }

    /** Running at 1 % with the speed that gives [vo2] (see the class rules). */
    fun running(vo2: Double, references: IntensityReferences): TreadmillTarget {
        val fraction = (vo2 - Acsm.REST_VO2) / (references.vo2maxMlKgMin - Acsm.REST_VO2)
        return TreadmillTarget(clampSpeed(fraction * references.masKmh), RUNNING_INCLINE_PERCENT, vo2)
    }

    /** The translator: [fraction] of VO2max (0.6 = 60 %). */
    fun forVo2maxFraction(fraction: Double, mode: ExerciseMode, references: IntensityReferences, walkingSpeedKmh: Double = DEFAULT_WALKING_SPEED_KMH): TreadmillTarget {
        val vo2 = fraction * references.vo2maxMlKgMin
        return when (mode) {
            ExerciseMode.Walk -> walking(vo2, walkingSpeedKmh)
            ExerciseMode.Run -> running(vo2, references)
        }
    }

    /**
     * [fraction] of the MAS. Running: that fraction of the MAS at 1 %. Walking: the same fraction of VO2max (with
     * VO2max = 3.5 · MAS the two are the same relative intensity).
     */
    fun forMasFraction(fraction: Double, mode: ExerciseMode, references: IntensityReferences, walkingSpeedKmh: Double = DEFAULT_WALKING_SPEED_KMH): TreadmillTarget =
        when (mode) {
            ExerciseMode.Walk -> walking(fraction * references.vo2maxMlKgMin, walkingSpeedKmh)
            ExerciseMode.Run -> TreadmillTarget(clampSpeed(fraction * references.masKmh), RUNNING_INCLINE_PERCENT, fraction * references.vo2maxMlKgMin)
        }

    /**
     * The fraction of VO2max that corresponds to a heart rate of [heartRateFraction] of the maximum.
     *  - With a resting heart rate: % of the heart-rate reserve ≈ % of the VO2 reserve (Swain DP, Leutholtz BC. Heart
     *    rate reserve is equivalent to %VO2 reserve, not to %VO2max. Med Sci Sports Exerc. 1997;29(3):410-414).
     *  - Without: `%HRmax = 0.6463·%VO2max + 37.182` (Swain DP, Abernathy KS, Smith CS, Lee SJ, Bunn SA. Target heart
     *    rates for the development of cardiorespiratory fitness. Med Sci Sports Exerc. 1994;26(1):112-116).
     * The result is kept within 0.2-1.0.
     */
    fun vo2maxFractionForHeartRate(heartRateFraction: Double, references: IntensityReferences): Double {
        val rest = references.restingHeartRateBpm
        val max = references.maxHeartRateBpm
        val fraction = if (rest != null && rest < max) {
            val reserve = (heartRateFraction * max - rest) / (max - rest)
            (Acsm.REST_VO2 + reserve * (references.vo2maxMlKgMin - Acsm.REST_VO2)) / references.vo2maxMlKgMin
        } else {
            (heartRateFraction * 100.0 - 37.182) / 0.6463 / 100.0
        }
        return fraction.coerceIn(0.2, 1.0)
    }

    private fun clampSpeed(v: Double): Double = round1(v.coerceIn(ProgramLimits.MIN_SPEED_KMH, ProgramLimits.MAX_SPEED_KMH))

    internal fun round1(v: Double): Double = (v * 10).roundToInt() / 10.0
}
