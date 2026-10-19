package com.treadz.console.screenshots

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.github.takahirom.roborazzi.captureRoboImage
import com.treadz.console.MainUiState
import com.treadz.console.controls.ControlsNote
import com.treadz.console.controls.SessionControls
import com.treadz.console.controls.SessionKind
import com.treadz.console.fakes.FakeApp
import com.treadz.console.fakes.GeneratedFixtures
import com.treadz.console.fakes.TrainingFixtures
import com.treadz.console.fakes.TrainingRig
import com.treadz.console.nav.Screen
import com.treadz.console.programs.plan.ProgramResolver
import com.treadz.console.programs.plan.ResolvedPlan
import com.treadz.console.training.RunUiState
import com.treadz.console.training.StartOutcome
import com.treadz.console.treadmill.BeltStatus
import com.treadz.console.treadmill.CommandResult
import com.treadz.console.treadmill.ConnectionState
import com.treadz.console.treadmill.FailureCode
import com.treadz.console.treadmill.TreadmillState
import com.treadz.console.ui.ControlActions
import com.treadz.console.ui.FitnessRunScreen
import com.treadz.console.ui.MainActions
import com.treadz.console.ui.PermissionsUi
import com.treadz.console.ui.RunActions
import com.treadz.console.ui.TreadZFrame
import com.treadz.console.ui.TreadZRoot
import com.treadz.console.userdata.ProgramSaveResult
import com.treadz.console.userdata.UserProfile
import com.treadz.console.userdata.memory.DemoData
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

/**
 * The live screens with one set of sizes (free workout, idle with Start, programs with "+1" and the live chart, the
 * chart at the end, a fitness test), "keep" in the editor and the previews, and the α1 lines of a session record, on
 * synthetic data. The speed and incline figure must have the same size in every live picture of a form factor.
 * Subclasses set the language and screen size with `@Config(qualifiers = ...)`. Images are only written with
 * `./gradlew :app:recordRoborazziDebug`.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
abstract class LiveScreenshotScenarios(private val prefix: String) {

    @get:Rule val rule = createComposeRule()

    private val app = FakeApp()
    private val rig by lazy { TrainingRig() }

    /** Opens the app on [theApp], goes to the screens of [startAt] and runs [interact] before the picture. */
    private fun capture(name: String, theApp: FakeApp = app, startAt: List<Screen> = emptyList(), interact: () -> Unit = {}) {
        val navigation = theApp.newNavigation()
        rule.setContent {
            TreadZRoot(dependencies = theApp, viewModel = theApp.viewModel, permissions = PermissionsUi(), actions = MainActions(), navigation = navigation)
        }
        rule.waitForIdle()
        navigation.replaceAll(Screen.Main)
        startAt.forEach(navigation::push)
        rule.waitForIdle()
        interact()
        rule.waitForIdle()
        rule.onRoot().captureRoboImage("build/outputs/roborazzi/${prefix}_$name.png")
    }

    /** Draws [content] inside the app frame, like the root does, without the navigation. */
    private fun host(name: String, content: @Composable () -> Unit) {
        rule.setContent {
            TreadZFrame(darkTheme = true, keepScreenOn = false, alert = null, onDismissAlert = {}) { Box(androidx.compose.ui.Modifier.fillMaxSize()) { content() } }
        }
        rule.waitForIdle()
        rule.onRoot().captureRoboImage("build/outputs/roborazzi/${prefix}_$name.png")
    }

    private fun click(tag: String) {
        runCatching { rule.onNodeWithTag(tag).performScrollTo() }
        rule.onNodeWithTag(tag).performClick()
        rule.waitForIdle()
    }

    private fun scrollTo(tag: String) {
        runCatching { rule.onNodeWithTag(tag).performScrollTo() }
        rule.waitForIdle()
    }

    private fun demo(theApp: FakeApp = app): UserProfile = runBlocking { DemoData.populate(theApp.userData, theApp.date).first }

    // ---- Free workout ----

    private fun freeRunning() {
        demo()
        app.controller.showConnected()
        app.controller.showRunning()
        app.recorder.showRecording()
        app.viewModel
    }

    @Test fun a01_free_workout_running() {
        freeRunning()
        capture("a01_free_running")
    }

    /** A failed speed command and an incline target still on its way: the figures keep their size. */
    @Test fun a02_free_workout_failure_and_target() {
        freeRunning()
        app.controller.setSpeedHandler = { CommandResult.Rejected(FailureCode.RejectedByTreadmill, "the treadmill said no") }
        app.controller.setInclineHandler = { awaitCancellation() }
        repeat(2) { app.viewModel.controls.stepIncline(+1) }
        app.viewModel.controls.stepSpeed(+1)
        capture("a02_free_failure_target")
    }

    @Test fun a03_idle_with_start() {
        demo()
        app.controller.showConnected()
        app.controller.state.value = TreadmillState(heartRateBpm = 72, updatedAtMillis = 1_000)
        capture("a03_idle_start")
    }

    // ---- Programs ----

    /** Starts [plan] on the rig with a belt that follows every speed command at once (the real line follows the plan). */
    private fun startOnRig(plan: ResolvedPlan): UserProfile {
        val user = demo(rig.app)
        rig.app.controller.state.value = TreadmillState(heartRateBpm = 120, updatedAtMillis = rig.scheduler.currentTime)
        rig.app.controller.setSpeedHandler = { kmh ->
            rig.app.controller.state.value = rig.app.controller.state.value.copy(speedKmh = kmh, targetSpeedKmh = kmh)
            CommandResult.Success
        }
        val outcome = rig.startProgram(plan, user.id)
        check(outcome == StartOutcome.Started) { "$outcome" }
        rig.app.controller.state.value = rig.app.controller.state.value.copy(status = BeltStatus.Running)
        return user
    }

    /**
     * The treadmill reports every second, like the real one: its distance and time go on, with a heart rate that follows
     * the effort.
     */
    private fun report(seconds: Int, heartRate: (Double) -> Int? = { speed -> (95 + speed * 7).toInt() }) {
        repeat(seconds) {
            val state = rig.app.controller.state.value
            meters += state.speedKmh / 3.6
            rig.app.controller.state.value = state.copy(
                heartRateBpm = heartRate(state.speedKmh), distanceMeters = meters.toInt(), elapsedSeconds = state.elapsedSeconds + 1,
                calories = (meters / 16).toInt(), updatedAtMillis = rig.scheduler.currentTime,
            )
            rig.advance(1_000)
        }
    }

    private var meters = 0.0

    @Test fun b01_program_running_with_the_live_chart() {
        startOnRig(rig.standardPlan("standard-intervals"))
        report(9 * 60)
        // A manual change: the real line leaves the plan.
        rig.adjustSpeed(9.0)
        report(3 * 60)
        capture("b01_program_live_chart", theApp = rig.app)
    }

    @Test fun b02_program_with_the_heart_rate_block_panel() {
        startOnRig(ProgramResolver.resolve(TrainingFixtures.heartRateProgram()))
        report(125) { 100 }
        var current = 100
        while (current != 118) {
            current += 3.coerceAtMost(118 - current)
            report(1) { current }
        }
        report(240) { 118 }
        capture("b02_program_hr_panel", theApp = rig.app)
    }

    /** A block that ends by distance, made 100 m longer: "+100 m", and the next block keeps the speed. */
    @Test fun b03_program_distance_block_extended() {
        startOnRig(ProgramResolver.resolve(TrainingFixtures.keepProgram()))
        report(5 * 60 + 90)
        rig.call { rig.app.programRunner.extendCurrentBlock() }
        report(5)
        capture("b03_program_distance_plus_100", theApp = rig.app)
    }

    @Test fun b04_program_finished_with_the_real_chart() {
        startOnRig(rig.standardPlan("standard-intervals"))
        report(10 * 60)
        rig.call { rig.app.programRunner.extendCurrentBlock() }
        report(22 * 60)
        capture("b04_program_finished_chart", theApp = rig.app)
    }

    // ---- Fitness test ----

    @Test fun c01_walking_test_running() {
        val user = demo()
        val test = TrainingFixtures.walkRamp(200)
        val locked = SessionControls(SessionKind.FitnessTest, speed = false, incline = false, pause = false, note = ControlsNote.TestLocked)
        val ui = MainUiState(
            connection = ConnectionState.Connected("AA", "REEBOK"),
            treadmill = TreadmillState(status = BeltStatus.Running, speedKmh = test.actualSpeedKmh, targetSpeedKmh = test.targetSpeedKmh, inclinePercent = test.actualInclinePercent, heartRateBpm = test.heartRateBpm, updatedAtMillis = 1_000),
            activeUser = user, session = locked,
        )
        host("c01_walk_test_running") { FitnessRunScreen(ui, RunUiState(kind = SessionKind.FitnessTest, test = test), ControlActions(), RunActions()) }
    }

    // ---- "Keep" ----

    private fun storedKeepProgram(): String {
        val user = demo()
        return (runBlocking { app.userData.programs.save(user.id, TrainingFixtures.keepProgram()) } as ProgramSaveResult.Saved).program.id
    }

    @Test fun d01_editor_with_kept_values() {
        val id = storedKeepProgram()
        capture("d01_editor_keep", startAt = listOf(Screen.Programs, Screen.ProgramEditor(id, 1))) { scrollTo("block_2_speed_keep") }
    }

    @Test fun d02_editor_keep_on_the_first_block() {
        val id = storedKeepProgram()
        capture("d02_editor_keep_first", startAt = listOf(Screen.Programs, Screen.ProgramEditor(id, 1))) {
            click("move_up_2")
            click("move_up_1")
            click("save_program_button")
            scrollTo("block_0_duration")
        }
    }

    @Test fun d03_plan_preview_with_kept_values() {
        val id = storedKeepProgram()
        capture("d03_preview_keep", startAt = listOf(Screen.Programs, Screen.ProgramStart(id))) { scrollTo("plan_block_list") }
    }

    // ---- The record of a generated session ----

    @Test fun e01_session_record_with_alpha1() {
        val user = demo()
        val noon = app.nowMillis()
        val base = GeneratedFixtures.record(generatorId = "zone2", startedAtMillis = noon - 86_400_000L, inputs = mapOf("mode" to "run"), settledValue = 9.1)
        val record = base.copy(blocks = base.blocks.map { it.copy(heartRate = it.heartRate!!.copy(alpha1Reductions = 2, alpha1Mean = 0.684, alpha1Windows = 54)) })
        val id = GeneratedFixtures.addSession(app.userData, user.id, record)
        capture("e01_record_alpha1", startAt = listOf(Screen.History, Screen.WorkoutDetail(id))) { scrollTo("session_record") }
    }
}
