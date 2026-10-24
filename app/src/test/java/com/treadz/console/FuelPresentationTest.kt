package com.treadz.console

import com.treadz.console.analysis.workout.AnalysisReason
import com.treadz.console.analysis.workout.Section
import com.treadz.console.analysis.workout.FuelZone
import com.treadz.console.fakes.AnalysisFixtures
import com.treadz.console.fakes.FakeAnalysisRepository
import com.treadz.console.fakes.FuelFixtures
import com.treadz.console.fuel.FuelFigures
import com.treadz.console.fuel.FuelPresenter
import com.treadz.console.training.FinishedWorkout
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** The arithmetic and the hiding rules of the fat/carbohydrate split, in the one place every screen takes them from. */
@OptIn(ExperimentalCoroutinesApi::class)
class FuelPresentationTest {

    @Test fun theFigureIsTheShareOfTheCaloriesOfTheSameScreen() {
        val figures = FuelPresenter.figures(316, 0.38)!!
        assertEquals(FuelFigures(fatKcal = 120, carbKcal = 196, fatPercent = 38, carbPercent = 62), figures)
        assertEquals(316, figures.totalKcal)
    }

    @Test fun roundingKeepsTheKcalAddingUpToTheCaloriesAndThePercentsTo100() {
        for (calories in 1..700) {
            for (step in 0..100) {
                val share = step / 100.0 + 0.0049
                val figures = FuelPresenter.figures(calories, share.coerceAtMost(1.0))!!
                assertEquals("kcal of $calories at $share", calories, figures.fatKcal + figures.carbKcal)
                assertEquals("percent at $share", 100, figures.fatPercent + figures.carbPercent)
                assertTrue(figures.fatKcal in 0..calories && figures.fatPercent in 0..100)
            }
        }
    }

    @Test fun halvesRoundUpAndTheCarbsTakeTheRest() {
        // 0.5 x 5 = 2.5 kcal rounds to 3; 12.5 % rounds to 13 %.
        assertEquals(FuelFigures(3, 2, 50, 50), FuelPresenter.figures(5, 0.5))
        assertEquals(FuelFigures(1, 7, 13, 87), FuelPresenter.figures(8, 0.125))
    }

    @Test fun aShareOutsideZeroToOneIsClamped() {
        assertEquals(FuelFigures(0, 100, 0, 100), FuelPresenter.figures(100, -0.2))
        assertEquals(FuelFigures(100, 0, 100, 0), FuelPresenter.figures(100, 1.7))
    }

    @Test fun withoutCaloriesOrShareThereIsNothingToShow() {
        assertNull(FuelPresenter.figures(null, 0.4))
        assertNull(FuelPresenter.figures(0, 0.4))
        assertNull(FuelPresenter.figures(-5, 0.4))
        assertNull(FuelPresenter.figures(300, null))
        assertNull(FuelPresenter.figures(300, Double.NaN))
    }

    // ---- live

    @Test fun liveShowsTheSplitOfTheLiveCaloriesWhenTheEngineSaysItCanBeShown() {
        val figures = FuelPresenter.live(316, FuelFixtures.snapshot(fatShare = 0.38))!!
        assertEquals(FuelFigures(120, 196, 38, 62, dimmed = false), figures)
    }

    @Test fun liveIsHiddenWhenItIsNotShowable() {
        assertNull(FuelPresenter.live(316, FuelFixtures.snapshot(showable = false)))
        assertNull(FuelPresenter.live(316, FuelFixtures.snapshot(fatShare = null, showable = true)))
        assertNull(FuelPresenter.live(316, null))
    }

    @Test fun liveIsHiddenWithoutCalories() {
        assertNull(FuelPresenter.live(null, FuelFixtures.snapshot()))
        assertNull(FuelPresenter.live(0, FuelFixtures.snapshot()))
    }

    @Test fun liveKeepsTheValuesButDimsThemWhileTheLatestHeartRateIsNotValid() {
        val valid = FuelPresenter.live(316, FuelFixtures.snapshot(currentlyValid = true))!!
        val dim = FuelPresenter.live(316, FuelFixtures.snapshot(currentlyValid = false))!!
        assertFalse(valid.dimmed)
        assertTrue(dim.dimmed)
        assertEquals(valid.copy(dimmed = true), dim)
    }

    // ---- stored

    @Test fun aFinishedWorkoutUsesItsStoredCaloriesNotTheKcalOfTheAnalysis() {
        // The analysis' own kcal (600) differ from the workout's calories (642): the screen shows the latter.
        val figures = FuelPresenter.finished(642, FuelFixtures.analysis(FuelFixtures.fuel(fatShare = 0.452, kcal = 600.0)))!!
        assertEquals(FuelFigures(290, 352, 45, 55), figures)
        assertEquals(642, figures.totalKcal)
    }

    @Test fun aFinishedWorkoutShowsNothingUntilItsAnalysisExistsOrWhenItHasNoSplit() {
        assertNull("not computed yet", FuelPresenter.finished(642, null))
        assertNull("section missing", FuelPresenter.finished(642, FuelFixtures.analysis(fuel = null)))
        assertNull("no calories", FuelPresenter.finished(0, FuelFixtures.analysis()))
        assertNull("no calories", FuelPresenter.finished(null, FuelFixtures.analysis()))
        assertNotNull(FuelPresenter.finished(642, FuelFixtures.analysis()))
    }

    @Test fun everyMissingReasonOfTheSectionHidesTheSplit() {
        listOf(AnalysisReason.NoWeight, AnalysisReason.NoMeasuredThresholds, AnalysisReason.NoReliableStrapHeartRate, AnalysisReason.NoHeartRate).forEach {
            val analysis = AnalysisFixtures.full().copy(fuel = Section.missing(it))
            assertNull("$it", FuelPresenter.finished(642, analysis))
            assertNull("$it", FuelPresenter.detail(642, analysis, 0L, 60_000L))
        }
    }

    // ---- the detail card

    private val first = 1_000_000L
    private val last = first + 3_600_000L

    @Test fun theDetailPutsTheMinutesOnTheTimeAxisOfTheOtherCharts() {
        val minutes = FuelFixtures.minutes(first + 5_000L, 3)
        val detail = FuelPresenter.detail(642, FuelFixtures.analysis(FuelFixtures.fuel(minutes = minutes)), first, last)!!
        assertEquals(listOf(5.0, 65.0, 125.0), detail.minutes.map { it.startSeconds })
        assertEquals(minutes.map { it.fatKcal }, detail.minutes.map { it.fatKcal })
        assertEquals(3_600.0, detail.durationSeconds, 0.0)
        assertEquals(FuelFigures(290, 352, 45, 55), detail.figures)
    }

    @Test fun theDetailLeavesTheMinutesOutWithoutSamples() {
        val detail = FuelPresenter.detail(642, FuelFixtures.analysis(FuelFixtures.fuel(minutes = FuelFixtures.minutes(first, 3))), null, null)!!
        assertTrue(detail.minutes.isEmpty())
        assertEquals(0.0, detail.durationSeconds, 0.0)
    }

    @Test fun theZonesAreTheOnesWithTimeInThemWithTheirShareOfFat() {
        val detail = FuelPresenter.detail(642, FuelFixtures.analysis(), first, last)!!
        assertEquals(listOf(2, 3, 4), detail.zones.map { it.zone })
        assertEquals(listOf(62, 48, 20), detail.zones.map { it.fatPercent })
        assertEquals(listOf(1_200, 1_500, 600), detail.zones.map { it.seconds })
    }

    @Test fun aZoneWithTimeButNoEnergyIsLeftOut() {
        val zones = listOf(FuelZone(2, 30, 0.0, 0.0), FuelZone(3, 60, 5.0, 5.0))
        val detail = FuelPresenter.detail(642, FuelFixtures.analysis(FuelFixtures.fuel(byZone = zones)), first, last)!!
        assertEquals(listOf(3), detail.zones.map { it.zone })
        assertEquals(50, detail.zones.single().fatPercent)
    }

    @Test fun theDetailIsHiddenWithoutSplitOrCalories() {
        assertNull(FuelPresenter.detail(642, null, first, last))
        assertNull(FuelPresenter.detail(642, FuelFixtures.analysis(fuel = null), first, last))
        assertNull(FuelPresenter.detail(0, FuelFixtures.analysis(), first, last))
    }

    // ---- the workout that has just ended

    private fun ended(id: Long = 7, calories: Int = 642, test: String? = null) = FinishedWorkout(id, 3_600, 9_000, test, calories)

    @Test fun theEndShowsNothingUntilTheAnalysisArrivesThenTheSplitOfTheStoredCalories() = runTest(UnconfinedTestDispatcher()) {
        val repository = FakeAnalysisRepository()
        val finished = MutableStateFlow<FinishedWorkout?>(null)
        val seen = mutableListOf<FuelFigures?>()
        backgroundScope.launch { FuelPresenter.finishedFlow(finished) { repository.analysis(it) }.collect { seen += it } }

        assertEquals(listOf<FuelFigures?>(null), seen)
        finished.value = ended()
        assertNull("analysis not ready", seen.last())
        repository.put(7, FuelFixtures.analysis())
        assertEquals(FuelFigures(290, 352, 45, 55), seen.last())
        finished.value = null
        assertNull(seen.last())
    }

    @Test fun theEndShowsNothingForAnotherWorkoutsAnalysisOrAFitnessTest() = runTest(UnconfinedTestDispatcher()) {
        val repository = FakeAnalysisRepository()
        repository.put(7, FuelFixtures.analysis())
        val finished = MutableStateFlow<FinishedWorkout?>(ended(id = 8))
        val seen = mutableListOf<FuelFigures?>()
        backgroundScope.launch { FuelPresenter.finishedFlow(finished) { repository.analysis(it) }.collect { seen += it } }
        assertNull("the analysis of workout 7 is not for workout 8", seen.last())

        finished.value = ended(id = 7, test = "test-1")
        assertNull("a fitness test has its own screens", seen.last())
        finished.value = ended(id = 7)
        assertNotNull(seen.last())
    }

    @Test fun theEndShowsNothingForAWorkoutWithoutCalories() = runTest(UnconfinedTestDispatcher()) {
        val repository = FakeAnalysisRepository()
        repository.put(7, FuelFixtures.analysis())
        val finished = MutableStateFlow<FinishedWorkout?>(ended(calories = 0))
        val seen = mutableListOf<FuelFigures?>()
        backgroundScope.launch { FuelPresenter.finishedFlow(finished) { repository.analysis(it) }.collect { seen += it } }
        assertNull(seen.last())
    }
}
