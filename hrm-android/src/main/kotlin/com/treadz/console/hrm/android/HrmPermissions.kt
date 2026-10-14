package com.treadz.console.hrm.android

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat

/**
 * Android 13+ permissions needed for the heart-rate strap (the same as for the treadmill). Only checked here: the UI
 * asks the user for [required].
 */
object HrmPermissions {

    val required: Array<String> = arrayOf(
        Manifest.permission.BLUETOOTH_SCAN,
        Manifest.permission.BLUETOOTH_CONNECT,
    )

    fun missing(context: Context): List<String> = required.filter { !isGranted(context, it) }

    fun hasAll(context: Context): Boolean = missing(context).isEmpty()

    fun hasScan(context: Context): Boolean = isGranted(context, Manifest.permission.BLUETOOTH_SCAN)

    fun hasConnect(context: Context): Boolean = isGranted(context, Manifest.permission.BLUETOOTH_CONNECT)

    private fun isGranted(context: Context, permission: String): Boolean =
        ContextCompat.checkSelfPermission(context, permission) == PackageManager.PERMISSION_GRANTED
}
