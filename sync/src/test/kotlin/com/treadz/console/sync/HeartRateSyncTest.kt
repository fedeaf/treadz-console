package com.treadz.console.sync

import com.treadz.console.programs.fitness.FitnessTestJson
import com.treadz.console.programs.fitness.HeartRateMaxOrigin
import com.treadz.console.programs.fitness.HeartRateSourceKind
import com.treadz.console.programs.model.ProgramKind
import com.treadz.console.userdata.DataResult
import com.treadz.console.userdata.HeartRatePeak
import com.treadz.console.userdata.ProgramSaveResult
import com.treadz.console.userdata.RestingHeartRateInput
import com.treadz.console.userdata.RestingHeartRateMeasurement
import com.treadz.console.userdata.RestingHeartRateSource
import com.treadz.console.userdata.StrapSaveResult
import com.treadz.console.userdata.UserProfile
import com.treadz.console.userdata.WorkoutEndReason
import com.treadz.console.userdata.WorkoutLink
import com.treadz.console.userdata.WorkoutStats
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

/**
 * The heart rate data through a fake GitHub: the resting heart rate history, the strap, the source and RR intervals of the
 * samples, the heart rate peak, the generated session record and the maximal heart-rate walking test.
 */
@RunWith(RobolectricTestRunner::class)
class HeartRateSyncTest {

    private val github = FakeGitHub().apply { validTokens += TOKEN }
    private val repo = github.addRepo(FakeGitHub.Repo("fede", "treadz-data"))

    private fun device(name: String, seed: Long) = Device(name, github, seed, seedGenericProfile = false)

    private fun paths() = repo.files().keys.toList().sorted()

    private fun folderOf(name: String) = paths().first { it.startsWith("users/$name-") && it.endsWith("/profile.json") }.removeSuffix("profile.json")

    private fun <T> ok(r: DataResult<T>): T = (r as DataResult.Success).value

    private fun Device.profile(name: String = "Ana") = profiles().first { it.name == name }

    private fun Device.addResting(
        user: UserProfile, daysAgo: Int, bpm: Int, source: RestingHeartRateSource = RestingHeartRateSource.Strap,
        lowest: Double? = null, coverage: Double? = null, rmssd: Double? = null,
    ) = ok(runBlocking { data.restingHeartRates.add(user.id, RestingHeartRateInput(clock.nowMillis - daysAgo * DAY_MS, bpm, source, lowest, coverage, rmssd)) })

    private fun Device.resting(user: UserProfile): List<RestingHeartRateMeasurement> = runBlocking { data.restingHeartRates.measurements(user.id).first() }

    private fun Device.pending() = runBlocking { data.sync.pendingCount.first() }

    /** What a measurement says, without the local ids. */
    private fun shape(m: List<RestingHeartRateMeasurement>) = m.map { listOf(it.measuredAtMillis, it.bpm, it.source, it.lowestMeanBpm, it.coverage, it.rmssdMs) }

    // ------------------------------------------------------------ resting heart rate

    @Test fun `the resting heart rate history is uploaded as a csv, the profile file shows the reference, and the other device gets it all`() {
        val a = device("tablet", 1)
        val ana = a.createUser("Ana")
        a.sync()
        val profileBefore = repo.text("${folderOf("ana")}profile.json")!!
        assertTrue(profileBefore, profileBefore.contains("\"resting_hr_bpm\": null"))

        a.addResting(ana, 3, 56, lowest = 55.6, coverage = 0.97, rmssd = 42.5)
        a.addResting(ana, 2, 58, RestingHeartRateSource.Console)
        a.addResting(ana, 1, 57, RestingHeartRateSource.Manual)
        a.sync()

        val folder = folderOf("ana")
        val csv = repo.text("${folder}resting-heart-rate.csv")!!
        assertEquals(
            "uuid,measured_at,bpm,source,lowest_mean_bpm,coverage,rmssd_ms,deleted,updated_at,updated_by",
            csv.lines().first(),
        )
        assertEquals(listOf("56,strap,55.6,0.97,42.5", "58,console,,,", "57,manual,,,"), csv.lines().drop(1).filter { it.isNotBlank() }.map { it.split(',').subList(2, 7).joinToString(",") })
        assertEquals("Sync · 3 resting heart rates · Ana", repo.log().last())
        // the profile file shows the reference (the median) and keeps its own version
        val profile = repo.text("${folder}profile.json")!!
        assertTrue(profile, profile.contains("\"resting_hr_bpm\": 57"))
        assertEquals(
            profileBefore.substringAfter("\"updated_at\""),
            profile.substringAfter("\"updated_at\""),
        )

        val b = device("phone", 2)
        b.sync()
        val anaB = b.profile()
        assertEquals(shape(a.resting(ana)), shape(b.resting(anaB)))
        assertEquals(57, anaB.restingHeartRateBpm)
        assertEquals(0, b.pending())
        val commits = repo.log().size
        listOf(a, b, a, b).forEach { assertNull(it.sync().commitSha) }
        assertEquals(commits, repo.log().size)
        assertTrue(repo.text("README.md")!!.contains("resting-heart-rate.csv"))
    }

    @Test fun `an edit and a deletion follow and the last edit wins`() {
        val a = device("tablet", 1)
        val ana = a.createUser("Ana")
        val first = a.addResting(ana, 3, 56)
        val second = a.addResting(ana, 2, 58)
        a.addResting(ana, 1, 60)
        a.sync()
        val b = device("phone", 2)
        b.sync()
        val anaB = b.profile()

        // the phone corrects one value
        b.at(T0 + 1_000)
        val onB = b.resting(anaB).first { it.bpm == 56 }
        ok(runBlocking { b.data.restingHeartRates.update(onB.id, RestingHeartRateInput(onB.measuredAtMillis, 54, onB.source)) })
        b.sync()
        assertEquals("Ana · resting heart rate 2026-10-05 · 54 bpm", repo.log().last())
        a.sync()
        assertEquals(listOf(54, 58, 60), a.resting(ana).map { it.bpm })
        assertEquals(first.id, a.resting(ana).first().id)

        // the tablet deletes another one: a row without values stays in the file
        a.at(T0 + 2_000)
        ok(runBlocking { a.data.restingHeartRates.delete(second.id) })
        a.sync()
        assertEquals("Ana · resting heart rate deleted", repo.log().last())
        val csv = repo.text("${folderOf("ana")}resting-heart-rate.csv")!!
        val tombstone = csv.lines().first { ",true," in it }
        assertTrue(tombstone, tombstone.split(',').subList(1, 7).all { it.isEmpty() || it == "0" })
        assertEquals(3, csv.lines().count { it.isNotBlank() } - 1)
        b.sync()
        assertEquals(listOf(54, 60), b.resting(anaB).map { it.bpm })
        assertEquals(0, b.pending())

        // the same measurement edited on both: the later edit wins on both devices
        b.at(T0 + 3_000)
        val phone = b.resting(anaB).first { it.bpm == 60 }
        ok(runBlocking { b.data.restingHeartRates.update(phone.id, RestingHeartRateInput(phone.measuredAtMillis, 61, phone.source)) })
        a.at(T0 + 4_000)
        val tablet = a.resting(ana).first { it.bpm == 60 }
        ok(runBlocking { a.data.restingHeartRates.update(tablet.id, RestingHeartRateInput(tablet.measuredAtMillis, 62, tablet.source)) })
        b.sync()
        a.sync()
        b.sync()
        assertEquals(listOf(54, 62), a.resting(ana).map { it.bpm })
        assertEquals(listOf(54, 62), b.resting(anaB).map { it.bpm })

        // and with everything deleted the file shows no reference
        a.at(T0 + 5_000)
        a.resting(ana).forEach { ok(runBlocking { a.data.restingHeartRates.delete(it.id) }) }
        a.sync()
        assertTrue(repo.text("${folderOf("ana")}profile.json")!!.contains("\"resting_hr_bpm\": null"))
        b.sync()
        assertTrue(b.resting(anaB).isEmpty())
        assertNull(b.profile().restingHeartRateBpm)
    }

    @Test fun `a profile file from before the history becomes one manual measurement on every device, without duplicates`() {
        val a = device("tablet", 1)
        a.createUser("Ana")
        a.sync()
        val folder = folderOf("ana")
        // an older app wrote the resting value in the profile file and knows nothing of the csv
        val old = repo.text("${folder}profile.json")!!
            .replace("\"resting_hr_bpm\": null", "\"resting_hr_bpm\": 58")
            .replace(Regex("\"updated_at\": \"[^\"]+\""), "\"updated_at\": \"2026-10-08T01:00:00.000Z\"")
            .replace(Regex("\"updated_by\": \"[^\"]+\""), "\"updated_by\": \"old-phone\"")
        replaceFile("${folder}profile.json", old)

        a.sync()
        val ana = a.profile()
        assertEquals(58, ana.restingHeartRateBpm)
        val migrated = a.resting(ana).single()
        assertEquals(RestingHeartRateSource.Manual, migrated.source)
        assertEquals(58, migrated.bpm)
        assertEquals(T0 + 20 * 60_000, migrated.measuredAtMillis) // the time of the profile edit
        assertEquals("what comes from the repository is not uploaded back", 0, a.pending())

        val b = device("phone", 2)
        b.sync()
        assertEquals(58, b.profile().restingHeartRateBpm)
        assertEquals(1, b.resting(b.profile()).size)
        assertEquals(0, b.pending())

        // a new measurement creates the file, with the old value in it once; the other device gets it without a duplicate
        b.at(T0 + 3 * 3_600_000L) // after the profile edit that carried the old value
        b.addResting(b.profile(), 0, 62)
        b.sync()
        val csv = repo.text("${folder}resting-heart-rate.csv")!!
        assertEquals(2, csv.lines().count { it.isNotBlank() } - 1)
        a.at(T0 + 4 * 3_600_000L)
        a.sync()
        assertEquals(listOf(58, 62), a.resting(a.profile()).map { it.bpm })
        assertEquals(60, a.profile().restingHeartRateBpm) // the median of 58 and 62
        assertEquals(0, a.pending())
        val commits = repo.log().size
        listOf(a, b).forEach { assertNull(it.sync().commitSha) }
        assertEquals(commits, repo.log().size)
    }

    // ------------------------------------------------------------ strap

    @Test fun `the strap of a user is in the profile file and the other device finds the same strap`() {
        val a = device("tablet", 1)
        val ana = a.createUser("Ana")
        a.sync()
        assertTrue(runBlocking { a.data.profiles.setStrap(ana.id, "aa:bb:cc:dd:ee:ff", "Polar H10 1234AB") } is StrapSaveResult.Saved)
        a.sync()
        val profile = repo.text("${folderOf("ana")}profile.json")!!
        assertTrue(profile, profile.contains("\"strap_address\": \"AA:BB:CC:DD:EE:FF\""))
        assertTrue(profile, profile.contains("\"strap_name\": \"Polar H10 1234AB\""))
        assertEquals("Ana · profile", repo.log().last())

        val b = device("phone", 2)
        b.sync()
        assertEquals("AA:BB:CC:DD:EE:FF", b.profile().strapAddress)
        assertEquals("Polar H10 1234AB", b.profile().strapName)

        // the user pairs another strap on the phone, then forgets it
        b.at(T0 + 1_000)
        runBlocking { b.data.profiles.setStrap(b.profile().id, "11:22:33:44:55:66", null) }
        b.sync()
        a.sync()
        assertEquals("11:22:33:44:55:66", a.profile().strapAddress)
        assertNull(a.profile().strapName)
        a.at(T0 + 2_000)
        ok(runBlocking { a.data.profiles.clearStrap(a.profile().id) })
        a.sync()
        assertFalse(repo.text("${folderOf("ana")}profile.json")!!.contains("strap"))
        b.sync()
        assertNull(b.profile().strapAddress)
    }

    // ------------------------------------------------------------ workouts

    private val samples = strapSamples(T0, seconds = 8, bpm = 150)

    private val peak = HeartRatePeak(150, HeartRateSourceKind.DirectStrap, T0 + 7_000)

    private fun Device.finishStrapWorkout(user: UserProfile, link: WorkoutLink? = null, hold: Boolean = false): Long = runBlocking {
        val store = data.workouts as WorkoutSessionStore
        val id = store.begin(user.id, T0, link)
        if (hold) data.workouts.holdUpload(id)
        store.finish(
            id, samples,
            WorkoutStats(durationSeconds = 8, distanceMeters = 14, calories = 2, averageSpeedKmh = 5.5, maxSpeedKmh = 5.5, averageHeartRateBpm = 150, maxHeartRateBpm = 150, maxInclinePercent = 5.0, peakHeartRate = peak),
            T0 + 8_000, WorkoutEndReason.Stopped,
        )
        id
    }

    @Test fun `source, RR intervals, peak and the session record reach the other device with the held workout kept or discarded`() {
        val a = device("tablet", 1)
        val ana = a.createUser("Ana")
        a.sync()
        val plan = planOf(personalised(generatorId = GENERATOR_C25K))
        val record = sessionRecord(GENERATOR_C25K, T0, session = 3)

        // held until the user decides: the record attached meanwhile is part of what is uploaded afterwards
        val id = a.finishStrapWorkout(ana, WorkoutLink(plan = plan), hold = true)
        ok(runBlocking { a.data.workouts.attachSessionRecord(id, record) })
        assertNull(a.sync().commitSha)
        assertTrue(paths().none { "/workouts/" in it })
        a.data.workouts.releaseUpload(id)
        a.sync()

        val folder = folderOf("ana")
        val csvPath = paths().single { it.startsWith("${folder}workouts/") && it.endsWith(".csv") }
        val csv = repo.text(csvPath)!!
        assertEquals(
            "seq,timestamp,elapsed_s,speed_kmh,target_speed_kmh,incline_pct,distance_m,kcal,hr_bpm,belt_status,stale,elevation_gain_m,hr_source,rr_ms",
            csv.lines().first(),
        )
        assertTrue(csv, csv.lines()[1].endsWith(",direct_strap,400;403"))
        val json = repo.text(csvPath.removeSuffix(".csv") + ".json")!!
        assertTrue(json, json.contains("\"peak_hr_bpm\": 150"))
        assertTrue(json, json.contains("\"peak_hr_source\": \"direct_strap\""))
        assertTrue(json, json.contains("\"generator_id\": \"$GENERATOR_C25K\""))
        assertTrue(json, json.contains("\"session_record\": {"))

        val b = device("phone", 2)
        b.sync()
        val anaB = b.profile()
        val onB = runBlocking { b.data.workouts.workouts(anaB.id).first() }.single()
        assertEquals(GENERATOR_C25K, onB.generatorId)
        assertEquals(peak, onB.stats.peakHeartRate)
        val detail = runBlocking { b.data.workouts.detail(onB.id) }!!
        assertEquals(samples.map { it.rrIntervalsMs }, detail.samples.map { it.rrIntervalsMs })
        assertEquals(samples.map { it.heartRateSource }, detail.samples.map { it.heartRateSource })
        assertEquals(samples.map { it.heartRateBpm }, detail.samples.map { it.heartRateBpm })
        assertEquals(record, detail.sessionRecord)
        assertEquals(plan, detail.plan)
        assertEquals(listOf(record), runBlocking { b.data.workouts.sessionRecords(anaB.id, GENERATOR_C25K).first() }.map { it.record })
        assertEquals(150, runBlocking { b.data.workouts.highestHeartRatePeak(anaB.id).first() }!!.bpm)
        assertEquals(0, b.pending())

        // a discarded one never reaches the repository
        val commits = repo.log().size
        val discarded = a.finishStrapWorkout(ana, WorkoutLink(plan = plan), hold = true)
        ok(runBlocking { a.data.workouts.attachSessionRecord(discarded, record) })
        ok(runBlocking { a.data.workouts.discardFinished(discarded) })
        assertNull(a.sync().commitSha)
        assertEquals(commits, repo.log().size)
        assertEquals(1, paths().count { it.endsWith(".csv") && "/workouts/" in it })
    }

    @Test fun `workout files written before the heart rate columns existed are still read`() {
        val a = device("tablet", 1)
        val ana = a.createUser("Ana")
        a.finishWorkout(ana, T0)
        a.sync()
        val csvPath = paths().single { it.endsWith(".csv") && "/workouts/" in it }
        val old = repo.text(csvPath)!!.lines().joinToString("\n") { line -> line.split(',').take(12).joinToString(",") }
        replaceFile(csvPath, old)
        val jsonPath = csvPath.removeSuffix(".csv") + ".json"
        replaceFile(jsonPath, repo.text(jsonPath)!!.replace("\"updated_at\"", "\"updated_at\"").replace(Regex("\"updated_at\": \"[^\"]+\""), "\"updated_at\": \"2027-01-01T00:00:00.000Z\""))
        val b = device("phone", 2)
        b.sync()
        val onB = runBlocking { b.data.workouts.workouts(b.profile().id).first() }.single()
        val read = runBlocking { b.data.workouts.detail(onB.id) }!!
        assertEquals(3, read.samples.size)
        assertTrue(read.samples.all { it.heartRateSource == null && it.rrIntervalsMs.isEmpty() })
        assertNull(onB.stats.peakHeartRate)
        assertNull(onB.generatorId)
        assertNull(read.sessionRecord)
    }

    // ------------------------------------------------------------ programs and tests

    @Test fun `a personalised program keeps its origin through the repository`() {
        val a = device("tablet", 1)
        val ana = a.createUser("Ana")
        val saved = (runBlocking { a.data.programs.save(ana.id, personalised()) } as ProgramSaveResult.Saved).program.program
        a.sync()
        val file = repo.text("${folderOf("ana")}programs/$PROGRAM_2.json")!!
        assertTrue(file, file.contains("\"kind\": \"personalised\""))
        assertTrue(file, file.contains("\"generatorId\": \"$GENERATOR_4X4\""))

        val b = device("phone", 2)
        b.sync()
        val remote = runBlocking { b.data.programs.get(PROGRAM_2) }!!.program
        assertEquals(ProgramKind.Personalised, remote.kind)
        assertEquals(origin(), remote.origin)
        assertEquals(saved, remote)
    }

    @Test fun `the maximal heart rate walking test travels like the other tests, with its workout`() {
        val a = device("tablet", 1)
        val ana = a.createUser("Ana")
        a.sync()
        val result = balkeResult(TEST_3, T0 - DAY_MS, peak = 186)
        ok(runBlocking { a.data.fitnessTests.save(ana.id, result) })
        a.sync()
        assertEquals("Ana · fitness test 2026-10-07 00:40 · max heart rate walk · HRmax 186", repo.log().last())
        a.finishStrapWorkout(ana, WorkoutLink(fitnessTestId = TEST_3))
        a.sync()

        val folder = folderOf("ana")
        val file = repo.text("${folder}fitness-tests/$TEST_3.json")!!
        assertTrue(file, file.contains("\"testType\": \"maxHeartRateWalk\""))
        assertEquals(result, FitnessTestJson.decode(file.substringAfter("\"result\": ").substringBeforeLast(",\n  \"updated_at\"")))
        val profile = repo.text("${folder}profile.json")!!
        assertTrue(profile, profile.contains("\"measuredBpm\": 186"))

        val b = device("phone", 2)
        b.sync()
        val anaB = b.profile()
        assertEquals(result, runBlocking { b.data.fitnessTests.get(TEST_3) }!!.result)
        val decision = runBlocking { b.data.fitnessTests.heartRateMax(anaB.id).first() }!!
        assertEquals(HeartRateMaxOrigin.Measured, decision.origin)
        assertEquals(186, decision.bpm)
        assertEquals(
            runBlocking { a.data.fitnessTests.fitnessProfile(ana.id).first() },
            runBlocking { b.data.fitnessTests.fitnessProfile(anaB.id).first() },
        )
        val workout = runBlocking { b.data.workouts.workouts(anaB.id).first() }.single()
        assertEquals(TEST_3, workout.fitnessTestId)
        val detail = runBlocking { b.data.workouts.detail(workout.id) }!!
        assertEquals(samples.flatMap { it.rrIntervalsMs }, detail.samples.flatMap { it.rrIntervalsMs })
        assertEquals(HeartRateSourceKind.DirectStrap, detail.samples.first().heartRateSource)
        assertEquals(0, b.pending() + runBlocking { b.data.fitnessTestDocuments.pendingCount.first() })
        val commits = repo.log().size
        listOf(a, b).forEach { assertNull(it.sync().commitSha) }
        assertEquals(commits, repo.log().size)
        assertNotNull(workout.stats.peakHeartRate)
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
