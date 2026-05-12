//DEPS org.codejive.miniterm:ansiparser:0.1.1-SNAPSHOT
//DEPS org.codejive.miniterm:mousetrack:0.1.1-SNAPSHOT

package examples;

import java.io.IOException;
import org.codejive.miniterm.Terminal;
import org.codejive.miniterm.ansiparser.AnsiReader;
import org.codejive.miniterm.mousetrack.MouseEvent;
import org.codejive.miniterm.mousetrack.MouseTracking;

public class PrintMouse {

    // ANSI helpers
    private static final String CSI = "\033[";
    private static final String CURSOR_UP = CSI + "A";
    private static final String ERASE_EOL = CSI + "K";

    public static void main(String[] args) {
        try (Terminal terminal = Terminal.create()) {
            terminal.enableRawMode();
            MouseTracking.enable(terminal, MouseTracking.Protocol.ANY_MOTION);
            MouseTracking.enableEncoding(terminal, MouseTracking.Encoding.SGR);
            try {
                // Reserve 5 lines and print the initial (empty) view
                printView(terminal, null);

                AnsiReader reader = new AnsiReader(() -> terminal.read(-1));
                String token;
                while ((token = reader.read()) != null) {
                    if (token.isEmpty()) continue;
                    if (!token.startsWith("\033") && token.charAt(0) == 3) break; // Ctrl+C
                    if (MouseTracking.isMouseEvent(token)) {
                        MouseEvent ev = MouseTracking.parse(token);
                        // Move cursor back up 5 lines to overwrite the previous view
                        terminal.write(CURSOR_UP.repeat(5));
                        printView(terminal, ev);
                    }
                }
            } finally {
                MouseTracking.disableEncoding(terminal, MouseTracking.Encoding.SGR);
                MouseTracking.disable(terminal, MouseTracking.Protocol.ANY_MOTION);
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private static void printView(Terminal terminal, MouseEvent ev) throws IOException {
        String type     = ev == null ? "-" : ev.type().name();
        String button   = ev == null ? "-" : ev.button().name();
        String position = ev == null ? "-" : ev.x() + ", " + ev.y();
        String mods     = ev == null ? "-" : modifiers(ev);

        writeLine(terminal, "Type:      " + type);
        writeLine(terminal, "Button:    " + button);
        writeLine(terminal, "Position:  " + position);
        writeLine(terminal, "Modifiers: " + mods);
        writeLine(terminal, "(move the mouse, click or scroll — Ctrl+C to exit)");
    }

    private static void writeLine(Terminal terminal, String text) throws IOException {
        terminal.write(text + ERASE_EOL + "\n");
    }

    private static String modifiers(MouseEvent ev) {
        StringBuilder sb = new StringBuilder();
        if (ev.shift()) sb.append("SHIFT ");
        if (ev.alt())   sb.append("ALT ");
        if (ev.ctrl())  sb.append("CTRL ");
        return sb.length() == 0 ? "-" : sb.toString().trim();
    }
}
