package examples;

import java.io.IOException;
import org.codejive.miniterm.Terminal;

public class TerminalSize {
    public static void main(String[] args) {
        try (Terminal terminal = Terminal.create()) {
            System.out.println("Terminal size: " + terminal.getSize());
        } catch (IOException e) {
            e.printStackTrace();
        }
    }
}
