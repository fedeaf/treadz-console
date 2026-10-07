package com.treadz.console

import android.content.ClipData
import android.content.Intent
import com.treadz.console.userdata.export.SavedExport

/** Share sheet Intent for an exported workout file, with read permission for the app picked in the chooser. */
object ExportShare {

    fun intent(export: SavedExport, title: CharSequence?): Intent {
        val uri = export.uri
        val send = Intent(Intent.ACTION_SEND).apply {
            type = export.format.mimeType
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, export.fileName)
            // ClipData + flag: this is what carries the read permission to the app picked in the chooser.
            clipData = ClipData.newRawUri(export.fileName, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        return Intent.createChooser(send, title).apply {
            clipData = send.clipData
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
    }
}
