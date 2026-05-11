package org.codejive.miniterm.ansiparser;

import java.io.IOException;

/**
 * Compact finite-state-machine ANSI escape sequence parser.
 *
 * <p>Call {@link #parse(IntReader)} with any {@link IntReader} that returns raw character codes
 * (e.g. {@code () -> terminal.read(100)}). Values &lt; 0 are treated as EOF/timeout.
 *
 * <p>ANSI sequences recognised:
 *
 * <ul>
 *   <li><b>CSI</b> ({@code ESC [}) – parameter/intermediate bytes followed by a final byte
 *   <li><b>OSC/DCS/PM/APC/SOS</b> ({@code ESC ] P X ^ _}) – string commands terminated by {@code
 *       BEL} (0x07) or {@code ST} ({@code ESC \})
 *   <li><b>Charset designators</b> ({@code ESC ( ) * + - . /}) – one additional character
 *   <li><b>Simple two-character sequences</b> – any other byte immediately after {@code ESC}
 * </ul>
 *
 * <p>DoS protection: sequences longer than {@link #MAX_SEQUENCE_LENGTH} characters are returned
 * truncated at that limit.
 */
public final class AnsiParser {

    /** Maximum number of characters accumulated before truncating a runaway sequence. */
    public static final int MAX_SEQUENCE_LENGTH = 2048;

    private static final int ESC = 0x1B;

    // FSM states
    private static final int S_INIT = 0;
    private static final int S_AFTER_ESC = 1;
    private static final int S_CSI = 2;
    private static final int S_STRING_CMD = 3;
    private static final int S_ESC_IN_STR = 4;
    private static final int S_CHARSET = 5;

    private AnsiParser() {}

    /**
     * Reads the next token from {@code source}.
     *
     * <p>If the first value supplied is not {@code ESC} (0x1B), it is returned immediately as a
     * single-character {@code String}. Otherwise, the FSM consumes values until the end of the
     * escape sequence is determined and returns the full sequence.
     *
     * @param source raw character reader; negative values signal EOF or timeout
     * @return the token as a {@code String}, an empty string to indicate a timeout or {@code null}
     *     for EOF before any character was read
     * @throws IOException if {@code source} throws an {@code IOException}
     */
    public static String parse(IntReader source) throws IOException {
        StringBuilder sb = new StringBuilder();
        int result = parse(sb, source, MAX_SEQUENCE_LENGTH);
        if (result == -1) {
            return sb.length() > 0 ? sb.toString() : null;
        } else {
            return sb.toString();
        }
    }

    /**
     * Reads the next token from {@code source}.
     *
     * <p>If the first value supplied is not {@code ESC} (0x1B), it is returned immediately as a
     * single-character {@code String}. Otherwise, the FSM consumes values until the end of the
     * escape sequence is determined and returns the full sequence.
     *
     * @param appendable where the token is accumulated
     * @param source raw character reader; negative values signal EOF or timeout
     * @param maxLength maximum number of characters to accumulate before truncating
     * @return {@code 0} on success, or the negative value returned by the supplier if it signalled
     *     EOF/timeout
     * @throws IOException if {@code appendable} or {@code source} throws an {@code IOException}
     */
    public static int parse(Appendable appendable, IntReader source, int maxLength)
            throws IOException {
        int state = S_INIT;
        int len = 0;
        while (len < maxLength) {
            int ch = source.read();
            if (ch < 0) {
                return ch;
            }
            appendable.append((char) ch);
            len++;

            switch (state) {
                case S_INIT:
                    if (ch != ESC) return 0;
                    state = S_AFTER_ESC;
                    break;

                case S_AFTER_ESC:
                    if (ch == '[') {
                        state = S_CSI;
                        break;
                    }
                    if ("]PX^_".indexOf(ch) >= 0) {
                        state = S_STRING_CMD;
                        break;
                    }
                    if ("()*+-./".indexOf(ch) >= 0) {
                        state = S_CHARSET;
                        break;
                    }
                    return 0; // simple two-char ESC sequence

                case S_CSI:
                    // final byte: 0x40–0x7E → sequence complete
                    if (ch >= 0x40 && ch <= 0x7E) return 0;
                    // valid parameter/intermediate bytes: 0x20–0x3F → keep reading
                    // anything else (control codes, DEL, high bytes) → abort
                    if (ch < 0x20 || ch > 0x3F) return 0;
                    break;

                case S_STRING_CMD:
                    if (ch == 0x07) return 0; // BEL terminates OSC
                    if (ch == ESC) state = S_ESC_IN_STR;
                    break;

                case S_ESC_IN_STR:
                    // ST = ESC \ → sequence complete; anything else → back to string body
                    if (ch == '\\') return 0;
                    state = S_STRING_CMD;
                    break;

                case S_CHARSET:
                    return 0; // one designator char consumed
            }
        }

        // DoS guard: return whatever was accumulated up to the limit
        return 0;
    }
}
