package com.treadz.console.ui

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.treadz.console.MainUiState
import com.treadz.console.R
import com.treadz.console.TreadmillIndicator
import com.treadz.console.home.HomeUiState
import java.time.Instant
import java.time.ZoneId

/**
 * The main screen while the treadmill is not connected: large shortcuts for everything that works without it (programs,
 * resting heart rate, weight, history) and "Train", which opens Devices until the treadmill is connected. When the
 * treadmill connects the main screen switches to the live data by itself.
 */
@Composable
fun HomeScreen(ui: MainUiState, home: HomeUiState, actions: MainActions, modifier: Modifier = Modifier) {
    val layout = LocalLayout.current
    val gap = 12.dp
    Column(modifier.fillMaxSize().testTag("home_screen"), verticalArrangement = Arrangement.spacedBy(gap)) {
        // What a finished workout or a recording leaves to answer stays reachable when the treadmill is gone.
        FinishedWorkoutNotice(ui, actions)
        RecordingNotice(ui, actions)
        home.peakOffer?.let { PeakOfferCard(it, actions.onAcceptPeakOffer, actions.onDeclinePeakOffer) }
        if (layout.wide) {
            Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(gap)) {
                Column(Modifier.weight(1.1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(gap)) {
                    TrainCard(ui, actions, Modifier.weight(1.4f).fillMaxWidth())
                    ProgramsCard(actions, Modifier.weight(1f).fillMaxWidth())
                }
                Column(Modifier.weight(1f).fillMaxHeight(), verticalArrangement = Arrangement.spacedBy(gap)) {
                    RestingCard(home, actions, Modifier.weight(1f).fillMaxWidth())
                    WeightCard(home, actions, Modifier.weight(1f).fillMaxWidth())
                    HistoryCard(home, actions, Modifier.weight(1f).fillMaxWidth())
                }
            }
        } else {
            Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(gap)) {
                TrainCard(ui, actions, Modifier.fillMaxWidth().heightIn(min = 120.dp))
                ProgramsCard(actions, Modifier.fillMaxWidth())
                // Both cards as tall as the taller one.
                Row(Modifier.fillMaxWidth().height(IntrinsicSize.Min), horizontalArrangement = Arrangement.spacedBy(gap)) {
                    RestingCard(home, actions, Modifier.weight(1f).fillMaxHeight())
                    WeightCard(home, actions, Modifier.weight(1f).fillMaxHeight())
                }
                HistoryCard(home, actions, Modifier.fillMaxWidth())
            }
        }
    }
}

@Composable
private fun TrainCard(ui: MainUiState, actions: MainActions, modifier: Modifier) {
    val note = when (ui.treadmillIndicator) {
        TreadmillIndicator.Searching -> R.string.home_train_searching
        TreadmillIndicator.NotFound -> R.string.home_train_not_found
        TreadmillIndicator.BluetoothOff, TreadmillIndicator.WaitingForBluetooth -> R.string.home_train_bluetooth_off
        TreadmillIndicator.NotSetUp -> R.string.home_train_not_set_up
        else -> R.string.home_train_connect
    }
    HomeCard(
        title = stringResource(R.string.home_train),
        note = stringResource(note),
        tag = "home_train",
        onClick = actions.onOpenDevices,
        modifier = modifier,
        emphasized = true,
    )
}

@Composable
private fun ProgramsCard(actions: MainActions, modifier: Modifier) {
    HomeCard(
        title = stringResource(R.string.main_programs),
        note = stringResource(R.string.home_programs_note),
        tag = "home_programs",
        onClick = actions.onOpenPrograms,
        modifier = modifier,
    )
}

@Composable
private fun RestingCard(home: HomeUiState, actions: MainActions, modifier: Modifier) {
    val reference = home.restingReference
    HomeCard(
        title = stringResource(R.string.home_resting),
        value = reference?.bpm?.let { Format.whole(it) },
        unit = if (reference != null) stringResource(R.string.unit_heart_rate) else null,
        note = reference?.let { restingOriginText(it) } ?: stringResource(R.string.home_resting_none),
        tag = "home_resting",
        onClick = actions.onOpenResting,
        modifier = modifier,
    )
}

@Composable
private fun WeightCard(home: HomeUiState, actions: MainActions, modifier: Modifier) {
    val weight = home.latestWeight
    val real = weight?.takeIf { !it.isPlaceholder }
    HomeCard(
        title = stringResource(R.string.main_weight),
        value = real?.let { Format.weight(it.weightKg) },
        unit = if (real != null) stringResource(R.string.unit_kg) else null,
        note = real?.let { Format.date(it.date, currentLocale()) } ?: stringResource(R.string.home_weight_none),
        tag = "home_weight",
        onClick = actions.onOpenWeight,
        modifier = modifier,
    )
}

@Composable
private fun HistoryCard(home: HomeUiState, actions: MainActions, modifier: Modifier) {
    val workout = home.lastWorkout
    val date = workout?.let { Format.date(Instant.ofEpochMilli(it.startedAtMillis).atZone(ZoneId.systemDefault()).toLocalDate(), currentLocale()) }
    HomeCard(
        title = stringResource(R.string.main_history),
        value = date,
        note = workout?.let {
            stringResource(R.string.home_history_last, Format.duration(it.stats.durationSeconds), Format.distanceKm(it.stats.distanceMeters))
        } ?: stringResource(R.string.home_history_none),
        tag = "home_history",
        onClick = actions.onOpenHistory,
        modifier = modifier,
        valueSmall = true,
    )
}

/** A big tappable shortcut: title, an optional figure with its unit, and a line of explanation. */
@Composable
private fun HomeCard(
    title: String,
    tag: String,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    value: String? = null,
    unit: String? = null,
    note: String? = null,
    emphasized: Boolean = false,
    valueSmall: Boolean = false,
) {
    val wide = LocalLayout.current.wide
    val container = if (emphasized) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceContainer
    val border = if (emphasized) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
    Panel(modifier.clickable(role = Role.Button, onClick = onClick).testTag(tag), color = container, borderColor = border) {
        Column(
            Modifier.fillMaxSize().padding(horizontal = 18.dp, vertical = 14.dp),
            verticalArrangement = Arrangement.spacedBy(4.dp, Alignment.CenterVertically),
        ) {
            Text(
                title,
                style = when {
                    emphasized -> if (wide) MaterialTheme.typography.displaySmall else MaterialTheme.typography.headlineMedium
                    wide -> MaterialTheme.typography.headlineSmall
                    else -> MaterialTheme.typography.titleLarge
                },
                fontWeight = FontWeight.Bold,
                color = if (emphasized) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurface,
            )
            if (value != null) {
                Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(
                        value,
                        Modifier.testTag("${tag}_value"),
                        style = when {
                            valueSmall -> if (wide) MaterialTheme.typography.headlineMedium else MaterialTheme.typography.titleLarge
                            wide -> MaterialTheme.typography.displayMedium
                            else -> MaterialTheme.typography.displaySmall
                        },
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                    )
                    if (unit != null) {
                        Text(unit, Modifier.padding(bottom = 6.dp), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
            if (note != null) {
                Text(
                    note,
                    Modifier.testTag("${tag}_note"),
                    style = if (wide) MaterialTheme.typography.titleMedium else MaterialTheme.typography.bodyMedium,
                    color = if (emphasized) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 4,
                )
            }
        }
    }
}
