package com.codebyramiz.blelab.ble.ota

import java.math.BigInteger
import java.security.AlgorithmParameters
import java.security.KeyFactory
import java.security.PublicKey
import java.security.Signature
import java.security.spec.ECGenParameterSpec
import java.security.spec.ECParameterSpec
import java.security.spec.ECPoint
import java.security.spec.ECPublicKeySpec

/** Decides whether an image manifest was signed by a key the board trusts. */
fun interface OtaSignatureVerifier {
    fun verify(size: Long, versionCode: Long, sha256: ByteArray, signature: ByteArray): Boolean
}

/**
 * Signature scheme shared with `firmware/tools/sign_firmware.py` and `firmware/ble_lab/ota_verify.h`:
 * ECDSA P-256 over SHA-256 of `"BLFW1" | u32 size LE | u32 version code LE | SHA-256(image)`,
 * written as raw `r || s` (64 bytes).
 */
object OtaSignature {
    private const val CONTEXT = "BLFW1"
    private const val COORDINATE = 32

    /** The development key compiled into the firmware (`ota_public_key.h`); the private half stays on the maintainer's machine. */
    val FIRMWARE_DEV_KEY: PublicKey by lazy {
        publicKey(
            "04be9cdab0d972d84b804c952ef3d89b1dda7327eb9eb944b162090fbbf9e80f188282281fc14317adcfcd9dd6b1f3c39e7579388c9d022a4a5ea38f2aadfe946a".chunked(2).map { it.toInt(16).toByte() }.toByteArray(),
        )
    }

    /** Bytes that are signed for an image of [size] with [versionCode] and digest [sha256]. */
    fun manifest(size: Long, versionCode: Long, sha256: ByteArray): ByteArray {
        val out = CONTEXT.encodeToByteArray() + ByteArray(8) + sha256
        for (i in 0 until 4) {
            out[CONTEXT.length + i] = (size shr (8 * i)).toByte()
            out[CONTEXT.length + 4 + i] = (versionCode shr (8 * i)).toByte()
        }
        return out
    }

    /** `major * 10000 + minor * 100 + patch` for a revision like `ble-lab 1.3.0`; null when it does not parse. */
    fun versionCode(revision: String?): Long? {
        val match = revision?.let { Regex("(\\d+)\\.(\\d+)\\.(\\d+)").find(it) } ?: return null
        val (major, minor, patch) = match.destructured
        return major.toLong() * 10_000 + minor.toLong() * 100 + patch.toLong()
    }

    /** Public key from an uncompressed SEC1 point (`04 || x || y`), the form the firmware header holds. */
    fun publicKey(uncompressedPoint: ByteArray): PublicKey {
        require(uncompressedPoint.size == 1 + 2 * COORDINATE && uncompressedPoint[0] == 0x04.toByte()) { "Not an uncompressed P-256 point" }
        val params = AlgorithmParameters.getInstance("EC").apply { init(ECGenParameterSpec("secp256r1")) }.getParameterSpec(ECParameterSpec::class.java)
        val x = BigInteger(1, uncompressedPoint.copyOfRange(1, 1 + COORDINATE))
        val y = BigInteger(1, uncompressedPoint.copyOfRange(1 + COORDINATE, uncompressedPoint.size))
        return KeyFactory.getInstance("EC").generatePublic(ECPublicKeySpec(ECPoint(x, y), params))
    }

    /** True when [signature] (raw `r || s`) is a valid signature by [key] over the manifest. */
    fun verify(key: PublicKey, size: Long, versionCode: Long, sha256: ByteArray, signature: ByteArray): Boolean {
        if (signature.size != 2 * COORDINATE) return false
        return runCatching {
            // The board signs the manifest digest; Java hashes it itself with SHA256withECDSA.
            Signature.getInstance("SHA256withECDSA").run {
                initVerify(key)
                update(manifest(size, versionCode, sha256))
                verify(toDer(signature))
            }
        }.getOrDefault(false)
    }

    /** Raw `r || s` to the ASN.1 DER SEQUENCE of two INTEGERs that the JCA expects. */
    fun toDer(raw: ByteArray): ByteArray {
        fun integer(bytes: ByteArray): ByteArray {
            val value = BigInteger(1, bytes).toByteArray()
            return byteArrayOf(0x02, value.size.toByte()) + value
        }
        val body = integer(raw.copyOfRange(0, COORDINATE)) + integer(raw.copyOfRange(COORDINATE, raw.size))
        return byteArrayOf(0x30, body.size.toByte()) + body
    }
}

/** Verifies manifests against [key], the way the firmware checks them against its compiled-in public key. */
class EcdsaOtaVerifier(private val key: PublicKey) : OtaSignatureVerifier {
    override fun verify(size: Long, versionCode: Long, sha256: ByteArray, signature: ByteArray) = OtaSignature.verify(key, size, versionCode, sha256, signature)
}
