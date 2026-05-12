//DEPS org.codejive.miniterm:ansiparser:0.1.4
//DEPS org.codejive.miniterm:termcap:0.1.4

package examples;

import java.io.IOException;
import org.codejive.miniterm.Terminal;
import org.codejive.miniterm.termcap.TermCaps;
import org.codejive.miniterm.termcap.TermProber;

public class PrintCaps {
    public static void main(String[] args) {
        TermCaps detected = TermCaps.detect();
        System.out.println("Terminal capabilities (detected from environment):");
        printCaps(detected);

        System.out.println();
        System.out.println("Terminal capabilities (queried):");
        try (Terminal terminal = Terminal.create()) {
            terminal.enableRawMode();
            TermCaps probed = TermProber.probe(terminal, () -> terminal.read(500));
            printCaps(probed);
        } catch (IOException e) {
            System.out.println("  (query failed: " + e.getMessage() + ")");
        }
    }

    private static void printCaps(TermCaps caps) {
        System.out.println("  colors         : " + caps.colors());
        System.out.println("  altScreen      : " + caps.altScreen());
        System.out.println("  mouse          : " + caps.mouse());
        System.out.println("  bracketedPaste : " + caps.bracketedPaste());
        System.out.println("  focusTracking  : " + caps.focusTracking());
        System.out.println("  hyperlinks     : " + caps.hyperlinks());
        System.out.println("  settableTitle  : " + caps.settableTitle());
        System.out.println("  unicode        : " + caps.unicode());
        System.out.println("  italic         : " + caps.italic());
        System.out.println("  strikethrough  : " + caps.strikethrough());
        System.out.println("  overline       : " + caps.overline());
        System.out.println("  sixel          : " + caps.sixel());
        System.out.println("  kittyGraphics  : " + caps.kittyGraphics());
        System.out.println("  iterm2Images   : " + caps.iterm2Images());
    }
}
