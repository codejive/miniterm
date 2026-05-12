package org.codejive.miniterm.termcap;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;

/**
 * Reads a single field — {@code max_colors} — from a compiled terminfo binary file.
 *
 * <p>The terminfo binary format (both legacy 16-bit and extended 32-bit number sections) is defined
 * in {@code term(5)}. We only need the {@code max_colors} numeric capability (index 13 in the
 * ncurses layout) and parse just enough of the header to locate it.
 *
 * <p>Search order follows the standard ncurses convention:
 *
 * <ol>
 *   <li>{@code $TERMINFO/<first-char>/<term>}
 *   <li>Each dir in {@code $TERMINFO_DIRS} (colon-separated)
 *   <li>{@code ~/.terminfo/<first-char>/<term>}
 *   <li>{@code /etc/terminfo/<first-char>/<term>}
 *   <li>{@code /usr/share/terminfo/<first-char>/<term>}
 * </ol>
 *
 * <p>Returns {@code -1} if the file is absent or cannot be parsed.
 */
final class TerminfoReader {

    // ncurses numeric capability index for max_colors
    private static final int MAX_COLORS_INDEX = 13;

    // Terminfo magic numbers
    private static final int MAGIC_LEGACY = 0x011A; // 16-bit numbers
    private static final int MAGIC_EXTENDED = 0x021E; // 32-bit numbers (ncurses extension)

    private static final String[] DEFAULT_DIRS = {
        "/etc/terminfo", "/usr/share/terminfo", "/usr/lib/terminfo"
    };

    private TerminfoReader() {}

    /**
     * Returns the {@code max_colors} value from the terminfo entry for {@code term}, or {@code -1}
     * if it cannot be determined.
     */
    static int readMaxColors(String term) {
        if (term == null || term.isEmpty()) return -1;
        File f = findFile(term, System.getenv("TERMINFO"), System.getenv("TERMINFO_DIRS"));
        if (f == null) return -1;
        try {
            return parse(f);
        } catch (IOException e) {
            return -1;
        }
    }

    /**
     * Testable overload: searches {@code terminfo} and {@code terminfoDirs} instead of reading real
     * environment variables.
     */
    static int readMaxColors(String term, String terminfo, String terminfoDirs) {
        if (term == null || term.isEmpty()) return -1;
        File f = findFile(term, terminfo, terminfoDirs);
        if (f == null) return -1;
        try {
            return parse(f);
        } catch (IOException e) {
            return -1;
        }
    }

    // ── File search ───────────────────────────────────────────────────────

    private static File findFile(String term, String terminfo, String terminfoDirs) {
        String firstChar = term.substring(0, 1);

        // 1. $TERMINFO
        if (terminfo != null && !terminfo.isEmpty()) {
            File f = new File(terminfo, firstChar + File.separator + term);
            if (f.isFile()) return f;
        }

        // 2. $TERMINFO_DIRS (colon-separated)
        if (terminfoDirs != null && !terminfoDirs.isEmpty()) {
            for (String dir : terminfoDirs.split(":")) {
                if (!dir.isEmpty()) {
                    File f = new File(dir, firstChar + File.separator + term);
                    if (f.isFile()) return f;
                }
            }
        }

        // 3. ~/.terminfo
        String home = System.getProperty("user.home");
        if (home != null) {
            File f =
                    new File(
                            home, ".terminfo" + File.separator + firstChar + File.separator + term);
            if (f.isFile()) return f;
        }

        // 4-5. System directories
        for (String dir : DEFAULT_DIRS) {
            File f = new File(dir, firstChar + File.separator + term);
            if (f.isFile()) return f;
        }

        return null;
    }

    // ── Binary parser ─────────────────────────────────────────────────────

    /**
     * Parses the terminfo binary and returns {@code max_colors}, or {@code -1} on any error.
     *
     * <p>Binary layout (all values little-endian):
     *
     * <pre>
     *   2  magic
     *   2  name_size      (bytes, including NUL terminator)
     *   2  bool_count
     *   2  num_count
     *   2  str_count      (unused)
     *   2  str_table_size (unused)
     *   name_size bytes   (terminal names, NUL-terminated)
     *   bool_count bytes  (boolean capabilities)
     *   0 or 1 pad byte   (align to even offset)
     *   num_count * (2 or 4) bytes  (numeric capabilities)
     * </pre>
     */
    private static int parse(File f) throws IOException {
        InputStream in = new FileInputStream(f);
        try {
            // Header: 6 shorts (12 bytes)
            byte[] header = new byte[12];
            if (readFully(in, header) < 12) return -1;

            int magic = le16(header, 0);
            boolean wide = (magic == MAGIC_EXTENDED);
            if (magic != MAGIC_LEGACY && magic != MAGIC_EXTENDED) return -1;

            int nameSize = le16(header, 2);
            int boolCount = le16(header, 4);
            int numCount = le16(header, 6);

            if (numCount <= MAX_COLORS_INDEX) return -1; // capability not present

            // Skip names section
            if (skip(in, nameSize) < nameSize) return -1;

            // Skip booleans section
            if (skip(in, boolCount) < boolCount) return -1;

            // Align to even byte boundary (from start of file)
            int consumed = 12 + nameSize + boolCount;
            if ((consumed & 1) != 0) {
                if (in.read() < 0) return -1;
            }

            // Skip to MAX_COLORS_INDEX in the numbers section
            int bytesPerNum = wide ? 4 : 2;
            long toSkip = (long) MAX_COLORS_INDEX * bytesPerNum;
            if (skip(in, toSkip) < toSkip) return -1;

            // Read the value
            byte[] numBuf = new byte[bytesPerNum];
            if (readFully(in, numBuf) < bytesPerNum) return -1;

            int value = wide ? le32(numBuf, 0) : le16(numBuf, 0);
            // A value of -1 (0xFFFF / 0xFFFFFFFF) means "capability absent"
            return (value < 0) ? -1 : value;
        } finally {
            try {
                in.close();
            } catch (IOException ignored) {
            }
        }
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    private static int le16(byte[] b, int off) {
        return (b[off] & 0xFF) | ((b[off + 1] & 0xFF) << 8);
    }

    private static int le32(byte[] b, int off) {
        return (b[off] & 0xFF)
                | ((b[off + 1] & 0xFF) << 8)
                | ((b[off + 2] & 0xFF) << 16)
                | ((b[off + 3] & 0xFF) << 24);
    }

    private static int readFully(InputStream in, byte[] buf) throws IOException {
        int total = 0;
        while (total < buf.length) {
            int n = in.read(buf, total, buf.length - total);
            if (n < 0) break;
            total += n;
        }
        return total;
    }

    private static long skip(InputStream in, long n) throws IOException {
        long remaining = n;
        while (remaining > 0) {
            long skipped = in.skip(remaining);
            if (skipped <= 0) {
                // skip() may return 0; fall back to a read to detect EOF
                if (in.read() < 0) break;
                skipped = 1;
            }
            remaining -= skipped;
        }
        return n - remaining;
    }
}
