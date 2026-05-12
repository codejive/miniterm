package org.codejive.miniterm.colors;

import java.io.IOException;
import java.util.function.Function;
import org.codejive.miniterm.ansiparser.AnsiParser;
import org.codejive.miniterm.ansiparser.IntReader;

/**
 * Static helpers for querying and setting terminal colours via OSC escape sequences.
 *
 * <p>All query methods require the terminal to be in <em>raw mode</em> so that the response can be
 * read back without line-buffering. The {@link IntReader} passed to query methods must return
 * negative values on timeout or EOF; a typical implementation wraps the terminal's read method with
 * a per-call timeout:
 *
 * <pre>{@code
 * terminal.enableRawMode();
 * Color bg = TermColors.queryBackground(terminal, () -> terminal.read(500));
 * }</pre>
 *
 * <p>Set and reset methods perform only output and can be called at any time.
 *
 * <h2>Send-query methods</h2>
 *
 * <p>These methods write only the request sequence, leaving the caller responsible for reading and
 * parsing the response via the {@code is*} and {@code parse*} methods:
 *
 * <ul>
 *   <li>{@link #sendForegroundQuery} / {@link #sendBackgroundQuery} / {@link #sendCursorQuery}
 *   <li>{@link #sendColorQuery(Appendable, int)} — single palette entry (OSC 4)
 * </ul>
 *
 * <h2>Query methods</h2>
 *
 * <ul>
 *   <li>{@link #queryForeground} / {@link #queryBackground} / {@link #queryCursor} — OSC 10/11/12
 *   <li>{@link #queryColor(Appendable, IntReader, int)} — single palette entry (OSC 4)
 *   <li>{@link #queryPalette(Appendable, IntReader)} — all 256 palette entries in one burst
 *   <li>{@link #queryPalette(Appendable, IntReader, int[])} — specific palette entries in one burst
 * </ul>
 *
 * <h2>Set methods</h2>
 *
 * <ul>
 *   <li>{@link #setForeground} / {@link #setBackground} / {@link #setCursor} — OSC 10/11/12
 *   <li>{@link #setColor(Appendable, int, Color)} — single palette entry (OSC 4)
 *   <li>{@link #setPalette(Appendable, Color[])} — all 256 palette entries
 * </ul>
 *
 * <h2>Reset methods</h2>
 *
 * <p>Restore original terminal colours (as defined by the active profile/theme):
 *
 * <ul>
 *   <li>{@link #resetForeground} (OSC 110) / {@link #resetBackground} (OSC 111) / {@link
 *       #resetCursor} (OSC 112)
 *   <li>{@link #resetColor(Appendable, int)} — single palette entry (OSC 104)
 *   <li>{@link #resetPalette(Appendable)} — all 256 palette entries (OSC 104 with no index)
 * </ul>
 *
 * <h2>Burst palette query</h2>
 *
 * <p>{@link #queryPalette} sends all requested queries at once, then collects responses until a
 * read timeout indicates the terminal has no more data to send. Results are returned as a {@code
 * Color[256]} array indexed by palette entry number; entries for which no response arrived are
 * {@code null}.
 *
 * <h2>SSH sessions</h2>
 *
 * <p>OSC queries work over any PTY chain including SSH, provided the terminal emulator on the local
 * end processes OSC sequences (most modern terminals do).
 */
public final class TermColors {

    /** Default per-query read timeout in milliseconds. */
    public static final int DEFAULT_TIMEOUT_MS = 500;

    private static final String OSC_PREFIX = "\033]";
    private static final String OSC_BEL = "\007";
    private static final String OSC_ST = "\033\\";

    private TermColors() {}

    // ── Send query ────────────────────────────────────────────────────────

    /**
     * Sends the OSC 10 foreground colour query to the terminal.
     *
     * <p>The caller is responsible for reading the response and parsing it with {@link
     * #parseForeground(String)}.
     *
     * @param out writable channel to the terminal
     * @throws IOException if writing fails
     */
    public static void sendForegroundQuery(Appendable out) throws IOException {
        out.append(OSC_PREFIX).append("10;?").append(OSC_BEL);
    }

    /**
     * Sends the OSC 11 background colour query to the terminal.
     *
     * <p>The caller is responsible for reading the response and parsing it with {@link
     * #parseBackground(String)}.
     *
     * @param out writable channel to the terminal
     * @throws IOException if writing fails
     */
    public static void sendBackgroundQuery(Appendable out) throws IOException {
        out.append(OSC_PREFIX).append("11;?").append(OSC_BEL);
    }

    /**
     * Sends the OSC 12 cursor colour query to the terminal.
     *
     * <p>The caller is responsible for reading the response and parsing it with {@link
     * #parseCursor(String)}.
     *
     * @param out writable channel to the terminal
     * @throws IOException if writing fails
     */
    public static void sendCursorQuery(Appendable out) throws IOException {
        out.append(OSC_PREFIX).append("12;?").append(OSC_BEL);
    }

    /**
     * Sends an OSC 4 palette colour query for {@code index} to the terminal.
     *
     * <p>The caller is responsible for reading the response and parsing it with {@link
     * #parseColor(String, int)}.
     *
     * @param out writable channel to the terminal
     * @param index palette colour index 0–255
     * @throws IllegalArgumentException if {@code index} is out of range
     * @throws IOException if writing fails
     */
    public static void sendColorQuery(Appendable out, int index) throws IOException {
        validateIndex(index);
        out.append(OSC_PREFIX)
                .append("4;")
                .append(String.valueOf(index))
                .append(";")
                .append("?")
                .append(OSC_BEL);
    }

    // ── Query ──────────────────────────────────────────────────────────────

    /**
     * Queries the terminal foreground (text) colour (OSC 10).
     *
     * @param out writable channel to the terminal
     * @param in readable channel from the terminal; negative values signal timeout or EOF
     * @return the current foreground colour, or {@code null} if the terminal did not respond
     * @throws IOException if writing fails
     */
    public static Color queryForeground(Appendable out, IntReader in) throws IOException {
        sendForegroundQuery(out);
        return readSingleColor(in, TermColors::parseForeground);
    }

    /**
     * Queries the terminal background colour (OSC 11).
     *
     * @param out writable channel to the terminal
     * @param in readable channel from the terminal; negative values signal timeout or EOF
     * @return the current background colour, or {@code null} if the terminal did not respond
     * @throws IOException if writing fails
     */
    public static Color queryBackground(Appendable out, IntReader in) throws IOException {
        sendBackgroundQuery(out);
        return readSingleColor(in, TermColors::parseBackground);
    }

    /**
     * Queries the terminal cursor colour (OSC 12).
     *
     * @param out writable channel to the terminal
     * @param in readable channel from the terminal; negative values signal timeout or EOF
     * @return the current cursor colour, or {@code null} if the terminal did not respond
     * @throws IOException if writing fails
     */
    public static Color queryCursor(Appendable out, IntReader in) throws IOException {
        sendCursorQuery(out);
        return readSingleColor(in, TermColors::parseCursor);
    }

    /**
     * Queries a single palette colour by index (OSC 4).
     *
     * @param out writable channel to the terminal
     * @param in readable channel from the terminal; negative values signal timeout or EOF
     * @param index palette colour index 0–255
     * @return the current colour at {@code index}, or {@code null} if the terminal did not respond
     * @throws IllegalArgumentException if {@code index} is out of range
     * @throws IOException if writing fails
     */
    public static Color queryColor(Appendable out, IntReader in, int index) throws IOException {
        sendColorQuery(out, index);
        return readPaletteColor(in, index);
    }

    /**
     * Queries all 256 palette colours in a single burst (OSC 4).
     *
     * <p>Sends all 256 queries at once, then reads responses until the terminal stops sending. The
     * returned array is indexed by palette entry number; entries for which no response arrived are
     * {@code null}.
     *
     * @param out writable channel to the terminal
     * @param in readable channel from the terminal; negative values signal timeout or EOF
     * @return a 256-element array of colours; entries may be {@code null} if the terminal did not
     *     respond for that index
     * @throws IOException if writing fails
     */
    public static Color[] queryPalette(Appendable out, IntReader in) throws IOException {
        int[] all = new int[256];
        for (int i = 0; i < 256; i++) all[i] = i;
        return queryPalette(out, in, all);
    }

    /**
     * Queries specific palette colours in a single burst (OSC 4).
     *
     * <p>Sends all requested queries at once, then reads responses until the terminal stops
     * sending. The returned array always has 256 elements; only the requested indices are
     * populated. Unresponded entries are {@code null}.
     *
     * @param out writable channel to the terminal
     * @param in readable channel from the terminal; negative values signal timeout or EOF
     * @param indices palette indices to query (each 0–255)
     * @return a 256-element array; only queried and responded entries are non-{@code null}
     * @throws IllegalArgumentException if any index is out of range
     * @throws IOException if writing fails
     */
    public static Color[] queryPalette(Appendable out, IntReader in, int[] indices)
            throws IOException {
        boolean[] requested = new boolean[256];
        int requestedCount = 0;
        for (int idx : indices) {
            validateIndex(idx);
            if (!requested[idx]) {
                requested[idx] = true;
                requestedCount++;
            }
        }

        // Send all queries as a burst
        for (int idx : indices) {
            sendColorQuery(out, idx);
        }

        // Collect responses until timeout
        Color[] result = new Color[256];
        int received = 0;
        int maxAttempts = requestedCount * 2 + 32;
        for (int attempt = 0; attempt < maxAttempts; attempt++) {
            StringBuilder sb = new StringBuilder();
            AnsiParser.parse(sb, in, AnsiParser.MAX_SEQUENCE_LENGTH);
            String seq = sb.toString();
            if (seq.isEmpty()) break; // timeout — terminal has no more responses

            if (!seq.startsWith(OSC_PREFIX)) continue;
            String body = oscBody(seq);
            if (body == null || !body.startsWith("4;")) continue;

            // body is now: "<index>;<rgb:...>" (after stripping "4;")
            String rest = body.substring(2);
            int semi = rest.indexOf(';');
            if (semi < 0) continue;
            int colorIndex;
            try {
                colorIndex = Integer.parseInt(rest.substring(0, semi));
            } catch (NumberFormatException e) {
                continue;
            }
            if (colorIndex < 0 || colorIndex > 255) continue;

            Color color = parseColor(seq, colorIndex);
            if (color != null && result[colorIndex] == null && requested[colorIndex]) {
                result[colorIndex] = color;
                received++;
                if (received >= requestedCount) break;
            }
        }
        return result;
    }

    // ── Set ───────────────────────────────────────────────────────────────

    /**
     * Sets the terminal foreground (text) colour (OSC 10).
     *
     * @param out writable channel to the terminal
     * @param color new foreground colour
     * @throws IOException if writing fails
     */
    public static void setForeground(Appendable out, Color color) throws IOException {
        out.append(OSC_PREFIX).append("10;").append(color.toString()).append(OSC_BEL);
    }

    /**
     * Sets the terminal background colour (OSC 11).
     *
     * @param out writable channel to the terminal
     * @param color new background colour
     * @throws IOException if writing fails
     */
    public static void setBackground(Appendable out, Color color) throws IOException {
        out.append(OSC_PREFIX).append("11;").append(color.toString()).append(OSC_BEL);
    }

    /**
     * Sets the terminal cursor colour (OSC 12).
     *
     * @param out writable channel to the terminal
     * @param color new cursor colour
     * @throws IOException if writing fails
     */
    public static void setCursor(Appendable out, Color color) throws IOException {
        out.append(OSC_PREFIX).append("12;").append(color.toString()).append(OSC_BEL);
    }

    /**
     * Sets a single palette colour by index (OSC 4).
     *
     * @param out writable channel to the terminal
     * @param index palette colour index 0–255
     * @param color new colour
     * @throws IllegalArgumentException if {@code index} is out of range
     * @throws IOException if writing fails
     */
    public static void setColor(Appendable out, int index, Color color) throws IOException {
        validateIndex(index);
        out.append(OSC_PREFIX)
                .append("4;")
                .append(String.valueOf(index))
                .append(";")
                .append(color.toString())
                .append(OSC_BEL);
    }

    /**
     * Sets all 256 palette colours (OSC 4).
     *
     * <p>Entries that are {@code null} in the array are skipped.
     *
     * @param out writable channel to the terminal
     * @param colors 256-element array of colours; {@code null} entries are skipped
     * @throws IllegalArgumentException if {@code colors} does not have exactly 256 elements
     * @throws IOException if writing fails
     */
    public static void setPalette(Appendable out, Color[] colors) throws IOException {
        if (colors.length != 256) {
            throw new IllegalArgumentException(
                    "palette array must have exactly 256 entries, got " + colors.length);
        }
        for (int i = 0; i < 256; i++) {
            if (colors[i] != null) {
                setColor(out, i, colors[i]);
            }
        }
    }

    // ── Reset ─────────────────────────────────────────────────────────────

    /**
     * Resets the terminal foreground colour to the profile default (OSC 110).
     *
     * @param out writable channel to the terminal
     * @throws IOException if writing fails
     */
    public static void resetForeground(Appendable out) throws IOException {
        out.append(OSC_PREFIX).append("110").append(OSC_BEL);
    }

    /**
     * Resets the terminal background colour to the profile default (OSC 111).
     *
     * @param out writable channel to the terminal
     * @throws IOException if writing fails
     */
    public static void resetBackground(Appendable out) throws IOException {
        out.append(OSC_PREFIX).append("111").append(OSC_BEL);
    }

    /**
     * Resets the terminal cursor colour to the profile default (OSC 112).
     *
     * @param out writable channel to the terminal
     * @throws IOException if writing fails
     */
    public static void resetCursor(Appendable out) throws IOException {
        out.append(OSC_PREFIX).append("112").append(OSC_BEL);
    }

    /**
     * Resets a single palette colour to the profile default (OSC 104).
     *
     * @param out writable channel to the terminal
     * @param index palette colour index 0–255
     * @throws IllegalArgumentException if {@code index} is out of range
     * @throws IOException if writing fails
     */
    public static void resetColor(Appendable out, int index) throws IOException {
        validateIndex(index);
        out.append(OSC_PREFIX).append("104;").append(String.valueOf(index)).append(OSC_BEL);
    }

    /**
     * Resets all 256 palette colours to the profile defaults (OSC 104 with no argument).
     *
     * @param out writable channel to the terminal
     * @throws IOException if writing fails
     */
    public static void resetPalette(Appendable out) throws IOException {
        out.append(OSC_PREFIX).append("104").append(OSC_BEL);
    }

    // ── Detection ─────────────────────────────────────────────────────────

    /**
     * Returns {@code true} if {@code seq} is an OSC 10 (foreground colour) query response.
     *
     * @param seq raw escape sequence to test
     */
    public static boolean isForegroundQueryResult(String seq) {
        return seq != null && seq.startsWith(OSC_PREFIX + "10;");
    }

    /**
     * Returns {@code true} if {@code seq} is an OSC 11 (background colour) query response.
     *
     * @param seq raw escape sequence to test
     */
    public static boolean isBackgroundQueryResult(String seq) {
        return seq != null && seq.startsWith(OSC_PREFIX + "11;");
    }

    /**
     * Returns {@code true} if {@code seq} is an OSC 12 (cursor colour) query response.
     *
     * @param seq raw escape sequence to test
     */
    public static boolean isCursorQueryResult(String seq) {
        return seq != null && seq.startsWith(OSC_PREFIX + "12;");
    }

    /**
     * Returns {@code true} if {@code seq} is any OSC 4 (palette colour) query response.
     *
     * @param seq raw escape sequence to test
     */
    public static boolean isColorQueryResult(String seq) {
        return seq != null && seq.startsWith(OSC_PREFIX + "4;");
    }

    /**
     * Returns {@code true} if {@code seq} is an OSC 4 query response for palette {@code index}.
     *
     * @param seq raw escape sequence to test
     * @param index expected palette index (0–255)
     */
    public static boolean isColorQueryResult(String seq, int index) {
        return seq != null && seq.startsWith(OSC_PREFIX + "4;" + index + ";");
    }

    // ── Parse ─────────────────────────────────────────────────────────────

    /**
     * Parses an OSC 10 (foreground colour) response sequence.
     *
     * @param seq raw escape sequence
     * @return the parsed colour, or {@code null} if {@code seq} is not a valid OSC 10 response
     */
    public static Color parseForeground(String seq) {
        if (!isForegroundQueryResult(seq)) return null;
        return Color.parse(oscBody(seq).substring(3));
    }

    /**
     * Parses an OSC 11 (background colour) response sequence.
     *
     * @param seq raw escape sequence
     * @return the parsed colour, or {@code null} if {@code seq} is not a valid OSC 11 response
     */
    public static Color parseBackground(String seq) {
        if (!isBackgroundQueryResult(seq)) return null;
        return Color.parse(oscBody(seq).substring(3));
    }

    /**
     * Parses an OSC 12 (cursor colour) response sequence.
     *
     * @param seq raw escape sequence
     * @return the parsed colour, or {@code null} if {@code seq} is not a valid OSC 12 response
     */
    public static Color parseCursor(String seq) {
        if (!isCursorQueryResult(seq)) return null;
        return Color.parse(oscBody(seq).substring(3));
    }

    /**
     * Parses an OSC 4 (palette colour) response sequence for the expected palette index.
     *
     * @param seq raw escape sequence
     * @param expectedIndex palette index (0–255) the response must contain
     * @return the parsed colour, or {@code null} if {@code seq} is not a valid OSC 4 response for
     *     {@code expectedIndex}
     */
    public static Color parseColor(String seq, int expectedIndex) {
        if (!isColorQueryResult(seq, expectedIndex)) return null;
        String prefix = "4;" + expectedIndex + ";";
        return Color.parse(oscBody(seq).substring(prefix.length()));
    }

    // ── Internal helpers ──────────────────────────────────────────────────

    /**
     * Reads ANSI sequences from {@code in} until {@code parser} returns a non-null {@link Color},
     * or until a timeout or EOF occurs.
     */
    private static Color readSingleColor(IntReader in, Function<String, Color> parser)
            throws IOException {
        for (int attempt = 0; attempt < 32; attempt++) {
            StringBuilder sb = new StringBuilder();
            AnsiParser.parse(sb, in, AnsiParser.MAX_SEQUENCE_LENGTH);
            String seq = sb.toString();
            if (seq.isEmpty()) return null; // timeout
            Color c = parser.apply(seq);
            if (c != null) return c;
        }
        return null;
    }

    /** Like {@link #readSingleColor} but matches an OSC 4 response for a specific palette index. */
    private static Color readPaletteColor(IntReader in, int expectedIndex) throws IOException {
        return readSingleColor(in, seq -> parseColor(seq, expectedIndex));
    }

    /**
     * Strips the {@code ESC ]} prefix and the BEL ({@code \007}) or ST ({@code ESC \}) terminator
     * from a parsed OSC sequence, returning just the body. Returns {@code null} if the sequence
     * does not start with {@code ESC ]}.
     */
    static String oscBody(String seq) {
        if (seq == null || !seq.startsWith(OSC_PREFIX)) return null;
        String body = seq.substring(2); // strip ESC ]
        if (body.endsWith(OSC_BEL)) return body.substring(0, body.length() - 1);
        if (body.endsWith(OSC_ST)) return body.substring(0, body.length() - 2);
        return body;
    }

    private static void validateIndex(int index) {
        if (index < 0 || index > 255) {
            throw new IllegalArgumentException("palette index out of range [0..255]: " + index);
        }
    }
}
