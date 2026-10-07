package com.treadz.console.userdata

import android.content.Context
import androidx.room.Room
import com.treadz.console.treadmill.control.Clock
import com.treadz.console.userdata.room.RoomUserData
import com.treadz.console.userdata.room.UserDataDatabase
import java.time.ZoneId

/**
 * The app's access point to the real, on-device data layer (a single Room database, one instance per process).
 * For tests and screenshots use `InMemoryUserData` instead.
 */
object TreadZUserData {

    @Volatile
    private var instance: UserData? = null

    fun get(context: Context): UserData =
        instance ?: synchronized(this) { instance ?: create(context.applicationContext).also { instance = it } }

    private fun create(context: Context): UserData {
        val db = Room.databaseBuilder(context, UserDataDatabase::class.java, UserDataDatabase.FILE_NAME)
            .addMigrations(*UserDataDatabase.MIGRATIONS)
            .build()
        return RoomUserData(db, Clock.System, ZoneId.systemDefault())
    }
}
