package com.treadz.console.userdata

import android.content.Context
import androidx.room.Room
import com.treadz.console.userdata.room.RoomUserData
import com.treadz.console.userdata.room.UserDataDatabase
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * The real data layer of the app brings the workout analyses up to date by itself, in the background, when it is created
 * (after the fitness results) and keeps them so after every change of the data they depend on.
 */
@RunWith(RobolectricTestRunner::class)
class WorkoutAnalysisAtStartTest {

    private suspend fun <T> eventually(what: String, seconds: Int = 20, read: suspend () -> T?): T {
        repeat(seconds * 10) {
            read()?.let { return it }
            delay(100)
        }
        throw AssertionError("$what did not happen in $seconds s")
    }

    @Test fun `opening the real data layer computes the analyses, and a change recomputes them`() = runBlocking<Unit> {
        val context = RuntimeEnvironment.getApplication() as Context
        val deviceId = TreadZUserData.deviceId(context)
        // what the previous version left in the database file: a user and a finished workout, no analyses (schema 6 is empty)
        val db = Room.databaseBuilder(context, UserDataDatabase::class.java, UserDataDatabase.FILE_NAME)
            .addMigrations(*UserDataDatabase.migrations(deviceId))
            .allowMainThreadQueries()
            .build()
        val seed = RoomUserData(db, MutableClock(), ZoneOffset.UTC, seedGenericProfile = false, deviceId = deviceId)
        val user = (seed.profiles.create(validInput()) as DataResult.Success).value
        val id = seed.addFinishedWorkout(user.id, T0, steadySamples(T0, 300), WorkoutStats(durationSeconds = 300, distanceMeters = 600))
        db.close()

        // a new process: the singleton is created again
        TreadZUserData::class.java.getDeclaredField("instance").apply { isAccessible = true }.set(TreadZUserData, null)
        val data = TreadZUserData.get(context)
        val first = eventually("the analysis at start") { data.workoutAnalysis.analysis(id).first() }
        assertEquals(300, first.analysis.signal.sampleCount)
        assertNull("no weight yet", first.analysis.energy.value!!.grossKcal)
        assertEquals(0, data.workoutAnalysisRecalculator.recalculate())

        // a weight is added later, by the user: it is noticed without anybody calling the recalculator
        assertTrue(data.weights.add(user.id, LocalDate.of(2026, 9, 1), 70.0) is DataResult.Success)
        val second = eventually("the recalculation after a change") { data.workoutAnalysis.analysis(id).first()?.takeIf { it.analysis.energy.value?.grossKcal != null } }
        assertNotNull(second.analysis.energy.value!!.grossKcal)
        assertEquals(0, data.workoutAnalysisRecalculator.recalculate())
    }
}
