package com.treadz.console.training

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.treadz.console.controls.ManualControls
import com.treadz.console.controls.SessionKind
import com.treadz.console.programs.heartrate.HeartRateProfile
import com.treadz.console.programs.heartrate.HeartRateSource
import com.treadz.console.programs.heartrate.HeartRateValidity
import com.treadz.console.programs.model.InvalidProgramException
import com.treadz.console.programs.personalised.CouchToFiveK
import com.treadz.console.programs.personalised.ExerciseMode
import com.treadz.console.programs.personalised.GeneratedProgram
import com.treadz.console.programs.personalised.GeneratorDescriptor
import com.treadz.console.programs.personalised.GeneratorInput
import com.treadz.console.programs.personalised.IntensityTranslator
import com.treadz.console.programs.personalised.ProgramGenerators
import com.treadz.console.programs.plan.ProgramResolver
import com.treadz.console.programs.plan.ResolutionEnvironment
import com.treadz.console.programs.plan.ResolvedPlan
import com.treadz.console.programs.plan.StartParameters
import com.treadz.console.settings.SettingsStore
import com.treadz.console.treadmill.BeltStatus
import com.treadz.console.treadmill.ConnectionState
import com.treadz.console.treadmill.TreadmillController
import com.treadz.console.treadmill.control.SafetyClamp
import com.treadz.console.userdata.UserData
import com.treadz.console.userdata.UserProfile
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate
import java.util.UUID

data class GeneratorUiState(
    val loading: Boolean = true,
    /** The generator id does not exist (a screen opened for a generator this version does not know). */
    val unknownGenerator: Boolean = false,
    val descriptor: GeneratorDescriptor? = null,
    val user: UserProfile? = null,
    val form: GeneratorForm = GeneratorForm(),
    /** The generated session; `null` while [problem] says why it cannot be. */
    val generated: GeneratedProgram? = null,
    val plan: ResolvedPlan? = null,
    val problem: GeneratorProblem? = null,
    /** The active user's heart-rate references: the zones are shown in bpm with them. */
    val heartRateProfile: HeartRateProfile? = null,
    /** There is a heart rate right now (a strap on the app or on the console). */
    val heartRateAvailable: Boolean = false,
    /** The week Couch to 5K suggests starting at (from the fitness level). */
    val suggestedStartWeek: Int? = null,
    /** The inputs come from a stored program: "Regenerate" with today's data. */
    val regenerating: Boolean = false,
    val blocker: StartBlocker? = null,
    val starting: Boolean = false,
    val failure: StartOutcome? = null,
    /** The form holds the generator's defaults (the "restore defaults" action has nothing to do). */
    val atDefaults: Boolean = true,
) {
    val canStart: Boolean get() = blocker == null && plan != null && !starting
}

/**
 * The generator screen: the inputs of a personalised program, the session generated from them and the user's data (every
 * change generates again: it is pure and fast), and the start through the [TrainingCoordinator]. Except for Couch to 5K
 * (whose sessions are only workouts), the generated program is saved as a program of the user once it has started.
 */
class GeneratorViewModel(
    private val generatorId: String,
    /** A stored personalised program whose inputs fill the form ("Regenerate"), or `null`. */
    private val fromProgramId: String?,
    private val userData: UserData,
    private val training: TrainingCoordinator,
    private val controller: TreadmillController,
    private val settings: SettingsStore,
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val today: () -> LocalDate = LocalDate::now,
    /** The name of the generated program: the translated generator name, with the mode and the main input. */
    private val programName: (GeneratorDescriptor, GeneratorForm) -> String = { d, _ -> d.id },
    private val heartRate: HeartRateSource? = null,
    /** The id of the program that will be generated (it stays the same while the inputs change). */
    private val programId: String = UUID.randomUUID().toString(),
) : ViewModel() {

    private val descriptor: GeneratorDescriptor? = ProgramGenerators.byId(generatorId)?.descriptor

    private data class Local(
        val form: GeneratorForm? = null,
        val regenerating: Boolean = false,
        val starting: Boolean = false,
        val failure: StartOutcome? = null,
    )

    private val local = MutableStateFlow(Local())

    private val environment = combine(controller.capabilities, controller.limits) { caps, limits ->
        ResolutionEnvironment(
            speedRange = caps?.speed ?: SafetyClamp.DEFAULT_SPEED_RANGE,
            inclineRange = caps?.incline ?: SafetyClamp.DEFAULT_INCLINE_RANGE,
            limits = limits,
        )
    }

    private data class Belt(val connected: Boolean, val status: BeltStatus, val speed: Double, val session: SessionKind)

    private val belt = combine(controller.connection, controller.state, training.visible) { connection, state, session ->
        Belt(connection is ConnectionState.Connected, state.status, state.speedKmh, session)
    }

    /** Whether a heart rate is coming in now: the app's own source when there is one, else the console's. */
    private val heartRateNow: Flow<Boolean> = if (heartRate != null) {
        heartRate.readings.map { it.bpm != null && it.validity != HeartRateValidity.Invalid }
            .onStart { emit(heartRate.latest().let { it.bpm != null && it.validity != HeartRateValidity.Invalid }) }
            .distinctUntilChanged()
    } else {
        controller.state.map { (it.heartRateBpm ?: 0) > 0 && !it.isStale }.distinctUntilChanged()
    }

    private val data: Flow<GeneratorContext?> = if (descriptor != null) generatorContext(userData, generatorId) else flowOf(null)

    val uiState: StateFlow<GeneratorUiState> = combine(local, environment, belt, data, heartRateNow) { local, env, belt, context, hr ->
        build(local, env, belt, context, hr)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, GeneratorUiState(descriptor = descriptor, unknownGenerator = descriptor == null, loading = descriptor != null))

    init {
        if (descriptor != null) {
            viewModelScope.launch { loadForm(descriptor) }
        }
    }

    /** The form starts with the stored program's inputs (regenerate), else what the user started with last time, else the defaults. */
    private suspend fun loadForm(descriptor: GeneratorDescriptor) {
        val userId = userData.profiles.activeUserId()
        val stored = fromProgramId?.let { userData.programs.get(it) }?.program?.origin?.takeIf { it.generatorId == descriptor.id }
        val form = when {
            stored != null -> GeneratorForm.fromStored(descriptor, storedFromOrigin(stored.inputs))
            userId != null -> GeneratorForm.fromStored(descriptor, settings.generatorInputs(userId, descriptor.id))
            else -> GeneratorForm.defaults(descriptor)
        }
        local.update { it.copy(form = form, regenerating = stored != null) }
    }

    /** The inputs a generator wrote in the origin of its program, in the form's own terms. */
    private fun storedFromOrigin(inputs: Map<String, String>): Map<String, String> = buildMap {
        inputs["mode"]?.let { put("mode", it.replaceFirstChar { c -> c.uppercase() }) }
        inputs["durationMinutes"]?.let { put("duration", it) }
        // The tempo generator also writes its own number of series: only the generators that ask for it have it as an input.
        inputs["series"]?.let { put("series", it) }
        inputs["walkingSpeedKmh"]?.toDoubleOrNull()
            ?.takeIf { kotlin.math.abs(it - IntensityTranslator.DEFAULT_WALKING_SPEED_KMH) > 0.049 }
            ?.let { put("walk", com.treadz.console.Decimals.format(it)) }
        inputs["jogSpeedAdjustmentPercent"]?.let { put("jog", it) }
    }

    private fun build(local: Local, env: ResolutionEnvironment, belt: Belt, context: GeneratorContext?, hrNow: Boolean): GeneratorUiState {
        val d = descriptor ?: return GeneratorUiState(loading = false, unknownGenerator = true)
        val form = local.form ?: return GeneratorUiState(descriptor = d, user = context?.user, loading = true)
        val problem = context?.let { GeneratorRequests.problemOf(d, it, form, today()) }
        var generated: GeneratedProgram? = null
        var plan: ResolvedPlan? = null
        var finalProblem = problem
        if (context != null && problem == null) {
            val request = GeneratorRequests.requestOf(d, context, form, today(), nowMillis(), programName(d, form), programId)
            generated = GeneratorRequests.generate(d, request)
            plan = generated?.let { resolve(it, env) }
            if (plan == null) finalProblem = GeneratorProblem.Rejected
        }
        val beltReady = when (belt.status) {
            BeltStatus.Running, BeltStatus.Paused -> true
            BeltStatus.Idle -> belt.speed <= ManualControls.STOPPED_SPEED_KMH
            BeltStatus.Starting, BeltStatus.SafetyKeyRemoved -> false
        }
        val blocker = when {
            context == null -> StartBlocker.NoUser
            !belt.connected -> StartBlocker.NotConnected
            belt.session != SessionKind.None -> StartBlocker.SessionActive
            !beltReady -> StartBlocker.BeltNotReady
            plan == null -> StartBlocker.InvalidValues
            else -> null
        }
        return GeneratorUiState(
            loading = false,
            descriptor = d,
            user = context?.user,
            form = form,
            generated = generated,
            plan = plan,
            problem = finalProblem,
            heartRateProfile = context?.let { GeneratorRequests.profileOf(it, today()).heartRate },
            heartRateAvailable = hrNow,
            suggestedStartWeek = generated?.couchToFiveK?.suggestedStartWeek,
            regenerating = local.regenerating,
            blocker = blocker,
            starting = local.starting,
            failure = local.failure,
            atDefaults = form == GeneratorForm.defaults(d),
        )
    }

    private fun resolve(generated: GeneratedProgram, env: ResolutionEnvironment): ResolvedPlan? =
        try {
            ProgramResolver.resolve(generated.program, StartParameters(), env)
        } catch (_: InvalidProgramException) {
            null
        }

    // ------------------------------------------------------------------ the form

    private fun edit(change: (GeneratorForm) -> GeneratorForm) =
        local.update { l -> l.form?.let { l.copy(form = change(it), failure = null) } ?: l }

    fun setMode(mode: ExerciseMode) = edit { if (descriptor != null && mode in descriptor.modes) it.copy(mode = mode) else it }

    fun stepDuration(direction: Int) = edit { form ->
        val range = descriptor?.durationMinutes ?: return@edit form
        form.copy(durationMinutes = ((form.durationMinutes ?: range.first) + direction * DURATION_STEP).coerceIn(range.first, range.last))
    }

    fun stepSeries(direction: Int) = edit { form ->
        val range = descriptor?.series ?: return@edit form
        form.copy(series = ((form.series ?: range.first) + direction).coerceIn(range.first, range.last))
    }

    fun setWalkingSpeed(text: String) = edit { it.copy(walkingSpeed = text) }

    fun stepWalkingSpeed(direction: Int) = edit { form ->
        val current = form.walkingSpeedKmh ?: IntensityTranslator.DEFAULT_WALKING_SPEED_KMH
        val value = (kotlin.math.round((current + direction * WALKING_STEP) * 10) / 10.0).coerceIn(GeneratorForm.WALKING_SPEED_RANGE.start, GeneratorForm.WALKING_SPEED_RANGE.endInclusive)
        form.copy(walkingSpeed = com.treadz.console.Decimals.format(value))
    }

    /** Couch to 5K: continue the plan (`false`) or choose the week to start at (`true`, from the suggested week). */
    fun chooseStartWeek(choose: Boolean) = edit { form ->
        form.copy(startWeek = if (choose) (uiState.value.suggestedStartWeek ?: 1) else null)
    }

    fun setStartWeek(week: Int) = edit { it.copy(startWeek = week.coerceIn(1, 9)) }

    fun stepJogAdjustment(direction: Int) = edit {
        it.copy(jogAdjustmentPercent = (it.jogAdjustmentPercent + direction * GeneratorForm.JOG_STEP).coerceIn(-GeneratorForm.JOG_LIMIT, GeneratorForm.JOG_LIMIT))
    }

    /** Puts back the generator's defaults, ignoring what was used last time (that only changes on start). */
    fun restoreDefaults() = edit { descriptor?.let(GeneratorForm::defaults) ?: it }

    // ------------------------------------------------------------------ start

    /** Starts the session; [onStarted] is called once the belt runs and the program clock has started. */
    fun start(onStarted: () -> Unit) {
        val state = uiState.value
        val plan = state.plan
        val generated = state.generated
        val user = state.user
        val d = state.descriptor
        if (!state.canStart || plan == null || generated == null || user == null || d == null) return
        local.update { it.copy(starting = true, failure = null) }
        viewModelScope.launch {
            val outcome = training.startProgram(user.id, plan, plan.parameters)
            local.update { it.copy(starting = false, failure = outcome.takeIf { o -> o != StartOutcome.Started }) }
            if (outcome == StartOutcome.Started) {
                // The session is saved only once it runs; Couch to 5K's sessions are only workouts (their records).
                if (d.id != CouchToFiveK.ID) userData.programs.save(user.id, generated.program)
                settings.setGeneratorInputs(user.id, d.id, state.form.toStored(d))
                onStarted()
            }
        }
    }

    companion object {
        const val DURATION_STEP = 5
        const val WALKING_STEP = 0.1

        /** The main input of a generator, for the name of its program and the list of saved sessions. */
        fun mainInput(descriptor: GeneratorDescriptor): GeneratorInput? = when {
            GeneratorInput.DurationMinutes in descriptor.inputs -> GeneratorInput.DurationMinutes
            GeneratorInput.Series in descriptor.inputs -> GeneratorInput.Series
            else -> null
        }
    }
}
