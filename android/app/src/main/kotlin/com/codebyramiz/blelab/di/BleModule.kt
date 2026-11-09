package com.codebyramiz.blelab.di

import android.content.Context
import com.codebyramiz.blelab.ble.BleScanner
import com.codebyramiz.blelab.ble.BluetoothStateMonitor
import com.codebyramiz.blelab.ble.CompositeBleScanner
import com.codebyramiz.blelab.ble.GattClientFactory
import com.codebyramiz.blelab.ble.RoutingGattClientFactory
import com.codebyramiz.blelab.ble.android.AndroidBleScanner
import com.codebyramiz.blelab.ble.android.AndroidBluetoothStateMonitor
import com.codebyramiz.blelab.ble.android.AndroidGattClientFactory
import com.codebyramiz.blelab.ble.simulated.SimulatedPeripheral
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

/** Wires the real radio next to the always-available demo peripheral. */
@Module
@InstallIn(SingletonComponent::class)
object BleModule {
    @Provides
    @Singleton
    fun demoPeripheral(): SimulatedPeripheral = SimulatedPeripheral()

    @Provides
    @Singleton
    fun scanner(@ApplicationContext context: Context, demo: SimulatedPeripheral): BleScanner = CompositeBleScanner(AndroidBleScanner(context), demo.scanner())

    @Provides
    @Singleton
    fun gattClientFactory(@ApplicationContext context: Context, demo: SimulatedPeripheral): GattClientFactory = RoutingGattClientFactory(AndroidGattClientFactory(context), demo)

    @Provides
    @Singleton
    fun bluetoothState(@ApplicationContext context: Context): BluetoothStateMonitor = AndroidBluetoothStateMonitor(context)
}
