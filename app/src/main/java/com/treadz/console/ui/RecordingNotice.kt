package com.treadz.console.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.LiveRegionMode
import androidx.compose.ui.semantics.liveRegion
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.treadz.console.MainUiState
import com.treadz.console.R
import com.treadz.console.treadmill.RecordingStatus

/** Discreet indicator while recording (it goes in the top bar). */
@Composable
fun RecordingIndicator(modifier: Modifier = Modifier) {
    Row(modifier.testTag("recording_indicator"), verticalAlignment = Alignment.CenterVertically) {
        StatusDot(MaterialTheme.colorScheme.error, size = 10.dp)
        Text(
            stringResource(R.string.recording_indicator),
            modifier = Modifier.padding(start = 8.dp),
            style = MaterialTheme.typography.labelLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

/** Message when the session ends: path of the saved file and Share button (or the error if it failed). */
@Composable
fun RecordingNotice(ui: MainUiState, actions: MainActions) {
    when (val notice = ui.recordingNotice) {
        is RecordingStatus.Saved -> Surface(
            modifier = Modifier.fillMaxWidth().testTag("recording_saved").semantics { liveRegion = LiveRegionMode.Polite },
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.primaryContainer,
            contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
        ) {
            Column(Modifier.padding(horizontal = 16.dp, vertical = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                Text(
                    stringResource(R.string.recording_saved_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                Text(
                    stringResource(R.string.recording_saved_body, notice.location),
                    style = MaterialTheme.typography.bodyMedium,
                )
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp, Alignment.End)) {
                    TextButton(onClick = actions.onDismissRecordingNotice, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text(stringResource(R.string.recording_close))
                    }
                    Button(onClick = { actions.onShare(notice.info) }, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text(stringResource(R.string.recording_share))
                    }
                }
            }
        }

        is RecordingStatus.Failed -> Surface(
            modifier = Modifier.fillMaxWidth().testTag("recording_failed"),
            shape = RoundedCornerShape(20.dp),
            color = MaterialTheme.colorScheme.error,
            contentColor = MaterialTheme.colorScheme.onError,
        ) {
            Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                Text(
                    stringResource(R.string.recording_failed_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
                Text(notice.reason, style = MaterialTheme.typography.bodyMedium)
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
                    TextButton(onClick = actions.onDismissRecordingNotice, modifier = Modifier.heightIn(min = 48.dp)) {
                        Text(stringResource(R.string.recording_close), color = MaterialTheme.colorScheme.onError)
                    }
                }
            }
        }

        else -> Unit
    }
}
