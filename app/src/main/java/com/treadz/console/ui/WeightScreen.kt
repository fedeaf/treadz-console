package com.treadz.console.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.treadz.console.R
import com.treadz.console.userdata.InputField
import com.treadz.console.userdata.WeightEntry
import com.treadz.console.weight.WeightEditorKind
import com.treadz.console.weight.WeightEditorUi
import com.treadz.console.weight.WeightUiState
import java.time.LocalDate
import kotlin.math.abs
import kotlin.math.roundToLong

data class WeightActions(
    val onBack: () -> Unit = {},
    val onAdd: () -> Unit = {},
    val onEdit: (WeightEntry) -> Unit = {},
    val onDelete: (Long) -> Unit = {},
    val onConfirmDelete: () -> Unit = {},
    val onCancelDelete: () -> Unit = {},
    val onSetTarget: () -> Unit = {},
    val onEditorDate: (LocalDate) -> Unit = {},
    val onEditorWeight: (String) -> Unit = {},
    val onSaveEditor: () -> Unit = {},
    val onDismissEditor: () -> Unit = {},
    val onClearTarget: () -> Unit = {},
)

@Composable
fun WeightScreen(state: WeightUiState, today: LocalDate, actions: WeightActions, modifier: Modifier = Modifier) {
    val layout = LocalLayout.current
    val user = state.user
    Column(modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ScreenHeader(
            title = stringResource(R.string.weight_title),
            subtitle = user?.displayName(),
            onBack = actions.onBack,
        )
        if (user == null) {
            if (state.loaded) Text(stringResource(R.string.no_user_chosen), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.titleMedium)
        } else if (layout.wide) {
            Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Column(Modifier.weight(1.5f).fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Summary(state, actions)
                    if (state.onlyPlaceholder) PlaceholderBanner(state, actions)
                    WeightChartPanel(state, Modifier.weight(1f).fillMaxWidth())
                }
                Column(Modifier.weight(1f).fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    EntryList(state, actions, Modifier.weight(1f).fillMaxWidth())
                    BigButton(stringResource(R.string.weight_add), actions.onAdd, Modifier.fillMaxWidth().testTag("add_weight_button"))
                }
            }
        } else {
            LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag("weight_list"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item { Column(verticalArrangement = Arrangement.spacedBy(12.dp)) { Summary(state, actions) } }
                if (state.onlyPlaceholder) item { PlaceholderBanner(state, actions) }
                item { WeightChartPanel(state, Modifier.fillMaxWidth().height(220.dp)) }
                entryItems(state, actions)
            }
            BigButton(stringResource(R.string.weight_add), actions.onAdd, Modifier.fillMaxWidth().testTag("add_weight_button"))
        }
        if (state.failed) ErrorText(stringResource(R.string.error_storage), Modifier.testTag("weight_error"))
    }

    state.editor?.let { WeightEditorDialog(it, today, actions) }
    state.confirmingDeleteId?.let { id ->
        val entry = state.entries.firstOrNull { it.id == id }
        ConfirmDialog(
            title = stringResource(R.string.weight_delete_title),
            text = entry?.let { stringResource(R.string.weight_delete_body, Format.date(it.date, currentLocale()), Format.weight(it.weightKg)) }.orEmpty(),
            confirmText = stringResource(R.string.action_delete),
            onConfirm = actions.onConfirmDelete,
            onDismiss = actions.onCancelDelete,
        )
    }
}

@Composable
private fun Summary(state: WeightUiState, actions: WeightActions) {
    val locale = currentLocale()
    val current = state.current
    val target = state.targetKg
    Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        StatTile(
            label = stringResource(R.string.weight_current),
            value = current?.let { Format.weight(it.weightKg) } ?: Format.NO_VALUE,
            unit = stringResource(R.string.unit_kg),
            note = if (current?.isPlaceholder == true) stringResource(R.string.weight_placeholder_tag) else current?.let { Format.date(it.date, locale) },
            valueTag = "current_weight",
            modifier = Modifier.weight(1f).fillMaxHeight(),
        )
        StatTile(
            label = stringResource(R.string.weight_target),
            value = target?.let { Format.weight(it) } ?: Format.NO_VALUE,
            unit = if (target != null) stringResource(R.string.unit_kg) else null,
            note = when {
                target == null -> stringResource(R.string.weight_target_none)
                current == null || current.isPlaceholder -> null
                abs(current.weightKg - target) < 0.05 -> stringResource(R.string.weight_target_reached)
                current.weightKg > target -> stringResource(R.string.weight_above_target, Format.weight(current.weightKg - target))
                else -> stringResource(R.string.weight_below_target, Format.weight(target - current.weightKg))
            },
            valueTag = "target_weight",
            modifier = Modifier.weight(1f).fillMaxHeight(),
        )
    }
    BigOutlinedButton(
        stringResource(if (target == null) R.string.weight_set_target else R.string.weight_change_target),
        actions.onSetTarget,
        Modifier.fillMaxWidth().testTag("set_target_button"),
    )
}

@Composable
private fun PlaceholderBanner(state: WeightUiState, actions: WeightActions) {
    val kg = state.current?.weightKg ?: 0.0
    Panel(Modifier.fillMaxWidth().testTag("placeholder_banner"), color = MaterialTheme.colorScheme.surfaceContainerHigh, borderColor = MaterialTheme.extra.warning) {
        Column(Modifier.padding(horizontal = 18.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(R.string.weight_placeholder_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.extra.warning)
            Text(stringResource(R.string.weight_placeholder_body, Format.weight(kg)), style = MaterialTheme.typography.bodyMedium)
            state.current?.let { entry ->
                RowButton(stringResource(R.string.weight_enter_real), { actions.onEdit(entry) }, Modifier.testTag("enter_real_weight_button"))
            }
        }
    }
}

@Composable
private fun WeightChartPanel(state: WeightUiState, modifier: Modifier) {
    val locale = currentLocale()
    val points = state.entries.map { ChartPoint(it.date.toEpochDay().toDouble(), it.weightKg, hollow = it.isPlaceholder) }
    val spanDays = if (points.size >= 2) points.last().x - points.first().x else 0.0
    val reference = state.targetKg?.let {
        ReferenceLine(it, stringResource(R.string.weight_chart_target, Format.weight(it)), MaterialTheme.extra.warning)
    }
    Panel(modifier.testTag("weight_chart")) {
        LineChart(
            points = points,
            color = MaterialTheme.colorScheme.primary,
            yLabel = { Format.weight(it) },
            xLabel = { Format.axisDate(LocalDate.ofEpochDay(it.roundToLong()), withYear = spanDays > 300, locale = locale) },
            description = stringResource(R.string.weight_chart_description),
            modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 12.dp),
            reference = reference,
            showDots = true,
            emptyText = stringResource(R.string.weight_empty),
        )
    }
}

@Composable
private fun EntryList(state: WeightUiState, actions: WeightActions, modifier: Modifier) {
    LazyColumn(modifier.testTag("weight_list"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        entryItems(state, actions)
    }
}

private fun LazyListScope.entryItems(state: WeightUiState, actions: WeightActions) {
    if (state.entries.isEmpty()) {
        item { Text(stringResource(R.string.weight_empty), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.titleMedium) }
        return
    }
    items(state.entries.asReversed(), key = { it.id }) { entry -> EntryRow(entry, actions) }
}

@Composable
private fun EntryRow(entry: WeightEntry, actions: WeightActions) {
    val locale = currentLocale()
    val placeholder = entry.isPlaceholder
    Panel(
        Modifier.fillMaxWidth().testTag("weight_entry_${entry.id}"),
        color = if (placeholder) MaterialTheme.colorScheme.background else MaterialTheme.colorScheme.surfaceContainer,
        borderColor = if (placeholder) MaterialTheme.extra.warning else MaterialTheme.colorScheme.outlineVariant,
    ) {
        Row(
            Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp),
        ) {
            Column(Modifier.weight(1f)) {
                Text(
                    "${Format.weight(entry.weightKg)} ${stringResource(R.string.unit_kg)}",
                    style = MaterialTheme.typography.titleLarge,
                    fontWeight = FontWeight.Bold,
                    color = if (placeholder) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface,
                )
                Text(Format.date(entry.date, locale), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (placeholder) Tag(stringResource(R.string.weight_placeholder_tag), MaterialTheme.extra.warning, Modifier.padding(top = 4.dp))
            }
            RowButton(stringResource(R.string.action_edit), { actions.onEdit(entry) }, Modifier.testTag("edit_weight_${entry.id}"))
            RowButton(stringResource(R.string.action_delete), { actions.onDelete(entry.id) }, Modifier.testTag("delete_weight_${entry.id}"), destructive = true)
        }
    }
}

@Composable
private fun WeightEditorDialog(editor: WeightEditorUi, today: LocalDate, actions: WeightActions) {
    val isTarget = editor.kind == WeightEditorKind.Target
    val weightField = if (isTarget) InputField.TargetWeight else InputField.Weight
    AlertDialog(
        onDismissRequest = actions.onDismissEditor,
        modifier = Modifier.fillMaxWidth(0.94f).widthIn(max = 560.dp).testTag("weight_editor"),
        // Own width: on a phone the platform default leaves the number field too narrow.
        properties = DialogProperties(usePlatformDefaultWidth = false),
        title = {
            Text(
                stringResource(
                    when (editor.kind) {
                        WeightEditorKind.Add -> R.string.weight_add_title
                        is WeightEditorKind.Edit -> R.string.weight_edit_title
                        WeightEditorKind.Target -> R.string.weight_target_title
                    },
                ),
                fontWeight = FontWeight.Bold,
            )
        },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                if (!isTarget) {
                    DateField(
                        label = stringResource(R.string.weight_date),
                        value = editor.date,
                        onChange = actions.onEditorDate,
                        today = today,
                        error = editor.errors[InputField.WeightDate]?.let { validationText(InputField.WeightDate, it) },
                        modifier = Modifier.testTag("weight_date_field"),
                    )
                }
                TextField(
                    label = stringResource(R.string.weight_field),
                    value = editor.weightText,
                    onChange = actions.onEditorWeight,
                    error = editor.errors[weightField]?.let { validationText(weightField, it) },
                    tag = "weight_field",
                    keyboardType = KeyboardType.Decimal,
                )
                if (editor.canClearTarget) {
                    TextButton(onClick = actions.onClearTarget, modifier = Modifier.height(48.dp).testTag("clear_target_button")) {
                        Text(stringResource(R.string.weight_clear_target), color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.titleMedium)
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = actions.onSaveEditor, enabled = !editor.saving, modifier = Modifier.height(48.dp).testTag("weight_save")) {
                Text(stringResource(R.string.action_save), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
            }
        },
        dismissButton = {
            TextButton(onClick = actions.onDismissEditor, modifier = Modifier.height(48.dp)) {
                Text(stringResource(R.string.action_cancel), style = MaterialTheme.typography.titleMedium)
            }
        },
    )
}
