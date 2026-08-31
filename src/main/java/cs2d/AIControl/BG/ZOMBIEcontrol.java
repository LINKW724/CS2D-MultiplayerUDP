package cs2d.AIControl.BG;

import java.util.*;

// 导入所有必需的模块
import cs2d.AIControl.A.AttackModule;
import cs2d.AIControl.A.GrenadeModule;
import cs2d.AIControl.A.PathfindingModule;
import cs2d.AIControl.B.PerceptionModule;
import cs2d.playerAndAi.Player;
import cs2d.playerAndAi.Weapon;
import cs2d.server.AIService.AIInput;
import cs2d.server.AIService.AIWorldView;
import cs2d.server.AIDifficulty;
import cs2d.server.GameState;
import cs2d.server.AiDiagnostics;
import cs2d.server.Item; // 导入 Item 枚举

import java.awt.geom.Point2D;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.Random;
import java.util.function.Consumer;
import java.util.stream.Collectors; // 导入 Collectors

/**
 * 僵尸模式 (ZOMBIE Mode) 的 AI 总控制器。
 * 协调感知、攻击和寻路模块，以执行特定阵营的行为。
 * - CT (幸存者): 执行风筝(Kiting)战术，并使用道具。
 * - ZOMBIE (僵尸): 执行简单的冲锋和近战攻击，索敌结合感知和随机选择。
 */
public class ZOMBIEcontrol {
    // --- 核心模块引用 ---
    private final Player owner;
    private final GameState gameState;
    private final AIDifficulty difficulty;
    private final PerceptionModule perceptionModule;
    private final AttackModule attackModule;
    private final PathfindingModule pathfindingModule;
    private final GrenadeModule grenadeModule; // 幸存者需要用道具
    private final Consumer<String> logger;
    // --- 内部状态 ---
    private Random rand = new Random();
    private AIState currentState = AIState.PATROLLING;
    private Player primaryTarget = null;
    private Point2D.Double lastKnownPosition = null;

    // --- [REALISTIC 难度专项：蜂群意识共享] ---
    // 记录 REALISTIC AI 正在集火的目标 (AI_ID -> Target_ID)
    private static final java.util.concurrent.ConcurrentHashMap<String, String> SHARED_FOCUS_TARGETS = new java.util.concurrent.ConcurrentHashMap<>();
    // 记录 REALISTIC AI 发现的敌人位置，即使队友没看到也能共享 (Enemy_ID -> Position)
    private static final java.util.concurrent.ConcurrentHashMap<String, Point2D.Double> SHARED_ENEMY_MAP = new java.util.concurrent.ConcurrentHashMap<>();
    // 记录 REALISTIC AI 的实时状态，用于阵型维持和掩护 (AI_ID -> Player)
    private static final java.util.concurrent.ConcurrentHashMap<String, Player> SHARED_TEAMMATE_STATES = new java.util.concurrent.ConcurrentHashMap<>();
    // 记录全局撤退信号 (时间戳 -> 撤退方向)
    private static final java.util.concurrent.ConcurrentHashMap<Long, Point2D.Double> SHARED_RETREAT_SIGNAL = new java.util.concurrent.ConcurrentHashMap<>();

    // --- 状态机计时器 ---
    private long lastStateChangeTime = 0;
    private long lastPathRecalculationTime = 0;
    private long nextTacticalDecisionTime = 0; // 幸存者下次扔雷的时间

    // --- 僵尸近战计时器 ---
    private long lastMeleeTime = 0;
    private static final long MELEE_COOLDOWN_MS = 1000; // 僵尸攻击冷却
    private static final int ZOMBIE_MELEE_DAMAGE = 20; // 僵尸攻击伤害
    private static final double MELEE_ATTACK_DISTANCE = Player.SIZE * 1.5; // 僵尸攻击距离

    // --- 幸存者战术常量 ---
    private static final long TACTICAL_DECISION_COOLDOWN = 3000; // 幸存者扔雷决策冷却
    private static final double KITING_ENGAGE_DISTANCE = 600; // 幸存者开始风筝的距离
    private static final double KITING_MAINTAIN_DISTANCE = 400; // 幸存者试图保持的距离

    /**
     * 僵尸模式的 AI 行为状态机。
     */
    public enum AIState {
        // 共享状态
        PATROLLING, // 巡逻 (双方)
        HUNTING, // 追击 (CT追猎LKP, Zombie冲锋)

        // CT (幸存者) 状态
        KITING, // 风筝 (边退边打)
        RELOADING, // 换弹 (寻找掩体)
        PREPARING_GRENADE, // 准备扔雷
        MOVING_TO_THROW_SPOT, // 移动到投掷点

        // ZOMBIE (僵尸) 状态
        ATTACKING // 近战攻击
    }

    /**
     * 构造函数：初始化 ZOMBIE 控制器并传入所有必要的模块。
     *
     * @param owner             AI 所属的玩家实体
     * @param gameState         当前游戏状态对象
     * @param difficulty        当前 AI 难度设置
     * @param perceptionModule  负责处理环境感知的模块
     * @param attackModule      负责处理攻击逻辑的模块
     * @param pathfindingModule 负责处理寻路和移动的模块
     * @param logger            用于记录调试信息的日志函数
     */
    public ZOMBIEcontrol(Player owner, GameState gameState, AIDifficulty difficulty,
            PerceptionModule perceptionModule,
            AttackModule attackModule,
            PathfindingModule pathfindingModule,
            Consumer<String> logger) {
        this.owner = owner;
        this.gameState = gameState;
        this.difficulty = difficulty;
        this.perceptionModule = perceptionModule;
        this.attackModule = attackModule;
        this.pathfindingModule = pathfindingModule;
        this.logger = logger;
        this.rand = new Random();

        // 僵尸不需要 GrenadeModule，但幸存者需要
        if (owner.team == Player.Team.CT) {
            if (owner.getPathfindingModule() == null || owner.getPathfindingModule().pathfinder == null) {
                throw new IllegalStateException(
                        "Pathfinder must be initialized before GrenadeModule for AI: " + owner.name);
            }
            this.grenadeModule = new GrenadeModule(owner, gameState, owner.getPathfindingModule().pathfinder,
                    difficulty, logger, this.rand);
        } else {
            this.grenadeModule = null; // 僵尸没有道具模块
        }
    }

    /**
     * AI 的核心更新方法，由 AIService 调用。
     *
     * @param worldView   AI的当前世界快照
     * @param currentTime 当前系统时间戳(毫秒)
     * @return 当前帧的AI输入指令
     */
    public AIInput update(AIWorldView worldView, long currentTime) {
        // 兼容新的 PerceivedPlayer 结构，提取快照
        List<Player.PlayerSnapshot> snapshots = worldView.players().stream()
                .map(cs2d.server.AIService.PerceivedPlayer::snapshot)
                .collect(Collectors.toList());

        // --- 步骤 0: 幸存者处理 GrenadeModule (最高优先级) ---
        if (owner.team == Player.Team.CT && grenadeModule != null) {
            boolean isStationary = (owner.vx * owner.vx + owner.vy * owner.vy < 0.1);
            boolean targetInLoS = snapshots.stream()
                    .anyMatch(s -> primaryTarget != null && s.id().equals(primaryTarget.id));

            Optional<GrenadeModule.GrenadeCommand> grenadeCmd = grenadeModule.update(
                    this.primaryTarget,
                    this.lastKnownPosition,
                    null, // 僵尸模式没有复杂的 TDM 状态，传入 null
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
                        // 模块内部状态已重置，大脑状态恢复 (简化处理)
                        nextState = AIState.PATROLLING;
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
                // 对于 MOVE_TO_SPOT, DO_NOTHING, 让后续逻辑处理
                // 对于 SWITCH_SLOT, HOLD_AND_AIM, THROW_NOW，已经处理，但仍需执行 executeActions
                // 来合并可能存在的移动/瞄准残留
                if (cmd.action() != GrenadeModule.ActionType.MOVE_TO_SPOT
                        && cmd.action() != GrenadeModule.ActionType.DO_NOTHING) {
                    // 继续执行 executeActions
                }
            }
        }
        // --- 结束 GrenadeModule 处理 ---

        // 1. 更新感知
        perceptionModule.update(worldView, currentTime);

        // 2. 选择目标 (确保只选择敌对阵营，僵尸添加随机逻辑)
        selectPrimaryTarget(currentTime);

        // 3. 运行僵尸模式状态机
        runZombieModeStateMachine(currentTime);

        // 4. 避让 (僵尸也需要避让其他僵尸)
        // (此处省略了 TDMcontrol 的 handleTeammateAvoidance 逻辑，您可以复制过来)

        // 5. 执行并合并模块
        return executeActions(worldView, currentTime);
    }

    /**
     * 运行 ZOMBIE 模式的状态机，根据阵营更新 currentState。
     */
    private void runZombieModeStateMachine(long currentTime) {
        AIState oldState = this.currentState;

        // --- 0. 检查是否正在执行扔雷流程 (仅CT) ---
        if (owner.team == Player.Team.CT &&
                (currentState == AIState.PREPARING_GRENADE || currentState == AIState.MOVING_TO_THROW_SPOT)) {
            return; // 状态机由 GrenadeModule 控制
        }

        // --- 1. 高优先级中断：幸存者换弹 ---
        if (owner.team == Player.Team.CT && owner.isReloading) {
            if (currentState != AIState.RELOADING) {
                currentState = AIState.RELOADING;
                Point2D.Double coverPoint = findCover(this.lastKnownPosition);
                if (coverPoint != null) {
                    pathfindingModule.setTarget(coverPoint);
                } else {
                    pathfindingModule.setTarget(calculateRetreatPosition(this.lastKnownPosition));
                }
            }
            // 换弹时，不改变状态机的其他判断，只让 executeActions 处理移动
        } else if (currentState == AIState.RELOADING) {
            // 换弹结束，下一帧根据目标情况决定状态
        }

        // --- 2. 核心状态转换 (分阵营) ---
        PerceptionModule.PerceptionInfo targetInfo = (this.primaryTarget != null)
                ? perceptionModule.getPerceptionInfo(this.primaryTarget.id)
                : null;
        // 注意：对于僵尸的随机目标，targetInfo 可能为 null
        boolean isTargetVisible = (targetInfo != null && targetInfo.isCurrentlyVisible()) ||
                (primaryTarget != null && pathfindingModule.hasLineOfSight(primaryTarget.position)); // 添加直接视线检查

        if (owner.team == Player.Team.CT) {
            // --- 幸存者 (CT) 逻辑 ---
            // 如果正在换弹，保持 RELOADING 状态
            if (owner.isReloading) {
                currentState = AIState.RELOADING;
            } else if (isTargetVisible) {
                double distance = owner.position.distance(primaryTarget.position);
                if (distance < KITING_ENGAGE_DISTANCE) {
                    currentState = AIState.KITING;
                    // 在风筝时考虑扔道具
                    if (currentTime >= nextTacticalDecisionTime) {
                        decideAndRequestThrow(currentTime);
                        nextTacticalDecisionTime = currentTime + TACTICAL_DECISION_COOLDOWN;
                    }
                } else {
                    // 目标太远，进入追猎 (HUNTING)
                    currentState = AIState.HUNTING;
                }
            } else if (this.lastKnownPosition != null) {
                currentState = AIState.HUNTING; // 追猎LKP
            } else {
                currentState = AIState.PATROLLING; // 巡逻
            }

        } else if (owner.team == Player.Team.ZOMBIE) {
            // --- 僵尸 (ZOMBIE) 逻辑 ---
            if (this.primaryTarget != null) { // 只要有目标 (感知到的或随机选的)
                double distance = owner.position.distance(this.primaryTarget.position);
                if (distance < MELEE_ATTACK_DISTANCE) {
                    currentState = AIState.ATTACKING; // 近战
                } else {
                    currentState = AIState.HUNTING; // 冲锋
                }
            } else {
                // 没有目标（理论上因为随机选择逻辑，不太可能发生，除非没有活着的CT）
                currentState = AIState.PATROLLING; // 巡逻
            }
        }

        if (oldState != this.currentState) {
            this.lastStateChangeTime = currentTime;
        }
    }

    /**
     * 僵尸执行近战攻击的逻辑 (带冷却)
     */
    private void handleMeleeAttack(long currentTime) {
        if (primaryTarget == null || !primaryTarget.isAlive()) {
            return;
        }

        if (currentTime - lastMeleeTime > MELEE_COOLDOWN_MS) {
            // logger.accept("Zombie " + owner.name + " is attacking " +
            // primaryTarget.name);
            gameState.requestMelee(owner, primaryTarget, ZOMBIE_MELEE_DAMAGE);
            lastMeleeTime = currentTime;
        }
    }

    /**
     * (幸存者) 决定是否扔道具 (僵尸模式定制版)
     */
    private void decideAndRequestThrow(long currentTime) {
        if (grenadeModule == null || primaryTarget == null)
            return;

        Item itemToThrow = null;

        // 检查僵尸群
        long visibleZombies = countVisibleEnemies();

        if (visibleZombies >= 3) {
            // 僵尸扎堆，优先扔火
            if (owner.equipment.containsKey(Item.MOLOTOV)) {
                itemToThrow = Item.MOLOTOV;
            } else if (owner.equipment.containsKey(Item.INCENDIARY)) {
                itemToThrow = Item.INCENDIARY;
            } else if (owner.equipment.containsKey(Item.HE_GRENADE)) {
                itemToThrow = Item.HE_GRENADE;
            }
        } else if (visibleZombies >= 1) {
            // 少量僵尸，考虑扔手雷
            if (owner.equipment.containsKey(Item.HE_GRENADE) && rand.nextDouble() < 0.5) {
                itemToThrow = Item.HE_GRENADE;
            }
        }

        if (itemToThrow != null) {
            boolean accepted = grenadeModule.requestThrow(itemToThrow, primaryTarget.position);
            if (accepted) {
                Item decidedItem = itemToThrow;
                AiDiagnostics.trace("grenadeDecision", logger,
                        () -> "Survivor [" + owner.name + "] decided to throw " + decidedItem.name()
                                + " at zombie horde.");
            }
            // 无论是否接受，都重置冷却
            nextTacticalDecisionTime = currentTime + TACTICAL_DECISION_COOLDOWN;
        }
    }

    /**
     * (幸存者) 统计视野内的敌人 (僵尸) 数量
     */
    private long countVisibleEnemies() {
        if (perceptionModule == null)
            return 0;

        return perceptionModule.getAllPerceivedEnemies().values().stream()
                .filter(info -> info != null && info.isCurrentlyVisible())
                .count();
    }

    // --- 辅助方法 (大部分从 TDMcontrol 复制而来) ---

    /**
     * (已修改) 选择最优先的目标。
     * 僵尸：优先感知("气味")，若无则随机选CT。
     * 幸存者：优先感知(僵尸)。
     */
    private void selectPrimaryTarget(long currentTime) {
        if (perceptionModule == null || gameState == null) {
            this.primaryTarget = null;
            this.lastKnownPosition = null;
            return;
        }

        Player oldTarget = this.primaryTarget;
        Player newBestTarget = null;
        Point2D.Double newBestLKP = null;
        double minScore = Double.POSITIVE_INFINITY;

        // 清理过期的共享数据 (简单处理)
        if (difficulty == AIDifficulty.REALISTIC && rand.nextInt(100) == 0) {
            SHARED_ENEMY_MAP.entrySet().removeIf(entry -> {
                Player p = gameState.getPlayerById(entry.getKey());
                return p == null || !p.isAlive();
            });
        }

        // --- 步骤 1: 汇总感知信息 ---
        for (PerceptionModule.PerceptionInfo info : perceptionModule.getAllPerceivedEnemies().values()) {
            if (info == null || info.enemyId() == null)
                continue;
            Player enemyPlayer = gameState.getPlayerById(info.enemyId());
            if (enemyPlayer == null || !enemyPlayer.isAlive())
                continue;

            // 阵营过滤
            if (owner.team == Player.Team.CT && enemyPlayer.team != Player.Team.ZOMBIE)
                continue;
            if (owner.team == Player.Team.ZOMBIE && enemyPlayer.team != Player.Team.CT)
                continue;

            // 共享发现的敌人位置
            if (difficulty == AIDifficulty.REALISTIC && info.isCurrentlyVisible()) {
                SHARED_ENEMY_MAP.put(info.enemyId(), enemyPlayer.position);
            }

            double distanceSq = owner.position.distanceSq(info.lastKnownPosition());
            double score = distanceSq;

            if (owner.team == Player.Team.CT && info.isCurrentlyVisible())
                score *= 0.1;

            // 集火逻辑：如果队友已经在打这个目标，增加优先级 (降低分数)
            if (difficulty == AIDifficulty.REALISTIC) {
                long teammatesTargeting = SHARED_FOCUS_TARGETS.values().stream()
                        .filter(tid -> tid.equals(info.enemyId()))
                        .count();
                score /= (1.0 + teammatesTargeting * 0.5); // 越多队友打，优先级越高
            }

            if (score < minScore) {
                minScore = score;
                newBestTarget = enemyPlayer;
                newBestLKP = info.lastKnownPosition();
            }
        }

        // 共享视野：如果自己没看到，看看队友有没有共享
        if (difficulty == AIDifficulty.REALISTIC && newBestTarget == null) {
            for (Map.Entry<String, Point2D.Double> entry : SHARED_ENEMY_MAP.entrySet()) {
                Player enemy = gameState.getPlayerById(entry.getKey());
                if (enemy == null || !enemy.isAlive())
                    continue;

                double distSq = owner.position.distanceSq(entry.getValue());
                if (distSq < 1500 * 1500) { // 共享距离限制
                    newBestTarget = enemy;
                    newBestLKP = entry.getValue();
                    break;
                }
            }
        }

        // --- 步骤 2: (仅限僵尸) 随机选择逻辑保持不变 ---
        if (owner.team == Player.Team.ZOMBIE && newBestTarget == null) {
            List<Player> aliveCTs = gameState.getPlayers().stream()
                    .filter(p -> p != null && p.isAlive() && p.team == Player.Team.CT)
                    .collect(Collectors.toList());
            if (!aliveCTs.isEmpty()) {
                newBestTarget = aliveCTs.get(rand.nextInt(aliveCTs.size()));
                newBestLKP = newBestTarget.position;
            }
        }

        // --- 步骤 3: 更新目标并广播集火信号 ---
        if (oldTarget != newBestTarget) {
            if (difficulty == AIDifficulty.REALISTIC) {
                if (newBestTarget != null)
                    SHARED_FOCUS_TARGETS.put(owner.id, newBestTarget.id);
                else
                    SHARED_FOCUS_TARGETS.remove(owner.id);
            }
        }
        this.primaryTarget = newBestTarget;
        this.lastKnownPosition = newBestLKP;
    }

    /**
     * (公用) 寻找一个随机的巡逻点。
     */
    private Point2D.Double findPatrolPoint() {
        int width = (gameState != null && gameState.width > 0) ? gameState.width : 1024;
        int height = (gameState != null && gameState.height > 0) ? gameState.height : 1024;

        // 简化：直接返回随机点，不强制检查 walkable，因为寻路模块会处理不可达的情况
        return new Point2D.Double(rand.nextInt(width), rand.nextInt(height));
        /*
         * // 原有逻辑:
         * if (pathfindingModule == null || pathfindingModule.pathfinder == null ) { //
         * 移除 isWalkable 检查
         * return new Point2D.Double(rand.nextInt(width), rand.nextInt(height));
         * }
         * 
         * int attempts = 0;
         * while (attempts < 10) {
         * Point2D.Double candidate = new Point2D.Double(rand.nextInt(width),
         * rand.nextInt(height));
         * // 简化：不再强制要求巡逻点必须 walkable
         * // if (pathfindingModule.isWalkable(candidate)) {
         * return candidate;
         * // }
         * // attempts++;
         * }
         * return new Point2D.Double(rand.nextInt(width), rand.nextInt(height));
         */
    }

    /**
     * (幸存者) 寻找一个相对于威胁位置的掩体点。
     */
    private Point2D.Double findCover(Point2D.Double threatPosition) {
        if (owner == null || owner.position == null || pathfindingModule == null || gameState == null) {
            return calculateRetreatPosition(threatPosition);
        }

        // (省略了 TDMcontrol 中完整的 findCover 逻辑，您可以复制过来)
        // 简化版：直接后退
        return calculateRetreatPosition(threatPosition);
    }

    /**
     * 重生时的逻辑。
     */
    public void reset() {
        this.primaryTarget = null;
        this.lastKnownPosition = null;
        this.currentState = AIState.PATROLLING;
        this.lastStateChangeTime = System.currentTimeMillis();
        this.lastPathRecalculationTime = 0;
        this.nextTacticalDecisionTime = 0;
        this.lastMeleeTime = 0;

        if (pathfindingModule != null) {
            pathfindingModule.reset(); // 重置寻路模块状态
        }
        if (attackModule != null && owner.team == Player.Team.CT) {
            attackModule.reset(); // 重置攻击模块状态
        }
        if (grenadeModule != null && owner.team == Player.Team.CT) {
            grenadeModule.cancelPendingWork();
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
     * (幸存者) AI出生时选择一把武器。
     * 这将设置 owner.nextWeapon 字段, 供 Player.respawn() 使用。
     */
    public void initializeWeaponChoice() {
        // 仅 CT (幸存者) 需要选枪
        if (owner.team != Player.Team.CT) {
            owner.nextWeapon = null; // 僵尸没有武器
            return;
        }

        // --- 僵尸模式幸存者武器池 ---
        // (使用你想要的列表：高射速、大弹容量)
        List<Weapon> choices = List.of(
                Weapon.M249,
                Weapon.NEGEV,
                Weapon.P90,
                Weapon.BIZON,
                Weapon.MAC10,
                Weapon.XM1014, // 喷子
                Weapon.MP7);

        // 随机选择一把
        Weapon chosenWeapon = choices.get(rand.nextInt(choices.size()));

        // 将选择结果存储在 Player 的 nextWeapon 字段中
        // 稍后 Player.respawn() 方法会读取这个字段
        owner.nextWeapon = chosenWeapon.name();
    }

    /**
     * 击杀时的逻辑 (例如重置道具冷却)。
     */
    public void onKill() {
        // 幸存者击杀僵尸后，可以减少道具冷却
        if (owner.team == Player.Team.CT) {
            this.nextTacticalDecisionTime = System.currentTimeMillis() + (TACTICAL_DECISION_COOLDOWN / 2);
        }
        // 僵尸击杀人类... (可能需要特殊逻辑，例如增加血量？)
    }

    /**
     * 死亡时的逻辑。
     */
    public void onDeath() {
        // 幸存者死亡 (变成僵尸) 或僵尸死亡 (重生)
        reset();
    }

    private static final long KITING_RECALCULATE_INTERVAL = 500; // 每 500ms 重新计算一次风筝点
    private long lastKiteRecalculationTime = 0;

    /**
     * 根据当前状态，执行并合并攻击和寻路模块的输出。
     * 这是区分 CT 和 ZOMBIE 行为的核心。
     */
    /**
     * 处理全局撤退逻辑。
     * 1. 评估当前局势 (僵尸密度)
     * 2. 如果危急，由“指挥官”发出全局撤退信号
     * 3. 所有 AI 响应信号，向统一方向撤退
     */
    private void handleGroupRetreat(long currentTime, List<Point2D.Double> allThreats) {
        if (difficulty != AIDifficulty.REALISTIC || owner.team != Player.Team.CT)
            return;

        // --- 1. 评估局势 ---
        Point2D.Double swarmCenter = getSwarmCenter(allThreats);
        long teammatesNearSwarm = SHARED_TEAMMATE_STATES.values().stream()
                .filter(p -> p.isAlive() && p.position.distance(swarmCenter) < 400)
                .count();
        long threatsNearCenter = allThreats.stream()
                .filter(pos -> pos.distance(swarmCenter) < 300)
                .count();

        // 如果中心点僵尸太多，且队友都在那，触发撤退
        boolean shouldRetreat = (threatsNearCenter >= 5 && teammatesNearSwarm >= 2);

        // --- 2. 发出/更新信号 ---
        if (shouldRetreat) {
            // 选出 ID 最小的作为临时“指挥官”
            String commanderId = SHARED_TEAMMATE_STATES.keySet().stream().sorted().findFirst().orElse(null);

            if (owner.id.equals(commanderId)) {
                // 指挥官寻找一个远离僵尸群的开阔点
                Point2D.Double escapePoint = calculateRetreatPosition(swarmCenter);
                if (escapePoint != null) {
                    SHARED_RETREAT_SIGNAL.clear(); // 清除旧信号
                    SHARED_RETREAT_SIGNAL.put(currentTime + 5000, escapePoint); // 信号有效期 5s
                    logger.accept("Commander [" + owner.name + "] issued GROUP RETREAT!");
                }
            }
        }

        // --- 3. 清理过期信号 ---
        SHARED_RETREAT_SIGNAL.keySet().removeIf(expiry -> expiry < currentTime);
    }

    /**
     * 根据当前状态，执行并合并攻击和寻路模块的输出。
     * 这是区分 CT 和 ZOMBIE 行为的核心。
     */
    private AIInput executeActions(AIWorldView worldView, long currentTime) {
        AIInput attackInput = null;
        AIInput pathInput = null;

        List<String> finalKeys = new ArrayList<>();
        double finalAngle = owner.angle;
        boolean finalShooting = false;

        // 更新共享的队友状态
        if (difficulty == AIDifficulty.REALISTIC && owner.team == Player.Team.CT) {
            SHARED_TEAMMATE_STATES.put(owner.id, owner);
            // 清理已死亡或离开的队友
            SHARED_TEAMMATE_STATES.entrySet().removeIf(entry -> {
                Player p = gameState.getPlayerById(entry.getKey());
                return p == null || !p.isAlive() || p.team != Player.Team.CT;
            });
        }

        // --- 阵营 A: 幸存者 (CT) 逻辑 ---
        if (owner.team == Player.Team.CT) {
            // 获取所有可见威胁用于撤退评估
            List<Point2D.Double> allThreats = new ArrayList<>();
            if (primaryTarget != null)
                allThreats.add(primaryTarget.position);
            perceptionModule.getAllPerceivedEnemies().values().stream()
                    .filter(info -> info.isCurrentlyVisible())
                    .forEach(info -> allThreats.add(info.lastKnownPosition()));

            handleGroupRetreat(currentTime, allThreats);

            // 幸存者总是需要攻击模块 (射击) 和寻路模块 (移动)
            // 只有在非扔雷状态下才更新攻击模块
            if (currentState != AIState.PREPARING_GRENADE && currentState != AIState.MOVING_TO_THROW_SPOT) {
                attackInput = attackModule.update(this.primaryTarget, this.lastKnownPosition, currentTime);
            }
            // 寻路模块总是需要更新
            pathInput = pathfindingModule.update(worldView);

            switch (currentState) {
                case KITING:
                    // 风筝: 射击 + 智能后退
                    if (attackInput != null) {
                        finalAngle = attackInput.angle();
                        finalShooting = attackInput.shooting();
                    }

                    // 优先响应全局撤退信号
                    Point2D.Double retreatSignalPos = SHARED_RETREAT_SIGNAL.values().stream().findFirst().orElse(null);
                    boolean followingRetreatSignal = false;

                    if (difficulty == AIDifficulty.REALISTIC && retreatSignalPos != null) {
                        // 如果距离撤退点还有一段距离，则继续前往
                        if (owner.position.distance(retreatSignalPos) > 30) {
                            if (pathfindingModule.getTargetPosition() == null
                                    || !pathfindingModule.getTargetPosition().equals(retreatSignalPos)) {
                                pathfindingModule.setTarget(retreatSignalPos);
                            }
                            followingRetreatSignal = true;
                        }
                    }

                    // ***【智能风筝逻辑】***
                    // 如果不在撤退信号中，或者已经到达了撤退点，则恢复智能风筝
                    if (!followingRetreatSignal) {
                        if (currentTime - lastKiteRecalculationTime > KITING_RECALCULATE_INTERVAL
                                || !pathfindingModule.isActive()) {

                            // logger.accept("AI " + owner.name + " is recalculating KITE position."); //
                            // 调试用

                            Point2D.Double kitePos = calculateKitePosition(this.primaryTarget, worldView); // <--
                                                                                                           // 调用新的智能方法

                            if (kitePos != null) {
                                // 只有当寻路目标不是后退点时才设置
                                if (pathfindingModule.getTargetPosition() == null
                                        || !pathfindingModule.getTargetPosition().equals(kitePos)) {
                                    pathfindingModule.setTarget(kitePos);
                                }
                            } else {
                                // 如果找不到智能点（例如被堵住），则回退到旧的简单后退逻辑
                                Point2D.Double retreatPos = calculateRetreatPosition(
                                        primaryTarget != null ? primaryTarget.position : lastKnownPosition);
                                if (retreatPos != null && (pathfindingModule.getTargetPosition() == null
                                        || !pathfindingModule.getTargetPosition().equals(retreatPos))) {
                                    pathfindingModule.setTarget(retreatPos);
                                } else if (retreatPos == null) {
                                    // 彻底卡住，停下
                                    pathfindingModule.setTarget(null);
                                }
                            }
                            lastKiteRecalculationTime = currentTime;
                        }
                    }

                    // 寻路模块会继续执行上一次设置的 (新或旧) 目标
                    pathInput = pathfindingModule.update(worldView); // 获取移动指令
                    finalKeys = pathInput.keys();

                    break;

                case HUNTING:
                    // 追猎: 移动到 LKP + 看路
                    if (this.lastKnownPosition != null) {
                        // 只有当寻路目标不是LKP时才设置
                        if (!pathfindingModule.isActive() || pathfindingModule.getTargetPosition() == null
                                || !pathfindingModule.getTargetPosition().equals(this.lastKnownPosition)) {
                            pathfindingModule.setTarget(this.lastKnownPosition);
                        }
                        pathInput = pathfindingModule.update(worldView);
                        finalKeys = pathInput.keys();
                        finalAngle = pathInput.angle(); // 看路
                    } else {
                        // LKP丢失，进入巡逻
                        currentState = AIState.PATROLLING;
                        // (继续执行下面的 PATROLLING 逻辑)
                    }
                    finalShooting = false; // 追猎时不射击
                    // 如果状态转为 PATROLLING，则直接执行巡逻逻辑
                    if (currentState != AIState.PATROLLING)
                        break;
                    // (故意不写 break，让 PATROLLING 逻辑接管)

                case RELOADING:
                    // 换弹: 移动到掩体 + 看路
                    // (寻路目标已在状态机中设置)
                    pathInput = pathfindingModule.update(worldView); // 总是更新移动
                    finalKeys = pathInput.keys();
                    finalAngle = pathInput.angle();
                    finalShooting = false;
                    break;

                case PATROLLING:
                    // 巡逻: 随机移动 + 看路
                    if (!pathfindingModule.isActive() && (currentTime - lastPathRecalculationTime > 3000)) {
                        pathfindingModule.setTarget(findPatrolPoint());
                        lastPathRecalculationTime = currentTime;
                    }
                    pathInput = pathfindingModule.update(worldView); // 总是更新移动
                    finalKeys = pathInput.keys();
                    finalAngle = pathInput.angle();
                    finalShooting = false;
                    break;

                // 扔雷状态由 update 顶部的 GrenadeModule 逻辑处理
                case PREPARING_GRENADE:
                case MOVING_TO_THROW_SPOT:
                    // 优先使用 GrenadeModule 的指令 (已在 update 顶部处理)
                    // 但仍需合并 pathInput 的移动 (如果是 MOVE_TO_SPOT)
                    if (pathInput != null) {
                        finalKeys = pathInput.keys(); // 合并移动指令
                        finalAngle = pathInput.angle(); // 看路
                    }
                    finalShooting = false; // 准备扔雷时不射击
                    break;
            }

        }
        // --- 阵营 B: 僵尸 (ZOMBIE) 逻辑 ---
        else if (owner.team == Player.Team.ZOMBIE) {
            // 僵尸不需要 AttackModule (不射击)
            // 寻路模块总是需要更新
            pathInput = pathfindingModule.update(worldView);

            switch (currentState) {
                case ATTACKING:
                    // 近战: 停止移动, 转向目标, 执行攻击
                    if (pathfindingModule.isActive()) {
                        pathfindingModule.setTarget(null); // 停下
                    }
                    pathInput = pathfindingModule.update(worldView); // 获取 "停止" 指令
                    finalKeys = pathInput.keys(); // 空列表

                    if (primaryTarget != null) {
                        finalAngle = Math.atan2(primaryTarget.position.y - owner.position.y,
                                primaryTarget.position.x - owner.position.x);
                        // 执行近战攻击
                        handleMeleeAttack(currentTime);
                    }
                    finalShooting = false;
                    break;

                case HUNTING:
                    // 冲锋: 移动到目标 + 看路
                    if (primaryTarget != null) {
                        // 只有当寻路目标不是当前目标时才设置
                        if (!pathfindingModule.isActive() || pathfindingModule.getTargetPosition() == null
                                || !pathfindingModule.getTargetPosition().equals(primaryTarget.position)) {
                            pathfindingModule.setTarget(primaryTarget.position);
                        }
                        pathInput = pathfindingModule.update(worldView);
                        finalKeys = pathInput.keys();
                        finalAngle = pathInput.angle();
                    } else {
                        // 目标丢失，进入巡逻
                        currentState = AIState.PATROLLING;
                        // (继续执行下面的 PATROLLING 逻辑)
                    }
                    finalShooting = false;
                    // 如果状态转为 PATROLLING，则直接执行巡逻逻辑
                    if (currentState != AIState.PATROLLING)
                        break;
                    // (故意不写 break，让 PATROLLING 逻辑接管)

                case PATROLLING:
                    // 巡逻: 随机移动 + 看路
                    if (!pathfindingModule.isActive() && (currentTime - lastPathRecalculationTime > 3000)) {
                        pathfindingModule.setTarget(findPatrolPoint());
                        lastPathRecalculationTime = currentTime;
                    }
                    pathInput = pathfindingModule.update(worldView); // 总是更新移动
                    finalKeys = pathInput.keys();
                    finalAngle = pathInput.angle();
                    finalShooting = false;
                    break;
                default:
                    break;
            }
        }

        // 返回最终合并的 AIInput
        // (僵尸不使用 isInteracting)
        return new AIInput(finalKeys, finalAngle, finalShooting, false, false);
    }

    // ***【新增方法 1】***
    /**
     * (幸存者) 计算一个智能的风筝/后退点，模拟“力的组合”思想。
     * 1. 远离僵尸 (A)
     * 2. 远离墙壁 (B)
     * 3. 走向开阔地 (C)
     * 4. (新增) 避开僵尸群
     * 5. 阵型维持 (靠近队友)
     * 6. 相互掩护 (保护换弹队友)
     *
     * @param primaryThreat 优先威胁
     * @param worldView     AI的视野
     * @return 一个最佳的临时寻路目标点
     */
    private Point2D.Double calculateKitePosition(Player primaryThreat, AIWorldView worldView) {
        if (owner == null || owner.position == null || pathfindingModule == null) {
            return calculateRetreatPosition(primaryThreat != null ? primaryThreat.position : null); // Fallback
        }

        // 注册自身状态供队友参考
        if (difficulty == AIDifficulty.REALISTIC) {
            SHARED_TEAMMATE_STATES.put(owner.id, owner);
        }

        Point2D.Double bestPos = null;
        double bestScore = Double.NEGATIVE_INFINITY;

        // --- 获取所有可见的威胁 (僵尸) ---
        List<Point2D.Double> allThreats = new ArrayList<>();
        if (primaryThreat != null && primaryThreat.isAlive()) {
            allThreats.add(primaryThreat.position);
        }
        // 添加其他可见的僵尸
        if (perceptionModule != null) {
            for (PerceptionModule.PerceptionInfo info : perceptionModule.getAllPerceivedEnemies().values()) {
                if (info != null && info.isCurrentlyVisible()
                        && (primaryThreat == null || info.enemyId() != primaryThreat.id)) {
                    Player enemy = gameState.getPlayerById(info.enemyId());
                    if (enemy != null && enemy.isAlive() && enemy.team == Player.Team.ZOMBIE) {
                        allThreats.add(enemy.position);
                    }
                }
            }
        }
        if (allThreats.isEmpty()) {
            // 没有威胁，使用 LKP
            if (this.lastKnownPosition != null) {
                allThreats.add(this.lastKnownPosition);
            } else {
                return calculateRetreatPosition(null); // 彻底没威胁，回退到旧逻辑
            }
        }

        // --- 采样 16 个方向 (360度) 并在多个距离进行采样 ---
        int numSamples = 16;
        double[] sampleDistances = { 150.0, 250.0, 350.0 }; // 尝试跑到不同远近的地方

        for (int i = 0; i < numSamples; i++) {
            double angle = (i / (double) numSamples) * 2.0 * Math.PI; // 0 to 2*PI
            for (double sampleDistance : sampleDistances) {
                double targetX = owner.position.x + Math.cos(angle) * sampleDistance;
                double targetY = owner.position.y + Math.sin(angle) * sampleDistance;

                int width = (gameState != null && gameState.width > 0) ? gameState.width : 1024;
                int height = (gameState != null && gameState.height > 0) ? gameState.height : 1024;
                targetX = Math.max(Player.SIZE, Math.min(width - Player.SIZE, targetX));
                targetY = Math.max(Player.SIZE, Math.min(height - Player.SIZE, targetY));

                Point2D.Double samplePos = new Point2D.Double(targetX, targetY);

                // --- 评估这个采样点 ---
                double currentScore = 0.0;

                // --- 权重 B (远离墙壁) ---
                // 点本身必须可达
                if (!pathfindingModule.isWalkable(samplePos)) {
                    continue; // 尝试下一个距离/点
                }

                // --- 权重 A (远离僵尸) ---
                // 计算点到最近僵尸的距离
                double minThreatDist = Double.POSITIVE_INFINITY;
                for (Point2D.Double threatPos : allThreats) {
                    minThreatDist = Math.min(minThreatDist, samplePos.distance(threatPos));
                }
                // 离僵尸越远越好
                double fleeScore = minThreatDist;

                // --- 权重 C (走向开阔地) ---
                // 检查采样点周围的“开阔度” (通过检查周围8个点是否可走来模拟)
                double openSpaceScore = 0.0;
                int checks = 8;
                double checkRadius = 80.0; // 检查 80 像素半径
                for (int j = 0; j < checks; j++) {
                    double checkAngle = (j / (double) checks) * 2.0 * Math.PI;
                    double checkX = samplePos.x + Math.cos(checkAngle) * checkRadius;
                    double checkY = samplePos.y + Math.sin(checkAngle) * checkRadius;
                    if (pathfindingModule.isWalkable(new Point2D.Double(checkX, checkY))) {
                        openSpaceScore += 1.0; // 周围可走的地方越多，分数越高
                    }
                }

                // --- (宏观) 避开僵尸群 ---
                // 远离僵尸群的中心点
                Point2D.Double swarmCenter = getSwarmCenter(allThreats);
                double distToSwarm = samplePos.distance(swarmCenter);
                double swarmAvoidScore = distToSwarm;

                // --- 阵型与掩护分数 ---
                double formationScore = 0.0;
                double protectionScore = 0.0;

                if (difficulty == AIDifficulty.REALISTIC) {
                    for (Player teammate : SHARED_TEAMMATE_STATES.values()) {
                        if (teammate == null || teammate.id.equals(owner.id) || !teammate.isAlive())
                            continue;

                        double distToTeammate = samplePos.distance(teammate.position);

                        // 阵型权重：倾向于保持在队友 150-300 像素内
                        if (distToTeammate > 150 && distToTeammate < 400) {
                            formationScore += 200; // 理想距离加分
                        } else if (distToTeammate > 400) {
                            formationScore -= (distToTeammate - 400) * 0.5; // 太远减分
                        }

                        // 相互掩护权重：如果队友在换弹，倾向于靠近他提供掩护
                        if (teammate.isReloading && distToTeammate < 500) {
                            protectionScore += (500 - distToTeammate) * 2.0;
                        }
                    }
                }

                // --- 最终计分 (力的组合) ---
                // 权重: A (Flee) > (Avoid Swarm) > C (Open Space)
                // 增加阵型 and 保护权重

                double WEIGHT_A_FLEE = 1.5; // (A) 远离僵尸 (最高)
                double WEIGHT_D_AVOID_SWARM = 1.2; // (新) 远离僵尸群中心 (次高)
                double WEIGHT_C_OPEN = 1.0; // (C) 走向开阔地 (常规)
                double WEIGHT_E_FORMATION = 1.0; // (E) 阵型维持
                double WEIGHT_F_PROTECT = 1.5; // (F) 相互掩护 (与逃命同级)

                currentScore = (fleeScore * WEIGHT_A_FLEE) +
                        (openSpaceScore * WEIGHT_C_OPEN) +
                        (swarmAvoidScore * WEIGHT_D_AVOID_SWARM) +
                        (formationScore * WEIGHT_E_FORMATION) +
                        (protectionScore * WEIGHT_F_PROTECT);

                if (currentScore > bestScore) {
                    bestScore = currentScore;
                    bestPos = samplePos;
                }
            }
        }

        // 如果所有点都不好 (例如被堵在死角)，bestPos 可能是 null
        // 在 executeActions 中会处理这种情况 (回退到旧逻辑)
        return bestPos;
    }

    // ***【新增方法 2】***
    /**
     * * 辅助方法：计算僵尸群的中心点
     */
    private Point2D.Double getSwarmCenter(List<Point2D.Double> allThreats) {
        if (allThreats == null || allThreats.isEmpty()) {
            // 如果没有威胁，返回一个无效点（或者AI自己的位置）
            return owner.position != null ? owner.position : new Point2D.Double(0, 0);
        }
        if (allThreats.size() == 1) {
            return allThreats.get(0); // 只有一个僵尸，中心就是它
        }

        double sumX = 0.0;
        double sumY = 0.0;
        for (Point2D.Double pos : allThreats) {
            sumX += pos.x;
            sumY += pos.y;
        }
        return new Point2D.Double(sumX / allThreats.size(), sumY / allThreats.size());
    }

    /**
     * (幸存者) 计算一个简单的后退点。
     * 
     * @param threatPosition 威胁来源 (LKP 或 当前目标位置)
     */
    private Point2D.Double calculateRetreatPosition(Point2D.Double threatPosition) {
        if (owner == null || owner.position == null)
            return null;

        double retreatAngle;
        if (threatPosition != null) {
            // 远离威胁
            retreatAngle = Math.atan2(owner.position.y - threatPosition.y, owner.position.x - threatPosition.x);
        } else {
            // 远离自己面朝的方向 (备用)
            retreatAngle = owner.angle + Math.PI;
        }

        // ***【修改】 增加随机性，防止被卡住***
        // 随机偏转 +-45 度
        retreatAngle += (rand.nextDouble() - 0.5) * (Math.PI / 2.0);

        double retreatDistance = 200 + rand.nextDouble() * 100; // 后退 200-300 像素
        double retreatX = owner.position.x + Math.cos(retreatAngle) * retreatDistance;
        double retreatY = owner.position.y + Math.sin(retreatAngle) * retreatDistance;

        int width = (gameState != null && gameState.width > 0) ? gameState.width : 1024;
        int height = (gameState != null && gameState.height > 0) ? gameState.height : 1024;
        retreatX = Math.max(Player.SIZE, Math.min(width - Player.SIZE, retreatX));
        retreatY = Math.max(Player.SIZE, Math.min(height - Player.SIZE, retreatY));

        Point2D.Double retreatPos = new Point2D.Double(retreatX, retreatY);

        // ***【修改】简化检查，让寻路模块自己处理 ***
        // 只要点可走，就尝试
        if (pathfindingModule != null && pathfindingModule.isWalkable(retreatPos)) {
            return retreatPos;
        }

        // 尝试寻找一个可走的后退点 (最多5次)
        int attempts = 0;
        while (attempts < 5) {
            retreatAngle += (rand.nextDouble() - 0.5) * Math.PI / 4; // 再次随机偏转 +-22.5 度
            retreatDistance = 150 + rand.nextDouble() * 100; // 减小距离再试
            retreatX = owner.position.x + Math.cos(retreatAngle) * retreatDistance;
            retreatY = owner.position.y + Math.sin(retreatAngle) * retreatDistance;
            retreatX = Math.max(Player.SIZE, Math.min(width - Player.SIZE, retreatX));
            retreatY = Math.max(Player.SIZE, Math.min(height - Player.SIZE, retreatY));
            retreatPos.setLocation(retreatX, retreatY);

            if (pathfindingModule != null && pathfindingModule.isWalkable(retreatPos)) {
                return retreatPos;
            }
            attempts++;
        }

        return null; // 找不到可走的后退点
    }
}
