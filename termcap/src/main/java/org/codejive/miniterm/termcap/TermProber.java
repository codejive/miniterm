package org.codejive.miniterm.termcap;

import java.io.IOException;
import org.codejive.miniterm.ansiparser.AnsiParser;
import org.codejive.miniterm.ansiparser.IntReader;

/**
 * Refines a {@link TermCaps} baseline by sending DA1/DA2 queries to the live terminal.
 *
 * <p>This class requires the {@code ansiparser} artifact to be present on the classpath. If it is
 * absent a {@link NoClassDefFoundError} will be thrown at call time.
 *
 * <h2>What is probed</h2>
 *
 * <ul>
 *   <li><b>DA1</b> ({@code ESC [ c}) — Primary Device Attributes: identifies broad feature support
 *       (colour, sixel graphics, etc.) via a list of parameter codes.
 *   <li><b>DA2</b> ({@code ESC [ > c}) — Secondary Device Attributes: identifies the terminal
 *       family and version via a {@code <type>;<version>;<unused>} triple.
 * </ul>
 *
 * <p>When the terminal does not respond the existing {@link TermCaps} value is kept unchanged, so
 * probing degrades gracefully on dumb pipes and non-interactive streams.
 *
 * <h2>Send-query methods</h2>
 *
 * <p>These methods write only the request sequence, leaving the caller responsible for reading and
 * applying the response via the {@code isDA1QueryResult}/{@code isDA2QueryResult} detectors and
 * {@link #applyDA1}/{@link #applyDA2} applicators:
 *
 * <ul>
 *   <li>{@link #sendDA1Query(Appendable)} — sends {@code ESC [ c}
 *   <li>{@link #sendDA2Query(Appendable)} — sends {@code ESC [ > c}
 * </ul>
 *
 * <h2>Usage</h2>
 *
 * <pre>{@code
 * terminal.enableRawMode();
 * TermCaps caps = TermProber.probe(terminal, () -> terminal.read(500));
 * }</pre>
 *
 * <p>The {@code Terminal} class implements {@link Appendable}, so it can be passed directly as the
 * {@code out} parameter.
 */
public final class TermProber {

    private static final String CSI = "\033[";

    // DA1 primary response prefix: ESC [ ?
    private static final String DA1_PREFIX = CSI + "?";
    // DA2 secondary response prefix: ESC [ >
    private static final String DA2_PREFIX = CSI + ">";

    // DA1 parameter codes (from ECMA-48 / DEC documentation)
    private static final int DA1_PARAM_COLOR = 22; // colour text capability
    private static final int DA1_PARAM_256_COLOR = 29; // 256 colours (non-standard, some terms)
    private static final int DA1_PARAM_SIXEL = 4; // sixel graphics

    // DA2 type codes
    private static final int DA2_TYPE_XTERM = 0;
    private static final int DA2_TYPE_VTE = 65;
    private static final int DA2_TYPE_KITTY = 1; // kitty's self-reported value
    private static final int DA2_TYPE_ITERM2 = 0; // iTerm2 also reports 0; resolved via env

    private TermProber() {}

    // ── Send query ────────────────────────────────────────────────────────

    /**
     * Sends the DA1 (Primary Device Attributes) query ({@code ESC [ c}) to the terminal.
     *
     * <p>The caller is responsible for reading the response and applying it with {@link
     * #applyDA1(String, TermCaps.Builder)}.
     *
     * @param out writable channel to the terminal
     * @throws IOException if writing fails
     */
    public static void sendDA1Query(Appendable out) throws IOException {
        out.append(CSI).append("c");
    }

    /**
     * Sends the DA2 (Secondary Device Attributes) query ({@code ESC [ > c}) to the terminal.
     *
     * <p>The caller is responsible for reading the response and applying it with {@link
     * #applyDA2(String, TermCaps.Builder)}.
     *
     * @param out writable channel to the terminal
     * @throws IOException if writing fails
     */
    public static void sendDA2Query(Appendable out) throws IOException {
        out.append(CSI).append(">c");
    }

    /**
     * Starts from {@link TermCaps#detect()} and refines the result using live DA1/DA2 queries.
     *
     * @param out writable channel to the terminal (e.g. a {@code Terminal} instance)
     * @param in readable channel from the terminal with per-call timeout semantics: negative return
     *     values signal EOF ({@code -1}) or timeout ({@code -2})
     * @return refined {@link TermCaps}
     * @throws IOException if writing to {@code out} fails
     */
    public static TermCaps probe(Appendable out, IntReader in) throws IOException {
        return probe(TermCaps.detect(), out, in);
    }

    /**
     * Refines an existing {@link TermCaps} baseline using live DA1/DA2 queries.
     *
     * @param base starting capability set (typically from {@link TermCaps#detect()})
     * @param out writable channel to the terminal
     * @param in readable channel from the terminal
     * @return refined {@link TermCaps}
     * @throws IOException if writing to {@code out} fails
     */
    public static TermCaps probe(TermCaps base, Appendable out, IntReader in) throws IOException {
        TermCaps.Builder b = TermCaps.builder(base);

        // ── DA1: Primary Device Attributes ───────────────────────────────
        // Query: ESC [ c
        // Response: ESC [ ? <p1> ; <p2> ; … c
        sendDA1Query(out);
        String da1 = readResponse(in, 'c');
        if (da1 != null) applyDA1(da1, b);

        // ── DA2: Secondary Device Attributes ───────────────────────────────
        // Query: ESC [ > c
        // Response: ESC [ > <type> ; <version> ; <unused> c
        sendDA2Query(out);
        String da2 = readResponse(in, 'c');
        if (da2 != null) applyDA2(da2, b);

        return b.build();
    }

    // ── Detection ─────────────────────────────────────────────────────────

    /**
     * Returns {@code true} if {@code seq} is a DA1 (Primary Device Attributes) response.
     *
     * <p>The expected form is {@code ESC [ ? <params> c}.
     *
     * @param seq raw escape sequence to test
     */
    public static boolean isDA1QueryResult(String seq) {
        return seq != null && seq.startsWith(DA1_PREFIX) && seq.endsWith("c");
    }

    /**
     * Returns {@code true} if {@code seq} is a DA2 (Secondary Device Attributes) response.
     *
     * <p>The expected form is {@code ESC [ > <params> c}.
     *
     * @param seq raw escape sequence to test
     */
    public static boolean isDA2QueryResult(String seq) {
        return seq != null && seq.startsWith(DA2_PREFIX) && seq.endsWith("c");
    }

    // ── Apply ──────────────────────────────────────────────────────────────

    /**
     * Applies a DA1 (Primary Device Attributes) response sequence to the given {@link
     * TermCaps.Builder}.
     *
     * @param seq the raw DA1 response sequence
     * @param b the builder to update
     * @return {@code true} if {@code seq} was a valid DA1 response and the builder was updated
     */
    public static boolean applyDA1(String seq, TermCaps.Builder b) {
        if (!isDA1QueryResult(seq)) return false;
        String params = seq.substring(DA1_PREFIX.length(), seq.length() - 1);
        for (String p : params.split(";")) {
            int code = parseIntSafe(p);
            if (code == DA1_PARAM_COLOR) {
                if (b.colors() < 8) b.colors(8);
            } else if (code == DA1_PARAM_256_COLOR) {
                if (b.colors() < 256) b.colors(256);
            }
        }
        return true;
    }

    /**
     * Applies a DA2 (Secondary Device Attributes) response sequence to the given {@link
     * TermCaps.Builder}.
     *
     * @param seq the raw DA2 response sequence
     * @param b the builder to update
     * @return {@code true} if {@code seq} was a valid DA2 response and the builder was updated
     */
    public static boolean applyDA2(String seq, TermCaps.Builder b) {
        if (!isDA2QueryResult(seq)) return false;
        String params = seq.substring(DA2_PREFIX.length(), seq.length() - 1);
        String[] parts = params.split(";", -1);
        int type = parts.length > 0 ? parseIntSafe(parts[0]) : -1;
        int version = parts.length > 1 ? parseIntSafe(parts[1]) : -1;
        applyDa2(b, type, version);
        return true;
    }

    // ── DA2 capability inference ──────────────────────────────────────────

    private static void applyDa2(TermCaps.Builder b, int type, int version) {
        switch (type) {
            case DA2_TYPE_VTE:
                // VTE >= 3802 (version encoding: major*10000 + minor*100 + micro)
                // All current VTE versions support 256 colours, bracketed paste, hyperlinks
                if (b.colors() < 256) b.colors(256);
                b.bracketedPaste(true).hyperlinks(true).italic(true).focusTracking(true);
                break;
            case DA2_TYPE_KITTY:
                b.colors(16_777_216)
                        .altScreen(true)
                        .mouse(true)
                        .bracketedPaste(true)
                        .focusTracking(true)
                        .hyperlinks(true)
                        .settableTitle(true)
                        .unicode(true)
                        .italic(true)
                        .strikethrough(true)
                        .overline(true);
                break;
            case DA2_TYPE_XTERM:
                // xterm and iTerm2 both report type 0; distinguish via TERM_PROGRAM env var
                // which was already applied in Layer 2. Just ensure xterm baseline.
                if (version > 0 && b.colors() < 256) {
                    // Modern xterm (>= ~282) supports 256 colours
                    b.colors(256);
                }
                b.altScreen(true).mouse(true).bracketedPaste(true).settableTitle(true);
                break;
            default:
                break;
        }
    }

    // ── Response reading ──────────────────────────────────────────────────

    /**
     * Reads ANSI sequences from {@code in} until one ending with {@code terminator} is found, or
     * until a timeout is hit.
     *
     * <p>Sequences that don't end with the expected terminator are silently discarded (they may be
     * leftover input the user typed before the query was sent).
     */
    private static String readResponse(IntReader in, char terminator) throws IOException {
        // Wrap in a timeout-aware reader: each individual read call already carries the timeout
        // (caller passes `() -> terminal.read(timeoutMs)`).  We make at most a bounded number of
        // attempts to avoid an infinite loop on a misbehaving source.
        for (int attempts = 0; attempts < 32; attempts++) {
            StringBuilder sb = new StringBuilder();
            int result = AnsiParser.parse(sb, in, AnsiParser.MAX_SEQUENCE_LENGTH);
            if (result == -1) {
                // EOF
                return null;
            }
            String seq = sb.toString();
            if (seq.isEmpty()) {
                // Timeout — no response
                return null;
            }
            if (seq.length() > 1 && seq.charAt(seq.length() - 1) == terminator) {
                return seq;
            }
            // Not the response we're looking for — keep reading
        }
        return null;
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private static int parseIntSafe(String s) {
        if (s == null || s.isEmpty()) return -1;
        try {
            return Integer.parseInt(s.trim());
        } catch (NumberFormatException e) {
            return -1;
        }
    }
}
