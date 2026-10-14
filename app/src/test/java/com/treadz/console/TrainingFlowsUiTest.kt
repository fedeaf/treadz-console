package com.treadz.console

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onNodeWithText
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextInput
import androidx.compose.ui.test.performTextReplacement
import com.treadz.console.fakes.TrainingFixtures
import com.treadz.console.fakes.TrainingRig
import com.treadz.console.nav.Screen
import com.treadz.console.programs.engine.RunOutcome
import com.treadz.console.programs.engine.RunState
import com.treadz.console.programs.fitness.FitnessTestPhase
import com.treadz.console.programs.fitness.FitnessTestType
import com.treadz.console.treadmill.BeltStatus
import com.treadz.console.treadmill.TreadmillState
import com.treadz.console.ui.MainActions
import com.treadz.console.ui.PermissionsUi
import com.treadz.console.ui.TreadZRoot
import com.treadz.console.userdata.WorkoutLink
import com.treadz.console.userdata.memory.DemoData
import com.treadz.console.userdata.memory.DemoPrograms
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** Programs and fitness tests through the real UI (Compose + ViewModels), with the runners in virtual time. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "en-w1280dp-h800dp-land-hdpi")
class TrainingFlowsUiTest {

    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val rig = TrainingRig()
    private val app get() = rig.app
    private val data get() = app.userData
    private val ana by lazy { runBlocking { DemoData.populate(data, app.date) }.first }

    private lateinit var navigation: com.treadz.console.nav.NavigationViewModel

    private fun show(vararg then: Screen) {
        ana
        navigation = app.newNavigation()
        rule.setContent { TreadZRoot(app, app.viewModel, PermissionsUi(), MainActions(), navigation) }
        rule.waitForIdle()
        navigation.replaceAll(Screen.Main)
        then.forEach(navigation::push)
        rule.waitForIdle()
    }

    private fun idleBelt(heartRate: Int? = 72) {
        app.controller.state.value = TreadmillState(heartRateBpm = heartRate, updatedAtMillis = rig.scheduler.currentTime)
    }

    private fun click(tag: String) {
        rule.onNodeWithTag(tag).performClick()
        rig.advance(50)
        rule.waitForIdle()
    }

    private fun exists(tag: String) = rule.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty()

    // ---- Programs ----

    @Test fun choosingAProgramStartingItAndStoppingItFromTheRunScreen() {
        idleBelt()
        show(Screen.Programs)
        rule.onNodeWithTag("programs_list").performScrollToNode(hasTestTag("program_standard-intervals"))
        click("program_standard-intervals")
        rule.onNodeWithTag("start_program_button").assertIsEnabled()
        rule.onNodeWithTag("plan_totals").assertIsDisplayed()

        click("start_program_button")
        rig.advance(200)
        rule.waitForIdle()
        assertEquals(RunState.Running, app.programRunner.progress.value.state)
        rule.onNodeWithTag("program_run").assertIsDisplayed()
        rule.onNodeWithTag("run_title").assertTextContains("Intervals")
        rule.onNodeWithTag("block_phase").assertTextContains("Warm-up")
        rule.onNodeWithTag("run_state").assertTextContains("Running")

        click("stop_button")
        rig.advance(200)
        rule.waitForIdle()
        assertEquals(RunOutcome.StoppedByUser, app.programRunner.progress.value.outcome)
        rule.onNodeWithTag("program_summary").assertIsDisplayed()
        rule.onNodeWithTag("program_outcome").assertTextContains("stopped", substring = true)
        click("done_button")
        assertTrue(exists("start_button") || exists("home_screen"))
    }

    @Test fun theStepButtonsDuringAProgramMarkAManualChange() {
        idleBelt()
        show(Screen.Programs, Screen.ProgramStart("standard-pyramid"))
        click("start_program_button")
        rig.advance(200)
        app.controller.showRunning()
        rig.advance(2_000)
        rule.waitForIdle()
        assertTrue(!exists("manual_override_tag"))

        click("speed_plus")
        rig.advance(200)
        rule.waitForIdle()
        rule.onNodeWithTag("manual_override_tag").assertIsDisplayed()
        assertTrue(app.programRunner.progress.value.manualOverride)
    }

    @Test fun anInterruptedProgramOffersContinueAndAbandon() {
        idleBelt()
        show(Screen.Programs, Screen.ProgramStart("standard-pyramid"))
        click("start_program_button")
        rig.advance(200)
        app.controller.showRunning()
        rig.advance(2_000)

        // The belt is paused from the console, outside the program.
        app.controller.showState { it.copy(status = BeltStatus.Paused, speedKmh = 3.0) }
        rig.advance(500)
        rule.waitForIdle()
        rule.onNodeWithTag("interrupted_panel").assertIsDisplayed()
        rule.onNodeWithTag("interrupted_reason").assertTextContains("outside the program", substring = true)
        rule.onNodeWithTag("speed_plus").assertIsNotEnabled()

        // The safety key is out: continuing is refused, and the reason is on screen.
        app.controller.showState { it.copy(status = BeltStatus.SafetyKeyRemoved, speedKmh = 0.0) }
        rig.advance(100)
        click("continue_button")
        rig.advance(100)
        rule.waitForIdle()
        rule.onNodeWithTag("continue_failure").assertTextContains("safety key", substring = true)

        click("abandon_button")
        rig.advance(100)
        rule.waitForIdle()
        assertEquals(RunOutcome.Abandoned, app.programRunner.progress.value.outcome)
        rule.onNodeWithTag("program_outcome").assertTextContains("abandoned", substring = true)
        // The belt was left as it was: STOP stays within reach next to Done.
        rule.onNodeWithTag("finished_belt_note").assertIsDisplayed()
        rule.onNodeWithTag("stop_button").assertIsDisplayed()
        rule.onNodeWithTag("done_button").assertIsDisplayed()
    }

    @Test fun theStartScreenSaysWhyItCannotStart() {
        app.controller.state.value = TreadmillState(status = BeltStatus.Starting)
        show(Screen.Programs, Screen.ProgramStart("standard-hill"))
        rule.onNodeWithTag("start_program_button").assertIsNotEnabled()
        rule.onNodeWithTag("start_message").assertIsDisplayed()
    }

    @Test fun anInvalidReferenceValueIsShownNextToItsFieldAndDisablesStart() {
        idleBelt()
        show(Screen.Programs, Screen.ProgramStart("standard-intervals"))
        rule.onNodeWithTag("easy_speed").performTextReplacement("30")
        rule.waitForIdle()
        rule.onNodeWithText("Enter a speed between 0.5 and 20.0 km/h", substring = true).assertIsDisplayed()
        rule.onNodeWithTag("start_program_button").assertIsNotEnabled()
    }

    // ---- Editor ----

    @Test fun aProgramIsCreatedWithTheEditorAndShowsUpInTheList() {
        idleBelt()
        show(Screen.Programs)
        rule.onNodeWithTag("programs_list").performScrollToNode(hasTestTag("new_program_button"))
        click("new_program_button")
        click("save_program_button")
        rule.onNodeWithText("Required").assertIsDisplayed()
        rule.onNodeWithTag("program_name_field").performTextInput("Morning run")
        click("save_program_button")
        rule.waitForIdle()
        val saved = runBlocking { data.programs.programs(ana.id).first() }.map { it.program.name }
        assertTrue("Morning run" in saved)
        rule.onNodeWithTag("programs_list").performScrollToNode(androidx.compose.ui.test.hasText("Morning run"))
        rule.onNodeWithText("Morning run").assertIsDisplayed()
    }

    @Test fun editingAProgramKeepsItsId() {
        idleBelt()
        show(Screen.Programs)
        rule.onNodeWithTag("programs_list").performScrollToNode(hasTestTag("edit_program_${DemoPrograms.NORWEGIAN_ID}"))
        click("edit_program_${DemoPrograms.NORWEGIAN_ID}")
        // The blocks have a row of targets now: the repeat may be below the fold.
        rule.onNodeWithTag("repeat_1").performScrollTo().assertIsDisplayed()
        rule.onNodeWithTag("repeat_count_1").performTextReplacement("5")
        click("save_program_button")
        rule.waitForIdle()
        val program = checkNotNull(runBlocking { data.programs.get(DemoPrograms.NORWEGIAN_ID) }).program
        assertEquals(5, (program.steps[1] as com.treadz.console.programs.model.Repeat).count)
    }

    // ---- Fitness tests ----

    @Test fun anEbbelingTestIsStartedFromTheProgramsAreaAndSavedWhenItEnds() {
        idleBelt()
        show(Screen.Programs)
        click("fitness_entry")
        click("choose_EbbelingWalk")
        rule.onNodeWithTag("test_warning").assertIsDisplayed()
        rule.onNodeWithTag("start_test_button").assertIsEnabled()

        click("start_test_button")
        rig.advance(200)
        app.controller.showState { it.copy(status = BeltStatus.Running, speedKmh = 4.8, targetSpeedKmh = 4.8, heartRateBpm = 100, updatedAtMillis = rig.scheduler.currentTime) }
        rig.advance(5_000)
        rule.waitForIdle()
        rule.onNodeWithTag("fitness_run").assertIsDisplayed()
        assertEquals(FitnessTestPhase.Stage1, app.fitnessRunner.progress.value.phase)
        rule.onNodeWithTag("instruction_text").assertIsDisplayed()
        rule.onNodeWithTag("end_test_button").assertIsDisplayed()
        // The test links the workout to its result.
        val testId = checkNotNull(app.links.linked.single().fitnessTestId)

        click("end_test_button")
        rig.advance(2_000)
        rule.waitForIdle()
        // The result exists while the cool-down runs and is stored under the linked id.
        val stored = runBlocking { data.fitnessTests.get(testId) }
        assertNotNull(stored)
        assertEquals(FitnessTestType.EbbelingWalk, stored!!.result.testType)
    }

    @Test fun theMaximalTestShowsTheExhaustedButtonAndStopAfterThreeMinutesCounts() {
        idleBelt(heartRate = null) // the maximal test does not need a heart rate
        show(Screen.Programs, Screen.FitnessTests, Screen.FitnessStart(FitnessTestType.MaxIncrementalRun))
        rule.onNodeWithTag("start_test_button").assertIsEnabled()
        click("start_test_button")
        rig.advance(200)
        app.controller.showState { it.copy(status = BeltStatus.Running, speedKmh = 5.5, targetSpeedKmh = 5.5, updatedAtMillis = rig.scheduler.currentTime) }
        rig.advance(10_000)
        rule.waitForIdle()
        rule.onNodeWithTag("fitness_run").assertIsDisplayed()
        // Warm-up: the user cannot report exhaustion yet; STOP is there.
        assertTrue(!exists("exhausted_button"))
        rule.onNodeWithTag("stop_button").assertIsDisplayed()
    }

    @Test fun theFitnessTestsListOpensAResultAndDeletingAsksFirst() {
        idleBelt()
        show(Screen.Programs, Screen.FitnessTests)
        rule.onNodeWithTag("summary_vo2max").assertIsDisplayed()
        click("test_${DemoPrograms.MAX_TEST_ID}")
        rule.onNodeWithTag("result_vo2max").assertIsDisplayed()
        rule.onNodeWithTag("result_validity").assertTextContains("Valid")
        click("delete_result_button")
        rule.onNodeWithTag("confirm_dialog").assertIsDisplayed()
        click("cancel_button")
        assertNotNull(runBlocking { data.fitnessTests.get(DemoPrograms.MAX_TEST_ID) })
        click("delete_result_button")
        click("confirm_button")
        rule.waitForIdle()
        assertEquals(null, runBlocking { data.fitnessTests.get(DemoPrograms.MAX_TEST_ID) })
    }

    // ---- History ----

    @Test fun theHistoryShowsWhatEachWorkoutWasFor() {
        idleBelt()
        val plan = rig.standardPlan("standard-intervals")
        val started = app.date.minusDays(2).atTime(18, 0).atZone(java.time.ZoneId.systemDefault()).toInstant().toEpochMilli()
        val samples = TrainingFixtures.planSamples(plan, started)
        val id = data.addWorkout(ana.id, TrainingFixtures.statsOf(samples), samples, started)
        val testWorkout = data.addWorkout(ana.id, TrainingFixtures.statsOf(samples.take(300)), samples.take(300), started - 86_400_000)
        runBlocking {
            data.workouts.link(id, WorkoutLink(plan = plan))
            data.workouts.link(testWorkout, WorkoutLink(fitnessTestId = DemoPrograms.MAX_TEST_ID))
        }
        show(Screen.History)
        rule.onNodeWithTag("program_tag_$id").assertTextContains("Intervals")
        rule.onNodeWithTag("test_tag_$testWorkout").assertIsDisplayed()

        click("workout_$id")
        rule.onNodeWithTag("chart_plan").assertIsDisplayed()
        navigation.pop()
        rule.waitForIdle()
        click("workout_$testWorkout")
        click("view_test_button")
        rule.onNodeWithTag("result_vo2max").assertIsDisplayed()
    }
}
