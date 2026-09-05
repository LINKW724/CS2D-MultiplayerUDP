package cs2d.AIControl.team;

import cs2d.AIControl.team.TacticalOrder.Posture;
import cs2d.AIControl.team.TacticalOrder.Role;
import cs2d.AIControl.team.TacticalOrder.TaskType;
import cs2d.AIControl.team.TeamTacticalSnapshot.AgentSnapshot;
import cs2d.AIControl.team.TeamTacticalSnapshot.RouteSnapshot;
import cs2d.AIControl.team.TeamTacticalSnapshot.Vec2;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Lets rear agents run through a corridor already occupied by friendlies, then
 * restores quiet movement before they reach the frontline. The policy is
 * intentionally stateful so distance noise cannot toggle running every plan.
 */
public final class FrontlineReinforcementRefiner implements TacticalPlanRefiner {

    static final double START_RUSH_GAP = 520.0;
    static final double STOP_RUSH_GAP = 280.0;
    private static final double CONTACT_CAUTION_RADIUS = 360.0;

    private final Set<AgentKey> rushingAgents = new HashSet<>();

    @Override
    public TacticalPlan refine(TeamTacticalSnapshot snapshot, TacticalPlan basePlan) {
        if (snapshot == null || basePlan == null || snapshot.agents().size() < 2) {
            clearTeam(snapshot == null ? null : snapshot.teamId());
            return basePlan;
        }

        Map<String, RouteSnapshot> routes = new HashMap<>();
        for (RouteSnapshot route : snapshot.routes()) {
            if (route != null && route.routeId() != null && route.keyPoints().size() >= 2) {
                routes.put(route.routeId(), route);
            }
        }
        Map<String, AgentSnapshot> agents = new HashMap<>();
        for (AgentSnapshot agent : snapshot.agents()) {
            if (agent != null && agent.id() != null && agent.position() != null) {
                agents.put(agent.id(), agent);
            }
        }

        Map<String, List<RouteAgent>> byRoute = new HashMap<>();
        for (TacticalOrder order : basePlan.orders().values()) {
            AgentSnapshot agent = order == null ? null : agents.get(order.agentId());
            RouteSnapshot route = order == null ? null : routes.get(order.routeId());
            if (isRouteAdvance(order, agent, route)) {
                byRoute.computeIfAbsent(order.routeId(), ignored -> new ArrayList<>())
                        .add(new RouteAgent(order, agent, progressAlong(route.keyPoints(), agent.position())));
            }
        }

        String teamId = snapshot.teamId() == null ? "" : snapshot.teamId();
        Set<AgentKey> activeKeys = new HashSet<>();
        Map<String, TacticalOrder> orders = new LinkedHashMap<>(basePlan.orders());
        for (List<RouteAgent> routeAgents : byRoute.values()) {
            if (routeAgents.size() < 2) {
                continue;
            }
            routeAgents.sort(Comparator.comparingDouble(RouteAgent::progress).reversed());
            double frontlineProgress = routeAgents.get(0).progress();
            for (RouteAgent routeAgent : routeAgents) {
                TacticalOrder order = routeAgent.order();
                AgentKey key = new AgentKey(teamId, order.agentId());
                activeKeys.add(key);
                double gap = frontlineProgress - routeAgent.progress();
                boolean wasRushing = rushingAgents.contains(key);
                boolean shouldRush = canRush(snapshot, routeAgent)
                        && gap >= (wasRushing ? STOP_RUSH_GAP : START_RUSH_GAP);
                if (shouldRush) {
                    rushingAgents.add(key);
                    orders.put(order.agentId(), order.withPosture(Posture.RUSH, 0L));
                } else {
                    rushingAgents.remove(key);
                }
            }
        }
        rushingAgents.removeIf(key -> key.teamId().equals(teamId) && !activeKeys.contains(key));
        return new TacticalPlan(basePlan.tasks(), orders);
    }

    private static boolean isRouteAdvance(TacticalOrder order, AgentSnapshot agent, RouteSnapshot route) {
        return order != null && agent != null && route != null
                && order.taskType() == TaskType.ADVANCE
                && order.preserveMapRoute();
    }

    private static boolean canRush(TeamTacticalSnapshot snapshot, RouteAgent routeAgent) {
        if (routeAgent.order().role() == Role.ANCHOR || routeAgent.agent().recentlyDamaged()) {
            return false;
        }
        double cautionRadiusSq = CONTACT_CAUTION_RADIUS * CONTACT_CAUTION_RADIUS;
        return snapshot.contacts().stream()
                .filter(contact -> contact != null && contact.position() != null)
                .noneMatch(contact -> contact.position().distanceSq(routeAgent.agent().position())
                        <= cautionRadiusSq);
    }

    private void clearTeam(String teamId) {
        String safeTeamId = teamId == null ? "" : teamId;
        rushingAgents.removeIf(key -> key.teamId().equals(safeTeamId));
    }

    static double progressAlong(List<Vec2> points, Vec2 position) {
        double bestDistanceSq = Double.POSITIVE_INFINITY;
        double bestProgress = 0.0;
        double accumulated = 0.0;
        for (int i = 1; i < points.size(); i++) {
            Vec2 start = points.get(i - 1);
            Vec2 end = points.get(i);
            double dx = end.x() - start.x();
            double dy = end.y() - start.y();
            double length = Math.hypot(dx, dy);
            if (length <= 1.0e-9) {
                continue;
            }
            double t = ((position.x() - start.x()) * dx + (position.y() - start.y()) * dy)
                    / (length * length);
            t = Math.max(0.0, Math.min(1.0, t));
            double nearestX = start.x() + t * dx;
            double nearestY = start.y() + t * dy;
            double distanceSq = square(position.x() - nearestX) + square(position.y() - nearestY);
            if (distanceSq < bestDistanceSq) {
                bestDistanceSq = distanceSq;
                bestProgress = accumulated + t * length;
            }
            accumulated += length;
        }
        return bestProgress;
    }

    private static double square(double value) {
        return value * value;
    }

    private record RouteAgent(TacticalOrder order, AgentSnapshot agent, double progress) {
    }

    private record AgentKey(String teamId, String agentId) {
    }
}
