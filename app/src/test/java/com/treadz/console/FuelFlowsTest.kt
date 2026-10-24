package com.treadz.console

import com.treadz.console.fakes.FakeAnalysisRepository
import com.treadz.console.fakes.FakeApp
import com.treadz.console.fakes.FuelFixtures
import com.treadz.console.fakes.MainDispatcherRule
import com.treadz.console.fakes.TrainingRig
import com.treadz.console.fakes.UserDataWithAnalysis
import com.treadz.console.fuel.FuelFigures
import com.treadz.console.history.WorkoutDetailViewModel
import com.treadz.console.treadmill.BeltStatus
import com.treadz.console.treadmill.TreadmillState
import com.treadz.console.treadmill.control.Clock
import com.treadz.console.userdata.WorkoutEndReason
import com.treadz.console.userdata.WorkoutSample
import com.treadz.console.userdata.WorkoutStats
import com.treadz.console.userdata.workout.WorkoutObserverConfig
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** The fat/carbohydrate split reaching the screens: live (main screen), at the end of a workout, and in the history detail. */
class FuelFlowsTest {

    @get:Rule val main = MainDispatcherRule()

    private val rig = TrainingRig()
    private val app get() = rig.app
    private val repository = FakeAnalysisRepository()
    private val data get() = UserDataWithAnalysis(app.userData, repository)
    private val observer = app.userData.workoutObserver(
        app.controller,
        Clock { rig.scheduler.currentTime },
        WorkoutObserverConfig(flushIntervalMs = 20, minSampleSpacingMs = 0),
        onError = { throw it },
    )

    /** The main ViewModel wired as the activity wires it, with the fake analyses. */
    private val vm by lazy {
        MainViewModel(app.controller, app.recorder, app.settings, data, app.appLifecycle, app.sync, app.training, app.strap, Clock.System, app.liveCalories, app.liveFuel)
    }
    private val ui get() = vm.uiState.value

    init {
        // The workout's calories need a weight (the session takes the owner's latest one when it starts, at the first advance).
        runBlocking { app.userData.weights.add(app.userData.profiles.activeUserId()!!, app.date, 70.0) }
        observer.start(rig.scope)
        rig.advance(10)
    }

    @After fun stopObserver() = rig.scope.cancel()

    // ---- live

    @Test fun liveShowsTheSplitOfTheLiveCaloriesWhenTheEngineCanShowIt() {
        assertNull("nothing before a workout", ui.liveFuel)
        app.liveCaloriesFlow.value = 316
        app.liveFuelFlow.value = FuelFixtures.snapshot(fatShare = 0.38)
        assertEquals(FuelFigures(120, 196, 38, 62), ui.liveFuel)
        assertEquals(316, ui.liveCalories)
    }

    @Test fun liveFollowsTheCaloriesAndDimsWhileTheHeartRateIsNotValid() {
        app.liveCaloriesFlow.value = 316
        app.liveFuelFlow.value = FuelFixtures.snapshot(fatShare = 0.38)
        app.liveCaloriesFlow.value = 330
        assertEquals(FuelFigures(125, 205, 38, 62), ui.liveFuel)
        app.liveFuelFlow.value = FuelFixtures.snapshot(fatShare = 0.40, currentlyValid = false)
        assertEquals(FuelFigures(132, 198, 40, 60, dimmed = true), ui.liveFuel)
    }

    @Test fun liveShowsNothingWhenItIsNotShowableOrThereAreNoCalories() {
        app.liveCaloriesFlow.value = 316
        app.liveFuelFlow.value = FuelFixtures.snapshot(showable = false)
        assertNull(ui.liveFuel)
        app.liveFuelFlow.value = FuelFixtures.snapshot()
        assertNotNull(ui.liveFuel)
        app.liveCaloriesFlow.value = null
        assertNull(ui.liveFuel)
        app.liveCaloriesFlow.value = 0
        assertNull(ui.liveFuel)
        app.liveCaloriesFlow.value = 316
        app.liveFuelFlow.value = null
        assertNull(ui.liveFuel)
    }

    // ---- at the end

    private fun runFor(seconds: Int) {
        for (i in 1..seconds) {
            app.controller.state.value = TreadmillState(
                status = BeltStatus.Running, speedKmh = 6.0, targetSpeedKmh = 6.0, distanceMeters = i * 2, elapsedSeconds = i,
                updatedAtMillis = rig.scheduler.currentTime,
            )
            rig.advance(1_000)
        }
    }

    private fun stopBelt() {
        app.controller.state.value = TreadmillState(status = BeltStatus.Idle, updatedAtMillis = rig.scheduler.currentTime)
        rig.advance(1_000)
    }

    @Test fun theEndShowsNothingUntilTheDataLayerHasAnalysedTheWorkoutThenTheSplitOfItsCalories() {
        vm
        runFor(120)
        assertNull("not while it is recorded", ui.finishedFuel)
        stopBelt()
        val finished = checkNotNull(app.training.finishedWorkout.value)
        assertTrue("the workout has calories to split", finished.calories > 0)
        assertNull("the analysis is a few seconds behind", ui.finishedFuel)

        repository.put(finished.workoutId, FuelFixtures.analysis(FuelFixtures.fuel(fatShare = 0.45)))
        val figures = checkNotNull(ui.finishedFuel)
        assertEquals(finished.calories, figures.totalKcal)
        assertEquals(45, figures.fatPercent)

        app.training.keepFinishedWorkout()
        rig.advance(10)
        assertNull(ui.finishedFuel)
    }

    @Test fun theEndShowsNothingWhenTheAnalysisHasNoSplit() {
        vm
        runFor(120)
        stopBelt()
        val finished = checkNotNull(app.training.finishedWorkout.value)
        repository.put(finished.workoutId, FuelFixtures.analysis(fuel = null))
        assertNull(ui.finishedFuel)
    }

    // ---- the detail

    private val detailApp = FakeApp()

    private fun sample(i: Int) = WorkoutSample(
        seq = i, timestampMillis = 1_000_000L + 10_000L * i, elapsedSeconds = i * 10, speedKmh = 8.0, targetSpeedKmh = 8.0, inclinePercent = 1.0,
        distanceMeters = i * 22, calories = i, heartRateBpm = 130, beltStatus = BeltStatus.Running,
    )

    private fun addWorkout(calories: Int, userId: Long? = runBlocking { detailApp.userData.profiles.activeUserId() }): Long =
        detailApp.userData.addWorkout(
            userId,
            WorkoutStats(durationSeconds = 3_600, distanceMeters = 8_000, calories = calories, averageSpeedKmh = 8.0, maxSpeedKmh = 8.0),
            (0..360).map(::sample),
            1_000_000L,
            WorkoutEndReason.Stopped,
        )

    private fun detail(id: Long) = WorkoutDetailViewModel(id, UserDataWithAnalysis(detailApp.userData, repository), detailApp.exporter)

    @Test fun theDetailHasNoCardUntilTheAnalysisExists() {
        val id = addWorkout(642)
        val vm = detail(id)
        assertNull(vm.uiState.value.fuel)
        repository.put(id, FuelFixtures.analysis(FuelFixtures.fuel(fatShare = 0.452, minutes = FuelFixtures.minutes(1_000_000L, 60))))
        val fuel = checkNotNull(vm.uiState.value.fuel)
        assertEquals(FuelFigures(290, 352, 45, 55), fuel.figures)
        assertEquals(60, fuel.minutes.size)
        assertEquals(0.0, fuel.minutes.first().startSeconds, 0.0)
        assertEquals(3_600.0, fuel.durationSeconds, 0.0)
        assertEquals(listOf(2, 3, 4), fuel.zones.map { it.zone })
    }

    @Test fun theDetailCardIsHiddenWithoutASplitOrCalories() {
        val withoutSplit = addWorkout(642)
        repository.put(withoutSplit, FuelFixtures.analysis(fuel = null))
        assertNull(detail(withoutSplit).uiState.value.fuel)

        val withoutCalories = addWorkout(0)
        repository.put(withoutCalories, FuelFixtures.analysis())
        assertNull(detail(withoutCalories).uiState.value.fuel)
    }

    @Test fun theDetailAddsUpToTheCaloriesOfTheSummary() {
        val id = addWorkout(777)
        repository.put(id, FuelFixtures.analysis(FuelFixtures.fuel(fatShare = 0.333, kcal = 1_000.0)))
        val fuel = checkNotNull(detail(id).uiState.value.fuel)
        assertEquals(777, fuel.figures.totalKcal)
        assertEquals(100, fuel.figures.fatPercent + fuel.figures.carbPercent)
    }
}
