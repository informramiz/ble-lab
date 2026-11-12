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
    /// The board no longer has our bond but this phone still holds the old key (Core Bluetooth's
    /// `peerRemovedPairingInformation`). Retrying cannot work until the phone forgets the board.
    case pairingRemoved
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
        case .pairingRemoved: "The board no longer recognises this iPhone's pairing"
        case .notConnected: "Not connected"
        case .malformedPayload(let expected, let actual): "Payload too short: \(actual) of \(expected) bytes"
        case .unexpected(let detail): detail
        }
    }
}

extension BLEError {
    /// What the user can do about it; nil when there is nothing specific to say.
    public var guidance: String? {
        switch self {
        case .pairingFailed, .insufficientEncryption:
            // iOS asks for the code once per connection, so a retry on the same link gets no prompt.
            "Pairing needs a new connection: go back, connect to the board again and tap the switch. When iOS asks, type the 6-digit code shown on the board's screen. If iOS never asks, forget the board in Settings > Bluetooth first."
        case .pairingRemoved:
            "Open iPhone Settings > Bluetooth, tap the (i) next to \"ble-lab-esp32\", choose Forget This Device, then connect to the board again."
        case .timeout:
            "If no pairing prompt appeared, forget the board in Settings > Bluetooth, reconnect and try again."
        case .unauthorized:
            "Allow Bluetooth for BLE Lab in Settings > Privacy & Security > Bluetooth."
        default:
            nil
        }
    }

    /// `message` plus `guidance`, for text shown to the user after a failed action.
    public var userMessage: String {
        guidance.map { "\(message). \($0)" } ?? message
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
