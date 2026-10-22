package com.treadz.console.userdata

import com.treadz.console.programs.fitness.FitnessTestJson
import com.treadz.console.programs.fitness.FitnessTestResult
import com.treadz.console.userdata.room.RoomUserData
import com.treadz.console.userdata.sync.FitnessTestDocument
import com.treadz.console.userdata.sync.RecordVersion
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.LocalDate
import java.time.ZoneOffset

/**
 * The fingerprint of the inputs of the derived fitness profile (`fitness.json`) and the derivation itself
 * ([com.treadz.console.userdata.sync.SyncStore.fitness]): deterministic, sensitive to the results and to the version of
 * the rules, and to nothing else. Synthetic data only.
 */
@RunWith(RobolectricTestRunner::class)
class FitnessFingerprintTest {

    private val clock = MutableClock()
    @After fun closeDatabases() = closeRoomDatabases()

    private var counter = 0
    private val db = newRoomDatabase()
    private val data = RoomUserData(db, clock, ZoneOffset.UTC, seedGenericProfile = false, deviceId = "tablet", uuidSource = { "uuid-${++counter}" })

    private fun <T> ok(r: DataResult<T>): T = (r as DataResult.Success).value

    private fun doc(result: FitnessTestResult) = result.id to FitnessTestJson.encode(result)

    private val one = ebbelingResult(TEST_1, T0 - 40 * DAY_MS, vo2max = 40.0)
    private val two = maxTestResult(TEST_2, T0 - 10 * DAY_MS, mas = 13.0)

    // ------------------------------------------------------------ the pure fingerprint

    @Test fun `it is a deterministic SHA-256, the same whatever the order of the results`() {
        val a = FitnessFingerprint.of(listOf(doc(one), doc(two)))
        assertEquals(a, FitnessFingerprint.of(listOf(doc(one), doc(two))))
        assertEquals(a, FitnessFingerprint.of(listOf(doc(two), doc(one))))
        assertTrue(a, Regex("[0-9a-f]{64}").matches(a))
    }

    @Test fun `it does not depend on how the document is written, only on what it says`() {
        val text = FitnessTestJson.encode(one)
        val reordered = org.json.JSONObject(text).let { o ->
            // the same fields in reverse order and numbers written another way
            org.json.JSONObject().also { r -> o.keys().asSequence().toList().reversed().forEach { k -> r.put(k, o.get(k)) } }.toString(2)
        }
        assertNotEquals(text, reordered)
        assertEquals(FitnessFingerprint.of(listOf(TEST_1 to text)), FitnessFingerprint.of(listOf(TEST_1 to reordered)))
        assertEquals(
            FitnessFingerprint.of(listOf("a" to """{"x": 1.0, "y": [1, 2], "z": {"b": 2, "a": 1}}""")),
            FitnessFingerprint.of(listOf("a" to """{"z": {"a": 1, "b": 2.00}, "y": [1,2], "x": 1}""")),
        )
        assertNotEquals(
            FitnessFingerprint.of(listOf("a" to """{"y": [1, 2]}""")),
            FitnessFingerprint.of(listOf("a" to """{"y": [2, 1]}""")),
        )
    }

    @Test fun `it changes with a new result, a removed one, a changed one, another id and the rules version`() {
        val base = FitnessFingerprint.of(listOf(doc(one)))
        val all = setOf(
            base,
            FitnessFingerprint.of(listOf(doc(one), doc(two))), // a new test
            FitnessFingerprint.of(emptyList()), // a tombstone: the result is no longer present
            FitnessFingerprint.of(listOf(doc(one.copy(flags = listOf(com.treadz.console.programs.fitness.FitnessTestFlag.StartSpeedClamped))))), // a recalculated or edited result
            FitnessFingerprint.of(listOf(doc(ebbelingResult(TEST_1, T0 - 40 * DAY_MS, vo2max = 40.5)))),
            FitnessFingerprint.of(listOf(TEST_3 to FitnessTestJson.encode(one))), // the same content under another id
            FitnessFingerprint.of(listOf(doc(one)), rulesVersion = FitnessProfiles.RULES_VERSION + 1),
        )
        assertEquals("every input gives a different fingerprint", 7, all.size)
    }

    // ------------------------------------------------------------ the derivation from the stored results

    private suspend fun ana(birth: LocalDate = LocalDate.of(1986, 10, 8), sex: Sex = Sex.Female): UserProfile =
        ok(data.profiles.create(validInput("Ana").copy(birthDate = birth, sex = sex)))

    private suspend fun uuidOf(user: UserProfile) = db.users().get(user.id)!!.uuid

    @Test fun `a user without a usable result has no derived profile`() = runBlocking<Unit> {
        val ana = ana()
        assertNull(data.sync.fitness(uuidOf(ana)))
        assertNull(data.sync.fitness("nobody"))
        ok(data.fitnessTests.save(ana.id, ebbelingResult(TEST_1, T0, validity = com.treadz.console.programs.fitness.Validity.Invalid)))
        assertNull("an invalid test gives nothing to derive", data.sync.fitness(uuidOf(ana)))
        ok(data.fitnessTests.save(ana.id, ebbelingResult(TEST_2, T0)))
        assertNotNull(data.sync.fitness(uuidOf(ana)))
    }

    @Test fun `the derived profile is the snapshot of the results with the fingerprint of the inputs and the rules version`() = runBlocking<Unit> {
        val ana = ana()
        ok(data.fitnessTests.save(ana.id, one))
        ok(data.fitnessTests.save(ana.id, two))
        val fitness = data.sync.fitness(uuidOf(ana))!!
        assertEquals(uuidOf(ana), fitness.userUuid)
        assertEquals(FitnessProfiles.RULES_VERSION, fitness.rulesVersion)
        assertEquals(FitnessProfiles.snapshot(listOf(one, two), Sex.Female, LocalDate.of(1986, 10, 8)), fitness.profile)
        assertEquals(FitnessFingerprint.of(listOf(doc(one), doc(two))), fitness.fingerprint)
    }

    @Test fun `the fingerprint follows the tests present, their deletion and their recalculation`() = runBlocking<Unit> {
        val ana = ana()
        val uuid = uuidOf(ana)
        ok(data.fitnessTests.save(ana.id, one))
        val first = data.sync.fitness(uuid)!!.fingerprint
        ok(data.fitnessTests.save(ana.id, two))
        val both = data.sync.fitness(uuid)!!.fingerprint
        assertNotEquals(first, both)
        // a recalculated result is saved with other content
        ok(data.fitnessTests.save(ana.id, two.copy(endDetail = "recalculated")))
        val recalculated = data.sync.fitness(uuid)!!.fingerprint
        assertNotEquals(both, recalculated)
        // a tombstone received from another device
        ok(data.fitnessTests.delete(TEST_2))
        assertEquals(first, data.sync.fitness(uuid)!!.fingerprint)
        ok(data.fitnessTests.delete(TEST_1))
        assertNull(data.sync.fitness(uuid))
    }

    @Test fun `it does not depend on the versions of the results, the sex and birth date, the weights or the resting heart rate`() = runBlocking<Unit> {
        val ana = ana()
        val uuid = uuidOf(ana)
        ok(data.fitnessTests.save(ana.id, one))
        val before = data.sync.fitness(uuid)!!.fingerprint

        // the same document arriving from another device with a later version
        val sameDocument = FitnessTestDocument(TEST_1, uuid, RecordVersion(T0 + 99 * DAY_MS, "phone"), false, FitnessTestJson.encode(one))
        assertEquals(1, data.fitnessTestDocuments.applyRemote(listOf(sameDocument)))
        // the profile's sex and birth date (they do not change in practice and are not part of it)
        ok(data.profiles.update(ana.id, validInput("Ana").copy(sex = Sex.Male, birthDate = LocalDate.of(1990, 1, 1))))
        // other data of the user
        ok(data.weights.add(ana.id, LocalDate.of(2026, 10, 1), 70.0))
        ok(data.restingHeartRates.add(ana.id, RestingHeartRateInput(T0 - DAY_MS, 55, RestingHeartRateSource.Manual)))
        assertEquals(before, data.sync.fitness(uuid)!!.fingerprint)
    }

    @Test fun `a result held while the test runs is not part of it, and a deleted user has none`() = runBlocking<Unit> {
        val ana = ana()
        val uuid = uuidOf(ana)
        ok(data.fitnessTests.save(ana.id, one))
        val before = data.sync.fitness(uuid)!!
        data.fitnessTests.holdUpload(TEST_2)
        ok(data.fitnessTests.save(ana.id, two))
        assertEquals(before.fingerprint, data.sync.fitness(uuid)!!.fingerprint)
        data.fitnessTests.releaseUpload(TEST_2)
        assertNotEquals(before.fingerprint, data.sync.fitness(uuid)!!.fingerprint)
        // the profile goes with its data
        ok(data.profiles.create(validInput("Beto")))
        ok(data.profiles.delete(ana.id))
        assertNull(data.sync.fitness(uuid))
    }

    // ------------------------------------------------------------ the rules version

    /**
     * Pins what the calculation derives from a fixed set of results. If this test fails the output of
     * `FitnessProfileCalculator` or `FitnessProfiles.snapshot` changed: raise [FitnessProfiles.RULES_VERSION] (so that every
     * device writes `fitness.json` again and an older app never overwrites the new file) and update the expected value.
     */
    @Test fun `the snapshot of a fixed set of results is pinned to the rules version`() {
        val results = listOf(one, two, ebbelingResult(TEST_3, T0 - 5 * DAY_MS, vo2max = 44.0))
        val snapshot = checkNotNull(FitnessProfiles.snapshot(results, Sex.Female, LocalDate.of(1986, 10, 8)))
        val digest = java.security.MessageDigest.getInstance("SHA-256")
            .digest(FitnessTestJson.encodeProfile(snapshot).toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
        assertEquals(
            "The fitness profile derived from fixed results changed: raise FitnessProfiles.RULES_VERSION (now ${FitnessProfiles.RULES_VERSION}) and update this value",
            PINNED_SNAPSHOT_DIGEST_FOR_RULES_VERSION_1,
            digest,
        )
        assertEquals(1, FitnessProfiles.RULES_VERSION)
    }

    private companion object {
        const val PINNED_SNAPSHOT_DIGEST_FOR_RULES_VERSION_1 = "8e57f2e3f4e2d1dedfb4028ae34d248619ccf95110fe5cf6768648c191e4ea9e"
    }
}
