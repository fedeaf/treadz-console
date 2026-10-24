package com.treadz.console.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.treadz.console.R
import com.treadz.console.analysis.thresholds.ThresholdPoint
import com.treadz.console.analysis.thresholds.ThresholdQuality
import com.treadz.console.analysis.thresholds.ThresholdReason
import com.treadz.console.analysis.trends.RecommendationDirection
import com.treadz.console.analysis.trends.ThresholdRecommendation
import com.treadz.console.analysis.workout.ThresholdKind
import com.treadz.console.analysis.zones.ZoneRange
import com.treadz.console.analysis.zones.ZoneSet
import com.treadz.console.analysis.zones.ZoneSource
import com.treadz.console.programs.fitness.FitnessTestResult
import com.treadz.console.programs.fitness.FitnessTestType
import com.treadz.console.programs.fitness.ThresholdEstimate
import com.treadz.console.programs.fitness.Validity

// Thresholds (VT1 / VT2) and heart-rate zones. Everything here is passive: a figure with the quality it carries, or the
// reason there is none. Nothing asks the user anything.

@Composable
fun ThresholdQuality.label(): String = stringResource(
    when (this) {
        ThresholdQuality.High -> R.string.threshold_quality_high
        ThresholdQuality.Medium -> R.string.threshold_quality_medium
        ThresholdQuality.Low -> R.string.threshold_quality_low
    },
)

/** Green for a high quality, blue for a medium one, the warning colour for one that does not count. */
@Composable
fun ThresholdQuality.color(): Color = when (this) {
    ThresholdQuality.High -> MaterialTheme.extra.ok
    ThresholdQuality.Medium -> MaterialTheme.colorScheme.secondary
    ThresholdQuality.Low -> MaterialTheme.extra.warning
}

@Composable
fun ThresholdReason.text(): String = stringResource(
    when (this) {
        ThresholdReason.NoRrIntervals -> R.string.threshold_reason_no_rr
        ThresholdReason.NoWorkoutSamples -> R.string.threshold_reason_no_samples
        ThresholdReason.NotEnoughValidWindows -> R.string.threshold_reason_few_windows
        ThresholdReason.NoAlpha1Decline -> R.string.threshold_reason_no_decline
        ThresholdReason.Vt1OutOfRange -> R.string.threshold_reason_vt1_out
    },
)

/**
 * VT1 and VT2 of a maximal test (heart rate, load, VO2 and share of the VO2max and of the reserve at each) with the
 * quality of the estimate, or the reason there are none. Nothing for Ebbeling (it has no thresholds), for an invalid
 * result or for one that has not been analysed yet.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ThresholdsPanel(result: FitnessTestResult, modifier: Modifier = Modifier) {
    if (result.validity != Validity.Valid || result.testType == FitnessTestType.EbbelingWalk) return
    val thresholds = result.thresholds ?: return
    val vt1 = thresholds.vt1
    val quality = thresholds.quality
    Panel(modifier.fillMaxWidth().testTag("result_thresholds")) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            FlowRow(horizontalArrangement = Arrangement.spacedBy(10.dp), verticalArrangement = Arrangement.spacedBy(4.dp), itemVerticalAlignment = Alignment.CenterVertically) {
                SectionLabel(stringResource(R.string.result_thresholds_title))
                if (vt1 != null && quality != null) Tag(quality.label(), quality.color(), Modifier.testTag("thresholds_quality"))
            }
            if (vt1 == null) {
                val reason = thresholds.reason
                if (reason != null) {
                    Text(reason.text(), Modifier.testTag("thresholds_reason"), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            } else {
                ThresholdRow(stringResource(R.string.result_vt1), vt1, "vt1")
                val vt2 = thresholds.vt2
                if (vt2 != null) {
                    ThresholdRow(stringResource(R.string.result_vt2), vt2, "vt2")
                } else {
                    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
                        Text(stringResource(R.string.result_vt2), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
                        Text(stringResource(R.string.result_vt2_not_reached), Modifier.testTag("thresholds_vt2_missing"), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
                if (quality == ThresholdQuality.Low) {
                    Text(stringResource(R.string.result_thresholds_low_note), Modifier.testTag("thresholds_low_note"), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.extra.warning)
                }
            }
        }
    }
}

/** One threshold: its heart rate big, and below it the load, the VO2 and the shares of VO2max and reserve that are known. */
@Composable
private fun ThresholdRow(label: String, point: ThresholdPoint, tag: String) {
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp), verticalAlignment = Alignment.Bottom) {
            Text(label, Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
            Text(
                stringResource(R.string.value_bpm, Format.whole(Math.round(point.heartRateBpm).toInt())),
                Modifier.testTag("threshold_${tag}_value"),
                style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold,
            )
        }
        val speed = point.speedKmh
        val incline = point.inclinePercent
        val details = listOfNotNull(
            if (speed != null && incline != null) stringResource(R.string.result_threshold_load, Format.speed(speed), Format.incline(incline)) else null,
            point.vo2MlKgMin?.let { stringResource(R.string.result_threshold_vo2, Format.weight(it)) },
            point.percentVo2max?.let { stringResource(R.string.result_threshold_pct_vo2max, Math.round(it).toInt()) },
            point.percentHeartRateReserve?.let { stringResource(R.string.result_threshold_pct_reserve, Math.round(it).toInt()) },
        )
        if (details.isNotEmpty()) {
            Text(
                details.joinToString(" · "), Modifier.fillMaxWidth().testTag("threshold_${tag}_detail"),
                style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.End,
            )
        }
    }
}

/** The thresholds of the profile (combined over the user's tests): the heart rate, with the quality of the estimate under it. */
@Composable
fun ProfileThresholdRows(vt1: ThresholdEstimate?, vt2: ThresholdEstimate?) {
    vt1?.let { ProfileThresholdRow(stringResource(R.string.result_vt1), it, "summary_vt1") }
    vt2?.let { ProfileThresholdRow(stringResource(R.string.result_vt2), it, "summary_vt2") }
}

@Composable
private fun ProfileThresholdRow(label: String, estimate: ThresholdEstimate, tag: String) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(0.dp)) {
        FactRow(label, stringResource(R.string.value_bpm, estimate.heartRateBpm), tag = tag)
        Text(
            estimate.quality.label(), Modifier.fillMaxWidth().testTag("${tag}_quality"),
            style = MaterialTheme.typography.bodySmall, color = estimate.quality.color(), textAlign = TextAlign.End,
        )
    }
}

// ---------------------------------------------------------------------------------------------------------------
// Recommendation of a test

/** "In your last 4 workouts VT1 looks about 8 bpm higher: want to take a test?" */
@Composable
fun ThresholdRecommendation.text(): String = stringResource(
    if (direction == RecommendationDirection.Higher) R.string.test_reco_higher else R.string.test_reco_lower,
    workouts,
    stringResource(if (kind == ThresholdKind.Vt1) R.string.perf_vt1_short else R.string.perf_vt2_short),
    differenceBpm,
)

/**
 * What the user's workouts suggest about their thresholds: a test is recommended, nothing changes by itself. It cannot be
 * dismissed and goes when a new test is done. Nothing when there is no recommendation.
 */
@Composable
fun TestRecommendationNotes(recommendations: List<ThresholdRecommendation>, modifier: Modifier = Modifier) {
    if (recommendations.isEmpty()) return
    Column(modifier.fillMaxWidth().testTag("test_recommendations"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        recommendations.forEach { r ->
            Panel(Modifier.fillMaxWidth().testTag("test_recommendation_${r.kind.name.lowercase()}"), borderColor = MaterialTheme.colorScheme.secondary) {
                Row(Modifier.padding(horizontal = 14.dp, vertical = 10.dp), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    StatusDot(MaterialTheme.colorScheme.secondary, size = 12.dp)
                    Text(r.text(), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold)
                }
            }
        }
    }
}

// ---------------------------------------------------------------------------------------------------------------
// Zones

@Composable
fun ZoneSource.label(): String = stringResource(
    when (this) {
        ZoneSource.MeasuredThresholds -> R.string.zone_source_thresholds
        ZoneSource.Vo2maxClassification -> R.string.zone_source_vo2max
        ZoneSource.SelfRatedFitness -> R.string.zone_source_level
        ZoneSource.Default -> R.string.zone_source_default
    },
)

@Composable
internal fun zoneColor(number: Int): Color = when (number) {
    1 -> MaterialTheme.colorScheme.onSurfaceVariant
    2 -> MaterialTheme.colorScheme.secondary
    3 -> MaterialTheme.extra.ok
    4 -> MaterialTheme.extra.warning
    else -> MaterialTheme.colorScheme.error
}

@Composable
internal fun ZoneRange.text(): String =
    lowBpm.let { low -> if (low == null) stringResource(R.string.zone_below_bpm, highBpm) else stringResource(R.string.zone_range_bpm, low, highBpm) }

/** The five zones in bpm with where they come from (and, with measured thresholds, that VT2 is only typical when it is). */
@Composable
fun ZoneList(zones: ZoneSet, modifier: Modifier = Modifier) {
    Column(modifier.fillMaxWidth().testTag("profile_zones"), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        Text(stringResource(R.string.profile_zones_title), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(zones.source.label(), Modifier.testTag("zones_source"), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        zones.zones.forEach { zone ->
            Row(Modifier.fillMaxWidth().testTag("zone_${zone.number}"), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                StatusDot(zoneColor(zone.number), size = 12.dp)
                Text(stringResource(R.string.zone_name, zone.number), Modifier.weight(1f), style = MaterialTheme.typography.bodyLarge)
                Text(zone.text(), Modifier.testTag("zone_${zone.number}_range").width(150.dp), style = MaterialTheme.typography.bodyLarge, fontWeight = FontWeight.SemiBold, textAlign = TextAlign.End)
            }
        }
        if (zones.source == ZoneSource.MeasuredThresholds && zones.vt2FromTable) {
            Text(stringResource(R.string.zone_vt2_typical), Modifier.testTag("zones_vt2_typical"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
}

/**
 * Where the heart-rate zone of a generated program came from, read from the inputs the generator wrote in the program's
 * origin. Nothing when the generator does not use the zone model (or the program is older than the model).
 */
@Composable
fun GeneratedZoneOrigin(inputs: Map<String, String>, modifier: Modifier = Modifier) {
    val source = inputs["zoneSource"] ?: return
    val vt1 = inputs["vt1Bpm"]?.toIntOrNull()
    val vt2 = inputs["vt2Bpm"]?.toIntOrNull()
    val text = when (source) {
        "measuredThresholds" -> when {
            vt1 != null && vt2 != null && inputs["vt2FromTable"] == "true" -> stringResource(R.string.generated_zone_thresholds_vt2_typical, vt1, vt2)
            vt1 != null && vt2 != null -> stringResource(R.string.generated_zone_thresholds, vt1, vt2)
            else -> stringResource(R.string.zone_source_thresholds)
        }
        "vo2maxClassification" -> stringResource(R.string.generated_zone_vo2max)
        "selfRatedFitness" -> stringResource(R.string.generated_zone_level)
        else -> stringResource(R.string.generated_zone_default)
    }
    Text(text, modifier.fillMaxWidth().testTag("generated_zone_origin"), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
}
