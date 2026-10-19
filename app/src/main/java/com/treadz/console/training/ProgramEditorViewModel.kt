package com.treadz.console.training

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.treadz.console.programs.engine.HeartRateControlMode
import com.treadz.console.programs.heartrate.HeartRateProfile
import com.treadz.console.programs.heartrate.HeartRateZoneBpm
import com.treadz.console.programs.heartrate.HeartRateZones
import com.treadz.console.programs.model.BlockPhase
import com.treadz.console.programs.model.HeartRateAdjust
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
import com.treadz.console.training.ProgramDrafts.withAutoBounds
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class ProgramEditorUiState(
    val loading: Boolean = true,
    val notFound: Boolean = false,
    /** The program has targets this editor cannot show (relative ones) or is a generated one: it is left as it is. */
    val unsupported: Boolean = false,
    /** [unsupported] because the program was generated: it can be duplicated as a free program instead. */
    val generated: Boolean = false,
    val isNew: Boolean = true,
    val draft: ProgramDraft = ProgramDraft(),
    /** Every problem found in the draft now. Shown next to the fields once the user has tried to save. */
    val problems: Map<FieldRef, EditorProblem> = emptyMap(),
    val showProblems: Boolean = false,
    /** Blocks (step, block inside a repeat) too short to be useful. */
    val shortBlocks: Set<Pair<Int, Int?>> = emptySet(),
    /** Heart-rate blocks (step, block inside a repeat) shorter than 3 minutes: the heart rate has no time to respond. */
    val shortHeartRateBlocks: Set<Pair<Int, Int?>> = emptySet(),
    /** How each heart-rate block is controlled: continuously or between the repetitions of its repeat. */
    val heartRateModes: Map<Pair<Int, Int?>, HeartRateControlMode> = emptyMap(),
    /** The active user's maximum heart rate (and resting reference): zones are shown in bpm. `null` without a user. */
    val heartRateProfile: HeartRateProfile? = null,
    /** The active user has a resting heart-rate reference: zones can be a % of the heart-rate reserve. */
    val hasResting: Boolean = false,
    /** The zone of each heart-rate block in bpm for the active user, as the values stand now. */
    val zoneBpm: Map<Pair<Int, Int?>, HeartRateZoneBpm> = emptyMap(),
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

    @OptIn(ExperimentalCoroutinesApi::class)
    private val heartRateReferences = userData.profiles.activeUser.flatMapLatest { user ->
        if (user == null) {
            flowOf(null)
        } else {
            combine(userData.fitnessTests.heartRateMax(user.id), userData.restingHeartRates.reference(user.id)) { max, resting ->
                max?.let { HeartRateProfile(it.bpm, resting?.bpm) }
            }
        }
    }

    init {
        viewModelScope.launch {
            heartRateReferences.collect { profile ->
                state.update { it.copy(heartRateProfile = profile, hasResting = profile?.usableRestingBpm != null) }
                refresh()
            }
        }
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
                state.update { it.copy(loading = false, unsupported = true, generated = stored.program.kind == ProgramKind.Personalised, isNew = false) }
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
                    s is SingleStepDraft && block == null -> s.copy(block = normalised(edit(s.block)))
                    s is RepeatStepDraft && block != null -> s.copy(blocks = s.blocks.mapIndexed { j, b -> if (j == block) normalised(edit(b)) else b })
                    else -> s
                }
            },
        )
    }

    /** A heart-rate block whose bounds have not been typed follows its starting value. */
    private fun normalised(block: BlockDraft): BlockDraft {
        val hr = block.heartRate ?: return block
        val env = environment()
        return block.copy(heartRate = hr.withAutoBounds(block, env.speedRange.max, env.inclineRange.max))
    }

    /**
     * "Keep" for the speed ([speed]) or the incline of a block: it starts with the value the treadmill has. Refused on the
     * first block (nothing to keep with the belt stopped), except to switch it off.
     */
    fun setKeep(step: Int, block: Int?, speed: Boolean, keep: Boolean) {
        if (keep && ProgramDrafts.isFirstBlock(step, block)) return
        editBlock(step, block) { if (speed) it.copy(keepSpeed = keep) else it.copy(keepIncline = keep) }
    }

    // ---- heart-rate targets

    /** Switches a block between "speed and incline" and "heart rate" (its speed and incline stay as the starting values). */
    fun setHeartRateTarget(step: Int, block: Int?, enabled: Boolean) = editBlock(step, block) { b ->
        when {
            !enabled -> b.copy(heartRate = null)
            b.heartRate != null -> b
            else -> {
                val env = environment()
                b.copy(heartRate = ProgramDrafts.heartRateDraftFor(b, env.speedRange.max, env.inclineRange.max))
            }
        }
    }

    /** Changes the heart-rate part of a block (zone, custom percentages…). */
    fun editHeartRate(step: Int, block: Int?, edit: (HeartRateDraft) -> HeartRateDraft) =
        editBlock(step, block) { b -> b.heartRate?.let { b.copy(heartRate = edit(it)) } ?: b }

    /** Zone 1-5 (`index` 0-4), or `null` for a zone of the user's own (it starts from the zone that was set). */
    fun setZone(step: Int, block: Int?, index: Int?) = editHeartRate(step, block) { hr ->
        if (index != null) {
            hr.copy(standardZone = index.coerceIn(0, HeartRateZones.STANDARD.lastIndex))
        } else {
            val current = ProgramDrafts.zoneOf(hr)
            hr.copy(standardZone = null, customLow = current?.lowPercent?.toString() ?: hr.customLow, customHigh = current?.highPercent?.toString() ?: hr.customHigh)
        }
    }

    /** The zone as a % of the heart-rate reserve: only for a user with a resting heart rate. */
    fun setReserve(step: Int, block: Int?, reserve: Boolean) {
        if (reserve && !state.value.hasResting) return
        editHeartRate(step, block) { it.copy(reserve = reserve) }
    }

    /** Which quantity the control moves: the speed (running) or the incline (walking, the speed stays). */
    fun setAdjust(step: Int, block: Int?, adjust: HeartRateAdjust) =
        editHeartRate(step, block) { if (it.adjust == adjust) it else it.copy(adjust = adjust, boundsAuto = true) }

    fun setHeartRateBound(step: Int, block: Int?, maximum: Boolean, text: String) = editHeartRate(step, block) {
        if (maximum) it.copy(maximum = text, boundsAuto = false) else it.copy(minimum = text, boundsAuto = false)
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
                shortHeartRateBlocks = ProgramDrafts.shortHeartRateBlocks(draft),
                heartRateModes = ProgramDrafts.controlModes(draft),
                zoneBpm = zonesInBpm(draft, it.heartRateProfile),
                plan = plan,
            )
        }
    }

    private fun zonesInBpm(draft: ProgramDraft, profile: HeartRateProfile?): Map<Pair<Int, Int?>, HeartRateZoneBpm> {
        if (profile == null || !profile.isUsable) return emptyMap()
        val result = linkedMapOf<Pair<Int, Int?>, HeartRateZoneBpm>()
        fun add(key: Pair<Int, Int?>, b: BlockDraft) {
            val zone = b.heartRate?.let(ProgramDrafts::zoneOf) ?: return
            if (zone.lowPercent in 0..100 && zone.highPercent in 0..100 && zone.lowPercent < zone.highPercent) result[key] = HeartRateZones.toBpm(zone, profile)
        }
        draft.steps.forEachIndexed { i, step ->
            when (step) {
                is SingleStepDraft -> add(i to null, step.block)
                is RepeatStepDraft -> step.blocks.forEachIndexed { j, b -> add(i to j, b) }
            }
        }
        return result
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
