package com.treadz.console

import android.content.Context
import android.content.Intent
import android.util.Log
import com.treadz.console.devices.StrapController
import com.treadz.console.heartrate.PeakOfferController
import com.treadz.console.hrm.HeartRateMonitor
import com.treadz.console.hrm.android.TreadZHeartRate
import com.treadz.console.hrm.resting.RestingHeartRateSession
import com.treadz.console.programs.heartrate.AutomaticHeartRateSource
import com.treadz.console.programs.heartrate.ControllerHeartRateSource
import com.treadz.console.programs.heartrate.HeartRateSource
import com.treadz.console.programs.heartrate.restingHeartRateSession
import com.treadz.console.settings.AndroidLanguageSettings
import com.treadz.console.settings.LanguageSettings
import com.treadz.console.settings.SettingsStore
import com.treadz.console.settings.SharedPreferencesSettingsStore
import com.treadz.console.sync.TreadZSync
import com.treadz.console.sync.service.SyncService
import com.treadz.console.training.ObserverSessionLinks
import com.treadz.console.training.TrainingCoordinator
import com.treadz.console.programs.engine.ProgramRunner
import com.treadz.console.programs.fitness.FitnessTestRunner
import com.treadz.console.treadmill.RecordingInfo
import com.treadz.console.treadmill.TreadmillController
import com.treadz.console.treadmill.WorkoutRecorder
import com.treadz.console.treadmill.android.TreadZTreadmill
import com.treadz.console.treadmill.control.Clock
import com.treadz.console.treadmill.lifecycle.AppLifecycle
import com.treadz.console.userdata.TreadZUserData
import com.treadz.console.userdata.UserData
import com.treadz.console.userdata.export.WorkoutExporter
import com.treadz.console.userdata.workout.WorkoutHistoryObserver
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import java.time.LocalDate

/**
 * Everything the UI needs from outside. The app uses [RealDependencies]; tests and screenshots
 * build the ViewModels directly with fake implementations, without touching production code.
 */
interface AppDependencies {
    val controller: TreadmillController
    val recorder: WorkoutRecorder
    val settings: SettingsStore
    val userData: UserData

    /** Optional synchronisation with a private GitHub repository (inactive until a token is configured). */
    val sync: SyncService

    /** The app's own language (Android 13+ per-app language). */
    val language: LanguageSettings

    /** App visibility reported to the backend, and the one way to leave the app. */
    val appLifecycle: AppLifecycle

    /**
     * The program and fitness test runners, one of each per process, and what ties them to the screens (routing of the
     * manual controls, starting, storing test results). Screens only observe and call them.
     */
    val training: TrainingCoordinator

    /** The heart rate in use everywhere: the strap connected to the app, else the treadmill console, else none. */
    val heartRate: HeartRateSource

    /** The active user's strap: kept connected, paired and scanned for; the live heart rate. */
    val strap: StrapController

    /** The "your heart rate reached X, use it as your maximum?" offers. */
    val peakOffers: PeakOfferController

    /** A resting heart-rate measurement fed by [heartRate] (it runs in [scope] and starts with `start()`). */
    fun restingSession(scope: CoroutineScope): RestingHeartRateSession

    /** The current time in epoch milliseconds. Injectable so tests and screenshots are deterministic. */
    fun nowMillis(): Long = System.currentTimeMillis()

    /** Writes workouts to CSV or TCX files in the Downloads folder. */
    val exporter: WorkoutExporter
    fun shareIntent(recording: RecordingInfo): Intent

    /** Today's date in the device time zone. Injectable so tests and screenshots are deterministic. */
    fun today(): LocalDate

    /** Where the Performance trends are computed (off the main thread). Tests run them inline so screens never wait on it. */
    val computeDispatcher: CoroutineDispatcher get() = Dispatchers.Default
}

/**
 * The main ViewModel wired to everything it uses. The activity builds it here (and so do the tests), so none of its
 * optional collaborators (synchronisation, training, strap) can be left out by mistake.
 */
fun AppDependencies.mainViewModel(clock: Clock = Clock.System): MainViewModel =
    MainViewModel(controller, recorder, settings, userData, appLifecycle, sync, training, strap, clock)

/** [scope] lives as long as the process: the runners and the workout history observer run in it. */
class RealDependencies(private val context: Context, private val scope: CoroutineScope) : AppDependencies {
    override val controller: TreadmillController by lazy { TreadZTreadmill.controller(context) }
    override val recorder: WorkoutRecorder by lazy { TreadZTreadmill.recorder(context) }
    override val settings: SettingsStore by lazy { SharedPreferencesSettingsStore(context) }
    override val userData: UserData by lazy { TreadZUserData.get(context) }
    override val sync: SyncService by lazy { TreadZSync.get(context) }
    override val language: LanguageSettings by lazy { AndroidLanguageSettings(context) }

    /** The strap connected to the app (one per process, also used by the debug recording). */
    private val strapMonitor: HeartRateMonitor by lazy { TreadZHeartRate.monitor(context) }

    override val heartRate: AutomaticHeartRateSource by lazy {
        AutomaticHeartRateSource(strapMonitor, ControllerHeartRateSource(controller), scope, trace = TreadZTreadmill.recordingSink(context))
    }
    override val strap: StrapController by lazy {
        StrapController(strapMonitor, heartRate, controller.state, userData.profiles, settings, scope)
    }
    override val peakOffers: PeakOfferController by lazy { PeakOfferController(userData, settings) }
    override fun restingSession(scope: CoroutineScope): RestingHeartRateSession = restingHeartRateSession(heartRate, scope)

    /** Records every treadmill session into the workout history. Started once by the application. */
    val workoutObserver: WorkoutHistoryObserver by lazy {
        userData.workoutObserver(controller, heartRateSource = heartRate, onError = { Log.e("TreadZ", "Workout history error", it) })
    }

    override val training: TrainingCoordinator by lazy {
        TrainingCoordinator(
            controller = controller,
            programRunner = ProgramRunner(controller, scope, heartRate = heartRate),
            fitnessRunner = FitnessTestRunner(controller, scope, heartRate = heartRate),
            links = ObserverSessionLinks(workoutObserver),
            userData = userData,
            scope = scope,
            onError = { Log.e("TreadZ", "Generated session record", it) },
        ).also { coordinator -> TreadZTreadmill.routeNotificationStop(context, coordinator::stopFromNotification) }
    }
    override val appLifecycle: AppLifecycle by lazy { TreadZTreadmill.lifecycle(context) }
    override val exporter: WorkoutExporter by lazy { WorkoutExporter.create(context, BuildConfig.VERSION_NAME) }
    override fun shareIntent(recording: RecordingInfo): Intent = TreadZTreadmill.shareIntent(context, recording)
    override fun today(): LocalDate = LocalDate.now()
}
