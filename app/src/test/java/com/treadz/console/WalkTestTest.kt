package com.treadz.console

import com.treadz.console.fakes.GeneratedFixtures
import com.treadz.console.fakes.MainDispatcherRule
import com.treadz.console.fakes.SimTrainingRig
import com.treadz.console.fakes.TrainingRig
import com.treadz.console.programs.fitness.FitnessActionResult
import com.treadz.console.programs.fitness.FitnessRefusal
import com.treadz.console.programs.fitness.FitnessSubject
import com.treadz.console.programs.fitness.FitnessTestPhase
import com.treadz.console.programs.fitness.FitnessTestType
import com.treadz.console.programs.fitness.HeartRateSourceKind
import com.treadz.console.programs.fitness.MaxHeartRateWalkMeasurements
import com.treadz.console.programs.fitness.MaxHeartRateWalkParameters
import com.treadz.console.analysis.fitness.Sex
import com.treadz.console.programs.fitness.Validity
import com.treadz.console.training.FitnessResults
import com.treadz.console.training.FitnessStartViewModel
import com.treadz.console.training.RunViewModel
import com.treadz.console.training.StartOutcome
import com.treadz.console.training.TestBlocker
import com.treadz.console.treadmill.BeltStatus
import com.treadz.console.treadmill.CommandResult
import com.treadz.console.treadmill.TreadmillState
import com.treadz.console.userdata.memory.DemoData
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.StandardTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** The maximal heart-rate walk test (modified Balke): its start screen's logic and the coordinator around it. */
@OptIn(ExperimentalCoroutinesApi::class)
class WalkTestTest {

    @get:Rule val main = MainDispatcherRule()

    @After fun tearDown() = Dispatchers.resetMain()

    private val rig = TrainingRig()
    private val app get() = rig.app
    private val controller get() = app.controller
    private val ana by lazy { runBlocking { DemoData.populate(app.userData, app.date) }.first }

    private fun belt(heartRate: Int? = 72) {
        controller.state.value = TreadmillState(heartRateBpm = heartRate, updatedAtMillis = rig.scheduler.currentTime)
    }

    private fun viewModel(): FitnessStartViewModel {
        ana
        return FitnessStartViewModel(FitnessTestType.MaxHeartRateWalk, app.userData, app.training, controller, app::today, heartRate = app.heartRate)
    }

    private val FitnessStartViewModel.state get() = uiState.value

    // ---- Start screen ----

    @Test fun theRampStartsAtTheDefaultsWithTheWarmUpDerivedFromTheSpeed() {
        belt()
        val s = viewModel().state
        assertEquals("5.5", s.speedText)
        assertEquals("5", s.inclineText)
        assertEquals(3.0, s.speedMin, 0.0)
        assertEquals(7.5, s.speedMax, 0.0)
        assertEquals(0.0, s.inclineMin, 0.0)
        assertEquals(15.0, s.inclineMax, 0.0)
        // 75 % then 80 % of 5.5 km/h, to 0.1 km/h.
        assertEquals(4.1 to 4.4, s.warmUpSpeeds)
        assertTrue(s.atDefaults)
        assertNull(s.blocker)
        assertTrue(s.canStart)
    }

    @Test fun theValuesOfTheLastWalkTestAreSuggestedAndTheDefaultsButtonBringsBackTheOriginals() {
        belt()
        ana
        val result = GeneratedFixtures.balke(startedAtMillis = app.nowMillis() - 86_400_000L * 10, startSpeedKmh = 6.0, startInclinePercent = 7.0)
        runBlocking { app.userData.fitnessTests.save(ana.id, result) }
        assertEquals(6.0 to 7.0, FitnessResults.suggestedWalkStart(listOf(result), 5.5, 5.0))

        val vm = viewModel()
        assertEquals("6.0", vm.state.speedText)
        assertEquals("7", vm.state.inclineText)
        assertEquals(4.5 to 4.8, vm.state.warmUpSpeeds)
        assertTrue("the suggested values are the starting point", vm.state.atDefaults)

        vm.stepSpeed(+1)
        vm.stepIncline(-1)
        assertEquals("6.1", vm.state.speedText)
        assertEquals("6", vm.state.inclineText)
        assertFalse(vm.state.atDefaults)
        vm.restoreDefaults()
        assertEquals("6.0", vm.state.speedText)
        assertEquals("7", vm.state.inclineText)
        assertTrue(vm.state.atDefaults)
    }

    @Test fun theValuesAreCheckedAgainstTheTestsLimits() {
        belt()
        val vm = viewModel()
        vm.setSpeed("2.5")
        assertTrue(vm.state.speedInvalid)
        assertEquals(TestBlocker.InvalidSpeed, vm.state.blocker)
        assertNull(vm.state.warmUpSpeeds)
        vm.setSpeed("7.5")
        assertFalse(vm.state.speedInvalid)
        vm.setIncline("16")
        assertTrue(vm.state.inclineInvalid)
        assertEquals(TestBlocker.InvalidIncline, vm.state.blocker)
        vm.setIncline("4.5")
        assertTrue("a whole number of percent", vm.state.inclineInvalid)
        vm.setIncline("0")
        assertNull(vm.state.blocker)
        repeat(30) { vm.stepIncline(+1) }
        assertEquals("15", vm.state.inclineText)
        repeat(50) { vm.stepSpeed(-1) }
        assertEquals("3.0", vm.state.speedText)
    }

    @Test fun theTestNeedsAHeartRateAndShowsWhereItComesFrom() {
        belt(heartRate = null)
        val vm = viewModel()
        assertEquals(TestBlocker.NoHeartRate, vm.state.blocker)
        assertNull(vm.state.heartRateSource)

        belt(heartRate = 80)
        assertNull(vm.state.blocker)
        assertEquals(HeartRateSourceKind.TreadmillConsole, vm.state.heartRateSource)
        assertEquals(80, vm.state.heartRateBpm)

        // A strap connected to the app takes over, and the screen says so.
        app.strapMonitor.showConnected(bpm = 91)
        assertEquals(HeartRateSourceKind.DirectStrap, vm.state.heartRateSource)
        assertEquals(91, vm.state.heartRateBpm)
        assertNull(vm.state.blocker)
    }

    @Test fun startingRunsTheTestThroughTheCoordinatorAndLinksTheWorkoutToTheResult() {
        belt()
        val vm = viewModel()
        vm.setSpeed("6.0")
        vm.setIncline("6")
        var started = 0
        vm.start { started++ }
        rig.advance(200)

        assertEquals(1, started)
        val progress = app.fitnessRunner.progress.value
        assertEquals(FitnessTestType.MaxHeartRateWalk, progress.testType)
        assertEquals(FitnessTestPhase.WarmUp, progress.phase)
        assertEquals(6.0, progress.nextStageSpeedKmh!!, 0.001)
        assertEquals(6.0, progress.nextStageInclinePercent!!, 0.001)
        // The workout is linked to the test result, like the other tests.
        assertNotNull(app.links.linked.single().fitnessTestId)
    }

    @Test fun aSecondTestOrABeltInMotionBlocksTheStart() {
        belt()
        val vm = viewModel()
        controller.showState { it.copy(status = BeltStatus.Running, speedKmh = 4.0) }
        assertEquals(TestBlocker.BeltNotAtRest, vm.state.blocker)
        controller.showState { it.copy(status = BeltStatus.Idle, speedKmh = 0.0) }
        assertNull(vm.state.blocker)
    }

    @Test fun theRampStartIsAdjustedThroughTheCoordinatorAndTheAppsControlsStayLocked() {
        belt()
        val subject = FitnessSubject(ageYears = 39, sex = Sex.Female, weightKg = 66.0, hrMaxBpm = 183)
        assertEquals(StartOutcome.Started, rig.call { app.training.startMaxHeartRateWalk(1, MaxHeartRateWalkParameters(subject, 5.5, 5.0)) })

        // Out of range values are clamped by the runner; the new ramp start shows in the progress.
        assertEquals(FitnessActionResult.Success, app.training.adjustRampStart(6.5, 8.0))
        rig.advance(300)
        val progress = app.fitnessRunner.progress.value
        assertEquals(6.5, progress.nextStageSpeedKmh!!, 0.001)
        assertEquals(8.0, progress.nextStageInclinePercent!!, 0.001)
        assertEquals(FitnessActionResult.Success, app.training.adjustRampStart(null, 40.0))
        assertEquals(15.0, app.fitnessRunner.progress.value.nextStageInclinePercent!!, 0.001)

        // The app's speed and incline controls stay refused for a test; only STOP works.
        assertTrue(rig.call { app.training.commands.setSpeed(7.0) } is CommandResult.NotAllowed)
        assertTrue(rig.call { app.training.commands.setIncline(3.0) } is CommandResult.NotAllowed)
    }

    // ---- Run screen and result, on the simulated treadmill ----

    @Test fun aWalkTestRunsToAValidResultWhenTheUserIsExhaustedAfterThreeMinutesOfRamp() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val rig = SimTrainingRig(this)
        rig.connect()
        val userId = rig.userData.profiles.activeUserId()!!
        val subject = FitnessSubject(ageYears = 40, sex = Sex.Male, weightKg = 80.0, hrMaxBpm = 180)
        assertEquals(StartOutcome.Started, rig.training.startMaxHeartRateWalk(userId, MaxHeartRateWalkParameters(subject, 5.5, 5.0)))
        val id = checkNotNull(rig.links.linked.single().fitnessTestId)
        val vm = RunViewModel(rig.training, rig.userData)

        // Warm-up: 1 min at 75 % and 0 %, then 1 min at 80 % and 1 %. The ramp start can be changed with − / +.
        delay(5_000)
        assertEquals(FitnessTestPhase.WarmUp, vm.uiState.value.test.phase)
        vm.stepRampSpeed(+1)
        vm.stepRampIncline(+1)
        delay(1_000)
        assertEquals(5.6, vm.uiState.value.test.nextStageSpeedKmh!!, 0.001)
        assertEquals(6.0, vm.uiState.value.test.nextStageInclinePercent!!, 0.001)
        assertTrue(!rig.training.controls.value.speed) // the app's own speed controls are locked in this test
        delay(60_000)
        assertEquals(4.5, rig.sim.currentSpeedKmh, 0.2) // 80 % of 5.6 km/h

        // The ramp: +1 % of incline every minute. Exhaustion counts only after 3 minutes of it.
        rig.awaitTrue(message = "the ramp") { vm.uiState.value.test.phase == FitnessTestPhase.Incremental }
        // From here on the ramp start is fixed.
        val refused = rig.training.adjustRampStart(5.0, 5.0)
        assertTrue(refused is FitnessActionResult.NotAllowed && refused.refusal == FitnessRefusal.WrongState)
        delay(60_000)
        val test = vm.uiState.value.test
        assertEquals(7.0, test.actualInclinePercent, 1.0)
        assertTrue(test.canReportExhaustion)
        assertTrue("the screen waits for 3 minutes of ramp (${test.phaseElapsedSeconds} s so far)", test.phaseElapsedSeconds < 180)
        delay(130_000)
        assertTrue(vm.uiState.value.test.phaseElapsedSeconds >= 180)
        vm.exhausted()

        rig.awaitTrue(message = "the end of the test") { vm.uiState.value.test.phase == FitnessTestPhase.Finished }
        delay(1_000)
        val stored = checkNotNull(rig.userData.fitnessTests.get(id)).result
        assertEquals(FitnessTestType.MaxHeartRateWalk, stored.testType)
        assertEquals(Validity.Valid, stored.validity)
        assertNull(stored.outputs) // no VO2max, no MAS
        val measurements = stored.measurements as MaxHeartRateWalkMeasurements
        assertEquals(5.6, measurements.startSpeedKmh, 0.001)
        assertEquals(6.0, measurements.startInclinePercent, 0.001)
        assertTrue(measurements.rampSeconds >= 180)
        assertTrue("effort ${stored.effortAssessment}", stored.effortAssessment != null)
        assertNotNull(vm.uiState.value.heartRateMax)
        assertTrue(rig.sim.forbiddenCommands.isEmpty())
        vm.dismiss()
    }

    @Test fun stopDuringTheRampBeforeThreeMinutesInvalidatesTheTestAndEndsInTheNormalWay() = runTest {
        Dispatchers.setMain(StandardTestDispatcher(testScheduler))
        val rig = SimTrainingRig(this)
        rig.connect()
        val userId = rig.userData.profiles.activeUserId()!!
        val subject = FitnessSubject(ageYears = 40, sex = Sex.Male, weightKg = 80.0, hrMaxBpm = 180)
        assertEquals(StartOutcome.Started, rig.training.startMaxHeartRateWalk(userId, MaxHeartRateWalkParameters(subject)))
        val id = checkNotNull(rig.links.linked.single().fitnessTestId)
        rig.awaitTrue(message = "the ramp") { rig.fitnessRunner.progress.value.phase == FitnessTestPhase.Incremental }
        delay(30_000)

        assertEquals(CommandResult.Success, rig.training.commands.stop())
        delay(5_000)
        val stored = checkNotNull(rig.userData.fitnessTests.get(id)).result
        assertEquals(Validity.Invalid, stored.validity)
        assertEquals(FitnessTestPhase.Finished, rig.fitnessRunner.progress.value.phase)
    }
}
