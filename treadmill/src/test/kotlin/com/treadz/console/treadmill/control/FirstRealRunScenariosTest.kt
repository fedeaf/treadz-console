package com.treadz.console.treadmill.control

import com.treadz.console.treadmill.BeltStatus
import com.treadz.console.treadmill.CommandResult
import com.treadz.console.treadmill.FailureCode
import com.treadz.console.treadmill.TreadmillEvent
import com.treadz.console.treadmill.simulator.SimulatedTreadmill.DataRate
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.EnumSource
import org.junit.jupiter.params.provider.MethodSource
import kotlin.math.abs

/**
 * The checks planned for the first run of stop, pause, resume, speed and incline on the real treadmill (empty belt,
 * 18 km/h and 18 % incline), run against the simulator at both data rates and with the deceleration rates and packet
 * timing jitter seen on the real one (0.4-0.5 km/h per second, packets every 390-590 ms).
 *
 * The incline reported in Treadmill Data is the target value and changes instantly: how long the platform really
 * takes to go up or down cannot be measured from the data, and nothing here (or in the controller) depends on it.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FirstRealRunScenariosTest {

    /** A data rate plus how the simulated belt accelerates and decelerates. */
    data class Profile(val rate: DataRate, val rampKmhPerSecond: Double, val decelKmhPerSecond: Double, val jitterMs: Long) {
        override fun toString() = "$rate ramp=$rampKmhPerSecond decel=$decelKmhPerSecond jitter=$jitterMs"
    }

    companion object {
        @JvmStatic
        fun profiles(): List<Profile> = DataRate.entries.flatMap { rate ->
            listOf(
                Profile(rate, 0.5, 0.5, 0),
                Profile(rate, 0.5, 0.4, 100),
                Profile(rate, 0.5, 0.45, 100),
            )
        }
    }

    private fun TestScope.rig(p: Profile): Rig = Rig(this, p.rate).also {
        it.sim.rampKmhPerSecond = p.rampKmhPerSecond
        it.sim.decelKmhPerSecond = p.decelKmhPerSecond
        it.sim.packetJitterMs = p.jitterMs
    }

    /** Runs the belt at [kmh] and [incline] and waits for the whole ramp (the helper in [Rig] assumes 0.5 km/h per s). */
    private suspend fun Rig.runAtSlow(kmh: Double, incline: Double = 0.0) {
        connectAndStart()
        assertEquals(CommandResult.Success, controller.setSpeed(kmh))
        if (incline > 0) assertEquals(CommandResult.Success, controller.setIncline(incline))
        delay(((kmh / 0.5) + 5).toLong() * 1000)
        assertEquals(kmh, controller.state.value.speedKmh, 1e-9)
        assertEquals(incline, controller.state.value.inclinePercent, 1e-9)
    }

    private fun Rig.alerts() = events.filterNot { it is TreadmillEvent.ManualChange }

    // ------------------------------------------------------------------ stop

    @ParameterizedTest(name = "{displayName} [{0}]")
    @MethodSource("profiles")
    fun `stop from 18 km_h is verified on the whole deceleration with no false alerts`(p: Profile) = runTest {
        val rig = rig(p)
        rig.runAtSlow(18.0)
        assertTrue(rig.events.isEmpty(), "before the stop: ${rig.events}")
        val sentAt = T0 + rig.now
        val r = rig.controller.stop()
        val took = rig.now - (sentAt - T0)
        assertEquals(CommandResult.Success, r)
        val expected = 18.0 / p.decelKmhPerSecond * 1000
        assertTrue(took in (expected - 3_000).toLong()..(expected + 6_000).toLong(), "stop took $took ms, deceleration ~${expected.toLong()} ms")
        assertEquals(BeltStatus.Idle, rig.controller.state.value.status)
        assertEquals(0.0, rig.sim.currentSpeedKmh)
        assertTrue(rig.events.isEmpty(), "no StillMoving/StopNotConfirmed/CommandNotExecuted expected: ${rig.events}")
        assertEquals(1, rig.commandsHex.count { it == "0801" })

        // The thresholds have margin: the longest time the speed went without decreasing is far below the limit.
        val changes = rig.samples.filter { it.atMillis > sentAt && !it.discarded }
            .fold(mutableListOf<TraceRecord.Sample>()) { acc, s -> if (acc.lastOrNull()?.sample?.speedKmh != s.sample.speedKmh) acc += s; acc }
        val longestHold = changes.zipWithNext().maxOf { (a, b) -> b.atMillis - a.atMillis }
        assertTrue(longestHold <= ControllerConfig().stopNotDecreasingMs / 2, "longest time without a decrease: $longestHold ms")
        assertTrue(changes.zipWithNext().all { (a, b) -> b.sample.speedKmh < a.sample.speedKmh }, "the speed must decrease on every change")
    }

    @ParameterizedTest(name = "{displayName} [{0}]")
    @MethodSource("profiles")
    fun `stop with 18 percent incline resets the incline and ends Idle`(p: Profile) = runTest {
        val rig = rig(p)
        rig.runAtSlow(6.0, incline = 18.0)
        assertEquals(18.0, rig.sim.currentInclinePercent)
        assertEquals(CommandResult.Success, rig.controller.stop())
        delay(3_000)
        val s = rig.controller.state.value
        assertEquals(BeltStatus.Idle, s.status)
        assertEquals(0.0, s.speedKmh)
        assertEquals(0.0, s.inclinePercent)
        assertEquals(0, s.elapsedSeconds)
        assertTrue(rig.events.isEmpty(), "${rig.events}")
        // Nothing can be set while stopped, and a new start begins from the minimum
        assertTrue(rig.controller.setIncline(5.0) is CommandResult.NotAllowed)
        assertEquals(CommandResult.Success, rig.controller.start())
        assertEquals(0.5, rig.controller.state.value.speedKmh, 1e-9)
        assertEquals(0.0, rig.controller.state.value.inclinePercent)
    }

    // ------------------------------------------------------------------ pause and resume

    @ParameterizedTest(name = "{displayName} [{0}]")
    @MethodSource("profiles")
    fun `pause at 18 km_h and 18 percent, then resume re-applies both only once the belt moves`(p: Profile) = runTest {
        val rig = rig(p)
        rig.runAtSlow(18.0, incline = 18.0)
        val distance = rig.controller.state.value.distanceMeters

        assertEquals(CommandResult.Success, rig.controller.pause())
        var s = rig.controller.state.value
        assertEquals(BeltStatus.Paused, s.status)
        assertEquals(0.0, s.speedKmh)
        assertEquals(0.0, s.inclinePercent, "pausing lowers the incline to 0")
        assertEquals(0.0, rig.sim.currentInclinePercent)
        assertTrue(s.distanceMeters >= distance && s.elapsedSeconds > 0, "pausing keeps time and distance")
        delay(5_000)
        assertEquals(BeltStatus.Paused, rig.controller.state.value.status)

        val resumedAt = T0 + rig.now
        assertEquals(CommandResult.Success, rig.controller.resume())
        // Speed 18.0 = 1800 = 0x0708 and incline 18 % = 180 = 0x00b4, after the start command and in that order
        assertEquals(listOf("0802", "07", "020807", "03b400"), rig.commandsHex.takeLast(4))
        val resumeWrite = rig.trace.filterIsInstance<TraceRecord.RawSent>().last { it.value.size == 1 && it.value[0].toInt() == 7 }
        val firstMoving = rig.samples.first { it.atMillis > resumeWrite.atMillis && it.sample.speedKmh > 0 && !it.discarded }
        val reSpeed = rig.trace.filterIsInstance<TraceRecord.RawSent>().last { it.value[0].toInt() == 2 }
        assertTrue(reSpeed.atMillis >= firstMoving.atMillis, "speed re-sent before the belt moved")
        assertTrue(resumedAt <= reSpeed.atMillis)

        delay(60_000)
        s = rig.controller.state.value
        assertEquals(BeltStatus.Running, s.status)
        assertEquals(18.0, s.speedKmh, 1e-9)
        assertEquals(18.0, s.inclinePercent, 1e-9)
        assertEquals(18.0, s.targetSpeedKmh)
        assertTrue(rig.events.isEmpty(), "no alerts or false manual changes while the belt ramps back to 18 km/h: ${rig.events}")
        assertTrue(rig.sim.forbiddenCommands.isEmpty())
    }

    // ------------------------------------------------------------------ incline

    @ParameterizedTest(name = "{displayName} [{0}]")
    @MethodSource("profiles")
    fun `incline 0 to 18 and 18 to 0 while running`(p: Profile) = runTest {
        val rig = rig(p)
        rig.runAtSlow(5.0)
        assertEquals(CommandResult.Success, rig.controller.setIncline(18.0))
        assertEquals(18.0, rig.controller.state.value.inclinePercent)
        assertEquals(18.0, rig.sim.currentInclinePercent)
        delay(5_000)
        assertEquals(CommandResult.Success, rig.controller.setIncline(0.0))
        assertEquals(0.0, rig.controller.state.value.inclinePercent)
        assertEquals(0.0, rig.sim.currentInclinePercent)
        // One percent steps are verified by the data too
        assertEquals(CommandResult.Success, rig.controller.setIncline(1.0))
        assertEquals(CommandResult.Success, rig.controller.setIncline(0.0))
        delay(5_000)
        assertEquals(listOf("03b400", "030000", "030a00", "030000"), rig.commandsHex.filter { it.startsWith("03") })
        assertEquals(BeltStatus.Running, rig.controller.state.value.status)
        assertEquals(5.0, rig.controller.state.value.speedKmh, 1e-9)
        assertTrue(rig.events.isEmpty(), "${rig.events}")
    }

    @ParameterizedTest(name = "{displayName} [{0}]")
    @MethodSource("profiles")
    fun `speed to 18 km_h and back while running is verified without false manual changes`(p: Profile) = runTest {
        val rig = rig(p)
        rig.runAtSlow(4.0)
        assertEquals(CommandResult.Success, rig.controller.setSpeed(18.0))
        delay(40_000)
        assertEquals(18.0, rig.controller.state.value.speedKmh, 1e-9)
        assertEquals(CommandResult.Success, rig.controller.setSpeed(4.0))
        delay(45_000)
        assertEquals(4.0, rig.controller.state.value.speedKmh, 1e-9)
        assertTrue(rig.events.isEmpty(), "${rig.events}")
    }

    // ------------------------------------------------------------------ queued and repeated commands

    @ParameterizedTest(name = "{displayName} [{0}]")
    @EnumSource(DataRate::class)
    fun `ten speed taps queued at once are executed in order, each verified`(rate: DataRate) = runTest {
        val rig = Rig(this, rate)
        rig.runAt(3.0)
        val results = (1..10).map { i -> async { rig.controller.setSpeed(3.0 + 0.1 * i) } }.awaitAll()
        assertTrue(results.all { it == CommandResult.Success }, "$results")
        delay(5_000)
        assertEquals(4.0, rig.controller.state.value.targetSpeedKmh)
        assertEquals(4.0, rig.controller.state.value.speedKmh, 1e-9)
        val sent = rig.commandsHex.filter { it.startsWith("02") }
        assertEquals((0..10).map { "02" + "%02x%02x".format((300 + 10 * it) and 0xFF, (300 + 10 * it) shr 8) }, sent)
        assertTrue(rig.events.isEmpty(), "${rig.events}")
    }

    @ParameterizedTest(name = "{displayName} [{0}]")
    @EnumSource(DataRate::class)
    fun `a stop jumps the queue of commands that are not being verified well`(rate: DataRate) = runTest {
        val rig = Rig(this, rate)
        rig.runAt(6.0)
        rig.sim.speedFault = true // every speed command would take 5 s to be reported
        val taps = (1..5).map { i -> async { rig.controller.setSpeed(6.0 + 0.1 * i) } }
        delay(1_000)
        val before = rig.now
        val stop = rig.controller.stop()
        val took = rig.now - before
        assertEquals(CommandResult.Success, stop)
        // 6 km/h at 0.5 km/h per second is ~12 s: waiting for five 5 s verifications first would add ~25 s
        assertTrue(took in 9_000..17_000, "stop took $took ms")
        val results = taps.awaitAll()
        assertTrue(results.all { it is CommandResult.NotVerified && it.code == FailureCode.Preempted }, "$results")
        // Only the speed command that was already on the wire was sent
        assertEquals(1, rig.commandsHex.count { it.startsWith("02") } - 1)
        assertTrue(rig.alerts().isEmpty(), "being interrupted by a stop is not a failed command: ${rig.events}")
        assertEquals(BeltStatus.Idle, rig.controller.state.value.status)
    }

    @ParameterizedTest(name = "{displayName} [{0}]")
    @EnumSource(DataRate::class)
    fun `two stops at once send one command and share the result`(rate: DataRate) = runTest {
        val rig = Rig(this, rate)
        rig.runAt(10.0)
        val a = async { rig.controller.stop() }
        val b = async { rig.controller.stop() }
        delay(2_000)
        val c = async { rig.controller.stop() } // a third tap while the belt is still decelerating
        assertEquals(listOf(CommandResult.Success, CommandResult.Success, CommandResult.Success), listOf(a.await(), b.await(), c.await()))
        assertEquals(1, rig.commandsHex.count { it == "0801" })
        assertTrue(rig.events.isEmpty(), "${rig.events}")
        assertEquals(BeltStatus.Idle, rig.controller.state.value.status)
    }

    @ParameterizedTest(name = "{displayName} [{0}]")
    @EnumSource(DataRate::class)
    fun `two pauses at once send one command`(rate: DataRate) = runTest {
        val rig = Rig(this, rate)
        rig.runAt(6.0)
        val a = async { rig.controller.pause() }
        val b = async { rig.controller.pause() }
        assertEquals(CommandResult.Success, a.await())
        assertEquals(CommandResult.Success, b.await())
        assertEquals(1, rig.commandsHex.count { it == "0802" })
        assertEquals(BeltStatus.Paused, rig.controller.state.value.status)
    }

    @ParameterizedTest(name = "{displayName} [{0}]")
    @EnumSource(DataRate::class)
    fun `a failed stop reported twice is shared and a later tap tries again`(rate: DataRate) = runTest {
        val rig = Rig(this, rate)
        rig.runAt(4.0)
        rig.sim.stopFault = true
        val a = async { rig.controller.stop() }
        val b = async { rig.controller.stop() }
        val ra = a.await()
        assertTrue(ra is CommandResult.NotVerified, "$ra")
        assertEquals(ra, b.await())
        assertEquals(1, rig.commandsHex.count { it == "0801" })
        assertEquals(listOf<TreadmillEvent>(TreadmillEvent.CommandNotExecuted("stop")), rig.events)
        assertEquals(BeltStatus.Running, rig.controller.state.value.status)

        // The user taps again once the treadmill works
        rig.sim.stopFault = false
        assertEquals(CommandResult.Success, rig.controller.stop())
        assertEquals(2, rig.commandsHex.count { it == "0801" })
        assertEquals(BeltStatus.Idle, rig.controller.state.value.status)
    }

    @ParameterizedTest(name = "{displayName} [{0}]")
    @EnumSource(DataRate::class)
    fun `a stop during a pause that is still decelerating is not kept waiting for it`(rate: DataRate) = runTest {
        val rig = Rig(this, rate)
        rig.runAt(12.0, incline = 3.0)
        val pause = async { rig.controller.pause() }
        delay(4_000) // pausing: ~10 km/h left
        assertTrue(rig.sim.currentSpeedKmh in 8.0..11.0, "${rig.sim.currentSpeedKmh}")
        val before = rig.now
        assertEquals(CommandResult.Success, rig.controller.stop())
        val took = rig.now - before
        assertTrue(took < 24_000, "stop took $took ms")
        val pauseResult = pause.await()
        assertTrue(pauseResult is CommandResult.NotVerified && pauseResult.code == FailureCode.Preempted, "$pauseResult")
        assertEquals(listOf("0802", "0801"), rig.commandsHex.takeLast(2))
        assertEquals(BeltStatus.Idle, rig.controller.state.value.status)
        assertEquals(0.0, rig.sim.currentSpeedKmh)
        assertTrue(rig.events.isEmpty(), "${rig.events}")
    }

    @ParameterizedTest(name = "{displayName} [{0}]")
    @EnumSource(DataRate::class)
    fun `pause then resume taps queued at once end running at the previous targets`(rate: DataRate) = runTest {
        val rig = Rig(this, rate)
        rig.runAt(5.0, incline = 4.0)
        val pause = async { rig.controller.pause() }
        val resume = async { rig.controller.resume() }
        assertEquals(CommandResult.Success, pause.await())
        assertEquals(CommandResult.Success, resume.await())
        delay(20_000)
        val s = rig.controller.state.value
        assertEquals(BeltStatus.Running, s.status)
        assertEquals(5.0, s.speedKmh, 1e-9)
        assertEquals(4.0, s.inclinePercent, 1e-9)
        assertTrue(rig.events.isEmpty(), "${rig.events}")
    }

    @ParameterizedTest(name = "{displayName} [{0}]")
    @EnumSource(DataRate::class)
    fun `a start queued before a stop is dropped`(rate: DataRate) = runTest {
        val rig = Rig(this, rate)
        rig.runAt(3.0)
        rig.sim.speedFault = true
        val slow = async { rig.controller.setSpeed(8.0) } // holds the queue for a while
        delay(500)
        val start = async { rig.controller.start() } // queued behind it (and not valid anyway)
        val stop = async { rig.controller.stop() }
        assertEquals(CommandResult.Success, stop.await())
        assertTrue(slow.await() is CommandResult.NotVerified)
        assertTrue(start.await() is CommandResult.NotVerified || start.await() is CommandResult.NotAllowed)
        assertEquals(BeltStatus.Idle, rig.controller.state.value.status)
        assertTrue(abs(rig.sim.currentSpeedKmh) < 1e-9)
    }
}
