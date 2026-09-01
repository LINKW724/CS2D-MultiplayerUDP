package cs2d.AIControl.movement;

import java.util.List;

/**
 * Stateful local steering planner for teammate separation.
 *
 * <p>The planner deliberately keeps a side-step commitment across simulation
 * frames. A noisy repulsion vector therefore cannot turn into A/D/A/D input.
 * Route following remains the BASE intent and this class only proposes a
 * perpendicular STEERING correction when this agent has to yield.</p>
 */
public final class LocalAvoidancePlanner {
    static final long STEERING_HOLD_MS = 600;
    static final long OPPOSITE_CONFIRM_MS = 250;
    static final long CLEAR_GRACE_MS = 120;
    private static final long UNSTUCK_SIDE_HOLD_MS = 3_000;
    private static final double FORCE_THRESHOLD = 0.60;

    private String committedKey;
    private long commitmentUntil;
    private String pendingOppositeKey;
    private long pendingOppositeSince;
    private long clearSince;
    private int unstuckSide;
    private long lastUnstuckChoiceTime;

    public MovementIntent plan(long now, String ownerId, List<String> baseKeys, Observation observation) {
        Observation safeObservation = observation == null ? Observation.clear() : observation;
        String proposedKey = choosePerpendicularKey(ownerId, baseKeys, safeObservation);

        if (proposedKey == null) {
            return MovementIntent.steering("teammate-avoidance", 200,
                    retainBrieflyOrClear(now, safeObservation.nearbyTeammates()));
        }

        clearSince = 0;
        if (committedKey == null) {
            commit(proposedKey, now);
        } else if (committedKey.equals(proposedKey)) {
            pendingOppositeKey = null;
            pendingOppositeSince = 0;
        } else if (now >= commitmentUntil) {
            if (!proposedKey.equals(pendingOppositeKey)) {
                pendingOppositeKey = proposedKey;
                pendingOppositeSince = now;
            } else if (now - pendingOppositeSince >= OPPOSITE_CONFIRM_MS) {
                commit(proposedKey, now);
            }
        }

        return MovementIntent.steering("teammate-avoidance", 200, List.of(committedKey));
    }

    /**
     * Selects a stable side for the stronger emergency unstuck action.
     * Repeated detections keep the same side; only a prolonged failure may flip it.
     */
    public int chooseUnstuckSide(long now, String ownerId) {
        if (unstuckSide == 0) {
            unstuckSide = stableSide(ownerId);
        } else if (lastUnstuckChoiceTime > 0 && now - lastUnstuckChoiceTime >= UNSTUCK_SIDE_HOLD_MS) {
            unstuckSide = -unstuckSide;
        }
        lastUnstuckChoiceTime = now;
        return unstuckSide;
    }

    public void reset() {
        committedKey = null;
        commitmentUntil = 0;
        pendingOppositeKey = null;
        pendingOppositeSince = 0;
        clearSince = 0;
        unstuckSide = 0;
        lastUnstuckChoiceTime = 0;
    }

    private String choosePerpendicularKey(String ownerId, List<String> baseKeys, Observation observation) {
        if (!observation.shouldYield() || observation.nearbyTeammates() <= 0
                || baseKeys == null || baseKeys.isEmpty()) {
            return null;
        }

        boolean vertical = baseKeys.contains("W") || baseKeys.contains("S");
        boolean horizontal = baseKeys.contains("A") || baseKeys.contains("D");
        if (vertical == horizontal) {
            // No free axis when movement is diagonal, and no route direction when idle.
            return null;
        }

        if (vertical) {
            return horizontalKey(observation.repulsionX(), ownerId, observation.closeContact());
        }
        return verticalKey(observation.repulsionY(), ownerId, observation.closeContact());
    }

    private String horizontalKey(double force, String ownerId, boolean allowStableFallback) {
        if (force < -FORCE_THRESHOLD) {
            return "A";
        }
        if (force > FORCE_THRESHOLD) {
            return "D";
        }
        return allowStableFallback ? (stableSide(ownerId) < 0 ? "A" : "D") : null;
    }

    private String verticalKey(double force, String ownerId, boolean allowStableFallback) {
        if (force < -FORCE_THRESHOLD) {
            return "W";
        }
        if (force > FORCE_THRESHOLD) {
            return "S";
        }
        return allowStableFallback ? (stableSide(ownerId) < 0 ? "W" : "S") : null;
    }

    private List<String> retainBrieflyOrClear(long now, int nearbyTeammates) {
        pendingOppositeKey = null;
        pendingOppositeSince = 0;
        if (committedKey == null) {
            return List.of();
        }
        if (nearbyTeammates > 0) {
            // This bot currently has right of way; it must follow the route, not sidestep.
            clearCommitment();
            return List.of();
        }
        if (clearSince == 0) {
            clearSince = now;
        }
        if (now - clearSince < CLEAR_GRACE_MS) {
            return List.of(committedKey);
        }
        clearCommitment();
        return List.of();
    }

    private void commit(String key, long now) {
        committedKey = key;
        commitmentUntil = now + STEERING_HOLD_MS;
        pendingOppositeKey = null;
        pendingOppositeSince = 0;
    }

    private void clearCommitment() {
        committedKey = null;
        commitmentUntil = 0;
        clearSince = 0;
    }

    private static int stableSide(String ownerId) {
        return ownerId != null && (ownerId.hashCode() & 1) == 0 ? -1 : 1;
    }

    /** Immutable perception result supplied by the game-specific controller. */
    public record Observation(
            double repulsionX,
            double repulsionY,
            int nearbyTeammates,
            boolean shouldYield,
            boolean closeContact) {
        public Observation {
            nearbyTeammates = Math.max(0, nearbyTeammates);
        }

        public static Observation clear() {
            return new Observation(0.0, 0.0, 0, false, false);
        }
    }
}
