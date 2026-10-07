package com.treadz.console

import com.treadz.console.ui.ChartMath
import com.treadz.console.ui.ChartPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class ChartMathTest {

    @Test fun niceAxisUsesRoundTicksCoveringTheData() {
        val axis = ChartMath.niceAxis(61.3, 68.7)
        assertTrue(axis.min <= 61.3 && axis.max >= 68.7)
        assertEquals(0.0, axis.step % 0.5, 1e-9)
        assertTrue(axis.ticks.size in 3..8)
    }

    @Test fun niceAxisGivesFlatDataSomeRoom() {
        val axis = ChartMath.niceAxis(70.0, 70.0)
        assertTrue(axis.min < 70.0 && axis.max > 70.0)
    }

    @Test fun niceAxisOfZeroOnlyData() {
        val axis = ChartMath.niceAxis(0.0, 0.0)
        assertTrue(axis.max > axis.min)
    }

    @Test fun niceAxisFromZero() {
        val axis = ChartMath.niceAxis(0.0, 9.5)
        assertEquals(0.0, axis.min, 0.0)
        assertTrue(axis.max >= 9.5)
    }

    @Test fun downsampleKeepsShortSeriesAsTheyAre() {
        val points = (0 until 10).map { ChartPoint(it.toDouble(), it.toDouble()) }
        assertEquals(points, ChartMath.downsample(points, 400))
    }

    @Test fun downsampleReducesLongSeriesKeepingTheEnds() {
        val points = (0 until 10_000).map { ChartPoint(it.toDouble(), (it % 100).toDouble()) }
        val reduced = ChartMath.downsample(points, 400)
        assertTrue(reduced.size <= 400)
        assertEquals(points.first(), reduced.first())
        assertEquals(points.last(), reduced.last())
        assertTrue(reduced.zipWithNext().all { (a, b) -> a.x < b.x })
    }
}
