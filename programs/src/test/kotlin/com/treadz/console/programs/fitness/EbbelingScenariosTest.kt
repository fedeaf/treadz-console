package com.treadz.console.programs.fitness

import com.treadz.console.programs.fitness.FitnessRig.Companion.START
import com.treadz.console.programs.fitness.FitnessRig.Companion.STOP_PAUSE
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
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import kotlin.math.min
import kotlin.math.roundToInt

@OptIn(ExperimentalCoroutinesApi::class)
class EbbelingScenariosTest {

    private suspend fun TestScope.started(
        model: SimulatedHeartRateModel? = PhysiologicalHeartRateModel(restingBpm = 78),
        subject: FitnessSubject = SUBJECT,
        startSpeed: Double = 4.8,
    ): FitnessRig {
        val rig = FitnessRig(this, model)
        rig.connect()
        assertEquals(FitnessActionResult.Success, rig.runner.startEbbeling(EbbelingParameters(subject, startSpeed)))
        return rig
    }

    private val FitnessRig.measurements: EbbelingMeasurements get() = result.measurements as EbbelingMeasurements

    /** Test time (ms of virtual time) at which the test clock started. */
    private val FitnessRig.testStart: Long get() = checkNotNull(eventsOf<FitnessTestEvent.Started>().single().atMillis) - FT0

    /** Simulator speeds sampled between two test times (seconds). */
    private fun FitnessRig.speedsBetween(fromS: Int, toS: Int) =
        speedLog.filter { (t, _) -> t - testStart in fromS * 1000L..toS * 1000L }.map { it.second }

    // ------------------------------------------------------------------ happy path

    @Test
    fun `happy path - 4 min flat, 4 min at 5 percent, steady state, cool-down and stop`() = runTest {
        val rig = started()
        assertEquals(FitnessTestPhase.Stage1, rig.progress.phase)
        assertEquals(90, rig.progress.heartRateBandLowBpm)
        assertEquals(126, rig.progress.heartRateBandHighBpm)
        assertEquals(153, rig.progress.heartRateLimitBpm)

        delay(30_000)
        rig.progress.let {
            assertEquals(FitnessInstruction.WalkSpeedMayChange, it.instruction)
            assertEquals(1, it.stageNumber)
            assertEquals(210.0, it.phaseRemainingSeconds!!.toDouble(), 1.0)
            assertFalse(it.speedLocked)
            assertEquals(4.8, it.targetSpeedKmh)
            assertEquals(4.8, it.actualSpeedKmh, 0.01)
            assertNotNull(it.heartRateBpm)
            assertTrue(it.canEndTest)
            assertFalse(it.canReportExhaustion)
        }
        delay(170_000) // t = 200 s
        assertEquals(FitnessInstruction.WalkSpeedFixed, rig.progress.instruction)
        assertTrue(rig.progress.speedLocked)
        delay(100_000) // t = 300 s
        rig.progress.let {
            assertEquals(FitnessTestPhase.Stage2, it.phase)
            assertEquals(FitnessInstruction.WalkUphill, it.instruction)
            assertEquals(2, it.stageNumber)
            assertEquals(SteadyStateStatus.Pending, it.steadyState)
            assertEquals(180.0, it.phaseRemainingSeconds!!.toDouble(), 1.0)
            assertEquals(5.0, it.targetInclinePercent)
            assertNull(it.steadyStateVariationBpm, "not before 2 min of stage 2")
        }
        delay(100_000) // t = 400 s
        assertNotNull(rig.progress.steadyStateVariationBpm)

        rig.awaitPhase(FitnessTestPhase.CoolDown)
        rig.progress.let {
            assertEquals(FitnessInstruction.CoolDownWalk, it.instruction)
            assertEquals(FitnessEndReason.Completed, it.endReason)
            assertEquals(Validity.Valid, it.result?.validity, "the result is known during the cool-down")
            assertFalse(it.canEndTest)
        }
        rig.awaitFinished()

        val r = rig.result
        val m = rig.measurements
        assertEquals(Validity.Valid, r.validity)
        assertEquals(FitnessEndReason.Completed, r.endReason)
        assertEquals(emptyList<FitnessTestFlag>(), r.flags)
        assertEquals(emptyList<SpeedAdjustment>(), m.speedAdjustments)
        assertEquals(4.8, m.testSpeedKmh)
        assertEquals(2.98, m.testSpeedMph)
        assertEquals(240, m.stage1Seconds)
        assertEquals(240, m.stage2Seconds)
        assertEquals(480, r.effortSeconds)
        assertEquals(180, r.coolDownSeconds)
        assertTrue(m.steadyStateVariationBpm!! <= 5.0)
        val steady = m.steadyStateHeartRateBpm!!
        assertTrue(steady in 105.0..125.0, "steady $steady")
        assertTrue(m.heartRateRiseBpm!! >= 10.0, "rise ${m.heartRateRiseBpm}")
        assertTrue(m.stage1EndHeartRateBpm!! in 90.0..126.0)

        val o = r.outputs!!
        assertEquals(FitnessFormulas.round1(FitnessFormulas.ebbelingVo2max(FitnessFormulas.kmhToMph(4.8), steady, 40, Sex.Male)), o.vo2maxMlKgMin)
        assertEquals(4.85, o.vo2maxSeeMlKgMin)
        assertEquals(Vo2MaxMethod.Ebbeling1991, o.method)
        assertEquals(Vo2MaxNorms.classify(o.vo2maxMlKgMin, 40, Sex.Male), o.classification)
        assertEquals(HeartRateSourceKind.TreadmillConsole, r.inputs.heartRateSource)
        assertEquals(180, r.inputs.agePredictedHrMaxBpm)

        // One record per minute of each phase, with the heart rate.
        assertEquals(listOf(4, 4, 3), listOf(FitnessTestPhase.Stage1, FitnessTestPhase.Stage2, FitnessTestPhase.CoolDown).map { p -> r.minutes.count { it.phase == p } })
        assertTrue(r.minutes.filter { it.phase == FitnessTestPhase.Stage2 }.all { it.heartRateBpm != null && it.inclinePercent == 5.0 && it.speedKmh == 4.8 })

        // The treadmill: started once, constant speed in stage 2, cool-down at 4 km/h and 0 %, stopped once.
        assertEquals(1, rig.count(START))
        assertEquals(1, rig.count(STOP_PAUSE))
        assertEquals(listOf(4.8, 4.0), rig.speedCommands)
        assertEquals(listOf(5.0, 0.0), rig.inclineCommands)
        assertTrue(rig.speedsBetween(242, 479).all { it == 4.8 })
        assertTrue(rig.sim.forbiddenCommands.isEmpty())
        assertEquals(BeltStatus.Idle, rig.controller.state.value.status)
        assertFalse(rig.sim.isBeltMoving)

        assertEquals(
            listOf(FitnessTestPhase.Idle, FitnessTestPhase.Starting, FitnessTestPhase.Stage1, FitnessTestPhase.Stage2, FitnessTestPhase.CoolDown, FitnessTestPhase.Stopping, FitnessTestPhase.Finished),
            rig.phases.toList(),
        )
        val preliminary = rig.eventsOf<FitnessTestEvent.EffortEnded>().single().result
        assertEquals(r.outputs, preliminary.outputs)
        assertEquals(r, rig.eventsOf<FitnessTestEvent.Finished>().single().result)
        assertEquals(r, FitnessTestJson.decode(FitnessTestJson.encode(r)))
    }

    // ------------------------------------------------------------------ stage 1 speed

    @Test
    fun `stage 1 raises the speed until the heart rate is in the band, then holds it`() = runTest {
        val rig = started(PhysiologicalHeartRateModel(restingBpm = 60))
        rig.awaitFinished()
        val r = rig.result
        val m = rig.measurements
        assertEquals(Validity.Valid, r.validity, "${r.flags} ${r.endReason}")
        assertTrue(FitnessTestFlag.SpeedAdjustedToHeartRateBand in r.flags)
        assertTrue(m.speedAdjustments.isNotEmpty())
        assertTrue(m.speedAdjustments.all { it.toKmh > it.fromKmh }, "${m.speedAdjustments}")
        assertTrue(m.speedAdjustments.all { it.heartRateBpm!! < 93.0 }, "only below the inner band")
        assertTrue(m.speedAdjustments.zipWithNext().all { (a, b) -> b.atSecond - a.atSecond >= 45 })
        // Mid-stage adjustments stop 90 s before the end of stage 1; later ones come with an extension.
        val midStage = m.speedAdjustments.filter { it.atSecond < 240 }
        assertTrue(midStage.all { it.atSecond <= 150 }, "${m.speedAdjustments}")
        assertEquals(m.speedAdjustments.size - midStage.size, m.stage1Extensions)
        assertEquals(240 + 90 * m.stage1Extensions, m.stage1Seconds)
        assertEquals(m.speedAdjustments.last().toKmh, m.testSpeedKmh)
        assertTrue(m.testSpeedKmh!! > 4.8)
        assertTrue(m.stage1EndHeartRateBpm!! in 90.0..126.0, "${m.stage1EndHeartRateBpm}")
        // The speed did not change in the last 90 s of stage 1 nor in stage 2.
        assertTrue(rig.speedsBetween(m.stage1Seconds - 88, m.stage1Seconds + m.stage2Seconds - 1).all { it == m.testSpeedKmh })
        assertEquals(r.outputs!!.vo2maxMlKgMin, FitnessFormulas.round1(FitnessFormulas.ebbelingVo2max(m.testSpeedMph!!.let { FitnessFormulas.kmhToMph(m.testSpeedKmh!!) }, m.steadyStateHeartRateBpm!!, 40, Sex.Male)))
    }

    @Test
    fun `heart rate above the band even at the slowest walk ends the test as invalid`() = runTest {
        val rig = started(PhysiologicalHeartRateModel(restingBpm = 100, vo2maxMlKgMin = 18.0))
        rig.awaitFinished()
        val r = rig.result
        val m = rig.measurements
        assertEquals(Validity.Invalid, r.validity)
        assertEquals(FitnessEndReason.HeartRateBandUnreachable, r.endReason)
        assertTrue(FitnessTestFlag.HeartRateAboveBand in r.flags)
        assertNull(r.outputs)
        assertTrue(m.speedAdjustments.all { it.toKmh < it.fromKmh })
        assertEquals(3.2, m.speedAdjustments.last().toKmh)
        assertNull(m.testSpeedKmh, "stage 2 never started")
        assertTrue(rig.inclineCommands.isEmpty(), "never at 5 %")
        assertTrue(FitnessTestPhase.CoolDown in rig.phases, "cool-down after the end")
        assertEquals(180, r.coolDownSeconds)
        assertEquals(BeltStatus.Idle, rig.controller.state.value.status)
    }

    @Test
    fun `heart rate below the band at the fastest walk goes on with a warning`() = runTest {
        val rig = started(PhysiologicalHeartRateModel(restingBpm = 55, vo2maxMlKgMin = 65.0))
        rig.awaitFinished()
        val r = rig.result
        val m = rig.measurements
        assertEquals(Validity.Valid, r.validity, "${r.flags}")
        assertTrue(FitnessTestFlag.HeartRateBelowBand in r.flags)
        assertEquals(7.2, m.testSpeedKmh)
        assertTrue(m.stage1EndHeartRateBpm!! < 90.0)
        assertTrue(m.heartRateRiseBpm!! >= 10.0)
        assertNotNull(r.outputs)
        assertEquals(listOf(5.6, 6.4, 7.2), m.speedAdjustments.map { it.toKmh }, "large steps when far below the band")
    }

    @Test
    fun `start speed outside the walking range is clamped and an age outside 20-59 is flagged`() = runTest {
        val rig = started(subject = SUBJECT.copy(ageYears = 62), startSpeed = 9.0)
        rig.awaitFinished()
        val r = rig.result
        assertEquals(7.2, r.inputs.startSpeedKmh)
        assertEquals(9.0, r.inputs.requestedStartSpeedKmh)
        assertTrue(FitnessTestFlag.StartSpeedClamped in r.flags)
        assertTrue(FitnessTestFlag.OutsideValidatedPopulation in r.flags)
        assertEquals(79, (r.measurements as EbbelingMeasurements).heartRateBandLowBpm, "50 % of 220 - 62")
    }

    // ------------------------------------------------------------------ steady state

    @Test
    fun `stage 2 is extended until the heart rate is steady`() = runTest {
        var gradeSince: Long? = null
        // Flat walking: 95-96 bpm. At 5 %: from 110, +3 bpm per minute for 260 s, then flat at 123.
        val model = SimulatedHeartRateModel { input ->
            if (input.inclinePercent >= 4.5) {
                if (gradeSince == null) gradeSince = input.second
            } else {
                gradeSince = null
            }
            val g = gradeSince
            if (g == null) 95 + (input.second % 2).toInt() else (110 + 3.0 * min(input.second - g, 260L) / 60.0).roundToInt()
        }
        val rig = started(model)
        rig.awaitPhase(FitnessTestPhase.Stage2)
        rig.awaitTrue(message = "extension") { rig.progress.stage2Extensions == 1 }
        assertEquals(SteadyStateStatus.Extended, rig.progress.steadyState)
        assertEquals(FitnessInstruction.WalkUphillExtended, rig.progress.instruction)
        rig.awaitFinished()
        val r = rig.result
        val m = rig.measurements
        assertEquals(Validity.Valid, r.validity, "${r.flags}")
        assertTrue(FitnessTestFlag.SteadyStateExtended in r.flags)
        assertEquals(1, m.stage2Extensions)
        assertEquals(300, m.stage2Seconds)
        assertEquals(5, r.minutes.count { it.phase == FitnessTestPhase.Stage2 })
        assertEquals(123.0, m.steadyStateHeartRateBpm!!, 0.5)
        assertEquals(1, rig.eventsOf<FitnessTestEvent.SteadyStateExtended>().single().extension)
    }

    @Test
    fun `no steady state after two extensions makes the test invalid`() = runTest {
        val rig = started(PhysiologicalHeartRateModel(restingBpm = 78, driftBpmPerMinute = 3.0), subject = SUBJECT.copy(hrMaxBpm = 200))
        rig.awaitFinished()
        val r = rig.result
        val m = rig.measurements
        assertEquals(Validity.Invalid, r.validity)
        assertEquals(FitnessEndReason.NoSteadyState, r.endReason)
        assertTrue(FitnessTestFlag.NoSteadyState in r.flags)
        assertEquals(2, m.stage2Extensions)
        assertEquals(360, m.stage2Seconds)
        assertTrue(m.steadyStateVariationBpm!! > 5.0)
        assertNull(m.steadyStateHeartRateBpm)
        assertNull(r.outputs)
        assertEquals(180, r.coolDownSeconds)
    }

    // ------------------------------------------------------------------ safety stops

    @Test
    fun `heart rate above 85 percent of the maximum stops the test and cools down`() = runTest {
        // Maximum 125: limit floor(0.85 * 125) = 106; stage 2 brings the heart rate to ~115.
        val rig = started(subject = SUBJECT.copy(hrMaxBpm = 125))
        assertEquals(106, rig.progress.heartRateLimitBpm)
        rig.awaitPhase(FitnessTestPhase.CoolDown)
        assertEquals(FitnessTestPhase.Stage2, rig.result.minutes.last().phase)
        rig.awaitFinished()
        val r = rig.result
        assertEquals(Validity.Invalid, r.validity)
        assertEquals(FitnessEndReason.HeartRateAboveLimit, r.endReason)
        assertTrue(FitnessTestFlag.HeartRateAboveSafetyLimit in r.flags)
        assertTrue(r.effortSeconds in 241..479, "${r.effortSeconds}")
        assertEquals(listOf(4.8, 4.0), rig.speedCommands, "cool-down walk after the stop of the test")
        assertEquals(listOf(5.0, 0.0), rig.inclineCommands)
        assertEquals(1, rig.count(STOP_PAUSE))
    }

    @Test
    fun `strap taken off - the console's plausible values then zero - ends the test`() = runTest {
        val rig = started()
        rig.awaitPhase(FitnessTestPhase.Stage2)
        delay(60_000)
        rig.physiology.strapOffPlausibleSeconds = 30
        rig.physiology.takeOffStrap()
        rig.awaitPhase(FitnessTestPhase.CoolDown)
        val r = rig.result
        assertEquals(FitnessEndReason.HeartRateLost, r.endReason)
        assertTrue(FitnessTestFlag.HeartRateLost in r.flags)
        assertEquals(Validity.Invalid, r.validity)
        // 240 s of stage 1 + 60 s + 30 s of ghost values + 5 s without heart rate.
        assertEquals(335.0, r.effortSeconds.toDouble(), 3.0)
        rig.awaitFinished()
        assertEquals(BeltStatus.Idle, rig.controller.state.value.status)
    }

    @Test
    fun `strap off before the grade - plausible but unresponsive values - invalidate the test`() = runTest {
        val rig = started()
        delay(150_000)
        rig.physiology.strapOffPlausibleSeconds = 10_000
        rig.physiology.takeOffStrap()
        rig.awaitFinished()
        val r = rig.result
        assertEquals(Validity.Invalid, r.validity)
        assertTrue(
            r.flags.any { it == FitnessTestFlag.HeartRateNotResponsive || it == FitnessTestFlag.NoSteadyState || it == FitnessTestFlag.HeartRateFlat },
            "${r.flags}",
        )
        assertTrue(FitnessTestFlag.HeartRateBelowBand in r.flags, "the ghost values (73-80) are below the band")
        assertNull(r.outputs)
    }

    // ------------------------------------------------------------------ user and manual actions

    @Test
    fun `a manual speed change breaks the protocol - invalid, cool-down`() = runTest {
        val rig = started()
        rig.awaitPhase(FitnessTestPhase.Stage2)
        delay(30_000)
        rig.sim.physicalSetSpeed(6.0)
        rig.awaitPhase(FitnessTestPhase.CoolDown)
        val r = rig.result
        assertEquals(FitnessEndReason.ManualChange, r.endReason)
        assertTrue(FitnessTestFlag.ManualChange in r.flags)
        assertEquals(Validity.Invalid, r.validity)
        assertTrue(rig.eventsOf<FitnessTestEvent.ManualChange>().single().brokeTest)
        rig.awaitFinished()
        assertEquals(4.0, rig.speedCommands.last(), "cool-down walk")
        assertEquals(BeltStatus.Idle, rig.controller.state.value.status)
    }

    @Test
    fun `STOP stops the belt at once - invalid, no cool-down`() = runTest {
        val rig = started()
        delay(100_000)
        assertEquals(FitnessActionResult.Success, rig.runner.stop())
        assertEquals(FitnessTestPhase.Finished, rig.progress.phase)
        val r = rig.result
        assertEquals(FitnessEndReason.StoppedByUser, r.endReason)
        assertTrue(FitnessTestFlag.StoppedByUser in r.flags)
        assertTrue(FitnessTestFlag.CoolDownIncomplete in r.flags)
        assertEquals(0, r.coolDownSeconds)
        assertEquals(100.0, r.effortSeconds.toDouble(), 1.0)
        assertEquals(BeltStatus.Idle, rig.controller.state.value.status)
        assertFalse(rig.sim.isBeltMoving)
        assertEquals(listOf(4.8), rig.speedCommands, "no cool-down command")
        val commands = rig.opcodes.size
        delay(60_000)
        assertEquals(commands, rig.opcodes.size, "nothing is sent after the test")
        assertTrue(rig.runner.stop() is FitnessActionResult.NotAllowed)
    }

    @Test
    fun `end test ends early with a cool-down - invalid`() = runTest {
        val rig = started()
        rig.awaitPhase(FitnessTestPhase.Stage2)
        delay(20_000)
        assertEquals(FitnessActionResult.Success, rig.runner.endTest())
        assertEquals(FitnessTestPhase.CoolDown, rig.progress.phase)
        assertEquals(FitnessEndReason.EndedByUser, rig.result.endReason)
        assertTrue(rig.runner.endTest() is FitnessActionResult.NotAllowed, "only once")
        rig.awaitFinished()
        assertEquals(Validity.Invalid, rig.result.validity)
        assertEquals(180, rig.result.coolDownSeconds)
        assertFalse(FitnessTestFlag.CoolDownIncomplete in rig.result.flags)
    }

    @Test
    fun `STOP during the cool-down keeps the result`() = runTest {
        val rig = started()
        rig.awaitPhase(FitnessTestPhase.CoolDown)
        val before = rig.result
        delay(60_000)
        assertEquals(FitnessActionResult.Success, rig.runner.stop())
        val r = rig.result
        assertEquals(Validity.Valid, r.validity)
        assertEquals(before.outputs, r.outputs)
        assertEquals(FitnessEndReason.Completed, r.endReason)
        assertTrue(FitnessTestFlag.CoolDownIncomplete in r.flags)
        assertEquals(60.0, r.coolDownSeconds.toDouble(), 1.0)
    }

    @Test
    fun `the safety key interrupts the test - invalid, no more commands`() = runTest {
        val rig = started()
        rig.awaitPhase(FitnessTestPhase.Stage2)
        delay(10_000)
        val commands = rig.opcodes.size
        rig.sim.removeSafetyKey()
        rig.awaitFinished(5_000)
        val r = rig.result
        assertEquals(FitnessEndReason.SafetyKeyRemoved, r.endReason)
        assertTrue(FitnessTestFlag.Interrupted in r.flags)
        assertEquals(Validity.Invalid, r.validity)
        rig.sim.restoreSafetyKey()
        delay(30_000)
        assertEquals(commands, rig.opcodes.size)
        assertEquals(1, rig.count(START), "never restarted")
    }

    // ------------------------------------------------------------------ refusals

    @Test
    fun `refuses to start without a heart rate, with limits below the protocol or twice`() = runTest {
        val rig = FitnessRig(this, model = null)
        rig.sim.setHeartRate(0)
        rig.connect()
        assertEquals(FitnessRefusal.NoHeartRate, (rig.runner.startEbbeling(EbbelingParameters(SUBJECT)) as FitnessActionResult.NotAllowed).refusal)
        assertEquals(0, rig.count(START))

        rig.sim.setHeartRate(90)
        delay(2_000)
        rig.controller.setLimits(SafetyLimits(maxInclinePercent = 3.0))
        assertEquals(FitnessRefusal.LimitsTooLow, (rig.runner.startEbbeling(EbbelingParameters(SUBJECT)) as FitnessActionResult.NotAllowed).refusal)
        rig.controller.setLimits(SafetyLimits())
        assertEquals(FitnessRefusal.InvalidSubject, (rig.runner.startEbbeling(EbbelingParameters(SUBJECT.copy(ageYears = 5))) as FitnessActionResult.NotAllowed).refusal)
        assertEquals(0, rig.count(START))

        assertEquals(FitnessActionResult.Success, rig.runner.startEbbeling(EbbelingParameters(SUBJECT)))
        assertEquals(FitnessRefusal.AlreadyActive, (rig.runner.startMaxTest(MaxTestParameters(SUBJECT)) as FitnessActionResult.NotAllowed).refusal)
        assertEquals(FitnessRefusal.WrongState, (rig.runner.exhausted() as FitnessActionResult.NotAllowed).refusal)
        rig.runner.stop()
    }

    // ------------------------------------------------------------------ the user changes the stage 1 speed

    private val strapOnLow get() = PhysiologicalHeartRateModel(restingBpm = 60)

    @Test
    fun `console speed change in stage 1 becomes the base speed and stops the automatic corrections`() = runTest {
        // At 4.8 km/h this heart rate (~83) is below the band, so without the user the speed would be raised at 60 s.
        val rig = started(strapOnLow)
        delay(30_000)
        rig.sim.physicalSetSpeed(7.0)
        rig.awaitTrue(message = "manual change seen") { rig.eventsOf<FitnessTestEvent.ManualChange>().isNotEmpty() }
        assertFalse(rig.eventsOf<FitnessTestEvent.ManualChange>().single().brokeTest)
        assertEquals(FitnessTestPhase.Stage1, rig.progress.phase)
        assertEquals(7.0, rig.progress.targetSpeedKmh)
        rig.awaitFinished()
        val r = rig.result
        val m = rig.measurements
        assertEquals(Validity.Valid, r.validity, "${r.flags} ${r.endReason}")
        assertEquals(1, m.speedAdjustments.size, "no automatic correction fought the user: ${m.speedAdjustments}")
        val a = m.speedAdjustments.single()
        assertEquals(SpeedAdjustmentOrigin.Console, a.origin)
        assertEquals(4.8, a.fromKmh)
        assertEquals(7.0, a.toKmh)
        assertTrue(a.atSecond in 25..45, "${a.atSecond}")
        assertTrue(FitnessTestFlag.SpeedAdjustedByUser in r.flags)
        assertFalse(FitnessTestFlag.SpeedAdjustedToHeartRateBand in r.flags)
        assertEquals(7.0, m.testSpeedKmh)
        assertEquals(240, m.stage1Seconds)
        assertEquals(1, rig.eventsOf<FitnessTestEvent.SpeedAdjusted>().size)
        assertEquals(SpeedAdjustmentOrigin.Console, rig.eventsOf<FitnessTestEvent.SpeedAdjusted>().single().adjustment.origin)
        // Constant from the console change to the end of stage 2, and the protocol never sent a speed of its own in between.
        assertTrue(rig.speedsBetween(a.atSecond + 5, m.stage1Seconds + m.stage2Seconds - 1).all { it == 7.0 })
        assertEquals(listOf(4.8, 4.0), rig.speedCommands, "start speed and the cool-down walk only")
        assertEquals(FitnessFormulas.round1(FitnessFormulas.ebbelingVo2max(FitnessFormulas.kmhToMph(7.0), m.steadyStateHeartRateBpm!!, 40, Sex.Male)), r.outputs!!.vo2maxMlKgMin)
        assertEquals(r, FitnessTestJson.decode(FitnessTestJson.encode(r)))
    }

    @Test
    fun `app speed changes in stage 1 are clamped, applied and recorded, and refused after the freeze`() = runTest {
        val rig = started(strapOnLow)
        delay(20_000)
        assertEquals(FitnessActionResult.Success, rig.runner.adjustSpeed(6.0))
        assertEquals(6.0, rig.progress.targetSpeedKmh)
        delay(20_000)
        assertEquals(6.0, rig.sim.currentSpeedKmh, 0.01, "the run loop applied it")
        assertEquals(FitnessActionResult.Success, rig.runner.adjustSpeed(9.0))
        assertEquals(7.2, rig.progress.targetSpeedKmh, "clamped to the walking range")
        delay(20_000)
        assertEquals(7.2, rig.sim.currentSpeedKmh, 0.01)
        delay(75_000) // t = 135 s: the freeze starts at 150 s
        assertEquals(FitnessActionResult.Success, rig.runner.adjustSpeed(7.0))
        assertEquals(7.0, rig.progress.targetSpeedKmh)
        delay(30_000) // t = 165 s
        assertTrue(rig.progress.speedLocked)
        assertEquals(FitnessInstruction.WalkSpeedFixed, rig.progress.instruction)
        val refused = rig.runner.adjustSpeed(5.0)
        assertEquals(FitnessActionResult.NotAllowed(FitnessRefusal.WrongState, "The speed cannot be changed now"), refused)
        assertEquals(7.0, rig.progress.targetSpeedKmh)
        rig.awaitFinished()
        val r = rig.result
        val m = rig.measurements
        assertEquals(Validity.Valid, r.validity, "${r.flags} ${r.endReason}")
        assertEquals(
            listOf(SpeedAdjustmentOrigin.App, SpeedAdjustmentOrigin.App, SpeedAdjustmentOrigin.App),
            m.speedAdjustments.map { it.origin },
        )
        assertEquals(listOf(4.8, 6.0, 7.2), m.speedAdjustments.map { it.fromKmh })
        assertEquals(listOf(6.0, 7.2, 7.0), m.speedAdjustments.map { it.toKmh })
        assertEquals(7.0, m.testSpeedKmh)
        assertTrue(FitnessTestFlag.SpeedAdjustedByUser in r.flags)
        assertEquals(r, FitnessTestJson.decode(FitnessTestJson.encode(r)))
        assertTrue(rig.sim.forbiddenCommands.isEmpty())
    }

    @Test
    fun `a user speed outside the band at the end of stage 1 still gets the end-of-stage correction`() = runTest {
        val rig = started(strapOnLow)
        delay(20_000)
        assertEquals(FitnessActionResult.Success, rig.runner.adjustSpeed(3.6)) // too slow: heart rate ~78, below the band of 90
        rig.awaitFinished()
        val m = rig.measurements
        assertEquals(SpeedAdjustmentOrigin.App, m.speedAdjustments.first().origin)
        assertTrue(m.speedAdjustments.drop(1).all { it.origin == SpeedAdjustmentOrigin.Automatic && it.atSecond >= 240 }, "${m.speedAdjustments}")
        assertTrue(m.stage1Extensions >= 1, "the band check at the end of stage 1 extended it")
        assertTrue(m.testSpeedKmh!! > 3.6)
    }

    @Test
    fun `console speed changes after the freeze, outside the walking range, or of the incline still invalidate`() = runTest {
        val frozen = started()
        delay(180_000)
        frozen.sim.physicalSetSpeed(5.6)
        frozen.awaitPhase(FitnessTestPhase.CoolDown)
        assertEquals(FitnessEndReason.ManualChange, frozen.result.endReason)
        assertEquals(Validity.Invalid, frozen.result.validity)

        val tooFast = started()
        delay(30_000)
        tooFast.sim.physicalSetSpeed(8.0)
        tooFast.awaitPhase(FitnessTestPhase.CoolDown)
        assertEquals(FitnessEndReason.ManualChange, tooFast.result.endReason)
        assertTrue(tooFast.eventsOf<FitnessTestEvent.ManualChange>().single().brokeTest)

        val incline = started()
        delay(30_000)
        incline.sim.physicalSetIncline(3.0)
        incline.awaitPhase(FitnessTestPhase.CoolDown)
        assertEquals(FitnessEndReason.ManualChange, incline.result.endReason)
        assertEquals(Validity.Invalid, incline.result.validity)
    }

    @Test
    fun `the app cannot change the speed in stage 2, in the maximal test or without a test`() = runTest {
        val rig = started()
        assertTrue(rig.runner.adjustSpeed(Double.NaN) is FitnessActionResult.NotAllowed)
        rig.awaitPhase(FitnessTestPhase.Stage2)
        assertEquals(FitnessRefusal.WrongState, (rig.runner.adjustSpeed(5.0) as FitnessActionResult.NotAllowed).refusal)
        rig.awaitFinished()
        assertEquals(FitnessRefusal.NotActive, (rig.runner.adjustSpeed(5.0) as FitnessActionResult.NotAllowed).refusal)

        val max = FitnessRig(this)
        max.connect()
        assertEquals(FitnessActionResult.Success, max.runner.startMaxTest(MaxTestParameters(SUBJECT)))
        assertEquals(FitnessRefusal.WrongState, (max.runner.adjustSpeed(5.0) as FitnessActionResult.NotAllowed).refusal)
    }

    @Test
    fun `documents written before the speed origin existed are still read`() = runTest {
        val rig = started(PhysiologicalHeartRateModel(restingBpm = 60))
        rig.awaitFinished()
        val r = rig.result
        assertTrue(rig.measurements.speedAdjustments.isNotEmpty())
        val text = FitnessTestJson.encode(r)
        assertTrue(text.contains("\"origin\": \"automatic\""))
        val old = text.replace(Regex(",\\s*\"origin\": \"automatic\""), "")
        assertFalse(old.contains("origin"))
        assertEquals(r, FitnessTestJson.decode(old))
    }
}
