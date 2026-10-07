package com.treadz.console.treadmill.control

import com.treadz.console.treadmill.BeltStatus
import com.treadz.console.treadmill.CommandResult
import com.treadz.console.treadmill.TreadmillEvent
import com.treadz.console.treadmill.simulator.SimulatedTreadmill.DataRate
import com.treadz.console.treadmill.simulator.SimulatedTreadmill.FreezeMode
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource

/**
 * The safety layer thresholds are time-based: they must behave the same at 1 Hz (PC-like) and at 2 Hz with every value
 * repeated (Android-like), where the treadmill values only change once per second.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ControllerTimingTest {

    private fun List<TreadmillEvent>.manualSpeeds() = filterIsInstance<TreadmillEvent.ManualChange>().mapNotNull { it.speedKmh }

    @ParameterizedTest(name = "{displayName} [{0}]")
    @EnumSource(DataRate::class)
    fun `frozen data takes 5 s with the belt running whatever the packet rate`(rate: DataRate) = runTest {
        val rig = Rig(this, rate)
        rig.runAt(3.0)
        rig.sim.freezeData(FreezeMode.Repeat)
        delay(3_500)
        assertTrue(rig.events.isEmpty(), "too early: ${rig.events}")
        delay(3_500)
        assertEquals(listOf<TreadmillEvent>(TreadmillEvent.DataStalled), rig.events)
    }

    @ParameterizedTest(name = "{displayName} [{0}]")
    @EnumSource(DataRate::class)
    fun `the start ramp is not a manual change even when it is slow`(rate: DataRate) = runTest {
        val rig = Rig(this, rate)
        rig.sim.rampKmhPerSecond = 0.1
        rig.connect()
        // The belt takes ~5 s to reach 0.5 km/h in steps of 0.1: the first Treadmill Data values are 0.0, 0.1, 0.2...
        rig.sim.physicalStart()
        delay(40_000)
        val s = rig.controller.state.value
        assertEquals(BeltStatus.Running, s.status)
        assertEquals(0.5, s.speedKmh, 1e-9)
        assertTrue(rig.events.isEmpty(), "${rig.events}")
    }

    @ParameterizedTest(name = "{displayName} [{0}]")
    @EnumSource(DataRate::class)
    fun `after the start ramp the console buttons are detected again`(rate: DataRate) = runTest {
        val rig = Rig(this, rate)
        rig.connectAndStart()
        delay(10_000)
        assertTrue(rig.events.isEmpty(), "${rig.events}")
        rig.sim.physicalSetSpeed(2.0)
        delay(10_000)
        assertEquals(listOf(2.0), rig.events.manualSpeeds())
    }

    @ParameterizedTest(name = "{displayName} [{0}]")
    @EnumSource(DataRate::class)
    fun `each settled speed is one manual change, not one per ramp step`(rate: DataRate) = runTest {
        val rig = Rig(this, rate)
        rig.runAt(2.0)
        rig.sim.physicalSetSpeed(8.0) // ramp of 12 s
        delay(20_000)
        assertEquals(listOf(8.0), rig.events.manualSpeeds())
        rig.sim.physicalSetSpeed(4.0) // ramp of 8 s
        delay(20_000)
        assertEquals(listOf(8.0, 4.0), rig.events.manualSpeeds())
        // The target the console shows follows the last settled value
        assertEquals(4.0, rig.controller.state.value.targetSpeedKmh)
    }

    @ParameterizedTest(name = "{displayName} [{0}]")
    @EnumSource(DataRate::class)
    fun `a button press interrupted halfway is reported once it settles`(rate: DataRate) = runTest {
        val rig = Rig(this, rate)
        rig.runAt(2.0)
        rig.sim.physicalSetSpeed(10.0)
        delay(4_000) // ramping: 2 -> 4 km/h
        assertTrue(rig.events.isEmpty(), "still ramping: ${rig.events}")
        rig.sim.physicalSetSpeed(5.0)
        delay(10_000)
        assertEquals(listOf(5.0), rig.events.manualSpeeds())
    }

    @ParameterizedTest(name = "{displayName} [{0}]")
    @EnumSource(DataRate::class)
    fun `resuming at a high speed is not a manual change while the belt ramps up again`(rate: DataRate) = runTest {
        val rig = Rig(this, rate)
        rig.runAt(12.0, incline = 3.0)
        assertEquals(CommandResult.Success, rig.controller.pause())
        assertEquals(CommandResult.Success, rig.controller.resume())
        delay(40_000)
        assertEquals(12.0, rig.controller.state.value.speedKmh, 1e-9)
        assertTrue(rig.events.isEmpty(), "${rig.events}")
    }

    @ParameterizedTest(name = "{displayName} [{0}]")
    @EnumSource(DataRate::class)
    fun `stopping from the maximum speed is verified on the whole deceleration`(rate: DataRate) = runTest {
        val rig = Rig(this, rate)
        rig.runAt(20.0, incline = 5.0)
        assertEquals(CommandResult.Success, rig.controller.stop())
        assertEquals(BeltStatus.Idle, rig.controller.state.value.status)
        assertTrue(rig.events.isEmpty(), "${rig.events}")
    }

    @ParameterizedTest(name = "{displayName} [{0}]")
    @EnumSource(DataRate::class)
    fun `a slow deceleration after Stop is not mistaken for a belt that does not stop`(rate: DataRate) = runTest {
        val rig = Rig(this, rate)
        rig.sim.rampKmhPerSecond = 0.2
        rig.connectAndStart()
        assertEquals(CommandResult.Success, rig.controller.setSpeed(3.0))
        delay(20_000)
        assertEquals(3.0, rig.controller.state.value.speedKmh, 1e-9)
        assertEquals(CommandResult.Success, rig.controller.stop())
        assertEquals(BeltStatus.Idle, rig.controller.state.value.status)
        assertTrue(rig.events.isEmpty(), "${rig.events}")
    }

    @ParameterizedTest(name = "{displayName} [{0}]")
    @EnumSource(DataRate::class)
    fun `a belt that keeps its speed after a stop status is reported after 3 s whatever the packet rate`(rate: DataRate) = runTest {
        val rig = Rig(this, rate)
        rig.runAt(4.0)
        rig.sim.stopStatusOnlyFault = true
        val before = rig.now
        val r = rig.controller.stop()
        assertTrue(r is CommandResult.NotVerified, "result: $r")
        val took = rig.now - before
        assertTrue(took in 3_000..4_500, "reported after $took ms")
    }

    @ParameterizedTest(name = "{displayName} [{0}]")
    @EnumSource(DataRate::class)
    fun `a speed command that does not move the belt is reported after the approach window`(rate: DataRate) = runTest {
        val rig = Rig(this, rate)
        rig.connectAndStart()
        rig.sim.speedFault = true
        val before = rig.now
        val r = rig.controller.setSpeed(6.0)
        assertTrue(r is CommandResult.NotVerified, "result: $r")
        val took = rig.now - before
        assertTrue(took in 5_000..6_500, "reported after $took ms")
    }
}
