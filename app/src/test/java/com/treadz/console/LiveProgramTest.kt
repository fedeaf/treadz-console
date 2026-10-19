package com.treadz.console

import com.treadz.console.fakes.MainDispatcherRule
import com.treadz.console.fakes.TrainingFixtures
import com.treadz.console.fakes.TrainingRig
import com.treadz.console.programs.engine.RunState
import com.treadz.console.programs.plan.ProgramResolver
import com.treadz.console.training.ActualSample
import com.treadz.console.training.ProgramSeries
import com.treadz.console.training.RunViewModel
import com.treadz.console.training.StartOutcome
import com.treadz.console.treadmill.CommandResult
import com.treadz.console.treadmill.TreadmillState
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** The live part of a program: the series of what the treadmill really did, and "+1". */
class LiveProgramTest {

    @get:Rule val main = MainDispatcherRule()

    private val rig = TrainingRig()
    private val app get() = rig.app
    private val controller get() = app.controller
    private val training get() = app.training
    private val userId get() = runBlocking { app.userData.profiles.activeUserId()!! }

    /** The belt at rest; afterwards every speed command moves the belt at once, so the series follows the plan. */
    private fun start(plan: com.treadz.console.programs.plan.ResolvedPlan = rig.standardPlan("standard-pyramid")) {
        controller.state.value = TreadmillState(updatedAtMillis = rig.scheduler.currentTime)
        controller.setSpeedHandler = { kmh ->
            controller.state.value = controller.state.value.copy(speedKmh = kmh, targetSpeedKmh = kmh)
            CommandResult.Success
        }
        assertEquals(StartOutcome.Started, rig.startProgram(plan, userId))
    }

    private val series get() = training.programSeries.value

    // ---- The series itself ----

    @Test fun aSampleCountsOncePerStride() {
        val s = ProgramSeries().appended(ActualSample(0, 5.0, 0.0)).appended(ActualSample(0, 5.1, 0.0)).appended(ActualSample(1, 5.2, 1.0))
        assertEquals(listOf(0, 1), s.samples.map { it.seconds })
        assertSame(s, s.appended(ActualSample(1, 9.9, 9.0)))
    }

    @Test fun aFullSeriesHalvesItsResolutionAndKeepsTheFirstAndTheNewestSample() {
        var s = ProgramSeries()
        for (t in 0..8) s = s.appended(ActualSample(t, t.toDouble(), 0.0), maxSamples = 8)
        assertEquals(2, s.strideSeconds)
        assertEquals(listOf(0, 2, 4, 6, 8), s.samples.map { it.seconds })
        // From now on one sample every two seconds.
        assertSame(s, s.appended(ActualSample(9, 1.0, 0.0), maxSamples = 8))
        assertEquals(10, s.appended(ActualSample(10, 1.0, 0.0), maxSamples = 8).samples.last().seconds)
    }

    // ---- Filled by the coordinator ----

    @Test fun theSeriesGrowsOncePerProgramSecondWhileTheProgramRuns() {
        start()
        rig.advance(30_000)
        val progress = app.programRunner.progress.value
        val seconds = series.samples.map { it.seconds }
        assertEquals(seconds.distinct(), seconds)
        assertEquals(seconds.sorted(), seconds)
        assertTrue("${seconds.size}", seconds.size in 28..32)
        assertEquals(progress.totalElapsedSeconds, seconds.last())
        // The real speed of the treadmill, not the plan's.
        assertEquals(controller.state.value.speedKmh, series.samples.last().speedKmh, 0.001)
    }

    @Test fun nothingIsAddedWhileTheProgramIsPaused() {
        start()
        rig.advance(20_000)
        assertEquals(CommandResult.Success, rig.call { training.commands.pause() })
        val paused = series.samples.size
        rig.advance(30_000)
        assertEquals(paused, series.samples.size)

        assertEquals(CommandResult.Success, rig.call { training.commands.resume() })
        rig.advance(10_000)
        assertTrue(series.samples.size > paused)
    }

    @Test fun theSeriesOutlivesTheScreenAndTheEndAndANewProgramStartsAnEmptyOne() {
        start()
        rig.advance(60_000)
        // A new screen (view model) sees what was recorded while there was none.
        val vm = RunViewModel(training, app.userData)
        rig.advance(10)
        assertEquals(series, vm.uiState.value.programSeries)

        rig.call { training.commands.stop() }
        assertEquals(RunState.Finished, app.programRunner.progress.value.state)
        val atTheEnd = series.samples.size
        assertTrue(atTheEnd >= 59)
        rig.advance(30_000)
        assertEquals(atTheEnd, series.samples.size)

        training.dismissResult()
        start(rig.standardPlan("standard-hill"))
        rig.advance(5_000)
        assertTrue("${series.samples.size}", series.samples.size <= 6)
    }

    // ---- "+1" ----

    @Test fun plusOneAddsAMinuteToATimeBlockAndTheScreenFollowsThePublishedPlan() {
        start()
        rig.advance(20_000)
        val vm = RunViewModel(training, app.userData)
        rig.advance(10)
        val before = vm.uiState.value.program

        vm.extendCurrentBlock()
        rig.advance(300)
        val after = vm.uiState.value.program
        assertNear(before.blockRemainingSeconds + 60, after.blockRemainingSeconds, 1)
        assertNear(before.totalRemainingSeconds + 60, after.totalRemainingSeconds, 1)
        assertEquals(before.plan!!.estimatedDurationSeconds + 60, after.plan!!.estimatedDurationSeconds)
    }

    @Test fun plusOneAddsAHundredMetresToADistanceBlock() {
        start(ProgramResolver.resolve(TrainingFixtures.keepProgram()))
        // Into the 1 km block (the warm-up lasts 5 minutes).
        rig.advance(330_000)
        val vm = RunViewModel(training, app.userData)
        rig.advance(10)
        val before = vm.uiState.value.program
        assertEquals(1, before.currentBlockIndex)

        vm.extendCurrentBlock()
        rig.advance(300)
        val after = vm.uiState.value.program
        assertNear(before.blockRemainingMeters!! + 100, after.blockRemainingMeters!!, 15)
        assertEquals(com.treadz.console.programs.model.BlockEnd.Distance(1100), after.currentBlock!!.end)
    }

    private fun assertNear(expected: Int, actual: Int, tolerance: Int) =
        assertTrue("expected $expected ± $tolerance, was $actual", kotlin.math.abs(expected - actual) <= tolerance)
}
