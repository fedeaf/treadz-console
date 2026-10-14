package com.treadz.console

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.treadz.console.fakes.FakeApp
import com.treadz.console.fakes.FakeHeartRateMonitor
import com.treadz.console.fakes.FakeTreadmillController
import com.treadz.console.fakes.InMemorySettingsStore
import com.treadz.console.fakes.createProfile
import com.treadz.console.hrm.StrapProblem
import com.treadz.console.nav.Screen
import com.treadz.console.settings.RememberedTreadmill
import com.treadz.console.treadmill.BeltStatus
import com.treadz.console.treadmill.ConnectionState
import com.treadz.console.ui.MainActions
import com.treadz.console.ui.PermissionsUi
import com.treadz.console.ui.TreadZRoot
import com.treadz.console.userdata.RestingHeartRateInput
import com.treadz.console.userdata.RestingHeartRateSource
import com.treadz.console.userdata.memory.DemoData
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The home (shown while the treadmill is not connected), its switch to the live data and back, and the Devices screen
 * with its two separate sections, on the real UI over the fakes.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "en-w1280dp-h800dp-land-hdpi")
class HomeAndDevicesUiTest {

    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private var app = FakeApp()
    private val controller get() = app.controller
    private val monitor get() = app.strapMonitor
    private var permissionRequests = 0

    private fun show(permissions: PermissionsUi = PermissionsUi(), vararg startAt: Screen) {
        app.viewModel
        rule.setContent {
            TreadZRoot(app, app.viewModel, permissions, MainActions(onRequestPermissions = { permissionRequests++ }), app.navigation)
        }
        rule.waitForIdle()
        startAt.forEach(app.navigation::push)
        rule.waitForIdle()
    }

    private fun assertNoText(text: String) {
        assertEquals("Unexpected text on screen: $text", 0, rule.onAllNodesWithText(text, substring = true, ignoreCase = true).fetchSemanticsNodes().size)
    }

    private fun idle() = rule.waitForIdle()

    // ---- Home and live data ----

    @Test fun withoutTheTreadmillTheMainScreenIsTheHome() {
        show()
        rule.onNodeWithTag("home_screen").assertIsDisplayed()
        rule.onNodeWithTag("home_train").assertIsDisplayed()
        rule.onNodeWithTag("home_programs").assertIsDisplayed()
        rule.onNodeWithTag("home_resting").assertIsDisplayed()
        rule.onNodeWithTag("home_weight").assertIsDisplayed()
        rule.onNodeWithTag("home_history").assertIsDisplayed()
        rule.onNodeWithTag("start_button").assertDoesNotExist()
        // No full-screen connection pane any more.
        rule.onNodeWithTag("scan_button").assertDoesNotExist()
        rule.onNodeWithTag("reconnect_button").assertDoesNotExist()
    }

    @Test fun theGenericUserAndNotificationBannersStayOnTheHome() {
        show(PermissionsUi(notificationsDenied = true))
        rule.onNodeWithTag("generic_banner").assertIsDisplayed()
        rule.onNodeWithTag("notifications_denied").assertIsDisplayed()
    }

    @Test fun trainOpensDevicesBecauseThereIsNoTreadmill() {
        show()
        rule.onNodeWithTag("home_train").performClick()
        idle()
        rule.onNodeWithTag("devices_screen").assertIsDisplayed()
    }

    @Test fun connectingSwitchesToTheLiveDataByItself() {
        show()
        controller.showConnected()
        idle()
        rule.onNodeWithTag("home_screen").assertDoesNotExist()
        rule.onNodeWithTag("start_button").assertIsEnabled()
    }

    @Test fun disconnectingAtRestGoesBackToTheHome() {
        controller.showConnected()
        show()
        rule.onNodeWithTag("start_button").assertIsDisplayed()
        controller.connection.value = ConnectionState.Disconnected
        idle()
        rule.onNodeWithTag("home_screen").assertIsDisplayed()
        rule.onNodeWithTag("start_button").assertDoesNotExist()
    }

    @Test fun neverLeavesTheLiveDataDuringAWorkout() {
        controller.showConnected()
        controller.showRunning()
        show()
        rule.onNodeWithTag("stop_button").assertIsDisplayed()
        // The connection goes: reconnecting, then given up, then plainly disconnected. The belt may still be moving.
        controller.showReconnecting(attempt = 2)
        idle()
        rule.onNodeWithTag("reconnecting_banner").assertIsDisplayed()
        rule.onNodeWithTag("home_screen").assertDoesNotExist()
        controller.connection.value = ConnectionState.Disconnected
        idle()
        rule.onNodeWithTag("home_screen").assertDoesNotExist()
        rule.onNodeWithTag("stop_unreachable").assertIsDisplayed()
        // Only once the belt is at rest again does the home come back.
        controller.showState { it.copy(status = BeltStatus.Idle, speedKmh = 0.0, targetSpeedKmh = null) }
        idle()
        rule.onNodeWithTag("home_screen").assertIsDisplayed()
    }

    @Test fun theShortcutsOpenTheirScreens() {
        show()
        rule.onNodeWithTag("home_programs").performClick()
        idle()
        rule.onNodeWithTag("screen_title").assertTextEquals("Programs")
        app.navigation.pop()
        idle()
        rule.onNodeWithTag("home_resting").performClick()
        idle()
        rule.onNodeWithTag("resting_screen").assertIsDisplayed()
        app.navigation.pop()
        idle()
        rule.onNodeWithTag("home_weight").performClick()
        idle()
        rule.onNodeWithTag("add_weight_button").assertIsDisplayed()
        app.navigation.pop()
        idle()
        rule.onNodeWithTag("home_history").performClick()
        idle()
        assertTrue(app.navigation.stack.value!!.last() == Screen.History)
    }

    @Test fun theCardsSayWhatTheUserHas() {
        runBlocking { DemoData.populate(app.userData, app.date) }
        val ana = runBlocking { app.userData.profiles.activeUserId()!! }
        runBlocking {
            for (daysAgo in 1..3) {
                app.userData.restingHeartRates.add(ana, RestingHeartRateInput(app.nowMillis() - daysAgo * 86_400_000L, 56 + daysAgo, RestingHeartRateSource.Manual))
            }
        }
        show(startAt = arrayOf(Screen.Main))
        rule.onNodeWithTag("home_resting_value", useUnmergedTree = true).assertTextEquals("58")
        rule.onNodeWithTag("home_resting_note", useUnmergedTree = true).assertTextContains("median of 4 measurements", substring = true)
        rule.onNodeWithTag("home_weight_value", useUnmergedTree = true).assertTextContains("64.2", substring = true)
        rule.onNodeWithTag("home_history_note", useUnmergedTree = true).assertTextContains("km", substring = true)
    }

    @Test fun withoutDataTheCardsInviteToFillThemIn() {
        show()
        rule.onNodeWithTag("home_resting_note", useUnmergedTree = true).assertTextContains("Not measured yet", substring = true)
        rule.onNodeWithTag("home_weight_note", useUnmergedTree = true).assertTextContains("Enter your weight", substring = true)
        rule.onNodeWithTag("home_history_note", useUnmergedTree = true).assertTextContains("No workouts yet", substring = true)
    }

    @Test fun theTrainCardSaysWhyItIsNotReady() {
        app = FakeApp(settings = InMemorySettingsStore(RememberedTreadmill(FakeTreadmillController.TREADMILL_ADDRESS, "REEBOK 0517")))
        controller.showBluetoothOff()
        show()
        rule.onNodeWithTag("home_train_note", useUnmergedTree = true).assertTextContains("Bluetooth is off", substring = true)
    }

    // ---- The indicators in the top bar ----

    @Test fun theTwoIndicatorsAreSeparateAndOpenDevices() {
        controller.showConnected()
        val id = runBlocking { app.userData.profiles.activeUserId()!! }
        runBlocking { app.userData.profiles.setStrap(id, FakeHeartRateMonitor.STRAP_ADDRESS, FakeHeartRateMonitor.STRAP_NAME) }
        monitor.showConnected(bpm = 72)
        show()
        rule.onNodeWithTag("treadmill_chip_status", useUnmergedTree = true).assertTextEquals("REEBOK 0517")
        rule.onNodeWithTag("strap_chip_status", useUnmergedTree = true).assertTextEquals("72 bpm")
        rule.onNodeWithTag("strap_chip").performClick()
        idle()
        rule.onNodeWithTag("devices_screen").assertIsDisplayed()
    }

    @Test fun theStrapIndicatorSaysNoneSearchingOrBluetoothOff() {
        show()
        rule.onNodeWithTag("strap_chip_status", useUnmergedTree = true).assertTextEquals("No strap")
        val id = runBlocking { app.userData.profiles.activeUserId()!! }
        runBlocking { app.userData.profiles.setStrap(id, FakeHeartRateMonitor.STRAP_ADDRESS, FakeHeartRateMonitor.STRAP_NAME) }
        idle()
        rule.onNodeWithTag("strap_chip_status", useUnmergedTree = true).assertTextEquals("Searching…")
        monitor.problem.value = StrapProblem.BluetoothOff
        idle()
        rule.onNodeWithTag("strap_chip_status", useUnmergedTree = true).assertTextEquals("Bluetooth off")
    }

    @Test fun theTreadmillIndicatorFollowsTheAutomaticConnection() {
        app = FakeApp(settings = InMemorySettingsStore(RememberedTreadmill(FakeTreadmillController.TREADMILL_ADDRESS, "REEBOK 0517")))
        controller.connectResult = com.treadz.console.treadmill.CommandResult.Rejected(
            com.treadz.console.treadmill.FailureCode.ConnectionFailed, "raw", com.treadz.console.treadmill.ConnectionProblem.DeviceNotFound,
        )
        app.viewModel.onPermissionsGranted()
        show()
        rule.onNodeWithTag("treadmill_chip_status", useUnmergedTree = true).assertTextEquals("Searching…")
    }

    // ---- Devices ----

    @Test fun devicesKeepsTheTreadmillAndTheStrapInTwoSections() {
        show(startAt = arrayOf(Screen.Devices))
        rule.onNodeWithTag("devices_treadmill").assertIsDisplayed()
        rule.onNodeWithTag("devices_strap").assertIsDisplayed()
        rule.onNodeWithTag("scan_button").assertIsEnabled()
        rule.onNodeWithTag("strap_scan_button").assertIsEnabled()
        rule.onNodeWithText("Heart-rate strap of User").assertIsDisplayed()
    }

    @Test fun theTreadmillScanAndTheStrapScanListsAreNeverMixed() {
        controller.scanResults = FakeTreadmillController.SAMPLE_DEVICES
        monitor.scanResults = FakeHeartRateMonitor.SAMPLE_STRAPS
        show(startAt = arrayOf(Screen.Devices))
        rule.onNodeWithTag("scan_button").performClick()
        idle()
        rule.onNodeWithText("REEBOK 0517").assertIsDisplayed()
        assertNoText("HRM-H9Z")
        rule.onNodeWithTag("strap_scan_button").performClick()
        idle()
        rule.onNodeWithText("HRM-H9Z 4821").assertIsDisplayed()
        rule.onNodeWithText("Polar H10 8A2C41").assertIsDisplayed()
    }

    @Test fun pairingAStrapFromTheListSavesItForTheActiveUser() {
        monitor.scanResults = FakeHeartRateMonitor.SAMPLE_STRAPS
        show(startAt = arrayOf(Screen.Devices))
        rule.onNodeWithTag("strap_scan_button").performClick()
        idle()
        rule.onNodeWithText("Polar H10 8A2C41").performClick()
        idle()
        val id = runBlocking { app.userData.profiles.activeUserId()!! }
        assertEquals("D4:11:22:33:44:55", runBlocking { app.userData.profiles.profile(id).first()!!.strapAddress })
        assertEquals("D4:11:22:33:44:55", monitor.address.value)
        rule.onNodeWithTag("strap_name").assertTextEquals("Polar H10 8A2C41")
        // Change and forget are now offered.
        rule.onNodeWithTag("forget_strap").performScrollTo().performClick()
        idle()
        assertEquals(null, monitor.address.value)
        rule.onNodeWithTag("strap_none").assertIsDisplayed()
    }

    @Test fun aConnectedStrapShowsItsBatteryHeartRateAndSource() {
        val id = runBlocking { app.userData.profiles.activeUserId()!! }
        runBlocking { app.userData.profiles.setStrap(id, FakeHeartRateMonitor.STRAP_ADDRESS, FakeHeartRateMonitor.STRAP_NAME) }
        monitor.showConnected(bpm = 68, battery = 64)
        show(startAt = arrayOf(Screen.Devices))
        rule.onNodeWithTag("strap_name").assertTextEquals("HRM-H9Z 4821")
        rule.onNodeWithText("64 %").assertIsDisplayed()
        rule.onNodeWithText("68 bpm").assertIsDisplayed()
        rule.onNodeWithTag("strap_hr_source").assertTextContains("strap", substring = true)
    }

    @Test fun whenTheConsoleHoldsTheStrapDevicesSaysWhereTheHeartRateComesFrom() {
        val id = runBlocking { app.userData.profiles.activeUserId()!! }
        runBlocking { app.userData.profiles.setStrap(id, FakeHeartRateMonitor.STRAP_ADDRESS, FakeHeartRateMonitor.STRAP_NAME) }
        monitor.showSearching()
        controller.showConnected()
        controller.showRunning(heartRate = 120)
        controller.showState { it.copy(status = BeltStatus.Idle, speedKmh = 0.0, targetSpeedKmh = null, heartRateBpm = 90) }
        show(startAt = arrayOf(Screen.Devices))
        rule.onNodeWithTag("strap_hr_source").assertTextContains("console", substring = true)
    }

    @Test fun strapProblemsHaveTheirOwnMessageInTheStrapSection() {
        monitor.problem.value = StrapProblem.PermissionMissing
        show(startAt = arrayOf(Screen.Devices))
        rule.onNodeWithTag("devices_strap").assertIsDisplayed()
        rule.onNodeWithTag("problem_permission_button").performScrollTo().performClick()
        assertEquals(1, permissionRequests)
        // The treadmill section has no message of its own.
        rule.onNodeWithTag("connect_problem").assertIsDisplayed()
    }

    @Test fun theTreadmillSectionOffersConnectAndForgetForTheRememberedTreadmill() {
        app = FakeApp(settings = InMemorySettingsStore(RememberedTreadmill(FakeTreadmillController.TREADMILL_ADDRESS, "REEBOK 0517")))
        controller.showConnected()
        controller.connection.value = ConnectionState.Disconnected
        show(startAt = arrayOf(Screen.Devices))
        rule.onNodeWithTag("treadmill_remembered").assertTextContains("REEBOK 0517", substring = true)
        rule.onNodeWithTag("reconnect_button").performClick()
        idle()
        assertTrue(app.viewModel.uiState.value.isConnected)
        rule.onNodeWithTag("reconnect_button").assertDoesNotExist()
        rule.onNodeWithTag("devices_forget_treadmill").performScrollTo().performClick()
        idle()
        assertEquals(null, app.settings.lastTreadmill)
    }

    @Test fun theTreadmillSectionSaysWhenTheAutomaticSearchGaveUp() {
        app = FakeApp(settings = InMemorySettingsStore(RememberedTreadmill(FakeTreadmillController.TREADMILL_ADDRESS, "REEBOK 0517")))
        controller.connectResult = com.treadz.console.treadmill.CommandResult.Rejected(
            com.treadz.console.treadmill.FailureCode.ConnectionFailed, "raw", com.treadz.console.treadmill.ConnectionProblem.DeviceNotFound,
        )
        // A manual attempt that did not find it: "not found" with Connect still on offer.
        show(startAt = arrayOf(Screen.Devices))
        rule.onNodeWithTag("reconnect_button").performClick()
        idle()
        rule.onNodeWithTag("treadmill_state").assertTextEquals("Not found")
        rule.onNodeWithTag("reconnect_button").assertIsEnabled()
    }

    @Test fun devicesCanBeOpenedFromTheSettings() {
        show(startAt = arrayOf(Screen.Settings))
        rule.onNodeWithTag("settings_open_devices").performScrollTo().performClick()
        idle()
        rule.onNodeWithTag("devices_screen").assertIsDisplayed()
    }

    @Test fun leavingDevicesStopsTheSearches() {
        controller.scanResults = FakeTreadmillController.SAMPLE_DEVICES
        monitor.scanResults = FakeHeartRateMonitor.SAMPLE_STRAPS
        show(startAt = arrayOf(Screen.Devices))
        rule.onNodeWithTag("scan_button").performClick()
        rule.onNodeWithTag("strap_scan_button").performClick()
        idle()
        assertTrue(app.viewModel.uiState.value.scanning)
        app.navigation.pop()
        idle()
        assertTrue(!app.viewModel.uiState.value.scanning)
    }

    @Test fun theStrapOfferCardIsAskedOnTheHomeAndAnswered() {
        monitor.scanResults = FakeHeartRateMonitor.SAMPLE_STRAPS
        monitor.scanNeverEnds = false
        show()
        app.strap.onPermissionsGranted()
        // The scan of the fake ends at once; the real one waits a few seconds, which the controller's own test covers.
        val deadline = System.currentTimeMillis() + 10_000
        while (System.currentTimeMillis() < deadline && app.viewModel.uiState.value.strap.pairingOffer == null) {
            Thread.sleep(100)
            idle()
        }
        rule.onNodeWithTag("strap_offer").assertIsDisplayed()
        rule.onNodeWithText("Use this strap (HRM-H9Z 4821)?").assertIsDisplayed()
        rule.onNodeWithTag("strap_offer_no").performClick()
        idle()
        rule.onNodeWithTag("strap_offer").assertDoesNotExist()
        assertTrue(app.settings.strapQuestionAnswered(runBlocking { app.userData.profiles.activeUserId()!! }))
    }
}
