package com.treadz.console.programs.fitness

import com.treadz.console.analysis.fitness.Sex
import com.treadz.console.analysis.fitness.Vo2MaxCategory
import com.treadz.console.analysis.fitness.Vo2MaxClassification
import com.treadz.console.analysis.thresholds.ThresholdMetrics
import com.treadz.console.analysis.thresholds.ThresholdPoint
import com.treadz.console.analysis.thresholds.ThresholdQuality
import com.treadz.console.analysis.thresholds.ThresholdReason
import com.treadz.console.analysis.thresholds.ThresholdResult
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
 *
 * The maximal walking test (`"testType": "maxHeartRateWalk"`) carries `"maxHeartRateWalk": {"warmUpSeconds",
 * "rampSeconds", "startSpeedKmh", "startInclinePercent", "inclineIncrementPercent", "inclineCapPercent",
 * "speedIncrementKmh", "speedCapKmh", "stageSeconds", "capReached", "peakHeartRateBpm", "heartRateCoverage",
 * "heartRatePlateau", "plateauRiseBpm", "hrMaxCandidateBpm", "effort", "stages": [...]}`. Since version 2 a valid one
 * has `outputs` with `"method": "acsmWalkRamp"`, `"vo2maxSeeMlKgMin": 3.5`, `masKmh` (VO2max / 3.5) with
 * `"masIsEstimated": true`, `hrMaxCandidateBpm` and `effort`. `inputs.heartRateSource` is `treadmillConsole` or
 * `directStrap` (the source of most readings).
 *
 * **Version 2** adds the `thresholds` block of the maximal tests (walking and running), with the version of the
 * algorithm that produced it:
 * ```
 * "thresholds": {"algorithmVersion": 2, "quality": "high",
 *   "vt1": {"heartRateBpm": 132.4, "speedKmh": 6.2, "inclinePercent": 11.3, "vo2MlKgMin": 31.6, "percentVo2max": 72.5,
 *           "percentHeartRateReserve": 61.0},
 *   "vt2": {"heartRateBpm": 151.2, ...},
 *   "metrics": {"stretchWindows": 31, "validWindows": 48, "totalWindows": 50, "rSquared": 0.82,
 *               "meanArtifactPercent": 1.2, "slopePerBpm": -0.021, "interceptAlpha1": 3.53}}
 * ```
 * or, without thresholds, `{"algorithmVersion": 2, "reason": "noRrIntervals"}` (also `noWorkoutSamples`,
 * `notEnoughValidWindows`, `noAlpha1Decline`, `vt1OutOfRange`; then `quality` and `metrics` may still be present).
 * A result is written with the lowest version that holds it: version 1 when it has neither `thresholds` nor an
 * `acsmWalkRamp` VO2max (so older apps keep reading it). Version 1 documents are read as before.
 */
object FitnessTestJson {

    // ------------------------------------------------------------------ results

    fun encode(result: FitnessTestResult): String = JsonPretty.render(resultTree(result), WIDTH)

    /** Reads a result. Throws [ProgramFormatException] if the document is not well formed. */
    fun decode(text: String): FitnessTestResult = resultFrom(JsonObject(parseDocument(text), ""))

    private fun resultTree(r: FitnessTestResult): Map<String, Any?> = compact(
        linkedMapOf(
            "schemaVersion" to maxOf(r.schemaVersion, FitnessTestResult.schemaVersionFor(r.outputs, r.thresholds)),
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
            "thresholds" to r.thresholds?.let { thresholdsTree(it) },
            "durations" to linkedMapOf("effortSeconds" to r.effortSeconds, "coolDownSeconds" to r.coolDownSeconds),
            when (val m = r.measurements) {
                is EbbelingMeasurements -> "ebbeling" to ebbelingTree(m)
                is MaxTestMeasurements -> "maxTest" to maxTree(m)
                is MaxHeartRateWalkMeasurements -> "maxHeartRateWalk" to walkTree(m)
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
            "masIsEstimated" to if (o.masIsEstimated) true else null,
        ),
    )

    private fun thresholdsTree(t: ThresholdResult): Map<String, Any?> = compact(
        linkedMapOf(
            "algorithmVersion" to t.algorithmVersion,
            "quality" to t.quality,
            "reason" to t.reason,
            "vt1" to t.vt1?.let { pointTree(it) },
            "vt2" to t.vt2?.let { pointTree(it) },
            "metrics" to t.metrics?.let {
                linkedMapOf(
                    "stretchWindows" to it.stretchWindows,
                    "validWindows" to it.validWindows,
                    "totalWindows" to it.totalWindows,
                    "rSquared" to it.rSquared,
                    "meanArtifactPercent" to it.meanArtifactPercent,
                    "slopePerBpm" to it.slopePerBpm,
                    "interceptAlpha1" to it.interceptAlpha1,
                )
            },
        ),
    )

    private fun pointTree(p: ThresholdPoint): Map<String, Any?> = compact(
        linkedMapOf(
            "heartRateBpm" to p.heartRateBpm,
            "speedKmh" to p.speedKmh,
            "inclinePercent" to p.inclinePercent,
            "vo2MlKgMin" to p.vo2MlKgMin,
            "percentVo2max" to p.percentVo2max,
            "percentHeartRateReserve" to p.percentHeartRateReserve,
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

    private fun stageTree(it: StageRecord): Map<String, Any?> = compact(
        linkedMapOf(
            "number" to it.number,
            "speedKmh" to it.speedKmh,
            "inclinePercent" to it.inclinePercent,
            "durationSeconds" to it.durationSeconds,
            "completed" to it.completed,
            "heartRateEndBpm" to it.heartRateEndBpm,
        ),
    )

    private fun walkTree(m: MaxHeartRateWalkMeasurements): Map<String, Any?> = compact(
        linkedMapOf(
            "warmUpSeconds" to m.warmUpSeconds,
            "rampSeconds" to m.rampSeconds,
            "startSpeedKmh" to m.startSpeedKmh,
            "startInclinePercent" to m.startInclinePercent,
            "inclineIncrementPercent" to m.inclineIncrementPercent,
            "inclineCapPercent" to m.inclineCapPercent,
            "speedIncrementKmh" to m.speedIncrementKmh,
            "speedCapKmh" to m.speedCapKmh,
            "stageSeconds" to m.stageSeconds,
            "capReached" to m.capReached,
            "peakHeartRateBpm" to m.peakHeartRateBpm,
            "heartRateCoverage" to m.heartRateCoverage,
            "heartRatePlateau" to m.heartRatePlateau,
            "plateauRiseBpm" to m.plateauRiseBpm,
            "hrMaxCandidateBpm" to m.hrMaxCandidateBpm,
            "effort" to m.effort,
            "stages" to m.stages.map { stageTree(it) },
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
            FitnessTestType.MaxHeartRateWalk -> walkFrom(o.obj("maxHeartRateWalk"))
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
            thresholds = o.optObj("thresholds")?.let { thresholdsFrom(it) },
            schemaVersion = version,
        )
    }

    private fun thresholdsFrom(o: JsonObject) = ThresholdResult(
        algorithmVersion = o.int("algorithmVersion"),
        vt1 = o.optObj("vt1")?.let { pointFrom(it) },
        vt2 = o.optObj("vt2")?.let { pointFrom(it) },
        quality = o.optEnum("quality", ThresholdQuality.entries.toTypedArray()),
        metrics = o.optObj("metrics")?.let {
            ThresholdMetrics(
                stretchWindows = it.int("stretchWindows"),
                validWindows = it.int("validWindows"),
                totalWindows = it.int("totalWindows"),
                rSquared = it.double("rSquared"),
                meanArtifactPercent = it.double("meanArtifactPercent"),
                slopePerBpm = it.double("slopePerBpm"),
                interceptAlpha1 = it.double("interceptAlpha1"),
            )
        },
        // A reason written by a newer app that this one does not know is kept as "not enough data".
        reason = o.optString("reason")?.let { code -> ThresholdReason.entries.firstOrNull { it.wireName() == code } ?: ThresholdReason.NotEnoughValidWindows },
    )

    private fun pointFrom(o: JsonObject) = ThresholdPoint(
        heartRateBpm = o.double("heartRateBpm"),
        speedKmh = o.optDouble("speedKmh"),
        inclinePercent = o.optDouble("inclinePercent"),
        vo2MlKgMin = o.optDouble("vo2MlKgMin"),
        percentVo2max = o.optDouble("percentVo2max"),
        percentHeartRateReserve = o.optDouble("percentHeartRateReserve"),
    )

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
        masIsEstimated = o.boolean("masIsEstimated", false),
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

    private fun stageFrom(it: JsonObject) = StageRecord(
        number = it.int("number"),
        speedKmh = it.double("speedKmh"),
        inclinePercent = it.double("inclinePercent"),
        durationSeconds = it.int("durationSeconds"),
        completed = it.boolean("completed", false),
        heartRateEndBpm = it.optDouble("heartRateEndBpm"),
    )

    private fun walkFrom(o: JsonObject) = MaxHeartRateWalkMeasurements(
        warmUpSeconds = o.int("warmUpSeconds"),
        rampSeconds = o.int("rampSeconds"),
        startSpeedKmh = o.double("startSpeedKmh"),
        startInclinePercent = o.double("startInclinePercent"),
        inclineIncrementPercent = o.double("inclineIncrementPercent"),
        inclineCapPercent = o.double("inclineCapPercent"),
        speedIncrementKmh = o.double("speedIncrementKmh"),
        speedCapKmh = o.double("speedCapKmh"),
        stageSeconds = o.int("stageSeconds"),
        capReached = o.boolean("capReached", false),
        stages = o.objects("stages", optional = true).map { stageFrom(it) },
        peakHeartRateBpm = o.optDouble("peakHeartRateBpm"),
        heartRateCoverage = o.optDouble("heartRateCoverage"),
        heartRatePlateau = o.boolean("heartRatePlateau", false),
        plateauRiseBpm = o.optDouble("plateauRiseBpm"),
        hrMaxCandidateBpm = o.optInt("hrMaxCandidateBpm"),
        effort = o.optEnum("effort", EffortAssessment.entries.toTypedArray()),
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
     *   "hrMax": {"measuredBpm": 181, "sourceResultId": "...", "testedAt": "2026-10-01T10:00:00Z"},
     *   "thresholds": {
     *     "rules": {"halfLifeDays": 30.0, "maxAgeDays": 180, "highQualityWeight": 1.0, "mediumQualityWeight": 0.5,
     *               "jumpBpm": 6.0, "jumpFactor": 0.5, "corroborationDays": 60, "corroborationBpm": 4.0},
     *     "vt1": {"heartRateBpm": 132, "vo2MlKgMin": 31.6, "quality": "high",
     *             "sources": [{"resultId": "...", "testType": "maxHeartRateWalk", "testedAt": "...", "heartRateBpm": 132.4,
     *                          "vo2MlKgMin": 31.6, "quality": "high", "ageDays": 2, "weight": 1.0, "jump": false,
     *                          "corroborated": false}]},
     *     "vt2": {...},
     *     "history": [{"resultId": "...", "testType": "maxHeartRateWalk", "testedAt": "...", "vt1Bpm": 132.4,
     *                  "vt2Bpm": 151.2, "quality": "high"}]
     *   }
     * }
     * ```
     * Added on 2026-10-17 (optional, the profile stays at version 1 because older apps ignore unknown fields):
     * `thresholds`; in `rules` `submaximalFactorWithMaximal`, `maximalRecencyDays` and `discrepancyFactor`; in
     * `vo2max` `discrepancy` (only when true); in each VO2max source `weightFactor` (only when not 1) and `discrepant`
     * (only when true).
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
                    "submaximalFactorWithMaximal" to profile.rules.submaximalFactorWithMaximal,
                    "maximalRecencyDays" to profile.rules.maximalRecencyDays,
                    "discrepancyFactor" to profile.rules.discrepancyFactor,
                ),
                "vo2max" to profile.vo2max?.let { v ->
                    compact(
                        linkedMapOf(
                            "valueMlKgMin" to v.valueMlKgMin,
                            "seeMlKgMin" to v.seeMlKgMin,
                            "classification" to v.classification?.let { classificationTree(it) },
                            "sources" to v.sources.map {
                                compact(
                                    linkedMapOf(
                                        "resultId" to it.resultId,
                                        "testType" to it.testType,
                                        "testedAt" to iso(it.testedAtMillis),
                                        "vo2maxMlKgMin" to it.vo2maxMlKgMin,
                                        "seeMlKgMin" to it.seeMlKgMin,
                                        "ageDays" to it.ageDays,
                                        "weight" to it.weight,
                                        "lowerBound" to it.lowerBound,
                                        "weightFactor" to if (it.weightFactor != 1.0) it.weightFactor else null,
                                        "discrepant" to if (it.discrepant) true else null,
                                    ),
                                )
                            },
                            "discrepancy" to if (v.discrepancy) true else null,
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
                "thresholds" to profile.thresholds?.let { thresholdProfileTree(it) },
            ),
        ),
    )

    private fun thresholdProfileTree(t: ThresholdProfile): Map<String, Any?> = compact(
        linkedMapOf(
            "rules" to linkedMapOf(
                "halfLifeDays" to t.rules.halfLifeDays,
                "maxAgeDays" to t.rules.maxAgeDays,
                "highQualityWeight" to t.rules.highQualityWeight,
                "mediumQualityWeight" to t.rules.mediumQualityWeight,
                "jumpBpm" to t.rules.jumpBpm,
                "jumpFactor" to t.rules.jumpFactor,
                "corroborationDays" to t.rules.corroborationDays,
                "corroborationBpm" to t.rules.corroborationBpm,
            ),
            "vt1" to t.vt1?.let { thresholdEstimateTree(it) },
            "vt2" to t.vt2?.let { thresholdEstimateTree(it) },
            "history" to t.history.map {
                compact(
                    linkedMapOf(
                        "resultId" to it.resultId,
                        "testType" to it.testType,
                        "testedAt" to iso(it.testedAtMillis),
                        "vt1Bpm" to it.vt1Bpm,
                        "vt2Bpm" to it.vt2Bpm,
                        "quality" to it.quality,
                    ),
                )
            },
        ),
    )

    private fun thresholdEstimateTree(e: ThresholdEstimate): Map<String, Any?> = compact(
        linkedMapOf(
            "heartRateBpm" to e.heartRateBpm,
            "vo2MlKgMin" to e.vo2MlKgMin,
            "quality" to e.quality,
            "sources" to e.sources.map {
                compact(
                    linkedMapOf(
                        "resultId" to it.resultId,
                        "testType" to it.testType,
                        "testedAt" to iso(it.testedAtMillis),
                        "heartRateBpm" to it.heartRateBpm,
                        "vo2MlKgMin" to it.vo2MlKgMin,
                        "quality" to it.quality,
                        "ageDays" to it.ageDays,
                        "weight" to it.weight,
                        "jump" to it.jump,
                        "corroborated" to it.corroborated,
                    ),
                )
            },
        ),
    )

    private fun thresholdProfileFrom(o: JsonObject): ThresholdProfile {
        val defaults = ThresholdProfileRules()
        val rules = o.optObj("rules")?.let {
            ThresholdProfileRules(
                halfLifeDays = it.optDouble("halfLifeDays") ?: defaults.halfLifeDays,
                maxAgeDays = it.optInt("maxAgeDays") ?: defaults.maxAgeDays,
                highQualityWeight = it.optDouble("highQualityWeight") ?: defaults.highQualityWeight,
                mediumQualityWeight = it.optDouble("mediumQualityWeight") ?: defaults.mediumQualityWeight,
                jumpBpm = it.optDouble("jumpBpm") ?: defaults.jumpBpm,
                jumpFactor = it.optDouble("jumpFactor") ?: defaults.jumpFactor,
                corroborationDays = it.optInt("corroborationDays") ?: defaults.corroborationDays,
                corroborationBpm = it.optDouble("corroborationBpm") ?: defaults.corroborationBpm,
            )
        } ?: defaults
        val qualities = ThresholdQuality.entries.toTypedArray()
        fun estimate(e: JsonObject) = ThresholdEstimate(
            heartRateBpm = e.int("heartRateBpm"),
            vo2MlKgMin = e.optDouble("vo2MlKgMin"),
            quality = e.enum("quality", qualities),
            sources = e.objects("sources", optional = true).map {
                ThresholdSource(
                    resultId = it.string("resultId"),
                    testType = it.enum("testType", FitnessTestType.entries.toTypedArray()),
                    testedAtMillis = time(it, "testedAt"),
                    heartRateBpm = it.double("heartRateBpm"),
                    vo2MlKgMin = it.optDouble("vo2MlKgMin"),
                    quality = it.enum("quality", qualities),
                    ageDays = it.int("ageDays"),
                    weight = it.double("weight"),
                    jump = it.boolean("jump", false),
                    corroborated = it.boolean("corroborated", false),
                )
            },
        )
        return ThresholdProfile(
            vt1 = o.optObj("vt1")?.let { estimate(it) },
            vt2 = o.optObj("vt2")?.let { estimate(it) },
            history = o.objects("history", optional = true).map {
                ThresholdHistoryEntry(
                    resultId = it.string("resultId"),
                    testType = it.enum("testType", FitnessTestType.entries.toTypedArray()),
                    testedAtMillis = time(it, "testedAt"),
                    vt1Bpm = it.optDouble("vt1Bpm"),
                    vt2Bpm = it.optDouble("vt2Bpm"),
                    quality = it.optEnum("quality", qualities),
                )
            },
            rules = rules,
        )
    }

    fun decodeProfile(text: String): FitnessProfile {
        val o = JsonObject(parseDocument(text), "")
        val version = checkVersion(o, FitnessProfile.SCHEMA_VERSION, "profile")
        val defaults = FitnessProfileRules()
        val rules = o.optObj("rules")?.let {
            FitnessProfileRules(
                halfLifeDays = it.double("halfLifeDays"),
                maxAgeDays = it.int("maxAgeDays"),
                warningSeeFactor = it.double("warningSeeFactor"),
                submaximalFactorWithMaximal = it.optDouble("submaximalFactorWithMaximal") ?: defaults.submaximalFactorWithMaximal,
                maximalRecencyDays = it.optInt("maximalRecencyDays") ?: defaults.maximalRecencyDays,
                discrepancyFactor = it.optDouble("discrepancyFactor") ?: defaults.discrepancyFactor,
            )
        } ?: defaults
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
                            weightFactor = it.optDouble("weightFactor") ?: 1.0,
                            discrepant = it.boolean("discrepant", false),
                        )
                    },
                    discrepancy = v.boolean("discrepancy", false),
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
            thresholds = o.optObj("thresholds")?.let { thresholdProfileFrom(it) },
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
