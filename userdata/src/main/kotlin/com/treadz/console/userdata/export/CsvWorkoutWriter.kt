package com.treadz.console.userdata.export

import com.treadz.console.programs.fitness.HeartRateSourceKind
import com.treadz.console.treadmill.BeltStatus

/** CSV export: see [WorkoutExporter] for the column reference. */
internal object CsvWorkoutWriter {

    val COLUMNS = listOf(
        "seq", "timestamp", "elapsed_s", "speed_kmh", "target_speed_kmh", "incline_pct", "distance_m", "kcal",
        "hr_bpm", "belt_status", "stale", "elevation_gain_m", "hr_source", "rr_ms",
    )

    /** Separator of the RR intervals inside the single `rr_ms` cell: it is not special in CSV, so the cell is never quoted. */
    const val RR_SEPARATOR = ';'

    fun write(points: List<ExportPoint>): String {
        val out = StringBuilder()
        appendRow(out, COLUMNS)
        for (p in points) {
            val s = p.sample
            appendRow(
                out,
                listOf(
                    Formats.integer(s.seq),
                    Formats.timestamp(s.timestampMillis),
                    Formats.integer(s.elapsedSeconds),
                    Formats.decimal(s.speedKmh, 2).orEmpty(),
                    s.targetSpeedKmh?.let { Formats.decimal(it, 2) }.orEmpty(),
                    Formats.decimal(s.inclinePercent, 1).orEmpty(),
                    Formats.integer(s.distanceMeters),
                    Formats.integer(s.calories),
                    s.heartRateBpm?.takeIf { it > 0 }?.let(Formats::integer).orEmpty(),
                    statusCode(s.beltStatus),
                    if (s.isStale) "true" else "false",
                    Formats.decimal(p.elevationGainMeters, 2).orEmpty(),
                    s.heartRateSource?.let(::sourceCode).orEmpty(),
                    s.rrIntervalsMs.joinToString(RR_SEPARATOR.toString()),
                ),
            )
        }
        return out.toString()
    }

    fun sourceCode(source: HeartRateSourceKind): String = when (source) {
        HeartRateSourceKind.DirectStrap -> "direct_strap"
        HeartRateSourceKind.TreadmillConsole -> "treadmill_console"
    }

    fun statusCode(status: BeltStatus): String = when (status) {
        BeltStatus.Idle -> "idle"
        BeltStatus.Starting -> "starting"
        BeltStatus.Running -> "running"
        BeltStatus.Paused -> "paused"
        BeltStatus.SafetyKeyRemoved -> "safety_key_removed"
    }

    private fun appendRow(out: StringBuilder, fields: List<String>) {
        fields.forEachIndexed { i, f ->
            if (i > 0) out.append(',')
            out.append(quote(f))
        }
        out.append('\n')
    }

    /** RFC 4180: quote only when the field has a comma, a quote or a line break. */
    private fun quote(field: String): String =
        if (field.any { it == ',' || it == '"' || it == '\n' || it == '\r' }) "\"" + field.replace("\"", "\"\"") + "\"" else field
}
