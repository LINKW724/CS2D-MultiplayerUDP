package cs2d.AIControl.movement;

import cs2d.AIControl.A.PathfindingModule;
import cs2d.server.GameState;
import cs2d.server.MapData;
import cs2d.server.QuadtreeNode;

import java.awt.Shape;
import java.awt.geom.Line2D;
import java.awt.geom.Point2D;
import java.awt.geom.Rectangle2D;
import java.util.ArrayList;

/** Server adapter that accelerates cover visibility tests with the map Quadtree. */
public final class QuadtreeCoverGeometryProbe implements LocalCoverPlanner.GeometryProbe {
    private final GameState gameState;
    private final PathfindingModule pathfinding;
    private final ArrayList<MapData.ShapeWrapper> rayCandidates = new ArrayList<>();

    public QuadtreeCoverGeometryProbe(GameState gameState, PathfindingModule pathfinding) {
        this.gameState = gameState;
        this.pathfinding = pathfinding;
    }

    @Override
    public boolean isInBounds(Point2D.Double point) {
        return gameState != null && point != null && gameState.isInBounds(point);
    }

    @Override
    public boolean isWalkable(Point2D.Double point) {
        return pathfinding != null && point != null && pathfinding.isWalkable(point);
    }

    @Override
    public boolean hasLineOfSight(Point2D.Double from, Point2D.Double to) {
        if (from == null || to == null || gameState == null) {
            return false;
        }
        QuadtreeNode root = gameState.getQuadtreeRootNode();
        if (root == null) {
            return pathfinding != null && pathfinding.pathfinder != null
                    && pathfinding.pathfinder.hasLineOfSightToPointFromPoint(from, to);
        }

        rayCandidates.clear();
        root.queryRay(rayCandidates, from, to);
        Line2D.Double ray = new Line2D.Double(from, to);
        Rectangle2D bounds = ray.getBounds2D();
        for (int i = 0; i < rayCandidates.size(); i++) {
            Shape obstacle = gameState.getShapeFromWrapper(rayCandidates.get(i));
            if (obstacle != null && obstacle.intersects(bounds)
                    && GameState.getLineShapeIntersections(ray, obstacle) != null) {
                return false;
            }
        }
        return true;
    }
}
