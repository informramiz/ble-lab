import Foundation

/// Transfer state the board reports in every OTA status notification.
public enum OtaState: UInt8, Sendable {
    case idle = 0
    case receiving = 1
    /// Image verified and activated; the board restarts about a second later.
    case done = 2
    case error = 3
}

/// Why the board refused or abandoned a transfer. Codes match `ota_error_t` in the firmware.
public enum OtaError: Int, Error, Equatable, Sendable {
    case none = 0
    case badCommand = 1
    case badState = 2
    case tooLarge = 3
    case begin = 4
    case write = 5
    case overflow = 6
    case incomplete = 7
    case hash = 8
    case image = 9
    case aborted = 10
    case badSignature = 11
    case downgrade = 12
    case unknown = -1

    public init(code: UInt8) { self = OtaError(rawValue: Int(code)) ?? .unknown }

    public var message: String {
        switch self {
        case .none: "No error"
        case .badCommand: "The board did not understand the command"
        case .badState: "The board is not in the right state for that command"
        case .tooLarge: "The image is larger than the board's update slot"
        case .begin: "The board could not start the update"
        case .write: "The board failed to write the image to flash"
        case .overflow: "The board received more data than announced"
        case .incomplete: "The board did not receive the whole image"
        case .hash: "The image failed the board's SHA-256 check"
        case .image: "The board rejected the image as not valid firmware"
        case .aborted: "The update was aborted"
        case .badSignature: "The board refused the image: it is unsigned, or signed with a key the board does not trust"
        case .downgrade: "The board refused the image: it is older than the firmware already running"
        case .unknown: "The board reported an unknown error"
        }
    }
}

/// One OTA status notification: `offset` is how many image bytes the board has accepted.
public struct OtaStatus: Equatable, Sendable {
    public let state: OtaState
    public let error: OtaError
    public let offset: Int

    public init(state: OtaState, error: OtaError = .none, offset: Int) {
        self.state = state
        self.error = error
        self.offset = offset
    }
}

/// OTA protocol v2, mirrored from `firmware/ble_lab/gatt_profile.h` and the Android `OtaProtocol`.
///
/// Control (write with response): START = `01` + u32 size LE + 32-byte SHA-256 + u32 version code LE +
/// 64-byte signature, ABORT = `02`, FINISH = `03`. Data (write without response): raw image bytes, in
/// order. Status: u8 state, u8 error, u32 offset LE. The board notifies status on START (offset 0),
/// every `ackInterval` bytes and when the last byte is in; the central stays within `windowBytes`
/// of the last reported offset.
public enum OtaProtocol {
    public static let commandStart: UInt8 = 0x01
    public static let commandAbort: UInt8 = 0x02
    public static let commandFinish: UInt8 = 0x03

    public static let ackInterval = 1024
    public static let windowBytes = 4096
    public static let sha256Length = 32
    public static let signatureLength = 64
    public static let startLength = 1 + 4 + sha256Length + 4 + signatureLength
    public static let statusLength = 6

    public static let abort = Data([commandAbort])
    public static let finish = Data([commandFinish])

    /// The 105-byte START command. Returns nil when a field has the wrong length or is out of the u32 range.
    public static func encodeStart(size: Int, sha256: Data, versionCode: Int, signature: Data) -> Data? {
        guard (1...Int(UInt32.max)).contains(size), (0...Int(UInt32.max)).contains(versionCode),
              sha256.count == sha256Length, signature.count == signatureLength else { return nil }
        var out = Data([commandStart])
        out.append(littleEndian: UInt32(size))
        out.append(sha256)
        out.append(littleEndian: UInt32(versionCode))
        out.append(signature)
        return out
    }

    /// Nil when `data` is not a well-formed status (wrong length or unknown state).
    public static func decodeStatus(_ data: Data) -> OtaStatus? {
        let bytes = [UInt8](data)
        guard bytes.count == statusLength, let state = OtaState(rawValue: bytes[0]) else { return nil }
        let offset = (0..<4).reduce(0) { $0 | Int(bytes[2 + $1]) << (8 * $1) }
        return OtaStatus(state: state, error: OtaError(code: bytes[1]), offset: offset)
    }
}

extension Data {
    mutating func append(littleEndian value: UInt32) {
        for shift in stride(from: 0, to: 32, by: 8) { append(UInt8(truncatingIfNeeded: value >> UInt32(shift))) }
    }
}
