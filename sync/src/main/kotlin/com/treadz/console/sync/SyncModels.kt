package com.treadz.console.sync


/** Why a synchronisation failed. Stable codes: the UI turns them into texts. */
enum class SyncErrorCode(
    /** A later attempt may succeed without the user doing anything. */
    val isRetryable: Boolean,
) {
    /** No connection to GitHub. */
    NoNetwork(true),

    /** The token is invalid, expired or revoked: the user must enter a new one. */
    Unauthorized(false),

    /** The token does not give access to any private repository. */
    NoPrivateRepository(false),

    /** The repository cannot be found or read with this token (deleted, renamed or access removed). */
    RepositoryNotFound(false),

    /** The token can read the repository but not write to it (the token needs read and write access to contents). */
    NoWriteAccess(false),

    /** GitHub's request limit was reached; see [SyncError.retryAfterMillis]. */
    RateLimited(true),

    /** GitHub failed (5xx). */
    ServerError(true),

    /** Other devices kept pushing at the same time and the attempts ran out. */
    Conflict(true),

    /** The repository is too big to be listed. */
    RepositoryTooLarge(false),

    /** GitHub's answer was not understood. */
    InvalidResponse(false),

    /** Something unexpected on this device (e.g. storage); the details are not shown. */
    Unknown(false),
}

data class SyncError(
    val code: SyncErrorCode,
    /** When GitHub asked to wait, how long, in milliseconds. */
    val retryAfterMillis: Long? = null,
)

/** A synchronisation failed with [error]. The message never contains the token. */
class SyncException(val error: SyncError, cause: Throwable? = null) : Exception("Synchronisation failed: ${error.code}", cause)

/** What would be uploaded for one user the first time a device is linked. */
data class UserUpload(
    val name: String,
    /** The profile itself is uploaded (new, or changed). */
    val profile: Boolean,
    val weightEntries: Int,
    val workouts: Int,
    val programs: Int,
    val fitnessTests: Int = 0,
    val restingHeartRates: Int = 0,
)

/**
 * What linking this device would upload, shown before anything is sent. Records that exist only on one side are
 * merged (nothing is overwritten): the repository's records and these coexist.
 */
data class FirstLinkSummary(
    val users: List<UserUpload>,
    /** Users already in the repository, `null` if it could not be looked at. */
    val remoteUsers: Int?,
) {
    val totalWorkouts: Int get() = users.sumOf { it.workouts }
    val totalWeightEntries: Int get() = users.sumOf { it.weightEntries }
    val totalPrograms: Int get() = users.sumOf { it.programs }
    val totalFitnessTests: Int get() = users.sumOf { it.fitnessTests }

    /** There is something to upload (otherwise linking needs no confirmation). */
    val hasUploads: Boolean get() = users.isNotEmpty()
}

/** Outcome of one synchronisation. */
data class SyncReport(
    /** Records created or updated here from the repository. */
    val downloaded: Int,
    /** Records uploaded. */
    val uploaded: Int,
    /** The commit created, or `null` if nothing needed uploading. */
    val commitSha: String?,
    /** Times the whole cycle ran (above 1: another device pushed meanwhile). */
    val attempts: Int,
)

