package com.treadz.console.userdata

import com.treadz.console.analysis.workout.AnalysisReason
import com.treadz.console.analysis.workout.DecouplingAnalysis
import com.treadz.console.analysis.workout.EfficiencyAnalysis
import com.treadz.console.analysis.workout.EnergyAnalysis
import com.treadz.console.analysis.workout.FitnessIndexAnalysis
import com.treadz.console.analysis.workout.HeartRateSourceMix
import com.treadz.console.analysis.workout.RecoveryAnalysis
import com.treadz.console.analysis.workout.RecoveryContext
import com.treadz.console.analysis.workout.Section
import com.treadz.console.analysis.workout.SessionAlpha1
import com.treadz.console.analysis.workout.SignalQuality
import com.treadz.console.analysis.workout.ThresholdEstimatesAnalysis
import com.treadz.console.analysis.workout.ThresholdKind
import com.treadz.console.analysis.workout.TrimpAnalysis
import com.treadz.console.analysis.workout.WorkoutAnalysis
import com.treadz.console.analysis.workout.WorkoutThresholdEstimate
import com.treadz.console.analysis.workout.ZoneTimeAnalysis
import com.treadz.console.analysis.zones.FitnessLevel
import com.treadz.console.analysis.zones.ZoneRange
import com.treadz.console.analysis.zones.ZoneSet
import com.treadz.console.analysis.zones.ZoneSource
import com.treadz.console.treadmill.recording.Json

/**
 * The JSON document of a stored [WorkoutAnalysis] (the `json` column of the analysis cache): the complete analysis, every
 * section with its value or its reason, and the zones used. It is local to the device and never synchronised, so the
 * format has no version of its own: a document that cannot be read (written by another version of the app) is simply
 * not shown and is replaced when the analysis is recalculated (the algorithm version is stored next to it).
 *
 * Enums are stored by name, absent values as `null`. Sections are `{"value": {...}}` or `{"reason": "Name"}`.
 * Doubles must be finite (JSON has no NaN): the analyzer only produces finite values.
 */
internal object WorkoutAnalysisJson {

    fun encode(analysis: WorkoutAnalysis): String = Json.stringify(
        linkedMapOf(
            "algorithmVersion" to analysis.algorithmVersion,
            "signal" to writeSignal(analysis.signal),
            "energy" to writeSection(analysis.energy, ::writeEnergy),
            "zoneTime" to writeSection(analysis.zoneTime, ::writeZoneTime),
            "trimp" to writeSection(analysis.trimp, ::writeTrimp),
            "efficiency" to writeSection(analysis.efficiency, ::writeEfficiency),
            "decoupling" to writeSection(analysis.decoupling, ::writeDecoupling),
            "fitnessIndex" to writeSection(analysis.fitnessIndex, ::writeFitnessIndex),
            "recovery" to writeSection(analysis.recovery, ::writeRecovery),
            "alpha1" to writeSection(analysis.alpha1, ::writeAlpha1),
            "thresholdEstimates" to writeSection(analysis.thresholdEstimates, ::writeThresholdEstimates),
        ),
    )

    /** The analysis of [text]; `IllegalArgumentException` if it is not a document of this format. */
    fun decode(text: String): WorkoutAnalysis = try {
        read(text)
    } catch (e: IllegalArgumentException) {
        throw e
    } catch (e: RuntimeException) {
        // The shared parser reports some malformed texts with other exception types.
        throw IllegalArgumentException("Not a workout analysis document: ${e.message}", e)
    }

    private fun read(text: String): WorkoutAnalysis {
        val root = Obj(Json.parseObject(text), "")
        return WorkoutAnalysis(
            algorithmVersion = root.int("algorithmVersion"),
            signal = readSignal(root.obj("signal")),
            energy = readSection(root, "energy", ::readEnergy),
            zoneTime = readSection(root, "zoneTime", ::readZoneTime),
            trimp = readSection(root, "trimp", ::readTrimp),
            efficiency = readSection(root, "efficiency", ::readEfficiency),
            decoupling = readSection(root, "decoupling", ::readDecoupling),
            fitnessIndex = readSection(root, "fitnessIndex", ::readFitnessIndex),
            recovery = readSection(root, "recovery", ::readRecovery),
            alpha1 = readSection(root, "alpha1", ::readAlpha1),
            // Documents of algorithm version 1 have no such section (they are recalculated anyway).
            thresholdEstimates = if (root.has("thresholdEstimates")) {
                readSection(root, "thresholdEstimates", ::readThresholdEstimates)
            } else {
                Section(null, AnalysisReason.NoThresholdReference)
            },
        )
    }

    // ---------------------------------------------------------------- writing

    private fun <T : Any> writeSection(section: Section<T>, write: (T) -> Map<String, Any?>): Map<String, Any?> {
        val value = section.value
        return if (value != null) mapOf("value" to write(value)) else mapOf("reason" to section.reason?.name)
    }

    private fun writeSignal(s: SignalQuality) = linkedMapOf(
        "sampleCount" to s.sampleCount,
        "coveredSeconds" to s.coveredSeconds,
        "gapSeconds" to s.gapSeconds,
        "movingSeconds" to s.movingSeconds,
        "heartRateSeconds" to s.heartRateSeconds,
        "frozenHeartRateSeconds" to s.frozenHeartRateSeconds,
        "heartRateCoverage" to s.heartRateCoverage,
        "heartRateSources" to linkedMapOf(
            "strapSeconds" to s.heartRateSources.strapSeconds,
            "consoleSeconds" to s.heartRateSources.consoleSeconds,
            "unknownSeconds" to s.heartRateSources.unknownSeconds,
        ),
        "rrBeats" to s.rrBeats,
        "rrArtifactPercent" to s.rrArtifactPercent,
    )

    private fun writeEnergy(e: EnergyAnalysis) = linkedMapOf(
        "movingSeconds" to e.movingSeconds,
        "meanSpeedKmh" to e.meanSpeedKmh,
        "distanceKm" to e.distanceKm,
        "meanVo2MlKgMin" to e.meanVo2MlKgMin,
        "meanMets" to e.meanMets,
        "percentOfVo2max" to e.percentOfVo2max,
        "grossKcal" to e.grossKcal,
        "netKcal" to e.netKcal,
    )

    private fun writeZoneTime(z: ZoneTimeAnalysis) = linkedMapOf(
        "zones" to writeZoneSet(z.zones),
        "secondsInZone" to z.secondsInZone,
        "analysedSeconds" to z.analysedSeconds,
    )

    private fun writeZoneSet(z: ZoneSet) = linkedMapOf(
        "zones" to z.zones.map { linkedMapOf("number" to it.number, "lowBpm" to it.lowBpm, "highBpm" to it.highBpm) },
        "source" to z.source.name,
        "level" to z.level.name,
        "usedReserve" to z.usedReserve,
        "vt1Bpm" to z.vt1Bpm,
        "vt2Bpm" to z.vt2Bpm,
        "vt2FromTable" to z.vt2FromTable,
    )

    private fun writeTrimp(t: TrimpAnalysis) = linkedMapOf(
        "trimp" to t.trimp,
        "minutes" to t.minutes,
        "restingHeartRateBpm" to t.restingHeartRateBpm,
        "restingAssumed" to t.restingAssumed,
    )

    private fun writeEfficiency(e: EfficiencyAnalysis) = linkedMapOf(
        "meanVo2MlKgMin" to e.meanVo2MlKgMin,
        "meanHeartRateBpm" to e.meanHeartRateBpm,
        "efficiencyFactor" to e.efficiencyFactor,
        "seconds" to e.seconds,
    )

    private fun writeDecoupling(d: DecouplingAnalysis) = linkedMapOf(
        "mainBlockSeconds" to d.mainBlockSeconds,
        "analysedSeconds" to d.analysedSeconds,
        "ef1" to d.ef1,
        "ef2" to d.ef2,
        "decouplingPercent" to d.decouplingPercent,
        "zone12Fraction" to d.zone12Fraction,
        "longSteadyZone2" to d.longSteadyZone2,
    )

    private fun writeFitnessIndex(f: FitnessIndexAnalysis) = linkedMapOf(
        "vo2maxEstimate" to f.vo2maxEstimate,
        "stretchCount" to f.stretchCount,
        "stretchesFound" to f.stretchesFound,
        "estimates" to f.estimates,
        "restingAssumed" to f.restingAssumed,
    )

    private fun writeRecovery(r: RecoveryAnalysis) = linkedMapOf(
        "peakHeartRateBpm" to r.peakHeartRateBpm,
        "peakEndMillis" to r.peakEndMillis,
        "drop60Bpm" to r.drop60Bpm,
        "drop120Bpm" to r.drop120Bpm,
        "context" to r.context.name,
        "meanVo2MlKgMin" to r.meanVo2MlKgMin,
    )

    private fun writeAlpha1(a: SessionAlpha1) = linkedMapOf(
        "totalWindows" to a.totalWindows,
        "validWindows" to a.validWindows,
        "secondsAtOrAbove" to a.secondsAtOrAbove,
        "secondsBelow" to a.secondsBelow,
        "meanAlpha1" to a.meanAlpha1,
    )

    private fun writeThresholdEstimates(t: ThresholdEstimatesAnalysis) = linkedMapOf(
        "stretchesFound" to t.stretchesFound,
        "estimates" to t.estimates.map { e ->
            linkedMapOf(
                "kind" to e.kind.name,
                "startMillis" to e.startMillis,
                "endMillis" to e.endMillis,
                "speedKmh" to e.speedKmh,
                "inclinePercent" to e.inclinePercent,
                "cleanWindows" to e.cleanWindows,
                "alpha1" to e.alpha1,
                "heartRateBpm" to e.heartRateBpm,
                "slopePerBpm" to e.slopePerBpm,
                "impliedBpm" to e.impliedBpm,
                "weightSeconds" to e.weightSeconds,
            )
        },
    )

    // ---------------------------------------------------------------- reading

    private fun <T : Any> readSection(parent: Obj, key: String, read: (Obj) -> T): Section<T> {
        val section = parent.obj(key)
        return if (section.has("value")) Section.of(read(section.obj("value"))) else Section(null, section.enum("reason", AnalysisReason.entries))
    }

    private fun readSignal(o: Obj) = SignalQuality(
        sampleCount = o.int("sampleCount"),
        coveredSeconds = o.int("coveredSeconds"),
        gapSeconds = o.int("gapSeconds"),
        movingSeconds = o.int("movingSeconds"),
        heartRateSeconds = o.int("heartRateSeconds"),
        frozenHeartRateSeconds = o.int("frozenHeartRateSeconds"),
        heartRateCoverage = o.optDouble("heartRateCoverage"),
        heartRateSources = o.obj("heartRateSources").let {
            HeartRateSourceMix(it.int("strapSeconds"), it.int("consoleSeconds"), it.int("unknownSeconds"))
        },
        rrBeats = o.int("rrBeats"),
        rrArtifactPercent = o.optDouble("rrArtifactPercent"),
    )

    private fun readEnergy(o: Obj) = EnergyAnalysis(
        movingSeconds = o.int("movingSeconds"),
        meanSpeedKmh = o.double("meanSpeedKmh"),
        distanceKm = o.double("distanceKm"),
        meanVo2MlKgMin = o.double("meanVo2MlKgMin"),
        meanMets = o.double("meanMets"),
        percentOfVo2max = o.optDouble("percentOfVo2max"),
        grossKcal = o.optDouble("grossKcal"),
        netKcal = o.optDouble("netKcal"),
    )

    private fun readZoneTime(o: Obj) = ZoneTimeAnalysis(
        zones = readZoneSet(o.obj("zones")),
        secondsInZone = o.list("secondsInZone").map { toInt(it) },
        analysedSeconds = o.int("analysedSeconds"),
    )

    private fun readZoneSet(o: Obj) = ZoneSet(
        zones = o.objects("zones").map { ZoneRange(it.int("number"), it.optInt("lowBpm"), it.int("highBpm")) },
        source = o.enum("source", ZoneSource.entries),
        level = o.enum("level", FitnessLevel.entries),
        usedReserve = o.boolean("usedReserve"),
        vt1Bpm = o.int("vt1Bpm"),
        vt2Bpm = o.int("vt2Bpm"),
        vt2FromTable = o.boolean("vt2FromTable"),
    )

    private fun readTrimp(o: Obj) = TrimpAnalysis(
        trimp = o.double("trimp"),
        minutes = o.double("minutes"),
        restingHeartRateBpm = o.int("restingHeartRateBpm"),
        restingAssumed = o.boolean("restingAssumed"),
    )

    private fun readEfficiency(o: Obj) = EfficiencyAnalysis(
        meanVo2MlKgMin = o.double("meanVo2MlKgMin"),
        meanHeartRateBpm = o.double("meanHeartRateBpm"),
        efficiencyFactor = o.double("efficiencyFactor"),
        seconds = o.int("seconds"),
    )

    private fun readDecoupling(o: Obj) = DecouplingAnalysis(
        mainBlockSeconds = o.int("mainBlockSeconds"),
        analysedSeconds = o.int("analysedSeconds"),
        ef1 = o.double("ef1"),
        ef2 = o.double("ef2"),
        decouplingPercent = o.double("decouplingPercent"),
        zone12Fraction = o.optDouble("zone12Fraction"),
        longSteadyZone2 = o.boolean("longSteadyZone2"),
    )

    private fun readFitnessIndex(o: Obj) = FitnessIndexAnalysis(
        vo2maxEstimate = o.double("vo2maxEstimate"),
        stretchCount = o.int("stretchCount"),
        stretchesFound = o.int("stretchesFound"),
        estimates = o.list("estimates").map { toDouble(it) },
        restingAssumed = o.boolean("restingAssumed"),
    )

    private fun readRecovery(o: Obj) = RecoveryAnalysis(
        peakHeartRateBpm = o.double("peakHeartRateBpm"),
        peakEndMillis = o.long("peakEndMillis"),
        drop60Bpm = o.optDouble("drop60Bpm"),
        drop120Bpm = o.optDouble("drop120Bpm"),
        context = o.enum("context", RecoveryContext.entries),
        meanVo2MlKgMin = o.double("meanVo2MlKgMin"),
    )

    private fun readAlpha1(o: Obj) = SessionAlpha1(
        totalWindows = o.int("totalWindows"),
        validWindows = o.int("validWindows"),
        secondsAtOrAbove = o.int("secondsAtOrAbove"),
        secondsBelow = o.int("secondsBelow"),
        meanAlpha1 = o.double("meanAlpha1"),
    )

    private fun readThresholdEstimates(o: Obj) = ThresholdEstimatesAnalysis(
        stretchesFound = o.int("stretchesFound"),
        estimates = o.objects("estimates").map { e ->
            WorkoutThresholdEstimate(
                kind = e.enum("kind", ThresholdKind.entries),
                startMillis = e.long("startMillis"),
                endMillis = e.long("endMillis"),
                speedKmh = e.double("speedKmh"),
                inclinePercent = e.double("inclinePercent"),
                cleanWindows = e.int("cleanWindows"),
                alpha1 = e.double("alpha1"),
                heartRateBpm = e.double("heartRateBpm"),
                slopePerBpm = e.double("slopePerBpm"),
                impliedBpm = e.double("impliedBpm"),
                weightSeconds = e.int("weightSeconds"),
            )
        },
    )

    private fun toInt(v: Any?): Int = (v as? Number)?.toInt() ?: throw IllegalArgumentException("not a number: $v")

    private fun toDouble(v: Any?): Double = (v as? Number)?.toDouble() ?: throw IllegalArgumentException("not a number: $v")

    /** Typed access to a parsed JSON object; every problem is an `IllegalArgumentException` that names the path. */
    private class Obj(private val map: Map<String, Any?>, private val path: String) {
        private fun at(key: String) = if (path.isEmpty()) key else "$path.$key"

        private fun fail(key: String, what: String): Nothing = throw IllegalArgumentException("${at(key)}: $what")

        fun has(key: String) = map[key] != null

        private fun required(key: String): Any = map[key] ?: fail(key, "is required")

        fun int(key: String): Int = (required(key) as? Number)?.toInt() ?: fail(key, "must be a number")

        fun long(key: String): Long = (required(key) as? Number)?.toLong() ?: fail(key, "must be a number")

        fun optInt(key: String): Int? = if (map[key] == null) null else int(key)

        fun double(key: String): Double = (required(key) as? Number)?.toDouble() ?: fail(key, "must be a number")

        fun optDouble(key: String): Double? = if (map[key] == null) null else double(key)

        fun boolean(key: String): Boolean = required(key) as? Boolean ?: fail(key, "must be true or false")

        fun obj(key: String): Obj {
            @Suppress("UNCHECKED_CAST")
            return Obj(required(key) as? Map<String, Any?> ?: fail(key, "must be an object"), at(key))
        }

        fun list(key: String): List<Any?> = required(key) as? List<*> ?: fail(key, "must be a list")

        fun objects(key: String): List<Obj> = list(key).mapIndexed { i, item ->
            @Suppress("UNCHECKED_CAST")
            Obj(item as? Map<String, Any?> ?: fail(key, "item $i must be an object"), "${at(key)}[$i]")
        }

        fun <E : Enum<E>> enum(key: String, values: List<E>): E {
            val name = required(key) as? String ?: fail(key, "must be a string")
            return values.firstOrNull { it.name == name } ?: fail(key, "unknown value '$name'")
        }
    }
}
