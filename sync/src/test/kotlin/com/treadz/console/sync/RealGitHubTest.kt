package com.treadz.console.sync

import com.treadz.console.sync.github.BranchState
import com.treadz.console.sync.github.RepositoryRef
import com.treadz.console.sync.github.RestGitHubClient
import com.treadz.console.userdata.Sex
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import okhttp3.OkHttpClient
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import java.time.LocalDate
import java.util.UUID

/**
 * End to end against the real GitHub. **Not part of the normal test tasks**: it only runs with
 * `./gradlew :sync:testDebugUnitTest -PrealGitHub=true` (which runs nothing else).
 *
 * - The token is read from `~/.config/treadz-console/github-token` (outside the repository) and never printed.
 * - The repository is discovered with the token (the single private repository, like the app does).
 * - Everything happens on a temporary branch forked from the default branch, which is deleted at the end; the default
 *   branch is never written to.
 * - It refuses to run on an **empty** repository: the first branch ever pushed to an empty repository becomes its
 *   default branch and can no longer be deleted. Give the repository a first commit (e.g. a README) on its default
 *   branch first.
 */
@RunWith(RobolectricTestRunner::class)
class RealGitHubTest {

    @Test fun `two devices synchronise through the private repository on a temporary branch`() = runBlocking<Unit> {
        assumeTrue("set -PrealGitHub=true to run", System.getProperty("treadz.realGitHub") == "true")
        val tokenFile = File(System.getProperty("user.home"), ".config/treadz-console/github-token")
        assertTrue("the token file is missing", tokenFile.isFile)
        val token = tokenFile.readText().trim()
        assertTrue(token.isNotEmpty())

        val http = OkHttpClient()
        val client = RestGitHubClient(http, { token })

        // The app's repository discovery, for real.
        val repos = client.privateRepositories()
        println("REAL: private repositories found: ${repos.map { it.fullName }}")
        val repo: RepositoryRef = repos.singleOrNull() ?: repos.first { it.name == "treadz-data" }
        val defaultBranch = repo.defaultBranch
        val branch = "tmp-synctest-" + UUID.randomUUID().toString().take(8)
        assertFalse("never write to the default branch", branch == defaultBranch)

        val initial = client.branchState(repo, defaultBranch)
        println("REAL: default branch state: ${initial::class.simpleName}")
        assumeTrue("the repository is empty: create a first commit on its default branch before running this test", initial is BranchState.Exists)
        try {
            client.createBranch(repo, branch, (initial as BranchState.Exists).commitSha)

            val runSeed = UUID.randomUUID().mostSignificantBits
            val a = RealDevice("tablet", runSeed, token)
            val ana = a.createUser("Ana")
            a.addWeight(ana, LocalDate.of(2026, 10, 1), 61.5)
            a.addWeight(ana, LocalDate.of(2026, 10, 2), 61.0)
            a.finishWorkout(ana, T0, seconds = 5, meters = 150)
            val first = a.engine.sync(repo, branch)
            println("REAL: first sync: attempts=${first.attempts} uploaded=${first.uploaded}")
            assertNotNull(first.commitSha)

            val head = (client.branchState(repo, branch) as BranchState.Exists).commitSha
            val files = client.files(repo, client.treeShaOfCommit(repo, head)).map { it.path }.sorted()
            println("REAL: files on the temporary branch: ${files.map { it.replace(Regex("[0-9a-f]{8}-[0-9a-f-]{27}"), "<uuid>") }}")
            assertTrue(files.contains("README.md"))
            val anaFolder = files.first { it.endsWith("/profile.json") }.removeSuffix("profile.json")
            assertTrue(anaFolder.matches(Regex("users/ana-[0-9a-f-]{36}/")))
            assertEquals(1, files.count { it == "${anaFolder}weight.csv" })
            assertEquals(2, files.count { it.startsWith("${anaFolder}workouts/2026/10/") })

            val b = RealDevice("phone", runSeed + 1, token, seedGenericProfile = true)
            b.engine.sync(repo, branch)
            val anaB = b.profiles().single()
            assertEquals("Ana", anaB.name)
            assertEquals(listOf(61.5, 61.0), b.data.weights.entries(anaB.id).first().map { it.weightKg })
            assertEquals(5, b.data.workouts.detail(b.data.workouts.workouts(anaB.id).first().single().id)!!.samples.size)

            // a change on B, a deletion on A, both through the real API
            b.at(T0 + 60_000)
            b.finishWorkout(anaB, T0 + 3_600_000, seconds = 3, meters = 90)
            b.engine.sync(repo, branch)
            a.at(T0 + 120_000)
            a.engine.sync(repo, branch)
            assertEquals(2, a.data.workouts.workouts(ana.id).first().size)
            val toDelete = a.data.workouts.workouts(ana.id).first().last()
            a.data.workouts.delete(toDelete.id)
            a.engine.sync(repo, branch)
            b.at(T0 + 180_000)
            b.engine.sync(repo, branch)
            assertEquals(1, b.data.workouts.workouts(anaB.id).first().size)

            val finalHead = (client.branchState(repo, branch) as BranchState.Exists).commitSha
            assertTrue(finalHead != head)
            assertEquals(0, a.data.sync.pendingCount.first())
            assertEquals(0, b.data.sync.pendingCount.first())
        } finally {
            // Cleanup: the temporary branch goes away whatever happened.
            runCatching { client.deleteBranch(repo, branch) }
            val after = client.branchState(repo, branch)
            println("REAL: temporary branch after cleanup: ${after::class.simpleName}")
            val mainAfter = client.branchState(repo, defaultBranch)
            println("REAL: default branch after the test: ${mainAfter::class.simpleName}")
            assertFalse("the temporary branch must be gone", after is BranchState.Exists)
            if (initial !is BranchState.Exists) assertFalse("the default branch must not have been created", mainAfter is BranchState.Exists)
        }
    }
}

/** A simulated device whose engine talks to the real GitHub. */
private class RealDevice(name: String, seed: Long, token: String, seedGenericProfile: Boolean = false) {
    private val fake = FakeGitHub()
    private val inner = Device(name, fake, seed, seedGenericProfile = seedGenericProfile)
    val data get() = inner.data
    val clock get() = inner.clock
    val engine = com.treadz.console.sync.engine.SyncEngine(
        RestGitHubClient(OkHttpClient(), { token }), inner.data.sync, inner.programs, inner.state, inner.clock, java.time.ZoneOffset.UTC,
    )
    fun at(millis: Long) { inner.at(millis) }
    fun createUser(name: String, sex: Sex = Sex.Female) = inner.createUser(name, sex)
    fun addWeight(user: com.treadz.console.userdata.UserProfile, date: LocalDate, kg: Double) = inner.addWeight(user, date, kg)
    fun finishWorkout(user: com.treadz.console.userdata.UserProfile?, startedAt: Long, seconds: Int = 3, meters: Int = 100) =
        inner.finishWorkout(user, startedAt, seconds, meters)
    fun profiles() = inner.profiles()
}
