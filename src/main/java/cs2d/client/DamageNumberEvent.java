package cs2d.client;

import java.util.Objects;

/** Immutable, server-authoritative input for one combat feedback number. */
public record DamageNumberEvent(
        String attackerId,
        String targetId,
        int damage,
        DamageNumberType type,
        boolean teammateDamage,
        double worldX,
        double worldY,
        long serverTimestamp) {

    public DamageNumberEvent {
        attackerId = Objects.requireNonNullElse(attackerId, "");
        targetId = Objects.requireNonNullElse(targetId, "");
        type = Objects.requireNonNull(type, "type");
        if (damage <= 0) {
            throw new IllegalArgumentException("damage must be positive");
        }
        if (!Double.isFinite(worldX) || !Double.isFinite(worldY)) {
            throw new IllegalArgumentException("damage-number position must be finite");
        }
    }
}
