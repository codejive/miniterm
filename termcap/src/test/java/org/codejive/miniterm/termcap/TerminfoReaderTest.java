package org.codejive.miniterm.termcap;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

class TerminfoReaderTest {

    @TempDir File tempDir;

    // ── helpers ───────────────────────────────────────────────────────────────

    /**
     * Builds a minimal terminfo binary for {@code term} with the given {@code maxColors} value and
     * writes it to {@code dir/<first-char>/<term>}.
     *
     * <p>Binary layout used here (legacy 16-bit magic):
     *
     * <pre>
     *   magic       0x011A  (little-endian)
     *   name_size   = len(term)+1
     *   bool_count  = 0
     *   num_count   = 14   (enough to include index 13 = max_colors)
     *   str_count   = 0
     *   str_table   = 0
     *   names       = term + NUL
     *   numbers[0..12] = -1 (absent)
     *   numbers[13]    = maxColors
     * </pre>
     */
    private File writeTerminfo(File dir, String term, int maxColors) throws IOException {
        File subDir = new File(dir, term.substring(0, 1));
        subDir.mkdirs();
        File f = new File(subDir, term);

        int nameSize = term.length() + 1; // +1 for NUL
        int boolCount = 0;
        int numCount = 14; // indices 0-13; 13 = max_colors

        // Pad byte is required when the offset just before the numbers section is odd.
        // That offset = 12 (header) + nameSize + boolCount.
        int preNumOffset = 12 + nameSize + boolCount;
        int padByte = (preNumOffset % 2 != 0) ? 1 : 0;

        byte[] data = new byte[12 + nameSize + boolCount + padByte + numCount * 2];
        int pos = 0;

        // magic (0x011A little-endian)
        data[pos++] = 0x1A;
        data[pos++] = 0x01;
        // name_size
        data[pos++] = (byte) (nameSize & 0xFF);
        data[pos++] = (byte) ((nameSize >> 8) & 0xFF);
        // bool_count = 0
        data[pos++] = 0;
        data[pos++] = 0;
        // num_count = 14
        data[pos++] = (byte) (numCount & 0xFF);
        data[pos++] = (byte) ((numCount >> 8) & 0xFF);
        // str_count = 0
        data[pos++] = 0;
        data[pos++] = 0;
        // str_table_size = 0
        data[pos++] = 0;
        data[pos++] = 0;

        // Names section
        for (int i = 0; i < term.length(); i++) {
            data[pos++] = (byte) term.charAt(i);
        }
        data[pos++] = 0; // NUL terminator

        // Pad byte (if needed)
        if (padByte == 1) {
            data[pos++] = 0;
        }

        // Numbers[0..12] = absent (-1 = 0xFFFF)
        for (int i = 0; i < 13; i++) {
            data[pos++] = (byte) 0xFF;
            data[pos++] = (byte) 0xFF;
        }

        // Numbers[13] = maxColors
        data[pos++] = (byte) (maxColors & 0xFF);
        data[pos] = (byte) ((maxColors >> 8) & 0xFF);

        FileOutputStream out = new FileOutputStream(f);
        try {
            out.write(data);
        } finally {
            out.close();
        }
        return f;
    }

    // ── tests ─────────────────────────────────────────────────────────────────

    @Test
    void readsMaxColorsFromLegacyBinary() throws IOException {
        writeTerminfo(tempDir, "xterm-256color", 256);

        int result =
                TerminfoReaderTestHelper.readMaxColors(
                        "xterm-256color", tempDir.getAbsolutePath(), null);
        assertThat(result).isEqualTo(256);
    }

    @Test
    void readsTruecolorFromExtendedBinary() throws IOException {
        // 16M does not fit in 16-bit; use extended (32-bit) magic 0x021E
        String term = "xterm-direct";
        File subDir = new File(tempDir, term.substring(0, 1));
        subDir.mkdirs();
        File f = new File(subDir, term);

        int nameSize = term.length() + 1;
        int boolCount = 0;
        int numCount = 14;
        int maxColors = 16_777_216;

        int preNumOffset = 12 + nameSize + boolCount;
        int padByte = (preNumOffset % 2 != 0) ? 1 : 0;

        byte[] data = new byte[12 + nameSize + boolCount + padByte + numCount * 4];
        int pos = 0;
        // magic 0x021E (extended, little-endian)
        data[pos++] = 0x1E;
        data[pos++] = 0x02;
        data[pos++] = (byte) (nameSize & 0xFF);
        data[pos++] = (byte) ((nameSize >> 8) & 0xFF);
        data[pos++] = 0;
        data[pos++] = 0; // bool_count
        data[pos++] = (byte) (numCount & 0xFF);
        data[pos++] = (byte) ((numCount >> 8) & 0xFF);
        data[pos++] = 0;
        data[pos++] = 0; // str_count
        data[pos++] = 0;
        data[pos++] = 0; // str_table_size
        for (int i = 0; i < term.length(); i++) data[pos++] = (byte) term.charAt(i);
        data[pos++] = 0;
        if (padByte == 1) data[pos++] = 0;
        // numbers[0..12] = absent (-1 = 0xFFFFFFFF)
        for (int i = 0; i < 13; i++) {
            data[pos++] = (byte) 0xFF;
            data[pos++] = (byte) 0xFF;
            data[pos++] = (byte) 0xFF;
            data[pos++] = (byte) 0xFF;
        }
        // numbers[13] = 16_777_216
        data[pos++] = (byte) (maxColors & 0xFF);
        data[pos++] = (byte) ((maxColors >> 8) & 0xFF);
        data[pos++] = (byte) ((maxColors >> 16) & 0xFF);
        data[pos] = (byte) ((maxColors >> 24) & 0xFF);

        FileOutputStream out = new FileOutputStream(f);
        try {
            out.write(data);
        } finally {
            out.close();
        }

        int result = TerminfoReaderTestHelper.readMaxColors(term, tempDir.getAbsolutePath(), null);
        assertThat(result).isEqualTo(16_777_216);
    }

    @Test
    void returnsMinusOneForMissingFile() {
        int result =
                TerminfoReaderTestHelper.readMaxColors(
                        "no-such-term", tempDir.getAbsolutePath(), null);
        assertThat(result).isEqualTo(-1);
    }

    @Test
    void returnsMinusOneForAbsentCapability() throws IOException {
        // Write a binary where numCount = 5 (too small to contain index 13)
        String term = "tiny-term";
        File subDir = new File(tempDir, term.substring(0, 1));
        subDir.mkdirs();
        File f = new File(subDir, term);

        int nameSize = term.length() + 1;
        int numCount = 5; // index 13 absent

        byte[] data = new byte[12 + nameSize + numCount * 2];
        int pos = 0;
        data[pos++] = 0x1A;
        data[pos++] = 0x01; // magic
        data[pos++] = (byte) nameSize;
        data[pos++] = 0; // name_size
        data[pos++] = 0;
        data[pos++] = 0; // bool_count
        data[pos++] = (byte) numCount;
        data[pos++] = 0; // num_count = 5

        FileOutputStream out = new FileOutputStream(f);
        try {
            out.write(data);
        } finally {
            out.close();
        }

        int result = TerminfoReaderTestHelper.readMaxColors(term, tempDir.getAbsolutePath(), null);
        assertThat(result).isEqualTo(-1);
    }

    @Test
    void returnsMinusOneForBadMagic() throws IOException {
        String term = "bad-magic";
        File subDir = new File(tempDir, term.substring(0, 1));
        subDir.mkdirs();
        File f = new File(subDir, term);
        Files.write(f.toPath(), new byte[] {0x00, 0x00, 0x00, 0x00});

        int result = TerminfoReaderTestHelper.readMaxColors(term, tempDir.getAbsolutePath(), null);
        assertThat(result).isEqualTo(-1);
    }

    @Test
    void handlesTermWithEvenBooleanCount() throws IOException {
        // bool_count = 2 → pad byte needed
        String term = "xterm";
        File subDir = new File(tempDir, term.substring(0, 1));
        subDir.mkdirs();
        File f = new File(subDir, term);

        int nameSize = term.length() + 1;
        int boolCount = 2; // even → no pad byte needed (12 + nameSize + boolCount is even)
        int numCount = 14;

        // total consumed before booleans = 12 + nameSize
        // + boolCount → check parity of 12 + nameSize + boolCount
        int consumedBeforeNums = 12 + nameSize + boolCount;
        int padByte = (consumedBeforeNums % 2 != 0) ? 1 : 0;

        byte[] data = new byte[12 + nameSize + boolCount + padByte + numCount * 2];
        int pos = 0;
        data[pos++] = 0x1A;
        data[pos++] = 0x01;
        data[pos++] = (byte) (nameSize & 0xFF);
        data[pos++] = (byte) ((nameSize >> 8) & 0xFF);
        data[pos++] = (byte) boolCount;
        data[pos++] = 0;
        data[pos++] = (byte) numCount;
        data[pos++] = 0;
        data[pos++] = 0;
        data[pos++] = 0;
        data[pos++] = 0;
        data[pos++] = 0;
        for (int i = 0; i < term.length(); i++) data[pos++] = (byte) term.charAt(i);
        data[pos++] = 0;
        // booleans
        for (int i = 0; i < boolCount; i++) data[pos++] = 1;
        // pad
        for (int i = 0; i < padByte; i++) data[pos++] = 0;
        // numbers[0..12] absent
        for (int i = 0; i < 13; i++) {
            data[pos++] = (byte) 0xFF;
            data[pos++] = (byte) 0xFF;
        }
        // numbers[13] = 8
        data[pos++] = 8;
        data[pos] = 0;

        FileOutputStream out = new FileOutputStream(f);
        try {
            out.write(data);
        } finally {
            out.close();
        }

        int result = TerminfoReaderTestHelper.readMaxColors(term, tempDir.getAbsolutePath(), null);
        assertThat(result).isEqualTo(8);
    }
}
