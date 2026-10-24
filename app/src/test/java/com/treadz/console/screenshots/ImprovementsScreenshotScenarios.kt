package com.treadz.console.screenshots

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import com.github.takahirom.roborazzi.captureRoboImage
import com.treadz.console.MainUiState
import com.treadz.console.analysis.fitness.Sex
import com.treadz.console.analysis.thresholds.ThresholdQuality
import com.treadz.console.analysis.trends.RecommendationDirection
import com.treadz.console.analysis.trends.ThresholdRecommendation
import com.treadz.console.analysis.workout.ThresholdKind
import com.treadz.console.controls.SessionControls
import com.treadz.console.controls.SessionKind
import com.treadz.console.fakes.GeneratedFixtures
import com.treadz.console.fakes.PerformanceFixtures
import com.treadz.console.fakes.ThresholdFixtures
import com.treadz.console.fakes.TrainingFixtures
import com.treadz.console.fakes.TrainingRig
import com.treadz.console.nav.Screen
import com.treadz.console.performance.PerformanceTrendsCalculator
import com.treadz.console.performance.PerformanceUiState
import com.treadz.console.programs.fitness.EffortAssessment
import com.treadz.console.programs.fitness.FitnessProfile
import com.treadz.console.programs.fitness.OutputsWithheldReason
import com.treadz.console.programs.fitness.StrapCheck
import com.treadz.console.programs.fitness.ThresholdEstimate
import com.treadz.console.programs.fitness.ThresholdProfile
import com.treadz.console.programs.model.Block
import com.treadz.console.programs.model.BlockEnd
import com.treadz.console.programs.model.BlockPhase
import com.treadz.console.programs.model.BlockTarget
import com.treadz.console.programs.model.HeartRateAdjust
import com.treadz.console.programs.model.HeartRateZone
import com.treadz.console.programs.model.Program
import com.treadz.console.programs.model.ProgramKind
import com.treadz.console.programs.plan.ProgramResolver
import com.treadz.console.programs.plan.ResolvedPlan
import com.treadz.console.programs.plan.StartParameters
import com.treadz.console.training.BrakeNotice
import com.treadz.console.training.FitnessTestsUiState
import com.treadz.console.training.ProgramsViewModel
import com.treadz.console.training.RunUiState
import com.treadz.console.training.StartOutcome
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
import com.treadz.console.userdata.PerformanceInputs
import com.treadz.console.userdata.UserProfile
import com.treadz.console.userdata.WorkoutEndReason
import com.treadz.console.userdata.WorkoutLink
import com.treadz.console.userdata.memory.DemoData
import com.treadz.console.userdata.memory.DemoPrograms
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode
import java.time.ZoneId

/**
 * The improvements batch on synthetic data: the programs screen in its new order, the sessions inside each program, the history
 * without the tag of the normal end, the α1 brake notice, the 85 % rule in a test result, the recommendation of a test, the
 * strap check and the zones of the profile in the program editor. Subclasses set the language and screen size with
 * `@Config(qualifiers = ...)`. Images are only written with `./gradlew :app:recordRoborazziDebug`.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
abstract class ImprovementsScreenshotScenarios(private val prefix: String) {

    @get:Rule val rule = createComposeRule()

    private val rig = TrainingRig()
    private val app get() = rig.app
    private val data get() = app.userData
    private val day = 86_400_000L
    private val ana: UserProfile by lazy { runBlocking { DemoData.populate(data, app.date).first } }
    private val zone = ZoneId.systemDefault()

    private fun idleBelt() {
        app.controller.state.value = TreadmillState(heartRateBpm = 72, updatedAtMillis = 1_000)
    }

    /** Opens the app (as after a start), goes to the screens of [startAt] and runs [interact] before the picture. */
    private fun capture(name: String, vararg startAt: Screen, interact: () -> Unit = {}) {
        ana
        idleBelt()
        val navigation = app.newNavigation()
        rule.setContent { TreadZRoot(dependencies = app, viewModel = app.viewModel, permissions = PermissionsUi(), actions = MainActions(), navigation = navigation) }
        rule.waitForIdle()
        navigation.replaceAll(Screen.Main)
        startAt.forEach(navigation::push)
        rule.waitForIdle()
        interact()
        rule.waitForIdle()
        rule.onRoot().captureRoboImage("build/outputs/roborazzi/${prefix}_$name.png")
    }

    private fun host(name: String, content: @Composable () -> Unit) {
        rule.setContent {
            TreadZFrame(darkTheme = true, keepScreenOn = false, alert = null, onDismissAlert = {}) { Box(androidx.compose.ui.Modifier.fillMaxSize()) { content() } }
        }
        rule.waitForIdle()
        rule.onRoot().captureRoboImage("build/outputs/roborazzi/${prefix}_$name.png")
    }

    private fun click(tag: String) {
        runCatching { rule.onNodeWithTag(tag).performScrollTo() }
        rule.onNodeWithTag(tag).performClick()
        rule.waitForIdle()
    }

    private fun scrollTo(tag: String) {
        rule.onNodeWithTag(tag).performScrollTo()
        rule.waitForIdle()
    }

    private fun scrollList(listTag: String, tag: String) {
        rule.onNodeWithTag(listTag).performScrollToNode(hasTestTag(tag))
        rule.waitForIdle()
    }

    private fun at(daysAgo: Long, hour: Int = 18) = app.date.minusDays(daysAgo).atTime(hour, 30).atZone(zone).toInstant().toEpochMilli()

    /** A finished workout of Ana that ran [plan] (a workout without a program when `null`). */
    private fun workout(plan: ResolvedPlan?, daysAgo: Long, minutes: Int = 30, reason: WorkoutEndReason = WorkoutEndReason.Stopped): Long {
        val start = at(daysAgo)
        val samples = PerformanceFixtures.samples(PerformanceFixtures.Session(minutes = minutes, seed = daysAgo), start)
        val id = data.addWorkout(ana.id, PerformanceFixtures.stats(samples), samples, start, reason)
        if (plan != null) runBlocking { data.workouts.link(id, WorkoutLink(plan = plan)) }
        return id
    }

    private fun generated(generatorId: String): ResolvedPlan {
        val generated = GeneratedFixtures.generate(data, ana, generatorId, null, app.date, app.nowMillis())
        return ProgramResolver.resolve(generated.program, StartParameters())
    }

    // ---- The Programs screen ----

    @Test fun i01_programs_my_programs_first() {
        idleBelt()
        capture("i01_programs_order", Screen.Programs)
    }

    @Test fun i02_programs_personalised_and_standard_after_mine() {
        idleBelt()
        // A session saved by an older version is listed nowhere.
        runBlocking { data.programs.save(ana.id, GeneratedFixtures.generate(data, ana, "zone2", null, app.date, app.nowMillis()).program) }
        capture("i02_programs_personalised", Screen.Programs) { scrollList("programs_list", "couch_start_button") }
    }

    @Test fun i03_programs_standard_at_the_end() {
        idleBelt()
        capture("i03_programs_standard", Screen.Programs) { scrollList("programs_list", "program_standard-glutes") }
    }

    // ---- The sessions inside each program ----

    @Test fun i04_standard_program_with_its_sessions() {
        idleBelt()
        val plan = rig.standardPlan("standard-pyramid")
        workout(plan, 12, 30)
        workout(plan, 6, 40, WorkoutEndReason.ConnectionLost)
        workout(plan, 2, 30)
        workout(rig.standardPlan("standard-hill"), 4)
        capture("i04_standard_sessions", Screen.Programs, Screen.ProgramStart("standard-pyramid")) { scrollTo("program_sessions") }
    }

    @Test fun i05_own_program_with_its_sessions() {
        idleBelt()
        val own = runBlocking { data.programs.programs(ana.id).first() }.first { it.program.origin == null }
        val plan = ProgramResolver.resolve(own.program, ProgramsViewModel.previewParameters(own.program))
        workout(plan, 3, 45)
        workout(plan, 1, 50)
        capture("i05_own_sessions", Screen.Programs, Screen.ProgramStart(own.id)) { scrollTo("program_sessions") }
    }

    @Test fun i06_program_without_sessions() {
        idleBelt()
        capture("i06_no_sessions", Screen.Programs, Screen.ProgramStart("standard-intervals")) { scrollTo("program_sessions") }
    }

    @Test fun i07_generator_with_its_sessions() {
        idleBelt()
        val plan = generated("zone2")
        workout(plan, 9, 35)
        workout(plan, 5, 45)
        workout(plan, 1, 40)
        workout(generated("tempo"), 2)
        capture("i07_generator_sessions", Screen.Programs, Screen.Generator("zone2")) { scrollTo("program_sessions") }
    }

    @Test fun i08_generator_top_without_the_regenerate_note() {
        idleBelt()
        workout(generated("tempo"), 2)
        capture("i08_generator_top", Screen.Programs, Screen.Generator("tempo"))
    }

    // ---- The history ----

    @Test fun i09_history_without_the_tag_of_the_normal_end() {
        workout(null, 1, 30)
        workout(rig.standardPlan("standard-hill"), 2, 40)
        workout(generated("zone2"), 4, 45, WorkoutEndReason.ConnectionLost)
        workout(rig.standardPlan("standard-pyramid"), 6, 20, WorkoutEndReason.SafetyKeyRemoved)
        capture("i09_history", Screen.History)
    }

    // ---- The α1 brake notice ----

    private fun runningProgram(notice: BrakeNotice?): Pair<MainUiState, RunUiState> {
        val outcome = rig.startProgram(ProgramResolver.resolve(TrainingFixtures.heartRateProgram()), ana.id)
        check(outcome == StartOutcome.Started) { "$outcome" }
        rig.advance(3 * 60_000L)
        val ui = MainUiState(
            connection = ConnectionState.Connected("AA", "REEBOK"),
            treadmill = TreadmillState(status = BeltStatus.Running, speedKmh = 6.5, targetSpeedKmh = 6.5, inclinePercent = 7.0, heartRateBpm = 141, updatedAtMillis = 1_000),
            activeUser = ana, session = SessionControls(SessionKind.Program),
        )
        return ui to RunUiState(kind = SessionKind.Program, program = app.programRunner.progress.value, programSeries = app.training.programSeries.value, brakeNotice = notice)
    }

    @Test fun i10_brake_notice_on_the_live_screen_of_a_program() {
        ana
        val (ui, run) = runningProgram(BrakeNotice(1))
        host("i10_brake_notice") { ProgramRunScreen(ui, run, ControlActions(), RunActions()) }
    }

    // ---- The 85 % rule ----

    private fun withheldWalk() = GeneratedFixtures.balke(
        id = "0b6f8f9e-4d0c-4d43-9f43-2f0c6f1a7b51", startedAtMillis = at(1), peakBpm = 141.0, hrMaxBpm = 183,
        effort = EffortAssessment.NotMaximal, vo2max = null,
        thresholds = ThresholdFixtures.found(ThresholdQuality.Low, vt2Bpm = null),
    ).copy(outputsWithheld = OutputsWithheldReason.PeakHeartRateBelowMinimum)

    @Test fun i11_result_of_a_walking_test_that_was_not_maximal() {
        ana
        val result = withheldWalk()
        runBlocking { data.fitnessTests.save(ana.id, result) }
        capture("i11_result_not_maximal", Screen.FitnessTests, Screen.FitnessResult(result.id))
    }

    @Test fun i12_result_of_a_running_test_that_was_not_maximal() {
        ana
        val base = DemoPrograms.maxTest(id = "1c7a9fae-5e1d-4e54-8a54-3a7d1b2c8d62", startedAtMillis = at(0), peakBpm = 150, ageYears = 39, sex = Sex.Female, weightKg = 65.0)
        val result = base.copy(outputs = null, outputsWithheld = OutputsWithheldReason.PeakHeartRateBelowMinimum)
        runBlocking { data.fitnessTests.save(ana.id, result) }
        capture("i12_run_not_maximal", Screen.FitnessTests, Screen.FitnessResult(result.id))
    }

    @Test fun i13_tests_history_with_the_result_that_was_not_maximal() {
        ana
        runBlocking { data.fitnessTests.save(ana.id, withheldWalk()) }
        capture("i13_tests_history", Screen.FitnessTests) { scrollTo("test_0b6f8f9e-4d0c-4d43-9f43-2f0c6f1a7b51") }
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

    @Test fun i14_performance_thresholds_card_recommends_a_test() {
        ana
        val state = PerformanceUiState(
            loaded = true, user = ana, profile = profileWithThresholds(),
            trends = PerformanceTrendsCalculator.compute(emptyList(), PerformanceInputs(), app.date.toEpochDay()),
            recommendations = recommendations,
        )
        host("i14_performance_recommendation") { PerformanceScreen(state, PerformanceActions()) }
    }

    @Test fun i15_fitness_tests_screen_recommends_a_test() {
        ana
        val state = FitnessTestsUiState(loaded = true, user = ana, recommendations = recommendations.take(1))
        host("i15_tests_recommendation") { FitnessTestsScreen(state, FitnessTestsActions()) }
    }

    // ---- The strap check ----

    @Test fun i16_strap_check_in_the_warm_up_of_a_maximal_test() {
        ana
        val test = TrainingFixtures.walkWarmUp().copy(strapCheck = StrapCheck(11.6, poor = true))
        val ui = MainUiState(
            connection = ConnectionState.Connected("AA", "REEBOK"),
            treadmill = TreadmillState(status = BeltStatus.Running, speedKmh = 4.4, targetSpeedKmh = 4.4, inclinePercent = 1.0, heartRateBpm = 104, updatedAtMillis = 1_000),
            activeUser = ana, session = SessionControls(SessionKind.FitnessTest, pause = false),
        )
        host("i16_strap_check") { FitnessRunScreen(ui, RunUiState(kind = SessionKind.FitnessTest, test = test), ControlActions(), RunActions()) }
    }

    // ---- The zones of the profile ----

    /** Ana has a walking test with thresholds, so her zones come from them (VT1 132, VT2 149). */
    private fun withThresholds() {
        ana
        runBlocking {
            data.fitnessTests.save(ana.id, GeneratedFixtures.balke(id = "0b6f8f9e-4d0c-4d43-9f43-2f0c6f1a7b61", startedAtMillis = at(5), thresholds = ThresholdFixtures.found(ThresholdQuality.High)))
        }
    }

    @Test fun i17_editor_new_heart_rate_block_asks_for_zone_two_of_the_profile() {
        withThresholds()
        capture("i17_editor_profile_zone", Screen.Programs, Screen.ProgramEditor(null, 1)) {
            click("block_1_target_hr")
            scrollTo("block_1_zone_bpm")
        }
    }

    @Test fun i18_editor_another_zone_of_the_profile() {
        withThresholds()
        capture("i18_editor_zone_four", Screen.Programs, Screen.ProgramEditor(null, 1)) {
            click("block_1_target_hr")
            click("block_1_pzone_4")
            scrollTo("block_1_zone_bpm")
        }
    }

    @Test fun i19_start_screen_shows_the_zone_of_the_profile_in_bpm() {
        withThresholds()
        idleBelt()
        val program = Program(
            id = "", name = "Zone 2 base", kind = ProgramKind.Free,
            steps = listOf(
                Block(BlockPhase.WarmUp, BlockEnd.Duration(300), BlockTarget.Absolute(4.5, 0.0)),
                Block(
                    BlockPhase.Work, BlockEnd.Duration(1800),
                    BlockTarget.HeartRate(HeartRateZone.ofProfile(2), HeartRateAdjust.Speed, 5.0, 9.0, BlockTarget.Absolute(6.0, 1.0)),
                    name = "Zone 2",
                ),
                Block(BlockPhase.CoolDown, BlockEnd.Duration(300), BlockTarget.Absolute(4.0, 0.0)),
            ),
        )
        val id = (runBlocking { data.programs.save(ana.id, program) } as com.treadz.console.userdata.ProgramSaveResult.Saved).program.id
        capture("i19_start_profile_zone", Screen.Programs, Screen.ProgramStart(id)) { runCatching { scrollTo("plan_block_zone_1") } }
    }
}
