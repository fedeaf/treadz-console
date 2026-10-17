package com.treadz.console.userdata.room

import com.treadz.console.programs.heartrate.HeartRateSource
import com.treadz.console.treadmill.TreadmillController
import com.treadz.console.treadmill.control.Clock
import com.treadz.console.userdata.FitnessRecalculator
import com.treadz.console.userdata.FitnessTestRepository
import com.treadz.console.userdata.StoredResultsRecalculator
import com.treadz.console.userdata.ProfileRepository
import com.treadz.console.userdata.ProgramRepository
import com.treadz.console.userdata.RestingHeartRateRepository
import com.treadz.console.userdata.StartParametersRepository
import com.treadz.console.userdata.SyncableUserData
import com.treadz.console.userdata.WeightRepository
import com.treadz.console.userdata.StoredWorkoutAnalysisRecalculator
import com.treadz.console.userdata.WorkoutAnalysisRecalculator
import com.treadz.console.userdata.WorkoutAnalysisRepository
import com.treadz.console.userdata.WorkoutHistoryRepository
import com.treadz.console.userdata.sync.FitnessTestDocumentStore
import com.treadz.console.userdata.sync.ProgramDocumentStore
import com.treadz.console.userdata.sync.SyncStore
import com.treadz.console.userdata.sync.UploadHold
import com.treadz.console.userdata.workout.WorkoutHistoryObserver
import com.treadz.console.userdata.workout.WorkoutObserverConfig
import kotlinx.coroutines.flow.first
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
    private val fitnessTestRepository = RoomFitnessTestRepository(db, clock, zone, stamper, uploadHold)
    override val fitnessTests: FitnessTestRepository = fitnessTestRepository
    override val startParameters: StartParametersRepository = RoomStartParametersRepository(db, stamper)
    override val restingHeartRates: RestingHeartRateRepository = RoomRestingHeartRateRepository(db, clock, stamper)
    override val fitnessRecalculator: FitnessRecalculator = StoredResultsRecalculator(RoomRecalculationSource(db, fitnessTestRepository))
    override val workoutAnalysis: WorkoutAnalysisRepository = RoomWorkoutAnalysisRepository(db, zone)
    override val workoutAnalysisRecalculator: WorkoutAnalysisRecalculator =
        StoredWorkoutAnalysisRecalculator(RoomAnalysisSource(db), zone, clock::nowMillis, analysisChanges(db))
    override val sync: SyncStore = RoomSyncStore(db, clock, zone, stamper, uploadHold)
    override val programDocuments: ProgramDocumentStore = RoomProgramDocuments(db)
    override val fitnessTestDocuments: FitnessTestDocumentStore = RoomFitnessTestDocuments(db, uploadHold)

    override fun workoutObserver(
        controller: TreadmillController,
        clock: Clock,
        config: WorkoutObserverConfig,
        heartRateSource: HeartRateSource?,
        onError: (Throwable) -> Unit,
    ) = WorkoutHistoryObserver(
        controller, workoutRepository, profiles::activeUserId, clock, config, onError, heartRateSource,
        // The maximum heart rate in use, for the plausibility rules of the workout's heart rate peak.
        heartRateMax = { userId -> userId?.let { fitnessTests.heartRateMax(it).first()?.bpm } },
    )
}
