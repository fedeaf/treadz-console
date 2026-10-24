package com.treadz.console

import com.treadz.console.fakes.GeneratedFixtures
import com.treadz.console.fakes.PerformanceFixtures
import com.treadz.console.fakes.MainDispatcherRule
import com.treadz.console.fakes.TrainingRig
import com.treadz.console.programs.engine.RunState
import com.treadz.console.programs.heartrate.HeartRateZones
import com.treadz.console.programs.model.Block
import com.treadz.console.programs.model.BlockTarget
import com.treadz.console.programs.model.ProgramKind
import com.treadz.console.programs.personalised.CouchToFiveK
import com.treadz.console.programs.personalised.CouchToFiveKDecision
import com.treadz.console.programs.personalised.ExerciseMode
import com.treadz.console.programs.personalised.GeneratorNoticeCode
import com.treadz.console.training.GeneratorForm
import com.treadz.console.training.GeneratorProblem
import com.treadz.console.training.GeneratorViewModel
import com.treadz.console.training.StartBlocker
import com.treadz.console.training.StartOutcome
import com.treadz.console.treadmill.CommandResult
import com.treadz.console.treadmill.ConnectionState
import com.treadz.console.treadmill.FailureCode
import com.treadz.console.treadmill.TreadmillState
import com.treadz.console.userdata.memory.DemoData
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** The generator screen: the request built from the profile, the notices, the start (and when the session is saved), remembered inputs. */
class GeneratorViewModelTest {

    @get:Rule val main = MainDispatcherRule()

    private val rig = TrainingRig()
    private val app get() = rig.app
    private val data get() = app.userData
    private val now get() = app.nowMillis()
    private val ana by lazy { runBlocking { DemoData.populate(data, app.date) }.first }

    private fun restingBelt() {
        app.controller.state.value = TreadmillState(heartRateBpm = 72, updatedAtMillis = rig.scheduler.currentTime)
    }

    private fun viewModel(id: String) = GeneratorViewModel(
        generatorId = id, userData = data, training = app.training, controller = app.controller, settings = app.settings,
        nowMillis = app::nowMillis, today = app::today, programName = { d, f -> "${d.id}/${f.mode.name}/${f.durationMinutes}/${f.series}" },
        heartRate = app.heartRate,
    )

    private val GeneratorViewModel.state get() = uiState.value

    private fun savedPrograms() = runBlocking { data.programs.programs(data.profiles.activeUserId()!!).first() }.map { it.program }

    // ---- The request ----

    @Test fun theRequestIsBuiltFromTheActiveUsersProfileAndData() {
        ana
        val s = viewModel("zone2").state
        val generated = checkNotNull(s.generated)
        val inputs = checkNotNull(generated.program.origin).inputs

        assertEquals("female", inputs["sex"])
        assertEquals("39", inputs["ageYears"])
        assertEquals("168", inputs["heightCm"])
        assertEquals("intermediate", inputs["selfRatedFitness"])
        assertEquals("58", inputs["restingHeartRateBpm"])
        // The fitness tests of the demo user give a measured MAS and a measured maximum heart rate.
        assertEquals("false", inputs["masEstimated"])
        assertEquals("measured", inputs["maxHeartRateOrigin"])
        val weight = runBlocking { data.weights.latestWeight(ana.id).first()!!.weightKg }
        assertEquals(weight, inputs.getValue("weightKg").toDouble(), 0.01)
        assertFalse(generated.estimatedValues)
        assertEquals("run", inputs["mode"])
        assertEquals("45", inputs["durationMinutes"])
        // The name is the one the screen builds from the translated text.
        assertEquals("zone2/Run/45/null", generated.program.name)
        assertEquals(ProgramKind.Personalised, generated.program.kind)
        assertEquals(generated.program.origin, s.plan!!.source.origin)
    }

    @Test fun theGenericProfilesPlaceholderWeightCountsAsUnknown() {
        val s = viewModel("zone2").state // a fresh install: the generic profile with its placeholder 80 kg
        val generated = checkNotNull(s.generated)
        assertNull(generated.program.origin!!.inputs["weightKg"])
        val codes = generated.notices.map { it.code }
        assertTrue(GeneratorNoticeCode.BodyDataAssumed in codes)
        assertTrue(GeneratorNoticeCode.EstimatedValues in codes)
        assertTrue(GeneratorNoticeCode.RestingHeartRateMissing in codes)
        assertTrue(generated.estimatedValues)
    }

    @Test fun everyInputChangeGeneratesTheSessionAgain() {
        val vm = viewModel("tempo")
        val first = vm.state.plan!!.estimatedDurationSeconds
        val programId = vm.state.plan!!.source.programId
        assertEquals(45 * 60, first)
        vm.stepDuration(+1)
        assertEquals(50, vm.state.form.durationMinutes)
        assertEquals(50 * 60, vm.state.plan!!.estimatedDurationSeconds)
        vm.setMode(ExerciseMode.Walk)
        assertEquals("walk", vm.state.generated!!.program.origin!!.inputs["mode"])
        // The same program id while the inputs change.
        assertEquals(programId, vm.state.plan!!.source.programId)
    }

    @Test fun theInputsStayInsideWhatTheGeneratorAllows() {
        val vm = viewModel("zone2")
        repeat(30) { vm.stepDuration(+1) }
        assertEquals(90, vm.state.form.durationMinutes)
        repeat(30) { vm.stepDuration(-1) }
        assertEquals(20, vm.state.form.durationMinutes)
        val sets = viewModel("intervals-30-30")
        repeat(40) { sets.stepSeries(+1) }
        assertEquals(30, sets.state.form.series)
        // A generator without a mode choice (Couch to 5K) always runs.
        assertEquals(ExerciseMode.Run, viewModel(CouchToFiveK.ID).state.form.mode)
    }

    @Test fun aBadWalkingSpeedStopsTheSession() {
        restingBelt()
        val vm = viewModel("zone2")
        assertTrue(vm.state.canStart)
        vm.setWalkingSpeed("abc")
        assertEquals(GeneratorProblem.WalkingSpeed, vm.state.problem)
        assertNull(vm.state.plan)
        assertEquals(StartBlocker.InvalidValues, vm.state.blocker)
        vm.setWalkingSpeed("5,5")
        assertNull(vm.state.problem)
        assertEquals(5.5, vm.state.generated!!.walkingSpeedKmh, 0.001)
        vm.stepWalkingSpeed(+1)
        assertEquals("5.6", vm.state.form.walkingSpeed)
        vm.setWalkingSpeed("9")
        assertEquals(GeneratorProblem.WalkingSpeed, vm.state.problem)
    }

    // ---- Notices ----

    @Test fun aSessionTheOtherDayWarnsAndALongBreakMovesThePlanBack() {
        ana
        GeneratedFixtures.addSession(data, ana.id, GeneratedFixtures.record(session = 7, startedAtMillis = now - 11 * 3_600_000L))
        val recent = checkNotNull(viewModel(CouchToFiveK.ID).state.generated)
        val warning = recent.notices.single { it.code == GeneratorNoticeCode.RecentSession }
        assertTrue(checkNotNull(warning.value) in 9.0..11.0)
        assertTrue(recent.couchToFiveK!!.recentSessionWarning)
        assertEquals(8, recent.couchToFiveK!!.session)

        val longAgo = GeneratedFixtures.record(session = 10, startedAtMillis = now - 14 * GeneratedFixtures.DAY_MS)
        runBlocking { data.workouts.delete(data.workouts.sessionRecords(ana.id, CouchToFiveK.ID).first().single().workoutId) }
        GeneratedFixtures.addSession(data, ana.id, longAgo)
        val rested = checkNotNull(viewModel(CouchToFiveK.ID).state.generated)
        assertTrue(GeneratorNoticeCode.LongBreak in rested.notices.map { it.code })
        assertEquals(1, rested.couchToFiveK!!.weeksBack)
        assertEquals(8, rested.couchToFiveK!!.session) // 11 − 3
    }

    @Test fun finishingThePlanOffersTheLastSessionAgain() {
        ana
        GeneratedFixtures.addSession(data, ana.id, GeneratedFixtures.record(session = 27, startedAtMillis = now - 3 * GeneratedFixtures.DAY_MS))
        val generated = checkNotNull(viewModel(CouchToFiveK.ID).state.generated)
        assertEquals(CouchToFiveKDecision.PlanCompleted, generated.couchToFiveK!!.decision)
        assertEquals(27, generated.couchToFiveK!!.session)
        assertTrue(GeneratorNoticeCode.PlanCompleted in generated.notices.map { it.code })
    }

    @Test fun aBlockStartsWhereTheHeartRateSettledLastTime() {
        ana
        // The settled value is reused only from a session of the same zone: the zone 2 the block runs with.
        val fresh = viewModel("zone2").state
        val zone = checkNotNull(fresh.generated).program.steps.filterIsInstance<Block>()
            .firstNotNullOf { (it.target as? BlockTarget.HeartRate)?.zone }
        val bpm = HeartRateZones.toBpm(zone, checkNotNull(fresh.heartRateProfile))
        val record = GeneratedFixtures.record(
            generatorId = "zone2", startedAtMillis = now - 3 * GeneratedFixtures.DAY_MS, inputs = mapOf("mode" to "run"), settledValue = 9.2,
            zoneLowBpm = bpm.lowBpm, zoneHighBpm = bpm.highBpm,
        )
        GeneratedFixtures.addSession(data, ana.id, record)
        val generated = checkNotNull(viewModel("zone2").state.generated)
        val notice = generated.notices.single { it.code == GeneratorNoticeCode.StartedFromHistory }
        assertEquals("1", notice.blockKey)
        assertEquals(9.2, checkNotNull(notice.value), 0.05)
        // Walking has its own history: the run's value is not used.
        val walking = viewModel("zone2").also { it.setMode(ExerciseMode.Walk) }.state.generated!!
        assertTrue(walking.notices.none { it.code == GeneratorNoticeCode.StartedFromHistory })
    }

    @Test fun withoutAHeartRateTheScreenKnowsTheHeartRateBlocksKeepTheirStartValues() {
        app.controller.state.value = TreadmillState(heartRateBpm = 0, updatedAtMillis = 1)
        val vm = viewModel("zone2")
        assertFalse(vm.state.heartRateAvailable)
        app.controller.state.value = TreadmillState(heartRateBpm = 120, updatedAtMillis = 2)
        assertTrue(vm.state.heartRateAvailable)
    }

    // ---- Starting ----

    @Test fun theSessionIsGeneratedAndRunButNeverSavedAsAProgram() {
        restingBelt()
        val vm = viewModel("zone2")
        vm.stepDuration(+1)
        assertTrue(savedPrograms().isEmpty()) // nothing is saved while the user is just choosing

        var started = 0
        vm.start { started++ }
        assertTrue(vm.state.starting)
        rig.advance(100)

        assertEquals(1, started)
        assertEquals(RunState.Running, app.programRunner.progress.value.state)
        // Not saved: the workout keeps the plan with the generator's origin, and the program has its own generated id.
        assertTrue(savedPrograms().isEmpty())
        val plan = app.links.linked.single().plan!!
        assertEquals("zone2", plan.source.origin!!.generatorId)
        assertEquals("50", plan.source.origin!!.inputs["durationMinutes"])
        assertEquals(vm.state.plan!!.source.programId, plan.source.programId)
        // A generated program has no start parameters to remember.
        assertNull(runBlocking { data.startParameters.lastUsed(runBlocking { data.profiles.activeUserId()!! }, plan.source.programId).first() })
    }

    @Test fun theSessionsDoneWithTheGeneratorAreListedInsideItNewestFirst() {
        val user = ana
        val other = runBlocking { data.profiles.profiles.first().first { it.name == "Beto" } }
        val vm = viewModel("zone2")
        assertTrue(vm.state.sessions.workouts.isEmpty())

        val first = addGeneratedWorkout(user.id, "zone2", noonDaysAgo(3))
        val second = addGeneratedWorkout(user.id, "zone2", noonDaysAgo(1))
        addGeneratedWorkout(user.id, "tempo", noonDaysAgo(2))
        addGeneratedWorkout(other.id, "zone2", noonDaysAgo(1))

        assertEquals(listOf(second, first), vm.state.sessions.workouts.map { it.id })
    }

    private fun noonDaysAgo(days: Long) = app.date.minusDays(days).atTime(12, 0).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()

    /** A finished workout that ran a program generated by [generatorId] (not saved as a program). */
    private fun addGeneratedWorkout(userId: Long, generatorId: String, startedAt: Long): Long {
        val samples = PerformanceFixtures.samples(PerformanceFixtures.Session(minutes = 20), startedAt)
        val id = data.addWorkout(userId, PerformanceFixtures.stats(samples), samples, startedAt)
        val user = runBlocking { data.profiles.profile(userId).first()!! }
        val generated = GeneratedFixtures.generate(data, user, generatorId, null, app.date, startedAt)
        val plan = com.treadz.console.programs.plan.ProgramResolver.resolve(generated.program, com.treadz.console.programs.plan.StartParameters())
        runBlocking { data.workouts.link(id, com.treadz.console.userdata.WorkoutLink(plan = plan)) }
        return id
    }

    @Test fun aRefusedStartSavesNothing() {
        restingBelt()
        app.controller.startResult = CommandResult.Rejected(FailureCode.RejectedByTreadmill, "no")
        val vm = viewModel("zone2")
        vm.start { error("must not start") }
        rig.advance(100)
        assertTrue(vm.state.failure is StartOutcome.CommandFailed)
        assertTrue(savedPrograms().isEmpty())
        assertEquals(1, app.links.clears)

        app.controller.startResult = CommandResult.Success
        app.controller.connection.value = ConnectionState.Disconnected
        assertEquals(StartBlocker.NotConnected, vm.state.blocker)
        vm.start { error("must not start") }
        rig.advance(100)
        assertTrue(savedPrograms().isEmpty())
    }

    @Test fun theBlockersAreTheOnesOfTheProgramStartScreen() {
        restingBelt()
        val vm = viewModel("tempo")
        assertNull(vm.state.blocker)
        app.controller.showState { it.copy(status = com.treadz.console.treadmill.BeltStatus.Starting) }
        assertEquals(StartBlocker.BeltNotReady, vm.state.blocker)
        app.controller.showState { it.copy(status = com.treadz.console.treadmill.BeltStatus.Idle, speedKmh = 0.0) }
        assertNull(vm.state.blocker)
        runBlocking { data.profiles.setActiveUser(null) }
        assertEquals(StartBlocker.NoUser, vm.state.blocker)
        assertNull(vm.state.generated)
    }

    @Test fun couchToFiveKRunsWithoutSavingAProgram() {
        restingBelt()
        ana
        val vm = viewModel(CouchToFiveK.ID)
        assertEquals(CouchToFiveKDecision.FirstSession, vm.state.generated!!.couchToFiveK!!.decision)
        vm.start { }
        rig.advance(100)

        assertEquals(RunState.Running, app.programRunner.progress.value.state)
        assertTrue(savedPrograms().none { it.kind == ProgramKind.Personalised })
        val origin = app.links.linked.single().plan!!.source.origin!!
        assertEquals(CouchToFiveK.ID, origin.generatorId)
        assertEquals("1", origin.inputs["session"])
    }

    // ---- Couch to 5K: the start week and the jog speed ----

    @Test fun theStartWeekIsChosenWithTheSuggestedWeekMarked() {
        ana
        val vm = viewModel(CouchToFiveK.ID)
        assertNull(vm.state.form.startWeek)
        val suggested = checkNotNull(vm.state.suggestedStartWeek)
        assertTrue(suggested in 1..9)

        vm.chooseStartWeek(true)
        assertEquals(suggested, vm.state.form.startWeek)
        vm.setStartWeek(4)
        val selection = vm.state.generated!!.couchToFiveK!!
        assertEquals(CouchToFiveKDecision.ChosenStartWeek, selection.decision)
        assertEquals(10, selection.session)
        assertEquals(suggested, vm.state.suggestedStartWeek)

        vm.chooseStartWeek(false)
        assertNull(vm.state.form.startWeek)
        assertEquals(CouchToFiveKDecision.FirstSession, vm.state.generated!!.couchToFiveK!!.decision)
    }

    @Test fun theJogSpeedMovesInFivePercentStepsWithinTwentyPercent() {
        val vm = viewModel(CouchToFiveK.ID)
        val base = vm.state.generated!!.program.origin!!.inputs.getValue("jogSpeedKmh").toDouble()
        repeat(6) { vm.stepJogAdjustment(+1) }
        assertEquals(20, vm.state.form.jogAdjustmentPercent)
        val faster = vm.state.generated!!.program.origin!!.inputs.getValue("jogSpeedKmh").toDouble()
        assertTrue("$faster > $base", faster > base)
        repeat(10) { vm.stepJogAdjustment(-1) }
        assertEquals(-20, vm.state.form.jogAdjustmentPercent)
    }

    // ---- Remembered inputs and defaults ----

    @Test fun theInputsOfTheLastStartAreRememberedPerUserAndGeneratorAndDefaultsBringBackTheOriginals() {
        restingBelt()
        val vm = viewModel("tempo")
        vm.setMode(ExerciseMode.Walk)
        vm.stepDuration(+1)
        vm.setWalkingSpeed("5.5")
        assertFalse(vm.state.atDefaults)
        vm.start { }
        rig.advance(100)

        val userId = runBlocking { data.profiles.activeUserId()!! }
        assertEquals(mapOf("mode" to "Walk", "duration" to "50", "walk" to "5.5"), app.settings.generatorInputs(userId, "tempo"))
        assertTrue(app.settings.generatorInputs(userId, "zone2").isEmpty())

        val again = viewModel("tempo")
        assertEquals(ExerciseMode.Walk, again.state.form.mode)
        assertEquals(50, again.state.form.durationMinutes)
        assertEquals("5.5", again.state.form.walkingSpeed)
        assertFalse(again.state.atDefaults)

        again.restoreDefaults()
        assertEquals(GeneratorForm.defaults(again.state.descriptor!!), again.state.form)
        assertTrue(again.state.atDefaults)
        // Only start remembers: the stored inputs are untouched until the next start.
        assertEquals("Walk", app.settings.generatorInputs(userId, "tempo")["mode"])
    }

    @Test fun anUnknownGeneratorIsNotFound() {
        val s = viewModel("future-generator").state
        assertTrue(s.unknownGenerator)
        assertNull(s.generated)
    }
}
