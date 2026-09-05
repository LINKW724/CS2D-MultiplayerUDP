package cs2d.server;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.awt.Point;
import java.awt.Shape;
import java.awt.geom.Point2D;
import java.awt.geom.Rectangle2D;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

class SpawnPointValidatorTest {
    @Test
    void validatesTheWholePlayerFootprintAgainstBoundsForbiddenCellsAndWalls() {
        SpawnPointValidator validator = new SpawnPointValidator(
                200, 160, 10, 20, Set.of(new Point(4, 3)),
                List.<Shape>of(new Rectangle2D.Double(120, 40, 30, 50)));

        assertFalse(validator.isValid(new Point2D.Double(5, 80)));
        assertFalse(validator.isValid(new Point2D.Double(75, 70)));
        assertTrue(validator.isForbidden(new Point2D.Double(75, 70)));
        assertFalse(validator.isForbidden(new Point2D.Double(40, 40)));
        assertFalse(validator.isValid(new Point2D.Double(115, 60)));
        assertTrue(validator.isValid(new Point2D.Double(40, 40)));
    }

    @Test
    void emergencySearchNeverReturnsAnUnvalidatedCoordinate() {
        SpawnPointValidator validator = new SpawnPointValidator(
                100, 100, 10, 20, Set.of(new Point(0, 0)), List.of());

        Point2D.Double fallback = validator.findAnyValidPoint(10);
        assertNotNull(fallback);
        assertTrue(validator.isValid(fallback));
    }
}
