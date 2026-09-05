package cs2d.server;

import java.awt.*;
import java.awt.geom.Point2D;
import java.awt.geom.Rectangle2D;
import java.io.Serializable;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 地图数据容器类 - 存储游戏地图的所有配置信息
 * 支持序列化以便网络传输和文件存储
 *
 * 设计特点：
 * - 支持自定义地图尺寸
 * - 多出生区域系统，避免玩家堆积
 * - 序列化友好的形状包装器
 * - 炸弹模式专用区域定义
 */
public class MapData implements Serializable {
    private static final long serialVersionUID = 1L;

    // === 地图基础属性 ===
    private int width = 1600; // 地图宽度（像素）- 默认16:9比例
    private int height = 900; // 地图高度（像素）

    // === 地图元素集合 ===
    private String name; // 地图的正式名称或文件名，方便全局携带
    private List<ShapeWrapper> obstacles = new ArrayList<>(); // 碰撞障碍物列表
    private List<Rectangle> ctSpawnAreas = new ArrayList<>(); // 反恐精英出生区域（多区域支持）
    private List<Rectangle> tSpawnAreas = new ArrayList<>(); // 恐怖分子出生区域（多区域支持）
    private Rectangle bombSiteA; // A点炸弹安装区域
    private Rectangle bombSiteB; // B点炸弹安装区域
    private Set<Point> forbiddenSpawnCells = new HashSet<>(); // 禁止复活的网格单元
    private Set<Point> generalForbiddenZones = new HashSet<>(); // 寻路禁区 / 通用禁区

    // === Getter/Setter 方法 ===
    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public int getWidth() {
        return width;
    }

    public void setWidth(int width) {
        this.width = width;
    }

    public int getHeight() {
        return height;
    }

    public void setHeight(int height) {
        this.height = height;
    }

    public List<ShapeWrapper> getObstacles() {
        return obstacles;
    }

    public void setObstacles(List<ShapeWrapper> obstacles) {
        this.obstacles = obstacles;
    }

    public List<Rectangle> getCtSpawnAreas() {
        return ctSpawnAreas;
    }

    public void setCtSpawnAreas(List<Rectangle> ctSpawnAreas) {
        this.ctSpawnAreas = ctSpawnAreas;
    }

    public List<Rectangle> getTSpawnAreas() {
        return tSpawnAreas;
    }

    public void setTSpawnAreas(List<Rectangle> tSpawnAreas) {
        this.tSpawnAreas = tSpawnAreas;
    }

    public Rectangle getBombSiteA() {
        return bombSiteA;
    }

    public void setBombSiteA(Rectangle bombSiteA) {
        this.bombSiteA = bombSiteA;
    }

    public Rectangle getBombSiteB() {
        return bombSiteB;
    }

    public void setBombSiteB(Rectangle bombSiteB) {
        this.bombSiteB = bombSiteB;
    }

    public Set<Point> getForbiddenSpawnCells() {
        if (this.forbiddenSpawnCells == null) {
            this.forbiddenSpawnCells = new HashSet<>();
        }
        return this.forbiddenSpawnCells;
    }

    public void setForbiddenSpawnCells(Set<Point> cells) {
        this.forbiddenSpawnCells = cells;
    }

    public Set<Point> getGeneralForbiddenZones() {
        if (this.generalForbiddenZones == null) {
            this.generalForbiddenZones = new HashSet<>();
        }
        return this.generalForbiddenZones;
    }

    public void setGeneralForbiddenZones(Set<Point> zones) {
        this.generalForbiddenZones = zones;
    }

    /**
     * 一个公共静态内部类，用于存储路径点及其连接信息。
     * 这个类可以被 Gson 完美地序列化到 .json 文件中。
     */
    public static class SerializableWaypoint {
        public Point2D.Double position; // 路径点的位置

        /**
         * 存储邻居的 *索引*。
         * 例如，如果这个点(索引5)连接到列表中的索引8和索引12，这里就存 [8, 12]
         */
        public List<Integer> neighborIndices;

        /**
         * 存储到每个邻居的 *成本* (通常是直线距离)。
         * 顺序必须与 neighborIndices 保持一致。
         */
        public List<Double> neighborCosts;

        // Gson 需要一个无参构造函数
        public SerializableWaypoint() {
            this.neighborIndices = new ArrayList<>();
            this.neighborCosts = new ArrayList<>();
        }

        // MapEditor 创建新点时使用的构造函数
        public SerializableWaypoint(Point2D.Double position) {
            this.position = position;
            this.neighborIndices = new ArrayList<>();
            this.neighborCosts = new ArrayList<>();
        }
    }

    private List<SerializableWaypoint> waypoints = new ArrayList<>();

    /**
     * 获取路径点坐标列表。
     * 这个方法会确保即使加载的是没有路径点的旧地图文件，列表也会被正确初始化。
     *
     * @return 路径点坐标的列表。
     */
    public List<SerializableWaypoint> getWaypoints() {
        if (this.waypoints == null) {
            this.waypoints = new ArrayList<>();
        }
        return this.waypoints;
    }

    /**
     * 辅助方法：获取 ShapeWrapper 的 AWT 边界框
     * 用于 Quadtree 构建和查询时的快速碰撞检测。
     * 
     * @param wrapper 形状对应的抽象包装对象
     * @return 该形状包围盒的 AWT 原生实现
     */
    public static java.awt.geom.Rectangle2D.Double getObstacleBounds(ShapeWrapper wrapper) {
        if (wrapper == null)
            return new java.awt.geom.Rectangle2D.Double();

        java.awt.geom.Rectangle2D.Double cached = wrapper.cachedBounds;
        if (cached != null)
            return cached;

        java.awt.geom.Rectangle2D.Double calculated = switch (wrapper.type) {
            case RECTANGLE:
            case ELLIPSE:
                // 矩形和椭圆的边界直接就是 x, y, w, h
                yield new java.awt.geom.Rectangle2D.Double(wrapper.x, wrapper.y, wrapper.width, wrapper.height);

            case POLYGON:
                // 多边形需要计算 xPoints 和 yPoints 的最大最小值来生成包围盒 (AABB)
                if (wrapper.xPoints == null || wrapper.xPoints.length == 0) {
                    yield new java.awt.geom.Rectangle2D.Double();
                }

                double minX = Double.MAX_VALUE;
                double minY = Double.MAX_VALUE;
                double maxX = -Double.MAX_VALUE; // 注意这里初始化为极小值
                double maxY = -Double.MAX_VALUE;

                for (int i = 0; i < wrapper.xPoints.length; i++) {
                    if (wrapper.xPoints[i] < minX)
                        minX = wrapper.xPoints[i];
                    if (wrapper.xPoints[i] > maxX)
                        maxX = wrapper.xPoints[i];
                    if (wrapper.yPoints[i] < minY)
                        minY = wrapper.yPoints[i];
                    if (wrapper.yPoints[i] > maxY)
                        maxY = wrapper.yPoints[i];
                }
                // 宽度 = maxX - minX, 高度 = maxY - minY
                yield new java.awt.geom.Rectangle2D.Double(minX, minY, maxX - minX, maxY - minY);

            default:
                yield new java.awt.geom.Rectangle2D.Double();
        };
        wrapper.cachedBounds = calculated;
        return calculated;
    }

    public void setWaypoints(List<SerializableWaypoint> waypoints) {
        this.waypoints = waypoints;
    }

    /**
     * 形状包装器
     */
    public static class ShapeWrapper implements Serializable {
        private static final long serialVersionUID = 1L;

        enum ShapeType {
            RECTANGLE, ELLIPSE, POLYGON
        }

        ShapeType type;
        double x, y;
        double width, height;
        double[] xPoints;
        double[] yPoints;
        /** 服务端静态地图空间查询缓存；transient 防止进入地图 JSON/序列化协议。 */
        transient volatile java.awt.geom.Rectangle2D.Double cachedBounds;

        /**
         * 矩形/椭圆构造器
         */
        ShapeWrapper(ShapeType type, double x, double y, double width, double height) {
            this.type = type;
            this.x = x;
            this.y = y;
            this.width = width;
            this.height = height;
        }

        /**
         * 多边形构造器 (已更新)
         *
         * @param xPoints 多边形顶点X坐标数组
         * @param yPoints 多边形顶点Y坐标数组
         */
        ShapeWrapper(int[] xPoints, int[] yPoints) {
            this.type = ShapeType.POLYGON;

            // 存储多边形的“基准点”（第一个顶点）
            // 这对于正确的拖拽计算至关重要
            this.x = xPoints[0];
            this.y = yPoints[0];

            this.xPoints = new double[xPoints.length];
            this.yPoints = new double[yPoints.length];
            for (int i = 0; i < xPoints.length; i++)
                this.xPoints[i] = xPoints[i];
            for (int i = 0; i < yPoints.length; i++)
                this.yPoints[i] = yPoints[i];
        }

        // GSON 需要一个无参构造函数来进行反序列化
    }

}
