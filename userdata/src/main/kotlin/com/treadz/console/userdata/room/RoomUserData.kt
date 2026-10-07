package com.treadz.console.userdata.room

import com.treadz.console.treadmill.TreadmillController
import com.treadz.console.treadmill.control.Clock
import com.treadz.console.userdata.ProfileRepository
import com.treadz.console.userdata.UserData
import com.treadz.console.userdata.WeightRepository
import com.treadz.console.userdata.WorkoutHistoryRepository
import com.treadz.console.userdata.workout.WorkoutHistoryObserver
import com.treadz.console.userdata.workout.WorkoutObserverConfig
import java.time.ZoneId

/** [UserData] backed by a Room database. */
internal class RoomUserData(
    private val db: UserDataDatabase,
    clock: Clock = Clock.System,
    zone: ZoneId = ZoneId.systemDefault(),
    /** Creates the generic profile when the database is empty (always on in the app; tests may turn it off). */
    seedGenericProfile: Boolean = true,
) : UserData {
    private val workoutRepository = RoomWorkoutRepository(db)

    override val profiles: ProfileRepository = RoomProfileRepository(db, clock, zone, seedGenericProfile)
    override val weights: WeightRepository = RoomWeightRepository(db, clock, zone)
    override val workouts: WorkoutHistoryRepository = workoutRepository

    override fun workoutObserver(
        controller: TreadmillController,
        clock: Clock,
        config: WorkoutObserverConfig,
        onError: (Throwable) -> Unit,
    ) = WorkoutHistoryObserver(controller, workoutRepository, profiles::activeUserId, clock, config, onError)
}
