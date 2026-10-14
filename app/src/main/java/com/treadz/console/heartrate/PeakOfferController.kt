package com.treadz.console.heartrate

import com.treadz.console.programs.fitness.HeartRateMaxPolicy
import com.treadz.console.programs.fitness.ObservedPeakOffer
import com.treadz.console.settings.SettingsStore
import com.treadz.console.userdata.DataResult
import com.treadz.console.userdata.ProfileInput
import com.treadz.console.userdata.UserData
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.update

/**
 * The observed-peak offer ("Your heart rate reached X bpm, above your maximum Y. Use X as your maximum?") of every user.
 * The decision is `HeartRateMaxPolicy.observedPeakOffer`; this class feeds it with the stored data and keeps the
 * answers: **yes** saves the peak as the manual maximum (the profile update stamps its date, so the same peak is not
 * offered again), **no** stores the moment of the answer per user in the settings, so a peak recorded before it is not
 * offered again either.
 */
class PeakOfferController(
    private val userData: UserData,
    private val settings: SettingsStore,
    private val nowMillis: () -> Long = { System.currentTimeMillis() },
) {
    /** Bumped on every answer: the answers live in the settings, which are not observable. */
    private val answers = MutableStateFlow(0)

    /** The offer for [userId], or `null` (also while the data loads and for an unknown user). */
    fun offer(userId: Long): Flow<ObservedPeakOffer?> = combine(
        userData.fitnessTests.heartRateMax(userId),
        userData.workouts.highestHeartRatePeak(userId),
        userData.profiles.profile(userId),
        answers,
    ) { decision, peak, profile, _ ->
        if (decision == null || profile == null) {
            null
        } else {
            HeartRateMaxPolicy.observedPeakOffer(
                decision = decision,
                peakBpm = peak?.bpm,
                peakAtMillis = peak?.atMillis,
                workoutId = peak?.workoutId,
                manualSetAtMillis = profile.maxHeartRateSetAtMillis,
                answeredAtMillis = settings.peakAnsweredAtMillis(userId),
            )
        }
    }.distinctUntilChanged()

    /** The offer for the active user. */
    @OptIn(ExperimentalCoroutinesApi::class)
    val activeOffer: Flow<ObservedPeakOffer?> =
        userData.profiles.activeUser.flatMapLatest { user -> if (user == null) flowOf(null) else offer(user.id) }

    /** "Yes": [offer]'s peak becomes the manual maximum heart rate. `false` if it could not be saved (the offer stays). */
    suspend fun accept(userId: Long, offer: ObservedPeakOffer): Boolean {
        val profile = userData.profiles.profile(userId).first() ?: return false
        val input = ProfileInput(
            name = profile.name,
            sex = profile.sex,
            heightCm = profile.heightCm,
            birthDate = profile.birthDate,
            // The reference of the history, so the update adds no resting measurement.
            restingHeartRateBpm = profile.restingHeartRateBpm,
            maxHeartRateBpm = offer.peakBpm,
            fitnessLevel = profile.fitnessLevel,
            goal = profile.goal,
        )
        val saved = userData.profiles.update(userId, input) is DataResult.Success
        if (saved) answers.update { it + 1 }
        return saved
    }

    /** "No": this peak (and any earlier one) is not offered again. */
    fun decline(userId: Long) {
        settings.setPeakAnsweredAtMillis(userId, nowMillis())
        answers.update { it + 1 }
    }
}
