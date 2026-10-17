package com.treadz.console.fakes

import com.treadz.console.analysis.thresholds.ThresholdQuality
import com.treadz.console.programs.fitness.HeartRateSourceKind
import com.treadz.console.treadmill.BeltStatus
import com.treadz.console.userdata.RestingHeartRateInput
import com.treadz.console.userdata.RestingHeartRateSource
import com.treadz.console.userdata.WorkoutSample
import com.treadz.console.userdata.WorkoutStats
import com.treadz.console.userdata.memory.InMemoryUserData
import kotlinx.coroutines.runBlocking
import java.time.LocalDate
import java.time.ZoneId
import java.util.Random
import kotlin.math.exp
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Synthetic workouts with heart rate (and strap RR), fitness tests and resting measurements, for the Performance and
 * workout analysis tests and screenshots. Nothing here is real data.
 */
object PerformanceFixtures {

    /** How a synthetic workout ends. */
    enum class Ending { StopsBelt, SlowsDown, Steady }

    /** Where the heart rate comes from. */
    enum class Heart { Strap, Console, Unknown, None }

    /**
     * One steady session: warm-up ramp, [minutes] at [speedKmh] / [inclinePercent] with the heart rate drifting from
     * [hrStart] to [hrEnd], and an [ending]. With a strap the RR intervals are generated: correlated noise in the first 60 %
     * (α1 around 1) and white noise after it (α1 around 0.5), so both sides of the 0.75 cut appear.
     */
    data class Session(
        val minutes: Int = 45,
        val speedKmh: Double = 5.5,
        val inclinePercent: Double = 8.0,
        val hrStart: Int = 124,
        val hrEnd: Int = 133,
        val heart: Heart = Heart.Strap,
        val ending: Ending = Ending.StopsBelt,
        val tailSeconds: Int = 180,
        val seed: Long = 1,
    )

    fun samples(session: Session, startedAtMillis: Long): List<WorkoutSample> {
        val mainSeconds = session.minutes * 60
        val rng = Random(session.seed)
        val out = ArrayList<WorkoutSample>()
        var distance = 0.0
        var calories = 0.0
        var noise = 0.0
        var nextBeatMs = 0.0
        val total = mainSeconds + if (session.ending == Ending.Steady) 0 else session.tailSeconds
        val sourceKind = when (session.heart) {
            Heart.Strap -> HeartRateSourceKind.DirectStrap
            Heart.Console -> HeartRateSourceKind.TreadmillConsole
            else -> null
        }
        for (i in 0 until total) {
            val inMain = i < mainSeconds
            val speed = when {
                inMain -> min(session.speedKmh, session.speedKmh * (i + 1) / 30.0)
                session.ending == Ending.StopsBelt -> 0.0
                else -> 3.0
            }
            val incline = if (inMain) session.inclinePercent else 0.0
            val hr: Int = if (inMain) {
                val warm = 15 * exp(-i / 90.0)
                val drift = (session.hrEnd - session.hrStart) * (i.toDouble() / mainSeconds)
                // The last 90 s climb a little, so the effort ends at its peak (as when the person speeds up before stopping).
                val finish = 6.0 * ((i - (mainSeconds - 90)) / 90.0).coerceIn(0.0, 1.0)
                (session.hrStart + drift - warm + finish + 1.6 * sin(i / 23.0)).roundToInt()
            } else {
                val t = (i - mainSeconds).toDouble()
                val tau = if (session.ending == Ending.StopsBelt) 70.0 else 140.0
                ((lastHrOfMain(session) + 6) * exp(-t / tau) + 70 * (1 - exp(-t / tau)) + 0.8 * sin(i / 5.0)).roundToInt()
            }
            distance += speed / 3.6
            calories += speed * 0.027 // a rough console counter: only a number to show next to the estimate
            val rr = ArrayList<Int>()
            if (session.heart == Heart.Strap) {
                val correlated = i < mainSeconds * 0.6
                val period = 60_000.0 / hr
                while (nextBeatMs < (i + 1) * 1000.0) {
                    noise = if (correlated) 0.93 * noise + 0.008 * rng.nextGaussian() else 0.02 * rng.nextGaussian()
                    val value = (period * (1 + noise.coerceIn(-0.12, 0.12))).roundToInt()
                    rr += value
                    nextBeatMs += value
                }
            }
            out += WorkoutSample(
                seq = i, timestampMillis = startedAtMillis + i * 1000L, elapsedSeconds = i, speedKmh = speed, targetSpeedKmh = speed,
                inclinePercent = incline, distanceMeters = distance.roundToInt(), calories = calories.roundToInt(),
                heartRateBpm = if (session.heart == Heart.None) null else hr,
                beltStatus = if (speed > 0) BeltStatus.Running else BeltStatus.Paused,
                heartRateSource = sourceKind, rrIntervalsMs = rr,
            )
        }
        return out
    }

    private fun lastHrOfMain(session: Session) = session.hrEnd

    fun stats(samples: List<WorkoutSample>): WorkoutStats {
        val hrs = samples.mapNotNull { it.heartRateBpm }
        val moving = samples.filter { it.speedKmh > 0 }
        return WorkoutStats(
            durationSeconds = samples.size,
            distanceMeters = samples.last().distanceMeters,
            calories = samples.last().calories,
            averageSpeedKmh = moving.map { it.speedKmh }.average(),
            maxSpeedKmh = moving.maxOf { it.speedKmh },
            averageHeartRateBpm = hrs.takeIf { it.isNotEmpty() }?.average()?.roundToInt(),
            maxHeartRateBpm = hrs.maxOrNull(),
            maxInclinePercent = samples.maxOf { it.inclinePercent },
        )
    }

    /** Adds [session] as a finished workout of [userId] [daysAgo] days before [today] (18:30); returns the workout id. */
    fun addSession(data: InMemoryUserData, userId: Long?, today: LocalDate, daysAgo: Int, session: Session, zone: ZoneId = ZoneId.systemDefault()): Long {
        val start = today.minusDays(daysAgo.toLong()).atTime(18, 30).atZone(zone).toInstant().toEpochMilli()
        val s = samples(session, start)
        return data.addWorkout(userId, stats(s), s, start)
    }

    private val zone2 = Session()
    private val tempo = Session(minutes = 30, speedKmh = 6.5, inclinePercent = 9.0, hrStart = 140, hrEnd = 148, ending = Ending.SlowsDown, tailSeconds = 180, seed = 5)

    /**
     * Ten weeks of training for [userId]: steady zone 2 sessions and harder ones, all with a strap (so with RR) except two
     * without heart rate and one with the console's. The older the session, the higher its heart rate for the same load,
     * so the fitness index rises. Also four walking tests with thresholds (one of them a lower bound), resting measurements
     * with RMSSD. Call `workoutAnalysisRecalculator.recalculate()` afterwards.
     */
    fun addTraining(data: InMemoryUserData, userId: Long, today: LocalDate, zone: ZoneId = ZoneId.systemDefault()): List<Long> {
        val plan = listOf(
            66 to 'A', 63 to 'B', 59 to 'A', 56 to 'A', 52 to 'B', 49 to 'A', 45 to 'A', 42 to 'B', 38 to 'A', 35 to 'A',
            31 to 'B', 28 to 'N', 24 to 'A', 21 to 'B', 17 to 'A', 14 to 'A', 10 to 'B', 7 to 'N', 5 to 'A', 2 to 'B',
        )
        return plan.mapIndexed { index, (daysAgo, kind) ->
            val older = (daysAgo * 0.12).roundToInt()
            val session = when (kind) {
                'A' -> zone2.copy(hrStart = 118 + older, hrEnd = 126 + older + (index % 3), seed = 10L + index)
                'B' -> tempo.copy(hrStart = 140 + older, hrEnd = 148 + older, seed = 40L + index)
                else -> zone2.copy(minutes = 35, heart = Heart.None, seed = 70L + index)
            }
            addSession(data, userId, today, daysAgo, session, zone)
        }
    }

    /** Four walking-test results of [userId]: improving VO2max and thresholds, one test not maximal (lower bound), one low quality. */
    fun addTests(data: InMemoryUserData, userId: Long, today: LocalDate, zone: ZoneId = ZoneId.systemDefault()) = runBlocking {
        fun at(daysAgo: Int) = today.minusDays(daysAgo.toLong()).atTime(9, 0).atZone(zone).toInstant().toEpochMilli()
        data.fitnessTests.save(userId, GeneratedFixtures.balke(id = "0b6f8f9e-4d0c-4d43-9f43-2f0c6f1a7c01", startedAtMillis = at(75), vo2max = 41.0, thresholds = ThresholdFixtures.found(ThresholdQuality.Medium, 127.0, 145.0)))
        data.fitnessTests.save(userId, GeneratedFixtures.balke(id = "0b6f8f9e-4d0c-4d43-9f43-2f0c6f1a7c02", startedAtMillis = at(50), vo2max = 38.0, vo2maxIsLowerBound = true, peakBpm = 160.0, thresholds = ThresholdFixtures.found(ThresholdQuality.Low, 124.0, null)))
        data.fitnessTests.save(userId, GeneratedFixtures.balke(id = "0b6f8f9e-4d0c-4d43-9f43-2f0c6f1a7c03", startedAtMillis = at(28), vo2max = 43.2, thresholds = ThresholdFixtures.found(ThresholdQuality.High, 130.0, 148.0)))
        data.fitnessTests.save(userId, GeneratedFixtures.balke(id = "0b6f8f9e-4d0c-4d43-9f43-2f0c6f1a7c04", startedAtMillis = at(4), vo2max = 44.6, thresholds = ThresholdFixtures.found(ThresholdQuality.High, 133.0, 150.0)))
    }

    /** Resting measurements every third day for five weeks, the rate falling and the RMSSD rising. */
    fun addResting(data: InMemoryUserData, userId: Long, today: LocalDate, zone: ZoneId = ZoneId.systemDefault()) = runBlocking {
        for (k in 0..11) {
            val daysAgo = 35 - k * 3
            val at = today.minusDays(daysAgo.toLong()).atTime(7, 15).atZone(zone).toInstant().toEpochMilli()
            data.restingHeartRates.add(
                userId,
                RestingHeartRateInput(at, bpm = 61 - k / 3 + (k % 2), source = RestingHeartRateSource.Strap, lowestMeanBpm = 60.0, coverage = 0.98, rmssdMs = 36.0 + k * 1.2 + (k % 3)),
            )
        }
    }
}
