package com.treadz.console.programs.engine

import com.treadz.console.programs.engine.ProgramRig.Companion.SET_INCLINE
import com.treadz.console.programs.engine.ProgramRig.Companion.SET_SPEED
import com.treadz.console.programs.engine.ProgramRig.Companion.START
import com.treadz.console.programs.engine.ProgramRig.Companion.STOP_PAUSE
import com.treadz.console.programs.heartrate.HeartRateValidity
import com.treadz.console.programs.model.BlockPhase
import com.treadz.console.programs.model.Repeat
import com.treadz.console.treadmill.BeltStatus
import com.treadz.console.treadmill.CommandResult
import com.treadz.console.treadmill.ConnectionState
import com.treadz.console.treadmill.TreadmillEvent
import com.treadz.console.treadmill.simulator.SimulatedTreadmill
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertNotNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test

@OptIn(ExperimentalCoroutinesApi::class)
class ProgramRunnerTest {

    // ------------------------------------------------------------------ a whole run

    @Test
    fun `a full run with repeats follows the plan on time and stops the belt at the end`() = runTest {
        val rig = ProgramRig(this)
        val plan = planOf(
            timed(20, 5.0, phase = BlockPhase.WarmUp),
            Repeat(2, listOf(timed(30, 8.0, 3.0), timed(20, 5.0, 0.0, BlockPhase.Recovery))),
            timed(20, 4.0, phase = BlockPhase.CoolDown),
        )
        rig.connect()
        assertEquals(RunnerResult.Success, rig.start(plan))
        val startedAt = rig.now
        assertEquals(RunState.Running, rig.progress.state)
        assertEquals(0, rig.progress.currentBlockIndex)

        // Block 1 (30 s at 8 km/h and 3 %) starts at 20 s; by 40 s the ramp is over.
        delay(40_000)
        assertEquals(1, rig.progress.currentBlockIndex)
        assertEquals(8.0, rig.sim.currentSpeedKmh, 0.001)
        assertEquals(3.0, rig.sim.currentInclinePercent, 0.001)

        rig.awaitState(RunState.Finished)
        assertEquals(RunOutcome.Completed, rig.progress.outcome)
        assertEquals(BeltStatus.Idle, rig.controller.state.value.status)
        assertFalse(rig.sim.isBeltMoving)
        assertEquals(1, rig.count(START), "the belt is started once")
        assertEquals(1, rig.count(STOP_PAUSE), "and stopped once, at the end")
        assertTrue(rig.sim.forbiddenCommands.isEmpty(), "the treadmill's time/distance targets are never used")

        val started = rig.eventsOf<ProgramEvent.BlockStarted>()
        assertEquals((0..5).toList(), started.map { it.blockIndex })
        assertEquals(BlockChangeReason.ProgramStart, started[0].reason)
        assertTrue(started.drop(1).all { it.reason == BlockChangeReason.Completed })
        // Program clock: block boundaries at 0, 20, 50, 70, 100 and 120 s (the program lasts 140 s).
        val offsets = started.map { (it.atMillis - started[0].atMillis) }
        listOf(0L, 20_000L, 50_000L, 70_000L, 100_000L, 120_000L).zip(offsets).forEach { (expected, actual) ->
            assertEquals(expected.toDouble(), actual.toDouble(), 300.0)
        }
        val finished = rig.eventsOf<ProgramEvent.Finished>().single()
        assertEquals(140.0, finished.programSeconds.toDouble(), 1.0)
        assertEquals(EndAction.StopBelt, finished.endAction)
        assertEquals(CommandResult.Success, finished.stopResult)
        assertEquals(ProgramEvent.Started::class, rig.events.first()::class)
        assertEquals(ProgramEvent.Finished::class, rig.events.last()::class)
        assertEquals(6, rig.eventsOf<ProgramEvent.BlockCompleted>().size)
        assertTrue(rig.now - startedAt in 140_000..170_000)
        assertEquals(listOf(RunState.Idle, RunState.Starting, RunState.Running, RunState.Stopping, RunState.Finished), rig.states.toList())
    }

    @Test
    fun `progress shows the current and next block, remaining time and targets against actual values`() = runTest {
        val rig = ProgramRig(this)
        val plan = planOf(timed(60, 6.0, 2.0), timed(30, 8.0))
        rig.connect()
        rig.start(plan)
        delay(10_000)
        val p = rig.progress
        assertEquals(RunState.Running, p.state)
        assertEquals(0, p.currentBlockIndex)
        assertEquals(1, p.nextBlock?.index)
        assertEquals(10.0, p.blockElapsedSeconds.toDouble(), 1.0)
        assertEquals(50.0, p.blockRemainingSeconds.toDouble(), 1.0)
        assertEquals(10.0, p.totalElapsedSeconds.toDouble(), 1.0)
        assertEquals(80.0, p.totalRemainingSeconds.toDouble(), 1.0)
        assertEquals(6.0, p.targetSpeedKmh)
        assertEquals(2.0, p.targetInclinePercent)
        assertFalse(p.manualOverride)
        assertTrue(p.actualSpeedKmh in 4.0..6.0, "still ramping towards 6 km/h: ${p.actualSpeedKmh}")
        assertEquals(2.0, p.actualInclinePercent, 0.001)
        assertNull(p.blockRemainingMeters)
        assertEquals(plan, p.plan)
        rig.runner.stop()
    }

    // ------------------------------------------------------------------ distance blocks

    @Test
    fun `a block that ends by distance uses the treadmill distance`() = runTest {
        val rig = ProgramRig(this)
        val plan = planOf(distance(100, 12.0), timed(20, 6.0))
        rig.connect()
        rig.start(plan)
        delay(15_000)
        val mid = rig.progress
        assertNotNull(mid.blockRemainingMeters)
        assertTrue(mid.blockRemainingMeters!! in 1..99)
        assertTrue(mid.blockElapsedMeters in 1..99)

        rig.awaitTrue(message = "second block") { rig.progress.currentBlockIndex == 1 }
        val done = rig.eventsOf<ProgramEvent.BlockCompleted>().first()
        assertEquals(0, done.blockIndex)
        // The counter has a 10 m resolution: the block ends on the first reading at or above 100 m.
        assertTrue(done.meters in 100..120, "ended at ${done.meters} m")
        assertTrue(done.elapsedSeconds in 25..60, "took ${done.elapsedSeconds} s")
        rig.awaitState(RunState.Finished)
        assertEquals(RunOutcome.Completed, rig.progress.outcome)
    }

    // ------------------------------------------------------------------ pause and resume

    @Test
    fun `pause stops the program clock and resume restores the incline and the speed of the block`() = runTest {
        val rig = ProgramRig(this)
        rig.connect()
        rig.start(planOf(timed(60, 8.0, 5.0), timed(30, 6.0, 2.0)))
        delay(25_000)
        assertEquals(5.0, rig.sim.currentInclinePercent, 0.001)

        assertEquals(RunnerResult.Success, rig.runner.pause())
        assertEquals(RunState.Paused, rig.progress.state)
        assertEquals(0.0, rig.sim.currentSpeedKmh, 0.001)
        assertEquals(0.0, rig.sim.currentInclinePercent, 0.001, "pausing lowers the incline to 0")
        val elapsed = rig.progress.totalElapsedSeconds
        val blockElapsed = rig.progress.blockElapsedSeconds
        assertTrue(elapsed in 24..40, "elapsed $elapsed")

        delay(30_000)
        assertEquals(elapsed, rig.progress.totalElapsedSeconds, "the clock is stopped while paused")
        assertEquals(blockElapsed, rig.progress.blockElapsedSeconds)

        assertEquals(RunnerResult.Success, rig.runner.resume())
        assertEquals(RunState.Running, rig.progress.state)
        delay(25_000)
        assertEquals(5.0, rig.sim.currentInclinePercent, 0.001, "the incline is back")
        assertEquals(8.0, rig.sim.currentSpeedKmh, 0.001, "and the speed")
        assertEquals(blockElapsed + 25.0, rig.progress.blockElapsedSeconds.toDouble(), 2.0)
        assertEquals(1, rig.eventsOf<ProgramEvent.Paused>().size)
        assertEquals(1, rig.eventsOf<ProgramEvent.Resumed>().size)

        rig.awaitState(RunState.Finished)
        assertEquals(RunOutcome.Completed, rig.progress.outcome)
        // The 90 s of program time took longer than 90 s of wall time: the pause is not part of it.
        assertEquals(90.0, rig.eventsOf<ProgramEvent.Finished>().single().programSeconds.toDouble(), 2.0)
    }

    @Test
    fun `pause and resume are refused in the wrong state`() = runTest {
        val rig = ProgramRig(this)
        rig.connect()
        assertTrue(rig.runner.pause() is RunnerResult.NotAllowed)
        assertTrue(rig.runner.resume() is RunnerResult.NotAllowed)
        rig.start(planOf(timed(60, 6.0)))
        assertTrue(rig.runner.resume() is RunnerResult.NotAllowed)
        rig.runner.pause()
        assertTrue(rig.runner.pause() is RunnerResult.NotAllowed)
        rig.runner.stop()
    }

    // ------------------------------------------------------------------ manual changes

    @Test
    fun `a manual change is respected until the next block applies its own values`() = runTest {
        val rig = ProgramRig(this)
        rig.connect()
        rig.start(planOf(timed(120, 8.0, 2.0), timed(40, 6.0, 0.0)))
        delay(30_000)
        assertEquals(8.0, rig.sim.currentSpeedKmh, 0.001)
        val speedCommands = rig.count(SET_SPEED)
        val inclineCommands = rig.count(SET_INCLINE)

        rig.sim.physicalSetSpeed(10.0)
        rig.sim.physicalSetIncline(5.0)
        delay(20_000)

        val manual = rig.eventsOf<ProgramEvent.ManualChange>()
        assertTrue(manual.isNotEmpty())
        assertTrue(manual.all { it.blockIndex == 0 })
        assertTrue(manual.any { it.speedKmh == 10.0 } && manual.any { it.inclinePercent == 5.0 })
        assertTrue(rig.progress.manualOverride)
        assertEquals(10.0, rig.progress.targetSpeedKmh)
        assertEquals(5.0, rig.progress.targetInclinePercent)
        assertEquals(RunState.Running, rig.progress.state)

        // Nothing fights the user for the rest of the block
        delay(50_000)
        assertEquals(10.0, rig.sim.currentSpeedKmh, 0.001)
        assertEquals(5.0, rig.sim.currentInclinePercent, 0.001)
        assertEquals(speedCommands, rig.count(SET_SPEED))
        assertEquals(inclineCommands, rig.count(SET_INCLINE))
        assertEquals(0, rig.progress.currentBlockIndex)

        // The next block (at 120 s) applies its own values again
        rig.awaitTrue(message = "block 2") { rig.progress.currentBlockIndex == 1 }
        rig.awaitTrue(message = "own values back") { rig.sim.currentSpeedKmh == 6.0 && rig.sim.currentInclinePercent == 0.0 }
        assertFalse(rig.progress.manualOverride)
        assertEquals(6.0, rig.progress.targetSpeedKmh)
        rig.runner.stop()
    }

    @Test
    fun `a manual change survives a pause and a resume within the block`() = runTest {
        val rig = ProgramRig(this)
        rig.connect()
        rig.start(planOf(timed(200, 8.0, 2.0), timed(30, 6.0)))
        delay(30_000)
        rig.sim.physicalSetSpeed(10.0)
        rig.sim.physicalSetIncline(5.0)
        delay(10_000)
        assertTrue(rig.progress.manualOverride)

        rig.runner.pause()
        rig.runner.resume()
        rig.awaitTrue(message = "manual values back") { rig.sim.currentSpeedKmh == 10.0 && rig.sim.currentInclinePercent == 5.0 }
        assertEquals(10.0, rig.progress.targetSpeedKmh)
        assertEquals(5.0, rig.progress.targetInclinePercent)
        assertTrue(rig.progress.manualOverride)
        assertEquals(RunState.Running, rig.progress.state)
        rig.runner.stop()
    }

    // ------------------------------------------------------------------ skipping

    @Test
    fun `skipping to the next and to the previous block`() = runTest {
        val rig = ProgramRig(this)
        rig.connect()
        rig.start(planOf(timed(60, 6.0), timed(60, 8.0, 2.0), timed(60, 5.0)))
        delay(3_000)

        assertEquals(RunnerResult.Success, rig.runner.skipToNext())
        assertEquals(1, rig.progress.currentBlockIndex)
        assertEquals(0, rig.progress.blockElapsedSeconds)
        assertEquals(BlockChangeReason.SkippedNext, rig.eventsOf<ProgramEvent.BlockStarted>().last().reason)
        delay(20_000)
        assertEquals(8.0, rig.sim.currentSpeedKmh, 0.001)
        assertEquals(2.0, rig.sim.currentInclinePercent, 0.001)

        // Back after 20 s in the block: it restarts the block instead
        rig.runner.skipToPrevious()
        assertEquals(1, rig.progress.currentBlockIndex)
        assertEquals(BlockChangeReason.Restarted, rig.eventsOf<ProgramEvent.BlockStarted>().last().reason)
        delay(2_000)
        // Back within 5 s of the block start: the previous block
        rig.runner.skipToPrevious()
        assertEquals(0, rig.progress.currentBlockIndex)
        assertEquals(BlockChangeReason.SkippedPrevious, rig.eventsOf<ProgramEvent.BlockStarted>().last().reason)
        rig.awaitTrue(message = "block 1 values") { rig.sim.currentSpeedKmh == 6.0 && rig.sim.currentInclinePercent == 0.0 }

        // From the first block it restarts the first block
        rig.runner.skipToPrevious()
        assertEquals(0, rig.progress.currentBlockIndex)
        assertEquals(BlockChangeReason.Restarted, rig.eventsOf<ProgramEvent.BlockStarted>().last().reason)

        rig.runner.skipToNext()
        rig.runner.skipToNext()
        assertEquals(2, rig.progress.currentBlockIndex)
        assertEquals(RunState.Running, rig.progress.state)

        // Skipping the last block ends the program like a normal finish
        assertEquals(RunnerResult.Success, rig.runner.skipToNext())
        assertEquals(RunState.Finished, rig.progress.state)
        assertEquals(RunOutcome.Completed, rig.progress.outcome)
        assertFalse(rig.sim.isBeltMoving)
        assertEquals(
            listOf(BlockChangeReason.SkippedNext, BlockChangeReason.Restarted, BlockChangeReason.SkippedPrevious, BlockChangeReason.Restarted, BlockChangeReason.SkippedNext, BlockChangeReason.SkippedNext, BlockChangeReason.SkippedNext),
            rig.eventsOf<ProgramEvent.BlockCompleted>().map { it.reason },
        )
    }

    @Test
    fun `skipping while paused changes the block and resume applies its values`() = runTest {
        val rig = ProgramRig(this)
        rig.connect()
        rig.start(planOf(timed(60, 6.0, 1.0), timed(60, 9.0, 4.0)))
        delay(15_000)
        rig.runner.pause()
        assertEquals(RunnerResult.Success, rig.runner.skipToNext())
        assertEquals(RunState.Paused, rig.progress.state)
        assertEquals(1, rig.progress.currentBlockIndex)
        assertEquals(0.0, rig.sim.currentSpeedKmh, 0.001)
        rig.runner.resume()
        rig.awaitTrue(message = "block 2 values") { rig.sim.currentSpeedKmh == 9.0 && rig.sim.currentInclinePercent == 4.0 }
        rig.runner.stop()
    }

    // ------------------------------------------------------------------ stop

    @Test
    fun `stop in the middle of the program stops the belt and ends the run`() = runTest {
        val rig = ProgramRig(this)
        rig.connect()
        rig.start(planOf(timed(120, 10.0, 3.0), timed(60, 6.0)))
        delay(30_000)
        assertEquals(RunnerResult.Success, rig.runner.stop())
        assertEquals(RunState.Finished, rig.progress.state)
        assertEquals(RunOutcome.StoppedByUser, rig.progress.outcome)
        assertFalse(rig.sim.isBeltMoving)
        val finished = rig.eventsOf<ProgramEvent.Finished>().single()
        assertEquals(RunOutcome.StoppedByUser, finished.outcome)
        assertEquals(CommandResult.Success, finished.stopResult)

        // Nothing else happens afterwards, and a new program can be started
        val commands = rig.opcodes.size
        delay(30_000)
        assertEquals(commands, rig.opcodes.size)
        assertEquals(RunnerResult.Success, rig.start(planOf(timed(30, 6.0))))
        rig.awaitState(RunState.Finished)
        assertEquals(RunOutcome.Completed, rig.progress.outcome)
        assertTrue(rig.runner.stop() is RunnerResult.NotAllowed)
    }

    // ------------------------------------------------------------------ interruptions

    @Test
    fun `the safety key interrupts the program, nothing is sent, and the user continues explicitly`() = runTest {
        val rig = ProgramRig(this)
        rig.connect()
        rig.start(planOf(timed(60, 8.0, 3.0), timed(30, 6.0)))
        delay(30_000)
        val elapsedBefore = rig.progress.totalElapsedSeconds

        rig.sim.removeSafetyKey()
        delay(2_000)
        assertEquals(RunState.Interrupted, rig.progress.state)
        assertEquals(InterruptionReason.SafetyKeyRemoved, rig.progress.interruption?.reason)
        assertEquals(0, rig.progress.interruption?.blockIndex)
        assertTrue(rig.eventsOf<ProgramEvent.Interrupted>().single().interruption.reason == InterruptionReason.SafetyKeyRemoved)
        val commands = rig.opcodes.size
        val frozenAt = rig.progress.totalElapsedSeconds

        delay(30_000)
        assertEquals(commands, rig.opcodes.size, "no commands while interrupted")
        assertEquals(frozenAt, rig.progress.totalElapsedSeconds, "the clock does not run")
        assertTrue(frozenAt - elapsedBefore <= 3)

        // The key is still out: refusal
        val refused = rig.runner.continueProgram()
        assertTrue(refused is RunnerResult.NotAllowed && refused.refusal == Refusal.SafetyKeyRemoved, "$refused")

        // Key back: the belt stays stopped and the program interrupted until the user says so
        rig.sim.restoreSafetyKey()
        delay(30_000)
        assertEquals(RunState.Interrupted, rig.progress.state)
        assertFalse(rig.sim.isBeltMoving, "the engine never restarts the belt on its own")
        assertEquals(commands, rig.opcodes.size)

        assertEquals(RunnerResult.Success, rig.runner.continueProgram())
        assertEquals(RunState.Running, rig.progress.state)
        assertNull(rig.progress.interruption)
        assertEquals(1, rig.eventsOf<ProgramEvent.Continued>().size)
        rig.awaitTrue(message = "targets again") { rig.sim.currentSpeedKmh == 8.0 && rig.sim.currentInclinePercent == 3.0 }
        assertEquals(0, rig.progress.currentBlockIndex)
        assertEquals(frozenAt.toDouble() + 2, rig.progress.blockElapsedSeconds.toDouble(), 20.0)

        rig.awaitState(RunState.Finished)
        assertEquals(RunOutcome.Completed, rig.progress.outcome)
        // 90 s of program time in total, the interruption excluded
        assertEquals(90.0, rig.eventsOf<ProgramEvent.Finished>().single().programSeconds.toDouble(), 3.0)
    }

    @Test
    fun `a lost connection interrupts the program and it can be continued after reconnecting`() = runTest {
        val rig = ProgramRig(this)
        rig.connect()
        rig.start(planOf(timed(60, 8.0, 3.0), timed(20, 6.0)))
        delay(30_000)

        rig.sim.dropConnection()
        delay(500)
        assertEquals(RunState.Interrupted, rig.progress.state)
        assertEquals(InterruptionReason.ConnectionLost, rig.progress.interruption?.reason)
        val refused = rig.runner.continueProgram()
        assertTrue(refused is RunnerResult.NotAllowed && refused.refusal == Refusal.NotConnected, "$refused")

        rig.awaitTrue(message = "reconnection") { rig.controller.connection.value is ConnectionState.Connected }
        delay(10_000)
        assertEquals(RunState.Interrupted, rig.progress.state, "reconnecting does not resume the program")
        assertFalse(rig.sim.isBeltMoving)
        val elapsed = rig.progress.totalElapsedSeconds

        assertEquals(RunnerResult.Success, rig.runner.continueProgram())
        assertEquals(RunState.Running, rig.progress.state)
        assertEquals(elapsed.toDouble(), rig.progress.totalElapsedSeconds.toDouble(), 1.0)
        rig.awaitTrue(message = "targets again") { rig.sim.currentSpeedKmh == 8.0 && rig.sim.currentInclinePercent == 3.0 }
        rig.awaitState(RunState.Finished)
        assertEquals(RunOutcome.Completed, rig.progress.outcome)
    }

    @Test
    fun `a command accepted but not executed interrupts the program`() = runTest {
        val rig = ProgramRig(this)
        rig.connect()
        rig.sim.speedFault = true
        assertEquals(RunnerResult.Success, rig.start(planOf(timed(60, 8.0), timed(30, 6.0))))
        rig.awaitState(RunState.Interrupted)
        val reason = rig.progress.interruption?.reason
        assertTrue(reason == InterruptionReason.CommandNotExecuted || reason == InterruptionReason.CommandFailed, "$reason")
        assertTrue(rig.controllerEvents.any { it == TreadmillEvent.CommandNotExecuted("setSpeed") })

        val commands = rig.opcodes.size
        delay(40_000)
        assertEquals(commands, rig.opcodes.size, "the engine issues nothing once interrupted")
        assertEquals(RunState.Interrupted, rig.progress.state)
        assertEquals(0, rig.count(STOP_PAUSE), "and it does not stop the belt by itself")

        // Abandoning ends the run without touching the belt
        assertEquals(RunnerResult.Success, rig.runner.abandon())
        assertEquals(RunOutcome.Abandoned, rig.progress.outcome)
        assertEquals(commands, rig.opcodes.size)
        assertTrue(rig.runner.abandon() is RunnerResult.NotAllowed)
    }

    @Test
    fun `stopping the belt from the console interrupts the program`() = runTest {
        val rig = ProgramRig(this)
        rig.connect()
        rig.start(planOf(timed(60, 8.0), timed(30, 6.0)))
        delay(30_000)
        rig.sim.physicalStop()
        delay(1_000)
        assertEquals(RunState.Interrupted, rig.progress.state)
        assertEquals(InterruptionReason.BeltChangedExternally, rig.progress.interruption?.reason)

        // The belt is still decelerating: the controller refuses to start, the program stays interrupted
        val early = rig.runner.continueProgram()
        assertTrue(early is RunnerResult.CommandFailed, "$early")
        assertEquals(RunState.Interrupted, rig.progress.state)

        rig.awaitTrue(message = "belt stopped") { !rig.sim.isBeltMoving }
        assertEquals(RunnerResult.Success, rig.runner.continueProgram())
        rig.awaitTrue(message = "targets again") { rig.sim.currentSpeedKmh == 8.0 }
        rig.awaitState(RunState.Finished)
        assertEquals(RunOutcome.Completed, rig.progress.outcome)
    }

    @Test
    fun `frozen data interrupts the program`() = runTest {
        val rig = ProgramRig(this)
        rig.connect()
        rig.start(planOf(timed(120, 8.0)))
        delay(30_000)
        rig.sim.freezeData(SimulatedTreadmill.FreezeMode.Silent)
        rig.awaitState(RunState.Interrupted, 30_000)
        assertEquals(InterruptionReason.DataStalled, rig.progress.interruption?.reason)
        // Data is not current: continuing is refused
        val refused = rig.runner.continueProgram()
        assertTrue(refused is RunnerResult.NotAllowed && refused.refusal == Refusal.DataNotCurrent, "$refused")
        rig.runner.abandon()
    }

    @Test
    fun `after frozen data comes back the program can be continued without restarting the belt`() = runTest {
        val rig = ProgramRig(this)
        rig.connect()
        rig.start(planOf(timed(120, 8.0, 2.0)))
        delay(30_000)
        rig.sim.freezeData(SimulatedTreadmill.FreezeMode.Silent)
        rig.awaitState(RunState.Interrupted, 30_000)
        rig.sim.freezeData(null)
        rig.awaitTrue(message = "fresh data") { !rig.controller.state.value.isStale }
        assertEquals(RunnerResult.Success, rig.runner.continueProgram())
        assertEquals(RunState.Running, rig.progress.state)
        assertEquals(1, rig.count(START), "the belt kept running: it is not started again")
        rig.runner.stop()
    }

    @Test
    fun `abandoning a running program leaves the belt alone`() = runTest {
        val rig = ProgramRig(this)
        rig.connect()
        rig.start(planOf(timed(120, 8.0)))
        delay(20_000)
        val commands = rig.opcodes.size
        assertEquals(RunnerResult.Success, rig.runner.abandon())
        assertEquals(RunOutcome.Abandoned, rig.progress.outcome)
        delay(20_000)
        assertEquals(commands, rig.opcodes.size)
        assertTrue(rig.sim.isBeltMoving)
        assertEquals(RunOutcome.Abandoned, rig.eventsOf<ProgramEvent.Finished>().single().outcome)
        rig.controller.stop()
    }

    // ------------------------------------------------------------------ start and end options

    @Test
    fun `handing over to manual leaves the belt running at the end`() = runTest {
        val rig = ProgramRig(this)
        rig.connect()
        rig.start(planOf(timed(20, 6.0), timed(20, 7.0)), RunOptions(EndAction.HandOverToManual))
        rig.awaitState(RunState.Finished)
        assertEquals(RunOutcome.Completed, rig.progress.outcome)
        delay(10_000)
        assertTrue(rig.sim.isBeltMoving)
        assertEquals(BeltStatus.Running, rig.controller.state.value.status)
        assertEquals(0, rig.count(STOP_PAUSE))
        val finished = rig.eventsOf<ProgramEvent.Finished>().single()
        assertEquals(EndAction.HandOverToManual, finished.endAction)
        assertNull(finished.stopResult)
        rig.controller.stop()
    }

    @Test
    fun `a stop accepted but not executed at the end still ends the program and reports it`() = runTest {
        val rig = ProgramRig(this)
        rig.connect()
        rig.start(planOf(timed(20, 6.0)))
        delay(5_000)
        rig.sim.stopFault = true
        rig.awaitState(RunState.Finished)
        assertEquals(RunOutcome.Completed, rig.progress.outcome)
        val result = rig.eventsOf<ProgramEvent.Finished>().single().stopResult
        assertTrue(result is CommandResult.NotVerified, "$result")
        assertTrue(rig.controllerEvents.contains(TreadmillEvent.CommandNotExecuted("stop")))
    }

    @Test
    fun `a failed start leaves the runner idle`() = runTest {
        val rig = ProgramRig(this)
        rig.connect()
        rig.sim.startFault = true
        val r = rig.start(planOf(timed(30, 6.0)))
        assertTrue(r is RunnerResult.CommandFailed, "$r")
        assertEquals(RunState.Idle, rig.progress.state)
        assertTrue(rig.events.isEmpty())
        rig.sim.startFault = false
        delay(5_000)
        assertEquals(RunnerResult.Success, rig.start(planOf(timed(30, 6.0))))
        rig.runner.stop()
    }

    @Test
    fun `a belt that is already running is used as it is`() = runTest {
        val rig = ProgramRig(this)
        rig.connect()
        assertEquals(CommandResult.Success, rig.controller.start())
        assertEquals(RunnerResult.Success, rig.start(planOf(timed(30, 9.0, 2.0))))
        assertEquals(1, rig.count(START))
        rig.awaitTrue(message = "targets") { rig.sim.currentSpeedKmh == 9.0 && rig.sim.currentInclinePercent == 2.0 }
        rig.awaitState(RunState.Finished)
    }

    @Test
    fun `a paused belt is resumed when the program starts`() = runTest {
        val rig = ProgramRig(this)
        rig.connect()
        rig.controller.start()
        rig.controller.setSpeed(7.0)
        delay(15_000)
        rig.controller.pause()
        assertEquals(RunnerResult.Success, rig.start(planOf(timed(30, 9.0))))
        assertEquals(RunState.Running, rig.progress.state)
        rig.awaitTrue(message = "targets") { rig.sim.currentSpeedKmh == 9.0 }
        rig.runner.stop()
    }

    @Test
    fun `starting is refused when it makes no sense`() = runTest {
        val rig = ProgramRig(this)
        val plan = planOf(timed(60, 6.0))
        val notConnected = rig.start(plan)
        assertTrue(notConnected is RunnerResult.NotAllowed && notConnected.refusal == Refusal.NotConnected, "$notConnected")

        rig.connect()
        val empty = rig.start(plan.copy(blocks = emptyList()))
        assertTrue(empty is RunnerResult.NotAllowed && empty.refusal == Refusal.InvalidPlan, "$empty")

        rig.sim.removeSafetyKey()
        delay(2_000)
        val key = rig.start(plan)
        assertTrue(key is RunnerResult.NotAllowed && key.refusal == Refusal.SafetyKeyRemoved, "$key")
        rig.sim.restoreSafetyKey()
        delay(2_000)

        assertEquals(RunnerResult.Success, rig.start(plan))
        val twice = rig.start(plan)
        assertTrue(twice is RunnerResult.NotAllowed && twice.refusal == Refusal.AlreadyActive, "$twice")
        assertTrue(rig.runner.continueProgram() is RunnerResult.NotAllowed)
        assertTrue(rig.runner.abandon() is RunnerResult.Success)
        assertEquals(0, rig.count(SET_INCLINE), "an incline of 0 on a belt at 0 is not sent")
    }

    // ------------------------------------------------------------------ heart rate

    @Test
    fun `the heart rate comes from an abstract source that reports validity`() = runTest {
        val rig = ProgramRig(this)
        rig.connect()
        rig.start(planOf(timed(60, 6.0)))
        delay(5_000)
        assertNull(rig.progress.heartRateBpm)
        assertEquals(HeartRateValidity.Invalid, rig.progress.heartRateValidity)
        rig.sim.setHeartRate(142)
        delay(3_000)
        assertEquals(142, rig.progress.heartRateBpm)
        assertEquals(HeartRateValidity.Unconfirmed, rig.progress.heartRateValidity)
        rig.sim.setHeartRate(0)
        delay(3_000)
        assertNull(rig.progress.heartRateBpm)
        assertEquals(HeartRateValidity.Invalid, rig.progress.heartRateValidity)
        rig.runner.stop()
    }
}
