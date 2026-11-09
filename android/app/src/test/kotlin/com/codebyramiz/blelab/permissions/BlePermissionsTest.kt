package com.codebyramiz.blelab.permissions

import android.Manifest
import android.os.Build
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class BlePermissionsTest {
    @Test
    fun `API 31 and later ask for scan and connect, not location`() {
        assertThat(BlePermissions.required(Build.VERSION_CODES.S))
            .containsExactly(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        assertThat(BlePermissions.usesLocation(Build.VERSION_CODES.S)).isFalse()
    }

    @Test
    fun `API 30 and earlier need fine location to see scan results`() {
        assertThat(BlePermissions.required(Build.VERSION_CODES.R)).containsExactly(Manifest.permission.ACCESS_FINE_LOCATION)
        assertThat(BlePermissions.usesLocation(Build.VERSION_CODES.O)).isTrue()
    }
}
