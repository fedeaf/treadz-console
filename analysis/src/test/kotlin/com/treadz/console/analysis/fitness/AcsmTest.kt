package com.treadz.console.analysis.fitness

import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Test

class AcsmTest {

    @Test
    fun `ACSM equations give the known values`() {
        // 5 km/h = 83.33 m/min, 10 %: 3.5 + 8.333 + 15.0
        assertEquals(26.833, Acsm.walkingVo2(5.0, 10.0), 0.001)
        // 10 km/h = 166.67 m/min, 1 %: 3.5 + 33.333 + 1.5
        assertEquals(38.333, Acsm.runningVo2(10.0, 1.0), 0.001)
        // Flat walking at 4.8 km/h (80 m/min): 3.5 + 8.0
        assertEquals(11.5, Acsm.walkingVo2(4.8, 0.0), 1e-9)
        // 12 km/h (200 m/min) flat: 3.5 + 40
        assertEquals(43.5, Acsm.runningVo2(12.0, 0.0), 1e-9)
    }

    @Test
    fun `the walking inverses solve the walking equation`() {
        // 26.833 at 5 km/h is the 10 % of the first case.
        assertEquals(0.10, Acsm.walkingGradeFor(Acsm.walkingVo2(5.0, 10.0), 5.0), 1e-9)
        // Below flat walking the grade is negative.
        assertEquals(true, Acsm.walkingGradeFor(10.0, 5.0) < 0.0)
        // 11.5 flat is 4.8 km/h; 26.833 at 10 % is 5 km/h.
        assertEquals(4.8, Acsm.walkingSpeedFor(11.5, 0.0), 1e-9)
        assertEquals(5.0, Acsm.walkingSpeedFor(Acsm.walkingVo2(5.0, 10.0), 0.10), 1e-9)
    }

    @Test
    fun `the speed step equivalent to one percent of grade`() {
        // ds = 1.8 * 91.667 * 0.01 / (0.1 + 1.8 * 0.18) = 3.89 m/min = 0.233 km/h, rounded to 0.2.
        assertEquals(0.2, Acsm.walkingSpeedStepForGrade(5.5, 18.0), 1e-9)
        // Flat: 1.8 * 83.33 * 0.01 / 0.1 = 15 m/min = 0.9 km/h.
        assertEquals(0.9, Acsm.walkingSpeedStepForGrade(5.0, 0.0), 1e-9)
        // A larger grade step scales the speed step, but it is never below 0.1 km/h.
        assertEquals(0.5, Acsm.walkingSpeedStepForGrade(5.5, 18.0, 2.0), 1e-9)
        assertEquals(0.1, Acsm.walkingSpeedStepForGrade(3.0, 30.0, 0.1), 1e-9)
    }
}
