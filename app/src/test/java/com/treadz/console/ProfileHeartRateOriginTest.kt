package com.treadz.console

import com.treadz.console.fakes.FakeApp
import com.treadz.console.fakes.MainDispatcherRule
import com.treadz.console.fakes.createProfile
import com.treadz.console.programs.fitness.HeartRateMaxOrigin
import com.treadz.console.programs.fitness.HeartRateSourceKind
import com.treadz.console.userdata.HeartRatePeak
import com.treadz.console.userdata.ReferenceStatus
import com.treadz.console.userdata.RestingHeartRateInput
import com.treadz.console.userdata.RestingHeartRateSource
import com.treadz.console.userdata.WorkoutStats
import com.treadz.console.userdata.memory.DemoPrograms
import com.treadz.console.users.ProfileEditorViewModel
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Rule
import org.junit.Test

/** What the profile editor knows about where the heart-rate values come from, and the observed-peak offer in it. */
class ProfileHeartRateOriginTest {

    @get:Rule val main = MainDispatcherRule()

    private val app = FakeApp()
    private val data get() = app.userData
    private val ana = data.createProfile("Ana", age = 35)
    private val day = 86_400_000L

    private fun editor() = ProfileEditorViewModel(
        ana.id, data.profiles, app.controller, app::today,
        fitnessTests = data.fitnessTests, restingHeartRates = data.restingHeartRates, peakOffers = app.peakOffers,
    )

    private fun peak(bpm: Int) {
        val at = app.nowMillis() - day
        data.addWorkout(ana.id, WorkoutStats(durationSeconds = 1800, peakHeartRate = HeartRatePeak(bpm, HeartRateSourceKind.DirectStrap, at)), emptyList(), at - 1_800_000)
    }

    @Test fun theRestingReferenceIsInTheStateWithItsStatus() {
        val vm = editor()
        // The value typed when the profile was created is one manual measurement: provisional.
        val first = vm.uiState.value.restingReference
        assertEquals(ReferenceStatus.Provisional, first?.status)
        assertEquals(1, first?.measurementsCount)
        runBlocking {
            for (daysAgo in 1..2) data.restingHeartRates.add(ana.id, RestingHeartRateInput(app.nowMillis() - daysAgo * day, 58, RestingHeartRateSource.Strap))
        }
        val now = vm.uiState.value.restingReference
        assertEquals(ReferenceStatus.Established, now?.status)
        assertEquals(3, now?.measurementsCount)
        assertEquals(58, now?.bpm)
    }

    @Test fun theMaximumsOriginComesFromTheDecision() {
        val vm = editor()
        assertEquals(HeartRateMaxOrigin.Estimated, vm.uiState.value.fitness?.heartRateMax?.origin)
        runBlocking { data.fitnessTests.save(ana.id, DemoPrograms.maxTest(startedAtMillis = app.nowMillis() - 10 * day, peakBpm = 181, ageYears = 35)) }
        val decision = vm.uiState.value.fitness?.heartRateMax
        assertEquals(HeartRateMaxOrigin.Measured, decision?.origin)
        assertNotNull(decision?.measured?.testedAtMillis)
    }

    @Test fun typingADifferentRestingValueAndSavingAddsAManualMeasurement() {
        val vm = editor()
        val before = runBlocking { data.restingHeartRates.measurements(ana.id).first() }.size
        vm.setRestingHeartRate("55")
        vm.save { }
        val after = runBlocking { data.restingHeartRates.measurements(ana.id).first() }
        assertEquals(before + 1, after.size)
        assertEquals(RestingHeartRateSource.Manual, after.last().source)
        assertEquals(55, after.last().bpm)
    }

    @Test fun theOfferIsInTheStateAndAcceptingPutsThePeakInTheFieldAndTheProfile() {
        val estimated = runBlocking { data.fitnessTests.heartRateMax(ana.id).first()!!.bpm }
        peak(estimated + 12)
        val vm = editor()
        assertEquals(estimated + 12, vm.uiState.value.peakOffer?.peakBpm)
        vm.acceptPeakOffer()
        assertNull(vm.uiState.value.peakOffer)
        assertEquals((estimated + 12).toString(), vm.uiState.value.form.maxHeartRate)
        assertEquals(estimated + 12, runBlocking { data.profiles.profile(ana.id).first()!!.maxHeartRateBpm })
        // Saving the form afterwards keeps it (the field has it).
        vm.save { }
        assertEquals(estimated + 12, runBlocking { data.profiles.profile(ana.id).first()!!.maxHeartRateBpm })
    }

    @Test fun decliningTheOfferRemembersItAndLeavesTheMaximumAlone() {
        val estimated = runBlocking { data.fitnessTests.heartRateMax(ana.id).first()!!.bpm }
        peak(estimated + 12)
        val vm = editor()
        vm.declinePeakOffer()
        assertNull(vm.uiState.value.peakOffer)
        assertNotNull(app.settings.peakAnsweredAtMillis(ana.id))
        assertNull(runBlocking { data.profiles.profile(ana.id).first()!!.maxHeartRateBpm })
    }
}
