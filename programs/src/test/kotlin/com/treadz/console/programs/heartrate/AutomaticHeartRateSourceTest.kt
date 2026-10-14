package com.treadz.console.programs.heartrate

import com.treadz.console.hrm.HrmTraceFields
import com.treadz.console.hrm.SimulatedStrap
import com.treadz.console.hrm.StrapHeartRateMonitor
import com.treadz.console.programs.fitness.HeartRateSourceKind
import com.treadz.console.treadmill.BeltStatus
import com.treadz.console.treadmill.TreadmillState
import com.treadz.console.treadmill.control.Clock
import com.treadz.console.treadmill.control.TraceRecord
import com.treadz.console.treadmill.recording.InMemoryRecordingStorage
import com.treadz.console.treadmill.recording.JsonlWorkoutRecorder
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.runTest
import org.junit.jupiter.api.Assertions.assertEquals
import org.junit.jupiter.api.Assertions.assertNull
import org.junit.jupiter.api.Assertions.assertTrue
import org.junit.jupiter.api.Test
import java.util.concurrent.CopyOnWriteArrayList

@OptIn(ExperimentalCoroutinesApi::class)
class AutomaticHeartRateSourceTest {

    /** A console heart rate the test sets directly. */
    private class FakeConsole(private val clock: Clock) : HeartRateSource {
        val state = MutableStateFlow(TreadmillState())
        fun set(bpm: Int?) {
            state.value = state.value.copy(heartRateBpm = bpm, updatedAtMillis = clock.nowMillis())
        }

        override fun latest(): HeartRateReading = ControllerHeartRateSource.map(state.value)
        override val readings: Flow<HeartRateReading> = state.map { ControllerHeartRateSource.map(it) }
    }

    private class Rig(scope: TestScope) {
        val bg = scope.backgroundScope
        val clock = Clock { scope.currentTime }
        var strapBpm = 120
        val strap = SimulatedStrap(bg, bpmSource = { strapBpm }, timeSource = clock::nowMillis)
        val monitor = StrapHeartRateMonitor(strap, bg, now = clock::nowMillis)
        val console = FakeConsole(clock)
        val trace = CopyOnWriteArrayList<TraceRecord>()
        val source = AutomaticHeartRateSource(monitor, console, bg, clock, trace = { trace += it })
        val readings = CopyOnWriteArrayList<HeartRateReading>()

        init {
            bg.launch(start = CoroutineStart.UNDISPATCHED) { source.readings.collect { readings += it } }
        }

        val sourceChanges get() = trace.filterIsInstance<TraceRecord.External>().filter { it.type == "hr_source" }
    }

    @Test
    fun `console, then the strap when the app has it, then the console again`() = runTest {
        val rig = Rig(this)
        assertNull(rig.source.latest().source)
        rig.console.set(98)
        delay(10)
        assertEquals(HeartRateReading(98, HeartRateValidity.Unconfirmed, currentTime - 10, HeartRateSourceKind.TreadmillConsole), rig.source.latest())
        assertEquals(HeartRateSourceKind.TreadmillConsole, rig.source.source.value)

        // The console releases the strap (it was the user's) and the app takes it: the console now sends 0.
        rig.console.set(0)
        rig.monitor.use(rig.strap.address)
        delay(3_000)
        val r = rig.source.latest()
        assertEquals(HeartRateSourceKind.DirectStrap, r.source)
        assertEquals(120, r.bpm)
        assertEquals(HeartRateValidity.Valid, r.validity)
        assertEquals(listOf(500, 500), r.rrIntervalsMs)
        assertEquals(HeartRateSourceKind.DirectStrap, rig.source.source.value)

        // Every strap notification is emitted once, with its RR intervals.
        val before = rig.readings.count { it.source == HeartRateSourceKind.DirectStrap && it.bpm != null }
        delay(10_000)
        val after = rig.readings.count { it.source == HeartRateSourceKind.DirectStrap && it.bpm != null }
        assertEquals(10, after - before)

        // Strap lost (out of range): back to the console.
        rig.strap.heldByConsole = true
        rig.strap.dropConnection()
        rig.console.set(121)
        delay(1_000)
        assertEquals(HeartRateSourceKind.TreadmillConsole, rig.source.latest().source)
        // (none while the console had already let the strap go and the app had not connected yet)
        assertEquals(listOf("TreadmillConsole", null, "DirectStrap", "TreadmillConsole"), rig.sourceChanges.map { it.fields["source"] })
    }

    @Test
    fun `a strap that lost contact is not replaced by the console`() = runTest {
        val rig = Rig(this)
        rig.monitor.use(rig.strap.address)
        delay(3_000)
        rig.console.set(140) // e.g. another user's strap paired with the console
        rig.strap.takeOff()
        delay(2_000)
        val r = rig.source.latest()
        assertEquals(HeartRateSourceKind.DirectStrap, r.source)
        assertNull(r.bpm)
        assertEquals(HeartRateValidity.Invalid, r.validity)
    }

    @Test
    fun `no source at all`() = runTest {
        val rig = Rig(this)
        rig.console.set(0)
        delay(10)
        val r = rig.source.latest()
        assertNull(r.bpm)
        assertNull(r.source)
        assertEquals(HeartRateValidity.Invalid, r.validity)
    }

    @Test
    fun `strap records and source changes go into the session recording and can be read back`() = runTest {
        val rig = Rig(this)
        val storage = InMemoryRecordingStorage()
        val recorder = JsonlWorkoutRecorder(storage, rig.clock)
        rig.monitor.addObserver { r ->
            val (type, fields) = HrmTraceFields.of(r)
            recorder.onTrace(TraceRecord.External(r.atMillis, type, fields))
        }
        val sink = AutomaticHeartRateSource(rig.monitor, rig.console, backgroundScope, rig.clock, trace = recorder)
        // A treadmill session starts (the recorder opens a file when the belt leaves Idle).
        recorder.onTrace(TraceRecord.StateChanged(currentTime, TreadmillState(status = BeltStatus.Running, speedKmh = 3.0), "test"))
        rig.monitor.use(rig.strap.address)
        delay(5_000)
        assertEquals(HeartRateSourceKind.DirectStrap, sink.source.value)
        recorder.finish("test")
        val session = storage.parsed(storage.fileNames.single())
        val readings = session.ofType("hrm_reading")
        assertTrue(readings.size >= 4, "${readings.size}")
        assertEquals(120L, readings.first()["hr_bpm"])
        assertEquals("Detected", readings.first()["contact"])
        assertEquals(listOf(500L, 500L), readings.first()["rr_ms"])
        assertTrue(session.ofType("hrm_rx").isNotEmpty())
        assertTrue(session.ofType("hrm_connection").any { it["state"] == "Connected" })
        assertEquals("DirectStrap", session.ofType("hr_source").last()["source"])
    }

    @Test
    fun `resting heart rate measured with the strap`() = runTest {
        val rig = Rig(this)
        rig.strapBpm = 58
        rig.monitor.use(rig.strap.address)
        delay(3_000)
        val session = restingHeartRateSession(rig.source, backgroundScope, now = rig.clock::nowMillis)
        session.start()
        delay(60_000)
        rig.strapBpm = 54
        delay(125_000)
        val result = checkNotNull(session.progress.value.result)
        assertTrue(result.valid, "$result")
        assertEquals(54, result.bpm)
    }

    @Test
    fun `resting heart rate with the strap taken off is invalid`() = runTest {
        val rig = Rig(this)
        rig.strapBpm = 58
        rig.monitor.use(rig.strap.address)
        delay(3_000)
        val session = restingHeartRateSession(rig.source, backgroundScope, now = rig.clock::nowMillis)
        session.start()
        delay(100_000)
        rig.strap.takeOff()
        delay(85_000)
        val result = checkNotNull(session.progress.value.result)
        assertTrue(!result.valid, "$result")
    }
}
