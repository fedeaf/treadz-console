package com.treadz.console.programs.fitness

import com.treadz.console.analysis.fitness.SelfRatedFitness
import com.treadz.console.analysis.fitness.Sex
import com.treadz.console.analysis.fitness.Vo2MaxNorms
import com.treadz.console.analysis.zones.TrainingZones
import com.treadz.console.analysis.zones.ZoneSet
import com.treadz.console.programs.heartrate.HeartRateProfile

/**
 * The user's training zones ([TrainingZones]) from the app's data: the one call for the profile screen and the
 * generators.
 *
 * Source, by priority: the measured thresholds of [fitnessProfile] (only high- and medium-quality tests reach it) → the
 * classification of its VO2max (always from tests; classified for [ageYears] and [sex] when the profile was computed
 * without them) → [selfRated] → the low level.
 */
object ProfileZones {

    fun compute(
        fitnessProfile: FitnessProfile?,
        heartRate: HeartRateProfile,
        selfRated: SelfRatedFitness?,
        ageYears: Int? = null,
        sex: Sex? = null,
    ): ZoneSet {
        val vo2 = fitnessProfile?.vo2max
        val category = vo2?.classification?.category
            ?: if (vo2 != null && ageYears != null && sex != null) Vo2MaxNorms.classify(vo2.valueMlKgMin, ageYears, sex).category else null
        return TrainingZones.compute(
            maxBpm = heartRate.maxBpm,
            restingBpm = heartRate.usableRestingBpm,
            vt1Bpm = fitnessProfile?.thresholds?.vt1?.heartRateBpm,
            vt2Bpm = fitnessProfile?.thresholds?.vt2?.heartRateBpm,
            testedVo2maxCategory = category,
            selfRated = selfRated,
        )
    }
}
