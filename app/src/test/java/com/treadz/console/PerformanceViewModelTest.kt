package com.treadz.console

import com.treadz.console.analysis.trends.DatedValue
import com.treadz.console.analysis.workout.RecoveryContext
import com.treadz.console.fakes.AnalysisFixtures
import com.treadz.console.fakes.FakeApp
import com.treadz.console.fakes.MainDispatcherRule
import com.treadz.console.fakes.PerformanceFixtures
import com.treadz.console.performance.PerformanceCard
import com.treadz.console.performance.PerformanceTrends
import com.treadz.console.performance.PerformanceTrendsCalculator
import com.treadz.console.performance.PerformanceViewModel
import com.treadz.console.performance.Windows
import com.treadz.console.analysis.trends.EpochDays
import com.treadz.console.analysis.trends.TrailingMean
import com.treadz.console.userdata.DatedWorkoutAnalysis
import com.treadz.console.userdata.PerformanceInputs
import com.treadz.console.userdata.memory.DemoData
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import kotlin.coroutines.CoroutineContext

class PerformanceViewModelTest {

    @get:Rule val mainDispatcher = MainDispatcherRule()

    private val app = FakeApp()
    private val data = app.userData
    private val users by lazy { runBlocking { DemoData.populate(data, app.date) } }
    private val ana get() = users.first
    private val beto get() = users.second

    private fun richAna() {
        PerformanceFixtures.addTests(data, ana.id, app.date)
        PerformanceFixtures.addTraining(data, ana.id, app.date)
        PerformanceFixtures.addResting(data, ana.id, app.date)
        recalculate()
    }

    private fun recalculate() = runBlocking { data.workoutAnalysisRecalculator.recalculate() }

    /** Counts the work handed to it and runs it inline. */
    private class CountingDispatcher : CoroutineDispatcher() {
        var dispatched = 0
        override fun dispatch(context: CoroutineContext, block: Runnable) {
            dispatched++
            block.run()
        }
    }

    private fun viewModel(dispatcher: CoroutineDispatcher = Dispatchers.Unconfined) = PerformanceViewModel(data, app::today, dispatcher)

    // ---- states

    @Test fun nothingIsShownBeforeTheFirstLoadAndNoTrendsWithoutAnActiveUser() {
        users
        runBlocking { data.profiles.setActiveUser(null) }
        val state = viewModel().uiState.value
        assertTrue(state.loaded)
        assertNull(state.user)
        assertNull(state.trends)
    }

    @Test fun aFreshInstallHasEveryCardEmpty() {
        val state = viewModel().uiState.value
        val trends = checkNotNull(state.trends)
        assertTrue(state.user!!.isGeneric)
        assertTrue(trends.tests.vo2max.isEmpty())
        assertTrue(trends.tests.thresholds.isEmpty())
        assertTrue(trends.fitnessIndex.points.isEmpty())
        assertTrue(trends.decoupling.points.isEmpty())
        assertFalse(trends.hasLoad)
        assertTrue(trends.loadDays.isEmpty())
        assertTrue(trends.recovery.values.all { it.isEmpty() })
        assertTrue(trends.restingMean.isEmpty())
        assertTrue(trends.absoluteVo2.isEmpty())
        assertEquals(0, trends.analysedWorkouts)
        // The zones exist anyway (the default ones): the thresholds card can still show them.
        assertNotNull(state.zones)
    }

    @Test fun fewAnalysedSessionsWithoutHeartRateLeaveTheLoadEmptyButCountTheWorkouts() {
        users
        recalculate()
        val trends = checkNotNull(viewModel().uiState.value.trends)
        // The demo workouts repeat one heart rate for the whole session: it is dropped as frozen.
        assertTrue(trends.analysedWorkouts > 0)
        assertEquals(0, trends.workoutsWithTrimp)
        assertFalse(trends.hasLoad)
        assertTrue(trends.fitnessIndex.points.isEmpty())
        // Ana's two demo tests show in the VO2max series.
        assertEquals(2, trends.tests.vo2max.size)
    }

    // ---- the trends are wired to the right inputs

    @Test fun theTestSeriesComeFromTheStoredTestsWithTheirBandAndLowerBound() {
        users
        richAna()
        val trends = checkNotNull(viewModel().uiState.value.trends)
        val vo2 = trends.tests.vo2max
        assertEquals(6, vo2.size) // 2 demo tests + 4 walking tests
        assertEquals(vo2.map { it.epochDay }, vo2.map { it.epochDay }.sorted())
        assertEquals(1, vo2.count { it.isLowerBound })
        assertTrue(vo2.all { it.low != null && it.high != null && it.low!! < it.value })
        assertEquals(4, trends.tests.thresholds.size)
        assertEquals(1, trends.tests.thresholds.count { it.quality == com.treadz.console.analysis.thresholds.ThresholdQuality.Low })
        assertNotNull(trends.vo2maxChange)
        assertNotNull(trends.vt1Change)
    }

    @Test fun theFitnessIndexAndTheDecouplingComeFromTheAnalysedSessions() {
        users
        richAna()
        val trends = checkNotNull(viewModel().uiState.value.trends)
        assertTrue(trends.fitnessIndex.points.size >= 8)
        // The older the session, the higher its heart rate for the same load: the index rises.
        assertTrue(trends.fitnessIndex.slopePer30Days!! > 0.0)
        assertTrue(trends.hasDecoupling)
        assertTrue(trends.decoupling.points.all { it.analysedSeconds >= 1_200 })
    }

    @Test fun theLoadHasTheWeeksTheDaysAndTheWorkoutsWithoutHeartRateFlagged() {
        users
        richAna()
        val trends = checkNotNull(viewModel().uiState.value.trends)
        assertTrue(trends.hasLoad)
        // The series starts at the first workout (66 days ago), at most the window back.
        assertTrue(trends.loadWeeks.size in 9..Windows.WEEKS)
        assertEquals(67, trends.loadDays.size)
        assertEquals(app.date.toEpochDay(), trends.loadDays.last().epochDay)
        assertTrue(trends.loadWeeks.sumOf { it.trimp } > 0.0)
        // Two sessions of the plan have no heart rate (and the demo ones are frozen readings): flagged, counted as 0.
        assertTrue(trends.workoutsWithoutTrimpShown >= 2)
        assertTrue(trends.loadWeeks.any { it.workoutsWithoutTrimp > 0 })
        assertTrue(trends.zoneWeeks.any { it.totalSeconds > 0 })
        // From the week of the first workout (ten weeks ago) up to the current one.
        assertTrue(trends.zoneWeeks.size in 9..Windows.WEEKS)
    }

    @Test fun theRecoveryKeepsTheTwoContextsApart() {
        users
        richAna()
        val recovery = checkNotNull(viewModel().uiState.value.trends).recovery
        assertTrue(recovery.getValue(RecoveryContext.BeltStopped).isNotEmpty())
        assertTrue(recovery.getValue(RecoveryContext.BeltMoving).isNotEmpty())
        // The sessions of each ending are different workouts, never mixed.
        val stopped = recovery.getValue(RecoveryContext.BeltStopped).map { it.epochDay }.toSet()
        val moving = recovery.getValue(RecoveryContext.BeltMoving).map { it.epochDay }.toSet()
        assertTrue(stopped.intersect(moving).isEmpty())
    }

    @Test fun restingHeartRateRmssdAndAbsoluteVo2UseTheirOwnInputs() {
        users
        richAna()
        val trends = checkNotNull(viewModel().uiState.value.trends)
        assertTrue(trends.restingMean.size >= 12)
        assertEquals(12, trends.rmssdMean.size)
        assertTrue(trends.restingMean.all { it.mean in 55.0..65.0 })
        assertTrue(trends.rmssdMean.all { it.mean in 30.0..60.0 })
        // One absolute VO2 per test with a VO2max, with the weight of the day (Ana's weights are in kg, not the placeholder).
        assertEquals(6, trends.absoluteVo2.size)
        assertTrue(trends.absoluteVo2.all { it.weightKg in 60.0..70.0 })
        assertTrue(trends.absoluteVo2.all { Math.abs(it.litresPerMinute - it.vo2maxMlKgMin * it.weightKg / 1000.0) < 1e-9 })
        assertTrue(trends.weights.isNotEmpty())
    }

    // ---- reactions

    @Test fun theActiveUserChangesTheTrends() {
        users
        richAna()
        val vm = viewModel()
        val first = checkNotNull(vm.uiState.value.trends)
        assertTrue(first.hasLoad)
        assertEquals("Ana", vm.uiState.value.user!!.name)
        runBlocking { data.profiles.setActiveUser(beto.id) }
        val second = vm.uiState.value
        assertEquals("Beto", second.user!!.name)
        assertFalse(checkNotNull(second.trends).hasLoad)
        assertTrue(second.trends!!.tests.vo2max.isEmpty())
        runBlocking { data.profiles.setActiveUser(ana.id) }
        assertTrue(checkNotNull(vm.uiState.value.trends).hasLoad)
    }

    @Test fun aNewAnalysisUpdatesTheScreen() {
        users
        recalculate()
        val vm = viewModel()
        val before = checkNotNull(vm.uiState.value.trends).analysedWorkouts
        PerformanceFixtures.addSession(data, ana.id, app.date, 2, PerformanceFixtures.Session(minutes = 30))
        recalculate()
        val after = checkNotNull(vm.uiState.value.trends)
        assertEquals(before + 1, after.analysedWorkouts)
        assertTrue(after.hasLoad)
    }

    @Test fun theTrendsAreComputedOnTheComputeDispatcher() {
        users
        val dispatcher = CountingDispatcher()
        val vm = viewModel(dispatcher)
        assertNotNull(vm.uiState.value.trends)
        assertTrue(dispatcher.dispatched > 0)
        val before = dispatcher.dispatched
        runBlocking { data.profiles.setActiveUser(beto.id) }
        assertTrue(dispatcher.dispatched > before)
    }

    @Test fun theChartsOfTheCardsOpenAndCloseIndependently() {
        val vm = viewModel()
        assertTrue(vm.uiState.value.expanded.isEmpty())
        vm.toggle(PerformanceCard.Load)
        vm.toggle(PerformanceCard.Vo2max)
        assertEquals(setOf(PerformanceCard.Load, PerformanceCard.Vo2max), vm.uiState.value.expanded)
        vm.toggle(PerformanceCard.Load)
        assertEquals(setOf(PerformanceCard.Vo2max), vm.uiState.value.expanded)
    }

    @Test fun todayIsTheOneOfTheInjectedClock() {
        val trends = checkNotNull(viewModel().uiState.value.trends)
        assertEquals(app.date.toEpochDay(), trends.todayEpochDay)
    }

    // ---- derived figures (pure)

    private fun dated(daysAgo: Int, analysis: com.treadz.console.analysis.workout.WorkoutAnalysis, today: Long = 20_000L) =
        DatedWorkoutAnalysis(daysAgo.toLong(), (today - daysAgo) * 86_400_000L, today - daysAgo, analysis)

    @Test fun theHeadlineLoadIsTheLastSevenDaysAndTheSevenBefore() {
        val today = 20_000L
        val analyses = listOf(
            dated(1, AnalysisFixtures.loadOnly(50.0)), dated(6, AnalysisFixtures.loadOnly(30.0)), // last 7 days (days today-6 .. today)
            dated(7, AnalysisFixtures.loadOnly(20.0)), dated(13, AnalysisFixtures.loadOnly(10.0)), // the 7 before
            dated(20, AnalysisFixtures.loadOnly(99.0)), dated(3, AnalysisFixtures.loadOnly(null)), // older; one without heart rate
        )
        val trends = PerformanceTrendsCalculator.compute(analyses, PerformanceInputs(), today)
        assertEquals(80.0, trends.trimpLast7Days, 1e-9)
        assertEquals(30.0, trends.trimpPrevious7Days, 1e-9)
        assertEquals(1, trends.workoutsWithoutTrimpLast7Days)
        assertEquals(6, trends.analysedWorkouts)
        assertEquals(5, trends.workoutsWithTrimp)
    }

    @Test fun theWeeklyBarsAreAWindowOfCompleteWeeks() {
        val today = 20_000L
        val trends = PerformanceTrendsCalculator.compute(listOf(dated(100, AnalysisFixtures.loadOnly(40.0)), dated(2, AnalysisFixtures.loadOnly(40.0))), PerformanceInputs(), today)
        assertEquals(Windows.WEEKS, trends.loadWeeks.size)
        assertEquals(EpochDays.weekStart(today) - 7L * (Windows.WEEKS - 1), trends.loadWeeks.first().weekStartEpochDay)
        assertEquals(7, trends.loadWeeks.first().daysInSeries)
        // The workout 100 days ago is outside the weeks shown but still feeds the fitness-fatigue model.
        assertTrue(trends.loadDays.last().fitness > 0.0)
    }

    @Test fun theChangeOfAMeanIsAgainstAtLeastAWeekEarlier() {
        val means = listOf(TrailingMean(100, 60.0, 3), TrailingMean(104, 59.0, 4), TrailingMean(108, 57.0, 4))
        assertEquals(-3.0, PerformanceTrends.weekChange(means)!!, 1e-9)
        assertNull(PerformanceTrends.weekChange(means.take(2)))
        assertNull(PerformanceTrends.weekChange(emptyList()))
    }

    @Test fun absoluteVo2NeedsATestAndAWeight() {
        val trends = PerformanceTrendsCalculator.compute(emptyList(), PerformanceInputs(weights = listOf(DatedValue(19_990, 66.0))), 20_000L)
        assertEquals(listOf(DatedValue(19_990, 66.0)), trends.weights)
        assertTrue(trends.absoluteVo2.isEmpty())
    }
}
