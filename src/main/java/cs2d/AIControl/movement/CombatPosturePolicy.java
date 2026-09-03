package cs2d.AIControl.movement;

import java.awt.geom.Point2D;
import java.util.List;

/**
 * Keeps a short memory of reliable combat threats and chooses where the bot
 * looks independently from where its locomotion moves it.
 *
 * <p>Only direct sight and actual damage are accepted as evidence. Sound-only
 * contacts never enter this policy, so a remote gunshot cannot pull a bot's
 * aim away from its current task.</p>
 */
public final class CombatPosturePolicy {
    public static final long THREAT_MEMORY_MS = 1_800;
    static final double FORWARD_TRAVEL_DOT_THRESHOLD = 0.35;

    private Point2D.Double threatPosition;
    private long threatObservedAt;

    public void observeThreat(long now, Point2D.Double position, ThreatEvidence evidence) {
        if (position == null || evidence == null
                || !Double.isFinite(position.x) || !Double.isFinite(position.y)) {
            return;
        }
        this.threatPosition = new Point2D.Double(position.x, position.y);
        this.threatObservedAt = now;
    }

    /**
     * Returns a guarded threat angle while stationary, retreating or moving
     * laterally. Normal travel facing is retained when moving toward the threat.
     */
    public double chooseFacing(long now, Point2D.Double ownerPosition,
            List<String> movementKeys, double travelFacing) {
        double safeTravelFacing = Double.isFinite(travelFacing) ? travelFacing : 0.0;
        if (!hasActiveThreat(now) || ownerPosition == null) {
            return safeTravelFacing;
        }

        double threatX = threatPosition.x - ownerPosition.x;
        double threatY = threatPosition.y - ownerPosition.y;
        double threatDistance = Math.hypot(threatX, threatY);
        if (threatDistance < 0.001) {
            return safeTravelFacing;
        }

        Direction movement = Direction.fromKeys(movementKeys);
        double threatAngle = Math.atan2(threatY, threatX);
        if (!movement.active()) {
            return threatAngle;
        }

        double travelTowardThreat = movement.x() * (threatX / threatDistance)
                + movement.y() * (threatY / threatDistance);
        return travelTowardThreat <= FORWARD_TRAVEL_DOT_THRESHOLD
                ? threatAngle
                : safeTravelFacing;
    }

    public boolean hasActiveThreat(long now) {
        if (threatPosition == null || now < threatObservedAt
                || now - threatObservedAt > THREAT_MEMORY_MS) {
            if (threatPosition != null && now >= threatObservedAt) {
                reset();
            }
            return false;
        }
        return true;
    }

    public void reset() {
        threatPosition = null;
        threatObservedAt = 0;
    }

    public enum ThreatEvidence {
        VISIBLE_ENEMY,
        DAMAGE_SOURCE
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
