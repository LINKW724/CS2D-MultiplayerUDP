package cs2d.AIControl.team;

import java.util.Map;

/**
 * Strategy extension point for team-level AI decisions.
 * Implementations consume immutable snapshots and return immutable orders.
 */
@FunctionalInterface
public interface TacticalCoordinator {
    Map<String, TacticalOrder> coordinate(TeamTacticalSnapshot snapshot);
}
