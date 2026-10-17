package com.treadz.console.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.size
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import java.time.LocalDate

// Charts of the Performance screen: several series over dates, a band, and weekly bars. Drawn on a Canvas like LineChart;
// the maths (axes, down-sampling) is ChartMath's.

/**
 * One series of a [TrendChart]. [ChartPoint.hollow] points are drawn as an empty circle but stay on the line (they are
 * real values with a caveat: a lower bound, a low quality).
 */
data class TrendSeries(
    val color: Color,
    val points: List<ChartPoint>,
    /** Connect the points. */
    val line: Boolean = true,
    /** Draw a dot at every point (a few per series); hollow points always get theirs. */
    val dots: Boolean = true,
)

/** A band around a series (the standard error of a test): [low]..[high] at [x]. */
data class BandPoint(val x: Double, val low: Double, val high: Double)

/** Epoch day (as the x of a chart point) to the short label of the axis. */
fun epochDayLabel(withYear: Boolean, locale: java.util.Locale): (Double) -> String =
    { x -> Format.axisDate(LocalDate.ofEpochDay(x.toLong()), withYear, locale) }

/** Whether a range of epoch days is long enough that its dates need the year. */
fun needsYear(firstEpochDay: Double, lastEpochDay: Double): Boolean = lastEpochDay - firstEpochDay > 300

/**
 * Line chart with several series, an optional band and reference line, over x values (epoch days). Every series must be
 * ordered by x.
 */
@Composable
fun TrendChart(
    series: List<TrendSeries>,
    xLabel: (Double) -> String,
    yLabel: (Double) -> String,
    description: String,
    modifier: Modifier = Modifier,
    band: List<BandPoint> = emptyList(),
    bandColor: Color = MaterialTheme.colorScheme.primary,
    reference: ReferenceLine? = null,
    yFloor: Double? = null,
    xTickCount: Int = 3,
) {
    val measurer = rememberTextMeasurer()
    val axisColor = MaterialTheme.colorScheme.outlineVariant
    val labelStyle = TextStyle(fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    val background = MaterialTheme.colorScheme.surfaceContainer
    Box(modifier.semantics { contentDescription = description }) {
        Canvas(Modifier.fillMaxSize()) {
            drawTrend(measurer, labelStyle, series, band, bandColor, reference, yFloor, axisColor, background, xLabel, yLabel, xTickCount)
        }
    }
}

private class Plot(val left: Float, val top: Float, val width: Float, val height: Float)

private fun DrawScope.plotArea(measurer: TextMeasurer, style: TextStyle, ticks: List<Double>, yLabel: (Double) -> String): Pair<Plot, List<androidx.compose.ui.text.TextLayoutResult>>? {
    val labels = ticks.map { measurer.measure(yLabel(it), style) }
    val left = labels.maxOf { it.size.width } + 10.dp.toPx()
    val bottom = labels.first().size.height + 10.dp.toPx()
    val top = 8.dp.toPx()
    val right = 12.dp.toPx()
    val plot = Plot(left, top, size.width - left - right, size.height - top - bottom)
    return if (plot.width <= 0f || plot.height <= 0f) null else plot to labels
}

private fun DrawScope.gridAndYLabels(plot: Plot, ticks: List<Double>, labels: List<androidx.compose.ui.text.TextLayoutResult>, py: (Double) -> Float, axisColor: Color, measurer: TextMeasurer, style: TextStyle, yLabel: (Double) -> String) {
    ticks.forEachIndexed { i, tick ->
        val y = py(tick)
        drawLine(axisColor, Offset(plot.left, y), Offset(plot.left + plot.width, y), strokeWidth = 1.dp.toPx())
        drawText(measurer, yLabel(tick), Offset(plot.left - 8.dp.toPx() - labels[i].size.width, y - labels[i].size.height / 2f), style)
    }
}

private fun DrawScope.drawTrend(
    measurer: TextMeasurer,
    style: TextStyle,
    series: List<TrendSeries>,
    band: List<BandPoint>,
    bandColor: Color,
    reference: ReferenceLine?,
    yFloor: Double?,
    axisColor: Color,
    background: Color,
    xLabel: (Double) -> String,
    yLabel: (Double) -> String,
    xTickCount: Int,
) {
    val allX = series.flatMap { s -> s.points.map { it.x } } + band.map { it.x }
    val allY = series.flatMap { s -> s.points.map { it.y } } + band.flatMap { listOf(it.low, it.high) } + listOfNotNull(reference?.value)
    if (allX.isEmpty() || allY.isEmpty()) return
    val room = (allY.max() - allY.min()).takeIf { it > 1e-9 }?.let { it * 0.06 } ?: 0.0
    val axis = ChartMath.niceAxis(
        min = if (yFloor != null) minOf(yFloor, allY.min()) else allY.min() - room,
        max = allY.max() + room,
    )
    val ticks = axis.ticks
    val (plot, labels) = plotArea(measurer, style, ticks, yLabel) ?: return
    val xMin = allX.min()
    val xMax = allX.max()
    val xSpan = (xMax - xMin).takeIf { it > 1e-9 } ?: 1.0
    fun px(x: Double): Float = if (xMax - xMin < 1e-9) plot.left + plot.width / 2 else (plot.left + (x - xMin) / xSpan * plot.width).toFloat()
    fun py(y: Double): Float = (plot.top + plot.height - (y - axis.min) / (axis.max - axis.min) * plot.height).toFloat()

    gridAndYLabels(plot, ticks, labels, ::py, axisColor, measurer, style, yLabel)
    xAxisLabels(measurer, style, plot, xMin, xSpan, xMax, ::px, xLabel, xTickCount)

    // The band under everything else.
    if (band.size >= 2) {
        val area = Path()
        band.forEachIndexed { i, b -> if (i == 0) area.moveTo(px(b.x), py(b.high)) else area.lineTo(px(b.x), py(b.high)) }
        band.asReversed().forEach { b -> area.lineTo(px(b.x), py(b.low)) }
        area.close()
        drawPath(area, bandColor.copy(alpha = 0.18f))
    } else if (band.size == 1) {
        val b = band.first()
        drawLine(bandColor.copy(alpha = 0.35f), Offset(px(b.x), py(b.high)), Offset(px(b.x), py(b.low)), strokeWidth = 8.dp.toPx(), cap = StrokeCap.Round)
    }

    if (reference != null) {
        val y = py(reference.value)
        drawLine(
            reference.color, Offset(plot.left, y), Offset(plot.left + plot.width, y),
            strokeWidth = 2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(14.dp.toPx(), 8.dp.toPx())),
        )
        val text = measurer.measure(reference.label, style.copy(color = reference.color))
        drawText(text, topLeft = Offset(plot.left + plot.width - text.size.width, y - text.size.height - 2.dp.toPx()))
    }

    series.forEach { s ->
        if (s.line && s.points.size >= 2) {
            val path = Path()
            s.points.forEachIndexed { i, p -> if (i == 0) path.moveTo(px(p.x), py(p.y)) else path.lineTo(px(p.x), py(p.y)) }
            drawPath(path, s.color, style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
        }
        val dotsEverywhere = s.dots && s.points.size <= 60
        s.points.forEach { p ->
            val centre = Offset(px(p.x), py(p.y))
            when {
                p.hollow -> {
                    drawCircle(background, 7.dp.toPx(), centre)
                    drawCircle(s.color, 7.dp.toPx(), centre, style = Stroke(2.5.dp.toPx()))
                }
                dotsEverywhere || s.points.size == 1 -> drawCircle(s.color, 5.dp.toPx(), centre)
            }
        }
    }
}

private fun DrawScope.xAxisLabels(
    measurer: TextMeasurer,
    style: TextStyle,
    plot: Plot,
    xMin: Double,
    xSpan: Double,
    xMax: Double,
    px: (Double) -> Float,
    xLabel: (Double) -> String,
    xTickCount: Int,
) {
    val count = if (xMax - xMin < 1e-9) 1 else xTickCount.coerceAtLeast(2)
    for (i in 0 until count) {
        val x = if (count == 1) xMin else xMin + xSpan * i / (count - 1)
        val text = measurer.measure(xLabel(x), style)
        val cx = px(x)
        val left = when (i) {
            0 -> if (count == 1) cx - text.size.width / 2f else plot.left
            count - 1 -> plot.left + plot.width - text.size.width
            else -> cx - text.size.width / 2f
        }
        drawText(text, topLeft = Offset(left, plot.top + plot.height + 6.dp.toPx()))
    }
}

/** One bar of a [WeeklyBarChart]; [flagged] bars carry a dot above them (part of the week is missing from the figure). */
data class BarItem(val value: Double, val flagged: Boolean = false)

/** Bars from zero, one per period, with the first and last period labelled on the axis. */
@Composable
fun WeeklyBarChart(
    bars: List<BarItem>,
    color: Color,
    flagColor: Color,
    yLabel: (Double) -> String,
    firstLabel: String,
    lastLabel: String,
    description: String,
    modifier: Modifier = Modifier,
) {
    val measurer = rememberTextMeasurer()
    val axisColor = MaterialTheme.colorScheme.outlineVariant
    val style = TextStyle(fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Box(modifier.semantics { contentDescription = description }) {
        Canvas(Modifier.fillMaxSize()) {
            if (bars.isEmpty()) return@Canvas
            val axis = ChartMath.niceAxis(0.0, bars.maxOf { it.value }.coerceAtLeast(1.0))
            val ticks = axis.ticks
            val (plot, labels) = plotArea(measurer, style, ticks, yLabel) ?: return@Canvas
            fun py(y: Double): Float = (plot.top + plot.height - (y - axis.min) / (axis.max - axis.min) * plot.height).toFloat()
            gridAndYLabels(plot, ticks, labels, ::py, axisColor, measurer, style, yLabel)
            val slot = plot.width / bars.size
            val barWidth = slot * 0.62f
            bars.forEachIndexed { i, bar ->
                val x = plot.left + slot * i + (slot - barWidth) / 2
                val top = py(bar.value)
                if (bar.value > 0) drawRoundRect(color, Offset(x, top), Size(barWidth, py(0.0) - top), androidx.compose.ui.geometry.CornerRadius(3.dp.toPx()))
                if (bar.flagged) drawCircle(flagColor, 4.dp.toPx(), Offset(x + barWidth / 2, top - 8.dp.toPx()))
            }
            edgeLabels(measurer, style, plot, firstLabel, lastLabel)
        }
    }
}

/**
 * 100 % stacked bars, one per period: [shares] has one list of fractions per bar (zone 1 first, adding up to 1, or all
 * zero for a period without data, drawn as an empty slot with a thin base line).
 */
@Composable
fun StackedShareChart(
    shares: List<List<Double>>,
    colors: List<Color>,
    firstLabel: String,
    lastLabel: String,
    description: String,
    modifier: Modifier = Modifier,
) {
    val measurer = rememberTextMeasurer()
    val axisColor = MaterialTheme.colorScheme.outlineVariant
    val style = TextStyle(fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Box(modifier.semantics { contentDescription = description }) {
        Canvas(Modifier.fillMaxSize()) {
            if (shares.isEmpty()) return@Canvas
            val ticks = listOf(0.0, 50.0, 100.0)
            val (plot, labels) = plotArea(measurer, style, ticks) { "${it.toInt()} %" } ?: return@Canvas
            fun py(y: Double): Float = (plot.top + plot.height - y / 100.0 * plot.height).toFloat()
            gridAndYLabels(plot, ticks, labels, ::py, axisColor, measurer, style) { "${it.toInt()} %" }
            val slot = plot.width / shares.size
            val barWidth = slot * 0.62f
            shares.forEachIndexed { i, fractions ->
                val x = plot.left + slot * i + (slot - barWidth) / 2
                var base = 0.0
                fractions.forEachIndexed { zone, fraction ->
                    if (fraction <= 0.0) return@forEachIndexed
                    val top = py((base + fraction) * 100)
                    val bottom = py(base * 100)
                    drawRect(colors[zone.coerceAtMost(colors.lastIndex)], Offset(x, top), Size(barWidth, bottom - top))
                    base += fraction
                }
            }
            edgeLabels(measurer, style, plot, firstLabel, lastLabel)
        }
    }
}

private fun DrawScope.edgeLabels(measurer: TextMeasurer, style: TextStyle, plot: Plot, firstLabel: String, lastLabel: String) {
    val y = plot.top + plot.height + 6.dp.toPx()
    val first = measurer.measure(firstLabel, style)
    drawText(first, topLeft = Offset(plot.left, y))
    if (lastLabel != firstLabel) {
        val last = measurer.measure(lastLabel, style)
        drawText(last, topLeft = Offset(plot.left + plot.width - last.size.width, y))
    }
}

/** Colour key under a chart: a dot (filled or hollow) and a name for each entry. */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ChartLegend(entries: List<LegendEntry>, modifier: Modifier = Modifier) {
    FlowRow(modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(14.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        entries.forEach { entry ->
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                LegendMark(entry)
                Text(entry.label, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
        }
    }
}

data class LegendEntry(val label: String, val color: Color, val hollow: Boolean = false, val band: Boolean = false)

@Composable
private fun LegendMark(entry: LegendEntry) {
    Canvas(Modifier.size14()) {
        val centre = Offset(size.width / 2, size.height / 2)
        when {
            entry.band -> drawRoundRect(entry.color.copy(alpha = 0.3f), Offset(0f, size.height * 0.15f), Size(size.width, size.height * 0.7f), androidx.compose.ui.geometry.CornerRadius(3.dp.toPx()))
            entry.hollow -> drawCircle(entry.color, size.width / 2 - 1.5.dp.toPx(), centre, style = Stroke(2.dp.toPx()))
            else -> drawCircle(entry.color, size.width / 2 - 1.dp.toPx(), centre)
        }
    }
}

private fun Modifier.size14() = this.then(Modifier.size(14.dp))
