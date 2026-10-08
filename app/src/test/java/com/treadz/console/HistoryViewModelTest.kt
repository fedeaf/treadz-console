package com.treadz.console

import com.treadz.console.fakes.FakeApp
import com.treadz.console.fakes.MainDispatcherRule
import com.treadz.console.fakes.RecordingWorkout
import com.treadz.console.fakes.createProfile
import com.treadz.console.history.HistoryTab
import com.treadz.console.history.HistoryViewModel
import com.treadz.console.history.ExportNotice
import com.treadz.console.history.WorkoutDetailViewModel
import com.treadz.console.treadmill.BeltStatus
import com.treadz.console.userdata.WorkoutEndReason
import com.treadz.console.userdata.WorkoutSample
import com.treadz.console.userdata.WorkoutStats
import com.treadz.console.userdata.export.ExportError
import com.treadz.console.userdata.export.ExportFormat
import com.treadz.console.userdata.export.ExportStorage
import com.treadz.console.userdata.export.SavedExport
import com.treadz.console.userdata.export.WorkoutExporter
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test

class HistoryViewModelTest {

    @get:Rule val mainDispatcher = MainDispatcherRule()
    private val app = FakeApp()
    private val data = app.userData
    private val activeId get() = runBlocking { data.profiles.activeUserId()!! }

    private fun sample(i: Int, hr: Int? = 120) = WorkoutSample(
        seq = i, timestampMillis = 1_000L * i, elapsedSeconds = i, speedKmh = 8.0, targetSpeedKmh = 8.0, inclinePercent = 1.0,
        distanceMeters = i * 2, calories = i / 10, heartRateBpm = hr, beltStatus = BeltStatus.Running,
    )

    private fun addWorkout(userId: Long?, startedAt: Long, seconds: Int = 60, reason: WorkoutEndReason = WorkoutEndReason.Stopped): Long =
        data.addWorkout(
            userId,
            WorkoutStats(durationSeconds = seconds, distanceMeters = seconds * 2, calories = seconds / 10, averageSpeedKmh = 8.0, maxSpeedKmh = 8.0),
            (0 until seconds).map { sample(it) },
            startedAt,
            reason,
        )

    private val vm by lazy { HistoryViewModel(data) }
    private val state get() = vm.uiState.value

    @Test fun listsTheActiveUsersWorkoutsNewestFirst() {
        val old = addWorkout(activeId, startedAt = 1_000)
        val recent = addWorkout(activeId, startedAt = 9_000)
        val someoneElse = data.createProfile("Ana")
        addWorkout(someoneElse.id, startedAt = 5_000)
        assertEquals(listOf(recent, old), state.workouts.map { it.id })
        assertTrue(state.unassigned.isEmpty())
    }

    @Test fun unassignedWorkoutsAreListedSeparatelyAndTheTabAppearsOnlyWithThem() {
        addWorkout(activeId, 1_000)
        assertEquals(HistoryTab.Mine, state.visibleTab)
        vm.selectTab(HistoryTab.Unassigned)
        assertEquals(HistoryTab.Mine, state.visibleTab) // nothing unassigned: no such tab
        val orphan = addWorkout(null, 2_000)
        assertEquals(listOf(orphan), state.unassigned.map { it.id })
        assertEquals(HistoryTab.Unassigned, state.visibleTab)
    }

    @Test fun assigningMovesTheWorkoutToTheChosenUser() {
        val ana = data.createProfile("Ana")
        val orphan = addWorkout(null, 2_000)
        vm.requestAssign(orphan)
        assertEquals(orphan, state.assigningId)
        vm.assignTo(ana.id)
        assertNull(state.assigningId)
        assertTrue(state.unassigned.isEmpty())
        assertEquals(ana.id, runBlocking { data.workouts.detail(orphan)!!.summary.userId })
    }

    @Test fun deleteNeedsConfirmation() {
        val id = addWorkout(activeId, 1_000)
        vm.requestDelete(id)
        vm.cancelDelete()
        assertEquals(1, state.workouts.size)
        vm.requestDelete(id)
        vm.confirmDelete()
        assertTrue(state.workouts.isEmpty())
        assertNull(state.confirmingDeleteId)
    }

    @Test fun noActiveUserLeavesTheListEmptyButShowsUnassigned() = runBlocking {
        addWorkout(activeId, 1_000)
        val orphan = addWorkout(null, 2_000)
        data.profiles.setActiveUser(null)
        assertNull(state.user)
        assertTrue(state.workouts.isEmpty())
        assertEquals(listOf(orphan), state.unassigned.map { it.id })
    }

    @Test fun detailLoadsSamplesAndOwner() {
        val id = addWorkout(activeId, 1_000, seconds = 90)
        val detail = WorkoutDetailViewModel(id, data, app.exporter).uiState.value
        assertEquals(90, detail.detail!!.samples.size)
        assertTrue(detail.owner!!.isGeneric)
    }

    @Test fun detailOfAnUnassignedWorkoutCanBeAssignedAndDeleted() {
        val ana = data.createProfile("Ana")
        val id = addWorkout(null, 1_000)
        val vm = WorkoutDetailViewModel(id, data, app.exporter)
        assertNull(vm.uiState.value.owner)
        vm.requestAssign()
        assertTrue(vm.uiState.value.assigning)
        vm.assignTo(ana.id)
        assertEquals(ana.id, vm.uiState.value.owner!!.id)
        vm.requestDelete()
        var deleted = false
        vm.confirmDelete { deleted = true }
        assertTrue(deleted)
        assertNull(runBlocking { data.workouts.detail(id) })
    }

    @Test fun missingWorkoutIsReported() {
        assertTrue(WorkoutDetailViewModel(12_345, data, app.exporter).uiState.value.notFound)
    }

    @Test fun inProgressWorkoutIsMarkedAsSuchAndEndsWhenTheBeltStops() {
        val recording = RecordingWorkout(app, seconds = 30).start()
        try {
            val running = state.workouts.single()
            assertTrue(running.isInProgress)
            assertEquals(30, running.stats.durationSeconds)
            val detail = WorkoutDetailViewModel(running.id, data, app.exporter).uiState.value
            assertTrue(detail.detail!!.summary.isInProgress)
            assertTrue(detail.detail.samples.isNotEmpty())
            // The belt stops: the workout is finished and listed as such.
            app.controller.showState { it.copy(status = BeltStatus.Idle, speedKmh = 0.0) }
            runBlocking { kotlinx.coroutines.withTimeout(10_000) { while (state.workouts.single().isInProgress) kotlinx.coroutines.delay(10) } }
            assertEquals(WorkoutEndReason.Stopped, state.workouts.single().endReason)
        } finally {
            recording.close()
        }
    }

    // ---- Export ----

    private fun waitUntilExported(vm: WorkoutDetailViewModel) = runBlocking {
        kotlinx.coroutines.withTimeout(10_000) { vm.uiState.first { !it.exporting } }
    }

    @Test fun exportAsksForTheFormatAndSavesIt() {
        val id = addWorkout(activeId, 1_000, seconds = 60)
        val vm = WorkoutDetailViewModel(id, data, app.exporter)
        assertTrue(vm.uiState.value.canExport)
        vm.requestExport()
        assertTrue(vm.uiState.value.choosingExport)
        assertTrue(app.exportStorage.files.isEmpty()) // nothing is written until a format is chosen

        var shared: SavedExport? = null
        vm.export(ExportFormat.Tcx) { shared = it }
        waitUntilExported(vm)
        val notice = vm.uiState.value.exportNotice as ExportNotice.Saved
        assertFalse(vm.uiState.value.choosingExport)
        assertEquals(notice.export, shared)
        assertEquals(ExportFormat.Tcx, shared!!.format)
        assertTrue(shared!!.fileName.endsWith(".tcx"))
        assertEquals(listOf(shared!!.fileName), app.exportStorage.files.keys.toList())
    }

    @Test fun exportCsvUsesTheCsvFile() {
        val id = addWorkout(activeId, 1_000, seconds = 30)
        val vm = WorkoutDetailViewModel(id, data, app.exporter)
        vm.requestExport()
        var shared: SavedExport? = null
        vm.export(ExportFormat.Csv) { shared = it }
        waitUntilExported(vm)
        assertEquals("text/csv", shared!!.format.mimeType)
        assertTrue(shared!!.fileName.endsWith(".csv"))
    }

    @Test fun cancellingTheFormatChoiceExportsNothing() {
        val id = addWorkout(activeId, 1_000)
        val vm = WorkoutDetailViewModel(id, data, app.exporter)
        vm.requestExport()
        vm.cancelExport()
        assertFalse(vm.uiState.value.choosingExport)
        assertTrue(app.exportStorage.files.isEmpty())
    }

    @Test fun aWorkoutWithoutSamplesReportsTheError() {
        val id = data.addWorkout(
            activeId,
            WorkoutStats(durationSeconds = 0, distanceMeters = 0, calories = 0, averageSpeedKmh = 0.0, maxSpeedKmh = 0.0),
            emptyList(),
            1_000,
            WorkoutEndReason.Stopped,
        )
        val vm = WorkoutDetailViewModel(id, data, app.exporter)
        var shared = false
        vm.requestExport()
        vm.export(ExportFormat.Csv) { shared = true }
        waitUntilExported(vm)
        assertEquals(ExportNotice.Failed(ExportError.NoSamples), vm.uiState.value.exportNotice)
        assertFalse(shared)
        vm.dismissExportNotice()
        assertNull(vm.uiState.value.exportNotice)
    }

    @Test fun storageErrorIsReported() {
        val id = addWorkout(activeId, 1_000)
        val failing = WorkoutExporter("0.1.0", object : ExportStorage {
            override fun save(fileName: String, format: ExportFormat, content: ByteArray): SavedExport =
                throw java.io.IOException("disk full")
        }, java.time.ZoneOffset.UTC)
        val vm = WorkoutDetailViewModel(id, data, failing)
        vm.requestExport()
        vm.export(ExportFormat.Tcx) { }
        waitUntilExported(vm)
        assertEquals(ExportNotice.Failed(ExportError.StorageError), vm.uiState.value.exportNotice)
    }

    @Test fun aWorkoutInProgressCannotBeExported() {
        val recording = RecordingWorkout(app, seconds = 20).start()
        try {
            val running = state.workouts.single()
            val vm = WorkoutDetailViewModel(running.id, data, app.exporter)
            assertFalse(vm.uiState.value.canExport)
            vm.requestExport()
            assertFalse(vm.uiState.value.choosingExport)
            var shared = false
            vm.export(ExportFormat.Csv) { shared = true }
            assertFalse(shared)
            assertTrue(app.exportStorage.files.isEmpty())
        } finally {
            recording.close()
        }
    }

    @Test fun pendingUploadsAreOnlyReportedWhileSyncIsLinked() {
        val first = addWorkout(activeId, startedAt = 1_000)
        val second = addWorkout(activeId, startedAt = 2_000)
        app.sync.setPendingWorkouts(setOf(first))
        val synced = HistoryViewModel(data, app.sync)
        // Not linked: everything is local, nothing is "pending".
        assertTrue(synced.uiState.value.pendingUploadIds.isEmpty())
        app.sync.emit(com.treadz.console.sync.service.SyncStatus(setup = com.treadz.console.sync.service.SyncSetup.Linked))
        assertEquals(setOf(first), synced.uiState.value.pendingUploadIds)
        app.sync.setPendingWorkouts(setOf(first, second))
        assertEquals(setOf(first, second), synced.uiState.value.pendingUploadIds)
        app.sync.emit(com.treadz.console.sync.service.SyncStatus())
        assertTrue(synced.uiState.value.pendingUploadIds.isEmpty())
    }

    @Test fun withoutASyncServiceThereAreNoPendingUploads() {
        addWorkout(activeId, startedAt = 1_000)
        assertTrue(vm.uiState.value.pendingUploadIds.isEmpty())
    }
}
