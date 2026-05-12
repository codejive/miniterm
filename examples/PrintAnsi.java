//DEPS org.codejive.miniterm:ansiparser:0.1.4

package examples;

import java.io.IOException;
import org.codejive.miniterm.Terminal;
import org.codejive.miniterm.ansiparser.AnsiReader;

public class PrintAnsi {
    public static void main(String[] args) {
        try (Terminal terminal = Terminal.create()) {
            terminal.enableRawMode();
            System.out.println("Press keys (Ctrl+C to exit):");
            AnsiReader reader = new AnsiReader(() -> terminal.read(-1));
            String token;
            while ((token = reader.read()) != null) {
                if (token.isEmpty()) {
                    System.out.println("Timeout");
                    continue;
                } else if (token.startsWith("\u001b")) {
                    if (token.equals("\u001b[A")) {
                        System.out.println("Up arrow");
                    } else if (token.equals("\u001b[B")) {
                        System.out.println("Down arrow");
                    } else if (token.equals("\u001b[C")) {
                        System.out.println("Right arrow");
                    } else if (token.equals("\u001b[D")) {
                        System.out.println("Left arrow");
                    } else {
                        System.out.println("Sequence: " + toHex(token));
                    }
                } else if (token.charAt(0) == 3) {
                    System.out.println("CTRL+C pressed: exiting...");
                    break; // Ctrl+C
                } else {
                    System.out.println("Key pressed: " + token);
                }
            }
        } catch (IOException e) {
            e.printStackTrace();
        }
    }

    private static String toHex(String s) {
        StringBuilder sb = new StringBuilder();
        for (char c : s.toCharArray()) {
            if (c >= 0x20 && c < 0x7f) {
                sb.append(c);
            } else {
                sb.append(String.format("\\x%02x", (int) c));
            }
        }
        return sb.toString();
    }
}
