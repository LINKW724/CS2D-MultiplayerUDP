package cs2d.AIControl.A;

import cs2d.playerAndAi.Player;
import cs2d.playerAndAi.Vector2D;
import cs2d.playerAndAi.Weapon; // 假设 AIDifficulty 在这个包或已导入
import cs2d.server.AIDifficulty;
import cs2d.server.GameState;
import cs2d.server.AiDiagnostics;
import cs2d.server.MapData;

// 使用 PathfindingModule 的内嵌类
import cs2d.AIControl.A.PathfindingModule.Pathfinder;
import cs2d.server.GameState.CollisionResult; // 假设 CollisionResult 定义在 GameState 中
import cs2d.AIControl.BG.TEAM_DEATHMATCHcontrol.AIState; // 根据错误日志调整 AIState 导入

import java.awt.Shape;
import cs2d.server.Item;
import java.awt.geom.Ellipse2D;
import java.awt.geom.Line2D;
import java.awt.geom.Path2D;
import java.awt.geom.PathIterator;
import java.awt.geom.Point2D;
import java.awt.geom.Rectangle2D;
import java.util.AbstractMap;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Random;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.stream.Collectors;

/**
 * AI 道具投掷控制器 (GrenadeModule)。
 * 负责处理 AI 的道具使用决策、异步轨迹计算和投掷执行。
 * 这是一个有状态的模块，它管理着从决策到投掷的整个生命周期。
 */
public class GrenadeModule {

    // --- 静态资源 ---
    // 共享的后台计算线程池 (所有 AI 实例共用)
    private static final ExecutorService grenadeCalculatorService = Executors.newFixedThreadPool(6);
    // 全局冷却，防止 AI 扎堆计算轨迹
    private static long lastGlobalGrenadeCheckTime = 0;
    private static final long GLOBAL_GRENADE_CHECK_COOLDOWN = 300; // 毫秒
    private static final long GRENADE_COOLDOWN_MS = 7000; // 道具使用冷却
    private static final long GRENADE_THROW_TIMEOUT_MS = 5000; // 投掷动作超时
    public static final double LONG_RANGE_FLASH_MIN_DISTANCE = 300; // 闪光弹最小距离

    // --- 模块核心引用 ---
    private final Player self; // 此模块的所有者 (AI 玩家)
    private final GameState gameState; // 游戏状态引用
    private final Pathfinder pathfinder; // 寻路器引用 (用于物理模拟)
    private final AIDifficulty difficulty; // AI 难度设置
    private final Consumer<String> logger; // 日志记录器
    private final Random rand; // 随机数生成器

    // --- 模块内部状态 ---
    private volatile ModuleState currentState = ModuleState.IDLE;
    private volatile GrenadeThrowPlan pendingGrenadePlan = null; // 异步计算的结果
    private volatile boolean isCalculatingGrenade = false; // 是否正在计算
    private final AtomicLong calculationGeneration = new AtomicLong();
    private long lastGrenadeThrowTime = 0; // 上次投掷时间

    // --- 当前投掷计划的状态 ---
    private Item grenadeToThrow = null;
    private Point2D.Double grenadeTargetPosition = null;
    private Point2D.Double grenadeDecisionPosition = null;
    private double grenadeDecisionAngle = 0;
    private long grenadePlanStartTime = 0; // 计划开始时间 (用于超时)

    /**
     * 模块内部状态机
     */
    private enum ModuleState {
        IDLE, // 空闲
        CALCULATING, // 正在后台计算轨迹
        PREPARING_GRENADE, // 准备投掷 (停稳、瞄准)
        MOVING_TO_THROW_SPOT // 移动到最佳投掷点
    }

    /**
     * 异步计算返回的投掷计划。
     */
    public record GrenadeThrowPlan(Item itemToThrow, Point2D.Double decisionPosition, double decisionAngle,
            Point2D.Double targetPosition) {
    }

    /**
     * 模块返回给 AIController 的指令。
     */
    public enum ActionType {
        DO_NOTHING, // 模块未激活
        MOVE_TO_SPOT, // 指示控制器寻路到特定地点
        HOLD_AND_AIM, // 指示控制器停止移动并瞄准特定角度
        THROW_NOW, // 指示控制器（在瞄准后）立即执行投掷
        SWITCH_SLOT // 指示控制器切换到道具槽位
    }

    /**
     * 封装模块的指令详情。
     */
    public record GrenadeCommand(ActionType action, Point2D.Double pathTarget, double aimAngle, int slot) {
    }

    /**
     * 构造函数
     */
    public GrenadeModule(Player self, GameState gameState, Pathfinder pathfinder, AIDifficulty difficulty,
            Consumer<String> logger, Random rand) {
        this.self = Objects.requireNonNull(self, "GrenadeModule requires a non-null owner.");
        this.gameState = Objects.requireNonNull(gameState, "GrenadeModule requires a non-null gameState.");
        // 注意：这里的 pathfinder 应该是 PathfindingModule.Pathfinder 类型
        this.pathfinder = Objects.requireNonNull(pathfinder, "GrenadeModule requires a non-null pathfinder.");
        this.difficulty = Objects.requireNonNull(difficulty, "GrenadeModule requires a non-null difficulty.");
        this.logger = Objects.requireNonNull(logger, "GrenadeModule requires a non-null logger.");
        this.rand = Objects.requireNonNull(rand, "GrenadeModule requires a non-null rand.");
    }

    /**
     * 模块的主更新方法，由 AIController 每帧调用。
     *
     * @param target          AI 的当前目标敌人
     * @param lkp             敌人的最后已知位置
     * @param controllerState AIController 的当前主状态
     * @param isStationary    AI 物理实体是否已静止
     * @param targetInLoS     AI 是否能看到目标
     * @return 一个 Optional<GrenadeCommand>，如果模块激活，则包含指令。
     */
    public Optional<GrenadeCommand> update(Player target, Point2D.Double lkp, AIState controllerState,
            boolean isStationary, boolean targetInLoS) {

        // 1. 检查超时
        if (currentState != ModuleState.IDLE
                && System.currentTimeMillis() - grenadePlanStartTime > GRENADE_THROW_TIMEOUT_MS) {
            AiDiagnostics.trace("grenadeTimeout", logger,
                    () -> self.name + " 的投掷动作超时（超过5秒），已强制取消。");
            resetGrenadeState();
            return Optional.empty();
        }

        // 2. 检查是否有新计算完成的计划 (移到 switch 之前处理)
        if (pendingGrenadePlan != null) {
            GrenadeThrowPlan plan = this.pendingGrenadePlan;
            this.pendingGrenadePlan = null; // 取出计划

            // 加载计划到模块状态
            this.grenadeToThrow = plan.itemToThrow();
            this.grenadeTargetPosition = plan.targetPosition();
            this.grenadeDecisionPosition = plan.decisionPosition();
            this.grenadeDecisionAngle = plan.decisionAngle();
            this.currentState = ModuleState.PREPARING_GRENADE; // 进入准备状态
            this.grenadePlanStartTime = System.currentTimeMillis(); // 开始计时

            AiDiagnostics.trace("grenadePlanReady", logger,
                    () -> String.format("%s's calculation is complete! Executing plan to throw %s.", self.name,
                            plan.itemToThrow().name()));

            // 返回“切换武器”指令
            return Optional.of(new GrenadeCommand(ActionType.SWITCH_SLOT, null, -1, getSlotForItem(grenadeToThrow)));
        }

        // 3. 根据当前模块状态执行逻辑
        switch (currentState) {
            case PREPARING_GRENADE:
                return handlePreparation(isStationary);

            case MOVING_TO_THROW_SPOT:
                return handleMovingToSpot(); // Error Fix: Add this method call

            case IDLE:
                // 不再自主决策
                return Optional.empty(); // 保持空闲，直到被 AIController 调用

            case CALCULATING:
                // 正在计算中，无事可做
                return Optional.empty();
        }

        return Optional.empty();
    }

    /**
     * 处理 PREPARING_GRENADE 状态的逻辑
     */
    private Optional<GrenadeCommand> handlePreparation(boolean isStationary) {
        if (grenadeToThrow == null || grenadeDecisionPosition == null) {
            resetGrenadeState();
            return Optional.empty();
        }

        // 检查是否已切换到正确的道具槽位
        if (self.currentSlot != getSlotForItem(grenadeToThrow)) {
            // 还没切换好，要求控制器再次切换 (或等待)
            return Optional.of(new GrenadeCommand(ActionType.SWITCH_SLOT, null, -1, getSlotForItem(grenadeToThrow)));
        }

        final double POSITION_TOLERANCE = Player.SIZE / 2.0;

        // 检查当前位置是否在“预定投掷点”
        if (self.position.distance(grenadeDecisionPosition) <= POSITION_TOLERANCE) {
            // --- 在正确的位置 ---
            if (isStationary) {
                // 已停稳，执行投掷
                AiDiagnostics.trace("grenadeThrow", logger,
                        () -> String.format("%s is throwing %s to target (%.1f, %.1f)",
                                self.name, grenadeToThrow.name(), grenadeTargetPosition.x, grenadeTargetPosition.y));

                // 记录：实际的 gameState.throwGrenade(self) 由 AIController 在收到 THROW_NOW 后调用
                resetGrenadeState(); // 清理状态
                // 返回“立即投掷”指令，并附上最终瞄准角度
                return Optional.of(new GrenadeCommand(ActionType.THROW_NOW, null, grenadeDecisionAngle, -1));
            } else {
                // 还没停稳，返回“原地停下并瞄准”指令
                return Optional.of(new GrenadeCommand(ActionType.HOLD_AND_AIM, null, grenadeDecisionAngle, -1));
            }
        } else {
            // --- 不在正确的位置 ---
            // 切换到“移动”状态，并返回“移动”指令
            this.currentState = ModuleState.MOVING_TO_THROW_SPOT;
            return Optional.of(new GrenadeCommand(ActionType.MOVE_TO_SPOT, grenadeDecisionPosition, -1, -1));
        }
    }

    /**
     * 处理 MOVING_TO_THROW_SPOT 状态的逻辑
     */
    private Optional<GrenadeCommand> handleMovingToSpot() {
        if (grenadeDecisionPosition == null) {
            resetGrenadeState();
            return Optional.empty();
        }

        final double POSITION_TOLERANCE = Player.SIZE / 2.0;

        // 检查是否已到达
        if (self.position.distance(grenadeDecisionPosition) <= POSITION_TOLERANCE) {
            // 已到达，切换回准备状态，下一帧将执行停稳和瞄准
            this.currentState = ModuleState.PREPARING_GRENADE;
            // 返回“停下”指令 (HOLD_AND_AIM 会让 AIController 清除路径)
            return Optional.of(new GrenadeCommand(ActionType.HOLD_AND_AIM, null, grenadeDecisionAngle, -1));
        } else {
            // 还没到，继续返回“移动”指令
            // AIController 收到 MOVE_TO_SPOT 后会负责设置路径
            return Optional.of(new GrenadeCommand(ActionType.MOVE_TO_SPOT, grenadeDecisionPosition, -1, -1));
        }
    }

    /**
     * AIController 通过此方法发起一个投掷请求。
     * (替换了原有的 tryTriggerGrenadeDecision)
     *
     * @param item           要投掷的道具
     * @param targetPosition 期望的落点
     * @return true 如果请求被接受（模块空闲），false 如果模块正忙或在冷却。
     */
    public boolean requestThrow(Item item, Point2D.Double targetPosition) {
        if (gameState.shouldFreezeAi()) {
            return false;
        }
        // 检查所有冷却和条件
        if (currentState != ModuleState.IDLE || isCalculatingGrenade) {
            return false; // 模块正忙
        }
        // 确保 self.equipment 初始化并且非空
        if (item == null || targetPosition == null || self.equipment == null || !self.equipment.containsKey(item)) {
            if (self.equipment == null)
                logger.accept("Warning: " + self.name + "'s equipment map is null!");
            return false; // 无效请求或没有该道具
        }

        long currentTime = System.currentTimeMillis();
        // 检查全局冷却（防止所有AI瞬间一起计算）
        if (currentTime - lastGlobalGrenadeCheckTime < GLOBAL_GRENADE_CHECK_COOLDOWN) {
            return false;
        }
        // 检查该AI自己的道具冷却
        if (currentTime - lastGrenadeThrowTime < GRENADE_COOLDOWN_MS) {
            return false;
        }

        // 取得计算许可
        lastGlobalGrenadeCheckTime = currentTime;
        isCalculatingGrenade = true;
        currentState = ModuleState.CALCULATING;
        long generation = calculationGeneration.get();

        // 提交异步计算任务
        grenadeCalculatorService.submit(() -> {
            try {
                // 在后台线程中执行耗时的物理计算
                GrenadeThrowPlan plan = calculateGrenadeThrow(item, targetPosition);

                if (plan != null && generation == calculationGeneration.get() && !gameState.shouldFreezeAi()) {
                    // 计算成功，将结果放入待处理队列
                    this.pendingGrenadePlan = plan;
                    AiDiagnostics.trace("grenadeCalcOk", logger,
                            () -> String.format("Background Calc OK for %s: Found a valid throw plan for %s.",
                                    self.name, item.name()));
                }
            } catch (Exception e) {
                if (generation == calculationGeneration.get() && !gameState.shouldFreezeAi()) {
                    logger.accept("GrenadeModule: 异步计算时发生错误: " + e.getMessage());
                    e.printStackTrace();
                }
            } finally {
                // 被取消的旧任务不能改写新一代状态。
                if (generation == calculationGeneration.get()) {
                    isCalculatingGrenade = false;
                    if (currentState == ModuleState.CALCULATING) {
                        currentState = ModuleState.IDLE;
                    }
                }
            }
        });

        return true; // 请求已接受，正在后台计算
    }

    /**
     * 核心战术决策与轨迹计算方法。
     * (原 calculateGrenadeThrow)
     * 移除了所有战术决策，只负责物理计算。
     */
    private GrenadeThrowPlan calculateGrenadeThrow(Item item, Point2D.Double targetPosition) {

        // --- 执行昂贵的轨迹计算 ---
        Optional<Map.Entry<Double, Double>> throwSolution = findBestThrowAngle(item, targetPosition);

        // 如果计算找到了一个误差在50单位以内的“好”路径
        if (throwSolution.isPresent() && throwSolution.get().getValue() <= 50.0) {
            double bestAngle = throwSolution.get().getKey();
            // [关键] 决策点就是 AI 发起请求时的位置
            Point2D.Double decisionPos = (Point2D.Double) self.position.clone();
            // 决策成功！返回一个完整的“投掷计划”
            return new GrenadeThrowPlan(item, decisionPos, bestAngle, targetPosition);
        }

        return null; // 找不到好的投掷路径
    }

    /**
     * 辅助方法：重置所有与手雷投掷相关的状态变量。
     */
    private void resetGrenadeState() {
        this.lastGrenadeThrowTime = System.currentTimeMillis();
        this.grenadeToThrow = null;
        this.grenadeTargetPosition = null;
        this.grenadeDecisionPosition = null;
        this.grenadeDecisionAngle = 0;
        this.grenadePlanStartTime = 0;
        this.currentState = ModuleState.IDLE;
        this.isCalculatingGrenade = false; // 确保重置
        this.pendingGrenadePlan = null; // 确保清空
    }

    /** 让所有已提交但尚未完成的轨迹计算结果永久失效。 */
    public void cancelPendingWork() {
        calculationGeneration.incrementAndGet();
        resetGrenadeState();
    }

    /**
     * 辅助方法：根据Item获取槽位
     */
    private int getSlotForItem(Item item) {
        return switch (item) {
            case FLASHBANG -> 6;
            case HE_GRENADE -> 7;
            case SMOKE_GRENADE -> 8;
            case MOLOTOV, INCENDIARY -> 9;
            case DECOY -> 10;
            default -> -1;
        };
    }

    // --- 战术辅助方法 ---

    /**
     * 检查是否看到多个敌人冲锋
     */
    private boolean isSeeingMultipleEnemiesRush(Player target) {
        if (target == null || gameState == null || gameState.getPlayers() == null)
            return false; // 添加 null 检查
        long rushers = gameState.getPlayers().stream()
                .filter(p -> p != null && p.position != null && p.team != self.team && p.isAlive()
                        && p.position.distance(target.position) < 300)
                // TODO: 依赖 AIController 或 PathfindingModule 的 hasLineOfSight
                // .filter(p -> pathfinder.hasLineOfSightToPointFromPoint(self.position,
                // p.position)) // 假设 PathfindingModule 有视线检查
                .count();
        // 简化：暂时不检查 LoS，只检查目标附近的敌人数量
        return rushers >= 2;
    }

    /**
     * 辅助方法：计算视野内有多少个活着的敌人。
     */
    private long countVisibleEnemies() {
        // TODO: 依赖 AIController 或 PathfindingModule 的 hasLineOfSight
        // 简化：暂时返回 1
        return 1;
        /*
         * if (gameState == null || gameState.getPlayers() == null) return 0;
         * return gameState.getPlayers().stream()
         * .filter(p -> p != null && p.position != null && p.team != self.team &&
         * p.isAlive() && pathfinder.hasLineOfSightToPointFromPoint(self.position,
         * p.position))
         * .count();
         */
    }

    // --- 物理模拟与碰撞检测 (从 AIController 移植) ---

    /**
     * 核心辅助方法：为给定的道具和目标点，搜索最佳的投掷角度。
     */
    private Optional<Map.Entry<Double, Double>> findBestThrowAngle(Item item, Point2D.Double targetPos) {
        long fuseMillis = switch (item) {
            case HE_GRENADE, FLASHBANG, DECOY -> 2000;
            case SMOKE_GRENADE -> 1500;
            case MOLOTOV, INCENDIARY -> 1000;
            default -> 0;
        };
        if (fuseMillis == 0)
            return Optional.empty();

        double bestAngle = -1;
        double minLandingError = Double.MAX_VALUE;

        // 添加 null 检查
        if (targetPos == null || self == null || self.position == null)
            return Optional.empty();

        Vector2D targetVec = new Vector2D(targetPos);
        double directAngleToTarget = Math.atan2(targetVec.y - self.position.y, targetVec.x - self.position.x);

        int searchSteps = 36;
        double angleIncrement = Math.toRadians(0.5);

        for (int i = -searchSteps; i <= searchSteps; i++) {
            double currentAngle = directAngleToTarget + (i * angleIncrement);
            Vector2D predictedLandingPoint = simulateGrenadePath(currentAngle, fuseMillis);

            if (predictedLandingPoint != null) {
                double landingError = predictedLandingPoint.distance(targetVec);
                if (landingError < minLandingError) {
                    minLandingError = landingError;
                    bestAngle = currentAngle;
                }
            }
        }

        if (bestAngle != -1) {
            return Optional.of(new AbstractMap.SimpleEntry<>(bestAngle, minLandingError));
        }
        return Optional.empty();
    }

    /**
     * 模拟手榴弹的飞行轨迹以预测落点。
     */
    private Vector2D simulateGrenadePath(double startAngle, long fuseMillis) {
        final double initialSpeed = 8.0;
        final double bounceAttenuation = 0.85;
        final double epsilon = 0.1;

        // 添加 null 检查
        if (self == null || self.position == null || gameState == null)
            return null;

        Vector2D currentPos = new Vector2D(self.position);
        double throwVx = Math.cos(startAngle) * initialSpeed;
        double throwVy = Math.sin(startAngle) * initialSpeed;
        Vector2D velocity = new Vector2D(throwVx + self.vx, throwVy + self.vy); // 假设 self.vx/vy 存在

        // 确保 GameState.SERVER_TICKRATE 是有效的
        int tickRate = (gameState.SERVER_TICKRATE > 0) ? (int) gameState.SERVER_TICKRATE : 64; // 提供默认值
        int maxSteps = (int) (fuseMillis / 1000.0 * tickRate);

        for (int i = 0; i < maxSteps; i++) {
            Vector2D nextPos = currentPos.add(velocity);
            CollisionResult collision = findClosestCollision(currentPos, nextPos);

            if (collision != null) {
                Vector2D impactPoint = new Vector2D(collision.impactPoint()); // 从 Point2D 转换
                Vector2D normal = new Vector2D(collision.normal()); // 从 Point2D 转换
                double dot = velocity.dotProduct(normal);
                velocity = velocity.subtract(normal.multiply(2 * dot));
                velocity = velocity.multiply(bounceAttenuation);
                currentPos = impactPoint.add(normal.multiply(epsilon));
            } else {
                currentPos = nextPos;
            }

            // 添加 gameState.width/height null 检查 (虽然不太可能为 0)
            int gameWidth = (gameState.width > 0) ? gameState.width : 1024;
            int gameHeight = (gameState.height > 0) ? gameState.height : 1024;

            if (currentPos.x <= 0 || currentPos.x >= gameWidth) {
                velocity.x *= -bounceAttenuation;
                currentPos.x = Math.max(1, Math.min(gameWidth - 1, currentPos.x));
            }
            if (currentPos.y <= 0 || currentPos.y >= gameHeight) {
                velocity.y *= -bounceAttenuation;
                currentPos.y = Math.max(1, Math.min(gameHeight - 1, currentPos.y));
            }

            if (velocity.x * velocity.x + velocity.y * velocity.y < 0.1) {
                break;
            }
        }

        // 添加 Player.SIZE 检查
        double playerSize = (Player.SIZE > 0) ? Player.SIZE : 16.0; // 提供默认值
        if (new Vector2D(self.position).distanceSq(currentPos) < (playerSize * 3) * (playerSize * 3)) {

            return null; // 投掷太近，无效
        }
        return currentPos;
    }

    /**
     * 查找射线与所有障碍物的最近碰撞点
     */
    private CollisionResult findClosestCollision(Vector2D rayStart, Vector2D rayEnd) {
        CollisionResult closestCollision = null;
        double minDistanceSq = Double.POSITIVE_INFINITY;

        // 检查 pathfinder 是否 null
        if (pathfinder == null)
            return null;

        List<Shape> obstacles = pathfinder.getObstacles(); // 依赖 Pathfinder
        if (obstacles == null)
            return null;

        for (Shape obs : obstacles) {
            if (obs == null)
                continue; // 添加障碍物 null 检查

            List<Vector2D[]> edges = new ArrayList<>();
            // ... (形状转换代码保持不变) ...
            if (obs instanceof Rectangle2D.Double rect) {
                // 添加对 rect 属性的检查（虽然不太可能为null/负数）
                if (rect.width < 0 || rect.height < 0)
                    continue;
                double x = rect.x, y = rect.y, w = rect.width, h = rect.height;
                Vector2D p1 = new Vector2D(x, y), p2 = new Vector2D(x + w, y), p3 = new Vector2D(x + w, y + h),
                        p4 = new Vector2D(x, y + h);
                edges.add(new Vector2D[] { p1, p2 });
                edges.add(new Vector2D[] { p2, p3 });
                edges.add(new Vector2D[] { p3, p4 });
                edges.add(new Vector2D[] { p4, p1 });
            } else if (obs instanceof Path2D.Double path) {
                if (path.getPathIterator(null) == null)
                    continue; // 检查 PathIterator
                PathIterator pi = path.getPathIterator(null);
                double[] coords = new double[6];
                Vector2D firstPoint = null, lastPoint = null;
                while (!pi.isDone()) {
                    int type = pi.currentSegment(coords);
                    // 添加 coords 长度检查 (虽然通常是 6)
                    if (coords.length < 2)
                        break;
                    Vector2D currentPoint = new Vector2D(coords[0], coords[1]);
                    if (type == PathIterator.SEG_MOVETO) {
                        firstPoint = currentPoint;
                    } else if (type == PathIterator.SEG_LINETO) {
                        if (lastPoint != null)
                            edges.add(new Vector2D[] { lastPoint, currentPoint });
                    } else if (type == PathIterator.SEG_CLOSE) {
                        if (lastPoint != null && firstPoint != null)
                            edges.add(new Vector2D[] { lastPoint, firstPoint });
                    }
                    lastPoint = currentPoint;
                    pi.next();
                }
            } else if (obs instanceof Ellipse2D.Double ellipse) {
                if (ellipse.width < 0 || ellipse.height < 0)
                    continue; // 检查椭圆尺寸
                final int numSegments = 16;
                double x = ellipse.x, y = ellipse.y, w = ellipse.width, h = ellipse.height;
                for (int i = 0; i < numSegments; i++) {
                    double angle1 = (i / (double) numSegments) * 2 * Math.PI;
                    double angle2 = ((i + 1) / (double) numSegments) * 2 * Math.PI;
                    Vector2D p1 = new Vector2D(x + w / 2 + (w / 2) * Math.cos(angle1),
                            y + h / 2 + (h / 2) * Math.sin(angle1));
                    Vector2D p2 = new Vector2D(x + w / 2 + (w / 2) * Math.cos(angle2),
                            y + h / 2 + (h / 2) * Math.sin(angle2));
                    edges.add(new Vector2D[] { p1, p2 });
                }
            }

            for (Vector2D[] edge : edges) {
                // 添加 edge 和 edge 内元素 null 检查
                if (edge == null || edge.length < 2 || edge[0] == null || edge[1] == null)
                    continue;

                Vector2D intersection = getLineIntersection(rayStart, rayEnd, edge[0], edge[1]);
                if (intersection != null) {
                    double distSq = rayStart.distanceSq(intersection);
                    if (distSq < minDistanceSq) {
                        minDistanceSq = distSq;
                        Vector2D edgeVector = edge[1].subtract(edge[0]);
                        Vector2D normal = new Vector2D(edgeVector.y, -edgeVector.x).normalize();
                        Vector2D velocityVector = rayEnd.subtract(rayStart);
                        if (normal.dotProduct(velocityVector) > 0) {
                            normal = normal.multiply(-1.0);
                        }
                        // Convert Vector2D to Point2D.Double and add distance
                        Point2D.Double impactPoint2D = new Point2D.Double(intersection.x, intersection.y);
                        Point2D.Double normal2D = new Point2D.Double(normal.x, normal.y);
                        // Using sqrt here for actual distance, assuming CollisionResult needs it
                        closestCollision = new CollisionResult(impactPoint2D, normal2D, Math.sqrt(minDistanceSq));
                    }
                }
            }
        }
        return closestCollision;
    }

    /**
     * 计算两条线段的交点
     */
    private Vector2D getLineIntersection(Vector2D p1, Vector2D p2, Vector2D p3, Vector2D p4) {
        // 添加参数 null 检查
        if (p1 == null || p2 == null || p3 == null || p4 == null)
            return null;

        double den = (p1.x - p2.x) * (p3.y - p4.y) - (p1.y - p2.y) * (p3.x - p4.x);
        // 添加极小值检查防止除零
        if (Math.abs(den) < 1e-9)
            return null;

        double t = ((p1.x - p3.x) * (p3.y - p4.y) - (p1.y - p3.y) * (p3.x - p4.x)) / den;
        double u = -((p1.x - p2.x) * (p1.y - p3.y) - (p1.y - p2.y) * (p1.x - p3.x)) / den;
        if (t >= 0 && t <= 1 && u >= 0 && u <= 1) {
            return new Vector2D(p1.x + t * (p2.x - p1.x), p1.y + t * (p2.y - p1.y));
        }
        return null;
    }

    /**
     * 关闭模块并释放资源（例如线程池）
     */
    public void shutdown() {
        if (grenadeCalculatorService != null && !grenadeCalculatorService.isShutdown()) { // 添加 null 检查
            grenadeCalculatorService.shutdown();
        }
    }
}
