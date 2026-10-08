package com.treadz.console.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.treadz.console.MainUiState
import com.treadz.console.sync.SyncError
import com.treadz.console.sync.SyncErrorCode
import com.treadz.console.sync.github.RepositoryRef
import com.treadz.console.sync.service.SyncService
import com.treadz.console.sync.service.SyncSetup
import com.treadz.console.sync.service.SyncStatus
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** What the settings screen shows. The GitHub token only exists here while it is being typed. */
data class SettingsUiState(
    val sync: SyncStatus = SyncStatus(),
    /** What the user has typed in the token field (never read back from the service). */
    val tokenInput: String = "",
    val tokenVisible: Boolean = false,
    /** A setup call (token, repository, first-link confirmation) is running. */
    val busy: Boolean = false,
    /** "Sync now" was pressed and has not returned yet. */
    val syncingNow: Boolean = false,
    /** Error returned by the last setup or sync call the user started. */
    val actionError: SyncError? = null,
    val confirmingUnlink: Boolean = false,
    val language: AppLanguage = AppLanguage.Automatic,
    val darkTheme: Boolean = true,
    /** Name of the remembered treadmill, `null` if it never had one. */
    val treadmillName: String? = null,
    val treadmillRemembered: Boolean = false,
    /** The belt is at rest: the settings that restart or reset things are available. */
    val beltIdle: Boolean = true,
    val canForgetTreadmill: Boolean = false,
) {
    val linked: Boolean get() = sync.setup == SyncSetup.Linked

    /**
     * The error to explain: the one of the user's last action, else the one the service reports. Before linking the
     * service's error is not repeated: it belongs to an attempt the user has already seen (and may be editing).
     */
    val error: SyncError? get() = actionError ?: sync.error.takeIf { sync.setup != SyncSetup.NotConfigured }

    /** The token is wrong or cannot write: a new one can be pasted without unlinking first. */
    val canReplaceToken: Boolean
        get() = linked && error?.code.let { it == SyncErrorCode.Unauthorized || it == SyncErrorCode.NoWriteAccess }

    val showsTokenField: Boolean get() = sync.setup == SyncSetup.NotConfigured || canReplaceToken

    val canSaveToken: Boolean get() = tokenInput.isNotBlank() && !busy
}

/**
 * Settings: GitHub synchronisation setup, language, and the bits of the main screen's state the screen shows (theme,
 * remembered treadmill). The theme and forgetting the treadmill are done by the main ViewModel (it owns that state).
 *
 * The token is only kept in memory while typed and is dropped as soon as the service has it; it is never logged and
 * never shown back.
 */
class SettingsViewModel(
    private val sync: SyncService,
    private val language: LanguageSettings,
    main: StateFlow<MainUiState>,
) : ViewModel() {

    private data class Local(
        val tokenInput: String = "",
        val tokenVisible: Boolean = false,
        val busy: Boolean = false,
        val syncingNow: Boolean = false,
        val actionError: SyncError? = null,
        val confirmingUnlink: Boolean = false,
        val language: AppLanguage = AppLanguage.Automatic,
    )

    private val local = MutableStateFlow(Local(language = language.current()))

    val uiState: StateFlow<SettingsUiState> = combine(sync.status, local, main) { status, local, main ->
        SettingsUiState(
            sync = status,
            tokenInput = local.tokenInput,
            tokenVisible = local.tokenVisible,
            busy = local.busy,
            syncingNow = local.syncingNow,
            actionError = local.actionError,
            confirmingUnlink = local.confirmingUnlink,
            language = local.language,
            darkTheme = main.darkTheme,
            treadmillName = main.rememberedName,
            treadmillRemembered = main.rememberedAddress != null,
            beltIdle = main.canOpenSettings,
            canForgetTreadmill = main.canForgetTreadmill,
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, SettingsUiState(language = local.value.language))

    // ---- Token and repository ----

    fun setToken(value: String) = local.update { it.copy(tokenInput = value, actionError = null) }

    /** Drops what was typed (the user left the screen without linking). */
    fun clearToken() = local.update { it.copy(tokenInput = "", tokenVisible = false) }

    fun setTokenVisible(visible: Boolean) = local.update { it.copy(tokenVisible = visible) }

    /** Gives the token to the service. It is forgotten here whatever the outcome: the field is empty afterwards. */
    fun saveToken() {
        val token = local.value.tokenInput
        if (token.isBlank() || local.value.busy) return
        local.update { it.copy(busy = true, actionError = null) }
        viewModelScope.launch {
            val error = sync.configure(token)
            local.update { it.copy(busy = false, actionError = error, tokenInput = if (error == null) "" else it.tokenInput) }
        }
    }

    fun selectRepository(repository: RepositoryRef) = runSetup { sync.selectRepository(repository) }

    fun confirmFirstLink() = runSetup { sync.confirmFirstLink() }

    fun cancelFirstLink() {
        viewModelScope.launch {
            sync.cancelFirstLink()
            local.update { it.copy(actionError = null, tokenInput = "") }
        }
    }

    /** Gives up choosing among several repositories: the token is forgotten. */
    fun cancelChoice() {
        viewModelScope.launch {
            sync.clear()
            local.update { it.copy(actionError = null, tokenInput = "") }
        }
    }

    private fun runSetup(call: suspend () -> SyncError?) {
        if (local.value.busy) return
        local.update { it.copy(busy = true, actionError = null) }
        viewModelScope.launch {
            val error = call()
            local.update { it.copy(busy = false, actionError = error) }
        }
    }

    // ---- Linked ----

    fun syncNow() {
        if (local.value.syncingNow || !uiState.value.linked) return
        local.update { it.copy(syncingNow = true, actionError = null) }
        viewModelScope.launch {
            val error = sync.syncNow()
            local.update { it.copy(syncingNow = false, actionError = error) }
        }
    }

    fun requestUnlink() = local.update { it.copy(confirmingUnlink = true) }

    fun cancelUnlink() = local.update { it.copy(confirmingUnlink = false) }

    /** Removes the token and the link. Local data stays and nothing is deleted from the repository. */
    fun confirmUnlink() {
        local.update { it.copy(confirmingUnlink = false) }
        viewModelScope.launch {
            sync.clear()
            local.update { it.copy(actionError = null, tokenInput = "") }
        }
    }

    // ---- Language ----

    /** Reads the language again: it may have been changed in Android's own settings meanwhile. */
    fun refreshLanguage() = local.update { it.copy(language = language.current()) }

    /** Applies [value] through the platform; Android recreates the activity with the new language. */
    fun setLanguage(value: AppLanguage) {
        if (!uiState.value.beltIdle) return
        language.set(value)
        local.update { it.copy(language = value) }
    }
}
