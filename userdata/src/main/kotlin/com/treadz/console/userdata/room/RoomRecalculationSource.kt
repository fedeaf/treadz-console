package com.treadz.console.userdata.room

import androidx.room.withTransaction
import com.treadz.console.programs.fitness.FitnessTestRecalculation
import com.treadz.console.programs.fitness.FitnessTestResult
import com.treadz.console.userdata.FitnessTestRecord
import com.treadz.console.userdata.LinkedWorkout
import com.treadz.console.userdata.RecalculationSource
import com.treadz.console.userdata.RestingPoint

/** The Room rows the recalculation of the stored fitness test results reads and writes (see `FitnessRecalculator`). */
internal class RoomRecalculationSource(
    private val db: UserDataDatabase,
    private val fitnessTests: RoomFitnessTestRepository,
) : RecalculationSource {

    override suspend fun candidates(): List<FitnessTestRecord> =
        db.fitnessTests().liveValidOfTypes(FitnessTestRecalculation.ANALYSED_TESTS.map { it.name }).mapNotNull { it.toRecord() }

    override suspend fun linkedWorkout(testId: String): LinkedWorkout? = db.withTransaction {
        val workout = db.workouts().linkedToTest(testId) ?: return@withTransaction null
        LinkedWorkout(workout.endReason == null, db.workouts().samples(workout.id).map { it.toSample() })
    }

    override suspend fun restingPoints(userId: Long): List<RestingPoint> =
        db.restingHeartRates().liveForUser(userId).map { RestingPoint(it.measuredAtMillis, it.bpm) }

    override suspend fun save(userId: Long, result: FitnessTestResult) {
        fitnessTests.save(userId, result)
    }
}
