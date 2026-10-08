package com.treadz.console.sync

import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.Protocol
import okhttp3.Response
import okhttp3.ResponseBody.Companion.toResponseBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.IOException
import java.security.MessageDigest
import java.util.Base64

/**
 * GitHub in memory: the REST endpoints the sync uses (user repositories, refs, commits, trees, blobs, Contents),
 * with Git's real rules where they matter (blob ids are SHA-1 of the content, refs move only by fast-forward, the
 * Git Data API refuses an empty repository). It is an OkHttp interceptor, so the real [com.treadz.console.sync.github.RestGitHubClient]
 * runs against it. Several devices share one instance.
 */
class FakeGitHub : Interceptor {

    class Repo(val owner: String, val name: String, val isPrivate: Boolean = true, val defaultBranch: String = "main", val archived: Boolean = false) {
        val blobs = HashMap<String, ByteArray>()
        val trees = HashMap<String, Map<String, String>>() // flattened: path -> blob sha
        val commits = HashMap<String, Commit>()
        val refs = LinkedHashMap<String, String>() // branch -> commit sha
        val isEmpty get() = commits.isEmpty()

        /** Files at the tip of [branch]. */
        fun files(branch: String = defaultBranch): Map<String, String> = refs[branch]?.let { trees.getValue(commits.getValue(it).tree) } ?: emptyMap()

        fun text(path: String, branch: String = defaultBranch): String? = files(branch)[path]?.let { String(blobs.getValue(it), Charsets.UTF_8) }

        /** Commit messages of [branch], oldest first. */
        fun log(branch: String = defaultBranch): List<String> {
            val result = ArrayList<String>()
            var sha = refs[branch]
            while (sha != null) {
                val c = commits.getValue(sha)
                result += c.message
                sha = c.parents.firstOrNull()
            }
            return result.reversed()
        }
    }

    class Commit(val tree: String, val parents: List<String>, val message: String)

    private class Failure(val status: Int, val body: String, val headers: Map<String, String> = emptyMap(), val onlyFor: Regex? = null)

    val repos = ArrayList<Repo>()
    val validTokens = HashSet<String>()

    /** Fine-grained tokens: the names of the only repositories a token can see (a token not listed here sees all). */
    val tokenRepos = HashMap<String, Set<String>>()
    private var caller: String? = null

    /** Writes are refused with 403, like a token with read-only access. */
    var readOnly = false

    /** `/user/repos` ignores `visibility=private` (a fine-grained token also lists the owner's public repositories). */
    var listsPublicRepos = true

    /** Throws an [IOException] for every request. */
    var offline = false

    /** Marks every tree answer as truncated. */
    var truncateTrees = false

    /** Called just before a branch update is processed, e.g. to let another device push first. */
    var beforeBranchUpdate: (() -> Unit)? = null

    /** `"METHOD /path"` of every request, in order. */
    val requests = ArrayList<String>()

    private val failures = ArrayDeque<Failure>()
    private var counter = 0

    fun addRepo(repo: Repo): Repo = repo.also { repos += it }

    /** The next request (matching [pathRegex], if given) fails with [status]. */
    fun failNext(status: Int, message: String = "Failure", headers: Map<String, String> = emptyMap(), pathRegex: Regex? = null) {
        failures += Failure(status, JSONObject().put("message", message).toString(), headers, pathRegex)
    }

    fun count(prefix: String) = requests.count { it.startsWith(prefix) }

    // ------------------------------------------------------------ HTTP plumbing

    override fun intercept(chain: Interceptor.Chain): Response {
        val request = chain.request()
        val path = request.url.encodedPath
        val line = "${request.method} $path"
        requests += line
        if (offline) throw IOException("fake network down")
        val auth = request.header("Authorization")?.removePrefix("Bearer ")
        if (auth == null || auth !in validTokens) return respond(request, 401, message("Bad credentials"))
        caller = auth
        failures.firstOrNull { it.onlyFor == null || it.onlyFor.containsMatchIn(line) }?.let {
            failures.remove(it)
            return respond(request, it.status, it.body, it.headers)
        }
        val body = request.body?.let { b -> okio.Buffer().also { b.writeTo(it) }.readUtf8() }?.let { if (it.isEmpty()) null else JSONObject(it) }
        val isWrite = request.method != "GET"
        if (isWrite && readOnly) return respond(request, 403, message("Resource not accessible by personal access token"))
        return try {
            route(request.method, path, request.url.queryParameter("page")?.toInt() ?: 1, request.url.queryParameter("recursive") != null, body)
                .let { (status, json) -> respond(request, status, json.toString()) }
        } catch (e: HttpError) {
            respond(request, e.status, message(e.message.orEmpty()))
        }
    }

    private class HttpError(val status: Int, message: String) : Exception(message)

    private fun message(text: String) = JSONObject().put("message", text).toString()

    private fun respond(request: okhttp3.Request, status: Int, body: String, headers: Map<String, String> = emptyMap()): Response {
        val builder = Response.Builder().request(request).protocol(Protocol.HTTP_1_1).code(status).message("fake")
            .body(body.toResponseBody("application/json".toMediaType()))
        headers.forEach { (k, v) -> builder.header(k, v) }
        return builder.build()
    }

    // ------------------------------------------------------------ routes

    private fun route(method: String, path: String, page: Int, recursive: Boolean, body: JSONObject?): Pair<Int, Any> {
        if (method == "GET" && path == "/user/repos") return 200 to userRepos(page)
        val parts = path.removePrefix("/").split("/")
        if (parts.size < 4 || parts[0] != "repos") throw HttpError(404, "Not Found")
        val repo = repos.firstOrNull { it.owner == parts[1] && it.name == parts[2] && canSee(it) } ?: throw HttpError(404, "Not Found")
        val rest = parts.drop(3)
        return when {
            method == "GET" && rest[0] == "git" && rest[1] == "ref" -> 200 to getRef(repo, rest.drop(3).joinToString("/"))
            method == "GET" && rest[0] == "git" && rest[1] == "commits" -> 200 to getCommit(repo, rest[2])
            method == "GET" && rest[0] == "git" && rest[1] == "trees" -> 200 to getTree(repo, rest[2], recursive)
            method == "GET" && rest[0] == "git" && rest[1] == "blobs" -> 200 to getBlob(repo, rest[2])
            method == "POST" && rest[0] == "git" && rest[1] == "blobs" -> 201 to createBlob(repo, body!!)
            method == "POST" && rest[0] == "git" && rest[1] == "trees" -> 201 to createTree(repo, body!!)
            method == "POST" && rest[0] == "git" && rest[1] == "commits" -> 201 to createCommit(repo, body!!)
            method == "PATCH" && rest[0] == "git" && rest[1] == "refs" -> 200 to updateRef(repo, rest.drop(3).joinToString("/"), body!!)
            method == "POST" && rest[0] == "git" && rest[1] == "refs" -> 201 to createRef(repo, body!!)
            method == "DELETE" && rest[0] == "git" && rest[1] == "refs" -> 204 to JSONObject().also { repo.refs.remove(rest.drop(3).joinToString("/")) }
            method == "PUT" && rest[0] == "contents" -> 201 to putContents(repo, rest.drop(1).joinToString("/"), body!!)
            else -> throw HttpError(404, "Not Found")
        }
    }

    private fun canSee(repo: Repo) = tokenRepos[caller]?.contains(repo.name) ?: true

    private fun userRepos(page: Int): JSONArray {
        val visible = repos.filter { (listsPublicRepos || it.isPrivate) && canSee(it) }
        val array = JSONArray()
        visible.drop((page - 1) * 100).take(100).forEach {
            array.put(
                JSONObject().put("name", it.name).put("private", it.isPrivate).put("archived", it.archived).put("default_branch", it.defaultBranch)
                    .put("owner", JSONObject().put("login", it.owner)).put("permissions", JSONObject().put("push", true)),
            )
        }
        return array
    }

    private fun getRef(repo: Repo, branch: String): JSONObject {
        if (repo.isEmpty) throw HttpError(409, "Git Repository is empty.")
        val sha = repo.refs["$branch".removePrefix("heads/")] ?: throw HttpError(404, "Not Found")
        return JSONObject().put("ref", "refs/heads/$branch").put("object", JSONObject().put("sha", sha).put("type", "commit"))
    }

    private fun requireData(repo: Repo) {
        if (repo.isEmpty) throw HttpError(409, "Git Repository is empty.")
    }

    private fun getCommit(repo: Repo, sha: String): JSONObject {
        requireData(repo)
        val c = repo.commits[sha] ?: throw HttpError(404, "Not Found")
        return JSONObject().put("sha", sha).put("message", c.message).put("tree", JSONObject().put("sha", c.tree))
    }

    private fun getTree(repo: Repo, sha: String, recursive: Boolean): JSONObject {
        requireData(repo)
        val tree = repo.trees[sha] ?: throw HttpError(404, "Not Found")
        val entries = JSONArray()
        // Directories too, like GitHub: the client must keep only blobs.
        tree.keys.flatMap { p -> p.split('/').dropLast(1).runningReduce { a, b -> "$a/$b" } }.distinct().sorted().forEach {
            entries.put(JSONObject().put("path", it).put("type", "tree").put("mode", "040000").put("sha", "d".repeat(40)))
        }
        tree.toSortedMap().forEach { (p, s) -> entries.put(JSONObject().put("path", p).put("type", "blob").put("mode", "100644").put("sha", s)) }
        return JSONObject().put("sha", sha).put("tree", entries).put("truncated", truncateTrees)
    }

    private fun getBlob(repo: Repo, sha: String): JSONObject {
        val bytes = repo.blobs[sha] ?: throw HttpError(404, "Not Found")
        // GitHub wraps base64 at 60 characters.
        val encoded = Base64.getEncoder().encodeToString(bytes).chunked(60).joinToString("\n")
        return JSONObject().put("sha", sha).put("encoding", "base64").put("content", encoded).put("size", bytes.size)
    }

    private fun storeBlob(repo: Repo, bytes: ByteArray): String {
        val digest = MessageDigest.getInstance("SHA-1")
        digest.update("blob ${bytes.size}\u0000".toByteArray())
        digest.update(bytes)
        val sha = digest.digest().joinToString("") { "%02x".format(it) }
        repo.blobs[sha] = bytes
        return sha
    }

    private fun createBlob(repo: Repo, body: JSONObject): JSONObject {
        val bytes = if (body.optString("encoding") == "base64") Base64.getDecoder().decode(body.getString("content")) else body.getString("content").toByteArray()
        return JSONObject().put("sha", storeBlob(repo, bytes))
    }

    private fun fakeSha(vararg parts: String): String {
        val digest = MessageDigest.getInstance("SHA-1")
        parts.forEach { digest.update(it.toByteArray()); digest.update(0) }
        digest.update((++counter).toString().toByteArray())
        return digest.digest().joinToString("") { "%02x".format(it) }
    }

    private fun storeTree(repo: Repo, files: Map<String, String>): String {
        val sha = fakeSha("tree", files.toSortedMap().entries.joinToString(",") { "${it.key}=${it.value}" })
        repo.trees[sha] = files
        return sha
    }

    private fun createTree(repo: Repo, body: JSONObject): JSONObject {
        requireData(repo)
        val base = body.optString("base_tree")
        val files = HashMap(repo.trees[base] ?: throw HttpError(422, "base_tree is not a valid tree"))
        val entries = body.getJSONArray("tree")
        for (i in 0 until entries.length()) {
            val e = entries.getJSONObject(i)
            if (e.isNull("sha")) {
                files.remove(e.getString("path"))
            } else {
                if (e.getString("sha") !in repo.blobs) throw HttpError(422, "tree.sha references a missing blob")
                files[e.getString("path")] = e.getString("sha")
            }
        }
        return JSONObject().put("sha", storeTree(repo, files))
    }

    private fun createCommit(repo: Repo, body: JSONObject): JSONObject {
        requireData(repo)
        val tree = body.getString("tree")
        if (tree !in repo.trees) throw HttpError(422, "tree does not exist")
        val parents = body.getJSONArray("parents").let { a -> (0 until a.length()).map { a.getString(it) } }
        if (parents.any { it !in repo.commits }) throw HttpError(422, "parent does not exist")
        val sha = fakeSha("commit", tree, body.getString("message"))
        repo.commits[sha] = Commit(tree, parents, body.getString("message"))
        return JSONObject().put("sha", sha)
    }

    private fun isAncestor(repo: Repo, ancestor: String, of: String): Boolean {
        var sha: String? = of
        while (sha != null) {
            if (sha == ancestor) return true
            sha = repo.commits.getValue(sha).parents.firstOrNull()
        }
        return false
    }

    private fun updateRef(repo: Repo, ref: String, body: JSONObject): JSONObject {
        beforeBranchUpdate?.let { hook -> beforeBranchUpdate = null; hook() }
        val branch = ref.removePrefix("heads/")
        val current = repo.refs[branch] ?: throw HttpError(404, "Reference does not exist")
        val target = body.getString("sha")
        if (target !in repo.commits) throw HttpError(422, "Object does not exist")
        if (!body.optBoolean("force", false) && !isAncestor(repo, current, target)) throw HttpError(422, "Update is not a fast forward")
        repo.refs[branch] = target
        return JSONObject().put("ref", "refs/heads/$branch").put("object", JSONObject().put("sha", target))
    }

    private fun createRef(repo: Repo, body: JSONObject): JSONObject {
        requireData(repo)
        val branch = body.getString("ref").removePrefix("refs/heads/")
        if (branch in repo.refs) throw HttpError(422, "Reference already exists")
        repo.refs[branch] = body.getString("sha")
        return JSONObject().put("ref", body.getString("ref"))
    }

    private fun putContents(repo: Repo, path: String, body: JSONObject): JSONObject {
        val branch = body.optString("branch").ifEmpty { repo.defaultBranch }
        val blob = storeBlob(repo, Base64.getDecoder().decode(body.getString("content")))
        val parent = repo.refs[branch]
        val base = parent?.let { repo.trees.getValue(repo.commits.getValue(it).tree) } ?: emptyMap()
        if (!repo.isEmpty && parent == null) throw HttpError(404, "Branch $branch not found")
        if (path in base) throw HttpError(422, "Invalid request.\n\n\"sha\" wasn't supplied.")
        val tree = storeTree(repo, base + (path to blob))
        val commit = fakeSha("commit", tree, body.getString("message"))
        repo.commits[commit] = Commit(tree, listOfNotNull(parent), body.getString("message"))
        repo.refs[branch] = commit
        return JSONObject().put("commit", JSONObject().put("sha", commit)).put("content", JSONObject().put("path", path))
    }
}
