package com.treadz.console.userdata

import android.content.Context
import androidx.room.Room
import com.treadz.console.treadmill.control.Clock
import com.treadz.console.userdata.room.RoomUserData
import com.treadz.console.userdata.room.UserDataDatabase
import com.treadz.console.userdata.sync.FitnessTestDocumentStore
import com.treadz.console.userdata.sync.ProgramDocumentStore
import com.treadz.console.userdata.sync.SyncStore
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import java.time.ZoneId
import java.util.UUID

/**
 * The app's access point to the real, on-device data layer (a single Room database, one instance per process).
 * For tests and screenshots use `InMemoryUserData` instead.
 */
object TreadZUserData {

    private const val PREFS = "treadz_userdata"
    private const val KEY_DEVICE_ID = "device_id"

    @Volatile
    private var instance: SyncableUserData? = null

    /** Runs the recalculations of derived data (fitness test results, workout analyses), off the main thread, from when the data layer is created. */
    private val background = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    fun get(context: Context): UserData = syncable(context)

    /**
     * The port of the `:sync` module to the same data layer as [get]. The UI does not need it; `:sync` does.
     */
    fun sync(context: Context): SyncStore = syncable(context).sync

    /** The port of the `:sync` module to the user programs (the same rows as `UserData.programs`). */
    fun programDocuments(context: Context): ProgramDocumentStore = syncable(context).programDocuments

    /** The port of the `:sync` module to the fitness test results (the same rows as `UserData.fitnessTests`). */
    fun fitnessTestDocuments(context: Context): FitnessTestDocumentStore = syncable(context).fitnessTestDocuments

    /**
     * The stable id of this installation (generated on first use), stamped on every change so that other devices can
     * resolve conflicts. It never leaves the device except inside the synchronised records.
     */
    fun deviceId(context: Context): String {
        val prefs = context.applicationContext.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
        prefs.getString(KEY_DEVICE_ID, null)?.let { return it }
        return UUID.randomUUID().toString().also { prefs.edit().putString(KEY_DEVICE_ID, it).apply() }
    }

    /**
     * A Room-backed data layer on a throw-away in-memory database, with an injectable clock, device id and uuid
     * source. For tests of modules that need the real merge rules (`:sync` tests use it to simulate devices); not for
     * the app. The caller owns it: it is never closed.
     */
    fun createInMemory(
        context: Context,
        clock: Clock = Clock.System,
        deviceId: String = "test-device",
        zone: ZoneId = ZoneId.systemDefault(),
        seedGenericProfile: Boolean = true,
        uuidSource: () -> String = { UUID.randomUUID().toString() },
    ): SyncableUserData {
        val db = Room.inMemoryDatabaseBuilder(context.applicationContext, UserDataDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        return RoomUserData(db, clock, zone, seedGenericProfile, deviceId, uuidSource)
    }

    private fun syncable(context: Context): SyncableUserData =
        instance ?: synchronized(this) { instance ?: create(context.applicationContext).also { instance = it } }

    private fun create(context: Context): SyncableUserData {
        val deviceId = deviceId(context)
        val db = Room.databaseBuilder(context, UserDataDatabase::class.java, UserDataDatabase.FILE_NAME)
            .addMigrations(*UserDataDatabase.migrations(deviceId))
            .build()
        val data = RoomUserData(db, Clock.System, ZoneId.systemDefault(), deviceId = deviceId)
        // Migration-like: results stored by an older version get their derived values (VO2max of the walking test,
        // thresholds) from their workouts. Once per result; the synchronisation repeats it when a pull brings data.
        // Then the workout analyses are brought up to date (they depend on those results) and kept so: after every change
        // of the data they are computed from (a workout ends, a pull, a local edit), debounced.
        background.launch {
            try {
                data.fitnessRecalculator.recalculate()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // A failure leaves the results as they were; the next start or synchronisation tries again.
            }
            try {
                data.workoutAnalysisRecalculator.recalculate()
                data.workoutAnalysisRecalculator.keepUpToDate()
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                // Derived data only: the next start tries again.
            }
        }
        return data
    }
}
