package cs2d.AIControl.zombie;

import cs2d.AIControl.zombie.ZombieTacticalSnapshot.Vec;
import java.util.Comparator;
import java.util.List;
import java.util.function.Predicate;

/** Local zombie locomotion: stable surround slots plus deterministic congestion escape. */
public final class ZombiePursuitPlanner {
    private static final double ENGAGEMENT_RADIUS = 30;
    private static final double RING_SPACING = 28;
    private static final int SLOTS_PER_RING = 6;
    private static final double CROWD_RADIUS = 52;
    private static final double DETOUR_DISTANCE = 88;

    public Vec engagementPoint(String id, Vec target, List<Neighbor> pursuers, Predicate<Vec> walkable) {
        List<Neighbor> ordered = pursuers.stream().sorted(Comparator.comparing(Neighbor::id)).toList();
        int rank = 0;
        for (int i = 0; i < ordered.size(); i++) if (ordered.get(i).id().equals(id)) rank = i;
        int ring = rank / SLOTS_PER_RING;
        int slot = rank % SLOTS_PER_RING;
        int remainingOnRing = Math.max(1, ordered.size() - ring * SLOTS_PER_RING);
        int slotsOnRing = Math.min(SLOTS_PER_RING, remainingOnRing);
        double radius = ENGAGEMENT_RADIUS + ring * RING_SPACING;
        double base = slot * Math.PI * 2.0 / slotsOnRing + ring * Math.PI / SLOTS_PER_RING;
        for (int offset = 0; offset < SLOTS_PER_RING; offset++) {
            double angle = base + offset * Math.PI * 2.0 / SLOTS_PER_RING;
            Vec candidate = new Vec(target.x() + Math.cos(angle) * radius,
                    target.y() + Math.sin(angle) * radius);
            if (walkable.test(candidate)) return candidate;
        }
        return target;
    }

    public Vec detour(String id, Vec origin, Vec target, List<Neighbor> pursuers,
            Predicate<Vec> walkable) {
        double x = unitX(origin, target) * 0.35;
        double y = unitY(origin, target) * 0.35;
        boolean repelled = false;
        for (Neighbor other : pursuers) {
            if (other.id().equals(id)) continue;
            double distance = origin.distance(other.position());
            if (distance >= CROWD_RADIUS) continue;
            repelled = true;
            double weight = (CROWD_RADIUS - distance) / CROWD_RADIUS;
            if (distance < 0.001) {
                double angle = pairAngle(id, other.id());
                x += Math.cos(angle) * weight * 2.5;
                y += Math.sin(angle) * weight * 2.5;
            } else {
                x += (origin.x() - other.position().x()) / distance * weight * 2.5;
                y += (origin.y() - other.position().y()) / distance * weight * 2.5;
            }
        }
        if (!repelled) {
            double side = (id.hashCode() & 1) == 0 ? 1 : -1;
            double forwardX = unitX(origin, target);
            double forwardY = unitY(origin, target);
            x += -forwardY * side * 1.2;
            y += forwardX * side * 1.2;
        }
        double angle = Math.atan2(y, x);
        for (int i = 0; i < 8; i++) {
            double candidateAngle = angle + (i % 2 == 0 ? 1 : -1) * ((i + 1) / 2) * Math.PI / 4;
            Vec candidate = new Vec(origin.x() + Math.cos(candidateAngle) * DETOUR_DISTANCE,
                    origin.y() + Math.sin(candidateAngle) * DETOUR_DISTANCE);
            if (walkable.test(candidate)) return candidate;
        }
        return engagementPoint(id, target, pursuers, walkable);
    }

    private static double unitX(Vec from, Vec to) {
        double distance = Math.max(0.001, from.distance(to));
        return (to.x() - from.x()) / distance;
    }

    private static double unitY(Vec from, Vec to) {
        double distance = Math.max(0.001, from.distance(to));
        return (to.y() - from.y()) / distance;
    }

    private static double pairAngle(String id, String otherId) {
        String low = id.compareTo(otherId) <= 0 ? id : otherId;
        String high = id.compareTo(otherId) <= 0 ? otherId : id;
        long hash = 31L * low.hashCode() + high.hashCode();
        double angle = (hash & 0xffffL) * (Math.PI * 2.0 / 65_536.0);
        return id.compareTo(otherId) <= 0 ? angle : angle + Math.PI;
    }

    public record Neighbor(String id, Vec position) {}
}
