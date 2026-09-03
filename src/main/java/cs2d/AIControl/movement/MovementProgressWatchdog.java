package cs2d.AIControl.movement;

import java.awt.geom.Point2D;
import java.util.List;

/**
 * Detects sustained disagreement between issued movement and actual progress.
 *
 * <p>The watchdog observes the final post-arbitration keys. It does not steer,
 * avoid or re-plan by itself; the game-mode controller decides how to recover
 * after a confirmed execution failure.</p>
 */
public final class MovementProgressWatchdog {
    static final long SAMPLE_INTERVAL_MS = 200;
    static final long MAX_OBSERVATION_GAP_MS = 750;
    static final double MIN_PROGRESS_RATIO = 0.18;
    static final double MIN_EXPECTED_DISTANCE = 3.0;
    static final double MIN_DIRECTION_COHERENCE = 0.75;
    static final int FAILURES_TO_CONFIRM = 3;

    private boolean initialized;
    private long lastObservationAt;
    private long windowStartedAt;
    private Point2D.Double windowStartPosition;
    private double previousDirectionX;
    private double previousDirectionY;
    private double previousSpeedPerSecond;
    private boolean previousCommandEligible;
    private double expectedVectorX;
    private double expectedVectorY;
    private double intendedDistance;
    private int consecutiveFailures;

    public Assessment observe(long now, Point2D.Double position, List<String> finalKeys,
            double expectedSpeedPerSecond, boolean eligible) {
        if (position == null || !Double.isFinite(position.x) || !Double.isFinite(position.y)) {
            reset();
            return Assessment.notEvaluated();
        }

        Direction currentDirection = Direction.fromKeys(finalKeys);
        boolean currentEligible = eligible && currentDirection.active()
                && Double.isFinite(expectedSpeedPerSecond) && expectedSpeedPerSecond > 0.0;

        if (!initialized) {
            initialize(now, position, currentDirection, expectedSpeedPerSecond, currentEligible);
            return Assessment.notEvaluated();
        }

        long elapsed = now - lastObservationAt;
        if (elapsed < 0 || elapsed > MAX_OBSERVATION_GAP_MS) {
            consecutiveFailures = 0;
            initialize(now, position, currentDirection, expectedSpeedPerSecond, currentEligible);
            return Assessment.notEvaluated();
        }

        if (previousCommandEligible && elapsed > 0) {
            double expectedDistance = previousSpeedPerSecond * elapsed / 1_000.0;
            expectedVectorX += previousDirectionX * expectedDistance;
            expectedVectorY += previousDirectionY * expectedDistance;
            intendedDistance += expectedDistance;
        }

        lastObservationAt = now;
        previousDirectionX = currentDirection.x();
        previousDirectionY = currentDirection.y();
        previousSpeedPerSecond = Math.max(0.0, expectedSpeedPerSecond);
        previousCommandEligible = currentEligible;

        if (!currentEligible) {
            consecutiveFailures = 0;
            restartWindow(now, position);
            return Assessment.notEvaluated();
        }
        if (now - windowStartedAt < SAMPLE_INTERVAL_MS) {
            return Assessment.notEvaluated();
        }

        double netExpectedDistance = Math.hypot(expectedVectorX, expectedVectorY);
        double coherence = intendedDistance > 0.0 ? netExpectedDistance / intendedDistance : 0.0;
        if (intendedDistance < MIN_EXPECTED_DISTANCE || coherence < MIN_DIRECTION_COHERENCE) {
            consecutiveFailures = 0;
            restartWindow(now, position);
            return Assessment.notEvaluated();
        }

        double directionX = expectedVectorX / netExpectedDistance;
        double directionY = expectedVectorY / netExpectedDistance;
        double actualX = position.x - windowStartPosition.x;
        double actualY = position.y - windowStartPosition.y;
        double forwardProgress = Math.max(0.0, actualX * directionX + actualY * directionY);
        double progressRatio = forwardProgress / intendedDistance;

        if (progressRatio < MIN_PROGRESS_RATIO) {
            consecutiveFailures++;
        } else {
            consecutiveFailures = 0;
        }
        boolean blocked = consecutiveFailures >= FAILURES_TO_CONFIRM;
        Assessment assessment = new Assessment(true, blocked, progressRatio, directionX, directionY,
                consecutiveFailures);
        restartWindow(now, position);
        return assessment;
    }

    public void reset(long now, Point2D.Double position) {
        reset();
        if (position != null && Double.isFinite(position.x) && Double.isFinite(position.y)) {
            initialize(now, position, new Direction(0.0, 0.0), 0.0, false);
        }
    }

    public void reset() {
        initialized = false;
        lastObservationAt = 0;
        windowStartedAt = 0;
        windowStartPosition = null;
        previousDirectionX = 0.0;
        previousDirectionY = 0.0;
        previousSpeedPerSecond = 0.0;
        previousCommandEligible = false;
        expectedVectorX = 0.0;
        expectedVectorY = 0.0;
        intendedDistance = 0.0;
        consecutiveFailures = 0;
    }

    private void initialize(long now, Point2D.Double position, Direction direction,
            double expectedSpeedPerSecond, boolean eligible) {
        initialized = true;
        lastObservationAt = now;
        windowStartedAt = now;
        windowStartPosition = new Point2D.Double(position.x, position.y);
        previousDirectionX = direction.x();
        previousDirectionY = direction.y();
        previousSpeedPerSecond = Math.max(0.0, expectedSpeedPerSecond);
        previousCommandEligible = eligible;
        expectedVectorX = 0.0;
        expectedVectorY = 0.0;
        intendedDistance = 0.0;
    }

    private void restartWindow(long now, Point2D.Double position) {
        windowStartedAt = now;
        windowStartPosition.setLocation(position);
        expectedVectorX = 0.0;
        expectedVectorY = 0.0;
        intendedDistance = 0.0;
    }

    public record Assessment(boolean evaluated, boolean blocked, double progressRatio,
            double intendedDirectionX, double intendedDirectionY, int consecutiveFailures) {
        static Assessment notEvaluated() {
            return new Assessment(false, false, 1.0, 0.0, 0.0, 0);
        }
    }

    private record Direction(double x, double y) {
        static Direction fromKeys(List<String> keys) {
            if (keys == null || keys.isEmpty()) {
                return new Direction(0.0, 0.0);
            }
            double x = (keys.contains("D") ? 1.0 : 0.0) - (keys.contains("A") ? 1.0 : 0.0);
            double y = (keys.contains("S") ? 1.0 : 0.0) - (keys.contains("W") ? 1.0 : 0.0);
            double length = Math.hypot(x, y);
            return length > 0.0 ? new Direction(x / length, y / length) : new Direction(0.0, 0.0);
        }

        boolean active() {
            return Math.abs(x) + Math.abs(y) > 0.0;
        }
    }
}
