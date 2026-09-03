package cs2d.AIControl.team;

import cs2d.AIControl.team.TacticalOrder.Role;
import cs2d.AIControl.team.TacticalOrder.TaskType;
import cs2d.AIControl.team.TeamTacticalSnapshot.AgentSnapshot;
import cs2d.AIControl.team.TeamTacticalSnapshot.ContactSnapshot;
import cs2d.AIControl.team.TeamTacticalSnapshot.ContactType;
import cs2d.AIControl.team.TeamTacticalSnapshot.HazardSnapshot;
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
 * Elastic task coordinator for TDM. It scales from one agent to large teams,
 * uses map-route occupancy when available, and treats death/drop clusters as
 * route risk instead of forming fixed-size squads.
 */
public final class AdaptiveTeamTacticalCoordinator implements TacticalCoordinator {

    private static final long ORDER_TTL_MS = 1_200L;
    private static final long CONTACT_MEMORY_MS = 3_000L;
    private static final double PAIR_SUPPORT_DISTANCE = 550.0;
    private static final double ISOLATION_DISTANCE = 900.0;
    private static final double ROUTE_RISK_SWITCH_THRESHOLD = 1.0;
    private static final double HAZARD_DECAY_DISTANCE = 600.0;
    private static final double CONTACT_CLUSTER_DISTANCE = 700.0;
    private static final double LOCAL_SOUND_RESPONSE_DISTANCE = 520.0;
    private static final double ROUTE_SOUND_CORRIDOR = 500.0;
    private static final double CONTACT_OBJECTIVE_GRID = 160.0;
    private final RouteAssignmentPolicy routeAssignmentPolicy;
    private final List<TacticalPlanRefiner> planRefiners;
    private final TacticalPostureDoctrine postureDoctrine;

    public AdaptiveTeamTacticalCoordinator() {
        this(new BalancedRouteAssignmentPolicy(), List.of(
                new ElasticManeuverRefiner(), new FrontlineReinforcementRefiner()),
                new TdmPostureDoctrine());
    }

    /** Allows game modes or extensions to add/replace doctrines without changing this coordinator. */
    public AdaptiveTeamTacticalCoordinator(List<TacticalPlanRefiner> planRefiners) {
        this(new BalancedRouteAssignmentPolicy(), planRefiners, new TdmPostureDoctrine());
    }

    /** Allows alternate route allocation and maneuver doctrines to be injected independently. */
    public AdaptiveTeamTacticalCoordinator(RouteAssignmentPolicy routeAssignmentPolicy,
            List<TacticalPlanRefiner> planRefiners) {
        this(routeAssignmentPolicy, planRefiners, new TdmPostureDoctrine());
    }

    public AdaptiveTeamTacticalCoordinator(RouteAssignmentPolicy routeAssignmentPolicy,
            List<TacticalPlanRefiner> planRefiners, TacticalPostureDoctrine postureDoctrine) {
        this.routeAssignmentPolicy = routeAssignmentPolicy == null
                ? snapshot -> Map.of()
                : routeAssignmentPolicy;
        this.planRefiners = planRefiners == null ? List.of() : List.copyOf(planRefiners);
        this.postureDoctrine = postureDoctrine == null ? new TdmPostureDoctrine() : postureDoctrine;
    }

    @Override
    public Map<String, TacticalOrder> coordinate(TeamTacticalSnapshot snapshot) {
        return buildPlan(snapshot).orders();
    }

    @Override
    public TacticalPlan plan(TeamTacticalSnapshot snapshot) {
        return buildPlan(snapshot);
    }

    private TacticalPlan buildPlan(TeamTacticalSnapshot snapshot) {
        if (snapshot == null || snapshot.agents().isEmpty()) {
            return new TacticalPlan(List.of(), Map.of());
        }

        List<AgentSnapshot> agents = snapshot.agents().stream()
                .filter(agent -> agent != null && agent.id() != null && agent.position() != null)
                .sorted(Comparator.comparing(AgentSnapshot::id))
                .toList();
        if (agents.isEmpty()) {
            return new TacticalPlan(List.of(), Map.of());
        }

        Map<String, String> initialRouteAssignments = routeAssignmentPolicy.assign(snapshot);
        Map<String, RouteStats> routes = buildRouteStats(
                agents, snapshot.routes(), snapshot.hazards(), initialRouteAssignments);
        Map<String, DraftOrder> drafts = new LinkedHashMap<>();
        double soundChaseDistance = soundChaseDistance(agents.size());

        for (AgentSnapshot agent : agents) {
            String routeId = effectiveRouteId(agent, initialRouteAssignments);
            double routeRisk = routeRisk(agent, routeId, routes, snapshot.hazards());
            Role role = routeId == null ? Role.FREE : Role.ENTRY;
            TaskType task = routeId == null ? TaskType.CONTROL_ROUTE : TaskType.ADVANCE;
            DraftOrder draft = new DraftOrder(agent, task, role, routeId, routeRisk, soundChaseDistance);
            RouteStats route = routes.get(routeId);
            if (route != null) {
                draft.taskObjective = route.endpoint();
                draft.movementTarget = route.endpoint();
                draft.arrivalRadius = 180.0;
            }
            drafts.put(agent.id(), draft);
        }

        assignRouteAnchors(routes, drafts);
        assignSoundResponders(snapshot, agents, drafts);
        applyPopulationStrategy(agents, routes, drafts);
        applyIsolationRecovery(agents, drafts);
        applyRoutePressure(routes, drafts);

        long expiresAt = snapshot.timestamp() + ORDER_TTL_MS;
        TacticalPlan plan = createPlan(snapshot.teamId(), drafts, routes, expiresAt);
        for (TacticalPlanRefiner refiner : planRefiners) {
            if (refiner != null) {
                TacticalPlan refined = refiner.refine(snapshot, plan);
                plan = refined == null ? plan : refined;
            }
        }
        return applyPostureDoctrine(plan);
    }

    private TacticalPlan applyPostureDoctrine(TacticalPlan plan) {
        Map<String, TacticalOrder> orders = new LinkedHashMap<>();
        plan.orders().values().stream()
                .filter(java.util.Objects::nonNull)
                .sorted(Comparator.comparing(TacticalOrder::agentId))
                .forEach(order -> orders.put(order.agentId(),
                        order.withPosture(postureDoctrine.choose(order), 0L)));
        return new TacticalPlan(plan.tasks(), orders);
    }

    private void assignRouteAnchors(Map<String, RouteStats> routes, Map<String, DraftOrder> drafts) {
        for (RouteStats route : routes.values()) {
            route.agents.stream()
                    .min(Comparator.comparing(AgentSnapshot::id))
                    .map(agent -> drafts.get(agent.id()))
                    .ifPresent(draft -> draft.role = Role.ANCHOR);
        }
    }

    private void assignSoundResponders(TeamTacticalSnapshot snapshot, List<AgentSnapshot> agents,
            Map<String, DraftOrder> drafts) {
        List<ContactSnapshot> contacts = snapshot.contacts().stream()
                .filter(contact -> contact != null && contact.enemyId() != null && contact.position() != null)
                .filter(contact -> snapshot.timestamp() - contact.timestamp() <= CONTACT_MEMORY_MS)
                .sorted(Comparator.comparingLong(ContactSnapshot::timestamp).reversed())
                .toList();

        Set<String> committedResponders = new HashSet<>();
        for (ContactCluster cluster : clusterContacts(contacts)) {
            int limit = responseLimit(cluster.type(), agents.size());
            double responseDistance = soundResponseDistance(cluster.type(), agents.size());
            Vec2 objective = quantize(cluster.center(), CONTACT_OBJECTIVE_GRID);
            agents.stream()
                    .filter(agent -> !committedResponders.contains(agent.id()))
                    .filter(agent -> agents.size() <= 3 || drafts.get(agent.id()).role != Role.ANCHOR)
                    .filter(agent -> Math.sqrt(agent.position().distanceSq(objective)) <= responseDistance)
                    .filter(agent -> isSoundRelevantToRoute(agent, objective))
                    .sorted(Comparator
                            .comparingInt((AgentSnapshot agent) -> drafts.get(agent.id()).role == Role.ANCHOR ? 1 : 0)
                            .thenComparingDouble(agent -> agent.position().distanceSq(objective))
                            .thenComparing(AgentSnapshot::id))
                    .limit(limit)
                    .forEach(agent -> {
                        DraftOrder draft = drafts.get(agent.id());
                        draft.allowedSoundTargetIds.addAll(cluster.enemyIds());
                        draft.taskType = TaskType.RESPOND_TO_CONTACT;
                        draft.role = Role.SUPPORT;
                        draft.movementTarget = objective;
                        draft.taskObjective = objective;
                        draft.preserveMapRoute = false;
                        draft.arrivalRadius = 240.0;
                        draft.taskMaximumAgents = limit;
                        draft.maxSoundResponseDistance = responseDistance;
                        committedResponders.add(agent.id());
                    });
        }
    }

    private static boolean isSoundRelevantToRoute(AgentSnapshot agent, Vec2 soundPosition) {
        if (agent.position().distanceSq(soundPosition) <= square(LOCAL_SOUND_RESPONSE_DISTANCE)
                || agent.routePoints().isEmpty()) {
            return true;
        }
        List<Vec2> points = agent.routePoints();
        double corridorSq = square(ROUTE_SOUND_CORRIDOR);
        double minimumDistanceSq = Double.POSITIVE_INFINITY;
        for (int i = 0; i < points.size(); i++) {
            minimumDistanceSq = Math.min(minimumDistanceSq, points.get(i).distanceSq(soundPosition));
            if (i > 0) {
                minimumDistanceSq = Math.min(minimumDistanceSq,
                        pointToSegmentDistanceSq(soundPosition, points.get(i - 1), points.get(i)));
            }
        }
        if (minimumDistanceSq > corridorSq) {
            return false;
        }
        int agentProgress = nearestRoutePointIndex(points, agent.position());
        int soundProgress = nearestRoutePointIndex(points, soundPosition);
        return soundProgress + 1 >= agentProgress;
    }

    private static int nearestRoutePointIndex(List<Vec2> points, Vec2 position) {
        int nearestIndex = 0;
        double nearestDistanceSq = Double.POSITIVE_INFINITY;
        for (int i = 0; i < points.size(); i++) {
            double distanceSq = points.get(i).distanceSq(position);
            if (distanceSq < nearestDistanceSq) {
                nearestDistanceSq = distanceSq;
                nearestIndex = i;
            }
        }
        return nearestIndex;
    }

    private List<ContactCluster> clusterContacts(List<ContactSnapshot> contacts) {
        List<ContactCluster> clusters = new ArrayList<>();
        double clusterDistanceSq = square(CONTACT_CLUSTER_DISTANCE);
        for (ContactSnapshot contact : contacts) {
            ContactCluster match = clusters.stream()
                    .filter(cluster -> cluster.center().distanceSq(contact.position()) <= clusterDistanceSq)
                    .findFirst()
                    .orElse(null);
            if (match == null) {
                clusters.add(new ContactCluster(contact));
            } else {
                match.add(contact);
            }
        }
        return clusters;
    }

    private void applyPopulationStrategy(List<AgentSnapshot> agents, Map<String, RouteStats> routes,
            Map<String, DraftOrder> drafts) {
        if (agents.size() == 1) {
            DraftOrder only = drafts.get(agents.get(0).id());
            only.taskType = only.taskType == TaskType.RESPOND_TO_CONTACT
                    ? only.taskType
                    : TaskType.CONTROL_ROUTE;
            only.role = Role.ANCHOR;
            return;
        }

        if (agents.size() == 2) {
            AgentSnapshot anchor = agents.stream()
                    .min(Comparator.comparingDouble((AgentSnapshot agent) -> drafts.get(agent.id()).routeRisk)
                            .thenComparing(AgentSnapshot::id))
                    .orElse(agents.get(0));
            AgentSnapshot partner = agents.get(0).id().equals(anchor.id()) ? agents.get(1) : agents.get(0);
            DraftOrder anchorDraft = drafts.get(anchor.id());
            DraftOrder partnerDraft = drafts.get(partner.id());
            anchorDraft.role = Role.ANCHOR;

            if (partner.position().distanceSq(anchor.position()) > square(PAIR_SUPPORT_DISTANCE)
                    && partnerDraft.taskType != TaskType.RESPOND_TO_CONTACT) {
                partnerDraft.taskType = TaskType.SUPPORT;
                partnerDraft.role = Role.SUPPORT;
                partnerDraft.supportTargetId = anchor.id();
                partnerDraft.movementTarget = anchor.position();
                partnerDraft.taskObjective = anchor.position();
                partnerDraft.preserveMapRoute = false;
                partnerDraft.arrivalRadius = PAIR_SUPPORT_DISTANCE * 0.7;
            } else if (partnerDraft.taskType != TaskType.RESPOND_TO_CONTACT) {
                partnerDraft.role = Role.SUPPORT;
            }
            return;
        }

        if (agents.size() == 3 && !routes.isEmpty()) {
            RouteStats safest = routes.values().stream()
                    .min(Comparator.comparingDouble(RouteStats::risk).thenComparing(RouteStats::routeId))
                    .orElse(null);
            if (safest != null && !safest.agents.isEmpty()) {
                DraftOrder anchor = drafts.get(safest.agents.get(0).id());
                if (anchor.taskType != TaskType.RESPOND_TO_CONTACT) {
                    anchor.taskType = TaskType.CONTROL_ROUTE;
                    anchor.role = Role.ANCHOR;
                }
            }
        }
    }

    private void applyIsolationRecovery(List<AgentSnapshot> agents, Map<String, DraftOrder> drafts) {
        if (agents.size() < 2) {
            return;
        }

        for (AgentSnapshot agent : agents) {
            DraftOrder draft = drafts.get(agent.id());
            if (draft.taskType == TaskType.RESPOND_TO_CONTACT || draft.taskType == TaskType.SUPPORT) {
                continue;
            }

            AgentSnapshot nearest = agents.stream()
                    .filter(other -> !other.id().equals(agent.id()))
                    .min(Comparator
                            .comparingDouble((AgentSnapshot other) -> agent.position().distanceSq(other.position()))
                            .thenComparing(AgentSnapshot::id))
                    .orElse(null);
            if (nearest == null || agent.position().distanceSq(nearest.position()) <= square(ISOLATION_DISTANCE)) {
                continue;
            }

            DraftOrder nearestDraft = drafts.get(nearest.id());
            boolean agentShouldMove = draft.routeRisk > nearestDraft.routeRisk
                    || (Double.compare(draft.routeRisk, nearestDraft.routeRisk) == 0
                    && agent.id().compareTo(nearest.id()) > 0);
            if (agentShouldMove) {
                draft.taskType = TaskType.REGROUP;
                draft.role = Role.SUPPORT;
                draft.supportTargetId = nearest.id();
                draft.movementTarget = nearest.position();
                draft.taskObjective = nearest.position();
                draft.preserveMapRoute = false;
                draft.arrivalRadius = ISOLATION_DISTANCE * 0.55;
            }
        }
    }

    private void applyRoutePressure(Map<String, RouteStats> routes, Map<String, DraftOrder> drafts) {
        if (routes.size() < 2 || drafts.size() < 4) {
            return;
        }

        RouteStats safest = routes.values().stream()
                .min(Comparator.comparingDouble(RouteStats::assignmentScore).thenComparing(RouteStats::routeId))
                .orElse(null);
        RouteStats mostRisky = routes.values().stream()
                .max(Comparator.comparingDouble(RouteStats::risk).thenComparing(RouteStats::routeId))
                .orElse(null);
        RouteStats mostOccupied = routes.values().stream()
                .max(Comparator.comparingInt(RouteStats::occupancy).thenComparing(RouteStats::routeId))
                .orElse(null);

        if (safest == null) {
            return;
        }

        RouteStats source = null;
        if (mostRisky != null && mostRisky.occupancy() > 1
                && mostRisky.risk() - safest.risk() >= ROUTE_RISK_SWITCH_THRESHOLD) {
            source = mostRisky;
        } else if (mostOccupied != null && mostOccupied.occupancy() - safest.occupancy() > 1) {
            source = mostOccupied;
        }

        if (source == null || source.routeId.equals(safest.routeId)) {
            return;
        }

        AgentSnapshot mover = source.agents.stream()
                .sorted(Comparator.comparing(AgentSnapshot::id).reversed())
                .filter(agent -> {
                    DraftOrder draft = drafts.get(agent.id());
                    return draft.role != Role.ANCHOR
                            && draft.taskType != TaskType.RESPOND_TO_CONTACT
                            && draft.taskType != TaskType.REGROUP;
                })
                .findFirst()
                .orElse(null);
        Vec2 flankTarget = safest.midpoint();
        if (mover == null || flankTarget == null) {
            return;
        }

        DraftOrder draft = drafts.get(mover.id());
        draft.taskType = TaskType.FLANK;
        draft.role = Role.FLANKER;
        draft.routeId = safest.routeId;
        draft.movementTarget = flankTarget;
        draft.taskObjective = flankTarget;
        draft.preserveMapRoute = false;
        draft.arrivalRadius = 180.0;
    }

    private TacticalPlan createPlan(String teamId, Map<String, DraftOrder> drafts,
            Map<String, RouteStats> routes, long expiresAt) {
        Map<TaskGroupKey, List<DraftOrder>> groups = new LinkedHashMap<>();
        drafts.values().stream()
                .sorted(Comparator.comparing(draft -> draft.agent.id()))
                .forEach(draft -> groups.computeIfAbsent(taskGroupKey(draft), ignored -> new ArrayList<>()).add(draft));

        List<TacticalTask> tasks = new ArrayList<>();
        Map<String, TacticalOrder> orders = new LinkedHashMap<>();
        groups.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .forEach(entry -> {
                    TaskGroupKey key = entry.getKey();
                    List<DraftOrder> assigned = entry.getValue();
                    String taskId = stableTaskId(teamId, key);
                    TacticalTask task = createTask(taskId, key, assigned, routes, expiresAt);
                    tasks.add(task);
                    assigned.forEach(draft -> orders.put(draft.agent.id(), draft.toOrder(expiresAt, taskId)));
                });
        return new TacticalPlan(tasks, orders);
    }

    private TacticalTask createTask(String taskId, TaskGroupKey key, List<DraftOrder> assigned,
            Map<String, RouteStats> routes, long expiresAt) {
        DraftOrder representative = assigned.get(0);
        RouteStats route = routes.get(key.routeId());
        int maximumAgents = switch (key.taskType()) {
            case RESPOND_TO_CONTACT -> Math.max(1, representative.taskMaximumAgents);
            case FLANK, SUPPORT, REGROUP -> 2;
            case SUPPRESS, ASSEMBLE -> assigned.size();
            case CONTROL_ROUTE, ADVANCE -> Math.max(assigned.size(),
                    route != null && route.suggestedCapacity() > 0 ? route.suggestedCapacity() : 1);
        };
        int priority = switch (key.taskType()) {
            case REGROUP -> 90;
            case SUPPORT -> 80;
            case RESPOND_TO_CONTACT -> 70;
            case FLANK -> 60;
            case SUPPRESS -> 55;
            case ASSEMBLE -> 85;
            case CONTROL_ROUTE -> 50;
            case ADVANCE -> 40;
        };
        TacticalTask.EngagementRule engagementRule = switch (key.taskType()) {
            case RESPOND_TO_CONTACT -> TacticalTask.EngagementRule.ASSIGNED_CONTACTS;
            case FLANK, SUPPRESS, ASSEMBLE -> TacticalTask.EngagementRule.IGNORE_REMOTE_SOUNDS;
            default -> TacticalTask.EngagementRule.LOCAL_ONLY;
        };
        Vec2 objective = representative.taskObjective;
        if (objective == null && route != null) {
            objective = route.midpoint();
        }
        double risk = assigned.stream().mapToDouble(draft -> draft.routeRisk).average().orElse(0.0);
        Set<String> allowedTargets = assigned.stream()
                .flatMap(draft -> draft.allowedSoundTargetIds.stream())
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        return new TacticalTask(taskId, key.taskType(), key.routeId(), objective,
                1, maximumAgents, priority, risk, engagementRule,
                representative.arrivalRadius, allowedTargets, expiresAt);
    }

    private static TaskGroupKey taskGroupKey(DraftOrder draft) {
        String discriminator;
        if (draft.taskType == TaskType.RESPOND_TO_CONTACT) {
            discriminator = draft.allowedSoundTargetIds.stream().sorted()
                    .collect(java.util.stream.Collectors.joining(","));
        } else if (draft.supportTargetId != null) {
            discriminator = draft.supportTargetId;
        } else if (draft.routeId != null) {
            discriminator = draft.routeId;
        } else {
            discriminator = "unrouted";
        }
        return new TaskGroupKey(draft.taskType, draft.routeId, discriminator);
    }

    private static String stableTaskId(String teamId, TaskGroupKey key) {
        String identity = (teamId == null ? "" : teamId) + '|' + key.taskType() + '|'
                + (key.routeId() == null ? "" : key.routeId()) + '|' + key.discriminator();
        return (teamId == null ? "TEAM" : teamId) + ':' + key.taskType().name().toLowerCase()
                + ':' + Integer.toUnsignedString(identity.hashCode(), 36);
    }

    private Map<String, RouteStats> buildRouteStats(List<AgentSnapshot> agents, List<RouteSnapshot> catalogRoutes,
            List<HazardSnapshot> hazards, Map<String, String> initialRouteAssignments) {
        Map<String, List<AgentSnapshot>> grouped = new HashMap<>();
        for (AgentSnapshot agent : agents) {
            String routeId = effectiveRouteId(agent, initialRouteAssignments);
            if (routeId != null) {
                grouped.computeIfAbsent(routeId, ignored -> new ArrayList<>()).add(agent);
            }
        }

        Map<String, RouteStats> result = new HashMap<>();
        for (RouteSnapshot route : catalogRoutes) {
            if (route == null || route.routeId() == null || route.routeId().isBlank()) {
                continue;
            }
            List<AgentSnapshot> assigned = grouped.getOrDefault(route.routeId(), List.of());
            double risk = riskAlong(route.keyPoints(), assigned, hazards);
            result.put(route.routeId(), new RouteStats(route.routeId(), assigned, route.keyPoints(), risk,
                    route.suggestedCapacity(), route.overlappingRouteIds()));
        }
        for (Map.Entry<String, List<AgentSnapshot>> entry : grouped.entrySet()) {
            if (result.containsKey(entry.getKey())) {
                continue;
            }
            List<Vec2> routePoints = entry.getValue().stream()
                    .map(AgentSnapshot::routePoints)
                    .filter(points -> !points.isEmpty())
                    .findFirst()
                    .orElse(List.of());
            double risk = riskAlong(routePoints, entry.getValue(), hazards);
            result.put(entry.getKey(), new RouteStats(entry.getKey(), entry.getValue(), routePoints, risk,
                    0, List.of()));
        }
        return result;
    }

    private double routeRisk(AgentSnapshot agent, String routeId,
            Map<String, RouteStats> routes, List<HazardSnapshot> hazards) {
        RouteStats route = routes.get(routeId);
        if (route != null) {
            return route.risk;
        }
        return riskAt(agent.position(), hazards);
    }

    private static String effectiveRouteId(AgentSnapshot agent, Map<String, String> initialRouteAssignments) {
        if (agent.routeId() != null && !agent.routeId().isBlank()) {
            return agent.routeId();
        }
        return initialRouteAssignments.get(agent.id());
    }

    private double riskAlong(List<Vec2> routePoints, List<AgentSnapshot> agents, List<HazardSnapshot> hazards) {
        if (routePoints == null || routePoints.isEmpty()) {
            return agents.stream().mapToDouble(agent -> riskAt(agent.position(), hazards)).average().orElse(0.0);
        }
        double total = 0.0;
        for (HazardSnapshot hazard : hazards) {
            if (hazard == null || hazard.position() == null || hazard.weight() <= 0) {
                continue;
            }
            double minDistanceSq = Double.POSITIVE_INFINITY;
            for (int i = 0; i < routePoints.size(); i++) {
                minDistanceSq = Math.min(minDistanceSq, routePoints.get(i).distanceSq(hazard.position()));
                if (i > 0) {
                    minDistanceSq = Math.min(minDistanceSq,
                            pointToSegmentDistanceSq(hazard.position(), routePoints.get(i - 1), routePoints.get(i)));
                }
            }
            total += hazard.weight() * Math.exp(-Math.sqrt(minDistanceSq) / HAZARD_DECAY_DISTANCE);
        }
        return total;
    }

    private double riskAt(Vec2 position, List<HazardSnapshot> hazards) {
        double total = 0.0;
        if (position == null) {
            return total;
        }
        for (HazardSnapshot hazard : hazards) {
            if (hazard != null && hazard.position() != null && hazard.weight() > 0) {
                total += hazard.weight()
                        * Math.exp(-Math.sqrt(position.distanceSq(hazard.position())) / HAZARD_DECAY_DISTANCE);
            }
        }
        return total;
    }

    private static double pointToSegmentDistanceSq(Vec2 point, Vec2 start, Vec2 end) {
        double dx = end.x() - start.x();
        double dy = end.y() - start.y();
        double lengthSq = dx * dx + dy * dy;
        if (lengthSq <= 1.0e-9) {
            return point.distanceSq(start);
        }
        double t = ((point.x() - start.x()) * dx + (point.y() - start.y()) * dy) / lengthSq;
        t = Math.max(0.0, Math.min(1.0, t));
        double nearestX = start.x() + t * dx;
        double nearestY = start.y() + t * dy;
        return square(point.x() - nearestX) + square(point.y() - nearestY);
    }

    private static int responseLimit(ContactType type, int teamSize) {
        if (type == ContactType.FOOTSTEP) {
            return 1;
        }
        return Math.max(1, Math.min(3, (int) Math.ceil(teamSize * 0.10)));
    }

    private static double soundChaseDistance(int teamSize) {
        return soundResponseDistance(ContactType.GUNSHOT, teamSize);
    }

    private static double soundResponseDistance(ContactType type, int teamSize) {
        if (type == ContactType.FOOTSTEP) {
            return teamSize <= 2 ? 520.0 : 650.0;
        }
        if (teamSize <= 1) {
            return 650.0;
        }
        if (teamSize == 2) {
            return 800.0;
        }
        return 900.0;
    }

    private static Vec2 quantize(Vec2 position, double grid) {
        return new Vec2(Math.rint(position.x() / grid) * grid,
                Math.rint(position.y() / grid) * grid);
    }

    private static double square(double value) {
        return value * value;
    }

    private static final class DraftOrder {
        private final AgentSnapshot agent;
        private TaskType taskType;
        private Role role;
        private String routeId;
        private String supportTargetId;
        private Vec2 movementTarget;
        private boolean preserveMapRoute = true;
        private double arrivalRadius = 120.0;
        private Vec2 taskObjective;
        private int taskMaximumAgents = 1;
        private final Set<String> allowedSoundTargetIds = new HashSet<>();
        private double maxSoundResponseDistance;
        private final double routeRisk;

        private DraftOrder(AgentSnapshot agent, TaskType taskType, Role role, String routeId, double routeRisk,
                double maxSoundResponseDistance) {
            this.agent = agent;
            this.taskType = taskType;
            this.role = role;
            this.routeId = routeId;
            this.routeRisk = routeRisk;
            this.maxSoundResponseDistance = maxSoundResponseDistance;
        }

        private TacticalOrder toOrder(long expiresAt, String taskId) {
            return new TacticalOrder(agent.id(), taskType, role, routeId, supportTargetId, movementTarget,
                    preserveMapRoute, arrivalRadius, allowedSoundTargetIds, maxSoundResponseDistance,
                    routeRisk, expiresAt, taskId);
        }
    }

    private record TaskGroupKey(TaskType taskType, String routeId, String discriminator)
            implements Comparable<TaskGroupKey> {
        @Override
        public int compareTo(TaskGroupKey other) {
            int typeComparison = taskType.compareTo(other.taskType);
            if (typeComparison != 0) {
                return typeComparison;
            }
            int routeComparison = nullSafe(routeId).compareTo(nullSafe(other.routeId));
            return routeComparison != 0
                    ? routeComparison
                    : nullSafe(discriminator).compareTo(nullSafe(other.discriminator));
        }

        private static String nullSafe(String value) {
            return value == null ? "" : value;
        }
    }

    private static final class ContactCluster {
        private final Set<String> enemyIds = new HashSet<>();
        private double x;
        private double y;
        private int count;
        private ContactType type;

        private ContactCluster(ContactSnapshot contact) {
            add(contact);
        }

        private void add(ContactSnapshot contact) {
            enemyIds.add(contact.enemyId());
            count++;
            x += (contact.position().x() - x) / count;
            y += (contact.position().y() - y) / count;
            if (type == null || contact.type() == ContactType.GUNSHOT) {
                type = contact.type();
            }
        }

        private Vec2 center() {
            return new Vec2(x, y);
        }

        private Set<String> enemyIds() {
            return enemyIds;
        }

        private ContactType type() {
            return type;
        }
    }

    private record RouteStats(String routeId, List<AgentSnapshot> agents, List<Vec2> points, double risk,
            int suggestedCapacity, List<String> overlappingRouteIds) {
        private int occupancy() {
            return agents.size();
        }

        private double assignmentScore() {
            return occupancy() + risk * 0.75;
        }

        private Vec2 midpoint() {
            if (!points.isEmpty()) {
                return points.get(points.size() / 2);
            }
            return agents.isEmpty() ? null : agents.get(0).position();
        }

        private Vec2 endpoint() {
            if (!points.isEmpty()) {
                return points.get(points.size() - 1);
            }
            return agents.isEmpty() ? null : agents.get(0).position();
        }
    }
}
