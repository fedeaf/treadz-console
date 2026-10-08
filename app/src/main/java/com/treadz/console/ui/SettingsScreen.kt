package com.treadz.console.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.treadz.console.R
import com.treadz.console.settings.AppLanguage
import com.treadz.console.settings.SettingsUiState

/** Actions of the settings screen. */
data class SettingsActions(
    val onBack: () -> Unit = {},
    val sync: SyncSettingsActions = SyncSettingsActions(),
    val onOpenUsers: () -> Unit = {},
    val onForgetTreadmill: () -> Unit = {},
    val onDarkTheme: (Boolean) -> Unit = {},
    val onLanguage: (AppLanguage) -> Unit = {},
    val onOpenAppSettings: () -> Unit = {},
    val onOpenDownloads: () -> Unit = {},
    /** `null` when there is no recording to share. */
    val onShareRecording: (() -> Unit)? = null,
)

/**
 * General settings: sync with GitHub, users, treadmill, appearance, language, permissions and about. A wide window
 * puts the synchronisation (the longest part) beside the rest.
 */
@Composable
fun SettingsScreen(
    state: SettingsUiState,
    permissions: PermissionsUi,
    versionName: String,
    actions: SettingsActions,
    modifier: Modifier = Modifier,
) {
    val layout = LocalLayout.current
    Column(modifier.fillMaxSize(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        ScreenHeader(title = stringResource(R.string.settings_title), onBack = actions.onBack)
        if (layout.wide) {
            Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(16.dp)) {
                Column(
                    Modifier.weight(1f).fillMaxSize().verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    SyncSection(state, actions.sync)
                }
                Column(
                    Modifier.weight(1f).fillMaxSize().verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(14.dp),
                ) {
                    OtherSections(state, permissions, versionName, actions)
                }
            }
        } else {
            Column(
                Modifier.weight(1f).fillMaxWidth().widthIn(max = 640.dp).verticalScroll(rememberScrollState()).testTag("settings_scroll"),
                verticalArrangement = Arrangement.spacedBy(14.dp),
            ) {
                SyncSection(state, actions.sync)
                OtherSections(state, permissions, versionName, actions)
            }
        }
    }
}

@Composable
private fun OtherSections(state: SettingsUiState, permissions: PermissionsUi, versionName: String, actions: SettingsActions) {
    UsersSection(actions)
    TreadmillSection(state, actions)
    AppearanceSection(state, actions)
    LanguageSection(state, actions)
    PermissionsSection(permissions, actions)
    AboutSection(versionName, actions)
}

/** A titled card of the settings. */
@Composable
fun SettingsSection(title: String, modifier: Modifier = Modifier, content: @Composable () -> Unit) {
    Panel(modifier.fillMaxWidth()) {
        Column(Modifier.padding(horizontal = 18.dp, vertical = 16.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
            Text(title, style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.Bold)
            content()
        }
    }
}

@Composable
private fun UsersSection(actions: SettingsActions) {
    SettingsSection(stringResource(R.string.settings_users_title), Modifier.testTag("settings_users")) {
        Text(stringResource(R.string.settings_users_body), style = MaterialTheme.typography.bodyLarge, color = MaterialTheme.colorScheme.onSurfaceVariant)
        BigOutlinedButton(
            stringResource(R.string.settings_users_button),
            actions.onOpenUsers,
            Modifier.fillMaxWidth().testTag("settings_manage_users"),
        )
    }
}

@Composable
private fun TreadmillSection(state: SettingsUiState, actions: SettingsActions) {
    SettingsSection(stringResource(R.string.settings_treadmill_title), Modifier.testTag("settings_treadmill")) {
        if (state.treadmillRemembered) {
            Text(
                state.treadmillName ?: stringResource(R.string.settings_treadmill_unnamed),
                Modifier.testTag("settings_treadmill_name"),
                style = MaterialTheme.typography.titleLarge,
                fontWeight = FontWeight.SemiBold,
            )
            Text(
                stringResource(R.string.settings_treadmill_forget_note),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            BigOutlinedButton(
                stringResource(R.string.settings_treadmill_forget),
                actions.onForgetTreadmill,
                Modifier.fillMaxWidth().testTag("forget_treadmill"),
                enabled = state.canForgetTreadmill,
                destructive = true,
            )
            if (!state.beltIdle) BeltLockedNote("settings_belt_locked")
        } else {
            Text(
                stringResource(R.string.settings_treadmill_none),
                Modifier.testTag("settings_treadmill_none"),
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

@Composable
private fun BeltLockedNote(tag: String) {
    Text(
        stringResource(R.string.settings_belt_locked),
        Modifier.testTag(tag),
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.extra.warning,
    )
}

@Composable
private fun AppearanceSection(state: SettingsUiState, actions: SettingsActions) {
    SettingsSection(stringResource(R.string.settings_appearance_title), Modifier.testTag("settings_appearance")) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            Text(stringResource(R.string.theme_dark), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
            Switch(checked = state.darkTheme, onCheckedChange = actions.onDarkTheme, modifier = Modifier.testTag("theme_switch"))
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun LanguageSection(state: SettingsUiState, actions: SettingsActions) {
    SettingsSection(stringResource(R.string.settings_language_title), Modifier.testTag("settings_language")) {
        FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
            LanguageChip(AppLanguage.Automatic, R.string.language_automatic, "language_auto", state, actions)
            LanguageChip(AppLanguage.Spanish, R.string.language_spanish, "language_es", state, actions)
            LanguageChip(AppLanguage.English, R.string.language_english, "language_en", state, actions)
        }
        Text(
            stringResource(R.string.settings_language_note),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        if (!state.beltIdle) BeltLockedNote("settings_belt_locked_language")
    }
}

@Composable
private fun LanguageChip(language: AppLanguage, label: Int, tag: String, state: SettingsUiState, actions: SettingsActions) {
    BigChip(
        text = stringResource(label),
        selected = state.language == language,
        onClick = { if (state.beltIdle) actions.onLanguage(language) },
        tag = tag,
    )
}

@Composable
private fun PermissionsSection(permissions: PermissionsUi, actions: SettingsActions) {
    val bluetooth = permissions.bluetooth == BluetoothPermission.Granted
    val notifications = permissions.notificationsGranted
    SettingsSection(stringResource(R.string.settings_permissions_title), Modifier.testTag("settings_permissions")) {
        PermissionRow(stringResource(R.string.settings_perm_bluetooth), bluetooth, "perm_bluetooth")
        PermissionRow(stringResource(R.string.settings_perm_notifications), notifications, "perm_notifications")
        if (!bluetooth || !notifications) {
            Text(
                stringResource(R.string.settings_perm_help),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            BigOutlinedButton(
                stringResource(R.string.perm_open_settings),
                actions.onOpenAppSettings,
                Modifier.fillMaxWidth().testTag("open_app_settings"),
            )
        }
    }
}

@Composable
private fun PermissionRow(label: String, granted: Boolean, tag: String) {
    val color = if (granted) MaterialTheme.extra.ok else MaterialTheme.colorScheme.error
    Row(
        Modifier.fillMaxWidth().semantics(mergeDescendants = true) {}.testTag(tag),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(10.dp),
    ) {
        StatusDot(color, size = 14.dp)
        Text(label, Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
        // The text carries the meaning; the dot only helps.
        Text(
            stringResource(if (granted) R.string.settings_perm_allowed else R.string.settings_perm_missing),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.SemiBold,
            color = color,
        )
    }
}

@Composable
private fun AboutSection(versionName: String, actions: SettingsActions) {
    SettingsSection(stringResource(R.string.settings_about_title), Modifier.testTag("settings_about")) {
        Text(stringResource(R.string.app_name), style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
        Text(
            stringResource(R.string.settings_about_version, versionName),
            Modifier.testTag("about_version"),
            style = MaterialTheme.typography.bodyLarge,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
        )
        Text(stringResource(R.string.settings_recordings_body), style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
        BigOutlinedButton(
            stringResource(R.string.settings_recordings_open),
            actions.onOpenDownloads,
            Modifier.fillMaxWidth().testTag("open_downloads"),
        )
        actions.onShareRecording?.let { share ->
            BigOutlinedButton(
                stringResource(R.string.settings_recordings_share),
                share,
                Modifier.fillMaxWidth().testTag("share_last_recording"),
            )
        }
    }
}
