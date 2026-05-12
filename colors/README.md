# colors

`colors` is a small Java 8+ library for querying and setting terminal colour palettes via OSC escape sequences, part of the [java-miniterm](../README.md) project.

It supports reading the full 256-entry palette in a single burst, querying the foreground/background/cursor colours, redefining palette entries, and resetting them to the profile defaults.

## Usage

### Query foreground and background

```java
Color fg = TermColors.queryForeground(terminal, in);
Color bg = TermColors.queryBackground(terminal, in);
Color cursor = TermColors.queryCursor(terminal, in);

if (bg != null) {
    // luminance check for dark/light theme detection
    boolean dark = (0.299 * bg.r8() + 0.587 * bg.g8() + 0.114 * bg.b8()) < 128;
}
```

Returns `null` if the terminal does not respond within the timeout.

### Query a single palette entry

```java
Color color1 = TermColors.queryColor(terminal, in, 1); // ANSI red
```

### Query the full 256-entry palette (burst)

All 256 queries are sent at once; responses are collected until a read timeout indicates the terminal has no more data:

```java
Color[] palette = TermColors.queryPalette(terminal, in);
// palette[n] is null if the terminal did not respond for that index
```

Query a specific subset:

```java
// Query only the 16 ANSI colours
int[] ansi16 = new int[16];
for (int i = 0; i < 16; i++) ansi16[i] = i;
Color[] result = TermColors.queryPalette(terminal, in, ansi16);
```

### Set colours

```java
TermColors.setForeground(terminal, Color.ofRgb8(220, 220, 220));
TermColors.setBackground(terminal, Color.ofRgb8(30, 30, 30));
TermColors.setCursor(terminal, Color.ofRgb8(255, 165, 0));

TermColors.setColor(terminal, 1, Color.ofRgb8(204, 0, 0)); // redefine ANSI red
```

Set the full palette from an array (null entries are skipped):

```java
Color[] palette = buildThemePalette(); // Color[256]
TermColors.setPalette(terminal, palette);
```

### Reset to profile defaults

```java
TermColors.resetForeground(terminal);  // OSC 110
TermColors.resetBackground(terminal);  // OSC 111
TermColors.resetCursor(terminal);      // OSC 112

TermColors.resetColor(terminal, 1);    // reset single palette entry (OSC 104)
TermColors.resetPalette(terminal);     // reset all 256 entries (OSC 104)
```

### Manual query

```java
TermColors.sendBackgroundQuery(terminal);
String seq = /* read sequence */
if (TermColors.isBackgroundQueryResult(seq)) {
    Color bg = TermColors.parseBackground(seq);
}
```

## Concepts

### `TermColors`

All methods are static. Query methods require the terminal in **raw mode** and an `IntReader` that returns negative values on timeout or EOF:

```java
terminal.enableRawMode();
IntReader in = () -> terminal.read(500); // 500 ms timeout per read
```

### `Color`

An immutable 16-bit-per-channel RGB colour, matching the precision used in the X11 `rgb:` colour specification that terminals return in OSC responses.

```java
Color red   = Color.of(0xFFFF, 0x0000, 0x0000);    // 16-bit channels
Color green = Color.ofRgb8(0, 255, 0);              // 8-bit, expanded
Color blue  = Color.parse("rgb:0000/0000/FFFF");    // from OSC string
```

Conversion helpers:

```java
color.r8()     // red channel downsampled to 8 bits (0-255)
color.toRgb8() // packed 0xRRGGBB int
color.toString() // "rgb:RRRR/GGGG/BBBB" — ready for use in OSC sequences
```

## OSC sequence reference

| Operation | Sequence |
|---|---|
| Query foreground | `OSC 10 ; ? BEL` |
| Query background | `OSC 11 ; ? BEL` |
| Query cursor colour | `OSC 12 ; ? BEL` |
| Query palette entry *n* | `OSC 4 ; n ; ? BEL` |
| Set foreground | `OSC 10 ; rgb:RRRR/GGGG/BBBB BEL` |
| Set background | `OSC 11 ; rgb:RRRR/GGGG/BBBB BEL` |
| Set cursor colour | `OSC 12 ; rgb:RRRR/GGGG/BBBB BEL` |
| Set palette entry *n* | `OSC 4 ; n ; rgb:RRRR/GGGG/BBBB BEL` |
| Reset foreground | `OSC 110 BEL` |
| Reset background | `OSC 111 BEL` |
| Reset cursor colour | `OSC 112 BEL` |
| Reset palette entry *n* | `OSC 104 ; n BEL` |
| Reset all palette entries | `OSC 104 BEL` |

## Dependency

### JBang

```java
//DEPS org.codejive.miniterm:colors:0.1.1
```

### Maven

```xml
<dependency>
    <groupId>org.codejive.miniterm</groupId>
    <artifactId>colors</artifactId>
    <version>0.1.1</version>
</dependency>
```

### Gradle

```kotlin
implementation("org.codejive.miniterm:colors:0.1.1")
```

`colors` requires `ansiparser` (included transitively) for parsing OSC responses.
