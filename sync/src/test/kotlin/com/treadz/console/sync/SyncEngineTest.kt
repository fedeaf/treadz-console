package com.treadz.console.sync

import com.treadz.console.userdata.DataResult
import com.treadz.console.userdata.ProfileInput
import com.treadz.console.userdata.Sex
import com.treadz.console.userdata.UserProfile
import com.treadz.console.userdata.sync.ProgramDocument
import com.treadz.console.userdata.sync.RecordVersion
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
import java.time.LocalDate

@RunWith(RobolectricTestRunner::class)
class SyncEngineTest {

    private val github = FakeGitHub().apply { validTokens += TOKEN }
    private val repo = github.addRepo(FakeGitHub.Repo("fede", "treadz-data"))

    private fun device(name: String, seed: Long, generic: Boolean = false) = Device(name, github, seed, seedGenericProfile = generic)

    private val day = 86_400_000L

    /** Files of the repository's main branch. */
    private fun paths() = repo.files().keys.toList().sorted()

    private fun userFolder(name: String) = paths().first { it.startsWith("users/$name-") && it.endsWith("/profile.json") }.removeSuffix("profile.json")

    // ------------------------------------------------------------ empty repository, first push

    @Test fun `an empty repository is bootstrapped with the README and the data goes in a second commit`() {
        val a = device("tablet", 1)
        val ana = a.createUser("Ana")
        a.addWeight(ana, LocalDate.of(2026, 10, 1), 61.5)
        a.finishWorkout(ana, T0, seconds = 3, meters = 100)

        val report = a.sync()

        assertEquals(1, report.attempts)
        assertNotNull(report.commitSha)
        assertEquals(2, repo.log().size)
        assertEquals("Initialise TreadZ data repository", repo.log().first())
        val folder = userFolder("ana")
        assertTrue(folder.matches(Regex("users/ana-[0-9a-f-]{36}/")))
        assertEquals(
            listOf("README.md", "${folder}profile.json", "${folder}weight.csv", "${folder}workouts/2026/10/20261008_004000.csv", "${folder}workouts/2026/10/20261008_004000.json"),
            paths().map { it }.sorted().let { p -> p },
        )
        assertTrue(repo.text("README.md")!!.contains("Deletions (tombstones)"))
        assertEquals(0, runBlocking { a.data.sync.pendingCount.first() })
    }

    @Test fun `the commit message of a single workout is readable`() {
        val a = device("tablet", 1)
        val ana = a.createUser("Fede")
        a.sync()
        a.finishWorkout(ana, T0 + 3 * day, seconds = 45 * 60, meters = 5200)
        a.sync()
        assertEquals("Fede · workout 2026-10-11 00:40 · 45 min · 5.2 km", repo.log().last())
        a.addWeight(ana, LocalDate.of(2026, 10, 1), 61.5)
        a.sync()
        assertEquals("Fede · weight 2026-10-01 · 61.5 kg", repo.log().last())
    }

    @Test fun `nothing pending and an empty repository sends nothing`() {
        val a = device("tablet", 1, generic = true)
        val report = a.sync()
        assertNull(report.commitSha)
        assertTrue(repo.isEmpty)
        assertEquals(0, github.requests.count { it.startsWith("PUT") || it.startsWith("POST") })
    }

    @Test fun `a second sync without changes only looks at the branch`() {
        val a = device("tablet", 1)
        a.createUser("Ana")
        a.sync()
        val before = github.requests.size
        val report = a.sync()
        assertNull(report.commitSha)
        assertEquals(listOf("GET /repos/fede/treadz-data/git/ref/heads/main"), github.requests.drop(before))
    }

    // ------------------------------------------------------------ what is not uploaded

    @Test fun `the generic profile is not uploaded until it is personalised`() {
        val a = device("tablet", 1, generic = true)
        a.sync()
        assertTrue(repo.isEmpty)
        val generic = a.profiles().single()
        runBlocking { a.data.profiles.personalize(generic.id, ProfileInput("Ana", Sex.Female, 168, LocalDate.of(1986, 1, 1)), 61.0) }
        a.sync()
        assertTrue(paths().any { it.endsWith("/profile.json") })
        assertTrue(repo.text(paths().first { it.endsWith("weight.csv") })!!.contains("61.0"))
    }

    @Test fun `a workout in progress or without an owner is not uploaded`() {
        val a = device("tablet", 1)
        val ana = a.createUser("Ana")
        val store = a.data.workouts as com.treadz.console.userdata.workout.WorkoutSessionStore
        runBlocking { store.begin(ana.id, T0) }
        a.finishWorkout(null, T0 + day)
        a.sync()
        assertTrue(paths().none { it.contains("/workouts/") })
        // when the orphan is given to a user it goes up
        val orphan = runBlocking { a.data.workouts.unassignedWorkouts().first() }.single()
        runBlocking { a.data.workouts.assign(orphan.id, ana.id) }
        a.sync()
        assertEquals(2, paths().count { it.contains("/workouts/") })
    }

    // ------------------------------------------------------------ two devices

    @Test fun `data goes from one device to another and the second sync downloads nothing`() {
        val a = device("tablet", 1)
        val ana = a.createUser("Ana")
        a.addWeight(ana, LocalDate.of(2026, 10, 1), 61.5)
        a.addWeight(ana, LocalDate.of(2026, 10, 5), 61.0)
        runBlocking { a.data.weights.setTargetWeight(ana.id, 58.0) }
        a.finishWorkout(ana, T0, seconds = 4, meters = 150)
        a.sync()

        val b = device("phone", 2, generic = true)
        val report = b.sync()
        assertEquals(0, report.uploaded)
        val remoteAna = b.profiles().single()
        assertEquals("Ana", remoteAna.name)
        assertFalse("the stray generic profile makes way", remoteAna.isGeneric)
        assertEquals(null, runBlocking { b.data.profiles.activeUser.first() })
        assertEquals(listOf(61.5, 61.0), runBlocking { b.data.weights.entries(remoteAna.id).first() }.map { it.weightKg })
        assertEquals(58.0, runBlocking { b.data.weights.targetWeightKg(remoteAna.id).first() }!!, 0.0)
        val workout = runBlocking { b.data.workouts.workouts(remoteAna.id).first() }.single()
        assertEquals(T0, workout.startedAtMillis)
        assertEquals(150, workout.stats.distanceMeters)
        assertEquals(4, runBlocking { b.data.workouts.detail(workout.id)!!.samples }.size)
        // what came from the repository is not pending, so it is not sent back
        assertEquals(0, runBlocking { b.data.sync.pendingCount.first() })
        val commitsBefore = repo.log().size
        val blobGets = github.count("GET /repos/fede/treadz-data/git/blobs")
        assertNull(b.sync().commitSha)
        assertEquals(commitsBefore, repo.log().size)
        assertEquals(blobGets, github.count("GET /repos/fede/treadz-data/git/blobs"))
    }

    @Test fun `only the files that changed are downloaded`() {
        val a = device("tablet", 1)
        val ana = a.createUser("Ana")
        a.finishWorkout(ana, T0)
        a.finishWorkout(ana, T0 + day)
        a.sync()
        val b = device("phone", 2)
        b.sync()
        a.finishWorkout(ana, T0 + 2 * day)
        a.sync()

        val before = github.count("GET /repos/fede/treadz-data/git/blobs")
        val report = b.sync()
        assertEquals(1, report.downloaded)
        assertEquals("the new workout: its summary and its samples", 2, github.count("GET /repos/fede/treadz-data/git/blobs") - before)
        assertEquals(3, runBlocking { b.data.workouts.workouts(b.profiles().single().id).first() }.size)
    }

    @Test fun `last writer wins in both directions`() {
        val a = device("tablet", 1)
        val ana = a.createUser("Ana")
        a.sync()
        val b = device("phone", 2)
        b.sync()
        val anaB = b.profiles().single()

        // B edits first, A edits later: A wins
        b.at(T0 + 1_000)
        runBlocking { b.data.profiles.update(anaB.id, edit(anaB, heightCm = 150)) }
        a.at(T0 + 2_000)
        runBlocking { a.data.profiles.update(ana.id, edit(ana, heightCm = 190)) }
        b.sync()
        a.sync()
        b.sync()
        assertEquals(190, b.profiles().single().heightCm)
        assertEquals(190, a.profiles().single().heightCm)

        // now A edits first and B later: B wins
        a.at(T0 + 3_000)
        runBlocking { a.data.profiles.update(ana.id, edit(ana, heightCm = 171)) }
        b.at(T0 + 4_000)
        runBlocking { b.data.profiles.update(anaB.id, edit(anaB, heightCm = 172)) }
        b.sync()
        a.sync()
        b.sync()
        assertEquals(172, a.profiles().single().heightCm)
        assertEquals(172, b.profiles().single().heightCm)
    }

    private fun edit(p: UserProfile, heightCm: Int = p.heightCm, name: String = p.name) =
        ProfileInput(name, p.sex, heightCm, p.birthDate, p.restingHeartRateBpm, p.maxHeartRateBpm, p.fitnessLevel, p.goal)

    @Test fun `different records edited on two devices are both kept`() {
        val a = device("tablet", 1)
        val ana = a.createUser("Ana")
        a.addWeight(ana, LocalDate.of(2026, 10, 1), 61.5)
        a.sync()
        val b = device("phone", 2)
        b.sync()
        val anaB = b.profiles().single()

        a.addWeight(ana, LocalDate.of(2026, 10, 2), 61.2)
        b.addWeight(anaB, LocalDate.of(2026, 10, 3), 61.0)
        a.finishWorkout(ana, T0)
        b.finishWorkout(anaB, T0 + day)
        a.sync()
        b.sync() // pushes after pulling A's changes into its weight log
        a.sync()

        for (device in listOf(a, b)) {
            val id = device.profiles().first { it.name == "Ana" }.id
            assertEquals(listOf(61.5, 61.2, 61.0), runBlocking { device.data.weights.entries(id).first() }.map { it.weightKg })
            assertEquals(2, runBlocking { device.data.workouts.workouts(id).first() }.size)
        }
    }

    // ------------------------------------------------------------ deletions

    @Test fun `deleting a workout leaves a tombstone, removes its samples and reaches the other device`() {
        val a = device("tablet", 1)
        val ana = a.createUser("Ana")
        val id = a.finishWorkout(ana, T0)
        a.sync()
        val b = device("phone", 2)
        b.sync()

        a.at(T0 + 10_000)
        runBlocking { a.data.workouts.delete(id) }
        a.sync()
        val json = paths().first { it.endsWith(".json") && it.contains("/workouts/") }
        assertTrue(repo.text(json)!!.contains("\"deleted\": true"))
        assertFalse(repo.text(json)!!.contains("stats"))
        assertTrue("the samples file is removed", paths().none { it.endsWith(".csv") && it.contains("/workouts/") })

        b.sync()
        assertTrue(runBlocking { b.data.workouts.workouts(b.profiles().single().id).first() }.isEmpty())
        assertEquals(0, runBlocking { b.data.sync.pendingCount.first() })
        // and a device that joins later does not get the workout back
        val c = device("watch", 3)
        c.sync()
        assertTrue(runBlocking { c.data.workouts.workouts(c.profiles().single().id).first() }.isEmpty())
    }

    @Test fun `deleting a weight entry keeps a tombstone row in the weight log`() {
        val a = device("tablet", 1)
        val ana = a.createUser("Ana")
        a.addWeight(ana, LocalDate.of(2026, 10, 1), 61.5)
        val drop = (a.addWeight(ana, LocalDate.of(2026, 10, 2), 70.0) as DataResult.Success).value
        a.sync()
        val b = device("phone", 2)
        b.sync()
        a.at(T0 + 10_000)
        runBlocking { a.data.weights.delete(drop.id) }
        a.sync()
        val csv = repo.text(paths().first { it.endsWith("weight.csv") })!!
        val rows = csv.trim().lines()
        assertEquals("uuid,date,weight_kg,deleted,updated_at,updated_by", rows.first())
        assertEquals(3, rows.size)
        assertTrue(rows.any { it.contains(",,,true,") })
        assertFalse(csv.contains("70"))
        b.sync()
        assertEquals(listOf(61.5), runBlocking { b.data.weights.entries(b.profiles().single().id).first() }.map { it.weightKg })
    }

    @Test fun `deleting a user leaves only its tombstone and removes the user from the other devices`() {
        val a = device("tablet", 1)
        val ana = a.createUser("Ana")
        val beto = a.createUser("Beto", Sex.Male)
        a.addWeight(ana, LocalDate.of(2026, 10, 1), 61.5)
        a.finishWorkout(ana, T0)
        a.finishWorkout(beto, T0)
        a.sync()
        val b = device("phone", 2)
        b.sync()
        runBlocking { b.data.profiles.setActiveUser(b.profiles().first { it.name == "Ana" }.id) }

        a.at(T0 + 10_000)
        runBlocking { a.data.profiles.delete(ana.id) }
        a.sync()
        val folder = userFolder("ana")
        assertEquals(listOf("${folder}profile.json"), paths().filter { it.startsWith(folder) })
        val tombstone = repo.text("${folder}profile.json")!!
        assertTrue(tombstone.contains("\"deleted\": true"))
        assertFalse(tombstone.contains("height_cm"))

        b.sync()
        assertEquals(listOf("Beto"), b.profiles().map { it.name })
        assertNull("the active user was the deleted one", runBlocking { b.data.profiles.activeUser.first() })
        assertEquals(1, runBlocking { b.data.workouts.workouts(b.profiles().single().id).first() }.size)
    }

    // ------------------------------------------------------------ rename

    @Test fun `renaming a user renames the folder without uploading its files again`() {
        val a = device("tablet", 1)
        val ana = a.createUser("Ana")
        a.addWeight(ana, LocalDate.of(2026, 10, 1), 61.5)
        a.finishWorkout(ana, T0)
        a.sync()
        val b = device("phone", 2)
        b.sync()
        val oldFolder = userFolder("ana")
        val uuid = oldFolder.removePrefix("users/ana-").removeSuffix("/")

        a.at(T0 + 10_000)
        runBlocking { a.data.profiles.update(ana.id, edit(ana, name = "Ana María")) }
        val blobsBefore = repo.blobs.size
        a.sync()

        assertEquals("only the new profile.json is a new blob", blobsBefore + 1, repo.blobs.size)
        val newFolder = "users/ana-maria-$uuid/"
        assertTrue(paths().none { it.startsWith(oldFolder) })
        assertEquals(
            listOf("${newFolder}profile.json", "${newFolder}weight.csv", "${newFolder}workouts/2026/10/20261008_004000.csv", "${newFolder}workouts/2026/10/20261008_004000.json"),
            paths().filter { it.startsWith("users/") },
        )
        assertTrue(repo.text("${newFolder}profile.json")!!.contains("Ana María"))

        val downloads = github.count("GET /repos/fede/treadz-data/git/blobs")
        b.sync()
        assertEquals("only the profile is downloaded: the other files just moved", 1, github.count("GET /repos/fede/treadz-data/git/blobs") - downloads)
        assertEquals("Ana María", b.profiles().single().name)
        // later changes of the workout list of the renamed user go to the new folder
        b.finishWorkout(b.profiles().single(), T0 + day)
        b.sync()
        assertEquals(2, paths().count { it.startsWith(newFolder) && it.contains("/workouts/") && it.endsWith(".json") })
        a.sync()
        assertEquals(2, runBlocking { a.data.workouts.workouts(ana.id).first() }.size)
    }

    // ------------------------------------------------------------ merging two devices that start apart

    @Test fun `users with different identities coexist`() {
        val a = device("tablet", 1)
        a.createUser("Ana")
        a.sync()
        val b = device("phone", 2)
        b.createUser("Ana") // the same name, created independently: another uuid
        b.createUser("Beto", Sex.Male)
        b.sync()
        a.sync()
        assertEquals(listOf("Ana", "Ana", "Beto"), a.profiles().map { it.name })
        assertEquals(3, paths().count { it.endsWith("/profile.json") })
    }

    // ------------------------------------------------------------ races

    @Test fun `a lost race against another device is retried after pulling its changes`() {
        val a = device("tablet", 1)
        val ana = a.createUser("Ana")
        a.sync()
        val b = device("phone", 2)
        b.sync()
        val anaB = b.profiles().single()

        a.addWeight(ana, LocalDate.of(2026, 10, 1), 61.5)
        // B pushes between A's commit and A's branch update
        github.beforeBranchUpdate = {
            b.addWeight(anaB, LocalDate.of(2026, 10, 2), 70.0)
            b.sync()
        }
        val report = a.sync()

        assertEquals(2, report.attempts)
        assertEquals(listOf(61.5, 70.0), runBlocking { a.data.weights.entries(ana.id).first() }.map { it.weightKg })
        val csv = repo.text(paths().first { it.endsWith("weight.csv") })!!
        assertTrue(csv.contains("61.5") && csv.contains("70.0"))
        assertEquals(0, runBlocking { a.data.sync.pendingCount.first() })
        // history is linear: nobody was overwritten
        assertEquals(4, repo.log().size)
    }

    @Test fun `when the race is lost every time the sync gives up with a conflict and keeps the changes pending`() {
        val a = Device("tablet", github, 1, seedGenericProfile = false, maxAttempts = 3)
        val ana = a.createUser("Ana")
        a.sync()
        val b = device("phone", 2)
        b.sync()
        a.addWeight(ana, LocalDate.of(2026, 10, 1), 61.5)
        var n = 0
        a.beforeRequest = { request ->
            if (request.method == "PATCH") {
                // another device sneaks a commit in before every update
                b.addWeight(b.profiles().single(), LocalDate.of(2026, 9, 1).plusDays((n++).toLong()), 70.0)
                b.sync()
            }
        }
        val error = errorOf { a.sync() }
        assertEquals(SyncErrorCode.Conflict, error.code)
        assertTrue(error.code.isRetryable)
        assertEquals(3, n)
        assertEquals("the change is still pending", 1, runBlocking { a.data.sync.pendingCount.first() })
        // once the other device calms down, it goes through, together with what the other device uploaded
        a.beforeRequest = null
        a.sync()
        assertEquals(0, runBlocking { a.data.sync.pendingCount.first() })
        assertEquals(4, runBlocking { a.data.weights.entries(ana.id).first() }.size)
    }

    // ------------------------------------------------------------ programs and odd files

    @Test fun `programs travel as opaque documents and their deletion is a tombstone`() {
        val a = device("tablet", 1)
        val ana = a.createUser("Ana")
        a.sync()
        val userUuid = userFolder("ana").removePrefix("users/ana-").removeSuffix("/")
        a.programs.put(ProgramDocument("00000000-0000-0000-0000-0000000000aa", userUuid, RecordVersion(T0, "tablet"), false, """{"name":"4x4","blocks":[1,2,3]}"""))
        a.sync()
        val path = paths().first { it.contains("/programs/") }
        assertTrue(path.endsWith("/programs/00000000-0000-0000-0000-0000000000aa.json"))
        assertTrue(repo.text(path)!!.contains("\"program\": {\"name\":\"4x4\",\"blocks\":[1,2,3]}"))

        val b = device("phone", 2)
        b.sync()
        assertEquals("""{"name":"4x4","blocks":[1,2,3]}""", b.programs.all().single().json.replace(" ", ""))

        a.programs.put(ProgramDocument("00000000-0000-0000-0000-0000000000aa", userUuid, RecordVersion(T0 + 1000, "tablet"), true, ""))
        a.sync()
        assertTrue(repo.text(path)!!.contains("\"deleted\": true"))
        b.sync()
        assertTrue(b.programs.all().single().deleted)
    }

    @Test fun `a damaged remote file is skipped and the rest is applied`() {
        val a = device("tablet", 1)
        a.createUser("Ana")
        a.createUser("Beto", Sex.Male)
        a.sync()
        // someone breaks Ana's profile in the repository
        val broken = paths().first { it.contains("users/ana-") && it.endsWith("profile.json") }
        val blob = github.repos.first().let { r ->
            val sha = "b".repeat(40)
            r.blobs[sha] = "{ not json".toByteArray()
            sha
        }
        val head = repo.refs.getValue("main")
        val commit = repo.commits.getValue(head)
        val newTree = "t".repeat(40)
        repo.trees[newTree] = repo.trees.getValue(commit.tree) + (broken to blob)
        val newCommit = "c".repeat(40)
        repo.commits[newCommit] = FakeGitHub.Commit(newTree, listOf(head), "break it")
        repo.refs["main"] = newCommit

        val b = device("phone", 2)
        b.sync()
        assertEquals(listOf("Beto"), b.profiles().map { it.name })
    }

    @Test fun `files outside the layout are ignored and a vanished file never deletes local data`() {
        val a = device("tablet", 1)
        val ana = a.createUser("Ana")
        a.addWeight(ana, LocalDate.of(2026, 10, 1), 61.5)
        a.sync()
        val b = device("phone", 2)
        b.sync()
        // somebody removes weight.csv by hand from the repository, and adds a stray file
        val head = repo.refs.getValue("main")
        val commit = repo.commits.getValue(head)
        val files = repo.trees.getValue(commit.tree).filterKeys { !it.endsWith("weight.csv") } + ("notes/todo.txt" to repo.trees.getValue(commit.tree).values.first())
        repo.trees["x".repeat(40)] = files
        repo.commits["y".repeat(40)] = FakeGitHub.Commit("x".repeat(40), listOf(head), "tidy")
        repo.refs["main"] = "y".repeat(40)
        b.sync()
        assertEquals(1, runBlocking { b.data.weights.entries(b.profiles().single().id).first() }.size)
    }

    @Test fun `two workouts started in the same second get different files`() {
        val a = device("tablet", 1)
        val ana = a.createUser("Ana")
        a.finishWorkout(ana, T0)
        a.finishWorkout(ana, T0 + 500)
        a.sync()
        assertEquals(2, paths().count { it.contains("/workouts/") && it.endsWith(".json") })
        val b = device("phone", 2)
        b.sync()
        assertEquals(2, runBlocking { b.data.workouts.workouts(b.profiles().single().id).first() }.size)
    }

    @Test fun `a workout moved to another user moves its files`() {
        val a = device("tablet", 1)
        val ana = a.createUser("Ana")
        val beto = a.createUser("Beto", Sex.Male)
        val id = a.finishWorkout(ana, T0)
        a.sync()
        a.at(T0 + 10_000)
        runBlocking { a.data.workouts.assign(id, beto.id) }
        a.sync()
        assertTrue(paths().none { it.startsWith(userFolder("ana")) && it.contains("/workouts/") })
        assertEquals(2, paths().count { it.startsWith(userFolder("beto")) && it.contains("/workouts/") })
        val b = device("phone", 2)
        b.sync()
        val betoB = b.profiles().first { it.name == "Beto" }
        assertEquals(1, runBlocking { b.data.workouts.workouts(betoB.id).first() }.size)
        assertEquals(0, runBlocking { b.data.workouts.workouts(b.profiles().first { it.name == "Ana" }.id).first() }.size)
    }

    // ------------------------------------------------------------ errors

    private fun errorOf(block: () -> Unit): SyncError = try {
        block()
        fail("expected a SyncException")
        throw IllegalStateException()
    } catch (e: SyncException) {
        e.error
    }

    @Test fun `failures are mapped to typed errors`() {
        val a = device("tablet", 1)
        a.createUser("Ana")
        a.sync()
        a.createUser("Beto", Sex.Male)

        github.offline = true
        assertEquals(SyncErrorCode.NoNetwork, errorOf { a.sync() }.code)
        github.offline = false

        a.tokens.set("revoked")
        assertEquals(SyncErrorCode.Unauthorized, errorOf { a.sync() }.code)
        a.tokens.set(TOKEN)

        github.failNext(503, "Service Unavailable")
        assertEquals(SyncErrorCode.ServerError, errorOf { a.sync() }.code)

        github.failNext(403, "API rate limit exceeded", mapOf("x-ratelimit-remaining" to "0", "x-ratelimit-reset" to ((T0 / 1000) + 90).toString()))
        val limited = errorOf { a.sync() }
        assertEquals(SyncErrorCode.RateLimited, limited.code)
        assertEquals(90_000L, limited.retryAfterMillis)

        github.failNext(429, "Too many", mapOf("retry-after" to "30"))
        assertEquals(30_000L, errorOf { a.sync() }.retryAfterMillis)

        github.truncateTrees = true
        a.state.let { runBlocking { it.save(it.load().copy(headCommitSha = null)) } }
        assertEquals(SyncErrorCode.RepositoryTooLarge, errorOf { a.sync() }.code)
        github.truncateTrees = false

        // after all that nothing was lost: the change is still pending and goes up
        assertEquals(1, runBlocking { a.data.sync.pendingCount.first() })
        a.sync()
        assertEquals(0, runBlocking { a.data.sync.pendingCount.first() })
    }

    @Test fun `a token without write access is reported on the first push`() {
        val a = device("tablet", 1)
        a.createUser("Ana")
        a.sync()
        github.readOnly = true
        a.createUser("Beto", Sex.Male)
        assertEquals(SyncErrorCode.NoWriteAccess, errorOf { a.sync() }.code)
        assertEquals("the changes stay pending", 1, runBlocking { a.data.sync.pendingCount.first() })
    }

    @Test fun `a token without write access cannot even bootstrap an empty repository`() {
        github.readOnly = true
        val a = device("tablet", 1)
        a.createUser("Ana")
        assertEquals(SyncErrorCode.NoWriteAccess, errorOf { a.sync() }.code)
    }

    @Test fun `nothing a failing sync reports contains the token`() {
        val a = device("tablet", 1)
        a.createUser("Ana")
        val texts = mutableListOf<String>()
        fun record(block: () -> Unit) = try {
            block()
        } catch (e: Throwable) {
            generateSequence(e) { it.cause }.forEach { texts += it.toString(); texts += it.stackTraceToString() }
        }
        github.offline = true
        record { a.sync() }
        github.offline = false
        a.tokens.set("rotated-token-value")
        record { a.sync() }
        a.tokens.set(TOKEN)
        github.failNext(500, "boom")
        record { a.sync() }
        github.readOnly = true
        record { a.sync() }
        texts += a.tokens.toString()
        texts += a.client.toString()
        assertTrue(texts.isNotEmpty())
        for (t in texts) {
            assertFalse(t.contains(TOKEN))
            assertFalse(t.contains("rotated-token-value"))
            assertFalse(t.contains("Bearer"))
        }
    }

    @Test fun `a mid-sync failure does not lose or duplicate anything`() {
        val a = device("tablet", 1)
        val ana = a.createUser("Ana")
        a.addWeight(ana, LocalDate.of(2026, 10, 1), 61.5)
        a.sync()
        a.addWeight(ana, LocalDate.of(2026, 10, 2), 62.0)
        github.failNext(500, "boom", pathRegex = Regex("POST .*/git/commits"))
        errorOf { a.sync() }
        assertEquals(1, runBlocking { a.data.sync.pendingCount.first() })
        a.sync()
        assertEquals(0, runBlocking { a.data.sync.pendingCount.first() })
        val csv = repo.text(paths().first { it.endsWith("weight.csv") })!!
        assertEquals(3, csv.trim().lines().size)
    }
}
