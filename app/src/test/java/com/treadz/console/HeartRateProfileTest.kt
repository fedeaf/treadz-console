package com.treadz.console

import com.treadz.console.fakes.MainDispatcherRule
import com.treadz.console.fakes.TrainingFixtures
import com.treadz.console.fakes.TrainingRig
import com.treadz.console.fakes.launchCollect
import com.treadz.console.programs.engine.HeartRateControlState
import com.treadz.console.programs.engine.ProgramEvent
import com.treadz.console.programs.engine.RunnerResult
import com.treadz.console.programs.heartrate.HeartRateProfile
import com.treadz.console.programs.plan.ProgramResolver
import com.treadz.console.training.StartOutcome
import com.treadz.console.treadmill.BeltStatus
import com.treadz.console.treadmill.TreadmillState
import com.treadz.console.userdata.RestingHeartRateInput
import com.treadz.console.userdata.RestingHeartRateSource
import com.treadz.console.userdata.memory.DemoPrograms
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * The coordinator hands the runner the user's heart-rate references (effective maximum from the policy and the resting
 * reference), so heart-rate guided blocks work; resuming the control goes through the coordinator to the runner.
 */
class HeartRateProfileTest {

    @get:Rule val main = MainDispatcherRule()

    private val rig = TrainingRig()
    private val app get() = rig.app
    private val data get() = app.userData
    private val userId get() = runBlocking { data.profiles.activeUserId()!! }
    private val plan get() = ProgramResolver.resolve(TrainingFixtures.heartRateProgram())

    private fun restBelt() {
        app.controller.state.value = TreadmillState(heartRateBpm = 72, updatedAtMillis = rig.scheduler.currentTime)
    }

    private fun startedOptions(): HeartRateProfile? {
        val events = mutableListOf<ProgramEvent>()
        val job = rig.scope.launchCollect(app.programRunner.events) { events += it }
        restBelt()
        assertEquals(StartOutcome.Started, rig.startProgram(plan, userId))
        job.cancel()
        return events.filterIsInstance<ProgramEvent.Started>().single().options.heartRateProfile
    }

    @Test fun theProfileHasTheEstimatedMaximumAndNoRestingWithoutMeasurements() {
        // The generic user is 40: Tanaka gives 180.
        assertEquals(HeartRateProfile(180, null), startedOptions())
    }

    @Test fun theRestingReferenceOfTheHistoryIsPassedToo() {
        runBlocking {
            for (daysAgo in 1..3) data.restingHeartRates.add(userId, RestingHeartRateInput(app.nowMillis() - daysAgo * 86_400_000L, 56 + daysAgo, RestingHeartRateSource.Manual))
        }
        assertEquals(HeartRateProfile(180, 58), startedOptions())
    }

    @Test fun theMaximumIsTheOneOfThePolicyNotTheEstimate() {
        // A maximal test measured 192 (valid, recent): it replaces the estimate.
        runBlocking { data.fitnessTests.save(userId, DemoPrograms.maxTest(startedAtMillis = app.nowMillis() - 5 * 86_400_000L, peakBpm = 192, ageYears = 40)) }
        val max = runBlocking { data.fitnessTests.heartRateMax(userId).first()!!.bpm }
        assertTrue(max != 180)
        assertEquals(HeartRateProfile(max, null), startedOptions())
    }

    @Test fun aGuidedBlockIsControlledNotUnavailable() {
        startedOptions()
        rig.advance(1_000)
        app.controller.state.value = TreadmillState(
            status = BeltStatus.Running, speedKmh = 6.0, targetSpeedKmh = 6.0, inclinePercent = 1.0, heartRateBpm = 110,
            updatedAtMillis = rig.scheduler.currentTime,
        )
        rig.advance(125_000)
        val control = app.programRunner.progress.value.heartRateControl
        assertNotNull(control)
        assertTrue(control!!.state != HeartRateControlState.Unavailable)
        assertEquals(108, control.zoneLowBpm)
        assertEquals(126, control.zoneHighBpm)
    }

    @Test fun resumingTheControlGoesThroughTheCoordinatorToTheRunner() {
        startedOptions()
        rig.advance(1_000)
        app.controller.state.value = TreadmillState(
            status = BeltStatus.Running, speedKmh = 6.0, targetSpeedKmh = 6.0, inclinePercent = 1.0, heartRateBpm = 110,
            updatedAtMillis = rig.scheduler.currentTime,
        )
        rig.advance(125_000)
        // Nothing to resume while the control is on.
        assertTrue(app.training.resumeHeartRateControl() is RunnerResult.NotAllowed)
        rig.adjustSpeed(7.0)
        rig.advance(300)
        assertEquals(HeartRateControlState.OffByManualChange, app.programRunner.progress.value.heartRateControl?.state)
        assertEquals(RunnerResult.Success, app.training.resumeHeartRateControl())
        rig.advance(300)
        assertTrue(app.programRunner.progress.value.heartRateControl?.state != HeartRateControlState.OffByManualChange)
    }
}
