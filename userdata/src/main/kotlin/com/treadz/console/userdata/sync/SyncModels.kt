package com.treadz.console.userdata.sync

import com.treadz.console.programs.fitness.FitnessProfile
import com.treadz.console.userdata.FitnessLevel
import com.treadz.console.userdata.Goal
import com.treadz.console.userdata.Sex
import com.treadz.console.userdata.WorkoutEndReason
import com.treadz.console.userdata.WorkoutSample
import com.treadz.console.userdata.WorkoutStats
import java.time.LocalDate

/**
 * Version of a record: when it was last changed and by which device. Two devices that changed the same record are
 * resolved by "last writer wins": the greater version wins, comparing the time first and, on a tie, the device id.
 */
data class RecordVersion(val updatedAtMillis: Long, val deviceId: String) : Comparable<RecordVersion> {
    override fun compareTo(other: RecordVersion): Int {
        val byTime = updatedAtMillis.compareTo(other.updatedAtMillis)
        return if (byTime != 0) byTime else deviceId.compareTo(other.deviceId)
    }
}

/** A profile as exchanged with other devices. Identified by [uuid], not by the local `Long` id. */
data class SyncProfile(
    val uuid: String,
    val version: RecordVersion,
    /** Tombstone: the profile was deleted. Only [uuid], [name] and [version] are meaningful then. */
    val deleted: Boolean,
    val name: String,
    val sex: Sex,
    val heightCm: Int,
    val birthDate: LocalDate,
    val restingHeartRateBpm: Int? = null,
    val maxHeartRateBpm: Int? = null,
    val fitnessLevel: FitnessLevel? = null,
    val goal: Goal? = null,
    val targetWeightKg: Double? = null,
    val createdAtMillis: Long = 0,
    /** When the manual [maxHeartRateBpm] was entered or last confirmed; `null` if unknown (it counts as older than any test). */
    val maxHeartRateSetAtMillis: Long? = null,
    /**
     * The deterministic fitness profile snapshot (`FitnessProfiles.snapshot`) of the user's results, **read-only**: it is
     * computed when the record is read from the device, written to `profile.json` for people and Claude to read, and
     * ignored on download (each device derives it from the synchronised results). It is not part of the record's
     * version and never a source of conflicts.
     */
    val fitness: FitnessProfile? = null,
)

/** A weight entry as exchanged with other devices. */
data class SyncWeightEntry(
    val uuid: String,
    /** The owner's [SyncProfile.uuid]. */
    val userUuid: String,
    val version: RecordVersion,
    /** Tombstone: the entry was deleted. [date] and [weightKg] are meaningless then. */
    val deleted: Boolean,
    val date: LocalDate,
    val weightKg: Double,
    /**
     * When the entry was created. With [date] and [uuid] it orders the entries identically on every device (see
     * `WeightEntry`). Repositories written before this field existed lack it: then it is the entry's last change time,
     * which is also what the schema migration assigns to entries that already exist.
     */
    val createdAtMillis: Long = version.updatedAtMillis,
)

/**
 * The summary of a finished workout as exchanged with other devices. A finished workout is immutable: after it
 * ends only its deletion (or being assigned to another user) produces a new [version].
 */
data class SyncWorkout(
    val uuid: String,
    /** The owner's [SyncProfile.uuid]. Pending workouts always have an owner (workouts without one are not uploaded). */
    val userUuid: String,
    /** Name of the owner when the record was read from the device, to name the folder of an owner not uploaded yet. */
    val ownerName: String,
    val version: RecordVersion,
    /** Tombstone: the workout was deleted and its samples dropped. Only the identity and times are meaningful then. */
    val deleted: Boolean,
    val startedAtMillis: Long,
    val endedAtMillis: Long,
    val endReason: WorkoutEndReason,
    val stats: WorkoutStats,
    /** The executed plan as a `PlanJson` document, or `null` (workouts without a program, and older ones). */
    val planJson: String? = null,
    /** The fitness test result that was run in this workout, or `null`. */
    val fitnessTestId: String? = null,
)

/** A [SyncWorkout] with its per-second samples, as downloaded from another device. */
class SyncWorkoutData(val workout: SyncWorkout, val samples: List<WorkoutSample>)

/** Local changes waiting to be uploaded (only records that are eligible, see [SyncStore.pendingChanges]). */
data class PendingChanges(
    val profiles: List<SyncProfile>,
    /** Pending entries, tombstones included. For the whole log of a user use [SyncStore.weightEntries]. */
    val weights: List<SyncWeightEntry>,
    val workouts: List<SyncWorkout>,
) {
    val isEmpty: Boolean get() = profiles.isEmpty() && weights.isEmpty() && workouts.isEmpty()

    /** Number of pending records. */
    val count: Int get() = profiles.size + weights.size + workouts.size
}

/** Changes read from the remote repository, to be merged into the local data. */
data class RemoteChanges(
    val profiles: List<SyncProfile> = emptyList(),
    val weights: List<SyncWeightEntry> = emptyList(),
    val workouts: List<SyncWorkoutData> = emptyList(),
)

/** What [SyncStore.applyRemote] did. */
data class ApplyResult(
    /** Records created or overwritten with the remote version. */
    val applied: Int,
    /** Records where the local version was newer (or equal) and was kept. */
    val keptLocal: Int,
    /** Records that could not be applied: the owner is unknown or deleted here, or the record is a tombstone of something unknown. */
    val skipped: Int,
)

/** Identity and version of a record that was uploaded (or found equal) and is no longer pending. */
data class SyncedRef(val uuid: String, val version: RecordVersion)

data class SyncedRecords(
    val profiles: List<SyncedRef> = emptyList(),
    val weights: List<SyncedRef> = emptyList(),
    val workouts: List<SyncedRef> = emptyList(),
)
