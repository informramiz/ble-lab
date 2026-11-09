package com.codebyramiz.blelab.ble

import com.codebyramiz.blelab.ble.model.BleError
import com.codebyramiz.blelab.ble.model.BleException
import com.codebyramiz.blelab.ble.model.ScanUpdate
import com.codebyramiz.blelab.ble.simulated.SimulatedPeripheral
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.merge
import java.util.UUID

/** Real radio plus the demo device, so the app always has something to connect to. */
class CompositeBleScanner(private val real: BleScanner, private val demo: BleScanner) : BleScanner {
    override fun scan(serviceFilter: UUID?): Flow<ScanUpdate> = merge(
        real.scan(serviceFilter).catch { e ->
            emit(ScanUpdate.Failed((e as? BleException)?.error ?: BleError.Unexpected(e.message ?: "Scan error")))
        },
        demo.scan(serviceFilter),
    )
}

/** Sends the demo address to the simulator and everything else to the real stack. */
class RoutingGattClientFactory(
    private val real: GattClientFactory,
    private val demo: SimulatedPeripheral,
) : GattClientFactory {
    override fun create(address: String): GattClient = if (address == demo.address) demo.newClient() else real.create(address)
}
