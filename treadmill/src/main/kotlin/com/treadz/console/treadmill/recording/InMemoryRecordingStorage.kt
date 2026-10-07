package com.treadz.console.treadmill.recording

/** In-memory storage: for tests and for simulator mode without files. */
class InMemoryRecordingStorage : RecordingStorage {
    private val files = linkedMapOf<String, StringBuilder>()
    private val closed = mutableSetOf<String>()

    /** If not `null`, `create` throws this error (simulates full storage or missing permissions). */
    @Volatile
    var failOnCreate: Exception? = null

    /** If `true`, writes fail (simulates an I/O error in the middle of a session). */
    @Volatile
    var failOnWrite: Boolean = false

    val fileNames: List<String> get() = synchronized(files) { files.keys.toList() }

    fun content(fileName: String): String = synchronized(files) { files.getValue(fileName).toString() }

    fun isClosed(fileName: String): Boolean = synchronized(files) { fileName in closed }

    fun parsed(fileName: String): RecordedSession = RecordingReader.parse(content(fileName))

    override fun exists(fileName: String): Boolean = synchronized(files) { fileName in files }

    override fun create(fileName: String): RecordingFile {
        failOnCreate?.let { throw it }
        require(' ' !in fileName) { "File names must not contain spaces: $fileName" }
        synchronized(files) { files[fileName] = StringBuilder() }
        return object : RecordingFile {
            override val location: String = "memory/$fileName"
            override fun appendLine(line: String) {
                if (failOnWrite) throw java.io.IOException("disk full")
                synchronized(files) { files.getValue(fileName).append(line).append('\n') }
            }

            override fun sync() = Unit
            override fun close() {
                synchronized(files) { closed += fileName }
            }
        }
    }
}
