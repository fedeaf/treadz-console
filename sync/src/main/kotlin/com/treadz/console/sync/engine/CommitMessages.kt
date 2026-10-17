package com.treadz.console.sync.engine

import com.treadz.console.programs.fitness.FitnessTestJson
import com.treadz.console.programs.fitness.FitnessTestType
import com.treadz.console.programs.fitness.Validity
import com.treadz.console.programs.json.ProgramJson
import com.treadz.console.userdata.sync.FitnessTestDocument
import com.treadz.console.userdata.sync.PendingChanges
import com.treadz.console.userdata.sync.ProgramDocument
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Readable commit messages, e.g. `Fede · workout 2026-10-08 00:40 · 45 min · 5.2 km`,
 * `Fede · program "Norwegian 4x4"` or `Fede · fitness test 2026-10-08 10:00 · Ebbeling walk · VO2max 41.0`.
 */
internal object CommitMessages {
    private val MINUTE = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.ROOT)
    private val DAY = DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.ROOT)
    private const val SEP = " · "
    private const val UNNAMED = "User"

    /** [names]: profile name by user uuid. */
    fun build(
        changes: PendingChanges,
        programs: List<ProgramDocument>,
        fitnessTests: List<FitnessTestDocument>,
        names: Map<String, String>,
        zone: ZoneId,
    ): String {
        fun name(uuid: String) = names[uuid]?.takeIf { it.isNotBlank() } ?: UNNAMED
        val single = changes.count + programs.size + fitnessTests.size == 1
        if (single) {
            changes.workouts.firstOrNull()?.let { w ->
                val start = Instant.ofEpochMilli(w.startedAtMillis).atZone(zone)
                return if (w.deleted) {
                    listOf(name(w.userUuid), "workout deleted ${MINUTE.format(start)}").joinToString(SEP)
                } else {
                    listOf(
                        name(w.userUuid),
                        "workout ${MINUTE.format(start)}",
                        duration(w.stats.durationSeconds),
                        "%.1f km".format(Locale.ROOT, w.stats.distanceMeters / 1000.0),
                    ).joinToString(SEP)
                }
            }
            changes.weights.firstOrNull()?.let { e ->
                return if (e.deleted) {
                    listOf(name(e.userUuid), "weight entry deleted").joinToString(SEP)
                } else {
                    listOf(name(e.userUuid), "weight ${DAY.format(e.date)}", "%.1f kg".format(Locale.ROOT, e.weightKg)).joinToString(SEP)
                }
            }
            changes.restingHeartRates.firstOrNull()?.let { m ->
                return if (m.deleted) {
                    listOf(name(m.userUuid), "resting heart rate deleted").joinToString(SEP)
                } else {
                    listOf(
                        name(m.userUuid),
                        "resting heart rate ${DAY.format(Instant.ofEpochMilli(m.measuredAtMillis).atZone(zone))}",
                        "${m.bpm} bpm",
                    ).joinToString(SEP)
                }
            }
            changes.profiles.firstOrNull()?.let { p ->
                return listOf(name(p.uuid), if (p.deleted) "profile deleted" else "profile").joinToString(SEP)
            }
            programs.firstOrNull()?.let { return program(it, name(it.userUuid)) }
            fitnessTests.firstOrNull()?.let { return fitnessTest(it, name(it.userUuid), zone) }
        }
        val parts = buildList {
            count(changes.workouts.size, "workout")?.let(::add)
            count(changes.weights.size, "weight entry", "weight entries")?.let(::add)
            count(changes.restingHeartRates.size, "resting heart rate")?.let(::add)
            count(changes.profiles.size, "profile")?.let(::add)
            count(programs.size, "program")?.let(::add)
            count(fitnessTests.size, "fitness test")?.let(::add)
        }
        val users = (changes.profiles.map { it.uuid } + changes.weights.map { it.userUuid } + changes.workouts.map { it.userUuid } +
            changes.restingHeartRates.map { it.userUuid } + programs.map { it.userUuid } + fitnessTests.map { it.userUuid }).distinct().map(::name).sorted()
        val who = if (users.isEmpty()) "" else users.joinToString(", ", prefix = SEP)
        return "Sync$SEP${parts.joinToString(", ")}$who"
    }

    private fun program(doc: ProgramDocument, owner: String): String {
        if (doc.deleted) return listOf(owner, "program deleted").joinToString(SEP)
        val title = runCatching { ProgramJson.decode(doc.json, validate = false).name }.getOrNull()
        return listOf(owner, if (title.isNullOrBlank()) "program" else "program \"$title\"").joinToString(SEP)
    }

    private fun fitnessTest(doc: FitnessTestDocument, owner: String, zone: ZoneId): String {
        if (doc.deleted) return listOf(owner, "fitness test deleted").joinToString(SEP)
        val result = runCatching { FitnessTestJson.decode(doc.json) }.getOrNull() ?: return listOf(owner, "fitness test").joinToString(SEP)
        val parts = mutableListOf(owner, "fitness test ${MINUTE.format(Instant.ofEpochMilli(result.startedAtMillis).atZone(zone))}")
        parts += when (result.testType) {
            FitnessTestType.EbbelingWalk -> "Ebbeling walk"
            FitnessTestType.MaxIncrementalRun -> "max incremental run"
            FitnessTestType.MaxHeartRateWalk -> "max heart rate walk"
        }
        val outputs = result.outputs
        if (result.validity != Validity.Valid) {
            parts += "invalid"
        } else if (outputs == null) {
            // The maximal heart-rate walk gives no VO2max: only the maximum heart rate.
            parts += result.measuredHrMaxCandidateBpm?.let { "HRmax $it" } ?: "invalid"
        } else {
            parts += "VO2max %.1f".format(Locale.ROOT, outputs.vo2maxMlKgMin)
            outputs.masKmh?.let { parts += (if (outputs.masIsEstimated) "estimated MAS" else "MAS") + " %.1f km/h".format(Locale.ROOT, it) }
        }
        result.thresholds?.let { t ->
            t.vt1?.let { parts += "VT1 %.0f bpm".format(Locale.ROOT, it.heartRateBpm) }
            t.vt2?.let { parts += "VT2 %.0f bpm".format(Locale.ROOT, it.heartRateBpm) }
        }
        return parts.joinToString(SEP)
    }

    private fun count(n: Int, one: String, many: String = one + "s"): String? = when (n) {
        0 -> null
        1 -> "1 $one"
        else -> "$n $many"
    }

    private fun duration(seconds: Int): String = if (seconds < 60) "$seconds s" else "${(seconds + 30) / 60} min"
}
