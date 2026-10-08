package com.treadz.console.userdata.export

import com.treadz.console.treadmill.BeltStatus
import com.treadz.console.userdata.WorkoutSample
import java.time.Instant
import java.time.format.DateTimeParseException

/**
 * The per-second CSV of a workout, the same file that [WorkoutExporter] exports (see its documentation for the
 * columns), as text in memory: [write] produces it and [read] turns it back into samples.
 *
 * The file keeps one row per distinct sample (repeated packets are collapsed), with values rounded as exported (speed 2
 * decimals, incline 1). [read] therefore returns the collapsed series, renumbered from 0 without gaps; the derived
 * `elevation_gain_m` column is ignored. A heart rate of 0 was exported as "no reading".
 */
object WorkoutCsv {

    /** The CSV of [samples] (any order; stale samples included and flagged). Header only if there are none. */
    fun write(samples: List<WorkoutSample>): String =
        CsvWorkoutWriter.write(ExportPoints.withElevation(ExportPoints.collapseRepeats(samples)))

    /**
     * Parses a file written by [write]. Rows that cannot be understood (missing or malformed required fields) are
     * skipped, so a damaged line does not lose the rest of the workout.
     */
    fun read(csv: String): List<WorkoutSample> {
        val rows = parseRows(csv)
        if (rows.isEmpty()) return emptyList()
        val header = rows.first().withIndex().associate { (i, name) -> name.trim() to i }
        fun col(row: List<String>, name: String): String? = header[name]?.let { row.getOrNull(it) }?.takeIf { it.isNotEmpty() }
        val samples = ArrayList<WorkoutSample>()
        for (row in rows.drop(1)) {
            val sample = runCatching {
                WorkoutSample(
                    seq = samples.size,
                    timestampMillis = Instant.parse(col(row, "timestamp")!!).toEpochMilli(),
                    elapsedSeconds = col(row, "elapsed_s")!!.toInt(),
                    speedKmh = col(row, "speed_kmh")!!.toDouble(),
                    targetSpeedKmh = col(row, "target_speed_kmh")?.toDouble(),
                    inclinePercent = col(row, "incline_pct")!!.toDouble(),
                    distanceMeters = col(row, "distance_m")!!.toInt(),
                    calories = col(row, "kcal")!!.toInt(),
                    heartRateBpm = col(row, "hr_bpm")?.toInt(),
                    beltStatus = beltStatus(col(row, "belt_status")!!),
                    isStale = col(row, "stale") == "true",
                )
            }
            sample.getOrNull()?.let { samples += it }
        }
        return samples
    }

    private fun beltStatus(code: String): BeltStatus =
        BeltStatus.entries.firstOrNull { CsvWorkoutWriter.statusCode(it) == code } ?: throw DateTimeParseException("belt status", code, 0)

    /** RFC 4180 reader: quoted fields, doubled quotes, `\n` or `\r\n`. Blank lines are ignored. */
    private fun parseRows(text: String): List<List<String>> {
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
