package com.treadz.console.analysis.fitness

/**
 * **VO2max without exercise testing**: the non-exercise model of Jackson et al. (1990) with BMI,
 * `VO2max = 56.363 + 1.921·PA-R − 0.381·age − 0.754·BMI + 10.987·sex` (sex 1 for men, 0 for women; ml·kg⁻¹·min⁻¹;
 * R ≈ 0.78, SEE about 5-6 ml·kg⁻¹·min⁻¹). Source: Jackson AS, Blair SN, Mahar MT, Wier LT, Ross RM, Stuteville JE.
 * Prediction of functional aerobic capacity without exercise testing. Med Sci Sports Exerc. 1990;22(6):863-870.
 *
 * PA-R is the 0-7 NASA/JSC physical activity rating (0: avoids walking or exertion; 1: walks for pleasure; 2: 10-60 min
 * a week of moderate activity; 3: over 1 h a week; 4: runs under 1 mile or under 30 min a week of vigorous activity;
 * 5: 1-5 miles or 30-60 min; 6: 5-10 miles or 1-3 h; 7: over 10 miles or over 3 h). The self-rated level maps to it as
 * [physicalActivityRating]: beginner 1, intermediate 4, advanced 6, unknown 1 (the conservative side).
 */
object NonExerciseVo2max {

    fun physicalActivityRating(level: SelfRatedFitness?): Int = when (level) {
        SelfRatedFitness.Beginner, null -> 1
        SelfRatedFitness.Intermediate -> 4
        SelfRatedFitness.Advanced -> 6
    }

    /** The Jackson et al. (1990) BMI equation, unrounded. */
    fun jacksonVo2max(physicalActivityRating: Int, ageYears: Int, bmi: Double, sex: Sex): Double =
        56.363 + 1.921 * physicalActivityRating - 0.381 * ageYears - 0.754 * bmi + 10.987 * (if (sex == Sex.Male) 1.0 else 0.0)

    fun bmi(heightCm: Double, weightKg: Double): Double = weightKg / ((heightCm / 100.0) * (heightCm / 100.0))
}
