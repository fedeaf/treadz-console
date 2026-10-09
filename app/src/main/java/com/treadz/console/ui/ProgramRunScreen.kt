package com.treadz.console.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
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
import androidx.compose.runtime.remember
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
import com.treadz.console.programs.engine.ProgramProgress
import com.treadz.console.programs.engine.RunState
import com.treadz.console.programs.model.BlockEnd
import com.treadz.console.programs.plan.ResolvedBlock
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
    val onUseMeasuredHeartRateMax: () -> Unit = {},
    val onKeepManualHeartRateMax: () -> Unit = {},
    /** Opens the stored result of a fitness test. */
    val onOpenTest: (String) -> Unit = {},
)

/**
 * The live screen while a program runs (and its summary when it ends): the current block with its time left, speed and
 * incline controls, the plan with the position, totals, and always STOP.
 */
@Composable
fun ProgramRunScreen(ui: MainUiState, run: RunUiState, controls: ControlActions, actions: RunActions, modifier: Modifier = Modifier) {
    val progress = run.program
    val layout = LocalLayout.current
    val wide = layout.wide
    val base = liveSizes(wide)
    // The block and the plan need room next to the speed and incline panels: slightly smaller controls on the tablet.
    val sizes = if (wide) base.copy(stepButton = 84.dp, stepRowHeight = 88.dp, stepValue = 96.sp, presetHeight = 52.dp, stopHeight = 112.dp, stopText = 72.sp, pauseHeight = 72.dp) else base
    val plan = progress.plan
    val segments = remember(plan) { plan?.segments().orEmpty() }
    val title = plan?.let { programDisplayName(it.source.programId, it.source.programName) }.orEmpty()

    Column(modifier.fillMaxSize().testTag("program_run"), verticalArrangement = Arrangement.spacedBy(sizes.gap)) {
        RunHeader(title, progress)
        when (progress.state) {
            RunState.Finished -> ProgramFinished(ui, progress, segments, controls, actions, Modifier.weight(1f))
            else -> {
                if (progress.state == RunState.Interrupted) InterruptedPanel(progress, run, actions)
                if (wide) ProgramWide(ui, run, controls, actions, sizes, segments, Modifier.weight(1f))
                else ProgramCompact(ui, run, controls, actions, sizes, segments, Modifier.weight(1f))
            }
        }
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

@Composable
private fun ProgramWide(
    ui: MainUiState, run: RunUiState, controls: ControlActions, actions: RunActions, sizes: LiveSizes,
    segments: List<com.treadz.console.training.PlanSegment>, modifier: Modifier,
) {
    val progress = run.program
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(sizes.gap)) {
        Column(Modifier.weight(2.1f).fillMaxSize(), verticalArrangement = Arrangement.spacedBy(sizes.gap)) {
            Row(Modifier.weight(2.3f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(sizes.gap)) {
                SpeedPanel(ui, controls, sizes, Modifier.weight(1f).fillMaxSize())
                InclinePanel(ui, controls, sizes, Modifier.weight(1f).fillMaxSize())
            }
            BlockPanel(progress, segments, Modifier.weight(2f).fillMaxWidth(), chartFills = true)
        }
        Column(Modifier.weight(1f).fillMaxSize(), verticalArrangement = Arrangement.spacedBy(sizes.gap)) {
            TotalsPanel(progress, big = true)
            ControlHintText(controlHint(ui), reserveLines = false)
            if (progress.state != RunState.Interrupted) {
                SkipRow(progress, actions, Modifier.fillMaxWidth())
                PauseResumeButton(ui, controls, sizes, Modifier.fillMaxWidth())
            }
            StopSection(ui, controls, sizes, Modifier.fillMaxWidth().weight(1f).heightIn(min = sizes.stopHeight))
        }
    }
}

@Composable
private fun ProgramCompact(
    ui: MainUiState, run: RunUiState, controls: ControlActions, actions: RunActions, sizes: LiveSizes,
    segments: List<com.treadz.console.training.PlanSegment>, modifier: Modifier,
) {
    val progress = run.program
    val previousText = stringResource(R.string.block_previous)
    val nextText = stringResource(R.string.block_skip)
    Column(modifier, verticalArrangement = Arrangement.spacedBy(sizes.gap)) {
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(sizes.gap)) {
            BlockPanel(progress, segments, Modifier.fillMaxWidth(), chartFills = false, showChart = false)
            SpeedPanel(ui, controls, sizes, Modifier.fillMaxWidth())
            InclinePanel(ui, controls, sizes, Modifier.fillMaxWidth())
            controlHint(ui)?.let { ControlHintText(it, reserveLines = false) }
            TotalsPanel(progress, big = false)
            Panel(Modifier.fillMaxWidth().testTag("block_chart_panel")) {
                Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp)) {
                    RunChart(progress, segments, Modifier.height(170.dp).fillMaxWidth())
                }
            }
        }
        if (progress.state != RunState.Interrupted) {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp), verticalAlignment = Alignment.CenterVertically) {
                val enabled = progress.state == RunState.Running || progress.state == RunState.Paused
                BigOutlinedButton(
                    "◀", actions.onSkipPrevious,
                    Modifier.width(76.dp).semantics { contentDescription = previousText }.testTag("skip_previous_button"), enabled = enabled,
                )
                PauseResumeButton(ui, controls, sizes, Modifier.weight(1f))
                BigOutlinedButton(
                    "▶", actions.onSkipNext,
                    Modifier.width(76.dp).semantics { contentDescription = nextText }.testTag("skip_next_button"), enabled = enabled,
                )
            }
        }
        StopSection(
            ui, controls, sizes,
            Modifier.fillMaxWidth().then(if (ui.stopUnreachable) Modifier.heightIn(min = sizes.stopHeight) else Modifier.height(sizes.stopHeight)),
        )
    }
}

// ---------------------------------------------------------------------------------------------------------------
// Block, totals, skip

@Composable
private fun blockTargetText(block: ResolvedBlock): String =
    stringResource(R.string.block_target, Format.speed(block.speedKmh), Format.incline(block.inclinePercent))

@Composable
private fun blockLengthText(block: ResolvedBlock, locale: java.util.Locale): String = when (val e = block.end) {
    is BlockEnd.Duration -> Format.duration(e.seconds, locale)
    is BlockEnd.Distance -> stringResource(R.string.value_km, Format.distanceKm(e.meters, locale))
}

/** The current block: phase, name, how much is left, what it asks for, what comes next, and the plan with the position. */
@Composable
private fun BlockPanel(
    progress: ProgramProgress,
    segments: List<com.treadz.console.training.PlanSegment>,
    modifier: Modifier,
    chartFills: Boolean,
    showChart: Boolean = true,
) {
    val block = progress.currentBlock
    val locale = currentLocale()
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
                            Format.duration(progress.blockRemainingSeconds, locale),
                            Modifier.testTag("block_remaining"),
                            style = MaterialTheme.typography.headlineLarge,
                            fontWeight = FontWeight.Bold,
                        )
                        progress.blockRemainingMeters?.let {
                            Text(stringResource(R.string.block_distance_left, Format.distanceKm(it, locale)), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        }
                    }
                    Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(
                            blockTargetText(block),
                            Modifier.testTag("block_target"),
                            style = MaterialTheme.typography.titleMedium,
                            fontWeight = FontWeight.SemiBold,
                        )
                        progress.nextBlock?.let { next ->
                            Text(
                                stringResource(R.string.block_next, next.phase.label(), blockTargetText(next), blockLengthText(next, locale)),
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
                    modifier = Modifier.fillMaxWidth().height(6.dp),
                    color = MaterialTheme.colorScheme.primary,
                    trackColor = MaterialTheme.colorScheme.outlineVariant,
                )
            } else {
                Text(stringResource(R.string.run_state_starting), style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            }
            if (showChart) RunChart(progress, segments, (if (chartFills) Modifier.weight(1f) else Modifier.height(170.dp)).fillMaxWidth())
        }
    }
}

@Composable
private fun RunChart(progress: ProgramProgress, segments: List<com.treadz.console.training.PlanSegment>, modifier: Modifier) {
    PlanChart(
        segments = segments,
        description = stringResource(R.string.run_plan_description),
        modifier = modifier.testTag("run_chart"),
        positionSeconds = progress.positionSeconds(segments),
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

@Composable
private fun TotalsPanel(progress: ProgramProgress, big: Boolean) {
    val locale = currentLocale()
    Panel(Modifier.fillMaxWidth().testTag("run_totals")) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 10.dp), horizontalArrangement = Arrangement.SpaceEvenly) {
            Total(stringResource(R.string.run_elapsed), Format.duration(progress.totalElapsedSeconds, locale), "run_elapsed", big)
            Total(stringResource(R.string.run_remaining), Format.duration(progress.totalRemainingSeconds, locale), "run_remaining", big)
            Total(stringResource(R.string.metric_distance), Format.distanceKm(progress.totalElapsedMeters, locale), "run_distance", big)
            Total(stringResource(R.string.metric_heart_rate), Format.heartRate(progress.heartRateBpm, locale), "run_heart_rate", big)
        }
    }
}

@Composable
private fun androidx.compose.foundation.layout.RowScope.Total(label: String, value: String, tag: String, big: Boolean) {
    Column(Modifier.weight(1f), horizontalAlignment = Alignment.CenterHorizontally) {
        Text(value, Modifier.testTag(tag), fontSize = if (big) 28.sp else 20.sp, fontWeight = FontWeight.Bold, maxLines = 1)
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
    }
}

/** Previous / next block, like a media player. */
@Composable
private fun SkipRow(progress: ProgramProgress, actions: RunActions, modifier: Modifier) {
    val enabled = progress.state == RunState.Running || progress.state == RunState.Paused
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        BigOutlinedButton("◀  " + stringResource(R.string.block_previous), actions.onSkipPrevious, Modifier.weight(1f).testTag("skip_previous_button"), enabled = enabled)
        BigOutlinedButton(stringResource(R.string.block_skip) + "  ▶", actions.onSkipNext, Modifier.weight(1f).testTag("skip_next_button"), enabled = enabled)
    }
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
    progress: ProgramProgress,
    segments: List<com.treadz.console.training.PlanSegment>,
    controls: ControlActions,
    actions: RunActions,
    modifier: Modifier,
) {
    val locale = currentLocale()
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
                    SummaryFigure(stringResource(R.string.stat_duration), Format.duration(progress.totalElapsedSeconds, locale), "summary_duration")
                    SummaryFigure(stringResource(R.string.stat_distance), stringResource(R.string.value_km, Format.distanceKm(progress.totalElapsedMeters, locale)), "summary_distance")
                    SummaryFigure(stringResource(R.string.summary_blocks), stringResource(R.string.summary_blocks_value, (progress.currentBlockIndex + 1).coerceAtMost(blockCount), blockCount), "summary_blocks")
                }
                PlanChart(
                    segments = segments,
                    description = stringResource(R.string.run_plan_description),
                    modifier = Modifier.weight(1f).fillMaxWidth().heightIn(min = 120.dp),
                )
            }
        }
        FinishedBar(ui, controls, actions, abandoned = progress.outcome == com.treadz.console.programs.engine.RunOutcome.Abandoned)
    }
}

/**
 * "Done" at the end of a program or test. While the belt is still moving (an abandoned program leaves it running, a
 * stopped one is still slowing down) STOP stays next to it, and the live controls are one tap away.
 */
@Composable
fun FinishedBar(ui: MainUiState, controls: ControlActions, actions: RunActions, abandoned: Boolean = false) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        if (ui.beltActive) {
            if (abandoned) {
                Text(stringResource(R.string.finished_belt_running), Modifier.testTag("finished_belt_note"), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.CenterVertically) {
                BigButton(stringResource(R.string.action_done), actions.onDismiss, Modifier.weight(1f).testTag("done_button"))
                if (ui.stopUnreachable) {
                    StopUnreachable(Modifier.weight(1f))
                } else {
                    StopButton(controls.onStop, textSize = 34.sp, modifier = Modifier.weight(1f).height(72.dp))
                }
            }
        } else {
            BigButton(stringResource(R.string.action_done), actions.onDismiss, Modifier.fillMaxWidth().testTag("done_button"))
        }
    }
}

@Composable
private fun SummaryFigure(label: String, value: String, tag: String) {
    Column {
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, Modifier.testTag(tag), style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
    }
}
