package com.treadz.console.screenshots

import androidx.compose.ui.test.junit4.v2.createComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performScrollTo
import com.github.takahirom.roborazzi.captureRoboImage
import com.treadz.console.analysis.fitness.Sex
import com.treadz.console.analysis.thresholds.ThresholdQuality
import com.treadz.console.analysis.thresholds.ThresholdReason
import com.treadz.console.analysis.thresholds.ThresholdResult
import com.treadz.console.fakes.FakeApp
import com.treadz.console.fakes.GeneratedFixtures
import com.treadz.console.fakes.ThresholdFixtures
import com.treadz.console.nav.Screen
import com.treadz.console.programs.fitness.EffortAssessment
import com.treadz.console.ui.MainActions
import com.treadz.console.ui.PermissionsUi
import com.treadz.console.ui.TreadZRoot
import com.treadz.console.userdata.ProfileInput
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
 * Thresholds, zones and the fitness level selector (phase F1) on synthetic data. Subclasses set the language and screen
 * size with `@Config(qualifiers = ...)`. Images are only written with `./gradlew :app:recordRoborazziDebug`.
 */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
abstract class ThresholdsScreenshotScenarios(private val prefix: String) {

    @get:Rule val rule = createComposeRule()

    private val app = FakeApp()
    private val data get() = app.userData
    private val users by lazy { runBlocking { DemoData.populate(data, app.date) } }
    private val ana get() = users.first
    private val beto get() = users.second
    private val id = "0b6f8f9e-4d0c-4d43-9f43-2f0c6f1a7b51"
    private val day = 86_400_000L

    private fun atDaysAgo(days: Long) = app.date.minusDays(days).atTime(18, 30).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    private fun capture(name: String, vararg screens: Screen, scrollTo: String? = null, light: Boolean = false) {
        users
        if (light) app.viewModel.setDarkTheme(false)
        val navigation = app.newNavigation()
        rule.setContent { TreadZRoot(dependencies = app, viewModel = app.viewModel, permissions = PermissionsUi(), actions = MainActions(), navigation = navigation) }
        rule.waitForIdle()
        screens.forEach(navigation::push)
        rule.waitForIdle()
        if (scrollTo != null) {
            rule.onNodeWithTag(scrollTo).performScrollTo()
            rule.waitForIdle()
        }
        rule.onRoot().captureRoboImage("build/outputs/roborazzi/${prefix}_$name.png")
    }

    private fun saveWalk(thresholds: ThresholdResult?, user: Long = ana.id, vo2max: Double? = 44.1, peak: Double = 186.0) = runBlocking {
        data.fitnessTests.save(user, GeneratedFixtures.balke(id = id, startedAtMillis = atDaysAgo(1), thresholds = thresholds, vo2max = vo2max, peakBpm = peak))
    }

    private fun result(vararg extra: Screen) = arrayOf(Screen.FitnessTests, Screen.FitnessResult(id), *extra)

    // ---- The result of the maximal walking test ----

    @Test fun t01_walk_result_with_thresholds() {
        saveWalk(ThresholdFixtures.found(ThresholdQuality.Medium))
        capture("t01_walk_thresholds", *result(), scrollTo = "result_thresholds")
    }

    @Test fun t02_walk_result_top_with_vo2max_and_estimated_mas() {
        saveWalk(ThresholdFixtures.found(ThresholdQuality.High))
        capture("t02_walk_top", *result())
    }

    @Test fun t03_walk_result_without_beat_to_beat_data() {
        saveWalk(ThresholdFixtures.none(ThresholdReason.NoRrIntervals))
        capture("t03_walk_no_rr", *result(), scrollTo = "result_thresholds")
    }

    @Test fun t04_walk_result_low_quality() {
        saveWalk(ThresholdFixtures.found(ThresholdQuality.Low, vt2Bpm = null))
        capture("t04_walk_low_quality", *result(), scrollTo = "result_thresholds")
    }

    @Test fun t05_walk_result_lower_bound_and_not_maximal() {
        runBlocking {
            data.fitnessTests.save(
                ana.id,
                GeneratedFixtures.balke(
                    id = id, startedAtMillis = atDaysAgo(1), peakBpm = 158.0, rampSeconds = 240, effort = EffortAssessment.NotMaximal,
                    flags = listOf(com.treadz.console.programs.fitness.FitnessTestFlag.ShortIncrementalPhase), vo2max = 36.2, vo2maxIsLowerBound = true,
                    thresholds = ThresholdFixtures.none(ThresholdReason.NotEnoughValidWindows),
                ),
            )
        }
        capture("t05_walk_lower_bound", *result())
    }

    @Test fun t06_running_result_with_thresholds() {
        val run = DemoPrograms.maxTest(id = id, startedAtMillis = atDaysAgo(1), masKmh = 13.0, peakBpm = 186, ageYears = 39, sex = Sex.Female, weightKg = 65.0)
        runBlocking { data.fitnessTests.save(ana.id, run.copy(thresholds = ThresholdFixtures.found(ThresholdQuality.High))) }
        capture("t06_run_thresholds", *result(), scrollTo = "result_thresholds")
    }

    @Test fun t07_walk_result_with_thresholds_light() {
        saveWalk(ThresholdFixtures.found(ThresholdQuality.High))
        capture("t07_walk_light", *result(), scrollTo = "result_thresholds", light = true)
    }

    @Test fun t08_tests_list_with_the_walking_result() {
        saveWalk(ThresholdFixtures.found(ThresholdQuality.Medium))
        capture("t08_tests_list", Screen.FitnessTests)
    }

    // ---- Zones in the profile, one per source ----

    @Test fun z01_zones_from_thresholds() {
        saveWalk(ThresholdFixtures.found(ThresholdQuality.High))
        capture("z01_zones_thresholds", Screen.ProfileEditor(ana.id, 1), scrollTo = "profile_zones")
    }

    @Test fun z02_zones_from_the_tested_vo2max() {
        capture("z02_zones_vo2max", Screen.ProfileEditor(ana.id, 1), scrollTo = "profile_zones")
    }

    @Test fun z03_zones_from_the_fitness_level() {
        capture("z03_zones_level", Screen.ProfileEditor(beto.id, 1), scrollTo = "profile_zones")
    }

    @Test fun z04_default_zones() {
        users
        val base = runBlocking { data.profiles.profile(beto.id).first()!! }
        runBlocking {
            data.profiles.update(beto.id, ProfileInput(base.name, base.sex, base.heightCm, base.birthDate, base.restingHeartRateBpm, base.maxHeartRateBpm, null, base.goal))
        }
        capture("z04_zones_default", Screen.ProfileEditor(beto.id, 1), scrollTo = "profile_zones")
    }

    @Test fun z05_zones_with_a_typical_vt2() {
        saveWalk(ThresholdFixtures.found(ThresholdQuality.Medium, vt2Bpm = null))
        capture("z05_zones_typical_vt2", Screen.ProfileEditor(ana.id, 1), scrollTo = "profile_zones")
    }

    @Test fun z06_profile_summary_with_the_discrepancy_mark() {
        runBlocking {
            data.fitnessTests.save(ana.id, DemoPrograms.ebbeling(id = "0b6f8f9e-4d0c-4d43-9f43-2f0c6f1a7b61", startedAtMillis = atDaysAgo(3), vo2max = 28.0, ageYears = 39, sex = Sex.Female))
            data.fitnessTests.save(ana.id, DemoPrograms.maxTest(id = "0b6f8f9e-4d0c-4d43-9f43-2f0c6f1a7b62", startedAtMillis = atDaysAgo(1), masKmh = 16.0, peakBpm = 186, ageYears = 39, sex = Sex.Female))
        }
        capture("z06_discrepancy", Screen.FitnessTests)
    }

    @Test fun z07_tests_screen_summary_with_thresholds_and_zones() {
        saveWalk(ThresholdFixtures.found(ThresholdQuality.High))
        capture("z07_tests_summary", Screen.FitnessTests)
    }

    @Test fun z08_zones_light() {
        saveWalk(ThresholdFixtures.found(ThresholdQuality.High))
        capture("z08_zones_light", Screen.ProfileEditor(ana.id, 1), scrollTo = "profile_zones", light = true)
    }

    // ---- The fitness level selector ----

    @Test fun f01_fitness_level_selector() {
        capture("f01_level_selector", Screen.ProfileEditor(ana.id, 1), scrollTo = "fitness_Advanced")
    }

    @Test fun f02_fitness_level_selector_light() {
        capture("f02_level_selector_light", Screen.ProfileEditor(beto.id, 1), scrollTo = "fitness_Advanced", light = true)
    }

    // ---- Generated programs ----

    @Test fun g01_generated_zone_from_thresholds() {
        saveWalk(ThresholdFixtures.found(ThresholdQuality.High))
        capture("g01_generator_thresholds", Screen.Programs, Screen.Generator("zone2"), scrollTo = "generated_zone_origin")
    }

    @Test fun g02_generated_zone_from_the_level() {
        runBlocking { data.profiles.setActiveUser(beto.id) }
        capture("g02_generator_level", Screen.Programs, Screen.Generator("tempo"), scrollTo = "generated_zone_origin")
    }
}
