package com.treadz.console.sync.engine

import com.treadz.console.userdata.sync.PendingChanges
import com.treadz.console.userdata.sync.ProgramDocument
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/** Readable commit messages, e.g. `Fede · workout 2026-10-08 00:40 · 45 min · 5.2 km`. */
internal object CommitMessages {
    private val MINUTE = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.ROOT)
    private val DAY = DateTimeFormatter.ofPattern("yyyy-MM-dd", Locale.ROOT)
    private const val SEP = " · "
    private const val UNNAMED = "User"

    /** [names]: profile name by user uuid. */
    fun build(changes: PendingChanges, programs: List<ProgramDocument>, names: Map<String, String>, zone: ZoneId): String {
        fun name(uuid: String) = names[uuid]?.takeIf { it.isNotBlank() } ?: UNNAMED
        val single = changes.count + programs.size == 1
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
            changes.profiles.firstOrNull()?.let { p ->
                return listOf(name(p.uuid), if (p.deleted) "profile deleted" else "profile").joinToString(SEP)
            }
            programs.firstOrNull()?.let { return listOf(name(it.userUuid), if (it.deleted) "program deleted" else "program").joinToString(SEP) }
        }
        val parts = buildList {
            count(changes.workouts.size, "workout")?.let(::add)
            count(changes.weights.size, "weight entry", "weight entries")?.let(::add)
            count(changes.profiles.size, "profile")?.let(::add)
            count(programs.size, "program")?.let(::add)
        }
        val users = (changes.profiles.map { it.uuid } + changes.weights.map { it.userUuid } + changes.workouts.map { it.userUuid } +
            programs.map { it.userUuid }).distinct().map(::name).sorted()
        val who = if (users.isEmpty()) "" else users.joinToString(", ", prefix = SEP)
        return "Sync$SEP${parts.joinToString(", ")}$who"
    }

    private fun count(n: Int, one: String, many: String = one + "s"): String? = when (n) {
        0 -> null
        1 -> "1 $one"
        else -> "$n $many"
    }

    private fun duration(seconds: Int): String = if (seconds < 60) "$seconds s" else "${(seconds + 30) / 60} min"
}
