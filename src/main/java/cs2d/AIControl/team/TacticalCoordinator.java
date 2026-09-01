package cs2d.AIControl.team;

import java.util.Map;

/**
 * Strategy extension point for team-level AI decisions.
 * Implementations consume immutable snapshots and return immutable orders.
 */
@FunctionalInterface
public interface TacticalCoordinator {
    Map<String, TacticalOrder> coordinate(TeamTacticalSnapshot snapshot);

    /**
     * Extended planning API. Existing coordinators remain source-compatible and
     * are treated as order-only planners until they opt into explicit tasks.
     */
    default TacticalPlan plan(TeamTacticalSnapshot snapshot) {
        return TacticalPlan.ordersOnly(coordinate(snapshot));
    }
}
