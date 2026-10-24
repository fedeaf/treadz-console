package com.treadz.console.userdata

import android.content.Context
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.platform.app.InstrumentationRegistry
import com.treadz.console.userdata.room.RoomUserData
import com.treadz.console.userdata.room.UserDataDatabase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.time.ZoneOffset

/**
 * Migration 7 to 8 (the decoupling removed from the analysis) with the real Room tooling: a populated version-7 database is
 * migrated and validated against the exported version-8 schema, keeps every row as it was (the analysis cache only loses
 * its two decoupling columns), and is served by the current data layer, which still reads the stored documents with
 * their old decoupling section.
 */
@RunWith(RobolectricTestRunner::class)
class Migration7To8Test {

    @get:Rule val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), UserDataDatabase::class.java)

    private val dbName = "migration-7-8.db"
    private val anaUuid = "5a0e0d2c-5b0f-4a56-9d6a-6f1f4b6d2c10"

    /** A document as version 7 of the database stored it: the analysis with a decoupling section. */
    private val oldDocument: String = WorkoutAnalysisJson.encode(fullAnalysis()).trimEnd().removeSuffix("}") +
        ""","decoupling":{"value":{"mainBlockSeconds":1500,"analysedSeconds":1200,"ef1":0.155,"ef2":0.15,""" +
        """"decouplingPercent":3.2,"zone12Fraction":0.82,"longSteadyZone2":true}}}"""

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
        "weights" to "SELECT * FROM weight_entries ORDER BY id",
        "workouts" to "SELECT * FROM workouts ORDER BY id",
        "samples" to "SELECT * FROM workout_samples ORDER BY workoutId, seq",
    )

    /** The columns the analysis cache keeps, in the order of the version-8 schema. */
    private val keptColumns = listOf(
        "workoutId", "algorithmVersion", "fingerprint", "computedAtMillis", "json", "trimp", "fitnessIndex",
        "recoveryDrop60Bpm", "recoveryDrop120Bpm", "recoveryContext",
    )

    private fun populateVersion7(db: SupportSQLiteDatabase) {
        db.execSQL(
            "INSERT INTO users (id, name, nameKey, sex, heightCm, birthEpochDay, restingHeartRateBpm, maxHeartRateBpm, fitnessLevel, goal, targetWeightKg, " +
                "createdAtMillis, isGeneric, uuid, updatedAtMillis, updatedBy, deleted, dirty, maxHeartRateSetAtMillis, strapAddress, strapName) VALUES " +
                "(1, 'Ana', 'ana', 'Female', 168, 6764, NULL, NULL, 'Intermediate', 'LoseWeight', 62.5, $T0, 0, '$anaUuid', ${T0 + 10}, 'tablet-1', 0, 0, NULL, NULL, NULL)",
        )
        db.execSQL(
            "INSERT INTO weight_entries (id, userId, epochDay, weightKg, isPlaceholder, uuid, updatedAtMillis, updatedBy, deleted, dirty, createdAtMillis) VALUES " +
                "(1, 1, 20000, 71.5, 0, 'w-1', ${T0 - 100}, 'tablet-1', 0, 0, ${T0 - 100})",
        )
        fun workout(id: Int, seconds: Int) {
            val start = T0 - (10 - id) * DAY_MS
            db.execSQL(
                "INSERT INTO workouts (id, userId, startedAtMillis, endedAtMillis, endReason, durationSeconds, distanceMeters, calories, averageSpeedKmh, maxSpeedKmh, " +
                    "averageHeartRateBpm, maxHeartRateBpm, maxInclinePercent, uuid, updatedAtMillis, updatedBy, deleted, dirty, peakHeartRateBpm, peakHeartRateSource, " +
                    "peakHeartRateAtMillis, sessionRecordJson, eventsJson) VALUES (?, 1, ?, ?, 'Stopped', $seconds, ${seconds * 2}, ${seconds / 20}, 8.0, 8.0, 140, 142, 1.0, " +
                    "?, ?, 'tablet-1', 0, 0, 142, 'DirectStrap', ${start + 5000}, NULL, NULL)",
                arrayOf<Any?>(id, start, start + seconds * 1000L, "workout-$id", start + seconds * 1000L),
            )
            for (seq in 0 until seconds) {
                db.execSQL(
                    "INSERT INTO workout_samples (workoutId, seq, timestampMillis, elapsedSeconds, speedKmh, targetSpeedKmh, inclinePercent, distanceMeters, calories, " +
                        "heartRateBpm, beltStatus, isStale, heartRateSource, rrIntervalsMs) VALUES ($id, $seq, ${start + seq * 1000L}, ${seq + 1}, 8.0, 8.0, 1.0, ${seq * 2}, ${seq / 20}, " +
                        "140, 'Running', 0, 'DirectStrap', '428')",
                )
            }
        }
        workout(1, seconds = 30)
        workout(2, seconds = 20)
        db.execSQL(
            "INSERT INTO workout_analysis (workoutId, algorithmVersion, fingerprint, computedAtMillis, json, trimp, fitnessIndex, decouplingPercent, " +
                "longSteadyZone2, recoveryDrop60Bpm, recoveryDrop120Bpm, recoveryContext) VALUES (1, 4, 'old', $T0, ?, 61.5, 41.7, 3.2, 1, 28.0, 41.5, 'BeltStopped')",
            arrayOf<Any?>(oldDocument),
        )
        db.execSQL(
            "INSERT INTO workout_analysis (workoutId, algorithmVersion, fingerprint, computedAtMillis, json, trimp, fitnessIndex, decouplingPercent, " +
                "longSteadyZone2, recoveryDrop60Bpm, recoveryDrop120Bpm, recoveryContext) VALUES (2, 4, 'old', ${T0 + 1}, '{}', NULL, NULL, NULL, 0, NULL, NULL, NULL)",
        )
    }

    @Test fun `the populated version 7 database migrates to version 8 keeping every row and dropping the decoupling columns`() {
        val before: Map<String, List<List<Any?>>>
        val analysesBefore: List<List<Any?>>
        helper.createDatabase(dbName, 7).use { db ->
            populateVersion7(db)
            before = tables.associate { (name, sql) -> name to rows(db, sql) }
            analysesBefore = rows(db, "SELECT ${keptColumns.joinToString { "`$it`" }} FROM workout_analysis ORDER BY workoutId")
        }
        assertEquals(listOf(1, 1, 2, 50), tables.map { (name, _) -> before.getValue(name).size })

        // Also validates the resulting schema against the exported 8.json.
        helper.runMigrationsAndValidate(dbName, 8, true, *UserDataDatabase.migrations("tablet-1")).use { db ->
            for ((name, sql) in tables) assertEquals(name, before.getValue(name), rows(db, sql))
            assertEquals(keptColumns, db.query("SELECT * FROM workout_analysis").use { c -> c.columnNames.toList() })
            assertEquals("every analysis row keeps its other values", analysesBefore, rows(db, "SELECT * FROM workout_analysis ORDER BY workoutId"))
            assertEquals(listOf(0L, 0L), rows(db, "SELECT dirty FROM workouts ORDER BY id").map { it.single() })
            // the foreign key survived the rebuild: the analysis goes with its workout, and cannot exist without one
            db.execSQL("PRAGMA foreign_keys = ON")
            assertTrue(runCatching { db.execSQL("INSERT INTO workout_analysis VALUES (99, 4, 'f', 1, '{}', NULL, NULL, NULL, NULL, NULL)") }.isFailure)
            db.execSQL("DELETE FROM workouts WHERE id = 2")
            assertEquals(listOf(1L), rows(db, "SELECT workoutId FROM workout_analysis").map { it.single() })
        }
    }

    @Test fun `after the migration the data layer reads the old documents and stores new ones without the decoupling`() = runBlocking<Unit> {
        helper.createDatabase(dbName, 7).use { populateVersion7(it) }
        helper.runMigrationsAndValidate(dbName, 8, true, *UserDataDatabase.migrations("tablet-1")).close()

        val context = RuntimeEnvironment.getApplication() as Context
        val db = Room.databaseBuilder(context, UserDataDatabase::class.java, dbName)
            .addMigrations(*UserDataDatabase.migrations("tablet-1"))
            .allowMainThreadQueries()
            .build()
        try {
            val data = RoomUserData(db, MutableClock(T0 + DAY_MS), ZoneOffset.UTC, deviceId = "tablet-1")
            assertEquals("the old document is read, its decoupling section ignored", fullAnalysis(), data.workoutAnalysis.analysis(1).first()!!.analysis)

            // The stored fingerprints are not the ones of the inputs: both analyses are computed again, and stored without it.
            assertEquals(2, data.workoutAnalysisRecalculator.recalculate())
            val json = db.query("SELECT json FROM workout_analysis WHERE workoutId = 1", null).use { c -> c.moveToFirst(); c.getString(0) }
            assertFalse(json.contains("decoupling"))
            assertEquals(30, data.workoutAnalysis.analysis(1).first()!!.analysis.signal.sampleCount)
        } finally {
            db.close()
        }
    }
}
