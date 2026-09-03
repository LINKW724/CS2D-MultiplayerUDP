package cs2d.AIControl.team;

import cs2d.AIControl.team.TacticalOrder.Posture;

import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Keeps a commander's posture decision stable while still allowing the plan to
 * refresh objectives at a lower level. This class owns no movement or combat
 * implementation details.
 */
public final class TacticalPostureCommitmentBoard {

    static final long MIN_COMMIT_MS = 3_000L;
    static final long COMMIT_SPREAD_MS = 3_001L;

    private final Map<String, Commitment> commitments = new HashMap<>();

    public Map<String, TacticalOrder> reconcile(String teamId, Map<String, TacticalOrder> proposed, long now) {
        Map<String, TacticalOrder> safeProposed = proposed == null ? Map.of() : proposed;
        Map<String, TacticalOrder> result = new LinkedHashMap<>();
        String teamKey = teamId == null ? "" : teamId;

        safeProposed.values().stream()
                .filter(order -> order != null && order.agentId() != null && order.isActive(now))
                .sorted(java.util.Comparator.comparing(TacticalOrder::agentId))
                .forEach(order -> {
                    String key = teamKey + ':' + order.agentId();
                    Commitment previous = commitments.get(key);
                    Posture proposedPosture = order.posture();
                    Posture acceptedPosture = proposedPosture;
                    long commitUntil;

                    if (previous != null && now < previous.commitUntil()
                            && proposedPosture != Posture.ENGAGE
                            && !isSafetyDowngrade(previous.posture(), proposedPosture)) {
                        acceptedPosture = previous.posture();
                        commitUntil = previous.commitUntil();
                    } else {
                        commitUntil = now + commitmentDuration(order);
                    }

                    commitments.put(key, new Commitment(acceptedPosture, commitUntil));
                    result.put(order.agentId(), order.withPosture(acceptedPosture, commitUntil));
                });

        commitments.keySet().removeIf(key -> key.startsWith(teamKey + ':')
                && safeProposed.values().stream().noneMatch(order -> order != null
                && (teamKey + ':' + order.agentId()).equals(key)));
        return Map.copyOf(result);
    }

    public void clear() {
        commitments.clear();
    }

    private static long commitmentDuration(TacticalOrder order) {
        String identity = String.valueOf(order.agentId()) + '|' + String.valueOf(order.taskId())
                + '|' + order.posture();
        return MIN_COMMIT_MS + Integer.toUnsignedLong(identity.hashCode()) % COMMIT_SPREAD_MS;
    }

    private static boolean isSafetyDowngrade(Posture current, Posture proposed) {
        return aggressiveness(proposed) < aggressiveness(current);
    }

    private static int aggressiveness(Posture posture) {
        return switch (posture) {
            case AMBUSH -> 0;
            case STEALTH_ADVANCE -> 1;
            case RUSH -> 2;
            case ENGAGE -> 3;
        };
    }

    private record Commitment(Posture posture, long commitUntil) {
    }
}
