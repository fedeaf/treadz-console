package com.treadz.console.userdata

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
) {
    val isInProgress: Boolean get() = endReason == null
}

data class WorkoutDetail(
    val summary: WorkoutSummary,
    /** Ordered by [WorkoutSample.seq]. */
    val samples: List<WorkoutSample>,
)
