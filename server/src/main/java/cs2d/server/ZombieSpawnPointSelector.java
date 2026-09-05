package cs2d.server;

import java.awt.geom.Point2D;
import java.util.*;

/** Dynamic selection over statically validated spawn points. */
final class ZombieSpawnPointSelector {
    Optional<Point2D.Double> choose(List<Point2D.Double> pool, List<Point2D.Double> humans,
            List<Point2D.Double> occupied, Point2D.Double avoided, Random random,
            double humanDistance, double occupiedDistance, double avoidedDistance) {
        if (pool == null || pool.isEmpty()) return Optional.empty();
        double humanSq = humanDistance * humanDistance;
        double occupiedSq = occupiedDistance * occupiedDistance;
        double avoidedSq = avoidedDistance * avoidedDistance;
        List<Point2D.Double> unoccupied = pool.stream()
                .filter(point -> occupied.stream().noneMatch(position -> point.distanceSq(position) < occupiedSq))
                .toList();
        List<Point2D.Double> strict = unoccupied.stream()
                .filter(point -> humans.stream().noneMatch(position -> point.distanceSq(position) < humanSq))
                .filter(point -> avoided == null || point.distanceSq(avoided) >= avoidedSq)
                .toList();
        if (!strict.isEmpty()) return Optional.of(strict.get(random.nextInt(strict.size())));
        return unoccupied.stream().max(Comparator.comparingDouble(point -> clearance(point, occupied, avoided)));
    }

    private static double clearance(Point2D.Double point, List<Point2D.Double> occupied,
            Point2D.Double avoided) {
        double result = occupied.stream().mapToDouble(point::distanceSq).min().orElse(Double.MAX_VALUE);
        if (avoided != null) result = Math.min(result, point.distanceSq(avoided));
        return result;
    }
}
