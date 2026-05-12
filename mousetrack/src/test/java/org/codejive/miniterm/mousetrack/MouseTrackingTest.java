package org.codejive.miniterm.mousetrack;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import org.junit.jupiter.api.Test;

class MouseTrackingTest {

    // ── enable / disable ──────────────────────────────────────────────────────

    @Test
    void enableX10SendsCorrectSequence() throws IOException {
        assertThat(captureEnable(MouseTracking.Protocol.X10)).isEqualTo("\033[?9h");
    }

    @Test
    void disableX10SendsCorrectSequence() throws IOException {
        assertThat(captureDisable(MouseTracking.Protocol.X10)).isEqualTo("\033[?9l");
    }

    @Test
    void enableNormalSendsCorrectSequence() throws IOException {
        assertThat(captureEnable(MouseTracking.Protocol.NORMAL)).isEqualTo("\033[?1000h");
    }

    @Test
    void disableNormalSendsCorrectSequence() throws IOException {
        assertThat(captureDisable(MouseTracking.Protocol.NORMAL)).isEqualTo("\033[?1000l");
    }

    @Test
    void enableButtonMotionSendsCorrectSequence() throws IOException {
        assertThat(captureEnable(MouseTracking.Protocol.BUTTON_MOTION)).isEqualTo("\033[?1002h");
    }

    @Test
    void enableAnyMotionSendsCorrectSequence() throws IOException {
        assertThat(captureEnable(MouseTracking.Protocol.ANY_MOTION)).isEqualTo("\033[?1003h");
    }

    @Test
    void enableSgrEncodingSendsCorrectSequence() throws IOException {
        assertThat(captureEnableEncoding(MouseTracking.Encoding.SGR)).isEqualTo("\033[?1006h");
    }

    @Test
    void disableSgrEncodingSendsCorrectSequence() throws IOException {
        assertThat(captureDisableEncoding(MouseTracking.Encoding.SGR)).isEqualTo("\033[?1006l");
    }

    @Test
    void enableUtf8EncodingSendsCorrectSequence() throws IOException {
        assertThat(captureEnableEncoding(MouseTracking.Encoding.UTF8)).isEqualTo("\033[?1005h");
    }

    @Test
    void enableUrxvtEncodingSendsCorrectSequence() throws IOException {
        assertThat(captureEnableEncoding(MouseTracking.Encoding.URXVT)).isEqualTo("\033[?1015h");
    }

    @Test
    void enableSgrPixelsEncodingSendsCorrectSequence() throws IOException {
        assertThat(captureEnableEncoding(MouseTracking.Encoding.SGR_PIXELS))
                .isEqualTo("\033[?1016h");
    }

    @Test
    void disableSgrPixelsEncodingSendsCorrectSequence() throws IOException {
        assertThat(captureDisableEncoding(MouseTracking.Encoding.SGR_PIXELS))
                .isEqualTo("\033[?1016l");
    }

    // ── isMouseEvent ──────────────────────────────────────────────────────────

    @Test
    void nullIsNotMouseEvent() {
        assertThat(MouseTracking.isMouseEvent(null)).isFalse();
    }

    @Test
    void emptyStringIsNotMouseEvent() {
        assertThat(MouseTracking.isMouseEvent("")).isFalse();
    }

    @Test
    void plainTextIsNotMouseEvent() {
        assertThat(MouseTracking.isMouseEvent("hello")).isFalse();
    }

    @Test
    void cursorUpIsNotMouseEvent() {
        assertThat(MouseTracking.isMouseEvent("\033[A")).isFalse();
    }

    @Test
    void legacyMouseEventIsRecognised() {
        // ESC [ M <32> <33> <34> — left press at (1,2)
        assertThat(MouseTracking.isMouseEvent("\033[M" + (char) 32 + (char) 33 + (char) 34))
                .isTrue();
    }

    @Test
    void legacySequenceWithWrongLengthIsNotMouseEvent() {
        // Only 5 chars (one payload byte missing)
        assertThat(MouseTracking.isMouseEvent("\033[M" + (char) 32 + (char) 33)).isFalse();
    }

    @Test
    void sgrPressIsRecognised() {
        assertThat(MouseTracking.isMouseEvent("\033[<0;10;20M")).isTrue();
    }

    @Test
    void sgrReleaseIsRecognised() {
        assertThat(MouseTracking.isMouseEvent("\033[<0;10;20m")).isTrue();
    }

    @Test
    void urxvtPressIsRecognised() {
        assertThat(MouseTracking.isMouseEvent("\033[32;10;20M")).isTrue();
    }

    // ── parse — legacy X10 ────────────────────────────────────────────────────

    @Test
    void parseNullReturnsNull() {
        assertThat(MouseTracking.parse(null)).isNull();
    }

    @Test
    void parseLegacyLeftPress() {
        // button byte = 0 + 32 = 32, x = 1+32 = 33, y = 2+32 = 34
        MouseEvent ev =
                MouseTracking.parse(
                        "\033[M" + (char) (0 + 32) + (char) (10 + 32) + (char) (5 + 32));
        assertThat(ev).isNotNull();
        assertThat(ev.type()).isEqualTo(MouseEvent.Type.PRESS);
        assertThat(ev.button()).isEqualTo(MouseEvent.Button.LEFT);
        assertThat(ev.x()).isEqualTo(10);
        assertThat(ev.y()).isEqualTo(5);
        assertThat(ev.shift()).isFalse();
        assertThat(ev.alt()).isFalse();
        assertThat(ev.ctrl()).isFalse();
    }

    @Test
    void parseLegacyMiddlePress() {
        // button byte = 1 + 32
        MouseEvent ev =
                MouseTracking.parse("\033[M" + (char) (1 + 32) + (char) (3 + 32) + (char) (7 + 32));
        assertThat(ev).isNotNull();
        assertThat(ev.type()).isEqualTo(MouseEvent.Type.PRESS);
        assertThat(ev.button()).isEqualTo(MouseEvent.Button.MIDDLE);
        assertThat(ev.x()).isEqualTo(3);
        assertThat(ev.y()).isEqualTo(7);
    }

    @Test
    void parseLegacyRightPress() {
        MouseEvent ev =
                MouseTracking.parse("\033[M" + (char) (2 + 32) + (char) (1 + 32) + (char) (1 + 32));
        assertThat(ev).isNotNull();
        assertThat(ev.type()).isEqualTo(MouseEvent.Type.PRESS);
        assertThat(ev.button()).isEqualTo(MouseEvent.Button.RIGHT);
    }

    @Test
    void parseLegacyRelease() {
        // button byte = 3 + 32 → release (button not identified)
        MouseEvent ev =
                MouseTracking.parse("\033[M" + (char) (3 + 32) + (char) (5 + 32) + (char) (5 + 32));
        assertThat(ev).isNotNull();
        assertThat(ev.type()).isEqualTo(MouseEvent.Type.RELEASE);
        assertThat(ev.button()).isEqualTo(MouseEvent.Button.NONE);
    }

    @Test
    void parseLegacyScrollUp() {
        // button byte = 64 + 32
        MouseEvent ev =
                MouseTracking.parse(
                        "\033[M" + (char) (64 + 32) + (char) (1 + 32) + (char) (1 + 32));
        assertThat(ev).isNotNull();
        assertThat(ev.type()).isEqualTo(MouseEvent.Type.SCROLL);
        assertThat(ev.button()).isEqualTo(MouseEvent.Button.SCROLL_UP);
    }

    @Test
    void parseLegacyScrollDown() {
        // button byte = 65 + 32
        MouseEvent ev =
                MouseTracking.parse(
                        "\033[M" + (char) (65 + 32) + (char) (1 + 32) + (char) (1 + 32));
        assertThat(ev).isNotNull();
        assertThat(ev.type()).isEqualTo(MouseEvent.Type.SCROLL);
        assertThat(ev.button()).isEqualTo(MouseEvent.Button.SCROLL_DOWN);
    }

    @Test
    void parseLegacyShiftModifier() {
        // button byte = 0 (left) | 0x04 (shift) + 32
        MouseEvent ev =
                MouseTracking.parse(
                        "\033[M" + (char) (0 | 0x04 | 32) + (char) (1 + 32) + (char) (1 + 32));
        assertThat(ev).isNotNull();
        assertThat(ev.shift()).isTrue();
        assertThat(ev.alt()).isFalse();
        assertThat(ev.ctrl()).isFalse();
    }

    @Test
    void parseLegacyAltModifier() {
        MouseEvent ev =
                MouseTracking.parse(
                        "\033[M" + (char) (0 | 0x08 | 32) + (char) (1 + 32) + (char) (1 + 32));
        assertThat(ev).isNotNull();
        assertThat(ev.shift()).isFalse();
        assertThat(ev.alt()).isTrue();
        assertThat(ev.ctrl()).isFalse();
    }

    @Test
    void parseLegacyCtrlModifier() {
        MouseEvent ev =
                MouseTracking.parse(
                        "\033[M" + (char) (0 | 0x10 | 32) + (char) (1 + 32) + (char) (1 + 32));
        assertThat(ev).isNotNull();
        assertThat(ev.shift()).isFalse();
        assertThat(ev.alt()).isFalse();
        assertThat(ev.ctrl()).isTrue();
    }

    @Test
    void parseLegacyDrag() {
        // raw button byte = 0 (left) | 0x20 (motion) = 32; terminal sends char(32 + 32) = char(64)
        MouseEvent ev =
                MouseTracking.parse(
                        "\033[M" + (char) ((0 | 0x20) + 32) + (char) (5 + 32) + (char) (3 + 32));
        assertThat(ev).isNotNull();
        assertThat(ev.type()).isEqualTo(MouseEvent.Type.DRAG);
        assertThat(ev.button()).isEqualTo(MouseEvent.Button.LEFT);
        assertThat(ev.x()).isEqualTo(5);
        assertThat(ev.y()).isEqualTo(3);
    }

    @Test
    void parseLegacyMove() {
        // raw button byte = 3 (no button) | 0x20 (motion) = 35; terminal sends char(35 + 32) =
        // char(67)
        MouseEvent ev =
                MouseTracking.parse(
                        "\033[M" + (char) ((3 | 0x20) + 32) + (char) (8 + 32) + (char) (4 + 32));
        assertThat(ev).isNotNull();
        assertThat(ev.type()).isEqualTo(MouseEvent.Type.MOVE);
        assertThat(ev.button()).isEqualTo(MouseEvent.Button.NONE);
    }

    // ── parse — SGR ───────────────────────────────────────────────────────────

    @Test
    void parseSgrLeftPress() {
        MouseEvent ev = MouseTracking.parse("\033[<0;10;20M");
        assertThat(ev).isNotNull();
        assertThat(ev.type()).isEqualTo(MouseEvent.Type.PRESS);
        assertThat(ev.button()).isEqualTo(MouseEvent.Button.LEFT);
        assertThat(ev.x()).isEqualTo(10);
        assertThat(ev.y()).isEqualTo(20);
    }

    @Test
    void parseSgrLeftRelease() {
        MouseEvent ev = MouseTracking.parse("\033[<0;10;20m");
        assertThat(ev).isNotNull();
        assertThat(ev.type()).isEqualTo(MouseEvent.Type.RELEASE);
        assertThat(ev.button()).isEqualTo(MouseEvent.Button.LEFT);
        assertThat(ev.x()).isEqualTo(10);
        assertThat(ev.y()).isEqualTo(20);
    }

    @Test
    void parseSgrMiddlePress() {
        MouseEvent ev = MouseTracking.parse("\033[<1;1;1M");
        assertThat(ev).isNotNull();
        assertThat(ev.type()).isEqualTo(MouseEvent.Type.PRESS);
        assertThat(ev.button()).isEqualTo(MouseEvent.Button.MIDDLE);
    }

    @Test
    void parseSgrRightPress() {
        MouseEvent ev = MouseTracking.parse("\033[<2;1;1M");
        assertThat(ev).isNotNull();
        assertThat(ev.type()).isEqualTo(MouseEvent.Type.PRESS);
        assertThat(ev.button()).isEqualTo(MouseEvent.Button.RIGHT);
    }

    @Test
    void parseSgrScrollUp() {
        MouseEvent ev = MouseTracking.parse("\033[<64;1;1M");
        assertThat(ev).isNotNull();
        assertThat(ev.type()).isEqualTo(MouseEvent.Type.SCROLL);
        assertThat(ev.button()).isEqualTo(MouseEvent.Button.SCROLL_UP);
    }

    @Test
    void parseSgrScrollDown() {
        MouseEvent ev = MouseTracking.parse("\033[<65;1;1M");
        assertThat(ev).isNotNull();
        assertThat(ev.type()).isEqualTo(MouseEvent.Type.SCROLL);
        assertThat(ev.button()).isEqualTo(MouseEvent.Button.SCROLL_DOWN);
    }

    @Test
    void parseSgrDrag() {
        // motion bit set, button 0 (left)
        MouseEvent ev = MouseTracking.parse("\033[<32;15;8M");
        assertThat(ev).isNotNull();
        assertThat(ev.type()).isEqualTo(MouseEvent.Type.DRAG);
        assertThat(ev.button()).isEqualTo(MouseEvent.Button.LEFT);
        assertThat(ev.x()).isEqualTo(15);
        assertThat(ev.y()).isEqualTo(8);
    }

    @Test
    void parseSgrMove() {
        // motion bit | button index 3 = hover
        MouseEvent ev = MouseTracking.parse("\033[<35;5;5M");
        assertThat(ev).isNotNull();
        assertThat(ev.type()).isEqualTo(MouseEvent.Type.MOVE);
        assertThat(ev.button()).isEqualTo(MouseEvent.Button.NONE);
    }

    @Test
    void parseSgrShiftModifier() {
        MouseEvent ev = MouseTracking.parse("\033[<4;1;1M");
        assertThat(ev).isNotNull();
        assertThat(ev.shift()).isTrue();
    }

    @Test
    void parseSgrLargeCoordinates() {
        MouseEvent ev = MouseTracking.parse("\033[<0;300;200M");
        assertThat(ev).isNotNull();
        assertThat(ev.x()).isEqualTo(300);
        assertThat(ev.y()).isEqualTo(200);
    }

    @Test
    void parseSgrMissingParameterReturnsNull() {
        assertThat(MouseTracking.parse("\033[<0;10M")).isNull();
    }

    @Test
    void parseSgrExtraParameterReturnsNull() {
        assertThat(MouseTracking.parse("\033[<0;10;20;30M")).isNull();
    }

    // ── parse — URXVT ─────────────────────────────────────────────────────────

    @Test
    void parseUrxvtLeftPress() {
        // Pb = 32 = 0x20 = motion bit? No: Pb=32 = left press (same as SGR Pb=0 for left... wait)
        // In URXVT, Pb is the raw button byte without +32 offset:
        // left press = 0, middle = 1, right = 2, release = 3, scroll up = 64
        MouseEvent ev = MouseTracking.parse("\033[0;10;20M");
        assertThat(ev).isNotNull();
        assertThat(ev.type()).isEqualTo(MouseEvent.Type.PRESS);
        assertThat(ev.button()).isEqualTo(MouseEvent.Button.LEFT);
        assertThat(ev.x()).isEqualTo(10);
        assertThat(ev.y()).isEqualTo(20);
    }

    @Test
    void parseUrxvtRightPress() {
        MouseEvent ev = MouseTracking.parse("\033[2;5;8M");
        assertThat(ev).isNotNull();
        assertThat(ev.type()).isEqualTo(MouseEvent.Type.PRESS);
        assertThat(ev.button()).isEqualTo(MouseEvent.Button.RIGHT);
        assertThat(ev.x()).isEqualTo(5);
        assertThat(ev.y()).isEqualTo(8);
    }

    @Test
    void parseUrxvtRelease() {
        // Pb = 3 = release in non-SGR protocol
        MouseEvent ev = MouseTracking.parse("\033[3;10;10M");
        assertThat(ev).isNotNull();
        assertThat(ev.type()).isEqualTo(MouseEvent.Type.RELEASE);
        assertThat(ev.button()).isEqualTo(MouseEvent.Button.NONE);
    }

    @Test
    void parseUrxvtScrollUp() {
        MouseEvent ev = MouseTracking.parse("\033[64;1;1M");
        assertThat(ev).isNotNull();
        assertThat(ev.type()).isEqualTo(MouseEvent.Type.SCROLL);
        assertThat(ev.button()).isEqualTo(MouseEvent.Button.SCROLL_UP);
    }

    @Test
    void parseUrxvtLargeCoordinates() {
        MouseEvent ev = MouseTracking.parse("\033[0;250;150M");
        assertThat(ev).isNotNull();
        assertThat(ev.x()).isEqualTo(250);
        assertThat(ev.y()).isEqualTo(150);
    }

    @Test
    void parseUrxvtMissingParameterReturnsNull() {
        assertThat(MouseTracking.parse("\033[0;10M")).isNull();
    }

    // ── toString ──────────────────────────────────────────────────────────────

    @Test
    void mouseEventToString() {
        MouseEvent ev = MouseTracking.parse("\033[<0;5;3M");
        assertThat(ev).isNotNull();
        String s = ev.toString();
        assertThat(s).contains("PRESS");
        assertThat(s).contains("LEFT");
        assertThat(s).contains("x=5");
        assertThat(s).contains("y=3");
    }

    @Test
    void mouseEventToStringWithModifiers() {
        // shift + alt + left press
        MouseEvent ev = MouseTracking.parse("\033[<12;1;1M");
        assertThat(ev).isNotNull();
        String s = ev.toString();
        assertThat(s).contains("shift");
        assertThat(s).contains("alt");
    }

    // ── helpers ───────────────────────────────────────────────────────────────

    private static String captureEnable(MouseTracking.Protocol protocol) throws IOException {
        StringBuilder sb = new StringBuilder();
        MouseTracking.enable(sb, protocol);
        return sb.toString();
    }

    private static String captureDisable(MouseTracking.Protocol protocol) throws IOException {
        StringBuilder sb = new StringBuilder();
        MouseTracking.disable(sb, protocol);
        return sb.toString();
    }

    private static String captureEnableEncoding(MouseTracking.Encoding encoding)
            throws IOException {
        StringBuilder sb = new StringBuilder();
        MouseTracking.enableEncoding(sb, encoding);
        return sb.toString();
    }

    private static String captureDisableEncoding(MouseTracking.Encoding encoding)
            throws IOException {
        StringBuilder sb = new StringBuilder();
        MouseTracking.disableEncoding(sb, encoding);
        return sb.toString();
    }
}
