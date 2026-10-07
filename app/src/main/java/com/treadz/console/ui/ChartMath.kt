package com.treadz.console.ui

import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.log10
import kotlin.math.pow

/** One point of a chart. [hollow] points are drawn as an empty circle and left out of the line (made-up values). */
data class ChartPoint(val x: Double, val y: Double, val hollow: Boolean = false)

/** A vertical axis with round tick values: [min] to [max] in steps of [step]. */
data class NiceAxis(val min: Double, val max: Double, val step: Double) {
    val ticks: List<Double>
        get() = generateSequence(min) { it + step }.takeWhile { it <= max + step * 1e-6 }.toList()
}

/** Pure chart helpers, kept apart from the drawing so they can be tested. */
object ChartMath {

    /** Axis covering [min]..[max] with about [targetTicks] round tick values. Flat data gets some room around it. */
    fun niceAxis(min: Double, max: Double, targetTicks: Int = 4): NiceAxis {
        var lo = min
        var hi = max
        if (hi - lo < 1e-9) {
            val room = if (abs(lo) < 1e-9) 1.0 else abs(lo) * 0.05
            lo -= room
            hi += room
        }
        val step = niceNumber((hi - lo) / (targetTicks - 1).coerceAtLeast(1))
        return NiceAxis(floor(lo / step) * step, ceil(hi / step) * step, step)
    }

    /** 1, 2, 2.5, 5 or 10 times a power of ten, the nearest one at or above [raw]. */
    fun niceNumber(raw: Double): Double {
        if (raw <= 0.0 || !raw.isFinite()) return 1.0
        val exponent = floor(log10(raw))
        val fraction = raw / 10.0.pow(exponent)
        val nice = when {
            fraction <= 1.0 -> 1.0
            fraction <= 2.0 -> 2.0
            fraction <= 2.5 -> 2.5
            fraction <= 5.0 -> 5.0
            else -> 10.0
        }
        return nice * 10.0.pow(exponent)
    }

    /**
     * Reduces [points] to at most [maxPoints] by averaging buckets of consecutive points, keeping the first and the
     * last ones, so a long workout (thousands of samples) draws quickly and still looks the same.
     */
    fun downsample(points: List<ChartPoint>, maxPoints: Int): List<ChartPoint> {
        if (points.size <= maxPoints || maxPoints < 3) return points
        val inner = points.subList(1, points.size - 1)
        val buckets = maxPoints - 2
        val size = inner.size.toDouble() / buckets
        val reduced = (0 until buckets).mapNotNull { b ->
            val from = floor(b * size).toInt()
            val to = floor((b + 1) * size).toInt().coerceAtMost(inner.size).coerceAtLeast(from + 1)
            val bucket = inner.subList(from, to)
            if (bucket.isEmpty()) null else ChartPoint(bucket.map { it.x }.average(), bucket.map { it.y }.average())
        }
        return listOf(points.first()) + reduced + points.last()
    }
}
