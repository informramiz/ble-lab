import CryptoKit
import Foundation

/// A firmware file ready to send: the image bytes, their SHA-256, the revision string (`ble-lab 1.3.0`)
/// found inside the image (nil when there is none), its `versionCode` and, for a package made by
/// `sign_firmware.py`, the `signature`. A raw `.bin` has no signature, so the board will refuse it; it
/// is still sent so the refusal is the board's, not the app's.
public struct FirmwareImage: Equatable, Sendable {
    public let bytes: Data
    public let sha256: Data
    public let version: String?
    public let versionCode: Int
    public let signature: Data?

    public var size: Int { bytes.count }
    public var isSigned: Bool { signature != nil }

    /// What goes in START when there is nothing to send: the board will answer with a signature error.
    public var signatureOrBlank: Data { signature ?? Data(count: OtaProtocol.signatureLength) }

    /// First byte of every ESP32 application image.
    private static let imageMagic: UInt8 = 0xE9
    /// Package header written by `sign_firmware.py`: `"BLFW" | u32 version code LE | 64-byte signature`, then the image.
    private static let packageMagic = Data("BLFW".utf8)
    private static let packageHeader = 4 + 4 + OtaProtocol.signatureLength

    /// Nil when `data` is empty or the image does not start with the ESP32 image magic byte.
    /// Accepts a signed package or a raw image.
    public static func parse(_ data: Data) -> FirmwareImage? {
        let data = Data(data)  // Re-base the indices so slicing below is zero-based.
        if data.count > packageHeader, data.prefix(packageMagic.count) == packageMagic {
            let image = Data(data.dropFirst(packageHeader))
            guard looksLikeImage(image) else { return nil }
            let code = (0..<4).reduce(0) { $0 | Int(data[4 + $1]) << (8 * $1) }
            return build(image, versionCode: code, signature: Data(data[8..<packageHeader]))
        }
        guard looksLikeImage(data) else { return nil }
        return build(data, versionCode: nil, signature: nil)
    }

    /// Splits the image into writes of at most `chunkSize` bytes, in order.
    public func chunks(chunkSize: Int) -> [Data] {
        precondition(chunkSize > 0, "chunkSize must be positive")
        return stride(from: 0, to: bytes.count, by: chunkSize).map { start in
            Data(bytes[bytes.startIndex + start ..< bytes.startIndex + min(start + chunkSize, bytes.count)])
        }
    }

    private static func looksLikeImage(_ data: Data) -> Bool { data.first == imageMagic }

    private static func build(_ image: Data, versionCode: Int?, signature: Data?) -> FirmwareImage {
        let version = readVersion(image)
        return FirmwareImage(
            bytes: image,
            sha256: Data(SHA256.hash(data: image)),
            version: version,
            versionCode: versionCode ?? OtaSignature.versionCode(revision: version) ?? 0,
            signature: signature
        )
    }

    /// The app descriptor's own version is the toolchain's git hash, so look for the revision literal instead.
    private static func readVersion(_ image: Data) -> String? {
        guard let text = String(data: image, encoding: .isoLatin1) else { return nil }
        return text.firstMatch(of: /ble-lab \d+\.\d+\.\d+/).map { String($0.0) }
    }
}
