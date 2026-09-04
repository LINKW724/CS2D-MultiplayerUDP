package cs2d.AIControl.zombie;

import cs2d.AIControl.zombie.ZombieTacticalSnapshot.Vec;
import java.awt.geom.Line2D;
import java.util.*;

/** Stateful LMG target director: maximize useful rays without losing close-threat discipline. */
public final class ZombieCrowdFirePlanner {
    private static final double EMERGENCY_RANGE = 170;
    private static final double RAY_WIDTH = 20;
    private static final long SWEEP_INTERVAL_MS = 550;
    private String lockedTargetId;
    private String engagementId;
    private long engagementGeneration;
    private long nextSweepAt;
    private Set<String> previousVisibleIds = Set.of();

    public Decision select(Vec origin, List<Target> visibleTargets, long now) {
        List<Target> targets = visibleTargets == null ? List.of() : visibleTargets.stream()
                .filter(Objects::nonNull).filter(t -> t.position() != null)
                .sorted(Comparator.comparing(Target::id)).toList();
        if (origin == null || targets.isEmpty()) {
            clearEngagement();
            return Decision.NONE;
        }
        updateEngagement(targets);
        Target nearest = targets.stream().min(Comparator
                .comparingDouble((Target t) -> origin.distance(t.position()))
                .thenComparing(Target::id)).orElseThrow();
        if (origin.distance(nearest.position()) < EMERGENCY_RANGE)
            return lock(nearest, targets.size(), rayHits(origin, nearest, targets), now);

        List<ScoredTarget> ranked = targets.stream().map(target -> new ScoredTarget(target,
                rayHits(origin, target, targets), targetScore(origin, target, targets)))
                .sorted(Comparator.comparingDouble(ScoredTarget::score).reversed()
                        .thenComparingDouble(s -> origin.distance(s.target().position()))
                        .thenComparing(s -> s.target().id())).toList();
        ScoredTarget best = ranked.get(0);
        ScoredTarget locked = ranked.stream().filter(s -> s.target().id().equals(lockedTargetId))
                .findFirst().orElse(null);

        // Keep a profitable penetration ray stable. Otherwise traverse the visible fan at a bounded rate.
        if (locked != null && (now < nextSweepAt || best.rayHits() >= 2
                && locked.score() >= best.score() - 220))
            return lock(locked.target(), targets.size(), locked.rayHits(), now, false);
        if (targets.size() >= 3 && best.rayHits() == 1 && locked != null) {
            List<Target> fan = targets.stream().sorted(Comparator
                    .comparingDouble(t -> Math.atan2(t.position().y() - origin.y(),
                            t.position().x() - origin.x()))).toList();
            int index = Math.max(0, java.util.stream.IntStream.range(0, fan.size())
                    .filter(i -> fan.get(i).id().equals(lockedTargetId)).findFirst().orElse(0));
            Target swept = fan.get((index + 1) % fan.size());
            return lock(swept, targets.size(), rayHits(origin, swept, targets), now);
        }
        return lock(best.target(), targets.size(), best.rayHits(), now);
    }

    public void reset() {
        clearEngagement();
    }

    private void updateEngagement(List<Target> targets) {
        Set<String> current = targets.stream().map(Target::id)
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        if (engagementId == null || Collections.disjoint(previousVisibleIds, current))
            engagementId = "zombie-crowd-" + (++engagementGeneration);
        previousVisibleIds = current;
    }

    private Decision lock(Target target, int crowdSize, int rayHits, long now) {
        return lock(target, crowdSize, rayHits, now, true);
    }

    private Decision lock(Target target, int crowdSize, int rayHits, long now, boolean renewed) {
        lockedTargetId = target.id();
        if (renewed) nextSweepAt = now + SWEEP_INTERVAL_MS;
        return new Decision(target.id(), engagementId, crowdSize, rayHits);
    }

    private static double targetScore(Vec origin, Target candidate, List<Target> targets) {
        int hits = rayHits(origin, candidate, targets);
        double distance = origin.distance(candidate.position());
        double healthValue = Math.min(1_500, Math.max(0, candidate.health())) * 0.03;
        return hits * 1_000.0 + healthValue - distance * 0.15;
    }

    private static int rayHits(Vec origin, Target candidate, List<Target> targets) {
        double dx = candidate.position().x() - origin.x();
        double dy = candidate.position().y() - origin.y();
        double length = Math.hypot(dx, dy);
        if (length < 0.001) return 1;
        double endX = origin.x() + dx / length * 8_000;
        double endY = origin.y() + dy / length * 8_000;
        return (int) targets.stream().filter(target -> {
            double projection = (target.position().x() - origin.x()) * dx
                    + (target.position().y() - origin.y()) * dy;
            return projection > 0 && Line2D.ptSegDist(origin.x(), origin.y(), endX, endY,
                    target.position().x(), target.position().y()) <= RAY_WIDTH;
        }).count();
    }

    private void clearEngagement() {
        lockedTargetId = null;
        engagementId = null;
        nextSweepAt = 0;
        previousVisibleIds = Set.of();
    }

    public record Target(String id, Vec position, int health) {}
    public record Decision(String targetId, String engagementId, int crowdSize, int rayHits) {
        public static final Decision NONE = new Decision(null, null, 0, 0);
    }
    private record ScoredTarget(Target target, int rayHits, double score) {}
}
