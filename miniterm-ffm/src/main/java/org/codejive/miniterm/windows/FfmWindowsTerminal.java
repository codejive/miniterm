package org.codejive.miniterm.windows;

import java.io.IOException;
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
import org.codejive.miniterm.Terminal;

/**
 * Windows terminal operations using Panama FFI.
 *
 * <p>This class provides terminal operations via direct bindings to Windows Kernel32 console
 * functions.
 */
public final class FfmWindowsTerminal implements Terminal {
    private final Arena arena;
    private final MemorySegment inputHandle;
    private final MemorySegment outputHandle;
    private final MemorySegment screenBufferInfo;
    private final MemorySegment inputRecord;
    private final MemorySegment intBuffer;

    private final int savedInputMode;
    private final int savedOutputMode;
    private boolean rawModeEnabled;
    private volatile Runnable resizeHandler;

    /**
     * Creates a new Windows terminal instance.
     *
     * @throws IOException if the terminal cannot be initialized
     */
    public FfmWindowsTerminal() throws IOException {
        this.arena = Arena.ofShared();

        inputHandle = Kernel32.getStdHandle(Kernel32.STD_INPUT_HANDLE);
        outputHandle = Kernel32.getStdHandle(Kernel32.STD_OUTPUT_HANDLE);

        if (inputHandle.address() == Kernel32.INVALID_HANDLE_VALUE
                || outputHandle.address() == Kernel32.INVALID_HANDLE_VALUE) {
            arena.close();
            throw new RuntimeException("Failed to get console handles");
        }

        screenBufferInfo = arena.allocate(Kernel32.CONSOLE_SCREEN_BUFFER_INFO_LAYOUT);
        inputRecord = arena.allocate(Kernel32.INPUT_RECORD_LAYOUT);
        intBuffer = arena.allocate(ValueLayout.JAVA_INT);

        // Save original console modes
        if (Kernel32.getConsoleMode(inputHandle, intBuffer) == 0) {
            arena.close();
            throw new RuntimeException("Failed to get input console mode");
        }
        savedInputMode = intBuffer.get(ValueLayout.JAVA_INT, 0);

        if (Kernel32.getConsoleMode(outputHandle, intBuffer) == 0) {
            arena.close();
            throw new RuntimeException("Failed to get output console mode");
        }
        savedOutputMode = intBuffer.get(ValueLayout.JAVA_INT, 0);

        rawModeEnabled = false;
    }

    @Override
    public void enableRawMode() throws IOException {
        if (rawModeEnabled) {
            return;
        }

        // Set input mode: disable line input, echo, and processed input; enable VT input
        var newInputMode =
                savedInputMode
                                & ~(Kernel32.ENABLE_LINE_INPUT
                                        | Kernel32.ENABLE_ECHO_INPUT
                                        | Kernel32.ENABLE_PROCESSED_INPUT)
                        | Kernel32.ENABLE_VIRTUAL_TERMINAL_INPUT
                        | Kernel32.ENABLE_WINDOW_INPUT;

        if (Kernel32.setConsoleMode(inputHandle, newInputMode) == 0) {
            throw new RuntimeException(
                    "Failed to set input console mode (error=" + Kernel32.getLastError() + ")");
        }

        // Set output mode: enable VT processing
        var newOutputMode =
                savedOutputMode
                        | Kernel32.ENABLE_VIRTUAL_TERMINAL_PROCESSING
                        | Kernel32.ENABLE_PROCESSED_OUTPUT;

        if (Kernel32.setConsoleMode(outputHandle, newOutputMode) == 0) {
            Kernel32.setConsoleMode(inputHandle, savedInputMode);
            throw new RuntimeException(
                    "Failed to set output console mode (error=" + Kernel32.getLastError() + ")");
        }

        rawModeEnabled = true;
    }

    @Override
    public void disableRawMode() throws IOException {
        if (!rawModeEnabled) {
            return;
        }

        if (Kernel32.setConsoleMode(inputHandle, savedInputMode) == 0) {
            throw new RuntimeException("Failed to restore input console mode");
        }

        if (Kernel32.setConsoleMode(outputHandle, savedOutputMode) == 0) {
            throw new RuntimeException("Failed to restore output console mode");
        }

        rawModeEnabled = false;
    }

    @Override
    public Size getSize() throws IOException {
        if (Kernel32.getConsoleScreenBufferInfo(outputHandle, screenBufferInfo) == 0) {
            throw new RuntimeException(
                    "Failed to get console screen buffer info (error="
                            + Kernel32.getLastError()
                            + ")");
        }

        var left = (short) Kernel32.CSBI_WINDOW_LEFT.get(screenBufferInfo, 0L);
        var right = (short) Kernel32.CSBI_WINDOW_RIGHT.get(screenBufferInfo, 0L);
        var top = (short) Kernel32.CSBI_WINDOW_TOP.get(screenBufferInfo, 0L);
        var bottom = (short) Kernel32.CSBI_WINDOW_BOTTOM.get(screenBufferInfo, 0L);

        var cols = right - left + 1;
        var rows = bottom - top + 1;

        if (cols > 0 && rows > 0) {
            return new Size(cols, rows);
        }
        throw new RuntimeException("Invalid console size");
    }

    @Override
    public int read(int timeoutMs) throws IOException {
        if (Kernel32.getNumberOfConsoleInputEvents(inputHandle, intBuffer) == 0) {
            throw new RuntimeException("Failed to get number of console input events");
        }

        if (intBuffer.get(ValueLayout.JAVA_INT, 0) == 0) {
            if (timeoutMs == 0) {
                return -2; // Non-blocking, no data
            }
            // Wait for input with timeout using WaitForSingleObject
            var waitResult = Kernel32.waitForSingleObject(inputHandle, timeoutMs);
            if (waitResult == Kernel32.WAIT_TIMEOUT) {
                return -2; // Timeout, no data
            }
            if (waitResult == Kernel32.WAIT_FAILED) {
                throw new RuntimeException(
                        "WaitForSingleObject failed (error=" + Kernel32.getLastError() + ")");
            }
            // WAIT_OBJECT_0: input is available, proceed to read
        }

        if (Kernel32.readConsoleInput(inputHandle, inputRecord, 1, intBuffer) == 0) {
            throw new RuntimeException("Failed to read console input");
        }

        if (intBuffer.get(ValueLayout.JAVA_INT, 0) == 0) {
            return -2; // No data
        }

        var eventType = (short) Kernel32.IR_EVENT_TYPE.get(inputRecord, 0L);
        if (eventType == Kernel32.KEY_EVENT) {
            var keyDown = (int) Kernel32.IR_KEY_DOWN.get(inputRecord, 0L);
            if (keyDown != 0) {
                var ch = (short) Kernel32.IR_CHAR.get(inputRecord, 0L);
                if (ch != 0) {
                    return ch & 0xFFFF;
                }
            }
        } else if (eventType == Kernel32.WINDOW_BUFFER_SIZE_EVENT) {
            var handler = resizeHandler;
            if (handler != null) {
                handler.run();
            }
        }

        return -2; // No relevant data
    }

    @Override
    public int peek(int timeoutMs) throws IOException {
        if (Kernel32.getNumberOfConsoleInputEvents(inputHandle, intBuffer) == 0) {
            throw new RuntimeException("Failed to get number of console input events");
        }
        return intBuffer.get(ValueLayout.JAVA_INT, 0) > 0 ? 0 : -2;
    }

    @Override
    public void write(byte[] data) throws IOException {
        write(data, 0, data.length);
    }

    @Override
    public void write(byte[] buffer, int offset, int length) throws IOException {
        if (length == 0) {
            return;
        }
        // Windows Console API uses UTF-16, so we need to convert from bytes
        write(new String(buffer, offset, length, StandardCharsets.UTF_8));
    }

    @Override
    public void write(String s) throws IOException {
        if (s.isEmpty()) {
            return;
        }

        try (var writeArena = Arena.ofConfined()) {
            var chars = s.toCharArray();
            var buffer = writeArena.allocate(ValueLayout.JAVA_CHAR, chars.length);
            for (var i = 0; i < chars.length; i++) {
                buffer.setAtIndex(ValueLayout.JAVA_CHAR, i, chars[i]);
            }

            var written = writeArena.allocate(ValueLayout.JAVA_INT);
            if (Kernel32.writeConsole(
                            outputHandle, buffer, chars.length, written, MemorySegment.NULL)
                    == 0) {
                throw new RuntimeException("Write failed (error=" + Kernel32.getLastError() + ")");
            }
        }
    }

    @Override
    public Charset getCharset() {
        return StandardCharsets.UTF_8;
    }

    @Override
    public boolean isRawModeEnabled() {
        return rawModeEnabled;
    }

    @Override
    public void onResize(Runnable handler) {
        this.resizeHandler = handler;
    }

    @Override
    public void close() throws IOException {
        try {
            if (rawModeEnabled) {
                disableRawMode();
            }
        } finally {
            arena.close();
        }
    }
}

final class Kernel32 { // --- Static FFI wrappers ---

    public static MemorySegment getStdHandle(int stdHandle) {
        try {
            return (MemorySegment) GET_STD_HANDLE.invokeExact(stdHandle);
        } catch (Throwable t) {
            throw new RuntimeException("GetStdHandle failed", t);
        }
    }

    public static int getConsoleMode(MemorySegment handle, MemorySegment modePtr) {
        try {
            return (int) GET_CONSOLE_MODE.invokeExact(handle, modePtr);
        } catch (Throwable t) {
            throw new RuntimeException("GetConsoleMode failed", t);
        }
    }

    public static int setConsoleMode(MemorySegment handle, int mode) {
        try {
            return (int) SET_CONSOLE_MODE.invokeExact(handle, mode);
        } catch (Throwable t) {
            throw new RuntimeException("SetConsoleMode failed", t);
        }
    }

    public static int getConsoleScreenBufferInfo(MemorySegment handle, MemorySegment infoPtr) {
        try {
            return (int) GET_CONSOLE_SCREEN_BUFFER_INFO.invokeExact(handle, infoPtr);
        } catch (Throwable t) {
            throw new RuntimeException("GetConsoleScreenBufferInfo failed", t);
        }
    }

    public static int writeConsole(
            MemorySegment handle,
            MemorySegment buffer,
            int numChars,
            MemorySegment numWritten,
            MemorySegment reserved) {
        try {
            return (int) WRITE_CONSOLE.invokeExact(handle, buffer, numChars, numWritten, reserved);
        } catch (Throwable t) {
            throw new RuntimeException("WriteConsoleW failed", t);
        }
    }

    public static int readConsoleInput(
            MemorySegment handle, MemorySegment buffer, int length, MemorySegment numRead) {
        try {
            return (int) READ_CONSOLE_INPUT.invokeExact(handle, buffer, length, numRead);
        } catch (Throwable t) {
            throw new RuntimeException("ReadConsoleInputW failed", t);
        }
    }

    public static int getNumberOfConsoleInputEvents(MemorySegment handle, MemorySegment numEvents) {
        try {
            return (int) GET_NUMBER_OF_CONSOLE_INPUT_EVENTS.invokeExact(handle, numEvents);
        } catch (Throwable t) {
            throw new RuntimeException("GetNumberOfConsoleInputEvents failed", t);
        }
    }

    public static int getLastError() {
        try {
            return (int) GET_LAST_ERROR.invokeExact();
        } catch (Throwable t) {
            throw new RuntimeException("GetLastError failed", t);
        }
    }

    public static int waitForSingleObject(MemorySegment handle, int timeoutMs) {
        try {
            return (int) WAIT_FOR_SINGLE_OBJECT.invokeExact(handle, timeoutMs);
        } catch (Throwable t) {
            throw new RuntimeException("WaitForSingleObject failed", t);
        }
    }

    // --- Constants ---

    public static final int STD_INPUT_HANDLE = -10;
    public static final int STD_OUTPUT_HANDLE = -11;
    public static final int ENABLE_PROCESSED_INPUT = 0x0001;
    public static final int ENABLE_LINE_INPUT = 0x0002;
    public static final int ENABLE_ECHO_INPUT = 0x0004;
    public static final int ENABLE_WINDOW_INPUT = 0x0008;
    public static final int ENABLE_VIRTUAL_TERMINAL_INPUT = 0x0200;
    public static final int ENABLE_PROCESSED_OUTPUT = 0x0001;
    public static final int ENABLE_VIRTUAL_TERMINAL_PROCESSING = 0x0004;
    public static final short KEY_EVENT = 0x0001;
    public static final short WINDOW_BUFFER_SIZE_EVENT = 0x0004;
    public static final long INVALID_HANDLE_VALUE = -1L;
    public static final int WAIT_TIMEOUT = 0x00000102;
    public static final int WAIT_FAILED = 0xFFFFFFFF;

    // --- FFI setup ---

    private static final Linker LINKER = Linker.nativeLinker();
    private static final SymbolLookup KERNEL32;

    private static final MethodHandle GET_STD_HANDLE;
    private static final MethodHandle GET_CONSOLE_MODE;
    private static final MethodHandle SET_CONSOLE_MODE;
    private static final MethodHandle GET_CONSOLE_SCREEN_BUFFER_INFO;
    private static final MethodHandle WRITE_CONSOLE;
    private static final MethodHandle READ_CONSOLE_INPUT;
    private static final MethodHandle GET_NUMBER_OF_CONSOLE_INPUT_EVENTS;
    private static final MethodHandle GET_LAST_ERROR;
    private static final MethodHandle WAIT_FOR_SINGLE_OBJECT;

    static {
        KERNEL32 = SymbolLookup.libraryLookup("kernel32", Arena.global());

        try {
            GET_STD_HANDLE =
                    LINKER.downcallHandle(
                            KERNEL32.find("GetStdHandle").orElseThrow(),
                            FunctionDescriptor.of(ValueLayout.ADDRESS, ValueLayout.JAVA_INT));

            GET_CONSOLE_MODE =
                    LINKER.downcallHandle(
                            KERNEL32.find("GetConsoleMode").orElseThrow(),
                            FunctionDescriptor.of(
                                    ValueLayout.JAVA_INT,
                                    ValueLayout.ADDRESS,
                                    ValueLayout.ADDRESS));

            SET_CONSOLE_MODE =
                    LINKER.downcallHandle(
                            KERNEL32.find("SetConsoleMode").orElseThrow(),
                            FunctionDescriptor.of(
                                    ValueLayout.JAVA_INT,
                                    ValueLayout.ADDRESS,
                                    ValueLayout.JAVA_INT));

            GET_CONSOLE_SCREEN_BUFFER_INFO =
                    LINKER.downcallHandle(
                            KERNEL32.find("GetConsoleScreenBufferInfo").orElseThrow(),
                            FunctionDescriptor.of(
                                    ValueLayout.JAVA_INT,
                                    ValueLayout.ADDRESS,
                                    ValueLayout.ADDRESS));

            WRITE_CONSOLE =
                    LINKER.downcallHandle(
                            KERNEL32.find("WriteConsoleW").orElseThrow(),
                            FunctionDescriptor.of(
                                    ValueLayout.JAVA_INT,
                                    ValueLayout.ADDRESS,
                                    ValueLayout.ADDRESS,
                                    ValueLayout.JAVA_INT,
                                    ValueLayout.ADDRESS,
                                    ValueLayout.ADDRESS));

            READ_CONSOLE_INPUT =
                    LINKER.downcallHandle(
                            KERNEL32.find("ReadConsoleInputW").orElseThrow(),
                            FunctionDescriptor.of(
                                    ValueLayout.JAVA_INT,
                                    ValueLayout.ADDRESS,
                                    ValueLayout.ADDRESS,
                                    ValueLayout.JAVA_INT,
                                    ValueLayout.ADDRESS));

            GET_NUMBER_OF_CONSOLE_INPUT_EVENTS =
                    LINKER.downcallHandle(
                            KERNEL32.find("GetNumberOfConsoleInputEvents").orElseThrow(),
                            FunctionDescriptor.of(
                                    ValueLayout.JAVA_INT,
                                    ValueLayout.ADDRESS,
                                    ValueLayout.ADDRESS));

            GET_LAST_ERROR =
                    LINKER.downcallHandle(
                            KERNEL32.find("GetLastError").orElseThrow(),
                            FunctionDescriptor.of(ValueLayout.JAVA_INT));

            WAIT_FOR_SINGLE_OBJECT =
                    LINKER.downcallHandle(
                            KERNEL32.find("WaitForSingleObject").orElseThrow(),
                            FunctionDescriptor.of(
                                    ValueLayout.JAVA_INT,
                                    ValueLayout.ADDRESS,
                                    ValueLayout.JAVA_INT));
        } catch (Exception e) {
            throw new ExceptionInInitializerError(e);
        }
    }

    // --- Structure layouts ---

    public static final MemoryLayout COORD_LAYOUT =
            MemoryLayout.structLayout(
                    ValueLayout.JAVA_SHORT.withName("X"), ValueLayout.JAVA_SHORT.withName("Y"));

    public static final MemoryLayout SMALL_RECT_LAYOUT =
            MemoryLayout.structLayout(
                    ValueLayout.JAVA_SHORT.withName("Left"),
                    ValueLayout.JAVA_SHORT.withName("Top"),
                    ValueLayout.JAVA_SHORT.withName("Right"),
                    ValueLayout.JAVA_SHORT.withName("Bottom"));

    public static final MemoryLayout CONSOLE_SCREEN_BUFFER_INFO_LAYOUT =
            MemoryLayout.structLayout(
                    COORD_LAYOUT.withName("dwSize"),
                    COORD_LAYOUT.withName("dwCursorPosition"),
                    ValueLayout.JAVA_SHORT.withName("wAttributes"),
                    SMALL_RECT_LAYOUT.withName("srWindow"),
                    COORD_LAYOUT.withName("dwMaximumWindowSize"));

    public static final MemoryLayout KEY_EVENT_RECORD_LAYOUT =
            MemoryLayout.structLayout(
                    ValueLayout.JAVA_INT.withName("bKeyDown"),
                    ValueLayout.JAVA_SHORT.withName("wRepeatCount"),
                    ValueLayout.JAVA_SHORT.withName("wVirtualKeyCode"),
                    ValueLayout.JAVA_SHORT.withName("wVirtualScanCode"),
                    ValueLayout.JAVA_SHORT.withName("uChar"),
                    ValueLayout.JAVA_INT.withName("dwControlKeyState"));

    public static final MemoryLayout INPUT_RECORD_LAYOUT =
            MemoryLayout.structLayout(
                    ValueLayout.JAVA_SHORT.withName("EventType"),
                    MemoryLayout.paddingLayout(2),
                    KEY_EVENT_RECORD_LAYOUT.withName("Event"));

    public static final VarHandle CSBI_WINDOW_LEFT =
            CONSOLE_SCREEN_BUFFER_INFO_LAYOUT.varHandle(
                    MemoryLayout.PathElement.groupElement("srWindow"),
                    MemoryLayout.PathElement.groupElement("Left"));

    public static final VarHandle CSBI_WINDOW_TOP =
            CONSOLE_SCREEN_BUFFER_INFO_LAYOUT.varHandle(
                    MemoryLayout.PathElement.groupElement("srWindow"),
                    MemoryLayout.PathElement.groupElement("Top"));

    public static final VarHandle CSBI_WINDOW_RIGHT =
            CONSOLE_SCREEN_BUFFER_INFO_LAYOUT.varHandle(
                    MemoryLayout.PathElement.groupElement("srWindow"),
                    MemoryLayout.PathElement.groupElement("Right"));

    public static final VarHandle CSBI_WINDOW_BOTTOM =
            CONSOLE_SCREEN_BUFFER_INFO_LAYOUT.varHandle(
                    MemoryLayout.PathElement.groupElement("srWindow"),
                    MemoryLayout.PathElement.groupElement("Bottom"));

    public static final VarHandle IR_EVENT_TYPE =
            INPUT_RECORD_LAYOUT.varHandle(MemoryLayout.PathElement.groupElement("EventType"));

    public static final VarHandle IR_KEY_DOWN =
            INPUT_RECORD_LAYOUT.varHandle(
                    MemoryLayout.PathElement.groupElement("Event"),
                    MemoryLayout.PathElement.groupElement("bKeyDown"));

    public static final VarHandle IR_CHAR =
            INPUT_RECORD_LAYOUT.varHandle(
                    MemoryLayout.PathElement.groupElement("Event"),
                    MemoryLayout.PathElement.groupElement("uChar"));
}
