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
    ],
    version = UserDataDatabase.VERSION,
    exportSchema = true,
)
internal abstract class UserDataDatabase : RoomDatabase() {
    abstract fun users(): UserDao
    abstract fun weights(): WeightDao
    abstract fun workouts(): WorkoutDao

    companion object {
        const val VERSION = 3
        const val FILE_NAME = "treadz_userdata.db"

        /** Device id recorded on the rows migrated by [MIGRATIONS], which has no device to ask. */
        const val MIGRATED_DEVICE_ID = "migrated"

        /** Migrations between consecutive versions, stamping migrated rows with [MIGRATED_DEVICE_ID]. */
        val MIGRATIONS: Array<Migration> = migrations(MIGRATED_DEVICE_ID)

        /** Migrations between consecutive versions; rows that exist before version 2 are stamped with [deviceId]. */
        fun migrations(deviceId: String): Array<Migration> = arrayOf(Migration1To2(deviceId), Migration2To3())
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
