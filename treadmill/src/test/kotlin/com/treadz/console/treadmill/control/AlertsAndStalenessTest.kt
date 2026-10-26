package com.treadz.console.treadmill.control

import com.treadz.console.treadmill.BeltStatus
import com.treadz.console.treadmill.CommandResult
import com.treadz.console.treadmill.ConnectionState
import com.treadz.console.treadmill.FailureCode
import com.treadz.console.treadmill.TreadmillAlert
import com.treadz.console.treadmill.TreadmillEvent
import com.treadz.console.treadmill.recording.RecordSerializer
import com.treadz.console.treadmill.simulator.SimulatedTreadmill.FreezeMode
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import com.treadz.console.treadmill.simulator.SimulatedTreadmill.DataRate

/** Pending alerts to acknowledge, stable failure codes and the stale data flag. */
@OptIn(ExperimentalCoroutinesApi::class)
class AlertsAndStalenessTest {

    private val Rig.pending: List<TreadmillAlert> get() = controller.pendingAlerts.value

    // ------------------------------------------------------------------ pending alerts

    @ParameterizedTest(name = "{displayName} [{0}]")
    @EnumSource(DataRate::class)
    fun `an alert stays pending even if nobody listens to events, without duplicates, until acknowledged`(rate: DataRate) = runTest {
        val rig = Rig(this, rate)
        rig.runAt(4.0)
        rig.sim.stopFault = true
        assertTrue(rig.controller.stop() is CommandResult.NotVerified)
        assertTrue(rig.controller.stop() is CommandResult.NotVerified)
        assertEquals(2, rig.eventsOf(TreadmillEvent.CommandNotExecuted::class).size, "events reports every time")
        assertEquals(listOf<TreadmillAlert>(TreadmillEvent.CommandNotExecuted("stop")), rig.pending, "but only one stays pending")

        rig.controller.acknowledgeAlert(TreadmillEvent.CommandNotExecuted("stop"))
        assertTrue(rig.pending.isEmpty())
        assertTrue(rig.trace.any { it is TraceRecord.Note && it.text.contains("Alert acknowledged") }, "the acknowledgement is recorded")
    }

    @ParameterizedTest(name = "{displayName} [{0}]")
    @EnumSource(DataRate::class)
    fun `manual changes and key restored are not pending alerts`(rate: DataRate) = runTest {
        val rig = Rig(this, rate)
        rig.runAt(3.0)
        rig.sim.physicalSetIncline(4.0)
        delay(3_000)
        assertTrue(rig.events.any { it is TreadmillEvent.ManualChange })
        assertTrue(rig.pending.isEmpty(), "${rig.pending}")
    }

    @ParameterizedTest(name = "{displayName} [{0}]")
    @EnumSource(DataRate::class)
    fun `the key removed alert clears itself when the key is put back`(rate: DataRate) = runTest {
        val rig = Rig(this, rate)
        rig.runAt(2.0, incline = 5.0)
        rig.sim.removeSafetyKey()
        delay(2_000)
        assertEquals(listOf<TreadmillAlert>(TreadmillEvent.SafetyKeyRemoved), rig.pending)
        rig.sim.restoreSafetyKey()
        delay(3_000)
        assertEquals(BeltStatus.Idle, rig.controller.state.value.status)
        assertTrue(rig.pending.isEmpty(), "${rig.pending}")
    }

    @ParameterizedTest(name = "{displayName} [{0}]")
    @EnumSource(DataRate::class)
    fun `several alerts pile up in order and are acknowledged separately`(rate: DataRate) = runTest {
        val rig = Rig(this, rate)
        rig.runAt(3.0)
        rig.sim.freezeData(FreezeMode.Silent)
        delay(8_000)
        rig.sim.freezeData(null)
        rig.sim.dropConnection()
        delay(100)
        assertEquals(listOf(TreadmillEvent.DataStalled, TreadmillEvent.ConnectionLost), rig.pending)
        rig.controller.acknowledgeAlert(TreadmillEvent.DataStalled)
        assertEquals(listOf<TreadmillAlert>(TreadmillEvent.ConnectionLost), rig.pending)
        delay(5_000)
        assertTrue(rig.connected)
        assertEquals(listOf<TreadmillAlert>(TreadmillEvent.ConnectionLost), rig.pending, "reconnecting does not acknowledge the alert")
    }

    // ------------------------------------------------------------------ stale data

    @ParameterizedTest(name = "{displayName} [{0}]")
    @EnumSource(DataRate::class)
    fun `when the connection is lost the last values are kept and marked as stale`(rate: DataRate) = runTest {
        val rig = Rig(this, rate)
        rig.runAt(5.0, incline = 3.0)
        assertFalse(rig.controller.state.value.isStale)
        rig.sim.dropConnection()
        delay(100)
        val s = rig.controller.state.value
        assertTrue(rig.controller.connection.value is ConnectionState.Reconnecting)
        assertTrue(s.isStale)
        assertEquals(5.0, s.speedKmh, 1e-9, "a stopped belt that may not be stopped is not shown")
        assertEquals(BeltStatus.Running, s.status)

        delay(5_000)
        assertTrue(rig.connected)
        assertFalse(rig.controller.state.value.isStale, "with new data after reconnecting they are current again")
    }

    @ParameterizedTest(name = "{displayName} [{0}]")
    @EnumSource(DataRate::class)
    fun `data that stops arriving marks the state as stale until it comes back`(rate: DataRate) = runTest {
        val rig = Rig(this, rate)
        rig.runAt(3.0)
        rig.sim.freezeData(FreezeMode.Silent)
        delay(4_000)
        assertFalse(rig.controller.state.value.isStale, "the deadline has not passed yet")
        delay(4_000)
        assertTrue(rig.controller.state.value.isStale)
        rig.sim.freezeData(null)
        delay(2_000)
        assertFalse(rig.controller.state.value.isStale)
    }

    @ParameterizedTest(name = "{displayName} [{0}]")
    @EnumSource(DataRate::class)
    fun `repeated packets with the stopwatch stuck are stale data too`(rate: DataRate) = runTest {
        val rig = Rig(this, rate)
        rig.runAt(3.0)
        rig.sim.freezeData(FreezeMode.Repeat)
        delay(10_000)
        assertTrue(rig.controller.state.value.isStale)
        rig.sim.freezeData(null)
        delay(3_000)
        assertFalse(rig.controller.state.value.isStale)
    }

    @ParameterizedTest(name = "{displayName} [{0}]")
    @EnumSource(DataRate::class)
    fun `after a requested disconnect the values stay as stale`(rate: DataRate) = runTest {
        val rig = Rig(this, rate)
        rig.connect()
        rig.controller.disconnect()
        assertEquals(ConnectionState.Disconnected, rig.controller.connection.value)
        assertTrue(rig.controller.state.value.isStale)
    }

    // ------------------------------------------------------------------ failure codes

    private fun CommandResult.code(): FailureCode? = (this as? CommandResult.Failure)?.code

    @ParameterizedTest(name = "{displayName} [{0}]")
    @EnumSource(DataRate::class)
    fun `every failure carries a stable code`(rate: DataRate) = runTest {
        val rig = Rig(this, rate)
        assertEquals(FailureCode.ConnectionFailed, rig.controller.connect("00:00:00:00:00:00").code())

        rig.connect()
        assertEquals(FailureCode.BeltNotRunning, rig.controller.setIncline(3.0).code())
        assertEquals(FailureCode.BeltNotRunning, rig.controller.pause().code())
        assertEquals(FailureCode.NothingToResume, rig.controller.resume().code())

        assertEquals(CommandResult.Success, rig.controller.start())
        assertEquals(FailureCode.AlreadyRunning, rig.controller.start().code())

        // Ignored stop: the "3 packets without decreasing" rule fires before the 02 status deadline.
        rig.sim.stopFault = true
        assertEquals(FailureCode.StillMoving, rig.controller.stop().code())
        rig.sim.stopFault = false
        rig.sim.stopStatusOnlyFault = true
        assertEquals(FailureCode.StillMoving, rig.controller.stop().code())
    }

    @ParameterizedTest(name = "{displayName} [{0}]")
    @EnumSource(DataRate::class)
    fun `a command interrupted by a stop is Preempted and raises no alert`(rate: DataRate) = runTest {
        val rig = Rig(this, rate)
        rig.connect()
        val start = async { rig.controller.start() }
        delay(1_000)
        assertEquals(CommandResult.Success, rig.controller.stop())
        assertEquals(FailureCode.Preempted, start.await().code())
        assertTrue(rig.pending.isEmpty(), "${rig.pending}")
    }

    @ParameterizedTest(name = "{displayName} [{0}]")
    @EnumSource(DataRate::class)
    fun `a stop does not wait for the Control Point response of the command before it`(rate: DataRate) = runTest {
        val rig = Rig(this, rate)
        rig.runAt(3.0)
        // The speed command is not answered within the response timeout (3 s).
        rig.sim.controlPointLatencyMs = 10_000
        val speed = async { rig.controller.setSpeed(6.0) }
        delay(300)
        rig.sim.controlPointLatencyMs = 80
        val stop = async { rig.controller.stop() }
        delay(200)
        assertTrue("0801" in rig.commandsHex, "the stop is written at once: ${rig.commandsHex}")
        assertEquals(FailureCode.Preempted, speed.await().code())
        assertEquals(CommandResult.Success, stop.await())
        assertTrue(rig.pending.isEmpty(), "${rig.pending}")
    }

    @ParameterizedTest(name = "{displayName} [{0}]")
    @EnumSource(DataRate::class)
    fun `a stop refused while the command before it is unanswered is written again`(rate: DataRate) = runTest {
        val rig = Rig(this, rate)
        rig.runAt(3.0)
        rig.sim.refusesWritesInProgress = true
        rig.sim.controlPointLatencyMs = 1_000
        val speed = async { rig.controller.setSpeed(6.0) }
        delay(300)
        val stop = async { rig.controller.stop() }
        assertEquals(FailureCode.Preempted, speed.await().code())
        assertEquals(CommandResult.Success, stop.await())
        assertEquals(1, rig.commandsHex.count { it == "0801" }, "${rig.commandsHex}")
        assertTrue(rig.pending.isEmpty(), "${rig.pending}")
    }

    @Test
    fun `the recording keeps the code of every failure and the stale flag`() {
        val fields = RecordSerializer.resultFields(CommandResult.NotVerified(FailureCode.StillMoving, "not decreasing"))
        assertEquals("StillMoving", fields["code"])
        assertEquals("not decreasing", fields["reason"])
        val state = RecordSerializer.stateFields(com.treadz.console.treadmill.TreadmillState(isStale = true))
        assertEquals(true, state["stale"])
    }
}
