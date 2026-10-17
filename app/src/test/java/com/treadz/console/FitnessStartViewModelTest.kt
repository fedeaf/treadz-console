package com.treadz.console

import com.treadz.console.fakes.MainDispatcherRule
import com.treadz.console.fakes.TrainingRig
import com.treadz.console.programs.fitness.FitnessRefusal
import com.treadz.console.programs.fitness.FitnessTestPhase
import com.treadz.console.programs.fitness.FitnessTestType
import com.treadz.console.programs.fitness.HeartRateMaxOrigin
import com.treadz.console.analysis.fitness.Sex
import com.treadz.console.training.FitnessStartViewModel
import com.treadz.console.training.StartOutcome
import com.treadz.console.training.TestBlocker
import com.treadz.console.treadmill.BeltStatus
import com.treadz.console.treadmill.ConnectionState
import com.treadz.console.treadmill.SafetyLimits
import com.treadz.console.treadmill.TreadmillState
import com.treadz.console.userdata.memory.DemoData
import com.treadz.console.userdata.memory.DemoPrograms
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.time.ZoneId

class FitnessStartViewModelTest {

    @get:Rule val main = MainDispatcherRule()

    private val rig = TrainingRig()
    private val app get() = rig.app
    private val controller get() = app.controller
    private val ana by lazy { runBlocking { DemoData.populate(app.userData, app.date) }.first }

    private fun belt(heartRate: Int? = 72) {
        controller.state.value = TreadmillState(heartRateBpm = heartRate, updatedAtMillis = rig.scheduler.currentTime)
    }

    private fun viewModel(type: FitnessTestType): FitnessStartViewModel {
        ana
        return FitnessStartViewModel(type, app.userData, app.training, controller, app::today)
    }

    private val FitnessStartViewModel.state get() = uiState.value

    // ---- What is shown ----

    @Test fun theSubjectComesFromTheProfileAndTheMeasuredMaximumHeartRate() {
        belt()
        val s = viewModel(FitnessTestType.EbbelingWalk).state
        assertEquals(ana.id, s.user!!.id)
        assertEquals(39, s.ageYears)
        assertEquals(runBlocking { app.userData.weights.latestWeight(ana.id).first() }!!.weightKg, s.weight!!.weightKg, 0.0)
        // Ana set no maximum: the one measured by her maximal test counts, not the estimate from her age.
        assertEquals(HeartRateMaxOrigin.Measured, s.heartRateMax!!.origin)
        assertEquals(183, s.heartRateMax!!.bpm)
        assertEquals(72, s.heartRateBpm)
    }

    @Test fun theStartSpeedIsTheSpeedOfTheLastTestOfThatTypeOrTheDefault() {
        belt()
        assertEquals("4.8", viewModel(FitnessTestType.EbbelingWalk).state.speedText) // Ana's Ebbeling test was at 4.8
        assertEquals("8.0", viewModel(FitnessTestType.MaxIncrementalRun).state.speedText)

        val started = app.date.minusDays(2).atTime(18, 0).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()
        runBlocking {
            app.userData.fitnessTests.save(
                ana.id,
                DemoPrograms.ebbeling(id = "0b6f8f9e-4d0c-4d43-9f43-2f0c6f1a7b21", startedAtMillis = started, ageYears = 39, sex = Sex.Female).let {
                    it.copy(measurements = (it.measurements as com.treadz.console.programs.fitness.EbbelingMeasurements).copy(testSpeedKmh = 5.4))
                },
            )
        }
        assertEquals("5.4", viewModel(FitnessTestType.EbbelingWalk).state.speedText)
    }

    @Test fun restoreDefaultsPutsBackTheSuggestedSpeed() {
        belt()
        val vm = viewModel(FitnessTestType.EbbelingWalk)
        assertTrue(vm.state.atDefaults)
        vm.stepSpeed(+1)
        assertFalse(vm.state.atDefaults)
        vm.restoreDefaults()
        assertEquals("4.8", vm.state.speedText) // Ana's last Ebbeling test
        assertTrue(vm.state.atDefaults)
    }

    @Test fun theSpeedIsCheckedAgainstTheTestsRange() {
        belt()
        val vm = viewModel(FitnessTestType.EbbelingWalk)
        assertEquals(3.2, vm.state.speedMin, 0.0)
        assertEquals(7.2, vm.state.speedMax, 0.0)
        vm.setSpeed("9")
        assertTrue(vm.state.speedInvalid)
        assertEquals(TestBlocker.InvalidSpeed, vm.state.blocker)
        vm.setSpeed("abc")
        assertTrue(vm.state.speedInvalid)
        vm.setSpeed("5,5")
        assertFalse(vm.state.speedInvalid)
        repeat(30) { vm.stepSpeed(+1) }
        assertEquals("7.2", vm.state.speedText)
        repeat(60) { vm.stepSpeed(-1) }
        assertEquals("3.2", vm.state.speedText)

        val max = viewModel(FitnessTestType.MaxIncrementalRun)
        assertEquals(6.0, max.state.speedMin, 0.0)
        assertEquals(10.0, max.state.speedMax, 0.0)
    }

    // ---- Why START may be disabled ----

    @Test fun ebbelingNeedsAHeartRateButTheMaximalTestStartsWithoutOne() {
        belt(heartRate = null)
        assertEquals(TestBlocker.NoHeartRate, viewModel(FitnessTestType.EbbelingWalk).state.blocker)
        assertNull(viewModel(FitnessTestType.MaxIncrementalRun).state.blocker)
        belt(heartRate = 72)
        assertNull(viewModel(FitnessTestType.EbbelingWalk).state.blocker)
    }

    @Test fun theBeltMustBeAtRestAndConnectedAndThereMustBeAUser() {
        belt()
        val vm = viewModel(FitnessTestType.EbbelingWalk)
        assertTrue(vm.state.canStart)

        controller.showState { it.copy(status = BeltStatus.Running, speedKmh = 6.0) }
        assertEquals(TestBlocker.BeltNotAtRest, vm.state.blocker)
        controller.showState { it.copy(status = BeltStatus.Idle, speedKmh = 2.0) }
        assertEquals(TestBlocker.BeltNotAtRest, vm.state.blocker)
        controller.showState { it.copy(status = BeltStatus.Paused, speedKmh = 0.0) }
        assertEquals(TestBlocker.BeltNotAtRest, vm.state.blocker)
        belt()

        controller.connection.value = ConnectionState.Disconnected
        assertEquals(TestBlocker.NotConnected, vm.state.blocker)
        controller.showConnected()
        assertNull(vm.state.blocker)

        runBlocking { app.userData.profiles.setActiveUser(null) }
        assertEquals(TestBlocker.NoUser, vm.state.blocker)
    }

    // ---- Starting ----

    @Test fun startingRunsTheTestWithTheSubjectOfTheProfileAndLinksTheWorkoutToItsResult() {
        belt()
        val vm = viewModel(FitnessTestType.EbbelingWalk)
        var started = 0
        vm.start { started++ }
        rig.advance(100)

        assertEquals(1, started)
        assertNull(vm.state.failure)
        val link = app.links.linked.single()
        assertNotNull(link.fitnessTestId)
        assertEquals(FitnessTestType.EbbelingWalk, app.fitnessRunner.progress.value.testType)

        // Stopping it stores the result with the subject that was used.
        controller.showState { it.copy(status = BeltStatus.Running, speedKmh = 4.8, targetSpeedKmh = 4.8, updatedAtMillis = rig.scheduler.currentTime) }
        rig.advance(5_000)
        rig.call { app.training.commands.stop() }
        rig.advance(1_000)
        assertEquals(FitnessTestPhase.Finished, app.fitnessRunner.progress.value.phase)
        val stored = checkNotNull(runBlocking { app.userData.fitnessTests.get(checkNotNull(link.fitnessTestId)) })
        assertEquals(ana.id, stored.userId)
        assertEquals(39, stored.result.inputs.ageYears)
        assertEquals(Sex.Female, stored.result.inputs.sex)
        assertEquals(183, stored.result.inputs.hrMaxBpm)
        assertEquals(4.8, stored.result.inputs.startSpeedKmh, 0.0)
    }

    @Test fun theMaximalTestStartsAtTheChosenSpeed() {
        belt()
        val vm = viewModel(FitnessTestType.MaxIncrementalRun)
        vm.setSpeed("7.5")
        vm.start { }
        rig.advance(100)
        val p = app.fitnessRunner.progress.value
        assertEquals(FitnessTestType.MaxIncrementalRun, p.testType)
        assertEquals(FitnessTestPhase.WarmUp, p.phase)
        assertNotNull(app.links.linked.single().fitnessTestId)
    }

    @Test fun aRefusalIsShownAndTheLinkWithdrawn() {
        belt()
        controller.setLimits(SafetyLimits(maxInclinePercent = 3.0)) // Ebbeling needs 5 %
        val vm = viewModel(FitnessTestType.EbbelingWalk)
        var started = 0
        vm.start { started++ }
        rig.advance(100)

        assertEquals(0, started)
        assertFalse(vm.state.starting)
        val failure = vm.state.failure as StartOutcome.TestRefused
        assertEquals(FitnessRefusal.LimitsTooLow, failure.refusal)
        assertEquals(1, app.links.clears)
        assertEquals(FitnessTestPhase.Idle, app.fitnessRunner.progress.value.phase)
        vm.setSpeed("5.0")
        assertNull(vm.state.failure) // editing clears the message
    }

    @Test fun startDoesNothingWhileItIsBlocked() {
        belt(heartRate = null)
        val vm = viewModel(FitnessTestType.EbbelingWalk)
        vm.start { error("must not start") }
        rig.advance(100)
        assertTrue(app.links.linked.isEmpty())
    }
}
