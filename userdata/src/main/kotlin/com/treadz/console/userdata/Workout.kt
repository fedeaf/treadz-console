package com.treadz.console.userdata

import com.treadz.console.programs.plan.ResolvedPlan
import com.treadz.console.treadmill.BeltStatus

/** How a stored workout ended. Stable codes (stored in the database): the UI translates them. */
enum class WorkoutEndReason {
    /** The belt came back to Idle with speed 0 after the user stopped it. */
    Stopped,

    /** The safety key was removed (emergency stop) and the session ended when the treadmill went back to Idle. */
    SafetyKeyRemoved,

    /** Bluetooth connection lost during the session (the treadmill stops by itself). */
    ConnectionLost,

    /** The connection was closed during the session. */
    Disconnected,

    /** The observer was stopped (e.g. the app service shut down) while the session was running. */
    Closed,

    /** The app process died during the session; found open at the next start and closed with the data flushed so far. */
    Interrupted,
}

/**
 * Per-second data point of a workout. The treadmill reports ~1 Hz, and every sample keeps the values as the
 * treadmill gave them, so the series is complete enough to export later (CSV, TCX, FIT).
 */
data class WorkoutSample(
    /** Position in the workout, from 0, without gaps. */
    val seq: Int,
    /** Epoch ms when the sample was received. */
    val timestampMillis: Long,
    /** Treadmill's own session time. It keeps counting while the belt is paused. */
    val elapsedSeconds: Int,
    val speedKmh: Double,
    val targetSpeedKmh: Double?,
    val inclinePercent: Double,
    val distanceMeters: Int,
    val calories: Int,
    /** `null` without a heart rate monitor. */
    val heartRateBpm: Int?,
    val beltStatus: BeltStatus,
    /** The values were not current (frozen data); they are excluded from the averages and maximums. */
    val isStale: Boolean = false,
)

/**
 * Totals of a workout. While the workout is in progress they are updated on every flush; after it ends they
 * are final.
 *
 * - [durationSeconds], [distanceMeters], [calories]: the highest value the treadmill reported (they only grow
 *   during a session, and the treadmill resets them to 0 when it goes back to Idle).
 * - [averageSpeedKmh]: mean speed of the non-stale samples with the belt moving (speed above 0), i.e. the
 *   moving average, not counting pauses.
 * - Heart rates: only samples with a heart rate reading; `null` if there was none.
 */
data class WorkoutStats(
    val durationSeconds: Int = 0,
    val distanceMeters: Int = 0,
    val calories: Int = 0,
    val averageSpeedKmh: Double = 0.0,
    val maxSpeedKmh: Double = 0.0,
    val averageHeartRateBpm: Int? = null,
    val maxHeartRateBpm: Int? = null,
    val maxInclinePercent: Double = 0.0,
)

data class WorkoutSummary(
    val id: Long,
    /** Owner. `null` for a workout done with no active user (see [WorkoutHistoryRepository.assign]). */
    val userId: Long?,
    val startedAtMillis: Long,
    /** `null` while the workout is in progress. */
    val endedAtMillis: Long?,
    /** `null` while the workout is in progress. */
    val endReason: WorkoutEndReason?,
    val stats: WorkoutStats,
    /** `source.programId` of the executed plan (a user program id or a `standard-...` id); `null` for a free workout. */
    val programId: String? = null,
    /** `source.programName` of the executed plan, as it was when the workout was done (English reference name for standard programs). */
    val programName: String? = null,
    /** Id of the fitness test result that was run in this workout (see [FitnessTestRepository]); `null` for a normal workout. */
    val fitnessTestId: String? = null,
) {
    val isInProgress: Boolean get() = endReason == null
}

data class WorkoutDetail(
    val summary: WorkoutSummary,
    /** Ordered by [WorkoutSample.seq]. */
    val samples: List<WorkoutSample>,
    /**
     * The executed plan snapshot (self-contained: it stays valid if the program is edited or deleted afterwards).
     * `null` for workouts without a program, workouts recorded before programs existed, and plans written by a newer
     * app than this one (unreadable).
     */
    val plan: ResolvedPlan? = null,
)

/**
 * What a workout was for, attached to it by the app: the plan that was executed and/or the fitness test that was run.
 * The workout history observer does not know about the program runner or the test runner; the app tells it (see
 * [com.treadz.console.userdata.workout.WorkoutHistoryObserver.linkNextSession]) or links a known workout directly
 * ([WorkoutHistoryRepository.link]).
 */
data class WorkoutLink(
    val plan: ResolvedPlan? = null,
    val fitnessTestId: String? = null,
) {
    val isEmpty: Boolean get() = plan == null && fitnessTestId == null

    /** This link with the parts that [other] has replaced by its own. */
    fun merge(other: WorkoutLink) = WorkoutLink(other.plan ?: plan, other.fitnessTestId ?: fitnessTestId)
}
