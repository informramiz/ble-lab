package com.codebyramiz.blelab.ble.ota

import com.codebyramiz.blelab.ble.protocol.OtaProtocol
import java.security.MessageDigest

/**
 * A firmware file ready to send: the image bytes, their SHA-256, the revision string
 * (`ble-lab 1.3.0`) found inside the image (null when there is none), its [versionCode] and, for a
 * package made by `sign_firmware.py`, the [signature]. A raw `.bin` has no signature, so the board
 * will refuse it; it is still sent so the refusal is the board's, not the app's.
 */
class FirmwareImage private constructor(
    val bytes: ByteArray,
    val sha256: ByteArray,
    val version: String?,
    val versionCode: Long,
    val signature: ByteArray?,
) {
    val size: Int get() = bytes.size
    val isSigned: Boolean get() = signature != null

    /** What goes in START when there is nothing to send: the board will answer with a signature error. */
    val signatureOrBlank: ByteArray get() = signature ?: ByteArray(OtaProtocol.SIGNATURE_LENGTH)

    /** Splits the image into writes of at most [chunkSize] bytes, in order. */
    fun chunks(chunkSize: Int): Sequence<ByteArray> {
        require(chunkSize > 0) { "chunkSize must be positive" }
        return sequence {
            var offset = 0
            while (offset < bytes.size) {
                val end = minOf(offset + chunkSize, bytes.size)
                yield(bytes.copyOfRange(offset, end))
                offset = end
            }
        }
    }

    companion object {
        /** First byte of every ESP32 application image. */
        private const val IMAGE_MAGIC = 0xE9

        /** The firmware embeds its Device Information revision string, e.g. `ble-lab 1.2.0`. */
        private val REVISION = Regex("ble-lab \\d+\\.\\d+\\.\\d+")

        /** Package header written by `sign_firmware.py`: `"BLFW" | u32 version code LE | 64-byte signature`, then the image. */
        private val PACKAGE_MAGIC = "BLFW".encodeToByteArray()
        private const val PACKAGE_HEADER = 4 + 4 + OtaProtocol.SIGNATURE_LENGTH

        /**
         * Null when [bytes] is empty or the image does not start with the ESP32 image magic byte.
         * Accepts a signed package or a raw image.
         */
        fun parse(bytes: ByteArray): FirmwareImage? {
            if (bytes.size > PACKAGE_HEADER && bytes.copyOfRange(0, PACKAGE_MAGIC.size).contentEquals(PACKAGE_MAGIC)) {
                val image = bytes.copyOfRange(PACKAGE_HEADER, bytes.size)
                if (!looksLikeImage(image)) return null
                val code = (0 until 4).sumOf { (bytes[4 + it].toLong() and 0xFF) shl (8 * it) }
                return build(image, code, bytes.copyOfRange(8, PACKAGE_HEADER))
            }
            if (!looksLikeImage(bytes)) return null
            return build(bytes, null, null)
        }

        private fun looksLikeImage(bytes: ByteArray) = bytes.isNotEmpty() && (bytes[0].toInt() and 0xFF) == IMAGE_MAGIC

        private fun build(image: ByteArray, versionCode: Long?, signature: ByteArray?): FirmwareImage {
            val version = readVersion(image)
            return FirmwareImage(image, MessageDigest.getInstance("SHA-256").digest(image), version, versionCode ?: OtaSignature.versionCode(version) ?: 0, signature)
        }

        /** The app descriptor's own version is the toolchain's git hash, so look for the revision literal instead. */
        private fun readVersion(bytes: ByteArray): String? = REVISION.find(String(bytes, Charsets.ISO_8859_1))?.value
    }
}
