package com.treadz.console.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.RowScope
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.treadz.console.MainUiState
import com.treadz.console.R
import com.treadz.console.treadmill.BeltStatus
import com.treadz.console.treadmill.CommandResult
import com.treadz.console.treadmill.ConnectionState
import androidx.compose.ui.graphics.Color

@Composable
fun LiveData(ui: MainUiState, actions: MainActions, wide: Boolean, sizes: LiveSizes, modifier: Modifier = Modifier) {
    val reconnecting = ui.connection is ConnectionState.Reconnecting
    Column(modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(sizes.gap)) {
        if (reconnecting) ReconnectingBanner(ui, actions)
        when {
            // The belt is not at rest: the controls, with STOP always in the same place.
            ui.beltActive && wide -> ActiveWide(ui, actions, sizes, Modifier.weight(1f).fillMaxWidth())
            ui.beltActive -> ActiveCompact(ui, actions, sizes, Modifier.weight(1f).fillMaxWidth())
            // At rest: Start in the slot of STOP, "Programs and tests" above it.
            wide -> LiveWideLayout(
                sizes, Modifier.weight(1f).fillMaxWidth(),
                main = { MetricsGrid(ui, sizes, Modifier.weight(1f).fillMaxWidth()) },
                sideTop = {
                    BeltStatusCard(ui.treadmill.status, Modifier.fillMaxWidth())
                    FinishedWorkoutNotice(ui, actions)
                    RecordingNotice(ui, actions)
                },
                sideBottom = {
                    StartHint(ui, showHint = true)
                    ProgramsButton(actions)
                    LiveStart(ui, actions.onStart, sizes)
                },
            )
            else -> Column(Modifier.weight(1f).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(sizes.gap)) {
                BeltStatusCard(ui.treadmill.status, Modifier.fillMaxWidth(), compact = true)
                // While reconnecting the figures are stale zeros and the banner needs the room.
                if (reconnecting) Spacer(Modifier.weight(1f)) else MetricsGrid(ui, sizes, Modifier.weight(1f).fillMaxWidth())
                FinishedWorkoutNotice(ui, actions)
                RecordingNotice(ui, actions)
                StartHint(ui, showHint = ui.recordingNotice == null && ui.finishedWorkout == null && !reconnecting)
                ProgramsButton(actions)
                LiveStart(ui, actions.onStart, sizes)
            }
        }
    }
}

/** Tablet, belt not at rest: incline and speed with their controls on the left, status, Pause and STOP on the right. */
@Composable
private fun ActiveWide(ui: MainUiState, actions: MainActions, sizes: LiveSizes, modifier: Modifier) {
    LiveWideLayout(
        sizes, modifier,
        main = {
            SpeedInclinePanels(ui, actions.controls, sizes)
            SecondaryMetrics(ui, sizes, Modifier.weight(1f).fillMaxWidth())
        },
        sideTop = {
            BeltStatusCard(ui.treadmill.status, Modifier.fillMaxWidth())
            RecordingNotice(ui, actions)
            ControlHintText(controlHint(ui))
        },
        sideBottom = {
            PauseResumeButton(ui, actions.controls, sizes, Modifier.fillMaxWidth())
            LiveStop(ui, actions.controls, sizes)
        },
    )
}

/**
 * Phone, belt not at rest. Pause and STOP stay at the bottom, always in view; incline, speed and the other figures
 * scroll above them if the screen is short.
 */
@Composable
private fun ActiveCompact(ui: MainUiState, actions: MainActions, sizes: LiveSizes, modifier: Modifier) {
    LiveCompactLayout(
        sizes, modifier,
        content = {
            SpeedInclinePanels(ui, actions.controls, sizes)
            controlHint(ui)?.let { ControlHintText(it, reserveLines = false) }
            SecondaryMetrics(ui, sizes, Modifier.fillMaxWidth())
            RecordingNotice(ui, actions)
        },
        bottom = {
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(sizes.gap), verticalAlignment = Alignment.CenterVertically) {
                BeltStateLabel(ui.treadmill.status, Modifier.weight(0.85f))
                PauseResumeButton(ui, actions.controls, sizes, Modifier.weight(1.15f))
            }
            LiveStop(ui, actions.controls, sizes)
        },
    )
}

/** Distance, time, calories and heart rate: below the controls, smaller than the speed and the incline. */
@Composable
private fun SecondaryMetrics(ui: MainUiState, sizes: LiveSizes, modifier: Modifier) {
    val t = ui.treadmill
    val stale = t.isStale || ui.connection is ConnectionState.Reconnecting
    val hr = ui.strap.heartRate
    val noHr = hr.bpm == null
    if (!sizes.wide) {
        // One line on the phone: the speed and the incline keep the room.
        MetricStrip(
            items = listOf(
                StripItem(stringResource(R.string.metric_distance), Format.distanceKm(t.distanceMeters), stringResource(R.string.unit_distance)),
                StripItem(stringResource(R.string.metric_time), Format.duration(t.elapsedSeconds), null),
                StripItem(stringResource(R.string.metric_calories), Format.calories(t.calories), stringResource(R.string.unit_calories)),
                StripItem(stringResource(R.string.metric_heart_rate), Format.heartRate(hr.bpm), if (noHr) null else stringResource(R.string.unit_heart_rate), badge = hr.source),
            ),
            dimmed = stale,
            modifier = modifier,
        )
        return
    }
    @Composable
    fun RowScope.metric(labelRes: Int, value: String, unitRes: Int?, note: String? = null, labelTrailing: (@Composable () -> Unit)? = null) = MetricCard(
        label = stringResource(labelRes),
        value = value,
        unit = unitRes?.let { stringResource(it) },
        modifier = Modifier.weight(1f).fillMaxSize(),
        note = note,
        maxValueSize = sizes.secondaryMax,
        unitSize = 24.sp,
        unitInline = true,
        dimmed = stale,
        labelTrailing = labelTrailing,
    )
    Column(modifier, verticalArrangement = Arrangement.spacedBy(sizes.gap)) {
        Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(sizes.gap)) {
            metric(R.string.metric_distance, Format.distanceKm(t.distanceMeters), R.string.unit_distance)
            metric(R.string.metric_time, Format.duration(t.elapsedSeconds), null)
        }
        Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(sizes.gap)) {
            metric(R.string.metric_calories, Format.calories(t.calories), R.string.unit_calories)
            metric(
                R.string.metric_heart_rate,
                Format.heartRate(hr.bpm),
                if (noHr) null else R.string.unit_heart_rate,
                note = if (noHr) noHeartRateText(hr.source) else null,
                labelTrailing = hr.source?.let { source -> { HeartRateSourceBadge(source) } },
            )
        }
    }
}

/** Belt state as a single line of big text (phone, next to the pause button). */
@Composable
private fun BeltStateLabel(status: BeltStatus, modifier: Modifier = Modifier) {
    val (textRes, color) = when (status) {
        BeltStatus.Idle -> R.string.belt_idle to MaterialTheme.colorScheme.onSurfaceVariant
        BeltStatus.Starting -> R.string.belt_starting to MaterialTheme.extra.warning
        BeltStatus.Running -> R.string.belt_running to MaterialTheme.extra.ok
        BeltStatus.Paused -> R.string.belt_paused to MaterialTheme.extra.warning
        BeltStatus.SafetyKeyRemoved -> R.string.belt_key_removed to MaterialTheme.colorScheme.error
    }
    Row(modifier.semantics { liveRegion = LiveRegionMode.Polite }, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        StatusDot(color, size = 18.dp)
        Text(
            stringResource(textRes),
            modifier = Modifier.testTag("belt_status"),
            style = MaterialTheme.typography.titleLarge,
            fontWeight = FontWeight.Bold,
            color = color,
            maxLines = 2,
        )
    }
}

@Composable
private fun MetricsGrid(ui: MainUiState, sizes: LiveSizes, modifier: Modifier = Modifier) {
    val t = ui.treadmill
    val stale = t.isStale || ui.connection is ConnectionState.Reconnecting
    val hr = ui.strap.heartRate
    val noHr = hr.bpm == null
    @Composable
    fun card(
        labelRes: Int,
        value: String,
        unitRes: Int?,
        mod: Modifier,
        note: String? = null,
        dimmed: Boolean = false,
        labelTrailing: (@Composable () -> Unit)? = null,
        fixed: Boolean = false,
    ) = MetricCard(
        label = stringResource(labelRes),
        value = value,
        unit = unitRes?.let { stringResource(it) },
        modifier = mod,
        note = note,
        maxValueSize = if (fixed) sizes.figure else sizes.metricMax,
        fixedValueSize = fixed,
        unitSize = sizes.unit,
        unitInline = sizes.unitInline,
        dimmed = dimmed || stale,
        labelTrailing = labelTrailing,
    )

    Column(modifier, verticalArrangement = Arrangement.spacedBy(sizes.gap)) {
        // Incline and speed at the fixed size of the live figures (on the phone in a row of fixed height, so a notice
        // below never squeezes them); the other figures share what is left.
        val figureRow = if (sizes.wide) Modifier.weight(1f) else Modifier.height(sizes.figurePanelHeight)
        Row(figureRow.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(sizes.gap)) {
            card(R.string.metric_incline, Format.incline(t.inclinePercent), R.string.unit_incline, Modifier.weight(1f).fillMaxSize(), fixed = true)
            card(R.string.metric_speed, Format.speed(t.speedKmh), R.string.unit_speed, Modifier.weight(1f).fillMaxSize(), fixed = true)
        }
        Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(sizes.gap)) {
            card(R.string.metric_distance, Format.distanceKm(t.distanceMeters), R.string.unit_distance, Modifier.weight(1f).fillMaxSize())
            card(R.string.metric_time, Format.duration(t.elapsedSeconds), null, Modifier.weight(1f).fillMaxSize())
        }
        Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(sizes.gap)) {
            card(R.string.metric_calories, Format.calories(t.calories), R.string.unit_calories, Modifier.weight(1f).fillMaxSize())
            card(
                R.string.metric_heart_rate,
                Format.heartRate(hr.bpm),
                if (noHr) null else R.string.unit_heart_rate,
                Modifier.weight(1f).fillMaxSize(),
                note = if (noHr) noHeartRateText(hr.source) else null,
                labelTrailing = hr.source?.let { source -> { HeartRateSourceBadge(source, withName = sizes.unitInline) } },
            )
        }
    }
}

@Composable
private fun BeltStatusCard(status: BeltStatus, modifier: Modifier = Modifier, compact: Boolean = false) {
    val (textRes, color) = when (status) {
        BeltStatus.Idle -> R.string.belt_idle to MaterialTheme.colorScheme.onSurfaceVariant
        BeltStatus.Starting -> R.string.belt_starting to MaterialTheme.extra.warning
        BeltStatus.Running -> R.string.belt_running to MaterialTheme.extra.ok
        BeltStatus.Paused -> R.string.belt_paused to MaterialTheme.extra.warning
        BeltStatus.SafetyKeyRemoved -> R.string.belt_key_removed to MaterialTheme.colorScheme.error
    }
    Surface(
        modifier = modifier.semantics { liveRegion = LiveRegionMode.Polite },
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
    ) {
        if (compact) {
            Row(
                Modifier.padding(horizontal = 20.dp, vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                StatusDot(color, size = 20.dp)
                Text(
                    stringResource(R.string.belt_label),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                Text(
                    stringResource(textRes),
                    modifier = Modifier.testTag("belt_status"),
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.Bold,
                    color = color,
                )
            }
        } else {
            Row(
                Modifier.padding(horizontal = 20.dp, vertical = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                StatusDot(color, size = 22.dp)
                Column {
                    Text(
                        stringResource(R.string.belt_label),
                        style = MaterialTheme.typography.titleMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                    Text(
                        stringResource(textRes),
                        modifier = Modifier.testTag("belt_status"),
                        style = MaterialTheme.typography.headlineLarge,
                        fontWeight = FontWeight.Bold,
                        color = color,
                    )
                }
            }
        }
    }
}

/** "Programs and tests", above Start. */
@Composable
private fun ProgramsButton(actions: MainActions) {
    BigOutlinedButton(stringResource(R.string.main_programs), actions.onOpenPrograms, Modifier.fillMaxWidth().testTag("programs_button"))
}

/** Above "Programs and tests" and Start: why Start failed, or what to do (when [showHint]). */
@Composable
private fun StartHint(ui: MainUiState, showHint: Boolean) {
    val failure = ui.startFailure
    if (failure != null) {
        Text(
            text = startFailureMessage(failure),
            modifier = Modifier.fillMaxWidth().testTag("start_failure").semantics { liveRegion = LiveRegionMode.Assertive },
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
            textAlign = TextAlign.Center,
        )
    } else if (showHint) {
        val hint = when {
            ui.activeUser == null -> R.string.start_hint_no_user
            !ui.isConnected -> R.string.start_hint_disconnected
            ui.startInProgress -> R.string.start_hint_busy
            else -> R.string.start_hint_idle
        }
        Text(
            text = stringResource(hint),
            modifier = Modifier.fillMaxWidth().testTag("start_hint"),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )
    }
}

@Composable
private fun startFailureMessage(result: CommandResult): String = when (result) {
    CommandResult.Success -> ""
    CommandResult.NotConnected -> stringResource(R.string.start_failed_not_connected)
    is CommandResult.Failure -> stringResource(R.string.start_failed, failureText(result.code))
}

/** The note under a heart rate figure that has no value: no monitor at all, or a strap that gives no reading. */
@Composable
private fun noHeartRateText(source: com.treadz.console.programs.fitness.HeartRateSourceKind?): String =
    stringResource(if (source == com.treadz.console.programs.fitness.HeartRateSourceKind.DirectStrap) R.string.heart_rate_strap_no_reading else R.string.heart_rate_none)

/** The source of the heart rate shown: its small icon and, where there is room, its name. */
@Composable
fun HeartRateSourceBadge(source: com.treadz.console.programs.fitness.HeartRateSourceKind, withName: Boolean = true) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp), modifier = Modifier.testTag("hr_source_badge")) {
        HeartRateSourceIcon(source)
        if (withName) Text(source.label(), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
    }
}
