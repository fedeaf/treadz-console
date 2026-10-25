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
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.time.ZoneOffset

/**
 * Migration 8 to 9 (the avatar of a user) with the real Room tooling: a populated version-8 database is migrated and
 * validated against the exported version-9 schema, keeps every row as it was (the only change is the new empty column,
 * and nothing becomes pending for upload), and is served by the current data layer, where the avatar works.
 */
@RunWith(RobolectricTestRunner::class)
class Migration8To9Test {

    @get:Rule val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), UserDataDatabase::class.java)

    private val dbName = "migration-8-9.db"
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

    private fun populateVersion8(db: SupportSQLiteDatabase) {
        db.execSQL(
            "INSERT INTO users (id, name, nameKey, sex, heightCm, birthEpochDay, restingHeartRateBpm, maxHeartRateBpm, fitnessLevel, goal, targetWeightKg, " +
                "createdAtMillis, isGeneric, uuid, updatedAtMillis, updatedBy, deleted, dirty, maxHeartRateSetAtMillis, strapAddress, strapName) VALUES " +
                "(1, 'Ana', 'ana', 'Female', 168, 6764, NULL, 185, 'Intermediate', 'LoseWeight', 62.5, $T0, 0, '$anaUuid', ${T0 + 10}, 'tablet-1', 0, 0, ${T0 + 5}, " +
                "'AA:BB:CC:DD:EE:FF', 'H9Z')",
        )
        db.execSQL(
            "INSERT INTO weight_entries (id, userId, epochDay, weightKg, isPlaceholder, uuid, updatedAtMillis, updatedBy, deleted, dirty, createdAtMillis) VALUES " +
                "(1, 1, 20000, 71.5, 0, 'w-1', ${T0 - 100}, 'tablet-1', 0, 0, ${T0 - 100})",
        )
    }

    @Test fun `the populated version 8 database migrates to version 9 keeping every row and adding the empty avatar column`() {
        val usersBefore: List<List<Any?>>
        val weightsBefore: List<List<Any?>>
        helper.createDatabase(dbName, 8).use { db ->
            populateVersion8(db)
            usersBefore = rows(db, "SELECT * FROM users ORDER BY id")
            weightsBefore = rows(db, "SELECT * FROM weight_entries ORDER BY id")
        }
        // Also validates the resulting schema against the exported 9.json.
        helper.runMigrationsAndValidate(dbName, 9, true, *UserDataDatabase.migrations("tablet-1")).use { db ->
            assertEquals("each user row is the old one plus the new column, empty", usersBefore.map { it + listOf(null) }, rows(db, "SELECT * FROM users ORDER BY id"))
            assertEquals(weightsBefore, rows(db, "SELECT * FROM weight_entries ORDER BY id"))
            val columns = db.query("SELECT * FROM users").use { c -> c.columnNames.toList() }
            assertEquals("avatar", columns.last())
            assertEquals(listOf(listOf(0L)), rows(db, "SELECT dirty FROM users"))
        }
    }

    @Test fun `after the migration the data layer reads the profile without an avatar and stores a new one`() = runBlocking<Unit> {
        helper.createDatabase(dbName, 8).use { populateVersion8(it) }
        helper.runMigrationsAndValidate(dbName, 9, true, *UserDataDatabase.migrations("tablet-1")).close()

        val context = RuntimeEnvironment.getApplication() as Context
        val db = Room.databaseBuilder(context, UserDataDatabase::class.java, dbName)
            .addMigrations(*UserDataDatabase.migrations("tablet-1"))
            .allowMainThreadQueries()
            .build()
        try {
            val data = RoomUserData(db, MutableClock(T0 + DAY_MS), ZoneOffset.UTC, deviceId = "tablet-1")
            val ana = data.profiles.profiles.first().single()
            assertNull(ana.avatarId)
            assertEquals("AA:BB:CC:DD:EE:FF", ana.strapAddress) // the rest of the profile is as it was

            val input = ProfileInput(ana.name, ana.sex, ana.heightCm, ana.birthDate, maxHeartRateBpm = ana.maxHeartRateBpm, fitnessLevel = ana.fitnessLevel, goal = ana.goal)
            val saved = (data.profiles.update(ana.id, input.copy(avatarId = "woman")) as DataResult.Success).value
            assertEquals("woman", saved.avatarId)
            assertEquals("woman", data.profiles.profile(ana.id).first()!!.avatarId)
            assertEquals("the strap is not part of the form and is kept", "AA:BB:CC:DD:EE:FF", saved.strapAddress)
        } finally {
            db.close()
        }
    }
}
