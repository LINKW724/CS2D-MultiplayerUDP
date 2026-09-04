package cs2d.AIControl.zombie;

import cs2d.AIControl.zombie.ZombieTacticalSnapshot.*;
import cs2d.AIControl.zombie.ZombieTacticalOrder.Task;
import java.util.*;
import java.util.function.Predicate;

/** Survivor commander: reinforce active fronts, stage predicted lanes, and retain local safety overrides. */
public final class ZombieTacticalCoordinator {
    public static final int CLEANUP_REMAINDER_THRESHOLD = 3;
    private static final int CLEANUP_AGENTS_PER_TARGET = 2;
    private final ZombieThreatEvaluator threats = new ZombieThreatEvaluator();
    private final ZombiePositionPlanner positions = new ZombiePositionPlanner();
    private final ZombieDefenseAllocator defenseAllocator = new ZombieDefenseAllocator();
    private final ZombieCombatFrontPlanner frontPlanner = new ZombieCombatFrontPlanner();
    private final ZombieFrontSupportAllocator frontAllocator = new ZombieFrontSupportAllocator();

    public Map<String, ZombieTacticalOrder> plan(ZombieTacticalSnapshot snapshot,
            ZombieTacticalTaskBoard board, Predicate<Vec> walkable) {
        List<Unit> agents = snapshot.allies().stream().filter(Unit::independentAi)
                .sorted(Comparator.comparing(Unit::id)).toList();
        board.retain(agents.stream().map(Unit::id).collect(java.util.stream.Collectors.toSet()));
        Map<String, Vec> homes = new HashMap<>();
        for (Unit agent : agents) {
            homes.put(agent.id(), board.home(agent.id(), () -> positions.choose(agent.position(),
                    agent.position(), List.of(), board.stations(), snapshot.hazards(), snapshot.now(),
                    walkable, null)));
        }
        int ready = (int) agents.stream().filter(Unit::ready).count();
        int limit = Math.min(2, Math.max(1, ready / 3));
        List<Unit> candidates = agents.stream().filter(Unit::ready)
                .filter(a -> board.canClear(a.id(), snapshot.now()))
                .filter(a -> a.position().distance(homes.get(a.id())) <= 650)
                .filter(a -> threats.assess(a.position(), snapshot).canAdvance())
                .filter(a -> snapshot.allies().stream().noneMatch(u -> u.reloading()
                        && u.position().distance(a.position()) < 450)
                        || snapshot.allies().stream().filter(Unit::ready)
                        .filter(u -> u.position().distance(a.position()) < 900).count() > 1)
                .sorted(Comparator.<Unit>comparingInt(a -> {
                    ZombieTacticalOrder old = board.current(a.id());
                    return old != null && old.task() == Task.CLEAR_THREAT ? 0 : 1;
                }).thenComparingDouble(a -> -a.strength()).thenComparing(Unit::id))
                .limit(limit).toList();
        Set<String> clearing = candidates.stream().map(Unit::id).collect(java.util.stream.Collectors.toSet());
        Map<String, Contact> cleanupAssignments = assignCleanup(snapshot, agents);
        List<ZombieCombatFront> combatFronts = frontPlanner.detect(snapshot, walkable);
        Map<String, ZombieFrontSupportAllocator.Assignment> frontAssignments =
                frontAllocator.allocate(agents, combatFronts);
        Map<String, ZombieDefenseAllocator.Assignment> defenseAssignments =
                defenseAllocator.allocate(agents, snapshot.attackLanes());
        Map<String, ZombieTacticalOrder> result = new LinkedHashMap<>();
        for (Unit agent : agents) {
            Vec home = homes.get(agent.id());
            ZombieThreatEvaluator.Assessment assessment = threats.assess(agent.position(), snapshot);
            Contact nearest = assessment.threats().stream()
                    .min(Comparator.comparingDouble(c -> c.position().distance(agent.position()))).orElse(null);
            Contact cleanupTarget = cleanupAssignments.get(agent.id());
            String targetId = nearest == null ? null : nearest.id();
            List<Vec> threatPoints = assessment.threats().stream().map(Contact::position).toList();
            List<Vec> teammates = snapshot.allies().stream().filter(a -> !a.id().equals(agent.id()))
                    .map(Unit::position).toList();
            Task task = agent.position().distance(home) > 100 ? Task.RETURN_TO_GUARD : Task.GUARD_AREA;
            Vec destination = home;
            Vec watchPoint = null;
            if (assessment.imminent()) {
                task = Task.REPOSITION;
                destination = positions.choose(agent.position(), home, threatPoints, teammates,
                        snapshot.hazards(), snapshot.now(), walkable, null);
            } else if (cleanupTarget != null) {
                task = Task.HUNT_REMAINDER;
                targetId = cleanupTarget.id();
                destination = positions.choose(agent.position(), cleanupTarget.position(), List.of(), teammates,
                        snapshot.hazards(), snapshot.now(), walkable, cleanupTarget.position());
                watchPoint = cleanupTarget.position();
            } else if (nearest != null && agent.ready() && snapshot.allies().stream()
                    .anyMatch(a -> a.reloading() && a.position().distance(agent.position()) < 450)) {
                task = Task.COVER_RELOAD; // Immediate local support takes precedence over distant deployment.
                watchPoint = nearest.position();
            } else if (frontAssignments.containsKey(agent.id())) {
                ZombieFrontSupportAllocator.Assignment assignment = frontAssignments.get(agent.id());
                task = assignment.role() == ZombieFrontSupportAllocator.Role.HOLD
                        ? Task.HOLD_FRONT : Task.SUPPORT_FRONT;
                targetId = assignment.frontId();
                destination = assignment.destination();
                watchPoint = assignment.watchPoint();
            } else if (defenseAssignments.containsKey(agent.id())) {
                ZombieDefenseAllocator.Assignment assignment = defenseAssignments.get(agent.id());
                task = switch (assignment.role()) {
                    case FORTIFY -> Task.FORTIFY_LANE;
                    case REINFORCE -> Task.REINFORCE_LANE;
                    case RESERVE -> Task.MOBILE_RESERVE;
                };
                targetId = assignment.laneId();
                watchPoint = assignment.watchPoint();
                destination = positions.choose(agent.position(), assignment.destination(), threatPoints, teammates,
                        snapshot.hazards(), snapshot.now(), walkable, watchPoint);
            } else if (clearing.contains(agent.id()) && nearest != null
                    && nearest.position().distance(home) <= 650) {
                task = Task.CLEAR_THREAT;
                double distance = agent.position().distance(nearest.position());
                double travel = Math.max(0, distance - 280);
                Vec desired = new Vec(agent.position().x() + (nearest.position().x() - agent.position().x())
                        * travel / Math.max(1, distance),
                        agent.position().y() + (nearest.position().y() - agent.position().y())
                        * travel / Math.max(1, distance));
                destination = positions.choose(agent.position(), desired, List.of(), teammates,
                        snapshot.hazards(), snapshot.now(), walkable, null);
                if (!positions.safeSegment(agent.position(), destination, threatPoints,
                        snapshot.hazards(), snapshot.now(), walkable)) {
                    task = Task.GUARD_AREA;
                    destination = home;
                }
            }
            result.put(agent.id(), board.assign(agent.id(), task, targetId,
                    destination, home, watchPoint, snapshot.now()));
        }
        return Map.copyOf(result);
    }

    private static Map<String, Contact> assignCleanup(ZombieTacticalSnapshot snapshot, List<Unit> agents) {
        if (snapshot.remainingZombies() <= 0
                || snapshot.remainingZombies() > CLEANUP_REMAINDER_THRESHOLD
                || snapshot.cleanupLeads().isEmpty()) return Map.of();
        Set<String> available = agents.stream().filter(Unit::ready).map(Unit::id)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        Map<String, Contact> assignments = new HashMap<>();
        for (Contact lead : snapshot.cleanupLeads().stream().sorted(Comparator.comparing(Contact::id)).toList()) {
            agents.stream().filter(Unit::ready).filter(agent -> available.contains(agent.id()))
                    .sorted(Comparator.comparingDouble(agent -> agent.position().distance(lead.position())))
                    .limit(CLEANUP_AGENTS_PER_TARGET).forEach(agent -> {
                        assignments.put(agent.id(), lead);
                        available.remove(agent.id());
                    });
        }
        return assignments;
    }
}
