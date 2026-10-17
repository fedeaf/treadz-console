package com.treadz.console.analysis.workout

import com.treadz.console.analysis.workout.WorkoutFixtures.context
import com.treadz.console.analysis.workout.WorkoutFixtures.session
import com.treadz.console.analysis.samples.WorkoutSample
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Test

class WorkoutAnalyzerRecoveryTest {

    private fun analyse(samples: List<WorkoutSample>) = WorkoutAnalyzer.analyse(samples, context())

    /** Heart rate rising from 120 to 149 bpm in 10 min, then dropping to 140 and falling by 1 bpm every 2 s. */
    private fun effortThenRecovery(t: Int): Int = if (t < 600) 120 + t / 20 else 140 - (t - 600) / 2

    @Test
    fun `recovery with the belt stopped`() {
        val s = session(730, speed = { if (it < 600) 10.0 else 0.0 }, heartRate = ::effortThenRecovery)
        val r = analyse(s).recovery.value!!
        // Peak: mean of seconds 570..599 = (10 * 148 + 20 * 149) / 30.
        assertEquals(148.667, r.peakHeartRateBpm, 1e-3)
        assertEquals(WorkoutFixtures.T0 + 600_000L, r.peakEndMillis)
        // 60 s after second 599: mean of the heart rate at 657..661 = 110.8; at 717..721: 80.8.
        assertEquals(148.667 - 110.8, r.drop60Bpm!!, 1e-3)
        assertEquals(148.667 - 80.8, r.drop120Bpm!!, 1e-3)
        assertEquals(RecoveryContext.BeltStopped, r.context)
        assertEquals(3.5, r.meanVo2MlKgMin, 1e-9)
    }

    @Test
    fun `recovery walking after the effort`() {
        // After the 10 km/h effort the belt goes on at 4 km/h: VO2 = 3.5 + 0.1 * 66.667 = 10.1667.
        val s = session(730, speed = { if (it < 600) 10.0 else 4.0 }, heartRate = ::effortThenRecovery)
        val r = analyse(s).recovery.value!!
        assertEquals(RecoveryContext.BeltMoving, r.context)
        assertEquals(10.1667, r.meanVo2MlKgMin, 1e-3)
        assertEquals(148.667 - 110.8, r.drop60Bpm!!, 1e-3)
    }

    @Test
    fun `recovery works with samples every 3 seconds`() {
        val s = session(732, step = 3, speed = { if (it < 600) 10.0 else 0.0 }, heartRate = ::effortThenRecovery)
        val r = analyse(s).recovery.value!!
        assertEquals(148.667, r.peakHeartRateBpm, 0.6)
        assertEquals(37.9, r.drop60Bpm!!, 2.0)
    }

    @Test
    fun `only the 60 s recovery when the data ends before 120 s`() {
        val s = session(680, speed = { if (it < 600) 10.0 else 0.0 }, heartRate = ::effortThenRecovery)
        val r = analyse(s).recovery.value!!
        assertNotNull(r.drop60Bpm)
        assertEquals(null, r.drop120Bpm)
    }

    @Test
    fun `no recovery when the load does not drop`() {
        // Constant 8 km/h; the heart rate peaks at 400 s and falls afterwards (e.g. a fitter second half).
        val s = session(800, speed = { 8.0 }, heartRate = { if (it < 400) 100 + it / 10 else 139 - (it - 400) / 10 })
        assertEquals(AnalysisReason.NoLoadDrop, analyse(s).recovery.reason)
    }

    @Test
    fun `no recovery when the data ends at the peak`() {
        val s = session(300, speed = { 10.0 }, heartRate = { 120 + it / 10 })
        assertEquals(AnalysisReason.NoRecoveryData, analyse(s).recovery.reason)
    }

    @Test
    fun `no recovery when the peak happens at a very low load`() {
        // 4 km/h flat walking has a net demand of 6.7, so use an almost nil one: 1 km/h.
        val s = session(730, speed = { if (it < 600) 1.0 else 0.0 }, heartRate = ::effortThenRecovery)
        assertEquals(AnalysisReason.NoLoadDrop, analyse(s).recovery.reason)
    }

    @Test
    fun `recovery counts from the end of the effort when the heart rate peaks after it`() {
        // Effort until 600 s; the heart rate keeps rising for 15 s after the load drops (lag), then falls 1 bpm per 2 s.
        val hr = { t: Int ->
            when {
                t < 600 -> 120 + t / 20 // 149 at the end
                t < 615 -> 150
                else -> 150 - (t - 615) / 2
            }
        }
        val s = session(760, speed = { if (it < 600) 10.0 else 0.0 }, heartRate = hr)
        val r = analyse(s).recovery.value!!
        assertEquals(WorkoutFixtures.T0 + 600_000L, r.peakEndMillis)
        // Last 30 s of the effort (570..599), not the later 30 s around the heart-rate maximum.
        assertEquals(148.667, r.peakHeartRateBpm, 1e-3)
        // 60 s after second 599: mean at 657..661 = 150 - (42..46) / 2 → 128.4 (integer halves: 129,129,128,128,127 → 128.2).
        assertEquals(148.667 - 128.2, r.drop60Bpm!!, 1e-3)
    }

    @Test
    fun `recovery is measured although the strap was connected late`() {
        // No heart rate during the first 400 s (global coverage 33 %), then a clean effort and recovery.
        val hr = { t: Int -> if (t < 400) null else effortThenRecovery(t) }
        val a = analyse(session(730, speed = { if (it < 600) 10.0 else 0.0 }, heartRate = hr))
        assertEquals(AnalysisReason.LowHeartRateCoverage, a.trimp.reason)
        assertEquals(148.667 - 110.8, a.recovery.value!!.drop60Bpm!!, 1e-3)
    }

    @Test
    fun `recovery after the last of several efforts`() {
        // Two efforts; the second reaches the higher peak.
        val hr = { t: Int ->
            when {
                t < 200 -> 120 + t / 10
                t < 300 -> 140 - (t - 200) / 5
                t < 500 -> 120 + (t - 300) / 5 // up to 159
                else -> 140 - (t - 500) / 2
            }
        }
        val s = session(700, speed = { if (it in 0 until 200 || it in 300 until 500) 10.0 else 4.0 }, heartRate = hr)
        val r = analyse(s).recovery.value!!
        // The peak is the end of the second effort (second 499).
        assertEquals(WorkoutFixtures.T0 + 500_000L, r.peakEndMillis)
        assertEquals(RecoveryContext.BeltMoving, r.context)
    }
}
