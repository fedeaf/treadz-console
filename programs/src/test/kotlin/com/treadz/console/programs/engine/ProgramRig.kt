package com.treadz.console.programs.engine

import com.treadz.console.programs.block
import com.treadz.console.programs.model.Block
import com.treadz.console.programs.model.BlockEnd
import com.treadz.console.programs.model.BlockPhase
import com.treadz.console.programs.model.BlockTarget
import com.treadz.console.programs.model.Program
import com.treadz.console.programs.model.ProgramKind
import com.treadz.console.programs.model.ProgramStep
import com.treadz.console.programs.plan.ProgramResolver
import com.treadz.console.programs.plan.ResolvedPlan
import com.treadz.console.programs.plan.StartParameters
import com.treadz.console.programs.ID_1
import com.treadz.console.treadmill.CommandResult
import com.treadz.console.treadmill.TreadmillEvent
import com.treadz.console.treadmill.control.Clock
import com.treadz.console.treadmill.control.ControllerConfig
import com.treadz.console.treadmill.control.FtmsTreadmillController
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

val T0: Long = Instant.parse("2026-10-08T10:00:00Z").toEpochMilli()

/** Simulated treadmill + controller + program runner on the virtual time of a [TestScope]. */
@OptIn(ExperimentalCoroutinesApi::class)
class ProgramRig(
    private val testScope: TestScope,
    runnerConfig: RunnerConfig = RunnerConfig(),
) {
    private val bg = testScope.backgroundScope
    val clock = Clock { T0 + testScope.currentTime }
    val sim = SimulatedTreadmill(bg, timeSource = { clock.nowMillis() })
    val controller = FtmsTreadmillController(sim, bg, clock, ControllerConfig())
    val runner = ProgramRunner(controller, bg, clock, runnerConfig)

    val events = CopyOnWriteArrayList<ProgramEvent>()
    val controllerEvents = CopyOnWriteArrayList<TreadmillEvent>()
    val states = CopyOnWriteArrayList<RunState>()

    init {
        bg.launch(start = CoroutineStart.UNDISPATCHED) { runner.events.collect { events += it } }
        bg.launch(start = CoroutineStart.UNDISPATCHED) { controller.events.collect { controllerEvents += it } }
        bg.launch(start = CoroutineStart.UNDISPATCHED) { runner.progress.collect { if (states.lastOrNull() != it.state) states += it.state } }
    }

    val progress: ProgramProgress get() = runner.progress.value

    suspend fun connect() {
        assertEquals(CommandResult.Success, controller.connect(sim.address))
        delay(3_000)
    }

    suspend fun start(plan: ResolvedPlan, options: RunOptions = RunOptions()): RunnerResult = runner.start(plan, options)

    /** Waits (in virtual time) until [condition] holds; fails after [timeoutMs]. */
    suspend fun awaitTrue(timeoutMs: Long = 120_000, message: String = "condition", condition: () -> Boolean) {
        var waited = 0L
        while (!condition()) {
            check(waited <= timeoutMs) { "Timed out waiting for $message (state=${progress.state}, block=${progress.currentBlockIndex}, speed=${controller.state.value.speedKmh})" }
            delay(100)
            waited += 100
        }
    }

    suspend fun awaitState(state: RunState, timeoutMs: Long = 120_000) =
        awaitTrue(timeoutMs, "state $state") { progress.state == state }

    val now: Long get() = testScope.currentTime

    /** Control Point opcodes of every command written to the treadmill so far. */
    val opcodes: List<Int> get() = sim.receivedCommands.map { it[0].toInt() and 0xFF }

    fun count(opcode: Int) = opcodes.count { it == opcode }

    inline fun <reified T : ProgramEvent> eventsOf(): List<T> = events.filterIsInstance<T>()

    companion object {
        const val SET_SPEED = 0x02
        const val SET_INCLINE = 0x03
        const val START = 0x07
        const val STOP_PAUSE = 0x08
    }
}

/** Builds a resolved plan from the given steps (absolute targets keep the numbers exact). */
fun planOf(vararg steps: ProgramStep, parameters: StartParameters = StartParameters()): ResolvedPlan =
    ProgramResolver.resolve(Program(ID_1, "Test", ProgramKind.Free, steps.toList()), parameters)

fun timed(seconds: Int, speed: Double, incline: Double = 0.0, phase: BlockPhase = BlockPhase.Work): Block =
    block(phase, BlockEnd.Duration(seconds), BlockTarget.Absolute(speed, incline))

fun distance(meters: Int, speed: Double, incline: Double = 0.0, phase: BlockPhase = BlockPhase.Work): Block =
    block(phase, BlockEnd.Distance(meters), BlockTarget.Absolute(speed, incline))
