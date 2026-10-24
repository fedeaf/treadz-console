package com.treadz.console

import androidx.activity.ComponentActivity
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import com.treadz.console.analysis.thresholds.ThresholdQuality
import com.treadz.console.analysis.trends.RecommendationDirection
import com.treadz.console.analysis.trends.ThresholdRecommendation
import com.treadz.console.analysis.workout.ThresholdKind
import com.treadz.console.controls.SessionControls
import com.treadz.console.controls.SessionKind
import com.treadz.console.fakes.GeneratedFixtures
import com.treadz.console.fakes.TrainingFixtures
import com.treadz.console.fakes.TrainingRig
import com.treadz.console.nav.Screen
import com.treadz.console.performance.PerformanceTrendsCalculator
import com.treadz.console.performance.PerformanceUiState
import com.treadz.console.programs.fitness.EffortAssessment
import com.treadz.console.programs.fitness.FitnessProfile
import com.treadz.console.programs.fitness.FitnessTestResult
import com.treadz.console.programs.fitness.OutputsWithheldReason
import com.treadz.console.programs.fitness.StrapCheck
import com.treadz.console.programs.fitness.ThresholdEstimate
import com.treadz.console.programs.fitness.ThresholdProfile
import com.treadz.console.training.BrakeNotice
import com.treadz.console.training.FitnessTestsUiState
import com.treadz.console.training.RunUiState
import com.treadz.console.treadmill.BeltStatus
import com.treadz.console.treadmill.ConnectionState
import com.treadz.console.treadmill.TreadmillState
import com.treadz.console.ui.ControlActions
import com.treadz.console.ui.FitnessRunScreen
import com.treadz.console.ui.FitnessTestsActions
import com.treadz.console.ui.FitnessTestsScreen
import com.treadz.console.ui.MainActions
import com.treadz.console.ui.PerformanceActions
import com.treadz.console.ui.PerformanceScreen
import com.treadz.console.ui.PermissionsUi
import com.treadz.console.ui.ProgramRunScreen
import com.treadz.console.ui.RunActions
import com.treadz.console.ui.TreadZFrame
import com.treadz.console.ui.TreadZRoot
import com.treadz.console.userdata.UserProfile
import com.treadz.console.userdata.memory.DemoData
import com.treadz.console.userdata.memory.DemoPrograms
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode

/**
 * The texts and places of the improvements batch on the real screens: the α1 brake notice, the 85 % rule of the maximal tests,
 * the recommendation of a test and the strap check. Subclasses set the language and give the exact sentences.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
abstract class ImprovementsUiScenes {

    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()

    protected val rig = TrainingRig()
    protected val app get() = rig.app
    private val data get() = app.userData
    private val ana: UserProfile by lazy { runBlocking { DemoData.populate(data, app.date).first } }
    private val day = 86_400_000L

    protected abstract val brakeText: String
    protected abstract val notMaximalWalk: String
    protected abstract val notMaximalRun: String
    protected abstract val vt1Higher: String
    protected abstract val vt2Lower: String
    protected abstract val strapText: String

    private fun host(content: @Composable () -> Unit) {
        rule.setContent {
            TreadZFrame(darkTheme = true, keepScreenOn = false, alert = null, onDismissAlert = {}) { Box(Modifier.fillMaxSize()) { content() } }
        }
        rule.waitForIdle()
    }

    private fun show(vararg screens: Screen) {
        ana
        val navigation = app.newNavigation()
        rule.setContent { TreadZRoot(app, app.viewModel, PermissionsUi(), MainActions(), navigation) }
        rule.waitForIdle()
        navigation.replaceAll(Screen.Main)
        screens.forEach(navigation::push)
        rule.waitForIdle()
    }

    // ---- The α1 brake notice ----

    // The brake only acts in heart-rate blocks: its notice is the text of the heart-rate panel for a few seconds.
    private fun runningProgramUi(notice: BrakeNotice?): Pair<MainUiState, RunUiState> {
        val outcome = rig.startProgram(com.treadz.console.programs.plan.ProgramResolver.resolve(TrainingFixtures.heartRateProgram()), ana.id)
        check(outcome == com.treadz.console.training.StartOutcome.Started) { "$outcome" }
        rig.advance(3 * 60_000L)
        check(app.programRunner.progress.value.heartRateControl != null) { "not in the heart-rate block" }
        val ui = MainUiState(
            connection = ConnectionState.Connected("AA", "REEBOK"),
            treadmill = TreadmillState(status = BeltStatus.Running, speedKmh = 11.0, targetSpeedKmh = 11.0, inclinePercent = 2.0, heartRateBpm = 142, updatedAtMillis = 1_000),
            activeUser = ana, session = SessionControls(SessionKind.Program),
        )
        return ui to RunUiState(kind = SessionKind.Program, program = app.programRunner.progress.value, brakeNotice = notice)
    }

    @Test fun theBrakeNoticeIsOnTheLiveScreenOfTheProgramWhileItLasts() {
        val (ui, run) = runningProgramUi(BrakeNotice(1))
        host { ProgramRunScreen(ui, run, ControlActions(), RunActions()) }
        rule.onNodeWithTag("hr_block_panel").assertIsDisplayed()
        rule.onNodeWithTag("brake_notice", useUnmergedTree = true).assertTextEquals(brakeText)
        rule.onNodeWithTag("hr_control_text", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test fun withoutAReductionThereIsNoNotice() {
        val (ui, run) = runningProgramUi(null)
        host { ProgramRunScreen(ui, run, ControlActions(), RunActions()) }
        rule.onNodeWithTag("brake_notice", useUnmergedTree = true).assertDoesNotExist()
        rule.onNodeWithTag("hr_control_text", useUnmergedTree = true).assertExists()
    }

    // ---- The 85 % rule ----

    private fun withheldWalk(): FitnessTestResult = GeneratedFixtures.balke(
        id = "0b6f8f9e-4d0c-4d43-9f43-2f0c6f1a7b51", startedAtMillis = app.nowMillis() - day, peakBpm = 140.4, hrMaxBpm = 185,
        effort = EffortAssessment.NotMaximal, vo2max = null,
    ).copy(outputsWithheld = OutputsWithheldReason.PeakHeartRateBelowMinimum)

    private fun withheldRun(): FitnessTestResult {
        val base = DemoPrograms.maxTest(id = "1c7a9fae-5e1d-4e54-8a54-3a7d1b2c8d62", startedAtMillis = app.nowMillis() - 2 * day, peakBpm = 150, ageYears = 40)
        return base.copy(outputs = null, outputsWithheld = OutputsWithheldReason.PeakHeartRateBelowMinimum)
    }

    @Test fun aWalkingResultWithWithheldOutputsSaysItWasNotAMaximalEffort() {
        val result = withheldWalk()
        runBlocking { data.fitnessTests.save(ana.id, result) }
        show(Screen.FitnessTests, Screen.FitnessResult(result.id))
        rule.onNodeWithTag("result_not_maximal").assertIsDisplayed()
        rule.onNodeWithTag("result_not_maximal").assertTextEquals(notMaximalWalk)
    }

    @Test fun aRunningResultWithWithheldOutputsSaysItAndIsNotCalledInvalid() {
        val result = withheldRun()
        runBlocking { data.fitnessTests.save(ana.id, result) }
        show(Screen.FitnessTests, Screen.FitnessResult(result.id))
        rule.onNodeWithTag("result_not_maximal").assertTextEquals(notMaximalRun)
        rule.onNodeWithTag("result_invalid_text").assertDoesNotExist()
    }

    @Test fun theTestsHistoryShowsItToo() {
        val walk = withheldWalk()
        val run = withheldRun()
        runBlocking {
            data.fitnessTests.save(ana.id, walk)
            data.fitnessTests.save(ana.id, run)
            data.fitnessTests.save(ana.id, GeneratedFixtures.balke(id = "2d8bafbf-6f2e-4f65-9b65-4b8e2c3d9e73", startedAtMillis = app.nowMillis() - 5 * day))
        }
        show(Screen.FitnessTests)
        rule.onNodeWithTag("test_not_maximal_${walk.id}", useUnmergedTree = true).assertTextEquals(notMaximalWalk)
        rule.onNodeWithTag("test_not_maximal_${run.id}", useUnmergedTree = true).assertTextEquals(notMaximalRun)
        // A test that has its figures says nothing of the kind.
        rule.onNodeWithTag("test_not_maximal_2d8bafbf-6f2e-4f65-9b65-4b8e2c3d9e73", useUnmergedTree = true).assertDoesNotExist()
    }

    // ---- The recommendation of a test ----

    private fun profileWithThresholds() = FitnessProfile(
        computedAtMillis = 0, vo2max = null, mas = null, hrMax = null,
        thresholds = ThresholdProfile(
            vt1 = ThresholdEstimate(131, null, ThresholdQuality.Medium, emptyList()),
            vt2 = ThresholdEstimate(149, null, ThresholdQuality.Medium, emptyList()),
            history = emptyList(),
        ),
    )

    private val recommendations = listOf(
        ThresholdRecommendation(ThresholdKind.Vt1, RecommendationDirection.Higher, 8, 4),
        ThresholdRecommendation(ThresholdKind.Vt2, RecommendationDirection.Lower, 6, 5),
    )

    @Test fun thePerformanceThresholdsCardRecommendsATest() {
        val state = PerformanceUiState(
            loaded = true, user = ana, profile = profileWithThresholds(),
            trends = PerformanceTrendsCalculator.compute(emptyList(), com.treadz.console.userdata.PerformanceInputs(), app.date.toEpochDay()),
            recommendations = recommendations,
        )
        host { PerformanceScreen(state, PerformanceActions()) }
        rule.onNodeWithTag("test_recommendation_vt1", useUnmergedTree = true).assertExists()
        rule.onNode(androidx.compose.ui.test.hasText(vt1Higher), useUnmergedTree = true).assertExists()
        rule.onNode(androidx.compose.ui.test.hasText(vt2Lower), useUnmergedTree = true).assertExists()
    }

    @Test fun withoutARecommendationTheCardSaysNothingOfIt() {
        val state = PerformanceUiState(
            loaded = true, user = ana, profile = profileWithThresholds(),
            trends = PerformanceTrendsCalculator.compute(emptyList(), com.treadz.console.userdata.PerformanceInputs(), app.date.toEpochDay()),
        )
        host { PerformanceScreen(state, PerformanceActions()) }
        rule.onNodeWithTag("test_recommendations", useUnmergedTree = true).assertDoesNotExist()
    }

    @Test fun theFitnessTestsScreenRecommendsATestAndItCannotBeDismissed() {
        host {
            FitnessTestsScreen(FitnessTestsUiState(loaded = true, user = ana, recommendations = recommendations), FitnessTestsActions())
        }
        rule.onNodeWithTag("test_recommendations").assertIsDisplayed()
        rule.onNode(androidx.compose.ui.test.hasText(vt1Higher)).assertExists()
        rule.onNode(androidx.compose.ui.test.hasText(vt2Lower)).assertExists()
        // Nothing to press: it is not a question and it is not a dialog.
        assertEquals(0, rule.onAllNodes(hasTestTag("test_recommendation_dismiss")).fetchSemanticsNodes().size)
    }

    // ---- The strap check ----

    private fun guide(test: com.treadz.console.programs.fitness.FitnessTestProgress) {
        val ui = MainUiState(
            connection = ConnectionState.Connected("AA", "REEBOK"),
            treadmill = TreadmillState(status = BeltStatus.Running, speedKmh = 4.4, targetSpeedKmh = 4.4, inclinePercent = 1.0, heartRateBpm = 104, updatedAtMillis = 1_000),
            activeUser = ana, session = SessionControls(SessionKind.FitnessTest, pause = false),
        )
        host { FitnessRunScreen(ui, RunUiState(kind = SessionKind.FitnessTest, test = test), ControlActions(), RunActions()) }
    }

    @Test fun aPoorStrapIsToldInTheWarmUpWithThePercentage() {
        guide(TrainingFixtures.walkWarmUp().copy(strapCheck = StrapCheck(12.4, poor = true)))
        rule.onNodeWithTag("strap_warning").assertIsDisplayed()
        rule.onNodeWithTag("strap_warning_text").assertTextEquals(strapText)
        rule.onNodeWithTag("strap_warning_detail").assertIsDisplayed()
    }

    @Test fun aGoodStrapOrNoCheckYetSaysNothing() {
        guide(TrainingFixtures.walkWarmUp().copy(strapCheck = StrapCheck(2.0, poor = false)))
        rule.onNodeWithTag("strap_warning").assertDoesNotExist()
    }

    @Test fun theWarningNeverBlocksTheTest() {
        guide(TrainingFixtures.walkWarmUp().copy(strapCheck = StrapCheck(12.4, poor = true)))
        // The test goes on: its buttons are still there.
        rule.onNodeWithTag("instruction_panel").assertIsDisplayed()
        rule.onNodeWithTag("ramp_start_panel").assertExists()
    }
}

@Config(qualifiers = "en-w1280dp-h800dp-land-hdpi")
class ImprovementsEnglishUiTest : ImprovementsUiScenes() {
    override val brakeText = "Lowered by α1: aerobic threshold signal"
    override val notMaximalWalk = "Not a maximal effort: your peak (140 bpm) did not reach 85 % of your maximum heart rate (185 bpm). This test gives no VO2max or MAS."
    override val notMaximalRun = "Not a maximal effort: your peak (150 bpm) did not reach 85 % of your maximum heart rate (180 bpm). This test gives no VO2max or MAS."
    override val vt1Higher = "In your last 4 workouts VT1 looks about 8 bpm higher: do you want to take a test?"
    override val vt2Lower = "In your last 5 workouts VT2 looks about 6 bpm lower: do you want to take a test?"
    override val strapText = "Wet the strap or adjust it"
}

@Config(qualifiers = "es-w360dp-h780dp-port-xhdpi")
class ImprovementsSpanishUiTest : ImprovementsUiScenes() {
    override val brakeText = "Bajada por α1: señal de umbral aeróbico"
    override val notMaximalWalk = "Esfuerzo no máximo: tu pico (140 ppm) no llegó al 85 % de tu FCmáx (185 ppm). Este test no da VO2máx ni VAM."
    override val notMaximalRun = "Esfuerzo no máximo: tu pico (150 ppm) no llegó al 85 % de tu FCmáx (180 ppm). Este test no da VO2máx ni VAM."
    override val vt1Higher = "En tus últimos 4 entrenos el VT1 parece unas 8 ppm más alto: ¿haces un test?"
    override val vt2Lower = "En tus últimos 5 entrenos el VT2 parece unas 6 ppm más bajo: ¿haces un test?"
    override val strapText = "Moja la banda o ajústala"
}
