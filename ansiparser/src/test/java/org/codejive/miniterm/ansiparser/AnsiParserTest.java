package org.codejive.miniterm.ansiparser;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class AnsiParserTest {

    // ── plain characters ──────────────────────────────────────────────────────

    @Test
    void plainCharIsReturnedAsIs() throws IOException {
        assertThat(AnsiParser.parse(supply('A'))).isEqualTo("A");
    }

    @Test
    void plainSpaceIsReturned() throws IOException {
        assertThat(AnsiParser.parse(supply(' '))).isEqualTo(" ");
    }

    @Test
    void eofAtStartReturnsNull() throws IOException {
        assertThat(AnsiParser.parse(supply(-1))).isNull();
    }

    @Test
    void timeoutAtStartReturnsEmpty() throws IOException {
        assertThat(AnsiParser.parse(supply(-2))).isEmpty();
    }

    // ── two-character ESC sequences ───────────────────────────────────────────

    @Test
    void simpleTwoCharEscSequence() throws IOException {
        assertThat(AnsiParser.parse(supply(0x1B, 'M'))).isEqualTo("\u001bM");
    }

    @Test
    void escFollowedByEofReturnsTruncated() throws IOException {
        assertThat(AnsiParser.parse(supply(0x1B, -1))).isEqualTo("\u001b");
    }

    // ── CSI sequences ─────────────────────────────────────────────────────────

    @Test
    void csiCursorUp() throws IOException {
        // ESC [ 1 A  → cursor up 1
        assertThat(AnsiParser.parse(supplyString("\u001b[1A"))).isEqualTo("\u001b[1A");
    }

    @Test
    void csiCursorPosition() throws IOException {
        // ESC [ 10 ; 20 H
        assertThat(AnsiParser.parse(supplyString("\u001b[10;20H"))).isEqualTo("\u001b[10;20H");
    }

    @Test
    void csiWithIntermediateByte() throws IOException {
        // ESC [ ! p  (DECSTR – intermediates are 0x20-0x2F, final is 0x40-0x7E)
        assertThat(AnsiParser.parse(supplyString("\u001b[!p"))).isEqualTo("\u001b[!p");
    }

    @Test
    void csiAbortsOnControlCode() throws IOException {
        // ESC [ BEL(0x07) – invalid byte terminates the sequence; the offending byte is
        // included in the returned string because it has already been consumed from the supplier
        String result = AnsiParser.parse(supply(0x1B, '[', 0x07));
        assertThat(result).isEqualTo("\u001b[\u0007");
    }

    // ── OSC sequences ─────────────────────────────────────────────────────────

    @Test
    void oscTerminatedByBel() throws IOException {
        // ESC ] 0 ; title BEL
        assertThat(AnsiParser.parse(supplyString("\u001b]0;My Title\u0007")))
                .isEqualTo("\u001b]0;My Title\u0007");
    }

    @Test
    void oscTerminatedBySt() throws IOException {
        // ESC ] 0 ; title ESC \
        assertThat(AnsiParser.parse(supplyString("\u001b]0;title\u001b\\")))
                .isEqualTo("\u001b]0;title\u001b\\");
    }

    @Test
    void oscEscNotFollowedByBackslashContinues() throws IOException {
        // ESC ] data ESC X(not \) → ESC was not ST, keep reading until BEL
        assertThat(AnsiParser.parse(supplyString("\u001b]data\u001bX\u0007")))
                .isEqualTo("\u001b]data\u001bX\u0007");
    }

    // ── charset designator sequences ──────────────────────────────────────────

    @Test
    void charsetDesignatorSequence() throws IOException {
        // ESC ( B  → designate G0 as ASCII
        assertThat(AnsiParser.parse(supplyString("\u001b(B"))).isEqualTo("\u001b(B");
    }

    // ── DoS protection ────────────────────────────────────────────────────────

    @Test
    void dosProtectionTruncatesLongSequence() throws IOException {
        // Infinite CSI sequence with parameter bytes; should stop at MAX_SEQUENCE_LENGTH
        AtomicInteger count = new AtomicInteger();
        IntReader endless =
                () -> {
                    int i = count.getAndIncrement();
                    if (i == 0) return 0x1B;
                    if (i == 1) return '[';
                    return '0'; // endless parameter bytes
                };
        String result = AnsiParser.parse(endless);
        assertThat(result).hasSize(AnsiParser.MAX_SEQUENCE_LENGTH);
        assertThat(result).startsWith("\u001b[");
    }

    private static IntReader supply(int... chars) {
        AtomicInteger idx = new AtomicInteger();
        return () -> {
            int i = idx.getAndIncrement();
            return i < chars.length ? chars[i] : -1;
        };
    }

    private static IntReader supplyString(String s) {
        int[] cp = s.codePoints().toArray();
        AtomicInteger idx = new AtomicInteger();
        return () -> {
            int i = idx.getAndIncrement();
            return i < cp.length ? cp[i] : -1;
        };
    }
}
