package com.treadz.console.programs.fitness

import com.treadz.console.programs.json.JsonObject
import com.treadz.console.programs.json.JsonPretty
import com.treadz.console.programs.json.ProgramFormatException
import com.treadz.console.programs.json.parseDocument
import com.treadz.console.programs.json.wireName
import java.time.Instant
import java.time.format.DateTimeParseException

/**
 * JSON documents for [FitnessTestResult] and [FitnessProfile], meant to be read by people (and by Claude) in the
 * synced repository.
 *
 * Rules of the format (the same as the program documents):
 *  - `schemaVersion` is mandatory; a newer version than this code knows is rejected. Unknown fields are ignored.
 *  - Field names carry their unit (`speedKmh`, `heartRateBpm`, `vo2maxMlKgMin`, `durationSeconds`...).
 *  - Times are ISO-8601 UTC strings (`"2026-10-08T10:00:00Z"`, milliseconds when not zero).
 *  - Enumerations are lower camel case (`ebbelingWalk`, `coolDown`). Flags carry their severity for the reader;
 *    unknown flag codes are dropped when reading (the stored `validity` is kept as written).
 *  - Absent values (`null`) are left out.
 *
 * Result of an Ebbeling test (abridged):
 * ```
 * {
 *   "schemaVersion": 1,
 *   "id": "0b6f8f9e-4d0c-4d43-9f43-2f0c6f1a7b10",
 *   "testType": "ebbelingWalk",
 *   "protocolVersion": 1,
 *   "startedAt": "2026-10-08T10:00:00Z", "effortEndedAt": "2026-10-08T10:08:00Z", "endedAt": "2026-10-08T10:11:00Z",
 *   "validity": "valid",
 *   "endReason": "completed",
 *   "flags": [],
 *   "inputs": {"ageYears": 40, "sex": "male", "weightKg": 80.0, "hrMaxBpm": 180, "agePredictedHrMaxBpm": 180,
 *              "heartRateSource": "treadmillConsole", "requestedStartSpeedKmh": 4.8, "startSpeedKmh": 4.8},
 *   "outputs": {"vo2maxMlKgMin": 40.4, "vo2maxSeeMlKgMin": 4.85, "method": "ebbeling1991", "vo2maxIsLowerBound": false,
 *               "classification": {"category": "fair", "ageGroup": "40-49", "ageOutsideNorms": false}},
 *   "durations": {"effortSeconds": 480, "coolDownSeconds": 180},
 *   "ebbeling": {"heartRateBandLowBpm": 90, "heartRateBandHighBpm": 126, "heartRateSafetyLimitBpm": 153,
 *                "testSpeedKmh": 4.8, "testSpeedMph": 2.98, "stage1Seconds": 240, "stage2Seconds": 240, ...,
 *                "stage1EndHeartRateBpm": 95.4, "steadyStateHeartRateBpm": 113.6, "steadyStateVariationBpm": 1.8,
 *                "heartRateRiseBpm": 18.2},
 *   "minutes": [
 *     {"phase": "stage1", "minute": 1, "endSecond": 60, "durationSeconds": 60, "speedKmh": 4.8, "inclinePercent": 0.0, "heartRateBpm": 88.5},
 *     ...
 *   ]
 * }
 * ```
 * A maximal test has `"maxTest": {...}` instead of `"ebbeling"`, and its outputs add `masKmh`, `masIsLowerBound`,
 * `hrMaxCandidateBpm` and `effort`. `maxTest` also carries `heartRatePlateau` and `plateauRiseBpm`. Each entry of the
 * Ebbeling `speedAdjustments` has an `origin` (`automatic`, `app` or `console`; absent = `automatic`).
 */
object FitnessTestJson {

    // ------------------------------------------------------------------ results

    fun encode(result: FitnessTestResult): String = JsonPretty.render(resultTree(result), WIDTH)

    /** Reads a result. Throws [ProgramFormatException] if the document is not well formed. */
    fun decode(text: String): FitnessTestResult = resultFrom(JsonObject(parseDocument(text), ""))

    private fun resultTree(r: FitnessTestResult): Map<String, Any?> = compact(
        linkedMapOf(
            "schemaVersion" to r.schemaVersion,
            "id" to r.id,
            "testType" to r.testType,
            "protocolVersion" to r.protocolVersion,
            "startedAt" to iso(r.startedAtMillis),
            "effortEndedAt" to iso(r.effortEndedAtMillis),
            "endedAt" to iso(r.endedAtMillis),
            "validity" to r.validity,
            "endReason" to r.endReason,
            "endDetail" to r.endDetail,
            "flags" to r.flags.map { linkedMapOf("code" to it, "severity" to it.severity) },
            "inputs" to compact(
                linkedMapOf(
                    "ageYears" to r.inputs.ageYears,
                    "sex" to r.inputs.sex,
                    "weightKg" to r.inputs.weightKg,
                    "hrMaxBpm" to r.inputs.hrMaxBpm,
                    "agePredictedHrMaxBpm" to r.inputs.agePredictedHrMaxBpm,
                    "heartRateSource" to r.inputs.heartRateSource,
                    "requestedStartSpeedKmh" to r.inputs.requestedStartSpeedKmh,
                    "startSpeedKmh" to r.inputs.startSpeedKmh,
                    "maxSpeedLimitKmh" to r.inputs.maxSpeedLimitKmh,
                    "maxInclineLimitPercent" to r.inputs.maxInclineLimitPercent,
                ),
            ),
            "outputs" to r.outputs?.let { outputsTree(it) },
            "durations" to linkedMapOf("effortSeconds" to r.effortSeconds, "coolDownSeconds" to r.coolDownSeconds),
            when (val m = r.measurements) {
                is EbbelingMeasurements -> "ebbeling" to ebbelingTree(m)
                is MaxTestMeasurements -> "maxTest" to maxTree(m)
            },
            "minutes" to r.minutes.map { minuteTree(it) },
        ),
    )

    private fun outputsTree(o: FitnessTestOutputs): Map<String, Any?> = compact(
        linkedMapOf(
            "vo2maxMlKgMin" to o.vo2maxMlKgMin,
            "vo2maxSeeMlKgMin" to o.vo2maxSeeMlKgMin,
            "method" to o.method,
            "vo2maxIsLowerBound" to o.vo2maxIsLowerBound,
            "classification" to o.classification?.let { classificationTree(it) },
            "masKmh" to o.masKmh,
            "masIsLowerBound" to if (o.masKmh != null) o.masIsLowerBound else null,
            "hrMaxCandidateBpm" to o.hrMaxCandidateBpm,
            "effort" to o.effort,
        ),
    )

    private fun classificationTree(c: Vo2MaxClassification) =
        linkedMapOf("category" to c.category, "ageGroup" to c.ageGroup, "ageOutsideNorms" to c.ageOutsideNorms)

    private fun ebbelingTree(m: EbbelingMeasurements): Map<String, Any?> = compact(
        linkedMapOf(
            "heartRateBandLowBpm" to m.heartRateBandLowBpm,
            "heartRateBandHighBpm" to m.heartRateBandHighBpm,
            "heartRateSafetyLimitBpm" to m.heartRateSafetyLimitBpm,
            "testSpeedKmh" to m.testSpeedKmh,
            "testSpeedMph" to m.testSpeedMph,
            "stage1Seconds" to m.stage1Seconds,
            "stage2Seconds" to m.stage2Seconds,
            "stage1Extensions" to m.stage1Extensions,
            "stage2Extensions" to m.stage2Extensions,
            "speedAdjustments" to m.speedAdjustments.map {
                compact(
                    linkedMapOf(
                        "atSecond" to it.atSecond, "fromKmh" to it.fromKmh, "toKmh" to it.toKmh, "heartRateBpm" to it.heartRateBpm,
                        "origin" to it.origin,
                    ),
                )
            },
            "stage1EndHeartRateBpm" to m.stage1EndHeartRateBpm,
            "steadyStateHeartRateBpm" to m.steadyStateHeartRateBpm,
            "steadyStateVariationBpm" to m.steadyStateVariationBpm,
            "heartRateRiseBpm" to m.heartRateRiseBpm,
        ),
    )

    private fun maxTree(m: MaxTestMeasurements): Map<String, Any?> = compact(
        linkedMapOf(
            "warmUpSeconds" to m.warmUpSeconds,
            "incrementalSeconds" to m.incrementalSeconds,
            "startSpeedKmh" to m.startSpeedKmh,
            "speedIncrementKmh" to m.speedIncrementKmh,
            "stageSeconds" to m.stageSeconds,
            "inclinePercent" to m.inclinePercent,
            "capKmh" to m.capKmh,
            "capReached" to m.capReached,
            "lastCompletedStageSpeedKmh" to m.lastCompletedStageSpeedKmh,
            "secondsIntoLastStage" to m.secondsIntoLastStage,
            "peakHeartRateBpm" to m.peakHeartRateBpm,
            "heartRateCoverage" to m.heartRateCoverage,
            "heartRatePlateau" to m.heartRatePlateau,
            "plateauRiseBpm" to m.plateauRiseBpm,
            "stages" to m.stages.map {
                compact(
                    linkedMapOf(
                        "number" to it.number,
                        "speedKmh" to it.speedKmh,
                        "inclinePercent" to it.inclinePercent,
                        "durationSeconds" to it.durationSeconds,
                        "completed" to it.completed,
                        "heartRateEndBpm" to it.heartRateEndBpm,
                    ),
                )
            },
        ),
    )

    private fun minuteTree(m: MinuteRecord): Map<String, Any?> = compact(
        linkedMapOf(
            "phase" to m.phase,
            "minute" to m.minute,
            "endSecond" to m.endSecond,
            "durationSeconds" to m.durationSeconds,
            "speedKmh" to m.speedKmh,
            "inclinePercent" to m.inclinePercent,
            "heartRateBpm" to m.heartRateBpm,
        ),
    )

    private fun resultFrom(o: JsonObject): FitnessTestResult {
        val version = checkVersion(o, FitnessTestResult.SCHEMA_VERSION, "result")
        val type = o.enum("testType", FitnessTestType.entries.toTypedArray())
        val measurements: FitnessTestMeasurements = when (type) {
            FitnessTestType.EbbelingWalk -> ebbelingFrom(o.obj("ebbeling"))
            FitnessTestType.MaxIncrementalRun -> maxFrom(o.obj("maxTest"))
        }
        val i = o.obj("inputs")
        return FitnessTestResult(
            id = o.string("id"),
            testType = type,
            protocolVersion = o.int("protocolVersion"),
            startedAtMillis = time(o, "startedAt"),
            effortEndedAtMillis = time(o, "effortEndedAt"),
            endedAtMillis = time(o, "endedAt"),
            validity = o.enum("validity", Validity.entries.toTypedArray()),
            flags = o.objects("flags", optional = true).mapNotNull { f ->
                val code = f.string("code")
                FitnessTestFlag.entries.firstOrNull { it.wireName() == code }
            },
            endReason = o.enum("endReason", FitnessEndReason.entries.toTypedArray()),
            endDetail = o.optString("endDetail"),
            inputs = FitnessTestInputs(
                ageYears = i.int("ageYears"),
                sex = i.enum("sex", Sex.entries.toTypedArray()),
                weightKg = i.double("weightKg"),
                hrMaxBpm = i.int("hrMaxBpm"),
                agePredictedHrMaxBpm = i.int("agePredictedHrMaxBpm"),
                heartRateSource = i.optEnum("heartRateSource", HeartRateSourceKind.entries.toTypedArray()),
                requestedStartSpeedKmh = i.double("requestedStartSpeedKmh"),
                startSpeedKmh = i.double("startSpeedKmh"),
                maxSpeedLimitKmh = i.optDouble("maxSpeedLimitKmh"),
                maxInclineLimitPercent = i.optDouble("maxInclineLimitPercent"),
            ),
            measurements = measurements,
            minutes = o.objects("minutes", optional = true).map { minuteFrom(it) },
            coolDownSeconds = o.optObj("durations")?.optInt("coolDownSeconds") ?: 0,
            outputs = o.optObj("outputs")?.let { outputsFrom(it) },
            schemaVersion = version,
        )
    }

    private fun outputsFrom(o: JsonObject) = FitnessTestOutputs(
        vo2maxMlKgMin = o.double("vo2maxMlKgMin"),
        vo2maxSeeMlKgMin = o.double("vo2maxSeeMlKgMin"),
        method = o.enum("method", Vo2MaxMethod.entries.toTypedArray()),
        vo2maxIsLowerBound = o.boolean("vo2maxIsLowerBound", false),
        classification = o.optObj("classification")?.let { classificationFrom(it) },
        masKmh = o.optDouble("masKmh"),
        masIsLowerBound = o.boolean("masIsLowerBound", false),
        hrMaxCandidateBpm = o.optInt("hrMaxCandidateBpm"),
        effort = o.optEnum("effort", EffortAssessment.entries.toTypedArray()),
    )

    private fun classificationFrom(o: JsonObject) = Vo2MaxClassification(
        category = o.enum("category", Vo2MaxCategory.entries.toTypedArray()),
        ageGroup = o.string("ageGroup"),
        ageOutsideNorms = o.boolean("ageOutsideNorms", false),
    )

    private fun ebbelingFrom(o: JsonObject) = EbbelingMeasurements(
        heartRateBandLowBpm = o.int("heartRateBandLowBpm"),
        heartRateBandHighBpm = o.int("heartRateBandHighBpm"),
        heartRateSafetyLimitBpm = o.int("heartRateSafetyLimitBpm"),
        testSpeedKmh = o.optDouble("testSpeedKmh"),
        testSpeedMph = o.optDouble("testSpeedMph"),
        stage1Seconds = o.int("stage1Seconds"),
        stage2Seconds = o.int("stage2Seconds"),
        stage1Extensions = o.optInt("stage1Extensions") ?: 0,
        stage2Extensions = o.optInt("stage2Extensions") ?: 0,
        speedAdjustments = o.objects("speedAdjustments", optional = true).map {
            SpeedAdjustment(
                it.int("atSecond"), it.double("fromKmh"), it.double("toKmh"), it.optDouble("heartRateBpm"),
                it.optEnum("origin", SpeedAdjustmentOrigin.entries.toTypedArray()) ?: SpeedAdjustmentOrigin.Automatic,
            )
        },
        stage1EndHeartRateBpm = o.optDouble("stage1EndHeartRateBpm"),
        steadyStateHeartRateBpm = o.optDouble("steadyStateHeartRateBpm"),
        steadyStateVariationBpm = o.optDouble("steadyStateVariationBpm"),
        heartRateRiseBpm = o.optDouble("heartRateRiseBpm"),
    )

    private fun maxFrom(o: JsonObject) = MaxTestMeasurements(
        warmUpSeconds = o.int("warmUpSeconds"),
        incrementalSeconds = o.int("incrementalSeconds"),
        startSpeedKmh = o.double("startSpeedKmh"),
        speedIncrementKmh = o.double("speedIncrementKmh"),
        stageSeconds = o.int("stageSeconds"),
        inclinePercent = o.double("inclinePercent"),
        capKmh = o.double("capKmh"),
        capReached = o.boolean("capReached", false),
        stages = o.objects("stages", optional = true).map {
            StageRecord(
                number = it.int("number"),
                speedKmh = it.double("speedKmh"),
                inclinePercent = it.double("inclinePercent"),
                durationSeconds = it.int("durationSeconds"),
                completed = it.boolean("completed", false),
                heartRateEndBpm = it.optDouble("heartRateEndBpm"),
            )
        },
        lastCompletedStageSpeedKmh = o.optDouble("lastCompletedStageSpeedKmh"),
        secondsIntoLastStage = o.optInt("secondsIntoLastStage"),
        peakHeartRateBpm = o.optDouble("peakHeartRateBpm"),
        heartRateCoverage = o.optDouble("heartRateCoverage"),
        heartRatePlateau = o.boolean("heartRatePlateau", false),
        plateauRiseBpm = o.optDouble("plateauRiseBpm"),
    )

    private fun minuteFrom(o: JsonObject) = MinuteRecord(
        phase = o.enum("phase", FitnessTestPhase.entries.toTypedArray()),
        minute = o.int("minute"),
        endSecond = o.int("endSecond"),
        durationSeconds = o.int("durationSeconds"),
        speedKmh = o.double("speedKmh"),
        inclinePercent = o.double("inclinePercent"),
        heartRateBpm = o.optDouble("heartRateBpm"),
    )

    // ------------------------------------------------------------------ profile

    /**
     * Fitness profile document:
     * ```
     * {
     *   "schemaVersion": 1,
     *   "computedAt": "2026-10-08T12:00:00Z",
     *   "rules": {"halfLifeDays": 90.0, "maxAgeDays": 365, "warningSeeFactor": 1.5},
     *   "vo2max": {"valueMlKgMin": 45.3, "seeMlKgMin": 4.0, "classification": {"category": "good", "ageGroup": "40-49", "ageOutsideNorms": false},
     *              "sources": [{"resultId": "...", "testType": "maxIncrementalRun", "testedAt": "2026-10-01T10:00:00Z", "vo2maxMlKgMin": 46.7,
     *                           "seeMlKgMin": 4.0, "ageDays": 7, "weight": 0.6, "lowerBound": false}, ...]},
     *   "mas": {"valueKmh": 13.35, "estimated": false, "lowerBound": false, "sourceResultId": "...", "testedAt": "2026-10-01T10:00:00Z", "effort": "maximal"},
     *   "hrMax": {"measuredBpm": 181, "sourceResultId": "...", "testedAt": "2026-10-01T10:00:00Z"}
     * }
     * ```
     */
    fun encodeProfile(profile: FitnessProfile): String = JsonPretty.render(
        width = WIDTH,
        value = compact(
            linkedMapOf(
                "schemaVersion" to profile.schemaVersion,
                "computedAt" to iso(profile.computedAtMillis),
                "rules" to linkedMapOf(
                    "halfLifeDays" to profile.rules.halfLifeDays,
                    "maxAgeDays" to profile.rules.maxAgeDays,
                    "warningSeeFactor" to profile.rules.warningSeeFactor,
                ),
                "vo2max" to profile.vo2max?.let { v ->
                    compact(
                        linkedMapOf(
                            "valueMlKgMin" to v.valueMlKgMin,
                            "seeMlKgMin" to v.seeMlKgMin,
                            "classification" to v.classification?.let { classificationTree(it) },
                            "sources" to v.sources.map {
                                linkedMapOf(
                                    "resultId" to it.resultId,
                                    "testType" to it.testType,
                                    "testedAt" to iso(it.testedAtMillis),
                                    "vo2maxMlKgMin" to it.vo2maxMlKgMin,
                                    "seeMlKgMin" to it.seeMlKgMin,
                                    "ageDays" to it.ageDays,
                                    "weight" to it.weight,
                                    "lowerBound" to it.lowerBound,
                                )
                            },
                        ),
                    )
                },
                "mas" to profile.mas?.let { m ->
                    compact(
                        linkedMapOf(
                            "valueKmh" to m.valueKmh,
                            "estimated" to m.estimated,
                            "lowerBound" to m.lowerBound,
                            "sourceResultId" to m.sourceResultId,
                            "testedAt" to m.testedAtMillis?.let { iso(it) },
                            "effort" to m.effort,
                        ),
                    )
                },
                "hrMax" to profile.hrMax?.let {
                    linkedMapOf("measuredBpm" to it.measuredBpm, "sourceResultId" to it.sourceResultId, "testedAt" to iso(it.testedAtMillis))
                },
            ),
        ),
    )

    fun decodeProfile(text: String): FitnessProfile {
        val o = JsonObject(parseDocument(text), "")
        val version = checkVersion(o, FitnessProfile.SCHEMA_VERSION, "profile")
        val rules = o.optObj("rules")?.let {
            FitnessProfileRules(it.double("halfLifeDays"), it.int("maxAgeDays"), it.double("warningSeeFactor"))
        } ?: FitnessProfileRules()
        return FitnessProfile(
            computedAtMillis = time(o, "computedAt"),
            vo2max = o.optObj("vo2max")?.let { v ->
                Vo2MaxEstimate(
                    valueMlKgMin = v.double("valueMlKgMin"),
                    seeMlKgMin = v.double("seeMlKgMin"),
                    classification = v.optObj("classification")?.let { classificationFrom(it) },
                    sources = v.objects("sources", optional = true).map {
                        Vo2MaxSource(
                            resultId = it.string("resultId"),
                            testType = it.enum("testType", FitnessTestType.entries.toTypedArray()),
                            testedAtMillis = time(it, "testedAt"),
                            vo2maxMlKgMin = it.double("vo2maxMlKgMin"),
                            seeMlKgMin = it.double("seeMlKgMin"),
                            ageDays = it.int("ageDays"),
                            weight = it.double("weight"),
                            lowerBound = it.boolean("lowerBound", false),
                        )
                    },
                )
            },
            mas = o.optObj("mas")?.let { m ->
                MasEstimate(
                    valueKmh = m.double("valueKmh"),
                    estimated = m.boolean("estimated", false),
                    lowerBound = m.boolean("lowerBound", false),
                    sourceResultId = m.optString("sourceResultId"),
                    testedAtMillis = if (m.has("testedAt")) time(m, "testedAt") else null,
                    effort = m.optEnum("effort", EffortAssessment.entries.toTypedArray()),
                )
            },
            hrMax = o.optObj("hrMax")?.let { h -> HrMaxEstimate(h.int("measuredBpm"), h.string("sourceResultId"), time(h, "testedAt")) },
            rules = rules,
            schemaVersion = version,
        )
    }

    // ------------------------------------------------------------------ helpers

    private fun checkVersion(o: JsonObject, supported: Int, what: String): Int {
        val version = o.int("schemaVersion")
        if (version > supported) {
            throw ProgramFormatException("schemaVersion", "version $version is newer than the supported $supported: update the app to read this $what")
        }
        return version
    }

    private fun iso(millis: Long): String = Instant.ofEpochMilli(millis).toString()

    private fun time(o: JsonObject, key: String): Long {
        val text = o.string(key)
        return try {
            Instant.parse(text).toEpochMilli()
        } catch (e: DateTimeParseException) {
            throw ProgramFormatException(if (o.path.isEmpty()) key else "${o.path}.$key", "must be an ISO-8601 time (was '$text')")
        }
    }

    /** Line width of the documents: a minute or stage record fits on one line. */
    private const val WIDTH = 170

    /** Leaves out absent values. */
    private fun compact(map: Map<String, Any?>): Map<String, Any?> = map.filterValues { it != null }
}
