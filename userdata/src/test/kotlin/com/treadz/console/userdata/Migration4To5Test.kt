package com.treadz.console.userdata

import android.content.Context
import androidx.room.Room
import androidx.room.testing.MigrationTestHelper
import androidx.sqlite.db.SupportSQLiteDatabase
import androidx.test.platform.app.InstrumentationRegistry
import com.treadz.console.programs.json.PlanJson
import com.treadz.console.userdata.room.RoomUserData
import com.treadz.console.userdata.room.UserDataDatabase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.time.ZoneOffset

/**
 * Migration 4 to 5 (heart rate data) with the real Room tooling: a populated version-4 database is migrated, validated
 * against the exported version-5 schema, and served by the current data layer.
 */
@RunWith(RobolectricTestRunner::class)
class Migration4To5Test {

    @get:Rule val helper = MigrationTestHelper(InstrumentationRegistry.getInstrumentation(), UserDataDatabase::class.java)

    private val dbName = "migration-4-5.db"

    private val anaUuid = "5a0e0d2c-5b0f-4a56-9d6a-6f1f4b6d2c10"
    private val betoUuid = "5a0e0d2c-5b0f-4a56-9d6a-6f1f4b6d2c20"
    private val goneUuid = "5a0e0d2c-5b0f-4a56-9d6a-6f1f4b6d2c30"

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

    private val userColumns = "id, name, nameKey, sex, heightCm, birthEpochDay, restingHeartRateBpm, maxHeartRateBpm, fitnessLevel, goal, targetWeightKg, " +
        "createdAtMillis, isGeneric, uuid, updatedAtMillis, updatedBy, deleted, dirty, maxHeartRateSetAtMillis"
    private val workoutColumns = "id, userId, startedAtMillis, endedAtMillis, endReason, durationSeconds, distanceMeters, calories, averageSpeedKmh, " +
        "maxSpeedKmh, averageHeartRateBpm, maxHeartRateBpm, maxInclinePercent, uuid, updatedAtMillis, updatedBy, deleted, dirty, planJson, programId, " +
        "programName, fitnessTestId"
    private val sampleColumns = "workoutId, seq, timestampMillis, elapsedSeconds, speedKmh, targetSpeedKmh, inclinePercent, distanceMeters, calories, " +
        "heartRateBpm, beltStatus, isStale"

    /**
     * A tablet in use: Ana (resting heart rate 55, manual maximum), Beto (none), a deleted profile that had one, the generic
     * profile, workouts with a plain plan, with a personalised plan and without one, with samples.
     */
    private fun populateVersion4(db: SupportSQLiteDatabase) {
        db.execSQL(
            "INSERT INTO users (id, name, nameKey, sex, heightCm, birthEpochDay, restingHeartRateBpm, maxHeartRateBpm, fitnessLevel, goal, " +
                "targetWeightKg, createdAtMillis, isGeneric, uuid, updatedAtMillis, updatedBy, deleted, dirty, maxHeartRateSetAtMillis) VALUES " +
                "(1, 'Ana', 'ana', 'Female', 168, 6764, 55, 172, 'Intermediate', 'LoseWeight', 62.5, $T0, 0, '$anaUuid', ${T0 + 10}, 'tablet-1', 0, 0, ${T0 + 5}), " +
                "(2, 'Beto', 'beto', 'Male', 181, 5000, NULL, NULL, NULL, NULL, NULL, ${T0 + 5000}, 0, '$betoUuid', ${T0 + 20}, 'phone-2', 0, 1, NULL), " +
                "(3, '', '', 'Male', 180, 5900, NULL, NULL, NULL, NULL, NULL, ${T0 + 9000}, 1, 'user-generic', ${T0 + 30}, 'tablet-1', 0, 1, NULL), " +
                "(4, 'Gone', 'gone', 'Male', 175, 5100, 61, NULL, NULL, NULL, NULL, ${T0 + 100}, 0, '$goneUuid', ${T0 + 40}, 'tablet-1', 1, 1, NULL)",
        )
        db.execSQL("INSERT INTO active_user (id, userId) VALUES (1, 1)")
        val personalisedPlan = PlanJson.encode(planOf(personalised(generatorId = GENERATOR_C25K)))
        val plainPlan = PlanJson.encode(hillPlan())
        fun workout(id: Int, user: Int, plan: String?, programId: String?) {
            val start = T0 + id * 86_400_000L
            db.execSQL(
                "INSERT INTO workouts (id, userId, startedAtMillis, endedAtMillis, endReason, durationSeconds, distanceMeters, calories, averageSpeedKmh, " +
                    "maxSpeedKmh, averageHeartRateBpm, maxHeartRateBpm, maxInclinePercent, uuid, updatedAtMillis, updatedBy, deleted, dirty, planJson, programId, programName, fitnessTestId) " +
                    "VALUES (?, ?, ?, ?, 'Stopped', 60, 133, 6, 8.0, 8.5, 140, 155, 1.5, ?, ?, 'tablet-1', 0, ?, ?, ?, ?, NULL)",
                arrayOf<Any?>(id, user, start, start + 60_000, "workout-$id", start + 60_000, id % 2, plan, programId, programId?.let { "Program $it" }),
            )
            for (seq in 0 until 60) {
                db.execSQL(
                    "INSERT INTO workout_samples (workoutId, seq, timestampMillis, elapsedSeconds, speedKmh, targetSpeedKmh, inclinePercent, " +
                        "distanceMeters, calories, heartRateBpm, beltStatus, isStale) VALUES " +
                        "($id, $seq, ${start + seq * 1000L}, ${seq + 1}, 8.0, 8.0, 1.5, ${seq * 2}, ${seq / 10}, 140, 'Running', 0)",
                )
            }
        }
        workout(1, 1, null, null)
        workout(2, 1, plainPlan, "standard-hill")
        workout(3, 2, personalisedPlan, PROGRAM_2)
    }

    @Test fun `the populated version 4 database migrates to version 5 keeping every row and adding the heart rate data`() {
        val before: Map<String, List<List<Any?>>>
        helper.createDatabase(dbName, 4).use { db ->
            populateVersion4(db)
            before = mapOf(
                "users" to rows(db, "SELECT $userColumns FROM users ORDER BY id"),
                "workouts" to rows(db, "SELECT $workoutColumns FROM workouts ORDER BY id"),
                "samples" to rows(db, "SELECT $sampleColumns FROM workout_samples ORDER BY workoutId, seq"),
            )
        }
        assertEquals(4, before.getValue("users").size)
        assertEquals(3, before.getValue("workouts").size)
        assertEquals(180, before.getValue("samples").size)

        // Also validates the resulting schema against the exported 5.json.
        helper.runMigrationsAndValidate(dbName, 5, true, *UserDataDatabase.migrations("tablet-1")).use { db ->
            assertEquals(before["users"], rows(db, "SELECT $userColumns FROM users ORDER BY id"))
            assertEquals(before["workouts"], rows(db, "SELECT $workoutColumns FROM workouts ORDER BY id"))
            assertEquals(before["samples"], rows(db, "SELECT $sampleColumns FROM workout_samples ORDER BY workoutId, seq"))

            // new columns: nothing for the old data, but the generator of the personalised plan
            assertEquals(4L, rows(db, "SELECT COUNT(*) FROM users WHERE strapAddress IS NULL AND strapName IS NULL").single().single())
            assertEquals(
                3L,
                rows(db, "SELECT COUNT(*) FROM workouts WHERE peakHeartRateBpm IS NULL AND peakHeartRateSource IS NULL AND peakHeartRateAtMillis IS NULL AND sessionRecordJson IS NULL").single().single(),
            )
            assertEquals(
                listOf(listOf(1L, null), listOf(2L, null), listOf(3L, GENERATOR_C25K)),
                rows(db, "SELECT id, generatorId FROM workouts ORDER BY id"),
            )
            assertEquals(180L, rows(db, "SELECT COUNT(*) FROM workout_samples WHERE heartRateSource IS NULL AND rrIntervalsMs IS NULL").single().single())

            // Ana's resting heart rate is her first measurement; nobody else gets one, not even the deleted profile
            val measurements = rows(db, "SELECT userId, uuid, measuredAtMillis, bpm, source, lowestMeanBpm, coverage, rmssdMs, updatedAtMillis, updatedBy, deleted, dirty FROM resting_heart_rates")
            assertEquals(
                listOf(listOf(1L, "resting-$anaUuid", T0 + 10, 55L, "Manual", null, null, null, T0 + 10, "tablet-1", 0L, 1L)),
                measurements,
            )
            // the unique uuid index and the foreign key work
            db.execSQL("PRAGMA foreign_keys = ON")
            assertTrue(
                runCatching {
                    db.execSQL("INSERT INTO resting_heart_rates (userId, uuid, measuredAtMillis, bpm, source, updatedAtMillis, updatedBy, deleted, dirty) VALUES (2, 'resting-$anaUuid', 1, 60, 'Manual', 1, 'x', 0, 1)")
                }.isFailure,
            )
            assertTrue(
                runCatching {
                    db.execSQL("INSERT INTO resting_heart_rates (userId, uuid, measuredAtMillis, bpm, source, updatedAtMillis, updatedBy, deleted, dirty) VALUES (99, 'r', 1, 60, 'Manual', 1, 'x', 0, 1)")
                }.isFailure,
            )
        }
    }

    @Test fun `after the migration the data layer serves the data and the heart rate features work`() = runBlocking<Unit> {
        helper.createDatabase(dbName, 4).use { populateVersion4(it) }
        helper.runMigrationsAndValidate(dbName, 5, true, *UserDataDatabase.migrations("tablet-1")).close()

        val context = RuntimeEnvironment.getApplication() as Context
        val db = Room.databaseBuilder(context, UserDataDatabase::class.java, dbName)
            .addMigrations(*UserDataDatabase.migrations("tablet-1"))
            .allowMainThreadQueries()
            .build()
        try {
            val clock = MutableClock(T0 + 3 * DAY_MS)
            val data = RoomUserData(db, clock, ZoneOffset.UTC, deviceId = "tablet-1")
            val ana = data.profiles.profiles.first().first { it.name == "Ana" }
            assertEquals(ana, data.profiles.activeUser.first())
            // the resting heart rate is now the reference of the history: one manual measurement
            assertEquals(55, ana.restingHeartRateBpm)
            val history = data.restingHeartRates.measurements(ana.id).first().single()
            assertEquals(RestingHeartRateSource.Manual, history.source)
            assertEquals(55, history.bpm)
            assertEquals(T0 + 10, history.measuredAtMillis)
            assertEquals(ReferenceStatus.Provisional, data.restingHeartRates.reference(ana.id).first()!!.status)
            assertNull(data.profiles.profiles.first().first { it.name == "Beto" }.restingHeartRateBpm)
            // it is pending, with a uuid that depends on the profile only, and the profile file shows the reference
            val pending = data.sync.pendingChanges()
            assertEquals(listOf("resting-$anaUuid"), pending.restingHeartRates.map { it.uuid })
            assertEquals(anaUuid, pending.restingHeartRates.single().userUuid)
            assertEquals(55, data.sync.profile(anaUuid)!!.restingHeartRateBpm)

            // workouts: old samples read back without source or RR, the personalised one filters by its generator
            val sample = data.workouts.detail(1)!!.samples.first()
            assertNull(sample.heartRateSource)
            assertEquals(emptyList<Int>(), sample.rrIntervalsMs)
            assertEquals(60, data.workouts.detail(1)!!.samples.size)
            assertNull(data.workouts.detail(1)!!.summary.stats.peakHeartRate)
            assertEquals(GENERATOR_C25K, data.workouts.workouts(2).first().single().generatorId)
            assertNull(data.workouts.highestHeartRatePeak(ana.id).first())

            // and the new features work on the migrated database
            data.profiles.setStrap(ana.id, "AA:BB:CC:DD:EE:FF", "Polar H10").let { assertTrue(it is StrapSaveResult.Saved) }
            assertEquals("AA:BB:CC:DD:EE:FF", data.profiles.profile(ana.id).first()!!.strapAddress)
            data.workouts.attachSessionRecord(1, sessionRecord(GENERATOR_C25K)).let { assertTrue(it is DataResult.Success) }
            assertEquals(1, data.workouts.sessionRecords(ana.id, GENERATOR_C25K).first().size)
            data.restingHeartRates.add(ana.id, RestingHeartRateInput(T0 + 2 * DAY_MS, 57, RestingHeartRateSource.Strap, rmssdMs = 40.0))
            assertEquals(2, data.restingHeartRates.measurements(ana.id).first().size)
            assertEquals(DataResult.Success(Unit), data.profiles.delete(ana.id))
            assertFalse(data.restingHeartRates.measurements(ana.id).first().isNotEmpty())
        } finally {
            db.close()
        }
    }

    @Test fun `a version 1 database goes through every migration to version 5`() {
        helper.createDatabase(dbName, 1).use { db ->
            db.execSQL(
                "INSERT INTO users (id, name, nameKey, sex, heightCm, birthEpochDay, restingHeartRateBpm, maxHeartRateBpm, fitnessLevel, goal, " +
                    "targetWeightKg, createdAtMillis, isGeneric) VALUES (1, 'Ana', 'ana', 'Female', 168, 6764, 55, NULL, NULL, NULL, NULL, $T0, 0)",
            )
        }
        helper.runMigrationsAndValidate(dbName, 5, true, *UserDataDatabase.migrations("tablet-1")).use { db ->
            val migrated = rows(db, "SELECT bpm, source, updatedBy, dirty FROM resting_heart_rates")
            assertEquals(listOf(listOf(55L, "Manual", "tablet-1", 1L)), migrated)
        }
    }
}
