package com.treadz.console.programs.fitness

import com.treadz.console.analysis.fitness.FitnessFormulas
import com.treadz.console.analysis.fitness.Sex
import com.treadz.console.analysis.fitness.Vo2MaxNorms
import com.treadz.console.analysis.thresholds.ThresholdEngine
import com.treadz.console.analysis.thresholds.ThresholdReason
import com.treadz.console.analysis.thresholds.ThresholdResult
import com.treadz.console.programs.engine.HeartRateRig
import com.treadz.console.treadmill.BeltStatus
import com.treadz.console.treadmill.SafetyLimits
import com.treadz.console.treadmill.simulator.PhysiologicalHeartRateModel
import com.treadz.console.treadmill.simulator.SimulatedHeartRateModel
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** The maximal heart-rate walking test (modified Balke) on the simulator. Subject: 40 years, maximum 180. */
@OptIn(ExperimentalCoroutinesApi::class)
class MaxHeartRateWalkScenariosTest {

    /** VO2max 45, maximum 180: at 5.5 km/h the demand passes 45 two to three minutes after reaching 18 %. */
    private fun walker() = PhysiologicalHeartRateModel(restingBpm = 60, maxBpm = 180, vo2maxMlKgMin = 45.0)

    private suspend fun TestScope.started(
        model: SimulatedHeartRateModel? = walker(),
        parameters: MaxHeartRateWalkParameters = MaxHeartRateWalkParameters(SUBJECT),
        limits: SafetyLimits = SafetyLimits(),
    ): FitnessRig {
        val rig = FitnessRig(this, model)
        rig.controller.setLimits(limits)
        rig.connect(warmSeconds = 10)
        assertEquals(FitnessActionResult.Success, rig.runner.startMaxHeartRateWalk(parameters))
        return rig
    }

    private val FitnessRig.walk: MaxHeartRateWalkMeasurements get() = result.measurements as MaxHeartRateWalkMeasurements

    private suspend fun FitnessRig.runToExhaustion() {
        awaitTrue(message = "simulated exhaustion") { physiology.secondsAboveVo2max >= 60 }
        delay(20_000)
        assertEquals(FitnessActionResult.Success, runner.exhausted())
    }

    @Test
    fun `happy path - warm-up, incline ramp, then speed, exhaustion, cool-down`() = runTest {
        val rig = started()
        rig.progress.let {
            assertEquals(FitnessTestType.MaxHeartRateWalk, it.testType)
            assertEquals(FitnessTestPhase.WarmUp, it.phase)
            assertEquals(FitnessInstruction.WarmUpWalk, it.instruction)
            assertEquals(120, it.phaseRemainingSeconds)
            assertEquals(5.5, it.nextStageSpeedKmh)
            assertEquals(5.0, it.nextStageInclinePercent)
            assertFalse(it.canReportExhaustion)
        }
        delay(40_000)
        assertEquals(4.1, rig.sim.currentSpeedKmh, 0.01) // 75 % of 5.5
        assertEquals(0.0, rig.sim.currentInclinePercent)
        delay(60_000)
        assertEquals(4.4, rig.sim.currentSpeedKmh, 0.01) // 80 % of 5.5
        assertEquals(1.0, rig.sim.currentInclinePercent)
        delay(30_000) // t = 130 s: ramp stage 1
        rig.progress.let {
            assertEquals(FitnessTestPhase.Incremental, it.phase)
            assertEquals(FitnessInstruction.WalkUphillUntilExhausted, it.instruction)
            assertEquals(1, it.stageNumber)
            assertEquals(6.0, it.nextStageInclinePercent)
            assertTrue(it.canReportExhaustion)
        }
        assertEquals(5.5, rig.sim.currentSpeedKmh, 0.01)
        assertEquals(5.0, rig.sim.currentInclinePercent)
        delay(13 * 60_000L) // stage 14: 18 %, still 5.5 km/h
        assertEquals(18.0, rig.sim.currentInclinePercent)
        assertEquals(5.5, rig.sim.currentSpeedKmh, 0.01)
        delay(60_000) // stage 15: +0.2 km/h (the equivalent of +1 %)
        assertEquals(18.0, rig.sim.currentInclinePercent)
        assertEquals(5.7, rig.sim.currentSpeedKmh, 0.01)
        rig.runToExhaustion()

        rig.progress.let {
            assertEquals(FitnessTestPhase.CoolDown, it.phase)
            assertEquals(FitnessEndReason.Exhausted, it.endReason)
        }
        delay(30_000)
        assertEquals(5.0, rig.sim.currentSpeedKmh, 0.01)
        assertEquals(0.0, rig.sim.currentInclinePercent)
        rig.awaitFinished()
        assertEquals(BeltStatus.Idle, rig.controller.state.value.status)

        val r = rig.result
        assertEquals(Validity.Valid, r.validity, "${r.flags}")
        // VO2max: ACSM at the mean real load of the last minute (the simulated walker's VO2max is 45).
        val o = checkNotNull(r.outputs)
        assertEquals(Vo2MaxMethod.AcsmWalkRamp, o.method)
        assertEquals(3.5, o.vo2maxSeeMlKgMin)
        assertTrue(o.vo2maxMlKgMin in 42.0..49.0, "${o.vo2maxMlKgMin}")
        assertFalse(o.vo2maxIsLowerBound)
        assertTrue(o.masIsEstimated)
        assertEquals(FitnessFormulas.round2(o.vo2maxMlKgMin / 3.5), o.masKmh)
        assertEquals(EffortAssessment.Maximal, o.effort)
        assertEquals(rig.walk.hrMaxCandidateBpm, o.hrMaxCandidateBpm)
        assertEquals(Vo2MaxNorms.classify(o.vo2maxMlKgMin, 40, Sex.Male), o.classification)
        // The console's heart rate has no RR intervals: no thresholds, and the reason is stored.
        assertEquals(ThresholdResult.none(ThresholdReason.NoRrIntervals), r.thresholds)
        assertEquals(FitnessTestResult.SCHEMA_VERSION, r.schemaVersion)
        assertEquals(180, r.coolDownSeconds)
        val m = rig.walk
        assertEquals(0.2, m.speedIncrementKmh)
        assertEquals(18.0, m.inclineCapPercent)
        assertTrue(m.rampSeconds >= 15 * 60, "${m.rampSeconds}")
        val peak = checkNotNull(m.peakHeartRateBpm)
        assertTrue(peak in 172.0..181.0, "$peak")
        assertEquals(EffortAssessment.Maximal, m.effort)
        assertEquals(peak.toInt().toDouble(), m.hrMaxCandidateBpm!!.toDouble(), 1.0)
        assertEquals(m.hrMaxCandidateBpm, r.measuredHrMaxCandidateBpm)
        assertEquals(EffortAssessment.Maximal, r.effortAssessment)
        assertEquals(HeartRateSourceKind.TreadmillConsole, r.inputs.heartRateSource)
        assertEquals(5.5, r.inputs.startSpeedKmh)
        assertTrue(m.stages.first().let { it.speedKmh == 5.5 && it.inclinePercent == 5.0 && it.completed })

        // The JSON keeps everything.
        assertEquals(r, FitnessTestJson.decode(FitnessTestJson.encode(r)))
        assertTrue(FitnessTestJson.encode(r).contains("\"testType\": \"maxHeartRateWalk\""))

        // It feeds the measured maximum and the VO2max of the profile; the MAS is estimated from it.
        val profile = FitnessProfileCalculator.compute(listOf(r), r.endedAtMillis, 40, Sex.Male)
        assertEquals(o.vo2maxMlKgMin, profile.vo2max?.valueMlKgMin)
        assertEquals(true, profile.mas?.estimated)
        assertNull(profile.thresholds)
        assertEquals(m.hrMaxCandidateBpm, profile.hrMax?.measuredBpm)
        assertEquals(r.id, profile.hrMax?.sourceResultId)
        val decision = HeartRateMaxPolicy.resolve(null, null, profile.hrMax, 180)
        assertEquals(HeartRateMaxOrigin.Measured, decision.origin)
    }

    @Test
    fun `the heart rate of a strap connected to the app is recorded as its source`() = runTest {
        val rig = HeartRateRig(this, physiology = walker())
        rig.connect(withStrap = true)
        delay(10_000)
        val runner = FitnessTestRunner(rig.controller, backgroundScope, rig.clock, heartRate = rig.source)
        assertEquals(FitnessActionResult.Success, runner.startMaxHeartRateWalk(MaxHeartRateWalkParameters(SUBJECT)))
        rig.awaitTrue(message = "exhaustion") { rig.physiology.secondsAboveVo2max >= 60 }
        assertEquals(FitnessActionResult.Success, runner.exhausted())
        rig.awaitTrue(message = "finished") { runner.progress.value.phase == FitnessTestPhase.Finished }
        val r = checkNotNull(runner.progress.value.result)
        assertEquals(Validity.Valid, r.validity, "${r.flags}")
        assertEquals(HeartRateSourceKind.DirectStrap, r.inputs.heartRateSource)
        assertEquals(EffortAssessment.Maximal, r.effortAssessment)
        // The strap's RR intervals reached the threshold analysis (the simulated strap's RR have no real variability,
        // so the thresholds themselves are not meaningful here).
        val t = checkNotNull(r.thresholds)
        assertEquals(ThresholdEngine.ALGORITHM_VERSION, t.algorithmVersion)
        assertTrue(t.reason != ThresholdReason.NoRrIntervals, "$t")
        assertEquals(Vo2MaxMethod.AcsmWalkRamp, r.outputs?.method)
    }

    @Test
    fun `incline limit of the user - the speed ramp starts there with a bigger step`() = runTest {
        val rig = started(limits = SafetyLimits(maxInclinePercent = 10.0))
        delay(120_000 + 5 * 60_000L + 30_000) // stage 6: 10 %
        assertEquals(10.0, rig.sim.currentInclinePercent)
        assertEquals(5.5, rig.sim.currentSpeedKmh, 0.01)
        delay(60_000) // stage 7: +0.4 km/h, the equivalent of +1 % at 10 %
        assertEquals(10.0, rig.sim.currentInclinePercent)
        assertEquals(5.9, rig.sim.currentSpeedKmh, 0.01)
        assertTrue(rig.inclineCommands.all { it <= 10.0 })
        rig.runner.stop()
        assertEquals(0.4, rig.walk.speedIncrementKmh)
        assertEquals(10.0, rig.walk.inclineCapPercent)
    }

    @Test
    fun `a manual change during the ramp makes it invalid, the cool-down follows`() = runTest {
        val rig = started()
        delay(200_000)
        rig.sim.physicalSetSpeed(6.0)
        rig.awaitPhase(FitnessTestPhase.CoolDown)
        assertEquals(Validity.Invalid, rig.result.validity)
        assertTrue(FitnessTestFlag.ManualChange in rig.result.flags)
        assertNull(rig.result.measuredHrMaxCandidateBpm)
        rig.awaitFinished()
    }

    @Test
    fun `manual changes in the warm-up are kept until the next warm-up minute`() = runTest {
        val rig = started()
        delay(20_000)
        rig.sim.physicalSetSpeed(4.6)
        delay(20_000)
        assertEquals(4.6, rig.sim.currentSpeedKmh, 0.01)
        assertEquals(FitnessTestPhase.WarmUp, rig.progress.phase)
        delay(40_000) // second warm-up minute
        assertEquals(4.4, rig.sim.currentSpeedKmh, 0.01)
        rig.runner.stop()
        assertTrue(FitnessTestFlag.ManualChange !in rig.result.flags)
    }

    @Test
    fun `the app's controls work in the cool-down without spoiling the test, not before`() = runTest {
        val rig = started()
        delay(20_000)
        assertFalse(rig.progress.manualChangesAllowed, "the warm-up offers the ramp start instead")
        assertTrue(rig.runner.adjustByHand(4.6, null) is FitnessActionResult.NotAllowed)

        delay(130_000) // ramp
        assertEquals(FitnessTestPhase.Incremental, rig.progress.phase)
        assertFalse(rig.progress.manualChangesAllowed)
        assertTrue(rig.runner.adjustByHand(6.0, null) is FitnessActionResult.NotAllowed)
        assertEquals(5.5, rig.sim.currentSpeedKmh, 0.01)

        delay(190_000)
        assertEquals(FitnessActionResult.Success, rig.runner.exhausted())
        assertEquals(FitnessTestPhase.CoolDown, rig.progress.phase)
        delay(10_000)
        val before = rig.progress.result
        assertTrue(rig.progress.manualChangesAllowed)
        assertEquals(FitnessActionResult.Success, rig.runner.adjustByHand(4.0, null))
        assertEquals(FitnessActionResult.Success, rig.runner.adjustByHand(null, 3.0))
        delay(20_000)
        assertEquals(4.0, rig.sim.currentSpeedKmh, 0.01, "not undone by the cool-down")
        assertEquals(3.0, rig.sim.currentInclinePercent, 0.01)
        assertEquals(FitnessTestPhase.CoolDown, rig.progress.phase)
        rig.runner.stop()
        rig.awaitFinished()
        val r = rig.result
        assertEquals(before?.validity, r.validity)
        assertEquals(FitnessEndReason.Exhausted, r.endReason)
        assertTrue(FitnessTestFlag.ManualChange !in r.flags, "${r.flags}")
    }

    @Test
    fun `STOP after 3 minutes of ramp counts as exhaustion`() = runTest {
        val rig = started()
        delay(120_000 + 13 * 60_000L)
        assertEquals(FitnessActionResult.Success, rig.runner.stop())
        rig.awaitFinished()
        val r = rig.result
        assertEquals(Validity.Valid, r.validity, "${r.flags}")
        assertTrue(FitnessTestFlag.EndedWithStop in r.flags)
        assertEquals(FitnessEndReason.Exhausted, r.endReason)
        assertTrue(rig.walk.hrMaxCandidateBpm != null)
    }

    @Test
    fun `exhausted before 3 minutes of ramp is invalid, and STOP then too`() = runTest {
        val rig = started()
        delay(120_000 + 100_000)
        assertEquals(FitnessActionResult.Success, rig.runner.exhausted())
        assertEquals(Validity.Invalid, rig.result.validity)
        assertTrue(FitnessTestFlag.IncrementalPhaseTooShort in rig.result.flags)
        rig.awaitFinished()

        val second = started()
        delay(150_000)
        second.runner.stop()
        assertEquals(Validity.Invalid, second.result.validity)
        assertTrue(FitnessTestFlag.StoppedByUser in second.result.flags)
    }

    @Test
    fun `the ramp start can be changed before starting or during the warm-up`() = runTest {
        val rig = started(parameters = MaxHeartRateWalkParameters(SUBJECT, startSpeedKmh = 5.0, startInclinePercent = 3.0))
        assertEquals(5.0, rig.progress.nextStageSpeedKmh)
        assertEquals(3.0, rig.progress.nextStageInclinePercent)
        delay(10_000)
        assertEquals(FitnessActionResult.Success, rig.runner.adjustRampStart(6.0, 4.0))
        delay(20_000)
        assertEquals(4.5, rig.sim.currentSpeedKmh, 0.01) // 75 % of 6.0
        delay(110_000) // ramp stage 1
        assertEquals(6.0, rig.sim.currentSpeedKmh, 0.01)
        assertEquals(4.0, rig.sim.currentInclinePercent)
        assertTrue(rig.runner.adjustRampStart(5.0, null) is FitnessActionResult.NotAllowed, "not during the ramp")
        rig.runner.stop()
        assertEquals(6.0, rig.walk.startSpeedKmh)
        assertEquals(4.0, rig.walk.startInclinePercent)
    }

    @Test
    fun `it needs no weight, unlike the tests that estimate the VO2max`() = runTest {
        val rig = started(parameters = MaxHeartRateWalkParameters(SUBJECT.copy(weightKg = 0.0)))
        assertEquals(FitnessTestPhase.WarmUp, rig.progress.phase)
        rig.runner.stop()
        assertEquals(0.0, rig.result.inputs.weightKg)
        assertEquals(rig.result, FitnessTestJson.decode(FitnessTestJson.encode(rig.result)))

        val other = FitnessRig(this, walker())
        other.connect(warmSeconds = 10)
        val refused = other.runner.startEbbeling(EbbelingParameters(SUBJECT.copy(weightKg = 0.0)))
        assertEquals(FitnessRefusal.InvalidSubject, (refused as FitnessActionResult.NotAllowed).refusal)
    }

    @Test
    fun `it needs a heart rate to start`() = runTest {
        val rig = FitnessRig(this, model = null)
        rig.connect()
        val r = rig.runner.startMaxHeartRateWalk(MaxHeartRateWalkParameters(SUBJECT))
        assertTrue(r is FitnessActionResult.NotAllowed && r.refusal == FitnessRefusal.NoHeartRate, "$r")
    }

    @Test
    fun `impossible spikes do not count for the maximum`() = runTest {
        // A heart rate rising with the load, plus a one-second 230 spike near the end.
        var spike = false
        val model = SimulatedHeartRateModel { input ->
            when {
                !input.beltRunning -> 80
                spike -> 230.also { spike = false }
                else -> (90 + input.inclinePercent * 4 + (input.speedKmh - 5.5) * 20).toInt()
            }
        }
        val rig = started(model = model)
        delay(120_000 + 12 * 60_000L)
        spike = true
        delay(60_000)
        assertEquals(FitnessActionResult.Success, rig.runner.exhausted())
        val peak = checkNotNull(rig.walk.peakHeartRateBpm)
        assertTrue(peak < 170, "the spike was ignored: $peak")
    }
}
