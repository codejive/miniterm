//DEPS org.codejive.miniterm:colors:0.1.2-SNAPSHOT

package examples;

import java.io.IOException;
import org.codejive.miniterm.Terminal;
import org.codejive.miniterm.colors.Color;
import org.codejive.miniterm.colors.TermColors;

public class PrintColors {
    public static void main(String[] args) {
        try (Terminal terminal = Terminal.create()) {
            terminal.enableRawMode();
            Color fg = TermColors.queryForeground(terminal, () -> terminal.read(500));
            Color bg = TermColors.queryBackground(terminal, () -> terminal.read(500));
            Color cursor = TermColors.queryCursor(terminal, () -> terminal.read(500));
            System.out.println("Terminal colors (queried via OSC):");
            System.out.println("  foreground : " + (fg != null ? fg : "not reported"));
            System.out.println("  background : " + (bg != null ? bg : "not reported"));
            System.out.println("  cursor     : " + (cursor != null ? cursor : "not reported"));
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}
