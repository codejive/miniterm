/*
 * Copyright TamboUI and miniterm Contributors
 * SPDX-License-Identifier: MIT
 */
package org.codejive.miniterm;

import java.io.IOException;
import java.nio.charset.Charset;
import java.util.function.Consumer;

/**
 * Platform-independent terminal operations interface.
 *
 * <p>This interface abstracts the low-level terminal operations that differ between Unix
 * (Linux/macOS) and Windows platforms.
 */
public interface Terminal extends AutoCloseable {

    /**
     * Enables raw mode on the terminal.
     *
     * <p>Raw mode disables line buffering, echo, and special character processing, allowing direct
     * character-by-character input.
     *
     * @throws IOException if raw mode cannot be enabled
     */
    void enableRawMode() throws IOException;

    /**
     * Disables raw mode and restores original terminal attributes.
     *
     * @throws IOException if raw mode cannot be disabled
     */
    void disableRawMode() throws IOException;

    /**
     * Gets the current terminal size.
     *
     * @return the terminal size
     * @throws IOException if the size cannot be determined
     */
    Size getSize() throws IOException;

    /**
     * Reads a single character from the terminal with timeout.
     *
     * @param timeoutMs timeout in milliseconds (-1 for infinite, 0 for non-blocking)
     * @return the character read, -1 for EOF, or -2 for timeout
     * @throws IOException if reading fails
     */
    int read(int timeoutMs) throws IOException;

    /**
     * Peeks at the next character without consuming it.
     *
     * @param timeoutMs timeout in milliseconds
     * @return the character peeked, -1 for EOF, or -2 for timeout
     * @throws IOException if reading fails
     */
    int peek(int timeoutMs) throws IOException;

    /**
     * Writes data to the terminal.
     *
     * @param data the data to write
     * @throws IOException if writing fails
     */
    void write(byte[] data) throws IOException;

    /**
     * Writes a portion of a byte array to the terminal.
     *
     * <p>This method allows writing from a reusable buffer without creating intermediate byte array
     * copies.
     *
     * @param buffer the byte array containing data
     * @param offset the start offset in the buffer
     * @param length the number of bytes to write
     * @throws IOException if writing fails
     */
    void write(byte[] buffer, int offset, int length) throws IOException;

    /**
     * Writes a string to the terminal.
     *
     * @param s the string to write
     * @throws IOException if writing fails
     */
    void write(String s) throws IOException;

    /**
     * Returns the charset used for terminal I/O.
     *
     * @return the terminal charset
     */
    Charset getCharset();

    /**
     * Checks if raw mode is currently enabled.
     *
     * @return true if raw mode is enabled
     */
    boolean isRawModeEnabled();

    /**
     * Registers a handler to be called when the terminal is resized.
     *
     * @param handler the handler to call on resize, or null to remove
     */
    void onResize(Consumer<Size> handler);

    /**
     * Closes the terminal and releases resources.
     *
     * @throws IOException if closing fails
     */
    @Override
    void close() throws IOException;

    /**
     * Factory method to create a Terminal for the current platform using FFM (Java 22+).
     *
     * <p>Strategy:
     *
     * <ol>
     *   <li>Detect OS (windows/unix)
     *   <li>Instantiate the FFM implementation: FfmWindowsTerminal or FfmUnixTerminal
     *   <li>Can be overridden via system properties:
     *       <ul>
     *         <li>{@code miniterm.terminal.type}: "windows" or "unix"
     *         <li>{@code miniterm.terminal.class}: specific fully-qualified class name
     *       </ul>
     * </ol>
     *
     * <p>For legacy (Java 8+) implementations, use the {@code miniterm} artifact instead.
     */
    public static Terminal create() throws IOException {
        String os = System.getProperty("os.name", "").toLowerCase();
        String ostype = os.contains("windows") ? "windows" : "unix";
        String term = System.getProperty("miniterm.terminal.type", ostype);
        String[] classNames =
                "windows".equals(term)
                        ? new String[] {"org.codejive.miniterm.windows.FfmWindowsTerminal"}
                        : new String[] {"org.codejive.miniterm.unix.FfmUnixTerminal"};
        String override = System.getProperty("miniterm.terminal.class");
        if (override != null) {
            classNames = new String[] {override};
        }
        Throwable last = null;
        for (String className : classNames) {
            try {
                return (Terminal) Class.forName(className).getDeclaredConstructor().newInstance();
            } catch (ReflectiveOperationException | Error e) {
                last = e;
            }
        }
        throw new IOException(
                "Failed to create terminal for platform: " + System.getProperty("os.name"), last);
    }

    public static class Size {

        public final int height;
        public final int width;

        public Size(int width, int height) {
            this.width = width;
            this.height = height;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) return true;
            if (o == null || getClass() != o.getClass()) return false;
            Size size = (Size) o;
            if (height != size.height) return false;
            return width == size.width;
        }

        @Override
        public int hashCode() {
            return 31 * height + width;
        }

        @Override
        public String toString() {
            return width + "x" + height;
        }
    }
}
