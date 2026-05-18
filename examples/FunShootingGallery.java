///usr/bin/env jbang "$0" "$@" ; exit $?
//DEPS org.codejive.miniterm:miniterm${miniterm.ffm:}:${miniterm.version:0.1.5}
//DEPS org.codejive.miniterm:ansiparser:${miniterm.version:0.1.5}
//DEPS org.codejive.miniterm:mousetrack:${miniterm.version:0.1.5}

package examples;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.util.*;
import java.util.function.BiFunction;
import org.codejive.miniterm.Terminal;
import org.codejive.miniterm.ansiparser.AnsiReader;
import org.codejive.miniterm.mousetrack.MouseEvent;
import org.codejive.miniterm.mousetrack.MouseTracking;

public class FunShootingGallery {

    // ── ANSI constants ────────────────────────────────────────────────────────
    private static final String ESC   = "\033";
    private static final String CSI   = ESC + "[";
    private static final String RESET = CSI + "0m";

    // ── Target definition ─────────────────────────────────────────────────────

    // Background colours for target layers (foreground stays default/white)
    private static final String CY = CSI + "106m";  // bright cyan bg   — outer ring  (10 pts each)
    private static final String YL = CSI + "103m";  // bright yellow bg — middle ring (25 pts each)
    private static final String RD = CSI + "1;101m"; // bold bright red bg — bullseye  (50 pts)

    /**
     * Visual lines of the target (5 visible chars wide × 5 lines tall).
     * ANSI colour codes are embedded; only the visible characters count for positioning.
     *
     *   .....
     * ..#####..
     * ..##*##..
     * ..#####..
     *   .....
     */
    private static final String[] TARGET_VISUAL = {
        "  " + CY + "     " + RESET,
        CY + "  " + RD + "     " + CY + "  " + RESET,
        CY + "  " + RD + "  " + YL + " " + RESET + RD + "  " + CY + "  " + RESET,
        CY + "  " + RD + "     " + CY + "  " + RESET,
        "  " + CY + "     " + RESET
    };

    /**
     * Hit-box for the target — same visible dimensions as TARGET_VISUAL.
     *   ' ' = miss (0 pts)
     *   '.' = 10 pts
     *   '#' = 25 pts
     *   '*' = 50 pts
     */
    private static final String[] TARGET_HITBOX = {
        "  .....  ",
        "..#####..",
        "..##*##..",
        "..#####..",
        "  .....  "
    };

    private static final String[] PLAY_BUTTON_VISUAL = {
            "╔════════════╗",
            "║ Play Again ║",
            "╚════════════╝"
    };

    private static final String[] EXIT_BUTTON_VISUAL = {
            "╔════════════╗",
            "║ Exit  Game ║",
            "╚════════════╝"
    };

    // ── Game constants ────────────────────────────────────────────────────────
    private static final int  MAX_TARGETS    = 5;
    private static final long MIN_LIFE_MS    = 1_000;
    private static final long MAX_LIFE_MS    = 3_000;
    private static final long SPAWN_EVERY_MS = 800;

    // ── Game state ────────────────────────────────────────────────────────────
    private static final class ActiveTarget {
        final int  x, y;
        final int  width, height;
        final String[] visual;
        final long expiresAt;
        final BiFunction<Integer, Integer, Boolean> onHit;
        ActiveTarget(int x, int y, int width, int height, String[] visual, long expiresAt,
                BiFunction<Integer, Integer, Boolean> onHit) {
            this.x = x;
            this.y = y;
            this.width  = width;
            this.height = height;
            this.visual = visual;
            this.expiresAt = expiresAt;
            this.onHit = onHit;
        }
    }

    private static final int  MAX_MISSES     = 10;

    private static Terminal terminal;
    private static int  cols, rows;
    private static final List<ActiveTarget> targets  = new ArrayList<>();
    private static int  score     = 0;
    private static int  misses    = 0;
    private static int  hits      = 0;
    private static long    lastSpawn = 0;
    private static boolean gameOver  = false;
    private static boolean restart   = false;
    private static boolean breakLoop = false;
    private static final Random rng = new Random();

    // Speed scales up with each hit: targets live shorter, spawn faster
    private static long spawnInterval() { return Math.max(200, SPAWN_EVERY_MS - hits * 20L); }
    private static long minLife()       { return Math.max(400, MIN_LIFE_MS    - hits * 20L); }
    private static long maxLife()       { return Math.max(800, MAX_LIFE_MS    - hits * 40L); }

    // ── Entry point ───────────────────────────────────────────────────────────
    public static void main(String[] args) throws Exception {
        try (Terminal term = Terminal.create()) {
            terminal = term;
            var sz = terminal.size();
            cols = sz.width;
            rows = sz.height;

            terminal.enableRawMode();
            terminal.onResize(s -> { cols = s.width; rows = s.height; });

            // Button-click mouse tracking with SGR extended coordinates
            MouseTracking.enable(terminal, MouseTracking.Protocol.BUTTON_MOTION);
            MouseTracking.enableEncoding(terminal, MouseTracking.Encoding.SGR);

            // Enter alternate screen, hide cursor
            terminal.write(CSI + "?1049h" + CSI + "?25l");

            try {
                do {
                    // Reset state for (re)start
                    gameOver = false; restart = false; breakLoop = false;
                    score = 0; misses = 0; hits = 0; lastSpawn = 0;
                    targets.clear();
                    terminal.write(CSI + "2J");
                    drawScore();

                    // terminal.read(50) returns -2 on timeout and -1 on EOF.
                    // AnsiReader maps -2 (timeout) → "" and -1 (EOF) → null.
                    AnsiReader reader = new AnsiReader(() -> terminal.read(50));
                    String token;
                    while ((token = reader.read()) != null) {
                        long now = System.currentTimeMillis();

                        // ── Handle input ─────────────────────────────────────────
                        if (!token.isEmpty()) {
                            if (!token.startsWith(ESC) && token.charAt(0) == 3) break; // Ctrl+C
                            if (MouseTracking.isMouseEvent(token)) {
                                MouseEvent ev = MouseTracking.parse(token);
                                if (ev.type() == MouseEvent.Type.PRESS) {
                                    handleClick(ev);
                                    if (breakLoop) break;
                                }
                            }
                        }

                        if (!gameOver) {
                            // ── Expire old targets ────────────────────────────────────
                            Iterator<ActiveTarget> it = targets.iterator();
                            while (it.hasNext()) {
                                ActiveTarget t = it.next();
                                if (now >= t.expiresAt) {
                                    eraseTarget(t);
                                    it.remove();
                                    score = Math.max(0, score - 5); // penalty for letting a target escape
                                    drawScore();
                                }
                            }

                            // ── Spawn a new target every spawnInterval() ──────────────
                            if (now - lastSpawn >= spawnInterval()) {
                                lastSpawn = now;
                                if (targets.size() < MAX_TARGETS) {
                                    spawnTarget();
                                }
                            }
                        }
                    }
                } while (restart);
            } finally {
                MouseTracking.disableEncoding(terminal, MouseTracking.Encoding.SGR);
                MouseTracking.disable(terminal, MouseTracking.Protocol.BUTTON_MOTION);
                // Exit alternate screen, restore cursor
                terminal.write(CSI + "?1049l" + CSI + "?25h");
            }
        }
        System.out.println("Final score: " + score);
    }

    // ── Game logic ────────────────────────────────────────────────────────────
    private static void handleClick(MouseEvent ev) throws IOException {
        int cx = ev.x();
        int cy = ev.y();
        Iterator<ActiveTarget> it = targets.iterator();
        while (it.hasNext()) {
            ActiveTarget t = it.next();
            int lx = cx - t.x; // column within target (0-based)
            int ly = cy - t.y; // row within target    (0-based)
            if (lx >= 0 && lx < t.width && ly >= 0 && ly < t.height) {
                if (t.onHit.apply(lx, ly)) {
                    eraseTarget(t);
                    it.remove();
                    return;
                }
            }
        }
        // No target was hit — count as miss
        if (!gameOver) {
            misses++;
            drawScore();
            if (misses >= MAX_MISSES) {
                gameOver = true;
                drawGameOver();
            }
        }
    }

    private static int scoreForChar(char c) {
        if (c == '.') return 10;
        if (c == '#') return 25;
        if (c == '*') return 50;
        return 0; // space = miss
    }

    private static void spawnTarget() throws IOException {
        int width  = TARGET_HITBOX[0].length();
        int height = TARGET_HITBOX.length;
        int minX = 1,  maxX = cols - width;
        int minY = 3,  maxY = rows - height; // rows 1-2 are the status bar
        if (maxX < minX || maxY < minY) return; // terminal too small

        for (int attempt = 0; attempt < 20; attempt++) {
            int nx = minX + rng.nextInt(maxX - minX + 1);
            int ny = minY + rng.nextInt(maxY - minY + 1);

            boolean overlaps = false;
            for (ActiveTarget t : targets) {
                // Add a 1-cell gap so targets are visually separated
                if (nx < t.x + t.width + 1 && nx + width + 1 > t.x
                        && ny < t.y + t.height + 1 && ny + height + 1 > t.y) {
                    overlaps = true;
                    break;
                }
            }
            if (!overlaps) {
                long life = minLife() + rng.nextInt((int)(maxLife() - minLife()) + 1);
                ActiveTarget t = new ActiveTarget(nx, ny, width, height, TARGET_VISUAL, System.currentTimeMillis() + life,
                        (lx, ly) -> {
                            int pts = scoreForChar(TARGET_HITBOX[ly].charAt(lx));
                            if (pts > 0) {
                                score += pts;
                                hits++;
                                drawScore();
                                return true;
                            }
                            return false;
                        });
                targets.add(t);
                drawTarget(t);
                return;
            }
        }
    }

    // ── Rendering ─────────────────────────────────────────────────────────────
    private static void drawTarget(ActiveTarget t) throws IOException {
        for (int i = 0; i < t.height; i++) {
            terminal.write(moveTo(t.x, t.y + i) + t.visual[i]);
        }
        terminal.write(RESET);
    }

    private static void eraseTarget(ActiveTarget t) throws IOException {
        String blank = " ".repeat(t.width);
        for (int i = 0; i < t.height; i++) {
            terminal.write(moveTo(t.x, t.y + i) + blank);
        }
    }

    private static void drawScore() {
        String scoreStr  = "  SCORE: " + score + "  ";
        String missStr   = "  MISSES: " + misses + "/" + MAX_MISSES + "  ";
        String help      = "  Click targets to score! Ctrl+C to quit  ";
        int missCol      = scoreStr.length() + 1;
        int helpCol      = Math.max(missCol + missStr.length() + 1, cols - help.length() + 1);
        try {
            terminal.write(
                    moveTo(1, 1) + CSI + "1;33m" + scoreStr + RESET +
                    moveTo(missCol, 1) + CSI + "1;91m" + missStr + RESET +
                    moveTo(helpCol, 1) + CSI + "90m" + help + RESET);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static void drawGameOver() {
        String[] lines = {
            "  ██████╗  █████╗ ███╗   ███╗███████╗  ",
            "  ██╔════╝ ██╔══██╗████╗ ████║██╔════╝ ",
            "  ██║  ███╗███████║██╔████╔██║█████╗   ",
            "  ██║   ██║██╔══██║██║╚██╔╝██║██╔══╝   ",
            "  ╚██████╔╝██║  ██║██║ ╚═╝ ██║███████╗ ",
            "  ╚═════╝ ╚═╝  ╚═╝╚═╝     ╚═╝╚══════╝  ",
            "                                       ",
            "   ██████╗ ██╗   ██╗███████╗██████╗    ",
            "  ██╔═══██╗██║   ██║██╔════╝██╔══██╗   ",
            "  ██║   ██║██║   ██║█████╗  ██████╔╝   ",
            "  ██║   ██║╚██╗ ██╔╝██╔══╝  ██╔══██╗   ",
            "  ╚██████╔╝ ╚████╔╝ ███████╗██║  ██║   ",
            "   ╚═════╝   ╚═══╝  ╚══════╝╚═╝  ╚═╝   ",
            "                                       ",
            "        Score: " + score + "   Misses: " + misses
        };
        int startRow = Math.max(1, (rows - lines.length) / 2);
        int maxLen   = 0;
        for (String l : lines) maxLen = Math.max(maxLen, l.length());
        int startCol = Math.max(1, (cols - maxLen) / 2);
        try {
            terminal.write(CSI + "1;31m");
            for (int i = 0; i < lines.length; i++) {
                terminal.write(moveTo(startCol, startRow + i) + lines[i]);
            }
            terminal.write(RESET);

            // Clear the list of existing game targets (but don't erase them)
            targets.clear();

            // Now show button choices
            int btnRow    = startRow + lines.length + 2;
            int btnWidth  = 14; // both buttons have the same width
            int btnHeight = 3;  // both buttons have the same height
            int gap       = 4;
            int playCol   = Math.max(1, (cols - btnWidth * 2 - gap) / 2);
            int exitCol   = playCol + btnWidth + gap;

            ActiveTarget playBtn = new ActiveTarget(playCol, btnRow, btnWidth, btnHeight,
                    PLAY_BUTTON_VISUAL, Long.MAX_VALUE,
                    (lx, ly) -> { restart = true; breakLoop = true; return true; });
            targets.add(playBtn);
            drawTarget(playBtn);

            ActiveTarget exitBtn = new ActiveTarget(exitCol, btnRow, btnWidth, btnHeight,
                    EXIT_BUTTON_VISUAL, Long.MAX_VALUE,
                    (lx, ly) -> { breakLoop = true; return true; });
            targets.add(exitBtn);
            drawTarget(exitBtn);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    private static String moveTo(int col, int row) {
        return CSI + row + ";" + col + "H";
    }
}
