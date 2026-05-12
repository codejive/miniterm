# termcap

`termcap` is a small (~10KB) Java 8+ terminal capability detector, part of the [java-miniterm](../README.md) project.

It detects what the current terminal can do — colour depth, mouse support, bracketed paste, hyperlinks, and more — without requiring any terminal I/O. An optional live-probing mode refines the result by querying the terminal directly using DA1/DA2 sequences.

## Capabilities detected

| Capability | Method | Description |
|---|---|---|
| Colour depth | `colors()` | `0`, `8`, `16`, `256`, or `16_777_216` (true-colour) |
| Alternate screen | `altScreen()` | `smcup`/`rmcup` support |
| Mouse tracking | `mouse()` | Any xterm mouse protocol variant |
| Bracketed paste | `bracketedPaste()` | DEC mode 2004 |
| Focus tracking | `focusTracking()` | DEC mode 1004 |
| Synchronized output | `synchronizedOutput()` | DEC mode 2026 |
| Hyperlinks | `hyperlinks()` | OSC 8 |
| Settable title | `settableTitle()` | OSC 0/2 window title |
| Unicode output | `unicode()` | UTF-8 output processed correctly |
| Italic text | `italic()` | `sitm`/`ritm` |
| Strikethrough | `strikethrough()` | `smxx`/`rmxx` |
| Overline | `overline()` | `Smol`/`Rmol` |

## Usage

### Passive detection (no terminal I/O)

```java
import org.codejive.miniterm.termcap.TermCaps;

TermCaps caps = TermCaps.detect();

if (caps.colors() >= 256) {
    // render with rich colours
}
if (caps.bracketedPaste()) {
    TermCaps.enableBracketedPaste(terminal);
}
```

`detect()` is safe to call at any time — before opening a `Terminal`, before enabling raw mode, or even in a non-interactive context. It never performs I/O.

### Live probing (refined detection)

When passive detection is not precise enough — especially over SSH, where supplemental environment variables are not forwarded — you can refine the result by sending DA1/DA2 queries to the terminal:

```java
import org.codejive.miniterm.termcap.TermCaps;
import org.codejive.miniterm.termcap.TermProber;

// ansiparser must be on the classpath
terminal.enableRawMode();
TermCaps caps = TermProber.probe(terminal, () -> terminal.read(500));
```

`TermProber` requires the `ansiparser` artifact — add it explicitly:

**Maven:**
```xml
<dependency>
    <groupId>org.codejive.miniterm</groupId>
    <artifactId>termcap</artifactId>
    <version>VERSION</version>
</dependency>
<dependency>
    <groupId>org.codejive.miniterm</groupId>
    <artifactId>ansiparser</artifactId>
    <version>VERSION</version>
</dependency>
```

### Manual query

```java
TermCaps.Builder b = TermCaps.builder(TermCaps.detect());
TermProber.sendDA1Query(terminal);
String seq = /* read sequence */
if (TermProber.isDA1QueryResult(seq)) {
    TermProber.applyDA1(seq, b);
}
TermCaps caps = b.build();
```

### Manual override

Use a copy-with-override builder to adjust any capability — for example, to honour a `--no-color` flag:

```java
TermCaps caps = TermCaps.builder(TermCaps.detect()).colors(0).build();
```

Or build from scratch:

```java
TermCaps caps = TermCaps.builder()
        .colors(256)
        .altScreen(true)
        .mouse(true)
        .build();
```

## Detection layers

`detect()` applies three layers in order; later layers win:

### Layer 1 — `$TERM`

Maps the terminal type name to a baseline. Common mappings:

| `$TERM` | Colours | Notable capabilities |
|---|---|---|
| `dumb` | 0 | none |
| `vt100`, `vt220` | 0 | none |
| `ansi` | 8 | — |
| `xterm` | 8 | altScreen, mouse, settableTitle, unicode |
| `xterm-256color` | 256 | + bracketedPaste, italic, strikethrough |
| `xterm-direct` | 16M | + overline |
| `screen`, `tmux` | 8 | altScreen |
| `screen-256color`, `tmux-256color` | 256 | altScreen, bracketedPaste |

Any `$TERM` ending in `-256color`, `-truecolor`, or `-direct` upgrades colour depth regardless of the prefix.

### Layer 2 — Supplemental environment variables

| Variable | Condition | Effect |
|---|---|---|
| `COLORTERM` | `truecolor` or `24bit` | colors = 16M |
| `WT_SESSION` | any | Full caps (Windows Terminal) |
| `TERM_PROGRAM` | `WezTerm` | Full caps + synchronizedOutput |
| `TERM_PROGRAM` | `iTerm.app` | Full caps |
| `TERM_PROGRAM` | `kitty` | Full caps |
| `TERM_PROGRAM` | `Apple_Terminal` | ≥256 colours, settableTitle |
| `VTE_VERSION` | any | ≥256 colours, bracketedPaste, hyperlinks, italic, focusTracking |
| `ConEmuANSI` | `ON` | 256 colours, settableTitle, unicode |
| `TMUX` | any | mouse = true (tmux passes mouse events through) |

### Layer 3 — Terminfo binary (Unix only)

Reads `max_colors` from the compiled terminfo entry for `$TERM`. Searches the standard ncurses path: `$TERMINFO` → `$TERMINFO_DIRS` → `~/.terminfo` → `/etc/terminfo` → `/usr/share/terminfo`. Falls back silently if the file is absent or unreadable.

### Layer 4 — Live probing (opt-in, requires `ansiparser`)

`TermProber.probe()` sends DA1 and DA2 queries and parses the responses:

- **DA1** (`ESC [ c`) — Primary Device Attributes: broad feature flags
- **DA2** (`ESC [ > c`) — Secondary Device Attributes: terminal family and version

Each query uses a configurable timeout (default 500 ms). On timeout the Layer 1–3 result is returned unchanged, so probing fails gracefully on dumb pipes.

> **SSH note:** `$TERM` is always forwarded by SSH, so Layers 1 and 3 work correctly on the remote host. Layer 2 variables (`COLORTERM`, `TERM_PROGRAM`, etc.) are *not* forwarded by default — configure `SendEnv`/`AcceptEnv` in your SSH config, or use live probing which works over any PTY chain.

## Dependency

### JBang

```java
//DEPS org.codejive.miniterm:termcap:0.1.1
```

### Maven

```xml
<dependency>
    <groupId>org.codejive.miniterm</groupId>
    <artifactId>termcap</artifactId>
    <version>0.1.1</version>
</dependency>
```

### Gradle

```kotlin
implementation("org.codejive.miniterm:termcap:0.1.1")
```

`termcap` has no mandatory runtime dependencies. The `ansiparser` artifact is only needed if you use `TermProber`.
