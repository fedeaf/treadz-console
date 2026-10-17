package com.treadz.console.analysis.fitness

/** VO2max categories of the Cooper Institute tables, best first. */
enum class Vo2MaxCategory { Superior, Excellent, Good, Fair, Poor }

/** Classification of a VO2max for the user's age and sex. */
data class Vo2MaxClassification(
    val category: Vo2MaxCategory,
    /** The norm age group used, e.g. "40-49". */
    val ageGroup: String,
    /** The user's age is outside 20-79: the nearest group was used. */
    val ageOutsideNorms: Boolean,
)

/**
 * VO2max norms by age and sex (ml·kg⁻¹·min⁻¹), from The Cooper Institute (Dallas, Texas), as published in the
 * "VO2 Max. Standard Ratings" tables of Garmin's device manuals ("Data reprinted with permission from The Cooper
 * Institute"). Each row gives the lower bound of a category: Superior = 95th percentile, Excellent = 80th, Good = 60th,
 * Fair = 40th; below Fair is Poor.
 *
 * A value equal to a bound belongs to the higher category. Ages under 20 use the 20-29 row and ages over 79 the
 * 70-79 row, with [Vo2MaxClassification.ageOutsideNorms] set.
 */
object Vo2MaxNorms {

    private data class Row(val fromAge: Int, val toAge: Int, val superior: Double, val excellent: Double, val good: Double, val fair: Double)

    private val MALE = listOf(
        Row(20, 29, 55.4, 51.1, 45.4, 41.7),
        Row(30, 39, 54.0, 48.3, 44.0, 40.5),
        Row(40, 49, 52.5, 46.4, 42.4, 38.5),
        Row(50, 59, 48.9, 43.4, 39.2, 35.6),
        Row(60, 69, 45.7, 39.5, 35.5, 32.3),
        Row(70, 79, 42.1, 36.7, 32.3, 29.4),
    )

    private val FEMALE = listOf(
        Row(20, 29, 49.6, 43.9, 39.5, 36.1),
        Row(30, 39, 47.4, 42.4, 37.8, 34.4),
        Row(40, 49, 45.3, 39.7, 36.3, 33.0),
        Row(50, 59, 41.1, 36.7, 33.0, 30.1),
        Row(60, 69, 37.8, 33.0, 30.0, 27.5),
        Row(70, 79, 36.7, 30.9, 28.1, 25.9),
    )

    fun classify(vo2max: Double, ageYears: Int, sex: Sex): Vo2MaxClassification {
        val rows = if (sex == Sex.Male) MALE else FEMALE
        val row = rows.firstOrNull { ageYears in it.fromAge..it.toAge } ?: if (ageYears < rows.first().fromAge) rows.first() else rows.last()
        val outside = ageYears < rows.first().fromAge || ageYears > rows.last().toAge
        // Compare on one decimal, like the table, so 41.69999 does not fall below 41.7.
        val v = FitnessFormulas.round1(vo2max)
        val category = when {
            v >= row.superior -> Vo2MaxCategory.Superior
            v >= row.excellent -> Vo2MaxCategory.Excellent
            v >= row.good -> Vo2MaxCategory.Good
            v >= row.fair -> Vo2MaxCategory.Fair
            else -> Vo2MaxCategory.Poor
        }
        return Vo2MaxClassification(category, "${row.fromAge}-${row.toAge}", outside)
    }

    /** Lower bounds (Superior, Excellent, Good, Fair) for an age and sex, e.g. to draw a scale in the UI. */
    fun bounds(ageYears: Int, sex: Sex): Map<Vo2MaxCategory, Double> {
        val rows = if (sex == Sex.Male) MALE else FEMALE
        val row = rows.firstOrNull { ageYears in it.fromAge..it.toAge } ?: if (ageYears < rows.first().fromAge) rows.first() else rows.last()
        return linkedMapOf(
            Vo2MaxCategory.Superior to row.superior,
            Vo2MaxCategory.Excellent to row.excellent,
            Vo2MaxCategory.Good to row.good,
            Vo2MaxCategory.Fair to row.fair,
        )
    }
}
