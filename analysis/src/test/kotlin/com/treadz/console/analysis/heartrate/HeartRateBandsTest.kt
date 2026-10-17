package com.treadz.console.analysis.heartrate

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class HeartRateBandsTest {

    @Test
    fun `percentage of the maximum`() {
        assertEquals(BpmRange(90, 108, usedReserve = false), HeartRateBands.toBpm(180, null, ofReserve = false, lowPercent = 50, highPercent = 60))
        // A resting rate is ignored when the band is asked on the maximum.
        assertEquals(BpmRange(90, 108, usedReserve = false), HeartRateBands.toBpm(180, 60, ofReserve = false, lowPercent = 50, highPercent = 60))
    }

    @Test
    fun `percentage of the reserve with Karvonen`() {
        // reserve 120: 60 + 0.6 * 120 = 132, 60 + 0.7 * 120 = 144
        assertEquals(BpmRange(132, 144, usedReserve = true), HeartRateBands.toBpm(180, 60, ofReserve = true, lowPercent = 60, highPercent = 70))
    }

    @Test
    fun `the reserve falls back to the maximum without a resting rate`() {
        assertEquals(BpmRange(108, 126, usedReserve = false), HeartRateBands.toBpm(180, null, ofReserve = true, lowPercent = 60, highPercent = 70))
    }

    @Test
    fun `bounds are rounded to the nearest bpm`() {
        // 185 * 0.55 = 101.75 -> 102, 185 * 0.65 = 120.25 -> 120
        assertEquals(BpmRange(102, 120, usedReserve = false), HeartRateBands.toBpm(185, null, ofReserve = false, lowPercent = 55, highPercent = 65))
        // reserve 125: 55 + 0.55 * 125 = 123.75 -> 124
        assertEquals(124, HeartRateBands.toBpm(180, 55, ofReserve = true, lowPercent = 55, highPercent = 65).lowBpm)
    }
}
