package com.treadz.console

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.content.ActivityNotFoundException
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.lifecycleScope
import com.treadz.console.lifecycle.finishOnExit
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.runtime.DisposableEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.treadz.console.treadmill.RecordingInfo
import com.treadz.console.userdata.export.SavedExport
import com.treadz.console.ui.BluetoothPermission
import com.treadz.console.ui.MainActions
import com.treadz.console.ui.PermissionsUi
import com.treadz.console.ui.TreadZRoot

class MainActivity : ComponentActivity() {

    private val dependencies: AppDependencies
        get() = (application as TreadZApplication).dependencies

    private val viewModel: MainViewModel by viewModels {
        object : ViewModelProvider.Factory {
            @Suppress("UNCHECKED_CAST")
            override fun <T : ViewModel> create(modelClass: Class<T>): T =
                MainViewModel(dependencies.controller, dependencies.recorder, dependencies.settings, dependencies.userData, dependencies.appLifecycle) as T
        }
    }

    private val bluetoothPermissions = arrayOf(
        Manifest.permission.BLUETOOTH_SCAN,
        Manifest.permission.BLUETOOTH_CONNECT,
    )

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        // An accepted exit (the Back dialog, or Exit in the notification) closes the task. Collected for the whole life
        // of the activity, not only while it is visible: the notification's Exit also reaches a stopped activity.
        finishOnExit(lifecycleScope, dependencies.appLifecycle, ::finishAndRemoveTask)
        setContent {
            var permissions by remember { mutableStateOf(currentPermissions(asked = false)) }
            var asked by rememberSaveable { mutableStateOf(false) }

            val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestMultiplePermissions()) {
                asked = true
                permissions = currentPermissions(asked = true)
            }
            val request = { launcher.launch(bluetoothPermissions + Manifest.permission.POST_NOTIFICATIONS) }

            // The result does not matter: the app watches the adapter state by itself.
            val enableBluetoothLauncher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {}
            val enableBluetooth: () -> Unit = {
                try {
                    enableBluetoothLauncher.launch(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE))
                } catch (_: ActivityNotFoundException) {
                    runCatching { startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)) }
                }
            }

            // Requests the permissions the first time the app opens; checks them again when coming back from settings.
            LaunchedEffect(Unit) {
                if (permissions.bluetooth != BluetoothPermission.Granted) request()
            }
            val lifecycleOwner = LocalLifecycleOwner.current
            DisposableEffect(lifecycleOwner) {
                val observer = LifecycleEventObserver { _, event ->
                    if (event == Lifecycle.Event.ON_RESUME) permissions = currentPermissions(asked)
                }
                lifecycleOwner.lifecycle.addObserver(observer)
                onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
            }
            LaunchedEffect(permissions.bluetooth) {
                if (permissions.bluetooth == BluetoothPermission.Granted) viewModel.onPermissionsGranted()
            }

            TreadZRoot(
                dependencies = dependencies,
                viewModel = viewModel,
                permissions = permissions,
                actions = MainActions(
                    onShare = ::share,
                    onShareExport = ::shareExport,
                    onRequestPermissions = request,
                    onEnableBluetooth = enableBluetooth,
                    onOpenSettings = ::openAppSettings,
                    onFinish = ::finishAndRemoveTask,
                    onMoveToBackground = { moveTaskToBack(true) },
                ),
            )
        }
    }

    private fun granted(permission: String) =
        ContextCompat.checkSelfPermission(this, permission) == PackageManager.PERMISSION_GRANTED

    private fun currentPermissions(asked: Boolean): PermissionsUi {
        val bluetooth = when {
            bluetoothPermissions.all(::granted) -> BluetoothPermission.Granted
            !asked -> BluetoothPermission.NeedsRequest
            bluetoothPermissions.any { shouldShowRequestPermissionRationale(it) } -> BluetoothPermission.Denied
            else -> BluetoothPermission.DeniedPermanently
        }
        // Only warn about notifications once they have been requested and denied.
        val notificationsDenied = asked && !granted(Manifest.permission.POST_NOTIFICATIONS)
        return PermissionsUi(bluetooth, notificationsDenied)
    }

    private fun share(recording: RecordingInfo) {
        startActivity(dependencies.shareIntent(recording))
    }

    private fun shareExport(export: SavedExport) {
        startActivity(ExportShare.intent(export, getString(R.string.export_share_title)))
    }

    private fun openAppSettings() {
        startActivity(
            Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.fromParts("package", packageName, null)),
        )
    }
}
