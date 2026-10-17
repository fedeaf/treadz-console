package com.treadz.console.sync

import com.treadz.console.analysis.thresholds.ThresholdReason
import com.treadz.console.programs.fitness.FitnessTestJson
import com.treadz.console.programs.fitness.FitnessTestRecalculation
import com.treadz.console.programs.fitness.FitnessTestResult
import com.treadz.console.userdata.DataResult
import com.treadz.console.userdata.UserProfile
import com.treadz.console.userdata.WorkoutEndReason
import com.treadz.console.userdata.WorkoutLink
import com.treadz.console.userdata.WorkoutSample
import com.treadz.console.userdata.WorkoutStats
import com.treadz.console.userdata.workout.WorkoutSessionStore
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * Results stored by an older app are recalculated from their workouts (VO2max of the walking test, thresholds) after a
 * pull that brings them, and two devices that do it write the same document: with synthetic data and a fake GitHub.
 */
@RunWith(RobolectricTestRunner::class)
class FitnessRecalculationSyncTest {

    private val github = FakeGitHub().apply { validTokens += TOKEN }
    private val repo = github.addRepo(FakeGitHub.Repo("fede", "treadz-data"))

    private fun device(name: String, seed: Long, recalculates: Boolean = false) =
        Device(name, github, seed, seedGenericProfile = false, recalculates = recalculates)

    private fun paths() = repo.files().keys.toList().sorted()
    private fun folder() = paths().first { it.startsWith("users/ana-") && it.endsWith("/profile.json") }.removeSuffix("profile.json")
    private fun testFile() = repo.text("${folder()}fitness-tests/$TEST_3.json")!!
    private fun profileFile() = repo.text("${folder()}profile.json")!!

    /** The `result` part of a fitness test file (what must be the same whoever wrote it), without the version. */
    private fun resultPart(file: String) = file.substringAfter("\"result\": ").substringBeforeLast(",\n  \"updated_at\"")

    private fun <T> ok(r: DataResult<T>): T = (r as DataResult.Success).value

    private fun Device.profile() = profiles().first { it.name == "Ana" }

    private fun Device.result(): FitnessTestResult? = runBlocking { data.fitnessTests.get(TEST_3) }?.result

    private fun Device.workoutWith(user: UserProfile, samples: List<WorkoutSample>, testId: String = TEST_3): Long = runBlocking {
        val store = data.workouts as WorkoutSessionStore
        val id = store.begin(user.id, T0, WorkoutLink(fitnessTestId = testId))
        store.finish(
            id, samples,
            WorkoutStats(durationSeconds = samples.size, distanceMeters = 800, averageSpeedKmh = 5.5, maxSpeedKmh = 5.5, averageHeartRateBpm = 140, maxHeartRateBpm = 168, maxInclinePercent = 15.0),
            T0 + 1000L * samples.size, WorkoutEndReason.Stopped,
        )
        id
    }

    /** A device not updated yet records the test: the old result (no outputs, no thresholds) and its workout. */
    private fun oldDeviceWithTest(withWorkout: Boolean = true): Device {
        val old = device("old", 1)
        val ana = old.createUser("Ana")
        ok(runBlocking { old.data.fitnessTests.save(ana.id, balkeResult(TEST_3, T0)) })
        if (withWorkout) old.workoutWith(ana, SyntheticTestWorkout.walking())
        old.sync()
        return old
    }

    @Test fun `a pull that brings an old result recalculates it and the new document goes up in the same cycle`() {
        val old = oldDeviceWithTest()
        assertFalse(testFile(), testFile().contains("\"thresholds\""))
        assertTrue(testFile(), testFile().contains("\"schemaVersion\": 1"))

        val tablet = device("tablet", 2, recalculates = true)
        val report = tablet.at(T0 + 1_000).sync()
        assertNotNull(report.commitSha)

        val file = testFile()
        assertTrue(file, file.contains("\"schemaVersion\": 2"))
        assertTrue(file, file.contains("\"method\": \"acsmWalkRamp\""))
        assertTrue(file, file.contains("\"masIsEstimated\": true"))
        assertTrue(file, file.contains("\"thresholds\": {"))
        assertTrue(file, file.contains("\"algorithmVersion\": ${FitnessTestRecalculation.ALGORITHM_VERSION}"))
        assertTrue(file, file.contains("\"vt1\": {"))
        assertEquals("tablet", Regex("\"updated_by\": \"([^\"]+)\"").find(file)!!.groupValues[1])
        assertTrue(repo.log().last(), repo.log().last().startsWith("Ana · fitness test 2026-10-08 00:40 · max heart rate walk · VO2max 36."))
        assertTrue(repo.log().last(), repo.log().last().contains("estimated MAS 10."))
        assertTrue(repo.log().last(), repo.log().last().contains("VT1 1"))

        // profile.json carries the VO2max of the walking test and the thresholds profile
        val profile = profileFile()
        assertTrue(profile, profile.contains("\"thresholds\": {"))
        assertTrue(profile, profile.contains("\"testType\": \"maxHeartRateWalk\""))
        assertTrue(profile, profile.contains("\"estimated\": true"))

        // the device that was not updated reads the new document; what it reads is the recalculated result
        old.at(T0 + 2_000).sync()
        assertEquals(tablet.result(), old.result())
        assertNotNull(old.result()!!.thresholds!!.vt1)
        assertEquals(old.result()!!.thresholds!!.vt1, tablet.result()!!.thresholds!!.vt1)

        // nothing left to do anywhere: no new commits, no new recalculation
        val commits = repo.log().size
        listOf(tablet, old, tablet).forEach { assertNull(it.at(T0 + 3_000).sync().commitSha) }
        assertEquals(commits, repo.log().size)
    }

    @Test fun `two devices that recalculate the same result write the same document and do not rewrite each other's`() {
        val old = oldDeviceWithTest()
        val phone = device("phone", 3)
        phone.at(T0 + 1_000).sync() // pulls the old result; this device has not been updated yet
        val tablet = device("tablet", 2, recalculates = true)
        tablet.at(T0 + 2_000).sync()
        val firstFile = testFile()
        assertEquals("tablet", Regex("\"updated_by\": \"([^\"]+)\"").find(firstFile)!!.groupValues[1])

        // the phone is updated: it recalculates at app start, before it has seen the tablet's document
        phone.recalculates = true
        phone.at(T0 + 3_000)
        assertEquals(1, runBlocking { phone.data.fitnessRecalculator.recalculate() })
        assertEquals(tablet.result(), phone.result())
        assertEquals(1, runBlocking { phone.data.fitnessTestDocuments.pending() }.size)

        // its (later) version wins on the repository: same result, other writer and time
        phone.sync()
        val secondFile = testFile()
        assertEquals("phone", Regex("\"updated_by\": \"([^\"]+)\"").find(secondFile)!!.groupValues[1])
        assertNotEquals(firstFile, secondFile)
        assertEquals(resultPart(firstFile), resultPart(secondFile))

        // and everybody settles: the tablet takes the phone's version and nobody writes again
        tablet.at(T0 + 4_000).sync()
        old.at(T0 + 4_500).sync()
        val commits = repo.log().size
        listOf(tablet, phone, old, tablet, phone).forEach { assertNull(it.at(T0 + 5_000).sync().commitSha) }
        assertEquals(commits, repo.log().size)
        assertEquals(tablet.result(), phone.result())
        assertEquals(tablet.result(), old.result())
        assertEquals(0, runBlocking { tablet.data.fitnessTestDocuments.pending() }.size + runBlocking { phone.data.fitnessTestDocuments.pending() }.size)
    }

    @Test fun `a result whose workout has not arrived is completed when the workout is pulled`() {
        val old = oldDeviceWithTest(withWorkout = false)
        val tablet = device("tablet", 2, recalculates = true)
        tablet.at(T0 + 1_000).sync()
        assertEquals(ThresholdReason.NoWorkoutSamples, tablet.result()!!.thresholds!!.reason)
        val onlyStages = testFile()
        assertTrue(onlyStages, onlyStages.contains("\"reason\": \"noWorkoutSamples\""))
        assertTrue(onlyStages, onlyStages.contains("\"method\": \"acsmWalkRamp\""))

        // no workout: nothing is tried again
        val commits = repo.log().size
        assertNull(tablet.at(T0 + 2_000).sync().commitSha)
        assertEquals(commits, repo.log().size)

        // the workout is uploaded by the other device and pulled by the tablet
        old.at(T0 + 3_000).sync() // takes the tablet's version of the result
        old.workoutWith(old.profile(), SyntheticTestWorkout.walking())
        old.at(T0 + 3_500).sync()
        tablet.at(T0 + 4_000).sync()
        val complete = testFile()
        assertFalse(complete, complete.contains("noWorkoutSamples"))
        assertTrue(complete, complete.contains("\"vt1\": {"))
        assertEquals("tablet", Regex("\"updated_by\": \"([^\"]+)\"").find(complete)!!.groupValues[1])
        assertNotNull(tablet.result()!!.thresholds!!.vt1)

        old.at(T0 + 5_000).sync()
        assertEquals(tablet.result(), old.result())
        val settled = repo.log().size
        listOf(tablet, old).forEach { assertNull(it.at(T0 + 6_000).sync().commitSha) }
        assertEquals(settled, repo.log().size)
    }

    @Test fun `the profile file shows the discrepancy and the submaximal factor`() {
        val old = oldDeviceWithTest()
        ok(runBlocking { old.data.fitnessTests.save(old.profile().id, ebbelingResult(TEST_1, T0 - 2 * DAY_MS, vo2max = 60.0)) })
        old.sync()
        val tablet = device("tablet", 2, recalculates = true)
        tablet.at(T0 + 1_000).sync()
        val profile = profileFile()
        assertTrue(profile, profile.contains("\"discrepancy\": true"))
        assertTrue(profile, profile.contains("\"discrepant\": true"))
        assertTrue(profile, profile.contains("\"weightFactor\": 0.25"))
        assertTrue(profile, profile.contains("\"submaximalFactorWithMaximal\": 0.25"))
        assertTrue(profile, profile.contains("\"discrepancyFactor\": 1.5"))
        assertTrue(profile, profile.contains("\"vt1\": {"))
        // the walking result's file decodes to what the tablet holds
        assertEquals(tablet.result(), FitnessTestJson.decode(resultPart(testFile())))
    }

    @Test fun `the README of the data repository documents the new fields`() {
        oldDeviceWithTest()
        val readme = repo.text("README.md")!!
        for (needle in listOf(
            "`schemaVersion` (1 or 2)", "Version 2", "acsmWalkRamp", "masIsEstimated", "`thresholds`", "algorithmVersion",
            "percentHeartRateReserve", "`rSquared`", "noWorkoutSamples", "noRrIntervals", "notEnoughValidWindows", "noAlpha1Decline",
            "vt1OutOfRange", "`vo2max.discrepancy`", "`thresholds`, present when", "recalculated", "weightFactor",
        )) {
            assertTrue("README lacks $needle", readme.contains(needle))
        }
        assertFalse(readme, readme.contains("has no outputs: its measured maximum heart rate"))
    }
}
