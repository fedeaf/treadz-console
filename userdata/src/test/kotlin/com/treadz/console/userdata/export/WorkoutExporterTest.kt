package com.treadz.console.userdata.export

import com.treadz.console.treadmill.BeltStatus
import com.treadz.console.userdata.T0
import com.treadz.console.userdata.WorkoutDetail
import com.treadz.console.userdata.WorkoutStats
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.io.IOException
import java.time.ZoneId
import java.time.ZoneOffset
import java.util.Locale

/** Pure generation (CSV and TCX) on the JVM: goldens, rules and locale independence. */
class WorkoutExporterTest {

    private val originalLocale = Locale.getDefault()

    @After fun restoreLocale() = Locale.setDefault(originalLocale)

    private val exporter = WorkoutExporter("0.1.0", InMemoryExportStorage(), ZoneOffset.UTC)

    private fun render(detail: WorkoutDetail, format: ExportFormat): RenderedExport =
        (exporter.render(detail, format) as ExportResult.Success).value

    private fun text(detail: WorkoutDetail, format: ExportFormat) = String(render(detail, format).content, Charsets.UTF_8)

    private fun golden(name: String): String =
        javaClass.getResourceAsStream("/export/$name")!!.use { String(it.readBytes(), Charsets.UTF_8) }

    /** Writes the actual output next to the build, to review it and update the golden file on purpose. */
    private fun assertGolden(name: String, actual: String) {
        File("build/golden-actual").apply { mkdirs() }.resolve(name).writeText(actual)
        assertEquals("golden $name", golden(name), actual)
    }

    // ---- Goldens ----

    @Test fun `csv matches the golden file`() = assertGolden("golden_workout.csv", text(goldenDetail(), ExportFormat.Csv))

    @Test fun `tcx matches the golden file`() = assertGolden("golden_workout.tcx", text(goldenDetail(), ExportFormat.Tcx))

    @Test fun `output does not depend on the default locale`() {
        val csv = golden("golden_workout.csv")
        val tcx = golden("golden_workout.tcx")
        for (locale in listOf(Locale("es", "ES"), Locale("de", "DE"), Locale("fr", "FR"), Locale("ar", "EG"), Locale("fa", "IR"), Locale("hi", "IN"))) {
            Locale.setDefault(locale)
            val e = WorkoutExporter("0.1.0", InMemoryExportStorage(), ZoneOffset.UTC)
            val d = goldenDetail()
            assertEquals("csv in $locale", csv, String((e.render(d, ExportFormat.Csv) as ExportResult.Success).value.content, Charsets.UTF_8))
            assertEquals("tcx in $locale", tcx, String((e.render(d, ExportFormat.Tcx) as ExportResult.Success).value.content, Charsets.UTF_8))
            assertEquals("treadz_workout_20261007_120000.csv", e.fileName(d.summary, ExportFormat.Csv))
        }
    }

    // ---- CSV ----

    @Test fun `csv format basics`() {
        val bytes = render(goldenDetail(), ExportFormat.Csv).content
        val text = String(bytes, Charsets.UTF_8)
        assertTrue("no BOM", bytes[0] == 's'.code.toByte())
        assertFalse("only \\n line endings", '\r' in text)
        assertTrue("ends with a line break", text.endsWith("\n"))
        val lines = text.trimEnd('\n').split('\n')
        assertEquals(
            "seq,timestamp,elapsed_s,speed_kmh,target_speed_kmh,incline_pct,distance_m,kcal,hr_bpm,belt_status,stale,elevation_gain_m",
            lines[0],
        )
        assertTrue(lines.drop(1).all { it.split(',').size == 12 })
        val seqs = lines.drop(1).map { it.substringBefore(',').toInt() }
        assertEquals(seqs.sorted(), seqs)
    }

    @Test fun `csv has one row per second after dropping repeated packets`() {
        val rows = text(goldenDetail(), ExportFormat.Csv).trimEnd('\n').split('\n').drop(1)
        assertEquals("stored: ${goldenDetail().samples.size} packets", 31, goldenDetail().samples.size)
        // 16 seconds, the two countdown seconds are identical and collapse into one row
        assertEquals(15, rows.size)
        // seq keeps the stored position: the second packet of every pair is gone
        assertEquals(listOf(0, 4, 6, 8, 10), rows.take(5).map { it.substringBefore(',').toInt() })
    }

    @Test fun `repeats are dropped only when every value is equal`() {
        val d = detail(
            listOf(
                Sec(BeltStatus.Running, 8.0, 0.0, 0, 1, packets = 1),
                Sec(BeltStatus.Running, 8.0, 0.0, 0, 1, packets = 1), // identical -> dropped
                Sec(BeltStatus.Running, 8.0, 0.0, 0, 1, hr = 100, packets = 1), // heart rate arrived -> kept
                Sec(BeltStatus.Running, 8.0, 0.0, 0, 1, hr = 100, packets = 1), // identical -> dropped
                Sec(BeltStatus.Paused, 8.0, 0.0, 0, 1, hr = 100, packets = 1), // status changed -> kept
            ),
        )
        assertEquals(listOf(0, 2, 4), text(d, ExportFormat.Csv).trimEnd('\n').split('\n').drop(1).map { it.substringBefore(',').toInt() })
    }

    @Test fun `rows are ordered by seq even if the samples come unordered`() {
        val d = goldenDetail()
        val shuffled = d.copy(samples = d.samples.reversed())
        assertEquals(text(d, ExportFormat.Csv), text(shuffled, ExportFormat.Csv))
    }

    @Test fun `heart rate null and zero are empty fields`() {
        val rows = text(goldenDetail(), ExportFormat.Csv).trimEnd('\n').split('\n').drop(1).map { it.split(',') }
        val hr = rows.map { it[8] }
        assertEquals(listOf("", "", "", "", "112"), hr.take(5)) // countdown, null, 0, 0, then a reading
    }

    @Test fun `stale samples are flagged in csv`() {
        val rows = text(goldenDetail(), ExportFormat.Csv).trimEnd('\n').split('\n').drop(1).map { it.split(',') }
        assertEquals(1, rows.count { it[10] == "true" })
        assertEquals("10", rows.single { it[10] == "true" }[2])
    }

    @Test fun `unknown target speed is an empty field`() {
        val rows = text(goldenDetail(), ExportFormat.Csv).trimEnd('\n').split('\n').drop(1).map { it.split(',') }
        assertEquals("", rows[0][4]) // Starting: no target
        assertEquals("8.0", rows[2][4])
    }

    @Test fun `belt status codes`() {
        val d = detail(
            BeltStatus.entries.map { Sec(it, 0.0, 0.0, 0, 0, packets = 1) }.mapIndexed { i, s -> s.copy(elapsed = i) },
        )
        val codes = text(d, ExportFormat.Csv).trimEnd('\n').split('\n').drop(1).map { it.split(',')[9] }
        assertEquals(listOf("idle", "starting", "running", "paused", "safety_key_removed"), codes)
    }

    // ---- Elevation ----

    private fun gains(d: WorkoutDetail) =
        text(d, ExportFormat.Csv).trimEnd('\n').split('\n').drop(1).map { it.split(',')[11] }

    @Test fun `climb is distance increment times incline`() {
        val d = detail(
            listOf(
                Sec(BeltStatus.Running, 8.0, 5.0, 0, 1, packets = 1),
                Sec(BeltStatus.Running, 8.0, 5.0, 100, 2, packets = 1), // 100 m at 5 % = 5 m
                Sec(BeltStatus.Running, 8.0, 2.0, 200, 3, packets = 1), // 100 m at the mean of 5 and 2 = 3.5 m
            ),
        )
        assertEquals(listOf("0.0", "5.0", "8.5"), gains(d))
    }

    @Test fun `no climb while the distance does not grow`() {
        val d = detail(
            listOf(
                Sec(BeltStatus.Running, 8.0, 10.0, 0, 1, packets = 1),
                Sec(BeltStatus.Paused, 0.0, 10.0, 0, 2, packets = 1),
                Sec(BeltStatus.Paused, 0.0, 10.0, 0, 3, packets = 1),
                Sec(BeltStatus.Running, 8.0, 10.0, 10, 4, packets = 1), // a counter reset would be ignored too
                Sec(BeltStatus.Running, 8.0, 10.0, 5, 5, packets = 1),
            ),
        )
        assertEquals(listOf("0.0", "0.0", "0.0", "1.0", "1.0"), gains(d))
    }

    @Test fun `negative incline lowers the altitude but not the gain`() {
        val d = detail(
            listOf(
                Sec(BeltStatus.Running, 8.0, 0.0, 0, 1, packets = 1),
                Sec(BeltStatus.Running, 8.0, 10.0, 100, 2, packets = 1), // +5 m (mean 5 %)
                Sec(BeltStatus.Running, 8.0, -10.0, 200, 3, packets = 1), // 0 % mean: flat
                Sec(BeltStatus.Running, 8.0, -10.0, 300, 4, packets = 1), // -10 m
            ),
        )
        assertEquals(listOf("0.0", "5.0", "5.0", "5.0"), gains(d))
        val altitudes = Regex("<AltitudeMeters>([^<]*)</AltitudeMeters>").findAll(text(d.copy(summary = d.summary), ExportFormat.Tcx))
            .map { it.groupValues[1] }.toList()
        assertEquals(listOf("0.0", "5.0", "5.0", "-5.0"), altitudes)
    }

    // ---- TCX rules ----

    private fun tcxDoc(d: WorkoutDetail) = TcxChecks.parse(render(d, ExportFormat.Tcx).content)

    @Test fun `tcx excludes stale samples`() {
        val trackpoints = TcxChecks.trackpoints(tcxDoc(goldenDetail()))
        assertEquals(13, trackpoints.size) // 16 seconds: 2 countdown and 1 stale excluded
        assertTrue(trackpoints.none { it.time == "2026-10-07T12:00:13.000Z" })
    }

    @Test fun `tcx drops the leading countdown but keeps the one after a pause`() {
        val trackpoints = TcxChecks.trackpoints(tcxDoc(goldenDetail()))
        assertEquals("2026-10-07T12:00:02.000Z", trackpoints.first().time)
        assertEquals(1, trackpoints.count { it.speed == 0.0 && it.distance == 11.0 && it.time == "2026-10-07T12:00:11.000Z" })
    }

    @Test fun `tcx keeps paused samples with zero speed and the clock running`() {
        val tp = TcxChecks.trackpoints(tcxDoc(goldenDetail()))
        val paused = tp.filter { it.time in setOf("2026-10-07T12:00:09.000Z", "2026-10-07T12:00:10.000Z") }
        assertEquals(2, paused.size)
        assertTrue(paused.all { it.speed == 0.0 && it.distance == 11.0 })
        val lap = TcxChecks.lap(tcxDoc(goldenDetail()))
        assertEquals("pauses count in the total time: first 12:00:02, last 12:00:15", 13.0, lap.totalTimeSeconds, 0.0)
    }

    @Test fun `tcx without heart rate has no heart rate elements`() {
        val d = detail(
            listOf(
                Sec(BeltStatus.Running, 8.0, 0.0, 0, 1, hr = null),
                Sec(BeltStatus.Running, 8.0, 0.0, 2, 2, hr = null),
                Sec(BeltStatus.Running, 8.0, 0.0, 4, 3, hr = 0),
            ),
            WorkoutStats(durationSeconds = 3, distanceMeters = 4, maxSpeedKmh = 8.0),
        )
        val xml = text(d, ExportFormat.Tcx)
        assertFalse("HeartRateBpm" in xml)
        assertFalse("AverageHeartRateBpm" in xml)
        assertFalse("MaximumHeartRateBpm" in xml)
        TcxChecks.assertSchemaOrder(TcxChecks.parse(xml.toByteArray()))
    }

    @Test fun `tcx omits an out of range heart rate`() {
        val d = detail(listOf(Sec(BeltStatus.Running, 8.0, 0.0, 0, 1, hr = 300, packets = 1)), WorkoutStats(maxHeartRateBpm = 300, averageHeartRateBpm = 300))
        assertFalse("HeartRateBpm" in text(d, ExportFormat.Tcx))
    }

    @Test fun `tcx lap totals come from the summary and the samples`() {
        val lap = TcxChecks.lap(tcxDoc(goldenDetail()))
        assertEquals("2026-10-07T12:00:02.000Z", lap.startTime)
        assertEquals(15.0, lap.distance, 0.0)
        assertEquals(8.0 / 3.6, lap.maxSpeed, 0.001)
        assertEquals(2, lap.calories)
        assertEquals(114, lap.avgHr)
        assertEquals(121, lap.maxHr)
    }

    @Test fun `tcx speed is metres per second`() {
        val tp = TcxChecks.trackpoints(tcxDoc(goldenDetail()))
        assertEquals(8.0 / 3.6, tp.first { it.time == "2026-10-07T12:00:04.000Z" }.speed, 0.001)
    }

    @Test fun `tcx trackpoint times strictly increase`() {
        val d = detail(listOf(Sec(BeltStatus.Running, 8.0, 0.0, 0, 1, packets = 1), Sec(BeltStatus.Running, 8.0, 0.0, 2, 2, packets = 1)))
        // two samples with the same timestamp: the second does not advance the clock
        val same = d.copy(samples = d.samples.map { it.copy(timestampMillis = T0) })
        val tp = TcxChecks.trackpoints(TcxChecks.parse(render(same, ExportFormat.Tcx).content))
        assertEquals(1, tp.size)
    }

    @Test fun `tcx is well formed with the right namespaces`() {
        val doc = tcxDoc(goldenDetail())
        assertEquals("http://www.garmin.com/xmlschemas/TrainingCenterDatabase/v2", doc.documentElement.namespaceURI)
        assertEquals("TrainingCenterDatabase", doc.documentElement.localName)
        assertEquals("Running", doc.getElementsByTagNameNS("*", "Activity").item(0).attributes.getNamedItem("Sport").nodeValue)
        val tpx = doc.getElementsByTagNameNS("http://www.garmin.com/xmlschemas/ActivityExtension/v2", "TPX")
        assertEquals(13, tpx.length)
    }

    @Test fun `tcx follows the schema element order`() {
        TcxChecks.assertSchemaOrder(tcxDoc(goldenDetail()))
    }

    @Test fun `tcx author has the app name and the parsed version`() {
        val xml = String(
            (WorkoutExporter("1.22.3-debug", InMemoryExportStorage(), ZoneOffset.UTC).render(goldenDetail(), ExportFormat.Tcx) as ExportResult.Success).value.content,
            Charsets.UTF_8,
        )
        assertTrue("<Name>TreadZ Console</Name>" in xml)
        assertTrue("<VersionMajor>1</VersionMajor>" in xml)
        assertTrue("<VersionMinor>22</VersionMinor>" in xml)
        assertTrue("<BuildMajor>3</BuildMajor>" in xml)
    }

    @Test fun `a hostile version string cannot break the xml`() {
        val e = WorkoutExporter("</Author><x a=\"&\">\u0000\uD800", InMemoryExportStorage(), ZoneOffset.UTC)
        val bytes = (e.render(goldenDetail(), ExportFormat.Tcx) as ExportResult.Success).value.content
        TcxChecks.assertSchemaOrder(TcxChecks.parse(bytes))
    }

    @Test fun `xml escaping`() {
        assertEquals("a&amp;b&lt;c&gt;d\"e", XmlWriter.escape("a&b<c>d\"e", attribute = false))
        assertEquals("a&quot;b&#10;c", XmlWriter.escape("a\"b\nc", attribute = true))
        assertEquals("ok", XmlWriter.escape("o\u0000k\u0001", attribute = false))
        assertEquals("\uD83C\uDFC3", XmlWriter.escape("\uD83C\uDFC3", attribute = false)) // valid surrogate pair kept
        assertEquals("", XmlWriter.escape("\uD83C", attribute = false)) // unpaired surrogate dropped
    }

    @Test fun `xml writer rejects unclosed elements`() {
        val w = XmlWriter(java.io.StringWriter())
        w.start("a")
        val failed = runCatching { w.finish() }.isFailure
        assertTrue(failed)
    }

    // ---- Refusals ----

    @Test fun `workout in progress is refused`() {
        val d = detail(GOLDEN_SECS, GOLDEN_STATS, ended = false)
        for (f in ExportFormat.entries) {
            assertEquals(ExportResult.Failure(ExportError.WorkoutInProgress), exporter.render(d, f))
        }
    }

    @Test fun `workout without samples is refused`() {
        val d = detail(emptyList())
        for (f in ExportFormat.entries) {
            assertEquals(ExportResult.Failure(ExportError.NoSamples), exporter.render(d, f))
        }
    }

    @Test fun `tcx of a workout with only stale or countdown samples is refused but csv is not`() {
        val stale = detail(listOf(Sec(BeltStatus.Running, 8.0, 0.0, 5, 3, stale = true)))
        assertEquals(ExportResult.Failure(ExportError.NoSamples), exporter.render(stale, ExportFormat.Tcx))
        assertTrue(exporter.render(stale, ExportFormat.Csv) is ExportResult.Success)
        val countdown = detail(listOf(Sec(BeltStatus.Starting, 0.0, 0.0, 0, 0)))
        assertEquals(ExportResult.Failure(ExportError.NoSamples), exporter.render(countdown, ExportFormat.Tcx))
    }

    @Test fun `an interrupted workout is exported as it was stored`() {
        val d = goldenDetail()
        val interrupted = d.copy(summary = d.summary.copy(endReason = com.treadz.console.userdata.WorkoutEndReason.Interrupted))
        assertEquals(text(d, ExportFormat.Csv), text(interrupted, ExportFormat.Csv))
    }

    // ---- File names and saving ----

    @Test fun `file names use the local start time and have no spaces`() {
        val madrid = WorkoutExporter("0.1.0", InMemoryExportStorage(), ZoneId.of("Europe/Madrid"))
        val s = goldenDetail().summary
        assertEquals("treadz_workout_20261007_140000.csv", madrid.fileName(s, ExportFormat.Csv))
        assertEquals("treadz_workout_20261007_140000.tcx", madrid.fileName(s, ExportFormat.Tcx))
        val tokyo = WorkoutExporter("0.1.0", InMemoryExportStorage(), ZoneId.of("Asia/Tokyo"))
        assertEquals("treadz_workout_20261007_210000.tcx", tokyo.fileName(s, ExportFormat.Tcx))
        assertEquals("treadz_workout_20261007_120000.csv", render(goldenDetail(), ExportFormat.Csv).fileName)
    }

    @Test fun `save writes the rendered bytes to the storage`() = runTest {
        val storage = InMemoryExportStorage()
        val e = WorkoutExporter("0.1.0", storage, ZoneOffset.UTC)
        val r = e.save(goldenDetail(), ExportFormat.Tcx) as ExportResult.Success
        assertEquals("treadz_workout_20261007_120000.tcx", r.value.fileName)
        assertEquals("Download/TreadZConsole/treadz_workout_20261007_120000.tcx", r.value.location)
        assertArrayEquals(render(goldenDetail(), ExportFormat.Tcx).content, storage.files.getValue(r.value.fileName))
    }

    @Test fun `save does not touch the storage when the workout cannot be exported`() = runTest {
        val storage = InMemoryExportStorage()
        val e = WorkoutExporter("0.1.0", storage, ZoneOffset.UTC)
        val r = e.save(detail(GOLDEN_SECS, GOLDEN_STATS, ended = false), ExportFormat.Csv)
        assertEquals(ExportResult.Failure(ExportError.WorkoutInProgress), r)
        assertTrue(storage.files.isEmpty())
    }

    @Test fun `storage failures are reported as a code`() = runTest {
        val failing = object : ExportStorage {
            override fun save(fileName: String, format: ExportFormat, content: ByteArray): SavedExport = throw IOException("disk full")
        }
        val r = WorkoutExporter("0.1.0", failing, ZoneOffset.UTC).save(goldenDetail(), ExportFormat.Csv)
        assertEquals(ExportResult.Failure(ExportError.StorageError, "disk full"), r)
    }

    @Test fun `mime types and extensions`() {
        assertEquals("text/csv", ExportFormat.Csv.mimeType)
        assertEquals("application/vnd.garmin.tcx+xml", ExportFormat.Tcx.mimeType)
        assertEquals(listOf("csv", "tcx"), ExportFormat.entries.map { it.extension })
    }
}
