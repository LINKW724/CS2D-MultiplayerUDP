package cs2d.AIControl.zombie;

import cs2d.AIControl.zombie.ZombieDefenseCorridor.Line;
import cs2d.AIControl.zombie.ZombieTacticalSnapshot.Unit;
import java.util.*;

/** Retains corridor intent and applies hysteresis while the front advances or withdraws. */
public final class ZombieDefenseStateBoard {
    private static final long CORRIDOR_LEASE_MS = 30_000;
    private static final long ADVANCE_HYSTERESIS_MS = 8_000;
    private final Map<String, State> states = new HashMap<>();

    public List<ActiveCorridor> update(List<ZombieDefenseCorridor> observed, List<Unit> agents, long now) {
        double firepower = agents.stream().filter(Unit::ready).mapToDouble(Unit::strength).sum();
        Set<String> observedIds = new HashSet<>();
        for (ZombieDefenseCorridor corridor : observed) {
            observedIds.add(corridor.id());
            State old = states.get(corridor.id());
            Line desired = desiredLine(corridor, firepower);
            Line line = old == null ? desired : applyHysteresis(old, desired, now);
            long lineSince = old == null || old.line() != line ? now : old.lineSince();
            states.put(corridor.id(), new State(corridor, line, lineSince, now));
        }
        states.entrySet().removeIf(entry -> now - entry.getValue().lastSeenAt() > CORRIDOR_LEASE_MS);
        return states.values().stream().map(state -> {
            boolean fallbackReady = agents.stream().filter(Unit::ready)
                    .anyMatch(agent -> agent.position().distance(state.corridor().anchor(state.line())) <= 120);
            boolean coverRequired = state.line() != Line.FORWARD && !fallbackReady
                    && now - state.lineSince() <= 5_000;
            return new ActiveCorridor(state.corridor(), state.line(),
                    observedIds.contains(state.corridor().id()), coverRequired);
        })
                .sorted(Comparator.comparingDouble((ActiveCorridor c) -> c.corridor().pressure()).reversed()
                        .thenComparing(c -> c.corridor().id())).toList();
    }

    public void clear() { states.clear(); }

    private static Line desiredLine(ZombieDefenseCorridor corridor, double firepower) {
        double ratio = corridor.pressure() / Math.max(1, firepower);
        if (corridor.etaMillis() <= 1_800 || ratio >= 1.25) return Line.FALLBACK_TWO;
        if (corridor.etaMillis() <= 4_000 || ratio >= 0.80) return Line.FALLBACK_ONE;
        return Line.FORWARD;
    }

    private static Line applyHysteresis(State old, Line desired, long now) {
        if (desired.ordinal() > old.line().ordinal()) return desired;
        if (desired.ordinal() < old.line().ordinal() && now - old.lineSince() < ADVANCE_HYSTERESIS_MS)
            return old.line();
        return desired;
    }

    public record ActiveCorridor(ZombieDefenseCorridor corridor, Line line,
            boolean observedNow, boolean coverRequired) {
        public ActiveCorridor(ZombieDefenseCorridor corridor, Line line, boolean observedNow) {
            this(corridor, line, observedNow, line != Line.FORWARD);
        }
    }
    private record State(ZombieDefenseCorridor corridor, Line line, long lineSince, long lastSeenAt) {}
}
