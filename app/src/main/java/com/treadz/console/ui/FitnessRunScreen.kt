package com.treadz.console.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.shape.RoundedCornerShape
import com.treadz.console.MainUiState
import com.treadz.console.R
import com.treadz.console.programs.fitness.FitnessTestConfig
import com.treadz.console.programs.fitness.FitnessTestPhase
import com.treadz.console.programs.fitness.FitnessTestProgress
import com.treadz.console.programs.fitness.FitnessTestType
import com.treadz.console.training.RunUiState
import kotlin.math.abs

/**
 * The guide while a fitness test runs: what to do now, the time left, speed, incline and heart rate, and the buttons
 * that apply ("Exhausted", "End test") besides STOP. When it has ended, the result.
 */
@Composable
fun FitnessRunScreen(ui: MainUiState, run: RunUiState, controls: ControlActions, actions: RunActions, modifier: Modifier = Modifier) {
    val test = run.test
    val layout = LocalLayout.current
    val wide = layout.wide
    val base = liveSizes(wide)
    val sizes = if (wide) base.copy(stepButton = 84.dp, stepRowHeight = 88.dp, stepValue = 96.sp, presetHeight = 52.dp, stopHeight = 112.dp, stopText = 72.sp) else base
    val type = test.testType ?: FitnessTestType.EbbelingWalk

    Column(modifier.fillMaxSize().testTag("fitness_run"), verticalArrangement = Arrangement.spacedBy(sizes.gap)) {
        if (layout.compact) {
            // A phone has no room for the title and the phase side by side.
            Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(type.label(), Modifier.testTag("run_title"), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, maxLines = 2)
                Tag(phaseLabel(type, test.phase), phaseColor(test.phase), Modifier.testTag("test_phase"))
            }
        } else {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(type.label(), Modifier.weight(1f).testTag("run_title"), style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold, maxLines = 1)
                Tag(phaseLabel(type, test.phase), phaseColor(test.phase), Modifier.testTag("test_phase"))
            }
        }
        if (test.phase == FitnessTestPhase.Finished && test.result != null) {
            Column(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
                    FitnessResultContent(
                        result = test.result!!,
                        comparison = run.comparison,
                        heartRateMax = run.heartRateMax,
                        saveFailed = run.saveFailed,
                        onUseMeasured = actions.onUseMeasuredHeartRateMax,
                        onKeepMine = actions.onKeepManualHeartRateMax,
                    )
                }
                FinishedBar(ui, controls, actions)
            }
        } else if (wide) {
            Row(Modifier.weight(1f), horizontalArrangement = Arrangement.spacedBy(sizes.gap)) {
                Column(Modifier.weight(2.1f).fillMaxSize(), verticalArrangement = Arrangement.spacedBy(sizes.gap)) {
                    InstructionPanel(test, Modifier.weight(1f).fillMaxWidth())
                    Row(Modifier.weight(1.5f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(sizes.gap)) {
                        SpeedFigure(ui, test, controls, sizes, Modifier.weight(1f).fillMaxSize())
                        InclineFigure(test, sizes, Modifier.weight(1f).fillMaxSize())
                        HeartRateFigure(test, run, sizes, Modifier.weight(1f).fillMaxSize())
                    }
                    RampStartPanel(test, actions)
                    TestInfo(test, run)
                }
                Column(Modifier.weight(1f).fillMaxSize(), verticalArrangement = Arrangement.spacedBy(sizes.gap)) {
                    ControlHintText(controlHint(ui), reserveLines = false)
                    TestButtons(test, actions, sizes)
                    StopSection(ui, controls, sizes, Modifier.fillMaxWidth().weight(1f).heightIn(min = sizes.stopHeight))
                }
            }
        } else {
            Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(sizes.gap)) {
                InstructionPanel(test, Modifier.fillMaxWidth())
                // On a phone the ramp start controls come first: they only exist during the warm-up and the figures would push them off screen.
                RampStartPanel(test, actions)
                SpeedFigure(ui, test, controls, sizes, Modifier.fillMaxWidth().heightIn(min = 120.dp))
                Row(Modifier.fillMaxWidth().height(120.dp), horizontalArrangement = Arrangement.spacedBy(sizes.gap)) {
                    InclineFigure(test, sizes, Modifier.weight(1f).fillMaxSize())
                    HeartRateFigure(test, run, sizes, Modifier.weight(1f).fillMaxSize())
                }
                controlHint(ui)?.let { ControlHintText(it, reserveLines = false) }
                TestInfo(test, run)
            }
            TestButtons(test, actions, sizes)
            StopSection(
                ui, controls, sizes,
                Modifier.fillMaxWidth().then(if (ui.stopUnreachable) Modifier.heightIn(min = sizes.stopHeight) else Modifier.height(sizes.stopHeight)),
            )
        }
    }
}

/** The ramp of the walking heart-rate test is not an "increasing speed" phase: it is its own word. */
@Composable
private fun phaseLabel(type: FitnessTestType, phase: FitnessTestPhase): String =
    if (type == FitnessTestType.MaxHeartRateWalk && phase == FitnessTestPhase.Incremental) stringResource(R.string.test_phase_ramp) else phase.label()

@Composable
private fun phaseColor(phase: FitnessTestPhase) = when (phase) {
    FitnessTestPhase.Stage1, FitnessTestPhase.Stage2, FitnessTestPhase.Incremental -> MaterialTheme.extra.ok
    FitnessTestPhase.Finished, FitnessTestPhase.Idle -> MaterialTheme.colorScheme.onSurfaceVariant
    else -> MaterialTheme.extra.warning
}

/** What to do now, in big letters, and how long this part lasts. */
@Composable
@OptIn(ExperimentalLayoutApi::class)
private fun InstructionPanel(test: FitnessTestProgress, modifier: Modifier) {
    Panel(modifier.testTag("instruction_panel")) {
        Column(Modifier.fillMaxSize().padding(horizontal = 18.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(6.dp, Alignment.CenterVertically)) {
            Text(
                test.instruction?.text().orEmpty(),
                Modifier.fillMaxWidth().testTag("instruction_text").semantics { liveRegion = LiveRegionMode.Polite },
                style = if (LocalLayout.current.compact) MaterialTheme.typography.titleMedium else MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
            )
            androidx.compose.foundation.layout.FlowRow(
                Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(24.dp), verticalArrangement = Arrangement.spacedBy(4.dp),
                itemVerticalAlignment = Alignment.Bottom,
            ) {
                val remaining = test.phaseRemainingSeconds
                if (remaining != null) {
                    Column {
                        Text(stringResource(R.string.test_time_left), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(Format.duration(remaining), Modifier.testTag("test_remaining"), style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
                    }
                }
                Column {
                    Text(stringResource(R.string.run_elapsed), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Text(Format.duration(test.elapsedSeconds), Modifier.testTag("test_elapsed"), style = if (remaining != null) MaterialTheme.typography.titleLarge else MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
                }
                if (test.testType == FitnessTestType.MaxHeartRateWalk && test.phase == FitnessTestPhase.Incremental) {
                    Column {
                        Text(stringResource(R.string.test_ramp_time), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(Format.duration(test.phaseElapsedSeconds), Modifier.testTag("test_ramp_time"), style = MaterialTheme.typography.headlineLarge, fontWeight = FontWeight.Bold)
                    }
                }
                test.stageNumber?.let { stage ->
                    Column {
                        Text(stringResource(R.string.test_stage), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(stage.toString(), Modifier.testTag("test_stage"), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
                    }
                }
            }
            val total = test.phaseRemainingSeconds?.let { it + test.phaseElapsedSeconds }
            if (total != null && total > 0) {
                LinearProgressIndicator(
                    progress = { (test.phaseElapsedSeconds.toFloat() / total).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth().height(6.dp),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.outlineVariant,
                )
            }
        }
    }
}

/** The speed: with − / + while the Ebbeling speed may still change, otherwise a figure with the target. */
@Composable
private fun SpeedFigure(ui: MainUiState, test: FitnessTestProgress, controls: ControlActions, sizes: LiveSizes, modifier: Modifier) {
    val adjustable = ui.session.speed && test.testType == FitnessTestType.EbbelingWalk && test.phase == FitnessTestPhase.Stage1 && !test.speedLocked
    val target = test.targetSpeedKmh?.takeIf { abs(it - test.actualSpeedKmh) >= 0.05 }
    if (adjustable) {
        AdjustPanel(
            label = stringResource(R.string.metric_speed),
            unit = stringResource(R.string.unit_speed),
            value = Format.speed(test.actualSpeedKmh),
            target = (ui.targetSpeed ?: target)?.takeIf { abs(it - test.actualSpeedKmh) >= 0.05 }?.let { stringResource(R.string.control_target, Format.speed(it)) },
            busy = ui.controls.speedInFlight,
            enabled = ui.canAdjustSpeed,
            dimmed = ui.treadmill.isStale,
            lessDescription = stringResource(R.string.control_speed_less),
            moreDescription = stringResource(R.string.control_speed_more),
            onLessPress = { controls.onSpeedPress(-1) },
            onMorePress = { controls.onSpeedPress(+1) },
            onRelease = controls.onSpeedRelease,
            onLessStep = { controls.onSpeedStep(-1) },
            onMoreStep = { controls.onSpeedStep(+1) },
            sizes = sizes,
            tag = "speed",
            modifier = modifier,
        )
    } else {
        MetricCard(
            label = stringResource(R.string.metric_speed),
            value = Format.speed(test.actualSpeedKmh),
            unit = stringResource(R.string.unit_speed),
            modifier = modifier.testTag("speed_figure"),
            note = test.targetSpeedKmh?.let { stringResource(R.string.test_target, Format.speed(it)) },
            maxValueSize = sizes.maxValue, unitSize = sizes.unit, unitInline = sizes.unitInline,
        )
    }
}

@Composable
private fun InclineFigure(test: FitnessTestProgress, sizes: LiveSizes, modifier: Modifier) {
    MetricCard(
        label = stringResource(R.string.metric_incline),
        value = Format.incline(test.actualInclinePercent),
        unit = stringResource(R.string.unit_incline),
        modifier = modifier.testTag("incline_figure"),
        note = test.targetInclinePercent?.let { stringResource(R.string.test_target, Format.incline(it)) },
        maxValueSize = sizes.maxValue, unitSize = sizes.unit, unitInline = sizes.unitInline,
    )
}

@Composable
private fun HeartRateFigure(test: FitnessTestProgress, run: RunUiState, sizes: LiveSizes, modifier: Modifier) {
    val note = when {
        test.heartRateBpm == null -> stringResource(R.string.heart_rate_none)
        test.heartRateBandLowBpm != null && test.heartRateBandHighBpm != null && test.heartRateLimitBpm != null ->
            stringResource(R.string.test_hr_band, test.heartRateBandLowBpm!!, test.heartRateBandHighBpm!!, test.heartRateLimitBpm!!)
        test.heartRateLimitBpm != null -> stringResource(R.string.test_hr_limit, test.heartRateLimitBpm!!)
        else -> null
    }
    MetricCard(
        label = stringResource(R.string.metric_heart_rate),
        value = Format.heartRate(test.heartRateBpm),
        unit = if (test.heartRateBpm != null) stringResource(R.string.unit_heart_rate) else null,
        modifier = modifier.testTag("heart_rate_figure"),
        note = note,
        maxValueSize = sizes.maxValue, unitSize = sizes.unit, unitInline = sizes.unitInline,
        labelTrailing = run.heartRateSource?.takeIf { test.heartRateBpm != null }?.let { source -> { HeartRateSourceIcon(source, size = 18.dp) } },
    )
}

/** Steady state (Ebbeling), next speed step and the speed cap (maximal test), and the lock on the speed. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun TestInfo(test: FitnessTestProgress, run: RunUiState) {
    val rows = mutableListOf<Pair<String, String>>()
    @Composable fun add(label: Int, value: String) { rows += stringResource(label) to value }
    if (test.testType == FitnessTestType.EbbelingWalk) {
        if (test.phase == FitnessTestPhase.Stage1) {
            add(R.string.test_speed_state, stringResource(if (test.speedLocked) R.string.test_speed_fixed else R.string.test_speed_adjustable))
        }
        test.steadyState?.let { add(R.string.test_steady_state, it.label()) }
        test.steadyStateVariationBpm?.let { add(R.string.test_steady_variation, stringResource(R.string.test_variation_value, Format.speed(it))) }
        if (test.stage2Extensions > 0) add(R.string.test_extensions, test.stage2Extensions.toString())
    } else if (test.testType == FitnessTestType.MaxHeartRateWalk) {
        // The ramp raises the incline every minute (the speed once the incline is at its top).
        if (test.phase == FitnessTestPhase.Incremental) {
            test.stageRemainingSeconds?.let { add(R.string.test_next_step_in, Format.duration(it)) }
            val speed = test.nextStageSpeedKmh
            val incline = test.nextStageInclinePercent
            if (speed != null) {
                add(R.string.test_walk_next_stage, stringResource(R.string.block_target, Format.speed(speed), Format.incline(incline ?: test.actualInclinePercent)))
            }
        }
    } else {
        test.stageRemainingSeconds?.let { add(R.string.test_next_step_in, Format.duration(it)) }
        test.nextStageSpeedKmh?.let { add(R.string.test_next_speed, stringResource(R.string.value_kmh, Format.speed(it))) }
        test.masIfExhaustedNowKmh?.let { add(R.string.test_mas_now, stringResource(R.string.value_kmh, Format.speed(it))) }
    }
    if (rows.isEmpty() && !test.capReached) return
    Panel(Modifier.fillMaxWidth().testTag("test_info")) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
            rows.forEach { (label, value) -> FactRow(label, value) }
            if (test.capReached) {
                Tag(stringResource(R.string.test_cap_reached), MaterialTheme.extra.warning, Modifier.testTag("cap_reached_tag"))
            }
        }
    }
}

/** "I'm exhausted" (maximal test) and "End test" (invalidates it), when they apply. */
@Composable
private fun TestButtons(test: FitnessTestProgress, actions: RunActions, sizes: LiveSizes) {
    val walk = test.testType == FitnessTestType.MaxHeartRateWalk
    // The walking test counts exhaustion only after 3 minutes of ramp; earlier it would make the test invalid.
    val minRamp = FitnessTestConfig().minIncrementalSeconds
    val exhaustedReady = !walk || test.phaseElapsedSeconds >= minRamp
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (test.canReportExhaustion) {
            Button(
                onClick = actions.onExhausted,
                enabled = exhaustedReady,
                modifier = Modifier.fillMaxWidth().height(sizes.pauseHeight).testTag("exhausted_button"),
                shape = RoundedCornerShape(24.dp),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.extra.warning, contentColor = MaterialTheme.extra.onWarning),
            ) {
                Text(stringResource(R.string.test_exhausted), fontSize = sizes.pauseText, fontWeight = FontWeight.ExtraBold, maxLines = 1)
            }
            if (walk && !exhaustedReady) {
                Text(stringResource(R.string.test_exhausted_after_ramp, minRamp / 60), Modifier.testTag("exhausted_wait_hint"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            } else if (!LocalLayout.current.compact) {
                Text(stringResource(R.string.test_exhausted_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
        if (walk && test.phase == FitnessTestPhase.Incremental) {
            Text(stringResource(R.string.test_walk_manual_note), Modifier.testTag("walk_manual_note"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.extra.warning)
        }
        if (test.canEndTest) {
            BigOutlinedButton(stringResource(R.string.test_end), actions.onEndTest, Modifier.fillMaxWidth().testTag("end_test_button"))
            if (!LocalLayout.current.compact) {
                Text(stringResource(R.string.test_end_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

/** Width from which the two ramp-start steppers fit side by side. */
private val RAMP_STEPPERS_ROW_MIN_WIDTH = 560.dp

/**
 * Walking heart-rate test, warm-up only: the speed and the incline the ramp will start at, with − / +. The changes go to
 * the test runner (through the coordinator); the warm-up steps follow the ramp speed.
 */
@Composable
private fun RampStartPanel(test: FitnessTestProgress, actions: RunActions) {
    if (test.testType != FitnessTestType.MaxHeartRateWalk || test.phase != FitnessTestPhase.WarmUp) return
    val speed = test.nextStageSpeedKmh ?: return
    val incline = test.nextStageInclinePercent ?: return
    Panel(Modifier.fillMaxWidth().testTag("ramp_start_panel")) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.test_ramp_start_title), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
            // Side by side only when each stepper keeps room for its value ("5.5 km/h") between its buttons.
            BoxWithConstraints(Modifier.fillMaxWidth()) {
                val speedStepper = @Composable { modifier: Modifier ->
                    ValueStepper(
                        label = stringResource(R.string.test_ramp_speed), value = stringResource(R.string.value_kmh, Format.speed(speed)),
                        onStep = actions.onRampSpeedStep, tag = "ramp_speed", modifier = modifier,
                    )
                }
                val inclineStepper = @Composable { modifier: Modifier ->
                    ValueStepper(
                        label = stringResource(R.string.test_ramp_incline), value = stringResource(R.string.value_percent, Format.incline(incline)),
                        onStep = actions.onRampInclineStep, tag = "ramp_incline", modifier = modifier,
                    )
                }
                if (maxWidth >= RAMP_STEPPERS_ROW_MIN_WIDTH) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                        speedStepper(Modifier.weight(1f))
                        inclineStepper(Modifier.weight(1f))
                    }
                } else {
                    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        speedStepper(Modifier)
                        inclineStepper(Modifier)
                    }
                }
            }
        }
    }
}
