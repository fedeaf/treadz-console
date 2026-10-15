package com.treadz.console.training

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.treadz.console.Decimals
import com.treadz.console.controls.ManualControls
import com.treadz.console.controls.SessionKind
import com.treadz.console.programs.fitness.EbbelingParameters
import com.treadz.console.programs.fitness.FitnessSubject
import com.treadz.console.programs.fitness.FitnessTestConfig
import com.treadz.console.programs.fitness.FitnessTestResult
import com.treadz.console.programs.fitness.FitnessTestType
import com.treadz.console.programs.fitness.HeartRateMaxDecision
import com.treadz.console.programs.fitness.HeartRateSourceKind
import com.treadz.console.programs.fitness.MaxHeartRateWalkParameters
import com.treadz.console.programs.fitness.MaxTestParameters
import com.treadz.console.programs.fitness.Sex as FitnessSex
import com.treadz.console.programs.heartrate.HeartRateSource
import com.treadz.console.treadmill.BeltStatus
import com.treadz.console.treadmill.ConnectionState
import com.treadz.console.treadmill.TreadmillController
import com.treadz.console.userdata.Sex
import com.treadz.console.userdata.UserData
import com.treadz.console.userdata.UserProfile
import com.treadz.console.userdata.WeightEntry
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.onStart
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import java.time.LocalDate
import kotlin.math.roundToInt

/** Why the test cannot be started now. */
enum class TestBlocker {
    NoUser,
    NotConnected,

    /** The belt is moving, starting or paused: the test needs it at rest. */
    BeltNotAtRest,

    /** Another program or test is on. */
    SessionActive,

    /** The test needs a heart rate: put the chest strap on. */
    NoHeartRate,

    /** The speed typed is not a number or outside the allowed range. */
    InvalidSpeed,

    /** The incline typed (walking heart-rate test) is not a number or outside the allowed range. */
    InvalidIncline,
}

data class FitnessStartUiState(
    val type: FitnessTestType,
    val loading: Boolean = true,
    val user: UserProfile? = null,
    val ageYears: Int = 0,
    val weight: WeightEntry? = null,
    val heartRateMax: HeartRateMaxDecision? = null,
    /** Start speed as typed. */
    val speedText: String = "",
    val speedMin: Double = 0.0,
    val speedMax: Double = 0.0,
    val speedInvalid: Boolean = false,
    /** Walking heart-rate test: the incline of the first ramp stage, as typed. */
    val inclineText: String = "",
    val inclineMin: Double = 0.0,
    val inclineMax: Double = 0.0,
    val inclineInvalid: Boolean = false,
    /** Walking heart-rate test: the two warm-up steps derived from the ramp start speed (km/h), `null` while it is not valid. */
    val warmUpSpeeds: Pair<Double, Double>? = null,
    /** The start values are the suggested ones (the "restore defaults" action has nothing to do). */
    val atDefaults: Boolean = true,
    /** Heart rate the app receives now (`null`: none). */
    val heartRateBpm: Int? = null,
    /** Where the heart rate comes from now (`null`: no source). */
    val heartRateSource: HeartRateSourceKind? = null,
    val blocker: TestBlocker? = null,
    val starting: Boolean = false,
    val failure: StartOutcome? = null,
) {
    val canStart: Boolean get() = blocker == null && !starting
}

/** Warning, subject and start values of a fitness test, and its start through the [TrainingCoordinator]. */
class FitnessStartViewModel(
    private val type: FitnessTestType,
    private val userData: UserData,
    private val training: TrainingCoordinator,
    private val controller: TreadmillController,
    private val today: () -> LocalDate,
    private val config: FitnessTestConfig = FitnessTestConfig(),
    /** The app's heart-rate source (strap or console); without one the heart rate is the console's. */
    private val heartRate: HeartRateSource? = null,
) : ViewModel() {

    private val ebbeling = type == FitnessTestType.EbbelingWalk
    private val walk = type == FitnessTestType.MaxHeartRateWalk
    private val speedRange = when (type) {
        FitnessTestType.EbbelingWalk -> config.ebbelingMinSpeedKmh..config.ebbelingMaxSpeedKmh
        FitnessTestType.MaxIncrementalRun -> config.maxTestMinStartSpeedKmh..config.maxTestMaxStartSpeedKmh
        FitnessTestType.MaxHeartRateWalk -> config.walkTestMinStartSpeedKmh..config.walkTestMaxStartSpeedKmh
    }
    private val inclineRange = 0.0..config.walkTestMaxStartInclinePercent
    private val defaultSpeed = when (type) {
        FitnessTestType.EbbelingWalk -> config.ebbelingDefaultStartSpeedKmh
        FitnessTestType.MaxIncrementalRun -> config.maxTestDefaultStartSpeedKmh
        FitnessTestType.MaxHeartRateWalk -> config.walkTestDefaultSpeedKmh
    }
    private val defaultIncline = config.walkTestDefaultInclinePercent

    private data class Local(
        val speedText: String = "",
        val inclineText: String = "",
        /** The start values suggested from the user's last test (the default); empty until the results are known. */
        val suggestedSpeedText: String = "",
        val suggestedInclineText: String = "",
        val suggested: Boolean = false,
        val starting: Boolean = false,
        val failure: StartOutcome? = null,
    )

    private val local = MutableStateFlow(Local())

    private data class Person(val user: UserProfile?, val weight: WeightEntry?, val hrMax: HeartRateMaxDecision?, val results: List<FitnessTestResult>)

    @OptIn(ExperimentalCoroutinesApi::class)
    private val person: Flow<Person> = userData.profiles.activeUser.flatMapLatest { user ->
        if (user == null) {
            flowOf(Person(null, null, null, emptyList()))
        } else {
            combine(
                userData.weights.latestWeight(user.id),
                userData.fitnessTests.heartRateMax(user.id),
                userData.fitnessTests.results(user.id),
            ) { weight, hrMax, results -> Person(user, weight, hrMax, results.map { it.result }) }
        }
    }

    private data class Belt(
        val connected: Boolean,
        val status: BeltStatus,
        val speed: Double,
        val heartRate: Int?,
        val source: HeartRateSourceKind?,
        val session: SessionKind,
    )

    /** The heart rate the test would use: the app's source when there is one, else the console's. */
    private val heartRateNow: Flow<Pair<Int?, HeartRateSourceKind?>> = combine(
        controller.state,
        heartRate?.readings?.onStart { emit(heartRate.latest()) } ?: flowOf(null),
    ) { state, _ ->
        if (heartRate != null) {
            val r = heartRate.latest()
            val bpm = r.bpm?.takeIf { it > 0 }
            bpm to (if (bpm != null) r.source else null)
        } else {
            val bpm = state.heartRateBpm?.takeIf { it > 0 && !state.isStale }
            bpm to (if (bpm != null) HeartRateSourceKind.TreadmillConsole else null)
        }
    }

    private val belt = combine(controller.connection, controller.state, training.visible, heartRateNow) { connection, state, session, hr ->
        Belt(connection is ConnectionState.Connected, state.status, state.speedKmh, hr.first, hr.second, session)
    }

    val uiState: StateFlow<FitnessStartUiState> = combine(person, belt, local) { person, belt, local ->
        val user = person.user
        val speed = Decimals.parse(local.speedText)
        val speedInvalid = speed == null || speed < speedRange.start - 1e-9 || speed > speedRange.endInclusive + 1e-9
        val incline = Decimals.parse(local.inclineText)
        val inclineInvalid = walk && (incline == null || incline < inclineRange.start - 1e-9 || incline > inclineRange.endInclusive + 1e-9 || incline != incline.roundToInt().toDouble())
        val atRest = belt.status == BeltStatus.Idle && belt.speed <= ManualControls.STOPPED_SPEED_KMH
        val blocker = when {
            user == null -> TestBlocker.NoUser
            !belt.connected -> TestBlocker.NotConnected
            belt.session != SessionKind.None -> TestBlocker.SessionActive
            !atRest -> TestBlocker.BeltNotAtRest
            (ebbeling || walk) && belt.heartRate == null -> TestBlocker.NoHeartRate
            speedInvalid -> TestBlocker.InvalidSpeed
            inclineInvalid -> TestBlocker.InvalidIncline
            else -> null
        }
        FitnessStartUiState(
            type = type,
            loading = user == null && !local.suggested,
            user = user,
            ageYears = user?.ageOn(today()) ?: 0,
            weight = person.weight,
            heartRateMax = person.hrMax,
            speedText = local.speedText,
            speedMin = speedRange.start,
            speedMax = speedRange.endInclusive,
            speedInvalid = speedInvalid,
            inclineText = local.inclineText,
            inclineMin = inclineRange.start,
            inclineMax = inclineRange.endInclusive,
            inclineInvalid = inclineInvalid,
            warmUpSpeeds = if (walk && !speedInvalid && speed != null) warmUpSpeeds(speed) else null,
            atDefaults = !local.suggested || (local.speedText == local.suggestedSpeedText && (!walk || local.inclineText == local.suggestedInclineText)),
            heartRateBpm = belt.heartRate,
            heartRateSource = belt.source,
            blocker = blocker,
            starting = local.starting,
            failure = local.failure,
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, FitnessStartUiState(type))

    init {
        // The suggested start values come from the user's last test of this type, once the results are known.
        viewModelScope.launch {
            person.collect { p ->
                if (!local.value.suggested && p.user != null) {
                    val speedSuggestion: Double
                    var inclineSuggestion = defaultIncline
                    when (type) {
                        FitnessTestType.EbbelingWalk -> speedSuggestion = FitnessResults.suggestedEbbelingSpeed(p.results, defaultSpeed)
                        FitnessTestType.MaxIncrementalRun -> speedSuggestion = FitnessResults.suggestedMaxStartSpeed(p.results, defaultSpeed)
                        FitnessTestType.MaxHeartRateWalk -> {
                            val last = FitnessResults.suggestedWalkStart(p.results, defaultSpeed, defaultIncline)
                            speedSuggestion = last.first
                            inclineSuggestion = last.second
                        }
                    }
                    val speed = (speedSuggestion.coerceIn(speedRange.start, speedRange.endInclusive) * 10).roundToInt() / 10.0
                    val speedText = Decimals.format(speed)
                    val inclineText = inclineSuggestion.coerceIn(inclineRange.start, inclineRange.endInclusive).roundToInt().toString()
                    local.update {
                        it.copy(speedText = speedText, suggestedSpeedText = speedText, inclineText = inclineText, suggestedInclineText = inclineText, suggested = true)
                    }
                }
            }
        }
    }

    /** The two warm-up steps of the walking test: 75 % then 80 % of the ramp speed, rounded to 0.1 km/h. */
    private fun warmUpSpeeds(rampSpeed: Double): Pair<Double, Double> =
        round1(rampSpeed * config.walkTestWarmUpFraction1) to round1(rampSpeed * config.walkTestWarmUpFraction2)

    private fun round1(v: Double) = (v * 10).roundToInt() / 10.0

    fun setSpeed(text: String) = local.update { it.copy(speedText = text, failure = null) }

    fun setIncline(text: String) = local.update { it.copy(inclineText = text, failure = null) }

    /** Puts back the suggested start values. */
    fun restoreDefaults() = local.update {
        if (it.suggested) it.copy(speedText = it.suggestedSpeedText, inclineText = it.suggestedInclineText, failure = null) else it
    }

    fun stepSpeed(direction: Int) {
        val current = Decimals.parse(local.value.speedText) ?: defaultSpeed
        val value = (((current + direction * SPEED_STEP) * 10).roundToInt() / 10.0).coerceIn(speedRange.start, speedRange.endInclusive)
        setSpeed(Decimals.format(value))
    }

    fun stepIncline(direction: Int) {
        val current = Decimals.parse(local.value.inclineText) ?: defaultIncline
        setIncline((current.roundToInt() + direction).coerceIn(inclineRange.start.toInt(), inclineRange.endInclusive.toInt()).toString())
    }

    /** Starts the test; [onStarted] is called once the belt runs and the test clock has started. */
    fun start(onStarted: () -> Unit) {
        val state = uiState.value
        val user = state.user ?: return
        val speed = Decimals.parse(state.speedText) ?: return
        if (!state.canStart) return
        val hrMax = state.heartRateMax?.bpm ?: return
        val subject = FitnessSubject(
            ageYears = state.ageYears,
            sex = if (user.sex == Sex.Male) FitnessSex.Male else FitnessSex.Female,
            // The walking heart-rate test measures no VO2max: without a weight it gets 0 (unknown), which it accepts.
            weightKg = state.weight?.weightKg ?: 0.0,
            hrMaxBpm = hrMax,
        )
        val incline = if (walk) Decimals.parse(state.inclineText) ?: return else 0.0
        local.update { it.copy(starting = true, failure = null) }
        viewModelScope.launch {
            val outcome = when (type) {
                FitnessTestType.EbbelingWalk -> training.startEbbeling(user.id, EbbelingParameters(subject, speed))
                FitnessTestType.MaxIncrementalRun -> training.startMaxTest(user.id, MaxTestParameters(subject, speed))
                FitnessTestType.MaxHeartRateWalk -> training.startMaxHeartRateWalk(user.id, MaxHeartRateWalkParameters(subject, speed, incline))
            }
            local.update { it.copy(starting = false, failure = outcome.takeIf { o -> o != StartOutcome.Started }) }
            if (outcome == StartOutcome.Started) onStarted()
        }
    }

    private companion object {
        const val SPEED_STEP = 0.1
    }
}
