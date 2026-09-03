package cs2d.AIControl.team;

import cs2d.AIControl.team.TacticalOrder.LocomotionDirective;
import cs2d.AIControl.team.TacticalOrder.Role;
import cs2d.AIControl.team.TacticalOrder.TaskType;
import cs2d.AIControl.team.TeamTacticalSnapshot.AgentSnapshot;
import cs2d.AIControl.team.TeamTacticalSnapshot.Vec2;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

class LowPopulationMobilityFallbackTest {

    private final LowPopulationMobilityFallback fallback = new LowPopulationMobilityFallback();

    @Test
    void fillsMissingSmallTeamOrdersWithExplicitFreePatrol() {
        long now = 1_000L;
        Map<String, TacticalOrder> orders = fallback.apply(
                List.of(agent("a", 10, 10), agent("b", 20, 20)), Map.of(), now);

        assertEquals(Set.of("a", "b"), orders.keySet());
        assertEquals(LocomotionDirective.FREE_PATROL, orders.get("a").locomotionDirective());
        assertEquals(null, orders.get("a").taskId());
    }

    @Test
    void preservesAnExistingCommanderOrder() {
        long now = 2_000L;
        TacticalOrder response = order("a", TaskType.RESPOND_TO_CONTACT,
                new Vec2(500, 500), now);

        Map<String, TacticalOrder> orders = fallback.apply(
                List.of(agent("a", 10, 10)), Map.of("a", response), now);

        assertSame(response, orders.get("a"));
    }

    @Test
    void releasesSoloAgentFromCompletedRouteControl() {
        long now = 3_000L;
        TacticalOrder routeControl = order("a", TaskType.CONTROL_ROUTE,
                new Vec2(100, 100), now);

        Map<String, TacticalOrder> orders = fallback.apply(
                List.of(agent("a", 100, 100)), Map.of("a", routeControl), now);

        assertEquals(LocomotionDirective.FREE_PATROL, orders.get("a").locomotionDirective());
        assertEquals(null, orders.get("a").movementTarget());
    }

    @Test
    void doesNotInventFallbackOrdersForLargeTeams() {
        long now = 4_000L;
        List<AgentSnapshot> agents = List.of(
                agent("a", 0, 0), agent("b", 10, 0),
                agent("c", 20, 0), agent("d", 30, 0));

        assertEquals(Map.of(), fallback.apply(agents, Map.of(), now));
    }

    private static AgentSnapshot agent(String id, double x, double y) {
        return new AgentSnapshot(id, new Vec2(x, y), 100, false, false, null, List.of());
    }

    private static TacticalOrder order(String id, TaskType type, Vec2 target, long now) {
        return new TacticalOrder(id, type, Role.ANCHOR, "route-a", null,
                target, true, 100.0, Set.of(), 1_000.0, 0.0, now + 1_000L, "task-a");
    }
}
