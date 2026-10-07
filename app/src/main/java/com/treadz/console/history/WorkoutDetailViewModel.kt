package com.treadz.console.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.treadz.console.userdata.DataResult
import com.treadz.console.userdata.UserData
import com.treadz.console.userdata.UserProfile
import com.treadz.console.userdata.WorkoutDetail
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class WorkoutDetailUiState(
    val loading: Boolean = true,
    val notFound: Boolean = false,
    val detail: WorkoutDetail? = null,
    /** Owner of the workout, `null` when it is unassigned. */
    val owner: UserProfile? = null,
    val users: List<UserProfile> = emptyList(),
    val confirmingDelete: Boolean = false,
    val assigning: Boolean = false,
    val failed: Boolean = false,
)

/** One workout with its samples, for the charts. A workout in progress is reloaded while it lasts. */
class WorkoutDetailViewModel(
    private val workoutId: Long,
    private val userData: UserData,
    private val refreshMillis: Long = REFRESH_MILLIS,
) : ViewModel() {

    private val state = MutableStateFlow(WorkoutDetailUiState())
    val uiState: StateFlow<WorkoutDetailUiState> = state.asStateFlow()

    init {
        viewModelScope.launch {
            load()
            while (state.value.detail?.summary?.isInProgress == true) {
                delay(refreshMillis)
                load()
            }
        }
    }

    private suspend fun load() {
        val detail = userData.workouts.detail(workoutId)
        val users = userData.profiles.profiles.first()
        state.update {
            if (detail == null) {
                it.copy(loading = false, notFound = true, detail = null)
            } else {
                it.copy(
                    loading = false,
                    notFound = false,
                    detail = detail,
                    owner = users.firstOrNull { u -> u.id == detail.summary.userId },
                    users = users,
                )
            }
        }
    }

    fun requestDelete() = state.update { it.copy(confirmingDelete = true) }

    fun cancelDelete() = state.update { it.copy(confirmingDelete = false) }

    /** Deletes the workout and calls [onDeleted]. */
    fun confirmDelete(onDeleted: () -> Unit) {
        state.update { it.copy(confirmingDelete = false) }
        viewModelScope.launch {
            when (userData.workouts.delete(workoutId)) {
                is DataResult.Success, DataResult.NotFound -> onDeleted()
                else -> state.update { it.copy(failed = true) }
            }
        }
    }

    fun requestAssign() = state.update { it.copy(assigning = true) }

    fun cancelAssign() = state.update { it.copy(assigning = false) }

    fun assignTo(userId: Long) {
        state.update { it.copy(assigning = false) }
        viewModelScope.launch {
            when (userData.workouts.assign(workoutId, userId)) {
                is DataResult.Success -> load()
                is DataResult.StorageError -> state.update { it.copy(failed = true) }
                else -> Unit
            }
        }
    }

    companion object {
        const val REFRESH_MILLIS = 5_000L
    }
}
