package com.treadz.console.programs.personalised

import com.treadz.console.analysis.fitness.FitnessFormulas
import com.treadz.console.analysis.fitness.NonExerciseVo2max
import com.treadz.console.analysis.fitness.Sex
import com.treadz.console.programs.fitness.HeartRateMaxOrigin

/**
 * The intensity references a generator works with, and whether each one is estimated.
 *
 * @property vo2maxMlKgMin From the fitness profile (tests), else the non-exercise estimate ([jackson]).
 * @property masKmh Maximal aerobic speed: measured by the maximal running test, else VO2max / 3.5.
 * @property maxHeartRateBpm The effective maximum heart rate given by the app.
 * @property jackson The non-exercise estimate, when it was needed.
 */
data class IntensityReferences(
    val vo2maxMlKgMin: Double,
    val vo2maxEstimated: Boolean,
    val masKmh: Double,
    val masEstimated: Boolean,
    val maxHeartRateBpm: Int,
    val maxHeartRateEstimated: Boolean,
    val restingHeartRateBpm: Int?,
    val jackson: NonExerciseEstimate?,
) {
    /**
     * Whether any of the references a generator uses is estimated. Then the generator lowers the intensity by
     * [IntensityEstimator.ESTIMATED_INTENSITY_REDUCTION] and flags the result ("estimated values: do a test to refine").
     */
    fun estimatedFor(usesVo2max: Boolean, usesMas: Boolean, usesMaxHeartRate: Boolean): Boolean =
        (usesVo2max && vo2maxEstimated) || (usesMas && masEstimated) || (usesMaxHeartRate && maxHeartRateEstimated)
}

/** The non-exercise VO2max estimate and what it was computed from. */
data class NonExerciseEstimate(
    val vo2maxMlKgMin: Double,
    val bmi: Double,
    /** True when height or weight was missing and [IntensityEstimator.DEFAULT_BMI] was used. */
    val bmiAssumed: Boolean,
    /** NASA/JSC physical activity rating (0-7) used, mapped from the self-rated fitness level. */
    val physicalActivityRating: Int,
)

/**
 * Builds the [IntensityReferences] from the profile, estimating what the fitness tests did not measure.
 *
 * **VO2max without tests**: the non-exercise model of Jackson et al. (1990) with BMI, see [NonExerciseVo2max]
 * (`VO2max = 56.363 + 1.921·PA-R − 0.381·age − 0.754·BMI + 10.987·sex`, R ≈ 0.78, SEE about 5-6 ml·kg⁻¹·min⁻¹). The
 * self-rated level maps to the activity rating PA-R as [NonExerciseVo2max.physicalActivityRating]: beginner 1,
 * intermediate 4, advanced 6, unknown 1 (the conservative side).
 *
 * The result is kept inside [MIN_VO2MAX]-[MAX_VO2MAX]. **MAS** without a maximal test is VO2max / 3.5 (Léger and
 * Mercier, see [FitnessFormulas.LEGER_MERCIER_RATIO]); when the profile has a measured MAS but no VO2max, VO2max is
 * 3.5 · MAS.
 */
object IntensityEstimator {

    /** Intensity reduction applied when a reference used is estimated: −10 %. */
    const val ESTIMATED_INTENSITY_REDUCTION = 0.10

    /** BMI assumed when height or weight is unknown (slightly overweight: the conservative side). */
    const val DEFAULT_BMI = 27.0

    const val MIN_VO2MAX = 15.0
    const val MAX_VO2MAX = 80.0

    fun nonExercise(profile: GeneratorProfile): NonExerciseEstimate {
        val h = profile.heightCm
        val w = profile.weightKg
        val known = h != null && w != null && h > 0 && w > 0
        val bmi = if (known) NonExerciseVo2max.bmi(checkNotNull(h), checkNotNull(w)) else DEFAULT_BMI
        val par = NonExerciseVo2max.physicalActivityRating(profile.selfRatedFitness)
        val value = NonExerciseVo2max.jacksonVo2max(par, profile.ageYears, bmi, profile.sex).coerceIn(MIN_VO2MAX, MAX_VO2MAX)
        return NonExerciseEstimate(FitnessFormulas.round1(value), FitnessFormulas.round1(bmi), !known, par)
    }

    fun references(profile: GeneratorProfile): IntensityReferences {
        val fp = profile.fitnessProfile
        val measuredVo2 = fp?.vo2max?.valueMlKgMin
        val measuredMas = fp?.mas?.takeIf { !it.estimated }?.valueKmh
        val jackson = if (measuredVo2 == null && measuredMas == null) nonExercise(profile) else null
        val vo2 = measuredVo2 ?: measuredMas?.let { FitnessFormulas.round1(FitnessFormulas.vo2maxFromMas(it)) } ?: checkNotNull(jackson).vo2maxMlKgMin
        val mas = measuredMas ?: FitnessFormulas.round2(FitnessFormulas.masFromVo2max(vo2))
        return IntensityReferences(
            vo2maxMlKgMin = vo2,
            vo2maxEstimated = jackson != null,
            masKmh = mas,
            masEstimated = measuredMas == null,
            maxHeartRateBpm = profile.heartRate.maxBpm,
            maxHeartRateEstimated = profile.maxHeartRateOrigin == HeartRateMaxOrigin.Estimated,
            restingHeartRateBpm = profile.heartRate.usableRestingBpm,
            jackson = jackson,
        )
    }
}
