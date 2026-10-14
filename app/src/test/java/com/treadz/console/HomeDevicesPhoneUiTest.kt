package com.treadz.console

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.treadz.console.fakes.FakeApp
import com.treadz.console.fakes.FakeHeartRateMonitor
import com.treadz.console.nav.Screen
import com.treadz.console.ui.MainActions
import com.treadz.console.ui.PermissionsUi
import com.treadz.console.ui.TreadZRoot
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The home, the indicators and Devices on a phone in portrait: everything reachable, nothing cut off the screen. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "en-w360dp-h780dp-port-xhdpi")
class HomeDevicesPhoneUiTest {

    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val app = FakeApp()

    private fun show(vararg startAt: Screen) {
        app.viewModel
        rule.setContent { TreadZRoot(app, app.viewModel, PermissionsUi(), MainActions(), app.navigation) }
        rule.waitForIdle()
        startAt.forEach(app.navigation::push)
        rule.waitForIdle()
    }

    @Test fun theHomeAndBothIndicatorsFitThePhone() {
        val id = runBlocking { app.userData.profiles.activeUserId()!! }
        runBlocking { app.userData.profiles.setStrap(id, FakeHeartRateMonitor.STRAP_ADDRESS, FakeHeartRateMonitor.STRAP_NAME) }
        app.strapMonitor.showConnected(bpm = 71)
        show()
        rule.onNodeWithTag("home_screen").assertIsDisplayed()
        rule.onNodeWithTag("treadmill_chip").assertIsDisplayed()
        rule.onNodeWithTag("strap_chip_status", useUnmergedTree = true).assertTextEquals("71 bpm")
        rule.onNodeWithTag("home_train").assertIsDisplayed()
        rule.onNodeWithTag("home_history").performScrollTo().assertIsDisplayed()
    }

    @Test fun theIndicatorsOpenDevicesOnAPhoneToo() {
        show()
        rule.onNodeWithTag("treadmill_chip").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("devices_screen").assertIsDisplayed()
        rule.onNodeWithTag("devices_treadmill").assertIsDisplayed()
        rule.onNodeWithTag("devices_strap").performScrollTo().assertIsDisplayed()
    }

    @Test fun theLiveScreenShowsTheSourceIconOnAPhone() {
        app.controller.showConnected()
        app.controller.showRunning(heartRate = 133)
        show()
        rule.onNodeWithTag("hr_source_TreadmillConsole", useUnmergedTree = true).assertIsDisplayed()
        assertEquals(133, app.viewModel.uiState.value.strap.heartRate.bpm)
    }

    @Test fun theRestingScreenWorksOnAPhone() {
        show(Screen.RestingHeartRate)
        rule.onNodeWithTag("resting_screen").assertIsDisplayed()
        rule.onNodeWithTag("resting_needs_strap").assertIsDisplayed()
        rule.onNodeWithTag("resting_list").performScrollToNode(hasTestTag("resting_add_manual"))
        rule.onNodeWithTag("resting_add_manual").assertIsDisplayed()
    }
}
