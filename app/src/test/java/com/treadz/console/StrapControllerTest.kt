package com.treadz.console

import com.treadz.console.devices.StrapController
import com.treadz.console.devices.StrapUiState
import com.treadz.console.fakes.FakeApp
import com.treadz.console.fakes.FakeHeartRateMonitor
import com.treadz.console.fakes.createProfile
import com.treadz.console.hrm.SensorContact
import com.treadz.console.hrm.StrapProblem
import com.treadz.console.programs.fitness.HeartRateSourceKind
import com.treadz.console.programs.heartrate.AutomaticHeartRateSource
import com.treadz.console.programs.heartrate.ControllerHeartRateSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.TestCoroutineScheduler
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the app does with the user's heart-rate strap: keeps the monitor on the active user's strap (at start and on every
 * user switch, silently), lets go of it when the app exits, asks once whether to use the first strap it sees, and the
 * scan, pairing and forgetting of the Devices screen.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class StrapControllerTest {

    private val scheduler = TestCoroutineScheduler()
    private val scope = CoroutineScope(SupervisorJob() + UnconfinedTestDispatcher(scheduler))
    private val app = FakeApp()
    private val monitor = FakeHeartRateMonitor()
    private val profiles get() = app.userData.profiles
    private val strapA = "AA:AA:AA:AA:AA:01"
    private val strapB = "BB:BB:BB:BB:BB:02"

    private fun newController(): StrapController {
        val source = AutomaticHeartRateSource(monitor, ControllerHeartRateSource(app.controller), scope)
        return StrapController(monitor, source, app.controller.state, profiles, app.settings, scope)
    }

    private fun StrapController.current(): StrapUiState = runBlocking { state.first() }

    private fun advance(millis: Long) {
        scheduler.advanceTimeBy(millis)
        scheduler.runCurrent()
    }

    private fun activeId() = runBlocking { profiles.activeUserId()!! }

    private fun pair(userId: Long, address: String, name: String = "Strap") {
        runBlocking { profiles.setStrap(userId, address, name) }
    }

    // ---- use() at start and on every user switch ----

    @Test fun startsOnTheActiveUsersStrap() {
        pair(activeId(), strapA)
        newController().start()
        assertEquals(listOf(strapA), monitor.useCalls)
    }

    @Test fun withoutAStrapTheMonitorIsToldNothing() {
        newController().start()
        assertNull(monitor.address.value)
        assertTrue(monitor.useCalls.isEmpty())
    }

    @Test fun everyUserSwitchHandsTheNewUsersStrapOrNothing() {
        val ana = app.userData.createProfile("Ana")
        val beto = app.userData.createProfile("Beto")
        pair(ana.id, strapA)
        pair(beto.id, strapB)
        runBlocking { profiles.setActiveUser(ana.id) }
        newController().start()
        assertEquals(strapA, monitor.address.value)

        runBlocking { profiles.setActiveUser(beto.id) }
        assertEquals(strapB, monitor.address.value)
        val generic = runBlocking { profiles.profiles.first().first { it.isGeneric }.id }
        runBlocking { profiles.setActiveUser(generic) }
        assertNull(monitor.address.value)
        assertEquals(listOf(strapA, strapB, null), monitor.useCalls)
    }

    @Test fun pairingOrForgettingFromDevicesMovesTheMonitor() {
        val controller = newController().also { it.start() }
        val id = activeId()
        controller.pair(com.treadz.console.hrm.DiscoveredStrap(strapA, "HRM A", -60))
        assertEquals(strapA, monitor.address.value)
        assertEquals("HRM A", controller.current().name)
        controller.forget()
        assertNull(monitor.address.value)
        assertFalse(controller.current().paired)
        assertNull(runBlocking { profiles.profile(id).first()!!.strapAddress })
    }

    // ---- Exit ----

    @Test fun exitReleasesTheStrapAndTheNextLaunchTakesItAgain() {
        pair(activeId(), strapA)
        val controller = newController().also { it.start() }
        controller.release()
        assertEquals(1, monitor.releaseCalls)
        assertNull(monitor.address.value)
        // A profile change after the exit must not take the strap back.
        pair(activeId(), strapB)
        assertNull(monitor.address.value)
        // The app is opened again in the same process.
        controller.onForeground()
        assertEquals(strapB, monitor.address.value)
    }

    // ---- The one-time question ----

    private fun foundStraps() {
        monitor.scanResults = FakeHeartRateMonitor.SAMPLE_STRAPS
    }

    @Test fun theStrongestStrapSeenAtStartIsOfferedOnce() {
        foundStraps()
        val controller = newController().also { it.start() }
        controller.onPermissionsGranted()
        assertNull("nothing until the scan is over", controller.current().pairingOffer)
        advance(StrapController.PAIRING_SCAN_MILLIS)
        assertEquals(FakeHeartRateMonitor.STRAP_ADDRESS, controller.current().pairingOffer?.address)
        assertEquals(1, monitor.scanCalls)
        // Granting the permissions again does not scan again.
        controller.onPermissionsGranted()
        advance(StrapController.PAIRING_SCAN_MILLIS)
        assertEquals(1, monitor.scanCalls)
    }

    @Test fun yesPairsTheStrapWithTheActiveUser() {
        foundStraps()
        val controller = newController().also { it.start() }
        controller.onPermissionsGranted()
        advance(StrapController.PAIRING_SCAN_MILLIS)
        controller.acceptPairingOffer()
        assertNull(controller.current().pairingOffer)
        assertEquals(FakeHeartRateMonitor.STRAP_ADDRESS, runBlocking { profiles.profile(activeId()).first()!!.strapAddress })
        assertEquals(FakeHeartRateMonitor.STRAP_ADDRESS, monitor.address.value)
    }

    @Test fun noIsRememberedPerUserAndNeverAskedAgain() {
        foundStraps()
        val first = newController().also { it.start() }
        first.onPermissionsGranted()
        advance(StrapController.PAIRING_SCAN_MILLIS)
        first.declinePairingOffer()
        assertNull(first.current().pairingOffer)
        assertTrue(app.settings.strapQuestionAnswered(activeId()))

        // The next time the app opens the user is not asked: no scan at all.
        val second = newController().also { it.start() }
        second.onPermissionsGranted()
        advance(StrapController.PAIRING_SCAN_MILLIS)
        assertNull(second.current().pairingOffer)
        assertEquals(1, monitor.scanCalls)
        assertNull(monitor.address.value)
    }

    @Test fun anotherUserIsAskedTheirOwnQuestion() {
        foundStraps()
        val ana = app.userData.createProfile("Ana")
        app.settings.setStrapQuestionAnswered(activeId())
        runBlocking { profiles.setActiveUser(ana.id) }
        val controller = newController().also { it.start() }
        controller.onPermissionsGranted()
        advance(StrapController.PAIRING_SCAN_MILLIS)
        assertNotNull(controller.current().pairingOffer)
    }

    @Test fun aUserWithAStrapIsNotAsked() {
        foundStraps()
        pair(activeId(), strapA)
        val controller = newController().also { it.start() }
        controller.onPermissionsGranted()
        advance(StrapController.PAIRING_SCAN_MILLIS)
        assertNull(controller.current().pairingOffer)
        assertEquals(0, monitor.scanCalls)
    }

    @Test fun nothingIsScannedBeforeThePermissionsAreGranted() {
        foundStraps()
        val controller = newController().also { it.start() }
        advance(StrapController.PAIRING_SCAN_MILLIS)
        assertEquals(0, monitor.scanCalls)
        assertNull(controller.current().pairingOffer)
    }

    @Test fun aFailedStartupScanIsSilent() {
        monitor.scanFails(StrapProblem.BluetoothOff)
        val controller = newController().also { it.start() }
        controller.onPermissionsGranted()
        advance(StrapController.PAIRING_SCAN_MILLIS)
        assertNull(controller.current().pairingOffer)
        assertNull(controller.current().scanProblem)
        assertFalse(app.settings.strapQuestionAnswered(activeId()))
    }

    // ---- Devices ----

    @Test fun theDevicesScanListsStrapsByStrength() {
        monitor.scanResults = FakeHeartRateMonitor.SAMPLE_STRAPS.reversed()
        val controller = newController().also { it.start() }
        controller.scan()
        val state = controller.current()
        assertTrue(state.scanning)
        assertEquals(listOf(-52, -74), state.devices.map { it.rssi })
        controller.stopScan()
        assertFalse(controller.current().scanning)
        assertTrue(controller.current().scanFinished)
    }

    @Test fun aScanThatFailsSaysWhy() {
        monitor.scanFails(StrapProblem.PermissionMissing)
        val controller = newController().also { it.start() }
        controller.scan()
        assertEquals(StrapProblem.PermissionMissing, controller.current().scanProblem)
        assertFalse(controller.current().scanning)
    }

    // ---- The heart rate in use ----

    @Test fun theLiveHeartRateIsTheStrapsWhenConnectedElseTheConsoles() {
        val controller = newController().also { it.start() }
        assertNull(controller.current().heartRate.source)

        app.controller.showConnected()
        app.controller.showRunning(heartRate = 131)
        assertEquals(HeartRateSourceKind.TreadmillConsole, controller.current().heartRate.source)
        assertEquals(131, controller.current().heartRate.bpm)

        monitor.showConnected(bpm = 72)
        val strap = controller.current()
        assertEquals(HeartRateSourceKind.DirectStrap, strap.heartRate.source)
        assertEquals(72, strap.heartRate.bpm)
        assertEquals(72, strap.strapBpm)
        assertEquals(87, strap.batteryPercent)
    }

    @Test fun aStrapWithoutContactIsStillTheSourceButHasNoValue() {
        pair(activeId(), strapA)
        val controller = newController().also { it.start() }
        app.controller.showConnected()
        app.controller.showRunning(heartRate = 131)
        monitor.showConnected(address = strapA, bpm = null)
        monitor.showReading(0, SensorContact.NotDetected)
        val state = controller.current()
        assertEquals(HeartRateSourceKind.DirectStrap, state.heartRate.source)
        assertNull(state.heartRate.bpm)
        assertNull(state.strapBpm)
    }
}
