package com.treadz.console.treadmill.control

import com.treadz.console.treadmill.SafetyLimits
import com.treadz.console.treadmill.ValueRange
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class SafetyClampTest {
    private val speed = ValueRange(0.5, 20.0, 0.1)
    private val incline = ValueRange(0.0, 18.0, 1.0)
    private val none = SafetyLimits()

    @Test
    fun `within range it does not change`() {
        assertEquals(3.0, SafetyClamp.speed(3.0, speed, none))
        assertEquals(12.3, SafetyClamp.speed(12.3, speed, none))
        assertEquals(5.0, SafetyClamp.incline(5.0, incline, none))
    }

    @Test
    fun `it is clamped to the treadmill range`() {
        assertEquals(0.5, SafetyClamp.speed(0.0, speed, none))
        assertEquals(0.5, SafetyClamp.speed(-4.0, speed, none))
        assertEquals(20.0, SafetyClamp.speed(99.0, speed, none))
        assertEquals(0.0, SafetyClamp.incline(-3.0, incline, none))
        assertEquals(18.0, SafetyClamp.incline(40.0, incline, none))
    }

    @Test
    fun `it is clamped to the safety limits`() {
        val limits = SafetyLimits(maxSpeedKmh = 8.0, maxInclinePercent = 6.0)
        assertEquals(8.0, SafetyClamp.speed(12.0, speed, limits))
        assertEquals(7.9, SafetyClamp.speed(7.9, speed, limits))
        assertEquals(6.0, SafetyClamp.incline(10.0, incline, limits))
    }

    @Test
    fun `a limit below the treadmill minimum does not request a speed the treadmill does not accept`() {
        assertEquals(0.5, SafetyClamp.speed(5.0, speed, SafetyLimits(maxSpeedKmh = 0.2)))
        assertEquals(0.0, SafetyClamp.incline(5.0, incline, SafetyLimits(maxInclinePercent = -1.0)))
    }

    @Test
    fun `it snaps to the treadmill step without ever skipping the limit`() {
        assertEquals(3.1, SafetyClamp.speed(3.14, speed, none))
        assertEquals(3.2, SafetyClamp.speed(3.16, speed, none))
        assertEquals(5.0, SafetyClamp.incline(4.6, incline, none))
        assertEquals(4.0, SafetyClamp.incline(4.4, incline, none))
        // 7.96 would round to 8.0, which exceeds the 7.97 limit with step 0.1 -> 7.9
        assertEquals(7.9, SafetyClamp.speed(7.96, speed, SafetyLimits(maxSpeedKmh = 7.97)))
    }

    @Test
    fun `it leaves no floating point noise`() {
        assertEquals(3.0, SafetyClamp.speed(2.9999999999, speed, none))
        assertEquals(1.1, SafetyClamp.speed(0.5 + 0.6, speed, none))
    }
}
