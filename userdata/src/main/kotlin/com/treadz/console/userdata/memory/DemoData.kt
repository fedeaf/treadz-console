package com.treadz.console.userdata.memory

import com.treadz.console.treadmill.BeltStatus
import com.treadz.console.userdata.FitnessLevel
import com.treadz.console.userdata.Goal
import com.treadz.console.userdata.ProfileInput
import com.treadz.console.userdata.Sex
import com.treadz.console.userdata.UserProfile
import com.treadz.console.userdata.WorkoutSample
import com.treadz.console.userdata.WorkoutStats
import kotlinx.coroutines.flow.first
import java.time.LocalDate
import java.time.ZoneId
import kotlin.math.roundToInt
import kotlin.math.sin

/** Ready-made content for the UI's tests and screenshots. */
object DemoData {

    /**
     * Replaces the generic profile that a fresh [InMemoryUserData] starts with by two demo users (the first one
     * active) and fills them with twelve weeks of weight entries, a target weight, and a few
     * finished workouts each, plus one workout done with nobody active. Ana also gets a free program ([DemoPrograms]),
     * the start parameters last used for the standard Hill program, and two fitness tests (an Ebbeling walk 40 days
     * ago and a maximal test 10 days ago); Beto has none of those. Returns the two profiles.
     */
    suspend fun populate(data: InMemoryUserData, today: LocalDate, zone: ZoneId = ZoneId.systemDefault()): Pair<UserProfile, UserProfile> {
        fun ok(r: com.treadz.console.userdata.DataResult<UserProfile>) = (r as com.treadz.console.userdata.DataResult.Success).value
        val ana = ok(
            data.profiles.create(
                ProfileInput(
                    "Ana", Sex.Female, 168, today.minusYears(39).minusDays(30), restingHeartRateBpm = 58,
                    fitnessLevel = FitnessLevel.Intermediate, goal = Goal.LoseWeight,
                ),
            ),
        )
        val beto = ok(
            data.profiles.create(
                ProfileInput(
                    "Beto", Sex.Male, 181, today.minusYears(42).minusDays(100), restingHeartRateBpm = 64, maxHeartRateBpm = 184,
                    fitnessLevel = FitnessLevel.Beginner, goal = Goal.ImproveFitness,
                ),
            ),
        )
        // Out with the generic profile (and its placeholder weight): the demo users take its place.
        data.profiles.profiles.first().filter { it.isGeneric }.forEach { data.profiles.delete(it.id) }
        data.profiles.setActiveUser(ana.id)
        for (week in 0 until 12) {
            val date = today.minusWeeks((11 - week).toLong())
            data.weights.add(ana.id, date, 68.0 - week * 0.35)
            data.weights.add(beto.id, date, 92.0 - week * 0.5)
        }
        data.weights.setTargetWeight(ana.id, 62.0)
        data.weights.setTargetWeight(beto.id, 84.0)

        fun startOf(daysAgo: Int) = today.minusDays(daysAgo.toLong()).atTime(18, 30).atZone(zone).toInstant().toEpochMilli()
        data.addWorkout(ana.id, 30 * 60, 9.5, 135, startOf(1))
        data.addWorkout(ana.id, 45 * 60, 8.0, 142, startOf(4))
        data.addWorkout(ana.id, 20 * 60, 10.5, 150, startOf(8))
        data.addWorkout(beto.id, 25 * 60, 7.0, 128, startOf(2))
        data.addWorkout(beto.id, 40 * 60, 6.5, 131, startOf(6))
        data.addWorkout(null, 15 * 60, 7.5, null, startOf(0))

        data.programs.save(ana.id, DemoPrograms.norwegian4x4())
        data.startParameters.save(
            ana.id, "standard-hill",
            com.treadz.console.programs.plan.StartParameters(
                totalDurationSeconds = 2400, easySpeedKmh = 5.0, maxSpeedKmh = 10.0, maxInclinePercent = 8.0, intensityAdjustmentPercent = 5,
            ),
        )
        val anaSex = com.treadz.console.analysis.fitness.Sex.Female
        data.fitnessTests.save(ana.id, DemoPrograms.ebbeling(startedAtMillis = startOf(40), vo2max = 38.5, ageYears = 39, sex = anaSex, weightKg = 66.0))
        data.fitnessTests.save(ana.id, DemoPrograms.maxTest(startedAtMillis = startOf(10), masKmh = 12.0, peakBpm = 183, ageYears = 39, sex = anaSex, weightKg = 65.0))
        return ana to beto
    }

    private fun InMemoryUserData.addWorkout(userId: Long?, seconds: Int, kmh: Double, hr: Int?, startedAtMillis: Long) {
        val samples = (0 until seconds).map { i ->
            val speed = if (i < 60) kmh * i / 60 else kmh
            WorkoutSample(
                seq = i,
                timestampMillis = startedAtMillis + i * 1000L,
                elapsedSeconds = i,
                speedKmh = speed,
                targetSpeedKmh = kmh,
                inclinePercent = if (i < 300) 0.0 else 2.0 + sin(i / 120.0).coerceAtLeast(0.0) * 2,
                distanceMeters = (i * kmh / 3.6).roundToInt(),
                calories = i / 9,
                heartRateBpm = hr?.let { it - 15 + (15.0 * (i.coerceAtMost(240) / 240.0)).roundToInt() },
                beltStatus = BeltStatus.Running,
            )
        }
        val hrs = samples.mapNotNull { it.heartRateBpm }
        val stats = WorkoutStats(
            durationSeconds = seconds,
            distanceMeters = samples.last().distanceMeters,
            calories = samples.last().calories,
            averageSpeedKmh = samples.filter { it.speedKmh > 0 }.map { it.speedKmh }.average(),
            maxSpeedKmh = kmh,
            averageHeartRateBpm = hrs.takeIf { it.isNotEmpty() }?.average()?.roundToInt(),
            maxHeartRateBpm = hrs.maxOrNull(),
            maxInclinePercent = samples.maxOf { it.inclinePercent },
        )
        addWorkout(userId, stats, samples, startedAtMillis)
    }
}
