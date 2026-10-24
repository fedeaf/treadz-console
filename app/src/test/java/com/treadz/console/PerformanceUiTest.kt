package com.treadz.console

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.treadz.console.fakes.FakeApp
import com.treadz.console.fakes.PerformanceFixtures
import com.treadz.console.nav.Screen
import com.treadz.console.ui.MainActions
import com.treadz.console.ui.PermissionsUi
import com.treadz.console.ui.TreadZRoot
import com.treadz.console.userdata.memory.DemoData
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/** The Performance screen and the analysis section of the workout summary on the real UI over the fakes. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "en-w1280dp-h800dp-land-hdpi")
class PerformanceUiTest {

    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val app = FakeApp()
    private val data get() = app.userData
    private val ana by lazy { runBlocking { DemoData.populate(data, app.date).first } }

    private fun show(vararg startAt: Screen) {
        app.viewModel
        val navigation = app.newNavigation()
        rule.setContent { TreadZRoot(app, app.viewModel, PermissionsUi(), MainActions(), navigation) }
        rule.waitForIdle()
        startAt.forEach(navigation::push)
        rule.waitForIdle()
    }

    private fun rich(): List<Long> {
        PerformanceFixtures.addTests(data, ana.id, app.date)
        val ids = PerformanceFixtures.addTraining(data, ana.id, app.date)
        PerformanceFixtures.addResting(data, ana.id, app.date)
        runBlocking { data.workoutAnalysisRecalculator.recalculate() }
        return ids
    }

    // Clickable cards merge their texts into one node: look at the individual ones.
    private fun exists(tag: String) = rule.onNodeWithTag(tag, useUnmergedTree = true).assertExists()

    private fun gone(tag: String) = rule.onNodeWithTag(tag, useUnmergedTree = true).assertDoesNotExist()

    // ---- access and cards

    @Test fun thePerformanceTileOpensTheScreenWithoutTheTreadmill() {
        show()
        rule.onNodeWithTag("home_performance").assertIsDisplayed().performClick()
        rule.waitForIdle()
        rule.onNodeWithTag("performance_screen").assertIsDisplayed()
    }

    @Test fun everyCardIsThereWithAPassiveLineWhenThereIsNoData() {
        show(Screen.Performance)
        listOf("Vo2max", "Thresholds", "FitnessIndex", "Load", "Recovery", "Resting", "WeightVo2").forEach { exists("perf_card_$it") }
        exists("perf_vo2max_empty")
        exists("perf_fitness_empty")
        exists("perf_load_empty")
        exists("perf_recovery_empty")
        exists("perf_weight_empty")
        // Nothing to draw: those cards do not open.
        gone("perf_toggle_Load")
        gone("perf_toggle_Vo2max")
        gone("perf_toggle_Recovery")
        // The zones always exist, so the thresholds card still opens and lists them.
        exists("perf_thresholds_empty")
        rule.onNodeWithTag("perf_toggle_Thresholds").performClick()
        rule.waitForIdle()
        exists("perf_details_Thresholds")
        exists("zone_3")
    }

    @Test fun aCardOpensItsChartsOnATapAndClosesOnTheNext() {
        ana
        rich()
        show(Screen.Performance)
        gone("perf_details_Vo2max")
        rule.onNodeWithTag("perf_toggle_Vo2max").performClick()
        rule.waitForIdle()
        exists("perf_vo2max_chart")
        rule.onNodeWithTag("perf_toggle_Vo2max").performClick()
        rule.waitForIdle()
        gone("perf_vo2max_chart")
    }

    @Test fun theCardsShowTheirFiguresAndTheirPassiveMarks() {
        ana
        rich()
        show(Screen.Performance)
        // The lower bound of 38.0 below the measured values does not count (a floor, not a measurement).
        rule.onNodeWithTag("perf_vo2max_value", useUnmergedTree = true).assertTextContains("42.6")
        exists("perf_vo2max_category")
        exists("perf_vo2max_change")
        exists("perf_vt1_value")
        exists("perf_thresholds_quality")
        // A trend, not the VO2max: said next to the figure.
        exists("perf_fitness_note")
        exists("perf_fitness_change")
        // Workouts without heart rate count as 0 and are flagged.
        exists("perf_load_missing")
        exists("perf_recovery_context")
        exists("perf_resting_value")
        exists("perf_rmssd_value")
        exists("perf_abs_value")
    }

    @Test fun theLoadCardHasItsThreeChartsAndTheRecoveryKeepsTheContextsApart() {
        ana
        rich()
        show(Screen.Performance)
        rule.onNodeWithTag("perf_toggle_Load").performScrollTo().performClick()
        rule.onNodeWithTag("perf_toggle_Recovery").performScrollTo().performClick()
        rule.waitForIdle()
        exists("perf_load_weekly_chart")
        exists("perf_load_banister_chart")
        exists("perf_load_zones_chart")
        exists("perf_recovery_chart_BeltMoving")
        exists("perf_recovery_chart_BeltStopped")
    }

    // ---- the analysis of a workout

    @Test fun aWorkoutShowsNothingUntilItsAnalysisIsComputed() {
        ana
        val id = PerformanceFixtures.addSession(data, ana.id, app.date, 3, PerformanceFixtures.Session(minutes = 30))
        show(Screen.History, Screen.WorkoutDetail(id))
        exists("export_button")
        gone("analysis_panel")
    }

    @Test fun aFullAnalysisShowsEveryMetricNextToTheTreadmillsOwnCalories() {
        ana
        val id = PerformanceFixtures.addSession(data, ana.id, app.date, 3, PerformanceFixtures.Session(minutes = 45))
        runBlocking { data.workoutAnalysisRecalculator.recalculate() }
        show(Screen.History, Screen.WorkoutDetail(id))
        exists("analysis_panel")
        listOf(
            "analysis_kcal_net", "analysis_kcal_gross", "analysis_kcal_console", "analysis_mets", "analysis_vo2", "analysis_zones", "analysis_zone_2",
            "analysis_trimp", "analysis_efficiency", "analysis_decoupling", "analysis_long_zone2", "analysis_recovery", "analysis_recovery_context",
            "analysis_alpha1_below", "analysis_alpha1_above", "analysis_alpha1_mean", "analysis_coverage", "analysis_sources", "analysis_artifacts",
        ).forEach { exists(it) }
        gone("analysis_note_NoHeartRate")
        gone("analysis_note_NoRrIntervals")
    }

    @Test fun aConsoleOnlyWorkoutSaysWhyThereIsNoAlpha1() {
        ana
        val id = PerformanceFixtures.addSession(data, ana.id, app.date, 3, PerformanceFixtures.Session(minutes = 30, heart = PerformanceFixtures.Heart.Console))
        runBlocking { data.workoutAnalysisRecalculator.recalculate() }
        show(Screen.History, Screen.WorkoutDetail(id))
        exists("analysis_trimp")
        exists("analysis_note_NoRrIntervals")
        gone("analysis_alpha1")
        gone("analysis_artifacts")
    }

    @Test fun aWorkoutWithoutHeartRateKeepsTheEnergyAndHidesTheRest() {
        ana
        val id = PerformanceFixtures.addSession(data, ana.id, app.date, 3, PerformanceFixtures.Session(minutes = 30, heart = PerformanceFixtures.Heart.None))
        runBlocking { data.workoutAnalysisRecalculator.recalculate() }
        show(Screen.History, Screen.WorkoutDetail(id))
        exists("analysis_kcal_gross")
        exists("analysis_note_NoHeartRate")
        gone("analysis_trimp")
        gone("analysis_zones")
        gone("analysis_recovery")
        gone("analysis_alpha1")
        gone("analysis_note_NoRrIntervals")
    }
}
