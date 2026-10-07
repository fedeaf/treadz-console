package com.treadz.console.treadmill.ftms

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class BleAddressAndScanLimiterTest {

    @Test
    fun `the treadmill address is a random static address`() {
        assertTrue(BleAddress.isRandomStatic("D5:F3:C2:06:5C:8B"))
        assertTrue(BleAddress.isRandomStatic("d5:f3:c2:06:5c:8b"))
        assertTrue(BleAddress.isRandomStatic("C0:00:00:00:00:01"))
        assertTrue(BleAddress.isRandomStatic("FF:FF:FF:FF:FF:FF"))
    }

    @Test
    fun `addresses whose two top bits are not both set are not random static`() {
        assertFalse(BleAddress.isRandomStatic("00:11:22:33:44:55"))
        assertFalse(BleAddress.isRandomStatic("BF:11:22:33:44:55"))
        assertFalse(BleAddress.isRandomStatic("7F:11:22:33:44:55"))
        assertFalse(BleAddress.isRandomStatic(""))
        assertFalse(BleAddress.isRandomStatic("not an address"))
    }

    @Test
    fun `addresses are compared case-insensitively`() {
        assertEquals("D5:F3:C2:06:5C:8B", BleAddress.normalize(" d5:f3:c2:06:5c:8b "))
    }

    @Test
    fun `the limiter lets scans through up to its limit and then says how long to wait`() {
        val limiter = ScanRateLimiter(maxStarts = 4, windowMs = 30_000)
        for (t in listOf(0L, 1_000L, 2_000L, 3_000L)) {
            assertEquals(0L, limiter.waitMs(t), "start at $t")
            limiter.recordStart(t)
        }
        // The fifth start would exceed the limit until the first one leaves the 30 s window
        assertEquals(26_000L, limiter.waitMs(4_000))
        assertEquals(1L, limiter.waitMs(29_999))
        assertEquals(0L, limiter.waitMs(30_000))
        limiter.recordStart(30_000)
        // Now the starts at 1_000, 2_000, 3_000 and 30_000 are inside the window
        assertEquals(1_000L, limiter.waitMs(30_000))
    }

    @Test
    fun `starts older than the window are forgotten`() {
        val limiter = ScanRateLimiter(maxStarts = 2, windowMs = 10_000)
        limiter.recordStart(0)
        limiter.recordStart(1_000)
        assertEquals(9_000L, limiter.waitMs(1_000))
        assertEquals(0L, limiter.waitMs(10_000), "the start at 0 left the window")
        limiter.recordStart(10_000)
        assertEquals(1_000L, limiter.waitMs(10_000))
        assertEquals(0L, limiter.waitMs(11_000))
    }
}
