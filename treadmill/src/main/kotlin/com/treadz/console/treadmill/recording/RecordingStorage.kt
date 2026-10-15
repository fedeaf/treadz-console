package com.treadz.console.treadmill.recording

import java.io.File
import java.io.FileOutputStream
import java.io.IOException

/**
 * Storage for the recordings, independent of the logic deciding what to record.
 * On Android it is implemented with MediaStore (public `Download/TreadZ/` folder); on the PC, a folder.
 */
interface RecordingStorage {
    /** `true` if a file with that name already exists (so it is not overwritten and the system does not add spaces to it). */
    fun exists(fileName: String): Boolean

    /** Creates a new file and keeps it open for incremental writing. */
    @Throws(IOException::class)
    fun create(fileName: String): RecordingFile
}

interface RecordingFile {
    /** Path shown to the user (e.g. `Download/TreadZ/treadz_20261007_183000.jsonl`). */
    val location: String

    /** Appends a line (without the line break) and hands it to the operating system: an app shutdown does not lose it. */
    @Throws(IOException::class)
    fun appendLine(line: String)

    /** Forces the write to disk (fsync): also protects against a power cut. */
    @Throws(IOException::class)
    fun sync()

    @Throws(IOException::class)
    fun close()
}

/** Stores the recordings in a file system folder (PC and tests). */
class DirectoryRecordingStorage(private val directory: File) : RecordingStorage {

    override fun exists(fileName: String): Boolean = File(directory, fileName).exists()

    override fun create(fileName: String): RecordingFile {
        require(' ' !in fileName) { "File names must not contain spaces: $fileName" }
        if (!directory.exists() && !directory.mkdirs()) throw IOException("Could not create $directory")
        val file = File(directory, fileName)
        if (!file.createNewFile()) throw IOException("Already exists: $file")
        return FileRecording(file)
    }

    private class FileRecording(private val file: File) : RecordingFile {
        private val out = FileOutputStream(file, true)
        override val location: String get() = file.path

        override fun appendLine(line: String) {
            out.write((line + "\n").toByteArray(Charsets.UTF_8))
            out.flush()
        }

        override fun sync() = out.fd.sync()

        override fun close() = out.close()
    }
}
