package com.treadz.console.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.treadz.console.heartrate.PeakOfferController
import com.treadz.console.programs.fitness.ObservedPeakOffer
import com.treadz.console.userdata.RestingHeartRateReference
import com.treadz.console.userdata.UserData
import com.treadz.console.userdata.UserProfile
import com.treadz.console.userdata.WeightEntry
import com.treadz.console.userdata.WorkoutSummary
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/** What the home screen's shortcuts say about the active user. */
data class HomeUiState(
    val user: UserProfile? = null,
    /** The resting heart rate reference of the user's history, `null` while there is no measurement. */
    val restingReference: RestingHeartRateReference? = null,
    /** The latest weight entry (it may be the placeholder of the generic profile). */
    val latestWeight: WeightEntry? = null,
    /** The last finished workout. */
    val lastWorkout: WorkoutSummary? = null,
    /** "Your heart rate reached X, use it as your maximum?", `null` when there is no offer. */
    val peakOffer: ObservedPeakOffer? = null,
)

/** The data of the home screen (the shortcuts shown while the treadmill is not connected) and the peak offer answers. */
@OptIn(ExperimentalCoroutinesApi::class)
class HomeViewModel(
    private val userData: UserData,
    private val peakOffers: PeakOfferController,
) : ViewModel() {

    val uiState: StateFlow<HomeUiState> = userData.profiles.activeUser.flatMapLatest { user ->
        if (user == null) {
            flowOf(HomeUiState())
        } else {
            combine(
                userData.restingHeartRates.reference(user.id),
                userData.weights.latestWeight(user.id),
                userData.workouts.workouts(user.id).map { list -> list.firstOrNull { !it.isInProgress } },
                peakOffers.offer(user.id),
            ) { reference, weight, workout, offer -> HomeUiState(user, reference, weight, workout, offer) }
        }
    }.stateIn(viewModelScope, SharingStarted.Eagerly, HomeUiState())

    /** "Yes": the peak becomes the active user's manual maximum heart rate. */
    fun acceptPeakOffer() {
        val offer = uiState.value.peakOffer ?: return
        viewModelScope.launch {
            val user = userData.profiles.activeUser.first() ?: return@launch
            peakOffers.accept(user.id, offer)
        }
    }

    /** "No": this peak is not offered again. */
    fun declinePeakOffer() {
        val user = uiState.value.user ?: return
        peakOffers.decline(user.id)
    }
}
