package com.codebyramiz.blelab.permissions

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat

/**
 * Runtime permissions for scanning and connecting, per
 * https://developer.android.com/develop/connectivity/bluetooth/bt-permissions
 * API 31+: BLUETOOTH_SCAN (declared neverForLocation) + BLUETOOTH_CONNECT.
 * API 26-30: ACCESS_FINE_LOCATION, which the platform requires for BLE scan results.
 */
object BlePermissions {
    // The API 31 constants are only returned on API 31+, so inlining them is safe.
    @SuppressLint("InlinedApi")
    fun required(sdkInt: Int = Build.VERSION.SDK_INT): List<String> = if (sdkInt >= Build.VERSION_CODES.S) {
        listOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
    } else {
        listOf(Manifest.permission.ACCESS_FINE_LOCATION)
    }

    fun usesLocation(sdkInt: Int = Build.VERSION.SDK_INT): Boolean = sdkInt < Build.VERSION_CODES.S

    fun granted(context: Context): Boolean = required().all { ContextCompat.checkSelfPermission(context, it) == PackageManager.PERMISSION_GRANTED }
}
