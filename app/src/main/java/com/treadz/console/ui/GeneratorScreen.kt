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
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.treadz.console.R
import com.treadz.console.programs.personalised.CouchToFiveK
import com.treadz.console.programs.personalised.ExerciseMode
import com.treadz.console.programs.personalised.GeneratedProgram
import com.treadz.console.programs.personalised.GeneratorInput
import com.treadz.console.programs.personalised.GeneratorNotice
import com.treadz.console.programs.personalised.GeneratorNoticeCode
import com.treadz.console.training.GeneratorForm
import com.treadz.console.training.GeneratorUiState
import com.treadz.console.training.segments

data class GeneratorActions(
    val onBack: () -> Unit = {},
    val onMode: (ExerciseMode) -> Unit = {},
    val onDurationStep: (Int) -> Unit = {},
    val onSeriesStep: (Int) -> Unit = {},
    val onWalkingSpeed: (String) -> Unit = {},
    val onWalkingSpeedStep: (Int) -> Unit = {},
    val onChooseStartWeek: (Boolean) -> Unit = {},
    val onStartWeek: (Int) -> Unit = {},
    val onJogStep: (Int) -> Unit = {},
    val onRestoreDefaults: () -> Unit = {},
    val onStart: () -> Unit = {},
    val onOpenFitnessTests: () -> Unit = {},
    val onOpenWeight: () -> Unit = {},
    val onOpenProfile: () -> Unit = {},
    val onOpenResting: () -> Unit = {},
)

/** A choice among a few (mode, plan, week): a chip with a touch target big enough to use on the move. */
@Composable
fun ChoiceChip(text: String, selected: Boolean, tag: String, onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true) {
    FilterChip(
        selected = selected,
        onClick = onClick,
        enabled = enabled,
        label = { Text(text, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(vertical = 6.dp)) },
        modifier = modifier.testTag(tag),
        colors = FilterChipDefaults.filterChipColors(
            selectedContainerColor = MaterialTheme.colorScheme.primaryContainer,
            selectedLabelColor = MaterialTheme.colorScheme.onPrimaryContainer,
        ),
    )
}

/** A personalised program being set up: its inputs, the session generated from them and the user's data, and START. */
@Composable
fun GeneratorScreen(state: GeneratorUiState, actions: GeneratorActions, modifier: Modifier = Modifier) {
    val layout = LocalLayout.current
    val descriptor = state.descriptor
    Column(modifier.fillMaxSize().testTag("generator_screen"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ScreenHeader(
            title = descriptor?.let { stringResource(generatorNameRes(it.id)) } ?: stringResource(R.string.generator_unknown_name),
            subtitle = state.user?.displayName(),
            onBack = actions.onBack,
        )
        when {
            state.unknownGenerator -> Text(
                stringResource(R.string.generator_not_found),
                Modifier.testTag("generator_not_found"),
                style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            state.loading || descriptor == null -> Unit
            layout.wide -> {
                Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Column(Modifier.weight(0.9f).fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        Intro(state)
                        InputsPanel(state, actions)
                    }
                    Column(Modifier.weight(1.1f).fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        Preview(state, actions)
                    }
                }
                StartBar(state, actions)
            }
            else -> {
                Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Intro(state)
                    InputsPanel(state, actions)
                    Preview(state, actions)
                }
                StartBar(state, actions)
            }
        }
    }
}

@Composable
private fun Intro(state: GeneratorUiState) {
    val descriptor = state.descriptor ?: return
    Text(stringResource(generatorDescriptionRes(descriptor.id)), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    if (state.regenerating) {
        Panel(Modifier.fillMaxWidth().testTag("generator_regenerating"), borderColor = MaterialTheme.colorScheme.secondary) {
            Text(
                stringResource(R.string.generator_regenerating),
                Modifier.padding(horizontal = 16.dp, vertical = 10.dp), style = MaterialTheme.typography.bodyMedium,
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun InputsPanel(state: GeneratorUiState, actions: GeneratorActions) {
    val descriptor = state.descriptor ?: return
    val form = state.form
    val inputs = descriptor.inputs
    Panel(Modifier.fillMaxWidth().testTag("generator_inputs")) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            if (GeneratorInput.Mode in inputs && descriptor.modes.size > 1) {
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(stringResource(R.string.generator_mode), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        for (mode in listOf(ExerciseMode.Walk, ExerciseMode.Run).filter { it in descriptor.modes }) {
                            ChoiceChip(mode.label(), form.mode == mode, "generator_mode_${mode.name}", { actions.onMode(mode) })
                        }
                    }
                }
            }
            val duration = descriptor.durationMinutes
            if (GeneratorInput.DurationMinutes in inputs && duration != null) {
                val minutes = form.durationMinutes ?: duration.first
                ValueStepper(
                    label = stringResource(R.string.generator_duration),
                    value = stringResource(R.string.duration_minutes, minutes),
                    onStep = actions.onDurationStep,
                    tag = "generator_duration",
                    supporting = stringResource(R.string.start_duration_range, duration.first, duration.last),
                    minusEnabled = minutes > duration.first,
                    plusEnabled = minutes < duration.last,
                )
            }
            val series = descriptor.series
            if (GeneratorInput.Series in inputs && series != null) {
                val count = form.series ?: series.first
                ValueStepper(
                    label = stringResource(R.string.generator_series),
                    value = count.toString(),
                    onStep = actions.onSeriesStep,
                    tag = "generator_series",
                    supporting = stringResource(R.string.generator_series_range, series.first, series.last),
                    minusEnabled = count > series.first,
                    plusEnabled = count < series.last,
                )
            }
            if (GeneratorInput.StartWeek in inputs) StartWeekChoice(state, actions)
            if (GeneratorInput.JogSpeedAdjustment in inputs) {
                val percent = form.jogAdjustmentPercent
                ValueStepper(
                    label = stringResource(R.string.generator_jog_adjustment),
                    value = when {
                        percent > 0 -> stringResource(R.string.adjustment_plus, percent)
                        percent < 0 -> stringResource(R.string.adjustment_minus, -percent)
                        else -> stringResource(R.string.adjustment_none)
                    },
                    onStep = actions.onJogStep,
                    tag = "generator_jog",
                    supporting = stringResource(R.string.generator_jog_hint, GeneratorForm.JOG_LIMIT),
                    minusEnabled = percent > -GeneratorForm.JOG_LIMIT,
                    plusEnabled = percent < GeneratorForm.JOG_LIMIT,
                )
            }
            if (GeneratorInput.WalkingSpeed in inputs) {
                StepperField(
                    label = stringResource(R.string.generator_walking_speed),
                    value = form.walkingSpeed,
                    onValue = actions.onWalkingSpeed,
                    onStep = actions.onWalkingSpeedStep,
                    unit = stringResource(R.string.unit_speed),
                    tag = "generator_walking",
                    error = if (!form.walkingSpeedValid) stringResource(R.string.err_speed_range, Format.speed(GeneratorForm.WALKING_SPEED_RANGE.start), Format.speed(GeneratorForm.WALKING_SPEED_RANGE.endInclusive)) else null,
                    supporting = stringResource(R.string.generator_walking_hint),
                )
            }
            RowButton(
                stringResource(R.string.start_restore_defaults),
                actions.onRestoreDefaults,
                Modifier.align(Alignment.End).testTag("restore_defaults_button"),
                enabled = !state.atDefaults,
            )
        }
    }
}

/** Couch to 5K: carry on with the plan from the history (the default) or choose the week to start at. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun StartWeekChoice(state: GeneratorUiState, actions: GeneratorActions) {
    val choosing = state.form.startWeek != null
    val suggested = state.suggestedStartWeek
    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(stringResource(R.string.generator_plan), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            ChoiceChip(stringResource(R.string.generator_plan_continue), !choosing, "generator_plan_continue", { actions.onChooseStartWeek(false) })
            ChoiceChip(stringResource(R.string.generator_plan_choose), choosing, "generator_plan_choose", { actions.onChooseStartWeek(true) })
        }
        if (choosing) {
            Text(stringResource(R.string.generator_start_week), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                for (week in 1..9) {
                    ChoiceChip(
                        if (week == suggested) stringResource(R.string.generator_week_suggested, week) else week.toString(),
                        state.form.startWeek == week, "generator_week_$week", { actions.onStartWeek(week) },
                    )
                }
            }
            if (suggested != null) {
                Text(stringResource(R.string.generator_week_hint, suggested), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        } else {
            Text(stringResource(R.string.generator_plan_continue_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/** The session the inputs give: chart, totals, the notices about it, and its blocks. */
@Composable
private fun Preview(state: GeneratorUiState, actions: GeneratorActions) {
    val generated = state.generated
    val plan = state.plan
    Panel(Modifier.fillMaxWidth().testTag("generator_preview")) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SectionLabel(stringResource(R.string.start_preview))
            val problem = state.problem
            if (problem != null) ErrorText(problem.text(), Modifier.testTag("generator_problem"))
            if (state.user == null) {
                Text(stringResource(R.string.no_user_chosen), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            if (generated != null && plan != null) {
                generated.couchToFiveK?.let { CouchSessionHeader(it) }
                val segments = remember(plan) { plan.segments() }
                PlanChart(
                    segments = segments,
                    description = stringResource(R.string.start_preview_description),
                    modifier = Modifier.fillMaxWidth().height(200.dp).testTag("generator_chart"),
                    emptyText = stringResource(R.string.start_preview_invalid),
                )
                PlanLegend(
                    stringResource(R.string.chart_speed), stringResource(R.string.chart_incline),
                    heartRateLabel = if (segments.any { it.heartRate != null }) stringResource(R.string.chart_heart_rate_blocks) else null,
                kept = segments.any { it.keepsSpeed || it.keepsIncline },
                )
                PlanTotals(plan)
                GeneratedZoneOrigin(generated.program.origin?.inputs.orEmpty())
                Notices(generated, plan, actions)
                if (generated.descriptor.heartRateGuided && !state.heartRateAvailable) {
                    Text(
                        stringResource(R.string.generator_no_heart_rate),
                        Modifier.testTag("generator_no_heart_rate"),
                        style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.extra.warning,
                    )
                }
                SectionLabel(stringResource(R.string.generator_blocks))
                PlanBlockList(
                    plan, state.heartRateProfile,
                    fromHistory = generated.notices.filter { it.code == GeneratorNoticeCode.StartedFromHistory }.mapNotNull { it.blockKey }.toSet(),
                )
            }
        }
    }
}

@Composable
private fun CouchSessionHeader(selection: com.treadz.console.programs.personalised.CouchToFiveKSelection) {
    Column(Modifier.testTag("couch_session_header").semantics(mergeDescendants = true) {}, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            stringResource(R.string.couch_session_title, selection.session, CouchToFiveK.SESSIONS),
            style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold,
        )
        Text(stringResource(R.string.couch_week_day, selection.week, selection.day), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(selection.decision.text(), Modifier.testTag("couch_decision"), style = MaterialTheme.typography.bodyMedium)
    }
}

/** Everything the generator says about the session, each one translated, with the way to fix what can be fixed. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun Notices(generated: GeneratedProgram, plan: com.treadz.console.programs.plan.ResolvedPlan, actions: GeneratorActions) {
    if (generated.notices.isEmpty()) return
    Column(Modifier.fillMaxWidth().testTag("generator_notices"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        generated.notices.forEach { notice ->
            val warning = notice.code != GeneratorNoticeCode.StartedFromHistory && notice.code != GeneratorNoticeCode.PlanCompleted
            Column(Modifier.fillMaxWidth().testTag("notice_${notice.code.name}"), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    "• " + notice.text(plan, generated.couchToFiveK),
                    style = MaterialTheme.typography.bodyMedium,
                    color = if (warning) MaterialTheme.extra.warning else MaterialTheme.colorScheme.onSurface,
                )
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    noticeActions(notice, actions)
                }
            }
        }
    }
}

@Composable
private fun noticeActions(notice: GeneratorNotice, actions: GeneratorActions) {
    when (notice.code) {
        GeneratorNoticeCode.EstimatedValues ->
            RowButton(stringResource(R.string.notice_action_tests), actions.onOpenFitnessTests, Modifier.testTag("notice_action_tests"))
        GeneratorNoticeCode.BodyDataAssumed -> {
            RowButton(stringResource(R.string.notice_action_weight), actions.onOpenWeight, Modifier.testTag("notice_action_weight"))
            RowButton(stringResource(R.string.notice_action_profile), actions.onOpenProfile, Modifier.testTag("notice_action_profile"))
        }
        GeneratorNoticeCode.RestingHeartRateMissing ->
            RowButton(stringResource(R.string.notice_action_resting), actions.onOpenResting, Modifier.testTag("notice_action_resting"))
        else -> Unit
    }
}

@Composable
private fun StartBar(state: GeneratorUiState, actions: GeneratorActions) {
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
                Modifier.fillMaxWidth().testTag("start_message").semantics { liveRegion = LiveRegionMode.Assertive },
                style = MaterialTheme.typography.titleSmall,
                fontWeight = if (failure != null) FontWeight.Bold else FontWeight.Normal,
                color = if (failure != null) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
            val session = state.generated?.couchToFiveK?.session
            BigButton(
                text = if (session != null) stringResource(R.string.couch_start_session, session) else stringResource(R.string.generator_start_button),
                onClick = actions.onStart,
                enabled = state.canStart,
                modifier = Modifier.weight(1f).height(72.dp).testTag("start_generated_button"),
            )
            if (state.starting) CircularProgressIndicator(Modifier.padding(start = 4.dp).height(32.dp), strokeWidth = 4.dp)
        }
    }
}
