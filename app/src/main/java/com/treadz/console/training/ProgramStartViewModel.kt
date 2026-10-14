package com.treadz.console.training

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.treadz.console.Decimals
import com.treadz.console.controls.ManualControls
import com.treadz.console.controls.SessionKind
import com.treadz.console.programs.heartrate.HeartRateProfile
import com.treadz.console.programs.model.Block
import com.treadz.console.programs.model.BlockTarget
import com.treadz.console.programs.model.InvalidProgramException
import com.treadz.console.programs.model.Program
import com.treadz.console.programs.model.ProgramKind
import com.treadz.console.programs.model.ProgramLimits
import com.treadz.console.programs.model.ProgramOrigin
import com.treadz.console.programs.model.Repeat
import com.treadz.console.programs.plan.IntensityParameter
import com.treadz.console.programs.plan.ProgramResolver
import com.treadz.console.programs.plan.ResolutionEnvironment
import com.treadz.console.programs.plan.ResolvedBlock
import com.treadz.console.programs.plan.ResolvedPlan
import com.treadz.console.programs.plan.StartParameters
import com.treadz.console.programs.standard.StandardProgram
import com.treadz.console.programs.standard.StandardPrograms
import com.treadz.console.treadmill.BeltStatus
import com.treadz.console.treadmill.ConnectionState
import com.treadz.console.treadmill.TreadmillController
import com.treadz.console.treadmill.control.SafetyClamp
import com.treadz.console.userdata.ProgramSaveResult
import com.treadz.console.userdata.UserData
import com.treadz.console.userdata.UserProfile
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
import kotlin.math.roundToInt

/** Why the Start button of a program is disabled. */
enum class StartBlocker {
    NoUser,
    NotConnected,

    /** The belt is starting, the safety key is out, or it is still slowing down. */
    BeltNotReady,

    /** Another program or a fitness test is on. */
    SessionActive,

    /** A reference value is not valid (see the field errors). */
    InvalidValues,
}

/** What is wrong with a reference value, next to its field. */
enum class ParameterProblem { Blank, NotANumber, OutOfRange, EasyAboveMax }

data class DurationOptions(val minSeconds: Int, val maxSeconds: Int, val stepSeconds: Int)

/** The values of the start form. The reference values are text, as typed. */
data class StartForm(
    /** Total duration; `null` when the program runs with the durations it was written with. */
    val durationSeconds: Int? = null,
    val easySpeed: String = "",
    val maxSpeed: String = "",
    val maxIncline: String = "",
    val adjustmentPercent: Int = 0,
)

data class ProgramStartUiState(
    val loading: Boolean = true,
    val notFound: Boolean = false,
    val programId: String = "",
    val kind: ProgramKind = ProgramKind.Free,
    /** Key of the standard program (its name and description are translated); `null` for the user's programs. */
    val standardKey: String? = null,
    val name: String = "",
    val description: String? = null,
    /** The reference values this program needs; the others are not asked. */
    val uses: Set<IntensityParameter> = emptySet(),
    val durationOptions: DurationOptions? = null,
    val form: StartForm = StartForm(),
    val problems: Map<IntensityParameter, ParameterProblem> = emptyMap(),
    /** The plan the current values give: what will be run. `null` while a value is not valid. */
    val plan: ResolvedPlan? = null,
    val speedMin: Double = SafetyClamp.DEFAULT_SPEED_RANGE.min,
    val speedMax: Double = SafetyClamp.DEFAULT_SPEED_RANGE.max,
    val inclineMax: Double = SafetyClamp.DEFAULT_INCLINE_RANGE.max,
    val blocker: StartBlocker? = null,
    val starting: Boolean = false,
    /** The last start that did not work. */
    val failure: StartOutcome? = null,
    val savingCopy: Boolean = false,
    val saveCopyFailed: Boolean = false,
    /** The form holds the program's default values (the "restore defaults" action has nothing to do). */
    val atDefaults: Boolean = true,
    /** What generated the program (personalised programs only): it runs exactly as stored, with no adjustments. */
    val origin: ProgramOrigin? = null,
    /** When the personalised program was generated (epoch ms). */
    val generatedAtMillis: Long? = null,
    /** The active user's heart-rate references: the zones of heart-rate blocks are shown in bpm with them. */
    val heartRateProfile: HeartRateProfile? = null,
) {
    val canStart: Boolean get() = blocker == null && plan != null && !starting
}

/**
 * Start screen of a program (standard or free): asks for the duration and the reference values it uses and the global
 * intensity adjustment, shows the plan they give, and starts it through the [TrainingCoordinator].
 */
class ProgramStartViewModel(
    private val programId: String,
    private val userData: UserData,
    private val training: TrainingCoordinator,
    private val controller: TreadmillController,
) : ViewModel() {

    private data class Local(
        val program: Program? = null,
        val standard: StandardProgram? = null,
        val loading: Boolean = true,
        val form: StartForm = StartForm(),
        val starting: Boolean = false,
        val failure: StartOutcome? = null,
        val savingCopy: Boolean = false,
        val saveCopyFailed: Boolean = false,
        val createdAtMillis: Long? = null,
    )

    private val local = MutableStateFlow(Local())

    private val environment = combine(controller.capabilities, controller.limits) { caps, limits ->
        ResolutionEnvironment(
            speedRange = caps?.speed ?: SafetyClamp.DEFAULT_SPEED_RANGE,
            inclineRange = caps?.incline ?: SafetyClamp.DEFAULT_INCLINE_RANGE,
            limits = limits,
        )
    }

    private data class Context(val user: UserProfile?, val connected: Boolean, val belt: BeltStatus, val speed: Double, val session: SessionKind)

    private val context = combine(
        userData.profiles.activeUser, controller.connection, controller.state, training.visible,
    ) { user, connection, state, session ->
        Context(user, connection is ConnectionState.Connected, state.status, state.speedKmh, session)
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private val heartRateProfile: Flow<HeartRateProfile?> = userData.profiles.activeUser.flatMapLatest { user ->
        if (user == null) {
            flowOf(null)
        } else {
            combine(userData.fitnessTests.heartRateMax(user.id), userData.restingHeartRates.reference(user.id)) { max, resting ->
                max?.let { HeartRateProfile(it.bpm, resting?.bpm) }
            }
        }
    }

    val uiState: StateFlow<ProgramStartUiState> = combine(local, environment, context, heartRateProfile) { local, env, ctx, hr -> build(local, env, ctx, hr) }
        .stateIn(viewModelScope, SharingStarted.Eagerly, ProgramStartUiState(programId = programId))

    init {
        viewModelScope.launch { load() }
    }

    private suspend fun load() {
        val standard = StandardPrograms.byId(programId)
        val stored = if (standard == null) userData.programs.get(programId) else null
        val program = standard?.program ?: stored?.program
        if (program == null) {
            local.update { it.copy(loading = false) }
            return
        }
        val userId = userData.profiles.activeUserId()
        val last = userId?.let { userData.startParameters.lastUsed(it, programId).first() }
        local.update {
            it.copy(program = program, standard = standard, loading = false, form = initialForm(program, standard, last), createdAtMillis = stored?.createdAtMillis)
        }
    }

    private fun build(local: Local, env: ResolutionEnvironment, ctx: Context, heartRateProfile: HeartRateProfile?): ProgramStartUiState {
        val program = local.program
        if (program == null) return ProgramStartUiState(loading = local.loading, notFound = !local.loading, programId = programId)
        val uses = local.standard?.uses ?: ProgramResolver.requiredParameters(program)
        // A generated program runs as it was generated: the global adjustment does not apply to it.
        val generated = program.kind == ProgramKind.Personalised
        val form = if (generated) local.form.copy(adjustmentPercent = 0) else local.form
        val (parameters, problems) = parametersOf(form, uses, env, local.standard != null)
        val plan = parameters?.let { resolve(program, it, env) }
        val belt = ctx.belt
        val beltReady = when (belt) {
            BeltStatus.Running, BeltStatus.Paused -> true
            BeltStatus.Idle -> ctx.speed <= ManualControls.STOPPED_SPEED_KMH
            BeltStatus.Starting, BeltStatus.SafetyKeyRemoved -> false
        }
        val blocker = when {
            ctx.user == null -> StartBlocker.NoUser
            !ctx.connected -> StartBlocker.NotConnected
            ctx.session != SessionKind.None -> StartBlocker.SessionActive
            !beltReady -> StartBlocker.BeltNotReady
            plan == null -> StartBlocker.InvalidValues
            else -> null
        }
        val std = local.standard
        return ProgramStartUiState(
            loading = false,
            programId = programId,
            kind = program.kind,
            standardKey = std?.key,
            name = program.name,
            description = program.description,
            uses = uses,
            durationOptions = std?.let { DurationOptions(it.minTotalDurationSeconds, it.maxTotalDurationSeconds, it.durationStepSeconds) },
            form = form,
            problems = problems,
            plan = plan,
            speedMin = env.speedRange.min,
            speedMax = env.speedRange.max,
            inclineMax = env.inclineRange.max,
            blocker = blocker,
            starting = local.starting,
            failure = local.failure,
            savingCopy = local.savingCopy,
            saveCopyFailed = local.saveCopyFailed,
            atDefaults = form == initialForm(program, std, last = null),
            origin = program.origin,
            generatedAtMillis = local.createdAtMillis,
            heartRateProfile = heartRateProfile,
        )
    }

    private fun resolve(program: Program, parameters: StartParameters, env: ResolutionEnvironment): ResolvedPlan? =
        try {
            ProgramResolver.resolve(program, parameters, env)
        } catch (_: InvalidProgramException) {
            null
        }

    // ------------------------------------------------------------------ the form

    fun setDuration(seconds: Int) = edit { form ->
        val options = uiState.value.durationOptions
        form.copy(durationSeconds = if (options == null) seconds else seconds.coerceIn(options.minSeconds, options.maxSeconds))
    }

    fun stepDuration(direction: Int) {
        val options = uiState.value.durationOptions ?: return
        val current = local.value.form.durationSeconds ?: return
        setDuration((current + direction * options.stepSeconds).coerceIn(options.minSeconds, options.maxSeconds))
    }

    fun setEasySpeed(text: String) = edit { it.copy(easySpeed = text) }
    fun setMaxSpeed(text: String) = edit { it.copy(maxSpeed = text) }
    fun setMaxIncline(text: String) = edit { it.copy(maxIncline = text) }

    fun stepEasySpeed(direction: Int) = edit { it.copy(easySpeed = stepped(it.easySpeed, direction * SPEED_STEP)) }
    fun stepMaxSpeed(direction: Int) = edit { it.copy(maxSpeed = stepped(it.maxSpeed, direction * SPEED_STEP)) }
    fun stepMaxIncline(direction: Int) = edit { it.copy(maxIncline = stepped(it.maxIncline, direction * INCLINE_STEP, decimals = 0)) }

    fun setAdjustment(percent: Int) = edit {
        it.copy(adjustmentPercent = percent.coerceIn(MIN_ADJUSTMENT_PERCENT, MAX_ADJUSTMENT_PERCENT))
    }

    fun stepAdjustment(direction: Int) = setAdjustment(local.value.form.adjustmentPercent + direction * ADJUSTMENT_STEP)

    /** Puts back the program's default values, ignoring what was used last time (that only changes on start). */
    fun restoreDefaults() = local.update { l ->
        val program = l.program ?: return@update l
        l.copy(form = initialForm(program, l.standard, last = null), failure = null)
    }

    private fun edit(change: (StartForm) -> StartForm) = local.update { it.copy(form = change(it.form), failure = null) }

    private fun stepped(text: String, delta: Double, decimals: Int = 1): String {
        val current = Decimals.parse(text) ?: return text
        val value = ((current + delta) * 10).roundToInt() / 10.0
        return if (decimals == 0) value.roundToInt().toString() else Decimals.format(value).replace(',', '.')
    }

    // ------------------------------------------------------------------ actions

    /** Starts the program; [onStarted] is called once the belt is running and the program clock has started. */
    fun start(onStarted: () -> Unit) {
        val state = uiState.value
        val plan = state.plan
        val user = cachedUser ?: return
        if (!state.canStart || plan == null) return
        local.update { it.copy(starting = true, failure = null) }
        viewModelScope.launch {
            val outcome = training.startProgram(user.id, plan, plan.parameters)
            local.update { it.copy(starting = false, failure = outcome.takeIf { o -> o != StartOutcome.Started }) }
            if (outcome == StartOutcome.Started) onStarted()
        }
    }

    /** The active user, kept up to date (the actions need the id). */
    @Volatile
    private var cachedUser: UserProfile? = null

    init {
        viewModelScope.launch { userData.profiles.activeUser.collect { cachedUser = it } }
    }

    /**
     * Saves the plan the current values give as a free program of the active user, with absolute values; [onSaved]
     * gets its id (the editor can then open it).
     */
    fun saveAsFreeProgram(name: String, onSaved: (String) -> Unit) {
        val plan = uiState.value.plan ?: return
        val user = cachedUser ?: return
        local.update { it.copy(savingCopy = true, saveCopyFailed = false) }
        viewModelScope.launch {
            val program = plan.toFreeProgram(name.take(ProgramLimits.MAX_NAME_LENGTH))
            val result = userData.programs.save(user.id, program)
            local.update { it.copy(savingCopy = false, saveCopyFailed = result !is ProgramSaveResult.Saved) }
            if (result is ProgramSaveResult.Saved) onSaved(result.program.id)
        }
    }

    // ------------------------------------------------------------------ pure parts

    companion object {
        const val SPEED_STEP = 0.5
        const val INCLINE_STEP = 1.0
        const val ADJUSTMENT_STEP = 5
        const val MIN_ADJUSTMENT_PERCENT = -30
        const val MAX_ADJUSTMENT_PERCENT = 30

        /**
         * The values the form starts with: what the user used last time, where it still fits, and the program's
         * defaults for everything else. The duration is brought into the catalogue's range and step.
         */
        fun initialForm(program: Program, standard: StandardProgram?, last: StartParameters?): StartForm {
            val defaults = standard?.defaultParameters ?: StartParameters()
            val duration = standard?.let { std ->
                val wanted = last?.totalDurationSeconds ?: std.defaultTotalDurationSeconds
                val steps = ((wanted - std.minTotalDurationSeconds).toDouble() / std.durationStepSeconds).roundToInt()
                (std.minTotalDurationSeconds + steps * std.durationStepSeconds).coerceIn(std.minTotalDurationSeconds, std.maxTotalDurationSeconds)
            }
            fun speedText(v: Double?) = v?.let { Decimals.format(it).replace(',', '.') }.orEmpty()
            fun inclineText(v: Double?) = v?.roundToInt()?.toString().orEmpty()
            val adjustment = (last?.intensityAdjustmentPercent ?: 0).let {
                (it.toDouble() / ADJUSTMENT_STEP).roundToInt() * ADJUSTMENT_STEP
            }.coerceIn(MIN_ADJUSTMENT_PERCENT, MAX_ADJUSTMENT_PERCENT)
            return StartForm(
                durationSeconds = duration,
                easySpeed = speedText(last?.easySpeedKmh ?: defaults.easySpeedKmh),
                maxSpeed = speedText(last?.maxSpeedKmh ?: defaults.maxSpeedKmh),
                maxIncline = inclineText(last?.maxInclinePercent ?: defaults.maxInclinePercent),
                adjustmentPercent = adjustment,
            )
        }

        /** The start parameters the form gives, or the problems of the reference values it asks for. */
        fun parametersOf(
            form: StartForm,
            uses: Set<IntensityParameter>,
            env: ResolutionEnvironment,
            scalable: Boolean,
        ): Pair<StartParameters?, Map<IntensityParameter, ParameterProblem>> {
            val problems = mutableMapOf<IntensityParameter, ParameterProblem>()
            fun read(text: String, parameter: IntensityParameter, min: Double, max: Double): Double? {
                if (parameter !in uses) return null
                if (text.isBlank()) return null.also { problems[parameter] = ParameterProblem.Blank }
                val value = Decimals.parse(text) ?: return null.also { problems[parameter] = ParameterProblem.NotANumber }
                if (value < min - 1e-9 || value > max + 1e-9) problems[parameter] = ParameterProblem.OutOfRange
                return value
            }
            val easy = read(form.easySpeed, IntensityParameter.EasySpeed, env.speedRange.min, env.speedRange.max)
            val max = read(form.maxSpeed, IntensityParameter.MaxSpeed, env.speedRange.min, env.speedRange.max)
            val incline = read(form.maxIncline, IntensityParameter.MaxIncline, env.inclineRange.min, env.inclineRange.max)
            if (easy != null && max != null && easy > max + 1e-9 && IntensityParameter.EasySpeed !in problems) {
                problems[IntensityParameter.EasySpeed] = ParameterProblem.EasyAboveMax
            }
            if (problems.isNotEmpty()) return null to problems
            return StartParameters(
                totalDurationSeconds = form.durationSeconds.takeIf { scalable },
                easySpeedKmh = easy,
                maxSpeedKmh = max,
                maxInclinePercent = incline,
                intensityAdjustmentPercent = form.adjustmentPercent,
            ) to problems
        }
    }
}

/**
 * The plan as a free program in absolute values. A repeat whose repetitions came out identical stays a repeat
 * (the scaled durations can differ by a second between repetitions, then the blocks are written out).
 */
fun ResolvedPlan.toFreeProgram(name: String): Program {
    fun block(b: ResolvedBlock) = Block(b.phase, b.end, BlockTarget.Absolute(b.speedKmh, b.inclinePercent), b.name)
    fun same(a: ResolvedBlock, b: ResolvedBlock) =
        a.phase == b.phase && a.name == b.name && a.end == b.end && a.speedKmh == b.speedKmh && a.inclinePercent == b.inclinePercent
    val steps = mutableListOf<com.treadz.console.programs.model.ProgramStep>()
    blocks.groupBy { it.stepIndex }.toSortedMap().forEach { (_, group) ->
        val repetition = group.first().repetition
        if (repetition == null) {
            group.forEach { steps += block(it) }
        } else {
            val perIteration = group.groupBy { it.repetition!!.iteration }.toSortedMap().values.toList()
            val first = perIteration.first()
            val identical = perIteration.all { it.size == first.size && it.indices.all { i -> same(it[i], first[i]) } }
            if (identical) steps += Repeat(repetition.count, first.map(::block)) else group.forEach { steps += block(it) }
        }
    }
    return Program(id = "", name = name, kind = ProgramKind.Free, steps = steps)
}
