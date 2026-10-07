package com.treadz.console.treadmill.control

import com.treadz.console.treadmill.BeltStatus
import com.treadz.console.treadmill.CommandResult
import com.treadz.console.treadmill.ConnectionState
import com.treadz.console.treadmill.SafetyLimits
import com.treadz.console.treadmill.TreadmillEvent
import com.treadz.console.treadmill.ftms.ControlPointResult
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertFalse
import com.treadz.console.treadmill.notNull
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import com.treadz.console.treadmill.simulator.SimulatedTreadmill.DataRate

/** Full controller scenarios against the simulated treadmill, with virtual time. */
@OptIn(ExperimentalCoroutinesApi::class)
class ControllerScenariosTest {

    // ------------------------------------------------------------------ connection

    @ParameterizedTest(name = "{displayName} [{0}]")
    @EnumSource(DataRate::class)
    fun `connecting reads capabilities, subscribes and requests control (Request Control) first`(rate: DataRate) = runTest {
        val rig = Rig(this, rate)
        assertEquals(ConnectionState.Disconnected, rig.controller.connection.value)
        assertNull(rig.controller.capabilities.value)

        assertEquals(CommandResult.Success, rig.connect())

        val c = rig.controller
        assertTrue(c.connection.value is ConnectionState.Connected)
        val caps = notNullCaps(rig)
        assertEquals(0.5, caps.speed.min)
        assertEquals(20.0, caps.speed.max)
        assertEquals(0.1, caps.speed.step, 1e-9)
        assertEquals(0.0, caps.incline.min)
        assertEquals(18.0, caps.incline.max)
        assertEquals("ChangYow", caps.manufacturer)
        assertEquals("SI3019", caps.model)
        assertEquals("07", caps.firmware)
        assertEquals(listOf("00"), rig.commandsHex)
        assertEquals(BeltStatus.Idle, c.state.value.status)
        assertTrue(c.state.value.updatedAtMillis > 0, "after a few seconds data must have arrived")
    }

    private fun notNullCaps(rig: Rig) = notNull(rig.controller.capabilities.value)

    @ParameterizedTest(name = "{displayName} [{0}]")
    @EnumSource(DataRate::class)
    fun `the first Treadmill Data packet after connecting is discarded as stale data`(rate: DataRate) = runTest {
        val rig = Rig(this, rate)
        rig.connect()
        val discarded = rig.samples.filter { it.discarded }
        assertEquals(1, discarded.size)
        assertTrue(rig.samples.any { !it.discarded })
    }

    @ParameterizedTest(name = "{displayName} [{0}]")
    @EnumSource(DataRate::class)
    fun `connecting to an address that does not exist returns an error and leaves the connection closed`(rate: DataRate) = runTest {
        val rig = Rig(this, rate)
        val r = rig.controller.connect("00:00:00:00:00:00")
        assertTrue(r is CommandResult.Rejected, "result: $r")
        assertEquals(ConnectionState.Disconnected, rig.controller.connection.value)
        assertNull(rig.controller.capabilities.value)
    }

    @ParameterizedTest(name = "{displayName} [{0}]")
    @EnumSource(DataRate::class)
    fun `without a connection every command returns NotConnected`(rate: DataRate) = runTest {
        val rig = Rig(this, rate)
        assertEquals(CommandResult.NotConnected, rig.controller.start())
        assertEquals(CommandResult.NotConnected, rig.controller.stop())
        assertEquals(CommandResult.NotConnected, rig.controller.pause())
        assertEquals(CommandResult.NotConnected, rig.controller.resume())
        assertEquals(CommandResult.NotConnected, rig.controller.setSpeed(3.0))
        assertEquals(CommandResult.NotConnected, rig.controller.setIncline(3.0))
    }

    @ParameterizedTest(name = "{displayName} [{0}]")
    @EnumSource(DataRate::class)
    fun `Request Control rejected is reported but the connection and the data go on`(rate: DataRate) = runTest {
        val rig = Rig(this, rate)
        rig.sim.forcedResults[0x00] = ControlPointResult.OperationFailed
        val r = rig.controller.connect(rig.sim.address)
        assertTrue(r is CommandResult.Rejected)
        assertTrue(rig.connected)
    }

    // ------------------------------------------------------------------ start

    @ParameterizedTest(name = "{displayName} [{0}]")
    @EnumSource(DataRate::class)
    fun `start, treadmill countdown and belt running at 0_5 km_h and 0 percent`(rate: DataRate) = runTest {
        val rig = Rig(this, rate)
        rig.connect()
        val before = rig.now
        assertEquals(CommandResult.Success, rig.controller.start())
        val took = rig.now - before
        assertTrue(took in 3_000..8_000, "the start must wait for the ~3.4 s countdown: $took ms")
        val s = rig.controller.state.value
        assertEquals(BeltStatus.Running, s.status)
        assertEquals(0.5, s.speedKmh, 1e-9)
        assertEquals(0.0, s.inclinePercent)
        assertEquals(0.5, s.targetSpeedKmh)
        assertEquals(listOf("00", "07"), rig.commandsHex)
        assertTrue(rig.events.isEmpty(), "no alerts: ${rig.events}")
        // The session started counting on the treadmill
        delay(5_000)
        assertTrue(rig.controller.state.value.elapsedSeconds >= 4)
    }

    @ParameterizedTest(name = "{displayName} [{0}]")
    @EnumSource(DataRate::class)
    fun `start with the belt already running or starting is not valid`(rate: DataRate) = runTest {
        val rig = Rig(this, rate)
        rig.connectAndStart()
        assertTrue(rig.controller.start() is CommandResult.NotAllowed)
    }

    @ParameterizedTest(name = "{displayName} [{0}]")
    @EnumSource(DataRate::class)
    fun `start accepted but not executed is NotVerified and alerts`(rate: DataRate) = runTest {
        val rig = Rig(this, rate)
        rig.connect()
        rig.sim.startFault = true
        val r = rig.controller.start()
        assertTrue(r is CommandResult.NotVerified, "result: $r")
        assertEquals(BeltStatus.Idle, rig.controller.state.value.status)
        assertEquals(listOf(TreadmillEvent.CommandNotExecuted("start")), rig.events)
    }

    @ParameterizedTest(name = "{displayName} [{0}]")
    @EnumSource(DataRate::class)
    fun `start rejected by the treadmill is Rejected`(rate: DataRate) = runTest {
        val rig = Rig(this, rate)
        rig.connect()
        rig.sim.forcedResults[0x07] = ControlPointResult.OperationFailed
        val r = rig.controller.start()
        assertTrue(r is CommandResult.Rejected, "result: $r")
        assertEquals(BeltStatus.Idle, rig.controller.state.value.status)
    }

    @ParameterizedTest(name = "{displayName} [{0}]")
    @EnumSource(DataRate::class)
    fun `starting with the console buttons is neither a manual change nor an alert`(rate: DataRate) = runTest {
        val rig = Rig(this, rate)
        rig.connect()
        rig.sim.physicalStart()
        delay(8_000)
        val s = rig.controller.state.value
        assertEquals(BeltStatus.Running, s.status)
        assertEquals(0.5, s.speedKmh, 1e-9)
        assertTrue(rig.events.isEmpty(), "${rig.events}")
    }

    // ------------------------------------------------------------------ speed and incline

    @ParameterizedTest(name = "{displayName} [{0}]")
    @EnumSource(DataRate::class)
    fun `a speed change is verified and the belt ramps to it`(rate: DataRate) = runTest {
        val rig = Rig(this, rate)
        rig.connectAndStart()
        val before = rig.now
        assertEquals(CommandResult.Success, rig.controller.setSpeed(3.0))
        assertTrue(rig.now - before < 5_000, "no need to wait for the ramp to finish")
        assertEquals(3.0, rig.controller.state.value.targetSpeedKmh)
        assertTrue(rig.commandsHex.contains("022c01"))
        delay(8_000)
        assertEquals(3.0, rig.controller.state.value.speedKmh, 1e-9)
        assertTrue(rig.events.isEmpty(), "changes made by the app are not manual changes: ${rig.events}")
    }

    @ParameterizedTest(name = "{displayName} [{0}]")
    @EnumSource(DataRate::class)
    fun `an incline change is verified with the value the treadmill reports`(rate: DataRate) = runTest {
        val rig = Rig(this, rate)
        rig.connectAndStart()
        assertEquals(CommandResult.Success, rig.controller.setIncline(5.0))
        delay(2_000)
        assertEquals(5.0, rig.controller.state.value.inclinePercent)
        assertTrue(rig.commandsHex.contains("033200"))
        assertTrue(rig.events.isEmpty(), "${rig.events}")
    }

    @ParameterizedTest(name = "{displayName} [{0}]")
    @EnumSource(DataRate::class)
    fun `incline with the belt stopped is NotAllowed and not sent`(rate: DataRate) = runTest {
        val rig = Rig(this, rate)
        rig.connect()
        val r = rig.controller.setIncline(4.0)
        assertTrue(r is CommandResult.NotAllowed, "result: $r")
        assertEquals(listOf("00"), rig.commandsHex)
        // Speed neither, until the belt is running
        assertTrue(rig.controller.setSpeed(4.0) is CommandResult.NotAllowed)
    }

    @ParameterizedTest(name = "{displayName} [{0}]")
    @EnumSource(DataRate::class)
    fun `speed accepted but not changing is NotVerified`(rate: DataRate) = runTest {
        val rig = Rig(this, rate)
        rig.connectAndStart()
        rig.sim.speedFault = true
        val r = rig.controller.setSpeed(6.0)
        assertTrue(r is CommandResult.NotVerified, "result: $r")
        assertEquals(listOf(TreadmillEvent.CommandNotExecuted("setSpeed")), rig.events)
        assertEquals(0.5, rig.controller.state.value.targetSpeedKmh, "the target does not change if it is not verified")
    }

    @ParameterizedTest(name = "{displayName} [{0}]")
    @EnumSource(DataRate::class)
    fun `incline accepted but not changing is NotVerified`(rate: DataRate) = runTest {
        val rig = Rig(this, rate)
        rig.connectAndStart()
        rig.sim.inclineFault = true
        val r = rig.controller.setIncline(6.0)
        assertTrue(r is CommandResult.NotVerified, "result: $r")
        assertEquals(listOf(TreadmillEvent.CommandNotExecuted("setIncline")), rig.events)
    }

    // ------------------------------------------------------------------ safety limits

    @ParameterizedTest(name = "{displayName} [{0}]")
    @EnumSource(DataRate::class)
    fun `commands are clamped to the treadmill range`(rate: DataRate) = runTest {
        val rig = Rig(this, rate)
        rig.runAt(3.0)
        assertEquals(CommandResult.Success, rig.controller.setSpeed(35.0))
        assertEquals(CommandResult.Success, rig.controller.setIncline(30.0))
        assertTrue(rig.commandsHex.contains("02d007"), "20,0 km/h = 07d0: ${rig.commandsHex}")
        assertTrue(rig.commandsHex.contains("03b400"), "18 % = 180 = 00b4: ${rig.commandsHex}")
        assertEquals(CommandResult.Success, rig.controller.setSpeed(0.1))
        assertTrue(rig.commandsHex.contains("023200"), "minimo 0,5 km/h: ${rig.commandsHex}")
        assertTrue(rig.sim.forbiddenCommands.isEmpty())
    }

    @ParameterizedTest(name = "{displayName} [{0}]")
    @EnumSource(DataRate::class)
    fun `safety limits clamp speed and incline and the treadmill never sees higher values`(rate: DataRate) = runTest {
        val rig = Rig(this, rate)
        rig.controller.setLimits(SafetyLimits(maxSpeedKmh = 8.0, maxInclinePercent = 6.0))
        assertEquals(SafetyLimits(8.0, 6.0), rig.controller.limits.value)
        rig.connectAndStart()
        assertEquals(CommandResult.Success, rig.controller.setSpeed(12.0))
        assertEquals(CommandResult.Success, rig.controller.setIncline(10.0))
        delay(20_000)
        assertEquals(8.0, rig.controller.state.value.speedKmh, 1e-9)
        assertEquals(6.0, rig.controller.state.value.inclinePercent, 1e-9)
        assertEquals(8.0, rig.controller.state.value.targetSpeedKmh)
        assertTrue(rig.commandsHex.contains("022003"), "8,0 km/h = 0320: ${rig.commandsHex}")
        assertTrue(rig.commandsHex.contains("033c00"), "6 % = 60 = 003c: ${rig.commandsHex}")
        // No speed command above 8 km/h went out to the treadmill
        val maxSent = rig.sim.receivedCommands.filter { it[0].toInt() == 2 }
            .maxOf { ((it[1].toInt() and 0xFF) or ((it[2].toInt() and 0xFF) shl 8)) / 100.0 }
        assertTrue(maxSent <= 8.0, "maximum speed sent: $maxSent")
    }

    @ParameterizedTest(name = "{displayName} [{0}]")
    @EnumSource(DataRate::class)
    fun `resuming also honours limits changed while paused`(rate: DataRate) = runTest {
        val rig = Rig(this, rate)
        rig.runAt(10.0, incline = 8.0)
        assertEquals(CommandResult.Success, rig.controller.pause())
        rig.controller.setLimits(SafetyLimits(maxSpeedKmh = 5.0, maxInclinePercent = 3.0))
        assertEquals(CommandResult.Success, rig.controller.resume())
        delay(15_000)
        assertEquals(5.0, rig.controller.state.value.speedKmh, 1e-9)
        assertEquals(3.0, rig.controller.state.value.inclinePercent, 1e-9)
    }

    // ------------------------------------------------------------------ pause and resume

    @ParameterizedTest(name = "{displayName} [{0}]")
    @EnumSource(DataRate::class)
    fun `pause and resume send speed and incline again only once the belt is running`(rate: DataRate) = runTest {
        val rig = Rig(this, rate)
        rig.runAt(3.0, incline = 5.0)
        val distanceBefore = rig.controller.state.value.distanceMeters

        assertEquals(CommandResult.Success, rig.controller.pause())
        val paused = rig.controller.state.value
        assertEquals(BeltStatus.Paused, paused.status)
        assertEquals(0.0, paused.speedKmh)
        assertEquals(0.0, rig.sim.currentInclinePercent, "pausing lowers the incline to 0")
        delay(3_000)
        assertEquals(BeltStatus.Paused, rig.controller.state.value.status)
        assertEquals(paused.elapsedSeconds, rig.controller.state.value.elapsedSeconds, "time does not advance while paused")
        assertTrue(rig.controller.state.value.distanceMeters >= distanceBefore, "pausing keeps the distance")
        assertTrue(rig.controller.state.value.elapsedSeconds > 0, "pausing keeps the time")

        assertEquals(CommandResult.Success, rig.controller.resume())
        delay(12_000)
        val s = rig.controller.state.value
        assertEquals(BeltStatus.Running, s.status)
        assertEquals(3.0, s.speedKmh, 1e-9)
        assertEquals(5.0, s.inclinePercent, 1e-9)

        // Order sent: 00, 07, 02 (3.0), 03 (5), 0802, 07, 02 (3.0), 03 (5)
        assertEquals(listOf("00", "07", "022c01", "033200", "0802", "07", "022c01", "033200"), rig.commandsHex)
        // The re-sent commands were written AFTER the belt moved again
        val resumeTx = rig.trace.filterIsInstance<TraceRecord.RawSent>().last { it.value.size == 1 && it.value[0].toInt() == 7 }
        val firstMoving = rig.samples.first { it.atMillis > resumeTx.atMillis && it.sample.speedKmh > 0 && !it.discarded }
        val reSpeed = rig.trace.filterIsInstance<TraceRecord.RawSent>().last { it.value[0].toInt() == 2 }
        val reIncline = rig.trace.filterIsInstance<TraceRecord.RawSent>().last { it.value[0].toInt() == 3 }
        assertTrue(reSpeed.atMillis >= firstMoving.atMillis, "speed re-sent before the belt moves")
        assertTrue(reIncline.atMillis >= reSpeed.atMillis)
        assertTrue(rig.events.isEmpty(), "${rig.events}")
    }

    @ParameterizedTest(name = "{displayName} [{0}]")
    @EnumSource(DataRate::class)
    fun `pause and resume are not valid outside their state`(rate: DataRate) = runTest {
        val rig = Rig(this, rate)
        rig.connect()
        assertTrue(rig.controller.pause() is CommandResult.NotAllowed)
        assertTrue(rig.controller.resume() is CommandResult.NotAllowed)
        rig.connectAndStart()
        assertTrue(rig.controller.resume() is CommandResult.NotAllowed)
    }

    @ParameterizedTest(name = "{displayName} [{0}]")
    @EnumSource(DataRate::class)
    fun `start from pause is the same as resume`(rate: DataRate) = runTest {
        val rig = Rig(this, rate)
        rig.runAt(2.0, incline = 3.0)
        assertEquals(CommandResult.Success, rig.controller.pause())
        assertEquals(CommandResult.Success, rig.controller.start())
        delay(8_000)
        assertEquals(2.0, rig.controller.state.value.speedKmh, 1e-9)
        assertEquals(3.0, rig.controller.state.value.inclinePercent, 1e-9)
    }

    @ParameterizedTest(name = "{displayName} [{0}]")
    @EnumSource(DataRate::class)
    fun `pause accepted but not executed is NotVerified with an alert`(rate: DataRate) = runTest {
        val rig = Rig(this, rate)
        rig.runAt(3.0)
        rig.sim.stopFault = true
        val r = rig.controller.pause()
        assertTrue(r is CommandResult.NotVerified, "result: $r")
        assertEquals(listOf(TreadmillEvent.CommandNotExecuted("pause")), rig.events)
        assertTrue(rig.sim.isBeltMoving)
        assertEquals(BeltStatus.Running, rig.controller.state.value.status)
    }

    // ------------------------------------------------------------------ stop

    @ParameterizedTest(name = "{displayName} [{0}]")
    @EnumSource(DataRate::class)
    fun `stop waits for the belt to reach 0 without a fixed timeout and leaves the treadmill Idle`(rate: DataRate) = runTest {
        val rig = Rig(this, rate)
        rig.runAt(12.0, incline = 4.0)
        val before = rig.now
        val stopSentAt = rig.now
        assertEquals(CommandResult.Success, rig.controller.stop())
        val took = rig.now - before
        // 12 km/h at ~0.5 km/h per second: around 24 s. The verification does not cut it short.
        assertTrue(took in 20_000..32_000, "stop from 12 km/h: $took ms")
        assertEquals(0.0, rig.sim.currentSpeedKmh)
        assertEquals(0.0, rig.controller.state.value.speedKmh)
        assertEquals(BeltStatus.Idle, rig.controller.state.value.status)
        // The speed went down on every packet
        val after = rig.samples.filter { it.atMillis > T0 + stopSentAt && !it.discarded }.map { it.sample.speedKmh }
        // Every distinct value is lower than the previous one (at 2 Hz each value arrives twice)
        val distinct = after.fold(mutableListOf<Double>()) { acc, v -> if (acc.lastOrNull() != v) acc += v; acc }
        assertTrue(distinct.zipWithNext().all { (a, b) -> b < a }, "not decreasing: $distinct")
        assertTrue(after.size <= 2 * distinct.size, "a value repeated more than twice: $after")
        delay(3_000)
        val s = rig.controller.state.value
        assertEquals(0, s.elapsedSeconds, "the treadmill resets the session")
        assertEquals(0, s.distanceMeters)
        assertEquals(0.0, s.inclinePercent)
        assertTrue(rig.events.isEmpty(), "${rig.events}")
        assertTrue(rig.commandsHex.contains("0801"))
    }

    @ParameterizedTest(name = "{displayName} [{0}]")
    @EnumSource(DataRate::class)
    fun `stop at low speed is verified too`(rate: DataRate) = runTest {
        val rig = Rig(this, rate)
        rig.connectAndStart()
        assertEquals(CommandResult.Success, rig.controller.stop())
        assertEquals(BeltStatus.Idle, rig.controller.state.value.status)
        assertEquals(0.0, rig.sim.currentSpeedKmh)
    }

    @ParameterizedTest(name = "{displayName} [{0}]")
    @EnumSource(DataRate::class)
    fun `stop accepted but not executed is NotVerified and raises the critical alert`(rate: DataRate) = runTest {
        val rig = Rig(this, rate)
        rig.runAt(4.0)
        rig.sim.stopFault = true // OK on the Control Point, nothing on the belt: the documented iConsole state
        val before = rig.now
        val r = rig.controller.stop()
        assertTrue(r is CommandResult.NotVerified, "result: $r")
        assertTrue(rig.now - before in 2_000..5_000, "the alert must fire within ~3 s, not later: ${rig.now - before} ms")
        assertEquals(listOf(TreadmillEvent.CommandNotExecuted("stop")), rig.events)
        assertTrue(rig.sim.isBeltMoving)
        // The controller still knows the belt is running
        assertEquals(BeltStatus.Running, rig.controller.state.value.status)
        assertEquals(4.0, rig.controller.state.value.speedKmh, 1e-9)
    }

    @ParameterizedTest(name = "{displayName} [{0}]")
    @EnumSource(DataRate::class)
    fun `stop with status 02 but the speed not decreasing is NotVerified and the state goes back to Running`(rate: DataRate) = runTest {
        val rig = Rig(this, rate)
        rig.runAt(4.0)
        rig.sim.stopStatusOnlyFault = true
        val r = rig.controller.stop()
        assertTrue(r is CommandResult.NotVerified, "result: $r")
        assertTrue((r as CommandResult.NotVerified).reason.contains("not decreasing"), r.reason)
        assertEquals(listOf(TreadmillEvent.CommandNotExecuted("stop")), rig.events)
        delay(3_000)
        // The data wins: the belt is still running, the UI cannot say Idle
        assertEquals(BeltStatus.Running, rig.controller.state.value.status)
    }

    @ParameterizedTest(name = "{displayName} [{0}]")
    @EnumSource(DataRate::class)
    fun `stop rejected by the treadmill is Rejected with an alert`(rate: DataRate) = runTest {
        val rig = Rig(this, rate)
        rig.runAt(2.0)
        rig.sim.forcedResults[0x08] = ControlPointResult.OperationFailed
        val r = rig.controller.stop()
        assertTrue(r is CommandResult.Rejected, "result: $r")
        assertEquals(listOf(TreadmillEvent.CommandNotExecuted("stop")), rig.events)
    }

    @ParameterizedTest(name = "{displayName} [{0}]")
    @EnumSource(DataRate::class)
    fun `stop during the start countdown is verified and cancels the start`(rate: DataRate) = runTest {
        val rig = Rig(this, rate)
        rig.connect()
        val start = async { rig.controller.start() }
        delay(1_000) // in the middle of the countdown
        assertEquals(BeltStatus.Starting, rig.controller.state.value.status)
        val r = rig.controller.stop()
        assertEquals(CommandResult.Success, r)
        val startResult = start.await()
        assertTrue(startResult is CommandResult.NotVerified, "the start must be interrupted: $startResult")
        delay(10_000)
        assertEquals(BeltStatus.Idle, rig.controller.state.value.status)
        assertEquals(0.0, rig.sim.currentSpeedKmh)
        // An interruption by a Stop is not an alert: only what is not carried out raises one
        assertTrue(rig.events.none { it is TreadmillEvent.CommandNotExecuted && it.command == "stop" })
    }

    @ParameterizedTest(name = "{displayName} [{0}]")
    @EnumSource(DataRate::class)
    fun `stopping with the console's physical button is reflected without alerts`(rate: DataRate) = runTest {
        val rig = Rig(this, rate)
        rig.runAt(3.0, incline = 2.0)
        rig.sim.physicalStop()
        delay(10_000)
        val s = rig.controller.state.value
        assertEquals(BeltStatus.Idle, s.status)
        assertEquals(0.0, s.speedKmh)
        assertTrue(rig.events.isEmpty(), "${rig.events}")
    }

    @ParameterizedTest(name = "{displayName} [{0}]")
    @EnumSource(DataRate::class)
    fun `disconnecting with the belt running stops it before closing BLE`(rate: DataRate) = runTest {
        val rig = Rig(this, rate)
        rig.runAt(2.0)
        rig.controller.disconnect()
        assertEquals(ConnectionState.Disconnected, rig.controller.connection.value)
        assertTrue(rig.commandsHex.contains("0801"), "a Stop must be sent before closing: ${rig.commandsHex}")
        assertEquals(0.0, rig.sim.currentSpeedKmh)
        assertNull(rig.controller.capabilities.value)
        // Requested disconnection: neither ConnectionLost nor reconnection
        assertTrue(rig.events.isEmpty(), "${rig.events}")
        delay(30_000)
        assertEquals(ConnectionState.Disconnected, rig.controller.connection.value)
        assertEquals(1, rig.sim.connectCount)
    }

    // ------------------------------------------------------------------ safety key

    @ParameterizedTest(name = "{displayName} [{0}]")
    @EnumSource(DataRate::class)
    fun `key removed and put back`(rate: DataRate) = runTest {
        val rig = Rig(this, rate)
        rig.runAt(2.0, incline = 5.0)
        rig.sim.removeSafetyKey()
        delay(2_000)
        var s = rig.controller.state.value
        assertEquals(BeltStatus.SafetyKeyRemoved, s.status)
        assertEquals(0.0, s.speedKmh)
        assertEquals(5.0, s.inclinePercent, "the platform stays inclined")
        assertEquals(listOf<TreadmillEvent>(TreadmillEvent.SafetyKeyRemoved), rig.events)

        // With the key removed nothing starts and nothing is sent to the console showing E-07
        val sentBefore = rig.commandsHex.size
        assertTrue(rig.controller.start() is CommandResult.NotAllowed)
        assertEquals(CommandResult.Success, rig.controller.stop())
        assertTrue(rig.controller.setSpeed(3.0) is CommandResult.NotAllowed)
        assertEquals(sentBefore, rig.commandsHex.size)
        delay(10_000)
        assertEquals(BeltStatus.SafetyKeyRemoved, rig.controller.state.value.status)
        assertEquals(listOf<TreadmillEvent>(TreadmillEvent.SafetyKeyRemoved), rig.events, "no repetitions")

        rig.sim.restoreSafetyKey()
        delay(3_000)
        s = rig.controller.state.value
        assertEquals(BeltStatus.Idle, s.status)
        assertEquals(0.0, s.inclinePercent, "when the key is put back the platform goes down to 0")
        assertEquals(0, s.elapsedSeconds)
        assertEquals(listOf(TreadmillEvent.SafetyKeyRemoved, TreadmillEvent.SafetyKeyRestored), rig.events)

        // And it can start again
        assertEquals(CommandResult.Success, rig.controller.start())
    }

    // ------------------------------------------------------------------ disconnection

    @ParameterizedTest(name = "{displayName} [{0}]")
    @EnumSource(DataRate::class)
    fun `losing the connection during the session alerts, reconnects and discards the first stale packet`(rate: DataRate) = runTest {
        val rig = Rig(this, rate)
        rig.runAt(5.0, incline = 3.0)
        rig.sim.dropConnection()
        delay(100)
        assertEquals(listOf<TreadmillEvent>(TreadmillEvent.ConnectionLost), rig.events)
        assertTrue(rig.controller.connection.value is ConnectionState.Reconnecting, "${rig.controller.connection.value}")
        assertNull(rig.controller.capabilities.value)
        assertEquals(CommandResult.NotConnected, rig.controller.stop())

        delay(5_000)
        assertTrue(rig.connected, "it must have reconnected: ${rig.controller.connection.value}")
        assertEquals(2, rig.sim.connectCount)
        // The first packet after reconnecting is stale data (5 km/h): it is discarded and not shown
        val stale = rig.samples.filter { it.discarded }.last()
        assertEquals(5.0, stale.sample.speedKmh, "the simulator resends the last packet from before the drop")
        val s = rig.controller.state.value
        assertEquals(BeltStatus.Idle, s.status)
        assertEquals(0.0, s.speedKmh)
        assertEquals(0.0, s.inclinePercent)
        // Request Control again on reconnect
        assertEquals(2, rig.commandsHex.count { it == "00" })
        // A single alert, and it can start again
        assertEquals(1, rig.events.size)
        assertEquals(CommandResult.Success, rig.controller.start())
    }

    @ParameterizedTest(name = "{displayName} [{0}]")
    @EnumSource(DataRate::class)
    fun `if the treadmill does not answer it keeps retrying until it reconnects`(rate: DataRate) = runTest {
        val rig = Rig(this, rate)
        rig.runAt(2.0)
        rig.sim.refuseConnections = true
        rig.sim.dropConnection()
        delay(30_000)
        val c = rig.controller.connection.value
        assertTrue(c is ConnectionState.Reconnecting && c.attempt >= 4, "$c")
        assertEquals(1, rig.events.size, "ConnectionLost only once")
        rig.sim.refuseConnections = false
        delay(10_000)
        assertTrue(rig.connected)
    }

    @ParameterizedTest(name = "{displayName} [{0}]")
    @EnumSource(DataRate::class)
    fun `losing the connection while Idle is not a session alert but it does reconnect`(rate: DataRate) = runTest {
        val rig = Rig(this, rate)
        rig.connect()
        rig.sim.dropConnection()
        delay(6_000)
        assertTrue(rig.events.isEmpty(), "${rig.events}")
        assertTrue(rig.connected)
    }

    // ------------------------------------------------------------------ frozen data

    @ParameterizedTest(name = "{displayName} [{0}]")
    @EnumSource(DataRate::class)
    fun `data that stops arriving with the connection open alerts only once`(rate: DataRate) = runTest {
        val rig = Rig(this, rate)
        rig.runAt(3.0)
        rig.sim.freezeData(com.treadz.console.treadmill.simulator.SimulatedTreadmill.FreezeMode.Silent)
        delay(4_000)
        assertTrue(rig.events.isEmpty(), "the deadline has not passed yet")
        delay(4_000)
        assertEquals(listOf<TreadmillEvent>(TreadmillEvent.DataStalled), rig.events)
        delay(20_000)
        assertEquals(1, rig.events.size, "it does not repeat")
        assertTrue(rig.connected)
        // The data comes back and, if it freezes again, it alerts again
        rig.sim.freezeData(null)
        delay(3_000)
        rig.sim.freezeData(com.treadz.console.treadmill.simulator.SimulatedTreadmill.FreezeMode.Silent)
        delay(8_000)
        assertEquals(2, rig.eventsOf(TreadmillEvent.DataStalled::class).size)
    }

    @ParameterizedTest(name = "{displayName} [{0}]")
    @EnumSource(DataRate::class)
    fun `packets that keep arriving but are always the same with the belt running are frozen data too`(rate: DataRate) = runTest {
        val rig = Rig(this, rate)
        rig.runAt(3.0)
        rig.sim.freezeData(com.treadz.console.treadmill.simulator.SimulatedTreadmill.FreezeMode.Repeat)
        delay(10_000)
        assertEquals(listOf<TreadmillEvent>(TreadmillEvent.DataStalled), rig.events)
    }

    @ParameterizedTest(name = "{displayName} [{0}]")
    @EnumSource(DataRate::class)
    fun `without data during a stop, the Stop is not considered verified`(rate: DataRate) = runTest {
        val rig = Rig(this, rate)
        rig.runAt(6.0)
        val stop = async { rig.controller.stop() }
        delay(500)
        rig.sim.freezeData(com.treadz.console.treadmill.simulator.SimulatedTreadmill.FreezeMode.Silent)
        val r = stop.await()
        assertTrue(r is CommandResult.NotVerified, "result: $r")
        assertTrue(rig.events.contains(TreadmillEvent.CommandNotExecuted("stop")))
    }

    // ------------------------------------------------------------------ manual changes

    @ParameterizedTest(name = "{displayName} [{0}]")
    @EnumSource(DataRate::class)
    fun `speed changes with the physical buttons are detected when the ramp settles`(rate: DataRate) = runTest {
        val rig = Rig(this, rate)
        rig.runAt(3.0)
        rig.sim.physicalSetSpeed(6.0)
        delay(2_000)
        assertTrue(rig.events.isEmpty(), "still ramping: ${rig.events}")
        delay(6_000)
        assertEquals(listOf<TreadmillEvent>(TreadmillEvent.ManualChange(speedKmh = 6.0, inclinePercent = null)), rig.events)
        assertEquals(6.0, rig.controller.state.value.targetSpeedKmh)
        // And from then on the new speed is the baseline: it does not repeat
        delay(10_000)
        assertEquals(1, rig.events.size)
    }

    @ParameterizedTest(name = "{displayName} [{0}]")
    @EnumSource(DataRate::class)
    fun `incline changes with the physical buttons are detected instantly`(rate: DataRate) = runTest {
        val rig = Rig(this, rate)
        rig.runAt(3.0, incline = 2.0)
        rig.sim.physicalSetIncline(7.0)
        delay(2_500)
        assertEquals(listOf<TreadmillEvent>(TreadmillEvent.ManualChange(speedKmh = null, inclinePercent = 7.0)), rig.events)
        assertEquals(7.0, rig.controller.state.value.inclinePercent)
    }

    @ParameterizedTest(name = "{displayName} [{0}]")
    @EnumSource(DataRate::class)
    fun `after a manual change the pause resumes with the console values`(rate: DataRate) = runTest {
        val rig = Rig(this, rate)
        rig.runAt(3.0, incline = 2.0)
        rig.sim.physicalSetIncline(6.0)
        delay(2_500)
        assertEquals(CommandResult.Success, rig.controller.pause())
        assertEquals(CommandResult.Success, rig.controller.resume())
        delay(10_000)
        assertEquals(6.0, rig.controller.state.value.inclinePercent, 1e-9)
    }

    // ------------------------------------------------------------------ misc

    @ParameterizedTest(name = "{displayName} [{0}]")
    @EnumSource(DataRate::class)
    fun `time and distance targets are never sent`(rate: DataRate) = runTest {
        val rig = Rig(this, rate)
        rig.runAt(4.0, incline = 3.0)
        rig.controller.pause()
        rig.controller.resume()
        delay(10_000)
        rig.controller.stop()
        assertTrue(rig.sim.forbiddenCommands.isEmpty())
        assertTrue(rig.sim.receivedCommands.none { it[0].toInt() == 0x0C || it[0].toInt() == 0x0D })
    }

    @ParameterizedTest(name = "{displayName} [{0}]")
    @EnumSource(DataRate::class)
    fun `only FTMS characteristics are used and the iConsole channel does not exist`(rate: DataRate) = runTest {
        val rig = Rig(this, rate)
        rig.runAt(2.0)
        rig.controller.stop()
        val used = rig.trace.filterIsInstance<TraceRecord.RawSent>().map { it.characteristic.shortName }.toSet() +
            rig.trace.filterIsInstance<TraceRecord.RawReceived>().map { it.characteristic.shortName }.toSet() +
            rig.trace.filterIsInstance<TraceRecord.RawRead>().map { it.characteristic.shortName }.toSet()
        assertTrue(used.none { it.startsWith("fff") }, "$used")
    }

    @ParameterizedTest(name = "{displayName} [{0}]")
    @EnumSource(DataRate::class)
    fun `a failing recorder does not affect treadmill control`(rate: DataRate) = runTest {
        val rig = Rig(this, rate)
        rig.storage.failOnCreate = java.io.IOException("no space left")
        rig.runAt(3.0)
        assertEquals(CommandResult.Success, rig.controller.stop())
        assertTrue(rig.recorder.status.value is com.treadz.console.treadmill.RecordingStatus.Failed)
    }
}
