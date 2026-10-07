package com.treadz.console.weight

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.treadz.console.Decimals
import com.treadz.console.userdata.DataResult
import com.treadz.console.userdata.InputField
import com.treadz.console.userdata.UserData
import com.treadz.console.userdata.UserProfile
import com.treadz.console.userdata.ValidationCode
import com.treadz.console.userdata.ValidationIssue
import com.treadz.console.userdata.WeightEntry
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

/** What the weight dialog is doing. */
sealed interface WeightEditorKind {
    data object Add : WeightEditorKind
    data class Edit(val entryId: Long) : WeightEditorKind
    data object Target : WeightEditorKind
}

data class WeightEditorUi(
    val kind: WeightEditorKind,
    /** Not used for the target weight. */
    val date: LocalDate,
    val weightText: String,
    val errors: Map<InputField, ValidationCode> = emptyMap(),
    val saving: Boolean = false,
    /** The target is set: the dialog offers to remove it. */
    val canClearTarget: Boolean = false,
)

data class WeightUiState(
    val loaded: Boolean = false,
    val user: UserProfile? = null,
    /** Ordered by date, oldest first. */
    val entries: List<WeightEntry> = emptyList(),
    val targetKg: Double? = null,
    val editor: WeightEditorUi? = null,
    val confirmingDeleteId: Long? = null,
    val failed: Boolean = false,
) {
    /** The current weight is the latest entry (which may be the made-up placeholder). */
    val current: WeightEntry? get() = entries.lastOrNull()

    /** Only the made-up weight of the generic profile is there: the UI invites the user to enter the real one. */
    val onlyPlaceholder: Boolean get() = entries.isNotEmpty() && entries.all { it.isPlaceholder }
}

/** Weight log and target weight of the active user. */
class WeightViewModel(
    private val userData: UserData,
    private val today: () -> LocalDate,
) : ViewModel() {

    private data class Data(val user: UserProfile?, val entries: List<WeightEntry>, val target: Double?)

    private data class Local(val editor: WeightEditorUi? = null, val confirmingDeleteId: Long? = null, val failed: Boolean = false)

    private val local = MutableStateFlow(Local())

    @OptIn(ExperimentalCoroutinesApi::class)
    private val data: Flow<Data> = userData.profiles.activeUser.flatMapLatest { user ->
        if (user == null) {
            flowOf(Data(null, emptyList(), null))
        } else {
            combine(userData.weights.entries(user.id), userData.weights.targetWeightKg(user.id)) { entries, target ->
                Data(user, entries, target)
            }
        }
    }

    val uiState: StateFlow<WeightUiState> = combine(data, local) { data, local ->
        WeightUiState(
            loaded = true,
            user = data.user,
            entries = data.entries,
            targetKg = data.target,
            editor = local.editor,
            confirmingDeleteId = local.confirmingDeleteId,
            failed = local.failed,
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, WeightUiState())

    fun openAdd() {
        val state = uiState.value
        // The placeholder is not a real weight: do not suggest it.
        val suggestion = state.current?.takeIf { !it.isPlaceholder }?.weightKg
        openEditor(WeightEditorUi(WeightEditorKind.Add, today(), suggestion?.let { Decimals.format(it) }.orEmpty()))
    }

    fun openEdit(entry: WeightEntry) = openEditor(
        // A placeholder is edited blank: its 80 kg is made up.
        WeightEditorUi(WeightEditorKind.Edit(entry.id), entry.date, if (entry.isPlaceholder) "" else Decimals.format(entry.weightKg)),
    )

    fun openTarget() {
        val target = uiState.value.targetKg
        openEditor(
            WeightEditorUi(WeightEditorKind.Target, today(), target?.let { Decimals.format(it) }.orEmpty(), canClearTarget = target != null),
        )
    }

    private fun openEditor(editor: WeightEditorUi) = local.update { it.copy(editor = editor, failed = false) }

    fun dismissEditor() = local.update { it.copy(editor = null) }

    fun setDate(date: LocalDate) = changeEditor { it.copy(date = date, errors = it.errors - InputField.WeightDate) }

    fun setWeightText(text: String) =
        changeEditor { it.copy(weightText = text, errors = it.errors - InputField.Weight - InputField.TargetWeight) }

    private fun changeEditor(change: (WeightEditorUi) -> WeightEditorUi) = local.update { l -> l.copy(editor = l.editor?.let(change)) }

    /** Validates and saves the open dialog; it closes when it worked and keeps the errors when it did not. */
    fun saveEditor() {
        val user = uiState.value.user ?: return
        val editor = local.value.editor ?: return
        if (editor.saving) return
        val field = if (editor.kind == WeightEditorKind.Target) InputField.TargetWeight else InputField.Weight
        val kg = Decimals.parse(editor.weightText)
        if (kg == null) {
            val code = if (editor.weightText.isBlank()) ValidationCode.Blank else ValidationCode.OutOfRange
            changeEditor { it.copy(errors = mapOf(field to code)) }
            return
        }
        changeEditor { it.copy(saving = true, errors = emptyMap()) }
        viewModelScope.launch {
            val result: DataResult<*> = when (val kind = editor.kind) {
                WeightEditorKind.Add -> userData.weights.add(user.id, editor.date, kg)
                is WeightEditorKind.Edit -> userData.weights.update(kind.entryId, editor.date, kg)
                WeightEditorKind.Target -> userData.weights.setTargetWeight(user.id, kg)
            }
            finish(result)
        }
    }

    /** Removes the target weight (from the target dialog). */
    fun clearTarget() {
        val user = uiState.value.user ?: return
        viewModelScope.launch { finish(userData.weights.setTargetWeight(user.id, null)) }
    }

    private fun finish(result: DataResult<*>) {
        when (result) {
            is DataResult.Success -> local.update { it.copy(editor = null, failed = false) }
            is DataResult.Invalid -> changeEditor { it.copy(saving = false, errors = result.issues.toErrors()) }
            // The entry or user disappeared meanwhile: the lists refresh by themselves.
            DataResult.NotFound -> local.update { it.copy(editor = null) }
            is DataResult.StorageError -> local.update { l -> l.copy(editor = l.editor?.copy(saving = false), failed = true) }
        }
    }

    private fun List<ValidationIssue>.toErrors() = associate { it.field to it.code }

    fun requestDelete(entryId: Long) = local.update { it.copy(confirmingDeleteId = entryId) }

    fun cancelDelete() = local.update { it.copy(confirmingDeleteId = null) }

    fun confirmDelete() {
        val id = local.value.confirmingDeleteId ?: return
        local.update { it.copy(confirmingDeleteId = null) }
        viewModelScope.launch {
            val result = userData.weights.delete(id)
            if (result is DataResult.StorageError) local.update { it.copy(failed = true) }
        }
    }
}
