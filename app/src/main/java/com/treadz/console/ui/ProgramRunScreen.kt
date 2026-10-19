package com.treadz.console.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.treadz.console.MainUiState
import com.treadz.console.R
import com.treadz.console.programs.engine.HeartRateControlState
import com.treadz.console.programs.engine.ProgramProgress
import com.treadz.console.programs.engine.RunState
import com.treadz.console.programs.model.BlockEnd
import com.treadz.console.programs.personalised.CouchToFiveK
import com.treadz.console.programs.plan.ResolvedBlock
import com.treadz.console.programs.plan.ResolvedPlan
import com.treadz.console.training.ContinueFailure
import com.treadz.console.training.RunUiState
import com.treadz.console.training.positionSeconds
import com.treadz.console.training.segments

/** What the screens of a program or fitness test in progress can ask for. */
data class RunActions(
    val onSkipPrevious: () -> Unit = {},
    val onSkipNext: () -> Unit = {},
    val onContinue: () -> Unit = {},
    val onAbandon: () -> Unit = {},
    val onExhausted: () -> Unit = {},
    val onEndTest: () -> Unit = {},
    /** Closes the result of a program / test that has finished. */
    val onDismiss: () -> Unit = {},
    /** Deletes the workout of the program / test that has just ended (it is never uploaded). */
    val onDiscard: () -> Unit = {},
    val onUseMeasuredHeartRateMax: () -> Unit = {},
    val onKeepManualHeartRateMax: () -> Unit = {},
    /** Switches the heart-rate control of the current block on again after a manual change turned it off. */
    val onResumeHeartRateControl: () -> Unit = {},
    /** Opens the stored result of a fitness test. */
    val onOpenTest: (String) -> Unit = {},
    /** Walking heart-rate test, warm-up: one − / + step of the speed the ramp starts at (0.1 km/h) or of its incline (1 %). */
    val onRampSpeedStep: (Int) -> Unit = {},
    val onRampInclineStep: (Int) -> Unit = {},
    /** "+1": a minute more in a time block, 100 m more in a distance block. */
    val onExtendBlock: () -> Unit = {},
)

/**
 * The live screen while a program runs (and its summary when it ends): the current block with its time left, speed and
 * incline controls, the plan with the position, totals, and always STOP.
 */
@Composable
fun ProgramRunScreen(ui: MainUiState, run: RunUiState, controls: ControlActions, actions: RunActions, modifier: Modifier = Modifier) {
    val progress = run.program
    val wide = LocalLayout.current.wide
    val sizes = liveSizes(wide)
    val plan = progress.plan
    // The published plan is the plan as it runs: "+1" makes it longer, and the chart follows.
    val segments = remember(plan) { plan?.segments().orEmpty() }
    val title = plan?.let { planTitle(it) }.orEmpty()

    Column(modifier.fillMaxSize().testTag("program_run"), verticalArrangement = Arrangement.spacedBy(sizes.gap)) {
        RunHeader(title, progress)
        when (progress.state) {
            RunState.Finished -> ProgramFinished(ui, run, progress, segments, controls, actions, Modifier.weight(1f))
            else -> {
                if (progress.state == RunState.Interrupted) InterruptedPanel(progress, run, actions)
                if (wide) ProgramWide(ui, run, controls, actions, sizes, segments, Modifier.weight(1f))
                else ProgramCompact(ui, run, controls, actions, sizes, segments, Modifier.weight(1f))
            }
        }
    }
}

/** The title of the running program: Couch to 5K names its session, any other program its own name. */
@Composable
@ReadOnlyComposable
private fun planTitle(plan: ResolvedPlan): String {
    val origin = plan.source.origin
    val session = origin?.takeIf { it.generatorId == CouchToFiveK.ID }?.inputs?.get("session")?.toIntOrNull()
    return if (session != null) {
        stringResource(R.string.couch_run_title, stringResource(generatorNameRes(CouchToFiveK.ID)), session, CouchToFiveK.SESSIONS)
    } else {
        programDisplayName(plan.source.programId, plan.source.programName)
    }
}

@Composable
private fun RunHeader(title: String, progress: ProgramProgress) {
    val (color, label) = when (progress.state) {
        RunState.Running -> MaterialTheme.extra.ok to stringResource(R.string.run_state_running)
        RunState.Paused -> MaterialTheme.extra.warning to stringResource(R.string.run_state_paused)
        RunState.Interrupted -> MaterialTheme.colorScheme.error to stringResource(R.string.run_state_interrupted)
        RunState.Starting -> MaterialTheme.extra.warning to stringResource(R.string.run_state_starting)
        RunState.Stopping -> MaterialTheme.extra.warning to stringResource(R.string.run_state_stopping)
        RunState.Finished -> MaterialTheme.colorScheme.onSurfaceVariant to stringResource(R.string.run_state_finished)
        RunState.Idle -> MaterialTheme.colorScheme.onSurfaceVariant to ""
    }
    Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(
            title,
            Modifier.weight(1f).testTag("run_title"),
            style = if (LocalLayout.current.compact) MaterialTheme.typography.titleLarge else MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
        )
        if (label.isNotEmpty()) Tag(label, color, Modifier.testTag("run_state").semantics { liveRegion = LiveRegionMode.Polite })
    }
}

// ---------------------------------------------------------------------------------------------------------------
// Layouts

/** What the treadmill really did, as the two chart series drawn over the plan. */
private data class ActualSeries(val speed: List<ChartPoint>, val incline: List<ChartPoint>)

@Composable
private fun rememberActual(run: RunUiState): ActualSeries = remember(run.programSeries) {
    ActualSeries(run.programSeries.points { it.speedKmh }, run.programSeries.points { it.inclinePercent })
}

/** The program can take "+1", previous and next: running or paused. */
private fun ProgramProgress.blockButtonsEnabled() = state == RunState.Running || state == RunState.Paused

@Composable
private fun ProgramWide(
    ui: MainUiState, run: RunUiState, controls: ControlActions, actions: RunActions, sizes: LiveSizes,
    segments: List<com.treadz.console.training.PlanSegment>, modifier: Modifier,
) {
    val progress = run.program
    val actual = rememberActual(run)
    LiveWideLayout(
        sizes, modifier,
        main = {
            SpeedInclinePanels(ui, controls, sizes)
            HeartRateBlockPanel(progress, actions, Modifier.fillMaxWidth())
            BlockPanel(progress, segments, actual, Modifier.weight(1f).fillMaxWidth(), chartFills = true)
        },
        sideTop = {
            TotalsPanel(progress, sizes)
            ControlHintText(controlHint(ui), reserveLines = false)
        },
        sideBottom = {
            if (progress.state != RunState.Interrupted) {
                val enabled = progress.blockButtonsEnabled()
                BlockButtonsRow(
                    sizes,
                    extend = { m -> ExtendBlockButton(progress.currentBlock?.end is BlockEnd.Distance, enabled, actions.onExtendBlock, m) },
                    previous = { m ->
                        val text = stringResource(R.string.block_previous)
                        SkipButton("◀", text, text, enabled, actions.onSkipPrevious, "skip_previous_button", m)
                    },
                    next = { m ->
                        val text = stringResource(R.string.block_skip)
                        SkipButton("▶", text, text, enabled, actions.onSkipNext, "skip_next_button", m)
                    },
                )
                PauseResumeButton(ui, controls, sizes, Modifier.fillMaxWidth())
            }
            LiveStop(ui, controls, sizes)
        },
    )
}

@Composable
private fun ProgramCompact(
    ui: MainUiState, run: RunUiState, controls: ControlActions, actions: RunActions, sizes: LiveSizes,
    segments: List<com.treadz.console.training.PlanSegment>, modifier: Modifier,
) {
    val progress = run.program
    val actual = rememberActual(run)
    val previousText = stringResource(R.string.block_previous)
    val nextText = stringResource(R.string.block_skip)
    LiveCompactLayout(
        sizes, modifier,
        content = {
            BlockPanel(progress, segments, actual, Modifier.fillMaxWidth(), chartFills = false, showChart = false)
            HeartRateBlockPanel(progress, actions, Modifier.fillMaxWidth())
            SpeedInclinePanels(ui, controls, sizes)
            controlHint(ui)?.let { ControlHintText(it, reserveLines = false) }
            TotalsPanel(progress, sizes)
            Panel(Modifier.fillMaxWidth().testTag("block_chart_panel")) {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                    RunChart(progress, segments, actual, Modifier.height(200.dp).fillMaxWidth())
                }
            }
        },
        bottom = {
            if (progress.state != RunState.Interrupted) {
                // ◀, "+1", Pause and ▶: "+1" between previous and next, next to Pause.
                Row(Modifier.fillMaxWidth().height(sizes.skipHeight), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    val enabled = progress.blockButtonsEnabled()
                    SkipButton("◀", null, previousText, enabled, actions.onSkipPrevious, "skip_previous_button", Modifier.width(sizes.skipWidth).fillMaxHeight())
                    ExtendBlockButton(progress.currentBlock?.end is BlockEnd.Distance, enabled, actions.onExtendBlock, Modifier.width(sizes.extendWidth).fillMaxHeight())
                    PauseResumeButton(ui, controls, sizes, Modifier.weight(1f))
                    SkipButton("▶", null, nextText, enabled, actions.onSkipNext, "skip_next_button", Modifier.width(sizes.skipWidth).fillMaxHeight())
                }
            }
            LiveStop(ui, controls, sizes)
        },
    )
}

// ---------------------------------------------------------------------------------------------------------------
// Block, totals, skip

@Composable
private fun blockLengthText(block: ResolvedBlock): String = when (val e = block.end) {
    is BlockEnd.Duration -> Format.duration(e.seconds)
    is BlockEnd.Distance -> stringResource(R.string.value_km, Format.distanceKm(e.meters))
}

/**
 * The current block: phase, name, how much is left, what it asks for, what comes next, and the plan with the position
 * and what was really done. What it asks for is the engine's real target ([ProgramProgress.targetSpeedKmh]): a block
 * that keeps a value, a heart-rate adjustment or a manual change make it differ from the plan's number. The next block
 * says "keep" for a value it keeps.
 */
@Composable
private fun BlockPanel(
    progress: ProgramProgress,
    segments: List<com.treadz.console.training.PlanSegment>,
    actual: ActualSeries,
    modifier: Modifier,
    chartFills: Boolean,
    showChart: Boolean = true,
) {
    val block = progress.currentBlock
    val plan = progress.plan
    Panel(modifier.testTag("block_panel")) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            if (block != null) {
                BlockHeader(progress, block, plan?.blocks?.size ?: 0)
                val total = progress.blockElapsedSeconds + progress.blockRemainingSeconds
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                    Column {
                        Text(stringResource(R.string.block_time_left), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(
                            Format.duration(progress.blockRemainingSeconds),
                            Modifier.testTag("block_remaining"),
                            style = MaterialTheme.typography.headlineLarge,
                            fontWeight = FontWeight.Bold,
                        )
                        progress.blockRemainingMeters?.let {
                            Text(stringResource(R.string.block_distance_left, Format.distanceKm(it)), Modifier.testTag("block_remaining_distance"), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            targetText(progress.targetSpeedKmh ?: block.speedKmh, progress.targetInclinePercent ?: block.inclinePercent),
                            Modifier.testTag("block_target"),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        progress.nextBlock?.let { next ->
                            Text(
                                stringResource(R.string.block_next, next.phase.label(), plannedTargetText(next, approximate = false), blockLengthText(next)),
                                Modifier.testTag("block_next"),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                                maxLines = 2,
                            )
                        } ?: Text(stringResource(R.string.block_last), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                LinearProgressIndicator(
                    progress = { if (total <= 0) 0f else (progress.blockElapsedSeconds.toFloat() / total).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth().height(6.dp).testTag("block_progress"),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.outlineVariant,
                )
            } else {
                Text(stringResource(R.string.run_state_starting), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            }
            if (showChart) RunChart(progress, segments, actual, (if (chartFills) Modifier.weight(1f) else Modifier.height(170.dp)).fillMaxWidth())
        }
    }
}

/** The plan with the position of the program and, as it fills, what the treadmill really did. */
@Composable
private fun RunChart(progress: ProgramProgress, segments: List<com.treadz.console.training.PlanSegment>, actual: ActualSeries, modifier: Modifier) {
    PlannedVersusActualChart(
        segments = segments,
        actualSpeed = actual.speed,
        actualIncline = actual.incline,
        description = stringResource(R.string.run_plan_description),
        modifier = modifier,
        positionSeconds = progress.positionSeconds(segments),
        chartTag = "run_chart",
    )
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BlockHeader(progress: ProgramProgress, block: ResolvedBlock, blockCount: Int) {
    FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp), itemVerticalAlignment = Alignment.CenterVertically) {
        PhaseTag(block.phase, Modifier.testTag("block_phase"))
        Text(
            block.name ?: stringResource(R.string.block_position, block.index + 1, blockCount),
            Modifier.testTag("block_name"),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
        )
        if (block.name != null) {
            Text(stringResource(R.string.block_position, block.index + 1, blockCount), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        block.repetition?.let { Text(stringResource(R.string.block_repetition, it.iteration, it.count), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant) }
        if (progress.manualOverride) {
            Tag(stringResource(R.string.block_manual), MaterialTheme.extra.warning, Modifier.testTag("manual_override_tag"))
        }
    }
    if (progress.manualOverride) {
        Text(stringResource(R.string.block_manual_hint), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.extra.warning)
    }
}

/** Elapsed, remaining, distance and heart rate of the program: two lines of two. */
@Composable
private fun TotalsPanel(progress: ProgramProgress, sizes: LiveSizes) {
    Panel(Modifier.fillMaxWidth().testTag("run_totals")) {
        Column(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Row(Modifier.fillMaxWidth()) {
                Total(stringResource(R.string.run_elapsed), Format.duration(progress.totalElapsedSeconds), "run_elapsed", sizes)
                Total(stringResource(R.string.run_remaining), Format.duration(progress.totalRemainingSeconds), "run_remaining", sizes)
            }
            Row(Modifier.fillMaxWidth()) {
                Total(stringResource(R.string.metric_distance), Format.distanceKm(progress.totalElapsedMeters), "run_distance", sizes, unit = stringResource(R.string.unit_distance))
                Total(
                    stringResource(R.string.metric_heart_rate), Format.heartRate(progress.heartRateBpm), "run_heart_rate", sizes,
                    unit = if (progress.heartRateBpm != null) stringResource(R.string.unit_heart_rate) else null,
                    badge = progress.heartRateSource.takeIf { progress.heartRateBpm != null },
                )
            }
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.Total(
    label: String,
    value: String,
    tag: String,
    sizes: LiveSizes,
    unit: String? = null,
    badge: com.treadz.console.programs.fitness.HeartRateSourceKind? = null,
) {
    Column(Modifier.weight(1f).semantics(mergeDescendants = true) {}, horizontalAlignment = Alignment.CenterHorizontally) {
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(value, Modifier.testTag(tag), fontSize = sizes.totalValue, fontWeight = FontWeight.Bold, maxLines = 1)
            if (unit != null) Text(unit, Modifier.padding(bottom = 4.dp), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(label, style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
            if (badge != null) HeartRateSourceIcon(badge, size = 14.dp)
        }
    }
}

// ---------------------------------------------------------------------------------------------------------------
// Heart-rate guided block

/**
 * Shown while the current block is guided by heart rate: its zone in bpm, the heart rate now (coloured against the zone),
 * and what the control is doing, in a short text. After a manual change of speed or incline the control is off until the
 * next block: "Resume heart-rate control" switches it on again (through the runner, never the treadmill directly).
 */
@Composable
private fun HeartRateBlockPanel(progress: ProgramProgress, actions: RunActions, modifier: Modifier) {
    val control = progress.heartRateControl ?: return
    val bpm = progress.heartRateBpm
    val low = control.zoneLowBpm
    val high = control.zoneHighBpm
    val ceiling = control.ceilingBpm
    val color = when {
        bpm == null || low == null || high == null -> MaterialTheme.colorScheme.onSurfaceVariant
        ceiling != null && bpm >= ceiling -> MaterialTheme.colorScheme.error
        bpm > high -> MaterialTheme.extra.warning
        bpm < low -> MaterialTheme.colorScheme.secondary
        else -> MaterialTheme.extra.ok
    }
    val stateColor = when (control.state) {
        HeartRateControlState.Active -> MaterialTheme.extra.ok
        HeartRateControlState.Ceiling -> MaterialTheme.colorScheme.error
        HeartRateControlState.Unavailable -> MaterialTheme.colorScheme.onSurfaceVariant
        else -> MaterialTheme.extra.warning
    }
    val compact = LocalLayout.current.compact
    val wide = LocalLayout.current.wide
    val valueStyle = if (compact) MaterialTheme.typography.titleLarge else MaterialTheme.typography.headlineSmall

    @Composable
    fun zone(modifier: Modifier) = Column(modifier) {
        Text(stringResource(R.string.hr_block_zone), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(
            if (low != null && high != null) stringResource(R.string.hr_block_zone_value, low, high) else Format.NO_VALUE,
            Modifier.testTag("hr_zone"),
            style = valueStyle,
            fontWeight = FontWeight.Bold,
            maxLines = 1,
        )
    }

    @Composable
    fun now(modifier: Modifier) = Column(modifier) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
            Text(stringResource(R.string.hr_block_now), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            progress.heartRateSource?.takeIf { bpm != null }?.let { HeartRateSourceIcon(it, size = 14.dp) }
        }
        Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            Text(Format.heartRate(bpm), Modifier.testTag("hr_now"), style = valueStyle, fontWeight = FontWeight.Bold, color = color)
            if (bpm != null) Text(stringResource(R.string.unit_heart_rate), Modifier.padding(bottom = 3.dp), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }

    @Composable
    fun title(modifier: Modifier) = Row(modifier, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(stringResource(R.string.hr_block_title), Modifier.weight(1f, fill = false), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Tag(control.state.shortLabel(), stateColor, Modifier.testTag("hr_control_state").semantics { liveRegion = LiveRegionMode.Polite })
    }

    @Composable
    fun explanation() = Text(control.state.explanation(control.adjust), Modifier.testTag("hr_control_text"), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)

    Panel(modifier.testTag("hr_block_panel"), borderColor = stateColor) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
            if (wide) {
                // Tablet: one band (title and what the control does, the zone, the heart rate now), so the block and its
                // chart keep their room under the fixed speed and incline panels.
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    Column(Modifier.weight(1.6f), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        title(Modifier.fillMaxWidth())
                        explanation()
                    }
                    zone(Modifier.weight(1f))
                    now(Modifier.weight(0.8f))
                }
            } else {
                title(Modifier.fillMaxWidth())
                Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                    zone(Modifier.weight(1f))
                    now(Modifier.weight(1f))
                }
                // On a phone the controls need the room: while all is well the state tag says enough.
                if (!compact || control.state != HeartRateControlState.Active) explanation()
            }
            if (control.state == HeartRateControlState.OffByManualChange) {
                BigButton(stringResource(R.string.hr_block_resume), actions.onResumeHeartRateControl, Modifier.fillMaxWidth().testTag("hr_resume_button"))
            }
        }
    }
}

@Composable
private fun HeartRateControlState.shortLabel(): String = stringResource(
    when (this) {
        HeartRateControlState.Active -> R.string.hr_state_active
        HeartRateControlState.Waiting -> R.string.hr_state_waiting
        HeartRateControlState.HoldingNoHeartRate -> R.string.hr_state_no_heart_rate
        HeartRateControlState.OffByManualChange -> R.string.hr_state_off
        HeartRateControlState.Ceiling -> R.string.hr_state_ceiling
        HeartRateControlState.Unavailable -> R.string.hr_state_unavailable
    },
)

@Composable
private fun HeartRateControlState.explanation(adjust: com.treadz.console.programs.model.HeartRateAdjust): String {
    val what = stringResource(if (adjust == com.treadz.console.programs.model.HeartRateAdjust.Speed) R.string.hr_adjust_speed else R.string.hr_adjust_incline)
    return stringResource(
        when (this) {
            HeartRateControlState.Active -> R.string.hr_text_active
            HeartRateControlState.Waiting -> R.string.hr_text_waiting
            HeartRateControlState.HoldingNoHeartRate -> R.string.hr_text_no_heart_rate
            HeartRateControlState.OffByManualChange -> R.string.hr_text_off
            HeartRateControlState.Ceiling -> R.string.hr_text_ceiling
            HeartRateControlState.Unavailable -> R.string.hr_text_unavailable
        },
        what,
    )
}

// ---------------------------------------------------------------------------------------------------------------
// Interrupted and finished

/** The program stopped on its own: why, and the two ways on. */
@Composable
private fun InterruptedPanel(progress: ProgramProgress, run: RunUiState, actions: RunActions) {
    val reason = progress.interruption?.reason
    Panel(Modifier.fillMaxWidth().testTag("interrupted_panel").semantics { liveRegion = LiveRegionMode.Assertive }, borderColor = MaterialTheme.colorScheme.error) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.interrupted_title), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.error)
            if (reason != null) Text(reason.text(), Modifier.testTag("interrupted_reason"), style = MaterialTheme.typography.bodyLarge)
            Text(stringResource(R.string.interrupted_body), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
            run.continueFailure?.let { failure ->
                val text = when (failure) {
                    is ContinueFailure.Refused -> failure.refusal.text()
                    is ContinueFailure.Command -> StartOutcomeText(failure)
                }
                ErrorText(text, Modifier.testTag("continue_failure"))
            }
            Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                BigButton(stringResource(R.string.interrupted_continue), actions.onContinue, Modifier.weight(1f).testTag("continue_button"), enabled = !run.continuing)
                BigOutlinedButton(stringResource(R.string.interrupted_abandon), actions.onAbandon, Modifier.weight(1f).testTag("abandon_button"), destructive = true)
            }
        }
    }
}

@Composable
private fun StartOutcomeText(failure: ContinueFailure.Command): String =
    com.treadz.console.training.StartOutcome.CommandFailed(failure.result).text()

@Composable
private fun ProgramFinished(
    ui: MainUiState,
    run: RunUiState,
    progress: ProgramProgress,
    segments: List<com.treadz.console.training.PlanSegment>,
    controls: ControlActions,
    actions: RunActions,
    modifier: Modifier,
) {
    val blockCount = progress.plan?.blocks?.size ?: 0
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        Panel(Modifier.weight(1f).fillMaxWidth().testTag("program_summary")) {
            Column(Modifier.padding(horizontal = 18.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                Text(
                    progress.outcome?.text().orEmpty(),
                    Modifier.testTag("program_outcome"),
                    style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold,
                    color = if (progress.outcome == com.treadz.console.programs.engine.RunOutcome.Completed) MaterialTheme.extra.ok else MaterialTheme.colorScheme.onSurface,
                )
                Row(horizontalArrangement = Arrangement.spacedBy(24.dp)) {
                    SummaryFigure(stringResource(R.string.stat_duration), Format.duration(progress.totalElapsedSeconds), "summary_duration")
                    SummaryFigure(stringResource(R.string.stat_distance), stringResource(R.string.value_km, Format.distanceKm(progress.totalElapsedMeters)), "summary_distance")
                    SummaryFigure(stringResource(R.string.summary_blocks), stringResource(R.string.summary_blocks_value, (progress.currentBlockIndex + 1).coerceAtMost(blockCount), blockCount), "summary_blocks")
                }
                run.couchNext?.let { next ->
                    Column(Modifier.fillMaxWidth().testTag("couch_next_panel"), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            stringResource(R.string.couch_next_after, next.session, CouchToFiveK.SESSIONS),
                            Modifier.testTag("couch_next_session"),
                            style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold,
                        )
                        Text(next.decision.text(), Modifier.testTag("couch_next_reason"), style = MaterialTheme.typography.bodyLarge)
                    }
                }
                // The plan as it was run ("+1" included) and what was really done, drawn as the history will show it.
                val actual = rememberActual(run)
                PlannedVersusActualChart(
                    segments = segments,
                    actualSpeed = actual.speed,
                    actualIncline = actual.incline,
                    description = stringResource(R.string.run_plan_description),
                    modifier = Modifier.weight(1f).fillMaxWidth().heightIn(min = 160.dp),
                    chartTag = "finished_chart",
                )
            }
        }
        // The app always ends a program by stopping the belt: only an abandoned one goes on by hand.
        FinishedBar(ui, controls, actions, continuesByHand = progress.outcome == com.treadz.console.programs.engine.RunOutcome.Abandoned)
    }
}

/**
 * "Done" at the end of a program or test. While the belt is still moving (an abandoned program leaves it running, a
 * stopped one is still slowing down) STOP stays next to it, and the live controls are one tap away. The workout can be
 * discarded as soon as it is stopped, also while the belt is slowing down. When the session [continuesByHand] (an
 * abandoned program) its workout is offered for discarding when it ends.
 */
@Composable
fun FinishedBar(ui: MainUiState, controls: ControlActions, actions: RunActions, continuesByHand: Boolean = false) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (ui.beltActive && continuesByHand) {
            Text(stringResource(R.string.finished_belt_running), Modifier.testTag("finished_belt_note"), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                BigButton(stringResource(R.string.action_done), actions.onDismiss, Modifier.weight(1f).testTag("done_button"))
                FinishedBarStop(ui, controls, Modifier.weight(1f))
            }
        } else if (ui.beltActive || ui.finishedWorkout != null) {
            // The workout has been recorded (or is being closed while the belt slows down): it can still be thrown away.
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                DiscardWorkoutButton(actions.onDiscard, Modifier.weight(1f))
                BigButton(stringResource(R.string.action_done), actions.onDismiss, Modifier.weight(2f).testTag("done_button"))
            }
            if (ui.beltActive) FinishedBarStop(ui, controls, Modifier.fillMaxWidth())
        } else {
            BigButton(stringResource(R.string.action_done), actions.onDismiss, Modifier.fillMaxWidth().testTag("done_button"))
        }
    }
}

@Composable
private fun FinishedBarStop(ui: MainUiState, controls: ControlActions, modifier: Modifier) {
    if (ui.stopUnreachable) {
        StopUnreachable(modifier)
    } else {
        StopButton(controls.onStop, textSize = 34.sp, modifier = modifier.height(72.dp))
    }
}

@Composable
private fun DiscardWorkoutButton(onDiscard: () -> Unit, modifier: Modifier) {
    var confirming by rememberSaveable { mutableStateOf(false) }
    BigOutlinedButton(stringResource(R.string.action_discard), { confirming = true }, modifier.testTag("discard_button"), destructive = true)
    if (confirming) {
        DiscardWorkoutDialog(
            onConfirm = {
                confirming = false
                onDiscard()
            },
            onDismiss = { confirming = false },
        )
    }
}

@Composable
private fun SummaryFigure(label: String, value: String, tag: String) {
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, Modifier.testTag(tag), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
    }
}
