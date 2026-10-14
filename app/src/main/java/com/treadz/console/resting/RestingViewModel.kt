package com.treadz.console.resting

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.treadz.console.devices.LiveHeartRate
import com.treadz.console.hrm.resting.RestingHeartRateProgress
import com.treadz.console.hrm.resting.RestingHeartRateResult
import com.treadz.console.hrm.resting.RestingHeartRateSession
import com.treadz.console.programs.fitness.HeartRateSourceKind
import com.treadz.console.userdata.DataResult
import com.treadz.console.userdata.InputField
import com.treadz.console.userdata.RestingHeartRateAssessment
import com.treadz.console.userdata.RestingHeartRateInput
import com.treadz.console.userdata.RestingHeartRateMeasurement
import com.treadz.console.userdata.RestingHeartRateReference
import com.treadz.console.userdata.RestingHeartRateSource
import com.treadz.console.userdata.UserData
import com.treadz.console.userdata.UserProfile
import com.treadz.console.userdata.ValidationCode
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class RestingPhase {
    /** How to measure, and the Start button. */
    Instructions,

    /** The 3 minutes are running. */
    Measuring,

    /** The measurement is over: its result (saved when valid). */
    Result,
}

/** The "add a value by hand" dialog. */
data class ManualRestingEditor(val text: String = "", val error: ValidationCode? = null, val saving: Boolean = false)

data class RestingUiState(
    val loaded: Boolean = false,
    val user: UserProfile? = null,
    val phase: RestingPhase = RestingPhase.Instructions,
    /** The live heart rate and its source (`source == null`: there is nothing to measure with). */
    val live: LiveHeartRate = LiveHeartRate(),
    val progress: RestingHeartRateProgress = RestingHeartRateProgress(),
    val result: RestingHeartRateResult? = null,
    /** The valid result as stored. */
    val saved: RestingHeartRateMeasurement? = null,
    val saveFailed: Boolean = false,
    /** Set when the saved measurement is clearly above the usual: the non-blocking "take it easy" warning. */
    val elevated: RestingHeartRateAssessment? = null,
    val reference: RestingHeartRateReference? = null,
    /** The user's measurements, oldest first. */
    val measurements: List<RestingHeartRateMeasurement> = emptyList(),
    val editor: ManualRestingEditor? = null,
    val confirmingDeleteId: Long? = null,
) {
    /** There is a heart-rate source to measure with: the strap connected to the app or the treadmill console. */
    val hasSource: Boolean get() = live.source != null
    val canStart: Boolean get() = user != null && hasSource && phase != RestingPhase.Measuring
    val measuring: Boolean get() = phase == RestingPhase.Measuring
}

/**
 * The resting heart rate screen: a 3-minute measurement fed by the heart rate in use (strap or console, **no treadmill
 * needed**), its result saved when valid, the reference and the history. A valid measurement is saved as soon as it is
 * over (it can be deleted from the list); an invalid one is never stored.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class RestingViewModel(
    private val userData: UserData,
    live: Flow<LiveHeartRate>,
    /** Builds the measurement session in the given scope; it starts with `start()`. */
    private val newSession: (CoroutineScope) -> RestingHeartRateSession,
    private val now: () -> Long = { System.currentTimeMillis() },
) : ViewModel() {

    private data class Local(
        val phase: RestingPhase = RestingPhase.Instructions,
        val progress: RestingHeartRateProgress = RestingHeartRateProgress(),
        val result: RestingHeartRateResult? = null,
        val saved: RestingHeartRateMeasurement? = null,
        val saveFailed: Boolean = false,
        val elevated: RestingHeartRateAssessment? = null,
        val editor: ManualRestingEditor? = null,
        val confirmingDeleteId: Long? = null,
    )

    private val local = MutableStateFlow(Local())
    private var session: RestingHeartRateSession? = null
    private var progressJob: Job? = null

    /** The source of the heart rate while measuring (the last one seen), to store with the result. */
    private var measuredSource: HeartRateSourceKind? = null
    private var latestLive = LiveHeartRate()

    private data class History(val user: UserProfile?, val measurements: List<RestingHeartRateMeasurement>, val reference: RestingHeartRateReference?)

    private val history: Flow<History> = userData.profiles.activeUser.flatMapLatest { user ->
        if (user == null) {
            flowOf(History(null, emptyList(), null))
        } else {
            combine(userData.restingHeartRates.measurements(user.id), userData.restingHeartRates.reference(user.id)) { all, reference ->
                History(user, all, reference)
            }
        }
    }

    private val liveWithSource: Flow<LiveHeartRate> = live

    val uiState: StateFlow<RestingUiState> = combine(history, liveWithSource, local) { history, live, local ->
        latestLive = live
        if (local.phase == RestingPhase.Measuring && live.source != null) measuredSource = live.source
        RestingUiState(
            loaded = true,
            user = history.user,
            phase = local.phase,
            live = live,
            progress = local.progress,
            result = local.result,
            saved = local.saved,
            saveFailed = local.saveFailed,
            elevated = local.elevated,
            reference = history.reference,
            measurements = history.measurements,
            editor = local.editor,
            confirmingDeleteId = local.confirmingDeleteId,
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, RestingUiState())

    // ------------------------------------------------------------------ the measurement

    /** Starts the 3-minute measurement (needs an active user and a heart-rate source). */
    fun start() {
        if (!uiState.value.canStart) return
        progressJob?.cancel()
        measuredSource = latestLive.source
        local.update { Local(phase = RestingPhase.Measuring, editor = it.editor) }
        val measurement = newSession(viewModelScope)
        session = measurement
        progressJob = viewModelScope.launch {
            measurement.progress.collect { progress ->
                local.update { it.copy(progress = progress) }
                val result = progress.result
                if (result != null) {
                    finish(result)
                    return@collect
                }
            }
        }
        measurement.start()
    }

    /** Ends the measurement early: it is too short, so it is not valid and nothing is stored. */
    fun cancel() {
        session?.cancel()
    }

    /** Back to the instructions ("Measure again" / "Done"). Not while measuring. */
    fun reset() {
        if (local.value.phase == RestingPhase.Measuring) return
        progressJob?.cancel()
        local.update { Local(editor = it.editor, confirmingDeleteId = it.confirmingDeleteId) }
    }

    /** The screen is left: a running measurement is dropped and the screen starts again next time. */
    fun leave() {
        session?.cancel()
        progressJob?.cancel()
        session = null
        local.value = Local()
    }

    private suspend fun finish(result: RestingHeartRateResult) {
        local.update { it.copy(phase = RestingPhase.Result, result = result, saved = null, saveFailed = false, elevated = null) }
        val user = uiState.value.user ?: return
        val kind = if (measuredSource == HeartRateSourceKind.DirectStrap) RestingHeartRateSource.Strap else RestingHeartRateSource.Console
        // Only a valid result gives an input: an invalid measurement is never stored.
        val input = RestingHeartRateInput.fromResult(result, kind) ?: return
        when (val stored = userData.restingHeartRates.add(user.id, input)) {
            is DataResult.Success -> {
                val assessment = userData.restingHeartRates.assessment(stored.value.id)
                local.update { it.copy(saved = stored.value, elevated = assessment?.takeIf { a -> a.isElevated }) }
            }
            else -> local.update { it.copy(saveFailed = true) }
        }
    }

    // ------------------------------------------------------------------ manual value

    fun openManual() = local.update { it.copy(editor = ManualRestingEditor()) }

    fun setManualText(text: String) = local.update { state -> state.copy(editor = state.editor?.copy(text = text, error = null)) }

    fun dismissManual() = local.update { it.copy(editor = null) }

    /** Adds the typed value as a measurement of now (source: manual). */
    fun saveManual() {
        val editor = local.value.editor ?: return
        val user = uiState.value.user ?: return
        if (editor.saving) return
        val bpm = editor.text.trim().toIntOrNull()
        if (bpm == null) {
            local.update { it.copy(editor = editor.copy(error = if (editor.text.isBlank()) ValidationCode.Blank else ValidationCode.OutOfRange)) }
            return
        }
        local.update { it.copy(editor = editor.copy(saving = true)) }
        viewModelScope.launch {
            val result = userData.restingHeartRates.add(user.id, RestingHeartRateInput(now(), bpm, RestingHeartRateSource.Manual))
            local.update { state ->
                when (result) {
                    is DataResult.Success -> state.copy(editor = null)
                    is DataResult.Invalid ->
                        state.copy(editor = editor.copy(saving = false, error = result.issues.firstOrNull { it.field == InputField.RestingHeartRate }?.code ?: ValidationCode.OutOfRange))
                    else -> state.copy(editor = editor.copy(saving = false, error = ValidationCode.OutOfRange))
                }
            }
        }
    }

    // ------------------------------------------------------------------ delete

    fun requestDelete(measurementId: Long) = local.update { it.copy(confirmingDeleteId = measurementId) }

    fun cancelDelete() = local.update { it.copy(confirmingDeleteId = null) }

    fun confirmDelete() {
        val id = local.value.confirmingDeleteId ?: return
        local.update { it.copy(confirmingDeleteId = null) }
        viewModelScope.launch {
            userData.restingHeartRates.delete(id)
            // The result on screen was this very measurement: it is gone, so is its warning.
            local.update { if (it.saved?.id == id) it.copy(saved = null, elevated = null) else it }
        }
    }
}
