package com.treadz.console

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performTextInput
import com.treadz.console.fakes.FakeApp
import com.treadz.console.fakes.createProfile
import com.treadz.console.nav.Screen
import com.treadz.console.treadmill.TreadmillEvent
import com.treadz.console.ui.MainActions
import com.treadz.console.ui.PermissionsUi
import com.treadz.console.ui.TreadZRoot
import com.treadz.console.userdata.memory.DemoData
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Users, weight and history through the real UI (Compose + ViewModels) on the fake data layer. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "en-w1280dp-h800dp-land-hdpi")
class UserFlowsUiTest {

    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val app = FakeApp()
    private val data get() = app.userData

    private fun show(vararg then: Screen) {
        val navigation = app.newNavigation()
        rule.setContent {
            TreadZRoot(app, app.viewModel, PermissionsUi(), MainActions(), navigation)
        }
        rule.waitForIdle()
        then.forEach(navigation::push)
        rule.waitForIdle()
    }

    private fun click(tag: String) {
        rule.onNodeWithTag(tag).performClick()
        rule.waitForIdle()
    }

    // ---- Startup flow ----

    @Test fun freshInstallOpensTheMainScreenWithTheGenericUser() {
        app.controller.showConnected()
        show()
        rule.onNodeWithTag("user_chip").assertTextContains("User", substring = true)
        rule.onNodeWithTag("generic_banner").assertIsDisplayed()
        rule.onNodeWithTag("start_button").assertIsEnabled()
    }

    @Test fun severalProfilesOpenTheSelectionFirstAndChoosingGoesToMain() {
        val (ana, beto) = runBlocking { DemoData.populate(data, app.date) }
        app.controller.showConnected()
        show()
        rule.onNodeWithTag("user_card_${ana.id}").assertIsDisplayed()
        click("user_card_${beto.id}")
        assertEquals(beto.id, runBlocking { data.profiles.activeUserId() })
        rule.onNodeWithTag("user_chip").assertTextContains("Beto", substring = true)
        rule.onNodeWithTag("start_button").assertIsEnabled()
    }

    @Test fun startIsDisabledWithoutAnActiveUser() {
        runBlocking { DemoData.populate(data, app.date) }
        runBlocking { data.profiles.setActiveUser(null) }
        app.controller.showConnected()
        show(Screen.Main)
        rule.onNodeWithTag("start_button").assertIsNotEnabled()
        rule.onNodeWithText("Choose a user to start.").assertIsDisplayed()
    }

    @Test fun deletingTheActiveUserForcesTheSelection() {
        val ana = data.createProfile("Ana")
        runBlocking { data.profiles.setActiveUser(ana.id) }
        app.controller.showConnected()
        show(Screen.Main)
        rule.onNodeWithTag("start_button").assertIsDisplayed()
        runBlocking { data.profiles.delete(ana.id) }
        rule.waitForIdle()
        rule.onNodeWithTag("add_user_button").assertIsDisplayed()
    }

    // ---- Switching users ----

    @Test fun userChipOpensTheSelectionWhileStopped() {
        data.createProfile("Ana")
        app.controller.showConnected()
        show(Screen.Main)
        click("user_chip")
        rule.onNodeWithTag("add_user_button").assertIsDisplayed()
        click("back_button")
        rule.onNodeWithTag("start_button").assertIsDisplayed()
    }

    @Test fun userChipIsLockedWhileTheBeltRuns() {
        data.createProfile("Ana")
        app.controller.showConnected()
        app.controller.showRunning()
        show(Screen.Main)
        rule.onNodeWithTag("user_chip").assertIsNotEnabled()
    }

    @Test fun otherUsersCannotBeChosenWhileTheBeltRuns() {
        val ana = data.createProfile("Ana")
        app.controller.showConnected()
        show(Screen.UserSelection)
        app.controller.showRunning()
        rule.waitForIdle()
        rule.onNodeWithTag("switch_locked_notice").assertIsDisplayed()
        rule.onNodeWithTag("user_card_${ana.id}").assertIsNotEnabled()
        click("user_card_${ana.id}")
        assertTrue(runBlocking { data.profiles.activeUserId() } != ana.id)
    }

    // ---- Profile editor ----

    @Test fun personalizeFromTheMainScreenShowsTranslatedErrorsNextToTheFields() {
        app.controller.showConnected()
        show()
        click("personalize_button")
        click("save_button")
        rule.onNodeWithText("Enter a name").assertIsDisplayed()
        rule.onNodeWithText("Enter the height").assertIsDisplayed()
        rule.onNodeWithText("Choose the birth date").assertIsDisplayed()
        // The profile is still the generic one.
        assertTrue(runBlocking { data.profiles.activeUser.first()!!.isGeneric })
    }

    @Test fun invalidHeightShowsTheRangeFromTheDataLayer() {
        show()
        click("personalize_button")
        rule.onNodeWithTag("field_height").performTextInput("50")
        click("save_button")
        rule.onNodeWithText("Enter a height between 100 and 230 cm").assertIsDisplayed()
    }

    @Test fun deletingAUserAsksForConfirmationAndExplainsTheConsequences() {
        val ana = data.createProfile("Ana")
        show(Screen.ProfileEditor(ana.id, 1))
        click("delete_button")
        rule.onNodeWithText("This also deletes all the weights and workouts of this user. It cannot be undone.").assertIsDisplayed()
        click("cancel_button")
        assertEquals(2, runBlocking { data.profiles.profiles.first().size })
        click("delete_button")
        click("confirm_button")
        assertEquals(1, runBlocking { data.profiles.profiles.first().size })
    }

    // ---- Weight ----

    @Test fun weightScreenInvitesToReplaceThePlaceholder() {
        show(Screen.Weight)
        rule.onNodeWithTag("placeholder_banner").assertIsDisplayed()
        rule.onNodeWithTag("current_weight").assertTextContains("80.0")
    }

    @Test fun addingAWeightFromTheScreenReplacesThePlaceholder() {
        show(Screen.Weight)
        click("add_weight_button")
        rule.onNodeWithTag("weight_field").performTextInput("71.5")
        click("weight_save")
        rule.onNodeWithTag("current_weight").assertTextContains("71.5")
        rule.onNodeWithTag("placeholder_banner").assertDoesNotExist()
    }

    @Test fun invalidWeightShowsTheErrorInTheDialog() {
        show(Screen.Weight)
        click("add_weight_button")
        rule.onNodeWithTag("weight_field").performTextInput("5")
        click("weight_save")
        rule.onNodeWithText("Enter a weight between 25 and 280 kg").assertIsDisplayed()
        rule.onNodeWithTag("weight_editor").assertIsDisplayed()
    }

    @Test fun targetWeightIsSetFromTheScreen() {
        show(Screen.Weight)
        click("set_target_button")
        rule.onNodeWithTag("weight_field").performTextInput("70")
        click("weight_save")
        rule.onNodeWithTag("target_weight").assertTextContains("70.0")
    }

    // ---- History ----

    @Test fun historyListsWorkoutsAndOpensTheDetail() {
        val (ana, _) = runBlocking { DemoData.populate(data, app.date) }
        show(Screen.History)
        val latest = runBlocking { data.workouts.workouts(ana.id).first().first() }
        rule.onNodeWithTag("workout_${latest.id}").assertIsDisplayed()
        click("workout_${latest.id}")
        rule.onNodeWithTag("chart_speed").assertIsDisplayed()
    }

    @Test fun unassignedWorkoutCanBeAssignedToAUser() {
        val (_, beto) = runBlocking { DemoData.populate(data, app.date) }
        show(Screen.History)
        val orphan = runBlocking { data.workouts.unassignedWorkouts().first().single() }
        click("tab_unassigned")
        click("assign_workout_${orphan.id}")
        click("assign_to_${beto.id}")
        assertEquals(beto.id, runBlocking { data.workouts.detail(orphan.id)!!.summary.userId })
        rule.onNodeWithTag("tab_unassigned").assertDoesNotExist()
    }

    @Test fun deletingAWorkoutAsksForConfirmation() {
        val (ana, _) = runBlocking { DemoData.populate(data, app.date) }
        val id = runBlocking { data.workouts.workouts(ana.id).first().first().id }
        show(Screen.History, Screen.WorkoutDetail(id))
        click("delete_workout_button")
        click("cancel_button")
        assertTrue(runBlocking { data.workouts.detail(id) } != null)
        click("delete_workout_button")
        click("confirm_button")
        assertNull(runBlocking { data.workouts.detail(id) })
        // Back on the list, without the deleted workout.
        rule.onNodeWithTag("workout_$id").assertDoesNotExist()
    }

    @Test fun emptyHistoryExplainsWhatToDo() {
        show(Screen.History)
        rule.onNodeWithTag("history_empty").assertIsDisplayed()
    }

    // ---- Safety alerts stay on top of everything ----

    @Test fun safetyAlertCoversTheOtherScreens() {
        app.controller.showConnected()
        show(Screen.Weight)
        app.controller.emit(TreadmillEvent.SafetyKeyRemoved)
        rule.waitForIdle()
        rule.onNodeWithTag("alert_overlay").assertIsDisplayed()
        click("alert_dismiss")
        rule.onNodeWithTag("alert_overlay").assertDoesNotExist()
    }

    @Test fun inProgressWorkoutIsShownAsSuch() {
        val recording = com.treadz.console.fakes.RecordingWorkout(app, seconds = 20).start()
        try {
            show(Screen.History)
            // The card is a single clickable node: look inside it.
            rule.onNodeWithTag("in_progress_tag", useUnmergedTree = true).assertIsDisplayed()
        } finally {
            recording.close()
        }
    }
}
