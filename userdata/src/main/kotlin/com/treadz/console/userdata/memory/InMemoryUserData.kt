package com.treadz.console.userdata.memory

import com.treadz.console.treadmill.TreadmillController
import com.treadz.console.treadmill.control.Clock
import com.treadz.console.userdata.DataResult
import com.treadz.console.userdata.GenericProfile
import com.treadz.console.userdata.InputField
import com.treadz.console.userdata.ProfileInput
import com.treadz.console.userdata.ProfileRepository
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
import com.treadz.console.userdata.WorkoutSample
import com.treadz.console.userdata.WorkoutStats
import com.treadz.console.userdata.WorkoutSummary
import com.treadz.console.userdata.workout.WorkoutHistoryObserver
import com.treadz.console.userdata.workout.WorkoutObserverConfig
import com.treadz.console.userdata.workout.WorkoutSessionStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.map
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId

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
        val nextId: Long = 1,
    )

    private val lock = Any()
    private val data = MutableStateFlow(if (seedGenericProfile) withGeneric(Data()) else Data())
    private val workoutSamples = mutableMapOf<Long, MutableList<WorkoutSample>>()

    private fun today(): LocalDate = Instant.ofEpochMilli(clock.nowMillis()).atZone(zone).toLocalDate()

    /** Adds a fresh generic profile (with its placeholder weight) and makes it active. */
    private fun withGeneric(d: Data): Data {
        val today = today()
        val input = GenericProfile.input(today)
        val profile = UserProfile(
            id = d.nextId, name = "", sex = input.sex, heightCm = input.heightCm, birthDate = input.birthDate,
            createdAtMillis = clock.nowMillis(), isGeneric = true,
        )
        val weight = WeightEntry(d.nextId + 1, profile.id, today, GenericProfile.WEIGHT_KG, isPlaceholder = true)
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
                val entry = WeightEntry(saved.nextId, id, today, weightKg)
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
            )
            return d.copy(profiles = d.profiles.map { if (it.id == id) updated else it }) to DataResult.Success(updated)
        }

        override suspend fun delete(id: Long): DataResult<Unit> = modify { d ->
            if (d.profiles.none { it.id == id }) return@modify d to DataResult.NotFound
            val removed = d.workouts.filter { it.userId == id }.map { it.id }
            removed.forEach { workoutSamples.remove(it) }
            val remaining = d.copy(
                profiles = d.profiles.filter { it.id != id },
                targets = d.targets - id,
                activeId = d.activeId.takeIf { it != id },
                weights = d.weights.filter { it.userId != id },
                workouts = d.workouts.filter { it.userId != id },
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
            d.weights.filter { it.userId == userId }.sortedWith(compareBy({ it.date }, { it.id }))
        }

        override fun latestWeight(userId: Long): Flow<WeightEntry?> = entries(userId).map { it.lastOrNull() }

        override fun targetWeightKg(userId: Long): Flow<Double?> = data.map { it.targets[userId] }

        override suspend fun add(userId: Long, date: LocalDate, weightKg: Double): DataResult<WeightEntry> {
            val issues = Validation.weightEntry(date, weightKg, today())
            if (issues.isNotEmpty()) return DataResult.Invalid(issues)
            return modify { d ->
                if (d.profiles.none { it.id == userId }) return@modify d to DataResult.NotFound
                val entry = WeightEntry(d.nextId, userId, date, weightKg)
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
        override suspend fun begin(userId: Long?, startedAtMillis: Long): Long = synchronized(lock) {
            val d = data.value
            val summary = WorkoutSummary(d.nextId, userId, startedAtMillis, null, null, WorkoutStats())
            workoutSamples[summary.id] = mutableListOf()
            data.value = d.copy(workouts = d.workouts + summary, nextId = d.nextId + 1)
            summary.id
        }

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
            WorkoutDetail(summary, workoutSamples[workoutId].orEmpty().toList())
        }

        override suspend fun delete(workoutId: Long): DataResult<Unit> = synchronized(lock) {
            if (data.value.workouts.none { it.id == workoutId }) return DataResult.NotFound
            workoutSamples.remove(workoutId)
            data.value = data.value.let { d -> d.copy(workouts = d.workouts.filter { it.id != workoutId }) }
            DataResult.Success(Unit)
        }

        override suspend fun assign(workoutId: Long, userId: Long): DataResult<Unit> = modify { d ->
            if (d.profiles.none { it.id == userId } || d.workouts.none { it.id == workoutId }) return@modify d to DataResult.NotFound
            d.copy(workouts = d.workouts.map { if (it.id == workoutId) it.copy(userId = userId) else it }) to DataResult.Success(Unit)
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
