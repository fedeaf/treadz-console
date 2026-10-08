package com.treadz.console.sync

import android.content.Context
import androidx.work.ListenableWorker
import androidx.work.NetworkType
import androidx.work.WorkInfo
import androidx.work.WorkManager
import androidx.work.testing.TestListenableWorkerBuilder
import androidx.work.testing.WorkManagerTestInitHelper
import com.treadz.console.sync.github.RepositoryRef
import com.treadz.console.sync.service.FakeSyncService
import com.treadz.console.sync.service.SyncSetup
import com.treadz.console.sync.service.SyncStatus
import com.treadz.console.sync.work.SyncWorker
import com.treadz.console.sync.work.WorkManagerSyncScheduler
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

@RunWith(RobolectricTestRunner::class)
class SchedulerAndFakeTest {

    private val context = RuntimeEnvironment.getApplication() as Context

    @Before fun initWorkManager() {
        WorkManagerTestInitHelper.initializeTestWorkManager(context)
    }

    @After fun resetService() = TreadZSync.setForTests(null)

    @Test fun `pushes are one unique job that needs only a network`() {
        val scheduler = WorkManagerSyncScheduler(context)
        scheduler.schedulePush()
        scheduler.schedulePush()
        val infos = WorkManager.getInstance(context).getWorkInfosForUniqueWork(WorkManagerSyncScheduler.UNIQUE_NAME).get()
        assertEquals(1, infos.size)
        val constraints = infos.single().constraints
        assertEquals(NetworkType.CONNECTED, constraints.requiredNetworkType)
        assertTrue(!constraints.requiresCharging() && !constraints.requiresDeviceIdle())
        scheduler.cancel()
        assertEquals(WorkInfo.State.CANCELLED, WorkManager.getInstance(context).getWorkInfosForUniqueWork(WorkManagerSyncScheduler.UNIQUE_NAME).get().single().state)
    }

    private fun runWorker(service: FakeSyncService): ListenableWorker.Result {
        TreadZSync.setForTests(service)
        val worker = TestListenableWorkerBuilder<SyncWorker>(context).build()
        return runBlocking { worker.doWork() }
    }

    @Test fun `the worker runs a sync and retries only the failures that may go away`() {
        val service = FakeSyncService(SyncStatus(setup = SyncSetup.Linked, repository = RepositoryRef("a", "b", "main")))
        assertEquals(ListenableWorker.Result.success(), runWorker(service))
        assertEquals(listOf("syncNow"), service.calls)

        service.nextSyncError = SyncError(SyncErrorCode.NoNetwork)
        assertEquals(ListenableWorker.Result.retry(), runWorker(service))
        service.nextSyncError = SyncError(SyncErrorCode.RateLimited, 60_000)
        assertEquals(ListenableWorker.Result.retry(), runWorker(service))
        service.nextSyncError = SyncError(SyncErrorCode.Unauthorized)
        assertEquals(ListenableWorker.Result.failure(), runWorker(service))
    }

    @Test fun `the worker retries when more changes arrived while it ran`() {
        val service = FakeSyncService(SyncStatus(setup = SyncSetup.Linked, pendingCount = 2))
        // the fake clears the pending count when it syncs; emulate new changes arriving after that
        val synced = object : com.treadz.console.sync.service.SyncService by service {
            override suspend fun syncNow(): SyncError? = service.syncNow().also { service.emit(service.status.value.copy(pendingCount = 1)) }
        }
        TreadZSync.setForTests(synced)
        val worker = TestListenableWorkerBuilder<SyncWorker>(context).build()
        assertEquals(ListenableWorker.Result.retry(), runBlocking { worker.doWork() })
    }

    // ---- the fake for the UI ----

    @Test fun `the fake follows the setup flow with scripted data`() = runBlocking<Unit> {
        val demo = RepositoryRef("demo", "treadz-data", "main")
        val fake = FakeSyncService()
        assertEquals(SyncErrorCode.Unauthorized, fake.configure("invalid")!!.code)
        assertEquals(SyncSetup.NotConfigured, fake.status.value.setup)

        assertNull(fake.configure("ghp_anything"))
        assertEquals(SyncSetup.Linked, fake.status.value.setup)
        assertEquals(demo, fake.status.value.repository)

        fake.clear()
        fake.privateRepositories = listOf(demo, RepositoryRef("demo", "other", "main"))
        fake.localUploads = FirstLinkSummary(listOf(UserUpload("Ana", true, 3, 2, 0)), remoteUsers = 1)
        assertNull(fake.configure("ghp_anything"))
        assertEquals(SyncSetup.ChoosingRepository, fake.status.value.setup)
        assertNull(fake.selectRepository(demo))
        assertEquals(SyncSetup.AwaitingFirstLinkConfirmation, fake.status.value.setup)
        assertEquals(2, fake.status.value.firstLink!!.totalWorkouts)
        fake.setPendingWorkouts(setOf(4L))
        assertNull(fake.confirmFirstLink())
        assertEquals(SyncSetup.Linked, fake.status.value.setup)
        assertEquals(emptySet<Long>(), fake.pendingWorkoutIds.first())
        assertTrue(fake.status.value.lastSyncMillis != null)

        fake.nextSyncError = SyncError(SyncErrorCode.NoNetwork)
        assertEquals(SyncErrorCode.NoNetwork, fake.syncNow()!!.code)
        assertEquals(SyncErrorCode.NoNetwork, fake.status.value.error!!.code)
        assertNull(fake.syncNow())
        assertNull(fake.status.value.error)

        fake.privateRepositories = emptyList()
        fake.clear()
        assertEquals(SyncErrorCode.NoPrivateRepository, fake.configure("ghp_anything")!!.code)
    }
}
