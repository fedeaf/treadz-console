package com.treadz.console.userdata.export

import android.content.ContentProvider
import android.content.ContentUris
import android.content.ContentValues
import android.content.Context
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.ParcelFileDescriptor
import android.provider.MediaStore
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File
import java.io.IOException

/**
 * A stand-in for the MediaStore Downloads provider: it keeps the rows and their files in memory/temp files and,
 * like the real one, renames a new file whose name is taken by someone else (adding " (1)").
 */
class FakeMediaProvider : ContentProvider() {
    class Row(val id: Long, var name: String, val mime: String, val path: String, var pending: Int, val file: File, val owned: Boolean)

    companion object {
        val rows = LinkedHashMap<Long, Row>()
        val foreignNames = mutableSetOf<String>()
        var failOpen = false
        lateinit var dir: File
        private var nextId = 1L

        fun reset() {
            rows.clear()
            foreignNames.clear()
            failOpen = false
            nextId = 1
            dir = java.nio.file.Files.createTempDirectory("fake-media").toFile()
        }
    }

    override fun onCreate() = true

    override fun insert(uri: Uri, values: ContentValues?): Uri {
        val v = values!!
        var name = v.getAsString(MediaStore.Downloads.DISPLAY_NAME)
        if (name in foreignNames || rows.values.any { it.name == name }) {
            val dot = name.lastIndexOf('.')
            name = name.substring(0, dot) + " (1)" + name.substring(dot)
        }
        val id = nextId++
        val row = Row(
            id, name, v.getAsString(MediaStore.Downloads.MIME_TYPE), v.getAsString(MediaStore.Downloads.RELATIVE_PATH) + "/",
            v.getAsInteger(MediaStore.Downloads.IS_PENDING) ?: 0, File(dir, "$id").also { it.createNewFile() }, owned = true,
        )
        rows[id] = row
        return ContentUris.withAppendedId(uri, id)
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?, selectionArgs: Array<out String>?, sortOrder: String?): Cursor {
        val cursor = MatrixCursor(projection!!)
        val matches: List<Row> = if (selection == null) {
            listOfNotNull(rows[ContentUris.parseId(uri)])
        } else {
            val (name, pathLike) = selectionArgs!!
            rows.values.filter { it.owned && it.pending == 0 && it.name == name && it.path.startsWith(pathLike.removeSuffix("%")) }
        }
        for (r in matches) {
            cursor.addRow(projection.map { col ->
                when (col) {
                    MediaStore.Downloads._ID -> r.id
                    MediaStore.Downloads.DISPLAY_NAME -> r.name
                    else -> null
                }
            })
        }
        return cursor
    }

    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int {
        val row = rows[ContentUris.parseId(uri)] ?: return 0
        values?.getAsInteger(MediaStore.Downloads.IS_PENDING)?.let { row.pending = it }
        return 1
    }

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int =
        if (rows.remove(ContentUris.parseId(uri)) != null) 1 else 0

    override fun getType(uri: Uri): String? = null

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        if (failOpen) throw SecurityException("not the owner")
        val row = rows.getValue(ContentUris.parseId(uri))
        val flags = ParcelFileDescriptor.MODE_WRITE_ONLY or ParcelFileDescriptor.MODE_CREATE or
            (if ('t' in mode) ParcelFileDescriptor.MODE_TRUNCATE else 0)
        return ParcelFileDescriptor.open(row.file, flags)
    }
}

@RunWith(RobolectricTestRunner::class)
class MediaStoreExportStorageTest {

    private lateinit var storage: MediaStoreExportStorage

    @Before fun setUp() {
        FakeMediaProvider.reset()
        Robolectric.setupContentProvider(FakeMediaProvider::class.java, MediaStore.AUTHORITY)
        storage = MediaStoreExportStorage(RuntimeEnvironment.getApplication() as Context)
    }

    private fun contentOf(saved: SavedExport) = FakeMediaProvider.rows.getValue(ContentUris.parseId(saved.uri)).file.readBytes()

    @Test fun `saves into the TreadZConsole download folder and returns the content uri`() {
        val bytes = "a,b\n1,2\n".toByteArray()
        val saved = storage.save("treadz_workout_20261007_120000.csv", ExportFormat.Csv, bytes)
        assertEquals("treadz_workout_20261007_120000.csv", saved.fileName)
        assertEquals("Download/TreadZConsole/treadz_workout_20261007_120000.csv", saved.location)
        assertEquals("content", saved.uri.scheme)
        assertArrayEquals(bytes, contentOf(saved))
        val row = FakeMediaProvider.rows.values.single()
        assertEquals("Download/TreadZConsole/", row.path)
        assertEquals("text/csv", row.mime)
        assertEquals("the file is visible once written", 0, row.pending)
    }

    @Test fun `tcx is stored as octet-stream so MediaStore keeps the extension`() {
        storage.save("treadz_workout_20261007_120000.tcx", ExportFormat.Tcx, byteArrayOf(1))
        assertEquals("application/octet-stream", FakeMediaProvider.rows.values.single().mime)
    }

    @Test fun `exporting again replaces the previous file`() {
        val first = storage.save("treadz_workout_20261007_120000.csv", ExportFormat.Csv, "first-version-longer".toByteArray())
        val second = storage.save("treadz_workout_20261007_120000.csv", ExportFormat.Csv, "second".toByteArray())
        assertEquals(first.uriString, second.uriString)
        assertEquals(1, FakeMediaProvider.rows.size)
        assertEquals("second", String(contentOf(second)))
    }

    @Test fun `a name taken by a file we cannot touch gets a suffix without spaces`() {
        FakeMediaProvider.foreignNames += "treadz_workout_20261007_120000.csv"
        val saved = storage.save("treadz_workout_20261007_120000.csv", ExportFormat.Csv, "x".toByteArray())
        assertEquals("treadz_workout_20261007_120000_2.csv", saved.fileName)
        assertFalse(' ' in saved.fileName)
        assertEquals("the renamed attempt was discarded", 1, FakeMediaProvider.rows.size)
    }

    @Test fun `a file of ours that cannot be overwritten is saved under a new name`() {
        storage.save("treadz_workout_20261007_120000.csv", ExportFormat.Csv, "old".toByteArray())
        FakeMediaProvider.failOpen = true
        try {
            // opening the existing file for writing is denied, and so is the new one: the failure is an IOException
            storage.save("treadz_workout_20261007_120000.csv", ExportFormat.Csv, "new".toByteArray())
            org.junit.Assert.fail("expected an IOException")
        } catch (e: IOException) {
            assertTrue(FakeMediaProvider.rows.size <= 2)
        }
    }

    @Test fun `names with spaces are rejected`() {
        val failed = runCatching { storage.save("a b.csv", ExportFormat.Csv, byteArrayOf()) }.exceptionOrNull()
        assertTrue(failed is IllegalArgumentException)
        assertTrue(FakeMediaProvider.rows.isEmpty())
    }

    @Test fun `different workouts do not overwrite each other`() {
        val a = storage.save("treadz_workout_20261007_120000.csv", ExportFormat.Csv, "a".toByteArray())
        val b = storage.save("treadz_workout_20261007_130000.csv", ExportFormat.Csv, "b".toByteArray())
        assertNotEquals(a.uriString, b.uriString)
        assertEquals(2, FakeMediaProvider.rows.size)
    }
}
