package com.treadz.console.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.treadz.console.R
import com.treadz.console.home.HeartRateMaxKind
import com.treadz.console.home.HeartRateMaxRow
import com.treadz.console.home.HeartRateMaxUiState
import com.treadz.console.programs.fitness.HeartRateMaxDecision
import com.treadz.console.programs.fitness.HeartRateMaxOrigin
import java.time.Instant
import java.time.ZoneId

data class HeartRateMaxActions(
    val onBack: () -> Unit = {},
    val onAcceptOffer: () -> Unit = {},
    val onDeclineOffer: () -> Unit = {},
)

/** Where the origin of a maximum heart rate is written in one line ("Entered by you", "Measured in the test of …"). */
@Composable
fun hrMaxOriginText(decision: HeartRateMaxDecision): String = when (decision.origin) {
    HeartRateMaxOrigin.Manual -> stringResource(R.string.hrmax_origin_line_manual)
    HeartRateMaxOrigin.Measured -> {
        val at = decision.measured?.testedAtMillis
        if (at != null) stringResource(R.string.hrmax_origin_line_measured, Format.date(dateOf(at), currentLocale())) else stringResource(R.string.hrmax_origin_line_measured_undated)
    }
    HeartRateMaxOrigin.Estimated -> stringResource(R.string.hrmax_origin_line_estimated)
}

private fun dateOf(epochMillis: Long) = Instant.ofEpochMilli(epochMillis).atZone(ZoneId.systemDefault()).toLocalDate()

/**
 * The maximum heart rate of a user: the value in use and where it comes from, every measurement it could come from with
 * the one in use marked, and the offer of an observed peak.
 */
@Composable
fun HeartRateMaxScreen(state: HeartRateMaxUiState, actions: HeartRateMaxActions, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxSize().testTag("hrmax_screen"), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ScreenHeader(title = stringResource(R.string.hrmax_title), subtitle = state.user?.displayName(), onBack = actions.onBack)
        val decision = state.decision
        if (state.loaded && (state.user == null || decision == null)) {
            Text(stringResource(R.string.editor_not_found), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.titleMedium)
            return@Column
        }
        if (decision == null) return@Column
        Column(
            Modifier.weight(1f).fillMaxWidth().widthIn(max = 720.dp).verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Panel(Modifier.fillMaxWidth().testTag("hrmax_current")) {
                Column(Modifier.padding(horizontal = 18.dp, vertical = 14.dp), verticalArrangement = Arrangement.spacedBy(2.dp)) {
                    Text(stringResource(R.string.hrmax_in_use), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    Row(verticalAlignment = Alignment.Bottom, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(Format.whole(decision.bpm), Modifier.testTag("hrmax_current_value"), style = MaterialTheme.typography.displayMedium, fontWeight = FontWeight.Bold)
                        Text(stringResource(R.string.unit_heart_rate), Modifier.padding(bottom = 8.dp), style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    Text(hrMaxOriginText(decision), Modifier.testTag("hrmax_current_origin"), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold)
                }
            }
            state.offer?.let { PeakOfferCard(it, actions.onAcceptOffer, actions.onDeclineOffer) }
            SectionLabel(stringResource(R.string.hrmax_measurements))
            state.rows.forEach { row -> MaxRow(row) }
            Text(stringResource(R.string.hrmax_footnote), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

@Composable
private fun MaxRow(row: HeartRateMaxRow) {
    val (title, detail) = when (val kind = row.kind) {
        HeartRateMaxKind.Manual -> stringResource(R.string.hrmax_row_manual) to row.atMillis?.let { stringResource(R.string.hrmax_row_set_on, Format.date(dateOf(it), currentLocale())) }
        is HeartRateMaxKind.Test -> stringResource(R.string.hrmax_row_test, kind.type.label()) to row.atMillis?.let { Format.date(dateOf(it), currentLocale()) }
        is HeartRateMaxKind.ObservedPeak -> stringResource(R.string.hrmax_row_peak) to row.atMillis?.let {
            Format.date(dateOf(it), currentLocale()) + (kind.source?.let { s -> " · " + s.label() } ?: "")
        }
        HeartRateMaxKind.AgeEstimate -> stringResource(R.string.hrmax_row_estimate) to null
    }
    Panel(
        Modifier.fillMaxWidth().testTag(rowTag(row)),
        borderColor = if (row.inUse) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant,
    ) {
        Row(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Column(Modifier.weight(1f)) {
                Text(title, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
                if (detail != null) Text(detail, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                if (row.inUse) Tag(stringResource(R.string.hrmax_in_use_tag), MaterialTheme.colorScheme.primary, Modifier.padding(top = 4.dp).testTag("hrmax_in_use_tag"))
            }
            Text("${Format.whole(row.bpm)} ${stringResource(R.string.unit_heart_rate)}", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
        }
    }
}

private fun rowTag(row: HeartRateMaxRow): String = when (val kind = row.kind) {
    HeartRateMaxKind.Manual -> "hrmax_row_manual"
    is HeartRateMaxKind.Test -> "hrmax_row_test_${kind.testId}"
    is HeartRateMaxKind.ObservedPeak -> "hrmax_row_peak"
    HeartRateMaxKind.AgeEstimate -> "hrmax_row_estimate"
}
