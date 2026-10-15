package com.treadz.console.programs.engine

import com.treadz.console.hrm.SimulatedStrap
import com.treadz.console.hrm.StrapConnectionState
import com.treadz.console.hrm.StrapHeartRateMonitor
import com.treadz.console.programs.heartrate.AutomaticHeartRateSource
import com.treadz.console.programs.heartrate.ControllerHeartRateSource
import com.treadz.console.programs.heartrate.HeartRateProfile
import com.treadz.console.programs.plan.ResolvedPlan
import com.treadz.console.treadmill.CommandResult
import com.treadz.console.treadmill.control.Clock
import com.treadz.console.treadmill.control.ControllerConfig
import com.treadz.console.treadmill.control.FtmsTreadmillController
import com.treadz.console.treadmill.control.TraceRecord
import com.treadz.console.treadmill.simulator.PhysiologicalHeartRateModel
import com.treadz.console.treadmill.simulator.SimulatedBodyInput
import com.treadz.console.treadmill.simulator.SimulatedHeartRateModel
import com.treadz.console.treadmill.simulator.SimulatedTreadmill
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.currentTime
import org.junit.jupiter.api.Assertions.assertEquals
import java.util.concurrent.CopyOnWriteArrayList

/**
 * Simulated treadmill + simulated strap + controller + automatic heart-rate source + program runner, on the virtual
 * time of a [TestScope]. One body ([body], a heart-rate model evaluated once per simulated second by the treadmill) feeds
 * either the console or the strap: the simulated strap holds one connection, so while the app has it the console
 * reports 0 (real straps may serve both; this keeps the two sources apart).
 *
 * [scripted] replaces the physiological model with a fixed curve (bpm as a function of the simulated second).
 */
@OptIn(ExperimentalCoroutinesApi::class)
class HeartRateRig(
    private val testScope: TestScope,
    val physiology: PhysiologicalHeartRateModel = PhysiologicalHeartRateModel(restingBpm = 60, maxBpm = 180, vo2maxMlKgMin = 45.0, noiseBpm = 1),
    val profile: HeartRateProfile? = HeartRateProfile(maxBpm = 180, restingBpm = 60),
    runnerConfig: RunnerConfig = RunnerConfig(),
) {
    private val bg = testScope.backgroundScope
    val clock = Clock { T0 + testScope.currentTime }
    val sim = SimulatedTreadmill(bg, timeSource = { clock.nowMillis() })
    val controller = FtmsTreadmillController(sim, bg, clock, ControllerConfig())

    /** When set, the body's heart rate is this function of the simulated second instead of the physiological model. */
    @Volatile
    var scripted: ((Long) -> Int)? = null

    /** The body's current heart rate (what a strap in contact measures). */
    @Volatile
    var bodyBpm: Int = 0
        private set

    val strap = SimulatedStrap(bg, bpmSource = { bodyBpm }, timeSource = { clock.nowMillis() })
    val monitor = StrapHeartRateMonitor(strap, bg, now = { clock.nowMillis() })
    val trace = CopyOnWriteArrayList<TraceRecord>()
    val source = AutomaticHeartRateSource(monitor, ControllerHeartRateSource(controller), bg, clock, trace = { trace += it })
    val runner = ProgramRunner(controller, bg, clock, runnerConfig, source)
    val events = CopyOnWriteArrayList<ProgramEvent>()

    /** Simulator speed and incline at every second (program check: never stopped, within bounds). */
    val log = CopyOnWriteArrayList<Triple<Long, Double, Double>>()

    init {
        sim.heartRateModel = SimulatedHeartRateModel { input: SimulatedBodyInput ->
            val s = scripted
            val bpm = if (s != null) s(input.second) else physiology.nextSecond(input)
            bodyBpm = bpm
            // The strap is with the app: the console has no heart rate.
            if (strap.isConnected) 0 else bpm
        }
        bg.launch(start = CoroutineStart.UNDISPATCHED) { runner.events.collect { events += it } }
        bg.launch {
            while (true) {
                log += Triple(testScope.currentTime, sim.currentSpeedKmh, sim.currentInclinePercent)
                delay(1_000)
            }
        }
    }

    val progress: ProgramProgress get() = runner.progress.value

    suspend fun connect(withStrap: Boolean = false) {
        assertEquals(CommandResult.Success, controller.connect(sim.address))
        if (withStrap) {
            monitor.use(strap.address)
            awaitTrue(60_000, "strap connected") { monitor.connection.value is StrapConnectionState.Connected }
        }
        delay(3_000)
    }

    suspend fun start(plan: ResolvedPlan): RunnerResult = runner.start(plan, RunOptions(heartRateProfile = profile))

    suspend fun awaitTrue(timeoutMs: Long = 3_600_000, message: String = "condition", condition: () -> Boolean) {
        var waited = 0L
        while (!condition()) {
            check(waited <= timeoutMs) { "Timed out waiting for $message (state=${progress.state}, block=${progress.currentBlockIndex}, speed=${sim.currentSpeedKmh}, hr=$bodyBpm)" }
            delay(100)
            waited += 100
        }
    }

    suspend fun awaitBlock(index: Int) = awaitTrue(message = "block $index") { progress.currentBlockIndex >= index || progress.state == RunState.Finished }

    suspend fun awaitFinished() = awaitTrue(message = "finished") { progress.state == RunState.Finished }

    inline fun <reified T : ProgramEvent> eventsOf(): List<T> = events.filterIsInstance<T>()

    /** Speeds of every Set Speed command, in order. */
    val speedCommands: List<Double>
        get() = sim.receivedCommands.filter { (it[0].toInt() and 0xFF) == 0x02 }
            .map { ((it[1].toInt() and 0xFF) or ((it[2].toInt() and 0xFF) shl 8)) / 100.0 }

    val stopCommands: Int get() = sim.receivedCommands.count { (it[0].toInt() and 0xFF) == 0x08 }
}
