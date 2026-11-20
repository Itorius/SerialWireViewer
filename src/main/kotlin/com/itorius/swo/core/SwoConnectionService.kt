package com.itorius.swo.core

import com.intellij.openapi.Disposable
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.service
import com.intellij.openapi.diagnostic.thisLogger
import com.intellij.openapi.project.Project
import com.intellij.openapi.util.Disposer
import com.intellij.util.containers.ContainerUtil
import com.itorius.swo.settings.SourceType
import com.itorius.swo.settings.SwoSettings

/** Owns the connection to the SWO source and distributes decoded text to listeners. */
@Service(Service.Level.PROJECT)
class SwoConnectionService(private val project: Project) : Disposable {

    interface Listener {
        /** Called on a background thread. [port] is the ITM stimulus port, or [RAW_PORT] when ITM decoding is off. */
        fun textReceived(port: Int, text: String)

        /** Called on a background thread. */
        fun statusChanged(status: Status, message: String)

        /** Called on a background thread when the target reports an ITM overflow. */
        fun overflow() {}
    }

    enum class Status { CONNECTING, CONNECTED, DISCONNECTED, ERROR }

    private val listeners = ContainerUtil.createLockFreeCopyOnWriteList<Listener>()

    @Volatile
    private var worker: Worker? = null

    @Volatile
    var currentDescription: String? = null
        private set

    val isConnected: Boolean get() = worker != null

    fun addListener(listener: Listener, parent: Disposable) {
        listeners.add(listener)
        Disposer.register(parent) { listeners.remove(listener) }
    }

    fun connect() {
        synchronized(this) {
            if (worker != null) return
            val settings = SwoSettings.getInstance(project).state
            val source = when (settings.sourceType) {
                SourceType.SERIAL -> SerialSwoSource(settings.serialPort, settings.baudRate)
                SourceType.TCP -> TcpSwoSource(settings.tcpHost, settings.tcpPort)
            }
            val w = Worker(source, settings.decodeItm)
            worker = w
            currentDescription = source.description
            ApplicationManager.getApplication().executeOnPooledThread(w)
        }
    }

    fun disconnect() {
        worker?.stop()
    }

    override fun dispose() {
        disconnect()
    }

    private fun fireStatus(status: Status, message: String) = listeners.forEach { it.statusChanged(status, message) }

    private inner class Worker(private val source: SwoSource, private val decodeItm: Boolean) : Runnable {
        @Volatile
        private var running = true

        private val buffers = arrayOfNulls<PortTextBuffer>(SwoSettings.ITM_PORT_COUNT + 1)
        private val decoder = ItmDecoder(object : ItmDecoder.Listener {
            override fun onInstrumentation(port: Int, data: ByteArray, length: Int) = buffer(port).append(data, 0, length)
            override fun onOverflow() = listeners.forEach { it.overflow() }
        })

        private fun buffer(port: Int) = buffers[port] ?: PortTextBuffer().also { buffers[port] = it }

        fun stop() {
            running = false
            // Closing unblocks a pending read on the reader thread.
            try {
                source.close()
            } catch (_: Exception) {
            }
        }

        override fun run() {
            var finalStatus = Status.DISCONNECTED
            var finalMessage = "Disconnected from ${source.description}"
            try {
                fireStatus(Status.CONNECTING, "Connecting to ${source.description}...")
                source.open()
                fireStatus(Status.CONNECTED, "Connected to ${source.description}")
                val chunk = ByteArray(16 * 1024)
                while (running) {
                    val n = source.read(chunk)
                    if (n < 0) {
                        if (running) finalMessage = "Connection closed by ${source.description}"
                        break
                    }
                    if (n == 0) continue
                    if (decodeItm) decoder.feed(chunk, 0, n) else buffer(RAW_PORT).append(chunk, 0, n)
                    flush()
                }
            } catch (e: Exception) {
                if (running) {
                    thisLogger().info("SWO connection failed", e)
                    finalStatus = Status.ERROR
                    finalMessage = e.message ?: e.javaClass.simpleName
                }
            } finally {
                try {
                    source.close()
                } catch (_: Exception) {
                }
                synchronized(this@SwoConnectionService) {
                    if (worker === this) {
                        worker = null
                        currentDescription = null
                    }
                }
                fireStatus(finalStatus, finalMessage)
            }
        }

        private fun flush() {
            for (port in buffers.indices) {
                val b = buffers[port] ?: continue
                if (b.isEmpty) continue
                val text = b.drainText() ?: continue
                listeners.forEach { it.textReceived(port, text) }
            }
        }
    }

    companion object {
        /** Pseudo port used for undecoded (raw) data. */
        const val RAW_PORT = SwoSettings.ITM_PORT_COUNT

        fun getInstance(project: Project): SwoConnectionService = project.service()
    }
}
