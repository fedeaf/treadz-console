package com.treadz.console.ui

import com.treadz.console.treadmill.DiscoveredTreadmill
import com.treadz.console.treadmill.RecordingInfo
import com.treadz.console.userdata.export.SavedExport

/** The manual treadmill controls. STOP never needs more than a tap. */
data class ControlActions(
    val onStop: () -> Unit = {},
    val onPause: () -> Unit = {},
    val onResume: () -> Unit = {},
    /** A −/+ button went down (`-1` or `+1`): one step now and auto-repeat until the matching release. */
    val onSpeedPress: (Int) -> Unit = {},
    val onSpeedRelease: () -> Unit = {},
    /** One step without holding (accessibility services click the button, they do not press it). */
    val onSpeedStep: (Int) -> Unit = {},
    val onInclinePress: (Int) -> Unit = {},
    val onInclineRelease: () -> Unit = {},
    val onInclineStep: (Int) -> Unit = {},
    val onSetSpeed: (Double) -> Unit = {},
    val onSetIncline: (Double) -> Unit = {},
    /** Leaves any other screen for the main one (the live data), from the STOP bar. */
    val onBackToMain: () -> Unit = {},
)

/** Actions the UI can request. Keeps the composables free of the ViewModel (easier to capture). */
data class MainActions(
    val onStart: () -> Unit = {},
    val onScan: () -> Unit = {},
    val onStopScan: () -> Unit = {},
    val onConnect: (DiscoveredTreadmill) -> Unit = {},
    val onReconnectRemembered: () -> Unit = {},
    /** Gives up the automatic reconnection and starts looking for treadmills. */
    val onStopReconnecting: () -> Unit = {},
    /** Asks Android to turn Bluetooth on. */
    val onEnableBluetooth: () -> Unit = {},
    val onDismissAlert: () -> Unit = {},
    val onDismissRecordingNotice: () -> Unit = {},
    val onShare: (RecordingInfo) -> Unit = {},
    val onRequestPermissions: () -> Unit = {},
    /** Opens Android's settings of this app (permissions). */
    val onOpenSettings: () -> Unit = {},
    /** Opens the app's own settings screen. */
    val onOpenSettingsScreen: () -> Unit = {},
    /** Opens Android's Downloads (where the debug recordings are saved). */
    val onOpenDownloads: () -> Unit = {},
    val onOpenUsers: () -> Unit = {},
    val onOpenWeight: () -> Unit = {},
    val onOpenHistory: () -> Unit = {},
    /** Opens the editor to personalise the generic profile. */
    val onPersonalize: () -> Unit = {},
    val controls: ControlActions = ControlActions(),
    /** Opens the share sheet for an exported workout file. */
    val onShareExport: (SavedExport) -> Unit = {},
    /** Closes the app's task (after the backend accepted the exit). */
    val onFinish: () -> Unit = {},
    /** Sends the app to the background, keeping it connected. */
    val onMoveToBackground: () -> Unit = {},
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
    /** The notification permission is granted right now (the settings screen shows it even before it was ever asked). */
    val notificationsGranted: Boolean = true,
)
