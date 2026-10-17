package com.treadz.console.analysis.workout

import com.treadz.console.analysis.samples.RrInterval
import com.treadz.console.analysis.thresholds.SyntheticRr
import com.treadz.console.analysis.workout.WorkoutFixtures.context
import com.treadz.console.analysis.workout.WorkoutFixtures.session
import com.treadz.console.analysis.workout.WorkoutFixtures.withRr
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

class WorkoutAnalyzerAlpha1Test {

    private fun rr(alpha: Double, seconds: Int = 600, hr: Double = 110.0, seed: Long = 5) =
        SyntheticRr.ramp(WorkoutFixtures.T0, seconds, hr, hr, alpha, alpha, seed = seed)

    private fun analyse(rr: List<RrInterval>, seconds: Int = 600, speed: (Int) -> Double = { 5.0 }) =
        WorkoutAnalyzer.analyse(withRr(session(seconds, speed = speed, heartRate = { 110 }), rr), context())

    @Test
    fun `aerobic session has its time above the cut`() {
        val a = analyse(rr(1.1))
        val s = a.alpha1.value!!
        // Windows of 120 s every 10 s over ~600 s of beats: 49 of them.
        assertTrue(s.validWindows in 45..49, "valid windows ${s.validWindows}")
        assertEquals(s.totalWindows, s.validWindows)
        assertEquals(s.validWindows * 10, s.secondsAtOrAbove + s.secondsBelow)
        assertTrue(s.secondsAtOrAbove >= 0.9 * s.validWindows * 10, "above ${s.secondsAtOrAbove}")
        assertTrue(s.meanAlpha1 > 0.85, "mean ${s.meanAlpha1}")
        assertTrue(a.signal.rrBeats > 1000)
        assertTrue(a.signal.rrArtifactPercent!! < 2.0)
        assertEquals(HeartRateSourceMix(600, 0, 0), a.signal.heartRateSources)
    }

    @Test
    fun `hard session has its time below the cut`() {
        val s = analyse(rr(0.45)).alpha1.value!!
        assertTrue(s.secondsBelow >= 0.9 * s.validWindows * 10, "below ${s.secondsBelow}")
        assertTrue(s.meanAlpha1 < 0.75, "mean ${s.meanAlpha1}")
    }

    @Test
    fun `windows with more than 5 percent artifacts do not count`() {
        // Every 8th interval is impossible (150 ms): 12.5 % artifacts in every window.
        val noisy = rr(1.1).mapIndexed { i, r -> if (i % 8 == 7) r.copy(rrMs = 150) else r }
        val a = analyse(noisy)
        assertEquals(AnalysisReason.NoValidWindows, a.alpha1.reason)
        assertTrue(a.signal.rrArtifactPercent!! in 12.0..13.5, "artifacts ${a.signal.rrArtifactPercent}")
    }

    @Test
    fun `a few artifacts leave the clean windows`() {
        // One artifact every 40 beats: 2.5 %, below the 5 % limit.
        val some = rr(1.1).mapIndexed { i, r -> if (i % 40 == 39) r.copy(rrMs = 150) else r }
        val s = analyse(some).alpha1.value!!
        assertTrue(s.validWindows >= 40)
    }

    @Test
    fun `windows with the belt stopped do not count`() {
        // The belt is stopped during the first 300 s.
        val s = analyse(rr(1.1), speed = { if (it < 300) 0.0 else 5.0 }).alpha1.value!!
        assertTrue(s.totalWindows in 19..22, "total ${s.totalWindows}")
        assertTrue(s.validWindows <= s.totalWindows)
    }

    @Test
    fun `no RR from the strap`() {
        assertEquals(AnalysisReason.NoRrIntervals, WorkoutAnalyzer.analyse(session(600), context()).alpha1.reason)
    }

    @Test
    fun `too few beats for a window`() {
        // 40 bpm: 80 beats in 120 s.
        val s = analyse(rr(1.1, hr = 40.0)).alpha1
        assertEquals(AnalysisReason.NoValidWindows, s.reason)
    }

    @Test
    fun `other sections still work with RR`() {
        val a = analyse(rr(1.1))
        assertNotNull(a.energy.value)
        assertNotNull(a.trimp.value)
    }
}
