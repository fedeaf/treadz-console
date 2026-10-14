package com.treadz.console.userdata.export

import com.treadz.console.programs.fitness.HeartRateSourceKind
import com.treadz.console.treadmill.BeltStatus
import com.treadz.console.userdata.T0
import com.treadz.console.userdata.WorkoutDetail
import com.treadz.console.userdata.WorkoutEndReason
import com.treadz.console.userdata.WorkoutSample
import com.treadz.console.userdata.WorkoutStats
import com.treadz.console.userdata.WorkoutSummary

/** One second of a scripted workout. */
internal data class Sec(
    val status: BeltStatus,
    val speed: Double,
    val incline: Double,
    val distance: Int,
    val elapsed: Int,
    val calories: Int = 0,
    val hr: Int? = null,
    val stale: Boolean = false,
    /** Packets received in this second: the treadmill sends ~2 per second with repeated values. */
    val packets: Int = 2,
    val source: HeartRateSourceKind? = null,
    /** RR intervals that arrived with the first packet of the second, and with the repeated second one. */
    val rr: List<Int> = emptyList(),
    val rrLate: List<Int> = emptyList(),
)

internal fun summary(
    stats: WorkoutStats = WorkoutStats(),
    ended: Boolean = true,
    startedAt: Long = T0,
    id: Long = 7,
) = WorkoutSummary(
    id = id,
    userId = 1,
    startedAtMillis = startedAt,
    endedAtMillis = if (ended) startedAt + 60_000 else null,
    endReason = if (ended) WorkoutEndReason.Stopped else null,
    stats = stats,
)

/** Builds the samples the way the observer stores a 2 Hz stream with repeated values: one per packet. */
internal fun samplesOf(secs: List<Sec>, startAt: Long = T0): List<WorkoutSample> {
    val out = mutableListOf<WorkoutSample>()
    secs.forEachIndexed { i, s ->
        repeat(s.packets) { k ->
            out += WorkoutSample(
                seq = out.size,
                timestampMillis = startAt + i * 1000L + k * 500L,
                elapsedSeconds = s.elapsed,
                speedKmh = s.speed,
                targetSpeedKmh = if (s.status == BeltStatus.Running) 8.0 else null,
                inclinePercent = s.incline,
                distanceMeters = s.distance,
                calories = s.calories,
                heartRateBpm = s.hr,
                beltStatus = s.status,
                isStale = s.stale,
                heartRateSource = s.source,
                rrIntervalsMs = if (k == 0) s.rr else s.rrLate,
            )
        }
    }
    return out
}

internal fun detail(secs: List<Sec>, stats: WorkoutStats = WorkoutStats(), ended: Boolean = true) =
    WorkoutDetail(summary(stats, ended), samplesOf(secs))

private val DIRECT = HeartRateSourceKind.DirectStrap
private val CONSOLE = HeartRateSourceKind.TreadmillConsole

/**
 * Realistic workout: countdown, ramp-up, incline changes, a heart rate of 0 and a missing one mixed with real
 * readings, a pause with the clock still counting, a resume with a new countdown, a frozen (stale) sample and the
 * belt decelerating at the end. The heart rate comes from a strap connected to the app (with RR intervals, some of them
 * on the repeated packet that the export drops) and then from the console.
 */
internal val GOLDEN_SECS = listOf(
    Sec(BeltStatus.Starting, 0.0, 0.0, 0, 0),
    Sec(BeltStatus.Starting, 0.0, 0.0, 0, 0),
    Sec(BeltStatus.Running, 3.0, 0.0, 0, 1, hr = null),
    Sec(BeltStatus.Running, 5.5, 0.0, 1, 2, hr = 0),
    Sec(BeltStatus.Running, 8.0, 0.0, 3, 3, hr = 0),
    Sec(BeltStatus.Running, 8.0, 2.0, 5, 4, hr = 112, source = DIRECT, rr = listOf(540, 531), rrLate = listOf(528)),
    Sec(BeltStatus.Running, 8.0, 2.0, 7, 5, calories = 1, hr = 115, source = DIRECT, rr = listOf(520)),
    Sec(BeltStatus.Running, 8.0, 4.0, 9, 6, calories = 1, hr = 118, source = DIRECT, rrLate = listOf(510, 505)),
    Sec(BeltStatus.Running, 8.0, 4.0, 11, 7, calories = 1, hr = 121, source = DIRECT),
    Sec(BeltStatus.Paused, 0.0, 4.0, 11, 8, calories = 1, hr = 119, source = CONSOLE),
    Sec(BeltStatus.Paused, 0.0, 4.0, 11, 9, calories = 1, hr = 110, source = CONSOLE),
    Sec(BeltStatus.Starting, 0.0, 4.0, 11, 9, calories = 1, hr = 108, source = CONSOLE),
    Sec(BeltStatus.Running, 4.0, 4.0, 12, 10, calories = 1, hr = 109, source = CONSOLE),
    Sec(BeltStatus.Running, 4.0, 4.0, 12, 10, calories = 1, hr = 109, stale = true, packets = 1, source = CONSOLE),
    Sec(BeltStatus.Running, 6.0, 1.0, 14, 11, calories = 2, hr = 112, source = CONSOLE),
    Sec(BeltStatus.Idle, 2.0, 0.0, 15, 12, calories = 2, hr = 110, source = CONSOLE),
)

internal val GOLDEN_STATS = WorkoutStats(
    durationSeconds = 12,
    distanceMeters = 15,
    calories = 2,
    averageSpeedKmh = 6.1,
    maxSpeedKmh = 8.0,
    averageHeartRateBpm = 114,
    maxHeartRateBpm = 121,
    maxInclinePercent = 4.0,
)

internal fun goldenDetail() = detail(GOLDEN_SECS, GOLDEN_STATS)
