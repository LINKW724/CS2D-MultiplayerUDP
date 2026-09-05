package cs2d.AIControl.BW.MapViz; // (使用您自己的包路径)

import javax.swing.*;
import java.awt.*;
import java.awt.event.ActionListener; // [新] 导入 ActionListener
import java.awt.geom.*;
import java.io.FileWriter;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.*;
import java.util.List;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;


/**
 * 这是一个独立的 Java Swing 可视化工具。
 * 添加了 GUI 开关来分别控制 6 种路径的显示和颜色。
 */
public class DynamicPathfinderVisualizer {

    // --- 配置 ---
    // [修改] 路径数量配置
    private static final int NUM_TDM_PATHS = 5; // T<->CT 路径数量
    private static final int NUM_DEMO_PATHS = 2; // Spawn<->Bombsite 路径数量

    public static final double GRID_SPACING = 12.0;
    private static final double SPAWN_PROTECTION_RADIUS = 500.0;
    public static final double PENAITY = 500.0;
    public static final double DECOY_FACTOR = 0.05 ;
    public static final double OBSTACLE_REWARD_FACTOR = 0.45;

    // 地图!
    public static final String PATHH = "O:\\\\java\\\\games\\\\CS2D-MultiplayerUDP\\\\maps\\\\d3_2.5x无粗.json";

    // 预设路径的输出文件夹
    public static final String PRE_PATH_OUTPUT_DIR = "O:\\java\\games\\CS2D-MultiplayerUDP\\maps\\prePath";

    public static final int WINDOW_WIDTH = 1280;
    public static final int WINDOW_HEIGHT = 720; //稍微增加高度以容纳控制面板

    // 出生点默认位置
    private static final Point2D.Double DEFAULT_T_SPAWN_POS = new Point2D.Double(3072, 850);
    private static final Point2D.Double DEFAULT_CT_SPAWN_POS = new Point2D.Double(282, 628);

    // [新] 包点默认位置 (您需要为您的地图调整这些值!)
    private static final Point2D.Double DEFAULT_BOMBSITE_A_POS = new Point2D.Double(1230, 2220); // 示例 A点
    private static final Point2D.Double DEFAULT_BOMBSITE_B_POS = new Point2D.Double(3150, 2730); // 示例 B点


    // [新] 辅助枚举，用于区分路径类型
    public enum PathType { TDM_T_CT, TDM_CT_T, DEMO_CT_A, DEMO_CT_B, DEMO_T_A, DEMO_T_B }

    // [新] 辅助记录，用于将路径与其类型关联起来
    record PathInfo(List<Waypoint> path, PathType type) {}


    // ====================================================================
    // === JSON POJO (Plain Old Java Objects) ===
    // ====================================================================

    /** 匹配 JSON 的根结构
     * [修改] bombSitesA 和 bombSitesB 改为单个对象
     */
    static class MapJsonData {
        double width;
        double height;
        List<ObstacleJson> obstacles;
        List<SpawnAreaJson> ctSpawnAreas; // 保持 List
        List<SpawnAreaJson> tSpawnAreas;  // 保持 List
        List<WaypointJson> waypoints;
        BombSiteJson bombSiteA; // [修改] A 包点 - 单个对象
        BombSiteJson bombSiteB; // [修改] B 包点 - 单个对象
    }

    /** 匹配 "obstacles" 数组 */
    static class ObstacleJson {
        String type;
        double x, y, width, height;
        double[] xPoints;
        double[] yPoints;
    }

    /** 匹配重生区域 */
    static class SpawnAreaJson {
        double x, y, width, height;
    }

    /** [新] 匹配包点区域 */
    static class BombSiteJson {
        double x, y, width, height;
    }

    /** 匹配 "waypoints" 数组 */
    static class WaypointJson {
        PositionJson position;
        List<Integer> neighborIndices;
        List<Double> neighborCosts;
    }

    /** 匹配 "position" 对象 */
    static class PositionJson {
        double x, y;
    }

    // [修改] 用于导出 *所有* 预设路径的 POJO
    static class PresetPathCollection {
        // TDM 路径
        List<List<Point2D.Double>> presetPathsTtoCT;
        List<List<Point2D.Double>> presetPathsCTtoT;
        // Demolition 路径
        List<List<Point2D.Double>> presetPathsCTtoA; // [新]
        List<List<Point2D.Double>> presetPathsCTtoB; // [新]
        List<List<Point2D.Double>> presetPathsTtoA;  // [新]
        List<List<Point2D.Double>> presetPathsTtoB;  // [新]

        public PresetPathCollection() {
            this.presetPathsTtoCT = new ArrayList<>();
            this.presetPathsCTtoT = new ArrayList<>();
            this.presetPathsCTtoA = new ArrayList<>(); // [新]
            this.presetPathsCTtoB = new ArrayList<>(); // [新]
            this.presetPathsTtoA = new ArrayList<>();  // [新]
            this.presetPathsTtoB = new ArrayList<>();  // [新]
        }
    }

    // ====================================================================
    // === (主程序入口) ===
    // ====================================================================

    public static void main(String[] args) {

        String mapToLoad = PATHH;

        // 1. 从文件加载地图数据
        MapJsonData mapJson;
        List<Shape> obstacles;
        List<Waypoint> waypoints;

        String mapName = "unknown_map";
        try {
            Path p = Paths.get(mapToLoad);
            String fileName = p.getFileName().toString();
            if (fileName.endsWith(".json")) {
                mapName = fileName.substring(0, fileName.length() - ".json".length());
            }
        } catch (Exception e) { System.err.println("无法从路径解析地图名: " + mapToLoad); }
        final String finalMapName = mapName;

        try {
            Gson gson = new Gson();
            String jsonContent = Files.readString(Paths.get(mapToLoad));
            mapJson = gson.fromJson(jsonContent, MapJsonData.class); // Gson 会自动处理 bombSiteA/B 现在是对象
            if (mapJson == null) throw new IOException("解析地图失败 (mapJson 为 null)。");
            obstacles = convertObstacles(mapJson);
        } catch (IOException e) {
            System.err.println("错误: 无法读取或解析地图文件: " + mapToLoad);
            e.printStackTrace();
            mapJson = new MapJsonData(); // 创建空对象以避免 NullPointerException
            mapJson.width = 3200; mapJson.height = 1800; obstacles = new ArrayList<>();
        }

        final MapData mapData = new MapData(mapJson.width, mapJson.height, obstacles);

        // 2. 决定航点图的来源 (不变)
        if (mapJson.waypoints != null && !mapJson.waypoints.isEmpty()) {
            System.out.println("信息: 找到 " + mapJson.waypoints.size() + " 个预计算航点。使用方案一。");
            waypoints = convertWaypoints(mapJson);
        } else {
            System.out.println("警告: JSON中未找到航点。正在动态生成寻路网格 (方案二)...");
            long startTime = System.currentTimeMillis();
            waypoints = generateGridWaypoints(mapData.MAP_WIDTH, mapData.MAP_HEIGHT, mapData.obstacles, GRID_SPACING);
            long endTime = System.currentTimeMillis();
            System.out.println("信息: 动态生成 " + waypoints.size() + " 个网格节点。耗时: " + (endTime - startTime) + " ms。");
        }
        mapData.setWaypoints(waypoints);


        // 3. 动态确定 T/CT 出生点 (使用 findCenter 处理列表)
        final Point2D.Double T_SPAWN_POS = findCenterFromList(mapJson.tSpawnAreas, DEFAULT_T_SPAWN_POS, "T-Spawn");
        final Point2D.Double CT_SPAWN_POS = findCenterFromList(mapJson.ctSpawnAreas, DEFAULT_CT_SPAWN_POS, "CT-Spawn");

        // [修改] 4. 动态确定 A/B 包点位置 (直接处理单个对象)
        final Point2D.Double BOMBSITE_A_POS;
        if (mapJson.bombSiteA != null) {
            BOMBSITE_A_POS = new Point2D.Double(mapJson.bombSiteA.x + mapJson.bombSiteA.width / 2, mapJson.bombSiteA.y + mapJson.bombSiteA.height / 2);
            System.out.println("信息: 从 JSON 加载 Bombsite A 位置: " + BOMBSITE_A_POS);
        } else {
            BOMBSITE_A_POS = DEFAULT_BOMBSITE_A_POS;
            System.out.println("警告: JSON 中未找到 'bombSiteA' 对象。使用默认位置: " + BOMBSITE_A_POS);
        }

        final Point2D.Double BOMBSITE_B_POS;
        if (mapJson.bombSiteB != null) {
            BOMBSITE_B_POS = new Point2D.Double(mapJson.bombSiteB.x + mapJson.bombSiteB.width / 2, mapJson.bombSiteB.y + mapJson.bombSiteB.height / 2);
            System.out.println("信息: 从 JSON 加载 Bombsite B 位置: " + BOMBSITE_B_POS);
        } else {
            BOMBSITE_B_POS = DEFAULT_BOMBSITE_B_POS;
            System.out.println("警告: JSON 中未找到 'bombSiteB' 对象。使用默认位置: " + BOMBSITE_B_POS);
        }


        // 在 Swing 事件调度线程上创建和显示 GUI
        SwingUtilities.invokeLater(() -> {
            JFrame frame = new JFrame("动态寻路: " + mapToLoad);
            frame.setDefaultCloseOperation(JFrame.EXIT_ON_CLOSE);
            frame.setLayout(new BorderLayout()); // [新] 设置为 BorderLayout

            // 5. 创建寻路器 (不变)
            WaypointPathfinder pathfinder = new WaypointPathfinder(
                    mapData.waypoints,
                    SPAWN_PROTECTION_RADIUS,
                    mapData.MAP_WIDTH,
                    mapData.MAP_HEIGHT,
                    mapData.obstacles
            );

            // 6. 找到所有关键点的最近航点
            Waypoint tSpawnNode = pathfinder.findNearestWaypoint(T_SPAWN_POS);
            Waypoint ctSpawnNode = pathfinder.findNearestWaypoint(CT_SPAWN_POS);
            Waypoint bombsiteANode = pathfinder.findNearestWaypoint(BOMBSITE_A_POS);
            Waypoint bombsiteBNode = pathfinder.findNearestWaypoint(BOMBSITE_B_POS);

            if (tSpawnNode == null || ctSpawnNode == null || bombsiteANode == null || bombsiteBNode == null) {
                // ... (错误处理不变) ...
                System.err.println("!! 致命错误: 无法找到 出生点 或 包点 的航点!");
                MapPanel mapPanel = new MapPanel(mapData, new ArrayList<>(), null); // 传递 null
                frame.add(mapPanel); frame.setSize(WINDOW_WIDTH, WINDOW_HEIGHT); frame.setLocationRelativeTo(null); frame.setVisible(true);
                return;
            }

            System.out.println("T-Spawn -> 最近航点 #" + mapData.waypoints.indexOf(tSpawnNode) + " " + tSpawnNode.position);
            System.out.println("CT-Spawn -> 最近航点 #" + mapData.waypoints.indexOf(ctSpawnNode) + " " + ctSpawnNode.position);
            System.out.println("Bombsite A -> 最近航点 #" + mapData.waypoints.indexOf(bombsiteANode) + " " + bombsiteANode.position);
            System.out.println("Bombsite B -> 最近航点 #" + mapData.waypoints.indexOf(bombsiteBNode) + " " + bombsiteBNode.position);

            // 7. [核心] 计算所有路径 (不变)
            System.out.println("\n--- 计算 TDM 路径 ---");
            List<List<Waypoint>> paths_T_to_CT = pathfinder.findAlternativePaths(tSpawnNode, ctSpawnNode, NUM_TDM_PATHS, PENAITY);
            System.out.println("计算 T->CT 完成: " + paths_T_to_CT.size() + " 条");
            List<List<Waypoint>> paths_CT_to_T = pathfinder.findAlternativePaths(ctSpawnNode, tSpawnNode, NUM_TDM_PATHS, PENAITY);
            System.out.println("计算 CT->T 完成: " + paths_CT_to_T.size() + " 条");

            System.out.println("\n--- 计算 Demolition 路径 ---");
            List<List<Waypoint>> paths_CT_to_A = pathfinder.findAlternativePaths(ctSpawnNode, bombsiteANode, NUM_DEMO_PATHS, PENAITY);
            System.out.println("计算 CT->A 完成: " + paths_CT_to_A.size() + " 条");
            List<List<Waypoint>> paths_CT_to_B = pathfinder.findAlternativePaths(ctSpawnNode, bombsiteBNode, NUM_DEMO_PATHS, PENAITY);
            System.out.println("计算 CT->B 完成: " + paths_CT_to_B.size() + " 条");
            List<List<Waypoint>> paths_T_to_A = pathfinder.findAlternativePaths(tSpawnNode, bombsiteANode, NUM_DEMO_PATHS + 1, PENAITY);
            System.out.println("计算 T->A 完成: " + paths_T_to_A.size() + " 条");
            List<List<Waypoint>> paths_T_to_B = pathfinder.findAlternativePaths(tSpawnNode, bombsiteBNode, NUM_DEMO_PATHS + 1, PENAITY);
            System.out.println("计算 T->B 完成: " + paths_T_to_B.size() + " 条");


            // 8. 组合所有路径用于绘图 (不变)
            List<PathInfo> allPathsToDraw = new ArrayList<>();
            paths_T_to_CT.forEach(p -> allPathsToDraw.add(new PathInfo(p, PathType.TDM_T_CT)));
            paths_CT_to_T.forEach(p -> allPathsToDraw.add(new PathInfo(p, PathType.TDM_CT_T)));
            paths_CT_to_A.forEach(p -> allPathsToDraw.add(new PathInfo(p, PathType.DEMO_CT_A)));
            paths_CT_to_B.forEach(p -> allPathsToDraw.add(new PathInfo(p, PathType.DEMO_CT_B)));
            paths_T_to_A.forEach(p -> allPathsToDraw.add(new PathInfo(p, PathType.DEMO_T_A)));
            paths_T_to_B.forEach(p -> allPathsToDraw.add(new PathInfo(p, PathType.DEMO_T_B)));

            // [新] 9. 创建控制面板 (复选框)
            JPanel controlPanel = new JPanel(new FlowLayout(FlowLayout.CENTER, 15, 5));
            controlPanel.setBackground(Color.LIGHT_GRAY);

            // 为复选框创建一个 map 来存储它们
            Map<PathType, JCheckBox> checkBoxes = new HashMap<>();

            // TDM 路径
            checkBoxes.put(PathType.TDM_T_CT, new JCheckBox("T->CT (Red)", true));
            checkBoxes.put(PathType.TDM_CT_T, new JCheckBox("CT->T (Blue)", true));
            // CT 路径
            checkBoxes.put(PathType.DEMO_CT_A, new JCheckBox("CT->A (Green)", true));
            checkBoxes.put(PathType.DEMO_CT_B, new JCheckBox("CT->B (Green)", true));
            // T 路径
            checkBoxes.put(PathType.DEMO_T_A, new JCheckBox("T->A (Orange)", true));
            checkBoxes.put(PathType.DEMO_T_B, new JCheckBox("T->B (Yellow)", true));

            // 添加到面板
            controlPanel.add(new JLabel("TDM:"));
            controlPanel.add(checkBoxes.get(PathType.TDM_T_CT));
            controlPanel.add(checkBoxes.get(PathType.TDM_CT_T));
            controlPanel.add(new JSeparator(SwingConstants.VERTICAL));
            controlPanel.add(new JLabel("CT Demo:"));
            controlPanel.add(checkBoxes.get(PathType.DEMO_CT_A));
            controlPanel.add(checkBoxes.get(PathType.DEMO_CT_B));
            controlPanel.add(new JSeparator(SwingConstants.VERTICAL));
            controlPanel.add(new JLabel("T Demo:"));
            controlPanel.add(checkBoxes.get(PathType.DEMO_T_A));
            controlPanel.add(checkBoxes.get(PathType.DEMO_T_B));


            // [修改] 10. 创建绘图面板，并传入复选框
            MapPanel mapPanel = new MapPanel(mapData, allPathsToDraw, checkBoxes);

            // [新] 11. 为所有复选框添加监听器，以便点击时重绘
            ActionListener repainter = e -> mapPanel.repaint();
            for (JCheckBox cb : checkBoxes.values()) {
                cb.addActionListener(repainter);
            }

            // [修改] 12. 将面板添加到 JFrame
            frame.add(mapPanel, BorderLayout.CENTER);
            frame.add(controlPanel, BorderLayout.SOUTH); // [修改] 将控制面板放到底部

            frame.setSize(WINDOW_WIDTH, WINDOW_HEIGHT); // 确保尺寸正确
            frame.setLocationRelativeTo(null); // 居中
            frame.setVisible(true);

            // ===============================================================
            // [修改] 13. 导出 *所有方向* 的预设路径 JSON
            // ===============================================================
            try {
                System.out.println("\n[新] 正在导出所有预设寻路JSON...");
                exportPrePathJson(
                        finalMapName,
                        paths_T_to_CT, paths_CT_to_T,
                        paths_CT_to_A, paths_CT_to_B,
                        paths_T_to_A, paths_T_to_B
                );
            } catch (Exception e) {
                System.err.println("!! 导出预设路径失败 !!");
                e.printStackTrace();
            }
        });
    }

    /**
     * [修改] 重命名为 findCenterFromList，只处理列表类型的区域 (出生点)
     * @param areas 区域列表 (SpawnAreaJson)
     * @param defaultPos 默认位置
     * @param areaName 区域名称 (用于日志)
     * @return 中心点
     */
    private static Point2D.Double findCenterFromList(List<SpawnAreaJson> areas, Point2D.Double defaultPos, String areaName) {
        if (areas != null && !areas.isEmpty()) {
            SpawnAreaJson area = areas.get(0); // 直接获取 SpawnAreaJson
            if (area != null) {
                Point2D.Double center = new Point2D.Double(area.x + area.width / 2, area.y + area.height / 2);
                System.out.println("信息: 从 JSON 加载 " + areaName + " 位置: " + center);
                return center;
            } else {
                System.out.println("警告: " + areaName + " 列表中的第一个元素为 null。使用默认位置。");
                return defaultPos;
            }
        } else {
            System.out.println("警告: JSON 中未找到 '" + areaName + "' 列表。使用默认位置: " + defaultPos);
            return defaultPos;
        }
    }


    /**
     * [新] 辅助方法：将一条完整的路径降采样为 N 个关键点
     * @param wpPath 完整的 Waypoint 路径
     * @param maxPoints 目标点数 (例如 10)
     * @return 一个简化的 Point2D.Double 列表
     */
    private static List<Point2D.Double> simplifyPath(List<Waypoint> wpPath, int maxPoints) {
        List<Point2D.Double> simplifiedPath = new ArrayList<>();
        int pathSize = wpPath.size();

        if (pathSize == 0) return simplifiedPath; // return empty list

        if (pathSize <= maxPoints) {
            // 路径点数很少，直接添加所有点
            for (Waypoint wp : wpPath) {
                simplifiedPath.add(wp.position);
            }
        } else {
            // [核心] 路径点数 > maxPoints, 我们需要均匀采样
            double step = (double)(pathSize - 1) / (maxPoints - 1);
            for (int i = 0; i < maxPoints; i++) {
                int index = (int)Math.round(i * step);
                index = Math.max(0, Math.min(index, pathSize - 1));
                simplifiedPath.add(wpPath.get(index).position);
            }
            // 确保最后一个点总是被包含
            Point2D.Double lastAddedPoint = simplifiedPath.get(simplifiedPath.size() - 1);
            Point2D.Double actualLastPoint = wpPath.get(pathSize - 1).position;
            if (!lastAddedPoint.equals(actualLastPoint)) {
                simplifiedPath.set(simplifiedPath.size() - 1, actualLastPoint);
            }
        }
        return simplifiedPath;
    }

    /**
     * [修改] 导出 *所有* 预设路径
     * @param mapName 地图名
     * @param pathsTtoCT T->CT 路径
     * @param pathsCTtoT CT->T 路径
     * @param pathsCTtoA CT->A 路径
     * @param pathsCTtoB CT->B 路径
     * @param pathsTtoA T->A 路径
     * @param pathsTtoB T->B 路径
     */
    private static void exportPrePathJson(String mapName,
                                          List<List<Waypoint>> pathsTtoCT, List<List<Waypoint>> pathsCTtoT,
                                          List<List<Waypoint>> pathsCTtoA, List<List<Waypoint>> pathsCTtoB,
                                          List<List<Waypoint>> pathsTtoA, List<List<Waypoint>> pathsTtoB) throws IOException {

        final int MAX_POINTS_PER_PATH = 10; // 降采样点数

        // 1. 创建JSON的根对象
        PresetPathCollection data = new PresetPathCollection();

        // 2. 转换并降采样所有路径
        if (pathsTtoCT != null) pathsTtoCT.forEach(p -> data.presetPathsTtoCT.add(simplifyPath(p, MAX_POINTS_PER_PATH)));
        if (pathsCTtoT != null) pathsCTtoT.forEach(p -> data.presetPathsCTtoT.add(simplifyPath(p, MAX_POINTS_PER_PATH)));
        if (pathsCTtoA != null) pathsCTtoA.forEach(p -> data.presetPathsCTtoA.add(simplifyPath(p, MAX_POINTS_PER_PATH)));
        if (pathsCTtoB != null) pathsCTtoB.forEach(p -> data.presetPathsCTtoB.add(simplifyPath(p, MAX_POINTS_PER_PATH)));
        if (pathsTtoA != null) pathsTtoA.forEach(p -> data.presetPathsTtoA.add(simplifyPath(p, MAX_POINTS_PER_PATH)));
        if (pathsTtoB != null) pathsTtoB.forEach(p -> data.presetPathsTtoB.add(simplifyPath(p, MAX_POINTS_PER_PATH)));

        // 3. 确保文件夹存在
        Path outputDir = Paths.get(PRE_PATH_OUTPUT_DIR);
        Files.createDirectories(outputDir);

        // 4. 构造输出路径
        String outputFileName = mapName + "_prePath.json";
        Path outputPath = outputDir.resolve(outputFileName);

        // 5. 写入 JSON
        Gson gson = new GsonBuilder().setPrettyPrinting().create();
        try (FileWriter writer = new FileWriter(outputPath.toFile())) {
            gson.toJson(data, writer);
        }

        System.out.println("信息: 成功导出所有预设路径到: " + outputPath);
        System.out.println("  - TDM T->CT: " + data.presetPathsTtoCT.size() + " 条");
        System.out.println("  - TDM CT->T: " + data.presetPathsCTtoT.size() + " 条");
        System.out.println("  - Demo CT->A: " + data.presetPathsCTtoA.size() + " 条");
        System.out.println("  - Demo CT->B: " + data.presetPathsCTtoB.size() + " 条");
        System.out.println("  - Demo T->A: " + data.presetPathsTtoA.size() + " 条");
        System.out.println("  - Demo T->B: " + data.presetPathsTtoB.size() + " 条");
    }

    /**
     * 辅助函数：将 JSON 障碍物转换为 AWT Shape
     */
    private static List<Shape> convertObstacles(MapJsonData mapJson) {
        List<Shape> obstacles = new ArrayList<>();
        if (mapJson.obstacles == null) return obstacles;

        for (ObstacleJson obs : mapJson.obstacles) {
            if (obs.type == null) continue;
            switch (obs.type) {
                case "RECTANGLE":
                    obstacles.add(new Rectangle2D.Double(obs.x, obs.y, obs.width, obs.height));
                    break;
                case "ELLIPSE":
                    obstacles.add(new Ellipse2D.Double(obs.x, obs.y, obs.width, obs.height));
                    break;
                case "POLYGON":
                    if (obs.xPoints != null && obs.yPoints != null && obs.xPoints.length == obs.yPoints.length) {
                        Polygon poly = new Polygon();
                        for (int i = 0; i < obs.xPoints.length; i++) {
                            poly.addPoint((int) obs.xPoints[i], (int) obs.yPoints[i]);
                        }
                        obstacles.add(poly);
                    }
                    break;
            }
        }
        // 添加地图边界
        obstacles.add(new Rectangle2D.Double(0, 0, mapJson.width, 1)); // 上
        obstacles.add(new Rectangle2D.Double(0, mapJson.height-1, mapJson.width, 1)); // 下
        obstacles.add(new Rectangle2D.Double(0, 0, 1, mapJson.height)); // 左
        obstacles.add(new Rectangle2D.Double(mapJson.width-1, 0, 1, mapJson.height)); // 右
        return obstacles;
    }

    /**
     * 辅助函数：将 JSON 航点转换为 Waypoint 对象
     */
    private static List<Waypoint> convertWaypoints(MapJsonData mapJson) {
        List<Waypoint> waypoints = new ArrayList<>();
        if (mapJson.waypoints == null) return waypoints;
        for (WaypointJson wpj : mapJson.waypoints) {
            waypoints.add(new Waypoint(
                    new Point2D.Double(wpj.position.x, wpj.position.y),
                    wpj.neighborIndices,
                    wpj.neighborCosts
            ));
        }
        return waypoints;
    }

    // ====================================================================
    // === [新] 方案二：动态网格生成 ===
    // ====================================================================

    /**
     * [新] 动态生成一个寻路网格图
     * @param mapWidth 地图宽度
     * @param mapHeight 地图高度
     * @param obstacles 障碍物列表
     * @param gridSpacing 网格间距
     * @return 一个 Waypoint 列表，已连接好邻居
     */
    private static List<Waypoint> generateGridWaypoints(double mapWidth, double mapHeight, List<Shape> obstacles, double gridSpacing) {

        int gridWidth = (int)(mapWidth / gridSpacing);
        int gridHeight = (int)(mapHeight / gridSpacing);

        // 1. 创建所有网格节点 (Waypoint)
        Waypoint[][] grid = new Waypoint[gridWidth][gridHeight];
        List<Waypoint> allWaypointsList = new ArrayList<>();

        for (int x = 0; x < gridWidth; x++) {
            for (int y = 0; y < gridHeight; y++) {
                Point2D.Double pos = new Point2D.Double(x * gridSpacing + (gridSpacing / 2),
                        y * gridSpacing + (gridSpacing / 2));

                Rectangle2D.Double gridCell = new Rectangle2D.Double(x * gridSpacing, y * gridSpacing, gridSpacing, gridSpacing);

                boolean isBlocked = false;
                for (Shape obs : obstacles) {
                    if (obs.intersects(gridCell)) {
                        isBlocked = true;
                        break;
                    }
                }

                if (!isBlocked) {
                    Waypoint wp = new Waypoint(pos, new ArrayList<>(), new ArrayList<>());
                    grid[x][y] = wp;
                    allWaypointsList.add(wp);
                }
            }
        }

        // 2. [关键] 链接邻居并进行视线检查 (Line-of-Sight Check)
        int[][] directions = {
                {0, 1}, {1, 0}, {0, -1}, {-1, 0}, // 直线
                {1, 1}, {1, -1}, {-1, -1}, {-1, 1} // 对角线
        };

        for (int x = 0; x < gridWidth; x++) {
            for (int y = 0; y < gridHeight; y++) {
                Waypoint current = grid[x][y];
                if (current == null) continue; // 这是障碍物内的点

                for (int[] dir : directions) {
                    int nx = x + dir[0];
                    int ny = y + dir[1];

                    if (nx >= 0 && nx < gridWidth && ny >= 0 && ny < gridHeight) {
                        Waypoint neighbor = grid[nx][ny];
                        if (neighbor == null) continue; // 邻居是障碍物

                        // [核心] 检查视线
                        if (hasLineOfSight(current.position, neighbor.position, obstacles)) {
                            double cost = current.position.distance(neighbor.position);
                            current.tempNeighbors.add(neighbor);
                            current.neighborCosts.add(cost);
                        }
                    }
                }
            }
        }

        // 3. [新] 转换: 将 tempNeighbors 转换为 neighborIndices
        for (Waypoint wp : allWaypointsList) {
            for (Waypoint neighbor : wp.tempNeighbors) {
                int neighborIndex = allWaypointsList.indexOf(neighbor);
                if (neighborIndex != -1) {
                    wp.neighborIndices.add(neighborIndex);
                }
            }
            wp.tempNeighbors.clear(); // 清理内存
        }

        return allWaypointsList;
    }

    /**
     * [新] 检查两点之间是否有障碍物 (视线检查)
     * @param p1 点1
     * @param p2 点2
     * @param obstacles 障碍物列表
     * @return 如果视线清晰，返回 true
     */
    private static boolean hasLineOfSight(Point2D.Double p1, Point2D.Double p2, List<Shape> obstacles) {
        Line2D.Double line = new Line2D.Double(p1, p2);

        for (Shape obs : obstacles) {
            if (obs.intersects(line.getBounds2D())) {
                if (line.intersects(obs.getBounds2D())) {
                    if (obs instanceof Rectangle2D) {
                        if (line.intersects((Rectangle2D) obs)) {
                            return false; // 视线被矩形阻挡
                        }
                    } else {
                        return false; // 视线被其他形状阻挡 (近似)
                    }
                }
            }
        }
        return true; // 视线清晰
    }

} // (DynamicPathfinderVisualizer 类结束)


/**
 * [新] 数据容器，用于保存地图数据
 * [!!!] 这是您 1000 多行代码的一部分
 */
class MapData {
    public final double MAP_WIDTH;
    public final double MAP_HEIGHT;
    public final List<Shape> obstacles;
    public List<Waypoint> waypoints;

    public MapData(double width, double height, List<Shape> obstacles) {
        this.MAP_WIDTH = width;
        this.MAP_HEIGHT = height;
        this.obstacles = obstacles;
        this.waypoints = new ArrayList<>(); // 默认为空
    }

    public void setWaypoints(List<Waypoint> waypoints) {
        this.waypoints = waypoints;
    }
}


/**
 * 自定义 JPanel，用于绘制地图、航点和路径
 * [修改] 使用 PathInfo 来区分路径并应用不同颜色
 * [!!!] 这是您 1000 多行代码的一部分
 */
class MapPanel extends JPanel {
    private final MapData mapData;
    // [修改] 存储 PathInfo 列表
    private List<DynamicPathfinderVisualizer.PathInfo> pathsToDraw;
    private double scale = 1.0;

    // [新] 存储复选框的引用
    private Map<DynamicPathfinderVisualizer.PathType, JCheckBox> checkBoxes;

    // [修改] 定义颜色
    private static final Color TDM_T_CT_COLOR = new Color(255, 0, 0, 180); // 红色
    private static final Color TDM_CT_T_COLOR = new Color(0, 0, 255, 180); // [新] 蓝色
    private static final Color CT_DEMO_COLOR = new Color(0, 200, 0, 180); // 绿色 (用于 CT->A 和 CT->B)
    private static final Color T_DEMO_A_COLOR = new Color(255, 140, 0, 180); // [新] 橙色
    private static final Color T_DEMO_B_COLOR = new Color(255, 200, 0, 180); // [新] 黄色

    // [修改] 简化笔触 - 所有预设路径用同一种粗细
    private static final Stroke PRESET_PATH_STROKE = new BasicStroke(2.5f);

    // [修改] 构造函数，接收复选框 Map
    public MapPanel(MapData mapData, List<DynamicPathfinderVisualizer.PathInfo> pathsToDraw, Map<DynamicPathfinderVisualizer.PathType, JCheckBox> checkBoxes) {
        this.mapData = mapData;
        this.pathsToDraw = (pathsToDraw != null) ? pathsToDraw : new ArrayList<>();
        this.checkBoxes = checkBoxes; // 存储引用
        this.setBackground(Color.DARK_GRAY);
    }

    @Override
    protected void paintComponent(Graphics g) {
        super.paintComponent(g);
        Graphics2D g2d = (Graphics2D) g;

        double scaleX = (double) getWidth() / mapData.MAP_WIDTH;
        double scaleY = (double) getHeight() / mapData.MAP_HEIGHT;
        this.scale = Math.min(scaleX, scaleY) * 0.95;

        g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

        // --- 设置坐标系 ---
        g2d.translate(getWidth() / 2, getHeight() / 2);
        g2d.scale(scale, scale);
        g2d.translate(-mapData.MAP_WIDTH / 2, -mapData.MAP_HEIGHT / 2);

        // --- 1. 绘制所有障碍物 ---
        g2d.setColor(Color.BLACK);
        for (Shape obstacle : mapData.obstacles) {
            g2d.fill(obstacle);
        }

        // --- 2. 绘制所有航点连接 (邻居关系) ---
        g2d.setColor(new Color(100, 100, 100, 50));
        g2d.setStroke(new BasicStroke(0.5f));

        if (mapData.waypoints == null) return;
        boolean isGrid = mapData.waypoints.size() > 2000;
        for (int i = 0; i < mapData.waypoints.size(); i++) {
            Waypoint wp = mapData.waypoints.get(i);
            for (int neighborIndex : wp.neighborIndices) {
                if (neighborIndex > i && neighborIndex < mapData.waypoints.size()) {
                    Waypoint neighbor = mapData.waypoints.get(neighborIndex);
                    g2d.drawLine(
                            (int) wp.position.x, (int) wp.position.y,
                            (int) neighbor.position.x, (int) neighbor.position.y
                    );
                }
            }
        }

        // --- 3. 绘制所有航点 ---
        if (!isGrid) {
            g2d.setColor(Color.CYAN);
            for (Waypoint wp : mapData.waypoints) {
                g2d.fillOval((int) wp.position.x - 5, (int) wp.position.y - 5, 10, 10);
            }
        }


        // --- 4. [修改] 绘制所有类型的路径 (带开关检查) ---
        g2d.setStroke(PRESET_PATH_STROKE); // 使用统一笔触

        if (pathsToDraw == null || checkBoxes == null) return; // 安全检查

        for (DynamicPathfinderVisualizer.PathInfo pathInfo : pathsToDraw) {
            List<Waypoint> path = pathInfo.path();
            DynamicPathfinderVisualizer.PathType type = pathInfo.type();

            // [核心] 检查对应的复选框是否被选中
            if (checkBoxes.get(type) == null || !checkBoxes.get(type).isSelected()) {
                continue; // 如果复选框不存在或未被选中，则跳过绘制
            }

            // 根据路径类型设置颜色
            switch (type) {
                case TDM_T_CT:
                    g2d.setColor(TDM_T_CT_COLOR); // 红
                    break;
                case TDM_CT_T:
                    g2d.setColor(TDM_CT_T_COLOR); // 蓝
                    break;
                case DEMO_CT_A:
                case DEMO_CT_B:
                    g2d.setColor(CT_DEMO_COLOR); // 绿
                    break;
                case DEMO_T_A:
                    g2d.setColor(T_DEMO_A_COLOR); // 橙
                    break;
                case DEMO_T_B:
                    g2d.setColor(T_DEMO_B_COLOR); // 黄
                    break;
            }

            // 绘制路径线段
            for (int j = 0; j < path.size() - 1; j++) {
                Waypoint a = path.get(j);
                Waypoint b = path.get(j + 1);
                g2d.drawLine((int) a.position.x, (int) a.position.y, (int) b.position.x, (int) b.position.y);
            }
        }
    }
}


/**
 * 航点 (Waypoint) 数据类
 * [!!!] 这是您 1000 多行代码的一部分
 */
class Waypoint implements Comparable<Waypoint> {
    Point2D.Double position;
    List<Integer> neighborIndices;
    List<Double> neighborCosts;
    double gCost = Double.POSITIVE_INFINITY;
    double hCost = 0;
    double currentPenaltyCost = 0;
    Waypoint parent = null;

    List<Waypoint> tempNeighbors = new ArrayList<>();

    // [新] 连续惩罚计数器
    int consecutivePenaltySteps = 0;

    public Waypoint(Point2D.Double position, List<Integer> neighborIndices, List<Double> neighborCosts) {
        this.position = position;
        this.neighborIndices = neighborIndices;
        this.neighborCosts = neighborCosts;
    }
    public double fCost() { return gCost + hCost; }
    public void reset() {
        gCost = Double.POSITIVE_INFINITY;
        hCost = 0;
        parent = null;
        consecutivePenaltySteps = 0; // [新] 重置计数器
    }
    @Override
    public int compareTo(Waypoint other) { return Double.compare(this.fCost(), other.fCost()); }
    @Override
    public String toString() { return "WP" + position; }
}

/**
 * [已更新] 航点图寻路器
 * [!!!] 这是您 1000 多行代码的一部分
 */
class WaypointPathfinder {

    private final List<Waypoint> allWaypoints;
    private final double spawnProtectionRadius;
    private final double influenceRadius;
    private final List<Shape> obstacles; // [新] 存储障碍物列表

    public WaypointPathfinder(List<Waypoint> allWaypoints, double spawnProtectionRadius, double mapWidth, double mapHeight, List<Shape> obstacles) {
        this.allWaypoints = allWaypoints;
        this.spawnProtectionRadius = spawnProtectionRadius;

        // [修改] 使用 DECOY_FACTOR
        this.influenceRadius = Math.min(mapWidth, mapHeight) * DynamicPathfinderVisualizer.DECOY_FACTOR;
        // System.out.println("信息: 惩罚影响半径设置为: " + this.influenceRadius); // 日志可以注释掉

        this.obstacles = obstacles; // [新]
    }

    public Waypoint findNearestWaypoint(Point2D.Double worldPos) {
        Waypoint nearest = null;
        double minDistSq = Double.POSITIVE_INFINITY;
        if (allWaypoints == null) return null;

        for (Waypoint wp : allWaypoints) {
            double distSq = wp.position.distanceSq(worldPos);
            if (distSq < minDistSq) {
                minDistSq = distSq;
                nearest = wp;
            }
        }
        return nearest;
    }

    public List<List<Waypoint>> findAlternativePaths(Waypoint start, Waypoint end, int numPaths, double penalty) {
        resetWaypointCosts();
        List<List<Waypoint>> allPaths = new ArrayList<>();

        double radiusSq = spawnProtectionRadius * spawnProtectionRadius;

        // [新] 定义“高惩罚”的门槛 (你说的 80%)
        double penaltyThreshold = penalty * 0.8;

        for (int i = 0; i < numPaths; i++) {
            // [新] 传入惩罚门槛
            List<Waypoint> path = findPath(start, end, penaltyThreshold);
            if (path == null || path.isEmpty()) break;
            allPaths.add(path);

            // [核心] 施加惩罚 (包含扩散)
            for (Waypoint wp : path) {

                // [!] 这里的 start 和 end 是指传入的 start/end 节点
                // 这就是“重生点保护”的实现
                if (wp.position.distanceSq(start.position) < radiusSq ||
                        wp.position.distanceSq(end.position) < radiusSq) {
                    continue;
                }

                wp.currentPenaltyCost += penalty; // 1. 惩罚路径点

                // 2. [新] 惩罚影响范围内的所有点 (包含你的“障碍物奖励”)
                for (Waypoint otherWp : allWaypoints) {

                    if (path.contains(otherWp) || otherWp == start || otherWp == end) {
                        continue;
                    }

                    // [!] 保护双方重生点
                    if (otherWp.position.distanceSq(start.position) < radiusSq ||
                            otherWp.position.distanceSq(end.position) < radiusSq) {
                        continue;
                    }

                    double distance = wp.position.distance(otherWp.position);

                    if (distance < this.influenceRadius) {

                        // [核心] 指数衰减 (Exponential Decay)
                        double k = 2.0; // [修改] 从 5.0 降到 2.0。 衰减更慢 = 惩罚范围更广
                        double decay = Math.exp(-k * (distance / this.influenceRadius));
                        double scaledPenalty = penalty * decay;


                        // [新] 实现你的“障碍物奖励” (基于粗细/障碍物数量)
                        int separationScore = getObstacleSeparationScore(wp.position, otherWp.position); // [修改]
                        double finalPenalty;

                        if (separationScore == 0) {
                            // 视线清晰 (无障碍): 施加全额惩罚
                            finalPenalty = scaledPenalty;
                        } else if (separationScore <= 2) { // [修改] 被 1-2 条射线击中 (薄墙)
                            // [新] 视线被 1 个障碍物阻挡: 施加 50% 惩罚 (中等奖励)
                            finalPenalty = scaledPenalty * 0.5; // 你可以调整这个 0.5
                        } else { // [修改] 被 3+ 条射线击中 (厚障碍)
                            // [新] 视线被 2+ 个障碍物阻挡 (很厚): 施加最大奖励
                            finalPenalty = scaledPenalty * DynamicPathfinderVisualizer.OBSTACLE_REWARD_FACTOR; // (0.25)
                        }
                        // [新] 这里是关键：惩罚是累加的 (+=)
                        // 如果这个点在 路径A 和 路径B 的影响范围内，它会受到两次惩罚
                        otherWp.currentPenaltyCost += finalPenalty;
                    }
                }
            }
        }
        resetWaypointCosts(); // 清理
        return allPaths;
    }

    private void resetWaypointCosts() {
        if (allWaypoints == null) return;
        for (Waypoint wp : allWaypoints) {
            wp.reset();
            wp.currentPenaltyCost = 0;
        }
    }

    /**
     * [新] findPath 方法现在接受一个 penaltyThreshold
     * @param start 起点
     * @param end 终点
     * @param penaltyThreshold “高惩罚”的门槛
     * @return 路径
     */
    private List<Waypoint> findPath(Waypoint start, Waypoint end, double penaltyThreshold) {
        if (allWaypoints == null) return null;

        // [新] 重置时，连续步数也会被清零 (在 Waypoint.reset() 中实现)
        for (Waypoint wp : allWaypoints) wp.reset();

        PriorityQueue<Waypoint> openSet = new PriorityQueue<>();
        Set<Waypoint> closedSet = new HashSet<>();

        start.gCost = 0;
        start.hCost = start.position.distance(end.position);
        openSet.add(start);

        while (!openSet.isEmpty()) {
            Waypoint current = openSet.poll();
            if (current == end) return retracePath(start, end);
            closedSet.add(current);

            for (int i = 0; i < current.neighborIndices.size(); i++) {
                int neighborIndex = current.neighborIndices.get(i);
                if (neighborIndex >= allWaypoints.size()) continue;
                Waypoint neighbor = allWaypoints.get(neighborIndex);
                if (closedSet.contains(neighbor)) continue;

                double moveCost = current.neighborCosts.get(i);

                // [新] 连续惩罚逻辑
                int newConsecutiveSteps = 0;
                double bonusPenalty = 0; // 额外惩罚

                // 检查新节点是否在高惩罚区
                if (neighbor.currentPenaltyCost >= penaltyThreshold) {
                    // 检查 *当前* 节点是否 *也* 在高惩罚区
                    // (我们使用 current.consecutivePenaltySteps > 0 来判断)
                    if (current.consecutivePenaltySteps > 0) {
                        newConsecutiveSteps = current.consecutivePenaltySteps + 1;

                        // [核心] 施加一个指数增长的额外惩罚！
                        // 连续第1步: 0 (因为 current.consecutivePenaltySteps 是 0)
                        // 连续第2步: newConsecutiveSteps=2。 惩罚 = moveCost * 1.0
                        // 连续第3步: newConsecutiveSteps=3。 惩罚 = moveCost * 2.0
                        // 惩罚会越来越高
                        bonusPenalty = moveCost * (newConsecutiveSteps - 1);

                    } else {
                        // 这是第一步踏入高惩罚区
                        newConsecutiveSteps = 1;
                        bonusPenalty = 0; // 没有 *额外* 惩罚
                    }
                } else {
                    // 这一步不在高惩罚区，重置计数器
                    newConsecutiveSteps = 0;
                    bonusPenalty = 0;
                }

                // [新] 总成本
                double newGCost = current.gCost + moveCost + neighbor.currentPenaltyCost + bonusPenalty;

                if (newGCost < neighbor.gCost) {
                    neighbor.parent = current;
                    neighbor.gCost = newGCost; // [修复] 移除拼写错误 's'
                    neighbor.hCost = neighbor.position.distance(end.position);
                    // [新] 将连续步数状态存入节点
                    neighbor.consecutivePenaltySteps = newConsecutiveSteps;

                    openSet.remove(neighbor);
                    openSet.add(neighbor);
                }
            }
        }
        return null; // 找不到路径
    }

    private List<Waypoint> retracePath(Waypoint start, Waypoint end) {
        List<Waypoint> path = new ArrayList<>();
        Waypoint current = end;
        while (current != null) {
            path.add(current);
            current = current.parent;
        }
        Collections.reverse(path);
        return path;
    }

    /**
     * [新] 检查两点之间的“战术分离度”（障碍物粗细的近似值）
     * (这是对 countIntersections 的升级)
     * @param p1 点1
     * @param p2 点2
     * @return 一个分离度分数 (0 = 视线清晰, 3+ = 很厚)
     */
    private int getObstacleSeparationScore(Point2D.Double p1, Point2D.Double p2) {
        // [新] 我们将投射 3 条射线来近似“粗细”
        int totalIntersections = 0;
        double offsetAmount = 10.0; // 10 像素偏移

        // [新] 计算 p1 和 p2 之间的法线（垂直）向量
        double dx = p2.x - p1.x;
        double dy = p2.y - p1.y;
        double mag = Math.sqrt(dx*dx + dy*dy);

        if (mag < 1.0) { // 如果点太近，偏移没有意义
            return castRay(p1, p2); // 只投射中心射线
        }

        double norm_dx = dx / mag;
        double norm_dy = dy / mag;
        // 垂直向量
        double perp_dx = -norm_dy * offsetAmount;
        double perp_dy = norm_dx * offsetAmount;

        // [新] 射线 1: 中心
        totalIntersections += castRay(p1, p2);

        // [新] 射线 2: 偏移 1
        Point2D.Double p1_up = new Point2D.Double(p1.x + perp_dx, p1.y + perp_dy);
        Point2D.Double p2_up = new Point2D.Double(p2.x + perp_dx, p2.y + perp_dy);
        totalIntersections += castRay(p1_up, p2_up);

        // [新] 射线 3: 偏移 2
        Point2D.Double p1_down = new Point2D.Double(p1.x - perp_dx, p1.y - perp_dy);
        Point2D.Double p2_down = new Point2D.Double(p2.x - perp_dx, p2.y - perp_dy);
        totalIntersections += castRay(p1_down, p2_down);

        return totalIntersections;
    }

    /**
     * [新] 辅助方法：投射一条射线并计算障碍物交叉点
     * @param p1 射线起点
     * @param p2 射线终点
     * @return 穿过的障碍物数量
     */
    private int castRay(Point2D.Double p1, Point2D.Double p2) {
        // 检查 p1 或 p2 是否为 null 或包含 NaN/Infinity
        if (p1 == null || p2 == null ||
                Double.isNaN(p1.x) || Double.isNaN(p1.y) || Double.isInfinite(p1.x) || Double.isInfinite(p1.y) ||
                Double.isNaN(p2.x) || Double.isNaN(p2.y) || Double.isInfinite(p2.x) || Double.isInfinite(p2.y)) {
            return 0; // 无效输入
        }

        Line2D.Double line;
        try {
            line = new Line2D.Double(p1, p2);
        } catch (Exception e) {
            // 捕获潜在的内部错误
            return 0;
        }

        int count = 0;

        // [新] 使用 this.obstacles
        for (Shape obs : this.obstacles) {
            try {
                if (obs.intersects(line.getBounds2D())) {
                    if (line.intersects(obs.getBounds2D())) {
                        if (obs instanceof Rectangle2D) {
                            if (line.intersects((Rectangle2D) obs)) {
                                count++; // [新] 计数
                            }
                        } else {
                            count++; // [新] 计数
                        }
                    }
                }
            } catch (Exception e) {
                // 捕获与某些形状（如空多边形）相交时可能发生的内部 AWT 错误
                // 静默失败并继续
            }
        }
        return count; // [新] 返回总数
    }
} // <--- WaypointPathfinder 类的结束