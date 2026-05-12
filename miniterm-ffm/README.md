# miniterm-ffm

`miniterm-ffm` is the modern Java 22+ implementation of the [java-miniterm](../README.md) library — a minimal (~25KB) low-level terminal access library for Java, using the Foreign Function & Memory (FFM) API for native calls.

> **Requires Java 22+.** For a Java 8+ compatible implementation see [`miniterm`](../miniterm/README.md).

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

## Adding the dependency

> **Note:** When using `miniterm-ffm` you may need to pass `--enable-native-access=org.codejive.miniterm.ffm` (or `ALL-UNNAMED` for classpath usage) to the JVM.

### JBang

```java
//DEPS org.codejive.miniterm:miniterm-ffm:0.1.1
```

### Maven

```xml
<dependency>
    <groupId>org.codejive.miniterm</groupId>
    <artifactId>miniterm-ffm</artifactId>
    <version>0.1.1</version>
</dependency>
```

### Gradle

```kotlin
implementation("org.codejive.miniterm:miniterm-ffm:0.1.1")
```

## Building

```bash
./mvnw clean install
```

## Running examples

The `examples/` folder in the project root contains several ready-to-run examples. Use the provided scripts to pick and run one interactively using the FFM implementation:

**Linux/macOS:**

```bash
./examples/run-ffm
```

**Windows:**

```batch
examples\run-ffm.bat
```

The scripts will list the available examples and let you choose one to run:

- **PrintSize** — prints the current terminal dimensions
- **WatchSize** — watches and prints terminal size changes in real time
- **PrintKeys** — prints the code of each key pressed (Ctrl+C to exit)
