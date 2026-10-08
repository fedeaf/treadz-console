package com.treadz.console.sync.engine

import com.treadz.console.sync.github.RepositoryRef
import org.json.JSONObject
import java.io.File
import java.io.IOException

/**
 * What this device remembers about its link with the repository. The part about files is only an optimisation (what
 * was already downloaded); losing it makes the next synchronisation download everything again and merge it, which
 * is always safe.
 */
data class SyncState(
    /** The repository this device is linked to, or `null` if none was chosen yet. */
    val repository: RepositoryRef? = null,
    /** The first link was confirmed (the existing local data may be uploaded). */
    val linked: Boolean = false,
    val lastSyncMillis: Long? = null,
    /** Commit that [files] describes. */
    val headCommitSha: String? = null,
    /** Blob SHA of every file of the repository at [headCommitSha]. */
    val files: Map<String, String> = emptyMap(),
    /** Path of the summary file of every workout, by workout uuid. */
    val workoutPaths: Map<String, String> = emptyMap(),
)

interface SyncStateStore {
    suspend fun load(): SyncState

    suspend fun save(state: SyncState)
}

class InMemorySyncStateStore(initial: SyncState = SyncState()) : SyncStateStore {
    @Volatile private var state = initial

    override suspend fun load(): SyncState = state

    override suspend fun save(state: SyncState) {
        this.state = state
    }
}

/** [SyncStateStore] in a JSON file, replaced atomically on every save. Contains no secrets. */
class FileSyncStateStore(private val file: File) : SyncStateStore {

    override suspend fun load(): SyncState = try {
        if (!file.isFile) SyncState() else decode(file.readText())
    } catch (e: Exception) {
        SyncState() // unreadable: start again, it is only a cache plus the choice of repository
    }

    override suspend fun save(state: SyncState) {
        val temp = File(file.parentFile, file.name + ".tmp")
        temp.writeText(encode(state))
        if (!temp.renameTo(file)) {
            file.delete()
            if (!temp.renameTo(file)) throw IOException("Could not store the synchronisation state")
        }
    }

    internal companion object {
        fun encode(s: SyncState): String = JSONObject().apply {
            s.repository?.let {
                put("repository", JSONObject().put("owner", it.owner).put("name", it.name).put("default_branch", it.defaultBranch))
            }
            put("linked", s.linked)
            s.lastSyncMillis?.let { put("last_sync_millis", it) }
            s.headCommitSha?.let { put("head_commit", it) }
            put("files", JSONObject(s.files))
            put("workout_paths", JSONObject(s.workoutPaths))
        }.toString()

        fun decode(text: String): SyncState {
            val o = JSONObject(text)
            fun map(name: String): Map<String, String> {
                val m = o.optJSONObject(name) ?: return emptyMap()
                return m.keys().asSequence().associateWith { m.getString(it) }
            }
            return SyncState(
                repository = o.optJSONObject("repository")?.let {
                    RepositoryRef(it.getString("owner"), it.getString("name"), it.optString("default_branch", "main"))
                },
                linked = o.optBoolean("linked", false),
                lastSyncMillis = if (o.has("last_sync_millis")) o.getLong("last_sync_millis") else null,
                headCommitSha = o.optString("head_commit").ifEmpty { null },
                files = map("files"),
                workoutPaths = map("workout_paths"),
            )
        }
    }
}
