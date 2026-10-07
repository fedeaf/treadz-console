package com.treadz.console.userdata.export

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.provider.MediaStore
import java.io.IOException

/**
 * Saves exports in the public `Download/TreadZConsole/` folder through MediaStore (no storage permissions on
 * Android 13+), the same folder as the debug recordings.
 *
 * - The file stays hidden (`IS_PENDING`) until it is completely written, so nobody sees a half-written export.
 * - Exporting the same workout again replaces the previous file of this app (same name, same Uri).
 * - If the name is taken by a file this app cannot overwrite (MediaStore would rename it adding " (1)", with a
 *   space), it is discarded and retried with a `_2`, `_3`... suffix.
 * - Names must have no spaces.
 */
class MediaStoreExportStorage(context: Context) : ExportStorage {

    private val resolver = context.applicationContext.contentResolver
    private val collection: Uri = MediaStore.Downloads.EXTERNAL_CONTENT_URI

    override fun save(fileName: String, format: ExportFormat, content: ByteArray): SavedExport {
        require(' ' !in fileName) { "File names must not contain spaces: $fileName" }
        val base = fileName.removeSuffix(".${format.extension}")
        for (attempt in 1..MAX_ATTEMPTS) {
            val wanted = if (attempt == 1) fileName else "${base}_$attempt.${format.extension}"
            val uri = writeNew(wanted, format, content) ?: continue
            return SavedExport(wanted, format, "$RELATIVE_PATH/$wanted", uri.toString())
        }
        throw IOException("Could not create a file with a valid name for $fileName")
    }

    /** Writes [content] as [name]; `null` if the name cannot be used. */
    private fun writeNew(name: String, format: ExportFormat, content: ByteArray): Uri? {
        findUri(name)?.let { existing ->
            // Our own file from a previous export: replace its content.
            return try {
                resolver.openOutputStream(existing, "wt")?.use { it.write(content) } ?: return null
                existing
            } catch (e: SecurityException) {
                null
            } catch (e: java.io.FileNotFoundException) {
                null
            }
        }
        val values = ContentValues().apply {
            put(MediaStore.Downloads.DISPLAY_NAME, name)
            put(MediaStore.Downloads.MIME_TYPE, storageMimeType(format))
            put(MediaStore.Downloads.RELATIVE_PATH, RELATIVE_PATH)
            put(MediaStore.Downloads.IS_PENDING, 1)
        }
        val uri = resolver.insert(collection, values) ?: throw IOException("MediaStore did not create $name")
        try {
            if (displayName(uri) != name) {
                resolver.delete(uri, null, null)
                return null
            }
            val out = resolver.openOutputStream(uri, "w") ?: throw IOException("Could not open $name")
            out.use { it.write(content) }
            resolver.update(uri, ContentValues().apply { put(MediaStore.Downloads.IS_PENDING, 0) }, null, null)
            return uri
        } catch (e: Exception) {
            runCatching { resolver.delete(uri, null, null) }
            throw if (e is IOException) e else IOException(e)
        }
    }

    /** Uri of a file of this app in the export folder; `null` if there is none. */
    private fun findUri(fileName: String): Uri? =
        resolver.query(
            collection,
            arrayOf(MediaStore.Downloads._ID),
            "${MediaStore.Downloads.DISPLAY_NAME} = ? AND ${MediaStore.Downloads.RELATIVE_PATH} LIKE ?",
            arrayOf(fileName, "$RELATIVE_PATH%"),
            null,
        )?.use { c -> if (c.moveToFirst()) ContentUris.withAppendedId(collection, c.getLong(0)) else null }

    private fun displayName(uri: Uri): String? =
        resolver.query(uri, arrayOf(MediaStore.Downloads.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst()) c.getString(0) else null
        }

    /**
     * MIME type stored in MediaStore. A type that does not match the extension makes MediaStore rename the file, so
     * the unknown TCX type is stored as octet-stream (sharing uses [ExportFormat.mimeType]).
     */
    private fun storageMimeType(format: ExportFormat) = when (format) {
        ExportFormat.Csv -> "text/csv"
        ExportFormat.Tcx -> "application/octet-stream"
    }

    companion object {
        const val RELATIVE_PATH = "Download/TreadZConsole"
        private const val MAX_ATTEMPTS = 5
    }
}
