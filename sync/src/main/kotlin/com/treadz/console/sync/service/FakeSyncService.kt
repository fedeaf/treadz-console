package com.treadz.console.sync.service

import com.treadz.console.sync.FirstLinkSummary
import com.treadz.console.sync.SyncError
import com.treadz.console.sync.SyncErrorCode
import com.treadz.console.sync.github.RepositoryRef
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

/**
 * A [SyncService] without network, storage or GitHub, for the UI's tests and screenshots. It follows the same setup
 * flow as the real one with scripted data:
 * - [configure] with a blank token or `"invalid"` fails with [SyncErrorCode.Unauthorized]; otherwise it looks at
 *   [privateRepositories] (none: [SyncErrorCode.NoPrivateRepository], one: that one, several: the user chooses).
 *   A failure only sets [SyncStatus.error]; when already linked, a working token keeps the link (and syncs) if
 *   [privateRepositories] still contains the linked repository, else fails with [SyncErrorCode.RepositoryNotFound];
 * - choosing a repository asks for confirmation if [localUploads] has something to upload, else it links;
 * - [syncNow] succeeds (or fails with [nextSyncError], once) and stamps [now] as the last sync time.
 * Use [emit] to put the status in any state for a screenshot.
 */
class FakeSyncService(
    initial: SyncStatus = SyncStatus(),
    var privateRepositories: List<RepositoryRef> = listOf(RepositoryRef("demo", "treadz-data", "main")),
    var localUploads: FirstLinkSummary? = null,
    var now: () -> Long = System::currentTimeMillis,
    /** Workouts reported as not uploaded yet. */
    pendingWorkouts: Set<Long> = emptySet(),
) : SyncService {

    private val _status = MutableStateFlow(initial)
    override val status: StateFlow<SyncStatus> = _status.asStateFlow()

    private val _pendingWorkoutIds = MutableStateFlow(pendingWorkouts)
    override val pendingWorkoutIds: Flow<Set<Long>> = _pendingWorkoutIds

    /** Error the next [syncNow] / [confirmFirstLink] fails with, once. */
    var nextSyncError: SyncError? = null

    /** Names of the calls received, in order (`"configure"`, `"syncNow"`...), for assertions. */
    val calls = mutableListOf<String>()

    fun emit(status: SyncStatus) {
        _status.value = status
    }

    fun setPendingWorkouts(ids: Set<Long>) {
        _pendingWorkoutIds.value = ids
    }

    override suspend fun configure(token: String): SyncError? {
        calls += "configure"
        _status.update { it.copy(error = null) }
        // Like the real service, a failure leaves the setup as it was and only reports the error.
        if (token.isBlank() || token.trim() == "invalid") return fail(SyncError(SyncErrorCode.Unauthorized))
        if (privateRepositories.isEmpty()) return fail(SyncError(SyncErrorCode.NoPrivateRepository))
        val current = _status.value
        if (current.setup == SyncSetup.Linked) {
            val linked = current.repository
            if (linked == null || privateRepositories.none { it.fullName.equals(linked.fullName, ignoreCase = true) }) {
                return fail(SyncError(SyncErrorCode.RepositoryNotFound))
            }
            return syncNow()
        }
        return if (privateRepositories.size == 1) {
            choose(privateRepositories.single())
        } else {
            _status.update { it.copy(setup = SyncSetup.ChoosingRepository, repositories = privateRepositories, error = null) }
            null
        }
    }

    override suspend fun selectRepository(repository: RepositoryRef): SyncError? {
        calls += "selectRepository"
        return choose(repository)
    }

    private fun choose(repository: RepositoryRef): SyncError? {
        val uploads = localUploads
        if (uploads != null && uploads.hasUploads) {
            _status.update {
                it.copy(
                    setup = SyncSetup.AwaitingFirstLinkConfirmation, repository = repository, repositories = emptyList(),
                    firstLink = uploads, error = null,
                )
            }
        } else {
            _status.update {
                it.copy(
                    setup = SyncSetup.Linked, repository = repository, repositories = emptyList(), firstLink = null, error = null,
                    lastSyncMillis = now(),
                )
            }
        }
        return null
    }

    override suspend fun confirmFirstLink(): SyncError? {
        calls += "confirmFirstLink"
        if (_status.value.setup != SyncSetup.AwaitingFirstLinkConfirmation) return null
        _status.update { it.copy(setup = SyncSetup.Linked, firstLink = null, error = null) }
        return syncNow()
    }

    override suspend fun cancelSetup() {
        calls += "cancelSetup"
        cancelUnlessLinked()
    }

    override suspend fun cancelFirstLink() {
        calls += "cancelFirstLink"
        cancelUnlessLinked()
    }

    private fun cancelUnlessLinked() {
        if (_status.value.setup != SyncSetup.Linked) _status.value = SyncStatus()
    }

    override suspend fun syncNow(): SyncError? {
        calls += "syncNow"
        if (_status.value.setup != SyncSetup.Linked) return null
        nextSyncError?.let {
            nextSyncError = null
            return fail(it)
        }
        _status.update { it.copy(isSyncing = false, pendingCount = 0, lastSyncMillis = now(), error = null) }
        _pendingWorkoutIds.value = emptySet()
        return null
    }

    override fun onAppOpened() {
        calls += "onAppOpened"
    }

    override suspend fun clear() {
        calls += "clear"
        _status.value = SyncStatus()
    }

    private fun fail(error: SyncError): SyncError {
        _status.update { it.copy(isSyncing = false, error = error) }
        return error
    }
}
