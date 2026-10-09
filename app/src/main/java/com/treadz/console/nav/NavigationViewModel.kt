package com.treadz.console.nav

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.treadz.console.userdata.ProfileRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/**
 * Back stack and startup flow.
 *
 * - Fresh install (only the generic profile, active): the first screen is [Screen.Main].
 * - More than one profile, or nobody active: the first screen is [Screen.UserSelection], every time the app opens.
 * - If nobody is active later on (the active profile was deleted), the selection is forced.
 *
 * It lives in the activity's ViewModel store, so a configuration change keeps the stack but a new app start asks again.
 */
class NavigationViewModel(private val profiles: ProfileRepository) : ViewModel() {

    private val state = MutableStateFlow<List<Screen>?>(null)

    /** The stack, last element on top; `null` until the profiles are loaded (nothing to show yet). */
    val stack: StateFlow<List<Screen>?> = state.asStateFlow()

    private var tokens = 0

    private val people = combine(profiles.profiles, profiles.activeUser) { all, active -> all to active }

    init {
        viewModelScope.launch {
            val (all, active) = people.first()
            state.value = if (all.size > 1 || active == null) listOf(Screen.UserSelection) else listOf(Screen.Main)
            people.collect { (_, current) ->
                if (current == null) state.update { stack -> forceSelection(stack) }
            }
        }
    }

    /** Nobody is active: stay in the selection (and its editor, where users are created, edited or deleted), else go there. */
    private fun forceSelection(stack: List<Screen>?): List<Screen>? = when (stack?.lastOrNull()) {
        null, Screen.UserSelection, is Screen.ProfileEditor -> stack
        else -> listOf(Screen.UserSelection)
    }

    fun push(screen: Screen) = state.update { stack ->
        if (stack == null || stack.lastOrNull() == screen) stack else stack + screen
    }

    fun pop() = state.update { stack -> if (stack != null && stack.size > 1) stack.dropLast(1) else stack }

    fun replaceAll(screen: Screen) {
        state.value = listOf(screen)
    }

    /** Opens the editor of a free program: `null` creates one. */
    fun openProgramEditor(programId: String?) = push(Screen.ProgramEditor(programId, ++tokens))

    /** Opens the profile editor: `null` creates a profile. */
    fun openEditor(profileId: Long?) = push(Screen.ProfileEditor(profileId, ++tokens))

    /**
     * Leaves the editor after a profile was deleted. Back to where it was opened from, except when nothing is left to
     * choose (a single profile, active): then straight to the main screen.
     */
    fun onProfileDeleted() {
        pop()
        viewModelScope.launch {
            val (all, active) = people.first()
            if (all.size == 1 && active != null) replaceAll(Screen.Main)
        }
    }
}
