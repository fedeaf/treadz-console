package com.treadz.console.programs.fitness

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class HeartRateLogTest {

    /** Two packets per second, each value repeated (like Android), from [from] s to [to] s. */
    private fun HeartRateLog.fill(from: Int, to: Int, bpm: (Int) -> Int?) {
        for (s in from until to) {
            add(s * 1000L + 250, bpm(s))
            add(s * 1000L + 750, bpm(s))
        }
    }

    @Test
    fun `means are time based and skip invalid samples`() {
        val log = HeartRateLog()
        log.fill(0, 10) { if (it < 5) 100 else null }
        log.fill(10, 20) { 110 }
        assertEquals(100.0, log.mean(0, 10_000))
        assertEquals(110.0, log.mean(10_000, 20_000))
        assertEquals((5 * 100 + 10 * 110) / 15.0, log.mean(0, 20_000)!!, 1e-9, "the invalid seconds do not count")
        assertNull(log.mean(5_000, 10_000))
        assertEquals(19_750L, log.lastValidAt)
    }

    @Test
    fun `variation of 5-second means, coverage and flatness`() {
        val log = HeartRateLog()
        log.fill(0, 120) { 100 + it / 30 } // 100 ... 103
        assertEquals(3.0, log.bucketVariation(0, 120_000, 5_000)!!, 1e-9)
        assertEquals(1.0, log.coverage(0, 120_000))
        assertFalse(log.isFlat(0, 120_000))
        assertTrue(log.isFlat(0, 29_999))

        val gaps = HeartRateLog()
        gaps.fill(0, 120) { if (it % 2 == 0) null else 100 }
        assertEquals(0.5, gaps.coverage(0, 120_000), 1e-9)
        val sparse = HeartRateLog()
        sparse.fill(0, 120) { if (it < 60) null else 100 }
        assertNull(sparse.bucketVariation(0, 120_000, 5_000), "fewer than 80 % of the buckets have data")
    }

    @Test
    fun `highest sliding mean`() {
        val log = HeartRateLog()
        log.fill(0, 60) { if (it in 30..34) 180 else 150 }
        assertEquals(180.0, log.maxSlidingMean(0, 60_000, 5_000)!!, 1e-9)
        assertEquals(150.0, log.maxSlidingMean(40_000, 60_000, 5_000)!!, 1e-9)
    }
}
