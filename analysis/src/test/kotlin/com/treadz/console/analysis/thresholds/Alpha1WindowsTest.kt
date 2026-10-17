package com.treadz.console.analysis.thresholds

import com.treadz.console.analysis.rr.RrCleaning
import com.treadz.console.analysis.samples.WorkoutSample
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class Alpha1WindowsTest {

    private val start = 1_000_000L
    private val minutes = 12
    private val end = start + minutes * 60_000L

    @Test
    fun `the engine uses the same windows`() {
        val rr = SyntheticRr.ramp(start, minutes * 60, 100.0, 180.0, 1.2, 0.3, seed = 3)
        val samples = SyntheticRr.rampSamples(start, minutes * 60, 6.0, 5.0, 17.0)
        val input = ThresholdInput(rr, samples, start, end, 190, 60, 45.0)
        val engineWindows = ThresholdEngine.analyse(input).windows
        assertEquals(engineWindows, Alpha1Windows.compute(rr, samples, start, end))
        assertEquals(61, engineWindows.size)
    }

    @Test
    fun `windows over any span`() {
        val rr = SyntheticRr.ramp(start, 600, 110.0, 110.0, 1.1, 1.1, seed = 8)
        val spanStart = start + 100_000L
        val spanEnd = start + 400_000L
        val windows = Alpha1Windows.compute(rr, emptyList(), spanStart, spanEnd)
        // 120 s windows every 10 s inside 300 s: 19 of them.
        assertEquals(19, windows.size)
        assertEquals(spanStart, windows.first().startMillis)
        assertEquals(spanEnd, windows.last().endMillis)
        assertTrue(windows.all { it.valid && it.alpha1 != null && it.speedKmh == null })
        assertTrue(windows.all { it.heartRateBpm!! in 100.0..120.0 })
    }

    @Test
    fun `a precleaned series gives the same windows`() {
        val rr = SyntheticRr.ramp(start, 400, 120.0, 120.0, 1.0, 1.0, seed = 4)
        val times = LongArray(rr.size) { rr[it].timeMillis }
        val cleaned = RrCleaning.clean(rr.map { it.rrMs })
        val samples = listOf(WorkoutSample(start, 5.0, 0.0))
        assertEquals(
            Alpha1Windows.compute(rr, samples, start, start + 400_000L),
            Alpha1Windows.compute(times, cleaned, samples, start, start + 400_000L),
        )
    }

    @Test
    fun `nothing for a span shorter than a window`() {
        val rr = SyntheticRr.ramp(start, 300, 110.0, 110.0, 1.1, 1.1)
        assertTrue(Alpha1Windows.compute(rr, emptyList(), start, start + 119_000L).isEmpty())
    }
}
