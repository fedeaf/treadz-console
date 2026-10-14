package com.treadz.console.programs.heartrate

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class HeartRatePlausibilityFilterTest {

    /** Feeds [values] (one per second, each repeated twice like the console at 2 Hz) from time [from]; returns the outputs. */
    private fun HeartRatePlausibilityFilter.feed(from: Long, vararg values: Int?): List<Int?> {
        val out = mutableListOf<Int?>()
        var t = from
        for (v in values) {
            repeat(2) {
                out += accept(t, v)
                t += 500
            }
        }
        return out
    }

    @Test
    fun `range limits`() {
        val f = HeartRatePlausibilityFilter(180)
        assertNull(f.accept(0, 39))
        assertEquals(40, f.accept(1_000, 40))
        f.reset()
        assertEquals(195, f.accept(10_000, 195))
        f.reset()
        assertNull(f.accept(20_000, 196), "maximum + 15 is the limit")
        assertNull(HeartRatePlausibilityFilter(null).accept(0, 241))
        assertEquals(230, HeartRatePlausibilityFilter(null, PlausibilityConfig(maxAboveHrMaxBpm = null)).accept(0, 230))
    }

    @Test
    fun `a jump of more than 30 bpm within a second is dropped, a lasting change is accepted`() {
        val f = HeartRatePlausibilityFilter(180)
        val out = f.feed(0, 120, 155, 120)
        assertEquals(listOf(120, 120, null, null, 120, 120), out)
        val g = HeartRatePlausibilityFilter(180)
        val lasting = g.feed(0, 120, 155, 155, 155)
        assertEquals(listOf(120, 120, null, null, null, 155, 155, 155), lasting, "accepted once the last accepted value is 1.5 s old")
        val h = HeartRatePlausibilityFilter(180)
        assertEquals(listOf(120, 120, 150, 150), h.feed(0, 120, 150), "30 bpm is still possible")
    }

    @Test
    fun `strap taken off - the console's drop to 73-80 is rejected until it comes back`() {
        val f = HeartRatePlausibilityFilter(180)
        f.feed(0, 140, 141, 140, 142, 141)
        val ghost = f.feed(5_000, 76, 77, 75, 78, 76, 74, 79, 80, 77, 75, 76, 77)
        assertTrue(ghost.all { it == null }, "$ghost")
        assertTrue(f.isSuspicious)
        // Back near the previous level: it was a glitch or the strap is back.
        assertEquals(listOf(130, 130), f.feed(30_000, 130))
        assertFalse(f.isSuspicious)
    }

    @Test
    fun `strap put back on after the ghost values`() {
        val f = HeartRatePlausibilityFilter(180)
        f.feed(0, 150, 150, 150)
        f.feed(3_000, 76, 76, 76, 76)
        // The real heart rate fell meanwhile (the user slowed down): 112 is not within 25 of 150, but it is a rise of more
        // than 25 from the ghost values: the strap is back.
        val back = f.feed(7_000, 112, 113)
        assertEquals(listOf(112, 112, 113, 113), back)
    }

    @Test
    fun `no reading resets the series`() {
        val f = HeartRatePlausibilityFilter(180)
        f.feed(0, 150, 150, 150)
        f.feed(3_000, 76, 76) // the first 1.5 s count as a jump, then as an impossible drop
        assertTrue(f.isSuspicious)
        assertNull(f.accept(5_000, null))
        assertFalse(f.isSuspicious)
        assertEquals(76, f.accept(6_000, 76))
    }

    @Test
    fun `a gradual fall is fine`() {
        val f = HeartRatePlausibilityFilter(180)
        val values = (0 until 60).map { 160 - it }.toTypedArray()
        val out = f.feed(0, *values)
        assertTrue(out.all { it != null }, "$out")
    }

    @Test
    fun `a single low glitch after a high one recovers at once`() {
        val f = HeartRatePlausibilityFilter(180)
        f.feed(0, 150, 150, 150, 150, 150, 150, 150, 150, 150, 150)
        assertEquals(listOf(null, null, 151, 151), f.feed(10_000, 120, 151))
    }
}
