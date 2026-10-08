package com.treadz.console

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.treadz.console.fakes.FakeApp
import com.treadz.console.nav.Screen
import com.treadz.console.sync.github.RepositoryRef
import com.treadz.console.sync.service.SyncSetup
import com.treadz.console.sync.service.SyncStatus
import com.treadz.console.ui.MainActions
import com.treadz.console.ui.PermissionsUi
import com.treadz.console.ui.TreadZRoot
import com.treadz.console.userdata.WorkoutEndReason
import com.treadz.console.userdata.WorkoutStats
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Settings entry point and the history's sync indicator on a phone in portrait. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "en-w360dp-h780dp-port-xhdpi")
class SettingsPhoneUiTest {

    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val app = FakeApp()

    private fun show(vararg then: Screen) {
        val navigation = app.newNavigation()
        rule.setContent { TreadZRoot(app, app.viewModel, PermissionsUi(), MainActions(), navigation) }
        rule.waitForIdle()
        then.forEach(navigation::push)
        rule.waitForIdle()
    }

    private fun click(tag: String) {
        runCatching { rule.onNodeWithTag(tag).performScrollTo() }
        rule.onNodeWithTag(tag).performClick()
        rule.waitForIdle()
    }

    @Test fun theGearReplacesTheThemeSwitchInTheTopBar() {
        app.controller.showConnected()
        show()
        rule.onNodeWithTag("settings_button").assertIsDisplayed().assertIsEnabled()
        rule.onNodeWithTag("theme_switch").assertDoesNotExist()
        click("settings_button")
        rule.onNodeWithTag("settings_sync").assertIsDisplayed()
    }

    @Test fun theGearIsHiddenOnceTheBeltIsMovingAndDisabledWhileStarting() {
        app.controller.showConnected()
        show()
        app.controller.showRunning()
        rule.waitForIdle()
        // The phone gives the room to the controls while the belt runs.
        rule.onNodeWithTag("settings_button").assertDoesNotExist()
    }

    @Test fun allSectionsAreReachableByScrolling() {
        show(Screen.Settings)
        for (tag in listOf("settings_sync", "settings_users", "settings_treadmill", "settings_appearance", "settings_language", "settings_permissions", "settings_about")) {
            rule.onNodeWithTag(tag).performScrollTo().assertIsDisplayed()
        }
    }

    @Test fun historyMarksWorkoutsNotUploadedYetOnlyWhileLinked() {
        val userId = kotlinx.coroutines.runBlocking { app.userData.profiles.activeUserId()!! }
        val id = app.userData.addWorkout(
            userId,
            WorkoutStats(durationSeconds = 600, distanceMeters = 1500, calories = 70, averageSpeedKmh = 9.0, maxSpeedKmh = 9.5),
            emptyList(),
            1_000L,
            WorkoutEndReason.Stopped,
        )
        app.sync.setPendingWorkouts(setOf(id))
        show(Screen.History)
        rule.onNodeWithText("Not uploaded yet").assertDoesNotExist()
        app.sync.emit(SyncStatus(setup = SyncSetup.Linked, repository = RepositoryRef("demo", "treadz-data", "main")))
        rule.waitForIdle()
        rule.onNodeWithText("Not uploaded yet").assertIsDisplayed()
        app.sync.setPendingWorkouts(emptySet())
        rule.waitForIdle()
        rule.onNodeWithText("Not uploaded yet").assertDoesNotExist()
    }
}
