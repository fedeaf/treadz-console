package com.treadz.console

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextReplacement
import com.treadz.console.fakes.GeneratedFixtures
import com.treadz.console.fakes.TrainingRig
import com.treadz.console.nav.NavigationViewModel
import com.treadz.console.nav.Screen
import com.treadz.console.programs.fitness.FitnessTestPhase
import com.treadz.console.programs.fitness.FitnessTestType
import com.treadz.console.programs.personalised.CouchToFiveK
import com.treadz.console.programs.personalised.ExerciseMode
import com.treadz.console.training.GeneratorForm
import com.treadz.console.treadmill.BeltStatus
import com.treadz.console.treadmill.TreadmillState
import com.treadz.console.ui.MainActions
import com.treadz.console.ui.PermissionsUi
import com.treadz.console.ui.TreadZRoot
import com.treadz.console.userdata.ProgramSaveResult
import com.treadz.console.userdata.memory.DemoData
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The personalised programs, Couch to 5K and the walking heart-rate test through the real UI, with the runners in virtual time. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "en-w1280dp-h800dp-land-hdpi")
class PersonalisedUiTest {

    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val rig = TrainingRig()
    private val app get() = rig.app
    private val data get() = app.userData
    private val ana by lazy { runBlocking { DemoData.populate(data, app.date) }.first }
    private val now get() = app.nowMillis()
    private lateinit var navigation: NavigationViewModel

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

    private fun scrollList(listTag: String, tag: String) {
        rule.onNodeWithTag(listTag).performScrollToNode(hasTestTag(tag))
    }

    private fun exists(tag: String) = rule.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty()

    private fun saveSession(generatorId: String, form: GeneratorForm? = null): String {
        val generated = GeneratedFixtures.generate(data, ana, generatorId, form, app.date, now)
        return (runBlocking { data.programs.save(ana.id, generated.program) } as ProgramSaveResult.Saved).program.id
    }

    // ---- The programs list ----

    @Test fun theListShowsTheGeneratorsTheLatestThreeSessionsFoldedAndTheCouchCard() {
        idleBelt()
        val ids = (1..4).map { saveSession("zone2", GeneratorForm(mode = ExerciseMode.Run, durationMinutes = 20 + it)) }
        GeneratedFixtures.addSession(data, ana.id, GeneratedFixtures.record(session = 7, startedAtMillis = now - 4 * GeneratedFixtures.DAY_MS))
        show(Screen.Programs)

        scrollList("programs_list", "generator_zone2")
        rule.onNodeWithTag("generator_zone2").assertIsDisplayed()
        rule.onNodeWithTag("generator_hr_tag_zone2").assertTextContains("Heart rate", substring = true)
        scrollList("programs_list", "toggle_sessions_zone2")
        rule.onNodeWithTag("toggle_sessions_zone2").assertTextContains("Show all (1 more)", substring = true)
        assertEquals(3, ids.count { exists("saved_$it") })
        click("toggle_sessions_zone2")
        assertEquals(4, ids.count { exists("saved_$it") })
        rule.onNodeWithTag("toggle_sessions_zone2").assertTextContains("Show fewer", substring = true)

        scrollList("programs_list", "couch_next")
        rule.onNodeWithTag("couch_next").assertTextContains("Session 8 of 27 (week 3, day 2)", substring = true)
        rule.onNodeWithTag("couch_reason").assertTextContains("you move on", substring = true)
        rule.onNodeWithTag("couch_last").assertIsDisplayed()
        rule.onNodeWithTag("couch_start_button").assertTextContains("Start session 8", substring = true)
    }

    @Test fun repeatingASavedSessionOpensTheStartScreenWithItsGenerationDateAndNoAdjustments() {
        idleBelt()
        val id = saveSession("tempo")
        show(Screen.Programs)
        scrollList("programs_list", "repeat_$id")
        click("repeat_$id")

        rule.onNodeWithTag("generated_on").assertIsDisplayed()
        rule.onNodeWithTag("generated_inputs").assertTextContains("Running", substring = true)
        rule.onNodeWithTag("regenerate_button").assertIsDisplayed()
        assertFalse("a generated program has no adjustment", exists("adjustment_plus"))
        rule.onNodeWithTag("start_program_button").assertIsEnabled()
        assertTrue(exists("plan_block_list"))

        // "Regenerate" opens the generator with the same inputs, to be generated again with today's data.
        click("regenerate_button")
        rule.onNodeWithTag("generator_screen").assertIsDisplayed()
        rule.onNodeWithTag("generator_regenerating").assertIsDisplayed()
    }

    @Test fun aSavedSessionCanBeDeletedAfterConfirmingAndDuplicatedAsFree() {
        idleBelt()
        val id = saveSession("norwegian-4x4")
        show(Screen.Programs)
        scrollList("programs_list", "delete_program_$id")
        click("delete_program_$id")
        rule.onNodeWithTag("confirm_dialog").assertIsDisplayed()
        click("cancel_button")
        assertTrue(runBlocking { data.programs.get(id) } != null)

        click("duplicate_program_$id")
        // The copy opens in the editor: it is a free program now.
        rule.onNodeWithTag("program_name_field").assertIsDisplayed()
        val copy = runBlocking { data.programs.programs(ana.id).first() }.single { it.program.name.endsWith("(copy)") }
        assertEquals(com.treadz.console.programs.model.ProgramKind.Free, copy.program.kind)
    }

    // ---- The generator screen ----

    @Test fun theGeneratorScreenShowsThePreviewTheNoticesAndTheirLinks() {
        idleBelt()
        ana
        runBlocking { data.profiles.setActiveUser(data.profiles.profiles.first().first { it.name == "Beto" }.id) }
        show(Screen.Programs, Screen.Generator("zone2"))

        rule.onNodeWithTag("generator_preview").assertIsDisplayed()
        rule.onNodeWithTag("generator_chart").assertIsDisplayed()
        rule.onNodeWithTag("plan_totals").assertIsDisplayed()
        // Beto has no fitness tests: the values are estimated and the notice says what to do.
        rule.onNodeWithTag("notice_EstimatedValues").assertIsDisplayed()
        rule.onNodeWithTag("start_generated_button").assertIsEnabled()
        click("generator_duration_plus")
        rule.onNodeWithTag("generator_duration_value").assertTextContains("50 min", substring = true)
        click("generator_mode_Walk")
        rule.onNodeWithTag("generator_chart").assertIsDisplayed()
        click("notice_action_tests")
        rule.onNodeWithTag("fitness_summary").assertIsDisplayed()
    }

    @Test fun theGeneratorScreenSaysWhyItCannotStart() {
        app.controller.state.value = TreadmillState(status = BeltStatus.Starting)
        show(Screen.Programs, Screen.Generator("tempo"))
        rule.onNodeWithTag("start_generated_button").assertIsNotEnabled()
        rule.onNodeWithTag("start_message").assertIsDisplayed()
    }

    @Test fun startingACouchSessionNamesItAndTheResultSaysWhatComesNext() {
        idleBelt()
        val workout = data.addWorkout(
            ana.id, com.treadz.console.userdata.WorkoutStats(durationSeconds = 600, distanceMeters = 800),
            listOf(
                com.treadz.console.userdata.WorkoutSample(
                    seq = 0, timestampMillis = 1_000, elapsedSeconds = 0, speedKmh = 6.0, targetSpeedKmh = 6.0, inclinePercent = 1.0,
                    distanceMeters = 0, calories = 0, heartRateBpm = 130, beltStatus = BeltStatus.Running,
                ),
            ),
            1_000,
        )
        app.links.workoutId = workout
        show(Screen.Programs)
        scrollList("programs_list", "couch_start_button")
        click("couch_start_button")
        rule.onNodeWithTag("couch_session_header").assertIsDisplayed()
        rule.onNodeWithTag("start_generated_button").assertTextContains("Start session 1", substring = true)

        click("start_generated_button")
        rig.advance(200)
        rule.waitForIdle()
        assertEquals(CouchToFiveK.ID, app.links.linked.single().plan!!.source.origin!!.generatorId)
        rule.onNodeWithTag("run_title").assertTextContains("Couch to 5K · session 1 of 27", substring = true)
        assertTrue("Couch to 5K sessions are not saved as programs", runBlocking { data.programs.programs(ana.id).first() }.none { it.program.kind == com.treadz.console.programs.model.ProgramKind.Personalised })

        // Stopped before its first run: that session left no run to judge, so the plan still starts at session 1.
        click("stop_button")
        rig.advance(300)
        rule.waitForIdle()
        rule.onNodeWithTag("couch_next_panel").assertIsDisplayed()
        rule.onNodeWithTag("couch_next_session").assertTextContains("Next: session 1 of 27", substring = true)
        rule.onNodeWithTag("couch_next_reason").assertTextContains("First session", substring = true)
    }

    @Test fun theStartWeekIsChosenWithTheSuggestedWeekMarked() {
        idleBelt()
        show(Screen.Programs, Screen.Generator(CouchToFiveK.ID))
        click("generator_plan_choose")
        rule.onNodeWithTag("generator_week_1").assertIsDisplayed()
        rule.onNodeWithTag("generator_week_9").assertIsDisplayed()
        click("generator_week_4")
        rule.onNodeWithTag("couch_session_header").assertTextContains("Session 10 of 27", substring = true)
        click("generator_plan_continue")
        rule.onNodeWithTag("couch_session_header").assertTextContains("Session 1 of 27", substring = true)
    }

    // ---- History ----

    @Test fun theHistoryTagsAWorkoutByItsGeneratorAndTheDetailShowsTheRecord() {
        val couch = GeneratedFixtures.addSession(
            data, ana.id, GeneratedFixtures.record(session = 7, startedAtMillis = now - GeneratedFixtures.DAY_MS, settledValue = 8.4, endHeartRate = 142.0),
        )
        val zone2 = GeneratedFixtures.addSession(
            data, ana.id, GeneratedFixtures.record(generatorId = "zone2", startedAtMillis = now - 2 * GeneratedFixtures.DAY_MS, inputs = mapOf("mode" to "run")),
        )
        show(Screen.History)
        rule.onNodeWithTag("generator_tag_$couch").assertTextContains("Couch to 5K · session 7", substring = true)
        rule.onNodeWithTag("generator_tag_$zone2").assertTextContains("Zone 2 easy run", substring = true)

        click("workout_$couch")
        rule.onNodeWithTag("detail_generator_tag").assertTextContains("Couch to 5K · session 7", substring = true)
        rule.onNodeWithTag("session_record").assertIsDisplayed()
        rule.onNodeWithTag("record_outcome").assertTextContains("Completed", substring = true)
        rule.onNodeWithTag("record_reductions").assertTextContains("0", substring = true)
        rule.onNodeWithTag("record_block_1").assertTextContains("ends at 142 bpm", substring = true)
        rule.onNodeWithTag("record_block_1").assertTextContains("settled at 8.4 km/h", substring = true)
    }

    // ---- The walking heart-rate test ----

    @Test fun theWalkTestIsTheThirdTestAndItsStartScreenExplainsIt() {
        idleBelt()
        show(Screen.Programs, Screen.FitnessTests)
        rule.onNodeWithTag("test_card_${FitnessTestType.MaxHeartRateWalk.name}").assertIsDisplayed()
        click("choose_${FitnessTestType.MaxHeartRateWalk.name}")

        rule.onNodeWithTag("test_walk_measures").assertTextContains("estimates your VO2max", substring = true)
        rule.onNodeWithTag("test_walk_duration").assertIsDisplayed()
        rule.onNodeWithTag("test_speed").assertTextContains("5.5", substring = true)
        rule.onNodeWithTag("test_incline").assertTextContains("5", substring = true)
        rule.onNodeWithTag("test_walk_warm_up").assertTextContains("4.1 km/h", substring = true)
        rule.onNodeWithTag("test_heart_rate_now").assertIsDisplayed()
        rule.onNodeWithTag("start_test_button").assertIsEnabled()

        rule.onNodeWithTag("test_speed").performTextReplacement("6")
        rule.waitForIdle()
        rule.onNodeWithTag("test_walk_warm_up").assertTextContains("4.5 km/h", substring = true)
        rule.onNodeWithTag("test_incline").performTextReplacement("20")
        rule.waitForIdle()
        rule.onNodeWithTag("start_test_button").assertIsNotEnabled()
    }

    @Test fun theWarmUpOffersTheRampStartControlsAndTheCoordinatorAppliesThem() {
        idleBelt()
        show(Screen.Programs, Screen.FitnessTests, Screen.FitnessStart(FitnessTestType.MaxHeartRateWalk))
        click("start_test_button")
        rig.advance(300)
        rule.waitForIdle()

        assertEquals(FitnessTestPhase.WarmUp, app.fitnessRunner.progress.value.phase)
        rule.onNodeWithTag("ramp_start_panel").assertIsDisplayed()
        rule.onNodeWithTag("ramp_speed_value").assertTextContains("5.5", substring = true)
        click("ramp_speed_plus")
        rig.advance(300)
        rule.waitForIdle()
        rule.onNodeWithTag("ramp_speed_value").assertTextContains("5.6", substring = true)
        click("ramp_incline_minus")
        rig.advance(300)
        rule.waitForIdle()
        rule.onNodeWithTag("ramp_incline_value").assertTextContains("4", substring = true)
        assertEquals(5.6, app.fitnessRunner.progress.value.nextStageSpeedKmh!!, 0.001)
        assertEquals(4.0, app.fitnessRunner.progress.value.nextStageInclinePercent!!, 0.001)
        // The exhausted button is not offered during the warm-up.
        assertFalse(exists("exhausted_button"))
    }
}
