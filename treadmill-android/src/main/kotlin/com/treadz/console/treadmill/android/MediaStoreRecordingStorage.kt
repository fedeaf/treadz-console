package com.treadz.console.treadmill.android

import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import com.treadz.console.treadmill.recording.RecordingFile
import com.treadz.console.treadmill.recording.RecordingStorage
import java.io.FileOutputStream
import java.io.IOException

/**
 * Stores the recordings in the public `Download/TreadZ/` folder through MediaStore
 * (no storage permissions on Android 13+). The files are visible from the start (`IS_PENDING`
 * is not used, since it would hide them and expire them if the app dies mid-session) and every line
 * is handed to the system as it is written: an unexpected shutdown does not lose what was recorded.
 *
 * Names have no spaces; if MediaStore renamed the file because of a collision (it would add " (1)"),
 * it is discarded and retried with a `_2`, `_3`… suffix.
 */
class MediaStoreRecordingStorage(context: Context) : RecordingStorage {

    private val resolver = context.applicationContext.contentResolver
    private val collection: Uri = MediaStore.Downloads.EXTERNAL_CONTENT_URI

    override fun exists(fileName: String): Boolean = findUri(fileName) != null

    override fun create(fileName: String): RecordingFile {
        require(' ' !in fileName) { "File names must not contain spaces: $fileName" }
        val base = fileName.removeSuffix(EXTENSION)
        for (attempt in 1..MAX_ATTEMPTS) {
            val wanted = if (attempt == 1) fileName else "${base}_x$attempt$EXTENSION"
            val values = ContentValues().apply {
                put(MediaStore.Downloads.DISPLAY_NAME, wanted)
                // octet-stream: this way MediaStore does not add an extension based on the MIME type (e.g. .txt)
                put(MediaStore.Downloads.MIME_TYPE, "application/octet-stream")
                put(MediaStore.Downloads.RELATIVE_PATH, RELATIVE_PATH)
            }
            val uri = resolver.insert(collection, values) ?: throw IOException("MediaStore did not create $wanted")
            val actual = displayName(uri)
            if (actual != wanted) {
                resolver.delete(uri, null, null)
                continue
            }
            val pfd = try {
                resolver.openFileDescriptor(uri, "w") ?: throw IOException("Could not open $wanted")
            } catch (e: Exception) {
                resolver.delete(uri, null, null)
                throw if (e is IOException) e else IOException(e)
            }
            return MediaStoreFile(wanted, ParcelFileDescriptor.AutoCloseOutputStream(pfd))
        }
        throw IOException("Could not create a file with a valid name for $fileName")
    }

    /** Uri of the saved file (to share it); `null` if it does not exist or does not belong to this app. */
    fun findUri(fileName: String): Uri? =
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

    private class MediaStoreFile(
        private val name: String,
        private val out: FileOutputStream,
    ) : RecordingFile {
        override val location: String = "$RELATIVE_PATH/$name"

        override fun appendLine(line: String) {
            // No intermediate buffer: write() goes straight to the descriptor and the system already has the line.
            out.write((line + "\n").toByteArray(Charsets.UTF_8))
        }

        override fun sync() = out.fd.sync()

        override fun close() = out.close()
    }

    companion object {
        const val RELATIVE_PATH = "Download/TreadZ"
        private const val EXTENSION = ".jsonl"
        private const val MAX_ATTEMPTS = 5
    }
}
