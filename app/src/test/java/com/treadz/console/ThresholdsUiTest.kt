package com.treadz.console

import androidx.activity.ComponentActivity
import androidx.compose.ui.test.assertTextContains
import androidx.compose.ui.test.assertTextEquals
import androidx.compose.ui.test.hasTestTag
import androidx.compose.ui.test.junit4.v2.createAndroidComposeRule
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.performClick
import com.treadz.console.analysis.thresholds.ThresholdQuality
import com.treadz.console.analysis.thresholds.ThresholdReason
import com.treadz.console.fakes.FakeApp
import com.treadz.console.fakes.GeneratedFixtures
import com.treadz.console.fakes.ThresholdFixtures
import com.treadz.console.nav.Screen
import com.treadz.console.ui.MainActions
import com.treadz.console.ui.PermissionsUi
import com.treadz.console.ui.TreadZRoot
import com.treadz.console.userdata.ProfileInput
import com.treadz.console.userdata.memory.DemoData
import com.treadz.console.userdata.memory.DemoPrograms
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import java.time.ZoneId

/** The VO2max of the walking test, thresholds, zones and the fitness level options through the real UI. */
@RunWith(RobolectricTestRunner::class)
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@Config(qualifiers = "en-w1280dp-h800dp-land-hdpi")
class ThresholdsUiTest {

    @get:Rule val rule = createAndroidComposeRule<ComponentActivity>()
    private val app = FakeApp()
    private val data get() = app.userData
    private val users by lazy { runBlocking { DemoData.populate(data, app.date) } }
    private val ana get() = users.first
    private val beto get() = users.second
    private val id = "0b6f8f9e-4d0c-4d43-9f43-2f0c6f1a7b51"
    private val at get() = app.date.minusDays(1).atTime(18, 30).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    private fun show(vararg then: Screen) {
        users
        val navigation = app.newNavigation()
        rule.setContent { TreadZRoot(app, app.viewModel, PermissionsUi(), MainActions(), navigation) }
        rule.waitForIdle()
        then.forEach(navigation::push)
        rule.waitForIdle()
    }

    private fun exists(tag: String) = rule.onAllNodes(hasTestTag(tag)).fetchSemanticsNodes().isNotEmpty()

    private fun text(tag: String) = rule.onNodeWithTag(tag)

    private fun saveWalk(thresholds: com.treadz.console.analysis.thresholds.ThresholdResult?, vo2max: Double? = 44.1) {
        runBlocking { data.fitnessTests.save(ana.id, GeneratedFixtures.balke(id = id, startedAtMillis = at, thresholds = thresholds, vo2max = vo2max)) }
    }

    // ---- The result of the walking test ----

    @Test fun theWalkingResultShowsVo2maxItsCategoryTheEstimatedMasAndTheThresholds() {
        saveWalk(ThresholdFixtures.found(ThresholdQuality.Medium))
        show(Screen.FitnessTests, Screen.FitnessResult(id))

        text("result_vo2max").assertTextContains("44.1")
        text("result_category").assertExists()
        text("result_mas").assertTextContains("≈ 12.6")
        text("result_hrmax").assertExists()
        text("result_effort_tag").assertExists()
        text("result_thresholds").assertExists()
        text("thresholds_quality").assertTextContains("Medium quality")
        text("threshold_vt1_value").assertTextContains("132 bpm")
        text("threshold_vt2_value").assertTextContains("149 bpm")
        text("threshold_vt1_detail").assertTextContains("62 % of VO2max", substring = true)
        text("threshold_vt1_detail").assertTextContains("61 % of heart-rate reserve", substring = true)
        assertFalse(exists("thresholds_low_note"))
        assertFalse(exists("thresholds_reason"))
    }

    @Test fun aLowerBoundVo2maxHasTheMarkAndAMissingVt2SaysSo() {
        runBlocking {
            data.fitnessTests.save(
                ana.id,
                GeneratedFixtures.balke(id = id, startedAtMillis = at, vo2maxIsLowerBound = true, thresholds = ThresholdFixtures.found(ThresholdQuality.High, vt2Bpm = null)),
            )
        }
        show(Screen.FitnessTests, Screen.FitnessResult(id))
        text("result_vo2max").assertTextContains("≥ 44.1")
        text("thresholds_vt2_missing").assertExists()
        assertFalse(exists("threshold_vt2_value"))
    }

    @Test fun withoutBeatToBeatDataThereIsOnlyAPassiveReason() {
        saveWalk(ThresholdFixtures.none(ThresholdReason.NoRrIntervals))
        show(Screen.FitnessTests, Screen.FitnessResult(id))
        text("thresholds_reason").assertTextContains("beat-to-beat", substring = true)
        assertFalse(exists("threshold_vt1_value"))
        assertFalse(exists("thresholds_quality"))
        // The VO2max does not depend on the thresholds.
        text("result_vo2max").assertTextContains("44.1")
    }

    private fun reasonIsShown(reason: ThresholdReason, words: String) {
        saveWalk(ThresholdFixtures.none(reason))
        show(Screen.FitnessTests, Screen.FitnessResult(id))
        text("thresholds_reason").assertTextContains(words, substring = true)
    }

    @Test fun noWorkoutDataHasItsOwnReason() = reasonIsShown(ThresholdReason.NoWorkoutSamples, "not available on this device")

    @Test fun tooFewCleanWindowsHaveTheirOwnReason() = reasonIsShown(ThresholdReason.NotEnoughValidWindows, "not enough clean heartbeat data")

    @Test fun aFlatPatternHasItsOwnReason() = reasonIsShown(ThresholdReason.NoAlpha1Decline, "did not change with the effort")

    @Test fun aFirstThresholdOutOfRangeHasItsOwnReason() = reasonIsShown(ThresholdReason.Vt1OutOfRange, "outside the range")

    @Test fun aLowQualityResultIsShownAsSuchAndSaysItDoesNotCount() {
        saveWalk(ThresholdFixtures.found(ThresholdQuality.Low))
        show(Screen.FitnessTests, Screen.FitnessResult(id))
        text("thresholds_quality").assertTextContains("Low quality")
        text("thresholds_low_note").assertTextContains("does not count", substring = true)
        text("threshold_vt1_value").assertTextContains("132 bpm")
    }

    @Test fun aWalkingResultStoredBeforeTheVo2maxKeepsTheHeartRateHeadline() {
        saveWalk(thresholds = null, vo2max = null)
        show(Screen.FitnessTests, Screen.FitnessResult(id))
        text("result_hrmax").assertExists()
        assertFalse(exists("result_vo2max"))
        assertFalse(exists("result_mas"))
        assertFalse(exists("result_thresholds"))
    }

    @Test fun theRunningResultMarksNoMasAsEstimatedAndShowsItsThresholds() {
        val run = DemoPrograms.maxTest(id = id, startedAtMillis = at, masKmh = 13.0, peakBpm = 186, ageYears = 39, sex = com.treadz.console.analysis.fitness.Sex.Female, weightKg = 65.0)
        runBlocking { data.fitnessTests.save(ana.id, run.copy(thresholds = ThresholdFixtures.found(ThresholdQuality.High, reservePercent = false))) }
        show(Screen.FitnessTests, Screen.FitnessResult(id))
        text("result_mas").assertTextEquals("13.0") // measured: no estimate mark
        text("thresholds_quality").assertTextContains("High quality")
        // Without a resting heart rate the threshold has no share of the reserve.
        text("threshold_vt1_detail").assertTextEquals("5.5 km/h, 9 % incline · VO2 27.6 ml/kg/min · 62 % of VO2max")
    }

    @Test fun theListShowsTheThresholdsOfAResult() {
        saveWalk(ThresholdFixtures.found(ThresholdQuality.Medium))
        show(Screen.FitnessTests)
        rule.onNodeWithTag("test_thresholds_$id", useUnmergedTree = true).assertTextContains("VT1 132 · VT2 149 bpm")
    }

    // ---- The profile ----

    @Test fun theProfileShowsTheZonesWithTheirSourceAndTheThresholdsWithTheirQuality() {
        saveWalk(ThresholdFixtures.found(ThresholdQuality.High))
        show(Screen.ProfileEditor(ana.id, 1))
        text("summary_vt1").assertTextContains("132 bpm")
        text("summary_vt1_quality").assertTextContains("High quality")
        text("summary_vt2").assertTextContains("149 bpm")
        text("summary_vt2_quality").assertTextContains("High quality")
        text("zones_source").assertTextContains("From your thresholds")
        text("zone_1_range").assertTextContains("Below 122 bpm")
        text("zone_2_range").assertTextContains("122–132 bpm")
        text("zone_3_range").assertTextContains("132–149 bpm")
        assertFalse(exists("zones_vt2_typical"))
    }

    @Test fun withoutThresholdsTheZonesComeFromTheTestedVo2max() {
        show(Screen.ProfileEditor(ana.id, 1))
        text("zones_source").assertTextContains("From your VO2max")
        assertFalse(exists("summary_vt1"))
        assertFalse(exists("summary_vo2max_discrepancy"))
    }

    @Test fun withoutTestsTheZonesComeFromTheFitnessLevel() {
        show(Screen.ProfileEditor(beto.id, 1))
        text("zones_source").assertTextContains("From your fitness level")
        text("zone_2_range").assertTextContains("bpm", substring = true)
    }

    @Test fun withoutTestsOrLevelTheZonesAreTheDefaultOnes() {
        users
        val base = runBlocking { data.profiles.profile(beto.id).first()!! }
        runBlocking {
            data.profiles.update(beto.id, ProfileInput(base.name, base.sex, base.heightCm, base.birthDate, base.restingHeartRateBpm, base.maxHeartRateBpm, null, base.goal))
        }
        show(Screen.ProfileEditor(beto.id, 1))
        text("zones_source").assertTextContains("Default zones", substring = true)
    }

    @Test fun twoTestsThatDisagreeLeaveAPassiveMarkNextToTheVo2max() {
        runBlocking {
            data.fitnessTests.save(ana.id, DemoPrograms.ebbeling(id = "0b6f8f9e-4d0c-4d43-9f43-2f0c6f1a7b61", startedAtMillis = at - 86_400_000L, vo2max = 28.0, ageYears = 39, sex = com.treadz.console.analysis.fitness.Sex.Female))
            data.fitnessTests.save(ana.id, DemoPrograms.maxTest(id = "0b6f8f9e-4d0c-4d43-9f43-2f0c6f1a7b62", startedAtMillis = at, masKmh = 16.0, peakBpm = 186, ageYears = 39, sex = com.treadz.console.analysis.fitness.Sex.Female))
        }
        show(Screen.ProfileEditor(ana.id, 1))
        text("summary_vo2max_discrepancy").assertTextContains("Tests differ")
    }

    @Test fun theFitnessLevelOptionsExplainThemselves() {
        show(Screen.ProfileEditor(ana.id, 1))
        text("fitness_Beginner").assertTextContains("Not very active", substring = true)
        text("fitness_Beginner").assertTextContains("No regular aerobic exercise", substring = true)
        text("fitness_Intermediate").assertTextContains("Active", substring = true)
        text("fitness_Intermediate").assertTextContains("Regular exercise, up to about 1 h a week", substring = true)
        text("fitness_Advanced").assertTextContains("Trained", substring = true)
        text("fitness_Advanced").assertTextContains("Aerobic exercise 1–3 h a week or more", substring = true)
        text("fitness_none").assertExists()
        assertTrue(exists("fitness_level_choice"))
        text("fitness_Advanced").performClick()
        rule.waitForIdle()
    }

    // ---- Generated programs ----

    @Test fun aGeneratedProgramSaysWhereItsZoneComesFrom() {
        show(Screen.Programs, Screen.Generator("zone2"))
        text("generated_zone_origin").assertTextContains("from your VO2max", substring = true)
    }

    @Test fun withMeasuredThresholdsItSaysSoWithTheirValues() {
        saveWalk(ThresholdFixtures.found(ThresholdQuality.High))
        show(Screen.Programs, Screen.Generator("zone2"))
        text("generated_zone_origin").assertTextContains("from your thresholds (VT1 132 bpm, VT2 149 bpm)", substring = true)
    }

    @Test fun aGeneratorThatDoesNotUseTheZoneModelSaysNothingAboutIt() {
        show(Screen.Programs, Screen.Generator("norwegian-4x4"))
        assertFalse(exists("generated_zone_origin"))
    }
}
