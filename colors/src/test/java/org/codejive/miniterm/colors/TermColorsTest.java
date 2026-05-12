package org.codejive.miniterm.colors;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import org.codejive.miniterm.ansiparser.IntReader;
import org.junit.jupiter.api.Test;

class TermColorsTest {

    // ── oscBody helper ────────────────────────────────────────────────────

    @Test
    void oscBody_belTerminated() {
        String seq = "\033]10;rgb:FFFF/0000/0000\007";
        assertThat(TermColors.oscBody(seq)).isEqualTo("10;rgb:FFFF/0000/0000");
    }

    @Test
    void oscBody_stTerminated() {
        String seq = "\033]11;rgb:0000/FFFF/0000\033\\";
        assertThat(TermColors.oscBody(seq)).isEqualTo("11;rgb:0000/FFFF/0000");
    }

    @Test
    void oscBody_nonOsc_returnsNull() {
        assertThat(TermColors.oscBody(null)).isNull();
        assertThat(TermColors.oscBody("\033[c")).isNull();
        assertThat(TermColors.oscBody("hello")).isNull();
    }

    @Test
    void oscBody_noTerminator_returnsBodyAsIs() {
        // Incomplete sequence — body returned without terminator stripping
        String seq = "\033]10;rgb:FFFF/0000/0000";
        assertThat(TermColors.oscBody(seq)).isEqualTo("10;rgb:FFFF/0000/0000");
    }

    // ── Send-query methods ─────────────────────────────────────────────────

    @Test
    void sendForegroundQuery_writesCorrectSequence() throws IOException {
        StringBuilder out = new StringBuilder();
        TermColors.sendForegroundQuery(out);
        assertThat(out.toString()).isEqualTo("\033]10;?\007");
    }

    @Test
    void sendBackgroundQuery_writesCorrectSequence() throws IOException {
        StringBuilder out = new StringBuilder();
        TermColors.sendBackgroundQuery(out);
        assertThat(out.toString()).isEqualTo("\033]11;?\007");
    }

    @Test
    void sendCursorQuery_writesCorrectSequence() throws IOException {
        StringBuilder out = new StringBuilder();
        TermColors.sendCursorQuery(out);
        assertThat(out.toString()).isEqualTo("\033]12;?\007");
    }

    @Test
    void sendColorQuery_writesCorrectSequence() throws IOException {
        StringBuilder out = new StringBuilder();
        TermColors.sendColorQuery(out, 42);
        assertThat(out.toString()).isEqualTo("\033]4;42;?\007");
    }

    @Test
    void sendColorQuery_rejectsOutOfRange() {
        assertThatThrownBy(() -> TermColors.sendColorQuery(new StringBuilder(), -1))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> TermColors.sendColorQuery(new StringBuilder(), 256))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ── Detection methods ─────────────────────────────────────────────────

    @Test
    void isForegroundQueryResult_matchesOsc10() {
        assertThat(TermColors.isForegroundQueryResult("\033]10;rgb:FFFF/0000/0000\007")).isTrue();
        assertThat(TermColors.isForegroundQueryResult("\033]11;rgb:FFFF/0000/0000\007")).isFalse();
        assertThat(TermColors.isForegroundQueryResult("\033[c")).isFalse();
        assertThat(TermColors.isForegroundQueryResult(null)).isFalse();
    }

    @Test
    void isBackgroundQueryResult_matchesOsc11() {
        assertThat(TermColors.isBackgroundQueryResult("\033]11;rgb:0000/0000/0000\007")).isTrue();
        assertThat(TermColors.isBackgroundQueryResult("\033]10;rgb:0000/0000/0000\007")).isFalse();
        assertThat(TermColors.isBackgroundQueryResult(null)).isFalse();
    }

    @Test
    void isCursorQueryResult_matchesOsc12() {
        assertThat(TermColors.isCursorQueryResult("\033]12;rgb:0000/FFFF/0000\007")).isTrue();
        assertThat(TermColors.isCursorQueryResult("\033]11;rgb:0000/FFFF/0000\007")).isFalse();
        assertThat(TermColors.isCursorQueryResult(null)).isFalse();
    }

    @Test
    void isColorQueryResult_anyOsc4() {
        assertThat(TermColors.isColorQueryResult("\033]4;7;rgb:AAAA/BBBB/CCCC\007")).isTrue();
        assertThat(TermColors.isColorQueryResult("\033]4;255;rgb:FFFF/FFFF/FFFF\007")).isTrue();
        assertThat(TermColors.isColorQueryResult("\033]10;rgb:FFFF/0000/0000\007")).isFalse();
        assertThat(TermColors.isColorQueryResult(null)).isFalse();
    }

    @Test
    void isColorQueryResult_withIndex() {
        String seq = "\033]4;7;rgb:AAAA/BBBB/CCCC\007";
        assertThat(TermColors.isColorQueryResult(seq, 7)).isTrue();
        assertThat(TermColors.isColorQueryResult(seq, 8)).isFalse();
        assertThat(TermColors.isColorQueryResult(null, 7)).isFalse();
    }

    // ── Parse methods ─────────────────────────────────────────────────────

    @Test
    void parseForeground_validSequence() {
        String seq = "\033]10;rgb:FFFF/0000/0000\007";
        assertThat(TermColors.parseForeground(seq)).isEqualTo(Color.of(0xFFFF, 0x0000, 0x0000));
    }

    @Test
    void parseForeground_wrongOscCode_returnsNull() {
        assertThat(TermColors.parseForeground("\033]11;rgb:FFFF/0000/0000\007")).isNull();
        assertThat(TermColors.parseForeground(null)).isNull();
    }

    @Test
    void parseBackground_validSequence() {
        String seq = "\033]11;rgb:1234/5678/9ABC\007";
        assertThat(TermColors.parseBackground(seq)).isEqualTo(Color.of(0x1234, 0x5678, 0x9ABC));
    }

    @Test
    void parseBackground_wrongOscCode_returnsNull() {
        assertThat(TermColors.parseBackground("\033]10;rgb:1234/5678/9ABC\007")).isNull();
    }

    @Test
    void parseCursor_validSequence() {
        String seq = "\033]12;rgb:0000/FFFF/0000\007";
        assertThat(TermColors.parseCursor(seq)).isEqualTo(Color.of(0x0000, 0xFFFF, 0x0000));
    }

    @Test
    void parseCursor_stTerminated() {
        String seq = "\033]12;rgb:0000/FFFF/0000\033\\";
        assertThat(TermColors.parseCursor(seq)).isEqualTo(Color.of(0x0000, 0xFFFF, 0x0000));
    }

    @Test
    void parseColor_validSequence() {
        String seq = "\033]4;7;rgb:AAAA/BBBB/CCCC\007";
        assertThat(TermColors.parseColor(seq, 7)).isEqualTo(Color.of(0xAAAA, 0xBBBB, 0xCCCC));
    }

    @Test
    void parseColor_wrongIndex_returnsNull() {
        String seq = "\033]4;7;rgb:AAAA/BBBB/CCCC\007";
        assertThat(TermColors.parseColor(seq, 8)).isNull();
    }

    @Test
    void parseColor_wrongOscCode_returnsNull() {
        assertThat(TermColors.parseColor("\033]10;rgb:AAAA/BBBB/CCCC\007", 10)).isNull();
    }

    // ── Set methods ───────────────────────────────────────────────────────

    @Test
    void setForeground_writesCorrectSequence() throws IOException {
        StringBuilder out = new StringBuilder();
        TermColors.setForeground(out, Color.of(0xFFFF, 0x0000, 0x0000));
        assertThat(out.toString()).isEqualTo("\033]10;rgb:FFFF/0000/0000\007");
    }

    @Test
    void setBackground_writesCorrectSequence() throws IOException {
        StringBuilder out = new StringBuilder();
        TermColors.setBackground(out, Color.of(0x0000, 0x0000, 0x0000));
        assertThat(out.toString()).isEqualTo("\033]11;rgb:0000/0000/0000\007");
    }

    @Test
    void setCursor_writesCorrectSequence() throws IOException {
        StringBuilder out = new StringBuilder();
        TermColors.setCursor(out, Color.of(0xFFFF, 0xFFFF, 0x0000));
        assertThat(out.toString()).isEqualTo("\033]12;rgb:FFFF/FFFF/0000\007");
    }

    @Test
    void setColor_writesCorrectSequence() throws IOException {
        StringBuilder out = new StringBuilder();
        TermColors.setColor(out, 7, Color.of(0xAAAA, 0xBBBB, 0xCCCC));
        assertThat(out.toString()).isEqualTo("\033]4;7;rgb:AAAA/BBBB/CCCC\007");
    }

    @Test
    void setColor_rejectsOutOfRange() {
        assertThatThrownBy(() -> TermColors.setColor(new StringBuilder(), -1, Color.of(0, 0, 0)))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> TermColors.setColor(new StringBuilder(), 256, Color.of(0, 0, 0)))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void setPalette_writesAllNonNullEntries() throws IOException {
        Color[] palette = new Color[256];
        palette[0] = Color.of(0x0000, 0x0000, 0x0000);
        palette[255] = Color.of(0xFFFF, 0xFFFF, 0xFFFF);

        StringBuilder out = new StringBuilder();
        TermColors.setPalette(out, palette);

        assertThat(out.toString())
                .contains("\033]4;0;rgb:0000/0000/0000\007")
                .contains("\033]4;255;rgb:FFFF/FFFF/FFFF\007");
        // Only two entries written (the rest are null)
        assertThat(out.toString().split("\033]4;").length - 1).isEqualTo(2);
    }

    @Test
    void setPalette_rejectsWrongLength() {
        assertThatThrownBy(() -> TermColors.setPalette(new StringBuilder(), new Color[100]))
                .isInstanceOf(IllegalArgumentException.class);
    }

    // ── Reset methods ─────────────────────────────────────────────────────

    @Test
    void resetForeground_writesCorrectSequence() throws IOException {
        StringBuilder out = new StringBuilder();
        TermColors.resetForeground(out);
        assertThat(out.toString()).isEqualTo("\033]110\007");
    }

    @Test
    void resetBackground_writesCorrectSequence() throws IOException {
        StringBuilder out = new StringBuilder();
        TermColors.resetBackground(out);
        assertThat(out.toString()).isEqualTo("\033]111\007");
    }

    @Test
    void resetCursor_writesCorrectSequence() throws IOException {
        StringBuilder out = new StringBuilder();
        TermColors.resetCursor(out);
        assertThat(out.toString()).isEqualTo("\033]112\007");
    }

    @Test
    void resetColor_writesCorrectSequence() throws IOException {
        StringBuilder out = new StringBuilder();
        TermColors.resetColor(out, 42);
        assertThat(out.toString()).isEqualTo("\033]104;42\007");
    }

    @Test
    void resetPalette_writesCorrectSequence() throws IOException {
        StringBuilder out = new StringBuilder();
        TermColors.resetPalette(out);
        assertThat(out.toString()).isEqualTo("\033]104\007");
    }

    // ── Query methods ─────────────────────────────────────────────────────

    @Test
    void queryForeground_parsesResponse() throws IOException {
        String response = "\033]10;rgb:FFFF/0000/0000\007";
        StringBuilder out = new StringBuilder();

        Color result = TermColors.queryForeground(out, stringReader(response));

        assertThat(out.toString()).isEqualTo("\033]10;?\007");
        assertThat(result).isEqualTo(Color.of(0xFFFF, 0x0000, 0x0000));
    }

    @Test
    void queryBackground_parsesResponse() throws IOException {
        String response = "\033]11;rgb:1234/5678/9ABC\007";
        Color result = TermColors.queryBackground(new StringBuilder(), stringReader(response));
        assertThat(result).isEqualTo(Color.of(0x1234, 0x5678, 0x9ABC));
    }

    @Test
    void queryCursor_parsesResponse() throws IOException {
        String response = "\033]12;rgb:0000/FFFF/0000\007";
        Color result = TermColors.queryCursor(new StringBuilder(), stringReader(response));
        assertThat(result).isEqualTo(Color.of(0x0000, 0xFFFF, 0x0000));
    }

    @Test
    void queryForeground_timeout_returnsNull() throws IOException {
        // Empty reader immediately times out
        Color result = TermColors.queryForeground(new StringBuilder(), timeoutReader());
        assertThat(result).isNull();
    }

    @Test
    void queryColor_parsesSpecificIndex() throws IOException {
        String response = "\033]4;7;rgb:AAAA/BBBB/CCCC\007";
        StringBuilder out = new StringBuilder();

        Color result = TermColors.queryColor(out, stringReader(response), 7);

        assertThat(out.toString()).isEqualTo("\033]4;7;?\007");
        assertThat(result).isEqualTo(Color.of(0xAAAA, 0xBBBB, 0xCCCC));
    }

    @Test
    void queryColor_skipsUnrelatedSequences() throws IOException {
        // First response has wrong index, second has the right one
        String responses = "\033]4;99;rgb:0000/0000/0000\007" + "\033]4;7;rgb:AAAA/BBBB/CCCC\007";
        Color result = TermColors.queryColor(new StringBuilder(), stringReader(responses), 7);
        assertThat(result).isEqualTo(Color.of(0xAAAA, 0xBBBB, 0xCCCC));
    }

    @Test
    void queryPalette_collectsBurstResponses() throws IOException {
        // Build responses for indices 0 and 3 only
        String resp0 = "\033]4;0;rgb:0000/0000/0000\007";
        String resp3 = "\033]4;3;rgb:FFFF/0000/0000\007";
        int[] indices = {0, 3};

        StringBuilder out = new StringBuilder();
        Color[] result = TermColors.queryPalette(out, stringReader(resp0 + resp3), indices);

        // Verify queries were sent
        assertThat(out.toString()).contains("\033]4;0;?\007").contains("\033]4;3;?\007");

        assertThat(result[0]).isEqualTo(Color.of(0x0000, 0x0000, 0x0000));
        assertThat(result[3]).isEqualTo(Color.of(0xFFFF, 0x0000, 0x0000));
        // Unrequested entries stay null
        assertThat(result[1]).isNull();
        assertThat(result[2]).isNull();
    }

    @Test
    void queryPalette_stTerminatedResponse() throws IOException {
        // Some terminals use ESC \ as string terminator instead of BEL
        String response = "\033]4;1;rgb:1234/5678/ABCD\033\\";
        Color[] result =
                TermColors.queryPalette(new StringBuilder(), stringReader(response), new int[] {1});
        assertThat(result[1]).isEqualTo(Color.of(0x1234, 0x5678, 0xABCD));
    }

    @Test
    void queryPalette_stopsOnTimeout() throws IOException {
        // Only one response; reader times out after that
        String resp = "\033]4;5;rgb:ABCD/EF01/2345\007";
        Color[] result =
                TermColors.queryPalette(new StringBuilder(), stringReader(resp), new int[] {5, 10});
        assertThat(result[5]).isEqualTo(Color.of(0xABCD, 0xEF01, 0x2345));
        assertThat(result[10]).isNull(); // timed out before receiving
    }

    // ── Helpers ───────────────────────────────────────────────────────────

    /** Returns an IntReader that serves characters from {@code s}, then signals timeout (-2). */
    private static IntReader stringReader(String s) {
        int[] pos = {0};
        return () -> {
            if (pos[0] >= s.length()) return -2;
            return s.charAt(pos[0]++);
        };
    }

    /** Returns an IntReader that immediately signals timeout. */
    private static IntReader timeoutReader() {
        return () -> -2;
    }
}
