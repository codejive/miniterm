package org.codejive.miniterm.termcap;

import java.util.HashMap;
import java.util.Map;

/**
 * Test helper that drives the package-private detection layers of {@link TermCaps} with fully
 * controlled inputs, without touching real environment variables.
 */
final class TermCapsTestHelper {

    private TermCapsTestHelper() {}

    /** Apply only Layer 1 (TERM baseline). */
    static TermCaps detectTerm(String term) {
        TermCaps.Builder b = TermCaps.builder();
        TermCaps.applyTerm(b, term);
        return b.build();
    }

    /**
     * Apply Layer 1 + Layer 2 with injected environment variable values.
     *
     * @param term value of {@code $TERM}
     * @param colorterm value of {@code $COLORTERM} (may be null)
     * @param termProgram value of {@code $TERM_PROGRAM} (may be null)
     * @param vteVersion value of {@code $VTE_VERSION} (may be null)
     * @param tmux value of {@code $TMUX} (may be null; any non-null value means "inside tmux")
     */
    static TermCaps detectWithEnv(
            String term, String colorterm, String termProgram, String vteVersion, String tmux) {
        Map<String, String> env = new HashMap<String, String>();
        if (colorterm != null) env.put("COLORTERM", colorterm);
        if (termProgram != null) env.put("TERM_PROGRAM", termProgram);
        if (vteVersion != null) env.put("VTE_VERSION", vteVersion);
        if (tmux != null) env.put("TMUX", tmux);

        TermCaps.Builder b = TermCaps.builder();
        TermCaps.applyTerm(b, term);
        TermCaps.applyEnvVars(b, env::get);
        // Skip terminfo (Layer 3) — not available in test environment
        return b.build();
    }
}
