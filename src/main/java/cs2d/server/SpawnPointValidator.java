package cs2d.server;

import java.awt.Point;
import java.awt.Shape;
import java.awt.geom.Area;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Point2D;
import java.awt.geom.Rectangle2D;
import java.util.List;
import java.util.Set;

/** Single authority for static spawn safety: bounds, forbidden cells and geometry. */
final class SpawnPointValidator {
    private final int mapWidth;
    private final int mapHeight;
    private final double radius;
    private final int forbiddenCellSize;
    private final Set<Point> forbiddenCells;
    private final List<Shape> obstacles;

    SpawnPointValidator(int mapWidth, int mapHeight, double radius, int forbiddenCellSize,
            Set<Point> forbiddenCells, List<Shape> obstacles) {
        this.mapWidth = mapWidth;
        this.mapHeight = mapHeight;
        this.radius = radius;
        this.forbiddenCellSize = forbiddenCellSize;
        this.forbiddenCells = Set.copyOf(forbiddenCells);
        this.obstacles = List.copyOf(obstacles);
    }

    boolean isValid(Point2D.Double point) {
        if (point == null || !Double.isFinite(point.x) || !Double.isFinite(point.y)
                || point.x - radius < 0 || point.y - radius < 0
                || point.x + radius > mapWidth || point.y + radius > mapHeight) {
            return false;
        }

        Ellipse2D.Double footprint = new Ellipse2D.Double(
                point.x - radius, point.y - radius, radius * 2.0, radius * 2.0);
        if (intersectsForbiddenCell(footprint)) {
            return false;
        }

        Area footprintArea = new Area(footprint);
        for (Shape obstacle : obstacles) {
            if (obstacle == null || !obstacle.getBounds2D().intersects(footprint.getBounds2D())) {
                continue;
            }
            Area intersection = new Area(obstacle);
            intersection.intersect(footprintArea);
            if (!intersection.isEmpty()) {
                return false;
            }
        }
        return true;
    }

    boolean isForbidden(Point2D.Double point) {
        if (point == null || !Double.isFinite(point.x) || !Double.isFinite(point.y)) return true;
        return intersectsForbiddenCell(new Ellipse2D.Double(
                point.x - radius, point.y - radius, radius * 2.0, radius * 2.0));
    }

    Point2D.Double findAnyValidPoint(int step) {
        int safeStep = Math.max(1, step);
        int start = Math.max(1, (int) Math.ceil(radius));
        for (int y = start; y <= mapHeight - start; y += safeStep) {
            for (int x = start; x <= mapWidth - start; x += safeStep) {
                Point2D.Double candidate = new Point2D.Double(x, y);
                if (isValid(candidate)) {
                    return candidate;
                }
            }
        }
        return null;
    }

    private boolean intersectsForbiddenCell(Ellipse2D.Double footprint) {
        if (forbiddenCells.isEmpty()) {
            return false;
        }
        Rectangle2D bounds = footprint.getBounds2D();
        int minX = Math.max(0, (int) Math.floor(bounds.getMinX() / forbiddenCellSize));
        int maxX = (int) Math.floor(Math.nextDown(bounds.getMaxX()) / forbiddenCellSize);
        int minY = Math.max(0, (int) Math.floor(bounds.getMinY() / forbiddenCellSize));
        int maxY = (int) Math.floor(Math.nextDown(bounds.getMaxY()) / forbiddenCellSize);
        for (int gridX = minX; gridX <= maxX; gridX++) {
            for (int gridY = minY; gridY <= maxY; gridY++) {
                if (forbiddenCells.contains(new Point(gridX, gridY))
                        && footprint.intersects(gridX * forbiddenCellSize, gridY * forbiddenCellSize,
                                forbiddenCellSize, forbiddenCellSize)) {
                    return true;
                }
            }
        }
        return false;
    }
}
