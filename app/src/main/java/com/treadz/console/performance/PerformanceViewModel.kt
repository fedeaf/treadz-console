package com.treadz.console.performance

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.treadz.console.analysis.trends.ThresholdRecommendation
import com.treadz.console.analysis.zones.ZoneSet
import com.treadz.console.programs.fitness.FitnessProfile
import com.treadz.console.training.UserZones
import com.treadz.console.userdata.UserData
import com.treadz.console.userdata.UserProfile
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.withContext
import java.time.LocalDate

/** The cards of the «Performance» screen, in the order they are listed. */
enum class PerformanceCard { Vo2max, Thresholds, FitnessIndex, Load, Recovery, Resting, WeightVo2 }

data class PerformanceUiState(
    /** The first load has finished (before it nothing is shown, not even the empty lines). */
    val loaded: Boolean = false,
    val user: UserProfile? = null,
    /** The fitness profile as of today: the VO2max and the thresholds the cards headline. */
    val profile: FitnessProfile? = null,
    /** The five zones in bpm and their source. */
    val zones: ZoneSet? = null,
    val trends: PerformanceTrends? = null,
    /** Tests the workouts suggest (VT1 and/or VT2 look different from the profile); empty for none. */
    val recommendations: List<ThresholdRecommendation> = emptyList(),
    /** The cards whose chart is open. */
    val expanded: Set<PerformanceCard> = emptySet(),
)

/**
 * The «Performance» screen of the active user: the trends of `:analysis` over the stored workout analyses and the other
 * inputs ([com.treadz.console.userdata.WorkoutAnalysisRepository]).
 *
 * The trends are computed on [computeDispatcher] (the default dispatcher in the app) each time any input changes,
 * dropping the computation in progress when a newer input arrives. The analyses themselves are kept up to date by the
 * data layer, so the screen only observes: a workout that ends, a pull of the synchronisation or a new test changes the
 * flows and the screen follows. "Today" is read when an input changes, not as time passes.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class PerformanceViewModel(
    private val userData: UserData,
    private val today: () -> LocalDate = LocalDate::now,
    private val computeDispatcher: CoroutineDispatcher = Dispatchers.Default,
) : ViewModel() {

    private val expanded = MutableStateFlow<Set<PerformanceCard>>(emptySet())

    private data class Content(
        val user: UserProfile?,
        val profile: FitnessProfile?,
        val zones: ZoneSet?,
        val trends: PerformanceTrends?,
        val recommendations: List<ThresholdRecommendation> = emptyList(),
    )

    private val content: Flow<Content> = userData.profiles.activeUser.flatMapLatest { user ->
        if (user == null) {
            flowOf(Content(null, null, null, null))
        } else {
            val analyses = userData.workoutAnalysis
            combine(
                analyses.analyses(user.id),
                analyses.performanceInputs(user.id),
                userData.fitnessTests.liveFitnessProfile(user.id),
                userData.fitnessTests.heartRateMax(user.id),
                userData.restingHeartRates.reference(user.id),
            ) { workouts, inputs, profile, hrMax, resting ->
                Inputs(user, workouts, inputs, profile, UserZones.compute(user, profile, hrMax, resting?.bpm, today()), today().toEpochDay())
            }.mapLatest { i ->
                withContext(computeDispatcher) {
                    Content(
                        i.user, i.profile, i.zones, PerformanceTrendsCalculator.compute(i.workouts, i.inputs, i.todayEpochDay),
                        TestRecommendations.compute(i.workouts, i.inputs.tests, i.profile, i.todayEpochDay),
                    )
                }
            }
        }
    }

    private class Inputs(
        val user: UserProfile,
        val workouts: List<com.treadz.console.userdata.DatedWorkoutAnalysis>,
        val inputs: com.treadz.console.userdata.PerformanceInputs,
        val profile: FitnessProfile?,
        val zones: ZoneSet,
        val todayEpochDay: Long,
    )

    val uiState: StateFlow<PerformanceUiState> = combine(content, expanded) { c, open ->
        PerformanceUiState(true, c.user, c.profile, c.zones, c.trends, c.recommendations, open)
    }.stateIn(viewModelScope, SharingStarted.Eagerly, PerformanceUiState())

    /** Opens the chart of [card], or closes it when it is open. */
    fun toggle(card: PerformanceCard) = expanded.update { if (card in it) it - card else it + card }
}
