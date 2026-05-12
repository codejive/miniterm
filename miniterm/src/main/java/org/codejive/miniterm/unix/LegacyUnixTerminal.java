package org.codejive.miniterm.unix;

import java.io.ByteArrayOutputStream;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InterruptedIOException;
import java.io.OutputStream;
import java.lang.ProcessBuilder.Redirect;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import org.codejive.miniterm.Terminal;

/**
 * Unix terminal implementation using {@code tty}/{@code stty} external commands.
 *
 * <p>Compatible with Java 8+. Also supports Cygwin environments on Windows.
 */
public final class LegacyUnixTerminal implements Terminal {

    private static final boolean IS_CYGWIN =
            System.getProperty("os.name", "").toLowerCase().contains("win")
                    && System.getenv("PWD") != null
                    && System.getenv("PWD").startsWith("/");

    private static final boolean IS_MACOS =
            System.getProperty("os.name", "").toLowerCase().contains("mac");

    /** The stty command name (stty or stty.exe on Cygwin). */
    private static final String STTY = IS_CYGWIN ? "stty.exe" : "stty";

    /**
     * The device-file flag for stty ({@code -F} on Linux, {@code -f} on macOS, {@code null} on
     * Cygwin/HP-UX/SunOS).
     */
    private static final String STTY_F_OPTION;

    static {
        if (IS_CYGWIN) {
            STTY_F_OPTION = null;
        } else if (IS_MACOS) {
            STTY_F_OPTION = "-f";
        } else {
            STTY_F_OPTION = "-F";
        }
    }

    private final String ttyName;
    private final InputStream input;
    private final OutputStream output;
    private final Charset charset;
    private final LinkedBlockingQueue<Integer> inputQueue = new LinkedBlockingQueue<Integer>();
    private final Thread pumpThread;

    private String savedSettings;
    private boolean rawModeEnabled;
    private volatile boolean closing;
    private volatile Consumer<Size> resizeHandler;
    private int peekedByte = -2;

    /** Creates a new Unix terminal instance. */
    public LegacyUnixTerminal() throws IOException {
        this.charset = detectCharset();

        if (IS_CYGWIN) {
            this.ttyName = null;
            this.input = System.in;
            this.output = System.out;
        } else {
            // Run `tty` to discover the TTY device path
            String tty;
            try {
                Process p =
                        new ProcessBuilder(IS_MACOS ? "tty" : "tty")
                                .redirectInput(Redirect.INHERIT)
                                .start();
                tty = capture(p).trim();
                if (p.exitValue() != 0) {
                    tty = null;
                }
            } catch (InterruptedException e) {
                throw (IOException)
                        new InterruptedIOException("tty command interrupted").initCause(e);
            }
            this.ttyName = tty;

            // Open the TTY file; fall back to System.in/out if inaccessible
            InputStream in;
            OutputStream out;
            if (ttyName != null && new java.io.File(ttyName).exists()) {
                try {
                    in = new FileInputStream(ttyName);
                } catch (IOException e) {
                    in = System.in;
                }
                try {
                    out = new FileOutputStream(ttyName);
                } catch (IOException e) {
                    out = System.out;
                }
            } else {
                in = System.in;
                out = System.out;
            }
            this.input = in;
            this.output = out;
        }

        pumpThread =
                new Thread(
                        new Runnable() {
                            public void run() {
                                pump();
                            }
                        },
                        "LegacyUnixTerminalPump");
        pumpThread.setDaemon(true);
        pumpThread.start();

        registerSigwinch();
    }

    @Override
    public void enableRawMode() throws IOException {
        if (rawModeEnabled) return;
        savedSettings = stty("-g").trim();
        sttySet("-icanon", "-echo", "min", "1", "time", "0");
        rawModeEnabled = true;
    }

    @Override
    public void disableRawMode() throws IOException {
        if (!rawModeEnabled || savedSettings == null) return;
        sttySet(savedSettings);
        rawModeEnabled = false;
    }

    @Override
    public Size size() throws IOException {
        String result = stty("size").trim();
        // Output is "rows cols"
        String[] parts = result.split("\\s+");
        if (parts.length >= 2) {
            try {
                int rows = Integer.parseInt(parts[0]);
                int cols = Integer.parseInt(parts[1]);
                return new Size(cols, rows);
            } catch (NumberFormatException ignore) {
            }
        }
        return new Size(80, 24);
    }

    @Override
    public int read(int timeoutMs) throws IOException {
        if (peekedByte != -2) {
            int b = peekedByte;
            peekedByte = -2;
            return b;
        }
        try {
            Integer b;
            if (timeoutMs < 0) {
                b = inputQueue.take();
            } else if (timeoutMs == 0) {
                b = inputQueue.poll();
            } else {
                b = inputQueue.poll(timeoutMs, TimeUnit.MILLISECONDS);
            }
            return b == null ? -2 : b;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return -2;
        }
    }

    @Override
    public int peek(int timeoutMs) throws IOException {
        if (peekedByte != -2) return peekedByte;
        int b = read(timeoutMs);
        if (b >= 0) peekedByte = b;
        return b;
    }

    @Override
    public void write(byte[] data) throws IOException {
        write(data, 0, data.length);
    }

    @Override
    public void write(byte[] buffer, int offset, int length) throws IOException {
        output.write(buffer, offset, length);
        output.flush();
    }

    @Override
    public void write(String s) throws IOException {
        write(s.getBytes(charset));
    }

    @Override
    public Charset charset() {
        return charset;
    }

    @Override
    public boolean rawModeEnabled() {
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
        if (input != System.in) {
            try {
                input.close();
            } catch (IOException ignore) {
            }
        }
        if (output != System.out) {
            try {
                output.close();
            } catch (IOException ignore) {
            }
        }
    }

    private void pump() {
        try {
            while (!closing) {
                int b;
                try {
                    b = input.read();
                } catch (InterruptedIOException e) {
                    break;
                } catch (IOException e) {
                    if (!closing) {
                        try {
                            inputQueue.put(-1);
                        } catch (InterruptedException ie) {
                            Thread.currentThread().interrupt();
                        }
                    }
                    break;
                }
                if (b == -1) {
                    try {
                        inputQueue.put(-1);
                    } catch (InterruptedException ie) {
                        Thread.currentThread().interrupt();
                    }
                    break;
                }
                try {
                    inputQueue.put(b);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    break;
                }
            }
        } catch (Exception e) {
            // Pump exits silently
        }
    }

    // --- stty helpers ---

    /** Run stty with the supplied arguments and return its output. */
    private String stty(String... args) throws IOException {
        List<String> cmd = buildSttyCommand(args);
        try {
            Process p = new ProcessBuilder(cmd).redirectInput(Redirect.INHERIT).start();
            String out = capture(p);
            if (p.exitValue() != 0) {
                throw new IOException("stty failed: " + out);
            }
            return out;
        } catch (InterruptedException e) {
            throw (IOException) new InterruptedIOException("stty interrupted").initCause(e);
        }
    }

    /** Run stty to set attributes; retries without the device flag if first attempt fails. */
    private void sttySet(String... args) throws IOException {
        List<String> cmd = buildSttyCommand(args);
        try {
            Process p = new ProcessBuilder(cmd).redirectInput(Redirect.INHERIT).start();
            String out = capture(p);
            if (p.exitValue() == 0) return;
            // Retry without -F ttyName
            if (STTY_F_OPTION != null) {
                List<String> fallback = new ArrayList<String>();
                fallback.add(STTY);
                for (String a : args) fallback.add(a);
                Process p2 = new ProcessBuilder(fallback).redirectInput(Redirect.INHERIT).start();
                String out2 = capture(p2);
                if (p2.exitValue() != 0) {
                    throw new IOException("stty failed: " + out2);
                }
            } else {
                throw new IOException("stty failed: " + out);
            }
        } catch (InterruptedException e) {
            throw (IOException) new InterruptedIOException("stty interrupted").initCause(e);
        }
    }

    private List<String> buildSttyCommand(String... args) {
        List<String> cmd = new ArrayList<String>();
        cmd.add(STTY);
        if (STTY_F_OPTION != null && ttyName != null) {
            cmd.add(STTY_F_OPTION);
            cmd.add(ttyName);
        }
        for (String a : args) cmd.add(a);
        return cmd;
    }

    private static String capture(Process p) throws IOException, InterruptedException {
        ByteArrayOutputStream bout = new ByteArrayOutputStream();
        InputStream in = p.getInputStream();
        InputStream err = p.getErrorStream();
        try {
            int c;
            while ((c = in.read()) != -1) bout.write(c);
            while ((c = err.read()) != -1) bout.write(c);
            p.waitFor();
        } finally {
            try {
                in.close();
            } catch (IOException ignore) {
            }
            try {
                err.close();
            } catch (IOException ignore) {
            }
        }
        return bout.toString();
    }

    // --- Charset detection ---

    private static Charset detectCharset() {
        String[] vars = {"LC_ALL", "LC_CTYPE", "LANG"};
        for (String var : vars) {
            String value = System.getenv(var);
            if (value != null && !value.isEmpty()) {
                Charset cs = parseCharsetFromLocale(value);
                if (cs != null) return cs;
            }
        }
        return StandardCharsets.UTF_8;
    }

    private static Charset parseCharsetFromLocale(String locale) {
        String upper = locale.toUpperCase();
        if (upper.contains("UTF-8") || upper.contains("UTF8")) {
            return StandardCharsets.UTF_8;
        }
        int dot = locale.indexOf('.');
        if (dot >= 0 && dot < locale.length() - 1) {
            String part = locale.substring(dot + 1);
            int at = part.indexOf('@');
            if (at >= 0) part = part.substring(0, at);
            try {
                return Charset.forName(part);
            } catch (Exception ignore) {
            }
        }
        if ("C".equals(locale) || "POSIX".equals(locale)) {
            return StandardCharsets.UTF_8;
        }
        return null;
    }

    // --- SIGWINCH (resize) support via reflection ---

    private void registerSigwinch() {
        final LegacyUnixTerminal self = this;
        try {
            Class<?> signalClass = Class.forName("sun.misc.Signal");
            Class<?> handlerClass = Class.forName("sun.misc.SignalHandler");
            Object signal = signalClass.getConstructor(String.class).newInstance("WINCH");
            Object handler =
                    java.lang.reflect.Proxy.newProxyInstance(
                            handlerClass.getClassLoader(),
                            new Class<?>[] {handlerClass},
                            new java.lang.reflect.InvocationHandler() {
                                public Object invoke(
                                        Object proxy,
                                        java.lang.reflect.Method method,
                                        Object[] args) {
                                    if ("handle".equals(method.getName())) {
                                        Consumer<Size> h = self.resizeHandler;
                                        if (h != null) {
                                            try {
                                                h.accept(self.size());
                                            } catch (IOException ignore) {
                                            }
                                        }
                                    }
                                    return null;
                                }
                            });
            signalClass
                    .getMethod("handle", signalClass, handlerClass)
                    .invoke(null, signal, handler);
        } catch (Exception ignore) {
            // SIGWINCH not available in this JVM — resize notifications will not fire
        }
    }
}
