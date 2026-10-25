package com.treadz.console

import com.treadz.console.fakes.FakeApp
import com.treadz.console.fakes.createProfile
import com.treadz.console.fakes.profileInput
import com.treadz.console.heartrate.PeakOfferController
import com.treadz.console.home.HeartRateMaxKind
import com.treadz.console.home.HeartRateMaxViewModel
import com.treadz.console.home.HomeViewModel
import com.treadz.console.programs.fitness.HeartRateMaxOrigin
import com.treadz.console.programs.fitness.HeartRateSourceKind
import com.treadz.console.userdata.HeartRatePeak
import com.treadz.console.userdata.WorkoutStats
import com.treadz.console.userdata.memory.DemoPrograms
import com.treadz.console.fakes.MainDispatcherRule
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/**
 * The observed-peak offer ("your heart rate reached X, above your maximum Y: use X?") on the home and in the profile,
 * and the list of maximum heart rate measurements with the one in use marked.
 */
class PeakOfferTest {

    @get:Rule val main = MainDispatcherRule()

    private val app = FakeApp()
    private val data get() = app.userData
    private val ana = data.createProfile("Ana", age = 35).also { runBlocking { data.profiles.setActiveUser(it.id) } }
    /** The maximum in use before anything is answered (the estimate from the age). */
    private val estimated = runBlocking { data.fitnessTests.heartRateMax(ana.id).first()!!.bpm }
    private val day = 86_400_000L
    private val now = app.nowMillis()

    private fun workoutWithPeak(bpm: Int, atMillis: Long = now - day, source: HeartRateSourceKind? = HeartRateSourceKind.DirectStrap) {
        data.addWorkout(
            ana.id,
            WorkoutStats(durationSeconds = 1800, distanceMeters = 5000, peakHeartRate = HeartRatePeak(bpm, source, atMillis)),
            emptyList(), atMillis - 1_800_000,
        )
    }

    private fun home() = HomeViewModel(data, app.peakOffers)

    // ---- Home ----

    @Test fun aPeakWellAboveTheMaximumInUseIsOffered() {
        workoutWithPeak(estimated + 10)
        val offer = home().uiState.value.peakOffer
        assertNotNull(offer)
        assertEquals(estimated + 10, offer!!.peakBpm)
        assertEquals(estimated, offer.currentBpm)
    }

    @Test fun aPeakWithinThreeBeatsOrLowerIsNotOffered() {
        workoutWithPeak(estimated + 3)
        assertNull(home().uiState.value.peakOffer)
    }

    @Test fun noWorkoutsNoOffer() {
        assertNull(home().uiState.value.peakOffer)
    }

    @Test fun yesSavesThePeakAsTheManualMaximumAndTheOfferGoes() {
        workoutWithPeak(estimated + 10)
        val vm = home()
        val measurementsBefore = runBlocking { data.restingHeartRates.measurements(ana.id).first() }.size
        vm.acceptPeakOffer()
        val profile = runBlocking { data.profiles.profile(ana.id).first()!! }
        assertEquals(estimated + 10, profile.maxHeartRateBpm)
        assertNotNull("the profile update stamps its date", profile.maxHeartRateSetAtMillis)
        val decision = runBlocking { data.fitnessTests.heartRateMax(ana.id).first()!! }
        assertEquals(HeartRateMaxOrigin.Manual, decision.origin)
        assertEquals(estimated + 10, decision.bpm)
        assertNull(vm.uiState.value.peakOffer)
        // The resting heart rate history is untouched by the profile update.
        assertEquals(measurementsBefore, runBlocking { data.restingHeartRates.measurements(ana.id).first() }.size)
    }

    @Test fun yesKeepsTheAvatarOfTheProfile() {
        runBlocking { data.profiles.update(ana.id, profileInput("Ana").copy(avatarId = "woman")) }
        workoutWithPeak(estimated + 10)
        home().acceptPeakOffer()
        val profile = runBlocking { data.profiles.profile(ana.id).first()!! }
        assertEquals(estimated + 10, profile.maxHeartRateBpm)
        assertEquals("woman", profile.avatarId)
    }

    @Test fun noRemembersTheAnswerAndTheSamePeakIsNotOfferedAgain() {
        workoutWithPeak(estimated + 10)
        val vm = home()
        vm.declinePeakOffer()
        assertNull(vm.uiState.value.peakOffer)
        assertNotNull(app.settings.peakAnsweredAtMillis(ana.id))
        // A new screen (and a new controller over the same settings) still does not offer it.
        assertNull(HomeViewModel(data, PeakOfferController(data, app.settings, ::nowFixed)).uiState.value.peakOffer)
        // The max stays what it was.
        assertNull(runBlocking { data.profiles.profile(ana.id).first()!!.maxHeartRateBpm })
    }

    private fun nowFixed() = now

    @Test fun aNewerHigherPeakIsOfferedAfterANo() {
        workoutWithPeak(estimated + 10)
        val vm = home()
        vm.declinePeakOffer()
        workoutWithPeak(estimated + 14, atMillis = now + 6 * 3_600_000L)
        assertEquals(estimated + 14, vm.uiState.value.peakOffer?.peakBpm)
    }

    @Test fun aPeakOlderThanTheManualValueIsNotOffered() {
        workoutWithPeak(estimated + 10, atMillis = now - 20 * day)
        val profile = runBlocking { data.profiles.profile(ana.id).first()!! }
        runBlocking {
            data.profiles.update(
                ana.id,
                profileInput("Ana", age = 35).copy(restingHeartRateBpm = profile.restingHeartRateBpm, maxHeartRateBpm = estimated + 2),
            )
        }
        // The manual value was entered after that peak, knowing it.
        assertNull(home().uiState.value.peakOffer)
    }

    // ---- Maximum heart rate detail ----

    private fun detail() = HeartRateMaxViewModel(ana.id, data, app.peakOffers, app::today)

    @Test fun withNothingElseTheEstimateIsTheOneInUse() {
        val state = detail().uiState.value
        assertEquals(HeartRateMaxOrigin.Estimated, state.decision?.origin)
        val row = state.rows.single()
        assertEquals(HeartRateMaxKind.AgeEstimate, row.kind)
        assertTrue(row.inUse)
    }

    @Test fun theListShowsEveryMeasurementAndMarksTheOneInUse() {
        runBlocking {
            data.fitnessTests.save(ana.id, DemoPrograms.maxTest(startedAtMillis = now - 10 * day, peakBpm = 181, ageYears = 35))
        }
        workoutWithPeak(estimated + 12, source = HeartRateSourceKind.TreadmillConsole)
        val state = detail().uiState.value
        assertEquals(HeartRateMaxOrigin.Measured, state.decision?.origin)
        val kinds = state.rows.map { it.kind::class }
        assertEquals(listOf(HeartRateMaxKind.Test::class, HeartRateMaxKind.ObservedPeak::class, HeartRateMaxKind.AgeEstimate::class), kinds)
        val used = state.rows.filter { it.inUse }
        assertEquals(1, used.size)
        assertTrue(used.single().kind is HeartRateMaxKind.Test)
        val peak = state.rows.first { it.kind is HeartRateMaxKind.ObservedPeak }
        assertEquals(estimated + 12, peak.bpm)
        assertFalse(peak.inUse)
        // The offer beats the measured maximum in use, not the age estimate.
        assertEquals(state.decision?.bpm, state.offer?.currentBpm)
    }

    @Test fun aManualValueWinsAndShowsInTheList() {
        runBlocking {
            data.profiles.update(ana.id, profileInput("Ana", age = 35).copy(restingHeartRateBpm = 60, maxHeartRateBpm = 190))
        }
        val state = detail().uiState.value
        assertEquals(HeartRateMaxOrigin.Manual, state.decision?.origin)
        assertEquals(190, state.rows.first().bpm)
        assertEquals(HeartRateMaxKind.Manual, state.rows.first().kind)
        assertTrue(state.rows.first().inUse)
        assertFalse(state.rows.last().inUse)
    }

    @Test fun answeringFromTheDetailWorksLikeFromTheHome() {
        workoutWithPeak(estimated + 10)
        val vm = detail()
        assertNotNull(vm.uiState.value.offer)
        vm.acceptOffer()
        assertNull(vm.uiState.value.offer)
        assertEquals(estimated + 10, vm.uiState.value.decision?.bpm)
        assertEquals(HeartRateMaxKind.Manual, vm.uiState.value.rows.first().kind)
    }
}
