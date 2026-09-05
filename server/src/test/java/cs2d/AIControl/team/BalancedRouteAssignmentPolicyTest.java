package cs2d.AIControl.team;

import cs2d.AIControl.team.TeamTacticalSnapshot.AgentSnapshot;
import cs2d.AIControl.team.TeamTacticalSnapshot.RouteSnapshot;
import cs2d.AIControl.team.TeamTacticalSnapshot.Vec2;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

class BalancedRouteAssignmentPolicyTest {

    private final BalancedRouteAssignmentPolicy policy = new BalancedRouteAssignmentPolicy();

    @Test
    void spreadsUnassignedSpawnWaveAcrossEveryUsableRoute() {
        List<AgentSnapshot> agents = new ArrayList<>();
        for (int i = 0; i < 10; i++) {
            agents.add(agent("bot-" + i, null));
        }
        List<RouteSnapshot> routes = List.of(
                route("route-0", 0), route("route-1", 100), route("route-2", 200),
                route("route-3", 300), route("route-4", 400));

        Map<String, String> assignments = policy.assign(snapshot(agents, routes));
        Map<String, Long> counts = assignments.values().stream()
                .collect(Collectors.groupingBy(value -> value, Collectors.counting()));

        assertEquals(10, assignments.size());
        assertEquals(Map.of("route-0", 2L, "route-1", 2L, "route-2", 2L,
                "route-3", 2L, "route-4", 2L), counts);
    }

    @Test
    void preservesExistingOwnershipAndFillsTheLessOccupiedRoute() {
        List<AgentSnapshot> agents = List.of(
                agent("existing", "route-b"),
                agent("new", null));
        List<RouteSnapshot> routes = List.of(route("route-a", 0), route("route-b", 100));

        Map<String, String> assignments = policy.assign(snapshot(agents, routes));

        assertFalse(assignments.containsKey("existing"));
        assertEquals("route-a", assignments.get("new"));
    }

    @Test
    void ignoresRoutesWithoutEnoughAuthoredPoints() {
        RouteSnapshot invalid = new RouteSnapshot("invalid", "TDM_CT_T",
                List.of(new Vec2(0, 0)), 0.0, 0, List.of());

        Map<String, String> assignments = policy.assign(snapshot(
                List.of(agent("bot", null)), List.of(invalid, route("valid", 100))));

        assertEquals(Map.of("bot", "valid"), assignments);
    }

    private static TeamTacticalSnapshot snapshot(List<AgentSnapshot> agents, List<RouteSnapshot> routes) {
        return new TeamTacticalSnapshot("CT", 1_000L, 1_600, 900,
                routes, agents, List.of(), List.of());
    }

    private static AgentSnapshot agent(String id, String routeId) {
        return new AgentSnapshot(id, new Vec2(50, 50), 100,
                false, false, routeId, List.of());
    }

    private static RouteSnapshot route(String id, double x) {
        return new RouteSnapshot(id, "TDM_CT_T",
                List.of(new Vec2(x, 0), new Vec2(x, 500)), 500.0, 0, List.of());
    }
}
