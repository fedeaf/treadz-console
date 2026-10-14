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

/** Sizes that change with the window size class. */
data class LiveSizes(
    val maxValue: androidx.compose.ui.unit.TextUnit,
    val unit: androidx.compose.ui.unit.TextUnit,
    val startHeight: Dp,
    val startText: androidx.compose.ui.unit.TextUnit,
    val gap: Dp,
    /** The unit goes next to the figure (tablet) or on the label line (phone, where width is short). */
    val unitInline: Boolean,
    /** Two panes (tablet in landscape): the controls fill the height they are given. */
    val wide: Boolean,
    /** Width of the −/+ buttons (their height is at least this too). */
    val stepButton: Dp,
    /** Height of the −/+ row on the phone (on the tablet the row takes the free height). */
    val stepRowHeight: Dp,
    /** Biggest size of the speed/incline figure between the −/+ buttons. */
    val stepValue: androidx.compose.ui.unit.TextUnit,
    val presetHeight: Dp,
    val pauseHeight: Dp,
    val pauseText: androidx.compose.ui.unit.TextUnit,
    val stopHeight: Dp,
    val stopText: androidx.compose.ui.unit.TextUnit,
)

/** The sizes of the live screens: the big ones of the tablet in landscape, the smaller ones of the phone. */
fun liveSizes(wide: Boolean): LiveSizes = if (wide) {
    LiveSizes(
        maxValue = 120.sp, unit = 30.sp, startHeight = 112.dp, startText = 56.sp, gap = 16.dp, unitInline = true,
        wide = true, stepButton = 108.dp, stepRowHeight = 112.dp, stepValue = 120.sp, presetHeight = 64.dp,
        pauseHeight = 88.dp, pauseText = 40.sp, stopHeight = 128.dp, stopText = 88.sp,
    )
} else {
    LiveSizes(
        maxValue = 52.sp, unit = 22.sp, startHeight = 88.dp, startText = 34.sp, gap = 12.dp, unitInline = false,
        wide = false, stepButton = 72.dp, stepRowHeight = 100.dp, stepValue = 60.sp, presetHeight = 52.dp,
        pauseHeight = 64.dp, pauseText = 26.sp, stopHeight = 92.dp, stopText = 52.sp,
    )
}

@Composable
fun LiveData(ui: MainUiState, actions: MainActions, wide: Boolean, sizes: LiveSizes, modifier: Modifier = Modifier) {
    val reconnecting = ui.connection is ConnectionState.Reconnecting
    Column(modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(sizes.gap)) {
        if (reconnecting) ReconnectingBanner(ui, actions)
        when {
            // The belt is not at rest: the controls, with STOP always in the same place.
            ui.beltActive && wide -> ActiveWide(ui, actions, sizes, Modifier.weight(1f).fillMaxWidth())
            ui.beltActive -> ActiveCompact(ui, actions, sizes, Modifier.weight(1f).fillMaxWidth())
            wide -> Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(sizes.gap)) {
                MetricsGrid(ui, sizes, Modifier.weight(2.1f).fillMaxSize())
                Column(
                    Modifier.weight(1f).fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(sizes.gap),
                ) {
                    BeltStatusCard(ui.treadmill.status, Modifier.fillMaxWidth())
                    FinishedWorkoutNotice(ui, actions)
                    RecordingNotice(ui, actions)
                    // On the tablet the button takes all the free height: it is the only thing to press.
                    StartSection(ui, actions, sizes, fillHeight = true, modifier = Modifier.weight(1f))
                }
            }
            else -> {
                BeltStatusCard(ui.treadmill.status, Modifier.fillMaxWidth(), compact = true)
                // While reconnecting the figures are stale zeros and the banner needs the room.
                if (reconnecting) Spacer(Modifier.weight(1f)) else MetricsGrid(ui, sizes, Modifier.weight(1f).fillMaxWidth())
                FinishedWorkoutNotice(ui, actions)
                RecordingNotice(ui, actions)
                StartSection(ui, actions, sizes, showHint = ui.recordingNotice == null && ui.finishedWorkout == null && !reconnecting)
            }
        }
    }
}

/** Tablet, belt not at rest: speed and incline with their controls on the left, status, pause and STOP on the right. */
@Composable
private fun ActiveWide(ui: MainUiState, actions: MainActions, sizes: LiveSizes, modifier: Modifier) {
    Row(modifier, horizontalArrangement = Arrangement.spacedBy(sizes.gap)) {
        Column(Modifier.weight(2.1f).fillMaxSize(), verticalArrangement = Arrangement.spacedBy(sizes.gap)) {
            Row(Modifier.weight(2.4f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(sizes.gap)) {
                SpeedPanel(ui, actions.controls, sizes, Modifier.weight(1f).fillMaxSize())
                InclinePanel(ui, actions.controls, sizes, Modifier.weight(1f).fillMaxSize())
            }
            SecondaryMetrics(ui, sizes, Modifier.weight(2f).fillMaxWidth())
        }
        Column(Modifier.weight(1f).fillMaxSize(), verticalArrangement = Arrangement.spacedBy(sizes.gap)) {
            BeltStatusCard(ui.treadmill.status, Modifier.fillMaxWidth())
            RecordingNotice(ui, actions)
            ControlHintText(controlHint(ui))
            PauseResumeButton(ui, actions.controls, sizes, Modifier.fillMaxWidth())
            // STOP takes all the free height: the biggest target on the screen.
            StopSection(ui, actions.controls, sizes, Modifier.fillMaxWidth().weight(1f).heightIn(min = sizes.stopHeight))
        }
    }
}

/**
 * Phone, belt not at rest. Pause and STOP stay at the bottom, always in view; speed, incline and the other figures
 * scroll above them if the screen is short.
 */
@Composable
private fun ActiveCompact(ui: MainUiState, actions: MainActions, sizes: LiveSizes, modifier: Modifier) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(sizes.gap)) {
        Column(
            Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(sizes.gap),
        ) {
            SpeedPanel(ui, actions.controls, sizes, Modifier.fillMaxWidth())
            InclinePanel(ui, actions.controls, sizes, Modifier.fillMaxWidth())
            controlHint(ui)?.let { ControlHintText(it, reserveLines = false) }
            SecondaryMetrics(ui, sizes, Modifier.fillMaxWidth())
            RecordingNotice(ui, actions)
        }
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(sizes.gap), verticalAlignment = Alignment.CenterVertically) {
            BeltStateLabel(ui.treadmill.status, Modifier.weight(0.85f))
            PauseResumeButton(ui, actions.controls, sizes, Modifier.weight(1.15f))
        }
        StopSection(
            ui, actions.controls, sizes,
            // The instruction that replaces STOP may need more lines than the button is tall.
            Modifier.fillMaxWidth().then(if (ui.stopUnreachable) Modifier.heightIn(min = sizes.stopHeight) else Modifier.height(sizes.stopHeight)),
        )
    }
}

/** Distance, time, calories and heart rate: below the controls, smaller than the speed and the incline. */
@Composable
private fun SecondaryMetrics(ui: MainUiState, sizes: LiveSizes, modifier: Modifier) {
    val t = ui.treadmill
    val stale = t.isStale || ui.connection is ConnectionState.Reconnecting
    val noHr = t.heartRateBpm == null
    if (!sizes.wide) {
        // One line on the phone: the speed and the incline keep the room.
        MetricStrip(
            items = listOf(
                Triple(stringResource(R.string.metric_distance), Format.distanceKm(t.distanceMeters), stringResource(R.string.unit_distance)),
                Triple(stringResource(R.string.metric_time), Format.duration(t.elapsedSeconds), null),
                Triple(stringResource(R.string.metric_calories), Format.calories(t.calories), stringResource(R.string.unit_calories)),
                Triple(stringResource(R.string.metric_heart_rate), Format.heartRate(t.heartRateBpm), if (noHr) null else stringResource(R.string.unit_heart_rate)),
            ),
            dimmed = stale,
            modifier = modifier,
        )
        return
    }
    @Composable
    fun RowScope.metric(labelRes: Int, value: String, unitRes: Int?, note: String? = null) = MetricCard(
        label = stringResource(labelRes),
        value = value,
        unit = unitRes?.let { stringResource(it) },
        modifier = Modifier.weight(1f).fillMaxSize(),
        note = note,
        maxValueSize = 72.sp,
        unitSize = 24.sp,
        unitInline = true,
        dimmed = stale,
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
                Format.heartRate(t.heartRateBpm),
                if (noHr) null else R.string.unit_heart_rate,
                note = if (noHr) stringResource(R.string.heart_rate_none) else null,
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
    val noHr = t.heartRateBpm == null
    @Composable
    fun card(
        labelRes: Int,
        value: String,
        unitRes: Int?,
        mod: Modifier,
        note: String? = null,
        dimmed: Boolean = false,
    ) = MetricCard(
        label = stringResource(labelRes),
        value = value,
        unit = unitRes?.let { stringResource(it) },
        modifier = mod,
        note = note,
        maxValueSize = sizes.maxValue,
        unitSize = sizes.unit,
        unitInline = sizes.unitInline,
        dimmed = dimmed || stale,
    )

    Column(modifier, verticalArrangement = Arrangement.spacedBy(sizes.gap)) {
        Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(sizes.gap)) {
            card(R.string.metric_speed, Format.speed(t.speedKmh), R.string.unit_speed, Modifier.weight(1f).fillMaxSize())
            card(R.string.metric_incline, Format.incline(t.inclinePercent), R.string.unit_incline, Modifier.weight(1f).fillMaxSize())
        }
        Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(sizes.gap)) {
            card(R.string.metric_distance, Format.distanceKm(t.distanceMeters), R.string.unit_distance, Modifier.weight(1f).fillMaxSize())
            card(R.string.metric_time, Format.duration(t.elapsedSeconds), null, Modifier.weight(1f).fillMaxSize())
        }
        Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(sizes.gap)) {
            card(R.string.metric_calories, Format.calories(t.calories), R.string.unit_calories, Modifier.weight(1f).fillMaxSize())
            card(
                R.string.metric_heart_rate,
                Format.heartRate(t.heartRateBpm),
                if (noHr) null else R.string.unit_heart_rate,
                Modifier.weight(1f).fillMaxSize(),
                note = if (noHr) stringResource(R.string.heart_rate_none) else null,
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

@Composable
private fun StartSection(
    ui: MainUiState,
    actions: MainActions,
    sizes: LiveSizes,
    modifier: Modifier = Modifier,
    fillHeight: Boolean = false,
    showHint: Boolean = true,
) {
    Column(modifier, verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Button(
            onClick = actions.onStart,
            enabled = ui.canStart,
            modifier = Modifier
                .fillMaxWidth()
                .then(if (fillHeight) Modifier.weight(1f) else Modifier.height(sizes.startHeight))
                .testTag("start_button"),
            shape = RoundedCornerShape(28.dp),
            colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary,
                disabledContainerColor = MaterialTheme.colorScheme.surfaceContainerHigh,
                disabledContentColor = MaterialTheme.colorScheme.onSurfaceVariant,
            ),
        ) {
            if (ui.startInProgress) {
                CircularProgressIndicator(
                    Modifier.size(sizes.startText.value.dp),
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    strokeWidth = 4.dp,
                )
            } else {
                Text(
                    stringResource(R.string.start_button),
                    fontSize = sizes.startText,
                    fontWeight = FontWeight.ExtraBold,
                    letterSpacing = 2.sp,
                )
            }
        }
        BigOutlinedButton(
            stringResource(R.string.main_programs),
            actions.onOpenPrograms,
            Modifier.fillMaxWidth().testTag("programs_button"),
        )
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
                modifier = Modifier.fillMaxWidth(),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                textAlign = TextAlign.Center,
            )
        }
    }
}

@Composable
private fun startFailureMessage(result: CommandResult): String = when (result) {
    CommandResult.Success -> ""
    CommandResult.NotConnected -> stringResource(R.string.start_failed_not_connected)
    is CommandResult.Failure -> stringResource(R.string.start_failed, failureText(result.code))
}
