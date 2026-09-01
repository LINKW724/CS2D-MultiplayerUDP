package cs2d.AIControl.movement;

import java.util.List;
import java.util.Locale;

/**
 * Immutable movement proposal produced by one thinking module.
 *
 * <p>The producer describes intent only. It never writes player input directly;
 * {@link MovementArbiter} is the single authority that turns proposals into one
 * executable movement decision.</p>
 */
public record MovementIntent(
        String sourceId,
        int priority,
        CompositionMode mode,
        List<String> keys) {

    public MovementIntent {
        sourceId = sourceId == null || sourceId.isBlank() ? "anonymous" : sourceId;
        mode = mode == null ? CompositionMode.BASE : mode;
        keys = keys == null ? List.of() : keys.stream()
                .filter(key -> key != null)
                .map(key -> key.toUpperCase(Locale.ROOT))
                .filter(MovementIntent::isMovementKey)
                .distinct()
                .toList();
    }

    public static MovementIntent base(String sourceId, int priority, List<String> keys) {
        return new MovementIntent(sourceId, priority, CompositionMode.BASE, keys);
    }

    public static MovementIntent steering(String sourceId, int priority, List<String> keys) {
        return new MovementIntent(sourceId, priority, CompositionMode.STEERING, keys);
    }

    public static MovementIntent exclusive(String sourceId, int priority, List<String> keys) {
        return new MovementIntent(sourceId, priority, CompositionMode.EXCLUSIVE, keys);
    }

    /** Explicitly owns locomotion while intentionally producing no movement. */
    public static MovementIntent stop(String sourceId, int priority) {
        return new MovementIntent(sourceId, priority, CompositionMode.EXCLUSIVE, List.of());
    }

    public boolean hasMovement() {
        return !keys.isEmpty();
    }

    private static boolean isMovementKey(String key) {
        return "W".equals(key) || "A".equals(key) || "S".equals(key) || "D".equals(key);
    }

    public enum CompositionMode {
        /** One strategic locomotion request, such as an A* path. */
        BASE,
        /** A local correction that may add movement but may not reverse BASE. */
        STEERING,
        /** A temporary action that owns locomotion completely. */
        EXCLUSIVE
    }
}
