package org.codejive.miniterm.ansiparser;

import java.io.IOException;

/**
 * Stateful ANSI token reader backed by an {@link IntReader}.
 *
 * <p>Create an instance with a character source and call {@link #read(Appendable)} repeatedly to
 * accumulate tokens into any {@link Appendable}.
 */
public final class AnsiReader {

    private final IntReader source;

    /**
     * Creates a new {@code AnsiReader} that reads from {@code source}.
     *
     * @param source raw character reader; negative values signal EOF or timeout
     */
    public AnsiReader(IntReader source) {
        this.source = source;
    }

    /**
     * Reads the next token from the supplier and returns it as a {@code String}.
     *
     * @return the token, an empty string to indicate a timeout or {@code null} for EOF before any
     *     character was read
     * @throws IOException if {@code source} throws an {@code IOException}
     */
    public String read() throws IOException {
        return AnsiParser.parse(source);
    }

    /**
     * Reads the next token from the supplier and appends it to {@code appendable}.
     *
     * <p>Delegates to {@link AnsiParser#parse(Appendable, IntReader, int)} after consuming the
     * first character. If the very first value returned by the supplier is negative, this method
     * returns that value immediately without touching {@code appendable}.
     *
     * @param appendable target to which the token is appended
     * @return {@code 0} on success, or the negative value returned by the supplier if it signalled
     *     EOF/timeout before any character was read
     * @throws IOException if {@code appendable} throws an {@code IOException}
     */
    public int read(Appendable appendable) throws IOException {
        return AnsiParser.parse(appendable, source, AnsiParser.MAX_SEQUENCE_LENGTH);
    }
}
