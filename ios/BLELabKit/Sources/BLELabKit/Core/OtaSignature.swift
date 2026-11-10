import CryptoKit
import Foundation

/// Decides whether an image manifest was signed by a key the board trusts.
public protocol OtaSignatureVerifier: Sendable {
    func verify(size: Int, versionCode: Int, sha256: Data, signature: Data) -> Bool
}

/// Signature scheme shared with `firmware/tools/sign_firmware.py` and `firmware/ble_lab/ota_verify.h`:
/// ECDSA P-256 over SHA-256 of `"BLFW1" | u32 size LE | u32 version code LE | SHA-256(image)`,
/// written as raw `r || s` (64 bytes).
public enum OtaSignature {
    private static let context = Data("BLFW1".utf8)

    /// The development key compiled into the firmware (`ota_public_key.h`); the private half stays on the maintainer's machine.
    public static let firmwareDevKey: P256.Signing.PublicKey = {
        let hex = "04be9cdab0d972d84b804c952ef3d89b1dda7327eb9eb944b162090fbbf9e80f188282281fc14317adcfcd9dd6b1f3c39e7579388c9d022a4a5ea38f2aadfe946a"
        let bytes = stride(from: 0, to: hex.count, by: 2).map { i -> UInt8 in
            let start = hex.index(hex.startIndex, offsetBy: i)
            return UInt8(hex[start..<hex.index(start, offsetBy: 2)], radix: 16)!
        }
        return try! P256.Signing.PublicKey(x963Representation: Data(bytes))
    }()

    /// Bytes that are signed for an image of `size` with `versionCode` and digest `sha256`.
    public static func manifest(size: Int, versionCode: Int, sha256: Data) -> Data {
        var out = context
        out.append(littleEndian: UInt32(truncatingIfNeeded: size))
        out.append(littleEndian: UInt32(truncatingIfNeeded: versionCode))
        out.append(sha256)
        return out
    }

    /// `major * 10000 + minor * 100 + patch` for a revision like `ble-lab 1.3.0`; nil when it does not parse.
    public static func versionCode(revision: String?) -> Int? {
        guard let revision, let match = revision.firstMatch(of: /(\d+)\.(\d+)\.(\d+)/),
              let major = Int(match.1), let minor = Int(match.2), let patch = Int(match.3) else { return nil }
        return major * 10_000 + minor * 100 + patch
    }

    /// True when `signature` (raw `r || s`) is a valid signature by `key` over the manifest.
    public static func verify(key: P256.Signing.PublicKey, size: Int, versionCode: Int, sha256: Data, signature: Data) -> Bool {
        guard signature.count == OtaProtocol.signatureLength,
              let parsed = try? P256.Signing.ECDSASignature(rawRepresentation: signature) else { return false }
        return key.isValidSignature(parsed, for: manifest(size: size, versionCode: versionCode, sha256: sha256))
    }
}

/// Verifies manifests against `key`, the way the firmware checks them against its compiled-in public key.
public struct EcdsaOtaVerifier: OtaSignatureVerifier {
    private let key: P256.Signing.PublicKey

    public init(key: P256.Signing.PublicKey = OtaSignature.firmwareDevKey) { self.key = key }

    public func verify(size: Int, versionCode: Int, sha256: Data, signature: Data) -> Bool {
        OtaSignature.verify(key: key, size: size, versionCode: versionCode, sha256: sha256, signature: signature)
    }
}
