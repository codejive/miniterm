///usr/bin/env jbang "$0" "$@" ; exit $?
//DEPS org.codejive.miniterm:miniterm${miniterm.ffm:}:${miniterm.version:0.1.5}

package examples;

import java.io.IOException;
import org.codejive.miniterm.Terminal;

public class WatchSize {
    public static void main(String[] args) {
        try (Terminal terminal = Terminal.create()) {
            System.out.println("Terminal size: " + terminal.size());
            terminal.enableRawMode();
            terminal.onResize(size -> System.out.println("New terminal size: " + size));
            System.out.println("Try resizing your terminal... (press Ctrl+C to exit)");
            while (true) {
                int key = terminal.read(1000);
                if (key == -1 || key == 3) { // Ctrl+C
                    break; // End of stream
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}
