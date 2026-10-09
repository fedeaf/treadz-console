package com.treadz.console.training

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.treadz.console.programs.model.BlockPhase
import com.treadz.console.programs.model.InvalidProgramException
import com.treadz.console.programs.model.ProgramKind
import com.treadz.console.programs.plan.ProgramResolver
import com.treadz.console.programs.plan.ResolutionEnvironment
import com.treadz.console.programs.plan.ResolvedPlan
import com.treadz.console.programs.plan.StartParameters
import com.treadz.console.treadmill.TreadmillController
import com.treadz.console.treadmill.control.SafetyClamp
import com.treadz.console.userdata.DataResult
import com.treadz.console.userdata.ProgramSaveResult
import com.treadz.console.userdata.UserData
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ProgramEditorUiState(
    val loading: Boolean = true,
    val notFound: Boolean = false,
    /** The program has targets this editor cannot show yet (heart rate, relative): it is left as it is. */
    val unsupported: Boolean = false,
    val isNew: Boolean = true,
    val draft: ProgramDraft = ProgramDraft(),
    /** Every problem found in the draft now. Shown next to the fields once the user has tried to save. */
    val problems: Map<FieldRef, EditorProblem> = emptyMap(),
    val showProblems: Boolean = false,
    /** Blocks (step, block inside a repeat) too short to be useful. */
    val shortBlocks: Set<Pair<Int, Int?>> = emptySet(),
    /** What the program would run, when the draft is valid. */
    val plan: ResolvedPlan? = null,
    val dirty: Boolean = false,
    val saving: Boolean = false,
    val saveFailed: Boolean = false,
    val confirmingDelete: Boolean = false,
    val confirmingDiscard: Boolean = false,
) {
    val canDelete: Boolean get() = !isNew
}

/** Creates and edits a free program (absolute speed and incline per block, time or distance, repeats of a group). */
class ProgramEditorViewModel(
    private val programId: String?,
    private val userData: UserData,
    private val controller: TreadmillController,
) : ViewModel() {

    private val state = MutableStateFlow(ProgramEditorUiState(isNew = programId == null))
    val uiState: StateFlow<ProgramEditorUiState> = state.asStateFlow()

    private var nextKey = 1L
    private var ownerId: Long? = null
    private var program: com.treadz.console.programs.model.Program? = null
    private var kind = ProgramKind.Free

    init {
        viewModelScope.launch {
            if (programId == null) {
                ownerId = userData.profiles.activeUser.first()?.id
                state.update { it.copy(loading = false, draft = ProgramDrafts.starter(::key)) }
                refresh()
                return@launch
            }
            val stored = userData.programs.get(programId)
            if (stored == null) {
                state.update { it.copy(loading = false, notFound = true) }
                return@launch
            }
            ownerId = stored.userId
            program = stored.program
            kind = stored.program.kind
            if (!ProgramDrafts.isEditable(stored.program)) {
                state.update { it.copy(loading = false, unsupported = true, isNew = false) }
                return@launch
            }
            state.update { it.copy(loading = false, isNew = false, draft = ProgramDrafts.draftOf(stored.program, ::key)) }
            refresh()
        }
    }

    private fun key() = nextKey++

    // ------------------------------------------------------------------ editing

    private fun change(edit: (ProgramDraft) -> ProgramDraft) {
        state.update { it.copy(draft = edit(it.draft), dirty = true, saveFailed = false) }
        refresh()
    }

    fun setName(value: String) = change { it.copy(name = value) }

    fun setDescription(value: String) = change { it.copy(description = value) }

    fun addBlock() = change { it.copy(steps = it.steps + SingleStepDraft(key(), lastBlockOr(it).copy(key = key()))) }

    fun addRepeat() = change {
        val work = lastBlockOr(it).copy(key = key(), phase = BlockPhase.Work)
        val recovery = work.copy(key = key(), phase = BlockPhase.Recovery)
        it.copy(steps = it.steps + RepeatStepDraft(key(), "4", listOf(work, recovery)))
    }

    /** New blocks start as a copy of the last one: only what differs has to be changed. */
    private fun lastBlockOr(draft: ProgramDraft): BlockDraft = when (val last = draft.steps.lastOrNull()) {
        is SingleStepDraft -> last.block
        is RepeatStepDraft -> last.blocks.lastOrNull() ?: BlockDraft(0)
        null -> BlockDraft(0)
    }

    fun removeStep(index: Int) = change { it.copy(steps = it.steps.filterIndexed { i, _ -> i != index }) }

    fun moveStep(index: Int, direction: Int) = change { it.copy(steps = it.steps.moved(index, direction)) }

    fun duplicateStep(index: Int) = change { draft ->
        val step = draft.steps.getOrNull(index) ?: return@change draft
        val copy = when (step) {
            is SingleStepDraft -> SingleStepDraft(key(), step.block.copy(key = key()))
            is RepeatStepDraft -> RepeatStepDraft(key(), step.count, step.blocks.map { it.copy(key = key()) })
        }
        draft.copy(steps = draft.steps.toMutableList().apply { add(index + 1, copy) })
    }

    /** Changes a block: [block] is its index inside a repeat, `null` for a block that is a step by itself. */
    fun editBlock(step: Int, block: Int?, edit: (BlockDraft) -> BlockDraft) = change { draft ->
        draft.copy(
            steps = draft.steps.mapIndexed { i, s ->
                when {
                    i != step -> s
                    s is SingleStepDraft && block == null -> s.copy(block = edit(s.block))
                    s is RepeatStepDraft && block != null -> s.copy(blocks = s.blocks.mapIndexed { j, b -> if (j == block) edit(b) else b })
                    else -> s
                }
            },
        )
    }

    fun setRepeatCount(step: Int, value: String) = editRepeat(step) { it.copy(count = value) }

    fun addBlockToRepeat(step: Int) = editRepeat(step) { r ->
        r.copy(blocks = r.blocks + (r.blocks.lastOrNull() ?: BlockDraft(0)).copy(key = key()))
    }

    fun removeBlockFromRepeat(step: Int, block: Int) = editRepeat(step) { r -> r.copy(blocks = r.blocks.filterIndexed { j, _ -> j != block }) }

    fun moveBlockInRepeat(step: Int, block: Int, direction: Int) = editRepeat(step) { r -> r.copy(blocks = r.blocks.moved(block, direction)) }

    private fun editRepeat(step: Int, edit: (RepeatStepDraft) -> RepeatStepDraft) = change { draft ->
        draft.copy(steps = draft.steps.mapIndexed { i, s -> if (i == step && s is RepeatStepDraft) edit(s) else s })
    }

    private fun <T> List<T>.moved(index: Int, direction: Int): List<T> {
        val target = index + direction
        if (index !in indices || target !in indices) return this
        return toMutableList().apply { add(target, removeAt(index)) }
    }

    // ------------------------------------------------------------------ checking and saving

    private fun environment() = ResolutionEnvironment(
        speedRange = controller.capabilities.value?.speed ?: SafetyClamp.DEFAULT_SPEED_RANGE,
        inclineRange = controller.capabilities.value?.incline ?: SafetyClamp.DEFAULT_INCLINE_RANGE,
        limits = controller.limits.value,
    )

    /** Re-reads the draft: problems, short blocks and the preview. */
    private fun refresh() {
        val draft = state.value.draft
        val parsed = ProgramDrafts.parse(draft, programId.orEmpty(), kind)
        // The preview does not wait for the name, nor for the id a new program gets when it is saved.
        val previewable = ProgramDrafts.parse(draft.copy(name = draft.name.ifBlank { "-" }), programId ?: PREVIEW_ID, kind)
        val plan = previewable.program?.let {
            try {
                ProgramResolver.resolve(it, StartParameters(), environment())
            } catch (_: InvalidProgramException) {
                null
            }
        }
        state.update {
            it.copy(
                problems = parsed.problems,
                shortBlocks = ProgramDrafts.shortBlocks(draft, ProgramResolver.SHORT_BLOCK_SECONDS),
                plan = plan,
            )
        }
    }

    /** Saves the program; [onSaved] is called when it is stored. */
    fun save(onSaved: () -> Unit) {
        val current = state.value
        if (current.loading || current.saving || current.unsupported || current.notFound) return
        val parsed = ProgramDrafts.parse(current.draft, programId.orEmpty(), kind)
        val toSave = parsed.program
        val owner = ownerId
        if (toSave == null) {
            state.update { it.copy(showProblems = true, problems = parsed.problems) }
            return
        }
        if (owner == null) {
            state.update { it.copy(saveFailed = true) }
            return
        }
        state.update { it.copy(saving = true, showProblems = false, saveFailed = false) }
        viewModelScope.launch {
            when (val result = userData.programs.save(owner, toSave)) {
                is ProgramSaveResult.Saved -> {
                    state.update { it.copy(saving = false, dirty = false) }
                    onSaved()
                }
                is ProgramSaveResult.Invalid ->
                    state.update { it.copy(saving = false, showProblems = true, problems = ProgramDrafts.placeIssues(result.issues, it.draft)) }
                ProgramSaveResult.NotFound, is ProgramSaveResult.StorageError -> state.update { it.copy(saving = false, saveFailed = true) }
            }
        }
    }

    fun requestDelete() = state.update { if (it.canDelete) it.copy(confirmingDelete = true) else it }

    fun cancelDelete() = state.update { it.copy(confirmingDelete = false) }

    fun confirmDelete(onDeleted: () -> Unit) {
        val id = programId ?: return
        state.update { it.copy(confirmingDelete = false, saving = true) }
        viewModelScope.launch {
            when (userData.programs.delete(id)) {
                is DataResult.Success, DataResult.NotFound -> {
                    state.update { it.copy(saving = false, dirty = false) }
                    onDeleted()
                }
                else -> state.update { it.copy(saving = false, saveFailed = true) }
            }
        }
    }

    /** Back: leaves at once, or asks first when there are changes that would be lost. */
    fun requestBack(onExit: () -> Unit) {
        if (state.value.dirty) state.update { it.copy(confirmingDiscard = true) } else onExit()
    }

    fun cancelDiscard() = state.update { it.copy(confirmingDiscard = false) }

    private companion object {
        const val PREVIEW_ID = "00000000-0000-4000-8000-000000000000"
    }
}
