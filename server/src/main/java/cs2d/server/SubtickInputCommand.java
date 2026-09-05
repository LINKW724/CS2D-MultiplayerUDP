package cs2d.server;

import java.util.ArrayList;
import java.util.List;

/**
 * One timestamped client input sample. Commands are ordered by client tick,
 * then by their fractional position inside that tick, and finally by sequence.
 */
record SubtickInputCommand(long sequence, long clientTick, int subTick, long clientTimeNanos,
        double angle, int buttons, int pressedButtons, int releasedButtons) {

    static final int BUTTON_FORWARD = 1 << 0;
    static final int BUTTON_BACK = 1 << 1;
    static final int BUTTON_LEFT = 1 << 2;
    static final int BUTTON_RIGHT = 1 << 3;
    static final int BUTTON_FIRE = 1 << 4;
    static final int BUTTON_WALK = 1 << 5;
    static final int BUTTON_INTERACT = 1 << 6;
    static final int BUTTON_UNDERHAND = 1 << 7;
    static final int KNOWN_BUTTON_MASK = BUTTON_FORWARD | BUTTON_BACK | BUTTON_LEFT | BUTTON_RIGHT
            | BUTTON_FIRE | BUTTON_WALK | BUTTON_INTERACT | BUTTON_UNDERHAND;
    static final int MAX_SUB_TICK = 65_535;

    SubtickInputCommand {
        if (sequence < 0 || clientTick < 0 || clientTimeNanos < 0)
            throw new IllegalArgumentException("Sub-tick input counters must be non-negative");
        if (subTick < 0 || subTick > MAX_SUB_TICK)
            throw new IllegalArgumentException("subTick out of range");
        if (!Double.isFinite(angle))
            throw new IllegalArgumentException("input angle must be finite");
        if ((buttons & ~KNOWN_BUTTON_MASK) != 0
                || (pressedButtons & ~KNOWN_BUTTON_MASK) != 0
                || (releasedButtons & ~KNOWN_BUTTON_MASK) != 0)
            throw new IllegalArgumentException("input contains unknown button bits");
    }

    double fraction() {
        return subTick / (double) MAX_SUB_TICK;
    }

    boolean pressed(int button) {
        return (pressedButtons & button) != 0;
    }

    boolean down(int button) {
        return (buttons & button) != 0;
    }

    List<String> movementKeys() {
        List<String> keys = new ArrayList<>(4);
        if (down(BUTTON_FORWARD)) keys.add("W");
        if (down(BUTTON_BACK)) keys.add("S");
        if (down(BUTTON_LEFT)) keys.add("A");
        if (down(BUTTON_RIGHT)) keys.add("D");
        return keys;
    }
}
