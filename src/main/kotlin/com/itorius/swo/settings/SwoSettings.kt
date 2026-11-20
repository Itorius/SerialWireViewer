package com.itorius.swo.settings

import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.components.service
import com.intellij.openapi.project.Project
import com.intellij.util.messages.Topic

enum class SourceType(val displayName: String) {
    SERIAL("Serial port (USB-UART on SWO pin)"),
    TCP("TCP server (OpenOCD, debug server)"),
}

/** Per-project SWO viewer configuration, stored in .idea/serialWireViewer.xml. */
@Service(Service.Level.PROJECT)
@State(name = "SerialWireViewerSettings", storages = [Storage("serialWireViewer.xml")])
class SwoSettings : PersistentStateComponent<SwoSettings.SettingsState> {

    class SettingsState {
        var sourceType: SourceType = SourceType.SERIAL
        var serialPort: String = ""
        var baudRate: Int = 2_000_000
        var tcpHost: String = "localhost"
        var tcpPort: Int = 3443

        /** Decode ITM packets. When off, the raw byte stream is shown as text. */
        var decodeItm: Boolean = true

        /** Bit mask of ITM stimulus ports (0..31) to display. */
        var enabledPortMask: Long = 1L

        var separateTabs: Boolean = true
        var combinedTab: Boolean = true
        var showTimestamps: Boolean = false
        var clearOnConnect: Boolean = false

        fun isPortEnabled(port: Int): Boolean = enabledPortMask and (1L shl port) != 0L

        fun setPortEnabled(port: Int, enabled: Boolean) {
            enabledPortMask = if (enabled) enabledPortMask or (1L shl port) else enabledPortMask and (1L shl port).inv()
        }

        fun enabledPorts(): List<Int> = (0 until ITM_PORT_COUNT).filter(::isPortEnabled)
    }

    private var state = SettingsState()

    override fun getState(): SettingsState = state

    override fun loadState(state: SettingsState) {
        this.state = state
    }

    companion object {
        const val ITM_PORT_COUNT = 32

        fun getInstance(project: Project): SwoSettings = project.service()

        val BAUD_PRESETS = listOf(
            115_200, 230_400, 460_800, 921_600, 1_000_000, 2_000_000,
            3_000_000, 4_000_000, 6_000_000, 8_000_000, 12_000_000,
        )
    }
}

fun interface SwoSettingsListener {
    fun settingsChanged()

    companion object {
        @JvmField
        @Topic.ProjectLevel
        val TOPIC = Topic(SwoSettingsListener::class.java, Topic.BroadcastDirection.NONE)
    }
}
