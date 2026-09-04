package cs2d.client;

import java.util.Objects;

/** Immutable, server-authoritative input for one outgoing damage-number effect. */
public record DamageNumberEvent(
        String attackerId,
        String targetId,
        int damage,
        boolean headshot,
        boolean teammateDamage,
        double worldX,
        double worldY,
        long serverTimestamp) {

    public DamageNumberEvent {
        attackerId = Objects.requireNonNullElse(attackerId, "");
        targetId = Objects.requireNonNullElse(targetId, "");
        if (damage <= 0) {
            throw new IllegalArgumentException("damage must be positive");
        }
        if (!Double.isFinite(worldX) || !Double.isFinite(worldY)) {
            throw new IllegalArgumentException("damage-number position must be finite");
        }
    }
}
