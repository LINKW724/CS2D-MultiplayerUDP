package cs2d.AIControl.zombie;

import cs2d.AIControl.zombie.ZombieTacticalSnapshot.Vec;
import java.awt.geom.Line2D;
import java.util.ArrayDeque;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.BiPredicate;
import java.util.function.Predicate;

/** Bounded local flood search that finds an actual exit from corners and narrow pockets. */
public final class ZombieEscapePlanner {
    private static final double STEP = 32;
    private static final int MAX_DEPTH = 8;
    private static final int[][] DIRECTIONS = {
            { 1, 0 }, { 1, 1 }, { 0, 1 }, { -1, 1 },
            { -1, 0 }, { -1, -1 }, { 0, -1 }, { 1, -1 }
    };

    public Vec choose(Vec origin, List<Vec> threats, List<Vec> teammates,
            List<ZombieAreaHazard> hazards, long now, Predicate<Vec> walkable,
            BiPredicate<Vec, Vec> traversable) {
        if (origin == null || threats == null || threats.isEmpty()) return origin;
        Vec best = origin;
        double bestScore = score(origin, origin, threats, teammates, hazards, now);
        ArrayDeque<Node> open = new ArrayDeque<>();
        Set<Cell> visited = new HashSet<>();
        open.add(new Node(origin, 0));
        visited.add(new Cell(0, 0));
        while (!open.isEmpty()) {
            Node node = open.removeFirst();
            if (node.depth() >= MAX_DEPTH) continue;
            for (int[] direction : DIRECTIONS) {
                int dx = cellX(node.point(), origin) + direction[0];
                int dy = cellY(node.point(), origin) + direction[1];
                Cell cell = new Cell(dx, dy);
                if (!visited.add(cell)) continue;
                Vec candidate = new Vec(origin.x() + dx * STEP, origin.y() + dy * STEP);
                if (!walkable.test(candidate) || !traversable.test(node.point(), candidate)
                        || !escapesThreats(node.point(), candidate, threats)) continue;
                int depth = node.depth() + 1;
                open.addLast(new Node(candidate, depth));
                double score = score(origin, candidate, threats, teammates, hazards, now)
                        - depth * 0.75;
                if (score > bestScore) {
                    best = candidate;
                    bestScore = score;
                }
            }
        }
        return best;
    }

    private double score(Vec origin, Vec candidate, List<Vec> threats,
            List<Vec> teammates, List<ZombieAreaHazard> hazards, long now) {
        if (hazards != null && hazards.stream().filter(h -> h != null && h.active(now))
                .anyMatch(h -> h.contains(candidate, 20))) return Double.NEGATIVE_INFINITY;
        double clearance = threats.stream().mapToDouble(candidate::distance).min().orElse(0);
        double allyClearance = teammates == null ? 120 : teammates.stream()
                .mapToDouble(candidate::distance).min().orElse(120);
        if (allyClearance < 36) return Double.NEGATIVE_INFINITY;
        return Math.min(520, clearance) * 4.0 + Math.min(120, allyClearance) * 0.35
                + Math.min(256, origin.distance(candidate)) * 0.12;
    }

    private boolean escapesThreats(Vec from, Vec to, List<Vec> threats) {
        for (Vec threat : threats) {
            double start = from.distance(threat);
            double end = to.distance(threat);
            if (start < 30) {
                if (end <= start + 2) return false;
            } else if (Line2D.ptSegDist(from.x(), from.y(), to.x(), to.y(),
                    threat.x(), threat.y()) < 30) {
                return false;
            }
        }
        return true;
    }

    private static int cellX(Vec point, Vec origin) {
        return (int) Math.round((point.x() - origin.x()) / STEP);
    }

    private static int cellY(Vec point, Vec origin) {
        return (int) Math.round((point.y() - origin.y()) / STEP);
    }

    private record Node(Vec point, int depth) {}
    private record Cell(int x, int y) {}
}
