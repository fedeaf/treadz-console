package com.treadz.console

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performTextClearance
import androidx.compose.ui.test.performTextInput
import com.treadz.console.fakes.FakeApp
import com.treadz.console.fakes.FakeHeartRateMonitor
import com.treadz.console.fakes.TrainingFixtures
import com.treadz.console.fakes.TrainingRig
import com.treadz.console.fakes.createProfile
import com.treadz.console.hrm.SensorContact
import com.treadz.console.nav.Screen
import com.treadz.console.programs.engine.HeartRateControlState
import com.treadz.console.programs.fitness.HeartRateMaxOrigin
import com.treadz.console.programs.plan.ProgramResolver
import com.treadz.console.treadmill.BeltStatus
import com.treadz.console.treadmill.TreadmillState
import com.treadz.console.ui.MainActions
import com.treadz.console.ui.PermissionsUi
import com.treadz.console.ui.TreadZRoot
import com.treadz.console.userdata.HeartRatePeak
import com.treadz.console.userdata.RestingHeartRateInput
import com.treadz.console.userdata.RestingHeartRateSource
import com.treadz.console.userdata.WorkoutStats
import com.treadz.console.userdata.memory.DemoPrograms
import com.treadz.console.programs.fitness.HeartRateSourceKind
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * Heart rate in the app, through the real UI: the source icon next to the heart rate, the resting heart rate screen, the
 * origin of the values in the profile, the observed-peak offer, and the heart-rate guided blocks of a program.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "en-w1280dp-h800dp-land-hdpi")
class HeartRateUiTest {

    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val rig = TrainingRig()
    private val app get() = rig.app
    private val data get() = app.userData
    private val monitor get() = app.strapMonitor
    private val userId get() = runBlocking { data.profiles.activeUserId()!! }
    private val day = 86_400_000L

    private fun show(vararg startAt: Screen) {
        val navigation = app.newNavigation()
        rule.setContent { TreadZRoot(app, app.viewModel, PermissionsUi(), MainActions(), navigation) }
        rule.waitForIdle()
        navigation.replaceAll(Screen.Main)
        startAt.forEach(navigation::push)
        rule.waitForIdle()
    }

    private fun idleBelt(heartRate: Int? = 72) {
        app.controller.state.value = TreadmillState(heartRateBpm = heartRate, updatedAtMillis = rig.scheduler.currentTime)
    }

    private fun pairStrap() {
        runBlocking { data.profiles.setStrap(userId, FakeHeartRateMonitor.STRAP_ADDRESS, FakeHeartRateMonitor.STRAP_NAME) }
    }

    private fun exists(tag: String) = rule.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty()

    // ---- The source next to the heart rate ----

    @Test fun theConsolesHeartRateShowsTheConsoleIcon() {
        app.controller.showConnected()
        idleBelt(heartRate = 96)
        show()
        rule.onNodeWithTag("metric_value_Heart rate", useUnmergedTree = true).assertTextEquals("96")
        rule.onNodeWithTag("hr_source_TreadmillConsole", useUnmergedTree = true).assertIsDisplayed()
        assertFalse(exists("hr_source_DirectStrap"))
    }

    @Test fun theStrapConnectedToTheAppShowsTheStrapIconAndWinsOverTheConsole() {
        pairStrap()
        app.controller.showConnected()
        idleBelt(heartRate = 96)
        monitor.showConnected(bpm = 71)
        show()
        rule.onNodeWithTag("metric_value_Heart rate", useUnmergedTree = true).assertTextEquals("71")
        rule.onNodeWithTag("hr_source_DirectStrap", useUnmergedTree = true).assertIsDisplayed()
        assertFalse(exists("hr_source_TreadmillConsole"))
    }

    @Test fun withNoHeartRateMonitorTheOldTextStays() {
        app.controller.showConnected()
        idleBelt(heartRate = null)
        show()
        rule.onNodeWithText("No heart rate monitor").assertIsDisplayed()
        assertFalse(exists("hr_source_DirectStrap"))
    }

    @Test fun aStrapThatLostContactSaysSoInsteadOfNoMonitor() {
        pairStrap()
        app.controller.showConnected()
        idleBelt(heartRate = null)
        monitor.showConnected(bpm = null)
        monitor.showReading(0, SensorContact.NotDetected)
        show()
        rule.onNodeWithText("Strap: no reading").assertIsDisplayed()
    }

    // ---- Resting heart rate ----

    @Test fun withoutAHeartRateSourceStartExplainsThatAStrapIsNeeded() {
        idleBelt(heartRate = null)
        show(Screen.RestingHeartRate)
        rule.onNodeWithTag("resting_needs_strap").assertIsDisplayed()
        rule.onNodeWithTag("resting_start").assertIsNotEnabled()
        // It works without the treadmill: nothing about connecting it.
        assertTrue(app.controller.connectCalls.isEmpty())
    }

    @Test fun theMeasurementKeepsTheScreenOnAndCancellingExplainsWhyItIsNotValid() {
        pairStrap()
        idleBelt()
        monitor.showConnected(bpm = 61)
        show(Screen.RestingHeartRate)
        rule.onNodeWithTag("resting_source", useUnmergedTree = true).assertTextContains("Strap", substring = true)
        rule.onNodeWithTag("resting_start").assertIsEnabled().performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("resting_countdown").assertIsDisplayed()
        assertTrue(anyViewKeepsScreenOn())
        rule.onNodeWithTag("resting_cancel").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("resting_invalid").assertIsDisplayed()
        rule.onNodeWithText("• It ended before the 3 minutes.", substring = true).assertIsDisplayed()
        assertFalse(anyViewKeepsScreenOn())
        // Nothing was stored.
        assertTrue(runBlocking { data.restingHeartRates.measurements(userId).first() }.isEmpty())
        // "Measure again" is there; "Done" goes back to the instructions.
        rule.onNodeWithTag("resting_done").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("resting_instructions").assertIsDisplayed()
    }

    @Test fun theReferenceChartAndHistoryAreShownAndEntriesCanBeAddedAndDeleted() {
        runBlocking {
            for (daysAgo in 1..3) data.restingHeartRates.add(userId, RestingHeartRateInput(app.nowMillis() - daysAgo * day, 55 + daysAgo, RestingHeartRateSource.Strap))
        }
        idleBelt()
        show(Screen.RestingHeartRate)
        rule.onNodeWithTag("resting_reference_bpm").assertTextContains("57", substring = true)
        rule.onNodeWithTag("resting_reference_origin").assertTextContains("Reference: median of 3 measurements (30 days)", substring = true)
        rule.onNodeWithTag("resting_chart").assertIsDisplayed()

        rule.onNodeWithTag("resting_add_manual").performClick()
        rule.waitForIdle()
        val before = runBlocking { data.restingHeartRates.measurements(userId).first() }.size
        rule.onNodeWithTag("resting_manual_field").performTextInput("300")
        rule.onNodeWithTag("resting_manual_save").performClick()
        rule.waitForIdle()
        rule.onNodeWithText("Enter a value between", substring = true).assertIsDisplayed()
        assertEquals(before, runBlocking { data.restingHeartRates.measurements(userId).first() }.size)
        rule.onNodeWithTag("resting_manual_field").performTextClearance()
        rule.onNodeWithTag("resting_manual_field").performTextInput("58")
        rule.onNodeWithTag("resting_manual_save").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("resting_manual_dialog").assertDoesNotExist()
        assertEquals(before + 1, runBlocking { data.restingHeartRates.measurements(userId).first() }.size)

        val last = runBlocking { data.restingHeartRates.measurements(userId).first() }.last()
        assertEquals(RestingHeartRateSource.Manual, last.source)
        rule.onNodeWithTag("delete_resting_${last.id}").performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("confirm_button").performClick()
        rule.waitForIdle()
        assertEquals(before, runBlocking { data.restingHeartRates.measurements(userId).first() }.size)
    }

    private fun anyViewKeepsScreenOn(): Boolean {
        fun check(v: android.view.View): Boolean =
            v.keepScreenOn || (v is android.view.ViewGroup && (0 until v.childCount).any { check(v.getChildAt(it)) })
        return check(rule.activity.window.decorView)
    }

    // ---- The profile: where the values come from ----

    private fun addResting(vararg daysAgo: Int, bpm: Int = 58) = runBlocking {
        daysAgo.forEach { data.restingHeartRates.add(userId, RestingHeartRateInput(app.nowMillis() - it * day, bpm, RestingHeartRateSource.Manual)) }
    }

    private fun profileOfActive(): Screen = Screen.ProfileEditor(userId, 1)

    @Test fun theRestingValueIsExplainedAsTheMedianOfTheMeasurements() {
        data.createProfile("Ana").also { runBlocking { data.profiles.setActiveUser(it.id) } }
        addResting(1, 2)
        show(profileOfActive())
        // Ana's own typed value at creation plus two more: three in the last 30 days.
        rule.onNodeWithTag("resting_hr_origin", useUnmergedTree = true).assertTextContains("Reference: median of 3 measurements (30 days)", substring = true)
        rule.onNodeWithTag("resting_hr_origin", useUnmergedTree = true).assertTextContains("manual measurement", substring = true)
    }

    @Test fun theRestingValueCanBeProvisionalOrOld() {
        val ana = data.createProfile("Ana")
        runBlocking { data.profiles.setActiveUser(ana.id) }
        // Only the value typed at creation: provisional.
        show(profileOfActive())
        rule.onNodeWithTag("resting_hr_origin", useUnmergedTree = true).assertTextContains("Provisional reference", substring = true)
    }

    @Test fun anOldRestingValueSaysWhenItWasMeasured() {
        val ana = runBlocking {
            (data.profiles.create(com.treadz.console.fakes.profileInput("Ana").copy(restingHeartRateBpm = null)) as com.treadz.console.userdata.DataResult.Success).value
        }
        runBlocking {
            data.profiles.setActiveUser(ana.id)
            data.restingHeartRates.add(ana.id, RestingHeartRateInput(app.nowMillis() - 45 * day, 61, RestingHeartRateSource.Strap))
        }
        show(profileOfActive())
        rule.onNodeWithTag("resting_hr_origin", useUnmergedTree = true).assertTextContains("over 30 days ago", substring = true)
    }

    @Test fun theMaximumIsEnteredMeasuredOrEstimated() {
        val ana = data.createProfile("Ana", age = 35)
        runBlocking { data.profiles.setActiveUser(ana.id) }
        show(profileOfActive())
        rule.onNodeWithTag("max_hr_estimate", useUnmergedTree = true).assertTextContains("Estimated from your age", substring = true)

        // Measured by a maximal test.
        runBlocking { data.fitnessTests.save(ana.id, DemoPrograms.maxTest(startedAtMillis = app.nowMillis() - 10 * day, peakBpm = 181, ageYears = 35)) }
        assertEquals(HeartRateMaxOrigin.Measured, runBlocking { data.fitnessTests.heartRateMax(ana.id).first()!!.origin })
        rule.waitForIdle()
        rule.onNodeWithTag("max_hr_estimate", useUnmergedTree = true).performScrollTo().assertTextContains("Measured in the test of", substring = true)
    }

    @Test fun aTypedMaximumSaysItWasEnteredByTheUser() {
        val ana = data.createProfile("Ana", age = 35)
        runBlocking { data.profiles.setActiveUser(ana.id) }
        show(profileOfActive())
        rule.onNodeWithTag("field_max_hr").performScrollTo().performTextInput("190")
        rule.waitForIdle()
        rule.onNodeWithTag("max_hr_estimate", useUnmergedTree = true).assertTextContains("Entered by you", substring = true)
    }

    @Test fun theProfileLinksToTheMaximumDetailAndTheRestingScreen() {
        val ana = data.createProfile("Ana", age = 35)
        runBlocking { data.profiles.setActiveUser(ana.id) }
        show(profileOfActive())
        rule.onNodeWithTag("open_hrmax").performScrollTo().performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("hrmax_screen").assertIsDisplayed()
        rule.onNodeWithTag("hrmax_current_origin").assertTextContains("Estimated from your age", substring = true)
        rule.onNodeWithTag("hrmax_in_use_tag").assertIsDisplayed()
    }

    // ---- The observed-peak offer ----

    /** The home is shown only without a treadmill connection (the rig starts connected and running). */
    private fun disconnected() {
        app.controller.connection.value = com.treadz.console.treadmill.ConnectionState.Disconnected
        app.controller.state.value = TreadmillState()
    }

    private fun workoutWithPeak(userId: Long, bpm: Int) {
        val at = app.nowMillis() - day
        data.addWorkout(userId, WorkoutStats(durationSeconds = 1800, distanceMeters = 5000, peakHeartRate = HeartRatePeak(bpm, HeartRateSourceKind.DirectStrap, at)), emptyList(), at - 1_800_000)
    }

    @Test fun theOfferOnTheHomeCanBeAcceptedAndThenGoes() {
        val ana = data.createProfile("Ana", age = 35)
        runBlocking { data.profiles.setActiveUser(ana.id) }
        val estimated = runBlocking { data.fitnessTests.heartRateMax(ana.id).first()!!.bpm }
        workoutWithPeak(ana.id, estimated + 11)
        disconnected()
        show()
        rule.onNodeWithTag("peak_offer_text", useUnmergedTree = true).assertTextContains("reached ${estimated + 11} bpm, above your maximum of $estimated bpm", substring = true)
        rule.onNodeWithTag("peak_offer_yes").performClick()
        rule.waitForIdle()
        assertFalse(exists("peak_offer"))
        assertEquals(estimated + 11, runBlocking { data.profiles.profile(ana.id).first()!!.maxHeartRateBpm })
    }

    @Test fun theOfferOnTheProfileCanBeDeclined() {
        val ana = data.createProfile("Ana", age = 35)
        runBlocking { data.profiles.setActiveUser(ana.id) }
        val estimated = runBlocking { data.fitnessTests.heartRateMax(ana.id).first()!!.bpm }
        workoutWithPeak(ana.id, estimated + 11)
        show(profileOfActive())
        rule.onNodeWithTag("peak_offer").performScrollTo().assertIsDisplayed()
        rule.onNodeWithTag("peak_offer_no").performClick()
        rule.waitForIdle()
        assertFalse(exists("peak_offer"))
        assertNotNull(app.settings.peakAnsweredAtMillis(ana.id))
    }

    // ---- Heart-rate guided blocks ----

    private fun startHeartRateProgram() {
        idleBelt(heartRate = 72)
        val plan = ProgramResolver.resolve(TrainingFixtures.heartRateProgram())
        assertEquals(com.treadz.console.training.StartOutcome.Started, rig.startProgram(plan, userId))
        // Into the guided block (after the 2-minute warm-up), running, with a heart rate.
        rig.advance(1_000)
        app.controller.state.value = TreadmillState(
            status = BeltStatus.Running, speedKmh = 6.0, targetSpeedKmh = 6.0, inclinePercent = 1.0, heartRateBpm = 104,
            elapsedSeconds = 200, updatedAtMillis = rig.scheduler.currentTime,
        )
        rig.advance(125_000)
        app.controller.state.value = app.controller.state.value.copy(heartRateBpm = 118, updatedAtMillis = rig.scheduler.currentTime)
        rig.advance(300)
    }

    @Test fun aHeartRateBlockShowsItsZoneInBpmTheHeartRateNowAndTheControlState() {
        startHeartRateProgram()
        show()
        rule.onNodeWithTag("hr_block_panel").assertIsDisplayed()
        // The generic user's maximum is 180: zone 2 is 60-70 %.
        rule.onNodeWithTag("hr_zone").assertTextContains("108–126 bpm", substring = true)
        rule.onNodeWithTag("hr_now", useUnmergedTree = true).assertTextEquals("118")
        rule.onNodeWithTag("hr_control_state").assertIsDisplayed()
        assertFalse(exists("hr_resume_button"))
        val control = app.programRunner.progress.value.heartRateControl
        assertNotNull(control)
        assertTrue(control!!.state != HeartRateControlState.Unavailable)
    }

    @Test fun aBlockThatIsNotGuidedShowsNoHeartRatePanel() {
        idleBelt()
        val plan = ProgramResolver.resolve(TrainingFixtures.heartRateProgram())
        rig.startProgram(plan, userId)
        rig.advance(1_000)
        show()
        // Still in the warm-up.
        assertFalse(exists("hr_block_panel"))
    }

    @Test fun afterAManualChangeTheControlIsOffAndResumeSwitchesItOnAgain() {
        startHeartRateProgram()
        show()
        rig.adjustSpeed(7.0)
        rig.advance(300)
        rule.waitForIdle()
        rule.onNodeWithTag("hr_control_state").assertTextContains("Off")
        rule.onNodeWithTag("hr_resume_button").assertIsDisplayed().performClick()
        rig.advance(300)
        rule.waitForIdle()
        assertTrue(app.programRunner.progress.value.heartRateControl!!.state != HeartRateControlState.OffByManualChange)
        assertFalse(exists("hr_resume_button"))
    }
}
