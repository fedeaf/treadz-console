package com.treadz.console.sync

import com.treadz.console.programs.fitness.HeartRateSourceKind
import com.treadz.console.programs.personalised.GeneratedSessionJson
import com.treadz.console.sync.engine.CommitMessages
import com.treadz.console.sync.format.RecordCodec
import com.treadz.console.sync.format.RepoPaths
import com.treadz.console.sync.format.RepositoryReadme
import com.treadz.console.userdata.HeartRatePeak
import com.treadz.console.userdata.RestingHeartRateSource
import com.treadz.console.userdata.Sex
import com.treadz.console.userdata.WorkoutEndReason
import com.treadz.console.userdata.WorkoutStats
import com.treadz.console.userdata.sync.PendingChanges
import com.treadz.console.userdata.sync.RecordVersion
import com.treadz.console.userdata.sync.SyncProfile
import com.treadz.console.userdata.sync.SyncRestingHeartRate
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

/** The files of the heart rate data: strap in the profile, the resting heart rate history and the workout document. */
@RunWith(RobolectricTestRunner::class)
class HeartRateRecordCodecTest {

    private val v = RecordVersion(T0 + 123, "tablet")
    private val uuid = "11111111-2222-4333-8444-555555555555"
    private fun text(bytes: ByteArray) = String(bytes, Charsets.UTF_8)

    private val profile = SyncProfile(uuid, v, false, "Ana", Sex.Female, 168, LocalDate.of(1986, 10, 8), createdAtMillis = T0 - 1000)

    // ---- strap and resting heart rate in the profile ----

    @Test fun `the strap is written after the creation time and read back, and left out when there is none`() {
        val paired = profile.copy(restingHeartRateBpm = 57, strapAddress = "AA:BB:CC:DD:EE:FF", strapName = "Polar H10 1234AB")
        val json = text(RecordCodec.profile(paired))
        assertTrue(
            json,
            json.contains(
                "  \"resting_hr_bpm\": 57,\n" +
                    "  \"max_hr_bpm\": null,",
            ),
        )
        assertTrue(
            json,
            json.contains("  \"created_at\": \"2026-10-08T00:39:59.000Z\",\n  \"strap_address\": \"AA:BB:CC:DD:EE:FF\",\n  \"strap_name\": \"Polar H10 1234AB\",\n  \"updated_at\""),
        )
        assertEquals(paired, RecordCodec.parseProfile(json.toByteArray()))
        // address only
        val unnamed = paired.copy(strapName = null)
        assertFalse(text(RecordCodec.profile(unnamed)).contains("strap_name"))
        assertEquals(unnamed, RecordCodec.parseProfile(RecordCodec.profile(unnamed)))
        // a profile file written before straps existed is read as "no strap" and is written back byte for byte
        val old = text(RecordCodec.profile(profile))
        assertFalse(old.contains("strap"))
        assertNull(RecordCodec.parseProfile(old.toByteArray())!!.strapAddress)
        // a tombstone keeps neither
        assertFalse(text(RecordCodec.profile(paired.copy(deleted = true))).contains("strap"))
    }

    // ---- resting-heart-rate.csv ----

    private fun measurement(id: String, at: Long, bpm: Int, source: RestingHeartRateSource = RestingHeartRateSource.Strap, deleted: Boolean = false,
                            version: RecordVersion = v, lowest: Double? = null, coverage: Double? = null, rmssd: Double? = null) =
        SyncRestingHeartRate(id, uuid, version, deleted, at, bpm, source, lowest, coverage, rmssd)

    @Test fun `the resting heart rate csv has a fixed layout, tombstones first without values, and round trips`() {
        val entries = listOf(
            measurement("m-2", T0 + 2_000, 57, RestingHeartRateSource.Console),
            measurement("m-1", T0 + 1_000, 55, RestingHeartRateSource.Strap, lowest = 54.6, coverage = 0.97, rmssd = 42.5),
            measurement("m-3", T0 + 500, 61, RestingHeartRateSource.Manual),
            measurement("m-0", T0, 70, deleted = true, version = RecordVersion(T0 + 5, "phone")),
        )
        val csv = text(RecordCodec.restingHeartRateCsv(entries))
        assertEquals(
            """
            uuid,measured_at,bpm,source,lowest_mean_bpm,coverage,rmssd_ms,deleted,updated_at,updated_by
            m-0,,,,,,,true,2026-10-08T00:40:00.005Z,phone
            m-3,2026-10-08T00:40:00.500Z,61,manual,,,,false,2026-10-08T00:40:00.123Z,tablet
            m-1,2026-10-08T00:40:01.000Z,55,strap,54.6,0.97,42.5,false,2026-10-08T00:40:00.123Z,tablet
            m-2,2026-10-08T00:40:02.000Z,57,console,,,,false,2026-10-08T00:40:00.123Z,tablet
            """.trimIndent() + "\n",
            csv,
        )
        // the same file whatever the order the records come in
        assertEquals(csv, text(RecordCodec.restingHeartRateCsv(entries.reversed())))
        val parsed = RecordCodec.parseRestingHeartRateCsv(csv.toByteArray(), uuid)
        assertEquals(listOf("m-0", "m-3", "m-1", "m-2"), parsed.map { it.uuid })
        assertTrue(parsed[0].deleted)
        assertEquals(entries[1], parsed[2])
        assertEquals(entries[3].copy(measuredAtMillis = 0, bpm = 0, source = RestingHeartRateSource.Manual), parsed[0])
        assertEquals(uuid, parsed[2].userUuid)
        // damaged rows are skipped, unknown sources read as manual, extra columns are ignored
        val damaged = csv + "m-9,not a time,60,strap,,,,false,2026-10-08T00:40:00.123Z,x\nm-8,2026-10-08T00:40:01.000Z,sixty,strap,,,,false,2026-10-08T00:40:00.123Z,x\n,,,,,,,,,\n"
        assertEquals(4, RecordCodec.parseRestingHeartRateCsv(damaged.toByteArray(), uuid).size)
        val future = "uuid,measured_at,bpm,source,future,deleted,updated_at,updated_by\nf-1,2026-10-08T00:40:01.000Z,60,watch,x,false,2026-10-08T00:40:00.123Z,tablet\n"
        val read = RecordCodec.parseRestingHeartRateCsv(future.toByteArray(), uuid).single()
        assertEquals(RestingHeartRateSource.Manual, read.source)
        assertNull(read.rmssdMs)
        assertTrue(RecordCodec.parseRestingHeartRateCsv(ByteArray(0), uuid).isEmpty())
    }

    @Test fun `the resting heart rate of a profile file without the history becomes one manual measurement, the same on every device`() {
        val old = profile.copy(restingHeartRateBpm = 58)
        val m = RecordCodec.legacyResting(old)!!
        assertEquals("resting-$uuid", m.uuid)
        assertEquals(uuid, m.userUuid)
        assertEquals(58, m.bpm)
        assertEquals(RestingHeartRateSource.Manual, m.source)
        assertEquals(old.version, m.version)
        assertEquals(old.version.updatedAtMillis, m.measuredAtMillis)
        assertEquals(m, RecordCodec.legacyResting(old))
        assertNull(RecordCodec.legacyResting(profile))
        assertNull(RecordCodec.legacyResting(old.copy(deleted = true)))
    }

    @Test fun `the file is found by its path`() {
        assertEquals("users/ana-$uuid/resting-heart-rate.csv", RepoPaths.restingHeartRates("ana-$uuid"))
        assertEquals(RepoPaths.Kind.RestingHeartRates("ana-$uuid"), RepoPaths.classify("users/ana-$uuid/resting-heart-rate.csv"))
        assertEquals(RepoPaths.Kind.Other, RepoPaths.classify("users/ana-$uuid/other/resting-heart-rate.csv"))
    }

    // ---- workout json ----

    private val workout = SyncWorkout(
        "99999999-8888-4777-8666-555555555555", uuid, "Ana", v, false, T0, T0 + 45 * 60_000, WorkoutEndReason.Stopped,
        WorkoutStats(2700, 5200, 310, 7.0, 9.5, 141, 168, 2.5),
    )

    @Test fun `the peak is part of the stats when there is one and absent otherwise`() {
        val withPeak = workout.copy(stats = workout.stats.copy(peakHeartRate = HeartRatePeak(171, HeartRateSourceKind.DirectStrap, T0 + 40 * 60_000)))
        val json = text(RecordCodec.workout(withPeak))
        assertTrue(
            json,
            json.contains(
                "    \"max_incline_pct\": 2.5,\n    \"peak_hr_bpm\": 171,\n    \"peak_hr_source\": \"direct_strap\",\n    \"peak_hr_at\": \"2026-10-08T01:20:00.000Z\"\n  }",
            ),
        )
        assertEquals(withPeak.copy(ownerName = ""), RecordCodec.parseWorkout(json.toByteArray(), uuid))
        // a peak without a known source
        val unknownSource = workout.copy(stats = workout.stats.copy(peakHeartRate = HeartRatePeak(171, null, T0)))
        assertFalse(text(RecordCodec.workout(unknownSource)).contains("peak_hr_source"))
        assertEquals(unknownSource.copy(ownerName = ""), RecordCodec.parseWorkout(RecordCodec.workout(unknownSource), uuid))
        // workouts without a peak are written exactly as before and read as having none
        val plain = text(RecordCodec.workout(workout))
        assertFalse(plain.contains("peak_hr"))
        assertNull(RecordCodec.parseWorkout(plain.toByteArray(), uuid)!!.stats.peakHeartRate)
    }

    @Test fun `the session record and its generator are in the workout document`() {
        val record = sessionRecord(GENERATOR_C25K, session = 7)
        val recordJson = GeneratedSessionJson.encode(record)
        val plan = com.treadz.console.programs.json.PlanJson.encode(planOf(personalised(generatorId = GENERATOR_C25K)))
        val withRecord = workout.copy(planJson = plan, sessionRecordJson = recordJson)
        val json = text(RecordCodec.workout(withRecord))
        assertTrue(json, json.contains("\"generator_id\": \"$GENERATOR_C25K\""))
        assertTrue(json, json.contains("\"session_record\": {\n    \"schemaVersion\": 1,"))
        val parsed = RecordCodec.parseWorkout(json.toByteArray(), uuid)!!
        assertEquals(record, GeneratedSessionJson.decode(parsed.sessionRecordJson!!))
        assertEquals(planOf(personalised(generatorId = GENERATOR_C25K)), com.treadz.console.programs.json.PlanJson.decode(parsed.planJson!!))
        // a record without a plan names its generator too
        val recordOnly = text(RecordCodec.workout(workout.copy(sessionRecordJson = recordJson)))
        assertTrue(recordOnly, recordOnly.contains("\"generator_id\": \"$GENERATOR_C25K\""))
        assertFalse(recordOnly.contains("\"plan\""))
        // a record that this app cannot read is passed on as it is
        val newer = GeneratedSessionJson.encode(record.copy(schemaVersion = 99))
        val kept = RecordCodec.parseWorkout(RecordCodec.workout(workout.copy(sessionRecordJson = newer)), uuid)!!
        assertTrue(kept.sessionRecordJson!!, kept.sessionRecordJson!!.contains("\"schemaVersion\":99")) // (re-read as compact JSON)
        assertNull(RecordCodec.parseWorkout(RecordCodec.workout(workout), uuid)!!.sessionRecordJson)
        assertFalse(text(RecordCodec.workout(workout)).contains("session_record"))
        assertFalse(text(RecordCodec.workout(workout)).contains("generator_id"))
    }

    // ---- commit messages and README ----

    private val names = mapOf(uuid to "Ana")

    @Test fun `commit messages name the resting heart rate measurements`() {
        val none = emptyList<Nothing>()
        fun message(vararg m: SyncRestingHeartRate) =
            CommitMessages.build(PendingChanges(none, none, none, m.toList()), none, none, names, ZoneOffset.UTC)
        assertEquals("Ana · resting heart rate 2026-10-08 · 55 bpm", message(measurement("m", T0, 55)))
        assertEquals("Ana · resting heart rate deleted", message(measurement("m", T0, 55, deleted = true)))
        assertEquals("Sync · 2 resting heart rates · Ana", message(measurement("a", T0, 55), measurement("b", T0, 56)))
    }

    @Test fun `the README describes the heart rate files and fields`() {
        val readme = RepositoryReadme.TEXT
        for (
            needle in listOf(
                "resting-heart-rate.csv", "uuid,measured_at,bpm,source,lowest_mean_bpm,coverage,rmssd_ms,deleted,updated_at,updated_by",
                "`manual`", "`strap`", "`console`", "reference", "median of the measurements of the last 30 days", "7 bpm",
                "strap_address", "strap_name", "hr_source", "direct_strap", "treadmill_console", "rr_ms", "peak_hr_bpm", "peak_hr_source",
                "peak_hr_at", "generator_id", "session_record", "origin", "maxHeartRateWalk", "resting heart rate measurement** stays",
            )
        ) {
            assertTrue("README lacks '$needle'", readme.contains(needle))
        }
        assertTrue(readme.contains("users/<name>-<uuid>/profile.json\n                    weight.csv\n                    resting-heart-rate.csv"))
    }
}
