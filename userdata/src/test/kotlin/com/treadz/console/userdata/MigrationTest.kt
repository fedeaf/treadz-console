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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * Migration 1 to 2 with the real Room tooling: the database is created from the exported version-1 schema, filled with
 * a realistic amount of data, migrated, and validated against the exported version-2 schema.
 */
@RunWith(RobolectricTestRunner::class)
class MigrationTest {

    @get:Rule val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), UserDataDatabase::class.java)

    private val dbName = "migration-test.db"

    private val uuidV4 = Regex("[0-9a-f]{8}-[0-9a-f]{4}-4[0-9a-f]{3}-[89ab][0-9a-f]{3}-[0-9a-f]{12}")

    /** A tablet that has been used for a while: three profiles (one still generic), weights, workouts of all kinds. */
    private fun populateVersion1(db: SupportSQLiteDatabase) {
        db.execSQL(
            "INSERT INTO users (id, name, nameKey, sex, heightCm, birthEpochDay, restingHeartRateBpm, maxHeartRateBpm, " +
                "fitnessLevel, goal, targetWeightKg, createdAtMillis, isGeneric) VALUES " +
                "(1, 'Ana', 'ana', 'Female', 168, 6764, 55, NULL, 'Intermediate', 'LoseWeight', 62.5, $T0, 0), " +
                "(2, 'Beto', 'beto', 'Male', 181, 5000, NULL, 184, NULL, NULL, NULL, ${T0 + 5000}, 0), " +
                "(3, '', '', 'Male', 180, 5900, NULL, NULL, NULL, NULL, NULL, ${T0 + 9000}, 1)",
        )
        db.execSQL("INSERT INTO active_user (id, userId) VALUES (1, 2)")
        for (i in 0 until 30) {
            db.execSQL("INSERT INTO weight_entries (userId, epochDay, weightKg, isPlaceholder) VALUES (1, ${20000 + i * 7}, ${68.0 - i * 0.2}, 0)")
        }
        db.execSQL("INSERT INTO weight_entries (userId, epochDay, weightKg, isPlaceholder) VALUES (2, 20100, 92.0, 0), (3, 20200, 80.0, 1)")
        // finished workouts of Ana and Beto, one interrupted, one done with nobody active, one still "in progress"
        val workouts = listOf(
            Triple(1, 1, "'Stopped'"), Triple(2, 1, "'ConnectionLost'"), Triple(3, 2, "'SafetyKeyRemoved'"),
            Triple(4, 2, "'Interrupted'"), Triple(5, 0, "'Stopped'"), Triple(6, 1, "NULL"),
        )
        for ((id, user, reason) in workouts) {
            val start = T0 + id * 86_400_000L
            val ended = if (reason == "NULL") "NULL" else "${start + 120_000}"
            db.execSQL(
                "INSERT INTO workouts (id, userId, startedAtMillis, endedAtMillis, endReason, durationSeconds, distanceMeters, " +
                    "calories, averageSpeedKmh, maxSpeedKmh, averageHeartRateBpm, maxHeartRateBpm, maxInclinePercent) " +
                    "VALUES ($id, ${if (user == 0) "NULL" else user.toString()}, $start, $ended, $reason, 120, 266, 12, 8.0, 8.5, 140, 155, 1.5)",
            )
            for (seq in 0 until 120) {
                db.execSQL(
                    "INSERT INTO workout_samples (workoutId, seq, timestampMillis, elapsedSeconds, speedKmh, targetSpeedKmh, " +
                        "inclinePercent, distanceMeters, calories, heartRateBpm, beltStatus, isStale) VALUES " +
                        "($id, $seq, ${start + seq * 1000L}, ${seq + 1}, 8.0, 8.0, 1.5, ${seq * 2}, ${seq / 10}, 140, 'Running', 0)",
                )
            }
        }
    }

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

    private val userColumns = "id, name, nameKey, sex, heightCm, birthEpochDay, restingHeartRateBpm, maxHeartRateBpm, fitnessLevel, goal, targetWeightKg, createdAtMillis, isGeneric"
    private val weightColumns = "id, userId, epochDay, weightKg, isPlaceholder"
    private val workoutColumns = "id, userId, startedAtMillis, endedAtMillis, endReason, durationSeconds, distanceMeters, calories, averageSpeedKmh, maxSpeedKmh, averageHeartRateBpm, maxHeartRateBpm, maxInclinePercent"
    private val sampleColumns = "workoutId, seq, timestampMillis, elapsedSeconds, speedKmh, targetSpeedKmh, inclinePercent, distanceMeters, calories, heartRateBpm, beltStatus, isStale"

    @Test fun `the populated version 1 database migrates to version 2 without losing or changing anything`() {
        val before: Map<String, List<List<Any?>>>
        helper.createDatabase(dbName, 1).use { db ->
            populateVersion1(db)
            before = mapOf(
                "users" to rows(db, "SELECT $userColumns FROM users ORDER BY id"),
                "weights" to rows(db, "SELECT $weightColumns FROM weight_entries ORDER BY id"),
                "workouts" to rows(db, "SELECT $workoutColumns FROM workouts ORDER BY id"),
                "samples" to rows(db, "SELECT $sampleColumns FROM workout_samples ORDER BY workoutId, seq"),
                "active" to rows(db, "SELECT id, userId FROM active_user"),
            )
        }
        assertEquals(3, before.getValue("users").size)
        assertEquals(32, before.getValue("weights").size)
        assertEquals(6, before.getValue("workouts").size)
        assertEquals(720, before.getValue("samples").size)

        // Also validates the resulting schema against the exported 2.json.
        helper.runMigrationsAndValidate(dbName, 2, true, *UserDataDatabase.migrations("tablet-1")).use { db ->
            // every pre-existing column of every row is unchanged
            assertEquals(before["users"], rows(db, "SELECT $userColumns FROM users ORDER BY id"))
            assertEquals(before["weights"], rows(db, "SELECT $weightColumns FROM weight_entries ORDER BY id"))
            assertEquals(before["workouts"], rows(db, "SELECT $workoutColumns FROM workouts ORDER BY id"))
            assertEquals(before["samples"], rows(db, "SELECT $sampleColumns FROM workout_samples ORDER BY workoutId, seq"))
            assertEquals(before["active"], rows(db, "SELECT id, userId FROM active_user"))

            // identities: a valid, unique UUID everywhere
            for (table in listOf("users", "weight_entries", "workouts")) {
                val uuids = rows(db, "SELECT uuid FROM $table").map { it.single() as String }
                assertTrue("$table: not a v4 UUID in $uuids", uuids.all { uuidV4.matches(it) })
                assertEquals("$table: duplicated uuid", uuids.size, uuids.toSet().size)
                // nothing deleted, everything pending (to be uploaded when the device is linked), stamped by the device
                assertEquals(0L, rows(db, "SELECT COUNT(*) FROM $table WHERE deleted <> 0").single().single())
                assertEquals(rows(db, "SELECT COUNT(*) FROM $table").single(), rows(db, "SELECT COUNT(*) FROM $table WHERE dirty = 1").single())
                assertEquals(rows(db, "SELECT COUNT(*) FROM $table").single(), rows(db, "SELECT COUNT(*) FROM $table WHERE updatedBy = 'tablet-1'").single())
            }
            // versions come from the dates we have
            assertEquals(listOf(T0, T0 + 5000, T0 + 9000), rows(db, "SELECT updatedAtMillis FROM users ORDER BY id").map { it.single() })
            assertEquals(20000L * 86_400_000L, rows(db, "SELECT updatedAtMillis FROM weight_entries WHERE id = 1").single().single())
            assertEquals(T0 + 86_400_000L + 120_000, rows(db, "SELECT updatedAtMillis FROM workouts WHERE id = 1").single().single())
            assertEquals(T0 + 6 * 86_400_000L, rows(db, "SELECT updatedAtMillis FROM workouts WHERE id = 6").single().single()) // in progress: its start

            // the name index no longer is unique, the uuid index is
            db.execSQL("INSERT INTO users (name, nameKey, sex, heightCm, birthEpochDay, createdAtMillis, isGeneric, uuid) VALUES ('ANA', 'ana', 'Male', 170, 7000, 1, 0, 'x1')")
            val duplicate = runCatching {
                db.execSQL("INSERT INTO users (name, nameKey, sex, heightCm, birthEpochDay, createdAtMillis, isGeneric, uuid) VALUES ('Z', 'z', 'Male', 170, 7000, 1, 0, 'x1')")
            }
            assertTrue("uuid must be unique", duplicate.isFailure)
        }
    }

    @Test fun `after the migration the data layer serves the same data and the migrated rows are pending`() = runBlocking<Unit> {
        helper.createDatabase(dbName, 1).use { populateVersion1(it) }
        helper.runMigrationsAndValidate(dbName, 2, true, *UserDataDatabase.migrations("tablet-1")).close()

        val context = RuntimeEnvironment.getApplication() as Context
        val db = Room.databaseBuilder(context, UserDataDatabase::class.java, dbName)
            .addMigrations(*UserDataDatabase.migrations("tablet-1"))
            .allowMainThreadQueries()
            .build()
        try {
            val data = RoomUserData(db, MutableClock(T0 + 30L * 86_400_000L), ZoneOffset.UTC, deviceId = "tablet-1")
            val profiles = data.profiles.profiles.first()
            assertEquals(listOf("", "Ana", "Beto"), profiles.map { it.name })
            val ana = profiles.first { it.name == "Ana" }
            val beto = profiles.first { it.name == "Beto" }
            assertEquals(beto, data.profiles.activeUser.first())
            assertEquals(Sex.Female, ana.sex)
            assertEquals(LocalDate.ofEpochDay(6764), ana.birthDate)
            assertEquals(30, data.weights.entries(ana.id).first().size)
            assertEquals(62.5, data.weights.targetWeightKg(ana.id).first()!!, 0.0)
            assertEquals(3, data.workouts.workouts(ana.id).first().size) // two finished and the one left in progress
            assertEquals(1, data.workouts.unassignedWorkouts().first().size)
            assertEquals(120, data.workouts.detail(1)!!.samples.size)

            // What the sync port sees: the generic profile and its placeholder weight, the unassigned workout and the one
            // in progress are not eligible; everything else is pending.
            val pending = data.sync.pendingChanges()
            assertEquals(listOf("Ana", "Beto"), pending.profiles.map { it.name }.sorted())
            assertEquals(31, pending.weights.size)
            assertEquals(listOf(1L, 2L, 3L, 4L), data.sync.pendingWorkoutIds.first().sorted())
            assertEquals(4, pending.workouts.size)
            assertEquals(2 + 31 + 4, data.sync.pendingCount.first())
            assertTrue(pending.profiles.all { it.version.deviceId == "tablet-1" && it.uuid.isNotEmpty() })
            assertNotNull(pending.workouts.firstOrNull { it.endReason == WorkoutEndReason.Interrupted })

            // and normal use keeps working on the migrated database
            data.weights.add(beto.id, LocalDate.of(2026, 10, 1), 91.0)
            assertEquals(91.0, data.weights.latestWeight(beto.id).first()!!.weightKg, 0.0)
            assertEquals(DataResult.Success(Unit), data.profiles.delete(ana.id))
            assertFalse(data.profiles.profiles.first().any { it.id == ana.id })
            assertEquals(2, data.profiles.profiles.first().size)
            assertTrue(data.workouts.detail(1) == null)
        } finally {
            db.close()
        }
    }
}
