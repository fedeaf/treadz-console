package com.treadz.console.treadmill.control

import com.treadz.console.treadmill.BeltStatus
import com.treadz.console.treadmill.CommandResult
import com.treadz.console.treadmill.ConnectionState
import com.treadz.console.treadmill.TreadmillEvent
import com.treadz.console.treadmill.ftms.toHex
import com.treadz.console.treadmill.recording.InMemoryRecordingStorage
import com.treadz.console.treadmill.recording.JsonlWorkoutRecorder
import com.treadz.console.treadmill.recording.RecorderConfig
import com.treadz.console.treadmill.simulator.SimulatedTreadmill
import com.treadz.console.treadmill.simulator.SimulatedTreadmill.DataRate
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.currentTime
import org.junit.jupiter.api.Assertions.assertEquals
import java.time.Instant
import java.time.ZoneOffset
import java.util.concurrent.CopyOnWriteArrayList

/** 2026-10-07T18:30:00Z: the tests' "zero" instant (it appears in the recording file names). */
val T0: Long = Instant.parse("2026-10-07T18:30:00Z").toEpochMilli()

/** Simulated treadmill + controller + recorder, all on the [TestScope] virtual time. */
@OptIn(ExperimentalCoroutinesApi::class)
class Rig(
    private val testScope: TestScope,
    dataRate: DataRate = DataRate.Hz2Repeated,
    config: ControllerConfig = ControllerConfig(),
    recorderConfig: RecorderConfig = RecorderConfig(zone = ZoneOffset.UTC),
) {
    private val bg = testScope.backgroundScope
    val clock = Clock { T0 + testScope.currentTime }
    val sim = SimulatedTreadmill(bg, dataRate = dataRate)
    val storage = InMemoryRecordingStorage()
    val recorder = JsonlWorkoutRecorder(storage, clock, recorderConfig)
    val trace = CopyOnWriteArrayList<TraceRecord>()
    val controller = FtmsTreadmillController(
        sim, bg, clock, config,
        observers = listOf(recorder, ControllerObserver { trace += it }),
    )
    val events = CopyOnWriteArrayList<TreadmillEvent>()

    init {
        bg.launch(start = CoroutineStart.UNDISPATCHED) { controller.events.collect { events += it } }
    }

    val now: Long get() = testScope.currentTime

    suspend fun connect(): CommandResult = controller.connect(sim.address).also { delay(3_000) }

    /** Connects, starts and leaves the belt running at minimum speed. */
    suspend fun connectAndStart() {
        assertEquals(CommandResult.Success, connect())
        assertEquals(CommandResult.Success, controller.start())
        assertEquals(BeltStatus.Running, controller.state.value.status)
    }

    /** Connects, starts and brings the belt to [kmh] (waits for the ramp to get there). */
    suspend fun runAt(kmh: Double, incline: Double = 0.0) {
        connectAndStart()
        assertEquals(CommandResult.Success, controller.setSpeed(kmh))
        if (incline > 0) assertEquals(CommandResult.Success, controller.setIncline(incline))
        delay(((kmh / 0.5) + 3).toLong() * 1000)
        assertEquals(kmh, controller.state.value.speedKmh, 0.001)
    }

    val connected: Boolean get() = controller.connection.value is ConnectionState.Connected

    /** Commands written to the Control Point, in hex. */
    val commandsHex: List<String> get() = sim.receivedCommands.map { it.toHex() }

    fun eventsOf(type: kotlin.reflect.KClass<out TreadmillEvent>): List<TreadmillEvent> = events.filter { type.isInstance(it) }

    val samples: List<TraceRecord.Sample> get() = trace.filterIsInstance<TraceRecord.Sample>()
}
