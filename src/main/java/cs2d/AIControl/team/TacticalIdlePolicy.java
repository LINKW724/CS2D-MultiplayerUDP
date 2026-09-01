package cs2d.AIControl.team;

/**
 * Pure policy for the gap between two commander tasks.
 *
 * <p>An agent without an actionable order holds its current position instead
 * of inventing a patrol target. Holding also keeps walk mode enabled so any
 * residual movement or emergency separation remains silent.</p>
 */
public final class TacticalIdlePolicy {

    public Decision decide(TacticalOrder order, long now, boolean hasImmediateThreat,
            boolean routeMovementInProgress) {
        if (hasImmediateThreat) {
            return Decision.active();
        }

        boolean activeOrder = order != null && order.isActive(now);
        boolean actionableOrder = activeOrder
                && (order.movementTarget() != null || routeMovementInProgress);
        return actionableOrder ? Decision.active() : Decision.ambush();
    }

    public record Decision(boolean holdPosition, boolean walkSilently) {
        public static Decision active() {
            return new Decision(false, false);
        }

        public static Decision ambush() {
            return new Decision(true, true);
        }
    }
}
