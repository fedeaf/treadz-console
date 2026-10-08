package com.treadz.console.sync.engine

import com.treadz.console.sync.FirstLinkSummary
import com.treadz.console.sync.SyncError
import com.treadz.console.sync.SyncErrorCode
import com.treadz.console.sync.SyncException
import com.treadz.console.sync.SyncReport
import com.treadz.console.sync.UserUpload
import com.treadz.console.sync.format.GitBlob
import com.treadz.console.sync.format.RecordCodec
import com.treadz.console.sync.format.RepoPaths
import com.treadz.console.sync.format.RepositoryReadme
import com.treadz.console.sync.github.BranchState
import com.treadz.console.sync.github.GitHubClient
import com.treadz.console.sync.github.GitHubException
import com.treadz.console.sync.github.GitHubFailure
import com.treadz.console.sync.github.RepositoryRef
import com.treadz.console.sync.github.TreeChange
import com.treadz.console.treadmill.control.Clock
import com.treadz.console.userdata.export.WorkoutCsv
import com.treadz.console.userdata.sync.FitnessTestDocument
import com.treadz.console.userdata.sync.FitnessTestDocumentStore
import com.treadz.console.userdata.sync.PendingChanges
import com.treadz.console.userdata.sync.ProgramDocument
import com.treadz.console.userdata.sync.ProgramDocumentStore
import com.treadz.console.userdata.sync.RemoteChanges
import com.treadz.console.userdata.sync.SyncProfile
import com.treadz.console.userdata.sync.SyncStore
import com.treadz.console.userdata.sync.SyncWeightEntry
import com.treadz.console.userdata.sync.SyncWorkout
import com.treadz.console.userdata.sync.SyncWorkoutData
import com.treadz.console.userdata.sync.SyncedRecords
import com.treadz.console.userdata.sync.SyncedRef
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import java.time.ZoneId

/**
 * One synchronisation cycle between the local data ([SyncStore], [ProgramDocumentStore], [FitnessTestDocumentStore]) and a branch of a GitHub
 * repository: **pull** what other devices changed, then **push** what changed here, in one commit.
 *
 * ## Pull
 * The tree of the branch's tip is compared, file by file (Git blob ids), with the state remembered from the last
 * cycle ([SyncState.files]); only new or changed files are downloaded, and merged with "last writer wins" per record
 * (see [SyncStore.applyRemote]). A file that has just moved to another folder (a renamed user) is recognised by its
 * unchanged content and not downloaded again. A file that disappears from the repository is **not** a deletion:
 * deletions are tombstones (see the repository README), so a missing file never removes local data.
 *
 * ## Push
 * The pending local records (see [SyncStore.pendingChanges]) become file changes against the tip that was just pulled:
 * new blobs, one tree, one commit, and the branch is moved **without force**. If another device pushed in the
 * meantime the update is refused; the cycle starts again (pull the new commit, rebuild, push), at most
 * [maxAttempts] times. Files whose content did not change are not touched, and nothing is committed when nothing
 * changed. Records are marked as synchronised only after the branch moved.
 *
 * ## The fitness block of `profile.json`
 * The fitness profile of a user is derived from the user's fitness test results (see `SyncProfile.fitness`), so
 * `profile.json` is rewritten not only when the profile changes but also whenever results of the user are uploaded.
 * The rewritten file keeps the profile's own version (`updated_at`, `updated_by`): a fitness change is not a profile
 * edit and can never win over, or hide, a concurrent edit of the profile on another device. Every device derives the
 * same block from the same results, so the rewrite is byte-identical wherever it is made; a file whose content did not
 * change is not uploaded, hence devices never keep rewriting each other's file. On download the block is ignored.
 *
 * ## Empty repository
 * The Git Data API cannot be used before the first commit, so the first commit is made with the Contents API
 * (the repository's `README.md`).
 *
 * @param pauseBeforeRetry waits between attempts after a lost race; tests replace it.
 */
class SyncEngine(
    private val client: GitHubClient,
    private val store: SyncStore,
    private val programs: ProgramDocumentStore,
    private val fitnessTests: FitnessTestDocumentStore,
    private val stateStore: SyncStateStore,
    private val clock: Clock = Clock.System,
    private val zone: ZoneId = ZoneId.systemDefault(),
    private val maxAttempts: Int = 5,
    private val pauseBeforeRetry: suspend (attempt: Int) -> Unit = { delay(300L * it) },
) {

    /**
     * What linking the device to the repository would upload. Local only, nothing is sent; the repository is looked
     * at just to count its users (`remoteUsers` is `null` if that fails).
     */
    suspend fun previewFirstLink(repo: RepositoryRef): FirstLinkSummary {
        val pending = store.pendingChanges()
        val programPending = programs.pending()
        val fitnessPending = fitnessTests.pending()
        val uuids = (pending.profiles.map { it.uuid } + pending.weights.map { it.userUuid } + pending.workouts.map { it.userUuid } +
            programPending.map { it.userUuid } + fitnessPending.map { it.userUuid }).distinct()
        val users = uuids.map { uuid ->
            val profile = pending.profiles.firstOrNull { it.uuid == uuid } ?: store.profile(uuid)
            UserUpload(
                name = profile?.name.orEmpty(),
                profile = pending.profiles.any { it.uuid == uuid },
                weightEntries = pending.weights.count { it.userUuid == uuid },
                workouts = pending.workouts.count { it.userUuid == uuid },
                programs = programPending.count { it.userUuid == uuid },
                fitnessTests = fitnessPending.count { it.userUuid == uuid },
            )
        }.sortedBy { it.name.lowercase() }
        val remoteUsers = try {
            val head = client.branchState(repo, repo.defaultBranch)
            if (head is BranchState.Exists) {
                val files = client.files(repo, client.treeShaOfCommit(repo, head.commitSha))
                files.mapNotNull { (RepoPaths.classify(it.path) as? RepoPaths.Kind.Profile)?.folder }.toSet().size
            } else {
                0
            }
        } catch (e: GitHubException) {
            if (e.failure == GitHubFailure.Unauthorized) throw translate(e, writing = false)
            null
        }
        return FirstLinkSummary(users, remoteUsers)
    }

    /** Runs a full cycle on [branch] (the default branch of [repo] unless told otherwise). Throws [SyncException]. */
    suspend fun sync(repo: RepositoryRef, branch: String = repo.defaultBranch): SyncReport {
        var writing = false
        var downloaded = 0
        val createdBlobs = HashSet<String>()
        try {
            for (attempt in 1..maxAttempts) {
                writing = false
                var state = stateStore.load()
                if (state.repository != repo) state = SyncState(repository = repo, linked = state.linked)

                // ---- the tip ----
                var head: String
                when (val branchState = client.branchState(repo, branch)) {
                    is BranchState.Exists -> head = branchState.commitSha
                    BranchState.Missing, BranchState.EmptyRepository -> {
                        if (store.pendingChanges().isEmpty && programs.pending().isEmpty() && fitnessTests.pending().isEmpty()) {
                            stateStore.save(state.copy(lastSyncMillis = clock.nowMillis()))
                            return SyncReport(0, 0, null, attempt)
                        }
                        writing = true
                        head = try {
                            client.createFile(repo, branch, RepoPaths.README, RepositoryReadme.TEXT.toByteArray(Charsets.UTF_8), FIRST_COMMIT_MESSAGE)
                        } catch (e: GitHubException) {
                            if (e.failure == GitHubFailure.ValidationFailed) {
                                // Another device created the first commit at the same moment: start again from it.
                                pauseBeforeRetry(attempt)
                                continue
                            }
                            throw e
                        }
                        writing = false
                    }
                }

                // ---- pull ----
                val files: Map<String, String>
                if (head == state.headCommitSha) {
                    files = state.files
                } else {
                    val treeSha = client.treeShaOfCommit(repo, head)
                    files = client.files(repo, treeSha).associate { it.path to it.sha }
                    val pulled = pull(repo, state, files)
                    downloaded += pulled.applied
                    state = state.copy(headCommitSha = head, files = files, workoutPaths = pulled.workoutPaths)
                    stateStore.save(state)
                }

                // ---- push ----
                val pending = store.pendingChanges()
                val programPending = programs.pending()
                val fitnessPending = fitnessTests.pending()
                if (pending.isEmpty && programPending.isEmpty() && fitnessPending.isEmpty()) {
                    stateStore.save(state.copy(lastSyncMillis = clock.nowMillis()))
                    return SyncReport(downloaded, 0, null, attempt)
                }
                val plan = buildPlan(files, state, pending, programPending, fitnessPending)
                val effective = plan.effectiveChanges(files)
                if (effective.isEmpty()) {
                    markSynced(pending, programPending, fitnessPending)
                    stateStore.save(state.copy(workoutPaths = plan.workoutPaths, lastSyncMillis = clock.nowMillis()))
                    return SyncReport(downloaded, 0, null, attempt)
                }

                writing = true
                for (change in effective.filterIsInstance<Effective.Upload>()) {
                    if (createdBlobs.add(change.sha)) {
                        val created = client.createBlob(repo, change.bytes)
                        check(created == change.sha) { "GitHub stored a different blob than the one sent" }
                    }
                }
                val tree = client.createTree(
                    repo,
                    client.treeShaOfCommit(repo, head),
                    effective.map { it.toTreeChange() },
                )
                val message = CommitMessages.build(pending, programPending, fitnessPending, plan.names, zone)
                val commit = client.createCommit(repo, message, tree, head)
                if (client.fastForward(repo, branch, commit)) {
                    markSynced(pending, programPending, fitnessPending)
                    val newFiles = HashMap(files)
                    effective.forEach { it.applyTo(newFiles) }
                    stateStore.save(
                        state.copy(headCommitSha = commit, files = newFiles, workoutPaths = plan.workoutPaths, lastSyncMillis = clock.nowMillis()),
                    )
                    return SyncReport(downloaded, pending.count + programPending.size + fitnessPending.size, commit, attempt)
                }
                // Somebody pushed first: pull what they did and try again.
                if (attempt < maxAttempts) pauseBeforeRetry(attempt)
            }
            throw SyncException(SyncError(SyncErrorCode.Conflict))
        } catch (e: GitHubException) {
            throw translate(e, writing)
        } catch (e: CancellationException) {
            throw e
        }
    }

    // ---------------------------------------------------------------- pull

    private class Pulled(val applied: Int, val workoutPaths: Map<String, String>)

    private suspend fun pull(repo: RepositoryRef, state: SyncState, files: Map<String, String>): Pulled {
        val previous = state.files
        val workoutPaths = HashMap(state.workoutPaths)

        // Files that only moved (a renamed user folder): same content under a path that is gone.
        val goneBySha = previous.filterKeys { it !in files }.entries.groupBy({ it.value }, { it.key }).mapValues { ArrayDeque(it.value) }
        val moved = HashSet<String>()
        for ((path, sha) in files) {
            if (previous[path] == sha) continue
            val old = goneBySha[sha]?.removeFirstOrNull() ?: continue
            moved += path
            for ((uuid, p) in workoutPaths.toMap()) if (p == old) workoutPaths[uuid] = path
        }

        val profiles = ArrayList<SyncProfile>()
        val weights = ArrayList<SyncWeightEntry>()
        val workouts = ArrayList<SyncWorkoutData>()
        val documents = ArrayList<ProgramDocument>()
        val fitness = ArrayList<FitnessTestDocument>()
        for ((path, sha) in files.entries.sortedBy { it.key }) {
            if (previous[path] == sha || path in moved) continue
            val kind = RepoPaths.classify(path)
            if (kind == RepoPaths.Kind.Other) continue
            val folder = when (kind) {
                is RepoPaths.Kind.Profile -> kind.folder
                is RepoPaths.Kind.Weights -> kind.folder
                is RepoPaths.Kind.WorkoutSummary -> kind.folder
                is RepoPaths.Kind.Program -> kind.folder
                is RepoPaths.Kind.FitnessTest -> kind.folder
                RepoPaths.Kind.Other -> continue
            }
            val folderUser = RepoPaths.uuidOfFolder(folder) ?: continue
            val bytes = client.blob(repo, sha)
            when (kind) {
                is RepoPaths.Kind.Profile -> RecordCodec.parseProfile(bytes)?.let { profiles += it }
                is RepoPaths.Kind.Weights -> weights += RecordCodec.parseWeightCsv(bytes, folderUser)
                is RepoPaths.Kind.WorkoutSummary -> RecordCodec.parseWorkout(bytes, folderUser)?.let { workout ->
                    workoutPaths[workout.uuid] = path
                    val csvPath = RepoPaths.csvOf(path)
                    val samples = if (workout.deleted) emptyList() else files[csvPath]?.let { WorkoutCsv.read(String(client.blob(repo, it), Charsets.UTF_8)) }.orEmpty()
                    workouts += SyncWorkoutData(workout, samples)
                }
                is RepoPaths.Kind.Program -> RecordCodec.parseProgram(bytes, folderUser)?.let { documents += it }
                is RepoPaths.Kind.FitnessTest -> RecordCodec.parseFitnessTest(bytes, folderUser)?.let { fitness += it }
                RepoPaths.Kind.Other -> Unit
            }
        }
        val result = store.applyRemote(RemoteChanges(profiles, weights, workouts))
        val programsApplied = if (documents.isEmpty()) 0 else programs.applyRemote(documents)
        val fitnessApplied = if (fitness.isEmpty()) 0 else fitnessTests.applyRemote(fitness)
        return Pulled(result.applied + programsApplied + fitnessApplied, workoutPaths)
    }

    // ---------------------------------------------------------------- push

    private sealed interface Change {
        class Put(val bytes: ByteArray) : Change
        class Copy(val sha: String) : Change
        data object Delete : Change
    }

    private sealed interface Effective {
        val path: String

        class Upload(override val path: String, val bytes: ByteArray, val sha: String) : Effective
        class Reference(override val path: String, val sha: String) : Effective
        class Remove(override val path: String) : Effective

        fun toTreeChange(): TreeChange = when (this) {
            is Upload -> TreeChange.Put(path, sha)
            is Reference -> TreeChange.Put(path, sha)
            is Remove -> TreeChange.Delete(path)
        }

        fun applyTo(files: MutableMap<String, String>) {
            when (this) {
                is Upload -> files[path] = sha
                is Reference -> files[path] = sha
                is Remove -> files.remove(path)
            }
        }
    }

    private class Plan(initialWorkoutPaths: Map<String, String>) {
        val changes = LinkedHashMap<String, Change>()
        val workoutPaths = HashMap(initialWorkoutPaths)
        val names = HashMap<String, String>()

        fun put(path: String, bytes: ByteArray) { changes[path] = Change.Put(bytes) }
        fun copy(path: String, sha: String) { changes[path] = Change.Copy(sha) }
        fun delete(path: String) { changes[path] = Change.Delete }

        /** The changes that really alter the tree [files]. */
        fun effectiveChanges(files: Map<String, String>): List<Effective> = changes.mapNotNull { (path, change) ->
            when (change) {
                is Change.Put -> GitBlob.sha(change.bytes).let { sha -> if (files[path] == sha) null else Effective.Upload(path, change.bytes, sha) }
                is Change.Copy -> if (files[path] == change.sha) null else Effective.Reference(path, change.sha)
                Change.Delete -> if (path in files) Effective.Remove(path) else null
            }
        }
    }

    private suspend fun buildPlan(
        files: Map<String, String>,
        state: SyncState,
        pending: PendingChanges,
        programPending: List<ProgramDocument>,
        fitnessPending: List<FitnessTestDocument>,
    ): Plan {
        val plan = Plan(state.workoutPaths)
        val folders = HashMap<String, String>() // user uuid -> existing folder
        for (path in files.keys.sorted()) {
            val folder = (RepoPaths.classify(path) as? RepoPaths.Kind.Profile)?.folder ?: continue
            RepoPaths.uuidOfFolder(folder)?.let { folders.putIfAbsent(it, folder) }
        }
        val userUuids = (pending.profiles.map { it.uuid } + pending.weights.map { it.userUuid } + pending.workouts.map { it.userUuid } +
            programPending.map { it.userUuid } + fitnessPending.map { it.userUuid }).distinct()

        for (userUuid in userUuids) {
            val pendingProfile = pending.profiles.firstOrNull { it.uuid == userUuid }
            val profile = pendingProfile ?: store.profile(userUuid) ?: continue
            plan.names[userUuid] = profile.name
            val existing = folders[userUuid]

            if (profile.deleted) {
                // Tombstone: only profile.json is left, with the last name, in the folder that exists.
                if (existing != null) {
                    files.keys.filter { it.startsWith(RepoPaths.userPrefix(existing)) && it != RepoPaths.profile(existing) }.forEach(plan::delete)
                    plan.put(RepoPaths.profile(existing), RecordCodec.profile(profile))
                }
                continue
            }

            val folder = RepoPaths.userFolder(profile.name, userUuid)
            if (existing != null && existing != folder) renameFolder(plan, files, existing, folder)
            val profilePath = RepoPaths.profile(folder)
            // Changed results change the fitness block of the profile file (same version, new content).
            val fitnessChanged = fitnessPending.any { it.userUuid == userUuid }
            if (pendingProfile != null || fitnessChanged || existing == null || (existing == folder && profilePath !in files)) {
                plan.put(profilePath, RecordCodec.profile(profile))
            }

            if (pending.weights.any { it.userUuid == userUuid }) {
                plan.put(RepoPaths.weights(folder), RecordCodec.weightCsv(store.weightEntries(userUuid)))
            }
            for (workout in pending.workouts.filter { it.userUuid == userUuid }) planWorkout(plan, files, folder, workout)
            for (document in programPending.filter { it.userUuid == userUuid }) {
                plan.put(RepoPaths.program(folder, document.uuid), RecordCodec.program(document))
            }
            for (document in fitnessPending.filter { it.userUuid == userUuid }) {
                plan.put(RepoPaths.fitnessTest(folder, document.uuid), RecordCodec.fitnessTest(document))
            }
        }
        if (RepoPaths.README !in files) plan.put(RepoPaths.README, RepositoryReadme.TEXT.toByteArray(Charsets.UTF_8))
        return plan
    }

    /** Moves a user's whole folder by referencing the existing blobs: nothing is uploaded again. */
    private fun renameFolder(plan: Plan, files: Map<String, String>, from: String, to: String) {
        val fromPrefix = RepoPaths.userPrefix(from)
        val toPrefix = RepoPaths.userPrefix(to)
        for ((path, sha) in files) {
            if (!path.startsWith(fromPrefix)) continue
            val newPath = toPrefix + path.removePrefix(fromPrefix)
            plan.copy(newPath, sha)
            plan.delete(path)
            for ((uuid, p) in plan.workoutPaths.toMap()) if (p == path) plan.workoutPaths[uuid] = newPath
        }
    }

    private suspend fun planWorkout(plan: Plan, files: Map<String, String>, folder: String, workout: SyncWorkout) {
        var path = plan.workoutPaths[workout.uuid]
        if (workout.deleted) {
            // A tombstone stays where the workout was; one that was never uploaded needs nothing.
            if (path == null) return
            plan.put(path, RecordCodec.workout(workout))
            plan.delete(RepoPaths.csvOf(path))
            return
        }
        val samples = store.workoutSamples(workout.uuid)
        if (path != null && (RepoPaths.classify(path) as? RepoPaths.Kind.WorkoutSummary)?.folder != folder) {
            // Assigned to another user: it moves with its owner.
            plan.delete(path)
            plan.delete(RepoPaths.csvOf(path))
            path = null
        }
        if (path == null) path = freshWorkoutPath(plan, files, folder, workout.startedAtMillis)
        plan.put(path, RecordCodec.workout(workout))
        plan.put(RepoPaths.csvOf(path), WorkoutCsv.write(samples).toByteArray(Charsets.UTF_8))
        plan.workoutPaths[workout.uuid] = path
    }

    private fun freshWorkoutPath(plan: Plan, files: Map<String, String>, folder: String, startedAtMillis: Long): String {
        val base = RepoPaths.workoutBase(folder, startedAtMillis, zone)
        var candidate = "$base.json"
        var n = 2
        while (candidate in files || candidate in plan.changes || plan.workoutPaths.containsValue(candidate)) {
            candidate = "${base}_${n++}.json"
        }
        return candidate
    }

    private suspend fun markSynced(pending: PendingChanges, programPending: List<ProgramDocument>, fitnessPending: List<FitnessTestDocument>) {
        store.markSynced(
            SyncedRecords(
                profiles = pending.profiles.map { SyncedRef(it.uuid, it.version) },
                weights = pending.weights.map { SyncedRef(it.uuid, it.version) },
                workouts = pending.workouts.map { SyncedRef(it.uuid, it.version) },
            ),
        )
        if (programPending.isNotEmpty()) programs.markSynced(programPending.map { SyncedRef(it.uuid, it.version) })
        if (fitnessPending.isNotEmpty()) fitnessTests.markSynced(fitnessPending.map { SyncedRef(it.uuid, it.version) })
    }

    // ---------------------------------------------------------------- errors

    private fun translate(e: GitHubException, writing: Boolean) = SyncException(e.toSyncError(writing), e)

    private companion object {
        const val FIRST_COMMIT_MESSAGE = "Initialise TreadZ data repository"
    }
}

/** Maps a failed request to the error shown to the user. [writing]: the failure happened while changing the repository. */
internal fun GitHubException.toSyncError(writing: Boolean = false): SyncError {
    val code = when (failure) {
        GitHubFailure.Network -> SyncErrorCode.NoNetwork
        GitHubFailure.Unauthorized -> SyncErrorCode.Unauthorized
        GitHubFailure.Forbidden, GitHubFailure.NotFound -> if (writing) SyncErrorCode.NoWriteAccess else SyncErrorCode.RepositoryNotFound
        GitHubFailure.RateLimited -> SyncErrorCode.RateLimited
        GitHubFailure.ServerError -> SyncErrorCode.ServerError
        GitHubFailure.Conflict -> SyncErrorCode.Conflict
        GitHubFailure.TooLarge -> SyncErrorCode.RepositoryTooLarge
        GitHubFailure.ValidationFailed, GitHubFailure.InvalidResponse -> SyncErrorCode.InvalidResponse
    }
    return SyncError(code, retryAfterMillis)
}
