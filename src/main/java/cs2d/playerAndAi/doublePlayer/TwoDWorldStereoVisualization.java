package cs2d.playerAndAi.doublePlayer;

import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.awt.geom.*;
import java.util.ArrayList;
import java.util.List;
import java.util.OptionalDouble;

// === 新增的导入 ===
import com.google.gson.Gson; // <-- 你需要添加 GSON 库!
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Paths;
// ==================

/**
 * 一个Java Swing应用程序，用于可视化“二维小人”通过“一只眼睛”看到的世界。
 * 60度视野，伪3D渲染。
 *
 * === 优化版本 ===
 * 1. 使用 `Line2D` 替代 `Shape` 作为渲染和光线投射的基础。
 * 2. 四叉树被修改为存储和查询 `Line2D`。
 * 3. 渲染核心从 "Ray Marching" (光线步进, 慢) 切换到 "Ray Casting" (光线投射, 快)。
 * 4. `castRay` 方法现在使用数学交点计算，而不是每步检查 `contains()`。
 *
 * @author Gemini (重构和优化)
 */
public class TwoDWorldStereoVisualization extends JFrame {

    private static final String PATH = "O:\\java\\games\\CS2D-MultiplayerUDP\\maps\\muzzle.json";

    /**
     * === 匹配重生区域的 POJO ===
     * (注意：它不S是 Quadtree.SpawnArea)
     */
    static class SpawnArea {
        double x;
        double y;
        double width;
        double height;
    }

    /**
     * 匹配 JSON 的根结构
     */
    static class MapData {
        double width;
        double height;
        List<Obstacle> obstacles;

        // === 确保你添加了这一行！ ===
        // 这就是为什么 ctSpawnAreas 变红的原因
        List<SpawnArea> ctSpawnAreas;
        // ============================
    }

    /**
     * 匹配 "obstacles" 数组中的每个对象
     */
    static class Obstacle {
        String type; // "RECTANGLE", "POLYGON", etc.
        double x;
        double y;
        double width;
        double height;
        double[] xPoints; // 仅用于 POLYGON
        double[] yPoints; // 仅用于 POLYGON
    }

    // ========================================================
    /**
     * 构造函数，设置窗口。
     */
    public TwoDWorldStereoVisualization() {
        setTitle("2D小人的世界 (单眼, Raycasting优化版, JSON加载)");
        setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);

        DrawingPanel panel = new DrawingPanel();

        add(panel);
        setSize(1600, 900); // 窗口大小 (800px 用于地图, 800px 用于第一人称视图)
        setLocationRelativeTo(null); // 居中
        setVisible(true);
    }

    /**
     * 程序入口点。
     * @param args 命令行参数（未使用）
     */
    public static void main(String[] args) {
        SwingUtilities.invokeLater(() -> new TwoDWorldStereoVisualization());
    }

    /**
     * 内部类，处理所有绘图、逻辑和输入。
     */
    class DrawingPanel extends JPanel implements KeyListener, ActionListener {

        // 地图数据
        // 'obstacles' 用于玩家的物理碰撞 (使用 .contains() 简单)
        private final List<Shape> obstacles = new ArrayList<>();
        // 'worldLines' 用于光线投射和地图绘制 (高性能)
        private final List<Line2D.Double> worldLines = new ArrayList<>();

        // 注意：这些值现在可以从JSON中更新，但我们保留默认值以防万一
        private double MAP_WIDTH = 4740;
        private double MAP_HEIGHT = 4640;

        // 玩家（小人）
        private Point2D.Double playerPos;
        private Rectangle2D ctSpawnZone = null;
        private double playerAngle; // 弧度

        // === 基于时间的移动 ===
        private final double MOVE_SPEED_PER_SECOND = 600.0; // 每秒移动600个单位
        private final double ROTATE_SPEED_PER_SECOND = 3.0; // 每秒旋转3.0弧度 (约172度)
        private long lastFrameTime; // 用于计算 delta time
        private double lastFrameTimeMs = 0.0; // <-- 用于显示的帧时间

        // 立体视觉参数 (已简化为单眼)
        private final double EYE_FOV = Math.toRadians(60.0); // 60度FOV
        private final double MAX_VIEW_DISTANCE = 1500.0;

        // 控制
        private final Timer gameLoop;
        private final boolean[] keys = new boolean[256];

        // === 四叉树 (优化版：存储 Line2D.Double) ===
        private Quadtree quadtree;

        /**
         * DrawingPanel 构造函数。
         */
        public DrawingPanel() {
            // 1. 初始化地图数据 (填充 obstacles 和 worldLines)
            initMap(); // <-- 这个方法会填充上面的 MAP_WIDTH, MAP_HEIGHT, 和 ctSpawnZone

            // 2. === 构建四叉树 ===
            //    初始化四叉树，边界为整个地图
            //    (使用从 JSON 加载的 MAP_WIDTH 和 MAP_HEIGHT)
            this.quadtree = new Quadtree(0, new Rectangle2D.Double(0, 0, MAP_WIDTH, MAP_HEIGHT), 10, 8);

            // ... (将 worldLines 插入树中) ...
            for (Line2D.Double line : worldLines) {
                quadtree.insert(line);
            }

            // === 修改：玩家位置 ===
            // 玩家位置和朝向
            if (this.ctSpawnZone != null) {
                // 重生在CT重生点的中心
                playerPos = new Point2D.Double(
                        this.ctSpawnZone.getCenterX(),
                        this.ctSpawnZone.getCenterY()
                );
            } else {
                // 如果（万一）重生点加载失败，退回到左上角
                playerPos = new Point2D.Double(252.0, 474.0);
                System.err.println("警告: 未能加载CT重生点，玩家出生在默认位置。");
            }
            playerAngle = Math.toRadians(0); // 面向右侧

            addKeyListener(this);
            setFocusable(true);
            setFocusTraversalKeysEnabled(false);

            gameLoop = new Timer(16, this); // 约 60 FPS

            // === 初始化帧时间 ===
            lastFrameTime = System.nanoTime();
            gameLoop.start();
        }

        // ====================================================================
        // === 地图初始化 (已重构) ===
        // ====================================================================

        /**
         * === 已修改：从JSON文件加载地图 ===
         * 初始化地图，加载所有障碍物。
         * 现在会从 JSON 文件读取数据来填充 'obstacles' 和 'worldLines'。
         */

        private void initMap() {
            obstacles.clear();
            worldLines.clear();

            // 2. 从JSON文件加载障碍物
            // !!! 确保这个路径是正确的 !!!
            String jsonFilePath = PATH;
            Gson gson = new Gson();

            try {
                // 读取文件所有内容
                String jsonContent = Files.readString(Paths.get(jsonFilePath));

                // 使用 Gson 解析 JSON 字符串到我们的 MapData POJO
                MapData mapData = gson.fromJson(jsonContent, MapData.class);

                if (mapData != null) {
                    // === 修改：取消注释以读取地图大小 ===
                    this.MAP_WIDTH = mapData.width;
                    this.MAP_HEIGHT = mapData.height;
                    System.out.println("地图大小已加载: " + this.MAP_WIDTH + " x " + this.MAP_HEIGHT);

                    // 1. 添加一个环绕地图的边界 (使用从JSON加载的边界)
                    addRect(new Rectangle2D.Double(-1, -1, MAP_WIDTH + 2, 1));
                    addRect(new Rectangle2D.Double(-1, -1, 1, MAP_HEIGHT + 2));
                    addRect(new Rectangle2D.Double(-1, MAP_HEIGHT, MAP_WIDTH + 2, 1));
                    addRect(new Rectangle2D.Double(MAP_WIDTH, -1, 1, MAP_HEIGHT + 2));


                    // === 新增：读取CT重生点 ===
                    if (mapData.ctSpawnAreas != null && !mapData.ctSpawnAreas.isEmpty()) {
                        // 获取第一个CT重生点

                        // 修正：类型是 SpawnArea, 而不是 Quadtree.SpawnArea
                        SpawnArea spawn = mapData.ctSpawnAreas.get(0);

                        // (一旦你在 MapData 中添加了 ctSpawnAreas 字段，这里的红色也会消失)
                        this.ctSpawnZone = new Rectangle2D.Double(spawn.x, spawn.y, spawn.width, spawn.height);
                        System.out.println("CT 重生点加载成功: " + this.ctSpawnZone.toString());
                    } else {
                        System.err.println("警告: 未在JSON中找到 'ctSpawnAreas'。");
                    }


                    // 3. 遍历JSON中的障碍物并添加到地图
                    if (mapData.obstacles != null) {
                        System.out.println("成功加载 " + mapData.obstacles.size() + " 个障碍物。");

                        for (Obstacle obs : mapData.obstacles) {
                            if (obs.type == null) continue;

                            // 根据障碍物类型调用相应的辅助方法
                            switch (obs.type) {
                                case "RECTANGLE":
                                    addRect(new Rectangle2D.Double(obs.x, obs.y, obs.width, obs.height));
                                    break;

                                case "POLYGON":
                                    if (obs.xPoints != null && obs.yPoints != null && obs.xPoints.length == obs.yPoints.length) {
                                        Polygon poly = new Polygon();
                                        for (int i = 0; i < obs.xPoints.length; i++) {
                                            // java.awt.Polygon.addPoint 需要 int 类型坐标
                                            poly.addPoint((int) obs.xPoints[i], (int) obs.yPoints[i]);
                                        }
                                        addPolygon(poly);
                                    } else {
                                        System.err.println("警告: 发现类型为POLYGON的障碍物，但xPoints/yPoints数据无效。");
                                    }
                                    break;

                                // 默认：忽略未知类型
                                default:
                                    // System.out.println("信息: 忽略未知障碍物类型: " + obs.type);
                                    break;
                            }
                        }
                    }
                } else {
                    System.err.println("错误: 无法加载地图数据。");
                    addDemoObstaclesOnError(); // 加载备用障碍物
                }

            } catch (IOException e) {
                System.err.println("错误: 无法读取JSON地图文件: " + jsonFilePath);
                e.printStackTrace();
                // 即使文件加载失败，也添加一些默认障碍物以便程序运行
                addDemoObstaclesOnError();
            } catch (Exception e) {
                System.err.println("错误: 解析JSON时发生未知错误。");
                e.printStackTrace();
                addDemoObstaclesOnError();
            }
        }

        /** * 辅助方法：如果JSON加载失败，添加一些默认障碍物以防程序崩溃
         */
        private void addDemoObstaclesOnError() {
            System.out.println("警告: JSON加载失败，正在加载备用障碍物...");
            addRect(new Rectangle2D.Double(500, 500, 100, 100));
            addRect(new Rectangle2D.Double(800, 1000, 200, 50));
            addRect(new Rectangle2D.Double(300, 1500, 50, 200));
        }


        /** 辅助方法：添加一个矩形到 'obstacles' 和 'worldLines' */
        private void addRect(Rectangle2D rect) {
            obstacles.add(rect);
            worldLines.add(new Line2D.Double(rect.getMinX(), rect.getMinY(), rect.getMaxX(), rect.getMinY())); // Top
            worldLines.add(new Line2D.Double(rect.getMaxX(), rect.getMinY(), rect.getMaxX(), rect.getMaxY())); // Right
            worldLines.add(new Line2D.Double(rect.getMaxX(), rect.getMaxY(), rect.getMinX(), rect.getMaxY())); // Bottom
            worldLines.add(new Line2D.Double(rect.getMinX(), rect.getMaxY(), rect.getMinX(), rect.getMinY())); // Left
        }

        /** 辅助方法：添加一个多边形到 'obstacles' 和 'worldLines' */
        private void addPolygon(Polygon poly) {
            obstacles.add(poly);
            for (int i = 0; i < poly.npoints; i++) {
                Point2D p1 = new Point2D.Double(poly.xpoints[i], poly.ypoints[i]);
                Point2D p2 = new Point2D.Double(poly.xpoints[(i + 1) % poly.npoints], poly.ypoints[(i + 1) % poly.npoints]);
                worldLines.add(new Line2D.Double(p1, p2));
            }
        }

        /** 辅助方法：添加一个椭圆到 'obstacles' 和 'worldLines' (近似为线段) */
        private void addEllipse(Ellipse2D ellipse) {
            // (此方法保留，但现在不再由 initMap 调用)
            obstacles.add(ellipse);
            int segments = 36; // 用36条线段近似一个圆
            double cx = ellipse.getCenterX();
            double cy = ellipse.getCenterY();
            double rx = ellipse.getWidth() / 2.0;
            double ry = ellipse.getHeight() / 2.0;

            Point2D.Double firstPt = null;
            Point2D.Double lastPt = null;

            for (int i = 0; i <= segments; i++) {
                double angle = (i / (double) segments) * Math.PI * 2.0;
                double px = cx + Math.cos(angle) * rx;
                double py = cy + Math.sin(angle) * ry;
                Point2D.Double currentPt = new Point2D.Double(px, py);

                if (i == 0) {
                    firstPt = currentPt;
                }
                if (i > 0) {
                    worldLines.add(new Line2D.Double(lastPt, currentPt));
                }
                lastPt = currentPt;
            }
        }


        // ====================================================================
        // === 渲染核心 (未修改) ===
        // ====================================================================

        /**
         * 主绘图方法。
         */
        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            Graphics2D g2d = (Graphics2D) g;

            // 开启抗锯齿，使线条更平滑
            g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

            // 清空屏幕
            g2d.setColor(Color.BLACK);
            g2d.fillRect(0, 0, getWidth(), getHeight());

            // 面板分为两半
            int halfWidth = getWidth() / 2;
            int height = getHeight();

            // --- 1. 绘制左侧的2D俯视图 (上帝视角) ---
            drawTopDownView(g2d, 0, 0, halfWidth, height);

            // --- 2. 绘制右侧的第一人称视图 ---
            drawFirstPersonView(g2d, halfWidth, 0, halfWidth, height);

            // 绘制分割线
            g2d.setColor(Color.WHITE);
            g2d.drawLine(halfWidth, 0, halfWidth, height);

            // 绘制提示
            g2d.setColor(Color.YELLOW);
            g2d.setFont(new Font("Monospaced", Font.BOLD, 16));
            g2d.drawString("左: 上帝视角 (W/S/A/D 移动)", 10, 20);
            g2d.drawString("右: 2D小人的[单眼]60度视图", halfWidth + 10, 20);

            // 绘制调试信息
            g2d.setColor(Color.GREEN);
            String msString = String.format("Frame Time: %.2f ms", lastFrameTimeMs);
            double fps = (lastFrameTimeMs > 0) ? (1000.0 / lastFrameTimeMs) : 0.0;
            String fpsString = String.format("FPS: %.0f", fps);

            g2d.drawString(msString, 10, 40);
            g2d.drawString(fpsString, 10, 60);
        }

        /**
         * 绘制左侧的2D俯视（上帝）视图。
         * (已修改为绘制 worldLines)
         */
        private void drawTopDownView(Graphics2D g2d, int x, int y, int w, int h) {
            AffineTransform oldTransform = g2d.getTransform();

            g2d.translate(x + w / 2.0, y + h / 2.0);
            double scale = Math.min(w / MAP_WIDTH, h / MAP_HEIGHT) * 0.9;
            g2d.scale(scale, scale);
            g2d.translate(-playerPos.x, -playerPos.y);

            g2d.setClip((int)(playerPos.x - (w/2.0)/scale), (int)(playerPos.y - (h/2.0)/scale), (int)(w/scale), (int)(h/scale));

            g2d.setColor(new Color(20, 20, 20));
            g2d.fillRect((int)(playerPos.x - w/scale), (int)(playerPos.y - h/scale), (int)(w/scale*2), (int)(h/scale*2));

            // 绘制所有障碍物线段
            g2d.setColor(Color.GRAY);
            g2d.setStroke(new BasicStroke(2.0f / (float)scale));
            for (Line2D.Double line : worldLines) {
                g2d.draw(line);
            }

            // 绘制玩家
            double playerSize = 25.0 / scale;
            g2d.setColor(Color.RED);
            g2d.fill(new Ellipse2D.Double(
                    playerPos.x - playerSize / 2,
                    playerPos.y - playerSize / 2,
                    playerSize,
                    playerSize
            ));

            // 绘制单眼60度FOV锥形
            g2d.setStroke(new BasicStroke(1.0f / (float)scale));
            double fovLeftEdgeAngle = playerAngle - EYE_FOV / 2.0;
            double fovRightEdgeAngle = playerAngle + EYE_FOV / 2.0;

            g2d.setColor(new Color(0, 255, 255, 100));
            g2d.draw(new Line2D.Double(playerPos.x, playerPos.y,
                    playerPos.x + Math.cos(fovLeftEdgeAngle) * MAX_VIEW_DISTANCE,
                    playerPos.y + Math.sin(fovLeftEdgeAngle) * MAX_VIEW_DISTANCE));
            g2d.draw(new Line2D.Double(playerPos.x, playerPos.y,
                    playerPos.x + Math.cos(fovRightEdgeAngle) * MAX_VIEW_DISTANCE,
                    playerPos.y + Math.sin(fovRightEdgeAngle) * MAX_VIEW_DISTANCE));

            g2d.setTransform(oldTransform);
            g2d.setClip(null);
        }

        /**
         * 绘制单只眼睛的伪3D视图 (LOD优化保留)
         * (已修改为使用 castRay_Optimized)
         */
        private void drawFirstPersonView(Graphics2D g2d, int x, int y, int w, int h)
        {
            AffineTransform oldTransform = g2d.getTransform();
            g2d.translate(x, y);
            g2d.setClip(0, 0, w, h);

            // 绘制天花板和地板
            g2d.setColor(new Color(50, 50, 50)); // 深灰色地板
            g2d.fillRect(0, h / 2, w, h / 2);
            g2d.setColor(new Color(135, 206, 235)); // 天蓝色天空
            g2d.fillRect(0, 0, w, h / 2);

            int numRays = w;
            double correctedDistance = 0;

            // 动态LOD步进循环
            int i = 0;
            while (i < numRays) {
                // 动态LOD步长
                int step = 1;
                if (correctedDistance > MAX_VIEW_DISTANCE * 0.8) {
                    step = 4;
                } else if (correctedDistance > MAX_VIEW_DISTANCE * 0.5) {
                    step = 2;
                }

                // 1. 计算光线角度
                double rayAngle = (playerAngle - EYE_FOV / 2.0) + (i * (EYE_FOV / numRays));

                // 2. 投射光线并获取距离 (!!! 调用优化后的版本 !!!)
                double distance = castRay_Optimized(playerPos, rayAngle).orElse(MAX_VIEW_DISTANCE);

                // 3. 修正鱼眼镜头
                double fishEyeCorrection = Math.cos(rayAngle - playerAngle);
                correctedDistance = distance * fishEyeCorrection;

                // 4. 计算墙壁高度
                double wallHeight = (h * 50.0) / correctedDistance;

                // 5. 计算墙壁的起始和结束y坐标
                double wallTop = (h / 2.0) - (wallHeight / 2.0);
                double wallBottom = (h / 2.0) + (wallHeight / 2.0);

                // 6. 裁剪墙壁
                int drawTop = (int) Math.max(0, wallTop);
                int drawBottom = (int) Math.min(h, wallBottom);

                // 7. 根据距离计算阴影
                // === 修改开始 ===
                // double shade = 1.0 - (distance / MAX_VIEW_DISTANCE);
                // shade = Math.max(0, Math.min(1, shade));

                // 新的计算方式：
                // 1. 计算距离百分比 (0.0 = 近, 1.0 = 远)
                double distancePercent = Math.max(0, Math.min(1, distance / MAX_VIEW_DISTANCE));

                // 2. 设置亮度范围 (1.0 = 100% 亮度, 0.4 = 40% 亮度)
                double maxBrightness = 1.0;
                double minBrightness = 0.4; // <-- 这是关键！墙壁最暗也只到40%亮度

                // 3. 线性插值计算阴影
                double shade = maxBrightness - (distancePercent * (maxBrightness - minBrightness));
                // === 修改结束 ===

                Color baseWallColor = Color.GRAY;

                Color shadedColor = new Color(
                        (int)(baseWallColor.getRed() * shade),
                        (int)(baseWallColor.getGreen() * shade),
                        (int)(baseWallColor.getBlue() * shade)
                );

                // 8. 绘制垂直切片 (LOD步长)
                g2d.setColor(shadedColor);
                g2d.fillRect(i, drawTop, step, (drawBottom - drawTop + 1));

                i += step;
            }

            g2d.setTransform(oldTransform);
            g2d.setClip(null);
        }

        /**
         * === 核心优化：真正的光线投射 (Ray Casting) ===
         *
         * @param startPoint 射线起点 (玩家位置)
         * @param angle      射线角度
         * @return 最近的碰撞距离
         */
        private OptionalDouble castRay_Optimized(Point2D.Double startPoint, double angle) {
            double rayDX = Math.cos(angle);
            double rayDY = Math.sin(angle);

            // 1. 计算光线的终点 (用于四叉树查询)
            Point2D.Double endPoint = new Point2D.Double(
                    startPoint.x + rayDX * MAX_VIEW_DISTANCE,
                    startPoint.y + rayDY * MAX_VIEW_DISTANCE
            );

            // 2. 从四叉树获取一个 *小得多* 的可能碰撞线段列表
            List<Line2D.Double> potentialColliders = new ArrayList<>();
            quadtree.queryRay(potentialColliders, startPoint, endPoint);

            double minDistance = Double.POSITIVE_INFINITY;
            Point2D.Double closestIntersection = null;

            // 3. 遍历 *小列表* 中的线段，并进行数学交点计算
            // (!!! 已删除 1500 步的循环 !!!)
            for (Line2D.Double wall : potentialColliders) {
                // 计算射线和线段的交点
                Point2D.Double intersection = getRaySegmentIntersection(startPoint, endPoint, wall);

                if (intersection != null) {
                    double distance = startPoint.distance(intersection);
                    if (distance < minDistance) {
                        minDistance = distance;
                        closestIntersection = intersection;
                    }
                }
            }

            // 4. 返回最近的交点距离
            if (closestIntersection != null) {
                return OptionalDouble.of(minDistance);
            } else {
                return OptionalDouble.empty(); // 没有撞到
            }
        }

        /**
         * 辅助函数：计算射线和线段的交点。
         *
         * @param rayP1 射线起点
         * @param rayP2 射线终点
         * @param segment 线段 (P3, P4)
         * @return 交点 (Point2D.Double) 或 null
         */
        private Point2D.Double getRaySegmentIntersection(Point2D.Double rayP1, Point2D.Double rayP2, Line2D.Double segment) {
            // 射线 (P1 -> P2)
            double x1 = rayP1.x;
            double y1 = rayP1.y;
            double x2 = rayP2.x;
            double y2 = rayP2.y;

            // 线段 (P3 -> P4)
            double x3 = segment.x1;
            double y3 = segment.y1;
            double x4 = segment.x2;
            double y4 = segment.y2;

            // 计算分母
            double den = (x1 - x2) * (y3 - y4) - (y1 - y2) * (x3 - x4);

            // 如果分母接近于0，线是平行的
            if (Math.abs(den) < 1e-6) {
                return null;
            }

            // 计算 t (射线) 和 u (线段)
            double t = ((x1 - x3) * (y3 - y4) - (y1 - y3) * (x3 - x4)) / den;
            double u = -((x1 - x2) * (y1 - y3) - (y1 - y2) * (x1 - x3)) / den;

            // --- 碰撞条件 ---
            // t >= 0:      交点在射线的方向上 (不是在后面)
            // u >= 0 && u <= 1: 交点在线段 P3-P4 之间
            if (t >= 0 && u >= 0 && u <= 1) {
                // 计算交点
                double Px = x1 + t * (x2 - x1);
                double Py = y1 + t * (y2 - y1);
                return new Point2D.Double(Px, Py);
            }

            // 没有交点
            return null;
        }


        // ====================================================================
        // === 游戏逻辑与输入 (未修改) ===
        // ====================================================================

        /**
         * 根据 delta time 更新玩家状态
         * (碰撞检测仍使用 'obstacles' 列表，因为它简单且不是瓶颈)
         * @param deltaTime 自上一帧以来经过的秒数
         */
        private void updatePlayer(double deltaTime) {
            double moveStep = 0.0;
            if (keys[KeyEvent.VK_W]) {
                moveStep = MOVE_SPEED_PER_SECOND * deltaTime;
            }
            if (keys[KeyEvent.VK_S]) {
                moveStep = -MOVE_SPEED_PER_SECOND * deltaTime;
            }

            double rotateAmount = 0.0;
            if (keys[KeyEvent.VK_A]) {
                rotateAmount = -ROTATE_SPEED_PER_SECOND * deltaTime;
            }
            if (keys[KeyEvent.VK_D]) {
                rotateAmount = ROTATE_SPEED_PER_SECOND * deltaTime;
            }

            playerAngle += rotateAmount;
            playerAngle = (playerAngle + 2 * Math.PI) % (2 * Math.PI);

            double newX = playerPos.x + Math.cos(playerAngle) * moveStep;
            double newY = playerPos.y + Math.sin(playerAngle) * moveStep;

            boolean collision = false;
            // 简单的碰撞检测 (仍使用 Shape.contains())
            for (Shape obstacle : obstacles) {
                if (obstacle.contains(newX, newY)) {
                    collision = true;
                    break;
                }
            }

            if (!collision) {
                playerPos.x = newX;
                playerPos.y = newY;
            }
        }

        /**
         * 游戏循环的主回调函数。
         */
        @Override
        public void actionPerformed(ActionEvent e) {
            long currentTime = System.nanoTime();
            double deltaTime = (currentTime - lastFrameTime) / 1_000_000_000.0;
            lastFrameTimeMs = (currentTime - lastFrameTime) / 1_000_000.0;
            lastFrameTime = currentTime;

            updatePlayer(deltaTime);
            repaint();
        }

        @Override
        public void keyTyped(KeyEvent e) {}

        @Override
        public void keyPressed(KeyEvent e) {
            if (e.getKeyCode() < 256) {
                keys[e.getKeyCode()] = true;
            }
        }

        @Override
        public void keyReleased(KeyEvent e) {
            if (e.getKeyCode() < 256) {
                keys[e.getKeyCode()] = false;
            }
        }

        // ====================================================================
        // === 四叉树 (Quadtree) 内部类 (未修改) ===
        // ====================================================================

        class Quadtree {
            private final int MAX_OBJECTS;
            private final int MAX_DEPTH;
            private final int level;
            private final Rectangle2D bounds;
            private final List<Line2D.Double> lines; // <-- 已修改
            private final Quadtree[] children;

            public Quadtree(int level, Rectangle2D bounds, int maxObjects, int maxDepth) {
                this.level = level;
                this.bounds = bounds;
                this.MAX_OBJECTS = maxObjects;
                this.MAX_DEPTH = maxDepth;
                this.lines = new ArrayList<>(); // <-- 已修改
                this.children = new Quadtree[4];
            }

            /** 辅助方法：获取 Line2D 的边界 */
            private Rectangle2D getBounds(Line2D.Double line) {
                return line.getBounds2D();
            }

            public void clear() {
                lines.clear();
                for (int i = 0; i < children.length; i++) {
                    if (children[i] != null) {
                        children[i].clear();
                        children[i] = null;
                    }
                }
            }

            private void split() {
                double subWidth = bounds.getWidth() / 2;
                double subHeight = bounds.getHeight() / 2;
                double x = bounds.getMinX();
                double y = bounds.getMinY();
                int nextLevel = level + 1;

                children[0] = new Quadtree(nextLevel, new Rectangle2D.Double(x + subWidth, y, subWidth, subHeight), MAX_OBJECTS, MAX_DEPTH);
                children[1] = new Quadtree(nextLevel, new Rectangle2D.Double(x, y, subWidth, subHeight), MAX_OBJECTS, MAX_DEPTH);
                children[2] = new Quadtree(nextLevel, new Rectangle2D.Double(x, y + subHeight, subWidth, subHeight), MAX_OBJECTS, MAX_DEPTH);
                children[3] = new Quadtree(nextLevel, new Rectangle2D.Double(x + subWidth, y + subHeight, subWidth, subHeight), MAX_OBJECTS, MAX_DEPTH);

                // 重新分配对象
                for (int i = lines.size() - 1; i >= 0; i--) {
                    Line2D.Double line = lines.get(i); // <-- 已修改
                    Rectangle2D lineBounds = getBounds(line); // <-- 已修改
                    int index = getIndex(lineBounds);

                    if (index != -1) {
                        children[index].insert(line); // <-- 已修改
                        lines.remove(i); // <-- 已修改
                    }
                }
            }

            /** 确定对象属于哪个子节点 (此逻辑保持不变) */
            private int getIndex(Rectangle2D pRect) {
                int index = -1;
                double verticalMidpoint = bounds.getMinX() + (bounds.getWidth() / 2);
                double horizontalMidpoint = bounds.getMinY() + (bounds.getHeight() / 2);

                boolean topQuadrant = (pRect.getMinY() < horizontalMidpoint && pRect.getMaxY() < horizontalMidpoint);
                boolean bottomQuadrant = (pRect.getMinY() > horizontalMidpoint && pRect.getMaxY() > horizontalMidpoint);

                if (pRect.getMinX() < verticalMidpoint && pRect.getMaxX() < verticalMidpoint) {
                    if (topQuadrant) index = 1;
                    else if (bottomQuadrant) index = 2;
                } else if (pRect.getMinX() > verticalMidpoint && pRect.getMaxX() > verticalMidpoint) {
                    if (topQuadrant) index = 0;
                    else if (bottomQuadrant) index = 3;
                }
                return index;
            }

            /** 将线段插入到四叉树中 */
            public void insert(Line2D.Double line) { // <-- 已修改
                Rectangle2D lineBounds = getBounds(line); // <-- 已修改

                if (children[0] != null) {
                    int index = getIndex(lineBounds);
                    if (index != -1) {
                        children[index].insert(line); // <-- 已修改
                        return;
                    }
                }

                lines.add(line); // <-- 已修改

                if (lines.size() > MAX_OBJECTS && level < MAX_DEPTH && children[0] == null) { // <-- 已修改
                    split();
                }
            }


            /**
             * === 新增：匹配重生区域的 POJO ===
             */
            static class SpawnArea {
                double x;
                double y;
                double width;
                double height;
            }

            /**
             * 匹配 JSON 的根结构
             */
            static class MapData {
                double width;
                double height;
                List<Obstacle> obstacles;
                // === 修改：添加重生点列表 ===
                List<SpawnArea> ctSpawnAreas;
            }
            /**
             * 返回所有可能与给定射线相交的线段。
             * @param returnLines 用于收集结果的列表（递归使用）
             * @param p1 射线起点
             * @param p2 射线终点
             * @return 包含潜在碰撞线段的列表
             */
            public List<Line2D.Double> queryRay(List<Line2D.Double> returnLines, Point2D p1, Point2D p2) { // <-- 已修改
                // 1. 剪枝：检查射线是否与此节点的边界相交
                if (!bounds.intersectsLine(p1.getX(), p1.getY(), p2.getX(), p2.getY())) {
                    return returnLines;
                }

                // 2. 添加此节点的线段
                returnLines.addAll(lines); // <-- 已修改

                // 3. 递归检查子节点
                if (children[0] != null) {
                    children[0].queryRay(returnLines, p1, p2);
                    children[1].queryRay(returnLines, p1, p2);
                    children[2].queryRay(returnLines, p1, p2);
                    children[3].queryRay(returnLines, p1, p2);
                }

                return returnLines;
            }
        }
    }
}