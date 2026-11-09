import Foundation

/// One IMU reading. Acceleration in g, angular rate in degrees per second.
public struct MotionSample: Equatable, Sendable {
    /// Board clock in milliseconds (wraps every 49 days); only differences are meaningful.
    public let boardMillis: UInt32
    public let ax: Float, ay: Float, az: Float
    public let gx: Float, gy: Float, gz: Float

    public init(boardMillis: UInt32, ax: Float, ay: Float, az: Float, gx: Float, gy: Float, gz: Float) {
        self.boardMillis = boardMillis
        self.ax = ax; self.ay = ay; self.az = az
        self.gx = gx; self.gy = gy; self.gz = gz
    }
}

public enum TouchKind: Equatable, Sendable { case tap, longPress }

/// A touch on the board's screen (320 x 240 px on the CoreS3).
public struct TouchEvent: Equatable, Sendable {
    /// Wrapping event counter; gaps mean missed notifications.
    public let index: Int
    public let kind: TouchKind
    /// True when the firmware's serial test console produced it instead of a finger.
    public let injected: Bool
    public let x: Int
    public let y: Int

    public init(index: Int, kind: TouchKind, injected: Bool, x: Int, y: Int) {
        self.index = index
        self.kind = kind
        self.injected = injected
        self.x = x
        self.y = y
    }
}

private extension Data {
    func le16(_ offset: Int) -> UInt16 { UInt16(self[startIndex + offset]) | UInt16(self[startIndex + offset + 1]) << 8 }
    func le32(_ offset: Int) -> UInt32 { UInt32(le16(offset)) | UInt32(le16(offset + 2)) << 16 }
    func leInt16(_ offset: Int) -> Int16 { Int16(bitPattern: le16(offset)) }

    mutating func appendLE16(_ value: UInt16) { append(contentsOf: [UInt8(value & 0xFF), UInt8(value >> 8)]) }
    mutating func appendLE32(_ value: UInt32) {
        appendLE16(UInt16(value & 0xFFFF))
        appendLE16(UInt16(value >> 16))
    }
}

/// Motion characteristic: a batch of samples per notification, little-endian.
///
/// Header, 8 bytes: `u32 t_ms` (board clock at the first sample), `u16 first index`, `u8 count`,
/// `u8 period_ms`. Then `count` x 12 bytes: `i16 ax, ay, az` in milli-g and `i16 gx, gy, gz` in 0.1 deg/s.
public enum MotionCodec {
    public static let headerSize = 8
    public static let sampleSize = 12

    /// Returns the samples of one notification; a truncated batch yields only its complete samples.
    public static func decode(_ data: Data) throws(BLEError) -> [MotionSample] {
        guard data.count >= headerSize else { throw .malformedPayload(expected: headerSize, actual: data.count) }
        let start = data.le32(0)
        let count = Int(data[data.startIndex + 6])
        let period = UInt32(data[data.startIndex + 7])
        let complete = min(count, (data.count - headerSize) / sampleSize)
        return (0..<complete).map { i in
            let o = headerSize + i * sampleSize
            return MotionSample(
                boardMillis: start &+ UInt32(i) &* period,
                ax: Float(data.leInt16(o)) / 1000, ay: Float(data.leInt16(o + 2)) / 1000, az: Float(data.leInt16(o + 4)) / 1000,
                gx: Float(data.leInt16(o + 6)) / 10, gy: Float(data.leInt16(o + 8)) / 10, gz: Float(data.leInt16(o + 10)) / 10
            )
        }
    }

    /// Inverse of `decode`, for the simulated board and tests. All samples share `periodMillis`.
    public static func encode(_ samples: [MotionSample], index: Int, periodMillis: Int) -> Data {
        var data = Data()
        data.appendLE32(samples.first?.boardMillis ?? 0)
        data.appendLE16(UInt16(truncatingIfNeeded: index))
        data.append(UInt8(truncatingIfNeeded: samples.count))
        data.append(UInt8(truncatingIfNeeded: periodMillis))
        for s in samples {
            for (value, scale) in [(s.ax, 1000), (s.ay, 1000), (s.az, 1000), (s.gx, 10), (s.gy, 10), (s.gz, 10)] as [(Float, Float)] {
                let raw = Int16(max(Float(Int16.min), min(Float(Int16.max), (value * scale).rounded())))
                data.appendLE16(UInt16(bitPattern: raw))
            }
        }
        return data
    }
}

/// Touch characteristic, 8 bytes little-endian: `u16 index`, `u8 kind` (1 tap, 2 long press),
/// `u8 flags` (bit 0 injected from the serial console), `u16 x`, `u16 y` in screen pixels.
public enum TouchCodec {
    public static let size = 8
    private static let injectedFlag: UInt8 = 0x01

    /// Returns nil for an unknown kind, so newer firmware adding gestures cannot break an older app.
    public static func decode(_ data: Data) throws(BLEError) -> TouchEvent? {
        guard data.count >= size else { throw .malformedPayload(expected: size, actual: data.count) }
        let kind: TouchKind
        switch data[data.startIndex + 2] {
        case 1: kind = .tap
        case 2: kind = .longPress
        default: return nil
        }
        return TouchEvent(
            index: Int(data.le16(0)),
            kind: kind,
            injected: data[data.startIndex + 3] & injectedFlag != 0,
            x: Int(data.le16(4)),
            y: Int(data.le16(6))
        )
    }

    public static func encode(_ event: TouchEvent) -> Data {
        var data = Data()
        data.appendLE16(UInt16(truncatingIfNeeded: event.index))
        data.append(event.kind == .tap ? 1 : 2)
        data.append(event.injected ? injectedFlag : 0)
        data.appendLE16(UInt16(truncatingIfNeeded: event.x))
        data.appendLE16(UInt16(truncatingIfNeeded: event.y))
        return data
    }
}

/// Message characteristic: UTF-8 text of 1 to `maxBytes` bytes.
public enum MessageCodec {
    public static let maxBytes = 64

    /// Encodes `text` for the wire. Returns nil when it is blank. Longer text is cut at the last
    /// whole character that fits, never inside a UTF-8 sequence.
    public static func encode(_ text: String) -> Data? {
        let trimmed = text.trimmingCharacters(in: .whitespacesAndNewlines)
        guard !trimmed.isEmpty else { return nil }
        let bytes = Array(trimmed.utf8)
        if bytes.count <= maxBytes { return Data(bytes) }
        var end = maxBytes
        // Step back over UTF-8 continuation bytes (10xxxxxx) to the start of a character.
        while end > 0, bytes[end] & 0xC0 == 0x80 { end -= 1 }
        return Data(bytes[..<end])
    }

    /// Bytes `text` needs once trimmed, for the counter in the UI.
    public static func byteLength(_ text: String) -> Int {
        text.trimmingCharacters(in: .whitespacesAndNewlines).utf8.count
    }
}
