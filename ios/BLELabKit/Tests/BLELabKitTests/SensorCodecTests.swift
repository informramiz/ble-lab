import Foundation
import Testing
@testable import BLELabKit

@Suite struct MotionCodecTests {
    private func sample(_ ms: UInt32, ax: Float = 0) -> MotionSample {
        MotionSample(boardMillis: ms, ax: ax, ay: 0.5, az: 1, gx: 12.3, gy: -45.6, gz: 0)
    }

    @Test func decodesHandBuiltBatch() throws {
        // t=0x01020304, index 7, 1 sample, 20 ms; ax=1000 mg, ay=-500, az=0, gx=100 (10 dps), gy=-100, gz=1
        let bytes: [UInt8] = [4, 3, 2, 1, 7, 0, 1, 20, 0xE8, 0x03, 0x0C, 0xFE, 0, 0, 100, 0, 0x9C, 0xFF, 1, 0]
        let s = try #require(try MotionCodec.decode(Data(bytes)).first)
        #expect(s.boardMillis == 0x0102_0304)
        #expect(s.ax == 1)
        #expect(s.ay == -0.5)
        #expect(s.gx == 10)
        #expect(s.gy == -10)
        #expect(abs(s.gz - 0.1) < 1e-6)
    }

    @Test func spacesSamplesByPeriodAndRoundTrips() throws {
        let batch = (0..<5).map { sample(1_000 + UInt32($0) * 20, ax: Float($0) / 10) }
        let decoded = try MotionCodec.decode(MotionCodec.encode(batch, index: 3, periodMillis: 20))
        #expect(decoded.map(\.boardMillis) == [1_000, 1_020, 1_040, 1_060, 1_080])
        #expect(abs(decoded[2].ax - 0.2) < 1e-3)
        #expect(abs(decoded[4].gy + 45.6) < 0.1)
    }

    @Test func emptyBatchHasNoSamples() throws {
        #expect(try MotionCodec.decode(MotionCodec.encode([], index: 0, periodMillis: 20)).isEmpty)
    }

    @Test func truncatedBatchKeepsCompleteSamples() throws {
        let full = MotionCodec.encode((0..<3).map { sample(UInt32($0) * 20) }, index: 0, periodMillis: 20)
        #expect(try MotionCodec.decode(full.dropLast(5)).count == 2)
    }

    @Test func clampsOutOfRangeValues() throws {
        let data = MotionCodec.encode([sample(0, ax: 100)], index: 0, periodMillis: 20)
        let decoded = try MotionCodec.decode(data)
        #expect(abs(decoded[0].ax - Float(Int16.max) / 1000) < 1e-3)
    }

    @Test func rejectsPayloadShorterThanHeader() {
        #expect(throws: BLEError.malformedPayload(expected: 8, actual: 7)) { try MotionCodec.decode(Data(count: 7)) }
    }
}

@Suite struct TouchCodecTests {
    @Test func decodesHandBuiltEvent() throws {
        // index 0x0102, long press, injected, x = 0x0140 (320), y = 0x00F0 (240)
        let event = try TouchCodec.decode(Data([0x02, 0x01, 2, 1, 0x40, 0x01, 0xF0, 0x00]))
        #expect(event == TouchEvent(index: 0x0102, kind: .longPress, injected: true, x: 320, y: 240))
    }

    @Test func roundTrips() throws {
        let original = TouchEvent(index: 65_535, kind: .tap, injected: false, x: 12, y: 200)
        #expect(try TouchCodec.decode(TouchCodec.encode(original)) == original)
    }

    @Test func unknownKindIsIgnored() throws {
        #expect(try TouchCodec.decode(Data([0, 0, 9, 0, 0, 0, 0, 0])) == nil)
    }

    @Test func rejectsShortPayload() {
        #expect(throws: BLEError.malformedPayload(expected: 8, actual: 3)) { try TouchCodec.decode(Data([1, 2, 3])) }
    }
}

@Suite struct MessageCodecTests {
    @Test func trimsAndEncodes() {
        #expect(MessageCodec.encode("  hi  ") == Data("hi".utf8))
    }

    @Test func blankIsNil() {
        #expect(MessageCodec.encode(" \n ") == nil)
    }

    @Test func keepsExactlyMaxBytes() {
        #expect(MessageCodec.encode(String(repeating: "a", count: 64))?.count == 64)
    }

    @Test func cutsAtCharacterBoundary() throws {
        // 63 ASCII bytes then a 2-byte character that would straddle the limit.
        let text = String(repeating: "a", count: 63) + "é"
        let data = try #require(MessageCodec.encode(text))
        #expect(data.count == 63)
        #expect(String(data: data, encoding: .utf8) != nil)
    }

    @Test func countsBytesNotCharacters() {
        #expect(MessageCodec.byteLength(" é ") == 2)
    }
}
