package com.treadz.console.ui

import com.treadz.console.treadmill.DiscoveredTreadmill
import com.treadz.console.treadmill.RecordingInfo

/** Actions the UI can request. Keeps the composables free of the ViewModel (easier to capture). */
data class MainActions(
    val onStart: () -> Unit = {},
    val onScan: () -> Unit = {},
    val onStopScan: () -> Unit = {},
    val onConnect: (DiscoveredTreadmill) -> Unit = {},
    val onReconnectRemembered: () -> Unit = {},
    val onDismissAlert: () -> Unit = {},
    val onDismissRecordingNotice: () -> Unit = {},
    val onShare: (RecordingInfo) -> Unit = {},
    val onDarkThemeChange: (Boolean) -> Unit = {},
    val onRequestPermissions: () -> Unit = {},
    val onOpenSettings: () -> Unit = {},
)

enum class BluetoothPermission {
    Granted,

    /** Not requested yet (or the user can try again). */
    NeedsRequest,

    /** Denied, but Android still allows asking again. */
    Denied,

    /** Denied with "don't ask again": it can only be fixed in the system settings. */
    DeniedPermanently,
}

data class PermissionsUi(
    val bluetooth: BluetoothPermission = BluetoothPermission.Granted,
    val notificationsDenied: Boolean = false,
)
