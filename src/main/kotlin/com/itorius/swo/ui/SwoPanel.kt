package com.itorius.swo.ui

import com.intellij.execution.filters.TextConsoleBuilderFactory
import com.intellij.execution.ui.ConsoleView
import com.intellij.execution.ui.ConsoleViewContentType
import com.intellij.ide.ActivityTracker
import com.intellij.icons.AllIcons
import com.intellij.openapi.Disposable
import com.intellij.openapi.actionSystem.ActionManager
import com.intellij.openapi.actionSystem.ActionUpdateThread
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.actionSystem.DefaultActionGroup
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.options.ShowSettingsUtil
import com.intellij.openapi.project.DumbAwareAction
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.SimpleToolWindowPanel
import com.intellij.openapi.util.Disposer
import com.intellij.ui.components.JBLabel
import com.intellij.ui.components.JBTabbedPane
import com.intellij.util.ui.JBUI
import com.intellij.util.ui.UIUtil
import com.itorius.swo.core.SwoConnectionService
import com.itorius.swo.core.SwoConnectionService.Status
import com.itorius.swo.settings.SwoConfigurable
import com.itorius.swo.settings.SwoSettings
import com.itorius.swo.settings.SwoSettingsListener
import java.awt.BorderLayout
import java.time.LocalTime
import java.time.format.DateTimeFormatter
import javax.swing.JPanel

/** Tool window content: a toolbar plus one console tab per displayed stimulus port. */
class SwoPanel(private val project: Project) : SimpleToolWindowPanel(true, true), Disposable {

    private val service = SwoConnectionService.getInstance(project)
    private val tabs = JBTabbedPane()
    private val statusLabel = JBLabel().apply {
        foreground = UIUtil.getContextHelpForeground()
        border = JBUI.Borders.emptyRight(8)
    }

    /** Immutable snapshot read from the reader thread; replaced on the EDT when settings change. */
    @Volatile
    private var sinks = Sinks.EMPTY
    private var consolesDisposable: Disposable? = null

    init {
        val group = DefaultActionGroup().apply {
            add(ConnectAction())
            add(DisconnectAction())
            addSeparator()
            add(ClearAction())
            addSeparator()
            add(SettingsAction())
        }
        val toolbar = ActionManager.getInstance().createActionToolbar("SwoViewer", group, true)
        toolbar.targetComponent = this
        val top = JPanel(BorderLayout()).apply {
            add(toolbar.component, BorderLayout.WEST)
            add(statusLabel, BorderLayout.EAST)
        }
        setToolbar(top)
        setContent(tabs)

        rebuildConsoles()
        updateStatusLabel(if (service.isConnected) "Connected to ${service.currentDescription}" else "Disconnected")

        project.messageBus.connect(this).subscribe(SwoSettingsListener.TOPIC, SwoSettingsListener {
            ApplicationManager.getApplication().invokeLater({ rebuildConsoles() }, project.disposed)
        })

        service.addListener(object : SwoConnectionService.Listener {
            override fun textReceived(port: Int, text: String) {
                val s = sinks
                if (port == SwoConnectionService.RAW_PORT || s.raw != null) {
                    // Raw data (or a mode switch while connected): show it in whatever console exists.
                    (s.raw ?: s.combined ?: s.perPort.firstOrNull { it != null })?.append(port, text)
                    return
                }
                if (!s.enabled[port]) return
                s.perPort[port]?.append(port, text)
                s.combined?.append(port, text)
            }

            override fun statusChanged(status: Status, message: String) {
                val s = sinks
                if (status == Status.CONNECTING && SwoSettings.getInstance(project).state.clearOnConnect) {
                    s.all.forEach { it.clear() }
                }
                val type = if (status == Status.ERROR) ConsoleViewContentType.ERROR_OUTPUT else ConsoleViewContentType.SYSTEM_OUTPUT
                s.all.forEach { it.printStatus(message, type) }
                ApplicationManager.getApplication().invokeLater {
                    updateStatusLabel(if (status == Status.ERROR) "Error" else message)
                }
            }

            override fun overflow() {
                sinks.all.forEach { it.markOverflow() }
            }
        }, this)
    }

    private fun updateStatusLabel(text: String) {
        statusLabel.text = text
        // Re-evaluate Connect/Disconnect enablement.
        ActivityTracker.getInstance().inc()
    }

    private fun rebuildConsoles() {
        val state = SwoSettings.getInstance(project).state
        val selectedTitle = tabs.selectedIndex.takeIf { it >= 0 }?.let { tabs.getTitleAt(it) }

        val disposable = Disposer.newDisposable(this, "SWO consoles")
        fun newSink(prefixPort: Boolean): ConsoleSink {
            val console = TextConsoleBuilderFactory.getInstance().createBuilder(project).apply { setViewer(true) }.console
            Disposer.register(disposable, console)
            return ConsoleSink(console, prefixPort, state.showTimestamps)
        }

        tabs.removeAll()
        val enabled = BooleanArray(SwoSettings.ITM_PORT_COUNT) { state.isPortEnabled(it) }
        val newSinks = if (!state.decodeItm) {
            val raw = newSink(false)
            tabs.addTab("Raw", raw.console.component)
            Sinks(raw = raw, combined = null, perPort = arrayOfNulls(SwoSettings.ITM_PORT_COUNT), enabled = enabled)
        } else {
            val ports = state.enabledPorts()
            val perPort = arrayOfNulls<ConsoleSink>(SwoSettings.ITM_PORT_COUNT)
            // Combined tab: always when it's the only view, otherwise when requested and >1 port is shown.
            val wantCombined = ports.isNotEmpty() && (!state.separateTabs || (state.combinedTab && ports.size > 1))
            val combined = if (wantCombined) newSink(prefixPort = ports.size > 1).also {
                tabs.addTab("All Ports", it.console.component)
            } else null
            if (state.separateTabs) {
                for (p in ports) {
                    val sink = newSink(false)
                    perPort[p] = sink
                    tabs.addTab("Port $p", sink.console.component)
                }
            }
            if (ports.isEmpty()) {
                tabs.addTab("No Ports", JBLabel("No ITM stimulus ports are selected. Enable some in the settings.", javax.swing.SwingConstants.CENTER))
            }
            Sinks(raw = null, combined = combined, perPort = perPort, enabled = enabled)
        }
        sinks = newSinks

        consolesDisposable?.let { Disposer.dispose(it) }
        consolesDisposable = disposable

        if (selectedTitle != null) {
            val idx = tabs.indexOfTab(selectedTitle)
            if (idx >= 0) tabs.selectedIndex = idx
        }
    }

    override fun dispose() {
        sinks = Sinks.EMPTY
    }

    private class Sinks(
        val raw: ConsoleSink?,
        val combined: ConsoleSink?,
        val perPort: Array<ConsoleSink?>,
        val enabled: BooleanArray,
    ) {
        val all: List<ConsoleSink> = listOfNotNull(raw, combined) + perPort.filterNotNull()

        companion object {
            val EMPTY = Sinks(null, null, arrayOfNulls(SwoSettings.ITM_PORT_COUNT), BooleanArray(SwoSettings.ITM_PORT_COUNT))
        }
    }

    /** Writes text to a console, inserting optional timestamp and port prefixes at line starts. */
    private class ConsoleSink(val console: ConsoleView, private val prefixPort: Boolean, private val timestamps: Boolean) {
        private var atLineStart = true
        private var lastPort = -1

        @Synchronized
        fun append(port: Int, text: String) {
            if (prefixPort && port != lastPort && !atLineStart) {
                console.print("\n", ConsoleViewContentType.NORMAL_OUTPUT)
                atLineStart = true
            }
            lastPort = port
            if (!prefixPort && !timestamps) {
                console.print(text, ConsoleViewContentType.NORMAL_OUTPUT)
                atLineStart = text.endsWith('\n')
                return
            }
            var start = 0
            while (start < text.length) {
                if (atLineStart) {
                    console.print(prefix(port), ConsoleViewContentType.LOG_DEBUG_OUTPUT)
                    atLineStart = false
                }
                val nl = text.indexOf('\n', start)
                val end = if (nl < 0) text.length else nl + 1
                console.print(text.substring(start, end), ConsoleViewContentType.NORMAL_OUTPUT)
                if (nl >= 0) atLineStart = true
                start = end
            }
        }

        private fun prefix(port: Int): String = buildString {
            if (timestamps) append('[').append(LocalTime.now().format(TIME_FORMAT)).append("] ")
            if (prefixPort) append('[').append(port.toString().padStart(2)).append("] ")
        }

        @Synchronized
        fun printStatus(message: String, type: ConsoleViewContentType) {
            val lead = if (atLineStart) "" else "\n"
            console.print("$lead--- $message ---\n", type)
            atLineStart = true
            lastPort = -1
        }

        @Synchronized
        fun markOverflow() {
            console.print("<ITM overflow>", ConsoleViewContentType.ERROR_OUTPUT)
        }

        @Synchronized
        fun clear() {
            console.clear()
            atLineStart = true
            lastPort = -1
        }

        companion object {
            private val TIME_FORMAT: DateTimeFormatter = DateTimeFormatter.ofPattern("HH:mm:ss.SSS")
        }
    }

    private inner class ConnectAction : DumbAwareAction("Connect", "Start reading SWO output", AllIcons.Actions.Execute) {
        override fun getActionUpdateThread() = ActionUpdateThread.BGT
        override fun update(e: AnActionEvent) {
            e.presentation.isEnabled = !service.isConnected
        }

        override fun actionPerformed(e: AnActionEvent) = service.connect()
    }

    private inner class DisconnectAction : DumbAwareAction("Disconnect", "Stop reading SWO output", AllIcons.Actions.Suspend) {
        override fun getActionUpdateThread() = ActionUpdateThread.BGT
        override fun update(e: AnActionEvent) {
            e.presentation.isEnabled = service.isConnected
        }

        override fun actionPerformed(e: AnActionEvent) = service.disconnect()
    }

    private inner class ClearAction : DumbAwareAction("Clear All", "Clear all SWO consoles", AllIcons.Actions.GC) {
        override fun getActionUpdateThread() = ActionUpdateThread.EDT
        override fun actionPerformed(e: AnActionEvent) = sinks.all.forEach { it.clear() }
    }

    private inner class SettingsAction : DumbAwareAction("Settings...", "Configure the SWO connection and displayed ports", AllIcons.General.Settings) {
        override fun getActionUpdateThread() = ActionUpdateThread.EDT
        override fun actionPerformed(e: AnActionEvent) {
            ShowSettingsUtil.getInstance().showSettingsDialog(project, SwoConfigurable::class.java)
        }
    }
}
