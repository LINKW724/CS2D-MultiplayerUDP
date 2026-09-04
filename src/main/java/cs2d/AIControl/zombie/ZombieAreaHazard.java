package cs2d.AIControl.zombie;

import cs2d.AIControl.zombie.ZombieTacticalSnapshot.Vec;

/** Immutable damaging area shared by the commander and immediate movement executor. */
public record ZombieAreaHazard(Vec center, double radius, long expiresAt, Type type) {
    public enum Type { ACTIVE_FIRE, PENDING_EXPLOSION }

    public boolean active(long now) {
        return center != null && radius > 0 && expiresAt > now;
    }

    public boolean contains(Vec point, double margin) {
        return point != null && center.distance(point) < radius + Math.max(0, margin);
    }
}
