# java-miniterm

`miniterm` is a Java library that provides low-level terminal access. Its main selling point is that it is extremely small (**~25KB**), making it ideal for CLI tools and applications where keeping dependencies lightweight matters.

Two variants are available:
- **`miniterm`** — legacy implementation, works with Java 8+
- **`miniterm-ffm`** — modern implementation using the Foreign Function & Memory API, requires Java 22+

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
var size = terminal.getSize();
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
//DEPS org.codejive.miniterm:miniterm:0.1.0
```

For the FFM variant (Java 22+):

```java
//DEPS org.codejive.miniterm:miniterm-ffm:0.1.0
```

### Maven

```xml
<dependency>
    <groupId>org.codejive.miniterm</groupId>
    <artifactId>miniterm</artifactId>
    <version>0.1.0</version>
</dependency>
```

For the FFM variant (Java 22+):

```xml
<dependency>
    <groupId>org.codejive.miniterm</groupId>
    <artifactId>miniterm-ffm</artifactId>
    <version>0.1.0</version>
</dependency>
```

### Gradle

```kotlin
implementation("org.codejive.miniterm:miniterm:0.1.0")
```

For the FFM variant (Java 22+):

```kotlin
implementation("org.codejive.miniterm:miniterm-ffm:0.1.0")
```

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

- **PrintSize** — prints the current terminal dimensions
- **WatchSize** — watches and prints terminal size changes in real time
- **PrintKeys** — prints the code of each key pressed (Ctrl+C to exit)
