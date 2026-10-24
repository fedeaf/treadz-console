package com.treadz.console.sync

import com.treadz.console.programs.fitness.FitnessTestJson
import com.treadz.console.sync.format.RecordCodec
import com.treadz.console.userdata.DataResult
import com.treadz.console.userdata.FitnessFingerprint
import com.treadz.console.userdata.FitnessProfiles
import com.treadz.console.userdata.ProfileInput
import com.treadz.console.userdata.RestingHeartRateInput
import com.treadz.console.userdata.RestingHeartRateSource
import com.treadz.console.userdata.UserProfile
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

/**
 * `fitness.json` is derived data, never merged: on every cycle each device compares the fingerprint of its own results with
 * the one in the file and writes the file only when they differ. Through a fake GitHub, with synthetic data.
 */
@RunWith(RobolectricTestRunner::class)
class FitnessFileSyncTest {

    private val github = FakeGitHub().apply { validTokens += TOKEN }
    private val repo = github.addRepo(FakeGitHub.Repo("fede", "treadz-data"))

    private fun device(name: String, seed: Long) = Device(name, github, seed, seedGenericProfile = false)

    private fun paths() = repo.files().keys.toList().sorted()
    private fun folderOf(name: String = "ana") = paths().first { it.startsWith("users/$name-") && it.endsWith("/profile.json") }.removeSuffix("profile.json")
    private fun fitnessPath(name: String = "ana") = "${folderOf(name)}fitness.json"
    private fun fitness(name: String = "ana") = repo.text(fitnessPath(name))
    private fun userUuid(name: String = "ana") = folderOf(name).removePrefix("users/$name-").removeSuffix("/")
    private fun field(text: String, name: String) = Regex("\"$name\": \"?([^\",\n]+)\"?").find(text)!!.groupValues[1]
    private fun iso(millis: Long) = RecordCodec.time(millis)

    private fun <T> ok(r: DataResult<T>): T = (r as DataResult.Success).value
    private fun Device.profile(name: String = "Ana") = profiles().first { it.name == name }
    private fun Device.saveTest(user: UserProfile, result: com.treadz.console.programs.fitness.FitnessTestResult) = ok(runBlocking { data.fitnessTests.save(user.id, result) })

    private val day = 86_400_000L

    private fun tombstone(testId: String, owner: String, at: Long, by: String) = """{
  "format": 1,
  "uuid": "$testId",
  "user_uuid": "$owner",
  "deleted": true,
  "updated_at": "${iso(at)}",
  "updated_by": "$by"
}
"""

    @Test fun `the first upload of a user with a test brings fitness json in the same commit`() {
        val a = device("tablet", 1)
        val ana = a.createUser("Ana")
        a.saveTest(ana, ebbelingResult(TEST_1, T0 - 3 * day, vo2max = 41.0))
        a.sync()

        assertEquals(2, repo.log().size) // the README, then everything else together
        val text = fitness()!!
        assertEquals(userUuid(), field(text, "user_uuid"))
        assertEquals(FitnessProfiles.RULES_VERSION.toString(), field(text, "rules_version"))
        assertEquals(iso(T0), field(text, "updated_at"))
        assertEquals("tablet", field(text, "updated_by"))
        val expected = FitnessFingerprint.of(listOf(TEST_1 to FitnessTestJson.encode(ebbelingResult(TEST_1, T0 - 3 * day, vo2max = 41.0))))
        assertEquals(expected, field(text, "fingerprint"))
    }

    @Test fun `a stale fitness json in the repository is rewritten on the next sync even with no local change`() {
        val a = device("tablet", 1)
        val ana = a.createUser("Ana")
        a.saveTest(ana, ebbelingResult(TEST_1, T0 - 3 * day, vo2max = 41.0))
        a.sync()
        val good = fitness()!!

        // the file as an older version left it: a block of another time, no fingerprint, no rules version
        val stale = good.replace(Regex("  \"rules_version\": \\d+,\n  \"fingerprint\": \"[0-9a-f]+\",\n"), "")
            .replace("\"valueMlKgMin\": 41.0", "\"valueMlKgMin\": 37.0").replace("\"updated_by\": \"tablet\"", "\"updated_by\": \"old-phone\"")
        repo.replaceFile(fitnessPath(), stale)

        val report = a.at(T0 + 5_000).sync()
        assertNotNull("nothing was pending, the file alone made the commit", report.commitSha)
        assertEquals(0, report.uploaded)
        assertEquals("Ana · fitness profile", repo.log().last())
        val fixed = fitness()!!
        assertTrue(fixed, fixed.contains("\"valueMlKgMin\": 41.0") && !fixed.contains("37.0"))
        assertEquals(iso(T0 + 5_000), field(fixed, "updated_at"))
        assertEquals("tablet", field(fixed, "updated_by"))
        assertEquals(field(good, "fingerprint"), field(fixed, "fingerprint"))
        val commits = repo.log().size
        assertNull(a.at(T0 + 6_000).sync().commitSha)
        assertEquals(commits, repo.log().size)

        // a missing file is written again as well
        repo.removeFile(fitnessPath())
        assertNotNull(a.at(T0 + 7_000).sync().commitSha)
        assertEquals(iso(T0 + 7_000), field(fitness()!!, "updated_at"))
    }

    @Test fun `two devices with the same data never rewrite each other`() {
        val a = device("tablet", 1)
        val ana = a.createUser("Ana")
        a.saveTest(ana, ebbelingResult(TEST_1, T0 - 40 * day, vo2max = 40.0))
        a.saveTest(ana, maxTestResult(TEST_2, T0 - 10 * day, mas = 13.0))
        a.sync()
        val first = fitness()!!

        val b = device("phone", 2)
        b.at(T0 + day).sync()
        val commits = repo.log().size
        for (round in 1..3) {
            assertNull("A, round $round", a.at(T0 + (2 + round) * day).sync().commitSha)
            assertNull("B, round $round", b.at(T0 + (2 + round) * day + 1).sync().commitSha)
        }
        assertEquals(commits, repo.log().size)
        assertEquals("the file is as the first writer left it", first, fitness())
        assertEquals("tablet", field(fitness()!!, "updated_by"))
    }

    @Test fun `a file written with a higher rules version is not overwritten, not even to remove it`() {
        val a = device("tablet", 1)
        val ana = a.createUser("Ana")
        a.saveTest(ana, ebbelingResult(TEST_1, T0 - 3 * day, vo2max = 41.0))
        a.sync()
        val future = fitness()!!.replace("\"rules_version\": ${FitnessProfiles.RULES_VERSION},", "\"rules_version\": ${FitnessProfiles.RULES_VERSION + 1},")
            .replace(Regex("\"fingerprint\": \"[0-9a-f]+\""), "\"fingerprint\": \"from-a-newer-app\"").replace("\"valueMlKgMin\": 41.0", "\"valueMlKgMin\": 99.0")
        repo.replaceFile(fitnessPath(), future)

        val commits = repo.log().size
        assertNull(a.at(T0 + day).sync().commitSha)
        assertEquals(commits, repo.log().size)
        assertEquals(future, fitness())

        // even when this device has nothing to derive from
        runBlocking { a.data.fitnessTests.delete(TEST_1) }
        a.at(T0 + 2 * day).sync()
        assertEquals(future, fitness())
        // the tombstone itself went up
        assertTrue(repo.text("${folderOf()}fitness-tests/$TEST_1.json")!!.contains("\"deleted\": true"))
    }

    @Test fun `a file of an older rules version is rewritten`() {
        val a = device("tablet", 1)
        val ana = a.createUser("Ana")
        val test = ebbelingResult(TEST_1, T0 - 3 * day, vo2max = 41.0)
        a.saveTest(ana, test)
        a.sync()
        val older = fitness()!!.replace("\"rules_version\": ${FitnessProfiles.RULES_VERSION},", "\"rules_version\": 0,")
            .replace(Regex("\"fingerprint\": \"[0-9a-f]+\""), "\"fingerprint\": \"${FitnessFingerprint.of(listOf(TEST_1 to FitnessTestJson.encode(test)), rulesVersion = 0)}\"")
        repo.replaceFile(fitnessPath(), older)
        assertNotNull(a.at(T0 + day).sync().commitSha)
        assertEquals(FitnessProfiles.RULES_VERSION.toString(), field(fitness()!!, "rules_version"))
        assertEquals(iso(T0 + day), field(fitness()!!, "updated_at"))
    }

    @Test fun `a deletion that arrives from a device that did not update the file changes it in the same cycle`() {
        val a = device("tablet", 1)
        val ana = a.createUser("Ana")
        a.saveTest(ana, ebbelingResult(TEST_1, T0 - 10 * day, vo2max = 40.0))
        a.saveTest(ana, ebbelingResult(TEST_3, T0 - 5 * day, vo2max = 50.0))
        a.sync()
        assertTrue(fitness()!!.contains(TEST_3))
        val b = device("phone", 2)
        b.at(T0 + day).sync()

        // somebody deletes a test and leaves fitness.json as it was
        repo.replaceFile("${folderOf()}fitness-tests/$TEST_3.json", tombstone(TEST_3, userUuid(), T0 + day, "old-phone"))
        val report = b.at(T0 + 2 * day).sync()
        assertEquals(1, report.downloaded)
        assertNotNull(report.commitSha)
        assertEquals("Ana · fitness profile", repo.log().last())
        assertFalse(fitness()!!.contains(TEST_3))
        assertTrue(fitness()!!.contains(TEST_1))
        assertEquals("phone", field(fitness()!!, "updated_by"))
        // the other device takes the tombstone and finds its derivation equal to the file
        assertNull(a.at(T0 + 3 * day).sync().commitSha)
        assertEquals(setOf(TEST_1), runBlocking { a.data.fitnessTests.results(a.profile().id).first() }.map { it.id }.toSet())
    }

    @Test fun `results and resting measurements leave profile json as it was`() {
        val a = device("tablet", 1)
        val ana = a.createUser("Ana")
        a.sync()
        val before = repo.text("${folderOf()}profile.json")!!

        a.at(T0 + day)
        a.saveTest(ana, ebbelingResult(TEST_1, T0, vo2max = 41.0))
        ok(runBlocking { a.data.restingHeartRates.add(ana.id, RestingHeartRateInput(T0, 56, RestingHeartRateSource.Manual)) })
        a.sync()
        assertEquals(before, repo.text("${folderOf()}profile.json"))
        assertNotNull(fitness())
        assertNotNull(repo.text("${folderOf()}resting-heart-rate.csv"))

        a.at(T0 + 2 * day)
        a.saveTest(ana, maxTestResult(TEST_2, T0 + day))
        a.sync()
        assertEquals(before, repo.text("${folderOf()}profile.json"))
        assertEquals(iso(T0 + 2 * day), field(fitness()!!, "updated_at"))

        // only an edit of the profile changes it
        a.at(T0 + 3 * day)
        runBlocking { a.data.profiles.update(ana.id, ProfileInput(ana.name, ana.sex, 171, ana.birthDate)) }
        a.sync()
        val edited = repo.text("${folderOf()}profile.json")!!
        assertEquals(iso(T0 + 3 * day), field(edited, "updated_at"))
        assertTrue(edited.contains("\"height_cm\": 171"))
        assertFalse(edited.contains("\"fitness\":") || edited.contains("resting_hr_bpm"))
        assertEquals("the edit does not touch the derived file", iso(T0 + 2 * day), field(fitness()!!, "updated_at"))
    }

    @Test fun `a user without a usable result has no fitness json and a leftover one is removed`() {
        val a = device("tablet", 1)
        val ana = a.createUser("Ana")
        a.saveTest(ana, ebbelingResult(TEST_1, T0, validity = com.treadz.console.programs.fitness.Validity.Invalid))
        a.sync()
        assertNull("an invalid test gives nothing to derive", fitness())

        // a file left by a device that still had a valid test that was deleted since
        repo.replaceFile(fitnessPath(), "{\n  \"format\": 1,\n  \"user_uuid\": \"${userUuid()}\",\n  \"fitness\": {},\n  \"updated_at\": \"${iso(T0)}\",\n  \"updated_by\": \"old-phone\"\n}\n")
        assertNotNull(a.at(T0 + day).sync().commitSha)
        assertNull(fitness())
        val commits = repo.log().size
        assertNull(a.at(T0 + 2 * day).sync().commitSha)
        assertEquals(commits, repo.log().size)
    }

    @Test fun `a deleted user loses the file with the rest of the folder`() {
        val a = device("tablet", 1)
        val ana = a.createUser("Ana")
        a.createUser("Beto")
        a.saveTest(ana, ebbelingResult(TEST_1, T0, vo2max = 41.0))
        a.sync()
        val folder = folderOf()
        assertNotNull(fitness())

        ok(runBlocking { a.data.profiles.delete(ana.id) })
        a.at(T0 + day).sync()
        assertEquals(listOf("${folder}profile.json"), paths().filter { it.startsWith(folder) })
        assertNull(fitness())
        val b = device("phone", 2)
        b.at(T0 + 2 * day).sync()
        assertEquals(listOf("Beto"), b.profiles().map { it.name })
        assertNull("nobody writes it again", b.at(T0 + 3 * day).sync().commitSha)
    }

    @Test fun `a renamed user's fitness json moves with the folder, untouched`() {
        val a = device("tablet", 1)
        val ana = a.createUser("Ana")
        a.saveTest(ana, ebbelingResult(TEST_1, T0, vo2max = 41.0))
        a.sync()
        val before = fitness()!!
        val oldFolder = folderOf()

        a.at(T0 + day)
        ok(runBlocking { a.data.profiles.update(ana.id, ProfileInput("Anabel", ana.sex, ana.heightCm, ana.birthDate)) })
        a.sync()
        assertTrue(paths().none { it.startsWith(oldFolder) })
        assertEquals(before, fitness("anabel"))
    }

    @Test fun `a user that only the repository knows is left alone when this device derives the same`() {
        val a = device("tablet", 1)
        val ana = a.createUser("Ana")
        a.saveTest(ana, maxTestResult(TEST_2, T0, mas = 13.0))
        a.sync()
        val b = device("phone", 2)
        b.at(T0 + day).sync()
        // b's own derivation is the file's: the fingerprint is the same although b's clock and device differ
        val local = runBlocking { b.data.sync.fitness(userUuid()) }!!
        assertEquals(field(fitness()!!, "fingerprint"), local.fingerprint)
    }

    @Test fun `the README describes fitness json, the derived values profile json no longer has, the updated_at rule and the events`() {
        val a = device("tablet", 1)
        a.createUser("Ana")
        a.sync()
        val readme = repo.text("README.md")!!
        for (needle in listOf(
            "Format version: 1.", "fitness.json", "derived data, never merged", "fingerprint", "rules_version", "higher `rules_version`",
            "`updated_at` changes with every change of content", "`resting_hr_bpm` (the reference of `resting-heart-rate.csv`",
            "`events`", "`manual_change`", "`heart_rate_increase`", "`heart_rate_decrease`", "`alpha1_reduction`", "`ceiling_reduction`",
            "`max_heart_rate_reached`", "`no_heart_rate_fallback`", "`heart_rate_control_resumed`", "`block_started`", "`speed`", "`incline`",
        )) {
            assertTrue("README lacks $needle", readme.contains(needle))
        }
        assertFalse(readme, readme.contains("`profile.json` may also contain a `fitness` block"))
    }
}
