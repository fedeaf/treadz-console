package com.treadz.console.training

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.treadz.console.programs.model.Program
import com.treadz.console.programs.model.ProgramKind
import com.treadz.console.programs.model.ProgramLimits
import com.treadz.console.programs.plan.ProgramResolver
import com.treadz.console.programs.plan.StartParameters
import com.treadz.console.programs.standard.StandardPrograms
import com.treadz.console.userdata.DataResult
import com.treadz.console.userdata.ProgramSaveResult
import com.treadz.console.userdata.UserData
import com.treadz.console.userdata.UserProfile
import com.treadz.console.userdata.UserProgram
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

/** What the list shows of a program. */
data class ProgramCardState(
    val id: String,
    val kind: ProgramKind,
    /** Key of the standard program (`hill`...), whose name and description are translated; `null` for the user's programs. */
    val standardKey: String?,
    /** The user's name for a free program (standard programs use the translated name). */
    val name: String,
    val description: String?,
    val segments: List<PlanSegment>,
    /** Duration at the default (standard) or as written (free), seconds. */
    val durationSeconds: Int,
    val minDurationSeconds: Int?,
    val maxDurationSeconds: Int?,
    val distanceMeters: Int,
    /** The program has blocks this app cannot edit yet (heart rate or relative targets). */
    val editable: Boolean = true,
)

data class ProgramsUiState(
    val loaded: Boolean = false,
    val user: UserProfile? = null,
    val standard: List<ProgramCardState> = emptyList(),
    val mine: List<ProgramCardState> = emptyList(),
    val confirmingDeleteId: String? = null,
    val failed: Boolean = false,
)

/** The standard programs and the active user's free programs. */
class ProgramsViewModel(
    private val userData: UserData,
    /** Name of the copy of a program ("Intervals (copy)"). */
    private val copyName: (String) -> String = { "$it (copy)" },
) : ViewModel() {

    private data class Local(val confirmingDeleteId: String? = null, val failed: Boolean = false)

    private val local = MutableStateFlow(Local())

    private val standardCards: List<ProgramCardState> = StandardPrograms.all.map { std ->
        val plan = runCatching { ProgramResolver.resolve(std.program, std.defaultParameters) }.getOrNull()
        ProgramCardState(
            id = std.id, kind = ProgramKind.Standard, standardKey = std.key, name = std.program.name,
            description = std.program.description,
            segments = plan?.segments().orEmpty(),
            durationSeconds = std.defaultTotalDurationSeconds,
            minDurationSeconds = std.minTotalDurationSeconds, maxDurationSeconds = std.maxTotalDurationSeconds,
            distanceMeters = plan?.estimatedDistanceMeters ?: 0,
            editable = false,
        )
    }

    @OptIn(ExperimentalCoroutinesApi::class)
    private val mine: Flow<Pair<UserProfile?, List<UserProgram>>> = userData.profiles.activeUser.flatMapLatest { user ->
        if (user == null) flowOf(null to emptyList()) else userData.programs.programs(user.id).map { user to it }
    }

    val uiState: StateFlow<ProgramsUiState> = combine(mine, local) { (user, programs), local ->
        ProgramsUiState(
            loaded = true,
            user = user,
            standard = standardCards,
            mine = programs.map(::cardOf),
            confirmingDeleteId = local.confirmingDeleteId,
            failed = local.failed,
        )
    }.stateIn(viewModelScope, SharingStarted.Eagerly, ProgramsUiState(standard = standardCards))

    private fun cardOf(p: UserProgram): ProgramCardState {
        val plan = runCatching { ProgramResolver.resolve(p.program, previewParameters(p.program)) }.getOrNull()
        return ProgramCardState(
            id = p.id, kind = p.program.kind, standardKey = null, name = p.program.name, description = p.program.description,
            segments = plan?.segments().orEmpty(),
            durationSeconds = plan?.estimatedDurationSeconds ?: 0,
            minDurationSeconds = null, maxDurationSeconds = null,
            distanceMeters = plan?.estimatedDistanceMeters ?: 0,
            editable = ProgramDrafts.isEditable(p.program),
        )
    }

    fun requestDelete(programId: String) = local.update { it.copy(confirmingDeleteId = programId) }

    fun cancelDelete() = local.update { it.copy(confirmingDeleteId = null) }

    fun confirmDelete() {
        val id = local.value.confirmingDeleteId ?: return
        local.update { it.copy(confirmingDeleteId = null) }
        viewModelScope.launch {
            if (userData.programs.delete(id) is DataResult.StorageError) local.update { it.copy(failed = true) }
        }
    }

    /** Saves a copy of the program under a new name; [onDuplicated] gets the id of the copy. */
    fun duplicate(programId: String, onDuplicated: (String) -> Unit = {}) {
        val user = uiState.value.user ?: return
        viewModelScope.launch {
            val original = userData.programs.get(programId)?.program ?: return@launch
            val name = copyName(original.name).take(ProgramLimits.MAX_NAME_LENGTH)
            when (val result = userData.programs.save(user.id, original.copy(id = "", name = name))) {
                is ProgramSaveResult.Saved -> onDuplicated(result.program.id)
                else -> local.update { it.copy(failed = true) }
            }
        }
    }

    companion object {
        /** Values for drawing a program whose targets are relative: only the shape matters. */
        fun previewParameters(program: Program): StartParameters = StartParameters(easySpeedKmh = 4.0, maxSpeedKmh = 10.0, maxInclinePercent = 10.0)
    }
}
