package com.codebyramiz.blelab.ble.protocol

/** The message characteristic carries UTF-8 text of 1 to [MAX_BYTES] bytes. */
object MessageCodec {
    const val MAX_BYTES = 64

    /**
     * Encodes [text] for the wire. Returns null when it is blank. Text longer than [MAX_BYTES]
     * is cut at the last whole character that fits, never in the middle of a UTF-8 sequence.
     */
    fun encode(text: String): ByteArray? {
        val trimmed = text.trim()
        if (trimmed.isEmpty()) return null
        val bytes = trimmed.encodeToByteArray()
        if (bytes.size <= MAX_BYTES) return bytes
        var end = MAX_BYTES
        // Step back over UTF-8 continuation bytes (10xxxxxx) to the start of a character.
        while (end > 0 && bytes[end].toInt() and 0xC0 == 0x80) end--
        return bytes.copyOf(end)
    }

    /** Bytes [text] needs once trimmed, for a counter in the UI. */
    fun byteLength(text: String): Int = text.trim().encodeToByteArray().size
}
