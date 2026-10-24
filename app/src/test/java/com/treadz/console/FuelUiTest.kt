package com.treadz.console

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.assertCountEquals
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasText
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onAllNodesWithTag
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import com.treadz.console.controls.SessionControls
import com.treadz.console.controls.SessionKind
import com.treadz.console.fakes.FakeApp
import com.treadz.console.fakes.FuelFixtures
import com.treadz.console.fuel.FuelFigures
import com.treadz.console.fuel.FuelPresenter
import com.treadz.console.programs.engine.ProgramProgress
import com.treadz.console.programs.engine.RunOutcome
import com.treadz.console.programs.engine.RunState
import com.treadz.console.programs.plan.ProgramResolver
import com.treadz.console.fakes.TrainingFixtures
import com.treadz.console.training.FinishedWorkout
import com.treadz.console.training.RunUiState
import com.treadz.console.treadmill.BeltStatus
import com.treadz.console.treadmill.ConnectionState
import com.treadz.console.treadmill.TreadmillState
import com.treadz.console.ui.ControlActions
import com.treadz.console.ui.LiveData
import com.treadz.console.ui.LocalLayout
import com.treadz.console.ui.MainActions
import com.treadz.console.ui.ProgramRunScreen
import com.treadz.console.ui.RunActions
import com.treadz.console.ui.TreadZFrame
import com.treadz.console.ui.WorkoutDetailActions
import com.treadz.console.ui.WorkoutDetailScreen
import com.treadz.console.ui.liveSizes
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * What the screens draw of the fat/carbohydrate split, from hand-made states: the live metric (phone strip and tablet
 * card), nothing when there is no split, the end-of-workout lines and the detail card with its explanation.
 * A non-breaking space joins each number to its unit, so a line never breaks between them.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "en-w1280dp-h800dp-land-hdpi")
class FuelUiTest {

    @get:Rule val rule = createComposeRule()

    private val nbsp = " "
    private val figures = FuelFigures(120, 196, 38, 62)
    private val running = MainUiState(
        connection = ConnectionState.Connected("AA", "REEBOK"),
        treadmill = TreadmillState(status = BeltStatus.Running, speedKmh = 8.5, targetSpeedKmh = 8.5, inclinePercent = 3.0, heartRateBpm = 142, updatedAtMillis = 1_000),
        liveCalories = 316,
    )

    private fun host(content: @Composable () -> Unit) {
        rule.setContent {
            TreadZFrame(darkTheme = true, keepScreenOn = false, alert = null, onDismissAlert = {}) { Box(androidx.compose.ui.Modifier.fillMaxSize()) { content() } }
        }
        rule.waitForIdle()
    }

    @Composable
    private fun live(ui: MainUiState) {
        val wide = LocalLayout.current.wide
        LiveData(ui, MainActions(), wide, liveSizes(wide))
    }

    // ---- live

    @Test fun theTabletShowsTheSplitNextToTheCalories() {
        host { live(running.copy(liveFuel = figures)) }
        rule.onNodeWithTag("metric_value_Fat · Carbs", useUnmergedTree = true).assertTextEquals("38$nbsp% · 62$nbsp%")
        rule.onNode(hasText("120 · 196${nbsp}kcal")).assertIsDisplayed()
        rule.onNodeWithTag("metric_value_Calories", useUnmergedTree = true).assertTextEquals("316")
    }

    @Test fun theTabletShowsNothingWithoutASplit() {
        host { live(running) }
        rule.onAllNodesWithTag("metric_value_Fat · Carbs", useUnmergedTree = true).assertCountEquals(0)
        rule.onNodeWithTag("metric_value_Calories", useUnmergedTree = true).assertExists()
    }

    @Test fun theIdleGridAlsoShowsIt() {
        host { live(running.copy(treadmill = TreadmillState(status = BeltStatus.Idle, updatedAtMillis = 1_000), liveFuel = figures)) }
        rule.onNodeWithTag("metric_value_Fat · Carbs", useUnmergedTree = true).assertTextEquals("38$nbsp% · 62$nbsp%")
    }

    // ---- the program

    private val plan = ProgramResolver.resolve(TrainingFixtures.keepProgram())

    private fun progress(state: RunState = RunState.Running) = ProgramProgress(
        state = state, plan = plan, currentBlockIndex = 1, currentBlock = plan.blocks[1], nextBlock = plan.blocks[2],
        totalElapsedSeconds = 1_020, totalRemainingSeconds = 780, totalElapsedMeters = 2_150, targetSpeedKmh = 9.6, actualSpeedKmh = 9.6,
    )

    private fun programUi(fuel: FuelFigures?) = running.copy(session = SessionControls(SessionKind.Program), liveFuel = fuel)

    @Test fun theProgramTotalsShowItWhenThereIsOne() {
        host { ProgramRunScreen(programUi(figures), RunUiState(kind = SessionKind.Program, program = progress()), ControlActions(), RunActions()) }
        rule.onNodeWithTag("run_fuel", useUnmergedTree = true).assertTextEquals("38$nbsp% · 62$nbsp%")
        rule.onNodeWithTag("run_fuel_kcal", useUnmergedTree = true).assertTextEquals("120 · 196${nbsp}kcal")
    }

    @Test fun theProgramTotalsShowNothingWithoutIt() {
        host { ProgramRunScreen(programUi(null), RunUiState(kind = SessionKind.Program, program = progress()), ControlActions(), RunActions()) }
        rule.onAllNodesWithTag("run_fuel", useUnmergedTree = true).assertCountEquals(0)
        rule.onNodeWithTag("run_distance", useUnmergedTree = true).assertExists()
    }

    // ---- the end

    private val finishedFigures = FuelFigures(290, 352, 45, 55)
    private val endedUi = MainUiState(
        connection = ConnectionState.Connected("AA", "REEBOK"),
        treadmill = TreadmillState(status = BeltStatus.Idle, updatedAtMillis = 1_000),
        finishedWorkout = FinishedWorkout(7, 1_800, 4_300, null, 642),
    )

    private val done get() = progress(RunState.Finished).copy(outcome = RunOutcome.Completed)

    @Test fun theProgramSummaryShowsNothingUntilTheAnalysisArrives() {
        host { ProgramRunScreen(endedUi, RunUiState(kind = SessionKind.Program, program = done), ControlActions(), RunActions()) }
        rule.onAllNodesWithTag("fuel_summary").assertCountEquals(0)
        rule.onNodeWithTag("program_summary").assertIsDisplayed()
    }

    @Test fun theProgramSummaryShowsTheSplitOfTheFinishedWorkout() {
        host { ProgramRunScreen(endedUi.copy(finishedFuel = finishedFigures), RunUiState(kind = SessionKind.Program, program = done), ControlActions(), RunActions()) }
        rule.onNodeWithTag("fuel_summary").assertTextEquals("Fat${nbsp}290${nbsp}kcal${nbsp}(45${nbsp}%) · Carbs${nbsp}352${nbsp}kcal${nbsp}(55${nbsp}%)")
    }

    @Test fun theNoticeAfterAWorkoutShowsNothingUntilTheAnalysisArrives() {
        host { live(endedUi) }
        rule.onAllNodesWithTag("fuel_summary").assertCountEquals(0)
        rule.onNodeWithTag("finished_workout").assertIsDisplayed()
    }

    @Test fun theNoticeShowsTheSplitWhenItArrives() {
        host { live(endedUi.copy(finishedFuel = finishedFigures)) }
        rule.onNodeWithTag("fuel_summary").assertIsDisplayed().assertTextEquals("Fat${nbsp}290${nbsp}kcal${nbsp}(45${nbsp}%) · Carbs${nbsp}352${nbsp}kcal${nbsp}(55${nbsp}%)")
    }

    // ---- the detail

    private val app = FakeApp()

    private fun detail(analysis: com.treadz.console.analysis.workout.WorkoutAnalysis?) {
        val state = FuelFixtures.detailState(app, analysis)
        host { WorkoutDetailScreen(state, WorkoutDetailActions()) }
    }

    private val withSplit get() = FuelFixtures.analysis(FuelFixtures.fuel(minutes = FuelFixtures.minutes(app.nowMillis() - 2 * 3_600_000L, 60)))

    @Test fun theDetailCardShowsTheTotalsTheChartAndTheZonesWithTime() {
        detail(withSplit)
        rule.onNodeWithTag("fuel_card").performScrollTo().assertIsDisplayed()
        rule.onNodeWithTag("fuel_total_fat").assertTextEquals("Fat", "290${nbsp}kcal · 45${nbsp}%")
        rule.onNodeWithTag("fuel_total_carbs").assertTextEquals("Carbs", "352${nbsp}kcal · 55${nbsp}%")
        rule.onNodeWithTag("fuel_bar").assertExists()
        rule.onNodeWithTag("fuel_chart").assertExists()
        rule.onNodeWithTag("fuel_zone_2").assertTextEquals("Zone 2 · 62 %")
        rule.onAllNodesWithTag("fuel_zone_1").assertCountEquals(0)
        rule.onAllNodesWithTag("fuel_zone_5").assertCountEquals(0)
    }

    @Test fun theDetailCardIsAbsentWithoutASplit() {
        detail(FuelFixtures.analysis(fuel = null))
        rule.onAllNodesWithTag("fuel_card").assertCountEquals(0)
        rule.onNodeWithTag("analysis_panel").assertExists()
    }

    @Test fun theDetailCardIsAbsentBeforeTheAnalysis() {
        detail(null)
        rule.onAllNodesWithTag("fuel_card").assertCountEquals(0)
    }

    @Test fun theInfoButtonOpensTheExplanation() {
        detail(withSplit)
        rule.onAllNodesWithTag("fuel_help_dialog").assertCountEquals(0)
        rule.onNodeWithTag("fuel_info_button").performScrollTo().performClick()
        rule.onNodeWithTag("fuel_help_dialog").assertExists()
        rule.onNode(hasText("How is it calculated?")).assertExists()
        rule.onNodeWithTag("fuel_help_close").performClick()
        rule.onAllNodesWithTag("fuel_help_dialog").assertCountEquals(0)
    }
}
