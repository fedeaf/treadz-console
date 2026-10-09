package com.treadz.console.fakes

import com.treadz.console.programs.engine.ProgramRunner
import com.treadz.console.programs.fitness.FitnessTestRunner
import com.treadz.console.training.FitnessTestIds
import com.treadz.console.training.TrainingCoordinator
import com.treadz.console.treadmill.CommandResult
import com.treadz.console.treadmill.control.Clock
import com.treadz.console.treadmill.control.ControllerConfig
import com.treadz.console.treadmill.control.FtmsTreadmillController
import com.treadz.console.treadmill.simulator.PhysiologicalHeartRateModel
import com.treadz.console.treadmill.simulator.SimulatedHeartRateModel
import com.treadz.console.treadmill.simulator.SimulatedTreadmill
import com.treadz.console.userdata.memory.InMemoryUserData
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.currentTime
import org.junit.Assert.assertEquals

/**
 * The real controller on the simulated treadmill (with a physiological heart-rate model), the real runners and the
 * coordinator, in the virtual time of a [TestScope]. The data layer is the in-memory one.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class SimTrainingRig(
    private val testScope: TestScope,
    val userData: InMemoryUserData = InMemoryUserData(),
    model: SimulatedHeartRateModel? = PhysiologicalHeartRateModel(restingBpm = 78),
) {
    private val bg = testScope.backgroundScope
    val clock = Clock { 1_800_000_000_000L + testScope.currentTime }
    val sim = SimulatedTreadmill(bg, timeSource = { clock.nowMillis() })
    val controller = FtmsTreadmillController(sim, bg, clock, ControllerConfig())
    val links = RecordingSessionLinks()
    val ids = FitnessTestIds()
    val programRunner = ProgramRunner(controller, bg, clock)
    val fitnessRunner = FitnessTestRunner(controller, bg, clock, idFactory = ids::next)
    val training = TrainingCoordinator(controller, programRunner, fitnessRunner, ids, links, userData, bg)

    init {
        sim.heartRateModel = model
    }

    suspend fun connect(warmSeconds: Long = 5) {
        assertEquals(CommandResult.Success, controller.connect(sim.address))
        delay(warmSeconds * 1000)
    }

    suspend fun awaitTrue(timeoutMs: Long = 1_800_000, message: String = "condition", condition: () -> Boolean) {
        var waited = 0L
        while (!condition()) {
            check(waited <= timeoutMs) { "Timed out waiting for $message" }
            delay(100)
            waited += 100
        }
    }
}
