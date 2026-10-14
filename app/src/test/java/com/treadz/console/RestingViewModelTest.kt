package com.treadz.console

import com.treadz.console.devices.LiveHeartRate
import com.treadz.console.fakes.FakeApp
import com.treadz.console.fakes.FakeHeartRateSource
import com.treadz.console.hrm.resting.RestingInvalidReason
import com.treadz.console.programs.fitness.HeartRateSourceKind
import com.treadz.console.programs.heartrate.restingHeartRateSession
import com.treadz.console.resting.RestingPhase
import com.treadz.console.resting.RestingViewModel
import com.treadz.console.userdata.InputField
import com.treadz.console.userdata.RestingHeartRateInput
import com.treadz.console.userdata.RestingHeartRateSource
import com.treadz.console.userdata.ReferenceStatus
import com.treadz.console.userdata.ValidationCode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The resting heart rate flow in virtual time: no treadmill involved, a valid measurement is saved (with its source), an
 * invalid one never is, a clearly higher value raises the non-blocking warning, and the history can be edited.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RestingViewModelTest {

    private val scheduler = TestCoroutineScheduler()
    private val app = FakeApp()
    private val source = FakeHeartRateSource()
    private val live = MutableStateFlow(LiveHeartRate(null, null))
    private val base = app.nowMillis()
    private val day = 86_400_000L
    private val repo get() = app.userData.restingHeartRates
    private val userId get() = runBlocking { app.userData.profiles.activeUserId()!! }

    @Before fun setUp() {
        Dispatchers.setMain(UnconfinedTestDispatcher(scheduler))
    }

    @After fun tearDown() = Dispatchers.resetMain()

    private fun newVm() = RestingViewModel(
        app.userData, live,
        newSession = { scope -> restingHeartRateSession(source, scope, now = { base + scheduler.currentTime }) },
        now = { base + scheduler.currentTime },
    )

    private fun strapOn(bpm: Int? = 60, kind: HeartRateSourceKind = HeartRateSourceKind.DirectStrap) {
        live.value = LiveHeartRate(bpm, kind)
    }

    /** One reading a second for [seconds] seconds. */
    private fun feed(seconds: Int, bpm: Int?, kind: HeartRateSourceKind? = HeartRateSourceKind.DirectStrap) {
        repeat(seconds) {
            scheduler.advanceTimeBy(1_000)
            scheduler.runCurrent()
            source.emit(bpm, base + scheduler.currentTime, kind)
        }
    }

    private fun finish() {
        scheduler.advanceTimeBy(2_000)
        scheduler.runCurrent()
    }

    private fun stored() = runBlocking { repo.measurements(userId).first() }

    // ---- Start ----

    @Test fun withoutAnyHeartRateSourceItCannotStartAndSaysSo() {
        val vm = newVm()
        assertFalse(vm.uiState.value.hasSource)
        assertFalse(vm.uiState.value.canStart)
        vm.start()
        assertEquals(RestingPhase.Instructions, vm.uiState.value.phase)
    }

    @Test fun theTreadmillConsoleStrapIsEnoughAndNeedsNoTreadmillConnection() {
        strapOn(kind = HeartRateSourceKind.TreadmillConsole)
        val vm = newVm()
        assertTrue(vm.uiState.value.canStart)
        assertTrue(app.controller.connectCalls.isEmpty())
    }

    // ---- The measurement ----

    @Test fun aValidMeasurementIsSavedWithItsSource() {
        strapOn()
        val vm = newVm()
        vm.start()
        assertTrue(vm.uiState.value.measuring)
        feed(181, 60)
        finish()
        val state = vm.uiState.value
        assertEquals(RestingPhase.Result, state.phase)
        assertTrue(state.result!!.valid)
        assertEquals(60, state.result!!.bpm)
        val saved = stored().single()
        assertEquals(60, saved.bpm)
        assertEquals(RestingHeartRateSource.Strap, saved.source)
        assertEquals(saved, state.saved)
        assertNull(state.elevated)
        assertEquals(60, state.reference?.bpm)
    }

    @Test fun theConsoleIsTheSourceWhenThatIsWhereTheHeartRateCameFrom() {
        strapOn(kind = HeartRateSourceKind.TreadmillConsole)
        val vm = newVm()
        vm.start()
        feed(181, 62, HeartRateSourceKind.TreadmillConsole)
        finish()
        assertEquals(RestingHeartRateSource.Console, stored().single().source)
    }

    @Test fun anInvalidMeasurementIsShownWithItsReasonAndNeverSaved() {
        strapOn()
        val vm = newVm()
        vm.start()
        // The strap drops out after 20 seconds: most of the last two minutes has no reading.
        feed(20, 61)
        scheduler.advanceTimeBy(170_000)
        scheduler.runCurrent()
        val state = vm.uiState.value
        assertEquals(RestingPhase.Result, state.phase)
        assertFalse(state.result!!.valid)
        assertTrue(RestingInvalidReason.MissingReadings in state.result!!.invalidReasons)
        assertNull(state.saved)
        assertTrue(stored().isEmpty())
    }

    @Test fun cancellingEndsTheMeasurementTooShortAndStoresNothing() {
        strapOn()
        val vm = newVm()
        vm.start()
        feed(60, 60)
        vm.cancel()
        val result = vm.uiState.value.result!!
        assertFalse(result.valid)
        assertTrue(RestingInvalidReason.TooShort in result.invalidReasons)
        assertTrue(stored().isEmpty())
    }

    @Test fun aClearlyHigherValueRaisesTheNonBlockingWarning() {
        // A usual 58, a morning of 66.
        runBlocking {
            for (daysAgo in 1..3) repo.add(userId, RestingHeartRateInput(base - daysAgo * day, 58, RestingHeartRateSource.Manual))
        }
        strapOn()
        val vm = newVm()
        vm.start()
        feed(181, 66)
        finish()
        val state = vm.uiState.value
        assertEquals(66, state.saved?.bpm)
        val warning = state.elevated
        assertNotNull(warning)
        assertEquals(8, warning!!.deltaBpm)
        assertEquals(58, warning.referenceBpm)
        assertEquals(4, stored().size) // it is a warning, not a refusal: the value is saved
    }

    @Test fun anUsualValueRaisesNoWarning() {
        runBlocking {
            for (daysAgo in 1..3) repo.add(userId, RestingHeartRateInput(base - daysAgo * day, 58, RestingHeartRateSource.Manual))
        }
        strapOn()
        val vm = newVm()
        vm.start()
        feed(181, 60)
        finish()
        assertNull(vm.uiState.value.elevated)
    }

    @Test fun resetGoesBackToTheInstructionsAndLeavingDropsARunningMeasurement() {
        strapOn()
        val vm = newVm()
        vm.start()
        vm.reset() // not while measuring
        assertTrue(vm.uiState.value.measuring)
        feed(10, 60)
        vm.leave()
        assertEquals(RestingPhase.Instructions, vm.uiState.value.phase)
        assertTrue(stored().isEmpty())
        feed(200, 60) // the dropped session does nothing any more
        assertTrue(stored().isEmpty())
    }

    // ---- The history ----

    @Test fun theReferenceAndItsStateComeFromTheHistory() {
        val vm = newVm()
        assertNull(vm.uiState.value.reference)
        runBlocking { repo.add(userId, RestingHeartRateInput(base - 2 * day, 60, RestingHeartRateSource.Manual)) }
        assertEquals(ReferenceStatus.Provisional, vm.uiState.value.reference?.status)
        runBlocking {
            repo.add(userId, RestingHeartRateInput(base - 3 * day, 62, RestingHeartRateSource.Manual))
            repo.add(userId, RestingHeartRateInput(base - 4 * day, 58, RestingHeartRateSource.Manual))
        }
        assertEquals(ReferenceStatus.Established, vm.uiState.value.reference?.status)
        assertEquals(3, vm.uiState.value.measurements.size)
        assertTrue(vm.uiState.value.measurements.zipWithNext().all { (a, b) -> a.measuredAtMillis <= b.measuredAtMillis })
    }

    @Test fun aValueTypedByHandIsOneMoreMeasurement() {
        val vm = newVm()
        vm.openManual()
        vm.setManualText("57")
        vm.saveManual()
        assertNull(vm.uiState.value.editor)
        val m = stored().single()
        assertEquals(57, m.bpm)
        assertEquals(RestingHeartRateSource.Manual, m.source)
        assertEquals(base, m.measuredAtMillis)
    }

    @Test fun aManualValueOutOfRangeOrBlankIsExplainedNextToTheField() {
        val vm = newVm()
        vm.openManual()
        vm.saveManual()
        assertEquals(ValidationCode.Blank, vm.uiState.value.editor?.error)
        vm.setManualText("300")
        vm.saveManual()
        assertEquals(ValidationCode.OutOfRange, vm.uiState.value.editor?.error)
        vm.setManualText("abc")
        vm.saveManual()
        assertEquals(ValidationCode.OutOfRange, vm.uiState.value.editor?.error)
        assertTrue(stored().isEmpty())
        // Typing again clears the message.
        vm.setManualText("58")
        assertNull(vm.uiState.value.editor?.error)
        assertEquals(InputField.RestingHeartRate, InputField.RestingHeartRate)
    }

    @Test fun deletingAnEntryAsksFirst() {
        val m = runBlocking { (repo.add(userId, RestingHeartRateInput(base - day, 60, RestingHeartRateSource.Manual)) as com.treadz.console.userdata.DataResult.Success).value }
        val vm = newVm()
        vm.requestDelete(m.id)
        assertEquals(m.id, vm.uiState.value.confirmingDeleteId)
        vm.cancelDelete()
        assertEquals(1, stored().size)
        vm.requestDelete(m.id)
        vm.confirmDelete()
        assertTrue(stored().isEmpty())
        assertNull(vm.uiState.value.confirmingDeleteId)
    }

    @Test fun deletingTheMeasurementJustSavedClearsItsResultAndWarning() {
        runBlocking {
            for (daysAgo in 1..3) repo.add(userId, RestingHeartRateInput(base - daysAgo * day, 58, RestingHeartRateSource.Manual))
        }
        strapOn()
        val vm = newVm()
        vm.start()
        feed(181, 66)
        finish()
        val id = vm.uiState.value.saved!!.id
        vm.requestDelete(id)
        vm.confirmDelete()
        assertNull(vm.uiState.value.saved)
        assertNull(vm.uiState.value.elevated)
        assertEquals(3, stored().size)
    }
}
