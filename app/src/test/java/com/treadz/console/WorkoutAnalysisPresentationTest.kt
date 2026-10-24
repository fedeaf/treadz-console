package com.treadz.console

import com.treadz.console.analysis.workout.AnalysisReason
import com.treadz.console.analysis.workout.RecoveryContext
import com.treadz.console.fakes.AnalysisFixtures
import com.treadz.console.fakes.FakeApp
import com.treadz.console.fakes.MainDispatcherRule
import com.treadz.console.fakes.PerformanceFixtures
import com.treadz.console.history.AnalysisNote
import com.treadz.console.history.WorkoutAnalysisPresenter
import com.treadz.console.history.WorkoutDetailViewModel
import com.treadz.console.userdata.export.WorkoutExporter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

/** What the workout summary shows for each section and each reason it can be missing. */
class WorkoutAnalysisPresentationTest {

    @get:Rule val mainDispatcher = MainDispatcherRule()

    private fun present(analysis: com.treadz.console.analysis.workout.WorkoutAnalysis) = checkNotNull(WorkoutAnalysisPresenter.present(analysis))

    @Test fun nothingIsShownWhileTheAnalysisIsNotComputed() {
        assertNull(WorkoutAnalysisPresenter.present(null))
    }

    @Test fun aCompleteAnalysisShowsEverySectionWithoutNotes() {
        val view = present(AnalysisFixtures.full())
        assertNotNull(view.energy)
        assertNotNull(view.zoneTime)
        assertNotNull(view.trimp)
        assertNotNull(view.efficiency)
        assertNotNull(view.fitnessIndex)
        assertNotNull(view.recovery)
        assertNotNull(view.alpha1)
        assertTrue(view.notes.isEmpty())
    }

    @Test fun aWorkoutWithNothingToAnalyseHidesTheWholeSection() {
        listOf(AnalysisReason.NoSamples, AnalysisReason.ImplausibleDuration, AnalysisReason.NoBeltMovement).forEach {
            assertNull("$it", WorkoutAnalysisPresenter.present(AnalysisFixtures.missing(it)))
        }
    }

    @Test fun noHeartRateIsOnePassiveNoteAndTheEnergyStays() {
        val view = present(AnalysisFixtures.missing(AnalysisReason.NoHeartRate).copy(energy = AnalysisFixtures.full().energy))
        assertNotNull(view.energy)
        assertEquals(setOf(AnalysisNote.NoHeartRate), view.notes)
        assertNull(view.trimp)
        assertNull(view.alpha1)
        assertNull(view.alpha1Note)
        assertEquals(listOf(AnalysisNote.NoHeartRate), view.heartRateNotes)
    }

    @Test fun lowCoverageOfAWholeSessionMetricIsANoteButOfALocalOneIsNot() {
        val whole = present(AnalysisFixtures.with(zoneTime = AnalysisReason.LowHeartRateCoverage, trimp = AnalysisReason.LowHeartRateCoverage, efficiency = AnalysisReason.LowHeartRateCoverage))
        assertEquals(setOf(AnalysisNote.LowHeartRateCoverage), whole.notes)
        val local = present(AnalysisFixtures.with(fitnessIndex = AnalysisReason.LowHeartRateCoverage, recovery = AnalysisReason.LowHeartRateCoverage))
        assertTrue(local.notes.isEmpty())
        assertNull(local.fitnessIndex)
        assertNull(local.recovery)
    }

    @Test fun anUnusableMaximumHeartRateIsANote() {
        val view = present(AnalysisFixtures.with(trimp = AnalysisReason.InvalidMaxHeartRate, fitnessIndex = AnalysisReason.InvalidMaxHeartRate))
        assertEquals(setOf(AnalysisNote.InvalidMaxHeartRate), view.notes)
        assertNull(view.trimp)
        assertNull(view.fitnessIndex)
    }

    @Test fun missingBeatToBeatDataIsANoteInPlaceOfAlpha1() {
        val noRr = present(AnalysisFixtures.with(alpha1 = AnalysisReason.NoRrIntervals))
        assertEquals(AnalysisNote.NoRrIntervals, noRr.alpha1Note)
        val noWindows = present(AnalysisFixtures.with(alpha1 = AnalysisReason.NoValidWindows))
        assertEquals(AnalysisNote.NoValidWindows, noWindows.alpha1Note)
        assertNull(noWindows.alpha1)
    }

    @Test fun reasonsThatMeanItDoesNotApplyAreHiddenWithoutANote() {
        val view = present(
            AnalysisFixtures.with(
                zoneTime = AnalysisReason.NoZones, fitnessIndex = AnalysisReason.NoSteadyStretch, recovery = AnalysisReason.NoLoadDrop,
            ),
        )
        assertTrue(view.notes.isEmpty())
        assertNull(view.zoneTime)
        assertNull(view.fitnessIndex)
        assertNull(view.recovery)
        listOf(AnalysisReason.NoRecoveryData, AnalysisReason.NoLoadDrop).forEach {
            val v = present(AnalysisFixtures.with(recovery = it))
            assertTrue(v.notes.isEmpty())
            assertNull(v.recovery)
        }
        // The rest of the sections is untouched.
        assertNotNull(view.energy)
        assertNotNull(view.trimp)
        assertNotNull(view.alpha1)
    }

    @Test fun theSignalQualityIsAlwaysCarried() {
        assertEquals(AnalysisFixtures.signal, present(AnalysisFixtures.with(trimp = AnalysisReason.NoHeartRate)).signal)
    }

    // ---- the view model

    private val app = FakeApp()
    private val data = app.userData
    private val exporter = WorkoutExporter("0.1.0", app.exportStorage, java.time.ZoneOffset.UTC)

    private fun detail(workoutId: Long) = WorkoutDetailViewModel(workoutId, data, exporter)

    private fun activeId() = runBlocking { data.profiles.activeUserId()!! }

    @Test fun theDetailShowsNothingUntilTheAnalysisIsComputedThenTheMetrics() {
        val id = PerformanceFixtures.addSession(data, activeId(), app.date, 3, PerformanceFixtures.Session(minutes = 30))
        val vm = detail(id)
        assertNull(vm.uiState.value.analysis)
        runBlocking { data.workoutAnalysisRecalculator.recalculate() }
        val view = checkNotNull(vm.uiState.value.analysis)
        assertNotNull(view.energy)
        assertNotNull(view.zoneTime)
        assertNotNull(view.trimp)
        // The strap's RR intervals give the session α1.
        assertNotNull(view.alpha1)
        assertTrue(view.signal.rrBeats > 0)
    }

    @Test fun aConsoleOnlyWorkoutHasTheMetricsButNotAlpha1AndSaysWhy() {
        val id = PerformanceFixtures.addSession(data, activeId(), app.date, 3, PerformanceFixtures.Session(minutes = 30, heart = PerformanceFixtures.Heart.Console))
        runBlocking { data.workoutAnalysisRecalculator.recalculate() }
        val view = checkNotNull(detail(id).uiState.value.analysis)
        assertNotNull(view.trimp)
        assertNull(view.alpha1)
        assertEquals(AnalysisNote.NoRrIntervals, view.alpha1Note)
    }

    @Test fun aWorkoutWithoutHeartRateKeepsTheEnergyAndSaysSo() {
        val id = PerformanceFixtures.addSession(data, activeId(), app.date, 3, PerformanceFixtures.Session(minutes = 30, heart = PerformanceFixtures.Heart.None))
        runBlocking { data.workoutAnalysisRecalculator.recalculate() }
        val view = checkNotNull(detail(id).uiState.value.analysis)
        assertNotNull(view.energy)
        assertEquals(listOf(AnalysisNote.NoHeartRate), view.heartRateNotes)
        assertNull(view.trimp)
        assertFalse(view.notes.contains(AnalysisNote.NoRrIntervals))
    }

    @Test fun aWorkoutThatEndsWithTheBeltStoppedShowsItsRecoveryWithThatContext() {
        val id = PerformanceFixtures.addSession(data, activeId(), app.date, 3, PerformanceFixtures.Session(minutes = 20))
        runBlocking { data.workoutAnalysisRecalculator.recalculate() }
        val recovery = checkNotNull(detail(id).uiState.value.analysis?.recovery)
        assertEquals(RecoveryContext.BeltStopped, recovery.context)
        assertNotNull(recovery.drop60Bpm)
    }

    @Test fun anUnknownWorkoutHasNoAnalysis() {
        assertNull(detail(9_999L).uiState.value.analysis)
        runBlocking { assertNull(data.workoutAnalysis.analysis(9_999L).first()) }
    }
}
