package org.codejive.miniterm.termcap;

/**
 * Thin wrapper that exposes the package-private {@link TerminfoReader#readMaxColors(String, String,
 * String)} overload for tests.
 */
final class TerminfoReaderTestHelper {

    private TerminfoReaderTestHelper() {}

    static int readMaxColors(String term, String terminfo, String terminfoDirs) {
        return TerminfoReader.readMaxColors(term, terminfo, terminfoDirs);
    }
}
