package cs2d.AIControl.movement;

import org.junit.jupiter.api.Test;

import java.awt.geom.Point2D;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertTrue;

class LocalCoverPlannerTest {
    private final LocalCoverPlanner planner = new LocalCoverPlanner();

    @Test
    void selectsHiddenWalkablePointAwayFromThreat() {
        Point2D.Double origin = new Point2D.Double(100, 100);
        Point2D.Double threat = new Point2D.Double(220, 100);

        Point2D.Double cover = planner.findBestCover(origin, threat, List.of(), geometry(false))
                .orElseThrow();

        assertTrue(cover.x < origin.x);
        assertTrue(!geometry(false).hasLineOfSight(threat, cover));
    }

    @Test
    void neverReturnsAnExposedCandidate() {
        assertTrue(planner.findBestCover(new Point2D.Double(100, 100),
                new Point2D.Double(220, 100), List.of(), geometry(true)).isEmpty());
    }

    @Test
    void avoidsCoverSlotAlreadyOccupiedByTeammate() {
        Point2D.Double origin = new Point2D.Double(100, 100);
        Point2D.Double threat = new Point2D.Double(220, 100);
        Point2D.Double occupied = new Point2D.Double(28, 100);

        Point2D.Double cover = planner.findBestCover(origin, threat, List.of(occupied), geometry(false))
                .orElseThrow();

        assertTrue(cover.distanceSq(occupied) >= 42.0 * 42.0);
    }

    private static LocalCoverPlanner.GeometryProbe geometry(boolean everythingExposed) {
        return new LocalCoverPlanner.GeometryProbe() {
            @Override
            public boolean isInBounds(Point2D.Double point) {
                return point.x >= 0 && point.y >= 0 && point.x <= 500 && point.y <= 500;
            }

            @Override
            public boolean isWalkable(Point2D.Double point) {
                return true;
            }

            @Override
            public boolean hasLineOfSight(Point2D.Double from, Point2D.Double to) {
                return everythingExposed || to.x >= 80;
            }
        };
    }
}
