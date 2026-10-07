package com.treadz.console.treadmill.control

import com.treadz.console.treadmill.CommandResult
import com.treadz.console.treadmill.FailureCode
import com.treadz.console.treadmill.TreadmillEvent
import com.treadz.console.treadmill.simulator.SimulatedTreadmill.DataRate
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * The treadmill speed step is 0.1 km/h and the reported speed is always a multiple of it: a single step is a
 * different speed, both for verifying commands and for detecting changes made with the console buttons.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SpeedStepTest {

    private fun Rig.firstSampleAt(kmh: Double, after: Long = 0L) =
        samples.first { !it.discarded && it.atMillis > after && abs(it.sample.speedKmh - kmh) < 1e-9 }

    @ParameterizedTest(name = "{displayName} [{0}]")
    @EnumSource(DataRate::class)
    fun `a +0_1 command is verified only once the data shows the new speed`(rate: DataRate) = runTest {
        val rig = Rig(this, rate)
        rig.runAt(3.0)
        val sentAt = T0 + rig.now
        assertEquals(CommandResult.Success, rig.controller.setSpeed(3.1))
        val finishedAt = T0 + rig.now
        val shown = rig.firstSampleAt(3.1, after = sentAt)
        assertTrue(finishedAt >= shown.atMillis, "verified at $finishedAt, before the data showed 3.1 at ${shown.atMillis}")
        assertEquals(3.1, rig.controller.state.value.targetSpeedKmh)
        assertEquals(3.1, rig.controller.state.value.speedKmh, 1e-9)
        assertTrue(rig.events.isEmpty(), "${rig.events}")
    }

    @ParameterizedTest(name = "{displayName} [{0}]")
    @EnumSource(DataRate::class)
    fun `a -0_1 command is verified only once the data shows the new speed`(rate: DataRate) = runTest {
        val rig = Rig(this, rate)
        rig.runAt(3.0)
        val sentAt = T0 + rig.now
        assertEquals(CommandResult.Success, rig.controller.setSpeed(2.9))
        val finishedAt = T0 + rig.now
        val shown = rig.firstSampleAt(2.9, after = sentAt)
        assertTrue(finishedAt >= shown.atMillis, "verified at $finishedAt, before the data showed 2.9 at ${shown.atMillis}")
        assertEquals(2.9, rig.controller.state.value.targetSpeedKmh)
        assertTrue(rig.events.isEmpty(), "${rig.events}")
    }

    @ParameterizedTest(name = "{displayName} [{0}]")
    @EnumSource(DataRate::class)
    fun `a +0_1 or -0_1 command the treadmill ignores is not verified`(rate: DataRate) = runTest {
        val rig = Rig(this, rate)
        rig.runAt(3.0)
        rig.sim.speedFault = true
        for (target in listOf(3.1, 2.9)) {
            val before = rig.now
            val r = rig.controller.setSpeed(target)
            assertTrue(r is CommandResult.NotVerified && r.code == FailureCode.TargetNotReached, "target $target: $r")
            assertTrue(rig.now - before >= 5_000, "verification window: ${rig.now - before} ms")
        }
        assertEquals(3.0, rig.controller.state.value.targetSpeedKmh, "the target does not change if it is not verified")
        assertEquals(
            listOf<TreadmillEvent>(TreadmillEvent.CommandNotExecuted("setSpeed")),
            rig.events.distinct(),
        )
    }

    @ParameterizedTest(name = "{displayName} [{0}]")
    @EnumSource(DataRate::class)
    fun `a command to the speed the belt already has is verified by the data`(rate: DataRate) = runTest {
        val rig = Rig(this, rate)
        rig.runAt(3.0)
        assertEquals(CommandResult.Success, rig.controller.setSpeed(3.0))
        assertTrue(rig.events.isEmpty(), "${rig.events}")
    }

    @ParameterizedTest(name = "{displayName} [{0}]")
    @EnumSource(DataRate::class)
    fun `a single 0_1 km_h change with the console buttons is exactly one manual change`(rate: DataRate) = runTest {
        val rig = Rig(this, rate)
        rig.runAt(3.0)
        rig.sim.physicalSetSpeed(3.1)
        delay(6_000)
        assertEquals(listOf<TreadmillEvent>(TreadmillEvent.ManualChange(speedKmh = 3.1, inclinePercent = null)), rig.events)
        assertEquals(3.1, rig.controller.state.value.targetSpeedKmh)
        delay(10_000)
        assertEquals(1, rig.events.size, "it does not repeat")

        rig.sim.physicalSetSpeed(3.0)
        delay(6_000)
        assertEquals(
            listOf<TreadmillEvent>(
                TreadmillEvent.ManualChange(3.1, null),
                TreadmillEvent.ManualChange(3.0, null),
            ),
            rig.events,
        )
    }

    @ParameterizedTest(name = "{displayName} [{0}]")
    @EnumSource(DataRate::class)
    fun `a console change of 0_1 right after a command from the app is detected too`(rate: DataRate) = runTest {
        val rig = Rig(this, rate)
        rig.runAt(3.0)
        assertEquals(CommandResult.Success, rig.controller.setSpeed(4.0))
        delay(5_000) // the ramp is over
        rig.sim.physicalSetSpeed(4.1)
        delay(6_000)
        assertEquals(listOf<TreadmillEvent>(TreadmillEvent.ManualChange(4.1, null)), rig.events)
    }

    @ParameterizedTest(name = "{displayName} [{0}]")
    @EnumSource(DataRate::class)
    fun `no manual change is reported during the start ramp or the resume ramp`(rate: DataRate) = runTest {
        val rig = Rig(this, rate)
        rig.runAt(9.0, incline = 4.0)
        assertEquals(CommandResult.Success, rig.controller.pause())
        assertEquals(CommandResult.Success, rig.controller.resume())
        delay(30_000)
        assertEquals(9.0, rig.controller.state.value.speedKmh, 1e-9)
        assertEquals(4.0, rig.controller.state.value.inclinePercent, 1e-9)
        assertTrue(rig.events.isEmpty(), "${rig.events}")
    }

    @ParameterizedTest(name = "{displayName} [{0}]")
    @EnumSource(DataRate::class)
    fun `the simulated treadmill only reports multiples of 0_1 km_h whatever the ramp`(rate: DataRate) = runTest {
        val rig = Rig(this, rate)
        rig.sim.rampKmhPerSecond = 0.45
        rig.sim.decelKmhPerSecond = 0.35
        rig.connectAndStart()
        assertEquals(CommandResult.Success, rig.controller.setSpeed(7.3))
        delay(20_000)
        rig.sim.physicalSetSpeed(5.14)
        delay(15_000)
        assertEquals(5.1, rig.controller.state.value.speedKmh, 1e-9)
        assertEquals(CommandResult.Success, rig.controller.stop())
        val off = rig.samples.filter { !it.discarded }.map { it.sample.speedKmh }
            .filter { abs(it * 10 - (it * 10).roundToInt()) > 1e-6 }
        assertTrue(off.isEmpty(), "speeds off the 0.1 km/h grid: ${off.distinct()}")
        assertTrue(rig.samples.any { abs(it.sample.speedKmh - 7.3) < 1e-9 })
    }
}
