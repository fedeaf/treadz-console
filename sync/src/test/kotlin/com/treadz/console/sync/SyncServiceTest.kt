package com.treadz.console.sync

import android.os.Looper
import com.treadz.console.sync.engine.SyncState
import com.treadz.console.sync.github.RepositoryRef
import com.treadz.console.sync.service.DefaultSyncService
import com.treadz.console.sync.service.SyncSetup
import com.treadz.console.userdata.Sex
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
class SyncServiceTest {

    private val github = FakeGitHub().apply { validTokens += TOKEN }
    private val repo = github.addRepo(FakeGitHub.Repo("fede", "treadz-data"))

    /** A device whose token is not set yet. */
    private fun device(name: String, seed: Long, generic: Boolean = true) =
        Device(name, github, seed, seedGenericProfile = generic).also { it.tokens.clear() }

    private fun awaitUntil(what: String, timeoutMs: Long = 5000, condition: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            shadowOf(Looper.getMainLooper()).idle()
            if (System.currentTimeMillis() > end) fail("timeout waiting for: $what")
            Thread.sleep(10)
        }
    }

    private fun mutations() = github.requests.count { it.startsWith("POST") || it.startsWith("PUT") || it.startsWith("PATCH") }

    @Test fun `a device with nothing configured is local only and does not touch the network`() {
        val a = device("tablet", 1)
        assertEquals(SyncSetup.NotConfigured, a.service.status.value.setup)
        assertNull(runBlocking { a.service.syncNow() })
        a.service.onAppOpened()
        assertTrue(github.requests.isEmpty())
        assertEquals(0, a.scheduler.pushes)
    }

    @Test fun `with one private repository the token is enough and the device is linked and synchronised`() {
        github.addRepo(FakeGitHub.Repo("fede", "my-website", isPrivate = false)) // public repositories are listed too: ignored
        val a = device("tablet", 1, generic = false)
        a.createUser("Ana")
        // (local data would need confirmation; this test is about a device without any)
        a.data.let { }
        val b = device("phone", 2)
        assertNull(runBlocking { b.service.configure("  $TOKEN\n") })
        val status = b.service.status.value
        assertEquals(SyncSetup.Linked, status.setup)
        assertEquals(RepositoryRef("fede", "treadz-data", "main"), status.repository)
        assertNotNull(status.lastSyncMillis)
        assertEquals(TOKEN, b.tokens.get())
        assertNull(status.error)
    }

    @Test fun `with several private repositories the user chooses from a list that has no public ones`() {
        github.addRepo(FakeGitHub.Repo("fede", "other-private"))
        github.addRepo(FakeGitHub.Repo("fede", "my-website", isPrivate = false))
        github.addRepo(FakeGitHub.Repo("fede", "old-private", archived = true))
        val a = device("tablet", 1)
        assertNull(runBlocking { a.service.configure(TOKEN) })
        val choosing = a.service.status.value
        assertEquals(SyncSetup.ChoosingRepository, choosing.setup)
        assertEquals(listOf("other-private", "treadz-data"), choosing.repositories.map { it.name }.sorted())
        assertNull(choosing.repository)
        assertEquals(0, mutations())

        assertNull(runBlocking { a.service.selectRepository(choosing.repositories.first { it.name == "treadz-data" }) })
        assertEquals(SyncSetup.Linked, a.service.status.value.setup)
        assertEquals("treadz-data", a.service.status.value.repository!!.name)
    }

    @Test fun `no private repository is a clear error and the token is not kept`() {
        github.repos.clear()
        github.addRepo(FakeGitHub.Repo("fede", "my-website", isPrivate = false))
        val a = device("tablet", 1)
        val error = runBlocking { a.service.configure(TOKEN) }
        assertEquals(SyncErrorCode.NoPrivateRepository, error!!.code)
        assertEquals(error, a.service.status.value.error)
        assertEquals(SyncSetup.NotConfigured, a.service.status.value.setup)
        assertNull(a.tokens.get())
    }

    @Test fun `a rejected token and a missing connection are reported and nothing is stored`() {
        val a = device("tablet", 1)
        assertEquals(SyncErrorCode.Unauthorized, runBlocking { a.service.configure("wrong") }!!.code)
        assertNull(a.tokens.get())
        assertEquals(SyncErrorCode.Unauthorized, runBlocking { a.service.configure("   ") }!!.code)
        github.offline = true
        assertEquals(SyncErrorCode.NoNetwork, runBlocking { a.service.configure(TOKEN) }!!.code)
        assertNull(a.tokens.get())
        assertEquals(SyncSetup.NotConfigured, a.service.status.value.setup)
    }

    @Test fun `first link with local data shows what would be uploaded and sends nothing until confirmed`() {
        // The repository already holds Beto, uploaded by another device.
        val other = Device("phone", github, 2, seedGenericProfile = false)
        val beto = other.createUser("Beto", Sex.Male)
        other.finishWorkout(beto, T0)
        other.sync()
        val commitsBefore = repo.log().size

        val a = device("tablet", 1, generic = false)
        val ana = a.createUser("Ana")
        a.addWeight(ana, LocalDate.of(2026, 10, 1), 61.5)
        a.addWeight(ana, LocalDate.of(2026, 10, 2), 61.0)
        a.finishWorkout(ana, T0 + 1000)
        a.finishWorkout(ana, T0 + 100_000)
        val mutationsBefore = mutations()

        assertNull(runBlocking { a.service.configure(TOKEN) })
        val status = a.service.status.value
        assertEquals(SyncSetup.AwaitingFirstLinkConfirmation, status.setup)
        val summary = status.firstLink!!
        val ana1 = summary.users.single()
        assertEquals("Ana", ana1.name)
        assertTrue(ana1.profile)
        assertEquals(2, ana1.weightEntries)
        assertEquals(2, ana1.workouts)
        assertEquals(2, summary.totalWorkouts)
        assertEquals(1, summary.remoteUsers)
        assertTrue(summary.hasUploads)
        // nothing was sent or merged
        assertEquals(mutationsBefore, mutations())
        assertEquals(commitsBefore, repo.log().size)
        assertEquals(listOf("Ana"), a.profiles().map { it.name })
        // not even a background upload is scheduled before the confirmation
        assertEquals(0, a.scheduler.pushes)
        assertNull(runBlocking { a.service.syncNow() })
        assertEquals(mutationsBefore, mutations())

        assertNull(runBlocking { a.service.confirmFirstLink() })
        assertEquals(SyncSetup.Linked, a.service.status.value.setup)
        assertNull(a.service.status.value.firstLink)
        // the union: both users, with their data, on both sides
        assertEquals(listOf("Ana", "Beto"), a.profiles().map { it.name })
        assertEquals(2, runBlocking { a.data.workouts.workouts(a.profiles().first { it.name == "Ana" }.id).first() }.size)
        assertEquals(1, runBlocking { a.data.workouts.workouts(a.profiles().first { it.name == "Beto" }.id).first() }.size)
        other.sync()
        assertEquals(listOf("Ana", "Beto"), other.profiles().map { it.name })
        assertEquals(2, repo.files().keys.count { it.contains("users/ana-") && it.endsWith(".json") && it.contains("/workouts/") })
    }

    @Test fun `cancelling the first link forgets the token and keeps the local data`() {
        val a = device("tablet", 1, generic = false)
        a.createUser("Ana")
        assertNull(runBlocking { a.service.configure(TOKEN) })
        assertEquals(SyncSetup.AwaitingFirstLinkConfirmation, a.service.status.value.setup)
        runBlocking { a.service.cancelFirstLink() }
        assertEquals(SyncSetup.NotConfigured, a.service.status.value.setup)
        assertNull(a.tokens.get())
        assertEquals(listOf("Ana"), a.profiles().map { it.name })
        assertTrue(repo.isEmpty)
        assertEquals(1, runBlocking { a.data.sync.pendingCount.first() })
    }

    @Test fun `a device with only the generic profile links without asking`() {
        val a = device("tablet", 1, generic = true)
        assertNull(runBlocking { a.service.configure(TOKEN) })
        assertEquals(SyncSetup.Linked, a.service.status.value.setup)
        assertTrue("nothing to upload, nothing sent", repo.isEmpty)
    }

    @Test fun `clearing removes the token and the link but not the data`() {
        val a = device("tablet", 1, generic = true)
        a.createUser("Ana")
        assertNull(runBlocking { a.service.configure(TOKEN) })
        runBlocking { a.service.confirmFirstLink() }
        runBlocking { a.service.clear() }
        assertEquals(SyncSetup.NotConfigured, a.service.status.value.setup)
        assertNull(a.tokens.get())
        assertEquals(SyncState(), runBlocking { a.state.load() })
        assertEquals(1, a.scheduler.cancels)
        assertTrue(a.profiles().any { it.name == "Ana" })
        assertTrue(repo.files().keys.any { it.endsWith("profile.json") })
    }

    @Test fun `the link survives a restart`() {
        val a = device("tablet", 1, generic = true)
        assertNull(runBlocking { a.service.configure(TOKEN) })
        val synced = a.service.status.value.lastSyncMillis
        assertNotNull(synced)
        a.tokens.set(TOKEN)

        val restarted = DefaultSyncService(a.client, a.engine, a.tokens, a.state, a.data.sync, a.programs, a.scheduler, CoroutineScope(SupervisorJob() + Dispatchers.Unconfined), a.clock)
        awaitUntil("restored") { restarted.status.value.setup == SyncSetup.Linked }
        assertEquals("treadz-data", restarted.status.value.repository!!.name)
        assertEquals(synced, restarted.status.value.lastSyncMillis)
    }

    @Test fun `changes are queued for the background upload and shown as pending until uploaded`() {
        val a = device("tablet", 1, generic = true)
        assertNull(runBlocking { a.service.configure(TOKEN) })
        val ana = a.createUser("Ana")
        awaitUntil("pending shown") { a.service.status.value.pendingCount == 1 }
        awaitUntil("push scheduled") { a.scheduler.pushes > 0 }
        val id = a.finishWorkout(ana, T0)
        awaitUntil("workout pending") { runBlocking { a.service.pendingWorkoutIds.first() } == setOf(id) }

        assertNull(runBlocking { a.service.syncNow() })
        awaitUntil("nothing pending") { a.service.status.value.pendingCount == 0 }
        assertEquals(emptySet<Long>(), runBlocking { a.service.pendingWorkoutIds.first() })
        assertFalse(a.service.status.value.isSyncing)
    }

    @Test fun `opening the app synchronises at most once a minute`() {
        val a = device("tablet", 1, generic = true)
        assertNull(runBlocking { a.service.configure(TOKEN) })
        val before = github.requests.size
        a.at(a.clock.nowMillis + 10_000).service.onAppOpened()
        assertEquals("too soon", before, github.requests.size)
        a.at(a.clock.nowMillis + 61_000).service.onAppOpened()
        awaitUntil("the foreground sync") { github.requests.size > before && !a.service.status.value.isSyncing }
    }

    @Test fun `a failed synchronisation is shown, keeps the data pending and schedules a retry when it may go away`() {
        val a = device("tablet", 1, generic = true)
        assertNull(runBlocking { a.service.configure(TOKEN) })
        a.createUser("Ana")
        awaitUntil("pending") { a.service.status.value.pendingCount == 1 }
        val pushes = a.scheduler.pushes
        github.offline = true
        assertEquals(SyncErrorCode.NoNetwork, runBlocking { a.service.syncNow() }!!.code)
        assertEquals(SyncErrorCode.NoNetwork, a.service.status.value.error!!.code)
        assertEquals(1, a.service.status.value.pendingCount)
        assertTrue(a.scheduler.pushes > pushes)
        github.offline = false
        assertNull(runBlocking { a.service.syncNow() })
        assertNull(a.service.status.value.error)
        awaitUntil("nothing pending") { a.service.status.value.pendingCount == 0 }
    }

    @Test fun `a revoked token is reported as such and the setup is kept so a new token can be entered`() {
        val a = device("tablet", 1, generic = true)
        assertNull(runBlocking { a.service.configure(TOKEN) })
        a.createUser("Ana")
        github.validTokens.clear()
        assertEquals(SyncErrorCode.Unauthorized, runBlocking { a.service.syncNow() }!!.code)
        assertEquals(SyncSetup.Linked, a.service.status.value.setup)
        github.validTokens += "new-token"
        assertNull(runBlocking { a.service.configure("new-token") })
        assertNull(a.service.status.value.error)
        assertTrue(repo.files().keys.any { it.endsWith("profile.json") })
    }

    // ---- a failed configure never loses a working setup ----

    private fun linkedDevice(): Device {
        val a = device("tablet", 1, generic = true)
        assertNull(runBlocking { a.service.configure(TOKEN) })
        assertEquals(SyncSetup.Linked, a.service.status.value.setup)
        return a
    }

    @Test fun `a bad new token on a linked device keeps the working token, the link and the syncing`() {
        val a = linkedDevice()
        val linkedStatus = a.service.status.value
        val stateBefore = runBlocking { a.state.load() }

        assertEquals(SyncErrorCode.Unauthorized, runBlocking { a.service.configure("not-a-token") }!!.code)
        assertEquals(TOKEN, a.tokens.get())
        assertEquals(SyncSetup.Linked, a.service.status.value.setup)
        assertEquals(linkedStatus.repository, a.service.status.value.repository)
        assertEquals(SyncErrorCode.Unauthorized, a.service.status.value.error!!.code)
        assertEquals(stateBefore, runBlocking { a.state.load() })

        github.offline = true
        assertEquals(SyncErrorCode.NoNetwork, runBlocking { a.service.configure("another-token") }!!.code)
        assertEquals(TOKEN, a.tokens.get())
        assertEquals(SyncSetup.Linked, a.service.status.value.setup)
        github.offline = false
        assertEquals(SyncErrorCode.Unauthorized, runBlocking { a.service.configure("   ") }!!.code)
        assertEquals(TOKEN, a.tokens.get())

        // it keeps syncing with the old token
        a.createUser("Ana")
        assertNull(runBlocking { a.service.syncNow() })
        assertNull(a.service.status.value.error)
        assertTrue(repo.files().keys.any { it.endsWith("profile.json") })
        assertEquals(0, a.scheduler.cancels)
    }

    @Test fun `a token with no private repository on a linked device keeps the previous one`() {
        val a = linkedDevice()
        github.validTokens += "empty-token"
        github.tokenRepos["empty-token"] = emptySet()
        assertEquals(SyncErrorCode.NoPrivateRepository, runBlocking { a.service.configure("empty-token") }!!.code)
        assertEquals(TOKEN, a.tokens.get())
        assertEquals(SyncSetup.Linked, a.service.status.value.setup)
    }

    @Test fun `a new working token for the same repository replaces the old one and the device stays linked`() {
        val a = linkedDevice()
        github.addRepo(FakeGitHub.Repo("fede", "other-private"))
        github.validTokens += "new-token"
        // the new token sees both repositories, the linked one among them: no choice, no new confirmation
        val mutationsBefore = mutations()
        assertNull(runBlocking { a.service.configure("new-token") })
        assertEquals("new-token", a.tokens.get())
        assertEquals(SyncSetup.Linked, a.service.status.value.setup)
        assertEquals("treadz-data", a.service.status.value.repository!!.name)
        assertEquals(emptyList<RepositoryRef>(), a.service.status.value.repositories)
        assertNull(a.service.status.value.error)
        assertEquals(mutationsBefore, mutations())
        assertNull(runBlocking { a.service.syncNow() })
    }

    @Test fun `a token for another private repository is rejected on a linked device and nothing changes`() {
        val a = linkedDevice()
        github.addRepo(FakeGitHub.Repo("fede", "other-private"))
        github.validTokens += "other-token"
        github.tokenRepos["other-token"] = setOf("other-private")
        val stateBefore = runBlocking { a.state.load() }
        val requestsBefore = github.requests.size

        val error = runBlocking { a.service.configure("other-token") }
        assertEquals(SyncErrorCode.RepositoryNotFound, error!!.code)
        assertEquals(error, a.service.status.value.error)
        assertEquals(TOKEN, a.tokens.get())
        assertEquals(SyncSetup.Linked, a.service.status.value.setup)
        assertEquals("treadz-data", a.service.status.value.repository!!.name)
        assertEquals(stateBefore, runBlocking { a.state.load() })
        // only the validation listing was made: nothing was read from or written to the other repository
        assertEquals(listOf("GET /user/repos"), github.requests.drop(requestsBefore))

        // the old token still works; and after unlinking the same token can be used to link the other repository
        a.createUser("Ana")
        assertNull(runBlocking { a.service.syncNow() })
        runBlocking { a.service.clear() }
        assertNull(runBlocking { a.service.configure("other-token") })
        assertEquals("other-private", a.service.status.value.repository!!.name)
    }

    @Test fun `a failed token during the setup keeps the setup as it was`() {
        github.addRepo(FakeGitHub.Repo("fede", "other-private"))
        val a = device("tablet", 1)
        assertNull(runBlocking { a.service.configure(TOKEN) })
        assertEquals(SyncSetup.ChoosingRepository, a.service.status.value.setup)
        assertEquals(SyncErrorCode.Unauthorized, runBlocking { a.service.configure("wrong") }!!.code)
        assertEquals(TOKEN, a.tokens.get())
        assertEquals(SyncSetup.ChoosingRepository, a.service.status.value.setup)
        assertEquals(2, a.service.status.value.repositories.size)
        // and the user can still choose
        assertNull(runBlocking { a.service.selectRepository(a.service.status.value.repositories.first { it.name == "treadz-data" }) })
        assertEquals(SyncSetup.Linked, a.service.status.value.setup)
    }

    @Test fun `a failed first token leaves no token and no setup, and the next attempt clears the old error`() {
        val a = device("tablet", 1)
        assertEquals(SyncErrorCode.Unauthorized, runBlocking { a.service.configure("wrong") }!!.code)
        assertNull(a.tokens.get())
        assertEquals(SyncErrorCode.Unauthorized, a.service.status.value.error!!.code)
        assertEquals(SyncSetup.NotConfigured, a.service.status.value.setup)
        github.offline = true
        runBlocking { a.service.configure(TOKEN) }
        assertEquals(SyncErrorCode.NoNetwork, a.service.status.value.error!!.code)
        github.offline = false
        assertNull(runBlocking { a.service.configure(TOKEN) })
        assertNull(a.service.status.value.error)
    }

    // ---- cancelSetup ----

    @Test fun `cancelSetup while choosing a repository forgets the token and uploads nothing`() {
        github.addRepo(FakeGitHub.Repo("fede", "other-private"))
        val a = device("tablet", 1, generic = false)
        a.createUser("Ana")
        assertNull(runBlocking { a.service.configure(TOKEN) })
        assertEquals(SyncSetup.ChoosingRepository, a.service.status.value.setup)
        val requestsBefore = github.requests.size
        runBlocking { a.service.cancelSetup() }
        assertEquals(SyncSetup.NotConfigured, a.service.status.value.setup)
        assertEquals(emptyList<RepositoryRef>(), a.service.status.value.repositories)
        assertNull(a.tokens.get())
        assertEquals(SyncState(), runBlocking { a.state.load() })
        assertEquals(0, mutations())
        assertEquals(requestsBefore, github.requests.size)
        assertTrue(repo.isEmpty)
        assertEquals(listOf("Ana"), a.profiles().map { it.name })
        assertEquals(0, a.scheduler.pushes)
    }

    @Test fun `cancelSetup while waiting for the first link confirmation forgets the token and uploads nothing`() {
        val a = device("tablet", 1, generic = false)
        a.createUser("Ana")
        assertNull(runBlocking { a.service.configure(TOKEN) })
        assertEquals(SyncSetup.AwaitingFirstLinkConfirmation, a.service.status.value.setup)
        runBlocking { a.service.cancelSetup() }
        assertEquals(SyncSetup.NotConfigured, a.service.status.value.setup)
        assertNull(a.service.status.value.firstLink)
        assertNull(a.service.status.value.repository)
        assertNull(a.tokens.get())
        assertEquals(SyncState(), runBlocking { a.state.load() })
        assertEquals(0, mutations())
        assertTrue(repo.isEmpty)
        assertEquals(0, a.scheduler.pushes)
        assertEquals(listOf("Ana"), a.profiles().map { it.name })
        // a later attempt starts from scratch
        assertNull(runBlocking { a.service.configure(TOKEN) })
        assertEquals(SyncSetup.AwaitingFirstLinkConfirmation, a.service.status.value.setup)
    }

    @Test fun `cancelSetup clears a leftover error when not configured and does nothing when linked`() {
        val a = device("tablet", 1)
        runBlocking { a.service.configure("wrong") }
        assertNotNull(a.service.status.value.error)
        runBlocking { a.service.cancelSetup() }
        assertNull(a.service.status.value.error)
        assertEquals(SyncSetup.NotConfigured, a.service.status.value.setup)

        val linked = linkedDevice()
        runBlocking { linked.service.cancelSetup() }
        runBlocking { linked.service.cancelFirstLink() }
        assertEquals(SyncSetup.Linked, linked.service.status.value.setup)
        assertEquals(TOKEN, linked.tokens.get())
        assertEquals(0, linked.scheduler.cancels)
    }

    @Test fun `clear also drops the leftover error`() {
        val a = linkedDevice()
        github.offline = true
        runBlocking { a.service.syncNow() }
        assertNotNull(a.service.status.value.error)
        runBlocking { a.service.clear() }
        assertNull(a.service.status.value.error)
        assertEquals(SyncSetup.NotConfigured, a.service.status.value.setup)
    }
}
