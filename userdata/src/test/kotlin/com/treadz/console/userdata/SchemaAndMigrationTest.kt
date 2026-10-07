package com.treadz.console.userdata

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import androidx.room.Room
import com.treadz.console.userdata.room.UserDataDatabase
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * Guards the on-disk format. The exported schema files are the contract with the databases already installed on
 * users' devices: the version-1 file must open with the current code, and every later version needs a migration.
 */
@RunWith(RobolectricTestRunner::class)
class SchemaAndMigrationTest {

    @get:Rule val folder = TemporaryFolder()

    private val schemaDir = File("schemas/com.treadz.console.userdata.room.UserDataDatabase")

    private fun schema(version: Int): JSONObject = JSONObject(File(schemaDir, "$version.json").readText()).getJSONObject("database")

    @Test fun `every version has an exported schema and a migration from the previous one`() {
        val current = UserDataDatabase.VERSION
        for (v in 1..current) assertTrue("schemas/.../$v.json is missing", File(schemaDir, "$v.json").isFile)
        assertEquals(current, schema(current).getInt("version"))
        for (v in 1 until current) {
            assertTrue(
                "no migration from $v to ${v + 1}",
                UserDataDatabase.MIGRATIONS.any { it.startVersion == v && it.endVersion == v + 1 },
            )
        }
    }

    /** Builds a database file exactly as version 1 created it (from the exported schema), with data in it. */
    private fun createVersion1File(): File {
        val db1 = schema(1)
        val file = folder.newFile("v1.db")
        val sql = SQLiteDatabase.openOrCreateDatabase(file, null)
        sql.execSQL("PRAGMA foreign_keys = ON")
        val entities = db1.getJSONArray("entities")
        for (i in 0 until entities.length()) {
            val e = entities.getJSONObject(i)
            val table = e.getString("tableName")
            sql.execSQL(e.getString("createSql").replace("\${TABLE_NAME}", table))
            val indices = e.optJSONArray("indices")
            if (indices != null) {
                for (j in 0 until indices.length()) {
                    sql.execSQL(indices.getJSONObject(j).getString("createSql").replace("\${TABLE_NAME}", table))
                }
            }
        }
        val setup = db1.getJSONArray("setupQueries")
        for (i in 0 until setup.length()) sql.execSQL(setup.getString(i))
        sql.version = 1

        sql.execSQL(
            "INSERT INTO users (id, name, nameKey, sex, heightCm, birthEpochDay, restingHeartRateBpm, maxHeartRateBpm, " +
                "fitnessLevel, goal, targetWeightKg, createdAtMillis, isGeneric) VALUES (1, 'Ana', 'ana', 'Female', 168, 6764, 55, NULL, " +
                "'Intermediate', 'LoseWeight', 62.5, $T0, 0)",
        )
        sql.execSQL("INSERT INTO active_user (id, userId) VALUES (1, 1)")
        sql.execSQL("INSERT INTO weight_entries (userId, epochDay, weightKg, isPlaceholder) VALUES (1, 20000, 64.0, 0), (1, 20100, 63.2, 0)")
        sql.execSQL(
            "INSERT INTO workouts (id, userId, startedAtMillis, endedAtMillis, endReason, durationSeconds, distanceMeters, " +
                "calories, averageSpeedKmh, maxSpeedKmh, averageHeartRateBpm, maxHeartRateBpm, maxInclinePercent) " +
                "VALUES (1, 1, $T0, ${T0 + 2000}, 'Stopped', 2, 4, 1, 8.0, 8.0, NULL, NULL, 0.0)",
        )
        sql.execSQL(
            "INSERT INTO workout_samples (workoutId, seq, timestampMillis, elapsedSeconds, speedKmh, targetSpeedKmh, " +
                "inclinePercent, distanceMeters, calories, heartRateBpm, beltStatus, isStale) " +
                "VALUES (1, 0, $T0, 1, 8.0, 8.0, 0.0, 2, 0, NULL, 'Running', 0), (1, 1, ${T0 + 1000}, 2, 8.0, 8.0, 0.0, 4, 1, NULL, 'Running', 0)",
        )
        sql.close()
        return file
    }

    @Test fun `a version 1 database opens with the current code, schema validated, and its data is readable`() = runBlocking<Unit> {
        val file = createVersion1File()
        val context = RuntimeEnvironment.getApplication() as Context
        val db = Room.databaseBuilder(context, UserDataDatabase::class.java, file.absolutePath)
            .addMigrations(*UserDataDatabase.MIGRATIONS)
            .allowMainThreadQueries()
            .build()
        try {
            val data = com.treadz.console.userdata.room.RoomUserData(db, MutableClock(), ZoneOffset.UTC)
            val ana = data.profiles.activeUser.first()!!
            assertEquals("Ana", ana.name)
            assertEquals(Sex.Female, ana.sex)
            assertEquals(LocalDate.ofEpochDay(6764), ana.birthDate)
            assertEquals(FitnessLevel.Intermediate, ana.fitnessLevel)
            assertEquals(Goal.LoseWeight, ana.goal)
            assertEquals(62.5, data.weights.targetWeightKg(ana.id).first()!!, 0.0)
            assertEquals(63.2, data.weights.latestWeight(ana.id).first()!!.weightKg, 0.0)
            val workout = data.workouts.workouts(ana.id).first().single()
            assertEquals(WorkoutEndReason.Stopped, workout.endReason)
            assertEquals(2, data.workouts.detail(workout.id)!!.samples.size)
            // and foreign keys are enforced on the opened database
            assertEquals(DataResult.NotFound, data.weights.add(99, LocalDate.of(2026, 1, 1), 70.0))
            data.profiles.delete(ana.id)
            assertTrue(data.workouts.detail(workout.id) == null)
        } finally {
            db.close()
        }
    }
}
