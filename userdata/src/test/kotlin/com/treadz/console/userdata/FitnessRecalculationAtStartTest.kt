package com.treadz.console.userdata

import android.content.Context
import androidx.room.Room
import com.treadz.console.programs.fitness.Vo2MaxMethod
import com.treadz.console.userdata.room.RoomUserData
import com.treadz.console.userdata.room.UserDataDatabase
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.time.ZoneOffset

/** The data layer of the app brings the stored results up to date by itself, in the background, when it is created. */
@RunWith(RobolectricTestRunner::class)
class FitnessRecalculationAtStartTest {

    @Test fun `opening the real data layer recalculates the results stored by an older version`() = runBlocking<Unit> {
        val context = RuntimeEnvironment.getApplication() as Context
        val deviceId = TreadZUserData.deviceId(context)
        // what the previous version left in the database file
        val db = Room.databaseBuilder(context, UserDataDatabase::class.java, UserDataDatabase.FILE_NAME)
            .addMigrations(*UserDataDatabase.migrations(deviceId))
            .allowMainThreadQueries()
            .build()
        val seed = RoomUserData(db, MutableClock(), ZoneOffset.UTC, seedGenericProfile = false, deviceId = deviceId)
        val user = (seed.profiles.create(validInput()) as DataResult.Success).value
        seed.fitnessTests.save(user.id, balkeResult(TEST_3, T0))
        seed.addFinishedWorkout(user.id, T0, SyntheticTestWorkout.walking(), WorkoutStats(durationSeconds = 720, distanceMeters = 800)).also {
            seed.workouts.link(it, WorkoutLink(fitnessTestId = TEST_3))
        }
        db.close()

        // a new process: the singleton is created again
        TreadZUserData::class.java.getDeclaredField("instance").apply { isAccessible = true }.set(TreadZUserData, null)
        val data = TreadZUserData.get(context)
        var outputs = data.fitnessTests.get(TEST_3)?.result?.outputs
        repeat(100) {
            if (outputs == null) {
                delay(100)
                outputs = data.fitnessTests.get(TEST_3)?.result?.outputs
            }
        }
        assertNotNull("the result was not recalculated in the background", outputs)
        assertEquals(Vo2MaxMethod.AcsmWalkRamp, outputs!!.method)
        val result = data.fitnessTests.get(TEST_3)!!.result
        assertNotNull(result.thresholds?.vt1)
        assertTrue(result.schemaVersion == 2)
        assertEquals(0, data.fitnessRecalculator.recalculate())
        assertEquals(1, data.fitnessTests.results(user.id).first().size)
    }
}
