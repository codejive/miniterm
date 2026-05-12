//DEPS org.codejive.miniterm:ansiparser:0.1.5
//DEPS org.codejive.miniterm:termcap:0.1.5

package examples;

import java.io.IOException;
import org.codejive.miniterm.Terminal;
import org.codejive.miniterm.ansiparser.AnsiReader;
import org.codejive.miniterm.termcap.TermCaps;

public class PrintFocus {
    public static void main(String[] args) {
        try (Terminal terminal = Terminal.create()) {
            terminal.enableRawMode();
            TermCaps.enableFocusTracking(terminal);
            try {
                System.out.println("Focus the terminal window (Ctrl+C to exit):");
                AnsiReader reader = new AnsiReader(() -> terminal.read(-1));
                String token;
                while ((token = reader.read()) != null) {
                    if (token.isEmpty()) continue;
                    if (!token.startsWith("\033") && token.charAt(0) == 3) break; // Ctrl+C
                    if (TermCaps.isFocusIn(token)) {
                        System.out.println("Focus IN");
                    } else if (TermCaps.isFocusOut(token)) {
                        System.out.println("Focus OUT");
                    }
                }
            } finally {
                TermCaps.disableFocusTracking(terminal);
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}
