package com.treadz.console.training

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.treadz.console.controls.SessionKind
import com.treadz.console.programs.engine.ProgramProgress
import com.treadz.console.programs.engine.Refusal
import com.treadz.console.programs.engine.RunnerResult
import com.treadz.console.programs.fitness.FitnessTestProgress
import com.treadz.console.programs.fitness.FitnessTestPhase
import com.treadz.console.programs.fitness.HeartRateMaxDecision
import com.treadz.console.treadmill.CommandResult
import com.treadz.console.userdata.DataResult
import com.treadz.console.userdata.UserData
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

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

    val uiState: StateFlow<RunUiState> = combine(
        combine(training.visible, training.programRunner.progress, training.fitnessRunner.progress) { kind, program, test -> Triple(kind, program, test) },
        combine(local, training.saveFailed, extras) { local, saveFailed, extras -> Triple(local, saveFailed, extras) },
    ) { (kind, program, test), (local, saveFailed, extras) ->
        RunUiState(kind, program, test, local.continuing, local.failure, saveFailed, extras.comparison, extras.heartRateMax)
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
