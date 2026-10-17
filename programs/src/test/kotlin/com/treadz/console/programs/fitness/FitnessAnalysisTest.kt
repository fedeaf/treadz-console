package com.treadz.console.programs.fitness

import com.treadz.console.analysis.fitness.Acsm
import com.treadz.console.analysis.fitness.FitnessFormulas
import com.treadz.console.analysis.fitness.SelfRatedFitness
import com.treadz.console.analysis.fitness.Sex
import com.treadz.console.analysis.fitness.Vo2MaxCategory
import com.treadz.console.analysis.fitness.Vo2MaxNorms
import com.treadz.console.analysis.samples.SampleHeartRateSource
import com.treadz.console.analysis.samples.WorkoutSample
import com.treadz.console.analysis.thresholds.ThresholdEngine
import com.treadz.console.analysis.thresholds.ThresholdPoint
import com.treadz.console.analysis.thresholds.ThresholdQuality
import com.treadz.console.analysis.thresholds.ThresholdReason
import com.treadz.console.analysis.thresholds.ThresholdResult
import com.treadz.console.analysis.zones.ZoneSource
import com.treadz.console.programs.heartrate.HeartRateProfile
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.Random
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.roundToLong
import kotlin.math.sqrt

/** Walking-test VO2max, the profile combination rules, thresholds in results and profile, recalculation and zones. */
class FitnessAnalysisTest {

    private val day = 86_400_000L
    private val now = FT0 + 400 * day

    // ------------------------------------------------------------------ fixtures

    private fun walk(
        id: String,
        daysAgo: Int,
        vo2: Double,
        effort: EffortAssessment = EffortAssessment.Maximal,
        lowerBound: Boolean = false,
        thresholds: ThresholdResult? = null,
    ) = sampleResult(FitnessTestType.MaxHeartRateWalk, id, now - daysAgo * day).copy(
        outputs = FitnessTestOutputs(
            vo2, 3.5, Vo2MaxMethod.AcsmWalkRamp, lowerBound, Vo2MaxNorms.classify(vo2, 40, Sex.Male),
            masKmh = FitnessFormulas.round2(vo2 / 3.5), masIsLowerBound = lowerBound, hrMaxCandidateBpm = 183, effort = effort,
            masIsEstimated = true,
        ),
        thresholds = thresholds,
        schemaVersion = FitnessTestResult.SCHEMA_VERSION,
    )

    private fun ebbeling(id: String, daysAgo: Int, vo2: Double) =
        sampleResult(FitnessTestType.EbbelingWalk, id, now - daysAgo * day).let { it.copy(outputs = it.outputs?.copy(vo2maxMlKgMin = vo2)) }

    private fun thresholds(vt1: Double?, vt2: Double? = null, quality: ThresholdQuality = ThresholdQuality.High, vo2: Double? = 30.0) =
        ThresholdResult(
            algorithmVersion = ThresholdEngine.ALGORITHM_VERSION,
            vt1 = vt1?.let { ThresholdPoint(it, 6.2, 10.0, vo2) },
            vt2 = vt2?.let { ThresholdPoint(it, 6.2, 15.0, vo2?.plus(8)) },
            quality = quality,
        )

    // ------------------------------------------------------------------ VO2max combination

    @Test
    fun `a recent maximal test damps the submaximal ones - 43,6 and 48,9 give about 44,2`() {
        val p = FitnessProfileCalculator.compute(listOf(walk("w", 2, 43.6), ebbeling("e", 2, 48.9)), now, 40, Sex.Male)
        val v = p.vo2max!!
        assertEquals(44.2, v.valueMlKgMin)
        assertEquals(0.25, v.sources.single { it.resultId == "e" }.weightFactor)
        assertEquals(1.0, v.sources.single { it.resultId == "w" }.weightFactor)
        assertFalse(v.discrepancy, "5.3 apart is below 1.5 * 5.98")
        assertTrue(v.sources.none { it.discrepant })
    }

    @Test
    fun `no damping without a valid maximal test of the last 90 days`() {
        val plain = (1 / (3.5 * 3.5) * 43.6 + 1 / (4.85 * 4.85) * 48.9) / (1 / (3.5 * 3.5) + 1 / (4.85 * 4.85))
        for (maximal in listOf(
            walk("old", 91, 43.6),
            walk("bound", 2, 43.6, effort = EffortAssessment.NotMaximal, lowerBound = true),
            walk("unknown", 2, 43.6, effort = EffortAssessment.Unknown),
        )) {
            val p = FitnessProfileCalculator.compute(listOf(maximal, ebbeling("e", maximal.let { ((now - it.startedAtMillis) / day).toInt() }, 48.9)), now)
            assertEquals(1.0, p.vo2max!!.sources.single { it.resultId == "e" }.weightFactor, maximal.id)
            if (maximal.id == "unknown") assertEquals(FitnessFormulas.round1(plain), p.vo2max!!.valueMlKgMin)
        }
    }

    @Test
    fun `gross discrepancies between two sources are flagged as data`() {
        val p = FitnessProfileCalculator.compute(listOf(walk("w", 2, 40.0), ebbeling("e", 2, 52.0), ebbeling("e2", 3, 44.0)), now)
        val v = p.vo2max!!
        assertTrue(v.discrepancy)
        assertEquals(setOf("w", "e"), v.sources.filter { it.discrepant }.map { it.resultId }.toSet())
        val json = FitnessTestJson.encodeProfile(p)
        assertTrue(json.contains("\"discrepancy\": true"), json)
        assertEquals(p, FitnessTestJson.decodeProfile(json))
    }

    @Test
    fun `the walking test now feeds the VO2max of the profile and still its measured maximum`() {
        val p = FitnessProfileCalculator.compute(listOf(walk("w", 2, 43.6)), now, 40, Sex.Male)
        assertEquals(43.6, p.vo2max!!.valueMlKgMin)
        assertEquals(3.5, p.vo2max!!.seeMlKgMin)
        assertEquals(MasEstimate(12.46, true, false, null, null, null), p.mas)
        assertEquals(183, p.hrMax!!.measuredBpm)
        // A walking result stored before format version 2 (no outputs) still gives its maximum.
        val old = sampleResult(FitnessTestType.MaxHeartRateWalk, "old", now - 2 * day)
        val q = FitnessProfileCalculator.compute(listOf(old), now)
        assertNull(q.vo2max)
        assertEquals(183, q.hrMax!!.measuredBpm)
    }

    // ------------------------------------------------------------------ threshold profile

    @Test
    fun `profile thresholds combine high and medium tests by quality and age, low ones only in the history`() {
        val results = listOf(
            walk("a", 0, 43.0, thresholds = thresholds(132.0, 151.0)),
            walk("b", 30, 43.0, thresholds = thresholds(134.0, 153.0, ThresholdQuality.Medium)),
            walk("low", 1, 43.0, thresholds = thresholds(110.0, quality = ThresholdQuality.Low)),
            walk("old", 181, 43.0, thresholds = thresholds(120.0)),
        )
        val t = FitnessProfileCalculator.compute(results, now).thresholds!!
        // Weights: a = 1, b = 0.5 (medium) * 0.5 (one half-life) = 0.25.
        assertEquals(((1.0 * 132 + 0.25 * 134) / 1.25).roundToInt(), t.vt1!!.heartRateBpm)
        assertEquals(listOf("a", "b"), t.vt1!!.sources.map { it.resultId })
        assertEquals(0.8, t.vt1!!.sources[0].weight)
        assertEquals(ThresholdQuality.High, t.vt1!!.quality)
        assertEquals(30.0, t.vt1!!.vo2MlKgMin)
        assertEquals(((1.0 * 151 + 0.25 * 153) / 1.25).roundToInt(), t.vt2!!.heartRateBpm)
        assertEquals(listOf("a", "low", "b", "old"), t.history.map { it.resultId })
        // Without any test with thresholds there is no threshold profile.
        assertNull(FitnessProfileCalculator.compute(listOf(walk("w", 1, 43.0)), now).thresholds)
        val noRr = walk("w", 1, 43.0, thresholds = ThresholdResult.none(ThresholdReason.NoRrIntervals))
        assertNull(FitnessProfileCalculator.compute(listOf(noRr), now).thresholds)
    }

    @Test
    fun `a jump weighs half until a later test corroborates it`() {
        val a = walk("a", 60, 43.0, thresholds = thresholds(130.0))
        val b = walk("b", 30, 43.0, thresholds = thresholds(142.0))
        val alone = FitnessProfileCalculator.compute(listOf(a, b), now).thresholds!!.vt1!!
        val sb = alone.sources.single { it.resultId == "b" }
        assertTrue(sb.jump)
        assertFalse(sb.corroborated)
        // Weights: a = 0.25 (two half-lives), b = 0.5 (one half-life) * 0.5 (jump).
        assertEquals(((0.25 * 130 + 0.25 * 142) / 0.5).roundToInt(), alone.heartRateBpm)

        val c = walk("c", 10, 43.0, thresholds = thresholds(141.0))
        val confirmed = FitnessProfileCalculator.compute(listOf(a, b, c), now).thresholds!!.vt1!!
        val cb = confirmed.sources.single { it.resultId == "b" }
        assertTrue(cb.jump && cb.corroborated)
        assertFalse(confirmed.sources.single { it.resultId == "c" }.jump, "c is close to the profile including b")
        val wa = 0.5.pow(60.0 / 30)
        val wb = 0.5.pow(30.0 / 30)
        val wc = 0.5.pow(10.0 / 30)
        assertEquals(((wa * 130 + wb * 142 + wc * 141) / (wa + wb + wc)).roundToInt(), confirmed.heartRateBpm)

        // A confirmation more than 60 days later does not count.
        val late = walk("late", 0, 43.0, thresholds = thresholds(142.0))
        val early = walk("b", 70, 43.0, thresholds = thresholds(142.0))
        val first = walk("a", 100, 43.0, thresholds = thresholds(130.0))
        val t = FitnessProfileCalculator.compute(listOf(first, early, late), now).thresholds!!.vt1!!
        assertFalse(t.sources.single { it.resultId == "b" }.corroborated)
    }

    @Test
    fun `the profile with thresholds survives a JSON round trip and stays at version 1`() {
        val p = FitnessProfileCalculator.compute(
            listOf(walk("a", 0, 43.6, thresholds = thresholds(132.0, 151.0)), ebbeling("e", 3, 48.9)), now, 40, Sex.Male,
        )
        val text = FitnessTestJson.encodeProfile(p)
        assertEquals(p, FitnessTestJson.decodeProfile(text))
        assertTrue(text.contains("\"schemaVersion\": 1"), text)
        assertTrue(text.contains("\"thresholds\": {"), text)
        assertTrue(text.contains("\"weightFactor\": 0.25"), text)
        // A profile written before these fields is read with the default rules.
        val old = text.substringBefore(",\n  \"thresholds\"") + "\n}\n"
        val decoded = FitnessTestJson.decodeProfile(
            old.replace(Regex(",\\s*\"submaximalFactorWithMaximal\": 0.25,\\s*\"maximalRecencyDays\": 90,\\s*\"discrepancyFactor\": 1.5"), ""),
        )
        assertNull(decoded.thresholds)
        assertEquals(FitnessProfileRules(), decoded.rules)
    }

    // ------------------------------------------------------------------ result JSON

    @Test
    fun `a result with thresholds is written as version 2, one without as version 1, and both read back`() {
        val r = walk("w", 1, 43.6, thresholds = thresholds(132.4, 151.2).copy(
            metrics = com.treadz.console.analysis.thresholds.ThresholdMetrics(31, 48, 50, 0.82, 1.2, -0.021, 3.53),
        ))
        assertEquals(FitnessTestResult.SCHEMA_VERSION, r.schemaVersion)
        val text = FitnessTestJson.encode(r)
        assertTrue(text.contains("\"schemaVersion\": 2"), text)
        assertTrue(text.contains("\"method\": \"acsmWalkRamp\""), text)
        assertTrue(text.contains("\"masIsEstimated\": true"), text)
        assertTrue(text.contains("\"algorithmVersion\": 1") && text.contains("\"quality\": \"high\""), text)
        assertTrue(text.contains("\"heartRateBpm\": 132.4"), text)
        assertEquals(r, FitnessTestJson.decode(text))

        val reason = r.copy(thresholds = ThresholdResult.none(ThresholdReason.NoWorkoutSamples))
        val reasonText = FitnessTestJson.encode(reason)
        assertTrue(reasonText.contains("\"thresholds\": {\"algorithmVersion\": 1, \"reason\": \"noWorkoutSamples\"}"), reasonText)
        assertEquals(reason, FitnessTestJson.decode(reasonText))

        // Unknown reasons from a newer app are read as "not enough data".
        val newer = FitnessTestJson.decode(reasonText.replace("noWorkoutSamples", "somethingNew"))
        assertEquals(ThresholdReason.NotEnoughValidWindows, newer.thresholds!!.reason)

        // Without the additions the document stays at version 1 (older apps keep reading it).
        val plain = sampleResult(FitnessTestType.MaxHeartRateWalk)
        assertEquals(1, plain.schemaVersion)
        assertTrue(FitnessTestJson.encode(plain).contains("\"schemaVersion\": 1"))
        // A result object that says 1 but carries thresholds is still written as 2.
        val inconsistent = plain.copy(thresholds = ThresholdResult.none(ThresholdReason.NoRrIntervals))
        assertTrue(FitnessTestJson.encode(inconsistent).contains("\"schemaVersion\": 2"))
    }

    // ------------------------------------------------------------------ recalculation

    /** Per-second samples of the hand-made walking result: warm-up, then the ramp at 5.5 km/h, 5 % + 1 %/min. */
    private fun walkSamples(result: FitnessTestResult, source: SampleHeartRateSource? = SampleHeartRateSource.Strap, rr: Boolean = true): List<WorkoutSample> {
        val m = result.measurements as MaxHeartRateWalkMeasurements
        val rampStart = result.startedAtMillis + m.warmUpSeconds * 1000L
        val end = result.effortEndedAtMillis
        val beats = if (rr) syntheticRamp(rampStart, ((end - rampStart) / 1000).toInt()) else emptyList()
        val bySecond = beats.groupBy { ceil((it.first - result.startedAtMillis) / 1000.0).toLong() }
        return (0..((result.endedAtMillis - result.startedAtMillis) / 1000)).map { s ->
            val t = result.startedAtMillis + s * 1000L
            val ramp = t >= rampStart && t <= end
            val incline = if (ramp) 5.0 + ((t - rampStart) / 60_000).toInt() else 0.0
            WorkoutSample(t, if (t <= end) 5.5 else 4.0, incline, 120, source, bySecond[s]?.map { it.second } ?: emptyList())
        }
    }

    @Test
    fun `Ebbeling and invalid results are never recalculated`() {
        assertNull(FitnessTestRecalculation.recalculate(sampleResult(FitnessTestType.EbbelingWalk), emptyList()))
        val invalid = sampleResult(FitnessTestType.MaxHeartRateWalk, validity = Validity.Invalid, flags = listOf(FitnessTestFlag.ManualChange))
        assertFalse(FitnessTestRecalculation.needsRecalculation(invalid))
        assertNull(FitnessTestRecalculation.recalculate(invalid, null))
    }

    @Test
    fun `an old walking result without its workout - VO2max from the stages, reason noWorkoutSamples, then idempotent`() {
        val old = sampleResult(FitnessTestType.MaxHeartRateWalk)
        assertTrue(FitnessTestRecalculation.needsRecalculation(old))
        val r = FitnessTestRecalculation.recalculate(old, null)!!
        // Last 60 s of the stages: stage 1 (60 s at 5 %; the last record lasted 0 s).
        assertEquals(FitnessFormulas.round1(Acsm.walkingVo2(5.5, 5.0)), r.outputs!!.vo2maxMlKgMin)
        assertEquals(Vo2MaxMethod.AcsmWalkRamp, r.outputs!!.method)
        assertEquals(EffortAssessment.Maximal, r.outputs!!.effort)
        assertEquals(183, r.outputs!!.hrMaxCandidateBpm)
        assertEquals(ThresholdResult.none(ThresholdReason.NoWorkoutSamples), r.thresholds)
        assertEquals(FitnessTestResult.SCHEMA_VERSION, r.schemaVersion)
        assertEquals(old.copy(outputs = r.outputs, thresholds = r.thresholds, schemaVersion = 2), r)
        // Idempotent: nothing to do with the same data...
        assertFalse(FitnessTestRecalculation.needsRecalculation(r))
        assertNull(FitnessTestRecalculation.recalculate(r, null))
        // ...but when the workout arrives later it is computed from it.
        assertTrue(FitnessTestRecalculation.needsRecalculation(r, samplesAvailable = true))
        val later = FitnessTestRecalculation.recalculate(r, walkSamples(old))!!
        assertNotNull(later.thresholds!!.vt1, "${later.thresholds}")
    }

    @Test
    fun `a walking result with strap samples gets its VO2max from the real load and its thresholds`() {
        val old = sampleResult(FitnessTestType.MaxHeartRateWalk)
        val r = FitnessTestRecalculation.recalculate(old, walkSamples(old), restingHeartRateBpm = 60)!!
        // The last minute of the 11-min ramp (600-660 s) was walked at 15 %.
        assertEquals(FitnessFormulas.round1(Acsm.walkingVo2(5.5, 15.0)), r.outputs!!.vo2maxMlKgMin)
        val t = r.thresholds!!
        assertNull(t.reason, "$t")
        val vt1 = t.vt1!!
        // The synthetic ramp: HR 100 → 180 and α 1.2 → 0.3 over the 11-min ramp; the estimator reads 0.75 at ~142 bpm.
        assertEquals(142.0, vt1.heartRateBpm, 10.0)
        assertEquals(5.5, vt1.speedKmh!!, 1e-9)
        assertNotNull(vt1.percentVo2max)
        assertNotNull(vt1.percentHeartRateReserve)
        assertEquals(r, FitnessTestJson.decode(FitnessTestJson.encode(r)), "stored values are already rounded")
        // Same inputs, same output (two devices write the same document).
        assertEquals(r, FitnessTestRecalculation.recalculate(old, walkSamples(old), restingHeartRateBpm = 60))
    }

    @Test
    fun `console or unknown heart rate never gives thresholds, even with RR values`() {
        val old = sampleResult(FitnessTestType.MaxHeartRateWalk)
        for (source in listOf(SampleHeartRateSource.Console, SampleHeartRateSource.Unknown, null)) {
            val r = FitnessTestRecalculation.recalculate(old, walkSamples(old, source))!!
            assertEquals(ThresholdResult.none(ThresholdReason.NoRrIntervals), r.thresholds, "$source")
            assertNotNull(r.outputs)
        }
        // A strap that sends no RR.
        val noRr = FitnessTestRecalculation.recalculate(old, walkSamples(old, rr = false))!!
        assertEquals(ThresholdReason.NoRrIntervals, noRr.thresholds!!.reason)
    }

    @Test
    fun `a running result keeps its VO2max and gets thresholds - an older algorithm version is recalculated`() {
        val run = sampleResult(FitnessTestType.MaxIncrementalRun)
        val r = FitnessTestRecalculation.recalculate(run, emptyList())!!
        assertEquals(run.outputs, r.outputs)
        assertEquals(ThresholdResult.none(ThresholdReason.NoRrIntervals), r.thresholds)
        val outdated = r.copy(thresholds = r.thresholds!!.copy(algorithmVersion = 0))
        assertTrue(FitnessTestRecalculation.needsRecalculation(outdated))
        assertEquals(r, FitnessTestRecalculation.recalculate(outdated, emptyList()))
    }

    // ------------------------------------------------------------------ zones

    @Test
    fun `profile zones - measured thresholds first, then the tested VO2max level, then the self-rated one`() {
        val withThresholds = FitnessProfileCalculator.compute(listOf(walk("a", 0, 43.6, thresholds = thresholds(132.0, 151.0))), now, 40, Sex.Male)
        val z = ProfileZones.compute(withThresholds, HeartRateProfile(181, 55), SelfRatedFitness.Beginner)
        assertEquals(ZoneSource.MeasuredThresholds, z.source)
        assertEquals(listOf(122 to 132, 132 to 151, 151 to 166), (2..4).map { z.zone(it).lowBpm!! to z.zone(it).highBpm })

        // VO2max 43.6 at 40 is "good": medium level, over the self-rated beginner.
        val noThresholds = FitnessProfileCalculator.compute(listOf(walk("a", 0, 43.6)), now, 40, Sex.Male)
        val level = ProfileZones.compute(noThresholds, HeartRateProfile(180, 60), SelfRatedFitness.Beginner)
        assertEquals(ZoneSource.Vo2maxClassification, level.source)
        assertEquals(Vo2MaxCategory.Good, noThresholds.vo2max!!.classification!!.category)
        // A profile computed without age and sex is classified with the ones given.
        val unclassified = FitnessProfileCalculator.compute(listOf(walk("a", 0, 43.6)), now)
        assertEquals(level, ProfileZones.compute(unclassified, HeartRateProfile(180, 60), SelfRatedFitness.Beginner, 40, Sex.Male))
        assertEquals(ZoneSource.SelfRatedFitness, ProfileZones.compute(unclassified, HeartRateProfile(180, 60), SelfRatedFitness.Beginner).source)
        assertEquals(ZoneSource.Default, ProfileZones.compute(null, HeartRateProfile(180, null), null).source)
    }

    companion object {
        /** Spectral 1/f^β noise (β = 2α − 1), unit SD. */
        private fun noise(n: Int, alpha: Double, random: Random): DoubleArray {
            val x = DoubleArray(n)
            for (k in 1..n / 2) {
                val a = k.toDouble().pow(-(2 * alpha - 1) / 2) * random.nextGaussian()
                val phase = random.nextDouble() * 2 * PI
                for (t in 0 until n) x[t] += a * cos(2 * PI * k * t / n + phase)
            }
            val mean = x.average()
            val sd = sqrt(x.sumOf { (it - mean) * (it - mean) } / n)
            return DoubleArray(n) { (x[it] - mean) / sd }
        }

        /** Beats (time, RR) of a ramp: HR 100 → 180 and α 1.2 → 0.3 linearly. */
        fun syntheticRamp(start: Long, seconds: Int, seed: Long = 3): List<Pair<Long, Int>> {
            val random = Random(seed)
            val grid = HashMap<Int, DoubleArray>()
            val out = ArrayList<Pair<Long, Int>>()
            var t = start.toDouble()
            var i = 0
            while (t < start + seconds * 1000.0) {
                val f = (t - start) / (seconds * 1000.0)
                val key = ((1.2 - 0.9 * f) / 0.05).roundToInt()
                val series = grid.getOrPut(key) { noise(2048, key * 0.05, random) }
                val rr = 60_000.0 / (100 + 80 * f) + 20 * series[i % 2048]
                t += rr
                out += t.roundToLong() to rr.roundToInt()
                i++
            }
            return out
        }
    }
}
