/*
 * Copyright TamboUI and miniterm Contributors
 * SPDX-License-Identifier: MIT
 */
package org.codejive.miniterm;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import org.junit.jupiter.api.Test;

class TerminalIT {

    @Test
    void terminalCanBeCreatedAndClosed() throws IOException {
        Terminal terminal = Terminal.create();
        try {
            assertThat(terminal).isNotNull();
            assertThat(terminal.charset()).isNotNull();
            assertThat(terminal.rawModeEnabled()).isFalse();
        } finally {
            terminal.close();
        }
    }
}
