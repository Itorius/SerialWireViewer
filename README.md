# Serial Wire Viewer

An IntelliJ Platform plugin for **CLion** (and other JetBrains IDEs) that shows Serial Wire Output (SWO)
from ARM Cortex-M microcontrollers in its own **SWO Viewer** tool window.

## Features

- **Sources**
  - *Serial port*: a USB-UART adapter wired to the SWO pin, or a probe that exposes SWO as a virtual COM port.
    Always 8N1; the baud rate must match the SWO pin frequency configured on the target.
  - *TCP*: SWO data streamed by a debug server, e.g. OpenOCD:
    `stm32f4x.tpiu configure -protocol uart -output :3443 -traceclk 168000000 -pin-freq 2000000`
    followed by `stm32f4x.tpiu enable`.
- **ITM decoding** of instrumentation packets, with sync, timestamp, extension, overflow and DWT packets skipped.
  You can turn it off to show the raw byte stream.
- **Stimulus port selection**: choose which of the 32 ITM ports to show. Each port can get its own tab,
  and there is an optional combined "All Ports" tab where lines are prefixed with `[port]`.
- Optional host timestamps per line, and an option to clear the consoles on connect.

## Usage

1. Open **Settings | Tools | Serial Wire Viewer**, pick the source, port and speed, and tick the stimulus ports to show.
2. Open the **SWO Viewer** tool window (bottom) and press **Connect**.

Settings are stored per project in `.idea/serialWireViewer.xml`.

On the target, enable ITM/SWO and write to stimulus port 0, e.g. with CMSIS `ITM_SendChar()`:

```c
int _write(int fd, char *buf, int len) {
    for (int i = 0; i < len; i++) ITM_SendChar(buf[i]);
    return len;
}
```

## Building

```
./gradlew buildPlugin      # -> build/distributions/SerialWireViewer-<version>.zip
./gradlew test
./gradlew runIde           # sandbox IDE; switch intellijIdea(...) to clion(...) in build.gradle.kts to run CLion
```

Install the zip through **Settings | Plugins | ⚙ | Install Plugin from Disk...**.

## Project layout

```
src/main/kotlin/com/itorius/swo/
├── core/      ItmDecoder, PortTextBuffer, SwoSource (serial/TCP), SwoConnectionService
├── settings/  SwoSettings (persistent state), SwoConfigurable (settings UI)
└── ui/        SwoToolWindowFactory, SwoPanel (toolbar + console tabs)
```
