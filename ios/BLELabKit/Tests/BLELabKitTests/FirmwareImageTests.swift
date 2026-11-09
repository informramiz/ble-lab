import CryptoKit
import Foundation
import Testing
@testable import BLELabKit

@Suite struct FirmwareImageTests {
    @Test func computesSha256AndFindsRevision() throws {
        let data = testImageBytes(size: 1000)
        let image = try #require(FirmwareImage.parse(data))
        #expect(image.sha256 == Data(SHA256.hash(data: data)))
        #expect(image.version == "ble-lab 1.3.0")
        #expect(image.size == 1000)
    }

    @Test func versionIsNilWhenImageCarriesNone() throws {
        #expect(try #require(FirmwareImage.parse(testImageBytes(size: 1000, revision: nil))).version == nil)
        #expect(try #require(FirmwareImage.parse(testImageBytes(size: 1000, revision: nil))).versionCode == 0)
    }

    @Test func rejectsEmptyAndNonImageFiles() {
        #expect(FirmwareImage.parse(Data()) == nil)
        #expect(FirmwareImage.parse(Data("hello".utf8)) == nil)
    }

    @Test func chunksCoverImageInOrderWithShortLast() throws {
        let data = testImageBytes(size: 1000)
        let chunks = try #require(FirmwareImage.parse(data)).chunks(chunkSize: 244)
        #expect(chunks.map(\.count) == [244, 244, 244, 244, 24])
        #expect(chunks.reduce(Data(), +) == data)
    }

    @Test func exactMultipleHasNoEmptyTrailingChunk() throws {
        #expect(try #require(FirmwareImage.parse(testImageBytes(size: 488))).chunks(chunkSize: 244).map(\.count) == [244, 244])
    }

    @Test func chunkingWorksOnSlicedInput() throws {
        let padded = Data([7, 7, 7]) + testImageBytes(size: 600)
        let image = try #require(FirmwareImage.parse(padded.dropFirst(3)))
        #expect(image.chunks(chunkSize: 500).map(\.count) == [500, 100])
    }

    @Test func rawImageIsUnsignedWithVersionFromRevision() throws {
        let image = try #require(FirmwareImage.parse(testImageBytes(size: 1000)))
        #expect(!image.isSigned)
        #expect(image.versionCode == 10_300)
        #expect(image.signatureOrBlank == Data(count: 64))
    }

    @Test func signedPackageYieldsImageHashVersionCodeAndSignature() throws {
        let signer = OtaTestSigner()
        let raw = testImageBytes(size: 1000)
        let image = try #require(FirmwareImage.parse(signer.pack(raw, versionCode: 10_400)))
        #expect(image.isSigned)
        #expect(image.versionCode == 10_400)
        #expect(image.bytes == raw)
        #expect(image.sha256 == Data(SHA256.hash(data: raw)))
        #expect(signer.verifier.verify(size: 1000, versionCode: 10_400, sha256: image.sha256, signature: try #require(image.signature)))
    }

    @Test func packageWhosePayloadIsNotAnImageIsRejected() {
        let bundle = Data("BLFW".utf8) + Data(count: 4) + Data(count: 64) + Data(count: 100)
        #expect(FirmwareImage.parse(bundle) == nil)
    }
}
