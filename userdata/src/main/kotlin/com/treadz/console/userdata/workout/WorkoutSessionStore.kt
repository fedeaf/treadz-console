package com.treadz.console.userdata.workout

import com.treadz.console.userdata.WorkoutEndReason
import com.treadz.console.userdata.WorkoutLink
import com.treadz.console.userdata.WorkoutSample
import com.treadz.console.userdata.WorkoutStats

/**
 * Write side of the workout history, used by [WorkoutHistoryObserver]. Every call is atomic: after a crash a
 * workout has either all the samples of a flush with its totals (the heart rate peak included, see
 * `WorkoutStats.peakHeartRate`), or none of them.
 */
interface WorkoutSessionStore {

    /**
     * Creates a workout in progress and returns its id. [userId] is `null` when nobody was active. [link] is the plan
     * and/or fitness test the workout is done for, if the app announced one.
     */
    suspend fun begin(userId: Long?, startedAtMillis: Long, link: WorkoutLink? = null): Long

    /**
     * Attaches [link] to a workout (the parts that are not `null` replace what it had). Returns `false` if the
     * workout no longer exists.
     */
    suspend fun attachLink(workoutId: Long, link: WorkoutLink): Boolean

    /**
     * Stores [samples] and the updated [stats] of a workout in progress.
     * Returns `false` (and stores nothing) if the workout no longer exists, e.g. its user was deleted.
     */
    suspend fun append(workoutId: Long, samples: List<WorkoutSample>, stats: WorkoutStats): Boolean

    /** Stores the last [samples] and closes the workout. Returns `false` if the workout no longer exists. */
    suspend fun finish(
        workoutId: Long,
        samples: List<WorkoutSample>,
        stats: WorkoutStats,
        endedAtMillis: Long,
        reason: WorkoutEndReason,
    ): Boolean

    /** Deletes a workout (used for sessions where the belt never moved). */
    suspend fun discard(workoutId: Long)

    /**
     * Closes the workouts left in progress by a process that died, with [WorkoutEndReason.Interrupted] and the
     * time of their last sample. Only valid while no session is being recorded. Returns how many were closed.
     */
    suspend fun closeInterrupted(): Int
}
