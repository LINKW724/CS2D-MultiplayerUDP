// =================================================================================
// 文件: GameState.java
// 描述: 游戏核心状态管理类，包含所有游戏实体、逻辑和规则。
// =================================================================================
package cs2d.server;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import cs2d.AIControl.A.PathfindingModule;
import cs2d.playerAndAi.Player;

import cs2d.playerAndAi.Waypoint;
import cs2d.playerAndAi.Weapon;
import cs2d.playerAndAi.doublePlayer.WaypointNode;
import javafx.application.Platform;

import java.awt.*;
import java.awt.geom.*;
import java.time.LocalDateTime;
import java.util.*;
import java.util.List;
import java.util.concurrent.*;
import java.util.function.Consumer;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import static cs2d.AIControl.B.PerceptionModule.TIMESTAMP_FORMATTER;
import static cs2d.playerAndAi.Player.*;

/**
 * 游戏核心状态管理类。
 * 它是“游戏世界”的数据黑板，包含了所有的实体（如玩家、投掷物、烟雾、掉落物）
 * 以及相关的世界物理逻辑、枪支开火系统和各种事件。
 */
public class GameState {

    // --- 游戏核心常量 ---
    public static final double SERVER_TICKRATE = GameServer.TPS; // 从服务器拿来TPS
    private static final long SPAWN_PROTECTION_MS = 3000L; // 重生时间 3s
    private static final long DEATHMATCH_SPAWN_PROTECTION_MS = 10000L; // 死斗模式的重生保护时间 10s
    private static final double FRICTION = 0.95; // 摩擦力系数，越高摩擦力越小

    private static final double BASE_SPEED = 1.5; // 人物移速
    private static final double ACCELERATION = 0.3; // 人物移动加速度

    private static final double PENETRATION_MULTIPLIER = 2.0; // 穿透系数 2.0
    private static final int TDM_GAME_DURATION_SECONDS = 600; // 团队战 10min
    private static final long ZOMBIE_WAVE_SPAWN_DURATION_MS = 10000L;
    private static final long DEMO_FREEZE_TIME_MS = 10000L; // 开始时间为10秒
    public static final int DEMO_WIN_SCORE = 13;
    public static final int DEMO_MAX_ROUNDS = 24;
    private static final long DEMO_ROUND_TIME_MS = 115000L; // 1分55秒
    private static final long DEMO_BOMB_TIME_MS = 40000L;
    private static final long DEMO_PLANT_TIME_MS = 3200L;
    private static final long DEMO_DEFUSE_TIME_MS = 10000L;
    private static final long DEMO_DEFUSE_WITH_KIT_TIME_MS = 5000L;
    public static final double GRENADE_BOUNCE_FRICTION = -0.85; // 碰撞后速度衰减系数
    public static boolean isRLTrainingMode = false;

    // 将总数改为6的倍数，例如60，这样可以冒10次烟 (10次 * 6个/次)
    private static final double SMOKE_PUFF_INITIAL_SPEED = 25.0; // 烟雾颗粒的初始扩散速度
    private static final int QUADTREE_MAX_OBJECTS = 8; // Quadtree: 每个节点分裂前最多容纳的对象数 (根据需要调整)
    private static final int QUADTREE_MAX_DEPTH = 10; // Quadtree: 树的最大深度 (根据需要调整)

    private static final int BFS_GRID_CELL_SIZE = 20; // <-- 新增: 必须与编辑器匹配
    private final Set<Point> forbiddenSpawnGridCells = new HashSet<>(); // <-- 新增
    private final Set<Point> generalForbiddenGridCells = new HashSet<>(); // <-- 通用寻路禁区
    private final ConcurrentHashMap<String, AIService.AIInput> aiInputMailbox; // <-- AI输入数据邮箱（线程安全的消息队列），用于异步通信。
    private final Set<String> pendingAiDropRequests = ConcurrentHashMap.newKeySet();

    // 僵尸生成点：
    private final List<Point2D.Double> precomputedSpawnPoints = new ArrayList<>();

    // ========================= 脚步声 =========================
    private static final long FOOTSTEP_INTERVAL_MS = 200L; // 0.2秒间隔
    // public static final double FOOTSTEP_RADIUS = CLIENT.FOOT_SOUND; //脚步声范围
    public static final double FOOTSTEP_RADIUS = 1000; // 脚步声范围(不能超过1050 = 7 * 150)格子定义好了
    private static final double FOOTSTEP_RADIUS_SQ = FOOTSTEP_RADIUS * FOOTSTEP_RADIUS; // 使用平方距离进行比较，避免开方运算，效率更高
    private final List<FootstepRevealEvent> footstepRevealEvents = new CopyOnWriteArrayList<>();// 列表 存储 脚步声 事件

    // --- 游戏状态变量 ---
    public int width; // 地图宽度
    public int height; // 地图高度
    private final GameMode gameMode; // 当前游戏模式（例如团队死斗、爆破）。
    private final AIDifficulty aiDifficulty; // AI的难度设置。
    private final Consumer<String> logger; // 一个用于记录日志的函数式接口实例。
    private final int initialAiCount; // 游戏开始时AI的数量。
    private final List<Player> players = new CopyOnWriteArrayList<>(); // 存储所有玩家（包括AI）的线程安全列表。
    private final List<Player> zombies = new CopyOnWriteArrayList<>(); // 存储所有僵尸的线程安全列表。
    private final List<Shape> obstacles = new ArrayList<>(); // 存储地图上所有障碍物（用于碰撞检测）的列表。
    // 存储地图上所有的“粗略点”
    private List<WaypointNode> waypointGraph;
    private final List<MapData.ShapeWrapper> obstacleWrappers = new ArrayList<>(); // 存储障碍物的数据包装对象，方便序列化。
    private final List<VisualEffect> visualEffects = new CopyOnWriteArrayList<>(); // 存储视觉效果（如枪口火焰、弹道）的线程安全列表。
    private final List<SoundEvent> soundEvents = new CopyOnWriteArrayList<>(); // 存储需要广播给所有玩家的音效事件。
    private final List<SoundEvent> aiSoundHistory = new CopyOnWriteArrayList<>(); // AI 声音历史
    private final List<HeadshotEvent> privateSoundEvents = new CopyOnWriteArrayList<>(); // 存储只发送给特定玩家的音效事件（如爆头提示）。
    private long gameStartTime; // 游戏开始时的时间戳。
    private boolean isGameOver = false; // 标记游戏是否已经结束。
    private final Map<String, Double> playerDamageBuffer = new ConcurrentHashMap<>(); // 存储玩家受到的不足1点的伤害，累积后再计算。
    private final Random rand = new Random(); // 用于生成随机数的实例。
    private final List<PlayerDeath> recentDeaths = new CopyOnWriteArrayList<>(); // 存储最近发生的玩家死亡事件的线程安全列表。
    // 存储转换好的 Shape 对象，与 obstacleWrappers 一一对应
    private final Map<MapData.ShapeWrapper, Shape> shapeCache = new IdentityHashMap<>(); // 用于存储所有在飞行中的手榴弹
    private final List<ThrownGrenade> thrownGrenades = new CopyOnWriteArrayList<>();
    // 用于存储所有烟雾弹生成的烟雾颗粒
    private final List<SmokePuff> smokePuffs = new CopyOnWriteArrayList<>();
    // 用于存储需要单独发送给特定玩家的闪光弹效果
    private final List<FlashEvent> flashEvents = new CopyOnWriteArrayList<>();

    public QuadtreeNode getQuadtreeRootNode() {
        return this.quadtreeRootNode;
    }

    /**
     * 从缓存中获取指定 ShapeWrapper 对应的 AWT Shape。
     * 供 AIService 使用，避免重复转换。
     * 
     * @param wrapper 障碍物包装器
     * @return 对应的 AWT Shape，若不存在则返回 null
     */
    public Shape getShapeFromWrapper(MapData.ShapeWrapper wrapper) {
        return this.shapeCache.get(wrapper);
    }

    /**
     * 获取地图宽度（单位：游戏世界像素）。
     */
    public int getMapWidth() {
        return this.width;
    }

    /**
     * 获取地图高度（单位：游戏世界像素）。
     */
    public int getMapHeight() {
        return this.height;
    }

    public List<MapData.ShapeWrapper> getObstacleWrappers() {
        return this.obstacleWrappers;
    }

    // 在 KillFeedInfo 记录的定义中，增加一个 long timestamp
    public record KillFeedInfo(String killerId, String killerName, Player.Team killerTeam, String victimId,
            String victimName, Player.Team victimTeam, String weapon, boolean isHeadshot,
            long timestamp) {
    }

    // 存储最近击杀信息的队列，使用线程安全的ConcurrentLinkedDeque以避免并发修改异常
    private final Deque<KillFeedInfo> recentKills = new ConcurrentLinkedDeque<>();
    // --- 发给 听觉感受器 的 ---
    private final List<SoundEvent> aiSoundEventsBuffer = Collections.synchronizedList(new ArrayList<>());

    // --- 专门用于伤害日志damageLogEvents的列表 ---
    private record TargetedDamageEvent(String recipientId, JsonObject payload) {
    }

    private final List<TargetedDamageEvent> privateDamageEvents = new CopyOnWriteArrayList<>();
    // --- 地图数据 ---

    private List<Rectangle> ctSpawnAreas; // CT队伍的出生区域列表。
    private List<Rectangle> tSpawnAreas; // T队伍的出生区域列表。
    private Rectangle bombSiteA; // A炸弹点的区域。
    private Rectangle bombSiteB; // B炸弹点的区域。

    // --- TDM模式变量 ---
    private int teamCTScore_TDM = 0; // 团队死斗模式中CT队伍的分数。
    private int teamTScore_TDM = 0; // 团队死斗模式中T队伍的分数。
    private int tdmWinScore = 100; // 团队死斗模式胜利条件分数。

    // --- 僵尸模式变量 ---
    private int currentWave = 0; // 当前是第几波僵尸。
    private int zombiesToSpawn = 0; // 当前波次还需要生成的僵尸数量。
    private long lastZombieSpawnTime = 0; // 上一个僵尸生成的时间戳。
    private long zombieSpawnInterval = 500; // 生成僵尸之间的时间间隔（毫秒）。
    private boolean waitingForNextWave = true; // 标记当前是否正在等待下一波僵尸。
    private long nextWaveStartTime = 0; // 下一波僵尸开始的时间戳。
    private int tKillsLastRound = 0; // 上一回合CT方击杀T方的人数 (这个变量名似乎有误，应为CT击杀僵尸或T方击杀CT)。

    // --- 爆破模式变量 ---
    // 定义一个枚举类型来表示回合的不同阶段。
    public enum RoundPhase {
        FREEZE_TIME, IN_PROGRESS, ROUND_OVER
    }

    /**
     * 获取T队伍在爆破模式下的得分。
     */
    public int getTScore() {
        return this.teamTScore_DEMO;
    }

    /**
     * 获取CT队伍在爆破模式下的得分。
     */
    public int getCTScore() {
        return this.teamCTScore_DEMO;
    }

    private RoundPhase roundPhase = RoundPhase.FREEZE_TIME; // 当前回合所处的阶段，默认为冻结时间。
    public int currentRound = 0; // 当前是第几回合。
    private int teamCTScore_DEMO = 0; // 爆破模式中CT队伍的得分。
    private int teamTScore_DEMO = 0; // 爆破模式中T队伍的得分。
    public long roundStartTime = 0; // 当前回合开始的时间戳。
    private boolean bombPlanted = false; // 标记炸弹是否已经被安放。
    private Point2D.Double bombPosition; // 炸弹被安放的位置。
    private long bombPlantTime = 0; // 炸弹被安放的时间戳。
    private String bombPlanterId = null; // 安放炸弹的玩家ID。
    private Player.Team roundWinner = null; // 当前回合的胜利方。
    private String roundWinReason = ""; // 当前回合胜利的原因。
    private long roundEndTime = 0; // 当前回合结束的时间戳。
    private final List<DroppedItem> droppedItems = new CopyOnWriteArrayList<>(); // 存储地图上所有掉落物品的线程安全列表。
    static final long SELF_DROP_PICKUP_COOLDOWN_MS = 2_000L;

    private void addDroppedItem(DroppedItem item) {
        if (item == null)
            return;
        if (item.isBomb) {
            droppedItems.removeIf(i -> i.isBomb);
        }
        if (droppedItems.size() >= 50) {
            droppedItems.stream().filter(i -> !i.isBomb).findFirst().ifPresent(droppedItems::remove);
        }
        droppedItems.add(item);
    }

    private final List<PingMarker> pingMarkers = new CopyOnWriteArrayList<>();

    // 添加占位符定义以解决编译问题
    public record WeaponUIData(String displayName, int cost, String faction) {
    }

    public record ItemUIData(String displayName, int cost, int maxQuantity, String team) {
    }

    // 道具烟雾弹
    private static final long SMOKE_DURATION_MS = 10000L; // 烟雾总共持续10秒

    private static final double SMOKE_FRICTION = 0.2; // 烟雾弹的烟雾的摩擦力

    // --- 性能日志 (供 ServerStatusWindow 调用) ---
    private double perfTimeAiInputPrep = 0.0;
    private double perfTimePhysics = 0.0;
    private double perfTimeGameLogic = 0.0;
    private double perfTimeTotal = 0.0;

    // --- 内部实体类 ---

    /**
     * 闪光弹效果事件，只发送给被闪到的玩家
     */
    public record FlashEvent(String playerId, long duration) {
        public JsonObject toJson() {
            JsonObject obj = new JsonObject();
            obj.addProperty("type", "flash_effect");
            obj.addProperty("playerId", playerId);
            obj.addProperty("duration", duration);
            return obj;
        }
    }
    // /**
    // * 这个类的缺失导致爆头音效消失？？
    // */
    // public record HeadshotEvent(String recipientId, String soundName) {
    // public JsonObject toJson() {
    // JsonObject obj = new JsonObject();
    // obj.addProperty("recipientId", recipientId);
    // obj.addProperty("soundName", soundName);
    // return obj;
    // }
    // }

    /**
     * 辅助方法：检查一个点是否在任何障碍物内部
     * 
     * @Param point 传入平面的一个点
     */

    public boolean isPointInObstacle(Point2D.Double point) {
        for (Shape obstacle : obstacles) {
            if (obstacle.contains(point)) {
                return true;
            }
        }
        return false;
    }

    /**
     * 代表一个烟雾颗粒
     */
    public static class SmokePuff {
        public final UUID id = UUID.randomUUID();
        public Point2D.Double position;
        public Point2D.Double velocity; // 烟雾颗粒的速度
        public long creationTime;
        public static final long DURATION_MS = 15000; // 烟雾持续15秒
        public static final double RADIUS = 75; // 烟雾颗粒半径

        // 构造函数，接收一个初始速度
        public SmokePuff(Point2D.Double position, Point2D.Double velocity) {
            this.position = position;
            this.velocity = velocity;
            this.creationTime = System.currentTimeMillis();
        }

        public boolean isExpired() {
            return System.currentTimeMillis() - creationTime > DURATION_MS;
        }

        public JsonObject toJson() {
            JsonObject obj = new JsonObject();
            obj.addProperty("id", id.toString());
            obj.addProperty("x", position.x);
            obj.addProperty("y", position.y);
            obj.addProperty("radius", RADIUS);
            long remaining = DURATION_MS - (System.currentTimeMillis() - creationTime);
            obj.addProperty("remaining", remaining > 0 ? remaining : 0);
            return obj;
        }
    }

    /**
     * 扔出的 龟内(不是，是投掷物)
     */
    public static class ThrownGrenade {
        public final UUID id = UUID.randomUUID();
        public final Item type;
        public final String ownerId;
        public final Player.Team ownerTeam;
        public Weapon throwerWeapon; // 用于诱饵弹
        public Point2D.Double position;
        public Point2D.Double velocity;
        public long throwTime;
        public long detonationTime;
        public long lastBounceTime = 0;

        // 诱饵弹状态
        public boolean isDecoying = false;
        public long lastDecoySoundTime = 0;
        public int decoyShotsLeft = 0;

        // 烟雾弹专用状态
        public boolean isSmoking = false;
        public long lastSmokePuffTime = 0;
        public long stopSmokingTime = 0; // 新增用于控制10秒持续时间的计时器
        public long smokeDispersedUntil = 0;

        // 一个队列，用于存放这颗投掷物自己的烟雾颗粒，实现“先进先出”
        public final java.util.Queue<SmokePuff> myPuffs = new java.util.LinkedList<>();

        /**
         * 用于“真实轨迹”日志记录的Tick计数器
         */
        public int tickCounter = 0;

        public ThrownGrenade(Item type, String ownerId, Player.Team ownerTeam, Point2D.Double position,
                Point2D.Double velocity, Weapon throwerWeapon) {
            this.type = type;
            this.ownerId = ownerId;
            this.ownerTeam = ownerTeam;
            this.position = position;
            this.velocity = velocity;
            this.throwerWeapon = throwerWeapon;
            this.throwTime = System.currentTimeMillis();

            // 设置不同手榴弹的引信时间
            long fuse = switch (type) {
                case HE_GRENADE, FLASHBANG, DECOY -> 2000; // 2秒
                case SMOKE_GRENADE -> 1500; // 1.5秒
                case MOLOTOV, INCENDIARY -> 1000; // 1秒
                default -> 2000;
            };
            this.detonationTime = this.throwTime + fuse;
        }

        /**
         * 解释：专门传输"状态"用的
         * 将投掷物对象的核心状态序列化为一个 JSON 对象。
         * 序列化是将一个Java对象转换为一种可存储或可传输的格式（在这里是JSON字符串）的过程。
         * 这个方法通常在服务器准备向所有客户端广播游戏状态时被调用。
         *
         * @return 包含此投掷物关键信息的 JsonObject。
         *
         */
        public JsonObject toJson() {
            // 创建一个新的、空的JSON对象。作为所有数据的容器。
            JsonObject obj = new JsonObject();

            // "id" 属性。
            // 将投掷物的唯一标识符（UUID）转换为字符串，并以 "id" 为键名存入JSON对象。
            // 客户端将使用这个ID来区分和跟踪每一个独立的投掷物。
            obj.addProperty("id", id.toString());

            // "type" 属性。
            // 将投掷物的枚举类型名称（e.g. "HE_GRENADE"）以 "type" 为键名存入JSON对象。
            // 客户端根据这个类型来决定如何渲染这个投掷物（例如，使用手雷模型还是烟雾弹模型）。
            obj.addProperty("type", type.name());

            obj.addProperty("x", position.x);
            obj.addProperty("y", position.y);
            obj.addProperty("vx", velocity.x);
            obj.addProperty("vy", velocity.y);

            // 返回构建完成的JSON对象。
            // 这个对象现在包含了客户端渲染和模拟所需的所有信息，可以被发送出去了。
            return obj;
        }
    }

    /**
     * 代表一个掉落在地图上的物品 (武器或C4)
     */
    public static class DroppedItem {
        UUID id; // 物品的唯一ID。
        public Weapon weapon; // 如果是武器，则存储武器类型。
        public Point2D.Double position; // 物品在地图上的位置。
        public boolean isBomb; // 标记这个物品是否是C4炸弹。
        int currentAmmo; // 如果是武器，则存储当前弹匣中的弹药量。
        int reserveAmmo; // 如果是武器，则存储备用弹药量。
        long dropTime; // 物品被丢弃时的时间戳。
        final String dropperPlayerId; // 主动扔枪者；死亡掉落和地图生成物为null。

        // 武器构造函数
        DroppedItem(Weapon weapon, Point2D.Double position, int currentAmmo, int reserveAmmo) {
            this(weapon, position, currentAmmo, reserveAmmo, null);
        }

        DroppedItem(Weapon weapon, Point2D.Double position, int currentAmmo, int reserveAmmo,
                String dropperPlayerId) {
            this.id = UUID.randomUUID(); // 为物品生成一个唯一的ID。
            this.weapon = weapon; // 设置武器类型。
            this.position = position; // 设置物品位置。
            this.isBomb = false; // 明确这不是一个C4炸弹。
            this.currentAmmo = currentAmmo; // 设置当前弹药。
            this.reserveAmmo = reserveAmmo; // 设置备用弹药。
            this.dropTime = System.currentTimeMillis(); // 记录当前时间为丢弃时间。
            this.dropperPlayerId = dropperPlayerId;
        }

        // C4构造函数
        DroppedItem(Point2D.Double position) {
            this.id = UUID.randomUUID(); // 为物品生成一个唯一的ID。
            this.weapon = null; // C4不是武器，所以设为null。
            this.position = position; // 设置物品位置。
            this.isBomb = true; // 明确这是一个C4炸弹。
            this.dropTime = System.currentTimeMillis(); // 记录当前时间为丢弃时间。
            this.dropperPlayerId = null;
        }

        // 将掉落物品的信息转换为JSON对象 -> 发送给客户端。
        public JsonObject toJson() {
            JsonObject obj = new JsonObject(); // 创建一个新的JSON对象。
            obj.addProperty("id", id.toString()); // 添加物品的ID。
            obj.addProperty("x", position.x); // 添加物品的x坐标。
            obj.addProperty("y", position.y); // 添加物品的y坐标。
            obj.addProperty("isBomb", isBomb); // 添加是否是C4的标记。
            if (weapon != null) { // 如果这是一个武器（而不是C4）。
                obj.addProperty("name", weapon.name()); // 添加武器的枚举名称。
                obj.addProperty("currentAmmo", currentAmmo); // 添加当前弹药
                obj.addProperty("reserveAmmo", reserveAmmo); // 添加备用弹药
            }
            return obj;
        }
    }

    /**
     * 处理玩家主动扔出C4炸药包的逻辑
     *
     * @param playerId 发出请求的玩家ID
     */
    public void playerDropC4(String playerId) {
        // 只能在爆破模式下扔C4
        if (gameMode != GameMode.DEMOLITION)
            return;

        players.stream()
                .filter(p -> p.id.equals(playerId) && p.isAlive())
                .findFirst()
                .ifPresent(player -> {
                    // 检查玩家是否是T，并且确实持有C4
                    if (player.team == Player.Team.T && player.hasBomb) {

                        player.hasBomb = false; // 玩家失去C4

                        // 计算扔出的位置（玩家前方一小段距离）
                        double dropDist = Player.SIZE * 2;
                        Point2D.Double dropPos = new Point2D.Double(
                                player.position.x + Math.cos(player.angle) * dropDist,
                                player.position.y + Math.sin(player.angle) * dropDist);

                        // 在地图上创建一个掉落的C4物品
                        addDroppedItem(new DroppedItem(dropPos));

                        // 扔掉C4后，自动切换回主武器（如果有）或副武器
                        if (player.primaryWeapon != null) {
                            player.switchToSlot(1);
                        } else {
                            player.switchToSlot(2);
                        }

                        logger.accept(player.name + " dropped the C4 bomb.");
                    }
                });
    }

    public static class PingMarker {
        public final String id;
        public final Point2D.Double position;
        public final long creationTime;
        public final Player.Team team; // 标点所属的队伍
        public static final long DURATION_MS = 2000; // 2秒

        public PingMarker(String id, Point2D.Double position, Player.Team team) {
            this.id = id;
            this.position = position;
            this.team = team;
            this.creationTime = System.currentTimeMillis();
        }

        public boolean isExpired() {
            return System.currentTimeMillis() - creationTime > DURATION_MS;
        }

        /**
         * 发送
         * 
         * @return
         */
        public JsonObject toJson() {
            JsonObject obj = new JsonObject();
            obj.addProperty("id", id);
            obj.addProperty("x", position.x);
            obj.addProperty("y", position.y);
            obj.addProperty("team", team.name());
            long remaining = DURATION_MS - (System.currentTimeMillis() - creationTime);
            obj.addProperty("remaining", Math.max(0, remaining));
            obj.addProperty("duration", DURATION_MS);
            return obj;
        }
    }

    /**
     * 专门用于位置暴露的事件
     *
     * @param revealedPlayerId   暴露者
     * @param revealedToPlayerId 接收者
     * @param position           暴露者的位置
     */
    public record FootstepRevealEvent(String revealedPlayerId, String revealedToPlayerId, Point2D.Double position) {
        public JsonObject toJson() {
            JsonObject obj = new JsonObject();
            obj.addProperty("type", "footstep_reveal");
            obj.addProperty("revealedPlayerId", revealedPlayerId);
            obj.addProperty("revealedToPlayerId", revealedToPlayerId);
            JsonObject pos = new JsonObject();
            pos.addProperty("x", position.x);
            pos.addProperty("y", position.y);
            obj.add("position", pos); // 将精确位置也发给客户端
            return obj;
        }
    }

    /**
     * 用于记录玩家死亡信息的内部类
     */
    public static class PlayerDeath {
        public final Player.Team team; // 死亡玩家所属的队伍。
        public final Point2D.Double position; // 玩家死亡时的位置。
        public final long timestamp; // 玩家死亡时的时间戳。

        // 构造函数，用于创建一个新的死亡记录。
        public PlayerDeath(Player.Team team, Point2D.Double position, long timestamp) {
            this.team = team; // 初始化队伍。
            this.position = position; // 初始化位置。
            this.timestamp = timestamp; // 初始化时间戳。
        }
    }

    /** v2.1旧版构造函数 */
    // // GameState的构造函数，用于初始化整个游戏世界。
    // public GameState(GameMode mode, AIDifficulty difficulty, int aiCount, int
    // startingWave, MapData mapData, Consumer<String> logger) {
    // this.gameMode = mode; // 设置游戏模式。
    // this.aiDifficulty = difficulty; // 设置AI难度。
    // this.logger = logger; // 设置日志记录器。
    // this.initialAiCount = aiCount; // 设置初始AI数量。
    //
    // if (gameMode == GameMode.ZOMBIE_MODE) { // 如果是僵尸模式。
    // this.currentWave = Math.max(0, startingWave - 1); // 设置起始波数，确保不小于0。
    // }
    //
    // if (mapData != null) { // 如果提供了地图数据。
    // this.width = mapData.getWidth(); // 使用地图数据中的宽度。
    // this.height = mapData.getHeight(); // 使用地图数据中的高度。
    // loadMapFromData(mapData); // 从地图数据中加载障碍物和出生点。
    // } else { // 如果没有提供地图数据。
    // this.width = 1600; // 使用默认宽度。
    // this.height = 900; // 使用默认高度。
    // generateRandomMap(); // 生成一个随机的地图布局。
    // }
    // initGame(); // 初始化游戏，例如添加AI玩家。
    // }

    /**
     * 序列化网格
     */
    private List<Player>[][] spatialGrid;
    private int gridCellSize = 150; // 每个格子的尺寸
    private int gridWidth;
    private int gridHeight;
    private QuadtreeNode quadtreeRootNode;

    public GameState(GameMode mode, AIDifficulty difficulty, int aiCount, int startingWave, MapData mapData,
            Consumer<String> logger, ConcurrentHashMap<String, AIService.AIInput> mailbox) {
        this.aiInputMailbox = mailbox;
        this.gameMode = mode;
        this.aiDifficulty = difficulty;
        this.logger = logger;
        this.initialAiCount = aiCount;
        this.tdmWinScore = (aiCount + 1) * 30;

        // [依赖注入防线] 理论上完美的控制反转不会传入 null。此段仅做极端防御。
        if (mapData == null) {
            logger.accept("[GameState Init] ERROR: 致命故障！收到了空 MapData! 违背依赖注入约定，强制拉起 MapGenerator...");
            mapData = MapGenerator.generateRandomMap(1600, 900);
        }

        // 1. 从 mapData 提取拓扑数据 (经过上面阻拦，mapData 命中非空)
        this.width = mapData.getWidth();
        this.height = mapData.getHeight();
        logger.accept("[GameState Init] 地形引擎强制加载成功: Width=" + this.width + ", Height=" + this.height);
        loadMapFromData(mapData);
        initializeQuadtree();

        if (mapData.getForbiddenSpawnCells() != null && !mapData.getForbiddenSpawnCells().isEmpty()) {
            this.forbiddenSpawnGridCells.addAll(mapData.getForbiddenSpawnCells());
            logger.accept("[GameState Init] 成功架设 " + this.forbiddenSpawnGridCells.size() + " 个禁止复活物理单元格。");
        }
        if (mapData.getGeneralForbiddenZones() != null && !mapData.getGeneralForbiddenZones().isEmpty()) {
            this.generalForbiddenGridCells.addAll(mapData.getGeneralForbiddenZones());
            logger.accept("[GameState Init] 成功架设 " + this.generalForbiddenGridCells.size() + " 个通用禁区单元格。");
        }

        // 在确认 this.width 和 this.height 有效之后，再进行路径点烘焙
        if (mapData != null && mapData.getWaypoints() != null && !mapData.getWaypoints().isEmpty()) {
            logger.accept("正在加载 " + mapData.getWaypoints().size() + " 个预烘焙的路径点...");
            // --- 调用新的加载方法 ---
            buildRuntimeWaypointGraph(mapData.getWaypoints());
        } else {
            logger.accept("地图不包含路径点，将仅使用网格寻路。");
            this.waypointGraph = null;
        }

        if (gameMode == GameMode.ZOMBIE_MODE) {
            this.currentWave = Math.max(0, startingWave - 1);
        }

        // [删除] 移除您在 465-473 行重复的地图加载逻辑
        // if (mapData != null) { ... } <-- 这段是重复的，删除它

        // 在 width 和 height 确定之后，再初始化空间网格
        // (确保 gridCellSize > 0 并且 width/height 有效)
        if (this.width > 0 && this.height > 0 && gridCellSize > 0) {
            this.gridWidth = (int) Math.ceil((double) this.width / gridCellSize);
            this.gridHeight = (int) Math.ceil((double) this.height / gridCellSize);

            // 使用 ArrayList 数组，并在循环中初始化每个格子
            this.spatialGrid = new ArrayList[gridWidth][gridHeight]; // <--- 新的类型安全方式 (编译器可能仍有警告，但这是标准做法)
            for (int x = 0; x < gridWidth; x++) {
                for (int y = 0; y < gridHeight; y++) {
                    spatialGrid[x][y] = new ArrayList<>(); // <-- 初始化每个格子为一个空的 ArrayList
                }
            }
        } else {
            logger.accept("[GameState Init] ERROR: 地图尺寸无效或 gridCellSize 无效 (Width=" + this.width + ", Height="
                    + this.height + ")，无法初始化空间网格！");
            // 至少初始化一个空数组防止后续 NullPointerException
            this.gridWidth = 0;
            this.gridHeight = 0;
            this.spatialGrid = (List<Player>[][]) new List[0][0];
        }

        // 预计算出生点 (现在 width/height 是正确的)
        precomputeValidSpawnPoints();
        // 初始化游戏
        initGame();
    }

    /**
     * (从客户端复制并修改) 初始化 Quadtree
     */
    private void initializeQuadtree() {
        // 检查 obstacleWrappers 是否有效 (使用 this.obstacleWrappers)
        if (this.obstacleWrappers == null || this.obstacleWrappers.isEmpty() || this.width <= 0 || this.height <= 0) {
            quadtreeRootNode = null;
            logger.accept("[GameState Quadtree] 地图数据无效或无障碍物，Quadtree 未构建。");
            return;
        }

        Rectangle2D.Double mapBounds = new Rectangle2D.Double(0, 0, this.width, this.height);
        quadtreeRootNode = new QuadtreeNode(0, mapBounds, QUADTREE_MAX_OBJECTS, QUADTREE_MAX_DEPTH);

        logger.accept("[GameState Quadtree] 正在为 " + this.obstacleWrappers.size() + " 个障碍物构建 Quadtree...");

        for (MapData.ShapeWrapper wrapper : this.obstacleWrappers) {
            if (wrapper != null) { // 添加 null 检查
                quadtreeRootNode.insert(wrapper);
            }
        }

        logger.accept("[GameState Quadtree] 构建完成。");
    }

    private void precomputeValidSpawnPoints() {
        if (quadtreeRootNode == null)
            return;

        final int step = 50;
        long startTime = System.currentTimeMillis();
        int count = 0;

        List<MapData.ShapeWrapper> nearbyObstacles = new ArrayList<>();
        // 对象重用
        java.awt.geom.Rectangle2D.Double queryRect = new java.awt.geom.Rectangle2D.Double();
        double querySize = 1.0;

        for (int x = step; x < this.width; x += step) {
            for (int y = step; y < this.height; y += step) {

                queryRect.setRect(x - querySize / 2, y - querySize / 2, querySize, querySize);

                nearbyObstacles.clear();
                quadtreeRootNode.queryBounds(nearbyObstacles, queryRect);

                boolean isSafe = true;
                Point2D.Double p = new Point2D.Double(x, y);

                for (MapData.ShapeWrapper wrapper : nearbyObstacles) {
                    // 【核心优化】直接从缓存获取 Shape，没有任何计算和对象创建开销！
                    Shape shape = this.shapeCache.get(wrapper);

                    // 容错：万一缓存里没有（不太可能），再临时转一次
                    if (shape == null) {
                        shape = convertWrapperToShape(wrapper);
                    }

                    if (shape != null && shape.contains(p)) {
                        isSafe = false;
                        break;
                    }
                }

                if (isSafe) {
                    precomputedSpawnPoints.add(p);
                    count++;
                }
            }
        }
        long duration = System.currentTimeMillis() - startTime;
        logger.accept("预计算完成 (缓存优化版)！共找到 " + count + " 个点。耗时: " + duration + "ms");
    }

    // 火焰区域
    /**
     * 这里修改 火焰伤害 /燃烧弹
     */
    private static final double EXPLOSIVE_ARMOR_PENETRATION = 0.7;
    private final List<FirePatch> firePatches = new CopyOnWriteArrayList<>();

    /**
     * 处理燃烧瓶/弹的引爆，生成一片火海
     */
    private void detonateMolotov(ThrownGrenade grenade) {
        addSoundEvent(SoundEvent.SoundType.EXPLODE, "molotov_detonate_1", grenade.position.x, grenade.position.y);

        // 在爆炸点周围生成火焰块，并传入来源道具
        firePatches.add(new FirePatch(grenade.ownerId, (Point2D.Double) grenade.position.clone(), grenade.type));
        for (int i = 0; i < 4; i++) {
            double angle = rand.nextDouble() * 2 * Math.PI;
            double radius = FirePatch.RADIUS * (1.0 + rand.nextDouble());
            Point2D.Double firePos = new Point2D.Double(
                    grenade.position.x + Math.cos(angle) * radius,
                    grenade.position.y + Math.sin(angle) * radius);
            if (!isPointInObstacle(firePos)) {
                firePatches.add(new FirePatch(grenade.ownerId, firePos, grenade.type));
            }
        }
        // 为每个生成的火焰块发送开始循环播放的指令
        // 用火焰块的ID作为声音的唯一标识符
        firePatches.forEach(firePatch -> {
            addSoundEvent(SoundEvent.SoundType.FIRE_LOOP_START, firePatch.id.toString(), firePatch.position.x,
                    firePatch.position.y);
        });
    }

    /**
     * 每帧更新所有火焰的状态
     */
    private void updateFirePatches() {
        final long FIRE_DAMAGE_INTERVAL_MS = 300L;
        final int FIRE_DAMAGE_PER_TICK = 15;
        long currentTime = System.currentTimeMillis();

        // 创建一个列表，用于存放本帧需要移除的火焰
        List<FirePatch> firesToRemove = new ArrayList<>();

        // --- 遍历所有火焰，处理过期和烟雾熄灭 ---
        for (FirePatch fire : firePatches) {
            // 检查火焰是否因为时间到了而过期
            if (fire.isExpired()) {
                addSoundEvent(SoundEvent.SoundType.FIRE_LOOP_STOP, fire.id.toString(), fire.position.x,
                        fire.position.y);
                firesToRemove.add(fire);
                continue; // 处理下一个火焰
            }

            // 检查火焰是否在烟雾中被熄灭
            for (SmokePuff smoke : smokePuffs) {
                if (fire.position.distance(smoke.position) < SmokePuff.RADIUS) {
                    addSoundEvent(SoundEvent.SoundType.FIRE_LOOP_STOP, fire.id.toString(), fire.position.x,
                            fire.position.y);
                    firesToRemove.add(fire);
                    break; // 这个火灭了，检查下一个
                }
            }
        }
        // 统一移除所有标记为要移除的火焰
        if (!firesToRemove.isEmpty()) {
            firePatches.removeAll(firesToRemove);
        }

        // --- 处理火焰伤害 ---
        List<Player> allCharacters = new ArrayList<>(players);
        allCharacters.addAll(zombies);
        Player owner = null;

        for (Player p : allCharacters) {
            if (!p.isAlive())
                continue;
            for (FirePatch fire : firePatches) {
                if (p.position.distance(fire.position) < FirePatch.RADIUS) {
                    if (currentTime - p.lastFireDamageTime > FIRE_DAMAGE_INTERVAL_MS) {
                        owner = players.stream().filter(pl -> pl.id.equals(fire.ownerId)).findFirst().orElse(null);
                        dealDamageAndHandleEvents(owner, p, FIRE_DAMAGE_PER_TICK, false, fire.sourceItem.name());
                        p.lastFireDamageTime = currentTime;
                    }
                    break;
                }
            }
        }
    }

    /**
     * 将火焰数据序列化，发给客户端
     */
    private void serializeFirePatches(JsonObject state) {
        JsonArray fireArray = new JsonArray();
        firePatches.forEach(f -> fireArray.add(f.toJson()));
        state.add("firePatches", fireArray);
    }

    /**
     * 代表一小块燃烧的区域
     */
    public static class FirePatch {
        public final UUID id = UUID.randomUUID();
        public final String ownerId;
        public final Point2D.Double position;
        public final long creationTime;
        public final Item sourceItem; // 补上这行遗漏的声明

        public static final long DURATION_MS = 12000; // 火焰/燃烧弹持续12秒
        public static final double RADIUS = 40; // 每小块火焰的半径

        // 构造函数，增加一个 sourceItem 参数
        public FirePatch(String ownerId, Point2D.Double position, Item sourceItem) {
            this.ownerId = ownerId;
            this.position = position;
            this.sourceItem = sourceItem; // 赋值
            this.creationTime = System.currentTimeMillis();
        }

        public boolean isExpired() {
            return System.currentTimeMillis() - creationTime > DURATION_MS;
        }

        public JsonObject toJson() {
            JsonObject obj = new JsonObject();
            obj.addProperty("id", id.toString());
            obj.addProperty("x", position.x);
            obj.addProperty("y", position.y);
            obj.addProperty("radius", RADIUS);
            return obj;
        }
    }

    /**
     * 地图加载器
     * 
     * @param data
     */
    // 从MapData对象中加载地图布局。
    private void loadMapFromData(MapData data) {
        this.ctSpawnAreas = data.getCtSpawnAreas();
        this.tSpawnAreas = data.getTSpawnAreas();
        this.bombSiteA = data.getBombSiteA();
        this.bombSiteB = data.getBombSiteB();

        // 清空旧缓存
        this.shapeCache.clear();
        this.obstacles.clear();
        this.obstacleWrappers.clear();

        for (MapData.ShapeWrapper wrapper : data.getObstacles()) {
            // 在这里进行耗时的转换，只做一次！
            Shape shape = convertWrapperToShape(wrapper);

            if (shape != null) {
                this.obstacles.add(shape);
                this.obstacleWrappers.add(wrapper);

                // 【核心修改】存入缓存！
                this.shapeCache.put(wrapper, shape);
            }
        }

        // 记得处理边界
        addMapBoundaries();
    }

    // 同时也修改 addMapBoundaries 方法，把边界也放入缓存
    private void addMapBoundaries() {
        int wallThickness = 10;
        MapData.ShapeWrapper[] boundaries = {
                new MapData.ShapeWrapper(MapData.ShapeWrapper.ShapeType.RECTANGLE, 0, 0, width, wallThickness),
                new MapData.ShapeWrapper(MapData.ShapeWrapper.ShapeType.RECTANGLE, 0, height - wallThickness, width,
                        wallThickness),
                new MapData.ShapeWrapper(MapData.ShapeWrapper.ShapeType.RECTANGLE, 0, 0, wallThickness, height),
                new MapData.ShapeWrapper(MapData.ShapeWrapper.ShapeType.RECTANGLE, width - wallThickness, 0,
                        wallThickness, height)
        };
        for (MapData.ShapeWrapper wrapper : boundaries) {
            Shape shape = convertWrapperToShape(wrapper);
            if (shape != null) {
                this.obstacleWrappers.add(wrapper);
                this.obstacles.add(shape);
                // 【核心修改】存入缓存
                this.shapeCache.put(wrapper, shape);
            }
        }
    }

    /**
     * 获取 T 方出生点区域列表
     */
    public List<Rectangle> getTSpawnAreas() {
        return tSpawnAreas;
    }

    /**
     * 获取 CT 方出生点区域列表
     */
    public List<Rectangle> getCtSpawnAreas() {
        return ctSpawnAreas;
    }

    // 将数据包装对象转换为AWT的Shape对象。
    public static Shape convertWrapperToShape(MapData.ShapeWrapper wrapper) {
        switch (wrapper.type) { // 根据包装对象中的类型进行判断。
            case RECTANGLE: // 如果是矩形。
                return new Rectangle2D.Double(wrapper.x, wrapper.y, wrapper.width, wrapper.height); // 创建并返回一个矩形对象。
            case ELLIPSE:
                // 椭圆 -> 16边的多边形
                Path2D.Double ellipseAsPolygon = new Path2D.Double();
                final int numSegments = 16;

                // 计算椭圆的中心和半径
                double centerX = wrapper.x + wrapper.width / 2.0;
                double centerY = wrapper.y + wrapper.height / 2.0;
                double radiusX = wrapper.width / 2.0;
                double radiusY = wrapper.height / 2.0;

                // 计算第一个顶点的位置并移动到那里
                double firstX = centerX + radiusX * Math.cos(0);
                double firstY = centerY + radiusY * Math.sin(0);
                ellipseAsPolygon.moveTo(firstX, firstY);

                // 循环生成剩下的15个顶点并连接成线
                for (int i = 1; i < numSegments; i++) {
                    double angle = (i / (double) numSegments) * 2 * Math.PI;
                    double x = centerX + radiusX * Math.cos(angle);
                    double y = centerY + radiusY * Math.sin(angle);
                    ellipseAsPolygon.lineTo(x, y);
                }

                ellipseAsPolygon.closePath(); // 闭合路径，形成一个封闭的16边形
                return ellipseAsPolygon; // 返回这个16边形作为障碍物
            // return new Ellipse2D.Double(wrapper.x, wrapper.y, wrapper.width,
            // wrapper.height); // 创建并返回 椭圆对象 x
            case POLYGON: // 如果是多边形。
                Path2D.Double poly = new Path2D.Double(); // 创建一个路径对象。
                if (wrapper.xPoints == null || wrapper.xPoints.length < 3)
                    return null; // 检查顶点数据是否有效。
                poly.moveTo(wrapper.xPoints[0], wrapper.yPoints[0]); // 移动到第一个顶点。
                for (int i = 1; i < wrapper.xPoints.length; i++)
                    poly.lineTo(wrapper.xPoints[i], wrapper.yPoints[i]); // 依次连接所有顶点。
                poly.closePath(); // 闭合路径，形成一个封闭的多边形。
                return poly; // 返回创建好的多边形。
            default: // 如果是未知的类型。
                return null; // 返回null。
        }
    }

    // 初始化游戏，根据游戏模式添加AI。
    // 初始化游戏，根据游戏模式添加AI。
    private void initGame() {
        gameStartTime = System.currentTimeMillis();

        // --- 性能监控开始 ---
        System.out.println("【性能监控】开始初始化游戏逻辑 (initGame)...");
        long startT = System.currentTimeMillis(); // 总开始时间
        long splitT = startT; // 分段计时器

        if (gameMode == GameMode.TEAM_DEATHMATCH) { // 团队死斗模式
            int teamSize = this.initialAiCount / 2;

            // 添加 CT
            for (int i = 0; i < teamSize; i++) {
                addAiPlayer(Player.Team.CT);
                // 监控第一个 AI 的创建耗时（通常最慢）
                if (i == 0) {
                    long now = System.currentTimeMillis();
                    System.out.println("--- [TDM] 第1个 CT AI 创建耗时: " + (now - splitT) + "ms");
                    splitT = now;
                }
            }

            // 添加 T
            for (int i = 0; i < this.initialAiCount - teamSize; i++) {
                addAiPlayer(Player.Team.T);
            }

        } else if (gameMode == GameMode.ZOMBIE_MODE) { // 僵尸模式

            for (int i = 0; i < this.initialAiCount; i++) {
                addAiPlayer(Player.Team.CT);
                // 僵尸模式下，我们多监控前几个，看看是不是内存爆炸导致的线性增长
                if (i < 3) {
                    long now = System.currentTimeMillis();
                    System.out.println("--- [Zombie] 第" + (i + 1) + "个 AI 创建耗时: " + (now - splitT) + "ms");
                    splitT = now;
                }
            }
            startNextWave();

        } else if (gameMode == GameMode.DEMOLITION) { // 爆破模式
            int teamSize = this.initialAiCount / 2;

            // 添加 CT
            for (int i = 0; i < teamSize; i++) {
                addAiPlayer(Player.Team.CT);
                if (i == 0) {
                    long now = System.currentTimeMillis();
                    System.out.println("--- [Demo] 第1个 CT AI 创建耗时: " + (now - splitT) + "ms");
                    splitT = now;
                }
            }

            // 添加 T
            for (int i = 0; i < this.initialAiCount - teamSize; i++) {
                addAiPlayer(Player.Team.T);
            }
            startNewRound();

        } else if (gameMode == GameMode.DEATHMATCH) { // 死斗模式

            for (int i = 0; i < this.initialAiCount; i++) {
                addAiPlayer(rand.nextBoolean() ? Player.Team.CT : Player.Team.T);
                if (i == 0) {
                    long now = System.currentTimeMillis();
                    System.out.println("--- [DM] 第1个 AI 创建耗时: " + (now - splitT) + "ms");
                    splitT = now;
                }
            }
        }

        long totalTime = System.currentTimeMillis() - startT;
        System.out.println("【性能监控】initGame 总耗时: " + totalTime + "ms");

        if (totalTime > 2000) {
            System.err.println("!!! 警告: 游戏初始化耗时过长 (" + totalTime + "ms)，请检查 PathfindingModule 是否分配了过大内存 !!!");
        }

        logger.accept("游戏逻辑初始化完成。");
    }

    /**
     * 处理玩家的标点请求。
     */
    public void playerRequestPing(String playerId, Point2D.Double position) {
        Player pinger = getPlayerById(playerId);
        if (pinger == null || !pinger.isAlive())
            return;

        // 移除该玩家之前的所有标点，确保每人最多一个
        pingMarkers.removeIf(marker -> marker.id.equals(playerId));

        // 添加新标点
        pingMarkers.add(new PingMarker(playerId, position, pinger.team));
    }

    /**
     * 每帧更新标点，移除过期的。
     */
    private void updatePingMarkers() {
        pingMarkers.removeIf(PingMarker::isExpired);
    }

    /**
     * 开始分析！
     */
    private long tickCounter = 0; // 用于性能分析计数的帧计数器
    // 每帧更新的

    /**
     * [最终版] 游戏主循环更新方法。
     * 此版本已彻底解耦AI计算，只负责应用输入和更新物理状态。
     */
    public void update() {
        if (isGameOver)
            return;

        if (!pendingAiDropRequests.isEmpty()) {
            for (String aiId : new ArrayList<>(pendingAiDropRequests)) {
                if (pendingAiDropRequests.remove(aiId))
                    playerDropWeapon(aiId);
            }
        }

        long frameStartTime = System.nanoTime();
        long lastTimeStamp = frameStartTime;
        long currentTime = System.currentTimeMillis();

        List<Player> allCharacters = new ArrayList<>(players);
        allCharacters.addAll(zombies);
        zombies.removeIf(z -> !z.isAlive());

        for (Player p : players) {
            if (p.isControllingBot()) {
                Player controlledBot = getPlayerById(p.controllingBotId);
                // 如果BOT找不到了，或者BOT已经死亡，则强制释放
                if (controlledBot == null || !controlledBot.isAlive()) {
                    logger.accept("被控BOT " + (controlledBot != null ? controlledBot.name : p.controllingBotId)
                            + " 阵亡，强制释放控制！");
                    playerReleaseBot(p);
                }
            }
        }

        // 应用AI输入
        if (!isAiFrozen) {
            // [BUG解决]将循环的目标从 players 列表改为 allCharacters 列表。
            // 这样，循环就能同时检查到在 players 列表中的AI幸存者，和在 zombies 列表中的AI僵尸，确保每一个AI的决策都能被执行。
            for (Player p : allCharacters) {
                if (p.isAI && !p.isControlledByPlayer()) {
                    AIService.AIInput latestInput = aiInputMailbox.get(p.id);
                    if (latestInput != null) {
                        // 应用常规输入
                        p.keysDown.clear();
                        p.keysDown.addAll(latestInput.keys());
                        p.angle = latestInput.angle();
                        p.isShooting = latestInput.shooting();
                        // 在这里处理来自AI的交互请求
                        boolean isRequestingInteraction = latestInput.isRequestingInteraction();
                        // 如果AI想要交互，而服务器当前认为它没有在交互
                        if (isRequestingInteraction && !p.isInteracting) {
                            // 由主线程安全地调用交互开始方法
                            playerStartInteraction(p.id);
                        }
                        // 如果AI不再想交互，而服务器还认为它在交互
                        else if (!isRequestingInteraction && p.isInteracting) {
                            // 由主线程安全地调用交互停止方法
                            playerStopInteraction(p.id);
                        }
                    }
                }
            }
            /** 冻住喽 */
        } else {
            for (Player p : players) {
                if (p.isAI && !p.isControlledByPlayer()) {
                    p.keysDown.clear();
                    p.isShooting = false;
                    p.ax = 0;
                    p.ay = 0;
                    p.vx = 0;
                    p.vy = 0;
                }
            }
        }

        if (currentTime - lastBoundsCheckTime > BOUNDS_CHECK_INTERVAL_MS) {
            periodicallyCheckAndCorrectBounds();
            lastBoundsCheckTime = currentTime;
        }

        players.removeIf(p -> !p.hasChosenTeam && (currentTime - p.connectionTime > 30000));

        updateGrenades();
        updateSmokePuffs();
        updateFirePatches();
        updatePingMarkers();

        /** 所有的玩家行为（射击、交互、换弹）都在这里更新，但不进行位置/碰撞修正 */
        allCharacters.forEach(this::updatePlayerBehaviorAndPhysicsPrep);

        long timeAfterPhysicsPrep = System.nanoTime();
        // ------------------------------------
        // 核心物理更新：移动、网格重建、碰撞解决[大更新，网格算法真好用]
        // 清空网格
        for (int x = 0; x < gridWidth; x++) {
            for (int y = 0; y < gridHeight; y++) {
                synchronized (spatialGrid[x][y]) {
                    spatialGrid[x][y].clear();
                }
            }
        }

        // 应用预计算的位移，并填充网格
        for (Player p : allCharacters) {
            if (!p.isAlive())
                continue;

            // 应用 p.vx 和 p.vy 带来的位移
            p.position.x += p.vx;
            p.position.y += p.vy;

            // 填充网格
            int gridX = (int) (p.position.x / gridCellSize);
            int gridY = (int) (p.position.y / gridCellSize);
            if (gridX >= 0 && gridX < gridWidth && gridY >= 0 && gridY < gridHeight) {
                synchronized (spatialGrid[gridX][gridY]) {
                    spatialGrid[gridX][gridY].add(p);
                }
            }
        }

        // 解决所有碰撞和穿透（人-人，人-墙），并修正最终位置
        allCharacters.stream()
                .filter(Player::isAlive)
                .forEach(this::resolveCollisionsAndSliding);

        long timeAfterPhysics = System.nanoTime();
        // ------------------------------------

        // 游戏逻辑和性能记录[放在控制台了]
        // ------------------------------------
        handlePlayerZombieProximityAttacks();
        handleFootstepSounds(); // 调用脚步声处理
        handleGameModeLogic();
        updateVisualEffects();

        // 性能日志
        if (tickCounter % 120 == 0) {
            // [修改] 将性能数据存储到成员变量中，供外部UI读取
            // 注意：这里的 lastTimeStamp 是 update() 方法开头的局部变量 (long lastTimeStamp =
            // frameStartTime;)

            // [1] AI/Input/Effects/Prep
            this.perfTimeAiInputPrep = (timeAfterPhysicsPrep - lastTimeStamp) / 1_000_000.0;

            // [2] Physics (Move/Grid/Collision)
            // !!! 关键：这里的 lastTimeStamp 必须使用 update() 方法内的那个局部变量
            // 但是我们不能在这里修改它，所以我们直接用时间戳相减
            this.perfTimePhysics = (timeAfterPhysics - timeAfterPhysicsPrep) / 1_000_000.0;

            // [3] Game Logic
            this.perfTimeGameLogic = (System.nanoTime() - timeAfterPhysics) / 1_000_000.0;

            // >>> TOTAL FRAME TIME
            this.perfTimeTotal = (System.nanoTime() - frameStartTime) / 1_000_000.0;
        }
        tickCounter++;
    }

    /** AI 线程只投递意图，实际丢枪由下一次游戏主线程 Tick 执行。 */
    public void requestAiDropWeapon(String aiId) {
        if (aiId != null)
            pendingAiDropRequests.add(aiId);
    }

    // --- 供服务器状态窗口调用的性能日志 Getters ---
    public double getPerfTimeAiInputPrep() {
        return perfTimeAiInputPrep;
    }

    public double getPerfTimePhysics() {
        return perfTimePhysics;
    }

    public double getPerfTimeGameLogic() {
        return perfTimeGameLogic;
    }

    public double getPerfTimeTotal() {
        return perfTimeTotal;
    }

    /**
     * 僵尸挠人-没有用网格算法优化
     */
    private void handlePlayerZombieProximityAttacks() {
        // 检查当前游戏模式是否为僵尸模式，如果不是则直接返回
        if (gameMode != GameMode.ZOMBIE_MODE) {
            return;
        }
        // 获取当前系统时间 -> 计算攻击冷却
        long currentTime = System.currentTimeMillis();

        // 查找所有存活的玩家控制的僵尸
        players.stream()
                // 过滤条件：玩家存活+不是AI控制+属于僵尸队伍
                .filter(p -> p.isAlive() && !p.isAI && p.team == Player.Team.ZOMBIE)
                .forEach(zombiePlayer -> {
                    // 检查攻击冷却时间是否已过
                    if (currentTime - zombiePlayer.lastAttackTime > PLAYER_ZOMBIE_ATTACK_COOLDOWN_MS) {
                        // 在攻击范围内寻找最近的幸存者
                        players.stream()
                                // 过滤条件：幸存者存活、属于CT队伍
                                .filter(ct -> ct.isAlive() && ct.team == Player.Team.CT)
                                // 进一步过滤：只选择在僵尸攻击范围内的幸存者
                                .filter(ct -> zombiePlayer.position.distance(ct.position) < PLAYER_ZOMBIE_ATTACK_RANGE)
                                .findFirst() // 攻击范围内找到的第一个幸存者
                                .ifPresent(victim -> {
                                    // 对受害者造成伤害并处理相关事件
                                    // 参数说明：攻击者、受害者、伤害值、是否爆头、攻击类型
                                    dealDamageAndHandleEvents(zombiePlayer, victim, 15, false, "ZOMBIE_CLAW");
                                    // 更新僵尸的最后攻击时间，重置冷却
                                    zombiePlayer.lastAttackTime = currentTime;
                                });
                    }
                });
    }

    /**
     * 在主循环中更新所有手榴弹的状态
     */
    private void playBounceSound(ThrownGrenade grenade) {
        String soundName = switch (grenade.type) {
            case HE_GRENADE -> "hegrenade_bounce";
            case FLASHBANG -> "flashbang_hit1";
            case SMOKE_GRENADE -> "smokegrenade_hit1";
            case MOLOTOV -> rand.nextBoolean() ? "molotov_bounce1" : "molotov_bounce2";
            case INCENDIARY -> "incendiary_bounce";
            default -> null;
        };
        if (soundName != null) {
            addSoundEvent(SoundEvent.SoundType.BOUNCE, soundName, grenade.position.x, grenade.position.y);
        }
    }

    private void updateGrenades() {
        long currentTime = System.currentTimeMillis();
        List<ThrownGrenade> grenadesToRemove = new ArrayList<>();

        for (ThrownGrenade grenade : thrownGrenades) {

            // v1.8 [BUG已经修复]
            // ================== [日志记录注入] ==================
            // 检查这是否是手雷被创建后的第一个Tick
            // if (grenade.tickCounter == 0) {
            // // 如果是，记录下它的初始状态，格式与AI的“幽灵轨迹”完全一致
            // logger.accept(String.format("--- REAL GRENADE START --- Type: %s, StartPos:
            // (%.1f, %.1f), StartVel: (%.2f, %.2f)", grenade.type.name(),
            // grenade.position.x, grenade.position.y, grenade.velocity.x,
            // grenade.velocity.y));
            // }
            // 记录下本Tick开始时的状态
            // logger.accept(String.format("REAL TICK %d: Pos(%.1f, %.1f), Vel(%.2f, %.2f)",
            // grenade.tickCounter, grenade.position.x, grenade.position.y,
            // grenade.velocity.x, grenade.velocity.y));

            // 记录完毕后，计数器加一，为下一个Tick做准备
            grenade.tickCounter++;
            // ================== [日志记录结束] ============================

            // --- 引爆和持续效果检查 ---
            if (currentTime >= grenade.detonationTime && !grenade.isDecoying && !grenade.isSmoking) {
                detonateGrenade(grenade);
                if (grenade.type != Item.DECOY && grenade.type != Item.SMOKE_GRENADE) {
                    grenadesToRemove.add(grenade);
                }
                continue;
            }
            if (grenade.isSmoking || grenade.isDecoying) {
                if (grenade.isSmoking) {
                    if (currentTime > grenade.stopSmokingTime) {
                        smokePuffs.removeAll(grenade.myPuffs);
                        grenadesToRemove.add(grenade);
                        continue;
                    }
                    if (currentTime < grenade.smokeDispersedUntil) {
                        continue;
                    }
                    if (grenade.myPuffs.isEmpty()) {
                        int puffsToCreate = 3;
                        for (int i = 0; i < puffsToCreate; i++) {
                            double angle = rand.nextDouble() * 2 * Math.PI;
                            double speed = SMOKE_PUFF_INITIAL_SPEED * (0.5 + rand.nextDouble());
                            Point2D.Double puffVelocity = new Point2D.Double(Math.cos(angle) * speed,
                                    Math.sin(angle) * speed);
                            SmokePuff newPuff = new SmokePuff((Point2D.Double) grenade.position.clone(), puffVelocity);
                            smokePuffs.add(newPuff);
                            grenade.myPuffs.add(newPuff);
                        }
                    }
                }
                if (grenade.isDecoying) {
                    if (currentTime - grenade.lastDecoySoundTime > 800) {
                        if (grenade.decoyShotsLeft > 0) {
                            Weapon wep = grenade.throwerWeapon != null ? grenade.throwerWeapon : Weapon.AK47;
                            addSoundEvent(SoundEvent.SoundType.FIRE, wep.name() + "_fire", grenade.position.x,
                                    grenade.position.y, grenade.ownerId); // 虚假诱饵弹
                            visualEffects.add(new VisualEffect("muzzle", grenade.position, 50));
                            grenade.lastDecoySoundTime = currentTime;
                            grenade.decoyShotsLeft--;
                        } else {
                            grenadesToRemove.add(grenade);
                        }
                    }
                }
                continue;
            }

            // --- 连续碰撞检测物理更新 ---
            Point2D.Double currentPos = grenade.position;
            Point2D.Double velocity = grenade.velocity;
            Point2D.Double nextPos = new Point2D.Double(currentPos.x + velocity.x, currentPos.y + velocity.y);

            Line2D.Double movementRay = new Line2D.Double(currentPos, nextPos);
            CollisionResult collision = findClosestCollision(movementRay, obstacles);

            if (collision != null && collision.distance() <= currentPos.distance(nextPos)) {
                Point2D.Double normal = collision.normal();
                double dot = velocity.x * normal.x + velocity.y * normal.y;
                if (dot > 0) {
                    normal.x *= -1;
                    normal.y *= -1;
                }
                dot = velocity.x * normal.x + velocity.y * normal.y;
                velocity.x -= 2 * dot * normal.x;
                velocity.y -= 2 * dot * normal.y;
                velocity.x *= -GRENADE_BOUNCE_FRICTION;
                velocity.y *= -GRENADE_BOUNCE_FRICTION;
                grenade.velocity = velocity;
                double epsilon = 0.1;
                grenade.position.x = collision.impactPoint().x + normal.x * epsilon;
                grenade.position.y = collision.impactPoint().y + normal.y * epsilon;
                long bounceCooldown = 100;
                if (System.currentTimeMillis() - grenade.lastBounceTime > bounceCooldown) {
                    playBounceSound(grenade);
                    grenade.lastBounceTime = System.currentTimeMillis();
                }
            } else {
                grenade.position = nextPos;
            }
        }
        thrownGrenades.removeAll(grenadesToRemove);
    }

    /**
     * 在主循环中更新所有烟雾颗粒
     */
    private void updateSmokePuffs() {
        // 移除所有已经过期的烟雾
        smokePuffs.removeIf(SmokePuff::isExpired);

        // 更新每一个烟雾颗粒物理状态
        for (SmokePuff puff : smokePuffs) {
            // 如果速度已经很小，就让它完全停下
            if (puff.velocity.distance(0, 0) < 0.1) {
                puff.velocity.x = 0;
                puff.velocity.y = 0;
                continue; // 跳过后续计算
            }

            // 计算下一步的位置
            double nextX = puff.position.x + puff.velocity.x;
            double nextY = puff.position.y + puff.velocity.y;

            // 简单的碰撞检测，碰到墙就停下
            if (isPointInObstacle(new Point2D.Double(nextX, puff.position.y)) || nextX <= 0 || nextX >= width) {
                puff.velocity.x = 0;
            } else {
                puff.position.x = nextX;
            }

            if (isPointInObstacle(new Point2D.Double(puff.position.x, nextY)) || nextY <= 0 || nextY >= height) {
                puff.velocity.y = 0;
            } else {
                puff.position.y = nextY;
            }

            // 应用碰撞摩擦力(不是摩擦力！是弹墙一次的)，让速度越来越慢
            puff.velocity.x *= SMOKE_FRICTION;
            puff.velocity.y *= SMOKE_FRICTION;
        }
    }

    /**
     * 处理单个手榴弹的爆炸效果
     */
    private void detonateGrenade(ThrownGrenade grenade) {
        /** 日志：记录实际爆炸的地点 */
        Player owner = getPlayerById(grenade.ownerId);
        String ownerName = (owner != null) ? owner.name : "Unknown (" + grenade.ownerId.substring(0, 4) + ")";
        // 看看人机扔雷准不准
        // logger.accept(String.format(
        // "--- [GRENADE REALITY] --- | Bot: %s | Grenade: %s | Actual Detonation
        // Coords: (%.1f, %.1f)",
        // ownerName,
        // grenade.type.name(),
        // grenade.position.x,
        // grenade.position.y
        // ));

        /** 功能：看看是哪种投掷物 */
        switch (grenade.type) {
            case HE_GRENADE -> detonateHE(grenade);
            case FLASHBANG -> detonateFlash(grenade);
            case SMOKE_GRENADE -> startSmoking(grenade);
            case DECOY -> startDecoy(grenade);
            case MOLOTOV, INCENDIARY -> detonateMolotov(grenade);
            case KEVLAR_HELMET, DEFUSE_KIT -> {
            }
        }
    }

    /**
     * 启动烟雾弹的冒烟效果
     */
    private void startSmoking(ThrownGrenade grenade) {
        // 播放烟雾弹爆开的音效
        addSoundEvent(SoundEvent.SoundType.SMOKE_EMIT, "smoke_emit", grenade.position.x, grenade.position.y);

        // 标记烟雾为“正在冒烟”状态，并设置好总的结束时间
        grenade.isSmoking = true;
        grenade.stopSmokingTime = System.currentTimeMillis() + SMOKE_DURATION_MS;

        // 让烟雾本身在原地停止移动
        grenade.velocity.x = 0;
        grenade.velocity.y = 0;

        // v1.9[修改]不再在这里生成烟雾颗粒，交给 updateGrenades() 统一处理
    }

    /**
     * 龟内爆炸！
     * 
     * @param heGrenade
     */
    private void detonateHE(ThrownGrenade heGrenade) {
        String explosionSound = rand.nextBoolean() ? "hegrenade_detonate_02" : "hegrenade_detonate_03";
        addSoundEvent(SoundEvent.SoundType.EXPLODE, explosionSound, heGrenade.position.x, heGrenade.position.y);
        visualEffects.add(new VisualEffect("explode_he", heGrenade.position, 300));

        double blastRadius = 350.0; // 手雷范围
        double maxDamage = 105.0; // 手雷最大伤害

        List<Player> allCharacters = new ArrayList<>(players);
        allCharacters.addAll(zombies);

        for (Player p : allCharacters) {
            if (!p.isAlive())
                continue;
            double dist = p.position.distance(heGrenade.position);

            if (dist < blastRadius) {
                if (dist < 250) {
                    privateSoundEvents.add(new HeadshotEvent(p.id, "explosion_ring"));
                }
                // 手雷阻挡伤害猛降
                Line2D.Double lineOfSight = new Line2D.Double(heGrenade.position, p.position);
                boolean isBlocked = obstacles.stream().anyMatch(obs -> obs.intersects(lineOfSight.getBounds2D()));

                double damage = maxDamage * (1.0 - (dist / blastRadius));
                if (isBlocked) {
                    damage = damage * 0.1;
                    System.out.println("龟内被挡住了");
                }

                if (damage > 0) {
                    Player owner = getPlayerById(heGrenade.ownerId);
                    dealDamageAndHandleEvents(owner, p, damage, false, heGrenade.type.name());
                }
            }
        }

        for (ThrownGrenade otherGrenade : thrownGrenades) {
            // 如果是正在冒烟的烟雾弹，并且在爆炸范围内
            if (otherGrenade.isSmoking && otherGrenade.position.distance(heGrenade.position) < blastRadius) {

                // 烟雾弹的烟雾被驱散了，0.8秒后才能恢复
                otherGrenade.smokeDispersedUntil = System.currentTimeMillis() + 800L;

                // 将这个烟雾弹当前产生的所有烟雾颗粒从全局列表中移除
                smokePuffs.removeAll(otherGrenade.myPuffs);

                // 清空这个烟雾弹自己的颗粒列表，准备下次重新生成
                otherGrenade.myPuffs.clear();
            }
        }
    }

    /**
     * 闪光demo
     * 
     * @param grenade
     */
    private void detonateFlash(ThrownGrenade grenade) {
        // 随机选择一个爆炸音效并广播给所有玩家
        String soundName = rand.nextBoolean() ? "flashbang_explode1" : "flashbang_explode2";
        addSoundEvent(SoundEvent.SoundType.EXPLODE, soundName, grenade.position.x, grenade.position.y);
        addSoundEvent(SoundEvent.SoundType.EXPLODE, "FLASHBANG", grenade.position.x, grenade.position.y);
        double maxEffectRadius = 3000.0;
        long maxDuration = 4500; // 最长4.5秒(后面有倍数修正)

        List<Player> allCharacters = new ArrayList<>(players);
        allCharacters.addAll(zombies);

        for (Player p : allCharacters) {
            if (!p.isAlive())
                continue;
            double dist = p.position.distance(grenade.position);
            if (dist > maxEffectRadius)
                continue;

            // 检查视线是否被遮挡 (简单实现)
            Line2D.Double lineOfSight = new Line2D.Double(grenade.position, p.position);
            boolean blocked = obstacles.stream().anyMatch(obs -> obs.intersects(lineOfSight.getBounds2D()));
            if (blocked)
                continue;

            // 计算玩家朝向与闪光弹方向的角度
            Point2D.Double playerToGrenade = new Point2D.Double(grenade.position.x - p.position.x,
                    grenade.position.y - p.position.y);
            Point2D.Double playerFacing = new Point2D.Double(Math.cos(p.angle), Math.sin(p.angle));

            double dotProduct = playerToGrenade.x * playerFacing.x + playerToGrenade.y * playerFacing.y;
            double magPlayerToGrenade = playerToGrenade.distance(0, 0);
            double magPlayerFacing = 1.0;

            double cosAngle = dotProduct / (magPlayerToGrenade * magPlayerFacing);
            double angleFactor = Math.max(0, cosAngle); // >0 表示闪光弹在视野前方

            // 根据距离和角度计算闪光时间
            double distanceFactor = 1.0 - (dist / maxEffectRadius);
            long duration = (long) (2 * maxDuration * distanceFactor * angleFactor); // 闪光弹

            if (duration > 500) {
                flashEvents.add(new FlashEvent(p.id, duration)); // 发给客户端
                if (p.isAI && p.getAttackModule() != null) { // <--- 检查是否是AI
                    p.getAttackModule().getFlashed(duration); // <--- 通知攻击模块
                }
            }
        }
    }

    /**
     * 挺假的开枪声音，而且AI还不能识别
     * 
     * @param grenade
     */
    private void startDecoy(ThrownGrenade grenade) {
        grenade.isDecoying = true;
        grenade.decoyShotsLeft = rand.nextInt(5) + 8; // 随机射击8-12次
        grenade.detonationTime = System.currentTimeMillis() + 15000; // 诱饵弹持续15秒

        // 让诱饵弹在原地停止移动，防止客户端预测导致的“一抽一抽”抖动
        grenade.velocity.x = 0;
        grenade.velocity.y = 0;

        // 如果诱饵弹没有记录下武器，给它一个默认武器AK
        if (grenade.throwerWeapon == null) {
            // 尝试从玩家身上重新获取
            Player thrower = getPlayerById(grenade.ownerId);
            if (thrower != null) {
                // 再次执行我们的修复逻辑
                grenade.throwerWeapon = (thrower.primaryWeapon != null) ? thrower.primaryWeapon
                        : thrower.secondaryWeapon;
            } else {
                // 如果连玩家都找不到了，就给一个默认的AK47
                grenade.throwerWeapon = Weapon.AK47;
            }
        }
    }

    /**
     * 将飞行中的投掷物序列化到一个给定的JsonObject中。
     * 
     * @param state 要添加信息的JsonObject。
     */
    private void serializeThrownGrenades(JsonObject state) {
        // 如果当前没有飞行中的投掷物，则不添加该字段以节省带宽
        if (thrownGrenades.isEmpty()) {
            return;
        }
        JsonArray grenadeArray = new JsonArray();
        thrownGrenades.forEach(g -> grenadeArray.add(g.toJson()));
        state.add("thrownGrenades", grenadeArray);
    }

    /**
     * 玩家投掷真龟内
     */
    public void throwGrenade(Player thrower) {

        // v2.0 [BUG已修复]
        Item grenadeTypeForLog = thrower.getCurrentGrenade();
        // logger.accept(String.format(
        // "[THROW DEBUG] ACTION for %s: Grenade=%s, From=(%.1f, %.1f), At_Angle=%.1f°",
        // thrower.name,
        // (grenadeTypeForLog != null ? grenadeTypeForLog.name() : "UNKNOWN"),
        // thrower.position.x,
        // thrower.position.y,
        // Math.toDegrees(thrower.angle) // 记录的是 thrower 实体上真实的、最终使用的角度
        // ));
        // --- 日志结束 ---
        Item grenadeType = thrower.getCurrentGrenade();
        if (grenadeType == null)
            return;

        int count = thrower.equipment.getOrDefault(grenadeType, 0);
        if (count > 0) {
            // 扣除道具数量
            if (count - 1 > 0) {
                thrower.equipment.put(grenadeType, count - 1);
            } else {
                thrower.equipment.remove(grenadeType);
            }

            // 根据投掷者阵营和道具类型，确定要播放的语音
            String teamPrefix = (thrower.team == Player.Team.CT) ? "ct_" : "t_";
            String soundKey = null;
            int maxNum = 0; // 该类型语音的最大编号

            switch (grenadeType) {
                case HE_GRENADE:
                    soundKey = "grenade";
                    maxNum = 6;
                    break;
                case FLASHBANG:
                    soundKey = "flashbang";
                    maxNum = 4;
                    break;
                case SMOKE_GRENADE:
                    soundKey = "smoke";
                    maxNum = 5;
                    break;
                case MOLOTOV,
                        INCENDIARY:
                    soundKey = "molotov";
                    maxNum = 11;
                    break;
                case DECOY:
                    soundKey = "decoy";
                    maxNum = 3;
                    break;
                case KEVLAR_HELMET:
                    break;
            }

            // 找到了对应的语音类型 -> 随机选择一个并发送私有事件
            if (soundKey != null && maxNum > 0) {
                int soundNum = rand.nextInt(maxNum) + 1;
                // 拼接成最终的音效名 "t_grenade01"之类的
                String finalSoundName = teamPrefix + soundKey + String.format("%02d", soundNum);

                // 只有投掷者自己能听到的私有音效
                privateSoundEvents.add(new HeadshotEvent(thrower.id, finalSoundName));
            }

            // 创建和添加 ThrownGrenade 实例的逻辑
            // 右键低抛
            double initialSpeed = thrower.isRequestingUnderhandThrow ? (8.0 * 0.08) : 8.0;
            Point2D.Double startPos = new Point2D.Double(thrower.position.x, thrower.position.y);

            Point2D.Double velocity = new Point2D.Double(
                    (Math.cos(thrower.angle) * initialSpeed) + thrower.vx, // 投掷速度的X分量 + 玩家自身的X速度
                    (Math.sin(thrower.angle) * initialSpeed) + thrower.vy // 投掷速度的Y分量 + 玩家自身的Y速度
            );
            // --- 诱饵弹 ---

            Weapon weaponToMimic = null;
            if (grenadeType == Item.DECOY) {
                // 如果是诱饵弹，优先使用主武器，如果主武器不存在，则使用副武器
                weaponToMimic = (thrower.primaryWeapon != null) ? thrower.primaryWeapon : thrower.secondaryWeapon;
            }
            // 使用选择好的武器来创建诱饵弹实例
            ThrownGrenade grenade = new ThrownGrenade(grenadeType, thrower.id, thrower.team, startPos, velocity,
                    weaponToMimic);
            thrownGrenades.add(grenade);
            // [优化] 投掷后自动切换回主武器或副武器
            if (thrower.primaryWeapon != null)
                thrower.switchToSlot(1);
            else
                thrower.switchToSlot(2);
        }
    }

    /**
     * 每帧更新玩家状态/老旧版本的
     * 
     * @param p
     */
    // private void updateSinglePlayerState(Player p) {
    // // ================== [冻结AI] ==================
    // // 如果AI被冻结，则跳过其所有的物理和行为更新
    // if (p.isAI && isAiFrozen) {
    // // 为了确保冻结
    // p.vx = 0;
    // p.vy = 0;
    // p.ax = 0;
    // p.ay = 0;
    // p.isShooting = false;
    // p.isMoving = false;
    //
    // return; // 立即退出
    // }
    // // 取消其无敌状态。
    // if (p.isInvincible && System.currentTimeMillis() - p.respawnTime >
    // SPAWN_PROTECTION_MS) {
    // p.isInvincible = false;
    // }
    // // 完成换弹。
    // if (p.isReloading && System.currentTimeMillis() - p.reloadStartTime >=
    // p.currentReloadTime) {
    // p.finishReload(gameMode);
    // }
    //
    // if (p.isAlive()) { // 如果玩家还活着。
    //
    // if (gameMode == GameMode.DEMOLITION && roundPhase == RoundPhase.FREEZE_TIME)
    // {
    // p.vx *= FRICTION;
    // p.vy *= FRICTION;
    // moveWithCollision(p, p.vx, p.vy);
    // } else {
    // if (p.isInteracting) {
    // p.ax = 0; p.ay = 0; p.vx = 0; p.vy = 0;
    // } else {
    // updatePlayerPhysics(p);
    // }
    // //尝试执行"射击/投掷"
    // if ((p.isShooting || p.isRequestingUnderhandThrow) && !p.isInteracting) {
    // requestShoot(p);
    // }
    //
    //
    // if (p.isInteracting) {
    // handleInteractionProgress(p);
    // }
    // }
    //
    // // 遍历所有掉落的物品，检查玩家是否可以拾取。
    // for (DroppedItem item : new CopyOnWriteArrayList<>(droppedItems)) {
    // if (p.position.distance(item.position) < Player.SIZE &&
    // System.currentTimeMillis() - item.dropTime > 500) {
    // if (item.isBomb && p.team == Player.Team.T && !p.hasBomb) {
    // p.hasBomb = true;
    // droppedItems.remove(item);
    // logger.accept(p.name + " picked up the C4");
    // break;
    // } else if (!item.isBomb && item.weapon != null &&
    // !item.weapon.getWeaponType().isPistol()
    // && p.primaryWeapon == null) {
    // playerPickupDroppedWeapon(p.id, item.id.toString());
    // break;
    // }
    // }
    // }
    //
    // } else if (gameMode == GameMode.ZOMBIE_MODE && !p.isAI && p.team ==
    // Player.Team.ZOMBIE) {
    // if (p.respawnTime > 0 && System.currentTimeMillis() - p.respawnTime >
    // PLAYER_ZOMBIE_RESPAWN_MS) {
    // transformToPlayerZombie(p);
    // }
    // } else if (p.team != Player.Team.ZOMBIE && gameMode ==
    // GameMode.TEAM_DEATHMATCH) { // 如果玩家已死亡，且是团队死斗模式。
    // // 如果死亡时间超过3秒，重生。
    // if (p.respawnTime > 0 && System.currentTimeMillis() - p.respawnTime > 3000) {
    // if (p.isControllingBot()) {
    // logger.accept("玩家 " + p.name + " 即将复活，自动释放BOT控制。");
    // playerReleaseBot(p);
    // }
    // p.respawn(getSpawnPoint(p.team), gameMode);
    // p.hasKevlar = true;
    // p.hasHelmet = true;
    // p.armorValue = 100;
    //
    // // --- 随机给予一个道具 ---
    // giveRandomEquipment(p);
    //
    // resolveSpawnOverlaps(p); // 解决出生时的重叠问题
    // }
    // }
    //
    // else if (!p.isAlive() && !p.isControllingBot() && (gameMode ==
    // GameMode.DEMOLITION || gameMode == GameMode.ZOMBIE_MODE)) {
    // // 优先寻找一个存活的同队队友
    // Player targetToSpectate = players.stream()
    // .filter(teammate -> teammate.isAlive() && teammate.team == p.team &&
    // !teammate.id.equals(p.id))
    // .findFirst()
    // .orElse(null);
    //
    // if (targetToSpectate != null) {
    // // 如果找到了队友，就设置为“团队观战”模式
    // p.spectatorTargetId = targetToSpectate.id;
    // p.spectatorMode = "TEAM_SPECTATE";
    // } else {
    // // 如果找不到任何存活的队友，则直接进入“自由漫游”模式 (全局视野)
    // p.spectatorTargetId = null;
    // p.spectatorMode = "FREE_ROAM";
    // }
    // }
    //
    //
    // if (p.primaryWeapon != null || p.secondaryWeapon != null) { // 如果玩家持有武器。
    // p.updateSpread(BASE_SPEED);
    // p.updateSpreadAndRecoil(BASE_SPEED);
    // }
    // }

    /**
     * [修复说明] 通过武器的显示名称（如"M4A1-S"）或常量名称（如"M4A1S"）安全地查找对应的武器枚举，忽略大小写。
     * 此修复防止了因客户端发送无效武器名称而导致的服务器崩溃问题。
     * 
     * @param name 要搜索的武器名称
     * @return 匹配到的武器枚举，如果未找到则返回null
     */
    private static Weapon getWeaponByName(String name) {
        if (name == null || name.isEmpty()) { // 如果传入的名称为空，则直接返回null。
            return null;
        }
        // 替换掉名称中的"-"和" "，以进行更宽松的匹配（例如，"M4A1-S" 变成 "M4A1S"）。
        String sanitizedName = name.replace("-", "").replace(" ", "");
        for (Weapon w : Weapon.values()) { // 遍历所有已定义的武器枚举。
            // 检查显示名称是否匹配（例如 "M4A1-S"）。
            if (w.name.equalsIgnoreCase(name)) {
                return w; // 匹配成功，返回这个武器枚举。
            }
            // 检查枚举常量名是否匹配（例如 "M4A1S"）。
            if (w.name().equalsIgnoreCase(sanitizedName)) {
                return w; // 匹配成功，返回这个武器枚举。
            }
        }
        // 如果遍历完所有武器都没有找到匹配项，返回null。
        return null;
    }

    public void addPlayer(String playerId, String playerName, String selection) {
        // 尝试根据玩家ID查找是否已存在该玩家。
        Player existingPlayer = players.stream().filter(p -> p.id.equals(playerId)).findFirst().orElse(null);

        // 玩家已经存在于列表中 <意味着这是他们连接后发送的队伍/武器选择>
        if (existingPlayer != null) {
            // 忽略掉任何残留的"SPECTATOR"请求，因为玩家已经选择队伍了。
            if ("SPECTATOR".equals(selection)) {
                return;
            }

            existingPlayer.name = playerName; // 更新玩家名称。
            existingPlayer.hasChosenTeam = true; // 标记为已选择队伍
            GameMode currentMode = getGameMode(); // 获取当前游戏模式。

            // 根据游戏模式处理选择。
            if (currentMode == GameMode.ZOMBIE_MODE) {
                if ("ZOMBIE".equals(selection)) {
                    existingPlayer.team = Player.Team.ZOMBIE;
                    transformToPlayerZombie(existingPlayer);
                } else {
                    existingPlayer.team = Player.Team.CT;
                    Weapon selectedWeapon = getWeaponByName(selection);
                    if (selectedWeapon != null) {
                        existingPlayer.setWeapon(selectedWeapon, gameMode);
                        existingPlayer.nextWeapon = selectedWeapon.name();
                    } else {
                        existingPlayer.setWeapon(Weapon.NEGEV, GameMode.ZOMBIE_MODE);
                        existingPlayer.nextWeapon = "NEGEV";
                        logger.accept("Player " + playerName + " chose invalid weapon '" + selection
                                + "', defaulting to NEGEV.");
                    }
                    existingPlayer.respawn(getSpawnPoint(existingPlayer.team), gameMode);
                    resolveSpawnOverlaps(existingPlayer);
                }
            } else if (currentMode == GameMode.DEATHMATCH) {
                // 玩家选择一个 "外观" 队伍
                Player.Team cosmeticTeam = "T".equals(selection) ? Player.Team.T : Player.Team.CT;
                existingPlayer.team = cosmeticTeam;
                existingPlayer.health = 0; // 玩家在选择第一把武器后才会重生
                logger.accept("玩家 " + playerName + " (ID: " + playerId + ") 已加入死斗模式，外观为 " + cosmeticTeam);
            } else { // 如果是团队死斗或爆破模式。
                Player.Team team = "T".equals(selection) ? Player.Team.T : Player.Team.CT; // 根据选择确定队伍。
                existingPlayer.team = team; // 设置玩家队伍。
                existingPlayer.money = 800; // 加入/切换队伍时重置金钱。

                if (currentMode == GameMode.TEAM_DEATHMATCH) { // 如果是团队死斗。
                    existingPlayer.health = 0; // 玩家在选择第一把武器后才会重生。
                } else { // 如果是爆破模式。
                    existingPlayer.setWeapon(team == Player.Team.T ? Weapon.GLOCK18 : Weapon.USPS); // 根据队伍设置初始手枪。
                    // 新的回合逻辑会处理玩家的正确出生。
                    existingPlayer.position = getSpawnPoint(team); // 暂时将他们放在出生点。
                    resolveSpawnOverlaps(existingPlayer); // 解决出生重叠问题
                    existingPlayer.health = 100; // 设置满生命值。
                }
            }
            logger.accept("玩家 " + playerName + " (ID: " + playerId + ") 已加入队伍 " + existingPlayer.team); // 记录日志。
            return; // 处理完毕，返回。
        }

        // 这是一个全新的客户端连接！
        // 客户端的初始握手信号使用 "SPECTATOR" 作为选择。
        if ("SPECTATOR".equals(selection)) {
            // 创建一个占位符玩家对象。他们还没有出生，也没有被分配到真正的队伍。
            // 这只是在服务器上注册他们的ID。
            Player player = new Player(playerId, playerName, new Point2D.Double(-100, -100), Player.Team.CT, false,
                    gameMode, this, aiDifficulty, logger);
            player.health = 0; // 确保这个占位符玩家不被认为是“存活”的。

            player.connectionTime = System.currentTimeMillis(); // 记录连接时间
            player.hasChosenTeam = false; // 标记为尚未选择队伍

            players.add(player); // 将玩家添加到列表中。
            logger.accept("玩家 " + playerName + " (ID: " + playerId + ") 已预连接。"); // 记录日志。
            return; // 处理完毕。
        }

        /** [BUG修复] */
        // 备用情况：一个新玩家在没有发送"SPECTATOR"握手信号的情况下连接。
        // 在当前的客户端逻辑下这不应该发生，但提供了代码的健壮性。
        logger.accept("警告: 新玩家 " + playerName + " 使用了非标准的初始选择 '" + selection + "'。正在创建占位符。");
        Player player = new Player(playerId, playerName, new Point2D.Double(-100, -100), Player.Team.CT, false,
                gameMode, this, aiDifficulty, logger);
        player.health = 0; // 确保不存活。

        player.connectionTime = System.currentTimeMillis(); // 记录连接时间
        player.hasChosenTeam = false; // 标记为尚未选择队伍

        players.add(player); // 添加到列表。
    }

    // 处理玩家选择武器的请求。
    public void playerChooseWeapon(String playerId, String weaponName) {
        players.stream().filter(p -> p.id.equals(playerId)).findFirst().ifPresent(player -> { // 找到对应的玩家。
            if (gameMode == GameMode.TEAM_DEATHMATCH) { // 团队死斗模式。
                Weapon selectedWeapon = getWeaponByName(weaponName); // 安全地获取武器枚举。
                if (selectedWeapon != null) { // 武器有效。
                    player.setWeapon(selectedWeapon); // 设置武器。
                    if (selectedWeapon.getWeaponType().isPistol()) {
                        player.selectedSecondaryName = selectedWeapon.name();
                    } else {
                        player.selectedPrimaryName = selectedWeapon.name();
                    }
                } else { // 如果武器无效。
                    player.setWeapon(Weapon.AK47); // 默认设置为AK47。
                    player.selectedPrimaryName = "AK47";
                    logger.accept("无效的武器选择 '" + weaponName + "', 已默认设置为 AK47。"); // 记录日志。
                }
                if (player.health <= 0) { // 玩家已经死亡。
                    player.respawn(getSpawnPoint(player.team), gameMode); // 立即重生。
                    resolveSpawnOverlaps(player); // [BUG修复] 解决出生重叠问题
                }
            } else if (gameMode == GameMode.DEATHMATCH) {
                Weapon selectedWeapon = getWeaponByName(weaponName); // 安全地获取武器枚举。
                if (selectedWeapon != null) { // 武器有效。
                    // 注意：死斗模式不检查派系
                    player.setWeapon(selectedWeapon); // 设置武器。
                    if (selectedWeapon.getWeaponType().isPistol()) {
                        player.selectedSecondaryName = selectedWeapon.name();
                    } else {
                        player.selectedPrimaryName = selectedWeapon.name();
                    }
                } else { // 如果武器无效。
                    player.setWeapon(Weapon.AK47); // 默认设置为AK47。
                    player.selectedPrimaryName = "AK47";
                    logger.accept("无效的武器选择 '" + weaponName + "', 已默认设置为 AK47。"); // 记录日志。
                }
                if (player.health <= 0) { // 玩家已经死亡。
                    player.respawn(getSpawnPoint(player.team), gameMode); // 立即重生。
                    resolveSpawnOverlaps(player);
                }
            }

            else if (gameMode == GameMode.DEMOLITION) { // 爆破模式。

                // ===============================================
                // [购买时间检查]
                // ===============================================

                // 回合总时长 + 冻结时间 = 125秒
                final long TOTAL_ROUND_START_MS = DEMO_ROUND_TIME_MS + DEMO_FREEZE_TIME_MS;
                // 购买窗口截止时间点 (回合开始后 15 秒)
                final long BUY_WINDOW_END_MS = 15000L;

                long elapsedTimeInRound = System.currentTimeMillis() - roundStartTime;

                // 如果经过的时间超出了购买窗口 (15秒)，则拒绝购买。
                if (elapsedTimeInRound > BUY_WINDOW_END_MS) {
                    logger.accept("玩家 " + playerId + " 购买武器失败，购买时间已过 (" + (elapsedTimeInRound / 1000) + "s / 15s)。");
                    return; // 拒绝购买请求
                }
                Weapon newWeapon = getWeaponByName(weaponName); // 安全地获取武器枚举。
                if (newWeapon != null) {
                    Weapon.Faction faction = newWeapon.getFaction(); // 调用新方法获取阵营
                    boolean canBuy = (faction == Weapon.Faction.ANY) ||
                            (faction == Weapon.Faction.CT && player.team == Player.Team.CT) ||
                            (faction == Weapon.Faction.T && player.team == Player.Team.T);

                    if (!canBuy) {
                        logger.accept("[Purchase Denied] " + player.name + " (" + player.team + ") cannot buy "
                                + newWeapon.name + " (" + faction + ").");
                        return; // 购买被拒绝，直接结束
                    }
                    // 如果玩家有足够的钱并且没有购买相同的武器。
                    if (player.money >= newWeapon.cost && player.primaryWeapon != newWeapon) {
                        player.money -= newWeapon.cost; // 扣钱！
                        player.itemsBoughtThisFreezeTime.add(newWeapon.name()); // 记录本次购买，用于撤销。

                        // 如果玩家已经有一把主武器，则先丢弃旧的。
                        if (player.primaryWeapon != null && !player.primaryWeapon.getWeaponType().isPistol()) {
                            playerDropWeapon(playerId);
                        }

                        player.setWeapon(newWeapon); // 设置新武器。
                        privateSoundEvents.add(new HeadshotEvent(player.id, "buy")); // 发送一个私有的购买音效事件。
                    }
                } else { // 武器名称无效的情况
                    logger.accept("Invalid weapon purchase attempt: " + weaponName); // 记录日志。
                }
            } else if (gameMode == GameMode.ZOMBIE_MODE) { // 如果是僵尸模式。
                Weapon selectedWeapon = getWeaponByName(weaponName); // 安全地获取武器枚举。
                if (selectedWeapon != null) { // 如果武器有效。
                    player.setWeapon(selectedWeapon, gameMode); // 设置武器。
                    player.nextWeapon = selectedWeapon.name(); // 记录下次重生的武器。
                } else { // 如果武器无效。
                    player.setWeapon(Weapon.M249, gameMode); // 默认设置为M249。
                    player.nextWeapon = "M249";
                    logger.accept("无效的武器选择 '" + weaponName + "', 已默认设置为 M249。"); // 记录日志。
                }
                if (player.health <= 0) { // 如果玩家已经死亡。
                    player.respawn(getSpawnPoint(player.team), gameMode); // 立即重生。
                    resolveSpawnOverlaps(player); // 解决出生重叠问题
                }
            }
        });
    }

    // 处理特定游戏模式的核心逻辑。
    private long lastZombieItemDropTime = 0; // 记录上次在僵尸模式发放道具的时间

    private void handleGameModeLogic() {
        /**
         * 僵尸模式
         */
        if (gameMode == GameMode.ZOMBIE_MODE) { // 如果是僵尸模式。
            // 检查是否还有任何幸存者存活。
            boolean survivorsAlive = players.stream().anyMatch(p -> p.isAlive() && p.team != Player.Team.ZOMBIE);
            if (!survivorsAlive) { // 如果没有幸存者了。
                finishGame();
                return;
            }
            // --- 僵尸模式定时发放道具 ---
            long currentTime = System.currentTimeMillis();
            // 如果游戏已经开始超过15秒，并且距离上次发放也超过了15秒
            if (lastZombieItemDropTime > 0 && currentTime - lastZombieItemDropTime > 15000) {

                // 获取所有存活的幸存者
                List<Player> aliveSurvivors = players.stream()
                        .filter(p -> p.isAlive() && p.team == Player.Team.CT)
                        .collect(Collectors.toList());

                // 玩家道具池：燃烧弹 or 龟内
                List<Item> playerItemPool = Arrays.asList(Item.INCENDIARY, Item.HE_GRENADE);

                for (Player survivor : aliveSurvivors) {
                    if (survivor.isAI) {
                        // AI逻辑：强制清空背包，并只给予燃烧瓶
                        Item itemToGive = Item.MOLOTOV;
                        survivor.equipment.clear();
                        survivor.equipment.put(itemToGive, 1);
                        // logger.accept("僵尸模式补给：给予AI " + survivor.name + " 一个 " + itemToGive.name());

                    } else { // 是入类
                        // 检查玩家背包是否是空的
                        if (survivor.equipment.isEmpty()) {
                            // 玩家道具池中随机选择一个
                            Item itemToGive = playerItemPool.get(rand.nextInt(playerItemPool.size()));
                            survivor.equipment.put(itemToGive, 1);
                            logger.accept("僵尸模式补给：给予玩家 " + survivor.name + " 一个 " + itemToGive.name());
                        }
                    }
                }
                lastZombieItemDropTime = currentTime; // 更新发放时间戳
            }

            // 如果是游戏刚开始的第一次发放
            if (lastZombieItemDropTime == 0 && currentTime - gameStartTime > 15000) {
                lastZombieItemDropTime = currentTime; // 初始化计时器，下个15秒才开始发放
            }

            // 检查是否有存活的人类转变成的僵尸
            boolean anyPlayerZombieAlive = players.stream().anyMatch(p -> p.isAlive() && p.team == Player.Team.ZOMBIE);

            // 如果当前波次所有僵尸都已生成且被消灭，并且不在等待下一波的状态。
            if (zombiesToSpawn == 0 && zombies.isEmpty() && !anyPlayerZombieAlive && !waitingForNextWave) {
                waitingForNextWave = true; // 开始等待下一波。
                nextWaveStartTime = System.currentTimeMillis() + 5000; // 设置5秒后开始下一波。
            }
            // 如果正在等待下一波，并且时间已到。
            if (waitingForNextWave && System.currentTimeMillis() >= nextWaveStartTime) {
                startNextWave(); // 开始下一波。
            }
            // 如果还有僵尸需要生成，并且距离上一个僵尸生成的时间间隔已到。
            if (zombiesToSpawn > 0 && System.currentTimeMillis() - lastZombieSpawnTime > zombieSpawnInterval) {
                spawnZombie(); // 生成一个僵尸。
                zombiesToSpawn--; // 待生成僵尸数量减一。
                lastZombieSpawnTime = System.currentTimeMillis(); // 更新上次生成时间。
            }
        }
        /**
         * 爆破模式
         */
        else if (gameMode == GameMode.DEMOLITION) { // 如果是爆破模式。
            long time = System.currentTimeMillis(); // 获取当前时间。
            switch (roundPhase) { // 根据当前回合阶段进行处理。
                case FREEZE_TIME: // 如果是冻结时间。
                    // 如果冻结时间已经结束。
                    if (time - roundStartTime > DEMO_FREEZE_TIME_MS) {
                        roundPhase = RoundPhase.IN_PROGRESS; // 将阶段切换到“进行中”。
                        players.forEach(p -> p.isInvincible = false); // 取消所有玩家的无敌状态。
                    }
                    break;
                case IN_PROGRESS: // 如果回合正在进行中。
                    checkRoundEndConditions(); // 检查是否满足回合结束的条件。
                    break;
                case ROUND_OVER: // 如果回合已经结束。
                    // 如果回合结束后的5秒等待时间已过。
                    if (time - roundEndTime > 5000) {
                        // 如果某队得分达到胜利条件，或者达到最大回合数且分数不平。
                        if (!isRLTrainingMode
                                && (teamCTScore_DEMO == DEMO_WIN_SCORE || teamTScore_DEMO == DEMO_WIN_SCORE
                                        || (currentRound >= DEMO_MAX_ROUNDS && teamCTScore_DEMO != teamTScore_DEMO))) {
                            finishGame();
                        } else if (isRLTrainingMode && currentRound >= 9999) {
                            finishGame();
                        } else { // 否则。
                            startNewRound(); // 开始新的一回合。
                        }
                    }
                    break;
            }
        } else if (gameMode == GameMode.DEATHMATCH || gameMode == GameMode.TEAM_DEATHMATCH) {
            long time = System.currentTimeMillis();
            if (roundPhase == RoundPhase.ROUND_OVER) {
                if (time - roundEndTime > 5000) {
                    finishGame();
                }
            } else {
                if ((time - gameStartTime) / 1000 >= TDM_GAME_DURATION_SECONDS) {
                    if (gameMode == GameMode.TEAM_DEATHMATCH) {
                        if (teamCTScore_TDM > teamTScore_TDM)
                            endRound(Player.Team.CT, "Time ran out");
                        else
                            endRound(Player.Team.T, "Time ran out");
                        finishGame();
                    } else {
                        finishGame();
                    }
                }
            }
        }
    }

    // 开始一个新回合（爆破模式）。

    private void startNewRound() {
        currentRound++;

        // --- 回合开始时，清除所有在场上的投掷物和效果
        // 为所有正在燃烧的火焰创建“停止音效”事件
        for (FirePatch fire : firePatches) {
            addSoundEvent(SoundEvent.SoundType.FIRE_LOOP_STOP, fire.id.toString(), fire.position.x, fire.position.y);
        }
        // 清空所有列表
        thrownGrenades.clear();
        smokePuffs.clear();
        firePatches.clear();
        visualEffects.clear(); // 同时清除视觉特效，如枪口火焰

        // 换边逻辑
        if (!isRLTrainingMode && currentRound == DEMO_WIN_SCORE) {
            logger.accept("半场换边！分数交换，重置所有玩家状态。");
            int tempScore = teamCTScore_DEMO;
            teamCTScore_DEMO = teamTScore_DEMO;
            teamTScore_DEMO = tempScore;

            for (Player p : players) {
                p.team = (p.team == Player.Team.T) ? Player.Team.CT : Player.Team.T;
                p.money = 800;
                p.equipment.clear();
                p.hasDefuseKit = false;
                p.hasKevlar = false;
                p.hasHelmet = false;
                p.armorValue = 0;
                p.consecutiveLosses = 0;
                // 新回合开始时，强制解除BOT控制
                if (p.isControllingBot()) {
                    playerReleaseBot(p);
                }
                // 强制清除主武器和副武器
                p.primaryWeapon = null;
                p.secondaryWeapon = null;
                // 重新设置正确的初始手枪
                p.setWeapon(p.team == Player.Team.T ? Weapon.GLOCK18 : Weapon.USPS, gameMode);
            }
        }

        roundPhase = RoundPhase.FREEZE_TIME;
        roundStartTime = System.currentTimeMillis();
        bombPlanted = false;
        bombPosition = null;
        bombPlanterId = null;
        roundWinner = null;
        roundWinReason = "";
        droppedItems.clear();
        tKillsLastRound = 0;

        players.forEach(p -> p.wasAliveLastRound = p.isAlive());

        players.forEach(p -> {
            if (currentRound != DEMO_WIN_SCORE) {
                if (p.wasAliveLastRound) {
                    // 幸存者逻辑：补满所有弹药
                    if (p.primaryWeapon != null) {
                        p.primary_currentAmmo = p.primaryWeapon.magazineSize;
                        p.primary_reserveAmmo = p.primaryWeapon.magazineSize * 4;
                    }
                    if (p.secondaryWeapon != null) {
                        p.secondary_currentAmmo = p.secondaryWeapon.magazineSize;
                        p.secondary_reserveAmmo = p.secondaryWeapon.magazineSize * 4;
                    }
                    // 更新手上的弹药
                    if (p.currentSlot == 1 && p.primaryWeapon != null) {
                        p.currentAmmo = p.primary_currentAmmo;
                        p.reserveAmmo = p.primary_reserveAmmo;
                    } else if (p.currentSlot == 2 && p.secondaryWeapon != null) {
                        p.currentAmmo = p.secondary_currentAmmo;
                        p.reserveAmmo = p.secondary_reserveAmmo;
                    }
                } else {
                    // 阵亡者逻辑
                    p.primaryWeapon = null;
                    p.primary_currentAmmo = 0;
                    p.primary_reserveAmmo = 0;
                    p.setWeapon(p.team == Player.Team.T ? Weapon.GLOCK18 : Weapon.USPS, gameMode);
                    p.equipment.clear();
                    p.hasDefuseKit = false;
                }
            }

            // =====[BUG修复] 新回合开始时，强制解除BOT控制 =======================
            if (p.isControllingBot()) {
                logger.accept("玩家 " + p.name + " 即将开始新回合，自动释放BOT控制。");
                playerReleaseBot(p);
            }
            // ==================================================================

            // [RL Auto-Buy]: Give RL agents full equipment and weapons automatically so the
            // ML model focuses purely on tactics.
            if (isRLTrainingMode && p.isAI) {
                List<Weapon> tRifles = Arrays.asList(Weapon.AK47, Weapon.GALIL, Weapon.SG553, Weapon.MAC10);
                List<Weapon> ctRifles = Arrays.asList(Weapon.M4A4, Weapon.M4A1S, Weapon.FAMAS, Weapon.AUG, Weapon.MP9);
                p.primaryWeapon = (p.team == Player.Team.T) ? tRifles.get(new Random().nextInt(tRifles.size()))
                        : ctRifles.get(new Random().nextInt(ctRifles.size()));
                p.secondaryWeapon = (p.team == Player.Team.T) ? Weapon.GLOCK18 : Weapon.USPS;
                p.hasKevlar = true;
                p.hasHelmet = true;
                p.armorValue = 100;
                if (p.team == Player.Team.CT)
                    p.hasDefuseKit = true;
            }

            p.resetForNewRound();
            p.shootTimeIndex = 0;
            p.ax = 0;
            p.ay = 0;
            p.vx = 0;
            p.vy = 0;
            p.isShooting = false;
            p.isRequestingUnderhandThrow = false;
            p.position = getSpawnPoint(p.team);
            p.isInvincible = true;

            if (p.primaryWeapon != null) {
                p.primary_currentAmmo = p.primaryWeapon.magazineSize;
                p.primary_reserveAmmo = p.primaryWeapon.magazineSize * 4; // 给予4个备用弹匣
            }
            if (p.secondaryWeapon != null) {
                p.secondary_currentAmmo = p.secondaryWeapon.magazineSize;
                // 如果是左轮和cz75，只给1个备用的
                if (p.secondaryWeapon == (Weapon.R8)) {
                    p.secondary_reserveAmmo = p.secondaryWeapon.magazineSize * 4;
                } else
                    p.secondary_reserveAmmo = p.secondaryWeapon.magazineSize * 4;
            }

            // 回合开始时自动切换武器
            if (p.primaryWeapon != null) {
                p.switchToSlot(1); // 如果有主武器，自动切换到主武器
            } else {
                p.switchToSlot(2); // 否则，切换到副武器（手枪）
            }
        });

        players.forEach(this::resolveSpawnOverlaps);

        players.forEach(p -> p.hasBomb = false);
        List<Player> terrorists = players.stream().filter(p -> p.team == Player.Team.T && p.isAlive())
                .collect(Collectors.toList());
        if (!terrorists.isEmpty()) {
            terrorists.get(rand.nextInt(terrorists.size())).hasBomb = true;
        }

        int soundNum = rand.nextInt(10) + 1;
        String soundName = "radiobotstart" + String.format("%02d", soundNum);
        for (Player p : players) {
            if (p.team == Player.Team.CT) {
                privateSoundEvents.add(new HeadshotEvent(p.id, "ct_" + soundName));
            } else {
                privateSoundEvents.add(new HeadshotEvent(p.id, "t_" + soundName));
            }
        }

        logger.accept("开始回合: " + currentRound);
    }

    // 结束当前回合。
    private void endRound(Player.Team winner, String reason) {
        if (roundPhase == RoundPhase.ROUND_OVER)
            return; // 如果已经结束，则不再执行。

        roundPhase = RoundPhase.ROUND_OVER;
        roundEndTime = System.currentTimeMillis();
        roundWinner = winner;
        roundWinReason = reason;

        if (winner == Player.Team.CT)
            teamCTScore_DEMO++;
        else
            teamTScore_DEMO++;

        awardRoundEndMoney(winner);

        // 先确定要播放哪个系列、哪个编号的欢呼声
        String soundPrefix;
        int soundMax;
        if (rand.nextBoolean()) {
            soundPrefix = "radiobotendclean";
            soundMax = 11;
        } else {
            soundPrefix = "radiobotendsolid";
            soundMax = 8;
        }
        int soundNum = rand.nextInt(soundMax) + 1;
        String soundBaseName = soundPrefix + String.format("%02d", soundNum); // "radiobotendsolid04"

        // 根据胜利方，给基础名字加上阵营前缀
        String winnerPrefix = (winner == Player.Team.CT) ? "ct_" : "t_";
        String finalSoundName = winnerPrefix + soundBaseName; // 最终得到 "ct_radiobotendsolid04" 或 "t_radiobotendsolid04"

        // 遍历所有玩家，只把这个专属声音发给胜利方的成员
        for (Player p : players) {
            if (p.team == winner) {
                privateSoundEvents.add(new HeadshotEvent(p.id, finalSoundName));
            }
        }

        // 同时也处理C4相关的全局语音
        if ("Bomb defused".equals(reason)) {
            broadcastGlobalVoice("ALL_DEMOLITION/c4/bombdef");
        }
        String globalWinVoice = (winner == Player.Team.CT) ? "ALL_DEMOLITION/c4/ctwin" : "ALL_DEMOLITION/c4/terwin";
        // 使用Timer延迟2.2秒播放，避免声音重叠
        new java.util.Timer().schedule(new java.util.TimerTask() {
            @Override
            public void run() {
                broadcastGlobalVoice(globalWinVoice);
            }
        }, 2200);

        List<Player> winners = players.stream().filter(p -> p.team == winner && p.isAlive())
                .collect(Collectors.toList());
        List<DroppedItem> availableGuns = droppedItems.stream().filter(item -> !item.isBomb && item.weapon != null)
                .collect(Collectors.toList());

        if (!winners.isEmpty() && !availableGuns.isEmpty()) {
            for (Player p : winners) {
                // 如果玩家只拿着手枪，他是最优先捡枪的人
                if (p.primaryWeapon == null) {
                    // 寻找地上最好的枪
                    availableGuns.stream()
                            .filter(gun -> gun.weapon.getWeaponType().isLongRange()) // 优先捡步枪/狙击
                            .min(Comparator.comparingDouble(gun -> gun.position.distance(p.position))) // 找最近的
                            .ifPresent(bestGun -> {
                                playerPickupDroppedWeapon(p.id, bestGun.id.toString());
                                availableGuns.remove(bestGun); // 这把枪被捡了
                                logger.accept("[POST-ROUND] " + p.name + " picked up a better weapon: "
                                        + bestGun.weapon.name);
                            });
                }
            }
        }

        logger.accept("回合 " + currentRound + " 结束. 获胜方: " + winner + ", 原因: " + reason);
    }

    // 检查回合是否结束。
    private void checkRoundEndConditions() {
        long time = System.currentTimeMillis(); // 获取当前时间。
        // 检查CT方和T方是否还有人存活。
        boolean ctAlive = players.stream().anyMatch(p -> p.team == Player.Team.CT && p.isAlive());
        boolean tAlive = players.stream().anyMatch(p -> p.team == Player.Team.T && p.isAlive());

        if (bombPlanted) { // 如果炸弹已经安放。
            if (!ctAlive) { // 如果CT被全歼。
                endRound(Player.Team.T, "Counter-Terrorists eliminated"); // T方获胜。
            } else if (time - bombPlantTime >= DEMO_BOMB_TIME_MS) { // 如果炸弹爆炸。

                // --- 调试信息 ---
                System.out.println("[服务器调试] 炸弹爆炸！正在创建 'explode_c4' 特效和音效...");

                // 添加爆炸音效 (公开事件)
                addSoundEvent(SoundEvent.SoundType.EXPLODE, "weapons/c4/c4_explode1", bombPosition.x, bombPosition.y);
                addSoundEvent(SoundEvent.SoundType.GENERIC, "weapons/c4/c4_exp_deb1",
                        bombPosition.x + rand.nextInt(100) - 50, bombPosition.y + rand.nextInt(100) - 50);

                // 巨大的视觉特效 (这是最关键的一行！)
                visualEffects.add(new VisualEffect("explode_c4", bombPosition, 1500)); // 持续1.5秒

                // 对范围内的所有玩家造成伤害
                double blastRadius = 800.0; // <-- 爆炸范围（半径，单位：像素）
                double maxDamage = 600.0; // <-- 在爆炸中心能造成的最大伤害

                for (Player p : players) {
                    if (!p.isAlive())
                        continue;
                    double dist = p.position.distance(bombPosition);
                    Line2D.Double lineOfSight = new Line2D.Double(bombPosition, p.position);
                    boolean isBlocked = obstacles.stream().anyMatch(obs -> obs.intersects(lineOfSight.getBounds2D()));

                    if (dist < blastRadius) {
                        // 伤害衰减：距离越远，伤害越低（线性衰减）
                        double damageFalloff = 1.0 - (dist / blastRadius);
                        double damage = maxDamage * damageFalloff;

                        // 掩体减伤：如果中间有墙，伤害大幅降低(阻挡)
                        if (isBlocked) {
                            damage *= 0.6; // <-- 掩体减伤系数 (0.6 = 只受到60%的伤害)
                        }

                        if (damage > 0) {
                            Player planter = getPlayerById(bombPlanterId);
                            dealDamageAndHandleEvents(planter, p, damage, false, "C4");
                        }
                    }
                }

                endRound(Player.Team.T, "The bomb has exploded"); // T方获胜。
            }
        } else { // 如果炸弹未安放。
            if (!tAlive) { // 如果T方被全歼。
                endRound(Player.Team.CT, "Terrorists eliminated"); // CT方获胜。
            } else if (!ctAlive) { // 如果CT方被全歼。
                endRound(Player.Team.T, "Counter-Terrorists eliminated"); // T方获胜。
            } else if (time - roundStartTime >= (DEMO_ROUND_TIME_MS + DEMO_FREEZE_TIME_MS)) { // 如果时间耗尽。
                endRound(Player.Team.CT, "Time ran out"); // CT方获胜。
            }
        }
    }

    /** v2.6 旧的获取ID的方法就不要了 */
    // public Player getPlayerById(String id) {
    // if (id == null) return null;
    // return players.stream().filter(p ->
    // id.equals(p.id)).findFirst().orElse(null);
    // }
    public Player getPlayerById(String id) {
        if (id == null)
            return null;

        // 将 players 和 zombies 两个列表的流合并成一个
        return Stream.concat(players.stream(), zombies.stream())
                .filter(p -> id.equals(p.id)) // 在合并后的流中查找ID
                .findFirst() // 找到第一个匹配的
                .orElse(null); // 如果没找到则返回null
    }

    // 回合结束时发钱。
    private void awardRoundEndMoney(Player.Team winningTeam) {
        // 定义连败奖励数组。
        int[] lossBonus = { 1900, 2400, 2900, 3400, 3900 };
        int winBonus = 3250; // 胜利奖励。
        int plantBonus = 300; // 安放C4奖励。
        int ctKillBonus = 50 * tKillsLastRound; // CT击杀的全队奖励

        for (Player p : players) { // 遍历所有玩家。
            boolean wonRound = p.team == winningTeam; // 判断玩家是否是胜利方。
            if (currentRound == 1 || currentRound == 13) {
                p.consecutiveLosses = 1; // 手枪局的是 1
            }
            if (wonRound) { // 如果是胜利方。
                p.money += winBonus; // 给予胜利奖励。
                p.consecutiveLosses = Math.max(0, p.consecutiveLosses - 2); // 连败次数减少。
            } else { // 如果是失败方。
                p.consecutiveLosses = Math.min(4, p.consecutiveLosses + 1); // 连败次数增加，最多5次。
                if (p.consecutiveLosses > 0) { // 如果有连败。
                    p.money += lossBonus[p.consecutiveLosses - 1]; // 根据连败次数给予奖励。
                }
            }

            if (p.team == Player.Team.CT) { // 如果是CT。
                p.money += ctKillBonus; // 给予击杀奖励。
            }

            if (p.id.equals(bombPlanterId) && !wonRound) { // 如果玩家是安放C4的人但输了回合。
                p.money += plantBonus; // 给予安放奖励。
            }
            if (p.money > 16000)
                p.money = 16000; // 确保金钱不超过上限16000。
        }
    }

    // 处理玩家购买物品的请求。
    public void playerBuyItem(String playerId, String itemName) {
        // 游戏模式是爆破模式。
        if (gameMode != GameMode.DEMOLITION) {
            return;
        }
        // 获取当前回合的剩余总时间（毫秒）
        // 回合总时长 (1分55秒) + 冻结时间 (10秒) = 125秒
        final long TOTAL_ROUND_START_MS = DEMO_ROUND_TIME_MS + DEMO_FREEZE_TIME_MS;

        // 购买窗口截止时间点 (回合开始后 15 秒)
        // 125秒 - 110秒 = 15秒 (15000 毫秒)
        final long BUY_WINDOW_END_MS = 15000L;

        long elapsedTimeInRound = System.currentTimeMillis() - roundStartTime;

        // 如果经过的时间已经超出了购买窗口，则拒绝购买。
        if (elapsedTimeInRound > BUY_WINDOW_END_MS) {
            logger.accept("玩家 " + playerId + " 购买物品失败，购买时间已过 (" + (elapsedTimeInRound / 1000) + "s / 15s)。");
            return;
        }

        // 找到发出请求的玩家。
        Player player = players.stream().filter(p -> p.id.equals(playerId)).findFirst().orElse(null);
        if (player == null)
            return; // 如果找不到玩家，则返回

        try { // 尝试将物品名称转换为Item枚举。
            Item itemToBuy = Item.valueOf(itemName);
            int purchaseCost = calculatePurchaseCost(itemToBuy, player.hasKevlar, player.hasHelmet);
            if (player.money >= purchaseCost) { // 如果玩家有足够的钱。
                boolean purchaseMade = false; // 标记是否成功购买。

                if (itemToBuy.type == Item.ItemType.GEAR) { // 如果是装备。

                    // 购买 胸甲 的条件：之前没有甲，或者甲的耐久度低于80
                    if (itemToBuy == Item.KEVLAR && (!player.hasKevlar || player.armorValue < 80)) {
                        player.hasKevlar = true;
                        player.armorValue = 100; // 无论如何，买完都补满到100
                        purchaseMade = true;
                    }
                    // 购买头 + 甲的条件：之前没有头盔（升级），或者甲的耐久度低于80（补甲）
                    else if (itemToBuy == Item.KEVLAR_HELMET && (!player.hasHelmet || player.armorValue < 80)) {
                        player.hasKevlar = true;
                        player.hasHelmet = true;
                        player.armorValue = 100; // 无论如何，买完都补满到100
                        purchaseMade = true;
                    }
                    // 购买拆弹器的逻辑不变
                    else if (itemToBuy == Item.DEFUSE_KIT && player.team == Player.Team.CT && !player.hasDefuseKit) {
                        player.hasDefuseKit = true;
                        purchaseMade = true;
                    }
                } else { // 如果是手雷。
                    int currentQuantity = player.equipment.getOrDefault(itemToBuy, 0); // 获取当前拥有该手雷的数量。
                    // T不能买燃烧弹，CT不能买燃烧瓶。
                    if (itemToBuy == Item.MOLOTOV
                            && (player.equipment.containsKey(Item.INCENDIARY) || player.team != Player.Team.T))
                        return;
                    if (itemToBuy == Item.INCENDIARY
                            && (player.equipment.containsKey(Item.MOLOTOV) || player.team != Player.Team.CT))
                        return;

                    if (currentQuantity < itemToBuy.maxQuantity) { // 如果还没达到最大携带数量。
                        player.equipment.put(itemToBuy, currentQuantity + 1); // 数量加一。
                        purchaseMade = true; // 购买成功。
                    }
                }

                if (purchaseMade) { // 如果购买成功。
                    player.money -= purchaseCost; // 已有胸甲升级头盔时只收 350 差价。
                    player.itemsBoughtThisFreezeTime.add(itemToBuy.name()); // 记录购买。
                    player.itemPurchaseCostsThisFreezeTime.put(itemToBuy.name(), purchaseCost);
                    privateSoundEvents.add(new HeadshotEvent(player.id, "buy")); // 发送购买音效。
                }
            }
        } catch (IllegalArgumentException e) { // 物品名称无效。
            logger.accept("无效的购买项目: " + itemName); // 记录日志。
        }
    }

    static int calculatePurchaseCost(Item item, boolean hasKevlar, boolean hasHelmet) {
        boolean helmetUpgrade = item == Item.KEVLAR_HELMET && hasKevlar && !hasHelmet;
        return helmetUpgrade ? item.cost - Item.KEVLAR.cost : item.cost;
    }

    // 处理伤害和相关事件。
    // private void dealDamageAndHandleEvents(Player shooter, Player target, double
    // baseDamage, boolean isHeadshot, String weaponName) {
    // if (target == null || target.isInvincible) return; // 如果目标是无敌的，则不造成伤害。
    //
    // // 如果shooter为null（例如手榴弹爆炸时所有者已断开连接），则无法处理后续的击杀者逻辑。
    // // 在这种情况下，伤害仍然会造成，但不会有击杀记录。
    // if (shooter == null) {
    // target.takeDamage(new Player.DamageInfo((int) baseDamage, isHeadshot));
    // return;
    // }
    //
    // double damageToApply = baseDamage; // 初始伤害为武器的基础伤害。
    // // --- 伤害衰减计算 ---
    // Weapon weapon = shooter.getCurrentWeapon();
    // // 只有当伤害来源是玩家手中的枪械时，才计算衰减
    // if (weapon != null) {
    // // 计算射手与目标之间的距离
    // double distance = shooter.position.distance(target.position);
    //
    // // 从Weapon枚举类获取该距离下的伤害系数 (这是我们之前创建的方法)
    // double falloffMultiplier = weapon.getDamageFalloff(distance);
    //
    // // 将伤害系数应用到基础伤害上
    // damageToApply *= falloffMultiplier;
    // }
    // /** 僵尸伤害特化
    // */
    // // 检查目标是否是僵尸，并且伤害来源是道具（手雷或燃烧瓶/弹）
    // boolean isGrenadeDamage = "HE_GRENADE".equals(weaponName) ||
    // "MOLOTOV".equals(weaponName) || "INCENDIARY".equals(weaponName);
    // if (target.team == Player.Team.ZOMBIE && isGrenadeDamage) {
    // // 将基础伤害值直接视为百分比
    // // 例如，基础伤害为75，则造成僵尸最大生命值的75%
    // damageToApply = target.maxHealth * (baseDamage / 100.0);
    // // 僵尸没有护甲，所以我们直接跳过护甲计算
    // } else {
    // // 对非僵尸玩家，或者非道具伤害，执行原有的护甲计算逻辑
    // if (target.armorValue > 0 && ((isHeadshot && target.hasHelmet) ||
    // (!isHeadshot && target.hasKevlar))) {
    // weapon = shooter.getCurrentWeapon();
    // double armorPen = (weapon != null) ? weapon.armorPenetration :
    // EXPLOSIVE_ARMOR_PENETRATION;
    // double damageToHealth = damageToApply * armorPen;
    // double damageToArmor = damageToApply * (1 - armorPen);
    // int armorDamage = (int) Math.min(target.armorValue, damageToArmor);
    // target.armorValue -= armorDamage;
    // damageToApply = damageToHealth + (damageToArmor - armorDamage);
    // }
    // }
    // if (isHeadshot) { // 如果是爆头。
    // damageToApply *= 4.0; // 伤害乘以4。
    // }
    //
    // shooter.totalShotsHit++; // 射手命中数加一。
    // if (isHeadshot) { // 爆头。
    // shooter.totalHeadshots++; // 射手爆头数加一。
    // // 向射手和目标发送爆头音效事件。
    // privateSoundEvents.add(new HeadshotEvent(shooter.id, "headshot"));
    // privateSoundEvents.add(new HeadshotEvent(target.id, "headshot"));
    //// System.out.println("[服务器调试] 爆头事件已创建并添加! 攻击者: " + shooter.id + ", 目标: " +
    // target.id);
    // } else { // 如果是击中身体。
    // // 向目标发送一个随机的受击音效事件。
    // privateSoundEvents.add(new HeadshotEvent(target.id, "受击" + (rand.nextInt(7) +
    // 1)));
    // }
    //
    // // 处理小数伤害。
    // double existingBuffer = playerDamageBuffer.getOrDefault(target.id, 0.0); //
    // 获取之前累积的小数伤害。
    // double totalPotentialDamage = damageToApply + existingBuffer; // 总伤害 = 本次伤害 +
    // 累积伤害。
    // int finalDamage = (int) totalPotentialDamage; // 取整数部分作为最终伤害。
    // double newBuffer = totalPotentialDamage - finalDamage; // 剩下的小数部分存回缓冲区。
    // playerDamageBuffer.put(target.id, newBuffer);
    //
    // if (finalDamage > 0) { // 如果最终伤害大于0。
    // shooter.damageDealt += finalDamage; // 增加射手造成的总伤害。
    // boolean wasAlive = target.isAlive(); // 记录目标在受伤前是否存活。
    // target.takeDamage(new Player.DamageInfo(finalDamage, isHeadshot)); // 1.
    // 先施加伤害
    //
    // boolean isKill = (wasAlive && !target.isAlive());
    //
    // // 3. 创建一个包含“正确”击杀状态的条目
    // Player.DamageLogEntry entry = new Player.DamageLogEntry(
    // shooter.id, target.id, finalDamage, System.currentTimeMillis(), isKill // <--
    // 一步到位！
    // );
    //
    // // 4. 添加到服务器端日志 (这个不变)
    // shooter.damageLog.add(entry);
    // if (!shooter.id.equals(target.id)) {
    // target.damageLog.add(entry);
    // }
    //
    // // 5. 准备 payload (数据体)
    // JsonObject payload = entry.toJson();
    //
    // // 6. [你的修改] 将事件加入 "privateDamageEvents" 列表
    // // 并使用新的 record 名字 "TargetedDamageEvent"
    // privateDamageEvents.add(new TargetedDamageEvent(shooter.id, payload));
    // // 为受害者（如果不同）将事件加入队列
    // if (!shooter.id.equals(target.id)) {
    // privateDamageEvents.add(new TargetedDamageEvent(target.id, payload));
    // }
    // // --- 优化结束 ---
    //
    // target.slowUntil = System.currentTimeMillis() + 300; // 中弹减速
    //
    // // 7. 如果确实是击杀，调用 handlePlayerKill (它现在只负责 killFeed 和分数)
    // if (isKill) {
    // handlePlayerKill(shooter, target, weaponName);
    // }
    // }
    // }

    /**
     * 伤害事件
     * 
     * @param shooter
     * @param target
     * @param baseDamage
     * @param isHeadshot
     * @param weaponName
     */
    private void dealDamageAndHandleEvents(Player shooter, Player target, double baseDamage, boolean isHeadshot,
            String weaponName) {
        // 如果目标无效或无敌，则不造成伤害。
        if (target == null || target.isInvincible)
            return;

        // --- [修改开始] 处理 shooter == null 的情况稍后进行，先计算伤害 ---
        // String attackerId = (shooter != null) ? shooter.id : null; // 获取攻击者ID，可能为null

        double damageToApply = baseDamage; // 初始伤害为武器的基础伤害。
        Weapon weapon = (shooter != null) ? shooter.getCurrentWeapon() : null; // 获取武器，可能为null

        // --- 伤害衰减计算 ---
        // 只有当伤害来源是玩家手中的枪械时，才计算衰减
        if (weapon != null && shooter != null) { // [修改] 确保 shooter 不为 null
            // 计算射手与目标之间的距离
            double distance = shooter.position.distance(target.position);
            // 从Weapon枚举类获取该距离下的伤害系数 (这是我们之前创建的方法)
            double falloffMultiplier = weapon.getDamageFalloff(distance);
            // 将伤害系数应用到基础伤害上
            damageToApply *= falloffMultiplier;
        }

        /** 僵尸伤害特化 (逻辑不变) */
        boolean isGrenadeDamage = "HE_GRENADE".equals(weaponName) || "MOLOTOV".equals(weaponName)
                || "INCENDIARY".equals(weaponName);
        if (target.team == Player.Team.ZOMBIE && isGrenadeDamage) {
            damageToApply = target.maxHealth * (baseDamage / 100.0);
        } else {
            // 对非僵尸玩家，或者非道具伤害，执行原有的护甲计算逻辑
            if (target.armorValue > 0 && ((isHeadshot && target.hasHelmet) || (!isHeadshot && target.hasKevlar))) {
                // [修改] 如果武器为null (e.g., C4/HE爆炸), 使用默认穿透
                double armorPen = (weapon != null) ? weapon.armorPenetration : EXPLOSIVE_ARMOR_PENETRATION;
                double damageToHealth = damageToApply * armorPen;
                double damageToArmor = damageToApply * (1 - armorPen);
                int armorDamage = (int) Math.min(target.armorValue, damageToArmor);
                target.armorValue -= armorDamage;
                damageToApply = damageToHealth + (damageToArmor - armorDamage);
            }
        }

        if (isHeadshot) { // 如果是爆头。
            damageToApply *= 4.0; // 伤害乘以4。
        }

        // --- 音效和射手统计 (只有 shooter 存在时才执行) ---
        if (shooter != null) {
            shooter.totalShotsHit++; // 射手命中数加一。
            if (isHeadshot) { // 爆头。
                shooter.totalHeadshots++; // 射手爆头数加一。

                // [!! 核心修复 !!] 使用 getEventRecipientId
                String shooterRecipientId = getEventRecipientId(shooter);
                String targetRecipientId = getEventRecipientId(target);

                if (shooterRecipientId != null) {
                    privateSoundEvents.add(new HeadshotEvent(shooterRecipientId, "headshot"));
                }
                // 避免给自己发两次 (如果自己打自己)
                if (targetRecipientId != null && !targetRecipientId.equals(shooterRecipientId)) {
                    privateSoundEvents.add(new HeadshotEvent(targetRecipientId, "headshot"));
                }

            } else { // 如果是击中身体。
                // 向目标发送一个随机的受击音效事件。(移动到下方公共部分)
                // privateSoundEvents.add(new HeadshotEvent(target.id, "受击" + (rand.nextInt(7) +
                // 1))); // <-- 移动
            }
        }

        // --- 公共音效 (无论 shooter 是否存在，目标都应收到受击音效) ---
        if (!isHeadshot) { // 如果不是爆头

            // [!! 核心修复 !!] 使用 getEventRecipientId
            String targetRecipientId = getEventRecipientId(target);
            if (targetRecipientId != null) {
                privateSoundEvents.add(new HeadshotEvent(targetRecipientId, "hit" + (rand.nextInt(7) + 1)));
            }
        }

        // 处理小数伤害。
        double existingBuffer = playerDamageBuffer.getOrDefault(target.id, 0.0); // 获取之前累积的小数伤害。
        double totalPotentialDamage = damageToApply + existingBuffer; // 总伤害 = 本次伤害 + 累积伤害。
        int finalDamage = (int) totalPotentialDamage; // 取整数部分作为最终 *潜在* 伤害。
        double newBuffer = totalPotentialDamage - finalDamage; // 剩下的小数部分存回缓冲区。
        playerDamageBuffer.put(target.id, newBuffer);

        // 如果最终潜在伤害大于0。
        if (finalDamage > 0) {
            boolean wasAlive = target.isAlive(); // 记录目标在受伤前是否存活。

            // --->>> [新] healthBeforeHit: 在调用 target.takeDamage() 之前保存了目标当前的 health 值。
            // <<<---
            double healthBeforeHit = target.health;

            target.takeDamage(new Player.DamageInfo(finalDamage, isHeadshot)); // 1. 先施加伤害

            // --->>> [新] actualDamageDealt: 计算实际损失的生命值 <<<---
            // 使用 Math.ceil 确保至少扣了 1 血时显示 1, Math.max 确保非负
            int actualDamageDealt = Math.max(0, (int) Math.ceil(healthBeforeHit - target.health));

            // 如果 shooter 存在，更新其造成的伤害统计
            if (shooter != null) {
                // --->>> [修改] shooter.damageDealt: 更新射手的伤害统计时，也使用了 actualDamageDealt <<<---
                shooter.damageDealt += actualDamageDealt;
            }

            boolean isKill = (wasAlive && !target.isAlive());

            // 3. 创建一个包含“正确”击杀状态和 *实际* 伤害的条目
            // --->>> [修改] Player.DamageLogEntry: 将第三个参数从 finalDamage 改为 actualDamageDealt
            // <<<---
            Player.DamageLogEntry entry = new Player.DamageLogEntry(
                    (shooter != null ? shooter.id : null), // 攻击者ID，可能为 null
                    target.id,
                    actualDamageDealt, // <--- 使用实际扣血量
                    System.currentTimeMillis(),
                    isKill);

            // 4. 添加到服务器端日志
            if (shooter != null) {
                shooter.damageLog.add(entry);
                // 避免重复添加自己对自己的伤害日志
                if (!shooter.id.equals(target.id)) {
                    target.damageLog.add(entry);
                }
            } else {
                // 如果 shooter 为 null (例如 C4)，只添加到目标的日志
                target.damageLog.add(entry);
            }

            // 5. 准备 payload (数据体)
            JsonObject payload = entry.toJson(); // toJson() 内部会使用 entry 里的 actualDamageDealt

            // 6. 将事件加入 "privateDamageEvents" 列表
            // 确保伤害日志也发给正确的接收者
            if (shooter != null) {
                String shooterRecipientId = getEventRecipientId(shooter);
                if (shooterRecipientId != null) {
                    privateDamageEvents.add(new TargetedDamageEvent(shooterRecipientId, payload));
                }
            }
            String targetRecipientId = getEventRecipientId(target);
            // 避免自己打自己时重复发送
            if (targetRecipientId != null
                    && (shooter == null || !targetRecipientId.equals(getEventRecipientId(shooter)))) {
                privateDamageEvents.add(new TargetedDamageEvent(targetRecipientId, payload));
            }

            target.slowUntil = System.currentTimeMillis() + 300; // 中弹减速

            // 7. 如果确实是击杀，调用 handlePlayerKill
            if (isKill) {
                // 、 死斗模式计分
                if (gameMode == GameMode.DEATHMATCH && shooter != null) {
                    shooter.score += 50; // 击杀 +50 分
                }
                handlePlayerKill(shooter, target, weaponName);
            }

            // --- 死斗模式计分 (伤害) ---
            if (gameMode == GameMode.DEATHMATCH && shooter != null && actualDamageDealt > 0) {
                shooter.score += actualDamageDealt; // 伤害 1:1 转为分数
            }
        }
    }

    // 处理玩家击杀事件。
    private void handlePlayerKill(Player shooter, Player target, String weaponName) {
        if (shooter.team == Player.Team.CT && target.team == Player.Team.T) {
            tKillsLastRound++;
        }
        if (gameMode == GameMode.ZOMBIE_MODE && target.team == Player.Team.CT) {
            if (zombiesToSpawn > 0) {
                transformToPlayerZombie(target);
                zombiesToSpawn--; // 消耗一个僵尸复活名额
            } else {
                // 如果名额已用完，玩家仍然变为僵尸阵营以供结算，但保持死亡状态无法重生
                target.team = Player.Team.ZOMBIE;
                target.health = 0;
            }
        }

        // [核心修复] 找到真正的统计数据接收者 (人类控制者)
        Player realKiller = shooter;
        if (shooter != null && shooter.isAI && shooter.controlledByPlayerId != null) {
            Player controller = getPlayerById(shooter.controlledByPlayerId);
            if (controller != null)
                realKiller = controller;
        }

        if (realKiller != null && realKiller != target) {
            realKiller.kills++; // 增加杀手击杀数
        }

        if (realKiller != null && realKiller.isAI)
            realKiller.deathStreak = 0;

        target.deaths++;
        target.score -= 50; // 死亡扣分 (匹配用户规则)
        if (target.isAI)
            target.deathStreak++;
        target.respawnTime = System.currentTimeMillis();

        String shooterRecipientId = getEventRecipientId(realKiller);
        if (shooterRecipientId != null) {
            privateSoundEvents.add(new HeadshotEvent(shooterRecipientId, "kill"));
        }

        playerDamageBuffer.remove(target.id);

        boolean wasHeadshot = false;
        if (target.lastDamageSource != null && target.lastDamageSource.isHeadshot()) {
            wasHeadshot = true;
        }

        // [BUG修复] 直接使用传递进来的、正确的 weaponName
        KillFeedInfo killInfo = new KillFeedInfo(
                shooter.id,
                shooter.name,
                shooter.team,
                target.id,
                target.name,
                target.team,
                weaponName, // <-- 用这个正确的武器名
                wasHeadshot,
                System.currentTimeMillis());

        recentKills.addFirst(killInfo);
        if (recentKills.size() > 4) {
            recentKills.removeLast();
        }

        recentDeaths.add(
                new PlayerDeath(target.team, (Point2D.Double) target.position.clone(), System.currentTimeMillis()));

        if (gameMode == GameMode.DEMOLITION || gameMode == GameMode.TEAM_DEATHMATCH
                || gameMode == GameMode.DEATHMATCH) {
            if (gameMode == GameMode.DEMOLITION) {
                if (shooter.getCurrentWeapon() != null) { // 检查是否有武器以获取击杀奖励
                    shooter.money = Math.min(16000, shooter.money + shooter.getCurrentWeapon().killReward);
                }
                if (target.hasBomb) {
                    target.hasBomb = false;
                    addDroppedItem(new DroppedItem((Point2D.Double) target.position.clone()));
                    logger.accept("炸弹在 (" + (int) target.position.x + ", " + (int) target.position.y + ") 掉落");
                }
            }
            // 所有非僵尸模式下，死亡都会掉落主武器
            if (target.primaryWeapon != null) {
                addDroppedItem(new DroppedItem(target.primaryWeapon, (Point2D.Double) target.position.clone(),
                        target.primary_currentAmmo, target.primary_reserveAmmo));
            }

            if (gameMode == GameMode.TEAM_DEATHMATCH) {
                if (shooter.team == Player.Team.CT)
                    teamCTScore_TDM++;
                else if (shooter.team == Player.Team.T)
                    teamTScore_TDM++;

                if (teamCTScore_TDM >= tdmWinScore)
                    finishTdmMatch(Player.Team.CT, "Score limit reached");
                else if (teamTScore_TDM >= tdmWinScore)
                    finishTdmMatch(Player.Team.T, "Score limit reached");
            }
        }
    }

    /**
     * 辅助方法：获取一个事件（如声音、日志）的最终接收者ID。
     * 如果玩家是AI并且被人类玩家控制，则返回人类玩家的ID。
     * 否则，返回玩家自己的ID。
     */
    private String getEventRecipientId(Player player) {
        if (player == null)
            return null;
        // 如果是AI 并且 正在被玩家控制
        if (player.isAI && player.isControlledByPlayer()) {
            return player.controlledByPlayerId; // 返回控制者的ID
        }
        return player.id; // 否则返回自己的ID
    }

    /**
     * 处理玩家丢弃武器的请求（完整版，支持夺舍状态）。
     *
     * @param playerId 发出请求的玩家ID
     */
    public void playerDropWeapon(String playerId) {
        // 规则：只能在爆破模式下扔枪
        if (gameMode != GameMode.DEMOLITION && gameMode != GameMode.TEAM_DEATHMATCH)
            return;
        // 找到发出请求的人类玩家
        Player humanPlayer = getPlayerById(playerId);
        if (humanPlayer == null)
            return;

        // 决定动作的实际执行者
        // 如果人类玩家正在夺舍，那么执行者就是被控制的BOT
        // 否则，执行者就是人类玩家自己
        Player actionTarget = humanPlayer.isControllingBot() ? getPlayerById(humanPlayer.controllingBotId)
                : humanPlayer;

        // 确保执行者存在
        if (actionTarget == null)
            return;

        // 丢枪逻辑只能由一个存活的、且拥有主武器的单位执行
        if (actionTarget.isAlive() && actionTarget.primaryWeapon != null) {

            // 在丢弃前，强制同步弹药数量
            // 如果当前正拿着主武器，就把“手上”的实时弹药存回记录
            // 丢弃时正拿着主武器，则自动切换到副武器
            if (actionTarget.currentSlot == 1) {
                actionTarget.switchToSlot(2);
                // [BUG修复] 丢枪切到副武器时，重置后坐力

                actionTarget.shootTimeIndex = 0;
                actionTarget.continueShotingSpread = 0;
                actionTarget.predictedRecoilAngle = 0;
                actionTarget.r8ChargeStartTime = 0;
            }

            Weapon droppedWeapon = actionTarget.primaryWeapon;

            // === 防卡墙逻辑，确保武器不会掉进墙里 ===
            // 计算一个在玩家前方的初始掉落位置
            double dropDist = Player.SIZE * 1.5;
            Point2D.Double dropPos = new Point2D.Double(
                    actionTarget.position.x + Math.cos(actionTarget.angle) * dropDist,
                    actionTarget.position.y + Math.sin(actionTarget.angle) * dropDist);

            // 如果初始位置在障碍物里，就把它往玩家方向拉回，最多尝试10次
            for (int i = 0; i < 10 && isPointInObstacle(dropPos); i++) {
                dropPos.x -= Math.cos(actionTarget.angle) * (Player.SIZE / 4.0);
                dropPos.y -= Math.sin(actionTarget.angle) * (Player.SIZE / 4.0);
            }

            // 如果尝试10次后仍然在墙里，就直接掉落在玩家脚下
            if (isPointInObstacle(dropPos)) {
                dropPos = (Point2D.Double) actionTarget.position.clone();
            }
            // === 防卡墙逻辑结束 ===

            // 在游戏世界中创建掉落物实体，并记录武器当前的弹药
            addDroppedItem(new DroppedItem(droppedWeapon, dropPos, actionTarget.primary_currentAmmo,
                    actionTarget.primary_reserveAmmo, actionTarget.id));

            // 从玩家（或AI）的物品栏中移除主武器
            actionTarget.primaryWeapon = null;
            actionTarget.primary_currentAmmo = 0;
            actionTarget.primary_reserveAmmo = 0;

            // 丢弃时正拿着主武器，则自动切换到副武器
            if (actionTarget.currentSlot == 1) {
                actionTarget.switchToSlot(2);
            }

            logger.accept(actionTarget.name + " 丢弃了 " + droppedWeapon.name);
        }
    }

    // 记录玩家为下一条命选择的武器(团队模式)
    public void playerSelectsNextWeapon(String playerId, String weaponName) {
        players.stream()
                .filter(p -> p.id.equals(playerId))
                .findFirst()
                .ifPresent(player -> {
                    try {
                        cs2d.playerAndAi.Weapon w = cs2d.playerAndAi.Weapon.valueOf(weaponName);
                        if (w.getWeaponType().isPistol()) {
                            player.selectedSecondaryName = weaponName;
                        } else {
                            player.selectedPrimaryName = weaponName;
                        }
                    } catch (Exception e) {
                        player.nextWeapon = weaponName;
                    }
                });
    }

    /**
     * [v2.8]处理玩家请求换弹，并添加了换弹音效的广播
     */
    public void playerRequestReload(String playerId) {
        Player humanPlayer = getPlayerById(playerId);
        if (humanPlayer == null)
            return;

        // 决定动作的实际执行者
        Player actionTarget = humanPlayer.isControllingBot() ? getPlayerById(humanPlayer.controllingBotId)
                : humanPlayer;

        if (actionTarget == null || !actionTarget.isAlive()) {
            return;
        }
        // 只有当角色当前没有在换弹时 + 后备弹药不为 0 + 前辈弹药是不满的，才允许他开始换弹
        if (actionTarget != null && actionTarget.isAlive() && !actionTarget.isReloading && actionTarget.reserveAmmo > 0
                && actionTarget.getCurrentWeapon().magazineSize != actionTarget.currentAmmo) {
            // 尝试开始换弹，如果换弹成功（例如，成功修改了状态），则继续
            actionTarget.startReload();

            // 此时，角色刚刚进入换弹状态，广播音效
            Weapon weapon = actionTarget.getCurrentWeapon();
            if (weapon != null) {
                addSoundEvent(SoundEvent.SoundType.RELOAD, weapon.name() + "_reload", actionTarget.position.x,
                        actionTarget.position.y, actionTarget.id);
            }
        }
        // 如果角色已经在换弹中，则不执行任何操作（包括不广播音效）
    }

    /**
     * 更新玩家的物理状态（移动、速度、碰撞）。
     * [算法优化]
     * 
     * @param p
     */
    private void updatePlayerPhysics(Player p) {
        // 允许人类玩家或被夺舍的BOT根据按键移动
        // --- 1. 计算加速度 ---
        // 总是先重置加速度，确保上一帧的加速度不残留
        p.ax = 0;
        p.ay = 0;

        // 移除错误的 if 条件！现在所有实体都会根据 keysDown 计算加速度
        // 根据按键设置加速度方向。
        if (p.keysDown.contains("W"))
            p.ay -= 1;
        if (p.keysDown.contains("S"))
            p.ay += 1;
        if (p.keysDown.contains("A"))
            p.ax -= 1;
        if (p.keysDown.contains("D"))
            p.ax += 1;

        // 计算玩家当前的最大速度（受武器和减速效果影响）。
        Weapon currentWep = p.getCurrentWeapon();
        double maxSpeed = (p.team == Player.Team.ZOMBIE || currentWep == null) ? BASE_SPEED
                : BASE_SPEED * currentWep.speedMultiplier;
        if (p.isWalking) {
            maxSpeed *= 0.5; // 静步速度为正常速度的50%
        }
        if (p.slowUntil > System.currentTimeMillis()) { // 如果玩家处于减速状态。
            maxSpeed *= 0.3; // 最大速度降低。
            p.isSlowed = true; // 设置减速标记。
        } else {
            p.isSlowed = false; // 取消减速标记。
        }

        p.vx += p.ax * ACCELERATION; // 速度 += 加速度 * 加速系数。
        p.vy += p.ay * ACCELERATION;

        double currentSpeed = Math.sqrt(p.vx * p.vx + p.vy * p.vy); // 计算当前合速度。
        if (currentSpeed > maxSpeed) { // 如果速度超过了最大速度。
            // 将速度等比例缩放到最大速度。
            p.vx = (p.vx / currentSpeed) * maxSpeed;
            p.vy = (p.vy / currentSpeed) * maxSpeed;
        }

        p.vx *= FRICTION; // 速度乘以摩擦力进行减速。
        p.vy *= FRICTION;
        p.isMoving = currentSpeed > 0.1; // 如果速度大于一个阈值，则认为玩家在移动。

        if (p.isInteracting && p.isMoving) { // 如果玩家在交互时移动了。
            playerStopInteraction(p.id);
            System.out.println("不要乱动!");// 停止交互。
        }

        // moveWithCollision(p, p.vx, p.vy); // 根据最终速度移动玩家并处理碰撞。
    }

    public void playerSwitchSlot(String playerId, int slot) {
        Player humanPlayer = getPlayerById(playerId);
        if (humanPlayer == null)
            return;

        // 决定动作的目标：如果是夺舍状态，目标是BOT；否则是玩家自己
        Player actionTarget = humanPlayer.isControllingBot() ? getPlayerById(humanPlayer.controllingBotId)
                : humanPlayer;

        // 如果找不到目标或目标已死亡，则不执行
        if (actionTarget == null || !actionTarget.isAlive()) {
            return;
        }

        // --- 直接在服务器线程上执行逻辑和日志，不再使用 Platform.runLater ---
        logger.accept("=================[ Weapon Switch Log ]=================");
        // logger.accept(String.format("[REQUEST] Player '%s' requested switch to slot
        // %d for target '%s'.", humanPlayer.name, slot, actionTarget.name));

        int oldSlot = actionTarget.currentSlot;
        actionTarget.switchToSlot(slot); // 对真正的目标执行切换
        int newSlot = actionTarget.currentSlot;

        if (oldSlot != newSlot) {

            // [BUG修复] 切枪时，必须重置后坐力和连射计数器！！
            actionTarget.shootTimeIndex = 0; // 重置压枪计数
            actionTarget.continueShotingSpread = 0; // 重置连射扩散
            actionTarget.predictedRecoilAngle = 0; // 重置视觉后坐力
            actionTarget.r8ChargeStartTime = 0; // 重置R8蓄力(如果有)
        } else {
            // logger.accept(String.format(" [RESULT] FAILED. Slot for '%s' remains %d.",
            // actionTarget.name, oldSlot));
        }
        logger.accept("======================================================");
    }

    /**
     * v1.5[旧版]处理玩家的射击请求(基于角度预测)
     */
    // public void requestShoot(Player shooter) {
    // // 如果是爆破模式的冻结时间，不能射击。
    // if (gameMode == GameMode.DEMOLITION && roundPhase == RoundPhase.FREEZE_TIME)
    // {
    // return;
    // }
    //
    // // 如果当前装备的是手榴弹，执行投掷逻辑
    // if (shooter.getCurrentGrenade() != null) {
    // long currentTime = System.currentTimeMillis();
    // if (currentTime - shooter.lastShotTime > 500) { // 防止快速连扔
    // shooter.lastShotTime = currentTime;
    // throwGrenade(shooter);
    // }
    // return;
    // }
    //
    // // 玩家没有武器，不能射击。
    // Weapon weapon = shooter.getCurrentWeapon();
    // if (weapon == null) {
    // return;
    // }
    //
    // // 获取当前时间，用于射速检查。
    // long currentTime = System.currentTimeMillis();
    // // 必须同时满足：存活、没在换弹、有子弹、距离上次射击时间已超过武器射速间隔。
    // if (shooter.isAlive() && !shooter.isReloading && shooter.currentAmmo > 0 &&
    // currentTime - shooter.lastShotTime > weapon.fireRateMillis) {
    //
    // shooter.lastShotTime = currentTime; // 更新上次射击时间。
    // shooter.currentAmmo--; // 弹药减一。
    // shooter.totalShotsFired += weapon.pelletCount; // 增加总射击数。
    //
    // // 计算枪口在世界中的精确位置。
    // double muzzleX = shooter.position.x + Math.cos(shooter.angle) * (Player.SIZE
    // / 2.0);
    // double muzzleY = shooter.position.y + Math.sin(shooter.angle) * (Player.SIZE
    // / 2.0);
    //
    // // 添加枪口火焰的视觉效果。
    // visualEffects.add(new VisualEffect("muzzle", new Point2D.Double(muzzleX,
    // muzzleY), 50));
    // // 添加开火的音效事件。
    // addSoundEvent(SoundEvent.SoundType.FIRE, weapon.name(), shooter.position.x,
    // shooter.position.y);
    // addSoundEvent(SoundEvent.SoundType.FIRE, weapon.name() + "_fire",
    // shooter.position.x, shooter.position.y);
    // // 循环处理每一颗弹丸（对于非霰弹枪，只循环一次）。
    // for (int i = 0; i < weapon.pelletCount; i++) {
    // double finalAngle; // 子弹最终的飞行角度。
    // double fixedRecoilAngle = 0; // 来自固定弹道的角度偏移。
    // double randomSpreadAngle = 0; // 来自移动或随机性的角度偏移。
    //
    // if (shooter.weaponFixedRecoil != null) { // 如果武器有固定后坐力模式。
    // if (i == 0) { // 只对每发的第一个弹丸应用后坐力。
    // if (shooter.shootTimeIndex < shooter.weaponFixedRecoil.length) { //
    // 如果还在弹道模式数组内。
    // fixedRecoilAngle = shooter.weaponFixedRecoil[shooter.shootTimeIndex]; //
    // 获取当前射击次数对应的角度偏移。
    // } else { // 如果超过了弹道模式数组的长度。
    // fixedRecoilAngle = shooter.weaponFixedRecoil[shooter.weaponFixedRecoil.length
    // - 1]; // 使用最后一个值。
    // }
    // }
    // randomSpreadAngle = (rand.nextDouble() - 0.5) * shooter.movingSpread; //
    // 即使有固定弹道，移动惩罚依然生效。
    // } else { // 如果是旧版随机后坐力模式。
    // randomSpreadAngle = (rand.nextDouble() - 0.5) * shooter.currentSpread; //
    // 从总扩散值中取一个随机偏移。
    // if (weapon.pelletCount > 1) { // 如果是霰弹枪。
    // randomSpreadAngle += (rand.nextDouble() - 0.5) * 0.25; // 额外增加一个弹丸散射。
    // }
    // }
    //
    // // 子弹的最终飞行角度 = 玩家瞄准角度 + 当前的后坐力角度 + 随机扩散。
    // finalAngle = shooter.angle + fixedRecoilAngle + randomSpreadAngle;
    // // 执行射线投射，模拟子弹飞行和命中。
    // calculateRaycast(shooter, new Point2D.Double(muzzleX, muzzleY), finalAngle);
    // }
    //
    // // --- 预测下一发的后坐力角度 ---
    // if (shooter.weaponFixedRecoil != null) { // 如果武器有固定弹道。
    //
    // int nextRecoilIndex = (shooter.shootTimeIndex + 1) %
    // shooter.weaponFixedRecoil.length;
    // double nextRecoilAngle = shooter.weaponFixedRecoil[nextRecoilIndex];
    //
    // shooter.predictedRecoilAngle = nextRecoilAngle;
    // shooter.shootTimeIndex++;
    // } else { // 如果武器没有固定弹道。
    // shooter.continueShotingSpread += weapon.recoilPerShot; // 增加旧版的连射扩散。
    // if (shooter.continueShotingSpread > weapon.maxSpread) { // 确保不超过上限。
    // shooter.continueShotingSpread = weapon.maxSpread;
    // }
    // shooter.predictedRecoilAngle = 0; // 没有固定弹道则不进行预测。
    // }
    //
    // if (shooter.currentAmmo == 0) { // 如果子弹打完了。
    // shooter.startReload(); // 自动开始换弹。
    // }
    // }
    // }

    /**
     * [拆分]
     * 处理玩家的射击请求。
     * 包含全自动、半自动和R8左轮蓄力射击的完整逻辑。
     */
    public void requestShoot(Player shooter) {
        if (gameMode == GameMode.DEMOLITION && roundPhase == RoundPhase.FREEZE_TIME)
            return;
        if (shooter.getCurrentGrenade() != null) {
            if (System.currentTimeMillis() - shooter.lastShotTime > 500) {
                throwGrenade(shooter);
                shooter.lastShotTime = System.currentTimeMillis();
            }
            return;
        }

        Weapon weapon = shooter.getCurrentWeapon();
        if (weapon == null || !shooter.isAlive() || shooter.isReloading || shooter.currentAmmo <= 0) {
            // 对于R8，即使没子弹也要处理抬起按键的逻辑
            if (weapon == Weapon.R8) {
                shooter.r8ChargeStartTime = 0;
            }
            return;
        }

        long currentTime = System.currentTimeMillis();

        // ================== R8 左轮蓄力逻辑 ==================
        if (weapon == Weapon.R8) {
            // --- 正在按住左键 ---
            if (shooter.isShooting) {
                // 如果蓄力计时器未启动（比如刚按下左键），则以当前时间启动它
                if (shooter.r8ChargeStartTime == 0) {
                    shooter.r8ChargeStartTime = currentTime;
                }

                long chargeDuration = currentTime - shooter.r8ChargeStartTime;

                // 检查是否蓄力满0.5秒(500ms)，并且射速冷却已过
                if (chargeDuration >= 500 && currentTime - shooter.lastShotTime > weapon.fireRateMillis) {
                    fireWeapon(shooter, weapon, currentTime); // 激发射击

                    // 射击后，立即将蓄力开始时间重置为当前时间！
                    // 这会无缝地开始下一次0-300ms的蓄力循环，只要玩家还按着左键。
                    shooter.r8ChargeStartTime = currentTime;
                }
            }
            // --- 松开了左键 ---
            else {
                // 只要松开按键，就无条件重置蓄力计时器
                shooter.r8ChargeStartTime = 0;
            }
            return; // R8的逻辑是独立的，处理完直接返回
        }
        // =================================================================

        // ================== 通用射击逻辑 ==================
        boolean triggerPulled; // 标记玩家是否有“开火”的意图

        if (weapon.isFullAuto) {
            // 对于全自动武器，只要按住键就视为有开火意图
            triggerPulled = shooter.isShooting;
        } else {
            // 对于半自动武器，只有在“新按下”的那一帧才有开火意图
            triggerPulled = shooter.isShooting && !shooter.wasShootingLastFrame;
        }

        // 如果玩家有开火意图，并且射速冷却已过
        if (triggerPulled && currentTime - shooter.lastShotTime > weapon.fireRateMillis) {
            fireWeapon(shooter, weapon, currentTime); // 开火！
        }
        // ====================================================
    }

    // 公共音效事件。
    /**
     * [重大修改] 添加一个公共声音事件。
     * 现在会同时添加到客户端列表和 AI 缓冲区。
     */
    public void addSoundEvent(SoundEvent.SoundType type, String weaponName, double x, double y) {
        SoundEvent newEvent = new SoundEvent(type, weaponName, x, y, null);

        // 1. 添加到客户端列表
        synchronized (soundEvents) {
            soundEvents.add(newEvent);
        }
        // 2. 同时添加到 AI 缓冲区
        synchronized (aiSoundEventsBuffer) {
            aiSoundEventsBuffer.add(newEvent);
        }

        // [修改] 你的日志记录逻辑现在应该在这里
        if (logger != null) {
            LocalDateTime now = LocalDateTime.now();
            String timestampStr = now.format(TIMESTAMP_FORMATTER);
            // logger.accept(String.format("[%s] [AIState] %s", timestampStr,
            // newEvent.toString()));
        }
    }

    /**
     * [重大修改] 添加一个公共声音事件 (带来源 ID)。
     * 现在会同时添加到客户端列表和 AI 缓冲区。
     */
    public void addSoundEvent(SoundEvent.SoundType type, String soundName, double x, double y, String sourcePlayerId) {
        SoundEvent newEvent = new SoundEvent(type, soundName, x, y, sourcePlayerId);

        // 1. 添加到客户端列表
        synchronized (soundEvents) {
            soundEvents.add(newEvent);
        }
        // 2. 同时添加到 AI 缓冲区
        synchronized (aiSoundEventsBuffer) {
            aiSoundEventsBuffer.add(newEvent);
        }

        // 你的 [AIState] 日志逻辑
        if (logger != null) {
            LocalDateTime now = LocalDateTime.now();
            String timestampStr = now.format(TIMESTAMP_FORMATTER);
            // logger.accept(String.format("[%s] [AIState] %s", timestampStr,
            // newEvent.toString()));
        }
    }

    /**
     * [拆分] 实际执行开火逻辑的辅助方法。
     */
    private void fireWeapon(Player shooter, Weapon weapon, long currentTime) {
        shooter.lastShotTime = currentTime;
        shooter.currentAmmo--;
        shooter.totalShotsFired += weapon.pelletCount;

        // 计算枪口位置：玩家位置 + 朝向方向偏移半个玩家身位
        double muzzleX = shooter.position.x + Math.cos(shooter.angle) * (Player.SIZE / 2.0);
        double muzzleY = shooter.position.y + Math.sin(shooter.angle) * (Player.SIZE / 2.0);

        visualEffects.add(new VisualEffect("muzzle", new Point2D.Double(muzzleX, muzzleY), 50));
        // addSoundEvent(SoundEvent.SoundType.FIRE, weapon.name(), shooter.position.x,
        // shooter.position.y, shooter.id);
        addSoundEvent(SoundEvent.SoundType.FIRE, weapon.name() + "_fire", shooter.position.x, shooter.position.y,
                shooter.id);

        for (int i = 0; i < weapon.pelletCount; i++) {
            double finalAngle;
            double fixedRecoilAngle = 0;
            double randomSpreadAngle = 0;

            /** 注意，CS2里面的枪是有初始的基础散射的 这里没有做(懒) */
            byte baseSpreadMuti = 2;
            if (shooter.weaponFixedRecoil != null) {
                if (i == 0) {
                    // 固定后坐力模式：使用预定义后坐力表<Weapon里面> 循环使用不同射击时机的后坐力
                    int recoilIndex = shooter.shootTimeIndex % shooter.weaponFixedRecoil.length;
                    fixedRecoilAngle = shooter.weaponFixedRecoil[recoilIndex];
                }
                // 移动扩散：-0.5到0.5之间的随机值乘以（移动扩散系数+基础扩散*系数)
                randomSpreadAngle = ((rand.nextDouble() - 0.5)
                        * (shooter.movingSpread + shooter.getCurrentWeapon().baseSpread) * baseSpreadMuti);
            } else {
                // 随机扩散模式：基础扩散 + 霰弹枪额外扩散
                randomSpreadAngle = (rand.nextDouble() - 0.5) * shooter.currentSpread;
                if (weapon.pelletCount > 1) {
                    randomSpreadAngle += (rand.nextDouble() - 0.5) * 0.25; // 霰弹枪额外散布
                }
            }

            // 最终角度 = 基础角度 + 固定后坐力 + 随机扩散 (散射/后坐力)
            finalAngle = shooter.angle + fixedRecoilAngle + randomSpreadAngle;
            calculateRaycast(shooter, new Point2D.Double(muzzleX, muzzleY), finalAngle);
        }

        if (shooter.weaponFixedRecoil != null) {
            // 预测下一发后坐力：用于客户端提前显示
            int nextRecoilIndex = (shooter.shootTimeIndex + 1) % shooter.weaponFixedRecoil.length;
            shooter.predictedRecoilAngle = shooter.weaponFixedRecoil[nextRecoilIndex];
            shooter.shootTimeIndex++;
        } else {
            // 连续射击扩散累积：每射击一次增加扩散，不超过武器最大扩散值
            shooter.continueShotingSpread += weapon.recoilPerShot;
            if (shooter.continueShotingSpread > weapon.maxSpread) {
                shooter.continueShotingSpread = weapon.maxSpread;
            }
            shooter.predictedRecoilAngle = 0;
        }

        if (shooter.currentAmmo == 0) {
            shooter.startReload();
        }
    }

    // 计算射线投射，模拟子弹飞行、穿透和命中。
    private void calculateRaycast(Player shooter, Point2D.Double startPoint, double angle) {
        Weapon weapon = shooter.getCurrentWeapon();
        double currentDamage = 0;
        if (weapon == null)
            return;
        else {
            currentDamage = weapon.damage;
        } // 初始伤害为武器基础伤害。

        final double initialPenetrationPower = weapon.penetrationPower * PENETRATION_MULTIPLIER; // 计算初始穿透力。
        double remainingPenetration = initialPenetrationPower; // 剩余穿透力。
        Point2D.Double currentRayStart = startPoint; // 射线开始点。
        Point2D.Double finalTrailEnd = null; // 弹道视觉效果的终点。
        List<Player> allTargets = new ArrayList<>(players); // 创建一个包含所有潜在目标的列表。
        allTargets.addAll(zombies);
        /** 注：我也没有做 子弹穿透人的 (懒 x2) */
        int maxHits = 10; // 一颗子弹最多穿透10个目标/障碍物。
        Line2D.Double ray = null; // 初始化射线。
        double raycastLength = 8000; // 射线的最大长度。

        for (int i = 0; i < maxHits; i++) { // 循环处理穿透。
            // 创建一条从当前起点出发，沿指定角度飞行的长射线。
            ray = new Line2D.Double(currentRayStart, new Point2D.Double(
                    currentRayStart.x + Math.cos(angle) * raycastLength,
                    currentRayStart.y + Math.sin(angle) * raycastLength));

            Player hitPlayer = null; // 初始化被击中的玩家为null。
            double minPlayerDist = Double.MAX_VALUE; // 初始化到玩家的最小距离为最大值。
            for (Player p : allTargets) { // 遍历所有目标。
                // [修改] 友军火力判定
                boolean isEnemy;
                if (gameMode == GameMode.DEATHMATCH) {
                    isEnemy = p != shooter; // 死斗模式，所有人都是敌人 (除了自己)
                } else {
                    isEnemy = p.team != shooter.team; // 其他模式，按队伍
                }

                // 目标不是射手自己、还活着、是敌人，并且射线离目标中心很近（即命中）。
                if (p != shooter && p.isAlive() && isEnemy && ray.ptSegDist(p.position) < Player.SIZE / 2.0) {
                    double dist = currentRayStart.distance(p.position); // 计算距离。
                    if (dist < minPlayerDist) { // 如果这个目标比之前找到的更近。
                        minPlayerDist = dist; // 更新最小距离。
                        hitPlayer = p; // 记录这个被击中的玩家。
                    }
                }
            }

            Point2D.Double wallEntryPoint = null; // 初始化墙壁的进入点。
            Point2D.Double wallExitPoint = null; // 初始化墙壁的出去点。
            double minWallDist = Double.MAX_VALUE; // 初始化到墙壁的最小距离。
            for (Shape obs : obstacles) { // 遍历所有障碍物。
                Point2D.Double[] intersections = getLineShapeIntersections(ray, obs); // 计算射线与障碍物的交点。
                if (intersections != null) { // 如果有交点。
                    double dist = currentRayStart.distance(intersections[0]); // 计算到第一个交点的距离。
                    if (dist < minWallDist) { // 如果这面墙更近。
                        minWallDist = dist; // 更新最小距离。
                        wallEntryPoint = intersections[0]; // 记录蛇入点。
                        wallExitPoint = intersections[1]; // 记录蛇出点。
                    }
                }
            }

            /**
             * 爆头判定
             */
            // 判断先打中玩家还是先打中墙。
            Point2D.Double entryPoint = null; // 命中物体的入口点
            Point2D.Double exitPoint = null; // 命中物体的出口点
            double objectThickness = 0; // 命中物体的厚度
            boolean hitWall = false; // 标记是否命中了墙体

            // 决策： 这轮循环是命中了玩家还是墙？
            if (hitPlayer != null && (wallEntryPoint == null || minPlayerDist < minWallDist)) {
                // --- A. 命中了玩家 ---

                // 计算精确撞击点 (入口点)
                Point2D.Double rayDir = new Point2D.Double(Math.cos(angle), Math.sin(angle));
                Point2D.Double startToCenter = new Point2D.Double(hitPlayer.position.x - currentRayStart.x,
                        hitPlayer.position.y - currentRayStart.y);
                double projection = rayDir.x * startToCenter.x + rayDir.y * startToCenter.y;
                entryPoint = new Point2D.Double(
                        currentRayStart.x + rayDir.x * projection,
                        currentRayStart.y + rayDir.y * projection);

                // 爆头判定
                boolean isHeadshot = ray.ptSegDist(hitPlayer.position) < Player.SIZE / 8.0;

                // 先处理伤害
                dealDamageAndHandleEvents(shooter, hitPlayer, currentDamage, isHeadshot, weapon.name());

                // 将玩家从目标列表中移除，防止重复命中
                allTargets.remove(hitPlayer);

                // 设定玩家的 "厚度" 和 "出口点"
                objectThickness = Player.SIZE; // 简化处理：将玩家厚度视为其直径

                // 估算出口点：从入口点沿着射线方向推进 "厚度" 距离
                exitPoint = new Point2D.Double(
                        entryPoint.x + rayDir.x * objectThickness,
                        entryPoint.y + rayDir.y * objectThickness);

            } else if (wallEntryPoint != null) {
                // --- B. 命中了墙体 ---
                entryPoint = wallEntryPoint;
                exitPoint = wallExitPoint; // wallExitPoint 是上一次修复的 (intersections.get(1))
                objectThickness = entryPoint.distance(exitPoint);
                hitWall = true; // 标记我们命中了墙

            } else {
                // --- C. 什么都没命中 ---
                finalTrailEnd = new Point2D.Double(ray.getX2(), ray.getY2());
                break; // 结束射线检测
            }

            // 统一穿透计算：判定和扣除必须使用同一个“穿透力”单位。
            double penetrationCost = objectThickness * weapon.penetrationCostPerPixel();
            if (remainingPenetration >= penetrationCost) {
                // 可以穿透：消耗穿透力和伤害
                remainingPenetration -= penetrationCost;
                currentDamage = (initialPenetrationPower > 0)
                        ? weapon.damage * (remainingPenetration / initialPenetrationPower)
                        : 0;

                // 如果伤害耗尽，子弹停止 (即使穿透了)
                if (currentDamage <= 0) {
                    finalTrailEnd = entryPoint; // 弹道终点设为入口
                    break;
                }

                // 推进射线的起点到出口点，准备下一次循环
                double epsilon = 0.1; // 极小值，防止射线起点卡在物体内部
                currentRayStart = new Point2D.Double(
                        exitPoint.x + Math.cos(angle) * epsilon,
                        exitPoint.y + Math.sin(angle) * epsilon);

                // 如果我们刚刚穿透的是玩家，我们不希望弹道停在玩家身上
                // 所以我们只在穿透墙体时设置 "可能的" 弹道终点
                if (hitWall) {
                    finalTrailEnd = entryPoint; // 视觉弹道在入口处停止
                }
            } else {
                // 无法穿透：子弹停止在入口点
                finalTrailEnd = entryPoint;
                break; // 结束射线检测
            }
        }
        if (finalTrailEnd == null) { // 如果循环结束了终点还是null（不太可能发生）。
            finalTrailEnd = new Point2D.Double(startPoint.x + Math.cos(angle) * raycastLength,
                    startPoint.y + Math.sin(angle) * raycastLength);
        }
        // 添加一个弹道的视觉效果。
        visualEffects.add(new VisualEffect("trail", startPoint, finalTrailEnd, 60));
    }

    /**
     * [旧版]计算一个点到一条线段的最短距离的平方。
     * 
     * @param p 点
     * @param a 线段端点1
     * @param b 线段端点2
     * @return 点到线段的最短距离的平方
     */
    private double pointToSegmentSq(Point2D p, Point2D a, Point2D b) {
        double l2 = a.distanceSq(b);
        if (l2 == 0.0)
            return p.distanceSq(a);
        double t = Math.max(0, Math.min(1,
                (p.getX() - a.getX()) * (b.getX() - a.getX()) + (p.getY() - a.getY()) * (b.getY() - a.getY()) / l2));
        Point2D projection = new Point2D.Double(a.getX() + t * (b.getX() - a.getX()),
                a.getY() + t * (b.getY() - a.getY()));
        return p.distanceSq(projection);
    }

    /**
     * 在给定的Shape（障碍物）上，找到距离一个点最近的那个点。
     * 
     * @param point 玩家中心点
     * @param shape 障碍物
     * @return 障碍物边界上距离玩家最近的点
     */
    private Point2D.Double findClosestPointOnShape(Point2D.Double point, Shape shape) {
        PathIterator path = shape.getPathIterator(null, 1.0);
        double[] coords = new double[6];
        Point2D.Double closestPoint = null;
        double min_dist_sq = Double.MAX_VALUE;

        Point2D.Double move_to = new Point2D.Double();
        Point2D.Double last_point = new Point2D.Double();

        while (!path.isDone()) {
            int type = path.currentSegment(coords);
            Point2D.Double p1 = last_point;
            Point2D.Double p2;

            if (type == PathIterator.SEG_MOVETO) {
                move_to.setLocation(coords[0], coords[1]);
                last_point.setLocation(coords[0], coords[1]);
                path.next();
                continue;
            }
            if (type == PathIterator.SEG_CLOSE) {
                p2 = move_to;
            } else {
                p2 = new Point2D.Double(coords[0], coords[1]);
            }

            // 计算点到当前线段的最近点
            double l2 = p1.distanceSq(p2);
            if (l2 == 0.0) {
                if (closestPoint == null || point.distanceSq(p1) < min_dist_sq) {
                    min_dist_sq = point.distanceSq(p1);
                    closestPoint = p1;
                }
            } else {
                double t = Math.max(0,
                        Math.min(1, ((point.x - p1.x) * (p2.x - p1.x) + (point.y - p1.y) * (p2.y - p1.y)) / l2));
                Point2D.Double projection = new Point2D.Double(p1.x + t * (p2.x - p1.x), p1.y + t * (p2.y - p1.y));
                if (closestPoint == null || point.distanceSq(projection) < min_dist_sq) {
                    min_dist_sq = point.distanceSq(projection);
                    closestPoint = projection;
                }
            }
            last_point.setLocation(p2);
            path.next();
        }
        return closestPoint;
    }

    /**
     * [优化]处理玩家移动和碰撞。
     * 采用了迭代式解决碰撞和穿透的方法，确保了玩家-玩家和玩家-障碍物碰撞的稳定性和分离。
     */
    public void moveWithCollision(Player player, double dx, double dy) {
        // 先应用位移
        player.position.x += dx;
        player.position.y += dy;

        // 迭代多次，以解决连锁碰撞和深层穿透问题
        int collisionPasses = 5; // 增加迭代次数以提高稳定性
        double min_separation = 0.1; // 最小安全推离距离（相当于 GJK-EPA 中的 Epsilon）

        for (int i = 0; i < collisionPasses; i++) {

            // --- 玩家-障碍物碰撞 (圆形-多边形) ---
            for (Shape obstacle : obstacles) {
                // 使用我们高效的数学方法检测碰撞
                if (isCircleIntersectingPolygon(obstacle, player.position, Player.SIZE / 2.0)) {

                    Point2D.Double closestPoint = findClosestPointOnShape(player.position, obstacle);
                    if (closestPoint == null)
                        continue;

                    double nx = player.position.x - closestPoint.x;
                    double ny = player.position.y - closestPoint.y;
                    double dist = player.position.distance(closestPoint);
                    double radius = Player.SIZE / 2.0;

                    // 如果圆心到最近点的距离小于半径，说明发生碰撞
                    if (dist < radius) {
                        double penetrationDepth = radius - dist;

                        // 计算归一化法线向量（推离方向）
                        if (dist == 0) {
                            // 特殊情况：圆心与最近点重合。赋予一个随机方向。
                            double randomAngle = rand.nextDouble() * 2 * Math.PI;
                            nx = Math.cos(randomAngle);
                            ny = Math.sin(randomAngle);
                        } else {
                            // 归一化推离方向
                            nx /= dist;
                            ny /= dist;
                        }

                        // 强行推开玩家
                        // 推开距离 = 穿透深度 + 最小安全分离距离 (min_separation)
                        // 这就是 GJK-EPA 思想在圆形-多边形碰撞中的应用：MTV (Minimum Translation Vector)
                        double pushDistance = penetrationDepth + min_separation;

                        // 乘上 PENETRATION_MULTIPLIER 来加速大穿透的解决
                        pushDistance *= PENETRATION_MULTIPLIER;

                        player.position.x += nx * pushDistance;
                        player.position.y += ny * pushDistance;

                        // 速度投影和滑动
                        double dot = player.vx * nx + player.vy * ny;
                        if (dot < 0) {
                            // 只有当速度是朝向障碍物内部时，才移除这个分量
                            // 这实现了玩家沿着墙壁滑动，而不是停止。
                            player.vx -= dot * nx;
                            player.vy -= dot * ny;
                        }
                    }
                }
            }

            // --- 玩家-玩家碰撞 (圆形-圆形，使用空间网格加速) ---
            int playerGridX = (int) (player.position.x / gridCellSize);
            int playerGridY = (int) (player.position.y / gridCellSize);

            // 遍历玩家所在格子及周围8个格子
            for (int x = -1; x <= 1; x++) {
                for (int y = -1; y <= 1; y++) {
                    int checkX = playerGridX + x;
                    int checkY = playerGridY + y;

                    if (checkX >= 0 && checkX < gridWidth && checkY >= 0 && checkY < gridHeight) {
                        // 对这个格子里的所有玩家进行碰撞检测
                        for (Player other : spatialGrid[checkX][checkY]) {
                            // 只需要检测一次：只让ID较小的玩家发起推开，避免重复修正
                            if (player.id.compareTo(other.id) >= 0)
                                continue;

                            double distSq = player.position.distanceSq(other.position);
                            double totalRadius = Player.SIZE; // 两个半径之和

                            if (distSq < totalRadius * totalRadius && distSq > 0) {
                                double dist = Math.sqrt(distSq);

                                // 重叠深度，加上最小推离距离 min_separation
                                double penetrationDepth = totalRadius - dist + min_separation;

                                // 各自推开距离 = (重叠深度) / 2.0
                                double overlap = penetrationDepth / 2.0;

                                double nx = (player.position.x - other.position.x) / dist;
                                double ny = (player.position.y - other.position.y) / dist;

                                // 位置修正：推开玩家
                                player.position.x += nx * overlap;
                                player.position.y += ny * overlap;
                                other.position.x -= nx * overlap;
                                other.position.y -= ny * overlap;

                                // 动量交换（推开效果）
                                // 计算动量在推开方向上的分量
                                double p1_dot = player.vx * nx + player.vy * ny;
                                double p2_dot = other.vx * nx + other.vy * ny;

                                // 交换动量，模拟弹性碰撞
                                player.vx += (p2_dot - p1_dot) * nx;
                                player.vy += (p2_dot - p1_dot) * ny;
                                other.vx += (p1_dot - p2_dot) * nx;
                                other.vy += (p1_dot - p2_dot) * ny;
                            }
                        }
                    }
                }
            }
        }
    }

    // 移除一个玩家（通常在玩家断开连接时调用）。
    public void removePlayer(String playerId) {
        playerDamageBuffer.remove(playerId); // 移除伤害缓冲区。
        players.removeIf(p -> p.id.equals(playerId)); // 从玩家列表中移除。
    }

    /**
     * 为一个玩家发放一套随机的道具（用于TDM/僵尸模式）。
     * 
     * @param player 需要发放道具的玩家。
     */
    private void giveRandomEquipment(Player player) {
        // 清空玩家现有的所有道具
        player.equipment.clear();

        // 创建一个包含所有可能道具的列表
        List<Item> possibleItems = new ArrayList<>(Arrays.asList(
                Item.HE_GRENADE,
                Item.FLASHBANG,
                Item.SMOKE_GRENADE,
                // Item.MOLOTOV, (这里根据阵营给道具)
                // Item.INCENDIARY, (这里根据阵营给道具)
                Item.DECOY));
        // 根据阵营添加正确的燃烧弹/燃烧瓶
        // possibleItems.add(player.team == Player.Team.T ? Item.MOLOTOV :
        // Item.INCENDIARY);

        // 从列表中随机选择一个道具
        Item randomItem = possibleItems.get(rand.nextInt(possibleItems.size()));

        // 将选中的道具给予玩家（默认数量为1）
        player.equipment.put(randomItem, 1);

        // logger.accept("给予了随机道具 '" + randomItem.name() + "' 给玩家 " + player.name);
        // logger.accept("[背包状态检查] " + player.name + " 的背包现在是: " +
        // player.equipment.toString());
    }

    // [面板功能]添加一个AI玩家。
    private void addAiPlayer(Player.Team team) {
        String aiName = "BOT " + (players.stream().filter(p -> p.isAI).count() + 1); // 生成AI名称。
        // 创建AI玩家实例。
        Player ai = new Player(UUID.randomUUID().toString(), aiName, getSpawnPoint(team), team, true, gameMode, this,
                aiDifficulty, logger);

        ai.hasChosenTeam = true; // 告诉系统这个AI已经选好队伍了，不是占位符！

        if (gameMode == GameMode.DEMOLITION) { // 如果是爆破模式。
            ai.setWeapon(team == Player.Team.T ? Weapon.GLOCK18 : Weapon.USPS); // 设置初始手枪。
        } else { // 其他模式。
            Weapon aiWeapon = getWeaponByName(ai.nextWeapon); // 获取AI选择的武器。
            if (aiWeapon == null) {
                List<Weapon> validWeapons = Arrays.asList(Weapon.AK47, Weapon.M4A4, Weapon.AUG, Weapon.SG553,
                        Weapon.FAMAS, Weapon.GALIL);
                aiWeapon = validWeapons.get(rand.nextInt(validWeapons.size()));
            }
            ai.setWeapon(aiWeapon); // 设置武器，如果无效则使用随机步枪
            giveRandomEquipment(ai); // 在AI初次诞生时也给它一个随机道具
        }
        players.add(ai); // 将AI添加到玩家列表。
    }

    // 生成一个僵尸。
    private void spawnZombie() {
        // 创建僵尸实例。

        /** 常规波数的僵尸 */

        Player zombie = new Player(UUID.randomUUID().toString(), "Zombie", getZombieSpawnPoint(), Player.Team.ZOMBIE,
                true, gameMode, this, aiDifficulty, logger);
        zombie.maxHealth = 50 + currentWave * 15; // 设置最大生命值

        /** 30 - 200 波数的特殊波数的僵尸 */

        if (currentWave > 20 && currentWave % 10 == 0 && currentWave <= 200) {
            zombie.maxHealth = 100 * (50 + currentWave * 15);
        }

        zombie.health = zombie.maxHealth; // 将当前生命值设为最大值
        zombies.add(zombie); // 将僵尸添加到列表。
        resolveSpawnOverlaps(zombie); // 解决僵尸出生时的重叠问题。
    }

    // 开始下一波僵尸。
    private void startNextWave() {
        currentWave++; // 波数加一。
        // 计算本波要生成的僵尸总数。
        if (currentWave <= 20) {
            zombiesToSpawn = 5 + currentWave * 2;
        } else if (currentWave > 20 && currentWave % 10 == 0 && currentWave <= 200) {
            zombiesToSpawn = currentWave / 10;
        } else
            zombiesToSpawn = 45;

        waitingForNextWave = false; // 取消等待状态。
        // 计算生成间隔，确保在指定时间内生成所有僵尸。
        zombieSpawnInterval = (zombiesToSpawn > 0) ? ZOMBIE_WAVE_SPAWN_DURATION_MS / zombiesToSpawn : 500;
        logger.accept("开始波数: " + currentWave); // 记录日志。
    }

    // 更新来自客户端的玩家输入。
    public void updatePlayerInput(String playerId, JsonObject input) {
        // 找到发起输入的玩家实体
        Player playerEntity = getPlayerById(playerId);
        if (playerEntity == null)
            return;

        // 检查这个玩家是否正在控制一个BOT
        // 只应用输入到BOT身上
        if (playerEntity.isControllingBot()) {
            Player controlledBot = getPlayerById(playerEntity.controllingBotId);
            if (controlledBot != null && controlledBot.isAlive()) {
                applyInputToPlayer(controlledBot, input);
            }
        } else if (playerEntity.isAlive()) {
            // 正常应用输入
            applyInputToPlayer(playerEntity, input);
        }
        // 如果玩家死了且没有夺舍，则忽略输入
    }

    /**
     * 将 updatePlayerInput 的核心逻辑提取到一个可重用的方法中
     */
    private void applyInputToPlayer(Player p, JsonObject input) {
        p.angle = input.get("angle").getAsDouble();
        p.isShooting = input.get("shooting").getAsBoolean();
        p.isRequestingUnderhandThrow = input.has("underhand") && input.get("underhand").getAsBoolean();
        p.keysDown.clear();
        JsonArray keys = input.getAsJsonArray("keys");
        p.isWalking = input.has("walking") && input.get("walking").getAsBoolean(); // 读取客户端的静步状态
        keys.forEach(key -> p.keysDown.add(key.getAsString().toUpperCase()));
    }

    private Point2D.Double getSpawnPoint(Player.Team team) {

        // [新增] 检查禁区是否被激活
        boolean useForbiddenZones = (gameMode == GameMode.DEATHMATCH || gameMode == GameMode.ZOMBIE_MODE)
                && !this.forbiddenSpawnGridCells.isEmpty();

        // 如果是(死斗/僵尸)且(禁区存在)，则跳过所有出生区逻辑，直接进入全局随机
        if (!useForbiddenZones) {
            // --- 原有逻辑：在指定区域内查找 ---
            List<Rectangle> spawnAreas;
            if (gameMode == GameMode.DEATHMATCH) {
                spawnAreas = new ArrayList<>();
                if (this.ctSpawnAreas != null)
                    spawnAreas.addAll(this.ctSpawnAreas);
                if (this.tSpawnAreas != null)
                    spawnAreas.addAll(this.tSpawnAreas);
            } else {
                spawnAreas = (team == Player.Team.CT) ? this.ctSpawnAreas : this.tSpawnAreas;
            }

            if (spawnAreas != null && !spawnAreas.isEmpty()) {
                Rectangle spawnArea = spawnAreas.get(rand.nextInt(spawnAreas.size()));
                for (int attempts = 0; attempts < 50; attempts++) {
                    double x = spawnArea.getX() + rand.nextDouble() * spawnArea.getWidth();
                    double y = spawnArea.getY() + rand.nextDouble() * spawnArea.getHeight();
                    Point2D.Double spawnPoint = new Point2D.Double(x, y);

                    // 检查禁区 (即使在useForbiddenZones=false时也检查，以防万一)
                    if (isPointInForbiddenZone(spawnPoint)) {
                        continue;
                    }

                    // ... (检查障碍物 和 检查其他玩家 的逻辑不变) ...
                    boolean isSafe = true;
                    Area playerArea = new Area(new Ellipse2D.Double(spawnPoint.x - Player.SIZE / 2,
                            spawnPoint.y - Player.SIZE / 2, Player.SIZE, Player.SIZE));
                    for (Shape obs : obstacles) {
                        Area obstacleArea = new Area(obs);
                        obstacleArea.intersect(playerArea);
                        if (!obstacleArea.isEmpty()) {
                            isSafe = false;
                            break;
                        }
                    }
                    if (!isSafe)
                        continue;

                    for (Player otherPlayer : players) {
                        if (otherPlayer.isAlive() && otherPlayer.position.distanceSq(spawnPoint) < (Player.SIZE * 2)
                                * (Player.SIZE * 2)) {
                            isSafe = false;
                            break;
                        }
                    }
                    if (isSafe)
                        return spawnPoint;
                }
            }
            // --- 原有逻辑结束 ---
        }

        // --- 新逻辑：全局随机重生 (当 useForbiddenZones=true 或 找不到安全点时) ---
        logger.accept("警告: 未能在指定区域找到安全点, 或禁区已激活。切换到全局随机重生...");

        for (int attempts = 0; attempts < 100; attempts++) { // 增加尝试次数
            // 在整个地图上随机选点
            double x = rand.nextDouble() * this.width;
            double y = rand.nextDouble() * this.height;
            Point2D.Double spawnPoint = new Point2D.Double(x, y);

            // 1. 检查禁区
            if (isPointInForbiddenZone(spawnPoint)) {
                continue;
            }

            // 2. 检查障碍物 (必须检查)
            boolean isSafe = true;
            Area playerArea = new Area(new Ellipse2D.Double(spawnPoint.x - Player.SIZE / 2,
                    spawnPoint.y - Player.SIZE / 2, Player.SIZE, Player.SIZE));
            for (Shape obs : obstacles) {
                Area obstacleArea = new Area(obs);
                obstacleArea.intersect(playerArea);
                if (!obstacleArea.isEmpty()) {
                    isSafe = false;
                    break;
                }
            }
            if (!isSafe)
                continue;

            // 3. 检查玩家 (可选，但在DM中最好有)
            for (Player otherPlayer : players) {
                if (otherPlayer.isAlive()
                        && otherPlayer.position.distanceSq(spawnPoint) < (Player.SIZE * 2) * (Player.SIZE * 2)) {
                    isSafe = false;
                    break;
                }
            }
            if (isSafe)
                return spawnPoint; // 找到全局安全点
        }

        // 最终备用：返回地图中心
        logger.accept("严重警告: 无法在100次尝试内找到任何安全重生点！强制重生在地图中心。");
        return new Point2D.Double(this.width / 2.0, this.height / 2.0);
    }

    /**
     * [优化] 获取一个安全的僵尸出生点。
     * 使用“预计算网格 + 动态过滤”方案，效率极高。
     */
    private Point2D.Double getZombieSpawnPoint() {
        // 如果由于某种原因没有预选点，则退回到旧的随机边缘生成逻辑
        if (precomputedSpawnPoints.isEmpty()) {
            logger.accept("警告: 预选出生点列表为空，使用备用生成逻辑。");
            return getZombieSpawnPointRandom();
        }

        // 获取所有存活的人类玩家的位置
        List<Point2D.Double> humanPositions = players.stream()
                .filter(p -> p.isAlive() && p.team == Player.Team.CT)
                .map(p -> p.position)
                .collect(Collectors.toList());

        // 如果没有人类存活，直接从预选点中随机选一个
        if (humanPositions.isEmpty()) {
            return precomputedSpawnPoints.get(rand.nextInt(precomputedSpawnPoints.size()));
        }

        final double minDistanceToPlayerSq = 400.0 * 400.0; // 最小安全距离的平方 (400像素)

        // 动态过滤：从所有预选点中，筛选出当前离所有玩家都足够远的点
        List<Point2D.Double> candidatePoints = precomputedSpawnPoints.stream()
                .filter(spawnPoint -> {
                    if (isPointInForbiddenZone(spawnPoint)) {
                        return false; // 在禁区内，淘汰这个点
                    }
                    // 检查这个出生点到所有玩家的距离
                    for (Point2D.Double humanPos : humanPositions) {
                        if (spawnPoint.distanceSq(humanPos) < minDistanceToPlayerSq) {
                            return false; // 距离太近，淘汰这个点
                        }
                    }
                    return true; // 距离所有玩家都足够远，这是一个有效的候选点
                })
                .collect(Collectors.toList());

        // 决策
        if (!candidatePoints.isEmpty()) {
            // 如果有候选点，直接从里面随机选一个返回，这非常快
            return candidatePoints.get(rand.nextInt(candidatePoints.size()));
        } else {
            // 容错：如果所有预选点都离玩家太近（比如玩家堵在角落），则退回到在地图边缘生成
            logger.accept("所有预选点都离玩家太近，尝试在地图边缘生成。");
            return getZombieSpawnPointRandom();
        }
    }

    // 获取一个安全的僵尸出生点（通常在地图边缘）。
    private Point2D.Double getZombieSpawnPointRandom() {
        Rectangle spawnBounds = new Rectangle(0, 0, (int) Player.SIZE, (int) Player.SIZE); // 创建碰撞检查矩形。
        for (int attempts = 0; attempts < 50; attempts++) { // 尝试50次。
            Point2D.Double spawnPoint;
            int padding = 50, side = rand.nextInt(4); // 随机选择一个地图边缘。
            if (side == 0)
                spawnPoint = new Point2D.Double(rand.nextInt(width), padding); // 上边缘
            else if (side == 1)
                spawnPoint = new Point2D.Double(rand.nextInt(width), height - padding); // 下边缘
            else if (side == 2)
                spawnPoint = new Point2D.Double(padding, rand.nextInt(height)); // 左边缘
            else
                spawnPoint = new Point2D.Double(width - padding, rand.nextInt(height)); // 右边缘

            if (isPointInForbiddenZone(spawnPoint)) {
                continue; // 这一点在禁区内，跳过
            }

            spawnBounds.setLocation((int) (spawnPoint.x - Player.SIZE / 2), (int) (spawnPoint.y - Player.SIZE / 2)); // 移动检查矩形。
            boolean isSafe = true; // 假设安全。
            for (Shape obs : obstacles) { // 检查是否与障碍物重叠。
                if (obs.intersects(spawnBounds)) {
                    isSafe = false;
                    break;
                }
            }
            if (isSafe)
                return spawnPoint; // 如果安全，则返回该点。
        }
        logger.accept("警告: 无法在 50 次尝试内找到安全的僵尸出生点。"); // 失败则记录警告。
        return new Point2D.Double(width / 2.0, 50); // 返回一个默认点。
    }

    private static final double LINE_INTERSECTION_EPSILON = 1.0e-8;

    /**
     * 返回线段从起点向终点经过的第一段“实体形状”区间。
     *
     * <p>不能简单取最近的两个边交点：穿过多边形顶点时相邻边会产生重复交点，
     * 凹多边形可能有四个以上交点，而线段起点位于形状内部时只有一个出口交点。
     * 这里先对边界参数去重，再用每个相邻区间的中点确认该区间是否真的位于填充区域内。</p>
     */
    public static Point2D.Double[] getLineShapeIntersections(Line2D.Double line, Shape shape) {
        if (line == null || shape == null)
            return null;

        double dx = line.x2 - line.x1;
        double dy = line.y2 - line.y1;
        if (dx * dx + dy * dy <= LINE_INTERSECTION_EPSILON * LINE_INTERSECTION_EPSILON)
            return null;
        Rectangle2D bounds = shape.getBounds2D();
        if (!bounds.intersectsLine(line) && !bounds.contains(line.x1, line.y1))
            return null;

        List<Double> boundaryParameters = new ArrayList<>();
        PathIterator pathIterator = shape.getPathIterator(null, 0.25);
        double[] coords = new double[6];
        double lastX = 0, lastY = 0, moveX = 0, moveY = 0;
        boolean hasCurrentSubpath = false;

        while (!pathIterator.isDone()) {
            int type = pathIterator.currentSegment(coords);
            if (type == PathIterator.SEG_MOVETO) {
                moveX = lastX = coords[0];
                moveY = lastY = coords[1];
                hasCurrentSubpath = true;
            } else if (type == PathIterator.SEG_LINETO && hasCurrentSubpath) {
                addSegmentIntersectionParameter(line, lastX, lastY, coords[0], coords[1], boundaryParameters);
                lastX = coords[0];
                lastY = coords[1];
            } else if (type == PathIterator.SEG_CLOSE && hasCurrentSubpath) {
                addSegmentIntersectionParameter(line, lastX, lastY, moveX, moveY, boundaryParameters);
                lastX = moveX;
                lastY = moveY;
            }
            pathIterator.next();
        }

        boundaryParameters.sort(Double::compareTo);
        List<Double> breakpoints = new ArrayList<>(boundaryParameters.size() + 2);
        breakpoints.add(0.0);
        for (double parameter : boundaryParameters) {
            double clamped = Math.max(0.0, Math.min(1.0, parameter));
            double previous = breakpoints.get(breakpoints.size() - 1);
            if (clamped - previous > LINE_INTERSECTION_EPSILON && clamped < 1.0 - LINE_INTERSECTION_EPSILON)
                breakpoints.add(clamped);
        }
        if (1.0 - breakpoints.get(breakpoints.size() - 1) > LINE_INTERSECTION_EPSILON)
            breakpoints.add(1.0);

        for (int i = 0; i + 1 < breakpoints.size(); i++) {
            double entryParameter = breakpoints.get(i);
            double exitParameter = breakpoints.get(i + 1);
            if (exitParameter - entryParameter <= LINE_INTERSECTION_EPSILON)
                continue;

            double midpoint = (entryParameter + exitParameter) * 0.5;
            if (shape.contains(line.x1 + dx * midpoint, line.y1 + dy * midpoint)) {
                return new Point2D.Double[] {
                        new Point2D.Double(line.x1 + dx * entryParameter, line.y1 + dy * entryParameter),
                        new Point2D.Double(line.x1 + dx * exitParameter, line.y1 + dy * exitParameter)
                };
            }
        }
        return null;
    }

    private static void addSegmentIntersectionParameter(Line2D.Double line, double edgeX1, double edgeY1,
            double edgeX2, double edgeY2, List<Double> parameters) {
        double rayX = line.x2 - line.x1;
        double rayY = line.y2 - line.y1;
        double edgeX = edgeX2 - edgeX1;
        double edgeY = edgeY2 - edgeY1;
        double denominator = rayX * edgeY - rayY * edgeX;
        if (Math.abs(denominator) <= LINE_INTERSECTION_EPSILON)
            return;

        double offsetX = edgeX1 - line.x1;
        double offsetY = edgeY1 - line.y1;
        double rayParameter = (offsetX * edgeY - offsetY * edgeX) / denominator;
        double edgeParameter = (offsetX * rayY - offsetY * rayX) / denominator;
        if (rayParameter >= -LINE_INTERSECTION_EPSILON && rayParameter <= 1.0 + LINE_INTERSECTION_EPSILON
                && edgeParameter >= -LINE_INTERSECTION_EPSILON && edgeParameter <= 1.0 + LINE_INTERSECTION_EPSILON) {
            parameters.add(rayParameter);
        }
    }

    // 计算两条线段的交点。
    public static Point2D getLineLineIntersectionPoint(Line2D.Double line1, Line2D.Double line2) {
        double x1 = line1.getX1(), y1 = line1.getY1(), x2 = line1.getX2(), y2 = line1.getY2(); // 获取第一条线的坐标。
        double x3 = line2.getX1(), y3 = line2.getY1(), x4 = line2.getX2(), y4 = line2.getY2(); // 获取第二条线的坐标。
        // 使用行列式计算分母
        double den = (x1 - x2) * (y3 - y4) - (y1 - y2) * (x3 - x4);
        if (den == 0)
            return null; // 为0 则两条线平行或共线。
        // 计算参数t，表示交点在线段1上的位置比例。
        double t = ((x1 - x3) * (y3 - y4) - (y1 - y3) * (x3 - x4)) / den;
        // 计算参数u，表示交点在线段2上的位置比例。
        double u = -((x1 - x2) * (y1 - y3) - (y1 - y2) * (x1 - x3)) / den;

        if (t >= 0 && t <= 1 && u >= 0 && u <= 1) { // [BUG修复] t也需要检查是否<=1
            return new Point2D.Double(x1 + t * (x2 - x1), y1 + t * (y2 - y1)); // 计算并返回交点坐标。
        }
        return null; // 否则，返回null。
    }

    // 检查游戏是否结束。
    // 注意：TDM 的时间/分数检查已移入 handleGameModeLogic()，此处不再重复检查，避免绕过 roundPhase 守卫。
    private boolean isGameOver() {
        return isGameOver;
    }

    /** 比赛终态：停止所有输入和位移，并丢弃尚未执行的 AI 请求。 */
    private void finishGame() {
        if (isGameOver)
            return;
        isGameOver = true;
        pendingAiDropRequests.clear();
        for (Player player : getAllCharacters()) {
            player.keysDown.clear();
            player.isShooting = false;
            player.isInteracting = false;
            player.ax = 0;
            player.ay = 0;
            player.vx = 0;
            player.vy = 0;
            aiInputMailbox.remove(player.id);
        }
    }

    /** TDM 达到分数上限时胜负已经确定，无需再等待 5 秒才进入最终结算。 */
    private void finishTdmMatch(Player.Team winner, String reason) {
        endRound(winner, reason);
        finishGame();
    }

    public GameMode getGameMode() {
        return this.gameMode;
    } // 获取当前游戏模式。

    private void updateVisualEffects() {
        visualEffects.removeIf(VisualEffect::isExpired);
    } // 移除已经过期的视觉效果。

    // 处理近战攻击请求。
    public void requestMelee(Player attacker, Player target, int damage) {
        if (attacker != null && target != null && target.isAlive()) {
            dealDamageAndHandleEvents(attacker, target, damage, false, "KNIFE");
        }
    }

    public Rectangle getBombSiteA() {
        return bombSiteA;
    } // 获取A点区域。

    public Rectangle getBombSiteB() {
        return bombSiteB;
    } // 获取B点区域。

    public boolean isBombPlanted() {
        return bombPlanted;
    } // 检查炸弹是否已安放。

    public Point2D.Double getBombPosition() {
        return bombPosition;
    } // 获取炸弹位置。

    public RoundPhase getRoundPhase() {
        return roundPhase;
    } // 获取当前回合阶段。

    public Player.Team getRoundWinner() {
        return roundWinner;
    } // 获取本局赢家

    // 获取掉落在地上的C4。
    public DroppedItem getDroppedBomb() {
        return droppedItems.stream().filter(item -> item.isBomb).findFirst().orElse(null);
    }

    public List<Player> getPlayers() {
        return players;
    } // 获取所有玩家列表。

    public List<DroppedItem> getDroppedItems() {
        return droppedItems;
    } // 获取所有掉落物列表。

    /**
     * [优化] 创建一个只包含静态地图数据的JSON对象。
     * 这个包只在客户端第一次连接时发送一次。
     * 
     * @return 包含地图数据的JsonObject。
     */
    public JsonObject getMapDataJson() {
        JsonObject mapData = new JsonObject(); // 创建根对象。
        mapData.addProperty("type", "map_data"); // 设置类型为 "map_data"。
        mapData.addProperty("width", width); // 添加地图宽度。
        mapData.addProperty("height", height); // 添加地图高度。

        // 序列化障碍物数据。
        JsonArray obstaclesArray = new JsonArray();
        for (MapData.ShapeWrapper wrapper : this.obstacleWrappers) {
            JsonObject obj = new JsonObject();
            obj.addProperty("type", wrapper.type.name());
            if (wrapper.type == MapData.ShapeWrapper.ShapeType.POLYGON) {
                JsonArray xPoints = new JsonArray(), yPoints = new JsonArray();
                for (double x : wrapper.xPoints)
                    xPoints.add(x);
                for (double y : wrapper.yPoints)
                    yPoints.add(y);
                obj.add("xPoints", xPoints);
                obj.add("yPoints", yPoints);
            } else {
                obj.addProperty("x", wrapper.x);
                obj.addProperty("y", wrapper.y);
                obj.addProperty("w", wrapper.width);
                obj.addProperty("h", wrapper.height);
            }
            obstaclesArray.add(obj);
        }
        mapData.add("obstacles", obstaclesArray);

        // 序列化炸弹点和出生点数据。
        if (bombSiteA != null) {
            JsonObject siteA = new JsonObject();
            siteA.addProperty("x", bombSiteA.x);
            siteA.addProperty("y", bombSiteA.y);
            siteA.addProperty("w", bombSiteA.width);
            siteA.addProperty("h", bombSiteA.height);
            mapData.add("bombSiteA", siteA);
        }
        if (bombSiteB != null) {
            JsonObject siteB = new JsonObject();
            siteB.addProperty("x", bombSiteB.x);
            siteB.addProperty("y", bombSiteB.y);
            siteB.addProperty("w", bombSiteB.width);
            siteB.addProperty("h", bombSiteB.height);
            mapData.add("bombSiteB", siteB);
        }
        if (ctSpawnAreas != null) {
            JsonArray ctSpawns = new JsonArray();
            for (Rectangle rect : ctSpawnAreas) {
                JsonObject spawnObj = new JsonObject();
                spawnObj.addProperty("x", rect.x);
                spawnObj.addProperty("y", rect.y);
                spawnObj.addProperty("w", rect.width);
                spawnObj.addProperty("h", rect.height);
                ctSpawns.add(spawnObj);
            }
            mapData.add("ctSpawnAreas", ctSpawns);
        }
        if (tSpawnAreas != null) {
            JsonArray tSpawns = new JsonArray();
            for (Rectangle rect : tSpawnAreas) {
                JsonObject spawnObj = new JsonObject();
                spawnObj.addProperty("x", rect.x);
                spawnObj.addProperty("y", rect.y);
                spawnObj.addProperty("w", rect.width);
                spawnObj.addProperty("h", rect.height);
                tSpawns.add(spawnObj);
            }
            mapData.add("tSpawnAreas", tSpawns);
        }
        if (!generalForbiddenGridCells.isEmpty()) {
            JsonArray forbiddenZones = new JsonArray();
            for (java.awt.Point p : generalForbiddenGridCells) {
                JsonObject pointObj = new JsonObject();
                pointObj.addProperty("x", p.x);
                pointObj.addProperty("y", p.y);
                forbiddenZones.add(pointObj);
            }
            mapData.add("generalForbiddenZones", forbiddenZones);
        }
        return mapData; // 返回地图数据JSON对象。
    }

    /**
     * [网络优化] 创建一个包含所有动态物体绝对位置的完整更新包（大包）。
     * 
     * @return 包含完整游戏状态的JsonObject。
     */
    public JsonObject getFullUpdateJson() {
        JsonObject state = new JsonObject(); // 创建根对象。
        state.addProperty("type", "full_update"); // 设置类型为 "full_update"。

        // 序列化所有非地图的通用游戏状态信息。
        serializeBaseGameState(state);

        // 序列化所有玩家的完整信息（包括绝对位置x, y）。
        JsonArray playersArray = new JsonArray();
        players.forEach(p -> playersArray.add(p.toJson())); // 调用 Player 的 toJson() 方法。
        state.add("players", playersArray);

        // 序列化所有僵尸的完整信息。
        JsonArray zombiesArray = new JsonArray();
        zombies.stream().filter(Player::isAlive).forEach(z -> zombiesArray.add(z.toJson()));
        state.add("zombies", zombiesArray);

        // 序列化所有掉落物品的完整信息。
        JsonArray droppedItemsArray = new JsonArray();
        droppedItems.forEach(item -> droppedItemsArray.add(item.toJson()));
        state.add("droppedItems", droppedItemsArray);

        // 序列化所有视觉和听觉效果（这些是瞬时事件，每次都应完整发送）。
        serializeEffects(state);

        // 序列化飞行中的手榴弹
        serializeThrownGrenades(state);
        return state;
    }

    /**
     * [网络优化] 创建一个只包含增量变化（主要是速度）的轻量级更新包（小包）。
     *
     * @return 包含增量更新的JsonObject。
     */
    public JsonObject getSmallUpdateJson() {
        JsonObject state = new JsonObject();
        state.addProperty("type", "small_update");

        serializeBaseGameState(state);

        JsonArray playersArray = new JsonArray();
        for (Player p : players) {
            JsonObject pJson = new JsonObject();

            // 默认的数据源是玩家自己
            Player dataSource = p;

            // 如果玩家正在夺舍一个存活的BOT，则切换数据源为被控制的BOT
            if (p.isControllingBot()) {
                Player controlledBot = getPlayerById(p.controllingBotId);
                if (controlledBot != null && controlledBot.isAlive()) {
                    dataSource = controlledBot; // 将数据源切换为BOT！
                }
            }
            // 无论是否夺舍，ID 永远是人类玩家的ID
            pJson.addProperty("id", p.id);

            // 所有数据都从正确的 dataSource 获取
            pJson.addProperty("vx", dataSource.vx);
            pJson.addProperty("vy", dataSource.vy);
            pJson.addProperty("angle", dataSource.angle);
            pJson.addProperty("health", dataSource.health);
            addControlStateToSmallUpdate(pJson, dataSource.isAlive(), p.spectatorMode, p.spectatorTargetId);
            pJson.addProperty("isShooting", dataSource.isShooting);
            pJson.addProperty("isReloading", dataSource.isReloading);
            pJson.addProperty("predictedRecoilAngle", dataSource.predictedRecoilAngle);

            Weapon currentWep = dataSource.getCurrentWeapon();

            // 其他不经常变化的数据（如名称、队伍、金钱等）在小包中不发送。
            playersArray.add(pJson); // 添加到数组。
        }
        state.add("players", playersArray);

        // 序列化所有僵尸的轻量级信息。
        JsonArray zombiesArray = new JsonArray();
        for (Player z : zombies) {
            if (z.isAlive()) {
                JsonObject zJson = new JsonObject();
                zJson.addProperty("id", z.id);
                zJson.addProperty("vx", z.vx);
                zJson.addProperty("vy", z.vy);
                zJson.addProperty("angle", z.angle);
                zJson.addProperty("health", z.health);
                zombiesArray.add(zJson);
            }
        }
        state.add("zombies", zombiesArray);

        // 序列化所有视觉和听觉效果。
        serializeEffects(state);

        // 序列化投掷物
        serializeThrownGrenades(state);

        return state; // 返回小包JSON对象。
    }

    static void addControlStateToSmallUpdate(JsonObject target, boolean isAlive, String spectatorMode,
            String spectatorTargetId) {
        target.addProperty("isAlive", isAlive);
        target.addProperty("spectatorMode", spectatorMode);
        // addProperty 会把 null 序列化为 JsonNull，以便客户端清除旧观战目标。
        target.addProperty("spectatorTargetId", spectatorTargetId);
    }

    /**
     * 将通用的、非地图的游戏状态信息序列化到一个给定的JsonObject中。
     *
     * @param state 要添加信息的JsonObject。
     */
    private void serializeBaseGameState(JsonObject state) {
        state.addProperty("mode", gameMode.toString()); // 添加游戏模式。
        state.addProperty("isGameOver", isGameOver()); // 添加游戏是否结束。
        if (gameMode == GameMode.TEAM_DEATHMATCH) { // TDM模式信息。
            state.addProperty("ctScore", teamCTScore_TDM);
            state.addProperty("tScore", teamTScore_TDM);
            state.addProperty("tdmWinScore", tdmWinScore);
            long remaining = Math.max(0,
                    TDM_GAME_DURATION_SECONDS - (System.currentTimeMillis() - gameStartTime) / 1000);
            state.addProperty("time", remaining);
            // [2025-12-01] 死斗模式计时器
        } else if (gameMode == GameMode.DEATHMATCH) {
            // 复制 TDM 的计时器逻辑
            long remaining = Math.max(0,
                    TDM_GAME_DURATION_SECONDS - (System.currentTimeMillis() - gameStartTime) / 1000);
            state.addProperty("time", remaining);
            // 死斗模式不发送团队分数
        } else if (gameMode == GameMode.ZOMBIE_MODE) { // 僵尸模式信息。
            state.addProperty("wave", currentWave);
            state.addProperty("zombiesLeft", zombies.size() + zombiesToSpawn);
            if (waitingForNextWave && zombies.isEmpty()) {
                state.addProperty("nextWaveIn",
                        Math.max(0, (nextWaveStartTime - System.currentTimeMillis()) / 1000 + 1));
            }
        } else if (gameMode == GameMode.DEMOLITION) { // 爆破模式信息。
            state.addProperty("ctScore", teamCTScore_DEMO);
            state.addProperty("tScore", teamTScore_DEMO);
            state.addProperty("round", currentRound);
            state.addProperty("roundPhase", roundPhase.toString());

            long roundTimeRemaining = 0;
            long currentTime = System.currentTimeMillis(); // 获取一次当前时间，避免重复调用

            switch (roundPhase) {
                case FREEZE_TIME:
                    // 冻结时间倒计时
                    roundTimeRemaining = Math.max(0, DEMO_FREEZE_TIME_MS - (currentTime - roundStartTime));
                    break;
                case IN_PROGRESS:
                    if (!bombPlanted) {
                        // 正常回合倒计时
                        roundTimeRemaining = Math.max(0,
                                (DEMO_ROUND_TIME_MS + DEMO_FREEZE_TIME_MS) - (currentTime - roundStartTime));
                    } else {
                        // 如果炸弹已安放，roundTime 应该显示炸弹倒计时。这里保持不变，由 bombTimer 字段负责
                        // 为了避免客户端逻辑混乱，可以继续显示回合时间走完，或者设置为一个特殊值，但通常 bombTimer 会覆盖它的显示，所以这里设为0是安全的
                        roundTimeRemaining = 0;
                    }
                    break;
                case ROUND_OVER:
                    // 在回合结束阶段，发送一个5秒的等待倒计时
                    long postRoundWaitTime = 5000L; // 5秒回合后等待时间
                    roundTimeRemaining = Math.max(0, postRoundWaitTime - (currentTime - roundEndTime));
                    break;
            }

            state.addProperty("roundTime", roundTimeRemaining);
            state.addProperty("bombPlanted", bombPlanted);
            if (bombPlanted) {
                state.addProperty("bombTimer", Math.max(0, DEMO_BOMB_TIME_MS - (currentTime - bombPlantTime)));
                JsonObject bombPos = new JsonObject();
                bombPos.addProperty("x", bombPosition.x);
                bombPos.addProperty("y", bombPosition.y);
                state.add("bombPosition", bombPos);
            }
            state.addProperty("roundWinner", roundWinner != null ? roundWinner.toString() : "");
            state.addProperty("roundWinReason", roundWinReason);

        }
        // 在 serializeBaseGameState() 方法中
        JsonArray killFeedArray = new JsonArray();

        for (KillFeedInfo info : recentKills) {
            JsonObject killJson = new JsonObject();
            killJson.addProperty("killerId", info.killerId());
            killJson.addProperty("killerName", info.killerName());
            killJson.addProperty("killerTeam", info.killerTeam().name());
            killJson.addProperty("victimId", info.victimId());
            killJson.addProperty("victimName", info.victimName());
            killJson.addProperty("victimTeam", info.victimTeam().name());
            killJson.addProperty("weapon", info.weapon());
            killJson.addProperty("isHeadshot", info.isHeadshot());
            killJson.addProperty("killTimestamp", info.timestamp()); // 把时间戳添加到JSON中
            killFeedArray.add(killJson);
        }
        state.add("killFeed", killFeedArray);
        // 序列化烟雾
        JsonArray smokeArray = new JsonArray();
        smokePuffs.forEach(s -> smokeArray.add(s.toJson()));
        // System.out.println("[服务端-发送] 准备发送烟雾数据包, 包含颗粒数量: " + smokeArray.size());
        state.add("smokePuffs", smokeArray);
        // 序列化火焰
        serializeFirePatches(state);
        // 序列化标点
        if (!pingMarkers.isEmpty()) {
            JsonArray pingArray = new JsonArray();
            pingMarkers.forEach(p -> pingArray.add(p.toJson()));
            state.add("pingMarkers", pingArray);
        }
    }

    /**
     * 供 AI (PerceptionModule) 调用。
     * 获取 AI 声音缓冲区中的所有事件，并 *清空* 该缓冲区。
     * 
     * @return 声音事件的副本。
     */
    public List<SoundEvent> getAndClearAiSoundEvents() {
        // 同步访问 AI 缓冲区
        synchronized (aiSoundEventsBuffer) {
            if (aiSoundEventsBuffer.isEmpty()) {
                return Collections.emptyList(); // 避免创建不必要的空列表
            }
            // 1. 创建一个副本
            List<SoundEvent> copy = new ArrayList<>(aiSoundEventsBuffer);
            // 2. 清空 AI 缓冲区
            aiSoundEventsBuffer.clear();
            // 3. 返回副本给 PerceptionModule
            return copy;
        }
    }

    /**
     * 将瞬时的效果（视觉、声音）序列化到一个给定的JsonObject中。
     *
     * @param state 要添加信息的JsonObject。
     */
    private void serializeEffects(JsonObject state) {

        // 这个是跟声音一起看看谁有问题的,销毁之前
        // if (logger != null) {
        // // 2. 遍历声音事件列表
        // for (SoundEvent soundEvent : soundEvents) {
        // // 3. 确保事件本身不为 null，防止日志记录失败
        // if (soundEvent != null) {
        // // 4. 获取当前精确时间
        // LocalDateTime now = LocalDateTime.now();
        // // 5. 格式化时间戳 (HH:mm:ss.SSS)
        // String timestampStr = now.format(TIMESTAMP_FORMATTER);
        // // 6. 使用 logger.accept 和 String.format 来记录日志
        // logger.accept(String.format("[%s] [AIState] %s",
        // timestampStr, // 插入格式化的时间戳
        // soundEvent.toString())); // soundEvent.toString() 会被自动调用
        // }
        // }
        // }

        // --- 序列化 “伤害事件” ---
        if (!privateDamageEvents.isEmpty()) {
            JsonArray privateMsgArray = new JsonArray();
            // System.out.println("[服务端调试] 检测到 privateDamageEvents 不为空，准备序列化...");
            privateDamageEvents.forEach(msg -> { // <--- 使用你的列表名
                JsonObject pm = new JsonObject();
                pm.addProperty("to", msg.recipientId()); // 添加接收者 ID
                pm.add("payload", msg.payload()); // 添加伤害日志
                privateMsgArray.add(pm);
            });

            state.add("damageLogEvents", privateMsgArray);
            // System.out.println("[服务端调试] 已添加 damageLogEvents 到 state: " +
            // privateMsgArray.toString());
            privateDamageEvents.clear(); // <--- 清空你的列表
        }

        // 序列化视觉效果。
        JsonArray vfxArray = new JsonArray();
        visualEffects.forEach(vfx -> vfxArray.add(vfx.toJson()));
        state.add("vfx", vfxArray);

        // 序列化公共音效。
        JsonArray soundArray = new JsonArray();
        soundEvents.forEach(s -> soundArray.add(s.toJson()));
        state.add("soundEvents", soundArray);
        soundEvents.clear(); // 发送后立即清空。

        // 序列化私有音效。
        JsonArray privateSoundArray = new JsonArray();
        privateSoundEvents.forEach(s -> privateSoundArray.add(s.toJson()));
        state.add("privateSoundEvents", privateSoundArray);
        privateSoundEvents.clear(); // 发送后立即清空。

        // 序列化闪光弹事件
        JsonArray flashArray = new JsonArray();
        flashEvents.forEach(f -> flashArray.add(f.toJson()));
        state.add("flashEvents", flashArray);
        flashEvents.clear(); // 发送后清空

        // [v2.8] 序列化脚步声暴露事件
        if (!footstepRevealEvents.isEmpty()) {
            JsonArray revealArray = new JsonArray();
            footstepRevealEvents.forEach(e -> revealArray.add(e.toJson()));
            state.add("footstepReveals", revealArray);
            footstepRevealEvents.clear(); // 发送后清空
        }
    }

    public void broadcastGlobalVoice(String soundName) {
        for (Player p : players) {
            if (p != null) {
                privateSoundEvents.add(new HeadshotEvent(p.id, soundName));
            }
        }
    }
    // 处理交互进度（安放/拆除C4）。

    private void handleInteractionProgress(Player p) {
        // 在交互时，强制让玩家静止，防止因漂移导致中断
        p.vx = 0;
        p.vy = 0;

        long interactionTime = System.currentTimeMillis() - p.interactionStartTime;

        if (p.team == Player.Team.T && p.hasBomb && !bombPlanted) {

            // 打印安放进度
            System.out.println(
                    "[服务器调试] 玩家 " + p.name + " 正在安放C4... 进度: " + interactionTime + " / " + DEMO_PLANT_TIME_MS + " ms");

            if (interactionTime >= DEMO_PLANT_TIME_MS) {
                // 确认安放完成的逻辑块被执行
                // System.out.println("!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!");
                System.out.println("!!! [服务器] C4安放成功！触发音效和状态变更。");
                // System.out.println("!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!");

                bombPlanted = true;
                bombPlantTime = System.currentTimeMillis();
                bombPosition = (Point2D.Double) p.position.clone();
                bombPlanterId = p.id;
                p.hasBomb = false;
                p.isInteracting = false;

                logger.accept("玩家 " + p.name + " 安放了炸弹！");

                // 使用 addSoundEvent 播放一个位置相关的、公开的“安放完成”音效
                addSoundEvent(SoundEvent.SoundType.GENERIC, "weapons/c4/c4_plant", p.position.x, p.position.y);

                // 使用 broadcastGlobalVoice 广播全局的“炸弹已经安放”语音
                broadcastGlobalVoice("ALL_DEMOLITION/c4/bombpl");
            } else if (System.currentTimeMillis() - p.lastKeyPressTime > 400) {
                int keyNum = rand.nextInt(7) + 1;
                privateSoundEvents.add(new HeadshotEvent(p.id, "weapons/c4/key_press" + keyNum));
                p.lastKeyPressTime = System.currentTimeMillis();
            }

        } else if (p.team == Player.Team.CT && bombPlanted) {
            if (p.position.distance(bombPosition) < 50) {

                // 打印拆除进度
                long requiredTime = p.hasDefuseKit ? DEMO_DEFUSE_WITH_KIT_TIME_MS : DEMO_DEFUSE_TIME_MS;
                System.out.println(
                        "[服务器调试] 玩家 " + p.name + " 正在拆除C4... 进度: " + interactionTime + " / " + requiredTime + " ms");

                if (interactionTime >= requiredTime) {
                    p.isInteracting = false;
                    addSoundEvent(SoundEvent.SoundType.GENERIC, "weapons/c4/c4_disarmfinish", p.position.x,
                            p.position.y);
                    endRound(Player.Team.CT, "Bomb defused");
                }
            } else {
                playerStopInteraction(p.id);
            }
        }
    }

    // 处理玩家开始交互的请求。
    public void playerStartInteraction(String playerId) {
        Player humanPlayer = getPlayerById(playerId);
        if (humanPlayer == null)
            return;

        // 确定实际执行交互动作的目标 (BOT 或 本体玩家)
        Player actionTarget = humanPlayer.isControllingBot() ? getPlayerById(humanPlayer.controllingBotId)
                : humanPlayer;
        if (actionTarget == null || !actionTarget.isAlive())
            return;

        if (actionTarget.isReloading)
            return;

        boolean canInteract = false;
        String actionName = actionTarget.team == Player.Team.T ? "安放C4" : "拆除C4";
        String soundToPlay = null;

        if (actionTarget.team == Player.Team.T && actionTarget.hasBomb && !bombPlanted) {
            if ((bombSiteA != null && bombSiteA.contains(actionTarget.position))
                    || (bombSiteB != null && bombSiteB.contains(actionTarget.position))) {
                canInteract = true;
                soundToPlay = "weapons/c4/c4_initiate";
            }
        } else if (actionTarget.team == Player.Team.CT && bombPlanted) {
            if (bombPosition != null && bombPosition.distance(actionTarget.position) < 50) {
                canInteract = true;
                soundToPlay = "weapons/c4/c4_disarmstart";
            }
        }

        if (canInteract) {
            // [核心修复] 在接受交互请求的瞬间，强制清除所有移动状态！
            actionTarget.keysDown.clear();
            actionTarget.isShooting = false;
            actionTarget.ax = 0;
            actionTarget.ay = 0;
            actionTarget.vx = 0; // 强制清除残余速度
            actionTarget.vy = 0; // 强制清除残余速度

            logger.accept(String.format("玩家 %s 开始在 (%s) 执行交互: %s", humanPlayer.name, actionTarget.name, actionName));

            actionTarget.isInteracting = true;
            actionTarget.interactionStartTime = System.currentTimeMillis();

            if (soundToPlay != null) {
                addSoundEvent(SoundEvent.SoundType.GENERIC, soundToPlay, actionTarget.position.x,
                        actionTarget.position.y);
            }
        }
    }

    public void playerStopInteraction(String playerId) {
        Player humanPlayer = getPlayerById(playerId);
        if (humanPlayer == null)
            return;

        // --- 确定实际执行交互动作的目标 (BOT 或 本体玩家) ---
        Player actionTarget = humanPlayer.isControllingBot() ? getPlayerById(humanPlayer.controllingBotId)
                : humanPlayer;
        if (actionTarget == null)
            return;

        // 停止交互
        if (actionTarget.isInteracting) {
            actionTarget.isInteracting = false;
            actionTarget.interactionStartTime = 0;
            logger.accept(String.format("玩家 %s 停止了在 (%s) 的交互。", humanPlayer.name, actionTarget.name));
        }
    }

    // 处理玩家撤销购买的请求。
    public void playerUndoPurchase(String playerId, String itemName) {
        // 只能在冻结时间撤销
        if (roundPhase != RoundPhase.FREEZE_TIME)
            return;

        Player player = players.stream().filter(p -> p.id.equals(playerId)).findFirst().orElse(null);
        // 如果玩家不存在，或者玩家在本回合没有购买过这个物品
        if (player == null || !player.itemsBoughtThisFreezeTime.contains(itemName))
            return;

        // --- 退款逻辑 ---

        // 尝试将物品名作为武器处理
        Weapon weaponToUndo = getWeaponByName(itemName); // 使用我们已有的安全方法
        if (weaponToUndo != null) {
            // 如果它确实是一把武器
            if (player.primaryWeapon == weaponToUndo) {
                // --- 退还主武器 ---
                player.money += weaponToUndo.cost;
                player.primaryWeapon = null;
                player.primary_currentAmmo = 0;
                player.primary_reserveAmmo = 0;
                player.switchToSlot(2); // 自动切换到副武器
                player.itemsBoughtThisFreezeTime.remove(itemName);
                logger.accept(player.name + " refunded " + itemName);

            } else if (player.secondaryWeapon == weaponToUndo) {
                // --- 退还副武器 ---
                player.money += weaponToUndo.cost;
                // 将副武器重置为初始手枪
                player.setWeapon(player.team == Player.Team.T ? Weapon.GLOCK18 : Weapon.USPS, gameMode);
                player.itemsBoughtThisFreezeTime.remove(itemName);
                logger.accept(player.name + " refunded " + itemName);
            } else {
                logger.accept(player.name + " tried to refund a weapon they don't have: " + itemName);
            }
            return; // 处理完武器逻辑后，直接结束方法
        }

        // 如果它不是武器，再尝试作为装备/手雷/盔甲处理
        try {
            Item itemToUndo = Item.valueOf(itemName);
            int refund = player.itemPurchaseCostsThisFreezeTime.getOrDefault(itemName, itemToUndo.cost);
            player.money += refund;

            if (itemToUndo.type == Item.ItemType.GEAR) {
                // --- 退还装备（护甲/钳子） ---
                if (itemToUndo == Item.KEVLAR_HELMET) {
                    player.hasHelmet = false;
                    if (refund == itemToUndo.cost - Item.KEVLAR.cost) {
                        player.hasKevlar = true;
                        player.armorValue = 100;
                    } else {
                        player.hasKevlar = false;
                        player.armorValue = 0;
                    }
                } else if (itemToUndo == Item.KEVLAR) {
                    player.hasKevlar = false;
                    player.armorValue = 0;
                } else if (itemToUndo == Item.DEFUSE_KIT) {
                    player.hasDefuseKit = false;
                }
            } else {
                // --- 退还手雷 ---
                Integer count = player.equipment.get(itemToUndo);
                if (count != null) {
                    if (count > 1) {
                        player.equipment.put(itemToUndo, count - 1);
                    } else {
                        player.equipment.remove(itemToUndo);
                    }
                }
            }
            player.itemsBoughtThisFreezeTime.remove(itemName);
            player.itemPurchaseCostsThisFreezeTime.remove(itemName);
            logger.accept(player.name + " refunded " + itemName);

        } catch (IllegalArgumentException e) {
            logger.accept("Could not undo purchase: Unknown item name '" + itemName + "'");
        }
    }

    public void playerPickupDroppedWeapon(String playerId, String itemId) {
        Player player = players.stream().filter(p -> p.id.equals(playerId)).findFirst().orElse(null);
        DroppedItem item = droppedItems.stream().filter(d -> d.id.toString().equals(itemId)).findFirst().orElse(null);

        if (player == null || item == null || item.isBomb || item.weapon == null) {
            if (player != null)
                logger.accept(player.name + " 拾取物品 " + itemId + " 失败 (物品不存在或无效).");
            return;
        }

        long now = System.currentTimeMillis();
        if (!canPlayerPickupDroppedWeapon(item, playerId, now)) {
            long remainingMillis = Math.max(0L,
                    SELF_DROP_PICKUP_COOLDOWN_MS - (now - item.dropTime));
            logger.accept(player.name + " 暂时不能捡回自己刚扔出的 " + item.weapon.name()
                    + " (剩余 " + remainingMillis + "ms). ");
            return;
        }

        if (player.position.distance(item.position) < 160) {
            // 如果玩家已经有主武器了，先把它扔掉
            if (player.primaryWeapon != null) {
                playerDropWeapon(playerId);
            }

            // 直接设置主武器和对应的弹药“仓库”
            player.primaryWeapon = item.weapon;
            player.primary_currentAmmo = item.currentAmmo;
            player.primary_reserveAmmo = item.reserveAmmo;

            // 自动切换到刚捡起的主武器
            player.switchToSlot(1);
            // [BUG修复] 捡到新枪切出来时，重置后坐力
            player.shootTimeIndex = 0;
            player.continueShotingSpread = 0;
            player.predictedRecoilAngle = 0;
            player.r8ChargeStartTime = 0;
            droppedItems.remove(item);
            logger.accept(player.name + " 成功拾取 " + item.weapon.name());
        } else {
            logger.accept(player.name + " 拾取 " + item.weapon.name() + " 失败 (距离太远).");
        }
    }

    /**
     * 获取指定时间范围内的所有死亡事件
     *
     * @param millisecondsAgo 时间范围（毫秒）
     * @return 死亡事件列表
     */
    public List<PlayerDeath> getRecentDeaths(int millisecondsAgo) {
        long cutoff = System.currentTimeMillis() - millisecondsAgo; // 计算截止时间点。
        recentDeaths.removeIf(d -> d.timestamp < cutoff); // 移除所有早于截止时间点的死亡记录。
        return new ArrayList<>(recentDeaths); // 返回一个包含剩余记录的新列表。
    }

    /**
     * 检查一个点是否在地图边界内
     *
     * @param point 要检查的点
     * @return 如果在边界内则为true，否则为false
     */
    public boolean isInBounds(Point2D.Double point) {
        if (point == null)
            return false; // 如果点为空，返回false。
        // 检查点的x和y坐标是否都在地图的有效范围内。
        return point.x >= 0 && point.x < width && point.y >= 0 && point.y < height;
    }

    /**
     * 允许 AI 控制器访问空间网格以进行近距离感知。
     * 
     * @return 包含玩家列表的2D网格数组。
     */
    public List<Player>[][] getSpatialGrid() {
        return this.spatialGrid;
    }

    /**
     * 获取网格单元的大小。
     */
    public int getGridCellSize() {
        return this.gridCellSize;
    }

    /**
     * 获取网格的宽度（单元格数量）。
     */
    public int getGridWidth() {
        return this.gridWidth;
    }

    /**
     * 获取网格的高度（单元格数量）。
     */
    public int getGridHeight() {
        return this.gridHeight;
    }

    /**
     * [优化] 解决玩家在出生时可能发生的重叠问题。
     * 使用空间网格加速，只检查附近的单位，避免了全列表遍历。
     */
    private void resolveSpawnOverlaps(Player justSpawned) {
        double pushImpulse = 1.0;
        int maxChecks = 10; // 迭代次数可以适当减少，因为解决效率更高

        for (int i = 0; i < maxChecks; i++) {
            boolean wasOverlapping = false;

            // 获取新生玩家所在的网格坐标
            int playerGridX = (int) (justSpawned.position.x / gridCellSize);
            int playerGridY = (int) (justSpawned.position.y / gridCellSize);

            // 只遍历该玩家周围的9个格子
            for (int x = -1; x <= 1; x++) {
                for (int y = -1; y <= 1; y++) {
                    int checkX = playerGridX + x;
                    int checkY = playerGridY + y;

                    // 确保格子坐标在有效范围内
                    if (checkX >= 0 && checkX < gridWidth && checkY >= 0 && checkY < gridHeight) {

                        // [核心优化] 只对格子内的少量单位进行检查
                        for (Player other : spatialGrid[checkX][checkY]) {
                            if (other == justSpawned || !other.isAlive()) {
                                continue;
                            }

                            double distance = justSpawned.position.distance(other.position);
                            double minDistance = Player.SIZE;

                            if (distance < minDistance) {
                                wasOverlapping = true;

                                double dx = justSpawned.position.x - other.position.x;
                                double dy = justSpawned.position.y - other.position.y;

                                if (distance == 0) {
                                    dx = (rand.nextDouble() - 0.5);
                                    dy = (rand.nextDouble() - 0.5);
                                }

                                double magnitude = Math.sqrt(dx * dx + dy * dy);
                                if (magnitude == 0)
                                    continue;

                                double pushX = dx / magnitude;
                                double pushY = dy / magnitude;
                                double overlap = minDistance - distance;

                                justSpawned.position.x += pushX * (overlap + 0.1); // 加上一点边距
                                justSpawned.position.y += pushY * (overlap + 0.1);

                                justSpawned.vx += pushX * pushImpulse;
                                justSpawned.vy += pushY * pushImpulse;
                            }
                        }
                    }
                }
            }

            // 如果在一次完整的9宫格检查后没有发现任何重叠，就可以提前退出
            if (!wasOverlapping) {
                break;
            }
        }
    }

    // [旧版]创建并返回一个包含按类别组织的武器列表的Map。
    private static Map<String, List<String>> createWeaponCategories() {
        Map<String, List<String>> map = new LinkedHashMap<>();
        map.put("Pistols", Arrays.asList("GLOCK18", "USPS", "P250", "FIVESEVEN", "TEC9", "DEAGLE", "R8"));
        map.put("Shotguns", Arrays.asList("NOVA", "XM1014", "MAG7", "SAWEDOFF"));
        map.put("SMGs", Arrays.asList("MP9", "MAC10", "MP7", "UMP45", "P90", "BIZON"));
        map.put("Rifles", Arrays.asList("AK47", "M4A4", "M4A1S", "FAMAS", "GALIL", "AUG", "SG553"));
        map.put("Snipers", Arrays.asList("AWP", "SSG08"));
        map.put("Machine_Guns", Arrays.asList("NEGEV", "M249"));
        return Collections.unmodifiableMap(map); // 返回一个不可修改的Map，防止意外更改。
    }

    // 创建并返回一个包含所有武器UI数据的Map。
    private static Map<String, GameState.WeaponUIData> createWeaponData() {
        Map<String, GameState.WeaponUIData> map = new HashMap<>();

        // 解释：第三个参数 "T", "CT", "ANY" 是我们手动添加的阵营信息
        map.put("AK47", new GameState.WeaponUIData("AK-47", 2700, "T"));
        map.put("M4A4", new GameState.WeaponUIData("M4A4", 3100, "CT"));
        map.put("M4A1S", new GameState.WeaponUIData("M4A1-S", 2900, "CT"));
        map.put("FAMAS", new GameState.WeaponUIData("FAMAS", 2050, "CT"));
        map.put("GALIL", new GameState.WeaponUIData("Galil AR", 1800, "T"));
        map.put("AUG", new GameState.WeaponUIData("AUG", 3300, "CT"));
        map.put("SG553", new GameState.WeaponUIData("SG 553", 3000, "T"));
        map.put("AWP", new GameState.WeaponUIData("AWP", 4750, "ANY"));
        map.put("SSG08", new GameState.WeaponUIData("SSG 08", 1700, "ANY"));
        map.put("MP9", new GameState.WeaponUIData("MP9", 1250, "CT"));
        map.put("MAC10", new GameState.WeaponUIData("MAC-10", 1050, "T"));
        map.put("MP7", new GameState.WeaponUIData("MP7", 1500, "ANY"));
        map.put("UMP45", new GameState.WeaponUIData("UMP-45", 1200, "ANY"));
        map.put("P90", new GameState.WeaponUIData("P90", 2350, "ANY"));
        map.put("BIZON", new GameState.WeaponUIData("PP-Bizon", 1400, "ANY"));
        map.put("NOVA", new GameState.WeaponUIData("Nova", 1050, "ANY"));
        map.put("XM1014", new GameState.WeaponUIData("XM1014", 2000, "ANY"));
        map.put("MAG7", new GameState.WeaponUIData("MAG-7", 1300, "CT"));
        map.put("SAWEDOFF", new GameState.WeaponUIData("Sawed-Off", 1100, "T")); // Sawed-Off 是 T 专属
        map.put("NEGEV", new GameState.WeaponUIData("Negev", 1700, "ANY"));
        map.put("M249", new GameState.WeaponUIData("M249", 5200, "ANY"));
        map.put("GLOCK18", new GameState.WeaponUIData("Glock-18", 200, "T"));
        map.put("USPS", new GameState.WeaponUIData("USP-S", 200, "CT"));
        map.put("P250", new GameState.WeaponUIData("P250", 300, "ANY"));
        map.put("FIVESEVEN", new GameState.WeaponUIData("Five-SeveN", 500, "CT"));
        map.put("TEC9", new GameState.WeaponUIData("Tec-9", 500, "T"));
        map.put("DEAGLE", new GameState.WeaponUIData("Desert Eagle", 700, "ANY"));
        map.put("R8", new GameState.WeaponUIData("R8 Revolver", 600, "ANY"));

        return Collections.unmodifiableMap(map); // 返回不可修改的Map。
    }

    // [旧版]
    private static Map<String, GameState.ItemUIData> createItemData() {
        Map<String, ItemUIData> map = new HashMap<>();
        map.put("HE_GRENADE", new ItemUIData("高爆手雷", 300, 1, null));
        map.put("FLASHBANG", new ItemUIData("闪光弹", 200, 2, null));
        map.put("SMOKE_GRENADE", new ItemUIData("烟雾弹", 300, 1, null));
        map.put("MOLOTOV", new ItemUIData("燃烧瓶 (T)", 500, 1, "T"));
        map.put("INCENDIARY", new ItemUIData("燃烧弹 (CT)", 600, 1, "CT"));
        map.put("DECOY", new ItemUIData("诱饵弹", 50, 1, null));
        map.put("KEVLAR", new ItemUIData("防弹衣", 650, 1, null));
        map.put("KEVLAR_HELMET", new ItemUIData("防弹衣+头盔", 1000, 1, null));
        map.put("DEFUSE_KIT", new ItemUIData("拆弹器", 400, 1, "CT"));
        return Collections.unmodifiableMap(map); // 返回不可修改的Map。
    }

    /**
     * 手动添加一个AI玩家到指定队伍。
     * 此方法被服务器控制面板调用，用于在游戏运行时动态增加人机。
     *
     * @param team 要加入的队伍 (CT 或 T)。
     */
    public void manuallyAddAiPlayer(Player.Team team) {
        // 生成一个唯一的AI名称，避免与现有BOT重复
        String aiName = "BOT " + (players.stream().filter(p -> p.isAI).count() + 1 + zombies.size());

        // 创建AI玩家实例
        Player ai = new Player(UUID.randomUUID().toString(), aiName, getSpawnPoint(team), team, true, gameMode, this,
                aiDifficulty, logger);

        // 标记这个AI已经选择过队伍，防止被识别为未初始化的占位符玩家
        ai.hasChosenTeam = true;

        // 根据当前游戏模式为新加入的AI设置合适的武器并使其重生
        if (gameMode == GameMode.DEMOLITION) {
            // 爆破模式下，只设置初始手枪，由新回合逻辑统一处理
            ai.setWeapon(team == Player.Team.T ? Weapon.GLOCK18 : Weapon.USPS);
            ai.health = 100; // 设置满生命值
            ai.money = 800; // 给予初始金钱
        } else {
            // 其他模式（TDM, ZOMBIE_MODE），直接重生
            Weapon aiWeapon = getWeaponByName(ai.nextWeapon);
            ai.setWeapon(aiWeapon != null ? aiWeapon : Weapon.AK47, gameMode);
            ai.respawn(ai.position, gameMode);
        }

        // 将AI添加到玩家列表
        players.add(ai);

        // 解决可能因同时添加而产生的出生点重叠问题
        resolveSpawnOverlaps(ai);

        // 在服务器日志中记录此操作
        logger.accept("已手动将 " + aiName + " 添加到队伍 " + team);
    }

    /**
     * 执行文本命令的核心逻辑。
     *
     * @param command 完整的命令字符串。
     */
    public void executeCommand(String command) {
        // --- 使用正则表达式解析命令，正确处理带引号的参数 ---
        List<String> parts = new ArrayList<>();
        // 这个正则表达式会匹配被引号括起来的完整部分，或者不含空格的独立部分
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("([^\"]\\S*|\".+?\")\\s*").matcher(command);
        while (m.find()) {
            parts.add(m.group(1).replace("\"", "")); // 添加匹配项，并移除前后的引号
        }
        if (parts.isEmpty())
            return;

        String commandName = parts.get(0).toLowerCase();

        try {
            switch (commandName) {
                // 命令格式: vfx "<玩家名>" <特效名>
                /** 显示粒子效果 */
                case "vfx":
                    if (parts.size() >= 3) {
                        String targetPlayerName = parts.get(1); // 现在这里会是 "BOT 1"
                        String effectName = parts.get(2);

                        Player target = players.stream()
                                .filter(p -> p.name.equalsIgnoreCase(targetPlayerName))
                                .findFirst()
                                .orElse(null);

                        if (target != null && target.isAlive()) {
                            visualEffects.add(new VisualEffect(effectName, target.position, 1500));
                            logger.accept("在玩家 '" + target.name + "' 身上播放了特效: " + effectName);
                        } else {
                            logger.accept("命令错误: 未找到存活的玩家 '" + targetPlayerName + "'");
                        }
                    } else {
                        logger.accept("命令格式错误. 应为: vfx \"<玩家名>\" <特效名>");
                    }
                    break;

                /** 给钱 其实"管理玩家" 那边就能改了 */
                // 命令格式: money "<玩家名>" <金额>
                case "money":
                    if (parts.size() >= 3) {
                        String targetPlayerName = parts.get(1);
                        try {
                            int amount = Integer.parseInt(parts.get(2));

                            Player target = players.stream()
                                    .filter(p -> p.name.equalsIgnoreCase(targetPlayerName))
                                    .findFirst()
                                    .orElse(null);

                            if (target != null) {
                                target.money += amount;
                                if (target.money > 16000)
                                    target.money = 16000;
                                logger.accept("给予玩家 '" + target.name + "' " + amount + " 金钱。新余额: " + target.money);
                            } else {
                                logger.accept("命令错误: 未找到玩家 '" + targetPlayerName + "'");
                            }
                        } catch (NumberFormatException e) {
                            logger.accept("命令错误: 金额必须是一个数字。");
                        }
                    } else {
                        logger.accept("命令格式错误. 应为: money \"<玩家名>\" <金额>");
                    }
                    break;

                default:
                    logger.accept("未知命令: " + commandName);
                    break;
            }
        } catch (Exception e) {
            logger.accept("执行命令时出错: " + e.getMessage());
        }
    }

    /**
     * 根据指定队伍，移除列表中的第一个AI玩家。
     *
     * @param team 要移除AI的队伍。
     */
    public void removeBotByTeam(Player.Team team) {
        // 查找第一个是AI并且属于指定队伍的玩家
        Player botToRemove = players.stream()
                .filter(p -> p.isAI && p.team == team)
                .findFirst()
                .orElse(null);

        if (botToRemove != null) {
            removePlayer(botToRemove.id);
            logger.accept("移除了 " + team.name() + " 方的 AI: " + botToRemove.name);
        } else {
            logger.accept("游戏中没有 " + team.name() + " 方的 AI 可移除。");
        }
    }

    /**
     * 杀死所有AI玩家。
     */
    public void killAllBots() {
        int killCount = 0;
        for (Player p : players) {
            // 找到所有存活的AI
            if (p.isAI && p.isAlive()) {
                p.health = 0; // 直接将其生命值设为0
                // 或者可以调用 takeDamage 方法来模拟被击杀
                // p.takeDamage(new Player.DamageInfo(999999999, false));

                // 触发死亡后的逻辑
                handlePlayerKill(p, p, "Killed by Console"); // 自己杀自己，武器为控制台
                killCount++;
            }
        }
        logger.accept("通过命令杀死了 " + killCount + " 个 AI。");
    }

    /**
     * 新增：提供一个公共方法，让外部可以获取当前所有的火焰区域列表。
     *
     * @return 包含所有FirePatch对象的列表。
     */
    public List<FirePatch> getFirePatches() {
        return firePatches;
    }

    /**
     * 一个用于封装碰撞检测结果的内部记录类。
     * 包含了碰撞点、被撞边的法线向量，以及碰撞距离。
     */
    public record CollisionResult(Point2D.Double impactPoint, Point2D.Double normal, double distance) {
    }

    /**
     * [优化]连续碰撞检测的核心方法。
     * 检测一条射线与所有障碍物的交点，并返回最近的那一个碰撞的详细信息。
     *
     * @param ray       移动射线（从当前位置到下一帧位置）
     * @param obstacles 所有障碍物的列表
     * @return 如果发生碰撞，返回一个包含碰撞点、法线和距离的 CollisionResult；否则返回 null。
     */
    private CollisionResult findClosestCollision(Line2D.Double ray, List<Shape> obstacles) {
        CollisionResult closestCollision = null;
        double minDistanceSq = Double.MAX_VALUE;

        for (Shape obs : obstacles) {
            PathIterator pi = obs.getPathIterator(null);
            double[] coords = new double[6];
            Point2D.Double firstPoint = null, lastPoint = null;

            while (!pi.isDone()) {
                int type = pi.currentSegment(coords);
                Point2D.Double currentPoint = new Point2D.Double(coords[0], coords[1]);

                if (type == PathIterator.SEG_MOVETO) {
                    firstPoint = currentPoint;
                } else if (type == PathIterator.SEG_LINETO || type == PathIterator.SEG_CLOSE) {
                    Point2D.Double edgeStart = lastPoint;
                    Point2D.Double edgeEnd = (type == PathIterator.SEG_CLOSE) ? firstPoint : currentPoint;

                    if (edgeStart != null) {
                        Line2D.Double edge = new Line2D.Double(edgeStart, edgeEnd);
                        if (ray.intersectsLine(edge)) {
                            Point2D intersection = getLineLineIntersectionPoint(ray, edge);
                            if (intersection != null) {
                                double distSq = ray.getP1().distanceSq(intersection);
                                if (distSq < minDistanceSq) {
                                    minDistanceSq = distSq;
                                    // 计算法线：法线是与边垂直的向量
                                    double edgeDx = edgeEnd.x - edgeStart.x;
                                    double edgeDy = edgeEnd.y - edgeStart.y;
                                    Point2D.Double normal = new Point2D.Double(-edgeDy, edgeDx); // 垂直向量
                                    // 归一化法线
                                    double mag = normal.distance(0, 0);
                                    if (mag > 0) {
                                        normal.x /= mag;
                                        normal.y /= mag;
                                    }
                                    closestCollision = new CollisionResult(
                                            new Point2D.Double(intersection.getX(), intersection.getY()), normal,
                                            Math.sqrt(distSq));
                                }
                            }
                        }
                    }
                }
                lastPoint = currentPoint;
                pi.next();
            }
        }
        return closestCollision;
    }

    // [BUG修复] 用于定期检查实体边界的计时器
    private long lastBoundsCheckTime = 0;
    private static final long BOUNDS_CHECK_INTERVAL_MS = 5000; // 每5秒检查一次

    /**
     * 定期检查所有实体是否在地图边界内，并处死那些卡在障碍物内的实体。
     * 增加了 continue 逻辑，确保一个实体在一轮检查中只被处死一次。
     */
    private void periodicallyCheckAndCorrectBounds() {
        // 创建一个包含所有角色的临时列表进行迭代，防止并发修改问题
        List<Player> allCharacters = new ArrayList<>(this.players);
        allCharacters.addAll(this.zombies);

        for (Player p : allCharacters) {
            // [安全检查] 如果这个单位在本轮检查中已经被杀死了，就完全跳过
            if (!p.isAlive()) {
                continue;
            }

            // --- 检查1: 是否在地图边界外 ---
            if (p.position.x < 0 || p.position.x > width || p.position.y < 0 || p.position.y > height) {
                logger.accept(String.format("警告: 检测到实体 '%s' 在地图边界之外！位置: (%.1f, %.1f)。执行处死...", p.name, p.position.x,
                        p.position.y));

                // ====================== [修复-边界] ======================
                boolean wasAlive = p.isAlive();
                p.health = 0; // <-- 关键：先将生命值设为0
                if (wasAlive) {
                    handlePlayerKill(p, p, "OUT_OF_BOUNDS");
                }
                // ========================================================
                continue;
            }

            // --- 检查: 是否在障碍物内部 ---
            // 只有在没有被边界处死的单位才会执行到这里
            if (getContainingObstacle(p.position) != null) {
                logger.accept(String.format("警告: 检测到实体 '%s' 卡在障碍物内！位置: (%.1f, %.1f)。执行处死...", p.name, p.position.x,
                        p.position.y));

                boolean wasAlive = p.isAlive();
                p.health = 0; // <-- 关键：先将生命值设为0
                if (wasAlive) {
                    handlePlayerKill(p, p, "CRUSHED");
                }

            }
        }
    }

    /**
     * 将一个玩家转换为玩家控制的僵尸。
     * 此版本手动处理重生逻辑，以确保武器被移除且血量正确设置。
     *
     * @param player 要转换的玩家。
     */
    private void transformToPlayerZombie(Player player) {
        player.team = Team.ZOMBIE;

        // 移除所有人类的装备和武器
        player.primaryWeapon = null;
        player.secondaryWeapon = null;
        player.currentSlot = 3; // 切换到“刀”槽位，确保没有枪械被持有
        player.equipment.clear();
        player.hasKevlar = false;
        player.hasHelmet = false;
        player.armorValue = 0;
        player.currentAmmo = 0;
        player.reserveAmmo = 0;
        player.primary_currentAmmo = 0;
        player.primary_reserveAmmo = 0;
        player.secondary_currentAmmo = 0;
        player.secondary_reserveAmmo = 0;

        // 根据当前波数计算并设置血量
        player.maxHealth = 50 + currentWave * 15; // 设置最大生命值
        /**
         * 这就是僵尸玩家的血量
         * 30 - 200 波数的特殊波数的僵尸
         */
        if (currentWave > 20 && currentWave % 10 == 0 && currentWave <= 200) {
            player.maxHealth = 100 * (50 + currentWave * 15);
        }

        // 将当前生命值设为最大值
        player.health = player.maxHealth;

        // 手动处理重生，而不是调用 player.respawn()
        player.position = getZombieSpawnPoint(); // 获取一个适合僵尸的重生点
        player.isInvincible = true; // 给予短暂的无敌保护
        player.respawnTime = System.currentTimeMillis();
        player.isReloading = false;

        // 解决可能出现的重生点重叠问题
        resolveSpawnOverlaps(player);

        // logger.accept(player.name + " 已经变成了僵尸!");
    }

    /**
     * 检查一个点是否在某个障碍物内部，并返回该障碍物。
     *
     * @param point 要检查的点。
     * @return 如果点在障碍物内，则返回该障碍物的 Shape 对象；否则返回 null。
     */
    private Shape getContainingObstacle(Point2D.Double point) {
        for (Shape obstacle : obstacles) {
            // 使用 Shape.contains() 方法进行精确判断
            if (obstacle.contains(point)) {
                return obstacle;
            }
        }
        return null; // 如果不在任何障碍物内
    }

    private boolean isAiFrozen = false; // <--- 在这里或附近添加这一行，用于标记AI是否被冻结

    public boolean getIsAiFrozen() {
        return isAiFrozen;
    }

    /**
     * 切换AI的冻结/恢复状态。
     * 当此方法被调用时，会翻转 isAiFrozen 标志。
     */
    public void toggleAiFreeze() {
        this.isAiFrozen = !this.isAiFrozen;
    }

    /**
     * 检查AI当前是否处于冻结状态。
     *
     * @return 如果AI被冻结，则返回 true，否则返回 false。
     */
    public boolean isAiFrozen() {
        return this.isAiFrozen;
    }

    static boolean canPlayerPickupDroppedWeapon(DroppedItem item, String playerId, long nowMillis) {
        if (item == null || item.isBomb || item.weapon == null)
            return false;
        if (item.dropperPlayerId == null || !item.dropperPlayerId.equals(playerId))
            return true;
        return nowMillis - item.dropTime >= SELF_DROP_PICKUP_COOLDOWN_MS;
    }

    /** AI 在手动冻结、回合结束或整场比赛结束时都不得继续产生新动作。 */
    public boolean shouldFreezeAi() {
        return shouldFreezeAiState(this.isAiFrozen, this.isGameOver, this.roundPhase);
    }

    static boolean shouldFreezeAiState(boolean manuallyFrozen, boolean gameOver, RoundPhase phase) {
        return manuallyFrozen || gameOver || phase == RoundPhase.ROUND_OVER;
    }

    // ================== [v2.5] AI线程池和决策结果类 ==================
    /**
     * 一个线程池，我们将把所有AI的决策任务都提交给它来并行处理。
     * 这里我们创建了10个线程，与您的提议一致。
     */
    private final ExecutorService aiThreadPool = Executors.newFixedThreadPool(10);

    /**
     * 一个轻量级的数据结构，用于封装单个AI在一帧内的决策结果。
     * AI线程将计算并返回这个对象，而不是直接修改Player对象。
     */
    private record AI_Action(String aiId, double newAx, double newAy, double newAngle, boolean shouldShoot) {
    }

    /**
     * 关闭 GameState 持有的资源，如线程池。
     */
    public void shutdown() {
        aiThreadPool.shutdownNow(); // 立即停止所有正在执行的AI任务
    }

    /**
     * 一个线程安全的队列，用于存放AI线程计算完成的决策结果。
     * AI线程是生产者，主线程是消费者。
     */
    private final ConcurrentLinkedQueue<AI_Action> completedAiActions = new ConcurrentLinkedQueue<>();

    /**
     * [优化] 高性能数学方法：计算一个点到一条线段的最近点。
     * 使用向量投影算法，避免平方根计算，时间复杂度O(1)
     *
     * @param p 点
     * @param a 线段端点1
     * @param b 线段端点2
     * @return 线段上距离点p最近的点
     */
    private Point2D.Double getClosestPointOnSegment(Point2D.Double p, Point2D.Double a, Point2D.Double b) {
        // 计算线段AB的向量
        double abx = b.x - a.x;
        double aby = b.y - a.y;

        // 计算点A到点P的向量
        double apx = p.x - a.x;
        double apy = p.y - a.y;

        // 计算线段长度的平方（避免开方，性能优化）
        double ab_squared = abx * abx + aby * aby;

        // 处理线段退化为点的情况
        if (ab_squared == 0) {
            return a;
        }

        // 核心优化：使用向量点积计算投影参数t
        // t = (AP · AB) / |AB|²，表示P在线段AB上的投影位置比例
        double t = (apx * abx + apy * aby) / ab_squared;

        // 将t限制在[0, 1]范围内，确保结果在线段上
        t = Math.max(0, Math.min(1, t));

        // 根据投影参数计算最近点坐标
        return new Point2D.Double(a.x + t * abx, a.y + t * aby);
    }

    /**
     * [优化] 高性能检测：一个圆形是否与一个多边形障碍物相交。
     * 采用三级检测策略：粗略包围盒 → 内部检测 → 边碰撞检测
     *
     * @param obstacle     多边形障碍物
     * @param circleCenter 圆心
     * @param radius       半径
     * @return 如果相交则返回true
     */
    private boolean isCircleIntersectingPolygon(Shape obstacle, Point2D.Double circleCenter, double radius) {
        // === 第一级优化：快速粗略检测（排除95%的不相交情况）===
        // 检查圆形包围盒与多边形外接矩形是否相交
        // 这是最快速但最不精确的检测，用于早期排除
        if (!obstacle.getBounds2D().intersects(circleCenter.x - radius, circleCenter.y - radius, radius * 2,
                radius * 2)) {
            return false; // 外接矩形不相交，绝对不可能碰撞
        }

        // === 第二级优化：内部检测 ===
        // 如果圆心在多边形内部，则必然相交（包含关系）
        // 这个检查成本中等，但能快速确定包含情况
        if (obstacle.contains(circleCenter)) {
            return true;
        }

        // === 第三级优化：精确的边-圆碰撞检测 ===
        // 遍历多边形的每条边，检查圆是否与边相交
        PathIterator pi = obstacle.getPathIterator(null);
        double[] coords = new double[6];
        Point2D.Double first = null, last = null;
        double radiusSq = radius * radius; // 使用距离平方避免开方运算

        while (!pi.isDone()) {
            int type = pi.currentSegment(coords);
            Point2D.Double current = new Point2D.Double(coords[0], coords[1]);

            if (type == PathIterator.SEG_MOVETO) {
                first = current; // 记录多边形起点
            } else if (type == PathIterator.SEG_LINETO) {
                // 检测圆与当前线段的碰撞
                Point2D.Double closestPoint = getClosestPointOnSegment(circleCenter, last, current);
                if (closestPoint.distanceSq(circleCenter) < radiusSq) {
                    return true; // 找到碰撞，立即返回
                }
            } else if (type == PathIterator.SEG_CLOSE) {
                // 检测最后一条边（连接首尾）
                if (first != null && last != null) {
                    Point2D.Double closestPoint = getClosestPointOnSegment(circleCenter, last, first);
                    if (closestPoint.distanceSq(circleCenter) < radiusSq) {
                        return true;
                    }
                }
            }
            last = current;
            pi.next();
        }
        return false;
    }

    // 用于在物理循环开始前，计算速度和处理行为/交互
    private void updatePlayerBehaviorAndPhysicsPrep(Player p) {

        // [!! 核心修复 1 !!] 检查玩家的 *意图* (keysDown) 而不是 *状态* (isMoving)
        if (p.isInvincible) {
            long protectionDuration = (gameMode == GameMode.DEATHMATCH) ? DEATHMATCH_SPAWN_PROTECTION_MS
                    : SPAWN_PROTECTION_MS;

            // 检查1: 是否超时
            if (System.currentTimeMillis() - p.respawnTime > protectionDuration) {
                p.isInvincible = false;

                // 检查2: (仅死斗模式)
            } else if (gameMode == GameMode.DEATHMATCH) {
                // 检查玩家是否按下了移动键 (W, A, S, D)
                boolean hasMoveInput = p.keysDown.contains("W") || p.keysDown.contains("A") || p.keysDown.contains("S")
                        || p.keysDown.contains("D");

                // 只有当玩家 *主动* 按键移动或射击时，才解除无敌
                if (hasMoveInput || p.isShooting) {
                    p.isInvincible = false;
                    logger.accept("玩家 " + p.name + " 因移动或开火，无敌状态已移除。");
                }
            }
        }

        if (p.isReloading && System.currentTimeMillis() - p.reloadStartTime >= p.currentReloadTime) {
            p.finishReload(gameMode);
        }

        if (p.isAlive()) {

            // 如果玩家正在交互，则将他变为“雕像”，并处理交互进度
            if (p.isInteracting) {
                // 确保清除所有速度和加速度，防止任何形式的移动
                p.ax = 0;
                p.ay = 0;
                p.vx = 0;
                p.vy = 0;
                p.isMoving = false; // 明确设置isMoving为false

                // 直接在这里处理交互进度，然后跳过后续的物理计算
                handleInteractionProgress(p);
                // 注意：下面的代码块不会被执行
            } else {
                // 如果没有在交互，才执行正常的物理和行为更新
                if (gameMode == GameMode.DEMOLITION && roundPhase == RoundPhase.FREEZE_TIME) {
                    p.vx *= FRICTION;
                    p.vy *= FRICTION;
                    p.position.x += p.vx;
                    p.position.y += p.vy;
                } else {
                    updatePlayerPhysics(p);
                }

                if ((p.isShooting || p.isRequestingUnderhandThrow)) { // isInteracting 检查已在外部完成
                    requestShoot(p);
                }
            }

            // 处理物品拾取
            long pickupCheckTime = System.currentTimeMillis();
            for (DroppedItem item : new CopyOnWriteArrayList<>(droppedItems)) {
                if (p.position.distance(item.position) < Player.SIZE) {
                    if (item.isBomb && pickupCheckTime - item.dropTime > 500
                            && p.team == Player.Team.T && !p.hasBomb) {
                        p.hasBomb = true;
                        droppedItems.remove(item);
                        logger.accept(p.name + " picked up the C4");
                        break;
                    } else if (!item.isBomb && item.weapon != null && !item.weapon.getWeaponType().isPistol()
                            && p.primaryWeapon == null
                            && canPlayerPickupDroppedWeapon(item, p.id, pickupCheckTime)) {
                        playerPickupDroppedWeapon(p.id, item.id.toString());
                        break;
                    }
                }
            }
        }
        // --- 死亡和重生逻辑 ---
        else if (gameMode == GameMode.ZOMBIE_MODE && p.team == Player.Team.ZOMBIE) {
            if (p.respawnTime > 0 && System.currentTimeMillis() - p.respawnTime > PLAYER_ZOMBIE_RESPAWN_MS) {
                if (zombiesToSpawn > 0) {
                    transformToPlayerZombie(p);
                    zombiesToSpawn--; // 消耗一个僵尸复活名额
                }
            }
        } else if (p.team != Player.Team.ZOMBIE && gameMode == GameMode.TEAM_DEATHMATCH) {
            if (p.respawnTime > 0 && System.currentTimeMillis() - p.respawnTime > 3000) {
                if (p.isControllingBot()) {
                    logger.accept("玩家 " + p.name + " 即将复活，自动释放BOT控制。");
                    playerReleaseBot(p);
                }
                p.respawn(getSpawnPoint(p.team), gameMode);
                p.hasKevlar = true;
                p.hasHelmet = true;
                p.armorValue = 100;
                giveRandomEquipment(p);
                resolveSpawnOverlaps(p);

            }

            // [!! 核心修复 2 !!] 修改死斗模式的重生逻辑
        } else if (gameMode == GameMode.DEATHMATCH && p.team != Player.Team.ZOMBIE) {
            // 移除3000ms延迟，实现立即重生 (在下一帧)
            if (p.respawnTime > 0) {
                if (p.isControllingBot()) {
                    logger.accept("玩家 " + p.name + " 即将复活，自动释放BOT控制。");
                    playerReleaseBot(p);
                }
                // [修改] 重生时使用 *组合* 的出生点，并传入正确的GameMode
                p.respawn(getSpawnPoint(p.team), gameMode);
                p.hasKevlar = true;
                p.hasHelmet = true;
                p.armorValue = 100;

                giveRandomEquipment(p);
                resolveSpawnOverlaps(p); // 解决出生时的重叠问题
            }
        }

        // 如果玩家已死亡，且没有夺舍其他BOT (无论是DEMO还是ZOMBIE模式)
        else if (!p.isAlive() && !p.isControllingBot()
                && (gameMode == GameMode.DEMOLITION || gameMode == GameMode.ZOMBIE_MODE)) {
            // 优先寻找一个存活的同队队友
            Player targetToSpectate = players.stream()
                    .filter(teammate -> teammate.isAlive() && teammate.team == p.team && !teammate.id.equals(p.id))
                    .findFirst()
                    .orElse(null);

            if (targetToSpectate != null) {
                // 如果找到了队友，就设置为“团队观战”模式
                p.spectatorTargetId = targetToSpectate.id;
                p.spectatorMode = "TEAM_SPECTATE";
            } else {
                // 如果找不到任何存活的队友，则直接进入“自由漫游”模式 (全局视野)
                p.spectatorTargetId = null;
                p.spectatorMode = "FREE_ROAM";
            }
        }
        // --- 武器状态更新 ---
        if (p.primaryWeapon != null || p.secondaryWeapon != null) {
            p.updateSpread(BASE_SPEED);
            p.updateSpreadAndRecoil(BASE_SPEED);
        }

        // 在所有逻辑处理完毕后，记录本帧的射击状态，供下一帧使用
        p.wasShootingLastFrame = p.isShooting;
    }

    /**
     * [碰撞解决器] [优化]
     * 专职负责解决玩家-墙壁和玩家-玩家的重叠和穿透。
     * * 优化点:
     * 使用 Quadtree 快速检索附近的障碍物，避免遍历所有障碍物。
     * 玩家-玩家碰撞部分已依赖于空间网格 (在 update() 中填充)。
     */
    public void resolveCollisionsAndSliding(Player player) {
        int collisionPasses = 5; // 迭代 5 次以确保稳定
        double min_separation = 0.1; // 最小安全推离距离
        double radius = Player.SIZE / 2.0;
        double totalRadius = Player.SIZE; // 两个半径之和

        // [优化 A] 创建一个代表玩家“感兴趣区域”的边界框
        // 这个区域比玩家大一点，以捕捉到即将发生的碰撞
        double queryRadius = radius * 2; // 查询半径
        Rectangle2D.Double playerQueryBounds = new Rectangle2D.Double(
                player.position.x - queryRadius,
                player.position.y - queryRadius,
                queryRadius * 2,
                queryRadius * 2);

        // [优化 B] 从 Quadtree 中仅检索附近的障碍物
        // 这是关键优化！我们不再遍历所有障碍物。
        List<MapData.ShapeWrapper> nearbyObstacleWrappers = new ArrayList<>();
        if (quadtreeRootNode != null) {
            // retrieve 方法会填充 nearbyObstacleWrappers 列表
            quadtreeRootNode.queryBounds(nearbyObstacleWrappers, playerQueryBounds);
        }

        for (int i = 0; i < collisionPasses; i++) {

            // --- 玩家-障碍物碰撞 (圆形-多边形) ---

            // [优化 C] 遍历从 Quadtree 检索到的 *附近* 障碍物 (通常只有 2-5 个，而不是全部 100+ 个)
            for (MapData.ShapeWrapper wrapper : nearbyObstacleWrappers) {

                // [优化 D] 动态地将 ShapeWrapper 转换回 Shape
                // 这一步有一定开销，但只对少数几个物体进行，而不是对所有物体
                Shape obstacle = convertWrapperToShape(wrapper);
                if (obstacle == null)
                    continue;

                // (内部的碰撞解决数学逻辑保持不变)
                if (isCircleIntersectingPolygon(obstacle, player.position, radius)) {
                    Point2D.Double closestPoint = findClosestPointOnShape(player.position, obstacle);
                    if (closestPoint == null)
                        continue;

                    double nx = player.position.x - closestPoint.x;
                    double ny = player.position.y - closestPoint.y;
                    double dist = player.position.distance(closestPoint);

                    if (dist < radius) {
                        double penetrationDepth = radius - dist;
                        if (dist == 0) {
                            double randomAngle = rand.nextDouble() * 2 * Math.PI;
                            nx = Math.cos(randomAngle);
                            ny = Math.sin(randomAngle);
                        } else {
                            nx /= dist;
                            ny /= dist;
                        }

                        // 强力推开：穿透深度 + 安全边距，并乘以乘数
                        double pushDistance = (penetrationDepth + min_separation) * PENETRATION_MULTIPLIER;

                        player.position.x += nx * pushDistance;
                        player.position.y += ny * pushDistance;

                        // 速度投影实现滑动
                        double dot = player.vx * nx + player.vy * ny;
                        if (dot < 0) {
                            player.vx -= dot * nx;
                            player.vy -= dot * ny;
                        }
                    }
                }
            } // [优化 C] 附近障碍物循环结束

            // --- 玩家-玩家碰撞 (圆形-圆形，使用空间网格加速) ---
            // (这部分代码是正确的，并且已经很快了，保持不变)
            int playerGridX = (int) (player.position.x / gridCellSize);
            int playerGridY = (int) (player.position.y / gridCellSize);

            for (int x = -1; x <= 1; x++) {
                for (int y = -1; y <= 1; y++) {
                    int checkX = playerGridX + x;
                    int checkY = playerGridY + y;

                    if (checkX >= 0 && checkX < gridWidth && checkY >= 0 && checkY < gridHeight) {
                        for (Player other : spatialGrid[checkX][checkY]) {
                            // 确保只处理一次：player.id 必须小于 other.id
                            if (player == other || player.id.compareTo(other.id) >= 0)
                                continue;
                            if (!other.isAlive())
                                continue; // [额外检查] 只和活体碰撞

                            double distSq = player.position.distanceSq(other.position);

                            if (distSq < totalRadius * totalRadius && distSq > 0) {
                                double dist = Math.sqrt(distSq);
                                double penetrationDepth = totalRadius - dist;

                                // 各自推开距离 = (穿透深度 + 安全边距) / 2
                                double overlap = (penetrationDepth + min_separation) / 2.0;

                                double nx = (player.position.x - other.position.x) / dist;
                                double ny = (player.position.y - other.position.y) / dist;

                                // 位置修正：推开玩家
                                player.position.x += nx * overlap;
                                player.position.y += ny * overlap;
                                other.position.x -= nx * overlap;
                                other.position.y -= ny * overlap;

                                // 动量交换
                                double p1_dot = player.vx * nx + player.vy * ny;
                                double p2_dot = other.vx * nx + other.vy * ny;

                                player.vx += (p2_dot - p1_dot) * nx;
                                player.vy += (p2_dot - p1_dot) * ny;
                                other.vx += (p1_dot - p2_dot) * nx;
                                other.vy += (p1_dot - p2_dot) * ny;
                            }
                        }
                    }
                }
            }
        }
    }

    /**
     * 获取当前所有需要被同步的角色总数 (玩家 + 僵尸)。
     *
     * @return 角色总数。
     */
    public int getCharacterCount() {
        // players 列表包含了所有人类玩家和AI玩家
        // zombies 列表包含了所有僵尸
        return players.size() + zombies.size();
    }

    public void playerRequestControlBot(String humanPlayerId, JsonObject data) {
        Player humanPlayer = getPlayerById(humanPlayerId);
        if (humanPlayer == null)
            return;

        // 防抖
        if (System.currentTimeMillis() - humanPlayer.lastControlRequestTime < 500) {
            return;
        }
        humanPlayer.lastControlRequestTime = System.currentTimeMillis();

        // --- 释放控制 ---
        // 这个判断依然是解除控制的核心
        // if (humanPlayer.isControllingBot()) {
        // logger.accept("[CONTROL_BOT] 玩家 " + humanPlayer.name + " 正在释放控制。");
        // playerReleaseBot(humanPlayer);
        // return;
        // }

        // --- 夺舍 ---
        if (humanPlayer.isAlive()) {
            return; // 活人不能夺舍
        }

        // 从客户端发来的消息中获取目标ID
        String targetId = data.has("targetId") && !data.get("targetId").isJsonNull()
                ? data.get("targetId").getAsString()
                : null;

        Player botToControl = null;

        // 优先策略：夺舍客户端明确指定的目标
        if (targetId != null) {
            logger.accept("[CONTROL_BOT] 客户端请求夺舍指定目标: " + targetId);
            Player spectatedPlayer = getPlayerById(targetId);
            // 检查观战目标是否是一个可以被夺舍的BOT
            if (spectatedPlayer != null && spectatedPlayer.isAI && spectatedPlayer.isAlive()
                    && !spectatedPlayer.isControlledByPlayer() && spectatedPlayer.health > 50) {
                botToControl = spectatedPlayer;
            }
        }

        // 后备策略：如果客户端没提供目标，或目标无效，则使用旧的“寻找最近”逻辑
        if (botToControl == null) {
            logger.accept("[CONTROL_BOT] 客户端未指定有效目标，执行后备策略：寻找最近的BOT。");
            botToControl = players.stream()
                    .filter(p -> p.isAI && p.team == humanPlayer.team && p.isAlive() && !p.isControlledByPlayer()
                            && p.health > 50)
                    .min(Comparator.comparingDouble(p -> p.position.distance(humanPlayer.position)))
                    .orElse(null);
        }

        // 如果找到了BOT
        if (botToControl != null) {
            logger.accept("玩家 " + humanPlayer.name + " 成功夺舍 BOT " + botToControl.name);
            humanPlayer.controllingBotId = botToControl.id;
            botToControl.controlledByPlayerId = humanPlayer.id;

            humanPlayer.spectatorMode = "CONTROLLING_BOT";
            humanPlayer.spectatorTargetId = botToControl.id;
        } else {
            logger.accept("[CONTROL_BOT] " + humanPlayer.name + " 夺舍失败，没有可用的BOT。");
        }
    }

    /**
     * 检查一个世界坐标点是否位于禁止复活区网格内。
     * 
     * @param point 玩家的世界坐标
     * @return true 如果该点在禁区内
     */
    private boolean isPointInForbiddenZone(Point2D.Double point) {
        if (this.forbiddenSpawnGridCells.isEmpty()) {
            return false; // 如果没有禁区，快速返回
        }
        int gridX = (int) (point.x / BFS_GRID_CELL_SIZE);
        int gridY = (int) (point.y / BFS_GRID_CELL_SIZE);
        return this.forbiddenSpawnGridCells.contains(new Point(gridX, gridY));
    }

    public boolean isPointInGeneralForbiddenZone(Point2D.Double point) {
        if (this.generalForbiddenGridCells.isEmpty()) {
            return false;
        }
        // GENERAL_ZONE_GRID_SIZE is 10 as defined in MapEditor
        int gridX = (int) (point.x / 10);
        int gridY = (int) (point.y / 10);
        return this.generalForbiddenGridCells.contains(new Point(gridX, gridY));
    }

    /**
     * [BUG修复] 释放对BOT的控制
     * 这个函数现在是所有释放场景（手动按E、BOT死亡、玩家复活）的唯一入口点，确保逻辑统一。
     *
     * @param humanPlayer 正在控制BOT的玩家。
     */
    private void playerReleaseBot(Player humanPlayer) {
        // 安全检查：如果玩家为空或并未在控制BOT，则直接返回
        if (humanPlayer == null || !humanPlayer.isControllingBot()) {
            return;
        }

        String botId = humanPlayer.controllingBotId;
        Player controlledBot = getPlayerById(botId);

        logger.accept("玩家 " + humanPlayer.name + " 正在释放对 BOT " + (controlledBot != null ? controlledBot.name : botId)
                + " 的控制。");

        // 恢复BOT的状态
        if (controlledBot != null) {
            controlledBot.controlledByPlayerId = null; // 标记BOT为自由状态

            // 立即清除BOT的移动输入，防止它因为玩家最后的按键而“漂移”
            controlledBot.keysDown.clear();
            controlledBot.ax = 0;
            controlledBot.ay = 0;
            controlledBot.vx = 0; // 新增：清除速度
            controlledBot.vy = 0; // 新增：清除速度
            controlledBot.isShooting = false;
            controlledBot.isRequestingUnderhandThrow = false; // 新增：清除低抛请求

        }

        // 彻底重置玩家的状态
        humanPlayer.controllingBotId = null; // 清除玩家的控制目标ID，这是解除控制的核心
        humanPlayer.spectatorMode = "TEAM_SPECTATE"; // 将玩家的模式明确切换回“团队观战”
        humanPlayer.spectatorTargetId = null; // 清空观战目标，让主循环逻辑自动寻找下一个可观战的队友

        logger.accept("玩家 " + humanPlayer.name + " 已成功释放控制，返回观战模式。");
    }

    /**
     * 计算爆破模式当前回合剩余时间（毫秒）。
     * 此方法主要供 AIController.java 调用。
     *
     * @return 剩余时间（毫秒），如果回合已结束或不是 IN_PROGRESS 状态，则返回 0。
     */
    public long getRoundTimeRemaining() {
        if (gameMode != GameMode.DEMOLITION)
            return 0;

        long currentTime = System.currentTimeMillis();

        if (roundPhase == RoundPhase.FREEZE_TIME) {
            return Math.max(0, DEMO_FREEZE_TIME_MS - (currentTime - roundStartTime));
        } else if (roundPhase == RoundPhase.IN_PROGRESS) {
            if (bombPlanted) {
                // 如果炸弹已安放，返回炸弹爆炸剩余时间
                return Math.max(0, DEMO_BOMB_TIME_MS - (currentTime - bombPlantTime));
            } else {
                // 如果炸弹未安放，返回回合时间剩余
                long totalRoundTime = DEMO_ROUND_TIME_MS + DEMO_FREEZE_TIME_MS;
                return Math.max(0, totalRoundTime - (currentTime - roundStartTime));
            }
        }
        return 0;
    }

    /**
     * 获取一个包含所有角色（玩家和僵尸）的完整列表。
     *
     * @return 包含所有角色的新列表。
     */
    public List<Player> getAllCharacters() {
        List<Player> all = new ArrayList<>(players);
        all.addAll(zombies);
        return all;
    }

    /**
     * 检查粗算图是否已成功烘焙并可用。
     * 
     * @return 如果 waypointGraph 不是 null 且不为空，则返回 true。
     */
    public boolean hasWaypointGraph() {
        return this.waypointGraph != null && !this.waypointGraph.isEmpty();
    }

    /**
     * 获取烘焙好的粗算图。
     * 
     * @return 包含 Waypoint 对象的列表 (图)，如果未烘焙则返回 null。
     */
    public List<WaypointNode> getWaypointGraph() {
        return this.waypointGraph;
    }

    /**
     * 不再计算，而是从 MapData 的预烘焙数据中构建运行时的图。
     * 这个过程非常快 (O(N+K))。
     */
    private void buildRuntimeWaypointGraph(List<MapData.SerializableWaypoint> sWaypoints) {
        int waypointCount = sWaypoints.size();
        this.waypointGraph = new ArrayList<>(waypointCount);
        Map<Integer, WaypointNode> nodeMap = new HashMap<>(); // 使用 WaypointNode

        // 1. 创建 WaypointNode 实例
        for (int i = 0; i < waypointCount; i++) {
            MapData.SerializableWaypoint sWay = sWaypoints.get(i); // 获取原始数据

            // --- VVVV 核心修复 VVVV ---
            // 检查 sWay 或 sWay.position 是否为 null
            if (sWay == null || sWay.position == null) {
                logger.accept("警告: 忽略了一个索引为 " + i + " 的无效或位置为空的路径点。");
                continue; // 跳过这个损坏的点
            }
            // --- ^^^^ 修复结束 ^^^^ ---

            Point2D.Double pos = sWay.position; // <-- 现在 pos 绝对不是 null

            // if (pos.x >= 0 && ...) // <-- 这是出错的行 (5200行)
            if (pos.x >= 0 && pos.x < this.width && pos.y >= 0 && pos.y < this.height) {
                WaypointNode node = new WaypointNode(i, pos); // 使用 WaypointNode
                this.waypointGraph.add(node);
                nodeMap.put(i, node);
            } else {
                logger.accept("警告: 忽略了一个在地图边界外的路径点坐标: (" + pos.x + ", " + pos.y + ")");
            }
        }

        // 2. 重新连接邻居 (这段代码现在是安全的)
        int totalConnections = 0;
        for (WaypointNode currentNode : this.waypointGraph) { // 遍历新建的 WaypointNode
            int i = currentNode.index;
            MapData.SerializableWaypoint sNode = sWaypoints.get(i);

            // 再次检查 sNode，虽然理论上 nodeMap 不会包含无效索引
            if (sNode == null || sNode.neighborIndices == null || sNode.neighborCosts == null) {
                continue;
            }

            for (int k = 0; k < sNode.neighborIndices.size(); k++) {
                int neighborIndex = sNode.neighborIndices.get(k);
                double cost = sNode.neighborCosts.get(k);

                WaypointNode neighborNode = nodeMap.get(neighborIndex); // 从 Map 中查找

                if (neighborNode != null) { // 确保邻居节点也是有效的
                    currentNode.neighbors.add(neighborNode);
                    currentNode.costs.add(cost);
                    totalConnections++;
                }
            }
        }

        logger.accept("路径点运行时图构建完成。 " + this.waypointGraph.size() + " 个节点, " + (totalConnections / 2) + " 条唯一连接。");
    }

    /**
     * 获取障碍物列表 (供烘焙和 AIController 使用)
     * 
     * @return 包含 Shape 对象的列表
     */
    public List<Shape> getObstacles() {
        return this.obstacles;
    }

    /**
     * 获取当前帧累积的所有公共声音事件。
     * AIService 会读取此列表。注意：此方法不清除列表。
     * 
     * @return 包含 SoundEvent 的线程安全列表。
     */
    public List<SoundEvent> getSoundEvents() {
        return soundEvents; // 直接返回引用 (因为是 CopyOnWriteArrayList，迭代是安全的)
    }

    // 你可能还需要一个方法来清除声音事件，由 AIService 在处理完后调用，或者在 GameState.update() 结束时调用
    public void clearSoundEvents() {
        soundEvents.clear();
    }

    // ======================AI======================
    /**
     * 命令指定的AI移动到目标坐标点。
     * 这个方法通常由服务器控制台命令或游戏逻辑触发。
     *
     * @param aiPlayerId 要命令移动的AI玩家的唯一ID。
     * @param targetX    目标位置的X坐标。
     * @param targetY    目标位置的Y坐标。
     */
    public void commandAiMoveTo(String aiPlayerId, double targetX, double targetY) {
        Player aiPlayer = getPlayerById(aiPlayerId);

        // (检查 aiPlayer 是否有效且为 AI 的逻辑不变)
        if (aiPlayer != null && aiPlayer.isAI && aiPlayer.isAlive()) { // 或者 aiPlayer.isAI()
            PathfindingModule pathModule = aiPlayer.getPathfindingModule();

            if (pathModule != null) {
                Point2D.Double targetPosition = new Point2D.Double(targetX, targetY);
                logger.accept("命令 AI [" + aiPlayer.name + "] 移动到 (" + targetX + ", " + targetY + ")");

                // 调用正确的 setTarget 方法
                pathModule.setTarget(targetPosition);
                // pathModule.setIntentionalMissionTarget(targetPosition); // <--- 删除或注释掉这行错误的调用

            } else {
                logger.accept("错误：AI [" + aiPlayer.name + "] 没有寻路模块！无法命令移动。");
            }
        } else {
            // 如果找不到玩家，或者找到的不是AI，或者AI已死亡
            if (aiPlayer == null) {
                logger.accept("错误：找不到 ID 为 '" + aiPlayerId + "' 的玩家。");
            } else if (!aiPlayer.isAI()) { // Use isAI() method if it exists, otherwise use isAI field
                logger.accept("错误：玩家 '" + aiPlayer.name + "' 不是 AI。");
            } else { // Must be dead
                logger.accept("错误：AI '" + aiPlayer.name + "' 已死亡，无法命令移动。");
            }
        }
    }
    // ======================AIend======================

    /**
     * [优化] 处理脚步声、换弹声、开火声和位置暴露的核心方法。
     * 使用空间网格加速，并能同时通知人类玩家和AI。
     */
    // private void handleFootstepSounds() {
    // long currentTime = System.currentTimeMillis();
    //
    //
    // // 遍历所有角色
    // for (Player p : getAllCharacters()) {
    // // 条件：正在移动、没有静步、且已超过脚步声间隔
    // if (p.isMoving && !p.isWalking && currentTime - p.lastFootstepTime >
    // FOOTSTEP_INTERVAL_MS) {
    //
    // p.lastFootstepTime = currentTime; // 更新发声时间
    //
    // // --- 发出公共声音事件给 AI 感知 ---
    // // 调用 addSoundEvent 方法
    // // 类型设置为 FOOTSTEP (确保你已在 SoundType 枚举中添加了它)
    // // 声音名称可以根据需要设置 (例如，区分不同地面材质)
    // // 位置是发出声音的玩家 p 的位置
    // // 来源 ID 是发出声音的玩家 p 的 ID
    // addSoundEvent(SoundEvent.SoundType.FOOTSTEP,
    // "footstep_generic", // 或者更具体的声音名, e.g., "footstep_concrete"
    // p.position.x,
    // p.position.y,
    // p.id); // <--- 传入发出声音的玩家 ID
    // // --- 使用空间网格进行高效的目标筛选 ---
    // int pGridX = (int) (p.position.x / gridCellSize);
    // int pGridY = (int) (p.position.y / gridCellSize);
    //
    // // 遍历发出声音者周围的 3x3 网格
    // for (int x = -3; x <= 3; x++) {
    // for (int y = -3; y <= 3; y++) {
    // int checkX = pGridX + x;
    // int checkY = pGridY + y;
    //
    // // 确保格子坐标在有效范围内
    // if (checkX >= 0 && checkX < gridWidth && checkY >= 0 && checkY < gridHeight)
    // {
    // // 只需检查这个小格子里的少数几个“听众”
    // for (Player listener : spatialGrid[checkX][checkY]) {
    // // 条件：是活着的敌人
    // if (listener != p && listener.team != p.team && listener.isAlive()) {
    // // 检查是否在最大物理传播范围内
    // if (p.position.distanceSq(listener.position) < FOOTSTEP_RADIUS_SQ) {
    //
    // // 根据听众类型分别处理
    // if (listener.isAI) {
    // // 无论如何，AI自己的大脑都需要知道听到了声音(改到听/视觉系统里面去了)
    // // 检查这个AI是否被夺舍
    // if (listener.isControlledByPlayer()) {
    // // 如果是，就创建一个事件，并将收件入ID设置为那个控制它的玩家ID，夺舍了。
    // footstepRevealEvents.add(new FootstepRevealEvent(p.id,
    // listener.controlledByPlayerId, (Point2D.Double) p.position.clone()));
    // }
    // } else {
    // // 如果听众本来就是入类玩家，则按原逻辑发送事件
    // footstepRevealEvents.add(new FootstepRevealEvent(p.id, listener.id,
    // (Point2D.Double) p.position.clone()));
    // }
    //
    // }
    // }
    // }
    // }
    // }
    // }
    // }
    // }
    // }
    private void handleFootstepSounds() {
        long currentTime = System.currentTimeMillis();

        // [!! 核心修复 !!]
        // 1. 在循环外获取当前游戏模式
        GameMode currentMode = this.gameMode; // (this.gameMode 是 final 的)

        // 遍历所有角色
        for (Player p : getAllCharacters()) {
            // 计算玩家当前的最大速度阈值（受武器影响）
            cs2d.playerAndAi.Weapon currentWep = p.getCurrentWeapon();
            double maxSpeed = (p.team == Player.Team.ZOMBIE || currentWep == null) ? BASE_SPEED
                    : BASE_SPEED * currentWep.speedMultiplier;

            // 计算当前真实移速平方，与最大速度一半的平方进行比较
            double currentSpeedSq = p.vx * p.vx + p.vy * p.vy;
            double thresholdSpeedSq = (maxSpeed / 2.0) * (maxSpeed / 2.0);

            // 条件：速度达到最大速度的1/2及以上、没有按下静步键、且已超过发声间隔
            if (currentSpeedSq >= thresholdSpeedSq && !p.isWalking
                    && currentTime - p.lastFootstepTime > FOOTSTEP_INTERVAL_MS) {

                p.lastFootstepTime = currentTime; // 更新发声时间

                // --- 发出公共声音事件给 AI 感知 ---
                addSoundEvent(SoundEvent.SoundType.FOOTSTEP,
                        "footstep_generic",
                        p.position.x,
                        p.position.y,
                        p.id);

                // --- 使用空间网格进行高效的目标筛选 ---
                int pGridX = (int) (p.position.x / gridCellSize);
                int pGridY = (int) (p.position.y / gridCellSize);

                // 遍历发出声音者周围的 3x3 网格
                for (int x = -3; x <= 3; x++) {
                    for (int y = -3; y <= 3; y++) {
                        int checkX = pGridX + x;
                        int checkY = pGridY + y;

                        // 确保格子坐标在有效范围内
                        if (checkX >= 0 && checkX < gridWidth && checkY >= 0 && checkY < gridHeight) {
                            // 只需检查这个小格子里的少数几个“听众”
                            for (Player listener : spatialGrid[checkX][checkY]) {

                                // [!! 核心修复 !!]
                                // 2. 动态判断 "isEnemy"
                                boolean isEnemy;
                                if (currentMode == GameMode.DEATHMATCH) {
                                    isEnemy = listener != p; // 死斗模式，所有人都是敌人
                                } else {
                                    isEnemy = listener.team != p.team; // 其他模式，按队伍
                                }

                                // 3. 使用 isEnemy 变量
                                // 条件：是活着的敌人
                                if (isEnemy && listener.isAlive()) {
                                    // 检查是否在最大物理传播范围内
                                    if (p.position.distanceSq(listener.position) < FOOTSTEP_RADIUS_SQ) {

                                        // 根据听众类型分别处理
                                        if (listener.isAI) {
                                            // (AI的感知模块会自行处理公共声音事件，这里不需要额外操作)
                                            // 检查这个AI是否被夺舍
                                            if (listener.isControlledByPlayer()) {
                                                footstepRevealEvents.add(
                                                        new FootstepRevealEvent(p.id, listener.controlledByPlayerId,
                                                                (Point2D.Double) p.position.clone()));
                                            }
                                        } else {
                                            // 如果听众本来就是入类玩家，则按原逻辑发送事件
                                            footstepRevealEvents.add(new FootstepRevealEvent(p.id, listener.id,
                                                    (Point2D.Double) p.position.clone()));
                                        }

                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    public int getAiCount() {
        int count = 0;
        for (Player p : players) {
            if (p.isAI)
                count++;
        }
        count += zombies.size();
        return count;
    }

    public List<SoundEvent> getAiSoundHistory() {
        return aiSoundHistory;
    }

    public List<SmokePuff> getSmokePuffs() {
        return smokePuffs;
    }
}
