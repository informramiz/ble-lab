import Foundation

/// Real radio plus the demo device: scan results are merged and `connect` routes by identifier,
/// so the demo device works on a phone with no ESP32 nearby.
public struct CompositeCentral: BLECentral {
    private let hardware: any BLECentral
    private let demo: SimulatedCentral

    public init(hardware: any BLECentral, demo: SimulatedCentral) {
        self.hardware = hardware
        self.demo = demo
    }

    public func scan(serviceFilter: UUID?) -> AsyncThrowingStream<ScannedDevice, any Error> {
        let sources = [demo.scan(serviceFilter: serviceFilter), hardware.scan(serviceFilter: serviceFilter)]
        return AsyncThrowingStream { continuation in
            let task = Task {
                await withTaskGroup(of: (any Error)?.self) { group in
                    for source in sources {
                        group.addTask {
                            do {
                                for try await device in source { continuation.yield(device) }
                                return nil
                            } catch {
                                return error
                            }
                        }
                    }
                    // A hardware failure (Bluetooth off, denied) ends the scan; the caller shows why.
                    for await error in group {
                        if let error {
                            continuation.finish(throwing: error)
                            group.cancelAll()
                            return
                        }
                    }
                    continuation.finish()
                }
            }
            continuation.onTermination = { _ in task.cancel() }
        }
    }

    public func connect(to id: UUID) async throws(BLEError) -> any GattLink {
        if id == demo.peripheral.id { return try await demo.connect(to: id) }
        return try await hardware.connect(to: id)
    }
}
