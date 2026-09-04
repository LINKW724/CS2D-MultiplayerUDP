package cs2d.AIControl.zombie;

import cs2d.AIControl.zombie.ZombieTacticalSnapshot.Vec;
import java.util.Set;

/** A short-lived team-level battle location derived from shared visual reports. */
public record ZombieCombatFront(String id, Vec center, Vec rallyPoint, Vec watchPoint,
        double pressure, int requiredDefenders, Set<String> engagedAgentIds, long observedAt) {
    public ZombieCombatFront {
        engagedAgentIds = Set.copyOf(engagedAgentIds);
        pressure = Math.max(0, pressure);
        requiredDefenders = Math.max(1, requiredDefenders);
    }

    public int supportNeeded() {
        return Math.max(0, requiredDefenders - engagedAgentIds.size());
    }
}
