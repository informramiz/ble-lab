import Foundation
import Testing
@testable import BLELabKit

@Suite struct TelemetryCodecTests {
    @Test func decodesLittleEndianPayload() throws {
        // seq 0x01020304, value 0x0960 = 2400 -> 24.00, flags LED on
        let telemetry = try TelemetryCodec.decode(Data([0x04, 0x03, 0x02, 0x01, 0x60, 0x09, 0x01]))
        #expect(telemetry == Telemetry(sequence: 0x0102_0304, value: 24, ledOn: true))
    }

    @Test func roundTripsNegativeValues() throws {
        let original = Telemetry(sequence: 42, value: -12.34, ledOn: false)
        #expect(try TelemetryCodec.decode(TelemetryCodec.encode(original)) == original)
    }

    @Test func clampsOutOfRangeValues() throws {
        let encoded = TelemetryCodec.encode(Telemetry(sequence: 1, value: 1_000, ledOn: false))
        #expect(try TelemetryCodec.decode(encoded).value == Float(Int16.max) / 100)
    }

    @Test func rejectsShortPayload() {
        #expect(throws: BLEError.malformedPayload(expected: 7, actual: 3)) {
            try TelemetryCodec.decode(Data([1, 2, 3]))
        }
    }

    @Test func ignoresTrailingBytes() throws {
        let data = TelemetryCodec.encode(Telemetry(sequence: 9, value: 1.5, ledOn: true)) + Data([0xFF, 0xFF])
        #expect(try TelemetryCodec.decode(data).sequence == 9)
    }

    @Test func encodesLed() {
        #expect(TelemetryCodec.encodeLed(true) == Data([1]))
        #expect(TelemetryCodec.encodeLed(false) == Data([0]))
    }
}
