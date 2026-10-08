package com.treadz.console.treadmill.control

import com.treadz.console.treadmill.CommandResult
import com.treadz.console.treadmill.TreadmillEvent
import com.treadz.console.treadmill.simulator.SimulatedTreadmill.DataRate
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import java.util.concurrent.CopyOnWriteArrayList

/**
 * `TreadmillState.targetSpeedKmh` is the target an app command asked for. While the console buttons are changing the
 * speed the target is unknown and is published as `null` until the speed settles.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class TargetSpeedPublishingTest {

    private class Observed(val speed: Double, val target: Double?)

    private fun kotlinx.coroutines.test.TestScope.record(rig: Rig): CopyOnWriteArrayList<Observed> {
        val seen = CopyOnWriteArrayList<Observed>()
        backgroundScope.launch { rig.controller.state.collect { seen += Observed(it.speedKmh, it.targetSpeedKmh) } }
        return seen
    }

    @ParameterizedTest(name = "{displayName} [{0}]")
    @EnumSource(DataRate::class)
    fun `lowering the speed with the console shows no target while the belt slows down`(rate: DataRate) = runTest {
        val rig = Rig(this, rate)
        rig.runAt(6.0)
        assertEquals(6.0, rig.controller.state.value.targetSpeedKmh)
        val seen = record(rig)

        rig.sim.physicalSetSpeed(5.0)
        delay(1_000)
        // The belt has started to slow down: the old target (6) must not be shown any more
        assertTrue(rig.controller.state.value.speedKmh < 6.0)
        assertNull(rig.controller.state.value.targetSpeedKmh)
        delay(10_000)

        assertTrue(seen.none { it.target == 6.0 && it.speed < 6.0 }, "a target of 6 was published while the belt slowed down")
        assertEquals(5.0, rig.controller.state.value.speedKmh, 0.001)
        // Settled: the console's choice is known again, and it was reported once
        assertEquals(5.0, rig.controller.state.value.targetSpeedKmh)
        assertEquals(listOf<TreadmillEvent>(TreadmillEvent.ManualChange(speedKmh = 5.0, inclinePercent = null)), rig.events)
    }

    @ParameterizedTest(name = "{displayName} [{0}]")
    @EnumSource(DataRate::class)
    fun `raising the speed with the console shows no target while the belt speeds up`(rate: DataRate) = runTest {
        val rig = Rig(this, rate)
        rig.runAt(6.0)
        val seen = record(rig)
        rig.sim.physicalSetSpeed(9.0)
        delay(1_500)
        assertNull(rig.controller.state.value.targetSpeedKmh)
        delay(12_000)
        assertTrue(seen.none { it.target == 6.0 && it.speed > 6.0 })
        assertEquals(9.0, rig.controller.state.value.targetSpeedKmh)
        assertEquals(1, rig.events.filterIsInstance<TreadmillEvent.ManualChange>().size)
    }

    @ParameterizedTest(name = "{displayName} [{0}]")
    @EnumSource(DataRate::class)
    fun `a target set by the app stays published during the whole ramp`(rate: DataRate) = runTest {
        val rig = Rig(this, rate)
        rig.runAt(6.0)
        val seen = record(rig)
        assertEquals(CommandResult.Success, rig.controller.setSpeed(10.0))
        delay(12_000)
        assertEquals(10.0, rig.controller.state.value.speedKmh, 0.001)
        // Once the command is verified (the first samples of the ramp) the target is 10 until the end of it
        val climbing = seen.filter { it.speed > 7.0 && it.speed < 10.0 }
        assertTrue(climbing.isNotEmpty() && climbing.all { it.target == 10.0 }, "no gap in the target during the ramp")
        assertTrue(rig.events.isEmpty())
    }

    @ParameterizedTest(name = "{displayName} [{0}]")
    @EnumSource(DataRate::class)
    fun `a new app command after a console change publishes its own target`(rate: DataRate) = runTest {
        val rig = Rig(this, rate)
        rig.runAt(6.0)
        rig.sim.physicalSetSpeed(4.0)
        delay(1_500)
        assertNull(rig.controller.state.value.targetSpeedKmh)
        assertEquals(CommandResult.Success, rig.controller.setSpeed(7.0))
        assertEquals(7.0, rig.controller.state.value.targetSpeedKmh)
        delay(10_000)
        assertEquals(7.0, rig.controller.state.value.speedKmh, 0.001)
        assertEquals(7.0, rig.controller.state.value.targetSpeedKmh)
    }

    @ParameterizedTest(name = "{displayName} [{0}]")
    @EnumSource(DataRate::class)
    fun `an incline changed with the console is reported at once, with no stale value`(rate: DataRate) = runTest {
        val rig = Rig(this, rate)
        rig.runAt(6.0, incline = 3.0)
        val seen = CopyOnWriteArrayList<Double>()
        backgroundScope.launch { rig.controller.state.collect { seen += it.inclinePercent } }
        rig.sim.physicalSetIncline(7.0)
        delay(1_500)
        assertEquals(7.0, rig.controller.state.value.inclinePercent)
        assertTrue(rig.events.contains(TreadmillEvent.ManualChange(speedKmh = null, inclinePercent = 7.0)))
        // The speed target is not affected by an incline change
        assertEquals(6.0, rig.controller.state.value.targetSpeedKmh)
    }

    @ParameterizedTest(name = "{displayName} [{0}]")
    @EnumSource(DataRate::class)
    fun `pausing and stopping clear the unknown target`(rate: DataRate) = runTest {
        val rig = Rig(this, rate)
        rig.runAt(6.0)
        rig.sim.physicalSetSpeed(4.0)
        delay(1_500)
        assertNull(rig.controller.state.value.targetSpeedKmh)
        assertEquals(CommandResult.Success, rig.controller.stop())
        assertNull(rig.controller.state.value.targetSpeedKmh)
        assertEquals(CommandResult.Success, rig.controller.start())
        assertEquals(0.5, rig.controller.state.value.targetSpeedKmh)
    }
}
