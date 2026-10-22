package com.treadz.console.sync

import com.treadz.console.programs.fitness.FitnessTestJson
import com.treadz.console.programs.fitness.HeartRateMaxOrigin
import com.treadz.console.programs.model.ProgramKind
import com.treadz.console.userdata.DataResult
import com.treadz.console.userdata.FitnessProfiles
import com.treadz.console.userdata.ProfileInput
import com.treadz.console.userdata.ProgramSaveResult
import com.treadz.console.userdata.Sex
import com.treadz.console.userdata.UserProfile
import com.treadz.console.userdata.WorkoutLink
import com.treadz.console.userdata.workout.WorkoutSessionStore
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.LocalDate

/** Programs, fitness tests, the derived fitness.json and the plan of a workout, through a fake GitHub. */
@RunWith(RobolectricTestRunner::class)
class ProgramAndFitnessSyncTest {

    private val github = FakeGitHub().apply { validTokens += TOKEN }
    private val repo = github.addRepo(FakeGitHub.Repo("fede", "treadz-data"))

    private fun device(name: String, seed: Long) = Device(name, github, seed, seedGenericProfile = false)

    private fun paths() = repo.files().keys.toList().sorted()

    private fun folderOf(name: String) = paths().first { it.startsWith("users/$name-") && it.endsWith("/profile.json") }.removeSuffix("profile.json")

    private fun fitnessBlock(text: String): String? = Regex("\"fitness\": \\{").find(text)?.let { text.substring(it.range.first) }

    private fun <T> ok(r: DataResult<T>): T = (r as DataResult.Success).value

    private fun Device.save(user: UserProfile, program: com.treadz.console.programs.model.Program) =
        (runBlocking { data.programs.save(user.id, program) } as ProgramSaveResult.Saved).program

    private fun Device.saveTest(user: UserProfile, result: com.treadz.console.programs.fitness.FitnessTestResult) =
        ok(runBlocking { data.fitnessTests.save(user.id, result) })

    private fun Device.profile(name: String = "Ana") = profiles().first { it.name == name }

    private fun edit(p: UserProfile, heightCm: Int = p.heightCm) =
        ProfileInput(p.name, p.sex, heightCm, p.birthDate, p.restingHeartRateBpm, p.maxHeartRateBpm, p.fitnessLevel, p.goal)

    private val day = 86_400_000L

    // ------------------------------------------------------------ programs

    @Test fun `a program travels to the other device and its edit and deletion follow`() {
        val a = device("tablet", 1)
        val ana = a.createUser("Ana")
        a.sync()
        a.save(ana, norwegian())
        a.sync()

        val path = "${folderOf("ana")}programs/$PROGRAM_1.json"
        assertTrue(path, path in paths())
        val file = repo.text(path)!!
        assertTrue(file, file.contains("\"program\": {\n    \"schemaVersion\": 1,"))
        assertTrue(file, file.contains("\"name\": \"Norwegian 4x4\""))
        assertEquals("Ana · program \"Norwegian 4x4\"", repo.log().last())

        val b = device("phone", 2)
        b.sync()
        val remote = runBlocking { b.data.programs.programs(b.profile().id).first() }.single()
        assertEquals(norwegian(), remote.program)
        assertEquals(0, runBlocking { b.data.programDocuments.pendingCount.first() })
        val commits = repo.log().size
        assertNull(b.sync().commitSha)
        assertEquals(commits, repo.log().size)

        // an edit on the phone
        b.at(T0 + 1_000)
        b.save(b.profile(), norwegian(name = "4x4 edited"))
        b.sync()
        assertEquals("Ana · program \"4x4 edited\"", repo.log().last())
        a.sync()
        assertEquals("4x4 edited", runBlocking { a.data.programs.get(PROGRAM_1) }!!.program.name)

        // and its deletion on the tablet
        a.at(T0 + 2_000)
        runBlocking { a.data.programs.delete(PROGRAM_1) }
        a.sync()
        assertEquals("Ana · program deleted", repo.log().last())
        val tombstone = repo.text(path)!!
        assertTrue(tombstone, tombstone.contains("\"deleted\": true"))
        assertFalse(tombstone, tombstone.contains("\"program\""))
        b.sync()
        assertNull(runBlocking { b.data.programs.get(PROGRAM_1) })
        assertEquals(0, runBlocking { b.data.programDocuments.pendingCount.first() })
    }

    @Test fun `a program edited on two devices keeps the later edit`() {
        val a = device("tablet", 1)
        val ana = a.createUser("Ana")
        a.save(ana, norwegian())
        a.sync()
        val b = device("phone", 2)
        b.sync()

        b.at(T0 + 1_000)
        b.save(b.profile(), norwegian(name = "Phone"))
        a.at(T0 + 2_000)
        a.save(ana, norwegian(name = "Tablet"))
        b.sync()
        a.sync()
        b.sync()
        assertEquals("Tablet", runBlocking { a.data.programs.get(PROGRAM_1) }!!.program.name)
        assertEquals("Tablet", runBlocking { b.data.programs.get(PROGRAM_1) }!!.program.name)
    }

    @Test fun `standard programs are never stored, so never uploaded`() {
        val a = device("tablet", 1)
        val ana = a.createUser("Ana")
        val result = runBlocking { a.data.programs.save(ana.id, norwegian(id = "standard-hill", kind = ProgramKind.Standard)) }
        assertTrue(result is ProgramSaveResult.Invalid)
        a.sync()
        assertTrue(paths().none { it.contains("/programs/") })
    }

    // ------------------------------------------------------------ fitness tests and the profile

    @Test fun `a fitness test is uploaded under fitness-tests and gets its own fitness json, the profile file is untouched`() {
        val a = device("tablet", 1)
        val ana = a.createUser("Ana")
        a.sync()
        val before = repo.text("${folderOf("ana")}profile.json")!!
        assertFalse(before, before.contains("\"fitness\""))
        assertNull(repo.text("${folderOf("ana")}fitness.json"))

        a.saveTest(ana, ebbelingResult(TEST_1, T0 - 3 * day, vo2max = 41.0))
        a.sync()

        val folder = folderOf("ana")
        val file = repo.text("${folder}fitness-tests/$TEST_1.json")!!
        assertTrue(file, file.contains("\"result\": {\n    \"schemaVersion\": 1,"))
        assertEquals(ebbelingResult(TEST_1, T0 - 3 * day, vo2max = 41.0), FitnessTestJson.decode(String(file.substringAfter("\"result\": ").substringBeforeLast(",\n  \"updated_at\"").toByteArray())))
        val fitness = repo.text("${folder}fitness.json")!!
        assertTrue(fitness, fitness.contains("\"rules_version\": ${FitnessProfiles.RULES_VERSION},"))
        assertTrue(fitness, fitness.contains("\"fitness\": {\n    \"schemaVersion\": 1,"))
        assertTrue(fitness, fitness.contains("\"valueMlKgMin\": 41.0"))
        assertEquals("profile.json is exactly as it was", before, repo.text("${folder}profile.json"))
        assertEquals("Ana · fitness test 2026-10-05 00:40 · Ebbeling walk · VO2max 41.0", repo.log().last())
    }

    @Test fun `a maximal test message shows the speed, an invalid one says so`() {
        val a = device("tablet", 1)
        val ana = a.createUser("Ana")
        a.sync()
        a.saveTest(ana, maxTestResult(TEST_2, T0 - day, mas = 13.5))
        a.sync()
        assertEquals("Ana · fitness test 2026-10-07 00:40 · max incremental run · VO2max 47.3 · MAS 13.5 km/h", repo.log().last())
        a.saveTest(ana, ebbelingResult(TEST_1, T0 - day, validity = com.treadz.console.programs.fitness.Validity.Invalid))
        a.sync()
        assertEquals("Ana · fitness test 2026-10-07 00:40 · Ebbeling walk · invalid", repo.log().last())
    }

    @Test fun `the other device gets the test and derives the same fitness block, and nothing is rewritten`() {
        val a = device("tablet", 1)
        val ana = a.createUser("Ana")
        a.saveTest(ana, ebbelingResult(TEST_1, T0 - 40 * day, vo2max = 40.0))
        a.saveTest(ana, maxTestResult(TEST_2, T0 - 10 * day, mas = 13.0))
        a.sync()

        val b = device("phone", 2)
        b.sync()
        val anaB = b.profile()
        assertEquals(listOf(TEST_2, TEST_1), runBlocking { b.data.fitnessTests.results(anaB.id).first() }.map { it.id })
        assertEquals(
            runBlocking { a.data.fitnessTests.fitnessProfile(ana.id).first() },
            runBlocking { b.data.fitnessTests.fitnessProfile(anaB.id).first() },
        )
        assertEquals(0, runBlocking { b.data.sync.pendingCount.first() + b.data.fitnessTestDocuments.pendingCount.first() })

        // nothing to upload on either side, and the repository is not touched by further rounds
        val commits = repo.log().size
        listOf(a, b, a, b).forEach { assertNull(it.sync().commitSha) }
        assertEquals(commits, repo.log().size)
    }

    @Test fun `tests saved offline on two devices converge on one fitness block without ping-pong`() {
        val a = device("tablet", 1)
        val ana = a.createUser("Ana")
        a.sync()
        val b = device("phone", 2)
        b.sync()
        val anaB = b.profile()

        a.at(T0 + 1_000)
        a.saveTest(ana, ebbelingResult(TEST_1, T0 - 30 * day, vo2max = 40.0))
        b.at(T0 + 2_000)
        b.saveTest(anaB, maxTestResult(TEST_2, T0 - 5 * day, mas = 13.0))

        a.sync() // A: T1 and fitness.json with {T1}
        b.sync() // B: pulls T1, uploads T2 and fitness.json with {T1, T2}
        a.sync() // A: pulls T2
        val commits = repo.log().size
        for (round in 1..3) {
            assertNull("round $round of A", a.sync().commitSha)
            assertNull("round $round of B", b.sync().commitSha)
        }
        assertEquals("no device rewrites the other's file", commits, repo.log().size)

        val expected = runBlocking { a.data.fitnessTests.fitnessProfile(ana.id).first() }!!
        assertEquals(2, expected.vo2max!!.sources.size)
        assertEquals(expected, runBlocking { b.data.fitnessTests.fitnessProfile(anaB.id).first() })
        // the file holds exactly that block
        val fitness = repo.text("${folderOf("ana")}fitness.json")!!
        assertTrue(fitness, fitness.contains("\"computedAt\": \"${java.time.Instant.ofEpochMilli(T0 - 5 * day)}\""))
        assertTrue(fitness, fitness.contains("\"resultId\": \"$TEST_1\""))
        assertTrue(fitness, fitness.contains("\"resultId\": \"$TEST_2\""))
    }

    @Test fun `the block in the file is the same whichever device writes it and in whichever order the tests arrived`() {
        val results = listOf(
            ebbelingResult(TEST_1, T0 - 40 * day, vo2max = 40.0),
            maxTestResult(TEST_2, T0 - 10 * day, mas = 13.0),
            ebbelingResult(TEST_3, T0 - 10 * day, vo2max = 44.0),
        )
        // device A saves them in one order, device B (on a repository of its own) in the opposite one
        fun blockWrittenBy(order: List<com.treadz.console.programs.fitness.FitnessTestResult>, seed: Long, name: String): String {
            val g = FakeGitHub().apply { validTokens += TOKEN }
            val r = g.addRepo(FakeGitHub.Repo("fede", "treadz-data"))
            val d = Device(name, g, seed, seedGenericProfile = false)
            val user = runBlocking { ok(d.data.profiles.create(ProfileInput("Ana", Sex.Female, 170, LocalDate.of(1986, 10, 8)))) }
            order.forEach { d.saveTest(user, it) }
            d.at(T0 + 100 * day) // wall-clock time differs too
            d.sync()
            val file = r.files().keys.first { it.endsWith("/fitness.json") }
            val text = r.text(file)!!
            // the same inputs give the same fingerprint, and the same block, on every device
            return Regex("\"fingerprint\": \"[0-9a-f]{64}\"").find(text)!!.value + fitnessBlock(text)!!.substringBefore("\n  \"updated_at\"")
        }
        assertEquals(blockWrittenBy(results, 1, "tablet"), blockWrittenBy(results.reversed(), 2, "phone"))
    }

    @Test fun `deleting a test is a tombstone and the block no longer counts it`() {
        val a = device("tablet", 1)
        val ana = a.createUser("Ana")
        a.saveTest(ana, ebbelingResult(TEST_1, T0 - 10 * day, vo2max = 40.0))
        a.saveTest(ana, ebbelingResult(TEST_3, T0 - 5 * day, vo2max = 50.0))
        a.sync()
        val b = device("phone", 2)
        b.sync()

        a.at(T0 + 1_000)
        runBlocking { a.data.fitnessTests.delete(TEST_3) }
        a.sync()
        assertEquals("Ana · fitness test deleted", repo.log().last())
        val folder = folderOf("ana")
        val tombstone = repo.text("${folder}fitness-tests/$TEST_3.json")!!
        assertTrue(tombstone, tombstone.contains("\"deleted\": true") && !tombstone.contains("\"result\""))
        assertFalse(repo.text("${folder}fitness.json")!!.contains(TEST_3))
        assertTrue(repo.text("${folder}fitness.json")!!.contains(TEST_1))

        b.sync()
        assertNull(runBlocking { b.data.fitnessTests.get(TEST_3) })
        assertEquals(listOf(TEST_1), runBlocking { b.data.fitnessTests.fitnessProfile(b.profile().id).first() }!!.vo2max!!.sources.map { it.resultId })

        // the last one: nothing usable is left, the file goes
        a.at(T0 + 2_000)
        runBlocking { a.data.fitnessTests.delete(TEST_1) }
        a.sync()
        assertNull(repo.text("${folder}fitness.json"))
        b.sync()
        assertNull(runBlocking { b.data.fitnessTests.fitnessProfile(b.profile().id).first() })
        assertNull("the other device does not bring it back", b.sync().commitSha)
        assertNull(repo.text("${folder}fitness.json"))
    }

    @Test fun `adding a test never touches a concurrent edit of the profile`() {
        val a = device("tablet", 1)
        val ana = a.createUser("Ana")
        a.sync()
        val b = device("phone", 2)
        b.sync()
        val anaB = b.profile()

        b.at(T0 + 2_000)
        runBlocking { b.data.profiles.update(anaB.id, edit(anaB, heightCm = 150)) } // offline edit
        a.at(T0 + 5_000)
        a.saveTest(ana, ebbelingResult(TEST_1, T0 - day))
        a.sync() // uploads the test and fitness.json; profile.json is not part of it
        b.sync() // the edit goes up
        a.sync()

        assertEquals(150, a.profile().heightCm)
        assertEquals(150, b.profile().heightCm)
        val profile = repo.text("${folderOf("ana")}profile.json")!!
        assertTrue(profile, profile.contains("\"height_cm\": 150") && !profile.contains("\"fitness\":"))
        assertTrue(repo.text("${folderOf("ana")}fitness.json")!!.contains("\"resultId\": \"$TEST_1\""))
        val commits = repo.log().size
        assertNull(a.sync().commitSha)
        assertNull(b.sync().commitSha)
        assertEquals(commits, repo.log().size)
    }

    @Test fun `a fitness json that somebody edited by hand but kept the fingerprint of is ignored and not fought over`() {
        val a = device("tablet", 1)
        val ana = a.createUser("Ana")
        a.saveTest(ana, ebbelingResult(TEST_1, T0 - day, vo2max = 41.0))
        a.sync()
        val path = "${folderOf("ana")}fitness.json"
        val tampered = repo.text(path)!!.replace("\"valueMlKgMin\": 41.0", "\"valueMlKgMin\": 99.0")
        assertTrue(tampered != repo.text(path))
        replaceFile(path, tampered)

        val b = device("phone", 2)
        b.sync()
        assertEquals(41.0, runBlocking { b.data.fitnessTests.fitnessProfile(b.profile().id).first() }!!.vo2max!!.valueMlKgMin, 0.0)
        val commits = repo.log().size
        assertNull(b.sync().commitSha)
        assertNull(a.sync().commitSha)
        assertEquals(commits, repo.log().size)
    }

    @Test fun `a test written by a newer app is kept without errors and not shown`() {
        val a = device("tablet", 1)
        a.createUser("Ana")
        a.sync()
        val folder = folderOf("ana")
        val userUuid = folder.removePrefix("users/ana-").removeSuffix("/")
        val future = """{
  "format": 1,
  "uuid": "$TEST_3",
  "user_uuid": "$userUuid",
  "deleted": false,
  "result": {"schemaVersion": 99, "id": "$TEST_3"},
  "updated_at": "2026-10-08T01:00:00.000Z",
  "updated_by": "future-phone"
}
"""
        replaceFile("${folder}fitness-tests/$TEST_3.json", future)
        val before = repo.log().size
        a.sync()
        assertEquals(emptyList<Any>(), runBlocking { a.data.fitnessTests.results(a.profile().id).first() })
        assertEquals(before, repo.log().size)
        assertEquals(0, runBlocking { a.data.fitnessTestDocuments.pendingCount.first() })
    }

    // ------------------------------------------------------------ the manual maximum heart rate

    @Test fun `the time of the manual maximum heart rate and the answers to the question reach the other device`() {
        val a = device("tablet", 1)
        val ana = runBlocking {
            ok(a.data.profiles.create(ProfileInput("Ana", Sex.Female, 170, LocalDate.of(1986, 10, 8), maxHeartRateBpm = 170)))
        }
        a.sync()
        val profile = repo.text("${folderOf("ana")}profile.json")!!
        assertTrue(profile, profile.contains("\"max_hr_bpm\": 170"))
        assertTrue(profile, profile.contains("\"max_hr_set_at\": \"2026-10-08T00:40:00.000Z\""))

        val b = device("phone", 2)
        b.sync()
        assertEquals(T0, b.profile().maxHeartRateSetAtMillis)

        // a newer maximal test on the phone: it asks
        b.at(T0 + 3 * day)
        b.saveTest(b.profile(), maxTestResult(TEST_2, T0 + day, peak = 186))
        assertTrue(runBlocking { b.data.fitnessTests.heartRateMax(b.profile().id).first() }!!.askUser)
        b.sync()
        a.at(T0 + 3 * day)
        a.sync()
        assertTrue("the same question on the other device", runBlocking { a.data.fitnessTests.heartRateMax(ana.id).first() }!!.askUser)

        // "keep mine" on the tablet answers it everywhere
        a.at(T0 + 4 * day)
        ok(runBlocking { a.data.fitnessTests.keepManualHeartRateMax(ana.id) })
        a.sync()
        b.at(T0 + 4 * day)
        b.sync()
        val kept = runBlocking { b.data.fitnessTests.heartRateMax(b.profile().id).first() }!!
        assertEquals(HeartRateMaxOrigin.Manual, kept.origin)
        assertEquals(170, kept.bpm)
        assertFalse(kept.askUser)

        // a later test asks again, and "use the measured one" clears the manual value on both
        b.at(T0 + 10 * day)
        b.saveTest(b.profile(), maxTestResult(TEST_3, T0 + 9 * day, peak = 190))
        b.sync()
        a.at(T0 + 10 * day)
        a.sync()
        ok(runBlocking { a.data.fitnessTests.useMeasuredHeartRateMax(ana.id) })
        a.sync()
        b.sync()
        val adopted = runBlocking { b.data.fitnessTests.heartRateMax(b.profile().id).first() }!!
        assertEquals(HeartRateMaxOrigin.Measured, adopted.origin)
        assertEquals(190, adopted.bpm)
        assertNull(b.profile().maxHeartRateBpm)
        assertNull(b.profile().maxHeartRateSetAtMillis)
        val file = repo.text("${folderOf("ana")}profile.json")!!
        assertTrue(file, file.contains("\"max_hr_bpm\": null") && !file.contains("max_hr_set_at"))
    }

    // ------------------------------------------------------------ workouts

    @Test fun `a workout carries the plan that was executed and the test that was run`() {
        val a = device("tablet", 1)
        val ana = a.createUser("Ana")
        val plan = hillPlan()
        val id = a.finishWorkout(ana, T0, seconds = 3)
        runBlocking { a.data.workouts.link(id, WorkoutLink(plan = plan, fitnessTestId = TEST_1)) }
        a.sync()

        val path = paths().first { it.contains("/workouts/") && it.endsWith(".json") }
        val file = repo.text(path)!!
        assertTrue(file, file.contains("\"program_id\": \"standard-hill\""))
        assertTrue(file, file.contains("\"program_name\": \"${plan.source.programName}\""))
        assertTrue(file, file.contains("\"fitness_test_id\": \"$TEST_1\""))
        assertTrue(file, file.contains("\"plan\": {\n    \"planSchemaVersion\": 1,"))
        // the plan is self-contained: absolute blocks and the parameters it was resolved with
        assertTrue(file, file.contains("\"blocks\": ["))
        assertTrue(file, file.contains("\"easySpeedKmh\""))

        val b = device("phone", 2)
        b.sync()
        val remote = runBlocking { b.data.workouts.workouts(b.profile().id).first() }.single()
        assertEquals("standard-hill", remote.programId)
        assertEquals(plan.source.programName, remote.programName)
        assertEquals(TEST_1, remote.fitnessTestId)
        assertEquals(plan, runBlocking { b.data.workouts.detail(remote.id) }!!.plan)
        assertEquals(0, runBlocking { b.data.sync.pendingCount.first() })
    }

    @Test fun `linking a workout after it was uploaded uploads it again with the link`() {
        val a = device("tablet", 1)
        val ana = a.createUser("Ana")
        val id = a.finishWorkout(ana, T0, seconds = 3)
        a.sync()
        val path = paths().first { it.contains("/workouts/") && it.endsWith(".json") }
        assertFalse(repo.text(path)!!.contains("plan"))

        a.at(T0 + 60_000)
        runBlocking { a.data.workouts.link(id, WorkoutLink(plan = hillPlan())) }
        a.sync()
        assertTrue(repo.text(path)!!.contains("\"program_id\": \"standard-hill\""))
        assertEquals("Ana · workout 2026-10-08 00:40 · 3 s · 0.1 km", repo.log().last())
        // the path of the workout did not change
        assertEquals(1, paths().count { it.contains("/workouts/") && it.endsWith(".json") })

        val b = device("phone", 2)
        b.sync()
        assertEquals("standard-hill", runBlocking { b.data.workouts.workouts(b.profile().id).first() }.single().programId)
    }

    // ------------------------------------------------------------ first link

    @Test fun `the first link counts the programs and tests to upload`() {
        val a = device("tablet", 1)
        val ana = a.createUser("Ana")
        a.save(ana, norwegian())
        a.save(ana, norwegian(PROGRAM_2, "Second"))
        a.saveTest(ana, ebbelingResult())
        val summary = runBlocking { a.engine.previewFirstLink(a.repo) }
        val user = summary.users.single()
        assertEquals(2, user.programs)
        assertEquals(1, user.fitnessTests)
        assertEquals(1, summary.totalFitnessTests)
        assertEquals(2, summary.totalPrograms)
    }

    @Test fun `the sync service counts programs and tests as pending`() {
        val a = device("tablet", 1)
        val ana = a.createUser("Ana")
        a.sync()
        a.save(ana, norwegian())
        a.saveTest(ana, ebbelingResult())
        val pending = runBlocking {
            a.data.sync.pendingCount.first() + a.data.programDocuments.pendingCount.first() + a.data.fitnessTestDocuments.pendingCount.first()
        }
        assertEquals(2, pending)
        assertEquals(2, a.sync().uploaded)
        assertNotNull(repo.text("${folderOf("ana")}fitness-tests/$TEST_1.json"))
    }

    @Test fun `the status of the sync service includes programs and tests in the pending count`() {
        val a = device("tablet", 1)
        val ana = a.createUser("Ana")
        a.sync()
        val service = a.service
        awaitUntil("nothing pending") { service.status.value.pendingCount == 0 }
        a.save(ana, norwegian())
        awaitUntil("the program") { service.status.value.pendingCount == 1 }
        a.saveTest(ana, ebbelingResult())
        awaitUntil("the program and the test") { service.status.value.pendingCount == 2 }
        a.sync()
        awaitUntil("nothing pending again") { service.status.value.pendingCount == 0 }
    }

    private fun awaitUntil(what: String, timeoutMs: Long = 5000, condition: () -> Boolean) {
        val end = System.currentTimeMillis() + timeoutMs
        while (!condition()) {
            org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idle()
            if (System.currentTimeMillis() > end) org.junit.Assert.fail("timeout waiting for: $what")
            Thread.sleep(10)
        }
    }

    @Test fun `the README of the repository describes the new files and fields`() {
        val a = device("tablet", 1)
        a.createUser("Ana")
        a.sync()
        val readme = repo.text("README.md")!!
        for (needle in listOf("fitness-tests/<id>.json", "programs/<uuid>.json", "max_hr_set_at", "`fitness` block", "program_id", "`plan`", "fitness test** keeps")) {
            assertTrue("README lacks '$needle'", readme.contains(needle))
        }
    }

    // ------------------------------------------------------------ helpers

    /** Replaces a file of the repository's main branch with a new commit made by "somebody else". */
    private fun replaceFile(path: String, text: String) {
        val sha = fakeSha(path + text.length + text.hashCode())
        repo.blobs[sha] = text.toByteArray()
        val head = repo.refs.getValue("main")
        val commit = repo.commits.getValue(head)
        val tree = fakeSha("tree$sha$head")
        repo.trees[tree] = repo.trees.getValue(commit.tree) + (path to sha)
        val newCommit = fakeSha("commit$tree")
        repo.commits[newCommit] = FakeGitHub.Commit(tree, listOf(head), "edited by hand")
        repo.refs["main"] = newCommit
    }

    private fun fakeSha(seed: String): String = java.security.MessageDigest.getInstance("SHA-1").digest(seed.toByteArray()).joinToString("") { "%02x".format(it) }
}
