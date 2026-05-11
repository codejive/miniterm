/*
 * Copyright TamboUI and miniterm Contributors
 * SPDX-License-Identifier: MIT
 */
package org.codejive.miniterm;

import java.io.IOException;

/**
 * Platform-independent terminal operations interface.
 *
 * <p>This interface abstracts the low-level terminal operations that differ between Unix
 * (Linux/macOS) and Windows platforms.
 */
public interface Terminal extends TerminalBase {

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
}
