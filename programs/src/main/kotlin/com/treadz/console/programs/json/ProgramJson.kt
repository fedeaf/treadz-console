package com.treadz.console.programs.json

import com.treadz.console.programs.model.Block
import com.treadz.console.programs.model.BlockEnd
import com.treadz.console.programs.model.BlockPhase
import com.treadz.console.programs.model.BlockTarget
import com.treadz.console.programs.model.HeartRateAdjust
import com.treadz.console.programs.model.HeartRateBasis
import com.treadz.console.programs.model.HeartRateZone
import com.treadz.console.programs.model.Program
import com.treadz.console.programs.model.ProgramKind
import com.treadz.console.programs.model.ProgramOrigin
import com.treadz.console.programs.model.ProgramStep
import com.treadz.console.programs.model.ProgramValidator
import com.treadz.console.programs.model.Repeat

/**
 * JSON document for a [Program], meant to be read by people (and by Claude) in the synced repository.
 *
 * Rules of the format:
 *  - Field names carry their unit (`durationSeconds`, `distanceMeters`, `speedKmh`, `inclinePercent`, `speedPercent`).
 *  - `schemaVersion` is mandatory. A document with a newer version than this code knows is rejected rather than
 *    half understood. Unknown fields are ignored on reading.
 *  - Enumerations are lower camel case strings (`warmUp`, `maxHeartRate`).
 *  - A block ends by `durationSeconds` or by `distanceMeters` (exactly one of them).
 *  - Heart-rate bounds are named after what they bound and the scale of the starting target:
 *    `minSpeedKmh`/`maxSpeedKmh` (absolute speed), `minSpeedPercent`/`maxSpeedPercent` (relative speed) or
 *    `minInclinePercent`/`maxInclinePercent` (incline, % or % of the range for a relative start).
 *
 * ```
 * {
 *   "schemaVersion": 1,
 *   "id": "5a0e0d2c-5b0f-4a56-9d6a-6f1f4b6d2c01",
 *   "name": "Norwegian 4x4",
 *   "kind": "free",
 *   "steps": [
 *     {"type": "block", "phase": "warmUp", "durationSeconds": 600, "target": {"type": "absolute", "speedKmh": 6.0, "inclinePercent": 2.0}},
 *     {"type": "repeat", "count": 4, "blocks": [
 *       {"phase": "work", "durationSeconds": 240, "target": {"type": "absolute", "speedKmh": 10.0, "inclinePercent": 6.0}},
 *       {"phase": "recovery", "durationSeconds": 180, "target": {"type": "absolute", "speedKmh": 6.0, "inclinePercent": 2.0}}
 *     ]}
 *   ]
 * }
 * ```
 */
object ProgramJson {

    fun encode(program: Program): String = JsonPretty.render(toTree(program))

    /**
     * Reads a program. Throws [ProgramFormatException] if the text is not a well formed document and, when [validate]
     * is true, [com.treadz.console.programs.model.InvalidProgramException] if the program breaks the model's limits.
     */
    fun decode(text: String, validate: Boolean = true): Program {
        val program = fromTree(JsonObject(parseDocument(text), ""))
        if (validate) ProgramValidator.requireValid(program)
        return program
    }

    // ------------------------------------------------------------------ writing

    internal fun toTree(p: Program): Map<String, Any?> = linkedMapOf(
        "schemaVersion" to p.schemaVersion,
        "id" to p.id,
        "name" to p.name,
        "kind" to p.kind,
        "description" to p.description,
        "origin" to p.origin?.let { originTree(it) },
        "steps" to p.steps.map { stepTree(it) },
    ).filterValues { it != null }

    internal fun originTree(o: ProgramOrigin): Map<String, Any?> = linkedMapOf(
        "generatorId" to o.generatorId,
        "generatorVersion" to o.generatorVersion,
        "inputs" to o.inputs,
    )

    private fun stepTree(step: ProgramStep): Map<String, Any?> = when (step) {
        is Block -> linkedMapOf<String, Any?>("type" to "block").also { it.putAll(blockTree(step)) }
        is Repeat -> linkedMapOf(
            "type" to "repeat",
            "count" to step.count,
            "blocks" to step.blocks.map { blockTree(it) },
        )
    }

    private fun blockTree(b: Block): Map<String, Any?> {
        val m = linkedMapOf<String, Any?>()
        if (b.name != null) m["name"] = b.name
        m["phase"] = b.phase
        when (val end = b.end) {
            is BlockEnd.Duration -> m["durationSeconds"] = end.seconds
            is BlockEnd.Distance -> m["distanceMeters"] = end.meters
        }
        m["target"] = targetTree(b.target)
        return m
    }

    private fun targetTree(t: BlockTarget): Map<String, Any?> = when (t) {
        is BlockTarget.Absolute -> linkedMapOf("type" to "absolute", "speedKmh" to t.speedKmh, "inclinePercent" to t.inclinePercent)
        is BlockTarget.Relative -> linkedMapOf("type" to "relative", "speedPercent" to t.speedPercent, "inclinePercent" to t.inclinePercent)
        is BlockTarget.HeartRate -> linkedMapOf<String, Any?>(
            "type" to "heartRate",
            "zone" to zoneTree(t.zone),
            "adjust" to t.adjust,
        ).also {
            val (minKey, maxKey) = boundKeys(t.adjust, t.start is BlockTarget.Relative)
            it[minKey] = t.minimum
            it[maxKey] = t.maximum
            it["start"] = targetTree(t.start)
        }
    }

    internal fun zoneTree(z: HeartRateZone): Map<String, Any?> =
        linkedMapOf("basis" to z.basis, "lowPercent" to z.lowPercent, "highPercent" to z.highPercent)

    /** Field names of the heart-rate bounds: they say what is bounded and in which scale. */
    internal fun boundKeys(adjust: HeartRateAdjust, relative: Boolean): Pair<String, String> = when {
        adjust == HeartRateAdjust.Incline -> "minInclinePercent" to "maxInclinePercent"
        relative -> "minSpeedPercent" to "maxSpeedPercent"
        else -> "minSpeedKmh" to "maxSpeedKmh"
    }

    // ------------------------------------------------------------------ reading

    private fun fromTree(o: JsonObject): Program {
        val version = o.int("schemaVersion")
        if (version > Program.SCHEMA_VERSION) {
            throw ProgramFormatException(
                "schemaVersion",
                "version $version is newer than the supported ${Program.SCHEMA_VERSION}: update the app to read this program",
            )
        }
        return Program(
            id = o.string("id"),
            name = o.string("name"),
            kind = o.enum("kind", ProgramKind.entries.toTypedArray()),
            steps = o.objects("steps").map { stepFrom(it) },
            description = o.optString("description"),
            origin = o.optObj("origin")?.let { originFrom(it) },
            schemaVersion = version,
        )
    }

    internal fun originFrom(o: JsonObject) = ProgramOrigin(
        generatorId = o.string("generatorId"),
        generatorVersion = o.int("generatorVersion"),
        inputs = o.stringMap("inputs"),
    )

    private fun stepFrom(o: JsonObject): ProgramStep {
        return when (val type = o.optString("type") ?: if (o.has("blocks")) "repeat" else "block") {
            "block" -> blockFrom(o)
            "repeat" -> Repeat(o.int("count"), o.objects("blocks").map { blockFrom(it) })
            else -> throw ProgramFormatException("${o.path}.type", "must be 'block' or 'repeat' (was '$type')")
        }
    }

    private fun blockFrom(o: JsonObject): Block {
        val hasDuration = o.has("durationSeconds")
        val hasDistance = o.has("distanceMeters")
        val end = when {
            hasDuration && hasDistance -> throw ProgramFormatException(o.path, "a block ends by durationSeconds or distanceMeters, not both")
            hasDuration -> BlockEnd.Duration(o.int("durationSeconds"))
            hasDistance -> BlockEnd.Distance(o.int("distanceMeters"))
            else -> throw ProgramFormatException(o.path, "a block needs durationSeconds or distanceMeters")
        }
        return Block(
            phase = o.enum("phase", BlockPhase.entries.toTypedArray()),
            end = end,
            target = targetFrom(o.obj("target")),
            name = o.optString("name"),
        )
    }

    internal fun targetFrom(o: JsonObject): BlockTarget = when (val type = o.string("type")) {
        "absolute", "relative" -> fixedFrom(o)
        "heartRate" -> {
            val start = fixedFrom(o.obj("start"))
            val adjust = o.enum("adjust", HeartRateAdjust.entries.toTypedArray())
            val (minKey, maxKey) = boundKeys(adjust, start is BlockTarget.Relative)
            BlockTarget.HeartRate(
                zone = zoneFrom(o.obj("zone")),
                adjust = adjust,
                minimum = o.double(minKey),
                maximum = o.double(maxKey),
                start = start,
            )
        }
        else -> throw ProgramFormatException("${o.path}.type", "must be 'absolute', 'relative' or 'heartRate' (was '$type')")
    }

    private fun fixedFrom(o: JsonObject): BlockTarget.Fixed = when (val type = o.string("type")) {
        "absolute" -> BlockTarget.Absolute(o.double("speedKmh"), o.double("inclinePercent"))
        "relative" -> BlockTarget.Relative(o.double("speedPercent"), o.double("inclinePercent"))
        else -> throw ProgramFormatException("${o.path}.type", "must be 'absolute' or 'relative' (was '$type')")
    }

    internal fun zoneFrom(o: JsonObject) = HeartRateZone(
        basis = o.enum("basis", HeartRateBasis.entries.toTypedArray()),
        lowPercent = o.int("lowPercent"),
        highPercent = o.int("highPercent"),
    )
}
