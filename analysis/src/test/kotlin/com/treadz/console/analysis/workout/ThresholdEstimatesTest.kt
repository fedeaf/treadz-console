package com.treadz.console.analysis.workout

import com.treadz.console.analysis.samples.RrInterval
import com.treadz.console.analysis.samples.SampleHeartRateSource
import com.treadz.console.analysis.samples.WorkoutSample
import com.treadz.console.analysis.thresholds.SyntheticRr
import com.treadz.console.analysis.workout.WorkoutFixtures.T0
import com.treadz.console.analysis.workout.WorkoutFixtures.context
import com.treadz.console.analysis.workout.WorkoutFixtures.session
import com.treadz.console.analysis.workout.WorkoutFixtures.withRr
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The thresholds implied by the stable stretches of a workout (synthetic data only). */
class ThresholdEstimatesTest {

    private val slope = -0.02

    private fun ctx(slope: Double? = this.slope, vt2: Double? = 150.0) = context().copy(alpha1SlopePerBpm = slope, vt2Bpm = vt2)

    /** RR of [seconds] from [from] at a steady [hr] and [alpha]. */
    private fun rr(from: Long, seconds: Int, hr: Double, alpha: Double, seed: Long = 3) =
        SyntheticRr.ramp(from, seconds, hr, hr, alpha, alpha, seed = seed)

    private fun analyse(
        seconds: Int,
        rr: List<RrInterval>,
        speed: (Int) -> Double = { 5.0 },
        incline: (Int) -> Double = { 0.0 },
        heartRate: (Int) -> Int? = { 110 },
        context: WorkoutAnalysisContext = ctx(),
        source: SampleHeartRateSource = SampleHeartRateSource.Strap,
    ) = WorkoutAnalyzer.analyse(withRr(session(seconds, speed = speed, incline = incline, heartRate = heartRate, source = source), rr), context)

    private fun Section<ThresholdEstimatesAnalysis>.estimates() = checkNotNull(value) { "reason $reason" }.estimates

    // ------------------------------------------------------------------ the estimate

    @Test
    fun `a steady stretch with clean RR implies VT1 from the slope, with its provenance and the duration as weight`() {
        val a = analyse(900, rr(T0, 900, 110.0, 1.0), incline = { 2.0 })
        val section = a.thresholdEstimates.value!!
        assertEquals(1, section.stretchesFound)
        val e = section.estimates.single()
        assertEquals(ThresholdKind.Vt1, e.kind)
        assertEquals(T0, e.startMillis)
        assertEquals(T0 + 900_000, e.endMillis)
        assertEquals(900, e.weightSeconds)
        assertEquals(5.0, e.speedKmh)
        assertEquals(2.0, e.inclinePercent)
        assertEquals(slope, e.slopePerBpm)
        assertEquals(110.0, e.heartRateBpm, 1.0)
        assertTrue(e.alpha1 in 0.8..1.25, "α1 ${e.alpha1}")
        assertTrue(e.cleanWindows in 55..67, "${e.cleanWindows} windows after the 2 min of settling")
        // VT1 = heart rate + (0.75 − α1) / slope.
        assertEquals(e.heartRateBpm + (0.75 - e.alpha1) / slope, e.impliedBpm, 1e-9)
        assertTrue(e.impliedBpm > e.heartRateBpm, "α1 above 0.75: the threshold is higher than the heart rate of the stretch")
    }

    @Test
    fun `without a slope - no test with thresholds - there is no estimate`() {
        val rr = rr(T0, 900, 110.0, 1.0)
        assertEquals(AnalysisReason.NoThresholdReference, analyse(900, rr, context = ctx(slope = null)).thresholdEstimates.reason)
        assertEquals(AnalysisReason.NoThresholdReference, analyse(900, rr, context = ctx(slope = 0.01)).thresholdEstimates.reason, "a slope that does not fall is no reference")
        // A context built without the new fields (older code) reads the same.
        assertEquals(AnalysisReason.NoThresholdReference, WorkoutAnalyzer.analyse(withRr(session(900), rr), context()).thresholdEstimates.reason)
    }

    @Test
    fun `RR from the console or without RR give no estimate`() {
        val rr = rr(T0, 900, 110.0, 1.0)
        // (A console heart rate that never changes would be a frozen reading: let it move.)
        val moving = { s: Int -> 108 + (s / 7) % 5 }
        assertEquals(AnalysisReason.NoRrIntervals, analyse(900, rr, heartRate = moving, source = SampleHeartRateSource.Console).thresholdEstimates.reason)
        assertEquals(AnalysisReason.NoRrIntervals, analyse(900, emptyList()).thresholdEstimates.reason)
    }

    // ------------------------------------------------------------------ the stretch

    @Test
    fun `a stretch needs 5 minutes with the load unchanged`() {
        // 4 min 40 s of the same load.
        val short = analyse(280, rr(T0, 280, 110.0, 1.0))
        assertEquals(AnalysisReason.NoSteadyStretch, short.thresholdEstimates.reason)
        // 5 min is enough.
        assertNotNull(analyse(300, rr(T0, 300, 110.0, 1.0)).thresholdEstimates.value)
        // Two parts of 3 min at different speeds are not a stretch.
        val split = analyse(360, rr(T0, 360, 110.0, 1.0), speed = { if (it < 180) 5.0 else 5.5 })
        assertEquals(AnalysisReason.NoSteadyStretch, split.thresholdEstimates.reason)
        // A change of incline breaks it as well.
        val inclined = analyse(360, rr(T0, 360, 110.0, 1.0), incline = { if (it < 180) 1.0 else 2.0 })
        assertEquals(AnalysisReason.NoSteadyStretch, inclined.thresholdEstimates.reason)
    }

    @Test
    fun `the first 2 minutes are left out - the windows start after them`() {
        // The heart rate and α1 of the first 2 min are different (settling); the estimate must not see them.
        val beats = rr(T0, 120, 90.0, 1.4) + rr(T0 + 120_000, 600, 120.0, 0.9, seed = 8)
        val hr = { s: Int -> if (s < 120) 90 else 120 }
        val e = analyse(720, beats, heartRate = hr).thresholdEstimates.estimates().single()
        assertEquals(120.0, e.heartRateBpm, 3.0)
        assertTrue(e.alpha1 < 1.15, "${e.alpha1}")
        assertEquals(720, e.weightSeconds, "the stretch is the whole run of unchanged load")
    }

    @Test
    fun `after the settling the heart rate must stay within 4 bpm`() {
        // +0.1 bpm per second: 12 bpm in 2 minutes of analysed time: nothing steady in 3 min.
        val drift = { s: Int -> 100 + (s * 0.1).toInt() }
        val beats = SyntheticRr.ramp(T0, 400, 100.0, 140.0, 1.0, 1.0, seed = 4)
        assertEquals(AnalysisReason.NoSteadyStretch, analyse(400, beats, heartRate = drift).thresholdEstimates.reason)
        // ±4 around the mean is allowed: a wobble of ±3.
        val wobble = { s: Int -> 110 + if ((s / 10) % 2 == 0) 3 else -3 }
        assertNotNull(analyse(600, rr(T0, 600, 110.0, 1.0), heartRate = wobble).thresholdEstimates.value)
        // ±6 is not.
        val wide = { s: Int -> 110 + if ((s / 60) % 2 == 0) 6 else -6 }
        assertEquals(AnalysisReason.NoSteadyStretch, analyse(600, rr(T0, 600, 110.0, 1.0), heartRate = wide).thresholdEstimates.reason)
    }

    @Test
    fun `when the heart rate drifts later the stretch ends where it was last steady`() {
        // Steady for 12 min, then a climb of 1 bpm every 5 s.
        val hr = { s: Int -> if (s < 720) 110 else 110 + (s - 720) / 5 }
        val beats = rr(T0, 720, 110.0, 1.0) + SyntheticRr.ramp(T0 + 720_000, 480, 110.0, 206.0, 1.0, 1.0, seed = 6)
        val e = analyse(1200, beats, heartRate = hr).thresholdEstimates.estimates().single()
        assertTrue(e.endMillis - T0 in 720_000L..780_000L, "ends at ${(e.endMillis - T0) / 1000} s")
        assertEquals(110.0, e.heartRateBpm, 2.0)
    }

    @Test
    fun `only inside the first 40 minutes of the workout`() {
        // One load for 50 min: the stretch is cut at 40 min.
        val long = analyse(3000, rr(T0, 3000, 110.0, 1.0))
        val e = long.thresholdEstimates.estimates().single()
        assertEquals(T0 + 2_400_000, e.endMillis)
        assertEquals(2400, e.weightSeconds)
        // A stretch that only starts after 40 min does not count (a different load every 90 s before it).
        val late = analyse(3200, rr(T0, 3200, 110.0, 1.0), speed = { if (it < 2500) 5.0 + 0.1 * ((it / 90) % 2) else 5.0 })
        assertEquals(AnalysisReason.NoSteadyStretch, late.thresholdEstimates.reason)
        // One that starts at 38 min has only 2 min inside the window.
        val edge = analyse(3000, rr(T0, 3000, 110.0, 1.0), speed = { if (it < 2280) 5.0 + 0.1 * ((it / 90) % 2) else 5.0 })
        assertEquals(AnalysisReason.NoSteadyStretch, edge.thresholdEstimates.reason)
    }

    @Test
    fun `not after a hard effort - more than a minute above VT2 earlier in the workout`() {
        // 200 s at 160 bpm (VT2 150), then a steady stretch at 110.
        val hard = rr(T0, 200, 160.0, 0.35, seed = 2) + rr(T0 + 200_000, 700, 110.0, 1.0, seed = 5)
        val hr = { s: Int -> if (s < 200) 160 else 110 }
        val speed = { s: Int -> if (s < 200) 9.0 else 5.0 }
        val after = analyse(900, hard, speed = speed, heartRate = hr)
        assertEquals(AnalysisReason.NoSteadyStretch, after.thresholdEstimates.reason)
        // The hard part is itself a stretch of 3 min only: nothing at all. With 60 s above VT2 or less, it is allowed.
        val brief = rr(T0, 60, 160.0, 0.35, seed = 2) + rr(T0 + 60_000, 840, 110.0, 1.0, seed = 5)
        val briefHr = { s: Int -> if (s < 60) 160 else 110 }
        val briefSpeed = { s: Int -> if (s < 60) 9.0 else 5.0 }
        val e = analyse(900, brief, speed = briefSpeed, heartRate = briefHr).thresholdEstimates.estimates().single()
        assertEquals(T0 + 60_000, e.startMillis)
        // Without any VT2 in the context, the one of the zones is used.
        val zonesVt2 = analyse(900, hard, speed = speed, heartRate = hr, context = ctx(vt2 = null))
        assertEquals(AnalysisReason.NoSteadyStretch, zonesVt2.thresholdEstimates.reason)
    }

    // ------------------------------------------------------------------ clean RR

    @Test
    fun `clean RR means windows with at most 3 percent artifacts`() {
        val base = rr(T0, 900, 110.0, 1.0)
        // One beat in 20 is impossible: 5 % in every window - fine for the threshold engine (5 %), not clean here.
        val noisy = base.mapIndexed { i, r -> if (i % 20 == 19) r.copy(rrMs = 150) else r }
        assertEquals(AnalysisReason.NoValidWindows, analyse(900, noisy).thresholdEstimates.reason)
        // One in 50: 2 %.
        val fine = base.mapIndexed { i, r -> if (i % 50 == 49) r.copy(rrMs = 150) else r }
        assertNotNull(analyse(900, fine).thresholdEstimates.value)
    }

    // ------------------------------------------------------------------ VT2

    @Test
    fun `VT2 only when running, with clean RR and a stretch α1 of 0_6 or less`() {
        val low = rr(T0, 900, 150.0, 0.35, seed = 9)
        val hr = { _: Int -> 150 }
        // Running (8 km/h) and low α1: both thresholds, VT2 = heart rate + (0.5 − α1) / slope.
        val both = analyse(900, low, speed = { 8.0 }, heartRate = hr, context = ctx(vt2 = 190.0)).thresholdEstimates.estimates()
        assertEquals(listOf(ThresholdKind.Vt1, ThresholdKind.Vt2), both.map { it.kind })
        val vt2 = both.single { it.kind == ThresholdKind.Vt2 }
        assertTrue(vt2.alpha1 <= 0.6, "${vt2.alpha1}")
        assertEquals(vt2.heartRateBpm + (0.5 - vt2.alpha1) / slope, vt2.impliedBpm, 1e-9)
        assertEquals(both[0].weightSeconds, vt2.weightSeconds)
        // Walking (7 km/h): only VT1.
        val walking = analyse(900, low, speed = { 7.0 }, heartRate = hr, context = ctx(vt2 = 190.0)).thresholdEstimates.estimates()
        assertEquals(listOf(ThresholdKind.Vt1), walking.map { it.kind })
        // 7.5 km/h counts as running.
        val edge = analyse(900, low, speed = { 7.5 }, heartRate = hr, context = ctx(vt2 = 190.0)).thresholdEstimates.estimates()
        assertEquals(2, edge.size)
        // Running with α1 well above 0.6: only VT1.
        val easy = rr(T0, 900, 120.0, 1.0)
        val running = analyse(900, easy, speed = { 9.0 }, heartRate = { 120 }, context = ctx(vt2 = 190.0)).thresholdEstimates.estimates()
        assertEquals(listOf(ThresholdKind.Vt1), running.map { it.kind })
    }

    @Test
    fun `two stretches of the same workout give their own estimates`() {
        val beats = rr(T0, 480, 110.0, 1.0, seed = 3) + rr(T0 + 480_000, 480, 130.0, 0.8, seed = 4)
        val hr = { s: Int -> if (s < 480) 110 else 130 }
        val section = analyse(960, beats, speed = { if (it < 480) 5.0 else 6.0 }, heartRate = hr).thresholdEstimates.value!!
        assertEquals(2, section.stretchesFound)
        assertEquals(listOf(5.0, 6.0), section.estimates.map { it.speedKmh })
        assertEquals(listOf(480, 480), section.estimates.map { it.weightSeconds })
    }

    @Test
    fun `the section does not disturb the others and the analysis has its new version`() {
        val a = analyse(900, rr(T0, 900, 110.0, 1.0))
        assertTrue(a.algorithmVersion >= 2) // the section came with version 2
        assertNotNull(a.energy.value)
        assertNotNull(a.alpha1.value)
        // No heart rate at all: the section carries the reason like the others.
        val none = WorkoutAnalyzer.analyse(session(900, heartRate = { null }), ctx())
        assertNull(none.thresholdEstimates.value)
        assertEquals(AnalysisReason.NoHeartRate, none.thresholdEstimates.reason)
        // A hand-made analysis without the section reads as "no reference".
        assertEquals(AnalysisReason.NoThresholdReference, com.treadz.console.analysis.trends.TrendFixtures.analysis().thresholdEstimates.reason)
    }
}
