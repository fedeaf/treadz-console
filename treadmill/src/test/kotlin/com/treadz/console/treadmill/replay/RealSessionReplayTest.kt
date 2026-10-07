package com.treadz.console.treadmill.replay

import com.treadz.console.treadmill.BeltStatus
import com.treadz.console.treadmill.CommandResult
import com.treadz.console.treadmill.TreadmillEvent
import com.treadz.console.treadmill.control.Clock
import com.treadz.console.treadmill.control.ControllerConfig
import com.treadz.console.treadmill.control.ControllerObserver
import com.treadz.console.treadmill.control.FtmsTreadmillController
import com.treadz.console.treadmill.control.T0
import com.treadz.console.treadmill.control.TraceRecord
import com.treadz.console.treadmill.ftms.FtmsCharacteristic
import com.treadz.console.treadmill.ftms.FtmsCodec
import com.treadz.console.treadmill.ftms.toHex
import com.treadz.console.treadmill.recording.InMemoryRecordingStorage
import com.treadz.console.treadmill.recording.JsonlWorkoutRecorder
import com.treadz.console.treadmill.recording.RecordedSession
import com.treadz.console.treadmill.recording.RecorderConfig
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.time.ZoneOffset
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.math.abs

/**
 * Replays the packets of two real sessions recorded on 2026-10-07 (Android client: Treadmill Data every ~490 ms,
 * each value repeated twice, values changing about once per second) through the codec and the controller on
 * virtual time. The sessions have a Start from the app, speed and incline changes made with the console buttons
 * and a stop with the console's STOP button.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RealSessionReplayTest {

    private class Timed<T>(val atMs: Long, val value: T)

    /** Everything observed while replaying one fragment. Times are ms since the replay origin. */
    private class Outcome(
        val fragment: RecordedFragment,
        val trace: List<TraceRecord>,
        val events: List<Timed<TreadmillEvent>>,
        val commandResults: List<Pair<String, CommandResult>>,
        val storage: InMemoryRecordingStorage,
        val origin: Long,
        val commandsHex: List<String>,
    ) {
        fun rel(atMillis: Long): Long = atMillis - T0 - origin
        val statuses: List<BeltStatus>
            get() = trace.filterIsInstance<TraceRecord.StateChanged>().map { it.state.status }.fold(mutableListOf()) { acc, s ->
                if (acc.lastOrNull() != s) acc += s
                acc
            }
        val manualChanges: List<Timed<TreadmillEvent.ManualChange>>
            get() = events.mapNotNull { e -> (e.value as? TreadmillEvent.ManualChange)?.let { Timed(e.atMs, it) } }
    }

    private suspend fun TestScope.replay(resource: String): Outcome {
        val fragment = RecordedFragment.load(resource)
        val bg = backgroundScope
        val clock = Clock { T0 + currentTime }
        val transport = ReplayTransport(bg, fragment, keepSendingLastDataMs = TAIL_MS)
        val storage = InMemoryRecordingStorage()
        val recorder = JsonlWorkoutRecorder(storage, clock, RecorderConfig(zone = ZoneOffset.UTC))
        val trace = CopyOnWriteArrayList<TraceRecord>()
        val controller = FtmsTreadmillController(
            transport, bg, clock, ControllerConfig(),
            observers = listOf(recorder, ControllerObserver { trace += it }),
        )
        val events = CopyOnWriteArrayList<Timed<TreadmillEvent>>()
        var origin = 0L
        transport.onReplayStart = { origin = currentTime }
        bg.launch(start = CoroutineStart.UNDISPATCHED) { controller.events.collect { events += Timed(currentTime - origin, it) } }

        assertEquals(CommandResult.Success, controller.connect("AA:BB:CC:DD:EE:FF"))
        val results = mutableListOf<Pair<String, CommandResult>>()
        for (cmd in fragment.commands) {
            delay(origin + cmd.atMs - currentTime)
            assertEquals("start", cmd.name)
            results += cmd.name to controller.start()
        }
        // The whole fragment plus the recorder's end grace period.
        delay(origin + fragment.packets.last().atMs + TAIL_MS - currentTime)
        return Outcome(fragment, trace.toList(), events.toList(), results, storage, origin, transport.written.map { it.toHex() })
    }

    /** A value that held for [durationMs] (until the next different value arrived). */
    private class Plateau(val value: Double, val startMs: Long, val durationMs: Long)

    private fun decodedSamples(fragment: RecordedFragment) =
        fragment.packets.filter { it.characteristic == FtmsCharacteristic.TreadmillData }
            .mapNotNull { p -> FtmsCodec.decodeTreadmillData(p.value)?.let(FtmsCodec::toSample)?.let { p.atMs to it } }

    private fun speedPlateaus(fragment: RecordedFragment): List<Plateau> {
        val samples = decodedSamples(fragment)
        val result = mutableListOf<Plateau>()
        var start = 0
        for (i in 1..samples.size) {
            if (i == samples.size || abs(samples[i].second.speedKmh - samples[start].second.speedKmh) >= 0.05) {
                val endAt = if (i == samples.size) samples.last().first else samples[i].first
                result += Plateau(samples[start].second.speedKmh, samples[start].first, endAt - samples[start].first)
                start = i
            }
        }
        return result
    }

    /** Offset (ms since the origin) of the packet where the real treadmill reported Training Status Idle after the start. */
    private fun idleAfterStartAt(fragment: RecordedFragment): Long {
        val startAt = fragment.commands.first().atMs
        return fragment.packets.first { it.characteristic == FtmsCharacteristic.TrainingStatus && it.atMs > startAt && it.value.toList() == listOf<Byte>(0x01, 0x01) }.atMs
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = [SESSION_1, SESSION_2])
    fun `no alerts are raised and the start is verified`(resource: String) = runTest {
        val o = replay(resource)
        assertEquals(listOf("start" to CommandResult.Success), o.commandResults)
        val alerts = o.events.map { it.value }.filter { it !is TreadmillEvent.ManualChange }
        assertTrue(alerts.isEmpty(), "no DataStalled, CommandNotExecuted, ConnectionLost or key events were expected: $alerts")
        assertTrue(o.trace.none { it is TraceRecord.Malformed }, "every recorded packet must decode")
        assertEquals(0, o.trace.filterIsInstance<TraceRecord.CommandFinished>().count { it.result !is CommandResult.Success })
        // Start, then Request Control before it
        assertEquals(listOf("00", "07"), o.commandsHex)
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = [SESSION_1, SESSION_2])
    fun `no manual change is reported while the belt ramps up after the start`(resource: String) = runTest {
        val o = replay(resource)
        // The belt has reached and held its starting speed (0.5 km/h) from this moment on.
        val startingSpeed = speedPlateaus(o.fragment).first { it.value in 0.45..0.55 }
        val rampEnd = startingSpeed.startMs + 1_500
        assertTrue(o.manualChanges.isNotEmpty(), "the sessions do have manual changes")
        val early = o.manualChanges.filter { it.atMs < rampEnd }
        assertTrue(early.isEmpty(), "manual changes during the start ramp (ends at $rampEnd ms): ${early.map { it.atMs to it.value }}")
        assertTrue(o.manualChanges.none { it.value.speedKmh != null && it.value.speedKmh!! < 0.55 && it.atMs < rampEnd + 5_000 })
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = [SESSION_1, SESSION_2])
    fun `manual changes are one event per settled value, not one per ramp step`(resource: String) = runTest {
        val o = replay(resource)
        val speedEvents = o.manualChanges.mapNotNull { it.value.speedKmh }
        val inclineEvents = o.manualChanges.mapNotNull { it.value.inclinePercent }

        // The incline is reported by the treadmill as a target value: every change is exactly one event.
        val samples = decodedSamples(o.fragment).map { it.second }
        val inclineTransitions = samples.zipWithNext().count { (a, b) -> abs(a.inclinePercent - b.inclinePercent) >= 0.05 }
        assertEquals(inclineTransitions, inclineEvents.size, "incline events vs transitions in the data")
        assertTrue(inclineEvents.size >= 10, "the sessions have ~12 incline changes")

        // Speed: only values that really held for at least the settle time are reported...
        val settleMs = ControllerConfig().manualChangeSettleMs
        val held = speedPlateaus(o.fragment).filter { it.durationMs >= settleMs }
        for (v in speedEvents) {
            assertTrue(held.any { abs(it.value - v) < 0.05 }, "speed $v was reported but never held for $settleMs ms")
        }
        // ...at most one per such plateau (excluding the idle and start-ramp plateaus) and not far from the plateaus
        // that are clearly stable (>= 2.5 s). The unfiltered ramp steps would be around 50 per session.
        val afterRamp = held.drop(2).dropLast(1)
        val clearlyStable = afterRamp.count { it.durationMs >= 2_500 }
        assertTrue(speedEvents.size <= afterRamp.size, "${speedEvents.size} speed events for ${afterRamp.size} settled plateaus")
        assertTrue(speedEvents.size >= clearlyStable / 2, "${speedEvents.size} speed events for $clearlyStable clearly stable plateaus")
        assertTrue(speedEvents.size + inclineEvents.size < 40, "too many manual changes: ${o.manualChanges.size}")
        // Consecutive events never repeat the same value
        assertTrue(speedEvents.zipWithNext().none { (a, b) -> abs(a - b) < 0.05 }, "$speedEvents")
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = [SESSION_1, SESSION_2])
    fun `the physical stop is followed from running to idle`(resource: String) = runTest {
        val o = replay(resource)
        assertEquals(listOf(BeltStatus.Idle, BeltStatus.Starting, BeltStatus.Running, BeltStatus.Paused, BeltStatus.Idle), o.statuses)
        // Running until the 02 status of the console's STOP, Idle with the Idle Training Status
        val stateAt = o.trace.filterIsInstance<TraceRecord.StateChanged>()
        val paused = stateAt.first { it.state.status == BeltStatus.Paused }
        val idleAgain = stateAt.first { it.atMillis >= paused.atMillis && it.state.status == BeltStatus.Idle }
        val stopStatus = o.fragment.packets.last { it.characteristic == FtmsCharacteristic.MachineStatus && it.value[0].toInt() == 0x02 }
        assertTrue(abs(o.rel(paused.atMillis) - stopStatus.atMs) < 100, "Paused at ${o.rel(paused.atMillis)} ms, status 02 at ${stopStatus.atMs} ms")
        assertTrue(abs(o.rel(idleAgain.atMillis) - idleAfterStartAt(o.fragment)) < 100, "Idle at ${o.rel(idleAgain.atMillis)} ms, Idle Training Status at ${idleAfterStartAt(o.fragment)} ms")
        assertEquals(BeltStatus.Idle, o.trace.filterIsInstance<TraceRecord.StateChanged>().last().state.status)
    }

    @ParameterizedTest(name = "{0}")
    @ValueSource(strings = [SESSION_1, SESSION_2])
    fun `the recorded sessions start with the belt leaving Idle and end when it returns to Idle`(resource: String) = runTest {
        val o = replay(resource)
        assertEquals(1, o.storage.fileNames.size, "one session, one file: ${o.storage.fileNames}")
        val name = o.storage.fileNames.single()
        assertTrue(Regex("""treadz_\d{8}_\d{6}\.jsonl""").matches(name), name)
        assertTrue(o.storage.isClosed(name), "file not closed")
        val session: RecordedSession = o.storage.parsed(name)

        val header = session.header!!
        assertEquals("command:start", header["start_reason"])
        val end = session.end!!
        assertEquals("idle", end["reason"])

        // The session ends the grace period after the Idle Training Status of the console's STOP
        val idleAt = idleAfterStartAt(o.fragment)
        val endedAt = (end["t"] as Long) - T0 - o.origin
        assertTrue(endedAt in idleAt..(idleAt + 3_500), "session ended at $endedAt ms, Idle at $idleAt ms")

        // Everything the treadmill sent between the Start and the end is in the file
        val startAt = o.fragment.commands.first().atMs
        val expectedRx = o.fragment.packets.count { it.atMs in (startAt + 200)..idleAt }
        val rx = session.ofType("rx").count { (it["t"] as Long) - T0 - o.origin in (startAt + 200)..idleAt }
        assertEquals(expectedRx, rx, "rx packets in the file")
        val speeds = session.ofType("data").filter { it["discarded"] == false }.map { it["speed_kmh"] as Double }
        assertEquals(speedPlateaus(o.fragment).maxOf { it.value }, speeds.max(), 1e-9)
        assertEquals(1, session.ofType("command").count { it["name"] == "start" })
        assertTrue(session.ofType("event").all { it["name"] == "ManualChange" })
        assertEquals(o.manualChanges.size, session.events("ManualChange").size, "every event is in the recording")
    }

    companion object {
        /** The recorder closes a session with the first record after its grace period: the treadmill goes on sending Idle data. */
        const val TAIL_MS = 4_000L
        const val SESSION_1 = "/real-sessions/real_session_1_20261007.txt"
        const val SESSION_2 = "/real-sessions/real_session_2_20261007.txt"
    }
}
