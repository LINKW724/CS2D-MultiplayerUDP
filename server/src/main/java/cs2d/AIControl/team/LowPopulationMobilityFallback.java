package cs2d.AIControl.team;

import cs2d.AIControl.team.TacticalOrder.LocomotionDirective;
import cs2d.AIControl.team.TacticalOrder.Posture;
import cs2d.AIControl.team.TacticalOrder.Role;
import cs2d.AIControl.team.TacticalOrder.TaskType;
import cs2d.AIControl.team.TeamTacticalSnapshot.AgentSnapshot;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Supplies locomotion continuity for small teams without inventing a tactical
 * task. Existing commander orders always win; completed route control may fall
 * back to free patrol until a new actionable order is published.
 */
public final class LowPopulationMobilityFallback {

    static final int MAX_FALLBACK_TEAM_SIZE = 3;
    static final long FALLBACK_TTL_MS = 1_500L;

    public Map<String, TacticalOrder> apply(List<AgentSnapshot> agents,
            Map<String, TacticalOrder> activeOrders, long now) {
        List<AgentSnapshot> safeAgents = agents == null ? List.of() : agents.stream()
                .filter(agent -> agent != null && agent.id() != null && agent.position() != null)
                .toList();
        Map<String, TacticalOrder> result = new LinkedHashMap<>(
                activeOrders == null ? Map.of() : activeOrders);
        if (safeAgents.isEmpty() || safeAgents.size() > MAX_FALLBACK_TEAM_SIZE) {
            return Map.copyOf(result);
        }

        for (AgentSnapshot agent : safeAgents) {
            TacticalOrder existing = result.get(agent.id());
            if (existing == null || !existing.isActive(now) || isCompletedRouteControl(agent, existing)) {
                result.put(agent.id(), freePatrolOrder(agent.id(), now));
            }
        }
        return Map.copyOf(result);
    }

    private static boolean isCompletedRouteControl(AgentSnapshot agent, TacticalOrder order) {
        if (order.taskType() != TaskType.CONTROL_ROUTE || order.movementTarget() == null) {
            return false;
        }
        double radius = Math.max(20.0, order.arrivalRadius());
        return agent.position().distanceSq(order.movementTarget()) <= radius * radius;
    }

    private static TacticalOrder freePatrolOrder(String agentId, long now) {
        return new TacticalOrder(agentId, TaskType.CONTROL_ROUTE, Role.FREE,
                null, null, null, false, 0.0, Set.of(), 0.0, 0.0,
                now + FALLBACK_TTL_MS, null, Posture.STEALTH_ADVANCE, 0L,
                LocomotionDirective.FREE_PATROL);
    }
}
