package com.treadz.console.userdata.room

import androidx.room.Database
import androidx.room.RoomDatabase
import androidx.room.migration.Migration

/**
 * Schema version 1: profiles, active user, weight log and workouts with their per-second samples.
 *
 * ## Changing the schema
 * 1. Bump [VERSION] and edit the entities. The build exports the new schema to `userdata/schemas/<version>.json`:
 *    commit it, schemas of released versions are never edited.
 * 2. Add a [Migration] from the previous version to [MIGRATIONS] and a migration test. The database is **never**
 *    rebuilt destructively: users' weights and workouts must survive an update.
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
        const val VERSION = 1
        const val FILE_NAME = "treadz_userdata.db"

        /** Migrations between consecutive versions; empty while version 1 is the only one. */
        val MIGRATIONS: Array<Migration> = emptyArray()
    }
}
