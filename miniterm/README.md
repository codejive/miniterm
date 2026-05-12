# miniterm

`miniterm` is the legacy Java 8+ implementation of the [java-miniterm](../README.md) library — a minimal (~25KB) low-level terminal access library for Java.

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

### JBang

```java
//DEPS org.codejive.miniterm:miniterm:0.1.5
```

### Maven

```xml
<dependency>
    <groupId>org.codejive.miniterm</groupId>
    <artifactId>miniterm</artifactId>
    <version>0.1.5</version>
</dependency>
```

### Gradle

```kotlin
implementation("org.codejive.miniterm:miniterm:0.1.5")
```

## Building

```bash
./mvnw clean install
```

## Running examples

The `examples/` folder in the project root contains several ready-to-run examples. Use the provided scripts to pick and run one interactively:

**Linux/macOS:**

```bash
./examples/run
```

**Windows:**

```batch
examples\run.bat
```

The scripts will list the available examples and let you choose one to run:

- **PrintSize** — prints the current terminal dimensions
- **WatchSize** — watches and prints terminal size changes in real time
- **PrintKeys** — prints the code of each key pressed (Ctrl+C to exit)
