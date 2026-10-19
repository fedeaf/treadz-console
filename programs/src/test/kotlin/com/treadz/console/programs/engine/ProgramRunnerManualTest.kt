package com.treadz.console.programs.engine

import com.treadz.console.programs.engine.ProgramRig.Companion.SET_SPEED
import com.treadz.console.programs.model.BlockPhase
import com.treadz.console.treadmill.CommandResult
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

/** Manual changes during a program: console ramps at a block change, and adjustments from the app's own controls. */
@OptIn(ExperimentalCoroutinesApi::class)
class ProgramRunnerManualTest {

    // ------------------------------------------------------------------ C: a console ramp in progress at a block change

    @Test
    fun `a console ramp in progress when the block changes does not stop the new block from applying its speed`() = runTest {
        val rig = ProgramRig(this)
        rig.connect()
        // Both blocks ask for 6 km/h: the treadmill's old target (6) equals the new block's speed
        rig.start(planOf(timed(30, 6.0), timed(60, 6.0)))
        delay(26_000)
        assertEquals(6.0, rig.sim.currentSpeedKmh, 0.001)
        val sent = rig.count(SET_SPEED)

        rig.sim.physicalSetSpeed(3.0)
        delay(5_000) // block 2 starts at 30 s, with the belt around 4 km/h and still slowing down
        assertEquals(1, rig.progress.currentBlockIndex)
        assertTrue(rig.sim.currentSpeedKmh in 3.1..5.5, "mid ramp: ${rig.sim.currentSpeedKmh}")

        rig.awaitTrue(message = "block 2 speed") { rig.sim.currentSpeedKmh == 6.0 }
        assertEquals(sent + 1, rig.count(SET_SPEED), "the block's speed was sent once")
        val applied = rig.eventsOf<ProgramEvent.TargetsApplied>().last()
        assertEquals(1, applied.blockIndex)
        assertEquals(6.0, applied.speedKmh)
        assertFalse(rig.progress.manualOverride)
        assertEquals(6.0, rig.controller.state.value.targetSpeedKmh)
        delay(10_000)
        assertEquals(6.0, rig.sim.currentSpeedKmh, 0.001)
        rig.runner.stop()
    }

    @Test
    fun `a console ramp that has settled before the block change is replaced by the block's own speed`() = runTest {
        val rig = ProgramRig(this)
        rig.connect()
        rig.start(planOf(timed(60, 6.0), timed(60, 6.0)))
        delay(30_000)
        rig.sim.physicalSetSpeed(8.0)
        delay(20_000)
        assertTrue(rig.progress.manualOverride)
        assertEquals(8.0, rig.sim.currentSpeedKmh, 0.001)
        rig.awaitTrue(message = "back to the block's speed") { rig.progress.currentBlockIndex == 1 && rig.sim.currentSpeedKmh == 6.0 }
        assertFalse(rig.progress.manualOverride)
        rig.runner.stop()
    }

    // ------------------------------------------------------------------ E: recovery and cool-down never raise the load

    @Test
    fun `a cool-down keeps a speed lowered during the effort instead of raising it to its plan`() = runTest {
        val rig = ProgramRig(this)
        rig.connect()
        // The zone 2 walk of a real session: the effort and the cool-down both plan 5.6 km/h.
        rig.start(planOf(timed(60, 5.6, 12.0), timed(60, 5.6, 0.0, BlockPhase.CoolDown)))
        delay(30_000)
        assertEquals(RunnerResult.Success, rig.runner.adjustSpeed(5.5))
        rig.awaitTrue(message = "adjusted") { rig.sim.currentSpeedKmh == 5.5 }
        val sent = rig.count(SET_SPEED)

        rig.awaitTrue(message = "cool-down") { rig.progress.currentBlockIndex == 1 }
        rig.awaitTrue(message = "flat") { rig.sim.currentInclinePercent == 0.0 }
        assertEquals(5.5, rig.progress.targetSpeedKmh)
        assertEquals(0.0, rig.progress.targetInclinePercent)
        delay(10_000)
        assertEquals(5.5, rig.sim.currentSpeedKmh, 0.001)
        assertEquals(sent, rig.count(SET_SPEED), "no speed command: the speed in force is kept")
        assertFalse(rig.progress.manualOverride)
        rig.runner.stop()
    }

    @Test
    fun `a recovery never goes above the load in force, and the next effort applies its plan again`() = runTest {
        val rig = ProgramRig(this)
        rig.connect()
        rig.start(planOf(timed(60, 8.0, 3.0), timed(40, 6.0, 4.0, BlockPhase.Recovery), timed(60, 8.0, 3.0)))
        delay(20_000)
        // Lowered from the console below the recovery's own plan, speed and incline.
        rig.sim.physicalSetSpeed(5.0)
        rig.sim.physicalSetIncline(1.0)
        rig.awaitTrue(message = "console change") { rig.progress.manualOverride && rig.sim.currentSpeedKmh == 5.0 && rig.sim.currentInclinePercent == 1.0 }

        rig.awaitTrue(message = "recovery") { rig.progress.currentBlockIndex == 1 }
        assertEquals(5.0, rig.progress.targetSpeedKmh)
        assertEquals(1.0, rig.progress.targetInclinePercent)
        delay(20_000)
        assertEquals(5.0, rig.sim.currentSpeedKmh, 0.001)
        assertEquals(1.0, rig.sim.currentInclinePercent, 0.001)

        rig.awaitTrue(message = "next effort") { rig.progress.currentBlockIndex == 2 }
        rig.awaitTrue(message = "effort plan") { rig.sim.currentSpeedKmh == 8.0 && rig.sim.currentInclinePercent == 3.0 }
        rig.runner.stop()
    }

    @Test
    fun `a cool-down planned lower than the load in force applies its plan`() = runTest {
        val rig = ProgramRig(this)
        rig.connect()
        rig.start(planOf(timed(40, 8.0, 2.0), timed(60, 5.0, 0.0, BlockPhase.CoolDown)))
        rig.awaitTrue(message = "cool-down") { rig.progress.currentBlockIndex == 1 }
        rig.awaitTrue(message = "cool-down plan") { rig.sim.currentSpeedKmh == 5.0 && rig.sim.currentInclinePercent == 0.0 }
        assertEquals(5.0, rig.progress.targetSpeedKmh)
        rig.runner.stop()
    }

    // ------------------------------------------------------------------ D: adjustments from the app

    @Test
    fun `a speed adjustment from the app is kept until the next block, also across a pause`() = runTest {
        val rig = ProgramRig(this)
        rig.connect()
        rig.start(planOf(timed(120, 8.0, 2.0), timed(40, 6.0, 0.0)))
        delay(30_000)

        assertEquals(RunnerResult.Success, rig.runner.adjustSpeed(9.5))
        assertTrue(rig.progress.manualOverride)
        assertEquals(9.5, rig.progress.targetSpeedKmh)
        assertEquals(2.0, rig.progress.targetInclinePercent)
        val event = rig.eventsOf<ProgramEvent.ManualChange>().single()
        assertEquals(ManualChangeSource.App, event.source)
        assertEquals(9.5, event.speedKmh)
        assertNull(event.inclinePercent)
        assertEquals(0, event.blockIndex)

        assertEquals(RunnerResult.Success, rig.runner.adjustIncline(5.0))
        assertEquals(5.0, rig.progress.targetInclinePercent)
        delay(20_000)
        assertEquals(9.5, rig.sim.currentSpeedKmh, 0.001)
        assertEquals(5.0, rig.sim.currentInclinePercent, 0.001)
        assertEquals(2, rig.eventsOf<ProgramEvent.ManualChange>().size, "the controller's own detection does not report the app's commands")

        // Pause and resume: the adjustment comes back, not the block's values
        rig.runner.pause()
        rig.runner.resume()
        rig.awaitTrue(message = "adjusted values back") { rig.sim.currentSpeedKmh == 9.5 && rig.sim.currentInclinePercent == 5.0 }
        assertTrue(rig.progress.manualOverride)
        assertEquals(RunState.Running, rig.progress.state)

        // The next block applies its own values again
        rig.awaitTrue(message = "block 2") { rig.progress.currentBlockIndex == 1 }
        rig.awaitTrue(message = "own values") { rig.sim.currentSpeedKmh == 6.0 && rig.sim.currentInclinePercent == 0.0 }
        assertFalse(rig.progress.manualOverride)
        rig.runner.stop()
    }

    @Test
    fun `repeated adjustments replace each other and the last one wins`() = runTest {
        val rig = ProgramRig(this)
        rig.connect()
        rig.start(planOf(timed(120, 8.0)))
        delay(20_000)
        listOf(8.1, 8.2, 8.3, 8.4).forEach { assertEquals(RunnerResult.Success, rig.runner.adjustSpeed(it)) }
        assertEquals(8.4, rig.progress.targetSpeedKmh)
        delay(10_000)
        assertEquals(8.4, rig.sim.currentSpeedKmh, 0.001)
        assertEquals(RunState.Running, rig.progress.state)
        rig.runner.stop()
    }

    @Test
    fun `adjustments are clamped like any command`() = runTest {
        val rig = ProgramRig(this)
        rig.connect()
        rig.start(planOf(timed(120, 8.0, 2.0)))
        delay(20_000)
        assertEquals(RunnerResult.Success, rig.runner.adjustSpeed(50.0))
        assertEquals(20.0, rig.progress.targetSpeedKmh)
        assertEquals(RunnerResult.Success, rig.runner.adjustIncline(-3.0))
        assertEquals(0.0, rig.progress.targetInclinePercent)
        rig.runner.stop()
    }

    @Test
    fun `adjusting is refused unless the program is running`() = runTest {
        val rig = ProgramRig(this)
        rig.connect()
        assertTrue(rig.runner.adjustSpeed(7.0) is RunnerResult.NotAllowed)
        rig.start(planOf(timed(120, 8.0)))
        delay(10_000)
        assertTrue(rig.runner.adjustSpeed(Double.NaN) is RunnerResult.NotAllowed)
        rig.runner.pause()
        val paused = rig.runner.adjustIncline(4.0)
        assertTrue(paused is RunnerResult.NotAllowed && paused.refusal == Refusal.WrongState, "$paused")
        assertFalse(rig.progress.manualOverride)
        rig.runner.stop()
    }

    @Test
    fun `an adjustment the treadmill does not carry out is undone and interrupts the program`() = runTest {
        val rig = ProgramRig(this)
        rig.connect()
        rig.start(planOf(timed(120, 8.0)))
        delay(25_000)
        rig.sim.speedFault = true
        val result = rig.runner.adjustSpeed(10.0)
        assertTrue(result is RunnerResult.CommandFailed && result.result is CommandResult.NotVerified, "$result")
        assertEquals(RunState.Interrupted, rig.progress.state)
        assertFalse(rig.progress.manualOverride)
        assertEquals(8.0, rig.progress.targetSpeedKmh)
        assertTrue(rig.eventsOf<ProgramEvent.ManualChange>().isEmpty())
        rig.runner.abandon()
    }
}
