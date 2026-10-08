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
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * Migrations 1 to 2 and 2 to 3 with the real Room tooling: the database is created from the exported version-1 schema, filled with
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

    // ---- 2 to 3 ----

    /** A version-2 database: two synchronised profiles, one with two weights on the same day recorded out of id order. */
    private fun populateVersion2(db: SupportSQLiteDatabase) {
        for ((id, uuid) in listOf(1 to "user-ana", 2 to "user-beto")) {
            db.execSQL(
                "INSERT INTO users (id, name, nameKey, sex, heightCm, birthEpochDay, createdAtMillis, isGeneric, uuid, updatedAtMillis, " +
                    "updatedBy, deleted, dirty) VALUES ($id, 'U$id', 'u$id', 'Female', 168, 6764, $T0, 0, '$uuid', $T0, 'tablet-1', 0, 0)",
            )
        }
        fun weight(id: Int, user: Int, day: Long, kg: Double, uuid: String, updatedAt: Long, deleted: Int, dirty: Int) = db.execSQL(
            "INSERT INTO weight_entries (id, userId, epochDay, weightKg, isPlaceholder, uuid, updatedAtMillis, updatedBy, deleted, dirty) " +
                "VALUES ($id, $user, $day, $kg, 0, '$uuid', $updatedAt, 'tablet-1', $deleted, $dirty)",
        )
        weight(1, 1, 20000, 90.0, "w-old", 20000L * 86_400_000L, 0, 1) // from version 1: midnight of its date
        weight(2, 1, 20300, 88.2, "w-b", T0 + 2_000, 0, 0) // same day, local id 2, created second
        weight(3, 1, 20300, 88.0, "w-a", T0 + 1_000, 0, 0) // same day, local id 3, but created first
        weight(4, 1, 20300, 0.0, "w-gone", T0 + 9_000, 1, 1) // a tombstone, still pending
        weight(5, 2, 20301, 70.0, "w-beto", T0 + 3_000, 0, 1)
    }

    @Test fun `the populated version 2 database migrates to version 3 keeping every row and giving entries a creation time`() {
        val weightColumnsV2 = "id, userId, epochDay, weightKg, isPlaceholder, uuid, updatedAtMillis, updatedBy, deleted, dirty"
        val before: Map<String, List<List<Any?>>>
        helper.createDatabase(dbName, 2).use { db ->
            populateVersion2(db)
            before = mapOf(
                "users" to rows(db, "SELECT * FROM users ORDER BY id"),
                "weights" to rows(db, "SELECT $weightColumnsV2 FROM weight_entries ORDER BY id"),
            )
        }
        // Also validates the resulting schema against the exported 3.json.
        helper.runMigrationsAndValidate(dbName, 3, true, *UserDataDatabase.migrations("tablet-1")).use { db ->
            assertEquals(before["users"], rows(db, "SELECT * FROM users ORDER BY id"))
            // nothing lost or changed (versions and pending flags included) ...
            assertEquals(before["weights"], rows(db, "SELECT $weightColumnsV2 FROM weight_entries ORDER BY id"))
            // ... and the creation time of every entry is its last change time
            assertEquals(
                rows(db, "SELECT updatedAtMillis FROM weight_entries ORDER BY id"),
                rows(db, "SELECT createdAtMillis FROM weight_entries ORDER BY id"),
            )
        }
    }

    @Test fun `after the 2 to 3 migration both entries of a day are ordered by their synchronised creation time`() = runBlocking<Unit> {
        helper.createDatabase(dbName, 2).use { populateVersion2(it) }
        helper.runMigrationsAndValidate(dbName, 3, true, *UserDataDatabase.migrations("tablet-1")).close()
        val context = RuntimeEnvironment.getApplication() as Context
        val db = Room.databaseBuilder(context, UserDataDatabase::class.java, dbName)
            .addMigrations(*UserDataDatabase.migrations("tablet-1"))
            .allowMainThreadQueries()
            .build()
        try {
            val data = RoomUserData(db, MutableClock(T0 + 30L * 86_400_000L), ZoneOffset.UTC, deviceId = "tablet-1")
            val ana = data.profiles.profiles.first().first { it.name == "U1" }
            // The local id says 88.0 is the latest (id 3); the synchronised creation time says 88.2 (created last).
            assertEquals(listOf(90.0, 88.0, 88.2), data.weights.entries(ana.id).first().map { it.weightKg })
            assertEquals(88.2, data.weights.latestWeight(ana.id).first()!!.weightKg, 0.0)
            // the pending tombstone and entries are still pending, and the exposed value is the stored one
            assertEquals(listOf("w-beto", "w-gone", "w-old"), data.sync.pendingChanges().weights.map { it.uuid }.sorted())
            assertEquals(T0 + 2_000, data.sync.weightEntries("user-ana").first { it.uuid == "w-b" }.createdAtMillis)

            // a new entry gets the current time as creation time and wins the day
            data.weights.add(ana.id, LocalDate.ofEpochDay(20300), 87.9)
            assertEquals(87.9, data.weights.latestWeight(ana.id).first()!!.weightKg, 0.0)
        } finally {
            db.close()
        }
    }

    // ---- 3 to 4 ----

    /**
     * A version-3 database as a tablet that has been synchronising would have it: two real profiles (one with a manual
     * maximum heart rate), the generic one, weights, workouts (finished, interrupted, in progress) with samples, a
     * tombstone, and a mix of pending and synchronised rows.
     */
    private fun populateVersion3(db: SupportSQLiteDatabase) {
        db.execSQL(
            "INSERT INTO users (id, name, nameKey, sex, heightCm, birthEpochDay, restingHeartRateBpm, maxHeartRateBpm, fitnessLevel, goal, " +
                "targetWeightKg, createdAtMillis, isGeneric, uuid, updatedAtMillis, updatedBy, deleted, dirty) VALUES " +
                "(1, 'Ana', 'ana', 'Female', 168, 6764, 55, 172, 'Intermediate', 'LoseWeight', 62.5, $T0, 0, 'user-ana', ${T0 + 10}, 'tablet-1', 0, 0), " +
                "(2, 'Beto', 'beto', 'Male', 181, 5000, NULL, NULL, NULL, NULL, NULL, ${T0 + 5000}, 0, 'user-beto', ${T0 + 20}, 'phone-2', 0, 1), " +
                "(3, '', '', 'Male', 180, 5900, NULL, NULL, NULL, NULL, NULL, ${T0 + 9000}, 1, 'user-generic', ${T0 + 30}, 'tablet-1', 0, 1), " +
                "(4, 'Gone', 'gone', 'Male', 175, 5100, NULL, NULL, NULL, NULL, NULL, ${T0 + 100}, 0, 'user-gone', ${T0 + 40}, 'tablet-1', 1, 1)",
        )
        db.execSQL("INSERT INTO active_user (id, userId) VALUES (1, 1)")
        for (i in 0 until 12) {
            db.execSQL(
                "INSERT INTO weight_entries (userId, epochDay, weightKg, isPlaceholder, uuid, updatedAtMillis, updatedBy, deleted, dirty, createdAtMillis) " +
                    "VALUES (1, ${20000 + i * 7}, ${68.0 - i * 0.2}, 0, 'w-$i', ${T0 + i}, 'tablet-1', 0, ${i % 2}, ${T0 + i})",
            )
        }
        db.execSQL(
            "INSERT INTO weight_entries (userId, epochDay, weightKg, isPlaceholder, uuid, updatedAtMillis, updatedBy, deleted, dirty, createdAtMillis) " +
                "VALUES (3, 20200, 80.0, 1, 'w-placeholder', $T0, 'tablet-1', 0, 1, $T0)",
        )
        val workouts = listOf(
            Triple(1, "'user-ana'", "'Stopped'"), Triple(2, "'user-ana'", "'ConnectionLost'"),
            Triple(3, "'user-beto'", "'SafetyKeyRemoved'"), Triple(4, "NULL", "'Stopped'"), Triple(5, "'user-ana'", "NULL"),
        )
        for ((id, owner, reason) in workouts) {
            val start = T0 + id * 86_400_000L
            val ended = if (reason == "NULL") "NULL" else "${start + 60_000}"
            val userId = when (owner) { "'user-ana'" -> "1"; "'user-beto'" -> "2"; else -> "NULL" }
            db.execSQL(
                "INSERT INTO workouts (id, userId, startedAtMillis, endedAtMillis, endReason, durationSeconds, distanceMeters, calories, " +
                    "averageSpeedKmh, maxSpeedKmh, averageHeartRateBpm, maxHeartRateBpm, maxInclinePercent, uuid, updatedAtMillis, updatedBy, deleted, dirty) " +
                    "VALUES ($id, $userId, $start, $ended, $reason, 60, 133, 6, 8.0, 8.5, 140, 155, 1.5, 'workout-$id', ${start + 60_000}, 'tablet-1', 0, ${id % 2})",
            )
            for (seq in 0 until 60) {
                db.execSQL(
                    "INSERT INTO workout_samples (workoutId, seq, timestampMillis, elapsedSeconds, speedKmh, targetSpeedKmh, inclinePercent, " +
                        "distanceMeters, calories, heartRateBpm, beltStatus, isStale) VALUES " +
                        "($id, $seq, ${start + seq * 1000L}, ${seq + 1}, 8.0, 8.0, 1.5, ${seq * 2}, ${seq / 10}, 140, 'Running', 0)",
                )
            }
        }
        db.execSQL(
            "INSERT INTO workouts (id, userId, startedAtMillis, endedAtMillis, endReason, durationSeconds, distanceMeters, calories, " +
                "averageSpeedKmh, maxSpeedKmh, averageHeartRateBpm, maxHeartRateBpm, maxInclinePercent, uuid, updatedAtMillis, updatedBy, deleted, dirty) " +
                "VALUES (6, 1, $T0, $T0, 'Stopped', 0, 0, 0, 0.0, 0.0, NULL, NULL, 0.0, 'workout-gone', ${T0 + 5}, 'tablet-1', 1, 1)",
        )
    }

    private val userColumnsV3 = "id, name, nameKey, sex, heightCm, birthEpochDay, restingHeartRateBpm, maxHeartRateBpm, fitnessLevel, goal, targetWeightKg, " +
        "createdAtMillis, isGeneric, uuid, updatedAtMillis, updatedBy, deleted, dirty"
    private val weightColumnsV3 = "id, userId, epochDay, weightKg, isPlaceholder, uuid, updatedAtMillis, updatedBy, deleted, dirty, createdAtMillis"
    private val workoutColumnsV3 = "$workoutColumns, uuid, updatedAtMillis, updatedBy, deleted, dirty"

    @Test fun `the populated version 3 database migrates to version 4 without changing a single row`() {
        val before: Map<String, List<List<Any?>>>
        helper.createDatabase(dbName, 3).use { db ->
            populateVersion3(db)
            before = mapOf(
                "users" to rows(db, "SELECT $userColumnsV3 FROM users ORDER BY id"),
                "weights" to rows(db, "SELECT $weightColumnsV3 FROM weight_entries ORDER BY id"),
                "workouts" to rows(db, "SELECT $workoutColumnsV3 FROM workouts ORDER BY id"),
                "samples" to rows(db, "SELECT $sampleColumns FROM workout_samples ORDER BY workoutId, seq"),
                "active" to rows(db, "SELECT id, userId FROM active_user"),
            )
        }
        assertEquals(4, before.getValue("users").size)
        assertEquals(13, before.getValue("weights").size)
        assertEquals(6, before.getValue("workouts").size)
        assertEquals(300, before.getValue("samples").size)

        // Also validates the resulting schema against the exported 4.json.
        helper.runMigrationsAndValidate(dbName, 4, true, *UserDataDatabase.migrations("tablet-1")).use { db ->
            assertEquals(before["users"], rows(db, "SELECT $userColumnsV3 FROM users ORDER BY id"))
            assertEquals(before["weights"], rows(db, "SELECT $weightColumnsV3 FROM weight_entries ORDER BY id"))
            assertEquals(before["workouts"], rows(db, "SELECT $workoutColumnsV3 FROM workouts ORDER BY id"))
            assertEquals(before["samples"], rows(db, "SELECT $sampleColumns FROM workout_samples ORDER BY workoutId, seq"))
            assertEquals(before["active"], rows(db, "SELECT id, userId FROM active_user"))

            // the new columns are empty: no manual maximum heart rate has a time, no workout has a program
            assertEquals(4L, rows(db, "SELECT COUNT(*) FROM users WHERE maxHeartRateSetAtMillis IS NULL").single().single())
            assertEquals(
                6L,
                rows(db, "SELECT COUNT(*) FROM workouts WHERE planJson IS NULL AND programId IS NULL AND programName IS NULL AND fitnessTestId IS NULL")
                    .single().single(),
            )
            // the new tables exist and are empty
            for (table in listOf("programs", "fitness_tests", "start_parameters")) {
                assertEquals(table, 0L, rows(db, "SELECT COUNT(*) FROM $table").single().single())
            }
            // their unique uuid indexes work, and the foreign keys point at users
            db.execSQL("PRAGMA foreign_keys = ON")
            db.execSQL(
                "INSERT INTO programs (userId, uuid, name, nameKey, kind, json, createdAtMillis, updatedAtMillis, updatedBy, deleted, dirty) " +
                    "VALUES (1, 'p1', 'A', 'a', 'Free', '{}', 1, 1, 'x', 0, 1)",
            )
            val duplicate = runCatching {
                db.execSQL(
                    "INSERT INTO programs (userId, uuid, name, nameKey, kind, json, createdAtMillis, updatedAtMillis, updatedBy, deleted, dirty) " +
                        "VALUES (1, 'p1', 'B', 'b', 'Free', '{}', 1, 1, 'x', 0, 1)",
                )
            }
            assertTrue("program uuid must be unique", duplicate.isFailure)
            val orphan = runCatching {
                db.execSQL(
                    "INSERT INTO fitness_tests (userId, uuid, testType, startedAtMillis, isValid, json, updatedAtMillis, updatedBy, deleted, dirty) " +
                        "VALUES (99, 't1', 'EbbelingWalk', 1, 1, '{}', 1, 'x', 0, 1)",
                )
            }
            assertTrue("a test needs its user", orphan.isFailure)
        }
    }

    @Test fun `after the 3 to 4 migration the data layer serves the same data and the new features work`() = runBlocking<Unit> {
        helper.createDatabase(dbName, 3).use { populateVersion3(it) }
        helper.runMigrationsAndValidate(dbName, 4, true, *UserDataDatabase.migrations("tablet-1")).close()

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
            assertEquals(ana, data.profiles.activeUser.first())
            assertEquals(172, ana.maxHeartRateBpm)
            assertNull(ana.maxHeartRateSetAtMillis)
            assertEquals(12, data.weights.entries(ana.id).first().size)
            val workouts = data.workouts.workouts(ana.id).first()
            assertEquals(3, workouts.size) // two finished and the one in progress; the tombstone is not listed
            assertTrue(workouts.all { it.programId == null && it.programName == null && it.fitnessTestId == null })
            assertEquals(60, data.workouts.detail(1)!!.samples.size)
            assertNull(data.workouts.detail(1)!!.plan)

            // what the sync sees is unchanged: the migration made nothing pending, and the new tables hold nothing
            val pending = data.sync.pendingChanges()
            assertEquals(listOf("Beto", "Gone"), pending.profiles.map { it.name }.sorted()) // Gone is a pending tombstone
            assertEquals(listOf("w-1", "w-11", "w-3", "w-5", "w-7", "w-9"), pending.weights.map { it.uuid }.sorted())
            assertEquals(listOf("workout-1", "workout-3", "workout-gone"), pending.workouts.map { it.uuid }.sorted())
            assertTrue(pending.workouts.all { it.planJson == null && it.fitnessTestId == null })
            assertEquals(emptyList<Any>(), data.programDocuments.pending())
            assertEquals(emptyList<Any>(), data.fitnessTestDocuments.pending())
            assertNull(data.sync.profile("user-ana")!!.fitness)

            // the new features work on the migrated database
            assertTrue(data.programs.save(ana.id, norwegian()) is ProgramSaveResult.Saved)
            data.workouts.link(1, WorkoutLink(plan = hillPlan(), fitnessTestId = TEST_1))
            assertEquals("standard-hill", data.workouts.workouts(ana.id).first().first { it.id == 1L }.programId)
            val newerTest = ebbelingResult(TEST_2, T0 + 29L * 86_400_000L)
            data.fitnessTests.save(ana.id, maxTestResult(TEST_1, T0 + 29L * 86_400_000L, peak = 188)) // after the old manual value
            assertNotNull(data.fitnessTests.save(ana.id, newerTest))
            // the manual value has no time, so a newer, different measurement makes the app ask
            val decision = data.fitnessTests.heartRateMax(ana.id).first()!!
            assertTrue(decision.askUser)
            assertEquals(172, decision.bpm)
            data.fitnessTests.keepManualHeartRateMax(ana.id)
            assertFalse(data.fitnessTests.heartRateMax(ana.id).first()!!.askUser)
            assertEquals(DataResult.Success(Unit), data.profiles.delete(ana.id))
            assertTrue(data.programs.programs(ana.id).first().isEmpty())
        } finally {
            db.close()
        }
    }

    @Test fun `a version 1 database goes through every migration to version 4`() {
        helper.createDatabase(dbName, 1).use { populateVersion1(it) }
        helper.runMigrationsAndValidate(dbName, 4, true, *UserDataDatabase.migrations("tablet-1")).use { db ->
            assertEquals(3L, rows(db, "SELECT COUNT(*) FROM users").single().single())
            assertEquals(720L, rows(db, "SELECT COUNT(*) FROM workout_samples").single().single())
            assertEquals(0L, rows(db, "SELECT COUNT(*) FROM programs").single().single())
        }
    }
}
