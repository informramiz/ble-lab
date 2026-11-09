package com.codebyramiz.blelab.ble.ota

import java.security.MessageDigest

/**
 * A firmware file ready to send: the bytes, their SHA-256 and the revision string
 * (`ble-lab 1.2.0`) found inside the image, null when there is none.
 */
class FirmwareImage private constructor(
    val bytes: ByteArray,
    val sha256: ByteArray,
    val version: String?,
) {
    val size: Int get() = bytes.size

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

        /** Null when [bytes] is empty or does not start with the ESP32 image magic byte. */
        fun parse(bytes: ByteArray): FirmwareImage? {
            if (bytes.isEmpty() || (bytes[0].toInt() and 0xFF) != IMAGE_MAGIC) return null
            return FirmwareImage(bytes, MessageDigest.getInstance("SHA-256").digest(bytes), readVersion(bytes))
        }

        /** The app descriptor's own version is the toolchain's git hash, so look for the revision literal instead. */
        private fun readVersion(bytes: ByteArray): String? = REVISION.find(String(bytes, Charsets.ISO_8859_1))?.value
    }
}
