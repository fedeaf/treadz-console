package com.treadz.console.sync

import com.treadz.console.sync.github.BranchState
import com.treadz.console.sync.github.GitHubException
import com.treadz.console.sync.github.GitHubFailure
import com.treadz.console.sync.github.RepositoryRef
import com.treadz.console.sync.github.RestGitHubClient
import com.treadz.console.sync.github.TreeChange
import kotlinx.coroutines.runBlocking
import okhttp3.Interceptor
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.ResponseBody.Companion.toResponseBody
import okhttp3.OkHttpClient
import okhttp3.Response
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class RestGitHubClientTest {

    private val github = FakeGitHub().apply { validTokens += TOKEN }
    private val seen = ArrayList<okhttp3.Request>()
    private var token: String? = TOKEN
    private val http = OkHttpClient.Builder()
        .addInterceptor(Interceptor { chain -> seen += chain.request(); chain.proceed(chain.request()) })
        .addInterceptor(github)
        .build()
    private val client = RestGitHubClient(http, { token }, nowMillis = { T0 })
    private val ref = RepositoryRef("fede", "treadz-data", "main")
    private val repo = github.addRepo(FakeGitHub.Repo("fede", "treadz-data"))

    private fun failureOf(block: suspend () -> Unit): GitHubException = try {
        runBlocking { block() }
        fail("expected a GitHubException")
        throw IllegalStateException()
    } catch (e: GitHubException) {
        e
    }

    @Test fun `requests carry the token only in the Authorization header`() {
        runBlocking { client.privateRepositories() }
        val request = seen.single()
        assertEquals("Bearer $TOKEN", request.header("Authorization"))
        assertEquals("application/vnd.github+json", request.header("Accept"))
        assertEquals("2022-11-28", request.header("X-GitHub-Api-Version"))
        assertTrue(request.header("User-Agent")!!.isNotEmpty())
        assertFalse(request.url.toString().contains(TOKEN))
    }

    @Test fun `without a token nothing is sent`() {
        token = null
        assertEquals(GitHubFailure.Unauthorized, failureOf { client.privateRepositories() }.failure)
        token = "  "
        assertEquals(GitHubFailure.Unauthorized, failureOf { client.privateRepositories() }.failure)
        assertTrue(seen.isEmpty())
    }

    @Test fun `only private, not archived repositories are listed, across pages`() {
        github.repos.clear()
        github.addRepo(FakeGitHub.Repo("fede", "site", isPrivate = false))
        github.addRepo(FakeGitHub.Repo("fede", "attic", archived = true))
        repeat(150) { github.addRepo(FakeGitHub.Repo("fede", "repo-%03d".format(it))) }
        val repos = runBlocking { client.privateRepositories() }
        assertEquals(150, repos.size)
        assertTrue(repos.all { it.owner == "fede" && it.defaultBranch == "main" })
        assertEquals(2, github.count("GET /user/repos"))
    }

    @Test fun `an empty repository, a missing branch and an existing one are told apart`() {
        assertEquals(BranchState.EmptyRepository, runBlocking { client.branchState(ref, "main") })
        runBlocking { client.createFile(ref, "main", "a.txt", "hi".toByteArray(), "first") }
        val state = runBlocking { client.branchState(ref, "main") } as BranchState.Exists
        assertEquals(repo.refs.getValue("main"), state.commitSha)
        assertEquals(BranchState.Missing, runBlocking { client.branchState(ref, "other") })
    }

    @Test fun `the git data flow stores blobs, builds a tree, commits and fast-forwards`() = runBlocking<Unit> {
        client.createFile(ref, "main", "README.md", "# hi".toByteArray(), "first")
        val head = (client.branchState(ref, "main") as BranchState.Exists).commitSha
        val tree = client.treeShaOfCommit(ref, head)
        val content = "line one\nline two é\n".repeat(100).toByteArray()
        val blob = client.createBlob(ref, content)
        assertArrayEquals(content, client.blob(ref, blob))
        val newTree = client.createTree(ref, tree, listOf(TreeChange.Put("users/x/file.csv", blob), TreeChange.Delete("README.md")))
        val commit = client.createCommit(ref, "message", newTree, head)
        assertTrue(client.fastForward(ref, "main", commit))
        assertEquals(listOf("users/x/file.csv"), client.files(ref, client.treeShaOfCommit(ref, commit)).map { it.path })
        // a commit that does not descend from the tip is refused, without force
        val sibling = client.createCommit(ref, "sibling", newTree, head)
        assertFalse(client.fastForward(ref, "main", sibling))
        assertEquals(commit, repo.refs.getValue("main"))
    }

    @Test fun `branches can be created and deleted`() = runBlocking<Unit> {
        client.createFile(ref, "main", "README.md", "# hi".toByteArray(), "first")
        val head = repo.refs.getValue("main")
        client.createBranch(ref, "temp/test-1", head)
        assertTrue(client.branchState(ref, "temp/test-1") is BranchState.Exists)
        client.deleteBranch(ref, "temp/test-1")
        assertEquals(BranchState.Missing, client.branchState(ref, "temp/test-1"))
    }

    @Test fun `creating a file that exists is a validation failure`() = runBlocking<Unit> {
        client.createFile(ref, "main", "README.md", "# hi".toByteArray(), "first")
        assertEquals(GitHubFailure.ValidationFailed, failureOf { client.createFile(ref, "main", "README.md", "again".toByteArray(), "second") }.failure)
    }

    @Test fun `error statuses are mapped and messages never hold the token`() {
        val cases = listOf(
            401 to GitHubFailure.Unauthorized, 403 to GitHubFailure.Forbidden, 404 to GitHubFailure.NotFound, 409 to GitHubFailure.Conflict,
            422 to GitHubFailure.ValidationFailed, 500 to GitHubFailure.ServerError, 502 to GitHubFailure.ServerError, 418 to GitHubFailure.InvalidResponse,
        )
        for ((status, expected) in cases) {
            github.failNext(status, "problem with $TOKEN-less request")
            val e = failureOf { client.privateRepositories() }
            assertEquals("HTTP $status", expected, e.failure)
            assertEquals(status, e.status)
            assertFalse(e.message!!.contains("Bearer"))
        }
        github.failNext(403, "Secondary rate limit")
        assertEquals(GitHubFailure.RateLimited, failureOf { client.privateRepositories() }.failure)
        github.failNext(429, "slow down", mapOf("retry-after" to "12"))
        assertEquals(12_000L, failureOf { client.privateRepositories() }.retryAfterMillis)
    }

    @Test fun `a network failure carries only the kind of problem`() {
        github.offline = true
        val e = failureOf { client.privateRepositories() }
        assertEquals(GitHubFailure.Network, e.failure)
        assertFalse(e.toString().contains(TOKEN))
        assertFalse(e.cause.toString().contains(TOKEN))
    }

    @Test fun `an answer that is not what GitHub sends is an invalid response`() {
        val garbage = OkHttpClient.Builder().addInterceptor(Interceptor { chain ->
            Response.Builder().request(chain.request()).protocol(okhttp3.Protocol.HTTP_1_1).code(200).message("ok")
                .body("<html>".toResponseBody("text/plain".toMediaType())).build()
        }).build()
        val bad = RestGitHubClient(garbage, { TOKEN })
        assertEquals(GitHubFailure.InvalidResponse, failureOf { bad.privateRepositories() }.failure)
    }
}
