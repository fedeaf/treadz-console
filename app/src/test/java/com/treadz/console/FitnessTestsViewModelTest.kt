package com.treadz.console

import com.treadz.console.fakes.FakeApp
import com.treadz.console.fakes.MainDispatcherRule
import com.treadz.console.programs.fitness.FitnessEndReason
import com.treadz.console.programs.fitness.FitnessTestFlag
import com.treadz.console.programs.fitness.HeartRateMaxOrigin
import com.treadz.console.analysis.fitness.Sex
import com.treadz.console.programs.fitness.Validity
import com.treadz.console.training.FitnessResultViewModel
import com.treadz.console.training.FitnessResults
import com.treadz.console.training.FitnessTestsViewModel
import com.treadz.console.userdata.memory.DemoData
import com.treadz.console.userdata.memory.DemoPrograms
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import java.time.ZoneId

class FitnessTestsViewModelTest {

    @get:Rule val main = MainDispatcherRule()
    private val app = FakeApp()
    private val data = app.userData
    private val users by lazy { runBlocking { DemoData.populate(data, app.date) } }
    private val ana get() = users.first
    private val beto get() = users.second

    private fun millisOf(daysAgo: Long) = app.date.minusDays(daysAgo).atTime(18, 30).atZone(ZoneId.systemDefault()).toInstant().toEpochMilli()

    private fun secondMaxTest(userId: Long, peak: Int, id: String = "0b6f8f9e-4d0c-4d43-9f43-2f0c6f1a7b31", daysAgo: Long = 0) = runBlocking {
        data.fitnessTests.save(userId, DemoPrograms.maxTest(id = id, startedAtMillis = millisOf(daysAgo), masKmh = 13.0, peakBpm = peak, ageYears = 42, sex = Sex.Male, weightKg = 90.0))
    }

    // ---- The list ----

    @Test fun showsTheActiveUsersTestsNewestFirstWithTheirFitnessFigures() {
        ana
        val state = FitnessTestsViewModel(data).uiState.value
        assertEquals(ana.id, state.user?.id)
        assertEquals(listOf(DemoPrograms.MAX_TEST_ID, DemoPrograms.EBBELING_ID), state.results.map { it.id })
        val profile = checkNotNull(state.profile)
        assertNotNull(profile.vo2max)
        assertNotNull(profile.mas)
        assertEquals(183, profile.hrMax!!.measuredBpm)
        assertEquals(HeartRateMaxOrigin.Measured, state.heartRateMax!!.origin)
    }

    @Test fun anotherUserHasNoTests() {
        ana
        runBlocking { data.profiles.setActiveUser(beto.id) }
        val state = FitnessTestsViewModel(data).uiState.value
        assertTrue(state.results.isEmpty())
        assertNull(state.profile?.vo2max)
        assertEquals(HeartRateMaxOrigin.Manual, state.heartRateMax!!.origin) // Beto set 184 himself
    }

    @Test fun deletingAsksFirstAndTheFitnessFiguresFollow() {
        ana
        val vm = FitnessTestsViewModel(data)
        vm.requestDelete(DemoPrograms.MAX_TEST_ID)
        vm.cancelDelete()
        assertEquals(2, vm.uiState.value.results.size)

        vm.requestDelete(DemoPrograms.MAX_TEST_ID)
        vm.confirmDelete()
        assertEquals(listOf(DemoPrograms.EBBELING_ID), vm.uiState.value.results.map { it.id })
        // Without the maximal test there is no measured maximum any more: back to the estimate.
        assertEquals(HeartRateMaxOrigin.Estimated, vm.uiState.value.heartRateMax!!.origin)
        assertTrue(vm.uiState.value.profile!!.mas!!.estimated)
    }

    // ---- The maximum heart rate question ----

    @Test fun aNewTestThatMeasuresSomethingDifferentFromWhatTheUserSetRaisesTheQuestion() {
        ana
        runBlocking { data.profiles.setActiveUser(beto.id) }
        val vm = FitnessTestsViewModel(data)
        assertFalse(vm.uiState.value.heartRateMax!!.askUser)

        secondMaxTest(beto.id, peak = 193)
        val decision = vm.uiState.value.heartRateMax!!
        assertTrue(decision.askUser)
        assertEquals(184, decision.bpm) // still his own value until he answers
        assertEquals(193, decision.measured!!.measuredBpm)
        assertEquals(9, decision.differenceBpm)
    }

    @Test fun usingTheMeasuredValueReplacesTheManualOne() {
        ana
        runBlocking { data.profiles.setActiveUser(beto.id) }
        secondMaxTest(beto.id, peak = 193)
        val vm = FitnessTestsViewModel(data)

        vm.useMeasuredHeartRateMax()
        val decision = vm.uiState.value.heartRateMax!!
        assertEquals(HeartRateMaxOrigin.Measured, decision.origin)
        assertEquals(193, decision.bpm)
        assertFalse(decision.askUser)
        assertNull(runBlocking { data.profiles.profile(beto.id).first() }!!.maxHeartRateBpm)
    }

    @Test fun keepingMineEndsTheQuestionUntilANewerTestDisagrees() {
        ana
        runBlocking { data.profiles.setActiveUser(beto.id) }
        secondMaxTest(beto.id, peak = 193)
        val vm = FitnessTestsViewModel(data)

        vm.keepManualHeartRateMax()
        var decision = vm.uiState.value.heartRateMax!!
        assertEquals(184, decision.bpm)
        assertEquals(HeartRateMaxOrigin.Manual, decision.origin)
        assertFalse(decision.askUser)
        assertEquals(184, runBlocking { data.profiles.profile(beto.id).first() }!!.maxHeartRateBpm)
    }

    // ---- One result ----

    @Test fun aResultIsComparedWithThePreviousValidTestOfTheSameType() {
        ana
        val newer = "0b6f8f9e-4d0c-4d43-9f43-2f0c6f1a7b32"
        runBlocking {
            data.fitnessTests.save(ana.id, DemoPrograms.ebbeling(id = newer, startedAtMillis = millisOf(3), vo2max = 44.3, ageYears = 39, sex = Sex.Female))
        }
        val vm = FitnessResultViewModel(newer, data)
        val comparison = checkNotNull(vm.uiState.value.comparison)
        assertEquals(DemoPrograms.EBBELING_ID, comparison.previousResultId)
        assertEquals(38.5, comparison.previousVo2max, 0.0)
        assertEquals(5.8, comparison.vo2maxDelta, 0.001)
        assertFalse(comparison.withinMeasurementError)

        // The maximal test is another type: nothing to compare with.
        assertNull(FitnessResultViewModel(DemoPrograms.MAX_TEST_ID, data).uiState.value.comparison)
    }

    @Test fun anInvalidResultHasNoComparisonAndDoesNotCountAsAPreviousTest() {
        ana
        val invalid = DemoPrograms.ebbeling(id = "0b6f8f9e-4d0c-4d43-9f43-2f0c6f1a7b33", startedAtMillis = millisOf(3), ageYears = 39, sex = Sex.Female).copy(
            validity = Validity.Invalid, outputs = null, flags = listOf(FitnessTestFlag.StoppedByUser), endReason = FitnessEndReason.StoppedByUser,
        )
        runBlocking { data.fitnessTests.save(ana.id, invalid) }
        assertNull(FitnessResultViewModel(invalid.id, data).uiState.value.comparison)

        val later = DemoPrograms.ebbeling(id = "0b6f8f9e-4d0c-4d43-9f43-2f0c6f1a7b34", startedAtMillis = millisOf(1), vo2max = 39.0, ageYears = 39, sex = Sex.Female)
        runBlocking { data.fitnessTests.save(ana.id, later) }
        val comparison = checkNotNull(FitnessResultViewModel(later.id, data).uiState.value.comparison)
        assertEquals(DemoPrograms.EBBELING_ID, comparison.previousResultId) // not the invalid one
        assertTrue(comparison.withinMeasurementError)
    }

    @Test fun aMissingResultIsNotFoundAndADeletedOneDisappears() {
        ana
        assertTrue(FitnessResultViewModel("nope", data).uiState.value.notFound)

        val vm = FitnessResultViewModel(DemoPrograms.MAX_TEST_ID, data)
        assertNotNull(vm.uiState.value.record)
        vm.requestDelete()
        assertTrue(vm.uiState.value.confirmingDelete)
        vm.cancelDelete()
        vm.requestDelete()
        var deleted = false
        vm.confirmDelete { deleted = true }
        assertTrue(deleted)
        assertTrue(vm.uiState.value.notFound)
        assertNull(runBlocking { data.fitnessTests.get(DemoPrograms.MAX_TEST_ID) })
    }

    @Test fun theQuestionCanAlsoBeAnsweredFromTheResult() {
        ana
        runBlocking { data.profiles.setActiveUser(beto.id) }
        secondMaxTest(beto.id, peak = 193)
        val vm = FitnessResultViewModel("0b6f8f9e-4d0c-4d43-9f43-2f0c6f1a7b31", data)
        assertTrue(vm.uiState.value.heartRateMax!!.askUser)
        vm.useMeasuredHeartRateMax()
        assertEquals(193, vm.uiState.value.heartRateMax!!.bpm)
        assertFalse(vm.uiState.value.heartRateMax!!.askUser)
    }

    // ---- Pure helpers ----

    @Test fun theErrorMarginOfTheTestsIsAboutFiveMlKgMin() {
        assertEquals(5.0, FitnessResults.MEASUREMENT_ERROR_VO2MAX, 0.0)
    }
}
