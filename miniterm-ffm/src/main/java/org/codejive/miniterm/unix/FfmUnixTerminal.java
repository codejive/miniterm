package org.codejive.miniterm.unix;

import java.io.IOException;
import java.lang.foreign.AddressLayout;
import java.lang.foreign.Arena;
import java.lang.foreign.FunctionDescriptor;
import java.lang.foreign.Linker;
import java.lang.foreign.MemoryLayout;
import java.lang.foreign.MemorySegment;
import java.lang.foreign.SymbolLookup;
import java.lang.foreign.ValueLayout;
import java.lang.invoke.MethodHandle;
import java.lang.invoke.VarHandle;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.charset.UnsupportedCharsetException;
import java.util.Locale;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.Consumer;
import org.codejive.miniterm.Terminal;

/**
 * Unix terminal operations using Panama FFI.
 *
 * <p>This class provides higher-level terminal operations built on top of the low-level libc
 * bindings in {@link LibC}.
 */
public final class FfmUnixTerminal implements Terminal {

    private static final VarHandle WS_ROW =
            LibC.WINSIZE_LAYOUT.varHandle(MemoryLayout.PathElement.groupElement("ws_row"));
    private static final VarHandle WS_COL =
            LibC.WINSIZE_LAYOUT.varHandle(MemoryLayout.PathElement.groupElement("ws_col"));

    private static final VarHandle POLLFD_FD =
            LibC.POLLFD_LAYOUT.varHandle(MemoryLayout.PathElement.groupElement("fd"));
    private static final VarHandle POLLFD_EVENTS =
            LibC.POLLFD_LAYOUT.varHandle(MemoryLayout.PathElement.groupElement("events"));
    private static final VarHandle POLLFD_REVENTS =
            LibC.POLLFD_LAYOUT.varHandle(MemoryLayout.PathElement.groupElement("revents"));

    private static final VarHandle TERMIOS_IFLAG =
            LibC.TERMIOS_LAYOUT.varHandle(MemoryLayout.PathElement.groupElement("c_iflag"));
    private static final VarHandle TERMIOS_OFLAG =
            LibC.TERMIOS_LAYOUT.varHandle(MemoryLayout.PathElement.groupElement("c_oflag"));
    private static final VarHandle TERMIOS_CFLAG =
            LibC.TERMIOS_LAYOUT.varHandle(MemoryLayout.PathElement.groupElement("c_cflag"));
    private static final VarHandle TERMIOS_LFLAG =
            LibC.TERMIOS_LAYOUT.varHandle(MemoryLayout.PathElement.groupElement("c_lflag"));

    private static final String DEV_TTY = "/dev/tty";

    // Offset to c_cc array in termios struct (platform-specific)
    private static final long TERMIOS_CC_OFFSET = PlatformConstants.TERMIOS_CC_OFFSET;

    // Environment variables to check for charset detection, in order of precedence
    private static final String[] LOCALE_ENV_VARS = {"LC_ALL", "LC_CTYPE", "LANG"};

    // Size of the reusable write buffer
    private static final int WRITE_BUFFER_SIZE = 8192;

    private final Arena arena;
    private final MemorySegment savedTermios;
    private final MemorySegment currentTermios;
    private final MemorySegment winsize;
    private final MemorySegment pollfd;
    private final MemorySegment readBuffer;
    private final MemorySegment writeBuffer;
    private final int ttyFd;
    private final Charset charset;

    private boolean rawModeEnabled;
    private int peekedChar = -2;
    private final ReentrantLock resizeLock = new ReentrantLock();
    private Consumer<Size> resizeHandler;
    private boolean resizePending;
    private MemorySegment previousSigaction; // Previous sigaction struct (for restoration)
    private Arena signalArena;

    /**
     * Creates a new Unix terminal instance.
     *
     * @throws IOException if the terminal cannot be initialized
     */
    public FfmUnixTerminal() throws IOException {
        // On macOS, use stdin directly for input (poll doesn't work well with /dev/tty)
        // On Linux, open /dev/tty to bypass any stdin/stdout redirection
        int fd;
        if (PlatformConstants.isMacOS()) {
            fd = LibC.STDIN_FILENO;
        } else {
            fd = LibC.open(DEV_TTY, LibC.O_RDWR);
            if (fd < 0) {
                throw new RuntimeException(
                        "Failed to open " + DEV_TTY + " (errno=" + LibC.getLastErrno() + ")");
            }
        }
        this.ttyFd = fd;
        this.charset = detectCharset();

        this.arena = Arena.ofShared();
        this.savedTermios = LibC.allocateTermios(arena);
        this.currentTermios = LibC.allocateTermios(arena);
        this.winsize = LibC.allocateWinsize(arena);
        this.pollfd = LibC.allocatePollfd(arena);
        this.readBuffer = arena.allocate(1);
        this.writeBuffer = arena.allocate(WRITE_BUFFER_SIZE);
        this.rawModeEnabled = false;

        // Save original terminal attributes
        int tcgetattrResult = LibC.tcgetattr(ttyFd, savedTermios);
        if (tcgetattrResult != 0) {
            LibC.close(ttyFd);
            arena.close();
            throw new RuntimeException("Failed to get terminal attributes");
        }

        // Copy to current
        MemorySegment.copy(savedTermios, 0, currentTermios, 0, LibC.TERMIOS_LAYOUT.byteSize());
    }

    /**
     * Detects the terminal charset from environment variables.
     *
     * <p>Checks LC_ALL, LC_CTYPE, and LANG in order of precedence. Falls back to UTF-8 if no
     * encoding is detected or if the detected encoding is not supported.
     *
     * @return the detected charset, or UTF-8 as default
     */
    private static Charset detectCharset() {
        // Check environment variables in order of precedence
        for (var envVar : LOCALE_ENV_VARS) {
            var value = System.getenv(envVar);
            if (value != null && !value.isEmpty()) {
                var detected = parseCharsetFromLocale(value);
                if (detected != null) {
                    return detected;
                }
            }
        }
        // Default to UTF-8
        return StandardCharsets.UTF_8;
    }

    /**
     * Parses a charset from a locale string like "en_US.UTF-8" or "C.UTF-8".
     *
     * @param locale the locale string
     * @return the parsed charset, or null if not found or not supported
     */
    private static Charset parseCharsetFromLocale(String locale) {
        var upper = locale.toUpperCase(Locale.ROOT);

        // Handle common UTF-8 patterns
        if (upper.contains("UTF-8") || upper.contains("UTF8")) {
            return StandardCharsets.UTF_8;
        }

        // Handle explicit charset after dot (e.g., "en_US.ISO-8859-1")
        var dotIndex = locale.indexOf('.');
        if (dotIndex >= 0 && dotIndex < locale.length() - 1) {
            var charsetPart = locale.substring(dotIndex + 1);
            // Remove any modifier after @ (e.g., "UTF-8@euro")
            var atIndex = charsetPart.indexOf('@');
            if (atIndex >= 0) {
                charsetPart = charsetPart.substring(0, atIndex);
            }
            try {
                return Charset.forName(charsetPart);
            } catch (UnsupportedCharsetException e) {
                // Fall through to default
            }
        }

        // "C" or "POSIX" locale typically means ASCII, but UTF-8 is safer for TUI
        if ("C".equals(locale) || "POSIX".equals(locale)) {
            return StandardCharsets.UTF_8;
        }

        return null;
    }

    /**
     * Enables raw mode on the terminal.
     *
     * <p>Raw mode disables line buffering, echo, and signal processing, allowing direct
     * character-by-character input.
     *
     * @throws IOException if raw mode cannot be enabled
     */
    public void enableRawMode() throws IOException {
        if (rawModeEnabled) {
            return;
        }

        // Re-read current attributes
        if (LibC.tcgetattr(ttyFd, currentTermios) != 0) {
            throw new RuntimeException("Failed to get terminal attributes");
        }

        // Get current flags
        var iflag = getTermiosFlag(TERMIOS_IFLAG);
        var oflag = getTermiosFlag(TERMIOS_OFLAG);
        var cflag = getTermiosFlag(TERMIOS_CFLAG);
        var lflag = getTermiosFlag(TERMIOS_LFLAG);

        // Disable various input processing
        iflag &= ~(LibC.BRKINT | LibC.ICRNL | LibC.INPCK | LibC.ISTRIP | LibC.IXON);

        // Disable output processing
        oflag &= ~(LibC.OPOST);

        // Set character size to 8 bits
        cflag |= LibC.CS8;

        // Disable echo, canonical mode, signals, and extended functions
        lflag &= ~(LibC.ECHO | LibC.ICANON | LibC.IEXTEN | LibC.ISIG);

        // Set the modified flags
        setTermiosFlag(TERMIOS_IFLAG, iflag);
        setTermiosFlag(TERMIOS_OFLAG, oflag);
        setTermiosFlag(TERMIOS_CFLAG, cflag);
        setTermiosFlag(TERMIOS_LFLAG, lflag);

        // Set VMIN and VTIME to 0 for non-blocking reads
        clearControlChar(currentTermios, LibC.VMIN);
        clearControlChar(currentTermios, LibC.VTIME);

        if (LibC.tcsetattr(ttyFd, LibC.TCSAFLUSH, currentTermios) != 0) {
            throw new RuntimeException("Failed to set terminal attributes");
        }

        rawModeEnabled = true;
    }

    /**
     * Disables raw mode and restores original terminal attributes.
     *
     * @throws IOException if raw mode cannot be disabled
     */
    public void disableRawMode() throws IOException {
        if (!rawModeEnabled) {
            return;
        }

        if (LibC.tcsetattr(ttyFd, LibC.TCSAFLUSH, savedTermios) != 0) {
            throw new RuntimeException("Failed to restore terminal attributes");
        }

        rawModeEnabled = false;
    }

    /**
     * Gets the current terminal size.
     *
     * @return the terminal size
     * @throws IOException if the size cannot be determined
     */
    public Size getSize() throws IOException {
        int ioctlResult = LibC.ioctl(ttyFd, LibC.TIOCGWINSZ, winsize);
        if (ioctlResult == 0) {
            var cols = Short.toUnsignedInt((short) WS_COL.get(winsize, 0L));
            var rows = Short.toUnsignedInt((short) WS_ROW.get(winsize, 0L));
            if (cols > 0 && rows > 0) {
                return new Size(cols, rows);
            }
        }
        throw new RuntimeException(
                "Failed to get terminal size (errno=" + LibC.getLastErrno() + ")");
    }

    /**
     * Reads a single character from the terminal with timeout.
     *
     * <p>This method also checks for and dispatches pending resize events, ensuring resize handlers
     * are called from the main event loop context rather than from signal handler context.
     *
     * @param timeoutMs timeout in milliseconds (-1 for infinite, 0 for non-blocking)
     * @return the character read, -1 for EOF, or -2 for timeout
     * @throws IOException if reading fails
     */
    public int read(int timeoutMs) throws IOException {
        // Check for pending resize events (set by signal handler)
        checkResizePending();

        // Return peeked character if available
        if (peekedChar != -2) {
            var c = peekedChar;
            peekedChar = -2;
            return c;
        }

        return readInternal(timeoutMs);
    }

    /**
     * Peeks at the next character without consuming it.
     *
     * @param timeoutMs timeout in milliseconds
     * @return the character peeked, -1 for EOF, or -2 for timeout
     * @throws IOException if reading fails
     */
    public int peek(int timeoutMs) throws IOException {
        if (peekedChar != -2) {
            return peekedChar;
        }

        peekedChar = readInternal(timeoutMs);
        return peekedChar;
    }

    /**
     * Writes data to the terminal.
     *
     * @param data the data to write
     * @throws IOException if writing fails
     */
    public void write(byte[] data) throws IOException {
        write(data, 0, data.length);
    }

    /**
     * Writes a portion of a byte array to the terminal.
     *
     * <p>This method uses a reusable buffer to avoid per-call memory allocation. For large writes
     * exceeding the buffer size, data is written in chunks.
     *
     * @param buffer the byte array containing data
     * @param offset the start offset in the buffer
     * @param length the number of bytes to write
     * @throws IOException if writing fails
     */
    public void write(byte[] buffer, int offset, int length) throws IOException {
        if (length == 0) {
            return;
        }

        int remaining = length;
        int currentOffset = offset;

        while (remaining > 0) {
            int chunkSize = Math.min(remaining, WRITE_BUFFER_SIZE);
            MemorySegment.copy(
                    buffer, currentOffset, writeBuffer, ValueLayout.JAVA_BYTE, 0, chunkSize);

            long written = 0;
            while (written < chunkSize) {
                long result = LibC.write(ttyFd, writeBuffer.asSlice(written), chunkSize - written);
                if (result < 0) {
                    throw new RuntimeException("Write failed (errno=" + LibC.getLastErrno() + ")");
                }
                written += result;
            }

            currentOffset += chunkSize;
            remaining -= chunkSize;
        }
    }

    /**
     * Writes a string to the terminal.
     *
     * @param s the string to write
     * @throws IOException if writing fails
     */
    public void write(String s) throws IOException {
        write(s.getBytes(charset));
    }

    /**
     * Returns the charset used for terminal I/O.
     *
     * @return the terminal charset
     */
    public Charset getCharset() {
        return charset;
    }

    /**
     * Checks if raw mode is currently enabled.
     *
     * @return true if raw mode is enabled
     */
    public boolean isRawModeEnabled() {
        return rawModeEnabled;
    }

    /**
     * Registers a handler to be called when the terminal is resized.
     *
     * <p>On Unix systems, this installs a SIGWINCH signal handler using Panama FFI. The signal
     * handler sets a flag which is checked from the main event loop (via {@link #read(int)}),
     * ensuring the handler is called from a safe context.
     *
     * <p>Only one handler can be registered at a time; subsequent calls will replace the previous
     * handler.
     *
     * @param handler the handler to call on resize, or null to remove
     */
    public void onResize(Consumer<Size> handler) {
        resizeLock.lock();
        try {
            this.resizeHandler = handler;
            if (handler != null && signalArena == null) {
                // Create a dedicated arena for the signal handler that lives as long as needed
                signalArena = Arena.ofShared();

                // Create the upcall stub for our signal handler
                // IMPORTANT: We only set a flag here, NOT call the handler directly.
                // Calling complex code from signal context can cause crashes.
                var signalHandlerStub =
                        LibC.createSignalHandler(
                                signalArena,
                                signum -> {
                                    resizeLock.lock();
                                    try {
                                        resizePending = true;
                                    } finally {
                                        resizeLock.unlock();
                                    }
                                });

                // Use sigaction() instead of signal() for better reliability on macOS
                // Allocate sigaction structs
                MemorySegment newSigaction = LibC.allocateSigaction(signalArena);
                MemorySegment oldSigaction = LibC.allocateSigaction(signalArena);

                // Set up new sigaction: handler pointer, NULL trampoline, empty mask, SA_RESTART
                // flag
                LibC.setSigactionHandler(newSigaction, signalHandlerStub);
                LibC.setSigactionTramp(
                        newSigaction, MemorySegment.NULL); // NULL for simple handlers
                LibC.setSigactionMask(newSigaction, 0);
                LibC.setSigactionFlags(newSigaction, LibC.SA_RESTART);

                // Install the signal handler and save the previous one
                int sigactionResult = LibC.sigaction(LibC.SIGWINCH, newSigaction, oldSigaction);

                if (sigactionResult != 0) {
                    throw new RuntimeException(
                            "Failed to install signal handler for Unix terminal (errno="
                                    + LibC.getLastErrno()
                                    + ")");
                }

                // Save the old sigaction for restoration on close
                previousSigaction = oldSigaction;
            }
        } finally {
            resizeLock.unlock();
        }
    }

    /**
     * Checks if a resize event is pending and dispatches it.
     *
     * <p>This should be called from the main event loop, not from signal context.
     */
    private void checkResizePending() throws IOException {
        Consumer<Size> handler = null;
        resizeLock.lock();
        try {
            if (resizePending) {
                resizePending = false;
                handler = resizeHandler;
            }
        } finally {
            resizeLock.unlock();
        }
        // Call handler outside of lock to avoid deadlock
        if (handler != null) {
            handler.accept(getSize());
        }
    }

    @Override
    public void close() throws IOException {
        try {
            if (rawModeEnabled) {
                disableRawMode();
            }
        } finally {
            // Restore previous SIGWINCH handler using sigaction
            if (previousSigaction != null) {
                LibC.sigaction(LibC.SIGWINCH, previousSigaction, MemorySegment.NULL);
                previousSigaction = null;
            }
            resizeHandler = null;

            // Close the signal arena (this invalidates the upcall stub)
            if (signalArena != null) {
                signalArena.close();
                signalArena = null;
            }

            // Don't close stdin on macOS
            if (!PlatformConstants.isMacOS()) {
                LibC.close(ttyFd);
            }
            arena.close();
        }
    }

    private int readInternal(int timeoutMs) throws IOException {
        // Set up poll
        POLLFD_FD.set(pollfd, 0L, ttyFd);
        POLLFD_EVENTS.set(pollfd, 0L, LibC.POLLIN);
        POLLFD_REVENTS.set(pollfd, 0L, (short) 0);

        int result;
        // Retry poll if interrupted by signal (EINTR)
        while (true) {
            result = LibC.poll(pollfd, 1, timeoutMs);
            if (result >= 0) {
                break;
            }
            // Check if interrupted by signal - if so, check for pending resize and retry
            if (LibC.getLastErrno() == LibC.EINTR) {
                checkResizePending();
                continue;
            }
            throw new RuntimeException("poll() failed (errno=" + LibC.getLastErrno() + ")");
        }

        if (result == 0) {
            return -2; // Timeout
        }

        var revents = (short) POLLFD_REVENTS.get(pollfd, 0L);

        if ((revents & LibC.POLLHUP) != 0 || (revents & LibC.POLLERR) != 0) {
            return -1; // EOF or error
        }

        if ((revents & LibC.POLLIN) != 0) {
            long bytesRead = LibC.read(ttyFd, readBuffer, 1);
            if (bytesRead <= 0) {
                return -1; // EOF
            }
            return Byte.toUnsignedInt(readBuffer.get(ValueLayout.JAVA_BYTE, 0));
        }

        return -2; // No data available
    }

    private void clearControlChar(MemorySegment termios, int index) {
        termios.set(ValueLayout.JAVA_BYTE, TERMIOS_CC_OFFSET + index, (byte) 0);
    }

    private long getTermiosFlag(VarHandle handle) {
        if (PlatformConstants.isMacOS()) {
            return (long) handle.get(currentTermios, 0L);
        } else {
            return Integer.toUnsignedLong((int) handle.get(currentTermios, 0L));
        }
    }

    private void setTermiosFlag(VarHandle handle, long value) {
        if (PlatformConstants.isMacOS()) {
            handle.set(currentTermios, 0L, value);
        } else {
            handle.set(currentTermios, 0L, (int) value);
        }
    }
}

/**
 * Panama FFI bindings to libc functions for terminal operations.
 *
 * <p>This class provides low-level access to Unix terminal control functions including termios
 * manipulation, terminal size queries, and non-blocking I/O.
 */
final class LibC {

    /** Standard input file descriptor. */
    static final int STDIN_FILENO = 0;

    /** Open flag for read/write access. */
    static final int O_RDWR = 2;

    /** Apply termios changes after all output has been transmitted, discarding pending input. */
    static final int TCSAFLUSH = 2;

    /** ioctl request code to get terminal window size. */
    static final long TIOCGWINSZ = PlatformConstants.TIOCGWINSZ;

    /** Local flag: enable echo of input characters. */
    static final int ECHO = PlatformConstants.ECHO;

    /** Local flag: enable canonical (line-by-line) input mode. */
    static final int ICANON = PlatformConstants.ICANON;

    /** Local flag: enable signal generation for INTR, QUIT, SUSP characters. */
    static final int ISIG = PlatformConstants.ISIG;

    /** Local flag: enable implementation-defined input processing. */
    static final int IEXTEN = PlatformConstants.IEXTEN;

    /** Input flag: enable XON/XOFF flow control on output. */
    static final int IXON = PlatformConstants.IXON;

    /** Input flag: translate carriage return to newline on input. */
    static final int ICRNL = PlatformConstants.ICRNL;

    /** Input flag: signal interrupt on break. */
    static final int BRKINT = PlatformConstants.BRKINT;

    /** Input flag: enable input parity check. */
    static final int INPCK = PlatformConstants.INPCK;

    /** Input flag: strip eighth bit off input characters. */
    static final int ISTRIP = PlatformConstants.ISTRIP;

    /** Output flag: enable implementation-defined output processing. */
    static final int OPOST = PlatformConstants.OPOST;

    /** Control flag: set character size to 8 bits. */
    static final int CS8 = PlatformConstants.CS8;

    /** Control character index for minimum number of bytes for non-canonical read. */
    static final int VMIN = PlatformConstants.VMIN;

    /** Control character index for timeout in deciseconds for non-canonical read. */
    static final int VTIME = PlatformConstants.VTIME;

    /** Poll event: there is data to read. */
    static final short POLLIN = 0x0001;

    /** Poll event: error condition on the file descriptor. */
    static final short POLLERR = 0x0008;

    /** Poll event: hang up on the file descriptor. */
    static final short POLLHUP = 0x0010;

    /** Signal number for terminal window size change. */
    static final int SIGWINCH = PlatformConstants.SIGWINCH;

    private static final Linker LINKER = Linker.nativeLinker();
    private static final SymbolLookup LIBC =
            SymbolLookup.loaderLookup().or(Linker.nativeLinker().defaultLookup());

    // Capture errno state location for error reporting
    private static final Linker.Option CAPTURE_ERRNO = Linker.Option.captureCallState("errno");
    private static final MemoryLayout CALL_STATE_LAYOUT = Linker.Option.captureStateLayout();
    private static final VarHandle ERRNO_HANDLE =
            CALL_STATE_LAYOUT.varHandle(MemoryLayout.PathElement.groupElement("errno"));

    // Thread-local call state segment to avoid per-call Arena allocation
    private static final ThreadLocal<MemorySegment> CALL_STATE_SEGMENT =
            ThreadLocal.withInitial(() -> Arena.global().allocate(CALL_STATE_LAYOUT));

    // Canonical layouts (matching jextract pattern)
    private static final ValueLayout.OfInt C_INT =
            (ValueLayout.OfInt) Linker.nativeLinker().canonicalLayouts().get("int");
    private static final ValueLayout.OfLong C_LONG =
            (ValueLayout.OfLong) Linker.nativeLinker().canonicalLayouts().get("long");
    private static final AddressLayout C_POINTER =
            ((AddressLayout) Linker.nativeLinker().canonicalLayouts().get("void*"))
                    .withTargetLayout(
                            MemoryLayout.sequenceLayout(
                                    java.lang.Long.MAX_VALUE,
                                    (ValueLayout.OfByte)
                                            Linker.nativeLinker().canonicalLayouts().get("char")));

    // Function descriptor for signal handlers: void handler(int signum)
    // Use canonical C_INT layout (same as jextract) for proper platform-specific handling
    private static final FunctionDescriptor SIGNAL_HANDLER_DESC = FunctionDescriptor.ofVoid(C_INT);

    // Method handles for libc functions
    private static final MethodHandle TCGETATTR;
    private static final MethodHandle TCSETATTR;
    private static final MethodHandle IOCTL;
    private static final MethodHandle READ;
    private static final MethodHandle WRITE;
    private static final MethodHandle POLL_MACOS; // nfds_t is unsigned int on macOS
    private static final MethodHandle POLL_LINUX; // nfds_t is unsigned long on Linux
    private static final MethodHandle OPEN;
    private static final MethodHandle CLOSE;
    private static final MethodHandle SIGACTION;

    /** Errno value indicating an interrupted system call. */
    static final int EINTR = 4;

    /** Sigaction flag: restart interrupted system calls. */
    static final int SA_RESTART = 2;

    static {
        try {
            TCGETATTR =
                    LINKER.downcallHandle(
                            LIBC.find("tcgetattr").orElseThrow(),
                            FunctionDescriptor.of(
                                    ValueLayout.JAVA_INT,
                                    ValueLayout.JAVA_INT,
                                    ValueLayout.ADDRESS));

            TCSETATTR =
                    LINKER.downcallHandle(
                            LIBC.find("tcsetattr").orElseThrow(),
                            FunctionDescriptor.of(
                                    ValueLayout.JAVA_INT,
                                    ValueLayout.JAVA_INT,
                                    ValueLayout.JAVA_INT,
                                    ValueLayout.ADDRESS));

            // ioctl(int fd, unsigned long request, ...) - variadic function
            // Use canonical layouts matching jextract: C_INT return, C_INT fd, C_LONG request
            // For TIOCGWINSZ, the variadic arg is a pointer to winsize struct
            // Use firstVariadicArg option to mark where variadic args start (after request)
            FunctionDescriptor ioctlDesc = FunctionDescriptor.of(C_INT, C_INT, C_LONG, C_POINTER);
            Linker.Option firstVariadicArg =
                    Linker.Option.firstVariadicArg(
                            2); // variadic args start at index 2 (after fd and request)
            IOCTL =
                    LINKER.downcallHandle(
                            LIBC.find("ioctl").orElseThrow(),
                            ioctlDesc,
                            firstVariadicArg,
                            CAPTURE_ERRNO);

            READ =
                    LINKER.downcallHandle(
                            LIBC.find("read").orElseThrow(),
                            FunctionDescriptor.of(
                                    ValueLayout.JAVA_LONG,
                                    ValueLayout.JAVA_INT,
                                    ValueLayout.ADDRESS,
                                    ValueLayout.JAVA_LONG));

            WRITE =
                    LINKER.downcallHandle(
                            LIBC.find("write").orElseThrow(),
                            FunctionDescriptor.of(
                                    ValueLayout.JAVA_LONG,
                                    ValueLayout.JAVA_INT,
                                    ValueLayout.ADDRESS,
                                    ValueLayout.JAVA_LONG),
                            CAPTURE_ERRNO);

            // nfds_t is unsigned int (4 bytes) on macOS, unsigned long (8 bytes) on Linux
            // We need separate handles because invokeExact requires exact type matching
            POLL_MACOS =
                    LINKER.downcallHandle(
                            LIBC.find("poll").orElseThrow(),
                            FunctionDescriptor.of(
                                    ValueLayout.JAVA_INT,
                                    ValueLayout.ADDRESS,
                                    ValueLayout.JAVA_INT,
                                    ValueLayout.JAVA_INT),
                            CAPTURE_ERRNO);
            POLL_LINUX =
                    LINKER.downcallHandle(
                            LIBC.find("poll").orElseThrow(),
                            FunctionDescriptor.of(
                                    ValueLayout.JAVA_INT,
                                    ValueLayout.ADDRESS,
                                    ValueLayout.JAVA_LONG,
                                    ValueLayout.JAVA_INT),
                            CAPTURE_ERRNO);

            OPEN =
                    LINKER.downcallHandle(
                            LIBC.find("open").orElseThrow(),
                            FunctionDescriptor.of(
                                    ValueLayout.JAVA_INT,
                                    ValueLayout.ADDRESS,
                                    ValueLayout.JAVA_INT),
                            CAPTURE_ERRNO);

            CLOSE =
                    LINKER.downcallHandle(
                            LIBC.find("close").orElseThrow(),
                            FunctionDescriptor.of(ValueLayout.JAVA_INT, ValueLayout.JAVA_INT));

            // sigaction(int signum, const struct sigaction *act, struct sigaction *oldact)
            // Returns 0 on success, -1 on error
            SIGACTION =
                    LINKER.downcallHandle(
                            LIBC.find("sigaction").orElseThrow(),
                            FunctionDescriptor.of(C_INT, C_INT, C_POINTER, C_POINTER));
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    private LibC() {}

    static int tcgetattr(int fd, MemorySegment termios) {
        try {
            return (int) TCGETATTR.invokeExact(fd, termios);
        } catch (Throwable t) {
            throw new RuntimeException("tcgetattr failed", t);
        }
    }

    static int tcsetattr(int fd, int optionalActions, MemorySegment termios) {
        try {
            return (int) TCSETATTR.invokeExact(fd, optionalActions, termios);
        } catch (Throwable t) {
            throw new RuntimeException("tcsetattr failed", t);
        }
    }

    static int ioctl(int fd, long request, MemorySegment arg) {
        try {
            MemorySegment callState = CALL_STATE_SEGMENT.get();
            int result = (int) IOCTL.invokeExact(callState, fd, request, arg);
            if (result < 0) {
                lastErrno = (int) ERRNO_HANDLE.get(callState, 0L);
            }
            return result;
        } catch (Throwable t) {
            throw new RuntimeException("ioctl failed", t);
        }
    }

    private static volatile int lastErrno = 0;

    static int getLastErrno() {
        return lastErrno;
    }

    static long read(int fd, MemorySegment buf, long count) {
        try {
            return (long) READ.invokeExact(fd, buf, count);
        } catch (Throwable t) {
            throw new RuntimeException("read failed", t);
        }
    }

    static long write(int fd, MemorySegment buf, long count) {
        try {
            MemorySegment callState = CALL_STATE_SEGMENT.get();
            long result = (long) WRITE.invokeExact(callState, fd, buf, count);
            if (result < 0) {
                lastErrno = (int) ERRNO_HANDLE.get(callState, 0L);
            }
            return result;
        } catch (Throwable t) {
            throw new RuntimeException("write failed", t);
        }
    }

    static int poll(MemorySegment fds, int nfds, int timeout) {
        try {
            MemorySegment callState = CALL_STATE_SEGMENT.get();
            int result;
            if (PlatformConstants.isMacOS()) {
                result = (int) POLL_MACOS.invokeExact(callState, fds, nfds, timeout);
            } else {
                result = (int) POLL_LINUX.invokeExact(callState, fds, (long) nfds, timeout);
            }
            if (result < 0) {
                lastErrno = (int) ERRNO_HANDLE.get(callState, 0L);
            }
            return result;
        } catch (Throwable t) {
            throw new RuntimeException("poll failed", t);
        }
    }

    static int open(String pathname, int flags) {
        try (Arena arena = Arena.ofConfined()) {
            MemorySegment pathSegment = arena.allocateFrom(pathname);
            MemorySegment callState = arena.allocate(CALL_STATE_LAYOUT);
            int result = (int) OPEN.invokeExact(callState, pathSegment, flags);
            if (result < 0) {
                lastErrno = (int) ERRNO_HANDLE.get(callState, 0L);
            }
            return result;
        } catch (Throwable t) {
            throw new RuntimeException("open failed", t);
        }
    }

    static int close(int fd) {
        try {
            return (int) CLOSE.invokeExact(fd);
        } catch (Throwable t) {
            throw new RuntimeException("close failed", t);
        }
    }

    static int sigaction(int signum, MemorySegment act, MemorySegment oldact) {
        try {
            return (int) SIGACTION.invokeExact(signum, act, oldact);
        } catch (Throwable t) {
            throw new RuntimeException("sigaction failed", t);
        }
    }

    static MemorySegment allocateSigaction(Arena arena) {
        return arena.allocate(SIGACTION_LAYOUT);
    }

    static void setSigactionHandler(MemorySegment sigactionStruct, MemorySegment handler) {
        SIGACTION_HANDLER.set(sigactionStruct, 0L, handler);
    }

    static void setSigactionFlags(MemorySegment sigactionStruct, int flags) {
        if (PlatformConstants.isMacOS()) {
            SIGACTION_FLAGS.set(sigactionStruct, 0L, flags);
        } else {
            // On Linux, sa_flags is a long
            SIGACTION_FLAGS.set(sigactionStruct, 0L, (long) flags);
        }
    }

    static void setSigactionTramp(MemorySegment sigactionStruct, MemorySegment tramp) {
        if (SIGACTION_TRAMP != null) {
            SIGACTION_TRAMP.set(sigactionStruct, 0L, tramp);
        }
        // On Linux, there's no trampoline field - do nothing
    }

    static void setSigactionMask(MemorySegment sigactionStruct, int mask) {
        if (SIGACTION_MASK_MACOS != null) {
            SIGACTION_MASK_MACOS.set(sigactionStruct, 0L, mask);
        }
        // On Linux, sa_mask is a 128-byte sigset_t - leave it zeroed (empty mask)
    }

    static MemorySegment createSignalHandler(Arena arena, java.util.function.IntConsumer handler) {
        try {
            // Create unbound MethodHandle first (like jextract does)
            MethodHandle unboundHandle =
                    java.lang.invoke.MethodHandles.lookup()
                            .findVirtual(
                                    java.util.function.IntConsumer.class,
                                    "accept",
                                    SIGNAL_HANDLER_DESC.toMethodType());
            // Bind handler when creating upcall stub (matches jextract pattern)
            return LINKER.upcallStub(unboundHandle.bindTo(handler), SIGNAL_HANDLER_DESC, arena);
        } catch (NoSuchMethodException | IllegalAccessException e) {
            throw new RuntimeException("Failed to create signal handler", e);
        }
    }

    /** Layout for the termios structure. */
    static final MemoryLayout TERMIOS_LAYOUT = PlatformConstants.TERMIOS_LAYOUT;

    /** Layout for the winsize structure. */
    static final MemoryLayout WINSIZE_LAYOUT =
            MemoryLayout.structLayout(
                    ValueLayout.JAVA_SHORT.withName("ws_row"),
                    ValueLayout.JAVA_SHORT.withName("ws_col"),
                    ValueLayout.JAVA_SHORT.withName("ws_xpixel"),
                    ValueLayout.JAVA_SHORT.withName("ws_ypixel"));

    /** Layout for the pollfd structure. */
    static final MemoryLayout POLLFD_LAYOUT =
            MemoryLayout.structLayout(
                    ValueLayout.JAVA_INT.withName("fd"),
                    ValueLayout.JAVA_SHORT.withName("events"),
                    ValueLayout.JAVA_SHORT.withName("revents"));

    private static final MemoryLayout SIGACTION_U_LAYOUT_MACOS =
            MemoryLayout.unionLayout(
                    C_POINTER.withName("__sa_handler"), C_POINTER.withName("__sa_sigaction"));

    static final MemoryLayout SIGACTION_LAYOUT =
            PlatformConstants.isMacOS()
                    ? MemoryLayout.structLayout(
                            SIGACTION_U_LAYOUT_MACOS.withName("__sigaction_u"),
                            C_POINTER.withName("sa_tramp"),
                            C_INT.withName("sa_mask"),
                            C_INT.withName("sa_flags"))
                    : MemoryLayout.structLayout(
                            C_POINTER.withName("sa_handler"),
                            ValueLayout.JAVA_LONG.withName("sa_flags"),
                            C_POINTER.withName("sa_restorer"),
                            MemoryLayout.sequenceLayout(128, ValueLayout.JAVA_BYTE)
                                    .withName("sa_mask"));

    private static final VarHandle SIGACTION_HANDLER =
            PlatformConstants.isMacOS()
                    ? SIGACTION_LAYOUT.varHandle(
                            MemoryLayout.PathElement.groupElement("__sigaction_u"),
                            MemoryLayout.PathElement.groupElement("__sa_handler"))
                    : SIGACTION_LAYOUT.varHandle(
                            MemoryLayout.PathElement.groupElement("sa_handler"));

    private static final VarHandle SIGACTION_FLAGS =
            PlatformConstants.isMacOS()
                    ? SIGACTION_LAYOUT.varHandle(MemoryLayout.PathElement.groupElement("sa_flags"))
                    : SIGACTION_LAYOUT.varHandle(MemoryLayout.PathElement.groupElement("sa_flags"));

    private static final VarHandle SIGACTION_TRAMP =
            PlatformConstants.isMacOS()
                    ? SIGACTION_LAYOUT.varHandle(MemoryLayout.PathElement.groupElement("sa_tramp"))
                    : null;

    private static final VarHandle SIGACTION_MASK_MACOS =
            PlatformConstants.isMacOS()
                    ? SIGACTION_LAYOUT.varHandle(MemoryLayout.PathElement.groupElement("sa_mask"))
                    : null;

    static MemorySegment allocateTermios(Arena arena) {
        return arena.allocate(TERMIOS_LAYOUT);
    }

    static MemorySegment allocateWinsize(Arena arena) {
        return arena.allocate(WINSIZE_LAYOUT);
    }

    static MemorySegment allocatePollfd(Arena arena) {
        return arena.allocate(POLLFD_LAYOUT);
    }
}

/**
 * Platform-specific constants for Unix terminal operations.
 *
 * <p>This class provides the correct values for termios flags, control character indices, and
 * structure layouts that differ between Linux and macOS.
 */
final class PlatformConstants {

    private static final boolean IS_MACOS;

    static {
        var os = System.getProperty("os.name", "").toLowerCase();
        IS_MACOS = os.contains("mac") || os.contains("darwin");
        if (!IS_MACOS && !os.contains("linux")) {
            throw new UnsupportedOperationException(
                    "Unsupported operating system for Unix terminal: "
                            + System.getProperty("os.name")
                            + ". Only Linux and macOS are supported by this implementation.");
        }
    }

    private PlatformConstants() {}

    static boolean isMacOS() {
        return IS_MACOS;
    }

    /** ioctl request code to get terminal window size. */
    static final long TIOCGWINSZ = IS_MACOS ? 0x40087468L : 0x5413L;

    /** Local flag: enable echo of input characters. */
    static final int ECHO = 0x00000008; // Same on both

    /** Local flag: enable canonical (line-by-line) input mode. */
    static final int ICANON = IS_MACOS ? 0x00000100 : 0x00000002;

    /** Local flag: enable signal generation for INTR, QUIT, SUSP characters. */
    static final int ISIG = IS_MACOS ? 0x00000080 : 0x00000001;

    /** Local flag: enable implementation-defined input processing. */
    static final int IEXTEN = IS_MACOS ? 0x00000400 : 0x00008000;

    /** Input flag: enable XON/XOFF flow control on output. */
    static final int IXON = IS_MACOS ? 0x00000200 : 0x00000400;

    /** Input flag: translate carriage return to newline on input. */
    static final int ICRNL = 0x00000100; // Same on both

    /** Input flag: signal interrupt on break. */
    static final int BRKINT = 0x00000002; // Same on both

    /** Input flag: enable input parity check. */
    static final int INPCK = 0x00000010; // Same on both

    /** Input flag: strip eighth bit off input characters. */
    static final int ISTRIP = 0x00000020; // Same on both

    /** Output flag: enable implementation-defined output processing. */
    static final int OPOST = 0x00000001; // Same on both

    /** Control flag: set character size to 8 bits. */
    static final int CS8 = IS_MACOS ? 0x00000300 : 0x00000030;

    /** Control character index for minimum number of bytes for non-canonical read. */
    static final int VMIN = IS_MACOS ? 16 : 6;

    /** Control character index for timeout in deciseconds for non-canonical read. */
    static final int VTIME = IS_MACOS ? 17 : 5;

    /** Signal number for terminal window size change. */
    static final int SIGWINCH = 28;

    /**
     * Termios structure layout.
     *
     * <p>Linux layout: - c_iflag (4 bytes) - c_oflag (4 bytes) - c_cflag (4 bytes) - c_lflag (4
     * bytes) - c_line (1 byte) - c_cc[32] (32 bytes) - padding (3 bytes) - c_ispeed (4 bytes) -
     * c_ospeed (4 bytes)
     *
     * <p>macOS layout: - c_iflag (8 bytes - unsigned long) - c_oflag (8 bytes) - c_cflag (8 bytes)
     * - c_lflag (8 bytes) - c_cc[20] (20 bytes) - padding (4 bytes) - c_ispeed (8 bytes) - c_ospeed
     * (8 bytes)
     */
    static final MemoryLayout TERMIOS_LAYOUT =
            IS_MACOS
                    ? MemoryLayout.structLayout(
                            ValueLayout.JAVA_LONG.withName("c_iflag"),
                            ValueLayout.JAVA_LONG.withName("c_oflag"),
                            ValueLayout.JAVA_LONG.withName("c_cflag"),
                            ValueLayout.JAVA_LONG.withName("c_lflag"),
                            MemoryLayout.sequenceLayout(20, ValueLayout.JAVA_BYTE).withName("c_cc"),
                            MemoryLayout.paddingLayout(4),
                            ValueLayout.JAVA_LONG.withName("c_ispeed"),
                            ValueLayout.JAVA_LONG.withName("c_ospeed"))
                    : MemoryLayout.structLayout(
                            ValueLayout.JAVA_INT.withName("c_iflag"),
                            ValueLayout.JAVA_INT.withName("c_oflag"),
                            ValueLayout.JAVA_INT.withName("c_cflag"),
                            ValueLayout.JAVA_INT.withName("c_lflag"),
                            ValueLayout.JAVA_BYTE.withName("c_line"),
                            MemoryLayout.sequenceLayout(32, ValueLayout.JAVA_BYTE).withName("c_cc"),
                            MemoryLayout.paddingLayout(3),
                            ValueLayout.JAVA_INT.withName("c_ispeed"),
                            ValueLayout.JAVA_INT.withName("c_ospeed"));

    /** Offset to the c_cc array in the termios structure. */
    static final long TERMIOS_CC_OFFSET = IS_MACOS ? 32L : 17L;
}
