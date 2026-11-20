package com.itorius.swo.core

import com.fazecast.jSerialComm.SerialPort
import java.io.IOException
import java.io.InputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.net.SocketTimeoutException

/** A byte stream carrying SWO data. */
interface SwoSource : AutoCloseable {
    val description: String

    @Throws(IOException::class)
    fun open()

    /** Blocks for a short time. Returns the number of bytes read, 0 on timeout, -1 at end of stream. */
    @Throws(IOException::class)
    fun read(buffer: ByteArray): Int

    override fun close()
}

/**
 * SWO pin wired to a USB-UART adapter (or a probe exposing SWO as a virtual COM port).
 * SWO in NRZ/UART mode is always 8 data bits, no parity, 1 stop bit.
 */
class SerialSwoSource(private val portName: String, private val baudRate: Int) : SwoSource {
    private var port: SerialPort? = null

    override val description: String get() = "$portName @ ${formatBaud(baudRate)}"

    override fun open() {
        if (portName.isBlank()) throw IOException("No serial port selected")
        val p = try {
            SerialPort.getCommPort(portName)
        } catch (e: Exception) {
            throw IOException("Serial port $portName not found", e)
        }
        p.setComPortParameters(baudRate, 8, SerialPort.ONE_STOP_BIT, SerialPort.NO_PARITY)
        p.setFlowControl(SerialPort.FLOW_CONTROL_DISABLED)
        p.setComPortTimeouts(SerialPort.TIMEOUT_READ_SEMI_BLOCKING, 100, 0)
        if (!p.openPort()) {
            throw IOException("Cannot open $portName (port is in use or does not exist)")
        }
        port = p
    }

    override fun read(buffer: ByteArray): Int {
        val p = port ?: return -1
        val n = p.readBytes(buffer, buffer.size)
        if (n < 0) throw IOException("Serial port $portName was disconnected")
        return n
    }

    override fun close() {
        port?.closePort()
        port = null
    }
}

/**
 * SWO data served over TCP by a debug server, e.g. OpenOCD
 * (`<target>.tpiu configure -protocol uart -output :3443 ...`).
 */
class TcpSwoSource(private val host: String, private val port: Int) : SwoSource {
    @Volatile
    private var socket: Socket? = null
    private var input: InputStream? = null

    override val description: String get() = "$host:$port"

    override fun open() {
        val s = Socket()
        try {
            s.connect(InetSocketAddress(host, port), 3000)
            s.soTimeout = 200
            s.tcpNoDelay = true
        } catch (e: IOException) {
            s.close()
            throw IOException("Cannot connect to $host:$port (${e.message})", e)
        }
        socket = s
        input = s.getInputStream()
    }

    override fun read(buffer: ByteArray): Int {
        val stream = input ?: return -1
        return try {
            stream.read(buffer)
        } catch (_: SocketTimeoutException) {
            0
        }
    }

    override fun close() {
        try {
            socket?.close()
        } catch (_: IOException) {
        }
        socket = null
        input = null
    }
}

fun formatBaud(baud: Int): String = when {
    baud >= 1_000_000 && baud % 1_000_000 == 0 -> "${baud / 1_000_000} Mbaud"
    baud >= 1_000_000 -> "%.3f Mbaud".format(baud / 1_000_000.0).trimEnd('0')
    else -> "$baud baud"
}

/** Serial ports currently present on the system as (system name, human readable description). */
fun listSerialPorts(): List<Pair<String, String>> =
    try {
        SerialPort.getCommPorts()
            .map { it.systemPortName to it.descriptivePortName }
            .sortedBy { it.first }
    } catch (_: Throwable) {
        emptyList()
    }
