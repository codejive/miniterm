package org.codejive.miniterm.ansiparser;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class AnsiReaderTest {

    // ── read() ────────────────────────────────────────────────────────────────

    @Test
    void readReturnsPlainChar() throws IOException {
        assertThat(new AnsiReader(supply('A')).read()).isEqualTo("A");
    }

    @Test
    void readReturnsCsiSequence() throws IOException {
        assertThat(new AnsiReader(supply(0x1B, '[', '1', 'A')).read()).isEqualTo("\u001b[1A");
    }

    @Test
    void readReturnsNullOnEof() throws IOException {
        assertThat(new AnsiReader(supply()).read()).isNull();
    }

    @Test
    void readReturnsEmptyOnTimeout() throws IOException {
        assertThat(new AnsiReader(supply(-2)).read()).isEmpty();
    }

    @Test
    void readReturnsMultipleTokensSequentially() throws IOException {
        AnsiReader reader = new AnsiReader(supply('A', 'B', 'C'));
        assertThat(reader.read()).isEqualTo("A");
        assertThat(reader.read()).isEqualTo("B");
        assertThat(reader.read()).isEqualTo("C");
        assertThat(reader.read()).isNull();
    }

    // ── read(Appendable) ──────────────────────────────────────────────────────

    @Test
    void readAppendableAppendsPlainChar() throws IOException {
        StringBuilder sb = new StringBuilder();
        assertThat(new AnsiReader(supply('Z')).read(sb)).isEqualTo(0);
        assertThat(sb.toString()).isEqualTo("Z");
    }

    @Test
    void readAppendableAppendsCsiSequence() throws IOException {
        StringBuilder sb = new StringBuilder();
        assertThat(new AnsiReader(supply(0x1B, '[', '2', 'J')).read(sb)).isEqualTo(0);
        assertThat(sb.toString()).isEqualTo("\u001b[2J");
    }

    @Test
    void readAppendableReturnsNegativeOnEof() throws IOException {
        StringBuilder sb = new StringBuilder();
        assertThat(new AnsiReader(supply()).read(sb)).isNegative();
        assertThat(sb.toString()).isEmpty();
    }

    @Test
    void readAppendableAccumulatesMultipleTokens() throws IOException {
        AnsiReader reader = new AnsiReader(supply('A', 0x1B, '[', '1', 'A', 'B'));
        StringBuilder sb = new StringBuilder();
        assertThat(reader.read(sb)).isEqualTo(0);
        assertThat(reader.read(sb)).isEqualTo(0);
        assertThat(reader.read(sb)).isEqualTo(0);
        assertThat(sb.toString()).isEqualTo("A\u001b[1AB");
    }

    private static IntReader supply(int... chars) {
        AtomicInteger idx = new AtomicInteger();
        return () -> {
            int i = idx.getAndIncrement();
            return i < chars.length ? chars[i] : -1;
        };
    }
}
