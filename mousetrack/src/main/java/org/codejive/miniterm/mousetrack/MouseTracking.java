package org.codejive.miniterm.mousetrack;

import java.io.IOException;

/**
 * Utility class for controlling terminal mouse tracking and parsing mouse event sequences.
 *
 * <h2>Enabling mouse tracking</h2>
 *
 * <p>Call {@link #enable(Appendable, Protocol)} to ask the terminal to start reporting mouse
 * events, and optionally {@link #enableEncoding(Appendable, Encoding)} to select how coordinates
 * are encoded. {@link Encoding#SGR} is recommended for new code because it supports arbitrarily
 * large terminals without coordinate overflow.
 *
 * <p>Always disable tracking when you are done, ideally in a {@code finally} block:
 *
 * <pre>{@code
 * MouseTracking.enable(terminal, MouseTracking.Protocol.NORMAL);
 * MouseTracking.enableEncoding(terminal, MouseTracking.Encoding.SGR);
 * try {
 *     // read and parse events …
 * } finally {
 *     MouseTracking.disableEncoding(terminal, MouseTracking.Encoding.SGR);
 *     MouseTracking.disable(terminal, MouseTracking.Protocol.NORMAL);
 * }
 * }</pre>
 *
 * <h2>Parsing mouse events</h2>
 *
 * <p>After receiving an escape sequence (e.g. from an {@code AnsiReader}), test it with {@link
 * #isMouseEvent(String)} and then decode it with {@link #parse(String)}:
 *
 * <pre>{@code
 * String seq = reader.read();
 * if (MouseTracking.isMouseEvent(seq)) {
 *     MouseEvent ev = MouseTracking.parse(seq);
 *     System.out.println(ev);
 * }
 * }</pre>
 *
 * <h2>Supported escape-sequence formats</h2>
 *
 * <ul>
 *   <li><b>Legacy X10</b> ({@code ESC [ M <b+32> <x+32> <y+32>}) — default encoding, limited to 223
 *       columns/rows
 *   <li><b>SGR</b> ({@code ESC [ < Pb ; Px ; Py M/m}) — preferred; no size limit; 'm' signals
 *       button release
 *   <li><b>URXVT</b> ({@code ESC [ Pb ; Px ; Py M}) — decimal coordinates without size limit
 * </ul>
 *
 * <h2>SGR-Pixels</h2>
 *
 * <p>{@link Encoding#SGR_PIXELS} (DEC mode 1016) extends {@link Encoding#SGR} to report pixel-level
 * coordinates instead of cell-based coordinates. Enable it together with {@link Encoding#SGR}.
 */
public final class MouseTracking {

    /**
     * Mouse tracking protocol controlling which events the terminal reports.
     *
     * <p>Protocols can be combined with an {@link Encoding} to extend coordinate range. Each
     * protocol is an ANSI DEC private mode ({@code ESC [ ? <n> h/l}).
     */
    public enum Protocol {
        /**
         * X10 compatible mode (DEC mode 9): reports button-press events only.
         *
         * <p>Coordinates are limited to 223 columns/rows in the default encoding.
         */
        X10(9),

        /**
         * Normal tracking mode (DEC mode 1000): reports button-press and button-release events.
         *
         * <p>This is the most widely supported mode and is a good default choice.
         */
        NORMAL(1000),

        /**
         * Button-motion tracking mode (DEC mode 1002): extends {@link #NORMAL} with drag events
         * (mouse moved while a button is held).
         */
        BUTTON_MOTION(1002),

        /**
         * Any-motion tracking mode (DEC mode 1003): extends {@link #BUTTON_MOTION} with hover
         * events (mouse moved with no button held).
         *
         * <p>Generates a large number of events; use with care.
         */
        ANY_MOTION(1003);

        private final int modeNumber;

        Protocol(int modeNumber) {
            this.modeNumber = modeNumber;
        }

        int getModeNumber() {
            return modeNumber;
        }
    }

    /**
     * Coordinate encoding extension for mouse events.
     *
     * <p>These extend the default legacy X10 byte encoding, which is limited to 223 columns/rows.
     * {@link #SGR} is the recommended choice for modern applications.
     *
     * <p>Each encoding is an ANSI DEC private mode ({@code ESC [ ? <n> h/l}).
     */
    public enum Encoding {
        /**
         * UTF-8 extended coordinates (DEC mode 1005).
         *
         * <p>Deprecated by many terminals; prefer {@link #SGR}.
         */
        UTF8(1005),

        /**
         * SGR extended encoding (DEC mode 1006).
         *
         * <p>Encodes all fields as decimal numbers; no coordinate limit. Releases are unambiguously
         * identified by the {@code 'm'} terminator. <b>Recommended for new code.</b>
         */
        SGR(1006),

        /**
         * URXVT extended encoding (DEC mode 1015).
         *
         * <p>Encodes coordinates as decimal numbers without limit, but does not identify which
         * button was released.
         */
        URXVT(1015),

        /**
         * SGR-Pixels encoding (DEC mode 1016).
         *
         * <p>Extends {@link #SGR} to report pixel-level coordinates instead of character-cell
         * coordinates. Requires {@link #SGR} to be enabled first. Not supported by all terminals.
         */
        SGR_PIXELS(1016);

        private final int modeNumber;

        Encoding(int modeNumber) {
            this.modeNumber = modeNumber;
        }

        int getModeNumber() {
            return modeNumber;
        }
    }

    private static final String CSI = "\033[";
    private static final String DEC_SET = "h";
    private static final String DEC_RST = "l";

    private MouseTracking() {}

    // ── protocol enable/disable ───────────────────────────────────────────────

    /**
     * Enables mouse-event reporting using the given protocol.
     *
     * <p>Sends {@code ESC [ ? <n> h} to {@code out}.
     *
     * @param out the terminal's output (typically the {@code Terminal} / {@code Appendable}
     *     connected to the TTY)
     * @param protocol the tracking protocol to enable
     * @throws IOException if writing to {@code out} fails
     */
    public static void enable(Appendable out, Protocol protocol) throws IOException {
        setMode(out, protocol.getModeNumber(), true);
    }

    /**
     * Disables mouse-event reporting for the given protocol.
     *
     * <p>Sends {@code ESC [ ? <n> l} to {@code out}.
     *
     * @param out the terminal's output
     * @param protocol the tracking protocol to disable
     * @throws IOException if writing to {@code out} fails
     */
    public static void disable(Appendable out, Protocol protocol) throws IOException {
        setMode(out, protocol.getModeNumber(), false);
    }

    // ── encoding enable/disable ───────────────────────────────────────────────

    /**
     * Enables an extended coordinate encoding.
     *
     * <p>Sends {@code ESC [ ? <n> h} to {@code out}. Should be paired with a call to {@link
     * #disableEncoding} when mouse tracking is no longer needed.
     *
     * @param out the terminal's output
     * @param encoding the coordinate encoding to enable
     * @throws IOException if writing to {@code out} fails
     */
    public static void enableEncoding(Appendable out, Encoding encoding) throws IOException {
        setMode(out, encoding.getModeNumber(), true);
    }

    /**
     * Disables an extended coordinate encoding, reverting to the default X10 byte encoding.
     *
     * <p>Sends {@code ESC [ ? <n> l} to {@code out}.
     *
     * @param out the terminal's output
     * @param encoding the coordinate encoding to disable
     * @throws IOException if writing to {@code out} fails
     */
    public static void disableEncoding(Appendable out, Encoding encoding) throws IOException {
        setMode(out, encoding.getModeNumber(), false);
    }

    // ── detection ─────────────────────────────────────────────────────────────

    /**
     * Returns {@code true} if {@code sequence} appears to be a terminal mouse event.
     *
     * <p>Recognises the three supported formats:
     *
     * <ul>
     *   <li>Legacy X10: exactly {@code ESC [ M} followed by three raw bytes
     *   <li>SGR: {@code ESC [ <} &hellip; {@code M} or {@code m}
     *   <li>URXVT: {@code ESC [} digits {@code ;} digits {@code ;} digits {@code M}
     * </ul>
     *
     * @param sequence the escape sequence to inspect; may be {@code null}
     * @return {@code true} if the sequence encodes a mouse event
     */
    public static boolean isMouseEvent(String sequence) {
        if (sequence == null || sequence.length() < 6) {
            return false;
        }
        if (sequence.charAt(0) != '\033' || sequence.charAt(1) != '[') {
            return false;
        }
        char c2 = sequence.charAt(2);
        if (c2 == 'M') {
            // Legacy X10: ESC [ M <b> <x> <y> — exactly 6 characters
            return sequence.length() == 6;
        }
        if (c2 == '<') {
            // SGR
            return parseSgr(sequence) != null;
        }
        if (Character.isDigit(c2) && sequence.charAt(sequence.length() - 1) == 'M') {
            // URXVT
            return parseUrxvt(sequence) != null;
        }
        return false;
    }

    // ── parsing ───────────────────────────────────────────────────────────────

    /**
     * Parses a terminal mouse-event escape sequence into a {@link MouseEvent}.
     *
     * <p>Returns {@code null} if the sequence is not a recognised mouse event. The three supported
     * input formats are described in {@link MouseTracking}.
     *
     * @param sequence the escape sequence to parse; may be {@code null}
     * @return the decoded {@link MouseEvent}, or {@code null} if parsing failed
     */
    public static MouseEvent parse(String sequence) {
        if (sequence == null) {
            return null;
        }
        MouseEvent ev = parseLegacy(sequence);
        if (ev == null) ev = parseSgr(sequence);
        if (ev == null) ev = parseUrxvt(sequence);
        return ev;
    }

    // ── private helpers ───────────────────────────────────────────────────────

    private static void setMode(Appendable out, int mode, boolean enable) throws IOException {
        out.append(CSI);
        out.append('?');
        out.append(Integer.toString(mode));
        out.append(enable ? DEC_SET : DEC_RST);
    }

    /**
     * Parses a legacy X10 mouse sequence: {@code ESC [ M <b+32> <x+32> <y+32>}.
     *
     * <p>The three payload characters are raw bytes offset by 32. Coordinates are 1-based.
     */
    private static MouseEvent parseLegacy(String sequence) {
        if (sequence.length() != 6) return null;
        if (sequence.charAt(0) != '\033') return null;
        if (sequence.charAt(1) != '[') return null;
        if (sequence.charAt(2) != 'M') return null;

        int b = sequence.charAt(3) - 32;
        int x = sequence.charAt(4) - 32;
        int y = sequence.charAt(5) - 32;

        if (x < 1 || y < 1) return null;

        return decodeButton(b, x, y, false);
    }

    /**
     * Parses an SGR mouse sequence: {@code ESC [ < Pb ; Px ; Py M/m}.
     *
     * <p>{@code 'M'} = press/motion/scroll; {@code 'm'} = button release.
     */
    private static MouseEvent parseSgr(String sequence) {
        if (sequence.length() < 9) return null;
        if (sequence.charAt(0) != '\033') return null;
        if (sequence.charAt(1) != '[') return null;
        if (sequence.charAt(2) != '<') return null;

        char last = sequence.charAt(sequence.length() - 1);
        if (last != 'M' && last != 'm') return null;

        String params = sequence.substring(3, sequence.length() - 1);
        int first = params.indexOf(';');
        if (first < 0) return null;
        int second = params.indexOf(';', first + 1);
        if (second < 0) return null;
        // Ensure there is no fourth semicolon
        if (params.indexOf(';', second + 1) >= 0) return null;

        try {
            int b = Integer.parseInt(params.substring(0, first));
            int x = Integer.parseInt(params.substring(first + 1, second));
            int y = Integer.parseInt(params.substring(second + 1));
            if (x < 1 || y < 1) return null;
            return decodeButton(b, x, y, last == 'm');
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * Parses a URXVT mouse sequence: {@code ESC [ Pb ; Px ; Py M}.
     *
     * <p>Coordinates are decimal; all events use {@code 'M'} as the terminator.
     */
    private static MouseEvent parseUrxvt(String sequence) {
        if (sequence.length() < 8) return null;
        if (sequence.charAt(0) != '\033') return null;
        if (sequence.charAt(1) != '[') return null;
        if (sequence.charAt(2) == '<') return null; // SGR, not URXVT
        if (sequence.charAt(2) == 'M') return null; // legacy, not URXVT
        if (!Character.isDigit(sequence.charAt(2))) return null;
        if (sequence.charAt(sequence.length() - 1) != 'M') return null;

        String params = sequence.substring(2, sequence.length() - 1);
        int first = params.indexOf(';');
        if (first < 0) return null;
        int second = params.indexOf(';', first + 1);
        if (second < 0) return null;
        if (params.indexOf(';', second + 1) >= 0) return null;

        try {
            int b = Integer.parseInt(params.substring(0, first));
            int x = Integer.parseInt(params.substring(first + 1, second));
            int y = Integer.parseInt(params.substring(second + 1));
            if (x < 1 || y < 1) return null;
            return decodeButton(b, x, y, false);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    /**
     * Decodes the button byte / parameter into a {@link MouseEvent}.
     *
     * <p>The button byte bit layout (same for legacy, SGR and URXVT after removing the +32 offset
     * used in legacy X10 encoding):
     *
     * <ul>
     *   <li>Bits&nbsp;0–1 (mask {@code 0x03}): button index — 0&nbsp;=&nbsp;left,
     *       1&nbsp;=&nbsp;middle, 2&nbsp;=&nbsp;right, 3&nbsp;=&nbsp;none / legacy release
     *   <li>Bit&nbsp;2 ({@code 0x04}): Shift modifier
     *   <li>Bit&nbsp;3 ({@code 0x08}): Alt/Meta modifier
     *   <li>Bit&nbsp;4 ({@code 0x10}): Ctrl modifier
     *   <li>Bit&nbsp;5 ({@code 0x20}): motion event
     *   <li>Bit&nbsp;6 ({@code 0x40}): extended button (scroll wheel)
     * </ul>
     *
     * @param b the raw button byte (without the +32 offset of legacy X10)
     * @param x 1-based column
     * @param y 1-based row
     * @param sgrRelease {@code true} when the SGR terminator was {@code 'm'} (explicit release)
     */
    private static MouseEvent decodeButton(int b, int x, int y, boolean sgrRelease) {
        boolean shift = (b & 0x04) != 0;
        boolean alt = (b & 0x08) != 0;
        boolean ctrl = (b & 0x10) != 0;
        boolean motion = (b & 0x20) != 0;
        boolean extended = (b & 0x40) != 0;
        int buttonIndex = b & 0x03;

        MouseEvent.Button button;
        MouseEvent.Type type;

        if (extended) {
            // Scroll-wheel events use the extended bit; button index 0 = up, 1 = down.
            if (buttonIndex == 0) {
                button = MouseEvent.Button.SCROLL_UP;
            } else if (buttonIndex == 1) {
                button = MouseEvent.Button.SCROLL_DOWN;
            } else {
                button = MouseEvent.Button.UNKNOWN;
            }
            type = MouseEvent.Type.SCROLL;
        } else if (sgrRelease) {
            // SGR explicitly identifies the released button via the 'm' terminator.
            button = buttonFromIndex(buttonIndex);
            type = MouseEvent.Type.RELEASE;
        } else if (!motion && buttonIndex == 3) {
            // Legacy / URXVT release: button index 3 means any button was released; the
            // specific button is not reported by these protocols.
            button = MouseEvent.Button.NONE;
            type = MouseEvent.Type.RELEASE;
        } else if (motion && buttonIndex == 3) {
            // Motion with no button held (hover).
            button = MouseEvent.Button.NONE;
            type = MouseEvent.Type.MOVE;
        } else if (motion) {
            // Motion with a button held (drag).
            button = buttonFromIndex(buttonIndex);
            type = MouseEvent.Type.DRAG;
        } else {
            // Button press.
            button = buttonFromIndex(buttonIndex);
            type = MouseEvent.Type.PRESS;
        }

        return new MouseEvent(type, button, x, y, shift, alt, ctrl);
    }

    private static MouseEvent.Button buttonFromIndex(int index) {
        switch (index) {
            case 0:
                return MouseEvent.Button.LEFT;
            case 1:
                return MouseEvent.Button.MIDDLE;
            case 2:
                return MouseEvent.Button.RIGHT;
            default:
                return MouseEvent.Button.UNKNOWN;
        }
    }
}
