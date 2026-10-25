package com.treadz.console.programs.fitness

import com.treadz.console.analysis.fitness.Sex
import com.treadz.console.analysis.samples.SampleHeartRateSource
import com.treadz.console.analysis.samples.WorkoutSample
import com.treadz.console.analysis.thresholds.ThresholdEngine
import com.treadz.console.analysis.thresholds.ThresholdReason
import com.treadz.console.analysis.thresholds.ThresholdResult
import com.treadz.console.treadmill.simulator.SimulatedHeartRateModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.roundToInt

/**
 * A maximal test whose peak heart rate is under 85 % of the maximum in use is not a maximal effort: no VO2max and no MAS,
 * not even as lower bounds, and the result says why. Live (both tests), with and without heart rate, and recalculated.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FitnessPeakRuleTest {

    /** A 35-year-old with maximum heart rate 185: 85 % is 157.25 bpm. */
    private val subject = SUBJECT.copy(ageYears = 35, hrMaxBpm = 185)

    /** 70 bpm at 6 km/h and [bpmPerKmh] more per km/h, never above [cap]; 62 when the belt is not running. */
    private fun scripted(cap: Int, bpmPerKmh: Double = 14.0) = SimulatedHeartRateModel { input ->
        if (input.beltRunning) minOf(cap, (70 + bpmPerKmh * (input.speedKmh - 6.0)).roundToInt()) else 62
    }

    private suspend fun TestScope.runningTest(model: SimulatedHeartRateModel?, after: Long = 11 * 60_000L + 20_000): FitnessRig {
        val rig = FitnessRig(this, model)
        rig.connect()
        assertEquals(FitnessActionResult.Success, rig.runner.startMaxTest(MaxTestParameters(subject, 8.0)))
        rig.awaitPhase(FitnessTestPhase.Incremental)
        delay(after)
        assertEquals(FitnessActionResult.Success, rig.runner.exhausted())
        rig.awaitFinished()
        return rig
    }

    // ------------------------------------------------------------------ running test

    @Test
    fun `running test - a peak under 85 percent gives no VO2max and no MAS, not even as bounds, and says why`() = runTest {
        val rig = runningTest(scripted(cap = 140)) // 75.7 %, with a plateau at the top
        val r = rig.result
        val m = r.measurements as MaxTestMeasurements
        assertEquals(140.0, m.peakHeartRateBpm)
        assertTrue(m.heartRatePlateau, "the plateau itself is measured")
        assertEquals(Validity.Valid, r.validity)
        assertEquals(FitnessEndReason.Exhausted, r.endReason)
        assertNull(r.outputs)
        assertEquals(OutputsWithheldReason.PeakHeartRateBelowMinimum, r.outputsWithheld)
        assertTrue(FitnessTestFlag.PeakHeartRateLow in r.flags)
        assertFalse(FitnessTestFlag.MaximalByHeartRatePlateau in r.flags, "a plateau does not make it maximal under 85 %")
        assertNull(r.measuredHrMaxCandidateBpm)
        assertNull(r.effortAssessment)
        assertNotNull(r.thresholds, "the thresholds are computed as usual (here: no RR from a console)")
        assertEquals(ThresholdResult.none(ThresholdReason.NoRrIntervals), r.thresholds)
        // JSON: the reason is stored, an older reader just sees a valid test without outputs.
        val json = FitnessTestJson.encode(r)
        assertTrue(json.contains("\"outputsWithheld\": \"peakHeartRateBelowMinimum\""), json)
        assertFalse(json.contains("\"outputs\""))
        assertEquals(r, FitnessTestJson.decode(json))
        // The profile gets nothing from it.
        val profile = FitnessProfileCalculator.compute(listOf(r), r.endedAtMillis, 35, Sex.Male)
        assertNull(profile.vo2max)
        assertNull(profile.mas)
        assertNull(profile.hrMax)
    }

    @Test
    fun `running test - the limit is 85 percent of the maximum in use`() = runTest {
        // 157.25: a peak of 158 is enough, 157 is not.
        val enough = runningTest(scripted(cap = 158, bpmPerKmh = 16.0))
        assertEquals(158.0, (enough.result.measurements as MaxTestMeasurements).peakHeartRateBpm)
        assertNotNull(enough.result.outputs)
        assertNull(enough.result.outputsWithheld)
        assertEquals(EffortAssessment.Maximal, enough.result.outputs!!.effort, "27 bpm under the maximum, but a plateau at 85 % or more still counts")
        val short = runningTest(scripted(cap = 157, bpmPerKmh = 16.0))
        assertEquals(157.0, (short.result.measurements as MaxTestMeasurements).peakHeartRateBpm)
        assertNull(short.result.outputs)
        assertEquals(OutputsWithheldReason.PeakHeartRateBelowMinimum, short.result.outputsWithheld)
    }

    @Test
    fun `running test - without a usable heart rate the rule does not apply`() = runTest {
        val rig = runningTest(model = null, after = 6 * 60_000L)
        val r = rig.result
        assertNotNull(r.outputs, "behaves as before: MAS and VO2max without an effort judgement")
        assertNull(r.outputsWithheld)
        assertEquals(EffortAssessment.Unknown, r.outputs!!.effort)
        assertNull((r.measurements as MaxTestMeasurements).peakHeartRateBpm)
    }

    @Test
    fun `running test - a stop that counts as exhaustion follows the rule too`() = runTest {
        val rig = FitnessRig(this, scripted(cap = 130))
        rig.connect()
        rig.runner.startMaxTest(MaxTestParameters(subject, 8.0))
        rig.awaitPhase(FitnessTestPhase.Incremental)
        delay(6 * 60_000L)
        rig.runner.stop()
        rig.awaitFinished()
        val r = rig.result
        assertEquals(Validity.Valid, r.validity)
        assertTrue(FitnessTestFlag.EndedWithStop in r.flags)
        assertNull(r.outputs)
        assertEquals(OutputsWithheldReason.PeakHeartRateBelowMinimum, r.outputsWithheld)
    }

    // ------------------------------------------------------------------ walking test

    private suspend fun TestScope.walkingTest(model: SimulatedHeartRateModel, afterWarmUpMs: Long = 12 * 60_000L, hook: suspend (FitnessRig) -> Unit = {}): FitnessRig {
        val rig = FitnessRig(this, model)
        rig.connect(warmSeconds = 10)
        assertEquals(FitnessActionResult.Success, rig.runner.startMaxHeartRateWalk(MaxHeartRateWalkParameters(subject)))
        delay(120_000 + afterWarmUpMs)
        assertEquals(FitnessActionResult.Success, rig.runner.exhausted())
        hook(rig)
        rig.awaitFinished()
        return rig
    }

    @Test
    fun `walking test - a plateau under 85 percent is not a maximal effort any more`() = runTest {
        // The heart rate stops rising at 125 bpm (67.6 %): a plateau, accepted as maximal before.
        val rig = walkingTest(SimulatedHeartRateModel { input -> if (input.beltRunning) minOf(125, (90 + input.inclinePercent * 4).toInt()) else 70 })
        val r = rig.result
        val m = r.measurements as MaxHeartRateWalkMeasurements
        assertTrue(m.heartRatePlateau, "the plateau is measured")
        assertEquals(EffortAssessment.NotMaximal, m.effort)
        assertEquals(Validity.Valid, r.validity)
        assertNull(r.outputs)
        assertEquals(OutputsWithheldReason.PeakHeartRateBelowMinimum, r.outputsWithheld)
        assertTrue(FitnessTestFlag.PeakHeartRateLow in r.flags)
        assertFalse(FitnessTestFlag.MaximalByHeartRatePlateau in r.flags)
        assertNull(r.measuredHrMaxCandidateBpm, "the measured maximum is not offered")
        assertEquals(EffortAssessment.NotMaximal, r.effortAssessment)
        assertEquals(r, FitnessTestJson.decode(FitnessTestJson.encode(r)))
        val profile = FitnessProfileCalculator.compute(listOf(r), r.endedAtMillis, 35, Sex.Male)
        assertNull(profile.vo2max)
        assertNull(profile.mas)
        assertNull(profile.hrMax)
    }

    @Test
    fun `walking test - the final peak decides, the one that includes the cool-down`() = runTest {
        // About 146 bpm (79 %) at the exhaustion; the heart rate keeps climbing to 176 for a few seconds afterwards.
        var afterSeconds = 0
        val model = SimulatedHeartRateModel { input ->
            when {
                !input.beltRunning -> 80
                afterSeconds > 0 -> 176.also { afterSeconds-- }
                else -> (90 + input.inclinePercent * 4 + (input.speedKmh - 5.5) * 20).toInt()
            }
        }
        val rig = walkingTest(model, afterWarmUpMs = 9 * 60_000L + 30_000) { rig ->
            afterSeconds = 10
            // The result known when the effort ends is preliminary: with the peak known then, no outputs yet.
            val preliminary = rig.result
            assertEquals(OutputsWithheldReason.PeakHeartRateBelowMinimum, preliminary.outputsWithheld)
            assertNull(preliminary.outputs)
        }
        val r = rig.result
        assertEquals(176.0, (r.measurements as MaxHeartRateWalkMeasurements).peakHeartRateBpm!!, 0.5)
        assertNull(r.outputsWithheld)
        assertNotNull(r.outputs)
        assertEquals(EffortAssessment.Maximal, r.outputs!!.effort)
        assertEquals(176, r.measuredHrMaxCandidateBpm)
    }

    // ------------------------------------------------------------------ stored results

    private fun stored(type: FitnessTestType, peak: Double?, algorithmVersion: Int = 2): FitnessTestResult {
        val base = sampleResult(type)
        val thresholds = ThresholdResult(algorithmVersion, reason = ThresholdReason.NoRrIntervals)
        return when (val m = base.measurements) {
            is MaxHeartRateWalkMeasurements -> base.copy(
                measurements = m.copy(peakHeartRateBpm = peak, hrMaxCandidateBpm = peak?.roundToInt(), heartRatePlateau = true, effort = EffortAssessment.Maximal),
                thresholds = thresholds,
                schemaVersion = 2,
            )
            is MaxTestMeasurements -> base.copy(
                measurements = m.copy(peakHeartRateBpm = peak, heartRatePlateau = true),
                outputs = base.outputs?.copy(hrMaxCandidateBpm = peak?.roundToInt(), effort = EffortAssessment.Maximal),
                thresholds = thresholds,
                schemaVersion = 2,
            )
            else -> error("maximal tests only")
        }
    }

    @Test
    fun `a stored walking result is recalculated once - version 2 had accepted the 90 bpm peak`() {
        // A short test with a peak of ~90 bpm (50 % of 180) that was accepted as valid and gave a VO2max of 11.9.
        val old = stored(FitnessTestType.MaxHeartRateWalk, peak = 90.0)
        assertTrue(FitnessTestRecalculation.needsRecalculation(old))
        val r = FitnessTestRecalculation.recalculate(old, null)!!
        assertEquals(Validity.Valid, r.validity)
        assertNull(r.outputs)
        assertEquals(OutputsWithheldReason.PeakHeartRateBelowMinimum, r.outputsWithheld)
        assertEquals(EffortAssessment.NotMaximal, (r.measurements as MaxHeartRateWalkMeasurements).effort)
        assertTrue(FitnessTestFlag.PeakHeartRateLow in r.flags)
        assertNull(r.measuredHrMaxCandidateBpm)
        // It carries the current version, so the other devices do not do it again, and it is idempotent.
        assertEquals(FitnessTestRecalculation.ALGORITHM_VERSION, r.thresholds!!.algorithmVersion)
        assertFalse(FitnessTestRecalculation.needsRecalculation(r))
        assertNull(FitnessTestRecalculation.recalculate(r, null))
        // Readable by a version-2 reader: the format version did not change and the field is optional.
        assertEquals(2, r.schemaVersion)
        assertEquals(r, FitnessTestJson.decode(FitnessTestJson.encode(r)))
        val withoutField = FitnessTestJson.encode(r).lines().filterNot { it.contains("outputsWithheld") }.joinToString("\n")
        assertNull(FitnessTestJson.decode(withoutField).outputsWithheld, "a document without the field reads as before")
        // Out of the profile.
        val profile = FitnessProfileCalculator.compute(listOf(r), r.endedAtMillis, 40, Sex.Male)
        assertNull(profile.vo2max)
        assertNull(profile.mas)
        assertNull(profile.hrMax)
    }

    @Test
    fun `a stored running result loses its MAS and VO2max too`() {
        val old = stored(FitnessTestType.MaxIncrementalRun, peak = 100.0)
        val r = FitnessTestRecalculation.recalculate(old, emptyList())!!
        assertNull(r.outputs)
        assertEquals(OutputsWithheldReason.PeakHeartRateBelowMinimum, r.outputsWithheld)
        assertEquals(Validity.Valid, r.validity)
        assertNull(FitnessProfileCalculator.compute(listOf(r), r.endedAtMillis).mas)
    }

    @Test
    fun `the recalculation uses the peak with the seconds after the effort, as the live test does`() {
        // Stored peak 150 (83 %): with the samples after the exhaustion it is 160 (88.9 %), so the outputs stay.
        val old = stored(FitnessTestType.MaxHeartRateWalk, peak = 150.0)
        val samples = (0..((old.endedAtMillis - old.startedAtMillis) / 1000)).map { s ->
            val effortEnd = (old.effortEndedAtMillis - old.startedAtMillis) / 1000
            val bpm = when {
                s <= effortEnd -> 148
                s <= effortEnd + 8 -> 160
                else -> 120
            }
            WorkoutSample(old.startedAtMillis + s * 1000L, 5.5, 0.0, bpm, SampleHeartRateSource.Strap)
        }
        val r = FitnessTestRecalculation.recalculate(old, samples)!!
        assertEquals(160.0, (r.measurements as MaxHeartRateWalkMeasurements).peakHeartRateBpm)
        assertNull(r.outputsWithheld)
        assertNotNull(r.outputs)
        assertEquals(160, r.outputs!!.hrMaxCandidateBpm)
    }

    @Test
    fun `a stored result without a peak is not touched by the rule`() {
        val old = stored(FitnessTestType.MaxHeartRateWalk, peak = null)
        val r = FitnessTestRecalculation.recalculate(old, null)!!
        assertNull(r.outputsWithheld)
        assertNotNull(r.outputs)
    }

    @Test
    fun `an invalid result and Ebbeling never get the reason`() {
        val ebbeling = sampleResult(FitnessTestType.EbbelingWalk)
        assertNull(FitnessTestRecalculation.withheldReason(ebbeling.measurements, ebbeling.endReason, 180))
        val stopped = FitnessTestRecalculation.withheldReason(
            (stored(FitnessTestType.MaxIncrementalRun, 100.0)).measurements, FitnessEndReason.StoppedByUser, 180,
        )
        assertNull(stopped, "only an effort that ended by exhaustion is judged")
        assertEquals(4, ThresholdEngine.ALGORITHM_VERSION)
    }
}
