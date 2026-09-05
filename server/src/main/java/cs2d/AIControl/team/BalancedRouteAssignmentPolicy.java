package cs2d.AIControl.team;

import cs2d.AIControl.team.TeamTacticalSnapshot.AgentSnapshot;
import cs2d.AIControl.team.TeamTacticalSnapshot.RouteSnapshot;

import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Deterministically fills the least occupied authored route. Existing route
 * ownership is preserved, so the policy only bootstraps new lives.
 */
public final class BalancedRouteAssignmentPolicy implements RouteAssignmentPolicy {

    @Override
    public Map<String, String> assign(TeamTacticalSnapshot snapshot) {
        if (snapshot == null || snapshot.agents().isEmpty() || snapshot.routes().isEmpty()) {
            return Map.of();
        }

        List<RouteSnapshot> routes = snapshot.routes().stream()
                .filter(BalancedRouteAssignmentPolicy::isUsable)
                .sorted(Comparator.comparing(RouteSnapshot::routeId))
                .toList();
        if (routes.isEmpty()) {
            return Map.of();
        }

        Map<String, Integer> occupancy = new HashMap<>();
        routes.forEach(route -> occupancy.put(route.routeId(), 0));
        for (AgentSnapshot agent : snapshot.agents()) {
            if (agent != null && occupancy.containsKey(agent.routeId())) {
                occupancy.merge(agent.routeId(), 1, Integer::sum);
            }
        }

        Map<String, String> assignments = new LinkedHashMap<>();
        snapshot.agents().stream()
                .filter(agent -> agent != null && agent.id() != null && agent.position() != null)
                .filter(agent -> agent.routeId() == null || agent.routeId().isBlank())
                .sorted(Comparator.comparing(AgentSnapshot::id))
                .forEach(agent -> {
                    RouteSnapshot selected = routes.stream()
                            .min(Comparator
                                    .comparingDouble((RouteSnapshot route) -> normalizedLoad(route, occupancy))
                                    .thenComparingInt(route -> occupancy.get(route.routeId()))
                                    .thenComparing(RouteSnapshot::routeId))
                            .orElse(null);
                    if (selected != null) {
                        assignments.put(agent.id(), selected.routeId());
                        occupancy.merge(selected.routeId(), 1, Integer::sum);
                    }
                });
        return Map.copyOf(assignments);
    }

    private static double normalizedLoad(RouteSnapshot route, Map<String, Integer> occupancy) {
        int capacity = route.suggestedCapacity() > 0 ? route.suggestedCapacity() : 1;
        return (double) occupancy.getOrDefault(route.routeId(), 0) / capacity;
    }

    private static boolean isUsable(RouteSnapshot route) {
        return route != null && route.routeId() != null && !route.routeId().isBlank()
                && route.keyPoints() != null && route.keyPoints().size() >= 2;
    }
}
