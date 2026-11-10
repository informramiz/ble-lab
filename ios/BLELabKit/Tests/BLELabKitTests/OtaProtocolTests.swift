import CryptoKit
import Foundation
import Testing
@testable import BLELabKit

@Suite struct OtaProtocolTests {
    private let sha = Data((0..<32).map { UInt8($0) })
    private let signature = Data((0..<64).map { UInt8(100 + $0) })

    @Test func startIs105BytesLittleEndian() throws {
        let start = try #require(OtaProtocol.encodeStart(size: 0x0102_0304, sha256: sha, versionCode: 0x0A0B_0C0D, signature: signature))
        #expect(start.count == 105)
        #expect(OtaProtocol.startLength == 105)
        #expect(start[0] == 0x01)
        #expect(Array(start[1..<5]) == [4, 3, 2, 1])
        #expect(start[5..<37] == sha)
        #expect(Array(start[37..<41]) == [0x0D, 0x0C, 0x0B, 0x0A])
        #expect(start[41..<105] == signature)
    }

    @Test func startRejectsBadFields() {
        #expect(OtaProtocol.encodeStart(size: 0, sha256: sha, versionCode: 1, signature: signature) == nil)
        #expect(OtaProtocol.encodeStart(size: 1, sha256: Data(count: 31), versionCode: 1, signature: signature) == nil)
        #expect(OtaProtocol.encodeStart(size: 1, sha256: sha, versionCode: -1, signature: signature) == nil)
        #expect(OtaProtocol.encodeStart(size: 1, sha256: sha, versionCode: 1, signature: Data(count: 63)) == nil)
        #expect(OtaProtocol.encodeStart(size: Int(UInt32.max) + 1, sha256: sha, versionCode: 1, signature: signature) == nil)
    }

    @Test func abortAndFinishAreSingleBytes() {
        #expect(OtaProtocol.abort == Data([0x02]))
        #expect(OtaProtocol.finish == Data([0x03]))
    }

    @Test func decodesStatus() {
        let status = OtaProtocol.decodeStatus(Data([1, 0, 0x00, 0x04, 0x00, 0x00]))
        #expect(status == OtaStatus(state: .receiving, error: .none, offset: 1024))
    }

    @Test func decodesErrorStatusWithHighOffset() {
        let status = OtaProtocol.decodeStatus(Data([3, 11, 0xFF, 0xFF, 0xFF, 0x7F]))
        #expect(status == OtaStatus(state: .error, error: .badSignature, offset: 0x7FFF_FFFF))
    }

    @Test func rejectsMalformedStatus() {
        #expect(OtaProtocol.decodeStatus(Data([1, 0, 0, 0, 0])) == nil)
        #expect(OtaProtocol.decodeStatus(Data([1, 0, 0, 0, 0, 0, 0])) == nil)
        #expect(OtaProtocol.decodeStatus(Data([9, 0, 0, 0, 0, 0])) == nil)
    }

    @Test func mapsEveryBoardErrorCode() {
        #expect(OtaError(code: 11) == .badSignature)
        #expect(OtaError(code: 12) == .downgrade)
        #expect(OtaError(code: 8) == .hash)
        #expect(OtaError(code: 10) == .aborted)
        #expect(OtaError(code: 200) == .unknown)
        for code in 0...12 { #expect(OtaError(code: UInt8(code)).rawValue == code) }
    }

    @Test func rejectionReasonsAreReadable() {
        #expect(OtaError.badSignature.message.contains("unsigned"))
        #expect(OtaError.downgrade.message.contains("older"))
        #expect(OtaError.unknown.message == "The board reported an unknown error")
    }
}

@Suite struct OtaSignatureTests {
    private let sha = Data((0..<32).map { UInt8($0) })

    @Test func manifestBindsContextSizeVersionAndHash() {
        let manifest = OtaSignature.manifest(size: 0x0102_0304, versionCode: 0x0A0B_0C0D, sha256: sha)
        #expect(manifest.count == 5 + 4 + 4 + 32)
        #expect(manifest.prefix(5) == Data("BLFW1".utf8))
        #expect(Array(manifest[5..<9]) == [4, 3, 2, 1])
        #expect(Array(manifest[9..<13]) == [0x0D, 0x0C, 0x0B, 0x0A])
        #expect(manifest.suffix(32) == sha)
    }

    @Test func versionCodeIsMajorMinorPatch() {
        #expect(OtaSignature.versionCode(revision: "ble-lab 1.3.0") == 10_300)
        #expect(OtaSignature.versionCode(revision: "ble-lab 12.34.56") == 123_456)
        #expect(OtaSignature.versionCode(revision: "garbage") == nil)
        #expect(OtaSignature.versionCode(revision: nil) == nil)
    }

    @Test func signatureVerifiesForSignerOnly() throws {
        let signer = P256.Signing.PrivateKey()
        let signature = try sign(signer, size: 1234, versionCode: 10_300)
        #expect(OtaSignature.verify(key: signer.publicKey, size: 1234, versionCode: 10_300, sha256: sha, signature: signature))
        #expect(!OtaSignature.verify(key: P256.Signing.PrivateKey().publicKey, size: 1234, versionCode: 10_300, sha256: sha, signature: signature))
    }

    @Test func changingSizeVersionOrHashBreaksSignature() throws {
        let signer = P256.Signing.PrivateKey()
        let signature = try sign(signer, size: 1234, versionCode: 10_300)
        let key = signer.publicKey
        #expect(!OtaSignature.verify(key: key, size: 1235, versionCode: 10_300, sha256: sha, signature: signature))
        #expect(!OtaSignature.verify(key: key, size: 1234, versionCode: 10_301, sha256: sha, signature: signature))
        #expect(!OtaSignature.verify(key: key, size: 1234, versionCode: 10_300, sha256: Data(count: 32), signature: signature))
    }

    @Test func blankAndTruncatedSignaturesNeverVerify() {
        let key = OtaSignature.firmwareDevKey
        #expect(!OtaSignature.verify(key: key, size: 1234, versionCode: 10_300, sha256: sha, signature: Data(count: 64)))
        #expect(!OtaSignature.verify(key: key, size: 1234, versionCode: 10_300, sha256: sha, signature: Data(count: 10)))
    }

    @Test func signFirmwareScriptSignatureVerifiesAgainstFirmwareKey() throws {
        let hex = "9104a01d911c06d94d5f3107af217379267ff24fae5d793b6ba1a03142e70546285bcfdd8a4e26918397f278ce58412b60e511d7901be38de2988460d5adbbdb"
        let signature = Data(stride(from: 0, to: hex.count, by: 2).map { i -> UInt8 in
            let start = hex.index(hex.startIndex, offsetBy: i)
            return UInt8(hex[start..<hex.index(start, offsetBy: 2)], radix: 16)!
        })
        let verifier = EcdsaOtaVerifier()
        #expect(verifier.verify(size: 1234, versionCode: 10_300, sha256: sha, signature: signature))
        #expect(!verifier.verify(size: 1234, versionCode: 10_299, sha256: sha, signature: signature))
    }

    private func sign(_ key: P256.Signing.PrivateKey, size: Int, versionCode: Int) throws -> Data {
        try key.signature(for: OtaSignature.manifest(size: size, versionCode: versionCode, sha256: sha)).rawRepresentation
    }
}
