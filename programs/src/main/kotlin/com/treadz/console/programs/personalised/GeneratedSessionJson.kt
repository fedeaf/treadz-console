package com.treadz.console.programs.personalised

import com.treadz.console.programs.engine.HeartRateBlockSummary
import com.treadz.console.programs.engine.HeartRateControlMode
import com.treadz.console.programs.fitness.HeartRateSourceKind
import com.treadz.console.programs.json.JsonObject
import com.treadz.console.programs.json.JsonPretty
import com.treadz.console.programs.json.ProgramFormatException
import com.treadz.console.programs.json.ProgramJson
import com.treadz.console.programs.json.parseDocument
import com.treadz.console.programs.model.BlockPhase
import com.treadz.console.programs.model.HeartRateAdjust
import java.time.Instant
import java.time.format.DateTimeParseException

/**
 * JSON document of a [GeneratedSessionRecord], stored next to the workout and synced. Same rules as the other
 * documents: mandatory `schemaVersion` (a newer one is rejected), units in the field names, ISO-8601 UTC times, lower
 * camel case enumerations, absent values left out, unknown fields ignored. The heart-rate summary's
 * `alpha1Reductions` (reductions by the real-time α1 brake; absent when the brake could not act in the block),
 * `alpha1Mean` and `alpha1Windows` (mean DFA-α1 of the valid windows evaluated in the block, and how many) were added
 * without a version change: records written before them read with these fields absent. So was `strapDeferrals` (regular
 * decisions deferred because the strap's readings were not confirmed by its RR; left out when 0).
 *
 * ```
 * {
 *   "schemaVersion": 1,
 *   "origin": {"generatorId": "couch-to-5k", "generatorVersion": 1, "inputs": {"session": "4", "week": "2", ...}},
 *   "programId": "5a0e0d2c-5b0f-4a56-9d6a-6f1f4b6d2c01",
 *   "startedAt": "2026-10-14T18:00:00Z", "endedAt": "2026-10-14T18:31:00Z",
 *   "outcome": "completed", "programSeconds": 1860, "maxHeartRateBpm": 180,
 *   "manualSpeedReductions": 0, "manualChanges": 0, "workBlocksPlanned": 6, "workBlocksCompleted": 6,
 *   "blocks": [
 *     {"blockIndex": 1, "key": "1.0", "phase": "work", "repetition": 1, "completed": true, "elapsedSeconds": 90, "plannedSeconds": 90,
 *      "plannedSpeedKmh": 7.0, "plannedInclinePercent": 1.0, "finalSpeedKmh": 7.0, "finalInclinePercent": 1.0,
 *      "manualSpeedReductions": 0, "manualChanges": 0, "endHeartRateBpm": 141.5,
 *      "heartRate": {"adjust": "speed", "mode": "betweenRepetitions", "zoneLowBpm": 108, "zoneHighBpm": 153, "startValue": 7.0, ...}},
 *     {"blockIndex": 2, ..., "heartRate": {"adjust": "incline", "mode": "continuous", ..., "alpha1Reductions": 1, "alpha1Mean": 0.82, "alpha1Windows": 40}},
 *     ...
 *   ]
 * }
 * ```
 */
object GeneratedSessionJson {

    private const val WIDTH = 170

    fun encode(record: GeneratedSessionRecord): String = JsonPretty.render(tree(record), WIDTH)

    /** Reads a record. Throws [ProgramFormatException] if the document is not well formed. */
    fun decode(text: String): GeneratedSessionRecord = from(JsonObject(parseDocument(text), ""))

    private fun tree(r: GeneratedSessionRecord): Map<String, Any?> = compact(
        linkedMapOf(
            "schemaVersion" to r.schemaVersion,
            "origin" to ProgramJson.originTree(r.origin),
            "programId" to r.programId,
            "startedAt" to iso(r.startedAtMillis),
            "endedAt" to iso(r.endedAtMillis),
            "outcome" to r.outcome,
            "programSeconds" to r.programSeconds,
            "maxHeartRateBpm" to r.maxHeartRateBpm,
            "manualSpeedReductions" to r.manualSpeedReductions,
            "manualChanges" to r.manualChanges,
            "workBlocksPlanned" to r.workBlocksPlanned,
            "workBlocksCompleted" to r.workBlocksCompleted,
            "blocks" to r.blocks.map { blockTree(it) },
        ),
    )

    private fun blockTree(b: SessionBlockRecord): Map<String, Any?> = compact(
        linkedMapOf(
            "blockIndex" to b.blockIndex,
            "key" to b.key,
            "phase" to b.phase,
            "repetition" to b.repetition,
            "completed" to b.completed,
            "elapsedSeconds" to b.elapsedSeconds,
            "plannedSeconds" to b.plannedSeconds,
            "plannedSpeedKmh" to b.plannedSpeedKmh,
            "plannedInclinePercent" to b.plannedInclinePercent,
            "finalSpeedKmh" to b.finalSpeedKmh,
            "finalInclinePercent" to b.finalInclinePercent,
            "manualSpeedReductions" to b.manualSpeedReductions,
            "manualChanges" to b.manualChanges,
            "endHeartRateBpm" to b.endHeartRateBpm,
            "heartRate" to b.heartRate?.let { summaryTree(it) },
        ),
    )

    private fun summaryTree(s: HeartRateBlockSummary): Map<String, Any?> = compact(
        linkedMapOf(
            "adjust" to s.adjust,
            "mode" to s.mode,
            "zoneLowBpm" to s.zoneLowBpm,
            "zoneHighBpm" to s.zoneHighBpm,
            "startValue" to s.startValue,
            "initialValue" to s.initialValue,
            "endValue" to s.endValue,
            "settledValue" to s.settledValue,
            "secondsInZone" to s.secondsInZone,
            "meanHeartRateBpm" to s.meanHeartRateBpm,
            "endHeartRateBpm" to s.endHeartRateBpm,
            "adjustments" to s.adjustments,
            "safetyReductions" to s.safetyReductions,
            "manualOverride" to s.manualOverride,
            "secondsWithoutHeartRate" to s.secondsWithoutHeartRate,
            "source" to s.source,
            "alpha1Reductions" to s.alpha1Reductions,
            "alpha1Mean" to s.alpha1Mean,
            "alpha1Windows" to s.alpha1Windows,
            "strapDeferrals" to s.strapDeferrals.takeIf { it > 0 },
        ),
    )

    private fun from(o: JsonObject): GeneratedSessionRecord {
        val version = o.int("schemaVersion")
        if (version > GeneratedSessionRecord.SCHEMA_VERSION) {
            throw ProgramFormatException(
                "schemaVersion",
                "version $version is newer than the supported ${GeneratedSessionRecord.SCHEMA_VERSION}: update the app to read this session record",
            )
        }
        return GeneratedSessionRecord(
            origin = ProgramJson.originFrom(o.obj("origin")),
            programId = o.string("programId"),
            startedAtMillis = time(o, "startedAt"),
            endedAtMillis = time(o, "endedAt"),
            outcome = o.enum("outcome", SessionOutcome.entries.toTypedArray()),
            programSeconds = o.int("programSeconds"),
            maxHeartRateBpm = o.optInt("maxHeartRateBpm"),
            manualSpeedReductions = o.int("manualSpeedReductions"),
            manualChanges = o.optInt("manualChanges") ?: 0,
            workBlocksPlanned = o.int("workBlocksPlanned"),
            workBlocksCompleted = o.int("workBlocksCompleted"),
            blocks = o.objects("blocks", optional = true).map { blockFrom(it) },
            schemaVersion = version,
        )
    }

    private fun blockFrom(b: JsonObject): SessionBlockRecord {
        val index = b.int("blockIndex")
        return SessionBlockRecord(
            blockIndex = index,
            key = b.string("key"),
            phase = b.enum("phase", BlockPhase.entries.toTypedArray()),
            repetition = b.optInt("repetition"),
            completed = b.boolean("completed", false),
            elapsedSeconds = b.int("elapsedSeconds"),
            plannedSeconds = b.int("plannedSeconds"),
            plannedSpeedKmh = b.double("plannedSpeedKmh"),
            plannedInclinePercent = b.double("plannedInclinePercent"),
            finalSpeedKmh = b.double("finalSpeedKmh"),
            finalInclinePercent = b.double("finalInclinePercent"),
            manualSpeedReductions = b.optInt("manualSpeedReductions") ?: 0,
            manualChanges = b.optInt("manualChanges") ?: 0,
            endHeartRateBpm = b.optDouble("endHeartRateBpm"),
            heartRate = b.optObj("heartRate")?.let { summaryFrom(it, index) },
        )
    }

    private fun summaryFrom(s: JsonObject, blockIndex: Int) = HeartRateBlockSummary(
        blockIndex = blockIndex,
        adjust = s.enum("adjust", HeartRateAdjust.entries.toTypedArray()),
        mode = s.enum("mode", HeartRateControlMode.entries.toTypedArray()),
        zoneLowBpm = s.int("zoneLowBpm"),
        zoneHighBpm = s.int("zoneHighBpm"),
        startValue = s.double("startValue"),
        initialValue = s.double("initialValue"),
        endValue = s.double("endValue"),
        settledValue = s.optDouble("settledValue"),
        secondsInZone = s.optInt("secondsInZone") ?: 0,
        meanHeartRateBpm = s.optDouble("meanHeartRateBpm"),
        endHeartRateBpm = s.optDouble("endHeartRateBpm"),
        adjustments = s.optInt("adjustments") ?: 0,
        safetyReductions = s.optInt("safetyReductions") ?: 0,
        manualOverride = s.boolean("manualOverride", false),
        secondsWithoutHeartRate = s.optInt("secondsWithoutHeartRate") ?: 0,
        source = s.optEnum("source", HeartRateSourceKind.entries.toTypedArray()),
        alpha1Reductions = s.optInt("alpha1Reductions"),
        alpha1Mean = s.optDouble("alpha1Mean"),
        alpha1Windows = s.optInt("alpha1Windows"),
        strapDeferrals = s.optInt("strapDeferrals") ?: 0,
    )

    private fun iso(millis: Long): String = Instant.ofEpochMilli(millis).toString()

    private fun time(o: JsonObject, key: String): Long {
        val text = o.string(key)
        return try {
            Instant.parse(text).toEpochMilli()
        } catch (e: DateTimeParseException) {
            throw ProgramFormatException(if (o.path.isEmpty()) key else "${o.path}.$key", "must be an ISO-8601 time (was '$text')")
        }
    }

    private fun compact(map: Map<String, Any?>): Map<String, Any?> = map.filterValues { it != null }
}
