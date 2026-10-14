package com.treadz.console.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.DialogProperties
import com.treadz.console.R
import com.treadz.console.hrm.resting.RestingInvalidReason
import com.treadz.console.resting.RestingPhase
import com.treadz.console.resting.RestingUiState
import com.treadz.console.userdata.InputField
import com.treadz.console.userdata.RestingHeartRateMeasurement
import com.treadz.console.userdata.RestingHeartRateSource
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

data class RestingActions(
    val onBack: () -> Unit = {},
    val onStart: () -> Unit = {},
    val onCancel: () -> Unit = {},
    val onReset: () -> Unit = {},
    val onOpenDevices: () -> Unit = {},
    val onAddManual: () -> Unit = {},
    val onManualText: (String) -> Unit = {},
    val onSaveManual: () -> Unit = {},
    val onDismissManual: () -> Unit = {},
    val onDelete: (Long) -> Unit = {},
    val onConfirmDelete: () -> Unit = {},
    val onCancelDelete: () -> Unit = {},
)

/**
 * Resting heart rate, with no treadmill involved: how to measure, the 3-minute measurement with the live heart rate and
 * the countdown, its result (saved when valid, with the "take it easy" warning when it is clearly above the usual), the
 * reference and the history as a chart and a list.
 */
@Composable
fun RestingHeartRateScreen(state: RestingUiState, actions: RestingActions, modifier: Modifier = Modifier) {
    val layout = LocalLayout.current
    Column(modifier.fillMaxSize().testTag("resting_screen"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ScreenHeader(
            title = stringResource(R.string.resting_title),
            subtitle = state.user?.displayName(),
            onBack = if (state.measuring) null else actions.onBack,
        )
        if (state.loaded && state.user == null) {
            Text(stringResource(R.string.no_user_chosen), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.titleMedium)
        } else if (layout.wide) {
            Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Column(Modifier.weight(1.1f).fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    MeasurementPanel(state, actions)
                    ReferencePanel(state)
                }
                if (!state.measuring) {
                    Column(Modifier.weight(1f).fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        HistoryChart(state, Modifier.fillMaxWidth().height(200.dp))
                        MeasurementList(state, actions, Modifier.weight(1f).fillMaxWidth())
                        BigOutlinedButton(stringResource(R.string.resting_add_manual), actions.onAddManual, Modifier.fillMaxWidth().testTag("resting_add_manual"))
                    }
                }
            }
        } else {
            LazyColumn(Modifier.weight(1f).fillMaxWidth().testTag("resting_list"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                item { MeasurementPanel(state, actions) }
                if (!state.measuring) {
                    item { ReferencePanel(state) }
                    item { HistoryChart(state, Modifier.fillMaxWidth().height(200.dp)) }
                    measurementItems(state, actions)
                    item { BigOutlinedButton(stringResource(R.string.resting_add_manual), actions.onAddManual, Modifier.fillMaxWidth().testTag("resting_add_manual")) }
                }
            }
        }
    }
    state.editor?.let { editor ->
        AlertDialog(
            onDismissRequest = actions.onDismissManual,
            modifier = Modifier.fillMaxWidth(0.94f).widthIn(max = 560.dp).testTag("resting_manual_dialog"),
            properties = DialogProperties(usePlatformDefaultWidth = false),
            title = { Text(stringResource(R.string.resting_manual_title), fontWeight = FontWeight.Bold) },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.resting_manual_body), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    TextField(
                        label = stringResource(R.string.resting_manual_field),
                        value = editor.text,
                        onChange = actions.onManualText,
                        error = editor.error?.let { validationText(InputField.RestingHeartRate, it) },
                        tag = "resting_manual_field",
                        keyboardType = KeyboardType.Number,
                    )
                }
            },
            confirmButton = {
                TextButton(onClick = actions.onSaveManual, enabled = !editor.saving, modifier = Modifier.height(48.dp).testTag("resting_manual_save")) {
                    Text(stringResource(R.string.action_save), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
                }
            },
            dismissButton = {
                TextButton(onClick = actions.onDismissManual, modifier = Modifier.height(48.dp)) {
                    Text(stringResource(R.string.action_cancel), style = MaterialTheme.typography.titleMedium)
                }
            },
        )
    }
    state.confirmingDeleteId?.let { id ->
        val entry = state.measurements.firstOrNull { it.id == id }
        ConfirmDialog(
            title = stringResource(R.string.resting_delete_title),
            text = entry?.let { stringResource(R.string.resting_delete_body, it.bpm, Format.dateTime(it.measuredAtMillis, currentLocale())) }.orEmpty(),
            confirmText = stringResource(R.string.action_delete),
            onConfirm = actions.onConfirmDelete,
            onDismiss = actions.onCancelDelete,
        )
    }
}

// ---------------------------------------------------------------------------------------------------------------
// The measurement: instructions, countdown, result

@Composable
private fun MeasurementPanel(state: RestingUiState, actions: RestingActions) {
    Panel(Modifier.fillMaxWidth().testTag("resting_panel")) {
        Column(Modifier.padding(horizontal = 18.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            when (state.phase) {
                RestingPhase.Instructions -> Instructions(state, actions)
                RestingPhase.Measuring -> Measuring(state, actions)
                RestingPhase.Result -> ResultPanel(state, actions)
            }
        }
    }
}

@Composable
private fun Instructions(state: RestingUiState, actions: RestingActions) {
    Text(stringResource(R.string.resting_instructions_title), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
    Text(stringResource(R.string.resting_instructions_body), Modifier.testTag("resting_instructions"), style = MaterialTheme.typography.bodyLarge)
    val source = state.live.source
    if (source != null) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            HeartRateSourceIcon(source)
            Text(
                state.live.bpm?.let { stringResource(R.string.resting_source_with_bpm, source.label(), it) } ?: stringResource(R.string.resting_source_no_reading, source.label()),
                Modifier.testTag("resting_source"),
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    } else {
        Text(stringResource(R.string.resting_needs_strap), Modifier.testTag("resting_needs_strap"), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold, color = MaterialTheme.extra.warning)
        RowButton(stringResource(R.string.resting_open_devices), actions.onOpenDevices, Modifier.testTag("resting_open_devices"))
    }
    BigButton(stringResource(R.string.resting_start), actions.onStart, Modifier.fillMaxWidth().testTag("resting_start"), enabled = state.canStart)
}

@Composable
private fun Measuring(state: RestingUiState, actions: RestingActions) {
    val progress = state.progress
    val total = (progress.elapsedSeconds + progress.remainingSeconds).coerceAtLeast(1)
    Text(stringResource(R.string.resting_measuring_title), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
    Text(
        Format.duration(progress.remainingSeconds),
        Modifier.fillMaxWidth().testTag("resting_countdown"),
        fontSize = 72.sp,
        fontWeight = FontWeight.Bold,
        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
    )
    LinearProgressIndicator(
        progress = { (progress.elapsedSeconds.toFloat() / total).coerceIn(0f, 1f) },
        modifier = Modifier.fillMaxWidth().height(8.dp),
        color = MaterialTheme.colorScheme.primary,
        trackColor = MaterialTheme.colorScheme.outlineVariant,
    )
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.CenterHorizontally), verticalAlignment = Alignment.CenterVertically) {
        state.live.source?.let { HeartRateSourceIcon(it, size = 28.dp) }
        Text(Format.heartRate(progress.currentBpm ?: state.live.bpm), Modifier.testTag("resting_live_bpm"), fontSize = 48.sp, fontWeight = FontWeight.Bold)
        Text(stringResource(R.string.unit_heart_rate), Modifier.padding(top = 18.dp), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    Text(stringResource(R.string.resting_stay_still), Modifier.fillMaxWidth(), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
    BigOutlinedButton(stringResource(R.string.action_cancel), actions.onCancel, Modifier.fillMaxWidth().testTag("resting_cancel"))
}

@Composable
private fun ResultPanel(state: RestingUiState, actions: RestingActions) {
    val result = state.result
    if (result == null) return
    if (result.valid && result.bpm != null) {
        Text(stringResource(R.string.resting_result_title), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(Format.whole(result.bpm!!), Modifier.testTag("resting_result_bpm"), fontSize = 64.sp, fontWeight = FontWeight.Bold)
            Text(stringResource(R.string.unit_heart_rate), Modifier.padding(bottom = 12.dp), style = MaterialTheme.typography.titleLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Tag(
                stringResource(if (state.saved != null) R.string.resting_saved else R.string.resting_valid),
                MaterialTheme.extra.ok,
                Modifier.padding(start = 6.dp, bottom = 14.dp).testTag("resting_valid_tag"),
            )
        }
        result.rmssdMs?.let {
            FactRow(stringResource(R.string.resting_rmssd), stringResource(R.string.resting_rmssd_value, Format.number("%.0f", it)), Modifier.testTag("resting_rmssd"))
        }
        if (state.saveFailed) ErrorText(stringResource(R.string.error_storage), Modifier.testTag("resting_save_error"))
        state.elevated?.let { assessment ->
            Panel(Modifier.fillMaxWidth().testTag("resting_elevated"), color = MaterialTheme.colorScheme.surfaceContainerHigh, borderColor = MaterialTheme.extra.warning) {
                Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(stringResource(R.string.resting_elevated_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.extra.warning)
                    Text(
                        stringResource(R.string.resting_elevated_body, assessment.deltaBpm ?: 0, assessment.referenceBpm ?: 0),
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }
            }
        }
    } else {
        Text(stringResource(R.string.resting_invalid_title), Modifier.testTag("resting_invalid"), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.error)
        Text(stringResource(R.string.resting_invalid_not_saved), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        result.invalidReasons.forEach { reason ->
            Text("• " + reason.text(), Modifier.testTag("resting_invalid_reason"), style = MaterialTheme.typography.bodyLarge)
        }
    }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        BigOutlinedButton(stringResource(R.string.resting_measure_again), actions.onStart, Modifier.weight(1f).testTag("resting_again"), enabled = state.canStart)
        BigButton(stringResource(R.string.action_done), actions.onReset, Modifier.weight(1f).testTag("resting_done"))
    }
}

@Composable
private fun RestingInvalidReason.text(): String = stringResource(
    when (this) {
        RestingInvalidReason.TooShort -> R.string.resting_reason_too_short
        RestingInvalidReason.MissingReadings -> R.string.resting_reason_missing
        RestingInvalidReason.UnreliableReadings -> R.string.resting_reason_unreliable
        RestingInvalidReason.ImplausibleValue -> R.string.resting_reason_implausible
    },
)

// ---------------------------------------------------------------------------------------------------------------
// Reference, chart, list

@Composable
private fun ReferencePanel(state: RestingUiState) {
    val reference = state.reference
    Panel(Modifier.fillMaxWidth().testTag("resting_reference")) {
        Column(Modifier.padding(horizontal = 18.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(stringResource(R.string.resting_reference_title), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            if (reference == null) {
                Text(stringResource(R.string.resting_reference_none), Modifier.testTag("resting_reference_none"), style = MaterialTheme.typography.bodyLarge)
            } else {
                Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(Format.whole(reference.bpm), Modifier.testTag("resting_reference_bpm"), style = MaterialTheme.typography.displaySmall, fontWeight = FontWeight.Bold)
                    Text(stringResource(R.string.unit_heart_rate), Modifier.padding(bottom = 6.dp), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                Text(restingOriginText(reference), Modifier.testTag("resting_reference_origin"), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                Text(restingStatusExplanation(reference), Modifier.testTag("resting_reference_explanation"), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

@Composable
private fun HistoryChart(state: RestingUiState, modifier: Modifier) {
    val locale = currentLocale()
    val points = state.measurements.map { ChartPoint(it.measuredAtMillis / DAY_MILLIS, it.bpm.toDouble()) }
    val spanDays = if (points.size >= 2) points.last().x - points.first().x else 0.0
    Panel(modifier.testTag("resting_chart")) {
        LineChart(
            points = points,
            color = MaterialTheme.colorScheme.primary,
            yLabel = { Format.whole(it.toInt()) },
            xLabel = { Format.axisDate(LocalDate.ofEpochDay(it.toLong()), withYear = spanDays > 300, locale = locale) },
            description = stringResource(R.string.resting_chart_description),
            modifier = Modifier.fillMaxSize().padding(horizontal = 12.dp, vertical = 12.dp),
            showDots = true,
            emptyText = stringResource(R.string.resting_empty),
        )
    }
}

private const val DAY_MILLIS = 86_400_000.0

@Composable
private fun MeasurementList(state: RestingUiState, actions: RestingActions, modifier: Modifier) {
    LazyColumn(modifier.testTag("resting_history"), verticalArrangement = Arrangement.spacedBy(8.dp)) { measurementItems(state, actions) }
}

private fun LazyListScope.measurementItems(state: RestingUiState, actions: RestingActions) {
    if (state.measurements.isEmpty()) {
        item { Text(stringResource(R.string.resting_empty), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.titleMedium) }
        return
    }
    items(state.measurements.asReversed(), key = { it.id }) { m -> MeasurementRow(m, actions) }
}

@Composable
private fun MeasurementRow(m: RestingHeartRateMeasurement, actions: RestingActions) {
    Panel(Modifier.fillMaxWidth().testTag("resting_entry_${m.id}")) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Column(Modifier.weight(1f)) {
                Text("${Format.whole(m.bpm)} ${stringResource(R.string.unit_heart_rate)}", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text(
                    Format.dateTime(m.measuredAtMillis, currentLocale()) + " · " + m.source.label(),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            RowButton(stringResource(R.string.action_delete), { actions.onDelete(m.id) }, Modifier.testTag("delete_resting_${m.id}"), destructive = true)
        }
    }
}

@Composable
private fun RestingHeartRateSource.label(): String = stringResource(
    when (this) {
        RestingHeartRateSource.Manual -> R.string.resting_source_manual
        RestingHeartRateSource.Strap -> R.string.hr_source_strap
        RestingHeartRateSource.Console -> R.string.hr_source_console
    },
)
