package cs2d.client;

import javafx.geometry.Point2D;
import javafx.geometry.Rectangle2D;
import java.util.ArrayList;
import java.util.List;

/**
 * 代表 Quadtree（四叉树）中的一个节点，用于对障碍物进行空间划分。
 * 通过将地图划分为更小的区域，可以显著提高碰撞检测和射线查询的效率。
 */
public class QuadtreeNode {

    private final int maxObjects; // 节点在分裂前可以容纳的最大对象数
    private final int maxDepth;   // 树的最大深度
    private final int level;      // 当前节点的深度级别
    private final Rectangle2D bounds; // 此节点代表的矩形区域

    /** 存储在当前节点中的静态障碍物对象 */
    private final List<GameClient.StaticObstacle> objects;

    /** 四个子节点 (如果是叶子节点则为 null) */
    private final QuadtreeNode[] children;

    /** 用于多边形简化的共线检测的极小值 */
    public static final double COLLINEAR_EPSILON = 1E-6;

    /**
     * QuadtreeNode 的构造函数。
     *
     * @param level      当前节点的深度级别
     * @param bounds     此节点代表的矩形区域
     * @param maxObjects 节点在分裂前可以容纳的最大对象数
     * @param maxDepth   树的最大深度
     */
    public QuadtreeNode(int level, Rectangle2D bounds, int maxObjects, int maxDepth) {
        this.level = level;
        this.bounds = bounds;
        this.maxObjects = maxObjects;
        this.maxDepth = maxDepth;
        this.objects = new ArrayList<>();
        this.children = new QuadtreeNode[4];
    }

    /**
     * 清空 Quadtree 节点及其所有子节点。
     */
    public void clear() {
        objects.clear();
        for (int i = 0; i < children.length; i++) {
            if (children[i] != null) {
                children[i].clear();
                children[i] = null;
            }
        }
    }

    /**
     * 将节点分裂成 4 个子节点。
     */
    private void split() {
        double subWidth = bounds.getWidth() / 2;
        double subHeight = bounds.getHeight() / 2;
        double x = bounds.getMinX();
        double y = bounds.getMinY();
        int nextLevel = level + 1;

        // 创建四个子节点
        children[0] = new QuadtreeNode(nextLevel, new Rectangle2D(x + subWidth, y, subWidth, subHeight), maxObjects, maxDepth);
        children[1] = new QuadtreeNode(nextLevel, new Rectangle2D(x, y, subWidth, subHeight), maxObjects, maxDepth);
        children[2] = new QuadtreeNode(nextLevel, new Rectangle2D(x, y + subHeight, subWidth, subHeight), maxObjects, maxDepth);
        children[3] = new QuadtreeNode(nextLevel, new Rectangle2D(x + subWidth, y + subHeight, subWidth, subHeight), maxObjects, maxDepth);

        // 将此节点的对象重新分配给子节点
        for (int i = objects.size() - 1; i >= 0; i--) {
            GameClient.StaticObstacle obj = objects.get(i);
            Rectangle2D objBounds = obj.bounds;
            if (objBounds == null) {
                continue;
            }

            int index = getIndex(objBounds);
            if (index != -1) {
                children[index].insert(obj);
                objects.remove(i);
            }
        }
    }

    /**
     * 确定对象属于哪个子节点。
     *
     * @param pRect 对象的边界矩形
     * @return 子节点的索引 (0-3)，如果对象跨越多个区域则返回 -1
     */
    private int getIndex(Rectangle2D pRect) {
        int index = -1;
        double verticalMidpoint = bounds.getMinX() + (bounds.getWidth() / 2);
        double horizontalMidpoint = bounds.getMinY() + (bounds.getHeight() / 2);

        boolean topQuadrant = (pRect.getMinY() < horizontalMidpoint && pRect.getMaxY() < horizontalMidpoint);
        boolean bottomQuadrant = (pRect.getMinY() > horizontalMidpoint);

        if (pRect.getMinX() < verticalMidpoint && pRect.getMaxX() < verticalMidpoint) {
            if (topQuadrant) {
                index = 1;
            } else if (bottomQuadrant) {
                index = 2;
            }
        } else if (pRect.getMinX() > verticalMidpoint) {
            if (topQuadrant) {
                index = 0;
            } else if (bottomQuadrant) {
                index = 3;
            }
        }
        return index;
    }

    /**
     * 将对象插入到四叉树中。
     *
     * @param obj 要插入的静态障碍物对象
     */
    public void insert(GameClient.StaticObstacle obj) {
        Rectangle2D objBounds = obj.bounds;
        if (objBounds == null) {
            return;
        }

        if (children[0] != null) {
            int index = getIndex(objBounds);
            if (index != -1) {
                children[index].insert(obj);
                return;
            }
        }

        objects.add(obj);

        if (objects.size() > maxObjects && level < maxDepth && children[0] == null) {
            split();
        }
    }

    /**
     * 返回所有可能与给定射线相交的对象。
     *
     * @param returnObjects 用于收集结果的列表
     * @param rayStart      射线的起点
     * @param rayEnd        射线的终点
     * @return 包含潜在相交对象的列表
     */
    public List<GameClient.StaticObstacle> queryRay(List<GameClient.StaticObstacle> returnObjects, Point2D rayStart, Point2D rayEnd) {
        if (!intersectsRay(bounds, rayStart, rayEnd)) {
            return returnObjects;
        }

        returnObjects.addAll(objects);

        if (children[0] != null) {
            children[0].queryRay(returnObjects, rayStart, rayEnd);
            children[1].queryRay(returnObjects, rayStart, rayEnd);
            children[2].queryRay(returnObjects, rayStart, rayEnd);
            children[3].queryRay(returnObjects, rayStart, rayEnd);
        }

        return returnObjects;
    }

    /**
     * 辅助方法：检查线段（射线）是否与矩形相交。
     */
    private boolean intersectsRay(Rectangle2D rect, Point2D p1, Point2D p2) {
        double dx = p2.getX() - p1.getX();
        double dy = p2.getY() - p1.getY();
        double t0 = 0.0;
        double t1 = 1.0;
        double[] p = {-dx, dx, -dy, dy};
        double[] q = {p1.getX() - rect.getMinX(), rect.getMaxX() - p1.getX(), p1.getY() - rect.getMinY(), rect.getMaxY() - p1.getY()};

        for (int i = 0; i < 4; i++) {
            if (p[i] == 0) {
                if (q[i] < 0) {
                    return false;
                }
            } else {
                double r = q[i] / p[i];
                if (p[i] < 0) {
                    if (r > t1) {
                        return false;
                    }
                    if (r > t0) {
                        t0 = r;
                    }
                } else {
                    if (r < t0) {
                        return false;
                    }
                    if (r < t1) {
                        t1 = r;
                    }
                }
            }
        }
        return t0 <= t1;
    }

    /**
     * 返回所有其边界与给定查询边界相交的对象。
     *
     * @param returnObjects 用于收集结果的列表
     * @param queryBounds   查询的边界矩形
     * @return 包含相交对象的列表
     */
    public List<GameClient.StaticObstacle> queryBounds(List<GameClient.StaticObstacle> returnObjects, Rectangle2D queryBounds) {
        if (!bounds.intersects(queryBounds)) {
            return returnObjects;
        }

        for (GameClient.StaticObstacle obj : objects) {
            Rectangle2D objBounds = obj.bounds;
            if (objBounds != null && objBounds.intersects(queryBounds)) {
                returnObjects.add(obj);
            }
        }

        if (children[0] != null) {
            children[0].queryBounds(returnObjects, queryBounds);
            children[1].queryBounds(returnObjects, queryBounds);
            children[2].queryBounds(returnObjects, queryBounds);
            children[3].queryBounds(returnObjects, queryBounds);
        }

        return returnObjects;
    }

    /**
     * 直接按离散 FOV 射线覆盖范围遍历四叉树。节点边界连一条射线都不可能命中时，
     * 整棵子树都会被跳过；判断是保守的，不会牺牲遮挡精度。
     */
    public List<GameClient.StaticObstacle> queryFov(List<GameClient.StaticObstacle> returnObjects,
            double sourceX, double sourceY, double sourceAngle, double halfFovRadians,
            double angleStep, int rayCount, double rayLength) {
        if (GameClient.fovRayIndexRange(bounds, sourceX, sourceY, sourceAngle,
                halfFovRadians, angleStep, rayCount, rayLength) < 0) {
            return returnObjects;
        }

        for (GameClient.StaticObstacle obj : objects) {
            if (GameClient.fovRayIndexRange(obj.bounds, sourceX, sourceY, sourceAngle,
                    halfFovRadians, angleStep, rayCount, rayLength) >= 0) {
                returnObjects.add(obj);
            }
        }

        if (children[0] != null) {
            children[0].queryFov(returnObjects, sourceX, sourceY, sourceAngle,
                    halfFovRadians, angleStep, rayCount, rayLength);
            children[1].queryFov(returnObjects, sourceX, sourceY, sourceAngle,
                    halfFovRadians, angleStep, rayCount, rayLength);
            children[2].queryFov(returnObjects, sourceX, sourceY, sourceAngle,
                    halfFovRadians, angleStep, rayCount, rayLength);
            children[3].queryFov(returnObjects, sourceX, sourceY, sourceAngle,
                    halfFovRadians, angleStep, rayCount, rayLength);
        }
        return returnObjects;
    }
}
