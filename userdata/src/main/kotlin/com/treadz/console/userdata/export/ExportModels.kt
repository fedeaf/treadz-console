package com.treadz.console.userdata.export

import android.net.Uri
import java.io.IOException

/** File formats a stored workout can be exported to. */
enum class ExportFormat(
    /** File name extension, without the dot. */
    val extension: String,
    /** MIME type to use when sharing the file (`Intent.setType`). */
    val mimeType: String,
) {
    /** One row per sample for analysis on a PC (spreadsheets, pandas, R). */
    Csv("csv", "text/csv"),

    /** Garmin Training Center XML v2, accepted by Strava, Garmin Connect and most fitness services. */
    Tcx("tcx", "application/vnd.garmin.tcx+xml"),
}

/** Why a workout could not be exported. Stable codes: the UI turns them into texts. */
enum class ExportError {
    /** The workout is still being recorded; export it once it has ended. */
    WorkoutInProgress,

    /** Nothing to export: the workout has no samples (for TCX: no current sample after excluding the stale ones). */
    NoSamples,

    /** The file could not be written to the device storage. */
    StorageError,
}

/** Result of an export operation. */
sealed interface ExportResult<out T> {
    data class Success<T>(val value: T) : ExportResult<T>

    /** Nothing was written. [reason] is only for the log (it is `null` unless [error] is [ExportError.StorageError]). */
    data class Failure(val error: ExportError, val reason: String? = null) : ExportResult<Nothing>
}

/** A file generated in memory, not saved yet. */
class RenderedExport(
    /** File name without spaces, e.g. `treadz_workout_20261007_134500.tcx`. */
    val fileName: String,
    val format: ExportFormat,
    /** Complete file content (UTF-8). */
    val content: ByteArray,
)

/** A file saved on the device. */
data class SavedExport(
    /** Actual file name (it can differ from the wanted one by a `_2`, `_3`... suffix if the name was taken). */
    val fileName: String,
    val format: ExportFormat,
    /** Human readable location for a message, e.g. `Download/TreadZConsole/treadz_workout_20261007_134500.csv`. */
    val location: String,
    /** String form of the content Uri, usable without Android classes (tests, fakes). */
    val uriString: String,
) {
    /** Content Uri of the saved file, to share it (`Intent.EXTRA_STREAM` with `FLAG_GRANT_READ_URI_PERMISSION`). */
    val uri: Uri get() = Uri.parse(uriString)
}

/** Where exported files end up. [MediaStoreExportStorage] is the real one, [InMemoryExportStorage] the fake. */
interface ExportStorage {

    /**
     * Writes [content] as a new file called [fileName] (or a variation of it if the name is taken) and returns where.
     * Blocking: call it from a background thread ([WorkoutExporter.save] does).
     */
    @Throws(IOException::class)
    fun save(fileName: String, format: ExportFormat, content: ByteArray): SavedExport
}
