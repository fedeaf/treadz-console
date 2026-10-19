package com.treadz.console.programs.engine

import com.treadz.console.programs.block
import com.treadz.console.programs.engine.ProgramRig.Companion.SET_INCLINE
import com.treadz.console.programs.engine.ProgramRig.Companion.SET_SPEED
import com.treadz.console.programs.model.Block
import com.treadz.console.programs.model.BlockEnd
import com.treadz.console.programs.model.BlockPhase
import com.treadz.console.programs.model.BlockTarget
import com.treadz.console.programs.model.HeartRateAdjust
import com.treadz.console.programs.model.HeartRateBasis
import com.treadz.console.programs.model.HeartRateZone
import com.treadz.console.programs.model.Repeat
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/**
 * Blocks that keep a speed or an incline start with the value in force when they begin: manual changes from the app and
 * from the console, the last heart-rate adjustment, the combination with the recovery rule, and no command for a value
 * that does not change.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ProgramRunnerKeepTest {

    private fun kept(seconds: Int, speed: Double? = null, incline: Double? = null, phase: BlockPhase = BlockPhase.Work): Block =
        block(phase, BlockEnd.Duration(seconds), BlockTarget.Absolute(speed, incline))

    private fun hrBlock(seconds: Int, adjust: HeartRateAdjust, min: Double, max: Double, start: BlockTarget.Absolute, phase: BlockPhase = BlockPhase.Work): Block =
        block(phase, BlockEnd.Duration(seconds), BlockTarget.HeartRate(HeartRateZone(HeartRateBasis.MaxHeartRate, 70, 80), adjust, min, max, start))

    private fun ProgramRig.startedEvent(blockIndex: Int) = eventsOf<ProgramEvent.BlockStarted>().single { it.blockIndex == blockIndex }

    // ------------------------------------------------------------------ manual changes

    @Test
    fun `a block that keeps starts with what the app set, and sends nothing`() = runTest {
        val rig = ProgramRig(this)
        rig.connect()
        rig.start(planOf(timed(60, 8.0, 2.0), kept(40)))
        delay(30_000)
        assertEquals(RunnerResult.Success, rig.runner.adjustSpeed(9.0))
        assertEquals(RunnerResult.Success, rig.runner.adjustIncline(4.0))
        delay(20_000)
        assertEquals(9.0, rig.sim.currentSpeedKmh, 0.001)
        assertEquals(4.0, rig.sim.currentInclinePercent, 0.001)
        val speeds = rig.count(SET_SPEED)
        val inclines = rig.count(SET_INCLINE)

        rig.awaitTrue(message = "kept block") { rig.progress.currentBlockIndex == 1 }
        delay(15_000)
        assertEquals(9.0, rig.progress.targetSpeedKmh)
        assertEquals(4.0, rig.progress.targetInclinePercent)
        assertFalse(rig.progress.manualOverride, "a new block starts without a manual override")
        assertEquals(speeds, rig.count(SET_SPEED))
        assertEquals(inclines, rig.count(SET_INCLINE))
        assertTrue(rig.eventsOf<ProgramEvent.TargetsApplied>().none { it.blockIndex == 1 })
        assertEquals(9.0, rig.sim.currentSpeedKmh, 0.001)
        assertEquals(4.0, rig.sim.currentInclinePercent, 0.001)

        val started = rig.startedEvent(1)
        assertEquals(9.0, started.targetSpeedKmh)
        assertEquals(4.0, started.targetInclinePercent)
        assertTrue(started.block.keepsSpeed && started.block.keepsIncline)
        assertEquals(8.0, started.block.speedKmh, "the plan only expected the previous planned value")
        rig.runner.stop()
    }

    @Test
    fun `a block that keeps starts with what was set on the console`() = runTest {
        val rig = ProgramRig(this)
        rig.connect()
        rig.start(planOf(timed(80, 8.0, 2.0), kept(40)))
        delay(30_000)
        rig.sim.physicalSetSpeed(7.0)
        rig.sim.physicalSetIncline(3.0)
        rig.awaitTrue(message = "console change") { rig.progress.manualOverride && rig.sim.currentSpeedKmh == 7.0 && rig.sim.currentInclinePercent == 3.0 }
        val speeds = rig.count(SET_SPEED)
        val inclines = rig.count(SET_INCLINE)

        rig.awaitTrue(message = "kept block") { rig.progress.currentBlockIndex == 1 }
        delay(15_000)
        assertEquals(7.0, rig.progress.targetSpeedKmh)
        assertEquals(3.0, rig.progress.targetInclinePercent)
        assertEquals(7.0, rig.sim.currentSpeedKmh, 0.001)
        assertEquals(3.0, rig.sim.currentInclinePercent, 0.001)
        assertEquals(speeds, rig.count(SET_SPEED), "the console's value is not sent again")
        assertEquals(inclines, rig.count(SET_INCLINE))
        assertEquals(7.0, rig.startedEvent(1).targetSpeedKmh)
        assertEquals(3.0, rig.startedEvent(1).targetInclinePercent)
        rig.runner.stop()
    }

    @Test
    fun `a block that keeps only one quantity applies the other one`() = runTest {
        val rig = ProgramRig(this)
        rig.connect()
        rig.start(planOf(timed(60, 8.0, 2.0), kept(40, incline = 0.0)))
        delay(30_000)
        assertEquals(RunnerResult.Success, rig.runner.adjustSpeed(9.5))
        delay(10_000)
        val speeds = rig.count(SET_SPEED)
        val inclines = rig.count(SET_INCLINE)

        rig.awaitTrue(message = "kept block") { rig.progress.currentBlockIndex == 1 }
        rig.awaitTrue(message = "flat") { rig.sim.currentInclinePercent == 0.0 }
        assertEquals(9.5, rig.progress.targetSpeedKmh)
        assertEquals(0.0, rig.progress.targetInclinePercent)
        assertEquals(speeds, rig.count(SET_SPEED), "the kept speed is not sent")
        assertEquals(inclines + 1, rig.count(SET_INCLINE), "the incline of the block is")
        rig.awaitTrue(message = "targets applied") { rig.eventsOf<ProgramEvent.TargetsApplied>().any { it.blockIndex == 1 } }
        val applied = rig.eventsOf<ProgramEvent.TargetsApplied>().single { it.blockIndex == 1 }
        assertEquals(null, applied.speedKmh)
        assertEquals(0.0, applied.inclinePercent)
        assertEquals(9.5, rig.sim.currentSpeedKmh, 0.001)
        rig.runner.stop()
    }

    @Test
    fun `a block that keeps and nothing was changed sends no command`() = runTest {
        val rig = ProgramRig(this)
        rig.connect()
        rig.start(planOf(timed(40, 8.0, 2.0), kept(40)))
        delay(30_000)
        val speeds = rig.count(SET_SPEED)
        val inclines = rig.count(SET_INCLINE)
        rig.awaitTrue(message = "kept block") { rig.progress.currentBlockIndex == 1 }
        delay(20_000)
        assertEquals(speeds, rig.count(SET_SPEED))
        assertEquals(inclines, rig.count(SET_INCLINE))
        assertEquals(8.0, rig.progress.targetSpeedKmh)
        assertEquals(2.0, rig.progress.targetInclinePercent)
        rig.runner.stop()
    }

    @Test
    fun `kept values inside a repeat follow what is in force at each repetition`() = runTest {
        val rig = ProgramRig(this)
        rig.connect()
        // 0 warm-up, then (1 work, 2 kept) twice: 3 work, 4 kept
        rig.start(planOf(timed(30, 6.0, 1.0, BlockPhase.WarmUp), Repeat(2, listOf(timed(40, 8.0, 2.0), kept(30)))))
        rig.awaitTrue(message = "first work") { rig.progress.currentBlockIndex == 1 }
        delay(25_000)
        assertEquals(RunnerResult.Success, rig.runner.adjustSpeed(9.0))
        rig.awaitTrue(message = "first kept") { rig.progress.currentBlockIndex == 2 }
        assertEquals(9.0, rig.progress.targetSpeedKmh)
        assertEquals(2.0, rig.progress.targetInclinePercent)

        rig.awaitTrue(message = "second work") { rig.progress.currentBlockIndex == 3 }
        rig.awaitTrue(message = "work plan again") { rig.sim.currentSpeedKmh == 8.0 }
        rig.awaitTrue(message = "second kept") { rig.progress.currentBlockIndex == 4 }
        assertEquals(8.0, rig.progress.targetSpeedKmh, "the second repetition keeps the second work block's own speed")
        assertEquals(8.0, rig.startedEvent(4).targetSpeedKmh)
        assertEquals(9.0, rig.startedEvent(2).targetSpeedKmh)
        rig.runner.stop()
    }

    // ------------------------------------------------------------------ recovery rule

    @Test
    fun `a recovery that keeps carries on, and its planned incline never raises the load`() = runTest {
        val rig = ProgramRig(this)
        rig.connect()
        // The recovery keeps the speed and plans 5 %, above the 3 % in force
        rig.start(planOf(timed(60, 8.0, 3.0), kept(40, incline = 5.0, phase = BlockPhase.Recovery)))
        delay(30_000)
        assertEquals(RunnerResult.Success, rig.runner.adjustSpeed(6.0))
        delay(10_000)
        val speeds = rig.count(SET_SPEED)
        val inclines = rig.count(SET_INCLINE)

        rig.awaitTrue(message = "recovery") { rig.progress.currentBlockIndex == 1 }
        delay(15_000)
        assertEquals(6.0, rig.progress.targetSpeedKmh, "the speed in force, not the 8 km/h of the plan")
        assertEquals(3.0, rig.progress.targetInclinePercent, "lower of the planned 5 % and the 3 % in force")
        assertEquals(speeds, rig.count(SET_SPEED))
        assertEquals(inclines, rig.count(SET_INCLINE))
        rig.runner.stop()
    }

    // ------------------------------------------------------------------ heart-rate blocks

    @Test
    fun `a cool-down that keeps carries on from the last value of the heart-rate control`() = runTest {
        val rig = HeartRateRig(this)
        rig.connect()
        rig.scripted = { 160 } // above the ceiling: the control keeps lowering the speed
        rig.start(
            planOf(
                timed(60, 6.0, 1.0, BlockPhase.WarmUp),
                hrBlock(240, HeartRateAdjust.Speed, 8.0, 12.0, BlockTarget.Absolute(11.0, 2.0)),
                kept(60, phase = BlockPhase.CoolDown),
            ),
        )
        rig.awaitBlock(2)
        val last = rig.eventsOf<ProgramEvent.HeartRateAdjusted>().filter { it.blockIndex == 1 }.last().toValue
        assertTrue(last < 11.0, "the control lowered the speed: $last")
        assertEquals(last, rig.progress.targetSpeedKmh, "not the 11 km/h the heart-rate block started with")
        assertEquals(2.0, rig.progress.targetInclinePercent)
        assertEquals(last, rig.eventsOf<ProgramEvent.BlockStarted>().single { it.blockIndex == 2 }.targetSpeedKmh)
        rig.awaitTrue(message = "belt at the control's value") { rig.sim.currentSpeedKmh == last }
        assertEquals(last, rig.speedCommands.last())
        val commands = rig.speedCommands.size
        delay(40_000)
        assertEquals(commands, rig.speedCommands.size)
        assertEquals(last, rig.sim.currentSpeedKmh, 0.001)
        assertEquals(0, rig.stopCommands)
        rig.runner.stop()
    }

    @Test
    fun `a heart-rate block that keeps its start begins at the value in force`() = runTest {
        val rig = HeartRateRig(this)
        rig.connect()
        rig.scripted = { 135 } // inside the zone (126-144 bpm): the control holds
        rig.start(planOf(timed(60, 8.0, 1.0, BlockPhase.WarmUp), hrBlock(300, HeartRateAdjust.Speed, 7.0, 10.0, BlockTarget.Absolute(null, 2.0))))
        delay(30_000)
        assertEquals(RunnerResult.Success, rig.runner.adjustSpeed(8.6))
        rig.awaitBlock(1)
        assertEquals(8.6, rig.progress.targetSpeedKmh, "the speed in force, not the planned 8.0")
        assertEquals(2.0, rig.progress.targetInclinePercent)
        rig.awaitTrue(message = "belt") { rig.sim.currentSpeedKmh == 8.6 && rig.sim.currentInclinePercent == 2.0 }
        assertEquals(8.6, rig.eventsOf<ProgramEvent.BlockStarted>().single { it.blockIndex == 1 }.targetSpeedKmh)
        assertEquals(HeartRateControlMode.Continuous, rig.progress.heartRateControl?.mode)
        rig.runner.stop()
    }

    @Test
    fun `a heart-rate block that keeps its start is brought within its bounds`() = runTest {
        val rig = HeartRateRig(this)
        rig.connect()
        rig.scripted = { 135 }
        rig.start(planOf(timed(60, 8.0, 1.0, BlockPhase.WarmUp), hrBlock(300, HeartRateAdjust.Speed, 7.0, 10.0, BlockTarget.Absolute(null, null))))
        delay(30_000)
        assertEquals(RunnerResult.Success, rig.runner.adjustSpeed(13.0))
        rig.awaitBlock(1)
        assertEquals(10.0, rig.progress.targetSpeedKmh, "the 13 km/h in force, within the 7-10 bounds")
        assertEquals(1.0, rig.progress.targetInclinePercent, "the incline is kept as it is")
        rig.awaitTrue(message = "belt") { rig.sim.currentSpeedKmh == 10.0 }
        rig.runner.stop()
    }

    @Test
    fun `a heart-rate block that adjusts the incline keeps its start incline in the same way`() = runTest {
        val rig = HeartRateRig(this)
        rig.connect()
        rig.scripted = { 135 }
        rig.start(planOf(timed(60, 6.0, 2.0, BlockPhase.WarmUp), hrBlock(300, HeartRateAdjust.Incline, 0.0, 6.0, BlockTarget.Absolute(7.0, null))))
        delay(30_000)
        assertEquals(RunnerResult.Success, rig.runner.adjustIncline(4.0))
        rig.awaitBlock(1)
        assertEquals(4.0, rig.progress.targetInclinePercent)
        assertEquals(7.0, rig.progress.targetSpeedKmh)
        rig.runner.stop()
    }
}
