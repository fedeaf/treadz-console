package com.treadz.console.sync.format

import com.treadz.console.programs.fitness.FitnessTestJson
import com.treadz.console.programs.fitness.HeartRateSourceKind
import com.treadz.console.programs.personalised.GeneratedSessionJson
import com.treadz.console.programs.json.PlanJson
import com.treadz.console.userdata.FitnessLevel
import com.treadz.console.userdata.Goal
import com.treadz.console.userdata.HeartRatePeak
import com.treadz.console.userdata.RestingHeartRateSource
import com.treadz.console.userdata.RestingHeartRates
import com.treadz.console.userdata.Sex
import com.treadz.console.userdata.WorkoutEndReason
import com.treadz.console.userdata.WorkoutEvent
import com.treadz.console.userdata.WorkoutEventQuantity
import com.treadz.console.userdata.WorkoutEventsJson
import com.treadz.console.userdata.WorkoutStats
import com.treadz.console.userdata.sync.FitnessTestDocument
import com.treadz.console.userdata.sync.ProgramDocument
import com.treadz.console.userdata.sync.RecordVersion
import com.treadz.console.userdata.sync.SyncFitness
import com.treadz.console.userdata.sync.SyncProfile
import com.treadz.console.userdata.sync.SyncRestingHeartRate
import com.treadz.console.userdata.sync.SyncWeightEntry
import com.treadz.console.userdata.sync.SyncWorkout
import org.json.JSONArray
import org.json.JSONException
import org.json.JSONObject
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Converts records to the files of the repository and back (formats in [RepositoryReadme]). Everything is UTF-8,
 * times are ISO-8601 UTC with milliseconds, numbers use a dot. The parsers return `null` for a file they cannot
 * understand (the sync skips it) and ignore unknown fields, so newer versions can add some.
 */
internal object RecordCodec {
    const val FORMAT = 1

    private val TIME: DateTimeFormatter =
        DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'", Locale.ROOT).withZone(ZoneOffset.UTC)

    fun time(millis: Long): String = TIME.format(Instant.ofEpochMilli(millis))

    private fun parseTime(text: String): Long = Instant.parse(text).toEpochMilli()

    /** `LoseWeight` to `lose_weight`. */
    fun code(name: String): String = name.replace(Regex("([a-z0-9])([A-Z])"), "$1_$2").lowercase(Locale.ROOT)

    private inline fun <reified E : Enum<E>> enumOf(text: String?): E? =
        if (text == null) null else enumValues<E>().firstOrNull { code(it.name) == text }

    // ---- profile.json ----

    /**
     * Only what the user edits: `resting_hr_bpm` (the derived reference of `resting-heart-rate.csv`) and the `fitness`
     * block (now `fitness.json`) are no longer written, so the file changes exactly when the user edits the profile.
     */
    fun profile(p: SyncProfile): ByteArray {
        val json = JsonObjectWriter().put("format", FORMAT).put("uuid", p.uuid).put("deleted", p.deleted).put("name", p.name)
        if (!p.deleted) {
            json.put("sex", code(p.sex.name))
                .put("height_cm", p.heightCm)
                .put("birth_date", p.birthDate.toString())
                .put("max_hr_bpm", p.maxHeartRateBpm)
                .put("fitness_level", p.fitnessLevel?.let { code(it.name) })
                .put("goal", p.goal?.let { code(it.name) })
                .put("target_weight_kg", p.targetWeightKg)
                .put("created_at", time(p.createdAtMillis))
            // Left out when unknown, so that profile files written before the field existed stay byte-identical.
            p.maxHeartRateSetAtMillis?.let { json.put("max_hr_set_at", time(it)) }
            p.strapAddress?.let { json.put("strap_address", it) }
            p.strapName?.let { json.put("strap_name", it) }
        }
        json.put("updated_at", time(p.version.updatedAtMillis)).put("updated_by", p.version.deviceId)
        return json.toText().toByteArray(Charsets.UTF_8)
    }

    fun parseProfile(bytes: ByteArray): SyncProfile? = parse(bytes) { o ->
        val version = RecordVersion(parseTime(o.getString("updated_at")), o.getString("updated_by"))
        val deleted = o.optBoolean("deleted", false)
        if (deleted) {
            SyncProfile(o.getString("uuid"), version, true, o.optString("name"), Sex.Male, 0, LocalDate.EPOCH)
        } else {
            SyncProfile(
                uuid = o.getString("uuid"),
                version = version,
                deleted = false,
                name = o.getString("name"),
                sex = enumOf<Sex>(o.getString("sex")) ?: throw JSONException("unknown sex"),
                heightCm = o.getInt("height_cm"),
                birthDate = LocalDate.parse(o.getString("birth_date")),
                // Written by older versions only (the reference of the measurements): see SyncProfile.restingHeartRateBpm.
                restingHeartRateBpm = o.optIntOrNull("resting_hr_bpm"),
                maxHeartRateBpm = o.optIntOrNull("max_hr_bpm"),
                fitnessLevel = enumOf<FitnessLevel>(o.optStringOrNull("fitness_level")),
                goal = enumOf<Goal>(o.optStringOrNull("goal")),
                targetWeightKg = if (o.isNull("target_weight_kg")) null else o.optDouble("target_weight_kg"),
                createdAtMillis = o.optStringOrNull("created_at")?.let(::parseTime) ?: version.updatedAtMillis,
                maxHeartRateSetAtMillis = o.optStringOrNull("max_hr_set_at")?.let(::parseTime),
                strapAddress = o.optStringOrNull("strap_address"),
                strapName = o.optStringOrNull("strap_name"),
                // A "fitness" block (older versions) is ignored: the fitness profile is derived data, see fitness.json.
            )
        }
    }

    // ---- fitness.json ----

    /**
     * The derived fitness profile of a user: the snapshot `profile.json` used to carry (`fitness`, same content), the
     * [SyncFitness.fingerprint] of its inputs and the version of the rules, with the time and device of the last change of
     * the file (not of the data). Nothing in it is merged: see `SyncEngine`.
     */
    fun fitness(f: SyncFitness, version: RecordVersion): ByteArray {
        val json = JsonObjectWriter().put("format", FORMAT).put("user_uuid", f.userUuid)
            .put("rules_version", f.rulesVersion).put("fingerprint", f.fingerprint)
            .putRaw("fitness", FitnessTestJson.encodeProfile(f.profile))
            .put("updated_at", time(version.updatedAtMillis)).put("updated_by", version.deviceId)
        return json.toText().toByteArray(Charsets.UTF_8)
    }

    /** What the engine needs of a `fitness.json` found in the repository: the block itself is never read back. */
    class RemoteFitness(val fingerprint: String, val rulesVersion: Int)

    /** `null` for a file that cannot be understood; a file without `rules_version` has version 0. */
    fun parseFitness(bytes: ByteArray): RemoteFitness? = parse(bytes) { o ->
        RemoteFitness(o.getString("fingerprint"), o.optInt("rules_version", 0))
    }

    // ---- weight.csv ----

    private const val WEIGHT_HEADER = "uuid,date,weight_kg,deleted,updated_at,updated_by,created_at"

    /**
     * The whole weight log of a user, tombstones included (they keep their uuid and version but no date or weight),
     * ordered like the app orders them: by date, creation time and uuid.
     */
    fun weightCsv(entries: List<SyncWeightEntry>): ByteArray {
        val out = StringBuilder(WEIGHT_HEADER).append('\n')
        for (e in entries.sortedWith(compareBy({ it.date }, { it.createdAtMillis }, { it.uuid }))) {
            val fields = listOf(
                e.uuid,
                if (e.deleted) "" else e.date.toString(),
                if (e.deleted) "" else decimal(e.weightKg),
                e.deleted.toString(),
                time(e.version.updatedAtMillis),
                e.version.deviceId,
                time(e.createdAtMillis),
            )
            out.append(fields.joinToString(",") { csv(it) }).append('\n')
        }
        return out.toString().toByteArray(Charsets.UTF_8)
    }

    /** Decimals written for speeds (km/h) and inclines (%) in the workout summary; the belt works in steps of 0.1. */
    private const val SPEED_DECIMALS = 2
    private const val INCLINE_DECIMALS = 1

    /**
     * [value] rounded half up to [decimals] places. It is computed on the decimal representation of the number, so
     * the result is exact, does not depend on the locale and is the same on every device.
     */
    internal fun rounded(value: Double, decimals: Int): Double =
        if (value.isFinite()) java.math.BigDecimal.valueOf(value).setScale(decimals, java.math.RoundingMode.HALF_UP).toDouble() else value

    fun decimal(value: Double): String = java.math.BigDecimal.valueOf(value).stripTrailingZeros().toPlainString().let { if ('.' in it) it else "$it.0" }

    /**
     * Rows that cannot be understood are skipped. Files written before the `created_at` column existed (or rows with
     * an empty or unreadable value) get the row's `updated_at` as creation time, which is also what the app's
     * database migration assigns to entries that already exist.
     */
    fun parseWeightCsv(bytes: ByteArray, userUuid: String): List<SyncWeightEntry> {
        val rows = CsvRows.parse(String(bytes, Charsets.UTF_8))
        if (rows.isEmpty()) return emptyList()
        val header = rows.first().withIndex().associate { (i, n) -> n.trim() to i }
        fun col(row: List<String>, name: String) = header[name]?.let { row.getOrNull(it) }
        return rows.drop(1).mapNotNull { row ->
            runCatching {
                val deleted = col(row, "deleted") == "true"
                val version = RecordVersion(parseTime(col(row, "updated_at")!!), col(row, "updated_by")!!)
                SyncWeightEntry(
                    uuid = col(row, "uuid")!!.takeIf { it.isNotEmpty() }!!,
                    userUuid = userUuid,
                    version = version,
                    deleted = deleted,
                    date = if (deleted) LocalDate.EPOCH else LocalDate.parse(col(row, "date")!!),
                    weightKg = if (deleted) 0.0 else col(row, "weight_kg")!!.toDouble(),
                    createdAtMillis = col(row, "created_at")?.takeIf { it.isNotBlank() }?.let { runCatching { parseTime(it.trim()) }.getOrNull() }
                        ?: version.updatedAtMillis,
                )
            }.getOrNull()
        }
    }

    // ---- workout json ----

    fun workout(w: SyncWorkout): ByteArray {
        val json = JsonObjectWriter().put("format", FORMAT).put("uuid", w.uuid).put("user_uuid", w.userUuid).put("deleted", w.deleted)
            .put("started_at", time(w.startedAtMillis))
        if (!w.deleted) {
            json.put("ended_at", time(w.endedAtMillis))
                .put("end_reason", code(w.endReason.name))
            val stats = JsonObjectWriter()
                .put("duration_s", w.stats.durationSeconds)
                .put("distance_m", w.stats.distanceMeters)
                .put("calories", w.stats.calories)
                .put("avg_speed_kmh", rounded(w.stats.averageSpeedKmh, SPEED_DECIMALS))
                .put("max_speed_kmh", rounded(w.stats.maxSpeedKmh, SPEED_DECIMALS))
                .put("avg_hr_bpm", w.stats.averageHeartRateBpm)
                .put("max_hr_bpm", w.stats.maxHeartRateBpm)
                .put("max_incline_pct", rounded(w.stats.maxInclinePercent, INCLINE_DECIMALS))
            // Left out when there is no peak, so that files of workouts without one stay as they were.
            w.stats.peakHeartRate?.let { peak ->
                stats.put("peak_hr_bpm", peak.bpm)
                peak.source?.let { stats.put("peak_hr_source", code(it.name)) }
                stats.put("peak_hr_at", time(peak.atMillis))
            }
            json.put("stats", stats)
            val plan = w.planJson
            val record = w.sessionRecordJson
            if (plan != null) {
                // The ids are repeated outside the plan so that a reader sees what the workout was without parsing it.
                val source = runCatching { PlanJson.decode(plan).source }.getOrNull()
                json.put("program_id", source?.programId).put("program_name", source?.programName)
                (source?.origin?.generatorId ?: recordGeneratorId(record))?.let { json.put("generator_id", it) }
                json.put("fitness_test_id", w.fitnessTestId).putRaw("plan", plan)
            } else {
                recordGeneratorId(record)?.let { json.put("generator_id", it) }
                if (w.fitnessTestId != null) json.put("fitness_test_id", w.fitnessTestId)
            }
            if (record != null) json.putRaw("session_record", record)
            if (w.events.isNotEmpty()) json.putRaw("events", eventsText(w.events))
        }
        json.put("updated_at", time(w.version.updatedAtMillis)).put("updated_by", w.version.deviceId)
        return json.toText().toByteArray(Charsets.UTF_8)
    }

    /** [folderUserUuid] is used when the file does not name its owner. */
    fun parseWorkout(bytes: ByteArray, folderUserUuid: String): SyncWorkout? = parse(bytes) { o ->
        val version = RecordVersion(parseTime(o.getString("updated_at")), o.getString("updated_by"))
        val started = parseTime(o.getString("started_at"))
        val owner = o.optStringOrNull("user_uuid") ?: folderUserUuid
        if (o.optBoolean("deleted", false)) {
            SyncWorkout(o.getString("uuid"), owner, "", version, true, started, started, WorkoutEndReason.Stopped, WorkoutStats())
        } else {
            val s = o.getJSONObject("stats")
            SyncWorkout(
                uuid = o.getString("uuid"),
                userUuid = owner,
                ownerName = "",
                version = version,
                deleted = false,
                startedAtMillis = started,
                endedAtMillis = parseTime(o.getString("ended_at")),
                endReason = enumOf<WorkoutEndReason>(o.getString("end_reason")) ?: WorkoutEndReason.Interrupted,
                stats = WorkoutStats(
                    durationSeconds = s.getInt("duration_s"),
                    distanceMeters = s.getInt("distance_m"),
                    calories = s.getInt("calories"),
                    averageSpeedKmh = s.getDouble("avg_speed_kmh"),
                    maxSpeedKmh = s.getDouble("max_speed_kmh"),
                    averageHeartRateBpm = s.optIntOrNull("avg_hr_bpm"),
                    maxHeartRateBpm = s.optIntOrNull("max_hr_bpm"),
                    maxInclinePercent = s.getDouble("max_incline_pct"),
                    peakHeartRate = s.optIntOrNull("peak_hr_bpm")?.let { bpm ->
                        HeartRatePeak(
                            bpm = bpm,
                            source = enumOf<HeartRateSourceKind>(s.optStringOrNull("peak_hr_source")),
                            atMillis = s.optStringOrNull("peak_hr_at")?.let(::parseTime) ?: 0,
                        )
                    },
                ),
                planJson = o.optJSONObject("plan")?.toString(),
                fitnessTestId = o.optStringOrNull("fitness_test_id"),
                sessionRecordJson = o.optJSONObject("session_record")?.toString(),
                events = parseEvents(o.optJSONArray("events")),
            )
        }
    }

    /**
     * The `events` array, one event per line: `{"seconds": 305, "type": "heart_rate_decrease", "quantity": "speed", "value": 5.4}`.
     * `quantity` and `value` are left out when the event changes nothing. Speeds are written with 2 decimals and inclines
     * with 1, like the stats.
     */
    private fun eventsText(events: List<WorkoutEvent>): String = events.joinToString(",\n", prefix = "[\n", postfix = "\n]") { e ->
        val quantity = e.quantity
        val value = e.value
        val line = StringBuilder("  {\"seconds\": ${e.seconds}, \"type\": \"${e.type.code}\"")
        if (quantity != null && value != null && value.isFinite()) {
            val decimals = if (quantity == WorkoutEventQuantity.Speed) SPEED_DECIMALS else INCLINE_DECIMALS
            line.append(", \"quantity\": \"${quantity.code}\", \"value\": ${decimal(rounded(value, decimals))}")
        }
        line.append('}').toString()
    }

    /** Events of a type this version does not know (written by a newer app) are left out. */
    private fun parseEvents(array: JSONArray?): List<WorkoutEvent> = array?.let { WorkoutEventsJson.decode(it.toString()) }.orEmpty()

    /** The generator named by a session record document, `null` if there is none or it cannot be read (the file keeps the record as it is). */
    private fun recordGeneratorId(record: String?): String? =
        if (record == null) null else runCatching { GeneratedSessionJson.decode(record).generatorId }.getOrNull()

    // ---- resting-heart-rate.csv ----

    private const val RESTING_HEADER = "uuid,measured_at,bpm,source,lowest_mean_bpm,coverage,rmssd_ms,deleted,updated_at,updated_by"

    /**
     * The whole resting heart rate history of a user, tombstones included (they keep their uuid and version but no
     * values). Rows are ordered by measurement time and uuid; the tombstones come first, by uuid, so that every device
     * writes the same file for the same records.
     */
    fun restingHeartRateCsv(entries: List<SyncRestingHeartRate>): ByteArray {
        val out = StringBuilder(RESTING_HEADER).append('\n')
        for (e in entries.sortedWith(compareBy({ if (it.deleted) Long.MIN_VALUE else it.measuredAtMillis }, { it.uuid }))) {
            val fields = listOf(
                e.uuid,
                if (e.deleted) "" else time(e.measuredAtMillis),
                if (e.deleted) "" else e.bpm.toString(),
                if (e.deleted) "" else code(e.source.name),
                if (e.deleted) "" else e.lowestMeanBpm?.let(::decimal).orEmpty(),
                if (e.deleted) "" else e.coverage?.let(::decimal).orEmpty(),
                if (e.deleted) "" else e.rmssdMs?.let(::decimal).orEmpty(),
                e.deleted.toString(),
                time(e.version.updatedAtMillis),
                e.version.deviceId,
            )
            out.append(fields.joinToString(",") { csv(it) }).append('\n')
        }
        return out.toString().toByteArray(Charsets.UTF_8)
    }

    /** Rows that cannot be understood are skipped. */
    fun parseRestingHeartRateCsv(bytes: ByteArray, userUuid: String): List<SyncRestingHeartRate> {
        val rows = CsvRows.parse(String(bytes, Charsets.UTF_8))
        if (rows.isEmpty()) return emptyList()
        val header = rows.first().withIndex().associate { (i, n) -> n.trim() to i }
        fun col(row: List<String>, name: String) = header[name]?.let { row.getOrNull(it) }?.takeIf { it.isNotBlank() }
        return rows.drop(1).mapNotNull { row ->
            runCatching {
                val deleted = col(row, "deleted") == "true"
                SyncRestingHeartRate(
                    uuid = col(row, "uuid")!!,
                    userUuid = userUuid,
                    version = RecordVersion(parseTime(col(row, "updated_at")!!), col(row, "updated_by")!!),
                    deleted = deleted,
                    measuredAtMillis = if (deleted) 0 else parseTime(col(row, "measured_at")!!.trim()),
                    bpm = if (deleted) 0 else col(row, "bpm")!!.trim().toInt(),
                    source = enumOf<RestingHeartRateSource>(col(row, "source")?.trim()) ?: RestingHeartRateSource.Manual,
                    lowestMeanBpm = if (deleted) null else col(row, "lowest_mean_bpm")?.trim()?.toDouble(),
                    coverage = if (deleted) null else col(row, "coverage")?.trim()?.toDouble(),
                    rmssdMs = if (deleted) null else col(row, "rmssd_ms")?.trim()?.toDouble(),
                )
            }.getOrNull()
        }
    }

    /**
     * The manual measurement that stands for the resting heart rate of a profile file written before the history existed
     * (it carries `resting_hr_bpm` and the repository has no `resting-heart-rate.csv` for the user). The uuid depends only on
     * the user and the version is the profile's, so every device derives the same record.
     */
    fun legacyResting(profile: SyncProfile): SyncRestingHeartRate? {
        val bpm = profile.restingHeartRateBpm ?: return null
        if (profile.deleted) return null
        return SyncRestingHeartRate(
            uuid = RestingHeartRates.legacyMeasurementUuid(profile.uuid),
            userUuid = profile.uuid,
            version = profile.version,
            deleted = false,
            measuredAtMillis = profile.version.updatedAtMillis,
            bpm = bpm,
            source = RestingHeartRateSource.Manual,
        )
    }

    // ---- programs ----

    fun program(p: ProgramDocument): ByteArray {
        val json = JsonObjectWriter().put("format", FORMAT).put("uuid", p.uuid).put("user_uuid", p.userUuid).put("deleted", p.deleted)
        if (!p.deleted) json.putRaw("program", p.json)
        json.put("updated_at", time(p.version.updatedAtMillis)).put("updated_by", p.version.deviceId)
        return json.toText().toByteArray(Charsets.UTF_8)
    }

    fun parseProgram(bytes: ByteArray, folderUserUuid: String): ProgramDocument? = parse(bytes) { o ->
        val deleted = o.optBoolean("deleted", false)
        ProgramDocument(
            uuid = o.getString("uuid"),
            userUuid = o.optStringOrNull("user_uuid") ?: folderUserUuid,
            version = RecordVersion(parseTime(o.getString("updated_at")), o.getString("updated_by")),
            deleted = deleted,
            json = if (deleted) "" else o.getJSONObject("program").toString(),
        )
    }

    // ---- fitness tests ----

    fun fitnessTest(t: FitnessTestDocument): ByteArray {
        val json = JsonObjectWriter().put("format", FORMAT).put("uuid", t.uuid).put("user_uuid", t.userUuid).put("deleted", t.deleted)
        if (!t.deleted) json.putRaw("result", t.json)
        json.put("updated_at", time(t.version.updatedAtMillis)).put("updated_by", t.version.deviceId)
        return json.toText().toByteArray(Charsets.UTF_8)
    }

    fun parseFitnessTest(bytes: ByteArray, folderUserUuid: String): FitnessTestDocument? = parse(bytes) { o ->
        val deleted = o.optBoolean("deleted", false)
        FitnessTestDocument(
            uuid = o.getString("uuid"),
            userUuid = o.optStringOrNull("user_uuid") ?: folderUserUuid,
            version = RecordVersion(parseTime(o.getString("updated_at")), o.getString("updated_by")),
            deleted = deleted,
            json = if (deleted) "" else o.getJSONObject("result").toString(),
        )
    }

    // ---- helpers ----

    private inline fun <T> parse(bytes: ByteArray, block: (JSONObject) -> T): T? = try {
        block(JSONObject(String(bytes, Charsets.UTF_8)))
    } catch (e: JSONException) {
        null
    } catch (e: java.time.DateTimeException) {
        null
    }

    private fun JSONObject.optStringOrNull(name: String): String? = if (isNull(name)) null else optString(name).takeIf { it.isNotEmpty() }

    private fun JSONObject.optIntOrNull(name: String): Int? = if (isNull(name)) null else getInt(name)

    private fun csv(field: String): String =
        if (field.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"" + field.replace("\"", "\"\"") + "\"" else field
}

/** Minimal RFC 4180 reader. */
internal object CsvRows {
    fun parse(text: String): List<List<String>> {
        val rows = ArrayList<List<String>>()
        var row = ArrayList<String>()
        val field = StringBuilder()
        var quoted = false
        var i = 0
        fun endField() { row.add(field.toString()); field.setLength(0) }
        fun endRow() {
            endField()
            if (!(row.size == 1 && row[0].isEmpty())) rows.add(row)
            row = ArrayList()
        }
        while (i < text.length) {
            val c = text[i]
            when {
                quoted && c == '"' && text.getOrNull(i + 1) == '"' -> { field.append('"'); i++ }
                c == '"' -> quoted = !quoted
                !quoted && c == ',' -> endField()
                !quoted && c == '\n' -> endRow()
                !quoted && c == '\r' -> Unit
                else -> field.append(c)
            }
            i++
        }
        if (field.isNotEmpty() || row.isNotEmpty()) endRow()
        return rows
    }
}
