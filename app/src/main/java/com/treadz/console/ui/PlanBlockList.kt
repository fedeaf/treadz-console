package com.treadz.console.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
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
import com.treadz.console.programs.heartrate.HeartRateProfile
import com.treadz.console.programs.heartrate.HeartRateZones
import com.treadz.console.programs.model.BlockEnd
import com.treadz.console.programs.model.HeartRateBasis
import com.treadz.console.programs.plan.ResolvedBlock
import com.treadz.console.programs.plan.ResolvedPlan
import com.treadz.console.training.PlanRow
import com.treadz.console.training.positionKey
import com.treadz.console.training.rows

/**
 * The blocks of a plan with their starting values; a block guided by heart rate also shows its zone in bpm for the user
 * (or as a % when the user's maximum is not known) and what the control adjusts. Repeats are shown once, with their count.
 */
@Composable
fun PlanBlockList(plan: ResolvedPlan, heartRate: HeartRateProfile?, modifier: Modifier = Modifier, fromHistory: Set<String> = emptySet()) {
    Column(modifier.fillMaxWidth().testTag("plan_block_list"), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        plan.rows().forEach { row ->
            when (row) {
                is PlanRow.Single -> BlockLine(row.block, heartRate, row.block.positionKey in fromHistory)
                is PlanRow.Group -> Panel(Modifier.fillMaxWidth().testTag("plan_group_${row.blocks.first().stepIndex}"), color = MaterialTheme.colorScheme.surfaceContainerHigh) {
                    Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(R.string.block_list_repeat, row.count), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.secondary)
                        row.blocks.forEach { BlockLine(it, heartRate, it.positionKey in fromHistory) }
                    }
                }
            }
        }
    }
}

@Composable
private fun BlockLine(block: ResolvedBlock, profile: HeartRateProfile?, fromHistory: Boolean) {
    val guidance = block.heartRate
    Row(Modifier.fillMaxWidth().testTag("plan_block_${block.index}"), verticalAlignment = Alignment.Top, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        PhaseTag(block.phase, Modifier.padding(top = 1.dp))
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(2.dp)) {
            val length = when (val e = block.end) {
                is BlockEnd.Duration -> Format.duration(e.seconds)
                is BlockEnd.Distance -> stringResource(R.string.value_km, Format.distanceKm(e.meters))
            }
            Text(
                length + (block.name?.let { " · $it" } ?: ""),
                style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.SemiBold,
            )
            if (guidance == null) {
                Text(
                    plannedTargetText(block),
                    Modifier.testTag("plan_block_target_${block.index}"),
                    style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            } else {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                    HeartIcon(size = 14.dp, tint = MaterialTheme.colorScheme.error)
                    Text(zoneText(guidance.zone, profile), Modifier.testTag("plan_block_zone_${block.index}"), style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                }
                Text(
                    stringResource(
                        R.string.block_list_hr_start,
                        if (block.keepsSpeed) stringResource(R.string.hr_start_speed_kept, Format.speed(block.speedKmh)) else stringResource(R.string.value_kmh, Format.speed(block.speedKmh)),
                        if (block.keepsIncline) stringResource(R.string.hr_start_incline_kept, Format.incline(block.inclinePercent)) else stringResource(R.string.value_percent, Format.incline(block.inclinePercent)),
                        guidance.adjust.label(),
                        adjustedValueText(guidance.adjust, guidance.minimum), adjustedValueText(guidance.adjust, guidance.maximum),
                    ),
                    style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                if (fromHistory) {
                    Text(stringResource(R.string.block_list_from_history), Modifier.testTag("plan_block_history_${block.index}"), style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.secondary)
                }
            }
        }
    }
}

/** "8.0 km/h · 2 %": what a block asks for now (the engine's real targets during a run). */
@Composable
fun targetText(speedKmh: Double, inclinePercent: Double): String =
    stringResource(R.string.block_target, Format.speed(speedKmh), Format.incline(inclinePercent))

/**
 * What a block of a plan asks for, with "keep" for a value it keeps instead of presenting the expected number as planned:
 * the real one is whatever is in force when the block starts. With [approximate] the expected value follows, marked as
 * approximate ("keep speed (≈ 8.0 km/h) · 2 %").
 */
@Composable
fun plannedTargetText(block: ResolvedBlock, approximate: Boolean = true): String {
    val speed = stringResource(R.string.value_kmh, Format.speed(block.speedKmh))
    val incline = stringResource(R.string.value_percent, Format.incline(block.inclinePercent))
    @Composable
    fun kept(keep: Int, expected: String) =
        if (approximate) stringResource(R.string.target_keep_approx, stringResource(keep), expected) else stringResource(keep)
    return stringResource(
        R.string.target_pair,
        if (block.keepsSpeed) kept(R.string.target_keep_speed, speed) else speed,
        if (block.keepsIncline) kept(R.string.target_keep_incline, incline) else incline,
    )
}

/** "Heart rate 118–134 bpm", or the percentages when the user's maximum is not known. */
@Composable
fun zoneText(zone: com.treadz.console.programs.model.HeartRateZone, profile: HeartRateProfile?): String =
    if (profile != null && profile.isUsable) {
        val bpm = HeartRateZones.toBpm(zone, profile)
        stringResource(R.string.block_list_hr_zone_bpm, bpm.lowBpm, bpm.highBpm)
    } else {
        stringResource(
            if (zone.basis == HeartRateBasis.HeartRateReserve) R.string.block_list_hr_zone_reserve else R.string.block_list_hr_zone_max,
            zone.lowPercent, zone.highPercent,
        )
    }
