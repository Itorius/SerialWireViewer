package com.itorius.swo.core

/**
 * Accumulates bytes of one stimulus port and turns them into text, keeping incomplete
 * UTF-8 sequences until the remaining bytes arrive. CR and NUL characters are dropped
 * (NULs commonly pad 16/32-bit ITM writes).
 */
class PortTextBuffer {
    private var bytes = ByteArray(256)
    private var size = 0

    val isEmpty: Boolean get() = size == 0

    fun append(data: ByteArray, offset: Int, length: Int) {
        if (size + length > bytes.size) {
            bytes = bytes.copyOf(maxOf(bytes.size * 2, size + length))
        }
        System.arraycopy(data, offset, bytes, size, length)
        size += length
    }

    /** Returns the decodable text accumulated so far, or null when there is none. */
    fun drainText(): String? {
        if (size == 0) return null
        val complete = completeUtf8Length()
        if (complete == 0) return null
        val text = String(bytes, 0, complete, Charsets.UTF_8)
        System.arraycopy(bytes, complete, bytes, 0, size - complete)
        size -= complete
        if (text.indexOf('\r') < 0 && text.indexOf('\u0000') < 0) return text
        val cleaned = text.filter { it != '\r' && it != '\u0000' }
        return cleaned.ifEmpty { null }
    }

    /** Length of the prefix that does not end in a truncated multi-byte UTF-8 sequence. */
    private fun completeUtf8Length(): Int {
        // Look back at most 3 bytes for the lead byte of the last sequence.
        var i = size - 1
        val limit = maxOf(0, size - 4)
        while (i >= limit) {
            val b = bytes[i].toInt() and 0xFF
            if (b and 0xC0 != 0x80) { // not a continuation byte
                val needed = when {
                    b and 0x80 == 0 -> 1
                    b and 0xE0 == 0xC0 -> 2
                    b and 0xF0 == 0xE0 -> 3
                    b and 0xF8 == 0xF0 -> 4
                    else -> 1 // invalid lead byte; let the decoder replace it
                }
                return if (size - i >= needed) size else i
            }
            i--
        }
        return size // only continuation bytes: malformed, decode with replacement
    }
}
