package com.treadz.console.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
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
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.drawText
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.treadz.console.training.PlanSegment

/**
 * The speed and the incline of a plan over its time line, as steps: the incline as a filled area (its own scale, on the
 * right) and the speed as a line (left). With [positionSeconds] the part already done is dimmed and a marker shows where
 * the program is. [actualSpeed] / [actualIncline] (x in seconds) are drawn over the plan, for the history. Without
 * [showAxes] only the shape is drawn (the small previews of the lists).
 */
@Composable
fun PlanChart(
    segments: List<PlanSegment>,
    description: String,
    modifier: Modifier = Modifier,
    positionSeconds: Double? = null,
    actualSpeed: List<ChartPoint> = emptyList(),
    actualIncline: List<ChartPoint> = emptyList(),
    showAxes: Boolean = true,
    speedColor: Color = MaterialTheme.colorScheme.primary,
    inclineColor: Color = MaterialTheme.colorScheme.secondary,
    emptyText: String? = null,
    heartRateColor: Color = MaterialTheme.colorScheme.error,
) {
    val measurer = rememberTextMeasurer()
    val labelStyle = TextStyle(fontSize = 12.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
    val grid = MaterialTheme.colorScheme.outlineVariant
    val marker = MaterialTheme.colorScheme.onSurface
    val actualColor = MaterialTheme.colorScheme.onSurface
    Box(modifier.semantics { contentDescription = description }) {
        if (segments.isEmpty()) {
            if (emptyText != null) {
                Text(emptyText, Modifier.align(Alignment.Center), color = MaterialTheme.colorScheme.onSurfaceVariant, style = MaterialTheme.typography.bodyLarge)
            }
            return@Box
        }
        Canvas(Modifier.matchParentSize()) {
            val totalSeconds = maxOf(segments.last().endSeconds, actualSpeed.lastOrNull()?.x?.toInt() ?: 0).coerceAtLeast(1)
            val speedMax = maxOf(segments.maxOf { it.speedKmh }, actualSpeed.maxOfOrNull { it.y } ?: 0.0)
            val inclineMax = maxOf(segments.maxOf { it.inclinePercent }, actualIncline.maxOfOrNull { it.y } ?: 0.0)
            val speedAxis = ChartMath.niceAxis(0.0, speedMax.coerceAtLeast(1.0))
            val inclineAxis = ChartMath.niceAxis(0.0, inclineMax.coerceAtLeast(1.0))

            val speedLabels = if (showAxes) speedAxis.ticks.map { measurer.measure(Format.speed(it), labelStyle) } else emptyList()
            val inclineLabels = if (showAxes) inclineAxis.ticks.map { measurer.measure(Format.incline(it), labelStyle) } else emptyList()
            val left = if (showAxes) speedLabels.maxOf { it.size.width } + 10.dp.toPx() else 0f
            val right = if (showAxes) inclineLabels.maxOf { it.size.width } + 10.dp.toPx() else 0f
            val bottom = if (showAxes) speedLabels.first().size.height + 10.dp.toPx() else 0f
            val top = if (showAxes) 8.dp.toPx() else 2.dp.toPx()
            val plot = Size(size.width - left - right, size.height - top - bottom)
            if (plot.width <= 0f || plot.height <= 0f) return@Canvas

            fun px(seconds: Double) = (left + seconds / totalSeconds * plot.width).toFloat()
            fun pySpeed(v: Double) = (top + plot.height - v / speedAxis.max * plot.height).toFloat()
            fun pyIncline(v: Double) = (top + plot.height - v / inclineAxis.max * plot.height).toFloat()

            if (showAxes) {
                speedAxis.ticks.forEachIndexed { i, tick ->
                    val y = pySpeed(tick)
                    drawLine(grid, Offset(left, y), Offset(left + plot.width, y), strokeWidth = 1.dp.toPx())
                    drawText(measurer, Format.speed(tick), Offset(left - 8.dp.toPx() - speedLabels[i].size.width, y - speedLabels[i].size.height / 2f), labelStyle.copy(color = speedColor))
                }
                inclineAxis.ticks.forEachIndexed { i, tick ->
                    val y = pyIncline(tick)
                    drawText(measurer, Format.incline(tick), Offset(left + plot.width + 8.dp.toPx(), y - inclineLabels[i].size.height / 2f), labelStyle.copy(color = inclineColor))
                }
                val xs = listOf(0.0, totalSeconds / 2.0, totalSeconds.toDouble())
                xs.forEachIndexed { i, x ->
                    val text = measurer.measure(Format.duration(x.toInt()), labelStyle)
                    val cx = px(x)
                    val x0 = when (i) {
                        0 -> left
                        2 -> left + plot.width - text.size.width
                        else -> cx - text.size.width / 2f
                    }
                    drawText(text, topLeft = Offset(x0, top + plot.height + 6.dp.toPx()))
                }
            }

            // Blocks guided by heart rate: a tinted column and a bar with a small heart on top of the plot.
            segments.filter { it.heartRate != null }.forEach { s ->
                val x0 = px(s.startSeconds.toDouble())
                val x1 = px(s.endSeconds.toDouble())
                drawRect(heartRateColor.copy(alpha = 0.10f), Offset(x0, top), Size(x1 - x0, plot.height))
                drawRoundRect(
                    heartRateColor.copy(alpha = 0.9f), Offset(x0, top), Size((x1 - x0).coerceAtLeast(1f), 5.dp.toPx()),
                    androidx.compose.ui.geometry.CornerRadius(2.5.dp.toPx()),
                )
                if (showAxes && x1 - x0 >= 26.dp.toPx()) {
                    val cx = (x0 + x1) / 2f
                    val h = 11.dp.toPx()
                    val heart = Path().apply {
                        moveTo(cx, top + 8.dp.toPx() + h)
                        cubicTo(cx - h * 1.2f, top + 8.dp.toPx() + h * 0.45f, cx - h * 0.9f, top + 8.dp.toPx() - h * 0.25f, cx, top + 8.dp.toPx() + h * 0.25f)
                        cubicTo(cx + h * 0.9f, top + 8.dp.toPx() - h * 0.25f, cx + h * 1.2f, top + 8.dp.toPx() + h * 0.45f, cx, top + 8.dp.toPx() + h)
                        close()
                    }
                    drawPath(heart, heartRateColor)
                }
            }

            // Incline: filled steps.
            val inclinePath = Path().apply {
                moveTo(px(0.0), pyIncline(0.0))
                segments.forEach { s ->
                    lineTo(px(s.startSeconds.toDouble()), pyIncline(s.inclinePercent))
                    lineTo(px(s.endSeconds.toDouble()), pyIncline(s.inclinePercent))
                }
                lineTo(px(segments.last().endSeconds.toDouble()), pyIncline(0.0))
                close()
            }
            drawPath(inclinePath, inclineColor.copy(alpha = 0.28f))
            drawPath(inclinePath, inclineColor.copy(alpha = 0.8f), style = Stroke(width = (if (showAxes) 1.5f else 1f).dp.toPx(), join = StrokeJoin.Round))

            // Speed: stepped line.
            val speedPath = Path().apply {
                segments.forEachIndexed { i, s ->
                    if (i == 0) moveTo(px(s.startSeconds.toDouble()), pySpeed(s.speedKmh)) else lineTo(px(s.startSeconds.toDouble()), pySpeed(s.speedKmh))
                    lineTo(px(s.endSeconds.toDouble()), pySpeed(s.speedKmh))
                }
            }
            drawPath(speedPath, speedColor, style = Stroke(width = (if (showAxes) 3f else 2f).dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))

            // What really happened.
            drawActual(actualIncline, ::px, ::pyIncline, inclineColor, 1.5f)
            drawActual(actualSpeed, ::px, ::pySpeed, actualColor, 2f)

            // Where the program is.
            if (positionSeconds != null) {
                val x = px(positionSeconds.coerceIn(0.0, totalSeconds.toDouble()))
                drawRect(marker.copy(alpha = 0.10f), Offset(left, top), Size(x - left, plot.height))
                drawLine(marker, Offset(x, top), Offset(x, top + plot.height), strokeWidth = 3.dp.toPx())
                drawCircle(marker, 5.dp.toPx(), Offset(x, top))
            }
        }
    }
}

private fun DrawScope.drawActual(points: List<ChartPoint>, px: (Double) -> Float, py: (Double) -> Float, color: Color, width: Float) {
    if (points.size < 2) return
    val path = Path()
    points.forEachIndexed { i, p -> if (i == 0) path.moveTo(px(p.x), py(p.y)) else path.lineTo(px(p.x), py(p.y)) }
    drawPath(path, color.copy(alpha = 0.9f), style = Stroke(width = width.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
}

/** Colour key of a [PlanChart]. */
@OptIn(androidx.compose.foundation.layout.ExperimentalLayoutApi::class)
@Composable
fun PlanLegend(
    speedLabel: String,
    inclineLabel: String,
    modifier: Modifier = Modifier,
    actualLabel: String? = null,
    /** Shown when the plan has blocks guided by heart rate. */
    heartRateLabel: String? = null,
) {
    androidx.compose.foundation.layout.FlowRow(
        modifier, horizontalArrangement = Arrangement.spacedBy(16.dp), verticalArrangement = Arrangement.spacedBy(4.dp),
        itemVerticalAlignment = Alignment.CenterVertically,
    ) {
        LegendItem(MaterialTheme.colorScheme.primary, speedLabel)
        LegendItem(MaterialTheme.colorScheme.secondary, inclineLabel)
        if (actualLabel != null) LegendItem(MaterialTheme.colorScheme.onSurface, actualLabel)
        if (heartRateLabel != null) LegendItem(MaterialTheme.colorScheme.error, heartRateLabel)
    }
}

@Composable
private fun LegendItem(color: Color, label: String) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        Canvas(Modifier.size(width = 18.dp, height = 4.dp)) { drawRect(color) }
        Text(label, style = MaterialTheme.typography.labelMedium, color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1)
    }
}

/**
 * A value of the samples on the program's clock: it runs only while the belt is running, like the program's, so the
 * actual line lines up with the plan. Samples with the belt not running or with stale data are left out.
 */
fun runningSeries(samples: List<com.treadz.console.userdata.WorkoutSample>, value: (com.treadz.console.userdata.WorkoutSample) -> Double): List<ChartPoint> {
    val points = ArrayList<ChartPoint>(samples.size)
    var clock = 0.0
    var previous: com.treadz.console.userdata.WorkoutSample? = null
    for (s in samples) {
        val p = previous
        if (p != null && p.beltStatus == com.treadz.console.treadmill.BeltStatus.Running) clock += (s.timestampMillis - p.timestampMillis) / 1000.0
        if (s.beltStatus == com.treadz.console.treadmill.BeltStatus.Running && !s.isStale) points += ChartPoint(clock, value(s))
        previous = s
    }
    return points
}
