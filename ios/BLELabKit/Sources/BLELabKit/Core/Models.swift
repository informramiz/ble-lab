import Foundation

/// Typed failures. Core Bluetooth errors are mapped into these by the adapter, so the
/// state machine never sees NSError.
public enum BLEError: Error, Equatable, Sendable {
    case bluetoothUnavailable(reason: String)
    case unauthorized
    case connectFailed(reason: String)
    case linkLost(reason: String)
    case timeout
    case serviceNotFound
    case insufficientEncryption
    case pairingFailed
    case notConnected
    case malformedPayload(expected: Int, actual: Int)
    case unexpected(String)

    /// Transient radio problems are retried with backoff; configuration problems are not.
    public var isRetryable: Bool {
        switch self {
        case .connectFailed, .linkLost, .timeout: true
        default: false
        }
    }

    public var message: String {
        switch self {
        case .bluetoothUnavailable(let reason): "Bluetooth unavailable: \(reason)"
        case .unauthorized: "Bluetooth permission denied"
        case .connectFailed(let reason): "Connect failed: \(reason)"
        case .linkLost(let reason): "Connection lost: \(reason)"
        case .timeout: "The peripheral did not answer in time"
        case .serviceNotFound: "This device does not expose the BLE Lab service"
        case .insufficientEncryption: "The peripheral requires an encrypted link"
        case .pairingFailed: "Pairing was rejected or failed"
        case .notConnected: "Not connected"
        case .malformedPayload(let expected, let actual): "Payload too short: \(actual) of \(expected) bytes"
        case .unexpected(let detail): detail
        }
    }
}

public struct DeviceInfo: Equatable, Sendable {
    public var manufacturer: String?
    public var model: String?
    public var firmware: String?

    public init(manufacturer: String? = nil, model: String? = nil, firmware: String? = nil) {
        self.manufacturer = manufacturer
        self.model = model
        self.firmware = firmware
    }
}

public struct ScannedDevice: Identifiable, Equatable, Sendable {
    public let id: UUID
    public var name: String?
    public var rssi: Int
    public var isBleLab: Bool
    public var isSimulated: Bool

    public init(id: UUID, name: String?, rssi: Int, isBleLab: Bool, isSimulated: Bool = false) {
        self.id = id
        self.name = name
        self.rssi = rssi
        self.isBleLab = isBleLab
        self.isSimulated = isSimulated
    }
}

/// The connection state machine, as the UI sees it. Mirrors the Android ConnectionState.
public enum ConnectionState: Equatable, Sendable {
    case idle
    case connecting(attempt: Int)
    /// Connected; discovering services, reading device info, subscribing.
    case discovering
    /// Ready. `maxWriteLength` is what iOS negotiated (iOS owns the MTU exchange).
    case ready(info: DeviceInfo, maxWriteLength: Int)
    case reconnecting(attempt: Int, delay: Duration, cause: BLEError)
    case failed(BLEError)
}
