package com.treadz.console.analysis.zones

import com.treadz.console.analysis.fitness.SelfRatedFitness
import com.treadz.console.analysis.fitness.Vo2MaxCategory
import com.treadz.console.analysis.heartrate.HeartRateBands
import kotlin.math.roundToInt

/** Fitness level that chooses the %-of-reserve zone table when there are no measured thresholds. */
enum class FitnessLevel { Low, Medium, High }

/** Where the zones come from, by priority. */
enum class ZoneSource {
    /** Measured VT1 (and VT2) of sufficient quality: the profile thresholds. */
    MeasuredThresholds,

    /** The level from the VO2max classification of the profile (from a test). */
    Vo2maxClassification,

    /** The level from the self-rated fitness of the profile. */
    SelfRatedFitness,

    /** Nothing known: the low level (the safe side). */
    Default,
}

/**
 * One zone in bpm.
 *
 * @property number 1-5.
 * @property lowBpm `null` for zone 1 (no lower bound).
 * @property highBpm The maximum heart rate for zone 5.
 */
data class ZoneRange(val number: Int, val lowBpm: Int?, val highBpm: Int)

/**
 * The five training zones.
 *
 * @property level The level of the table used (with measured thresholds, the level that gives VT2 when only VT1 was
 *   measured; otherwise informative).
 * @property usedReserve The table was applied as % of the heart-rate reserve (a resting heart rate was given); without
 *   it, as the equivalent % of the maximum. Not meaningful for [ZoneSource.MeasuredThresholds].
 * @property vt1Bpm The Z2/Z3 boundary; [vt2Bpm] the Z3/Z4 boundary (measured or from the table).
 * @property vt2FromTable Only VT1 was measured: VT2 comes from the level table.
 */
data class ZoneSet(
    val zones: List<ZoneRange>,
    val source: ZoneSource,
    val level: FitnessLevel,
    val usedReserve: Boolean,
    val vt1Bpm: Int,
    val vt2Bpm: Int,
    val vt2FromTable: Boolean = false,
) {
    fun zone(number: Int): ZoneRange = zones[number - 1]
}

/**
 * The training zone model (five zones in bpm) and its source.
 *
 * **Source, by priority**: measured thresholds (the profile thresholds, which only take high- and medium-quality tests)
 * → the VO2max classification of the profile, when it comes from a test (poor/fair → low, good → medium,
 * excellent/superior → high) → the self-rated fitness (beginner → low, intermediate → medium, advanced → high) →
 * the low level (the safe side).
 *
 * **Without measured thresholds** (% of the heart-rate reserve, Karvonen; boundaries Z1/Z2, Z2/Z3, Z3/Z4, Z4/Z5):
 *
 * | Level | Z1 | Z2 | Z3 | Z4 | Z5 |
 * |---|---|---|---|---|---|
 * | Low | < 45 | 45-55 | 55-75 | 75-90 | > 90 |
 * | Medium | < 50 | 50-60 | 60-80 | 80-90 | > 90 |
 * | High | < 55 | 55-65 | 65-85 | 85-90 | > 90 |
 *
 * Rationale: the % of the reserve is about the % of the VO2 reserve (Swain DP, Leutholtz BC. Heart rate reserve is
 * equivalent to %VO2 reserve, not to %VO2max. Med Sci Sports Exerc. 1997;29(3):410-414; ACSM's Guidelines). VT1 falls at
 * about 45-55 % of VO2max in sedentary people, 55-65 % in active ones and 65-75 % or more in trained ones; VT2 at about
 * 70-80 % untrained and 85-90 % trained. So the Z2/Z3 boundary is the typical VT1 of the level and Z3/Z4 the typical
 * VT2. Without a resting heart rate the table is applied as the equivalent % of the maximum
 * ([HeartRateBands.reserveToMaxPercent], resting assumed at a third of the maximum).
 *
 * **With measured thresholds**: Z1 < VT1 − 10; Z2 = VT1 − 10 … VT1; Z3 = VT1 … VT2; Z4 = VT2 … (VT2 + HRmax) / 2;
 * Z5 above. With only VT1 measured, VT2 comes from the table of the level (by the same priority, without the
 * thresholds); if that is not above VT1, VT2 is the middle between VT1 and the maximum (a safety guard, an assumption).
 */
object TrainingZones {

    /** Z2 spans this many bpm below VT1. */
    const val Z2_BELOW_VT1_BPM = 10

    /** %-of-reserve boundaries Z1/Z2, Z2/Z3, Z3/Z4, Z4/Z5 for each level. */
    val RESERVE_BOUNDARIES: Map<FitnessLevel, List<Int>> = mapOf(
        FitnessLevel.Low to listOf(45, 55, 75, 90),
        FitnessLevel.Medium to listOf(50, 60, 80, 90),
        FitnessLevel.High to listOf(55, 65, 85, 90),
    )

    fun levelOf(category: Vo2MaxCategory): FitnessLevel = when (category) {
        Vo2MaxCategory.Poor, Vo2MaxCategory.Fair -> FitnessLevel.Low
        Vo2MaxCategory.Good -> FitnessLevel.Medium
        Vo2MaxCategory.Excellent, Vo2MaxCategory.Superior -> FitnessLevel.High
    }

    fun levelOf(selfRated: SelfRatedFitness): FitnessLevel = when (selfRated) {
        SelfRatedFitness.Beginner -> FitnessLevel.Low
        SelfRatedFitness.Intermediate -> FitnessLevel.Medium
        SelfRatedFitness.Advanced -> FitnessLevel.High
    }

    /**
     * The zones for a person.
     *
     * @param maxBpm The effective maximum heart rate.
     * @param restingBpm A usable resting heart rate (plausible, below the maximum), or `null`.
     * @param vt1Bpm Measured VT1 of sufficient quality (the profile's), or `null`.
     * @param vt2Bpm Measured VT2 of sufficient quality, or `null` (ignored without [vt1Bpm]).
     * @param testedVo2maxCategory The VO2max classification of the profile when it comes from a test, or `null`.
     * @param selfRated The self-rated fitness, or `null`.
     */
    fun compute(
        maxBpm: Int,
        restingBpm: Int?,
        vt1Bpm: Int? = null,
        vt2Bpm: Int? = null,
        testedVo2maxCategory: Vo2MaxCategory? = null,
        selfRated: SelfRatedFitness? = null,
    ): ZoneSet {
        val rest = restingBpm?.takeIf { it < maxBpm }
        val (levelSource, level) = when {
            testedVo2maxCategory != null -> ZoneSource.Vo2maxClassification to levelOf(testedVo2maxCategory)
            selfRated != null -> ZoneSource.SelfRatedFitness to levelOf(selfRated)
            else -> ZoneSource.Default to FitnessLevel.Low
        }
        val table = checkNotNull(RESERVE_BOUNDARIES[level]).map { HeartRateBands.reserveBpm(maxBpm, rest, it) }
        if (vt1Bpm == null || vt1Bpm >= maxBpm) {
            return build(table[0], table[1], table[2], table[3], maxBpm, levelSource, level, rest != null, vt2FromTable = false)
        }
        val measuredVt2 = vt2Bpm?.takeIf { it > vt1Bpm && it < maxBpm }
        val vt2 = measuredVt2 ?: table[2].takeIf { it > vt1Bpm } ?: ((vt1Bpm + maxBpm) / 2.0).roundToInt()
        val z4High = ((vt2 + maxBpm) / 2.0).roundToInt()
        return build(vt1Bpm - Z2_BELOW_VT1_BPM, vt1Bpm, vt2, z4High, maxBpm, ZoneSource.MeasuredThresholds, level, rest != null, measuredVt2 == null)
    }

    private fun build(
        b1: Int, b2: Int, b3: Int, b4: Int, maxBpm: Int,
        source: ZoneSource, level: FitnessLevel, usedReserve: Boolean, vt2FromTable: Boolean,
    ) = ZoneSet(
        zones = listOf(
            ZoneRange(1, null, b1),
            ZoneRange(2, b1, b2),
            ZoneRange(3, b2, b3),
            ZoneRange(4, b3, b4),
            ZoneRange(5, b4, maxBpm),
        ),
        source = source,
        level = level,
        usedReserve = usedReserve,
        vt1Bpm = b2,
        vt2Bpm = b3,
        vt2FromTable = vt2FromTable,
    )
}
