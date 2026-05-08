package org.codejive.miniterm.windows;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.Charset;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.codejive.miniterm.Terminal;

/**
 * Windows terminal implementation using JNI via {@code aesh-console.dll}.
 *
 * <p>Compatible with Java 8+. Uses the same native DLL as the aesh-term-mini project.
 */
public final class LegacyWindowsTerminal implements Terminal {

    // Console mode flags for input handle
    private static final int ENABLE_PROCESSED_INPUT = 0x0001;
    private static final int ENABLE_LINE_INPUT = 0x0002;
    private static final int ENABLE_ECHO_INPUT = 0x0004;
    private static final int ENABLE_WINDOW_INPUT = 0x0008;
    private static final int ENABLE_VIRTUAL_TERMINAL_INPUT = 0x0200;

    // Console mode flags for output handle
    private static final int ENABLE_PROCESSED_OUTPUT = 0x0001;
    private static final int ENABLE_VIRTUAL_TERMINAL_PROCESSING = 0x0004;

    // Key modifier state bits
    private static final int RIGHT_ALT_PRESSED = 0x0001;
    private static final int LEFT_ALT_PRESSED = 0x0002;
    private static final int LEFT_CTRL_PRESSED = 0x0008;
    private static final int RIGHT_CTRL_PRESSED = 0x0004;
    private static final int SHIFT_PRESSED = 0x0010;
    private static final int ALT_MASK = RIGHT_ALT_PRESSED | LEFT_ALT_PRESSED;
    private static final int CTRL_MASK = RIGHT_CTRL_PRESSED | LEFT_CTRL_PRESSED;

    private static final long INPUT_HANDLE =
            WinConsoleNative.getStdHandle(WinConsoleNative.STD_INPUT_HANDLE);
    private static final long OUTPUT_HANDLE =
            WinConsoleNative.getStdHandle(WinConsoleNative.STD_OUTPUT_HANDLE);

    private final Charset charset;
    private final LinkedBlockingQueue<Integer> inputQueue = new LinkedBlockingQueue<Integer>();
    private final Thread pumpThread;

    private int savedInputMode = -1;
    private int savedOutputMode = -1;
    private boolean rawModeEnabled;
    private volatile boolean closing;
    private volatile Consumer<Size> resizeHandler;
    private int peekedChar = -2;

    /** Creates a new Windows terminal instance. */
    public LegacyWindowsTerminal() {
        this.charset = detectCharset();
        pumpThread =
                new Thread(
                        new Runnable() {
                            public void run() {
                                pump();
                            }
                        },
                        "LegacyWindowsTerminalPump");
        pumpThread.setDaemon(true);
        pumpThread.start();
    }

    @Override
    public void enableRawMode() throws IOException {
        if (rawModeEnabled) return;

        savedInputMode = WinConsoleNative.getConsoleMode(INPUT_HANDLE);
        savedOutputMode = WinConsoleNative.getConsoleMode(OUTPUT_HANDLE);

        int newInputMode =
                (savedInputMode & ~(ENABLE_LINE_INPUT | ENABLE_ECHO_INPUT | ENABLE_PROCESSED_INPUT))
                        | ENABLE_WINDOW_INPUT
                        | ENABLE_VIRTUAL_TERMINAL_INPUT;
        if (!WinConsoleNative.setConsoleMode(INPUT_HANDLE, newInputMode)) {
            throw new IOException("Failed to set raw input mode");
        }

        int newOutputMode =
                savedOutputMode | ENABLE_PROCESSED_OUTPUT | ENABLE_VIRTUAL_TERMINAL_PROCESSING;
        WinConsoleNative.setConsoleMode(OUTPUT_HANDLE, newOutputMode);

        rawModeEnabled = true;
    }

    @Override
    public void disableRawMode() throws IOException {
        if (!rawModeEnabled) return;
        if (savedInputMode != -1) {
            WinConsoleNative.setConsoleMode(INPUT_HANDLE, savedInputMode);
        }
        if (savedOutputMode != -1) {
            WinConsoleNative.setConsoleMode(OUTPUT_HANDLE, savedOutputMode);
        }
        rawModeEnabled = false;
    }

    @Override
    public Size getSize() throws IOException {
        int[] size = WinConsoleNative.getConsoleSize(OUTPUT_HANDLE);
        if (size == null) return new Size(80, 24);
        return new Size(size[0], size[1]);
    }

    @Override
    public int read(int timeoutMs) throws IOException {
        if (peekedChar != -2) {
            int c = peekedChar;
            peekedChar = -2;
            return c;
        }
        try {
            Integer c;
            if (timeoutMs < 0) {
                c = inputQueue.take();
            } else if (timeoutMs == 0) {
                c = inputQueue.poll();
            } else {
                c = inputQueue.poll(timeoutMs, TimeUnit.MILLISECONDS);
            }
            return c == null ? -2 : c;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return -2;
        }
    }

    @Override
    public int peek(int timeoutMs) throws IOException {
        if (peekedChar != -2) return peekedChar;
        int c = read(timeoutMs);
        if (c >= 0) peekedChar = c;
        return c;
    }

    @Override
    public void write(byte[] data) throws IOException {
        write(data, 0, data.length);
    }

    @Override
    public void write(byte[] buffer, int offset, int length) throws IOException {
        System.out.write(buffer, offset, length);
        System.out.flush();
    }

    @Override
    public void write(String s) throws IOException {
        char[] chars = s.toCharArray();
        WinConsoleNative.writeConsole(OUTPUT_HANDLE, chars, chars.length);
    }

    @Override
    public Charset getCharset() {
        return charset;
    }

    @Override
    public boolean isRawModeEnabled() {
        return rawModeEnabled;
    }

    @Override
    public void onResize(Consumer<Size> handler) {
        this.resizeHandler = handler;
    }

    @Override
    public void close() throws IOException {
        closing = true;
        pumpThread.interrupt();
        disableRawMode();
    }

    private void pump() {
        while (!closing) {
            int[] event;
            try {
                event = WinConsoleNative.readConsoleInputEvent(INPUT_HANDLE);
            } catch (Exception e) {
                if (!closing) continue;
                break;
            }
            if (event == null) continue;

            if (event[0] == WinConsoleNative.WINDOW_BUFFER_SIZE_EVENT) {
                Consumer<Size> h = resizeHandler;
                if (h != null) {
                    try {
                        h.accept(getSize());
                    } catch (IOException ignore) {
                    }
                }
                continue;
            }
            if (event[0] != WinConsoleNative.KEY_EVENT) continue;

            boolean keyDown = event[1] != 0;
            if (!keyDown) continue;

            int repeatCount = event[2];
            short vKeyCode = (short) event[3];
            char unicodeChar = (char) event[4];
            int ctrlState = event[5];

            boolean isAlt = (ctrlState & ALT_MASK) != 0 && (ctrlState & CTRL_MASK) == 0;

            if (unicodeChar > 0) {
                boolean shiftPressed = (ctrlState & SHIFT_PRESSED) != 0;
                if (unicodeChar == '\t' && shiftPressed) {
                    enqueue('\033');
                    enqueue('[');
                    enqueue('Z');
                } else {
                    if (isAlt) enqueue('\033');
                    enqueue(unicodeChar);
                }
            } else {
                String seq = getEscapeSequence(vKeyCode);
                if (seq != null) {
                    for (int k = 0; k < repeatCount; k++) {
                        if (isAlt) enqueue('\033');
                        for (int i = 0; i < seq.length(); i++) {
                            enqueue(seq.charAt(i));
                        }
                    }
                }
            }
        }
    }

    private void enqueue(char c) {
        try {
            inputQueue.put((int) c);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    private static String getEscapeSequence(short keyCode) {
        switch (keyCode) {
            case 0x08:
                return "\177"; // VK_BACK
            case 0x21:
                return "\033[5~"; // VK_PRIOR (Page Up)
            case 0x22:
                return "\033[6~"; // VK_NEXT  (Page Down)
            case 0x23:
                return "\033[F"; // VK_END
            case 0x24:
                return "\033[H"; // VK_HOME
            case 0x25:
                return "\033[D"; // VK_LEFT
            case 0x26:
                return "\033[A"; // VK_UP
            case 0x27:
                return "\033[C"; // VK_RIGHT
            case 0x28:
                return "\033[B"; // VK_DOWN
            case 0x2D:
                return "\033[2~"; // VK_INSERT
            case 0x2E:
                return "\033[3~"; // VK_DELETE
            case 0x70:
                return "\033OP"; // VK_F1
            case 0x71:
                return "\033OQ"; // VK_F2
            case 0x72:
                return "\033OR"; // VK_F3
            case 0x73:
                return "\033OS"; // VK_F4
            case 0x74:
                return "\033[15~"; // VK_F5
            case 0x75:
                return "\033[17~"; // VK_F6
            case 0x76:
                return "\033[18~"; // VK_F7
            case 0x77:
                return "\033[19~"; // VK_F8
            case 0x78:
                return "\033[20~"; // VK_F9
            case 0x79:
                return "\033[21~"; // VK_F10
            case 0x7A:
                return "\033[23~"; // VK_F11
            case 0x7B:
                return "\033[24~"; // VK_F12
            default:
                return null;
        }
    }

    private static Charset detectCharset() {
        int codepage = WinConsoleNative.getConsoleOutputCP();
        String msCharset = "ms" + codepage;
        if (Charset.isSupported(msCharset)) return Charset.forName(msCharset);
        String cpCharset = "cp" + codepage;
        if (Charset.isSupported(cpCharset)) return Charset.forName(cpCharset);
        return Charset.defaultCharset();
    }
}

/**
 * JNI bridge to Windows console API (Kernel32). Loads {@code miniterm-console.dll} from the system
 * library path or extracts it from the JAR at runtime.
 */
final class WinConsoleNative {

    static final int STD_INPUT_HANDLE = -10;
    static final int STD_OUTPUT_HANDLE = -11;
    static final long INVALID_HANDLE = -1L;

    static native long getStdHandle(int nStdHandle);

    static native int getConsoleMode(long handle);

    static native boolean setConsoleMode(long handle, int mode);

    static native int getConsoleOutputCP();

    /** Returns {@code int[]{width, height}} or {@code null} on error. */
    static native int[] getConsoleSize(long handle);

    /** Event type: key event. */
    static final int KEY_EVENT = 1;

    /** Event type: window buffer size changed. */
    static final int WINDOW_BUFFER_SIZE_EVENT = 4;

    /**
     * Reads one console input event (blocking).
     *
     * <p>Returns {@code int[6]} for key events: {@code {1, keyDown, repeatCount, vKeyCode,
     * unicodeChar, controlKeyState}}, or {@code int[3]} for resize: {@code {4, width, height}}.
     * Returns {@code null} for other events or on error.
     */
    static native int[] readConsoleInputEvent(long handle);

    static native boolean writeConsole(long handle, char[] buffer, int length);

    static {
        loadLibrary();
    }

    private static void loadLibrary() {
        try {
            System.loadLibrary("miniterm-console");
            return;
        } catch (UnsatisfiedLinkError ignore) {
            // Not on system path, try extracting from JAR
        }

        String arch = System.getProperty("os.arch");
        if ("amd64".equals(arch) || "x86_64".equals(arch)) {
            arch = "x86_64";
        }
        String resourcePath = "/native/windows-" + arch + "/miniterm-console.dll";
        try (InputStream in = WinConsoleNative.class.getResourceAsStream(resourcePath)) {
            if (in == null) {
                throw new UnsatisfiedLinkError("Native library not found in JAR: " + resourcePath);
            }
            File tempFile = File.createTempFile("miniterm-console", ".dll");
            tempFile.deleteOnExit();
            try (FileOutputStream out = new FileOutputStream(tempFile)) {
                byte[] buf = new byte[4096];
                int n;
                while ((n = in.read(buf)) > 0) {
                    out.write(buf, 0, n);
                }
            }
            System.load(tempFile.getAbsolutePath());
        } catch (IOException e) {
            throw new UnsatisfiedLinkError("Failed to extract native library: " + e.getMessage());
        }
    }

    private WinConsoleNative() {}
}
