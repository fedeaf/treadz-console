package com.treadz.console.analysis.workout

import com.treadz.console.analysis.energy.EnergyExpenditure
import com.treadz.console.analysis.energy.FuelRules
import com.treadz.console.analysis.energy.SubstrateModel
import com.treadz.console.analysis.fitness.Sex
import com.treadz.console.analysis.samples.RrInterval
import com.treadz.console.analysis.samples.SampleHeartRateSource
import com.treadz.console.analysis.samples.WorkoutSample
import com.treadz.console.analysis.workout.WorkoutFixtures.T0
import com.treadz.console.analysis.zones.TrainingZones
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.abs
import kotlin.math.roundToInt

class FuelSectionTest {

    private val zones = TrainingZones.compute(maxBpm = 185, restingBpm = 60, vt1Bpm = 131, vt2Bpm = 155)
    private val ref = WorkoutAnalysisContext(Sex.Male, 70.0, 60, 185, zones)

    private fun steady(minutes: Int, bpm: Int?, source: SampleHeartRateSource = SampleHeartRateSource.Strap) =
        WorkoutFixtures.session(minutes * 60, heartRate = { bpm }, source = source)

    private fun fuel(samples: List<WorkoutSample>, context: WorkoutAnalysisContext = ref): FuelAnalysis =
        checkNotNull(FuelSection.compute(samples, context).value) { FuelSection.compute(samples, context).reason.toString() }

    private fun reason(samples: List<WorkoutSample>, context: WorkoutAnalysisContext = ref): AnalysisReason? =
        FuelSection.compute(samples, context).reason

    private fun kcalPerSecond(speed: Double = 5.0, incline: Double = 0.0, weight: Double = 70.0) =
        EnergyExpenditure.kcalPerMinute(speed, incline, weight) / 60.0

    // ------------------------------------------------------------------ the reference profile

    @Test
    fun `30 minutes at 100 bpm are 60-70 percent fat`() {
        val share = fuel(steady(30, 100)).fatShare
        assertTrue(share in 0.59..0.71, "$share")
    }

    @Test
    fun `60 and 120 minutes at VT1 are 40-50 percent fat`() {
        for (minutes in listOf(60, 120)) {
            val share = fuel(steady(minutes, 131)).fatShare
            assertTrue(share in 0.39..0.51, "$minutes min: $share")
        }
    }

    @Test
    fun `60 and 120 minutes at VT2 are 10-20 percent fat`() {
        for (minutes in listOf(60, 120)) {
            val share = fuel(steady(minutes, 155)).fatShare
            assertTrue(share in 0.09..0.21, "$minutes min: $share")
        }
    }

    @Test
    fun `30 minutes at 95 percent of the maximum are 5-15 percent fat`() {
        val share = fuel(steady(30, 176)).fatShare
        assertTrue(share in 0.04..0.16, "$share")
    }

    @Test
    fun `the figures of the plan for the reference profile`() {
        assertEquals(0.62, fuel(steady(30, 100)).fatShare, 0.005)
        assertEquals(0.43, fuel(steady(60, 131)).fatShare, 0.005)
        assertEquals(0.47, fuel(steady(120, 131)).fatShare, 0.005)
        assertEquals(0.14, fuel(steady(60, 155)).fatShare, 0.01)
        assertEquals(0.17, fuel(steady(120, 155)).fatShare, 0.01)
    }

    @Test
    fun `women burn more fat at the same heart rate`() {
        val man = fuel(steady(60, 131)).fatShare
        val woman = fuel(steady(60, 131), ref.copy(sex = Sex.Female)).fatShare
        assertTrue(woman > man + 0.08, "$woman vs $man")
        assertTrue(woman in 0.50..0.58, "$woman")
    }

    @Test
    fun `the share falls as the heart rate rises`() {
        val shares = listOf(90, 110, 130, 150, 170).map { fuel(steady(20, it)).fatShare }
        assertEquals(shares.sortedDescending(), shares)
        assertEquals(shares.size, shares.toSet().size)
    }

    @Test
    fun `an unknown resting heart rate is assumed as a third of the maximum and flagged`() {
        val assumed = fuel(steady(10, 110), ref.copy(restingHeartRateBpm = null))
        assertTrue(assumed.anchors.restingAssumed)
        assertEquals(62, assumed.anchors.restingBpm)
        val known = fuel(steady(10, 110))
        assertFalse(known.anchors.restingAssumed)
        assertEquals(60, known.anchors.restingBpm)
        assertEquals(131, known.anchors.vt1Bpm)
        assertEquals(155, known.anchors.vt2Bpm)
        assertFalse(known.anchors.vt2FromTable)
        assertEquals(185, known.anchors.maxBpm)
        assertEquals(Sex.Male, known.anchors.sex)
    }

    @Test
    fun `VT2 from the table is flagged and still gives a split`() {
        val onlyVt1 = TrainingZones.compute(maxBpm = 185, restingBpm = 60, vt1Bpm = 131, vt2Bpm = null, selfRated = null)
        assertEquals(com.treadz.console.analysis.zones.ZoneSource.MeasuredThresholds, onlyVt1.source)
        val result = fuel(steady(10, 120), ref.copy(zones = onlyVt1))
        assertTrue(result.anchors.vt2FromTable)
        assertEquals(onlyVt1.vt2Bpm, result.anchors.vt2Bpm)
    }

    // ------------------------------------------------------------------ a ramp

    @Test
    fun `in a ramp the minute with the most fat kcal is near VT1`() {
        val minutes = 24
        val seconds = minutes * 60
        val hr = { t: Int -> 75.0 + 110.0 * t / seconds }
        val samples = WorkoutFixtures.session(
            seconds,
            speed = { t -> 3.0 + 9.0 * t / seconds },
            heartRate = { t -> hr(t).roundToInt() },
        )
        val result = fuel(samples)
        val best = result.minutes.indices.maxByOrNull { result.minutes[it].fatKcal }!!
        val bestHr = hr(best * 60 + 30)
        assertTrue(abs(bestHr - 131) <= 10, "most fat at $bestHr bpm (minute $best)")
    }

    // ------------------------------------------------------------------ seconds without a valid heart rate

    @Test
    fun `leading seconds take the first valid share and gaps the last valid one`() {
        // Ticks 0-9 without heart rate, 10-39 at 100, 40-44 without, 45-99 at 100 (85 % valid).
        val samples = WorkoutFixtures.session(100, heartRate = { t -> if (t in 0..9 || t in 40..44) null else 100 })
        val result = fuel(samples)
        assertEquals(100, result.movingSeconds)
        assertEquals(85, result.validSeconds)
        assertEquals(0.85, result.validCoverage, 1e-12)

        val e = kcalPerSecond()
        fun share(movedSeconds: Int) = SubstrateModel.fatShare(SubstrateModel.rer(100.0, result.anchors, movedSeconds / 60.0))
        var expectedFat = 0.0
        var last = share(10)
        for (k in 0 until 100) {
            val valid = !(k in 0..9 || k in 40..44)
            if (valid) last = share(k)
            expectedFat += e * last
        }
        assertEquals(expectedFat, result.fatKcal, 1e-9)
        assertEquals(e * 100, result.fatKcal + result.carbKcal, 1e-9)
    }

    @Test
    fun `the energy of the leading seconds waits unassigned in the live snapshot`() {
        val tracker = checkNotNull(FuelSection.tracker(ref))
        val samples = WorkoutFixtures.session(100, heartRate = { t -> if (t < 30) null else 100 })
        for (s in samples.take(25)) tracker.add(s)
        val waiting = tracker.snapshot()
        assertNull(waiting.fatShare)
        assertEquals(0.0, waiting.fatKcal)
        assertFalse(waiting.showable)
        assertFalse(waiting.currentlyValid)
        assertEquals(0, waiting.validSeconds)
        for (s in samples.drop(25)) tracker.add(s)
        val section = tracker.finish()
        // 70 % valid: not enough for the section, and the live snapshot says so too.
        assertEquals(AnalysisReason.NoReliableStrapHeartRate, section.reason)
        val last = tracker.snapshot()
        assertFalse(last.showable)
        assertEquals(0.7, last.validCoverage!!, 1e-12)
        assertTrue(last.fatShare != null && last.currentlyValid)
    }

    @Test
    fun `a console or unknown heart rate is not valid`() {
        assertEquals(AnalysisReason.NoReliableStrapHeartRate, reason(steady(10, 120, SampleHeartRateSource.Console)))
        assertEquals(AnalysisReason.NoReliableStrapHeartRate, reason(steady(10, 120, SampleHeartRateSource.Unknown)))
        // 85 % strap, then 15 % console: split present, 85 % valid.
        val mixed = WorkoutFixtures.session(200, heartRate = { 120 }, source = SampleHeartRateSource.Strap)
            .mapIndexed { i, s -> if (i >= 170) s.copy(heartRateSource = SampleHeartRateSource.Console) else s }
        val result = fuel(mixed)
        assertEquals(170, result.validSeconds)
        assertEquals(0.85, result.validCoverage, 1e-12)
        // A sample with a heart rate and no source counts as unknown.
        assertEquals(AnalysisReason.NoReliableStrapHeartRate, reason(steady(10, 120).map { it.copy(heartRateSource = null) }))
    }

    @Test
    fun `a heart rate outside 30-250 is not valid`() {
        val samples = WorkoutFixtures.session(200, heartRate = { t -> if (t < 20) 25 else if (t < 40) 255 else 120 })
        val result = fuel(samples)
        assertEquals(160, result.validSeconds)
    }

    // ------------------------------------------------------------------ the strap check against the RR

    /** Beats every 60000/rate ms from [T0] for [seconds]: [rateAt] gives the rate (bpm) of the RR at a second, [rrAt] overrides one interval. */
    private fun beats(seconds: Int, rateAt: (Double) -> Double, rrAt: ((Double, Int) -> Int)? = null): List<RrInterval> {
        val out = ArrayList<RrInterval>()
        var t = 0.0
        var i = 0
        while (t < seconds * 1000.0) {
            val base = (60_000.0 / rateAt(t / 1000.0)).roundToInt()
            val rr = rrAt?.invoke(t / 1000.0, i) ?: base
            t += rr
            out += RrInterval(T0 + t.toLong(), rr)
            i++
        }
        return out
    }

    private fun withRr(seconds: Int, bpm: Int, rr: List<RrInterval>) =
        WorkoutFixtures.withRr(WorkoutFixtures.session(seconds, heartRate = { bpm }), rr)

    @Test
    fun `windows whose RR confirm the strap are valid`() {
        val result = fuel(withRr(300, 100, beats(300, { 100.0 })))
        assertEquals(300, result.validSeconds)
    }

    @Test
    fun `windows where the strap and the RR disagree are not valid`() {
        // The strap says 100 all the time; from second 200 to 300 the RR say 90.
        val rr = beats(600, { t -> if (t in 200.0..300.0) 90.0 else 100.0 })
        val result = fuel(withRr(600, 100, rr))
        assertTrue(result.validSeconds in 480..510, "${result.validSeconds}")
    }

    @Test
    fun `windows full of artifacts are not valid`() {
        val junk = intArrayOf(250, 390, 300, 380, 260, 350)
        val rr = beats(600, { 100.0 }, { t, i -> if (t in 200.0..300.0) junk[i % junk.size] else 600 })
        val result = fuel(withRr(600, 100, rr))
        assertTrue(result.validSeconds in 480..510, "${result.validSeconds}")
    }

    @Test
    fun `a window is judged without the beats that come after it`() {
        // Two sessions equal for the first 300 s; the second has junk beats afterwards. Whatever follows must not change
        // the first minutes.
        val clean = withRr(600, 100, beats(600, { 100.0 }))
        val junk = intArrayOf(250, 390, 300, 380, 260, 350)
        val dirty = withRr(600, 100, beats(600, { 100.0 }, { t, i -> if (t in 300.0..360.0) junk[i % junk.size] else 600 }))
        val a = fuel(clean)
        val b = fuel(dirty)
        for (m in 0 until 4) assertEquals(a.minutes[m], b.minutes[m], "minute $m")
        assertTrue(b.validSeconds < a.validSeconds)
    }

    @Test
    fun `a strap sending no RR is not judged and counts as valid`() {
        val result = fuel(steady(10, 120))
        assertEquals(600, result.validSeconds)
    }

    // ------------------------------------------------------------------ the totals

    @Test
    fun `fat plus carbohydrate is the gross kcal of the energy section`() {
        // A workout with pauses, speed and incline changes, gaps and 3-s samples.
        val samples = ArrayList<WorkoutSample>()
        var t = 0
        fun block(seconds: Int, step: Int, speed: Double, incline: Double, hr: Int?) {
            for (s in 0 until seconds step step) {
                samples += WorkoutSample(T0 + (t + s) * 1000L, speed, incline, hr, if (hr != null) SampleHeartRateSource.Strap else null)
            }
            t += seconds
        }
        block(300, 1, 4.0, 0.0, 95)
        block(600, 3, 6.0, 2.0, 120)
        block(120, 1, 0.0, 0.0, 100)
        block(30, 3, 0.0, 0.0, null)
        t += 60 // a gap
        block(900, 1, 8.5, 1.0, 150)
        block(100, 2, 5.0, 0.0, 130)
        val context = ref
        val analysis = WorkoutAnalyzer.analyse(samples, context)
        val energy = checkNotNull(analysis.energy.value)
        val result = checkNotNull(analysis.fuel.value) { "${analysis.fuel.reason}" }
        assertEquals(checkNotNull(energy.grossKcal), result.fatKcal + result.carbKcal, 1e-6)
        assertEquals(energy.movingSeconds, result.movingSeconds)
        assertTrue(result.fatKcal > 0.0 && result.carbKcal > 0.0)
        assertEquals(result.fatKcal / (result.fatKcal + result.carbKcal), result.fatShare, 1e-12)
    }

    @Test
    fun `the same signal sampled at 1 Hz or 2 Hz gives the same split`() {
        val oneHz = steady(10, 125)
        val twoHz = oneHz.flatMap { listOf(it, it.copy(timeMillis = it.timeMillis + 500)) }
        assertEquals(fuel(oneHz), fuel(twoHz).copy())
    }

    @Test
    fun `the zones and the minutes add up to the totals when every second is valid`() {
        val seconds = 20 * 60
        val samples = WorkoutFixtures.session(seconds, heartRate = { t -> 90 + 60 * t / seconds }, speed = { t -> 4.0 + 4.0 * t / seconds })
        val result = fuel(samples)
        assertEquals(seconds, result.validSeconds)
        assertEquals(5, result.byZone.size)
        assertEquals(listOf(1, 2, 3, 4, 5), result.byZone.map { it.number })
        assertEquals(seconds, result.byZone.sumOf { it.seconds })
        assertEquals(result.fatKcal, result.byZone.sumOf { it.fatKcal }, 1e-9)
        assertEquals(result.carbKcal, result.byZone.sumOf { it.carbKcal }, 1e-9)
        assertEquals(20, result.minutes.size)
        assertEquals(result.fatKcal, result.minutes.sumOf { it.fatKcal }, 1e-9)
        assertEquals(result.carbKcal, result.minutes.sumOf { it.carbKcal }, 1e-9)
        assertTrue(result.byZone.count { it.seconds > 0 } >= 3)
        // The minutes start at the first moving second, one every 60 s.
        assertEquals(T0, result.minutes.first().startMillis)
        assertEquals(listOf(T0, T0 + 60_000L, T0 + 120_000L), result.minutes.take(3).map { it.startMillis })
    }

    @Test
    fun `a heart rate in a zone is counted in that zone`() {
        assertEquals(listOf(600, 0, 0, 0, 0), fuel(steady(10, 100)).byZone.map { it.seconds })
        assertEquals(listOf(0, 0, 600, 0, 0), fuel(steady(10, 140)).byZone.map { it.seconds })
        assertEquals(listOf(0, 0, 0, 0, 600), fuel(steady(10, 180)).byZone.map { it.seconds })
    }

    @Test
    fun `the zones only count the valid seconds and the minutes all of the moving ones`() {
        val samples = WorkoutFixtures.session(300, heartRate = { t -> if (t in 100..139) null else 140 })
        val result = fuel(samples)
        assertEquals(260, result.validSeconds)
        assertEquals(260, result.byZone.sumOf { it.seconds })
        assertTrue(result.byZone.sumOf { it.fatKcal } < result.fatKcal)
        assertEquals(result.fatKcal, result.minutes.sumOf { it.fatKcal }, 1e-9)
    }

    @Test
    fun `minutes skip the stopped belt but stay contiguous`() {
        // 30 s stopped, 150 s moving, 120 s paused, 60 s moving.
        val samples = WorkoutFixtures.session(360, heartRate = { 120 }, speed = { t -> if (t < 30 || t in 180..299) 0.0 else 5.0 })
        val result = fuel(samples)
        assertEquals(210, result.movingSeconds)
        // From the first moving second (30) to the last (359): 330 s = 6 minutes of the grid.
        assertEquals(6, result.minutes.size)
        assertEquals(T0 + 30_000L, result.minutes.first().startMillis)
        assertEquals(0.0, result.minutes[3].fatKcal + result.minutes[3].carbKcal, 1e-12)
        assertEquals(kcalPerSecond() * 210, result.fatKcal + result.carbKcal, 1e-9)
    }

    // ------------------------------------------------------------------ live = batch

    @Test
    fun `feeding the samples one by one gives the totals of the section`() {
        val seconds = 900
        val junk = intArrayOf(250, 390, 300, 380, 260, 350)
        val rr = beats(seconds, { 100.0 + 50.0 * it / seconds }, { t, i -> if (t in 400.0..460.0) junk[i % junk.size] else (60_000.0 / (100.0 + 50.0 * t / seconds)).roundToInt() })
        val samples = WorkoutFixtures.withRr(
            WorkoutFixtures.session(seconds, heartRate = { t -> if (t in 600..630) null else 100 + 50 * t / seconds }, speed = { t -> 4.0 + 3.0 * t / seconds }),
            rr,
        )
        val section = checkNotNull(FuelSection.compute(samples, ref).value)

        val tracker = checkNotNull(FuelSection.tracker(ref))
        var previous = tracker.snapshot()
        for ((i, s) in samples.withIndex()) {
            tracker.add(s)
            val now = tracker.snapshot()
            assertTrue(now.fatKcal >= previous.fatKcal - 1e-12 && now.carbKcal >= previous.carbKcal - 1e-12, "monotonic at $i")
            assertTrue(now.movingSeconds >= previous.movingSeconds)
            // The snapshot lags by at most one window plus the open sample.
            assertTrue(now.movingSeconds >= i - 12, "lag at $i: ${now.movingSeconds}")
            previous = now
        }
        val live = tracker.finish()
        assertEquals(section, live.value)
        val last = tracker.snapshot()
        assertEquals(section.fatKcal, last.fatKcal, 0.0)
        assertEquals(section.carbKcal, last.carbKcal, 0.0)
        assertEquals(section.fatShare, last.fatShare!!, 1e-12)
        assertEquals(section.movingSeconds, last.movingSeconds)
        assertEquals(section.validSeconds, last.validSeconds)
        assertEquals(section.validCoverage, last.validCoverage!!, 1e-12)
        assertTrue(last.showable)
    }

    @Test
    fun `the live snapshot gives the same totals however often it is read`() {
        val samples = steady(15, 130)
        val quiet = checkNotNull(FuelSection.tracker(ref)).also { t -> samples.forEach { t.add(it) } }
        val busy = checkNotNull(FuelSection.tracker(ref)).also { t -> samples.forEach { s -> t.add(s); t.snapshot() } }
        assertEquals(quiet.finish(), busy.finish())
    }

    @Test
    fun `the snapshot becomes showable once the valid heart rate covers 80 percent`() {
        val tracker = checkNotNull(FuelSection.tracker(ref))
        val samples = WorkoutFixtures.session(300, heartRate = { t -> if (t < 40) null else 120 })
        var firstShowable: Int? = null
        for ((i, s) in samples.withIndex()) {
            tracker.add(s)
            if (firstShowable == null && tracker.snapshot().showable) firstShowable = i
        }
        // 80 % of the decided seconds: 40 without + n with, n >= 160 -> decided second 200 or so.
        assertNotNull(firstShowable)
        assertTrue(firstShowable!! in 195..215, "$firstShowable")
        assertTrue(tracker.snapshot().currentlyValid)
    }

    // ------------------------------------------------------------------ the reasons it is missing

    @Test
    fun `the base reasons are those of the other sections`() {
        assertEquals(AnalysisReason.NoSamples, reason(emptyList()))
        assertEquals(AnalysisReason.NoSamples, reason(steady(1, 100).take(1)))
        val far = listOf(WorkoutSample(T0, 5.0, 0.0), WorkoutSample(T0 + 30 * 3600_000L, 5.0, 0.0))
        assertEquals(AnalysisReason.ImplausibleDuration, reason(far))
        assertEquals(AnalysisReason.NoBeltMovement, reason(WorkoutFixtures.session(300, speed = { 0.0 })))
        // The tracker alone says the same.
        val tracker = checkNotNull(FuelSection.tracker(ref))
        far.forEach { tracker.add(it) }
        assertEquals(AnalysisReason.ImplausibleDuration, tracker.finish().reason)
    }

    @Test
    fun `the profile reasons`() {
        val samples = steady(10, 120)
        assertEquals(AnalysisReason.NoWeight, reason(samples, ref.copy(weightKg = null)))
        assertEquals(AnalysisReason.NoWeight, reason(samples, ref.copy(weightKg = 0.0)))
        assertEquals(AnalysisReason.InvalidMaxHeartRate, reason(samples, ref.copy(maxHeartRateBpm = 30)))
        assertEquals(AnalysisReason.NoMeasuredThresholds, reason(samples, ref.copy(zones = null)))
        val table = TrainingZones.compute(maxBpm = 185, restingBpm = 60)
        assertEquals(AnalysisReason.NoMeasuredThresholds, reason(samples, ref.copy(zones = table)))
        // Resting at or above VT1: the anchors do not increase.
        assertEquals(AnalysisReason.NoMeasuredThresholds, reason(samples, ref.copy(restingHeartRateBpm = 131)))
        for (context in listOf(ref.copy(weightKg = null), ref.copy(zones = null), ref.copy(maxHeartRateBpm = 30), ref.copy(restingHeartRateBpm = 140))) {
            assertNull(FuelSection.tracker(context))
        }
        assertNotNull(FuelSection.tracker(ref))
    }

    @Test
    fun `not enough strap heart rate`() {
        assertEquals(AnalysisReason.NoReliableStrapHeartRate, reason(steady(10, null)))
        // 79 % valid is not enough, 80 % is.
        assertEquals(AnalysisReason.NoReliableStrapHeartRate, reason(WorkoutFixtures.session(100, heartRate = { t -> if (t < 21) null else 120 })))
        assertNotNull(FuelSection.compute(WorkoutFixtures.session(100, heartRate = { t -> if (t < 20) null else 120 }), ref).value)
    }

    // ------------------------------------------------------------------ the analyzer

    @Test
    fun `the analyzer computes the section and the version is 4`() {
        assertEquals(4, WorkoutAnalyzer.ALGORITHM_VERSION)
        val analysis = WorkoutAnalyzer.analyse(steady(10, 120), ref)
        assertEquals(4, analysis.algorithmVersion)
        assertEquals(FuelSection.compute(steady(10, 120), ref), analysis.fuel)
        assertEquals(AnalysisReason.NoBeltMovement, WorkoutAnalyzer.analyse(WorkoutFixtures.session(300, speed = { 0.0 }), ref).fuel.reason)
        assertEquals(AnalysisReason.NoMeasuredThresholds, WorkoutAnalyzer.analyse(steady(10, 120), WorkoutFixtures.context()).fuel.reason)
    }

    @Test
    fun `a hand-built analysis has no split by default`() {
        val analysis = WorkoutAnalyzer.analyse(steady(10, 120), ref)
        val built = WorkoutAnalysis(
            analysis.algorithmVersion, analysis.signal, analysis.energy, analysis.zoneTime, analysis.trimp, analysis.efficiency,
            analysis.fitnessIndex, analysis.recovery, analysis.alpha1,
        )
        assertEquals(AnalysisReason.NoMeasuredThresholds, built.fuel.reason)
    }

    @Test
    fun `the rules can be changed`() {
        val none = FuelRules(rerPerMovingMinute = 0.0)
        val withDuration = fuel(steady(60, 131)).fatShare
        val without = checkNotNull(FuelSection.compute(steady(60, 131), ref, fuelRules = none).value).fatShare
        assertTrue(without < withDuration)
        assertEquals(SubstrateModel.fatShare(0.88), without, 1e-6)
    }
}
