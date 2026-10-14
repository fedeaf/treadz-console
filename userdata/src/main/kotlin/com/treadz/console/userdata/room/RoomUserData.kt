package com.treadz.console.userdata.room

import com.treadz.console.treadmill.TreadmillController
import com.treadz.console.treadmill.control.Clock
import com.treadz.console.userdata.FitnessTestRepository
import com.treadz.console.userdata.ProfileRepository
import com.treadz.console.userdata.ProgramRepository
import com.treadz.console.userdata.StartParametersRepository
import com.treadz.console.userdata.SyncableUserData
import com.treadz.console.userdata.WeightRepository
import com.treadz.console.userdata.WorkoutHistoryRepository
import com.treadz.console.userdata.sync.FitnessTestDocumentStore
import com.treadz.console.userdata.sync.ProgramDocumentStore
import com.treadz.console.userdata.sync.SyncStore
import com.treadz.console.userdata.sync.UploadHold
import com.treadz.console.userdata.workout.WorkoutHistoryObserver
import com.treadz.console.userdata.workout.WorkoutObserverConfig
import java.time.ZoneId
import java.util.UUID

/** [SyncableUserData] backed by a Room database. */
internal class RoomUserData(
    private val db: UserDataDatabase,
    clock: Clock = Clock.System,
    zone: ZoneId = ZoneId.systemDefault(),
    /** Creates the generic profile when the database is empty (always on in the app; tests may turn it off). */
    seedGenericProfile: Boolean = true,
    /** Stable id of this installation; stamped on every change so that other devices can resolve conflicts. */
    deviceId: String = "local",
    uuidSource: () -> String = { UUID.randomUUID().toString() },
) : SyncableUserData {
    private val stamper = Stamper(clock, deviceId, uuidSource)
    private val uploadHold = UploadHold()
    private val workoutRepository = RoomWorkoutRepository(db, stamper, uploadHold)

    override val profiles: ProfileRepository = RoomProfileRepository(db, clock, zone, seedGenericProfile, stamper)
    override val weights: WeightRepository = RoomWeightRepository(db, clock, zone, stamper)
    override val workouts: WorkoutHistoryRepository = workoutRepository
    override val programs: ProgramRepository = RoomProgramRepository(db, stamper)
    override val fitnessTests: FitnessTestRepository = RoomFitnessTestRepository(db, clock, zone, stamper, uploadHold)
    override val startParameters: StartParametersRepository = RoomStartParametersRepository(db, stamper)
    override val sync: SyncStore = RoomSyncStore(db, clock, zone, stamper, uploadHold)
    override val programDocuments: ProgramDocumentStore = RoomProgramDocuments(db)
    override val fitnessTestDocuments: FitnessTestDocumentStore = RoomFitnessTestDocuments(db, uploadHold)

    override fun workoutObserver(
        controller: TreadmillController,
        clock: Clock,
        config: WorkoutObserverConfig,
        onError: (Throwable) -> Unit,
    ) = WorkoutHistoryObserver(controller, workoutRepository, profiles::activeUserId, clock, config, onError)
}
