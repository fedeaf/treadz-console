package com.treadz.console

import com.treadz.console.controls.ControlAxis
import com.treadz.console.fakes.FakeApp
import com.treadz.console.fakes.FakeWorkoutRecorder
import com.treadz.console.fakes.InMemorySettingsStore
import com.treadz.console.treadmill.BeltStatus
import com.treadz.console.treadmill.CommandResult
import com.treadz.console.treadmill.control.Clock
import com.treadz.console.treadmill.control.FtmsTreadmillController
import com.treadz.console.treadmill.ftms.toHex
import com.treadz.console.treadmill.simulator.SimulatedTreadmill
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.currentTime
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The manual controls on the real controller and the simulated treadmill (2 Hz with repeated values, like Android),
 * in virtual time: what the user taps is what the treadmill ends up doing, with few commands.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class ManualControlsSimulatorTest {

    @After fun tearDown() = Dispatchers.resetMain()

    private class Rig(scope: TestScope) {
        val sim = SimulatedTreadmill(scope.backgroundScope)
        val controller = FtmsTreadmillController(sim, scope.backgroundScope, Clock { scope.currentTime })
        val vm = FakeApp().let { MainViewModel(controller, FakeWorkoutRecorder(), InMemorySettingsStore(), it.userData, it.appLifecycle) }

        /** Speed commands (opcode 0x02) written to the treadmill so far. */
        val speedCommandCount get() = sim.receivedCommands.count { it.toHex().startsWith("02") }
        val inclineCommandCount get() = sim.receivedCommands.count { it.toHex().startsWith("03") }
    }

    private suspend fun TestScope.runningAt(rig: Rig, kmh: Double) {
        assertEquals(CommandResult.Success, rig.controller.connect(rig.sim.address))
        delay(3_000)
        assertEquals(CommandResult.Success, rig.controller.start())
        assertEquals(CommandResult.Success, rig.controller.setSpeed(kmh))
        delay(((kmh / 0.5) + 4).toLong() * 1000)
        assertEquals(kmh, rig.sim.currentSpeedKmh, 0.001)
    }

    @Test fun fiveQuickTapsEndFiveTenthsHigherWithAtMostTwoCommands() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val rig = Rig(this)
        delay(100) // the ViewModel's collectors start
        runningAt(rig, 8.0)
        val before = rig.speedCommandCount

        repeat(5) { rig.vm.controls.stepSpeed(+1) }
        testScheduler.runCurrent()
        assertEquals(8.5, rig.vm.uiState.value.controls.pendingSpeed!!, 0.0001) // shown at once
        delay(15_000)

        assertEquals(8.5, rig.sim.currentSpeedKmh, 0.001)
        assertEquals(8.5, rig.controller.state.value.targetSpeedKmh!!, 0.001)
        assertTrue("sent ${rig.speedCommandCount - before} speed commands", rig.speedCommandCount - before <= 2)
        assertNull(rig.vm.uiState.value.controls.pendingSpeed)
        assertNull(rig.vm.uiState.value.controls.failure)
    }

    @Test fun holdingPlusRampsUpAndStopsWhenReleased() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val rig = Rig(this)
        delay(100)
        runningAt(rig, 6.0)

        rig.vm.controls.startRepeat(ControlAxis.Speed, +1)
        delay(2_500)
        rig.vm.controls.stopRepeat()
        val target = rig.vm.uiState.value.controls.pendingSpeed ?: rig.controller.state.value.targetSpeedKmh!!
        assertTrue("target $target", target > 7.0)
        delay(30_000)
        assertEquals(target, rig.sim.currentSpeedKmh, 0.001)
        // Releasing really stops it: no more commands afterwards.
        val commands = rig.speedCommandCount
        delay(10_000)
        assertEquals(commands, rig.speedCommandCount)
        // Far fewer commands than steps: the taps were coalesced.
        assertTrue("commands ${rig.speedCommandCount}", rig.speedCommandCount < 12)
    }

    @Test fun inclineStepsEndWhereTheUserTapped() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val rig = Rig(this)
        delay(100)
        runningAt(rig, 8.0)
        val before = rig.inclineCommandCount
        repeat(4) { rig.vm.controls.stepIncline(+1) }
        delay(10_000)
        assertEquals(4.0, rig.sim.currentInclinePercent, 0.001)
        assertTrue(rig.inclineCommandCount - before <= 2)
        rig.vm.controls.setIncline(0.0)
        delay(10_000)
        assertEquals(0.0, rig.sim.currentInclinePercent, 0.001)
    }

    @Test fun pauseResumeAndStopOnTheSimulator() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val rig = Rig(this)
        delay(100)
        runningAt(rig, 4.0)

        rig.vm.controls.pause()
        delay(1_000)
        // Right after pausing the belt is still slowing down: Resume is not offered yet.
        assertEquals(BeltStatus.Paused, rig.vm.uiState.value.treadmill.status)
        assertTrue(rig.vm.uiState.value.resumeWaitsForBelt)
        assertTrue(!rig.vm.uiState.value.canResume)
        delay(20_000)
        assertTrue(rig.vm.uiState.value.canResume)

        rig.vm.controls.resume()
        delay(30_000)
        assertEquals(BeltStatus.Running, rig.vm.uiState.value.treadmill.status)

        rig.vm.controls.stop()
        delay(40_000)
        assertEquals(0.0, rig.sim.currentSpeedKmh, 0.001)
        assertEquals(BeltStatus.Idle, rig.vm.uiState.value.treadmill.status)
        assertTrue(!rig.vm.uiState.value.beltActive)
    }

    @Test fun aConsoleButtonChangeResetsTheUsersPendingTarget() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val rig = Rig(this)
        delay(100)
        runningAt(rig, 8.0)
        rig.sim.physicalSetSpeed(10.0)
        delay(15_000)
        assertEquals(10.0, rig.sim.currentSpeedKmh, 0.001)
        // The next tap starts from what the console set, not from the app's old target.
        rig.vm.controls.stepSpeed(+1)
        delay(15_000)
        assertEquals(10.1, rig.sim.currentSpeedKmh, 0.001)
    }
}
