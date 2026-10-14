package com.treadz.console.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.treadz.console.R
import com.treadz.console.hrm.DiscoveredStrap
import com.treadz.console.programs.fitness.HeartRateSourceKind
import com.treadz.console.programs.fitness.ObservedPeakOffer
import com.treadz.console.userdata.ReferenceStatus
import com.treadz.console.userdata.RestingHeartRateReference

/** Words for where the heart rate comes from ("Strap" / "Console"). */
@Composable
fun HeartRateSourceKind.label(): String = stringResource(
    when (this) {
        HeartRateSourceKind.DirectStrap -> R.string.hr_source_strap
        HeartRateSourceKind.TreadmillConsole -> R.string.hr_source_console
    },
)

/**
 * Small icon of the heart-rate source, next to the heart rate: a heart for the strap connected to the app, a console
 * (screen on a stand) for the treadmill. The meaning is also in its description.
 */
@Composable
fun HeartRateSourceIcon(source: HeartRateSourceKind, modifier: Modifier = Modifier, size: Dp = 22.dp, tint: Color = MaterialTheme.colorScheme.onSurfaceVariant) {
    val description = source.label()
    Canvas(modifier.size(size).semantics { contentDescription = description }.testTag("hr_source_${source.name}")) {
        val w = this.size.width
        val h = this.size.height
        when (source) {
            HeartRateSourceKind.DirectStrap -> {
                val heart = Path().apply {
                    moveTo(w * 0.5f, h * 0.9f)
                    cubicTo(w * -0.05f, h * 0.55f, w * 0.05f, h * 0.05f, w * 0.5f, h * 0.30f)
                    cubicTo(w * 0.95f, h * 0.05f, w * 1.05f, h * 0.55f, w * 0.5f, h * 0.9f)
                    close()
                }
                drawPath(heart, tint)
            }
            HeartRateSourceKind.TreadmillConsole -> {
                val stroke = Stroke(width = w * 0.10f, cap = StrokeCap.Round)
                drawRoundRect(tint, Offset(w * 0.08f, h * 0.10f), Size(w * 0.84f, h * 0.52f), CornerRadius(w * 0.12f), style = stroke)
                drawLine(tint, Offset(w * 0.5f, h * 0.62f), Offset(w * 0.5f, h * 0.88f), strokeWidth = stroke.width, cap = StrokeCap.Round)
                drawLine(tint, Offset(w * 0.25f, h * 0.9f), Offset(w * 0.75f, h * 0.9f), strokeWidth = stroke.width, cap = StrokeCap.Round)
            }
        }
    }
}

/** Heart shape alone, for the strap indicator of the top bar. */
@Composable
fun HeartIcon(modifier: Modifier = Modifier, size: Dp = 18.dp, tint: Color) {
    Canvas(modifier.size(size)) {
        val w = this.size.width
        val h = this.size.height
        val heart = Path().apply {
            moveTo(w * 0.5f, h * 0.9f)
            cubicTo(w * -0.05f, h * 0.55f, w * 0.05f, h * 0.05f, w * 0.5f, h * 0.30f)
            cubicTo(w * 0.95f, h * 0.05f, w * 1.05f, h * 0.55f, w * 0.5f, h * 0.9f)
            close()
        }
        drawPath(heart, tint)
    }
}

/** "Use this strap (HRM-1234)?": the one-time, non-blocking question about the first strap seen. */
@Composable
fun StrapOfferCard(offer: DiscoveredStrap, onYes: () -> Unit, onNo: () -> Unit, modifier: Modifier = Modifier) {
    val compact = LocalLayout.current.compact
    val name = offer.name ?: stringResource(R.string.strap_unnamed)
    Panel(modifier.fillMaxWidth().testTag("strap_offer"), color = MaterialTheme.colorScheme.surfaceContainerHigh, borderColor = MaterialTheme.colorScheme.primary) {
        @Composable
        fun title(modifier: Modifier) = Column(modifier) {
            Text(stringResource(R.string.strap_offer_title, name), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
            if (!compact) Text(stringResource(R.string.strap_offer_body), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        if (compact) {
            // A phone: the question on its own line, the answers below it.
            Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    HeartIcon(tint = MaterialTheme.colorScheme.primary, size = 28.dp)
                    title(Modifier.weight(1f))
                }
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(10.dp, Alignment.End)) {
                    RowButton(stringResource(R.string.action_no), onNo, Modifier.testTag("strap_offer_no"))
                    RowButton(stringResource(R.string.action_yes), onYes, Modifier.testTag("strap_offer_yes"))
                }
            }
        } else {
            Row(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                HeartIcon(tint = MaterialTheme.colorScheme.primary, size = 28.dp)
                title(Modifier.weight(1f))
                RowButton(stringResource(R.string.action_no), onNo, Modifier.testTag("strap_offer_no"))
                RowButton(stringResource(R.string.action_yes), onYes, Modifier.testTag("strap_offer_yes"))
            }
        }
    }
}

/** "Your heart rate reached X bpm, above your maximum Y. Use X as your maximum?" */
@Composable
fun PeakOfferCard(offer: ObservedPeakOffer, onYes: () -> Unit, onNo: () -> Unit, modifier: Modifier = Modifier) {
    Panel(modifier.fillMaxWidth().testTag("peak_offer"), color = MaterialTheme.colorScheme.surfaceContainerHigh, borderColor = MaterialTheme.extra.warning) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            Text(stringResource(R.string.peak_offer_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, color = MaterialTheme.extra.warning)
            Text(stringResource(R.string.peak_offer_body, offer.peakBpm, offer.currentBpm), Modifier.testTag("peak_offer_text"), style = MaterialTheme.typography.bodyLarge)
            Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                RowButton(stringResource(R.string.peak_offer_yes, offer.peakBpm), onYes, Modifier.testTag("peak_offer_yes"))
                RowButton(stringResource(R.string.action_no), onNo, Modifier.testTag("peak_offer_no"))
            }
        }
    }
}

/** Where the resting heart rate in use comes from, in one line: the median of N measurements, provisional or old. */
@Composable
fun restingOriginText(reference: RestingHeartRateReference): String = when (reference.status) {
    ReferenceStatus.Established -> stringResource(R.string.resting_origin_established, reference.measurementsCount)
    ReferenceStatus.Provisional -> stringResource(R.string.resting_origin_provisional, reference.measurementsCount)
    ReferenceStatus.Stale ->
        stringResource(R.string.resting_origin_stale, Format.date(java.time.Instant.ofEpochMilli(reference.latestAtMillis).atZone(java.time.ZoneId.systemDefault()).toLocalDate(), currentLocale()))
}

/** The same, with the consequence for the user (what makes the reference firm, what to do when it is old). */
@Composable
fun restingStatusExplanation(reference: RestingHeartRateReference): String = when (reference.status) {
    ReferenceStatus.Established -> stringResource(R.string.resting_status_established)
    ReferenceStatus.Provisional -> stringResource(R.string.resting_status_provisional)
    ReferenceStatus.Stale -> stringResource(R.string.resting_status_stale)
}
