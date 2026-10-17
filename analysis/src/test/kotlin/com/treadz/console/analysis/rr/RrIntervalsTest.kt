package com.treadz.console.analysis.rr

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Test
import kotlin.math.sqrt

class RrIntervalsTest {

    private val few = RmssdRules(minIntervals = 4)

    @Test
    fun `RMSSD is the root mean square of the successive differences`() {
        // Differences: 20, -20, 20, -20 -> RMSSD 20.
        assertEquals(20.0, RrIntervals.rmssd(listOf(800, 820, 800, 820, 800), few)!!, 1e-12)
        // Differences: 10, 20, 0, 0 -> sqrt((100 + 400) / 4)
        assertEquals(sqrt(125.0), RrIntervals.rmssd(listOf(800, 810, 830, 830, 830), RmssdRules(minIntervals = 3))!!, 1e-12)
    }

    @Test
    fun `too few accepted intervals give no RMSSD`() {
        assertNull(RrIntervals.rmssd(listOf(800, 820, 800), few))
        assertNull(RrIntervals.rmssd(emptyList(), few))
        // The default rules need 60 intervals.
        assertNull(RrIntervals.rmssd(List(59) { if (it % 2 == 0) 800 else 820 }))
        assertEquals(20.0, RrIntervals.rmssd(List(60) { if (it % 2 == 0) 800 else 820 })!!, 1e-12)
    }

    @Test
    fun `intervals out of range are left out and so is the difference across them`() {
        // 250 is below the minimum: the difference 800 -> 820 after it is not counted against 800 before it.
        val rr = listOf(800, 820, 250, 900, 920, 2_500, 700, 720)
        // Accepted runs: [800, 820], [900, 920], [700, 720]: three differences of 20.
        assertEquals(20.0, RrIntervals.rmssd(rr, RmssdRules(minIntervals = 6))!!, 1e-12)
        assertNull(RrIntervals.rmssd(rr, RmssdRules(minIntervals = 7)))
    }

    @Test
    fun `a jump of more than 20 percent from the previous accepted interval is an artifact`() {
        // 800 -> 1000 is 25 %: the 1000 is dropped and starts no difference; the run restarts at 810.
        val rr = listOf(800, 810, 1_000, 810, 830)
        // Accepted: 800, 810, 810, 830; differences 10 and 20 (none across the dropped one) -> sqrt(500 / 2)
        assertEquals(sqrt(250.0), RrIntervals.rmssd(rr, few)!!, 1e-12)
    }

    @Test
    fun `without a usable difference there is no RMSSD`() {
        assertNull(RrIntervals.rmssd(listOf(800, 1_500, 800, 1_500), RmssdRules(minIntervals = 2)))
    }
}
