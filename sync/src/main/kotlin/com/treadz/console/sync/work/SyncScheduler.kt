package com.treadz.console.sync.work

import android.content.Context
import androidx.work.BackoffPolicy
import androidx.work.Constraints
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.NetworkType
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.treadz.console.sync.TreadZSync
import java.util.concurrent.TimeUnit

/** Queues the upload of pending changes to run in the background. */
interface SyncScheduler {
    /** Asks for a synchronisation to run as soon as there is a network. Several calls while one is queued count as one. */
    fun schedulePush()

    fun cancel()
}

/** [SyncScheduler] that does nothing: for tests and for code that syncs by itself. */
object NoScheduler : SyncScheduler {
    override fun schedulePush() = Unit

    override fun cancel() = Unit
}

/**
 * [SyncScheduler] on WorkManager: one unique job that needs a connected network (**only the network, no Bluetooth**)
 * and is retried with exponential backoff, so uploads also happen with the app closed, as soon as there is a connection.
 */
class WorkManagerSyncScheduler(private val context: Context) : SyncScheduler {

    override fun schedulePush() {
        val request = OneTimeWorkRequestBuilder<SyncWorker>()
            .setConstraints(Constraints.Builder().setRequiredNetworkType(NetworkType.CONNECTED).build())
            .setBackoffCriteria(BackoffPolicy.EXPONENTIAL, BACKOFF_SECONDS, TimeUnit.SECONDS)
            .build()
        WorkManager.getInstance(context).enqueueUniqueWork(UNIQUE_NAME, ExistingWorkPolicy.KEEP, request)
    }

    override fun cancel() {
        WorkManager.getInstance(context).cancelUniqueWork(UNIQUE_NAME)
    }

    companion object {
        const val UNIQUE_NAME = "treadz-sync-push"
        private const val BACKOFF_SECONDS = 30L
    }
}

/** The background job: one full synchronisation cycle. */
class SyncWorker(context: Context, params: WorkerParameters) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val service = TreadZSync.get(applicationContext)
        val error = service.syncNow()
        return when {
            error == null -> if (service.status.value.pendingCount > 0) Result.retry() else Result.success()
            error.code.isRetryable -> Result.retry()
            else -> Result.failure() // the user has to act (new token...); the status tells the UI
        }
    }
}
