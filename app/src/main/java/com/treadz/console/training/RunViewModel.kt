package com.treadz.console.training

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.treadz.console.controls.SessionKind
import com.treadz.console.programs.engine.ProgramProgress
import com.treadz.console.programs.engine.Refusal
import com.treadz.console.programs.engine.RunState
import com.treadz.console.programs.engine.RunnerResult
import com.treadz.console.programs.fitness.FitnessTestProgress
import com.treadz.console.programs.fitness.FitnessTestPhase
import com.treadz.console.programs.fitness.HeartRateMaxDecision
import com.treadz.console.programs.fitness.HeartRateSourceKind
import com.treadz.console.programs.heartrate.HeartRateSource
import com.treadz.console.programs.personalised.CouchToFiveK
import com.treadz.console.programs.personalised.CouchToFiveKSelection
import com.treadz.console.treadmill.CommandResult
import com.treadz.console.userdata.DataResult
import com.treadz.console.userdata.UserData
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate

/** Why "Continue" did not work. */
sealed interface ContinueFailure {
    data class Refused(val refusal: Refusal) : ContinueFailure
    data class Command(val result: CommandResult) : ContinueFailure
}

data class RunUiState(
    /** Which session the screen shows. */
    val kind: SessionKind = SessionKind.None,
    val program: ProgramProgress = ProgramProgress(),
    val test: FitnessTestProgress = FitnessTestProgress(),
    val continuing: Boolean = false,
    val continueFailure: ContinueFailure? = null,
    /** The result of the finished test could not be stored. */
    val saveFailed: Boolean = false,
    /** For a finished test: how it compares with the previous test of the same type. */
    val comparison: FitnessComparison? = null,
    val heartRateMax: HeartRateMaxDecision? = null,
    /** Where the heart rate comes from now (`null`: none, or not known). */
    val heartRateSource: HeartRateSourceKind? = null,
    /** After a Couch to 5K session: what comes next, once the record of the session that has just ended is stored. */
    val couchNext: CouchToFiveKSelection? = null,
) {
    val testResultShown: Boolean get() = kind == SessionKind.FitnessTest && test.phase == FitnessTestPhase.Finished
}

/**
 * The screen of the program or fitness test in progress (or that has just ended). It only observes the runners and
 * calls them; the state lives in the runners, so it survives the screen being off, the app in the background and
 * this screen being recreated.
 */
class RunViewModel(
    private val training: TrainingCoordinator,
    private val userData: UserData,
    /** The app's heart-rate source, for the icon next to the heart rate during a test. */
    private val heartRate: HeartRateSource? = null,
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val today: () -> LocalDate = LocalDate::now,
) : ViewModel() {

    private data class Local(val continuing: Boolean = false, val failure: ContinueFailure? = null)

    private val local = MutableStateFlow(Local())

    private data class Extras(val comparison: FitnessComparison?, val heartRateMax: HeartRateMaxDecision?)

    @OptIn(ExperimentalCoroutinesApi::class)
    private val extras: Flow<Extras> = combine(userData.profiles.activeUser, training.fitnessRunner.progress) { user, progress -> user to progress.result }
        .flatMapLatest { (user, result) ->
            if (user == null || result == null) {
                flowOf(Extras(null, null))
            } else {
                combine(userData.fitnessTests.results(user.id), userData.fitnessTests.heartRateMax(user.id)) { results, hrMax ->
                    Extras(FitnessResults.compare(result, results.map { it.result }), hrMax)
                }
            }
        }

    private val heartRateSource: Flow<HeartRateSourceKind?> = if (heartRate == null) {
        flowOf(null)
    } else {
        heartRate.readings.map { r -> r.source.takeIf { r.bpm != null } }
            .onStart { emit(heartRate.latest().let { r -> r.source.takeIf { r.bpm != null } }) }
            .distinctUntilChanged()
    }

    /** What comes after the Couch to 5K session that has just finished (see [RunUiState.couchNext]). */
    @OptIn(ExperimentalCoroutinesApi::class)
    private val couchNext: Flow<CouchToFiveKSelection?> = training.programRunner.progress
        .map { p -> p.plan?.takeIf { p.state == RunState.Finished && it.source.origin?.generatorId == CouchToFiveK.ID } }
        .distinctUntilChanged()
        .flatMapLatest { plan ->
            if (plan == null) {
                flowOf(null)
            } else {
                generatorContext(userData, CouchToFiveK.ID).map { context ->
                    // The record of this very session must be stored, or what comes next would follow from the one before.
                    if (context == null || context.history.none { it.programId == plan.source.programId }) {
                        null
                    } else {
                        GeneratorRequests.couchSession(context, today(), nowMillis())?.couchToFiveK
                    }
                }
            }
        }

    val uiState: StateFlow<RunUiState> = combine(
        combine(training.visible, training.programRunner.progress, training.fitnessRunner.progress) { kind, program, test -> Triple(kind, program, test) },
        combine(local, training.saveFailed, extras) { local, saveFailed, extras -> Triple(local, saveFailed, extras) },
        combine(heartRateSource, couchNext) { source, next -> source to next },
    ) { (kind, program, test), (local, saveFailed, extras), (source, next) ->
        RunUiState(kind, program, test, local.continuing, local.failure, saveFailed, extras.comparison, extras.heartRateMax, source, next)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, RunUiState())

    // ------------------------------------------------------------------ program

    fun skipToNext() {
        viewModelScope.launch { training.programRunner.skipToNext() }
    }

    fun skipToPrevious() {
        viewModelScope.launch { training.programRunner.skipToPrevious() }
    }

    /** Continues an interrupted program; a refusal is kept in the state for the screen to explain. */
    fun continueProgram() {
        if (local.value.continuing) return
        local.update { Local(continuing = true) }
        viewModelScope.launch {
            val failure = when (val result = training.programRunner.continueProgram()) {
                RunnerResult.Success -> null
                is RunnerResult.NotAllowed -> ContinueFailure.Refused(result.refusal)
                is RunnerResult.CommandFailed -> ContinueFailure.Command(result.result)
            }
            local.update { Local(continuing = false, failure = failure) }
        }
    }

    /** Switches the heart-rate control of the current block on again (after a manual change turned it off). */
    fun resumeHeartRateControl() {
        training.resumeHeartRateControl()
    }

    /** Gives up an interrupted program, leaving the belt as it is. */
    fun abandon() {
        local.update { Local() }
        training.programRunner.abandon()
    }

    // ------------------------------------------------------------------ fitness test

    fun exhausted() {
        training.fitnessRunner.exhausted()
    }

    fun endTest() {
        training.fitnessRunner.endTest()
    }

    /** Walking heart-rate test, warm-up: changes the speed and/or incline the ramp starts at. */
    fun adjustRampStart(speedKmh: Double?, inclinePercent: Double?) {
        training.adjustRampStart(speedKmh, inclinePercent)
    }

    /** One − / + step of the ramp start speed (0.1 km/h) or incline (1 %) from what the test shows now. */
    fun stepRampSpeed(direction: Int) {
        val current = uiState.value.test.nextStageSpeedKmh ?: return
        adjustRampStart(kotlin.math.round((current + direction * 0.1) * 10) / 10.0, null)
    }

    fun stepRampIncline(direction: Int) {
        val current = uiState.value.test.nextStageInclinePercent ?: return
        adjustRampStart(null, kotlin.math.round(current) + direction)
    }

    /** Closes the result: the main screen shows the live data again. */
    /** Throws away the workout of the program / test that has just ended and closes its result. */
    fun discard() {
        local.update { Local() }
        training.discardFinishedWorkout()
    }

    fun dismiss() {
        local.update { Local() }
        training.dismissResult()
    }

    fun useMeasuredHeartRateMax() = answer { userData.fitnessTests.useMeasuredHeartRateMax(it) }

    fun keepManualHeartRateMax() = answer { userData.fitnessTests.keepManualHeartRateMax(it) }

    private fun answer(call: suspend (Long) -> DataResult<Unit>) {
        viewModelScope.launch {
            val user = userData.profiles.activeUser.first() ?: return@launch
            call(user.id)
        }
    }
}
