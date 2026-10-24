package com.treadz.console.screenshots

import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import com.github.takahirom.roborazzi.captureRoboImage
import com.treadz.console.fakes.FakeApp
import com.treadz.console.fakes.RecordingWorkout
import com.treadz.console.fakes.createProfile
import com.treadz.console.fakes.setAvatar
import com.treadz.console.nav.Screen
import com.treadz.console.ui.MainActions
import com.treadz.console.ui.PermissionsUi
import com.treadz.console.ui.TreadZRoot
import com.treadz.console.userdata.UserProfile
import com.treadz.console.userdata.WorkoutStats
import com.treadz.console.userdata.memory.DemoData
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode

/**
 * Milestone 2 screens (users, weight, history) rendered on top of the fake data layer. Subclasses set the language
 * and screen size with `@Config(qualifiers = ...)`. Images are only written with `./gradlew :app:recordRoborazziDebug`.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
abstract class UserScreenshotScenarios(private val prefix: String) {

    @get:Rule val rule = createComposeRule()

    private val app = FakeApp()
    private val data get() = app.userData

    /** Opens the app (as after a start) and runs [interact] before taking the picture. */
    private fun capture(name: String, startAt: List<Screen> = emptyList(), interact: () -> Unit = {}) {
        val navigation = app.newNavigation()
        rule.setContent {
            TreadZRoot(dependencies = app, viewModel = app.viewModel, permissions = PermissionsUi(), actions = MainActions(), navigation = navigation)
        }
        rule.waitForIdle()
        // Opened like a user does: first the startup screen, then navigating to the screen of the scenario.
        startAt.forEach(navigation::push)
        rule.waitForIdle()
        interact()
        rule.waitForIdle()
        rule.onRoot().captureRoboImage("build/outputs/roborazzi/${prefix}_$name.png")
    }

    /** Picture of an open dialog (dialogs are separate windows, not part of the root). */
    private fun captureDialog(name: String) {
        rule.waitForIdle()
        rule.onNode(isDialog()).captureRoboImage("build/outputs/roborazzi/${prefix}_$name.png")
    }

    private fun click(tag: String) {
        // Scrolls only when the node is inside a scrollable container.
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

    /** Ana has an unfinished first week and the generic profile is replaced (see [DemoData]). */
    private fun firstWorkoutId(user: UserProfile): Long = runBlocking { data.workouts.workouts(user.id).first().last().id }

    // ---- User selection ----

    @Test fun u01_selection_two_users() {
        demo()
        capture("u01_selection")
    }

    @Test fun u02_selection_with_generic_profile() {
        data.createProfile("Ana", age = 39)
        capture("u02_selection_generic")
    }

    @Test fun u03_selection_locked_while_running() {
        demo()
        app.controller.showConnected()
        app.controller.showRunning()
        capture("u03_selection_locked")
    }

    @Test fun u04_selection_nobody_active() {
        demo()
        runBlocking { data.profiles.setActiveUser(null) }
        capture("u04_selection_nobody")
    }

    @Test fun u05_selection_with_avatars() {
        val (ana, beto) = demo()
        data.setAvatar(ana, "woman")
        data.setAvatar(beto, "man")
        capture("u05_selection_avatars")
    }

    // ---- Main screen with users ----

    @Test fun m01_main_generic_user_banner() {
        app.controller.showConnected()
        capture("m01_main_generic")
    }

    @Test fun m02_main_named_user_running() {
        demo()
        app.controller.showConnected()
        app.controller.showRunning()
        capture("m02_main_running", startAt = listOf(Screen.Main))
    }

    @Test fun m03_main_unassigned_badge() {
        demo()
        app.controller.showConnected()
        capture("m03_main_unassigned", startAt = listOf(Screen.Main))
    }

    @Test fun m04_main_without_user_start_disabled() {
        demo()
        runBlocking { data.profiles.setActiveUser(null) }
        app.controller.showConnected()
        capture("m04_main_no_user", startAt = listOf(Screen.Main))
    }

    @Test fun m05_main_user_chip_with_avatar() {
        val (ana, _) = demo()
        data.setAvatar(ana, "woman")
        runBlocking { data.profiles.setActiveUser(ana.id) }
        app.controller.showConnected()
        capture("m05_main_avatar", startAt = listOf(Screen.Main))
    }

    // ---- Profile editor ----

    @Test fun e01_editor_new_user() {
        capture("e01_editor_new", startAt = listOf(Screen.ProfileEditor(null, 1)))
    }

    @Test fun e02_editor_new_user_validation_errors() {
        capture("e02_editor_errors", startAt = listOf(Screen.ProfileEditor(null, 1))) {
            type("field_height", "50")
            click("save_button")
        }
    }

    @Test fun e08_editor_heart_rate_errors() {
        capture("e08_editor_heart_rate_errors", startAt = listOf(Screen.ProfileEditor(null, 1))) {
            type("field_resting_hr", "110")
            type("field_max_hr", "105")
            click("save_button")
            runCatching { rule.onNodeWithTag("field_max_hr").performScrollTo() }
        }
    }

    @Test fun e03_editor_personalize_generic() {
        val id = runBlocking { data.profiles.activeUserId()!! }
        capture("e03_editor_personalize", startAt = listOf(Screen.ProfileEditor(id, 1)))
    }

    @Test fun e04_editor_personalize_errors() {
        val id = runBlocking { data.profiles.activeUserId()!! }
        capture("e04_editor_personalize_errors", startAt = listOf(Screen.ProfileEditor(id, 1))) {
            type("field_weight", "12")
            click("save_button")
        }
    }

    @Test fun e05_editor_edit_user() {
        val (ana, _) = demo()
        capture("e05_editor_edit", startAt = listOf(Screen.ProfileEditor(ana.id, 1)))
    }

    @Test fun e09_editor_avatar_choice() {
        val (ana, _) = demo()
        data.setAvatar(ana, "woman")
        capture("e09_editor_avatar", startAt = listOf(Screen.ProfileEditor(ana.id, 1)))
    }

    @Test fun e06_editor_name_taken() {
        val (_, beto) = demo()
        capture("e06_editor_name_taken", startAt = listOf(Screen.ProfileEditor(beto.id, 1))) {
            runCatching { rule.onNodeWithTag("field_name").performScrollTo() }
            rule.onNodeWithTag("field_name").performTextReplacement("ana")
            click("save_button")
        }
    }

    @Test fun e07_editor_delete_confirmation() {
        val (ana, _) = demo()
        capture("e07_editor_delete_user", startAt = listOf(Screen.ProfileEditor(ana.id, 1))) { click("delete_button") }
        captureDialog("e07_delete_dialog")
    }

    // ---- Weight ----

    @Test fun w01_weight_placeholder_only() {
        capture("w01_weight_placeholder", startAt = listOf(Screen.Weight))
    }

    @Test fun w02_weight_chart_with_target() {
        demo()
        capture("w02_weight_chart", startAt = listOf(Screen.Weight))
    }

    @Test fun w03_weight_add_dialog() {
        demo()
        capture("w03_weight_add", startAt = listOf(Screen.Weight)) { click("add_weight_button") }
        captureDialog("w03_weight_add_dialog")
    }

    @Test fun w04_weight_add_dialog_error() {
        demo()
        capture("w04_weight_add_error", startAt = listOf(Screen.Weight)) {
            click("add_weight_button")
            type("weight_field", "7")
            click("weight_save")
        }
        captureDialog("w04_weight_add_error_dialog")
    }

    @Test fun w05_weight_target_dialog() {
        demo()
        capture("w05_weight_target", startAt = listOf(Screen.Weight)) { click("set_target_button") }
        captureDialog("w05_weight_target_dialog")
    }

    @Test fun w06_weight_placeholder_then_real_entry() {
        capture("w06_weight_enter_real", startAt = listOf(Screen.Weight)) { click("enter_real_weight_button") }
        captureDialog("w06_weight_enter_real_dialog")
    }

    @Test fun w07_weight_single_real_entry() {
        val ana = data.createProfile("Ana")
        runBlocking {
            data.profiles.setActiveUser(ana.id)
            data.weights.add(ana.id, app.date, 64.2)
        }
        capture("w07_weight_single", startAt = listOf(Screen.Weight))
    }

    @Test fun w08_weight_delete_confirmation() {
        demo()
        val id = runBlocking { data.weights.entries(data.profiles.activeUserId()!!).first().last().id }
        capture("w08_weight_delete", startAt = listOf(Screen.Weight)) { click("delete_weight_$id") }
        captureDialog("w08_weight_delete_dialog")
    }

    // ---- History ----

    @Test fun h01_history_list() {
        demo()
        capture("h01_history", startAt = listOf(Screen.History))
    }

    @Test fun h02_history_unassigned_tab() {
        demo()
        capture("h02_history_unassigned", startAt = listOf(Screen.History)) { click("tab_unassigned") }
    }

    @Test fun h03_history_empty() {
        capture("h03_history_empty", startAt = listOf(Screen.History))
    }

    @Test fun h04_history_assign_dialog() {
        demo()
        val id = runBlocking { data.workouts.unassignedWorkouts().first().first().id }
        capture("h04_history_assign", startAt = listOf(Screen.History)) {
            click("tab_unassigned")
            click("assign_workout_$id")
        }
        captureDialog("h04_assign_dialog")
    }

    @Test fun h05_history_in_progress_and_ended_reasons() {
        val (ana, _) = demo()
        runBlocking { data.profiles.setActiveUser(ana.id) }
        data.addWorkout(
            ana.id,
            WorkoutStats(durationSeconds = 18 * 60, distanceMeters = 2_400, calories = 160, averageSpeedKmh = 8.0, maxSpeedKmh = 9.0, averageHeartRateBpm = 138, maxHeartRateBpm = 160),
            emptyList(),
            app.date.atTime(7, 0).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli(),
            com.treadz.console.userdata.WorkoutEndReason.SafetyKeyRemoved,
        )
        val recording = RecordingWorkout(app, seconds = 95).start()
        try {
            capture("h05_history_in_progress", startAt = listOf(Screen.History))
        } finally {
            recording.close()
        }
    }

    @Test fun h06_history_delete_confirmation() {
        val (ana, _) = demo()
        val id = firstWorkoutId(ana)
        capture("h06_history_detail_delete", startAt = listOf(Screen.History, Screen.WorkoutDetail(id))) { click("delete_workout_button") }
        captureDialog("h06_detail_delete_dialog")
    }

    // ---- Workout detail ----

    @Test fun d01_workout_detail() {
        val (ana, _) = demo()
        capture("d01_detail", startAt = listOf(Screen.History, Screen.WorkoutDetail(firstWorkoutId(ana))))
    }

    @Test fun d02_workout_detail_unassigned_without_heart_rate() {
        demo()
        val id = runBlocking { data.workouts.unassignedWorkouts().first().first().id }
        capture("d02_detail_unassigned", startAt = listOf(Screen.History, Screen.WorkoutDetail(id)))
    }

    @Test fun d03_workout_detail_in_progress() {
        val recording = RecordingWorkout(app, seconds = 120).start()
        try {
            val id = runBlocking { data.workouts.inProgressWorkout().first()!!.id }
            capture("d03_detail_in_progress", startAt = listOf(Screen.History, Screen.WorkoutDetail(id)))
        } finally {
            recording.close()
        }
    }

    @Test fun d04_workout_detail_charts_scrolled() {
        val (ana, _) = demo()
        capture("d04_detail_charts", startAt = listOf(Screen.History, Screen.WorkoutDetail(firstWorkoutId(ana)))) {
            runCatching { rule.onNodeWithTag("chart_heart_rate").performScrollTo() }
        }
    }

    // ---- Light theme ----

    @Test fun l01_light_selection() {
        data.createProfile("Ana", age = 39)
        app.viewModel.setDarkTheme(false)
        capture("l01_light_selection")
    }

    @Test fun l02_light_weight() {
        demo()
        app.viewModel.setDarkTheme(false)
        capture("l02_light_weight", startAt = listOf(Screen.Weight))
    }

    @Test fun l03_light_history() {
        demo()
        app.viewModel.setDarkTheme(false)
        capture("l03_light_history", startAt = listOf(Screen.History))
    }

    @Test fun l04_light_editor_errors() {
        app.viewModel.setDarkTheme(false)
        capture("l04_light_editor_errors", startAt = listOf(Screen.ProfileEditor(null, 1))) { click("save_button") }
    }

    @Test fun l05_light_detail() {
        val (ana, _) = demo()
        app.viewModel.setDarkTheme(false)
        capture("l05_light_detail", startAt = listOf(Screen.History, Screen.WorkoutDetail(firstWorkoutId(ana))))
    }
}
