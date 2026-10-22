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
import com.treadz.console.userdata.FitnessProfiles
import com.treadz.console.userdata.FitnessRecalculator
import com.treadz.console.userdata.WorkoutAnalysisRecalculator
import com.treadz.console.userdata.export.WorkoutCsv
import com.treadz.console.userdata.sync.FitnessTestDocument
import com.treadz.console.userdata.sync.FitnessTestDocumentStore
import com.treadz.console.userdata.sync.PendingChanges
import com.treadz.console.userdata.sync.ProgramDocument
import com.treadz.console.userdata.sync.ProgramDocumentStore
import com.treadz.console.userdata.sync.RecordVersion
import com.treadz.console.userdata.sync.RemoteChanges
import com.treadz.console.userdata.sync.SyncProfile
import com.treadz.console.userdata.sync.SyncRestingHeartRate
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
 * ## The resting heart rate history
 * Measurements go to `resting-heart-rate.csv` of the user with the rules of `weight.csv` (one row per record, whole file
 * rewritten when a record changes, tombstones for deletions). `profile.json` does not show their reference any more: it
 * is derived, and the csv holds everything needed to compute it. A profile file with `resting_hr_bpm` (written by an older
 * version) in a repository that has no measurements file for the user was written before the history existed: its value
 * becomes the user's first manual measurement (the same record on every device).
 *
 * ## The fitness profile (`fitness.json`)
 * The fitness profile of a user is derived data (see [SyncStore.fitness]): the deterministic snapshot of the user's fitness
 * test results, with the **fingerprint** of its inputs (the results present on the device and the version of the
 * calculation rules). It is **never merged with "last writer wins"**. On every cycle, for every user of the repository
 * (also when nothing else changed here, also right after a pull that brought tombstones), the device compares its own
 * fingerprint with the one in the file:
 *  - the same: nothing is written (two devices with the same data never rewrite each other's file);
 *  - different, or the file is missing: the device computes the block and writes it **in the same commit** as its other
 *    changes, with `updated_at`/`updated_by` of this write (the time the content last changed);
 *  - the file was written with a higher rules version than this app's (an older app on this device): it is left alone;
 *  - the user has no usable result: the file is removed (a deleted user loses it with the rest of the folder).
 * What is known about the files is kept in [SyncState.fitnessBlobs], so an idle cycle makes no extra request.
 * `profile.json` does not carry the block: it only changes when the user edits the profile.
 *
 * ## Empty repository
 * The Git Data API cannot be used before the first commit, so the first commit is made with the Contents API
 * (the repository's `README.md`).
 *
 * ## Recalculation after a pull
 * A pull that brought fitness test results or workouts linked to a test runs the [recalculation] (when given) before the
 * push: results written by an older app, or whose workout has just arrived, get their derived values (VO2max of the
 * walking test, thresholds) and go up in the same cycle. Every device derives the same document from the same data, so
 * when two devices do it the last writer wins and nobody rewrites it again (the result then has the current algorithm
 * version). A failure there never fails the synchronisation.
 *
 * A pull that brought anything the per-workout analyses are computed from (workouts, weights, resting heart rate
 * measurements, profiles or fitness test results) then runs the [analysisRecalculation] (when given), after the one above
 * since the recalculated results change the context of a workout. The analyses are a local cache and never synchronised,
 * so nothing is pushed because of them; a failure there never fails the synchronisation either.
 *
 * @param pauseBeforeRetry waits between attempts after a lost race; tests replace it.
 * @param recalculation the stored-results recalculation to run after a pull that brought results or test workouts.
 * @param analysisRecalculation the workout analysis recalculation to run after a pull that brought workouts or their context.
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
    private val recalculation: FitnessRecalculator? = null,
    private val analysisRecalculation: WorkoutAnalysisRecalculator? = null,
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
            pending.restingHeartRates.map { it.userUuid } + programPending.map { it.userUuid } + fitnessPending.map { it.userUuid }).distinct()
        val users = uuids.map { uuid ->
            val profile = pending.profiles.firstOrNull { it.uuid == uuid } ?: store.profile(uuid)
            UserUpload(
                name = profile?.name.orEmpty(),
                profile = pending.profiles.any { it.uuid == uuid },
                weightEntries = pending.weights.count { it.userUuid == uuid },
                workouts = pending.workouts.count { it.userUuid == uuid },
                programs = programPending.count { it.userUuid == uuid },
                fitnessTests = fitnessPending.count { it.userUuid == uuid },
                restingHeartRates = pending.restingHeartRates.count { it.userUuid == uuid },
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
                    if (pulled.testData) recalculate()
                    if (pulled.analysisData) recalculateAnalyses()
                    downloaded += pulled.applied
                    state = state.copy(headCommitSha = head, files = files, workoutPaths = pulled.workoutPaths)
                    stateStore.save(state)
                }

                // ---- push ----
                val pending = store.pendingChanges()
                val programPending = programs.pending()
                val fitnessPending = fitnessTests.pending()
                val plan = buildPlan(repo, files, state, pending, programPending, fitnessPending)
                val effective = plan.effectiveChanges(files)
                val nothingPending = pending.isEmpty && programPending.isEmpty() && fitnessPending.isEmpty()
                if (effective.isEmpty()) {
                    if (!nothingPending) markSynced(pending, programPending, fitnessPending)
                    stateStore.save(state.copy(workoutPaths = plan.workoutPaths, fitnessBlobs = plan.blobsIn(files), lastSyncMillis = clock.nowMillis()))
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
                val message = CommitMessages.build(pending, programPending, fitnessPending, plan.names, zone, plan.derivedFitness)
                val commit = client.createCommit(repo, message, tree, head)
                if (client.fastForward(repo, branch, commit)) {
                    if (!nothingPending) markSynced(pending, programPending, fitnessPending)
                    val newFiles = HashMap(files)
                    effective.forEach { it.applyTo(newFiles) }
                    stateStore.save(
                        state.copy(
                            headCommitSha = commit,
                            files = newFiles,
                            workoutPaths = plan.workoutPaths,
                            fitnessBlobs = plan.blobsIn(newFiles),
                            lastSyncMillis = clock.nowMillis(),
                        ),
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

    /**
     * @property testData The pull brought fitness test results or workouts linked to a test.
     * @property analysisData The pull brought workouts, weights, resting heart rate measurements, profiles or fitness test results.
     */
    private class Pulled(val applied: Int, val workoutPaths: Map<String, String>, val testData: Boolean, val analysisData: Boolean)

    private suspend fun recalculateAnalyses() {
        try {
            analysisRecalculation?.recalculate()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Derived data only: the analyses stay as they are and the next change or app start tries again.
        }
    }

    private suspend fun recalculate() {
        try {
            recalculation?.recalculate()
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            // Derived values only: the results stay as they are and the next pull or app start tries again.
        }
    }

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
        val restingHeartRates = ArrayList<SyncRestingHeartRate>()
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
                is RepoPaths.Kind.RestingHeartRates -> kind.folder
                is RepoPaths.Kind.WorkoutSummary -> kind.folder
                is RepoPaths.Kind.Program -> kind.folder
                is RepoPaths.Kind.FitnessTest -> kind.folder
                // Derived data: never merged, looked at when the plan is built (see the class documentation).
                is RepoPaths.Kind.Fitness, RepoPaths.Kind.Other -> continue
            }
            val folderUser = RepoPaths.uuidOfFolder(folder) ?: continue
            val bytes = client.blob(repo, sha)
            when (kind) {
                is RepoPaths.Kind.Profile -> RecordCodec.parseProfile(bytes)?.let { profile ->
                    profiles += profile
                    // A profile written before the resting heart rate history existed: its value is the first measurement.
                    if (RepoPaths.restingHeartRates(kind.folder) !in files) RecordCodec.legacyResting(profile)?.let { restingHeartRates += it }
                }
                is RepoPaths.Kind.Weights -> weights += RecordCodec.parseWeightCsv(bytes, folderUser)
                is RepoPaths.Kind.RestingHeartRates -> restingHeartRates += RecordCodec.parseRestingHeartRateCsv(bytes, folderUser)
                is RepoPaths.Kind.WorkoutSummary -> RecordCodec.parseWorkout(bytes, folderUser)?.let { workout ->
                    workoutPaths[workout.uuid] = path
                    val csvPath = RepoPaths.csvOf(path)
                    val samples = if (workout.deleted) emptyList() else files[csvPath]?.let { WorkoutCsv.read(String(client.blob(repo, it), Charsets.UTF_8)) }.orEmpty()
                    workouts += SyncWorkoutData(workout, samples)
                }
                is RepoPaths.Kind.Program -> RecordCodec.parseProgram(bytes, folderUser)?.let { documents += it }
                is RepoPaths.Kind.FitnessTest -> RecordCodec.parseFitnessTest(bytes, folderUser)?.let { fitness += it }
                is RepoPaths.Kind.Fitness, RepoPaths.Kind.Other -> Unit
            }
        }
        val result = store.applyRemote(RemoteChanges(profiles, weights, workouts, restingHeartRates))
        val programsApplied = if (documents.isEmpty()) 0 else programs.applyRemote(documents)
        val fitnessApplied = if (fitness.isEmpty()) 0 else fitnessTests.applyRemote(fitness)
        val testData = fitness.isNotEmpty() || workouts.any { it.workout.fitnessTestId != null }
        val analysisData = fitness.isNotEmpty() || profiles.isNotEmpty() || weights.isNotEmpty() || restingHeartRates.isNotEmpty() || workouts.isNotEmpty()
        return Pulled(result.applied + programsApplied + fitnessApplied, workoutPaths, testData, analysisData)
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

    private class Plan(initialWorkoutPaths: Map<String, String>, initialBlobs: Map<String, FitnessBlob>) {
        val changes = LinkedHashMap<String, Change>()
        val workoutPaths = HashMap(initialWorkoutPaths)
        val names = HashMap<String, String>()

        /** Folder of each user in the tree after this plan (the new one for a renamed user). */
        val folders = HashMap<String, String>()

        /** What is known about `fitness.json` blobs by SHA: the remembered ones, those read in this cycle and those written by it. */
        val blobs = HashMap(initialBlobs)

        /** Users whose `fitness.json` is written or removed by this plan. */
        val derivedFitness = ArrayList<String>()

        /** The part of [blobs] that describes files of [files]. */
        fun blobsIn(files: Map<String, String>): Map<String, FitnessBlob> {
            val shas = files.filterKeys { RepoPaths.classify(it) is RepoPaths.Kind.Fitness }.values.toSet()
            return blobs.filterKeys { it in shas }
        }

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
        repo: RepositoryRef,
        files: Map<String, String>,
        state: SyncState,
        pending: PendingChanges,
        programPending: List<ProgramDocument>,
        fitnessPending: List<FitnessTestDocument>,
    ): Plan {
        val plan = Plan(state.workoutPaths, state.fitnessBlobs)
        val folders = HashMap<String, String>() // user uuid -> existing folder
        for (path in files.keys.sorted()) {
            val folder = (RepoPaths.classify(path) as? RepoPaths.Kind.Profile)?.folder ?: continue
            RepoPaths.uuidOfFolder(folder)?.let { folders.putIfAbsent(it, folder) }
        }
        val userUuids = (pending.profiles.map { it.uuid } + pending.weights.map { it.userUuid } + pending.workouts.map { it.userUuid } +
            pending.restingHeartRates.map { it.userUuid } + programPending.map { it.userUuid } + fitnessPending.map { it.userUuid }).distinct()

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
            plan.folders[userUuid] = folder
            // The profile file holds only what the user edits: it changes with the profile, nothing else (the fitness
            // profile and the resting reference are derived, see the class documentation).
            val restingChanged = pending.restingHeartRates.any { it.userUuid == userUuid }
            if (pendingProfile != null || existing == null || (existing == folder && profilePath !in files)) {
                plan.put(profilePath, RecordCodec.profile(profile))
            }

            if (pending.weights.any { it.userUuid == userUuid }) {
                plan.put(RepoPaths.weights(folder), RecordCodec.weightCsv(store.weightEntries(userUuid)))
            }
            if (restingChanged) plan.put(RepoPaths.restingHeartRates(folder), RecordCodec.restingHeartRateCsv(store.restingHeartRates(userUuid)))
            for (workout in pending.workouts.filter { it.userUuid == userUuid }) planWorkout(plan, files, folder, workout)
            for (document in programPending.filter { it.userUuid == userUuid }) {
                plan.put(RepoPaths.program(folder, document.uuid), RecordCodec.program(document))
            }
            for (document in fitnessPending.filter { it.userUuid == userUuid }) {
                plan.put(RepoPaths.fitnessTest(folder, document.uuid), RecordCodec.fitnessTest(document))
            }
        }
        planFitness(repo, plan, files, folders, userUuids)
        // The first thing written to a repository that has no README gets it along.
        if (plan.changes.isNotEmpty() && RepoPaths.README !in files) plan.put(RepoPaths.README, RepositoryReadme.TEXT.toByteArray(Charsets.UTF_8))
        return plan
    }

    /**
     * The `fitness.json` of every user of the repository (and of the users uploaded for the first time in this plan):
     * written when its fingerprint differs from the local one, removed when the user has no usable result any more, left
     * alone when it matches or was written with a higher rules version. See the class documentation.
     */
    private suspend fun planFitness(repo: RepositoryRef, plan: Plan, files: Map<String, String>, folders: Map<String, String>, pendingUsers: List<String>) {
        for (userUuid in (pendingUsers + folders.keys).distinct()) {
            val profile = store.profile(userUuid)?.takeUnless { it.deleted } ?: continue
            val existing = folders[userUuid]
            val folder = plan.folders[userUuid] ?: existing ?: continue
            val remote = existing?.let { files[RepoPaths.fitness(it)] }?.let { sha -> plan.blobs.getOrPut(sha) { readFitnessBlob(repo, sha) } }
            val local = store.fitness(userUuid)
            val target = RepoPaths.fitness(folder)
            when {
                remote != null && remote.rulesVersion > FitnessProfiles.RULES_VERSION -> Unit // written by a newer app: never overwritten
                local == null -> if (remote != null) {
                    plan.delete(target)
                    plan.derivedFitness += userUuid
                }
                remote != null && remote.fingerprint == local.fingerprint -> Unit
                else -> {
                    val bytes = RecordCodec.fitness(local, RecordVersion(clock.nowMillis(), store.deviceId))
                    plan.put(target, bytes)
                    plan.blobs[GitBlob.sha(bytes)] = FitnessBlob(local.fingerprint, local.rulesVersion)
                    plan.derivedFitness += userUuid
                }
            }
            if (userUuid in plan.derivedFitness) plan.names.putIfAbsent(userUuid, profile.name)
        }
    }

    /** A file that cannot be understood counts as a file of an older version with other inputs: it is written again. */
    private suspend fun readFitnessBlob(repo: RepositoryRef, sha: String): FitnessBlob =
        RecordCodec.parseFitness(client.blob(repo, sha))?.let { FitnessBlob(it.fingerprint, it.rulesVersion) } ?: FitnessBlob("", 0)

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
                restingHeartRates = pending.restingHeartRates.map { SyncedRef(it.uuid, it.version) },
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
