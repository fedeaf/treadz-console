package com.treadz.console.screenshots

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.isDialog
import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performScrollTo
import androidx.compose.ui.test.performScrollToNode
import androidx.compose.ui.test.performTextReplacement
import com.github.takahirom.roborazzi.captureRoboImage
import com.treadz.console.MainUiState
import com.treadz.console.controls.ControlsNote
import com.treadz.console.controls.SessionControls
import com.treadz.console.controls.SessionKind
import com.treadz.console.fakes.GeneratedFixtures
import com.treadz.console.fakes.TrainingFixtures
import com.treadz.console.fakes.TrainingRig
import com.treadz.console.nav.Screen
import com.treadz.console.programs.fitness.EffortAssessment
import com.treadz.console.programs.fitness.FitnessEndReason
import com.treadz.console.programs.fitness.FitnessTestFlag
import com.treadz.console.programs.fitness.FitnessTestProgress
import com.treadz.console.programs.fitness.FitnessTestType
import com.treadz.console.programs.fitness.HeartRateSourceKind
import com.treadz.console.programs.model.Block
import com.treadz.console.programs.model.BlockEnd
import com.treadz.console.programs.model.BlockPhase
import com.treadz.console.programs.model.BlockTarget
import com.treadz.console.programs.model.HeartRateAdjust
import com.treadz.console.programs.model.HeartRateBasis
import com.treadz.console.programs.model.HeartRateZone
import com.treadz.console.programs.model.Program
import com.treadz.console.programs.model.ProgramKind
import com.treadz.console.programs.model.Repeat
import com.treadz.console.programs.personalised.CouchToFiveK
import com.treadz.console.programs.personalised.ExerciseMode
import com.treadz.console.programs.plan.ProgramResolver
import com.treadz.console.training.GeneratorForm
import com.treadz.console.training.RunUiState
import com.treadz.console.training.StartOutcome
import com.treadz.console.treadmill.BeltStatus
import com.treadz.console.treadmill.ConnectionState
import com.treadz.console.treadmill.TreadmillState
import com.treadz.console.treadmill.control.Clock
import com.treadz.console.ui.ControlActions
import com.treadz.console.ui.FitnessRunScreen
import com.treadz.console.ui.generatorProgramName
import com.treadz.console.ui.MainActions
import com.treadz.console.ui.PermissionsUi
import com.treadz.console.ui.RunActions
import com.treadz.console.ui.TreadZFrame
import com.treadz.console.ui.TreadZRoot
import com.treadz.console.userdata.ProgramSaveResult
import com.treadz.console.userdata.UserProfile
import com.treadz.console.userdata.WorkoutLink
import com.treadz.console.userdata.memory.DemoData
import com.treadz.console.userdata.memory.InMemoryUserData
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.GraphicsMode
import java.time.ZoneId

/**
 * Milestone 5 phase C2 screens: heart-rate blocks in the editor, the Personalised section of the programs list, the
 * generator screens, Couch to 5K, the run screens of generated sessions, the history by origin and the walking heart-rate
 * test. Subclasses set the language and screen size with `@Config(qualifiers = ...)`. Images are only written with
 * `./gradlew :app:recordRoborazziDebug`.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
abstract class PersonalisedScreenshotScenarios(private val prefix: String) {

    @get:Rule val rule = createComposeRule()

    private val zone = ZoneId.systemDefault()
    private val day = GeneratedFixtures.DAY_MS
    private val noon = com.treadz.console.fakes.FakeApp.TODAY.atTime(12, 0).atZone(zone).toInstant().toEpochMilli()
    private var clockMillis = noon
    private val data = InMemoryUserData(clock = Clock { clockMillis }, zone = zone)
    private val rig = TrainingRig(data, baseMillis = noon)
    private val app get() = rig.app
    private val now get() = app.nowMillis()

    // The belt is at rest unless a scenario says otherwise.
    init {
        app.controller.state.value = TreadmillState(heartRateBpm = 72, updatedAtMillis = rig.clockMillis())
    }

    /** Opens the app (as after a start), goes to the screens of [startAt] and runs [interact] before the picture. */
    private fun capture(name: String, startAt: List<Screen> = emptyList(), interact: () -> Unit = {}) {
        val navigation = app.newNavigation()
        rule.setContent {
            TreadZRoot(dependencies = app, viewModel = app.viewModel, permissions = PermissionsUi(), actions = MainActions(), navigation = navigation)
        }
        rule.waitForIdle()
        navigation.replaceAll(Screen.Main)
        startAt.forEach(navigation::push)
        rule.waitForIdle()
        interact()
        rule.waitForIdle()
        rule.onRoot().captureRoboImage("build/outputs/roborazzi/${prefix}_$name.png")
    }

    private fun captureDialog(name: String) {
        rule.waitForIdle()
        rule.onNode(isDialog()).captureRoboImage("build/outputs/roborazzi/${prefix}_$name.png")
    }

    private fun click(tag: String) {
        runCatching { rule.onNodeWithTag(tag).performScrollTo() }
        rule.onNodeWithTag(tag).performClick()
        rule.waitForIdle()
    }

    private fun scrollTo(tag: String) {
        runCatching { rule.onNodeWithTag(tag).performScrollTo() }
        rule.waitForIdle()
    }

    private fun scrollList(listTag: String, tag: String) {
        runCatching { rule.onNodeWithTag(listTag).performScrollToNode(hasTestTag(tag)) }
        rule.waitForIdle()
    }

    /** Draws [content] inside the app frame, like the root does, without the navigation. */
    private fun host(name: String, content: @Composable () -> Unit) {
        rule.setContent {
            TreadZFrame(darkTheme = true, keepScreenOn = false, alert = null, onDismissAlert = {}) { Box(androidx.compose.ui.Modifier.fillMaxSize()) { content() } }
        }
        rule.waitForIdle()
        rule.onRoot().captureRoboImage("build/outputs/roborazzi/${prefix}_$name.png")
    }

    private fun demo(): UserProfile = runBlocking { DemoData.populate(data, app.date).first }

    private fun idleBelt(heartRate: Int? = 72) {
        app.controller.state.value = TreadmillState(heartRateBpm = heartRate, updatedAtMillis = rig.clockMillis())
    }

    /** The name the app gives a generated program (translated, with the mode and the main input). */
    private fun programName(generatorId: String, form: GeneratorForm?): String {
        val descriptor = checkNotNull(com.treadz.console.programs.personalised.ProgramGenerators.byId(generatorId)).descriptor
        val context = androidx.test.core.app.ApplicationProvider.getApplicationContext<android.content.Context>()
        return generatorProgramName(context, descriptor, form ?: GeneratorForm.defaults(descriptor))
    }

    /** A saved personalised session "generated" [daysAgo] days ago. */
    private fun saveSession(user: UserProfile, generatorId: String, daysAgo: Int, form: GeneratorForm? = null): String {
        clockMillis = noon - daysAgo * day
        val generated = GeneratedFixtures.generate(data, user, generatorId, form, app.date, clockMillis, name = programName(generatorId, form))
        val id = (runBlocking { data.programs.save(user.id, generated.program) } as ProgramSaveResult.Saved).program.id
        clockMillis = noon
        return id
    }

    private fun couchRecord(user: UserProfile, session: Int, daysAgo: Double, completed: Boolean = true, endHeartRate: Double = 138.0, reductions: Int = 0) =
        GeneratedFixtures.addSession(
            data, user.id,
            GeneratedFixtures.record(
                session = session, startedAtMillis = noon - (daysAgo * day).toLong(), completed = completed, endHeartRate = endHeartRate,
                manualReductions = reductions, settledValue = 8.6,
            ),
        )

    // ---- Heart-rate blocks in the editor ----

    private fun editor(name: String, id: String? = null, interact: () -> Unit) =
        capture(name, startAt = listOf(Screen.Programs, Screen.ProgramEditor(id, 1)), interact = interact)

    @Test fun e01_editor_heart_rate_block_zone() {
        demo()
        editor("e01_editor_hr_zone") {
            click("block_1_target_hr")
            scrollTo("block_1_zone_bpm")
        }
    }

    @Test fun e02_editor_heart_rate_custom_zone_and_reserve() {
        demo()
        editor("e02_editor_hr_custom") {
            click("block_1_target_hr")
            click("block_1_zone_custom")
            rule.onNodeWithTag("block_1_zone_low").performTextReplacement("55")
            rule.onNodeWithTag("block_1_zone_high").performTextReplacement("65")
            click("block_1_basis_reserve")
            scrollTo("block_1_zone_bpm")
        }
    }

    @Test fun e03_editor_reserve_disabled_without_a_resting_heart_rate() {
        editor("e03_editor_hr_no_resting") {
            click("block_1_target_hr")
            scrollTo("block_1_resting_link")
        }
    }

    @Test fun e04_editor_short_heart_rate_block_walking() {
        demo()
        editor("e04_editor_hr_short") {
            click("block_1_target_hr")
            click("block_1_adjust_incline")
            rule.onNodeWithTag("block_1_duration").performTextReplacement("2:30")
            scrollTo("block_1_hr_short")
        }
    }

    @Test fun e05_editor_heart_rate_validation_errors() {
        demo()
        editor("e05_editor_hr_errors") {
            click("block_1_target_hr")
            click("block_1_zone_custom")
            rule.onNodeWithTag("block_1_zone_low").performTextReplacement("70")
            rule.onNodeWithTag("block_1_zone_high").performTextReplacement("60")
            rule.onNodeWithTag("block_1_min").performTextReplacement("9")
            rule.onNodeWithTag("block_1_max").performTextReplacement("25")
            rule.onNodeWithTag("program_name_field").performTextReplacement("Zone test")
            click("save_program_button")
            scrollTo("block_1_basis_max")
        }
    }

    @Test fun e06_editor_heart_rate_in_a_repeat_is_controlled_between_repetitions() {
        val ana = demo()
        val program = Program(
            id = "", name = "4 x 4 by heart rate", kind = ProgramKind.Free,
            steps = listOf(
                Block(BlockPhase.WarmUp, BlockEnd.Duration(600), BlockTarget.Absolute(5.0, 1.0)),
                Repeat(
                    4,
                    listOf(
                        Block(
                            BlockPhase.Work, BlockEnd.Duration(240),
                            BlockTarget.HeartRate(HeartRateZone(HeartRateBasis.MaxHeartRate, 85, 95), HeartRateAdjust.Speed, 7.0, 11.0, BlockTarget.Absolute(9.0, 1.0)),
                        ),
                        Block(BlockPhase.Recovery, BlockEnd.Duration(180), BlockTarget.Absolute(5.0, 1.0)),
                    ),
                ),
                Block(BlockPhase.CoolDown, BlockEnd.Duration(300), BlockTarget.Absolute(4.5, 0.0)),
            ),
        )
        val id = (runBlocking { data.programs.save(ana.id, program) } as ProgramSaveResult.Saved).program.id
        editor("e06_editor_hr_repeat", id) { scrollTo("block_1_0_mode") }
    }

    @Test fun e07_editor_preview_marks_the_heart_rate_blocks() {
        demo()
        editor("e07_editor_hr_preview") {
            click("block_1_target_hr")
            scrollTo("editor_preview")
        }
    }

    // ---- The Personalised section of the programs list ----

    @Test fun p01_personalised_section() {
        demo()
        idleBelt()
        capture("p01_personalised", startAt = listOf(Screen.Programs)) { scrollList("programs_list", "generator_zone2") }
    }

    @Test fun p02_saved_sessions_folded() {
        val ana = demo()
        (1..5).forEach { saveSession(ana, "zone2", daysAgo = 6 - it, form = GeneratorForm(mode = if (it % 2 == 0) ExerciseMode.Walk else ExerciseMode.Run, durationMinutes = 20 + it * 10)) }
        saveSession(ana, "intervals-30-30", 3, GeneratorForm(series = 18))
        capture("p02_saved_folded", startAt = listOf(Screen.Programs)) { scrollList("programs_list", "toggle_sessions_zone2") }
    }

    @Test fun p03_saved_sessions_unfolded() {
        val ana = demo()
        (1..5).forEach { saveSession(ana, "zone2", daysAgo = 6 - it, form = GeneratorForm(mode = if (it % 2 == 0) ExerciseMode.Walk else ExerciseMode.Run, durationMinutes = 20 + it * 10)) }
        capture("p03_saved_unfolded", startAt = listOf(Screen.Programs)) {
            scrollList("programs_list", "toggle_sessions_zone2")
            click("toggle_sessions_zone2")
            scrollList("programs_list", "toggle_sessions_zone2")
        }
    }

    @Test fun p04_couch_card_first_session() {
        demo()
        capture("p04_couch_first", startAt = listOf(Screen.Programs)) { scrollList("programs_list", "couch_start_button") }
    }

    @Test fun p05_couch_card_advance() {
        couchRecord(demo(), session = 7, daysAgo = 4.0)
        capture("p05_couch_advance", startAt = listOf(Screen.Programs)) { scrollList("programs_list", "couch_start_button") }
    }

    @Test fun p06_couch_card_repeat_after_a_hard_effort() {
        couchRecord(demo(), session = 7, daysAgo = 3.0, endHeartRate = 168.0)
        capture("p06_couch_repeat", startAt = listOf(Screen.Programs)) { scrollList("programs_list", "couch_start_button") }
    }

    @Test fun p07_couch_card_step_back_and_recent_session() {
        couchRecord(demo(), session = 7, daysAgo = 0.4, completed = false)
        capture("p07_couch_step_back", startAt = listOf(Screen.Programs)) { scrollList("programs_list", "couch_start_button") }
    }

    @Test fun p08_couch_card_long_break() {
        couchRecord(demo(), session = 10, daysAgo = 15.0)
        capture("p08_couch_long_break", startAt = listOf(Screen.Programs)) { scrollList("programs_list", "couch_start_button") }
    }

    @Test fun p09_couch_card_plan_completed() {
        couchRecord(demo(), session = 27, daysAgo = 3.0)
        capture("p09_couch_completed", startAt = listOf(Screen.Programs)) { scrollList("programs_list", "couch_start_button") }
    }

    @Test fun p10_delete_a_saved_session() {
        val ana = demo()
        val id = saveSession(ana, "tempo", 2)
        capture("p10_delete_saved", startAt = listOf(Screen.Programs)) {
            scrollList("programs_list", "delete_program_$id")
            click("delete_program_$id")
        }
        captureDialog("p10_delete_dialog")
    }

    // ---- The generator screen ----

    private fun generator(name: String, generatorId: String, mode: ExerciseMode? = null, from: String? = null, interact: () -> Unit = {}) {
        idleBelt()
        capture(name, startAt = listOf(Screen.Programs, Screen.Generator(generatorId, from))) {
            mode?.let { click("generator_mode_${it.name}") }
            interact()
        }
    }

    @Test fun g01_zone2_run() { demo(); generator("g01_zone2_run", "zone2", ExerciseMode.Run) }

    @Test fun g02_zone2_walk() { demo(); generator("g02_zone2_walk", "zone2", ExerciseMode.Walk) }

    @Test fun g03_zone2_estimated_values_and_notices() {
        // A fresh install: the generic user has no tests, no resting heart rate and only a placeholder weight.
        generator("g03_zone2_estimated", "zone2", ExerciseMode.Run) { scrollTo("generator_notices") }
    }

    @Test fun g04_tempo_run() { demo(); generator("g04_tempo_run", "tempo", ExerciseMode.Run) }

    @Test fun g05_tempo_walk() { demo(); generator("g05_tempo_walk", "tempo", ExerciseMode.Walk) }

    @Test fun g06_norwegian_run() { demo(); generator("g06_norwegian_run", "norwegian-4x4", ExerciseMode.Run) }

    @Test fun g07_norwegian_walk() { demo(); generator("g07_norwegian_walk", "norwegian-4x4", ExerciseMode.Walk) }

    @Test fun g08_intervals_run() { demo(); generator("g08_intervals_run", "intervals-30-30", ExerciseMode.Run) }

    @Test fun g09_intervals_walk() { demo(); generator("g09_intervals_walk", "intervals-30-30", ExerciseMode.Walk) }

    @Test fun g10_zone2_block_list() { demo(); generator("g10_zone2_blocks", "zone2", ExerciseMode.Run) { scrollTo("plan_block_list") } }

    @Test fun g11_norwegian_block_list() { demo(); generator("g11_norwegian_blocks", "norwegian-4x4", ExerciseMode.Run) { scrollTo("plan_block_list") } }

    @Test fun g12_couch_first_session() { demo(); generator("g12_couch_first", CouchToFiveK.ID) }

    @Test fun g13_couch_block_list() { demo(); generator("g13_couch_blocks", CouchToFiveK.ID) { scrollTo("plan_block_list") } }

    @Test fun g14_couch_start_week_choice() {
        demo()
        generator("g14_couch_start_week", CouchToFiveK.ID) {
            click("generator_plan_choose")
            click("generator_week_4")
            scrollTo("generator_week_9")
        }
    }

    @Test fun g15_couch_recent_session_and_started_from_history() {
        couchRecord(demo(), session = 7, daysAgo = 0.4)
        generator("g15_couch_recent", CouchToFiveK.ID) { scrollTo("generator_notices") }
    }

    @Test fun g16_zone2_without_heart_rate() {
        demo()
        idleBelt(heartRate = null)
        generator("g16_zone2_no_hr", "zone2", ExerciseMode.Run) { scrollTo("generator_no_heart_rate") }
    }

    @Test fun g17_generator_cannot_start_and_walking_speed_error() {
        demo()
        app.controller.showReconnecting()
        capture("g17_generator_blocked", startAt = listOf(Screen.Programs, Screen.Generator("tempo"))) {
            rule.onNodeWithTag("generator_walking").performTextReplacement("9")
            scrollTo("generator_walking")
        }
    }

    @Test fun g18_regenerate_a_saved_session() {
        val ana = demo()
        val id = saveSession(ana, "tempo", 5, GeneratorForm(mode = ExerciseMode.Walk, durationMinutes = 55, walkingSpeed = "5.5"))
        generator("g18_regenerate", "tempo", from = id)
    }

    @Test fun g19_start_screen_of_a_saved_session() {
        val ana = demo()
        val id = saveSession(ana, "norwegian-4x4", 5)
        idleBelt()
        capture("g19_repeat_saved", startAt = listOf(Screen.Programs, Screen.ProgramStart(id)))
    }

    // ---- Running a generated session ----

    /** The treadmill reports every second, like the real one (the runner treats a silent source as "no heart rate"). */
    private fun report(seconds: Int, heartRate: Int?) {
        repeat(seconds) {
            app.controller.state.value = app.controller.state.value.copy(heartRateBpm = heartRate, updatedAtMillis = rig.clockMillis())
            rig.advance(1_000)
        }
    }

    private fun startGenerated(user: UserProfile, generatorId: String, form: GeneratorForm?) {
        idleBelt()
        val generated = GeneratedFixtures.generate(data, user, generatorId, form, app.date, now, name = programName(generatorId, form))
        val outcome = rig.startProgram(ProgramResolver.resolve(generated.program), user.id)
        check(outcome == StartOutcome.Started) { "$outcome" }
        app.controller.state.value = TreadmillState(
            status = BeltStatus.Running, speedKmh = 6.0, targetSpeedKmh = 6.0, inclinePercent = 1.0, heartRateBpm = 100,
            distanceMeters = 300, elapsedSeconds = 180, updatedAtMillis = rig.clockMillis(),
        )
    }

    @Test fun r01_norwegian_4x4_second_repetition() {
        val ana = demo()
        startGenerated(ana, "norwegian-4x4", GeneratorForm(mode = ExerciseMode.Run))
        // Warm-up (10 min), the first repetition (4 + 3 min) and a minute and a half of the second.
        report(10 * 60 + 7 * 60, 120)
        report(90, 158)
        capture("r01_4x4_repetition", startAt = listOf(Screen.Main))
    }

    @Test fun r02_couch_to_5k_session_running() {
        val ana = demo()
        startGenerated(ana, CouchToFiveK.ID, null)
        report(5 * 60 + 2 * 150 + 20, 140)
        capture("r02_couch_running", startAt = listOf(Screen.Main))
    }

    @Test fun r03_couch_to_5k_result_with_the_next_session() {
        val ana = demo()
        val workout = data.addWorkout(
            ana.id, com.treadz.console.userdata.WorkoutStats(durationSeconds = 1800, distanceMeters = 4000),
            GeneratedFixtures.addSessionSamples(noon), noon - 3_600_000L,
        )
        app.links.workoutId = workout
        startGenerated(ana, CouchToFiveK.ID, null)
        report(30 * 60 + 5, 128)
        rig.advance(5_000)
        capture("r03_couch_result", startAt = listOf(Screen.Main))
    }

    // ---- History by origin ----

    @Test fun h01_history_tags_by_generator() {
        val ana = demo()
        couchRecord(ana, session = 7, daysAgo = 1.0)
        GeneratedFixtures.addSession(
            data, ana.id,
            GeneratedFixtures.record(generatorId = "zone2", startedAtMillis = noon - 2 * day, inputs = mapOf("mode" to "run"), settledValue = 9.1),
        )
        capture("h01_history_tags", startAt = listOf(Screen.History))
    }

    @Test fun h02_workout_detail_with_the_session_record() {
        val ana = demo()
        val id = couchRecord(ana, session = 7, daysAgo = 1.0)
        capture("h02_detail_record", startAt = listOf(Screen.History, Screen.WorkoutDetail(id))) { scrollTo("session_record") }
    }

    // ---- The walking heart-rate test ----

    private fun balkeResult(user: UserProfile, id: String, daysAgo: Int, peak: Double? = 186.0, ramp: Int = 600, effort: EffortAssessment? = EffortAssessment.Maximal) =
        GeneratedFixtures.balke(id = id, startedAtMillis = noon - daysAgo * day, peakBpm = peak, rampSeconds = ramp, effort = effort).also { runBlocking { data.fitnessTests.save(user.id, it) } }

    private val balkeId = "0b6f8f9e-4d0c-4d43-9f43-2f0c6f1a7b21"

    @Test fun w01_tests_list_with_the_walk_test() {
        val ana = demo()
        idleBelt()
        balkeResult(ana, balkeId, 3)
        capture("w01_tests_list", startAt = listOf(Screen.Programs, Screen.FitnessTests))
    }

    @Test fun w02_walk_start_ready() {
        demo()
        idleBelt()
        capture("w02_walk_start", startAt = listOf(Screen.Programs, Screen.FitnessTests, Screen.FitnessStart(FitnessTestType.MaxHeartRateWalk)))
    }

    @Test fun w03_walk_start_without_heart_rate() {
        demo()
        idleBelt(heartRate = null)
        capture("w03_walk_start_no_hr", startAt = listOf(Screen.Programs, Screen.FitnessTests, Screen.FitnessStart(FitnessTestType.MaxHeartRateWalk))) {
            scrollTo("test_heart_rate_now")
        }
    }

    @Test fun w04_walk_start_with_the_strap_and_last_values() {
        val ana = demo()
        idleBelt()
        app.strapMonitor.showConnected(bpm = 76)
        runBlocking { data.fitnessTests.save(ana.id, GeneratedFixtures.balke(id = balkeId, startedAtMillis = noon - 12 * day, startSpeedKmh = 6.0, startInclinePercent = 7.0)) }
        capture("w04_walk_start_strap", startAt = listOf(Screen.Programs, Screen.FitnessTests, Screen.FitnessStart(FitnessTestType.MaxHeartRateWalk)))
    }

    private val locked = SessionControls(SessionKind.FitnessTest, speed = false, incline = false, pause = false, note = ControlsNote.TestLocked)

    private fun guide(name: String, test: FitnessTestProgress, source: HeartRateSourceKind? = HeartRateSourceKind.DirectStrap) {
        val ana = demo()
        val run = RunUiState(kind = SessionKind.FitnessTest, test = test, heartRateSource = source)
        val ui = MainUiState(
            connection = ConnectionState.Connected("AA", "REEBOK"),
            treadmill = TreadmillState(
                status = BeltStatus.Running, speedKmh = test.actualSpeedKmh, targetSpeedKmh = test.targetSpeedKmh,
                inclinePercent = test.actualInclinePercent, heartRateBpm = test.heartRateBpm, updatedAtMillis = 1_000,
            ),
            activeUser = ana, session = if (test.manualChangesAllowed) SessionControls(SessionKind.FitnessTest, pause = false) else locked,
        )
        host(name) { FitnessRunScreen(ui, run, ControlActions(), RunActions()) }
    }

    @Test fun w05_walk_warm_up_with_the_ramp_start_controls() = guide("w05_walk_warm_up", TrainingFixtures.walkWarmUp())

    @Test fun w06_walk_ramp_before_three_minutes() = guide("w06_walk_ramp_early", TrainingFixtures.walkRamp(rampSeconds = 95, hr = 149))

    @Test fun w07_walk_ramp_exhausted_available() = guide("w07_walk_ramp_late", TrainingFixtures.walkRamp(rampSeconds = 372, hr = 171))

    @Test fun w08_walk_ramp_at_the_top() = guide("w08_walk_ramp_top", TrainingFixtures.walkRamp(rampSeconds = 780, hr = 183, capReached = true))

    @Test fun w09_walk_cool_down() {
        val result = GeneratedFixtures.balke(startedAtMillis = 1_000_000)
        guide("w09_walk_cool_down", TrainingFixtures.coolDown(result))
    }

    @Test fun w10_walk_result_maximal_in_the_run_screen() {
        val result = GeneratedFixtures.balke(startedAtMillis = 1_000_000, peakBpm = 187.0)
        guide("w10_walk_result_maximal", TrainingFixtures.finished(result))
    }

    @Test fun w11_walk_result_not_maximal() {
        val ana = demo()
        val short = GeneratedFixtures.balke(
            id = balkeId, startedAtMillis = noon - day, peakBpm = 158.0, rampSeconds = 240, effort = EffortAssessment.NotMaximal,
            flags = listOf(FitnessTestFlag.ShortIncrementalPhase),
        )
        runBlocking { data.fitnessTests.save(ana.id, short) }
        capture("w11_walk_not_maximal", startAt = listOf(Screen.Programs, Screen.FitnessTests, Screen.FitnessResult(balkeId)))
    }

    @Test fun w12_walk_result_invalid() {
        val ana = demo()
        val invalid = GeneratedFixtures.balke(
            id = balkeId, startedAtMillis = noon - day, peakBpm = null, rampSeconds = 120, effort = null, valid = false,
            flags = listOf(FitnessTestFlag.ManualChange), endReason = FitnessEndReason.ManualChange,
        )
        runBlocking { data.fitnessTests.save(ana.id, invalid) }
        capture("w12_walk_invalid", startAt = listOf(Screen.Programs, Screen.FitnessTests, Screen.FitnessResult(balkeId)))
    }

    @Test fun w13_walk_result_asks_about_the_maximum_heart_rate() {
        demo()
        val beto = runBlocking { data.profiles.profiles.first().first { it.name == "Beto" } }
        runBlocking { data.profiles.setActiveUser(beto.id) }
        runBlocking {
            data.fitnessTests.save(
                beto.id,
                GeneratedFixtures.balke(id = balkeId, startedAtMillis = noon + 1_000, peakBpm = 193.0, ageYears = 42, hrMaxBpm = 184),
            )
        }
        capture("w13_walk_hrmax_question", startAt = listOf(Screen.Programs, Screen.FitnessTests, Screen.FitnessResult(balkeId)))
    }
}
