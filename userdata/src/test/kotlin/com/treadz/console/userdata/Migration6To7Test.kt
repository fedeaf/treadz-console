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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.time.ZoneOffset

/**
 * Migration 6 to 7 (the events of a workout) with the real Room tooling: a populated version-6 database is migrated and
 * validated against the exported version-7 schema, keeps every row as it was (the only change is the new empty column),
 * and is served by the current data layer, where the events work.
 */
@RunWith(RobolectricTestRunner::class)
class Migration6To7Test {

    @get:Rule val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), UserDataDatabase::class.java)

    private val dbName = "migration-6-7.db"
    private val anaUuid = "5a0e0d2c-5b0f-4a56-9d6a-6f1f4b6d2c10"

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
        "samples" to "SELECT * FROM workout_samples ORDER BY workoutId, seq",
        "resting" to "SELECT * FROM resting_heart_rates ORDER BY id",
    )

    private fun populateVersion6(db: SupportSQLiteDatabase) {
        db.execSQL(
            "INSERT INTO users (id, name, nameKey, sex, heightCm, birthEpochDay, restingHeartRateBpm, maxHeartRateBpm, fitnessLevel, goal, targetWeightKg, " +
                "createdAtMillis, isGeneric, uuid, updatedAtMillis, updatedBy, deleted, dirty, maxHeartRateSetAtMillis, strapAddress, strapName) VALUES " +
                "(1, 'Ana', 'ana', 'Female', 168, 6764, NULL, NULL, 'Intermediate', 'LoseWeight', 62.5, $T0, 0, '$anaUuid', ${T0 + 10}, 'tablet-1', 0, 0, NULL, NULL, NULL)",
        )
        db.execSQL(
            "INSERT INTO weight_entries (id, userId, epochDay, weightKg, isPlaceholder, uuid, updatedAtMillis, updatedBy, deleted, dirty, createdAtMillis) VALUES " +
                "(1, 1, 20000, 71.5, 0, 'w-1', ${T0 - 100}, 'tablet-1', 0, 0, ${T0 - 100})",
        )
        db.execSQL(
            "INSERT INTO resting_heart_rates (id, userId, uuid, measuredAtMillis, bpm, source, lowestMeanBpm, coverage, rmssdMs, updatedAtMillis, updatedBy, deleted, dirty) VALUES " +
                "(1, 1, 'r-1', ${T0 - DAY_MS}, 57, 'Strap', 56.2, 0.97, 42.5, ${T0 - DAY_MS}, 'tablet-1', 0, 0)",
        )
        fun workout(id: Int, ended: Boolean, dirty: Int, seconds: Int) {
            val start = T0 - (10 - id) * DAY_MS
            db.execSQL(
                "INSERT INTO workouts (id, userId, startedAtMillis, endedAtMillis, endReason, durationSeconds, distanceMeters, calories, averageSpeedKmh, maxSpeedKmh, " +
                    "averageHeartRateBpm, maxHeartRateBpm, maxInclinePercent, uuid, updatedAtMillis, updatedBy, deleted, dirty, peakHeartRateBpm, peakHeartRateSource, " +
                    "peakHeartRateAtMillis, sessionRecordJson) VALUES (?, 1, ?, ?, ?, $seconds, ${seconds * 2}, ${seconds / 20}, 8.0, 8.0, 140, 142, 1.0, ?, ?, 'tablet-1', 0, ?, " +
                    "142, 'DirectStrap', ${start + 5000}, NULL)",
                arrayOf<Any?>(id, start, if (ended) start + seconds * 1000L else null, if (ended) "Stopped" else null, "workout-$id", start + seconds * 1000L, dirty),
            )
            for (seq in 0 until seconds) {
                db.execSQL(
                    "INSERT INTO workout_samples (workoutId, seq, timestampMillis, elapsedSeconds, speedKmh, targetSpeedKmh, inclinePercent, distanceMeters, calories, " +
                        "heartRateBpm, beltStatus, isStale, heartRateSource, rrIntervalsMs) VALUES ($id, $seq, ${start + seq * 1000L}, ${seq + 1}, 8.0, 8.0, 1.0, ${seq * 2}, ${seq / 20}, " +
                        "140, 'Running', 0, 'DirectStrap', '428')",
                )
            }
        }
        workout(1, ended = true, dirty = 0, seconds = 30)
        workout(2, ended = true, dirty = 1, seconds = 20)
        workout(3, ended = false, dirty = 1, seconds = 10)
    }

    @Test fun `the populated version 6 database migrates to version 7 keeping every row and adding the empty events column`() {
        val before: Map<String, List<List<Any?>>>
        val workoutsBefore: List<List<Any?>>
        helper.createDatabase(dbName, 6).use { db ->
            populateVersion6(db)
            before = tables.associate { (name, sql) -> name to rows(db, sql) }
            workoutsBefore = rows(db, "SELECT * FROM workouts ORDER BY id")
        }
        // Also validates the resulting schema against the exported 7.json.
        helper.runMigrationsAndValidate(dbName, 7, true, *UserDataDatabase.migrations("tablet-1")).use { db ->
            for ((name, sql) in tables) assertEquals(name, before.getValue(name), rows(db, sql))
            val after = rows(db, "SELECT * FROM workouts ORDER BY id")
            assertEquals("each workout row is the old one plus the new column, empty", workoutsBefore.map { it + listOf(null) }, after)
            val columns = db.query("SELECT * FROM workouts").use { c -> c.columnNames.toList() }
            assertEquals("eventsJson", columns.last())
            assertEquals(listOf(listOf(0L, 1L, 1L)), listOf(rows(db, "SELECT dirty FROM workouts ORDER BY id").map { it.single() as Long }))
        }
    }

    @Test fun `after the migration the data layer reads the workouts without events and stores new ones`() = runBlocking<Unit> {
        helper.createDatabase(dbName, 6).use { populateVersion6(it) }
        helper.runMigrationsAndValidate(dbName, 7, true, *UserDataDatabase.migrations("tablet-1")).close()

        val context = RuntimeEnvironment.getApplication() as Context
        val db = Room.databaseBuilder(context, UserDataDatabase::class.java, dbName)
            .addMigrations(*UserDataDatabase.migrations("tablet-1"))
            .allowMainThreadQueries()
            .build()
        try {
            val data = RoomUserData(db, MutableClock(T0 + DAY_MS), ZoneOffset.UTC, deviceId = "tablet-1")
            val ana = data.profiles.profiles.first().single()
            assertEquals(listOf(1L, 2L, 3L), data.workouts.workouts(ana.id).first().map { it.id }.sorted())
            for (id in 1L..3L) assertEquals(emptyList<WorkoutEvent>(), data.workouts.detail(id)!!.events)
            assertEquals(30, data.workouts.detail(1)!!.samples.size)
            assertEquals(listOf("workout-2"), data.sync.pendingChanges().workouts.map { it.uuid }) // the migration made nothing pending

            data.workouts.setEvents(1, EVENTS).ok()
            assertEquals(EVENTS, data.workouts.detail(1)!!.events)
            assertEquals(listOf("workout-1", "workout-2"), data.sync.pendingChanges().workouts.map { it.uuid }.sorted())
            assertTrue(data.sync.pendingChanges().workouts.first { it.uuid == "workout-1" }.events == EVENTS)
            assertNull(data.workouts.detail(99))
        } finally {
            db.close()
        }
    }

    private fun <T> DataResult<T>.ok(): T = (this as DataResult.Success).value
}
