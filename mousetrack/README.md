# mousetrack

`mousetrack` is a small (~5KB) Java 8+ terminal mouse-tracking utility, part of the [java-miniterm](../README.md) project.

It provides static helpers for enabling and disabling terminal mouse-event reporting protocols by writing the appropriate ANSI DEC private-mode sequences to any `Appendable`, and for detecting and parsing the escape sequences that the terminal sends back into structured `MouseEvent` objects.

Three wire formats are supported: legacy X10, SGR (recommended), and URXVT.
For pixel-accurate coordinates, SGR can be combined with the SGR-Pixels encoding (mode 1016).

## Usage

### Enabling mouse tracking

Choose a [protocol](#protocols) and write the enable sequence to the terminal output. Pair an [encoding](#encodings) with it when you need coordinates beyond column/row 223 — `SGR` is the recommended choice.

```java
import org.codejive.miniterm.mousetrack.MouseTracking;

MouseTracking.enable(terminal, MouseTracking.Protocol.NORMAL);
MouseTracking.enableEncoding(terminal, MouseTracking.Encoding.SGR);
```

Always disable tracking before exiting — ideally in a `finally` block — so the terminal is left in a clean state:

```java
try {
    // … read and handle events …
} finally {
    MouseTracking.disableEncoding(terminal, MouseTracking.Encoding.SGR);
    MouseTracking.disable(terminal, MouseTracking.Protocol.NORMAL);
}
```

### Detecting and parsing mouse events

After reading an escape sequence (e.g. via `AnsiReader` from the `ansiparser` module), check whether it is a mouse event and decode it:

```java
import org.codejive.miniterm.mousetrack.MouseEvent;
import org.codejive.miniterm.mousetrack.MouseTracking;

if (MouseTracking.isMouseEvent(seq)) {
    MouseEvent ev = MouseTracking.parse(seq);
    System.out.printf("%-8s %-12s at (%d, %d)%n",
            ev.type(), ev.button(), ev.x(), ev.y());
}
```

`MouseEvent` exposes:

| Method | Description |
|---|---|
| `type()` | `PRESS`, `RELEASE`, `MOVE`, `DRAG`, or `SCROLL` |
| `button()` | `LEFT`, `MIDDLE`, `RIGHT`, `SCROLL_UP`, `SCROLL_DOWN`, or `NONE` |
| `x()` | 1-based column |
| `y()` | 1-based row |
| `shift()` | Shift modifier held |
| `alt()` | Alt/Meta modifier held |
| `ctrl()` | Ctrl modifier held |

### Full example with `miniterm` and `ansiparser`

```java
import org.codejive.miniterm.Terminal;
import org.codejive.miniterm.ansiparser.AnsiReader;
import org.codejive.miniterm.mousetrack.MouseEvent;
import org.codejive.miniterm.mousetrack.MouseTracking;

try (Terminal terminal = Terminal.create()) {
    terminal.enableRawMode();
    MouseTracking.enable(terminal, MouseTracking.Protocol.BUTTON_MOTION);
    MouseTracking.enableEncoding(terminal, MouseTracking.Encoding.SGR);
    try {
        AnsiReader reader = new AnsiReader(() -> terminal.read(1000));
        String token;
        while ((token = reader.read()) != null) {
            if (token.isEmpty()) continue; // timeout
            if (!token.startsWith("\u001b") && token.charAt(0) == 3) break; // Ctrl+C
            if (MouseTracking.isMouseEvent(token)) {
                MouseEvent ev = MouseTracking.parse(token);
                System.out.println(ev);
            }
        }
    } finally {
        MouseTracking.disableEncoding(terminal, MouseTracking.Encoding.SGR);
        MouseTracking.disable(terminal, MouseTracking.Protocol.BUTTON_MOTION);
    }
}
```

## Protocols

| Constant | DEC mode | Reports |
|---|---|---|
| `Protocol.X10` | `?9` | Button-press events only |
| `Protocol.NORMAL` | `?1000` | Button press and release |
| `Protocol.BUTTON_MOTION` | `?1002` | Press, release, and drag (motion while a button is held) |
| `Protocol.ANY_MOTION` | `?1003` | Press, release, drag, and hover (all mouse movement) |

`ANY_MOTION` can generate a very large number of events; use it only when needed.

## Encodings

| Constant | DEC mode | Coordinate limit | Notes |
|---|---|---|---|
| *(default)* | — | 223 cols/rows | Legacy X10 byte encoding; no mode to enable |
| `Encoding.UTF8` | `?1005` | ~2047 | Deprecated by many terminals |
| `Encoding.SGR` | `?1006` | Unlimited | **Recommended** — decimal fields, unambiguous release |
| `Encoding.URXVT` | `?1015` | Unlimited | Decimal fields but does not identify the released button |
| `Encoding.SGR_PIXELS` | `?1016` | Unlimited | Reports **pixel coordinates** instead of cell coordinates; requires `SGR` to be enabled first |

### SGR-Pixels (pixel-accurate coordinates)

`Encoding.SGR_PIXELS` (DEC mode 1016) is an extension of SGR that makes the terminal report the exact pixel position of the cursor rather than its character-cell position. Enable it together with `Encoding.SGR`:

```java
MouseTracking.enable(terminal, MouseTracking.Protocol.NORMAL);
MouseTracking.enableEncoding(terminal, MouseTracking.Encoding.SGR);
MouseTracking.enableEncoding(terminal, MouseTracking.Encoding.SGR_PIXELS);
try {
    // ev.x() / ev.y() are now pixel offsets from the top-left corner of the terminal window
} finally {
    MouseTracking.disableEncoding(terminal, MouseTracking.Encoding.SGR_PIXELS);
    MouseTracking.disableEncoding(terminal, MouseTracking.Encoding.SGR);
    MouseTracking.disable(terminal, MouseTracking.Protocol.NORMAL);
}
```

Not all terminals support mode 1016; check the terminal's documentation before relying on it.

## Adding the dependency

### JBang

```java
//DEPS org.codejive.miniterm:mousetrack:0.1.3
```

### Maven

```xml
<dependency>
    <groupId>org.codejive.miniterm</groupId>
    <artifactId>mousetrack</artifactId>
    <version>0.1.3</version>
</dependency>
```

### Gradle

```kotlin
implementation("org.codejive.miniterm:mousetrack:0.1.3")
```

## Building

```bash
./mvnw clean install
```
