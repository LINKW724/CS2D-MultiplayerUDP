package cs2d.AIControl.movement;

import java.awt.geom.Point2D;
import java.util.Optional;
import java.util.function.Predicate;

/** Supplies strategic patrol objectives without owning path execution or keys. */
public interface PatrolTargetProvider {
    Optional<Point2D.Double> nextTarget(PatrolContext context);

    record PatrolContext(String agentId, Point2D.Double origin, int mapWidth, int mapHeight,
            Predicate<Point2D.Double> walkable) {
    }
}
