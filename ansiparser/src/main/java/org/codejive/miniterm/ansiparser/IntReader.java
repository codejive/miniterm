package org.codejive.miniterm.ansiparser;

import java.io.IOException;

/**
 * A functional interface for reading a single integer value, allowing checked {@link IOException}
 * to be thrown.
 *
 * <p>This is equivalent to {@link java.util.function.IntSupplier} but permits I/O exceptions,
 * making it suitable for use with sources like terminal readers:
 *
 * <pre>{@code
 * new AnsiReader(() -> terminal.read(1000))
 * }</pre>
 *
 * <p>Any {@link java.util.function.IntSupplier} is compatible via method reference:
 *
 * <pre>{@code
 * IntSupplier supplier = ...;
 * new AnsiReader(supplier::getAsInt)
 * }</pre>
 */
@FunctionalInterface
public interface IntReader {

    /**
     * Reads and returns a single integer value.
     *
     * @return the integer value; negative values conventionally signal EOF or timeout
     * @throws IOException if an I/O error occurs
     */
    int read() throws IOException;
}
