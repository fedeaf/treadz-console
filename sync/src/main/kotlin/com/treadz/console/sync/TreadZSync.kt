package com.treadz.console.sync

import android.content.Context
import android.os.Handler
import android.os.Looper
import androidx.lifecycle.DefaultLifecycleObserver
import androidx.lifecycle.LifecycleOwner
import androidx.lifecycle.ProcessLifecycleOwner
import com.treadz.console.sync.engine.FileSyncStateStore
import com.treadz.console.sync.engine.SyncEngine
import com.treadz.console.sync.github.RestGitHubClient
import com.treadz.console.sync.service.DefaultSyncService
import com.treadz.console.sync.service.SyncService
import com.treadz.console.sync.token.EncryptedTokenStore
import com.treadz.console.sync.work.WorkManagerSyncScheduler
import com.treadz.console.treadmill.control.Clock
import com.treadz.console.userdata.TreadZUserData
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import okhttp3.OkHttpClient
import java.io.File
import java.util.concurrent.TimeUnit

/**
 * The app's access point to the real synchronisation (one instance per process), like `TreadZUserData` for the data.
 *
 * [get] builds everything on first use: the Room-backed sync port of the data layer, the GitHub client, the encrypted
 * token store, the state file and the WorkManager scheduler, and arranges for the app coming to the foreground to
 * download changes. Call it from `Application.onCreate` so that pending uploads resume after a restart. Nothing
 * touches the network until a token is configured.
 */
object TreadZSync {

    @Volatile
    private var instance: SyncService? = null

    fun get(context: Context): SyncService =
        instance ?: synchronized(this) { instance ?: create(context.applicationContext).also { instance = it } }

    /** Replaces the service (tests of code that calls [get], e.g. the background worker). */
    fun setForTests(service: SyncService?) {
        instance = service
    }

    private fun create(context: Context): SyncService {
        val store = TreadZUserData.sync(context)
        val programs = TreadZUserData.programDocuments(context)
        val fitnessTests = TreadZUserData.fitnessTestDocuments(context)
        val tokens = EncryptedTokenStore(context.getSharedPreferences("treadz_sync_secrets", Context.MODE_PRIVATE))
        val http = OkHttpClient.Builder()
            .connectTimeout(15, TimeUnit.SECONDS)
            .readTimeout(30, TimeUnit.SECONDS)
            .callTimeout(120, TimeUnit.SECONDS)
            .build()
        val client = RestGitHubClient(http, tokens::get)
        val stateStore = FileSyncStateStore(File(context.filesDir, "treadz_sync_state.json"))
        val engine = SyncEngine(
            client, store, programs, fitnessTests, stateStore, Clock.System,
            recalculation = TreadZUserData.get(context).fitnessRecalculator,
        )
        val service = DefaultSyncService(
            client = client,
            engine = engine,
            tokens = tokens,
            stateStore = stateStore,
            store = store,
            programs = programs,
            fitnessTests = fitnessTests,
            scheduler = WorkManagerSyncScheduler(context),
            scope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
        )
        Handler(Looper.getMainLooper()).post {
            ProcessLifecycleOwner.get().lifecycle.addObserver(
                object : DefaultLifecycleObserver {
                    override fun onStart(owner: LifecycleOwner) = service.onAppOpened()
                },
            )
        }
        return service
    }
}
