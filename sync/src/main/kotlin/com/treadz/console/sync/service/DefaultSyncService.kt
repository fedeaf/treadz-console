package com.treadz.console.sync.service

import com.treadz.console.sync.SyncError
import com.treadz.console.sync.SyncErrorCode
import com.treadz.console.sync.SyncException
import com.treadz.console.sync.engine.SyncEngine
import com.treadz.console.sync.engine.SyncState
import com.treadz.console.sync.engine.SyncStateStore
import com.treadz.console.sync.engine.toSyncError
import com.treadz.console.sync.github.GitHubClient
import com.treadz.console.sync.github.GitHubException
import com.treadz.console.sync.github.RepositoryRef
import com.treadz.console.sync.token.TokenStore
import com.treadz.console.sync.work.SyncScheduler
import com.treadz.console.treadmill.control.Clock
import com.treadz.console.userdata.sync.ProgramDocumentStore
import com.treadz.console.userdata.sync.SyncStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * The real [SyncService]: keeps the setup state, runs the [SyncEngine] one cycle at a time and asks the [scheduler] to
 * upload in the background whenever something is pending.
 *
 * @param scope runs the initial loading, the observation of pending changes and [onAppOpened]; give it a background dispatcher.
 */
class DefaultSyncService(
    private val client: GitHubClient,
    private val engine: SyncEngine,
    private val tokens: TokenStore,
    private val stateStore: SyncStateStore,
    private val store: SyncStore,
    private val programs: ProgramDocumentStore,
    private val scheduler: SyncScheduler,
    private val scope: CoroutineScope,
    private val clock: Clock = Clock.System,
) : SyncService {

    private val _status = MutableStateFlow(SyncStatus())
    override val status: StateFlow<SyncStatus> = _status.asStateFlow()

    override val pendingWorkoutIds: Flow<Set<Long>> get() = store.pendingWorkoutIds

    private val syncLock = Mutex()
    private val setupLock = Mutex()

    init {
        scope.launch { setupLock.withLock { restore() } }
        scope.launch {
            combine(store.pendingCount, programs.pendingCount) { a, b -> a + b }.collect { count ->
                _status.update { it.copy(pendingCount = count) }
                if (count > 0 && _status.value.setup == SyncSetup.Linked && tokens.get() != null) scheduler.schedulePush()
            }
        }
    }

    /** Rebuilds the status from what is stored (after a restart). */
    private suspend fun restore() {
        val state = stateStore.load()
        val token = tokens.get()
        _status.update { it.copy(lastSyncMillis = state.lastSyncMillis) }
        when {
            token == null -> _status.update { it.copy(setup = SyncSetup.NotConfigured, repository = null) }
            state.repository == null -> validateStoredToken()
            state.linked -> _status.update { it.copy(setup = SyncSetup.Linked, repository = state.repository) }
            else -> chooseRepository(state.repository, syncAfterwards = false)
        }
        if (_status.value.setup == SyncSetup.Linked && _status.value.pendingCount > 0) scheduler.schedulePush()
    }

    override suspend fun configure(token: String): SyncError? = setupLock.withLock {
        _status.update { it.copy(error = null) }
        val clean = token.trim()
        if (clean.isEmpty()) return fail(SyncError(SyncErrorCode.Unauthorized))
        // The candidate is tried without storing it: whatever fails leaves the stored token and the setup untouched
        // (so a device that is linked keeps syncing with its working token) and a background sync never sees it.
        val repositories = try {
            client.privateRepositories(clean)
        } catch (e: GitHubException) {
            return fail(e.toSyncError())
        }
        if (repositories.isEmpty()) return fail(SyncError(SyncErrorCode.NoPrivateRepository))
        if (_status.value.setup == SyncSetup.Linked) {
            val linked = stateStore.load().repository
            val same = linked?.let { l -> repositories.firstOrNull { it.fullName.equals(l.fullName, ignoreCase = true) } }
            // The new token cannot reach the linked repository: it is never switched silently (see [SyncService.configure]).
            if (linked == null || same == null) return fail(SyncError(SyncErrorCode.RepositoryNotFound))
            tokens.set(clean)
            return chooseRepository(linked, syncAfterwards = true)
        }
        tokens.set(clean)
        advance(repositories)
    }

    /** Validates the stored token (after a restart). A token that does not work is forgotten. */
    private suspend fun validateStoredToken(): SyncError? {
        val repositories = try {
            client.privateRepositories()
        } catch (e: GitHubException) {
            tokens.clear()
            return fail(e.toSyncError())
        }
        if (repositories.isEmpty()) {
            tokens.clear()
            return fail(SyncError(SyncErrorCode.NoPrivateRepository))
        }
        return advance(repositories)
    }

    /** Moves the setup on with the repositories the (already stored) token can use: the only one is used, several are offered. */
    private suspend fun advance(repositories: List<RepositoryRef>): SyncError? {
        if (repositories.size == 1) return chooseRepository(repositories.single(), syncAfterwards = true)
        _status.update {
            it.copy(setup = SyncSetup.ChoosingRepository, repositories = repositories, repository = null, firstLink = null, error = null)
        }
        return null
    }

    override suspend fun selectRepository(repository: RepositoryRef): SyncError? = setupLock.withLock {
        if (tokens.get() == null) return fail(SyncError(SyncErrorCode.Unauthorized))
        chooseRepository(repository, syncAfterwards = true)
    }

    /**
     * Remembers the repository; links at once, or asks for confirmation if this device has data to upload. A repository
     * that is already linked stays linked (e.g. when only the token is replaced): no new confirmation.
     */
    private suspend fun chooseRepository(repository: RepositoryRef, syncAfterwards: Boolean): SyncError? {
        val previous = stateStore.load()
        if (previous.repository == repository && previous.linked) {
            _status.update { it.copy(setup = SyncSetup.Linked, repository = repository, repositories = emptyList(), firstLink = null, error = null) }
            return if (syncAfterwards) runSync() else null
        }
        val state = if (previous.repository == repository) previous else SyncState(repository = repository, lastSyncMillis = null)
        stateStore.save(state.copy(linked = false))
        val preview = try {
            engine.previewFirstLink(repository)
        } catch (e: SyncException) {
            return fail(e.error)
        }
        if (preview.hasUploads) {
            _status.update {
                it.copy(
                    setup = SyncSetup.AwaitingFirstLinkConfirmation, repository = repository, repositories = emptyList(),
                    firstLink = preview, error = null,
                )
            }
            return null
        }
        stateStore.save(state.copy(linked = true))
        _status.update { it.copy(setup = SyncSetup.Linked, repository = repository, repositories = emptyList(), firstLink = null, error = null) }
        return if (syncAfterwards) runSync() else null
    }

    override suspend fun confirmFirstLink(): SyncError? {
        val allowed = setupLock.withLock {
            val state = stateStore.load()
            if (_status.value.setup != SyncSetup.AwaitingFirstLinkConfirmation || state.repository == null) return@withLock false
            stateStore.save(state.copy(linked = true))
            _status.update { it.copy(setup = SyncSetup.Linked, firstLink = null, error = null) }
            true
        }
        return if (allowed) runSync() else null
    }

    override suspend fun cancelSetup() {
        setupLock.withLock {
            if (_status.value.setup == SyncSetup.Linked) return
            forget()
        }
    }

    override suspend fun cancelFirstLink() = cancelSetup()

    override suspend fun clear() {
        setupLock.withLock { forget() }
    }

    private suspend fun forget() {
        tokens.clear()
        stateStore.save(SyncState())
        scheduler.cancel()
        _status.update { SyncStatus(pendingCount = it.pendingCount) }
    }

    override suspend fun syncNow(): SyncError? = if (_status.value.setup == SyncSetup.Linked) runSync() else null

    override fun onAppOpened() {
        val s = _status.value
        if (s.setup != SyncSetup.Linked || s.isSyncing) return
        val last = s.lastSyncMillis
        if (last != null && clock.nowMillis() - last < MIN_FOREGROUND_INTERVAL_MS) return
        scope.launch { runSync() }
    }

    private suspend fun runSync(): SyncError? = syncLock.withLock {
        val repository = stateStore.load().repository ?: return null
        _status.update { it.copy(isSyncing = true) }
        try {
            engine.sync(repository)
            val now = clock.nowMillis()
            _status.update { it.copy(isSyncing = false, lastSyncMillis = now, error = null) }
            null
        } catch (e: SyncException) {
            _status.update { it.copy(isSyncing = false, error = e.error) }
            // Whatever is still pending is retried in the background when the failure may go away by itself.
            if (e.error.code.isRetryable && _status.value.pendingCount > 0) scheduler.schedulePush()
            e.error
        } catch (e: CancellationException) {
            _status.update { it.copy(isSyncing = false) }
            throw e
        } catch (e: Exception) {
            // Never show or log details here: they could come from a layer that knows the token.
            val error = SyncError(SyncErrorCode.Unknown)
            _status.update { it.copy(isSyncing = false, error = error) }
            error
        }
    }

    private fun fail(error: SyncError): SyncError {
        _status.update { it.copy(error = error) }
        return error
    }

    private companion object {
        const val MIN_FOREGROUND_INTERVAL_MS = 60_000L
    }
}
