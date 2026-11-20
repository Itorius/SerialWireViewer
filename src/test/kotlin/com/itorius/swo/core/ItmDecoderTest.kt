package com.itorius.swo.core

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ItmDecoderTest {

    private class Collector : ItmDecoder.Listener {
        val ports = mutableMapOf<Int, StringBuilder>()
        var overflows = 0
        override fun onInstrumentation(port: Int, data: ByteArray, length: Int) {
            val sb = ports.getOrPut(port) { StringBuilder() }
            for (i in 0 until length) sb.append((data[i].toInt() and 0xFF).toChar())
        }

        override fun onOverflow() {
            overflows++
        }
    }

    private fun decode(vararg bytes: Int): Collector {
        val c = Collector()
        ItmDecoder(c).feed(ByteArray(bytes.size) { bytes[it].toByte() })
        return c
    }

    /** 8-bit write to stimulus port [port]. */
    private fun stim8(port: Int, ch: Char) = intArrayOf((port shl 3) or 0x01, ch.code)

    @Test
    fun singleByteWritesOnPort0() {
        val c = decode(*stim8(0, 'H'), *stim8(0, 'i'))
        assertEquals("Hi", c.ports[0].toString())
    }

    @Test
    fun portsAreDemultiplexed() {
        val c = decode(*stim8(0, 'a'), *stim8(1, 'b'), *stim8(31, 'c'), *stim8(0, 'd'))
        assertEquals("ad", c.ports[0].toString())
        assertEquals("b", c.ports[1].toString())
        assertEquals("c", c.ports[31].toString())
    }

    @Test
    fun wordWritesAreLittleEndian() {
        // 32-bit write to port 2: header (2<<3)|0b11
        val c = decode((2 shl 3) or 0x03, 'a'.code, 'b'.code, 'c'.code, 'd'.code)
        assertEquals("abcd", c.ports[2].toString())
    }

    @Test
    fun halfWordWrite() {
        val c = decode((5 shl 3) or 0x02, 'x'.code, 'y'.code)
        assertEquals("xy", c.ports[5].toString())
    }

    @Test
    fun syncTimestampsAndHardwarePacketsAreSkipped() {
        val c = decode(
            0x00, 0x00, 0x00, 0x00, 0x00, 0x80,   // sync
            *stim8(0, 'A'),
            0xC0, 0x81, 0x82, 0x03,               // local timestamp with continuation
            0x30,                                 // single byte local timestamp
            0x05, 0x01,                           // hardware (DWT) packet, 1 byte payload
            0x94, 0x81, 0x01,                     // global timestamp 1
            0x08,                                 // extension without continuation
            *stim8(0, 'B'),
            0x70,                                 // overflow
            *stim8(0, 'C'),
        )
        assertEquals("ABC", c.ports[0].toString())
        assertEquals(1, c.overflows)
    }

    @Test
    fun payloadSplitAcrossFeeds() {
        val col = Collector()
        val d = ItmDecoder(col)
        d.feed(byteArrayOf(((3 shl 3) or 0x03).toByte(), 'w'.code.toByte()))
        d.feed(byteArrayOf('x'.code.toByte(), 'y'.code.toByte(), 'z'.code.toByte()))
        assertEquals("wxyz", col.ports[3].toString())
    }
}

class PortTextBufferTest {
    @Test
    fun keepsIncompleteUtf8Sequence() {
        val bytes = "č".toByteArray(Charsets.UTF_8)
        val b = PortTextBuffer()
        b.append(byteArrayOf('a'.code.toByte(), bytes[0]), 0, 2)
        assertEquals("a", b.drainText())
        b.append(byteArrayOf(bytes[1]), 0, 1)
        assertEquals("č", b.drainText())
    }

    @Test
    fun stripsCarriageReturnAndNul() {
        val b = PortTextBuffer()
        val data = "ab\r\n\u0000".toByteArray()
        b.append(data, 0, data.size)
        assertEquals("ab\n", b.drainText())
        assertNull(b.drainText())
    }
}
