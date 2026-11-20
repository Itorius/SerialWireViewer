package com.itorius.swo.core

/**
 * Streaming decoder for the ARM CoreSight ITM/DWT packet protocol carried over SWO
 * (ARMv7-M Architecture Reference Manual, Appendix D4 "Debug ITM and DWT Packet Protocol").
 *
 * Only instrumentation (software source) packets are reported; synchronisation, overflow,
 * timestamp, extension and hardware (DWT) packets are parsed and skipped.
 */
class ItmDecoder(private val listener: Listener) {

    interface Listener {
        /** Payload of one stimulus-port write, in little-endian byte order. Do not keep a reference to [data]. */
        fun onInstrumentation(port: Int, data: ByteArray, length: Int)

        /** The ITM FIFO overflowed on the target; some packets were lost. */
        fun onOverflow() {}
    }

    private enum class State { HEADER, PAYLOAD, CONTINUATION }

    private var state = State.HEADER
    private var zeroCount = 0
    private var port = 0
    private var hardware = false
    private var remaining = 0
    private val payload = ByteArray(4)
    private var payloadLength = 0

    fun reset() {
        state = State.HEADER
        zeroCount = 0
        remaining = 0
        payloadLength = 0
    }

    fun feed(buffer: ByteArray, offset: Int = 0, length: Int = buffer.size - offset) {
        for (i in offset until offset + length) {
            feedByte(buffer[i].toInt() and 0xFF)
        }
    }

    private fun feedByte(b: Int) {
        when (state) {
            State.HEADER -> header(b)
            State.PAYLOAD -> {
                payload[payloadLength++] = b.toByte()
                if (--remaining == 0) {
                    state = State.HEADER
                    if (!hardware) listener.onInstrumentation(port, payload, payloadLength)
                }
            }
            State.CONTINUATION -> if (b and 0x80 == 0) state = State.HEADER
        }
    }

    private fun header(b: Int) {
        // Synchronisation packet: >= 47 zero bits followed by a single one bit (00 00 00 00 00 80).
        if (b == 0x00) {
            zeroCount++
            return
        }
        if (zeroCount > 0) {
            zeroCount = 0
            if (b == 0x80) return
        }

        when {
            // Source packet: SS != 00. Bit 2 selects hardware (DWT) vs. instrumentation (ITM).
            b and 0x03 != 0 -> {
                port = b ushr 3
                hardware = b and 0x04 != 0
                remaining = when (b and 0x03) {
                    1 -> 1
                    2 -> 2
                    else -> 4
                }
                payloadLength = 0
                state = State.PAYLOAD
            }
            // Overflow packet
            b == 0x70 -> listener.onOverflow()
            // Local timestamp (LTS1 when C bit set, otherwise single-byte LTS2)
            b and 0x0F == 0x00 -> if (b and 0x80 != 0) state = State.CONTINUATION
            // Extension packet
            b and 0x0B == 0x08 -> if (b and 0x80 != 0) state = State.CONTINUATION
            // Global timestamp packets GTS1 / GTS2, always followed by continuation-coded payload
            b == 0x94 || b == 0xB4 -> state = State.CONTINUATION
            // Reserved encodings: ignore
            else -> Unit
        }
    }
}
