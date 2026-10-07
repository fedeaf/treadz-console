package com.treadz.console.userdata.export

import com.treadz.console.userdata.WorkoutStats
import java.io.Writer

/**
 * TCX (Training Center Database v2) export. The element order follows the schema, which is strict: Strava and
 * Garmin Connect reject files that break it.
 *
 * ```
 * TrainingCenterDatabase
 *   Activities / Activity[Sport=Running]
 *     Id, Lap[StartTime]
 *       TotalTimeSeconds, DistanceMeters, MaximumSpeed, Calories,
 *       AverageHeartRateBpm?, MaximumHeartRateBpm?, Intensity, TriggerMethod,
 *       Track / Trackpoint*: Time, AltitudeMeters, DistanceMeters, HeartRateBpm?, Extensions/TPX/Speed
 *   Author (Application_t)
 * ```
 */
internal object TcxWorkoutWriter {

    private const val NS = "http://www.garmin.com/xmlschemas/TrainingCenterDatabase/v2"
    private const val NS_XSI = "http://www.w3.org/2001/XMLSchema-instance"
    private const val NS_TPX = "http://www.garmin.com/xmlschemas/ActivityExtension/v2"
    private const val SCHEMA_LOCATION = "$NS http://www.garmin.com/xmlschemas/TrainingCenterDatabasev2.xsd"
    private const val APP_NAME = "TreadZ Console"

    /** HeartRateInBeatsPerMinute_t accepts 1..255. */
    private val VALID_HEART_RATE = 1..255
    private const val MAX_CALORIES = 65_535

    /** @param points current samples (no stale ones), at least one, ordered. */
    fun write(out: Writer, points: List<ExportPoint>, stats: WorkoutStats, appVersion: String) {
        // Timestamps must grow strictly; a point that does not advance the clock is dropped.
        val track = ArrayList<ExportPoint>(points.size)
        for (p in points) if (track.isEmpty() || p.sample.timestampMillis > track.last().sample.timestampMillis) track += p
        val first = track.first().sample
        val last = track.last().sample
        val startTime = Formats.timestamp(first.timestampMillis)
        val totalSeconds = (last.timestampMillis - first.timestampMillis) / 1000.0
        val distance = track.maxOf { it.sample.distanceMeters }

        val w = XmlWriter(out)
        w.declaration()
        w.start(
            "TrainingCenterDatabase",
            listOf(
                "xmlns" to NS,
                "xmlns:xsi" to NS_XSI,
                "xmlns:ns3" to NS_TPX,
                "xsi:schemaLocation" to SCHEMA_LOCATION,
            ),
        )
        w.start("Activities")
        w.start("Activity", listOf("Sport" to "Running"))
        w.element("Id", startTime)
        w.start("Lap", listOf("StartTime" to startTime))
        w.element("TotalTimeSeconds", Formats.decimal(totalSeconds, 3)!!)
        w.element("DistanceMeters", Formats.decimal(distance.toDouble(), 1)!!)
        w.element("MaximumSpeed", Formats.decimal(stats.maxSpeedKmh / 3.6, 3) ?: "0.0")
        w.element("Calories", stats.calories.coerceIn(0, MAX_CALORIES).toString())
        stats.averageHeartRateBpm?.takeIf { it in VALID_HEART_RATE }?.let { heartRate(w, "AverageHeartRateBpm", it) }
        stats.maxHeartRateBpm?.takeIf { it in VALID_HEART_RATE }?.let { heartRate(w, "MaximumHeartRateBpm", it) }
        w.element("Intensity", "Active")
        w.element("TriggerMethod", "Manual")
        w.start("Track")
        for (p in track) {
            val s = p.sample
            w.start("Trackpoint")
            w.element("Time", Formats.timestamp(s.timestampMillis))
            w.element("AltitudeMeters", Formats.decimal(p.altitudeMeters, 2) ?: "0.0")
            w.element("DistanceMeters", Formats.decimal(s.distanceMeters.toDouble(), 1)!!)
            s.heartRateBpm?.takeIf { it in VALID_HEART_RATE }?.let { heartRate(w, "HeartRateBpm", it) }
            w.start("Extensions")
            w.start("ns3:TPX")
            w.element("ns3:Speed", Formats.decimal(s.speedKmh / 3.6, 3) ?: "0.0")
            w.end() // TPX
            w.end() // Extensions
            w.end() // Trackpoint
        }
        w.end() // Track
        w.end() // Lap
        w.end() // Activity
        w.end() // Activities
        author(w, appVersion)
        w.end() // TrainingCenterDatabase
        w.finish()
    }

    private fun heartRate(w: XmlWriter, name: String, bpm: Int) {
        w.start(name)
        w.element("Value", bpm.toString())
        w.end()
    }

    private fun author(w: XmlWriter, appVersion: String) {
        val numbers = Regex("""\d+""").findAll(appVersion).map { it.value.toIntOrNull()?.coerceIn(0, 65_535) ?: 0 }.toList()
        fun part(i: Int) = (numbers.getOrNull(i) ?: 0).toString()
        w.start("Author", listOf("xsi:type" to "Application_t"))
        w.element("Name", APP_NAME)
        w.start("Build")
        w.start("Version")
        w.element("VersionMajor", part(0))
        w.element("VersionMinor", part(1))
        w.element("BuildMajor", part(2))
        w.element("BuildMinor", "0")
        w.end() // Version
        w.end() // Build
        w.element("LangID", "en")
        w.element("PartNumber", "000-00000-00")
        w.end() // Author
    }
}
