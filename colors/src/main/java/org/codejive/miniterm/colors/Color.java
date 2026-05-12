package org.codejive.miniterm.colors;

/**
 * An immutable 16-bit-per-channel RGB colour, as used in terminal OSC colour specifications.
 *
 * <p>Colours are stored with 16 bits per channel (0–65535), which matches the precision returned by
 * terminals in {@code rgb:RRRR/GGGG/BBBB} format. 8-bit values (0–255) can be created with {@link
 * #ofRgb8} and retrieved with {@link #r8()}, {@link #g8()}, {@link #b8()}.
 *
 * <h2>Parsing</h2>
 *
 * <p>{@link #parse(String)} accepts the X11 {@code rgb:} colour specification with 1–4 hex digits
 * per channel:
 *
 * <pre>{@code
 * Color red = Color.parse("rgb:FFFF/0000/0000");
 * Color grey = Color.parse("rgb:80/80/80"); // 8-bit
 * }</pre>
 *
 * <h2>Formatting</h2>
 *
 * <p>{@link #toString()} always produces the canonical 4-digit form {@code rgb:RRRR/GGGG/BBBB},
 * suitable for use in OSC set sequences.
 */
public final class Color {

    private final int r; // 0..65535
    private final int g;
    private final int b;

    private Color(int r, int g, int b) {
        if (r < 0 || r > 65535 || g < 0 || g > 65535 || b < 0 || b > 65535) {
            throw new IllegalArgumentException(
                    "colour component out of range [0..65535]: " + r + "," + g + "," + b);
        }
        this.r = r;
        this.g = g;
        this.b = b;
    }

    /**
     * Creates a colour from 16-bit per channel values (0–65535 each).
     *
     * @param r red channel 0–65535
     * @param g green channel 0–65535
     * @param b blue channel 0–65535
     * @return new colour
     * @throws IllegalArgumentException if any component is out of range
     */
    public static Color of(int r, int g, int b) {
        return new Color(r, g, b);
    }

    /**
     * Creates a colour from 8-bit per channel values (0–255 each).
     *
     * <p>Each component is expanded to 16 bits by byte-replication ({@code 0xFF → 0xFFFF}).
     *
     * @param r red channel 0–255
     * @param g green channel 0–255
     * @param b blue channel 0–255
     * @return new colour
     * @throws IllegalArgumentException if any component is out of range
     */
    public static Color ofRgb8(int r, int g, int b) {
        if (r < 0 || r > 255 || g < 0 || g > 255 || b < 0 || b > 255) {
            throw new IllegalArgumentException(
                    "8-bit colour component out of range [0..255]: " + r + "," + g + "," + b);
        }
        return new Color((r << 8) | r, (g << 8) | g, (b << 8) | b);
    }

    /**
     * Parses an X11 {@code rgb:} colour specification.
     *
     * <p>Accepts 1–4 hex digits per channel. Each component is scaled to 16 bits:
     *
     * <ul>
     *   <li>4 digits — used as-is
     *   <li>2 digits — byte-replicated (e.g. {@code FF} → {@code FFFF})
     *   <li>1 digit — nibble-replicated (e.g. {@code F} → {@code FFFF})
     *   <li>3 digits — approximated as left-justified 12-bit value
     * </ul>
     *
     * @param s string starting with {@code rgb:} followed by three hex components separated by
     *     {@code /}
     * @return parsed colour, or {@code null} if {@code s} is null, does not start with {@code
     *     rgb:}, or contains invalid hex digits
     */
    public static Color parse(String s) {
        if (s == null) return null;
        String t = s.trim();
        if (!t.startsWith("rgb:")) return null;
        String[] parts = t.substring(4).split("/", -1);
        if (parts.length != 3) return null;
        try {
            int r = normalizeHex(parts[0].trim());
            int g = normalizeHex(parts[1].trim());
            int b = normalizeHex(parts[2].trim());
            return new Color(r, g, b);
        } catch (IllegalArgumentException e) {
            return null;
        }
    }

    /** Red channel as a 16-bit value (0–65535). */
    public int r() {
        return r;
    }

    /** Green channel as a 16-bit value (0–65535). */
    public int g() {
        return g;
    }

    /** Blue channel as a 16-bit value (0–65535). */
    public int b() {
        return b;
    }

    /** Red channel downsampled to 8 bits (0–255). */
    public int r8() {
        return r >> 8;
    }

    /** Green channel downsampled to 8 bits (0–255). */
    public int g8() {
        return g >> 8;
    }

    /** Blue channel downsampled to 8 bits (0–255). */
    public int b8() {
        return b >> 8;
    }

    /**
     * Returns the colour as a packed 24-bit RGB integer ({@code 0xRRGGBB}), using the 8-bit
     * downsampled values.
     *
     * @return packed RGB integer
     */
    public int toRgb8() {
        return (r8() << 16) | (g8() << 8) | b8();
    }

    /**
     * Returns the canonical {@code rgb:RRRR/GGGG/BBBB} representation suitable for use in OSC
     * terminal sequences.
     */
    @Override
    public String toString() {
        return String.format("rgb:%04X/%04X/%04X", r, g, b);
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof Color)) return false;
        Color other = (Color) o;
        return r == other.r && g == other.g && b == other.b;
    }

    @Override
    public int hashCode() {
        int result = r;
        result = 31 * result + g;
        result = 31 * result + b;
        return result;
    }

    // ── Internal ──────────────────────────────────────────────────────────

    /**
     * Scale a hex string of 1–4 digits to a 16-bit integer using digit-replication, matching the
     * XTerm/X11 convention for the {@code rgb:} colour spec.
     */
    private static int normalizeHex(String hex) {
        if (hex.isEmpty() || hex.length() > 4) {
            throw new NumberFormatException("invalid hex component length: '" + hex + "'");
        }
        int val = Integer.parseInt(hex, 16);
        switch (hex.length()) {
            case 1:
                return val * 0x1111; // nibble-repeat: F → FFFF
            case 2:
                return (val << 8) | val; // byte-repeat: FF → FFFF
            case 3:
                return (val << 4) | (val >> 8); // 12-bit left-justified: FFF → FFFF
            case 4:
                return val;
            default:
                return val;
        }
    }
}
