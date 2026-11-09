import Foundation

/// What the radio can do, independent of Core Bluetooth. The app ships two implementations:
/// `CoreBluetoothCentral` for real hardware and `SimulatedCentral` for the simulator, tests and demo mode.
public protocol BLECentral: Sendable {
    /// Scans until the stream is cancelled. `serviceFilter` nil means every advertiser.
    func scan(serviceFilter: UUID?) -> AsyncThrowingStream<ScannedDevice, any Error>
    /// Connects and returns a link. Throws `BLEError`.
    func connect(to id: UUID) async throws(BLEError) -> any GattLink
}

public enum LinkEvent: Equatable, Sendable {
    case notification(AttributeID, Data)
    case disconnected(BLEError)
}

/// One connected peripheral. Every call is a single GATT operation.
public protocol GattLink: Sendable {
    var events: AsyncStream<LinkEvent> { get }
    /// Largest payload for a write with response; iOS negotiates the ATT MTU itself.
    var maximumWriteLength: Int { get async }
    /// Largest payload for one write without response (`maximumWriteValueLength(for: .withoutResponse)` on Core Bluetooth).
    var maximumWriteWithoutResponseLength: Int { get async }
    func discoverServices() async throws(BLEError) -> Set<AttributeID>
    func read(_ characteristic: AttributeID, in service: AttributeID) async throws(BLEError) -> Data
    func write(_ value: Data, to characteristic: AttributeID, in service: AttributeID) async throws(BLEError)
    /// Returns once the bytes are handed to the radio; there is no acknowledgement. Implementations must
    /// not drop data when the radio's transmit queue is full.
    func writeWithoutResponse(_ value: Data, to characteristic: AttributeID, in service: AttributeID) async throws(BLEError)
    func setNotify(_ enabled: Bool, for characteristic: AttributeID, in service: AttributeID) async throws(BLEError)
    func disconnect() async
}

/// Waits between retries. Injected so tests run in zero wall time.
public protocol Sleeper: Sendable {
    func sleep(for duration: Duration) async throws
}

public struct TaskSleeper: Sleeper {
    public init() {}
    public func sleep(for duration: Duration) async throws { try await Task.sleep(for: duration) }
}
