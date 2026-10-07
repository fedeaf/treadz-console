package com.treadz.console.screenshots

import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.github.takahirom.roborazzi.captureRoboImage
import com.treadz.console.fakes.FakeApp
import com.treadz.console.nav.Screen
import com.treadz.console.treadmill.BeltStatus
import com.treadz.console.treadmill.CommandResult
import com.treadz.console.treadmill.FailureCode
import com.treadz.console.ui.MainActions
import com.treadz.console.ui.PermissionsUi
import com.treadz.console.ui.TreadZRoot
import com.treadz.console.userdata.UserProfile
import com.treadz.console.userdata.memory.DemoData
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

/**
 * Milestone 3: the manual controls on the main screen, the STOP bar on the other screens and the export choice, on
 * fake treadmills. Subclasses set the language and screen size with `@Config(qualifiers = ...)`. Images are only written
 * with `./gradlew :app:recordRoborazziDebug`.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
abstract class ControlsScreenshotScenarios(private val prefix: String) {

    @get:Rule val rule = createComposeRule()

    private val app = FakeApp()
    private val controller get() = app.controller
    private val controls get() = app.viewModel.controls

    init {
        app.viewModel
    }

    /** Opens the app (as after a start), goes to [startAt] and runs [interact]. */
    private fun open(startAt: List<Screen> = emptyList(), interact: () -> Unit = {}) {
        val navigation = app.newNavigation()
        rule.setContent {
            TreadZRoot(dependencies = app, viewModel = app.viewModel, permissions = PermissionsUi(), actions = MainActions(), navigation = navigation)
        }
        rule.waitForIdle()
        startAt.forEach(navigation::push)
        rule.waitForIdle()
        interact()
        rule.waitForIdle()
    }

    private fun capture(name: String, startAt: List<Screen> = emptyList(), interact: () -> Unit = {}) {
        open(startAt, interact)
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

    private fun running() {
        controller.showConnected()
        controller.showRunning()
        app.recorder.showRecording()
    }

    private fun demo(): Pair<UserProfile, UserProfile> = runBlocking { DemoData.populate(app.userData, app.date) }

    // ---- Main screen ----

    @Test fun c01_running_with_controls() {
        running()
        capture("c01_running")
    }

    @Test fun c02_running_pending_targets() {
        running()
        // The treadmill is slow to answer: the taps are shown at once and wait for it.
        controller.setSpeedHandler = { awaitCancellation() }
        controller.setInclineHandler = { awaitCancellation() }
        repeat(3) { controls.stepSpeed(+1) }
        repeat(2) { controls.stepIncline(+1) }
        capture("c02_running_pending")
    }

    @Test fun c03_running_light_theme() {
        running()
        controller.setSpeedHandler = { awaitCancellation() }
        repeat(3) { controls.stepSpeed(-1) }
        app.viewModel.setDarkTheme(false)
        capture("c03_running_light")
    }

    @Test fun c04_paused_belt_still_slowing_down() {
        running()
        controller.showState { it.copy(status = BeltStatus.Paused, speedKmh = 3.5, targetSpeedKmh = 8.5, inclinePercent = 0.0) }
        capture("c04_paused_slowing")
    }

    @Test fun c05_paused_stopped_resume_enabled() {
        running()
        controller.showState { it.copy(status = BeltStatus.Paused, speedKmh = 0.0, targetSpeedKmh = 8.5, inclinePercent = 0.0) }
        capture("c05_paused_stopped")
    }

    @Test fun c06_speed_command_failed() {
        running()
        controller.setSpeedHandler = { CommandResult.Rejected(FailureCode.RejectedByTreadmill, "the treadmill said no") }
        controls.stepSpeed(+1)
        capture("c06_speed_failed")
    }

    @Test fun c07_resume_failed() {
        running()
        controller.showState { it.copy(status = BeltStatus.Paused, speedKmh = 0.0, targetSpeedKmh = 8.5, inclinePercent = 0.0) }
        controller.resumeResult = CommandResult.Rejected(FailureCode.RejectedByTreadmill, "the treadmill said no")
        controls.resume()
        capture("c07_resume_failed")
    }

    @Test fun c13_incline_command_not_executed_alert() {
        running()
        controller.setInclineHandler = { CommandResult.NotVerified(FailureCode.TargetNotReached, "the incline did not move") }
        controls.stepIncline(+1)
        capture("c13_command_not_executed_alert")
    }

    @Test fun c08_disconnected_while_running() {
        running()
        controller.showReconnecting(attempt = 2)
        capture("c08_reconnecting_running")
    }

    @Test fun c09_starting_countdown() {
        controller.showConnected()
        controller.showState { it.copy(status = BeltStatus.Starting, speedKmh = 0.0) }
        capture("c09_starting")
    }

    @Test fun c10_running_without_active_user() {
        demo()
        runBlocking { app.userData.profiles.setActiveUser(null) }
        running()
        capture("c10_running_no_user", startAt = listOf(Screen.Main))
    }

    @Test fun c11_stopping_belt_still_moving() {
        running()
        controller.showState { it.copy(status = BeltStatus.Idle, speedKmh = 5.5, targetSpeedKmh = null) }
        capture("c11_stopping")
    }

    @Test fun c12_running_max_speed_values() {
        running()
        controller.showState { it.copy(speedKmh = 12.5, targetSpeedKmh = 12.5, inclinePercent = 12.0, distanceMeters = 10_480, elapsedSeconds = 4_321, calories = 842, heartRateBpm = 168) }
        capture("c12_running_big_values")
    }

    // ---- STOP bar on the other screens ----

    @Test fun b01_stop_bar_on_weight() {
        demo()
        running()
        capture("b01_bar_weight", startAt = listOf(Screen.Weight))
    }

    @Test fun b02_stop_bar_on_history() {
        demo()
        running()
        capture("b02_bar_history", startAt = listOf(Screen.History))
    }

    @Test fun b03_stop_bar_without_connection() {
        demo()
        running()
        controller.showReconnecting()
        capture("b03_bar_unreachable", startAt = listOf(Screen.Weight))
    }

    @Test fun b04_stop_bar_on_user_selection() {
        demo()
        running()
        capture("b04_bar_users")
    }

    // ---- Export ----

    private fun firstWorkoutId(): Long = runBlocking {
        val ana = app.userData.profiles.activeUser.first()!!
        app.userData.workouts.workouts(ana.id).first().last().id
    }

    @Test fun x01_export_detail_with_button() {
        demo()
        capture("x01_detail_export", startAt = listOf(Screen.WorkoutDetail(firstWorkoutId())))
    }

    @Test fun x02_export_format_choice() {
        demo()
        open(startAt = listOf(Screen.WorkoutDetail(firstWorkoutId()))) { click("export_button") }
        captureDialog("x02_export_choice")
    }

    @Test fun x04_export_saved_notice() {
        demo()
        capture("x04_export_saved", startAt = listOf(Screen.WorkoutDetail(firstWorkoutId()))) {
            click("export_button")
            click("export_tcx")
            rule.waitUntil(10_000) { rule.onAllNodesWithTagExists("export_saved") }
            rule.onNodeWithTag("export_saved").performScrollTo()
        }
    }

    @Test fun x05_export_error_no_samples() {
        demo()
        val ana = runBlocking { app.userData.profiles.activeUser.first()!! }
        val empty = app.userData.addWorkout(
            ana.id,
            com.treadz.console.userdata.WorkoutStats(durationSeconds = 0, distanceMeters = 0, calories = 0, averageSpeedKmh = 0.0, maxSpeedKmh = 0.0),
            emptyList(),
            1_000,
            com.treadz.console.userdata.WorkoutEndReason.Stopped,
        )
        capture("x05_export_error", startAt = listOf(Screen.WorkoutDetail(empty))) {
            click("export_button")
            click("export_csv")
            rule.waitUntil(10_000) { rule.onAllNodesWithTagExists("export_failed") }
        }
    }

    private fun androidx.compose.ui.test.junit4.ComposeContentTestRule.onAllNodesWithTagExists(tag: String): Boolean =
        onAllNodes(androidx.compose.ui.test.hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty()
}
