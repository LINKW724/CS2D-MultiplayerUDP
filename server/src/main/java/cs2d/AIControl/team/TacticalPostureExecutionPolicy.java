package cs2d.AIControl.team;

import cs2d.AIControl.team.TacticalOrder.Posture;

/** Pure translation from team posture plus reliable local evidence to execution mode. */
public final class TacticalPostureExecutionPolicy {

    public Decision decide(TacticalOrder order, long now, boolean directSight,
            boolean targetWithinEffectiveRange, boolean arrivedAtObjective) {
        if (directSight && targetWithinEffectiveRange) {
            return Decision.forPosture(Posture.ENGAGE);
        }
        if (directSight) {
            return Decision.forPosture(Posture.STEALTH_ADVANCE);
        }
        if (order == null || !order.isActive(now) || arrivedAtObjective
                || order.posture() == Posture.AMBUSH) {
            return Decision.forPosture(Posture.AMBUSH);
        }

        Posture requested = order.posture() == Posture.ENGAGE
                ? Posture.STEALTH_ADVANCE
                : order.posture();
        return Decision.forPosture(requested);
    }

    public record Decision(Posture posture, boolean holdPosition, boolean walkSilently,
            boolean allowCombatMovement) {
        static Decision forPosture(Posture posture) {
            return switch (posture) {
                case AMBUSH -> new Decision(posture, true, true, false);
                case STEALTH_ADVANCE -> new Decision(posture, false, true, false);
                case RUSH -> new Decision(posture, false, false, false);
                case ENGAGE -> new Decision(posture, false, false, true);
            };
        }
    }
}
