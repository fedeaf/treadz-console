package com.treadz.console.training

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.treadz.console.analysis.zones.ZoneSet
import com.treadz.console.programs.fitness.FitnessProfile
import com.treadz.console.programs.fitness.HeartRateMaxDecision
import com.treadz.console.userdata.DataResult
import com.treadz.console.userdata.FitnessTestRecord
import com.treadz.console.userdata.UserData
import com.treadz.console.userdata.UserProfile
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate

data class FitnessTestsUiState(
    val loaded: Boolean = false,
    val user: UserProfile? = null,
    /** The active user's tests, newest first. */
    val results: List<FitnessTestRecord> = emptyList(),
    /** The active user's fitness profile as of today (VO2max, MAS, measured maximum heart rate). */
    val profile: FitnessProfile? = null,
    val heartRateMax: HeartRateMaxDecision? = null,
    /** The active user's five training zones in bpm and where they come from; `null` without a user. */
    val zones: ZoneSet? = null,
    val confirmingDeleteId: String? = null,
    val failed: Boolean = false,
)

/** The fitness tests entry: the two tests to choose from, the user's fitness figures and the list of their past tests. */
class FitnessTestsViewModel(
    private val userData: UserData,
    /** The date the age (and so the estimated maximum heart rate) is computed on. */
    private val today: () -> LocalDate = LocalDate::now,
) : ViewModel() {

    private data class Local(val confirmingDeleteId: String? = null, val failed: Boolean = false)

    private val local = MutableStateFlow(Local())

    private data class Content(
        val user: UserProfile?,
        val results: List<FitnessTestRecord>,
        val profile: FitnessProfile?,
        val hrMax: HeartRateMaxDecision?,
        val zones: ZoneSet?,
    )

    @OptIn(ExperimentalCoroutinesApi::class)
    private val content: Flow<Content> = userData.profiles.activeUser.flatMapLatest { user ->
        if (user == null) {
            flowOf(Content(null, emptyList(), null, null, null))
        } else {
            combine(
                userData.fitnessTests.results(user.id),
                userData.fitnessTests.liveFitnessProfile(user.id),
                userData.fitnessTests.heartRateMax(user.id),
                userData.restingHeartRates.reference(user.id),
            ) { results, profile, hrMax, resting ->
                Content(user, results, profile, hrMax, UserZones.compute(user, profile, hrMax, resting?.bpm, today()))
            }
        }
    }

    val uiState: StateFlow<FitnessTestsUiState> = combine(content, local) { c, local ->
        FitnessTestsUiState(true, c.user, c.results, c.profile, c.hrMax, c.zones, local.confirmingDeleteId, local.failed)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, FitnessTestsUiState())

    fun requestDelete(testId: String) = local.update { it.copy(confirmingDeleteId = testId) }

    fun cancelDelete() = local.update { it.copy(confirmingDeleteId = null) }

    fun confirmDelete() {
        val id = local.value.confirmingDeleteId ?: return
        local.update { it.copy(confirmingDeleteId = null) }
        viewModelScope.launch {
            if (userData.fitnessTests.delete(id) is DataResult.StorageError) local.update { it.copy(failed = true) }
        }
    }

    fun useMeasuredHeartRateMax() = answer { userData.fitnessTests.useMeasuredHeartRateMax(it) }

    fun keepManualHeartRateMax() = answer { userData.fitnessTests.keepManualHeartRateMax(it) }

    private fun answer(call: suspend (Long) -> DataResult<Unit>) {
        val user = uiState.value.user ?: return
        viewModelScope.launch {
            if (call(user.id) is DataResult.StorageError) local.update { it.copy(failed = true) }
        }
    }
}
