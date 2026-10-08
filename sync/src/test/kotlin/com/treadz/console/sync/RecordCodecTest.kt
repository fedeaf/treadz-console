package com.treadz.console.sync

import com.treadz.console.sync.format.RecordCodec
import com.treadz.console.sync.format.RepoPaths
import com.treadz.console.userdata.FitnessLevel
import com.treadz.console.userdata.Goal
import com.treadz.console.userdata.Sex
import com.treadz.console.userdata.WorkoutEndReason
import com.treadz.console.userdata.WorkoutStats
import com.treadz.console.userdata.sync.ProgramDocument
import com.treadz.console.userdata.sync.RecordVersion
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
        uuid, v, false, "Ana María", Sex.Female, 168, LocalDate.of(1986, 10, 8), 55, 184,
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
              "resting_hr_bpm": 55,
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
        val bare = profile.copy(restingHeartRateBpm = null, maxHeartRateBpm = null, fitnessLevel = null, goal = null, targetWeightKg = null)
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
            uuid,date,weight_kg,deleted,updated_at,updated_by
            w-1,2026-10-01,61.5,false,2026-10-08T00:40:00.123Z,tablet
            w-3,,,true,2026-10-08T00:40:00.005Z,phone
            w-2,2026-10-05,61.0,false,2026-10-08T00:40:00.123Z,tablet
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

    private val workout = SyncWorkout(
        "99999999-8888-4777-8666-555555555555", uuid, "Ana", v, false, T0, T0 + 45 * 60_000, WorkoutEndReason.SafetyKeyRemoved,
        WorkoutStats(2700, 5200, 310, 7.0, 9.5, 141, 168, 2.5),
    )

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
