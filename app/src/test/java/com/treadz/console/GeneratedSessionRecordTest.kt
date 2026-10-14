package com.treadz.console

import com.treadz.console.fakes.MainDispatcherRule
import com.treadz.console.fakes.TrainingRig
import com.treadz.console.programs.model.Block
import com.treadz.console.programs.model.BlockEnd
import com.treadz.console.programs.model.BlockPhase
import com.treadz.console.programs.model.BlockTarget
import com.treadz.console.programs.model.Program
import com.treadz.console.programs.model.ProgramKind
import com.treadz.console.programs.model.ProgramOrigin
import com.treadz.console.programs.personalised.CouchToFiveK
import com.treadz.console.programs.personalised.SessionOutcome
import com.treadz.console.programs.plan.ProgramResolver
import com.treadz.console.programs.plan.ResolvedPlan
import com.treadz.console.training.StartOutcome
import com.treadz.console.treadmill.BeltStatus
import com.treadz.console.treadmill.ConnectionState
import com.treadz.console.treadmill.TreadmillState
import com.treadz.console.userdata.WorkoutSample
import com.treadz.console.userdata.WorkoutStats
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** The record of a generated session is stored with its workout when the program finishes: what makes Couch to 5K advance. */
class GeneratedSessionRecordTest {

    @get:Rule val main = MainDispatcherRule()

    private val rig = TrainingRig()
    private val app get() = rig.app
    private val training get() = app.training
    private val userId get() = runBlocking { app.userData.profiles.activeUserId()!! }

    private fun restingBelt() {
        app.controller.state.value = TreadmillState(heartRateBpm = 72, updatedAtMillis = rig.scheduler.currentTime)
    }

    /** A workout of the active user that the coordinator attaches the record to. */
    private fun workoutInProgress(): Long {
        val samples = listOf(
            WorkoutSample(
                seq = 0, timestampMillis = 1_000, elapsedSeconds = 0, speedKmh = 8.0, targetSpeedKmh = 8.0, inclinePercent = 1.0,
                distanceMeters = 0, calories = 0, heartRateBpm = 150, beltStatus = BeltStatus.Running,
            ),
        )
        val id = app.userData.addWorkout(userId, WorkoutStats(durationSeconds = 600, distanceMeters = 1_000), samples, 1_000)
        app.links.workoutId = id
        return id
    }

    private fun generatedPlan(generatorId: String = CouchToFiveK.ID): ResolvedPlan {
        val program = Program(
            id = "6a1f1f84-1b22-4f4d-8f07-0a6f5f2a9a01", name = "Generated", kind = ProgramKind.Personalised,
            steps = listOf(
                Block(BlockPhase.WarmUp, BlockEnd.Duration(60), BlockTarget.Absolute(5.0, 1.0)),
                Block(BlockPhase.Work, BlockEnd.Duration(120), BlockTarget.Absolute(8.0, 1.0)),
            ),
            origin = ProgramOrigin(generatorId, 1, mapOf("session" to "7", "mode" to "run")),
        )
        return ProgramResolver.resolve(program)
    }

    private fun stop() {
        rig.call { training.commands.stop() }
        rig.advance(100)
    }

    private fun record(workoutId: Long) = runBlocking { app.userData.workouts.detail(workoutId) }?.sessionRecord

    @Test fun theRecordIsStoredWithTheWorkoutWhenTheProgramFinishes() {
        restingBelt()
        val workout = workoutInProgress()
        val plan = generatedPlan()
        assertEquals(StartOutcome.Started, rig.startProgram(plan, userId))
        rig.advance(30_000)
        assertNull("nothing yet: the program is still running", record(workout))

        stop()

        val stored = checkNotNull(record(workout))
        assertEquals(CouchToFiveK.ID, stored.generatorId)
        assertEquals("7", stored.origin.inputs["session"])
        assertEquals(SessionOutcome.Stopped, stored.outcome)
        assertEquals(plan.source.programId, stored.programId)
        assertEquals(plan.blocks.size, stored.workBlocksPlanned + 1) // a warm-up and one work block
        assertEquals(1, stored.workBlocksPlanned)
        assertEquals(0, stored.workBlocksCompleted)
        // The workout is the generator's now: the history can show it by origin and the generators read the record.
        val summary = checkNotNull(runBlocking { app.userData.workouts.detail(workout) }).summary
        assertEquals(CouchToFiveK.ID, summary.generatorId)
        assertEquals(1, runBlocking { app.userData.workouts.sessionRecords(userId, CouchToFiveK.ID).first() }.size)
        assertTrue(app.trainingErrors.isEmpty())
    }

    @Test fun aProgramWithoutAnOriginLeavesNoRecord() {
        restingBelt()
        val workout = workoutInProgress()
        assertEquals(StartOutcome.Started, rig.startProgram(rig.standardPlan("standard-pyramid"), userId))
        rig.advance(30_000)
        stop()

        assertNull(record(workout))
        assertTrue(runBlocking { app.userData.workouts.sessionRecords(userId, CouchToFiveK.ID).first() }.isEmpty())
        assertTrue(app.trainingErrors.isEmpty())
    }

    @Test fun aRefusedStartLeavesNoRecordAndNoStaleListenerForTheNextProgram() {
        app.controller.connection.value = ConnectionState.Disconnected
        val refused = rig.startProgram(generatedPlan(), userId)
        assertTrue(refused is StartOutcome.ProgramRefused)

        app.controller.showConnected()
        restingBelt()
        val workout = workoutInProgress()
        assertEquals(StartOutcome.Started, rig.startProgram(rig.standardPlan("standard-pyramid"), userId))
        rig.advance(10_000)
        stop()

        // The listener of the refused start was withdrawn: the next program, which has no origin, is not recorded.
        assertNull(record(workout))
        assertTrue(app.trainingErrors.isEmpty())
    }

    @Test fun aMissingWorkoutIsReportedAndNeverBreaksTheRun() {
        restingBelt()
        app.links.workoutId = null
        assertEquals(StartOutcome.Started, rig.startProgram(generatedPlan(), userId))
        rig.advance(10_000)
        stop()

        assertEquals(1, app.trainingErrors.size)
        assertEquals(com.treadz.console.programs.engine.RunState.Finished, app.programRunner.progress.value.state)
    }

    @Test fun twoGeneratedSessionsOneAfterTheOtherGetTheirOwnRecords() {
        restingBelt()
        val first = workoutInProgress()
        assertEquals(StartOutcome.Started, rig.startProgram(generatedPlan(), userId))
        rig.advance(10_000)
        stop()
        training.dismissResult()

        restingBelt()
        val second = workoutInProgress()
        assertEquals(StartOutcome.Started, rig.startProgram(generatedPlan(), userId))
        rig.advance(10_000)
        stop()

        assertNotNull(record(first))
        assertNotNull(record(second))
        assertEquals(2, runBlocking { app.userData.workouts.sessionRecords(userId, CouchToFiveK.ID).first() }.size)
    }
}
