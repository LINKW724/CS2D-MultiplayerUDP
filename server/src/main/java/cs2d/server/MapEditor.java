package cs2d.server;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.TypeAdapter;
import com.google.gson.stream.JsonReader;
import com.google.gson.stream.JsonToken;
import com.google.gson.stream.JsonWriter;

import javax.swing.*;
import java.awt.*;
import java.awt.event.*;
import java.awt.geom.*;
import java.io.File;
import java.io.FileReader;
import java.io.FileWriter;
import java.io.IOException;
import java.util.*;
import java.util.List;
import java.util.stream.Collectors;

/**
 * CS2D地图编辑器。
 * 负责提供可视化界面用于地图片段读取、障碍物解析（物理碰撞箱）功能。
 * 支持地图缩放与区域对象批量移动管理。
 *
 * @version 1.1
 * @since 2024
 */
public class MapEditor {
    private static final int FORBIDDEN_ZONE_GRID_SIZE = 20; // BFS网格大小 (复活禁区)
    private static final int GENERAL_ZONE_GRID_SIZE = 10; // 通行禁区网格大小 (更细致)
    // 主窗口组件
    private JFrame frame;
    private MapPanel mapPanel;
    private MapData mapData;
    // 这个列表用来存储我们所有“粗略点”的坐标
    private List<Point2D.Double> waypoints = new ArrayList<>();

    // 地图尺寸控件
    private JSpinner widthSpinner;
    private JSpinner heightSpinner;
    private JSpinner scaleSpinner; // <-- 新增：缩放微调器

    /**
     * 编辑器工具枚举 (已更新)
     */
    private enum Tool {
        SELECT, // 选择/移动工具
        RECTANGLE, // 矩形障碍物
        ELLIPSE, // 圆形障碍物
        POLYGON, // 多边形障碍物
        CT_SPAWN, // 反恐精英出生区域
        T_SPAWN, // 恐怖分子出生区域
        BOMB_A, // A点炸弹区域
        BOMB_B, // B点炸弹区域
        ERASER, // 橡皮擦工具
        AREA_DELETE, // 区域删除工具
        WAYPOINT, // 路径点
        AREA_MOVE, // 区域移动工具
        FORBIDDEN_SPAWN_ZONE,
        FORBIDDEN_ZONE // 通用禁区 (寻路/导航区域)
    }

    // 当前选中的工具
    private Tool currentTool = Tool.RECTANGLE;

    private Point2D.Double searchedPoint = null; // 存储搜索到的点
    private JTextField searchXField;
    private JTextField searchYField;
    private double zoomFactor = 1.0;
    private JSlider zoomSlider;

    // 多边形绘制时的临时点集合
    private ArrayList<Point> currentPolygonPoints = new ArrayList<>();

    /**
     * 构造函数 (保持不变)
     */
    public MapEditor(File mapFile) {
        if (mapFile != null) {
            loadMap(mapFile);
        } else {
            mapData = new MapData();
        }
        createUI();
    }

    // 添加一个适配器来确保 Point2D.Double 能被正确保存和加载
    private final Gson gson = new GsonBuilder()
            .registerTypeAdapter(Point2D.Double.class, new Point2DTypeAdapter()) // <-- 添加这一行
            .setPrettyPrinting()
            .create();

    /**
     * 创建用户界面 (已更新)
     */
    private void createUI() {
        frame = new JFrame("CS2D 地图编辑器");
        frame.setSize(1200, 800);
        frame.setDefaultCloseOperation(JFrame.DISPOSE_ON_CLOSE);
        frame.setLayout(new BorderLayout());

        // === 顶部面板 - 地图尺寸控制 (已更新) ===
        JPanel topPanel = new JPanel(new FlowLayout(FlowLayout.LEFT));
        topPanel.setBorder(BorderFactory.createEmptyBorder(2, 5, 2, 5));

        // 宽度设置
        topPanel.add(new JLabel(" 宽: "));
        widthSpinner = new JSpinner(new SpinnerNumberModel(mapData.getWidth(), 500, 10000, 100));
        topPanel.add(widthSpinner);

        // 高度设置
        topPanel.add(new JLabel(" 高: "));
        heightSpinner = new JSpinner(new SpinnerNumberModel(mapData.getHeight(), 500, 10000, 100));
        topPanel.add(heightSpinner);

        // 应用尺寸按钮
        JButton resizeButton = new JButton("应用尺寸");
        resizeButton.addActionListener(e -> resizeMap());
        topPanel.add(resizeButton);

        // --- VVVV 新增缩放功能 VVVV ---
        topPanel.add(new JSeparator(SwingConstants.VERTICAL));
        topPanel.add(new JLabel(" 缩放倍数: "));
        // SpinnerNumberModel(默认值, 最小值, 最大值, 步长)
        scaleSpinner = new JSpinner(new SpinnerNumberModel(1.0, 0.1, 10.0, 0.1));
        topPanel.add(scaleSpinner);

        JButton scaleButton = new JButton("应用缩放");
        scaleButton.addActionListener(e -> applyScale());
        topPanel.add(scaleButton);
        // --- VVVV 新增: 坐标搜索功能 VVVV ---
        topPanel.add(new JSeparator(SwingConstants.VERTICAL));
        topPanel.add(new JLabel(" 搜索坐标 (X, Y): "));
        searchXField = new JTextField("0", 5); // 5列宽
        searchYField = new JTextField("0", 5);
        topPanel.add(searchXField);
        topPanel.add(searchYField);

        JButton searchButton = new JButton("跳转");
        searchButton.addActionListener(e -> searchCoordinates());
        topPanel.add(searchButton);
        frame.add(topPanel, BorderLayout.NORTH);

        // === 左侧面板 - 工具选择 (已更新) ===
        JPanel toolContainer = new JPanel();
        toolContainer.setLayout(new BoxLayout(toolContainer, BoxLayout.Y_AXIS));
        toolContainer.setBorder(BorderFactory.createTitledBorder("工具"));

        ButtonGroup toolGroup = new ButtonGroup();

        addToolButton(toolContainer, toolGroup, "选择/移动", Tool.SELECT, false);
        addToolButton(toolContainer, toolGroup, "矩形障碍", Tool.RECTANGLE, true);
        addToolButton(toolContainer, toolGroup, "圆形障碍", Tool.ELLIPSE, false);
        addToolButton(toolContainer, toolGroup, "多边形障碍", Tool.POLYGON, false);
        addToolButton(toolContainer, toolGroup, "CT出生点", Tool.CT_SPAWN, false);
        addToolButton(toolContainer, toolGroup, "T出生点", Tool.T_SPAWN, false);
        addToolButton(toolContainer, toolGroup, "A包点", Tool.BOMB_A, false);
        addToolButton(toolContainer, toolGroup, "B包点", Tool.BOMB_B, false);
        addToolButton(toolContainer, toolGroup, "橡皮擦", Tool.ERASER, false);
        addToolButton(toolContainer, toolGroup, "区域删除", Tool.AREA_DELETE, false);
        addToolButton(toolContainer, toolGroup, "区域移动", Tool.AREA_MOVE, false);
        addToolButton(toolContainer, toolGroup, "路径点<粗>", Tool.WAYPOINT, false);
        addToolButton(toolContainer, toolGroup, "复活禁区(泼)", Tool.FORBIDDEN_SPAWN_ZONE, false); // <-- 新增
        addToolButton(toolContainer, toolGroup, "通行禁区(泼)", Tool.FORBIDDEN_ZONE, false); // 通用禁区
        frame.add(toolContainer, BorderLayout.WEST);

        // === 中央和底部面板 (保持不变) ===
        mapPanel = new MapPanel();
        JScrollPane scrollPane = new JScrollPane(mapPanel);
        frame.add(scrollPane, BorderLayout.CENTER);

        JPanel bottomPanel = new JPanel();

        bottomPanel.add(new JLabel("视图缩放:"));
        // JSlider(最小值, 最大值, 默认值)
        // 50 = 0.5x, 100 = 1x, 200 = 2x
        zoomSlider = new JSlider(25, 400, 100);
        zoomSlider.setMajorTickSpacing(100);
        zoomSlider.setMinorTickSpacing(25);
        zoomSlider.setPaintTicks(true);
        zoomSlider.setPaintLabels(true);
        zoomSlider.addChangeListener(e -> {
            // 当滑块移动时，更新 zoomFactor
            zoomFactor = zoomSlider.getValue() / 100.0;
            // 更新 mapPanel 的状态
            mapPanel.updateZoom();
        });
        bottomPanel.add(zoomSlider);

        JButton saveButton = new JButton("保存地图");
        saveButton.addActionListener(e -> saveMap());
        JButton clearSpawnsButton = new JButton("清除所有出生点");
        clearSpawnsButton.addActionListener(e -> {
            mapData.getCtSpawnAreas().clear();
            mapData.getTSpawnAreas().clear();
            mapPanel.repaint();
        });
        JButton clearWaypointsButton = new JButton("清除所有路径点"); // 新增按钮
        clearWaypointsButton.addActionListener(e -> {
            mapData.getWaypoints().clear(); // 清除路径点数据
            mapPanel.repaint(); // 刷新界面
        });
        bottomPanel.add(clearWaypointsButton);

        JButton clearForbiddenZonesButton = new JButton("清除所有复活禁区");
        clearForbiddenZonesButton.addActionListener(e -> {
            mapData.getForbiddenSpawnCells().clear(); // 清除禁区数据
            mapPanel.repaint(); // 刷新界面
        });
        bottomPanel.add(clearForbiddenZonesButton);

        JButton clearGeneralZonesButton = new JButton("清除所有通行禁区");
        clearGeneralZonesButton.addActionListener(e -> {
            mapData.getGeneralForbiddenZones().clear();
            mapPanel.repaint();
        });
        bottomPanel.add(clearGeneralZonesButton);

        bottomPanel.add(saveButton);
        bottomPanel.add(clearSpawnsButton);
        frame.add(bottomPanel, BorderLayout.SOUTH);

        frame.setLocationRelativeTo(null);
        frame.setVisible(true);
    }

    private void searchCoordinates() {
        try {
            double x = Double.parseDouble(searchXField.getText());
            double y = Double.parseDouble(searchYField.getText());

            // 1. 更新要高亮显示的点
            searchedPoint = new Point2D.Double(x, y);

            // 2. 滚动 JScrollPane 将该点置于视图中心
            // (mapPanel.getParent() 是 JViewport)
            if (mapPanel != null && mapPanel.getParent() instanceof JViewport viewport) {

                // 3. 计算目标点在 *缩放后* 的视图坐标
                double viewX = x * zoomFactor;
                double viewY = y * zoomFactor;

                // 4. 计算 JViewport 左上角应该在的新位置，以使 (viewX, viewY) 居中
                // (减去视口一半的宽高)
                double newViewX = viewX - viewport.getWidth() / 2.0;
                double newViewY = viewY - viewport.getHeight() / 2.0;

                // 5. 确保滚动位置不超出边界
                // (JViewport 的 setViewPosition 会自动处理最大值)
                newViewX = Math.max(0, newViewX);
                newViewY = Math.max(0, newViewY);

                // 6. 设置滚动位置
                viewport.setViewPosition(new Point((int) newViewX, (int) newViewY));
            }

            mapPanel.repaint(); // 触发重绘以显示五角星

        } catch (NumberFormatException e) {
            JOptionPane.showMessageDialog(frame, "坐标必须是有效的数字！", "输入错误", JOptionPane.ERROR_MESSAGE);
            searchedPoint = null; // 清除高亮
            if (mapPanel != null)
                mapPanel.repaint();
        }
    }

    /**
     * 添加工具按钮 (已更新)
     */
    private void addToolButton(JPanel panel, ButtonGroup group, String text, Tool tool, boolean selected) {
        JRadioButton button = new JRadioButton(text);
        button.setSelected(selected);
        if (selected) {
            currentTool = tool;
        }
        button.addActionListener(e -> {
            currentTool = tool;
            currentPolygonPoints.clear();
            mapPanel.clearSelection(); // <-- 切换工具时清除所有选择
            mapPanel.repaint();
        });
        group.add(button);
        panel.add(button);
    }

    /**
     * 调整地图尺寸 (保持不变)
     */
    private void resizeMap() {
        int newWidth = (int) widthSpinner.getValue();
        int newHeight = (int) heightSpinner.getValue();
        mapData.setWidth(newWidth);
        mapData.setHeight(newHeight);
        mapPanel.setPreferredSize(new Dimension(newWidth, newHeight));
        mapPanel.revalidate();
        mapPanel.repaint();
    }

    // --- VVVV 新增：应用缩放功能 VVVV ---
    /**
     * 新增：按比例缩放整个地图
     */
    private void applyScale() {
        double factor = (double) scaleSpinner.getValue();
        if (factor == 1.0)
            return; // 没有变化

        // 1. 缩放地图尺寸
        int newWidth = (int) (mapData.getWidth() * factor);
        int newHeight = (int) (mapData.getHeight() * factor);
        mapData.setWidth(newWidth);
        mapData.setHeight(newHeight);

        // 2. 缩放所有障碍物
        for (MapData.ShapeWrapper wrapper : mapData.getObstacles()) {
            wrapper.x *= factor;
            wrapper.y *= factor;
            if (wrapper.type == MapData.ShapeWrapper.ShapeType.POLYGON) {
                for (int i = 0; i < wrapper.xPoints.length; i++) {
                    wrapper.xPoints[i] *= factor;
                    wrapper.yPoints[i] *= factor;
                }
            } else {
                wrapper.width *= factor;
                wrapper.height *= factor;
            }
        }

        // 3. 缩放所有区域
        scaleRectangleList(mapData.getCtSpawnAreas(), factor);
        scaleRectangleList(mapData.getTSpawnAreas(), factor);
        mapData.setBombSiteA(scaleRectangle(mapData.getBombSiteA(), factor));
        mapData.setBombSiteB(scaleRectangle(mapData.getBombSiteB(), factor));

        // 4. 更新UI
        widthSpinner.setValue(newWidth);
        heightSpinner.setValue(newHeight);
        scaleSpinner.setValue(1.0); // 重置缩放倍数

        mapPanel.setPreferredSize(new Dimension(newWidth, newHeight));
        mapPanel.revalidate();
        mapPanel.repaint();
    }

    /**
     * 辅助方法：缩放一个矩形
     */
    private Rectangle scaleRectangle(Rectangle rect, double factor) {
        if (rect == null)
            return null;
        return new Rectangle(
                (int) (rect.x * factor),
                (int) (rect.y * factor),
                (int) (rect.width * factor),
                (int) (rect.height * factor));
    }

    /**
     * 辅助方法：缩放一个矩形列表
     */
    private void scaleRectangleList(List<Rectangle> rects, double factor) {
        for (int i = 0; i < rects.size(); i++) {
            rects.set(i, scaleRectangle(rects.get(i), factor));
        }
    }
    // --- ^^^^ 结束 ^^^^ ---

    /**
     * 保存地图 (保持不变)
     */
    private void saveMap() {
        JFileChooser fileChooser = new JFileChooser("maps");
        int result = fileChooser.showSaveDialog(frame);
        if (result == JFileChooser.APPROVE_OPTION) {
            File file = fileChooser.getSelectedFile();
            if (!file.getName().toLowerCase().endsWith(".json")) {
                file = new File(file.getParentFile(), file.getName() + ".json");
            }

            try {
                System.out.println("[MapEditor] 开始烘焙路径点连接...");
                bakeWaypointConnections(); // <-- 在这里执行耗时的 O(N^2 * M) 计算
                System.out.println("[MapEditor] 烘焙完成。");
            } catch (Exception e) {
                e.printStackTrace();
                JOptionPane.showMessageDialog(frame, "烘焙路径点连接时出错: " + e.getMessage(), "烘焙错误",
                        JOptionPane.ERROR_MESSAGE);
                return; // 烘焙失败则不保存
            }

            try (FileWriter writer = new FileWriter(file)) {
                mapData.setWidth((int) widthSpinner.getValue());
                mapData.setHeight((int) heightSpinner.getValue());
                gson.toJson(mapData, writer);
                JOptionPane.showMessageDialog(frame, "地图已保存!");
            } catch (IOException e) {
                JOptionPane.showMessageDialog(frame, "保存失败: " + e.getMessage(), "错误", JOptionPane.ERROR_MESSAGE);
            }
        }
    }

    /**
     * 加载地图 (保持不变)
     */
    private void loadMap(File file) {
        try (FileReader reader = new FileReader(file)) {
            mapData = gson.fromJson(reader, MapData.class);
            if (mapData.getCtSpawnAreas() == null)
                mapData.setCtSpawnAreas(new ArrayList<>());
            if (mapData.getTSpawnAreas() == null)
                mapData.setTSpawnAreas(new ArrayList<>());
            if (mapData.getWaypoints() == null)
                mapData.setWaypoints(new ArrayList<>()); // 初始化路径点列表

        } catch (IOException e) {
            JOptionPane.showMessageDialog(null, "加载地图失败: " + e.getMessage(), "错误", JOptionPane.ERROR_MESSAGE);
            mapData = new MapData();
        }
    }

    /**
     * 地图绘制面板 (已重写)
     * 负责地图的可视化渲染和用户交互处理
     */
    class MapPanel extends JPanel {
        // 鼠标交互状态
        private Point startPoint; // 鼠标按下位置
        private Point endPoint; // 鼠标释放/拖拽位置

        private static final int WAYPOINT_DRAW_RADIUS = 5; // 绘制路径点时使用的半径
        private static final int WAYPOINT_CLICK_RADIUS = 8; // 检测点击路径点时的容错半径
        private Object selectedObject = null; // [SELECT] 单个选中的对象
        private List<Object> selectedObjects = new ArrayList<>(); // [AREA_MOVE] 多个选中的对象

        private Point individualDragOffset; // [SELECT] 单个对象拖拽的偏移量

        private Rectangle selectionRect = null; // [AREA_MOVE, AREA_DELETE] 正在绘制的选择框
        private Point groupDragStartPoint = null; // [AREA_MOVE] 区域拖拽的起始点
        private List<Point> originalPositions = new ArrayList<>(); // [AREA_MOVE] 区域拖拽的原始坐标
        // --- ^^^^ 结束 ^^^^ ---

        /**
         * 地图面板构造函数
         */
        MapPanel() {
            setPreferredSize(new Dimension(mapData.getWidth(), mapData.getHeight()));
            setBackground(Color.DARK_GRAY);
            setFocusable(true);

            MouseAdapter ma = new MouseAdapter() {

                /**
                 * 鼠标按下事件
                 */
                @Override
                public void mousePressed(MouseEvent e) {
                    requestFocusInWindow(); // 获取焦点以接收键盘事件（如删除键）

                    // [核心] 将视图坐标转换为地图坐标
                    startPoint = toMapCoords(e.getPoint());
                    endPoint = startPoint;

                    // 重置选择框和拖拽状态
                    selectionRect = null;
                    groupDragStartPoint = null;
                    originalPositions.clear();

                    if (currentTool == Tool.SELECT) {
                        clearSelection(); // 清除之前的多选状态
                        selectedObject = findObjectAt(startPoint); // [使用 Map 坐标] 查找鼠标下的对象
                        if (selectedObject != null) {
                            // 如果选中的不是路径点（而是障碍物或区域），计算拖拽偏移量
                            if (!(selectedObject instanceof MapData.SerializableWaypoint)) {
                                individualDragOffset = new Point(
                                        startPoint.x - getObjectX(selectedObject), // [使用 Map 坐标]
                                        startPoint.y - getObjectY(selectedObject) // [使用 Map 坐标]
                                );
                            } else {
                                individualDragOffset = null; // 路径点不需要偏移量，直接用鼠标位置
                            }
                        }
                    }
                    // --- VVVV 新增 VVVV ---
                    else if (currentTool == Tool.WAYPOINT) {
                        // 如果当前是路径点工具，就在点击位置添加一个新的路径点
                        MapData.SerializableWaypoint newWp = new MapData.SerializableWaypoint(
                                new Point2D.Double(startPoint.x, startPoint.y));
                        mapData.getWaypoints().add(newWp);
                        clearSelection(); // 添加后清除任何之前的选择状态
                    }
                    // --- ^^^^ 结束 ^^^^ ---
                    else if (currentTool == Tool.AREA_MOVE) {
                        selectedObject = null; // 区域移动模式下，清除单选状态
                        boolean clickedOnSelection = false;
                        // 检查是否点击在了已经选中的对象上
                        for (Object obj : selectedObjects) {
                            if (obj instanceof MapData.SerializableWaypoint) { // 对路径点使用距离判断
                                // [使用 Map 坐标]
                                if (((MapData.SerializableWaypoint) obj).position
                                        .distance(startPoint) < WAYPOINT_CLICK_RADIUS) {
                                    clickedOnSelection = true;
                                    break;
                                }
                            } else if (getShapeFromObject(obj).contains(startPoint)) { // [使用 Map 坐标]
                                clickedOnSelection = true;
                                break;
                            }
                        }

                        if (clickedOnSelection) {
                            // 如果点在了选区上，准备开始拖拽整个选区
                            groupDragStartPoint = startPoint; // [使用 Map 坐标]
                            originalPositions.clear(); // 清空旧的原始位置
                            // 记录下当前所有选中对象的原始位置
                            for (Object obj : selectedObjects) {
                                if (obj instanceof MapData.SerializableWaypoint wp) {
                                    originalPositions.add(new Point((int) wp.position.x, (int) wp.position.y));
                                } else { // 记录其他对象的位置
                                    originalPositions.add(new Point(getObjectX(obj), getObjectY(obj)));
                                }
                            }
                        } else {
                            // 如果没点在选区上，说明要开始画一个新的选择框
                            clearSelection(); // 清除之前的选区
                            selectionRect = new Rectangle(startPoint.x, startPoint.y, 0, 0); // 初始化选择框
                        }
                    } else if (currentTool == Tool.AREA_DELETE) {
                        // 准备画区域删除的选择框
                        selectionRect = new Rectangle(startPoint.x, startPoint.y, 0, 0);
                    } else if (currentTool == Tool.POLYGON) {
                        // 多边形绘制逻辑
                        currentPolygonPoints.add(startPoint);
                        // 双击结束绘制
                        if (e.getClickCount() == 2 && currentPolygonPoints.size() > 2) {
                            int[] xPointsInt = currentPolygonPoints.stream().mapToInt(p -> p.x).toArray();
                            int[] yPointsInt = currentPolygonPoints.stream().mapToInt(p -> p.y).toArray();
                            mapData.getObstacles().add(new MapData.ShapeWrapper(xPointsInt, yPointsInt));
                            currentPolygonPoints.clear(); // 清空临时点
                        }
                    } else if (currentTool == Tool.ERASER) {
                        // 橡皮擦逻辑
                        Object objToDelete = findObjectAt(startPoint);
                        if (objToDelete != null) {
                            removeObject(objToDelete);
                        } else {
                            int sGridX = startPoint.x / FORBIDDEN_ZONE_GRID_SIZE;
                            int sGridY = startPoint.y / FORBIDDEN_ZONE_GRID_SIZE;
                            mapData.getForbiddenSpawnCells().remove(new Point(sGridX, sGridY));

                            int gGridX = startPoint.x / GENERAL_ZONE_GRID_SIZE;
                            int gGridY = startPoint.y / GENERAL_ZONE_GRID_SIZE;
                            mapData.getGeneralForbiddenZones().remove(new Point(gGridX, gGridY));
                        }
                    } else {
                        // 其他工具（画矩形、椭圆、区域等），清除选择状态
                        clearSelection();
                    }
                    repaint(); // 每次按下鼠标都重绘界面
                }

                /**
                 * 鼠标拖拽事件
                 */
                @Override
                public void mouseDragged(MouseEvent e) {
                    endPoint = toMapCoords(e.getPoint()); // [核心] 将视图坐标转换为地图坐标

                    if (currentTool == Tool.SELECT && selectedObject != null) {
                        // 处理单选对象的拖拽
                        if (selectedObject instanceof MapData.SerializableWaypoint) {
                            // 如果是路径点，直接更新它的位置为鼠标当前位置
                            updateObjectPosition(selectedObject, endPoint.x, endPoint.y); // [使用 Map 坐标]
                        } else if (individualDragOffset != null) { // 只有障碍物/区域才有偏移量
                            // 如果是障碍物或区域，根据鼠标位置和初始偏移量计算新位置
                            int newX = endPoint.x - individualDragOffset.x; // [使用 Map 坐标]
                            int newY = endPoint.y - individualDragOffset.y; // [使用 Map 坐标]
                            updateObjectPosition(selectedObject, newX, newY);
                        }
                    } else if (currentTool == Tool.AREA_MOVE) {
                        // 处理区域移动
                        if (groupDragStartPoint != null && !selectedObjects.isEmpty()) {
                            // 如果正在拖拽选区（而不是画选择框）
                            int dx = endPoint.x - groupDragStartPoint.x; // [使用 Map 坐标]
                            int dy = endPoint.y - groupDragStartPoint.y; // [使用 Map 坐标]
                            // 移动所有选中的对象
                            for (int i = 0; i < selectedObjects.size(); i++) {
                                if (i < originalPositions.size()) {
                                    Point originalPos = originalPositions.get(i);
                                    updateObjectPosition(selectedObjects.get(i), originalPos.x + dx,
                                            originalPos.y + dy);
                                }
                            }
                        } else if (selectionRect != null) {
                            // 如果正在画选择框，更新选择框的范围 (使用 startPoint 和 endPoint，它们都已是 Map 坐标)
                            selectionRect.setBounds(
                                    Math.min(startPoint.x, endPoint.x),
                                    Math.min(startPoint.y, endPoint.y),
                                    Math.abs(startPoint.x - endPoint.x),
                                    Math.abs(startPoint.y - endPoint.y));
                        }
                    } else if (currentTool == Tool.AREA_DELETE && selectionRect != null) {
                        // 如果是区域删除工具，更新删除框的范围 (使用 startPoint 和 endPoint)
                        selectionRect.setBounds(
                                Math.min(startPoint.x, endPoint.x),
                                Math.min(startPoint.y, endPoint.y),
                                Math.abs(startPoint.x - endPoint.x),
                                Math.abs(startPoint.y - endPoint.y));
                    } else if (currentTool == Tool.POLYGON && !currentPolygonPoints.isEmpty()) {
                        // 预览线段 (依赖 endPoint，已在开头更新)
                    } else if (startPoint != null &&
                            currentTool != Tool.SELECT && currentTool != Tool.ERASER &&
                            currentTool != Tool.POLYGON && currentTool != Tool.AREA_MOVE &&
                            currentTool != Tool.AREA_DELETE && currentTool != Tool.WAYPOINT) {
                        // 预览创建形状 (依赖 startPoint 和 endPoint)
                    }

                    repaint(); // 每次拖拽都重绘界面
                }

                /**
                 * 鼠标释放事件
                 */
                @Override
                public void mouseReleased(MouseEvent e) {
                    // endPoint 已经在 mouseDragged 中被更新为 map 坐标
                    // 或者如果只是点击（没有拖拽），endPoint == startPoint (也是 map 坐标)

                    if (currentTool == Tool.AREA_DELETE && selectionRect != null) {
                        // 执行区域删除操作 (selectionRect 已是 map 坐标)
                        mapData.getObstacles()
                                .removeIf(wrapper -> createShapeFromWrapper(wrapper).intersects(selectionRect));
                        mapData.getCtSpawnAreas().removeIf(rect -> rect.intersects(selectionRect));
                        mapData.getTSpawnAreas().removeIf(rect -> rect.intersects(selectionRect));
                        if (mapData.getBombSiteA() != null && mapData.getBombSiteA().intersects(selectionRect)) {
                            mapData.setBombSiteA(null);
                        }
                        if (mapData.getBombSiteB() != null && mapData.getBombSiteB().intersects(selectionRect)) {
                            mapData.setBombSiteB(null);
                        }
                        mapData.getWaypoints().removeIf(wp -> selectionRect.contains(wp.position.x, wp.position.y));

                        // --- 新增：删除框内的所有禁区单元格 ---
                        mapData.getForbiddenSpawnCells().removeIf(cell -> selectionRect.contains(
                                cell.x * FORBIDDEN_ZONE_GRID_SIZE + FORBIDDEN_ZONE_GRID_SIZE / 2,
                                cell.y * FORBIDDEN_ZONE_GRID_SIZE + FORBIDDEN_ZONE_GRID_SIZE / 2));
                        mapData.getGeneralForbiddenZones().removeIf(cell -> selectionRect.contains(
                                cell.x * GENERAL_ZONE_GRID_SIZE + GENERAL_ZONE_GRID_SIZE / 2,
                                cell.y * GENERAL_ZONE_GRID_SIZE + GENERAL_ZONE_GRID_SIZE / 2));
                        // --- 结束 ---

                    } else if (currentTool == Tool.AREA_MOVE && selectionRect != null) {
                        // 完成区域选择操作 (selectionRect 已是 map 坐标)
                        selectedObjects.clear();
                        selectedObjects.addAll(mapData.getObstacles().stream()
                                .filter(w -> createShapeFromWrapper(w).intersects(selectionRect))
                                .collect(Collectors.toList()));
                        selectedObjects.addAll(mapData.getCtSpawnAreas().stream()
                                .filter(r -> r.intersects(selectionRect))
                                .collect(Collectors.toList()));
                        selectedObjects.addAll(mapData.getTSpawnAreas().stream()
                                .filter(r -> r.intersects(selectionRect))
                                .collect(Collectors.toList()));
                        if (mapData.getBombSiteA() != null && mapData.getBombSiteA().intersects(selectionRect)) {
                            selectedObjects.add(mapData.getBombSiteA());
                        }
                        if (mapData.getBombSiteB() != null && mapData.getBombSiteB().intersects(selectionRect)) {
                            selectedObjects.add(mapData.getBombSiteB());
                        }
                        selectedObjects.addAll(mapData.getWaypoints().stream()
                                .filter(wp -> selectionRect.contains(wp.position.x, wp.position.y))
                                .collect(Collectors.toList()));

                        // --- VVVV 新增: 复活禁区工具逻辑 VVVV ---
                    } else if ((currentTool == Tool.FORBIDDEN_SPAWN_ZONE || currentTool == Tool.FORBIDDEN_ZONE)
                            && startPoint != null) {
                        // 1. 弹出输入框，询问BFS深度
                        String depthStr = JOptionPane.showInputDialog(
                                frame,
                                "请输入“泼洒”的层数 (BFS深度):",
                                "设置禁区范围",
                                JOptionPane.QUESTION_MESSAGE);
                        int maxDepth = 10; // 默认值
                        try {
                            if (depthStr != null)
                                maxDepth = Integer.parseInt(depthStr);
                        } catch (NumberFormatException ex) {
                            // 忽略无效输入，使用默认值
                        }

                        // 2. 运行BFS来“泼洒”区域
                        Set<Point> targetSet;
                        int currentGridSize;
                        if (currentTool == Tool.FORBIDDEN_SPAWN_ZONE) {
                            targetSet = mapData.getForbiddenSpawnCells();
                            currentGridSize = FORBIDDEN_ZONE_GRID_SIZE;
                        } else {
                            targetSet = mapData.getGeneralForbiddenZones();
                            currentGridSize = GENERAL_ZONE_GRID_SIZE;
                        }
                        runForbiddenZoneBFS(startPoint, maxDepth, targetSet, currentGridSize);
                        // --- ^^^^ 结束 ^^^^ ---

                    } else if (currentTool != Tool.SELECT && currentTool != Tool.ERASER &&
                            currentTool != Tool.POLYGON && currentTool != Tool.AREA_MOVE &&
                            currentTool != Tool.AREA_DELETE && currentTool != Tool.WAYPOINT &&
                            currentTool != Tool.FORBIDDEN_SPAWN_ZONE) { // <-- 已更新
                        // 处理创建新形状或区域的逻辑 (startPoint 和 endPoint 已是 map 坐标)
                        if (startPoint == null || endPoint == null)
                            return;
                        int x = Math.min(startPoint.x, endPoint.x);
                        int y = Math.min(startPoint.y, endPoint.y);
                        int width = Math.abs(startPoint.x - endPoint.x);
                        int height = Math.abs(startPoint.y - endPoint.y);

                        if (width < 1 || height < 1) {
                            startPoint = null;
                            endPoint = null;
                            repaint();
                            return;
                        }

                        Rectangle rect = new Rectangle(x, y, width, height);
                        switch (currentTool) {
                            case RECTANGLE:
                                mapData.getObstacles().add(new MapData.ShapeWrapper(
                                        MapData.ShapeWrapper.ShapeType.RECTANGLE, x, y, width, height));
                                break;
                            case ELLIPSE:
                                mapData.getObstacles().add(new MapData.ShapeWrapper(
                                        MapData.ShapeWrapper.ShapeType.ELLIPSE, x, y, width, height));
                                break;
                            case CT_SPAWN:
                                mapData.getCtSpawnAreas().add(rect);
                                break;
                            case T_SPAWN:
                                mapData.getTSpawnAreas().add(rect);
                                break;
                            case BOMB_A:
                                mapData.setBombSiteA(rect);
                                break;
                            case BOMB_B:
                                mapData.setBombSiteB(rect);
                                break;
                        }
                    }

                    // 重置鼠标交互状态
                    startPoint = null;
                    endPoint = null;
                    individualDragOffset = null;
                    selectionRect = null;
                    groupDragStartPoint = null;
                    // 注意：selectedObjects 列表不清空，以便用户可以继续拖拽选区
                    repaint(); // 鼠标释放后重绘
                }

                /**
                 * 鼠标移动事件 (用于多边形预览)
                 */
                @Override
                public void mouseMoved(MouseEvent e) {
                    // 如果正在画多边形，更新预览线的终点
                    if (currentTool == Tool.POLYGON && !currentPolygonPoints.isEmpty()) {
                        endPoint = toMapCoords(e.getPoint()); // [核心] 将视图坐标转换为地图坐标
                        repaint();
                    }
                }
            };

            addMouseListener(ma);
            addMouseMotionListener(ma);

            // 键盘监听器 (已更新以处理路径点)
            addKeyListener(new KeyAdapter() {
                @Override
                public void keyPressed(KeyEvent e) {
                    if (e.getKeyCode() == KeyEvent.VK_DELETE) {
                        if (selectedObject != null) {
                            removeObject(selectedObject); // removeObject 已更新
                            selectedObject = null;
                        }
                        if (!selectedObjects.isEmpty()) {
                            for (Object obj : selectedObjects) {
                                removeObject(obj); // removeObject 已更新
                            }
                            selectedObjects.clear();
                        }
                        repaint();
                    }
                }
            });
        }

        private void drawStar(Graphics g, double centerX, double centerY, double outerRadius, int numPoints) {
            Graphics2D g2d = (Graphics2D) g.create();
            try {
                double innerRadius = outerRadius * 0.4;
                double[] xPoints = new double[numPoints * 2];
                double[] yPoints = new double[numPoints * 2];

                for (int i = 0; i < numPoints * 2; i++) {
                    double radius = (i % 2 == 0) ? outerRadius : innerRadius;
                    double angle = Math.PI / numPoints * i - Math.PI / 2;
                    xPoints[i] = centerX + radius * Math.cos(angle);
                    yPoints[i] = centerY + radius * Math.sin(angle);
                }

                Path2D.Double star = new Path2D.Double();
                star.moveTo(xPoints[0], yPoints[0]);
                for (int i = 1; i < xPoints.length; i++) {
                    star.lineTo(xPoints[i], yPoints[i]);
                }
                star.closePath();
                g2d.fill(star);
            } finally {
                g2d.dispose();
            }
        }

        /**
         * 当外部 zoomSlider 变化时，调用此方法
         */
        public void updateZoom() {
            // 1. 计算缩放后的新尺寸
            Dimension newSize = new Dimension(
                    (int) (mapData.getWidth() * zoomFactor),
                    (int) (mapData.getHeight() * zoomFactor));
            // 2. 设置面板的首选尺寸，JScrollPane 会根据这个尺寸显示滚动条
            setPreferredSize(newSize);
            // 3. 重新验证布局
            revalidate();
            // 4. 触发重绘
            repaint();
        }

        /**
         * 将 Swing (视图) 坐标转换为地图 (模型) 坐标
         */
        private Point toMapCoords(Point viewCoords) {
            if (viewCoords == null)
                return null;
            return new Point(
                    (int) (viewCoords.x / zoomFactor),
                    (int) (viewCoords.y / zoomFactor));
        }

        /**
         * 清除所有选择状态
         */
        public void clearSelection() {
            selectedObject = null;
            selectedObjects.clear();
        }

        /**
         * 绘制地图面板内容 (已更新以绘制路径点和高亮)
         */
        @Override
        protected void paintComponent(Graphics g) {
            super.paintComponent(g);
            Graphics2D g2d = (Graphics2D) g.create(); // <-- 使用 create() 创建一个副本

            // 在所有绘制操作之前，对 Graphics2D 应用缩放
            g2d.scale(zoomFactor, zoomFactor);

            // 开启抗锯齿
            g2d.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);

            if (searchedPoint != null) {
                g2d.setColor(Color.YELLOW); // 使用黄色
                g2d.setStroke(new BasicStroke(3)); // 粗线条

                // 绘制一个醒目的五角星 (需要 drawStar 辅助方法)
                drawStar(g2d, searchedPoint.x, searchedPoint.y, 20, 5); // 20像素半径

                // 再绘制一个闪烁的圆圈，每半秒闪烁一次
                if ((System.currentTimeMillis() / 500) % 2 == 0) {
                    g2d.drawOval((int) searchedPoint.x - 25, (int) searchedPoint.y - 25, 50, 50);
                }
            }

            // 绘制所有障碍物（灰色填充）
            g2d.setColor(Color.LIGHT_GRAY);
            for (MapData.ShapeWrapper wrapper : mapData.getObstacles()) {
                g2d.fill(createShapeFromWrapper(wrapper));
            }

            // 绘制 CT/T 出生区和 A/B 包点（半透明颜色填充，白色边框和文字标签）
            drawZone(g2d, mapData.getCtSpawnAreas(), new Color(0, 0, 255, 100), "CT");
            drawZone(g2d, mapData.getTSpawnAreas(), new Color(255, 0, 0, 100), "T");
            if (mapData.getBombSiteA() != null)
                drawSingleZone(g2d, mapData.getBombSiteA(), new Color(255, 165, 0, 100), "A");
            if (mapData.getBombSiteB() != null)
                drawSingleZone(g2d, mapData.getBombSiteB(), new Color(255, 165, 0, 100), "B");

            g2d.setColor(Color.MAGENTA);
            Font waypointFont = new Font("Arial", Font.PLAIN, 10);
            g2d.setFont(waypointFont);
            FontMetrics fm = g2d.getFontMetrics();
            int waypointIndex = 0;

            for (MapData.SerializableWaypoint wp : mapData.getWaypoints()) { // <-- 遍历

                // 检查 wp 对象本身或其 position 字段是否为 null
                if (wp == null || wp.position == null) {
                    System.err.println("[MapEditor] 警告: 跳过一个无效的(null)路径点。");
                    continue; // 跳过这个损坏的点，继续循环
                }
                int drawX = (int) wp.position.x - WAYPOINT_DRAW_RADIUS; // <-- 使用 .position

                int drawY = (int) wp.position.y - WAYPOINT_DRAW_RADIUS; // <-- 使用 .position

                g2d.fillOval(drawX, drawY, WAYPOINT_DRAW_RADIUS * 2, WAYPOINT_DRAW_RADIUS * 2);
                String indexStr = String.valueOf(waypointIndex++);
                g2d.setColor(Color.WHITE);
                g2d.drawString(indexStr, (int) wp.position.x + WAYPOINT_DRAW_RADIUS + 2,
                        (int) wp.position.y + fm.getAscent() / 2); // <-- 使用 .position

                // --- 绘制已烘焙的连接线 ---
                if (wp.neighborIndices != null) {
                    g2d.setColor(new Color(255, 0, 255, 50)); // 半透明品红
                    g2d.setStroke(new BasicStroke(1));
                    for (int neighborIndex : wp.neighborIndices) {
                        // 避免越界并防止反向绘制
                        if (neighborIndex < mapData.getWaypoints().size() && neighborIndex > waypointIndex - 1) {
                            MapData.SerializableWaypoint neighbor = mapData.getWaypoints().get(neighborIndex);
                            g2d.drawLine((int) wp.position.x, (int) wp.position.y, (int) neighbor.position.x,
                                    (int) neighbor.position.y);
                        }
                    }
                }
                // --- ^^^^ 结束绘制连接线 ^^^^ ---

                g2d.setColor(Color.MAGENTA); // 恢复颜色
            }
            g2d.setFont(new Font("Arial", Font.BOLD, 20));

            // 禁止复活区
            g2d.setColor(new Color(255, 100, 0, 70)); // 半透明的橙色
            for (Point cell : mapData.getForbiddenSpawnCells()) {
                g2d.fillRect(
                        cell.x * FORBIDDEN_ZONE_GRID_SIZE,
                        cell.y * FORBIDDEN_ZONE_GRID_SIZE,
                        FORBIDDEN_ZONE_GRID_SIZE,
                        FORBIDDEN_ZONE_GRID_SIZE);
            }

            // 通行禁区 (蓝紫色)
            g2d.setColor(new Color(100, 50, 255, 90));
            for (Point cell : mapData.getGeneralForbiddenZones()) {
                g2d.fillRect(
                        cell.x * GENERAL_ZONE_GRID_SIZE,
                        cell.y * GENERAL_ZONE_GRID_SIZE,
                        GENERAL_ZONE_GRID_SIZE,
                        GENERAL_ZONE_GRID_SIZE);
            }

            // 绘制高亮效果 (已更新以处理路径点)
            g2d.setColor(Color.CYAN);
            g2d.setStroke(new BasicStroke(3));
            if (selectedObject != null) {
                if (selectedObject instanceof MapData.SerializableWaypoint wp) { // <-- 改为新类型
                    g2d.drawOval((int) wp.position.x - WAYPOINT_CLICK_RADIUS,
                            (int) wp.position.y - WAYPOINT_CLICK_RADIUS, // <-- 使用 .position
                            WAYPOINT_CLICK_RADIUS * 2, WAYPOINT_CLICK_RADIUS * 2);
                } else {
                    g2d.draw(getShapeFromObject(selectedObject));
                }
            }
            if (!selectedObjects.isEmpty()) {
                for (Object obj : selectedObjects) {
                    if (obj instanceof MapData.SerializableWaypoint wp) { // <-- 改为新类型
                        g2d.drawOval((int) wp.position.x - WAYPOINT_CLICK_RADIUS,
                                (int) wp.position.y - WAYPOINT_CLICK_RADIUS, // <-- 使用 .position
                                WAYPOINT_CLICK_RADIUS * 2, WAYPOINT_CLICK_RADIUS * 2);
                    } else {
                        g2d.draw(getShapeFromObject(obj));
                    }
                }
            }
            g2d.setStroke(new BasicStroke(1)); // 恢复默认线条宽度

            // 绘制正在拖拽创建的形状预览（矩形、椭圆、区域）
            if (startPoint != null && endPoint != null &&
                    currentTool != Tool.SELECT && currentTool != Tool.ERASER && currentTool != Tool.POLYGON &&
                    currentTool != Tool.AREA_MOVE && currentTool != Tool.AREA_DELETE && currentTool != Tool.WAYPOINT) {
                g2d.setColor(new Color(255, 255, 255, 150)); // 半透明白色
                int x = Math.min(startPoint.x, endPoint.x);
                int y = Math.min(startPoint.y, endPoint.y);
                int width = Math.abs(startPoint.x - endPoint.x);
                int height = Math.abs(startPoint.y - endPoint.y);

                if (currentTool == Tool.RECTANGLE)
                    g2d.fillRect(x, y, width, height);
                else if (currentTool == Tool.ELLIPSE)
                    g2d.fillOval(x, y, width, height);
                else
                    g2d.drawRect(x, y, width, height); // 出生区和包点只画框
            }

            // 绘制区域选择/删除框（虚线框）
            if ((currentTool == Tool.AREA_DELETE || currentTool == Tool.AREA_MOVE) && selectionRect != null) {
                Color boxColor = (currentTool == Tool.AREA_DELETE) ? Color.RED : Color.CYAN; // 删除用红色，选择用青色

                g2d.setColor(new Color(boxColor.getRed(), boxColor.getGreen(), boxColor.getBlue(), 100)); // 半透明填充
                g2d.fillRect(selectionRect.x, selectionRect.y, selectionRect.width, selectionRect.height);

                g2d.setColor(boxColor); // 边框颜色
                // 设置虚线样式
                g2d.setStroke(new BasicStroke(1, BasicStroke.CAP_BUTT, BasicStroke.JOIN_BEVEL,
                        0, new float[] { 9 }, 0));
                g2d.drawRect(selectionRect.x, selectionRect.y, selectionRect.width, selectionRect.height);
                g2d.setStroke(new BasicStroke(1)); // 恢复默认实线
            }

            // 绘制正在绘制的多边形预览线段
            if (currentTool == Tool.POLYGON && !currentPolygonPoints.isEmpty()) {
                g2d.setColor(Color.WHITE);
                g2d.setStroke(new BasicStroke(1));
                // 绘制已经确定的线段
                for (int i = 0; i < currentPolygonPoints.size() - 1; i++) {
                    g2d.drawLine(currentPolygonPoints.get(i).x, currentPolygonPoints.get(i).y,
                            currentPolygonPoints.get(i + 1).x, currentPolygonPoints.get(i + 1).y);
                }
                // 绘制从最后一个点到当前鼠标位置的预览线
                if (endPoint != null) {
                    g2d.drawLine(currentPolygonPoints.get(currentPolygonPoints.size() - 1).x,
                            currentPolygonPoints.get(currentPolygonPoints.size() - 1).y,
                            endPoint.x, endPoint.y);
                }
            }

            g2d.dispose();
        }

        /**
         * 绘制区域 (保持不变)
         */
        private void drawZone(Graphics2D g2d, List<Rectangle> zones, Color color, String label) {
            if (zones == null)
                return;
            for (Rectangle zone : zones) {
                drawSingleZone(g2d, zone, color, label);
            }
        }

        private void drawSingleZone(Graphics2D g2d, Rectangle zone, Color color, String label) {
            g2d.setColor(color);
            g2d.fill(zone);
            g2d.setColor(Color.WHITE);
            g2d.setStroke(new BasicStroke(1));
            g2d.draw(zone);
            g2d.setFont(new Font("Arial", Font.BOLD, 20));
            FontMetrics fm = g2d.getFontMetrics();
            int stringX = zone.x + (zone.width - fm.stringWidth(label)) / 2;
            int stringY = zone.y + (zone.height - fm.getHeight()) / 2 + fm.getAscent();
            g2d.drawString(label, stringX, stringY);
        }

        /**
         * 辅助方法：获取Shape (保持不变)
         */
        private Shape getShapeFromObject(Object obj) {
            if (obj instanceof MapData.ShapeWrapper) {
                return createShapeFromWrapper((MapData.ShapeWrapper) obj);
            } else if (obj instanceof Rectangle) {
                return (Rectangle) obj;
            }
            return new Rectangle();
        }

        /**
         * 辅助方法：获取X (保持不变)
         */
        private int getObjectX(Object obj) {
            if (obj instanceof MapData.ShapeWrapper) {
                return (int) ((MapData.ShapeWrapper) obj).x;
            } else if (obj instanceof MapData.SerializableWaypoint wp) { // <-- 改为新类型
                return (int) wp.position.x;
            }
            return 0;
        }

        /**
         * 辅助方法：获取Y (保持不变)
         */
        private int getObjectY(Object obj) {
            if (obj instanceof MapData.ShapeWrapper) {
                return (int) ((MapData.ShapeWrapper) obj).y;
            } else if (obj instanceof MapData.SerializableWaypoint wp) { // <-- 改为新类型
                return (int) wp.position.y;
            }
            return 0;
        }

        /**
         * 更新对象的位置 (已更新以处理路径点)
         */
        private void updateObjectPosition(Object obj, int x, int y) {
            if (obj instanceof MapData.ShapeWrapper wrapper) {
                // 计算基准点的移动偏移量
                double dx = x - wrapper.x;
                double dy = y - wrapper.y;
                // 如果是多边形，需要移动所有顶点
                if (wrapper.type == MapData.ShapeWrapper.ShapeType.POLYGON && wrapper.xPoints != null) {
                    for (int i = 0; i < wrapper.xPoints.length; i++) {
                        wrapper.xPoints[i] += dx;
                        wrapper.yPoints[i] += dy;
                    }
                }
                // 更新所有形状（矩形、椭圆、多边形）的基准点 x, y
                wrapper.x = x;
                wrapper.y = y;

            } else if (obj instanceof Rectangle rect) {
                // 直接设置矩形的新位置
                rect.setLocation(x, y);
            } else if (obj instanceof MapData.SerializableWaypoint wp) { // <-- 改为新类型
                wp.position.setLocation(x, y); // <-- 使用 .position
            }
        }

        /**
         * 移除对象 (已更新以处理路径点)
         */
        private void removeObject(Object obj) {
            if (obj instanceof MapData.ShapeWrapper) {
                mapData.getObstacles().remove(obj); // 移除障碍物
            } else if (obj instanceof Rectangle) {
                // 移除包点或出生区
                if (Objects.equals(obj, mapData.getBombSiteA()))
                    mapData.setBombSiteA(null);
                if (Objects.equals(obj, mapData.getBombSiteB()))
                    mapData.setBombSiteB(null);
                mapData.getCtSpawnAreas().remove(obj);
                mapData.getTSpawnAreas().remove(obj);
            } else if (obj instanceof MapData.SerializableWaypoint) { // <-- 改为新类型
                mapData.getWaypoints().remove(obj);
                // --- ^^^^ ---
            }
        }

        /**
         * 在指定点查找对象 (已更新以优先查找路径点)
         */
        private Object findObjectAt(Point p) {
            // 优先检查多选对象（路径点用距离，其他用形状包含）
            for (Object obj : selectedObjects) {
                if (obj instanceof Point2D.Double wp) {
                    if (wp.distance(p) < WAYPOINT_CLICK_RADIUS)
                        return obj;
                } else if (getShapeFromObject(obj).contains(p)) { // 使用 contains 判断点是否在形状内部
                    return obj;
                }
            }
            // 其次检查单选对象
            if (selectedObject != null) {
                if (selectedObject instanceof Point2D.Double wp) {
                    if (wp.distance(p) < WAYPOINT_CLICK_RADIUS)
                        return selectedObject;
                } else if (getShapeFromObject(selectedObject).contains(p)) {
                    return selectedObject;
                }
            }

            // 因为路径点很小，先检查它们，避免被大的障碍物覆盖
            for (MapData.SerializableWaypoint wp : mapData.getWaypoints()) { // <-- 改为新类型
                if (wp.position.distance(p) < WAYPOINT_CLICK_RADIUS) { // <-- 使用 .position
                    return wp;
                }
            }

            // 然后再按原来的顺序检查障碍物（从上层往下层找）
            for (int i = mapData.getObstacles().size() - 1; i >= 0; i--) {
                MapData.ShapeWrapper wrapper = mapData.getObstacles().get(i);
                if (createShapeFromWrapper(wrapper).contains(p)) {
                    return wrapper;
                }
            }
            // 最后检查包点和出生区
            if (mapData.getBombSiteA() != null && mapData.getBombSiteA().contains(p))
                return mapData.getBombSiteA();
            if (mapData.getBombSiteB() != null && mapData.getBombSiteB().contains(p))
                return mapData.getBombSiteB();
            for (Rectangle r : mapData.getCtSpawnAreas())
                if (r.contains(p))
                    return r;
            for (Rectangle r : mapData.getTSpawnAreas())
                if (r.contains(p))
                    return r;

            return null; // 什么都没找到
        }

        /**
         * 创建Shape (保持不变)
         */
        public Shape createShapeFromWrapper(MapData.ShapeWrapper wrapper) {
            switch (wrapper.type) {
                case RECTANGLE:
                    return new Rectangle2D.Double(wrapper.x, wrapper.y, wrapper.width, wrapper.height);
                case ELLIPSE:
                    return new Ellipse2D.Double(wrapper.x, wrapper.y, wrapper.width, wrapper.height);
                case POLYGON:
                    Path2D.Double poly = new Path2D.Double();
                    if (wrapper.xPoints == null || wrapper.xPoints.length < 3)
                        return new Rectangle();
                    poly.moveTo(wrapper.xPoints[0], wrapper.yPoints[0]);
                    for (int i = 1; i < wrapper.xPoints.length; i++) {
                        poly.lineTo(wrapper.xPoints[i], wrapper.yPoints[i]);
                    }
                    poly.closePath();
                    return poly;
                default:
                    return new Rectangle();
            }
        }
    }

    // --- VVVV 新增: Gson 的 Point2D.Double 适配器 VVVV ---
    // 这个内部类告诉 Gson 如何将 Point2D.Double 对象转换为 JSON，以及如何从 JSON 读回它。
    // 这能确保坐标被正确地保存为 {"x": ..., "y": ...} 格式。
    static class Point2DTypeAdapter extends TypeAdapter<Point2D.Double> {
        @Override
        public void write(JsonWriter out, Point2D.Double value) throws IOException {
            if (value == null) {
                out.nullValue(); // 如果点是 null，写入 JSON null
                return;
            }
            // 写入 JSON 对象: { "x": x坐标, "y": y坐标 }
            out.beginObject();
            out.name("x").value(value.getX());
            out.name("y").value(value.getY());
            out.endObject();
        }

        @Override
        public Point2D.Double read(JsonReader in) throws IOException {
            if (in.peek() == JsonToken.NULL) {
                in.nextNull(); // 如果读到 JSON null，返回 null
                return null;
            }
            // 读取 JSON 对象
            in.beginObject();
            double x = 0, y = 0;
            while (in.hasNext()) {
                String name = in.nextName(); // 读取键名 ("x" 或 "y")
                if ("x".equals(name)) {
                    x = in.nextDouble(); // 读取 x 值
                } else if ("y".equals(name)) {
                    y = in.nextDouble(); // 读取 y 值
                } else {
                    in.skipValue(); // 忽略其他未知字段
                }
            }
            in.endObject();
            // 创建并返回 Point2D.Double 对象
            return new Point2D.Double(x, y);
        }
    }

    /**
     * 在编辑器端执行的路径点烘焙逻辑
     * (这个方法在 MapEditor 类内部，与 saveMap() 平级)
     */
    public void bakeWaypointConnections() {
        List<MapData.SerializableWaypoint> waypoints = mapData.getWaypoints();
        List<MapData.ShapeWrapper> obstacles = mapData.getObstacles();

        // 创建一个临时的 Shape 列表，用于碰撞检测
        // 我们需要 mapPanel 实例来调用 createShapeFromWrapper
        if (mapPanel == null) {
            System.err.println("烘焙失败: mapPanel 未初始化!");
            return;
        }
        List<Shape> obstacleShapes = new ArrayList<>();
        for (MapData.ShapeWrapper wrapper : obstacles) {
            obstacleShapes.add(mapPanel.createShapeFromWrapper(wrapper)); // 使用 MapPanel 的方法
        }

        int connectionCount = 0;

        // 2. 在开始计算前，清除所有旧的连接信息
        for (MapData.SerializableWaypoint wp : waypoints) {
            wp.neighborIndices.clear();
            wp.neighborCosts.clear();
        }

        // 3. 执行 N^2 视线检查
        for (int i = 0; i < waypoints.size(); i++) {
            MapData.SerializableWaypoint wp1 = waypoints.get(i);
            for (int j = i + 1; j < waypoints.size(); j++) {
                MapData.SerializableWaypoint wp2 = waypoints.get(j);

                // 4. 检查视线
                boolean hasObstacle = false;
                Line2D.Double ray = new Line2D.Double(wp1.position, wp2.position);

                for (Shape obs : obstacleShapes) {
                    // 使用我们即将复制过来的静态方法进行检查
                    if (obs.intersects(ray.getBounds2D()) && getLineShapeIntersections(ray, obs) != null) {
                        hasObstacle = true;
                        break;
                    }
                }

                // 5. 如果视线通畅，则双向添加连接 (使用索引)
                if (!hasObstacle) {
                    double distance = wp1.position.distance(wp2.position);

                    // wp1 -> wp2
                    wp1.neighborIndices.add(j); // 添加索引 j
                    wp1.neighborCosts.add(distance);

                    // wp2 -> wp1
                    wp2.neighborIndices.add(i); // 添加索引 i
                    wp2.neighborCosts.add(distance);

                    connectionCount++;
                }
            }
        }
        System.out.println("[MapEditor] 烘焙统计: " + waypoints.size() + " 个路径点, " + connectionCount + " 条唯一连接。");
    }

    /**
     * (从 GameState 复制) 计算一条直线与一个任意形状的所有交点。
     */
    private static Point2D.Double[] getLineShapeIntersections(Line2D.Double line, Shape shape) {
        // 安全检查：如果 shape 本身就是 null，直接返回
        if (shape == null) {
            System.err.println("警告: getLineShapeIntersections 收到 null Shape!");
            return null;
        }

        PathIterator pathIterator = shape.getPathIterator(null, 1.0); // 使用较小的平坦度
        List<Point2D.Double> intersections = new ArrayList<>();
        double[] coords = new double[6];

        // --- VVVV 修正变量初始化 VVVV ---
        Point2D.Double move_to = null; // 路径的起始点
        Point2D.Double last_point = null; // 上一个顶点
        // --- ^^^^ ---

        while (!pathIterator.isDone()) {
            int type = pathIterator.currentSegment(coords);
            Point2D.Double currentPoint = null; // 当前段的目标点
            Point2D.Double p1 = null; // 当前要检查的线段的起点
            Point2D.Double p2 = null; // 当前要检查的线段的终点

            switch (type) {
                case PathIterator.SEG_MOVETO:
                    move_to = new Point2D.Double(coords[0], coords[1]);
                    last_point = move_to; // 更新 last_point
                    // SEG_MOVETO 不形成线段，直接进入下一次循环
                    pathIterator.next();
                    continue; // 跳过后续的线段检查

                case PathIterator.SEG_LINETO:
                    currentPoint = new Point2D.Double(coords[0], coords[1]);
                    p1 = last_point; // 线段起点是上一个点
                    p2 = currentPoint; // 线段终点是当前点
                    break;

                case PathIterator.SEG_CLOSE:
                    // 闭合路径形成连接 last_point 和 move_to 的线段
                    if (move_to != null) { // 必须要有起始点才能闭合
                        p1 = last_point;
                        p2 = move_to;
                        currentPoint = move_to; // 更新 currentPoint 以便更新 last_point
                    }
                    break;

                // 可以选择性地处理 SEG_QUADTO 和 SEG_CUBICTO，或者忽略它们
                // 忽略曲线可能导致细微的不精确，但在很多情况下足够了
                case PathIterator.SEG_QUADTO:
                case PathIterator.SEG_CUBICTO:
                    // 简单处理：将曲线的终点视为直线段的终点
                    // 对于二次曲线，终点是 coords[2], coords[3]
                    // 对于三次曲线，终点是 coords[4], coords[5]
                    int endCoordIndex = (type == PathIterator.SEG_QUADTO) ? 2 : 4;
                    if (coords.length > endCoordIndex + 1) { // 确保坐标数组足够长
                        currentPoint = new Point2D.Double(coords[endCoordIndex], coords[endCoordIndex + 1]);
                        p1 = last_point;
                        p2 = currentPoint;
                    }
                    break;

                default:
                    // 未知类型，忽略
                    break;
            }

            // --- VVVV 核心修复：添加 Null 检查 VVVV ---
            // 只有当 p1 和 p2 都有效时，才进行交点计算
            if (p1 != null && p2 != null) {
                Line2D.Double edge = new Line2D.Double(p1, p2); // 创建障碍物的边
                Point2D intersection = getLineLineIntersectionPoint(line, edge); // 计算交点
                if (intersection != null) {
                    intersections.add(new Point2D.Double(intersection.getX(), intersection.getY()));
                }
            } else if (type != PathIterator.SEG_MOVETO) { // 排除正常的 MOVETO
                // 如果 p1 或 p2 是 null (且不是因为 MOVETO)，打印警告
                System.err.println("警告: 在 getLineShapeIntersections 中跳过边检查，因为端点为 null (p1=" + p1 + ", p2=" + p2
                        + ", type=" + type + ")");
            }
            // --- ^^^^ 修复结束 ^^^^ ---

            // 更新 last_point 以供下一次迭代使用
            if (currentPoint != null) {
                last_point = currentPoint;
            }
            // (对于 SEG_CLOSE, currentPoint 已经是 move_to, 所以 last_point 会正确更新)

            pathIterator.next(); // 移动到路径的下一段
        } // 结束 while 循环

        // ... (后续排序和返回交点的代码保持不变) ...
        if (intersections.isEmpty())
            return null;
        intersections.sort(Comparator.comparingDouble(p -> line.getP1().distanceSq(p)));
        if (intersections.size() == 1)
            return new Point2D.Double[] { intersections.get(0), intersections.get(0) };
        return new Point2D.Double[] { intersections.get(0), intersections.get(intersections.size() - 1) };
    }

    /**
     * (从 GameState 复制) 计算两条线段的交点。
     */
    private static Point2D getLineLineIntersectionPoint(Line2D.Double line1, Line2D.Double line2) {
        double x1 = line1.getX1(), y1 = line1.getY1(), x2 = line1.getX2(), y2 = line1.getY2();
        double x3 = line2.getX1(), y3 = line2.getY1(), x4 = line2.getX2(), y4 = line2.getY2();
        double den = (x1 - x2) * (y3 - y4) - (y1 - y2) * (x3 - x4);
        if (den == 0)
            return null;
        double t = ((x1 - x3) * (y3 - y4) - (y1 - y3) * (x3 - x4)) / den;
        double u = -((x1 - x2) * (y1 - y3) - (y1 - y2) * (x1 - x3)) / den;
        if (t >= 0 && t <= 1 && u >= 0 && u <= 1) { // 确保检查范围 [0, 1]
            return new Point2D.Double(x1 + t * (x2 - x1), y1 + t * (y2 - y1));
        }
        return null;
    }

    /**
     * 第一步：将所有障碍物光栅化到一个 2D 布尔网格中。
     * 
     * @return boolean[][] grid, 'true' 表示该单元格被障碍物阻挡。
     */
    private boolean[][] rasterizeObstaclesToGrid(int gridSize) {
        int gridW = (int) Math.ceil((double) mapData.getWidth() / gridSize);
        int gridH = (int) Math.ceil((double) mapData.getHeight() / gridSize);
        boolean[][] blockedGrid = new boolean[gridW][gridH];

        for (MapData.ShapeWrapper wrapper : mapData.getObstacles()) {
            Shape shape = mapPanel.createShapeFromWrapper(wrapper);
            Rectangle bounds = shape.getBounds();

            // 遍历障碍物包围盒所覆盖的网格单元
            int startX = Math.max(0, bounds.x / gridSize);
            int endX = Math.min(gridW, (bounds.x + bounds.width) / gridSize + 1);
            int startY = Math.max(0, bounds.y / gridSize);
            int endY = Math.min(gridH, (bounds.y + bounds.height) / gridSize + 1);

            for (int gy = startY; gy < endY; gy++) {
                for (int gx = startX; gx < endX; gx++) {
                    // 检查该单元格的中心点是否在障碍物内部
                    int cellCenterX = gx * gridSize + gridSize / 2;
                    int cellCenterY = gy * gridSize + gridSize / 2;
                    if (shape.contains(cellCenterX, cellCenterY)) {
                        blockedGrid[gx][gy] = true;
                    }
                }
            }
        }
        return blockedGrid;
    }

    /**
     * 第二步：从点击点开始运行BFS（广度优先搜索）来“泼洒”禁区。
     * 
     * @param clickPoint 鼠标点击的地图坐标
     * @param maxDepth   用户输入的BFS层数
     */
    private void runForbiddenZoneBFS(Point clickPoint, int maxDepth, Set<Point> targetSet, int gridSize) {
        // 1. 获取障碍物网格
        boolean[][] blockedGrid = rasterizeObstaclesToGrid(gridSize);
        int gridW = blockedGrid.length;
        if (gridW == 0)
            return;
        int gridH = blockedGrid[0].length;

        // 2. 获取起始单元格
        int startX = clickPoint.x / gridSize;
        int startY = clickPoint.y / gridSize;

        // 安全检查：不能从界外或障碍物内部开始
        if (startX < 0 || startX >= gridW || startY < 0 || startY >= gridH || blockedGrid[startX][startY]) {
            return;
        }

        // 3. BFS 初始化
        Queue<Point> queue = new LinkedList<>();
        Queue<Integer> depthQueue = new LinkedList<>();
        Set<Point> visited = targetSet; // 直接使用并修改传入的Set

        Point startCell = new Point(startX, startY);
        if (!visited.contains(startCell)) {
            queue.add(startCell);
            depthQueue.add(0);
            visited.add(startCell);
        }

        int[] dx = { 0, 0, 1, -1 }; // 邻居偏移 (上, 下, 右, 左)
        int[] dy = { 1, -1, 0, 0 };

        // 4. 运行 BFS
        while (!queue.isEmpty()) {
            Point cell = queue.poll();
            int depth = depthQueue.poll();

            // 如果达到最大深度，停止“泼洒”
            if (depth >= maxDepth) {
                continue;
            }

            // 检查4个邻居
            for (int i = 0; i < 4; i++) {
                int nx = cell.x + dx[i];
                int ny = cell.y + dy[i];
                Point neighbor = new Point(nx, ny);

                // 检查：在界内 + 不是障碍物 + 没访问过
                if (nx >= 0 && nx < gridW && ny >= 0 && ny < gridH &&
                        !blockedGrid[nx][ny] && !visited.contains(neighbor)) {
                    visited.add(neighbor); // 标记为已访问 (即添加为禁区)
                    queue.add(neighbor); // 加入队列
                    depthQueue.add(depth + 1); // 深度+1
                }
            }
        }
        // BFS 结束后, mapData.getForbiddenSpawnCells() 已经自动更新了
    }
}