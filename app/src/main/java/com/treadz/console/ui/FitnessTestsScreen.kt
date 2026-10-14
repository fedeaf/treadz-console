package com.treadz.console.ui

import androidx.compose.foundation.clickable
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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.treadz.console.R
import com.treadz.console.programs.fitness.FitnessProfile
import com.treadz.console.programs.fitness.FitnessTestType
import com.treadz.console.programs.fitness.Validity
import com.treadz.console.training.FitnessStartUiState
import com.treadz.console.training.FitnessTestsUiState
import com.treadz.console.userdata.FitnessTestRecord

data class FitnessTestsActions(
    val onBack: () -> Unit = {},
    val onStartTest: (FitnessTestType) -> Unit = {},
    val onOpenResult: (String) -> Unit = {},
    val onDelete: (String) -> Unit = {},
    val onConfirmDelete: () -> Unit = {},
    val onCancelDelete: () -> Unit = {},
    val onUseMeasuredHeartRateMax: () -> Unit = {},
    val onKeepManualHeartRateMax: () -> Unit = {},
)

/** The two tests to choose from, the user's fitness figures and the list of their past tests. */
@Composable
fun FitnessTestsScreen(state: FitnessTestsUiState, actions: FitnessTestsActions, modifier: Modifier = Modifier) {
    val wide = LocalLayout.current.wide
    Column(modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ScreenHeader(title = stringResource(R.string.fitness_tests_title), subtitle = state.user?.displayName(), onBack = actions.onBack)
        if (wide) {
            Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Column(Modifier.weight(1f).fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Intro()
                    TestCards(state, actions)
                }
                Column(Modifier.weight(1f).fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    Fitness(state, actions)
                    History(state, actions)
                }
            }
        } else {
            Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Fitness(state, actions)
                Intro()
                TestCards(state, actions)
                History(state, actions)
            }
        }
        if (state.failed) ErrorText(stringResource(R.string.error_storage))
    }
    state.confirmingDeleteId?.let {
        ConfirmDialog(
            title = stringResource(R.string.fitness_result_delete_title),
            text = stringResource(R.string.fitness_result_delete_body),
            confirmText = stringResource(R.string.action_delete),
            onConfirm = actions.onConfirmDelete,
            onDismiss = actions.onCancelDelete,
        )
    }
}

@Composable
private fun Intro() {
    Text(stringResource(R.string.fitness_tests_intro), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** The tests the screen offers, in the order they are listed. */
private val OFFERED_TESTS = listOf(FitnessTestType.EbbelingWalk, FitnessTestType.MaxIncrementalRun, FitnessTestType.MaxHeartRateWalk)

@Composable
private fun TestCards(state: FitnessTestsUiState, actions: FitnessTestsActions) {
    for (type in OFFERED_TESTS) {
        Panel(Modifier.fillMaxWidth().testTag("test_card_${type.name}")) {
            Column(Modifier.padding(horizontal = 18.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(type.label(), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                Text(type.summary(), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                BigButton(
                    stringResource(R.string.fitness_test_choose), { actions.onStartTest(type) },
                    Modifier.fillMaxWidth().testTag("choose_${type.name}"), enabled = state.user != null,
                )
            }
        }
    }
    if (state.user == null && state.loaded) Text(stringResource(R.string.no_user_chosen), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

/** VO2max with its category, the maximal aerobic speed and the maximum heart rate with where it comes from. */
@Composable
private fun Fitness(state: FitnessTestsUiState, actions: FitnessTestsActions) {
    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
        HeartRateMaxQuestion(state.heartRateMax, actions.onUseMeasuredHeartRateMax, actions.onKeepManualHeartRateMax)
        FitnessSummaryPanel(state.profile, state.heartRateMax, Modifier.testTag("fitness_summary"))
    }
}

/** The fitness figures of a user (also shown in the profile). */
@Composable
fun FitnessSummaryPanel(profile: FitnessProfile?, heartRateMax: com.treadz.console.programs.fitness.HeartRateMaxDecision?, modifier: Modifier = Modifier) {
    Panel(modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            SectionLabel(stringResource(R.string.fitness_summary_title))
            val vo2 = profile?.vo2max
            if (vo2 != null) {
                FactRow(
                    stringResource(R.string.result_vo2max),
                    stringResource(R.string.value_vo2max, Format.weight(vo2.valueMlKgMin)) + (vo2.classification?.let { " · " + it.category.label() } ?: ""),
                    tag = "summary_vo2max",
                )
            } else {
                Text(stringResource(R.string.fitness_summary_none), Modifier.testTag("summary_none"), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            profile?.mas?.let {
                FactRow(
                    stringResource(R.string.result_mas),
                    stringResource(R.string.value_kmh, Format.speed(it.valueKmh)) + if (it.estimated) " (" + stringResource(R.string.fitness_summary_estimated) + ")" else "",
                    tag = "summary_mas",
                )
            }
            heartRateMax?.let { FactRow(stringResource(R.string.hrmax_label), hrMaxValue(it), tag = "summary_hrmax") }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun History(state: FitnessTestsUiState, actions: FitnessTestsActions) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        SectionLabel(stringResource(R.string.fitness_history_title))
        if (state.results.isEmpty()) {
            Text(stringResource(R.string.fitness_history_empty), Modifier.testTag("fitness_history_empty"), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        state.results.forEach { record -> ResultRow(record, actions) }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ResultRow(record: FitnessTestRecord, actions: FitnessTestsActions) {
    val locale = currentLocale()
    val result = record.result
    val valid = result.validity == Validity.Valid
    Panel(Modifier.fillMaxWidth().clickable(role = Role.Button, onClick = { actions.onOpenResult(record.id) }).testTag("test_${record.id}")) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp), itemVerticalAlignment = Alignment.CenterVertically) {
                Text(Format.dateTime(result.startedAtMillis, locale), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
                Tag(result.testType.label(), MaterialTheme.colorScheme.secondary)
                Tag(stringResource(if (valid) R.string.result_valid else R.string.result_invalid), if (valid) MaterialTheme.extra.ok else MaterialTheme.colorScheme.error)
            }
            result.outputs?.let { o ->
                Text(
                    stringResource(R.string.value_vo2max, Format.weight(o.vo2maxMlKgMin)) + (o.classification?.let { " · " + it.category.label() } ?: "") +
                        (o.masKmh?.let { " · " + stringResource(R.string.result_mas_short, Format.speed(it)) } ?: ""),
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
            // The walking heart-rate test measures the maximum heart rate and nothing else.
            result.measuredHrMaxCandidateBpm?.takeIf { result.testType == FitnessTestType.MaxHeartRateWalk }?.let { bpm ->
                Text(
                    stringResource(
                        if (result.effortAssessment == com.treadz.console.programs.fitness.EffortAssessment.Maximal) R.string.result_hrmax_line else R.string.result_peak_line,
                        bpm,
                    ) + (result.effortAssessment?.let { " · " + it.label() } ?: ""),
                    Modifier.testTag("test_hrmax_${record.id}"),
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
            Row(horizontalArrangement = Arrangement.End, modifier = Modifier.fillMaxWidth()) {
                RowButton(stringResource(R.string.action_delete), { actions.onDelete(record.id) }, Modifier.testTag("delete_test_${record.id}"), destructive = true)
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------------------------
// Start of a test

data class FitnessStartActions(
    val onBack: () -> Unit = {},
    val onSpeed: (String) -> Unit = {},
    val onSpeedStep: (Int) -> Unit = {},
    val onIncline: (String) -> Unit = {},
    val onInclineStep: (Int) -> Unit = {},
    val onRestoreDefaults: () -> Unit = {},
    val onStart: () -> Unit = {},
)

/** The warning, who is tested, the start speed and START. */
@Composable
fun FitnessStartScreen(state: FitnessStartUiState, actions: FitnessStartActions, modifier: Modifier = Modifier) {
    val wide = LocalLayout.current.wide
    Column(modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ScreenHeader(title = state.type.label(), onBack = actions.onBack)
        if (wide) {
            Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Column(Modifier.weight(1f).fillMaxSize().verticalScroll(rememberScrollState())) { WarningPanel(state.type) }
                Column(Modifier.weight(1f).fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    SubjectPanel(state)
                    SpeedPanelCard(state, actions)
                    HeartRateNow(state)
                }
            }
        } else {
            Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                WarningPanel(state.type)
                SubjectPanel(state)
                SpeedPanelCard(state, actions)
                HeartRateNow(state)
            }
        }
        StartTestBar(state, actions)
    }
}

/** The brief message before a test: the effort it involves and the conditions. */
@Composable
private fun WarningPanel(type: FitnessTestType) {
    val ebbeling = type == FitnessTestType.EbbelingWalk
    val walk = type == FitnessTestType.MaxHeartRateWalk
    Panel(Modifier.fillMaxWidth().testTag("test_warning"), borderColor = MaterialTheme.extra.warning) {
        Column(Modifier.padding(horizontal = 18.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (walk) {
                Text(stringResource(R.string.test_walk_measures), Modifier.testTag("test_walk_measures"), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                Text(stringResource(R.string.test_walk_duration), Modifier.testTag("test_walk_duration"), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Text(stringResource(R.string.test_warning_title), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = MaterialTheme.extra.warning)
            Text(
                stringResource(
                    when {
                        ebbeling -> R.string.test_warning_effort_ebbeling
                        walk -> R.string.test_warning_effort_walk
                        else -> R.string.test_warning_effort_max
                    },
                ),
                style = MaterialTheme.typography.bodyLarge,
            )
            val bullets = when {
                ebbeling -> listOf(R.string.test_warning_food, R.string.test_warning_exercise, R.string.test_warning_strap, R.string.test_warning_stop_ebbeling)
                walk -> listOf(R.string.test_warning_food, R.string.test_warning_exercise, R.string.test_warning_strap_walk, R.string.test_warning_handrails, R.string.test_warning_end_walk)
                else -> listOf(R.string.test_warning_food, R.string.test_warning_exercise, R.string.test_warning_strap, R.string.test_warning_end_max)
            }
            bullets.forEach { Text("• " + stringResource(it), style = MaterialTheme.typography.bodyLarge) }
        }
    }
}

@Composable
private fun SubjectPanel(state: FitnessStartUiState) {
    Panel(Modifier.fillMaxWidth().testTag("test_subject")) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            SectionLabel(stringResource(R.string.test_subject_title))
            state.user?.let { user ->
                FactRow(stringResource(R.string.field_name), user.displayName())
                FactRow(stringResource(R.string.test_subject_age_sex), stringResource(R.string.test_subject_age_sex_value, state.ageYears, user.sex.label()))
            }
            state.weight?.let {
                FactRow(stringResource(R.string.weight_title), stringResource(R.string.value_kg, Format.weight(it.weightKg)))
                if (it.isPlaceholder) Text(stringResource(R.string.test_subject_placeholder_weight), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.extra.warning)
            }
            state.heartRateMax?.let { FactRow(stringResource(R.string.hrmax_label), hrMaxValue(it), tag = "subject_hrmax") }
        }
    }
}

@Composable
private fun SpeedPanelCard(state: FitnessStartUiState, actions: FitnessStartActions) {
    val walk = state.type == FitnessTestType.MaxHeartRateWalk
    Panel(Modifier.fillMaxWidth().testTag("test_start_values")) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            StepperField(
                label = stringResource(if (walk) R.string.test_ramp_speed else R.string.test_start_speed), value = state.speedText, onValue = actions.onSpeed, onStep = actions.onSpeedStep,
                unit = stringResource(R.string.unit_speed), tag = "test_speed",
                error = if (state.speedInvalid && state.speedText.isNotBlank()) stringResource(R.string.err_speed_range, Format.speed(state.speedMin), Format.speed(state.speedMax)) else null,
                supporting = stringResource(
                    when (state.type) {
                        FitnessTestType.EbbelingWalk -> R.string.test_start_speed_hint_ebbeling
                        FitnessTestType.MaxIncrementalRun -> R.string.test_start_speed_hint_max
                        FitnessTestType.MaxHeartRateWalk -> R.string.test_start_speed_hint_walk
                    },
                    Format.speed(state.speedMin), Format.speed(state.speedMax),
                ),
            )
            if (walk) {
                StepperField(
                    label = stringResource(R.string.test_ramp_incline), value = state.inclineText, onValue = actions.onIncline, onStep = actions.onInclineStep,
                    unit = stringResource(R.string.unit_incline), tag = "test_incline", keyboardType = androidx.compose.ui.text.input.KeyboardType.Number,
                    error = if (state.inclineInvalid && state.inclineText.isNotBlank()) stringResource(R.string.err_incline_range, state.inclineMin.toInt(), state.inclineMax.toInt()) else null,
                    supporting = stringResource(R.string.test_start_incline_hint_walk, state.inclineMin.toInt(), state.inclineMax.toInt()),
                )
                state.warmUpSpeeds?.let { (first, second) ->
                    Text(
                        stringResource(R.string.test_walk_warm_up, Format.speed(first), Format.speed(second)),
                        Modifier.testTag("test_walk_warm_up"),
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
            }
            RowButton(
                stringResource(R.string.start_restore_defaults),
                actions.onRestoreDefaults,
                Modifier.align(Alignment.End).padding(top = 8.dp).testTag("restore_defaults_button"),
                enabled = !state.atDefaults,
            )
        }
    }
}

/** The heart rate the treadmill reports now: Ebbeling will not start without one. */
@Composable
private fun HeartRateNow(state: FitnessStartUiState) {
    val bpm = state.heartRateBpm
    val source = state.heartRateSource
    Row(Modifier.fillMaxWidth().testTag("test_heart_rate_now"), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        StatusDot(if (bpm != null) MaterialTheme.extra.ok else MaterialTheme.extra.warning, size = 14.dp)
        Text(
            if (bpm != null) stringResource(R.string.test_heart_rate_now, bpm) else stringResource(R.string.test_heart_rate_missing),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
        )
        if (bpm != null && source != null) {
            HeartRateSourceIcon(source, size = 18.dp)
            Text(source.label(), Modifier.testTag("test_heart_rate_source"), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun StartTestBar(state: FitnessStartUiState, actions: FitnessStartActions) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp), horizontalAlignment = Alignment.CenterHorizontally) {
        val failure = state.failure
        val message = when {
            failure != null -> failure.text()
            state.blocker != null -> state.blocker.text()
            else -> null
        }
        if (message != null) {
            Text(
                message,
                Modifier.fillMaxWidth().testTag("test_start_message").semantics { liveRegion = LiveRegionMode.Assertive },
                style = MaterialTheme.typography.titleSmall,
                fontWeight = if (failure != null) FontWeight.Bold else FontWeight.Normal,
                color = if (failure != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            BigButton(stringResource(R.string.test_start_button), actions.onStart, Modifier.weight(1f).height(72.dp).testTag("start_test_button"), enabled = state.canStart)
            if (state.starting) CircularProgressIndicator(Modifier.height(32.dp), strokeWidth = 4.dp)
        }
    }
}
