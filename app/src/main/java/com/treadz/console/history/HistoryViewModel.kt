package com.treadz.console.history

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.treadz.console.sync.service.SyncService
import com.treadz.console.sync.service.SyncSetup
import com.treadz.console.userdata.DataResult
import com.treadz.console.userdata.UserData
import com.treadz.console.userdata.UserProfile
import com.treadz.console.userdata.WorkoutSummary
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

enum class HistoryTab { Mine, Unassigned }

data class HistoryUiState(
    val loaded: Boolean = false,
    val user: UserProfile? = null,
    /** The active user's workouts, newest first. */
    val workouts: List<WorkoutSummary> = emptyList(),
    /** Workouts recorded with nobody active, newest first. */
    val unassigned: List<WorkoutSummary> = emptyList(),
    /** Everybody, to choose who an unassigned workout belongs to. */
    val users: List<UserProfile> = emptyList(),
    val tab: HistoryTab = HistoryTab.Mine,
    val confirmingDeleteId: Long? = null,
    val assigningId: Long? = null,
    val failed: Boolean = false,
    /** Workouts whose latest state is not uploaded yet. Empty unless this device is linked to a GitHub repository. */
    val pendingUploadIds: Set<Long> = emptySet(),
    /** The Couch to 5K session (1-27) of each of the active user's workouts that was one, by workout id. */
    val couchSessions: Map<Long, Int> = emptyMap(),
) {
    /** The tab to show: the unassigned one only exists while there is something in it. */
    val visibleTab: HistoryTab get() = if (unassigned.isEmpty()) HistoryTab.Mine else tab
}

/** Workout list of the active user, plus the workouts nobody owns. */
class HistoryViewModel(
    private val userData: UserData,
    /** Only used for the "not uploaded yet" indicator; `null` without synchronisation. */
    sync: SyncService? = null,
) : ViewModel() {

    private data class Local(
        val tab: HistoryTab = HistoryTab.Mine,
        val confirmingDeleteId: Long? = null,
        val assigningId: Long? = null,
        val failed: Boolean = false,
    )

    private val local = MutableStateFlow(Local())

    @OptIn(ExperimentalCoroutinesApi::class)
    private val mine: Flow<Pair<UserProfile?, List<WorkoutSummary>>> = userData.profiles.activeUser.flatMapLatest { user ->
        if (user == null) flowOf(null to emptyList()) else userData.workouts.workouts(user.id).map { user to it }
    }

    /** Ids of the workouts waiting to be uploaded, only while linked (otherwise nothing is "pending": it is all local). */
    @OptIn(ExperimentalCoroutinesApi::class)
    private val pendingUploads: Flow<Set<Long>> = if (sync == null) {
        flowOf(emptySet())
    } else {
        sync.status
            .map { it.setup == SyncSetup.Linked }
            .distinctUntilChanged()
            .flatMapLatest { linked -> if (linked) sync.pendingWorkoutIds else flowOf(emptySet()) }
    }

    /** The Couch to 5K session number of each workout of the active user that was one (it lives in the session record). */
    @OptIn(ExperimentalCoroutinesApi::class)
    private val couchSessions: Flow<Map<Long, Int>> = userData.profiles.activeUser.flatMapLatest { user ->
        if (user == null) flowOf(emptyMap()) else ProgramSessions.couchSessionNumbers(userData, user.id)
    }

    private data class Content(
        val mine: Pair<UserProfile?, List<WorkoutSummary>>,
        val unassigned: List<WorkoutSummary>,
        val users: List<UserProfile>,
        val pendingUploads: Set<Long>,
        val couchSessions: Map<Long, Int>,
    )

    private val content = combine(
        combine(mine, userData.workouts.unassignedWorkouts(), userData.profiles.profiles, pendingUploads) { a, b, c, d -> Quad(a, b, c, d) },
        couchSessions,
    ) { quad, couch -> Content(quad.a, quad.b, quad.c, quad.d, couch) }

    private data class Quad<A, B, C, D>(val a: A, val b: B, val c: C, val d: D)

    val uiState: StateFlow<HistoryUiState> = combine(content, local) { content, local ->
        val (user, workouts) = content.mine
        val unassigned = content.unassigned
        val users = content.users
        HistoryUiState(
            loaded = true,
            user = user,
            workouts = workouts,
            unassigned = unassigned,
            users = users,
            tab = local.tab,
            confirmingDeleteId = local.confirmingDeleteId,
            assigningId = local.assigningId,
            failed = local.failed,
            pendingUploadIds = content.pendingUploads,
            couchSessions = content.couchSessions,
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, HistoryUiState())

    fun selectTab(tab: HistoryTab) = local.update { it.copy(tab = tab) }

    fun requestDelete(workoutId: Long) = local.update { it.copy(confirmingDeleteId = workoutId) }

    fun cancelDelete() = local.update { it.copy(confirmingDeleteId = null) }

    fun confirmDelete() {
        val id = local.value.confirmingDeleteId ?: return
        local.update { it.copy(confirmingDeleteId = null) }
        viewModelScope.launch {
            if (userData.workouts.delete(id) is DataResult.StorageError) local.update { it.copy(failed = true) }
        }
    }

    fun requestAssign(workoutId: Long) = local.update { it.copy(assigningId = workoutId) }

    fun cancelAssign() = local.update { it.copy(assigningId = null) }

    fun assignTo(userId: Long) {
        val id = local.value.assigningId ?: return
        local.update { it.copy(assigningId = null) }
        viewModelScope.launch {
            if (userData.workouts.assign(id, userId) is DataResult.StorageError) local.update { it.copy(failed = true) }
        }
    }
}
