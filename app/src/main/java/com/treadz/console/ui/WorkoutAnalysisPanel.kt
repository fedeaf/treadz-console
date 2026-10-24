package com.treadz.console.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
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
import com.treadz.console.analysis.workout.RecoveryAnalysis
import com.treadz.console.analysis.workout.RecoveryContext
import com.treadz.console.analysis.workout.SessionAlpha1
import com.treadz.console.analysis.workout.SignalQuality
import com.treadz.console.analysis.workout.ZoneTimeAnalysis
import com.treadz.console.history.AnalysisNote
import com.treadz.console.history.WorkoutAnalysisView
import kotlin.math.roundToInt

/**
 * The metrics of one workout (energy, time in zones, load, efficiency, drift, fitness index, recovery, α1, signal quality).
 * Every figure sits with what qualifies it; a metric that does not apply is simply absent and one that is missing because
 * of the data is replaced by a single passive line. The calories are the app's estimate from the speed, the incline and
 * the weight of that day (for workouts recorded since they are stored that way, the same as the workout's total).
 */
@Composable
fun WorkoutAnalysisPanel(view: WorkoutAnalysisView, modifier: Modifier = Modifier) {
    Panel(modifier.fillMaxWidth().testTag("analysis_panel")) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            SectionLabel(stringResource(R.string.analysis_title))
            view.energy?.let { energy ->
                Heading(stringResource(R.string.analysis_energy_title))
                energy.netKcal?.let { FactRow(stringResource(R.string.analysis_kcal_net), "${Format.calories(it.roundToInt())} ${stringResource(R.string.unit_calories)}", tag = "analysis_kcal_net") }
                energy.grossKcal?.let { FactRow(stringResource(R.string.analysis_kcal_gross), "${Format.calories(it.roundToInt())} ${stringResource(R.string.unit_calories)}", tag = "analysis_kcal_gross") }
                FactRow(stringResource(R.string.analysis_mets), stringResource(R.string.analysis_mets_value, Format.number("%.1f", energy.meanMets)), tag = "analysis_mets")
                FactRow(stringResource(R.string.analysis_vo2), stringResource(R.string.value_vo2max, Format.number("%.1f", energy.meanVo2MlKgMin)), tag = "analysis_vo2")
                energy.percentOfVo2max?.let { FactRow(stringResource(R.string.analysis_pct_vo2max), stringResource(R.string.value_percent, Format.whole(it.roundToInt())), tag = "analysis_pct_vo2max") }
                Note(stringResource(R.string.analysis_energy_note), null)
            }
            view.heartRateNotes.forEach { note -> Note(note.text(view.signal), "analysis_note_${note.name}") }
            view.zoneTime?.let { ZoneBars(it) }
            view.trimp?.let { trimp ->
                FactRow(stringResource(R.string.analysis_trimp), Format.whole(trimp.trimp.roundToInt()), tag = "analysis_trimp")
                if (trimp.restingAssumed) Note(stringResource(R.string.analysis_trimp_assumed), "analysis_trimp_assumed")
            }
            view.efficiency?.let { ef ->
                FactRow(stringResource(R.string.analysis_efficiency), Format.number("%.2f", ef.efficiencyFactor), tag = "analysis_efficiency")
                Note(stringResource(R.string.analysis_efficiency_note), null)
            }
            view.fitnessIndex?.let { fi ->
                FactRow(stringResource(R.string.analysis_fitness), stringResource(R.string.value_vo2max, Format.weight(fi.vo2maxEstimate)), tag = "analysis_fitness")
                Note(stringResource(R.string.analysis_fitness_note, fi.stretchCount) + if (fi.restingAssumed) " " + stringResource(R.string.analysis_fitness_rest_assumed) else "", "analysis_fitness_note")
            }
            view.recovery?.let { Recovery(it) }
            val alpha1 = view.alpha1
            if (alpha1 != null) {
                Alpha1(alpha1)
            } else {
                view.alpha1Note?.let { Note(it.text(view.signal), "analysis_note_${it.name}") }
            }
            Signal(view.signal)
        }
    }
}

@Composable
private fun Heading(text: String) {
    Text(text, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun Note(text: String, tag: String?) {
    Text(text, if (tag != null) Modifier.testTag(tag) else Modifier, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
}

@Composable
private fun AnalysisNote.text(signal: SignalQuality): String = when (this) {
    AnalysisNote.NoHeartRate -> stringResource(R.string.analysis_note_no_hr)
    AnalysisNote.LowHeartRateCoverage ->
        stringResource(R.string.analysis_note_low_coverage, stringResource(R.string.value_percent, Format.whole(((signal.heartRateCoverage ?: 0.0) * 100).roundToInt())))
    AnalysisNote.InvalidMaxHeartRate -> stringResource(R.string.analysis_note_invalid_max)
    AnalysisNote.NoRrIntervals -> stringResource(R.string.analysis_note_no_rr)
    AnalysisNote.NoValidWindows -> stringResource(R.string.analysis_note_no_windows)
}

/** Time in each zone of that day, as bars; the zones are the ones the analysis used. */
@Composable
private fun ZoneBars(zoneTime: ZoneTimeAnalysis) {
    Column(Modifier.fillMaxWidth().testTag("analysis_zones"), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Heading(stringResource(R.string.analysis_zones_title))
        Note(zoneTime.zones.source.label(), "analysis_zones_source")
        val total = zoneTime.analysedSeconds.coerceAtLeast(1)
        zoneTime.zones.zones.forEachIndexed { i, zone ->
            val seconds = zoneTime.secondsInZone.getOrElse(i) { 0 }
            val share = seconds.toDouble() / total
            Column(Modifier.fillMaxWidth().testTag("analysis_zone_${zone.number}"), verticalArrangement = Arrangement.spacedBy(3.dp)) {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    StatusDot(zoneColor(zone.number), size = 12.dp)
                    Text(stringResource(R.string.zone_name, zone.number), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                    Text(zone.text(), Modifier.weight(1f), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
                    Text(
                        stringResource(R.string.analysis_time_share, Format.duration(seconds), stringResource(R.string.value_percent, Format.whole((share * 100).roundToInt()))),
                        Modifier.testTag("analysis_zone_${zone.number}_time"),
                        style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold,
                    )
                }
                Box(Modifier.fillMaxWidth().height(8.dp).background(MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(50))) {
                    if (share > 0.0) Box(Modifier.fillMaxWidth(share.toFloat().coerceIn(0.02f, 1f)).fillMaxHeight().background(zoneColor(zone.number), RoundedCornerShape(50)))
                }
            }
        }
    }
}

@Composable
private fun Recovery(recovery: RecoveryAnalysis) {
    val unit = stringResource(R.string.unit_heart_rate)
    Column(Modifier.fillMaxWidth().testTag("analysis_recovery"), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
            Text(stringResource(R.string.analysis_recovery), Modifier.weight(1f, fill = false), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Tag(
                stringResource(if (recovery.context == RecoveryContext.BeltStopped) R.string.perf_context_stopped else R.string.perf_context_moving),
                MaterialTheme.colorScheme.secondary, Modifier.testTag("analysis_recovery_context"),
            )
        }
        FactRow(stringResource(R.string.perf_recovery_1min), recovery.drop60Bpm?.let { "${Format.whole(it.roundToInt())} $unit" } ?: Format.NO_VALUE, tag = "analysis_recovery_60")
        FactRow(stringResource(R.string.perf_recovery_2min), recovery.drop120Bpm?.let { "${Format.whole(it.roundToInt())} $unit" } ?: Format.NO_VALUE, tag = "analysis_recovery_120")
    }
}

/**
 * α1 ≥ 0.75 is an effort below the aerobic threshold (VT1), below 0.75 at or above it: the model's `secondsAtOrAbove` counts
 * the α1 at or above the cut, which is the time **below** the threshold.
 */
@Composable
private fun Alpha1(alpha1: SessionAlpha1) {
    val total = (alpha1.secondsAtOrAbove + alpha1.secondsBelow).coerceAtLeast(1)
    @Composable fun share(seconds: Int) = stringResource(R.string.value_percent, Format.whole((seconds * 100.0 / total).roundToInt()))
    Column(Modifier.fillMaxWidth().testTag("analysis_alpha1"), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        FactRow(
            stringResource(R.string.analysis_alpha1_below),
            stringResource(R.string.analysis_time_share, Format.duration(alpha1.secondsAtOrAbove), share(alpha1.secondsAtOrAbove)),
            tag = "analysis_alpha1_below",
        )
        FactRow(
            stringResource(R.string.analysis_alpha1_above),
            stringResource(R.string.analysis_time_share, Format.duration(alpha1.secondsBelow), share(alpha1.secondsBelow)),
            tag = "analysis_alpha1_above",
        )
        FactRow(stringResource(R.string.analysis_alpha1_mean), Format.number("%.2f", alpha1.meanAlpha1), tag = "analysis_alpha1_mean")
        Note(stringResource(R.string.analysis_alpha1_help), null)
    }
}

@Composable
private fun Signal(signal: SignalQuality) {
    Column(Modifier.fillMaxWidth().testTag("analysis_signal"), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Heading(stringResource(R.string.analysis_signal_title))
        signal.heartRateCoverage?.let {
            FactRow(stringResource(R.string.analysis_coverage), stringResource(R.string.value_percent, Format.whole((it * 100).roundToInt())), tag = "analysis_coverage")
        }
        val sources = listOfNotNull(
            signal.heartRateSources.strapSeconds.takeIf { it > 0 }?.let { stringResource(R.string.analysis_source_strap, Format.duration(it)) },
            signal.heartRateSources.consoleSeconds.takeIf { it > 0 }?.let { stringResource(R.string.analysis_source_console, Format.duration(it)) },
            signal.heartRateSources.unknownSeconds.takeIf { it > 0 }?.let { stringResource(R.string.analysis_source_unknown, Format.duration(it)) },
        )
        if (sources.isNotEmpty()) FactRow(stringResource(R.string.analysis_hr_source), sources.joinToString(" · "), tag = "analysis_sources")
        signal.rrArtifactPercent?.let {
            FactRow(stringResource(R.string.analysis_rr_artifacts), stringResource(R.string.value_percent, Format.number("%.1f", it)), tag = "analysis_artifacts")
        }
        if (signal.frozenHeartRateSeconds > 0) FactRow(stringResource(R.string.analysis_frozen), Format.duration(signal.frozenHeartRateSeconds), tag = "analysis_frozen")
        if (signal.gapSeconds > 0) FactRow(stringResource(R.string.analysis_gaps), Format.duration(signal.gapSeconds), tag = "analysis_gaps")
    }
}
