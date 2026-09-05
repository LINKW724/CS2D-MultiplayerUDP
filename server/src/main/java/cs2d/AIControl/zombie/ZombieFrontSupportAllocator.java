package cs2d.AIControl.zombie;

import cs2d.AIControl.zombie.ZombieTacticalSnapshot.Unit;
import cs2d.AIControl.zombie.ZombieTacticalSnapshot.Vec;
import java.util.*;

/** Keeps current shooters on a front and fills only its measured firepower deficit. */
public final class ZombieFrontSupportAllocator {
    public enum Role { HOLD, SUPPORT }
    public record Assignment(Role role, String frontId, Vec destination, Vec watchPoint) {}

    public Map<String, Assignment> allocate(List<Unit> agents, List<ZombieCombatFront> fronts) {
        if (fronts == null || fronts.isEmpty()) return Map.of();
        List<Unit> ready = agents.stream().filter(Unit::ready).filter(Unit::independentAi)
                .sorted(Comparator.comparing(Unit::id)).toList();
        if (ready.isEmpty()) return Map.of();
        Map<String, Assignment> result = new HashMap<>();
        Set<String> available = ready.stream().map(Unit::id)
                .collect(java.util.stream.Collectors.toCollection(LinkedHashSet::new));
        for (ZombieCombatFront front : fronts) {
            ready.stream().filter(a -> available.contains(a.id()))
                    .filter(a -> front.engagedAgentIds().contains(a.id()))
                    .forEach(a -> {
                        result.put(a.id(), new Assignment(Role.HOLD, front.id(), a.position(), front.watchPoint()));
                        available.remove(a.id());
                    });
        }
        int reserve = ready.size() >= 4 && fronts.stream().noneMatch(f ->
                f.pressure() >= 6 && f.engagedAgentIds().size() < 2) ? 1 : 0;
        for (ZombieCombatFront front : fronts) {
            long holding = result.values().stream().filter(a -> a.frontId().equals(front.id())).count();
            int needed = Math.max(0, front.requiredDefenders() - (int) holding);
            for (int i = 0; i < needed && available.size() > reserve; i++) {
                Unit selected = ready.stream().filter(a -> available.contains(a.id()))
                        .min(Comparator.comparingDouble(a -> assignmentCost(a, front)))
                        .orElse(null);
                if (selected == null) break;
                result.put(selected.id(), new Assignment(Role.SUPPORT, front.id(),
                        front.rallyPoint(), front.watchPoint()));
                available.remove(selected.id());
            }
        }
        return Map.copyOf(result);
    }

    private static double assignmentCost(Unit agent, ZombieCombatFront front) {
        // High-firepower weapons may travel modestly farther to anchor a pressured front.
        return agent.position().distance(front.rallyPoint()) - agent.strength() * 140;
    }
}
