package com.treadz.console.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.treadz.console.R
import com.treadz.console.programs.engine.HeartRateControlMode
import com.treadz.console.programs.heartrate.HeartRateZones
import com.treadz.console.programs.model.BlockPhase
import com.treadz.console.programs.model.HeartRateAdjust
import com.treadz.console.programs.plan.ProgramResolver
import com.treadz.console.training.BlockDraft
import com.treadz.console.training.EditorField
import com.treadz.console.training.FieldRef
import com.treadz.console.training.HeartRateDraft
import com.treadz.console.training.ProgramEditorUiState
import com.treadz.console.training.RepeatStepDraft
import com.treadz.console.training.SingleStepDraft
import com.treadz.console.training.segments
import com.treadz.console.training.stepDistance
import com.treadz.console.training.stepDuration
import com.treadz.console.training.stepHeartRateBound
import com.treadz.console.training.stepIncline
import com.treadz.console.training.stepSpeed
import com.treadz.console.training.stepZone

data class ProgramEditorActions(
    val onBack: () -> Unit = {},
    val onName: (String) -> Unit = {},
    val onDescription: (String) -> Unit = {},
    val onAddBlock: () -> Unit = {},
    val onAddRepeat: () -> Unit = {},
    val onRemoveStep: (Int) -> Unit = {},
    val onMoveStep: (Int, Int) -> Unit = { _, _ -> },
    val onDuplicateStep: (Int) -> Unit = {},
    val onEditBlock: (step: Int, block: Int?, edit: (BlockDraft) -> BlockDraft) -> Unit = { _, _, _ -> },
    val onRepeatCount: (Int, String) -> Unit = { _, _ -> },
    val onAddBlockToRepeat: (Int) -> Unit = {},
    val onRemoveBlockFromRepeat: (Int, Int) -> Unit = { _, _ -> },
    val onMoveBlockInRepeat: (Int, Int, Int) -> Unit = { _, _, _ -> },
    val onSave: () -> Unit = {},
    val onDelete: () -> Unit = {},
    val onConfirmDelete: () -> Unit = {},
    val onCancelDelete: () -> Unit = {},
    val onDiscard: () -> Unit = {},
    val onCancelDiscard: () -> Unit = {},
    /** Switches a block (step, block inside a repeat) between "speed and incline" and "heart rate". */
    val onHeartRateTarget: (step: Int, block: Int?, enabled: Boolean) -> Unit = { _, _, _ -> },
    /** Zone 1-5 (0-4), or `null` for a zone of the user's own. */
    val onZone: (step: Int, block: Int?, zone: Int?) -> Unit = { _, _, _ -> },
    val onReserve: (step: Int, block: Int?, reserve: Boolean) -> Unit = { _, _, _ -> },
    val onAdjust: (step: Int, block: Int?, adjust: HeartRateAdjust) -> Unit = { _, _, _ -> },
    val onHeartRateBound: (step: Int, block: Int?, maximum: Boolean, text: String) -> Unit = { _, _, _, _ -> },
    val onEditHeartRate: (step: Int, block: Int?, edit: (HeartRateDraft) -> HeartRateDraft) -> Unit = { _, _, _ -> },
    val onOpenResting: () -> Unit = {},
)

/** Editor of a free program: name, blocks by time or distance, repeats, and a live preview. */
@Composable
fun ProgramEditorScreen(state: ProgramEditorUiState, actions: ProgramEditorActions, modifier: Modifier = Modifier) {
    val wide = LocalLayout.current.wide
    Column(modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ScreenHeader(
            title = stringResource(if (state.isNew) R.string.editor_program_new else R.string.editor_program_edit),
            onBack = actions.onBack,
        )
        when {
            state.notFound -> Text(stringResource(R.string.program_not_found), Modifier.testTag("program_not_found"), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            state.unsupported -> Text(stringResource(if (state.generated) R.string.editor_program_generated else R.string.editor_program_unsupported), Modifier.testTag("program_unsupported"), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            state.loading -> Unit
            wide -> {
                Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Column(Modifier.weight(1.35f).fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Steps(state, actions)
                    }
                    Preview(state, Modifier.weight(1f).fillMaxSize(), fill = true)
                }
                SaveBar(state, actions)
            }
            else -> {
                Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Steps(state, actions)
                    Preview(state, Modifier.fillMaxWidth(), fill = false)
                }
                SaveBar(state, actions)
            }
        }
    }
    if (state.confirmingDelete) {
        ConfirmDialog(
            title = stringResource(R.string.program_delete_title, state.draft.name),
            text = stringResource(R.string.program_delete_body),
            confirmText = stringResource(R.string.action_delete),
            onConfirm = actions.onConfirmDelete,
            onDismiss = actions.onCancelDelete,
        )
    }
    if (state.confirmingDiscard) {
        ConfirmDialog(
            title = stringResource(R.string.editor_discard_title),
            text = stringResource(R.string.editor_discard_body),
            confirmText = stringResource(R.string.editor_discard_confirm),
            onConfirm = actions.onDiscard,
            onDismiss = actions.onCancelDiscard,
        )
    }
}

@Composable
private fun problem(state: ProgramEditorUiState, step: Int?, block: Int?, field: EditorField, adjust: HeartRateAdjust? = null): String? =
    if (!state.showProblems) null else state.problems[FieldRef(step, block, field)]?.text(field, adjust)

@Composable
private fun Steps(state: ProgramEditorUiState, actions: ProgramEditorActions) {
    val draft = state.draft
    OutlinedTextField(
        value = draft.name,
        onValueChange = actions.onName,
        label = { Text(stringResource(R.string.editor_program_name)) },
        isError = problem(state, null, null, EditorField.Name) != null,
        supportingText = { problem(state, null, null, EditorField.Name)?.let { Text(it, fontWeight = FontWeight.SemiBold) } },
        singleLine = true,
        textStyle = MaterialTheme.typography.titleLarge,
        modifier = Modifier.fillMaxWidth().testTag("program_name_field"),
    )
    OutlinedTextField(
        value = draft.description,
        onValueChange = actions.onDescription,
        label = { Text(stringResource(R.string.editor_program_description)) },
        isError = problem(state, null, null, EditorField.Description) != null,
        supportingText = { problem(state, null, null, EditorField.Description)?.let { Text(it, fontWeight = FontWeight.SemiBold) } },
        maxLines = 3,
        modifier = Modifier.fillMaxWidth().testTag("program_description_field"),
    )
    problem(state, null, null, EditorField.Steps)?.let { ErrorText(it, Modifier.testTag("steps_error")) }
    draft.steps.forEachIndexed { index, step ->
        when (step) {
            is SingleStepDraft -> BlockCard(
                state = state, actions = actions, stepIndex = index, blockIndex = null, block = step.block,
                title = stringResource(R.string.editor_block_number, index + 1),
                stepButtons = true,
            )
            is RepeatStepDraft -> RepeatCard(state, actions, index, step)
        }
    }
    Row(horizontalArrangement = Arrangement.spacedBy(12.dp), modifier = Modifier.fillMaxWidth()) {
        BigOutlinedButton(stringResource(R.string.editor_add_block), actions.onAddBlock, Modifier.weight(1f).testTag("add_block_button"))
        BigOutlinedButton(stringResource(R.string.editor_add_repeat), actions.onAddRepeat, Modifier.weight(1f).testTag("add_repeat_button"))
    }
    if (state.canDelete) {
        BigOutlinedButton(stringResource(R.string.program_delete), actions.onDelete, Modifier.fillMaxWidth().testTag("delete_program_button"), destructive = true)
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun RepeatCard(state: ProgramEditorUiState, actions: ProgramEditorActions, index: Int, step: RepeatStepDraft) {
    Panel(Modifier.fillMaxWidth().testTag("repeat_$index"), borderColor = MaterialTheme.colorScheme.secondary) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.editor_repeat_title), Modifier.weight(1f).padding(top = 14.dp), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                OutlinedTextField(
                    value = step.count,
                    onValueChange = { actions.onRepeatCount(index, it) },
                    label = { Text(stringResource(R.string.editor_repeat_times)) },
                    isError = problem(state, index, null, EditorField.RepeatCount) != null,
                    supportingText = { problem(state, index, null, EditorField.RepeatCount)?.let { Text(it, fontWeight = FontWeight.SemiBold) } },
                    singleLine = true,
                    keyboardOptions = androidx.compose.foundation.text.KeyboardOptions(keyboardType = KeyboardType.Number),
                    textStyle = MaterialTheme.typography.titleLarge,
                    modifier = Modifier.width140().testTag("repeat_count_$index"),
                )
            }
            problem(state, index, null, EditorField.RepeatBlocks)?.let { ErrorText(it) }
            step.blocks.forEachIndexed { j, block ->
                BlockCard(
                    state = state, actions = actions, stepIndex = index, blockIndex = j, block = block,
                    title = stringResource(R.string.editor_block_in_repeat, j + 1), stepButtons = false,
                    nested = true,
                )
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                RowButton(stringResource(R.string.editor_add_block_to_repeat), { actions.onAddBlockToRepeat(index) }, Modifier.testTag("add_block_to_repeat_$index"))
                StepButtons(actions, index)
            }
        }
    }
}

private fun Modifier.width140(): Modifier = this.then(Modifier.width(140.dp))

/** Up, down, duplicate and delete of a whole step. */
@Composable
private fun StepButtons(actions: ProgramEditorActions, index: Int) {
    RowButton(stringResource(R.string.editor_move_up), { actions.onMoveStep(index, -1) }, Modifier.testTag("move_up_$index"))
    RowButton(stringResource(R.string.editor_move_down), { actions.onMoveStep(index, +1) }, Modifier.testTag("move_down_$index"))
    RowButton(stringResource(R.string.program_duplicate), { actions.onDuplicateStep(index) }, Modifier.testTag("duplicate_step_$index"))
    RowButton(stringResource(R.string.action_delete), { actions.onRemoveStep(index) }, Modifier.testTag("remove_step_$index"), destructive = true)
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BlockCard(
    state: ProgramEditorUiState,
    actions: ProgramEditorActions,
    stepIndex: Int,
    blockIndex: Int?,
    block: BlockDraft,
    title: String,
    stepButtons: Boolean,
    nested: Boolean = false,
) {
    val wide = LocalLayout.current.wide
    val tag = if (blockIndex == null) "block_$stepIndex" else "block_${stepIndex}_$blockIndex"
    fun edit(change: (BlockDraft) -> BlockDraft) = actions.onEditBlock(stepIndex, blockIndex, change)
    val short = (stepIndex to blockIndex) in state.shortBlocks
    val shortHeartRate = (stepIndex to blockIndex) in state.shortHeartRateBlocks
    val hr = block.heartRate
    Panel(
        Modifier.fillMaxWidth().testTag(tag),
        color = if (nested) MaterialTheme.colorScheme.surfaceContainerHigh else MaterialTheme.colorScheme.surfaceContainer,
        borderColor = if (short || shortHeartRate) MaterialTheme.extra.warning else MaterialTheme.colorScheme.outlineVariant,
    ) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                for (phase in BlockPhase.entries) {
                    SmallChip(phase.label(), block.phase == phase, "${tag}_phase_${phase.name}") { edit { it.copy(phase = phase) } }
                }
            }
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                ChoiceChip(stringResource(R.string.editor_target_fixed), hr == null, "${tag}_target_fixed", { actions.onHeartRateTarget(stepIndex, blockIndex, false) })
                ChoiceChip(stringResource(R.string.editor_target_heart_rate), hr != null, "${tag}_target_hr", { actions.onHeartRateTarget(stepIndex, blockIndex, true) })
            }
            val endTypes: @Composable () -> Unit = {
                SmallChip(stringResource(R.string.editor_end_time), !block.byDistance, "${tag}_by_time") { edit { it.copy(byDistance = false) } }
                SmallChip(stringResource(R.string.editor_end_distance), block.byDistance, "${tag}_by_distance") { edit { it.copy(byDistance = true) } }
            }
            val endField = if (block.byDistance) EditorField.Distance else EditorField.Duration
            val endProblem = problem(state, stepIndex, blockIndex, endField)
            val numbers: @Composable () -> Unit = {
                if (block.byDistance) {
                    StepperField(
                        label = stringResource(R.string.editor_distance), value = block.distance, onValue = { v -> edit { it.copy(distance = v) } },
                        onStep = { d -> edit { it.stepDistance(d) } }, unit = null, tag = "${tag}_distance",
                        error = endProblem, keyboardType = KeyboardType.Number,
                        modifier = if (wide) Modifier.weight(1f) else Modifier,
                    )
                } else {
                    StepperField(
                        label = stringResource(R.string.editor_duration), value = block.duration, onValue = { v -> edit { it.copy(duration = v) } },
                        onStep = { d -> edit { it.stepDuration(d) } }, unit = null, tag = "${tag}_duration",
                        error = endProblem, supporting = stringResource(R.string.editor_duration_hint), keyboardType = KeyboardType.Text,
                        modifier = if (wide) Modifier.weight(1f) else Modifier,
                    )
                }
            }
            val speedField: @Composable (Modifier) -> Unit = { m ->
                StepperField(
                    label = stringResource(if (hr != null) R.string.editor_hr_start_speed else R.string.chart_speed), value = block.speed, onValue = { v -> edit { it.copy(speed = v) } },
                    onStep = { d -> edit { it.stepSpeed(d) } }, unit = null, tag = "${tag}_speed",
                    error = problem(state, stepIndex, blockIndex, EditorField.Speed, hr?.adjust), modifier = m,
                )
            }
            val inclineField: @Composable (Modifier) -> Unit = { m ->
                StepperField(
                    label = stringResource(if (hr != null) R.string.editor_hr_start_incline else R.string.chart_incline), value = block.incline, onValue = { v -> edit { it.copy(incline = v) } },
                    onStep = { d -> edit { it.stepIncline(d) } }, unit = null, tag = "${tag}_incline",
                    error = problem(state, stepIndex, blockIndex, EditorField.Incline, hr?.adjust), keyboardType = KeyboardType.Number, modifier = m,
                )
            }
            if (wide) {
                // One row: what ends the block, the speed and the incline.
                Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { endTypes() }
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                        numbers()
                        speedField(Modifier.weight(1f))
                        inclineField(Modifier.weight(1f))
                    }
                }
            } else {
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) { endTypes() }
                numbers()
                speedField(Modifier)
                inclineField(Modifier)
            }
            if (short) {
                Text(stringResource(R.string.editor_short_block, com.treadz.console.programs.plan.ProgramResolver.SHORT_BLOCK_SECONDS), Modifier.testTag("${tag}_short"), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.extra.warning)
            }
            if (hr != null) HeartRateTarget(state, actions, stepIndex, blockIndex, block, hr, tag, shortHeartRate)
            OutlinedTextField(
                value = block.name,
                onValueChange = { v -> edit { it.copy(name = v) } },
                label = { Text(stringResource(R.string.editor_block_name)) },
                isError = problem(state, stepIndex, blockIndex, EditorField.BlockName) != null,
                supportingText = { problem(state, stepIndex, blockIndex, EditorField.BlockName)?.let { Text(it, fontWeight = FontWeight.SemiBold) } },
                singleLine = true,
                modifier = Modifier.fillMaxWidth().testTag("${tag}_name"),
            )
            if (stepButtons) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) { StepButtons(actions, stepIndex) }
            } else if (blockIndex != null) {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    RowButton(stringResource(R.string.editor_move_up), { actions.onMoveBlockInRepeat(stepIndex, blockIndex, -1) }, Modifier.testTag("${tag}_up"))
                    RowButton(stringResource(R.string.editor_move_down), { actions.onMoveBlockInRepeat(stepIndex, blockIndex, +1) }, Modifier.testTag("${tag}_down"))
                    RowButton(stringResource(R.string.action_delete), { actions.onRemoveBlockFromRepeat(stepIndex, blockIndex) }, Modifier.testTag("${tag}_remove"), destructive = true)
                }
            }
        }
    }
}

@Composable
private fun SmallChip(text: String, selected: Boolean, tag: String, onClick: () -> Unit) {
    androidx.compose.material3.FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(text, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(vertical = 2.dp)) },
        modifier = Modifier.testTag(tag),
        colors = androidx.compose.material3.FilterChipDefaults.filterChipColors(
            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
            selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
        ),
    )
}

@Composable
private fun Preview(state: ProgramEditorUiState, modifier: Modifier, fill: Boolean) {
    val plan = state.plan
    Panel(modifier.testTag("editor_preview")) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SectionLabel(stringResource(R.string.start_preview))
            val segments = remember(plan) { plan?.segments().orEmpty() }
            PlanChart(
                segments = segments,
                description = stringResource(R.string.start_preview_description),
                modifier = (if (fill) Modifier.weight(1f) else Modifier.height(200.dp)).fillMaxWidth().testTag("editor_chart"),
                emptyText = stringResource(R.string.editor_preview_invalid),
            )
            PlanLegend(
                stringResource(R.string.chart_speed), stringResource(R.string.chart_incline),
                heartRateLabel = if (segments.any { it.heartRate != null }) stringResource(R.string.chart_heart_rate_blocks) else null,
            )
            if (plan != null) {
                PlanTotals(plan)
                PlanWarnings(plan.warnings.filter { it.code != com.treadz.console.programs.plan.WarningCode.ShortBlock })
            }
            if (state.shortHeartRateBlocks.isNotEmpty()) {
                Text(
                    stringResource(R.string.editor_hr_short_summary, state.shortHeartRateBlocks.size),
                    Modifier.testTag("editor_hr_short_summary"),
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.extra.warning,
                )
            }
            if (state.shortBlocks.isNotEmpty()) {
                Text(
                    stringResource(R.string.editor_short_blocks_summary, state.shortBlocks.size, ProgramResolver.SHORT_BLOCK_SECONDS),
                    Modifier.testTag("editor_short_summary"),
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.extra.warning,
                )
            }
        }
    }
}

@Composable
private fun SaveBar(state: ProgramEditorUiState, actions: ProgramEditorActions) {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        if (state.saveFailed) ErrorText(stringResource(R.string.error_storage), Modifier.padding(bottom = 6.dp).testTag("editor_error"))
        if (state.showProblems && state.problems.isNotEmpty()) ErrorText(stringResource(R.string.editor_fix_problems), Modifier.padding(bottom = 6.dp).testTag("editor_problems"))
        BigButton(stringResource(R.string.action_save), actions.onSave, Modifier.fillMaxWidth().testTag("save_program_button"), enabled = !state.saving)
    }
}

/**
 * The heart-rate part of a block: the zone (Z1-Z5 or the user's own percentages) in bpm for the active user, the basis, what
 * the control adjusts and between which bounds, how the block is controlled, and the warning of a block that is too short.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun HeartRateTarget(
    state: ProgramEditorUiState,
    actions: ProgramEditorActions,
    stepIndex: Int,
    blockIndex: Int?,
    block: BlockDraft,
    hr: HeartRateDraft,
    tag: String,
    tooShort: Boolean,
) {
    fun edit(change: (BlockDraft) -> BlockDraft) = actions.onEditBlock(stepIndex, blockIndex, change)
    val key = stepIndex to blockIndex
    val wide = LocalLayout.current.wide
    Column(Modifier.fillMaxWidth().testTag("${tag}_hr"), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(stringResource(R.string.editor_hr_zone), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            for (i in HeartRateZones.STANDARD.indices) {
                ChoiceChip("Z${i + 1}", hr.standardZone == i, "${tag}_zone_$i", { actions.onZone(stepIndex, blockIndex, i) })
            }
            ChoiceChip(stringResource(R.string.editor_hr_zone_custom), hr.standardZone == null, "${tag}_zone_custom", { actions.onZone(stepIndex, blockIndex, null) })
        }
        if (hr.standardZone == null) {
            val range = stringResource(R.string.editor_hr_percent_unit)
            // Two fields side by side on the tablet; one under the other on a phone, where each needs the full width.
            val zoneFields: @Composable (Modifier) -> Unit = { m ->
                StepperField(
                    label = stringResource(R.string.editor_hr_zone_low), value = hr.customLow,
                    onValue = { v -> actions.onEditHeartRate(stepIndex, blockIndex) { it.copy(customLow = v) } },
                    onStep = { d -> edit { it.stepZone(false, d) } }, unit = range, tag = "${tag}_zone_low",
                    error = problem(state, stepIndex, blockIndex, EditorField.ZoneLow), keyboardType = KeyboardType.Number, modifier = m,
                )
                StepperField(
                    label = stringResource(R.string.editor_hr_zone_high), value = hr.customHigh,
                    onValue = { v -> actions.onEditHeartRate(stepIndex, blockIndex) { it.copy(customHigh = v) } },
                    onStep = { d -> edit { it.stepZone(true, d) } }, unit = range, tag = "${tag}_zone_high",
                    error = problem(state, stepIndex, blockIndex, EditorField.ZoneHigh), keyboardType = KeyboardType.Number, modifier = m,
                )
            }
            if (wide) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) { zoneFields(Modifier.weight(1f)) }
            else zoneFields(Modifier)
        }
        // The basis of the percentages: the reserve (Karvonen) needs a resting heart rate.
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            ChoiceChip(stringResource(R.string.editor_hr_basis_max), !hr.reserve, "${tag}_basis_max", { actions.onReserve(stepIndex, blockIndex, false) })
            ChoiceChip(stringResource(R.string.editor_hr_basis_reserve), hr.reserve, "${tag}_basis_reserve", { actions.onReserve(stepIndex, blockIndex, true) }, enabled = state.hasResting)
        }
        if (!state.hasResting) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(stringResource(R.string.editor_hr_reserve_hint), Modifier.weight(1f).testTag("${tag}_reserve_hint"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                RowButton(stringResource(R.string.editor_hr_measure_resting), actions.onOpenResting, Modifier.testTag("${tag}_resting_link"))
            }
        }
        val bpm = state.zoneBpm[key]
        Text(
            if (bpm != null) stringResource(R.string.editor_hr_zone_bpm, bpm.lowBpm, bpm.highBpm, state.heartRateProfile?.maxBpm ?: 0)
            else stringResource(R.string.editor_hr_zone_unknown),
            Modifier.testTag("${tag}_zone_bpm"),
            style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
        )
        // What the control moves, and between which bounds.
        Text(stringResource(R.string.editor_hr_adjust), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            ChoiceChip(stringResource(R.string.editor_hr_adjust_speed), hr.adjust == HeartRateAdjust.Speed, "${tag}_adjust_speed", { actions.onAdjust(stepIndex, blockIndex, HeartRateAdjust.Speed) })
            ChoiceChip(stringResource(R.string.editor_hr_adjust_incline), hr.adjust == HeartRateAdjust.Incline, "${tag}_adjust_incline", { actions.onAdjust(stepIndex, blockIndex, HeartRateAdjust.Incline) })
        }
        val speed = hr.adjust == HeartRateAdjust.Speed
        val unit = stringResource(if (speed) R.string.unit_speed else R.string.unit_incline)
        val bounds: @Composable (Modifier) -> Unit = { m ->
            StepperField(
                label = stringResource(if (speed) R.string.editor_hr_min_speed else R.string.editor_hr_min_incline), value = hr.minimum,
                onValue = { v -> actions.onHeartRateBound(stepIndex, blockIndex, false, v) },
                onStep = { d -> edit { it.stepHeartRateBound(false, d) } }, unit = unit, tag = "${tag}_min",
                error = problem(state, stepIndex, blockIndex, EditorField.HeartRateMin, hr.adjust),
                keyboardType = if (speed) KeyboardType.Decimal else KeyboardType.Number, modifier = m,
            )
            StepperField(
                label = stringResource(if (speed) R.string.editor_hr_max_speed else R.string.editor_hr_max_incline), value = hr.maximum,
                onValue = { v -> actions.onHeartRateBound(stepIndex, blockIndex, true, v) },
                onStep = { d -> edit { it.stepHeartRateBound(true, d) } }, unit = unit, tag = "${tag}_max",
                error = problem(state, stepIndex, blockIndex, EditorField.HeartRateMax, hr.adjust),
                keyboardType = if (speed) KeyboardType.Decimal else KeyboardType.Number, modifier = m,
            )
        }
        if (wide) Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) { bounds(Modifier.weight(1f)) }
        else bounds(Modifier)
        if (!speed) Text(stringResource(R.string.editor_hr_walking_note), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        // How the block is controlled (the 6-minute rule of the program runner).
        state.heartRateModes[key]?.let { mode ->
            Text(
                stringResource(if (mode == HeartRateControlMode.Continuous) R.string.editor_hr_mode_continuous else R.string.editor_hr_mode_between),
                Modifier.testTag("${tag}_mode"),
                style = MaterialTheme.typography.bodyMedium,
            )
        }
        if (tooShort) {
            Text(stringResource(R.string.editor_hr_short_block), Modifier.testTag("${tag}_hr_short"), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.extra.warning)
        }
    }
}
