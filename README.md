# java-miniterm

`miniterm` is a Java library that provides low-level terminal access. Its main selling point is that it is extremely small (**~25KB**), making it ideal for CLI tools and applications where keeping dependencies lightweight matters.

Two variants are available:
- **[`miniterm`](miniterm/README.md)** — legacy implementation, works with Java 8+
- **[`miniterm-ffm`](miniterm-ffm/README.md)** — modern implementation using the Foreign Function & Memory API, requires Java 22+

And then we have utility modules:
- **[`ansiparser`](ansiparser/README.md)** — compact ANSI escape sequence parser
- **[`mousetrack`](mousetrack/README.md)** — terminal mouse-tracking helpers and event parser

**Philosophy** : this project has been expressly created to be as minimal as possible, it only offers the most essential functionality that is missing from Java to be able to use the features of a modern Terminal. Several other projects exist that do this as well, but they normally come with a whole bunch of other things that you might not need. `miniterm` on the other hand *only* does the work that you can't do with standard Java APIs. Everything else can be built on top.

**Acknowledgements** : `miniterm` is inspired by and has unashamedly copied ideas and code from [AEsh](https://github.com/aeshell/aesh-readline) and [Tamboui Panama backend](https://github.com/tamboui/tamboui/tree/main/tamboui-panama-backend).

## Usage

```java
import org.codejive.miniterm.Terminal;

public class Example {
    public static void main(String[] args) throws Exception {
        try (Terminal terminal = Terminal.create()) {
            // Do terminal stuff here...
        }
    }
}
```

### Get terminal size

```java
var size = terminal.size();
System.out.println("The size of the terminal is " + size);
```

### Read key presses

```java
terminal.enableRawMode();
while (true) {
    int key = terminal.read(1000);
    if (key == -1 || key == 3) { // Ctrl+C
        break; // End of stream
    } else if (key >= 0) {
        System.out.println("Key pressed: " + key);
    }
}
```

### Read key presses & ANSI sequences

```java
terminal.enableRawMode();
AnsiReader reader = new AnsiReader(() -> terminal.read(-1));
String seq;
while ((seq = reader.read()) != null) {
    if (seq.startsWith("\u001b")) {
        if (seq.equals("\u001b[A")) {
            System.out.println("Up arrow");
        } else { /* etc... */ }
    } else if (seq.charAt(0) == 3) {
        break; // Ctrl+C
    } else {
        System.out.println("Key pressed: " + seq);
    }
}
```

### Handling mouse events

```java
terminal.enableRawMode();
MouseTracking.enable(terminal, MouseTracking.Protocol.NORMAL);
MouseTracking.enableEncoding(terminal, MouseTracking.Encoding.SGR);
AnsiReader reader = new AnsiReader(() -> terminal.read(-1));
String seq;
while ((seq = reader.read()) != null) {
    if (MouseTracking.isMouseEvent(seq)) {
        MouseEvent ev = MouseTracking.parse(seq);
        System.out.printf("%-8s %-12s at (%d, %d)%n",
                ev.type(), ev.button(), ev.x(), ev.y());
    }
}
```

## Modules

Three artifacts are published independently:

| Artifact | Description |
|----------|-------------|
| [`miniterm`](miniterm/README.md) | Legacy terminal implementation, Java 8+ |
| [`miniterm-ffm`](miniterm-ffm/README.md) | Modern FFM-based terminal implementation, Java 22+ |
| [`ansiparser`](ansiparser/README.md) | Compact ANSI escape sequence parser, Java 8+ |
| [`mousetrack`](mousetrack/README.md) | Terminal mouse-tracking helpers and event parser, Java 8+ |

For dependency coordinates (Maven, Gradle, JBang) and module-specific usage details, see the individual module READMEs linked above.

## Building

```bash
./mvnw clean install
```

## Running examples

The `examples/` folder contains several ready-to-run examples. Use the provided scripts to pick and run one interactively:

**Linux/macOS:**

```bash
# Using the legacy (Java 8+) implementation
./examples/run

# Using the FFM (Java 22+) implementation
./examples/run-ffm
```

**Windows:**

```batch
:: Using the legacy (Java 8+) implementation
examples\run.bat

:: Using the FFM (Java 22+) implementation
examples\run-ffm.bat
```

The scripts will list the available examples and let you choose one to run:

- **PrintAnsi** — prints the the ANSI sequence of each key pressed
- **PrintKeys** — prints the code of each key pressed
- **PrintMouse** — prints mouse events
- **PrintSize** — prints the current terminal dimensions
- **WatchSize** — watches and prints terminal size changes in real time
