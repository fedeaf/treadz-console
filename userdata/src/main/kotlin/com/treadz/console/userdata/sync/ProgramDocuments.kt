package com.treadz.console.userdata.sync

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/**
 * A user's training program as the synchronisation sees it: an opaque JSON document with an identity and a version.
 * The program model arrives later; until then nothing but this envelope is known to the sync.
 */
data class ProgramDocument(
    val uuid: String,
    /** The owner's [SyncProfile.uuid]. */
    val userUuid: String,
    val version: RecordVersion,
    /** Tombstone: the program was deleted; [json] is empty then. */
    val deleted: Boolean,
    /** The program as a JSON object, owned by the program feature. Never interpreted by the sync. */
    val json: String,
)

/**
 * Where the programs of the users are kept, as far as the synchronisation is concerned. The program feature will
 * implement it on top of its own storage; [InMemoryProgramDocuments] is a working stand-in.
 * It follows the same rules as [SyncStore]: pending flags, last writer wins, a remote change is never pending.
 */
interface ProgramDocumentStore {
    val pendingCount: Flow<Int>

    suspend fun pending(): List<ProgramDocument>

    /** Merges downloaded documents with "last writer wins". Returns how many were applied. */
    suspend fun applyRemote(documents: List<ProgramDocument>): Int

    suspend fun markSynced(synced: List<SyncedRef>)
}

/** [ProgramDocumentStore] in memory: no persistence, for tests and until programs exist. */
class InMemoryProgramDocuments : ProgramDocumentStore {
    private data class Entry(val document: ProgramDocument, val pending: Boolean)

    private val entries = MutableStateFlow<Map<String, Entry>>(emptyMap())

    override val pendingCount: Flow<Int> = entries.map { m -> m.values.count { it.pending } }

    /** Creates or replaces a program as a local change (pending). */
    fun put(document: ProgramDocument) {
        entries.value = entries.value + (document.uuid to Entry(document, pending = true))
    }

    /** Every document, tombstones included. */
    fun all(): List<ProgramDocument> = entries.value.values.map { it.document }.sortedBy { it.uuid }

    override suspend fun pending(): List<ProgramDocument> = entries.value.values.filter { it.pending }.map { it.document }

    override suspend fun applyRemote(documents: List<ProgramDocument>): Int {
        var applied = 0
        var next = entries.value
        for (d in documents) {
            val current = next[d.uuid]
            if (current == null) {
                if (!d.deleted) {
                    next = next + (d.uuid to Entry(d, pending = false))
                    applied++
                }
            } else if (d.version > current.document.version) {
                next = next + (d.uuid to Entry(d, pending = false))
                applied++
            } else if (d.version == current.document.version) {
                next = next + (d.uuid to current.copy(pending = false))
            }
        }
        entries.value = next
        return applied
    }

    override suspend fun markSynced(synced: List<SyncedRef>) {
        var next = entries.value
        for (ref in synced) {
            val e = next[ref.uuid] ?: continue
            if (e.document.version == ref.version) next = next + (ref.uuid to e.copy(pending = false))
        }
        entries.value = next
    }
}
