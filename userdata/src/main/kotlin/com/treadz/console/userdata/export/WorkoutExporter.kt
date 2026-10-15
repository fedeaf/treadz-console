package com.treadz.console.userdata.export

import android.content.Context
import com.treadz.console.userdata.WorkoutDetail
import com.treadz.console.userdata.WorkoutSummary
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.io.OutputStreamWriter
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

/**
 * Exports a stored workout ([WorkoutDetail], from `WorkoutHistoryRepository.detail`) to a CSV or TCX file.
 *
 * ## Use from the UI
 * ```
 * val exporter = WorkoutExporter.create(context, appVersion = BuildConfig.VERSION_NAME)
 * when (val r = exporter.save(detail, ExportFormat.Tcx)) {
 *     is ExportResult.Success -> share(r.value.uri, r.value.format.mimeType)  // r.value.location for a message
 *     is ExportResult.Failure -> showError(r.error)                           // stable code, the UI translates it
 * }
 * ```
 * [save] writes into `Download/TreadZ/` and returns the content Uri. To only generate the bytes (tests, custom
 * destination) use [render], which is pure and runs on the JVM.
 *
 * ## Rules (both formats)
 * - **In progress**: a workout that has not ended is refused ([ExportError.WorkoutInProgress]); export it once it ends.
 * - **Repeated samples**: the treadmill sends ~2 packets per second with repeated values, and the history keeps what
 *   arrives. A sample identical to the previous one (all values, not the timestamp) is dropped from the export, so the
 *   file has ~1 row per second. Nothing else is dropped or merged.
 * - **No samples**: refused ([ExportError.NoSamples]); no empty files.
 * - **Pauses**: the treadmill's own time keeps counting while the belt is paused, and so does the export: samples keep
 *   coming with speed 0 and the distance still. The paused time is part of the duration.
 * - **Elevation**: the treadmill does not report it, it is computed. Between two samples the climb is
 *   `distance increment x mean incline / 100` meters (never negative increments of distance). The altitude starts at 0.
 * - **RR intervals**: never dropped with a repeated sample (see above).
 * - **Heart rate**: a missing reading (`null`) or 0 means "no reading" and is left empty / omitted.
 * - Numbers use a dot decimal separator and ASCII digits whatever the device language; time is UTC.
 *
 * ## CSV
 * UTF-8, comma separated, `\n` line endings (also after the last row), one header row and one row per sample ordered
 * by `seq`. Empty field = no value. Stale samples are included and flagged.
 *
 * | column | content |
 * |---|---|
 * | `seq` | sample position in the stored workout (can skip numbers where repeats were dropped) |
 * | `timestamp` | ISO-8601 UTC with milliseconds, e.g. `2026-10-07T12:00:05.000Z` |
 * | `elapsed_s` | treadmill session time in seconds (keeps counting during a pause) |
 * | `speed_kmh`, `target_speed_kmh` | real belt speed; requested speed (empty if unknown) |
 * | `incline_pct` | incline in percent |
 * | `distance_m`, `kcal` | accumulated distance and energy as reported by the treadmill |
 * | `hr_bpm` | heart rate, empty without a monitor |
 * | `belt_status` | `idle`, `starting`, `running`, `paused` or `safety_key_removed` |
 * | `stale` | `true` if the values were not current (frozen data), else `false` |
 * | `elevation_gain_m` | cumulative climb computed from incline and distance |
 * | `hr_source` | where `hr_bpm` came from: `direct_strap` (strap connected to the app), `treadmill_console`, or empty |
 * | `rr_ms` | RR intervals (ms between beats) received since the previous row, separated by `;`; empty without a strap. A repeated sample that is dropped passes its intervals to the row that is kept |
 *
 * ## TCX
 * An indoor run: `Activity Sport="Running"` with one `Lap` (`Intensity` Active, `TriggerMethod` Manual) and one
 * `Trackpoint` per sample with time, altitude, distance, heart rate (only when present) and the speed in m/s
 * (ActivityExtension v2 `TPX/Speed`). There is no GPS position. An `Author` element names the app and version.
 * - **Stale samples are excluded**: their values are the last known ones replayed, not measurements.
 * - **The start countdown is excluded**: leading `starting` samples with the belt still are dropped, so the activity
 *   starts when the belt does. `Id`/`StartTime` are the time of the first trackpoint.
 * - `TotalTimeSeconds` is last minus first trackpoint time (pauses included); `DistanceMeters` the final distance;
 *   `MaximumSpeed`, `Calories` and the average/maximum heart rate come from the workout summary.
 * - Trackpoints are strictly ordered in time; one that does not advance the clock is dropped.
 * - There is a single lap: Strava and Garmin work out stopped time from the zero speed of the paused samples.
 *
 * File names: `treadz_workout_<yyyyMMdd_HHmmss>.csv` / `.tcx`, from the workout start in the local [zone]; never
 * spaces.
 *
 * @param appVersion version name of the app (e.g. `0.1.0`), written in the TCX `Author` element.
 * @param storage destination of [save].
 */
class WorkoutExporter(
    private val appVersion: String,
    private val storage: ExportStorage,
    private val zone: ZoneId = ZoneId.systemDefault(),
) {

    /** Generates the file in memory. Pure: no I/O, independent of the device locale. */
    fun render(detail: WorkoutDetail, format: ExportFormat): ExportResult<RenderedExport> {
        val summary = detail.summary
        if (summary.isInProgress) return ExportResult.Failure(ExportError.WorkoutInProgress)
        val collapsed = ExportPoints.collapseRepeats(detail.samples)
        val all = ExportPoints.withElevation(collapsed)
        val bytes = when (format) {
            ExportFormat.Csv -> {
                if (all.isEmpty()) return ExportResult.Failure(ExportError.NoSamples)
                CsvWorkoutWriter.write(all).toByteArray(Charsets.UTF_8)
            }

            ExportFormat.Tcx -> {
                val current = ExportPoints.dropLeadingCountdown(all.filterNot { it.sample.isStale })
                if (current.isEmpty()) return ExportResult.Failure(ExportError.NoSamples)
                val buffer = ByteArrayOutputStream()
                OutputStreamWriter(buffer, Charsets.UTF_8).use { TcxWorkoutWriter.write(it, current, summary.stats, appVersion) }
                buffer.toByteArray()
            }
        }
        return ExportResult.Success(RenderedExport(fileName(summary, format), format, bytes))
    }

    /** Generates the file and saves it with the [storage] (on the IO dispatcher). */
    suspend fun save(detail: WorkoutDetail, format: ExportFormat): ExportResult<SavedExport> {
        val rendered = when (val r = render(detail, format)) {
            is ExportResult.Failure -> return r
            is ExportResult.Success -> r.value
        }
        return withContext(Dispatchers.IO) {
            try {
                ExportResult.Success(storage.save(rendered.fileName, format, rendered.content))
            } catch (e: IOException) {
                ExportResult.Failure(ExportError.StorageError, e.message ?: e.javaClass.simpleName)
            } catch (e: SecurityException) {
                ExportResult.Failure(ExportError.StorageError, e.message ?: e.javaClass.simpleName)
            }
        }
    }

    /** `treadz_workout_yyyyMMdd_HHmmss.<ext>` from the workout start in the local time zone. */
    fun fileName(summary: WorkoutSummary, format: ExportFormat): String =
        "treadz_workout_${FILE_TIME.format(Instant.ofEpochMilli(summary.startedAtMillis).atZone(zone))}.${format.extension}"

    companion object {
        private val FILE_TIME = DateTimeFormatter.ofPattern("yyyyMMdd_HHmmss", Locale.ROOT)

        /** The real exporter: saves into `Download/TreadZ/` through MediaStore (no permissions needed). */
        fun create(context: Context, appVersion: String): WorkoutExporter =
            WorkoutExporter(appVersion, MediaStoreExportStorage(context))
    }
}
