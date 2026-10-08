package com.treadz.console.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import com.treadz.console.R
import com.treadz.console.userdata.FitnessLevel
import com.treadz.console.userdata.Goal
import com.treadz.console.userdata.InputField
import com.treadz.console.userdata.Sex
import com.treadz.console.userdata.UserDataLimits
import com.treadz.console.users.EditorMode
import com.treadz.console.users.ProfileEditorUiState
import java.time.LocalDate

/** Actions of the profile editor. */
data class ProfileEditorActions(
    val onBack: () -> Unit = {},
    val onName: (String) -> Unit = {},
    val onSex: (Sex) -> Unit = {},
    val onHeight: (String) -> Unit = {},
    val onBirthDate: (LocalDate) -> Unit = {},
    val onRestingHeartRate: (String) -> Unit = {},
    val onMaxHeartRate: (String) -> Unit = {},
    val onFitnessLevel: (FitnessLevel?) -> Unit = {},
    val onGoal: (Goal?) -> Unit = {},
    val onWeight: (String) -> Unit = {},
    val onSave: () -> Unit = {},
    val onDelete: () -> Unit = {},
    val onConfirmDelete: () -> Unit = {},
    val onCancelDelete: () -> Unit = {},
)

@Composable
fun ProfileEditorScreen(
    state: ProfileEditorUiState,
    today: LocalDate,
    actions: ProfileEditorActions,
    modifier: Modifier = Modifier,
) {
    val layout = LocalLayout.current
    val title = when (state.mode) {
        EditorMode.Create -> stringResource(R.string.editor_title_create)
        EditorMode.Edit -> stringResource(R.string.editor_title_edit)
        EditorMode.Personalize -> stringResource(R.string.editor_title_personalize)
    }
    Column(modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ScreenHeader(title = title, onBack = actions.onBack)
        when {
            state.notFound -> Text(
                stringResource(R.string.editor_not_found),
                Modifier.testTag("editor_not_found"),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            state.loading -> Unit
            else -> {
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.TopCenter) {
                    Column(
                        Modifier
                            .widthIn(max = if (layout.wide) 1100.dp else 640.dp)
                            .fillMaxSize()
                            .verticalScroll(rememberScrollState()),
                        verticalArrangement = Arrangement.spacedBy(14.dp),
                    ) {
                        if (state.mode == EditorMode.Personalize) {
                            Text(
                                stringResource(R.string.editor_personalize_intro),
                                style = MaterialTheme.typography.bodyLarge,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                        if (layout.wide) {
                            Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                                    IdentityFields(state, today, actions)
                                }
                                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                                    TrainingFields(state, actions)
                                    DeleteSection(state, actions)
                                }
                            }
                        } else {
                            IdentityFields(state, today, actions)
                            TrainingFields(state, actions)
                            DeleteSection(state, actions)
                        }
                    }
                }
                // Always visible: the form can be longer than the screen.
                SaveBar(state, actions)
            }
        }
    }
    if (state.confirmingDelete) {
        ConfirmDialog(
            title = stringResource(R.string.delete_user_title, state.form.name.ifBlank { stringResource(R.string.generic_user_label) }),
            text = stringResource(if (state.syncLinked) R.string.delete_user_body_synced else R.string.delete_user_body),
            confirmText = stringResource(R.string.action_delete),
            onConfirm = actions.onConfirmDelete,
            onDismiss = actions.onCancelDelete,
        )
    }
}

@Composable
private fun IdentityFields(state: ProfileEditorUiState, today: LocalDate, actions: ProfileEditorActions) {
    val form = state.form
    val errors = state.errors
    @Composable fun error(field: InputField): String? = errors[field]?.let { code -> validationText(field, code) }

    TextField(
        label = stringResource(R.string.field_name),
        value = form.name,
        onChange = actions.onName,
        error = error(InputField.Name),
        tag = "field_name",
        keyboardType = KeyboardType.Text,
    )
    Choice(
        label = stringResource(R.string.field_sex),
    ) {
        Sex.entries.forEach { sex ->
            BigChip(sex.label(), selected = form.sex == sex, onClick = { actions.onSex(sex) }, tag = "sex_${sex.name}")
        }
    }
    TextField(
        label = stringResource(R.string.field_height),
        value = form.height,
        onChange = actions.onHeight,
        error = error(InputField.Height),
        tag = "field_height",
        keyboardType = KeyboardType.Number,
        supporting = stringResource(R.string.hint_height_range, UserDataLimits.HEIGHT_CM.first, UserDataLimits.HEIGHT_CM.last),
    )
    DateField(
        label = stringResource(R.string.field_birth_date),
        value = form.birthDate,
        onChange = actions.onBirthDate,
        today = today,
        error = error(InputField.BirthDate),
        modifier = Modifier.testTag("field_birth_date"),
    )
    if (state.mode == EditorMode.Personalize) {
        TextField(
            label = stringResource(R.string.field_weight_personalize),
            value = form.weight,
            onChange = actions.onWeight,
            error = error(InputField.Weight),
            tag = "field_weight",
            keyboardType = KeyboardType.Decimal,
            supporting = stringResource(R.string.hint_weight_personalize),
        )
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TrainingFields(state: ProfileEditorUiState, actions: ProfileEditorActions) {
    val form = state.form
    @Composable fun error(field: InputField): String? = state.errors[field]?.let { code -> validationText(field, code) }

    TextField(
        label = stringResource(R.string.field_resting_hr),
        value = form.restingHeartRate,
        onChange = actions.onRestingHeartRate,
        error = error(InputField.RestingHeartRate),
        tag = "field_resting_hr",
        keyboardType = KeyboardType.Number,
    )
    val estimated = state.estimatedMaxHeartRate
    TextField(
        label = stringResource(R.string.field_max_hr),
        value = form.maxHeartRate,
        onChange = actions.onMaxHeartRate,
        error = error(InputField.MaxHeartRate),
        tag = "field_max_hr",
        keyboardType = KeyboardType.Number,
        supporting = when {
            form.maxHeartRate.isNotBlank() -> null
            estimated != null -> stringResource(R.string.hint_max_hr_estimated, estimated)
            else -> stringResource(R.string.hint_max_hr_needs_birth)
        },
        supportingTag = "max_hr_estimate",
    )
    Choice(label = stringResource(R.string.field_fitness)) {
        BigChip(stringResource(R.string.option_not_set), selected = form.fitnessLevel == null, onClick = { actions.onFitnessLevel(null) }, tag = "fitness_none")
        FitnessLevel.entries.forEach { level ->
            BigChip(level.label(), selected = form.fitnessLevel == level, onClick = { actions.onFitnessLevel(level) }, tag = "fitness_${level.name}")
        }
    }
    Choice(label = stringResource(R.string.field_goal)) {
        BigChip(stringResource(R.string.option_not_set), selected = form.goal == null, onClick = { actions.onGoal(null) }, tag = "goal_none")
        Goal.entries.forEach { goal ->
            BigChip(goal.label(), selected = form.goal == goal, onClick = { actions.onGoal(goal) }, tag = "goal_${goal.name}")
        }
    }
}

@Composable
private fun SaveBar(state: ProfileEditorUiState, actions: ProfileEditorActions) {
    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
        if (state.saveFailed) ErrorText(stringResource(R.string.error_storage), Modifier.padding(bottom = 6.dp).testTag("editor_error"))
        BigButton(
            text = stringResource(R.string.action_save),
            onClick = actions.onSave,
            enabled = !state.saving,
            modifier = Modifier.widthIn(max = 640.dp).fillMaxWidth().testTag("save_button"),
        )
    }
}

@Composable
private fun DeleteSection(state: ProfileEditorUiState, actions: ProfileEditorActions) {
    if (!state.canDelete) return
    Column(Modifier.padding(top = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        BigOutlinedButton(
            text = stringResource(R.string.editor_delete),
            onClick = actions.onDelete,
            enabled = !state.deleteLockedByBelt && !state.saving,
            destructive = true,
            modifier = Modifier.fillMaxWidth().testTag("delete_button"),
        )
        if (state.deleteLockedByBelt) {
            Text(
                stringResource(R.string.delete_user_locked),
                Modifier.testTag("delete_locked_notice"),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** Outlined text field with its error or hint below. */
@Composable
fun TextField(
    label: String,
    value: String,
    onChange: (String) -> Unit,
    error: String?,
    tag: String,
    keyboardType: KeyboardType,
    modifier: Modifier = Modifier,
    supporting: String? = null,
    supportingTag: String? = null,
) {
    OutlinedTextField(
        value = value,
        onValueChange = onChange,
        label = { Text(label) },
        isError = error != null,
        supportingText = {
            val text = error ?: supporting
            if (text != null) {
                Text(text, modifier = if (supportingTag != null && error == null) Modifier.testTag(supportingTag) else Modifier, style = MaterialTheme.typography.bodyMedium, fontWeight = if (error != null) FontWeight.SemiBold else FontWeight.Normal)
            }
        },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = keyboardType),
        textStyle = MaterialTheme.typography.titleLarge,
        modifier = modifier.fillMaxWidth().heightIn(min = 64.dp).testTag(tag),
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Choice(label: String, content: @Composable () -> Unit) {
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            content()
        }
    }
}

@Composable
internal fun BigChip(text: String, selected: Boolean, onClick: () -> Unit, tag: String) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        label = { Text(text, style = MaterialTheme.typography.titleMedium, modifier = Modifier.padding(vertical = 10.dp)) },
        modifier = Modifier.testTag(tag),
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
            selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
        ),
        border = FilterChipDefaults.filterChipBorder(
            enabled = true,
            selected = selected,
            borderColor = MaterialTheme.colorScheme.outline,
            selectedBorderColor = MaterialTheme.colorScheme.primary,
            borderWidth = 1.dp,
            selectedBorderWidth = 2.dp,
        ),
    )
}
