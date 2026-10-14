package com.treadz.console.userdata.memory

import com.treadz.console.programs.fitness.FitnessProfile
import com.treadz.console.programs.fitness.FitnessTestJson
import com.treadz.console.programs.fitness.FitnessTestResult
import com.treadz.console.programs.fitness.HeartRateMaxDecision
import com.treadz.console.programs.json.PlanJson
import com.treadz.console.programs.json.ProgramJson
import com.treadz.console.programs.model.Program
import com.treadz.console.programs.plan.ResolvedPlan
import com.treadz.console.programs.plan.StartParameters
import com.treadz.console.treadmill.TreadmillController
import com.treadz.console.treadmill.control.Clock
import com.treadz.console.userdata.DataResult
import com.treadz.console.userdata.FitnessProfiles
import com.treadz.console.userdata.FitnessTestRecord
import com.treadz.console.userdata.FitnessTestRepository
import com.treadz.console.userdata.GenericProfile
import com.treadz.console.userdata.InputField
import com.treadz.console.userdata.ProfileInput
import com.treadz.console.userdata.ProfileRepository
import com.treadz.console.userdata.ProgramRepository
import com.treadz.console.userdata.ProgramSaveResult
import com.treadz.console.userdata.StartParametersRepository
import com.treadz.console.userdata.UserProgram
import com.treadz.console.userdata.UserData
import com.treadz.console.userdata.UserProfile
import com.treadz.console.userdata.Validation
import com.treadz.console.userdata.ValidationCode
import com.treadz.console.userdata.ValidationIssue
import com.treadz.console.userdata.WeightEntry
import com.treadz.console.userdata.WeightRepository
import com.treadz.console.userdata.WorkoutDetail
import com.treadz.console.userdata.WorkoutEndReason
import com.treadz.console.userdata.WorkoutHistoryRepository
import com.treadz.console.userdata.WorkoutLink
import com.treadz.console.userdata.WorkoutSample
import com.treadz.console.userdata.WorkoutStats
import com.treadz.console.userdata.WorkoutSummary
import com.treadz.console.userdata.room.FitnessRules
import com.treadz.console.userdata.room.ProgramRules
import com.treadz.console.userdata.workout.WorkoutHistoryObserver
import com.treadz.console.userdata.workout.WorkoutObserverConfig
import com.treadz.console.userdata.workout.WorkoutSessionStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.util.Locale
import java.util.UUID

/**
 * [UserData] kept in memory, with the same behaviour as the Room implementation (validation, cascades, ordering,
 * observable flows). For the UI's tests and screenshots (see [DemoData] for ready-made content); nothing is persisted.
 */
class InMemoryUserData(
    private val clock: Clock = Clock.System,
    private val zone: ZoneId = ZoneId.systemDefault(),
    /** Starts with the generic profile, like a fresh install of the app (tests may turn it off). */
    seedGenericProfile: Boolean = true,
) : UserData {

    private data class Data(
        val profiles: List<UserProfile> = emptyList(),
        val targets: Map<Long, Double> = emptyMap(),
        val activeId: Long? = null,
        val weights: List<WeightEntry> = emptyList(),
        val workouts: List<WorkoutSummary> = emptyList(),
        val programs: List<UserProgram> = emptyList(),
        val fitness: List<FitnessTestRecord> = emptyList(),
        val startParams: Map<Pair<Long, String>, StartParameters> = emptyMap(),
        val nextId: Long = 1,
    )

    private val lock = Any()
    private val data = MutableStateFlow(if (seedGenericProfile) withGeneric(Data()) else Data())
    private val workoutSamples = mutableMapOf<Long, MutableList<WorkoutSample>>()
    private val workoutPlans = mutableMapOf<Long, ResolvedPlan>()

    private fun today(): LocalDate = Instant.ofEpochMilli(clock.nowMillis()).atZone(zone).toLocalDate()

    /** Adds a fresh generic profile (with its placeholder weight) and makes it active. */
    private fun withGeneric(d: Data): Data {
        val today = today()
        val input = GenericProfile.input(today)
        val profile = UserProfile(
            id = d.nextId, name = "", sex = input.sex, heightCm = input.heightCm, birthDate = input.birthDate,
            createdAtMillis = clock.nowMillis(), isGeneric = true,
        )
        val weight = WeightEntry(d.nextId + 1, profile.id, today, GenericProfile.WEIGHT_KG, isPlaceholder = true, createdAtMillis = clock.nowMillis())
        return d.copy(
            profiles = d.profiles + profile,
            weights = d.weights + weight,
            activeId = profile.id,
            nextId = d.nextId + 2,
        )
    }

    private fun <T> modify(block: (Data) -> Pair<Data, DataResult<T>>): DataResult<T> = synchronized(lock) {
        val (next, result) = block(data.value)
        data.value = next
        result
    }

    override val profiles: ProfileRepository = object : ProfileRepository {
        override val profiles: Flow<List<UserProfile>> = data.map { d -> d.profiles.sortedWith(byName) }
        override val activeUser: Flow<UserProfile?> = data.map { d -> d.profiles.firstOrNull { it.id == d.activeId } }

        override fun profile(id: Long): Flow<UserProfile?> = data.map { d -> d.profiles.firstOrNull { it.id == id } }

        override suspend fun activeUserId(): Long? = data.value.activeId

        override suspend fun create(input: ProfileInput): DataResult<UserProfile> {
            val issues = Validation.profile(input, today())
            if (issues.isNotEmpty()) return DataResult.Invalid(issues)
            return modify { d ->
                if (d.profiles.any { Validation.nameKey(it.name) == Validation.nameKey(input.name) }) {
                    return@modify d to DataResult.Invalid(listOf(nameTaken))
                }
                val profile = UserProfile(
                    id = d.nextId,
                    name = Validation.normalizedName(input.name),
                    sex = input.sex,
                    heightCm = input.heightCm,
                    birthDate = input.birthDate,
                    restingHeartRateBpm = input.restingHeartRateBpm,
                    maxHeartRateBpm = input.maxHeartRateBpm,
                    fitnessLevel = input.fitnessLevel,
                    goal = input.goal,
                    createdAtMillis = clock.nowMillis(),
                    maxHeartRateSetAtMillis = if (input.maxHeartRateBpm != null) clock.nowMillis() else null,
                )
                val active = if (d.profiles.isEmpty()) profile.id else d.activeId
                d.copy(profiles = d.profiles + profile, activeId = active, nextId = d.nextId + 1) to DataResult.Success(profile)
            }
        }

        override suspend fun update(id: Long, input: ProfileInput): DataResult<UserProfile> = modify { d -> save(d, id, input) }

        override suspend fun personalize(id: Long, input: ProfileInput, weightKg: Double?): DataResult<UserProfile> {
            val today = today()
            val issues = Validation.profile(input, today) +
                (if (weightKg != null) Validation.weightEntry(today, weightKg, today) else emptyList())
            if (issues.isNotEmpty()) return DataResult.Invalid(issues)
            return modify { d ->
                val (saved, result) = save(d, id, input)
                if (result !is DataResult.Success || weightKg == null) return@modify saved to result
                val entry = WeightEntry(saved.nextId, id, today, weightKg, createdAtMillis = clock.nowMillis())
                saved.copy(
                    weights = saved.weights.filterNot { it.userId == id && it.isPlaceholder } + entry,
                    nextId = saved.nextId + 1,
                ) to result
            }
        }

        private fun save(d: Data, id: Long, input: ProfileInput): Pair<Data, DataResult<UserProfile>> {
            val current = d.profiles.firstOrNull { it.id == id } ?: return d to DataResult.NotFound
            val stillGeneric = current.isGeneric && input.name.isBlank()
            val issues = Validation.profile(input, today(), allowBlankName = stillGeneric)
            if (issues.isNotEmpty()) return d to DataResult.Invalid(issues)
            if (!stillGeneric && d.profiles.any { it.id != id && Validation.nameKey(it.name) == Validation.nameKey(input.name) }) {
                return d to DataResult.Invalid(listOf(nameTaken))
            }
            val updated = current.copy(
                name = Validation.normalizedName(input.name),
                sex = input.sex,
                heightCm = input.heightCm,
                birthDate = input.birthDate,
                restingHeartRateBpm = input.restingHeartRateBpm,
                maxHeartRateBpm = input.maxHeartRateBpm,
                fitnessLevel = input.fitnessLevel,
                goal = input.goal,
                isGeneric = stillGeneric,
                maxHeartRateSetAtMillis = when {
                    input.maxHeartRateBpm == null -> null
                    input.maxHeartRateBpm != current.maxHeartRateBpm -> clock.nowMillis()
                    else -> current.maxHeartRateSetAtMillis
                },
            )
            return d.copy(profiles = d.profiles.map { if (it.id == id) updated else it }) to DataResult.Success(updated)
        }

        override suspend fun delete(id: Long): DataResult<Unit> = modify { d ->
            if (d.profiles.none { it.id == id }) return@modify d to DataResult.NotFound
            val removed = d.workouts.filter { it.userId == id }.map { it.id }
            removed.forEach { workoutSamples.remove(it); workoutPlans.remove(it) }
            val remaining = d.copy(
                profiles = d.profiles.filter { it.id != id },
                targets = d.targets - id,
                activeId = d.activeId.takeIf { it != id },
                weights = d.weights.filter { it.userId != id },
                workouts = d.workouts.filter { it.userId != id },
                programs = d.programs.filter { it.userId != id },
                fitness = d.fitness.filter { it.userId != id },
                startParams = d.startParams.filterKeys { it.first != id },
            )
            // There is always at least one profile.
            (if (remaining.profiles.isEmpty()) withGeneric(remaining) else remaining) to DataResult.Success(Unit)
        }

        override suspend fun setActiveUser(id: Long?): DataResult<Unit> = modify { d ->
            if (id != null && d.profiles.none { it.id == id }) return@modify d to DataResult.NotFound
            d.copy(activeId = id) to DataResult.Success(Unit)
        }
    }

    override val weights: WeightRepository = object : WeightRepository {
        override fun entries(userId: Long): Flow<List<WeightEntry>> = data.map { d ->
            d.weights.filter { it.userId == userId }.sortedWith(compareBy({ it.date }, { it.createdAtMillis }, { it.id }))
        }

        override fun latestWeight(userId: Long): Flow<WeightEntry?> = entries(userId).map { it.lastOrNull() }

        override fun targetWeightKg(userId: Long): Flow<Double?> = data.map { it.targets[userId] }

        override suspend fun add(userId: Long, date: LocalDate, weightKg: Double): DataResult<WeightEntry> {
            val issues = Validation.weightEntry(date, weightKg, today())
            if (issues.isNotEmpty()) return DataResult.Invalid(issues)
            return modify { d ->
                if (d.profiles.none { it.id == userId }) return@modify d to DataResult.NotFound
                val entry = WeightEntry(d.nextId, userId, date, weightKg, createdAtMillis = clock.nowMillis())
                d.copy(
                    weights = d.weights.filterNot { it.userId == userId && it.isPlaceholder } + entry,
                    nextId = d.nextId + 1,
                ) to DataResult.Success(entry)
            }
        }

        override suspend fun update(entryId: Long, date: LocalDate, weightKg: Double): DataResult<WeightEntry> {
            val issues = Validation.weightEntry(date, weightKg, today())
            if (issues.isNotEmpty()) return DataResult.Invalid(issues)
            return modify { d ->
                val current = d.weights.firstOrNull { it.id == entryId } ?: return@modify d to DataResult.NotFound
                val updated = current.copy(date = date, weightKg = weightKg, isPlaceholder = false)
                d.copy(weights = d.weights.map { if (it.id == entryId) updated else it }) to DataResult.Success(updated)
            }
        }

        override suspend fun delete(entryId: Long): DataResult<Unit> = modify { d ->
            if (d.weights.none { it.id == entryId }) return@modify d to DataResult.NotFound
            d.copy(weights = d.weights.filter { it.id != entryId }) to DataResult.Success(Unit)
        }

        override suspend fun setTargetWeight(userId: Long, weightKg: Double?): DataResult<Unit> {
            if (weightKg != null) {
                val issues = Validation.targetWeight(weightKg)
                if (issues.isNotEmpty()) return DataResult.Invalid(issues)
            }
            return modify { d ->
                if (d.profiles.none { it.id == userId }) return@modify d to DataResult.NotFound
                val targets = if (weightKg == null) d.targets - userId else d.targets + (userId to weightKg)
                d.copy(targets = targets) to DataResult.Success(Unit)
            }
        }
    }

    private val store = object : WorkoutSessionStore {
        override suspend fun begin(userId: Long?, startedAtMillis: Long, link: WorkoutLink?): Long = synchronized(lock) {
            val d = data.value
            val summary = WorkoutSummary(d.nextId, userId, startedAtMillis, null, null, WorkoutStats())
            workoutSamples[summary.id] = mutableListOf()
            data.value = d.copy(workouts = d.workouts + summary, nextId = d.nextId + 1)
            if (link != null) applyLink(summary.id, link)
            summary.id
        }

        override suspend fun attachLink(workoutId: Long, link: WorkoutLink): Boolean = synchronized(lock) { applyLink(workoutId, link) }

        override suspend fun append(workoutId: Long, samples: List<WorkoutSample>, stats: WorkoutStats): Boolean =
            synchronized(lock) { write(workoutId, samples, stats) { it } }

        override suspend fun finish(
            workoutId: Long,
            samples: List<WorkoutSample>,
            stats: WorkoutStats,
            endedAtMillis: Long,
            reason: WorkoutEndReason,
        ): Boolean = synchronized(lock) {
            write(workoutId, samples, stats) { it.copy(endedAtMillis = endedAtMillis, endReason = reason) }
        }

        private fun write(id: Long, samples: List<WorkoutSample>, stats: WorkoutStats, change: (WorkoutSummary) -> WorkoutSummary): Boolean {
            val d = data.value
            if (d.workouts.none { it.id == id }) return false
            val stored = workoutSamples.getOrPut(id) { mutableListOf() }
            samples.forEach { s -> stored.removeAll { it.seq == s.seq }; stored += s }
            stored.sortBy { it.seq }
            data.value = d.copy(workouts = d.workouts.map { if (it.id == id) change(it.copy(stats = stats)) else it })
            return true
        }

        override suspend fun discard(workoutId: Long) {
            synchronized(lock) {
                workoutSamples.remove(workoutId)
                workoutPlans.remove(workoutId)
                data.value = data.value.let { d -> d.copy(workouts = d.workouts.filter { it.id != workoutId }) }
            }
        }

        override suspend fun closeInterrupted(): Int = synchronized(lock) {
            val d = data.value
            val open = d.workouts.filter { it.isInProgress }
            data.value = d.copy(
                workouts = d.workouts.map { w ->
                    if (!w.isInProgress) {
                        w
                    } else {
                        val last = workoutSamples[w.id]?.maxOfOrNull { it.timestampMillis } ?: w.startedAtMillis
                        w.copy(endedAtMillis = last, endReason = WorkoutEndReason.Interrupted)
                    }
                },
            )
            open.size
        }
    }

    override val workouts: WorkoutHistoryRepository = object : WorkoutHistoryRepository {
        private fun newestFirst(list: List<WorkoutSummary>) = list.sortedWith(compareByDescending<WorkoutSummary> { it.startedAtMillis }.thenByDescending { it.id })

        override fun workouts(userId: Long): Flow<List<WorkoutSummary>> = data.map { d -> newestFirst(d.workouts.filter { it.userId == userId }) }

        override fun unassignedWorkouts(): Flow<List<WorkoutSummary>> = data.map { d -> newestFirst(d.workouts.filter { it.userId == null }) }

        override fun inProgressWorkout(): Flow<WorkoutSummary?> = data.map { d -> newestFirst(d.workouts.filter { it.isInProgress }).firstOrNull() }

        override suspend fun detail(workoutId: Long): WorkoutDetail? = synchronized(lock) {
            val summary = data.value.workouts.firstOrNull { it.id == workoutId } ?: return null
            WorkoutDetail(summary, workoutSamples[workoutId].orEmpty().toList(), workoutPlans[workoutId])
        }

        override suspend fun delete(workoutId: Long): DataResult<Unit> = synchronized(lock) {
            if (data.value.workouts.none { it.id == workoutId }) return DataResult.NotFound
            workoutSamples.remove(workoutId)
            workoutPlans.remove(workoutId)
            data.value = data.value.let { d -> d.copy(workouts = d.workouts.filter { it.id != workoutId }) }
            DataResult.Success(Unit)
        }

        /** Nothing is uploaded here: discarding is deleting the workout and its fitness test result. */
        override suspend fun discardFinished(workoutId: Long): DataResult<Unit> = synchronized(lock) {
            val workout = data.value.workouts.firstOrNull { it.id == workoutId } ?: return DataResult.NotFound
            workoutSamples.remove(workoutId)
            workoutPlans.remove(workoutId)
            data.value = data.value.let { d ->
                d.copy(
                    workouts = d.workouts.filter { it.id != workoutId },
                    fitness = d.fitness.filter { workout.fitnessTestId == null || it.id != workout.fitnessTestId },
                )
            }
            DataResult.Success(Unit)
        }

        override suspend fun link(workoutId: Long, link: WorkoutLink): DataResult<Unit> = synchronized(lock) {
            if (applyLink(workoutId, link)) DataResult.Success(Unit) else DataResult.NotFound
        }

        override suspend fun assign(workoutId: Long, userId: Long): DataResult<Unit> = modify { d ->
            if (d.profiles.none { it.id == userId } || d.workouts.none { it.id == workoutId }) return@modify d to DataResult.NotFound
            d.copy(workouts = d.workouts.map { if (it.id == workoutId) it.copy(userId = userId) else it }) to DataResult.Success(Unit)
        }
    }

    /** Merges [link] into the workout (call with [lock] held). `false` if the workout does not exist. */
    private fun applyLink(workoutId: Long, link: WorkoutLink): Boolean {
        val d = data.value
        if (d.workouts.none { it.id == workoutId }) return false
        link.plan?.let { workoutPlans[workoutId] = PlanJson.decode(PlanJson.encode(it)) }
        data.value = d.copy(
            workouts = d.workouts.map {
                if (it.id != workoutId) {
                    it
                } else {
                    it.copy(
                        programId = link.plan?.source?.programId ?: it.programId,
                        programName = link.plan?.source?.programName ?: it.programName,
                        fitnessTestId = link.fitnessTestId ?: it.fitnessTestId,
                    )
                }
            },
        )
        return true
    }

    override val programs: ProgramRepository = object : ProgramRepository {
        private fun byName(list: List<UserProgram>) =
            list.sortedWith(compareBy({ it.program.name.trim().lowercase(Locale.ROOT) }, { it.id }))

        override fun programs(userId: Long): Flow<List<UserProgram>> = data.map { d -> byName(d.programs.filter { it.userId == userId }) }

        override fun program(programId: String): Flow<UserProgram?> = data.map { d -> d.programs.firstOrNull { it.id == programId } }

        override suspend fun get(programId: String): UserProgram? = data.value.programs.firstOrNull { it.id == programId }

        override suspend fun save(userId: Long, program: Program): ProgramSaveResult {
            val candidate = if (program.id.isBlank()) program.copy(id = UUID.randomUUID().toString()) else program
            val issues = ProgramRules.issues(candidate)
            if (issues.isNotEmpty()) return ProgramSaveResult.Invalid(issues)
            // Stored as the JSON document, like the real implementation, so that what is read back is identical.
            val normalised = ProgramJson.decode(ProgramJson.encode(candidate), validate = false)
            return synchronized(lock) {
                val d = data.value
                if (d.profiles.none { it.id == userId }) return@synchronized ProgramSaveResult.NotFound
                val existing = d.programs.firstOrNull { it.id == normalised.id }
                if (existing != null && existing.userId != userId) return@synchronized ProgramSaveResult.Invalid(listOf(ProgramRules.idTaken))
                if (existing != null && existing.program == normalised) return@synchronized ProgramSaveResult.Saved(existing)
                val now = clock.nowMillis()
                val saved = UserProgram(userId, normalised, existing?.createdAtMillis ?: now, now)
                data.value = d.copy(programs = d.programs.filter { it.id != normalised.id } + saved)
                ProgramSaveResult.Saved(saved)
            }
        }

        override suspend fun delete(programId: String): DataResult<Unit> = modify { d ->
            if (d.programs.none { it.id == programId }) return@modify d to DataResult.NotFound
            d.copy(
                programs = d.programs.filter { it.id != programId },
                startParams = d.startParams.filterKeys { it.second != programId },
            ) to DataResult.Success(Unit)
        }
    }

    override val startParameters: StartParametersRepository = object : StartParametersRepository {
        override fun lastUsed(userId: Long, programId: String): Flow<StartParameters?> =
            data.map { it.startParams[userId to programId] }.distinctUntilChanged()

        override suspend fun save(userId: Long, programId: String, parameters: StartParameters): DataResult<Unit> = modify { d ->
            if (d.profiles.none { it.id == userId }) return@modify d to DataResult.NotFound
            d.copy(startParams = d.startParams + ((userId to programId) to parameters)) to DataResult.Success(Unit)
        }

        override suspend fun clear(userId: Long, programId: String): DataResult<Unit> = modify { d ->
            d.copy(startParams = d.startParams - (userId to programId)) to DataResult.Success(Unit)
        }
    }

    override val fitnessTests: FitnessTestRepository = object : FitnessTestRepository {
        private fun newestFirst(list: List<FitnessTestRecord>) =
            list.sortedWith(compareByDescending<FitnessTestRecord> { it.result.startedAtMillis }.thenByDescending { it.id })

        override fun results(userId: Long): Flow<List<FitnessTestRecord>> =
            data.map { d -> newestFirst(d.fitness.filter { it.userId == userId }) }

        override suspend fun get(testId: String): FitnessTestRecord? = data.value.fitness.firstOrNull { it.id == testId }

        override suspend fun save(userId: Long, result: FitnessTestResult): DataResult<FitnessTestRecord> {
            require(FitnessRules.isValidId(result.id)) { "Invalid fitness test id '${result.id}'" }
            val normalised = FitnessTestJson.decode(FitnessTestJson.encode(result))
            return modify { d ->
                if (d.profiles.none { it.id == userId }) return@modify d to DataResult.NotFound
                val existing = d.fitness.firstOrNull { it.id == normalised.id }
                if (existing != null && existing.userId != userId) {
                    return@modify d to DataResult.StorageError("the fitness test id belongs to another user")
                }
                if (existing != null && existing.result == normalised) return@modify d to DataResult.Success(existing)
                val record = FitnessTestRecord(userId, normalised, clock.nowMillis())
                d.copy(fitness = d.fitness.filter { it.id != normalised.id } + record) to DataResult.Success(record)
            }
        }

        override suspend fun delete(testId: String): DataResult<Unit> = modify { d ->
            if (d.fitness.none { it.id == testId }) return@modify d to DataResult.NotFound
            d.copy(fitness = d.fitness.filter { it.id != testId }) to DataResult.Success(Unit)
        }

        private fun resultsOf(d: Data, userId: Long) = d.fitness.filter { it.userId == userId }.map { it.result }

        override fun fitnessProfile(userId: Long): Flow<FitnessProfile?> = data.map { d ->
            d.profiles.firstOrNull { it.id == userId }?.let { FitnessProfiles.snapshot(resultsOf(d, userId), it.sex, it.birthDate) }
        }.distinctUntilChanged()

        override fun liveFitnessProfile(userId: Long): Flow<FitnessProfile?> = data.map { d ->
            d.profiles.firstOrNull { it.id == userId }
                ?.let { FitnessProfiles.live(resultsOf(d, userId), it.sex, it.birthDate, clock.nowMillis(), zone) }
        }.distinctUntilChanged()

        override fun heartRateMax(userId: Long): Flow<HeartRateMaxDecision?> = data.map { d ->
            d.profiles.firstOrNull { it.id == userId }?.let { p ->
                val live = FitnessProfiles.live(resultsOf(d, userId), p.sex, p.birthDate, clock.nowMillis(), zone)
                FitnessProfiles.heartRateMax(p, live, today())
            }
        }.distinctUntilChanged()

        override suspend fun useMeasuredHeartRateMax(userId: Long): DataResult<Unit> = modify { d ->
            val p = d.profiles.firstOrNull { it.id == userId } ?: return@modify d to DataResult.NotFound
            val live = FitnessProfiles.live(resultsOf(d, userId), p.sex, p.birthDate, clock.nowMillis(), zone)
            if (live.hrMax == null) return@modify d to DataResult.NotFound
            val cleared = p.copy(maxHeartRateBpm = null, maxHeartRateSetAtMillis = null)
            d.copy(profiles = d.profiles.map { if (it.id == userId) cleared else it }) to DataResult.Success(Unit)
        }

        override suspend fun keepManualHeartRateMax(userId: Long): DataResult<Unit> = modify { d ->
            val p = d.profiles.firstOrNull { it.id == userId } ?: return@modify d to DataResult.NotFound
            if (p.maxHeartRateBpm == null) return@modify d to DataResult.NotFound
            val live = FitnessProfiles.live(resultsOf(d, userId), p.sex, p.birthDate, clock.nowMillis(), zone)
            val confirmedAt = maxOf(clock.nowMillis(), (live.hrMax?.testedAtMillis ?: Long.MIN_VALUE) + 1)
            val kept = p.copy(maxHeartRateSetAtMillis = confirmedAt)
            d.copy(profiles = d.profiles.map { if (it.id == userId) kept else it }) to DataResult.Success(Unit)
        }
    }

    override fun workoutObserver(
        controller: TreadmillController,
        clock: Clock,
        config: WorkoutObserverConfig,
        onError: (Throwable) -> Unit,
    ) = WorkoutHistoryObserver(controller, store, profiles::activeUserId, clock, config, onError)

    /** Adds a finished workout directly (for UI tests and screenshots), assigned to [userId]. Returns its id. */
    fun addWorkout(userId: Long?, summaryStats: WorkoutStats, samples: List<WorkoutSample>, startedAtMillis: Long, reason: WorkoutEndReason = WorkoutEndReason.Stopped): Long =
        synchronized(lock) {
            val d = data.value
            val summary = WorkoutSummary(
                d.nextId, userId, startedAtMillis, startedAtMillis + summaryStats.durationSeconds * 1000L, reason, summaryStats,
            )
            workoutSamples[summary.id] = samples.toMutableList()
            data.value = d.copy(workouts = d.workouts + summary, nextId = d.nextId + 1)
            summary.id
        }

    private companion object {
        val nameTaken = ValidationIssue(InputField.Name, ValidationCode.NameTaken)
        val byName = compareBy<UserProfile>({ Validation.nameKey(it.name) }, { it.id })
    }
}
