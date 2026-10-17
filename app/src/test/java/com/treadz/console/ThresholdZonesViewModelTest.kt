package com.treadz.console

import com.treadz.console.analysis.fitness.SelfRatedFitness
import com.treadz.console.analysis.thresholds.ThresholdQuality
import com.treadz.console.analysis.zones.FitnessLevel as ZoneLevel
import com.treadz.console.analysis.zones.ZoneSource
import com.treadz.console.fakes.FakeApp
import com.treadz.console.fakes.GeneratedFixtures
import com.treadz.console.fakes.MainDispatcherRule
import com.treadz.console.fakes.ThresholdFixtures
import com.treadz.console.training.FitnessResults
import com.treadz.console.training.FitnessTestsViewModel
import com.treadz.console.training.UserZones
import com.treadz.console.userdata.FitnessLevel
import com.treadz.console.userdata.ProfileInput
import com.treadz.console.userdata.memory.DemoData
import com.treadz.console.userdata.memory.DemoPrograms
import com.treadz.console.users.ProfileEditorViewModel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.time.ZoneId

/** The zones the screens show, the resting reference of a live test and the comparison of estimated MAS values. */
class ThresholdZonesViewModelTest {

    @get:Rule val main = MainDispatcherRule()
    private val app = FakeApp()
    private val data = app.userData
    private val users by lazy { runBlocking { DemoData.populate(data, app.date) } }
    private val ana get() = users.first
    private val beto get() = users.second

    private fun atDaysAgo(days: Long) = app.date.minusDays(days).atTime(18, 30).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    private fun walk(userId: Long, id: String, daysAgo: Long, quality: ThresholdQuality, vt1: Double = 132.4, vt2: Double? = 149.1) = runBlocking {
        data.fitnessTests.save(userId, GeneratedFixtures.balke(id = id, startedAtMillis = atDaysAgo(daysAgo), thresholds = ThresholdFixtures.found(quality, vt1, vt2)))
    }

    private fun tests() = FitnessTestsViewModel(data, app::today)

    // ---- Zones by source ----

    @Test fun theTestedVo2maxGivesTheZonesOfAUserWithTests() {
        ana
        val zones = checkNotNull(tests().uiState.value.zones)
        assertEquals(ZoneSource.Vo2maxClassification, zones.source)
        assertEquals(5, zones.zones.size)
        assertTrue(zones.usedReserve) // Ana's resting heart rate is known
    }

    @Test fun withoutTestsTheFitnessLevelGivesTheZones() {
        ana
        runBlocking { data.profiles.setActiveUser(beto.id) }
        val zones = checkNotNull(tests().uiState.value.zones)
        assertEquals(ZoneSource.SelfRatedFitness, zones.source)
        assertEquals(ZoneLevel.Low, zones.level) // Beto is "Not very active"
    }

    @Test fun withNeitherTheDefaultZonesAreUsed() {
        ana
        val base = runBlocking { data.profiles.profile(beto.id).first()!! }
        runBlocking {
            data.profiles.update(beto.id, ProfileInput(base.name, base.sex, base.heightCm, base.birthDate, base.restingHeartRateBpm, base.maxHeartRateBpm, null, base.goal))
            data.profiles.setActiveUser(beto.id)
        }
        val zones = checkNotNull(tests().uiState.value.zones)
        assertEquals(ZoneSource.Default, zones.source)
        assertEquals(ZoneLevel.Low, zones.level)
    }

    @Test fun measuredThresholdsGiveTheZonesAroundThemAndFollowTheProfile() {
        ana
        walk(ana.id, "0b6f8f9e-4d0c-4d43-9f43-2f0c6f1a7b71", 1, ThresholdQuality.High)
        val state = tests().uiState.value
        val zones = checkNotNull(state.zones)
        assertEquals(ZoneSource.MeasuredThresholds, zones.source)
        val profileVt1 = checkNotNull(state.profile?.thresholds?.vt1).heartRateBpm
        assertEquals(profileVt1, zones.vt1Bpm)
        assertEquals(profileVt1 - 10, zones.zone(2).lowBpm)
        assertFalse(zones.vt2FromTable)
    }

    @Test fun aLowQualityResultDoesNotCountForTheProfileOrTheZones() {
        ana
        runBlocking { data.profiles.setActiveUser(beto.id) }
        walk(beto.id, "0b6f8f9e-4d0c-4d43-9f43-2f0c6f1a7b72", 1, ThresholdQuality.Low)
        val state = tests().uiState.value
        assertNull(state.profile?.thresholds?.vt1)
        assertNotEquals(ZoneSource.MeasuredThresholds, state.zones?.source) // the VO2max of the same test still counts
    }

    @Test fun aMissingVt2IsTakenFromTheTableAndSaysSo() {
        ana
        walk(ana.id, "0b6f8f9e-4d0c-4d43-9f43-2f0c6f1a7b73", 1, ThresholdQuality.High, vt2 = null)
        val zones = checkNotNull(tests().uiState.value.zones)
        assertEquals(ZoneSource.MeasuredThresholds, zones.source)
        assertTrue(zones.vt2FromTable)
    }

    @Test fun theMeasuredMaximumAndTheRestingReferenceFeedTheZones() {
        ana
        val state = tests().uiState.value
        val hrMax = checkNotNull(state.heartRateMax)
        val resting = runBlocking { data.restingHeartRates.reference(ana.id).first() }?.bpm
        val expected = UserZones.compute(checkNotNull(state.user), state.profile, hrMax, resting, app.date)
        assertEquals(expected, state.zones)
        assertEquals(hrMax.bpm, state.zones?.zones?.last()?.highBpm) // Z5 ends at the maximum
    }

    @Test fun theLevelMappingKeepsTheStoredValues() {
        assertEquals(SelfRatedFitness.Beginner, UserZones.selfRatedOf(FitnessLevel.Beginner))
        assertEquals(SelfRatedFitness.Intermediate, UserZones.selfRatedOf(FitnessLevel.Intermediate))
        assertEquals(SelfRatedFitness.Advanced, UserZones.selfRatedOf(FitnessLevel.Advanced))
        assertNull(UserZones.selfRatedOf(null))
        assertEquals(listOf("Beginner", "Intermediate", "Advanced"), FitnessLevel.entries.map { it.name })
    }

    @Test fun theProfileEditorHasTheSameZones() {
        ana
        walk(ana.id, "0b6f8f9e-4d0c-4d43-9f43-2f0c6f1a7b74", 1, ThresholdQuality.Medium)
        val editor = ProfileEditorViewModel(
            ana.id, data.profiles, app.controller, app::today,
            fitnessTests = data.fitnessTests, restingHeartRates = data.restingHeartRates,
        )
        val zones = checkNotNull(editor.uiState.value.fitness?.zones)
        assertEquals(zones, tests().uiState.value.zones)
        assertEquals(ZoneSource.MeasuredThresholds, zones.source)
        // The discrepancy flag comes with the profile.
        assertFalse(checkNotNull(editor.uiState.value.fitness?.profile?.vo2max).discrepancy)
    }

    @Test fun twoTestsThatDisagreeSetTheDiscrepancyFlagOfTheProfile() {
        ana
        runBlocking {
            data.fitnessTests.save(ana.id, DemoPrograms.ebbeling(id = "0b6f8f9e-4d0c-4d43-9f43-2f0c6f1a7b75", startedAtMillis = atDaysAgo(3), vo2max = 28.0, ageYears = 39, sex = com.treadz.console.analysis.fitness.Sex.Female))
            data.fitnessTests.save(ana.id, DemoPrograms.maxTest(id = "0b6f8f9e-4d0c-4d43-9f43-2f0c6f1a7b76", startedAtMillis = atDaysAgo(1), masKmh = 16.0, peakBpm = 186, ageYears = 39, sex = com.treadz.console.analysis.fitness.Sex.Female))
        }
        val vo2 = checkNotNull(tests().uiState.value.profile?.vo2max)
        assertTrue(vo2.discrepancy)
        assertTrue(vo2.sources.any { it.discrepant })
    }

    // ---- Estimated MAS in the comparison ----

    @Test fun theComparisonOfWalkingTestsMarksTheMasAsEstimated() {
        ana
        val first = GeneratedFixtures.balke(id = "0b6f8f9e-4d0c-4d43-9f43-2f0c6f1a7b77", startedAtMillis = atDaysAgo(20), vo2max = 40.0)
        val second = GeneratedFixtures.balke(id = "0b6f8f9e-4d0c-4d43-9f43-2f0c6f1a7b78", startedAtMillis = atDaysAgo(1), vo2max = 44.1)
        val comparison = checkNotNull(FitnessResults.compare(second, listOf(first, second)))
        assertTrue(comparison.masIsEstimated)
        assertEquals(4.1 / 3.5, checkNotNull(comparison.masDelta), 0.02)
    }

    @Test fun theComparisonOfRunningTestsKeepsTheMasAsMeasured() {
        val a = DemoPrograms.maxTest(id = "0b6f8f9e-4d0c-4d43-9f43-2f0c6f1a7b79", startedAtMillis = atDaysAgo(20), masKmh = 12.0)
        val b = DemoPrograms.maxTest(id = "0b6f8f9e-4d0c-4d43-9f43-2f0c6f1a7b7a", startedAtMillis = atDaysAgo(1), masKmh = 13.0)
        val comparison = checkNotNull(FitnessResults.compare(b, listOf(a, b)))
        assertFalse(comparison.masIsEstimated)
        assertEquals(1.0, checkNotNull(comparison.masDelta), 1e-9)
    }
}
