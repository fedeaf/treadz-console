package com.treadz.console.users

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.treadz.console.beltBusy
import com.treadz.console.treadmill.TreadmillController
import com.treadz.console.userdata.DataResult
import com.treadz.console.userdata.ProfileRepository
import com.treadz.console.userdata.UserProfile
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate

data class UserCard(val profile: UserProfile, val ageYears: Int, val isActive: Boolean)

data class UserSelectionUiState(
    val loaded: Boolean = false,
    val users: List<UserCard> = emptyList(),
    /** The belt is not stopped: the active user cannot be changed, only kept. */
    val switchLocked: Boolean = false,
    /** Selecting failed (storage error). */
    val failed: Boolean = false,
)

/** Who is training: lists the profiles and sets the active user. */
class UserSelectionViewModel(
    private val profiles: ProfileRepository,
    controller: TreadmillController,
    private val today: () -> LocalDate,
) : ViewModel() {

    private val failed = MutableStateFlow(false)

    val uiState: StateFlow<UserSelectionUiState> = combine(
        profiles.profiles,
        profiles.activeUser,
        controller.beltBusy,
        failed,
    ) { all, active, busy, failed ->
        val date = today()
        UserSelectionUiState(
            loaded = true,
            users = all.map { UserCard(it, it.ageOn(date), isActive = it.id == active?.id) },
            switchLocked = busy,
            failed = failed,
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, UserSelectionUiState())

    /**
     * Makes [id] the active user and calls [onSelected]. While the belt is not stopped, only the current active user
     * can be "selected" (to carry on with them); any other choice is ignored.
     */
    fun select(id: Long, onSelected: () -> Unit): Job = viewModelScope.launch {
        val state = uiState.value
        val current = state.users.firstOrNull { it.isActive }
        if (current?.profile?.id == id) {
            onSelected()
            return@launch
        }
        if (state.switchLocked) return@launch
        when (profiles.setActiveUser(id)) {
            is DataResult.Success -> {
                failed.update { false }
                onSelected()
            }
            is DataResult.StorageError -> failed.update { true }
            else -> Unit // The profile disappeared meanwhile: the list refreshes by itself.
        }
    }
}
