package cs2d.AIControl.movement;

import java.util.List;

/**
 * Keeps locomotion and facing independent when a path briefly reverses.
 *
 * <p>The path planner owns where the body moves. This policy only decides
 * where the bot looks while executing that movement. A sharp reversal is
 * initially performed as a backpedal, so a bot does not immediately expose
 * its back to the direction it was guarding. Long reversals eventually turn
 * normally.</p>
 */
public final class LocomotionFacingPolicy {
    static final long BACKPEDAL_HOLD_MS = 900;
    static final double BACKPEDAL_THRESHOLD_RADIANS = Math.toRadians(125.0);

    private boolean reverseCommitted;
    private long reverseStartedAt;
    private double guardedAngle;

    public double chooseFacing(long now, double currentAngle, double pathAngle, List<String> movementKeys) {
        double safeCurrent = finiteOr(currentAngle, 0.0);
        if (movementKeys == null || movementKeys.isEmpty() || !Double.isFinite(pathAngle)) {
            reset();
            return safeCurrent;
        }

        double movementAngle = movementAngle(movementKeys, pathAngle);
        double difference = Math.abs(normalize(movementAngle - safeCurrent));
        if (difference < BACKPEDAL_THRESHOLD_RADIANS) {
            reset();
            return normalize(pathAngle);
        }

        if (!reverseCommitted) {
            reverseCommitted = true;
            reverseStartedAt = now;
            guardedAngle = safeCurrent;
        }
        if (now - reverseStartedAt < BACKPEDAL_HOLD_MS) {
            return guardedAngle;
        }
        return normalize(pathAngle);
    }

    public void reset() {
        reverseCommitted = false;
        reverseStartedAt = 0;
        guardedAngle = 0.0;
    }

    static double normalize(double angle) {
        while (angle <= -Math.PI) {
            angle += Math.PI * 2.0;
        }
        while (angle > Math.PI) {
            angle -= Math.PI * 2.0;
        }
        return angle;
    }

    private static double movementAngle(List<String> keys, double fallback) {
        double x = (keys.contains("D") ? 1.0 : 0.0) - (keys.contains("A") ? 1.0 : 0.0);
        double y = (keys.contains("S") ? 1.0 : 0.0) - (keys.contains("W") ? 1.0 : 0.0);
        return Math.abs(x) + Math.abs(y) > 0.0 ? Math.atan2(y, x) : fallback;
    }

    private static double finiteOr(double value, double fallback) {
        return Double.isFinite(value) ? value : fallback;
    }
}
