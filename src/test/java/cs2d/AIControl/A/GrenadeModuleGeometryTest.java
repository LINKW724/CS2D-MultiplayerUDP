package cs2d.AIControl.A;

import org.junit.jupiter.api.Test;

import java.awt.geom.Ellipse2D;
import java.awt.geom.Path2D;
import java.awt.geom.Rectangle2D;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

class GrenadeModuleGeometryTest {
    @Test
    void rectangleEdgesAreFlattenedOnceWithoutChangingGeometry() {
        double[] edges = GrenadeModule.flattenCollisionEdges(new Rectangle2D.Double(10, 20, 30, 40));
        assertArrayEquals(new double[] {
                10, 20, 40, 20,
                40, 20, 40, 60,
                40, 60, 10, 60,
                10, 60, 10, 20
        }, edges);
    }

    @Test
    void pathAndEllipseKeepTheirOriginalSegmentResolution() {
        Path2D.Double triangle = new Path2D.Double();
        triangle.moveTo(0, 0);
        triangle.lineTo(20, 0);
        triangle.lineTo(10, 10);
        triangle.closePath();
        assertEquals(12, GrenadeModule.flattenCollisionEdges(triangle).length);
        assertEquals(16 * 4,
                GrenadeModule.flattenCollisionEdges(new Ellipse2D.Double(0, 0, 30, 20)).length);
    }
}
