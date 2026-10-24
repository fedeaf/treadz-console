package com.treadz.console.userdata.sync

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map

/**
 * A synchronised record that is a JSON document owned by its feature (a program, a fitness test result): an identity,
 * an owner and a version around a document the sync never interprets.
 */
interface UserDocument {
    val uuid: String

    /** The owner's [SyncProfile.uuid]. */
    val userUuid: String
    val version: RecordVersion

    /** Tombstone: the record was deleted; [json] is empty then. */
    val deleted: Boolean

    /** The document as a JSON object, owned by its feature. Never interpreted by the sync. */
    val json: String
}

/**
 * Where the documents of one kind are kept, as far as the synchronisation is concerned. It follows the same rules as
 * [SyncStore]: pending flags, last writer wins, a remote change is never pending, a tombstone of an unknown record is
 * skipped, and records of an unknown or deleted owner are skipped. Records of the generic profile are not pending until
 * it is personalised.
 */
interface DocumentStore<D : UserDocument> {
    val pendingCount: Flow<Int>

    /** Documents (tombstones included) changed here since they were last synchronised. */
    suspend fun pending(): List<D>

    /** Merges downloaded documents with "last writer wins". Returns how many were applied. */
    suspend fun applyRemote(documents: List<D>): Int

    suspend fun markSynced(synced: List<SyncedRef>)
}

/** A training program of a user as the synchronisation sees it: the `ProgramJson` document with identity and version. */
data class ProgramDocument(
    /** The program's id. */
    override val uuid: String,
    override val userUuid: String,
    override val version: RecordVersion,
    override val deleted: Boolean,
    override val json: String,
) : UserDocument

/**
 * Where the programs of the users are kept, as far as the synchronisation is concerned. The Room data layer implements
 * it over the same rows as `ProgramRepository`; [InMemoryProgramDocuments] is a working stand-in for tests.
 */
interface ProgramDocumentStore : DocumentStore<ProgramDocument>

/** A [DocumentStore] in memory: no persistence, for tests. */
abstract class InMemoryDocumentStore<D : UserDocument> : DocumentStore<D> {
    private data class Entry<D>(val document: D, val pending: Boolean)

    private val entries = MutableStateFlow<Map<String, Entry<D>>>(emptyMap())

    override val pendingCount: Flow<Int> = entries.map { m -> m.values.count { it.pending } }

    /** Creates or replaces a document as a local change (pending). */
    fun put(document: D) {
        entries.value = entries.value + (document.uuid to Entry(document, pending = true))
    }

    /** Every document, tombstones included. */
    fun all(): List<D> = entries.value.values.map { it.document }.sortedBy { it.uuid }

    override suspend fun pending(): List<D> = entries.value.values.filter { it.pending }.map { it.document }

    override suspend fun applyRemote(documents: List<D>): Int {
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
            } else if (current.pending && !current.document.deleted && !d.deleted && sameContent(current.document, d)) {
                next = next + (d.uuid to Entry(d, pending = false))
            }
        }
        entries.value = next
        return applied
    }

    /**
     * Whether a newer pending local document says the same as an older remote one, which is then taken instead (nothing
     * to upload). Off by default: last writer wins.
     */
    protected open fun sameContent(local: D, remote: D): Boolean = false

    override suspend fun markSynced(synced: List<SyncedRef>) {
        var next = entries.value
        for (ref in synced) {
            val e = next[ref.uuid] ?: continue
            if (e.document.version == ref.version) next = next + (ref.uuid to e.copy(pending = false))
        }
        entries.value = next
    }
}

/** [ProgramDocumentStore] in memory: no persistence, for tests. */
class InMemoryProgramDocuments : InMemoryDocumentStore<ProgramDocument>(), ProgramDocumentStore
