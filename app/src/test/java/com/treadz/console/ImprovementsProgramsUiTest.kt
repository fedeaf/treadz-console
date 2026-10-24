package com.treadz.console

import androidx.activity.ComponentActivity
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.test.assert
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.getBoundsInRoot
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.isSelected
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onAllNodesWithText
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import com.treadz.console.fakes.GeneratedFixtures
import com.treadz.console.fakes.PerformanceFixtures
import com.treadz.console.fakes.TrainingRig
import com.treadz.console.nav.Screen
import com.treadz.console.programs.plan.ProgramResolver
import com.treadz.console.programs.plan.StartParameters
import com.treadz.console.training.ProgramsViewModel
import com.treadz.console.ui.MainActions
import com.treadz.console.ui.PermissionsUi
import com.treadz.console.ui.TreadZRoot
import com.treadz.console.userdata.UserProfile
import com.treadz.console.userdata.WorkoutEndReason
import com.treadz.console.userdata.WorkoutLink
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

/**
 * The programs screen and the screens of each program after the improvements: my programs first, no list of saved sessions, each
 * program with its own sessions inside it (the rows of the History screen), no tag for a normal end, and the zones of the profile
 * in the editor.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "en-w1280dp-h800dp-land-hdpi")
class ImprovementsProgramsUiTest {

    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val rig = TrainingRig()
    private val app get() = rig.app
    private val data get() = app.userData
    private val ana: UserProfile by lazy { runBlocking { DemoData.populate(data, app.date).first } }
    private val day = 86_400_000L

    private fun show(vararg screens: Screen) {
        ana
        val navigation = app.newNavigation()
        rule.setContent { TreadZRoot(app, app.viewModel, PermissionsUi(), MainActions(), navigation) }
        rule.waitForIdle()
        navigation.replaceAll(Screen.Main)
        screens.forEach(navigation::push)
        rule.waitForIdle()
    }

    private fun click(tag: String) {
        runCatching { rule.onNodeWithTag(tag).performScrollTo() }
        rule.onNodeWithTag(tag).performClick()
        rule.waitForIdle()
    }

    private fun exists(tag: String) = rule.onAllNodes(hasTestTag(tag), useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()

    private fun at(daysAgo: Long) = app.nowMillis() - daysAgo * day

    /** A finished workout of Ana that ran [plan]. */
    private fun workout(plan: com.treadz.console.programs.plan.ResolvedPlan?, daysAgo: Long, reason: WorkoutEndReason = WorkoutEndReason.Stopped, user: Long = ana.id): Long {
        val start = at(daysAgo)
        val samples = PerformanceFixtures.samples(PerformanceFixtures.Session(minutes = 20), start)
        val id = data.addWorkout(user, PerformanceFixtures.stats(samples), samples, start, reason)
        if (plan != null) runBlocking { data.workouts.link(id, WorkoutLink(plan = plan)) }
        return id
    }

    private fun generated(generatorId: String): com.treadz.console.programs.plan.ResolvedPlan {
        val generated = GeneratedFixtures.generate(data, ana, generatorId, null, app.date, app.nowMillis())
        return ProgramResolver.resolve(generated.program, StartParameters())
    }

    /** The vertical position of a node, for the order of the rows. */
    private fun top(tag: String) = rule.onNodeWithTag(tag).getBoundsInRoot().top

    // ---- Sessions inside each program ----

    @Test fun aStandardProgramShowsItsOwnSessionsNewestFirstAndOpensThem() {
        val plan = rig.standardPlan("standard-pyramid")
        val old = workout(plan, 9)
        val recent = workout(plan, 2)
        val other = workout(rig.standardPlan("standard-hill"), 3)
        show(Screen.Programs, Screen.ProgramStart("standard-pyramid"))

        rule.onNodeWithTag("program_sessions").performScrollTo().assertIsDisplayed()
        rule.onNode(hasText("Your sessions (2)")).assertIsDisplayed()
        rule.onNodeWithTag("workout_$recent").performScrollTo()
        rule.onNodeWithTag("workout_$old").performScrollTo()
        assertTrue("newest first", top("workout_$recent") < top("workout_$old"))
        assertFalse("another program's session", exists("workout_$other"))

        click("workout_$recent")
        assertTrue("the detail of the workout opens", exists("export_button"))
    }

    @Test fun anOwnProgramShowsItsSessionsAndSaysSoWhenThereAreNone() {
        val own = runBlocking { data.programs.programs(ana.id).first() }.first { it.program.origin == null }
        show(Screen.Programs, Screen.ProgramStart(own.id))
        rule.onNodeWithTag("program_sessions_empty").performScrollTo().assertIsDisplayed()
    }

    @Test fun anOwnProgramListsTheWorkoutsThatRanIt() {
        val own = runBlocking { data.programs.programs(ana.id).first() }.first { it.program.origin == null }
        val plan = ProgramResolver.resolve(own.program, ProgramsViewModel.previewParameters(own.program))
        val id = workout(plan, 1)
        show(Screen.Programs, Screen.ProgramStart(own.id))
        rule.onNodeWithTag("workout_$id").performScrollTo().assertIsDisplayed()
        assertFalse(exists("program_sessions_empty"))
    }

    @Test fun aGeneratorShowsTheSessionsItGeneratedNotTheOnesOfAnotherGeneratorOrUser() {
        val zone2 = generated("zone2")
        val older = workout(zone2, 6)
        val newer = workout(zone2, 1)
        val tempo = workout(generated("tempo"), 2)
        val beto = runBlocking { data.profiles.profiles.first().first { it.name == "Beto" } }
        val others = workout(zone2, 1, user = beto.id)
        show(Screen.Programs, Screen.Generator("zone2"))

        rule.onNodeWithTag("program_sessions").performScrollTo().assertIsDisplayed()
        rule.onNode(hasText("Your sessions (2)")).assertIsDisplayed()
        rule.onNodeWithTag("workout_$newer").performScrollTo()
        rule.onNodeWithTag("workout_$older").performScrollTo()
        assertTrue(top("workout_$newer") < top("workout_$older"))
        assertFalse(exists("workout_$tempo"))
        assertFalse(exists("workout_$others"))
    }

    @Test fun startingAGeneratedSessionDoesNotSaveAProgramAndTheWorkoutKeepsItsGenerator() {
        app.controller.state.value = com.treadz.console.treadmill.TreadmillState(heartRateBpm = 72, updatedAtMillis = rig.scheduler.currentTime)
        show(Screen.Programs, Screen.Generator("tempo"))
        click("start_generated_button")
        rig.advance(200)
        rule.waitForIdle()
        assertTrue(runBlocking { data.programs.programs(ana.id).first() }.none { it.program.origin != null })
        assertEquals("tempo", app.links.linked.single().plan!!.source.origin!!.generatorId)
    }

    // ---- The Programs screen ----

    @Test fun thePersonalisedSectionHasNoSavedSessionsAnyMore() {
        // A session saved by an older version.
        runBlocking { data.programs.save(ana.id, GeneratedFixtures.generate(data, ana, "zone2", null, app.date, app.nowMillis()).program) }
        show(Screen.Programs)
        rule.onNodeWithTag("programs_list").performScrollToNode(hasTestTag("generator_zone2"))
        rule.onNodeWithTag("generator_zone2").assertIsDisplayed()
        assertFalse(exists("toggle_sessions_zone2"))
        assertEquals(0, rule.onAllNodes(hasText("Saved sessions", substring = true)).fetchSemanticsNodes().size)
        assertEquals(0, rule.onAllNodes(hasText("Regenerate")).fetchSemanticsNodes().size)
    }

    // ---- The History rows ----

    @Test fun theNormalEndHasNoTagAndTheOtherEndsKeepTheirs() {
        val stopped = workout(null, 1, WorkoutEndReason.Stopped)
        val lost = workout(null, 2, WorkoutEndReason.ConnectionLost)
        val key = workout(null, 3, WorkoutEndReason.SafetyKeyRemoved)
        show(Screen.History)
        rule.onNodeWithTag("workout_$stopped").assertIsDisplayed()
        rule.onNodeWithTag("workout_$lost").assertIsDisplayed()
        rule.onNodeWithTag("workout_$key").assertIsDisplayed()
        assertEquals(0, rule.onAllNodesWithText("Stopped").fetchSemanticsNodes().size)
        assertEquals(1, rule.onAllNodesWithText("Connection lost").fetchSemanticsNodes().size)
        assertEquals(1, rule.onAllNodesWithText("Safety key removed").fetchSemanticsNodes().size)
    }

    @Test fun everyWorkoutOfTheUserIsInTheHistoryWhateverItRan() {
        val free = workout(null, 1)
        val standard = workout(rig.standardPlan("standard-hill"), 2)
        val personalised = workout(generated("zone2"), 3)
        show(Screen.History)
        listOf(free, standard, personalised).forEach { rule.onNodeWithTag("workout_$it").assertIsDisplayed() }
    }

    // ---- The editor ----

    @Test fun aNewHeartRateBlockAsksForZoneTwoOfTheProfileAndShowsTheZonesWithTheirBpm() {
        show(Screen.Programs, Screen.ProgramEditor(null, 1))
        click("block_1_target_hr")
        rule.onNodeWithTag("block_1_pzone_2").assert(isSelected())
        rule.onNodeWithTag("block_1_pzone_2").assertTextContains("Z2 · ", substring = true)
        rule.onNodeWithTag("block_1_zone_1").assert(androidx.compose.ui.test.isNotSelected())
        // The zone of the profile has its own bpm: no basis to choose.
        assertFalse(exists("block_1_basis_max"))

        click("block_1_pzone_4")
        rule.onNodeWithTag("block_1_pzone_4").assert(isSelected())
        rule.onNodeWithTag("block_1_pzone_2").assert(androidx.compose.ui.test.isNotSelected())

        // A standard zone is a percentage of the maximum, and brings the basis back.
        click("block_1_zone_2")
        rule.onNodeWithTag("block_1_zone_2").assert(isSelected())
        rule.onNodeWithTag("block_1_pzone_4").assert(androidx.compose.ui.test.isNotSelected())
        assertTrue(exists("block_1_basis_max"))
    }
}

/** The order of the Programs screen, with a window tall enough to compose every item. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "en-w600dp-h4000dp-port-hdpi")
class ImprovementsProgramsOrderUiTest {

    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val app = com.treadz.console.fakes.FakeApp()

    @Test fun myProgramsComeFirstThenThePersonalisedOnesAndThenTheStandardOnes() {
        val ana = runBlocking { DemoData.populate(app.userData, app.date).first }
        val own = runBlocking { app.userData.programs.programs(ana.id).first() }.first { it.program.origin == null }
        val navigation = app.newNavigation()
        rule.setContent { TreadZRoot(app, app.viewModel, PermissionsUi(), MainActions(), navigation) }
        rule.waitForIdle()
        navigation.replaceAll(Screen.Main)
        navigation.push(Screen.Programs)
        rule.waitForIdle()

        val tags = rule.onNodeWithTag("programs_list").fetchSemanticsNode().tags()
        fun index(tag: String) = tags.indexOf(tag).also { assertTrue("$tag in $tags", it >= 0) }
        // The access to the tests stays at the top.
        assertTrue(index("fitness_entry") < index("new_program_button"))
        assertTrue(index("new_program_button") < index("program_${own.id}"))
        assertTrue(index("program_${own.id}") < index("generator_zone2"))
        assertTrue(index("generator_zone2") < index("generator_couch-to-5k").takeIf { it >= 0 } ?: Int.MAX_VALUE)
        assertTrue(index("generator_intervals-30-30") < index("program_standard-hill"))
        assertTrue(index("generator_${com.treadz.console.programs.personalised.CouchToFiveK.ID}") < index("program_standard-hill"))
    }

    private fun SemanticsNode.tags(): List<String> {
        val own = if (SemanticsProperties.TestTag in config) config[SemanticsProperties.TestTag] else null
        return listOfNotNull(own) + children.flatMap { it.tags() }
    }
}
