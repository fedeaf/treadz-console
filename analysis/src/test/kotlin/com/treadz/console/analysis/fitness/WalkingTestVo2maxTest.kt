package com.treadz.console.analysis.fitness

import com.treadz.console.analysis.samples.WorkoutSample
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class WalkingTestVo2maxTest {

    @Test
    fun `from the stages - 42 s at 16 percent and 18 s at 17 percent at 6,2 km per h`() {
        val stages = (6..16).map { StageLoad(6.2, it.toDouble(), 60) } + StageLoad(6.2, 17.0, 18)
        val e = WalkingTestVo2max.fromStages(stages)!!
        val s = 6200.0 / 60.0
        val expected = (42 * Acsm.walkingVo2(6.2, 16.0) + 18 * Acsm.walkingVo2(6.2, 17.0)) / 60
        assertEquals(expected, e.vo2maxMlKgMin, 1e-9)
        assertEquals(3.5 + 0.1 * s + 1.8 * s * (42 * 0.16 + 18 * 0.17) / 60, e.vo2maxMlKgMin, 1e-9)
        assertEquals(44.15, e.vo2maxMlKgMin, 0.01)
        assertTrue(e.fromStages)
        assertFalse(e.running)
        assertEquals(60.0, e.coveredSeconds)
    }

    @Test
    fun `from the samples - the mean real load of the last minute, whatever the sample rate`() {
        // The incline really reached 16 % 20 s before the end (the motor lags the stage change).
        val end = 1_000_000L
        val oneHz = (0..120).map { s -> WorkoutSample(end - 120_000 + s * 1000L, 6.2, if (s < 100) 15.0 else 16.0) }
        val twoHz = (0..240).map { h -> WorkoutSample(end - 120_000 + h * 500L, 6.2, if (h < 200) 15.0 else 16.0) }
        val a = WalkingTestVo2max.fromSamples(oneHz, end)!!
        val b = WalkingTestVo2max.fromSamples(twoHz, end)!!
        val expected = (40 * Acsm.walkingVo2(6.2, 15.0) + 20 * Acsm.walkingVo2(6.2, 16.0)) / 60
        assertEquals(expected, a.vo2maxMlKgMin, 1e-9)
        assertEquals(expected, b.vo2maxMlKgMin, 1e-9)
        assertEquals(15.0 + 1.0 / 3, a.inclinePercent, 1e-9)
        assertFalse(a.fromStages)
    }

    @Test
    fun `above 7,5 km per h on average the running equation is used`() {
        val end = 100_000L
        val samples = (0..60).map { WorkoutSample(end - 60_000 + it * 1000L, 8.0, 5.0) }
        val e = WalkingTestVo2max.fromSamples(samples, end)!!
        assertTrue(e.running)
        assertEquals(Acsm.runningVo2(8.0, 5.0), e.vo2maxMlKgMin, 1e-9)
    }

    @Test
    fun `less than 30 s of samples in the window gives no estimate`() {
        val end = 100_000L
        assertNull(WalkingTestVo2max.fromSamples(listOf(WorkoutSample(end - 20_000, 6.0, 10.0)), end))
        assertNull(WalkingTestVo2max.fromSamples(emptyList(), end))
        assertNull(WalkingTestVo2max.fromStages(emptyList()))
    }
}
