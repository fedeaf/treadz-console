package com.treadz.console.analysis.workout

import com.treadz.console.analysis.workout.WorkoutFixtures.context
import com.treadz.console.analysis.workout.WorkoutFixtures.session
import com.treadz.console.analysis.samples.WorkoutSample
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class WorkoutAnalyzerAerobicTest {

    private fun analyse(samples: List<WorkoutSample>, ctx: WorkoutAnalysisContext = context()) = WorkoutAnalyzer.analyse(samples, ctx)

    // ------------------------------------------------------------------ fitness index

    @Test
    fun `fitness index of one steady stretch`() {
        // 6 km/h, 5 %: VO2 22.5; HRr = (120 - 60) / 120 = 0.5; VO2max = 3.5 + 19 / 0.5 = 41.5.
        val f = analyse(session(600, speed = { 6.0 }, incline = { 5.0 }, heartRate = { 120 })).fitnessIndex.value!!
        assertEquals(41.5, f.vo2maxEstimate, 1e-9)
        assertEquals(1, f.stretchCount)
        assertEquals(1, f.stretchesFound)
        assertEquals(listOf(41.5), f.estimates)
        assertFalse(f.restingAssumed)
    }

    @Test
    fun `fitness index uses only the last 60 percent of the stretch`() {
        // The first 4 of 10 minutes at 100 bpm (heart rate still settling), the last 6 at 120 bpm.
        val f = analyse(session(600, speed = { 6.0 }, incline = { 5.0 }, heartRate = { if (it < 240) 100 else 120 })).fitnessIndex.value!!
        assertEquals(41.5, f.vo2maxEstimate, 1e-9)
    }

    @Test
    fun `fitness index is the median of the stretches with a usable reserve fraction`() {
        // Four stretches of 5 min, different incline (more than 1 % apart).
        //  A 5 km/h  0 %, 100 bpm: HRr 0.333, out of range
        //  B 6 km/h  5 %, 120 bpm: VO2 22.5 -> 41.5
        //  C 6 km/h  8 %, 135 bpm: VO2 27.9 -> 3.5 + 24.4 / 0.625 = 42.54
        //  D 6 km/h 10 %, 150 bpm: VO2 31.5 -> 3.5 + 28 / 0.75 = 40.8333
        val inc = listOf(0.0, 5.0, 8.0, 10.0)
        val sp = listOf(5.0, 6.0, 6.0, 6.0)
        val hr = listOf(100, 120, 135, 150)
        val s = session(1200, speed = { sp[it / 300] }, incline = { inc[it / 300] }, heartRate = { hr[it / 300] })
        val f = analyse(s).fitnessIndex.value!!
        assertEquals(41.5, f.vo2maxEstimate, 1e-9)
        assertEquals(3, f.stretchCount)
        assertEquals(4, f.stretchesFound)
        assertEquals(41.5, f.estimates[0], 1e-9)
        assertEquals(42.54, f.estimates[1], 1e-9)
        assertEquals(3.5 + 28.0 / 0.75, f.estimates[2], 1e-9)
    }

    @Test
    fun `fitness index with an even number of stretches is the mean of the middle two`() {
        val inc = listOf(5.0, 8.0)
        val hr = listOf(120, 135)
        val s = session(600, speed = { 6.0 }, incline = { inc[it / 300] }, heartRate = { hr[it / 300] })
        assertEquals((41.5 + 42.54) / 2, analyse(s).fitnessIndex.value!!.vo2maxEstimate, 1e-9)
    }

    @Test
    fun `no fitness index without a long steady stretch or a usable reserve fraction`() {
        // The incline changes every 2 min: no stretch of 3 min.
        val short = session(1200, speed = { 6.0 }, incline = { 3.0 + 2 * (it / 120) }, heartRate = { 120 })
        assertEquals(AnalysisReason.NoSteadyStretch, analyse(short).fitnessIndex.reason)
        // 175 bpm is 96 % of the reserve.
        assertEquals(AnalysisReason.NoSteadyStretch, analyse(session(600, heartRate = { 175 })).fitnessIndex.reason)
        // A walk shorter than 3 minutes is no stretch.
        assertEquals(AnalysisReason.NoSteadyStretch, analyse(session(170)).fitnessIndex.reason)
    }

    @Test
    fun `fitness index flags an assumed resting heart rate`() {
        val f = analyse(session(600, speed = { 6.0 }, incline = { 5.0 }, heartRate = { 120 }), context(resting = null)).fitnessIndex.value!!
        assertTrue(f.restingAssumed)
        assertEquals(41.5, f.vo2maxEstimate, 1e-9)
    }

    @Test
    fun `a pause splits the stretches`() {
        val s = session(900, speed = { if (it in 300 until 330) 0.0 else 6.0 }, incline = { 5.0 }, heartRate = { 120 })
        // 300 s, pause, 570 s: two stretches.
        assertEquals(2, analyse(s).fitnessIndex.value!!.stretchesFound)
    }
}
