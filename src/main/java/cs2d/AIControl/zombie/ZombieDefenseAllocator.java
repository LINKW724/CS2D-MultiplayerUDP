package cs2d.AIControl.zombie;

import cs2d.AIControl.zombie.ZombieTacticalSnapshot.Unit;
import cs2d.AIControl.zombie.ZombieTacticalSnapshot.Vec;
import cs2d.AIControl.zombie.ZombieDefenseCorridor.Line;
import cs2d.AIControl.zombie.ZombieDefenseStateBoard.ActiveCorridor;
import java.util.*;

/** Allocates most ready survivors to corridor slots while retaining a mobile reserve. */
public final class ZombieDefenseAllocator {
    public enum Role { FORTIFY, REINFORCE, COVER_WITHDRAWAL, FALL_BACK_ONE, FALL_BACK_TWO, RESERVE }
    public record Assignment(Role role, String laneId, Vec destination, Vec watchPoint) {}

    public Map<String, Assignment> allocate(List<Unit> agents, List<ActiveCorridor> corridors) {
        List<Unit> ready = agents.stream().filter(Unit::ready).sorted(Comparator.comparing(Unit::id)).toList();
        if (ready.isEmpty() || corridors == null || corridors.isEmpty()) return Map.of();
        int reserveCount = ready.size() >= 4 ? Math.max(1, (int) Math.ceil(ready.size() * 0.20)) : 0;
        int deploymentLimit = Math.max(1, (int) Math.ceil(ready.size() * 0.72));
        Set<String> available = ready.stream().map(Unit::id)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        Map<String, Assignment> result = new HashMap<>();
        List<ActiveCorridor> ordered = corridors.stream()
                .sorted(Comparator.comparingDouble(this::urgency).reversed()
                        .thenComparing(c -> c.corridor().id()))
                .toList();
        int deployed = 0;
        for (ActiveCorridor active : ordered) {
            ZombieDefenseCorridor corridor = active.corridor();
            List<Vec> stations = corridor.stations(active.line());
            int capacity = Math.max(1, stations.size());
            int pressureDemand = Math.max(1, (int) Math.ceil(corridor.pressure() / 1.6));
            int demand = Math.min(capacity, pressureDemand);
            for (int i = 0; i < demand && available.size() > reserveCount
                    && deployed < deploymentLimit; i++) {
                Vec stationTarget = stations.get(i % stations.size());
                Unit agent = ready.stream().filter(a -> available.contains(a.id()))
                        .min(Comparator.comparingDouble(a -> a.position().distance(stationTarget)))
                        .orElse(null);
                if (agent == null) break;
                Vec destination = stationTarget;
                Role role = role(active.line(), i);
                if (active.coverRequired() && i == 0) {
                    Line coverLine = active.line() == Line.FALLBACK_TWO ? Line.FALLBACK_ONE : Line.FORWARD;
                    List<Vec> cover = corridor.stations(coverLine);
                    destination = cover.isEmpty() ? destination : cover.get(0);
                    role = Role.COVER_WITHDRAWAL;
                }
                result.put(agent.id(), new Assignment(role, corridor.id(), destination, corridor.watchPoint()));
                available.remove(agent.id());
                deployed++;
            }
        }
        if (reserveCount > 0 && !available.isEmpty()) {
            Vec center = weightedCenter(ordered);
            String reserveId = ready.stream().filter(a -> available.contains(a.id()))
                    .min(Comparator.comparingDouble(a -> a.position().distance(center))).orElseThrow().id();
            result.put(reserveId, new Assignment(Role.RESERVE, "reserve", center,
                    ordered.get(0).corridor().watchPoint()));
        }
        return Map.copyOf(result);
    }

    private static Role role(Line line, int index) {
        return switch (line) {
            case FORWARD -> index == 0 ? Role.FORTIFY : Role.REINFORCE;
            case FALLBACK_ONE -> Role.FALL_BACK_ONE;
            case FALLBACK_TWO -> Role.FALL_BACK_TWO;
        };
    }

    private double urgency(ActiveCorridor active) {
        ZombieDefenseCorridor corridor = active.corridor();
        double staleFactor = active.observedNow() ? 1.0 : 0.12;
        return staleFactor * corridor.pressure() / Math.max(1.0, corridor.etaMillis() / 1_000.0);
    }

    private static Vec weightedCenter(List<ActiveCorridor> corridors) {
        double weight = corridors.stream().mapToDouble(c -> Math.max(0.1, c.corridor().pressure())).sum();
        double x = corridors.stream().mapToDouble(c -> c.corridor().anchor(c.line()).x()
                * Math.max(0.1, c.corridor().pressure())).sum();
        double y = corridors.stream().mapToDouble(c -> c.corridor().anchor(c.line()).y()
                * Math.max(0.1, c.corridor().pressure())).sum();
        return new Vec(x / weight, y / weight);
    }
}
