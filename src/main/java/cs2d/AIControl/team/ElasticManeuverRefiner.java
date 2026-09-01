package cs2d.AIControl.team;

import cs2d.AIControl.team.TacticalOrder.Role;
import cs2d.AIControl.team.TacticalOrder.TaskType;
import cs2d.AIControl.team.TacticalTask.EngagementRule;
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
 * Adds temporary population-aware maneuvers to a valid base plan. It never
 * forms persistent squads: assignments disappear as soon as population,
 * contact or route conditions no longer support the maneuver.
 */
public final class ElasticManeuverRefiner implements TacticalPlanRefiner {

    private static final double ROUTE_START_RADIUS = 480.0;
    private static final int MAX_WAVE_AGENTS = 5;

    @Override
    public TacticalPlan refine(TeamTacticalSnapshot snapshot, TacticalPlan basePlan) {
        if (snapshot == null || basePlan == null || snapshot.agents().size() < 3) {
            return basePlan;
        }

        List<RouteSnapshot> routes = snapshot.routes().stream()
                .filter(ElasticManeuverRefiner::hasUsableRoute)
                .sorted(Comparator.comparing(RouteSnapshot::routeId))
                .toList();
        if (routes.isEmpty()) {
            return basePlan;
        }

        Map<String, AgentSnapshot> agentsById = new HashMap<>();
        for (AgentSnapshot agent : snapshot.agents()) {
            if (agent != null && agent.id() != null && agent.position() != null) {
                agentsById.put(agent.id(), agent);
            }
        }

        TacticalPlan wavePlan = refineRespawnWave(snapshot, basePlan, routes, agentsById);
        if (wavePlan != basePlan) {
            return wavePlan;
        }
        return refinePincer(snapshot, basePlan, routes, agentsById);
    }

    private TacticalPlan refineRespawnWave(TeamTacticalSnapshot snapshot, TacticalPlan basePlan,
            List<RouteSnapshot> routes, Map<String, AgentSnapshot> agentsById) {
        List<AgentSnapshot> nearRouteStarts = agentsById.values().stream()
                .filter(agent -> distanceToNearestRouteStartSq(agent.position(), routes)
                        <= square(ROUTE_START_RADIUS))
                .sorted(Comparator.comparing(AgentSnapshot::id))
                .toList();
        int agentsAlreadyForward = agentsById.size() - nearRouteStarts.size();
        if (agentsAlreadyForward < 2 || nearRouteStarts.isEmpty() || nearRouteStarts.size() > MAX_WAVE_AGENTS) {
            return basePlan;
        }

        List<AgentSnapshot> eligible = nearRouteStarts.stream()
                .filter(agent -> isManeuverEligible(basePlan.orders().get(agent.id())))
                .toList();
        if (eligible.isEmpty()) {
            return basePlan;
        }

        RouteSnapshot route = selectPrimaryRoute(routes, agentsById.values());
        if (route == null) {
            return basePlan;
        }
        if (eligible.size() < 3) {
            return createAssemblyPlan(snapshot, basePlan, route, eligible);
        }
        return createWaveAdvancePlan(snapshot, basePlan, route,
                eligible.stream().limit(MAX_WAVE_AGENTS).toList());
    }

    private TacticalPlan createAssemblyPlan(TeamTacticalSnapshot snapshot, TacticalPlan basePlan,
            RouteSnapshot route, List<AgentSnapshot> agents) {
        String taskId = stableId(snapshot.teamId(), "assemble", route.routeId());
        Vec2 rallyPoint = routePoint(route, 0.15);
        long expiresAt = planExpiry(basePlan, snapshot.timestamp());
        TacticalTask task = new TacticalTask(taskId, TaskType.ASSEMBLE, route.routeId(), rallyPoint,
                1, 2, 85, routeRisk(basePlan, route.routeId()), EngagementRule.IGNORE_REMOTE_SOUNDS,
                140.0, Set.of(), expiresAt);

        Map<String, TacticalOrder> orders = new LinkedHashMap<>(basePlan.orders());
        for (AgentSnapshot agent : agents) {
            TacticalOrder base = orders.get(agent.id());
            orders.put(agent.id(), reassign(base, TaskType.ASSEMBLE, Role.RESERVE, route.routeId(),
                    rallyPoint, false, 140.0, taskId, expiresAt));
        }
        return mergePlan(basePlan, orders, List.of(task));
    }

    private TacticalPlan createWaveAdvancePlan(TeamTacticalSnapshot snapshot, TacticalPlan basePlan,
            RouteSnapshot route, List<AgentSnapshot> agents) {
        String operationId = stableId(snapshot.teamId(), "wave", route.routeId());
        String taskId = operationId + ":advance";
        Vec2 target = routePoint(route, 0.72);
        long expiresAt = planExpiry(basePlan, snapshot.timestamp());
        TacticalTask task = new TacticalTask(taskId, TaskType.ADVANCE, route.routeId(), target,
                3, MAX_WAVE_AGENTS, 75, routeRisk(basePlan, route.routeId()),
                EngagementRule.IGNORE_REMOTE_SOUNDS, 180.0, Set.of(), expiresAt, operationId);

        Map<String, TacticalOrder> orders = new LinkedHashMap<>(basePlan.orders());
        for (int i = 0; i < agents.size(); i++) {
            AgentSnapshot agent = agents.get(i);
            TacticalOrder base = orders.get(agent.id());
            Role role = i == 0 ? Role.ENTRY : Role.SUPPORT;
            orders.put(agent.id(), reassign(base, TaskType.ADVANCE, role, route.routeId(),
                    target, true, 180.0, taskId, expiresAt));
        }
        return mergePlan(basePlan, orders, List.of(task));
    }

    private TacticalPlan refinePincer(TeamTacticalSnapshot snapshot, TacticalPlan basePlan,
            List<RouteSnapshot> routes, Map<String, AgentSnapshot> agentsById) {
        if (routes.size() < 2) {
            return basePlan;
        }
        List<AgentSnapshot> eligible = agentsById.values().stream()
                .filter(agent -> isManeuverEligible(basePlan.orders().get(agent.id())))
                .sorted(Comparator.comparing(AgentSnapshot::id))
                .toList();
        if (eligible.size() < 3) {
            return basePlan;
        }

        RouteSnapshot primaryRoute = selectPrimaryRoute(routes, eligible);
        RouteSnapshot flankRoute = selectIndependentFlankRoute(primaryRoute, routes, basePlan);
        if (primaryRoute == null || flankRoute == null) {
            return basePlan;
        }

        int flankCount = eligible.size() >= 4 ? 2 : 1;
        int suppressCount = Math.min(3, eligible.size() - flankCount);
        if (suppressCount < 1) {
            return basePlan;
        }

        Vec2 primaryTarget = routePoint(primaryRoute, 0.58);
        Vec2 flankTarget = routePoint(flankRoute, 0.82);
        List<AgentSnapshot> suppressors = eligible.stream()
                .sorted(Comparator
                        .comparingInt((AgentSnapshot agent) -> primaryRoute.routeId().equals(agent.routeId()) ? 0 : 1)
                        .thenComparingDouble(agent -> agent.position().distanceSq(primaryTarget))
                        .thenComparing(AgentSnapshot::id))
                .limit(suppressCount)
                .toList();
        Set<String> committed = new HashSet<>();
        suppressors.forEach(agent -> committed.add(agent.id()));
        List<AgentSnapshot> flankers = eligible.stream()
                .filter(agent -> !committed.contains(agent.id()))
                .sorted(Comparator
                        .comparingInt((AgentSnapshot agent) -> {
                            TacticalOrder order = basePlan.orders().get(agent.id());
                            return order != null && order.role() == Role.ANCHOR ? 1 : 0;
                        })
                        .thenComparingDouble(agent -> agent.position().distanceSq(flankRoute.keyPoints().get(0)))
                        .thenComparing(AgentSnapshot::id))
                .limit(flankCount)
                .toList();
        if (flankers.size() < flankCount) {
            return basePlan;
        }

        long expiresAt = planExpiry(basePlan, snapshot.timestamp());
        String operationId = stableId(snapshot.teamId(), "pincer",
                primaryRoute.routeId() + '|' + flankRoute.routeId());
        String suppressTaskId = operationId + ":suppress";
        String flankTaskId = operationId + ":flank";
        TacticalTask suppressTask = new TacticalTask(suppressTaskId, TaskType.SUPPRESS,
                primaryRoute.routeId(), primaryTarget, suppressors.size(), suppressors.size(),
                68, routeRisk(basePlan, primaryRoute.routeId()), EngagementRule.IGNORE_REMOTE_SOUNDS,
                190.0, Set.of(), expiresAt, operationId);
        TacticalTask flankTask = new TacticalTask(flankTaskId, TaskType.FLANK,
                flankRoute.routeId(), flankTarget, flankers.size(), 2,
                69, routeRisk(basePlan, flankRoute.routeId()), EngagementRule.IGNORE_REMOTE_SOUNDS,
                190.0, Set.of(), expiresAt, operationId);

        Map<String, TacticalOrder> orders = new LinkedHashMap<>(basePlan.orders());
        for (AgentSnapshot agent : suppressors) {
            orders.put(agent.id(), reassign(orders.get(agent.id()), TaskType.SUPPRESS, Role.SUPPRESSOR,
                    primaryRoute.routeId(), primaryTarget, true, 190.0, suppressTaskId, expiresAt));
        }
        for (AgentSnapshot agent : flankers) {
            orders.put(agent.id(), reassign(orders.get(agent.id()), TaskType.FLANK, Role.FLANKER,
                    flankRoute.routeId(), flankTarget, true, 190.0, flankTaskId, expiresAt));
        }
        return mergePlan(basePlan, orders, List.of(suppressTask, flankTask));
    }

    private static TacticalPlan mergePlan(TacticalPlan basePlan, Map<String, TacticalOrder> orders,
            List<TacticalTask> additions) {
        Set<String> assignedTaskIds = orders.values().stream()
                .map(TacticalOrder::taskId)
                .filter(java.util.Objects::nonNull)
                .collect(java.util.stream.Collectors.toSet());
        List<TacticalTask> tasks = new ArrayList<>();
        basePlan.tasks().stream()
                .filter(task -> assignedTaskIds.contains(task.taskId()))
                .forEach(tasks::add);
        tasks.addAll(additions);
        return new TacticalPlan(tasks, orders);
    }

    private static TacticalOrder reassign(TacticalOrder base, TaskType taskType, Role role,
            String routeId, Vec2 target, boolean preserveMapRoute, double arrivalRadius,
            String taskId, long expiresAt) {
        double routeRisk = base == null ? 0.0 : base.routeRisk();
        return new TacticalOrder(base == null ? null : base.agentId(), taskType, role, routeId, null,
                target, preserveMapRoute, arrivalRadius, Set.of(), 0.0, routeRisk, expiresAt, taskId);
    }

    private static boolean isManeuverEligible(TacticalOrder order) {
        return order != null
                && order.taskType() != TaskType.RESPOND_TO_CONTACT
                && order.taskType() != TaskType.SUPPORT
                && order.taskType() != TaskType.REGROUP
                && order.taskType() != TaskType.FLANK
                && order.taskType() != TaskType.SUPPRESS
                && order.taskType() != TaskType.ASSEMBLE;
    }

    private static RouteSnapshot selectPrimaryRoute(List<RouteSnapshot> routes,
            java.util.Collection<AgentSnapshot> agents) {
        Map<String, Integer> occupancy = new HashMap<>();
        for (AgentSnapshot agent : agents) {
            if (agent.routeId() != null) {
                occupancy.merge(agent.routeId(), 1, Integer::sum);
            }
        }
        return routes.stream()
                .max(Comparator.comparingInt((RouteSnapshot route) -> occupancy.getOrDefault(route.routeId(), 0))
                        .thenComparing(RouteSnapshot::length)
                        .thenComparing(RouteSnapshot::routeId))
                .orElse(null);
    }

    private static RouteSnapshot selectIndependentFlankRoute(RouteSnapshot primary, List<RouteSnapshot> routes,
            TacticalPlan plan) {
        if (primary == null) {
            return null;
        }
        return routes.stream()
                .filter(route -> !route.routeId().equals(primary.routeId()))
                .filter(route -> !primary.overlappingRouteIds().contains(route.routeId()))
                .filter(route -> !route.overlappingRouteIds().contains(primary.routeId()))
                .min(Comparator.comparingDouble((RouteSnapshot route) -> routeRisk(plan, route.routeId()))
                        .thenComparing(RouteSnapshot::routeId))
                .orElse(null);
    }

    private static double routeRisk(TacticalPlan plan, String routeId) {
        return plan.tasks().stream()
                .filter(task -> routeId != null && routeId.equals(task.routeId()))
                .mapToDouble(TacticalTask::risk)
                .average()
                .orElse(0.0);
    }

    private static double distanceToNearestRouteStartSq(Vec2 position, List<RouteSnapshot> routes) {
        return routes.stream()
                .map(route -> route.keyPoints().get(0))
                .mapToDouble(position::distanceSq)
                .min()
                .orElse(Double.POSITIVE_INFINITY);
    }

    private static Vec2 routePoint(RouteSnapshot route, double progress) {
        int lastIndex = route.keyPoints().size() - 1;
        int index = Math.max(0, Math.min(lastIndex, (int) Math.round(lastIndex * progress)));
        return route.keyPoints().get(index);
    }

    private static boolean hasUsableRoute(RouteSnapshot route) {
        return route != null && route.routeId() != null && !route.routeId().isBlank()
                && route.keyPoints() != null && route.keyPoints().size() >= 2;
    }

    private static long planExpiry(TacticalPlan plan, long now) {
        return plan.orders().values().stream()
                .mapToLong(TacticalOrder::expiresAt)
                .filter(value -> value > now)
                .min()
                .orElse(now + 1_200L);
    }

    private static String stableId(String teamId, String tactic, String discriminator) {
        String identity = (teamId == null ? "" : teamId) + '|' + tactic + '|' + discriminator;
        return (teamId == null ? "TEAM" : teamId) + ':' + tactic + ':'
                + Integer.toUnsignedString(identity.hashCode(), 36);
    }

    private static double square(double value) {
        return value * value;
    }
}
