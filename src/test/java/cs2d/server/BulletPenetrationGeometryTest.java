package cs2d.server;

import org.junit.jupiter.api.Test;

import java.awt.geom.Line2D;
import java.awt.geom.Path2D;
import java.awt.geom.Point2D;
import java.awt.geom.Rectangle2D;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

class BulletPenetrationGeometryTest {
    private static final double EPSILON = 1.0e-6;

    @Test
    void vertexCrossingDoesNotCollapsePolygonThicknessToZero() {
        Path2D.Double square = polygon(
                new double[] { 0, 10, 10, 0 },
                new double[] { 0, 0, 10, 10 });

        Point2D.Double[] interval = GameState.getLineShapeIntersections(
                new Line2D.Double(-5, -5, 15, 15), square);

        assertInterval(interval, 0, 0, 10, 10);
    }

    @Test
    void rayStartingInsideUsesStartAsEntryAndNearestBoundaryAsExit() {
        Rectangle2D.Double wall = new Rectangle2D.Double(0, 0, 10, 10);

        Point2D.Double[] interval = GameState.getLineShapeIntersections(
                new Line2D.Double(5, 5, 15, 5), wall);

        assertInterval(interval, 5, 5, 10, 5);
    }

    @Test
    void concavePolygonReturnsOnlyFirstFilledInterval() {
        Path2D.Double concaveWall = polygon(
                new double[] { 0, 10, 10, 7, 7, 3, 3, 0 },
                new double[] { 0, 0, 10, 10, 3, 3, 10, 10 });

        Point2D.Double[] first = GameState.getLineShapeIntersections(
                new Line2D.Double(-5, 5, 15, 5), concaveWall);
        Point2D.Double[] fromInside = GameState.getLineShapeIntersections(
                new Line2D.Double(1, 5, 15, 5), concaveWall);
        Point2D.Double[] fromCavity = GameState.getLineShapeIntersections(
                new Line2D.Double(4, 5, 15, 5), concaveWall);

        assertInterval(first, 0, 5, 3, 5);
        assertInterval(fromInside, 1, 5, 3, 5);
        assertInterval(fromCavity, 7, 5, 10, 5);
    }

    @Test
    void tangentVertexDoesNotCreateZeroThicknessWallHit() {
        Path2D.Double diamond = polygon(
                new double[] { 0, 5, 10, 5 },
                new double[] { 5, 0, 5, 10 });

        Point2D.Double[] interval = GameState.getLineShapeIntersections(
                new Line2D.Double(-5, 0, 15, 0), diamond);

        assertNull(interval);
    }

    @Test
    void boundaryStartOnlyHitsWhenRayEntersFilledArea() {
        Rectangle2D.Double wall = new Rectangle2D.Double(0, 0, 10, 10);

        Point2D.Double[] entering = GameState.getLineShapeIntersections(
                new Line2D.Double(0, 5, 15, 5), wall);
        Point2D.Double[] leaving = GameState.getLineShapeIntersections(
                new Line2D.Double(0, 5, -5, 5), wall);

        assertInterval(entering, 0, 5, 10, 5);
        assertNull(leaving);
    }

    @Test
    void missStillReturnsNull() {
        Rectangle2D.Double wall = new Rectangle2D.Double(0, 0, 10, 10);
        assertNull(GameState.getLineShapeIntersections(new Line2D.Double(-5, -2, 15, -2), wall));
    }

    private static Path2D.Double polygon(double[] xs, double[] ys) {
        assertEquals(xs.length, ys.length);
        Path2D.Double polygon = new Path2D.Double();
        polygon.moveTo(xs[0], ys[0]);
        for (int i = 1; i < xs.length; i++)
            polygon.lineTo(xs[i], ys[i]);
        polygon.closePath();
        return polygon;
    }

    private static void assertInterval(Point2D.Double[] interval,
            double entryX, double entryY, double exitX, double exitY) {
        assertNotNull(interval);
        assertEquals(2, interval.length);
        assertEquals(entryX, interval[0].x, EPSILON);
        assertEquals(entryY, interval[0].y, EPSILON);
        assertEquals(exitX, interval[1].x, EPSILON);
        assertEquals(exitY, interval[1].y, EPSILON);
    }
}
