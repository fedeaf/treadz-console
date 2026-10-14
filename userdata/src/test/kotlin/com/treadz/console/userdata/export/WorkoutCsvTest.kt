package com.treadz.console.userdata.export

import com.treadz.console.programs.fitness.HeartRateSourceKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class WorkoutCsvTest {

    @Test fun `write produces the same CSV as the exporter`() {
        val detail = goldenDetail()
        val exported = WorkoutExporter("0.1.0", InMemoryExportStorage()).render(detail, ExportFormat.Csv)
        val expected = String((exported as ExportResult.Success).value.content, Charsets.UTF_8)
        assertEquals(expected, WorkoutCsv.write(detail.samples))
    }

    @Test fun `read gives back the collapsed samples renumbered from zero`() {
        val samples = goldenDetail().samples
        val read = WorkoutCsv.read(WorkoutCsv.write(samples))
        val collapsed = ExportPoints.collapseRepeats(samples)
        assertEquals(collapsed.size, read.size)
        assertEquals(read.indices.toList(), read.map { it.seq })
        for ((a, b) in collapsed.zip(read)) {
            assertEquals(a.timestampMillis, b.timestampMillis)
            assertEquals(a.elapsedSeconds, b.elapsedSeconds)
            assertEquals(a.speedKmh, b.speedKmh, 0.0)
            assertEquals(a.targetSpeedKmh, b.targetSpeedKmh)
            assertEquals(a.inclinePercent, b.inclinePercent, 0.0)
            assertEquals(a.distanceMeters, b.distanceMeters)
            assertEquals(a.calories, b.calories)
            assertEquals(a.heartRateBpm?.takeIf { it > 0 }, b.heartRateBpm)
            assertEquals(a.beltStatus, b.beltStatus)
            assertEquals(a.isStale, b.isStale)
            assertEquals(a.heartRateSource, b.heartRateSource)
            assertEquals(a.rrIntervalsMs, b.rrIntervalsMs)
        }
        // and writing what was read gives the same file, but for the sequence numbers (which are renumbered)
        fun withoutSeq(csv: String) = csv.lines().map { it.substringAfter(',') }
        assertEquals(withoutSeq(WorkoutCsv.write(samples)), withoutSeq(WorkoutCsv.write(read)))
    }

    @Test fun `the RR intervals of the repeated packets are merged into the row that is kept, none lost or repeated`() {
        val samples = goldenDetail().samples
        val all = samples.flatMap { it.rrIntervalsMs }
        assertEquals(listOf(540, 531, 528, 520, 510, 505), all)
        val read = WorkoutCsv.read(WorkoutCsv.write(samples))
        assertEquals(all, read.flatMap { it.rrIntervalsMs })
        val withRr = read.filter { it.rrIntervalsMs.isNotEmpty() }
        assertEquals(listOf(listOf(540, 531, 528), listOf(520), listOf(510, 505)), withRr.map { it.rrIntervalsMs })
        assertTrue(withRr.all { it.heartRateSource == HeartRateSourceKind.DirectStrap })
        // one cell, not quoted: the separator is not a CSV one
        val row = WorkoutCsv.write(samples).lines().first { ",540;531;528" in it }
        assertTrue(row, row.endsWith(",direct_strap,540;531;528"))
    }

    @Test fun `a file written before the source and RR columns existed still reads`() {
        val old = "seq,timestamp,elapsed_s,speed_kmh,target_speed_kmh,incline_pct,distance_m,kcal,hr_bpm,belt_status,stale,elevation_gain_m\n" +
            "0,2026-10-07T12:00:00.000Z,1,8.0,8.0,0.0,2,0,120,running,false,0.0\n"
        val read = WorkoutCsv.read(old).single()
        assertEquals(120, read.heartRateBpm)
        assertEquals(null, read.heartRateSource)
        assertEquals(emptyList<Int>(), read.rrIntervalsMs)
    }

    @Test fun `unknown source codes and damaged intervals are tolerated`() {
        val csv = "seq,timestamp,elapsed_s,speed_kmh,target_speed_kmh,incline_pct,distance_m,kcal,hr_bpm,belt_status,stale,elevation_gain_m,hr_source,rr_ms,future\n" +
            "0,2026-10-07T12:00:00.000Z,1,8.0,8.0,0.0,2,0,120,running,false,0.0,watch,500;x;510,1\n"
        val read = WorkoutCsv.read(csv).single()
        assertEquals(null, read.heartRateSource)
        assertEquals(listOf(500, 510), read.rrIntervalsMs)
    }

    @Test fun `a damaged row is skipped and the rest is kept`() {
        val csv = WorkoutCsv.write(goldenDetail().samples)
        val lines = csv.trimEnd().lines().toMutableList()
        lines[3] = "garbage,line"
        val read = WorkoutCsv.read(lines.joinToString("\n") + "\n")
        assertEquals(WorkoutCsv.read(csv).size - 1, read.size)
    }

    @Test fun `an empty or header only file has no samples`() {
        assertTrue(WorkoutCsv.read("").isEmpty())
        assertTrue(WorkoutCsv.read(WorkoutCsv.write(emptyList())).isEmpty())
    }
}
