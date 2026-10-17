package com.treadz.console

import com.treadz.console.controls.ControlsNote
import com.treadz.console.controls.SessionKind
import com.treadz.console.fakes.MainDispatcherRule
import com.treadz.console.fakes.TrainingRig
import com.treadz.console.fakes.launchCollect
import com.treadz.console.programs.engine.InterruptionReason
import com.treadz.console.programs.engine.ManualChangeSource
import com.treadz.console.programs.engine.ProgramEvent
import com.treadz.console.programs.engine.Refusal
import com.treadz.console.programs.engine.RunOutcome
import com.treadz.console.programs.engine.RunState
import com.treadz.console.programs.fitness.EbbelingParameters
import com.treadz.console.programs.fitness.FitnessSubject
import com.treadz.console.programs.fitness.FitnessTestFlag
import com.treadz.console.programs.fitness.FitnessTestPhase
import com.treadz.console.programs.fitness.FitnessTestType
import com.treadz.console.analysis.fitness.Sex
import com.treadz.console.programs.fitness.Validity
import com.treadz.console.training.StartOutcome
import com.treadz.console.treadmill.BeltStatus
import com.treadz.console.treadmill.CommandResult
import com.treadz.console.treadmill.ConnectionState
import com.treadz.console.treadmill.FailureCode
import com.treadz.console.treadmill.TreadmillState
import com.treadz.console.treadmill.control.CommandOrigin
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * The coordinator: starting a program or a fitness test (the link to the workout, the remembered parameters), where the
 * manual controls' commands go while one is running, and what the screens may show. Runs the real runners on the fake
 * treadmill in virtual time.
 */
class TrainingCoordinatorTest {

    @get:Rule val main = MainDispatcherRule()

    private val rig = TrainingRig()
    private val app get() = rig.app
    private val controller get() = app.controller
    private val training get() = app.training
    private val userId get() = runBlocking { app.userData.profiles.activeUserId()!! }

    /** The belt at rest, connected, with a heart rate (the strap is on). */
    private fun restingBelt(heartRate: Int? = 72) {
        controller.state.value = TreadmillState(heartRateBpm = heartRate, updatedAtMillis = rig.scheduler.currentTime)
    }

    private fun startPyramid(): com.treadz.console.programs.plan.ResolvedPlan {
        val plan = rig.standardPlan("standard-pyramid")
        assertEquals(StartOutcome.Started, rig.startProgram(plan, userId))
        return plan
    }

    private val subject = FitnessSubject(ageYears = 40, sex = Sex.Male, weightKg = 80.0, hrMaxBpm = 180)

    private fun startEbbeling(): StartOutcome = rig.call { training.startEbbeling(userId, EbbelingParameters(subject, 4.8)) }

    // ---- Starting a program ----

    @Test fun startingAProgramLinksThePlanAndRemembersTheParameters() {
        restingBelt()
        val plan = startPyramid()

        assertEquals(plan, app.links.linked.single().plan)
        assertEquals(0, app.links.clears)
        assertEquals(plan.parameters, runBlocking { app.userData.startParameters.lastUsed(userId, "standard-pyramid").first() })
        assertEquals(RunState.Running, app.programRunner.progress.value.state)
        assertEquals(SessionKind.Program, training.visible.value)
    }

    @Test fun aRefusedStartWithdrawsTheLinkAndRemembersNothing() {
        controller.connection.value = ConnectionState.Disconnected
        val outcome = rig.startProgram(rig.standardPlan("standard-pyramid"), userId)

        assertEquals(Refusal.NotConnected, (outcome as StartOutcome.ProgramRefused).refusal)
        assertEquals(1, app.links.clears)
        assertNull(runBlocking { app.userData.startParameters.lastUsed(userId, "standard-pyramid").first() })
        assertEquals(RunState.Idle, app.programRunner.progress.value.state)
        assertEquals(SessionKind.None, training.visible.value)
    }

    @Test fun aFailedBeltStartWithdrawsTheLinkToo() {
        restingBelt()
        controller.startResult = CommandResult.Rejected(FailureCode.RejectedByTreadmill, "no")
        val outcome = rig.startProgram(rig.standardPlan("standard-pyramid"), userId)

        assertTrue(outcome is StartOutcome.CommandFailed)
        assertEquals(1, app.links.clears)
        assertNull(runBlocking { app.userData.startParameters.lastUsed(userId, "standard-pyramid").first() })
    }

    @Test fun aSecondProgramCannotStartWhileOneIsActive() {
        restingBelt()
        startPyramid()
        val linksBefore = app.links.linked.size
        assertEquals(StartOutcome.Busy, rig.startProgram(rig.standardPlan("standard-hill"), userId))
        assertEquals(linksBefore, app.links.linked.size)
    }

    // ---- Routing of the manual controls ----

    @Test fun withoutASessionTheCommandsGoStraightToTheController() {
        restingBelt()
        controller.showRunning()
        rig.call { training.commands.setSpeed(9.0) }
        rig.call { training.commands.setIncline(4.0) }
        rig.call { training.commands.pause() }
        rig.call { training.commands.resume() }
        rig.call { training.commands.stop() }

        assertEquals(listOf(9.0), controller.speedCommands)
        assertEquals(listOf(4.0), controller.inclineCommands)
        assertEquals(1, controller.pauseCalls)
        assertEquals(1, controller.resumeCalls)
        assertEquals(1, controller.stopCalls)
        assertEquals(RunState.Idle, app.programRunner.progress.value.state)
    }

    @Test fun adjustmentsDuringAProgramGoThroughTheRunner() {
        restingBelt()
        startPyramid()
        val events = mutableListOf<ProgramEvent>()
        val job = rig.scope.launchCollect(app.programRunner.events) { events += it }

        assertEquals(CommandResult.Success, rig.call { training.commands.setSpeed(9.5) })
        assertEquals(CommandResult.Success, rig.call { training.commands.setIncline(3.0) })

        val progress = app.programRunner.progress.value
        assertTrue(progress.manualOverride)
        assertEquals(9.5, progress.targetSpeedKmh!!, 0.001)
        assertEquals(3.0, progress.targetInclinePercent!!, 0.001)
        val changes = events.filterIsInstance<ProgramEvent.ManualChange>()
        assertTrue(changes.isNotEmpty())
        assertTrue(changes.all { it.source == ManualChangeSource.App })
        job.cancel()
    }

    @Test fun pauseAndResumeDuringAProgramGoThroughTheRunner() {
        restingBelt()
        startPyramid()

        assertEquals(CommandResult.Success, rig.call { training.commands.pause() })
        assertEquals(RunState.Paused, app.programRunner.progress.value.state)
        assertEquals(CommandResult.Success, rig.call { training.commands.resume() })
        assertEquals(RunState.Running, app.programRunner.progress.value.state)
        assertEquals(RunState.Running, app.programRunner.progress.value.state)
    }

    @Test fun stopDuringAProgramEndsItThroughTheRunner() {
        restingBelt()
        startPyramid()
        val stopsBefore = controller.stopCalls

        assertEquals(CommandResult.Success, rig.call { training.commands.stop() })

        val progress = app.programRunner.progress.value
        assertEquals(RunState.Finished, progress.state)
        assertEquals(RunOutcome.StoppedByUser, progress.outcome)
        assertEquals(stopsBefore + 1, controller.stopCalls)
        // The result stays on screen until the user closes it.
        assertEquals(SessionKind.Program, training.visible.value)
        training.dismissResult()
        rig.advance(1)
        assertEquals(SessionKind.None, training.visible.value)
    }

    @Test fun afterTheProgramEndedTheStopGoesStraightToTheController() {
        restingBelt()
        startPyramid()
        rig.call { training.commands.stop() }
        val stops = controller.stopCalls
        controller.showRunning()

        rig.call { training.commands.stop() }
        assertEquals(stops + 1, controller.stopCalls)
        assertEquals(RunOutcome.StoppedByUser, app.programRunner.progress.value.outcome)
    }

    @Test fun theNotificationsStopEndsAProgramThroughTheRunner() {
        restingBelt()
        startPyramid()
        controller.showRunning()
        rig.advance(1_000)
        val stopsBefore = controller.stopCalls

        assertEquals(CommandResult.Success, rig.call { training.stopFromNotification() })
        rig.advance(1_000)

        val progress = app.programRunner.progress.value
        assertEquals(RunState.Finished, progress.state)
        assertEquals(RunOutcome.StoppedByUser, progress.outcome)
        assertEquals(stopsBefore + 1, controller.stopCalls)
        assertEquals(CommandOrigin.Notification, controller.stopOrigins.last())
        assertEquals(SessionKind.Program, training.visible.value)
    }

    @Test fun withoutASessionTheNotificationsStopGoesStraightToTheController() {
        controller.showRunning()

        assertEquals(CommandResult.Success, rig.call { training.stopFromNotification() })

        assertEquals(1, controller.stopCalls)
        assertEquals(CommandOrigin.Notification, controller.stopOrigins.single())
    }

    @Test fun aStopFromOutsideTheAppInterruptsTheProgram() {
        restingBelt()
        startPyramid()
        controller.showRunning()
        rig.advance(1_000)

        // The console's STOP: the belt stops without the app asking.
        rig.call { controller.stop() }
        rig.advance(1_000)

        val progress = app.programRunner.progress.value
        assertEquals(RunState.Interrupted, progress.state)
        assertEquals(InterruptionReason.BeltChangedExternally, progress.interruption?.reason)
        val controls = training.controls.value
        assertFalse(controls.speed)
        assertFalse(controls.incline)
        assertFalse(controls.pause)
        assertEquals(ControlsNote.ProgramInterrupted, controls.note)
    }

    @Test fun anInterruptedProgramRefusesToContinueWhileTheKeyIsOut() {
        restingBelt()
        startPyramid()
        controller.showRunning()
        controller.showState { it.copy(status = BeltStatus.SafetyKeyRemoved, speedKmh = 0.0) }
        rig.advance(1_000)
        assertEquals(RunState.Interrupted, app.programRunner.progress.value.state)

        val result = rig.call { app.programRunner.continueProgram() }
        assertEquals(Refusal.SafetyKeyRemoved, (result as com.treadz.console.programs.engine.RunnerResult.NotAllowed).refusal)
        assertEquals(RunState.Interrupted, app.programRunner.progress.value.state)
    }

    // ---- Fitness tests ----

    @Test fun aTestNeedsTheBeltAtRest() {
        controller.showRunning()
        assertEquals(StartOutcome.Busy, startEbbeling())
        assertTrue(app.links.linked.isEmpty())
    }

    @Test fun startingATestLinksTheWorkoutToTheResultIdBeforeTheBeltMoves() {
        restingBelt()
        val outcome = startEbbeling()

        assertEquals(StartOutcome.Started, outcome)
        val link = app.links.linked.single()
        assertNotNull(link.fitnessTestId)
        assertNull(link.plan)
        assertEquals(FitnessTestType.EbbelingWalk, app.fitnessRunner.progress.value.testType)
        assertEquals(SessionKind.FitnessTest, training.visible.value)
        // The runner used the pre-generated id for its result.
        rig.advance(2_000)
        controller.showState { it.copy(heartRateBpm = 100, updatedAtMillis = rig.scheduler.currentTime) }
    }

    @Test fun aRefusedTestStartWithdrawsTheLink() {
        restingBelt(heartRate = null) // no strap
        val outcome = startEbbeling()

        assertEquals(com.treadz.console.programs.fitness.FitnessRefusal.NoHeartRate, (outcome as StartOutcome.TestRefused).refusal)
        assertEquals(1, app.links.clears)
        assertEquals(SessionKind.None, training.visible.value)
        // The next test gets a fresh id: the reserved one was released.
        restingBelt()
        assertEquals(StartOutcome.Started, startEbbeling())
        assertEquals(2, app.links.linked.size)
        assertTrue(app.links.linked[0].fitnessTestId != app.links.linked[1].fitnessTestId)
    }

    @Test fun duringATestOnlyTheSpeedOfTheFirstStageAndStopAreAllowed() {
        restingBelt()
        assertEquals(StartOutcome.Started, startEbbeling())
        controller.showState { it.copy(status = BeltStatus.Running, speedKmh = 4.8, targetSpeedKmh = 4.8, heartRateBpm = 100, updatedAtMillis = rig.scheduler.currentTime) }
        rig.advance(5_000)

        val controls = training.controls.value
        assertEquals(SessionKind.FitnessTest, controls.kind)
        assertTrue(controls.speed)
        assertFalse(controls.incline)
        assertFalse(controls.pause)
        assertEquals(ControlsNote.TestSpeedOnly, controls.note)

        assertTrue(rig.call { training.commands.pause() } is CommandResult.NotAllowed)
        assertTrue(rig.call { training.commands.resume() } is CommandResult.NotAllowed)
        assertTrue(rig.call { training.commands.setIncline(3.0) } is CommandResult.NotAllowed)
        assertEquals(0, controller.pauseCalls)
        assertTrue(controller.inclineCommands.isEmpty())

        assertEquals(CommandResult.Success, rig.call { training.commands.setSpeed(5.2) })
        rig.advance(1_000)
        assertEquals(5.2, app.fitnessRunner.progress.value.targetSpeedKmh!!, 0.001)
    }

    @Test fun stopDuringATestStopsTheBeltAndTheResultIsStoredAsInvalid() {
        restingBelt()
        assertEquals(StartOutcome.Started, startEbbeling())
        controller.showState { it.copy(status = BeltStatus.Running, speedKmh = 4.8, targetSpeedKmh = 4.8, heartRateBpm = 100, updatedAtMillis = rig.scheduler.currentTime) }
        rig.advance(30_000)

        assertEquals(CommandResult.Success, rig.call { training.commands.stop() })
        rig.advance(1_000)

        val progress = app.fitnessRunner.progress.value
        assertEquals(FitnessTestPhase.Finished, progress.phase)
        assertEquals(1, controller.stopCalls)
        val id = app.links.linked.single().fitnessTestId!!
        val stored = runBlocking { app.userData.fitnessTests.get(id) }
        assertNotNull("the result is stored under the id the workout was linked to", stored)
        assertEquals(Validity.Invalid, stored!!.result.validity)
        assertTrue(FitnessTestFlag.StoppedByUser in stored.result.flags)
        assertEquals(userId, stored.userId)
        assertFalse(training.saveFailed.value)
        // The result stays up until the user closes it.
        assertEquals(SessionKind.FitnessTest, training.visible.value)
        training.dismissResult()
        rig.advance(1)
        assertEquals(SessionKind.None, training.visible.value)
    }

    @Test fun theNotificationsStopEndsATestThroughTheRunner() {
        restingBelt()
        assertEquals(StartOutcome.Started, startEbbeling())
        controller.showState { it.copy(status = BeltStatus.Running, speedKmh = 4.8, targetSpeedKmh = 4.8, heartRateBpm = 100, updatedAtMillis = rig.scheduler.currentTime) }
        rig.advance(30_000)

        assertEquals(CommandResult.Success, rig.call { training.stopFromNotification() })
        rig.advance(1_000)

        val progress = app.fitnessRunner.progress.value
        assertEquals(FitnessTestPhase.Finished, progress.phase)
        assertTrue(FitnessTestFlag.StoppedByUser in progress.result!!.flags)
        assertEquals(1, controller.stopCalls)
        assertEquals(CommandOrigin.Notification, controller.stopOrigins.single())
        assertNotNull(runBlocking { app.userData.fitnessTests.get(progress.result!!.id) })
    }

    @Test fun aStopFromOutsideTheAppEndsATestAsInvalidAndTheResultIsStillStored() {
        restingBelt()
        assertEquals(StartOutcome.Started, startEbbeling())
        controller.showState { it.copy(status = BeltStatus.Running, speedKmh = 4.8, targetSpeedKmh = 4.8, heartRateBpm = 100, updatedAtMillis = rig.scheduler.currentTime) }
        rig.advance(30_000)

        // The console's STOP: the belt stops without the app asking.
        rig.call { controller.stop() }
        rig.advance(2_000)

        val progress = app.fitnessRunner.progress.value
        assertEquals(FitnessTestPhase.Finished, progress.phase)
        assertEquals(Validity.Invalid, progress.result!!.validity)
        assertEquals(com.treadz.console.programs.fitness.FitnessEndReason.BeltStoppedExternally, progress.result!!.endReason)
        assertEquals(SessionKind.FitnessTest, training.visible.value) // shown until the user closes it
        assertNotNull(runBlocking { app.userData.fitnessTests.get(progress.result!!.id) })
    }

    @Test fun aNewRunShowsItsScreenAgainEvenIfThePreviousResultWasClosed() {
        restingBelt()
        startPyramid()
        rig.call { training.commands.stop() }
        training.dismissResult()
        rig.advance(1)
        assertEquals(SessionKind.None, training.visible.value)

        controller.state.value = TreadmillState(updatedAtMillis = rig.scheduler.currentTime)
        assertEquals(StartOutcome.Started, rig.startProgram(rig.standardPlan("standard-hill"), userId))
        assertEquals(SessionKind.Program, training.visible.value)
    }
}
