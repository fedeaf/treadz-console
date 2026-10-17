package com.treadz.console.userdata

import android.content.Context
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.platform.app.InstrumentationRegistry
import com.treadz.console.programs.fitness.FitnessTestJson
import com.treadz.console.userdata.room.RoomUserData
import com.treadz.console.userdata.room.UserDataDatabase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.time.ZoneOffset

/**
 * Migration 5 to 6 (the cache of the workout analyses) with the real Room tooling: a populated version-5 database is
 * migrated, validated against the exported version-6 schema, untouched except for the new empty table, and served by the
 * current data layer, which then computes the analyses.
 */
@RunWith(RobolectricTestRunner::class)
class Migration5To6Test {

    @get:Rule val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), UserDataDatabase::class.java)

    private val dbName = "migration-5-6.db"
    private val anaUuid = "5a0e0d2c-5b0f-4a56-9d6a-6f1f4b6d2c10"
    private val betoUuid = "5a0e0d2c-5b0f-4a56-9d6a-6f1f4b6d2c20"

    private fun rows(db: SupportSQLiteDatabase, sql: String): List<List<Any?>> = db.query(sql).use { c ->
        buildList {
            while (c.moveToNext()) {
                add(
                    (0 until c.columnCount).map { i ->
                        when (c.getType(i)) {
                            android.database.Cursor.FIELD_TYPE_NULL -> null
                            android.database.Cursor.FIELD_TYPE_INTEGER -> c.getLong(i)
                            android.database.Cursor.FIELD_TYPE_FLOAT -> c.getDouble(i)
                            else -> c.getString(i)
                        }
                    },
                )
            }
        }
    }

    private val tables = listOf(
        "users" to "SELECT * FROM users ORDER BY id",
        "active_user" to "SELECT * FROM active_user ORDER BY id",
        "weights" to "SELECT * FROM weight_entries ORDER BY id",
        "workouts" to "SELECT * FROM workouts ORDER BY id",
        "samples" to "SELECT * FROM workout_samples ORDER BY workoutId, seq",
        "tests" to "SELECT * FROM fitness_tests ORDER BY id",
        "resting" to "SELECT * FROM resting_heart_rates ORDER BY id",
    )

    /** Ana (a weight, a resting measurement, a test), Beto, and four workouts: Ana's, Beto's, one in progress and one with no owner. */
    private fun populateVersion5(db: SupportSQLiteDatabase) {
        db.execSQL(
            "INSERT INTO users (id, name, nameKey, sex, heightCm, birthEpochDay, restingHeartRateBpm, maxHeartRateBpm, fitnessLevel, goal, targetWeightKg, " +
                "createdAtMillis, isGeneric, uuid, updatedAtMillis, updatedBy, deleted, dirty, maxHeartRateSetAtMillis, strapAddress, strapName) VALUES " +
                "(1, 'Ana', 'ana', 'Female', 168, 6764, NULL, NULL, 'Intermediate', 'LoseWeight', 62.5, $T0, 0, '$anaUuid', ${T0 + 10}, 'tablet-1', 0, 0, NULL, 'AA:BB:CC:DD:EE:FF', 'H9Z'), " +
                "(2, 'Beto', 'beto', 'Male', 181, 5000, NULL, 184, NULL, NULL, NULL, ${T0 + 5000}, 0, '$betoUuid', ${T0 + 20}, 'phone-2', 0, 1, ${T0 + 5}, NULL, NULL)",
        )
        db.execSQL("INSERT INTO active_user (id, userId) VALUES (1, 1)")
        db.execSQL(
            "INSERT INTO weight_entries (id, userId, epochDay, weightKg, isPlaceholder, uuid, updatedAtMillis, updatedBy, deleted, dirty, createdAtMillis) VALUES " +
                "(1, 1, 20000, 71.5, 0, 'w-1', ${T0 - 100}, 'tablet-1', 0, 0, ${T0 - 100})",
        )
        db.execSQL(
            "INSERT INTO resting_heart_rates (id, userId, uuid, measuredAtMillis, bpm, source, lowestMeanBpm, coverage, rmssdMs, updatedAtMillis, updatedBy, deleted, dirty) VALUES " +
                "(1, 1, 'r-1', ${T0 - 86_400_000L}, 57, 'Strap', 56.2, 0.97, 42.5, ${T0 - 86_400_000L}, 'tablet-1', 0, 0)",
        )
        db.execSQL(
            "INSERT INTO fitness_tests (id, userId, uuid, testType, startedAtMillis, isValid, vo2maxMlKgMin, masKmh, json, updatedAtMillis, updatedBy, deleted, dirty) VALUES " +
                "(1, 1, '$TEST_2', 'MaxIncrementalRun', ${T0 - 20 * DAY_MS}, 1, 47.25, 13.5, ?, ${T0 - 20 * DAY_MS}, 'tablet-1', 0, 0)",
            arrayOf<Any?>(FitnessTestJson.encode(maxTestResult(TEST_2, T0 - 20 * DAY_MS))),
        )
        fun workout(id: Int, user: Int?, ended: Boolean, seconds: Int) {
            val start = T0 - (10 - id) * DAY_MS
            db.execSQL(
                "INSERT INTO workouts (id, userId, startedAtMillis, endedAtMillis, endReason, durationSeconds, distanceMeters, calories, averageSpeedKmh, maxSpeedKmh, " +
                    "averageHeartRateBpm, maxHeartRateBpm, maxInclinePercent, uuid, updatedAtMillis, updatedBy, deleted, dirty, peakHeartRateBpm, peakHeartRateSource, " +
                    "peakHeartRateAtMillis) VALUES (?, ?, ?, ?, ?, $seconds, ${seconds * 2}, ${seconds / 20}, 8.0, 8.0, 140, 142, 1.0, ?, ?, 'tablet-1', 0, ?, 142, 'DirectStrap', ${start + 5000})",
                arrayOf<Any?>(id, user, start, if (ended) start + seconds * 1000L else null, if (ended) "Stopped" else null, "workout-$id", start + seconds * 1000L, if (ended) 1 else 0),
            )
            for (seq in 0 until seconds) {
                db.execSQL(
                    "INSERT INTO workout_samples (workoutId, seq, timestampMillis, elapsedSeconds, speedKmh, targetSpeedKmh, inclinePercent, distanceMeters, calories, " +
                        "heartRateBpm, beltStatus, isStale, heartRateSource, rrIntervalsMs) VALUES ($id, $seq, ${start + seq * 1000L}, ${seq + 1}, 8.0, 8.0, 1.0, ${seq * 2}, ${seq / 20}, " +
                        "${140 + seq / 120}, 'Running', 0, 'DirectStrap', '${60_000 / (140 + seq / 120)}')",
                )
            }
        }
        workout(1, 1, ended = true, seconds = 300)
        workout(2, 2, ended = true, seconds = 240)
        workout(3, 1, ended = false, seconds = 20)
        workout(4, null, ended = true, seconds = 200)
    }

    @Test fun `the populated version 5 database migrates to version 6 keeping every row and adding the empty analysis table`() {
        val before: Map<String, List<List<Any?>>>
        helper.createDatabase(dbName, 5).use { db ->
            populateVersion5(db)
            before = tables.associate { (name, sql) -> name to rows(db, sql) }
        }
        assertEquals(listOf(2, 1, 1, 4, 760, 1, 1), tables.map { (name, _) -> before.getValue(name).size })

        // Also validates the resulting schema against the exported 6.json.
        helper.runMigrationsAndValidate(dbName, 6, true, *UserDataDatabase.migrations("tablet-1")).use { db ->
            for ((name, sql) in tables) assertEquals(name, before.getValue(name), rows(db, sql))
            assertEquals(emptyList<List<Any?>>(), rows(db, "SELECT * FROM workout_analysis"))
            assertEquals(
                listOf("workoutId", "algorithmVersion", "fingerprint", "computedAtMillis", "json", "trimp", "fitnessIndex", "decouplingPercent", "longSteadyZone2", "recoveryDrop60Bpm", "recoveryDrop120Bpm", "recoveryContext"),
                db.query("SELECT * FROM workout_analysis").use { c -> c.columnNames.toList() },
            )
            // the foreign key: the analysis goes with its workout, and cannot exist without one
            db.execSQL("PRAGMA foreign_keys = ON")
            assertTrue(runCatching { db.execSQL("INSERT INTO workout_analysis VALUES (99, 1, 'f', 1, '{}', NULL, NULL, NULL, 0, NULL, NULL, NULL)") }.isFailure)
            db.execSQL("INSERT INTO workout_analysis VALUES (1, 1, 'f', 1, '{}', 55.5, NULL, NULL, 0, NULL, NULL, NULL)")
            assertEquals(1L, rows(db, "SELECT COUNT(*) FROM workout_analysis").single().single())
            db.execSQL("DELETE FROM workouts WHERE id = 1")
            assertEquals(0L, rows(db, "SELECT COUNT(*) FROM workout_analysis").single().single())
        }
    }

    @Test fun `after the migration the data layer computes the analyses of the finished workouts that belong to somebody`() = runBlocking<Unit> {
        helper.createDatabase(dbName, 5).use { populateVersion5(it) }
        helper.runMigrationsAndValidate(dbName, 6, true, *UserDataDatabase.migrations("tablet-1")).close()

        val context = RuntimeEnvironment.getApplication() as Context
        val db = Room.databaseBuilder(context, UserDataDatabase::class.java, dbName)
            .addMigrations(*UserDataDatabase.migrations("tablet-1"))
            .allowMainThreadQueries()
            .build()
        try {
            val data = RoomUserData(db, MutableClock(T0 + DAY_MS), ZoneOffset.UTC, deviceId = "tablet-1")
            val ana = data.profiles.profiles.first().first { it.name == "Ana" }
            val beto = data.profiles.profiles.first().first { it.name == "Beto" }
            assertEquals(emptyList<DatedWorkoutAnalysis>(), data.workoutAnalysis.analyses(ana.id).first())
            assertNull(data.workoutAnalysis.analysis(1).first())

            assertEquals("Ana's and Beto's finished workouts; not the one in progress nor the one with no owner", 2, data.workoutAnalysisRecalculator.recalculate())
            val anas = data.workoutAnalysis.analyses(ana.id).first()
            assertEquals(listOf(1L), anas.map { it.workoutId })
            assertEquals(300, anas.single().analysis.signal.sampleCount)
            assertNotNull(anas.single().analysis.trimp.value)
            assertEquals(listOf(2L), data.workoutAnalysis.analyses(beto.id).first().map { it.workoutId })
            assertNull(data.workoutAnalysis.analysis(3).first())
            assertNull(data.workoutAnalysis.analysis(4).first())
            // Ana's workout was analysed with her test of eleven days before: its measured maximum heart rate, in the zones
            assertEquals(185, anas.single().analysis.zoneTime.value!!.zones.zones.last().highBpm)
            assertEquals(0, data.workoutAnalysisRecalculator.recalculate())

            // the rest of the data is served as before
            assertEquals(71.5, data.weights.latestWeight(ana.id).first()!!.weightKg, 0.0)
            assertEquals(57, data.restingHeartRates.reference(ana.id).first()!!.bpm)
            assertEquals(4, listOf(1L, 2L, 3L, 4L).count { data.workouts.detail(it) != null })
            assertEquals(1, data.workoutAnalysis.performanceInputs(ana.id).first().tests.size)
        } finally {
            db.close()
        }
    }
}
