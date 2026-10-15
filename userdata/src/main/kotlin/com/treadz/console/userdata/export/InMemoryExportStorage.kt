package com.treadz.console.userdata.export

/** Fake [ExportStorage] for tests and screenshots: keeps the files in memory. */
class InMemoryExportStorage : ExportStorage {

    private val _files = LinkedHashMap<String, ByteArray>()

    /** Saved files by name, in saving order. */
    val files: Map<String, ByteArray> get() = synchronized(this) { LinkedHashMap(_files) }

    override fun save(fileName: String, format: ExportFormat, content: ByteArray): SavedExport = synchronized(this) {
        _files[fileName] = content
        SavedExport(fileName, format, "$RELATIVE_PATH/$fileName", "content://com.treadz.console.test/export/$fileName")
    }

    companion object {
        const val RELATIVE_PATH = "Download/TreadZ"
    }
}
