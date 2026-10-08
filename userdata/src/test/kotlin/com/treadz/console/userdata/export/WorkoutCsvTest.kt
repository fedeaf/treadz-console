package com.treadz.console.userdata.export

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
        }
        // and writing what was read gives the same file, but for the sequence numbers (which are renumbered)
        fun withoutSeq(csv: String) = csv.lines().map { it.substringAfter(',') }
        assertEquals(withoutSeq(WorkoutCsv.write(samples)), withoutSeq(WorkoutCsv.write(read)))
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
