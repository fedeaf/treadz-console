package com.treadz.console.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.treadz.console.heartrate.PeakOfferController
import com.treadz.console.programs.fitness.FitnessTestType
import com.treadz.console.programs.fitness.HeartRateMaxDecision
import com.treadz.console.programs.fitness.HeartRateMaxOrigin
import com.treadz.console.programs.fitness.ObservedPeakOffer
import com.treadz.console.programs.fitness.HeartRateSourceKind
import com.treadz.console.userdata.UserData
import com.treadz.console.userdata.UserProfile
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import java.time.LocalDate

/** Where one maximum heart rate value of the list comes from. */
sealed interface HeartRateMaxKind {
    /** The value the user entered (or accepted from an observed peak). */
    data object Manual : HeartRateMaxKind

    /** The measured maximum of a maximal fitness test. */
    data class Test(val testId: String, val type: FitnessTestType) : HeartRateMaxKind

    /** The highest peak seen in a workout lately. */
    data class ObservedPeak(val workoutId: Long, val source: HeartRateSourceKind?) : HeartRateMaxKind

    /** Calculated from the age. */
    data object AgeEstimate : HeartRateMaxKind
}

/** One line of the list of measurements; [inUse] marks the one the app uses now. */
data class HeartRateMaxRow(val kind: HeartRateMaxKind, val bpm: Int, val atMillis: Long?, val inUse: Boolean)

data class HeartRateMaxUiState(
    val loaded: Boolean = false,
    val user: UserProfile? = null,
    /** The value in use and its origin. */
    val decision: HeartRateMaxDecision? = null,
    val rows: List<HeartRateMaxRow> = emptyList(),
    /** An observed peak above the maximum in use, offered as the new maximum. */
    val offer: ObservedPeakOffer? = null,
)

/**
 * The maximum heart rate of a user: the value in use, where it comes from and every measurement it could come from
 * (maximal tests, the highest peak seen in a workout, the value typed by the user and the estimate from the age).
 */
class HeartRateMaxViewModel(
    private val profileId: Long,
    private val userData: UserData,
    private val peakOffers: PeakOfferController,
    private val today: () -> LocalDate,
) : ViewModel() {

    val uiState: StateFlow<HeartRateMaxUiState> = combine(
        userData.profiles.profile(profileId),
        userData.fitnessTests.heartRateMax(profileId),
        userData.fitnessTests.results(profileId),
        userData.workouts.highestHeartRatePeak(profileId),
        peakOffers.offer(profileId),
    ) { profile, decision, results, peak, offer ->
        if (profile == null) {
            HeartRateMaxUiState(loaded = true)
        } else {
            val rows = buildList {
                profile.maxHeartRateBpm?.let {
                    add(HeartRateMaxRow(HeartRateMaxKind.Manual, it, profile.maxHeartRateSetAtMillis, inUse = decision?.origin == HeartRateMaxOrigin.Manual))
                }
                results.mapNotNull { record -> record.result.measuredHrMaxCandidateBpm?.let { record to it } }
                    .sortedByDescending { it.first.result.startedAtMillis }
                    .forEach { (record, bpm) ->
                        val used = decision?.origin == HeartRateMaxOrigin.Measured && decision.measured?.sourceResultId == record.id
                        add(HeartRateMaxRow(HeartRateMaxKind.Test(record.id, record.result.testType), bpm, record.result.startedAtMillis, used))
                    }
                peak?.let { add(HeartRateMaxRow(HeartRateMaxKind.ObservedPeak(it.workoutId, it.source), it.bpm, it.atMillis, inUse = false)) }
                add(HeartRateMaxRow(HeartRateMaxKind.AgeEstimate, profile.estimatedMaxHeartRateBpm(today()), null, inUse = decision?.origin == HeartRateMaxOrigin.Estimated))
            }
            HeartRateMaxUiState(loaded = true, user = profile, decision = decision, rows = rows, offer = offer)
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, HeartRateMaxUiState())

    fun acceptOffer() {
        val offer = uiState.value.offer ?: return
        viewModelScope.launch { peakOffers.accept(profileId, offer) }
    }

    fun declineOffer() = peakOffers.decline(profileId)
}
