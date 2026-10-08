package com.treadz.console.sync.service

import com.treadz.console.sync.FirstLinkSummary
import com.treadz.console.sync.SyncError
import com.treadz.console.sync.github.RepositoryRef
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.StateFlow

/** Where the device is in the setup of the synchronisation. */
enum class SyncSetup {
    /** No token (or it was cleared): the app works locally only, exactly as before. */
    NotConfigured,

    /** The token works and gives access to several private repositories: the user picks one from [SyncStatus.repositories]. */
    ChoosingRepository,

    /** A repository is chosen and this device has data to upload: waiting for [SyncService.confirmFirstLink] or [SyncService.cancelFirstLink]. */
    AwaitingFirstLinkConfirmation,

    /** Linked: changes are uploaded in the background, and downloaded when the app opens or on demand. */
    Linked,
}

/**
 * Everything the UI shows about the synchronisation. Immutable; [SyncService.status] emits a new one on every change.
 */
data class SyncStatus(
    val setup: SyncSetup = SyncSetup.NotConfigured,
    /** The private repositories to choose from (only in [SyncSetup.ChoosingRepository]). */
    val repositories: List<RepositoryRef> = emptyList(),
    /** The chosen repository, from the moment it is chosen. */
    val repository: RepositoryRef? = null,
    /** What linking would upload (only in [SyncSetup.AwaitingFirstLinkConfirmation]). */
    val firstLink: FirstLinkSummary? = null,
    /** A synchronisation is running right now. */
    val isSyncing: Boolean = false,
    /** Records changed on this device and not uploaded yet (0 when nothing waits). */
    val pendingCount: Int = 0,
    /** When the last synchronisation finished successfully (epoch ms), `null` if never. */
    val lastSyncMillis: Long? = null,
    /** The last attempt failed with this; cleared by the next success. A retry is scheduled by itself for retryable codes. */
    val error: SyncError? = null,
)

/**
 * The synchronisation as the UI sees it. Optional: until a token is configured nothing happens and the app is purely
 * local. Everything is `suspend` or reactive and safe to call from the main thread; the network work runs elsewhere.
 *
 * ## Setup flow
 * 1. The user pastes a GitHub token (fine-grained, limited to the data repository, with read and write access to
 *    "Contents"): [configure]. The token is validated by listing the private repositories it can use.
 *    - One private repository: it is used automatically (the user never types its name).
 *    - Several: [SyncStatus.setup] becomes [SyncSetup.ChoosingRepository]; the user picks one: [selectRepository].
 *    - None, or the token is rejected: an error is returned (and shown in [SyncStatus.error]) and nothing is stored.
 * 2. If this device already has data to upload, [SyncSetup.AwaitingFirstLinkConfirmation] shows what would be sent
 *    ([SyncStatus.firstLink]); [confirmFirstLink] uploads it (merging with whatever the repository holds, nothing is
 *    overwritten) and [cancelFirstLink] forgets the token and the repository again. Without local data to upload the
 *    device is linked right away.
 * 3. [SyncSetup.Linked]: from then on every change is queued for upload (background work, needs network only),
 *    changes of other devices are downloaded when the app opens ([onAppOpened]) and on [syncNow].
 *
 * [clear] removes the token and the link; local data is never touched by this.
 */
interface SyncService {

    val status: StateFlow<SyncStatus>

    /** Local ids of the workouts whose latest state is not uploaded yet (for a per-workout indicator in the history). */
    val pendingWorkoutIds: Flow<Set<Long>>

    /** Stores and validates [token]. Returns the error, or `null` on success (then see [status] for the next step). */
    suspend fun configure(token: String): SyncError?

    /** Chooses one of [SyncStatus.repositories] (or changes to another one: the link is then confirmed again). */
    suspend fun selectRepository(repository: RepositoryRef): SyncError?

    /** Uploads what [SyncStatus.firstLink] listed and links the device. Returns the sync error, if any. */
    suspend fun confirmFirstLink(): SyncError?

    /** Gives up linking: forgets the token and the repository. */
    suspend fun cancelFirstLink()

    /** Downloads what changed in the repository and uploads what changed here, now. Returns the error, or `null`. Does nothing when not linked. */
    suspend fun syncNow(): SyncError?

    /** Call when the app comes to the foreground: downloads changes (at most once a minute). Does not block. */
    fun onAppOpened()

    /** Removes the token, the link and the remembered repository state. Local data stays. */
    suspend fun clear()
}
