# ansiparser

`ansiparser` is a compact (~5KB) Java 8+ ANSI escape sequence parser, part of the [java-miniterm](../README.md) project.

It provides a finite-state-machine parser that correctly handles all common ANSI/VT escape sequences — CSI sequences, OSC/DCS string commands, charset designators, and plain two-character sequences — with built-in DoS protection against runaway sequences.

## Usage

Two entry points are available depending on whether you prefer a stateless or stateful style.

### Stateless — `AnsiParser`

`AnsiParser.parse(IntSupplier)` reads the next token from any `IntSupplier` that returns raw character codes. Negative values are treated as EOF/timeout.

```java
import org.codejive.miniterm.ansiparser.AnsiParser;

// Supply characters from wherever you want
IntSupplier source = () -> inputStream.read();

String token;
while ((token = AnsiParser.parse(source)) != null) {
    if (token.startsWith("\u001b")) {
        System.out.println("Escape sequence: " + token.substring(1));
    } else {
        System.out.println("Plain char: " + token);
    }
}
```

### Stateful — `AnsiReader`

`AnsiReader` wraps an `IntSupplier` and lets you call `read()` repeatedly on the same instance.

```java
import org.codejive.miniterm.ansiparser.AnsiReader;

AnsiReader reader = new AnsiReader(() -> inputStream.read());

String token;
while ((token = reader.read()) != null) {
    System.out.println("Token: " + token);
}
```

To avoid allocating a `String` per token, use the `Appendable` overload:

```java
StringBuilder sb = new StringBuilder();
int result;
while ((result = reader.read(sb)) >= 0 || sb.length() > 0) {
    System.out.println("Token: " + sb);
    sb.setLength(0);
}
```

### Pairing with `miniterm`

`ansiparser` is designed to work naturally alongside `miniterm` or `miniterm-ffm`:

```java
import org.codejive.miniterm.Terminal;
import org.codejive.miniterm.ansiparser.AnsiReader;

try (Terminal terminal = Terminal.create()) {
    terminal.enableRawMode();
    AnsiReader reader = new AnsiReader(() -> terminal.read(1000));
    String token;
    while ((token = reader.read()) != null) {
        if (token.isEmpty()) {
            // Timeout
        } else if (token.equals("\u001b[A")) {
            System.out.println("Up arrow");
        } else if (!token.startsWith("\u001b") && token.charAt(0) == 3) {
            break; // Ctrl+C
        }
    }
}
```

## Adding the dependency

### JBang

```java
//DEPS org.codejive.miniterm:ansiparser:0.1.1
```

### Maven

```xml
<dependency>
    <groupId>org.codejive.miniterm</groupId>
    <artifactId>ansiparser</artifactId>
    <version>0.1.1</version>
</dependency>
```

### Gradle

```kotlin
implementation("org.codejive.miniterm:ansiparser:0.1.1")
```

## Building

```bash
./mvnw clean install
```
