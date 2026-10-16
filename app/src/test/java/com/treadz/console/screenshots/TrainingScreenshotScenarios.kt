package com.treadz.console.screenshots

import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import com.github.takahirom.roborazzi.captureRoboImage
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import com.treadz.console.MainUiState
import com.treadz.console.controls.SessionControls
import com.treadz.console.controls.SessionKind
import com.treadz.console.fakes.TrainingFixtures
import com.treadz.console.fakes.TrainingRig
import com.treadz.console.programs.fitness.FitnessTestProgress
import com.treadz.console.programs.fitness.SteadyStateStatus
import com.treadz.console.programs.plan.StartParameters
import com.treadz.console.training.RunUiState
import com.treadz.console.treadmill.ConnectionState
import com.treadz.console.treadmill.TreadmillState
import com.treadz.console.ui.ControlActions
import com.treadz.console.ui.FitnessRunScreen
import com.treadz.console.ui.RunActions
import com.treadz.console.ui.TreadZFrame
import com.treadz.console.userdata.WorkoutLink
import com.treadz.console.userdata.memory.DemoPrograms
import com.treadz.console.nav.Screen
import com.treadz.console.treadmill.BeltStatus
import com.treadz.console.ui.MainActions
import com.treadz.console.ui.PermissionsUi
import com.treadz.console.ui.TreadZRoot
import com.treadz.console.userdata.UserProfile
import com.treadz.console.userdata.memory.DemoData
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

/**
 * Milestone 5c screens (programs, fitness tests) on the fake data layer, with the runners in virtual time. Subclasses set
 * the language and screen size with `@Config(qualifiers = ...)`. Images are only written with
 * `./gradlew :app:recordRoborazziDebug`.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
abstract class TrainingScreenshotScenarios(private val prefix: String) {

    @get:Rule val rule = createComposeRule()

    private val rig = TrainingRig()
    private val app get() = rig.app
    private val data get() = app.userData

    /** Opens the app (as after a start), goes to the screens of [startAt] and runs [interact] before the picture. */
    private fun capture(name: String, startAt: List<Screen> = emptyList(), interact: () -> Unit = {}) {
        val navigation = app.newNavigation()
        rule.setContent {
            TreadZRoot(dependencies = app, viewModel = app.viewModel, permissions = PermissionsUi(), actions = MainActions(), navigation = navigation)
        }
        rule.waitForIdle()
        startAt.forEach(navigation::push)
        rule.waitForIdle()
        interact()
        rule.waitForIdle()
        rule.onRoot().captureRoboImage("build/outputs/roborazzi/${prefix}_$name.png")
    }

    private fun captureDialog(name: String) {
        rule.waitForIdle()
        rule.onNode(isDialog()).captureRoboImage("build/outputs/roborazzi/${prefix}_$name.png")
    }

    private fun click(tag: String) {
        runCatching { rule.onNodeWithTag(tag).performScrollTo() }
        rule.onNodeWithTag(tag).performClick()
        rule.waitForIdle()
    }

    private fun type(tag: String, text: String) {
        runCatching { rule.onNodeWithTag(tag).performScrollTo() }
        rule.onNodeWithTag(tag).performTextInput(text)
        rule.waitForIdle()
    }

    private fun demo(): Pair<UserProfile, UserProfile> = runBlocking { DemoData.populate(data, app.date) }

    // ---- Programs list and start ----

    @Test fun p01_programs_list() {
        demo()
        capture("p01_programs", startAt = listOf(Screen.Programs))
    }

    @Test fun p02_programs_list_without_own_programs() {
        capture("p02_programs_generic", startAt = listOf(Screen.Programs))
    }

    @Test fun p03_start_intervals_defaults() {
        demo()
        capture("p03_start_intervals", startAt = listOf(Screen.Programs, Screen.ProgramStart("standard-intervals")))
    }

    @Test fun p04_start_hill_last_used() {
        demo()
        capture("p04_start_hill", startAt = listOf(Screen.Programs, Screen.ProgramStart("standard-hill")))
    }

    @Test fun p05_start_free_program() {
        val (ana, _) = demo()
        val id = runBlocking { data.programs.programs(ana.id).first().first().id }
        capture("p05_start_free", startAt = listOf(Screen.Programs, Screen.ProgramStart(id)))
    }

    @Test fun p06_start_with_warnings() {
        val (ana, _) = demo()
        runBlocking {
            data.startParameters.save(
                ana.id, "standard-intervals",
                com.treadz.console.programs.plan.StartParameters(totalDurationSeconds = 1200, easySpeedKmh = 5.0, maxSpeedKmh = 19.0, maxInclinePercent = 8.0, intensityAdjustmentPercent = 30),
            )
        }
        capture("p06_start_warnings", startAt = listOf(Screen.Programs, Screen.ProgramStart("standard-intervals")))
    }

    @Test fun p07_start_not_connected() {
        demo()
        app.controller.showReconnecting()
        capture("p07_start_not_connected", startAt = listOf(Screen.Programs, Screen.ProgramStart("standard-pyramid")))
    }

    // ---- Running ----

    private fun startIntervals(minutes: Int): Long {
        val (ana, _) = demo()
        val outcome = rig.startProgram(rig.standardPlan("standard-intervals"), ana.id)
        check(outcome == com.treadz.console.training.StartOutcome.Started) { "$outcome" }
        rig.advance(minutes * 60_000L)
        app.controller.showState { it.copy(speedKmh = 11.0, targetSpeedKmh = 11.0, inclinePercent = 2.0) }
        return ana.id
    }

    @Test fun r01_program_running() {
        startIntervals(12)
        capture("r01_running", startAt = listOf(Screen.Main))
    }

    @Test fun r02_program_manual_override() {
        startIntervals(12)
        rig.adjustSpeed(9.5)
        capture("r02_manual_override", startAt = listOf(Screen.Main))
    }

    @Test fun r03_program_paused() {
        startIntervals(12)
        rig.call { app.programRunner.pause() }
        app.controller.showState { it.copy(status = BeltStatus.Paused, speedKmh = 0.0) }
        capture("r03_paused", startAt = listOf(Screen.Main))
    }

    @Test fun r04_program_interrupted() {
        startIntervals(12)
        app.controller.showState { it.copy(status = BeltStatus.Paused, speedKmh = 3.0) }
        rig.advance(500)
        capture("r04_interrupted", startAt = listOf(Screen.Main))
    }

    @Test fun r05_program_finished() {
        startIntervals(31)
        capture("r05_finished", startAt = listOf(Screen.Main))
    }

    @Test fun r08_program_abandoned_belt_still_running() {
        startIntervals(12)
        rig.call { app.programRunner.abandon().let { } }
        capture("r08_abandoned", startAt = listOf(Screen.Main))
    }

    @Test fun r06_program_running_light_theme() {
        startIntervals(3)
        app.viewModel.setDarkTheme(false)
        capture("r06_running_light", startAt = listOf(Screen.Main))
    }

    @Test fun r07_stop_bar_over_programs_screen() {
        startIntervals(12)
        capture("r07_stop_bar", startAt = listOf(Screen.Programs))
    }

    // ---- Fitness tests: choosing and starting ----

    private fun idleBelt(heartRate: Int? = 72) {
        app.controller.state.value = TreadmillState(heartRateBpm = heartRate, updatedAtMillis = 1_000)
    }

    @Test fun f01_fitness_tests_with_results() {
        demo()
        idleBelt()
        capture("f01_fitness_tests", startAt = listOf(Screen.Programs, Screen.FitnessTests))
    }

    @Test fun f02_fitness_tests_without_results() {
        idleBelt()
        capture("f02_fitness_tests_empty", startAt = listOf(Screen.Programs, Screen.FitnessTests))
    }

    @Test fun f03_ebbeling_start_ready() {
        demo()
        idleBelt()
        capture("f03_ebbeling_start", startAt = listOf(Screen.Programs, Screen.FitnessTests, Screen.FitnessStart(com.treadz.console.programs.fitness.FitnessTestType.EbbelingWalk)))
    }

    @Test fun f04_ebbeling_start_without_heart_rate() {
        demo()
        idleBelt(heartRate = null)
        capture("f04_ebbeling_no_hr", startAt = listOf(Screen.Programs, Screen.FitnessTests, Screen.FitnessStart(com.treadz.console.programs.fitness.FitnessTestType.EbbelingWalk)))
    }

    @Test fun f05_max_test_start() {
        demo()
        idleBelt()
        capture("f05_max_start", startAt = listOf(Screen.Programs, Screen.FitnessTests, Screen.FitnessStart(com.treadz.console.programs.fitness.FitnessTestType.MaxIncrementalRun)))
    }

    @Test fun f06_hrmax_question() {
        val (_, beto) = demo()
        runBlocking {
            data.profiles.setActiveUser(beto.id)
            data.fitnessTests.save(beto.id, DemoPrograms.maxTest(id = "0b6f8f9e-4d0c-4d43-9f43-2f0c6f1a7b09", startedAtMillis = app.date.atTime(18, 30).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli(), masKmh = 13.0, peakBpm = 193, ageYears = 42, sex = com.treadz.console.programs.fitness.Sex.Male, weightKg = 90.0))
        }
        idleBelt()
        capture("f06_hrmax_question", startAt = listOf(Screen.Programs, Screen.FitnessTests))
    }

    // ---- Fitness tests: results ----

    private fun anaSecondEbbeling(): String {
        val id = "0b6f8f9e-4d0c-4d43-9f43-2f0c6f1a7b03"
        val started = app.date.minusDays(3).atTime(18, 30).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
        runBlocking {
            val ana = data.profiles.activeUserId()!!
            data.fitnessTests.save(ana, DemoPrograms.ebbeling(id = id, startedAtMillis = started, vo2max = 41.2, ageYears = 39, sex = com.treadz.console.programs.fitness.Sex.Female, weightKg = 65.5))
        }
        return id
    }

    @Test fun x01_result_ebbeling_with_comparison() {
        demo()
        val id = anaSecondEbbeling()
        capture("x01_result_ebbeling", startAt = listOf(Screen.Programs, Screen.FitnessTests, Screen.FitnessResult(id)))
    }

    @Test fun x02_result_max_test() {
        demo()
        capture("x02_result_max", startAt = listOf(Screen.Programs, Screen.FitnessTests, Screen.FitnessResult(DemoPrograms.MAX_TEST_ID)))
    }

    @Test fun x03_result_invalid() {
        val (ana, _) = demo()
        val base = DemoPrograms.ebbeling(id = "0b6f8f9e-4d0c-4d43-9f43-2f0c6f1a7b04", startedAtMillis = app.date.minusDays(1).atTime(18, 0).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli(), ageYears = 39, sex = com.treadz.console.programs.fitness.Sex.Female)
        runBlocking { data.fitnessTests.save(ana.id, TrainingFixtures.invalidEbbeling(base)) }
        capture("x03_result_invalid", startAt = listOf(Screen.Programs, Screen.FitnessTests, Screen.FitnessResult(base.id)))
    }

    @Test fun x04_delete_result_confirmation() {
        demo()
        capture("x04_delete_result", startAt = listOf(Screen.Programs, Screen.FitnessTests, Screen.FitnessResult(DemoPrograms.MAX_TEST_ID))) { click("delete_result_button") }
        captureDialog("x04_delete_dialog")
    }

    // ---- Fitness tests: the guide while running (progress built by hand) ----

    private fun guide(name: String, test: FitnessTestProgress, session: SessionControls, saveFailed: Boolean = false, hr: Int? = test.heartRateBpm) {
        val (ana, _) = demo()
        val run = RunUiState(
            kind = SessionKind.FitnessTest, test = test, saveFailed = saveFailed,
            comparison = test.result?.let { com.treadz.console.training.FitnessResults.compare(it, emptyList()) },
        )
        val ui = MainUiState(
            connection = ConnectionState.Connected("AA", "REEBOK"),
            treadmill = TreadmillState(status = BeltStatus.Running, speedKmh = test.actualSpeedKmh, targetSpeedKmh = test.targetSpeedKmh, inclinePercent = test.actualInclinePercent, heartRateBpm = hr, updatedAtMillis = 1_000),
            activeUser = ana, session = session,
        )
        host(name) { FitnessRunScreen(ui, run, ControlActions(), RunActions()) }
    }

    /** Draws [content] inside the app frame, like the root does, without the navigation. */
    private fun host(name: String, content: @Composable () -> Unit) {
        rule.setContent {
            TreadZFrame(darkTheme = true, keepScreenOn = false, alert = null, onDismissAlert = {}) { Box(androidx.compose.ui.Modifier.fillMaxSize()) { content() } }
        }
        rule.waitForIdle()
        rule.onRoot().captureRoboImage("build/outputs/roborazzi/${prefix}_$name.png")
    }

    private val speedOnly = SessionControls(SessionKind.FitnessTest, speed = true, incline = false, pause = false, note = com.treadz.console.controls.ControlsNote.TestSpeedOnly)
    private val coolDown = SessionControls(SessionKind.FitnessTest, pause = false)
    private val locked = SessionControls(SessionKind.FitnessTest, speed = false, incline = false, pause = false, note = com.treadz.console.controls.ControlsNote.TestLocked)

    @Test fun g01_ebbeling_stage1_speed_may_change() = guide("g01_stage1", TrainingFixtures.ebbelingStage1(), speedOnly)

    @Test fun g02_ebbeling_stage1_speed_fixed() = guide("g02_stage1_fixed", TrainingFixtures.ebbelingStage1(speedLocked = true), locked)

    @Test fun g03_ebbeling_stage2() = guide("g03_stage2", TrainingFixtures.ebbelingStage2(), locked)

    @Test fun g04_ebbeling_stage2_extended() = guide("g04_stage2_extended", TrainingFixtures.ebbelingStage2(SteadyStateStatus.Extended, extensions = 1), locked)

    @Test fun g05_max_test_incremental() = guide("g05_max_running", TrainingFixtures.maxIncremental(), locked)

    @Test fun g06_max_test_top_speed() = guide("g06_max_cap", TrainingFixtures.maxIncremental(capReached = true), locked)

    /** The cool-down allows manual changes: speed and incline with − / +. */
    @Test fun g07_max_test_cool_down() = guide("g07_cool_down", TrainingFixtures.coolDown(DemoPrograms.maxTest(startedAtMillis = 1_000_000)), coolDown)

    @Test fun g08_result_after_max_test() = guide("g08_finished_max", TrainingFixtures.finished(DemoPrograms.maxTest(startedAtMillis = 1_000_000, masKmh = 12.5, peakBpm = 188)), locked)

    @Test fun g09_result_after_invalid_ebbeling() {
        val base = DemoPrograms.ebbeling(startedAtMillis = 1_000_000)
        guide("g09_finished_invalid", TrainingFixtures.finished(TrainingFixtures.invalidEbbeling(base)), locked, saveFailed = true)
    }

    // ---- Editor ----

    @Test fun e01_editor_new_program() {
        demo()
        capture("e01_editor_new", startAt = listOf(Screen.Programs, Screen.ProgramEditor(null, 1)))
    }

    @Test fun e02_editor_with_repeat() {
        val (ana, _) = demo()
        capture("e02_editor_repeat", startAt = listOf(Screen.Programs, Screen.ProgramEditor(DemoPrograms.NORWEGIAN_ID, 1)))
    }

    @Test fun e03_editor_validation_errors() {
        demo()
        capture("e03_editor_errors", startAt = listOf(Screen.Programs, Screen.ProgramEditor(null, 1))) {
            rule.onNodeWithTag("program_name_field").performTextInput("")
            type("block_0_speed", "x")
            click("save_program_button")
        }
    }

    @Test fun e04_editor_short_block() {
        demo()
        capture("e04_editor_short", startAt = listOf(Screen.Programs, Screen.ProgramEditor(DemoPrograms.NORWEGIAN_ID, 1))) {
            runCatching { rule.onNodeWithTag("block_1_0_duration").performScrollTo() }
            rule.onNodeWithTag("block_1_0_duration").performTextReplacement("0:20")
            rule.waitForIdle()
        }
    }

    @Test fun e05_programs_delete_confirmation() {
        demo()
        capture("e05_delete_program", startAt = listOf(Screen.Programs)) {
            rule.onNodeWithTag("programs_list").performScrollToNode(hasTestTag("delete_program_${DemoPrograms.NORWEGIAN_ID}"))
            click("delete_program_${DemoPrograms.NORWEGIAN_ID}")
        }
        captureDialog("e05_delete_dialog")
    }

    // ---- History and profile ----

    private fun linkedWorkouts(): Pair<Long, Long> {
        val (ana, _) = demo()
        val plan = rig.standardPlan("standard-intervals")
        val started = app.date.minusDays(2).atTime(18, 0).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
        val samples = TrainingFixtures.planSamples(plan, started)
        val programWorkout = data.addWorkout(ana.id, TrainingFixtures.statsOf(samples), samples, started)
        val testStarted = app.date.minusDays(10).atTime(18, 0).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
        val testSamples = TrainingFixtures.planSamples(plan, testStarted).take(900)
        val testWorkout = data.addWorkout(ana.id, TrainingFixtures.statsOf(testSamples), testSamples, testStarted)
        runBlocking {
            data.workouts.link(programWorkout, WorkoutLink(plan = plan))
            data.workouts.link(testWorkout, WorkoutLink(fitnessTestId = DemoPrograms.MAX_TEST_ID))
        }
        return programWorkout to testWorkout
    }

    @Test fun h01_history_with_program_and_test_badges() {
        linkedWorkouts()
        capture("h01_history", startAt = listOf(Screen.History))
    }

    @Test fun h02_detail_of_a_program_workout() {
        val (program, _) = linkedWorkouts()
        capture("h02_detail_program", startAt = listOf(Screen.History, Screen.WorkoutDetail(program)))
    }

    @Test fun h03_detail_of_a_test_workout() {
        val (_, test) = linkedWorkouts()
        capture("h03_detail_test", startAt = listOf(Screen.History, Screen.WorkoutDetail(test)))
    }

    @Test fun h04_profile_with_fitness_block() {
        val (ana, _) = demo()
        capture("h04_profile_fitness", startAt = listOf(Screen.ProfileEditor(ana.id, 1)))
    }

    @Test fun h05_profile_hrmax_question() {
        val (_, beto) = demo()
        runBlocking {
            data.fitnessTests.save(beto.id, DemoPrograms.maxTest(id = "0b6f8f9e-4d0c-4d43-9f43-2f0c6f1a7b09", startedAtMillis = app.date.atTime(18, 30).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli(), masKmh = 13.0, peakBpm = 193, ageYears = 42, sex = com.treadz.console.programs.fitness.Sex.Male, weightKg = 90.0))
        }
        capture("h05_profile_hrmax_question", startAt = listOf(Screen.ProfileEditor(beto.id, 1)))
    }

    @Test fun m01_main_with_programs_button() {
        demo()
        idleBelt()
        capture("m01_main_programs", startAt = listOf(Screen.Main))
    }

    // ---- Light theme ----

    @Test fun l01_programs_light() {
        demo()
        app.viewModel.setDarkTheme(false)
        capture("l01_programs_light", startAt = listOf(Screen.Programs))
    }

    @Test fun l02_start_light() {
        demo()
        idleBelt()
        app.viewModel.setDarkTheme(false)
        capture("l02_start_light", startAt = listOf(Screen.Programs, Screen.ProgramStart("standard-pyramid")))
    }

    @Test fun l03_editor_light() {
        demo()
        idleBelt()
        app.viewModel.setDarkTheme(false)
        capture("l03_editor_light", startAt = listOf(Screen.Programs, Screen.ProgramEditor(DemoPrograms.NORWEGIAN_ID, 1)))
    }

    @Test fun l04_result_light() {
        demo()
        idleBelt()
        app.viewModel.setDarkTheme(false)
        capture("l04_result_light", startAt = listOf(Screen.Programs, Screen.FitnessTests, Screen.FitnessResult(DemoPrograms.MAX_TEST_ID)))
    }

    @Test fun l05_guide_light() {
        val (ana, _) = demo()
        val test = TrainingFixtures.ebbelingStage2(SteadyStateStatus.Pending)
        val ui = MainUiState(
            connection = ConnectionState.Connected("AA", "REEBOK"),
            treadmill = TreadmillState(status = BeltStatus.Running, speedKmh = 4.8, targetSpeedKmh = 4.8, inclinePercent = 5.0, heartRateBpm = 121, updatedAtMillis = 1_000),
            activeUser = ana, session = locked,
        )
        rule.setContent {
            TreadZFrame(darkTheme = false, keepScreenOn = false, alert = null, onDismissAlert = {}) {
                Box(androidx.compose.ui.Modifier.fillMaxSize()) { FitnessRunScreen(ui, RunUiState(kind = SessionKind.FitnessTest, test = test), ControlActions(), RunActions()) }
            }
        }
        rule.waitForIdle()
        rule.onRoot().captureRoboImage("build/outputs/roborazzi/${prefix}_l05_guide_light.png")
    }
}
