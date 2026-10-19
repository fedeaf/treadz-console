package com.treadz.console.programs.json

import com.treadz.console.programs.model.BlockEnd
import com.treadz.console.programs.model.BlockPhase
import com.treadz.console.programs.model.HeartRateAdjust
import com.treadz.console.programs.model.ProgramKind
import com.treadz.console.programs.plan.HeartRateGuidance
import com.treadz.console.programs.plan.PlanSource
import com.treadz.console.programs.plan.PlanWarning
import com.treadz.console.programs.plan.Repetition
import com.treadz.console.programs.plan.ResolvedBlock
import com.treadz.console.programs.plan.ResolvedPlan
import com.treadz.console.programs.plan.StartParameters
import com.treadz.console.programs.plan.WarningCode

/**
 * JSON document for a [ResolvedPlan]: the copy of what was planned that the workout history keeps. It follows the
 * conventions of [ProgramJson] (units in the field names, lower camel case enumerations, unknown fields ignored on
 * reading, mandatory `planSchemaVersion`). A block that keeps a speed or an incline (see
 * [com.treadz.console.programs.model.BlockTarget.Absolute]) carries `"keepsSpeed": true` / `"keepsIncline": true` next
 * to the expected `speedKmh` / `inclinePercent`; only a plan with such a block is written with `planSchemaVersion` 2,
 * every other keeps version 1. Heart-rate bounds of a block use `minSpeedKmh`/`maxSpeedKmh` or
 * `minInclinePercent`/`maxInclinePercent`; they are always absolute.
 */
object PlanJson {

    fun encode(plan: ResolvedPlan): String = JsonPretty.render(toTree(plan))

    fun decode(text: String): ResolvedPlan = fromTree(JsonObject(parseDocument(text), ""))

    // ------------------------------------------------------------------ writing

    private fun toTree(p: ResolvedPlan): Map<String, Any?> = linkedMapOf(
        "planSchemaVersion" to ResolvedPlan.planSchemaVersionFor(p.blocks),
        "source" to sourceTree(p.source),
        "parameters" to parametersTree(p.parameters),
        "estimatedDurationSeconds" to p.estimatedDurationSeconds,
        "estimatedDistanceMeters" to p.estimatedDistanceMeters,
        "blocks" to p.blocks.map { blockTree(it) },
        "warnings" to p.warnings.map { warningTree(it) },
    )

    private fun sourceTree(s: PlanSource): Map<String, Any?> = linkedMapOf(
        "programId" to s.programId,
        "programName" to s.programName,
        "kind" to s.kind,
        "programSchemaVersion" to s.programSchemaVersion,
        "origin" to s.origin?.let { ProgramJson.originTree(it) },
    ).filterValues { it != null }

    private fun parametersTree(p: StartParameters): Map<String, Any?> = linkedMapOf(
        "totalDurationSeconds" to p.totalDurationSeconds,
        "easySpeedKmh" to p.easySpeedKmh,
        "maxSpeedKmh" to p.maxSpeedKmh,
        "maxInclinePercent" to p.maxInclinePercent,
        "intensityAdjustmentPercent" to p.intensityAdjustmentPercent,
    ).filterValues { it != null }

    private fun blockTree(b: ResolvedBlock): Map<String, Any?> {
        val m = linkedMapOf<String, Any?>("index" to b.index, "phase" to b.phase)
        if (b.name != null) m["name"] = b.name
        when (val end = b.end) {
            is BlockEnd.Duration -> m["durationSeconds"] = end.seconds
            is BlockEnd.Distance -> m["distanceMeters"] = end.meters
        }
        m["speedKmh"] = b.speedKmh
        m["inclinePercent"] = b.inclinePercent
        // For a kept value the number above is the expected one; the flag says it is not a planned value.
        if (b.keepsSpeed) m["keepsSpeed"] = true
        if (b.keepsIncline) m["keepsIncline"] = true
        b.heartRate?.let { m["heartRate"] = guidanceTree(it) }
        m["estimatedDurationSeconds"] = b.estimatedDurationSeconds
        m["stepIndex"] = b.stepIndex
        if (b.blockInStep != null) m["blockInStep"] = b.blockInStep
        b.repetition?.let { m["repetition"] = linkedMapOf("iteration" to it.iteration, "count" to it.count) }
        return m
    }

    private fun guidanceTree(g: HeartRateGuidance): Map<String, Any?> {
        val (minKey, maxKey) = ProgramJson.boundKeys(g.adjust, relative = false)
        return linkedMapOf("zone" to ProgramJson.zoneTree(g.zone), "adjust" to g.adjust, minKey to g.minimum, maxKey to g.maximum)
    }

    private fun warningTree(w: PlanWarning): Map<String, Any?> = linkedMapOf(
        "code" to w.code,
        "blockIndex" to w.blockIndex,
        "requested" to w.requested,
        "applied" to w.applied,
        "message" to w.message,
    ).filterValues { it != null }

    // ------------------------------------------------------------------ reading

    private fun fromTree(o: JsonObject): ResolvedPlan {
        val version = o.int("planSchemaVersion")
        if (version > ResolvedPlan.PLAN_SCHEMA_VERSION) {
            throw ProgramFormatException("planSchemaVersion", "version $version is newer than the supported ${ResolvedPlan.PLAN_SCHEMA_VERSION}")
        }
        return ResolvedPlan(
            source = sourceFrom(o.obj("source")),
            parameters = parametersFrom(o.obj("parameters")),
            blocks = o.objects("blocks").map { blockFrom(it) },
            estimatedDurationSeconds = o.int("estimatedDurationSeconds"),
            estimatedDistanceMeters = o.int("estimatedDistanceMeters"),
            warnings = o.objects("warnings", optional = true).map { warningFrom(it) },
            planSchemaVersion = version,
        )
    }

    private fun sourceFrom(o: JsonObject) = PlanSource(
        programId = o.string("programId"),
        programName = o.string("programName"),
        kind = o.enum("kind", ProgramKind.entries.toTypedArray()),
        programSchemaVersion = o.int("programSchemaVersion"),
        origin = o.optObj("origin")?.let { ProgramJson.originFrom(it) },
    )

    private fun parametersFrom(o: JsonObject) = StartParameters(
        totalDurationSeconds = o.optInt("totalDurationSeconds"),
        easySpeedKmh = o.optDouble("easySpeedKmh"),
        maxSpeedKmh = o.optDouble("maxSpeedKmh"),
        maxInclinePercent = o.optDouble("maxInclinePercent"),
        intensityAdjustmentPercent = o.optInt("intensityAdjustmentPercent") ?: 0,
    )

    private fun blockFrom(o: JsonObject): ResolvedBlock {
        val end = when {
            o.has("durationSeconds") -> BlockEnd.Duration(o.int("durationSeconds"))
            o.has("distanceMeters") -> BlockEnd.Distance(o.int("distanceMeters"))
            else -> throw ProgramFormatException(o.path, "a block needs durationSeconds or distanceMeters")
        }
        return ResolvedBlock(
            index = o.int("index"),
            phase = o.enum("phase", BlockPhase.entries.toTypedArray()),
            name = o.optString("name"),
            end = end,
            speedKmh = o.double("speedKmh"),
            inclinePercent = o.double("inclinePercent"),
            heartRate = o.optObj("heartRate")?.let { guidanceFrom(it) },
            stepIndex = o.int("stepIndex"),
            blockInStep = o.optInt("blockInStep"),
            repetition = o.optObj("repetition")?.let { Repetition(it.int("iteration"), it.int("count")) },
            estimatedDurationSeconds = o.int("estimatedDurationSeconds"),
            keepsSpeed = o.boolean("keepsSpeed", false),
            keepsIncline = o.boolean("keepsIncline", false),
        )
    }

    private fun guidanceFrom(o: JsonObject): HeartRateGuidance {
        val adjust = o.enum("adjust", HeartRateAdjust.entries.toTypedArray())
        val (minKey, maxKey) = ProgramJson.boundKeys(adjust, relative = false)
        return HeartRateGuidance(ProgramJson.zoneFrom(o.obj("zone")), adjust, o.double(minKey), o.double(maxKey))
    }

    private fun warningFrom(o: JsonObject) = PlanWarning(
        code = o.enum("code", WarningCode.entries.toTypedArray()),
        blockIndex = o.optInt("blockIndex"),
        requested = o.optDouble("requested"),
        applied = o.optDouble("applied"),
        message = o.optString("message") ?: "",
    )
}
