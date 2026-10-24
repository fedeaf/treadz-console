package com.treadz.console.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.ui.Alignment
import androidx.compose.runtime.Composable
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
import androidx.compose.ui.unit.sp
import androidx.compose.ui.unit.dp

/** A horizontal dashed line at [value] with a label (the target weight). */
data class ReferenceLine(val value: Double, val label: String, val color: Color)

/**
 * Simple line chart drawn on a Canvas: gridlines with round Y values, a few X labels, the line with its area and,
 * optionally, dots and a reference line. [points] must be ordered by x.
 */
@Composable
fun LineChart(
    points: List<ChartPoint>,
    color: Color,
    yLabel: (Double) -> String,
    xLabel: (Double) -> String,
    description: String,
    modifier: Modifier = Modifier,
    /** The Y axis starts at this value instead of following the data (speed and incline start at 0). */
    yFloor: Double? = null,
    reference: ReferenceLine? = null,
    showDots: Boolean = false,
    fill: Boolean = true,
    xTickCount: Int = 3,
    emptyText: String? = null,
) {
    val measurer = rememberTextMeasurer()
    val axisColor = MaterialTheme.colorScheme.outlineVariant
    val textColor = MaterialTheme.colorScheme.onSurfaceVariant
    val labelStyle = TextStyle(fontSize = 12.sp, color = textColor)
    val background = MaterialTheme.colorScheme.surfaceContainer
    Box(modifier.semantics { contentDescription = description }) {
        if (points.isEmpty()) {
            if (emptyText != null) {
                Text(
                    emptyText,
                    Modifier.align(Alignment.Center),
                    color = textColor,
                    style = MaterialTheme.typography.bodyLarge,
                )
            }
            return@Box
        }
        Canvas(Modifier.fillMaxSize()) {
            drawChart(measurer, labelStyle, points, color, axisColor, background, yLabel, xLabel, yFloor, reference, showDots, fill, xTickCount)
        }
    }
}

/** Where the plot of a chart is and how data maps onto it (see [drawChartFrame]). */
internal class ChartFrame(
    val axis: NiceAxis,
    val leftMargin: Float,
    val topMargin: Float,
    val plot: Size,
    private val xMin: Double,
    private val xSpan: Double,
    private val xFlat: Boolean,
) {
    fun px(x: Double): Float = if (xFlat) leftMargin + plot.width / 2 else (leftMargin + ((x - xMin) / xSpan * plot.width)).toFloat()

    fun py(y: Double): Float = (topMargin + plot.height - ((y - axis.min) / (axis.max - axis.min) * plot.height)).toFloat()
}

/**
 * The frame the charts of the app share: margins sized to the Y labels, a horizontal gridline with its label at every
 * tick of [axis] and [xTickCount] X labels (the first at the left edge, the last at the right edge, the others centred)
 * between [xMin] and [xMax]. `null` when there is no room to draw.
 */
internal fun DrawScope.drawChartFrame(
    measurer: TextMeasurer,
    labelStyle: TextStyle,
    axis: NiceAxis,
    xMin: Double,
    xMax: Double,
    axisColor: Color,
    yLabel: (Double) -> String,
    xLabel: (Double) -> String,
    xTickCount: Int,
): ChartFrame? {
    val ticks = axis.ticks
    val labels = ticks.map { measurer.measure(yLabel(it), labelStyle) }
    val leftMargin = (labels.maxOf { it.size.width } + 10.dp.toPx())
    val bottomMargin = labels.first().size.height + 10.dp.toPx()
    val topMargin = 8.dp.toPx()
    val rightMargin = 12.dp.toPx()
    val plot = Size(size.width - leftMargin - rightMargin, size.height - topMargin - bottomMargin)
    if (plot.width <= 0f || plot.height <= 0f) return null

    val xFlat = xMax - xMin < 1e-9
    val xSpan = (xMax - xMin).takeIf { !xFlat } ?: 1.0
    val frame = ChartFrame(axis, leftMargin, topMargin, plot, xMin, xSpan, xFlat)

    // Gridlines and Y labels.
    ticks.forEachIndexed { i, tick ->
        val y = frame.py(tick)
        drawLine(axisColor, Offset(leftMargin, y), Offset(leftMargin + plot.width, y), strokeWidth = 1.dp.toPx())
        drawText(measurer, yLabel(tick), Offset(leftMargin - 8.dp.toPx() - labels[i].size.width, y - labels[i].size.height / 2f), labelStyle)
    }

    // X labels: first at the left edge, last at the right edge, the others centred.
    val count = if (xFlat) 1 else xTickCount.coerceAtLeast(2)
    for (i in 0 until count) {
        val x = if (count == 1) xMin else xMin + xSpan * i / (count - 1)
        val text = measurer.measure(xLabel(x), labelStyle)
        val cx = frame.px(x)
        val left = when (i) {
            0 -> if (count == 1) cx - text.size.width / 2f else leftMargin
            count - 1 -> leftMargin + plot.width - text.size.width
            else -> cx - text.size.width / 2f
        }
        drawText(text, topLeft = Offset(left, topMargin + plot.height + 6.dp.toPx()))
    }
    return frame
}

private fun DrawScope.drawChart(
    measurer: TextMeasurer,
    labelStyle: TextStyle,
    points: List<ChartPoint>,
    color: Color,
    axisColor: Color,
    background: Color,
    yLabel: (Double) -> String,
    xLabel: (Double) -> String,
    yFloor: Double?,
    reference: ReferenceLine?,
    showDots: Boolean,
    fill: Boolean,
    xTickCount: Int,
) {
    val values = points.map { it.y } + listOfNotNull(reference?.value)
    // A little room so that the line and the reference do not sit on the edges of the chart.
    val room = (values.max() - values.min()).takeIf { it > 1e-9 }?.let { it * 0.06 } ?: 0.0
    val axis = ChartMath.niceAxis(
        min = if (yFloor != null) minOf(yFloor, values.min()) else values.min() - room,
        max = values.max() + room,
    )
    val frame = drawChartFrame(measurer, labelStyle, axis, points.first().x, points.last().x, axisColor, yLabel, xLabel, xTickCount) ?: return
    val leftMargin = frame.leftMargin
    val plot = frame.plot
    fun px(x: Double): Float = frame.px(x)
    fun py(y: Double): Float = frame.py(y)

    // Line and area (hollow points are not part of the line).
    val line = points.filter { !it.hollow }
    if (line.size >= 2) {
        val path = Path()
        line.forEachIndexed { i, p -> if (i == 0) path.moveTo(px(p.x), py(p.y)) else path.lineTo(px(p.x), py(p.y)) }
        if (fill) {
            val area = Path().apply {
                addPath(path)
                lineTo(px(line.last().x), py(axis.min))
                lineTo(px(line.first().x), py(axis.min))
                close()
            }
            drawPath(area, color.copy(alpha = 0.16f))
        }
        drawPath(path, color, style = Stroke(width = 3.dp.toPx(), cap = StrokeCap.Round, join = StrokeJoin.Round))
    }

    // Reference line with its label above the line, on the right.
    if (reference != null) {
        val y = py(reference.value)
        drawLine(
            reference.color, Offset(leftMargin, y), Offset(leftMargin + plot.width, y),
            strokeWidth = 2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(14.dp.toPx(), 8.dp.toPx())),
        )
        val text = measurer.measure(reference.label, labelStyle.copy(color = reference.color))
        drawText(text, topLeft = Offset(leftMargin + plot.width - text.size.width, y - text.size.height - 2.dp.toPx()))
    }

    // Dots: every point when asked, and always the hollow (made-up) ones and a lone point.
    points.forEach { p ->
        when {
            p.hollow -> {
                drawCircle(background, 7.dp.toPx(), Offset(px(p.x), py(p.y)))
                drawCircle(color, 7.dp.toPx(), Offset(px(p.x), py(p.y)), style = Stroke(2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(5.dp.toPx(), 4.dp.toPx()))))
            }
            showDots || line.size == 1 -> drawCircle(color, 5.dp.toPx(), Offset(px(p.x), py(p.y)))
        }
    }
}
