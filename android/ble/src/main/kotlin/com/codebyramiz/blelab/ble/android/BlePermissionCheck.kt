package com.codebyramiz.blelab.ble.android

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.os.Build
import androidx.core.content.ContextCompat
import com.codebyramiz.blelab.ble.model.BleError
import com.codebyramiz.blelab.ble.model.BleException

/** Runtime permissions per https://developer.android.com/develop/connectivity/bluetooth/bt-permissions */
internal object BlePermissionCheck {
    fun requireScan(context: Context) = require(context, if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) Manifest.permission.BLUETOOTH_SCAN else Manifest.permission.ACCESS_FINE_LOCATION)

    fun requireConnect(context: Context) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) require(context, Manifest.permission.BLUETOOTH_CONNECT)
    }

    private fun require(context: Context, permission: String) {
        if (ContextCompat.checkSelfPermission(context, permission) != PackageManager.PERMISSION_GRANTED) {
            throw BleException(BleError.MissingPermission(permission))
        }
    }
}
