package cs2d.AIControl.movement;

import java.awt.geom.Point2D;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * Deterministic, local cover evaluator.
 *
 * <p>This class knows nothing about players, game modes or controller states.
 * It consumes a small geometry boundary and returns a tactical destination.
 * The caller remains responsible for deciding when cover is appropriate and
 * the pathfinder remains responsible for reaching it.</p>
 */
public final class LocalCoverPlanner {
    private static final int DIRECTION_SAMPLES = 12;
    private static final double[] SEARCH_RADII = { 80.0, 140.0, 210.0 };
    private static final double MIN_TEAMMATE_CLEARANCE_SQ = 42.0 * 42.0;
    private static final double TEAMMATE_COMFORT_SQ = 96.0 * 96.0;

    public Optional<Point2D.Double> findBestCover(Point2D.Double origin, Point2D.Double threat,
            Collection<Point2D.Double> teammates, GeometryProbe geometry) {
        if (origin == null || threat == null || geometry == null) {
            return Optional.empty();
        }

        List<Point2D.Double> safeTeammates = teammates == null ? List.of() : teammates.stream()
                .filter(point -> point != null)
                .toList();
        double awayAngle = Math.atan2(origin.y - threat.y, origin.x - threat.x);
        Point2D.Double best = null;
        double bestScore = Double.NEGATIVE_INFINITY;

        for (double radius : SEARCH_RADII) {
            for (int sample = 0; sample < DIRECTION_SAMPLES; sample++) {
                double angle = awayAngle + sample * (Math.PI * 2.0 / DIRECTION_SAMPLES);
                Point2D.Double candidate = new Point2D.Double(
                        origin.x + Math.cos(angle) * radius,
                        origin.y + Math.sin(angle) * radius);
                Point2D.Double midpoint = new Point2D.Double(
                        (origin.x + candidate.x) * 0.5,
                        (origin.y + candidate.y) * 0.5);

                if (!geometry.isInBounds(candidate) || !geometry.isWalkable(candidate)
                        || !geometry.isWalkable(midpoint)
                        || geometry.hasLineOfSight(threat, candidate)) {
                    continue;
                }

                double teammatePenalty = teammatePenalty(candidate, safeTeammates);
                if (Double.isInfinite(teammatePenalty)) {
                    continue;
                }
                double retreatAlignment = Math.cos(angle - awayAngle);
                double score = 140.0 * retreatAlignment - radius * 0.22 - teammatePenalty;
                if (score > bestScore) {
                    bestScore = score;
                    best = candidate;
                }
            }
        }
        return Optional.ofNullable(best);
    }

    private static double teammatePenalty(Point2D.Double candidate, Collection<Point2D.Double> teammates) {
        double penalty = 0.0;
        for (Point2D.Double teammate : teammates) {
            double distanceSq = candidate.distanceSq(teammate);
            if (distanceSq < MIN_TEAMMATE_CLEARANCE_SQ) {
                return Double.POSITIVE_INFINITY;
            }
            if (distanceSq < TEAMMATE_COMFORT_SQ) {
                penalty += 80.0 * (1.0 - distanceSq / TEAMMATE_COMFORT_SQ);
            }
        }
        return penalty;
    }

    public interface GeometryProbe {
        boolean isInBounds(Point2D.Double point);

        boolean isWalkable(Point2D.Double point);

        boolean hasLineOfSight(Point2D.Double from, Point2D.Double to);
    }
}
