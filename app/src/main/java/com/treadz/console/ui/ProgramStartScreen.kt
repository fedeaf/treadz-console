package com.treadz.console.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
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
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.treadz.console.R
import com.treadz.console.programs.plan.IntensityParameter
import com.treadz.console.programs.plan.ResolvedPlan
import com.treadz.console.training.ProgramStartUiState
import com.treadz.console.training.ProgramStartViewModel
import com.treadz.console.training.segments

data class ProgramStartActions(
    val onBack: () -> Unit = {},
    val onDurationStep: (Int) -> Unit = {},
    val onEasySpeed: (String) -> Unit = {},
    val onEasySpeedStep: (Int) -> Unit = {},
    val onMaxSpeed: (String) -> Unit = {},
    val onMaxSpeedStep: (Int) -> Unit = {},
    val onMaxIncline: (String) -> Unit = {},
    val onMaxInclineStep: (Int) -> Unit = {},
    val onAdjustmentStep: (Int) -> Unit = {},
    val onRestoreDefaults: () -> Unit = {},
    val onStart: () -> Unit = {},
    val onSaveAsFree: (String) -> Unit = {},
    /** Opens the generator with the inputs of this personalised program, to generate it again with today's data. */
    val onRegenerate: () -> Unit = {},
)

/** Start screen of a program: what to choose, the plan it gives, and START. */
@Composable
fun ProgramStartScreen(state: ProgramStartUiState, actions: ProgramStartActions, modifier: Modifier = Modifier) {
    val layout = LocalLayout.current
    val name = programDisplayName(state.programId, state.name)
    Column(modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ScreenHeader(title = name, onBack = actions.onBack)
        when {
            state.notFound -> Text(
                stringResource(R.string.program_not_found),
                Modifier.testTag("program_not_found"),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            state.loading -> Unit
            layout.wide -> {
                Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Column(Modifier.weight(0.9f).fillMaxSize().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        Intro(state, actions)
                        StartForm(state, actions)
                    }
                    if (state.origin != null || state.plan?.blocks?.any { it.heartRate != null } == true) {
                        // With the list of blocks the preview is taller than the screen: it scrolls.
                        Column(Modifier.weight(1.1f).fillMaxSize().verticalScroll(rememberScrollState())) {
                            PlanPreview(state, actions.onSaveAsFree, Modifier.fillMaxWidth(), fill = false)
                        }
                    } else {
                        PlanPreview(state, actions.onSaveAsFree, Modifier.weight(1.1f).fillMaxSize(), fill = true)
                    }
                }
                StartBar(state, actions)
            }
            else -> {
                Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Intro(state, actions)
                    StartForm(state, actions)
                    PlanPreview(state, actions.onSaveAsFree, Modifier.fillMaxWidth(), fill = false)
                }
                StartBar(state, actions)
            }
        }
    }
}

@Composable
private fun Intro(state: ProgramStartUiState, actions: ProgramStartActions) {
    val description = state.standardKey?.let { stringResource(standardDescriptionRes(it)) } ?: state.description
    if (!description.isNullOrBlank()) {
        Text(description, style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    val origin = state.origin
    if (origin != null) {
        // A personalised program is repeated exactly as it was generated: its values, with no adjustments.
        val inputs = origin.inputs
        val mode = inputs["mode"]?.let { m -> com.treadz.console.programs.personalised.ExerciseMode.entries.firstOrNull { it.name.equals(m, ignoreCase = true) } }
        val details = listOfNotNull(
            mode?.label(),
            inputs["durationMinutes"]?.toIntOrNull()?.let { stringResource(R.string.duration_minutes, it) },
            if (origin.generatorId == "intervals-30-30") inputs["series"]?.toIntOrNull()?.let { stringResource(R.string.generator_series_count, it) } else null,
        )
        Panel(Modifier.fillMaxWidth().testTag("generated_info"), borderColor = MaterialTheme.colorScheme.secondary) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                state.generatedAtMillis?.let {
                    Text(
                        stringResource(R.string.generated_on, Format.dateTime(it, currentLocale())),
                        Modifier.testTag("generated_on"),
                        style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold,
                    )
                }
                if (details.isNotEmpty()) Text(details.joinToString(" · "), Modifier.testTag("generated_inputs"), style = MaterialTheme.typography.bodyLarge)
                GeneratedZoneOrigin(inputs)
                Text(stringResource(R.string.generated_repeat_note), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                RowButton(stringResource(R.string.saved_regenerate), actions.onRegenerate, Modifier.testTag("regenerate_button"))
            }
        }
    }
}

@Composable
private fun StartForm(state: ProgramStartUiState, actions: ProgramStartActions) {
    // A personalised program runs as it was generated: nothing to choose.
    if (state.kind == com.treadz.console.programs.model.ProgramKind.Personalised) return
    val form = state.form
    val wide = LocalLayout.current.wide
    val fields = mutableListOf<@Composable (Modifier) -> Unit>()
    state.durationOptions?.let { options ->
        fields += { m ->
            val seconds = form.durationSeconds ?: options.minSeconds
            ValueStepper(
                label = stringResource(R.string.start_duration),
                value = stringResource(R.string.duration_minutes, seconds / 60),
                onStep = actions.onDurationStep,
                tag = "duration",
                modifier = m,
                supporting = stringResource(R.string.start_duration_range, options.minSeconds / 60, options.maxSeconds / 60),
                minusEnabled = seconds > options.minSeconds,
                plusEnabled = seconds < options.maxSeconds,
            )
        }
    }
    fields += { m ->
        ValueStepper(
            label = stringResource(R.string.start_adjustment),
            value = adjustmentText(form.adjustmentPercent),
            onStep = actions.onAdjustmentStep,
            tag = "adjustment",
            modifier = m,
            supporting = stringResource(R.string.start_adjustment_hint),
            minusEnabled = form.adjustmentPercent > ProgramStartViewModel.MIN_ADJUSTMENT_PERCENT,
            plusEnabled = form.adjustmentPercent < ProgramStartViewModel.MAX_ADJUSTMENT_PERCENT,
        )
    }
    fun problem(p: IntensityParameter) = state.problems[p]
    if (IntensityParameter.EasySpeed in state.uses) {
        fields += { m ->
            StepperField(
                label = stringResource(R.string.start_easy_speed), value = form.easySpeed, onValue = actions.onEasySpeed, onStep = actions.onEasySpeedStep,
                unit = stringResource(R.string.unit_speed), tag = "easy_speed", modifier = m,
                error = problem(IntensityParameter.EasySpeed)?.text(IntensityParameter.EasySpeed, state.speedMin, state.speedMax, state.inclineMax),
                supporting = stringResource(R.string.start_easy_speed_hint),
            )
        }
    }
    if (IntensityParameter.MaxSpeed in state.uses) {
        fields += { m ->
            StepperField(
                label = stringResource(R.string.start_max_speed), value = form.maxSpeed, onValue = actions.onMaxSpeed, onStep = actions.onMaxSpeedStep,
                unit = stringResource(R.string.unit_speed), tag = "max_speed", modifier = m,
                error = problem(IntensityParameter.MaxSpeed)?.text(IntensityParameter.MaxSpeed, state.speedMin, state.speedMax, state.inclineMax),
                supporting = stringResource(R.string.start_max_speed_hint),
            )
        }
    }
    if (IntensityParameter.MaxIncline in state.uses) {
        fields += { m ->
            StepperField(
                label = stringResource(R.string.start_max_incline), value = form.maxIncline, onValue = actions.onMaxIncline, onStep = actions.onMaxInclineStep,
                unit = stringResource(R.string.unit_incline), tag = "max_incline", keyboardType = KeyboardType.Number, modifier = m,
                error = problem(IntensityParameter.MaxIncline)?.text(IntensityParameter.MaxIncline, state.speedMin, state.speedMax, state.inclineMax),
                supporting = stringResource(R.string.start_max_incline_hint),
            )
        }
    }
    Panel(Modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
            if (wide) {
                // Two columns: the whole form fits on the tablet's screen.
                fields.chunked(2).forEach { pair ->
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        pair.forEach { field -> field(Modifier.weight(1f)) }
                        if (pair.size == 1) Spacer(Modifier.weight(1f))
                    }
                }
            } else {
                fields.forEach { field -> field(Modifier) }
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

@Composable
private fun adjustmentText(percent: Int): String = when {
    percent > 0 -> stringResource(R.string.adjustment_plus, percent)
    percent < 0 -> stringResource(R.string.adjustment_minus, -percent)
    else -> stringResource(R.string.adjustment_none)
}

/** The plan the current values give: chart, totals and warnings. */
@Composable
private fun PlanPreview(state: ProgramStartUiState, onSaveAsFree: (String) -> Unit, modifier: Modifier, fill: Boolean) {
    val plan = state.plan
    Panel(modifier.testTag("plan_preview")) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SectionLabel(stringResource(R.string.start_preview))
            val segments = remember(plan) { plan?.segments().orEmpty() }
            PlanChart(
                segments = segments,
                description = stringResource(R.string.start_preview_description),
                modifier = (if (fill) Modifier.weight(1f) else Modifier.height(200.dp)).fillMaxWidth().testTag("plan_chart"),
                emptyText = stringResource(R.string.start_preview_invalid),
            )
            PlanLegend(
                stringResource(R.string.chart_speed), stringResource(R.string.chart_incline),
                heartRateLabel = if (segments.any { it.heartRate != null }) stringResource(R.string.chart_heart_rate_blocks) else null,
            )
            if (plan != null) {
                PlanTotals(plan)
                PlanWarnings(plan.warnings)
                // The blocks with their zones in bpm, where heart rate is involved.
                if (state.origin != null || segments.any { it.heartRate != null }) PlanBlockList(plan, state.heartRateProfile)
                val copyName = stringResource(R.string.program_copy_name, programDisplayName(state.programId, state.name))
                RowButton(stringResource(R.string.start_save_as_free), { onSaveAsFree(copyName) }, Modifier.testTag("save_as_free_button"), enabled = !state.savingCopy)
                if (state.saveCopyFailed) ErrorText(stringResource(R.string.error_storage))
            }
        }
    }
}

@Composable
internal fun PlanTotals(plan: ResolvedPlan) {
    Row(Modifier.fillMaxWidth().testTag("plan_totals"), horizontalArrangement = Arrangement.spacedBy(20.dp)) {
        Total(stringResource(R.string.stat_duration), Format.duration(plan.estimatedDurationSeconds))
        Total(stringResource(R.string.stat_distance), stringResource(R.string.value_km, Format.distanceKm(plan.estimatedDistanceMeters)))
        Total(stringResource(R.string.start_blocks), plan.blocks.size.toString())
    }
}

@Composable
private fun Total(label: String, value: String) {
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
    }
}

@Composable
private fun StartBar(state: ProgramStartUiState, actions: ProgramStartActions) {
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
            BigButton(
                text = stringResource(R.string.start_program_button),
                onClick = actions.onStart,
                enabled = state.canStart,
                modifier = Modifier.weight(1f).height(72.dp).testTag("start_program_button"),
            )
            if (state.starting) CircularProgressIndicator(Modifier.padding(start = 4.dp).height(32.dp), strokeWidth = 4.dp)
        }
    }
}
