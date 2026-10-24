package com.treadz.console.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
    val sizes = liveSizes(wide)
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
            LiveWideLayout(
                sizes, Modifier.weight(1f),
                main = {
                    StrapWarning(test)
                    InstructionPanel(test, Modifier.weight(1f).fillMaxWidth())
                    // Incline, speed and heart rate: the same order and the same fixed size as the other live screens.
                    Row(Modifier.fillMaxWidth().height(sizes.adjustPanelHeight), horizontalArrangement = Arrangement.spacedBy(sizes.gap)) {
                        InclineFigure(ui, test, controls, sizes, Modifier.weight(1f))
                        SpeedFigure(ui, test, controls, sizes, Modifier.weight(1f))
                        HeartRateFigure(test, run, sizes, Modifier.weight(1f))
                    }
                    RampStartPanel(test, actions)
                    TestInfo(test, run)
                },
                sideTop = {
                    ControlHintText(controlHint(ui), reserveLines = false)
                    TestNotes(test)
                    if (mainButton(test) == TestMainButton.EndTest) {
                        Text(stringResource(R.string.test_end_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
                sideBottom = {
                    // "+1" where a program has it: the warm-up and the cool-down (never while the test measures).
                    BlockButtonsRow(sizes, extend = { m -> TestExtendButton(test, actions, m) })
                    // The test's main button takes the place of Pause.
                    TestMainButton(test, actions, sizes, Modifier.fillMaxWidth())
                    LiveStop(ui, controls, sizes)
                },
            )
        } else {
            LiveCompactLayout(
                sizes, Modifier.weight(1f).fillMaxWidth(),
                content = {
                    StrapWarning(test)
                    InstructionPanel(test, Modifier.fillMaxWidth())
                    // On a phone the ramp start controls come first: they only exist during the warm-up and the figures would push them off screen.
                    RampStartPanel(test, actions)
                    // Incline above speed, as in the other live screens.
                    if (test.manualChangesAllowed) {
                        // The incline with − / + needs the whole width, like the speed.
                        InclineFigure(ui, test, controls, sizes, Modifier.fillMaxWidth())
                        SpeedFigure(ui, test, controls, sizes, Modifier.fillMaxWidth())
                        HeartRateFigure(test, run, sizes, Modifier.fillMaxWidth())
                    } else {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(sizes.gap)) {
                            InclineFigure(ui, test, controls, sizes, Modifier.weight(1f))
                            HeartRateFigure(test, run, sizes, Modifier.weight(1f))
                        }
                        SpeedFigure(ui, test, controls, sizes, Modifier.fillMaxWidth())
                    }
                    controlHint(ui)?.let { ControlHintText(it, reserveLines = false) }
                    TestInfo(test, run)
                    TestNotes(test)
                },
                bottom = {
                    // "+1" next to the test's main button; alone (cool-down), centred.
                    Row(Modifier.fillMaxWidth().height(sizes.pauseHeight), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.CenterHorizontally)) {
                        TestExtendButton(test, actions, Modifier.width(sizes.extendWidth).fillMaxHeight())
                        if (mainButton(test) != null) TestMainButton(test, actions, sizes, Modifier.weight(1f))
                    }
                    LiveStop(ui, controls, sizes)
                },
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

/**
 * "Wet the strap or adjust it": while the warm-up of a maximal test sees too many wrong beats in the heart rate strap
 * ([com.treadz.console.programs.fitness.StrapCheck.poor]). It goes by itself when the beats get better and it never blocks
 * the test.
 */
@Composable
private fun StrapWarning(test: FitnessTestProgress) {
    val check = test.strapCheck?.takeIf { it.poor } ?: return
    Panel(Modifier.fillMaxWidth().testTag("strap_warning").semantics { liveRegion = LiveRegionMode.Polite }, borderColor = MaterialTheme.extra.warning) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            Text(stringResource(R.string.test_strap_poor), Modifier.testTag("strap_warning_text"), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.extra.warning)
            Text(
                stringResource(R.string.test_strap_poor_detail, Format.number("%.0f", check.artifactPercent)),
                Modifier.testTag("strap_warning_detail"),
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
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

/**
 * The speed: with − / + while the Ebbeling speed may still change and during the cool-down (manual changes allowed),
 * otherwise a figure with the target. Both at the fixed size of the live figures.
 */
@Composable
private fun SpeedFigure(ui: MainUiState, test: FitnessTestProgress, controls: ControlActions, sizes: LiveSizes, modifier: Modifier) {
    val ebbelingSpeed = test.testType == FitnessTestType.EbbelingWalk && test.phase == FitnessTestPhase.Stage1 && !test.speedLocked
    val adjustable = ui.session.speed && (ebbelingSpeed || test.manualChangesAllowed)
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
            message = ui.controls.failure?.takeIf { it.action == com.treadz.console.controls.ControlAction.Speed }?.let { failureMessage(it) },
        )
    } else {
        LiveFigurePanel(
            label = stringResource(R.string.metric_speed),
            unit = stringResource(R.string.unit_speed),
            value = Format.speed(test.actualSpeedKmh),
            sizes = sizes,
            tag = "speed_figure",
            modifier = modifier,
            note = test.targetSpeedKmh?.let { stringResource(R.string.test_target, Format.speed(it)) },
            dimmed = ui.treadmill.isStale,
        )
    }
}

/** The incline: with − / + during the cool-down (manual changes allowed), otherwise a figure with the target. */
@Composable
private fun InclineFigure(ui: MainUiState, test: FitnessTestProgress, controls: ControlActions, sizes: LiveSizes, modifier: Modifier) {
    if (ui.session.incline && test.manualChangesAllowed) {
        AdjustPanel(
            label = stringResource(R.string.metric_incline),
            unit = stringResource(R.string.unit_incline),
            value = Format.incline(test.actualInclinePercent),
            target = ui.targetIncline?.takeIf { abs(it - test.actualInclinePercent) >= 0.05 }?.let { stringResource(R.string.control_target, Format.incline(it)) },
            busy = ui.controls.inclineInFlight,
            enabled = ui.canAdjustIncline,
            dimmed = ui.treadmill.isStale,
            lessDescription = stringResource(R.string.control_incline_less),
            moreDescription = stringResource(R.string.control_incline_more),
            onLessPress = { controls.onInclinePress(-1) },
            onMorePress = { controls.onInclinePress(+1) },
            onRelease = controls.onInclineRelease,
            onLessStep = { controls.onInclineStep(-1) },
            onMoreStep = { controls.onInclineStep(+1) },
            sizes = sizes,
            tag = "incline",
            modifier = modifier,
            message = ui.controls.failure?.takeIf { it.action == com.treadz.console.controls.ControlAction.Incline }?.let { failureMessage(it) },
        )
        return
    }
    LiveFigurePanel(
        label = stringResource(R.string.metric_incline),
        unit = stringResource(R.string.unit_incline),
        value = Format.incline(test.actualInclinePercent),
        sizes = sizes,
        tag = "incline_figure",
        modifier = modifier,
        note = test.targetInclinePercent?.let { stringResource(R.string.test_target, Format.incline(it)) },
        dimmed = ui.treadmill.isStale,
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
    LiveFigurePanel(
        label = stringResource(R.string.metric_heart_rate),
        unit = if (test.heartRateBpm != null) stringResource(R.string.unit_heart_rate) else null,
        value = Format.heartRate(test.heartRateBpm),
        sizes = sizes,
        tag = "heart_rate_figure",
        modifier = modifier,
        note = note,
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

/** "+1" in a test: a minute more of the current warm-up step or of the cool-down; disabled while the test measures. */
@Composable
private fun TestExtendButton(test: FitnessTestProgress, actions: RunActions, modifier: Modifier) {
    ExtendBlockButton(byDistance = false, enabled = test.canExtendPhase, onClick = actions.onExtendTestPhase, modifier = modifier)
}

/** The test's single main button, in the place of a program's Pause. */
private enum class TestMainButton { EndTest, Exhausted }

/**
 * One main button at a time, so the wrong one cannot be pressed by mistake: "End test" while the effort cannot end
 * in a valid way yet, then "I'm exhausted" in its place once exhaustion counts (after 3 min of ramp); `null` when
 * neither applies (cool-down).
 */
private fun mainButton(test: FitnessTestProgress): TestMainButton? = when {
    exhaustedReady(test) -> TestMainButton.Exhausted
    test.canEndTest -> TestMainButton.EndTest
    else -> null
}

@Composable
private fun TestMainButton(test: FitnessTestProgress, actions: RunActions, sizes: LiveSizes, modifier: Modifier) {
    when (mainButton(test)) {
        TestMainButton.Exhausted -> ExhaustedButton(actions, sizes, modifier)
        TestMainButton.EndTest -> EndTestButton(actions, modifier.height(sizes.pauseHeight))
        null -> Unit
    }
}

/** "I'm exhausted" (maximal tests): in the place of Pause, with its height. */
@Composable
private fun ExhaustedButton(actions: RunActions, sizes: LiveSizes, modifier: Modifier) {
    Button(
        onClick = actions.onExhausted,
        modifier = modifier.height(sizes.pauseHeight).testTag("exhausted_button"),
        shape = RoundedCornerShape(24.dp),
        colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.extra.warning, contentColor = MaterialTheme.extra.onWarning),
    ) {
        Text(
            stringResource(R.string.test_exhausted), fontWeight = FontWeight.ExtraBold, maxLines = 1, softWrap = false,
            autoSize = androidx.compose.foundation.text.TextAutoSize.StepBased(minFontSize = 14.sp, maxFontSize = sizes.pauseText, stepSize = 1.sp),
        )
    }
}

/** "End test" (it makes the test invalid). */
@Composable
private fun EndTestButton(actions: RunActions, modifier: Modifier) {
    BigOutlinedButton(stringResource(R.string.test_end), actions.onEndTest, modifier.testTag("end_test_button"))
}

/** Exhaustion counts only after 3 minutes of ramp (both maximal tests); earlier it would make the test invalid. */
private fun exhaustedReady(test: FitnessTestProgress): Boolean =
    test.canReportExhaustion && test.phaseElapsedSeconds >= FitnessTestConfig().minIncrementalSeconds

/** What to know about the test's buttons: when "Exhausted" counts, and that the walking ramp is not changed by hand. */
@Composable
private fun TestNotes(test: FitnessTestProgress) {
    val walk = test.testType == FitnessTestType.MaxHeartRateWalk
    if (test.canReportExhaustion) {
        if (!exhaustedReady(test)) {
            Text(stringResource(R.string.test_exhausted_after_ramp, FitnessTestConfig().minIncrementalSeconds / 60), Modifier.testTag("exhausted_wait_hint"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        } else if (!LocalLayout.current.compact) {
            Text(stringResource(R.string.test_exhausted_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    if (walk && test.phase == FitnessTestPhase.Incremental) {
        Text(stringResource(R.string.test_walk_manual_note), Modifier.testTag("walk_manual_note"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.extra.warning)
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
