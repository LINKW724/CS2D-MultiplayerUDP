package cs2d.AIControl.BG;

// --- 新增 Import ---
import cs2d.AIControl.A.GrenadeModule;
import cs2d.server.Item; // 需要导入 Item 枚举
// --- 结束新增 Import ---

import cs2d.AIControl.A.AttackModule;
import cs2d.AIControl.A.PathfindingModule; // 导入 A 包
import cs2d.AIControl.B.PerceptionModule;
import cs2d.playerAndAi.Player;

import cs2d.playerAndAi.Weapon;
import cs2d.server.AIService.AIInput;
import cs2d.server.AIService.AIWorldView;
import cs2d.server.AIDifficulty;
import cs2d.server.GameState;
import cs2d.server.AiDiagnostics;

import java.awt.*;
import java.awt.geom.Point2D;
import java.util.*;
import java.util.List;
import java.util.function.Consumer; // 导入 Consumer
import java.util.stream.Collectors;

import static cs2d.AIControl.A.GrenadeModule.LONG_RANGE_FLASH_MIN_DISTANCE;

import cs2d.server.AIService.PerceivedPlayer;
import cs2d.server.AIService.PerceptionReason;
import java.awt.geom.Line2D;

/**
 * 团队死斗 (TDM) 模式的 AI 总控制器。
 */
public class TEAM_DEATHMATCHcontrol {
    // ...
    // --- 核心模块引用 ---
    private final Player owner;
    private final GameState gameState;
    private final AIDifficulty difficulty;
    private final PerceptionModule perceptionModule;
    private final AttackModule attackModule;
    private final PathfindingModule pathfindingModule;
    // --- 新增模块引用 ---
    private final GrenadeModule grenadeModule;
    // --- 结束新增 ---
    private final Consumer<String> logger;

    // --- 内部状态 ---
    private Random rand = new Random();
    private AIState currentState = AIState.PATROLLING;
    private Player primaryTarget = null;
    private Point2D.Double lastKnownPosition = null;

    // --- 状态机计时器 ---
    private long lastStateChangeTime = 0;
    private long lastPathRecalculationTime = 0;
    private long targetAcquiredTime = 0;
    private int shotsFiredAtCurrentTarget = 0;

    private long lastStuckCheckTime = 0;
    private Point2D.Double lastPositionForStuckCheck;
    private int stuckCount = 0;
    private long unstuckUntil = 0;

    private Point2D.Double unstuckTarget = null;

    private static final long STUCK_CHECK_INTERVAL_MS = 1500;
    private static final double MIN_MOVEMENT_FOR_STUCK = 3.0;
    private static final long UNSTUCK_DURATION_MS = 800; // 避让 0.8 秒
    /**
     * AI 将尝试避开此半径内的队友 (约 1.5 倍玩家大小)
     */
    private static final double AVOIDANCE_RADIUS = Player.SIZE * 1.5;
    /**
     * 用于优化的半径平方
     */
    private static final double AVOIDANCE_RADIUS_SQ = AVOIDANCE_RADIUS * AVOIDANCE_RADIUS;

    /** 团队死斗模式的 AI 武器池 (只包含长枪) */
    private static final List<Weapon> TDM_WEAPON_POOL = Arrays.asList(
            Weapon.AK47, Weapon.M4A4, Weapon.M4A1S, Weapon.FAMAS, Weapon.GALIL,
            Weapon.MP7, Weapon.UMP45, Weapon.P90, Weapon.SSG08, Weapon.XM1014
    // 排除 AWP, M249, NEGEV (TDM节奏太快)
    );

    /** AI 当前选择的武器 (用于追踪表现) */
    private Weapon currentTdmWeapon = null;
    /** AI 拿着这把武器连续死亡的次数 */
    private int deathsWithCurrentWeapon = 0;
    /** 连续死亡多少次后强制换枪 */
    private static final int MAX_DEATHS_BEFORE_SWITCH = 3;
    private long burstCooldownUntil = 0; // 连续射击冷却

    // --- 新增：战术决策计时器 ---
    private long nextTacticalDecisionTime = 0; // 下次可以考虑扔雷的时间
    private static final long TACTICAL_DECISION_COOLDOWN = 1500; // 扔雷决策冷却(毫秒)
    // --- 结束新增 ---

    /**
     * TDM 模式的 AI 行为状态机。
     */
    public enum AIState {
        PATROLLING,
        HUNTING,
        ATTACKING,
        TAKING_COVER,
        IDLE,
        // --- 新增状态 ---
        PREPARING_GRENADE, // 正在执行 GrenadeModule 的 HOLD_AND_AIM 或 SWITCH_SLOT
        MOVING_TO_THROW_SPOT // 正在执行 GrenadeModule 的 MOVE_TO_SPOT
        // --- 结束新增 ---
    }

    /**
     * 构造函数：初始化 TDM 控制器并传入所有必要的模块。
     *
     * @param owner             AI 所属的玩家实体
     * @param gameState         当前游戏状态对象
     * @param difficulty        当前 AI 难度设置
     * @param perceptionModule  负责处理环境感知的模块
     * @param attackModule      负责处理攻击逻辑的模块
     * @param pathfindingModule 负责处理寻路和移动的模块
     * @param logger            用于记录调试信息的日志函数
     */
    public TEAM_DEATHMATCHcontrol(Player owner, GameState gameState, AIDifficulty difficulty,
            PerceptionModule perceptionModule,
            AttackModule attackModule,
            PathfindingModule pathfindingModule,
            Consumer<String> logger) { // <--- 添加 logger
        this.owner = owner;
        this.gameState = gameState;
        this.difficulty = difficulty;
        this.perceptionModule = perceptionModule;
        this.attackModule = attackModule;
        this.pathfindingModule = pathfindingModule;
        // --- 新增：初始化 GrenadeModule ---
        // 确保 rand 已经初始化
        this.rand = new Random(); // 如果之前没有初始化，在这里初始化
        // 假设 owner.getPathfindingModule().pathfinder 可以获取到 Pathfinder 实例
        if (owner.getPathfindingModule() == null || owner.getPathfindingModule().pathfinder == null) {
            throw new IllegalStateException(
                    "Pathfinder must be initialized before GrenadeModule for AI: " + owner.name);
        }
        this.grenadeModule = new GrenadeModule(owner, gameState, owner.getPathfindingModule().pathfinder, difficulty,
                logger, this.rand);
        // --- 结束新增 ---

        // --- 初始化卡死检测 ---
        this.lastPositionForStuckCheck = (Point2D.Double) owner.position.clone();
        this.lastStuckCheckTime = System.currentTimeMillis();

        this.logger = logger; // <--- 存储 logger
    }

    /**
     * AI 的核心更新方法，由 AIService 调用。
     * 不再接收和处理声音列表，感知模块自行获取。
     * 集成了 GrenadeModule 的调用。
     *
     * @param worldView   AI 的当前世界快照
     * @param currentTime 当前系统时间戳(毫秒)
     * @return 当前帧的 AI 输入指令
     */
    public AIInput update(AIWorldView worldView, long currentTime) { // <-- 签名已修改，移除 List<SoundEvent>

        // --- 新增：步骤 0 - 处理 GrenadeModule 指令 (最高优先级) ---
        boolean isStationary = (owner.vx * owner.vx + owner.vy * owner.vy < 0.1);
        boolean targetInLoS = (primaryTarget != null && pathfindingModule.hasLineOfSight(primaryTarget.position)); // 需要先调用
                                                                                                                   // selectPrimaryTarget
                                                                                                                   // 吗？
                                                                                                                   // 暂时这样

        Optional<GrenadeModule.GrenadeCommand> grenadeCmd = grenadeModule.update(
                this.primaryTarget,
                this.lastKnownPosition,
                this.currentState,
                isStationary,
                targetInLoS);

        if (grenadeCmd.isPresent()) {
            GrenadeModule.GrenadeCommand cmd = grenadeCmd.get();
            AIState nextState = this.currentState; // 临时变量
            AIInput grenadeActionInput = null; // 用于存储模块返回的直接输入

            switch (cmd.action()) {
                case SWITCH_SLOT:
                    owner.switchToSlot(cmd.slot());
                    nextState = AIState.PREPARING_GRENADE;
                    // 清除移动，原地等待切换完成
                    pathfindingModule.setTarget(null);
                    grenadeActionInput = new AIInput(new ArrayList<>(), owner.angle, false, false, false);
                    break;
                case MOVE_TO_SPOT:
                    nextState = AIState.MOVING_TO_THROW_SPOT;
                    // 设置寻路目标，让寻路模块处理移动
                    if (pathfindingModule.getTargetPosition() == null
                            || !pathfindingModule.getTargetPosition().equals(cmd.pathTarget())) {
                        pathfindingModule.setTarget(cmd.pathTarget());
                    }
                    // 不需要直接返回 AIInput，让 executeActions 处理寻路
                    break;
                case HOLD_AND_AIM:
                    nextState = AIState.PREPARING_GRENADE;
                    // 清除寻路，原地瞄准
                    pathfindingModule.setTarget(null);
                    // 直接返回瞄准指令
                    grenadeActionInput = new AIInput(new ArrayList<>(), cmd.aimAngle(), false, false, false);
                    break;
                case THROW_NOW:
                    // 大脑执行投掷动作！
                    gameState.throwGrenade(owner);
                    // 模块内部状态已重置，大脑状态恢复
                    nextState = (primaryTarget != null) ? AIState.ATTACKING : AIState.PATROLLING;
                    // 返回一个瞬时的瞄准指令（虽然马上会被覆盖）
                    grenadeActionInput = new AIInput(new ArrayList<>(), cmd.aimAngle(), false, false, false);
                    break;
                case DO_NOTHING:
                    // 模块空闲或正在计算，大脑继续自己的逻辑
                    break;
            }

            // 更新状态机
            if (this.currentState != nextState) {
                this.currentState = nextState;
                this.lastStateChangeTime = currentTime;
            }

            // 如果模块返回了直接的 AIInput (切换武器、原地瞄准、投掷瞬间)，则优先执行并返回
            if (grenadeActionInput != null) {
                return grenadeActionInput;
            }
            // 如果是 MOVE_TO_SPOT，不直接返回，让后续的 executeActions 处理移动
            // 如果是 DO_NOTHING，继续执行大脑逻辑
            if (cmd.action() != GrenadeModule.ActionType.DO_NOTHING
                    && cmd.action() != GrenadeModule.ActionType.MOVE_TO_SPOT) {
                // 对于 SWITCH_SLOT, HOLD_AND_AIM, THROW_NOW，既然已经处理了，就直接返回，不再执行后续逻辑
                return executeActions(worldView, currentTime); // 确保即使返回也要合并一下移动和瞄准？或者直接返回 grenadeActionInput ? 选后者更安全
                // return grenadeActionInput; // <--- 修正：应该直接返回指令
            }
        }
        // --- 结束新增 GrenadeModule 处理 ---

        // 1. 更新感知
        perceptionModule.update(worldView, /* 移除 relevantSounds */ currentTime); // <-- 调用已修改

        // 2. 选择目标 (逻辑不变)
        selectPrimaryTarget(currentTime);

        // 检查是否看到了敌人
        PerceptionModule.PerceptionInfo targetInfo = (this.primaryTarget != null)
                ? perceptionModule.getPerceptionInfo(this.primaryTarget.id)
                : null;
        boolean isTargetVisible = targetInfo != null && targetInfo.isCurrentlyVisible();

        // 如果AI正在执行预设路径 但 突然看到了敌人
        if (isTargetVisible && pathfindingModule.isFollowingPresetPath()) {
            if (logger != null) {
                AiDiagnostics.trace("presetPathCancelled", logger,
                        () -> "AI [" + owner.name + "] 正在执行预设路径，但发现敌人！取消预设路径！");
            }
            // 命令寻路模块取消，并回退到标准A*
            pathfindingModule.cancelPresetPath();
        }

        // 3. 更新状态机 (会在这里触发扔雷请求)
        runTdmStateMachine(currentTime);

        // 4. 避让 (逻辑不变)
        AIInput avoidanceInput = handleTeammateAvoidance(currentTime, worldView);
        if (avoidanceInput != null) {
            return avoidanceInput;
        }

        // 5. 执行并合并模块 (逻辑不变)
        return executeActions(worldView, currentTime);
    }

    /**
     * 运行 TDM 状态机，更新 AI 的 currentState。
     * 加入了扔雷决策点。
     */
    private void runTdmStateMachine(long currentTime) {
        AIState oldState = this.currentState;

        // --- 0. 检查是否正在执行扔雷流程 ---
        // 如果 GrenadeModule 正在活动，状态机由它控制，这里不做转换
        if (currentState == AIState.PREPARING_GRENADE || currentState == AIState.MOVING_TO_THROW_SPOT) {
            return;
        }
        // --- 结束新增 ---

        // --- 1. 高优先级中断：换弹 ---
        if (owner.isReloading) {
            if (currentState != AIState.TAKING_COVER) {
                currentState = AIState.TAKING_COVER;
                Point2D.Double coverPoint = findCover(this.lastKnownPosition);
                // 修正：确保 coverPoint 不为 null 再设置目标
                if (coverPoint != null) {
                    pathfindingModule.setTarget(coverPoint);
                } else {
                    // 找不到掩体，原地换弹或执行简单后退
                    pathfindingModule.setTarget(calculateRetreatPosition());
                }
            }
            // 换弹时，不考虑扔雷
            nextTacticalDecisionTime = currentTime + TACTICAL_DECISION_COOLDOWN; // 重置冷却
        } else if (currentState == AIState.TAKING_COVER) {
            // 换弹结束
            currentState = (this.lastKnownPosition != null) ? AIState.HUNTING : AIState.PATROLLING;
            // 换弹结束后，根据情况设置寻路目标
            if (currentState == AIState.HUNTING && this.lastKnownPosition != null) {
                pathfindingModule.setTarget(this.lastKnownPosition);
            } else {
                pathfindingModule.setTarget(findPatrolPoint()); // 找不到敌人就巡逻
            }
        }

        // --- 2. 核心状态转换 ---
        PerceptionModule.PerceptionInfo targetInfo = (this.primaryTarget != null)
                ? perceptionModule.getPerceptionInfo(this.primaryTarget.id)
                : null;
        boolean isTargetVisible = targetInfo != null && targetInfo.isCurrentlyVisible();

        if (isTargetVisible) {
            currentState = AIState.ATTACKING;

            // --- 新增：攻击状态下的扔雷决策 ---
            if (currentTime >= nextTacticalDecisionTime) {
                decideAndRequestThrow(currentTime, true); // true 表示目标可见
                nextTacticalDecisionTime = currentTime + TACTICAL_DECISION_COOLDOWN; // 重置冷却
            }
            // --- 结束新增 ---

        } else if (this.lastKnownPosition != null) {
            if (currentState == AIState.ATTACKING || currentState == AIState.PATROLLING
                    || currentState == AIState.IDLE) {
                currentState = AIState.HUNTING;
            }

            // --- 新增：追猎状态下的扔雷决策 (例如扔雷清点) ---
            if (currentState == AIState.HUNTING && currentTime >= nextTacticalDecisionTime) {
                decideAndRequestThrow(currentTime, false); // false 表示目标不可见
                nextTacticalDecisionTime = currentTime + TACTICAL_DECISION_COOLDOWN; // 重置冷却
            }
            // --- 结束新增 ---

        } else {
            if (currentState != AIState.PATROLLING && currentState != AIState.TAKING_COVER) {
                currentState = AIState.PATROLLING;
            }
            // 巡逻时不主动扔雷
            nextTacticalDecisionTime = currentTime + TACTICAL_DECISION_COOLDOWN; // 重置冷却
        }

        if (oldState != this.currentState) {
            this.lastStateChangeTime = currentTime;
            // 状态切换时，也重置扔雷决策冷却，避免刚切换状态就扔雷
            nextTacticalDecisionTime = currentTime + TACTICAL_DECISION_COOLDOWN;
        }
    }

    // --- 新增：扔雷决策与请求方法 ---
    /**
     * 根据当前战术情况决定是否以及如何扔雷，并向 GrenadeModule 发出请求。
     * 
     * @param currentTime     当前时间戳
     * @param targetIsVisible 目标是否在视野内
     */
    private void decideAndRequestThrow(long currentTime, boolean targetIsVisible) {
        // 基本条件检查
        if (primaryTarget == null && lastKnownPosition == null)
            return; // 没有目标信息
        if (grenadeModule == null)
            return; // 模块未初始化

        Point2D.Double throwTargetPos = targetIsVisible ? primaryTarget.position : lastKnownPosition;
        if (throwTargetPos == null)
            return; // 目标点无效

        double distance = owner.position.distance(throwTargetPos);

        Item itemToThrow = null;

        // 简单的决策逻辑 (你可以根据需要扩展)
        if (targetIsVisible) {
            // 目标可见
            if (distance > LONG_RANGE_FLASH_MIN_DISTANCE && owner.equipment.containsKey(Item.FLASHBANG)) {
                if (rand.nextDouble() < 0.6) { // 60% 概率扔闪光
                    itemToThrow = Item.FLASHBANG;
                }
            } else if (distance > 250 && owner.equipment.containsKey(Item.HE_GRENADE)) {
                if (rand.nextDouble() < 0.4) { // 40% 概率扔手雷
                    itemToThrow = Item.HE_GRENADE;
                }
            }
        } else {
            // 目标不可见 (在掩体后)
            if (distance < 800) { // 只对较近的掩体后敌人扔雷/火
                if (owner.equipment.containsKey(Item.HE_GRENADE) && rand.nextDouble() < 0.7) { // 70% 扔手雷
                    itemToThrow = Item.HE_GRENADE;
                } else if (owner.equipment.containsKey(Item.MOLOTOV) && rand.nextDouble() < 0.5) { // 50% 扔火 (T)
                    itemToThrow = Item.MOLOTOV;
                } else if (owner.equipment.containsKey(Item.INCENDIARY) && rand.nextDouble() < 0.5) { // 50% 扔火 (CT)
                    itemToThrow = Item.INCENDIARY;
                }
            }
        }

        // 如果决策结果是要扔某个道具
        if (itemToThrow != null) {
            boolean accepted = grenadeModule.requestThrow(itemToThrow, throwTargetPos);
            if (accepted) {
                Item decidedItem = itemToThrow;
                AiDiagnostics.trace("grenadeDecision", logger,
                        () -> "AI [" + owner.name + "] decided to throw " + decidedItem.name() + " at ("
                                + (int) throwTargetPos.x + "," + (int) throwTargetPos.y + ")");
            } else if (!gameState.shouldFreezeAi() && logger != null) {
                Item rejectedItem = itemToThrow;
                AiDiagnostics.trace("grenadeRejected", logger,
                        () -> "AI [" + owner.name + "] grenade request for " + rejectedItem.name()
                                + " was rejected (busy or cooldown).");
            }
            // 不论是否接受，都重置冷却计时器
            nextTacticalDecisionTime = currentTime + TACTICAL_DECISION_COOLDOWN;
        }
    }
    // --- 结束新增 ---

    /**
     * 根据当前状态，执行并合并攻击和寻路模块的输出。
     * 确保在 GrenadeModule 活动时不干扰其瞄准。
     */
    private AIInput executeActions(AIWorldView worldView, long currentTime) {
        AIInput attackInput = null;
        AIInput pathInput = null;

        // --- 新增：检查 GrenadeModule 状态 ---
        boolean isGrenadeModuleAiming = (currentState == AIState.PREPARING_GRENADE);
        boolean isGrenadeModuleMoving = (currentState == AIState.MOVING_TO_THROW_SPOT);
        // --- 结束新增 ---

        // 1. 根据状态设置寻路模块的“意图目标”
        // 如果 GrenadeModule 正在移动，则大脑不设置寻路目标
        if (!isGrenadeModuleMoving && !isGrenadeModuleAiming) { // <-- 添加检查
            switch (currentState) {
                case ATTACKING:
                    // 在设置目标前，先检查目标点是否可行走
                    // 添加 primaryTarget null 检查
                    if (this.primaryTarget != null && this.primaryTarget.position != null
                            && pathfindingModule.isWalkable(this.primaryTarget.position)) {
                        // --- [新增：短枪远距离绕路与掩护] ---
                        Weapon wep = owner.getCurrentWeapon();
                        double effectiveRange = Double.MAX_VALUE;
                        if (wep != null) {
                            if (wep.getWeaponType() == Weapon.WeaponType.SHOTGUN)
                                effectiveRange = 400.0;
                            else if (wep.getWeaponType() == Weapon.WeaponType.SMG)
                                effectiveRange = 650.0;
                        }
                        double dist = owner.position.distance(this.primaryTarget.position);

                        if (dist > effectiveRange) {
                            // 距离过远且是短枪，不走直线枪线，寻找掩体靠近或绕路
                            Point2D.Double coverPoint = findCover(this.primaryTarget.position);
                            // 如果掩体位置不至于让我们离敌人更远太多，就去掩体
                            if (coverPoint != null && coverPoint.distance(this.primaryTarget.position) < dist + 150) {
                                pathfindingModule.setTarget(coverPoint);
                            } else {
                                // 没有好掩体，走侧面绕路 (偏转45度)
                                double angleToTarget = Math.atan2(this.primaryTarget.position.y - owner.position.y,
                                        this.primaryTarget.position.x - owner.position.x);
                                double flankAngle = angleToTarget + (rand.nextBoolean() ? Math.PI / 4 : -Math.PI / 4);
                                Point2D.Double flankPoint = new Point2D.Double(
                                        owner.position.x + Math.cos(flankAngle) * (dist * 0.5),
                                        owner.position.y + Math.sin(flankAngle) * (dist * 0.5));
                                if (pathfindingModule.isWalkable(flankPoint)) {
                                    pathfindingModule.setTarget(flankPoint);
                                } else {
                                    pathfindingModule.setTarget(this.primaryTarget.position); // 退回直冲
                                }
                            }
                        } else {
                            // 距离内，正常冲锋
                            // 只有当寻路模块当前没有目标，或者目标不是我们要攻击的敌人时，才更新
                            if (!pathfindingModule.isActive() || pathfindingModule.getTargetPosition() == null
                                    || !pathfindingModule.getTargetPosition().equals(this.primaryTarget.position)) {
                                pathfindingModule.setTarget(this.primaryTarget.position);
                            }
                        }
                        // --- 结束新增 ---
                    } else if (pathfindingModule.isActive()) {
                        // 如果目标不可行走，但寻路模块还在走向之前的某个点，让它停下
                        pathfindingModule.setTarget(null);
                    }
                    break;
                case HUNTING:
                    if (this.lastKnownPosition != null && pathfindingModule.isWalkable(this.lastKnownPosition)) {
                        if (!pathfindingModule.isActive() || pathfindingModule.getTargetPosition() == null
                                || !pathfindingModule.getTargetPosition().equals(this.lastKnownPosition)) {
                            pathfindingModule.setTarget(this.lastKnownPosition);
                        }
                    } else if (pathfindingModule.isActive()) {
                        pathfindingModule.setTarget(null);
                    }
                    break;
                case PATROLLING:
                    if (!pathfindingModule.isActive() && (currentTime - lastPathRecalculationTime > 3000)) {
                        pathfindingModule.setTarget(findPatrolPoint()); // 这个点保证是可走的
                        lastPathRecalculationTime = currentTime;
                    }
                    break;
                case TAKING_COVER:
                    // 目标已在状态机切换时设置
                    break;
                case IDLE: // IDLE 状态清除寻路目标
                    if (pathfindingModule.isActive()) {
                        pathfindingModule.setTarget(null);
                    }
                    break;

                // 新增状态的处理：在这些状态下，大脑不主动设置寻路目标
                case PREPARING_GRENADE:
                case MOVING_TO_THROW_SPOT:
                    break;
                default:
                    break;
            }
        } // <-- 结束 GrenadeModule 状态检查

        // 2. 更新（调用）子模块
        if (attackModule != null) {
            attackInput = attackModule.update(this.primaryTarget, this.lastKnownPosition, currentTime);
        }
        // 只有当 GrenadeModule 不在移动时，才让寻路模块更新并获取输入
        if (pathfindingModule != null /* && !isGrenadeModuleMoving */) { // <--- 移除这个 !isGrenadeModuleMoving
                                                                         // 条件，让移动合并逻辑处理
            pathInput = pathfindingModule.update(worldView);
        }

        // 3. 合并结果
        List<String> finalKeys = new ArrayList<>();
        double finalAngle = owner.angle;
        boolean finalShooting = false;
        boolean finalWalking = false;

        // --- 决定移动按键 ---
        if (attackInput != null && !attackInput.keys().isEmpty()) {
            // 优先级 1: 急停/战斗移动 (来自 AttackModule)
            finalKeys = attackInput.keys();

        } else if (isGrenadeModuleMoving && pathInput != null) {
            // 优先级 2: 扔雷移动 (来自 PathfindingModule，在 GrenadeModule 控制下)
            finalKeys = pathInput.keys();

        } else if (!isGrenadeModuleAiming && !isGrenadeModuleMoving) {
            // 优先级 3: 正常寻路（已合并主动避让）

            // --- 调用主动避让系统 ---
            List<String> avoidanceKeys = calculateProactiveAvoidanceKeys();

            if (!avoidanceKeys.isEmpty()) {
                // 我们正在主动避让！
                // 使用 Set 来合并寻路键 (例如 "W") 和 避让键 (例如 "A")
                // 结果将是 ["W", "A"]，使 AI 斜向移动，完美解决问题！
                Set<String> mergedKeys = new HashSet<>();

                // 1. 添加寻路模块的意图 (例如 "W")
                if (pathInput != null) {
                    mergedKeys.addAll(pathInput.keys());
                }

                // 2. 添加避让模块的意图 (例如 "A" 或 "D")
                mergedKeys.addAll(avoidanceKeys);

                finalKeys = new ArrayList<>(mergedKeys);

            } else if (pathInput != null) {
                // [原逻辑] 没有检测到需要避让的队友，正常使用寻路
                finalKeys = pathInput.keys();
            }
            // 优先级 4: 原地不动 (如果 pathInput 也为 null, finalKeys 保持为空)

        }
        // --- [新增逻辑结束] ---

        // --- 决定瞄准角度和射击 ---
        if (isGrenadeModuleAiming) {
            // 优先级 1: 扔雷瞄准 (角度由 GrenadeModule 在 update 开头设置好，这里无需处理)
            // finalAngle = grenadeModule.getAimAngle(); // 假设有这个方法，或者直接使用owner.angle?
            // 不，HOLD_AND_AIM 命令已经设置了
            finalShooting = false; // 扔雷时不射击
        } else if (attackInput != null && (currentState == AIState.ATTACKING || currentState == AIState.HUNTING
                || currentState == AIState.TAKING_COVER)) {
            // 优先级 2: 攻击瞄准 (来自 AttackModule)
            finalAngle = attackInput.angle();
            finalShooting = attackInput.shooting();
        } else if (pathInput != null && pathfindingModule.isActive() && !isGrenadeModuleAiming
                && !isGrenadeModuleMoving) {
            // 优先级 3: 看路 (来自 PathfindingModule)
            finalAngle = pathInput.angle();
            finalShooting = false;
        } else {
            // 优先级 4: 保持当前角度，不射击
            finalAngle = owner.angle; // 保持当前角度
            finalShooting = false;
        }

        // 返回最终合并的 AIInput
        return new AIInput(finalKeys, finalAngle, finalShooting, owner.isInteracting, finalWalking);
    }

    /**
     * 根据当前的 perceptionMap 选择最优先处理的目标。
     * [已修复] 确保在目标死亡或丢失时能正确清除 lastKnownPosition。
     */
    private void selectPrimaryTarget(long currentTime) {
        Player oldTarget = this.primaryTarget; // 保留下旧目标用于比较

        // --- VVVV VVVV ---
        Player newBestTarget = null;
        Point2D.Double newBestLKP = null;
        // --- ^^^^ [修复结束] ^^^^ ---

        double minScore = Double.POSITIVE_INFINITY; // 初始化最低分数为无穷大

        // 添加 perceptionModule null 检查
        if (perceptionModule == null) {
            this.primaryTarget = null;
            this.lastKnownPosition = null;
            return;
        }

        // 遍历感知模块提供的所有敌人信息
        for (PerceptionModule.PerceptionInfo info : perceptionModule.getAllPerceivedEnemies().values()) {
            // 添加 info null 检查
            if (info == null || info.enemyId() == null)
                continue;

            Player enemyPlayer = gameState.getPlayerById(info.enemyId());
            // 添加 enemyPlayer null 检查
            if (enemyPlayer == null || !enemyPlayer.isAlive() || enemyPlayer.position == null
                    || info.lastKnownPosition() == null) { // 添加 position null 检查
                continue; // <--- 确保不会选择已死亡的敌人或处理无效信息
            }

            double distanceSq = owner.position.distanceSq(info.lastKnownPosition());
            double score = distanceSq; // 基础分数是距离

            // 根据感知类型调整分数（视觉优先，脚步声次之）
            switch (info.type()) {
                case SIGHT: // 视觉信息
                    score *= 0.5; // 优先级最高
                    if (enemyPlayer.isReloading)
                        score *= 0.3; // 正在换弹的敌人更优先
                    if (enemyPlayer.health < 40)
                        score *= 0.7; // 低血量敌人更优先
                    break;
                case GUNSHOT: // 枪声信息
                    score *= 1.0;
                    break;
                case FOOTSTEP: // 脚步声信息
                    score *= 1.5;
                    break;
            }

            // 根据信息的新鲜度调整分数（越旧的信息优先级越低）
            long age = currentTime - info.timestamp();
            // 防止 age 为负数 (虽然不太可能)
            if (age < 0)
                age = 0;
            score *= (1.0 + (double) age / 2000.0); // 每过2秒优先级降低一倍

            if (score < minScore) {
                minScore = score; // 更新最低分数
                newBestTarget = enemyPlayer; // 更新最佳目标
                newBestLKP = info.lastKnownPosition(); // 更新最佳目标位置
            }
        } // --- 敌人信息遍历结束 ---

        if (oldTarget != newBestTarget) {
            this.targetAcquiredTime = currentTime; // 重置反应计时器
            this.shotsFiredAtCurrentTarget = 0; // 重置射击计数
            // 添加 owner null 检查
            if (owner != null) {
                this.owner.shootTimeIndex = 0; // 重置压枪
            }
        }

        this.primaryTarget = newBestTarget;
        this.lastKnownPosition = newBestLKP;
    }

    /**
     * 寻找一个随机的巡逻点。
     */
    private Point2D.Double findPatrolPoint() {
        // 添加 gameState null 检查
        int width = (gameState != null && gameState.width > 0) ? gameState.width : 1024;
        int height = (gameState != null && gameState.height > 0) ? gameState.height : 1024;

        // 如果寻路模块不可用，直接返回随机点
        if (pathfindingModule == null || pathfindingModule.pathfinder == null) {
            return new Point2D.Double(rand.nextInt(width), rand.nextInt(height));
        }

        int attempts = 0;
        final int MAX_ATTEMPTS = 10;
        while (attempts < MAX_ATTEMPTS) {
            Point2D.Double candidate = new Point2D.Double(
                    rand.nextInt(width),
                    rand.nextInt(height));
            // pathfindingModule.isWalkable 现在是 public
            if (pathfindingModule.isWalkable(candidate)) {
                return candidate;
            }
            attempts++;
        }
        // 多次尝试失败，返回随机点
        return new Point2D.Double(rand.nextInt(width), rand.nextInt(height));
    }

    /**
     * 寻找一个相对于威胁位置的掩体点。
     */
    private Point2D.Double findCover(Point2D.Double threatPosition) {
        // 添加 owner.position null 检查
        if (owner == null || owner.position == null)
            return calculateRetreatPosition();

        // 如果寻路模块不可用，直接后退
        if (pathfindingModule == null || pathfindingModule.pathfinder == null || gameState == null) {
            return calculateRetreatPosition();
        }

        for (int i = 0; i < 8; i++) {
            double angle = rand.nextDouble() * 2 * Math.PI;
            double checkRadius = 150 + rand.nextDouble() * 150;
            Point2D.Double candidatePoint = new Point2D.Double(
                    owner.position.x + Math.cos(angle) * checkRadius,
                    owner.position.y + Math.sin(angle) * checkRadius);

            // 检查点是否在边界内
            if (gameState.isInBounds(candidatePoint)) {
                // pathfindingModule.isWalkable 是 public
                if (pathfindingModule.isWalkable(candidatePoint)) {
                    // 如果没有威胁源信息，找到第一个可走的点就返回
                    if (threatPosition == null) {
                        return candidatePoint;
                    } else {
                        // 有威胁源，需要检查视线
                        // pathfindingModule.pathfinder 是 public, hasLineOfSightToPointFromPoint 也是
                        // public
                        if (!pathfindingModule.pathfinder.hasLineOfSightToPointFromPoint(threatPosition,
                                candidatePoint)) {
                            // 找到了一个被遮挡的点
                            return candidatePoint;
                        }
                    }
                }
            }
        }
        // 找不到掩体，执行后退
        return calculateRetreatPosition();
    }

    /**
     * 计算一个简单的后退点（当前位置反方向）。
     */
    private Point2D.Double calculateRetreatPosition() {
        // 添加 owner.position 和 owner.angle null/NaN 检查
        if (owner == null || owner.position == null || Double.isNaN(owner.angle)) {
            // 返回一个默认位置或基于游戏状态的位置
            int width = (gameState != null && gameState.width > 0) ? gameState.width : 1024;
            int height = (gameState != null && gameState.height > 0) ? gameState.height : 1024;
            return new Point2D.Double(width / 2.0, height / 2.0); // 地图中心
        }

        double retreatAngle = owner.angle + Math.PI;
        // 限制后退点在地图内
        double retreatX = owner.position.x + Math.cos(retreatAngle) * 200;
        double retreatY = owner.position.y + Math.sin(retreatAngle) * 200;
        int width = (gameState != null && gameState.width > 0) ? gameState.width : 1024;
        int height = (gameState != null && gameState.height > 0) ? gameState.height : 1024;
        retreatX = Math.max(Player.SIZE, Math.min(width - Player.SIZE, retreatX));
        retreatY = Math.max(Player.SIZE, Math.min(height - Player.SIZE, retreatY));

        return new Point2D.Double(retreatX, retreatY);
    }

    /**
     * 在 AI 第一次出生时调用，选择初始武器。
     * (这个方法在 Player 构造函数中被调用)
     */
    public void initializeWeaponChoice() {
        // TDM 模式第一把枪随机选择
        selectNewWeapon(null); // 传入 null 表示没有需要避开的武器
        this.deathsWithCurrentWeapon = 0; // 重置死亡计数
    }

    /**
     * 当 AI 玩家死亡时，由 GameState.handlePlayerKill 调用。
     * 负责检查是否需要更换武器。
     */
    public void onDeath() {
        // 添加 owner null 检查
        if (owner == null)
            return;

        // 检查 AI 死亡时拿的是否是主武器，并且这把主武器是否是它自己选择的
        Weapon weaponAtDeath = owner.primaryWeapon; // TDM 主要关心主武器
        if (weaponAtDeath != null && weaponAtDeath == this.currentTdmWeapon) {
            // 是的，拿着选的枪死了，死亡次数 +1
            this.deathsWithCurrentWeapon++;
            // logger.accept(owner.name + " died with " + weaponAtDeath.name() + ". Streak:
            // " + deathsWithCurrentWeapon); // 调试
        } else if (this.currentTdmWeapon == null) {
            // 可能是第一条命（拿着手枪）死的，也初始化一下
            initializeWeaponChoice();
        }

        // 检查是否达到了换枪阈值
        if (this.deathsWithCurrentWeapon >= MAX_DEATHS_BEFORE_SWITCH) {
            if (logger != null) { // 安全检查
                logger.accept(
                        "AI [" + owner.name + "] 拿着 " + (currentTdmWeapon != null ? currentTdmWeapon.name() : "武器")
                                + " 连续死了 " + deathsWithCurrentWeapon + " 次。正在更换武器！");
            }
            selectNewWeapon(this.currentTdmWeapon); // 选择一把新武器 (避开旧的)
            this.deathsWithCurrentWeapon = 0; // 重置计数器
        } else {
            // 没达到阈值，继续使用当前武器
            if (this.currentTdmWeapon != null) {
                if (this.currentTdmWeapon.getWeaponType().isPistol()) {
                    owner.selectedSecondaryName = this.currentTdmWeapon.name();
                } else {
                    owner.selectedPrimaryName = this.currentTdmWeapon.name();
                }
            } else {
                // 理论上不应该发生，但作为保险
                selectNewWeapon(null);
            }
        }
    }

    /**
     * 重置 TDM 控制器的状态。
     * 在 AI 重生时调用，使其恢复到初始巡逻状态。
     */
    public void reset() {
        // 添加 owner 或 owner.position null 检查
        if (owner == null || owner.position == null)
            return;

        // 确保在重置大脑时，也重置寻路模块的状态
        if (this.pathfindingModule != null) {
            this.pathfindingModule.reset();
        }

        // 1. 清除所有战术和目标信息
        this.primaryTarget = null;
        this.lastKnownPosition = null;

        // 2. 重置状态机
        this.currentState = AIState.PATROLLING; // 重生后默认进入巡逻
        this.lastStateChangeTime = System.currentTimeMillis();

        // 3. 重置攻击状态
        this.targetAcquiredTime = 0;
        this.shotsFiredAtCurrentTarget = 0;
        this.burstCooldownUntil = 0;

        // 4. 重置寻路计时器
        this.lastPathRecalculationTime = 0;
        // --- 5.设置重生后的初始冲锋目标 ---
        Point2D.Double nearestEnemySpawnCenter = null;
        double minDistanceSq = Double.POSITIVE_INFINITY;

        // 确定敌方队伍的出生点列表
        List<Rectangle> enemySpawnAreas = null;
        if (gameState != null) { // 添加 gameState null 检查
            if (owner.team == Player.Team.T) {
                enemySpawnAreas = gameState.getCtSpawnAreas();
            } else if (owner.team == Player.Team.CT) {
                enemySpawnAreas = gameState.getTSpawnAreas();
            }
        }

        // 检查出生点列表是否有效
        if (enemySpawnAreas != null && !enemySpawnAreas.isEmpty()) {
            // 遍历所有敌方出生点
            for (Rectangle spawnArea : enemySpawnAreas) {
                // getCenter 现在返回 nullable
                Point2D.Double center = getCenter(spawnArea);
                if (center != null) {
                    // 计算当前位置(复活点)到出生点中心的距离平方
                    double distSq = owner.position.distanceSq(center);
                    // 找到最近的那个
                    if (distSq < minDistanceSq) {
                        minDistanceSq = distSq;
                        nearestEnemySpawnCenter = center;
                    }
                }
            }
        }

        // 如果成功找到了最近的敌人出生点
        if (nearestEnemySpawnCenter != null && pathfindingModule != null) { // 添加 pathfindingModule null 检查
            // 设置寻路目标！让 AI 冲过去
            pathfindingModule.setTarget(nearestEnemySpawnCenter);
            if (logger != null) {
                // logger.accept("AI [" + owner.name + "] respawned. Charging towards nearest
                // enemy spawn at (" + (int)nearestEnemySpawnCenter.x + ", " +
                // (int)nearestEnemySpawnCenter.y + ")");
            }
        } else {
            // 如果找不到敌人出生点 (地图配置问题?), 则退回随机巡逻
            if (logger != null) {
                logger.accept("AI [" + owner.name
                        + "] respawned. Could not find enemy spawn points or pathfinding module, starting patrol.");
            }
            // 不需要额外操作，因为状态已经是 PATROLLING，executeActions 会处理
        }
    }

    /** 比赛/回合结束时立即撤销寻路、攻击和异步投掷计划。 */
    public void cancelPendingActions() {
        primaryTarget = null;
        lastKnownPosition = null;
        currentState = AIState.PATROLLING;
        if (pathfindingModule != null)
            pathfindingModule.reset();
        if (attackModule != null)
            attackModule.reset();
        if (grenadeModule != null)
            grenadeModule.cancelPendingWork();
    }

    /**
     * 当 AI 玩家获得击杀时，由 GameState.handlePlayerKill 调用。
     * 用于重置武器死亡计数，并清除对被击杀目标的感知。
     */
    public void onKill() {
        // 添加 owner null 检查
        if (owner == null)
            return;

        Weapon weaponOnKill = owner.getCurrentWeapon(); // 检查当前拿的武器
        // 如果是用 AI 自己选的武器（通常是主武器）拿到了击杀
        if (weaponOnKill != null && weaponOnKill == this.currentTdmWeapon) {
            if (this.deathsWithCurrentWeapon > 0) {
                // if (logger != null) { // 添加日志
                // logger.accept("AI [" + owner.name + "] got a kill with " +
                // weaponOnKill.name() + ". Resetting death streak.");
                // }
                this.deathsWithCurrentWeapon = 0; // 重置连死计数
            }
        }

        // --- VVVV 核心修改 VVVV ---
        // 击杀发生后，清除所有目标和记忆。
        this.primaryTarget = null;
        this.lastKnownPosition = null;

        // 通知 PerceptionModule 清除所有感知信息
        if (this.perceptionModule != null) {
            this.perceptionModule.clearPerceptions(); // 清空所有感知记录
        }

        // 重置状态机，让 AI 重新进入巡逻/搜寻下一个敌人
        this.currentState = AIState.PATROLLING;
        // 重置寻路目标，让巡逻逻辑重新计算
        if (this.pathfindingModule != null) {
            this.pathfindingModule.setTarget(null);
        }
    }

    /**
     * 内部辅助方法：从武器池中选择一把新武器，并更新 AI 状态。
     * 
     * @param weaponToAvoid 应该避免选择的武器 (通常是上一把)，可以为 null。
     */
    private void selectNewWeapon(Weapon weaponToAvoid) {
        // 添加 owner null 检查
        if (owner == null)
            return;

        // 1. 创建一个可用的武器选项列表
        List<Weapon> availableChoices = TDM_WEAPON_POOL.stream()
                .filter(w -> w != weaponToAvoid) // 过滤掉要避免的武器
                .collect(Collectors.toList());

        // 2. 如果过滤后列表为空 (例如池子里只有一把枪)，则不过滤
        if (availableChoices.isEmpty()) {
            availableChoices = new ArrayList<>(TDM_WEAPON_POOL); // 复制一份
        }
        // 防止 availableChoices 仍然为空 (如果 TDM_WEAPON_POOL 本身是空的)
        if (availableChoices.isEmpty()) {
            if (logger != null)
                logger.accept("错误：TDM 武器池为空！无法为 AI [" + owner.name + "] 选择武器。");
            this.currentTdmWeapon = null;
            owner.nextWeapon = (owner.team == Player.Team.T) ? "GLOCK18" : "USPS"; // 退回手枪
            return;
        }

        // 3. 随机选择一把新武器
        this.currentTdmWeapon = availableChoices.get(rand.nextInt(availableChoices.size()));

        // 4. 将武器名称设置到 Player 的相关字段
        if (this.currentTdmWeapon.getWeaponType().isPistol()) {
            owner.selectedSecondaryName = this.currentTdmWeapon.name();
        } else {
            owner.selectedPrimaryName = this.currentTdmWeapon.name();
        }

        // logger.accept("AI [" + owner.name + "] 已选择新武器: " +
        // this.currentTdmWeapon.name()); // 调试
    }

    /**
     * 检查AI是否被队友卡住
     * (因为撞墙已由 isWalkable() 提前阻止)
     */
    private boolean checkIfStuck(long currentTime) {
        // 添加 owner 或 owner.position null 检查
        if (owner == null || owner.position == null || pathfindingModule == null)
            return false;

        // 如果AI不应该移动，则不算卡住
        if (currentState == AIState.IDLE || currentState == AIState.TAKING_COVER || owner.isReloading
                || !pathfindingModule.isActive()) {
            stuckCount = 0;
            // 更新时间戳和位置，防止下次立即触发
            lastStuckCheckTime = currentTime;
            if (lastPositionForStuckCheck == null) { // 第一次初始化
                lastPositionForStuckCheck = (Point2D.Double) owner.position.clone();
            } else { // 正常更新
                lastPositionForStuckCheck.setLocation(owner.position.x, owner.position.y);
            }
            return false;
        }

        // 检查是否到了检查时间
        if (currentTime - lastStuckCheckTime > STUCK_CHECK_INTERVAL_MS) {
            // 确保 lastPositionForStuckCheck 已初始化
            if (lastPositionForStuckCheck == null) {
                lastPositionForStuckCheck = (Point2D.Double) owner.position.clone();
            }

            // 关键：AI正在寻路(isActive)，但位置却没变
            if (owner.position.distance(lastPositionForStuckCheck) < MIN_MOVEMENT_FOR_STUCK) {
                stuckCount++;
                if (stuckCount > 1) { // 连续1次(1.5秒)就触发
                    return true; // 确认被队友卡住！
                }
            } else {
                stuckCount = 0;
            }

            lastStuckCheckTime = currentTime;
            lastPositionForStuckCheck.setLocation(owner.position.x, owner.position.y); // 更新位置
        }
        return false;
    }

    /**
     * 执行主动避让队友的机动
     */
    private void performUnstuckManeuver(long currentTime) {
        // 添加 gameState 或 owner null 检查
        if (gameState == null || owner == null || owner.position == null || pathfindingModule == null)
            return;

        if (gameState.getIsAiFrozen())
            return; // 添加冻结检查

        if (logger != null) {
            AiDiagnostics.trace("unstuck", logger,
                    () -> "AI [" + owner.name + "] 被队友卡住! 执行侧向避让。");
        }

        // 1. 清除当前寻路目标（打断死锁）
        pathfindingModule.setTarget(null);

        // 2. 设置避让计时器
        this.unstuckUntil = currentTime + UNSTUCK_DURATION_MS;

        // 3. 计算一个随机的侧向目标点 (90度)
        double currentAngle = Double.isNaN(owner.angle) ? 0.0 : owner.angle; // 处理 NaN
        double dodgeAngle = currentAngle + (rand.nextBoolean() ? Math.PI / 2 : -Math.PI / 2);
        double dodgeDist = 100.0;
        // 确保计算出的点在地图内
        int width = (gameState.width > 0) ? gameState.width : 1024;
        int height = (gameState.height > 0) ? gameState.height : 1024;
        double dodgeX = owner.position.x + Math.cos(dodgeAngle) * dodgeDist;
        double dodgeY = owner.position.y + Math.sin(dodgeAngle) * dodgeDist;
        dodgeX = Math.max(Player.SIZE, Math.min(width - Player.SIZE, dodgeX));
        dodgeY = Math.max(Player.SIZE, Math.min(height - Player.SIZE, dodgeY));

        this.unstuckTarget = new Point2D.Double(dodgeX, dodgeY);

        // 4. 命令“腿”开始执行这个脱困路径
        pathfindingModule.setTarget(this.unstuckTarget);

        // 5. 重置计数器
        stuckCount = 0;
        lastStuckCheckTime = currentTime;
        // 确保 lastPositionForStuckCheck 在下次检查前有效
        if (lastPositionForStuckCheck == null) {
            lastPositionForStuckCheck = (Point2D.Double) owner.position.clone();
        } else {
            lastPositionForStuckCheck.setLocation(owner.position.x, owner.position.y);
        }
    }

    /**
     * 检查并处理队友碰撞避让逻辑 (最高优先级)。
     * 
     * @param currentTime 当前时间戳
     * @param worldView   AI的世界视图 (用于执行避让动作)
     * @return 如果正在执行或刚触发了避让，则返回 AIInput；否则返回 null。
     */
    private AIInput handleTeammateAvoidance(long currentTime, AIWorldView worldView) {
        // 添加 pathfindingModule null 检查
        if (pathfindingModule == null)
            return null;

        // A. 检查是否正在执行避让
        if (currentTime < unstuckUntil) {
            if (!pathfindingModule.isActive()) {
                // 避让路径提前走完了
                unstuckUntil = 0;
                unstuckTarget = null;
                currentState = AIState.PATROLLING;
                return null; // 避让结束，让主 update 逻辑继续
            }
            // 正在避让中：继续执行避让路径，并中断主 update 逻辑
            return pathfindingModule.update(worldView); // update 可能返回 null，需要检查
            // AIInput avoidanceMove = pathfindingModule.update(worldView);
            // return (avoidanceMove != null) ? avoidanceMove : new AIInput(new
            // ArrayList<>(), owner.angle, false, false, false); // 提供默认返回值
        }
        // B. 检查是否避让刚结束
        else if (unstuckTarget != null) {
            // 避让时间到，清除状态
            unstuckTarget = null;
            currentState = AIState.PATROLLING;
            pathfindingModule.setTarget(null);
            return null; // 避让结束，让主 update 逻辑继续
        }

        // C. 检查是否 *刚刚* 被队友卡住了
        if (checkIfStuck(currentTime)) {
            performUnstuckManeuver(currentTime);
            // 立刻开始执行避让路径，并中断主 update 逻辑
            // 再次检查 update 的返回值
            AIInput avoidanceMove = pathfindingModule.update(worldView);
            return (avoidanceMove != null) ? avoidanceMove
                    : new AIInput(new ArrayList<>(), owner.angle, false, false, false);
        }

        // D. 既没有在避让，也没有触发避让
        return null; // 告诉主 update 逻辑继续正常执行
    }

    /**
     * 改为获取一个矩形内的可保证无障碍物的点。
     * 优先尝试中心点，如果中心点被阻挡，则在周围小范围搜索。
     * 
     * @param rect 矩形对象
     * @return 矩形内的一个可赛地点的 Point2D.Double 对象。如果找不到或 rect 为 null，则返回 null。
     */
    private Point2D.Double getCenter(Rectangle rect) {
        // 基础检查
        if (rect == null || pathfindingModule == null) {
            return null;
        }
        // 检查矩形尺寸是否有效
        if (rect.width <= 0 || rect.height <= 0)
            return null;

        // 1. 计算几何中心点
        double centerX = rect.getCenterX();
        double centerY = rect.getCenterY();
        Point2D.Double geometricCenter = new Point2D.Double(centerX, centerY);

        // 2. 检查几何中心点是否可行走
        if (pathfindingModule.isWalkable(geometricCenter)) {
            return geometricCenter;
        }

        // 3. 如果中心点被阻挡，在其周围进行小范围搜索
        double playerSize = (Player.SIZE > 0) ? Player.SIZE : 16.0; // 获取有效的 Player.SIZE
        double searchStep = playerSize / 2.0;
        int maxAttemptsPerDirection = 3;

        int[][] directions = {
                { 0, -1 }, { 0, 1 }, { -1, 0 }, { 1, 0 },
                { -1, -1 }, { 1, -1 }, { -1, 1 }, { 1, 1 }
        };

        for (int[] dir : directions) {
            for (int step = 1; step <= maxAttemptsPerDirection; step++) {
                double candidateX = centerX + dir[0] * searchStep * step;
                double candidateY = centerY + dir[1] * searchStep * step;
                Point2D.Double candidatePoint = new Point2D.Double(candidateX, candidateY);

                if (rect.contains(candidatePoint)) {
                    if (pathfindingModule.isWalkable(candidatePoint)) {
                        return candidatePoint;
                    }
                } else {
                    break;
                }
            }
        }

        // 4. 找不到
        if (logger != null) {
            // logger.accept("警告: 在矩形 [" + (int)rect.x + "," + (int)rect.y + "," +
            // (int)rect.width + "," + (int)rect.height + "] 内找不到可行走的点！");
        }
        return null; // 明确返回 null 表示失败
    }

    /**
     * 使用 GameState 的空间网格来计算主动的队友避让。
     * 检查 AI 周围 3x3 的网格，如果发现近距离的队友，则计算一个“排斥”向量。
     *
     * @return 一个包含 "A" (左平移) 或 "D" (右平移) 的列表，用于引导 AI 绕开队友。
     */
    private List<String> calculateProactiveAvoidanceKeys() {
        List<String> avoidanceKeys = new ArrayList<>();

        // 1. 仅当 AI 正在寻路时才需要避让
        if (pathfindingModule == null || !pathfindingModule.isActive() || gameState == null) {
            return avoidanceKeys; // 返回空列表
        }

        // 2. 从 GameState 获取网格数据
        List<Player>[][] grid = gameState.getSpatialGrid();
        int cellSize = gameState.getGridCellSize();
        int gridWidth = gameState.getGridWidth();
        int gridHeight = gameState.getGridHeight();

        // 3. 检查网格是否有效
        if (grid == null || cellSize <= 0 || gridWidth == 0) {
            return avoidanceKeys; // 网格未初始化
        }

        // 4. 获取 AI 自己的位置和网格坐标
        Point2D.Double myPos = owner.position;
        int myGridX = (int) (myPos.x / cellSize);
        int myGridY = (int) (myPos.y / cellSize);

        Point2D.Double totalAvoidanceVector = new Point2D.Double(0, 0);
        int neighborsFound = 0;

        // 5. 遍历 AI 周围的 3x3 网格
        for (int x = -1; x <= 1; x++) {
            for (int y = -1; y <= 1; y++) {
                int checkX = myGridX + x;
                int checkY = myGridY + y;

                // 确保检查的网格在边界内
                if (checkX >= 0 && checkX < gridWidth && checkY >= 0 && checkY < gridHeight) {

                    // 6. 遍历该网格中的所有玩家
                    // 6. 遍历该网格中的所有玩家 (线程安全版)

                    // 6.1. 获取原始列表的引用
                    List<Player> playersInCell = grid[checkX][checkY];

                    // 6.2. 创建一个线程安全的“快照” (snapshot)
                    List<Player> safeSnapshot;
                    synchronized (playersInCell) { // <--- 使用与 GameState 相同的锁
                        safeSnapshot = new ArrayList<>(playersInCell);
                    }

                    // 6.3. 遍历这个安全的快照，而不是原始列表
                    for (Player other : safeSnapshot) {

                        // 6.4 添加防御性检查，彻底解决 NPE
                        if (other == null) {
                            continue;
                        }

                        // 7. 过滤：不是自己、是队友、还活着 (现在 'other' 绝不为 null)
                        if (other == owner || other.team != owner.team || !other.isAlive()) {
                            continue;
                        }

                        double distSq = myPos.distanceSq(other.position);

                        // 8. 检查是否在避让半径内 (AVOIDANCE_RADIUS_SQ = 36^2 = 1296)
                        if (distSq < AVOIDANCE_RADIUS_SQ) {
                            double dist = Math.sqrt(distSq);
                            double repulsionX, repulsionY;

                            if (dist < 0.5) {
                                // 如果完全重叠或极近，给一个基于 ID 的固定偏移推力，防止卡死或同频震荡
                                double randomAngle = (owner.id.hashCode() % 100) * (Math.PI * 2 / 100.0);
                                repulsionX = Math.cos(randomAngle);
                                repulsionY = Math.sin(randomAngle);
                                dist = 1.0;
                            } else {
                                repulsionX = myPos.x - other.position.x;
                                repulsionY = myPos.y - other.position.y;
                            }

                            // 10. 施加权重：距离越近，"力" 越大
                            double force = (AVOIDANCE_RADIUS - dist) / AVOIDANCE_RADIUS; // 线性衰减

                            // 11. 累加总的排斥向量
                            totalAvoidanceVector.x += (repulsionX / dist) * force;
                            totalAvoidanceVector.y += (repulsionY / dist) * force;
                            neighborsFound++;
                        }
                    }
                }
            }
        }

        // 12. 如果找到了需要避让的队友
        if (neighborsFound > 0) {
            double vecMag = totalAvoidanceVector.distance(0, 0);
            if (vecMag < 0.1) {
                return avoidanceKeys; // 总向量太小，忽略
            }

            // 15. 将排斥向量的 "力" 转换为平移按键
            // 提高阈值 (由 0.3 提升至 0.6)
            // 只有当排斥力足够大时才触发按键，有效防止 AI 在出生点轻微摩擦时的左右“鬼畜”摆动
            double forceThreshold = 0.6;

            if (totalAvoidanceVector.x < -forceThreshold) {
                avoidanceKeys.add("A"); // "力" 指向左 (X-), 按 "A"
            } else if (totalAvoidanceVector.x > forceThreshold) {
                avoidanceKeys.add("D"); // "力" 指向右 (X+), 按 "D"
            }

            if (totalAvoidanceVector.y < -forceThreshold) {
                avoidanceKeys.add("W"); // "力" 指向上 (Y-), 按 "W"
            } else if (totalAvoidanceVector.y > forceThreshold) {
                avoidanceKeys.add("S"); // "力" 指向下 (Y+), 按 "S"
            }
        }

        return avoidanceKeys;
    }

    public void resetForNewRound() {
        reset();
    }
}
