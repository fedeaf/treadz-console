package com.treadz.console.userdata.sync

import com.treadz.console.programs.fitness.FitnessTestJson

/** A fitness test result of a user as the synchronisation sees it: the `FitnessTestJson` document with identity and version. */
data class FitnessTestDocument(
    /** The result's id. */
    override val uuid: String,
    override val userUuid: String,
    override val version: RecordVersion,
    override val deleted: Boolean,
    override val json: String,
) : UserDocument

/**
 * Where the fitness test results of the users are kept, as far as the synchronisation is concerned. The Room data
 * layer implements it over the same rows as `FitnessTestRepository`.
 *
 * Changing the results changes the user's fitness profile, which is derived data in its own file (`fitness.json`, see
 * [SyncStore.fitness]): the sync compares its fingerprint with the results present on the device on every cycle.
 *
 * Besides "last writer wins": a pending local result that is newer than the remote one but **says the same** (two
 * devices recalculated the same result, each before seeing the other's) takes the remote version and is no longer
 * pending, so the file is not rewritten for its time stamp alone.
 */
interface FitnessTestDocumentStore : DocumentStore<FitnessTestDocument>

/** [FitnessTestDocumentStore] in memory: no persistence, for tests. */
class InMemoryFitnessTestDocuments : InMemoryDocumentStore<FitnessTestDocument>(), FitnessTestDocumentStore {
    override fun sameContent(local: FitnessTestDocument, remote: FitnessTestDocument): Boolean {
        val left = runCatching { FitnessTestJson.decode(local.json) }.getOrNull() ?: return false
        return left == runCatching { FitnessTestJson.decode(remote.json) }.getOrNull()
    }
}
