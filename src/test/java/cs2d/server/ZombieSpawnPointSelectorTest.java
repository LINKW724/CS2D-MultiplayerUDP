package cs2d.server;

import java.awt.geom.Point2D;
import java.util.List;
import java.util.Random;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ZombieSpawnPointSelectorTest {
    private final ZombieSpawnPointSelector selector = new ZombieSpawnPointSelector();

    @Test void neverChoosesAPointOccupiedByAnotherZombie() {
        Point2D.Double occupied = point(100, 100);
        Point2D.Double free = point(300, 100);
        var result = selector.choose(List.of(occupied, free), List.of(), List.of(occupied), null,
                new Random(1), 400, 60, 250);
        assertEquals(free, result.orElseThrow());
    }

    @Test void recycledZombiePrefersAPlaceFarFromItsOldSpawn() {
        Point2D.Double old = point(100, 100);
        Point2D.Double near = point(200, 100);
        Point2D.Double far = point(500, 100);
        var result = selector.choose(List.of(near, far), List.of(), List.of(old), old,
                new Random(1), 400, 60, 250);
        assertEquals(far, result.orElseThrow());
    }

    @Test void relaxedFallbackStillRefusesPhysicalOverlap() {
        Point2D.Double human = point(100, 100);
        Point2D.Double occupied = point(300, 100);
        Point2D.Double fallback = point(370, 100);
        var result = selector.choose(List.of(occupied, fallback), List.of(human), List.of(occupied), null,
                new Random(1), 400, 60, 250);
        assertEquals(fallback, result.orElseThrow());
    }

    private static Point2D.Double point(double x, double y) { return new Point2D.Double(x, y); }
}
