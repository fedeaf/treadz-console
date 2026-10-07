package com.treadz.console.treadmill.android

import android.content.ClipData
import android.content.Context
import android.content.Intent
import com.treadz.console.treadmill.RecordingInfo

/** Share menu Intent for a saved recording, with read permission for the target app. */
internal object RecordingShare {

    fun intent(context: Context, recording: RecordingInfo): Intent {
        val uri = MediaStoreRecordingStorage(context).findUri(recording.fileName)
        val send = Intent(Intent.ACTION_SEND).apply {
            putExtra(Intent.EXTRA_SUBJECT, recording.fileName)
            if (uri != null) {
                type = "text/plain"
                putExtra(Intent.EXTRA_STREAM, uri)
                // ClipData + flag: this is what carries the read permission to the app picked in the chooser.
                clipData = ClipData.newRawUri(recording.fileName, uri)
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } else {
                // The file is gone (deleted or from another install): share at least its name.
                type = "text/plain"
                putExtra(
                    Intent.EXTRA_TEXT,
                    context.getString(R.string.treadz_share_missing, "${MediaStoreRecordingStorage.RELATIVE_PATH}/${recording.fileName}"),
                )
            }
        }
        return Intent.createChooser(send, recording.fileName).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (uri != null) {
                clipData = send.clipData
                addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
            }
        }
    }
}
