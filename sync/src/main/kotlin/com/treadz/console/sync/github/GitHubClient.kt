package com.treadz.console.sync.github

/** A GitHub repository the token can use. */
data class RepositoryRef(
    val owner: String,
    val name: String,
    /** The branch the data lives on. */
    val defaultBranch: String,
) {
    val fullName: String get() = "$owner/$name"
}

/** A file of a Git tree: its [path] from the repository root and the SHA-1 of its content (a Git blob id). */
data class RemoteFile(val path: String, val sha: String)

/** What the branch looks like on GitHub. */
sealed interface BranchState {
    /** The branch exists; [commitSha] is its tip. */
    data class Exists(val commitSha: String) : BranchState

    /** The repository has commits but not this branch. */
    data object Missing : BranchState

    /** The repository has no commits at all: the Git Data API cannot be used until a first commit exists. */
    data object EmptyRepository : BranchState
}

/** One change of a tree, relative to a base tree. */
sealed interface TreeChange {
    val path: String

    /** Adds or replaces [path] with the existing blob [blobSha]. */
    data class Put(override val path: String, val blobSha: String) : TreeChange

    /** Removes [path]. */
    data class Delete(override val path: String) : TreeChange
}

/** Why a request to GitHub failed. Stable codes. */
enum class GitHubFailure {
    /** No connection, timeout or any I/O problem. */
    Network,

    /** 401: the token is invalid, expired or revoked. */
    Unauthorized,

    /** 403 that is not a rate limit: the token may not do that (e.g. read-only access). */
    Forbidden,

    /** The request limit was reached; [GitHubException.retryAfterMillis] says when to retry, if known. */
    RateLimited,

    /** 404: not found, or hidden because the token has no access. */
    NotFound,

    /** 409, e.g. the repository is empty. */
    Conflict,

    /** 422 with another reason than a non fast-forward update. */
    ValidationFailed,

    /** 5xx. */
    ServerError,

    /** The answer could not be understood. */
    InvalidResponse,

    /** The repository tree is too big to be listed in one request. */
    TooLarge,
}

/**
 * A failed GitHub request. The message is safe to log: it never contains the token or any request header.
 */
class GitHubException(
    val failure: GitHubFailure,
    val status: Int? = null,
    /** How long to wait before retrying, when GitHub said so (rate limits). */
    val retryAfterMillis: Long? = null,
    message: String,
    cause: Throwable? = null,
) : Exception(message, cause)

/**
 * The GitHub operations the synchronisation needs, over the REST API (there is no `git` on Android). Every function
 * throws [GitHubException] on failure.
 */
interface GitHubClient {

    /**
     * The **private** repositories a token can see (the listing also includes public ones, which are dropped).
     *
     * @param token a candidate token to try without storing it (used to validate a token before it replaces the one
     *   in use); `null` uses the stored one.
     */
    suspend fun privateRepositories(token: String? = null): List<RepositoryRef>

    suspend fun branchState(repo: RepositoryRef, branch: String): BranchState

    /** The tree of a commit. */
    suspend fun treeShaOfCommit(repo: RepositoryRef, commitSha: String): String

    /** Every file of a tree, recursively (`TooLarge` if GitHub truncated the answer). */
    suspend fun files(repo: RepositoryRef, treeSha: String): List<RemoteFile>

    suspend fun blob(repo: RepositoryRef, sha: String): ByteArray

    /** Stores [content] and returns its blob SHA. */
    suspend fun createBlob(repo: RepositoryRef, content: ByteArray): String

    /** A new tree: [baseTreeSha] with [changes] applied. */
    suspend fun createTree(repo: RepositoryRef, baseTreeSha: String, changes: List<TreeChange>): String

    suspend fun createCommit(repo: RepositoryRef, message: String, treeSha: String, parentSha: String): String

    /** Moves the branch to [commitSha] **without force**. Returns `false` if that is not a fast-forward (somebody pushed first). */
    suspend fun fastForward(repo: RepositoryRef, branch: String, commitSha: String): Boolean

    /**
     * Creates the very first commit of an empty repository (or adds a file to [branch]) with the Contents API, which
     * works without any commit. Returns the SHA of the new commit. Fails with [GitHubFailure.ValidationFailed] if the
     * file already exists.
     */
    suspend fun createFile(repo: RepositoryRef, branch: String, path: String, content: ByteArray, message: String): String

    suspend fun createBranch(repo: RepositoryRef, branch: String, commitSha: String)

    suspend fun deleteBranch(repo: RepositoryRef, branch: String)
}
