package com.treadz.console.userdata

import com.treadz.console.programs.fitness.HeartRateMaxDecision
import com.treadz.console.programs.heartrate.HeartRateSource
import com.treadz.console.programs.personalised.GeneratedSessionRecord
import com.treadz.console.treadmill.TreadmillController
import com.treadz.console.treadmill.control.Clock
import com.treadz.console.userdata.sync.FitnessTestDocumentStore
import com.treadz.console.userdata.sync.ProgramDocumentStore
import com.treadz.console.userdata.sync.SyncStore
import com.treadz.console.userdata.workout.WorkoutHistoryObserver
import com.treadz.console.userdata.workout.WorkoutObserverConfig
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import java.time.LocalDate

/**
 * Local profiles and the active user. Everything stays on the device; there are no accounts or PINs.
 *
 * Observable data is exposed as cold [Flow]s that re-emit on every change (they emit once the data is loaded, so
 * "no active user" is `null` and not "still loading"). Writes are `suspend` and safe to call from the main thread.
 */
interface ProfileRepository {

    /**
     * All profiles, ordered by name (case-insensitive); the generic profile, with its empty name, comes first. A fresh
     * database contains the generic profile, so this is never empty.
     */
    val profiles: Flow<List<UserProfile>>

    /** The user who is training, or `null` if nobody is selected. Persists across restarts. */
    val activeUser: Flow<UserProfile?>

    fun profile(id: Long): Flow<UserProfile?>

    /** One-shot read of the active user's id. */
    suspend fun activeUserId(): Long?

    /**
     * Creates a normal profile: the name is required and unique. It does not become the active user (there is always
     * a profile, so someone already is, or the UI is about to ask who trains).
     */
    suspend fun create(input: ProfileInput): DataResult<UserProfile>

    /**
     * Edits a profile. A normal profile needs a name. The generic profile ([UserProfile.isGeneric]) may keep a blank
     * name (it stays generic and only the other fields are validated); as soon as it gets a name it becomes a normal
     * profile (`isGeneric = false`) and the usual name rules apply. To personalise it together with the real weight
     * use [personalize].
     */
    suspend fun update(id: Long, input: ProfileInput): DataResult<UserProfile>

    /**
     * Personalises the generic profile atomically (it also works on any profile): saves [input], which must have a
     * name (`Blank` otherwise), and, if [weightKg] is not `null`, records it as the real current weight dated today and
     * removes the placeholder weight of the generic profile, all in one transaction. With `weightKg == null` the
     * placeholder weight is kept (still flagged [WeightEntry.isPlaceholder]), so the UI can keep asking for it.
     * The result is a normal profile (`isGeneric = false`). Weight problems are reported with [InputField.Weight].
     */
    suspend fun personalize(id: Long, input: ProfileInput, weightKg: Double?): DataResult<UserProfile>

    /**
     * Deletes the profile and, with it, its weight entries, target weight and workouts.
     *
     * - There is always at least one profile: deleting the last one recreates a fresh generic profile
     *   ([GenericProfile]) and makes it the active user.
     * - If other profiles remain and the deleted one was active, nobody is active afterwards: the UI must ask who is
     *   training (silently switching to another person would attribute the next workout to the wrong one).
     */
    suspend fun delete(id: Long): DataResult<Unit>

    /** Selects who is training; `null` leaves nobody selected. */
    suspend fun setActiveUser(id: Long?): DataResult<Unit>

    /**
     * Pairs the user with a heart rate strap (the one found by the strap scan: [address] is its Bluetooth address, [name]
     * what it advertises). The address is validated and stored in upper case ([StrapRules]); a blank name is stored as
     * `null`. Replaces the previous strap. Changing the strap is a change of the profile, so it is synchronised.
     */
    suspend fun setStrap(id: Long, address: String, name: String?): StrapSaveResult

    /** Forgets the user's strap. [DataResult.NotFound] if the profile does not exist; clearing no strap changes nothing. */
    suspend fun clearStrap(id: Long): DataResult<Unit>
}

/** Manual weight log and target weight, per user. */
interface WeightRepository {

    /**
     * Entries of the user ordered by date ascending, ready for a chart; within a date by creation time (see
     * [WeightEntry]). Empty for an unknown user.
     */
    fun entries(userId: Long): Flow<List<WeightEntry>>

    /**
     * The latest entry: the last of [entries] (the highest date; on a tie the one created last, see [WeightEntry]).
     * It is the same on every device that shares the data. This is the user's current weight, which feeds calories
     * and other calculations. `null` if the user has no entries.
     */
    fun latestWeight(userId: Long): Flow<WeightEntry?>

    fun targetWeightKg(userId: Long): Flow<Double?>

    /** Adds a real entry. It also removes the user's placeholder weight ([WeightEntry.isPlaceholder]), if any. */
    suspend fun add(userId: Long, date: LocalDate, weightKg: Double): DataResult<WeightEntry>

    /** Edits an entry; editing a placeholder turns it into a normal entry. */
    suspend fun update(entryId: Long, date: LocalDate, weightKg: Double): DataResult<WeightEntry>

    suspend fun delete(entryId: Long): DataResult<Unit>

    /** Sets or clears (`null`) the target weight. */
    suspend fun setTargetWeight(userId: Long, weightKg: Double?): DataResult<Unit>
}

/** Stored workouts. They are created by [WorkoutHistoryObserver], never by the UI. */
interface WorkoutHistoryRepository {

    /** Workouts of the user, newest first (a workout in progress included, with [WorkoutSummary.isInProgress]). */
    fun workouts(userId: Long): Flow<List<WorkoutSummary>>

    /** Workouts done with no active user, newest first, waiting for [assign] (or [delete]). */
    fun unassignedWorkouts(): Flow<List<WorkoutSummary>>

    /** The workout being recorded right now, if any. */
    fun inProgressWorkout(): Flow<WorkoutSummary?>

    /** Summary and per-second samples of one workout; `null` if it does not exist. */
    suspend fun detail(workoutId: Long): WorkoutDetail?

    suspend fun delete(workoutId: Long): DataResult<Unit>

    /**
     * Keeps a workout (in progress or finished) out of the uploads until [releaseUpload], while the user decides
     * whether to keep it. In memory only: after a restart it is uploaded as usual.
     */
    fun holdUpload(workoutId: Long) {}

    fun releaseUpload(workoutId: Long) {}

    /**
     * Discards a finished workout the user did not want, together with its fitness test result. While its upload is
     * held (it never left this device) the workout, its samples and the result are deleted for good, with no tombstone,
     * so nothing ever reaches the repository; otherwise it is a normal [delete] of both. The hold is released.
     */
    suspend fun discardFinished(workoutId: Long): DataResult<Unit>

    /** Gives an unassigned workout (or moves any workout) to a user. */
    suspend fun assign(workoutId: Long, userId: Long): DataResult<Unit>

    /**
     * Attaches to a workout the plan that was executed and/or the fitness test that was run (the parts of [link] that
     * are not `null` replace what the workout had). Works on a workout in progress and on a finished one (it is then
     * uploaded again with the link). Linking what is already linked changes nothing. The plan is stored as the
     * self-contained `PlanJson` document, so it outlives the program. [DataResult.NotFound] if the workout does not
     * exist. During a session the app usually lets the observer do it instead, see
     * [WorkoutHistoryObserver.linkNextSession].
     */
    suspend fun link(workoutId: Long, link: WorkoutLink): DataResult<Unit>

    /**
     * Stores the record of a generated session with its workout (`GeneratedSessionRecords.build` makes it when the
     * program runner finishes). Call it for the workout in progress ([WorkoutHistoryObserver.activeWorkoutId], or
     * [WorkoutHistoryObserver.lastWorkoutId] if the belt has already stopped) or any finished one. It works with the
     * upload hold: while the workout is held the record waits with it and is part of the workout document when it is
     * uploaded after [releaseUpload]; [discardFinished] removes it with the workout. Attaching the same record again
     * changes nothing; a different one replaces it. [DataResult.NotFound] if the workout does not exist.
     */
    suspend fun attachSessionRecord(workoutId: Long, record: GeneratedSessionRecord): DataResult<Unit>

    /**
     * The session records of the user for one generator ([GeneratedSessionRecord.generatorId]), newest first (by the
     * start of their workout), for the generators to adapt the next session. A record written by a newer app than this
     * one (schema version) cannot be read and is skipped. Workouts that were deleted or discarded have no record.
     */
    fun sessionRecords(userId: Long, generatorId: String): Flow<List<StoredSessionRecord>>

    /**
     * The highest heart-rate peak ([WorkoutStats.peakHeartRate]) among the user's finished workouts that started in the
     * last [withinDays] days (365 by default), or `null`: its value, workout and time. Re-emitted when the workouts change,
     * not as time passes. The policy that offers it as a new maximum heart rate is not part of this layer.
     */
    fun highestHeartRatePeak(userId: Long, withinDays: Int = HEART_RATE_PEAK_DAYS): Flow<HeartRatePeakRecord?>

    companion object {
        /** Window of the observed heart-rate peak used to detect a maximum heart rate higher than the one in use. */
        const val HEART_RATE_PEAK_DAYS = 365
    }
}

/** Everything the UI needs from the data layer. [TreadZUserData] builds the real one, `InMemoryUserData` the fake. */
interface UserData {
    val profiles: ProfileRepository
    val weights: WeightRepository
    val workouts: WorkoutHistoryRepository

    /** Free and personalised programs of each user (standard programs live in `:programs`). */
    val programs: ProgramRepository

    /** Fitness test results, the fitness profile and the maximum heart rate decision. */
    val fitnessTests: FitnessTestRepository

    /** The start parameters last used for each program and user (local to the device). */
    val startParameters: StartParametersRepository

    /** Resting heart rate measurements of each user and their reference (the profile's resting heart rate). */
    val restingHeartRates: RestingHeartRateRepository

    /**
     * Brings the stored fitness test results up to date (VO2max of the walking test, thresholds): the real data layer
     * runs it by itself at app start and after a synchronisation that brought results or workouts; the UI never needs
     * to call it. See [FitnessRecalculator].
     */
    val fitnessRecalculator: FitnessRecalculator

    /**
     * Creates the component that records the treadmill's sessions into [workouts]. It only observes
     * [controller]; it never sends commands. Call [WorkoutHistoryObserver.start] to begin.
     *
     * With a [heartRateSource] (the app's `AutomaticHeartRateSource`) the heart rate, its source and the RR intervals of
     * every sample come from it; without one the heart rate is the console's, as before.
     */
    fun workoutObserver(
        controller: TreadmillController,
        clock: Clock = Clock.System,
        config: WorkoutObserverConfig = WorkoutObserverConfig(),
        heartRateSource: HeartRateSource? = null,
        onError: (Throwable) -> Unit = {},
    ): WorkoutHistoryObserver
}

/** The active user's current weight (latest entry), or `null` if nobody is active or they have no weights. */
@OptIn(ExperimentalCoroutinesApi::class)
val UserData.activeUserWeightKg: Flow<Double?>
    get() = profiles.activeUser.flatMapLatest { user ->
        if (user == null) flowOf(null) else weights.latestWeight(user.id).map { it?.weightKg }
    }

/** The active user's maximum heart rate decision (see [FitnessTestRepository.heartRateMax]), or `null` if nobody is active. */
@OptIn(ExperimentalCoroutinesApi::class)
val UserData.activeUserHeartRateMax: Flow<HeartRateMaxDecision?>
    get() = profiles.activeUser.flatMapLatest { user ->
        if (user == null) flowOf(null) else fitnessTests.heartRateMax(user.id)
    }

/**
 * A [UserData] that can also be synchronised with other devices: it exposes [sync], the port used by the `:sync`
 * module, and the stores of the documents (programs, fitness tests) that are synchronised as JSON files. The real
 * (Room) data layer is one; the UI does not need any of them.
 */
interface SyncableUserData : UserData {
    val sync: SyncStore

    /** The user programs as documents for the synchronisation (the same rows as [programs]). */
    val programDocuments: ProgramDocumentStore

    /** The fitness test results as documents for the synchronisation (the same rows as [fitnessTests]). */
    val fitnessTestDocuments: FitnessTestDocumentStore
}
