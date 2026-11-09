import Foundation

/// A `BLECentral` backed by `SimulatedPeripheral`. `latency` is applied to every GATT operation.
public struct SimulatedCentral: BLECentral {
    public let peripheral: SimulatedPeripheral
    private let latency: Duration
    private let sleeper: any Sleeper

    public init(peripheral: SimulatedPeripheral, latency: Duration = .milliseconds(120), sleeper: any Sleeper = TaskSleeper()) {
        self.peripheral = peripheral
        self.latency = latency
        self.sleeper = sleeper
    }

    public func scan(serviceFilter: UUID?) -> AsyncThrowingStream<ScannedDevice, any Error> {
        let peripheral = peripheral
        let sleeper = sleeper
        return AsyncThrowingStream { continuation in
            let task = Task {
                var rng = SplitMix(seed: 11)
                while !Task.isCancelled {
                    if serviceFilter == nil || serviceFilter == GattProfile.labService {
                        let rssi = -52 + Int(rng.nextUnit() * 8) - 4
                        continuation.yield(ScannedDevice(id: peripheral.id, name: peripheral.name, rssi: rssi, isBleLab: true, isSimulated: true))
                    }
                    do { try await sleeper.sleep(for: .seconds(1)) } catch { break }
                }
                continuation.finish()
            }
            continuation.onTermination = { _ in task.cancel() }
        }
    }

    public func connect(to id: UUID) async throws(BLEError) -> any GattLink {
        guard id == peripheral.id else { throw .connectFailed(reason: "unknown peripheral") }
        try? await sleeper.sleep(for: latency * 3)
        let events = try await peripheral.acceptConnection()
        return SimulatedLink(peripheral: peripheral, events: events, latency: latency, sleeper: sleeper)
    }
}

struct SimulatedLink: GattLink {
    let peripheral: SimulatedPeripheral
    let events: AsyncStream<LinkEvent>
    let latency: Duration
    let sleeper: any Sleeper

    var maximumWriteLength: Int { get async { 244 } }  // ATT MTU 247 minus the 3-byte header.

    func discoverServices() async throws(BLEError) -> Set<AttributeID> {
        try await op { _ in [.custom(GattProfile.labService), .sig(GattProfile.deviceInfoService)] }
    }

    func read(_ characteristic: AttributeID, in service: AttributeID) async throws(BLEError) -> Data {
        try await op { p throws(BLEError) in try await p.read(characteristic) }
    }

    func write(_ value: Data, to characteristic: AttributeID, in service: AttributeID) async throws(BLEError) {
        try await op { p throws(BLEError) in try await p.write(value, to: characteristic) }
    }

    func setNotify(_ enabled: Bool, for characteristic: AttributeID, in service: AttributeID) async throws(BLEError) {
        try await op { p in await p.setNotify(enabled) }
    }

    func disconnect() async { await peripheral.centralDisconnected() }

    private func op<T: Sendable>(_ body: (SimulatedPeripheral) async throws(BLEError) -> T) async throws(BLEError) -> T {
        try? await sleeper.sleep(for: latency)
        guard await peripheral.isConnected() else { throw .notConnected }
        return try await body(peripheral)
    }
}
