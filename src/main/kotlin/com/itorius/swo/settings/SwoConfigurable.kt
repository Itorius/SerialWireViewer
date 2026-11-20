package com.itorius.swo.settings

import com.intellij.openapi.options.BoundConfigurable
import com.intellij.openapi.project.Project
import com.intellij.openapi.ui.ComboBox
import com.intellij.openapi.ui.DialogPanel
import com.intellij.openapi.ui.ValidationInfo
import com.intellij.ui.CollectionComboBoxModel
import com.intellij.ui.dsl.listCellRenderer.textListCellRenderer
import com.intellij.ui.components.JBCheckBox
import com.intellij.ui.dsl.builder.MutableProperty
import com.intellij.ui.dsl.builder.bindIntText
import com.intellij.ui.dsl.builder.bindItem
import com.intellij.ui.dsl.builder.bindSelected
import com.intellij.ui.dsl.builder.bindText
import com.intellij.ui.dsl.builder.columns
import com.intellij.ui.dsl.builder.panel
import com.intellij.ui.layout.selected
import com.intellij.ui.layout.selectedValueMatches
import com.itorius.swo.core.listSerialPorts

/** Settings | Tools | Serial Wire Viewer */
class SwoConfigurable(private val project: Project) : BoundConfigurable("Serial Wire Viewer") {

    private val state get() = SwoSettings.getInstance(project).state

    override fun createPanel(): DialogPanel {
        val portDescriptions = mutableMapOf<String, String>()
        val portModel = CollectionComboBoxModel<String>()
        fun reloadPorts(keep: String?) {
            val ports = listSerialPorts()
            portDescriptions.clear()
            ports.forEach { (name, description) -> portDescriptions[name] = description }
            val names = ports.map { it.first }.toMutableList()
            if (!keep.isNullOrBlank() && keep !in names) names.add(0, keep)
            portModel.replaceAll(names)
            portModel.selectedItem = keep
        }
        reloadPorts(state.serialPort)

        val portBoxes = mutableListOf<JBCheckBox>()

        return panel {
            group("Connection") {
                lateinit var sourceCombo: ComboBox<SourceType>
                row("Source:") {
                    sourceCombo = comboBox(SourceType.entries, textListCellRenderer { it?.displayName })
                        .bindItem({ state.sourceType }, { state.sourceType = it ?: SourceType.SERIAL })
                        .component
                }

                rowsRange {
                    row("Serial port:") {
                        val combo = ComboBox(portModel).apply {
                            isEditable = true
                            renderer = textListCellRenderer { name: String ->
                                portDescriptions[name]?.takeIf { it != name }?.let { "$name  ($it)" } ?: name
                            }
                        }
                        cell(combo)
                            .columns(24)
                            .bind(
                                { c -> (c.editor.item as? String)?.trim().orEmpty() },
                                { c, v -> c.selectedItem = v },
                                MutableProperty({ state.serialPort }, { state.serialPort = it }),
                            )
                        button("Refresh") { reloadPorts(combo.editor.item as? String) }
                    }
                    row("Speed (baud):") {
                        val combo = ComboBox(SwoSettings.BAUD_PRESETS.map { it.toString() }.toTypedArray()).apply {
                            isEditable = true
                        }
                        cell(combo)
                            .columns(12)
                            .bind(
                                { c -> (c.editor.item as? String)?.trim().orEmpty() },
                                { c, v -> c.selectedItem = v },
                                MutableProperty({ state.baudRate.toString() }, { v -> v.toIntOrNull()?.let { state.baudRate = it } }),
                            )
                            .validationOnApply {
                                val v = (it.editor.item as? String)?.trim()?.toIntOrNull()
                                if (v == null || v <= 0) ValidationInfo("Enter a positive baud rate", it) else null
                            }
                            .comment("Must match the SWO pin frequency configured on the target (TPIU ACPR prescaler).")
                    }
                }.visibleIf(sourceCombo.selectedValueMatches { it == SourceType.SERIAL })

                rowsRange {
                    row("Host:") {
                        textField().bindText({ state.tcpHost }, { state.tcpHost = it.trim() }).columns(20)
                    }
                    row("Port:") {
                        intTextField(1..65535).bindIntText({ state.tcpPort }, { state.tcpPort = it })
                            .comment(
                                "The SWO speed is configured in the debug server. OpenOCD example:<br>" +
                                    "<code>stm32f4x.tpiu configure -protocol uart -output :3443 " +
                                    "-traceclk 168000000 -pin-freq 2000000</code>"
                            )
                    }
                }.visibleIf(sourceCombo.selectedValueMatches { it == SourceType.TCP })
            }

            group("Decoding") {
                lateinit var decode: JBCheckBox
                row {
                    decode = checkBox("Decode ITM packets")
                        .bindSelected({ state.decodeItm }, { state.decodeItm = it })
                        .comment("Turn off to display the raw byte stream as text (e.g. a plain UART on the SWO pin).")
                        .component
                }

                group("Stimulus Ports to Show") {
                    row {
                        comment("Port 0 is used by CMSIS <code>ITM_SendChar()</code> / printf retargeting.")
                    }
                    for (r in 0 until SwoSettings.ITM_PORT_COUNT / 8) {
                        row {
                            for (c in 0 until 8) {
                                val port = r * 8 + c
                                portBoxes += checkBox(port.toString().padStart(2))
                                    .bindSelected({ state.isPortEnabled(port) }, { state.setPortEnabled(port, it) })
                                    .component
                            }
                        }
                    }
                    row {
                        link("Select all") { portBoxes.forEach { it.isSelected = true } }
                        link("Select none") { portBoxes.forEach { it.isSelected = false } }
                        link("Only port 0") { portBoxes.forEachIndexed { i, b -> b.isSelected = i == 0 } }
                    }
                }.enabledIf(decode.selected)
            }

            group("Display") {
                row {
                    checkBox("Separate tab for each port")
                        .bindSelected({ state.separateTabs }, { state.separateTabs = it })
                }
                row {
                    checkBox("Combined \"All Ports\" tab (when more than one port is shown)")
                        .bindSelected({ state.combinedTab }, { state.combinedTab = it })
                }
                row {
                    checkBox("Prefix lines with host timestamps")
                        .bindSelected({ state.showTimestamps }, { state.showTimestamps = it })
                }
                row {
                    checkBox("Clear consoles on connect")
                        .bindSelected({ state.clearOnConnect }, { state.clearOnConnect = it })
                }
            }
        }
    }

    override fun apply() {
        super.apply()
        project.messageBus.syncPublisher(SwoSettingsListener.TOPIC).settingsChanged()
    }
}
