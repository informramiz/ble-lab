import Foundation
import Testing
@testable import BLELabKit

/// A central whose scan results the test pushes by hand.
final class ScriptedCentral: BLECentral, @unchecked Sendable {
    private let lock = NSLock()
    private var continuation: AsyncThrowingStream<ScannedDevice, any Error>.Continuation?
    private(set) var filters: [UUID?] = []

    func scan(serviceFilter: UUID?) -> AsyncThrowingStream<ScannedDevice, any Error> {
        let (stream, continuation) = AsyncThrowingStream<ScannedDevice, any Error>.makeStream()
        lock.withLock {
            filters.append(serviceFilter)
            self.continuation = continuation
        }
        return stream
    }

    func emit(_ device: ScannedDevice) { _ = lock.withLock { continuation }?.yield(device) }
    func fail(_ error: BLEError) { lock.withLock { continuation }?.finish(throwing: error) }

    func connect(to id: UUID) async throws(BLEError) -> any GattLink { throw .connectFailed(reason: "scripted") }
}

/// Never returns until cancelled, so the scan window stays open for the whole test.
struct ForeverSleeper: Sleeper {
    func sleep(for duration: Duration) async throws { try await Task.sleep(for: .seconds(3600)) }
}

@MainActor
@Suite struct ScanModelTests {
    let central = ScriptedCentral()
    let a = UUID(), b = UUID(), c = UUID()

    @Test func sortsLabDevicesFirstThenByRssiAndKeepsNames() async {
        let model = ScanModel(central: central, sleeper: ForeverSleeper())
        model.start()
        central.emit(ScannedDevice(id: a, name: "Band", rssi: -40, isBleLab: false))
        central.emit(ScannedDevice(id: b, name: "ble-lab-esp32", rssi: -70, isBleLab: true))
        central.emit(ScannedDevice(id: c, name: "Other lab", rssi: -50, isBleLab: true))
        central.emit(ScannedDevice(id: b, name: nil, rssi: -45, isBleLab: true))
        #expect(await waitUntil { model.devices.count == 3 && model.devices.first?.rssi == -45 })
        #expect(model.devices.map(\.id) == [b, c, a])
        #expect(model.devices.first?.name == "ble-lab-esp32", "a later packet without a name keeps the known name")
    }

    @Test func stopsAfterScanWindow() async {
        let model = ScanModel(central: central, duration: .seconds(10), sleeper: RecordingSleeper())
        model.start()
        #expect(model.isScanning)
        #expect(await waitUntil { !model.isScanning })
    }

    @Test func filterDefaultsToLabServiceAndTogglingRestarts() async {
        let model = ScanModel(central: central, sleeper: ForeverSleeper())
        model.start()
        model.labOnly = false
        #expect(central.filters == [GattProfile.labService, nil])
    }

    @Test func surfacesScanErrors() async {
        let model = ScanModel(central: central, sleeper: ForeverSleeper())
        model.start()
        central.fail(.bluetoothUnavailable(reason: "Bluetooth is off"))
        #expect(await waitUntil { model.error != nil })
        #expect(model.error == .bluetoothUnavailable(reason: "Bluetooth is off"))
        #expect(!model.isScanning)
    }
}

@MainActor
@Suite struct DeviceModelTests {
    let peripheral = SimulatedPeripheral()

    func makeModel() -> DeviceModel {
        let manager = ConnectionManager(
            central: SimulatedCentral(peripheral: peripheral, latency: .zero, sleeper: RecordingSleeper()),
            sleeper: RecordingSleeper(),
            random: { 0.5 }
        )
        let device = ScannedDevice(id: peripheral.id, name: peripheral.name, rssi: -50, isBleLab: true, isSimulated: true)
        return DeviceModel(device: device, manager: manager)
    }

    @Test func chartsTelemetryInARollingWindow() async {
        let model = makeModel()
        model.start()
        #expect(await waitUntil { model.state.isReady })
        for _ in 0..<(DeviceModel.window + 10) { await peripheral.pushTelemetry() }
        #expect(await waitUntil { model.samples.last?.sequence == UInt32(DeviceModel.window + 9) })
        #expect(model.samples.count == DeviceModel.window)
        #expect(model.samples.first?.sequence == 10)
        #expect(model.stats != nil)
    }

    @Test func ledWriteIsLoggedAndConfirmed() async {
        let model = makeModel()
        model.start()
        #expect(await waitUntil { model.state.isReady })
        await model.setLED(true)
        #expect(model.ledOn)
        #expect(!model.ledPending)
        #expect(model.log.last == "LED on")
    }

    @Test func declinedPairingRollsBackLed() async {
        await peripheral.configure(requireBonding: true, acceptBonding: false)
        let model = makeModel()
        model.start()
        #expect(await waitUntil { model.state.isReady })
        await model.setLED(true)
        #expect(!model.ledOn)
        #expect(model.writeError == .pairingFailed)
        #expect(model.log.last?.hasPrefix("LED write failed") == true)
    }

    @Test func logsStateTransitions() {
        let model = makeModel()
        model.observe(.discovering)
        model.observe(.discovering)
        model.observe(.ready(info: DeviceInfo(), maxWriteLength: 244))
        model.observe(.failed(.timeout))
        #expect(model.log == ["Connected, discovering services", "Ready: 244-byte writes, notifications on", "Failed: The peripheral did not answer in time"])
    }

    @Test func cannotWriteBeforeReady() async {
        let model = makeModel()
        #expect(!model.canWrite)
        await model.setLED(true)
        #expect(!model.ledOn)
    }
}

@Suite struct CompositeCentralTests {
    @Test func routesDemoIdToSimulatorAndOthersToHardware() async throws {
        let peripheral = SimulatedPeripheral()
        let composite = CompositeCentral(
            hardware: ScriptedCentral(),
            demo: SimulatedCentral(peripheral: peripheral, latency: .zero, sleeper: RecordingSleeper())
        )
        _ = try await composite.connect(to: peripheral.id)
        #expect(await peripheral.connectAttempts == 1)
        await #expect(throws: BLEError.connectFailed(reason: "scripted")) { _ = try await composite.connect(to: UUID()) }
    }

    @Test func mergesScanResults() async throws {
        let hardware = ScriptedCentral()
        let composite = CompositeCentral(
            hardware: hardware,
            demo: SimulatedCentral(peripheral: SimulatedPeripheral(), latency: .zero, sleeper: ForeverSleeper())
        )
        var iterator = composite.scan(serviceFilter: nil).makeAsyncIterator()
        let first = try await iterator.next()
        #expect(first?.isSimulated == true)
        let real = ScannedDevice(id: UUID(), name: "ble-lab-esp32", rssi: -60, isBleLab: true)
        for _ in 0..<50 where hardware.filters.isEmpty { await Task.yield() }
        hardware.emit(real)
        #expect(try await iterator.next() == real)
    }
}

@MainActor
@Suite struct DeviceModelBoardTests {
    let peripheral = SimulatedPeripheral()
    let repository = InMemoryRecordingRepository()

    func makeModel() -> DeviceModel {
        let manager = ConnectionManager(
            central: SimulatedCentral(peripheral: peripheral, latency: .zero, sleeper: RecordingSleeper()),
            sleeper: RecordingSleeper(),
            random: { 0.5 }
        )
        let device = ScannedDevice(id: peripheral.id, name: peripheral.name, rssi: -50, isBleLab: true, isSimulated: true)
        return DeviceModel(device: device, manager: manager, recorder: SessionRecorder(repository: repository, batchSize: 2))
    }

    @Test func exposesMotionTiltTouchAndFlags() async {
        let model = makeModel()
        model.start()
        #expect(await waitUntil { model.state.isReady })
        await peripheral.pushTelemetry()
        await peripheral.pushMotion()
        await peripheral.touch(x: 10, y: 20, kind: .tap)
        #expect(await waitUntil { !model.motion.isEmpty && !model.touches.isEmpty && model.latest != nil })
        #expect(model.hasMotionSensor && model.hasTouchScreen)
        #expect(model.tilt != nil)
        #expect(model.touches.last?.x == 10)
    }

    @Test func messageIsSentAndReported() async {
        let model = makeModel()
        model.start()
        #expect(await waitUntil { model.state.isReady })
        await model.sendMessage("  hello  ")
        #expect(model.messageStatus == .shown("hello"))
        #expect(await peripheral.messages == ["hello"])
    }

    @Test func retryAfterPairingFailureClearsTheStaleError() async {
        await peripheral.configure(requireBonding: true, acceptBonding: false)
        let model = makeModel()
        model.start()
        #expect(await waitUntil { model.state.isReady })
        await model.sendMessage("hi")
        #expect(model.messageStatus == .failed(BLEError.pairingFailed.message))
        await peripheral.configure(requireBonding: true, acceptBonding: true)
        await model.sendMessage("hi")
        #expect(model.messageStatus == .shown("hi"))
        #expect(await peripheral.messages == ["hi"])
    }

    @Test func recordingSavesSessionWithSamples() async {
        let model = makeModel()
        model.start()
        #expect(await waitUntil { model.state.isReady })
        await model.toggleRecording()
        #expect(model.isRecording)
        await peripheral.pushTelemetry()
        #expect(await waitUntil { model.recorder?.state != .recording(sessionId: 1, startedAtMillis: 0, telemetryCount: 0, motionCount: 0) && (model.samples.count > 0) })
        await model.toggleRecording()
        #expect(!model.isRecording)
        let sessions = await repository.sessions()
        #expect(sessions.count == 1 && sessions[0].telemetryCount >= 1 && sessions[0].endedAtMillis != nil)
    }
}
