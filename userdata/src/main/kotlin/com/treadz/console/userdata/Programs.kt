package com.treadz.console.userdata

import com.treadz.console.programs.model.Program
import com.treadz.console.programs.model.ProgramKind
import com.treadz.console.programs.plan.StartParameters
import kotlinx.coroutines.flow.Flow
import com.treadz.console.programs.model.ValidationIssue as ProgramIssue

/**
 * A program stored for a user: a **free** or **personalised** program ([ProgramKind]). Standard programs live in the
 * code of `:programs` (`StandardPrograms`); they are never stored, never synchronised and not part of any list here.
 */
data class UserProgram(
    val userId: Long,
    val program: Program,
    val createdAtMillis: Long,
    /** Last change, as a time (the global version of the record also has the device that made it). */
    val updatedAtMillis: Long,
) {
    /** The program's id (a UUID text), also the name of its file in the synchronised repository. */
    val id: String get() = program.id
}

/** Outcome of [ProgramRepository.save]. */
sealed interface ProgramSaveResult {
    /** Stored (or already stored identically). [program] has the id that was assigned if the program came without one. */
    data class Saved(val program: UserProgram) : ProgramSaveResult

    /**
     * The program was rejected; nothing changed. The issues are those of `ProgramValidator` (each has a stable `path`
     * such as `steps[2].durationSeconds` and an English message for logs), plus these paths of the repository:
     * `kind` (a standard program cannot be stored) and `id` (the id already belongs to a program of another user).
     */
    data class Invalid(val issues: List<ProgramIssue>) : ProgramSaveResult

    /** The user does not exist (e.g. it was deleted meanwhile). */
    data object NotFound : ProgramSaveResult

    data class StorageError(val reason: String) : ProgramSaveResult
}

/**
 * The free and personalised programs of each user. They are synchronised with the other devices
 * (`users/<name>-<uuid>/programs/<uuid>.json`), with the same rules as the other records: last writer wins, deletions
 * are tombstones.
 *
 * The documents are the JSON of `ProgramJson`; a program that cannot be read (written by a newer app, or damaged) is
 * kept as it is, synchronised unchanged, and left out of the lists.
 */
interface ProgramRepository {

    /** The programs of the user ordered by name (case-insensitive), then by id. Empty for an unknown user. */
    fun programs(userId: Long): Flow<List<UserProgram>>

    /** One program by its id; `null` if it does not exist or was deleted. */
    fun program(programId: String): Flow<UserProgram?>

    suspend fun get(programId: String): UserProgram?

    /**
     * Creates or replaces (by [Program.id]) a program of the user. A blank id gets a new UUID. The program is
     * validated with `ProgramValidator` and must be [ProgramKind.Free] or [ProgramKind.Personalised]. Saving a
     * program identical to the stored one changes nothing (it is not uploaded again); saving over a deleted program
     * brings it back.
     */
    suspend fun save(userId: Long, program: Program): ProgramSaveResult

    /**
     * Deletes the program (a tombstone, so the other devices learn about it) together with the last-used start
     * parameters of every user for it. [DataResult.NotFound] if it does not exist.
     */
    suspend fun delete(programId: String): DataResult<Unit>
}

/**
 * The start parameters last used for each program and user, to pre-fill the start screen. They are a convenience of
 * this device: **not synchronised**. They exist for standard programs (`standard-...` ids) and for user programs.
 * Deleting a user program or a profile deletes them; they are never shared between users.
 */
interface StartParametersRepository {

    /** The parameters last used by the user for [programId], or `null` if none. */
    fun lastUsed(userId: Long, programId: String): Flow<StartParameters?>

    /** Remembers [parameters] as the last used ones. [DataResult.NotFound] if the user does not exist. */
    suspend fun save(userId: Long, programId: String, parameters: StartParameters): DataResult<Unit>

    suspend fun clear(userId: Long, programId: String): DataResult<Unit>
}
