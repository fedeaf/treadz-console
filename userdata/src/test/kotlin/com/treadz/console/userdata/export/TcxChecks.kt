package com.treadz.console.userdata.export

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.w3c.dom.Document
import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Structural checks of a TCX file: it is parsed back with a namespace-aware DOM parser, and the children of every
 * element are checked against the sequences of the Training Center Database v2 schema (subset used by the export).
 * The official XSD is not available offline, so the sequences are written down here from the schema.
 */
internal object TcxChecks {

    const val NS = "http://www.garmin.com/xmlschemas/TrainingCenterDatabase/v2"
    const val NS_TPX = "http://www.garmin.com/xmlschemas/ActivityExtension/v2"

    class Lap(val startTime: String, val totalTimeSeconds: Double, val distance: Double, val maxSpeed: Double, val calories: Int, val avgHr: Int?, val maxHr: Int?)
    class Trackpoint(val time: String, val altitude: Double, val distance: Double, val hr: Int?, val speed: Double)

    fun parse(bytes: ByteArray): Document = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        .newDocumentBuilder().parse(ByteArrayInputStream(bytes))

    fun parse(text: String): Document = parse(text.toByteArray(Charsets.UTF_8))

    /** Allowed child sequence of each element, in schema order. */
    private val SEQUENCES = mapOf(
        "TrainingCenterDatabase" to listOf("Folders", "Activities", "Workouts", "Courses", "Author", "Extensions"),
        "Activities" to listOf("Activity", "MultiSportSession"),
        "Activity" to listOf("Id", "Lap", "Notes", "Training", "Creator", "Extensions"),
        "Lap" to listOf(
            "TotalTimeSeconds", "DistanceMeters", "MaximumSpeed", "Calories", "AverageHeartRateBpm", "MaximumHeartRateBpm",
            "Intensity", "Cadence", "TriggerMethod", "Track", "Notes", "Extensions",
        ),
        "Track" to listOf("Trackpoint"),
        "Trackpoint" to listOf("Time", "Position", "AltitudeMeters", "DistanceMeters", "HeartRateBpm", "Cadence", "SensorState", "Extensions"),
        "AverageHeartRateBpm" to listOf("Value"),
        "MaximumHeartRateBpm" to listOf("Value"),
        "HeartRateBpm" to listOf("Value"),
        "Extensions" to listOf("TPX"),
        "TPX" to listOf("Speed", "RunCadence", "Watts", "CadenceSensor"),
        "Author" to listOf("Name", "Build", "LangID", "PartNumber"),
        "Build" to listOf("Version", "Type", "Time", "Builder"),
        "Version" to listOf("VersionMajor", "VersionMinor", "BuildMajor", "BuildMinor"),
    )

    private val REQUIRED = mapOf(
        "Activity" to listOf("Id", "Lap"),
        "Lap" to listOf("TotalTimeSeconds", "DistanceMeters", "Calories", "Intensity", "TriggerMethod"),
        "Trackpoint" to listOf("Time"),
        "Author" to listOf("Name", "Build", "LangID", "PartNumber"),
        "Version" to listOf("VersionMajor", "VersionMinor"),
        "HeartRateBpm" to listOf("Value"),
    )

    fun assertSchemaOrder(doc: Document) {
        assertEquals(NS, doc.documentElement.namespaceURI)
        check(doc.documentElement)
    }

    private fun children(e: Element): List<Element> =
        (0 until e.childNodes.length).map { e.childNodes.item(it) }.filterIsInstance<Element>()

    private fun check(e: Element) {
        val name = e.localName
        val kids = children(e)
        SEQUENCES[name]?.let { order ->
            val indexes = kids.map { k ->
                val i = order.indexOf(k.localName)
                assertTrue("<${k.localName}> is not allowed in <$name>", i >= 0)
                i
            }
            assertEquals("children of <$name> out of schema order: ${kids.map { it.localName }}", indexes.sorted(), indexes)
        }
        REQUIRED[name]?.forEach { r -> assertTrue("<$name> misses <$r>", kids.any { it.localName == r }) }
        // single-occurrence elements
        if (name != "Track" && name != "Activities" && name != "Activity") {
            val dup = kids.groupBy { it.localName }.filter { (n, v) -> v.size > 1 && !(name == "Activity" && n == "Lap") }
            assertTrue("repeated children in <$name>: ${dup.keys}", dup.isEmpty())
        }
        // Intensity and TriggerMethod enumerations
        if (name == "Intensity") assertEquals("Active", e.textContent)
        if (name == "TriggerMethod") assertEquals("Manual", e.textContent)
        if (name == "Value") assertTrue("heart rate 1..255", e.textContent.toInt() in 1..255)
        if (name == "Time" || name == "Id") assertTrue(Regex("""\d{4}-\d\d-\d\dT\d\d:\d\d:\d\d(\.\d+)?Z""").matches(e.textContent))
        kids.forEach(::check)
        // timestamps strictly increase along the track
        if (name == "Track") {
            val times = kids.map { children(it).first { c -> c.localName == "Time" }.textContent }
            assertEquals("track times strictly increase", times.sorted().distinct(), times)
        }
    }

    fun lap(doc: Document): Lap {
        val lap = doc.getElementsByTagNameNS(NS, "Lap").item(0) as Element
        fun child(n: String) = children(lap).firstOrNull { it.localName == n }
        fun hr(n: String) = child(n)?.let { children(it).single().textContent.toInt() }
        return Lap(
            lap.getAttribute("StartTime"),
            child("TotalTimeSeconds")!!.textContent.toDouble(),
            child("DistanceMeters")!!.textContent.toDouble(),
            child("MaximumSpeed")!!.textContent.toDouble(),
            child("Calories")!!.textContent.toInt(),
            hr("AverageHeartRateBpm"),
            hr("MaximumHeartRateBpm"),
        )
    }

    fun trackpoints(doc: Document): List<Trackpoint> {
        val list = doc.getElementsByTagNameNS(NS, "Trackpoint")
        return (0 until list.length).map { i ->
            val t = list.item(i) as Element
            fun child(n: String) = children(t).firstOrNull { it.localName == n }
            val speed = (t.getElementsByTagNameNS(NS_TPX, "Speed").item(0) as Element).textContent.toDouble()
            Trackpoint(
                child("Time")!!.textContent,
                child("AltitudeMeters")!!.textContent.toDouble(),
                child("DistanceMeters")!!.textContent.toDouble(),
                child("HeartRateBpm")?.let { children(it).single().textContent.toInt() },
                speed,
            )
        }
    }
}
