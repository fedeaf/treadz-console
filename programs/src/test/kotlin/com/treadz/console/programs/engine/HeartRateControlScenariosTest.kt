package com.treadz.console.programs.engine

import com.treadz.console.programs.block
import com.treadz.console.programs.fitness.HeartRateSourceKind
import com.treadz.console.programs.model.Block
import com.treadz.console.programs.model.BlockEnd
import com.treadz.console.programs.model.BlockPhase
import com.treadz.console.programs.model.BlockTarget
import com.treadz.console.programs.model.HeartRateAdjust
import com.treadz.console.programs.model.HeartRateBasis
import com.treadz.console.programs.model.HeartRateZone
import com.treadz.console.programs.model.Repeat
import com.treadz.console.treadmill.BeltStatus
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Heart-rate-guided blocks on the simulator with its physiological heart-rate model (rest 60, max 180, VO2max 45), with
 * the heart rate coming from the console and from a strap connected to the app. Profile: maximum 180, resting 60.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HeartRateControlScenariosTest {

    private fun hrBlock(
        seconds: Int, low: Int, high: Int, adjust: HeartRateAdjust, min: Double, max: Double, speed: Double, incline: Double,
        basis: HeartRateBasis = HeartRateBasis.MaxHeartRate,
    ): Block = block(
        BlockPhase.Work, BlockEnd.Duration(seconds),
        BlockTarget.HeartRate(HeartRateZone(basis, low, high), adjust, min, max, BlockTarget.Absolute(speed, incline)),
    )

    /** 3 min walking warm-up, 20 min at 70-80 % (126-144 bpm) adjusting the speed between 7.5 and 12 from 10 km/h. */
    private fun zone3Run() = planOf(
        timed(180, 6.0, 1.0, BlockPhase.WarmUp),
        hrBlock(1200, 70, 80, HeartRateAdjust.Speed, 7.5, 12.0, 10.0, 1.0),
        timed(120, 5.0, 0.0, BlockPhase.CoolDown),
    )

    private fun HeartRateRig.blockLog(blockStartMs: Long, blockEndMs: Long) = log.filter { it.first in blockStartMs..blockEndMs }

    private fun continuousSpeedScenario(withStrap: Boolean) = runTest {
        val rig = HeartRateRig(this)
        rig.connect(withStrap)
        assertEquals(RunnerResult.Success, rig.start(zone3Run()))
        rig.awaitBlock(1)
        val blockStart = rig.clock.nowMillis() - T0
        delay(30_000)
        val status = checkNotNull(rig.progress.heartRateControl)
        assertEquals(126, status.zoneLowBpm)
        assertEquals(144, status.zoneHighBpm)
        assertEquals(152, status.ceilingBpm)
        assertEquals(HeartRateControlMode.Continuous, status.mode)
        val expectedSource = if (withStrap) HeartRateSourceKind.DirectStrap else HeartRateSourceKind.TreadmillConsole
        assertEquals(expectedSource, rig.progress.heartRateSource)
        assertEquals(expectedSource, status.source)

        rig.awaitBlock(2)
        val blockEnd = rig.clock.nowMillis() - T0
        assertEquals(0, rig.stopCommands, "the belt is never stopped by the control")
        val summary = rig.progress.heartRateSummaries.single()
        assertEquals(1, summary.blockIndex)
        assertEquals(expectedSource, summary.source)
        assertTrue(summary.adjustments >= 2, "$summary")
        val settled = checkNotNull(summary.settledValue) { "$summary" }
        assertTrue(settled in 7.6..9.0, "$summary")
        val end = checkNotNull(summary.endHeartRateBpm)
        assertTrue(end in 124.0..146.0, "$summary")
        assertTrue(summary.secondsInZone >= 600, "$summary")
        // Within the block the belt ran between the bounds (after the ramp down from the start speed).
        val inBlock = rig.blockLog(blockStart + 30_000, blockEnd)
        assertTrue(inBlock.all { it.second in 7.5..12.0 }, "${inBlock.map { it.second }.distinct()}")
        assertTrue(rig.eventsOf<ProgramEvent.HeartRateAdjusted>().any { it.reason == HeartRateAdjustReason.AboveZone })
        if (withStrap) assertTrue(rig.source.latest().rrIntervalsMs.isNotEmpty(), "the strap provides RR intervals")
        rig.awaitFinished()
        assertEquals(listOf(summary), rig.eventsOf<ProgramEvent.Finished>().single().heartRateSummaries)
    }

    @Test
    fun `continuous speed control brings the heart rate into the zone - console heart rate`() = continuousSpeedScenario(withStrap = false)

    @Test
    fun `continuous speed control brings the heart rate into the zone - strap connected to the app`() = continuousSpeedScenario(withStrap = true)

    @Test
    fun `continuous incline control while walking keeps the speed`() = runTest {
        val rig = HeartRateRig(this)
        rig.connect()
        val plan = planOf(
            timed(120, 5.0, 0.0, BlockPhase.WarmUp),
            hrBlock(900, 60, 70, HeartRateAdjust.Incline, 0.0, 15.0, 5.0, 2.0),
            timed(60, 4.0, 0.0, BlockPhase.CoolDown),
        )
        rig.start(plan)
        rig.awaitBlock(1)
        val blockStart = rig.clock.nowMillis() - T0
        rig.awaitBlock(2)
        val summary = rig.progress.heartRateSummaries.single()
        assertEquals(HeartRateAdjust.Incline, summary.adjust)
        assertTrue(summary.endValue in 6.0..10.0, "$summary")
        assertTrue(checkNotNull(summary.endHeartRateBpm) in 106.0..128.0, "$summary")
        assertNotNull(summary.settledValue, "$summary")
        val speeds = rig.blockLog(blockStart + 15_000, rig.clock.nowMillis() - T0 - 2_000).map { it.second }.distinct()
        assertEquals(listOf(5.0), speeds, "only the incline is adjusted")
        val ups = rig.eventsOf<ProgramEvent.HeartRateAdjusted>().filter { it.reason == HeartRateAdjustReason.BelowZone }
        assertTrue(ups.isNotEmpty())
        assertTrue(ups.first().programSeconds - 120 >= 90, "no upward correction in the first 90 s: ${ups.first()}")
        assertTrue(ups.all { it.toValue - it.fromValue in 1.0..2.0 }, "$ups")
    }

    @Test
    fun `between repetitions - 4x4 corrected from one repetition to the next`() = runTest {
        val rig = HeartRateRig(this)
        rig.connect()
        val plan = planOf(
            timed(300, 6.0, 1.0, BlockPhase.WarmUp),
            Repeat(
                4,
                listOf(
                    hrBlock(240, 85, 95, HeartRateAdjust.Speed, 7.5, 14.0, 8.5, 1.0),
                    timed(180, 6.0, 1.0, BlockPhase.Recovery),
                ),
            ),
            timed(120, 5.0, 0.0, BlockPhase.CoolDown),
        )
        rig.start(plan)
        rig.awaitFinished()
        val summaries = rig.progress.heartRateSummaries
        assertEquals(listOf(1, 3, 5, 7), summaries.map { it.blockIndex })
        assertTrue(summaries.all { it.mode == HeartRateControlMode.BetweenRepetitions })
        // Each repetition started where the heart rate at the end of the previous one said, and stayed there.
        val starts = summaries.map { it.initialValue }
        assertEquals(8.5, starts[0])
        for (i in 1 until starts.size) assertTrue(starts[i] > starts[i - 1], "$starts")
        assertTrue(summaries.all { it.initialValue == it.endValue && it.adjustments == 0 }, "only safety changes inside a repetition: $summaries")
        val next = rig.eventsOf<ProgramEvent.HeartRateAdjusted>().filter { it.reason == HeartRateAdjustReason.NextRepetition }
        assertEquals(3, next.size)
        assertTrue(next.none { it.reason == HeartRateAdjustReason.AboveZone || it.reason == HeartRateAdjustReason.BelowZone })
    }

    @Test
    fun `ceiling - sharp reductions and an alert, the belt never stops`() = runTest {
        val rig = HeartRateRig(this)
        rig.connect()
        rig.scripted = { 160 } // the heart rate does not follow the load: above the ceiling (152) all the time
        rig.start(planOf(hrBlock(300, 70, 80, HeartRateAdjust.Speed, 8.0, 12.0, 11.0, 1.0)))
        rig.awaitTrue(message = "at the minimum") { rig.progress.targetSpeedKmh == 8.0 }
        val alerts = rig.eventsOf<ProgramEvent.HeartRateCeilingExceeded>()
        assertTrue(alerts.isNotEmpty())
        assertTrue(alerts.first().reduced)
        assertEquals(152, alerts.first().ceilingBpm)
        val sharp = rig.eventsOf<ProgramEvent.HeartRateAdjusted>().filter { it.reason == HeartRateAdjustReason.CeilingExceeded }
        assertEquals(11.0, sharp.first().fromValue)
        assertEquals(10.0, sharp.first().toValue)
        assertEquals(HeartRateControlState.Ceiling, rig.progress.heartRateControl?.state)
        delay(60_000)
        assertEquals(BeltStatus.Running, rig.controller.state.value.status)
        assertEquals(0, rig.stopCommands)
        assertEquals(8.0, rig.sim.currentSpeedKmh)
        rig.runner.stop()
    }

    @Test
    fun `maximum heart rate reached - recovery value`() = runTest {
        val rig = HeartRateRig(this)
        rig.connect()
        rig.scripted = { s -> if (s < 30) 140 else (140 + (s - 30) * 2).coerceAtMost(185).toInt() }
        rig.start(planOf(hrBlock(400, 80, 90, HeartRateAdjust.Speed, 8.0, 12.0, 11.0, 1.0)))
        rig.awaitTrue(message = "maximum reached") { rig.eventsOf<ProgramEvent.HeartRateMaxReached>().isNotEmpty() }
        val max = rig.eventsOf<ProgramEvent.HeartRateMaxReached>().first()
        assertEquals(180, max.maxBpm)
        rig.awaitTrue(message = "recovery value") { rig.progress.targetSpeedKmh == 8.0 }
        rig.runner.stop()
    }

    @Test
    fun `strap taken off with the console's ghost values - no increase, hold, resume when it is back`() = runTest {
        val rig = HeartRateRig(this)
        rig.connect()
        rig.start(zone3Run())
        rig.awaitBlock(1)
        delay(420_000) // the heart rate has settled in the zone
        val speedBefore = checkNotNull(rig.progress.targetSpeedKmh)
        val adjustmentsBefore = rig.eventsOf<ProgramEvent.HeartRateAdjusted>().size
        rig.physiology.takeOffStrap()
        delay(15_000)
        assertEquals(HeartRateControlState.HoldingNoHeartRate, rig.progress.heartRateControl?.state)
        delay(150_000) // ghost values 73-80 for 90 s, then 0
        val after = rig.eventsOf<ProgramEvent.HeartRateAdjusted>().drop(adjustmentsBefore)
        assertTrue(after.none { it.toValue > it.fromValue }, "never increased on a ghost heart rate: $after")
        assertTrue(checkNotNull(rig.progress.targetSpeedKmh) <= speedBefore)
        assertEquals(HeartRateControlState.HoldingNoHeartRate, rig.progress.heartRateControl?.state)
        assertEquals(BeltStatus.Running, rig.controller.state.value.status)
        rig.physiology.putOnStrap()
        delay(20_000)
        assertFalse(rig.progress.heartRateControl?.state == HeartRateControlState.HoldingNoHeartRate, "${rig.progress.heartRateControl}")
        rig.runner.stop()
    }

    @Test
    fun `strap connected to the app taken off - no heart rate, hold`() = runTest {
        val rig = HeartRateRig(this)
        rig.connect(withStrap = true)
        rig.start(zone3Run())
        rig.awaitBlock(1)
        delay(300_000)
        val speedBefore = checkNotNull(rig.progress.targetSpeedKmh)
        rig.strap.takeOff()
        delay(10_000)
        assertEquals(HeartRateControlState.HoldingNoHeartRate, rig.progress.heartRateControl?.state)
        assertEquals(HeartRateSourceKind.DirectStrap, rig.progress.heartRateSource, "the strap stays the source (no fallback to the console)")
        delay(50_000)
        assertTrue(checkNotNull(rig.progress.targetSpeedKmh) <= speedBefore)
        rig.strap.putOn()
        delay(15_000)
        assertFalse(rig.progress.heartRateControl?.state == HeartRateControlState.HoldingNoHeartRate)
        rig.runner.stop()
    }

    @Test
    fun `manual change switches the control off until the next block, and it can be switched back on`() = runTest {
        val rig = HeartRateRig(this)
        rig.connect()
        rig.start(zone3Run())
        rig.awaitBlock(1)
        delay(240_000)
        rig.sim.physicalSetSpeed(11.0)
        rig.awaitTrue(message = "control off") { rig.progress.heartRateControl?.state == HeartRateControlState.OffByManualChange }
        val n = rig.eventsOf<ProgramEvent.HeartRateAdjusted>().size
        delay(120_000)
        assertEquals(n, rig.eventsOf<ProgramEvent.HeartRateAdjusted>().size, "no automatic change while off")
        assertEquals(11.0, rig.sim.currentSpeedKmh)
        assertTrue(rig.progress.manualOverride)

        assertEquals(RunnerResult.Success, rig.runner.resumeHeartRateControl())
        assertFalse(rig.progress.manualOverride)
        assertTrue(rig.runner.resumeHeartRateControl() is RunnerResult.NotAllowed, "already on")
        rig.awaitTrue(message = "control acts again") { rig.eventsOf<ProgramEvent.HeartRateAdjusted>().size > n }
        assertTrue(checkNotNull(rig.progress.targetSpeedKmh) < 11.0)

        // The app's own controls switch it off too.
        assertEquals(RunnerResult.Success, rig.runner.adjustSpeed(9.0))
        assertEquals(HeartRateControlState.OffByManualChange, rig.progress.heartRateControl?.state)
        rig.awaitBlock(2)
        assertNull(rig.progress.heartRateControl, "the cool-down is not heart-rate guided")
        assertTrue(rig.progress.heartRateSummaries.single().manualOverride)
        rig.runner.stop()
    }

    @Test
    fun `without a heart-rate profile the block runs at its start values`() = runTest {
        val rig = HeartRateRig(this, profile = null)
        rig.connect()
        rig.start(planOf(hrBlock(300, 70, 80, HeartRateAdjust.Speed, 7.5, 12.0, 10.0, 1.0)))
        delay(200_000)
        assertEquals(HeartRateControlState.Unavailable, rig.progress.heartRateControl?.state)
        assertNull(rig.progress.heartRateControl?.zoneLowBpm)
        assertEquals(10.0, rig.sim.currentSpeedKmh)
        assertTrue(rig.eventsOf<ProgramEvent.HeartRateAdjusted>().isEmpty())
        assertTrue(rig.runner.resumeHeartRateControl() is RunnerResult.NotAllowed)
        rig.runner.stop()
        assertTrue(rig.eventsOf<ProgramEvent.Finished>().single().heartRateSummaries.isEmpty())
    }

    @Test
    fun `reserve zones use the resting heart rate`() = runTest {
        val rig = HeartRateRig(this)
        rig.connect()
        rig.start(planOf(hrBlock(300, 60, 70, HeartRateAdjust.Speed, 7.5, 12.0, 9.0, 1.0, basis = HeartRateBasis.HeartRateReserve)))
        delay(5_000)
        val status = checkNotNull(rig.progress.heartRateControl)
        assertEquals(132, status.zoneLowBpm) // 60 + 0.6 x 120
        assertEquals(144, status.zoneHighBpm)
        rig.runner.stop()
    }

    @Test
    fun `pause and resume keep the heart-rate target`() = runTest {
        val rig = HeartRateRig(this)
        rig.connect()
        rig.start(zone3Run())
        rig.awaitBlock(1)
        delay(300_000)
        val target = checkNotNull(rig.progress.targetSpeedKmh)
        assertEquals(RunnerResult.Success, rig.runner.pause())
        delay(30_000)
        assertEquals(RunnerResult.Success, rig.runner.resume())
        delay(20_000)
        assertTrue(kotlin.math.abs(rig.sim.currentSpeedKmh - target) <= 0.55, "speed ${rig.sim.currentSpeedKmh} vs $target")
        rig.runner.stop()
    }
}
