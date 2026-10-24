package com.treadz.console.screenshots

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.github.takahirom.roborazzi.captureRoboImage
import com.treadz.console.MainUiState
import com.treadz.console.analysis.workout.WorkoutAnalysis
import com.treadz.console.controls.SessionControls
import com.treadz.console.controls.SessionKind
import com.treadz.console.fakes.FakeApp
import com.treadz.console.fakes.FakeHeartRateMonitor
import com.treadz.console.fakes.FuelFixtures
import com.treadz.console.fakes.TrainingFixtures
import com.treadz.console.fuel.FuelFigures
import com.treadz.console.fuel.FuelPresenter
import com.treadz.console.history.WorkoutDetailUiState
import com.treadz.console.nav.Screen
import com.treadz.console.programs.engine.BlockStart
import com.treadz.console.programs.engine.ProgramProgress
import com.treadz.console.programs.engine.RunOutcome
import com.treadz.console.programs.engine.RunState
import com.treadz.console.programs.fitness.HeartRateSourceKind
import com.treadz.console.programs.plan.ProgramResolver
import com.treadz.console.training.FinishedWorkout
import com.treadz.console.training.RunUiState
import com.treadz.console.treadmill.BeltStatus
import com.treadz.console.treadmill.ConnectionState
import com.treadz.console.treadmill.TreadmillState
import com.treadz.console.ui.ControlActions
import com.treadz.console.ui.LiveData
import com.treadz.console.ui.LocalLayout
import com.treadz.console.ui.MainActions
import com.treadz.console.ui.PermissionsUi
import com.treadz.console.ui.ProgramRunScreen
import com.treadz.console.ui.RunActions
import com.treadz.console.ui.TreadZFrame
import com.treadz.console.ui.TreadZRoot
import com.treadz.console.ui.WorkoutDetailActions
import com.treadz.console.ui.WorkoutDetailScreen
import com.treadz.console.ui.liveSizes
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

/**
 * The estimated fat/carbohydrate split: live on the main and program screens (valid and dimmed), at the end of a program
 * and of a free workout, and the «Energy sources» card of the history detail (dark and light, with its explanation), on
 * synthetic data. Subclasses set the language and screen size with `@Config(qualifiers = ...)`. Images are only written
 * with `./gradlew :app:recordRoborazziDebug`.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
abstract class FuelScreenshotScenarios(private val prefix: String) {

    @get:Rule val rule = createComposeRule()

    private val app = FakeApp()

    private fun path(name: String) = "build/outputs/roborazzi/${prefix}_$name.png"

    /** Opens the whole app on [app] and takes the picture of the main screen. */
    private fun captureApp(name: String) {
        val navigation = app.newNavigation()
        rule.setContent {
            TreadZRoot(dependencies = app, viewModel = app.viewModel, permissions = PermissionsUi(), actions = MainActions(), navigation = navigation)
        }
        rule.waitForIdle()
        navigation.replaceAll(Screen.Main)
        rule.waitForIdle()
        // On the phone the figures scroll above Pause and STOP: show the line of the split, as a user would scroll to it.
        runCatching { rule.onNodeWithTag("fuel_strip").performScrollTo() }
        rule.waitForIdle()
        rule.onRoot().captureRoboImage(path(name))
    }

    /** Draws [content] inside the app frame, like the root does, without the navigation. */
    private fun host(name: String, darkTheme: Boolean = true, interact: () -> Unit = {}, content: @Composable () -> Unit) {
        rule.setContent {
            TreadZFrame(darkTheme = darkTheme, keepScreenOn = false, alert = null, onDismissAlert = {}) { Box(androidx.compose.ui.Modifier.fillMaxSize()) { content() } }
        }
        rule.waitForIdle()
        interact()
        rule.waitForIdle()
        rule.onRoot().captureRoboImage(path(name))
    }

    private fun scrollTo(tag: String) {
        rule.onNodeWithTag(tag).performScrollTo()
        rule.waitForIdle()
    }

    // ---- Live, main screen ----

    /** The split only exists with the strap connected to the app: the user's strap, connected, gives the heart rate. */
    private fun strapConnected(bpm: Int) {
        runBlocking {
            val id = app.userData.profiles.activeUserId()!!
            app.userData.profiles.setStrap(id, FakeHeartRateMonitor.STRAP_ADDRESS, FakeHeartRateMonitor.STRAP_NAME)
        }
        app.strapMonitor.showConnected(bpm = bpm)
    }

    private fun running(snapshot: com.treadz.console.analysis.energy.FuelSnapshot?) {
        app.controller.showConnected()
        app.controller.showRunning()
        app.recorder.showRecording()
        strapConnected(bpm = 141)
        app.liveCaloriesFlow.value = 316
        app.liveFuelFlow.value = snapshot
    }

    @Test fun a01_live_split() {
        running(FuelFixtures.snapshot(fatShare = 0.38))
        captureApp("a01_live")
    }

    @Test fun a02_live_split_dimmed() {
        running(FuelFixtures.snapshot(fatShare = 0.38, currentlyValid = false))
        captureApp("a02_live_dimmed")
    }

    /** No split to show: the screen is the one of before, with no gap. */
    @Test fun a03_live_without_split() {
        running(FuelFixtures.snapshot(showable = false))
        captureApp("a03_live_hidden")
    }

    /** The belt at rest with the split still on (the grid of the idle screen): the figures fit. */
    @Test fun a04_idle_grid_with_split() {
        app.controller.showConnected()
        app.controller.state.value = TreadmillState(heartRateBpm = 98, updatedAtMillis = 1_000)
        strapConnected(bpm = 98)
        app.liveCaloriesFlow.value = 316
        app.liveFuelFlow.value = FuelFixtures.snapshot(fatShare = 0.38)
        captureApp("a04_idle_grid")
    }

    // ---- Live, program ----

    private val plan = ProgramResolver.resolve(TrainingFixtures.keepProgram())

    private val runningUi = MainUiState(
        connection = ConnectionState.Connected("AA", "REEBOK"),
        treadmill = TreadmillState(status = BeltStatus.Running, speedKmh = 9.6, targetSpeedKmh = 9.6, inclinePercent = 2.0, heartRateBpm = 141, updatedAtMillis = 1_000),
        session = SessionControls(SessionKind.Program),
        activeUser = runBlocking { app.userData.profiles.activeUser.first() },
    )

    private fun progress(state: RunState = RunState.Running) = ProgramProgress(
        state = state, plan = plan, currentBlockIndex = 1, currentBlock = plan.blocks[1], nextBlock = plan.blocks[2],
        blockElapsedSeconds = 100, blockRemainingSeconds = 260, blockElapsedMeters = 280, blockRemainingMeters = 720,
        totalElapsedSeconds = 1_020, totalRemainingSeconds = 780, totalElapsedMeters = 2_150,
        targetSpeedKmh = 9.6, targetInclinePercent = 2.0, actualSpeedKmh = 9.6, actualInclinePercent = 2.0,
        heartRateBpm = 141, heartRateSource = HeartRateSourceKind.DirectStrap,
    )

    @Test fun b01_program_running_with_split() {
        val ui = runningUi.copy(liveCalories = 316, liveFuel = FuelPresenter.live(316, FuelFixtures.snapshot(fatShare = 0.38)))
        host("b01_program_live", interact = { runCatching { scrollTo("run_totals") } }) { ProgramRunScreen(ui, RunUiState(kind = SessionKind.Program, program = progress()), ControlActions(), RunActions()) }
    }

    @Test fun b02_program_running_with_split_dimmed() {
        val ui = runningUi.copy(liveCalories = 316, liveFuel = FuelPresenter.live(316, FuelFixtures.snapshot(fatShare = 0.38, currentlyValid = false)))
        host("b02_program_live_dimmed", interact = { runCatching { scrollTo("run_totals") } }) { ProgramRunScreen(ui, RunUiState(kind = SessionKind.Program, program = progress()), ControlActions(), RunActions()) }
    }

    @Test fun b03_program_summary_with_split() {
        val starts = plan.blocks.indices.map { BlockStart(it, it * 300) }
        val finished = progress(RunState.Finished).copy(outcome = RunOutcome.Completed, currentBlockIndex = plan.blocks.lastIndex, blockStarts = starts)
        val ui = MainUiState(
            connection = ConnectionState.Connected("AA", "REEBOK"),
            treadmill = TreadmillState(status = BeltStatus.Idle, updatedAtMillis = 1_000),
            finishedWorkout = FinishedWorkout(7, 1_800, 4_300, null, 642),
            finishedFuel = FuelFigures(290, 352, 45, 55),
        )
        host("b03_program_summary") { ProgramRunScreen(ui, RunUiState(kind = SessionKind.Program, program = finished), ControlActions(), RunActions()) }
    }

    // ---- The end of a workout without a program ----

    @Test fun c01_end_notice_with_split() {
        val ui = MainUiState(
            connection = ConnectionState.Connected("AA", "REEBOK"),
            treadmill = TreadmillState(status = BeltStatus.Idle, updatedAtMillis = 1_000),
            activeUser = runBlocking { app.userData.profiles.activeUser.first() },
            finishedWorkout = FinishedWorkout(7, 1_800, 4_300, null, 642),
            finishedFuel = FuelFigures(290, 352, 45, 55),
        )
        host("c01_end_notice") {
            val wide = LocalLayout.current.wide
            LiveData(ui, MainActions(), wide, liveSizes(wide))
        }
    }

    /** The analysis has not arrived yet: the notice is the one of before. */
    @Test fun c02_end_notice_without_split() {
        val ui = MainUiState(
            connection = ConnectionState.Connected("AA", "REEBOK"),
            treadmill = TreadmillState(status = BeltStatus.Idle, updatedAtMillis = 1_000),
            activeUser = runBlocking { app.userData.profiles.activeUser.first() },
            finishedWorkout = FinishedWorkout(7, 1_800, 4_300, null, 642),
        )
        host("c02_end_notice_waiting") {
            val wide = LocalLayout.current.wide
            LiveData(ui, MainActions(), wide, liveSizes(wide))
        }
    }

    // ---- History detail ----

    private val startedAt = app.nowMillis() - 2 * 3_600_000L

    private fun detailState(analysis: WorkoutAnalysis?): WorkoutDetailUiState = FuelFixtures.detailState(app, analysis, startedAt)

    private val withSplit get() = FuelFixtures.analysis(FuelFixtures.fuel(fatShare = 0.452, minutes = FuelFixtures.minutes(startedAt, 60)))

    private fun detail(name: String, darkTheme: Boolean, analysis: WorkoutAnalysis?, interact: () -> Unit = { scrollTo("fuel_card") }) {
        val state = detailState(analysis)
        host(name, darkTheme, interact) { WorkoutDetailScreen(state, WorkoutDetailActions()) }
    }

    @Test fun d01_detail_card_dark() = detail("d01_detail_dark", true, withSplit)

    @Test fun d02_detail_card_light() = detail("d02_detail_light", false, withSplit)

    /** The card also shows the zones and the note: a taller scroll to see it whole. */
    @Test fun d03_detail_card_zones_and_note() = detail("d03_detail_zones", true, withSplit) { scrollTo("fuel_note") }

    @Test fun d04_detail_explanation() {
        val state = detailState(withSplit)
        rule.setContent {
            TreadZFrame(darkTheme = true, keepScreenOn = false, alert = null, onDismissAlert = {}) { Box(androidx.compose.ui.Modifier.fillMaxSize()) { WorkoutDetailScreen(state, WorkoutDetailActions()) } }
        }
        rule.waitForIdle()
        scrollTo("fuel_info_button")
        rule.onNodeWithTag("fuel_info_button").performClick()
        rule.waitForIdle()
        rule.onNode(isDialog()).captureRoboImage(path("d04_detail_explanation"))
    }

    /** No split in the analysis: the detail is the one of before, with no card and no gap. */
    @Test fun d05_detail_without_split() = detail("d05_detail_hidden", true, FuelFixtures.analysis(fuel = null)) { }
}
