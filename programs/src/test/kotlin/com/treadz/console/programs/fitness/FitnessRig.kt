package com.treadz.console.programs.fitness

import com.treadz.console.treadmill.CommandResult
import com.treadz.console.treadmill.TreadmillEvent
import com.treadz.console.treadmill.control.Clock
import com.treadz.console.treadmill.control.ControllerConfig
import com.treadz.console.treadmill.control.FtmsTreadmillController
import com.treadz.console.treadmill.simulator.PhysiologicalHeartRateModel
import com.treadz.console.treadmill.simulator.SimulatedHeartRateModel
import com.treadz.console.treadmill.simulator.SimulatedTreadmill
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.currentTime
import org.junit.jupiter.api.Assertions.assertEquals
import java.time.Instant
import java.util.concurrent.CopyOnWriteArrayList

val FT0: Long = Instant.parse("2026-10-08T10:00:00Z").toEpochMilli()

const val RESULT_ID = "0b6f8f9e-4d0c-4d43-9f43-2f0c6f1a7b10"

/** A 40-year-old man, 80 kg, maximum heart rate 180. */
val SUBJECT = FitnessSubject(ageYears = 40, sex = Sex.Male, weightKg = 80.0, hrMaxBpm = 180)

/** Simulated treadmill (with a heart-rate model) + controller + fitness test runner on the virtual time of a [TestScope]. */
@OptIn(ExperimentalCoroutinesApi::class)
class FitnessRig(
    private val testScope: TestScope,
    val model: SimulatedHeartRateModel? = PhysiologicalHeartRateModel(),
    config: FitnessTestConfig = FitnessTestConfig(),
) {
    private val bg = testScope.backgroundScope
    val clock = Clock { FT0 + testScope.currentTime }
    val sim = SimulatedTreadmill(bg, timeSource = { clock.nowMillis() })
    val controller = FtmsTreadmillController(sim, bg, clock, ControllerConfig())
    val runner = FitnessTestRunner(controller, bg, clock, config = config, idFactory = { RESULT_ID })

    val events = CopyOnWriteArrayList<FitnessTestEvent>()
    val controllerEvents = CopyOnWriteArrayList<TreadmillEvent>()
    val phases = CopyOnWriteArrayList<FitnessTestPhase>()

    /** Simulator speed at every test-time second (for checking that the speed was constant). */
    val speedLog = CopyOnWriteArrayList<Pair<Long, Double>>()

    init {
        sim.heartRateModel = model
        bg.launch(start = CoroutineStart.UNDISPATCHED) { runner.events.collect { events += it } }
        bg.launch(start = CoroutineStart.UNDISPATCHED) { controller.events.collect { controllerEvents += it } }
        bg.launch(start = CoroutineStart.UNDISPATCHED) { runner.progress.collect { if (phases.lastOrNull() != it.phase) phases += it.phase } }
        bg.launch {
            while (true) {
                speedLog += testScope.currentTime to sim.currentSpeedKmh
                delay(1_000)
            }
        }
    }

    val physiology: PhysiologicalHeartRateModel get() = model as PhysiologicalHeartRateModel

    val progress: FitnessTestProgress get() = runner.progress.value

    val result: FitnessTestResult get() = checkNotNull(progress.result) { "no result yet (phase ${progress.phase})" }

    val now: Long get() = testScope.currentTime

    /** Connects and lets the heart-rate model run for a while (the strap is on before the test). */
    suspend fun connect(warmSeconds: Long = 5) {
        assertEquals(CommandResult.Success, controller.connect(sim.address))
        delay(warmSeconds * 1000)
    }

    suspend fun awaitTrue(timeoutMs: Long = 1_800_000, message: String = "condition", condition: () -> Boolean) {
        var waited = 0L
        while (!condition()) {
            check(waited <= timeoutMs) {
                "Timed out waiting for $message (phase=${progress.phase}, t=${progress.elapsedSeconds}, speed=${sim.currentSpeedKmh}, hr=${sim.currentHeartRate})"
            }
            delay(100)
            waited += 100
        }
    }

    suspend fun awaitPhase(phase: FitnessTestPhase, timeoutMs: Long = 1_800_000) = awaitTrue(timeoutMs, "phase $phase") { progress.phase == phase }

    suspend fun awaitFinished(timeoutMs: Long = 1_800_000) = awaitPhase(FitnessTestPhase.Finished, timeoutMs)

    /** Control Point opcodes written to the treadmill so far. */
    val opcodes: List<Int> get() = sim.receivedCommands.map { it[0].toInt() and 0xFF }

    fun count(opcode: Int) = opcodes.count { it == opcode }

    /** Speeds (km/h) of every Set Speed command, in order. */
    val speedCommands: List<Double>
        get() = sim.receivedCommands.filter { (it[0].toInt() and 0xFF) == SET_SPEED }
            .map { ((it[1].toInt() and 0xFF) or ((it[2].toInt() and 0xFF) shl 8)) / 100.0 }

    /** Inclines (%) of every Set Incline command, in order. */
    val inclineCommands: List<Double>
        get() = sim.receivedCommands.filter { (it[0].toInt() and 0xFF) == SET_INCLINE }
            .map { ((it[1].toInt() and 0xFF) or ((it[2].toInt() and 0xFF) shl 8)).toShort() / 10.0 }

    inline fun <reified T : FitnessTestEvent> eventsOf(): List<T> = events.filterIsInstance<T>()

    companion object {
        const val SET_SPEED = 0x02
        const val SET_INCLINE = 0x03
        const val START = 0x07
        const val STOP_PAUSE = 0x08
    }
}
