package com.treadz.console.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
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
import androidx.compose.ui.graphics.Color
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

/** Sizes that change with the window size class. */
data class LiveSizes(
    val maxValue: androidx.compose.ui.unit.TextUnit,
    val unit: androidx.compose.ui.unit.TextUnit,
    val startHeight: Dp,
    val startText: androidx.compose.ui.unit.TextUnit,
    val gap: Dp,
    /** The unit goes next to the figure (tablet) or on the label line (phone, where width is short). */
    val unitInline: Boolean,
)

@Composable
fun LiveData(ui: MainUiState, actions: MainActions, wide: Boolean, sizes: LiveSizes, modifier: Modifier = Modifier) {
    val reconnecting = ui.connection is ConnectionState.Reconnecting
    Column(modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(sizes.gap)) {
        if (reconnecting) ReconnectingBanner()
        if (wide) {
            Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(sizes.gap)) {
                MetricsGrid(ui, sizes, Modifier.weight(2.1f).fillMaxSize())
                Column(
                    Modifier.weight(1f).fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(sizes.gap),
                ) {
                    BeltStatusCard(ui.treadmill.status, Modifier.fillMaxWidth())
                    RecordingNotice(ui, actions)
                    // On the tablet the button takes all the free height: it is the only thing to press.
                    StartSection(ui, actions, sizes, fillHeight = true, modifier = Modifier.weight(1f))
                }
            }
        } else {
            BeltStatusCard(ui.treadmill.status, Modifier.fillMaxWidth(), compact = true)
            MetricsGrid(ui, sizes, Modifier.weight(1f).fillMaxWidth())
            RecordingNotice(ui, actions)
            StartSection(ui, actions, sizes, showHint = ui.recordingNotice == null && !reconnecting)
        }
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
                !ui.isConnected -> R.string.start_hint_disconnected
                ui.treadmill.status == BeltStatus.Idle -> R.string.start_hint_idle
                else -> R.string.start_hint_busy
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

@Composable
private fun ReconnectingBanner() {
    Surface(
        modifier = Modifier.fillMaxWidth().semantics { liveRegion = LiveRegionMode.Polite },
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.extra.warning,
        contentColor = MaterialTheme.extra.onWarning,
    ) {
        Text(
            stringResource(R.string.reconnecting_banner),
            Modifier.padding(horizontal = 20.dp, vertical = 12.dp),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold,
        )
    }
}
