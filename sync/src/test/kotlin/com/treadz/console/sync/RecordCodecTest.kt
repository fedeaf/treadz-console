package com.treadz.console.sync

import com.treadz.console.sync.format.RecordCodec
import com.treadz.console.sync.format.RepoPaths
import com.treadz.console.userdata.FitnessFingerprint
import com.treadz.console.userdata.FitnessLevel
import com.treadz.console.userdata.FitnessProfiles
import com.treadz.console.userdata.Goal
import com.treadz.console.userdata.Sex
import com.treadz.console.userdata.WorkoutEndReason
import com.treadz.console.userdata.WorkoutEvent
import com.treadz.console.userdata.WorkoutEventQuantity
import com.treadz.console.userdata.WorkoutEventType
import com.treadz.console.userdata.WorkoutStats
import com.treadz.console.programs.fitness.FitnessTestJson
import com.treadz.console.programs.json.PlanJson
import com.treadz.console.userdata.sync.FitnessTestDocument
import com.treadz.console.userdata.sync.ProgramDocument
import com.treadz.console.userdata.sync.RecordVersion
import com.treadz.console.userdata.sync.SyncFitness
import com.treadz.console.userdata.sync.SyncProfile
import com.treadz.console.userdata.sync.SyncWeightEntry
import com.treadz.console.userdata.sync.SyncWorkout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.time.LocalDate
import java.time.ZoneOffset

@RunWith(RobolectricTestRunner::class)
class RecordCodecTest {

    private val v = RecordVersion(T0 + 123, "tablet")
    private val uuid = "11111111-2222-4333-8444-555555555555"

    private fun text(bytes: ByteArray) = String(bytes, Charsets.UTF_8)

    private val profile = SyncProfile(
        uuid, v, false, "Ana María", Sex.Female, 168, LocalDate.of(1986, 10, 8), null, 184,
        FitnessLevel.Intermediate, Goal.LoseWeight, 62.5, T0 - 1000,
    )

    @Test fun `profile json has a fixed readable layout and round trips`() {
        assertEquals(
            """
            {
              "format": 1,
              "uuid": "11111111-2222-4333-8444-555555555555",
              "deleted": false,
              "name": "Ana Mar${'í'}a",
              "sex": "female",
              "height_cm": 168,
              "birth_date": "1986-10-08",
              "max_hr_bpm": 184,
              "fitness_level": "intermediate",
              "goal": "lose_weight",
              "target_weight_kg": 62.5,
              "created_at": "2026-10-08T00:39:59.000Z",
              "updated_at": "2026-10-08T00:40:00.123Z",
              "updated_by": "tablet"
            }
            """.trimIndent() + "\n",
            text(RecordCodec.profile(profile)),
        )
        assertEquals(profile, RecordCodec.parseProfile(RecordCodec.profile(profile)))
        val bare = profile.copy(maxHeartRateBpm = null, fitnessLevel = null, goal = null, targetWeightKg = null)
        assertEquals(bare, RecordCodec.parseProfile(RecordCodec.profile(bare)))
        assertTrue(text(RecordCodec.profile(bare)).contains("\"goal\": null"))
    }

    @Test fun `a profile tombstone keeps no personal data but the name`() {
        val tombstone = profile.copy(deleted = true)
        val json = text(RecordCodec.profile(tombstone))
        assertEquals(
            listOf("format", "uuid", "deleted", "name", "updated_at", "updated_by"),
            Regex("\"([a-z_]+)\":").findAll(json).map { it.groupValues[1] }.toList(),
        )
        val parsed = RecordCodec.parseProfile(json.toByteArray())!!
        assertTrue(parsed.deleted)
        assertEquals(uuid, parsed.uuid)
        assertEquals("Ana María", parsed.name)
        assertEquals(v, parsed.version)
    }

    @Test fun `names with quotes, backslashes and control characters survive`() {
        val odd = profile.copy(name = "A \"quoted\" \\ name\twith tab")
        assertEquals(odd, RecordCodec.parseProfile(RecordCodec.profile(odd)))
    }

    @Test fun `unknown fields are ignored, unknown codes drop the record or the value`() {
        val json = text(RecordCodec.profile(profile)).replace("\"deleted\": false,", "\"deleted\": false,\n  \"future_field\": {\"a\": 1},")
        assertEquals(profile, RecordCodec.parseProfile(json.toByteArray()))
        assertNull(RecordCodec.parseProfile(json.replace("\"female\"", "\"robot\"").toByteArray()))
        assertEquals(null, RecordCodec.parseProfile(json.replace("\"lose_weight\"", "\"fly\"").toByteArray())!!.goal)
        assertNull(RecordCodec.parseProfile("not json".toByteArray()))
        assertNull(RecordCodec.parseProfile("{}".toByteArray()))
    }

    @Test fun `weight csv is ordered by date, keeps tombstones without data and skips damaged rows`() {
        val entries = listOf(
            SyncWeightEntry("w-2", uuid, v, false, LocalDate.of(2026, 10, 5), 61.0),
            SyncWeightEntry("w-1", uuid, v, false, LocalDate.of(2026, 10, 1), 61.5),
            SyncWeightEntry("w-3", uuid, RecordVersion(T0 + 5, "phone"), true, LocalDate.of(2026, 10, 3), 70.0),
        )
        val csv = text(RecordCodec.weightCsv(entries))
        assertEquals(
            """
            uuid,date,weight_kg,deleted,updated_at,updated_by,created_at
            w-1,2026-10-01,61.5,false,2026-10-08T00:40:00.123Z,tablet,2026-10-08T00:40:00.123Z
            w-3,,,true,2026-10-08T00:40:00.005Z,phone,2026-10-08T00:40:00.005Z
            w-2,2026-10-05,61.0,false,2026-10-08T00:40:00.123Z,tablet,2026-10-08T00:40:00.123Z
            """.trimIndent() + "\n",
            csv,
        )
        val parsed = RecordCodec.parseWeightCsv(csv.toByteArray(), uuid)
        assertEquals(listOf("w-1", "w-3", "w-2"), parsed.map { it.uuid })
        assertTrue(parsed[1].deleted)
        assertEquals(61.5, parsed[0].weightKg, 0.0)
        assertEquals(uuid, parsed[0].userUuid)
        val damaged = csv + "w-9,not a date,70,false,2026-10-08T00:40:00.123Z,x\n,,,,,\n"
        assertEquals(3, RecordCodec.parseWeightCsv(damaged.toByteArray(), uuid).size)
    }

    @Test fun `entries of one day are ordered by creation time then uuid, whatever the order they come in`() {
        val day = LocalDate.of(2026, 10, 8)
        fun entry(id: String, createdAt: Long, kg: Double, updatedAt: Long = T0 + 100_000) =
            SyncWeightEntry(id, uuid, RecordVersion(updatedAt, "tablet"), false, day, kg, createdAtMillis = createdAt)
        val entries = listOf(entry("c", T0 + 2, 88.2), entry("b", T0 + 1, 88.0, updatedAt = T0 + 500_000), entry("a", T0 + 2, 87.0))
        for (shuffled in listOf(entries, entries.reversed(), listOf(entries[1], entries[2], entries[0]))) {
            val csv = text(RecordCodec.weightCsv(shuffled))
            val rows = csv.trim().lines().drop(1)
            assertEquals(listOf("b", "a", "c"), rows.map { it.substringBefore(',') }) // by creation time, then uuid
            val parsed = RecordCodec.parseWeightCsv(csv.toByteArray(), uuid)
            assertEquals(entries.sortedBy { it.uuid }, parsed.sortedBy { it.uuid }) // creation times survive the round trip
        }
        assertEquals(T0 + 1, RecordCodec.parseWeightCsv(RecordCodec.weightCsv(entries), uuid).first { it.uuid == "b" }.createdAtMillis)
    }

    @Test fun `a weight csv written before created_at existed still parses and falls back to the update time`() {
        val old = """
            uuid,date,weight_kg,deleted,updated_at,updated_by
            w-1,2026-10-01,61.5,false,2026-10-08T00:40:00.123Z,tablet
            w-3,,,true,2026-10-08T00:40:00.005Z,phone
        """.trimIndent() + "\n"
        val parsed = RecordCodec.parseWeightCsv(old.toByteArray(), uuid)
        assertEquals(listOf("w-1", "w-3"), parsed.map { it.uuid })
        assertEquals(parsed.map { it.version.updatedAtMillis }, parsed.map { it.createdAtMillis })
        // a row with an empty or unreadable created_at falls back the same way
        val partial = old.replace("tablet", "tablet,").replace("phone", "phone,garbage")
            .replace("updated_by\n", "updated_by,created_at\n")
        val fallback = RecordCodec.parseWeightCsv(partial.toByteArray(), uuid)
        assertEquals(parsed.map { it.createdAtMillis }, fallback.map { it.createdAtMillis })
    }

    private val workout = SyncWorkout(
        "99999999-8888-4777-8666-555555555555", uuid, "Ana", v, false, T0, T0 + 45 * 60_000, WorkoutEndReason.SafetyKeyRemoved,
        WorkoutStats(2700, 5200, 310, 7.0, 9.5, 141, 168, 2.5),
    )

    @Test fun `workout stats are written with sensible precision and old precise values are still read`() {
        val precise = workout.copy(stats = workout.stats.copy(averageSpeedKmh = 4.81130063965887, maxSpeedKmh = 6.050000001, maxInclinePercent = 2.4500000001))
        val json = text(RecordCodec.workout(precise))
        assertTrue(json, json.contains("\"avg_speed_kmh\": 4.81,"))
        assertTrue(json, json.contains("\"max_speed_kmh\": 6.05,"))
        assertTrue(json, json.contains("\"max_incline_pct\": 2.5"))
        // half up on the decimal representation, and the same text whatever the default locale
        val previous = java.util.Locale.getDefault()
        try {
            java.util.Locale.setDefault(java.util.Locale.GERMANY)
            assertEquals(json, text(RecordCodec.workout(precise)))
        } finally {
            java.util.Locale.setDefault(previous)
        }
        assertEquals(4.81, RecordCodec.parseWorkout(json.toByteArray(), uuid)!!.stats.averageSpeedKmh, 0.0)
        assertEquals("7.0", RecordCodec.rounded(7.0, 2).toString())
        assertEquals(0.0, RecordCodec.rounded(0.004, 2), 0.0)
        assertTrue(RecordCodec.rounded(Double.NaN, 2).isNaN())
        // a file written by an older version, with every decimal
        val old = json.replace("\"avg_speed_kmh\": 4.81,", "\"avg_speed_kmh\": 4.81130063965887,")
        assertEquals(4.81130063965887, RecordCodec.parseWorkout(old.toByteArray(), uuid)!!.stats.averageSpeedKmh, 0.0)
    }

    @Test fun `workout json round trips and a tombstone keeps only identity and start`() {
        val json = text(RecordCodec.workout(workout))
        assertTrue(json.contains("\"end_reason\": \"safety_key_removed\""))
        assertTrue(json.contains("  \"stats\": {\n    \"duration_s\": 2700,"))
        assertEquals(workout.copy(ownerName = ""), RecordCodec.parseWorkout(json.toByteArray(), "ignored"))

        val tombstone = text(RecordCodec.workout(workout.copy(deleted = true)))
        assertFalse(tombstone.contains("stats"))
        assertFalse(tombstone.contains("ended_at"))
        val parsed = RecordCodec.parseWorkout(tombstone.toByteArray(), uuid)!!
        assertTrue(parsed.deleted)
        assertEquals(T0, parsed.startedAtMillis)
        assertEquals(uuid, parsed.userUuid)

        val noHeartRate = workout.copy(stats = workout.stats.copy(averageHeartRateBpm = null, maxHeartRateBpm = null))
        assertEquals(noHeartRate.copy(ownerName = ""), RecordCodec.parseWorkout(RecordCodec.workout(noHeartRate), uuid))
    }

    @Test fun `a program document keeps its json untouched`() {
        val doc = ProgramDocument("p-1", uuid, v, false, """{"name":"4x4","blocks":[{"min":4,"kmh":9.5}]}""")
        val file = text(RecordCodec.program(doc))
        assertTrue(file.contains("\"program\": {\"name\":\"4x4\",\"blocks\":[{\"min\":4,\"kmh\":9.5}]}"))
        val parsed = RecordCodec.parseProgram(file.toByteArray(), uuid)!!
        assertEquals("p-1", parsed.uuid)
        assertEquals(v, parsed.version)
        assertTrue(parsed.json.contains("\"4x4\""))
        val tombstone = RecordCodec.parseProgram(RecordCodec.program(doc.copy(deleted = true, json = "")), uuid)!!
        assertTrue(tombstone.deleted)
        assertEquals("", tombstone.json)
    }

    @Test fun `the time of the manual maximum heart rate is written when known and left out otherwise`() {
        val withTime = profile.copy(maxHeartRateSetAtMillis = T0 - 500)
        val json = text(RecordCodec.profile(withTime))
        assertTrue(json, json.contains("  \"created_at\": \"2026-10-08T00:39:59.000Z\",\n  \"max_hr_set_at\": \"2026-10-08T00:39:59.500Z\",\n  \"updated_at\""))
        assertEquals(withTime, RecordCodec.parseProfile(json.toByteArray()))
        // a profile file written before the field existed is read as "unknown" and is written back byte for byte
        val old = RecordCodec.profile(profile)
        assertNull(RecordCodec.parseProfile(old)!!.maxHeartRateSetAtMillis)
        assertFalse(text(old).contains("max_hr_set_at"))
    }

    @Test fun `the profile file holds only what the user edits, and the derived values of older files are read as before or ignored`() {
        val json = text(RecordCodec.profile(profile.copy(restingHeartRateBpm = 55)))
        assertFalse("the reference of the resting history is derived: never written", json.contains("resting_hr_bpm"))
        assertFalse(json.contains("\"fitness\":"))

        // a file written by an older version carries both; the fitness block is ignored, the resting value is the legacy one
        val old = json.replace("\"max_hr_bpm\":", "\"resting_hr_bpm\": 55,\n  \"max_hr_bpm\":")
            .replace("\"deleted\": false,", "\"deleted\": false,\n  \"fitness\": {\"schemaVersion\": 99, \"computedAt\": \"nonsense\"},")
        val parsed = RecordCodec.parseProfile(old.toByteArray())!!
        assertEquals(profile.copy(restingHeartRateBpm = 55), parsed)
        assertEquals(55, RecordCodec.legacyResting(parsed)!!.bpm)
        assertNull(RecordCodec.legacyResting(profile))
        // the tombstone has neither
        assertFalse(text(RecordCodec.profile(profile.copy(deleted = true))).contains("resting_hr_bpm"))
    }

    private fun fitness(results: List<com.treadz.console.programs.fitness.FitnessTestResult> = listOf(ebbelingResult(TEST_1, T0 - 1000))): SyncFitness {
        val snapshot = FitnessProfiles.snapshot(results, Sex.Female, LocalDate.of(1986, 10, 8))!!
        return SyncFitness(uuid, FitnessFingerprint.of(results.map { it.id to FitnessTestJson.encode(it) }), FitnessProfiles.RULES_VERSION, snapshot)
    }

    @Test fun `fitness json has the snapshot, the fingerprint, the rules version and the version of the last change`() {
        val f = fitness()
        val json = text(RecordCodec.fitness(f, v))
        assertTrue(json, json.startsWith("{\n  \"format\": 1,\n  \"user_uuid\": \"$uuid\",\n  \"rules_version\": ${f.rulesVersion},\n  \"fingerprint\": \"${f.fingerprint}\",\n  \"fitness\": {\n    \"schemaVersion\": 1,\n    \"computedAt\": \"2026-10-08T00:39:59Z\","))
        assertTrue(json, json.endsWith("\n  },\n  \"updated_at\": \"2026-10-08T00:40:00.123Z\",\n  \"updated_by\": \"tablet\"\n}\n"))
        // the block is the document `profile.json` used to carry, as it was
        assertEquals(f.profile, FitnessTestJson.decodeProfile(json.substringAfter("\"fitness\": ").substringBefore(",\n  \"updated_at\"")))
        // what the engine needs is read back; the block is not
        val remote = RecordCodec.parseFitness(json.toByteArray())!!
        assertEquals(f.fingerprint, remote.fingerprint)
        assertEquals(f.rulesVersion, remote.rulesVersion)
        assertEquals("a file without a rules version is version 0", 0, RecordCodec.parseFitness("{\"fingerprint\": \"x\"}".toByteArray())!!.rulesVersion)
        assertNull(RecordCodec.parseFitness("not json".toByteArray()))
        assertNull(RecordCodec.parseFitness("{}".toByteArray()))
    }

    @Test fun `the fitness json is identical for the same results whatever their order`() {
        val a = ebbelingResult(TEST_1, T0 - 40 * 86_400_000L)
        val b = maxTestResult(TEST_2, T0 - 5 * 86_400_000L)
        assertEquals(text(RecordCodec.fitness(fitness(listOf(a, b)), v)), text(RecordCodec.fitness(fitness(listOf(b, a)), v)))
    }

    @Test fun `a workout with a plan and a test round trips, and an older workout without them is still valid`() {
        val plan = PlanJson.encode(hillPlan())
        val linked = workout.copy(planJson = plan, fitnessTestId = TEST_1)
        val json = text(RecordCodec.workout(linked))
        assertTrue(json, json.contains("\"program_id\": \"standard-hill\""))
        assertTrue(json, json.contains("\"fitness_test_id\": \"$TEST_1\",\n  \"plan\": {\n    \"planSchemaVersion\": 1,"))
        val parsed = RecordCodec.parseWorkout(json.toByteArray(), uuid)!!
        assertEquals(hillPlan(), PlanJson.decode(parsed.planJson!!))
        assertEquals(TEST_1, parsed.fitnessTestId)
        assertEquals(linked.copy(ownerName = "", planJson = parsed.planJson), parsed)

        val plain = text(RecordCodec.workout(workout))
        assertFalse(plain.contains("plan") || plain.contains("program_id") || plain.contains("fitness_test_id"))
        val old = RecordCodec.parseWorkout(plain.toByteArray(), uuid)!!
        assertNull(old.planJson)
        assertNull(old.fitnessTestId)

        val onlyTest = text(RecordCodec.workout(workout.copy(fitnessTestId = TEST_2)))
        assertEquals(TEST_2, RecordCodec.parseWorkout(onlyTest.toByteArray(), uuid)!!.fitnessTestId)
        // a tombstone carries neither
        assertFalse(text(RecordCodec.workout(linked.copy(deleted = true))).contains("plan"))
    }

    @Test fun `the events of a workout are written one per line, round trip, and files without them have none`() {
        val events = listOf(
            WorkoutEvent(305, WorkoutEventType.ManualChange, WorkoutEventQuantity.Speed, 7.5),
            WorkoutEvent(305, WorkoutEventType.ManualChange, WorkoutEventQuantity.Incline, 4.0),
            WorkoutEvent(612, WorkoutEventType.HeartRateDecrease, WorkoutEventQuantity.Speed, 6.4),
            WorkoutEvent(640, WorkoutEventType.HeartRateControlResumed),
            WorkoutEvent(700, WorkoutEventType.MaxHeartRateReached),
        )
        val withEvents = workout.copy(events = events)
        val json = text(RecordCodec.workout(withEvents))
        val expected = """
              "events": [
                {"seconds": 305, "type": "manual_change", "quantity": "speed", "value": 7.5},
                {"seconds": 305, "type": "manual_change", "quantity": "incline", "value": 4.0},
                {"seconds": 612, "type": "heart_rate_decrease", "quantity": "speed", "value": 6.4},
                {"seconds": 640, "type": "heart_rate_control_resumed"},
                {"seconds": 700, "type": "max_heart_rate_reached"}
              ],
              "updated_at"
        """.trimIndent().prependIndent("  ").trimStart()
        assertTrue(json, json.contains(expected))
        assertEquals(withEvents.copy(ownerName = ""), RecordCodec.parseWorkout(json.toByteArray(), uuid))

        // absent when there are none, and a file without the field has none
        val plain = text(RecordCodec.workout(workout))
        assertFalse(plain.contains("events"))
        assertEquals(emptyList<WorkoutEvent>(), RecordCodec.parseWorkout(plain.toByteArray(), uuid)!!.events)
        // events of a type this version does not know are left out; a tombstone has none
        val future = json.replace("heart_rate_decrease", "something_new")
        assertEquals(events - events[2], RecordCodec.parseWorkout(future.toByteArray(), uuid)!!.events)
        assertFalse(text(RecordCodec.workout(withEvents.copy(deleted = true))).contains("events"))
        // speeds are written with 2 decimals and inclines with 1, like the stats
        val rounded = text(RecordCodec.workout(workout.copy(events = listOf(WorkoutEvent(1, WorkoutEventType.ManualChange, WorkoutEventQuantity.Speed, 6.456), WorkoutEvent(2, WorkoutEventType.ManualChange, WorkoutEventQuantity.Incline, 3.25)))))
        assertTrue(rounded, rounded.contains("\"value\": 6.46}") && rounded.contains("\"value\": 3.3}"))
    }

    @Test fun `a fitness test document keeps its json and its tombstone has none`() {
        val result = FitnessTestJson.encode(ebbelingResult(TEST_1, T0))
        val doc = FitnessTestDocument(TEST_1, uuid, v, false, result)
        val file = text(RecordCodec.fitnessTest(doc))
        assertTrue(file, file.startsWith("{\n  \"format\": 1,\n  \"uuid\": \"$TEST_1\",\n  \"user_uuid\": \"$uuid\",\n  \"deleted\": false,\n  \"result\": {\n    \"schemaVersion\": 1,"))
        val parsed = RecordCodec.parseFitnessTest(file.toByteArray(), "ignored")!!
        assertEquals(TEST_1, parsed.uuid)
        assertEquals(uuid, parsed.userUuid)
        assertEquals(v, parsed.version)
        assertEquals(FitnessTestJson.decode(result), FitnessTestJson.decode(parsed.json))

        val tombstone = RecordCodec.parseFitnessTest(RecordCodec.fitnessTest(doc.copy(deleted = true, json = "")), uuid)!!
        assertTrue(tombstone.deleted)
        assertEquals("", tombstone.json)
        assertNull(RecordCodec.parseFitnessTest("{ nope".toByteArray(), uuid))
        // the folder user is used when the file does not name its owner
        val anonymous = file.replace("  \"user_uuid\": \"$uuid\",\n", "")
        assertEquals("folder-user", RecordCodec.parseFitnessTest(anonymous.toByteArray(), "folder-user")!!.userUuid)
    }

    @Test fun `fitness test files are told apart from the other files`() {
        assertTrue(RepoPaths.classify("users/ana-$uuid/fitness-tests/$TEST_1.json") is RepoPaths.Kind.FitnessTest)
        assertEquals("users/ana-$uuid/fitness-tests/$TEST_1.json", RepoPaths.fitnessTest("ana-$uuid", TEST_1))
        assertEquals(RepoPaths.Kind.Other, RepoPaths.classify("users/ana-$uuid/fitness-tests/$TEST_1.csv"))
        assertEquals(RepoPaths.Kind.Other, RepoPaths.classify("users/ana-$uuid/fitness-tests/nested/$TEST_1.json"))
    }

    @Test fun `folder names are readable ASCII with the uuid at the end`() {
        assertEquals("ana-maria", RepoPaths.slug("Ana María"))
        assertEquals("jose", RepoPaths.slug("  José  "))
        assertEquals("nandu", RepoPaths.slug("Ñandú"))
        assertEquals("o-brien", RepoPaths.slug("O'Brien"))
        assertEquals("user", RepoPaths.slug("李雷"))
        assertEquals("user", RepoPaths.slug(""))
        assertEquals(30, RepoPaths.slug("a".repeat(80)).length)
        assertEquals("ana-maria-$uuid", RepoPaths.userFolder("Ana María", uuid))
        assertEquals(uuid, RepoPaths.uuidOfFolder("ana-maria-$uuid"))
        assertEquals(uuid, RepoPaths.uuidOfFolder("user-$uuid"))
        assertNull(RepoPaths.uuidOfFolder("ana"))
        assertNull(RepoPaths.uuidOfFolder(uuid))
        assertEquals(
            "users/ana-$uuid/workouts/2026/10/20261008_024000",
            RepoPaths.workoutBase("ana-$uuid", T0, ZoneOffset.ofHours(2)),
        )
        assertTrue(RepoPaths.classify("users/ana-$uuid/workouts/2026/10/20261008_004000.json") is RepoPaths.Kind.WorkoutSummary)
        assertEquals(RepoPaths.Kind.Other, RepoPaths.classify("users/ana-$uuid/workouts/2026/10/20261008_004000.csv"))
        assertEquals(RepoPaths.Kind.Other, RepoPaths.classify("README.md"))
        assertEquals(RepoPaths.Kind.Other, RepoPaths.classify("users/ana-$uuid/notes.txt"))
    }
}
