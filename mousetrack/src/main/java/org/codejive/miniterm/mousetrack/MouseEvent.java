package org.codejive.miniterm.mousetrack;

/**
 * Immutable representation of a terminal mouse event.
 *
 * <p>Instances are produced by {@link MouseTracking#parse(String)}.
 */
public final class MouseEvent {

    /** The kind of mouse action that occurred. */
    public enum Type {
        /** A mouse button was pressed down. */
        PRESS,
        /** A previously pressed mouse button was released. */
        RELEASE,
        /** The mouse was moved while no button was held (hover). */
        MOVE,
        /** The mouse was moved while at least one button was held (drag). */
        DRAG,
        /** The scroll wheel was rotated. */
        SCROLL
    }

    /** The mouse button associated with the event. */
    public enum Button {
        /** No specific button (e.g. a pure {@link Type#MOVE} or a legacy {@link Type#RELEASE}). */
        NONE,
        /** The primary (left) mouse button. */
        LEFT,
        /** The middle (wheel) mouse button. */
        MIDDLE,
        /** The secondary (right) mouse button. */
        RIGHT,
        /** Scroll-wheel rotation upward. */
        SCROLL_UP,
        /** Scroll-wheel rotation downward. */
        SCROLL_DOWN,
        /** A button reported by the terminal that is not recognised by this implementation. */
        UNKNOWN
    }

    private final Type type;
    private final Button button;
    private final int x;
    private final int y;
    private final boolean shift;
    private final boolean alt;
    private final boolean ctrl;

    MouseEvent(Type type, Button button, int x, int y, boolean shift, boolean alt, boolean ctrl) {
        this.type = type;
        this.button = button;
        this.x = x;
        this.y = y;
        this.shift = shift;
        this.alt = alt;
        this.ctrl = ctrl;
    }

    /**
     * Returns the type of this event.
     *
     * @return the event type
     */
    public Type type() {
        return type;
    }

    /**
     * Returns the button associated with this event.
     *
     * <p>For {@link Type#MOVE} events and for {@link Type#RELEASE} events reported via the legacy
     * or URXVT protocols (which do not identify the released button), this returns {@link
     * Button#NONE}.
     *
     * @return the button
     */
    public Button button() {
        return button;
    }

    /**
     * Returns the 1-based column at which the event occurred.
     *
     * @return column, &ge; 1
     */
    public int x() {
        return x;
    }

    /**
     * Returns the 1-based row at which the event occurred.
     *
     * @return row, &ge; 1
     */
    public int y() {
        return y;
    }

    /**
     * Returns {@code true} if the Shift key was held when the event was reported.
     *
     * @return shift modifier state
     */
    public boolean shift() {
        return shift;
    }

    /**
     * Returns {@code true} if the Alt/Meta key was held when the event was reported.
     *
     * @return alt/meta modifier state
     */
    public boolean alt() {
        return alt;
    }

    /**
     * Returns {@code true} if the Ctrl key was held when the event was reported.
     *
     * @return ctrl modifier state
     */
    public boolean ctrl() {
        return ctrl;
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) return true;
        if (!(o instanceof MouseEvent)) return false;
        MouseEvent that = (MouseEvent) o;
        return x == that.x
                && y == that.y
                && shift == that.shift
                && alt == that.alt
                && ctrl == that.ctrl
                && type == that.type
                && button == that.button;
    }

    @Override
    public int hashCode() {
        int result = type.hashCode();
        result = 31 * result + button.hashCode();
        result = 31 * result + x;
        result = 31 * result + y;
        result = 31 * result + (shift ? 1 : 0);
        result = 31 * result + (alt ? 1 : 0);
        result = 31 * result + (ctrl ? 1 : 0);
        return result;
    }

    @Override
    public String toString() {
        StringBuilder sb = new StringBuilder("MouseEvent{");
        sb.append("type=").append(type);
        sb.append(", button=").append(button);
        sb.append(", x=").append(x);
        sb.append(", y=").append(y);
        if (shift) sb.append(", shift");
        if (alt) sb.append(", alt");
        if (ctrl) sb.append(", ctrl");
        sb.append('}');
        return sb.toString();
    }
}
