package com.treadz.console.training

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.treadz.console.programs.model.Program
import com.treadz.console.programs.model.ProgramKind
import com.treadz.console.programs.model.ProgramLimits
import com.treadz.console.programs.personalised.CouchToFiveK
import com.treadz.console.programs.personalised.CouchToFiveKSelection
import com.treadz.console.programs.personalised.ExerciseMode
import com.treadz.console.programs.personalised.GeneratorDescriptor
import com.treadz.console.programs.personalised.GeneratorInput
import com.treadz.console.programs.personalised.GeneratorNotice
import com.treadz.console.programs.personalised.ProgramGenerators
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
import java.time.LocalDate

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
    /** The program has blocks this app cannot edit (relative targets) or was generated: it can be duplicated as a free one. */
    val editable: Boolean = true,
)

/** A saved personalised session of a generator, as the list shows it. */
data class SavedSessionState(
    val programId: String,
    val name: String,
    /** When it was generated (epoch ms). */
    val createdAtMillis: Long,
    val mode: ExerciseMode?,
    /** Its main input: the duration (zone 2, tempo)... */
    val durationMinutes: Int?,
    /** ... or the number of series (30/30). */
    val series: Int?,
)

/** A generator of personalised programs with the sessions saved from it (none for Couch to 5K). */
data class GeneratorCardState(
    val descriptor: GeneratorDescriptor,
    /** Every saved session, newest first. */
    val sessions: List<SavedSessionState> = emptyList(),
    val expanded: Boolean = false,
) {
    /** The sessions on view: the latest [VISIBLE_SESSIONS] unless the list is unfolded. */
    val visibleSessions: List<SavedSessionState> get() = if (expanded) sessions else sessions.take(VISIBLE_SESSIONS)
    val hiddenCount: Int get() = sessions.size - visibleSessions.size
    val canFold: Boolean get() = sessions.size > VISIBLE_SESSIONS

    companion object {
        const val VISIBLE_SESSIONS = 3
    }
}

/** The Couch to 5K card: the session that comes next and why. */
data class CouchCardState(
    val selection: CouchToFiveKSelection,
    /** When the last session ended (epoch ms), `null` before the first one. */
    val lastSessionAtMillis: Long?,
    val notices: List<GeneratorNotice>,
)

data class ProgramsUiState(
    val loaded: Boolean = false,
    val user: UserProfile? = null,
    val standard: List<ProgramCardState> = emptyList(),
    /** The generators of personalised programs, in the registry's order (Couch to 5K has its own card, [couch]). */
    val generators: List<GeneratorCardState> = emptyList(),
    val couch: CouchCardState? = null,
    val mine: List<ProgramCardState> = emptyList(),
    val confirmingDeleteId: String? = null,
    val failed: Boolean = false,
) {
    /** The name of a program of the user (free or saved personalised), for the delete dialog. */
    fun nameOf(programId: String): String? =
        mine.firstOrNull { it.id == programId }?.name ?: generators.firstNotNullOfOrNull { g -> g.sessions.firstOrNull { it.programId == programId }?.name }
}

/** The standard programs, the generators of personalised programs with their saved sessions, and the user's own programs. */
class ProgramsViewModel(
    private val userData: UserData,
    private val nowMillis: () -> Long = System::currentTimeMillis,
    private val today: () -> LocalDate = LocalDate::now,
    /** Name of the copy of a program ("Intervals (copy)"). */
    private val copyName: (String) -> String = { "$it (copy)" },
) : ViewModel() {

    private data class Local(
        val confirmingDeleteId: String? = null,
        val failed: Boolean = false,
        val expanded: Set<String> = emptySet(),
    )

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

    /** The generators whose saved sessions are listed under their card. */
    private val groupedGenerators: List<GeneratorDescriptor> =
        ProgramGenerators.all.map { it.descriptor }.filter { it.id != CouchToFiveK.ID }

    private val couchCard: Flow<CouchCardState?> = generatorContext(userData, CouchToFiveK.ID).map { context ->
        context?.let { couchCardOf(it) }
    }

    val uiState: StateFlow<ProgramsUiState> = combine(mine, couchCard, local) { (user, programs), couch, local ->
        val groupedIds = groupedGenerators.map { it.id }.toSet()
        val personalised = programs.filter { it.program.kind == ProgramKind.Personalised && it.program.origin?.generatorId in groupedIds }
        ProgramsUiState(
            loaded = true,
            user = user,
            standard = standardCards,
            generators = ProgramGenerators.all.map { it.descriptor }.filter { it.id != CouchToFiveK.ID }.map { d ->
                GeneratorCardState(
                    descriptor = d,
                    sessions = personalised.filter { it.program.origin?.generatorId == d.id }
                        .sortedByDescending { it.createdAtMillis }
                        .map { savedSessionOf(d, it) },
                    expanded = d.id in local.expanded,
                )
            },
            couch = couch,
            mine = programs.filter { it !in personalised }.map(::cardOf),
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

    private fun savedSessionOf(descriptor: GeneratorDescriptor, p: UserProgram): SavedSessionState {
        val inputs = p.program.origin?.inputs.orEmpty()
        return SavedSessionState(
            programId = p.id,
            name = p.program.name,
            createdAtMillis = p.createdAtMillis,
            mode = inputs["mode"]?.let { m -> ExerciseMode.entries.firstOrNull { it.name.equals(m, ignoreCase = true) } },
            durationMinutes = inputs["durationMinutes"]?.toIntOrNull().takeIf { GeneratorInput.DurationMinutes in descriptor.inputs },
            series = inputs["series"]?.toIntOrNull().takeIf { GeneratorInput.Series in descriptor.inputs },
        )
    }

    private fun couchCardOf(context: GeneratorContext): CouchCardState? {
        val generated = GeneratorRequests.couchSession(context, today(), nowMillis()) ?: return null
        val selection = generated.couchToFiveK ?: return null
        return CouchCardState(selection, GeneratorRequests.lastSessionAtMillis(context.history), generated.notices)
    }

    // ------------------------------------------------------------------ actions

    fun requestDelete(programId: String) = local.update { it.copy(confirmingDeleteId = programId) }

    fun cancelDelete() = local.update { it.copy(confirmingDeleteId = null) }

    fun confirmDelete() {
        val id = local.value.confirmingDeleteId ?: return
        local.update { it.copy(confirmingDeleteId = null) }
        viewModelScope.launch {
            if (userData.programs.delete(id) is DataResult.StorageError) local.update { it.copy(failed = true) }
        }
    }

    /** Unfolds (or folds) the saved sessions of a generator. */
    fun toggleExpanded(generatorId: String) = local.update {
        it.copy(expanded = if (generatorId in it.expanded) it.expanded - generatorId else it.expanded + generatorId)
    }

    /**
     * Saves a copy of the program under a new name; [onDuplicated] gets the id of the copy. The copy of a personalised
     * program is a free program without origin, which can be edited.
     */
    fun duplicate(programId: String, onDuplicated: (String) -> Unit = {}) {
        val user = uiState.value.user ?: return
        viewModelScope.launch {
            val original = userData.programs.get(programId)?.program ?: return@launch
            val name = copyName(original.name).take(ProgramLimits.MAX_NAME_LENGTH)
            val copy = original.copy(id = "", name = name, kind = ProgramKind.Free, origin = null)
            when (val result = userData.programs.save(user.id, copy)) {
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
