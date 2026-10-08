package com.treadz.console.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.res.pluralStringResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.DialogProperties
import com.treadz.console.R
import com.treadz.console.settings.SettingsUiState
import com.treadz.console.sync.FirstLinkSummary
import com.treadz.console.sync.UserUpload
import com.treadz.console.sync.github.RepositoryRef
import com.treadz.console.sync.service.SyncSetup

/** The user's choices in the synchronisation part of the settings. */
data class SyncSettingsActions(
    val onToken: (String) -> Unit = {},
    val onTokenVisible: (Boolean) -> Unit = {},
    val onSaveToken: () -> Unit = {},
    val onSelectRepository: (RepositoryRef) -> Unit = {},
    val onConfirmFirstLink: () -> Unit = {},
    val onCancelFirstLink: () -> Unit = {},
    /** Gives up choosing a repository: forgets the token. */
    val onCancelChoice: () -> Unit = {},
    val onSyncNow: () -> Unit = {},
    val onUnlink: () -> Unit = {},
    val onConfirmUnlink: () -> Unit = {},
    val onCancelUnlink: () -> Unit = {},
)

/** "Sync with GitHub": what it is and, depending on how far the setup is, the token, the repository or the status. */
@Composable
fun SyncSection(state: SettingsUiState, actions: SyncSettingsActions) {
    SettingsSection(stringResource(R.string.sync_title), Modifier.testTag("settings_sync")) {
        Text(
            stringResource(R.string.sync_intro),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        when (state.sync.setup) {
            SyncSetup.NotConfigured -> TokenEntry(state, actions, replacing = false)
            SyncSetup.ChoosingRepository -> RepositoryChoice(state, actions)
            SyncSetup.AwaitingFirstLinkConfirmation -> {
                Text(
                    stringResource(R.string.sync_first_link_pending),
                    Modifier.testTag("first_link_waiting"),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold,
                )
                state.error?.let { ErrorText(syncErrorText(it), Modifier.testTag("sync_error")) }
            }
            SyncSetup.Linked -> LinkedStatus(state, actions)
        }
    }
    if (state.sync.setup == SyncSetup.AwaitingFirstLinkConfirmation) {
        state.sync.firstLink?.let { summary ->
            FirstLinkDialog(
                summary = summary,
                repository = state.sync.repository?.fullName.orEmpty(),
                busy = state.busy,
                onConfirm = actions.onConfirmFirstLink,
                onCancel = actions.onCancelFirstLink,
            )
        }
    }
    if (state.confirmingUnlink) {
        ConfirmDialog(
            title = stringResource(R.string.sync_unlink_title),
            text = stringResource(R.string.sync_unlink_body),
            confirmText = stringResource(R.string.sync_unlink_confirm),
            onConfirm = actions.onConfirmUnlink,
            onDismiss = actions.onCancelUnlink,
        )
    }
}

/** Token field (masked, with show/hide), its explanation and the Link button. */
@Composable
private fun TokenEntry(state: SettingsUiState, actions: SyncSettingsActions, replacing: Boolean) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        if (replacing) {
            Text(stringResource(R.string.sync_replace_token_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        }
        OutlinedTextField(
            value = state.tokenInput,
            onValueChange = actions.onToken,
            label = { Text(stringResource(R.string.sync_token_label)) },
            singleLine = true,
            enabled = !state.busy,
            visualTransformation = if (state.tokenVisible) VisualTransformation.None else PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, autoCorrectEnabled = false),
            trailingIcon = {
                TextButton(onClick = { actions.onTokenVisible(!state.tokenVisible) }, modifier = Modifier.heightIn(min = 48.dp).testTag("token_visibility")) {
                    Text(stringResource(if (state.tokenVisible) R.string.sync_token_hide else R.string.sync_token_show))
                }
            },
            textStyle = MaterialTheme.typography.titleMedium,
            modifier = Modifier.fillMaxWidth().heightIn(min = 64.dp).testTag("token_field"),
        )
        // While replacing the token the error is already shown above, with the status.
        if (!replacing) state.error?.let { ErrorText(syncErrorText(it), Modifier.testTag("sync_error")) }
        if (state.busy) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 3.dp)
                Text(stringResource(R.string.sync_token_checking), style = MaterialTheme.typography.titleMedium, modifier = Modifier.testTag("token_checking"))
            }
        }
        BigButton(
            text = stringResource(R.string.sync_token_save),
            onClick = actions.onSaveToken,
            enabled = state.canSaveToken,
            modifier = Modifier.fillMaxWidth().testTag("token_save"),
        )
        if (!replacing) TokenHelp()
    }
}

@Composable
private fun TokenHelp() {
    Column(Modifier.testTag("token_help"), verticalArrangement = Arrangement.spacedBy(4.dp)) {
        Text(stringResource(R.string.sync_token_help_title), style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
        Text(
            stringResource(R.string.sync_token_help_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
    }
}

@Composable
private fun RepositoryChoice(state: SettingsUiState, actions: SyncSettingsActions) {
    Column(Modifier.testTag("repository_choice"), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(stringResource(R.string.sync_choose_title), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text(stringResource(R.string.sync_choose_body), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        state.sync.repositories.forEach { repository ->
            BigOutlinedButton(
                text = repository.fullName,
                onClick = { actions.onSelectRepository(repository) },
                enabled = !state.busy,
                modifier = Modifier.fillMaxWidth().testTag("repository_${repository.fullName}"),
            )
        }
        state.error?.let { ErrorText(syncErrorText(it), Modifier.testTag("sync_error")) }
        BigOutlinedButton(
            text = stringResource(R.string.action_cancel),
            onClick = actions.onCancelChoice,
            enabled = !state.busy,
            modifier = Modifier.fillMaxWidth().testTag("repository_cancel"),
        )
    }
}

@Composable
private fun LinkedStatus(state: SettingsUiState, actions: SyncSettingsActions) {
    val status = state.sync
    val locale = currentLocale()
    val syncing = status.isSyncing || state.syncingNow
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        LabelValue(stringResource(R.string.sync_label_repository), status.repository?.fullName ?: Format.NO_VALUE, Modifier.testTag("sync_repository"))
        LabelValue(
            stringResource(R.string.sync_label_last_sync),
            status.lastSyncMillis?.let { Format.dateTime(it, locale) } ?: stringResource(R.string.sync_never),
            Modifier.testTag("sync_last"),
        )
        LabelValue(
            stringResource(R.string.sync_label_pending),
            if (status.pendingCount > 0) pluralStringResource(R.plurals.sync_pending_changes, status.pendingCount, status.pendingCount) else stringResource(R.string.sync_pending_none),
            Modifier.testTag("sync_pending"),
        )
        if (syncing) {
            Row(Modifier.testTag("sync_progress"), verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 3.dp)
                Text(stringResource(R.string.sync_syncing), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            }
        }
        state.error?.let { ErrorText(syncErrorText(it), Modifier.testTag("sync_error")) }
        if (state.canReplaceToken) TokenEntry(state, actions, replacing = true)
        BigButton(
            text = stringResource(R.string.sync_now),
            onClick = actions.onSyncNow,
            enabled = !syncing,
            modifier = Modifier.fillMaxWidth().testTag("sync_now"),
        )
        Text(
            stringResource(R.string.sync_token_saved),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.testTag("token_saved_note"),
        )
        BigOutlinedButton(
            text = stringResource(R.string.sync_unlink),
            onClick = actions.onUnlink,
            destructive = true,
            modifier = Modifier.fillMaxWidth().testTag("sync_unlink"),
        )
    }
}

/**
 * What linking would upload. It is not dismissable by tapping outside: it only closes with an explicit choice, and
 * nothing leaves the device before "Upload and link".
 */
@Composable
private fun FirstLinkDialog(
    summary: FirstLinkSummary,
    repository: String,
    busy: Boolean,
    onConfirm: () -> Unit,
    onCancel: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = {},
        properties = DialogProperties(dismissOnBackPress = false, dismissOnClickOutside = false),
        modifier = Modifier.testTag("first_link_dialog"),
        title = { Text(stringResource(R.string.sync_first_link_title), fontWeight = FontWeight.Bold) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState()), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                Text(stringResource(R.string.sync_first_link_body, repository), style = MaterialTheme.typography.bodyLarge)
                summary.users.forEach { UploadRow(it) }
                summary.remoteUsers?.takeIf { it > 0 }?.let { count ->
                    Text(
                        pluralStringResource(R.plurals.sync_remote_users, count, count),
                        Modifier.testTag("first_link_remote"),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                Text(
                    stringResource(R.string.sync_first_link_cancel_note),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
        },
        confirmButton = {
            TextButton(onClick = onConfirm, enabled = !busy, modifier = Modifier.heightIn(min = 48.dp).testTag("first_link_confirm")) {
                Text(stringResource(R.string.sync_first_link_confirm), fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleMedium)
            }
        },
        dismissButton = {
            TextButton(onClick = onCancel, enabled = !busy, modifier = Modifier.heightIn(min = 48.dp).testTag("first_link_cancel")) {
                Text(stringResource(R.string.action_cancel), style = MaterialTheme.typography.titleMedium)
            }
        },
    )
}

/** One user of the first-link summary: the name and what of theirs would be uploaded. */
@Composable
private fun UploadRow(upload: UserUpload) {
    val parts = buildList {
        if (upload.profile) add(stringResource(R.string.sync_user_profile))
        if (upload.weightEntries > 0) add(pluralStringResource(R.plurals.sync_count_weights, upload.weightEntries, upload.weightEntries))
        if (upload.workouts > 0) add(pluralStringResource(R.plurals.sync_count_workouts, upload.workouts, upload.workouts))
        if (upload.programs > 0) add(pluralStringResource(R.plurals.sync_count_programs, upload.programs, upload.programs))
    }
    Column(Modifier.fillMaxWidth().testTag("first_link_user"), verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(upload.name.ifBlank { stringResource(R.string.generic_user_label) }, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.Bold)
        Text(parts.joinToString(" · "), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
internal fun LabelValue(label: String, value: String, modifier: Modifier = Modifier) {
    Row(modifier.fillMaxWidth().padding(vertical = 2.dp).semantics(mergeDescendants = true) {}, verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        Text(value, style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold, maxLines = 2)
    }
}
