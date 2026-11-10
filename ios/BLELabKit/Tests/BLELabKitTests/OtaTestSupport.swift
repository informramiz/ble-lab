import CryptoKit
import Foundation
@testable import BLELabKit

/// Signs images the way `firmware/tools/sign_firmware.py` does, with a throwaway key.
struct OtaTestSigner {
    let key = P256.Signing.PrivateKey()
    var verifier: EcdsaOtaVerifier { EcdsaOtaVerifier(key: key.publicKey) }

    func sign(size: Int, versionCode: Int, sha256: Data) -> Data {
        // swiftlint:disable:next force_try
        try! key.signature(for: OtaSignature.manifest(size: size, versionCode: versionCode, sha256: sha256)).rawRepresentation
    }

    /// A `.signed.bin`: `"BLFW" | u32 version code LE | signature | image`.
    func pack(_ image: Data, versionCode: Int) -> Data {
        var out = Data("BLFW".utf8)
        out.append(littleEndian: UInt32(versionCode))
        out.append(sign(size: image.count, versionCode: versionCode, sha256: Data(SHA256.hash(data: image))))
        out.append(image)
        return out
    }
}

/// An ESP32-looking image: magic byte first, deterministic filler, the revision literal at offset 100.
func testImageBytes(size: Int, revision: String? = "ble-lab 1.3.0") -> Data {
    var out = Data((0..<size).map { UInt8($0 % 251) })
    out[0] = 0xE9
    if let revision { out.replaceSubrange(100..<(100 + revision.utf8.count), with: Data(revision.utf8)) }
    return out
}
