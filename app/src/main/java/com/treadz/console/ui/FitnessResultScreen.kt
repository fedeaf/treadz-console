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
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.treadz.console.R
import com.treadz.console.programs.fitness.EbbelingMeasurements
import com.treadz.console.programs.fitness.FitnessTestFlag
import com.treadz.console.programs.fitness.FitnessTestResult
import com.treadz.console.programs.fitness.FitnessTestType
import com.treadz.console.programs.fitness.FlagSeverity
import com.treadz.console.programs.fitness.HeartRateMaxDecision
import com.treadz.console.programs.fitness.MaxTestMeasurements
import com.treadz.console.programs.fitness.Validity
import com.treadz.console.training.FitnessComparison
import com.treadz.console.training.FitnessResultUiState

data class FitnessResultActions(
    val onBack: () -> Unit = {},
    val onDelete: () -> Unit = {},
    val onConfirmDelete: () -> Unit = {},
    val onCancelDelete: () -> Unit = {},
    val onUseMeasuredHeartRateMax: () -> Unit = {},
    val onKeepManualHeartRateMax: () -> Unit = {},
)

/** A stored fitness test result. */
@Composable
fun FitnessResultScreen(state: FitnessResultUiState, actions: FitnessResultActions, modifier: Modifier = Modifier) {
    val record = state.record
    val locale = currentLocale()
    Column(modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ScreenHeader(
            title = record?.result?.testType?.label() ?: stringResource(R.string.fitness_result_title),
            subtitle = record?.let { Format.dateTime(it.result.startedAtMillis, locale) },
            onBack = actions.onBack,
        )
        when {
            state.notFound -> Text(
                stringResource(R.string.fitness_result_not_found),
                Modifier.testTag("result_not_found"),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            record == null -> Unit
            else -> {
                Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    FitnessResultContent(
                        result = record.result,
                        comparison = state.comparison,
                        heartRateMax = state.heartRateMax,
                        saveFailed = false,
                        onUseMeasured = actions.onUseMeasuredHeartRateMax,
                        onKeepMine = actions.onKeepManualHeartRateMax,
                    )
                    BigOutlinedButton(stringResource(R.string.fitness_result_delete), actions.onDelete, Modifier.fillMaxWidth().testTag("delete_result_button"), destructive = true)
                    if (state.failed) ErrorText(stringResource(R.string.error_storage))
                }
            }
        }
    }
    if (state.confirmingDelete) {
        ConfirmDialog(
            title = stringResource(R.string.fitness_result_delete_title),
            text = stringResource(R.string.fitness_result_delete_body),
            confirmText = stringResource(R.string.action_delete),
            onConfirm = actions.onConfirmDelete,
            onDismiss = actions.onCancelDelete,
        )
    }
}

/**
 * The figures of a result: VO2max with its category, the maximal aerobic speed and heart rates of the maximal test, how
 * the effort was judged, what happened (flags, translated), the comparison with the previous test, and the question about
 * the maximum heart rate when a test measured something different from what the user set.
 */
@Composable
fun FitnessResultContent(
    result: FitnessTestResult,
    comparison: FitnessComparison?,
    heartRateMax: HeartRateMaxDecision?,
    saveFailed: Boolean,
    onUseMeasured: () -> Unit,
    onKeepMine: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val wide = LocalLayout.current.wide
    if (wide) {
        Row(modifier.fillMaxWidth().testTag("fitness_result"), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Headline(result)
                Figures(result)
            }
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                HeartRateMaxQuestion(heartRateMax, onUseMeasured, onKeepMine)
                comparison?.let { Comparison(it) }
                WhatHappened(result)
                MinutesChart(result)
                if (saveFailed) ErrorText(stringResource(R.string.fitness_result_save_failed), Modifier.testTag("result_save_failed"))
            }
        }
    } else {
        Column(modifier.fillMaxWidth().testTag("fitness_result"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Headline(result)
            HeartRateMaxQuestion(heartRateMax, onUseMeasured, onKeepMine)
            Figures(result)
            comparison?.let { Comparison(it) }
            WhatHappened(result)
            MinutesChart(result)
            if (saveFailed) ErrorText(stringResource(R.string.fitness_result_save_failed), Modifier.testTag("result_save_failed"))
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Headline(result: FitnessTestResult) {
    val valid = result.validity == Validity.Valid
    val outputs = result.outputs
    Panel(Modifier.fillMaxWidth().testTag("result_headline"), borderColor = if (valid) MaterialTheme.extra.ok else MaterialTheme.colorScheme.error) {
        Column(Modifier.padding(horizontal = 18.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(6.dp), itemVerticalAlignment = Alignment.CenterVertically) {
                Text(result.testType.label(), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Tag(
                    stringResource(if (valid) R.string.result_valid else R.string.result_invalid),
                    if (valid) MaterialTheme.extra.ok else MaterialTheme.colorScheme.error,
                    Modifier.testTag("result_validity"),
                )
            }
            if (outputs != null) {
                Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(stringResource(R.string.result_vo2max), Modifier.padding(bottom = 10.dp), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(
                        (if (outputs.vo2maxIsLowerBound) "≥ " else "") + Format.weight(outputs.vo2maxMlKgMin),
                        Modifier.testTag("result_vo2max"),
                        style = MaterialTheme.typography.displayMedium, fontWeight = FontWeight.Bold,
                    )
                    Text(stringResource(R.string.unit_vo2max), Modifier.padding(bottom = 10.dp), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
                outputs.classification?.let { c ->
                    Tag(c.category.label(), categoryColor(c.category), Modifier.testTag("result_category"))
                    Text(
                        stringResource(if (c.ageOutsideNorms) R.string.result_norms_outside else R.string.result_norms, result.inputs.sex.label(), c.ageGroup),
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    stringResource(R.string.result_error_margin, Format.weight(outputs.vo2maxSeeMlKgMin)),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Text(stringResource(R.string.result_invalid_explanation), Modifier.testTag("result_invalid_text"), style = MaterialTheme.typography.bodyLarge)
            }
        }
    }
}

@Composable
private fun categoryColor(category: com.treadz.console.programs.fitness.Vo2MaxCategory): Color = when (category) {
    com.treadz.console.programs.fitness.Vo2MaxCategory.Superior, com.treadz.console.programs.fitness.Vo2MaxCategory.Excellent -> MaterialTheme.extra.ok
    com.treadz.console.programs.fitness.Vo2MaxCategory.Good -> MaterialTheme.colorScheme.primary
    com.treadz.console.programs.fitness.Vo2MaxCategory.Fair -> MaterialTheme.extra.warning
    com.treadz.console.programs.fitness.Vo2MaxCategory.Poor -> MaterialTheme.colorScheme.error
}

@Composable
private fun com.treadz.console.programs.fitness.Sex.label(): String = stringResource(
    if (this == com.treadz.console.programs.fitness.Sex.Male) R.string.sex_male_plural else R.string.sex_female_plural,
)

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Figures(result: FitnessTestResult) {
    val locale = currentLocale()
    val outputs = result.outputs
    val max = result.measurements as? MaxTestMeasurements
    val ebbeling = result.measurements as? EbbelingMeasurements
    FlowRow(Modifier.fillMaxWidth().testTag("result_figures"), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalArrangement = Arrangement.spacedBy(12.dp), maxItemsInEachRow = 2) {
        val tile = Modifier.weight(1f)
        outputs?.masKmh?.let {
            StatTile(
                stringResource(R.string.result_mas), (if (outputs.masIsLowerBound) "≥ " else "") + Format.speed(it, locale), tile, unit = stringResource(R.string.unit_speed),
                note = if (outputs.masIsLowerBound) stringResource(R.string.result_mas_lower_bound) else null, valueTag = "result_mas",
            )
        }
        if (result.testType == FitnessTestType.MaxIncrementalRun) {
            val peak = max?.peakHeartRateBpm
            StatTile(
                stringResource(R.string.result_peak_hr), peak?.let { Format.whole(it.toInt(), locale) } ?: Format.NO_VALUE, tile,
                unit = if (peak != null) stringResource(R.string.unit_heart_rate) else null, valueTag = "result_peak_hr",
            )
            outputs?.hrMaxCandidateBpm?.let {
                StatTile(stringResource(R.string.result_measured_hrmax), Format.whole(it, locale), tile, unit = stringResource(R.string.unit_heart_rate), valueTag = "result_measured_hrmax")
            }
            outputs?.effort?.let {
                StatTile(stringResource(R.string.result_effort), it.label(), tile, valueTag = "result_effort")
            }
        }
        ebbeling?.let { m ->
            m.testSpeedKmh?.let { StatTile(stringResource(R.string.result_test_speed), Format.speed(it, locale), tile, unit = stringResource(R.string.unit_speed)) }
            m.steadyStateHeartRateBpm?.let { StatTile(stringResource(R.string.result_steady_hr), Format.whole(it.toInt(), locale), tile, unit = stringResource(R.string.unit_heart_rate)) }
        }
        StatTile(stringResource(R.string.result_effort_time), Format.duration(result.effortSeconds, locale), tile)
        StatTile(
            stringResource(R.string.result_subject),
            stringResource(R.string.result_subject_value, result.inputs.ageYears), tile,
            note = stringResource(R.string.value_kg, Format.weight(result.inputs.weightKg, locale)),
        )
    }
}

@Composable
private fun Comparison(c: FitnessComparison) {
    val locale = currentLocale()
    Panel(Modifier.fillMaxWidth().testTag("result_comparison")) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            SectionLabel(stringResource(R.string.result_comparison_title))
            Text(
                stringResource(R.string.result_comparison_vo2, signed(c.vo2maxDelta, locale), Format.weight(c.previousVo2max, locale), Format.date(java.time.Instant.ofEpochMilli(c.previousStartedAtMillis).atZone(java.time.ZoneId.systemDefault()).toLocalDate(), locale)),
                Modifier.testTag("comparison_vo2"),
                style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold,
            )
            c.masDelta?.let { Text(stringResource(R.string.result_comparison_mas, signed(it, locale)), style = MaterialTheme.typography.bodyLarge) }
            if (c.withinMeasurementError) {
                Text(stringResource(R.string.result_comparison_within_error), Modifier.testTag("comparison_within_error"), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

private fun signed(value: Double, locale: java.util.Locale): String = String.format(locale, "%+.1f", value)

@Composable
private fun WhatHappened(result: FitnessTestResult) {
    val flags = result.flags
    Panel(Modifier.fillMaxWidth().testTag("result_flags")) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            SectionLabel(stringResource(R.string.result_what_happened))
            Text(result.endReason.text(), Modifier.testTag("result_end_reason"), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
            if (flags.isEmpty()) {
                Text(stringResource(R.string.result_no_flags), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            flags.forEach { flag -> FlagRow(flag) }
        }
    }
}

@Composable
private fun FlagRow(flag: FitnessTestFlag) {
    val color = when (flag.severity) {
        FlagSeverity.Info -> MaterialTheme.colorScheme.onSurfaceVariant
        FlagSeverity.Warning -> MaterialTheme.extra.warning
        FlagSeverity.Invalidating -> MaterialTheme.colorScheme.error
    }
    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        StatusDot(color, size = 10.dp, modifier = Modifier.padding(top = 7.dp))
        Text(flag.text(), Modifier.testTag("flag_${flag.name}"), style = MaterialTheme.typography.bodyMedium, color = if (flag.severity == FlagSeverity.Info) MaterialTheme.colorScheme.onSurface else color)
    }
}

/** Heart rate at the end of each minute of the test. */
@Composable
private fun MinutesChart(result: FitnessTestResult) {
    val points = result.minutes.mapNotNull { m -> m.heartRateBpm?.let { ChartPoint(m.endSecond.toDouble(), it) } }
    if (points.size < 2) return
    val locale = currentLocale()
    Panel(Modifier.fillMaxWidth().height(200.dp).testTag("result_chart")) {
        Column(Modifier.padding(horizontal = 14.dp, vertical = 10.dp)) {
            Text(stringResource(R.string.result_hr_chart), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant)
            LineChart(
                points = points, color = MaterialTheme.colorScheme.error,
                yLabel = { Format.whole(it.toInt(), locale) }, xLabel = { Format.duration(it.toInt(), locale) },
                description = stringResource(R.string.result_hr_chart), modifier = Modifier.weight(1f).fillMaxWidth().padding(top = 6.dp), showDots = true,
            )
        }
    }
}
