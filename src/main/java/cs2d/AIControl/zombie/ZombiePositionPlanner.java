package cs2d.AIControl.zombie;

import cs2d.AIControl.zombie.ZombieTacticalSnapshot.Vec;
import java.awt.geom.Line2D;
import java.util.List;
import java.util.function.Predicate;

/** Small bounded geometry search. Checks the whole local segment, not just a walkable endpoint. */
public final class ZombiePositionPlanner {
    public Vec choose(Vec origin, Vec desired, List<Vec> threats, List<Vec> teammates,
                      Predicate<Vec> walkable) {
        return choose(origin, desired, threats, teammates, List.of(), 0, walkable, null);
    }

    public Vec choose(Vec origin, Vec desired, List<Vec> threats, List<Vec> teammates,
                      Predicate<Vec> walkable, Vec firingTarget) {
        return choose(origin, desired, threats, teammates, List.of(), 0, walkable, firingTarget);
    }

    public Vec choose(Vec origin, Vec desired, List<Vec> threats, List<Vec> teammates,
                      List<ZombieAreaHazard> hazards, long now, Predicate<Vec> walkable,
                      Vec firingTarget) {
        Vec best = origin;
        double bestScore = score(origin, origin, desired, threats, teammates, hazards, now,
                walkable, firingTarget);
        for (double radius : new double[] { 80, 160, 240 }) {
            for (int i = 0; i < 12; i++) {
                double angle = i * Math.PI / 6;
                Vec candidate = new Vec(origin.x() + Math.cos(angle) * radius,
                        origin.y() + Math.sin(angle) * radius);
                double score = score(origin, candidate, desired, threats, teammates, hazards, now,
                        walkable, firingTarget);
                if (score > bestScore) { best = candidate; bestScore = score; }
            }
        }
        return best;
    }

    public boolean safeSegment(Vec origin, Vec destination, List<Vec> threats, Predicate<Vec> walkable) {
        return safeSegment(origin, destination, threats, List.of(), 0, walkable);
    }

    public boolean safeSegment(Vec origin, Vec destination, List<Vec> threats,
            List<ZombieAreaHazard> hazards, long now, Predicate<Vec> walkable) {
        int steps = Math.max(1, (int) Math.ceil(origin.distance(destination) / 16));
        for (int i = 0; i <= steps; i++) {
            double t = i / (double) steps;
            if (!walkable.test(new Vec(origin.x() + (destination.x() - origin.x()) * t,
                    origin.y() + (destination.y() - origin.y()) * t))) return false;
        }
        for (Vec threat : threats) {
            double clearance = Math.min(110, Math.max(0, origin.distance(threat) - 8));
            if (Line2D.ptSegDist(origin.x(), origin.y(), destination.x(), destination.y(),
                    threat.x(), threat.y()) < clearance) return false;
        }
        for (ZombieAreaHazard hazard : hazards) {
            if (hazard == null || !hazard.active(now)) continue;
            double clearance = hazard.radius() + 24;
            double start = origin.distance(hazard.center());
            double end = destination.distance(hazard.center());
            if (start < clearance) {
                // A unit already in fire must be allowed to move outward, but not to another point in it.
                if (end < clearance || end <= start + 8) return false;
            } else if (Line2D.ptSegDist(origin.x(), origin.y(), destination.x(), destination.y(),
                    hazard.center().x(), hazard.center().y()) < clearance) return false;
        }
        return true;
    }

    private double score(Vec origin, Vec candidate, Vec desired, List<Vec> threats,
                         List<Vec> teammates, List<ZombieAreaHazard> hazards, long now,
                         Predicate<Vec> walkable, Vec firingTarget) {
        if (!safeSegment(origin, candidate, threats, hazards, now, walkable))
            return Double.NEGATIVE_INFINITY;
        double nearest = threats.stream().mapToDouble(t -> t.distance(candidate)).min().orElse(400);
        double separation = teammates.stream().mapToDouble(t -> t.distance(candidate)).min().orElse(120);
        // Separation is bounded; adding ten teammates cannot outweigh survival.
        if (separation < 45) return Double.NEGATIVE_INFINITY;
        boolean blockedShot = firingTarget != null && (teammates.stream().anyMatch(t ->
                candidate.distance(t) < candidate.distance(firingTarget)
                && Line2D.ptSegDist(candidate.x(), candidate.y(), firingTarget.x(), firingTarget.y(),
                        t.x(), t.y()) < 30)
                || !safeSegment(candidate, firingTarget, List.of(), hazards, now, walkable));
        return Math.min(400, nearest) * 2.5 - desired.distance(candidate) * 0.65
                + Math.min(120, separation) * 0.5 - origin.distance(candidate) * 0.08
                - (blockedShot ? 1_000 : 0);
    }
}
