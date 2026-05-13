///usr/bin/env jbang "$0" "$@" ; exit $?
//DEPS org.codejive.miniterm:miniterm${miniterm.ffm:}:${miniterm.version:0.1.5}

package examples;

import java.io.IOException;
import org.codejive.miniterm.Terminal;

public class PrintKeys {
    public static void main(String[] args) {
        try (Terminal terminal = Terminal.create()) {
            terminal.enableRawMode();
            System.out.println("Press keys (Ctrl+C to exit):");
            while (true) {
                int key = terminal.read(1000);
                if (key == -1 || key == 3) { // Ctrl+C
                    break; // End of stream
                } else if (key >= 0) {
                    System.out.println("Key pressed: " + key);
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}
