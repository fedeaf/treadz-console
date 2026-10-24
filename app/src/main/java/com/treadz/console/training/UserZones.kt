package com.treadz.console.training

import com.treadz.console.analysis.fitness.SelfRatedFitness
import com.treadz.console.analysis.zones.ZoneSet
import com.treadz.console.programs.fitness.FitnessProfile
import com.treadz.console.programs.fitness.HeartRateMaxDecision
import com.treadz.console.programs.fitness.ProfileZones
import com.treadz.console.programs.heartrate.HeartRateProfile
import com.treadz.console.userdata.FitnessLevel
import com.treadz.console.userdata.Sex
import com.treadz.console.userdata.UserData
import com.treadz.console.userdata.UserProfile
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.combine
import java.time.LocalDate

/**
 * The user's training zones as the rest of the app sees them: the same heart-rate profile the generators use (the
 * effective maximum of the policy, else the estimate from the age; the resting reference of the history) through
 * [ProfileZones].
 */
object UserZones {

    fun compute(
        user: UserProfile,
        fitnessProfile: FitnessProfile?,
        heartRateMax: HeartRateMaxDecision?,
        restingBpm: Int?,
        today: LocalDate,
    ): ZoneSet = ProfileZones.compute(
        fitnessProfile = fitnessProfile,
        heartRate = HeartRateProfile(heartRateMax?.bpm ?: user.estimatedMaxHeartRateBpm(today), restingBpm),
        selfRated = selfRatedOf(user.fitnessLevel),
        ageYears = user.ageOn(today),
        sex = if (user.sex == Sex.Male) com.treadz.console.analysis.fitness.Sex.Male else com.treadz.console.analysis.fitness.Sex.Female,
    )

    /**
     * The heart-rate references the engine runs a program with: the effective maximum, the resting reference, the VT1 and the
     * zones themselves (for targets that name a zone of the profile). `null` when the maximum is unknown. The previews and the
     * editor build the profile with this, so what they show in bpm is what the session will use.
     */
    fun heartRateProfile(
        user: UserProfile,
        fitnessProfile: FitnessProfile?,
        heartRateMax: HeartRateMaxDecision?,
        restingBpm: Int?,
        today: LocalDate,
    ): HeartRateProfile? {
        val decision = heartRateMax ?: return null
        val zones = compute(user, fitnessProfile, decision, restingBpm, today)
        return HeartRateProfile(decision.bpm, restingBpm, zones.vt1Bpm, zones)
    }

    /** [heartRateProfile] of [user], following every change of what it is made of. */
    fun heartRateProfileFlow(userData: UserData, user: UserProfile, today: () -> LocalDate): Flow<HeartRateProfile?> =
        combine(
            userData.fitnessTests.liveFitnessProfile(user.id),
            userData.fitnessTests.heartRateMax(user.id),
            userData.restingHeartRates.reference(user.id),
        ) { fitness, max, resting -> heartRateProfile(user, fitness, max, resting?.bpm, today()) }

    /** The self-rated level the zone model and the generators work with (stored values unchanged). */
    fun selfRatedOf(level: FitnessLevel?): SelfRatedFitness? = when (level) {
        FitnessLevel.Beginner -> SelfRatedFitness.Beginner
        FitnessLevel.Intermediate -> SelfRatedFitness.Intermediate
        FitnessLevel.Advanced -> SelfRatedFitness.Advanced
        null -> null
    }
}
