package com.treadz.console.screenshots

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.github.takahirom.roborazzi.captureRoboImage
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
import org.robolectric.annotation.GraphicsMode

/**
 * The Performance screen (cards, charts, empty states) and the analysis section of the workout summary (phase F2), on
 * synthetic data. Subclasses set the language and screen size with `@Config(qualifiers = ...)`. Images are only written with
 * `./gradlew :app:recordRoborazziDebug`.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
abstract class PerformanceScreenshotScenarios(private val prefix: String) {

    @get:Rule val rule = createComposeRule()

    private val app = FakeApp()
    private val data get() = app.userData
    private val users by lazy { runBlocking { DemoData.populate(data, app.date) } }
    private val ana get() = users.first
    private val beto get() = users.second

    private fun recalculate() = runBlocking { data.workoutAnalysisRecalculator.recalculate() }

    /** Ten weeks of training, four walking tests and resting measurements for Ana, analysed. */
    private fun richAna(): List<Long> {
        users
        PerformanceFixtures.addTests(data, ana.id, app.date)
        val ids = PerformanceFixtures.addTraining(data, ana.id, app.date)
        PerformanceFixtures.addResting(data, ana.id, app.date)
        recalculate()
        return ids
    }

    private fun capture(name: String, vararg screens: Screen, open: List<String> = emptyList(), scrollTo: String? = null, light: Boolean = false, scrollIfPossible: Boolean = false) {
        users
        if (light) app.viewModel.setDarkTheme(false)
        val navigation = app.newNavigation()
        rule.setContent { TreadZRoot(dependencies = app, viewModel = app.viewModel, permissions = PermissionsUi(), actions = MainActions(), navigation = navigation) }
        rule.waitForIdle()
        screens.forEach(navigation::push)
        rule.waitForIdle()
        open.forEach { tag ->
            rule.onNodeWithTag(tag).performScrollTo().performClick()
            rule.waitForIdle()
        }
        if (scrollTo != null) {
            // The home screen does not scroll on a tablet: everything is in view already.
            if (scrollIfPossible) runCatching { rule.onNodeWithTag(scrollTo).performScrollTo() } else rule.onNodeWithTag(scrollTo).performScrollTo()
            rule.waitForIdle()
        }
        rule.onRoot().captureRoboImage("build/outputs/roborazzi/${prefix}_$name.png")
    }

    private fun card(name: String) = capture("p_$name", Screen.Performance, open = listOf("perf_toggle_$name"), scrollTo = "perf_card_$name")

    // ---- the Performance screen

    @Test fun p01_overview_with_data() {
        richAna()
        capture("p01_overview", Screen.Performance)
    }

    @Test fun p02_few_analysed_sessions() {
        users
        recalculate()
        capture("p02_few_sessions", Screen.Performance)
    }

    @Test fun p03_no_data_at_all() {
        users
        runBlocking { data.profiles.setActiveUser(beto.id) }
        capture("p03_no_data", Screen.Performance)
    }

    @Test fun p04_vo2max_chart() {
        richAna()
        card("Vo2max")
    }

    @Test fun p05_thresholds_and_zones() {
        richAna()
        card("Thresholds")
    }

    @Test fun p06_fitness_index_and_decoupling() {
        richAna()
        card("FitnessIndex")
    }

    @Test fun p07_training_load() {
        richAna()
        card("Load")
    }

    @Test fun p08_recovery_by_context() {
        richAna()
        card("Recovery")
    }

    @Test fun p09_resting_and_rmssd() {
        richAna()
        card("Resting")
    }

    @Test fun p10_weight_and_absolute_vo2() {
        richAna()
        card("WeightVo2")
    }

    @Test fun p11_overview_with_data_light() {
        richAna()
        capture("p11_overview_light", Screen.Performance, light = true)
    }

    @Test fun p12_load_chart_light() {
        richAna()
        capture("p12_load_light", Screen.Performance, open = listOf("perf_toggle_Load"), scrollTo = "perf_card_Load", light = true)
    }

    @Test fun p13_empty_cards_do_not_open() {
        users
        runBlocking { data.profiles.setActiveUser(beto.id) }
        capture("p13_empty_open", Screen.Performance, open = listOf("perf_toggle_Thresholds"), scrollTo = "perf_card_Thresholds")
    }

    @Test fun h01_home_shows_the_performance_shortcut() {
        richAna()
        capture("h01_home", Screen.Main, scrollTo = "home_performance", scrollIfPossible = true)
    }

    // ---- the analysis of a workout

    @Test fun w01_full_analysis() {
        val ids = richAna()
        // A zone 2 session of 45 minutes with a strap, ended with the belt stopped.
        capture("w01_full", Screen.History, Screen.WorkoutDetail(ids[18]), scrollTo = "analysis_panel")
    }

    @Test fun w02_full_analysis_zones_and_signal() {
        val ids = richAna()
        capture("w02_full_signal", Screen.History, Screen.WorkoutDetail(ids[19]), scrollTo = "analysis_signal")
    }

    @Test fun w03_console_only_workout() {
        richAna()
        val id = PerformanceFixtures.addSession(data, ana.id, app.date, 3, PerformanceFixtures.Session(minutes = 40, heart = PerformanceFixtures.Heart.Console, hrStart = 128, hrEnd = 136, seed = 3))
        recalculate()
        capture("w03_console_only", Screen.History, Screen.WorkoutDetail(id), scrollTo = "analysis_signal")
    }

    @Test fun w04_workout_without_heart_rate() {
        val ids = richAna()
        capture("w04_no_heart_rate", Screen.History, Screen.WorkoutDetail(ids[17]), scrollTo = "analysis_signal")
    }

    @Test fun w05_full_analysis_light() {
        val ids = richAna()
        capture("w05_full_light", Screen.History, Screen.WorkoutDetail(ids[18]), scrollTo = "analysis_panel", light = true)
    }
}
