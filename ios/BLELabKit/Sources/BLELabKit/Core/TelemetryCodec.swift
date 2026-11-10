import Foundation

public struct Telemetry: Equatable, Sendable {
    public let sequence: UInt32
    public let value: Float
    public let ledOn: Bool
    /// Flags bit 1: the board has an accelerometer and gyroscope (firmware 1.1.0+).
    public let hasMotionSensor: Bool
    /// Flags bit 2: the board has a touch screen (firmware 1.1.0+).
    public let hasTouchScreen: Bool

    public init(sequence: UInt32, value: Float, ledOn: Bool, hasMotionSensor: Bool = false, hasTouchScreen: Bool = false) {
        self.sequence = sequence
        self.value = value
        self.ledOn = ledOn
        self.hasMotionSensor = hasMotionSensor
        self.hasTouchScreen = hasTouchScreen
    }
}

/// Encodes and decodes the 7-byte little-endian telemetry payload.
public enum TelemetryCodec {
    public static let length = 7

    public static func decode(_ data: Data) throws(BLEError) -> Telemetry {
        guard data.count >= length else { throw .malformedPayload(expected: length, actual: data.count) }
        let bytes = [UInt8](data.prefix(length))
        let sequence = UInt32(bytes[0]) | UInt32(bytes[1]) << 8 | UInt32(bytes[2]) << 16 | UInt32(bytes[3]) << 24
        let raw = Int16(bitPattern: UInt16(bytes[4]) | UInt16(bytes[5]) << 8)
        return Telemetry(
            sequence: sequence,
            value: Float(raw) / 100,
            ledOn: bytes[6] & 0x01 != 0,
            hasMotionSensor: bytes[6] & 0x02 != 0,
            hasTouchScreen: bytes[6] & 0x04 != 0
        )
    }

    public static func encode(_ telemetry: Telemetry) -> Data {
        let scaled = (telemetry.value * 100).rounded()
        let raw = Int16(max(Float(Int16.min), min(Float(Int16.max), scaled)))
        let s = telemetry.sequence
        let v = UInt16(bitPattern: raw)
        return Data([
            UInt8(s & 0xFF), UInt8(s >> 8 & 0xFF), UInt8(s >> 16 & 0xFF), UInt8(s >> 24 & 0xFF),
            UInt8(v & 0xFF), UInt8(v >> 8),
            (telemetry.ledOn ? 0x01 : 0x00) | (telemetry.hasMotionSensor ? 0x02 : 0x00) | (telemetry.hasTouchScreen ? 0x04 : 0x00),
        ])
    }

    public static func encodeLed(_ on: Bool) -> Data { Data([on ? 0x01 : 0x00]) }
}
