package cs2d.AIControl.zombie;

import cs2d.AIControl.zombie.ZombieTacticalSnapshot.Unit;
import cs2d.AIControl.zombie.ZombieTacticalSnapshot.Vec;
import java.util.*;

/** Converts predicted lane pressure into bounded fortify, reinforcement and reserve assignments. */
public final class ZombieDefenseAllocator {
    public enum Role { FORTIFY, REINFORCE, RESERVE }
    public record Assignment(Role role, String laneId, Vec destination, Vec watchPoint) {}

    public Map<String, Assignment> allocate(List<Unit> agents, List<ZombieAttackLane> lanes) {
        List<Unit> ready = agents.stream().filter(Unit::ready).sorted(Comparator.comparing(Unit::id)).toList();
        if (ready.isEmpty() || lanes == null || lanes.isEmpty()) return Map.of();
        int reserveCount = ready.size() >= 4 ? 1 : 0;
        Set<String> available = ready.stream().map(Unit::id)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        Map<String, Assignment> result = new HashMap<>();
        List<ZombieAttackLane> ordered = lanes.stream()
                .sorted(Comparator.comparingDouble(this::urgency).reversed().thenComparing(ZombieAttackLane::id))
                .toList();
        for (ZombieAttackLane lane : ordered) {
            int demand = Math.min(4, Math.max(1, (int) Math.ceil(lane.pressure() / 2.0)));
            for (int i = 0; i < demand && available.size() > reserveCount; i++) {
                Unit agent = ready.stream().filter(a -> available.contains(a.id()))
                        .min(Comparator.comparingDouble(a -> a.position().distance(lane.defensePoint())))
                        .orElse(null);
                if (agent == null) break;
                Role role = lane.etaMillis() <= 4_000 || lane.pressure() >= 4 ? Role.REINFORCE : Role.FORTIFY;
                result.put(agent.id(), new Assignment(role, lane.id(), lane.defensePoint(), lane.watchPoint()));
                available.remove(agent.id());
            }
        }
        if (reserveCount > 0 && !available.isEmpty()) {
            Vec center = weightedCenter(ordered);
            String reserveId = ready.stream().filter(a -> available.contains(a.id()))
                    .min(Comparator.comparingDouble(a -> a.position().distance(center))).orElseThrow().id();
            result.put(reserveId, new Assignment(Role.RESERVE, "reserve", center, ordered.get(0).watchPoint()));
        }
        return Map.copyOf(result);
    }

    private double urgency(ZombieAttackLane lane) {
        return lane.pressure() / Math.max(1.0, lane.etaMillis() / 1_000.0);
    }

    private static Vec weightedCenter(List<ZombieAttackLane> lanes) {
        double weight = lanes.stream().mapToDouble(l -> Math.max(0.1, l.pressure())).sum();
        double x = lanes.stream().mapToDouble(l -> l.defensePoint().x() * Math.max(0.1, l.pressure())).sum();
        double y = lanes.stream().mapToDouble(l -> l.defensePoint().y() * Math.max(0.1, l.pressure())).sum();
        return new Vec(x / weight, y / weight);
    }
}
