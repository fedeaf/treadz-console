package com.treadz.console.userdata.sync

import com.treadz.console.userdata.WorkoutSample
import kotlinx.coroutines.flow.Flow

/**
 * The port the `:sync` module uses to read local changes and to merge remote ones. The UI does not use it.
 *
 * ## Model
 * Profiles, weight entries and workouts have a global identity ([SyncProfile.uuid] and so on) next to their local
 * `Long` id, a [RecordVersion] and a tombstone flag. Every local change (create, edit, delete) stamps a new version and
 * flags the record as *pending*; [markSynced] clears the flag.
 *
 * ## What is never uploaded
 * - The **generic profile** (and, with it, its weights and workouts) until it is personalised: then it is an ordinary
 *   pending profile. Placeholder weights never are.
 * - A workout **in progress**: it becomes pending when it ends. A workout without an owner is not uploaded either
 *   until it is assigned to a user.
 * - The **active user** is a per-device choice and is not part of the data.
 *
 * ## Deletions
 * Deleting is soft: the UI sees nothing, but the record stays as a tombstone until it has been synchronised (and
 * forever after, as a record of the deletion). A deleted profile takes its weights and workouts with it; they are not
 * pending individually.
 */
interface SyncStore {

    /** Stable id of this installation, stamped on every change (see [RecordVersion.deviceId]). */
    val deviceId: String

    /** Number of eligible pending records; emits on every change. */
    val pendingCount: Flow<Int>

    /** Local ids of the workouts whose latest state has not been uploaded yet (for a per-workout indicator). */
    val pendingWorkoutIds: Flow<Set<Long>>

    /** A profile by its global id, tombstones included; `null` if unknown. */
    suspend fun profile(uuid: String): SyncProfile?

    /** Snapshot of the eligible pending records. */
    suspend fun pendingChanges(): PendingChanges

    /**
     * Every weight entry of a profile, tombstones included and placeholders excluded: the content of its weight log
     * file. Empty for an unknown profile.
     */
    suspend fun weightEntries(userUuid: String): List<SyncWeightEntry>

    /** Per-second samples of a workout, ordered by `seq`; empty for a tombstone or an unknown workout. */
    suspend fun workoutSamples(workoutUuid: String): List<WorkoutSample>

    /**
     * Merges records downloaded from the repository, in one transaction. They are applied with "last writer wins"
     * per record (greater [RecordVersion]); records applied or equal are not pending afterwards, so they are never
     * uploaded back. Records that are newer locally are kept (and stay pending).
     *
     * - Unknown records are created; a tombstone of an unknown record is skipped.
     * - A deleted profile stays deleted: **a profile tombstone always wins** over a later edit of the profile (its
     *   weights and workouts are gone with it, so it cannot be restored). Deleting a profile also clears the active
     *   user if it was that profile, and, if it was the last one, recreates the generic profile.
     * - Weights and workouts whose owner is unknown or deleted here are skipped.
     * - If [dropUnusedGeneric] is set and this merge **created** real profiles, an untouched generic profile (no
     *   workouts, only the placeholder weight) is deleted: a fresh device linking to a repository should not keep a
     *   stray "User". If it was the active user, nobody is active afterwards (the UI asks who trains).
     */
    suspend fun applyRemote(changes: RemoteChanges, dropUnusedGeneric: Boolean = true): ApplyResult

    /**
     * Clears the pending flag of records that were uploaded. A record that changed again since [SyncedRef.version]
     * stays pending.
     */
    suspend fun markSynced(synced: SyncedRecords)
}
