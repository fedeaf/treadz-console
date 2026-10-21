package com.treadz.console.programs.fitness

import com.treadz.console.analysis.fitness.FitnessFormulas
import com.treadz.console.analysis.fitness.Sex
import com.treadz.console.programs.fitness.FitnessRig.Companion.START
import com.treadz.console.programs.fitness.FitnessRig.Companion.STOP_PAUSE
import com.treadz.console.treadmill.BeltStatus
import com.treadz.console.treadmill.ConnectionState
import com.treadz.console.treadmill.SafetyLimits
import com.treadz.console.treadmill.simulator.PhysiologicalHeartRateModel
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

@OptIn(ExperimentalCoroutinesApi::class)
class MaxTestScenariosTest {

    /** A 35-year-old runner with VO2max 50 and maximum heart rate 185: the demand passes 50 at ~13.4 km/h. */
    private fun runner() = PhysiologicalHeartRateModel(restingBpm = 60, maxBpm = 185, vo2maxMlKgMin = 50.0)

    private val subject = SUBJECT.copy(ageYears = 35, hrMaxBpm = 185)

    private suspend fun TestScope.started(
        model: SimulatedHeartRateModel? = runner(),
        startSpeed: Double = 8.0,
        limits: SafetyLimits = SafetyLimits(),
    ): FitnessRig {
        val rig = FitnessRig(this, model)
        rig.controller.setLimits(limits)
        rig.connect()
        assertEquals(FitnessActionResult.Success, rig.runner.startMaxTest(MaxTestParameters(subject, startSpeed)))
        return rig
    }

    private val FitnessRig.measurements: MaxTestMeasurements get() = result.measurements as MaxTestMeasurements

    /** Waits until the simulated body has been above its VO2max for a minute (and 20 s more), then reports exhaustion. */
    private suspend fun FitnessRig.runToExhaustion() {
        awaitTrue(message = "simulated exhaustion") { physiology.secondsAboveVo2max >= 60 }
        delay(20_000)
        assertEquals(FitnessActionResult.Success, runner.exhausted())
    }

    /**
     * A scripted heart rate: 70 bpm at 6 km/h and 10 bpm more for every km/h, never above [cap]; 62 when the belt is not
     * running. The plateau rule sees the heart rate at the end of each stage: +5 bpm per stage until the cap, then flat.
     */
    private fun plateauModel(cap: Int, bpmPerKmh: Double = 10.0) = SimulatedHeartRateModel { input ->
        if (input.beltRunning) minOf(cap, (70 + bpmPerKmh * (input.speedKmh - 6.0)).roundToInt()) else 62
    }

    // ------------------------------------------------------------------ happy path

    @Test
    fun `happy path - warm-up, +0_5 km per hour every minute at 1 percent, exhaustion, recovery walk, stop`() = runTest {
        val rig = started()
        rig.progress.let {
            assertEquals(FitnessTestPhase.WarmUp, it.phase)
            assertEquals(FitnessInstruction.WarmUpWalk, it.instruction)
            assertEquals(300, it.phaseRemainingSeconds)
            assertEquals(8.0, it.nextStageSpeedKmh)
            assertFalse(it.canReportExhaustion)
        }
        delay(150_000)
        assertEquals(FitnessInstruction.WarmUpJog, rig.progress.instruction)
        assertEquals(6.5, rig.sim.currentSpeedKmh, 0.01)
        assertEquals(1.0, rig.sim.currentInclinePercent)
        delay(180_000) // t = 330 s: stage 1 of the incremental phase
        rig.progress.let {
            assertEquals(FitnessTestPhase.Incremental, it.phase)
            assertEquals(FitnessInstruction.RunUntilExhausted, it.instruction)
            assertEquals(1, it.stageNumber)
            assertEquals(30.0, it.stageRemainingSeconds!!.toDouble(), 1.0)
            assertEquals(8.5, it.nextStageSpeedKmh)
            assertTrue(it.canReportExhaustion)
            assertNull(it.masIfExhaustedNowKmh, "no stage completed yet")
        }
        delay(120_000) // t = 450 s: stage 3 at 9 km/h, 30 s in
        assertEquals(9.0, rig.sim.currentSpeedKmh, 0.01)
        assertEquals(8.75, rig.progress.masIfExhaustedNowKmh!!, 0.01)

        rig.runToExhaustion()
        assertEquals(FitnessTestPhase.CoolDown, rig.progress.phase)
        assertNotNull(rig.progress.result?.outputs, "result known during the recovery walk")
        rig.awaitFinished()

        val r = rig.result
        val m = rig.measurements
        val o = r.outputs!!
        assertEquals(Validity.Valid, r.validity, "${r.flags}")
        assertEquals(FitnessEndReason.Exhausted, r.endReason)
        assertEquals(emptyList<FitnessTestFlag>(), r.flags)
        assertEquals(300, m.warmUpSeconds)
        assertEquals(20.0, m.capKmh)
        assertFalse(m.capReached)
        // MAS from the incremental time, and VO2max = 3.5 * MAS.
        val incremental = (r.effortEndedAtMillis - r.startedAtMillis) / 1000.0 - 300
        assertEquals(m.incrementalSeconds.toDouble(), incremental, 1.0)
        assertEquals(FitnessFormulas.mas(8.0, 0.5, 60, incremental)!!, o.masKmh!!, 0.01)
        assertTrue(o.masKmh!! in 13.3..14.2, "the simulated runner's limit is ~13.4 km/h (+1 min): ${o.masKmh}")
        assertEquals(FitnessFormulas.round1(3.5 * o.masKmh!!), o.vo2maxMlKgMin)
        assertEquals(4.0, o.vo2maxSeeMlKgMin)
        assertEquals(Vo2MaxMethod.LegerMercier1984, o.method)
        assertFalse(o.masIsLowerBound)
        assertEquals((incremental / 60).toInt(), m.stages.count { it.completed })
        assertEquals(m.lastCompletedStageSpeedKmh, m.stages.last { it.completed }.speedKmh)
        assertTrue(m.stages.all { it.inclinePercent == 1.0 })
        assertEquals((0 until m.stages.size).map { 8.0 + 0.5 * it }, m.stages.map { it.speedKmh })
        // Heart rate: reliable, peak near the maximum, maximal effort.
        assertEquals(1.0, m.heartRateCoverage!!, 0.02)
        assertTrue(m.peakHeartRateBpm!! >= 175.0, "${m.peakHeartRateBpm}")
        assertEquals(Math.round(m.peakHeartRateBpm!!).toInt(), o.hrMaxCandidateBpm)
        assertEquals(EffortAssessment.Maximal, o.effort)

        // Recovery walk at 5 km/h and 0 % for 5 min, then the belt stops.
        assertEquals(300, r.coolDownSeconds)
        assertEquals(5.0, rig.speedCommands.last())
        assertEquals(0.0, rig.inclineCommands.last())
        assertEquals(listOf(1.0, 0.0), rig.inclineCommands, "1 % from the start, 0 % for the recovery")
        assertEquals(1, rig.count(START))
        assertEquals(1, rig.count(STOP_PAUSE))
        assertTrue(rig.sim.forbiddenCommands.isEmpty())
        assertEquals(BeltStatus.Idle, rig.controller.state.value.status)
        assertEquals(
            listOf(FitnessTestPhase.Idle, FitnessTestPhase.Starting, FitnessTestPhase.WarmUp, FitnessTestPhase.Incremental, FitnessTestPhase.CoolDown, FitnessTestPhase.Stopping, FitnessTestPhase.Finished),
            rig.phases.toList(),
        )
        assertEquals(m.stages.size, rig.eventsOf<FitnessTestEvent.StageStarted>().size)
        assertFalse(m.stages.last().completed)
        assertEquals(20, m.secondsIntoLastStage)
        assertEquals(r, FitnessTestJson.decode(FitnessTestJson.encode(r)))
    }

    // ------------------------------------------------------------------ cap and effort

    @Test
    fun `the speed cap is held until exhaustion and the MAS is a lower bound`() = runTest {
        val rig = started(limits = SafetyLimits(maxSpeedKmh = 11.0))
        rig.awaitPhase(FitnessTestPhase.Incremental)
        // Stages 8 ... 11 (7 stages), then the cap: from 7 min in, the speed stays at 11.
        rig.awaitTrue(message = "cap") { rig.progress.capReached }
        rig.progress.let {
            assertEquals(FitnessInstruction.HoldTopSpeed, it.instruction)
            assertNull(it.nextStageSpeedKmh)
            assertNull(it.stageRemainingSeconds)
            assertEquals(11.0, it.masIfExhaustedNowKmh)
        }
        delay(120_000)
        assertEquals(11.0, rig.sim.currentSpeedKmh, 0.01)
        assertEquals(FitnessActionResult.Success, rig.runner.exhausted())
        rig.awaitFinished()
        val r = rig.result
        val o = r.outputs!!
        assertEquals(Validity.Valid, r.validity)
        assertTrue(FitnessTestFlag.SpeedCapReached in r.flags)
        assertEquals(11.0, o.masKmh)
        assertTrue(o.masIsLowerBound)
        assertTrue(o.vo2maxIsLowerBound)
        assertEquals(38.5, o.vo2maxMlKgMin)
        assertTrue(rig.measurements.capReached)
        assertEquals(11.0, rig.measurements.capKmh)
        assertTrue(rig.speedCommands.all { it <= 11.0 })
        // The runner was far from its limit at 11 km/h: the peak heart rate says so.
        assertEquals(EffortAssessment.NotMaximal, o.effort)
        assertTrue(FitnessTestFlag.PeakHeartRateLow in r.flags)
    }

    @Test
    fun `a heart-rate plateau with confirmed exhaustion is a maximal effort even far below the estimated maximum, but not adopted as HRmax`() = runTest {
        val rig = started(plateauModel(cap = 130)) // the cap is reached at 12 km/h
        rig.awaitPhase(FitnessTestPhase.Incremental)
        delay(11 * 60_000L + 20_000) // 11 completed stages (8 ... 13), 20 s into the 13.5 km/h stage
        assertEquals(FitnessActionResult.Success, rig.runner.exhausted())
        rig.awaitFinished()
        val r = rig.result
        val o = r.outputs!!
        val m = rig.measurements
        assertEquals(Validity.Valid, r.validity, "${r.flags}")
        assertEquals(130.0, m.peakHeartRateBpm)
        assertTrue(185 - 130 > 10, "far below the profile maximum")
        assertTrue(m.heartRatePlateau)
        assertEquals(0.0, m.plateauRiseBpm!!, 0.01, "130 at 13.0 km/h, 130 at 12.0 km/h")
        assertEquals(EffortAssessment.Maximal, o.effort)
        assertEquals(130, o.hrMaxCandidateBpm)
        assertTrue(FitnessTestFlag.MaximalByHeartRatePlateau in r.flags)
        assertFalse(FitnessTestFlag.PeakHeartRateLow in r.flags)
        assertFalse(FitnessTestFlag.EndedWithStop in r.flags)
        assertEquals(300, r.coolDownSeconds, "button exhaustion still has the recovery walk")
        assertEquals(r, FitnessTestJson.decode(FitnessTestJson.encode(r)))

        // 130 is more than 25 bpm below the Tanaka estimate for 35 (184): the profile does not adopt it, but the result
        // still counts for VO2max and MAS.
        val profile = FitnessProfileCalculator.compute(listOf(r), r.endedAtMillis, 35, Sex.Male)
        assertNull(profile.hrMax)
        assertEquals(o.masKmh, profile.mas?.valueKmh)
    }

    @Test
    fun `the plateau rule - rise over the last two completed stages below 3 bpm`() = runTest {
        // cap 127: ... 11.5 km/h = 125, 12.0 = 127 (cap), 12.5 = 127. Exhausted 20 s into the 13.0 stage: completed up to 12.5.
        val flat = started(plateauModel(cap = 127))
        flat.awaitPhase(FitnessTestPhase.Incremental)
        delay(10 * 60_000L + 20_000)
        flat.runner.exhausted()
        flat.awaitFinished()
        assertEquals(2.0, flat.measurements.plateauRiseBpm!!, 0.01, "127 - 125")
        assertTrue(flat.measurements.heartRatePlateau)
        assertEquals(EffortAssessment.Maximal, flat.result.outputs!!.effort)

        // cap 130: 11.5 km/h = 125, 12.0 = 130, 12.5 = 130: rise 5 bpm, still climbing -> no plateau.
        val rising = started(plateauModel(cap = 130))
        rising.awaitPhase(FitnessTestPhase.Incremental)
        delay(10 * 60_000L + 20_000)
        rising.runner.exhausted()
        rising.awaitFinished()
        assertEquals(5.0, rising.measurements.plateauRiseBpm!!, 0.01)
        assertFalse(rising.measurements.heartRatePlateau)
        assertEquals(EffortAssessment.NotMaximal, rising.result.outputs!!.effort)
        assertTrue(FitnessTestFlag.PeakHeartRateLow in rising.result.flags)
        assertFalse(FitnessTestFlag.MaximalByHeartRatePlateau in rising.result.flags)
    }

    @Test
    fun `no plateau - the heart rate keeps rising with the speed and the peak is low - not maximal`() = runTest {
        val rig = started(plateauModel(cap = 200))
        rig.awaitPhase(FitnessTestPhase.Incremental)
        delay(11 * 60_000L + 20_000)
        rig.runner.exhausted()
        rig.awaitFinished()
        val r = rig.result
        assertEquals(10.0, rig.measurements.plateauRiseBpm!!, 0.01, "+5 bpm per stage")
        assertFalse(rig.measurements.heartRatePlateau)
        assertEquals(EffortAssessment.NotMaximal, r.outputs!!.effort)
        assertTrue(FitnessTestFlag.PeakHeartRateLow in r.flags)
        assertEquals(145, r.outputs!!.hrMaxCandidateBpm, "peak includes the 13.5 km/h stage")
        // A not-maximal peak is not adopted as the maximum.
        assertNull(FitnessProfileCalculator.compute(listOf(r), r.endedAtMillis).hrMax)
    }

    @Test
    fun `a flat heart rate at the speed cap is not a plateau - the speed is not rising`() = runTest {
        val rig = started(plateauModel(cap = 200), limits = SafetyLimits(maxSpeedKmh = 11.0))
        rig.awaitPhase(FitnessTestPhase.Incremental)
        delay(10 * 60_000L)
        assertTrue(rig.progress.capReached)
        rig.runner.exhausted()
        rig.awaitFinished()
        val r = rig.result
        assertNull(rig.measurements.plateauRiseBpm, "last two stages are both at the cap")
        assertFalse(rig.measurements.heartRatePlateau)
        assertEquals(EffortAssessment.NotMaximal, r.outputs!!.effort)
        assertTrue(FitnessTestFlag.PeakHeartRateLow in r.flags)
    }

    @Test
    fun `a plateau needs an effort of at least 5 min`() = runTest {
        // 20 bpm per km/h from 70 bpm at 6 km/h, saturating at 80: stages 70, 80, 80, 80; exhaustion at 4 min 20 s.
        val rig = started(plateauModel(cap = 80, bpmPerKmh = 20.0), startSpeed = 6.0)
        rig.awaitPhase(FitnessTestPhase.Incremental)
        delay(260_000)
        rig.runner.exhausted()
        rig.awaitFinished()
        val r = rig.result
        assertEquals(Validity.Valid, r.validity)
        assertTrue(rig.measurements.heartRatePlateau, "the plateau itself is measured")
        assertEquals(EffortAssessment.NotMaximal, r.outputs!!.effort)
        assertTrue(FitnessTestFlag.ShortIncrementalPhase in r.flags)
        assertFalse(FitnessTestFlag.MaximalByHeartRatePlateau in r.flags)
    }

    @Test
    fun `stopping early with a low peak heart rate is reported as not maximal`() = runTest {
        val rig = started()
        rig.awaitPhase(FitnessTestPhase.Incremental)
        delay(6 * 60_000L + 20_000)
        assertEquals(FitnessActionResult.Success, rig.runner.exhausted())
        rig.awaitFinished()
        val r = rig.result
        val o = r.outputs!!
        assertEquals(Validity.Valid, r.validity)
        assertEquals(EffortAssessment.NotMaximal, o.effort)
        assertTrue(FitnessTestFlag.PeakHeartRateLow in r.flags)
        assertFalse(FitnessTestFlag.ShortIncrementalPhase in r.flags)
        assertEquals(8.0 + 0.5 * 5 + 0.5 * 20 / 60.0, o.masKmh!!, 0.02)
        assertTrue(o.hrMaxCandidateBpm!! < 175)
    }

    @Test
    fun `exhaustion within 3 min of the incremental phase is invalid, within 5 min not maximal`() = runTest {
        val rig = started()
        rig.awaitPhase(FitnessTestPhase.Incremental)
        delay(150_000)
        rig.runner.exhausted()
        rig.awaitFinished()
        assertEquals(Validity.Invalid, rig.result.validity)
        assertTrue(FitnessTestFlag.IncrementalPhaseTooShort in rig.result.flags)
        assertNull(rig.result.outputs)
        assertEquals(300, rig.result.coolDownSeconds, "the recovery walk still happens")

        val rig2 = started()
        rig2.awaitPhase(FitnessTestPhase.Incremental)
        delay(240_000)
        rig2.runner.exhausted()
        rig2.awaitFinished()
        assertEquals(Validity.Valid, rig2.result.validity)
        assertTrue(FitnessTestFlag.ShortIncrementalPhase in rig2.result.flags)
        assertEquals(EffortAssessment.NotMaximal, rig2.result.outputs!!.effort)
    }

    @Test
    fun `the peak takes the seconds after the exhaustion but not an impossible reading in the cool-down`() = runTest {
        // A heart rate that keeps rising for a few seconds after the exhaustion, then a strap moved: a doubled reading.
        var after = 0
        var doubled = 0
        val model = SimulatedHeartRateModel { input ->
            when {
                !input.beltRunning -> 62
                doubled > 0 -> 250.also { doubled-- }
                after > 0 -> 183.also { after-- }
                else -> minOf(178, (120 + 10 * (input.speedKmh - 8.0)).roundToInt())
            }
        }
        val rig = started(model = model)
        rig.awaitPhase(FitnessTestPhase.Incremental)
        delay(8 * 60_000L) // 12 km/h: 160 bpm, still rising with every stage
        assertEquals(FitnessActionResult.Success, rig.runner.exhausted())
        after = 10
        delay(60_000)
        doubled = 10 // above 240: impossible
        rig.awaitFinished()
        assertEquals(183.0, rig.measurements.peakHeartRateBpm!!, 0.5)
        assertEquals(183, rig.result.outputs!!.hrMaxCandidateBpm)
    }

    @Test
    fun `+1 in the warm-up delays the incremental phase by a minute per tap`() = runTest {
        val rig = started()
        assertTrue(rig.progress.canExtendPhase)
        delay(30_000)
        assertEquals(FitnessActionResult.Success, rig.runner.extendPhase()) // the walk lasts 3 min
        delay(140_000) // t = 170 s
        assertEquals(5.5, rig.sim.currentSpeedKmh, 0.01)
        delay(30_000) // t = 200 s: the jog
        assertEquals(6.5, rig.sim.currentSpeedKmh, 0.01)
        assertEquals(FitnessActionResult.Success, rig.runner.extendPhase()) // the jog lasts 4 min
        delay(200_000) // t = 400 s
        assertEquals(FitnessTestPhase.WarmUp, rig.progress.phase)
        delay(30_000) // t = 430 s: the incremental phase started at 420 s
        assertEquals(FitnessTestPhase.Incremental, rig.progress.phase)
        assertFalse(rig.progress.canExtendPhase)
        assertTrue(rig.runner.extendPhase() is FitnessActionResult.NotAllowed)
        rig.runner.stop()
        assertEquals(420, (rig.result.measurements as MaxTestMeasurements).warmUpSeconds)
    }

    @Test
    fun `exhausted during the warm-up ends the test early`() = runTest {
        val rig = started()
        delay(60_000)
        assertEquals(FitnessActionResult.Success, rig.runner.exhausted())
        assertEquals(FitnessTestPhase.CoolDown, rig.progress.phase)
        assertEquals(FitnessEndReason.EndedByUser, rig.result.endReason)
        assertEquals(Validity.Invalid, rig.result.validity)
        assertTrue(rig.runner.exhausted() is FitnessActionResult.NotAllowed, "not during the cool-down")
    }

    // ------------------------------------------------------------------ heart rate not needed

    @Test
    fun `without a heart rate the test runs, gives the MAS and no effort judgement`() = runTest {
        val rig = FitnessRig(this, model = null)
        rig.sim.setHeartRate(0)
        rig.connect()
        assertEquals(FitnessActionResult.Success, rig.runner.startMaxTest(MaxTestParameters(subject)))
        rig.awaitPhase(FitnessTestPhase.Incremental)
        delay(9 * 60_000L + 30_000)
        rig.runner.exhausted()
        rig.awaitFinished()
        val r = rig.result
        val o = r.outputs!!
        assertEquals(Validity.Valid, r.validity)
        assertTrue(FitnessTestFlag.NoValidHeartRate in r.flags)
        assertEquals(EffortAssessment.Unknown, o.effort)
        assertNull(o.hrMaxCandidateBpm)
        assertNull(rig.measurements.peakHeartRateBpm)
        assertEquals(0.0, rig.measurements.heartRateCoverage)
        assertNull(r.inputs.heartRateSource)
        assertEquals(8.0 + 0.5 * 8 + 0.25, o.masKmh!!, 0.02)
    }

    @Test
    fun `a heart rate that does not rise with the speed is not used`() = runTest {
        val rig = started(model = PhysiologicalHeartRateModel(restingBpm = 60, maxBpm = 185, vo2maxMlKgMin = 50.0, strapOffPlausibleSeconds = 100_000))
        rig.physiology.takeOffStrap()
        rig.awaitPhase(FitnessTestPhase.Incremental)
        delay(8 * 60_000L)
        rig.runner.exhausted()
        rig.awaitFinished()
        val r = rig.result
        assertEquals(Validity.Valid, r.validity)
        assertTrue(FitnessTestFlag.HeartRateUnreliable in r.flags, "${r.flags}")
        assertEquals(EffortAssessment.Unknown, r.outputs!!.effort)
        assertNull(r.outputs!!.hrMaxCandidateBpm)
    }

    // ------------------------------------------------------------------ user actions and interruptions

    @Test
    fun `STOP before 3 min of the incremental phase stops the belt fully - invalid, no recovery walk`() = runTest {
        val rig = started()
        rig.awaitPhase(FitnessTestPhase.Incremental)
        delay(150_000)
        val speedsBefore = rig.speedCommands
        assertEquals(FitnessActionResult.Success, rig.runner.stop())
        assertEquals(BeltStatus.Idle, rig.controller.state.value.status)
        assertFalse(rig.sim.isBeltMoving)
        val r = rig.result
        assertEquals(FitnessEndReason.StoppedByUser, r.endReason)
        assertEquals(Validity.Invalid, r.validity)
        assertNull(r.outputs)
        assertEquals(speedsBefore, rig.speedCommands, "no recovery walk command")
        assertEquals(0, r.coolDownSeconds)
        assertEquals(1, rig.count(STOP_PAUSE))
        assertTrue(rig.measurements.stages.isNotEmpty(), "the stages run are kept for reading")
    }

    @Test
    fun `STOP during the warm-up is invalid`() = runTest {
        val rig = started()
        delay(100_000)
        assertEquals(FitnessActionResult.Success, rig.runner.stop())
        assertEquals(FitnessEndReason.StoppedByUser, rig.result.endReason)
        assertEquals(Validity.Invalid, rig.result.validity)
        assertFalse(FitnessTestFlag.EndedWithStop in rig.result.flags)
    }

    @Test
    fun `STOP after 3 min of the incremental phase counts as exhaustion - valid, no recovery walk`() = runTest {
        val rig = started()
        rig.awaitPhase(FitnessTestPhase.Incremental)
        delay(240_000)
        val speedsBefore = rig.speedCommands
        assertEquals(FitnessActionResult.Success, rig.runner.stop())
        assertEquals(BeltStatus.Idle, rig.controller.state.value.status)
        assertFalse(rig.sim.isBeltMoving)
        val r = rig.result
        val o = r.outputs!!
        assertEquals(Validity.Valid, r.validity, "${r.flags}")
        assertEquals(FitnessEndReason.Exhausted, r.endReason)
        assertTrue(FitnessTestFlag.EndedWithStop in r.flags)
        assertTrue(FitnessTestFlag.CoolDownIncomplete in r.flags, "the belt stopped: no cool-down")
        assertFalse(FitnessTestFlag.StoppedByUser in r.flags)
        assertFalse(FitnessTestFlag.Interrupted in r.flags)
        assertTrue(r.endDetail!!.contains("STOP") && r.endDetail!!.contains("no cool-down"), r.endDetail)
        assertEquals(0, r.coolDownSeconds)
        assertEquals(speedsBefore, rig.speedCommands, "no recovery walk command")
        assertEquals(1, rig.count(STOP_PAUSE))
        // Same computation as the exhausted button: 4 completed stages (8 ... 9.5) and no time into the next.
        assertEquals(240.0, rig.measurements.incrementalSeconds.toDouble(), 1.0)
        assertEquals(FitnessFormulas.mas(8.0, 0.5, 60, rig.measurements.incrementalSeconds.toDouble())!!, o.masKmh!!, 0.03)
        assertEquals(FitnessFormulas.round1(3.5 * o.masKmh!!), o.vo2maxMlKgMin)
        assertEquals(EffortAssessment.NotMaximal, o.effort, "4 min is under the 5 min of a maximal effort")
        assertEquals(4, rig.measurements.stages.count { it.completed })
        assertEquals(r, FitnessTestJson.decode(FitnessTestJson.encode(r)))
        assertEquals(FitnessTestPhase.Finished, rig.progress.phase)
    }

    @Test
    fun `the belt stopped from the console after 3 min counts as exhaustion too`() = runTest {
        val rig = started()
        rig.awaitPhase(FitnessTestPhase.Incremental)
        delay(250_000)
        rig.sim.physicalStop()
        rig.awaitFinished(10_000)
        val r = rig.result
        assertEquals(Validity.Valid, r.validity, "${r.flags}")
        assertEquals(FitnessEndReason.Exhausted, r.endReason)
        assertTrue(FitnessTestFlag.EndedWithStop in r.flags)
        assertFalse(FitnessTestFlag.Interrupted in r.flags)
        assertTrue(r.endDetail!!.contains("console"), r.endDetail)
        assertEquals(0, r.coolDownSeconds)
        assertEquals(9.5 + 10 / 60.0 * 0.5, r.outputs!!.masKmh!!, 0.15)
        assertEquals(1, rig.count(START))
        assertEquals(0, rig.count(STOP_PAUSE), "the runner did not send a stop: the console did it")
    }

    @Test
    fun `STOP at exhaustion with a heart-rate plateau is a maximal effort`() = runTest {
        val rig = started(plateauModel(cap = 130))
        rig.awaitPhase(FitnessTestPhase.Incremental)
        delay(11 * 60_000L + 20_000)
        assertEquals(FitnessActionResult.Success, rig.runner.stop())
        val r = rig.result
        assertEquals(Validity.Valid, r.validity, "${r.flags}")
        assertTrue(FitnessTestFlag.EndedWithStop in r.flags)
        assertTrue(FitnessTestFlag.MaximalByHeartRatePlateau in r.flags)
        assertEquals(EffortAssessment.Maximal, r.outputs!!.effort)
        assertEquals(130, r.outputs!!.hrMaxCandidateBpm)
        assertTrue(rig.measurements.heartRatePlateau)
    }

    @Test
    fun `the console STOP before 3 min of the incremental phase is an invalid interruption`() = runTest {
        val rig = started()
        rig.awaitPhase(FitnessTestPhase.Incremental)
        delay(60_000)
        rig.sim.physicalStop()
        rig.awaitFinished(10_000)
        assertEquals(FitnessEndReason.BeltStoppedExternally, rig.result.endReason)
        assertTrue(FitnessTestFlag.Interrupted in rig.result.flags)
        assertEquals(Validity.Invalid, rig.result.validity)
    }

    @Test
    fun `safety key during the incremental phase - invalid, never resumed`() = runTest {
        val rig = started()
        rig.awaitPhase(FitnessTestPhase.Incremental)
        delay(200_000)
        rig.sim.removeSafetyKey()
        rig.awaitFinished(5_000)
        assertEquals(FitnessEndReason.SafetyKeyRemoved, rig.result.endReason)
        assertEquals(Validity.Invalid, rig.result.validity)
        val commands = rig.opcodes.size
        rig.sim.restoreSafetyKey()
        delay(60_000)
        assertEquals(commands, rig.opcodes.size)
        assertTrue(rig.runner.exhausted() is FitnessActionResult.NotAllowed)
    }

    @Test
    fun `connection lost - invalid, not restarted after reconnecting`() = runTest {
        val rig = started()
        rig.awaitPhase(FitnessTestPhase.Incremental)
        delay(120_000)
        rig.sim.dropConnection()
        rig.awaitFinished(5_000)
        assertEquals(FitnessEndReason.ConnectionLost, rig.result.endReason)
        assertEquals(Validity.Invalid, rig.result.validity)
        rig.awaitTrue(60_000, "reconnection") { rig.controller.connection.value is ConnectionState.Connected }
        delay(30_000)
        assertEquals(1, rig.count(START), "never restarted")
        assertFalse(rig.sim.isBeltMoving)
        assertEquals(FitnessTestPhase.Finished, rig.progress.phase)
    }

    @Test
    fun `manual changes - respected in the warm-up, breaking in the incremental phase`() = runTest {
        val rig = started()
        delay(30_000)
        rig.sim.physicalSetSpeed(6.0)
        rig.awaitTrue(message = "manual change seen") { rig.eventsOf<FitnessTestEvent.ManualChange>().isNotEmpty() }
        assertFalse(rig.eventsOf<FitnessTestEvent.ManualChange>().single().brokeTest)
        delay(30_000)
        assertEquals(6.0, rig.sim.currentSpeedKmh, 0.01, "kept until the next warm-up step")
        assertEquals(FitnessTestPhase.WarmUp, rig.progress.phase)
        delay(90_000) // t = 150 s: jog step
        assertEquals(6.5, rig.sim.currentSpeedKmh, 0.01)

        rig.awaitPhase(FitnessTestPhase.Incremental)
        delay(90_000)
        rig.sim.physicalSetSpeed(7.0)
        rig.awaitPhase(FitnessTestPhase.CoolDown)
        assertEquals(FitnessEndReason.ManualChange, rig.result.endReason)
        assertEquals(Validity.Invalid, rig.result.validity)
        assertTrue(rig.eventsOf<FitnessTestEvent.ManualChange>().last().brokeTest)
        rig.awaitFinished()
        assertEquals(BeltStatus.Idle, rig.controller.state.value.status)
    }

    @Test
    fun `start speed is clamped to 6-10 km per hour`() = runTest {
        val rig = started(startSpeed = 12.0)
        delay(200_000)
        assertEquals(8.5, rig.sim.currentSpeedKmh, 0.01, "warm-up jog = 10 - 1.5")
        rig.runner.stop()
        assertEquals(10.0, rig.result.inputs.startSpeedKmh)
        assertTrue(FitnessTestFlag.StartSpeedClamped in rig.result.flags)
    }
}
