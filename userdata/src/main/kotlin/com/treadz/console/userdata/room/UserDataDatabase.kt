package com.treadz.console.userdata.room

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase

/**
 * Schema versions:
 * 1. Profiles, active user, weight log and workouts with their per-second samples.
 * 2. Synchronisation bookkeeping on profiles, weight entries and workouts: global `uuid`, version
 *    (`updatedAtMillis` + `updatedBy`), `deleted` tombstone and `dirty` flag. The unique index on the profile name
 *    became a plain index. See [Migration1To2].
 * 3. Weight entries carry `createdAtMillis`, the synchronised tie-break between entries of the same day. See
 *    [Migration2To3].
 * 4. Programs, fitness tests and the fitness profile: tables `programs`, `fitness_tests` and `start_parameters`;
 *    `users.maxHeartRateSetAtMillis`; the executed plan and fitness test id on `workouts`. See [Migration3To4].
 * 5. Heart rate data: the source and RR intervals of each sample, the heart rate peak, the generated session record and
 *    the generator id of each workout, the strap of each user and the table `resting_heart_rates` (the resting heart
 *    rate history). See [Migration4To5].
 * 6. The cache of the per-workout analysis: table `workout_analysis` (derived data, never synchronised). See
 *    [Migration5To6].
 *
 * ## Changing the schema
 * 1. Bump [VERSION] and edit the entities. The build exports the new schema to `userdata/schemas/<version>.json`:
 *    commit it, schemas of released versions are never edited.
 * 2. Add a [Migration] from the previous version to [migrations] and a migration test (`MigrationTestHelper`, using
 *    the exported schemas, with a populated database of the previous version). The database is **never** rebuilt
 *    destructively: users' weights and workouts must survive an update.
 */
@Database(
    entities = [
        UserEntity::class,
        ActiveUserEntity::class,
        WeightEntryEntity::class,
        WorkoutEntity::class,
        WorkoutSampleEntity::class,
        ProgramEntity::class,
        FitnessTestEntity::class,
        StartParametersEntity::class,
        RestingHeartRateEntity::class,
        WorkoutAnalysisEntity::class,
    ],
    version = UserDataDatabase.VERSION,
    exportSchema = true,
)
internal abstract class UserDataDatabase : RoomDatabase() {
    abstract fun users(): UserDao
    abstract fun weights(): WeightDao
    abstract fun workouts(): WorkoutDao
    abstract fun programs(): ProgramDao
    abstract fun fitnessTests(): FitnessTestDao
    abstract fun startParameters(): StartParametersDao
    abstract fun restingHeartRates(): RestingHeartRateDao
    abstract fun workoutAnalysis(): WorkoutAnalysisDao

    companion object {
        const val VERSION = 6
        const val FILE_NAME = "treadz_userdata.db"

        /** Device id recorded on the rows migrated by [MIGRATIONS], which has no device to ask. */
        const val MIGRATED_DEVICE_ID = "migrated"

        /** Migrations between consecutive versions, stamping migrated rows with [MIGRATED_DEVICE_ID]. */
        val MIGRATIONS: Array<Migration> = migrations(MIGRATED_DEVICE_ID)

        /** Migrations between consecutive versions; rows that exist before version 2 are stamped with [deviceId]. */
        fun migrations(deviceId: String): Array<Migration> = arrayOf(Migration1To2(deviceId), Migration2To3(), Migration3To4(), Migration4To5(deviceId), Migration5To6())
    }
}

/**
 * Version 1 to 2. Keeps every row (nothing is dropped or rebuilt) and only adds columns and indexes:
 * - every existing profile, weight entry and workout gets a random version-4 UUID;
 * - its version is the best date we have: profiles their creation time, weight entries midnight UTC of their date,
 *   workouts their end (or start) time; `updatedBy` is [deviceId];
 * - nothing is deleted, and everything is flagged `dirty` so that it is uploaded when the device is linked to a
 *   repository (while no repository is configured the flag has no effect).
 *
 * The version-4 UUID is built in SQL from `randomblob`, evaluated once per row.
 */
internal class Migration1To2(private val deviceId: String) : Migration(1, 2) {

    override fun migrate(db: SupportSQLiteDatabase) {
        val quotedDevice = "'" + deviceId.replace("'", "''") + "'"
        for (table in listOf("users", "weight_entries", "workouts")) {
            db.execSQL("ALTER TABLE `$table` ADD COLUMN `uuid` TEXT NOT NULL DEFAULT ''")
            db.execSQL("ALTER TABLE `$table` ADD COLUMN `updatedAtMillis` INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE `$table` ADD COLUMN `updatedBy` TEXT NOT NULL DEFAULT ''")
            db.execSQL("ALTER TABLE `$table` ADD COLUMN `deleted` INTEGER NOT NULL DEFAULT 0")
            db.execSQL("ALTER TABLE `$table` ADD COLUMN `dirty` INTEGER NOT NULL DEFAULT 0")
            db.execSQL("UPDATE `$table` SET `uuid` = $UUID_V4_SQL, `updatedBy` = $quotedDevice, `dirty` = 1")
        }
        db.execSQL("UPDATE `users` SET `updatedAtMillis` = `createdAtMillis`")
        db.execSQL("UPDATE `weight_entries` SET `updatedAtMillis` = `epochDay` * 86400000")
        db.execSQL("UPDATE `workouts` SET `updatedAtMillis` = COALESCE(`endedAtMillis`, `startedAtMillis`)")

        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_users_uuid` ON `users` (`uuid`)")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_weight_entries_uuid` ON `weight_entries` (`uuid`)")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_workouts_uuid` ON `workouts` (`uuid`)")
        // The name index is no longer unique: devices may create profiles with the same name independently.
        db.execSQL("DROP INDEX IF EXISTS `index_users_nameKey`")
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_users_nameKey` ON `users` (`nameKey`)")
    }

    private companion object {
        const val UUID_V4_SQL =
            "lower(hex(randomblob(4)) || '-' || hex(randomblob(2)) || '-4' || substr(hex(randomblob(2)), 2) || '-' || " +
                "substr('89ab', abs(random()) % 4 + 1, 1) || substr(hex(randomblob(2)), 2) || '-' || hex(randomblob(6)))"
    }
}

/**
 * Version 2 to 3. Adds `weight_entries.createdAtMillis` and keeps every row. Existing entries get their
 * `updatedAtMillis` as creation time. That is deliberately a function of synchronised data only: a repository
 * written by a version without the column has no creation times, and readers fall back to the same value
 * (`updated_at`), so devices that already share entries keep agreeing on their order. (Entries migrated from
 * version 1 have midnight UTC of their date there, so such old entries of the same day are ordered by uuid.) The
 * rows are not flagged `dirty`: nothing needs uploading because of this migration.
 */
internal class Migration2To3 : Migration(2, 3) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `weight_entries` ADD COLUMN `createdAtMillis` INTEGER NOT NULL DEFAULT 0")
        db.execSQL("UPDATE `weight_entries` SET `createdAtMillis` = `updatedAtMillis`")
    }
}

/**
 * Version 3 to 4 (milestone 5: programs, fitness tests and the fitness profile). Only adds; every existing row is kept
 * as it is and nothing is flagged `dirty` (there is nothing new to upload):
 * - `users.maxHeartRateSetAtMillis`: `NULL` for existing profiles, so an existing manual maximum heart rate counts as
 *   older than any fitness test;
 * - `workouts.planJson`, `programId`, `programName`, `fitnessTestId`: `NULL` (no existing workout had a program);
 * - the new tables `programs`, `fitness_tests` and `start_parameters`, empty.
 * The statements are the ones of the exported version-4 schema.
 */
internal class Migration3To4 : Migration(3, 4) {
    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `users` ADD COLUMN `maxHeartRateSetAtMillis` INTEGER")
        db.execSQL("ALTER TABLE `workouts` ADD COLUMN `planJson` TEXT")
        db.execSQL("ALTER TABLE `workouts` ADD COLUMN `programId` TEXT")
        db.execSQL("ALTER TABLE `workouts` ADD COLUMN `programName` TEXT")
        db.execSQL("ALTER TABLE `workouts` ADD COLUMN `fitnessTestId` TEXT")

        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `programs` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `userId` INTEGER NOT NULL, " +
                "`uuid` TEXT NOT NULL, `name` TEXT NOT NULL, `nameKey` TEXT NOT NULL, `kind` TEXT NOT NULL, `json` TEXT NOT NULL, " +
                "`createdAtMillis` INTEGER NOT NULL, `updatedAtMillis` INTEGER NOT NULL, `updatedBy` TEXT NOT NULL, " +
                "`deleted` INTEGER NOT NULL, `dirty` INTEGER NOT NULL, " +
                "FOREIGN KEY(`userId`) REFERENCES `users`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_programs_userId` ON `programs` (`userId`)")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_programs_uuid` ON `programs` (`uuid`)")

        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `fitness_tests` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `userId` INTEGER NOT NULL, " +
                "`uuid` TEXT NOT NULL, `testType` TEXT NOT NULL, `startedAtMillis` INTEGER NOT NULL, `isValid` INTEGER NOT NULL, " +
                "`vo2maxMlKgMin` REAL, `masKmh` REAL, `json` TEXT NOT NULL, `updatedAtMillis` INTEGER NOT NULL, " +
                "`updatedBy` TEXT NOT NULL, `deleted` INTEGER NOT NULL, `dirty` INTEGER NOT NULL, " +
                "FOREIGN KEY(`userId`) REFERENCES `users`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_fitness_tests_userId_startedAtMillis` ON `fitness_tests` (`userId`, `startedAtMillis`)")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_fitness_tests_uuid` ON `fitness_tests` (`uuid`)")

        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `start_parameters` (`userId` INTEGER NOT NULL, `programId` TEXT NOT NULL, " +
                "`totalDurationSeconds` INTEGER, `easySpeedKmh` REAL, `maxSpeedKmh` REAL, `maxInclinePercent` REAL, " +
                "`intensityAdjustmentPercent` INTEGER NOT NULL, `updatedAtMillis` INTEGER NOT NULL, " +
                "PRIMARY KEY(`userId`, `programId`), " +
                "FOREIGN KEY(`userId`) REFERENCES `users`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
        )
    }
}

/**
 * Version 4 to 5 (heart rate data). Only adds; every existing row is kept as it is:
 * - `workout_samples.heartRateSource`, `rrIntervalsMs` and `workouts.peakHeartRate*`, `sessionRecordJson`: `NULL` (older
 *   workouts have no source, RR intervals, peak or session record; their samples came from the console);
 * - `workouts.generatorId`: filled from the origin of the stored plan when it has one;
 * - `users.strapAddress` and `strapName`: `NULL`;
 * - the new table `resting_heart_rates`, filled with one manual measurement per existing profile that had a resting
 *   heart rate (dated by the profile's last change, uuid `resting-<profile uuid>` so that devices converting the same
 *   profile agree, flagged `dirty` so that it is uploaded). The old column `users.restingHeartRateBpm` stays as it is
 *   and is no longer read.
 * The statements are the ones of the exported version-5 schema.
 */
internal class Migration4To5(private val deviceId: String) : Migration(4, 5) {

    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL("ALTER TABLE `users` ADD COLUMN `strapAddress` TEXT")
        db.execSQL("ALTER TABLE `users` ADD COLUMN `strapName` TEXT")
        db.execSQL("ALTER TABLE `workouts` ADD COLUMN `peakHeartRateBpm` INTEGER")
        db.execSQL("ALTER TABLE `workouts` ADD COLUMN `peakHeartRateSource` TEXT")
        db.execSQL("ALTER TABLE `workouts` ADD COLUMN `peakHeartRateAtMillis` INTEGER")
        db.execSQL("ALTER TABLE `workouts` ADD COLUMN `sessionRecordJson` TEXT")
        db.execSQL("ALTER TABLE `workouts` ADD COLUMN `generatorId` TEXT")
        db.execSQL("ALTER TABLE `workout_samples` ADD COLUMN `heartRateSource` TEXT")
        db.execSQL("ALTER TABLE `workout_samples` ADD COLUMN `rrIntervalsMs` TEXT")

        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `resting_heart_rates` (`id` INTEGER PRIMARY KEY AUTOINCREMENT NOT NULL, `userId` INTEGER NOT NULL, " +
                "`uuid` TEXT NOT NULL, `measuredAtMillis` INTEGER NOT NULL, `bpm` INTEGER NOT NULL, `source` TEXT NOT NULL, " +
                "`lowestMeanBpm` REAL, `coverage` REAL, `rmssdMs` REAL, `updatedAtMillis` INTEGER NOT NULL, `updatedBy` TEXT NOT NULL, " +
                "`deleted` INTEGER NOT NULL, `dirty` INTEGER NOT NULL, " +
                "FOREIGN KEY(`userId`) REFERENCES `users`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
        )
        db.execSQL("CREATE INDEX IF NOT EXISTS `index_resting_heart_rates_userId_measuredAtMillis` ON `resting_heart_rates` (`userId`, `measuredAtMillis`)")
        db.execSQL("CREATE UNIQUE INDEX IF NOT EXISTS `index_resting_heart_rates_uuid` ON `resting_heart_rates` (`uuid`)")

        val quotedDevice = "'" + deviceId.replace("'", "''") + "'"
        db.execSQL(
            "INSERT INTO `resting_heart_rates` (`userId`, `uuid`, `measuredAtMillis`, `bpm`, `source`, `lowestMeanBpm`, `coverage`, `rmssdMs`, " +
                "`updatedAtMillis`, `updatedBy`, `deleted`, `dirty`) " +
                "SELECT `id`, '${RESTING_UUID_PREFIX}' || `uuid`, `updatedAtMillis`, `restingHeartRateBpm`, 'Manual', NULL, NULL, NULL, " +
                "`updatedAtMillis`, $quotedDevice, 0, 1 FROM `users` WHERE `restingHeartRateBpm` IS NOT NULL AND `deleted` = 0",
        )

        // The generator of the personalised programs already run, from the stored plan.
        val generators = mutableListOf<Pair<Long, String>>()
        db.query("SELECT `id`, `planJson` FROM `workouts` WHERE `planJson` IS NOT NULL").use { c ->
            while (c.moveToNext()) {
                readPlan(c.getString(1))?.source?.origin?.generatorId?.let { generators += c.getLong(0) to it }
            }
        }
        for ((id, generatorId) in generators) {
            db.execSQL("UPDATE `workouts` SET `generatorId` = ? WHERE `id` = ?", arrayOf<Any>(generatorId, id))
        }
    }

    private companion object {
        /** `RestingHeartRates.legacyMeasurementUuid` for every profile, in SQL. */
        const val RESTING_UUID_PREFIX = "resting-"
    }
}

/**
 * Version 5 to 6 (the cache of the per-workout analysis). Only adds the empty table `workout_analysis`: every existing
 * row is kept as it is, nothing is flagged `dirty` (the analyses are derived data and are never synchronised), and the
 * analyses are computed in the background by the recalculation (`WorkoutAnalysisRecalculator`) the first time the app
 * runs. The statement is the one of the exported version-6 schema.
 */
internal class Migration5To6 : Migration(5, 6) {

    override fun migrate(db: SupportSQLiteDatabase) {
        db.execSQL(
            "CREATE TABLE IF NOT EXISTS `workout_analysis` (`workoutId` INTEGER NOT NULL, `algorithmVersion` INTEGER NOT NULL, " +
                "`fingerprint` TEXT NOT NULL, `computedAtMillis` INTEGER NOT NULL, `json` TEXT NOT NULL, `trimp` REAL, `fitnessIndex` REAL, " +
                "`decouplingPercent` REAL, `longSteadyZone2` INTEGER NOT NULL, `recoveryDrop60Bpm` REAL, `recoveryDrop120Bpm` REAL, " +
                "`recoveryContext` TEXT, PRIMARY KEY(`workoutId`), " +
                "FOREIGN KEY(`workoutId`) REFERENCES `workouts`(`id`) ON UPDATE NO ACTION ON DELETE CASCADE )",
        )
    }
}
