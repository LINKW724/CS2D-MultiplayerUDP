// 定义这个类所在的包路径
package cs2d.AIControl.A;

// 导入AWT的Point2D类，用于处理二维坐标
import java.awt.geom.Line2D;
import java.awt.geom.Point2D;
// 导入AWT相关的类，用于障碍物形状处理
import java.awt.Shape;
import java.awt.geom.Area;
import java.awt.geom.Ellipse2D;
// 导入Java工具类
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedList;
import java.util.List;
import java.util.Map;
import java.util.PriorityQueue;
import java.util.Queue;
import java.util.Set;
import java.util.Random;

// 导入Player类，以便模块可以引用其“所有者”AI
import cs2d.AIControl.BW.MapViz.DynamicPathfinderVisualizer;
import cs2d.playerAndAi.Player;
// 导入AIService中的内部记录类，用于返回决策结果
import cs2d.server.AIService.AIInput;
import cs2d.server.AIService.AIWorldView;
import cs2d.server.AIService.PerceivedPlayer;
// 导入A*寻路所需的游戏状态和地图数据
import cs2d.server.GameMode;
import cs2d.server.GameServer;
import cs2d.server.GameState;
import cs2d.server.MapData;
// [修复] 导入了正确的 java.awt.Rectangle
import java.awt.Rectangle;

/**
 * AI "大脑" 的寻路模块。
 * 负责维护AI的目标路径、处理不同游戏模式下的A*寻路以及预设接力赛寻路逻辑。
 */
public class PathfindingModule {

    // 模块的“所有者” (即这个“大脑”属于哪个AI)
    private final Player owner;
    // 对游戏状态的引用，用于初始化寻路器
    private final GameState gameState;

    // AI要移动到的最终目标坐标
    private Point2D.Double targetPosition;

    // 模块是否被激活（即是否正在寻路）
    private boolean isActive = false;

    // ... (常量 STOPPING_DISTANCE, MOVE_THRESHOLD 保持不变)
    private static final double STOPPING_DISTANCE = 20.0;
    private static final double STOPPING_DISTANCE_SQ = STOPPING_DISTANCE * STOPPING_DISTANCE;
    private static final double MOVE_THRESHOLD = 1.0;

    // --- A* 寻路相关字段 ---
    public final Pathfinder pathfinder;
    /** [修改] 当前正在遵循的 *分段* 路径 (例如 从P1到P4) */
    private List<Node> currentPath;
    private int currentPathIndex;

    // --- [新] 预设寻路模块 ---
    private final PresetPathModule presetPathModule;
    /** 此生是否已使用过预设路径 (死亡后重置) */
    private boolean hasUsedPresetPathThisLife = false;
    /** 当前是否正在跟随预设路径 (用于被大脑取消) */
    private boolean isFollowingPresetPath = false;
    /** AI出生点附近的半径 (用于触发预设路径) */
    private static final double SPAWN_VICINITY_RADIUS = 350.0;
    private static final double SPAWN_VICINITY_RADIUS_SQ = SPAWN_VICINITY_RADIUS * SPAWN_VICINITY_RADIUS;

    // --- [新] "接力赛" 状态字段 ---
    /** 预设路径的关键点列表 (例如 [P1, P2 ... P10]) */
    private List<Point2D.Double> presetKeyPoints;
    /** "接力赛" 当前的目标点索引 (例如 0 代表 P1) */
    private int presetKeyPointIndex;

    // "跳过近点" 的距离阈值 (100像素)
    private static final double MIN_DIST_SQ_FOR_NEXT_SEGMENT = 100.0 * 100.0;
    // --- 寻路转向速度 ---
    /** 寻路逻辑期望的最终朝向 (弧度) */
    private double pathTargetAngle;
    /** 经过平滑处理后，AI当前的实际朝向 (弧度) */
    private double pathCurrentAngle;
    /** AI 寻路时的固定转向速度 (每帧10度) */
    private static final double PATH_TURN_SPEED = Math.toRadians(10.0);

    /**
     * 构造函数
     * 
     * @param owner 这个模块所属的Player (AI)
     */
    public PathfindingModule(Player owner) {
        this.owner = owner;
        this.gameState = owner.getGameState();

        if (this.gameState != null) {
            this.pathfinder = new Pathfinder(this.gameState, (int) Player.SIZE);

            // [新] 初始化和加载预设寻路模块
            this.presetPathModule = new PresetPathModule();

            // ===============================================================
            // [修复] 在传入 mapName 之前，必须先移除 .json 后缀
            // ===============================================================

            String fullMapName = GameServer.getMapName();
            String baseMapName = fullMapName; // 默认值

            if (fullMapName != null && fullMapName.endsWith(".json")) {
                baseMapName = fullMapName.substring(0, fullMapName.length() - ".json".length());
            }

            // 3. 使用清理后的 "baseMapName" (例如 "mirage_3x无粗") 来加载
            this.presetPathModule.loadMap(baseMapName);
            // ===============================================================
            // [修复结束]
            // ===============================================================

        } else {
            this.pathfinder = null;
            this.presetPathModule = null;
            System.err.println("PathfindingModule: GameState is null for AI: " + owner.name);
        }

        this.pathTargetAngle = owner.angle;
        this.pathCurrentAngle = owner.angle;

        this.targetPosition = null;
    }

    public void reset() {
        clearPath(); // 清除当前路径
        this.targetPosition = null; // 清除最终目标
        this.isActive = false; // 设为非激活状态

        // 死亡重置
        this.hasUsedPresetPathThisLife = false;
        this.isFollowingPresetPath = false;
        this.presetKeyPoints = null;
        this.pathTargetAngle = owner.angle;
        this.pathCurrentAngle = owner.angle;
        this.presetKeyPointIndex = 0;
    }

    /**
     * 大脑调用的方法，用于在看到敌人时取消当前的预设路径。
     * （取消操作会延迟到下一帧 update() 时再计算新路径）。
     */
    public void cancelPresetPath() {
        if (!isFollowingPresetPath)
            return;

        // System.out.println("AI [" + owner.name + "] 取消了预设路径。");
        this.isFollowingPresetPath = false;
        this.presetKeyPoints = null;
        this.presetKeyPointIndex = 0;

        // [修复] 只设置状态，不立即计算路径
        clearPath(); // 清除当前的分段路径
        // 让下一帧的 update() 方法去根据 targetPosition 计算新路径
        // 确保 isActive 仍然是 true (如果 targetPosition 有效)
        this.isActive = (this.targetPosition != null);
    }

    /**
     * 检查 AI 当前是否正在按照预设路线行进。
     * 
     * @return 如果正在执行预设路径则返回true
     */
    public boolean isFollowingPresetPath() {
        return this.isFollowingPresetPath;
    }

    /**
     * 为AI设置一个新的移动目标，并优先尝试寻路预设地图的热点路径。
     * 
     * @param target 最终要抵达的目标坐标
     */
    public void setTarget(Point2D.Double target) {

        // 如果正在跟随预设路径，不允许被新的非空target覆盖 (除非被cancel)
        if (this.isFollowingPresetPath && target != null) {
            // 允许覆盖 *最终* 目标 (targetPosition)，但不允许打断接力赛
            this.targetPosition = target;
            return;
        }

        // [BUG FIX]: Prevent continuous target updates from RLlib from resetting
        // pathing every frame.
        if (this.isActive && target != null && this.targetPosition != null && this.currentPath != null) {
            if (this.targetPosition.distanceSq(target) < 2500.0) { // 50 pixels (approx 1-2 grids) tolerance
                this.targetPosition = target; // update target gently without destroying current A* path
                return;
            }
        }

        this.targetPosition = target; // 总是更新最终目标

        if (target == null) {
            this.isActive = false;
            this.isFollowingPresetPath = false; // [新]
            this.presetKeyPoints = null;
            clearPath();
        } else {

            // [新] 1. 尝试使用预设路径
            boolean presetPathSet = trySetPresetPath(target);

            if (presetPathSet) {
                // 成功启动了"接力赛"
            } else {
                // [新] 2. 回退到标准A*
                this.isFollowingPresetPath = false;
                this.presetKeyPoints = null;
                this.isActive = setNewPath(target);
            }
        }
    }

    /**
     * [新] 检查是否满足条件，并尝试设置一条预设路径
     * [修改] 支持 TDM 和 Demolition 模式
     * 
     * @param targetPos AI的最终目标 (可能是敌方出生点，也可能是包点)
     * @return 如果成功设置了预设路径，返回 true
     */
    // 在 PathfindingModule.java 中
    /**
     * 检查是否满足条件，并尝试设置一条基于当前模式的预设路径。
     * 支持 TDM, Demolition 和 Deathmatch 模式的判断。
     * 
     * @param targetPos AI的最终目标 (可能为敌方出生点或包点等)
     * @return 如果成功设置了由关键点组成的预设路径片段，则返回true
     */
    private boolean trySetPresetPath(Point2D.Double targetPos) {
        // 1. 检查基本条件
        if (hasUsedPresetPathThisLife || presetPathModule == null || !presetPathModule.isLoaded()
                || gameState == null) {
            return false;
        }

        // 2. 检查AI是否在出生点附近 (通用条件)
        List<Rectangle> mySpawns = (owner.team == Player.Team.T) ? gameState.getTSpawnAreas()
                : gameState.getCtSpawnAreas();
        boolean nearMySpawn = isNearSpawn(owner.position, mySpawns);
        if (!nearMySpawn)
            return false;

        // 3. 根据游戏模式判断目标类型并选择路径
        DynamicPathfinderVisualizer.PathType selectedPathType = null;

        // [!! 核心修复 !!]
        // 将 TDM 和 DEATHMATCH 合并到同一个 if 块中
        if (gameState.getGameMode().equals(GameMode.TEAM_DEATHMATCH)
                || gameState.getGameMode().equals(GameMode.DEATHMATCH)) {
            // --- TDM / Deathmatch 逻辑 ---
            List<Rectangle> enemySpawns = (owner.team == Player.Team.T) ? gameState.getCtSpawnAreas()
                    : gameState.getTSpawnAreas();
            boolean nearEnemySpawn = isNearSpawn(targetPos, enemySpawns);
            if (nearEnemySpawn) {
                selectedPathType = (owner.team == Player.Team.T) ? DynamicPathfinderVisualizer.PathType.TDM_T_CT
                        : DynamicPathfinderVisualizer.PathType.TDM_CT_T;
            }
        } else if (gameState.getGameMode().equals(GameMode.DEMOLITION)) {
            // --- Demolition 逻辑 ---
            Rectangle siteA = gameState.getBombSiteA();
            Rectangle siteB = gameState.getBombSiteB();

            // 检查 targetPos 是否在矩形 *内部*
            boolean nearSiteA = (siteA != null && siteA.contains(targetPos));
            boolean nearSiteB = (siteB != null && siteB.contains(targetPos));

            if (nearSiteA) {
                selectedPathType = (owner.team == Player.Team.T) ? DynamicPathfinderVisualizer.PathType.DEMO_T_A
                        : DynamicPathfinderVisualizer.PathType.DEMO_CT_A;
            } else if (nearSiteB) {
                selectedPathType = (owner.team == Player.Team.T) ? DynamicPathfinderVisualizer.PathType.DEMO_T_B
                        : DynamicPathfinderVisualizer.PathType.DEMO_CT_B;
            }
        }

        // 4. 如果找到了合适的路径类型
        if (selectedPathType != null) {
            // 尝试获取预设路径点
            List<Point2D.Double> keyPoints = presetPathModule.getRandomPresetKeyPoints(owner.team, selectedPathType);

            if (keyPoints != null && !keyPoints.isEmpty()) {
                // 5. 成功获取"接力赛"路径！
                this.presetKeyPoints = keyPoints;
                this.presetKeyPointIndex = 0; // 指向 P1
                this.isFollowingPresetPath = true;
                this.hasUsedPresetPathThisLife = true;

                // 6. 启动接力赛的第一棒
                this.isActive = true; // 激活模块
                clearPath(); // 确保 currentPath 为空，以触发 update() 中的接力赛逻辑
                return true;
            } else {
                // (日志) System.out.println("AI [" + owner.name + "] 无法获取类型 " + selectedPathType
                // + " 的预设路径点。");
            }
        }

        return false; // 没有触发预设路径
    }

    /**
     * [新] 辅助方法：检查一个点是否在任何一个出生点矩形附近
     */
    private boolean isNearSpawn(Point2D.Double pos, List<Rectangle> spawns) {
        if (pos == null || spawns == null || spawns.isEmpty()) {
            return false;
        }
        for (Rectangle rect : spawns) {
            if (rect == null)
                continue;
            double closestX = Math.max(rect.getMinX(), Math.min(pos.x, rect.getMaxX()));
            double closestY = Math.max(rect.getMinY(), Math.min(pos.y, rect.getMaxY()));
            double dx = pos.x - closestX;
            double dy = pos.y - closestY;
            double squaredDistance = (dx * dx) + (dy * dy);
            if (squaredDistance < SPAWN_VICINITY_RADIUS_SQ) {
                return true;
            }
        }
        return false;
    }

    /**
     * 检查模块当前是否有活动的移动目标。
     */
    public boolean isActive() {
        return this.isActive && (this.targetPosition != null || this.isFollowingPresetPath);
    }

    /**
     * 获取当前寻路模块的最终目标位置。
     */
    public Point2D.Double getTargetPosition() {
        return this.targetPosition;
    }

    // =========================================================================
    // --- 核心逻辑 ---
    // =========================================================================

    /**
     * 寻路模块的核心每帧更新逻辑（“思考”）。
     * 执行路径跟随、"接力赛"的分段切换或由于目标丢失而发起的重新A*寻路。
     * 
     * @param worldView AI当前的感知视野信息
     * @return AI的按键输入及转向指令
     */
    public AIInput update(AIWorldView worldView) {

        // --- [新] 接力赛管理 ---
        if (isFollowingPresetPath) {
            // 检查当前 *分段* 路径是否已走完
            if (currentPath == null || currentPathIndex >= currentPath.size()) {

                // === 我们已到达上一个目标点, 准备找 *下一个* ===

                // [核心修复] 循环跳过所有我们 "已经路过" (太近) 的点
                while (presetKeyPoints != null && presetKeyPointIndex < presetKeyPoints.size()) {

                    Point2D.Double nextSegmentTarget = presetKeyPoints.get(presetKeyPointIndex);

                    // 检查这个点是否离我们太近
                    if (owner.position.distanceSq(nextSegmentTarget) < MIN_DIST_SQ_FOR_NEXT_SEGMENT) {
                        // 这个点太近了, 算作 "已到达", 跳过它
                        presetKeyPointIndex++;
                    } else {
                        // 找到了一个足够远的目标点！
                        // System.out.println("AI [" + owner.name + "] 开始接力赛第 " + (presetKeyPointIndex +
                        // 1) + " 段。");
                        this.isActive = setNewPath(nextSegmentTarget); // 计算 P(n) -> P(n+k)
                        break; // 退出 while 循环, 等待 A* 计算完成
                    }
                }

                // [检查] while 循环是否跑完了所有点
                if (presetKeyPoints != null && presetKeyPointIndex >= presetKeyPoints.size()) {
                    // [接力完成] 跑完了所有关键点
                    // System.out.println("AI [" + owner.name + "] 接力赛完成。");
                    this.isFollowingPresetPath = false;
                    this.presetKeyPoints = null;
                    // 计算 *最后一段* (P10 -> 最终目标)
                    this.isActive = setNewPath(this.targetPosition);
                }
            }
        }
        // --- [新] 接力赛管理结束 ---

        // 如果模块未激活、或(在非接力赛时)没有最终目标、或(在有接力赛时)分段路径丢失
        if (!isActive || (!isFollowingPresetPath && targetPosition == null) || currentPath == null
                || currentPath.isEmpty() || currentPathIndex >= currentPath.size()) {

            boolean pathFound = false;

            // 如果是在接力赛中被打断 (例如被推开导致路径丢失, 且还没到达目标)
            if (isFollowingPresetPath && presetKeyPoints != null && presetKeyPointIndex < presetKeyPoints.size()) {
                // 重新计算当前分段
                Point2D.Double currentSegmentTarget = presetKeyPoints.get(presetKeyPointIndex);
                pathFound = setNewPath(currentSegmentTarget);
            }
            // 如果是在标准寻路中被打断
            else if (isActive && targetPosition != null && !isFollowingPresetPath) {
                pathFound = setNewPath(this.targetPosition);
            }

            // [核心修复] 如果没有找到新路径，或者根本没尝试找（例如 isActive 为 false），强制清理状态
            if (!pathFound) {
                clearPath();
                this.isActive = false; // 确保彻底停止
                // 停止时，将目标角度设为当前角度 (停止转向)
                this.pathTargetAngle = this.pathCurrentAngle;
            } else {
                this.isActive = true;
            }
        }

        // --- 跟随 *分段* 路径移动 ---
        // followPath() 现在只返回按键
        List<String> keys;
        // [修复] 增加对 currentPathIndex 的边界检查
        if (this.isActive && this.currentPath != null && currentPathIndex < currentPath.size()) {
            keys = followPath();
        } else {
            // 确保如果没路可走，按键为空
            keys = new ArrayList<>();
            this.pathTargetAngle = this.pathCurrentAngle; // 保持当前角度
        }

        // 无论如何，都更新一次平滑角度
        updatePathfindingAngle();

        // 返回最终决策
        // 移动键(keys)被立即返回，而角度(pathCurrentAngle)会逐渐跟上
        // 这实现了 "不影响移动" 的转向限制
        return new AIInput(keys, this.pathCurrentAngle, false, false, false);
    }

    /**
     * AI 跟随当前 `currentPath` (分段路径) 移动的具体执行逻辑。
     * 
     * @return 只需要触发物理移动的键盘按键列表 (W/A/S/D)
     */
    private List<String> followPath() {
        // 1. 获取当前子目标 (路径上的下一个节点)
        Node targetNode = currentPath.get(currentPathIndex);
        Point2D.Double nextNodePos = pathfinder.nodeToWorld(targetNode);

        // 2. 检查是否已到达该子目标
        // [优化] 将判定半径从 0.5 倍 SIZE 扩大到 0.8 倍，防止在墙边因为无法贴近中心而卡死
        double reachThresholdSq = (Player.SIZE * 0.8) * (Player.SIZE * 0.8);
        if (owner.position.distanceSq(nextNodePos) < reachThresholdSq) {
            currentPathIndex++; // 移动到路径的下一个节点

            // 3. 检查是否已走完 *当前分段* 路径
            if (currentPathIndex >= currentPath.size()) {
                // [修改] 路径分段已走完
                // 停止移动 (等待下一帧 update() 计算新分段)
                // [新] 停止时，将目标角度设为当前角度 (停止转向)
                this.pathTargetAngle = this.pathCurrentAngle;
                return new ArrayList<>(); // [修改] 返回空按键
            } else {
                // 路径未完，获取新的子目标
                targetNode = currentPath.get(currentPathIndex);
                nextNodePos = pathfinder.nodeToWorld(targetNode);
            }
        }

        // 4. 计算朝向下一个子目标(nextNodePos)的向量
        double deltaX = nextNodePos.x - owner.position.x;
        double deltaY = nextNodePos.y - owner.position.y;

        // 5. 根据向量计算 WASD 平移动作
        List<String> keys = new ArrayList<>();
        if (deltaY < -MOVE_THRESHOLD) {
            keys.add("W");
        } else if (deltaY > MOVE_THRESHOLD) {
            keys.add("S");
        }
        if (deltaX < -MOVE_THRESHOLD) {
            keys.add("A");
        } else if (deltaX > MOVE_THRESHOLD) {
            keys.add("D");
        }

        // 6. [修改] 计算并设置朝向 (但不返回)
        this.pathTargetAngle = Math.atan2(deltaY, deltaX);

        // 7. [修改] 返回移动按键
        return keys;
    }

    /**
     * 计算一条从 AI 当前位置到目标点的新路径。(标准A*)
     * (保持不变)
     */
    private boolean setNewPath(Point2D.Double targetPos) {
        // 安全检查
        if (pathfinder == null || owner == null || targetPos == null) {
            clearPath();
            return false;
        }

        pathfinder.resetCosts();
        this.currentPath = pathfinder.findPath(owner.position, targetPos);
        this.currentPathIndex = 0;

        if (this.currentPath == null || this.currentPath.isEmpty()) {
            clearPath();
            return false;
        }

        return true;
    }

    /**
     * 清理当前的寻路状态。
     */
    private void clearPath() {
        this.currentPath = null;
        this.currentPathIndex = 0;
    }

    /**
     * 平滑地更新 AI 的寻路朝向角度。
     * (此逻辑从 AttackModule 复制而来，但使用固定的转向速度)
     */
    private void updatePathfindingAngle() {
        // 规范化角度到 [-PI, PI] 区间
        while (pathTargetAngle <= -Math.PI)
            pathTargetAngle += 2 * Math.PI;
        while (pathTargetAngle > Math.PI)
            pathTargetAngle -= 2 * Math.PI;
        while (pathCurrentAngle <= -Math.PI)
            pathCurrentAngle += 2 * Math.PI;
        while (pathCurrentAngle > Math.PI)
            pathCurrentAngle -= 2 * Math.PI;

        // 计算最短角度差
        double angleDifference = pathTargetAngle - pathCurrentAngle;
        if (angleDifference > Math.PI)
            angleDifference -= 2 * Math.PI; // 走另一边更快
        if (angleDifference < -Math.PI)
            angleDifference += 2 * Math.PI; // 走另一边更快

        // 应用转向速度限制 (使用 PATH_TURN_SPEED)
        double turnAmount = Math.max(-PATH_TURN_SPEED, Math.min(PATH_TURN_SPEED, angleDifference));

        // 更新当前角度
        this.pathCurrentAngle += turnAmount;
    }

    /**
     * [便捷方法] 检查从 AI 当前位置到目标点是否有直接视线。
     * (保持不变)
     */
    public boolean hasLineOfSight(Point2D.Double targetPos) {
        if (pathfinder == null) {
            System.err.println("警告: PathfindingModule 中的 pathfinder 未初始化，无法检查视线！");
            return true;
        }
        return pathfinder.hasLineOfSightToPointFromPoint(owner.position, targetPos);
    }

    /**
     * A*寻路算法节点类
     * (保持不变)
     */
    public static class Node {
        int x, y;
        public int baseCost;
        int currentCost;
        int gCost;
        int hCost;
        Node parent;

        Node(int baseCost, int x, int y) {
            this.baseCost = baseCost;
            this.currentCost = baseCost;
            this.x = x;
            this.y = y;
        }

        int fCost() {
            return gCost + hCost;
        }
    }

    /**
     * 允许“大脑”检查一个世界级坐标点是否可行走或是否与障碍物重叠。
     * 
     * @param worldPos 待检测的世界坐标
     * @return 如果没有遇墙壁则返回 true
     */
    public boolean isWalkable(Point2D.Double worldPos) {
        if (pathfinder == null) {
            return false;
        }
        return pathfinder.isWalkable(worldPos);
    }

    // =============内部类===============
    /**
     * 寻路器类，封装了A*网格和算法。
     * (保持不变)
     */
    public static class Pathfinder {

        public static final int WALL_COST = 1000000;

        private static Node[][] gridCache = null;
        private static int cachedGridWidth = 0;
        private static int cachedGridHeight = 0;
        private static final Object cacheLock = new Object();

        private final Map<MapData.ShapeWrapper, Shape> shapeCache;
        public final Node[][] grid;
        private final List<Shape> obstacles;
        public final int gridWidth;
        public final int gridHeight;
        private final int cellSize;
        private final int worldWidth, worldHeight;

        public Pathfinder(GameState gameState, int cellSize) {
            if (gameState == null) {
                throw new IllegalArgumentException("GameState cannot be null for Pathfinder.");
            }
            if (cellSize <= 0) {
                throw new IllegalArgumentException("Cell size must be positive.");
            }

            this.worldWidth = gameState.width;
            this.worldHeight = gameState.height;
            this.cellSize = cellSize;

            if (this.worldWidth <= 0 || this.worldHeight <= 0) {
                throw new IllegalArgumentException("Invalid map dimensions from GameState.");
            }
            this.gridWidth = (int) Math.ceil((double) this.worldWidth / cellSize);
            this.gridHeight = (int) Math.ceil((double) this.worldHeight / cellSize);

            this.obstacles = new ArrayList<>();
            this.shapeCache = new HashMap<>();

            if (gameState.getObstacleWrappers() != null) {
                for (MapData.ShapeWrapper wrapper : gameState.getObstacleWrappers()) {
                    if (wrapper == null)
                        continue;
                    Shape shape = GameState.convertWrapperToShape(wrapper);
                    if (shape != null) {
                        this.obstacles.add(shape);
                        this.shapeCache.put(wrapper, shape);
                    }
                }
            }

            synchronized (cacheLock) {
                if (gridCache != null && cachedGridWidth == this.gridWidth && cachedGridHeight == this.gridHeight) {
                    this.grid = createGridFromCache();
                } else {
                    this.grid = new Node[gridWidth][gridHeight];
                    createGrid();
                    gridCache = this.grid;
                    cachedGridWidth = this.gridWidth;
                    cachedGridHeight = this.gridHeight;
                }
            }
        }

        public Shape getShapeForWrapper(MapData.ShapeWrapper wrapper) {
            return shapeCache.get(wrapper);
        }

        public boolean hasLineOfSightToPointFromPoint(Point2D.Double startPos, Point2D.Double endPos) {
            if (startPos == null || endPos == null || obstacles == null || obstacles.isEmpty()) {
                return true;
            }
            Line2D.Double line = new Line2D.Double(startPos, endPos);
            for (Shape obs : obstacles) {
                if (obs.intersects(line.getBounds2D())
                        && GameState.getLineShapeIntersections(line, obs) != null) {
                    return false;
                }
            }
            return true;
        }

        private Node[][] createGridFromCache() {
            Node[][] newGrid = new Node[this.gridWidth][this.gridHeight];
            for (int x = 0; x < this.gridWidth; x++) {
                for (int y = 0; y < this.gridHeight; y++) {
                    Node cachedNode = gridCache[x][y];
                    newGrid[x][y] = new Node(cachedNode.baseCost, cachedNode.x, cachedNode.y);
                }
            }
            return newGrid;
        }

        /**
         * 创建寻路网格
         * * 演进历史：
         * 1. Area.intersect() -> 极慢 (30s)，因为进行了复杂的几何切割运算。
         * 2. Shape.contains() -> 极快 (80ms)，但可能导致AI在边缘穿模，因为它只检测中心点。
         * 3. Shape.intersects() -> [当前选择] 性能与精度的最佳平衡。
         * 它检测"格子矩形"与"障碍物"是否重叠，不产生垃圾对象，速度极快且不会穿墙。
         * 速度：依然使用 fast intersects，保持秒开 (200ms)。
         * 精度：引入“收缩检测”，允许 AI 贴墙走，解决狭窄通道被误判堵死的问题。
         */
        private void createGrid() {
            double halfSize = cellSize / 2.0;

            // =======================================================
            // [核心参数调整] 收缩检测范围
            // =======================================================
            // 1.0 = 检测整个格子 (最严格，防止贴墙和卡墙)
            // 0.5 = 只检测格子中心 50% 的区域 (容易贴墙，适合极窄地图但易卡住)
            double shrinkFactor = 1.0;

            // 计算实际检测的矩形大小
            double checkSize = cellSize * shrinkFactor;
            // 计算偏移量，确保检测框居中
            double offset = checkSize / 2.0;

            for (int x = 0; x < gridWidth; x++) {
                for (int y = 0; y < gridHeight; y++) {
                    // 1. 计算格子世界中心
                    double worldX = x * cellSize + halfSize;
                    double worldY = y * cellSize + halfSize;

                    int cost = 0;

                    // 2. 计算居中的、缩小后的检测框左上角
                    double checkX = worldX - offset;
                    double checkY = worldY - offset;

                    // 3. 遍历障碍物
                    for (Shape obs : obstacles) {
                        // 只检测核心区域是否撞墙
                        if (obs.intersects(checkX, checkY, checkSize, checkSize)) {
                            cost = WALL_COST;
                            break;
                        }
                    }

                    this.grid[x][y] = new Node(cost, x, y);
                }
            }
        }

        /**
         * 这是很严谨的，但是很慢。
         * 
         * @param startWorld
         * @param endWorld
         * @return
         */
        // private void createGrid() {
        // for (int x = 0; x < gridWidth; x++) {
        // for (int y = 0; y < gridHeight; y++) {
        // Point2D.Double worldPoint = nodeToWorld(new Node(0, x, y));
        // int cost = 0;
        // double checkRadius = (cellSize / 2.0) * 1.1;
        // Ellipse2D.Double playerBoundsAtNode = new Ellipse2D.Double(worldPoint.x -
        // checkRadius, worldPoint.y - checkRadius, checkRadius * 2, checkRadius * 2);
        // Area checkArea = new Area(playerBoundsAtNode);
        //
        // for (Shape obs : obstacles) {
        // Area obsArea = new Area(obs);
        // obsArea.intersect(checkArea);
        // if (!obsArea.isEmpty()) {
        // cost = WALL_COST;
        // break;
        // }
        // }
        // this.grid[x][y] = new Node(cost, x, y);
        // }
        // }
        // }

        public List<Node> findPath(Point2D.Double startWorld, Point2D.Double endWorld) {
            Node startNode = worldToNode(startWorld);
            Node endNode = worldToNode(endWorld);

            if (startNode.currentCost >= WALL_COST) {
                startNode = findNearestWalkableNode(startNode);
                if (startNode == null)
                    return null;
            }
            if (endNode.currentCost >= WALL_COST) {
                endNode = findNearestWalkableNode(endNode);
                if (endNode == null)
                    return null;
            }

            // [彻底修复并发Bug] 使用本地 Map 存储每个线程的路径搜索状态，不再修改全局 Node 对象
            Map<Node, Integer> gCostMap = new HashMap<>();
            Map<Node, Integer> hCostMap = new HashMap<>();
            Map<Node, Node> cameFrom = new HashMap<>();

            PriorityQueue<Node> openSet = new PriorityQueue<>(
                    Comparator.comparingInt(n -> gCostMap.getOrDefault(n, 1000000) + hCostMap.getOrDefault(n, 0)));
            Set<Node> closedSet = new HashSet<>();

            gCostMap.put(startNode, 0);
            hCostMap.put(startNode, getDistance(startNode, endNode));
            openSet.add(startNode);

            while (!openSet.isEmpty()) {
                Node currentNode = openSet.poll();

                if (currentNode == endNode) {
                    return retracePath(startNode, endNode, cameFrom);
                }

                closedSet.add(currentNode);

                for (Node neighbor : getNeighbors(currentNode)) {
                    if (neighbor.currentCost >= WALL_COST || closedSet.contains(neighbor))
                        continue;

                    int tentativeGCost = gCostMap.get(currentNode) + getDistance(currentNode, neighbor)
                            + neighbor.currentCost;

                    if (tentativeGCost < gCostMap.getOrDefault(neighbor, Integer.MAX_VALUE)) {
                        cameFrom.put(neighbor, currentNode);
                        gCostMap.put(neighbor, tentativeGCost);
                        hCostMap.put(neighbor, getDistance(neighbor, endNode));

                        // 移除并重新添加以更新优先级队列
                        if (openSet.contains(neighbor)) {
                            openSet.remove(neighbor);
                        }
                        openSet.add(neighbor);
                    }
                }
            }
            return null; // 找不到路径
        }

        private List<Node> retracePath(Node startNode, Node endNode, Map<Node, Node> cameFrom) {
            List<Node> path = new ArrayList<>();
            Node currentNode = endNode;
            while (currentNode != null && currentNode != startNode) {
                path.add(currentNode);
                currentNode = cameFrom.get(currentNode);
            }
            if (currentNode == startNode) {
                Collections.reverse(path);
                return path;
            }
            return null; // 路径中断
        }

        private Node findNearestWalkableNode(Node node) {
            if (node.currentCost < WALL_COST)
                return node;

            Queue<Node> queue = new LinkedList<>();
            Set<Node> visited = new HashSet<>();
            queue.add(node);
            visited.add(node);

            while (!queue.isEmpty()) {
                Node current = queue.poll();
                for (Node neighbor : getNeighbors(current)) {
                    if (neighbor.currentCost < WALL_COST)
                        return neighbor;
                    if (!visited.contains(neighbor)) {
                        visited.add(neighbor);
                        queue.add(neighbor);
                    }
                }
            }
            return null;
        }

        private List<Node> getNeighbors(Node node) {
            List<Node> neighbors = new ArrayList<>();
            for (int x = -1; x <= 1; x++) {
                for (int y = -1; y <= 1; y++) {
                    if (x == 0 && y == 0)
                        continue;
                    int checkX = node.x + x;
                    int checkY = node.y + y;
                    if (checkX >= 0 && checkX < gridWidth && checkY >= 0 && checkY < gridHeight) {
                        neighbors.add(grid[checkX][checkY]);
                    }
                }
            }
            return neighbors;
        }

        private int getDistance(Node a, Node b) {
            int dX = Math.abs(a.x - b.x);
            int dY = Math.abs(a.y - b.y);
            return (dX > dY) ? 14 * dY + 10 * (dX - dY) : 14 * dX + 10 * (dY - dX);
        }

        public Node worldToNode(Point2D.Double worldPos) {
            int x = (int) (worldPos.x / cellSize);
            int y = (int) (worldPos.y / cellSize);
            x = Math.max(0, Math.min(x, gridWidth - 1));
            y = Math.max(0, Math.min(y, gridHeight - 1));
            return grid[x][y];
        }

        public Point2D.Double nodeToWorld(Node node) {
            return new Point2D.Double(
                    (node.x * cellSize) + cellSize / 2.0,
                    (node.y * cellSize) + cellSize / 2.0);
        }

        public List<Shape> getObstacles() {
            return obstacles;
        }

        public boolean isWalkable(Point2D.Double worldPos) {
            if (worldPos == null)
                return false;
            Node node = worldToNode(worldPos);
            return node != null && node.currentCost < WALL_COST;
        }

        public void resetCosts() {
            if (grid == null)
                return;
            for (int x = 0; x < gridWidth; x++) {
                for (int y = 0; y < gridHeight; y++) {
                    if (grid[x][y] != null) {
                        grid[x][y].currentCost = grid[x][y].baseCost;
                    }
                }
            }
        }

        public List<List<Node>> findAlternativePaths(Point2D.Double startWorld, Point2D.Double endWorld, int numPaths,
                int penalty) {
            resetCosts();
            List<List<Node>> allPaths = new ArrayList<>();
            for (int i = 0; i < numPaths; i++) {
                List<Node> path = findPath(startWorld, endWorld);
                if (path == null || path.isEmpty()) {
                    break;
                }
                allPaths.add(path);
                for (Node node : path) {
                    if (node.currentCost < WALL_COST) {
                        if (node.currentCost > Integer.MAX_VALUE - penalty) {
                            node.currentCost = WALL_COST;
                        } else {
                            node.currentCost += penalty;
                        }
                        if (node.currentCost >= WALL_COST) {
                            node.currentCost = WALL_COST - 1;
                        }
                    }
                }
            }
            resetCosts();
            return allPaths;
        }

    } // --- 嵌套的 Pathfinder 类结束 ---
}