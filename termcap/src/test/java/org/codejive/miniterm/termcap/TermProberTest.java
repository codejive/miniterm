package org.codejive.miniterm.termcap;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import org.junit.jupiter.api.Test;

class TermProberTest {

    // ── sendDA1Query / sendDA2Query ───────────────────────────────────────

    @Test
    void sendDA1Query_writesCorrectSequence() throws IOException {
        StringBuilder out = new StringBuilder();
        TermProber.sendDA1Query(out);
        assertThat(out.toString()).isEqualTo("\033[c");
    }

    @Test
    void sendDA2Query_writesCorrectSequence() throws IOException {
        StringBuilder out = new StringBuilder();
        TermProber.sendDA2Query(out);
        assertThat(out.toString()).isEqualTo("\033[>c");
    }

    // ── isDA1QueryResult ──────────────────────────────────────────────────

    @Test
    void isDA1QueryResult_validResponse() {
        // ESC [ ? 62 ; 22 c  (typical xterm DA1)
        assertThat(TermProber.isDA1QueryResult("\033[?62;22c")).isTrue();
    }

    @Test
    void isDA1QueryResult_emptyParams() {
        assertThat(TermProber.isDA1QueryResult("\033[?c")).isTrue();
    }

    @Test
    void isDA1QueryResult_da2Response_returnsFalse() {
        // DA2 starts with ESC [ >
        assertThat(TermProber.isDA1QueryResult("\033[>0;279;0c")).isFalse();
    }

    @Test
    void isDA1QueryResult_null_returnsFalse() {
        assertThat(TermProber.isDA1QueryResult(null)).isFalse();
    }

    @Test
    void isDA1QueryResult_wrongTerminator_returnsFalse() {
        assertThat(TermProber.isDA1QueryResult("\033[?62;22R")).isFalse();
    }

    // ── isDA2QueryResult ──────────────────────────────────────────────────

    @Test
    void isDA2QueryResult_validResponse() {
        // ESC [ > 0 ; 279 ; 0 c  (xterm DA2)
        assertThat(TermProber.isDA2QueryResult("\033[>0;279;0c")).isTrue();
    }

    @Test
    void isDA2QueryResult_kittyResponse() {
        // Kitty reports type 1
        assertThat(TermProber.isDA2QueryResult("\033[>1;4000;0c")).isTrue();
    }

    @Test
    void isDA2QueryResult_da1Response_returnsFalse() {
        assertThat(TermProber.isDA2QueryResult("\033[?62;22c")).isFalse();
    }

    @Test
    void isDA2QueryResult_null_returnsFalse() {
        assertThat(TermProber.isDA2QueryResult(null)).isFalse();
    }

    // ── applyDA1 ──────────────────────────────────────────────────────────

    @Test
    void applyDA1_colorParam_setsAtLeast8Colors() {
        TermCaps.Builder b = TermCaps.builder();
        boolean applied = TermProber.applyDA1("\033[?22c", b);
        assertThat(applied).isTrue();
        assertThat(b.colors()).isGreaterThanOrEqualTo(8);
    }

    @Test
    void applyDA1_256colorParam_setsAtLeast256Colors() {
        TermCaps.Builder b = TermCaps.builder();
        boolean applied = TermProber.applyDA1("\033[?29c", b);
        assertThat(applied).isTrue();
        assertThat(b.colors()).isGreaterThanOrEqualTo(256);
    }

    @Test
    void applyDA1_unknownParams_returnsTrueWithoutChange() {
        TermCaps.Builder b = TermCaps.builder();
        b.colors(0);
        boolean applied = TermProber.applyDA1("\033[?1;2c", b);
        assertThat(applied).isTrue();
        assertThat(b.colors()).isEqualTo(0);
    }

    @Test
    void applyDA1_notDA1_returnsFalse() {
        TermCaps.Builder b = TermCaps.builder();
        assertThat(TermProber.applyDA1("\033[>0;279;0c", b)).isFalse();
        assertThat(TermProber.applyDA1(null, b)).isFalse();
    }

    @Test
    void applyDA1_doesNotDowngradeHigherColorCount() {
        TermCaps.Builder b = TermCaps.builder();
        b.colors(256);
        // DA1 param 22 would only set to 8 if current < 8
        TermProber.applyDA1("\033[?22c", b);
        assertThat(b.colors()).isEqualTo(256);
    }

    // ── applyDA2 ──────────────────────────────────────────────────────────

    @Test
    void applyDA2_xtermType_setsBaselineCaps() {
        TermCaps.Builder b = TermCaps.builder();
        boolean applied = TermProber.applyDA2("\033[>0;300;0c", b);
        assertThat(applied).isTrue();
        assertThat(b.colors()).isGreaterThanOrEqualTo(256);
        assertThat(b.build().altScreen()).isTrue();
        assertThat(b.build().mouse()).isTrue();
    }

    @Test
    void applyDA2_kittyType_setsFullCaps() {
        TermCaps.Builder b = TermCaps.builder();
        boolean applied = TermProber.applyDA2("\033[>1;4000;0c", b);
        assertThat(applied).isTrue();
        TermCaps caps = b.build();
        assertThat(caps.colors()).isEqualTo(16_777_216);
        assertThat(caps.unicode()).isTrue();
        assertThat(caps.hyperlinks()).isTrue();
    }

    @Test
    void applyDA2_vteType_setsVteCaps() {
        TermCaps.Builder b = TermCaps.builder();
        boolean applied = TermProber.applyDA2("\033[>65;5402;1c", b);
        assertThat(applied).isTrue();
        TermCaps caps = b.build();
        assertThat(caps.colors()).isGreaterThanOrEqualTo(256);
        assertThat(caps.bracketedPaste()).isTrue();
        assertThat(caps.hyperlinks()).isTrue();
    }

    @Test
    void applyDA2_notDA2_returnsFalse() {
        TermCaps.Builder b = TermCaps.builder();
        assertThat(TermProber.applyDA2("\033[?62;22c", b)).isFalse();
        assertThat(TermProber.applyDA2(null, b)).isFalse();
    }
}
