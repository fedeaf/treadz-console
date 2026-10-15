package com.treadz.console.sync.github

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.HttpUrl
import okhttp3.HttpUrl.Companion.toHttpUrl
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.util.Base64
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/**
 * [GitHubClient] over the GitHub REST API with OkHttp.
 *
 * The token is only ever put in the `Authorization` header of a request; it is read through [token] for every request
 * (so clearing it takes effect at once) and never appears in a message, a log or an exception.
 *
 * @param token the current token, or `null` when none is set (then every call fails with [GitHubFailure.Unauthorized]
 *   without touching the network).
 * @param nowMillis wall clock, to turn a rate-limit reset time into a wait.
 */
class RestGitHubClient(
    private val http: OkHttpClient,
    private val token: () -> String?,
    private val baseUrl: String = "https://api.github.com",
    private val nowMillis: () -> Long = System::currentTimeMillis,
) : GitHubClient {

    private val json = "application/json; charset=utf-8".toMediaType()

    // ---- repositories ----

    override suspend fun privateRepositories(token: String?): List<RepositoryRef> {
        val result = ArrayList<RepositoryRef>()
        for (page in 1..MAX_REPO_PAGES) {
            val url = url("user", "repos").newBuilder()
                .addQueryParameter("visibility", "private")
                .addQueryParameter("per_page", PAGE_SIZE.toString())
                .addQueryParameter("page", page.toString())
                .build()
            val items = parseArray(call(request(url, token).get().build()))
            for (i in 0 until items.length()) {
                val item = items.optJSONObject(i) ?: continue
                // A fine-grained token also lists the owner's public repositories, and `permissions` describes the
                // user, not the token: only `private` is reliable. Archived and disabled repositories cannot be pushed to.
                if (!item.optBoolean("private", false)) continue
                if (item.optBoolean("archived", false) || item.optBoolean("disabled", false)) continue
                val owner = item.optJSONObject("owner")?.optString("login").orEmpty()
                val name = item.optString("name")
                if (owner.isEmpty() || name.isEmpty()) continue
                result += RepositoryRef(owner, name, item.optString("default_branch").ifEmpty { "main" })
            }
            if (items.length() < PAGE_SIZE) break
        }
        return result
    }

    // ---- refs, commits, trees, blobs ----

    override suspend fun branchState(repo: RepositoryRef, branch: String): BranchState {
        val request = get(repoUrl(repo, "git", "ref", "heads", *branch.split('/').toTypedArray()))
        return try {
            val sha = parseObject(call(request)).optJSONObject("object")?.optString("sha").orEmpty()
            if (sha.isEmpty()) throw invalid("ref without sha")
            BranchState.Exists(sha)
        } catch (e: GitHubException) {
            when (e.failure) {
                GitHubFailure.Conflict -> BranchState.EmptyRepository // "Git Repository is empty."
                GitHubFailure.NotFound -> BranchState.Missing
                else -> throw e
            }
        }
    }

    override suspend fun treeShaOfCommit(repo: RepositoryRef, commitSha: String): String {
        val tree = parseObject(call(get(repoUrl(repo, "git", "commits", commitSha)))).optJSONObject("tree")?.optString("sha").orEmpty()
        if (tree.isEmpty()) throw invalid("commit without tree")
        return tree
    }

    override suspend fun files(repo: RepositoryRef, treeSha: String): List<RemoteFile> {
        val url = repoUrl(repo, "git", "trees", treeSha).newBuilder().addQueryParameter("recursive", "1").build()
        val body = parseObject(call(get(url)))
        if (body.optBoolean("truncated", false)) {
            throw GitHubException(GitHubFailure.TooLarge, message = "The repository tree is too large to list")
        }
        val entries = body.optJSONArray("tree") ?: throw invalid("tree without entries")
        val files = ArrayList<RemoteFile>(entries.length())
        for (i in 0 until entries.length()) {
            val e = entries.optJSONObject(i) ?: continue
            if (e.optString("type") == "blob") files += RemoteFile(e.getString("path"), e.getString("sha"))
        }
        return files
    }

    override suspend fun blob(repo: RepositoryRef, sha: String): ByteArray {
        val body = parseObject(call(get(repoUrl(repo, "git", "blobs", sha))))
        val content = body.optString("content")
        return try {
            if (body.optString("encoding") == "base64") Base64.getMimeDecoder().decode(content) else content.toByteArray(Charsets.UTF_8)
        } catch (e: IllegalArgumentException) {
            throw invalid("blob is not valid base64")
        }
    }

    override suspend fun createBlob(repo: RepositoryRef, content: ByteArray): String {
        val body = JSONObject().put("content", Base64.getEncoder().encodeToString(content)).put("encoding", "base64")
        return sha(parseObject(call(post(repoUrl(repo, "git", "blobs"), body))), "blob")
    }

    override suspend fun createTree(repo: RepositoryRef, baseTreeSha: String, changes: List<TreeChange>): String {
        val entries = JSONArray()
        for (change in changes) {
            val entry = JSONObject().put("path", change.path).put("mode", "100644").put("type", "blob")
            when (change) {
                is TreeChange.Put -> entry.put("sha", change.blobSha)
                is TreeChange.Delete -> entry.put("sha", JSONObject.NULL)
            }
            entries.put(entry)
        }
        val body = JSONObject().put("base_tree", baseTreeSha).put("tree", entries)
        return sha(parseObject(call(post(repoUrl(repo, "git", "trees"), body))), "tree")
    }

    override suspend fun createCommit(repo: RepositoryRef, message: String, treeSha: String, parentSha: String): String {
        val body = JSONObject().put("message", message).put("tree", treeSha).put("parents", JSONArray().put(parentSha))
        return sha(parseObject(call(post(repoUrl(repo, "git", "commits"), body))), "commit")
    }

    override suspend fun fastForward(repo: RepositoryRef, branch: String, commitSha: String): Boolean {
        val body = JSONObject().put("sha", commitSha).put("force", false)
        val request = request(repoUrl(repo, "git", "refs", "heads", *branch.split('/').toTypedArray())).patch(body.toRequestBody(json)).build()
        return try {
            call(request)
            true
        } catch (e: GitHubException) {
            if (e.failure == GitHubFailure.ValidationFailed && e.message.orEmpty().contains("fast forward", ignoreCase = true)) false else throw e
        }
    }

    override suspend fun createFile(repo: RepositoryRef, branch: String, path: String, content: ByteArray, message: String): String {
        val body = JSONObject()
            .put("message", message)
            .put("content", Base64.getEncoder().encodeToString(content))
            .put("branch", branch)
        val request = request(repoUrl(repo, "contents", *path.split('/').toTypedArray())).put(body.toString().toRequestBody(json)).build()
        return parseObject(call(request)).optJSONObject("commit")?.optString("sha").orEmpty().ifEmpty { throw invalid("contents answer without commit") }
    }

    override suspend fun createBranch(repo: RepositoryRef, branch: String, commitSha: String) {
        val body = JSONObject().put("ref", "refs/heads/$branch").put("sha", commitSha)
        call(post(repoUrl(repo, "git", "refs"), body))
    }

    override suspend fun deleteBranch(repo: RepositoryRef, branch: String) {
        call(request(repoUrl(repo, "git", "refs", "heads", *branch.split('/').toTypedArray())).delete().build())
    }

    // ---- plumbing ----

    private fun url(vararg segments: String): HttpUrl {
        val builder = baseUrl.toHttpUrl().newBuilder()
        segments.forEach { builder.addPathSegment(it) }
        return builder.build()
    }

    private fun repoUrl(repo: RepositoryRef, vararg segments: String): HttpUrl = url("repos", repo.owner, repo.name, *segments)

    private fun request(url: HttpUrl, tokenOverride: String? = null): Request.Builder {
        val t = (tokenOverride ?: token())?.takeIf { it.isNotBlank() } ?: throw GitHubException(GitHubFailure.Unauthorized, message = "No token is set")
        return Request.Builder().url(url)
            .header("Authorization", "Bearer $t")
            .header("Accept", "application/vnd.github+json")
            .header("X-GitHub-Api-Version", API_VERSION)
            .header("User-Agent", USER_AGENT)
    }

    private fun get(url: HttpUrl): Request = request(url).get().build()

    private fun post(url: HttpUrl, body: JSONObject): Request = request(url).post(body.toString().toRequestBody(json)).build()

    private fun JSONObject.toRequestBody(type: okhttp3.MediaType) = toString().toRequestBody(type)

    private fun sha(body: JSONObject, what: String): String = body.optString("sha").ifEmpty { throw invalid("$what answer without sha") }

    private fun invalid(what: String) = GitHubException(GitHubFailure.InvalidResponse, message = "Unexpected answer from GitHub: $what")

    /** Runs the request and returns the body; non-2xx answers become a [GitHubException]. */
    private suspend fun call(request: Request): String {
        val response = execute(http.newCall(request))
        response.use { r ->
            val text = try {
                r.body.string()
            } catch (e: IOException) {
                throw GitHubException(GitHubFailure.Network, message = "Connection to GitHub failed while reading the answer", cause = e)
            }
            if (r.isSuccessful) return text
            throw failure(r, text)
        }
    }

    private suspend fun execute(call: Call): Response = suspendCancellableCoroutine { cont ->
        cont.invokeOnCancellation { call.cancel() }
        call.enqueue(
            object : Callback {
                override fun onFailure(call: Call, e: IOException) {
                    // Only the kind of failure is reported: the exception text of an HTTP stack is not ours to forward.
                    cont.resumeWithException(
                        GitHubException(GitHubFailure.Network, message = "Could not reach GitHub (${e.javaClass.simpleName})", cause = e),
                    )
                }

                override fun onResponse(call: Call, response: Response) {
                    cont.resume(response)
                }
            },
        )
    }

    private fun failure(response: Response, body: String): GitHubException {
        val status = response.code
        val message = runCatching { JSONObject(body).optString("message") }.getOrDefault("")
        val rateLimited = status == 429 ||
            (status == 403 && (response.header("x-ratelimit-remaining") == "0" || response.header("retry-after") != null ||
                message.contains("rate limit", ignoreCase = true)))
        val failure = when {
            rateLimited -> GitHubFailure.RateLimited
            status == 401 -> GitHubFailure.Unauthorized
            status == 403 -> GitHubFailure.Forbidden
            status == 404 -> GitHubFailure.NotFound
            status == 409 -> GitHubFailure.Conflict
            status == 422 -> GitHubFailure.ValidationFailed
            status >= 500 -> GitHubFailure.ServerError
            else -> GitHubFailure.InvalidResponse
        }
        val retryAfter = if (rateLimited) retryAfterMillis(response) else null
        return GitHubException(failure, status, retryAfter, "GitHub answered HTTP $status${if (message.isNotEmpty()) ": $message" else ""}")
    }

    private fun retryAfterMillis(response: Response): Long? {
        response.header("retry-after")?.toLongOrNull()?.let { return it * 1000 }
        val reset = response.header("x-ratelimit-reset")?.toLongOrNull() ?: return null
        return (reset * 1000 - nowMillis()).coerceAtLeast(0)
    }

    private fun parseObject(text: String): JSONObject = try {
        JSONObject(text)
    } catch (e: JSONException) {
        throw invalid("not a JSON object")
    }

    private fun parseArray(text: String): JSONArray = try {
        JSONArray(text)
    } catch (e: JSONException) {
        throw invalid("not a JSON array")
    }

    private companion object {
        const val API_VERSION = "2022-11-28"
        const val USER_AGENT = "TreadZ-Sync"
        const val PAGE_SIZE = 100
        const val MAX_REPO_PAGES = 10
    }
}
