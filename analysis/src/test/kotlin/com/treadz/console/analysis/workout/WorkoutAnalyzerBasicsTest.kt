package com.treadz.console.analysis.workout

import com.treadz.console.analysis.fitness.Sex
import com.treadz.console.analysis.samples.SampleHeartRateSource
import com.treadz.console.analysis.samples.WorkoutSample
import com.treadz.console.analysis.workout.WorkoutFixtures.context
import com.treadz.console.analysis.workout.WorkoutFixtures.session
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.exp

class WorkoutAnalyzerBasicsTest {

    private fun analyse(samples: List<WorkoutSample>, ctx: WorkoutAnalysisContext = context()) = WorkoutAnalyzer.analyse(samples, ctx)

    // ------------------------------------------------------------------ energy

    @Test
    fun `energy of a constant walk matches the ACSM equation`() {
        val a = analyse(session(600, speed = { 5.0 }), context(vo2max = 40.0))
        assertEquals(WorkoutAnalyzer.ALGORITHM_VERSION, a.algorithmVersion)
        val e = a.energy.value!!
        assertEquals(600, e.movingSeconds)
        // VO2 = 3.5 + 0.1 * 83.333 = 11.8333
        assertEquals(11.8333, e.meanVo2MlKgMin, 1e-3)
        assertEquals(11.8333 / 3.5, e.meanMets, 1e-3)
        assertEquals(11.8333 / 40.0 * 100.0, e.percentOfVo2max!!, 1e-2)
        // 11.8333 ml/kg/min * 70 kg = 828.33 ml/min = 0.82833 L/min * 5 kcal/L = 4.1417 kcal/min, 10 min.
        assertEquals(41.4167, e.grossKcal!!, 1e-2)
        // Net: (11.8333 - 3.5) * 70 / 1000 * 5 = 2.9167 kcal/min.
        assertEquals(29.1667, e.netKcal!!, 1e-2)
        assertEquals(5.0, e.meanSpeedKmh, 1e-9)
        assertEquals(5.0 * 600 / 3600, e.distanceKm, 1e-9)
    }

    @Test
    fun `energy uses the walking or running equation second by second`() {
        // 5 min at 7 km/h (walking: 15.1667) and 5 min at 8 km/h (running: 3.5 + 0.2 * 133.33 = 30.1667).
        val s = session(600, speed = { if (it < 300) 7.0 else 8.0 })
        val e = analyse(s).energy.value!!
        assertEquals((15.1667 + 30.1667) / 2, e.meanVo2MlKgMin, 1e-3)
    }

    @Test
    fun `running with incline`() {
        // 10 km/h and 1 %: 3.5 + 0.2 * 166.667 + 0.9 * 166.667 * 0.01 = 38.3333
        val e = analyse(session(120, speed = { 10.0 }, incline = { 1.0 })).energy.value!!
        assertEquals(38.3333, e.meanVo2MlKgMin, 1e-3)
    }

    @Test
    fun `without weight only VO2 and METs`() {
        val e = analyse(session(300), context(weightKg = null)).energy.value!!
        assertNull(e.grossKcal)
        assertNull(e.netKcal)
        assertEquals(11.8333, e.meanVo2MlKgMin, 1e-3)
        assertNull(e.percentOfVo2max)
    }

    @Test
    fun `a stopped belt does not count`() {
        // 5 min walking, 2 min stopped (pause, heart rate still recorded), 5 min walking.
        val s = session(720, speed = { if (it in 300 until 420) 0.0 else 5.0 })
        val a = analyse(s)
        assertEquals(720, a.signal.coveredSeconds)
        assertEquals(600, a.signal.movingSeconds)
        assertEquals(600, a.energy.value!!.movingSeconds)
        assertEquals(11.8333, a.energy.value!!.meanVo2MlKgMin, 1e-3)
        assertEquals(600, a.zoneTime.value!!.analysedSeconds)
    }

    @Test
    fun `a gap longer than the hold time is not counted`() {
        // 300 samples, 60 s without samples, 300 samples. The sample before the gap holds for 10 s only.
        val s = session(300) + session(300, start = WorkoutFixtures.T0 + 360_000L)
        val a = analyse(s)
        assertEquals(600, s.size)
        // 299 + 10 + 299 + 1 (the last sample holds for the interval before it)
        assertEquals(609, a.signal.coveredSeconds)
        assertEquals(51, a.signal.gapSeconds)
        assertEquals(609, a.energy.value!!.movingSeconds)
    }

    @Test
    fun `a short silence is bridged by sample-and-hold`() {
        // One sample every 5 s: every interval is held completely.
        val a = analyse(session(300, step = 5))
        assertEquals(300, a.signal.coveredSeconds)
        assertEquals(0, a.signal.gapSeconds)
    }

    @Test
    fun `samples every 3 seconds without RR and with unknown source`() {
        // One 3 s sample at 141 so the unknown-source reading is not a frozen one (> 5 min unchanged).
        val s = session(600, step = 3, source = SampleHeartRateSource.Unknown, heartRate = { if (it == 300) 141 else 140 })
        val a = analyse(s)
        assertEquals(600, a.signal.coveredSeconds)
        assertEquals(HeartRateSourceMix(0, 0, 600), a.signal.heartRateSources)
        assertEquals(0, a.signal.rrBeats)
        assertNull(a.signal.rrArtifactPercent)
        assertNotNull(a.energy.value)
        assertNotNull(a.zoneTime.value)
        assertNotNull(a.trimp.value)
        assertNotNull(a.efficiency.value)
        assertEquals(AnalysisReason.NoRrIntervals, a.alpha1.reason)
        assertEquals(15.346, a.trimp.value!!.trimp, 1e-2)
    }

    @Test
    fun `console heart rate never gives alpha1 even with RR intervals attached`() {
        val s = session(300, source = SampleHeartRateSource.Console).map { it.copy(rrIntervalsMs = listOf(800)) }
        val a = analyse(s)
        assertEquals(AnalysisReason.NoRrIntervals, a.alpha1.reason)
        assertNotNull(a.trimp.value)
    }

    @Test
    fun `without heart rate only the energy`() {
        val a = analyse(session(600, heartRate = { null }))
        assertNotNull(a.energy.value)
        for (reason in listOf(a.zoneTime.reason, a.trimp.reason, a.efficiency.reason, a.decoupling.reason, a.fitnessIndex.reason, a.recovery.reason, a.alpha1.reason)) {
            assertEquals(AnalysisReason.NoHeartRate, reason)
        }
        assertEquals(0.0, a.signal.heartRateCoverage!!, 1e-12)
        assertEquals(HeartRateSourceMix(0, 0, 0), a.signal.heartRateSources)
    }

    @Test
    fun `heart rate below the coverage is not used`() {
        // Heart rate in 70 % of the moving seconds.
        val a = analyse(session(1000, heartRate = { if (it % 10 < 7) 130 else null }))
        assertEquals(0.7, a.signal.heartRateCoverage!!, 1e-9)
        assertEquals(AnalysisReason.LowHeartRateCoverage, a.trimp.reason)
        assertEquals(AnalysisReason.LowHeartRateCoverage, a.zoneTime.reason)
        assertNotNull(a.energy.value)
    }

    @Test
    fun `heart rate at 80 percent is enough`() {
        val a = analyse(session(1000, heartRate = { if (it % 10 < 8) 130 else null }))
        assertNotNull(a.trimp.value)
    }

    @Test
    fun `a frozen heart rate is missing`() {
        // 2 min of a real signal, then the same value for 10 min (a console repeating its last reading).
        val a = analyse(session(720, heartRate = { if (it < 120) 100 + it % 7 else 104 }, source = SampleHeartRateSource.Console))
        assertEquals(600, a.signal.frozenHeartRateSeconds)
        assertEquals(120, a.signal.heartRateSeconds)
        assertEquals(AnalysisReason.LowHeartRateCoverage, a.trimp.reason)
    }

    @Test
    fun `a steady heart rate for a few minutes is kept`() {
        val a = analyse(session(600, heartRate = { if (it < 300) 130 else 131 }, source = SampleHeartRateSource.Console))
        assertEquals(0, a.signal.frozenHeartRateSeconds)
        assertNotNull(a.trimp.value)
        // A strap is never taken as frozen.
        assertEquals(0, analyse(session(720, heartRate = { 104 })).signal.frozenHeartRateSeconds)
    }

    @Test
    fun `implausible heart rates are missing`() {
        val a = analyse(session(300, heartRate = { 300 }))
        assertEquals(AnalysisReason.NoHeartRate, a.trimp.reason)
    }

    @Test
    fun `no samples, one sample, belt never moving`() {
        val empty = analyse(emptyList())
        assertEquals(AnalysisReason.NoSamples, empty.energy.reason)
        assertEquals(AnalysisReason.NoSamples, empty.alpha1.reason)
        assertEquals(0, empty.signal.sampleCount)
        assertNull(empty.signal.heartRateCoverage)
        assertEquals(AnalysisReason.NoSamples, analyse(session(1)).energy.reason)
        val stopped = analyse(session(300, speed = { 0.0 }))
        assertEquals(AnalysisReason.NoBeltMovement, stopped.energy.reason)
        assertEquals(AnalysisReason.NoBeltMovement, stopped.trimp.reason)
        assertEquals(300, stopped.signal.coveredSeconds)
    }

    @Test
    fun `corrupt timestamps are rejected without an exception`() {
        val s = listOf(WorkoutSample(0L, 5.0, 0.0), WorkoutSample(10L * 24 * 3600 * 1000, 5.0, 0.0))
        // The first sample holds for 10 s, the second for the same: the span is 10 days + 10 s.
        assertEquals(AnalysisReason.ImplausibleDuration, analyse(s).energy.reason)
    }

    @Test
    fun `unsorted samples are sorted`() {
        val s = session(300)
        assertEquals(analyse(s), analyse(s.reversed()))
    }

    @Test
    fun `invalid maximum heart rate`() {
        val a = analyse(session(300), context(max = 0))
        assertEquals(AnalysisReason.InvalidMaxHeartRate, a.trimp.reason)
        assertEquals(AnalysisReason.InvalidMaxHeartRate, a.fitnessIndex.reason)
        assertNotNull(a.energy.value)
        assertNotNull(a.efficiency.value)
    }

    // ------------------------------------------------------------------ signal

    @Test
    fun `heart-rate source mix`() {
        val s = session(100, source = SampleHeartRateSource.Strap) + session(100, start = WorkoutFixtures.T0 + 100_000L, source = SampleHeartRateSource.Console)
        val a = analyse(s)
        assertEquals(HeartRateSourceMix(100, 100, 0), a.signal.heartRateSources)
        assertEquals(1.0, a.signal.heartRateCoverage!!, 1e-9)
    }

    // ------------------------------------------------------------------ zones

    @Test
    fun `time in zones with the zones used`() {
        // Zones for max 180 and rest 60 (low level): Z1 < 114, Z2 114-126, Z3 126-150, Z4 150-168, Z5 from 168.
        val hr = { t: Int ->
            when {
                t < 240 -> 100
                t < 420 -> 120
                t < 540 -> 140
                t < 600 -> 160
                else -> 175
            }
        }
        val z = analyse(session(660, heartRate = hr)).zoneTime.value!!
        assertEquals(listOf(240, 180, 120, 60, 60), z.secondsInZone)
        assertEquals(660, z.analysedSeconds)
        assertEquals(WorkoutFixtures.zones, z.zones)
    }

    @Test
    fun `zone boundaries belong to the zone above`() {
        val hrs = listOf(113, 114, 125, 126, 149, 150, 167, 168, 200)
        val s = hrs.flatMap { h -> session(10, heartRate = { h }, start = WorkoutFixtures.T0 + 10_000L * hrs.indexOf(h)) }
        val z = analyse(s).zoneTime.value!!
        assertEquals(listOf(10, 20, 20, 20, 20), z.secondsInZone)
    }

    @Test
    fun `no zones in the context`() {
        assertEquals(AnalysisReason.NoZones, analyse(session(300), context(zones = null)).zoneTime.reason)
    }

    // ------------------------------------------------------------------ TRIMP

    @Test
    fun `TRIMP of a man`() {
        // HRr = (140 - 60) / 120 = 0.6667; per minute 0.6667 * 0.64 * e^(1.92 * 0.6667) = 1.5346; 10 minutes.
        val t = analyse(session(600, heartRate = { 140 })).trimp.value!!
        assertEquals(15.346, t.trimp, 1e-2)
        assertEquals(10.0, t.minutes, 1e-9)
        assertEquals(60, t.restingHeartRateBpm)
        assertFalse(t.restingAssumed)
    }

    @Test
    fun `TRIMP of a woman`() {
        // 0.6667 * 0.86 * e^(1.67 * 0.6667) = 1.7455 per minute.
        val t = analyse(session(600, heartRate = { 140 }), context(sex = Sex.Female)).trimp.value!!
        assertEquals(17.455, t.trimp, 1e-2)
    }

    @Test
    fun `TRIMP matches the formula for a changing heart rate`() {
        val s = session(600, heartRate = { 100 + it / 10 })
        var expected = 0.0
        for (t in 0 until 600) {
            val hrr = ((100 + t / 10) - 60) / 120.0
            expected += hrr * 0.64 * exp(1.92 * hrr) / 60.0
        }
        assertEquals(expected, analyse(s).trimp.value!!.trimp, 1e-9)
    }

    @Test
    fun `TRIMP without resting heart rate assumes a third of the maximum and says so`() {
        val withRest = analyse(session(600, heartRate = { 140 })).trimp.value!!
        val t = analyse(session(600, heartRate = { 140 }), context(resting = null)).trimp.value!!
        assertTrue(t.restingAssumed)
        assertEquals(60, t.restingHeartRateBpm)
        assertEquals(withRest.trimp, t.trimp, 1e-9)
    }

    @Test
    fun `TRIMP limits the reserve fraction to 0-1`() {
        // A heart rate above the maximum counts as the maximum; below resting as zero.
        val high = analyse(session(60, heartRate = { 200 })).trimp.value!!.trimp
        assertEquals(1.0 * 0.64 * exp(1.92), high, 1e-9)
        assertEquals(0.0, analyse(session(60, heartRate = { 50 })).trimp.value!!.trimp, 1e-12)
    }

    // ------------------------------------------------------------------ efficiency

    @Test
    fun `efficiency factor`() {
        // 6 km/h and 5 %: VO2 = 3.5 + 10 + 9 = 22.5; EF = 22.5 / 120.
        val e = analyse(session(600, speed = { 6.0 }, incline = { 5.0 })).efficiency.value!!
        assertEquals(22.5, e.meanVo2MlKgMin, 1e-9)
        assertEquals(120.0, e.meanHeartRateBpm, 1e-9)
        assertEquals(22.5 / 120.0, e.efficiencyFactor, 1e-9)
        assertEquals(600, e.seconds)
    }
}
