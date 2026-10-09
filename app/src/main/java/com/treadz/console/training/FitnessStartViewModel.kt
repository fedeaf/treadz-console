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
import com.treadz.console.programs.fitness.MaxTestParameters
import com.treadz.console.programs.fitness.Sex as FitnessSex
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

    /** Ebbeling needs a heart rate: put the chest strap on. */
    NoHeartRate,

    /** The speed typed is not a number or outside the allowed range. */
    InvalidSpeed,
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
    /** Heart rate the treadmill reports now (`null`: none). */
    val heartRateBpm: Int? = null,
    val blocker: TestBlocker? = null,
    val starting: Boolean = false,
    val failure: StartOutcome? = null,
) {
    val canStart: Boolean get() = blocker == null && !starting
}

/** Warning, subject and start speed of a fitness test, and its start through the [TrainingCoordinator]. */
class FitnessStartViewModel(
    private val type: FitnessTestType,
    private val userData: UserData,
    private val training: TrainingCoordinator,
    private val controller: TreadmillController,
    private val today: () -> LocalDate,
    private val config: FitnessTestConfig = FitnessTestConfig(),
) : ViewModel() {

    private val ebbeling = type == FitnessTestType.EbbelingWalk
    private val speedRange = if (ebbeling) config.ebbelingMinSpeedKmh..config.ebbelingMaxSpeedKmh else config.maxTestMinStartSpeedKmh..config.maxTestMaxStartSpeedKmh
    private val defaultSpeed = if (ebbeling) config.ebbelingDefaultStartSpeedKmh else config.maxTestDefaultStartSpeedKmh

    private data class Local(
        val speedText: String = "",
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

    private data class Belt(val connected: Boolean, val status: BeltStatus, val speed: Double, val heartRate: Int?, val session: SessionKind)

    private val belt = combine(controller.connection, controller.state, training.visible) { connection, state, session ->
        Belt(connection is ConnectionState.Connected, state.status, state.speedKmh, state.heartRateBpm?.takeIf { it > 0 && !state.isStale }, session)
    }

    val uiState: StateFlow<FitnessStartUiState> = combine(person, belt, local) { person, belt, local ->
        val user = person.user
        val speed = Decimals.parse(local.speedText)
        val speedInvalid = speed == null || speed < speedRange.start - 1e-9 || speed > speedRange.endInclusive + 1e-9
        val atRest = belt.status == BeltStatus.Idle && belt.speed <= ManualControls.STOPPED_SPEED_KMH
        val blocker = when {
            user == null -> TestBlocker.NoUser
            !belt.connected -> TestBlocker.NotConnected
            belt.session != SessionKind.None -> TestBlocker.SessionActive
            !atRest -> TestBlocker.BeltNotAtRest
            ebbeling && belt.heartRate == null -> TestBlocker.NoHeartRate
            speedInvalid -> TestBlocker.InvalidSpeed
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
            heartRateBpm = belt.heartRate,
            blocker = blocker,
            starting = local.starting,
            failure = local.failure,
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, FitnessStartUiState(type))

    init {
        // The suggested start speed comes from the user's last test of this type, once the results are known.
        viewModelScope.launch {
            person.collect { p ->
                if (!local.value.suggested && p.user != null) {
                    val suggestion = if (ebbeling) FitnessResults.suggestedEbbelingSpeed(p.results, defaultSpeed) else FitnessResults.suggestedMaxStartSpeed(p.results, defaultSpeed)
                    val speed = (suggestion.coerceIn(speedRange.start, speedRange.endInclusive) * 10).roundToInt() / 10.0
                    local.update { it.copy(speedText = Decimals.format(speed, java.util.Locale.ROOT), suggested = true) }
                }
            }
        }
    }

    fun setSpeed(text: String) = local.update { it.copy(speedText = text, failure = null) }

    fun stepSpeed(direction: Int) {
        val current = Decimals.parse(local.value.speedText) ?: defaultSpeed
        val value = (((current + direction * SPEED_STEP) * 10).roundToInt() / 10.0).coerceIn(speedRange.start, speedRange.endInclusive)
        setSpeed(Decimals.format(value, java.util.Locale.ROOT))
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
            weightKg = state.weight?.weightKg ?: 0.0,
            hrMaxBpm = hrMax,
        )
        local.update { it.copy(starting = true, failure = null) }
        viewModelScope.launch {
            val outcome = if (ebbeling) {
                training.startEbbeling(user.id, EbbelingParameters(subject, speed))
            } else {
                training.startMaxTest(user.id, MaxTestParameters(subject, speed))
            }
            local.update { it.copy(starting = false, failure = outcome.takeIf { o -> o != StartOutcome.Started }) }
            if (outcome == StartOutcome.Started) onStarted()
        }
    }

    private companion object {
        const val SPEED_STEP = 0.1
    }
}
