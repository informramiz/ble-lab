package com.codebyramiz.blelab.ble.protocol

/** Transfer state the board reports in every OTA status notification. */
enum class OtaState(val code: Int) {
    Idle(0),
    Receiving(1),

    /** Image verified and activated; the board restarts about a second later. */
    Done(2),
    Error(3),
    ;

    companion object {
        fun from(code: Int): OtaState? = entries.firstOrNull { it.code == code }
    }
}

/** Why the board refused or abandoned a transfer. */
enum class OtaError(val code: Int, val message: String) {
    None(0, "No error"),
    BadCommand(1, "The board did not understand the command"),
    BadState(2, "The board is not in the right state for that command"),
    TooLarge(3, "The image is larger than the board's update slot"),
    Begin(4, "The board could not start the update"),
    Write(5, "The board failed to write the image to flash"),
    Overflow(6, "The board received more data than announced"),
    Incomplete(7, "The board did not receive the whole image"),
    Hash(8, "The image failed the board's SHA-256 check"),
    Image(9, "The board rejected the image as not valid firmware"),
    Aborted(10, "The update was aborted"),
    BadSignature(11, "The board refused the image: it is unsigned, or signed with a key the board does not trust"),
    Downgrade(12, "The board refused the image: it is older than the firmware already running"),
    Unknown(-1, "The board reported an unknown error"),
    ;

    companion object {
        fun from(code: Int): OtaError = entries.firstOrNull { it.code == code } ?: Unknown
    }
}

/** One OTA status notification: [offset] is how many image bytes the board has accepted. */
data class OtaStatus(val state: OtaState, val error: OtaError, val offset: Long)

/**
 * OTA protocol v2, mirrored from `firmware/ble_lab/gatt_profile.h`.
 *
 * Control (write with response): START = `01` + u32 size LE + 32-byte SHA-256 + u32 version code LE + 64-byte signature, ABORT = `02`,
 * FINISH = `03`. Data (write without response): raw image bytes, in order. Status: u8 state,
 * u8 error, u32 offset LE. The board notifies status on START (offset 0, "ready"), every
 * [ACK_INTERVAL] bytes, and when the last byte is in; the central stays within [WINDOW_BYTES]
 * of the last reported offset.
 */
object OtaProtocol {
    const val CMD_START: Byte = 0x01
    const val CMD_ABORT: Byte = 0x02
    const val CMD_FINISH: Byte = 0x03

    const val ACK_INTERVAL = 1024
    const val WINDOW_BYTES = 4096
    const val SHA256_LENGTH = 32
    const val SIGNATURE_LENGTH = 64
    const val START_LENGTH = 1 + 4 + SHA256_LENGTH + 4 + SIGNATURE_LENGTH
    const val STATUS_LENGTH = 6

    /** ATT header (opcode + handle) that comes out of the MTU for each write. */
    const val ATT_OVERHEAD = 3

    val ABORT = byteArrayOf(CMD_ABORT)
    val FINISH = byteArrayOf(CMD_FINISH)

    fun encodeStart(size: Long, sha256: ByteArray, versionCode: Long, signature: ByteArray): ByteArray {
        require(size in 1..UInt.MAX_VALUE.toLong()) { "Image size out of range: $size" }
        require(sha256.size == SHA256_LENGTH) { "SHA-256 must be $SHA256_LENGTH bytes" }
        require(versionCode in 0..UInt.MAX_VALUE.toLong()) { "Version code out of range: $versionCode" }
        require(signature.size == SIGNATURE_LENGTH) { "Signature must be $SIGNATURE_LENGTH bytes" }
        val out = ByteArray(START_LENGTH)
        out[0] = CMD_START
        for (i in 0 until 4) {
            out[1 + i] = (size shr (8 * i)).toByte()
            out[5 + SHA256_LENGTH + i] = (versionCode shr (8 * i)).toByte()
        }
        sha256.copyInto(out, destinationOffset = 5)
        signature.copyInto(out, destinationOffset = 5 + SHA256_LENGTH + 4)
        return out
    }

    /** Null when [bytes] is not a well-formed status (wrong length or unknown state). */
    fun decodeStatus(bytes: ByteArray): OtaStatus? {
        if (bytes.size != STATUS_LENGTH) return null
        val state = OtaState.from(bytes[0].toInt() and 0xFF) ?: return null
        var offset = 0L
        for (i in 0 until 4) offset = offset or ((bytes[2 + i].toLong() and 0xFF) shl (8 * i))
        return OtaStatus(state, OtaError.from(bytes[1].toInt() and 0xFF), offset)
    }

    /** Largest data write that fits the negotiated [mtu]. */
    fun chunkSize(mtu: Int): Int = (mtu - ATT_OVERHEAD).coerceAtLeast(1)
}
