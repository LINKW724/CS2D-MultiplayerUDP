package cs2d.AIControl.team;

import cs2d.AIControl.team.TacticalOrder.Role;
import cs2d.AIControl.team.TacticalOrder.TaskType;
import cs2d.AIControl.team.TeamTacticalSnapshot.AgentSnapshot;
import cs2d.AIControl.team.TeamTacticalSnapshot.ContactSnapshot;
import cs2d.AIControl.team.TeamTacticalSnapshot.ContactType;
import cs2d.AIControl.team.TeamTacticalSnapshot.HazardSnapshot;
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

    @Override
    public Map<String, TacticalOrder> coordinate(TeamTacticalSnapshot snapshot) {
        if (snapshot == null || snapshot.agents().isEmpty()) {
            return Map.of();
        }

        List<AgentSnapshot> agents = snapshot.agents().stream()
                .filter(agent -> agent != null && agent.id() != null && agent.position() != null)
                .sorted(Comparator.comparing(AgentSnapshot::id))
                .toList();
        if (agents.isEmpty()) {
            return Map.of();
        }

        Map<String, RouteStats> routes = buildRouteStats(agents, snapshot.hazards());
        Map<String, DraftOrder> drafts = new LinkedHashMap<>();
        double soundChaseDistance = soundChaseDistance(agents.size());

        for (AgentSnapshot agent : agents) {
            double routeRisk = routeRisk(agent, routes, snapshot.hazards());
            Role role = agent.routeId() == null ? Role.FREE : Role.ENTRY;
            TaskType task = agent.routeId() == null ? TaskType.CONTROL_ROUTE : TaskType.ADVANCE;
            drafts.put(agent.id(), new DraftOrder(agent, task, role, routeRisk, soundChaseDistance));
        }

        assignRouteAnchors(routes, drafts);
        assignSoundResponders(snapshot, agents, drafts);
        applyPopulationStrategy(agents, routes, drafts);
        applyIsolationRecovery(agents, drafts);
        applyRoutePressure(routes, drafts);

        Map<String, TacticalOrder> result = new LinkedHashMap<>();
        long expiresAt = snapshot.timestamp() + ORDER_TTL_MS;
        for (DraftOrder draft : drafts.values()) {
            result.put(draft.agent.id(), draft.toOrder(expiresAt));
        }
        return Map.copyOf(result);
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
            agents.stream()
                    .filter(agent -> !committedResponders.contains(agent.id()))
                    .filter(agent -> Math.sqrt(agent.position().distanceSq(cluster.center()))
                            <= drafts.get(agent.id()).maxSoundResponseDistance)
                    .sorted(Comparator
                            .comparingInt((AgentSnapshot agent) -> drafts.get(agent.id()).role == Role.ANCHOR ? 1 : 0)
                            .thenComparingDouble(agent -> agent.position().distanceSq(cluster.center()))
                            .thenComparing(AgentSnapshot::id))
                    .limit(limit)
                    .forEach(agent -> {
                        DraftOrder draft = drafts.get(agent.id());
                        draft.allowedSoundTargetIds.addAll(cluster.enemyIds());
                        draft.taskType = TaskType.RESPOND_TO_CONTACT;
                        committedResponders.add(agent.id());
                    });
        }
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
        draft.preserveMapRoute = false;
        draft.arrivalRadius = 180.0;
    }

    private Map<String, RouteStats> buildRouteStats(List<AgentSnapshot> agents, List<HazardSnapshot> hazards) {
        Map<String, List<AgentSnapshot>> grouped = new HashMap<>();
        for (AgentSnapshot agent : agents) {
            if (agent.routeId() != null && !agent.routeId().isBlank()) {
                grouped.computeIfAbsent(agent.routeId(), ignored -> new ArrayList<>()).add(agent);
            }
        }

        Map<String, RouteStats> result = new HashMap<>();
        for (Map.Entry<String, List<AgentSnapshot>> entry : grouped.entrySet()) {
            List<Vec2> routePoints = entry.getValue().stream()
                    .map(AgentSnapshot::routePoints)
                    .filter(points -> !points.isEmpty())
                    .findFirst()
                    .orElse(List.of());
            double risk = riskAlong(routePoints, entry.getValue(), hazards);
            result.put(entry.getKey(), new RouteStats(entry.getKey(), entry.getValue(), routePoints, risk));
        }
        return result;
    }

    private double routeRisk(AgentSnapshot agent, Map<String, RouteStats> routes, List<HazardSnapshot> hazards) {
        RouteStats route = routes.get(agent.routeId());
        if (route != null) {
            return route.risk;
        }
        return riskAt(agent.position(), hazards);
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
            return Math.max(1, Math.min(2, (int) Math.ceil(teamSize * 0.08)));
        }
        return Math.max(1, Math.min(4, (int) Math.ceil(teamSize * 0.16)));
    }

    private static double soundChaseDistance(int teamSize) {
        if (teamSize <= 1) {
            return 700.0;
        }
        if (teamSize == 2) {
            return 1_000.0;
        }
        if (teamSize <= 5) {
            return 1_600.0;
        }
        return 2_400.0;
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
        private final Set<String> allowedSoundTargetIds = new HashSet<>();
        private final double maxSoundResponseDistance;
        private final double routeRisk;

        private DraftOrder(AgentSnapshot agent, TaskType taskType, Role role, double routeRisk,
                double maxSoundResponseDistance) {
            this.agent = agent;
            this.taskType = taskType;
            this.role = role;
            this.routeId = agent.routeId();
            this.routeRisk = routeRisk;
            this.maxSoundResponseDistance = maxSoundResponseDistance;
        }

        private TacticalOrder toOrder(long expiresAt) {
            return new TacticalOrder(agent.id(), taskType, role, routeId, supportTargetId, movementTarget,
                    preserveMapRoute, arrivalRadius, allowedSoundTargetIds, maxSoundResponseDistance,
                    routeRisk, expiresAt);
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

    private record RouteStats(String routeId, List<AgentSnapshot> agents, List<Vec2> points, double risk) {
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
    }
}
