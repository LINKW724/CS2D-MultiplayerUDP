//// =================================================================================
//// 文件: AIController.java
//// 描述: AI行为控制核心类 (已升级，包含道具购买与使用逻辑)
//// =================================================================================
//package cs2d.playerAndAi;
//
//import cs2d.playerAndAi.doublePlayer.WaypointNode;
//import cs2d.server.*;
//
//import java.awt.Rectangle;
//import java.awt.Shape;
//import java.awt.geom.*;
//import java.util.*;
//import java.util.concurrent.ConcurrentHashMap;
//import java.util.concurrent.ExecutorService;
//import java.util.concurrent.Executors;
//import java.util.function.Consumer;
//import java.util.stream.Collectors;
//import cs2d.server.AIService;
//
//import static cs2d.playerAndAi.AIController.TacticRole.RETRIEVING_HERO_WEAPON;
//
//
//public class AIController {
//    // 引用当前 AI 所控制的玩家实体。
//    private final Player self;
//    // AI 的难度级别配置。
//    private final AIDifficulty difficulty;
//    // 当前 AI 正在追逐或攻击的玩家目标。
//    private Player target;
//    // 记录 AI 上次被闪光弹致盲的时间戳（毫秒）。
//    private long lastFlashTime = 0;
//    // 记录 AI 被闪光弹致盲的持续时间（毫秒）。
//    private long flashDuration = 0;
//
//
//    // ==== 指挥官下达的战术指令 ====
//    private Point2D.Double tacticalObjective; // 战术目标点
//    private TacticRole tacticRole;// 在战术中扮演的角色
//    private Player playerToFollow;
//    private boolean holdPosition;             //[旧版] 是否需要原地待命
//
//    private WaypointNode lastPassedWaypointNode = null; // 记录上一个经过的粗点
//    private long lastPassedWaypointTime = 0;          // 记录经过的时间
//    private static final long STICKY_WAYPOINT_DURATION = 2000; // 阻止回头的时间 (例如 2 秒)
//    /**
//     *  定义AI在战术中扮演的角色
//     */
//    public enum TacticRole {
//        IDLE,           // 无角色
//        ENTRY,          // 突击手 (冲在前面)
//        SUPPORT,        // 支援
//        BOMB_CARRIER,   // C4携带者 (通常走在队伍中间或后面)
//        DISTRACTION,    // 佯攻/吸引火力
//        EXECUTE,        // 佯攻后的主攻手
//        FLANK,          // 侧翼包抄
//        DEFENDER,       // 防守者
//        ROTATOR,        // 回防者
//        SAVER,          // 保枪者
//        BODYGUARD,       //辅助 <保护英雄单位>
//        RETRIEVING_HERO_WEAPON,  //继承 <正在拾取英雄阵亡后掉落的武器>
//        DEFUSER,        // 拆弹人
//        FLEEING_BOMB,    // 正在从即将爆炸的C4处撤离
//        GUARDIAN        // 护卫 掩护拆弹人
//    }
//    /**
//     * AI 的行为状态机枚举。
//     */
//    private enum AIState {
//        IDLE,               // 空闲或待命状态。
//        PATROLLING,         // 巡逻或随机移动中。
//        ATTACKING,          // 正在与目标交火并进行战术移动。
//        HUNTING,            // 目标丢失，正在前往目标最后已知位置进行追击。
//        TAKING_COVER,       // 换弹或低血量时寻找掩体。
//        MOVING_TO_SITE,     // 爆破模式中，移动到指定的 A 或 B 包点。
//        PLANTING_BOMB,      // 正在安放 C4。
//        DEFENDING_SITE,     // 爆破模式中，防守已安放的 C4 或预定的包点。
//        ROTATING,           // 爆破模式中，从一个包点快速移动到另一个包点（回防或转点）。
//        DEFUSING_BOMB,      // 正在拆除 C4。
//        RETRIEVING_BOMB,    // T 方移动到掉落的 C4 位置去拾取。
//        GUARDING_BOMB,      // CT 方防守掉落的 C4 物品。
//        UNSTUCKING,         // AI 判定自己卡住，正在执行随机移动脱困。
//        BLIND_RETALIATION,  // 基于枪声记忆，对掩体后目标执行盲射反击（穿墙）。
//
//        PREPARING_GRENADE,       // 准备投掷的总状态（停稳、瞄准）。
//        MOVING_TO_THROW_SPOT,    // 正在移动到预定投掷点。
//
//        RETREATING,         // 战术性撤退（例如血量低或寡不敌众时）
//        RUSHING_BOMBSITE    // 战术性冲锋包点（例如执行快攻）
//
//    }
//
//    // AI 当前所处的行为状态。
//    private AIState currentState;
//    // 目标玩家最后一次出现在视野中的位置。
//    private Point2D.Double lastKnownPosition;
//    /**
//     * 一个用于封装碰撞检测结果的内部记录类。
//     * 包含了碰撞点、被撞边的法线向量。
//     */
//    private record CollisionResult(Vector2D impactPoint, Vector2D normal) {}
//    /**
//     * 一个内部记录类，用于封装枪声的位置和时间戳。
//     */
//    private record SoundCue(Point2D.Double position, long timestamp) {}
//
//    /** 记录 AI 最后听到的枪声的位置和时间。*/
//    // 创建一个静态的、所有AI共享的单线程执行器
//    private static final ExecutorService grenadeCalculatorService = Executors.newFixedThreadPool(6);
//
//    // 用于标记当前AI是否已经提交了一个计算任务，防止重复提交
//    private boolean isCalculatingGrenade = false;
//
//    // 用于从后台线程接收投掷计划。'volatile' 关键字确保了线程间的可见性
//    private volatile GrenadeThrowPlan pendingGrenadePlan = null;
//
//    /**
//     * 一个封装了手雷投掷决策结果的记录类。
//     * @param itemToThrow 要投掷的道具
//     * @param decisionPosition 做出决策时AI所在的位置
//     * @param decisionAngle 计算出的最佳投掷角度
//     * @param targetPosition 道具的目标落点
//     */
//    private record GrenadeThrowPlan(Item itemToThrow, Point2D.Double decisionPosition, double decisionAngle, Point2D.Double targetPosition) {}
//    /**
//     * 控制此 AI 控制器是否活动的开关。
//     * 当被玩家夺舍时，此开关应设为 false。
//     */
//    private volatile boolean isEnabled = true;
//    private SoundCue lastHeardSoundCue;
//    // 用于所有随机决策的随机数生成器。
//    private final Random rand = new Random();
//    // 对主游戏状态的引用，用于获取地图、玩家、回合信息等。
//    private GameState gameState;
//    // 服务器提供的日志输出接口（通常连接到控制台或文件）。
//    private final Consumer<String> logger;
//    // AI 锁定当前目标玩家的时间戳。用于计算反应时间。
//    private long targetAcquiredTime = 0;
//    // AI 视野范围内或已知位置的所有玩家列表。
//    private List<Player> knownPlayers;
//
//    // 寻路逻辑的核心对象。
//    private Pathfinder pathfinder;
//    // 当前 AI 正在遵循的寻路路径
//    private List<Node> currentPath;
//    // 当前路径中下一个要前往的节点的索引。
//    private int currentPathIndex;
//    // 记录上次计算路径的时间戳，用于限制寻路频率。
//    private List<WaypointNode> currentCoarsePath;
//    private List<WaypointNode> waypointGraphCache = null;
//    private int currentCoarsePathIndex;
//
//    //   粗算使用的路径点图的引用 (从 GameState 获取)
//    private long lastPathRecalculationTime = 0;
//
//    // 上次执行卡住检查的时间。
//    private long lastStuckCheckTime = 0;
//    // 上次执行卡住检查时 AI 的位置。
//    private Point2D.Double lastPositionForStuckCheck;
//    // 卡住检查的间隔时间（毫秒）。
//    private static final long STUCK_CHECK_INTERVAL = 1500;
//    // 判定卡住所需的最小移动距离（单位）。
//    private static final double MIN_MOVEMENT_FOR_STUCK = 3.0;
//    // 连续判定为卡住的次数。
//    private int stuckCount = 0;
//    // 强制执行脱困动作直到该时间戳。
//    private long unstuckUntil = 0;
//
//    // 记录武器连射后的冷却时间，直到该时间戳 AI 停止射击。
//    private long burstCooldownUntil = 0;
//    // 记录对当前目标已经连续射出的子弹数。
//    private int shotsFiredAtCurrentTarget = 0;
//    // AI 连续撞墙的起始时间戳。
//    private long wallCollisionStartTime = 0;
//    // AI 当前是否正在与墙壁碰撞。
//    private boolean isCollidingWithWall = false;
//
//    // 上次改变横移方向的时间。
//    private long lastStrafeDirectionChange = 0;
//    // AI 当前是否向左横移。
//    private boolean strafingLeft = false;
//    // 改变横移方向的间隔时间（毫秒）。
//    private static final long STRAFE_DIRECTION_CHANGE_INTERVAL = 1500;
//
//    // 上次执行近战攻击的时间。
//    private long lastMeleeAttackTime = 0;
//    // 近战攻击的冷却时间（毫秒）。
//    private static final long MELEE_COOLDOWN = 1000;
//    // 近战攻击的伤害值。
//    private static final int MELEE_DAMAGE = 10;
//
//    // 标记 AI 在当前回合的冻结时间是否已完成购买。
//    private boolean hasBoughtThisRound = false;
//    // 上一帧的回合阶段，用于检测回合开始等状态变化。
//    private GameState.RoundPhase lastRoundPhase = null;
//    // 爆破模式中，AI 被分配的进攻或防守目标点（A点或B点中心坐标）。
//    public Point2D.Double assignedObjective;
//
//    // --- 道具相关 ---
//   // 当前 AI 准备投掷的道具类型。
//    private Item grenadeToThrow = null;
//    // 上次成功投掷道具的时间戳。
//    private long lastGrenadeThrowTime = 0;
//    // 道具使用后的冷却时间（毫秒）。
//    private static final long GRENADE_COOLDOWN_MS = 7000;
//    // --- 道具相关 2 ---
//    // 道具期望的最终落点坐标。
//    private Point2D.Double grenadeTargetPosition = null;
//    // AI 做出投掷决策时所在的位置（AI需要移动到此点才能保证投掷精度）。
//    private Point2D.Double grenadeDecisionPosition = null;
//    // 存储决策时的“完美投掷角度”（弧度）。
//    private double grenadeDecisionAngle = 0;
//
//    // ---道具使用相关的战术常量 ---
//
////    private static final double MIN_GRENADE_THROW_DISTANCE = 300; // 扔手雷/燃烧弹的最小距离
////    private static final double MAX_GRENADE_THROW_DISTANCE = 1200; // 扔手雷/燃烧弹的最大距离
//    private static final double LONG_RANGE_FLASH_MIN_DISTANCE = 300; // 使用远程闪光弹的最小距离
//
//    //顺滑角度
//    /** AI 期望朝向的目标角度 (弧度) */
//    private double targetAngle;
//    /** AI 当前平滑过渡中的角度 (弧度)，这个值将最终赋给 self.angle */
//    private double currentAngle;
//
//    public AIController(Player self, AIDifficulty difficulty, Consumer<String> logger) {
//        this.self = self;
//        this.difficulty = difficulty;
//        this.logger = logger;
//        this.currentState = AIState.IDLE;
//        this.lastStuckCheckTime = System.currentTimeMillis();
//        this.lastPositionForStuckCheck = new Point2D.Double(self.position.x, self.position.y);
//        // [优化]错开每个AI的初始思考时间，防止开局集体卡顿。
//        this.nextTacticalUpdateTime = System.currentTimeMillis() + rand.nextInt(500);
//        this.currentAngle = self.angle;
//        this.targetAngle = self.angle;
//
//        //指挥的
//        this.tacticalObjective = null;
//        this.tacticRole = TacticRole.IDLE;
//        this.holdPosition = false;
//    }
//
//    /**
//     * 应用闪光弹效果到玩家
//     * 设置闪光开始时间和持续时间，用于客户端渲染闪光效果
//     *
//     * @param duration 闪光持续时间（毫秒），数值越大致盲时间越长
//     */
//    public void getFlashed(long duration) {
//        this.lastFlashTime = System.currentTimeMillis();  // 记录闪光开始时间
//        this.flashDuration = duration;                    // 设置闪光持续时间
//    }
//
//    /**
//     * 为玩家选择初始武器
//     * 根据游戏模式提供不同的武器池，确保模式平衡性
//     *
//     * @param mode 当前游戏模式，影响可用武器选择
//     * @return 随机选择的武器，确保开局武器多样性
//     */
//    public Weapon chooseInitialWeapon(GameMode mode) {
//        List<Weapon> choices = new ArrayList<>();
//
//        // 僵尸模式专用武器池：高射速、大弹容量的自动武器
//        if (mode == GameMode.ZOMBIE_MODE) {
//            choices.addAll(Arrays.asList(
//                    Weapon.MAC10,
//                    Weapon.P90,
//                    Weapon.BIZON,
//                    Weapon.NEGEV,
//                    Weapon.M249,
//                    Weapon.AK47,
//                    Weapon.XM1014
//            ));
//        } else {
//            // 标准模式：所有武器可用
//            choices.addAll(Arrays.asList(Weapon.values()));
//        }
//
//        // 随机选择，确保每局开局武器不同
//        return choices.get(rand.nextInt(choices.size()));
//    }
//
//    /**
//     * 为玩家选择新的替换武器
//     * 确保新武器与当前武器不同，避免重复选择
//     *
//     * @param mode 当前游戏模式，影响武器选择池
//     * @return 与当前武器不同的新武器，增加游戏变化性
//     */
//    public Weapon chooseNewWeapon(GameMode mode) {
//        Weapon currentWeapon = self.getCurrentWeapon();  // 获取玩家当前持有的武器
//        Weapon newWeapon;
//
//        // 循环直到选择到不同的武器，避免武器重复
//        do {
//            newWeapon = chooseInitialWeapon(mode);  // 从初始武器池中选择
//        } while (newWeapon == currentWeapon);       // 确保新武器与当前武器不同
//
//        return newWeapon;
//    }
//
//    /**
//     * 平滑更新AI的朝向角度。
//     * 此方法在每帧的最后被调用，用于计算从 currentAngle 到 targetAngle 的平滑过渡。
//     */
//    private static final double BASE_TURN_SPEED_DEGREES = 3.0; // <--- 这里是你想调整的基础速度
//    private void updateAimAngle() {
//        // 定义AI的转向速度，数值越大转得越快。可以根据难度进行调整。
//        //   final double TURN_SPEED = Math.toRadians(10.0 + difficulty.ordinal() * 5);
//       //  根据难度获取修饰因子。我们用 aimErrorMultiplier，因为它通常是难度越低，数值越高。
//        //    这里用一个简单的除法来让难度越低（aimErrorMultiplier越大），转速越快。
//        final double difficultyFactor = 1.0 / this.difficulty.aimErrorMultiplier;
//
//        // 确保 factor 不会太低，例如最低 0.5
//        final double MIN_FACTOR = 0.5;
//        final double finalFactor = Math.max(MIN_FACTOR, difficultyFactor);
//
//        //  将基础速度乘以修饰因子
//        double turnSpeedDegrees = BASE_TURN_SPEED_DEGREES * finalFactor;
//
//        //  将度数转换为弧度作为最终转向速度
//        final double TURN_SPEED = Math.toRadians(turnSpeedDegrees); // <-- TURN
//        //        final double TURN_SPEED = Math.toRadians(3.0); // 每帧转3度(根据难度决定？)
//
//        // 规范化角度到 -PI 到 +PI 范围，方便计算
//        while (targetAngle <= -Math.PI) targetAngle += 2 * Math.PI;
//        while (targetAngle > Math.PI)  targetAngle -= 2 * Math.PI;
//        while (currentAngle <= -Math.PI) currentAngle += 2 * Math.PI;
//        while (currentAngle > Math.PI)  currentAngle -= 2 * Math.PI;
//
//        // 计算当前角度和目标角度之间的最短角位移
//        double angleDifference = targetAngle - currentAngle;
//        if (angleDifference > Math.PI) {
//            angleDifference -= 2 * Math.PI; // 走另一边更近
//        }
//        if (angleDifference < -Math.PI) {
//            angleDifference += 2 * Math.PI; // 走另一边更近
//        }
//
//        // 根据转向速度，计算本帧实际能转动的角度
//        double turnAmount = Math.max(-TURN_SPEED, Math.min(TURN_SPEED, angleDifference));
//
//        // 更新当前角度
//        currentAngle += turnAmount;
//
//        // 将最终平滑计算出的角度，赋给玩家实体
//        self.angle = currentAngle;
//    }
//
//
//
//    /**
//     * AI的核心更新方法（已重构为在独立的AI服务线程中运行）。
//     * 此方法接收一个简化的世界观快照，以确保与主游戏线程的数据隔离。
//     * @param worldView 包含所有玩家状态快照的只读数据对象。
//     */
//    public void update(AIService.AIWorldView worldView) {
//        // --- [核心] ---
//        // 检查AI是否被禁用
//        if (!isEnabled) {
//            return; // 如果被禁用，则直接返回，不执行任何AI逻辑
//        }
//
//        //  AI静步决策逻辑
//        // 默认情况下，AI不静步
//        boolean shouldWalk = false;
//        // 小僵尸静什么步?
//        if (self.team != Player.Team.ZOMBIE) {
//            // 只有高难度AI才会考虑静步
//            if (difficulty == AIDifficulty.HARD || difficulty == AIDifficulty.VERY_HARD || difficulty == AIDifficulty.HELL) {
//                // 计算当前的“静步权重”
//                double walkingWeight = calculateWalkingWeight();
//
//                // 如果权重超过阈值，则决定静步
//                if (walkingWeight > 50) {
//                    shouldWalk = true;
//                }
//            }
//        }
//        // 将最终决策应用到玩家对象上，服务器的物理引擎会自动处理减速
//        this.self.isWalking = shouldWalk;
//        // 如果AI当前的状态是安放C4或拆除C4，则执行以下锁定逻辑并立即结束本帧更新。
//        if (currentState == AIState.PLANTING_BOMB || currentState == AIState.DEFUSING_BOMB) {
//
//            // 强制清除所有移动和射击意图，变为“雕像”
//            self.ax = 0;
//            self.ay = 0;
//            self.vx = 0;
//            self.vy = 0;
//            self.isShooting = false;
//            self.keysDown.clear();
//
//            //  验证交互条件是否仍然满足
//            boolean conditionsMet = false;
//            if (currentState == AIState.PLANTING_BOMB) {
//                conditionsMet = self.hasBomb && isAtBombSite();
//            } else { // DEFUSING_BOMB[拆弹]
//                // 确保gameState和bombPosition不为null
//                if (gameState != null && gameState.isBombPlanted() && gameState.getBombPosition() != null) {
//                    conditionsMet = self.position.distance(gameState.getBombPosition()) < 50;
//                }
//            }
//
//            if (conditionsMet) {
//                //   条件满足，维持交互意图并立即返回，阻止任何其他AI逻辑执行
//                self.isInteracting = true; // 持续表达交互意图
//                return;
//            } else {
//                //  如果条件在中途失效（如被炸出范围），则取消交互状态
//                logger.accept(self.name + " [INTERACT_CANCEL] 交互条件失效，强制退出。");
//                self.isInteracting = false; // 停止交互意图
//                // 恢复到之前的状态，以便AI立即开始移动
//                currentState = (self.team == Player.Team.T) ? AIState.MOVING_TO_SITE : AIState.PATROLLING;
//                // 注意：这里没写 return，允许AI在本帧的剩余时间里开始移动
//            }
//        }
//
//        if (grenadePlanStartTime != 0 && System.currentTimeMillis() - grenadePlanStartTime > GRENADE_THROW_TIMEOUT_MS) {
//            logger.accept(self.name + " 的投掷动作超时（超过5秒），已强制取消。");
//            resetGrenadeState(); // 调用重置方法来清理一切并恢复正常状态
//        }
//
//        // 从Player对象中获取对主GameState的引用，这是AI与游戏世界交互的桥梁
//        this.gameState = self.getGameState();
//        if (this.gameState == null) return; // 如果无法获取GameState，则无法继续决策
//        // 全局状态安全检查
//        performGlobalStateSafetyCheck();
//
//        // 将只读的PlayerSnapshot列表转换回一个可操作的、真实的Player对象列表
//        List<Player> allCharacters = worldView.players().stream()
//                .map(snapshot -> gameState.getPlayerById(snapshot.id()))
//                .filter(Objects::nonNull) // 过滤掉可能已断开连接的玩家
//                .collect(Collectors.toList());
//
//        // --- 执行原有的决策逻辑 ---
//        // 枪声记忆过期检查
//        if (lastHeardSoundCue != null && System.currentTimeMillis() - lastHeardSoundCue.timestamp() > 3000) {
//            lastHeardSoundCue = null;
//            logger.accept(self.name + " 的枪声记忆已过期。");
//        }
//        // 检查并执行已完成的投掷计划
//        if (pendingGrenadePlan != null) {
//            GrenadeThrowPlan plan = this.pendingGrenadePlan;
//            this.pendingGrenadePlan = null;
//
////            logger.accept(String.format("%s's calculation is complete! Executing plan to throw %s.", self.name, plan.itemToThrow().name()));
//
//            this.grenadeToThrow = plan.itemToThrow();
//            this.grenadeTargetPosition = plan.targetPosition();
//            this.grenadeDecisionPosition = plan.decisionPosition();
//            this.grenadeDecisionAngle = plan.decisionAngle();
//            this.currentState = AIState.PREPARING_GRENADE;
//            self.switchToSlot(getSlotForItem(grenadeToThrow));
//            this.grenadePlanStartTime = System.currentTimeMillis();
//
//
//            return;
//        }
//
//        this.knownPlayers = allCharacters.stream().filter(Objects::nonNull).collect(Collectors.toList());
//
//        if (this.pathfinder == null) {
//            // 注意：这里的obstacles现在是从pathfinder获取，而不是参数
//            this.pathfinder = new Pathfinder(gameState, (int) Player.SIZE);
//        }
//
//        if (this.gameState.getRoundPhase() == GameState.RoundPhase.FREEZE_TIME && lastRoundPhase != GameState.RoundPhase.FREEZE_TIME) {
//            hasBoughtThisRound = false;
//            assignedObjective = null;
//        }
//        this.lastRoundPhase = this.gameState.getRoundPhase();
//        self.ax = 0;
//        self.ay = 0;
//
//        if (checkIfStuck()) {
//            performUnstuckManeuver();
//        }
//        if (System.currentTimeMillis() < unstuckUntil) {
//            return;
//        } else if (currentState == AIState.UNSTUCKING) {
//            currentState = AIState.PATROLLING;
//        }
//
//        if (System.currentTimeMillis() < lastFlashTime + flashDuration) {
//            self.ax = (rand.nextDouble() - 0.5) * 2;
//            self.ay = (rand.nextDouble() - 0.5) * 2;
//            self.angle += (rand.nextDouble() - 0.5) * 1.5;
//            self.isShooting = rand.nextDouble() < 0.1;
//            return;
//        }
//
//        if (currentState == AIState.PREPARING_GRENADE) {
//            handleGrenadePreparation();
//            updateAimAngle();
//            return;
//        }
//
//        if (currentState == AIState.MOVING_TO_THROW_SPOT) {
//            if (currentPath == null || currentPathIndex >= currentPath.size()) {
//                this.currentState = AIState.PREPARING_GRENADE;
//                clearPath();
//                return;
//            }
//        }
//
//        // --- 模式分流和移动意图计算 ---
//
//        //  如果是 CT 队伍且游戏模式是 ZOMBIE_MODE，执行幸存者逻辑 (直接设置 ax/ay)
//        if (self.team == Player.Team.CT && gameState.getGameMode() == GameMode.ZOMBIE_MODE) {
//            updateSurvivor(allCharacters);
//            // 幸存者的移动（风筝/恐慌）逻辑已在 updateSurvivor 中完成，此处不需要 followPath。
//        }
//        //   如果是 ZOMBIE 队伍，执行僵尸逻辑 (设置寻路路径)
//        else if (self.team == Player.Team.ZOMBIE) {
//            updateZombie(allCharacters);
//            // 僵尸移动依赖寻路，因此需要执行路径跟随
//            followPath();
//        }
//        //   否则，执行 DEMO 或 TDM 的逻辑 (设置寻路路径)
//        else if (this.gameState.getGameMode() == GameMode.DEMOLITION) {
//            updateBot_Demolition(allCharacters, this.gameState);
//            // DEMO Bots使用寻路，需要路径跟随
//            followPath();
//        } else {
//            updateBot(allCharacters); // TDM Bots也需要寻路
//            followPath();
//        }
//
//        // 无论是幸存者还是僵尸，只要是 ZOMBIE_MODE，都需要在逻辑末尾触发移动！
//        if (gameState.getGameMode() == GameMode.ZOMBIE_MODE) {
//            followPath();
//        }
//
//        if (self.team != Player.Team.ZOMBIE) {
//            applyTeamSeparation();
//        }
//        adjustMovementForCollision();
//        updateAimAngle();
//        normalizeAcceleration();
//    }
//
//    /**[旧版]
//     * 每帧更新AI
//     */
//    public void update(List<Player> allCharacters, List<Shape> obstacles, GameState state) {
//
//
//        System.out.println(String.format("AI: %s, State: %s, Pos: (%.1f, %.1f), Vel: (%.2f, %.2f), Ax/Ay: (%.2f, %.2f)",
//                self.name, currentState.name(), self.position.x, self.position.y, self.vx, self.vy, self.ax, self.ay));
//        // 同样，在这里也加上检查
//        if (!isEnabled) {
//            return; // 如果被禁用，则直接返回
//        }
//        this.gameState = state;
//
//        // 枪声记忆过期检查
//        // 如果AI记着一个枪声，并且这个枪声已经过去了超过3秒
//        if (lastHeardSoundCue != null && System.currentTimeMillis() - lastHeardSoundCue.timestamp() > 3000) {
//            lastHeardSoundCue = null; // 就把它忘掉
////            logger.accept(self.name + " 的枪声记忆已过期。");
//        }
//
//        if (pendingGrenadePlan != null) {
//            // 从 volatile 变量中安全地取出计划
//            GrenadeThrowPlan plan = this.pendingGrenadePlan;
//            // 立刻清空，防止重复执行
//            this.pendingGrenadePlan = null;
//
//            logger.accept(String.format("%s's calculation is complete! Executing plan to throw %s.", self.name, plan.itemToThrow().name()));
//
//            // -- 将计划内容应用到AI当前状态 --
//            this.grenadeToThrow = plan.itemToThrow();
//            this.grenadeTargetPosition = plan.targetPosition();
//            this.grenadeDecisionPosition = plan.decisionPosition();
//            this.grenadeDecisionAngle = plan.decisionAngle();
//
//            // 进入准备投掷的初始状态
//            this.currentState = AIState.PREPARING_GRENADE;
//            self.switchToSlot(getSlotForItem(grenadeToThrow)); // 切换到道具
//
//            // 因为这是一个重大状态转变，可以直接 return，开始执行投掷准备
//            return;
//        }
//
//        this.knownPlayers = allCharacters.stream().filter(Objects::nonNull).collect(Collectors.toList());
//
//        if (this.pathfinder == null) {
//            // 现在直接传入 gameState 和 cellSize
//            this.pathfinder = new Pathfinder(gameState, (int) Player.SIZE);
//        }
//
//        if (state.getRoundPhase() == GameState.RoundPhase.FREEZE_TIME && lastRoundPhase != GameState.RoundPhase.FREEZE_TIME) {
//            hasBoughtThisRound = false;
//            assignedObjective = null;
//        }
//        this.lastRoundPhase = state.getRoundPhase();
//        self.ax = 0;
//        self.ay = 0;
//
//        if (checkIfStuck()) {
//            performUnstuckManeuver();
//        }
//        if (System.currentTimeMillis() < unstuckUntil) {
//            return;
//        } else if (currentState == AIState.UNSTUCKING) {
//            currentState = AIState.PATROLLING;
//        }
//
//        if (System.currentTimeMillis() < lastFlashTime + flashDuration) {
//            self.ax = (rand.nextDouble() - 0.5) * 2;
//            self.ay = (rand.nextDouble() - 0.5) * 2;
//            self.angle += (rand.nextDouble() - 0.5) * 1.5;
//            self.isShooting = rand.nextDouble() < 0.1;
//            return;
//        }
//
//        if (currentState == AIState.PREPARING_GRENADE) {
//            handleGrenadePreparation();
//            // 强制转身后，也需要调用 updateAimAngle 来同步内部状态
//            updateAimAngle();
//            return;
//        }
//
//        //  检查AI是否已“走回到”投掷点
//        if (currentState == AIState.MOVING_TO_THROW_SPOT) {
//            // 如果寻路路径已走完（意味着已到达目的地）
//            if (currentPath == null || currentPathIndex >= currentPath.size()) {
////
//                // 切换回准备状态，下一帧将执行停稳、瞄准、投掷
//                this.currentState = AIState.PREPARING_GRENADE;
//                // 停下，防止走过头
//                clearPath();
//                return;
//            }
//        }
//
//        if (self.team == Player.Team.ZOMBIE) {
//            updateZombie(allCharacters);
//        } else if (currentState == AIState.MOVING_TO_THROW_SPOT) {
//            // 如果正在去投掷点的路上，唯一要做的就是跟着路走
//            followPath();
//        } else if (gameState.getGameMode() == GameMode.DEMOLITION) {
//            updateBot_Demolition(allCharacters, state);
//        }  else {
//            updateBot(allCharacters);
//        }
//        if (self.team != Player.Team.ZOMBIE) {
//            applyTeamSeparation();
//        }
//        adjustMovementForCollision();
//        // 调用平滑转向更新
//        updateAimAngle();
//        normalizeAcceleration();
//    }
//
//    // --- 核心AI逻辑 ---
//    /**
//     * 辅助方法：检查AI当前是否在任意一个炸弹安放区内。
//     * @return 如果在包点内则返回true，否则返回false。
//     */
//    private boolean isAtBombSite() {
//        if (this.gameState == null) return false;
//
//        Rectangle siteA = gameState.getBombSiteA();
//        Rectangle siteB = gameState.getBombSiteB();
//
//        boolean atSiteA = siteA != null && self.getBounds().intersects(siteA);
//        boolean atSiteB = siteB != null && self.getBounds().intersects(siteB);
//
//        return atSiteA || atSiteB;
//    }
//
//    /**
//     * 评估听到的枪声威胁。
//     * 这个方法会模拟一次穿墙射击，但只用于计算伤害衰减，而不真正开火。
//     * @return 如果判断威胁足够大（穿透伤害>50%），则返回 true。
//     */
//    private boolean assessHeardThreat() {
//        Weapon wep = self.getCurrentWeapon();
//        if (lastHeardSoundCue == null || wep == null) {
//            return false;
//        }
//
//        if (self.position.distance(lastHeardSoundCue.position()) > 1000) {
//            lastHeardSoundCue = null; // 距离太远还是直接忘掉
//            return false;
//        }
//        long currentTime = System.currentTimeMillis();
//        long timeSinceSound = currentTime - lastHeardSoundCue.timestamp();
//
//        // 衰减逻辑
//        if (timeSinceSound > 3000) {
//            lastHeardSoundCue = null;
//            return false; // 超过 3 秒，直接忘记
//        }
//
//        // 超过 1 秒后，威胁开始线性衰减
//        final long START_DECAY_MS = 1000;
//        double decayFactor = 3.0;
//
//        if (timeSinceSound > START_DECAY_MS) {
//            // 在 1 秒到 3 秒之间，将威胁权重从 1.0 快速衰减到 0.0
//            decayFactor = 1.0 - (double)(timeSinceSound - START_DECAY_MS) / (3000.0 - START_DECAY_MS);
//            decayFactor = Math.max(0.1, decayFactor); // 至少保留 10% 威胁以应对近距离
//        }
//
//        // 随机性：难度越高，越容易将枪声判断为威胁
//        if (rand.nextDouble() > difficulty.penetrationChance) {
//            return false;
//        }
//
//        // --- 模拟计算穿透伤害衰减 ---
//        Line2D.Double ray = new Line2D.Double(self.position, lastHeardSoundCue.position());
//
//        double totalThickness = 0;
//
//        List<Shape> intersectingWalls = new ArrayList<>();
//        for (Shape obs : pathfinder.getObstacles()) {
//            if (obs.intersects(ray.getBounds2D()) && GameState.getLineShapeIntersections(ray, obs) != null) {
//                intersectingWalls.add(obs);
//            }
//        }
//        intersectingWalls.sort(Comparator.comparingDouble(s -> self.position.distance(s.getBounds2D().getCenterX(), s.getBounds2D().getCenterY())));
//
//        for (Shape wall : intersectingWalls) {
//            Point2D.Double[] intersections = GameState.getLineShapeIntersections(ray, wall);
//            if (intersections != null && intersections.length >= 2) {
//                totalThickness += intersections[0].distance(intersections[1]);
//            }
//        }
//
//        double initialPenetrationPower = wep.penetrationPower;
//
//        // 如果连自己武器的穿透力都无法穿过这些障碍物，那自然没有威胁
//        if (initialPenetrationPower <= totalThickness) {
//            // 穿不透不代表威胁解除，先不忘掉，让计时器决定
//            return false;
//        }
//
//        // --- 核心判断逻辑---
//        double remainingPower = initialPenetrationPower - totalThickness;
//        double damageMultiplier = remainingPower / initialPenetrationPower;
//
//        // 如果计算出的伤害衰减小于50%（即能造成超过50%的伤害），就判定为高威胁！
//        if (damageMultiplier >= 0.5) {
//            return true; // 返回true，触发反击！
//        }
//        // 最终威胁判断：引入衰减因子
//        double finalThreatValue = damageMultiplier * decayFactor;
//
//        /** 解释：如果AI的生命值不满，它会变得更加警觉。生命值越低，这个系数越高，使得AI在受伤时更容易将一个模糊的枪声判断为需要立即反击的严重威胁。*/
//        if (self.health < 100) {
//            // 生命值越低，威胁感知越强。这里用一个简单的线性公式来计算增益。
//            // 例如：99血时威胁*1.03，50血时威胁*2.5，1血时威胁约*4.0。
//            double healthBonus = 1.0 + 3* (100.0 - self.health) / 100.0;
//            finalThreatValue *= healthBonus;
//
//            // 简单粗暴地直接翻倍?
//            // finalThreatValue *= 2.0;
//        }
//        // 只有当最终威胁值足够高时（例如 > 25% 的初始伤害），才触发反击
//        if (finalThreatValue >= 0.25) {
//            return true;
//        }
//
//        return false;
//    }
//    /**
//     * 执行盲射反击的战术动作。
//     */
//    private void performBlindRetaliation() {
//        if (lastHeardSoundCue == null) {
//            return;
//        }
//        clearPath();
//        aimAndShoot(lastHeardSoundCue.position());
//
//        if (System.currentTimeMillis() < burstCooldownUntil) {
//            self.isShooting = false;
//        }
//        if (self.isShooting) {
//            shotsFiredAtCurrentTarget++;
//        }
//        if (shotsFiredAtCurrentTarget >= 5) {
//            burstCooldownUntil = System.currentTimeMillis() + 800;
//            shotsFiredAtCurrentTarget = 0;
//        }
//    }
//    private void updateBot_Demolition(List<Player> allCharacters, GameState state) {
//        // --- 回合状态重置、换弹等中断逻辑  ---
//        if (state.getRoundPhase() == GameState.RoundPhase.FREEZE_TIME && lastRoundPhase != GameState.RoundPhase.FREEZE_TIME) {
//            if (currentState == AIState.PLANTING_BOMB || currentState == AIState.DEFUSING_BOMB) {
//                gameState.playerStopInteraction(self.id);
//                currentState = AIState.PATROLLING;
//            }
//            hasBoughtThisRound = false;
//            assignedObjective = null;
//        }
//        if (self.isReloading) {
//            handleReloading();
//            followPath();
//            return;
//        }
//        if (state.getRoundPhase() == GameState.RoundPhase.FREEZE_TIME) {
//            if (!hasBoughtThisRound) {
//                makePurchases();
//                hasBoughtThisRound = true;
//            }
//        }
//
//        // ======================= [ 核心优先级 ] =======================
//        // (D) 视觉攻击 (新优先级：最高)
//        if (target != null && hasLineOfSight(target.position)) {
//            if (shouldEngage()) {
//                currentState = AIState.ATTACKING;
//                attack(); // 执行开火和走位
//                followPath(); // 保证走位能执行
//                return; // <--- 优先处理战斗，并立即退出
//            }
//        }
//
//        //  “安包检查” 拥有绝对优先权?
//        // **最高优先级A (T方)：安放C4**
//        if (self.hasBomb && isAtBombSite()) {
//            if (attackBombSite(state)) {
//                return;
//            }
//        }
//
//        // **最高优先级B (CT方 - 拆弹手)：拆除C4**
//        // 只有被指挥官指定为“拆弹手”的AI，才会执行拆包逻辑。
//        if (self.team == Player.Team.CT && state.isBombPlanted() && tacticRole == TacticRole.DEFUSER) {
//            // retakeSite 包含移动到炸弹和开始交互的逻辑
//            retakeSite(state);
//            followPath();
//            return; // 作为拆弹手，这是当前唯一的任务
//        }
//            // “护卫”指令的优先级高于所有常规的战术移动指令
//        //  护卫行为逻辑
//        if (tacticRole == TacticRole.BODYGUARD) {
//            // 【主检查】确保目标玩家不为 null 且仍活着
//            if (playerToFollow != null && playerToFollow.isAlive()) {
//                // 英雄存活，执行护卫任务
//
//                // 【空指针修复点 1：检查英雄位置是否有效】
//                if (playerToFollow.position == null) {
//                    logger.accept("[BODYGUARD] " + self.name + " 英雄玩家位置无效，强制解除护卫任务。");
//                    clearTacticalObjective();
//                    return;
//                }
//
//                //    威胁评估：索敌，并检查是否有近距离威胁
//                findTarget(allCharacters);
//                // 威胁范围定义为600像素
//                boolean immediateThreat = (target != null && self.position.distance(target.position) < 600);
//
//                if (immediateThreat) {
//                    // 战术：顶上去吸引火力！
//                    this.tacticalObjective = target.position;
//
//                    // 强行让自己进入攻击状态，并执行攻击（包含移动）
//                    currentState = AIState.ATTACKING;
//                    attack();
//                    followPath();
//
//                } else {
//                    // 常规战术：保持跟随
//                    this.tacticalObjective = playerToFollow.position;
//                    final double FOLLOW_DISTANCE = 150.0;
//                    if (self.position.distance(playerToFollow.position) > FOLLOW_DISTANCE) {
//                        if (shouldRecalculatePath()) setNewPath(playerToFollow.position);
//                        followPath();
//                    } else {
//                        clearPath();
//                        this.targetAngle = Math.atan2(playerToFollow.position.y - self.position.y, playerToFollow.position.x - self.position.x);
//                    }
//                }
//
//            } else {
//                // 英雄已阵亡（!isAlive()）或目标玩家引用本身已丢失（playerToFollow == null）
//                logger.accept("[BODYGUARD] " + self.name + "'s hero has fallen or target is null! Attempting to retrieve weapon.");
//
//                // 【空指针修复点 2：在执行捡枪逻辑前，双重检查 playerToFollow 和其 position】
//                if (playerToFollow != null && playerToFollow.position != null) {
//                    // 在英雄最后的位置附近寻找掉落的长枪
//                    GameState.DroppedItem heroWeapon = findDroppedWeaponNear(playerToFollow.position);
//                    if (heroWeapon != null) {
//                        // 找到了！切换到“捡枪”模式
//                        setTacticalObjective(heroWeapon.position, RETRIEVING_HERO_WEAPON);
//                    } else {
//                        // 没找到枪，任务解除，恢复自由
//                        clearTacticalObjective();
//                    }
//                } else {
//                    // target is null 或 target.position 是 null，任务解除，恢复自由
//                    clearTacticalObjective();
//                }
//            }
//            return; // 护卫逻辑是独立的，执行后直接结束本帧
//        }
//
//        //   捡枪行为逻辑
//        if (tacticRole == RETRIEVING_HERO_WEAPON) {
//            final double PICKUP_RANGE = Player.SIZE;
//
//            // 检查是否已到达武器附近
//            if (tacticalObjective != null && self.position.distance(tacticalObjective) < PICKUP_RANGE) {
//                // 假设已经捡到了（实际的拾取由GameState主逻辑处理）
//                logger.accept("[BODYGUARD] " + self.name + " has retrieved the hero's weapon! Continuing the fight!");
//                // 捡到枪后，任务完成，清除所有指令，等待指挥官的新任务（很可能会被指派为突击手）
//                clearTacticalObjective();
//            } else {
//                // 还没到，继续跑向武器位置
//                if (shouldRecalculatePath()) setNewPath(tacticalObjective);
//                followPath();
//            }
//            return; // 捡枪逻辑也是独立的，执行后直接结束
//        }
//
//
//        // **第二优先级：执行指挥官的战术命令**
//        if (tacticalObjective != null) {
//            // 首先，无论如何都先索敌
//            findTarget(allCharacters);
//
//            // --- [最终优化逻辑] 统一处理所有角色 ---
//
//            boolean isInGreenChannel = (tacticRole == TacticRole.ROTATOR ||
//                    tacticRole == TacticRole.DEFUSER ||
//                    tacticRole == TacticRole.ENTRY   ||
//                    tacticRole == TacticRole.SUPPORT);
//
//            boolean isDefenderOnPost = (tacticRole == TacticRole.DEFENDER &&
//                    self.position.distance(tacticalObjective) < 150.0);
//
//            boolean shouldFight = false;
//            if (tacticRole == TacticRole.SAVER) {
//                shouldFight = false;
//                self.isShooting = false;
//            }
//            else if (target != null && hasLineOfSight(target.position)) {
//                boolean wasRecentlyHit = (System.currentTimeMillis() - lastTimeDamaged < RECENTLY_HIT_DURATION_MS);
//                if (isInGreenChannel || (tacticRole == TacticRole.DEFENDER && !isDefenderOnPost)) {
//                    if (wasRecentlyHit) shouldFight = true;
//                } else if (isDefenderOnPost) {
//                    if (shouldEngage()) shouldFight = true;
//                } else { // Other roles
//                    if (shouldEngage()) shouldFight = true;
//                }
//            }
//
//            if (shouldFight) {
//                currentState = AIState.ATTACKING;
//                if (isDefenderOnPost) {
//                    aimAndShoot(target);
//                    clearPath();
//                } else {
//                    if (isInGreenChannel) {
//                        aimAndShoot(target);
//                    } else {
//                        attack();
//                    }
//                }
//            } else {
//                self.isShooting = false;
//                if (currentPath == null || currentPath.isEmpty()) {
//                    if (System.currentTimeMillis() - lastPathRecalculationTime > 1000) {
//                        logger.accept(String.format("[%s] Path is null, attempting setNewPath to %s.", self.name, formatObjective(tacticalObjective)));
//                        boolean pathFound = setNewPath(tacticalObjective);
//                        lastPathRecalculationTime = System.currentTimeMillis();
//                    }
//                }
//            }
//
//            if ((isDefenderOnPost || tacticRole == TacticRole.SAVER) && !shouldFight) {
//                if (tacticRole == TacticRole.DEFENDER) {
//                    clearPath();
//                    this.targetAngle = Math.atan2(tacticalObjective.y - self.position.y, tacticalObjective.x - self.position.x);
//                }
//            }
//
//            if (!isDefenderOnPost) {
//                followPath();
//            }
//
//            // --- [关键修复!] ---
//            // 无论上面执行了什么逻辑 (战斗或移动)，只要处理了指挥官命令，就必须在这里返回！
//            // 阻止代码继续执行到下面的 "(D) 视觉攻击" 等默认逻辑。
//            return;
//            // --- [修复结束] ---
//        }
//
//
//        // --- 核心战术决策循环 ---
//
//        // (A) 视觉索敌：这是所有决策的基础
//        findTarget(allCharacters);
//
//        // (B) 道具决策：如果看到敌人，考虑是否使用道具
//        if (target != null) {
//            if (System.currentTimeMillis() > nextTacticalUpdateTime) {
//                if (useTacticalGrenade()) {
//                    // 如果决定扔雷，直接返回，进入 PREPARING_GRENADE 状态
//                    nextTacticalUpdateTime = System.currentTimeMillis() + 1000 + rand.nextInt(1000);
//                    return;
//                }
//                nextTacticalUpdateTime = System.currentTimeMillis() + 300 + rand.nextInt(400);
//            }
//        }
//
//        // (C) 任务优先：检查是否满足安包条件
//        if (self.hasBomb && isAtBombSite()) {
//            // 调用 attackBombSite 并检查其返回值
//            if (attackBombSite(state)) {
//
//                // 取消安包指令。
//                return;
//            }
//        }
//
//        // (D) 视觉攻击：如果视野里有敌人，立刻攻击
//        if (target != null && hasLineOfSight(target.position)) {
//
//            // 首先，判断自己是否是辅助角色
//            boolean isSupportRole = (tacticRole == TacticRole.BOMB_CARRIER ||
//                    tacticRole == TacticRole.ROTATOR ||
//                    tacticRole == TacticRole.DEFENDER ||
//                    tacticRole == TacticRole.SAVER);
//
//            // 决策：我是否应该开枪？
//            if (shouldEngage()) {
//                // 即使决定开枪，进攻型角色也应该在开枪前考虑扔雷
//                if (!isSupportRole) {
//                    useTacticalGrenade(); // 尝试提交一个扔雷任务
//                    if (isCalculatingGrenade) {
//                        clearPath(); // 如果开始计算了，就停下等待，下一帧再决定
//                        return;
//                    }
//                }
//                // 执行攻击
//                currentState = AIState.ATTACKING;
//                attack();
//                followPath();
//                return;
//
//            } else {
//                // 决策：虽然有子弹，但决定不开枪 (可能因为任务优先、位置不利等)。
//                self.isShooting = false;
//
//                // 尝试让支援角色使用道具 (逻辑不变)
//                if (isSupportRole) {
//                    useTacticalGrenade(); // 尝试提交一个扔雷任务
//                    if (isCalculatingGrenade) {
//                        // 如果开始计算了，就停下等待，下一帧再决定
//                        clearPath();
//                        logger.accept(String.format("[%s] (State: %s, Role: %s) 决定不交战，正在计算投掷 %s 到 %s (掩护已禁用).",
//                                self.name,
//                                currentState.name(),
//                                tacticRole.name(),
//                                grenadeToThrow != null ? grenadeToThrow.name() : "道具", // 显示准备扔的道具
//                                formatObjective(grenadeTargetPosition) // 显示目标点
//                        ));
//                        return;
//                    }
//                }
//
//                // 如果不是使用道具的支援角色，或者没能扔出道具，则继续执行当前任务路径
//                // (原“寻找掩护”逻辑保持禁用状态)
//
//                // ================== [禁用掩护 - 确认] ==================
//                // 确保这部分代码是被注释掉的
//                /*
//                Point2D.Double coverPoint = findCover(target.position);
//                if (coverPoint != null && self.position.distance(coverPoint) > 50) {
//                    setNewPath(coverPoint);
//                    currentState = AIState.TAKING_COVER;
//                }
//                */
//                // ================== [禁用掩护 - 确认结束] ==================
//
//                // --- [日志细化] ---
//                String targetName = (target != null) ? target.name : "未知目标";
//                String objectiveDesc = (tacticalObjective != null) ? formatObjective(tacticalObjective) : "未知地点"; // 修正：使用 tacticalObjective
//                logger.accept(String.format("[%s] (State: %s, Role: %s) 决定不与 %s 交战，继续前往目标 %s (主动掩护已禁用).",
//                        self.name,
//                        currentState.name(), // 显示当前状态
//                        tacticRole.name(),   // 显示战术角色
//                        targetName,          // 显示当前瞄准的目标
//                        objectiveDesc        // 显示指挥官给的目标
//                ));
//                // --- [日志细化结束] ---
//
//
//                // AI 现在即使决定不射击，也会继续执行当前路径
//                followPath();
//                return;
//            }
//        }
//
//        // (E) 听觉反击 (次高战斗优先级)：如果视野里没人，但听到了有威胁的枪声
//        if (assessHeardThreat()) {
//            // 盲射反击，无论如何都应该执行
//            currentState = AIState.BLIND_RETALIATION;
//            performBlindRetaliation(); // 执行盲射反击
//            return;
//        }
//
//        // (F) 状态清理：如果AI之前在盲射，但现在威胁已解除，则恢复巡逻
//        if (currentState == AIState.BLIND_RETALIATION) {
//            currentState = AIState.PATROLLING;
//        }
//
//        // ======================= [对枪声的“预瞄”反应] =======================
//        //它会停止“看路”的默认行为，转而将枪口朝向枪声来源方向，保持警惕。
//        boolean isEngagedInCombat = (currentState == AIState.ATTACKING || currentState == AIState.BLIND_RETALIATION);
//        if (!isEngagedInCombat && lastHeardSoundCue != null) {
//            // 存在枪声记忆，且没有在交火
////            logger.accept(self.name + " heard a sound. Pre-aiming towards threat direction while moving.");
//
//            // 计算朝向枪声位置的角度
//            double angleToSound = Math.atan2(lastHeardSoundCue.position().y - self.position.y,
//                    lastHeardSoundCue.position().x - self.position.x);
//
//            // <注意> 设置目标角度，但不开火
//            this.targetAngle = angleToSound;
//            self.isShooting = false;
//        }
//
//        // ---  如果没有任何战斗发生，则执行常规的游戏目标 ---
//        self.isShooting = false;
//        GameState.DroppedItem droppedBomb = state.getDroppedBomb();
//        if (droppedBomb != null) {
//            if (self.team == Player.Team.T) {
//                if (droppedBomb != null) {
//                    prioritizeDroppedBomb_T(droppedBomb);
//                } else {
//                    if (state.isBombPlanted()) {
//                        defendBombSite(state);
//                    } else {
//                        attackBombSite(state); // <--- 这里会计算目标 assignedObjective
//                    }
//                }
//
//                //  强制 T 方在 MOVING_TO_SITE 状态下检查路径
//                if (currentState == AIState.MOVING_TO_SITE && assignedObjective != null) {
//                    // 如果没有路径或路径已走完，则强制立即重新计算
//                    if (currentPath == null || currentPath.isEmpty()) {
//                        // 确保强制进行寻路，如果失败，则记录日志并切换到巡逻。
//                        if (!setNewPath(assignedObjective)) {
//                            // 如果寻路失败（目标不可达），则放弃移动到包点，进入巡逻
//                            logger.accept(self.name + " 寻路到目标点失败，切换到巡逻。");
//                            currentState = AIState.PATROLLING;
//                        }
//                    }
//                }
//
//            }
//            else guardDroppedBomb_CT(droppedBomb);
//        } else {
//            if (self.team == Player.Team.T) {
//                if (state.isBombPlanted()) defendBombSite(state);
//                else attackBombSite(state);
//            } else {
//                if (state.isBombPlanted()) retakeSite(state);
//                else defendBombSite(state);
//            }
//        }
//
//        // ---  根据寻路结果执行移动 ---
//        if (currentState != AIState.PLANTING_BOMB && currentState != AIState.DEFUSING_BOMB) {
//            followPath();
//        }
//    }
//
//
//    /**
//     *  辅助方法：在指定位置附近寻找最有价值的掉落武器。
//     * @param location 英雄阵亡的位置
//     * @return 找到的最有价值的 DroppedItem 对象，如果没找到则返回 null。
//     */
//    private GameState.DroppedItem findDroppedWeaponNear(Point2D.Double location) {
//        if (location == null) return null;
//
//        return gameState.getDroppedItems().stream()
//                //  必须是武器，不能是C4
//                .filter(item -> !item.isBomb && item.weapon != null)
//                //   必须是长枪
//                .filter(item -> item.weapon.getWeaponType().isLongRange())
//                //   必须在英雄死亡点附近的一个合理范围内（例如300像素）
//                .filter(item -> item.position.distance(location) < 300)
//                // 排序：找到离死亡点最近的那一把
//                .min(Comparator.comparingDouble(item -> item.position.distance(location)))
//                // 如果找到了就返回，否则返回null
//                .orElse(null);
//    }
//    /** [旧版]
//     * 僵尸模式专属的“风筝”战术逻辑。
//     * AI会根据与最近僵尸的距离，决定后退、站桩或迂回。
//     * @param allCharacters 游戏中的所有角色列表
//     */
//    private void handleZombieKiting(List<Player> allCharacters) {
//        if (target == null) return; // 没有目标则不做任何事
//
//        //  找到威胁最大的敌人——离自己最近的那个僵尸
//        Player nearestZombie = allCharacters.stream()
//                .filter(p -> p.team == Player.Team.ZOMBIE && p.isAlive())
//                .min(Comparator.comparingDouble(p -> self.position.distanceSq(p.position)))
//                .orElse(null);
//
//        if (nearestZombie == null) {
//            // 如果视野内没有僵尸，但依然有目标（可能在障碍物后），则正常攻击
//            if (hasLineOfSight(target.position)) {
//                attack();
//            }
//            return;
//        }
//
//        //  根据距离定义战术
//        double distanceToNearest = self.position.distance(nearestZombie.position);
//        final double PANIC_DISTANCE = Player.SIZE * 0.5;   // 恐慌距离：僵尸太近了，必须跑！ //取消恐慌距离
//        final double KITING_DISTANCE = 300; // 风筝距离：理想的边退边打距离
//
//        if (distanceToNearest < PANIC_DISTANCE) {
//            // 恐慌状态：优先逃跑
//            self.isShooting = false; // 停止射击以获得更快的移动速度
//            clearPath();
//            // 计算远离僵尸的方向
//            double angleAway = Math.atan2(self.position.y - nearestZombie.position.y, self.position.x - nearestZombie.position.x);
//            self.ax = Math.cos(angleAway);
//            self.ay = Math.sin(angleAway);
//            logger.accept(self.name + " is in PANIC, retreating from zombie.");
//
//        } else if (distanceToNearest < KITING_DISTANCE) {
//            // 风筝状态：边退边打
//            clearPath(); // 清除寻路路径，但不影响本帧的加速度
//
//            // 设置后退的加速度
//            double angleAway = Math.atan2(self.position.y - nearestZombie.position.y, self.position.x - nearestZombie.position.x);
//            self.ax = Math.cos(angleAway);
//            self.ay = Math.sin(angleAway);
//
//            //  直接执行瞄准和射击，而不是调用attack()
//            if (target != null && target.isAlive() && hasLineOfSight(target.position)) {
//                // 简单检查一下射击冷却和弹药
//                if (System.currentTimeMillis() >= burstCooldownUntil && self.currentAmmo > 0) {
//                    aimAndShoot(target); // 只调用瞄准射击函数
//                } else {
//                    self.isShooting = false;
//                }
//            }
//
//            logger.accept(self.name + " is KITING zombie.");
//
//        } else {
//            //安全状态：站桩输出
//            self.ax = 0;
//            self.ay = 0;
//            // 这里可以安全地调用attack()，因为它不会有移动意图
//            attack();
//            logger.accept(self.name + " is holding position against zombie.");
//        }
//    }
//
//    private void updateBot(List<Player> allCharacters) {
//        // ======================= [僵尸模式分流] =======================
//        if (gameState.getGameMode() == GameMode.ZOMBIE_MODE && self.team == Player.Team.CT) {
//            updateSurvivor(allCharacters);
//            // followPath() 会在主 update 循环的末尾被调用，这里不需要重复调用
//            return; // 执行完幸存者逻辑后，跳过所有常规模式的逻辑
//        }
//
//
//        //==========其他模式===========
//        // 处理中断行为（例如换弹）
//        if (self.isReloading) {
//            handleReloading(); // 换弹时，AI会尝试找掩体或逃跑
//            followPath();      // 沿着路径移动
//            return;
//        }
//        // 如果AI刚结束找掩体（通常是换弹完毕），则重置其状态
//        if (currentState == AIState.TAKING_COVER) {
//            currentState = AIState.PATROLLING;
//        }
//
//        if (currentState == AIState.IDLE) {
//            currentState = AIState.PATROLLING;
//        }
//
//        // 索敌
//        findTarget(allCharacters);
//
//        // 如果没有子弹了，强制换弹并中断后续逻辑
//        if (self.currentAmmo == 0 && !self.isReloading && self.reserveAmmo > 0) {
//            self.startReload();
//            clearPath();
//            return;
//        }
//
//        // ---AI核心战术决策 (状态机) ---
//        // 这是AI主要的决策入口，决定AI在这一帧做什么。
//        // 这里会根据当前状态进行判断，并可能切换到其他状态。
//        switch (currentState) {
//            case PATROLLING, IDLE:
//                patrol(); // AI执行巡逻移动
//                // 状态切换判断：如果看到了敌人
//                if (target != null && hasLineOfSight(target.position)) {
//                    // 检查AI的“战术思考”冷却时间是否已到
//                    if (System.currentTimeMillis() > nextTacticalUpdateTime) {
//                        // 尝试进行道具决策
//                        if (useTacticalGrenade()) {
//                            // 如果成功扔雷，更新一个较长的冷却时间（道具CD），并让AI处于 PREPARING_GRENADE 状态。
//                            nextTacticalUpdateTime = System.currentTimeMillis() + 500 + rand.nextInt(1000);
//                            // useTacticalGrenade() 内部会设置 currentState = AIState.PREPARING_GRENADE
//                        } else {
//                            // 如果没有扔雷（无论是没道具、没角度还是战术不符），则更新一个较短的冷却时间，
//                            // 然后进入攻击状态，准备开枪。
//                            nextTacticalUpdateTime = System.currentTimeMillis() + 300 + rand.nextInt(400);
//                            currentState = AIState.ATTACKING;
//                        }
//                    } else {
//                        // 如果战术思考冷却未到，暂时不考虑扔雷，但如果看到了敌人，直接进入攻击状态（AI不能傻站着）。
//                        currentState = AIState.ATTACKING;
//                    }
//                }
//                break;
//
//            case ATTACKING:
//                // 状态切换判断：如果目标丢失
//                if (target == null || !target.isAlive() || !hasLineOfSight(target.position)) {
//                    currentState = AIState.HUNTING; // 丢失目标则进入追击状态
//                    clearPath(); // 清除当前路径
//                } else {
//                    // 持续攻击状态下，也找机会决策是否扔道具
//                    if (System.currentTimeMillis() > nextTacticalUpdateTime) {
//                        if (useTacticalGrenade()) {
//                            // 如果成功扔雷，更新较长冷却时间，进入 PREPARING_GRENADE 状态。
//                            nextTacticalUpdateTime = System.currentTimeMillis() + 1000 + rand.nextInt(1000);
//                        } else {
//                            // 如果没有扔雷，更新较短冷却时间，然后继续执行攻击行为。
//                            nextTacticalUpdateTime = System.currentTimeMillis() + 300 + rand.nextInt(400);
//                            attack(); // 执行开火和走位
//                        }
//                    } else {
//                        // 如果战术思考冷却未到，暂时不扔雷，但继续执行攻击行为。
//                        attack();
//                    }
//                }
//                break;
//
//            case HUNTING:
//                hunt(); // AI执行追击移动
//                // 状态切换判断：如果重新看到了敌人
//                if (target != null && hasLineOfSight(target.position)) {
//                    // 同样，在进入攻击前先决策是否扔道具
//                    if (System.currentTimeMillis() > nextTacticalUpdateTime) {
//                        if (useTacticalGrenade()) {
//                            nextTacticalUpdateTime = System.currentTimeMillis() + 1000 + rand.nextInt(1000);
//                        } else {
//                            nextTacticalUpdateTime = System.currentTimeMillis() + 300 + rand.nextInt(400);
//                            currentState = AIState.ATTACKING;
//                        }
//                    } else {
//                        currentState = AIState.ATTACKING;
//                    }
//                }
//                break;
//
//            case PREPARING_GRENADE:
//                // 这个状态会在 update() 方法的顶层被优先处理，所以这里不需额外逻辑
//                break;
//
//            default:
//                // 如果AI处于未知状态，默认切换回巡逻。
//                currentState = AIState.PATROLLING;
//                break;
//        }
//
//        // 执行移动
//        // 确保AI实体根据其当前计算出的加速度（self.ax, self.ay）移动。
//        followPath();
//    }
//
//
//    // T方优先去捡掉落的C4。
//    private void prioritizeDroppedBomb_T(GameState.DroppedItem bomb) {
//        currentState = AIState.RETRIEVING_BOMB; // 设置状态为捡包。
//
//        // 同步更新 AI 的目标变量
//        this.assignedObjective = bomb.position;
//
//        if (shouldRecalculatePath()) { // 如果可以重新计算路径。
//            setNewPath(bomb.position); // 设置新路径到C4的位置。
//        }
//    }
//
//    // CT方防守掉落的C4。
//    private void guardDroppedBomb_CT(GameState.DroppedItem bomb) {
//        currentState = AIState.GUARDING_BOMB; // 设置状态为守包。
//        this.assignedObjective = bomb.position;
//        // 如果离包太远，就跑过去。
//        if (self.position.distance(bomb.position) > 300) {
//            if (shouldRecalculatePath()) { // 如果可以重新计算路径。
//                setNewPath(bomb.position); // 设置新路径到C4的位置。
//            }
//        } else { // 如果已经很近了。
//            // 在包附近巡逻。
//            if (currentPath == null || currentPath.isEmpty()) { // 如果没有巡逻路径。
//                patrolNearPoint(bomb.position, 200); // 在包周围200半径内找一个点巡逻。
//            }
//        }
//    }
//
//        // T方进攻包点。[旧版]已弃用
//    private long lastPlantAttemptTime = 0;
//    private static final long FORCE_PLANT_COOLDOWN = 3000; // 3秒强制安包冷却
//
//    private boolean attackBombSite(GameState state) {
//        // 1. **状态锁存检查**
//        if (currentState == AIState.PLANTING_BOMB) {
//            // AI线程现在只负责维持自身状态和清除移动意图
//            self.ax = 0; self.ay = 0;
//            self.vx = 0; self.vy = 0;
//            self.keysDown.clear();
//            self.isShooting = false;
//
//            // AI不再直接调用gameState.playerStartInteraction()
//            // 它只是确保自己的isInteracting标志为true，这个标志会被AIService打包发送
//            self.isInteracting = true;
//
//            if (!self.hasBomb || !isAtBombSite()) {
//                logger.accept(self.name + " [PLANT_CANCEL] 条件失效，停止交互意图。");
//                self.isInteracting = false; // 停止交互意图
//                currentState = AIState.MOVING_TO_SITE;
//            }
//            return true;
//        }
//
//        //  **触发条件检查**
//        if (self.hasBomb && isAtBombSite()) {
//            logger.accept(self.name + " 已在包点，设置安放意图！");
//            clearPath();
//            self.isShooting = false;
//
//            //  不调用服务器方法，只改变自身状态
//            currentState = AIState.PLANTING_BOMB;
//            self.isInteracting = true; // 设置交互意图
//            self.interactionStartTime = System.currentTimeMillis(); // AI自己记录开始时间
//
//            logger.accept(self.name + " [PLANT_INTENT_SET] 成功设置安包意图。");
//            return true;
//        }
//
//        //   **默认移动逻辑**
//        if (assignedObjective == null || currentState != AIState.MOVING_TO_SITE) {
//            executeAttackStrategy(state);
//        }
//        followPath();
//        return false;
//    }
//    /**
//     * 评估当前是否安全可以安包
//     * 返回true表示可以安包，false表示需要等待
//     */
//
//    private boolean assessPlantingSafety() {
//        StringBuilder debugInfo = new StringBuilder(self.name + " 安包安全评估: ");
//
//        boolean anyEnemyVisible = isAnyEnemyVisible();
//        boolean teammateCovering = hasTeammateCovering();
//        boolean enemiesAtSafeDistance = areEnemiesAtSafeDistance();
//        boolean enemiesDistracted = areEnemiesDistracted();
//        boolean plantingUrgent = isPlantingUrgent();
//
//        debugInfo.append("可见敌人=").append(anyEnemyVisible)
//                .append(", 队友掩护=").append(teammateCovering)
//                .append(", 安全距离=").append(enemiesAtSafeDistance)
//                .append(", 敌人分心=").append(enemiesDistracted)
//                .append(", 情况紧急=").append(plantingUrgent);
//
//        boolean result = !anyEnemyVisible || teammateCovering || enemiesAtSafeDistance || enemiesDistracted || plantingUrgent;
//
//        debugInfo.append(" => ").append(result ? "允许安包" : "禁止安包");
//        logger.accept(debugInfo.toString());
//
//        return result;
//    }
//
//    /**
//     *  [旧版]计算可见威胁等级
//     * 0 = 无威胁，1 = 低威胁，2 = 中威胁，3 = 高威胁
//     */
//    private int calculateVisibleThreatLevel() {
//        int threatLevel = 0;
//
//        for (Player enemy : knownPlayers) {
//            if (enemy.team != self.team && enemy.isAlive()) {
//                double distance = self.position.distance(enemy.position);
//                boolean hasLoS = hasLineOfSight(enemy.position);
//
//                if (hasLoS) {
//                    if (distance < 50) {
//                        threatLevel = Math.max(threatLevel, 3); // 近距离可见 - 高威胁
//                    } else if (distance < 100) {
//                        threatLevel = Math.max(threatLevel, 2); // 中距离可见 - 中威胁
//                    } else {
//                        threatLevel = Math.max(threatLevel, 1); // 远距离可见 - 低威胁
//                    }
//                } else if (distance < 200) {
//                    threatLevel = Math.max(threatLevel, 1); // 近距离但无视线 - 低威胁
//                }
//            }
//        }
//
//        return threatLevel;
//    }
//
//    /**
//     *  [旧版]检查敌人是否在较远距离且没有直接视线
//     */
//    private boolean areEnemiesFarAwayAndNotVisible() {
//        return knownPlayers.stream()
//                .filter(p -> p.team != self.team && p.isAlive())
//                .allMatch(p ->
//                        self.position.distance(p.position) > 400 && // 距离大于400像素
//                                !hasLineOfSight(p.position) // 且没有直接视线
//                );
//    }
//
//    /**
//     * 降低安全距离要求
//     */
//    private boolean areEnemiesAtSafeDistance() {
//        return knownPlayers.stream()
//                .filter(p -> p.team != self.team && p.isAlive())
//                .allMatch(p ->
//                        self.position.distance(p.position) > 50 || //安全距离50
//                                !hasLineOfSight(p.position)
//                );
//    }
//    /**
//     * 检查是否有任何敌人可见
//     */
//    private boolean isAnyEnemyVisible() {
//        return knownPlayers.stream()
//                .anyMatch(p -> p.team != self.team && p.isAlive() && hasLineOfSight(p.position));
//    }
//
//    /**
//     * 检查是否有队友在附近提供掩护
//     */
//    private boolean hasTeammateCovering() {
//        long nearbyTeammates = knownPlayers.stream()
//                .filter(p -> p.team == self.team && p.isAlive() && p != self)
//                .filter(p -> self.position.distance(p.position) < 400) // 400像素范围内的队友
//                .count();
//
//        return nearbyTeammates >= 1; // 至少有一个队友在附近
//    }
//
//
//
//    /**
//     * 检查敌人是否被牵制（正在交火等）
//     */
//    private boolean areEnemiesDistracted() {
//        return knownPlayers.stream()
//                .filter(p -> p.team != self.team && p.isAlive())
//                .anyMatch(p -> {
//                    // 敌人正在射击
//                    if (p.isShooting) return true;
//
//                    // 敌人正在换弹
//                    if (p.isReloading) return true;
//
//                    // 敌人正在与队友交火
//                    if (isEngagedWithTeammate(p)) return true;
//
//                    return false;
//                });
//    }
//
//    /**
//     * 检查特定敌人是否正在与队友交火
//     */
//    private boolean isEngagedWithTeammate(Player enemy) {
//        return knownPlayers.stream()
//                .filter(p -> p.team == self.team && p.isAlive() && p != self)
//                .anyMatch(teammate -> {
//                    // 队友正在瞄准这个敌人
//                    double angleToEnemy = Math.atan2(enemy.position.y - teammate.position.y,
//                            enemy.position.x - teammate.position.x);
//                    double angleDiff = Math.abs(teammate.angle - angleToEnemy);
//                    return angleDiff < Math.toRadians(30) && // 角度差小于30度
//                            teammate.position.distance(enemy.position) < 600; // 距离适中
//                });
//    }
//
//    /**
//     * 检查是否情况紧急需要强制安包
//     */
//    private boolean isPlantingUrgent() {
//        // 回合剩余时间很少
//        if (gameState.getRoundTimeRemaining() < 30000) { // 剩余30秒
//            return true;
//        }
//
//        // 自己血量很低
//        if (self.health < 30) {
//            return true;
//        }
//
//        // 队友数量很少
//        long aliveTeammates = knownPlayers.stream()
//                .filter(p -> p.team == self.team && p.isAlive())
//                .count();
//        long aliveEnemies = knownPlayers.stream()
//                .filter(p -> p.team != self.team && p.isAlive())
//                .count();
//
//        // 人数劣势很大
//        if (aliveTeammates * 2 < aliveEnemies) {
//            return true;
//        }
//
//        return false;
//    }
//    // 执行进攻策略。
//    private void executeAttackStrategy(GameState state) {
//        currentState = AIState.MOVING_TO_SITE;
//
//        Point2D.Double targetSiteA = getCenter(state.getBombSiteA());
//        Point2D.Double targetSiteB = getCenter(state.getBombSiteB());
//
//        // 【修复】确保 assignedObjective 不为 null
//        if (assignedObjective == null || currentState != AIState.MOVING_TO_SITE) {
//            Player bombCarrier = this.knownPlayers.stream()
//                    .filter(p -> p.team == Player.Team.T && p.hasBomb)
//                    .findFirst().orElse(null);
//
//            if (self.hasBomb || bombCarrier == null) {
//                assignedObjective = rand.nextBoolean() ? targetSiteA : targetSiteB;
//            } else {
//                if (bombCarrier.aiController != null && bombCarrier.aiController.assignedObjective != null) {
//                    Point2D.Double teamObjective = bombCarrier.aiController.assignedObjective;
//                    if (rand.nextDouble() < 0.85) {
//                        assignedObjective = teamObjective;
//                    } else {
//                        assignedObjective = teamObjective.equals(targetSiteA) ? targetSiteB : targetSiteA;
//                    }
//                } else {
//                    assignedObjective = rand.nextBoolean() ? targetSiteA : targetSiteB;
//                }
//            }
//
//            // 【新增】双重检查，确保 assignedObjective 不为 null
//            if (assignedObjective == null) {
//                logger.accept(self.name + " 错误：无法确定进攻目标，使用默认A点");
//                assignedObjective = targetSiteA != null ? targetSiteA :
//                        targetSiteB != null ? targetSiteB :
//                                new Point2D.Double(gameState.width / 2.0, gameState.height / 2.0);
//            }
//        }
//
//        // 添加空值检查
//        if (assignedObjective == null) {
//            logger.accept(self.name + " 错误：assignedObjective 为 null，无法执行进攻策略");
//            currentState = AIState.PATROLLING;
//            return;
//        }
//
//        Point2D.Double primaryTarget = assignedObjective;
//        Point2D.Double secondaryTarget = primaryTarget.equals(targetSiteA) ? targetSiteB : targetSiteA;
//
//        // 确保 secondaryTarget 不为 null
//        if (secondaryTarget == null) {
//            secondaryTarget = primaryTarget.equals(targetSiteA) ? targetSiteB : targetSiteA;
//            if (secondaryTarget == null) {
//                secondaryTarget = new Point2D.Double(gameState.width / 2.0, gameState.height / 2.0);
//            }
//        }
//
//        boolean pathFound = false;
//
//        if (currentPath == null || currentPath.isEmpty()) {
//            // 60%概率尝试走侧翼
//            if (rand.nextDouble() < 0.6) {
//                Point2D.Double flankPoint = calculateFlankPoint(primaryTarget);
//                if (flankPoint != null && setNewPath(flankPoint)) {
//                    pathFound = true;
//                }
//            }
//
//            if (!pathFound) {
//                if (setNewPath(primaryTarget)) pathFound = true;
//            }
//
//            if (!pathFound) {
//                if (setNewPath(secondaryTarget)) pathFound = true;
//            }
//
//            if (!pathFound) {
//                logger.accept(self.name + " 无法找到通往包点的路径");
//                currentState = AIState.PATROLLING;
//            }
//        }
//    }
//    /**
//     * 在包点内进行防御性巡逻
//     */
//    private void defendInBombSite() {
//        if (currentPath == null || currentPath.isEmpty()) {
//            // 在包点内找一个防守位置
//            Point2D.Double defendPos = findDefensivePositionInSite();
//            if (defendPos != null) {
//                setNewPath(defendPos);
//            } else {
//                // 如果没有好的防守位置，就在当前位置附近小范围移动
//                patrolNearPoint(self.position, 80);
//            }
//        }
//
//        // 继续索敌和攻击
//        if (target != null && hasLineOfSight(target.position)) {
//            attack();
//        }
//    }
//
//    /**
//     * 在包点内寻找好的防守位置
//     */
//    private Point2D.Double findDefensivePositionInSite() {
//        Rectangle currentSite = getCurrentBombSite();
//        if (currentSite == null) return null;
//
//        // 尝试几个预定义的防守位置
//        Point2D.Double[] defenseSpots = {
//                new Point2D.Double(currentSite.getCenterX(), currentSite.getCenterY()),
//                new Point2D.Double(currentSite.getMinX() + 50, currentSite.getCenterY()),
//                new Point2D.Double(currentSite.getMaxX() - 50, currentSite.getCenterY()),
//                new Point2D.Double(currentSite.getCenterX(), currentSite.getMinY() + 50),
//                new Point2D.Double(currentSite.getCenterX(), currentSite.getMaxY() - 50)
//        };
//
//        // 找一个有掩体的位置
//        for (Point2D.Double spot : defenseSpots) {
//            if (hasCoverFromCommonApproach(spot)) {
//                return spot;
//            }
//        }
//
//        // 如果没有理想位置，返回包点中心
//        return new Point2D.Double(currentSite.getCenterX(), currentSite.getCenterY());
//    }
//
//    /**
//     * 获取当前所在的包点矩形
//     */
//    private Rectangle getCurrentBombSite() {
//        Rectangle siteA = gameState.getBombSiteA();
//        Rectangle siteB = gameState.getBombSiteB();
//
//        if (siteA != null && self.getBounds().intersects(siteA)) {
//            return siteA;
//        }
//        if (siteB != null && self.getBounds().intersects(siteB)) {
//            return siteB;
//        }
//        return null;
//    }
//
//    /**
//     * 这是一个优化后的防守逻辑版本。
//     * 主要解决了当一个包点队友全部阵亡后，另一个包点的队友（即使只有一人）也应该去回防的问题。
//     * AI会主动监测另一处包点的队友存活情况，而不是被动地等待人数失衡。
//     */
//    private void defendBombSite(GameState state) {
//        // 如果是CT，并且没有分配防守点，则分配一个（开局逻辑）。
//        if (self.team == Player.Team.CT && assignedObjective == null) {
//            long ctAtA = getDefendersAtSite(getCenter(state.getBombSiteA())); // 计算A点的防守人数。
//            long ctAtB = getDefendersAtSite(getCenter(state.getBombSiteB())); // 计算B点的防守人数。
//            assignedObjective = (ctAtA <= ctAtB) ? getCenter(state.getBombSiteA()) : getCenter(state.getBombSiteB()); // 去人少的那个点。
//        }
//
//        // CT方动态补防与回防逻辑。
//        if (self.team == Player.Team.CT && state.getRoundPhase() == GameState.RoundPhase.IN_PROGRESS && shouldRecalculatePath()) {
//            Point2D.Double siteACenter = getCenter(state.getBombSiteA()); // A点中心。
//            Point2D.Double siteBCenter = getCenter(state.getBombSiteB()); // B点中心。
//
//            long defendersAtA = getDefendersAtSite(siteACenter); // A点防守人数。
//            long defendersAtB = getDefendersAtSite(siteBCenter); // B点防守人数。
//
//            // 紧急回防：检查另一个包点是否因队友阵亡而失守。
//            // 如果AI被分配防守B点，但AI检测到A点已经没有任何队友，必须立即去A点回防。
//            if (assignedObjective.equals(siteBCenter) && defendersAtA == 0 && defendersAtB > 0) {
//                assignedObjective = siteACenter;
//                currentState = AIState.ROTATING;
//                setNewPath(assignedObjective);
//                System.out.println(self.name + ": 检测到A点队友全部阵亡，从B点前往回防！");
//                return;
//            }
//            // 同理，如果AI被分配防守A点，但检测到B点失守，立即去B点回防。
//            if (assignedObjective.equals(siteACenter) && defendersAtB == 0 && defendersAtA > 0) {
//                assignedObjective = siteBCenter;
//                currentState = AIState.ROTATING;
//                setNewPath(assignedObjective);
//                System.out.println(self.name + ": 检测到B点队友全部阵亡，从A点前往回防！");
//                return;
//            }
//
//            // 人数再平衡逻辑（作为次级策略，防止开局站位过于集中）
//            // 如果我在A点，且A点人数比B点多至少2人，我去B点平衡人数。
//            if (assignedObjective.equals(siteACenter) && defendersAtA - defendersAtB >= 2) {
//                assignedObjective = siteBCenter;
//                currentState = AIState.ROTATING;
//                setNewPath(assignedObjective);
//                System.out.println(self.name + ": A点人数过多，换防B点。");
//                return;
//            }
//            // 如果我在B点，且B点人数比A点多至少2人，我去A点平衡人数。
//            if (assignedObjective.equals(siteBCenter) && defendersAtB - defendersAtA >= 2) {
//                assignedObjective = siteACenter;
//                currentState = AIState.ROTATING;
//                setNewPath(assignedObjective);
//                System.out.println(self.name + ": B点人数过多，换防A点。");
//                return;
//            }
//        }
//
//        // --- 移动和巡逻 ---
//        currentState = AIState.DEFENDING_SITE;
//        if (currentPath == null || currentPath.isEmpty()) {
//            if (state.isBombPlanted()) {
//                if (self.position.distance(state.getBombPosition()) > 250) {
//                    setNewPath(state.getBombPosition());
//                } else {
//                    patrolNearPoint(state.getBombPosition(), 200);
//                }
//            } else if (self.team == Player.Team.CT) {
//                if(assignedObjective != null && self.position.distance(assignedObjective) > 100) {
//                    setNewPath(assignedObjective);
//                } else if (assignedObjective != null) {
//                    patrolNearPoint(assignedObjective, 150);
//                }
//            } else {
//                patrol();
//            }
//        }
//    }
//
//
//    // 获取在指定包点的防守人数。
//    private long getDefendersAtSite(Point2D.Double siteCenter) {
//        if (siteCenter == null) return 0; // 如果包点不存在，返回0。
//        return this.knownPlayers.stream() // 遍历所有已知玩家。
//                .filter(p -> p.isAI && p.team == Player.Team.CT && p.isAlive() && p.aiController != null // 筛选出存活的CT方AI。
//                        && p.aiController.assignedObjective != null // 且该AI有明确的目标点。
//                        && p.aiController.assignedObjective.equals(siteCenter)) // 且其目标点就是指定的包点。
//                .count(); // 统计符合条件的AI数量。
//    }
//
//    // 在一个点附近巡逻。
//    private void patrolNearPoint(Point2D.Double center, double radius) {
//        if (currentPath == null || currentPath.isEmpty()) { // 如果没有路径。
//            double patrolAngle = rand.nextDouble() * 2 * Math.PI; // 随机一个角度。
//            double patrolDist = rand.nextDouble() * radius; // 随机一个距离。
//            // 计算出巡逻目标点。
//            Point2D.Double patrolTarget = new Point2D.Double(
//                    center.x + Math.cos(patrolAngle) * patrolDist,
//                    center.y + Math.sin(patrolAngle) * patrolDist
//            );
//            setNewPath(patrolTarget); // 设置去该点的路径。
//        }
//    }
//
//    // CT回防包点。
//    /**
//     * CT回防并拆除C4。此版本已重构为“人类客户端”模式。
//     * 一旦靠近炸弹，AI将强制开始拆除并锁定自身，直到拆除成功、被移动出范围或回合结束。
//     * @param state 游戏状态对象
//     */
//        private void retakeSite (GameState state){
//            // 检查是否已在炸弹的交互范围内
//            if (state.getBombPosition() != null && self.position.distance(state.getBombPosition()) < 50) {
//
//                //  不再检查附近是否有敌人，直接进入拆包状态
//
//                // 停止所有移动和射击意图
//                clearPath();
//                self.isShooting = false;
//
//                // 切换到拆包锁定状态，并设置交互意图
//                currentState = AIState.DEFUSING_BOMB;
//                self.isInteracting = true; // 设置交互意图，由服务器主线程处理
//                self.interactionStartTime = System.currentTimeMillis(); // AI自己记录开始时间
//
//                logger.accept(self.name + " [DEFUSE_INTENT_SET] 已在炸弹点，成功设置拆包意图。");
//            } else {
//                // 如果离包还远，则继续向炸弹移动
//                currentState = AIState.MOVING_TO_SITE;
//                if (shouldRecalculatePath()) {
//                    setNewPath(state.getBombPosition());
//                }
//            }
//        }
//
//
//    //[旧版] 检查附近是否有可见的敌人。
//    private boolean isEnemyVisibleNearby() {
//        return this.knownPlayers.stream() // 遍历所有已知玩家。
//                .anyMatch(p -> p.team != self.team && p.isAlive() // 筛选出存活的敌人。
//                        && self.position.distance(p.position) < 1000 // 且距离在1000以内。
//                        && hasLineOfSight(p.position)); // 且有清晰的视线。
//    }
//
//
//    // 获取一个矩形的中心点。
//    private Point2D.Double getCenter(Rectangle rect) {
//        if (rect == null) return new Point2D.Double(gameState.width / 2.0, gameState.height / 2.0); // 如果矩形为空，返回地图中心。
//        return new Point2D.Double(rect.getCenterX(), rect.getCenterY()); // 返回矩形的中心坐标。
//    }
//
//
//    /** 检查AI是否卡住 */
//    private boolean checkIfStuck() {
//        long currentTime = System.currentTimeMillis();
//        if (currentTime - lastStuckCheckTime > STUCK_CHECK_INTERVAL) {
//
//            // 检查点：如果AI正在进行互动（下包/拆包），则直接返回 false。
//            if (self.isInteracting || currentState == AIState.PLANTING_BOMB || currentState == AIState.DEFUSING_BOMB) {
//                // 即使不移动，也是任务需要，不属于卡住
//                stuckCount = 0; // 重置计数
//                return false;
//            }
//
//            // 如果在间隔时间内移动距离很小，且不是在射击或交互，也不是空闲。
//            if (self.position.distance(lastPositionForStuckCheck) < MIN_MOVEMENT_FOR_STUCK
//                    // 【注意】：你之前的代码中已经排除了 !self.isInteracting，但最好在顶部明确任务状态。
//                    && !self.isShooting
//                    && currentState != AIState.IDLE)
//            {
//                stuckCount++;
//                if (stuckCount > 2) {
//                    return true;
//                }
//            } else {
//                stuckCount = 0;
//            }
//            this.lastPositionForStuckCheck = (Point2D.Double) self.position.clone();
//            this.lastStuckCheckTime = currentTime;
//        }
//        return false;
//    }
//
//    /** 执行挣脱卡死的动作 */
//    private void performUnstuckManeuver() {
//        performUnstuckManeuver(250); // 默认持续250ms
//    }
//
//    /**
//     * 执行挣脱卡死的动作 (可指定持续时间)
//     * @param durationMs 挣脱动作的持续毫秒数
//     */
//    private void performUnstuckManeuver(long durationMs) {
//        currentState = AIState.UNSTUCKING;
////        logger.accept(self.name + " is stuck! Forcing unstuck maneuver.");
//
//        // ========================= [核心修复：强制清除所有战斗/移动状态] =========================
//        // 解释：AI卡住通常是因为它执着于攻击一个目标或前往一个点。
//        // 因此，脱困的第一步就是让它“忘记”它正在做的事。
//
//        clearPath(); // 清除寻路路径，停止移动指令
//        self.isShooting = false; // 立即停止开火
//        this.target = null; //  清除当前锁定的目标，这是打断“交战中”状态的核心
//        this.lastKnownPosition = null; // 清除最后已知位置，打断“追击”状态
//        // ======================================================================================
//
//        // 施加一个随机方向的力，进行物理移动
//        double unstuckAngle = rand.nextDouble() * 2 * Math.PI;
//        self.ax = Math.cos(unstuckAngle);
//        self.ay = Math.sin(unstuckAngle);
//
//        // 标记挣脱状态的持续时间
//        unstuckUntil = System.currentTimeMillis() + durationMs;
//
//        // 重置所有与卡住相关的计数器
//        stuckCount = 0;
//        isCollidingWithWall = false;
//        wallCollisionStartTime = 0;
//    }
//
//
//    /** 僵尸的更新逻辑 */
//    // 文件: AIController.java
//
//    private void updateZombie(List<Player> allCharacters) {
//        findTarget(allCharacters); // 寻找最近的人类玩家作为目标
//
//        if (target != null && target.isAlive()) {
//            //  无论距离多远，始终保持冲向目标的移动意图
//            if (shouldRecalculatePath()) {
//                setNewPath(target.position);
//            }
//
//            //   在持续移动的同时，额外判断是否可以攻击
//            if (self.position.distance(target.position) < Player.SIZE * 1.5) {
//                meleeAttack(target); // 调用我们修改过的、不会让AI停下的meleeAttack
//            }
//
//        } else {
//            // 如果没有目标，僵尸进入巡逻状态
//            patrol();
//        }
//
//        // 无论是在追击还是巡逻，最后都执行移动
//        followPath();
//    }
//
//    /** 僵尸的近战攻击 */
//    private void meleeAttack(Player target) {
//        // 攻击时不停止移动。
////        self.ax = 0;
////        self.ay = 0;
//        if (System.currentTimeMillis() - lastMeleeAttackTime > MELEE_COOLDOWN) { // 如果攻击冷却好了。
//            gameState.requestMelee(self, target, MELEE_DAMAGE); // 请求服务器处理近战攻击。
//            lastMeleeAttackTime = System.currentTimeMillis(); // 更新上次攻击时间。
//        }
//    }
//
//    /** 寻找最合适的目标 */
//    private void findTarget(List<Player> allCharacters) {
//        Player oldTarget = this.target; // 保存旧目标，用于判断目标是否切换。
//
//        // 筛选出所有活着的敌人作为潜在目标。
//        List<Player> potentialTargets = allCharacters.stream()
//                .filter(p -> p.isAlive()) // 必须是活着的
//
//                .filter(p -> {
//                    if (self.team == Player.Team.CT && gameState.getGameMode() == GameMode.ZOMBIE_MODE) {
//                        return p.team == Player.Team.ZOMBIE;
//                    } else {
//                        // 在 DEMO/TDM 等其他模式下，使用标准的队伍差异判断
//                        return p.team != self.team;
//                    }
//                })
//                .collect(Collectors.toList());
//
//        if (potentialTargets.isEmpty()) { // 如果没有潜在目标。
//            this.target = null; // 清空目标。
//            shotsFiredAtCurrentTarget = 0; // 重置射击计数
//            return; // 返回。
//        }
//
//        // 将潜在目标分为“可见”和“不可见”两组。
//        Map<Boolean, List<Player>> partitionedTargets = potentialTargets.stream()
//                .collect(Collectors.partitioningBy(p -> hasLineOfSight(p.position)));
//
//        List<Player> visibleTargets = partitionedTargets.get(true); // 可见目标列表。
//        List<Player> hiddenTargets = partitionedTargets.get(false); // 不可见目标列表。
//
//        Player newTarget = null; // 初始化新目标。
//        if (!visibleTargets.isEmpty()) { // 如果有可见目标。
//            // 选择距离最近的可见目标。
//            newTarget = visibleTargets.stream()
//                    .min(Comparator.comparingDouble(p -> self.position.distanceSq(p.position)))
//                    .orElse(null);
//        } else { // 如果没有可见目标。
//            // 尝试在不可见目标中找到最近500ms内开过火的。
//            Optional<Player> shootingHiddenTarget = hiddenTargets.stream()
//                    .filter(p -> System.currentTimeMillis() - p.lastShotTime < 500)
//                    .min(Comparator.comparingDouble(p -> self.position.distanceSq(p.position)));
//
//            if (shootingHiddenTarget.isPresent()) { // 如果找到了正在开火的隐藏目标。
//                newTarget = shootingHiddenTarget.get();
//                // 记录枪声时，同时记录位置和当前时间戳
//                this.lastHeardSoundCue = new SoundCue(
//                        new Point2D.Double(newTarget.position.x, newTarget.position.y),
//                        System.currentTimeMillis()
//                );
//            } else { // 如果没有正在开火的隐藏目标。
//                // 选择距离最近的隐藏目标。
//                newTarget = hiddenTargets.stream()
//                        .min(Comparator.comparingDouble(p -> self.position.distanceSq(p.position)))
//                        .orElse(null);
//            }
//        }
//
//        if (oldTarget != newTarget) { // 如果目标发生了切换。
//            this.targetAcquiredTime = System.currentTimeMillis(); // 更新锁定目标的时间戳。
//            this.shotsFiredAtCurrentTarget = 0; // 重置对新目标的射击计数！
//
//
//            this.self.shootTimeIndex = 0; // 这是一次全新的压枪！
//
//        }
//
//        this.target = newTarget; // 更新当前目标。
//
//        if (target != null) { // 如果最终找到了目标。
//            lastKnownPosition = new Point2D.Double(target.position.x, target.position.y); // 更新敌人最后出现的位置。
//        }
//    }
//
//    /** 巡逻行为：随机选择一个目标点并寻路过去 */
//    private void patrol() {
//        currentState = AIState.PATROLLING;
//        // 检查是否需要重新计算路径
//        if (currentPath == null || currentPath.isEmpty() || shouldRecalculatePath()) {
//            Point2D.Double patrolTarget = null;
//
//            // ======================= [核心修改：爆破模式优先使用粗点] =======================
//            if (gameState.getGameMode() == GameMode.DEMOLITION && gameState.hasWaypointGraph()) {
//
//                // 1. 确保缓存已加载
//                if (waypointGraphCache == null) {
//                    waypointGraphCache = gameState.getWaypointGraph();
//                }
//
//                // 2. 从已加载的 Waypoint 列表中随机选择一个节点作为目标
//                if (waypointGraphCache != null && !waypointGraphCache.isEmpty()) {
//                    WaypointNode randomWaypoint = waypointGraphCache.get(rand.nextInt(waypointGraphCache.size()));
//                    patrolTarget = randomWaypoint.position;
//                    logger.accept(self.name + " (DEMO Patrol): 选择 Waypoint " + formatObjective(patrolTarget));
//                }
//            }
//            // ======================= [爆破模式优先使用粗点 - 结束] =======================
//
//            // 如果上述逻辑没有分配目标 (例如不是 DEMO 模式或没有 Waypoint)，则退回原有的随机点逻辑
//            if (patrolTarget == null) {
//                int attempts = 0;
//                final int MAX_ATTEMPTS = 5; // 最多尝试5次寻找可达点
//
//                // 循环尝试寻找一个可达的目标点
//                while (patrolTarget == null && attempts < MAX_ATTEMPTS) {
//                    // 随机选择一个点
//                    Point2D.Double candidate = new Point2D.Double(rand.nextInt(gameState.width), rand.nextInt(gameState.height));
//
//                    // 检查寻路器是否认为这个点是可走的
//                    if (pathfinder.isWalkable(candidate)) {
//                        patrolTarget = candidate;
//                    }
//                    attempts++;
//                }
//            }
//
//            // 如果找到了可达点，才尝试设置路径
//            if (patrolTarget != null) {
//                setNewPath(patrolTarget);
//            } else {
//                // 如果5次都找不到可达点，则将AI状态暂时设置为IDLE，等待下一帧重试。
//                currentState = AIState.IDLE;
//            }
//        }
//    }
//
//    /** 攻击行为 */
//    private void attack() {
//        // --- 基础安全检查 ---
//        if (target == null || !target.isAlive() || !hasLineOfSight(target.position)) {
//            currentState = AIState.HUNTING;
//            self.isShooting = false;
//            clearPath();
//            return;
//        }
//
//        //  --- 反应时间检查 ---
//        if (System.currentTimeMillis() - targetAcquiredTime < difficulty.reactionTimeMs) {
//            // 在反应时间内，AI会瞄准但不开火
//            self.isShooting = false;
//            return;
//        }
//
//        // --- 获取决策所需的核心信息 ---
//        Weapon wep = self.getCurrentWeapon();
//        if (wep == null) return; // 没有武器则无法攻击
//
//        Weapon.WeaponType type = wep.getWeaponType();
//        double distanceToTarget = self.position.distance(target.position);
//
//        // --- 根据武器类型和距离决定行动 ---
//
//        // 策略A：使用长枪 (步枪/狙击枪)
//        if (type.isLongRange()) {
//
//            // 【检查是否处于回防或进攻模式
//            boolean isRetakingOrAttacking = currentState == AIState.ROTATING ||
//                    currentState == AIState.DEFUSING_BOMB ||
//                    currentState == AIState.MOVING_TO_SITE ||
//                    tacticRole == TacticRole.ROTATOR; // 增加对指挥官指令的检查
//
//            // 如果不是在紧急回防，才执行风筝/后退逻辑
////            if (!isRetakingOrAttacking) {
////                // 僵尸模式：如果距离太近 (<250)，则需要后退拉开距离
////                if (distanceToTarget < 250 && gameMode.m) {
////                    Point2D.Double retreatPos = new Point2D.Double(
////                            self.position.x - (target.position.x - self.position.x),
////                            self.position.y - (target.position.y - self.position.y)
////                    );
////                    if (shouldRecalculatePath()) setNewPath(retreatPos);
////                } else {
////                    // 距离合适，站桩输出
////                    clearPath();
////                }
////            } else {
////                //在回防时，取消所有风筝，强行靠近目标！
////                if (distanceToTarget > 150) { // 如果太远，还是得靠近
////                    if (shouldRecalculatePath()) setNewPath(target.position);
////                } else {
////                    clearPath(); // 距离够近，站桩对射
////                }
////            }
//
//            // 对于长枪，只要看到敌人就在有效射程内，直接开火
//            aimAndShoot(target);
//            // 策略B：使用短枪 (冲锋枪/霰弹枪)
//        } else if (type.isShortRange()) {
//            // 如果距离太远 (>400)，则优先靠近目标，不开火！
//            if (distanceToTarget > 400) {
//                self.isShooting = false; // <--- 核心！强制设置为不开火
//                if (shouldRecalculatePath()) {
//                    // 不再是直愣愣地冲向敌人，而是去敌人侧翼的一个点
//                    setNewPath(calculateFlankPoint(target.position));
//                }
//            } else {
//                // 距离合适 (<400)，进入近距离战斗模式：左右横移并开火
//                long currentTime = System.currentTimeMillis();
//                if (currentTime - lastStrafeDirectionChange > STRAFE_DIRECTION_CHANGE_INTERVAL + rand.nextInt(1000)) {
//                    strafingLeft = rand.nextBoolean();
//                    lastStrafeDirectionChange = currentTime;
//                }
//
//                double angleToTarget = Math.atan2(target.position.y - self.position.y, target.position.x - self.position.x);
//                double strafeAngle = angleToTarget + (strafingLeft ? -Math.PI / 2 : Math.PI / 2);
//
//                self.ax = Math.cos(strafeAngle);
//                self.ay = Math.sin(strafeAngle);
//
//                this.currentPath = null;
//                this.currentPathIndex = 0;
//
//                // 在合适的距离内，才进行开火
//                aimAndShoot(target);
//            }
//
//            // 策略C：其他武器（例如手枪），站桩输出
//        } else {
//            clearPath();
//            aimAndShoot(target);
//        }
//
//        // --- 统一的射击冷却逻辑 ---
//        // 如果AI正在冷却，则不能开火
//        if (System.currentTimeMillis() < burstCooldownUntil) {
//            self.isShooting = false;
//            return; // 直接返回，确保本帧不开火
//        }
//
//        // 如果AI正在开火，检查是否达到了它的最大连射上限
//        if (self.isShooting && shotsFiredAtCurrentTarget >= difficulty.maxBurstShots) {
//            self.isShooting = false; // 停止射击
//
//            this.self.shootTimeIndex = 0; // 为下一次连射做好准备！
//
//            shotsFiredAtCurrentTarget = 0; // 重置连射计数器
//            burstCooldownUntil = System.currentTimeMillis() + (long)(300 + rand.nextInt(400));
//        }
//    }
//
//
//    /**
//     * 瞄准并向一个玩家射击，新增AI压枪逻辑
//     */
//    private void aimAndShoot(Player targetPlayer) {
//        if (targetPlayer == null) return;
//
//        // ---  基础瞄准位置计算 ---
//        Point2D.Double aimPosition;
//        if (difficulty.ordinal() <= AIDifficulty.HARD.ordinal()) {
//            aimPosition = new Point2D.Double(
//                    targetPlayer.position.x + (rand.nextDouble() - 0.5) * Player.SIZE * 0.7,
//                    targetPlayer.position.y + (rand.nextDouble() - 0.5) * Player.SIZE * 0.7
//            );
//        } else {
//            aimPosition = targetPlayer.position;
//        }
//
//        // 基础瞄准角度的定义（之前缺失的部分）
//        double baseAngle = Math.atan2(aimPosition.y - self.position.y, aimPosition.x - self.position.x);
//        // 检查武器是否有固定弹道
//        if (self.weaponFixedRecoil != null) {
//
//            //  使用 shootTimeIndex[] 来“预测”下一发子弹的后坐力
//            int nextRecoilIndex = self.shootTimeIndex % self.weaponFixedRecoil.length;
//
//            // 获取即将到来的、需要被补偿的后坐力角度
//            double recoilToExpect = self.weaponFixedRecoil[nextRecoilIndex];
//
//            // 根据AI难度，计算实际的补偿角度
//            double compensationAngle = recoilToExpect * difficulty.recoilControlFactor;
//
//            // 提前向下移动准星以抵消后坐力
//            baseAngle -= compensationAngle;
//        }
//
//
//        // --- 应用随机散射和失误逻辑 ---
//        double finalAngle = baseAngle + ((rand.nextDouble() - 0.5) * self.currentSpread * difficulty.aimErrorMultiplier);
//
//        boolean wasIntentionalMiss = false;
//        final int SHOOT_TOTAL_NEED_NUM = 3;
//
//        if (difficulty.ordinal() <= AIDifficulty.HARD.ordinal()) {
//            if (shotsFiredAtCurrentTarget == 0) {
//                double missOffset = 0.035 + rand.nextDouble() * 0.052;
//                finalAngle += (rand.nextBoolean() ? 1 : -1) * missOffset;
//                wasIntentionalMiss = true;
//            } else if (shotsFiredAtCurrentTarget <= SHOOT_TOTAL_NEED_NUM) {
//                double dialingInError = (rand.nextDouble() - 0.5) * self.currentSpread * (difficulty.aimErrorMultiplier * 2.0);
//                finalAngle += dialingInError;
//            }
//        }
//
//        if (!wasIntentionalMiss && rand.nextDouble() < difficulty.missChance) {
//            finalAngle += (rand.nextBoolean() ? 1 : -1) * (0.5 + rand.nextDouble());
//        }
//
//        // ---  执行射击 ---
//        this.targetAngle = finalAngle;
//        self.isShooting = true;
//        gameState.requestShoot(self);
//        shotsFiredAtCurrentTarget++;
//    }
//
//    /**
//     * 瞄准并向指定坐标点射击，仅应用基础误差（用于穿墙等特殊射击情况）
//     * 此方法用于AI的精确射击，误差较小，适合穿墙预判等场景
//     *
//     * @param aimPosition 目标坐标点，为null时直接返回
//     */
//    private void aimAndShoot(Point2D.Double aimPosition) {
//        if (aimPosition == null) return;
//
//        // 计算基础瞄准角度：目标位置与自身位置的向量角度
//        double baseAngle = Math.atan2(aimPosition.y - self.position.y, aimPosition.x - self.position.x);
//
//        // 计算瞄准误差：[-0.5, 0.5]的随机值 × 当前扩散系数 × 难度误差倍率
//        double aimError = (rand.nextDouble() - 0.5) * self.currentSpread * difficulty.aimErrorMultiplier;
//
//        // 最终目标角度 = 基础角度 + 误差修正
//        this.targetAngle = baseAngle + aimError;
//
//        // 设置射击状态并请求射击
//        self.isShooting = true;
//        gameState.requestShoot(self);
//    }
//
//    /**
//     * AI在换弹时采取的战术行为
//     * 根据游戏模式和武器类型选择不同的撤退策略：
//     * - 僵尸模式短武器：远离最近的僵尸
//     * - 其他情况：寻找掩体或直线撤退
//     */
//    private void handleReloading() {
//        // 进入掩护状态
//        currentState = AIState.TAKING_COVER;
//
//        // 如果已有路径且不需要重新计算，则保持当前路径
//        if ((currentPath != null && !currentPath.isEmpty()) || !shouldRecalculatePath()) {
//            return;
//        }
//
//        // === 僵尸模式特殊处理 ===
//        Weapon wep = self.getCurrentWeapon();
//        if (gameState.getGameMode() == GameMode.ZOMBIE_MODE && wep != null && wep.getWeaponType().isShortRange()) {
//            // 寻找最近的存活僵尸并远离它
//            knownPlayers.stream()
//                    .filter(p -> p.team == Player.Team.ZOMBIE && p.isAlive())
//                    .min(Comparator.comparingDouble(p -> self.position.distanceSq(p.position)))
//                    .ifPresent(nearestZombie -> {
//                        // 计算远离僵尸的方向向量
//                        double angleAway = Math.atan2(self.position.y - nearestZombie.position.y,
//                                self.position.x - nearestZombie.position.x);
//                        // 计算撤退位置：当前位置 + 远离方向 × 200像素
//                        Point2D.Double retreatPos = new Point2D.Double(
//                                self.position.x + Math.cos(angleAway) * 200,
//                                self.position.y + Math.sin(angleAway) * 200
//                        );
//                        setNewPath(retreatPos);
//                    });
//            return;
//        }
//
//        // === 标准模式撤退逻辑 ===
//        // 优先寻找有视野遮挡的掩体点
//        Point2D.Double coverPoint = findCover(lastKnownPosition);
//        if (coverPoint != null) {
//            setNewPath(coverPoint);
//        } else if (lastKnownPosition != null) {
//            // 找不到掩体时直线撤退：远离最后已知威胁位置
//            double angleAway = Math.atan2(self.position.y - lastKnownPosition.y,
//                    self.position.x - lastKnownPosition.x);
//            Point2D.Double retreatPos = new Point2D.Double(
//                    self.position.x + Math.cos(angleAway) * 150,  // 撤退150像素距离
//                    self.position.y + Math.sin(angleAway) * 150
//            );
//            setNewPath(retreatPos);
//        }
//        logger.accept(self.name + " 正在移动中换弹 (掩护已禁用)."); // 可选日志
//    }
//
//    /**
//     * 寻找一个相对于威胁位置的掩体点
//     * 采用随机采样策略，在周围8个方向寻找有视野遮挡的安全位置
//     *
//     * @param threatPosition 威胁源坐标位置
//     * @return 找到的掩体点坐标，如未找到则返回null
//     */
//    Point2D.Double findCover(Point2D.Double threatPosition) {
//        if (threatPosition == null || pathfinder == null) return null;
//
//        // 尝试8个随机方向寻找掩体
//        for (int i = 0; i < 8; i++) {
//            // 随机角度和距离：150-300像素范围内
//            double angle = rand.nextDouble() * 2 * Math.PI;
//            double checkRadius = 150 + rand.nextDouble() * 150;
//
//            // 计算候选点坐标
//            Point2D.Double candidatePoint = new Point2D.Double(
//                    self.position.x + Math.cos(angle) * checkRadius,
//                    self.position.y + Math.sin(angle) * checkRadius
//            );
//
//            // 验证候选点：在地图边界内、可行走、且从威胁位置无直接视野
//            if (gameState.isInBounds(candidatePoint) &&
//                    pathfinder.isWalkable(candidatePoint) &&
//                    !hasLineOfSightToPointFromPoint(candidatePoint, threatPosition)) {
//                return candidatePoint;
//            }
//        }
//        return null;
//    }
//
//    /**
//     * 战术移动：根据武器类型和距离，决定是后退、跑打还是站桩
//     */
////    private void tacticalReposition() {
////        Weapon wep = self.getCurrentWeapon();
////        if (target == null || wep == null) return;
////        Weapon.WeaponType type = wep.getWeaponType();
////        double distanceToTarget = self.position.distance(target.position);
////
////        if (type.isLongRange()) {
////            if (distanceToTarget < 250) {
////                Point2D.Double retreatPos = new Point2D.Double(
////                        self.position.x - (target.position.x - self.position.x),
////                        self.position.y - (target.position.y - self.position.y)
////                );
////                if (shouldRecalculatePath()) setNewPath(retreatPos);
////            } else {
////                clearPath();
////            }
////        } else if (type.isShortRange()) {
////            if (distanceToTarget > 400) {
////                if (shouldRecalculatePath()) setNewPath(target.position);
////            } else {
////                long currentTime = System.currentTimeMillis();
////                if (currentTime - lastStrafeDirectionChange > STRAFE_DIRECTION_CHANGE_INTERVAL + rand.nextInt(1000)) {
////                    strafingLeft = rand.nextBoolean();
////                    lastStrafeDirectionChange = currentTime;
////                }
////
////                double angleToTarget = Math.atan2(target.position.y - self.position.y, target.position.x - self.position.x);
////                double strafeAngle = angleToTarget + (strafingLeft ? -Math.PI / 2 : Math.PI / 2);
////
////                self.ax = Math.cos(strafeAngle);
////                self.ay = Math.sin(strafeAngle);
////
////                this.currentPath = null;
////                this.currentPathIndex = 0;
////            }
////        } else {
////            clearPath();
////        }
////    }
//
//    /** 追猎行为 */
//    private void hunt() {
//        self.isShooting = false;
//        if (attemptPenetrationShot()) {
//            return;
//        }
//
//        if (lastKnownPosition == null) {
//            currentState = AIState.PATROLLING;
//            return;
//        }
//
//        if (self.position.distance(lastKnownPosition) < Player.SIZE) {
//            currentState = AIState.PATROLLING;
//            return;
//        }
//
//        if (shouldRecalculatePath()) {
//            double flankRoll = rand.nextDouble();
//            Weapon wep = self.getCurrentWeapon();
//            double requiredRoll = wep != null && wep.getWeaponType().isShortRange() ? 0.6 : 0.2;
//
//            if (flankRoll < requiredRoll * difficulty.flankChance) {
//                setNewPath(calculateFlankPoint(lastKnownPosition));
//            } else {
//                setNewPath(lastKnownPosition);
//            }
//        }
//    }
//
//
//    /** 尝试进行穿墙射击 */
//    private boolean attemptPenetrationShot() {
//        Weapon wep = self.getCurrentWeapon();
//        if (lastHeardSoundCue == null || wep == null) {
//            return false;
//        }
//
//        // 如果距离太远，则判定枪声无效，忘掉它
//        if (self.position.distance(lastHeardSoundCue.position()) > 1000) {
//            lastHeardSoundCue = null;
//            return false;
//        }
//
//        // 随机数判定，难度越高越想穿
//        if (rand.nextDouble() > difficulty.penetrationChance) {
//            return false;
//        }
//
//
//        // 为地狱人机开启“外挂”，豁免精度检查
//        boolean isAccuracyGoodEnough = true; // 默认精度足够
//        // 只有非地狱难度的AI，才需要检查自己的射击精度
//        if (difficulty != AIDifficulty.HELL) {
//            if (wep.getWeaponType().isLongRange() && self.continueShotingSpread >= 0.15 * wep.maxSpread) {
//                isAccuracyGoodEnough = false;
//            } else if (wep.getWeaponType().isShortRange() && self.currentSpread >= 0.22 * wep.maxSpread) {
//                isAccuracyGoodEnough = false;
//            }
//        }
//
//        // 如果精度不够好
//        if (!isAccuracyGoodEnough) {
//            clearPath(); // 停下脚步，尝试恢复准星
//            // 不要忘记枪声位置
//            return false;
//        }
//        // --- 后续的物理穿透计算保持不变 ---
//        Line2D.Double ray = new Line2D.Double(self.position, lastHeardSoundCue.position());
//        double totalThickness = 0;
//
//        List<Shape> intersectingWalls = new ArrayList<>();
//        for(Shape obs : pathfinder.getObstacles()) {
//            if(obs.intersects(ray.getBounds2D()) && GameState.getLineShapeIntersections(ray, obs) != null) {
//                intersectingWalls.add(obs);
//            }
//        }
//        intersectingWalls.sort(Comparator.comparingDouble(s -> self.position.distance(s.getBounds2D().getCenterX(), s.getBounds2D().getCenterY())));
//
//        for(Shape wall : intersectingWalls) {
//            Point2D.Double[] intersections = GameState.getLineShapeIntersections(ray, wall);
//            if (intersections != null && intersections.length >= 2) {
//                totalThickness += intersections[0].distance(intersections[1]);
//            }
//        }
//
//        double initialPenetrationPower = wep.penetrationPower;
//
//        if (initialPenetrationPower > totalThickness) {
//            double remainingPower = initialPenetrationPower - totalThickness;
//            double damageMultiplier = remainingPower / initialPenetrationPower;
//
//            if (damageMultiplier >= 0.4) {
//                aimAndShoot(lastHeardSoundCue.position()); // 射击！
//                clearPath();
//                lastHeardSoundCue = null; // 只有在成功射击后，才忘记位置
//                return true;
//            }
//        }
//
//        // 如果只是因为墙太厚穿不过，先不要忘记位置，也许换个角度还有机会
//        // lastHeardSoundCue.position() = null; // 我们把这行代码注释/删掉了
//        return false;
//    }
//
//    private long lastPathRecalculationAttemptTime = 0;
//    private static final long PATH_RECALCULATION_COOLDOWN = 3000; // 3秒冷却
//
//
//    /**
//     *  跟随路径 - 处理粗细路径的衔接
//     */
//    private void followPath() {
//        // 如果连细算路径都没有，直接返回 (可能正在等待计算，或寻路失败)
//        if (currentPath == null || currentPath.isEmpty()) {
//
//            // 检查AI是否应该在移动，并且是否可以重新计算
//            if (currentState == AIState.MOVING_TO_SITE && assignedObjective != null && shouldRecalculatePath()) {
//
//                logger.accept(self.name + " (Top-Level): 路径为null，尝试智能恢复/降级...");
//                // 旧代码 (导致循环):
//                // logger.accept(self.name + " 路径丢失，尝试重新寻路到 " + formatObjective(assignedObjective));
//                // setNewPath(assignedObjective);
//
//                resumeHpaPathfinding();
//            }
//            return;
//        }
//
//        // 获取当前细算路径的目标节点和世界坐标
//        Node targetNode = currentPath.get(currentPathIndex);
//        Point2D.Double targetPos = pathfinder.nodeToWorld(targetNode);
//
//        // 检查是否到达当前细算路径的节点
//        // 阈值可以稍微宽松一点，避免因为浮点误差卡住
//        if (self.position.distanceSq(targetPos) < (Player.SIZE * 0.8) * (Player.SIZE * 0.8) ) {
//            currentPathIndex++; // 移动到细算路径的下一个节点
//
//            // 检查细算路径是否已经走完
//            if (currentPathIndex >= currentPath.size()) {
//                // === [HPA* 核心衔接点] ===
//                //  刚刚走完了一段细算路径 (可能是HPA*的一段，也可能是纯网格寻路的终点)
//
//                //  检查 是否正处于一个 HPA* 的过程中 (即 currentCoarsePath 有内容)
//                if (currentCoarsePath != null && !currentCoarsePath.isEmpty()) {
//
//                    // 在推进索引 *之前*，记录下我们刚刚到达的这个粗点
//                    if (currentCoarsePathIndex < currentCoarsePath.size()) { // 确保索引有效
//                        this.lastPassedWaypointNode = currentCoarsePath.get(currentCoarsePathIndex);
//                        this.lastPassedWaypointTime = System.currentTimeMillis();
//                        // logger.accept(String.format("[%s] Just passed Waypoint %s.", self.name, formatObjective(lastPassedWaypointNode.position))); // 可选日志
//                    }
//
//                    //   推进粗算路径的索引
//                    currentCoarsePathIndex++;
//
//                    //  检查粗算路径是否还有下一个目标点
//                    if (currentCoarsePathIndex < currentCoarsePath.size()) {
//                        // 还有下一个粗算目标！
//                        WaypointNode nextWaypoint = currentCoarsePath.get(currentCoarsePathIndex);
//                        Point2D.Double nextLegTargetPos = nextWaypoint.position;
//
//                        logger.accept(self.name + " HPA* 下一段: " + formatObjective(nextLegTargetPos) + " (粗索引 " + currentCoarsePathIndex + "/" + (currentCoarsePath.size()-1) + ")");
//                        // 计算从当前位置到下一个粗算点的精确路径
//                        List<GameState.FirePatch> firePatches = (gameState != null) ? gameState.getFirePatches() : new ArrayList<>();
//                        this.currentPath = pathfinder.findPath(self, self.position, nextLegTargetPos, this.knownPlayers, firePatches);
//                        this.currentPathIndex = 0; // 重置细算路径的索引
//
//                        //  处理细算失败的情况
//                        if (this.currentPath == null || this.currentPath.isEmpty()) {
//                            logger.accept(self.name + " HPA* 失败: 细算下一段路径失败！停止移动。");
//                            clearPath(); // 清理路径，AI 将停下
//
//                            if (currentState == AIState.MOVING_TO_SITE && assignedObjective != null) {
//                                // 旧代码 (导致循环):
//                                // setNewPath(assignedObjective);
//
//                                // 新代码 (调用智能恢复逻辑):
//                                logger.accept(self.name + " (HPA-Leg): 衔接失败，尝试智能恢复/降级...");
//                                resumeHpaPathfinding();
//                            } else {
//                                currentState = AIState.PATROLLING; // 进入巡逻状态
//                            }
//                        }
//                        return; // 无论成功失败，都需要返回，移动将在下一帧根据新的 currentPath 进行
//                    } else {
//                        // 粗算路径已经走完 (到达了最后一个 targetPos 对应的临时 Waypoint)
//                        logger.accept(self.name + " HPA* 完成: 已到达最终目标 " + formatObjective(targetPos)); // targetPos 是细路径最后一点，接近最终目标
//                        clearPath(); // 清理所有路径，寻路结束
//                        return;
//                    }
//                } else {
//                    // 如果 currentCoarsePath 为空，说明这是一个纯网格寻路，现在已经走完
//                    logger.accept(self.name + " 网格寻路完成: 已到达目标 " + formatObjective(targetPos));
//                    clearPath(); // 清理路径，寻路结束
//                    return;
//                }
//                // === [HPA* 衔接结束] ===
//            }
//
//            // 如果细算路径还没走完，获取下一个节点的目标坐标
//            targetNode = currentPath.get(currentPathIndex);
//            targetPos = pathfinder.nodeToWorld(targetNode);
//        }
//
//        // 计算朝向细算路径下一个节点的移动方向
//        double moveAngle = Math.atan2(targetPos.y - self.position.y, targetPos.x - self.position.x);
//        self.ax = Math.cos(moveAngle);
//        self.ay = Math.sin(moveAngle);
//
//        // --- 看路逻辑  ---
//        // 检查AI是否正在“主动交战”或“预瞄”
//        boolean hasHigherPriorityAim = (
//                currentState == AIState.ATTACKING ||                  // 1. 正在主动攻击
//                        currentState == AIState.BLIND_RETALIATION ||  // 2. 正在盲射反击
//                        (lastHeardSoundCue != null) ||                // 3. 正在预瞄枪声
//                        (target != null && hasLineOfSight(target.position)) // 4. 正在瞄准一个*可见*的目标
//        );
//
//        if (!hasHigherPriorityAim) {
//            // 在所有其他情况下 (包括巡逻、移动到包点、追击不可见目标时)，都看向前进方向
//            this.targetAngle = moveAngle; // <-- 核心：将目标角度设置为移动方向
//        }
//
//    }
//
//    private boolean setNewPath(Point2D.Double targetPos) {
//        if (targetPos == null || pathfinder == null || gameState == null) {
//            logger.accept(self.name + " setNewPath 失败: 输入无效或 PathFinder/GameState 未初始化");
//            clearPath(); // 清理路径状态
//            return false;
//        }
//
//        // ---  核心决策逻辑  ---
//        final double LONG_PATH_THRESHOLD_SQ = 1000.0 * 1000.0;
//        double distanceSq = self.position.distanceSq(targetPos);
//        boolean useHierarchicalPathfinding = false;
//
//        if (gameState.hasWaypointGraph()) {
//            if (distanceSq > LONG_PATH_THRESHOLD_SQ) {
//                useHierarchicalPathfinding = true;
//                if (waypointGraphCache == null) {
//                    waypointGraphCache = gameState.getWaypointGraph(); // <-- getWaypointGraph() 现在返回 List<WaypointNode>
//                }
//            }
//        }
//
//        if (useHierarchicalPathfinding && waypointGraphCache != null) {
//            logger.accept(self.name + " (长距/有图) 尝试分层寻路 HPA* 到 " + formatObjective(targetPos));
//
//            // [修正] 调用新方法 (传入最终目标 targetPos)
//            WaypointNode startWp = findNearestVisibleWaypoint(self.position, targetPos);
//            WaypointNode endWp = findNearestVisibleWaypoint(targetPos, targetPos); // 查找离目标点最近的入口
//
//            if (startWp == null || endWp == null) {
//                logger.accept(self.name + " HPA* 失败: 找不到可见的起始或结束路径点。降级为网格寻路。");
//                return setNewPathFallback(targetPos);
//            }
//
//            // [健壮性检查]
//            if (startWp == endWp) {
//                logger.accept(self.name + " HPA* 失败: 起始路径点与结束路径点是同一个。降级为网格寻路。");
//                return setNewPathFallback(targetPos);
//            }
//
//            // [修正] 循环变量类型改为 WaypointNode
//            if (waypointGraphCache != null) {
//                for (WaypointNode wp : waypointGraphCache) {
//                    wp.reset();
//                }
//            }
//
//            // [修正] this.currentCoarsePath 现在是 List<WaypointNode>
//            this.currentCoarsePath = pathfinder.findHighLevelPath(startWp, endWp);
//
//            // --- VVVV 关键修复 VVVV ---
//            // 必须在 pathfinder 返回后立即检查 null！
//            if (this.currentCoarsePath == null || this.currentCoarsePath.isEmpty()) {
//                logger.accept(self.name + " HPA* 失败: 粗算 A* 未找到路径 (可能路径中断)。降级为网格寻路。");
//                return setNewPathFallback(targetPos); // 在这里返回，防止 NullPointerException
//            }
//            // --- ^^^^ 修复结束 ^^^^ ---
//
//            // [修正] 使用 WaypointNode 构造函数
//            WaypointNode finalTargetWp = new WaypointNode(-1, targetPos); // -1 表示临时点
//
//            // 这行代码 (之前的 2671 行) 现在是安全的
//            if (this.currentCoarsePath.get(this.currentCoarsePath.size()-1) != endWp) {
//                this.currentCoarsePath.add(endWp);
//            }
//            this.currentCoarsePath.add(finalTargetWp);
//
//            this.currentCoarsePathIndex = 0;
//            Point2D.Double firstLegTargetPos;
//
//            // [修正] this.currentCoarsePath.get(0) 现在是 WaypointNode
//            if (hasLineOfSight(this.currentCoarsePath.get(0).position)) {
//                firstLegTargetPos = this.currentCoarsePath.get(0).position;
//                logger.accept(self.name + " HPA* 第一段(直达): " + formatObjective(firstLegTargetPos));
//            } else {
//                firstLegTargetPos = startWp.position;
//                logger.accept(self.name + " HPA* 第一段(需绕行至入口): " + formatObjective(firstLegTargetPos));
//                this.currentCoarsePath.add(0, startWp);
//            }
//
//
//            List<GameState.FirePatch> firePatches = (gameState != null) ? gameState.getFirePatches() : new ArrayList<>();
//            this.currentPath = pathfinder.findPath(self, self.position, firstLegTargetPos, this.knownPlayers, firePatches);
//            this.lastPathRecalculationTime = System.currentTimeMillis();
//
//            if (this.currentPath == null || this.currentPath.isEmpty()) {
//                logger.accept(self.name + " HPA* 失败: 细算第一段路径失败。降级为网格寻路。");
//                clearPath();
//                return setNewPathFallback(targetPos);
//            }
//            this.currentPathIndex = 0;
//            return true;
//
//        } else {
//            // --- 情况B：使用纯网格寻路 (短距离 或 地图无路径点) ---
//            if (distanceSq <= LONG_PATH_THRESHOLD_SQ) {
//                logger.accept(self.name + " (短距) 使用网格寻路到 " + formatObjective(targetPos));
//            } else {
//                logger.accept(self.name + " (长距/无图) 使用网格寻路到 " + formatObjective(targetPos));
//            }
//            return setNewPathFallback(targetPos); // 调用纯网格寻路
//        }
//    }
//    /**
//     *  纯网格寻路的备用方法 (从 setNewPath 分离出来)
//     */
//    private boolean setNewPathFallback(Point2D.Double targetPos) {
//        if (targetPos == null || pathfinder == null || gameState == null) return false;
//
//        // 确保清除任何可能残留的粗算路径状态
//        this.currentCoarsePath = null;
//        this.currentCoarsePathIndex = 0;
//        this.waypointGraphCache = null; // 清除缓存引用
//
//        // 执行细算 (网格 A*)
//        List<GameState.FirePatch> firePatches = (gameState != null) ? gameState.getFirePatches() : new ArrayList<>();
//        this.currentPath = pathfinder.findPath(self, self.position, targetPos, this.knownPlayers, firePatches);
//        this.lastPathRecalculationTime = System.currentTimeMillis();
//
//        if (this.currentPath == null || this.currentPath.isEmpty()) {
//            logger.accept(self.name + " 网格寻路失败: 找不到路径到 " + formatObjective(targetPos));
//            clearPath(); // 确保清理状态
//            return false;
//        }
//        this.currentPathIndex = 0;
//        return true; // 网格寻路成功
//    }
//
//    /**
//     * 实时碰撞规避逻辑 + 撞墙惩罚机制
//     * 使用"触须检测"算法在移动方向上预判碰撞，并进行动态避障
//     * 包含防卡死机制：长时间碰撞后执行脱困操作
//     */
//    private void adjustMovementForCollision() {
//        // 检查是否处于移动状态且寻路器可用
//        if ((self.ax == 0 && self.ay == 0) || pathfinder == null) {
//            // 如果未移动且之前处于碰撞状态，则重置碰撞状态
//            if (isCollidingWithWall) {
//                isCollidingWithWall = false;
//                wallCollisionStartTime = 0;
//            }
//            return; // 未移动时无需进行碰撞检测
//        }
//
//        // 防卡死机制：如果持续碰撞超过2秒，执行脱困操作
//        if (isCollidingWithWall && System.currentTimeMillis() - wallCollisionStartTime > 2000) {
//            performUnstuckManeuver(1000); // 执行1秒的脱困操作
//            return;
//        }
//
//        // 计算当前移动方向的角度（弧度）
//        double moveAngle = Math.atan2(self.ay, self.ax);
//
//        // 设置触须长度：1.5倍玩家尺寸，提供足够的预判距离
//        double feelerLength = Player.SIZE * 1.5;
//
//        // 定义三个触须检测角度：正前方、左前方45度、右前方45度
//        double[] feelerAngles = {moveAngle, moveAngle - Math.PI / 4, moveAngle + Math.PI / 4};
//
//        // 当前帧碰撞检测标志
//        boolean collisionDetectedThisFrame = false;
//
//        // 三重触须检测循环
//        Loop:
//        for (double angle : feelerAngles) {
//            // 计算当前触须的终点坐标
//            Point2D.Double feelerEnd = new Point2D.Double(
//                    self.position.x + Math.cos(angle) * feelerLength,
//                    self.position.y + Math.sin(angle) * feelerLength
//            );
//
//            // 创建触须线段对象
//            Line2D.Double feeler = new Line2D.Double(self.position, feelerEnd);
//
//            // 遍历所有障碍物进行碰撞检测
//            for (Shape obstacle : pathfinder.getObstacles()) {
//                // 快速包围盒相交检测 + 精确线段-形状相交检测
//                if (obstacle.intersects(feeler.getBounds2D()) &&
//                        GameState.getLineShapeIntersections(feeler, obstacle) != null) {
//
//                    collisionDetectedThisFrame = true; // 标记碰撞发生
//
//                    // 计算墙壁法线向量（垂直于墙壁方向）
//                    double wallNormalX = -Math.cos(angle);
//                    double wallNormalY = -Math.sin(angle);
//
//                    // 计算当前加速度在墙壁法线上的投影分量
//                    double dotProduct = self.ax * wallNormalX + self.ay * wallNormalY;
//                    double projX = dotProduct * wallNormalX;
//                    double projY = dotProduct * wallNormalY;
//
//                    // 从当前加速度中减去朝向墙壁的分量，实现平滑避障
//                    self.ax -= projX;
//                    self.ay -= projY;
//
//                    break Loop; // 发现碰撞后跳出所有循环
//                }
//            }
//        }
//
//        // 更新碰撞状态机
//        if (collisionDetectedThisFrame) {
//            // 新碰撞：记录碰撞开始时间
//            if (!isCollidingWithWall) {
//                isCollidingWithWall = true;
//                wallCollisionStartTime = System.currentTimeMillis();
//            }
//        } else {
//            // 碰撞结束：重置碰撞状态
//            if (isCollidingWithWall) {
//                isCollidingWithWall = false;
//                wallCollisionStartTime = 0;
//            }
//        }
//    }
//
//    /**
//     * 计算一个侧翼包抄点
//     * 在目标位置的垂直方向上随机选择左右一侧，生成包抄位置
//     * 用于实现AI的战术包围行为
//     *
//     * @param targetPos 目标位置坐标，不能为null
//     * @return 计算出的侧翼包抄点坐标，确保在地图边界内
//     */
//    private Point2D.Double calculateFlankPoint(Point2D.Double targetPos) {
//        // 【修复】空值安全检查 - 防止因目标位置为空导致的计算异常
//        if (targetPos == null) {
//            logger.accept(self.name + " 错误：calculateFlankPoint 传入的 targetPos 为 null");
//            // 返回一个安全的位置：在当前住置附近随机偏移，确保在地图边界内
//            double safeX = Math.max(50, Math.min(gameState.width - 50, self.position.x + (rand.nextDouble() - 0.5) * 100));
//            double safeY = Math.max(50, Math.min(gameState.height - 50, self.position.y + (rand.nextDouble() - 0.5) * 100));
//            return new Point2D.Double(safeX, safeY);
//        }
//
//        // 计算从当前位置指向目标位置的角度
//        double angleToTarget = Math.atan2(targetPos.y - self.position.y, targetPos.x - self.position.x);
//
//        // 随机选择向左或向右90度方向进行包抄
//        double flankAngle = angleToTarget + (rand.nextBoolean() ? Math.PI / 2 : -Math.PI / 2);
//
//        // 设置包抄距离：150-350像素范围内随机
//        double flankDistance = 150 + rand.nextDouble() * 200;
//
//        // 计算包抄点的原始坐标
//        double flankX = self.position.x + Math.cos(flankAngle) * flankDistance;
//        double flankY = self.position.y + Math.sin(flankAngle) * flankDistance;
//
//        // 确保包抄点坐标在地图边界内，避免生成无效位置
//        flankX = Math.max(Player.SIZE, Math.min(gameState.width - Player.SIZE, flankX));
//        flankY = Math.max(Player.SIZE, Math.min(gameState.height - Player.SIZE, flankY));
//
//        return new Point2D.Double(flankX, flankY);
//    }
//
//    /** 清除当前路径 */
//    private void clearPath() {
//        this.currentPath = null;
//        this.currentPathIndex = 0;
//
//
//        this.currentCoarsePath = null;
//        this.currentCoarsePathIndex = 0;
//        this.waypointGraphCache = null; // 清除缓存引用
//
//        // 停止移动
//        if (this.self != null) { // 添加 null 检查
//            this.self.ax = 0;
//            this.self.ay = 0;
//        }
//    }
//
//    /**
//     *  辅助方法：从“粗算图”中找到一个离指定位置最近、且当前位置能“看”到的点。
//     * @param position 检查视线的起点 (通常是 self.position 或 targetPos)
//     * @return 最近且可见的 WaypointNode 对象，如果找不到或粗算图无效则返回 null。
//     */
//    private WaypointNode findNearestVisibleWaypoint(Point2D.Double position, Point2D.Double finalTargetPos) {
//        if (waypointGraphCache == null || waypointGraphCache.isEmpty() || position == null || finalTargetPos == null) {
//            return null;
//        }
//
//        WaypointNode bestWp = null;
//        double minCost = Double.POSITIVE_INFINITY;
//        double totalDirectDistance = position.distance(finalTargetPos);
//
//        // 获取当前时间，用于检查“粘性”是否过期
//        long currentTime = System.currentTimeMillis();
//
//        for (WaypointNode wp : waypointGraphCache) {
//            if (hasLineOfSightToPointFromPoint(position, wp.position)) {
//
//                double distToWp = position.distance(wp.position);
//                double distWpToTarget = wp.position.distance(finalTargetPos);
//                double detourFactor = (distToWp + distWpToTarget) / (totalDirectDistance + 1e-6);
//                double cost = (distToWp + distWpToTarget);
//
//                if (detourFactor > 1.5) cost *= 3.0;
//                if (detourFactor > 2.0) cost *= 10.0;
//
//                // --- [添加回头惩罚/禁止] ---
//                // 检查这个点是不是我们刚刚经过的点，并且时间还没超过 STICKY_WAYPOINT_DURATION
//                if (wp == this.lastPassedWaypointNode &&
//                        currentTime - this.lastPassedWaypointTime < STICKY_WAYPOINT_DURATION)
//                {
//                    // logger.accept(String.format("[%s] Penalizing recently passed Waypoint %s.", self.name, formatObjective(wp.position))); // 可选日志
//                    // 施加一个巨大的惩罚，使其几乎不可能被选中
//                    cost *= 100.0;
//                    // 或者直接跳过这个点 (更强硬)
//                    // continue;
//                }
//                //  寻找成本最低的点
//                if (cost < minCost) {
//                    minCost = cost;
//                    bestWp = wp;
//                }
//            }
//        }
//
//        // [日志] 添加日志方便调试
//        if (bestWp != null) {
//             logger.accept(self.name + " HPA* 入口选择: " + formatObjective(bestWp.position) + " (成本: " + (int)minCost + ")");
//        } else {
//             logger.accept(self.name + " HPA* 入口选择: 失败! 找不到任何可见的路径点。");
//        }
//
//        return bestWp;
//    }
//
//    /** 检查是否应该重新计算路径（防止过于频繁的计算） */
//    private boolean shouldRecalculatePath() {
//        return System.currentTimeMillis() - lastPathRecalculationTime > 1000;
//    }
//
//    /** 检查是否有清晰的视线（无障碍物） */
//    private boolean hasLineOfSight(Point2D.Double targetPos) {
//        return hasLineOfSightToPointFromPoint(self.position, targetPos);
//    }
//    /**
//     * 检查两点之间是否存在直接视线（无障碍物遮挡）
//     * 用于判断AI是否可以直接看到目标位置，决定射击、移动等行为
//     *
//     * @param startPos 视线起点坐标
//     * @param endPos 视线终点坐标
//     * @return 如果两点间无遮挡返回true，有障碍物遮挡返回false
//     *         如果参数无效或寻路器不可用，默认返回true（保守策略）
//     */
//    private boolean hasLineOfSightToPointFromPoint(Point2D.Double startPos, Point2D.Double endPos) {
//
//        // if (pathfinder == null || pathfinder.getObstacles() == null || startPos == null || endPos == null) return true;
//
//        // 检查 GameState 和 Quadtree
//        if (gameState == null || gameState.getQuadtreeRootNode() == null || startPos == null || endPos == null) {
//            return true; // 如果 Quadtree 没准备好，保守返回 true
//        }
//
//        Line2D.Double line = new Line2D.Double(startPos, endPos);
//
//        // 从 Quadtree 获取【少量】候选障碍物
//        List<MapData.ShapeWrapper> candidateObstacles = new ArrayList<>();
//        gameState.getQuadtreeRootNode().queryRay(candidateObstacles, startPos, endPos);
//
//        // 只检查这些【少量】候选者
//        // for(Shape obs : pathfinder.getObstacles()) {
//        for (MapData.ShapeWrapper wrapper : candidateObstacles) { // [新代码]
//
//            // 需要一个方法将 ShapeWrapper 转换回 Shape
//            // 在 Pathfinder 中缓存这个转换
//            Shape obs = pathfinder.getShapeForWrapper(wrapper); // (需要添加这个辅助方法)
//            if (obs == null) continue;
//
//            if (obs.intersects(line.getBounds2D()) && GameState.getLineShapeIntersections(line, obs) != null) {
//                return false; // 视线被阻断
//            }
//        }
//
//        return true; // 视线畅通
//    }
//
//    /**
//     * 应用团队分离力，避免队友之间过度拥挤
//     * 实现智能的队友间距保持，特别处理正在执行关键任务的队友
//     *
//     * 算法原理：基于排斥力的群体行为模拟
//     * - 为每个过近的队友计算排斥向量
//     * - 排斥力大小与距离成反比
//     * - 对正在安包/拆包的队友给予特殊保护距离
//     */
//    private void applyTeamSeparation() {
//        // 特殊情况处理：如果AI正在执行关键任务，完全忽略分离逻辑
//        if (self.isInteracting || currentState == AIState.PLANTING_BOMB || currentState == AIState.DEFUSING_BOMB) {
//            return; // 保持站位不动，专注完成任务
//        }
//
//        // 僵尸队伍不应用团队分离逻辑，或者没有已知玩家信息时退出
//        if (self.team == Player.Team.ZOMBIE || knownPlayers == null) {
//            return;
//        }
//
//        // 分离力参数配置
//        double separationForceFactor = 1.5; // 分离力缩放系数
//        double totalForceX = 0; // X轴方向总分离力
//        double totalForceY = 0; // Y轴方向总分离力
//        int closeTeammates = 0; // 统计过近的队友数量
//
//        // 遍历所有已知玩家，寻找需要分离的队友
//        for (Player p : knownPlayers) {
//            // 过滤条件：非自身、存活、同一队伍
//            if (p != self && p.isAlive() && p.team == self.team) {
//                // 动态计算期望分离距离
//                double desiredSeparation = Player.SIZE * 1.5; // 默认分离距离：1.5倍玩家尺寸
//
//                // 特殊处理：如果队友正在执行关键任务（安包/拆包）
//                if (p.isInteracting) {
//                    // 大幅增加安全距离，为关键任务队友创造保护空间
//                    desiredSeparation = Player.SIZE * 4.0; // VIP保护距离
//                }
//
//                // 再次检查自身状态（防止状态变化）
//                if (self.isInteracting || currentState == AIState.PLANTING_BOMB || currentState == AIState.DEFUSING_BOMB) {
//                    return; // 如果自身开始执行关键任务，立即退出分离逻辑
//                }
//
//                // 计算与当前队友的实际距离
//                double distance = self.position.distance(p.position);
//
//                // 如果队友在期望分离距离内，计算排斥力
//                if (distance > 0 && distance < desiredSeparation) {
//                    // 计算排斥方向向量：从队友指向自身
//                    double forceX = self.position.x - p.position.x;
//                    double forceY = self.position.y - p.position.y;
//
//                    // 计算向量长度
//                    double magnitude = Math.sqrt(forceX * forceX + forceY * forceY);
//
//                    if (magnitude > 0) {
//                        // 计算排斥力强度：距离越近，排斥力越强
//                        // 公式：(期望距离 - 实际距离) / 期望距离，实现非线性排斥
//                        double forceScale = (desiredSeparation - distance) / desiredSeparation;
//
//                        // 累加归一化后的排斥力向量
//                        totalForceX += (forceX / magnitude) * forceScale;
//                        totalForceY += (forceY / magnitude) * forceScale;
//                    }
//                    closeTeammates++; // 统计过近队友数量
//                }
//            }
//        }
//
//        // 如果有过近队友，应用平均分离力
//        if (closeTeammates > 0) {
//            // 计算平均分离力并乘以缩放系数
//            self.ax += (totalForceX / closeTeammates) * separationForceFactor;
//            self.ay += (totalForceY / closeTeammates) * separationForceFactor;
//        }
//    }
//
//    /**
//     * 归一化加速度向量，确保移动速度不会超过最大值
//     * 防止因多方向力叠加导致的超速移动
//     *
//     * 原理：将加速度向量缩放到单位长度，保持方向不变
//     * 类似向量数学中的单位化操作
//     */
//    private void normalizeAcceleration() {
//        // 计算当前加速度向量的长度（模）
//        double magnitude = Math.sqrt(self.ax * self.ax + self.ay * self.ay);
//
//        // 如果向量长度超过1，进行归一化处理
//        if (magnitude > 1.0) {
//            self.ax /= magnitude; // X分量归一化
//            self.ay /= magnitude; // Y分量归一化
//        }
//        // 如果长度小于等于1，保持原向量不变
//    }
//
//    /**
//     * A*寻路算法节点类
//     * 用于在网格化地图中表示路径查找的搜索节点
//     *
//     * 包含A*算法所需的所有核心属性：
//     * - 位置坐标、障碍物标记
//     * - 路径代价（gCost、hCost、fCost）
//     * - 父节点引用（用于回溯路径）
//     */
//    static class Node {
//        int x, y;           // 节点在网格中的坐标
//        boolean isWall;     // 是否为障碍物节点
//        int gCost;          // 从起点到当前节点的实际移动代价
//        int hCost;          // 从当前节点到目标点的启发式估计代价
//        Node parent;        // 路径回溯的父节点引用
//
//        /**
//         * 节点构造函数
//         * @param isWall 是否为障碍物
//         * @param x 网格X坐标
//         * @param y 网格Y坐标
//         */
//        Node(boolean isWall, int x, int y) {
//            this.isWall = isWall;
//            this.x = x;
//            this.y = y;
//        }
//
//        /**
//         * 计算A*算法的总评估代价
//         * f(n) = g(n) + h(n)
//         *
//         * @return 总评估代价，用于优先级队列排序
//         */
//        int fCost() {
//            return gCost + hCost;
//        }
//    }
//
//    static class Pathfinder {
//        // 新增一个静态变量来缓存第一次生成的网格
//        private static Node[][] gridCache = null;
//        private static int cachedGridWidth = 0;
//        private static int cachedGridHeight = 0;
//        private static final Object cacheLock = new Object();
//        private final Map<MapData.ShapeWrapper, Shape> shapeCache; // 保持 final
//        private final Node[][] grid;
//        private final List<Shape> obstacles; // 保持 final
//        private final int gridWidth, gridHeight, cellSize;
//        private final int worldWidth, worldHeight;
//        private final Random rand = new Random();
//
//        /**
//         * [修正版构造函数]
//         * @param gameState GameState 实例，用于获取地图数据和障碍物信息。
//         * @param cellSize 网格单元的大小。
//         */
//        public Pathfinder(GameState gameState, int cellSize) { // <-- 修改参数列表
//            // 健壮性检查
//            if (gameState == null) {
//                throw new IllegalArgumentException("GameState cannot be null for Pathfinder initialization.");
//            }
//            if (cellSize <= 0) {
//                throw new IllegalArgumentException("Cell size must be positive for Pathfinder grid.");
//            }
//
//            //  从 GameState 获取尺寸
//            this.worldWidth = gameState.width;
//            this.worldHeight = gameState.height;
//            this.cellSize = cellSize;
//
//            //  计算网格尺寸 (添加边界检查)
//            if (this.worldWidth <= 0 || this.worldHeight <= 0) {
//                throw new IllegalArgumentException("Invalid map dimensions received from GameState for Pathfinder grid.");
//            }
//            this.gridWidth = (int) Math.ceil((double) this.worldWidth / cellSize);
//            this.gridHeight = (int) Math.ceil((double) this.worldHeight / cellSize);
//
//            //   初始化障碍物列表和形状缓存
//            this.obstacles = new ArrayList<>(); // 初始化为空列表
//            this.shapeCache = new HashMap<>();  // 初始化为空 Map
//
//            // 4. [核心] 遍历 ShapeWrappers，填充 obstacles 列表和 shapeCache
//            if (gameState.getObstacleWrappers() != null) {
//                for (MapData.ShapeWrapper wrapper : gameState.getObstacleWrappers()) {
//                    if (wrapper == null) continue; // 跳过 null wrapper
//                    Shape shape = GameState.convertWrapperToShape(wrapper); // 必须是 public static
//                    if (shape != null) {
//                        this.obstacles.add(shape);       // 填充 obstacles 列表 (给 createGrid 用)
//                        this.shapeCache.put(wrapper, shape); // 填充 shapeCache (给 getShapeForWrapper 用)
//                    }
//                }
//            }
//
//            // 5. 网格缓存逻辑 (保持不变)
//            synchronized (cacheLock) {
//                if (gridCache != null && cachedGridWidth == this.gridWidth && cachedGridHeight == this.gridHeight) {
//                    this.grid = createGridFromCache();
//                } else {
//                    this.grid = new Node[gridWidth][gridHeight];
//                    // createGrid() 现在会使用上面第 4 步填充好的 this.obstacles
//                    createGrid();
//                    gridCache = this.grid;
//                    cachedGridWidth = this.gridWidth;
//                    cachedGridHeight = this.gridHeight;
//                }
//            }
//        }
//        /**
//        public Pathfinder(List<Shape> obstacles, int worldWidth, int worldHeight, int cellSize) {
//            this.obstacles = obstacles;
//            this.cellSize = cellSize;
//            this.worldWidth = worldWidth;
//            this.worldHeight = worldHeight;
//            this.gridWidth = worldWidth / cellSize;
//            this.gridHeight = worldHeight / cellSize;
//
//            // 构造函数的核心修改
//            synchronized (cacheLock) {
//                // 检查是否有可用的、尺寸匹配的缓存
//                if (gridCache != null && cachedGridWidth == this.gridWidth && cachedGridHeight == this.gridHeight) {
//                    // 如果有缓存，就执行超快速的“从缓存创建”
//                    this.grid = createGridFromCache();
//                } else {
//                    // 如果没有缓存（这是游戏中的第一个Pathfinder），执行一次昂贵的创建
//                    this.grid = new Node[gridWidth][gridHeight];
//                    createGrid(); // 这个方法会填充 this.grid
//                    // 将结果存入缓存，供后续所有 Pathfinder 使用
//                    gridCache = this.grid;
//                    cachedGridWidth = this.gridWidth;
//                    cachedGridHeight = this.gridHeight;
//                }
//            }
//         */
//
//        //  添加这个辅助方法
//        public Shape getShapeForWrapper(MapData.ShapeWrapper wrapper) {
//            return shapeCache.get(wrapper);
//        }
//        /**
//         * 从缓存中快速深度拷贝一份网格。
//         * 只拷贝不可变数据（isWall, x, y），确保每个Pathfinder的寻路计算不会互相干扰。
//         * @return 一个全新的、与缓存内容相同的 Node[][] 数组。
//         */
//        private Node[][] createGridFromCache() {
//            Node[][] newGrid = new Node[this.gridWidth][this.gridHeight];
//            for (int x = 0; x < this.gridWidth; x++) {
//                for (int y = 0; y < this.gridHeight; y++) {
//                    // 从缓存的节点中复制基础信息来创建新节点
//                    Node cachedNode = gridCache[x][y];
//                    newGrid[x][y] = new Node(cachedNode.isWall, cachedNode.x, cachedNode.y);
//                }
//            }
//            return newGrid;
//        }
//
//        // createGrid() 方法保持原样，不需要修改
//        private void createGrid() {
//            for (int x = 0; x < gridWidth; x++) {
//                for (int y = 0; y < gridHeight; y++) {
//                    Point2D.Double worldPoint = nodeToWorld(new Node(false, x, y));
//                    boolean isWall = false;
//                    double checkRadius = (cellSize / 2.0) * 1.1;
//                    Ellipse2D.Double playerBoundsAtNode = new Ellipse2D.Double(worldPoint.x - checkRadius, worldPoint.y - checkRadius, checkRadius * 2, checkRadius * 2);
//                    Area checkArea = new Area(playerBoundsAtNode);
//
//                    for (Shape obs : obstacles) {
//                        Area obsArea = new Area(obs);
//                        obsArea.intersect(checkArea);
//                        if (!obsArea.isEmpty()) {
//                            isWall = true;
//                            break;
//                        }
//                    }
//                    // 直接填充 this.grid
//                    this.grid[x][y] = new Node(isWall, x, y);
//                }
//            }
//        }
//
//
//        public List<Node> findPath(Player self, Point2D.Double startWorld, Point2D.Double endWorld, List<Player> allPlayers, List<GameState.FirePatch> firePatches) {
//            Node startNode = worldToNode(startWorld);
//            Node endNode = worldToNode(endWorld);
//
//            if (startNode.isWall) { startNode = findNearestWalkableNode(startNode); if (startNode == null) return null; }
//            if (endNode.isWall) { endNode = findNearestWalkableNode(endNode); if (endNode == null) return null; }
//
//            // 临时将火焰区域标记为墙壁
//            List<Node> temporaryFireNodes = new ArrayList<>();
//            if (firePatches != null) {
//                for (GameState.FirePatch fire : firePatches) {
//                    // 遍历火焰影响范围内的所有网格节点
//                    int minX = Math.max(0, worldToNode(new Point2D.Double(fire.position.x - GameState.FirePatch.RADIUS, 0)).x);
//                    int maxX = Math.min(gridWidth - 1, worldToNode(new Point2D.Double(fire.position.x + GameState.FirePatch.RADIUS, 0)).x);
//                    int minY = Math.max(0, worldToNode(new Point2D.Double(0, fire.position.y - GameState.FirePatch.RADIUS)).y);
//                    int maxY = Math.min(gridHeight - 1, worldToNode(new Point2D.Double(0, fire.position.y + GameState.FirePatch.RADIUS)).y);
//
//                    for (int x = minX; x <= maxX; x++) {
//                        for (int y = minY; y <= maxY; y++) {
//                            Node node = grid[x][y];
//                            // 如果节点在火焰半径内，并且它不是一个永久的墙
//                            if (!node.isWall && nodeToWorld(node).distanceSq(fire.position) < GameState.FirePatch.RADIUS * GameState.FirePatch.RADIUS) {
//                                node.isWall = true; // 临时标记为墙
//                                temporaryFireNodes.add(node); // 记录下来，以便后续恢复
//                            }
//                        }
//                    }
//                }
//            }
//
//            // 使用 try-finally 确保无论寻路成功与否，临时标记都会被清除
//            try {
//                List<Node> openSet = new ArrayList<>();
//                Set<Node> closedSet = new HashSet<>();
//                openSet.add(startNode);
//
//                while (!openSet.isEmpty()) {
//                    Node currentNode = openSet.get(0);
//                    for (int i = 1; i < openSet.size(); i++) {
//                        if (openSet.get(i).fCost() < currentNode.fCost() || (openSet.get(i).fCost() == currentNode.fCost() && openSet.get(i).hCost < currentNode.hCost)) {
//                            currentNode = openSet.get(i);
//                        }
//                    }
//                    openSet.remove(currentNode);
//                    closedSet.add(currentNode);
//
//                    if (currentNode == endNode) {
//                        return retracePath(startNode, endNode);
//                    }
//
//                    for (Node neighbor : getNeighbors(currentNode)) {
//                        if (neighbor.isWall || closedSet.contains(neighbor)) continue;
//
//                        // 避开队友
//                        Point2D.Double neighborWorldPos = nodeToWorld(neighbor);
//                        boolean isBlockedByTeammate = false;
//                        for (Player p : allPlayers) {
//                            if (p != self && p.isAlive() && p.team == self.team && neighborWorldPos.distanceSq(p.position) < (cellSize * cellSize) / 4.0) {
//                                isBlockedByTeammate = true;
//                                break;
//                            }
//                        }
//                        if (isBlockedByTeammate) continue;
//
//                        int newCost = currentNode.gCost + getDistance(currentNode, neighbor);
//                        if (newCost < neighbor.gCost || !openSet.contains(neighbor)) {
//                            neighbor.gCost = newCost;
//                            neighbor.hCost = getDistance(neighbor, endNode);
//                            neighbor.parent = currentNode;
//                            if (!openSet.contains(neighbor)) openSet.add(neighbor);
//                        }
//                    }
//                }
//                return null; // 找不到路径
//            } finally {
//                // 恢复被临时标记为墙的火焰节点
//                for (Node node : temporaryFireNodes) {
//                    node.isWall = false;
//                }
//            }
//        }
//        /**
//         * 在网格中寻找距离指定节点最近的可行走节点
//         * 使用广度优先搜索(BFS)算法向外扩散，直到找到第一个非障碍物节点
//         *
//         * @param node 起始节点（可能是障碍物节点）
//         * @return 找到的第一个可行走节点，如果找不到则返回null
//         */
//        private Node findNearestWalkableNode(Node node) {
//            // 如果起始节点本身就是可行走的，直接返回
//            if (!node.isWall) return node;
//
//            // 初始化BFS队列和已访问集合
//            Queue<Node> queue = new LinkedList<>();
//            Set<Node> visited = new HashSet<>();
//            queue.add(node);
//            visited.add(node);
//
//            // 开始BFS搜索
//            while(!queue.isEmpty()) {
//                Node current = queue.poll(); // 取出队列头部节点
//
//                // 遍历当前节点的所有邻居节点
//                for (Node neighbor : getNeighbors(current)) {
//                    // 找到第一个可行走节点，立即返回（最近的可行走节点）
//                    if (!neighbor.isWall) return neighbor;
//
//                    // 如果邻居节点未被访问过，加入队列继续搜索
//                    if (!visited.contains(neighbor)) {
//                        visited.add(neighbor);
//                        queue.add(neighbor);
//                    }
//                }
//            }
//            return null; // 搜索完所有可达节点仍未找到可行走节点
//        }
//
//        /**
//         * 从终点回溯到起点，重建完整路径
//         * 通过父节点指针链从终点反向遍历到起点，然后反转得到正向路径
//         *
//         * @param startNode 路径起始节点
//         * @param endNode 路径终止节点
//         * @return 从起点到终点的节点列表，如果路径不完整可能返回空列表
//         */
//        private List<Node> retracePath(Node startNode, Node endNode) {
//            List<Node> path = new ArrayList<>();
//            Node currentNode = endNode;
//
//            // 从终点开始，沿着父节点指针回溯到起点
//            while (currentNode != startNode) {
//                path.add(currentNode); // 将当前节点加入路径
//
//                // 【注释掉的代码】原版的空指针检查，现在依赖A*算法的正确性
//                // if (currentNode.parent == null) {
//                //     return null; // 父节点链断裂，路径不完整
//                // }
//
//                currentNode = currentNode.parent; // 移动到父节点
//            }
//
//            // 反转路径：从终点->起点 变为 起点->终点
//            Collections.reverse(path);
//            return path;
//        }
//
//        /**
//         * 获取指定节点的所有相邻节点（8方向）
//         * 包括上下左右和对角线方向的邻居，实现全方位移动
//         *
//         * @param node 中心节点
//         * @return 相邻节点列表，已过滤边界外的无效位置
//         */
//        private List<Node> getNeighbors(Node node) {
//            List<Node> neighbors = new ArrayList<>();
//
//            // 遍历3x3邻域（排除中心点自身）
//            for (int x = -1; x <= 1; x++) {
//                for (int y = -1; y <= 1; y++) {
//                    if (x == 0 && y == 0) continue; // 跳过中心点
//
//                    // 计算邻居节点坐标
//                    int checkX = node.x + x;
//                    int checkY = node.y + y;
//
//                    // 边界检查：确保坐标在网格范围内
//                    if (checkX >= 0 && checkX < gridWidth && checkY >= 0 && checkY < gridHeight) {
//                        neighbors.add(grid[checkX][checkY]); // 添加有效邻居
//                    }
//                }
//            }
//            return neighbors;
//        }
//
//        /**
//         * 计算两个节点之间的移动代价（使用对角线距离）
//         * 采用14-10代价系统：对角线移动代价14，直线移动代价10
//         * 这是A*算法中常用的启发式函数，比曼哈顿距离更准确
//         *
//         * @param a 起始节点
//         * @param b 目标节点
//         * @return 估算的移动代价
//         */
//        private int getDistance(Node a, Node b) {
//            int dX = Math.abs(a.x - b.x); // X方向距离
//            int dY = Math.abs(a.y - b.y); // Y方向距离
//
//            // 计算公式：min(dX, dY) * 14 + |dX - dY| * 10
//            // 14 ≈ 10 * √2（对角线移动的近似代价）
//            return (dX > dY) ? 14*dY + 10*(dX-dY) : 14*dX + 10*(dY-dX);
//        }
//
//        /**
//         * 将世界坐标转换为网格节点坐标
//         * 通过除法取整将连续的世界坐标映射到离散的网格索引
//         *
//         * @param worldPos 世界坐标系中的点（像素坐标）
//         * @return 对应的网格节点，已进行边界安全处理
//         */
//        private Node worldToNode(Point2D.Double worldPos) {
//            // 计算网格索引：世界坐标 / 单元格尺寸
//            int x = (int)(worldPos.x / cellSize);
//            int y = (int)(worldPos.y / cellSize);
//
//            // 边界安全处理：限制索引在有效范围内
//            x = Math.max(0, Math.min(x, gridWidth - 1));
//            y = Math.max(0, Math.min(y, gridHeight - 1));
//
//            return grid[x][y]; // 返回对应的网格节点
//        }
//
//        /**
//         * 将网格节点转换回世界坐标
//         * 返回单元格中心点的世界坐标，使移动目标更自然
//         *
//         * @param node 网格节点
//         * @return 节点对应的世界坐标（单元格中心点）
//         */
//        public Point2D.Double nodeToWorld(Node node) {
//            // 计算单元格中心点坐标：节点索引 * 单元格尺寸 + 半单元格偏移
//            return new Point2D.Double(
//                    (node.x * cellSize) + cellSize / 2.0,
//                    (node.y * cellSize) + cellSize / 2.0
//            );
//        }
//
//        /**
//         * 获取所有障碍物形状列表
//         * 用于碰撞检测和视线判断等其他系统
//         *
//         * @return 障碍物形状列表
//         */
//        public List<Shape> getObstacles() {
//            return obstacles;
//        }
//
//        /**
//         * 检查世界坐标点是否可行走（非障碍物区域）
//         * 通过坐标转换和节点查询实现快速可行性检查
//         *
//         * @param worldPos 要检查的世界坐标点
//         * @return 如果点在可行走区域返回true，否则返回false
//         */
//        public boolean isWalkable(Point2D.Double worldPos) {
//            if(worldPos == null) return false; // 空值安全检查
//
//            // 转换为网格节点并检查是否为障碍物
//            Node node = worldToNode(worldPos);
//            return node != null && !node.isWall;
//        }
//
//        /**
//         * 计算一个相对于起点和目标点的侧翼包抄点。
//         * @param startPos 执行侧翼包抄单位的起始位置。
//         * @param targetPos 将要被包抄的目标位置。
//         * @return 一个计算出的侧翼点。
//         */
//        public Point2D.Double calculateFlankPoint(Point2D.Double startPos, Point2D.Double targetPos) {
//            if (targetPos == null || startPos == null) {
//                // 返回一个靠近起点的安全备用位置
//                // 现在可以直接使用 this.worldWidth 和 this.rand
//                double safeX = Math.max(Player.SIZE, Math.min(this.worldWidth - Player.SIZE, startPos.x + (this.rand.nextDouble() - 0.5) * 100));
//                double safeY = Math.max(Player.SIZE, Math.min(this.worldHeight - Player.SIZE, startPos.y + (this.rand.nextDouble() - 0.5) * 100));
//                return new Point2D.Double(safeX, safeY);
//            }
//
//            double angleToTarget = Math.atan2(targetPos.y - startPos.y, targetPos.x - startPos.x);
//            // 随机选择左翼或右翼
//            // 使用 this.rand
//            double flankAngle = angleToTarget + (this.rand.nextBoolean() ? Math.PI / 2 : -Math.PI / 2);
//            double flankDistance = 150 + this.rand.nextDouble() * 200;
//
//            double flankX = startPos.x + Math.cos(flankAngle) * flankDistance;
//            double flankY = startPos.y + Math.sin(flankAngle) * flankDistance;
//
//            // 确保计算出的点在地图边界内
//            //  使用 this.worldWidth
//            flankX = Math.max(Player.SIZE, Math.min(this.worldWidth - Player.SIZE, flankX));
//            flankY = Math.max(Player.SIZE, Math.min(this.worldHeight - Player.SIZE, flankY));
//
//            return new Point2D.Double(flankX, flankY);
//        }
//
//        /**
//         * 在 "WaypointNode 图" 上运行 A* 算法。
//         * @param startNode 起始路径点节点 (类型是 WaypointNode)
//         * @param endNode 目标路径点节点 (类型是 WaypointNode)
//         * @return 包含一系列 WaypointNode 的路径，如果找不到则返回 null。
//         */
//        public List<WaypointNode> findHighLevelPath(WaypointNode startNode, WaypointNode endNode) {
//            if (startNode == null || endNode == null) return null;
//
//            // 注意：重置逻辑已在 AIController.setNewPath 中完成，调用此方法前完成
//
//            // 使用 PriorityQueue 可以更高效地找到 fCost 最低的节点
//            PriorityQueue<WaypointNode> openSet = new PriorityQueue<>(Comparator.comparingDouble(WaypointNode::fCost));
//            Set<WaypointNode> closedSet = new HashSet<>();
//
//            // 初始化起点
//            startNode.gCost = 0;
//            startNode.hCost = startNode.position.distance(endNode.position); // 欧几里得距离作为启发
//            openSet.add(startNode);
//
//            while (!openSet.isEmpty()) {
//                WaypointNode currentNode = openSet.poll(); // 取出 fCost 最低的节点
//
//                // 如果当前节点就是目标节点，回溯路径
//                if (currentNode == endNode) {
//                    return retraceHighLevelPath(startNode, endNode);
//                }
//
//                closedSet.add(currentNode); // 将当前节点加入已检查集合
//
//                // 遍历当前节点的邻居
//                // (确保 WaypointNode 类有 neighbors 和 costs 列表)
//                if (currentNode.neighbors == null || currentNode.costs == null) continue; // 安全检查
//
//                for (int i = 0; i < currentNode.neighbors.size(); i++) {
//                    WaypointNode neighbor = currentNode.neighbors.get(i);
//                    double costToNeighbor = currentNode.costs.get(i); // 获取到邻居的实际成本
//
//                    // 如果邻居已在 closedSet 中，跳过
//                    if (closedSet.contains(neighbor)) {
//                        continue;
//                    }
//
//                    // 计算经由当前节点到达邻居的总成本 (gCost)
//                    double newGCost = currentNode.gCost + costToNeighbor;
//
//                    // 如果这条路径更优，或者邻居不在 openSet 中
//                    boolean isInOpenSet = openSet.contains(neighbor);
//                    if (newGCost < neighbor.gCost || !isInOpenSet) {
//                        neighbor.gCost = newGCost;
//                        neighbor.hCost = neighbor.position.distance(endNode.position); // 更新 hCost
//                        neighbor.parent = currentNode; // 设置父节点用于回溯
//
//                        if (!isInOpenSet) {
//                            openSet.add(neighbor); // 加入 openSet
//                        } else {
//                            // 如果已在 openSet 中，需要更新其在优先队列中的位置
//                            // (Java 的 PriorityQueue 不直接支持更新，所以先移除再添加)
//                            openSet.remove(neighbor);
//                            openSet.add(neighbor);
//                        }
//                    }
//                }
//            }
//
//            return null; // 循环结束仍未找到目标，说明没有路径
//        }
//
//
//        /**
//         * 回溯粗算路径 (使用 WaypointNode)
//         * @param startNode 起始点 (类型是 WaypointNode)
//         * @param endNode 终点 (类型是 WaypointNode)
//         * @return 路径列表 (类型是 WaypointNode)
//         */
//        private List<WaypointNode> retraceHighLevelPath(WaypointNode startNode, WaypointNode endNode) {
//            List<WaypointNode> path = new ArrayList<>();
//            WaypointNode current = endNode;
//
//            // 通过 parent 指针回溯路径
//            while (current != null && current != startNode) { // 增加 current != null 检查
//                path.add(current);
//                current = current.parent; // 移动到父节点
//            }
//
//            if (current == startNode) { // 确保起点也被加入
//                path.add(startNode);
//            } else {
//                // 如果 current 变成 null 但还没到 startNode，说明路径有问题
//                System.err.println("错误：回溯粗算路径时路径中断！(start: " + startNode.index + ", end: " + endNode.index + ")");
//                // 可以返回 null 或空列表，取决于你的错误处理策略
//                return null;
//                // path.clear(); // 或者返回空列表
//            }
//
//            Collections.reverse(path); // 反转列表得到从起点到终点的顺序
//
//            // 注意：重置逻辑在 AIController.setNewPath 中完成，这里不需要重置
//
//            return path;
//        }
//
//        /**
//         * 回溯粗算路径
//         * @param startWp 起始点 (类型必须是 cs2d.playerAndAi.Waypoint)
//         * @param endWp 终点 (类型必须是 cs2d.playerAndAi.Waypoint)
//         * @return 路径列表 (类型必须是 cs2d.playerAndAi.Waypoint)
//         */
//        private List<Waypoint> retraceHighLevelPath(Waypoint startWp, Waypoint endWp) { // <-- 检查这里的 Waypoint 类型
//            List<Waypoint> path = new ArrayList<>();
//            Waypoint current = endWp;
//            // 通过 parent 指针回溯
//            while (current != null && current != startWp) { // 增加 current != null 的安全检查
//                path.add(current);
//                current = current.parent; // 移动到父节点
//            }
//            if (current == startWp) { // 确保起点也被加入
//                path.add(startWp);
//            } else {
//                // 如果 current 变成 null 但还没到 startWp，说明路径断了
//                System.err.println("错误：回溯粗算路径时路径中断！");
//                return null; // 或者返回一个空列表 new ArrayList<>()
//            }
//
//            Collections.reverse(path); // 反转列表得到从起点到终点的顺序
//
//
//
//            return path;
//        }
//
//    }
//
//    /** 老旧方法，已经删了
//     * 服务器端的连续碰撞检测核心方法。
//     * @param ray 移动射线
//     * @param obstacles 所有障碍物列表
//     * @return 最近的碰撞结果，如果没有碰撞则返回 null
//     */
//
//
//    private static long lastGlobalGrenadeCheckTime = 0;
//    private static final long GLOBAL_GRENADE_CHECK_COOLDOWN = 300; // 每300毫秒只允许一个AI进行手雷轨迹计算
//    // --- 道具购买与使用逻辑 ---
////    /**
////     * 模拟手榴弹的飞行轨迹以预测落点。
////     * @param startAngle 初始投掷角度。
////     * @param fuseMillis 引信时间（毫秒）。
////     * @return 预测的最终落点。如果轨迹不佳，则返回 null。
////     */
//    /** 新版的垃圾 */
//
//    /**
//     * 查找射线与所有障碍物的最近碰撞点
//     * 使用几何算法精确计算射线与各种形状的碰撞，支持矩形、多边形和椭圆的碰撞检测
//     *
//     * @param rayStart 射线起点坐标
//     * @param rayEnd 射线终点坐标
//     * @return 最近的碰撞结果，包含碰撞点和法线向量；如果没有碰撞返回null
//     */
//    private CollisionResult findClosestCollision(Vector2D rayStart, Vector2D rayEnd) {
//        CollisionResult closestCollision = null;      // 存储最近的碰撞结果
//        double minDistanceSq = Double.POSITIVE_INFINITY; // 最小距离平方（初始设为无穷大）
//        List<Shape> obstacles = pathfinder.getObstacles(); // 获取所有障碍物形状
//
//        // 空值安全检查
//        if (obstacles == null) return null;
//
//        // 遍历所有障碍物进行碰撞检测
//        for (Shape obs : obstacles) {
//            // 将AWT Shape统一转换为Vector2D边的列表，实现通用碰撞检测
//            List<Vector2D[]> edges = new ArrayList<>();
//
//            // === 矩形障碍物处理 ===
//            if (obs instanceof Rectangle2D.Double rect) {
//                // 提取矩形参数
//                double x = rect.x, y = rect.y, w = rect.width, h = rect.height;
//                // 计算矩形的四个顶点（顺时针顺序）
//                Vector2D p1 = new Vector2D(x, y);         // 左上
//                Vector2D p2 = new Vector2D(x + w, y);     // 右上
//                Vector2D p3 = new Vector2D(x + w, y + h); // 右下
//                Vector2D p4 = new Vector2D(x, y + h);     // 左下
//                // 添加矩形的四条边
//                edges.add(new Vector2D[]{p1, p2}); // 上边
//                edges.add(new Vector2D[]{p2, p3}); // 右边
//                edges.add(new Vector2D[]{p3, p4}); // 下边
//                edges.add(new Vector2D[]{p4, p1}); // 左边
//
//                // === 多边形障碍物处理 ===
//            } else if (obs instanceof Path2D.Double path) {
//                // 使用PathIterator遍历多边形路径
//                PathIterator pi = path.getPathIterator(null);
//                double[] coords = new double[6]; // 存储路径段坐标
//                Vector2D firstPoint = null, lastPoint = null; // 记录首尾点用于闭合路径
//
//                while (!pi.isDone()) {
//                    int type = pi.currentSegment(coords); // 获取当前路径段类型
//                    Vector2D currentPoint = new Vector2D(coords[0], coords[1]); // 当前点坐标
//
//                    if (type == PathIterator.SEG_MOVETO) {
//                        // 路径开始点
//                        firstPoint = currentPoint;
//                    } else if (type == PathIterator.SEG_LINETO) {
//                        // 直线段：连接上一点到当前点
//                        if(lastPoint != null) edges.add(new Vector2D[]{lastPoint, currentPoint});
//                    } else if (type == PathIterator.SEG_CLOSE) {
//                        // 闭合路径：连接最后一点到第一点
//                        if(lastPoint != null && firstPoint != null) edges.add(new Vector2D[]{lastPoint, firstPoint});
//                    }
//                    lastPoint = currentPoint; // 更新最后一点
//                    pi.next(); // 移动到下一段
//                }
//
//                // === 椭圆障碍物处理 ===
//            } else if (obs instanceof Ellipse2D.Double ellipse) {
//                // 使用16边形近似椭圆（与客户端渲染保持一致）
//                final int numSegments = 16; // 分段数，平衡精度和性能
//                double x = ellipse.x, y = ellipse.y, w = ellipse.width, h = ellipse.height;
//
//                for (int i = 0; i < numSegments; i++) {
//                    // 计算当前分段和下一分段的弧度角
//                    double angle1 = (i / (double) numSegments) * 2 * Math.PI;
//                    double angle2 = ((i + 1) / (double) numSegments) * 2 * Math.PI;
//
//                    // 计算椭圆边界上的两个点（参数方程）
//                    Vector2D p1 = new Vector2D(
//                            x + w/2 + (w/2) * Math.cos(angle1), // 椭圆参数方程：中心点 + 半径*cosθ
//                            y + h/2 + (h/2) * Math.sin(angle1)  // 中心点 + 半径*sinθ
//                    );
//                    Vector2D p2 = new Vector2D(
//                            x + w/2 + (w/2) * Math.cos(angle2),
//                            y + h/2 + (h/2) * Math.sin(angle2)
//                    );
//                    edges.add(new Vector2D[]{p1, p2}); // 添加椭圆边
//                }
//            }
//
//            // === 边碰撞检测 ===
//            // 对当前障碍物的所有边进行射线碰撞检测
//            for (Vector2D[] edge : edges) {
//                // 计算射线与当前边的交点
//                Vector2D intersection = getLineIntersection(rayStart, rayEnd, edge[0], edge[1]);
//
//                if (intersection != null) {
//                    // 计算交点到射线起点的距离平方（避免开方运算优化性能）
//                    double distSq = rayStart.distanceSq(intersection);
//
//                    // 检查是否为更近的碰撞
//                    if (distSq < minDistanceSq) {
//                        minDistanceSq = distSq; // 更新最小距离
//
//                        // ---  碰撞法线计算 ---
//                        // 计算边的方向向量
//                        Vector2D edgeVector = edge[1].subtract(edge[0]);
//                        // 计算边的垂直向量（法线方向）：(dx, dy) → (dy, -dx)
//                        Vector2D normal = new Vector2D(edgeVector.y, -edgeVector.x).normalize();
//
//                        // ---  法线方向校正 ---
//                        // 计算射线方向向量
//                        Vector2D velocityVector = rayEnd.subtract(rayStart);
//                        // 如果法线与射线方向点积为正，说明法线指向射线内部，需要反转
//                        if (normal.dotProduct(velocityVector) > 0) {
//                            normal = normal.multiply(-1.0); // 反转法线方向
//                        }
//
//                        // 创建新的碰撞结果
//                        closestCollision = new CollisionResult(intersection, normal);
//                    }
//                }
//            }
//        }
//        return closestCollision;
//    }
//
//
//    /**
//     * AI在爆破模式冻结时间内的购买逻辑 (已升级)
//     */
//
//    private void makePurchases() {
//        final long MIN_MONEY_AFTER_BUY = 1000; // 定义购买后至少要剩余的钱数
//        // 从指挥官获取本回合的经济策略 (这里假设有一个方法可以获取)
//        // 注意：在实际应用中，你需要在TeamCommander下达指令时将策略传递给AI
//        TeamCommander commander = (self.team == Player.Team.T) ? gameState.getTCommander() : gameState.getCTCommander();
//        TeamCommander.EconomicStrategy strategy = commander.getCurrentEcoStrategy();
//
//        // 手枪局有特殊的购买逻辑，优先级最高
//        if (gameState.currentRound == 1 || gameState.currentRound == 13) {
//
//            boolean isCT = self.team == Player.Team.CT;
//            double strategyRoll = rand.nextDouble(); // 生成一个0.0到1.0的随机数来决定策略
//
//            // --- 策略1：护甲优先 (40%概率) ---
//            if (strategyRoll < 0.40) {
//                if (self.money >= 650) {
//                    gameState.playerBuyItem(self.id, "KEVLAR");
//                    logger.accept("[Pistol Round] " + self.name + " chose strategy: Armor First.");
//                }
//                // 买了甲还剩 $150，不够买任何东西了
//            } else if (strategyRoll < 0.70) {
//                // 优先考虑买沙鹰
//                if (self.money >= 700) {
//                    gameState.playerBuyItem(self.id, "DEAGLE");
//                    logger.accept("[Pistol Round] " + self.name + " chose strategy: Pistol Upgrade (Deagle).");
//                }
//                // 钱不够买沙鹰，但够买FN57或Tec9
//                else if (self.money >= 500) {
//                    String pistolToBuy = isCT ? "FIVESEVEN" : "TEC9";
//                    gameState.playerBuyItem(self.id, pistolToBuy);
//                    logger.accept("[Pistol Round] " + self.name + " chose strategy: Pistol Upgrade (" + pistolToBuy + ").");
//                    // 买了500的手枪后还剩$300，可以买个雷或烟
//                    if (self.money >= 300) {
//                        gameState.playerBuyItem(self.id, isCT ? "HE_GRENADE" : "SMOKE_GRENADE");
//                    }
//                }
//            }
//        }
//
//        // ---  根据团队策略执行购买 ---
//        // AIController.java -> inside makePurchases()
//
//        switch (strategy) {
//            case FULL_BUY:
//                // ======================= [覆盖所有武器的智能全起逻辑] =======================
//                //  购买核心装备：头甲是必须的
//                if (self.money >= 1000) gameState.playerBuyItem(self.id, "KEVLAR_HELMET");
//
//                //  购买主战武器 (分层、分角色、多样化决策)
//                boolean hasBoughtPrimary = false;
//
//                // **决策层 1: 狙击手 (AWPer) - 队伍唯一**
//                boolean teamHasAwper = gameState.getPlayers().stream()
//                        .anyMatch(p -> p.team == self.team && p.primaryWeapon == Weapon.AWP);
//                // 条件：队伍里还没人拿AWP，并且我买完后还能剩下足够的经济缓冲
//                if (!teamHasAwper && self.money >= Weapon.AWP.cost + MIN_MONEY_AFTER_BUY) {
//                    playerChooseWeapon(self.id, "AWP");
//                    hasBoughtPrimary = true;
//                }
//
//                // **决策层 2: 主力步枪手 (Rifler) - 多样性选择**
//                if (!hasBoughtPrimary) {
//                    List<String> riflePreferences = new ArrayList<>();
//                    if (self.team == Player.Team.CT) {
//                        riflePreferences.addAll(Arrays.asList("M4A4", "M4A1S", "AUG", "FAMAS")); // CT步枪池
//                    } else {
//                        riflePreferences.addAll(Arrays.asList("AK47", "SG553", "GALIL")); // T步枪池
//                    }
//                    Collections.shuffle(riflePreferences); // 关键：打乱偏好，让AI每次的选择都可能不同
//
//                    for (String rifleName : riflePreferences) {
//                        Weapon rifle = Weapon.valueOf(rifleName);
//                        if (self.money >= rifle.cost + MIN_MONEY_AFTER_BUY) {
//                            playerChooseWeapon(self.id, rifleName);
//                            hasBoughtPrimary = true;
//                            break;
//                        }
//                    }
//                }
//
//                // **决策层 3: 重火力手 (Heavy) - 小概率战术选择**
//                if (!hasBoughtPrimary && rand.nextDouble() < 0.15) { // 15%的概率成为机枪哥
//                    Weapon lmg = (self.money > Weapon.M249.cost + MIN_MONEY_AFTER_BUY) ? Weapon.M249 : Weapon.NEGEV;
//                    if (self.money >= lmg.cost + MIN_MONEY_AFTER_BUY) {
//                        playerChooseWeapon(self.id, lmg.name());
//                        hasBoughtPrimary = true;
//                    }
//                }
//
//                // **决策层 4: 最后的选择 (鸟狙或沙鹰)**
//                if (!hasBoughtPrimary) {
//                    // 如果连最便宜的步枪都买不起，但钱还很多，就买把鸟狙或沙鹰当副狙/突破手
//                    if (self.money >= Weapon.SSG08.cost + MIN_MONEY_AFTER_BUY) {
//                        playerChooseWeapon(self.id, "SSG08");
//                        hasBoughtPrimary = true;
//                    } else if (self.money >= Weapon.DEAGLE.cost + 2000) { // 买沙鹰需要更多经济缓冲
//                        playerChooseWeapon(self.id, "DEAGLE");
//                        // 注意：沙鹰是副武器，所以hasBoughtPrimary仍然是false
//                    }
//                }
//
//                // 3. 用所有剩余的钱，按优先级购买钳子和道具
//                if (self.team == Player.Team.CT && self.money >= 400 && !self.hasDefuseKit && rand.nextDouble() < 0.5) { // 50%概率
//                    gameState.playerBuyItem(self.id, "DEFUSE_KIT");
//                }
//                if (self.money >= 600) gameState.playerBuyItem(self.id, self.team == Player.Team.T ? "MOLOTOV" : "INCENDIARY");
//                if (self.money >= 300) gameState.playerBuyItem(self.id, "SMOKE_GRENADE");
//                if (self.money >= 200) gameState.playerBuyItem(self.id, "FLASHBANG");
//                if (self.money >= 200) gameState.playerBuyItem(self.id, "FLASHBANG"); // 如果钱够，买第二颗闪
//
//                break;
//            // ========================================================================
//
//            case FORCE_BUY:
//                // ======================= [ 覆盖所有经济型武器的强起逻辑] =======================
//
//                //  核心装备：甲，保命要紧
//                if (self.money >= 650) gameState.playerBuyItem(self.id, "KEVLAR");
//
//                //  用剩下的钱，从“性价比武器池”里买能买得起的最贵的那个
//                if (self.money >= 2500) playerChooseWeapon(self.id, "P90"); // P90是强起局大杀器
//                else if (self.money >= 2100) playerChooseWeapon(self.id, "XM1014"); // 连喷
//                else if (self.money >= 1900) playerChooseWeapon(self.id, self.team == Player.Team.CT ? "FAMAS" : "GALIL");
//                else if (self.money >= 1800) playerChooseWeapon(self.id, "SSG08"); // 鸟狙也是强起翻盘利器
//                else if (self.money >= 1600) playerChooseWeapon(self.id, "MP7");
//                else if (self.money >= 1400) playerChooseWeapon(self.id, "BIZON");
//                else if (self.money >= 1300 && self.team == Player.Team.CT) playerChooseWeapon(self.id, "MAG7");
//                else if (self.money >= 1200) playerChooseWeapon(self.id, "UMP45");
//                else if (self.money >= 1100 && self.team == Player.Team.T) playerChooseWeapon(self.id, "SAWEDOFF");
//                else if (self.money >= 1050 && self.team == Player.Team.T) playerChooseWeapon(self.id, "MAC10");
//                else if (self.money >= 1250 && self.team == Player.Team.CT) playerChooseWeapon(self.id, "MP9");
//                else if (self.money >= 1050) playerChooseWeapon(self.id, "NOVA");
//                else if (self.money >= 700) playerChooseWeapon(self.id, "DEAGLE"); // 实在没钱就只能沙鹰了
//
//                //   如果还有一丁点余钱，买颗闪光弹也许能创造奇迹
//                if (self.money >= 200) gameState.playerBuyItem(self.id, "FLASHBANG");
//
//                break;
//            // ======================================================================================
//
//            case HERO_RIFLE:
//                // --- 英雄枪局 (逻辑不变) ---
//                Player hero = commander.getRichestPlayer();
//                if (self == hero) {
//                    logger.accept("[HERO RIFLE] " + self.name + " is the designated hero.");
//                    if (self.money >= 1000) gameState.playerBuyItem(self.id, "KEVLAR_HELMET");
//                    // 英雄的购买逻辑也应该智能，而不是无脑买最贵的
//                    if (self.money >= 4750) playerChooseWeapon(self.id, "AWP");
//                    else if (self.money >= 3100) playerChooseWeapon(self.id, self.team == Player.Team.CT ? "M4A4" : "AK47");
//                    else if (self.money >= 2050) playerChooseWeapon(self.id, self.team == Player.Team.CT ? "FAMAS" : "GALIL");
//                } else {
//                    if (self.money >= 300) gameState.playerBuyItem(self.id, "SMOKE_GRENADE");
//                }
//                break;
//
//            case ECO:
//                // --- 纯经济局 ---
//                if (self.money >= 500 && self.money < 1500) {
//                    playerChooseWeapon(self.id, self.team == Player.Team.CT ? "FIVESEVEN" : "TEC9");
//                }
//                break;
//        }
//        hasBoughtThisRound = true;
//    }
//
//    // 辅助方法，为了能在AIController里调用 (或者你可以把playerChooseWeapon移到GameState里)
//    private void playerChooseWeapon(String playerId, String weaponName) {
//        gameState.playerChooseWeapon(playerId, weaponName);
//    }
//
//    /**
//     * [旧版]智能投掷检查方法
//     * 检查从AI位置到目标点的直接路径，如果被阻挡，则尝试寻找一个能绕过障碍物的投掷角度。
//     * @param targetPos 期望的道具落点
//     * @return 一个包含通畅投掷角度的Optional，如果找不到则为空
//     */
//    private Optional<Double> findClearThrowingAngle(Point2D.Double targetPos) {
//        if (targetPos == null) {
//            return Optional.empty();
//        }
//
//        //   检查直接路径
//        double directAngle = Math.atan2(targetPos.y - self.position.y, targetPos.x - self.position.x);
//        if (hasLineOfSight(targetPos)) {
//            return Optional.of(directAngle); // 直接路径通畅，直接返回
//        }
//
//        //  如果直接路径被挡，尝试向两侧微调角度寻找出路
//        double searchStep = Math.toRadians(5.0); // 每次偏转5度
//        double maxSearchAngle = Math.toRadians(45.0); // 最大搜索范围为两侧各45度
//
//        for (double offset = searchStep; offset <= maxSearchAngle; offset += searchStep) {
//            // 检查右侧角度
//            double rightAngle = directAngle + offset;
//            Point2D.Double rightTarget = new Point2D.Double(
//                    self.position.x + Math.cos(rightAngle) * 1000, // 投射一条长射线
//                    self.position.y + Math.sin(rightAngle) * 1000
//            );
//            if (hasLineOfSight(rightTarget)) {
//                // 找到了！返回这个能绕开障碍物的角度
//                return Optional.of(rightAngle);
//            }
//
//            // 检查左侧角度
//            double leftAngle = directAngle - offset;
//            Point2D.Double leftTarget = new Point2D.Double(
//                    self.position.x + Math.cos(leftAngle) * 1000,
//                    self.position.y + Math.sin(leftAngle) * 1000
//            );
//            if (hasLineOfSight(leftTarget)) {
//                // 找到了！返回这个能绕开障碍物的角度
//                return Optional.of(leftAngle);
//            }
//        }
//
//        //  如果左右45度都找不到出路，放弃投掷
//        return Optional.empty();
//    }
//
//
//    /**
//     * [自动计算] 核心辅助方法：为给定的道具和目标点，搜索最佳的投掷角度。
//     * @param item 要投掷的道具
//     * @param targetPos 目标位置
//     * @return 一个包含最佳角度和落点误差的 Optional<Pair>，如果找不到则为空。
//     */
//    private Optional<Map.Entry<Double, Double>> findBestThrowAngle(Item item, Point2D.Double targetPos) {
//        long fuseMillis = switch (item) {
//            case HE_GRENADE, FLASHBANG, DECOY -> 2000;
//            case SMOKE_GRENADE -> 1500;
//            case MOLOTOV, INCENDIARY -> 1000;
//            default -> 0;
//        };
//        if (fuseMillis == 0) return Optional.empty();
//
//        double bestAngle = -1;
//        double minLandingError = Double.MAX_VALUE;
//
//        // CHANGED: 将目标点也转换为 Vector2D 进行计算
//        Vector2D targetVec = new Vector2D(targetPos);
//
//        double directAngleToTarget = Math.atan2(targetVec.y - self.position.y, targetVec.x - self.position.x);
//        // AI搜索的范围从 -72*2度到 +144度 + 每隔0.5度搜索一次
//        int searchSteps = 36;
//        double angleIncrement = Math.toRadians(0.5);
//
//        for (int i = -searchSteps; i <= searchSteps; i++) {
//            double currentAngle = directAngleToTarget + (i * angleIncrement);
//
//            // CHANGED: 接收 Vector2D 类型的返回值
//            Vector2D predictedLandingPoint = simulateGrenadePath(currentAngle, fuseMillis);
//
//            if (predictedLandingPoint != null) {
//                // CHANGED: 使用 Vector2D 进行距离计算
//                double landingError = predictedLandingPoint.distance(targetVec);
//                if (landingError < minLandingError) {
//                    minLandingError = landingError;
//                    bestAngle = currentAngle;
//                }
//            }
//        }
//
//        if (bestAngle != -1) {
//            return Optional.of(new AbstractMap.SimpleEntry<>(bestAngle, minLandingError));
//        }
//        return Optional.empty();
//    }
//
//
//    /**
//     * 模拟手榴弹的飞行轨迹以预测落点。
//     * 此版本已完全使用 Vector2D 并调用重构后的碰撞检测，与客户端逻辑同步。
//     */
//    private Vector2D simulateGrenadePath(double startAngle, long fuseMillis) {
//        // --- 初始化 (与客户端同步) ---
//        final double initialSpeed = 8.0;
//        final double bounceAttenuation = 0.85;
//        final double epsilon = 0.1; // 防止卡墙的微小推离量
//
//        // REASON: 所有位置和速度都使用 Vector2D
//        Vector2D currentPos = new Vector2D(self.position);
//        double throwVx = Math.cos(startAngle) * initialSpeed;
//        double throwVy = Math.sin(startAngle) * initialSpeed;
//        Vector2D velocity = new Vector2D(throwVx + self.vx, throwVy + self.vy);
//
//        int maxSteps = (int) (fuseMillis / 1000.0 * GameState.SERVER_TICKRATE);
////        logger.accept(String.format("--- GHOST SIMULATION START --- Bot: %s, Angle: %.2f, StartPos: (%.1f, %.1f), StartVel: (%.2f, %.2f)", self.name, Math.toDegrees(startAngle), currentPos.x, currentPos.y, velocity.x, velocity.y));
//        // ---  开始模拟 (与客户端同步) ---
//        for (int i = 0; i < maxSteps; i++) {
//            Vector2D nextPos = currentPos.add(velocity);
//
//            // CHANGED: 调用全新的、可靠的碰撞检测方法
//            CollisionResult collision = findClosestCollision(currentPos, nextPos);
//
//            if (collision != null) {
//                // REASON: 所有碰撞响应都使用 Vector2D 运算
//                Vector2D impactPoint = collision.impactPoint();
//                Vector2D normal = collision.normal();
//
//                //  向量反射: v' = v - 2 * (v . n) * n
//                double dot = velocity.dotProduct(normal);
//                velocity = velocity.subtract(normal.multiply(2 * dot));
//
//                //  速度衰减
//                velocity = velocity.multiply(bounceAttenuation);
//
//                //   Epsilon推离，防止“卡”进墙里
//                currentPos = impactPoint.add(normal.multiply(epsilon));
//
//            } else {
//                // 没有碰撞，正常移动
//                currentPos = nextPos;
//            }
//
//            //  边界处理
//            if (currentPos.x <= 0 || currentPos.x >= gameState.width) {
//                velocity.x *= -bounceAttenuation;
//                currentPos.x = Math.max(1, Math.min(gameState.width - 1, currentPos.x));
//            }
//            if (currentPos.y <= 0 || currentPos.y >= gameState.height) {
//                velocity.y *= -bounceAttenuation;
//                currentPos.y = Math.max(1, Math.min(gameState.height - 1, currentPos.y));
//            }
//
//            //   终止条件
//            if (velocity.x * velocity.x + velocity.y * velocity.y < 0.1) {
//                break;
//            }
//            // ================== [记录每一步] ==================
////            logger.accept(String.format("GHOST TICK %d: Pos(%.1f, %.1f), Vel(%.2f, %.2f)", i, currentPos.x, currentPos.y, velocity.x, velocity.y));
//        }
////        logger.accept("--- GHOST SIMULATION END --- FinalPos: (" + (int)currentPos.x + "," + (int)currentPos.y + ")");
//
//        // ---   返回最终结果 ---
//        // 如果落点离自己太近，视为一次无效/危险的投掷，返回null
//        if (new Vector2D(self.position).distanceSq(currentPos) < (Player.SIZE * 3) * (Player.SIZE * 3)) {
//            return null;
//        }
//        return currentPos;
//    }
//
//    /**
//     * AI的核心战术道具决策方法
//     */
////    private boolean useTacticalGrenade() {
////        if (self.equipment.isEmpty() || target == null) return false;
////
////        long currentTime = System.currentTimeMillis();
////        // [重要] 遵守全局冷却，确保昂贵的计算不会扎堆
////        if (currentTime - lastGlobalGrenadeCheckTime < GLOBAL_GRENADE_CHECK_COOLDOWN) {
////            return false;
////        }
////        if (gameState.getGameMode() == GameMode.DEMOLITION && gameState.getRoundPhase() == GameState.RoundPhase.FREEZE_TIME) return false;
////        if (currentTime - lastGrenadeThrowTime < GRENADE_COOLDOWN_MS) return false;
////
////        // 取得计算许可
////        lastGlobalGrenadeCheckTime = currentTime;
////
////        Point2D.Double objectivePos = lastKnownPosition != null ? lastKnownPosition : target.position;
////        boolean targetInLoS = hasLineOfSight(target.position);
////        double distanceToTarget = self.position.distance(target.position);
////
////        for (Item item : self.equipment.keySet()) {
//////            logger.accept(String.format(" --> [%s] 正在评估道具: %s", self.name, item.name()));
////
////            Point2D.Double finalObjectivePos = objectivePos; // 创建一个临时变量，防止修改原始objectivePos
////            if (item == Item.SMOKE_GRENADE && gameState.getGameMode() != GameMode.DEMOLITION) {
////                if (self.health < 50 || isSeeingMultipleEnemiesRush()) {
////                    finalObjectivePos = new Point2D.Double((self.position.x + target.position.x) / 2, (self.position.y + target.position.y) / 2);
//////                    logger.accept(String.format(" ---> [%s] 触发防御性烟雾逻辑，目标点修正为中点。", self.name));
////                }
////            }
////
////            Optional<Map.Entry<Double, Double>> throwSolution = findBestThrowAngle(item, finalObjectivePos);
////
////            if (throwSolution.isPresent()) {
////                double landingError = throwSolution.get().getValue();
////                double acceptableError = 80.0;  //允许的最小误差
//////                logger.accept(String.format(" ---> [%s] 轨迹计算完成。最佳方案误差为: %.1f (要求小于 %.1f)", self.name, landingError, acceptableError));
////
////                if (landingError < acceptableError) {
//////                    logger.accept(String.format(" ----> [%s] 误差在接受范围内。开始进行战术判断...", self.name));
////                    boolean shouldThrow = false;
////                    switch (item) {
////                        case FLASHBANG: {
////                            boolean tdmCondition = (gameState.getGameMode() == GameMode.TEAM_DEATHMATCH && targetInLoS && rand.nextDouble() < 0.25);
////                            boolean generalCondition = (targetInLoS && distanceToTarget > LONG_RANGE_FLASH_MIN_DISTANCE) || (!targetInLoS && distanceToTarget < 600);
////                            if (tdmCondition || generalCondition) { shouldThrow = true; }
////                            break;
////                        }
////                        case HE_GRENADE: case MOLOTOV: case INCENDIARY: {
////                            boolean tdmCondition = (gameState.getGameMode() == GameMode.TEAM_DEATHMATCH && targetInLoS && distanceToTarget > 250 && rand.nextDouble() < 0.35);
////                            boolean generalCondition = (isSeeingMultipleEnemiesRush() || !targetInLoS);
////                            if (tdmCondition || generalCondition) { shouldThrow = true; }
////                            break;
////                        }
////                        case SMOKE_GRENADE:
////                            if (gameState.getGameMode() == GameMode.DEMOLITION && self.team == Player.Team.T && currentState == AIState.MOVING_TO_SITE) {
////                                shouldThrow = true;
////                            } else if (gameState.getGameMode() != GameMode.DEMOLITION && (self.health < 50 || isSeeingMultipleEnemiesRush())) {
////                                shouldThrow = true;
////                            }
////                            break;
////                    }
//////                    logger.accept(String.format(" -----> [%s] 战术判断结束。最终决策: shouldThrow = %b", self.name, shouldThrow));
////
////                    if (shouldThrow) {
////
////                        // --- 执行昂贵的计算 ---
////                        Optional<Map.Entry<Double, Double>> throwSolutionOptional = findBestThrowAngle(item, objectivePos);
////
////                        // 如果计算成功，并且误差在接受范围内
////                        if (throwSolutionOptional.isPresent() && throwSolutionOptional.get().getValue() <= 80.0) {
////                            // --- [核心] 保存完整的投掷计划 ---
////                            this.grenadeToThrow = item;
////                            this.grenadeTargetPosition = objectivePos;
////                            this.grenadeDecisionPosition = (Point2D.Double) self.position.clone(); // 记录下当前的“完美投掷点”
////                            this.grenadeDecisionAngle = throwSolutionOptional.get().getKey();              // 记录下计算出的“完美投掷角度”
////
////                            // 进入准备投掷的初始状态
////                            this.currentState = AIState.PREPARING_GRENADE;
////                            self.switchToSlot(getSlotForItem(item)); // 切换到道具
////
////                            logger.accept(String.format("%s has a plan! Moving to throw %s from (%.1f, %.1f)",
////                                    self.name, item.name(), grenadeDecisionPosition.x, grenadeDecisionPosition.y));
////
////                            return true; // 决策成功，结束本帧
////                        }
////                    }
////                }
////            } else {
//////                logger.accept(String.format(" ---> [%s] 轨迹计算失败，找不到任何有效的投掷路径。", self.name));
////            }
////        }
//////        logger.accept(String.format(" -> [%s] 遍历完所有道具，未找到合适的投掷机会。决策结束。", self.name));
////        return false;
////    }
//
//    /**
//     * [异步执行] 核心战术决策与轨迹计算方法。
//     * 这个方法将在后台线程中被调用，以避免阻塞游戏主线程。
//     * 它完整地包含了您原始代码中的所有战术判断逻辑。
//     * @return 如果找到合适的投掷机会并计算成功，则返回一个GrenadeThrowPlan；否则返回null。
//     */
//    private GrenadeThrowPlan calculateGrenadeThrow() {
//        //  基础安全检查
//        // 这些检查是低成本的，可以提前否决，避免不必要的计算。
//        if (self.equipment.isEmpty() || target == null) return null;
//        if (gameState.getGameMode() == GameMode.DEMOLITION && gameState.getRoundPhase() == GameState.RoundPhase.FREEZE_TIME) return null;
//        if (System.currentTimeMillis() - lastGrenadeThrowTime < GRENADE_COOLDOWN_MS) return null;
//
//        // 获取当前战术态势信息
//        Point2D.Double objectivePos = lastKnownPosition != null ? lastKnownPosition : target.position;
//        boolean targetInLoS = hasLineOfSight(target.position);
//        double distanceToTarget = self.position.distance(target.position);
//
//        // 遍历所有可用道具，进行逐一评估
//        for (Item item : self.equipment.keySet()) {
//
//            Point2D.Double finalObjectivePos = objectivePos;
//            boolean shouldConsiderThrowing = false;
//
//            // [核心] 执行特定道具的战术评估逻辑
//            switch (item) {
//                case FLASHBANG: {
//                    // TDM模式下，有75%概率对视野内的敌人扔闪光。
//                    boolean tdmCondition = (gameState.getGameMode() == GameMode.TEAM_DEATHMATCH && targetInLoS && rand.nextDouble() < 0.75);
//                    // 通用逻辑：对远距离的可见敌人，或近距离的不可见敌人（例如在掩体后）使用闪光。
//                    boolean generalCondition = (targetInLoS && distanceToTarget > LONG_RANGE_FLASH_MIN_DISTANCE) || (!targetInLoS && distanceToTarget < 600);
//
//                    if (tdmCondition || generalCondition) {
//                        shouldConsiderThrowing = true;
//                    }
//                    break;
//                }
//                case HE_GRENADE:
//                case MOLOTOV:
//                case INCENDIARY: {
//                    // TDM模式下，有85%的概率对中远距离的敌人使用伤害性道具。
//                    boolean tdmCondition = (gameState.getGameMode() == GameMode.TEAM_DEATHMATCH && targetInLoS && distanceToTarget > 250 && rand.nextDouble() < 0.85);
//                    // 通用逻辑：当看到多个敌人冲锋，或者敌人躲在掩体后时，使用伤害性道具。
//                    boolean generalCondition = isSeeingMultipleEnemiesRush() || !targetInLoS;
//
//                    //僵尸模式专属逻辑：只要看到2个或以上的僵尸，就考虑扔范围伤害道具
//                    boolean zombieCondition = (gameState.getGameMode() == GameMode.ZOMBIE_MODE && countVisibleEnemies() >= 2 && distanceToTarget > 200);
//
//                    if (tdmCondition || generalCondition || zombieCondition) {
//                        shouldConsiderThrowing = true;
//                    }
//                    break;
//                }
//                case SMOKE_GRENADE: {
//                    // 爆破模式T方：在进攻包点的路上，使用烟雾弹封锁关键枪线。
//                    if (gameState.getGameMode() == GameMode.DEMOLITION && self.team == Player.Team.T && currentState == AIState.MOVING_TO_SITE) {
//                        shouldConsiderThrowing = true;
//                    }
//                    // 其他模式或情境下的防御性烟雾：当血量低或被多人冲锋时，在自己脚下扔烟以求自保。
//                    else if (gameState.getGameMode() != GameMode.DEMOLITION && (self.health < 50 || isSeeingMultipleEnemiesRush())) {
//                        finalObjectivePos = new Point2D.Double(self.position.x, self.position.y);
//                        shouldConsiderThrowing = true;
//                    }
//                    break;
//                }
//                case DECOY:shouldConsiderThrowing = true;
//                default:
//                    // 对于其他道具，这里没有定义战术，所以直接跳过。
//                    break;
//            }
//
//            //   --- 执行昂贵的轨迹计算 ---
//            // 只有在通过了前面的战术评估后 (shouldConsiderThrowing == true)，才值得花费CPU资源去进行实际的物理轨迹模拟。
//            if (shouldConsiderThrowing) {
//                Optional<Map.Entry<Double, Double>> throwSolution = findBestThrowAngle(item, finalObjectivePos);
//
//                // 如果计算找到了一个误差在50单位以内的“好”路径
//                if (throwSolution.isPresent() && throwSolution.get().getValue() <= 50.0) {
//                    // 决策成功！打包所有信息，形成一个完整的“投掷计划”并返回。 后台线程的工作到此结束。
//                    double bestAngle = throwSolution.get().getKey();
//                    Point2D.Double decisionPos = (Point2D.Double) self.position.clone(); // 记录下决策时的“理想投掷点”
//
//                    logger.accept(String.format("Background Calc OK for %s: Found a valid throw plan for %s.", self.name, item.name()));
//                    return new GrenadeThrowPlan(item, decisionPos, bestAngle, finalObjectivePos);
//                }
//            }
//        } // -- 道具遍历循环结束 --
//
//        // 如果遍历完所有道具，都没有找到合适的投掷机会，则返回 null。
//        return null;
//    }
//
//
//    /**
//     * 辅助方法：计算视野内有多少个活着的敌人。
//     * @return 视野内可见的敌人数量。
//     */
//    private long countVisibleEnemies() {
//        if (knownPlayers == null) return 0;
//        return knownPlayers.stream()
//                .filter(p -> p.team != self.team && p.isAlive() && hasLineOfSight(p.position))
//                .count();
//    }
//
//    //  useTacticalGrenade 方法负责提交任务
//    private boolean useTacticalGrenade() {
//        // 如果已经有一个待执行的计划，或者正在计算中，则不再提交新任务
//        if (pendingGrenadePlan != null || isCalculatingGrenade) {
//            return false;
//        }
//
//        // 全局冷却检查仍然保留，防止所有AI同时提交任务
//        long currentTime = System.currentTimeMillis();
//        if (currentTime - lastGlobalGrenadeCheckTime < GLOBAL_GRENADE_CHECK_COOLDOWN) {
//            return false;
//        }
//        lastGlobalGrenadeCheckTime = currentTime;
//
//        // 标记为正在计算，防止本AI在下一帧重复提交
//        isCalculatingGrenade = true;
//
//        // 提交一个 lambda 表达式作为计算任务
//        grenadeCalculatorService.submit(() -> {
//            try {
//                // 在后台线程中执行耗时的计算
//                GrenadeThrowPlan plan = calculateGrenadeThrow();
//
//                // 如果计算成功，将结果存放到 pendingGrenadePlan 中
//                // 主线程会在未来的更新中检查并使用这个计划
//                if (plan != null) {
//                    this.pendingGrenadePlan = plan;
//                }
//            } catch (Exception e) {
//                // 异常处理，以防计算过程中断
//                e.printStackTrace();
//            } finally {
//                // 无论成功与否，都要重置计算标记，允许AI未来再次进行决策
//                isCalculatingGrenade = false;
//            }
//        });
//
//        // 立即返回 false，因为决策还未做出，不能中断当前攻击等行为
//        return false;
//    }
//
//    // 辅助方法，根据Item获取槽位
//    private int getSlotForItem(Item item) {
//        return switch (item) {
//            case FLASHBANG -> 6;
//            case HE_GRENADE -> 7;
//            case SMOKE_GRENADE -> 8;
//            case MOLOTOV, INCENDIARY -> 9;
//            case DECOY -> 10;
//            default -> -1;
//        };
//    }
//
//    /**
//     * 处理投掷道具的后续动作
//     */
//    private void handleGrenadePreparation() {
//        // 确保有投掷计划，否则立即中止
//        if (grenadeToThrow == null || grenadeDecisionPosition == null) {
//            // 发生意外情况，重置状态
//            resetGrenadeState();
//            return;
//        }
//
//        //  检查是否已切换到正确的道具槽位
//        if (self.currentSlot != getSlotForItem(grenadeToThrow)) {
//            // 还没切换好，等待 (也可以在这里加入超时放弃的逻辑)
//            return;
//        }
//
//        // 允许的位置误差（半个身位），在这个范围内就认为是在“正确地点”
//        final double POSITION_TOLERANCE = Player.SIZE / 2.0;
//
//        //  检查当前位置是否在“预定投掷点”的误差范围内
//        if (self.position.distance(grenadeDecisionPosition) <= POSITION_TOLERANCE) {
//            // --- 情况A: 在正确的位置，准备原地投掷 ---
//            clearPath(); // 确保AI停下所有移动意图
//
//            // 等待物理实体完全停稳
//            boolean isStationary = (self.vx * self.vx + self.vy * self.vy < 0.1);
//            if (isStationary) {
//                // 已停稳，执行强制转身并投掷
//
//                // [日志] 只在投掷瞬间记录投掷终点
//                logger.accept(String.format("%s is throwing %s to target (%.1f, %.1f)",
//                        self.name,
//                        grenadeToThrow.name(),
//                        grenadeTargetPosition.x,
//                        grenadeTargetPosition.y
//                ));
//
//                // 强制瞬时转身
//                this.targetAngle = this.grenadeDecisionAngle;
//                this.currentAngle = this.grenadeDecisionAngle;
//                self.angle = this.grenadeDecisionAngle;
//
//                // 执行投掷
//                gameState.throwGrenade(self);
//
//                // 投掷完成，重置所有状态
//                resetGrenadeState();
//            }
//            // 如果还没停稳，则这一帧什么都不做，等待下一帧继续检查
//
//        } else {
//            // --- 情况B: 不在正确的位置，需要“走回去” ---
//            // 将状态切换为移动，并设置寻路回到“预定投掷点”
//            this.currentState = AIState.MOVING_TO_THROW_SPOT;
//            setNewPath(grenadeDecisionPosition);
//        }
//    }
//
//    /**
//     * 辅助方法，用于清理所有与手雷投掷相关的状态变量。
//     */
//    private void resetGrenadeState() {
//        this.lastGrenadeThrowTime = System.currentTimeMillis();
//        this.grenadeToThrow = null;
//        this.grenadeTargetPosition = null;
//        this.grenadeDecisionPosition = null;
//        this.grenadeDecisionAngle = 0;
//
//        // 在这里将计时器重置为0，是确保机制能正常工作的关键。
//        this.grenadePlanStartTime = 0;
//
//        // 投掷后根据情况可以回到攻击或巡逻状态
//        this.currentState = target != null ? AIState.ATTACKING : AIState.PATROLLING;
//    }
//
//    /**[旧版]
//     * 检查AI的实际朝向是否已基本对准目标朝向
//     * @return 如果角度差在容忍范围内，返回true
//     */
//    private boolean isAimAlmostAligned() {
//        if (self.angle == this.targetAngle) {
//            return true;
//        }
//        // 定义一个容忍度
//        final double ANGLE_TOLERANCE = Math.toRadians(0.2);  //容忍度是0.2度
//
//        // 规范化角度到 -PI 到 +PI 范围，方便计算最短角位移
//        double current = self.angle;
//        double target = this.targetAngle;
//        while (target <= -Math.PI) target += 2 * Math.PI;
//        while (target > Math.PI)  target -= 2 * Math.PI;
//        while (current <= -Math.PI) current += 2 * Math.PI;
//        while (current > Math.PI)  current -= 2 * Math.PI;
//
//        double angleDifference = target - current;
//        if (angleDifference > Math.PI) angleDifference -= 2 * Math.PI;
//        if (angleDifference < -Math.PI) angleDifference += 2 * Math.PI;
//
//        return Math.abs(angleDifference) < ANGLE_TOLERANCE;
//    }
//    // 辅助方法
//    private boolean isSeeingMultipleEnemiesRush() {
//        if(target == null) return false;
//        long rushers = knownPlayers.stream()
//                .filter(p -> p.team != self.team && p.isAlive() && hasLineOfSight(p.position) && p.position.distance(target.position) < 300)
//                .count();
//        return rushers >= 2;
//    }
//
//
//
//    /**
//     * 优化部分
//     */
//
//    private long nextTacticalUpdateTime = 0; // AI 下一次可以进行战术决策的时间戳
//    /**
//     *  计算两条线段的交点，纯 Vector2D 运算，用于复刻客户端逻辑。
//     * @param p1 线段A的起点
//     * @param p2 线段A的终点
//     * @param p3 线段B的起点
//     * @param p4 线段B的终点
//     * @return Vector2D 交点，如果两条线段不相交则返回 null。
//     */
//    private Vector2D getLineIntersection(Vector2D p1, Vector2D p2, Vector2D p3, Vector2D p4) {
//        double den = (p1.x - p2.x) * (p3.y - p4.y) - (p1.y - p2.y) * (p3.x - p4.x);
//        if (den == 0) {
//            return null; // 平行或共线
//        }
//
//        double t = ((p1.x - p3.x) * (p3.y - p4.y) - (p1.y - p3.y) * (p3.x - p4.x)) / den;
//        double u = -((p1.x - p2.x) * (p1.y - p3.y) - (p1.y - p2.y) * (p1.x - p3.x)) / den;
//
//        //  交点在两条线段上
//        if (t >= 0 && t <= 1 && u >= 0 && u <= 1) {
//            return new Vector2D(p1.x + t * (p2.x - p1.x), p1.y + t * (p2.y - p1.y));
//        }
//
//        return null; // 交点不在区段内
//    }
//
//
//
//    /**
//     *设置此 AI 控制器的启用/禁用状态。
//     * @param enabled 如果为 true，AI将正常思考和行动；如果为 false，AI将停止所有活动。
//     */
//    public void setEnabled(boolean enabled) {
//        this.isEnabled = enabled;
//        if (!enabled) {
//            if (self != null) {
//                self.ax = 0;
//                self.ay = 0;
//                self.isShooting = false;
//            }
//            clearPath(); // 清除寻路路径
//        }
//    }
//
//    /**
//     * 将 AIState 枚举转换为易读的中文描述。
//     * @param state AIState 枚举值
//     * @return 对应的中文描述 (包含目标信息)
//     */
//    private String getChineseStateName(AIState state) {
//        String objectiveStr = formatObjective(this.assignedObjective);
//
//        // 获取最后已知敌人的位置信息（用于追击）
//        String lastKnownStr = formatObjective(this.lastKnownPosition);
//
//        return switch (state) {
//            case MOVING_TO_SITE -> "移动到包点: " + objectiveStr;
//            case DEFENDING_SITE -> "防守包点: " + objectiveStr;
//            case ROTATING -> "转点中 (去 " + objectiveStr + ")";
//            case RETRIEVING_BOMB -> "去捡C4 (在 " + objectiveStr + ")";
//            case GUARDING_BOMB -> "防守掉落C4 (在 " + objectiveStr + ")";
//
//            case HUNTING -> "追击目标 (最后在 " + lastKnownStr + ")";
//            case TAKING_COVER -> "寻找掩护 (远离 " + lastKnownStr + ")";
//            case UNSTUCKING -> "脱困中 (卡住)";
//            case BLIND_RETALIATION -> "盲射反击 (瞄准 " + lastKnownStr + ")";
//
//            case PLANTING_BOMB -> "安放C4中 (" + objectiveStr + ")";
//            case DEFUSING_BOMB -> "拆除C4中"; // 拆包目标是固定的C4位置
//
//            case PREPARING_GRENADE -> "准备投掷道具"; // 投掷目标在 handleGrenadePreparation 内部
//            case MOVING_TO_THROW_SPOT -> "移动到投掷点"; // 投掷点是临时坐标，不需要详细显示
//
//            case RETREATING -> "战术性撤退";
//            case RUSHING_BOMBSITE -> "冲锋包点: " + objectiveStr;
//
//            case ATTACKING -> "攻击中/交火";
//            case PATROLLING -> "巡逻中";
//            case IDLE -> "空闲/待命";
//        };
//    }
//
//
//    /**
//     * 提供一个公共方法来获取 AI 当前状态的名称 (已强化)。
//     * 用于服务器管理面板的实时显示。
//     * @return 包含任务、战斗、位置信息的详细状态字符串。
//     */
//    public String getCurrentStateName() {
//        if (!isEnabled) {
//            return "【禁用】被玩家接管或强制冻结";
//        }
//
//        // --- 提取核心上下文信息 ---
//        String baseState = getChineseStateName(this.currentState);
//        String context = "";
//
//        // 武器信息
//        String weaponName = self.getCurrentWeapon() != null ? self.getCurrentWeapon().name : "KNIFE";
//
//        if (this.tacticRole == TacticRole.SAVER) {
//            return String.format("【经济模式】保枪中 | HP:%d | 武器:%s",
//                    self.health,
//                    weaponName);
//        }
//        // 目标信息
//        boolean hasTarget = (target != null && target.isAlive());
//        String targetName = hasTarget ? target.name : "N/A";
//
//        // 角色信息（只在需要时显示，避免冗余）
//        String roleInfo = (tacticRole != TacticRole.IDLE && tacticRole != TacticRole.DEFENDER) ?
//                " | 角色:" + tacticRole.name() : "";
//
//        // --- 根据主要状态类型，组合详细信息 ---
//        switch (this.currentState) {
//            case PLANTING_BOMB:
//            case DEFUSING_BOMB:
//                context = String.format(" | 目标:%s | 进度:%.1fs",
//                        formatObjective(gameState.getBombPosition()),
//                        (System.currentTimeMillis() - self.interactionStartTime) / 1000.0);
//                break;
//
//            case ATTACKING:
//                if (hasTarget) {
//                    if (self.getCurrentWeapon() == null) {
//                        return "无武器";
//                    }
//                    // 判断武器类型
//                    String wepType = self.getCurrentWeapon().getWeaponType().isLongRange() ? "长枪" : "短枪";
//
//                    context = String.format(" | 目标:%s | 武器:%s",
//                            targetName,
//                            target.health,
//                            wepType);
//                }
//                break;
//
//            case DEFENDING_SITE:
//                // 假设我们有一个简单的 checkHasCover() 方法
//                boolean hasCover = (findCover(getCenter(gameState.getBombSiteA())) != null) || (findCover(getCenter(gameState.getBombSiteB())) != null);
//                context = String.format(" | 位置:%s | 掩护:%s",
//                        formatObjective(this.assignedObjective),
//                        hasCover ? "有" : "无");
//                break;
//
//            case MOVING_TO_SITE:
//            case ROTATING:
//                context = String.format(" | 目标:%s %s",
//                        formatObjective(this.tacticalObjective),
//                        roleInfo);
//                break;
//
//            case HUNTING:
//                context = String.format(" | 最后目标:%s | 武器:%s",
//                        formatObjective(this.lastKnownPosition),
//                        weaponName);
//                break;
//
//            case PREPARING_GRENADE:
//                context = String.format(" | 投掷:%s | 目标:%s",
//                        this.grenadeToThrow != null ? this.grenadeToThrow.name() : "N/A",
//                        formatObjective(this.grenadeTargetPosition));
//                break;
//
//
//            default:
//                context = "";
//                break;
//        }
//
//        return baseState + context;
//    }
//
//    /**
//     * 格式化 AI 的目标点为易读的字符串。
//     * @param objective AI 的目标坐标点。
//     * @return 格式化的目标名称（例如：A点, B点, (x, y)）。
//     */
//    private String formatObjective(Point2D.Double objective) {
//        if (objective == null) {
//            return "未知目标";
//        }
//
//        Point2D.Double siteA = getCenter(gameState.getBombSiteA());
//        Point2D.Double siteB = getCenter(gameState.getBombSiteB());
//
//        // 假设 A/B 点的坐标是固定的，并且可以通过 distanceSq 比较
//        final double SITE_TOLERANCE_SQ = 50 * 50; // 假设50单位的误差
//
//        if (objective == null) return "未知";
//
//        if (siteA != null && objective.distance(siteA) < 100) return "A点";
//
//        if (siteB != null && objective.distanceSq(siteB) < SITE_TOLERANCE_SQ) {
//            return "B点";
//        }
//
//        // 如果都不是包点，显示精确坐标（或最后已知目标位置）
//        return String.format("(%.0f, %.0f)", objective.x, objective.y);
//
//    }
//
//    /**
//     * 全局状态安全检查，防止AI卡在无效状态
//     */
//    private void performGlobalStateSafetyCheck() {
//
//        if (currentState == AIState.PLANTING_BOMB && gameState.isBombPlanted()) {
//            logger.accept(self.name + " 炸弹已安放，强制退出安包状态");
//            gameState.playerStopInteraction(self.id);
//            currentState = AIState.DEFENDING_SITE;
//        }
//
//        if (currentState == AIState.DEFUSING_BOMB && !gameState.isBombPlanted()) {
//            logger.accept(self.name + " 炸弹未安放，强制退出拆包状态");
//            gameState.playerStopInteraction(self.id);
//            currentState = AIState.PATROLLING;
//        }
//
//        if (currentState == AIState.PLANTING_BOMB && !self.hasBomb) {
//            logger.accept(self.name + " 没有C4，强制退出安包状态");
//            gameState.playerStopInteraction(self.id);
//            currentState = AIState.PATROLLING;
//        }
//
//        if (gameState.getRoundPhase() != GameState.RoundPhase.IN_PROGRESS) {
//            if (currentState == AIState.PLANTING_BOMB || currentState == AIState.DEFUSING_BOMB) {
//                logger.accept(self.name + " 回合未进行，强制退出交互状态");
//                gameState.playerStopInteraction(self.id);
//                currentState = AIState.PATROLLING;
//            }
//        }
//    }
//
//    /**
//     * 检查一个目标点是否可以作为有掩体的防守/安包位置。
//     * 逻辑：如果目标点与 T/CT 队伍的常见进攻/回防路线之间存在障碍物，则认为有掩体。
//     * @param position 要检查的坐标点
//     * @return 如果目标点到至少一个常见进攻/回防路线之间存在障碍物，则返回 true。
//     */
//    public boolean hasCoverFromCommonApproach(Point2D.Double position) {
//        if (position == null || gameState == null) return false;
//
//        // 简化逻辑：检查从所有出生点到该目标点是否有掩体
//        List<Rectangle> allSpawnAreas = new ArrayList<>();
//        if (gameState.getGameMode() == GameMode.DEMOLITION) {
//            allSpawnAreas.addAll(self.team == Player.Team.CT ? gameState.getTSpawnAreas() : gameState.getCtSpawnAreas());
//        } else {
//            // 对于其他模式，考虑所有玩家的当前位置
//            gameState.getPlayers().stream()
//                    .filter(p -> p.team != self.team && p.isAlive())
//                    .forEach(p -> {
//                        // 创建一个代表敌人当前位置的虚拟矩形
//                        allSpawnAreas.add(new Rectangle((int)p.position.x - 10, (int)p.position.y - 10, 20, 20));
//                    });
//        }
//
//        // 如果没有找到任何需要防范的区域，则默认安全
//        if (allSpawnAreas.isEmpty()) {
//            return true;
//        }
//
//        int coverCount = 0;
//        for (Rectangle spawnArea : allSpawnAreas) {
//            // 从出生点的中心向目标点发射射线
//            Point2D.Double start = new Point2D.Double(spawnArea.getCenterX(), spawnArea.getCenterY());
//            Line2D.Double lineOfSight = new Line2D.Double(start, position);
//
//            boolean isBlocked = false;
//            for (Shape obs : pathfinder.getObstacles()) {
//                if (obs.intersects(lineOfSight.getBounds2D()) && GameState.getLineShapeIntersections(lineOfSight, obs) != null) {
//                    isBlocked = true;
//                    break;
//                }
//            }
//            if (isBlocked) {
//                coverCount++;
//            }
//        }
//
//        // 只要有一条或更多的主要攻击线被阻挡，就认为有掩体。
//        return coverCount > 0;
//    }
//
//    /**
//     * [已升级] 指挥官设置战术目标的方法。
//     * 允许同时设置一个地理目标和一个需要跟随的玩家。
//     */
//    public void setTacticalObjective(Point2D.Double objective, Player targetPlayer, TacticRole role) {
//        this.tacticalObjective = objective;
//        this.playerToFollow = targetPlayer;
//        this.tacticRole = role;
//        this.holdPosition = (role == TacticRole.SAVER);
//    }
//    // 为了兼容捡枪的旧代码，保留一个重载方法
//    public void setTacticalObjective(Point2D.Double objective, TacticRole role) {
//        setTacticalObjective(objective, null, role);
//    }
//
//    //   清除战术目标，让AI恢复自由行动
//    public void clearTacticalObjective() {
//        // [关键日志] 记录AI清除目标的原因
////        logger.accept(String.format("[%s] Objective Cleared! 当前状态: %s, 当前角色: %s",
////                self.name,
////                currentState.name(),
////                tacticRole.name()
////        ));
//        this.tacticalObjective = null;
//        this.tacticRole = TacticRole.IDLE;
//        this.holdPosition = false;
//
//    }
//    //   公共Getter，供TeamCommander获取AI当前目标
//    public Point2D.Double getTacticalObjective() {
//        return this.tacticalObjective;
//    }
//
//    /**
//     *  公共Getter，供TeamCommander获取AI当前的战术角色。
//     * @return 当前的 TacticRole 枚举值。
//     */
//    public TacticRole getTacticRole() {
//        return this.tacticRole;
//    }
//
//    /**
//     * [权重函数]决定 AI 在看到敌人时，是应该交火还是回避并执行任务。
//     * 任务权重 VS 战斗权重
//     * @return 如果战斗权重 > 任务权重，则返回 true (应交火)。
//     */
//    private boolean shouldEngage() {
//        if (target == null || !target.isAlive()) return false;
//
//        Weapon wep = self.getCurrentWeapon();
//        if (wep == null) return false;
//
//        // ======================= [战术角色 -> 交战姿态] =======================
//        // 解释：在进行复杂的权重计算之前，我们先做一个简单的“角色”判断。
//        // 如果AI是进攻型角色，那么只要看到敌人，就应该无条件交火，绝不后退！
//        boolean hasLoS = hasLineOfSight(target.position);
//        switch (tacticRole) {
//            case ENTRY:         // 突击手
//            case FLANK:         // 侧翼包抄手
//            case EXECUTE:       // 主攻手
//
//            case DISTRACTION:   // 佯攻手
//
//                // 如果这些进攻角色在视野内看到了敌人，则强制交火，直接返回true！
//                if (hasLoS) {
//                    return true;
//                }
//                // 如果没看到，则让后续逻辑判断是否需要穿墙盲射。
//                break;
//        }
//
//        // --- 任务权重 (Mission Priority) ---
//        // 如果 AI 正在执行以下任何任务，且看到了敌人，则强制交火！
//        // RETRIEVING_BOMB: T方去捡包
//        // GUARDING_BOMB:   CT方防守掉落的包
//        if (hasLoS &&
//                (currentState == AIState.RETRIEVING_BOMB ||
//                        currentState == AIState.GUARDING_BOMB ||
//                        currentState == AIState.MOVING_TO_SITE))
//        {
//            // 只有 CT方 DEFUSER 在拆包范围内，才允许它不打人
//            if (tacticRole == TacticRole.DEFUSER && self.position.distance(gameState.getBombPosition()) < 50) {
//                // 拆弹手在拆包范围内，优先拆包，除非被击中（肾上腺素模式）
//            } else {
//                // 对于所有其他情况 (包括正在移动中的 DEFUSER)，强制交火！
//                return true;
//            }
//        }
//        double missionWeight = 0.0;
//        if (self.health < 30) missionWeight += 2.0;
//        if (tacticRole == TacticRole.SAVER) return false;
//        switch (tacticRole) {
//            case BOMB_CARRIER: missionWeight += 4.0; break;
//            case DEFUSER:      missionWeight += 4.0; break;
//            case ROTATOR:      missionWeight += 3.0; break;
//            case DEFENDER:     missionWeight += 2.0; break;
//            case BODYGUARD:    missionWeight += 5.0; break; // 护卫的命很“贱”.
//        }
//
//        // ---  战斗权重 (Engagement Priority) ---
//        double engagementWeight = 0.0;
//        double distance = self.position.distance(target.position);
//        hasLoS = hasLineOfSight(target.position);
//
//        // ======================= [肾上腺素模式] =======================
//        // 解释：我们检查AI是否在 RECENTLY_HIT_DURATION_MS (3秒) 内被攻击过。如果是，直接给战斗权重加上一个极高的“肾上腺素”加成。这个值（+10.0）
//        // 足以压倒几乎所有的任务权重，强制AI立即反击攻击者。
//        boolean wasRecentlyHit = (System.currentTimeMillis() - lastTimeDamaged < RECENTLY_HIT_DURATION_MS);
//        if (wasRecentlyHit) {
//            engagementWeight += 10.0; // "肾上腺素"加成！
//        }
//
//
//        // A. 武器距离效率：
//        if (wep.getWeaponType().isLongRange() && distance < 800) {
//            engagementWeight += 5.0;
//        } else if (wep.getWeaponType().isShortRange()) {
//            if (distance < 150) engagementWeight += 8.0;
//            else if (distance < 400) engagementWeight += 4.0;
//            else if (distance < 600)  engagementWeight += 3.0;
//            else if (Weapon.GLOCK18.equals(wep) || Weapon.USPS.equals(wep)) {
//                engagementWeight *= 2;
//            }
//        }
//
//        // B. 敌人危险程度/有利条件：
//        if (hasLoS) engagementWeight += 1.0;
//        if (distance < 50) engagementWeight += 2.0;
//        if (target.isReloading) engagementWeight += 5.0;
//        if (target.health < 40) engagementWeight += 3.0;
//
//        // C. 距离惩罚
//        final double START_PUNISH_DISTANCE = 300.0;
//        final double MAX_PUNISH_DISTANCE = 800.0;
//        final double MAX_PUNISH_VALUE = 1.5;
//        if (distance > START_PUNISH_DISTANCE) {
//            double punishFactor = Math.min(1.0, (distance - START_PUNISH_DISTANCE) / (MAX_PUNISH_DISTANCE - START_PUNISH_DISTANCE));
//            engagementWeight -= punishFactor * MAX_PUNISH_VALUE;
//        }
//
//        // ---   最终决策  ---
//        if (!hasLoS && lastHeardSoundCue != null && distance > 50) {
//            if (currentState != AIState.BLIND_RETALIATION) return false;
//        }
//
//        final double SAFETY_MARGIN = 1.0;
//        return engagementWeight > (missionWeight + SAFETY_MARGIN);
//    }
//
//    /**
//     * 当AI受到伤害时，由外部游戏逻辑调用此方法。
//     * 这会更新AI的“被攻击”状态，使其在短时间内变得更具攻击性。
//     */
//    private long lastTimeDamaged = 0;
//    public void onDamaged() {
//        this.lastTimeDamaged = System.currentTimeMillis();
////        logger.accept(self.name + " was hit! Adrenaline rush activated.");
//    }
//
//    // 定义“刚刚被攻击”的时间窗口
//    private static final long RECENTLY_HIT_DURATION_MS = 5000;
//    private long grenadePlanStartTime = 0; // [新增] 记录投掷计划开始时间的时间戳
//    private static final long GRENADE_THROW_TIMEOUT_MS = 5000; // [新增] 5秒超时
//
//    /**
//     * 僵尸模式下，幸存者AI的专属更新逻辑。
//     *  “倒着走” 风筝战术，始终面向目标后退并开火。
//     */
//    private void updateSurvivor(List<Player> allCharacters) {
//        //  寻找最近的僵尸作为目标
//        findTarget(allCharacters);
//
//        //  处理换弹等中断行为
//        if (self.isReloading) {
//            handleReloading();
//            followPath();
//            return;
//        }
//        if (currentState == AIState.PREPARING_GRENADE || currentState == AIState.MOVING_TO_THROW_SPOT) {
//            // 如果已经在执行投掷计划，则让它继续，不要打断
//            return;
//        }
//
//        //   如果没有目标，则巡逻
//        if (target == null || !target.isAlive()) {
//            self.isShooting = false;
//            patrol();
//            followPath();
//            return;
//        }
//
//        // ======================= [ 道具决策 ] =======================
//        // 在进行移动或射击前，先考虑是否应该使用战术道具
//        if (System.currentTimeMillis() > nextTacticalUpdateTime) {
//            // 尝试提交一个异步的扔道具的计算任务
//            if (useTacticalGrenade()) {
//                // 如果成功提交了任务 (useTacticalGrenade 内部会处理)，
//                // 我们就更新冷却时间，然后可以继续执行本帧的移动和射击，
//                // AI会在计算完成后自动进入扔雷状态。
//                nextTacticalUpdateTime = System.currentTimeMillis() + 1000 + rand.nextInt(1000);
//            } else {
//                // 如果没有扔，则设置一个较短的冷却，避免频繁检查
//                nextTacticalUpdateTime = System.currentTimeMillis() + 300 + rand.nextInt(400);
//            }
//        }
//
//
//        // ======================= [ 倒着走逻辑 ] =======================
//        final double ENGAGE_DISTANCE = 500; // 进入风筝模式的距离阈值
//        boolean targetInSight = target != null && target.isAlive() && hasLineOfSight(target.position);
//
//        // --- 交战 (Engaging / Kiting) ---
//        // 条件：视野内有僵尸，并且距离小于交战阈值
//        if (targetInSight && self.position.distance(target.position) < ENGAGE_DISTANCE) {
//            currentState = AIState.ATTACKING; // 标记为攻击状态
//
//            // 执行风筝逻辑 (倒着走并开火)
//            final double KITING_DISTANCE = 400;
//            double distanceToTarget = self.position.distance(target.position);
//            double angleToTarget = Math.atan2(target.position.y - self.position.y, target.position.x - self.position.x);
//
//            this.targetAngle = angleToTarget; // 始终瞄准目标
//            aimAndShoot(target); // 射击
//
//            // 根据距离决定后退还是站桩
//            if (distanceToTarget < KITING_DISTANCE) {
//                double angleAway = angleToTarget + Math.PI;
//                clearPath();
//                self.ax = Math.cos(angleAway);
//                self.ay = Math.sin(angleAway);
//            } else {
//                clearPath();
//                self.ax = 0;
//                self.ay = 0;
//            }
//
//            // --- 追猎 (Hunting) ---
//            // 条件：视野内没有目标，但我们知道僵尸最后出现的位置
//        } else if (lastKnownPosition != null) {
//            currentState = AIState.HUNTING; // 标记为追猎状态
//            self.isShooting = false; // 追猎时不盲目开火
//
////            logger.accept(self.name + " is hunting zombies at last known position.");
//
//            // 如果还没到达最后已知位置，就继续寻路过去
//            if (self.position.distance(lastKnownPosition) > Player.SIZE * 2) {
//                if ((currentPath == null || currentPath.isEmpty()) && shouldRecalculatePath()) {
//                    setNewPath(tacticalObjective);
//                }
//                // 如果已经到达，但没发现敌人，说明信息过时，清除lastKnownPosition
//            } else {
//                lastKnownPosition = null;
//            }
//
//            // ---  巡逻 (Patrolling) ---
//            // 条件：完全没有目标信息
//        } else {
//            currentState = AIState.PATROLLING; // 标记为巡逻状态
//            self.isShooting = false;
//
//            logger.accept(self.name + " has no target, patrolling to find zombies.");
//
//            patrol(); // 执行随机巡逻逻辑
//        }
//        // 无论处于追猎还是巡逻，都需要执行路径跟随
//        if (currentState == AIState.HUNTING || currentState == AIState.PATROLLING) {
//            followPath();
//        }
//    }
//
//    /**
//     * [脚步声]当AI“听到”脚步声时，由 GameState 调用此方法。
//     * @param soundSource 发出声音的敌人玩家。
//     */
//    public void onHeardFootstep(Player soundSource) {
//        // AI不会对友军的脚步声做出反应
//        //  将 this.player 替换为 this.self
//        if (soundSource.team == this.self.team) {
//            return;
//        }
//
//        //   根据AI难度，确定其“听力范围”
//        double hearingRange;
//        switch (this.difficulty) {
//            case HARD:
//                hearingRange = 200.0;
//                break;
//            case VERY_HARD:
//                hearingRange = 450.0;
//                break;
//            case HELL:
//                hearingRange = 700.0;
//                break;
//            default: // EASY 和 NORMAL 难度的AI是“聋子”，听不到脚步声
//                hearingRange = 0.0;
//                break;
//        }
//
//        // 如果AI听力为0，则不处理
//        if (hearingRange <= 0) {
//            return;
//        }
//
//        //  检查声音源是否在AI的听力范围之内
//        double distance = this.self.position.distance(soundSource.position);
//        if (distance <= hearingRange) {
//            //  声音在范围内！像看到敌人一样，更新AI的“记忆”
//            // 这会利用现有的索敌逻辑，让AI去调查或攻击该位置。
//            this.lastKnownPosition = (Point2D.Double) soundSource.position.clone();
//            this.lastSeenTime = System.currentTimeMillis(); // [修正] 现在这个变量已存在
//
//            // (可选) 如果AI当前处于空闲或巡逻状态，立即切换到更具攻击性的“狩猎”状态
//            if (this.currentState == AIState.IDLE || this.currentState == AIState.PATROLLING) {
//                this.currentState = AIState.HUNTING;
//                this.stateStartTime = System.currentTimeMillis();
//                // 调试
//                // logger.accept("AI " + self.name + " heard footsteps and is now hunting.");
//            }
//        }
//    }
//    private long stateStartTime;        // [旧版] 记录当前状态开始的时间戳
//    private long lastSeenTime;          // [新版] 记录最后一次看到/感知到敌人的时间戳
//    /**
//     * [新增] 计算AI在当前战术态势下选择“静步”的权重。
//     * @return 一个代表静步倾向的分数。分数越高，越倾向于静步。
//     */
//    private double calculateWalkingWeight() {
//        // 基础权重为0
//        double weight = 0;
//
//        // --- 触发条件：AI必须“知道”附近有敌人 ---
//        // 如果AI记忆中没有敌人位置，或者敌人已消失超过5秒，那么就完全没有理由静步
//        if (this.lastKnownPosition == null || System.currentTimeMillis() - this.lastSeenTime > 5000) {
//            return 0; // 权重为0，AI会正常跑动
//        }
//
//        // --- 权重计算 ---
//
//        //  距离权重：离敌人记忆中的位置越近，静步的意愿越强烈
//        double distanceToTarget = this.self.position.distance(this.lastKnownPosition);
//        if (distanceToTarget < 800) { // 只在800像素范围内考虑静步
//            // 使用一个反比函数，距离越近，权重加成越高
//            weight += (800 - distanceToTarget) / 10.0; // 例如，距离200时，权重+60
//        }
//
//        // 2. 难度权重：难度越高的AI，基础静步意愿越强
//        switch (this.difficulty) {
//            case HARD:
//                weight += 10;
//                break;
//            case VERY_HARD:
//                weight += 20;
//                break;
//            case HELL:
//                weight += 30;
//                break;
//        }
//
//        //  武器权重：根据持有武器的类型调整权重
//        Weapon currentWeapon = this.self.getCurrentWeapon();
//        if (currentWeapon != null) {
//            switch (currentWeapon.getWeaponType()) {
//                case SHOTGUN:
//                case SMG:
//                    weight -= 60; // 拿着近战优势武器，更倾向于快速接近，静步权重降低
//                    break;
//                case RIFLE:
//                    weight += 15; // 拿着步枪，倾向于更谨慎地接敌
//                    break;
//                case SNIPER:
//                    weight += 25; // 拿着狙击枪，极度需要隐蔽，权重最高
//                    break;
//            }
//        }
//
//        // --- 否决条件：在某些紧急情况下，强制取消静步 ---
//
//        //   状态否决：如果正在攻击、撤退或执行紧急任务，速度优先！
//        switch (this.currentState) {
//            case ATTACKING: // 如果已经暴露位置开火，再静步毫无意义
//            case RETREATING: // 撤退时保命要紧，必须全速
//            case RUSHING_BOMBSITE: // 抢点时时间就是生命
//                return 0; // 直接返回0，强制取消静步
//        }
//
//        return weight;
//    }
//
//    /**
//     * [HPA* 智能恢复/跳点逻辑]
//     * 当“小路”寻路失败时调用此方法，尝试从当前位置恢复“大路”计划。
//     * 1. 尝试寻路到“大路”的当前目标 (例如 粗点B)。
//     * 2. 如果失败，则“跳点”，尝试寻路到“大路”的下一个目标 (粗点C)。
//     * 3. 如果再次失败，则放弃HPA*，降级为纯网格A*寻路到最终目标。
//     */
//    private void resumeHpaPathfinding() {
//        // 检查是否有“大路”计划，以及这个计划是否已经走完
//        if (currentCoarsePath == null || currentCoarsePathIndex >= currentCoarsePath.size()) {
//            // 没有“大路”计划，或者计划已完成。
//            // 这不是HPA*的错，可能是AI卡住了。我们直接用“小路”尝试到最终目标。
//            logger.accept(self.name + " (HPA Resume): 无效的粗路径。降级为网格A*到 " + formatObjective(assignedObjective));
//            setNewPathFallback(assignedObjective); // 降级为纯网格A*
//            return;
//        }
//
//        // --- 1. 尝试恢复当前路径 (例如，从 A 到 B) ---
//        WaypointNode nextWaypoint = currentCoarsePath.get(currentCoarsePathIndex);
//        logger.accept(self.name + " HPA* (恢复): 尝试重新计算 '小路' 到 " + formatObjective(nextWaypoint.position));
//
//        List<GameState.FirePatch> firePatches = (gameState != null) ? gameState.getFirePatches() : new ArrayList<>();
//        List<Node> newPath = pathfinder.findPath(self, self.position, nextWaypoint.position, this.knownPlayers, firePatches);
//
//        if (newPath != null && !newPath.isEmpty()) {
//            // **成功！** 找到了小路 (A -> B)。
//            logger.accept(self.name + " HPA* (恢复成功): '小路' 已找到。");
//            this.currentPath = newPath;
//            this.currentPathIndex = 0;
//            this.lastPathRecalculationTime = System.currentTimeMillis(); // 重置计时器
//            return; // 成功恢复，退出
//        }
//
//        // --- 2. 恢复失败！执行“智能跳点” (Skip) ---
//        logger.accept(self.name + " HPA* (恢复失败): '小路' 到 " + formatObjective(nextWaypoint.position) + " 仍被阻挡。");
//
//        // 跳过这个点 (B点)
//        currentCoarsePathIndex++;
//
//        // 检查“大路”上是否还有下一个点 (C点)
//        if (currentCoarsePathIndex < currentCoarsePath.size()) {
//            WaypointNode nextNextWaypoint = currentCoarsePath.get(currentCoarsePathIndex);
//            logger.accept(self.name + " HPA* (智能跳点): 尝试直接计算 '小路' 到 " + formatObjective(nextNextWaypoint.position));
//
//            // 尝试计算从 A 到 C 的路径
//            newPath = pathfinder.findPath(self, self.position, nextNextWaypoint.position, this.knownPlayers, firePatches);
//
//            if (newPath != null && !newPath.isEmpty()) {
//                //  跳点成功 找到了 A -> C 的路径
//                logger.accept(self.name + " HPA* (跳点成功): 已找到绕行路径。");
//                this.currentPath = newPath;
//                this.currentPathIndex = 0;
//                this.lastPathRecalculationTime = System.currentTimeMillis();
//                return;
//            }
//
//            //跳点也失败了 (A -> C 也不通)
//            logger.accept(self.name + " HPA* (跳点失败): '小路' 到 " + formatObjective(nextNextWaypoint.position) + " 也不通。");
//        } else {
//            logger.accept(self.name + " HPA* (跳点失败): " + formatObjective(nextWaypoint.position) + " 已经是最后一个粗网格点。");
//        }
//        //  “大路”的 A->B 和 A->C 都走不通 ->  “大路”方案已不可行。
//        // 放弃 HPA*，使用纯“小路”（网格A*）走完剩余全程。
//        logger.accept(self.name + " HPA* : 放弃粗路径，使用纯网格A*到最终目标 " + formatObjective(assignedObjective));
//        setNewPathFallback(assignedObjective); // 降级为纯网格A*
//    }
//}
