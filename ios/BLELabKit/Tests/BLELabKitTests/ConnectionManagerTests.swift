import Foundation
import Testing
@testable import BLELabKit

@MainActor
@Suite struct ConnectionManagerTests {
    let sleeper = RecordingSleeper()
    let peripheral = SimulatedPeripheral()

    func makeManager(maxAttempts: Int = 6) -> ConnectionManager {
        ConnectionManager(
            central: SimulatedCentral(peripheral: peripheral, latency: .zero, sleeper: RecordingSleeper()),
            backoff: BackoffPolicy(maxAttempts: maxAttempts),
            sleeper: sleeper,
            random: { 0.5 }
        )
    }

    @Test func connectsReadsDeviceInfoAndBecomesReady() async {
        let manager = makeManager()
        manager.connect(to: SimulatedPeripheral.demoID)
        #expect(await waitUntil { manager.state.isReady })
        let expected = DeviceInfo(manufacturer: "Espressif", model: "ESP32-WROOM-32", firmware: "ble-lab 1.0.0")
        #expect(manager.state == .ready(info: expected, maxWriteLength: 244))
    }

    @Test func streamsDecodedTelemetry() async {
        let manager = makeManager()
        manager.connect(to: SimulatedPeripheral.demoID)
        #expect(await waitUntil { manager.state.isReady })
        #expect(await peripheral.pushTelemetry())
        await peripheral.pushTelemetry()
        var iterator = manager.telemetry.makeAsyncIterator()
        let first = await iterator.next()
        let second = await iterator.next()
        #expect(first?.sequence == 0)
        #expect(second?.sequence == 1)
        #expect((20...28).contains(first?.value ?? 0))
    }

    @Test func writesLed() async throws {
        let manager = makeManager()
        manager.connect(to: SimulatedPeripheral.demoID)
        #expect(await waitUntil { manager.state.isReady })
        try await manager.setLED(true)
        #expect(await peripheral.ledOn)
        #expect(await peripheral.ledWrites == [Data([1])])
    }

    @Test func declinedPairingSurfacesAsPairingFailed() async {
        await peripheral.configure(requireBonding: true, acceptBonding: false)
        let manager = makeManager()
        manager.connect(to: SimulatedPeripheral.demoID)
        #expect(await waitUntil { manager.state.isReady })
        await #expect(throws: BLEError.pairingFailed) { try await manager.setLED(true) }
        #expect(await peripheral.ledOn == false)
        #expect(manager.state.isReady, "a rejected write must not drop the link")
    }

    @Test func acceptedPairingBondsThenWrites() async throws {
        await peripheral.configure(requireBonding: true, acceptBonding: true)
        let manager = makeManager()
        manager.connect(to: SimulatedPeripheral.demoID)
        #expect(await waitUntil { manager.state.isReady })
        try await manager.setLED(true)
        #expect(await peripheral.bonded)
    }

    @Test func retriesTransientConnectFailuresWithBackoff() async {
        await peripheral.configure(connectFailures: 2)
        let manager = makeManager()
        manager.connect(to: SimulatedPeripheral.demoID)
        #expect(await waitUntil { manager.state.isReady })
        #expect(await peripheral.connectAttempts == 3)
        // Attempts 2 and 3 wait 1 s and 2 s (500 ms x 2^(n-1), jitter centred).
        #expect(await sleeper.delays == [.seconds(1), .seconds(2)])
    }

    @Test func givesUpAfterMaxAttempts() async {
        await peripheral.configure(connectFailures: 100)
        let manager = makeManager(maxAttempts: 3)
        manager.connect(to: SimulatedPeripheral.demoID)
        #expect(await waitUntil { manager.state.isFailed })
        #expect(manager.state == .failed(.connectFailed(reason: "peripheral did not respond")))
        #expect(await peripheral.connectAttempts == 3)
    }

    @Test func reconnectsAfterLinkLossAndResetsAttempts() async {
        let manager = makeManager()
        manager.connect(to: SimulatedPeripheral.demoID)
        #expect(await waitUntil { manager.state.isReady })
        await peripheral.dropLink()
        #expect(await waitUntil { !manager.state.isReady })
        #expect(await waitUntil { manager.state.isReady })
        #expect(await peripheral.connectAttempts == 2)
        #expect(await sleeper.delays == [.milliseconds(500)], "backoff restarts from the first step after a Ready session")
    }

    @Test func disconnectReturnsToIdleAndStopsRetrying() async {
        let manager = makeManager()
        manager.connect(to: SimulatedPeripheral.demoID)
        #expect(await waitUntil { manager.state.isReady })
        await manager.disconnect()
        #expect(manager.state == .idle)
        for _ in 0..<200 { await Task.yield() }
        #expect(manager.state == .idle)
        #expect(await peripheral.connectAttempts == 1)
    }

    @Test func ledWriteRequiresReady() async {
        let manager = makeManager()
        await #expect(throws: BLEError.notConnected) { try await manager.setLED(true) }
    }

    @Test func unknownPeripheralFailsAfterRetries() async {
        let manager = makeManager(maxAttempts: 2)
        manager.connect(to: UUID())
        #expect(await waitUntil { manager.state.isFailed })
        #expect(manager.state == .failed(.connectFailed(reason: "unknown peripheral")))
    }
}

@Suite struct SimulatedCentralTests {
    @Test func scanFindsDemoDeviceOnlyForLabFilter() async throws {
        let central = SimulatedCentral(peripheral: SimulatedPeripheral(), latency: .zero, sleeper: RecordingSleeper())
        var iterator = central.scan(serviceFilter: GattProfile.labService).makeAsyncIterator()
        let device = try await iterator.next()
        #expect(device?.isBleLab == true)
        #expect(device?.isSimulated == true)
        #expect(device?.id == SimulatedPeripheral.demoID)
    }

    @Test func operationsFailAfterDisconnect() async throws {
        let peripheral = SimulatedPeripheral()
        let link = try await SimulatedCentral(peripheral: peripheral, latency: .zero, sleeper: RecordingSleeper()).connect(to: peripheral.id)
        await link.disconnect()
        await #expect(throws: BLEError.notConnected) { try await link.discoverServices() }
    }
}

@Suite struct ErrorMappingTests {
    @Test func onlyTransientErrorsRetry() {
        #expect(BLEError.linkLost(reason: "x").isRetryable)
        #expect(BLEError.timeout.isRetryable)
        #expect(BLEError.connectFailed(reason: "x").isRetryable)
        #expect(!BLEError.serviceNotFound.isRetryable)
        #expect(!BLEError.pairingFailed.isRetryable)
        #expect(!BLEError.unauthorized.isRetryable)
    }
}
