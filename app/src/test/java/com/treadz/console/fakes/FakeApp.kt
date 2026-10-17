package com.treadz.console.fakes

import android.content.Intent
import com.treadz.console.AppDependencies
import com.treadz.console.mainViewModel
import com.treadz.console.MainViewModel
import com.treadz.console.nav.NavigationViewModel
import com.treadz.console.programs.engine.ProgramRunner
import com.treadz.console.programs.fitness.FitnessTestRunner
import com.treadz.console.devices.StrapController
import com.treadz.console.heartrate.PeakOfferController
import com.treadz.console.hrm.resting.RestingHeartRateSession
import com.treadz.console.programs.heartrate.AutomaticHeartRateSource
import com.treadz.console.programs.heartrate.ControllerHeartRateSource
import com.treadz.console.programs.heartrate.restingHeartRateSession
import com.treadz.console.sync.service.FakeSyncService
import com.treadz.console.training.SessionLinks
import com.treadz.console.training.TrainingCoordinator
import com.treadz.console.userdata.WorkoutLink
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import com.treadz.console.treadmill.RecordingInfo
import com.treadz.console.treadmill.control.Clock
import com.treadz.console.userdata.export.InMemoryExportStorage
import com.treadz.console.userdata.export.WorkoutExporter
import com.treadz.console.userdata.memory.InMemoryUserData
import java.time.LocalDate
import java.time.ZoneId
import java.time.ZoneOffset

/**
 * Everything the UI needs, built on fakes, with direct access to them to set up scenarios. The data layer starts
 * like a fresh install (one generic profile, active); the date is fixed so screenshots and tests are repeatable.
 */
class FakeApp(
    override val controller: FakeTreadmillController = FakeTreadmillController(),
    override val recorder: FakeWorkoutRecorder = FakeWorkoutRecorder(),
    override val settings: InMemorySettingsStore = InMemorySettingsStore(),
    val date: LocalDate = TODAY,
    zone: ZoneId = ZoneId.systemDefault(),
    override val appLifecycle: FakeAppLifecycle = FakeAppLifecycle(controller),
    override val userData: InMemoryUserData = InMemoryUserData(
        clock = Clock { date.atTime(12, 0).atZone(zone).toInstant().toEpochMilli() },
        zone = zone,
    ),
    override val sync: FakeSyncService = FakeSyncService(now = { date.atTime(12, 0).atZone(zone).toInstant().toEpochMilli() }),
    override val language: FakeLanguageSettings = FakeLanguageSettings(),
    /** Where the program and fitness test runners run. Tests with virtual time pass their own scope and clock. */
    private val trainingScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default),
    private val trainingClock: Clock = Clock.System,
    /** Where the strap controller and the automatic heart-rate source run: inline, so tests need no waiting. */
    private val strapScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined),
    /** Time of the automatic connection window of the main ViewModel. */
    private val mainClock: Clock = Clock.System,
    /** How long the start-up scan for the strap pairing question lasts (screenshots do not wait the real six seconds). */
    private val strapPairingScanMillis: Long = StrapController.PAIRING_SCAN_MILLIS,
) : AppDependencies {

    /** The strap connected to the app (none paired until a test says so). */
    val strapMonitor = FakeHeartRateMonitor()

    override val heartRate: AutomaticHeartRateSource by lazy { AutomaticHeartRateSource(strapMonitor, ControllerHeartRateSource(controller), strapScope) }

    override val strap: StrapController by lazy {
        StrapController(strapMonitor, heartRate, controller.state, userData.profiles, settings, strapScope, pairingScanMillis = strapPairingScanMillis).also { it.start() }
    }

    override val peakOffers: PeakOfferController by lazy { PeakOfferController(userData, settings, ::nowMillis) }

    override fun restingSession(scope: CoroutineScope): RestingHeartRateSession = restingHeartRateSession(heartRate, scope)

    override fun nowMillis(): Long = date.atTime(12, 0).atZone(zoneId).toInstant().toEpochMilli()

    private val zoneId = zone


    /** The links the training coordinator asked for, in order. */
    val links = RecordingSessionLinks()
    val programRunner: ProgramRunner by lazy { ProgramRunner(controller, trainingScope, trainingClock) }
    val fitnessRunner: FitnessTestRunner by lazy { FitnessTestRunner(controller, trainingScope, trainingClock) }

    /** What went wrong in the background of the training coordinator (the record of a generated session). */
    val trainingErrors = mutableListOf<Throwable>()

    override val training: TrainingCoordinator by lazy {
        TrainingCoordinator(controller, programRunner, fitnessRunner, links, userData, trainingScope, onError = { trainingErrors += it })
    }


    /** Where exported files end up in tests. */
    val exportStorage = InMemoryExportStorage()

    override val exporter: WorkoutExporter = WorkoutExporter("0.1.0", exportStorage, ZoneOffset.UTC)

    override fun shareIntent(recording: RecordingInfo): Intent = Intent(Intent.ACTION_SEND)

    override fun today(): LocalDate = date

    /** The trends of the Performance screen are computed inline: a screen never waits for another thread. */
    override val computeDispatcher: kotlinx.coroutines.CoroutineDispatcher = kotlinx.coroutines.Dispatchers.Unconfined

    /** Built like the activity builds it. */
    val viewModel: MainViewModel by lazy { mainViewModel(mainClock) }

    /** A new navigation state, as when the app opens (create it after setting up the profiles). */
    fun newNavigation(): NavigationViewModel = NavigationViewModel(userData.profiles)

    val navigation: NavigationViewModel by lazy { newNavigation() }

    companion object {
        val TODAY: LocalDate = LocalDate.of(2026, 10, 7)
    }
}

/** Remembers what the training coordinator linked to the workout history. */
class RecordingSessionLinks : SessionLinks {
    val linked = mutableListOf<WorkoutLink>()
    var clears = 0

    /** The workout the coordinator attaches a generated session record to (tests set it to a workout they created). */
    var workoutId: Long? = null

    override fun link(link: WorkoutLink) {
        linked += link
    }

    override fun clear() {
        clears++
    }

    override fun currentWorkoutId(): Long? = workoutId
}
