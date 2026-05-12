package org.codejive.miniterm.termcap;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class TermCapsTest {

    // ── builder defaults ──────────────────────────────────────────────────────

    @Test
    void builderDefaultsAreAllOff() {
        TermCaps caps = TermCaps.builder().build();
        assertThat(caps.colors()).isZero();
        assertThat(caps.altScreen()).isFalse();
        assertThat(caps.mouse()).isFalse();
        assertThat(caps.bracketedPaste()).isFalse();
        assertThat(caps.focusTracking()).isFalse();
        assertThat(caps.synchronizedOutput()).isFalse();
        assertThat(caps.hyperlinks()).isFalse();
        assertThat(caps.settableTitle()).isFalse();
        assertThat(caps.unicode()).isFalse();
        assertThat(caps.italic()).isFalse();
        assertThat(caps.strikethrough()).isFalse();
        assertThat(caps.overline()).isFalse();
    }

    @Test
    void builderSetsEachCapability() {
        TermCaps caps =
                TermCaps.builder()
                        .colors(256)
                        .altScreen(true)
                        .mouse(true)
                        .bracketedPaste(true)
                        .focusTracking(true)
                        .synchronizedOutput(true)
                        .hyperlinks(true)
                        .settableTitle(true)
                        .unicode(true)
                        .italic(true)
                        .strikethrough(true)
                        .overline(true)
                        .build();
        assertThat(caps.colors()).isEqualTo(256);
        assertThat(caps.altScreen()).isTrue();
        assertThat(caps.mouse()).isTrue();
        assertThat(caps.bracketedPaste()).isTrue();
        assertThat(caps.focusTracking()).isTrue();
        assertThat(caps.synchronizedOutput()).isTrue();
        assertThat(caps.hyperlinks()).isTrue();
        assertThat(caps.settableTitle()).isTrue();
        assertThat(caps.unicode()).isTrue();
        assertThat(caps.italic()).isTrue();
        assertThat(caps.strikethrough()).isTrue();
        assertThat(caps.overline()).isTrue();
    }

    @Test
    void builderFromBaseCopiesAllFields() {
        TermCaps base = TermCaps.builder().colors(256).altScreen(true).mouse(true).build();
        TermCaps copy = TermCaps.builder(base).build();
        assertThat(copy.colors()).isEqualTo(256);
        assertThat(copy.altScreen()).isTrue();
        assertThat(copy.mouse()).isTrue();
    }

    @Test
    void builderFromBaseAllowsOverride() {
        TermCaps base = TermCaps.builder().colors(256).mouse(true).build();
        TermCaps override = TermCaps.builder(base).colors(0).mouse(false).build();
        assertThat(override.colors()).isZero();
        assertThat(override.mouse()).isFalse();
        // unrelated field preserved
        assertThat(override.altScreen()).isFalse();
    }

    // ── Layer 1: TERM env var ─────────────────────────────────────────────────
    // We test applyTerm via the package-visible detect(term) overload that is
    // absent from the public API, so we call the internal helper indirectly
    // through a minimal builder-wrapper.

    @Test
    void termDumbGivesNoCapabilities() {
        TermCaps caps = detectTerm("dumb");
        assertThat(caps.colors()).isZero();
        assertThat(caps.altScreen()).isFalse();
        assertThat(caps.mouse()).isFalse();
    }

    @Test
    void termVt100GivesNoCapabilities() {
        TermCaps caps = detectTerm("vt100");
        assertThat(caps.colors()).isZero();
    }

    @Test
    void termAnsiGives8Colors() {
        TermCaps caps = detectTerm("ansi");
        assertThat(caps.colors()).isEqualTo(8);
        assertThat(caps.altScreen()).isFalse();
    }

    @Test
    void termXtermGivesBaseline() {
        TermCaps caps = detectTerm("xterm");
        assertThat(caps.colors()).isEqualTo(8);
        assertThat(caps.altScreen()).isTrue();
        assertThat(caps.mouse()).isTrue();
        assertThat(caps.settableTitle()).isTrue();
        assertThat(caps.unicode()).isTrue();
    }

    @Test
    void termXterm256colorGives256Colors() {
        TermCaps caps = detectTerm("xterm-256color");
        assertThat(caps.colors()).isEqualTo(256);
        assertThat(caps.bracketedPaste()).isTrue();
        assertThat(caps.italic()).isTrue();
        assertThat(caps.strikethrough()).isTrue();
    }

    @Test
    void termXtermDirectGivesTruecolor() {
        TermCaps caps = detectTerm("xterm-direct");
        assertThat(caps.colors()).isEqualTo(16_777_216);
        assertThat(caps.overline()).isTrue();
    }

    @Test
    void termScreenGivesAltScreen() {
        TermCaps caps = detectTerm("screen");
        assertThat(caps.altScreen()).isTrue();
        assertThat(caps.colors()).isEqualTo(8);
    }

    @Test
    void termScreen256colorGives256Colors() {
        TermCaps caps = detectTerm("screen-256color");
        assertThat(caps.colors()).isEqualTo(256);
        assertThat(caps.bracketedPaste()).isTrue();
    }

    @Test
    void termTmux256colorGives256Colors() {
        TermCaps caps = detectTerm("tmux-256color");
        assertThat(caps.colors()).isEqualTo(256);
    }

    @Test
    void unknownTermSuffix256colorUpgradesColors() {
        TermCaps caps = detectTerm("someterm-256color");
        assertThat(caps.colors()).isEqualTo(256);
    }

    @Test
    void unknownTermSuffixDirectUpgradesTruecolor() {
        TermCaps caps = detectTerm("someterm-direct");
        assertThat(caps.colors()).isEqualTo(16_777_216);
    }

    // ── Layer 2: COLORTERM env var ────────────────────────────────────────────

    @Test
    void colortermTruecolorUpgradesToTruecolor() {
        TermCaps caps = detectWithEnv("xterm-256color", "truecolor", null, null, null);
        assertThat(caps.colors()).isEqualTo(16_777_216);
    }

    @Test
    void colorterm24bitUpgradesToTruecolor() {
        TermCaps caps = detectWithEnv("xterm-256color", "24bit", null, null, null);
        assertThat(caps.colors()).isEqualTo(16_777_216);
    }

    @Test
    void termProgramWezTermGivesFullCaps() {
        TermCaps caps = detectWithEnv("xterm-256color", null, "WezTerm", null, null);
        assertThat(caps.colors()).isEqualTo(16_777_216);
        assertThat(caps.synchronizedOutput()).isTrue();
        assertThat(caps.focusTracking()).isTrue();
        assertThat(caps.hyperlinks()).isTrue();
        assertThat(caps.overline()).isTrue();
    }

    @Test
    void termProgramITermGivesFullCaps() {
        TermCaps caps = detectWithEnv("xterm-256color", null, "iTerm.app", null, null);
        assertThat(caps.colors()).isEqualTo(16_777_216);
        assertThat(caps.hyperlinks()).isTrue();
        assertThat(caps.overline()).isTrue();
    }

    @Test
    void termProgramKittyGivesFullCaps() {
        TermCaps caps = detectWithEnv("xterm-256color", null, "kitty", null, null);
        assertThat(caps.colors()).isEqualTo(16_777_216);
        assertThat(caps.focusTracking()).isTrue();
    }

    @Test
    void termProgramAppleTerminalGives256Colors() {
        TermCaps caps = detectWithEnv("xterm-256color", null, "Apple_Terminal", null, null);
        assertThat(caps.colors()).isGreaterThanOrEqualTo(256);
        assertThat(caps.settableTitle()).isTrue();
    }

    @Test
    void vteVersionGivesAtLeast256Colors() {
        TermCaps caps = detectWithEnv("xterm", null, null, "5202", null);
        assertThat(caps.colors()).isGreaterThanOrEqualTo(256);
        assertThat(caps.bracketedPaste()).isTrue();
        assertThat(caps.hyperlinks()).isTrue();
    }

    @Test
    void tmuxEnvVarEnablesMouse() {
        // Inside tmux, TERM=screen so mouse is off by default; TMUX env should enable it
        TermCaps caps = detectWithEnv("screen", null, null, null, "set-of-values");
        assertThat(caps.mouse()).isTrue();
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    /**
     * Applies only Layer 1 (TERM) by directly driving the internal builder logic. We do this via
     * the package-visible {@code TermCapsTestHelper}, which calls the same private static methods.
     */
    private static TermCaps detectTerm(String term) {
        return TermCapsTestHelper.detectTerm(term);
    }

    private static TermCaps detectWithEnv(
            String term, String colorterm, String termProgram, String vteVersion, String tmux) {
        return TermCapsTestHelper.detectWithEnv(term, colorterm, termProgram, vteVersion, tmux);
    }
}
