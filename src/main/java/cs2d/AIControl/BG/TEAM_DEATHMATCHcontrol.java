package cs2d.AIControl.BG;

// --- 新增 Import ---
import cs2d.AIControl.A.GrenadeModule;
import cs2d.server.Item; // 需要导入 Item 枚举
// --- 结束新增 Import ---

import cs2d.AIControl.A.AttackModule;
import cs2d.AIControl.A.PathfindingModule; // 导入 A 包
import cs2d.AIControl.B.PerceptionModule;
import cs2d.AIControl.B.PerceptionType;
import cs2d.AIControl.movement.CombatPosturePolicy;
import cs2d.AIControl.movement.CoverScanBudget;
import cs2d.AIControl.movement.LocalCoverPlanner;
import cs2d.AIControl.movement.LocomotionFacingPolicy;
import cs2d.AIControl.movement.MovementArbiter;
import cs2d.AIControl.movement.MovementDecision;
import cs2d.AIControl.movement.MovementIntent;
import cs2d.AIControl.movement.MovementProgressWatchdog;
import cs2d.AIControl.movement.QuadtreeCoverGeometryProbe;
import cs2d.AIControl.team.TacticalOrder;
import cs2d.AIControl.team.TacticalIdlePolicy;
import cs2d.AIControl.team.TeamTacticalSnapshot.Vec2;
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
    private final SoundPursuitGate soundPursuitGate = new SoundPursuitGate();
    private final MovementArbiter movementArbiter = new MovementArbiter();
    private final MovementProgressWatchdog movementProgressWatchdog = new MovementProgressWatchdog();
    private final LocalCoverPlanner localCoverPlanner = new LocalCoverPlanner();
    private final LocalCoverPlanner.GeometryProbe coverGeometry;
    private final LocomotionFacingPolicy locomotionFacingPolicy = new LocomotionFacingPolicy();
    private final CombatPosturePolicy combatPosturePolicy = new CombatPosturePolicy();
    private final TacticalIdlePolicy tacticalIdlePolicy = new TacticalIdlePolicy();
    private Point2D.Double committedCoverPoint;
    private long coverCommitUntil;
    private long nextCoverResponseTime;
    private long nextRangedCoverScanTime;
    private final long rangedCoverScanPhaseMs;

    // --- 状态机计时器 ---
    private long lastStateChangeTime = 0;
    private long lastPathRecalculationTime = 0;
    private long targetAcquiredTime = 0;
    private int shotsFiredAtCurrentTarget = 0;

    private long recoveryUntil;
    private List<String> recoveryKeys = List.of();
    private static final long RECOVERY_COMMIT_MS = 500;
    private static final double RECOVERY_STEP = Player.SIZE * 1.45;
    private static final double TEAMMATE_BLOCK_RADIUS_SQ = Math.pow(Player.SIZE * 1.8, 2.0);
    private static final double LEGACY_MOVEMENT_SUBSTEPS_PER_SECOND = 120.0;
    private static final double BASE_MOVEMENT_PER_SUBSTEP = 1.5;

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
    private static final long DAMAGE_COVER_WINDOW_MS = 900;
    private static final long COVER_COMMIT_MS = 2_200;
    private static final long COVER_RESPONSE_COOLDOWN_MS = 2_800;
    private static final long RANGED_COVER_SCAN_INTERVAL_MS = 600;
    private static final CoverScanBudget COVER_SCAN_BUDGET = new CoverScanBudget(2, 16);
    private static final double COVER_ARRIVAL_RADIUS_SQ = 30.0 * 30.0;
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
        this.coverGeometry = new QuadtreeCoverGeometryProbe(gameState, pathfindingModule);
        this.rangedCoverScanPhaseMs = Math.floorMod(owner.id == null ? 0 : owner.id.hashCode(),
                RANGED_COVER_SCAN_INTERVAL_MS);
        this.nextRangedCoverScanTime = System.currentTimeMillis() + rangedCoverScanPhaseMs;
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

        this.movementProgressWatchdog.reset(System.currentTimeMillis(), owner.position);

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
    public AIInput update(AIWorldView worldView, long currentTime) {
        return update(worldView, currentTime, null);
    }

    /**
     * Updates local combat behavior while optionally consuming a generic team
     * intent. Sight and immediate survival remain local responsibilities.
     */
    public AIInput update(AIWorldView worldView, long currentTime, TacticalOrder tacticalOrder) {

        soundPursuitGate.updateOrder(tacticalOrder, currentTime);

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
                return executeActions(worldView, currentTime, tacticalOrder); // 确保即使返回也要合并一下移动和瞄准？或者直接返回 grenadeActionInput ? 选后者更安全
                // return grenadeActionInput; // <--- 修正：应该直接返回指令
            }
        }
        // --- 结束新增 GrenadeModule 处理 ---

        // 1. 更新感知
        perceptionModule.update(worldView, /* 移除 relevantSounds */ currentTime); // <-- 调用已修改

        // 2. 选择目标 (逻辑不变)
        selectPrimaryTarget(currentTime, tacticalOrder);

        // 检查是否看到了敌人
        PerceptionModule.PerceptionInfo targetInfo = (this.primaryTarget != null)
                ? perceptionModule.getPerceptionInfo(this.primaryTarget.id)
                : null;
        boolean isTargetVisible = targetInfo != null && targetInfo.isCurrentlyVisible();
        updateReliableThreatMemory(currentTime, isTargetVisible);

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

        // 4. 只有最终移动长期没有产生预期位移时，才执行短暂恢复动作。
        AIInput unstuckMovement = currentRecoveryInput(currentTime);

        // 5. 统一仲裁所有移动提案，再产生唯一的最终行动
        return executeActions(worldView, currentTime, tacticalOrder, unstuckMovement);
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

        // --- 1. 高优先级生存决策：换弹或受击后进入确定的局部掩体 ---
        boolean coverReached = committedCoverPoint != null && owner.position != null
                && owner.position.distanceSq(committedCoverPoint) <= COVER_ARRIVAL_RADIUS_SQ;
        if (coverReached) {
            clearCoverCommitment();
        }
        boolean coverCommitActive = committedCoverPoint != null && currentTime < coverCommitUntil;
        Point2D.Double damageThreat = owner.lastDamageSourcePosition != null
                ? owner.lastDamageSourcePosition
                : this.lastKnownPosition;

        if (owner.isReloading) {
            if (!coverCommitActive) {
                beginCoverMovement(currentTime, damageThreat != null ? damageThreat : this.lastKnownPosition);
            }
            currentState = AIState.TAKING_COVER;
            nextTacticalDecisionTime = currentTime + TACTICAL_DECISION_COOLDOWN;
            return;
        }
        if (coverCommitActive) {
            currentState = AIState.TAKING_COVER;
            return;
        }
        boolean recentlyDamaged = damageThreat != null
                && currentTime - owner.lastDamageSourcePositionTime <= DAMAGE_COVER_WINDOW_MS;
        if (recentlyDamaged && currentTime >= nextCoverResponseTime && beginCoverMovement(currentTime, damageThreat)) {
            currentState = AIState.TAKING_COVER;
            nextCoverResponseTime = currentTime + COVER_RESPONSE_COOLDOWN_MS;
            return;
        }
        if (currentState == AIState.TAKING_COVER) {
            clearCoverCommitment();
            currentState = (this.lastKnownPosition != null) ? AIState.HUNTING : AIState.PATROLLING;
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
    private AIInput executeActions(AIWorldView worldView, long currentTime, TacticalOrder tacticalOrder) {
        return executeActions(worldView, currentTime, tacticalOrder, null);
    }

    private AIInput executeActions(AIWorldView worldView, long currentTime, TacticalOrder tacticalOrder,
            AIInput unstuckMovement) {
        AIInput attackInput = null;
        AIInput pathInput = null;
        boolean unstuckMovementActive = unstuckMovement != null;

        // --- 新增：检查 GrenadeModule 状态 ---
        boolean isGrenadeModuleAiming = (currentState == AIState.PREPARING_GRENADE);
        boolean isGrenadeModuleMoving = (currentState == AIState.MOVING_TO_THROW_SPOT);
        // --- 结束新增 ---

        TacticalIdlePolicy.Decision idleDecision = tacticalIdlePolicy.decide(tacticalOrder, currentTime,
                primaryTarget != null, pathfindingModule != null && pathfindingModule.isActive());
        boolean coverMovementActive = currentState == AIState.TAKING_COVER && committedCoverPoint != null;
        if (!coverMovementActive && idleDecision.holdPosition()
                && pathfindingModule != null && pathfindingModule.isActive()) {
            pathfindingModule.setTarget(null);
        }

        // 1. 根据状态设置寻路模块的“意图目标”
        // 如果 GrenadeModule 正在移动，则大脑不设置寻路目标
        boolean tacticalMovementApplied = unstuckMovementActive
                || coverMovementActive
                || idleDecision.holdPosition()
                || (!coverMovementActive && applyTacticalMovementIntent(tacticalOrder, currentTime));
        if (!isGrenadeModuleMoving && !isGrenadeModuleAiming && !tacticalMovementApplied) { // <-- 添加检查
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
                            // 局部几何评估是低频思考，不进入60Hz动作循环。
                            if (currentTime >= nextRangedCoverScanTime && COVER_SCAN_BUDGET.tryAcquire(currentTime)) {
                                nextRangedCoverScanTime = currentTime + RANGED_COVER_SCAN_INTERVAL_MS;
                                Point2D.Double coverPoint = findCover(this.primaryTarget.position);
                                if (coverPoint != null
                                        && coverPoint.distance(this.primaryTarget.position) < dist + 150) {
                                    pathfindingModule.setTarget(coverPoint);
                                } else {
                                    double angleToTarget = Math.atan2(
                                            this.primaryTarget.position.y - owner.position.y,
                                            this.primaryTarget.position.x - owner.position.x);
                                    double flankAngle = angleToTarget
                                            + (rand.nextBoolean() ? Math.PI / 4 : -Math.PI / 4);
                                    Point2D.Double flankPoint = new Point2D.Double(
                                            owner.position.x + Math.cos(flankAngle) * (dist * 0.5),
                                            owner.position.y + Math.sin(flankAngle) * (dist * 0.5));
                                    pathfindingModule.setTarget(pathfindingModule.isWalkable(flankPoint)
                                            ? flankPoint
                                            : this.primaryTarget.position);
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
        if (!unstuckMovementActive && attackModule != null) {
            attackInput = attackModule.update(this.primaryTarget, this.lastKnownPosition, currentTime);
        }
        if (unstuckMovementActive) {
            pathInput = unstuckMovement;
        } else if (pathfindingModule != null /* && !isGrenadeModuleMoving */) { // <--- 移除这个 !isGrenadeModuleMoving
                                                                          // 条件，让移动合并逻辑处理
            pathInput = pathfindingModule.update(worldView);
        }

        // 3. 合并结果
        List<String> finalKeys = new ArrayList<>();
        double finalAngle = owner.angle;
        boolean finalShooting = false;
        boolean finalWalking = idleDecision.walkSilently();

        // 所有思考模块只提交不可变意图；移动仲裁器是唯一按键决策者。
        List<MovementIntent> movementIntents = new ArrayList<>();
        if (unstuckMovementActive) {
            movementIntents.add(MovementIntent.exclusive("unstuck", 500, unstuckMovement.keys()));
        } else if (currentState != AIState.TAKING_COVER
                && attackInput != null && !attackInput.keys().isEmpty()) {
            movementIntents.add(MovementIntent.exclusive("combat", 400, attackInput.keys()));
        } else if (isGrenadeModuleMoving && pathInput != null) {
            movementIntents.add(MovementIntent.exclusive("grenade", 300, pathInput.keys()));
        } else if (!isGrenadeModuleAiming && !isGrenadeModuleMoving && pathInput != null) {
            movementIntents.add(MovementIntent.base("path", 100, pathInput.keys()));
        }
        MovementDecision movementDecision = movementArbiter.decide(movementIntents);
        finalKeys = new ArrayList<>(movementDecision.keys());

        // --- 决定瞄准角度和射击 ---
        if (unstuckMovementActive) {
            locomotionFacingPolicy.reset();
            finalAngle = combatPosturePolicy.chooseFacing(currentTime, owner.position,
                    finalKeys, unstuckMovement.angle());
            finalShooting = false;
        } else if (isGrenadeModuleAiming) {
            locomotionFacingPolicy.reset();
            // 优先级 1: 扔雷瞄准 (角度由 GrenadeModule 在 update 开头设置好，这里无需处理)
            // finalAngle = grenadeModule.getAimAngle(); // 假设有这个方法，或者直接使用owner.angle?
            // 不，HOLD_AND_AIM 命令已经设置了
            finalShooting = false; // 扔雷时不射击
        } else if (attackInput != null && (currentState == AIState.ATTACKING || attackInput.shooting())) {
            locomotionFacingPolicy.reset();
            // 优先级 2: 攻击瞄准 (来自 AttackModule)
            finalAngle = attackInput.angle();
            finalShooting = attackInput.shooting();
        } else if (pathInput != null && pathfindingModule.isActive() && !isGrenadeModuleAiming
                && !isGrenadeModuleMoving) {
            // 优先级 3: 路径只决定移动；朝向策略允许短距离倒退警戒。
            double travelFacing = locomotionFacingPolicy.chooseFacing(currentTime, owner.angle,
                    pathInput.angle(), finalKeys);
            finalAngle = combatPosturePolicy.chooseFacing(currentTime, owner.position, finalKeys, travelFacing);
            finalShooting = false;
        } else {
            locomotionFacingPolicy.reset();
            // 优先级 4: 短时间保持可靠交战方向，否则保持当前角度。
            finalAngle = combatPosturePolicy.chooseFacing(currentTime, owner.position, finalKeys, owner.angle);
            finalShooting = false;
        }

        boolean monitorProgress = !unstuckMovementActive
                && !isGrenadeModuleAiming
                && !isGrenadeModuleMoving
                && currentState != AIState.IDLE
                && !owner.isReloading
                && !gameState.shouldFreezeAi();
        MovementProgressWatchdog.Assessment progress = movementProgressWatchdog.observe(
                currentTime, owner.position, finalKeys,
                expectedMovementSpeedPerSecond(finalWalking), monitorProgress);
        if (progress.blocked()) {
            beginMovementRecovery(currentTime, progress);
        }

        // 返回最终合并的 AIInput
        return new AIInput(finalKeys, finalAngle, finalShooting, owner.isInteracting, finalWalking);
    }

    /**
     * 根据当前的 perceptionMap 选择最优先处理的目标。
     * [已修复] 确保在目标死亡或丢失时能正确清除 lastKnownPosition。
     */
    private void selectPrimaryTarget(long currentTime, TacticalOrder tacticalOrder) {
        Player oldTarget = this.primaryTarget; // 保留下旧目标用于比较

        // --- VVVV VVVV ---
        Player newBestTarget = null;
        Point2D.Double newBestLKP = null;
        // --- ^^^^ [修复结束] ^^^^ ---

        double minScore = Double.POSITIVE_INFINITY; // 初始化最低分数为无穷大
        int bestPerceptionTier = Integer.MAX_VALUE;
        PerceptionType newBestType = null;

        // 添加 perceptionModule null 检查
        if (perceptionModule == null) {
            this.primaryTarget = null;
            this.lastKnownPosition = null;
            return;
        }

        String lockedSoundTargetId = soundPursuitGate.lockedTargetId(currentTime);
        if (lockedSoundTargetId != null) {
            PerceptionModule.PerceptionInfo lockedInfo = perceptionModule.getPerceptionInfo(lockedSoundTargetId);
            Player lockedPlayer = gameState.getPlayerById(lockedSoundTargetId);
            if (lockedInfo == null || lockedPlayer == null || !lockedPlayer.isAlive()) {
                soundPursuitGate.clearTargetLock();
            }
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

            // 枪声和脚步只调动全队中距离最近的小组；视觉接敌始终立即响应。
            if (info.type() != PerceptionType.SIGHT) {
                double soundDistance = owner.position.distance(info.lastKnownPosition());
                if (tacticalOrder != null && tacticalOrder.isActive(currentTime)) {
                    if (!soundPursuitGate.canPursue(tacticalOrder, currentTime)
                            || !tacticalOrder.allowsSoundTarget(info.enemyId(), soundDistance)
                            || !soundPursuitGate.acceptsTarget(info.enemyId(), currentTime)) {
                        continue;
                    }
                } else {
                    // 无指挥任务时保持伏击。声音只提供警觉，不允许自行离开位置追击。
                    continue;
                }
            }

            double distanceSq = owner.position.distanceSq(info.lastKnownPosition());
            double score = distanceSq; // 基础分数是距离
            int perceptionTier = info.type() == PerceptionType.SIGHT ? 0 : 1;

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

            if (perceptionTier < bestPerceptionTier
                    || (perceptionTier == bestPerceptionTier && score < minScore)) {
                bestPerceptionTier = perceptionTier;
                minScore = score; // 更新最低分数
                newBestTarget = enemyPlayer; // 更新最佳目标
                newBestType = info.type();
                if (perceptionTier == 1 && tacticalOrder != null
                        && tacticalOrder.taskType() == TacticalOrder.TaskType.RESPOND_TO_CONTACT
                        && tacticalOrder.movementTarget() != null) {
                    Vec2 objective = tacticalOrder.movementTarget();
                    Point2D.Double fixedBattleZone = new Point2D.Double(objective.x(), objective.y());
                    newBestLKP = pathfindingModule != null && pathfindingModule.isWalkable(fixedBattleZone)
                            ? fixedBattleZone
                            : info.lastKnownPosition();
                } else {
                    newBestLKP = info.lastKnownPosition(); // 更新最佳目标位置
                }
            }
        } // --- 敌人信息遍历结束 ---

        if (newBestType == PerceptionType.SIGHT) {
            soundPursuitGate.clearTargetLock();
        } else if (newBestTarget != null) {
            soundPursuitGate.lockTarget(newBestTarget.id, currentTime);
        }

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
     * Translates a high-level movement objective into the existing path module.
     * The tactical layer does not know about keys, A* or controller states.
     */
    private boolean applyTacticalMovementIntent(TacticalOrder order, long currentTime) {
        if (order == null || !order.isActive(currentTime) || order.movementTarget() == null
                || primaryTarget != null || pathfindingModule == null || owner.position == null) {
            return false;
        }
        if (order.taskType() == TacticalOrder.TaskType.RESPOND_TO_CONTACT
                && !soundPursuitGate.canPursue(order, currentTime)) {
            return false;
        }

        Vec2 target = order.movementTarget();
        Point2D.Double targetPoint = new Point2D.Double(target.x(), target.y());
        if (order.taskType() == TacticalOrder.TaskType.RESPOND_TO_CONTACT
                && !pathfindingModule.isWalkable(targetPoint)) {
            return false;
        }
        double arrivalRadius = Math.max(20.0, order.arrivalRadius());
        if (owner.position.distanceSq(targetPoint) <= arrivalRadius * arrivalRadius) {
            if (pathfindingModule.isActive()) {
                pathfindingModule.setTarget(null);
            }
            return true;
        }

        boolean authoredManeuverRoute = order.preserveMapRoute()
                && order.routeId() != null
                && (order.taskType() == TacticalOrder.TaskType.FLANK
                || order.taskType() == TacticalOrder.TaskType.SUPPRESS
                || order.taskType() == TacticalOrder.TaskType.ADVANCE);
        if (authoredManeuverRoute && pathfindingModule.followPresetRoute(order.routeId(), targetPoint)) {
            return true;
        }

        if (!order.preserveMapRoute() && pathfindingModule.isFollowingPresetPath()) {
            pathfindingModule.cancelPresetPath();
        }
        Point2D.Double existing = pathfindingModule.getTargetPosition();
        if (existing == null || existing.distanceSq(targetPoint) > 25.0 * 25.0) {
            pathfindingModule.setTarget(targetPoint);
        }
        return true;
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
        if (owner == null || owner.position == null || threatPosition == null
                || pathfindingModule == null || pathfindingModule.pathfinder == null || gameState == null) {
            return calculateRetreatPosition();
        }

        List<Point2D.Double> teammatePositions = gameState.getPlayers().stream()
                .filter(player -> player != null && player != owner && player.isAlive() && player.team == owner.team)
                .map(player -> player.position)
                .filter(Objects::nonNull)
                .map(point -> new Point2D.Double(point.x, point.y))
                .toList();
        return localCoverPlanner.findBestCover(owner.position, threatPosition, teammatePositions, coverGeometry)
                .orElseGet(this::calculateRetreatPosition);
    }

    private boolean beginCoverMovement(long currentTime, Point2D.Double threatPosition) {
        if (pathfindingModule == null || owner == null || owner.position == null
                || !COVER_SCAN_BUDGET.tryAcquire(currentTime)) {
            return false;
        }
        Point2D.Double coverPoint = findCover(threatPosition);
        if (coverPoint == null || !pathfindingModule.isWalkable(coverPoint)) {
            return false;
        }
        committedCoverPoint = coverPoint;
        coverCommitUntil = currentTime + COVER_COMMIT_MS;
        pathfindingModule.setTarget(coverPoint);
        return true;
    }

    private void clearCoverCommitment() {
        committedCoverPoint = null;
        coverCommitUntil = 0;
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
        this.soundPursuitGate.reset();
        this.locomotionFacingPolicy.reset();
        this.combatPosturePolicy.reset();
        this.movementProgressWatchdog.reset(System.currentTimeMillis(), owner.position);
        clearCoverCommitment();
        this.nextCoverResponseTime = 0;
        this.nextRangedCoverScanTime = System.currentTimeMillis() + rangedCoverScanPhaseMs;
        this.recoveryUntil = 0;
        this.recoveryKeys = List.of();

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
        soundPursuitGate.reset();
        locomotionFacingPolicy.reset();
        combatPosturePolicy.reset();
        movementProgressWatchdog.reset(System.currentTimeMillis(), owner.position);
        clearCoverCommitment();
        nextCoverResponseTime = 0;
        nextRangedCoverScanTime = System.currentTimeMillis() + rangedCoverScanPhaseMs;
        recoveryUntil = 0;
        recoveryKeys = List.of();
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
        // 击杀后清除选敌状态，但保留短暂的可靠威胁朝向，避免立刻背向交战区。
        this.primaryTarget = null;
        this.lastKnownPosition = null;
        this.soundPursuitGate.reset();
        this.locomotionFacingPolicy.reset();
        this.movementProgressWatchdog.reset(System.currentTimeMillis(), owner.position);
        this.recoveryUntil = 0;
        this.recoveryKeys = List.of();
        clearCoverCommitment();
        this.nextRangedCoverScanTime = System.currentTimeMillis() + rangedCoverScanPhaseMs;

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

    private void updateReliableThreatMemory(long currentTime, boolean targetVisible) {
        if (targetVisible && primaryTarget != null && primaryTarget.position != null) {
            combatPosturePolicy.observeThreat(currentTime, primaryTarget.position,
                    CombatPosturePolicy.ThreatEvidence.VISIBLE_ENEMY);
            return;
        }
        if (owner.lastDamageSourcePosition != null
                && currentTime - owner.lastDamageSourcePositionTime <= CombatPosturePolicy.THREAT_MEMORY_MS) {
            combatPosturePolicy.observeThreat(currentTime, owner.lastDamageSourcePosition,
                    CombatPosturePolicy.ThreatEvidence.DAMAGE_SOURCE);
        }
    }

    private AIInput currentRecoveryInput(long currentTime) {
        if (currentTime >= recoveryUntil) {
            if (recoveryUntil > 0) {
                recoveryUntil = 0;
                recoveryKeys = List.of();
                movementProgressWatchdog.reset(currentTime, owner.position);
            }
            return null;
        }
        return new AIInput(recoveryKeys, angleForKeys(recoveryKeys, owner.angle), false, false, false);
    }

    private void beginMovementRecovery(long currentTime, MovementProgressWatchdog.Assessment progress) {
        if (owner.position == null || pathfindingModule == null || currentTime < recoveryUntil) {
            return;
        }

        double directionX = progress.intendedDirectionX();
        double directionY = progress.intendedDirectionY();
        Player blocker = findBlockingTeammate(directionX, directionY);
        boolean blockerIsMoving = blocker != null && Math.hypot(blocker.vx, blocker.vy) > 0.15;
        if (blockerIsMoving && compareStableId(owner.id, blocker.id) <= 0) {
            // The same pair always grants the same bot right of way. It keeps its
            // route; only the yielding bot performs a recovery movement.
            movementProgressWatchdog.reset(currentTime, owner.position);
            return;
        }

        recoveryKeys = chooseRecoveryKeys(directionX, directionY, blocker);
        recoveryUntil = currentTime + RECOVERY_COMMIT_MS;
        movementProgressWatchdog.reset(currentTime, owner.position);

        if (blocker == null) {
            pathfindingModule.requestRepath();
        }
        if (logger != null) {
            String reason = blocker == null ? "path" : "teammate " + blocker.name;
            AiDiagnostics.trace("unstuck", logger,
                    () -> "AI [" + owner.name + "] movement made no progress; recovering from " + reason + ".");
        }
    }

    private List<String> chooseRecoveryKeys(double directionX, double directionY, Player blocker) {
        int stableSide = stableRecoverySide(owner.id, blocker == null ? null : blocker.id);
        double firstX = stableSide * directionY;
        double firstY = stableSide * -directionX;
        double secondX = -firstX;
        double secondY = -firstY;

        if (isRecoveryPointWalkable(firstX, firstY)) {
            return keysForDirection(firstX, firstY);
        }
        if (isRecoveryPointWalkable(secondX, secondY)) {
            return keysForDirection(secondX, secondY);
        }
        if (isRecoveryPointWalkable(-directionX, -directionY)) {
            return keysForDirection(-directionX, -directionY);
        }
        return List.of();
    }

    private boolean isRecoveryPointWalkable(double directionX, double directionY) {
        if (Math.hypot(directionX, directionY) < 0.1) {
            return false;
        }
        Point2D.Double candidate = new Point2D.Double(
                owner.position.x + directionX * RECOVERY_STEP,
                owner.position.y + directionY * RECOVERY_STEP);
        return pathfindingModule.isWalkable(candidate);
    }

    private Player findBlockingTeammate(double directionX, double directionY) {
        if (Math.hypot(directionX, directionY) < 0.1) {
            return null;
        }
        Player nearest = null;
        double nearestDistanceSq = Double.POSITIVE_INFINITY;
        for (Player other : gameState.getPlayers()) {
            if (other == null || other == owner || !other.isAlive() || other.team != owner.team
                    || other.position == null) {
                continue;
            }
            double relativeX = other.position.x - owner.position.x;
            double relativeY = other.position.y - owner.position.y;
            double distanceSq = relativeX * relativeX + relativeY * relativeY;
            if (distanceSq >= TEAMMATE_BLOCK_RADIUS_SQ) {
                continue;
            }
            double forward = relativeX * directionX + relativeY * directionY;
            double lateral = Math.abs(relativeX * -directionY + relativeY * directionX);
            if (forward > -Player.SIZE * 0.15 && lateral < Player.SIZE * 0.9
                    && distanceSq < nearestDistanceSq) {
                nearest = other;
                nearestDistanceSq = distanceSq;
            }
        }
        return nearest;
    }

    private double expectedMovementSpeedPerSecond(boolean walking) {
        Weapon weapon = owner.getCurrentWeapon();
        double movementPerSubstep = BASE_MOVEMENT_PER_SUBSTEP
                * (weapon == null ? 1.0 : weapon.speedMultiplier);
        if (walking) {
            movementPerSubstep *= 0.5;
        }
        if (owner.isSlowed) {
            movementPerSubstep *= 0.3;
        }
        return movementPerSubstep * LEGACY_MOVEMENT_SUBSTEPS_PER_SECOND;
    }

    private static int stableRecoverySide(String ownerId, String blockerId) {
        String pair = String.valueOf(ownerId) + ':' + String.valueOf(blockerId);
        return (pair.hashCode() & 1) == 0 ? -1 : 1;
    }

    private static List<String> keysForDirection(double x, double y) {
        List<String> keys = new ArrayList<>(2);
        if (y < -0.25) {
            keys.add("W");
        } else if (y > 0.25) {
            keys.add("S");
        }
        if (x < -0.25) {
            keys.add("A");
        } else if (x > 0.25) {
            keys.add("D");
        }
        return keys;
    }

    private static double angleForKeys(List<String> keys, double fallback) {
        double x = (keys.contains("D") ? 1.0 : 0.0) - (keys.contains("A") ? 1.0 : 0.0);
        double y = (keys.contains("S") ? 1.0 : 0.0) - (keys.contains("W") ? 1.0 : 0.0);
        return Math.hypot(x, y) > 0.0 ? Math.atan2(y, x) : fallback;
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

    private static int compareStableId(String left, String right) {
        String safeLeft = left == null ? "" : left;
        String safeRight = right == null ? "" : right;
        return safeLeft.compareTo(safeRight);
    }

    public void resetForNewRound() {
        reset();
    }
}
