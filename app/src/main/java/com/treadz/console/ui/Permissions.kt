package com.treadz.console.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.treadz.console.R

/** Explanation and request of the Bluetooth permissions (and, if denied, how to fix it). */
@Composable
fun PermissionPane(permission: BluetoothPermission, actions: MainActions, modifier: Modifier = Modifier) {
    val (titleRes, bodyRes) = when (permission) {
        BluetoothPermission.NeedsRequest, BluetoothPermission.Granted -> R.string.perm_title to R.string.perm_body
        BluetoothPermission.Denied -> R.string.perm_denied_title to R.string.perm_denied_body
        BluetoothPermission.DeniedPermanently -> R.string.perm_denied_title to R.string.perm_denied_permanent_body
    }
    Box(modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
        Column(
            Modifier.widthIn(max = 600.dp).verticalScroll(rememberScrollState()).testTag("permission_pane"),
            verticalArrangement = Arrangement.spacedBy(16.dp),
        ) {
            Text(
                stringResource(titleRes),
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
            )
            Text(
                stringResource(bodyRes),
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
            if (permission == BluetoothPermission.DeniedPermanently) {
                Button(
                    onClick = actions.onOpenSettings,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 72.dp),
                    shape = RoundedCornerShape(24.dp),
                ) { Text(stringResource(R.string.perm_open_settings), style = MaterialTheme.typography.titleLarge) }
            } else {
                Button(
                    onClick = actions.onRequestPermissions,
                    modifier = Modifier.fillMaxWidth().heightIn(min = 72.dp),
                    shape = RoundedCornerShape(24.dp),
                ) { Text(stringResource(R.string.perm_grant), style = MaterialTheme.typography.titleLarge) }
            }
        }
    }
}

/** Discreet notice: without notifications the foreground service works, but its notification is not shown. */
@Composable
fun NotificationsDeniedBanner(modifier: Modifier = Modifier) {
    Surface(
        modifier = modifier.fillMaxWidth().testTag("notifications_denied"),
        shape = RoundedCornerShape(16.dp),
        color = MaterialTheme.colorScheme.surfaceContainerHigh,
    ) {
        Text(
            stringResource(R.string.notifications_denied),
            Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurface,
        )
    }
}
