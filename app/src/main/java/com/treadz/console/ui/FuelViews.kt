package com.treadz.console.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.treadz.console.R
import com.treadz.console.fuel.FuelDetail
import com.treadz.console.fuel.FuelFigures
import com.treadz.console.fuel.FuelMinuteBar

// ---------------------------------------------------------------------------------------------------------------
// Texts of the estimated fat/carbohydrate split. The figures come from FuelPresenter: they already add up to the calories
// of the screen that shows them.

/** «38 % · 62 %». */
@Composable
fun FuelFigures.percentsText(): String = stringResource(R.string.fuel_percents, Format.whole(fatPercent), Format.whole(carbPercent))

/** «120 · 196 kcal». */
@Composable
fun FuelFigures.kcalText(): String = stringResource(R.string.fuel_kcal_note, Format.calories(fatKcal), Format.calories(carbKcal))

/** «Fat 290 kcal (45 %) · Carbs 352 kcal (55 %)»: the end of a workout, and the description of the bar. */
@Composable
fun FuelFigures.summaryText(): String =
    stringResource(R.string.fuel_summary, Format.calories(fatKcal), Format.whole(fatPercent), Format.calories(carbKcal), Format.whole(carbPercent))

/** The end-of-workout line (program summary, notice after a free workout). */
@Composable
fun FuelEndLine(figures: FuelFigures, modifier: Modifier = Modifier, style: TextStyle = MaterialTheme.typography.bodyMedium) {
    Text(figures.summaryText(), modifier.testTag("fuel_summary"), style = style)
}

/**
 * The split on the phone, as one compact line under the figures of [MetricStrip]: «Fat · Carbs  38 % · 62 %  120 · 196 kcal».
 * There is no room on the phone for a card of its own (the speed and incline panels and STOP keep their size).
 */
@Composable
fun FuelStripLine(figures: FuelFigures, dimmed: Boolean, modifier: Modifier = Modifier) {
    val valueColor = if (dimmed) MaterialTheme.colorScheme.onSurfaceVariant else MaterialTheme.colorScheme.onSurface
    Row(
        modifier.fillMaxWidth().padding(start = 8.dp, end = 8.dp, bottom = 6.dp).testTag("fuel_strip").semantics(mergeDescendants = true) {},
        horizontalArrangement = Arrangement.Center,
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(stringResource(R.string.fuel_metric), style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        Text(figures.percentsText(), Modifier.padding(start = 8.dp).testTag("metric_value_fuel"), fontSize = 17.sp, fontWeight = FontWeight.Bold, color = valueColor, maxLines = 1)
        Text(figures.kcalText(), Modifier.padding(start = 8.dp), fontSize = 12.sp, fontWeight = FontWeight.Medium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
    }
}

// ---------------------------------------------------------------------------------------------------------------
// The card of the workout detail

/**
 * «Energy sources»: the totals of fat and carbohydrate with a stacked bar, the energy per minute (same time axis as the
 * other charts of the detail), the share of fat in each zone, and the estimate note with the explanation behind its «i».
 */
@Composable
fun FuelCard(fuel: FuelDetail, modifier: Modifier = Modifier) {
    var explaining by rememberSaveable { mutableStateOf(false) }
    val figures = fuel.figures
    Panel(modifier.fillMaxWidth().testTag("fuel_card")) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            SectionLabel(stringResource(R.string.fuel_title))
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                FuelTotal(stringResource(R.string.fuel_fat), figures.fatKcal, figures.fatPercent, MaterialTheme.extra.fuelFat, "fuel_total_fat", Modifier.weight(1f))
                FuelTotal(stringResource(R.string.fuel_carbs), figures.carbKcal, figures.carbPercent, MaterialTheme.extra.fuelCarbs, "fuel_total_carbs", Modifier.weight(1f))
            }
            FuelBar(figures, Modifier.fillMaxWidth().height(14.dp).testTag("fuel_bar"))
            if (fuel.minutes.isNotEmpty()) {
                val description = stringResource(R.string.fuel_chart_description)
                Text(description, style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant)
                FuelStackedChart(fuel.minutes, fuel.durationSeconds, description, Modifier.fillMaxWidth().height(170.dp).testTag("fuel_chart"))
            }
            if (fuel.zones.isNotEmpty()) {
                Text(stringResource(R.string.fuel_by_zone), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant)
                Column(Modifier.fillMaxWidth().testTag("fuel_zones"), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    fuel.zones.forEach { zone ->
                        val label = stringResource(R.string.zone_name, zone.zone) + " · " + stringResource(R.string.value_percent, Format.whole(zone.fatPercent))
                        Column(Modifier.fillMaxWidth().testTag("fuel_zone_${zone.zone}").semantics(mergeDescendants = true) {}, verticalArrangement = Arrangement.spacedBy(3.dp)) {
                            Text(label, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.SemiBold)
                            Box(Modifier.fillMaxWidth().height(8.dp).background(MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(50))) {
                                Box(
                                    Modifier.fillMaxWidth((zone.fatPercent / 100f).coerceIn(0.02f, 1f)).fillMaxHeight()
                                        .background(MaterialTheme.extra.fuelFat, RoundedCornerShape(50)),
                                )
                            }
                        }
                    }
                }
            }
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    stringResource(R.string.fuel_estimate_note),
                    Modifier.weight(1f).testTag("fuel_note"),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
                InfoButton(stringResource(R.string.fuel_how_title), { explaining = true }, Modifier.testTag("fuel_info_button"))
            }
        }
    }
    if (explaining) FuelHelpDialog(onDismiss = { explaining = false })
}

@Composable
private fun FuelTotal(label: String, kcal: Int, percent: Int, color: Color, tag: String, modifier: Modifier) {
    val value = stringResource(R.string.fuel_total, Format.calories(kcal), Format.whole(percent))
    Column(modifier.testTag(tag).semantics(mergeDescendants = true) {}, verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            StatusDot(color, size = 12.dp)
            Text(label, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
        }
        Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold, maxLines = 1)
    }
}

/** The two shares as one horizontal bar; [FuelFigures.summaryText] is its description. */
@Composable
fun FuelBar(figures: FuelFigures, modifier: Modifier = Modifier) {
    val description = figures.summaryText()
    Row(modifier.clip(RoundedCornerShape(50)).semantics { contentDescription = description }) {
        if (figures.fatPercent > 0) Box(Modifier.weight(figures.fatPercent.toFloat()).fillMaxHeight().background(MaterialTheme.extra.fuelFat))
        if (figures.carbPercent > 0) Box(Modifier.weight(figures.carbPercent.toFloat()).fillMaxHeight().background(MaterialTheme.extra.fuelCarbs))
    }
}

/** A small «i» in a ring, 48 dp to touch. */
@Composable
private fun InfoButton(description: String, onClick: () -> Unit, modifier: Modifier = Modifier) {
    Box(
        modifier.size(48.dp).clip(CircleShape).clickable(role = Role.Button, onClick = onClick).semantics { contentDescription = description },
        contentAlignment = Alignment.Center,
    ) {
        Box(Modifier.size(26.dp).border(2.dp, MaterialTheme.colorScheme.onSurfaceVariant, CircleShape), contentAlignment = Alignment.Center) {
            Text("i", fontSize = 15.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
        }
    }
}

@Composable
fun FuelHelpDialog(onDismiss: () -> Unit) {
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag("fuel_help_dialog"),
        title = { Text(stringResource(R.string.fuel_how_title), fontWeight = FontWeight.Bold) },
        text = { Text(stringResource(R.string.fuel_how_text), style = MaterialTheme.typography.bodyLarge) },
        confirmButton = {
            TextButton(onClick = onDismiss, modifier = Modifier.heightIn(min = 48.dp).testTag("fuel_help_close")) {
                Text(stringResource(R.string.action_ok), style = MaterialTheme.typography.titleMedium)
            }
        },
    )
}

// ---------------------------------------------------------------------------------------------------------------
// Energy per minute, stacked

/**
 * Energy per minute as a stacked area: fat below, carbohydrate above, one step per minute ([minutes]; their start is on
 * the time axis of the other charts of the workout, which spans [durationSeconds]). The frame (axes, gridlines, labels)
 * is the one of [LineChart].
 */
@Composable
fun FuelStackedChart(minutes: List<FuelMinuteBar>, durationSeconds: Double, description: String, modifier: Modifier = Modifier) {
    val measurer = rememberTextMeasurer()
    val axisColor = MaterialTheme.colorScheme.outlineVariant
    val labelStyle = TextStyle(fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    val fat = MaterialTheme.extra.fuelFat
    val carbs = MaterialTheme.extra.fuelCarbs
    Box(modifier.semantics { contentDescription = description }) {
        if (minutes.isEmpty()) return@Box
        Canvas(Modifier.fillMaxSize()) {
            val top = minutes.maxOf { it.fatKcal + it.carbKcal }
            val axis = ChartMath.niceAxis(0.0, top * 1.05)
            val end = maxOf(durationSeconds, minutes.maxOf { it.startSeconds + MINUTE_SECONDS })
            val frame = drawChartFrame(
                measurer, labelStyle, axis, 0.0, end, axisColor,
                yLabel = { Format.number(if (axis.step < 1.0) "%.1f" else "%.0f", it) },
                xLabel = { Format.duration(it.toInt()) },
                xTickCount = 3,
            ) ?: return@Canvas
            for (minute in minutes) {
                val x0 = frame.px(minute.startSeconds)
                // A little wider than the step so that neighbouring minutes leave no seam.
                val width = frame.px(minOf(minute.startSeconds + MINUTE_SECONDS, end)) - x0 + 1.5f
                val zero = frame.py(0.0)
                val fatTop = frame.py(minute.fatKcal)
                val carbTop = frame.py(minute.fatKcal + minute.carbKcal)
                if (zero > fatTop) drawRect(fat, Offset(x0, fatTop), Size(width, zero - fatTop))
                if (fatTop > carbTop) drawRect(carbs, Offset(x0, carbTop), Size(width, fatTop - carbTop))
            }
        }
    }
}

private const val MINUTE_SECONDS = 60.0
