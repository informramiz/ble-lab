package com.codebyramiz.blelab.ble.protocol

import com.codebyramiz.blelab.ble.model.BleError
import com.google.common.truth.Truth.assertThat
import org.junit.Test

class GattStatusTest {
    @Test
    fun `133 and timeouts are retryable, auth errors are not`() {
        assertThat(GattStatus.isRetryable(GattStatus.GATT_ERROR)).isTrue()
        assertThat(GattStatus.isRetryable(GattStatus.CONNECTION_TIMEOUT)).isTrue()
        assertThat(GattStatus.isRetryable(GattStatus.OPERATION_TIMEOUT)).isTrue()
        assertThat(GattStatus.isRetryable(GattStatus.INSUFFICIENT_AUTHENTICATION)).isFalse()
        assertThat(GattStatus.isRetryable(GattStatus.SUCCESS)).isFalse()
    }

    @Test
    fun `auth and encryption errors need bonding`() {
        assertThat(GattStatus.needsBonding(GattStatus.INSUFFICIENT_AUTHENTICATION)).isTrue()
        assertThat(GattStatus.needsBonding(GattStatus.INSUFFICIENT_ENCRYPTION)).isTrue()
        assertThat(GattStatus.needsBonding(GattStatus.GATT_ERROR)).isFalse()
    }

    @Test
    fun `describes every known code and falls back for unknown ones`() {
        val known = listOf(0, 0x05, 0x08, 0x0F, 0x13, 0x16, 0x22, 0x3E, 0x85, 0x101, -1)
        known.forEach { assertThat(GattStatus.describe(it)).doesNotContain("GATT status") }
        assertThat(GattStatus.describe(0x99)).isEqualTo("GATT status 153")
        assertThat(GattStatus.describe(GattStatus.GATT_ERROR)).contains("133")
    }

    @Test
    fun `errors carry user-facing messages and retry policy`() {
        val errors = listOf(
            BleError.Gatt(0x85), BleError.LinkLost(8), BleError.ServiceNotFound, BleError.BondingFailed,
            BleError.NotConnected, BleError.MissingPermission("p"), BleError.BluetoothOff, BleError.ScanFailed(2),
            BleError.Unexpected("x"),
        )
        errors.forEach { assertThat(it.message).isNotEmpty() }
        assertThat(errors.filter { it.retryable }).containsExactly(BleError.Gatt(0x85), BleError.LinkLost(8), BleError.ScanFailed(2))
    }
}
