package cs2d.AIControl.zombie;

import cs2d.AIControl.zombie.ZombieTacticalSnapshot.Vec;
import java.util.List;
import java.util.function.Predicate;

/** Selects an upstream, walkable and hazard-free choke point from a predicted route. */
public final class ZombieDefenseLinePlanner {
    public DefensePoint choose(List<Vec> route, List<ZombieAreaHazard> hazards, long now,
            Predicate<Vec> walkable) {
        if (route == null || route.size() < 2) return null;
        int from = Math.max(1, (int) Math.floor(route.size() * 0.35));
        int to = Math.min(route.size() - 1, (int) Math.ceil(route.size() * 0.82));
        Vec best = null;
        Vec watch = null;
        double bestScore = Double.NEGATIVE_INFINITY;
        for (int i = from; i <= to; i++) {
            Vec candidate = route.get(i);
            if (!walkable.test(candidate) || hazardous(candidate, hazards, now)) continue;
            Vec previous = route.get(Math.max(0, i - 1));
            Vec next = route.get(Math.min(route.size() - 1, i + 1));
            double dx = next.x() - previous.x();
            double dy = next.y() - previous.y();
            double length = Math.max(0.001, Math.hypot(dx, dy));
            double px = -dy / length;
            double py = dx / length;
            int blockedSides = 0;
            for (double offset : new double[] { -96, -48, 48, 96 }) {
                Vec probe = new Vec(candidate.x() + px * offset, candidate.y() + py * offset);
                if (!walkable.test(probe)) blockedSides++;
            }
            double progress = i / (double) (route.size() - 1);
            double score = blockedSides * 120.0 - Math.abs(progress - 0.62) * 80.0;
            if (score > bestScore) {
                bestScore = score;
                best = candidate;
                watch = route.get(Math.max(0, i - 2));
            }
        }
        return best == null ? null : new DefensePoint(best, watch);
    }

    private static boolean hazardous(Vec point, List<ZombieAreaHazard> hazards, long now) {
        return hazards.stream().anyMatch(hazard -> hazard != null && hazard.active(now)
                && hazard.contains(point, 40));
    }

    public record DefensePoint(Vec position, Vec watchPoint) {}
}
