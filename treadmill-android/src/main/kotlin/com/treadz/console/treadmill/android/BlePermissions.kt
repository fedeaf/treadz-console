package com.treadz.console.treadmill.android

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat

/**
 * Android 13+ permissions needed for the treadmill. They are only checked here: the request to the user
 * (`ActivityResultContracts.RequestMultiplePermissions`) is made by the UI with [required] and [recommended].
 */
object BlePermissions {

    /** Essential: without them it is impossible to scan or connect. */
    val required: Array<String> = arrayOf(
        Manifest.permission.BLUETOOTH_SCAN,
        Manifest.permission.BLUETOOTH_CONNECT,
    )

    /** Recommended: without it the foreground service notification does not show in the status bar. */
    val recommended: Array<String> = arrayOf(Manifest.permission.POST_NOTIFICATIONS)

    /** The missing essential ones. */
    fun missing(context: Context): List<String> = required.filter { !isGranted(context, it) }

    fun hasAll(context: Context): Boolean = missing(context).isEmpty()

    fun hasScan(context: Context): Boolean = isGranted(context, Manifest.permission.BLUETOOTH_SCAN)

    fun hasConnect(context: Context): Boolean = isGranted(context, Manifest.permission.BLUETOOTH_CONNECT)

    fun hasNotifications(context: Context): Boolean = isGranted(context, Manifest.permission.POST_NOTIFICATIONS)

    private fun isGranted(context: Context, permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
}
