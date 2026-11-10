package com.codebyramiz.blelab.ble.ota

import java.math.BigInteger
import java.security.KeyPair
import java.security.KeyPairGenerator
import java.security.Signature
import java.security.spec.ECGenParameterSpec

/** A throwaway P-256 key pair that signs images the way `sign_firmware.py` does. */
class OtaTestSigner {
    private val pair: KeyPair = KeyPairGenerator.getInstance("EC").apply { initialize(ECGenParameterSpec("secp256r1")) }.generateKeyPair()

    /** What a board built with this signer's public key would use. */
    val verifier: OtaSignatureVerifier = EcdsaOtaVerifier(pair.public)

    fun sign(size: Long, versionCode: Long, sha256: ByteArray): ByteArray {
        val der = Signature.getInstance("SHA256withECDSA").run {
            initSign(pair.private)
            update(OtaSignature.manifest(size, versionCode, sha256))
            sign()
        }
        // DER SEQUENCE { INTEGER r, INTEGER s } to fixed-width r || s.
        var at = 2 + (if (der[1].toInt() and 0x80 != 0) 1 else 0)
        fun integer(): ByteArray {
            val length = der[at + 1].toInt()
            val value = BigInteger(1, der.copyOfRange(at + 2, at + 2 + length)).toByteArray().let { if (it[0] == 0.toByte()) it.copyOfRange(1, it.size) else it }
            at += 2 + length
            return ByteArray(32 - value.size) + value
        }
        return integer() + integer()
    }

    /** A `.signed.bin` package for [image] claiming [versionCode], signed by this signer. */
    fun pack(image: ByteArray, versionCode: Long): ByteArray {
        val sha = java.security.MessageDigest.getInstance("SHA-256").digest(image)
        val header = "BLFW".encodeToByteArray() + ByteArray(4) { (versionCode shr (8 * it)).toByte() }
        return header + sign(image.size.toLong(), versionCode, sha) + image
    }

    companion object {
        /** A minimal ESP32-looking image that carries [revision]. */
        fun image(size: Int = 20_000, revision: String = "ble-lab 1.3.0"): ByteArray {
            val bytes = ByteArray(size) { (it % 251).toByte() }
            bytes[0] = 0xE9.toByte()
            revision.encodeToByteArray().copyInto(bytes, 100)
            return bytes
        }
    }
}
