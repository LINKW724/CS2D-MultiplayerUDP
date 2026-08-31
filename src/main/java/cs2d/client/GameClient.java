// 定义该文件所属的包，用于组织代码结构
package cs2d.client;

// 导入 Google 的 Gson 库，用于处理 JSON 数据格式
import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
// 导入 JavaFX 动画相关的类
import javafx.animation.AnimationTimer;
import javafx.animation.FadeTransition;

// 导入 JavaFX 应用程序核心类
import javafx.animation.PauseTransition;
import javafx.application.Application;
import javafx.application.Platform;
// 导入 JavaFX 的属性绑定类，用于数据和UI的同步
// 导入 JavaFX 几何图形相关的类，如边距、点、位置等
import javafx.geometry.*;
// 导入 JavaFX 场景和节点相关的类
import javafx.scene.Cursor;
import javafx.scene.Group;
import javafx.scene.Node;
import javafx.scene.Scene;
// 导入 JavaFX 画布和图形上下文，用于2D绘图
import javafx.scene.canvas.Canvas;
import javafx.scene.canvas.GraphicsContext;
// 导入 JavaFX 的 UI 控件类
import javafx.scene.control.*;
// 导入 JavaFX 输入事件相关的类，如键盘和鼠标事件
import javafx.scene.input.KeyCode;
import javafx.scene.input.KeyEvent;
import javafx.scene.input.MouseButton;
// 导入 JavaFX 布局容器相关的类
import javafx.scene.layout.*;
// 导入 JavaFX 音频播放相关的类
import javafx.scene.media.AudioClip;
// 导入 JavaFX 颜色和绘图相关的类
import javafx.scene.paint.Color;
// 导入 JavaFX 形状相关的类，如 SVG 路径
import javafx.scene.shape.FillRule;
import javafx.scene.shape.ClosePath;
import javafx.scene.shape.LineTo;
import javafx.scene.shape.MoveTo;
import javafx.scene.shape.Path;
import javafx.scene.shape.Rectangle;
import javafx.scene.shape.SVGPath;
import javafx.scene.transform.Affine;
// 导入 JavaFX 文本和字体相关的类
import javafx.scene.text.*;
// 导入 JavaFX 窗口相关的类
import javafx.stage.Stage;
// 导入 JavaFX 工具类，如动画时长
import javafx.util.Duration;

// 导入 Java 的 IO (输入/输出) 类，用于文件读写
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.Enumeration;
import java.util.List;
// 导入 Java 的网络类，用于 UDP 通信
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.net.SocketException;
import java.net.URL;
// 导入 Java 的字符集类，确保文本编码正确
import java.nio.charset.StandardCharsets;
// 导入 Java 的集合工具类
import java.util.*;
// 导入 Java 的并发编程工具类，用于多线程处理
import java.util.concurrent.*;
// 导入 Java 的函数式接口类
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Consumer;
// 导入 Java 的 Stream API 类，用于数据流处理
import java.util.stream.Collectors;
import java.util.stream.Stream;
import java.util.stream.StreamSupport;
import java.util.zip.GZIPInputStream;
import java.util.zip.CRC32;

import javafx.scene.image.Image;
import javafx.scene.image.ImageView;
import javafx.scene.image.PixelBuffer;
import javafx.scene.image.PixelFormat;
import javafx.scene.image.WritableImage;

import java.nio.IntBuffer;

// 定义游戏客户端的主类，它继承自 JavaFX 的 Application 类
public class GameClient extends Application {
    static int sanitizeRenderRate(int requestedRate) {
        return requestedRate >= 30 && requestedRate <= 500 ? requestedRate : 165;
    }


    private static final int SUPPORTED_PROTOCOL_VERSION = 3;

    private QuadtreeNode quadtreeRootNode; //
    private static final int QUADTREE_MAX_OBJECTS = 8; // 根据需要调整
    private static final int QUADTREE_MAX_DEPTH = 10; // (根据需要调整
    private static final double FOOT_SOUND = 1050; // 脚步声范围
    private static final double GUNSHOT_SOUND_VISUAL_RANGE = FOOT_SOUND * 5; // 3000.0 枪声

    // region 内部类 (Inner Classes) - 这是一个代码折叠标记，方便在IDE中隐藏/显示这部分代码

    // --- 用于静态数据的枚举和记录 ---
    // 定义一个枚举来表示客户端的不同状态
    private enum ClientState {
        CONNECTING, LOBBY, PLAYING, GAME_OVER
    }

    // 使用 record 定义一个不可变的数据结构，用于存储武器的UI信息
    public record WeaponUIData(String name, int cost, String faction, int magSize, boolean isIndividual) {
    }

    // 使用 record 定义一个不可变的数据结构，用于存储道具的UI信息
    public record ItemUIData(String name, int cost, int maxQuantity, String team) {
    }

    private final List<cs2d.client.GameClient.ExplosionEffect> explosionEffects = new CopyOnWriteArrayList<>(); // 手雷爆炸特效
    // 使用线程安全的 ConcurrentHashMap 存储飞行中的手榴弹信息，键是ID，值是JSON数据
    private final ConcurrentHashMap<String, JsonObject> thrownGrenades = new ConcurrentHashMap<>(); // 没有使用插值法的
    private Image flashbangSnapshot = null;// 闪光视觉暂留
    /** 闪光残影复用固定屏幕尺寸缓冲，避免每次闪光重新分配大图。 */
    private WritableImage flashbangSnapshotBuffer;
    private final ConcurrentHashMap<String, cs2d.client.GameClient.ClientGrenade> clientGrenades = new ConcurrentHashMap<>(); // 使用插值法的
    // 使用线程安全的 ConcurrentHashMap 存储烟雾弹颗粒的信息

    private final ConcurrentHashMap<String, cs2d.client.GameClient.FireEmitter> fireEmitters = new ConcurrentHashMap<>();// 燃烧弹特效
    private long lastFrameTimeNanos = 0; // 确保燃烧弹这个变量来计算deltaTime
    private final ConcurrentHashMap<String, JsonObject> smokePuffs = new ConcurrentHashMap<>();
    // 闪光弹
    private double flashBangAlpha = 0.0; // 用于控制闪光弹白屏效果的透明度，范围 0.0 到 1.0
    private long flashBangFadeEndTime = 0; // 记录闪光效果应该结束的系统时间戳
    private boolean isUnderhandThrowing = false; // 标记玩家是否正在使用右键进行低抛投掷
    private long flashBangTotalDuration = 0; // 记录闪光弹效果的总时长，用于计算渐隐进度
    // 使用线程安全的 ConcurrentHashMap 存储地图上的标点信息
    private final ConcurrentHashMap<String, JsonObject> pingMarkers = new ConcurrentHashMap<>();
    // 使用线程安全的 ConcurrentHashMap 存储地图上的火焰效果
    private final ConcurrentHashMap<String, JsonObject> firePatches = new ConcurrentHashMap<>();
    private final List<Point2D> grenadePath = new ArrayList<>();// 预测的轨迹
    /** 用于显示 FPS 的标签 */
    private Label fpsLabel;
    /** 上次更新 FPS 的时间戳 (纳秒) */
    private long lastFpsUpdateTime = 0;
    /** 自上次更新以来的帧数计数器 */
    private int frameCount = 0;

    // --- 障碍物缓存 ---
    /** 单块远低于JavaFX 4096纹理上限，避免超大地图整图快照耗尽RTTexture。 */
    static final int OBSTACLE_CACHE_TILE_SIZE = 1024;
    /** 全图视角最终只占1600x900逻辑像素；2048概览纹理足够并可把20次提交合成1次。 */
    static final int OBSTACLE_OVERVIEW_MAX_SIZE = 2048;
    /** 固定图集纹理上限；地图加载时一次构建，运动中不再复制/上传巨型视口纹理。 */
    static final int OBSTACLE_ATLAS_MAX_SIZE = 3072;
    // 旧视口辅助函数保留给协议级回归测试；运行时固定图集路径不会调用它们。
    static final int OBSTACLE_VIEWPORT_CACHE_SIZE = 3072;
    static final double OBSTACLE_VIEWPORT_REBUILD_MARGIN = 128.0;
    static final double OBSTACLE_VIEWPORT_SAFETY_PADDING = 384.0;
    static final double OBSTACLE_VIEWPORT_SIZE_QUANTUM = 128.0;
    private static final double FOG_TEXTURE_MARGIN = 192.0;
    private static final boolean USE_RESIDENT_STATIC_MAP_LAYER = Boolean.parseBoolean(
            System.getProperty("cs2d.staticMapLayer", "true"));
    private final List<ObstacleCacheTile> obstacleCacheTiles = new ArrayList<>();
    private final List<ResidentObstacleAtlasNode> residentObstacleAtlasNodes = new ArrayList<>();
    private Image obstacleOverviewImage = null;
    private Group residentStaticMapLayer;
    private Group residentObstacleAtlasLayer;
    private ImageView residentObstacleOverviewView;
    // 兼容旧诊断与回归测试的惰性视口状态；固定图集路径不再提交构建请求。
    private ImageView residentObstacleViewportFrontView;
    private ImageView residentObstacleViewportBackView;
    private volatile ViewportBufferSet residentViewportBuffers;
    private final AtomicReference<ViewportBuildRequest> pendingViewportBuild = new AtomicReference<>();
    private volatile ViewportBuildRequest activeViewportBuild;
    private final AtomicBoolean viewportWorkerRunning = new AtomicBoolean(false);
    private final AtomicLong viewportBuildSequence = new AtomicLong();
    private final LongAdder perfStaticViewportBuildReplacements = new LongAdder();
    private final LongAdder perfStaticViewportBackgroundNanos = new LongAdder();
    private double residentViewportOriginX;
    private double residentViewportOriginY;
    private double residentViewportWidth;
    private double residentViewportHeight;
    private boolean residentViewportValid;
    private boolean residentViewportActive;
    private String residentStaticMapMode = "canvas";
    private final Affine residentStaticMapTransform = new Affine();
    private final Affine fogLayerTransform = new Affine();
    private Path fogPath;
    private final MoveTo fogHoleStart = new MoveTo();
    private final ClosePath fogHoleClose = new ClosePath();
    private final List<LineTo> fogVertexPool = new ArrayList<>();
    private int activeFogLineCount;
    private Canvas hudCanvas;
    private GraphicsContext hudGc;
    private List<Point2D> rasterizedFogGeometry = List.of();
    private double fogRasterCameraX;
    private double fogRasterCameraY;
    private double fogRasterScale = 1.0;
    private double fogRasterOffsetX;
    private double fogRasterOffsetY;
    private Color fogRasterColor;
    private long perfFogRasterizations;
    private long perfFogTransformOnlyFrames;
    private Node gameRenderNode;
    private volatile boolean residentStaticMapReady = false;
    private int residentVisibleTileCount = 0;
    private long perfStaticViewportRebuilds;
    private long perfStaticViewportTileBlits;
    private long perfStaticAtlasBuilds;
    private final Map<String, Image> playerSpriteCache = new HashMap<>();
    private final Map<String, Image> droppedWeaponSpriteCache = new HashMap<>();
    private long perfPlayerSpriteDraws;
    private long perfDroppedWeaponSpriteDraws;
    private long perfSpriteBuilds;
    /** 同一服务端会话可能有多个已经在途的map_data分片，只初始化一次相同地图。 */
    private String initializedMapSignature = null;

    // --- 记分板长按检测 ---
    private long tabPressTime = 0;
    private boolean tabIsLongPress = false;

    // --- 网络保活追踪 ---
    // 用于保存当前的定时重连句柄以及心跳线程，以防止多次点击导致的并发泄漏
    private java.util.concurrent.ScheduledFuture<?> connectionHandle;
    private java.util.concurrent.ScheduledFuture<?> staticDataRequestHandle;
    private Thread pingThread;

    // public String spectatorTargetId;
    // 定义一个静态内部类，专门用于处理和缓存武器图标
    static class WeaponIcon {
        // 缓存SVG路径数据，使用 ConcurrentHashMap 保证线程安全，键是武器名，值是SVG路径字符串
        private static final Map<String, String> SVG_PATH_CACHE = new ConcurrentHashMap<>();
        // 定义一个名称到文件名的映射表，因为有些武器的内部名称和文件名不一致
        private static final Map<String, String> NAME_TO_FILENAME_MAP = new HashMap<>();

        // static 代码块，在类加载时仅执行一次，用于初始化静态数据
        static {
            // --- 定义名称映射与预加载爆头图标的固定路径 ---
            // SVG_PATH_CACHE.put("HEADSHOT", "headshot");
            // 将内部名称 "KEVLAR" 映射到图标文件名 "armor.svg"
            NAME_TO_FILENAME_MAP.put("KEVLAR", "armor");

            // 以下是各种武器的名称到文件名的映射
            NAME_TO_FILENAME_MAP.put("GLOCK18", "glock");
            NAME_TO_FILENAME_MAP.put("USPS", "usp_silencer");
            NAME_TO_FILENAME_MAP.put("FIVESEVEN", "fiveseven");
            NAME_TO_FILENAME_MAP.put("R8", "revolver");
            NAME_TO_FILENAME_MAP.put("M4A4", "m4a1");
            NAME_TO_FILENAME_MAP.put("M4A1S", "m4a1_silencer");
            NAME_TO_FILENAME_MAP.put("GALIL", "galilar");
            NAME_TO_FILENAME_MAP.put("SG553", "sg556");
            NAME_TO_FILENAME_MAP.put("SAWEDOFF", "sawedoff");
            NAME_TO_FILENAME_MAP.put("HE_GRENADE", "grenade");
            NAME_TO_FILENAME_MAP.put("SMOKE_GRENADE", "smokegrenade");
            NAME_TO_FILENAME_MAP.put("MOLOTOV", "molotov");
            NAME_TO_FILENAME_MAP.put("INCENDIARY", "inferno");
            NAME_TO_FILENAME_MAP.put("DECOY", "decoy");
            NAME_TO_FILENAME_MAP.put("KEVLAR_HELMET", "heavy_armor");
            NAME_TO_FILENAME_MAP.put("DEFUSE_KIT", "defuser");
            NAME_TO_FILENAME_MAP.put("KNIFE", "knife_t");

            // --- 执行一次性预加载所有图标文件 ---
            preloadAllIcons();
        }

        /**
         * 一次性加载所有已知武器和物品的图标数据到缓存中
         */
        private static void preloadAllIcons() {
            // 在控制台打印日志，表示开始预加载
            System.out.println("--- 开始预加载所有武器图标 ---");

            // 创建一个集合来存储所有需要加载图标的物品名称，使用 HashSet 自动去重
            Set<String> allWeaponKeys = new HashSet<>();
            // 将所有武器的键名添加到集合
            allWeaponKeys.addAll(WEAPON_UI_DATA.keySet());
            // 将所有道具的键名添加到集合
            allWeaponKeys.addAll(ITEM_UI_DATA.keySet());
            // 确保默认的刀子图标也被加载
            allWeaponKeys.add("KNIFE");

            // 遍历集合中的每一个武器名称
            for (String weaponName : allWeaponKeys) {
                // 如果缓存中已经有了这个图标（比如手动添加的），就跳过
                if (SVG_PATH_CACHE.containsKey(weaponName)) {
                    continue; // 继续下一个循环
                }
                // 调用加载方法，从文件加载SVG路径数据
                String pathData = loadSvgPathData(weaponName);
                // 如果加载成功（返回的路径不是null）
                if (pathData != null) {
                    // 将加载到的路径数据存入缓存
                    SVG_PATH_CACHE.put(weaponName, pathData);
                }
            }
            // 在控制台打印日志，表示预加载完成
            System.out.println("--- 所有武器图标预加载完成 ---");
        }

        /**
         * 获取指定武器的图标节点
         *
         * @param weaponName 武器的内部名称
         * @param isHeadshot 是否是爆头图标
         * @return 一个可以显示在UI上的JavaFX节点 (Node)
         */
        public static Node getIcon(String weaponName, boolean isHeadshot) {
            // [新增打印] 打印传入 getIcon 方法的原始参数
            // System.out.println("[WEAPON_ICON] getIcon 方法被调用, 传入参数 -> weaponName: '" +
            // weaponName + "', isHeadshot: " + isHeadshot);
            // 根据是否是爆头来确定用于查找缓存的键名
            String key = isHeadshot ? "HEADSHOT" : weaponName;

            // [新增打印] 打印用于查找缓存的 key (用于调试)
            // System.out.println("[WEAPON_ICON] 正在使用 Key: '" + key + "' 查找图标...");

            // 先用最简单、非阻塞的 get 方法尝试从缓存获取数据
            String pathData = SVG_PATH_CACHE.get(key);

            // 如果缓存中没有，才去加载
            if (pathData == null) {
                // System.out.println("[WEAPON_ICON] 缓存未命中, 尝试从文件加载..."); // (调试信息)
                // 调用加载方法，这个方法是独立的，不会访问缓存
                pathData = loadSvgPathData(key);

                // 2a. 如果加载成功，才用 put 方法存入缓存
                if (pathData != null) {
                    // System.out.println("[WEAPON_ICON] 文件加载成功, 将 Key: '" + key + "' 存入缓存。"); //
                    // (调试信息)
                    SVG_PATH_CACHE.put(key, pathData);
                } else {
                    // 如果加载失败，在控制台打印错误信息
                    System.err.println("[WEAPON_ICON] 文件加载失败!");
                }
            } else {
                // System.out.println("[WEAPON_ICON] 缓存命中, 直接使用缓存数据。"); // (调试信息)
            }

            // 如果经过以上步骤，数据仍然是 null (意味着加载失败)
            if (pathData == null) {
                // 打印错误信息
                System.err.println("[WEAPON_ICON] 图标获取失败! 启用备用方案: 加载 KNIFE 图标。");
                // 尝试获取备用的刀子图标路径。computeIfAbsent表示如果"KNIFE"不存在，则调用loadSvgPathData加载它
                pathData = SVG_PATH_CACHE.computeIfAbsent("KNIFE", cs2d.client.GameClient.WeaponIcon::loadSvgPathData);

                // 如果连备用图标都加载失败
                if (pathData == null) {
                    // 打印致命错误信息
                    System.err.println("致命错误: 连备用图标 KNIFE 都无法加载！");
                    // 返回一个空的 Pane 节点，避免程序崩溃
                    return new Pane();
                }
            }

            // 创建一个 SVGPath 对象来显示图标
            SVGPath svgPath = new SVGPath();
            // 设置 SVG 的路径数据
            svgPath.setContent(pathData);
            // 调用样式方法来设置图标的颜色和大小
            styleIcon(svgPath);
            // 返回创建好的图标节点
            return svgPath;
        }

        // 辅助方法：从文件加载SVG路径字符串，失败则返回null
        private static String loadSvgPathData(String weaponName) {
            // 根据武器名从映射表中获取文件名，如果找不到，则使用武器名的小写形式作为默认文件名
            String fileName = NAME_TO_FILENAME_MAP.getOrDefault(weaponName, weaponName.toLowerCase());
            // 构造资源文件的路径
            String resourcePath = "/icons/" + fileName + ".svg";

            // 使用 try-with-resources 语句确保输入流能被自动关闭
            try (InputStream stream = cs2d.client.GameClient.WeaponIcon.class.getResourceAsStream(resourcePath)) {
                // 如果流为 null，说明资源文件不存在
                if (stream == null) {
                    System.err.println("预加载失败: 图标文件未找到 -> " + resourcePath + " (对应武器名: " + weaponName + ")");
                    return null; // 返回 null 表示失败
                }

                // 读取流中的所有字节，并用 UTF-8 编码转换为字符串
                String svgContent = new String(stream.readAllBytes(), StandardCharsets.UTF_8);
                // 使用 Stream API 处理 SVG 内容字符串
                String path = Arrays.stream(svgContent.split("<path")) // 按 "<path" 分割
                        .filter(s -> s.contains(" d=\"")) // 筛选出包含 " d="" 的部分
                        // 提取 "d" 属性中的路径数据
                        .map(s -> s.substring(s.indexOf(" d=\"") + 4, s.indexOf("\"", s.indexOf(" d=\"") + 5)))
                        // 将所有提取到的路径数据用空格连接起来（适用于多路径图标）
                        .collect(Collectors.joining(" "));

                // 如果提取出的路径是空的
                if (path.isBlank()) {
                    System.err.println("预加载失败: SVG文件格式不正确或无路径数据 -> " + resourcePath);
                    return null; // 返回 null 表示失败
                }
                // System.out.println("预加载成功: " + resourcePath); // (调试信息)
                return path; // 返回提取到的路径数据

            } catch (IOException e) { // 捕获读取文件时可能发生的IO异常
                System.err.println("预加载异常: 读取文件时出错 -> " + resourcePath);
                e.printStackTrace(); // 打印异常堆栈信息
                return null; // 返回 null 表示失败
            }
        }

        // 样式方法保持不变
        private static void styleIcon(SVGPath path) {
            path.setFill(Color.WHITE); // 设置图标填充色为白色
            path.setScaleX(0.8); // 将图标在X轴上缩放到80%
            path.setScaleY(0.8); // 将图标在Y轴上缩放到80%
        }
    }

    // endregion - 代码折叠区域结束

    // region 成员变量 (Member Variables) - 代码折叠区域开始

    // --- HUD 动态标签 ---
    // 定义一系列用于显示游戏信息的 JavaFX Label 控件
    private Label pingLabel, roundLabel, timerLabel, ctScoreLabel, tScoreLabel;
    private Label waveLabel, zombiesLeftLabel, winnerLabel, reasonLabel, nextWaveLabel;
    private VBox lossBonusBox; // 用于显示战败补偿条的容器
    private Label moneyLabel, hpLabel, armorLabel, weaponLabel, ammoLabel;

    private HBox equipBox; // 用于显示玩家装备图标的容器
    /** 装备栏只在实际内容变化时重建，避免每帧触发SVG、CSS和布局失效。 */
    private String renderedEquipmentHudSignature;
    /** null表示尚未应用过全局HUD可见性。 */
    private Boolean renderedHudGlobalVisibility;
    private Label buyPrompt, changeWeaponPrompt; // 用于显示购买和换枪提示的标签
    // 击杀信息
    private VBox killFeedVBox; // 专门用于存放击杀信息UI的容器
    private List<Region> lossBonusBars; // 存储战败补偿的UI条
    private int killsThisLife = 0; // 记录玩家本条命的击杀数
    /** 用于存放已处理过的击杀事件ID，防止重复计数 */
    private StackPane killsThisLifeContainer; // 一个容器，用来叠放 "Kills: X" 标签和特效
    private Label killsThisLifeLabel; // 显示 "Kills: X" 的标签
    private Pane killFlashEffect; // 击杀后的闪光特效层
    // 使用一个集合来存储已经处理过的击杀信息ID，防止重复显示或计数
    private final Set<String> processedKillFeedIds = new HashSet<>();

    // --- 静态数据 (常量) ---
    // 定义一个静态 Map 存储按类别分的武器列表
    private static final Map<String, List<String>> WEAPONS_BY_CATEGORY = createWeaponCategories();
    // 定义一个静态 Map 存储所有武器的UI数据
    private static final Map<String, cs2d.client.GameClient.WeaponUIData> WEAPON_UI_DATA = createWeaponData();
    // 定义一个静态 Map 存储所有道具的UI数据
    private static final Map<String, cs2d.client.GameClient.ItemUIData> ITEM_UI_DATA = createItemData();
    // 爆破模式的最大回合数
    private static final int DEMOLITION_MAX_ROUNDS = 24;
    // 爆破模式的安放C4所需时间（毫秒）
    private static final int DEMO_PLANT_TIME_MS = 3000;
    // 爆破模式无拆弹器时拆除C4所需时间（毫秒）
    private static final int DEMO_DEFUSE_TIME_MS = 10000;
    // 爆破模式有拆弹器时拆除C4所需时间（毫秒）
    private static final int DEMO_DEFUSE_WITH_KIT_TIME_MS = 5000;

    // --- 网络配置 ---
    private String serverIp = "10.10.10.242"; // 默认的服务器IP地址
    private int serverPort = 14726; // 默认的服务器端口号
    // 游戏画布（窗口内容区域）的宽度
    private static final int CANVAS_WIDTH = 1600;
    // 游戏画布的高度
    private static final int CANVAS_HEIGHT = 900;
    // 玩家的尺寸（直径）
    private static final int PLAYER_SIZE = 24;
    // 服务器权威模拟、输入发送和客户端渲染使用彼此独立的时钟。
    private static final double SERVER_TICK_RATE = 60.0;
    /** 服务端在每个60Hz Tick内执行两个旧版运动子步，速度字段仍是120Hz单位。 */
    private static final double LEGACY_PHYSICS_RATE = 120.0;
    private static final int INPUT_SEND_RATE = 60;
    private static final int TARGET_RENDER_RATE = sanitizeRenderRate(
            Integer.getInteger("cs2d.renderHz", 165));
    private static final Color FOLLOW_FOG_COLOR = Color.rgb(26, 32, 44, 0.85);
    private static final Color GLOBAL_FOG_COLOR = Color.rgb(26, 32, 44, 0.5);
    static final int FOV_RAY_COUNT = 106 * 16;
    private static final double FOV_RADIANS = Math.toRadians(106.0);
    private static final double FOV_ANGLE_STEP = FOV_RADIANS / (FOV_RAY_COUNT - 1);
    private static final double FOV_RAY_LENGTH = 8000.0;
    private static final double TWO_PI = Math.PI * 2.0;
    private static final double FOV_SIMPLIFY_ANGLE_TOLERANCE = Math.toRadians(1.0);
    private static final double[] FOV_RELATIVE_COSINES = createFovRelativeDirections(false);
    private static final double[] FOV_RELATIVE_SINES = createFovRelativeDirections(true);
    private static final int DYNAMIC_ELLIPSE_SEGMENTS = 16;
    private static final double[] DYNAMIC_ELLIPSE_UNIT_X = createUnitCircleCoordinates(false);
    private static final double[] DYNAMIC_ELLIPSE_UNIT_Y = createUnitCircleCoordinates(true);
    private static final int MAX_NETWORK_MESSAGES_PER_RENDER_FRAME = 512;

    // --- JavaFX UI 元素 ---
    private Stage primaryStage; // 主窗口
    private StackPane rootPane; // 根布局容器
    private Canvas canvas; // 游戏绘图区域
    private GraphicsContext gc; // 画布的图形上下文，用于绘图
    private VBox ipEntryPane; // IP输入界面
    private VBox serverBrowserPane; // [新增] 服务器浏览器界面
    private final Map<String, JsonObject> discoveredServers = new ConcurrentHashMap<>(); // [新增] 发现的服务器
    private long lastDiscoveryBroadcastTime = 0;
    private static final long DISCOVERY_INTERVAL_MS = 3000; // 每3秒广播一次探测包

    private DatagramSocket discoverySocket; // [新增] 专门用于局域网发现的 Socket
    private Thread discoveryThread; // [新增] 专门用于监听发现回复的线程
    private VBox lobbyPane; // 游戏大厅界面
    private VBox nameSelectionPane; // 名字选择界面
    private VBox teamSelectionPane; // 队伍选择界面
    private StackPane gameContainer; // 游戏主界面容器
    private BorderPane buyMenuPane; // 购买菜单界面
    private StackPane scoreboardPane; // 记分板界面
    private ScrollPane scoreboardScrollPane;
    private VBox scoreboardContent;
    private String scoreboardStructureKey;
    private final Map<String, Label> scoreboardSectionLabels = new HashMap<>();
    private final Map<String, List<Label>> scoreboardPlayerLabels = new HashMap<>();
    private VBox tdmWeaponSelectorPane; // 团队死斗模式的武器选择界面
    private VBox settingsPane; // 设置界面
    private Label connectionStatusLabel; // 连接状态标签
    // 存储购买菜单中所有“购买”按钮的映射
    private final Map<String, Button> buyMenuButtons = new ConcurrentHashMap<>();
    // 存储购买菜单中所有“撤销”按钮的映射
    private final Map<String, Button> undoMenuButtons = new ConcurrentHashMap<>();
    private TilePane groundWeaponsContainer; // 用于显示地上武器的容器
    // 存储地上武器拾取按钮的映射
    private final Map<String, Button> groundWeaponButtons = new ConcurrentHashMap<>();
    private VBox initialWeaponSelectorPane; // 初始武器选择界面
    // --- 网络 ---
    private DatagramSocket socket; // UDP套接字，用于收发数据
    private InetSocketAddress serverAddress; // 服务器的地址和端口
    // 用于监听网络消息的单线程执行器
    private final ExecutorService networkListenExecutor = Executors.newSingleThreadExecutor();
    // 用于发送网络消息的单线程执行器
    private final ExecutorService networkSendExecutor = Executors.newSingleThreadExecutor();
    /** 输入发送不依赖JavaFX Pulse；画面掉帧时仍维持稳定60Hz命令流。 */
    private final ScheduledExecutorService inputSendExecutor = Executors.newSingleThreadScheduledExecutor(r -> {
        Thread t = new Thread(r, "Subtick-Input-Sender");
        t.setDaemon(true);
        return t;
    });
    private final SubtickInputTransmitter subtickInputTransmitter = new SubtickInputTransmitter(INPUT_SEND_RATE);
    private final AtomicReference<LocalInputState> latestLocalInput =
            new AtomicReference<>(new LocalInputState(0.0, 0));
    private ScheduledFuture<?> inputSendHandle;

    // --- 专门用于 FOV 计算的单线程执行器 ---
    private final ExecutorService fovExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "FOV-Calculator-Thread");
        t.setDaemon(true); // 设为守护线程
        return t;
    });
    private final ExecutorService staticViewportExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "Static-Viewport-Composer");
        t.setDaemon(true);
        return t;
    });
    private final AtomicReference<cs2d.client.GameClient.FovRequest> pendingFovRequest = new AtomicReference<>();
    private final AtomicBoolean fovWorkerRunning = new AtomicBoolean(false);
    // 只有单一FOV worker访问这些缓冲区，可跨帧复用，避免1696射线产生高频临时数组。
    private final double[] fovRayDirectionsX = new double[FOV_RAY_COUNT];
    private final double[] fovRayDirectionsY = new double[FOV_RAY_COUNT];
    private final double[] fovClosestDistances = new double[FOV_RAY_COUNT];
    private final List<cs2d.client.GameClient.StaticObstacle> fovCandidateObstacles = new ArrayList<>();
    private long[] fovCandidateRayRanges = new long[0];
    private double[] dynamicFovEdgeCoordinates = new double[0];
    // 一个专用的 handleServerMessage 单线程池
    private final ExecutorService stateUpdateExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "State-Update-Thread");
        t.setDaemon(true);
        return t;
    });
    /** 性能报告在后台格式化和输出，IDEA控制台变慢时不能阻塞JavaFX帧线程。 */
    private final ExecutorService diagnosticsExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread t = new Thread(r, "Client-Diagnostics-Thread");
        t.setDaemon(true);
        return t;
    });
    /** 状态线程只发刷新信号；JavaFX帧边界合并消费，避免120Hz runLater任务堆积。 */
    private final AtomicBoolean buyMenuRefreshPending = new AtomicBoolean(false);
    // 用于处理连接和重连的定时任务执行器
    private final ScheduledExecutorService connectionExecutor = Executors.newSingleThreadScheduledExecutor();

    // 网络线程完成UDP分片重组和JSON解析；普通事件保持顺序，状态包只保留各自最新一份。
    private final ConcurrentLinkedQueue<JsonObject> messageBatchQueue = new ConcurrentLinkedQueue<>();
    private final LatestStateMailbox pendingStateMessages = new LatestStateMailbox();

    private int drainNetworkMessagesForRenderFrame() {
        int processed = 0;
        JsonObject message;
        while (processed < MAX_NETWORK_MESSAGES_PER_RENDER_FRAME
                && (message = messageBatchQueue.poll()) != null) {
            try {
                handleServerMessage(message);
            } catch (RuntimeException e) {
                System.err.println("Network message execution error: " + e.getMessage());
            }
            processed++;
        }
        for (JsonObject state : pendingStateMessages.drainOrdered()) {
            try {
                handleServerMessage(state);
            } catch (RuntimeException e) {
                System.err.println("State message execution error: " + e.getMessage());
            }
            processed++;
        }
        return processed;
    }

    private final Gson gson = new Gson(); // Gson实例，用于JSON序列化和反序列化

    private final Random rand = new Random(); // 客户端随机数生成器
    private volatile boolean running = false; // 用于控制网络线程的运行状态

    // --- 游戏设置 ---
    // 存储游戏设置的对象
    private final GameSettings gameSettings = new GameSettings();
    /** 记录上次收到服务器消息的纳秒时间戳，用于看门狗检测 */
    private volatile long lastServerMessageTime = System.nanoTime();

    /** 用于防止多个线程同时触发重连的原子锁 */
    private final AtomicBoolean isReconnecting = new AtomicBoolean(false);
    private final AtomicLong lastStateSequence = new AtomicLong(-1);
    private volatile String serverSessionId;
    private java.util.concurrent.ScheduledFuture<?> reconnectRetryHandle;
    private int reconnectAttempt = 0;
    // --- 游戏状态 ---
    private volatile cs2d.client.GameClient.ClientState clientState = cs2d.client.GameClient.ClientState.CONNECTING; // 当前客户端状态，volatile保证多线程可见性
    private volatile JsonObject mapData; // 当前地图的数据，volatile保证多SFX线程可见性
    // 存储所有客户端玩家的信息
    private final ConcurrentHashMap<String, cs2d.client.GameClient.ClientPlayer> clientPlayers = new ConcurrentHashMap<>();
    // 存储地上掉落的物品信息
    /**
     * 地面物品使用不可变快照。状态线程构建完成后一次替换，避免渲染线程观察到
     * clear() 与逐项 put() 之间的短暂空集合。
     */
    private volatile Map<String, JsonObject> droppedItems = Map.of();
    // 存储视觉特效（如爆炸、枪火）的列表
    private final List<JsonObject> visualEffects = new CopyOnWriteArrayList<>();
    // 存储所有僵尸的信息
    private final ConcurrentHashMap<String, cs2d.client.GameClient.ClientPlayer> clientZombies = new ConcurrentHashMap<>();
    private volatile JsonObject latestGameState; // 最新的游戏状态快照，volatile保证多线程可见性
    private volatile JsonObject latestFullGameState;
    private volatile String myPlayerId; // 我自己的玩家ID，需对握手重试线程立即可见
    private volatile cs2d.client.GameClient.ClientPlayer me; // 对我自己的 ClientPlayer 对象的引用
    private String playerName = "Player"; // 玩家设置的名字
    private final Set<KeyCode> keysDown = new HashSet<>(); // 存储当前按下的所有键盘按键
    private boolean isShooting = false; // 标记是否正在开火
    private double mouseX, mouseY; // 记录鼠标在屏幕上的X和Y坐标
    private boolean isInteracting = false; // 标记是否正在进行交互（如拆弹、安放C4）
    private long ping = 0; // 记录到服务器的延迟（毫秒）
    private long pingStartTime = 0; // 记录发送ping请求的时间戳

    // --- [新] 性能日志变量 ---
    /** 上次性能日志更新的时间戳 (纳秒) */
    private long lastPerfLogTime = 0;
    /** 性能日志间隔内的总绘制耗时 (纳秒) */
    private double perfTimeTotalDraw = 0;

    /** [1] 性能日志间隔内的FOV计算耗时 (纳秒) */
    private double perfTimeFovCalc = 0;
    /** [L1] 逻辑 - 发送输入耗时 */
    private double perfTimeLogic_SendInput = 0;
    /** [L2] 逻辑 - 插值/平滑耗时 (玩家/僵尸/手雷) */
    private double perfTimeLogic_Interpolation = 0;
    /** [L3] 逻辑 - HUD逻辑耗时 (KillFeed, DamageLog) */
    private double perfTimeLogic_HUDLogic = 0;

    /** 性能日志间隔内的总帧数 */
    private int perfFrameCount = 0;
    private final RuntimePerformanceMonitor runtimePerformanceMonitor = new RuntimePerformanceMonitor();
    // 后台 FOV 多维度监控；LongAdder 避免计算线程与 JavaFX 线程争用。
    private final LongAdder fovRequestCount = new LongAdder();
    private final LongAdder fovCalculationCount = new LongAdder();
    private final LongAdder fovPublishedCount = new LongAdder();
    private final LongAdder fovPendingReplacementCount = new LongAdder();
    private final LongAdder fovStaleResultCount = new LongAdder();
    private final LongAdder fovTotalCalculationNanos = new LongAdder();
    private final LongAdder fovQueryNanos = new LongAdder();
    private final LongAdder fovEdgeExtractNanos = new LongAdder();
    private final LongAdder fovIntersectionNanos = new LongAdder();
    private final LongAdder fovCandidateObstacleCount = new LongAdder();
    private final LongAdder fovRawVertexCount = new LongAdder();
    private final LongAdder fovFinalVertexCount = new LongAdder();
    private final AtomicLong fovMaxCalculationNanos = new AtomicLong();
    private final AtomicLong fovLatestFinalVertexCount = new AtomicLong();

    /** [2] (这个不再直接累加，而是由 2a, 2b, 2c 相加得出) */
    // private double perfTimeWorldDraw = 0; // 我们不再需要这个总的累加器

    /** [2a] 障碍物绘制耗时 */
    private double perfTimeDrawWorld_Obstacles = 0;
    /** [2b] 实体(玩家/物品)绘制耗时 */
    private double perfTimeDrawWorld_Entities = 0;
    /** [2c] 特效(烟雾/火焰)绘制耗时 */
    private double perfTimeDrawWorld_VFX = 0;

    /** [3] 性能日志间隔内的迷雾绘制耗时 (纳秒) */
    private double perfTimeFogDraw = 0;
    /** [4] 性能日志间隔内的HUD绘制耗时 (纳秒) */
    private double perfTimeHudDraw = 0;
    /** HUD缓存命中审计：稳定状态下应远小于渲染帧数。 */
    private long perfEquipmentHudRebuilds = 0;
    private long perfHudVisibilityPasses = 0;

    // --- [新] 网络消息处理 (Message Handling) 耗时 ---
    /** [M] 消息处理总耗时 */
    private double perfTimeMsgHandling = 0;
    /** [M1] Full Update 耗时 */
    private double perfTimeMsg_FullUpdate = 0;
    /** [M2] Small Update 耗时 */
    private double perfTimeMsg_SmallUpdate = 0;
    /** [M3] Chunk (分片) 耗时 */
    private final LongAdder perfTimeMsgChunkNanos = new LongAdder();
    private final LongAdder perfChunkDatagrams = new LongAdder();
    private final LongAdder perfCompletedChunkMessages = new LongAdder();
    /** [M4] Map Data 耗时 */
    private double perfTimeMsg_MapData = 0;
    /** [M5] Events (声音/击杀) 耗时 */
    private double perfTimeMsg_Events = 0;

    // --- FOV 优化变量 ---
    private AnimationTimer gameLoop; // 管理游戏循环的 AnimationTimer
    /** HUD由唯一游戏帧循环驱动，避免匿名AnimationTimer在界面重建后残留。 */
    private Runnable hudFrameUpdater = () -> {
    };

    private record LocalInputState(double angle, int buttons) {
    }

    // --- 渲染与相机 ---
    // 游戏镜头对象，用于控制视野
    private final cs2d.client.GameClient.Camera camera = new cs2d.client.GameClient.Camera();
    private String cameraMode = "follow"; // 相机模式，"follow"跟随玩家，"full"全局视角

    // --- 视野/战争迷雾 ---
    private volatile List<Point2D> fovPoints = new ArrayList<>(); // 存储视野多边形的顶点
    private Point2D lastPlayerPosForFOV = new Point2D(-1, -1); // 上一次计算视野时的玩家位置
    private double lastSourceAngleForFOV = Double.NaN; // 真正决定射线方向的上一次角度

    // --- 声音系统 ---
    // 存储所有预加载的音效
    private final Map<String, AudioClip> sounds = new ConcurrentHashMap<>();
    /** 高频WAV由一个固定线程混音，避免每次AudioClip.play创建原生媒体线程。 */
    private final PcmAudioMixer pcmAudioMixer = new PcmAudioMixer();
    private final Map<String, PcmAudioMixer.Sound> pcmSounds = new ConcurrentHashMap<>();
    private final LongAdder pcmFallbackPlays = new LongAdder();
    /** MP3会进入JavaFX MediaPlayer后端；远程语音必须串行，避免每次重叠播放创建原生线程群。 */
    private final Set<String> mediaBackedSoundKeys = ConcurrentHashMap.newKeySet();
    private final MediaSoundGate mediaSoundGate = new MediaSoundGate(TimeUnit.MILLISECONDS.toNanos(250));
    private volatile AudioClip activeMediaBackedWorldSound;
    private final LongAdder mediaBackedWorldSoundsPlayed = new LongAdder();
    private final LongAdder mediaBackedWorldSoundsSuppressed = new LongAdder();
    // 声音能传播的最大距离
    private static final double MAX_SOUND_DISTANCE = 800;

    static final class MediaSoundGate {
        private final long minimumIntervalNanos;
        private long nextAllowedNanos;

        MediaSoundGate(long minimumIntervalNanos) {
            this.minimumIntervalNanos = Math.max(0L, minimumIntervalNanos);
        }

        synchronized boolean tryAcquire(long nowNanos, boolean previousSoundStillPlaying) {
            if (previousSoundStillPlaying || nowNanos < nextAllowedNanos)
                return false;
            nextAllowedNanos = nowNanos + minimumIntervalNanos;
            return true;
        }
    }

    // --- UI 字体与样式 ---
    // 定义各种UI元素的字体
    private static Font hudFont, smallHudFont, tinyHudFont, scoreboardFont, titleFont, lobbyTitleFont, buttonFont;
    // 定义UI中使用的主要颜色
    private final Color PRIMARY_BLUE = Color.web("#63B3ED"), PRIMARY_RED = Color.web("#F56565"),
            PRIMARY_GREEN = Color.web("#48BB78");
    private final Color BACKGROUND_DARK = Color.web("#1a202c"), CARD_BACKGROUND = Color.web("#2d3748"),
            TEXT_LIGHT = Color.web("#f7fafc");
    // 定义面板的通用CSS样式字符串
    private final String panelStyle = "-fx-background-color: rgba(26, 32, 44, 0.95); -fx-background-radius: 16px; -fx-border-radius: 16px; -fx-border-color: rgba(255,255,255,0.1); -fx-border-width: 1px;";
    // 定义按钮被禁用时的CSS样式
    private final String buttonDisabledStyle = "-fx-opacity: 0.4;";
    // 定义按钮的通用CSS样式
    private final String buttonStyle = "-fx-background-color: #4a5568; -fx-text-fill: white; -fx-font-family: 'Orbitron'; -fx-font-weight: 600; -fx-font-size: 16px; -fx-padding: 12px 20px; -fx-border-color: #2d3748; -fx-border-width: 2px; -fx-background-radius: 8px; -fx-border-radius: 8px; -fx-effect: dropshadow(gaussian, rgba(0,0,0,0.1), 4, 0, 0, 2);";
    // 定义按钮在鼠标悬停时的CSS样式
    private final String buttonHoverStyle = "-fx-background-color: #2d3748; -fx-border-color: #63b3ed; -fx-effect: dropshadow(gaussian, rgba(99,179,237,0.2), 8, 0, 0, 3);";
    /** 记录当前正在观战的队友在列表中的索引 */
    private int spectateTeammateIndex = 0;
    /** 用于检测玩家是否刚刚从存活状态变为死亡状态 */
    private boolean wasAliveLastFrame = true;
    // endregion - 代码折叠区域结束

    // 静态代码块，在类加载时调用加载自定义字体的方法
    static {
        loadCustomFonts();
    }

    // 定义存储设置的文件名
    private static final String SETTINGS_FILE_NAME = "cs2d_settings.json";

    /**
     * 将当前的 `gameSettings` 对象保存到 JSON 文件中。
     */
    private void saveSettings() {
        // 打印保存日志
        System.out.println("正在保存设置到: " + SETTINGS_FILE_NAME);
        // 使用 try-with-resources 确保 FileWriter 被自动关闭
        try (FileWriter writer = new FileWriter(SETTINGS_FILE_NAME)) {
            // 使用 Gson 将 GameSettings 对象的 JSON 表示写入文件
            gson.toJson(cs2d.client.GameSettings.toJson(), writer);
        } catch (IOException e) { // 捕获IO异常
            // 打印错误信息
            System.err.println("无法保存设置: " + e.getMessage());
        }
    }

    // 用于存储当前回合/生命周期内收到的伤害日志条目的列表
    private final List<JsonObject> currentDamageLogEntries = new ArrayList<>();

    // 用于显示日志的 UI 元素 (我们稍后创建)
    private GridPane damageLogDisplayBox;

    // TDM 模式淡出计时器
    private PauseTransition tdmDamageLogFadeTimer;
    private FadeTransition tdmDamageLogFadeOut;

    /**
     * 从 JSON 文件中加载设置，并更新 `gameSettings` 对象。
     */
    private void loadSettings() {
        // 创建文件对象
        File settingsFile = new File(SETTINGS_FILE_NAME);
        // 如果文件存在
        if (settingsFile.exists()) {
            // 打印加载日志
            System.out.println("从 " + SETTINGS_FILE_NAME + " 加载设置...");
            // 使用 try-with-resources 确保 FileReader 被自动关闭
            try (FileReader reader = new FileReader(settingsFile)) {
                // 从文件中读取 JSON 数据
                JsonObject json = gson.fromJson(reader, JsonObject.class);
                // 使用 JSON 数据更新 GameSettings 对象
                gameSettings.fromJson(json);
            } catch (IOException | com.google.gson.JsonSyntaxException e) { // 捕获IO异常或JSON语法错误
                // 打印错误信息
                System.err.println("无法加载设置: " + e.getMessage() + "。将使用默认设置。");
            }
        } else {
            // 如果文件不存在，打印提示信息
            System.out.println("设置文件未找到。将使用默认设置。");
        }
    }

    /**
     * 创建初始武器选择界面，这是一个独立的顶层面板。
     */
    private void createInitialWeaponSelectorUI() {
        // 创建一个新的 VBox 布局容器，元素垂直间距为20
        initialWeaponSelectorPane = new VBox(20);
        // 设置容器内容居中对齐
        initialWeaponSelectorPane.setAlignment(Pos.CENTER);
        // 应用通用的面板样式
        initialWeaponSelectorPane.setStyle(panelStyle);
        // 设置内边距为40
        initialWeaponSelectorPane.setPadding(new Insets(40));
        // 默认设置为不可见
        initialWeaponSelectorPane.setVisible(false);
        // 限制最大宽度为800像素
        initialWeaponSelectorPane.setMaxWidth(1600);
        // 限制最大高度为600像素
        initialWeaponSelectorPane.setMaxHeight(900);
    }

    // JavaFX Application 的入口方法
    @Override
    public void start(Stage stage) {
        // 在程序启动时加载设置
        loadSettings();

        // 保存主舞台（窗口）的引用
        this.primaryStage = stage;
        // 设置窗口标题
        primaryStage.setTitle("CS2D Client (UDP)");

        // 使用 StackPane 作为根布局，它会自动将所有子节点居中堆叠
        rootPane = new StackPane();
        // 设置根布局的背景颜色
        rootPane.setStyle("-fx-background-color: " + toCssColor(BACKGROUND_DARK) + ";");

        // 调用方法创建各个UI界面
        createIpEntryUI();
        createServerBrowserUI(); // [新增]
        createGameUI();
        createLobbyUI();
        createInitialWeaponSelectorUI();
        createSettingsPanel();

        // 确保所有顶层面板都被添加到了 StackPane 中
        // 将所有创建好的顶层UI面板添加到根布局中
        rootPane.getChildren().addAll(
                ipEntryPane,
                serverBrowserPane, // [新增]
                gameContainer,
                lobbyPane,
                initialWeaponSelectorPane, // 这是我们之前创建的独立武器选择面板
                settingsPane);

        // --- 设置初始可见性 ---
        // 初始显示服务器浏览器
        serverBrowserPane.setVisible(true);
        startDiscoveryListener(); // [新增] 启动发现监听器

        // 其他界面全部隐藏
        ipEntryPane.setVisible(false);
        lobbyPane.setVisible(false);
        gameContainer.setVisible(false);
        settingsPane.setVisible(false);
        initialWeaponSelectorPane.setVisible(false);

        // 创建场景，并将根布局放入其中，设置场景的初始大小
        Scene scene = new Scene(rootPane, CANVAS_WIDTH, CANVAS_HEIGHT);
        // 为场景设置输入事件监听器
        setupInputListeners(scene);

        // 将场景设置到主舞台上
        primaryStage.setScene(scene);
        // 设置窗口关闭请求的处理器，调用 shutdown 方法
        primaryStage.setOnCloseRequest(e -> shutdown());
        // 显示窗口
        primaryStage.show();
        // 启动连接看门狗
        startConnectionWatchdog();
        // 预加载所有音效文件
        preloadSounds();
    }

    // 程序关闭时调用的方法
    // 程序关闭时调用的方法
    private void shutdown() {
        // 在关闭前保存设置
        saveSettings();
        // 将运行标志设为 false，以停止所有循环
        running = false;
        // 立即关闭网络监听线程池
        networkListenExecutor.shutdownNow();
        // 立即关闭网络发送线程池
        networkSendExecutor.shutdownNow();
        inputSendExecutor.shutdownNow();
        // 立即关闭连接管理线程池
        connectionExecutor.shutdownNow();

        // --- 关闭 FOV 线程池 ---
        fovExecutor.shutdownNow();
        staticViewportExecutor.shutdownNow();
        pcmAudioMixer.close();

        // 如果套接字存在且未关闭，则关闭它
        if (socket != null && !socket.isClosed())
            socket.close();
        // 退出 JavaFX 应用程序线程
        Platform.exit();
        // 退出 Java 虚拟机
        System.exit(0);
    }

    /**
     * 主动断开连接并返回 IP 输入主菜单。
     */
    private void disconnect() {
        System.out.println("[Disconnect] 正在主动断开连接并返回主菜单...");

        // 1. 停止运行标志和循环
        running = false;
        subtickInputTransmitter.reset();
        latestLocalInput.set(new LocalInputState(0.0, 0));
        cancelStaticDataRequests();
        completeReconnect();
        if (gameLoop != null) {
            gameLoop.stop();
        }

        // 2. 关闭套接字以终止监听线程 (socket.receive 会抛出 SocketException)
        if (socket != null && !socket.isClosed()) {
            socket.close();
        }

        // 3. 在 UI 线程上重置状态和界面
        Platform.runLater(() -> {
            // 隐藏所有游戏相关面板
            gameContainer.setVisible(false);
            lobbyPane.setVisible(false);
            settingsPane.setVisible(false);
            initialWeaponSelectorPane.setVisible(false);
            buyMenuPane.setVisible(false);
            scoreboardPane.setVisible(false);

            // 显示 IP 输入界面
            ipEntryPane.setVisible(true);
            ipEntryPane.toFront();

            // 清理核心数据结构，防止内存泄漏和状态干扰
            clientPlayers.clear();
            clientZombies.clear();
            droppedItems = Map.of();
            visualEffects.clear();
            smokePuffs.clear();
            firePatches.clear();
            pingMarkers.clear();
            clientGrenades.clear();
            thrownGrenades.clear(); // 新增：清理旧的手雷数据
            explosionEffects.clear();
            fireEmitters.clear();
            processedKillFeedIds.clear();
            currentDamageLogEntries.clear();
            keysDown.clear();

            // 停止并清理循环音效
            loopingSounds.values().forEach(javafx.scene.media.AudioClip::stop);
            loopingSounds.clear();
            pcmAudioMixer.stopAll();

            // 重置核心引用和状态
            myPlayerId = null;
            me = null;
            latestGameState = null;
            latestFullGameState = null;
            fovPoints = List.of();
            clearCachedFogLayer();
            initializedMapSignature = null;
            obstacleCacheTiles.clear();
            obstacleOverviewImage = null;
            residentStaticMapReady = false;
            clearResidentStaticAtlas();
            spectateTeammateIndex = 0; // 重置观战索引
            clientState = cs2d.client.GameClient.ClientState.CONNECTING; // 重置为初始状态

            System.out.println("[Disconnect] 已成功重置状态并返回主菜单。");
        });
    }

    // 设置客户端的状态，并相应地更新UI的可见性
    private void setClientState(cs2d.client.GameClient.ClientState newState) {
        // 如果新状态和当前状态相同，则不做任何操作
        if (clientState == newState)
            return;
        // 更新客户端状态
        clientState = newState;
        // 在 JavaFX 应用程序线程上执行UI更新
        Platform.runLater(() -> {
            // 隐藏IP输入界面
            ipEntryPane.setVisible(false);
            // 根据新状态决定大厅界面是否可见
            lobbyPane.setVisible(clientState == cs2d.client.GameClient.ClientState.LOBBY);
            // 根据新状态决定游戏主容器是否可见
            gameContainer.setVisible(clientState == cs2d.client.GameClient.ClientState.PLAYING
                    || clientState == cs2d.client.GameClient.ClientState.GAME_OVER);
            // 比赛结束直接复用 TAB 记分板；其他状态默认隐藏。
            scoreboardPane.setVisible(clientState == cs2d.client.GameClient.ClientState.GAME_OVER);
            // 隐藏购买菜单
            buyMenuPane.setVisible(false);
            // 隐藏团队死斗武器选择器
            tdmWeaponSelectorPane.setVisible(false);

            // 如果新状态是游戏结束
            if (clientState == cs2d.client.GameClient.ClientState.GAME_OVER) {
                updateScoreboardUI();
                scoreboardPane.toFront();
            }
            // else if (clientState == ClientState.PLAYING) { // 如果新状态是正在游戏
            // // 如果游戏状态不为空，并且游戏模式是团队死斗
            // if (latestGameState != null &&
            // "TEAM_DEATHMATCH".equals(getString(latestGameState, "mode"))) {
            // // 打开T阵营的初始武器选择界面
            // JsonObject meData = me.data;
            // String myTeam = getString(meData, "team");
            // openInitialWeaponSelection(myTeam); //默认是T???
            // }
            // }
        });
    }

    // region 网络 (Networking) - 代码折叠区域开始
    // 连接到服务器的方法
    private void connect() {
        // [核心修复] 彻底关闭旧 Socket，引爆还在旧 Socket 上打转的阻塞监听线程 (SocketException)，
        // 以免它永久霸占 networkListenExecutor 的单线程，导致后续接收任务永远排队。
        if (this.socket != null && !this.socket.isClosed()) {
            this.socket.close();
        }
        this.mapData = null;
        this.serverSessionId = null;
        this.initializedMapSignature = null;
        this.obstacleCacheTiles.clear();
        this.obstacleOverviewImage = null;
        this.residentStaticMapReady = false;
        clearResidentStaticAtlas();
        this.lastStateSequence.set(-1);
        this.chunkBuffers.clear();
        this.messageBatchQueue.clear();
        this.pendingStateMessages.clear();

        // [核心修复] 切断之前残留的定时握手器和心跳线程，防止重开端口导致成倍发送洪水包。
        if (this.connectionHandle != null && !this.connectionHandle.isDone()) {
            this.connectionHandle.cancel(true);
        }
        cancelStaticDataRequests();
        if (this.pingThread != null && this.pingThread.isAlive()) {
            this.pingThread.interrupt();
        }

        try {
            // 创建一个新的 DatagramSocket 用于UDP通信
            socket = new DatagramSocket();
            // 创建服务器地址对象
            serverAddress = new InetSocketAddress(serverIp, serverPort);
            // 设置运行标志为 true
            running = true;
            // 在网络监听线程池中提交监听任务
            networkListenExecutor.submit(this::listen);

            // 创建一个定时任务，每500毫秒检查一次是否已连接成功（即是否收到了自己的玩家ID）
            connectionHandle = connectionExecutor.scheduleAtFixedRate(() -> {
                // 如果还没有收到自己的玩家ID，并且程序在运行
                if (myPlayerId == null && running) {
                    // 发送一个加入游戏的请求，名字为"Connecting..."，选择观战
                    sendMessage(createJsonMessage("joinGame", "name", "Connecting...", "selection", "SPECTATOR"));
                }
            }, 0, 500, TimeUnit.MILLISECONDS); // 立即开始，每500ms执行一次

            // 启动一个新线程来等待连接成功，并在成功后取消上面的定时任务
            new Thread(() -> {
                // 当 myPlayerId 还是 null 并且程序在运行时，循环等待
                while (myPlayerId == null && running) {
                    try {
                        Thread.sleep(100);
                    } catch (InterruptedException e) {
                        break;
                    } // 等待100毫秒
                }
                // 连接成功或程序停止后，取消定时任务
                if (connectionHandle != null) {
                    connectionHandle.cancel(true);
                }
            }).start();

            // 启动一个专门用于发送ping请求的线程
            pingThread = new Thread(() -> {
                try {
                    Thread.sleep(2000); // 首次连接后等待2秒
                    while (running && !Thread.currentThread().isInterrupted()) { // 当程序在运行时，而且没有被重连打断
                        pingStartTime = System.nanoTime(); // 记录发送ping的时间
                        sendMessage(createJsonMessage("ping")); // 发送ping消息
                        Thread.sleep(2000); // 每2秒发送一次
                    }
                } catch (InterruptedException ignored) {
                } // 忽略中断异常
            });
            pingThread.setDaemon(true); // 将ping线程设置为守护线程，这样主程序退出时它也会退出
            pingThread.start(); // 启动ping线程

        } catch (IOException e) { // 捕获IO异常
            // 在 JavaFX 线程上更新UI以显示连接失败
            Platform.runLater(() -> {
                lobbyPane.setVisible(false); // 隐藏大厅
                ipEntryPane.setVisible(true); // 显示IP输入界面
                Label errorLabel = (Label) ipEntryPane.lookup("#errorLabel"); // 查找错误标签
                if (errorLabel != null) { // 如果找到了
                    errorLabel.setText("Connection Failed: " + e.getMessage()); // 显示错误信息
                }
            });
        }
    }

    private synchronized void startStaticDataRequests() {
        cancelStaticDataRequests();
        if (mapData != null)
            return;
        staticDataRequestHandle = connectionExecutor.scheduleAtFixedRate(() -> {
            if (running && myPlayerId != null && mapData == null) {
                sendMessage(createJsonMessage("request_static_data"));
            }
        }, 0, 750, TimeUnit.MILLISECONDS);
    }

    private synchronized void cancelStaticDataRequests() {
        if (staticDataRequestHandle != null) {
            staticDataRequestHandle.cancel(false);
            staticDataRequestHandle = null;
        }
    }

    // 监听来自服务器的数据包
    private void listen() {
        // 创建一个足够大的字节数组作为接收缓冲区
        byte[] buffer = new byte[1024 * 128]; // 128KB
        // 当程序在运行时，持续监听
        while (running) {
            try {
                // 创建一个数据包对象用于接收数据
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                // 阻塞等待接收数据包
                socket.receive(packet);
                if (!isExpectedServer(packet)) {
                    System.err.println("[CLIENT] 丢弃来自非当前服务器的数据包: " + packet.getSocketAddress());
                    continue;
                }
                // 将接收到的字节数据转换为UTF-8编码的字符串
                String message = new String(packet.getData(), 0, packet.getLength(), StandardCharsets.UTF_8);

                // UDP分片的JSON解析、校验、拼接和解压在接收线程完成；FX线程只消费完整消息。
                queueIncomingDatagram(message);

            } catch (SocketException e) { // 捕获套接字异常
                // 如果程序仍在运行，说明是意外关闭，打印错误
                if (running)
                    System.err.println("Socket closed, listener thread exiting.");
            } catch (Exception e) { // 捕获其他异常
                // 如果程序仍在运行，打印错误
                if (running)
                    System.err.println("Error in listen thread: " + e.getMessage());
            }
        }
    }

    private void queueIncomingDatagram(String message) {
        lastServerMessageTime = System.nanoTime();
        if (message == null)
            return;
        try {
            JsonObject parsed = gson.fromJson(message, JsonObject.class);
            if (parsed == null || !parsed.has("type"))
                return;
            if (!"chunk".equals(getString(parsed, "type"))) {
                queueParsedServerMessage(parsed);
                return;
            }

            long chunkStartTime = System.nanoTime();
            perfChunkDatagrams.increment();
            try {
                String fullMessage = assembleChunkMessage(parsed);
                if (fullMessage != null) {
                    JsonObject completed = gson.fromJson(fullMessage, JsonObject.class);
                    if (completed != null && completed.has("type")) {
                        perfCompletedChunkMessages.increment();
                        queueParsedServerMessage(completed);
                    }
                }
            } finally {
                perfTimeMsgChunkNanos.add(System.nanoTime() - chunkStartTime);
            }
        } catch (RuntimeException e) {
            System.err.println("解析服务器数据包时出错: " + e.getMessage());
        }
    }

    private void queueParsedServerMessage(JsonObject message) {
        String type = getString(message, "type");
        if ("full_update".equals(type) || "small_update".equals(type)) {
            pendingStateMessages.offer(message);
        } else {
            messageBatchQueue.offer(message);
        }
    }

    private boolean isExpectedServer(DatagramPacket packet) {
        InetSocketAddress expected = serverAddress;
        return expected != null && expected.getAddress() != null
                && expected.getPort() == packet.getPort()
                && expected.getAddress().equals(packet.getAddress());
    }

    /**
     * 启动一个持久的“看门狗”定时任务，用于检测与服务器的连接是否丢失。
     */
    private void startConnectionWatchdog() {
        // 5秒超时（纳秒）
        long disconnectTimeoutNanos = 5_000_000_000L;

        // 即使之后没有新分片到达，也会按 TTL 主动释放不完整缓存。
        connectionExecutor.scheduleAtFixedRate(this::cleanupExpiredChunkBuffers, 2, 2, TimeUnit.SECONDS);

        // 使用 connectionExecutor 安排一个重复执行的任务
        connectionExecutor.scheduleAtFixedRate(() -> {
            // 看门狗只在我们处于 "PLAYING" 状态时才激活
            if (!running || clientState != cs2d.client.GameClient.ClientState.PLAYING) {
                // 如果我们不在游戏中（例如在大厅、加载、或已触发重连），
                // 就重置计时器，以防止刚一进入游戏就立即触发断线。
                if (running) {
                    this.lastServerMessageTime = System.nanoTime();
                }
                return; // 不执行检测
            }

            // 计算自上次收到消息以来经过的时间
            long timeSinceLastMessage = System.nanoTime() - lastServerMessageTime;

            // 如果超过了超时阈值
            if (timeSinceLastMessage > disconnectTimeoutNanos) {
                // 打印错误并触发重连
                System.err.println("[Reconnect Watchdog] 超过5秒未收到服务器消息。触发断线重连！");

                // 在UI线程上显示反馈（reconnect() 也会显示）
                Platform.runLater(() -> connectionStatusLabel.setText("Connection lost. Reconnecting..."));

                // 调用重连方法
                reconnect();
            }
        }, 5, 2, TimeUnit.SECONDS); // 启动后5秒开始检查，之后每2秒检查一次
    }

    /**
     * 执行断线重连逻辑。
     * 这是一个线程安全的方法，用于清理旧连接并尝试建立新连接。
     */
    private void reconnect() {
        // 使用 AtomicBoolean 确保只有一个线程可以执行重连逻辑
        if (!isReconnecting.compareAndSet(false, true)) {
            // 如果 `isReconnecting` 已经是 true，说明另一个线程正在重连，则直接返回
            return;
        }

        System.out.println("[Reconnect] 正在启动重连程序...");

        // 1. 停止所有网络活动
        running = false; // 停止所有循环 (如 ping, sendInput)
        if (socket != null && !socket.isClosed()) {
            socket.close(); // 这将强制 listen() 线程中的 socket.receive() 抛出 SocketException 并退出
        }
        // 我们 *不* 关闭 executors，因为重连需要它们

        // 2. 清理游戏状态并在UI线程上显示反馈
        Platform.runLater(() -> {
            // 更新UI提示
            connectionStatusLabel.setText("Connection lost. Reconnecting...");
            connectionStatusLabel.setVisible(true);

            // 强制显示大厅（覆盖在游戏之上），并隐藏子菜单
            lobbyPane.setVisible(true);
            nameSelectionPane.setVisible(false);
            teamSelectionPane.setVisible(false);
            lobbyPane.toFront(); // 确保它在最上面

            // --- 彻底清理所有游戏状态 ---
            clientPlayers.clear();
            clientZombies.clear();
            droppedItems = Map.of();
            visualEffects.clear();
            smokePuffs.clear();
            firePatches.clear();
            pingMarkers.clear();
            clientGrenades.clear();
            explosionEffects.clear();
            fireEmitters.clear();
            processedKillFeedIds.clear();
            currentDamageLogEntries.clear();

            myPlayerId = null; // 关键：必须重置
            me = null;
            fovPoints = new ArrayList<>(); // 关键：清除FOV
            latestGameState = null; // 关键：清除旧状态
            latestFullGameState = null;

            // 隐藏游戏内面板
            buyMenuPane.setVisible(false);
            scoreboardPane.setVisible(false);
            tdmWeaponSelectorPane.setVisible(false);

            // 重置客户端状态到“连接中”，这将暂停看门狗（因为它只在PLAYING时运行）
            // 注意：我们不调用 setClientState()，因为它会隐藏我们想显示的 lobbyPane
            clientState = cs2d.client.GameClient.ClientState.CONNECTING;
        });

        reconnectAttempt = 0;
        scheduleReconnectAttempt(1);
    }

    private synchronized void scheduleReconnectAttempt(long delaySeconds) {
        if (!isReconnecting.get())
            return;
        reconnectRetryHandle = connectionExecutor.schedule(() -> {
            if (!isReconnecting.get())
                return;
            reconnectAttempt++;
            System.out.println("[Reconnect] 第 " + reconnectAttempt + " 次连接尝试...");
            connect();

            // connect() 内部会吞掉 Socket 创建异常，因此统一在等待欢迎包后判断成功与否。
            connectionExecutor.schedule(() -> {
                if (!isReconnecting.get())
                    return;
                if (myPlayerId != null) {
                    completeReconnect();
                    return;
                }
                long nextDelay = Math.min(8L, 1L << Math.min(reconnectAttempt, 3));
                Platform.runLater(() -> connectionStatusLabel
                        .setText("Reconnect failed. Retrying in " + nextDelay + "s..."));
                scheduleReconnectAttempt(nextDelay);
            }, 3, TimeUnit.SECONDS);
        }, delaySeconds, TimeUnit.SECONDS);
    }

    private synchronized void completeReconnect() {
        isReconnecting.set(false);
        reconnectAttempt = 0;
        if (reconnectRetryHandle != null) {
            reconnectRetryHandle.cancel(false);
            reconnectRetryHandle = null;
        }
    }

    // 处理从服务器接收到的JSON消息
    private void handleServerMessage(JsonObject json) {
        // --- 更新看门狗时间戳 ---
        this.lastServerMessageTime = System.nanoTime();
        // 启动总计时器
        long msgHandleStartTime = System.nanoTime();

        if (json == null || !json.has("type"))
            return;
        String type = getString(json, "type");

        // 使用 switch 语句根据消息类型进行分发处理
        switch (type) {
            case "map_data": // 如果是地图数据
                if (!hasCurrentSession(json))
                    break;
                // [新] 计时 [M4] Map Data
                long mapStartTime = System.nanoTime();
                String incomingMapSignature = createMapSignature(json);
                if (Objects.equals(initializedMapSignature, incomingMapSignature)) {
                    cancelStaticDataRequests();
                    System.out.println("[CLIENT] 忽略同一会话中重复到达的 map_data，复用现有地图缓存。");
                    perfTimeMsg_MapData += (System.nanoTime() - mapStartTime);
                    break;
                }
                if (json != null) {
                    System.out.println("[CLIENT DEBUG] Handling 'map_data'. Received JSON: "
                            + json.toString().substring(0, Math.min(json.toString().length(), 200)) + "...");
                    System.out.println("[CLIENT DEBUG] map_data details: Width=" + json.get("width") + ", Height="
                            + json.get("height") + ", HasObstacles=" + json.has("obstacles"));
                } else {
                    System.err.println("[CLIENT DEBUG] ERROR: Handling 'map_data' but received null JSON!");
                }
                this.mapData = json; // 更新本地地图数据
                cancelStaticDataRequests();
                initializeQuadtree();
                initializedMapSignature = incomingMapSignature;
                perfTimeMsg_MapData += (System.nanoTime() - mapStartTime); // 累加 [M4]
                break;

            case "weapon_physics_data":
                if (json.has("data")) {
                    JsonObject physicsData = json.getAsJsonObject("data");
                    // 遍历收到的所有武器键
                    for (String weaponKey : physicsData.keySet()) {
                        JsonObject weaponStats = physicsData.getAsJsonObject(weaponKey);
                        double penPower = getDouble(weaponStats, "penPower");
                        double penCost = getDouble(weaponStats, "penCost");
                        // 将数据存入我们创建的静态Map中
                        WEAPON_PHYSICS_DATA.put(weaponKey,
                                new cs2d.client.GameClient.WeaponPhysicsData(penPower, penCost));
                    }
                    System.out.println("[CLIENT] 成功加载 " + WEAPON_PHYSICS_DATA.size() + " 个武器的物理数据。");
                }
                break;

            case "small_update":
                if (!acceptStatePacket(json))
                    break;
                long smallUpStartTime = System.nanoTime();
                final JsonObject finalJsonSmall = mergeMissingStateFields(latestFullGameState, json);
                this.latestGameState = finalJsonSmall;
                // [修复] 提交到专用线程池，而不是 new Thread
                stateUpdateExecutor.submit(() -> {
                    updateStateFromSmall(finalJsonSmall);
                });

                perfTimeMsg_SmallUpdate += (System.nanoTime() - smallUpStartTime);
                break;

            case "full_update":
                if (!acceptStatePacket(json))
                    break;
                long fullUpStartTime = System.nanoTime();
                // 网络线程解析后此对象只读发布，不再在FX线程复制整棵50人JSON树。
                final JsonObject finalJsonFull = json;
                this.latestFullGameState = finalJsonFull;
                this.latestGameState = finalJsonFull;
                // [修复] 同样提交到该线程池，保证状态更新的串行安全
                stateUpdateExecutor.submit(() -> {
                    updateStateFromFull(finalJsonFull);
                });

                perfTimeMsg_FullUpdate += (System.nanoTime() - fullUpStartTime);
                break;

            case "initialInfo": // 如果是服务器发送的初始信息
                if (getInt(json, "protocolVersion") != SUPPORTED_PROTOCOL_VERSION) {
                    connectionStatusLabel.setText("Protocol version mismatch");
                    System.err.println("[CLIENT] 不支持服务器协议版本");
                    break;
                }
                String incomingSessionId = getString(json, "sessionId");
                if (incomingSessionId == null || incomingSessionId.isBlank()) {
                    System.err.println("[CLIENT] 丢弃缺少 sessionId 的 initialInfo");
                    break;
                }
                if (serverSessionId != null && !serverSessionId.equals(incomingSessionId)) {
                    lastStateSequence.set(-1);
                    subtickInputTransmitter.reset();
                    latestLocalInput.set(new LocalInputState(0.0, 0));
                    chunkBuffers.clear();
                    initializedMapSignature = null;
                    obstacleCacheTiles.clear();
                    obstacleOverviewImage = null;
                    residentStaticMapReady = false;
                    clearResidentStaticAtlas();
                }
                serverSessionId = incomingSessionId;
                myPlayerId = getString(json, "playerId"); // 获取并保存我自己的玩家ID
                if (myPlayerId == null || myPlayerId.isBlank()) {
                    System.err.println("[CLIENT] 丢弃缺少 playerId 的 initialInfo");
                    break;
                }
                setClientState(cs2d.client.GameClient.ClientState.LOBBY); // 将客户端状态切换到大厅
                connectionStatusLabel.setText("Connection Successful!"); // 更新连接状态标签
                nameSelectionPane.setVisible(true); // 显示名字选择界面
                teamSelectionPane.setVisible(false); // 隐藏队伍选择界面
                populateTeamSelection(getString(json, "mode")); // 根据游戏模式填充队伍选择界面
                sendMessage(createJsonMessage("welcome_ack", "playerId", myPlayerId));
                startStaticDataRequests();
                completeReconnect();
                break;

            case "server_info": // [新增] 处理发现的服务器信息
                String serverAddr = getString(json, "server_ip") + ":" + getInt(json, "server_port");
                discoveredServers.put(serverAddr, json);
                Platform.runLater(this::updateServerBrowserList);
                break;

            case "pong": // 如果是服务器对ping的响应
                if (!hasCurrentSession(json))
                    break;
                ping = (System.nanoTime() - pingStartTime) / 1_000_000;
                break;
            case "input_ack":
                if (hasCurrentSession(json) && json.has("ackSequence"))
                    subtickInputTransmitter.acknowledge(json.get("ackSequence").getAsLong());
                break;
        }

        // --- [新] 计时 [M5] 统一的事件处理区域 ---
        long eventStartTime = System.nanoTime();

        // 处理音效 (所有人都能听到的声音)
        if (json.has("soundEvents")) {
            json.getAsJsonArray("soundEvents").forEach(e -> {
                JsonObject s = e.getAsJsonObject();
                String soundTypeString = getString(s, "type");
                String soundName = getString(s, "soundName");
                Point2D soundPos = new Point2D(getDouble(s, "x"), getDouble(s, "y"));

                boolean isFireSound = "FIRE".equals(soundTypeString);
                boolean isReloadSound = "RELOAD".equals(soundTypeString);

                if ((isFireSound || isReloadSound) && !isPointInPolygon(soundPos, fovPoints)) {
                    cs2d.client.GameClient.ClientPlayer povSource = (me != null && getBool(me.data, "isAlive")) ? me
                            : getSpectatorTarget();
                    String sourcePlayerId = getString(s, "sourcePlayerId");

                    if (povSource != null && sourcePlayerId != null && !sourcePlayerId.isEmpty()) {
                        cs2d.client.GameClient.ClientPlayer sourcePlayer = clientPlayers.get(sourcePlayerId);

                        if (sourcePlayer != null && !sourcePlayerId.equals(povSource.id)) {
                            double distance = povSource.getPos().distance(soundPos);
                            String vizType = null;

                            if (isFireSound && distance < GUNSHOT_SOUND_VISUAL_RANGE) {
                                vizType = "FIRE";
                            } else if (isReloadSound && distance < FOOT_SOUND) {
                                vizType = "RELOAD";
                            }

                            // 找到 handleServerMessage 中的声音处理逻辑，修改判断条件：
                            if (vizType != null) {
                                // [修复] 使用 "RELOAD".equals(vizType) 替代 ==
                                if ("RELOAD".equals(vizType)) {
                                    sourcePlayer.soundRevealExpireTime = System.currentTimeMillis() + 2000; // 暴露时间: 2秒
                                } else {
                                    sourcePlayer.soundRevealExpireTime = System.currentTimeMillis() + 100; // 暴露时间: 0.1秒
                                    sourcePlayer.revealSoundType = vizType;
                                }
                            }
                        }
                    }
                }

                if ("FIRE_LOOP_START".equals(soundTypeString)) {
                    if (!loopingSounds.containsKey(soundName)) {
                        AudioClip fireLoop = sounds.get("fire_loop_1");
                        if (fireLoop != null) {
                            fireLoop.setCycleCount(AudioClip.INDEFINITE);
                            playSound(fireLoop, soundPos);
                            loopingSounds.put(soundName, fireLoop);
                        }
                    }
                } else if ("FIRE_LOOP_STOP".equals(soundTypeString)) {
                    AudioClip fireLoop = loopingSounds.remove(soundName);
                    if (fireLoop != null) {
                        fireLoop.stop();
                        playSound("fire_loop_fadeout_01", soundPos);
                    }
                } else {
                    playSound(soundName, soundPos);
                }
            });
        }

        // 处理音效 (包括爆头、扔雷语音、回合旁白)
        if (json.has("privateSoundEvents")) {
            json.getAsJsonArray("privateSoundEvents").forEach(e -> {
                JsonObject s = e.getAsJsonObject();
                if (myPlayerId != null && myPlayerId.equals(getString(s, "recipientId"))) {
                    playSound(getString(s, "soundName"), null);
                }
            });
        }

        // 处理击杀信息
        if (json.has("killFeed")) {
            processKillFeed(json.getAsJsonArray("killFeed"));
        }

        // 处理闪光弹视觉效果
        if (json.has("flashEvents")) {
            json.getAsJsonArray("flashEvents").forEach(e -> {
                JsonObject flash = e.getAsJsonObject();
                if (myPlayerId != null && myPlayerId.equals(getString(flash, "playerId"))) {
                    triggerFlashbangEffect(getLong(flash, "duration"));
                }
            });
        }

        // --- 处理伤害日志事件 ---
        if (json.has("damageLogEvents")) {
            JsonArray damageEvents = json.getAsJsonArray("damageLogEvents");
            damageEvents.forEach(msgElement -> {
                JsonObject msg = msgElement.getAsJsonObject();
                if (myPlayerId != null && myPlayerId.equals(getString(msg, "to"))) {
                    JsonObject payload = msg.getAsJsonObject("payload");

                    if (payload != null && "damage_event".equals(getString(payload, "type"))) {
                        currentDamageLogEntries.add(payload);

                        // [核心新增] 本地同步更新伤害和命中统计
                        if (me != null && me.data != null) {
                            int dmg = getInt(payload, "dmg");
                            if (dmg > 0) {
                                me.mutateData(snapshot -> {
                                    snapshot.addProperty("damageDealt", getInt(snapshot, "damageDealt") + dmg);
                                    snapshot.addProperty("totalShotsHit", getInt(snapshot, "totalShotsHit") + 1);
                                });
                                updateLocalScore(me); // 实时更新分数
                            }
                        }
                    }
                }
            });
        }

        if (json.has("pingMarkers")) {
            JsonArray pings = json.getAsJsonArray("pingMarkers");
            pingMarkers.clear();
            pings.forEach(pEl -> {
                JsonObject pData = pEl.getAsJsonObject();
                pingMarkers.put(getString(pData, "id"), pData);
            });
        } else {
            if (!pingMarkers.isEmpty()) {
                pingMarkers.clear();
            }
        }

        // 检查游戏是否结束
        if (latestGameState != null && getBool(latestGameState, "isGameOver")
                && clientState != cs2d.client.GameClient.ClientState.GAME_OVER) {
            setClientState(cs2d.client.GameClient.ClientState.GAME_OVER); // 将客户端状态设置为游戏结束
        }

        // 处理脚步声暴露事件
        if (json.has("footstepReveals")) {
            json.getAsJsonArray("footstepReveals").forEach(e -> {
                JsonObject reveal = e.getAsJsonObject();
                String recipientId = getString(reveal, "revealedToPlayerId");
                String controlledBotId = null;
                if (me != null && me.data != null && "CONTROLLING_BOT".equals(getString(me.data, "spectatorMode"))) {
                    controlledBotId = getString(me.data, "spectatorTargetId");
                }
                if ((myPlayerId != null && myPlayerId.equals(recipientId)) ||
                        (controlledBotId != null && controlledBotId.equals(recipientId))) {

                    String revealedPlayerId = getString(reveal, "revealedPlayerId");
                    cs2d.client.GameClient.ClientPlayer revealedPlayer = clientPlayers.get(revealedPlayerId);
                    if (revealedPlayer == null)
                        revealedPlayer = clientZombies.get(revealedPlayerId);

                    if (revealedPlayer != null) {
                        revealedPlayer.soundRevealExpireTime = System.currentTimeMillis() + 300; // 暴露0.3秒
                        revealedPlayer.revealSoundType = "FOOTSTEP";
                    }
                }
            });
        }

        perfTimeMsg_Events += (System.nanoTime() - eventStartTime); // 累加 [M5]
        perfTimeMsgHandling += (System.nanoTime() - msgHandleStartTime); // 累加总耗时
    }

    private boolean hasCurrentSession(JsonObject json) {
        return serverSessionId != null
                && json.has("protocolVersion")
                && getInt(json, "protocolVersion") == SUPPORTED_PROTOCOL_VERSION
                && serverSessionId.equals(getString(json, "sessionId"));
    }

    private String assembleChunkMessage(JsonObject json) {
        String id = getString(json, "id");
        int index = getInt(json, "index");
        int total = getInt(json, "total");
        String data = getString(json, "data");
        String checksum = getString(json, "checksum");

        if (serverSessionId != null && !hasCurrentSession(json)) {
            if (id != null)
                chunkBuffers.remove(id);
            return null;
        }
        if (json.has("sequence") && json.get("sequence").isJsonPrimitive()
                && json.getAsJsonPrimitive("sequence").isNumber()
                && json.get("sequence").getAsLong() <= lastStateSequence.get()) {
            if (id != null)
                chunkBuffers.remove(id);
            return null;
        }

        cleanupExpiredChunkBuffers();
        if (id == null || id.isBlank() || id.length() > 128
                || total < 1 || total > MAX_CHUNK_COUNT
                || index < 0 || index >= total
                || data == null || data.length() > MAX_CHUNK_DATA_LENGTH
                || checksum == null || !checksum.matches("[0-9a-fA-F]{1,16}")) {
            if (id != null)
                chunkBuffers.remove(id);
            System.err.println("[CLIENT] 丢弃无效 UDP 分片");
            return null;
        }

        if (!chunkBuffers.containsKey(id) && chunkBuffers.size() >= MAX_CHUNK_BUFFERS)
            evictOldestChunkBuffer();

        ChunkBuffer buffer = chunkBuffers.computeIfAbsent(id, key -> new ChunkBuffer(total, checksum));
        if (!buffer.matches(total, checksum)) {
            chunkBuffers.remove(id, buffer);
            System.err.println("[CLIENT] 丢弃元数据冲突的 UDP 分片: " + id);
            return null;
        }
        if (!buffer.addChunk(index, data))
            return null;

        chunkBuffers.remove(id, buffer);
        try {
            return buffer.getFullMessage();
        } catch (IOException e) {
            System.err.println("解析重组消息时出错: " + e.getMessage());
            return null;
        }
    }

    private boolean acceptStatePacket(JsonObject json) {
        if (!hasCurrentSession(json) || !json.has("sequence")
                || !json.get("sequence").isJsonPrimitive()
                || !json.getAsJsonPrimitive("sequence").isNumber())
            return false;
        long incoming = json.get("sequence").getAsLong();
        long previous = lastStateSequence.get();
        while (incoming > previous) {
            if (lastStateSequence.compareAndSet(previous, incoming))
                return true;
            previous = lastStateSequence.get();
        }
        return false;
    }

    /**
     * 小状态包只携带高频动态字段。保留其新值，同时从最近完整快照补回 mode、roundPhase、
     * 炸弹状态等缺失字段，避免按键和 HUD 在两个完整快照之间失去运行上下文。
     */
    static JsonObject mergeMissingStateFields(JsonObject fullSnapshot, JsonObject incrementalSnapshot) {
        JsonObject merged = new JsonObject();
        if (incrementalSnapshot != null) {
            for (Map.Entry<String, JsonElement> entry : incrementalSnapshot.entrySet())
                merged.add(entry.getKey(), entry.getValue());
        }
        if (fullSnapshot == null)
            return merged;
        for (Map.Entry<String, JsonElement> entry : fullSnapshot.entrySet()) {
            if (!merged.has(entry.getKey()))
                merged.add(entry.getKey(), entry.getValue());
        }
        return merged;
    }

    /**
     * UDP状态是可覆盖快照，不是必须逐条回放的事件。分别保留最新完整包和小包，
     * 帧边界最多消费两份并按sequence排序，避免暂停后一次处理数百份历史状态。
     */
    static final class LatestStateMailbox {
        private final AtomicReference<JsonObject> latestFull = new AtomicReference<>();
        private final AtomicReference<JsonObject> latestSmall = new AtomicReference<>();
        private final LongAdder replaced = new LongAdder();

        void offer(JsonObject state) {
            if (state == null)
                return;
            String type = getString(state, "type");
            AtomicReference<JsonObject> slot = "full_update".equals(type) ? latestFull
                    : "small_update".equals(type) ? latestSmall : null;
            if (slot == null)
                return;
            while (true) {
                JsonObject previous = slot.get();
                if (previous != null && sequenceOf(previous) >= sequenceOf(state)) {
                    replaced.increment();
                    return;
                }
                if (slot.compareAndSet(previous, state)) {
                    if (previous != null)
                        replaced.increment();
                    return;
                }
            }
        }

        List<JsonObject> drainOrdered() {
            JsonObject full = latestFull.getAndSet(null);
            JsonObject small = latestSmall.getAndSet(null);
            if (full == null)
                return small == null ? List.of() : List.of(small);
            if (small == null)
                return List.of(full);
            return sequenceOf(full) <= sequenceOf(small) ? List.of(full, small) : List.of(small, full);
        }

        int pendingCount() {
            return (latestFull.get() == null ? 0 : 1) + (latestSmall.get() == null ? 0 : 1);
        }

        long replacedThenReset() {
            return replaced.sumThenReset();
        }

        void clear() {
            latestFull.set(null);
            latestSmall.set(null);
            replaced.reset();
        }

        private static long sequenceOf(JsonObject state) {
            JsonElement sequence = state == null ? null : state.get("sequence");
            return sequence != null && sequence.isJsonPrimitive() && sequence.getAsJsonPrimitive().isNumber()
                    ? sequence.getAsLong() : Long.MIN_VALUE;
        }
    }

    /** 构建完成后整体发布，渲染线程不会看见半更新的地面物品集合。 */
    static Map<String, JsonObject> createDroppedItemSnapshot(JsonArray items) {
        if (items == null || items.isEmpty())
            return Map.of();

        Map<String, JsonObject> snapshot = new LinkedHashMap<>();
        items.forEach(element -> {
            if (!element.isJsonObject())
                return;
            JsonObject item = element.getAsJsonObject();
            String id = getString(item, "id");
            if (id != null && !id.isBlank())
                snapshot.put(id, item);
        });
        return Map.copyOf(snapshot);
    }

    // 触发闪光弹效果
    // private void triggerFlashbangEffect(long duration) {
    // this.flashBangAlpha = 1.0; // 屏幕瞬间变白（透明度设为1）
    // this.flashBangFadeEndTime = System.currentTimeMillis() + duration; //
    // 计算效果结束的时间戳
    // this.flashBangTotalDuration = duration; // 记录总时长，用于计算渐隐进度
    //
    // // 播放耳鸣音效（这是一个2D私有音效，所以位置为null）
    // playSound("flashbang_ring", null);
    // }
    private void triggerFlashbangEffect(long duration) {
        // 只截取1600x900动态Canvas。静态地图Atlas仍在底层正常显示；若对整个
        // gameRenderNode同步snapshot，会强制Prism合成/回读整张大地图并造成数秒停顿。
        if (canvas != null) {
            if (flashbangSnapshotBuffer == null) {
                flashbangSnapshotBuffer = new WritableImage(CANVAS_WIDTH, CANVAS_HEIGHT);
            }
            javafx.scene.SnapshotParameters snapshotParameters = new javafx.scene.SnapshotParameters();
            snapshotParameters.setFill(Color.TRANSPARENT);
            snapshotParameters.setViewport(new Rectangle2D(0, 0, CANVAS_WIDTH, CANVAS_HEIGHT));
            this.flashbangSnapshot = canvas.snapshot(snapshotParameters, flashbangSnapshotBuffer);
        }

        // 后续逻辑保持不变
        this.flashBangAlpha = 1.0;
        this.flashBangFadeEndTime = System.currentTimeMillis() + duration;
        this.flashBangTotalDuration = duration;

        playSound("flashbang_ring", null);
    }

    // 根据完整的游戏状态更新本地数据
    private void updateStateFromFull(JsonObject state) {
        // 更新玩家信息
        if (state.has("players") && !state.get("players").isJsonNull()) {
            Set<String> serverPlayerIds = new HashSet<>(); // 创建一个集合存储服务器上的所有玩家ID
            // 遍历服务器发来的玩家数组
            state.getAsJsonArray("players").forEach(pEl -> {
                JsonObject pData = pEl.getAsJsonObject(); // 获取玩家数据
                String id = getString(pData, "id"); // 获取玩家ID
                serverPlayerIds.add(id); // 将ID添加到集合中
                cs2d.client.GameClient.ClientPlayer cPlayer = clientPlayers.get(id); // 尝试从本地获取该玩家对象
                if (cPlayer == null) { // 如果本地不存在
                    clientPlayers.put(id, new cs2d.client.GameClient.ClientPlayer(pData, this)); // 创建一个新的玩家对象并存储
                } else { // 如果本地已存在
                    cPlayer.updateFull(pData); // 调用其完整更新方法
                }
            });
            // 移除本地存在但服务器上已不存在的玩家（例如掉线的玩家）
            clientPlayers.keySet().removeIf(id -> !serverPlayerIds.contains(id));
        }

        // 更新僵尸信息（逻辑同玩家）
        if (state.has("zombies")) {
            Set<String> serverZombieIds = new HashSet<>();

            // 在 ClientPlayer.java 中为 clientZombies 添加一个静态锁对象
            // static final Object zombieLock = new Object();
            // synchronized(zombieLock) { // (或者简单地锁 'clientZombies' 本身)
            synchronized (clientZombies) {
                state.getAsJsonArray("zombies").forEach(zEl -> {
                    JsonObject zData = zEl.getAsJsonObject();
                    String id = getString(zData, "id");
                    serverZombieIds.add(id);
                    cs2d.client.GameClient.ClientPlayer cZombie = clientZombies.get(id);
                    if (cZombie == null) {
                        clientZombies.put(id, new cs2d.client.GameClient.ClientPlayer(zData, this));
                    } else {
                        cZombie.updateFull(zData);
                    }
                });
                clientZombies.keySet().removeIf(id -> !serverZombieIds.contains(id));
            }
        }

        // 更新地上掉落的物品信息
        if (state.has("droppedItems")) {
            droppedItems = createDroppedItemSnapshot(state.getAsJsonArray("droppedItems"));
        }

        // 更新飞行中的手榴弹
        if (state.has("thrownGrenades")) {
            JsonArray grenadesFromServer = state.getAsJsonArray("thrownGrenades");
            Set<String> serverGrenadeIds = new HashSet<>();

            // 遍历服务器发来的手雷
            grenadesFromServer.forEach(gEl -> {
                JsonObject gData = gEl.getAsJsonObject();
                String id = getString(gData, "id");
                serverGrenadeIds.add(id);

                // 如果我们已经有这个手雷了，就更新它
                if (clientGrenades.containsKey(id)) {
                    clientGrenades.get(id).update(gData);
                } else {
                    // 如果是新的手雷，就创建一个新的 ClientGrenade 对象
                    clientGrenades.put(id, new cs2d.client.GameClient.ClientGrenade(gData, this));
                }
            });

            // 移除那些服务器上已经不存在的手雷（比如已经爆炸的）
            clientGrenades.keySet().removeIf(id -> !serverGrenadeIds.contains(id));
        } else {
            // 如果服务器由于为空而没有发送 thrownGrenades 字段，彻底清空客户端本地手雷，避免幽灵飞行！
            clientGrenades.clear();
        }

        // 更新火焰
        if (state.has("firePatches")) {
            Set<String> serverFireIds = new HashSet<>();
            // 将服务器传来的数据更新到 firePatches 映射中
            state.getAsJsonArray("firePatches").forEach(fEl -> {
                JsonObject fData = fEl.getAsJsonObject();
                String id = getString(fData, "id");
                serverFireIds.add(id);
                firePatches.put(id, fData);

                // 如果这是一个新的火焰，就为它创建一个发射器
                if (!fireEmitters.containsKey(id)) {
                    Point2D pos = new Point2D(getDouble(fData, "x"), getDouble(fData, "y"));
                    fireEmitters.put(id, new cs2d.client.GameClient.FireEmitter(id, pos));
                }
            });

            // 移除那些在服务器上已经消失的火焰的发射器
            fireEmitters.keySet().removeIf(id -> !serverFireIds.contains(id));
            firePatches.keySet().removeIf(id -> !serverFireIds.contains(id));

        } else {
            // 如果服务器没发来火焰数据，清空本地所有火焰
            firePatches.clear();
            fireEmitters.clear();
        }

        // 烟雾逻辑属于这里
        if (state.has("smokePuffs")) {
            // System.out.println("[客户端-接收] 成功接收到烟雾数据包, 包含颗粒数量: " +
            // state.getAsJsonArray("smokePuffs").size()); // 调试信息
            smokePuffs.clear(); // 首先，清除旧的烟雾数据
            // 然后，从服务器添加新的烟雾数据
            state.getAsJsonArray("smokePuffs").forEach(sEl -> {
                JsonObject sData = sEl.getAsJsonObject();
                smokePuffs.put(getString(sData, "id"), sData); // 添加新数据
            });
        }

        // 在最后，调用通用的状态更新方法
        updateCommonState(state);

    }

    // 根据增量的游戏状态更新本地数据
    private void updateStateFromSmall(JsonObject state) {
        // 正常更新玩家的动态数据（位置、角度等）
        if (state.has("players") && !state.get("players").isJsonNull()) {
            state.getAsJsonArray("players").forEach(pEl -> {
                JsonObject pData = pEl.getAsJsonObject();
                cs2d.client.GameClient.ClientPlayer cPlayer = clientPlayers.get(getString(pData, "id"));
                if (cPlayer != null) {
                    cPlayer.updateDynamic(pData); // 调用动态更新方法
                }
            });
        }

        // 更新僵尸的动态数据
        if (state.has("zombies")) {
            // synchronized(ClientPlayer.zombieLock) { // (或者简单地锁 'clientZombies' 本身)
            synchronized (clientZombies) {
                state.getAsJsonArray("zombies").forEach(zEl -> {
                    JsonObject zData = zEl.getAsJsonObject();
                    cs2d.client.GameClient.ClientPlayer cZombie = clientZombies.get(getString(zData, "id"));
                    if (cZombie != null) {
                        cZombie.updateDynamic(zData); // 调用动态更新方法
                    }
                });
            }
        }

        // 现在把烟雾和火焰的更新逻辑放到这里（即使是小包也可能包含这些信息）
        if (state.has("smokePuffs")) {
            // System.out.println("[客户端-接收] 成功接收到烟雾数据包, 包含颗粒数量: " +
            // state.getAsJsonArray("smokePuffs").size()); // 调试信息
            smokePuffs.clear();
            state.getAsJsonArray("smokePuffs").forEach(sEl -> {
                JsonObject sData = sEl.getAsJsonObject();
                smokePuffs.put(getString(sData, "id"), sData);
            });
        }
        if (state.has("firePatches")) {
            firePatches.clear();
            state.getAsJsonArray("firePatches").forEach(fEl -> {
                JsonObject fData = fEl.getAsJsonObject();
                firePatches.put(getString(fData, "id"), fData);
            });
        }

        // 更新飞行中的手榴弹 (需要处理小包中可能包含的情况)
        if (state.has("thrownGrenades")) {
            JsonArray grenadesFromServer = state.getAsJsonArray("thrownGrenades");
            Set<String> serverGrenadeIds = new HashSet<>();

            // 遍历服务器发来的手雷
            grenadesFromServer.forEach(gEl -> {
                JsonObject gData = gEl.getAsJsonObject();
                String id = getString(gData, "id");
                serverGrenadeIds.add(id);

                // 如果我们已经有这个手雷了，就更新它
                if (clientGrenades.containsKey(id)) {
                    clientGrenades.get(id).update(gData);
                } else {
                    // 如果是新的手雷，就创建一个新的 ClientGrenade 对象
                    clientGrenades.put(id, new cs2d.client.GameClient.ClientGrenade(gData, this));
                }
            });

            // 移除那些服务器上已经不存在的手雷（比如已经爆炸的）
            clientGrenades.keySet().removeIf(id -> !serverGrenadeIds.contains(id));
        } else {
            // 如果小包中完全没有 thrownGrenades，说明服务器端天空中没有任何手雷。彻底清空！
            clientGrenades.clear();
        }

        // 最后调用通用更新方法
        updateCommonState(state);
    }

    // 专门用于存储客户端本地状态的 Map，键是玩家ID
    private final Map<String, JsonObject> clientPlayerState = new ConcurrentHashMap<>();

    // 更新通用的状态（不区分完整或增量包）
    private void updateCommonState(JsonObject state) {
        // 首先，更新玩家的本地引用，确保 me.data 是最新的
        updateLocalPlayerRef();
        // 如果我们正在进行预测 (predictedWeaponKey 不为 null)
        if (this.predictedWeaponKey != null && me != null && me.data != null) {
            // 获取服务器刚刚确认的、你当前正拿着的武器
            String confirmedWeaponKey = getString(me.data, "weaponKey");

            // 如果服务器确认的武器 和 我们预测的武器一致了
            if (this.predictedWeaponKey.equals(confirmedWeaponKey)) {
                // 这说明我们的预测已经成功被服务器应用，“接力”完成，此时才可以安全地清除预测
                this.predictedWeaponKey = null;
            }
        }

        // --- 检查是否发生换边 ---
        if (me != null && latestGameState != null) {
            String currentTeam = getString(me.data, "team");

            // 1. 从新的 Map 中获取或创建当前玩家的本地状态对象
            JsonObject localState = clientPlayerState.computeIfAbsent(me.id, k -> {
                JsonObject json = new JsonObject();
                json.addProperty("lastTeam", currentTeam); // 首次连接，记录当前队伍
                return json;
            });

            String lastTeam = getString(localState, "lastTeam");

            // 如果队伍发生了变化 (例如，从 CT 变到 T) 并且当前是DEMO模式
            if ("DEMOLITION".equals(getString(latestGameState, "mode")) && !currentTeam.equals(lastTeam)) {

                localState.addProperty("lastTeam", currentTeam);
                Platform.runLater(() -> {
                    if (buyMenuPane != null && buyMenuPane.getCenter() != null) {
                        buyMenuPane.setCenter(null);
                        buyMenuButtons.clear();
                        undoMenuButtons.clear();
                    }
                });
            }
        }

        if (state.has("vfx")) {
            visualEffects.clear();
            state.getAsJsonArray("vfx").forEach(vfxEl -> {
                JsonObject vfx = vfxEl.getAsJsonObject();
                String type = getString(vfx, "type");

                // --- 确保 pos 在这里被定义和使用 ---
                Point2D pos = new Point2D(getDouble(vfx, "x"), getDouble(vfx, "y"));

                if ("explode_he".equals(type) || "explode_c4".equals(type)) {
                    // 如果是 C4 爆炸
                    if ("explode_c4".equals(type)) {
                        // 使用 C4 特效类，但它必须继承 ExplosionEffect
                        explosionEffects.add(
                                new cs2d.client.GameClient.C4ExplosionEffect(pos, System.currentTimeMillis() / 1000.0));
                    } else {
                        // 如果是 HE 爆炸
                        explosionEffects.add(
                                new cs2d.client.GameClient.ExplosionEffect(pos, System.currentTimeMillis() / 1000.0));
                    }
                } else {
                    // 其他类型的 VFX，按老办法处理
                    visualEffects.add(vfx);
                }
            });
        }

        buyMenuRefreshPending.set(true);
    }

    // 发送消息到服务器
    private void sendMessage(String message) {
        // 如果套接字为空、已关闭或服务器地址为空，则不发送
        if (socket == null || socket.isClosed() || serverAddress == null)
            return;
        // 在网络发送线程池中提交发送任务
        networkSendExecutor.submit(() -> {
            try {
                // 将消息字符串转换为UTF-8编码的字节数组
                byte[] buffer = message.getBytes(StandardCharsets.UTF_8);
                // 创建一个数据包，包含数据、长度和目标地址
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length, serverAddress);
                // 发送数据包
                socket.send(packet);
            } catch (IOException e) { // 捕获IO异常
                // 如果程序仍在运行，打印错误堆栈
                if (running)
                    e.printStackTrace();
            }
        });
    }

    // 启动游戏主循环
    // 启动游戏主循环
    private void startGameLoop() {
        // 如果已经有循环正在运行，先停止它
        if (gameLoop != null) {
            gameLoop.stop();
        }
        ensureInputSendLoop();
        // 创建一个 AnimationTimer，它会在每一帧被调用
        gameLoop = new AnimationTimer() {
            private long lastPerfLogTime = 0; // 用于 2 秒性能日志
            private final FramePacingMonitor framePacingMonitor =
                    new FramePacingMonitor(TARGET_RENDER_RATE, TARGET_RENDER_RATE * 4);

            // --- [新] 最终诊断变量 ---
            /** 记录上一次 handle() 方法被调用的时间戳 */
            private long lastNanoTime = 0; // [替换] 使用 System.nanoTime()
            private double perfTime_A_TotalFrameTime = 0;
            private double perfTime_B_OnFrameCodeTime = 0;
            // --- 最终诊断变量结束 ---

            // AnimationTimer 的核心方法，每帧执行
            @Override
            public void handle(long now) {
                // ClientMain 已把 JavaFX Pulse 设置为目标刷新率。每个Pulse直接绘制，
                // 避免同频的第二层截止时间过滤因亚毫秒抖动误跳过整个下一帧。

                // [新增] 局域网服务器发现广播
                if (serverBrowserPane != null && serverBrowserPane.isVisible()) {
                    broadcastDiscoveryProbe();
                }

                // [修复] 使用 JavaFX 提供的 'now' 参数。
                if (lastNanoTime == 0) {
                    lastNanoTime = now;
                    return;
                }
                long timeSinceLastHandle = now - lastNanoTime;
                lastNanoTime = now;
                framePacingMonitor.record(timeSinceLastHandle);

                // --- 计时 [A] 帧间总耗时 ---
                perfTime_A_TotalFrameTime += timeSinceLastHandle;

                // [新] 启动 [B] 帧内代码耗时的总计时器
                long onFrameCodeStartTime = System.nanoTime();

                // 在渲染帧边界应用网络状态。服务器60Hz权威模拟，165Hz渲染不由网络包到达时刻驱动。
                drainNetworkMessagesForRenderFrame();
                if (buyMenuRefreshPending.getAndSet(false)
                        && buyMenuPane != null && buyMenuPane.isVisible()) {
                    updateBuyMenuUI();
                }

                // ----------------------------------------------------
                // --- (你所有的游戏逻辑和渲染) ---
                // ----------------------------------------------------

                // FPS 计数器
                frameCount++;
                if (now - lastFpsUpdateTime >= 1_000_000_000) {
                    double fps = frameCount;
                    fpsLabel.setText(String.format("FPS: %.0f", fps));
                    frameCount = 0;
                    lastFpsUpdateTime = now;
                }

                // --- [L1] 165Hz本地输入采样；网络发送由独立60Hz线程完成 ---
                long inputStartTime = System.nanoTime();
                publishLocalInputState(updateLocalAimVisual());
                perfTimeLogic_SendInput += (System.nanoTime() - inputStartTime);

                // --- [L2] 插值/平滑 ---
                // [修复] 计算精确的 deltaTime；速度字段仍使用兼容的120Hz运动单位。
                double deltaTime = timeSinceLastHandle / 1_000_000_000.0;
                if (deltaTime <= 0 || deltaTime > 0.1)
                    deltaTime = 1.0 / 60.0; // 防御性处理

                long interpStartTime = System.nanoTime();
                updateRecoil(deltaTime); // [修复] 传入 deltaTime
                final double finalDeltaTime = deltaTime;
                clientPlayers.values().forEach(p -> p.updateRenderPosition(finalDeltaTime));
                clientZombies.values().forEach(z -> z.updateRenderPosition(finalDeltaTime));
                clientGrenades.values().forEach(g -> g.updateRenderPosition(finalDeltaTime));
                perfTimeLogic_Interpolation += (System.nanoTime() - interpStartTime);

                // --- [L3] HUD逻辑 (预绘制) ---
                long hudLogicStartTime = System.nanoTime();
                updateAndCleanKillFeed();
                triggerDamageLogDisplayIfNeeded();
                hudFrameUpdater.run();
                perfTimeLogic_HUDLogic += (System.nanoTime() - hudLogicStartTime);

                // --- 状态检查 (死亡/复活/观战) ---
                if (me != null) {
                    boolean isAliveThisFrame = getBool(me.data, "isAlive");
                    if (wasAliveLastFrame && !isAliveThisFrame)
                        spectateTeammateIndex = 0;
                    if (!wasAliveLastFrame && isAliveThisFrame) {
                        // [修复] 不再在重生时清除已处理的击杀ID。
                        // 否则服务器发送的最近5条记录会被当成新事件再次显示。
                        // processedKillFeedIds.clear();
                        killsThisLife = 0;
                    }
                    if (isAliveThisFrame && ("FREEZE_TIME".equals(getString(latestGameState, "roundPhase"))
                            || getInt(latestGameState, "nextWaveIn") == 1)) {
                        killsThisLife = 0;
                    }
                    wasAliveLastFrame = isAliveThisFrame;
                }
                boolean isSpectating = (me == null || (me != null && me.data != null && !getBool(me.data, "isAlive")));
                if (isSpectating) {
                    updateGrenadeTrails();
                } else {
                    if (!grenadeTrails.isEmpty())
                        grenadeTrails.clear();
                }

                // --- [C] 相机更新 ---
                updateCamera(deltaTime); // [新] 在绘制前更新相机

                // --- [R] 渲染 (draw) ---
                draw(deltaTime); // [新] 传入统一的 deltaTime

                // ----------------------------------------------------
                // --- (游戏逻辑和渲染结束) ---
                // ----------------------------------------------------

                // [新] 累加 [B] 帧内代码耗时
                // 我们从总的渲染时间中减去 [L] 和 [M] (因为 [M] 是在 handleServerMessage 中单独累加的，不在这里)
                // (更正：[M] 是在 handleServerMessage 中累加的，但会阻塞 handle，所以 [A] 会包含它。
                // 我们需要计算 [B] = [L] + [1] + [2] + [3] + [4])

                // (再次更正：`perfTimeTotalDraw` 已经包含了 [1,2,3,4]。
                // 我们只需要计算 `handle` 内部的总耗时)
                // --- 记分板实时更新 ---
                if (scoreboardPane.isVisible() && frameCount % 15 == 0) {
                    updateScoreboardUI();
                }

                perfTime_B_OnFrameCodeTime += (System.nanoTime() - onFrameCodeStartTime);

                // --- 性能报告逻辑 ---
                // --- 性能报告逻辑 ---
                if (now - lastPerfLogTime >= 2_000_000_000L) { // 每 2 秒
                    if (perfFrameCount > 0) {
                        // 计算平均耗时 (纳秒 -> 毫秒)

                        // [A] 帧间总耗时
                        double avg_A_TotalFrameTime = (perfTime_A_TotalFrameTime / perfFrameCount) / 1_000_000.0;
                        // [B] 帧内代码耗时
                        double avg_B_OnFrameCodeTime = (perfTime_B_OnFrameCodeTime / perfFrameCount) / 1_000_000.0;

                        // [M] 消息处理 (总)
                        double avg_M_Total = (perfTimeMsgHandling / perfFrameCount) / 1_000_000.0;

                        // [A]-[B] 是等待下一次客户端渲染截止时间/系统呈现的时间，不是FX或GC执行耗时。
                        double avgFramePacingWait = Math.max(0.0,
                                avg_A_TotalFrameTime - avg_B_OnFrameCodeTime);
                        FramePacingMonitor.Snapshot pacing = framePacingMonitor.snapshotAndReset();

                        // --- [新] 打印 [M] 的详细分解 ---
                        double avg_M1_Full = (perfTimeMsg_FullUpdate / perfFrameCount) / 1_000_000.0;
                        double avg_M2_Small = (perfTimeMsg_SmallUpdate / perfFrameCount) / 1_000_000.0;
                        double avg_M3_Chunk = (perfTimeMsgChunkNanos.sumThenReset() / (double) perfFrameCount)
                                / 1_000_000.0;
                        double avg_M4_Map = (perfTimeMsg_MapData / perfFrameCount) / 1_000_000.0;
                        double avg_M5_Events = (perfTimeMsg_Events / perfFrameCount) / 1_000_000.0;

                        // --- 打印 [B] 内部的详细分解 ---
                        double avgLogicInput = (perfTimeLogic_SendInput / perfFrameCount) / 1_000_000.0;
                        double avgLogicInterp = (perfTimeLogic_Interpolation / perfFrameCount) / 1_000_000.0;
                        double avgLogicHUD = (perfTimeLogic_HUDLogic / perfFrameCount) / 1_000_000.0;
                        double avgLogicTotal = avgLogicInput + avgLogicInterp + avgLogicHUD;

                        double avgFov = (perfTimeFovCalc / perfFrameCount) / 1_000_000.0;

                        double avgWorldObstacles = (perfTimeDrawWorld_Obstacles / perfFrameCount) / 1_000_000.0;
                        double avgWorldEntities = (perfTimeDrawWorld_Entities / perfFrameCount) / 1_000_000.0;
                        double avgWorldVFX = (perfTimeDrawWorld_VFX / perfFrameCount) / 1_000_000.0;
                        double avgWorld = avgWorldObstacles + avgWorldEntities + avgWorldVFX;

                        double avgFog = (perfTimeFogDraw / perfFrameCount) / 1_000_000.0;
                        double avgHud = (perfTimeHudDraw / perfFrameCount) / 1_000_000.0;
                        double avgTotalDraw = (perfTimeTotalDraw / perfFrameCount) / 1_000_000.0; // 这个是 draw() 的总耗时

                        long fovRequests = fovRequestCount.sumThenReset();
                        long fovCalculations = fovCalculationCount.sumThenReset();
                        long fovPublished = fovPublishedCount.sumThenReset();
                        long fovPendingReplaced = fovPendingReplacementCount.sumThenReset();
                        long fovStaleResults = fovStaleResultCount.sumThenReset();
                        long fovTotalNanos = fovTotalCalculationNanos.sumThenReset();
                        long fovQueryTotal = fovQueryNanos.sumThenReset();
                        long fovEdgeTotal = fovEdgeExtractNanos.sumThenReset();
                        long fovIntersectionTotal = fovIntersectionNanos.sumThenReset();
                        long fovCandidateTotal = fovCandidateObstacleCount.sumThenReset();
                        long fovRawVertices = fovRawVertexCount.sumThenReset();
                        long fovFinalVertices = fovFinalVertexCount.sumThenReset();
                        long fovMaxNanos = fovMaxCalculationNanos.getAndSet(0);
                        long fovLatestVertices = fovLatestFinalVertexCount.get();
                        long equipmentHudRebuilds = perfEquipmentHudRebuilds;
                        long hudVisibilityPasses = perfHudVisibilityPasses;
                        long chunkDatagrams = perfChunkDatagrams.sumThenReset();
                        long completedChunkMessages = perfCompletedChunkMessages.sumThenReset();
                        long coalescedStateMessages = pendingStateMessages.replacedThenReset();
                        int pendingStateCount = pendingStateMessages.pendingCount();
                        int queuedEventCount = messageBatchQueue.size();
                        long mediaSoundsPlayed = mediaBackedWorldSoundsPlayed.sumThenReset();
                        long mediaSoundsSuppressed = mediaBackedWorldSoundsSuppressed.sumThenReset();
                        long pcmFallbackCount = pcmFallbackPlays.sumThenReset();
                        PcmAudioMixer.Snapshot pcmSnapshot = pcmAudioMixer.snapshotAndReset();
                        perfEquipmentHudRebuilds = 0;
                        perfHudVisibilityPasses = 0;
                        double fovBackgroundAvgMs = fovCalculations == 0 ? 0.0
                                : fovTotalNanos / (double) fovCalculations / 1_000_000.0;
                        boolean staticMapLayerActive = isResidentStaticMapLayerReady();
                        String staticMapLayerMode = staticMapLayerActive ? residentStaticMapMode : "canvas";
                        int staticMapCachedTiles = obstacleCacheTiles.size();
                        int staticMapVisibleTiles = residentVisibleTileCount;
                        long staticViewportRebuilds = perfStaticViewportRebuilds;
                        long staticViewportTileBlits = perfStaticViewportTileBlits;
                        long staticViewportReplacements = perfStaticViewportBuildReplacements.sumThenReset();
                        long staticViewportBackgroundNanos = perfStaticViewportBackgroundNanos.sumThenReset();
                        ViewportBufferSet viewportBuffers = residentViewportBuffers;
                        int staticViewportWidth = viewportBuffers == null ? 0 : viewportBuffers.width;
                        int staticViewportHeight = viewportBuffers == null ? 0 : viewportBuffers.height;
                        long staticAtlasBuilds = perfStaticAtlasBuilds;
                        int staticAtlasNodes = residentObstacleAtlasNodes.size();
                        long playerSpriteDraws = perfPlayerSpriteDraws;
                        long droppedWeaponSpriteDraws = perfDroppedWeaponSpriteDraws;
                        long spriteBuilds = perfSpriteBuilds;
                        long fogRasterizations = perfFogRasterizations;
                        long fogTransformOnlyFrames = perfFogTransformOnlyFrames;
                        int playerSpriteCacheSize = playerSpriteCache.size();
                        int droppedWeaponSpriteCacheSize = droppedWeaponSpriteCache.size();
                        boolean staticViewportActive = residentViewportActive;
                        perfStaticViewportRebuilds = 0;
                        perfStaticViewportTileBlits = 0;
                        perfStaticAtlasBuilds = 0;
                        perfPlayerSpriteDraws = 0;
                        perfDroppedWeaponSpriteDraws = 0;
                        perfSpriteBuilds = 0;
                        perfFogRasterizations = 0;
                        perfFogTransformOnlyFrames = 0;

                        diagnosticsExecutor.execute(() -> {
                            RuntimePerformanceMonitor.Snapshot runtime = runtimePerformanceMonitor.snapshotAndReset();
                            System.out.println("--- 客户端性能 (最终诊断) (每 ~2s 更新) ---");
                            System.out.printf("  [A] 帧间总耗时 (Real FPS Time): \t%.3f ms (约 %d FPS)\n", avg_A_TotalFrameTime,
                                    (int) (1000.0 / avg_A_TotalFrameTime));
                            System.out.printf("  [B] 帧内代码 (Code in handle()): \t%.3f ms\n", avg_B_OnFrameCodeTime);
                            System.out.printf("  [M] 消息处理 (handleServerMessage): \t%.3f ms\n", avg_M_Total);
                            System.out.printf("  [PACE] 帧外间隔 (目标等待 + Pulse/Prism/系统调度): \t%.3f ms\n",
                                    avgFramePacingWait);
                            System.out.printf("  [REAL] 目标 %d Hz | 实际 %.1f FPS | 1%% Low %.1f FPS | p99 %.3f ms | 最大 %.3f ms | 严重迟帧 %d/%d\n",
                                    TARGET_RENDER_RATE, pacing.observedFps(), pacing.onePercentLowFps(),
                                    pacing.p99Millis(), pacing.maxMillis(), pacing.severelyLateFrames(),
                                    pacing.sampleCount());
                            System.out.printf("  [RUNTIME] 进程CPU %.1f%% | FX线程CPU %.1fms (%s) | Prism线程CPU %.1fms (%s)%n",
                                    runtime.processCpuPercent(), runtime.fxThreadCpuMillis(), runtime.fxThreadState(),
                                    runtime.renderThreadCpuMillis(), runtime.renderThreadState());
                            System.out.printf("            GC %d次 / 停顿%dms | Heap %.1f/%.1f MiB%n",
                                    runtime.gcCollections(), runtime.gcPauseMillis(),
                                    runtime.heapUsedMiB(), runtime.heapCommittedMiB());
                            System.out.printf("  [STATIC-MAP] active=%s | mode=%s | visibleTiles=%d/%d%n",
                                    staticMapLayerActive, staticMapLayerMode,
                                    staticMapVisibleTiles, staticMapCachedTiles);
                            System.out.printf("  [STATIC-VIEWPORT] 重建 %d | 合并块 %d | active=%s%n",
                                    staticViewportRebuilds, staticViewportTileBlits, staticViewportActive);
                            System.out.printf("                    后台 %.3f ms | 覆盖请求 %d | 缓冲 %dx%d%n",
                                    staticViewportRebuilds == 0 ? 0.0
                                            : staticViewportBackgroundNanos / (double) staticViewportRebuilds / 1_000_000.0,
                                    staticViewportReplacements, staticViewportWidth, staticViewportHeight);
                            System.out.printf("  [STATIC-ATLAS] 启动期构建 %d | 节点 %d | 运动中重建 0%n",
                                    staticAtlasBuilds, staticAtlasNodes);
                            System.out.printf("  [SPRITE-CACHE] 玩家绘制 %d | 地面武器绘制 %d | 新建纹理 %d"
                                            + " | 玩家缓存 %d | 武器缓存 %d%n",
                                    playerSpriteDraws, droppedWeaponSpriteDraws, spriteBuilds,
                                    playerSpriteCacheSize, droppedWeaponSpriteCacheSize);
                            System.out.printf("  [FOG-GEOMETRY] 顶点更新 %d | 仅变换 %d | 复用率 %.1f%%%n",
                                    fogRasterizations, fogTransformOnlyFrames,
                                    fogRasterizations + fogTransformOnlyFrames == 0 ? 0.0
                                            : fogTransformOnlyFrames * 100.0
                                                    / (fogRasterizations + fogTransformOnlyFrames));
                            System.out.println("  --- 帧内耗时 [B] 的详细分解 ---");
                            System.out.printf("      [L] 游戏逻辑 (Logic): \t\t%.3f ms\n", avgLogicTotal);
                            System.out.printf("      [R] 渲染总耗时 (draw()): \t%.3f ms\n", avgTotalDraw);
                            System.out.println("  --- (渲染 [R] 的详细分解) ---");
                            System.out.printf("          [1] FOV计算: \t\t%.3f ms\n", avgFov);
                            System.out.printf("          [2] 世界渲染: \t\t%.3f ms\n", avgWorld);
                            System.out.printf("          [3] 迷雾绘制: \t\t%.3f ms\n", avgFog);
                            System.out.printf("          [4] HUD 绘制: \t\t%.3f ms\n", avgHud);
                            System.out.printf("  [FOV-BG] 请求 %d | 实算 %d | 发布 %d | 待算覆盖 %d | 过期结果 %d\n",
                                    fovRequests, fovCalculations, fovPublished, fovPendingReplaced, fovStaleResults);
                            System.out.printf("           后台耗时 avg %.3f ms / max %.3f ms"
                                            + " | 查询 %.3f ms | 边提取 %.3f ms | 求交 %.3f ms\n",
                                    fovBackgroundAvgMs, fovMaxNanos / 1_000_000.0,
                                    fovCalculations == 0 ? 0.0 : fovQueryTotal / (double) fovCalculations / 1_000_000.0,
                                    fovCalculations == 0 ? 0.0 : fovEdgeTotal / (double) fovCalculations / 1_000_000.0,
                                    fovCalculations == 0 ? 0.0
                                            : fovIntersectionTotal / (double) fovCalculations / 1_000_000.0);
                            System.out.printf("           候选障碍 avg %.1f | 顶点 avg %.1f -> %.1f | 最新发布顶点 %d\n",
                                    fovCalculations == 0 ? 0.0 : fovCandidateTotal / (double) fovCalculations,
                                    fovCalculations == 0 ? 0.0 : fovRawVertices / (double) fovCalculations,
                                    fovCalculations == 0 ? 0.0 : fovFinalVertices / (double) fovCalculations,
                                    fovLatestVertices);
                            System.out.printf("  [HUD-CACHE] 装备节点重建 %d | 全局可见性遍历 %d\n",
                                    equipmentHudRebuilds, hudVisibilityPasses);
                            System.out.printf("  [NET-CHUNK] UDP分片 %d | 完整消息 %d | 后台拼包 %.3f ms/帧\n",
                                    chunkDatagrams, completedChunkMessages, avg_M3_Chunk);
                            System.out.printf("  [NET-STATE] 覆盖旧快照 %d | 待消费状态 %d | 排队事件 %d\n",
                                    coalescedStateMessages, pendingStateCount, queuedEventCount);
                            System.out.printf("  [AUDIO-MEDIA] 播放 %d | 防重叠丢弃 %d\n",
                                    mediaSoundsPlayed, mediaSoundsSuppressed);
                            System.out.printf("  [AUDIO-PCM] 请求 %d | 已混音 %d | 活动 %d | 峰值 %d | 排队 %d"
                                            + " | JavaFX回退 %d | running=%s%n",
                                    pcmSnapshot.requestedVoices(), pcmSnapshot.mixedVoices(),
                                    pcmSnapshot.activeVoices(), pcmSnapshot.peakVoices(), pcmSnapshot.queuedVoices(),
                                    pcmFallbackCount, pcmSnapshot.running());
                            System.out.println("  --- (消息处理 [M] 的详细分解) ---");
                            System.out.printf("      [M1] Full Update: \t%.3f ms\n", avg_M1_Full);
                            System.out.printf("      [M2] Small Update: \t%.3f ms\n", avg_M2_Small);
                            System.out.printf("      [M3] Chunk: \t\t%.3f ms\n", avg_M3_Chunk);
                            System.out.printf("      [M4] Map Data: \t\t%.3f ms\n", avg_M4_Map);
                            System.out.printf("      [M5] Events: \t\t%.3f ms\n", avg_M5_Events);
                            System.out.println("----------------------------------------");
                        });

                        // 重置所有累加器
                        perfTime_A_TotalFrameTime = 0;
                        perfTime_B_OnFrameCodeTime = 0;

                        perfTimeTotalDraw = 0;
                        perfTimeFovCalc = 0;
                        perfTimeDrawWorld_Obstacles = 0;
                        perfTimeDrawWorld_Entities = 0;
                        perfTimeDrawWorld_VFX = 0;
                        perfTimeFogDraw = 0;
                        perfTimeHudDraw = 0;
                        perfTimeLogic_SendInput = 0;
                        perfTimeLogic_Interpolation = 0;
                        perfTimeLogic_HUDLogic = 0;

                        perfTimeMsgHandling = 0;
                        perfTimeMsg_FullUpdate = 0;
                        perfTimeMsg_SmallUpdate = 0;
                        perfTimeMsg_MapData = 0;
                        perfTimeMsg_Events = 0;

                        perfFrameCount = 0;
                    }
                    lastPerfLogTime = now; // 重置2秒计时器
                }
            }
        };
        gameLoop.start(); // 启动 AnimationTimer
    }

    /**
     * [已修改] 绘图主循环，现在接收统一的 deltaTime
     */
    private void draw(double deltaTime) {
        // [新] 1. 启动总计时器
        long drawStartTime = System.nanoTime();

        // 清除整个画布
        gc.clearRect(0, 0, CANVAS_WIDTH, CANVAS_HEIGHT);
        if (hudGc != null) {
            hudGc.setTransform(1, 0, 0, 1, 0, 0);
            hudGc.clearRect(0, 0, CANVAS_WIDTH, CANVAS_HEIGHT);
        }
        // 如果地图数据为空，或者客户端不在游戏/游戏结束状态
        if (mapData == null || (clientState != cs2d.client.GameClient.ClientState.PLAYING
                && clientState != cs2d.client.GameClient.ClientState.GAME_OVER)) {
            gc.setFill(BACKGROUND_DARK); // 设置填充色为深色背景
            gc.fillRect(0, 0, CANVAS_WIDTH, CANVAS_HEIGHT); // 绘制背景
            clearCachedFogLayer();

            // [新] 在退出前累加耗时
            long drawEndTime = System.nanoTime();
            perfTimeTotalDraw += (drawEndTime - drawStartTime);
            perfFrameCount++; // 即使是空帧也要计数
            return; // 结束绘制
        }

        updateResidentStaticMapLayer();

        // [修复] 不再在这里计算 deltaTime，直接使用传入的参数

        // --- [新] 2. 计时 FOV 计算 ---
        long fovStartTime = System.nanoTime();
        // [注意] updateCamera 已经在 AnimationTimer.handle 中先被调用了，这里无需重复调用
        calculateFOVIfNeeded(); // 这个方法负责计算 fovPoints
        // 本帧所有可见性判定与迷雾绘制共用同一个不可变快照，避免后台FOV在半帧中切换。
        List<Point2D> frameFovPoints = this.fovPoints;
        long fovEndTime = System.nanoTime();
        perfTimeFovCalc += (fovEndTime - fovStartTime); // 累加 [1]

        // --- 计算相机视野范围 (用于特效剔除) ---
        Point2D topLeftWorld = camera.screenToWorld(0, 0);
        Point2D bottomRightWorld = camera.screenToWorld(CANVAS_WIDTH, CANVAS_HEIGHT);
        Rectangle2D cameraViewBoundsForEffects = new Rectangle2D(
                topLeftWorld.getX(), topLeftWorld.getY(),
                bottomRightWorld.getX() - topLeftWorld.getX(),
                bottomRightWorld.getY() - topLeftWorld.getY());

        // --- [新] 3. 世界坐标绘制 (拆分计时) ---
        gc.save();
        camera.applyTransform(gc); // 应用相机变换

        // 3a. 绘制世界物体 (障碍物 + 实体)
        // [注意] drawWorldObjects() 方法现在会 *内部* 累加 [2a] 和 [2b]
        drawWorldObjects(frameFovPoints);

        // 3c. 绘制特效 (VFX)
        long vfxStartTime = System.nanoTime();

        // 更新和绘制可见的火焰发射器
        fireEmitters.values().forEach(emitter -> {
            if (cameraViewBoundsForEffects.contains(emitter.position)) {
                emitter.update(deltaTime);
                emitter.draw(gc);
            }
        });

        // 绘制可见的烟雾颗粒
        gc.save();
        smokePuffs.values().forEach(smokePuff -> {
            double x = getDouble(smokePuff, "x");
            double y = getDouble(smokePuff, "y");
            double radius = getDouble(smokePuff, "radius");
            if (cameraViewBoundsForEffects.intersects(x - radius, y - radius, radius * 2, radius * 2)) {
                drawSmokePuff(smokePuff);
            }
        });
        gc.restore();

        // 更新和绘制可见的手雷爆炸效果
        explosionEffects.removeIf(cs2d.client.GameClient.ExplosionEffect::isDead);
        for (cs2d.client.GameClient.ExplosionEffect effect : explosionEffects) {
            if (cameraViewBoundsForEffects.contains(effect.position)) {
                effect.update(deltaTime);
                effect.draw(gc, camera);
            }
        }

        // 绘制其他的简单视觉特效 (drawVFX 内部已有基于视野多边形的精确剔除)
        visualEffects.forEach(this::drawVFX);

        long vfxEndTime = System.nanoTime();
        perfTimeDrawWorld_VFX += (vfxEndTime - vfxStartTime); // 累加 [2c]

        gc.restore(); // 结束世界坐标绘制

        // --- 4. 计时迷雾图层更新 ---
        long fogStartTime = System.nanoTime();
        updateCachedFogLayer(frameFovPoints);
        long fogEndTime = System.nanoTime();
        perfTimeFogDraw += (fogEndTime - fogStartTime); // 累加 [3]

        // --- 5. 计时 HUD 绘制 ---
        long hudStartTime = System.nanoTime();
        GraphicsContext worldGc = gc;
        if (hudGc != null)
            gc = hudGc;
        try {
            gc.save();
            drawCrosshairAndAimLine(); // 绘制准星
            if (me != null && getBool(me.data, "isAlive") && "follow".equals(cameraMode)) {
                drawOffscreenIndicators();
            }
            gc.restore();

            // 最终覆盖层: 闪光弹效果
            drawFlashbangEffect();
        } finally {
            gc = worldGc;
        }
        long hudEndTime = System.nanoTime();
        perfTimeHudDraw += (hudEndTime - hudStartTime); // 累加 [4]

        // --- 6. 累加总耗时 ---
        perfTimeTotalDraw += (hudEndTime - drawStartTime); // 总耗时
        perfFrameCount++; // 帧计数+1，用于计算平均值
    }

    private record FovRequest(Point2D sourcePos, double sourceAngle,
            List<JsonObject> dynamicObstacles) {
    }

    private record FovComputationResult(List<Point2D> points, long totalNanos, long queryNanos,
            long edgeExtractNanos, long intersectionNanos, int candidateObstacles,
            int rawVertices, int finalVertices) {
    }

    /** JavaFX线程只发布最新视角；单一后台worker顺序计算，并自动跳过中间过期请求。 */
    private void calculateFOVIfNeeded() {
        cs2d.client.GameClient.ClientPlayer fovSource = null; // 视野的来源 (玩家自己、观战目标或夺舍的Bot)

        // --- 1. 确定视野源 (逻辑不变) ---
        if (me != null) {
            if (getBool(me.data, "isAlive")) {
                fovSource = me;
            } else {
                String spectateMode = getString(me.data, "spectatorMode");
                if ("CONTROLLING_BOT".equals(spectateMode)) {
                    String controlledBotId = getString(me.data, "spectatorTargetId");
                    if (controlledBotId != null) {
                        fovSource = clientPlayers.get(controlledBotId);
                    }
                }
                if (fovSource == null) {
                    fovSource = getSpectatorTarget();
                }
            }
        }

        // --- 2. 基本检查 (逻辑不变) ---
        if (fovSource == null || !getBool(fovSource.data, "isAlive") || mapData == null) {
            if (!fovPoints.isEmpty()) {
                fovPoints = new ArrayList<>(); // [修改] 安全地清空
            }
            return;
        }

        // --- 3. 计算当前位置和目标位置 (逻辑不变) ---
        Point2D sourcePos = new Point2D(fovSource.renderX, fovSource.renderY);
        double sourceAngle = fovSource.angle;

        // computeFov真正使用的是sourceAngle，不是鼠标的世界坐标。相机平移会改变
        // lookAtPos但不会改变射线方向，旧判断会因此制造大量无意义计算。
        if (sourcePos.equals(lastPlayerPosForFOV)
                && Double.doubleToLongBits(sourceAngle) == Double.doubleToLongBits(lastSourceAngleForFOV)) {
            return; // 跳过计算
        }

        // --- 5. 收集动态障碍物 (逻辑不变) ---
        List<JsonObject> dynamicObstacles = new ArrayList<>();
        smokePuffs.values().forEach(smokePuff -> {
            JsonObject smokeObstacle = new JsonObject();
            double radius = getDouble(smokePuff, "radius");
            smokeObstacle.addProperty("type", "ELLIPSE");
            smokeObstacle.addProperty("x", getDouble(smokePuff, "x") - radius);
            smokeObstacle.addProperty("y", getDouble(smokePuff, "y") - radius);
            smokeObstacle.addProperty("w", radius * 2);
            smokeObstacle.addProperty("h", radius * 2);
            dynamicObstacles.add(smokeObstacle);
        });

        FovRequest request = new FovRequest(sourcePos, sourceAngle,
                List.copyOf(dynamicObstacles));
        FovRequest replaced = pendingFovRequest.getAndSet(request);
        fovRequestCount.increment();
        if (replaced != null)
            fovPendingReplacementCount.increment();

        // 只有请求真正进入latest-wins邮箱后才更新缓存，避免快速转头时把未计算角度误标为已完成。
        lastPlayerPosForFOV = sourcePos;
        lastSourceAngleForFOV = sourceAngle;
        ensureFovWorkerRunning();
    }

    private void ensureFovWorkerRunning() {
        if (!fovWorkerRunning.compareAndSet(false, true))
            return;
        try {
            fovExecutor.execute(this::drainLatestFovRequests);
        } catch (RejectedExecutionException ignored) {
            fovWorkerRunning.set(false);
        }
    }

    private void drainLatestFovRequests() {
        try {
            FovRequest request;
            while ((request = pendingFovRequest.getAndSet(null)) != null) {
                FovComputationResult result = computeFov(request);
                recordFovComputation(result);

                // 单worker天然按请求开始顺序完成。即使计算期间到达了更新角度，当前结果
                // 也是目前最新的完整画面，必须立即发布；旧逻辑在持续转头时会丢掉90%以上
                // 的完成结果，使视觉FOV只剩个位数到二十余Hz。
                fovPoints = result.points();
                fovPublishedCount.increment();
                fovLatestFinalVertexCount.set(result.finalVertices());
            }
        } finally {
            fovWorkerRunning.set(false);
            // 处理“读到空邮箱”和“worker置空”之间刚到达的请求。
            if (pendingFovRequest.get() != null)
                ensureFovWorkerRunning();
        }
    }

    private void recordFovComputation(FovComputationResult result) {
        fovCalculationCount.increment();
        fovTotalCalculationNanos.add(result.totalNanos());
        fovQueryNanos.add(result.queryNanos());
        fovEdgeExtractNanos.add(result.edgeExtractNanos());
        fovIntersectionNanos.add(result.intersectionNanos());
        fovCandidateObstacleCount.add(result.candidateObstacles());
        fovRawVertexCount.add(result.rawVertices());
        fovFinalVertexCount.add(result.finalVertices());
        fovMaxCalculationNanos.accumulateAndGet(result.totalNanos(), Math::max);
    }

    /**
     * 绘制所有世界物体
     * 此版本从 Quadtree 查询 StaticObstacle 对象。
     */
    private void drawWorldObjects(List<Point2D> frameFovPoints) {
        // --- A. 计算相机视野的世界坐标范围 ---
        Point2D topLeftWorld = camera.screenToWorld(0, 0);
        Point2D bottomRightWorld = camera.screenToWorld(CANVAS_WIDTH, CANVAS_HEIGHT);
        Rectangle2D cameraViewBounds = new Rectangle2D(
                topLeftWorld.getX(), topLeftWorld.getY(),
                bottomRightWorld.getX() - topLeftWorld.getX(),
                bottomRightWorld.getY() - topLeftWorld.getY());

        // 提前提取相机的双精度边界，用于后续所有实体的高效剔除运算，完全避免对象创建
        double minX = topLeftWorld.getX();
        double minY = topLeftWorld.getY();
        double maxX = bottomRightWorld.getX();
        double maxY = bottomRightWorld.getY();

        // --- [核心优化] C. 绘制障碍物分块缓存 ---
        long obstacleStartTime = System.nanoTime();
        if (!isResidentStaticMapLayerReady() || "tiles".equals(residentStaticMapMode)) {
            // A/B回退路径：保持原单Canvas静态地图绘制行为。
            gc.setFill(CARD_BACKGROUND);
            gc.fillRect(0, 0, getDouble(mapData, "width"), getDouble(mapData, "height"));

            if ("full".equals(cameraMode) && obstacleOverviewImage != null) {
                gc.drawImage(obstacleOverviewImage, 0, 0,
                        getDouble(mapData, "width"), getDouble(mapData, "height"));
            } else if (!obstacleCacheTiles.isEmpty()) {
                for (ObstacleCacheTile tile : obstacleCacheTiles) {
                    if (cameraViewBounds.intersects(tile.bounds()))
                        gc.drawImage(tile.image(), tile.x(), tile.y());
                }
            } else if (mapData != null && mapData.has("obstacles")) {
                gc.setFill(Color.web("#4a5568"));
                JsonArray allObstacles = mapData.getAsJsonArray("obstacles");
                allObstacles.forEach(obsEl -> {
                    if (obsEl.isJsonObject()) {
                        JsonObject obs = obsEl.getAsJsonObject();
                        Rectangle2D bounds = getObstacleBounds(obs);
                        if (bounds != null && cameraViewBounds.intersects(bounds)) {
                            drawObstacle(obs);
                        }
                    }
                });
                System.err.println("[警告] 障碍物分块缓存为空，正在回退到慢速绘制！");
            }
        }
        perfTimeDrawWorld_Obstacles += (System.nanoTime() - obstacleStartTime); // 累加 [2a] (现在应该接近0)
        // --- [优化结束] ---

        // --- D. 绘制【可见的】动态物体 ---
        long entityStartTime = System.nanoTime();

        // 绘制可见的掉落物
        droppedItems.values().forEach(item -> {
            // [修复] 消除 new Rectangle2D，使用纯数学包围盒检测
            double itemX = getDouble(item, "x");
            double itemY = getDouble(item, "y");
            // 1. 相机视口剔除 (掉落物宽高的半径相当于 10)
            if (itemX + 10 > minX && itemX - 10 < maxX &&
                    itemY + 10 > minY && itemY - 10 < maxY) {
                // 2. [新增] 视野多边形剔除: 只有玩家视野内才显示 (像人一样)
                if (isCircleVisibleInPolygon(new Point2D(itemX, itemY), 10, frameFovPoints)) {
                    drawDroppedItem(item);
                }
            }
        });

        // 绘制安放的 C4 (如果可见)
        if (latestGameState != null && getBool(latestGameState, "bombPlanted")) {
            JsonObject pos = latestGameState.getAsJsonObject("bombPosition");
            // [修复] 消除 new Rectangle2D
            double bombX = getDouble(pos, "x");
            double bombY = getDouble(pos, "y");
            // C4 半径相当于 7.5
            if (bombX + 7.5 > minX && bombX - 7.5 < maxX &&
                    bombY + 7.5 > minY && bombY - 7.5 < maxY) {
                // [新增] 只有在玩家视野内才显示 C4 (像人一样)
                if (isCircleVisibleInPolygon(new Point2D(bombX, bombY), 7.5, frameFovPoints)) {
                    drawPlantedBomb(getDouble(latestGameState, "bombTimer"));
                }
            }
        }

        // --- E. 绘制【可见的】角色和标记 ---

        // 绘制可见的手雷 (用 contains 检查点，contains 方法本身不产生新对象)
        clientGrenades.values().forEach(grenade -> {
            if (cameraViewBounds.contains(grenade.renderX, grenade.renderY)) {
                drawThrownGrenade(grenade);
            }
        });
        // (火焰和烟雾的绘制在 draw() 主循环中处理，这里不需要)

        // 绘制可见的声音标记
        drawSoundVisualizations(); // (这个方法内部应该有自己的可见性/过期处理)

        // 绘制可见的玩家和僵尸
        clientPlayers.values().forEach(p -> {
            // [修复] 消除 new Rectangle2D
            if (p.renderX + PLAYER_SIZE > minX && p.renderX - PLAYER_SIZE < maxX &&
                    p.renderY + PLAYER_SIZE > minY && p.renderY - PLAYER_SIZE < maxY) {
                drawVisibleCharacter(p);
            }
        });

        // 遍历时加锁
        synchronized (clientZombies) {
            clientZombies.values().forEach(z -> {
                // [修复] 消除 new Rectangle2D
                if (z.renderX + PLAYER_SIZE > minX && z.renderX - PLAYER_SIZE < maxX &&
                        z.renderY + PLAYER_SIZE > minY && z.renderY - PLAYER_SIZE < maxY) {
                    drawVisibleCharacter(z);
                }
            });
        }

        // 绘制可见的标点 (contains 方法不产生对象)
        pingMarkers.values().forEach(marker -> {
            if (cameraViewBounds.contains(getDouble(marker, "x"), getDouble(marker, "y"))) {
                drawPingMarker(marker);
            }
        });

        // --- F. 绘制观战时的手雷轨迹 ---
        boolean isSpectating = (me == null || (me.data != null && !getBool(me.data, "isAlive")));
        if (isSpectating) {
            drawGrenadeTrails();
        }
        perfTimeDrawWorld_Entities += (System.nanoTime() - entityStartTime); // 累加 [2b]
    }

    /**
     * 根据从服务器收到的标点数据，绘制一个带有动画效果的倒三角标点。
     */
    private void drawPingMarker(JsonObject marker) {
        // 安全检查：如果 "me" 对象或其数据为空，或者标点数据没有 "team" 字段，则不绘制
        if (me == null || me.data == null || !marker.has("team")) {
            return;
        }

        // 队伍判断：只有同队的标点才可见
        String myTeam = getString(me.data, "team"); // 获取我的队伍
        String markerTeam = getString(marker, "team"); // 获取标点的队伍
        if (!myTeam.equals(markerTeam)) { // 如果不是同一队
            return; // 不绘制
        }

        double remaining = getDouble(marker, "remaining"); // 获取剩余显示时间
        double duration = getDouble(marker, "duration"); // 获取总显示时间

        // 如果服务器由于某些原因没有发送 duration，提供一个默认值以避免除以零
        if (duration <= 0) {
            duration = 2000.0; // 使用客户端的备用默认值 (2秒)
        }

        // 计算显示进度 (从 1.0 到 0.0)
        double progress = remaining / duration;
        if (progress <= 0) { // 如果进度小于等于0，说明已结束
            return; // 不绘制
        }

        // 透明度与进度挂钩，实现渐隐
        double alpha = progress;
        // 使用 sin 函数创建一个呼吸/脉冲的缩放效果
        double scale = 1.0 + Math.sin((1.0 - progress) * Math.PI * 2) * 0.1;

        double x = getDouble(marker, "x"); // 获取标点的X坐标
        double y = getDouble(marker, "y"); // 获取标点的Y坐标

        gc.save(); // 保存状态
        gc.setGlobalAlpha(alpha); // 设置全局透明度

        // 计算倒三角的顶点坐标
        double size = 20 * scale; // 基础大小乘以缩放系数
        double[] xPoints = { x, x - size / 2, x + size / 2 }; // X坐标数组
        double[] yPoints = { y + size / 2, y - size / 2, y - size / 2 }; // Y坐标数组

        gc.setFill(PRIMARY_BLUE); // 设置填充色为蓝色
        gc.setStroke(Color.WHITE); // 设置描边色为白色
        gc.setLineWidth(2); // 设置线宽

        gc.fillPolygon(xPoints, yPoints, 3); // 填充多边形
        gc.strokePolygon(xPoints, yPoints, 3); // 描边多边形

        gc.restore(); // 恢复状态
    }

    /**
     * 绘制地上的火焰效果
     */
    private void drawFirePatch(JsonObject fire) {
        double x = getDouble(fire, "x"); // 获取火焰中心的X坐标
        double y = getDouble(fire, "y"); // 获取火焰中心的Y坐标
        double baseRadius = getDouble(fire, "radius"); // 获取火焰的基础半径

        // 通过随机变化半径和透明度，模拟火焰的动态闪烁效果
        double flickerRadius = baseRadius * (0.8 + Math.random() * 0.4); // 半径在80%到120%之间随机变化
        double alpha = 0.5 + Math.random() * 0.3; // 透明度在0.5到0.8之间随机变化

        // 绘制外层火光 (更透明的红色)
        gc.setFill(Color.rgb(255, 69, 0, alpha * 0.5)); // 设置颜色为半透明的橙红色
        // 绘制一个椭圆代表外层火光
        gc.fillOval(x - flickerRadius, y - flickerRadius, flickerRadius * 2, flickerRadius * 2);

        // 绘制内层火焰 (更亮的橘黄色)
        gc.setFill(Color.rgb(255, 165, 0, alpha)); // 设置颜色为不透明的橘黄色
        // 绘制一个较小的椭圆代表内层火焰
        gc.fillOval(x - flickerRadius * 0.6, y - flickerRadius * 0.6, flickerRadius * 1.2, flickerRadius * 1.2);
    }

    // 绘制飞行中的手榴弹
    private void drawThrownGrenade(cs2d.client.GameClient.ClientGrenade grenade) {
        // 使用平滑的 renderX 和 renderY
        double x = grenade.renderX;
        double y = grenade.renderY;

        gc.setFill(Color.DARKSLATEGRAY);
        gc.fillOval(x - 5, y - 5, 10, 10);
    }

    // 绘制烟雾颗粒
    private void drawSmokePuff(JsonObject smoke) {
        double x = getDouble(smoke, "x"); // 获取烟雾颗粒的X坐标
        double y = getDouble(smoke, "y"); // 获取烟雾颗粒的Y坐标
        double radius = getDouble(smoke, "radius"); // 获取半径
        long remaining = getLong(smoke, "remaining"); // 获取剩余持续时间

        // 直接使用硬编码的常量值，因为它是在服务端定义的
        final long DURATION_MS = 15000; // 烟雾总持续时间

        // 计算透明度，使其在开始时快速出现，然后随时间线性减弱
        double alpha = Math.min(1.0, (remaining / (double) DURATION_MS) * 1.5) * 0.7;

        gc.setGlobalAlpha(alpha); // 设置全局透明度
        gc.setFill(Color.LIGHTGRAY); // 设置填充色为浅灰色
        gc.fillOval(x - radius, y - radius, radius * 2, radius * 2); // 绘制圆形烟雾
    }

    // 绘制闪光弹效果
    // private void drawFlashbangEffect() {
    // if (flashBangAlpha > 0) { // 如果闪光效果的透明度大于0
    // long currentTime = System.currentTimeMillis(); // 获取当前时间
    // long remainingTime = flashBangFadeEndTime - currentTime; // 计算剩余时间
    //
    // if (remainingTime <= 0) { // 如果时间到了
    // // 效果结束
    // flashBangAlpha = 0; // 透明度设为0
    // } else {
    // // 根据剩余时间的百分比，来计算当前的透明度
    // // 效果是：刚被闪时全白，然后效果随时间线性减弱
    // flashBangAlpha = (double) remainingTime / flashBangTotalDuration;
    // }
    //
    // if (flashBangAlpha > 0) { // 如果透明度仍然大于0
    // // 为了让效果更“闪”，可以让最白的时候更白（透明度乘以1.5，但不超过1）
    // double effectPower = Math.min(1.0, flashBangAlpha * 1.5);
    //
    // gc.setFill(Color.rgb(128, 128, 128, effectPower)); // 设置为半透明的中度灰色
    // gc.fillRect(0, 0, CANVAS_WIDTH, CANVAS_HEIGHT); // 覆盖整个画布
    // }
    // }
    // }

    private void drawFlashbangEffect() {
        if (flashBangAlpha <= 0) {
            return;
        }

        long currentTime = System.currentTimeMillis();
        long remainingTime = flashBangFadeEndTime - currentTime;

        if (remainingTime <= 0) {
            flashBangAlpha = 0;
            // 效果结束时，清除快照以释放内存
            flashbangSnapshot = null;
            return;
        }
        double linearProgress = (double) remainingTime / flashBangTotalDuration;

        // // progress 从 1.0 (刚被闪) 变为 0.0 (效果结束)
        // double progress = (double) remainingTime / flashBangTotalDuration;
        // --- 方案 A: "Ease-In" 效果 ---
        // 视觉效果：白屏效果在大部分时间里保持强烈，然后在最后快速消失。
        // 推荐使用这个方案，感觉更像“闪光”。
        // 调整 Math.pow 的第二个参数（指数），值越大，效果越“突然”。(例如 2.0, 3.0, 4.0)
        // double effectProgress = Math.pow(linearProgress, 6.0);

        // --- 方案 B: "Ease-Out" 效果 ---
        // 视觉效果：白色从一开始就缓慢变淡，然后越淡越快。
        // 这完全符合您“开始慢，后面快”的文字描述。
        // 您可以调整 Math.pow 的第二个参数（指数），值越小，开始时淡得越慢。(例如 0.5, 0.4)
        double effectProgress = Math.pow(linearProgress, 0.3);

        // --- 第一层：绘制“视觉暂留”的灰色残影 ---
        // --- 绘制“视觉暂留”的灰色残影 ---
        if (flashbangSnapshot != null) {
            // 残影的透明度，让它比主效果消失得更快一些
            double afterimageAlpha = effectProgress * 0.9;

            gc.save();
            gc.setGlobalAlpha(afterimageAlpha);
            gc.drawImage(flashbangSnapshot, 0, 0);
            gc.restore();
        }

        // --- 绘制上层的“致盲”灰色/白色层 ---
        Color blindColor = Color.rgb(200, 200, 200, effectProgress);

        gc.setFill(blindColor);
        gc.fillRect(0, 0, CANVAS_WIDTH, CANVAS_HEIGHT);

        // 更新 flashBangAlpha 的值，保持逻辑一致
        flashBangAlpha = effectProgress;
    }

    /**
     * 在存活的队友之间循环切换观战目标。
     */
    private void cycleSpectatorTarget() {
        // 如果玩家自己还活着，则此功能无效
        if (me == null || getBool(me.data, "isAlive")) {
            return;
        }

        // 获取我所在的队伍
        String myTeam = getString(me.data, "team");

        // 找出所有当前存活的队友
        List<cs2d.client.GameClient.ClientPlayer> livingTeammates = clientPlayers.values().stream() // 获取所有玩家
                .filter(p -> getBool(p.data, "isAlive") && myTeam.equals(getString(p.data, "team"))) // 筛选出活着的同队队友
                .sorted(Comparator.comparing(p -> getString(p.data, "name"))) // 按名字排序，确保顺序固定
                .collect(Collectors.toList()); // 收集成列表

        if (livingTeammates.isEmpty()) { // 如果没有活着的队友
            return; // 结束方法
        }

        // 索引加一，并使用取模运算实现循环
        spectateTeammateIndex = (spectateTeammateIndex + 1) % livingTeammates.size();

        // 获取下一个观战目标的名字
        String nextTargetName = getString(livingTeammates.get(spectateTeammateIndex).data, "name");
        // 打印日志
    }

    /**
     * 一个辅助方法，根据客户端的观战索引，获取正确的观战目标。
     *
     * @return 应该被观战的 ClientPlayer 对象，如果没有则返回 null。
     */
    private cs2d.client.GameClient.ClientPlayer getSpectatorTarget() {
        // 如果我还活着，就没有观战目标
        if (me == null || getBool(me.data, "isAlive")) {
            return null;
        }

        // 获取我的队伍
        String myTeam = getString(me.data, "team");

        // 获取所有存活队友
        List<cs2d.client.GameClient.ClientPlayer> livingTeammates = clientPlayers.values().stream() // 获取所有玩家
                .filter(p -> getBool(p.data, "isAlive") && myTeam.equals(getString(p.data, "team"))) // 筛选出活着的同队队友
                .sorted(Comparator.comparing(p -> getString(p.data, "name"))) // 按名字排序
                .collect(Collectors.toList()); // 收集成列表

        if (livingTeammates.isEmpty()) { // 如果没有存活队友
            return null;
        }

        // 确保索引不会越界 (例如，如果一个队友刚刚阵亡)
        if (spectateTeammateIndex >= livingTeammates.size()) {
            spectateTeammateIndex = 0; // 重置为0
        }

        // 返回当前索引对应的队友
        return livingTeammates.get(spectateTeammateIndex);
    }

    /**
     * 渲染决策的核心方法。
     * [修改] 增加了队友可见性判断：在非个人死斗模式下，队友始终可见。
     *
     * @param p 要进行可见性判断和绘制的客户端玩家对象 (ClientPlayer)。
     */
    private void drawVisibleCharacter(cs2d.client.GameClient.ClientPlayer p) {
        synchronized (p) {
            // 规则 0: 永不绘制已死亡的角色。
            if (!getBool(p.data, "isAlive")) {
                return;
            }

            // 规则 1: 永远以“完全可见”模式绘制玩家自己。
            if (myPlayerId != null && myPlayerId.equals(p.id)) {
                drawPlayer(p, true);
                return;
            }

            // --- 队友可见性判断 ---
            // 获取当前游戏模式
            String mode = (latestGameState != null) ? getString(latestGameState, "mode") : "";

            // 只有在“非个人死斗”模式下，队友才是盟友
            // (DEATHMATCH 模式下所有人都是敌人，包括以前的队友，所以要跳过这个检查)
            if (!"DEATHMATCH".equals(mode)) {
                // 确保“我”还活着且数据存在 (如果是纯观战模式，me可能为null，走下面的povSource逻辑)
                if (me != null && me.data != null) {
                    String myTeam = getString(me.data, "team");
                    String pTeam = getString(p.data, "team");

                    // 如果是队友 (且队伍有效)
                    if (myTeam != null && myTeam.equals(pTeam)) {
                        // 直接绘制，isInDirectFov 设为 true (不透明)
                        drawPlayer(p, true);
                        return; // *** 既然是队友，直接绘制并结束，跳过后续FOV计算 ***
                    }
                }
            }

            // --- 确定视角源 (povSource) ---
            cs2d.client.GameClient.ClientPlayer povSource = null;
            if (me != null) {
                if (getBool(me.data, "isAlive")) {
                    povSource = me;
                } else {
                    String spectateMode = getString(me.data, "spectatorMode");
                    if ("CONTROLLING_BOT".equals(spectateMode)) {
                        String controlledBotId = getString(me.data, "spectatorTargetId");
                        if (controlledBotId != null) {
                            povSource = clientPlayers.get(controlledBotId);
                        }
                    }
                    if (povSource == null) {
                        povSource = getSpectatorTarget();
                    }
                }
            }

            // 规则 2: 如果没有有效的视角源（例如，作为初始观察者加入游戏，或者全知视角），则绘制所有实体。
            if (povSource == null) {
                drawPlayer(p, true);
                return;
            }

            // --- 核心可见性判断 (针对敌人) ---

            // 条件 A: 检查是否在常规的视野多边形(FOV)内。
            Point2D center = new Point2D(p.renderX, p.renderY);
            boolean isInFov = isCircleVisibleInPolygon(center, PLAYER_SIZE / 2.0, fovPoints);

            if (isInFov) {
                // 如果在直接视野内，调用 drawPlayer 并告知其“完全可见”。
                drawPlayer(p, true);
                return;
            }

            // --- 如果不在视野内，才检查声音 ---

            // 条件 B: 检查是否因任何类型的声音而被临时暴露。
            boolean isRevealedBySound = false;
            if (p.soundRevealExpireTime > System.currentTimeMillis()) {
                double distance = povSource.getPos().distance(p.getPos());
                if ("FOOTSTEP".equals(p.revealSoundType) && distance < FOOT_SOUND)
                    isRevealedBySound = true;
                else if ("RELOAD".equals(p.revealSoundType) && distance < FOOT_SOUND)
                    isRevealedBySound = true;
                else if ("FIRE".equals(p.revealSoundType) && distance < GUNSHOT_SOUND_VISUAL_RANGE)
                    isRevealedBySound = true;
            }

            if (isRevealedBySound) {
                // 如果是因为声音暴露，绘制为“非直接可见”（通常带透明度或特殊标记）
                drawPlayer(p, false);
                return;
            }

            // 既不是队友，也不在视野内，也没发出声音 -> 不绘制
        }
    }

    // private void drawVisibleCharacter(ClientPlayer p) {
    // // 在访问 'p' 之前锁定它
    // synchronized (p) {
    // // 规则 0 (最高优先级): 永不绘制已死亡的角色。
    // if (!getBool(p.data, "isAlive")) {
    // return;
    // }
    //
    // // 规则 1: 永远以“完全可见”模式绘制玩家自己。
    // boolean isSelf = myPlayerId != null && myPlayerId.equals(p.id);
    // if (isSelf) {
    // drawPlayer(p, true); // `isInDirectFov` 为 true，表示所有信息都可见。
    // return;
    // }
    //
    // // 获取我当前的队伍信息，用于判断敌我。
    // String myCurrentTeam = (me != null && me.data != null) ? getString(me.data,
    // "team") : "";
    //
    // // 规则 2: 永远以“完全可见”模式绘制所有活着的队友。
    // String pTeam = getString(p.data, "team");
    // if (!myCurrentTeam.isEmpty() && pTeam.equals(myCurrentTeam)) {
    // drawPlayer(p, true);
    // return;
    // }
    //
    // // --- 如果代码执行到这里，说明 'p' 必定是一个敌人 ---
    //
    // // 确定当前的“视角源”(Point of View Source)，即所有距离和视野计算的中心点。
    // ClientPlayer povSource = null;
    // if (me != null) {
    // if (getBool(me.data, "isAlive")) {
    // // 情况 A: 我还活着，视角源就是我自己。
    // povSource = me;
    // } else {
    // // 情况 B: 我已经阵亡，需要判断是普通观战还是夺舍。
    // String spectateMode = getString(me.data, "spectatorMode");
    // if ("CONTROLLING_BOT".equals(spectateMode)) {
    // // 如果是夺舍模式，则从服务器数据中获取被控制的BOT ID。
    // String controlledBotId = getString(me.data, "spectatorTargetId");
    // if (controlledBotId != null) {
    // // 找到这个BOT的客户端实例，作为我们的视角源。
    // povSource = clientPlayers.get(controlledBotId);
    // }
    // }
    //
    // // 如果不是夺舍模式，或者没能找到被控制的BOT，则退回到普通的观战逻辑。
    // if (povSource == null) {
    // povSource = getSpectatorTarget();
    // }
    // }
    // }
    //
    // // 规则 3: 如果没有有效的视角源（例如，作为初始观察者加入游戏），则以“完全可见”模式绘制所有敌人。
    // if (povSource == null) {
    // drawPlayer(p, true);
    // return;
    // }
    //
    // // --- 核心可见性判断 ---
    //
    // // 条件 A: 检查敌人是否在常规的视野多边形(FOV)内。
    // Point2D center = new Point2D(p.renderX, p.renderY);
    // boolean isInFov = isCircleVisibleInPolygon(center, PLAYER_SIZE / 2.0,
    // fovPoints);
    //
    // // 条件 B: 检查敌人是否因任何类型的声音而被临时暴露。
    // boolean isRevealedBySound = false;
    // if (p.soundRevealExpireTime > System.currentTimeMillis()) { // 检查暴露时间是否未过期。
    // double distance = povSource.getPos().distance(p.getPos());
    // // 根据声音类型和各自的有效范围进行判断。
    // if ("FOOTSTEP".equals(p.revealSoundType) && distance < FOOT_SOUND) {
    // isRevealedBySound = true;
    // } else if ("RELOAD".equals(p.revealSoundType) && distance < FOOT_SOUND) {
    // isRevealedBySound = true;
    // } else if ("FIRE".equals(p.revealSoundType) && distance <
    // GUNSHOT_SOUND_VISUAL_RANGE) {
    // isRevealedBySound = true;
    // }
    // }
    //
    // // --- 最终渲染决策 ---
    // if (isInFov) {
    // // 如果敌人在直接视野内，调用 drawPlayer 并告知其“完全可见”。
    // drawPlayer(p, true);
    // } else if (isRevealedBySound) {
    // // 如果敌人仅因声音暴露，调用 drawPlayer 并告知其“非直接可见”。
    // // 这将触发 drawPlayer 内部的透明度计算逻辑。
    // drawPlayer(p, false);
    // }
    // // 如果两个条件都不满足，则不进行任何绘制。
    //
    // } // [新] 在函数末尾释放锁
    // }

    private synchronized void ensureInputSendLoop() {
        if (inputSendHandle != null && !inputSendHandle.isCancelled() && !inputSendHandle.isDone())
            return;
        long intervalNanos = 1_000_000_000L / INPUT_SEND_RATE;
        inputSendHandle = inputSendExecutor.scheduleAtFixedRate(this::sendSubtickInputBatch,
                0L, intervalNanos, TimeUnit.NANOSECONDS);
    }

    /** JavaFX线程调用：以显示帧率采样最新角度和按键状态。 */
    private void publishLocalInputState(double angle) {
        if (!Double.isFinite(angle))
            return;
        latestLocalInput.set(new LocalInputState(angle, currentInputButtons()));
    }

    private int currentInputButtons() {
        boolean alive = me != null && me.data != null && getBool(me.data, "isAlive");
        int buttons = 0;
        if (keysDown.contains(KeyCode.W)) buttons |= SubtickInputTransmitter.BUTTON_FORWARD;
        if (keysDown.contains(KeyCode.S)) buttons |= SubtickInputTransmitter.BUTTON_BACK;
        if (keysDown.contains(KeyCode.A)) buttons |= SubtickInputTransmitter.BUTTON_LEFT;
        if (keysDown.contains(KeyCode.D)) buttons |= SubtickInputTransmitter.BUTTON_RIGHT;
        if (alive && isShooting) buttons |= SubtickInputTransmitter.BUTTON_FIRE;
        if (isWalking) buttons |= SubtickInputTransmitter.BUTTON_WALK;
        if (alive && isInteracting) buttons |= SubtickInputTransmitter.BUTTON_INTERACT;
        if (alive && isUnderhandThrowing) buttons |= SubtickInputTransmitter.BUTTON_UNDERHAND;
        return buttons;
    }

    /** 独立60Hz线程调用；重发尚未ACK的短窗口，UDP丢包不会吞掉输入边沿。 */
    private void sendSubtickInputBatch() {
        if (clientState != cs2d.client.GameClient.ClientState.PLAYING
                || myPlayerId == null || me == null || serverSessionId == null)
            return;
        LocalInputState inputState = latestLocalInput.get();
        JsonObject batch = subtickInputTransmitter.captureAndBuildBatch(System.nanoTime(),
                inputState.angle(), inputState.buttons());
        addProtocolMetadata(batch);
        sendMessage(gson.toJson(batch));
    }

    /** 按下/释放边沿立即发出，同时仍由60Hz心跳重发直到服务器ACK。 */
    private void sendSubtickInputImmediately() {
        if (clientState != cs2d.client.GameClient.ClientState.PLAYING || me == null)
            return;
        publishLocalInputState(updateLocalAimVisual());
        sendSubtickInputBatch();
    }

    private double updateLocalAimVisual() {
        if (me == null)
            return 0.0;
        Point2D mouseWorld = camera.screenToWorld(mouseX, mouseY);
        double angle = Math.atan2(mouseWorld.getY() - me.renderY, mouseWorld.getX() - me.renderX);
        if (Double.isFinite(angle)) {
            // 本地玩家的视觉朝向以鼠标为准。服务器仍会收到角度并进行权威射击判定，
            // 但服务器快照不能把本地准星/角色朝向降回60Hz。
            me.angle = angle;
            me.targetAngle = angle;
        }
        return angle;
    }

    // 更新本地对 "me" (我自己) 对象的引用
    private void updateLocalPlayerRef() {
        // 检查 myPlayerId 是否为 null
        if (myPlayerId != null) {
            // 如果不为 null (是正常玩家)，就去 clientPlayers 映射中获取玩家对象
            me = clientPlayers.get(myPlayerId);
        } else {
            // 如果为 null (正在观战)，就把 'me' 对象也设为 null
            me = null;
        }
    }

    private void drawPlayer(cs2d.client.GameClient.ClientPlayer p, boolean isInDirectFov) {
        // [新] 在访问 'p' 之前锁定它
        synchronized (p) {
            if (getDouble(p.data, "health") <= 0)
                return;

            gc.save(); // 保存状态，以便应用透明度等效果

            // --- 1. 透明度计算 (声音暴露) ---
            // 只有当玩家不是通过直接视野看到时，才计算距离衰减的透明度。
            if (!isInDirectFov) {
                cs2d.client.GameClient.ClientPlayer povSource = (me != null && getBool(me.data, "isAlive")) ? me
                        : getSpectatorTarget();
                if (povSource != null && p.revealSoundType != null) {
                    double distance = povSource.getPos().distance(p.getPos());
                    double maxRange = switch (p.revealSoundType) {
                        case "FIRE" -> GUNSHOT_SOUND_VISUAL_RANGE;
                        default -> FOOT_SOUND; // "RELOAD" 和 "FOOTSTEP" 共用一个范围
                    };

                    // 线性插值计算透明度，范围从 1.0 (最近) 到 0.2 (最远)。
                    double progress = Math.min(1.0, distance / maxRange);
                    double alpha = 1.0 - (progress * 0.8); // 0.8 是 (1.0 - 0.2) 的范围

                    gc.setGlobalAlpha(alpha);
                }
            }

            // --- 2. 基础角色绘制 ---
            JsonObject pData = p.data;
            double x = p.renderX, y = p.renderY, angle = p.angle;
            String team = getString(pData, "team");
            String mode = (latestGameState != null) ? getString(latestGameState, "mode") : "";

            if (getBool(pData, "isInvincible"))
                gc.setGlobalAlpha(0.5 * gc.getGlobalAlpha());
            if (getBool(pData, "isSlowed")) {
                // 复用静态特效
                gc.setEffect(SLOWED_EFFECT);
            }
            Color bodyColor;
            if (p.id.equals(myPlayerId))
                bodyColor = Color.web("#68D391");
            else if ("DEATHMATCH".equals(mode))
                bodyColor = PRIMARY_RED;
            else if ("CT".equals(team))
                bodyColor = PRIMARY_BLUE;
            else if ("T".equals(team))
                bodyColor = PRIMARY_RED;
            else
                bodyColor = PRIMARY_GREEN;

            boolean armed = !"ZOMBIE".equals(team) && pData.has("weaponName");
            drawCachedPlayerSprite(x, y, angle, bodyColor, armed);
            gc.setEffect(null);

            // --- 3. 敏感信息显示决策 ---
            // [2025-12-01] 在死斗模式下，不把其他人视为“敏感敌人”，以便看到血条。
            boolean isEnemy = !"DEATHMATCH".equals(mode) && me != null && me.data != null
                    && !getString(p.data, "team").equals(getString(me.data, "team"));

            // 规则 A (C4 指示器): 在爆破模式下，只有在直接视野内才能看到敌人的C4。
            boolean showC4Info = true;
            if ("DEMOLITION".equals(mode) && isEnemy) {
                showC4Info = isInDirectFov;
            }
            if (showC4Info && getBool(pData, "hasBomb")) {
                gc.setFill(Color.RED);
                gc.setFont(Font.font("Orbitron", FontWeight.BOLD, 16));
                gc.setTextAlign(TextAlignment.CENTER);
                gc.fillText("C4", x, y - PLAYER_SIZE);
            }

            // 规则 B (血条): 只要当前情况不是“爆破模式下的敌人”，就显示血量条。
            if (!("DEMOLITION".equals(mode) && isEnemy)) {
                drawHealthBar(p);
            }

            // "me == null" 表示客户端以观战模式加入（myPlayerId 为 null）
            // 并且我们只为非自己的玩家绘制名字（尽管在me==null时isSelf总是false）
            if (me == null) {
                String name = getString(p.data, "name");
                team = getString(p.data, "team");

                // 设置字体和对齐方式
                gc.setFont(smallHudFont); // 使用小号 HUD 字体
                gc.setTextAlign(TextAlignment.CENTER); // 居中对齐

                // 根据队伍设置颜色
                if ("CT".equals(team)) {
                    gc.setFill(PRIMARY_BLUE);
                } else if ("T".equals(team)) {
                    gc.setFill(PRIMARY_RED);
                } else if ("ZOMBIE".equals(team)) {
                    gc.setFill(PRIMARY_GREEN);
                } else {
                    gc.setFill(Color.WHITE); // 默认颜色
                }

                // 计算绘制位置（在玩家头顶上方）
                // p.renderX/Y 是玩家中心
                // PLAYER_SIZE / 2.0 是玩家半径
                // 再减去一个固定的偏移量（例如 10 像素）
                double xk = p.renderX;
                double yk = p.renderY - (PLAYER_SIZE / 2.0) - 10;

                // 绘制名字
                gc.fillText(name, xk, yk);
            }

            gc.restore(); // 恢复画布状态

        } // [新] 在函数末尾释放锁
    }

    /**
     * 绘制准星和瞄准线 - 基于动态半径和服务器预测的后坐力
     */
    // 绘制准星和瞄准线的方法
    private void drawCrosshairAndAimLine() {
        // 如果玩家对象为空、已死亡或ID为空，则不绘制
        if (me == null || !getBool(me.data, "isAlive") || myPlayerId == null) {
            return;
        }

        // 判断当前是否手持投掷物
        int currentSlot = getInt(me.data, "currentSlot");
        boolean isHoldingGrenade = currentSlot >= 6; // 槽位6及以上为投掷物

        if (isHoldingGrenade) {
            // --- 投掷物模式 ---
            calculateGrenadeTrajectory(); // 实时计算轨迹
            drawGrenadeTrajectory(); // 绘制轨迹和落点
        } else {

            // 将玩家的世界坐标转换为屏幕坐标
            Point2D playerScreenPos = camera.worldToScreen(me.renderX, me.renderY);
            // 计算玩家屏幕位置与鼠标屏幕位置之间的距离，模拟视觉上的后坐力半径
            double recoilRadius = playerScreenPos.distance(mouseX, mouseY);

            // [核心修复] 计算从玩家屏幕位置到鼠标当前屏幕位置的瞬时角度。
            // 使用这个瞬时角度而不是 me.angle (可能由于 update/draw 不同步而滞后) 来彻底根治抖动。
            double angleToMouse = Math.atan2(mouseY - playerScreenPos.getY(), mouseX - playerScreenPos.getX());
            double playerAngle = angleToMouse; // 恢复变量定义，供后续瞄准线逻辑使用
            // [修复] 使用我们平滑修正后的 visualRecoilAngle
            double predictedRecoilAngle = this.visualRecoilAngle;
            // 计算最终显示的瞄准角度
            double finalDisplayAngle = angleToMouse + predictedRecoilAngle;

            // 计算最终准星在屏幕上的X坐标
            double finalCrosshairX = playerScreenPos.getX() + recoilRadius * Math.cos(finalDisplayAngle);
            // 计算最终准星在屏幕上的Y坐标
            double finalCrosshairY = playerScreenPos.getY() + recoilRadius * Math.sin(finalDisplayAngle);

            // 如果设置中不跟随 recoil，则准星位置就是鼠标位置
            if (!gameSettings.isFollowRecoil()) {
                finalCrosshairX = mouseX;
                finalCrosshairY = mouseY;
            }

            // 如果设置中显示瞄准线
            if (gameSettings.isShowAimLine()) {
                // 计算枪口在世界中的X坐标
                double muzzleWorldX = me.renderX + Math.cos(playerAngle) * (PLAYER_SIZE / 2.0);
                // 计算枪口在世界中的Y坐标
                double muzzleWorldY = me.renderY + Math.sin(playerAngle) * (PLAYER_SIZE / 2.0);
                // 将枪口的世界坐标转换为屏幕坐标
                Point2D muzzleScreenPos = camera.worldToScreen(muzzleWorldX, muzzleWorldY);

                // 保存图形上下文状态
                gc.save();

                // --- 新增：穿墙伤害预测逻辑 ---
                if (gameSettings.isWallPenetrationPrediction()) {
                    drawWallPenetrationPrediction(new Point2D(muzzleWorldX, muzzleWorldY),
                            new Point2D(finalCrosshairX, finalCrosshairY));
                } else {
                    // 原有的普通瞄准线逻辑
                    gc.setStroke(gameSettings.getAimLineColor());
                    gc.setGlobalAlpha(gameSettings.getAimLineOpacity());
                    gc.setLineWidth(1.5);
                    gc.strokeLine(muzzleScreenPos.getX(), muzzleScreenPos.getY(), finalCrosshairX, finalCrosshairY);
                }

                // 恢复图形上下文状态
                gc.restore();
            }

            // 设置描边颜色为准星颜色（从设置中获取）
            gc.setStroke(gameSettings.getCrosshairColor());
            // 在鼠标位置绘制一个小的中心点
            gc.fillOval(mouseX - 1, mouseY - 1, 2, 2);

            // 使用设置中的自定义准星颜色
            gc.setStroke(gameSettings.getCrosshairColor());

            // 设置线宽
            gc.setLineWidth(2);
            // 设置准星大小
            double size = 8;
            // 绘制准星的水平线
            gc.strokeLine(finalCrosshairX - size, finalCrosshairY, finalCrosshairX + size, finalCrosshairY);
            // 绘制准星的垂直线
            gc.strokeLine(finalCrosshairX, finalCrosshairY - size, finalCrosshairX, finalCrosshairY + size);
        }
    }

    /**
     * 计算射线线段 (rayStart -> rayEnd) 与障碍物边界线段 (p1 -> p2) 的交点参数 t.
     */
    private double getRayEdgeT(Point2D rayStart, Point2D rayEnd, Point2D p1, Point2D p2) {
        double det = (rayStart.getX() - rayEnd.getX()) * (p1.getY() - p2.getY())
                - (rayStart.getY() - rayEnd.getY()) * (p1.getX() - p2.getX());
        if (det == 0)
            return Double.NaN;

        double t = ((rayStart.getX() - p1.getX()) * (p1.getY() - p2.getY())
                - (rayStart.getY() - p1.getY()) * (p1.getX() - p2.getX())) / det;
        double u = -((rayStart.getX() - rayEnd.getX()) * (rayStart.getY() - p1.getY())
                - (rayStart.getY() - rayEnd.getY()) * (rayStart.getX() - p1.getX())) / det;

        if (t >= -1e-6 && t <= 1 + 1e-6 && u >= -1e-6 && u <= 1 + 1e-6) {
            return Math.max(0.0, Math.min(1.0, t));
        }
        return Double.NaN;
    }

    /**
     * 解析法计算射线穿过单个 StaticObstacle 的精确区间 [t_start, t_end]。
     */
    private double[] getObstaclePenetrationInterval(Point2D rayStart, Point2D rayEnd,
                                                    cs2d.client.GameClient.StaticObstacle obs) {
        java.util.List<Double> ts = new java.util.ArrayList<>();
        for (Point2D[] edge : obs.edges) {
            double t = getRayEdgeT(rayStart, rayEnd, edge[0], edge[1]);
            if (!Double.isNaN(t)) {
                boolean dup = false;
                for (double ext : ts)
                    if (Math.abs(ext - t) < 1e-5)
                        dup = true;
                if (!dup)
                    ts.add(t);
            }
        }

        if (ts.isEmpty())
            return null;
        ts.sort(Double::compareTo);

        if (ts.size() == 1) {
            if (isPointInObstacle(rayStart, obs.originalJson)) {
                return new double[] { 0.0, ts.get(0) };
            } else {
                return new double[] { ts.get(0), 1.0 };
            }
        }

        return new double[] { ts.get(0), ts.get(ts.size() - 1) };
    }

    /**
     * 实时计算并绘制穿墙伤害预测线。
     */
    private void drawWallPenetrationPrediction(Point2D muzzleWorld, Point2D crosshairScreen) {
        Point2D targetWorld = camera.screenToWorld(crosshairScreen.getX(), crosshairScreen.getY());

        String weaponKey = getString(me.data, "weaponKey");
        if (weaponKey == null)
            return;

        // [修复] 健壮的查找逻辑：原始键 -> 大写 -> 去符号大写
        cs2d.client.GameClient.WeaponPhysicsData physics = WEAPON_PHYSICS_DATA.get(weaponKey);
        if (physics == null) {
            physics = WEAPON_PHYSICS_DATA.get(weaponKey.toUpperCase());
        }
        if (physics == null) {
            physics = WEAPON_PHYSICS_DATA
                    .get(weaponKey.toUpperCase().replace("-", "").replace(" ", "").replace("_", ""));
        }

        // 如果没有物理数据（比如刀、手雷），按普通瞄准线绘制
        if (physics == null || physics.penetrationPower() <= 0) {
            gc.setStroke(gameSettings.getAimLineColor());
            gc.setGlobalAlpha(gameSettings.getAimLineOpacity());
            gc.setLineWidth(1.5);
            Point2D muzzleScreen = camera.worldToScreen(muzzleWorld.getX(), muzzleWorld.getY());
            gc.strokeLine(muzzleScreen.getX(), muzzleScreen.getY(), crosshairScreen.getX(), crosshairScreen.getY());
            return;
        }

        final double PENETRATION_MULTIPLIER = 2.0;
        final double initialPenetrationPower = physics.penetrationPower() * PENETRATION_MULTIPLIER;
        // [性能修复] Quadtree 查询并解析计算几何厚度
        java.util.List<cs2d.client.GameClient.StaticObstacle> candidateObstacles = new java.util.ArrayList<>();
        if (quadtreeRootNode != null) {
            quadtreeRootNode.queryRay(candidateObstacles, muzzleWorld, targetWorld);
        }

        java.util.List<double[]> intervals = new java.util.ArrayList<>();
        for (cs2d.client.GameClient.StaticObstacle obs : candidateObstacles) {
            double[] inter = getObstaclePenetrationInterval(muzzleWorld, targetWorld, obs);
            if (inter != null)
                intervals.add(inter);
        }

        // 按起点排序并合并重叠区间
        intervals.sort((a, b) -> Double.compare(a[0], b[0]));
        java.util.List<double[]> merged = new java.util.ArrayList<>();
        for (double[] inter : intervals) {
            if (merged.isEmpty()) {
                merged.add(new double[] { inter[0], inter[1] });
            } else {
                double[] last = merged.get(merged.size() - 1);
                if (inter[0] <= last[1] + 1e-5) {
                    last[1] = Math.max(last[1], inter[1]);
                } else {
                    merged.add(new double[] { inter[0], inter[1] });
                }
            }
        }

        double totalObstacleThickness = 0.0;
        double rayLength = muzzleWorld.distance(targetWorld);
        double allowedThickness = initialPenetrationPower > 0 && physics.penetrationCostPerPixel() > 0
                ? initialPenetrationPower / physics.penetrationCostPerPixel()
                : 0;

        double finalT = 1.0;
        boolean stopped = false;

        for (double[] inter : merged) {
            double segmentLength = (inter[1] - inter[0]) * rayLength;
            if (totalObstacleThickness + segmentLength >= allowedThickness) {
                // 子弹在此区间内耗尽动能
                double remainingAllowed = allowedThickness - totalObstacleThickness;
                finalT = rayLength > 0 ? inter[0] + (remainingAllowed / rayLength) : 1.0;
                totalObstacleThickness = allowedThickness; // 达到最大允许厚度
                stopped = true;
                break;
            } else {
                totalObstacleThickness += segmentLength;
            }
        }

        double totalPenetrationCost = totalObstacleThickness * physics.penetrationCostPerPixel();
        double remainingPenetration = stopped ? 0 : initialPenetrationPower - totalPenetrationCost;
        double damagePercent = (initialPenetrationPower > 0) ? (remainingPenetration / initialPenetrationPower) : 0;

        Point2D finalTargetWorld = new Point2D(
                muzzleWorld.getX() + finalT * (targetWorld.getX() - muzzleWorld.getX()),
                muzzleWorld.getY() + finalT * (targetWorld.getY() - muzzleWorld.getY()));

        Color startColor = gameSettings.getWallPenColorFull();
        Color endColor = gameSettings.getWallPenColorNone();

        Color lineColor = startColor.interpolate(endColor, 1.0 - Math.max(0, damagePercent));
        lineColor = new Color(lineColor.getRed(), lineColor.getGreen(), lineColor.getBlue(),
                gameSettings.getAimLineOpacity());

        Point2D muzzleScreen = camera.worldToScreen(muzzleWorld.getX(), muzzleWorld.getY());
        Point2D endScreen = camera.worldToScreen(finalTargetWorld.getX(), finalTargetWorld.getY());

        gc.setStroke(lineColor);
        gc.setLineWidth(2.0);
        gc.strokeLine(muzzleScreen.getX(), muzzleScreen.getY(), endScreen.getX(), endScreen.getY());

        if (damagePercent < 1.0) {
            gc.setFill(lineColor);
            gc.setFont(tinyHudFont);
            gc.fillText(String.format("%d%%", (int) (Math.max(0, damagePercent) * 100)), endScreen.getX() + 10,
                    endScreen.getY());
        }
    }

    /**
     * 绘制血条 (使用客户端推测的maxHealth)
     */
    /**
     * 绘制血条 (实现对数增长宽度和居中对齐)
     */
    private void drawHealthBar(cs2d.client.GameClient.ClientPlayer p) {
        double x = p.renderX;
        double y = p.renderY;
        double health = getDouble(p.data, "health");
        double maxHealth = getDouble(p.data, "maxHealth");

        // --- 修正后的核心常量 ---
        final double BASE_BAR_WIDTH = 30; // 100 HP时的标准宽度
        final double BAR_HEIGHT = 5;

        // 确保 MaxHP 至少为 100
        double maxHpForLog = Math.max(100.0, maxHealth);

        // --- 关键修正：使用更激进的对数增长 ---
        // 我们想要：log10(1000) = 2 × log10(100)，但实际上 log10(1000) = 3, log10(100) = 2
        // 所以需要调整系数：宽度 = (log10(maxHP) - 1) × 系数

        double backgroundWidth = (Math.log10(maxHpForLog) - 1) * BASE_BAR_WIDTH;

        // 计算当前血量的宽度（保持百分比关系）
        double healthPercentage = health / maxHealth;
        double greenBarWidth = backgroundWidth * healthPercentage;

        // 计算血条的起始 X 坐标 (实现居中)
        double barX = x - backgroundWidth / 2.0;
        double barY = y - PLAYER_SIZE / 2.0 - BAR_HEIGHT - 5;

        // --- 绘制 ---
        // 绘制背景（已损失的血量）
        gc.setFill(Color.web("#E53E3E"));
        gc.fillRect(barX, barY, backgroundWidth, BAR_HEIGHT);

        // 绘制当前血量
        gc.setFill(PRIMARY_GREEN);
        gc.fillRect(barX, barY, greenBarWidth, BAR_HEIGHT);
    }

    // 绘制地上掉落的物品
    private void drawDroppedItem(JsonObject item) {
        double x = getDouble(item, "x"); // 获取物品X坐标
        double y = getDouble(item, "y"); // 获取物品Y坐标

        if (getBool(item, "isBomb")) { // 如果是C4炸弹
            // --- C4的特殊绘制：方形+五角星 ---
            double size = 16; // 标记的整体大小
            double halfSize = size / 2; // 大小的一半

            // 绘制红色背景方块
            gc.setFill(Color.RED);
            gc.fillRect(x - halfSize, y - halfSize, size, size);

            // 绘制内部的黄色五角星
            gc.setFill(Color.YELLOW);
            drawStar(gc, x, y, halfSize * 0.85, 5); // 调用辅助方法绘制五角星

            // 添加一个闪烁的白色外边框，让它更醒目
            if ((System.currentTimeMillis() / 400) % 2 == 0) { // 每400毫秒切换一次状态
                gc.setStroke(Color.WHITE); // 设置描边为白色
                gc.setLineWidth(2.5); // 较粗的线宽
            } else {
                gc.setStroke(Color.BLACK); // 设置描边为黑色
                gc.setLineWidth(1); // 较细的线宽
            }
            gc.strokeRect(x - halfSize, y - halfSize, size, size); // 绘制边框

            // 在下方绘制 "C4" 文字 (保持原有逻辑)
            gc.setFill(Color.WHITE);
            gc.setFont(smallHudFont);
            gc.setTextAlign(TextAlignment.CENTER);
            gc.fillText("C4", x, y + 25);

        } else { // 如果是其他掉落的武器
            String name = WEAPON_UI_DATA.getOrDefault(getString(item, "name"),
                    new cs2d.client.GameClient.WeaponUIData(getString(item, "name"), 0, "ANY", 0, false)).name();
            Image sprite = droppedWeaponSpriteCache.computeIfAbsent(name, this::createDroppedWeaponSprite);
            gc.drawImage(sprite, x - 80, y - 16, 160, 56);
            perfDroppedWeaponSpriteDraws++;
        }
    }

    private Image createDroppedWeaponSprite(String displayName) {
        final double logicalWidth = 160;
        final double logicalHeight = 56;
        final double scale = 2.0;
        Canvas spriteCanvas = new Canvas(logicalWidth * scale, logicalHeight * scale);
        GraphicsContext spriteGc = spriteCanvas.getGraphicsContext2D();
        spriteGc.scale(scale, scale);
        spriteGc.setStroke(Color.YELLOW);
        spriteGc.setLineWidth(2);
        spriteGc.strokeRect(logicalWidth / 2.0 - 10, 6, 20, 20);
        spriteGc.setFill(Color.WHITE);
        spriteGc.setFont(smallHudFont);
        spriteGc.setTextAlign(TextAlignment.CENTER);
        spriteGc.fillText(displayName, logicalWidth / 2.0, 41);
        javafx.scene.SnapshotParameters parameters = new javafx.scene.SnapshotParameters();
        parameters.setFill(Color.TRANSPARENT);
        perfSpriteBuilds++;
        return spriteCanvas.snapshot(parameters, null);
    }

    /**
     * 绘制五角星的辅助方法
     *
     * @param gc          图形上下文
     * @param centerX     五角星的中心X坐标
     * @param centerY     五角星的中心Y坐标
     * @param outerRadius 五角星的外接圆半径 (决定大小)
     * @param numPoints   角的数量 (这里是5)
     */
    private void drawStar(GraphicsContext gc, double centerX, double centerY, double outerRadius, int numPoints) {
        // 内接圆半径，调整这个比例可以改变五角星的“胖瘦”
        double innerRadius = outerRadius * 0.4;
        // 存储所有顶点的X坐标
        double[] xPoints = new double[numPoints * 2];
        // 存储所有顶点的Y坐标
        double[] yPoints = new double[numPoints * 2];

        // 循环计算每个顶点的位置
        for (int i = 0; i < numPoints * 2; i++) {
            // 交替使用外接圆和内接圆半径来形成尖角
            double radius = (i % 2 == 0) ? outerRadius : innerRadius;
            // 计算角度，-Math.PI/2是为了让一个角朝上
            double angle = Math.PI / numPoints * i - Math.PI / 2;

            // 计算顶点的X坐标
            xPoints[i] = centerX + radius * Math.cos(angle);
            // 计算顶点的Y坐标
            yPoints[i] = centerY + radius * Math.sin(angle);
        }
        // 使用计算出的顶点绘制并填充五角星
        gc.fillPolygon(xPoints, yPoints, numPoints * 2);
    }

    // 绘制已安放的C4炸弹
    private void drawPlantedBomb(double bombTimer) {
        // 如果最新的游戏状态为空或没有炸弹位置信息，则返回
        if (latestGameState == null || !latestGameState.has("bombPosition"))
            return;
        // 获取炸弹位置的JSON对象
        JsonObject pos = latestGameState.getAsJsonObject("bombPosition");
        // 获取炸弹的坐标
        double x = getDouble(pos, "x"), y = getDouble(pos, "y");
        // 根据剩余时间计算闪烁速度（时间越少，闪得越快）
        double blinkSpeed = bombTimer < 10000 ? (bombTimer < 5000 ? 150 : 300) : 600;
        // 使用取模运算计算当前的透明度，实现闪烁效果
        double alpha = (System.currentTimeMillis() % blinkSpeed) < (blinkSpeed / 2) ? 1.0 : 0.5;
        // 设置填充颜色和透明度
        gc.setFill(Color.rgb(255, 0, 0, alpha));
        // 绘制炸弹的实体
        gc.fillRect(x - 7.5, y - 7.5, 15, 15);
        // 设置描边颜色和透明度
        gc.setStroke(Color.rgb(255, 255, 255, alpha));
        // 绘制炸弹的边框
        gc.strokeRect(x - 7.5, y - 7.5, 15, 15);
    }

    /**
     * 辅助方法：判断一条线段是否与一个多边形（视野）相交
     *
     * @param startPoint 线段起点
     * @param endPoint   线段终点
     * @param polygon    多边形的顶点列表
     * @return 如果可见或相交，则返回true
     */
    private boolean isLineSegmentVisibleInPolygon(Point2D startPoint, Point2D endPoint, List<Point2D> polygon) {
        // 如果视野为空（例如观战或死亡时），则默认所有东西都可见
        if (polygon == null || polygon.isEmpty())
            return true;

        // 如果线段的任意一个端点在视野内，则它可见
        if (isPointInPolygon(startPoint, polygon) || isPointInPolygon(endPoint, polygon)) {
            return true;
        }

        // 如果两个端点都在视野外，检查线段是否与多边形的任何一条边相交
        for (int i = 0, j = polygon.size() - 1; i < polygon.size(); j = i++) {
            Point2D polyEdgeStart = polygon.get(j);
            Point2D polyEdgeEnd = polygon.get(i);

            // 如果线段与多边形的某条边相交，则它可见
            if (getLineIntersection(startPoint, endPoint, polyEdgeStart, polyEdgeEnd) != null) {
                return true;
            }
        }

        // 如果以上条件都不满足，则线段完全在视野外
        return false;
    }

    // 绘制视觉特效 (VFX)
    private void drawVFX(JsonObject vfx) {
        String type = getString(vfx, "type"); // 获取特效类型
        double x = getDouble(vfx, "x"); // 获取特效X坐标
        double y = getDouble(vfx, "y"); // 获取特效Y坐标

        // --- 恢复你原有的、更智能的可见性判断逻辑 ---
        boolean isVisible; // 声明一个布尔变量用于存储可见性结果

        if ("muzzle".equals(type)) { // 如果是枪口火光
            // 枪口火光总是可见
            isVisible = true;
        } else if ("trail".equals(type)) { // 如果是子弹轨迹
            // 对子弹轨迹使用线段与视野多边形的相交判断
            Point2D startPoint = new Point2D(x, y); // 轨迹起点
            Point2D endPoint = new Point2D(getDouble(vfx, "endX"), getDouble(vfx, "endY")); // 轨迹终点
            isVisible = isLineSegmentVisibleInPolygon(startPoint, endPoint, fovPoints); // 判断线段是否可见
        } else { // 其他点状特效（如爆炸）
            // 其他点状特效，使用圆形与视野多边形的相交判断
            Point2D center = new Point2D(x, y);
            // C4爆炸范围大，用大半径判断；其他用小半径
            double checkRadius = "explode_c4".equals(type) ? 150.0 : 15.0;
            isVisible = isCircleVisibleInPolygon(center, checkRadius, fovPoints); // 判断圆形是否可见
        }

        // 如果最终判断为不可见，则不进行任何绘制
        if (!isVisible) {
            return;
        }
        // --- 修复结束 ---

        // --- 绘制逻辑 ---
        if ("explode_c4".equals(type)) { // 如果是C4爆炸
            // System.out.println("[客户端调试] 正在绘制 'explode_c4' 特效...");

            gc.save(); // 保存状态
            double remaining = getDouble(vfx, "remaining"); // 剩余时间
            double duration = getDouble(vfx, "duration"); // 总时长

            if (duration > 0) { // 避免除以零
                double progress = 1.0 - (remaining / duration); // 计算进度
                if (progress >= 0 && progress <= 1) { // 确保进度在有效范围内
                    double maxRadius = 350; // 最大半径

                    // 内部核心：白色闪光
                    if (progress < 0.2) {
                        gc.setGlobalAlpha(Math.max(0, 1.0 - progress * 5));
                        gc.setFill(Color.WHITE);
                        gc.fillOval(x - 50, y - 50, 100, 100);
                    }
                    // 中间火焰层
                    double fireRadius = maxRadius * 0.7 * progress;
                    double fireAlpha = 0.8 * (1.0 - Math.pow(progress, 0.5));
                    gc.setGlobalAlpha(Math.max(0, fireAlpha));
                    gc.setFill(Color.ORANGERED);
                    gc.fillOval(x - fireRadius, y - fireRadius, fireRadius * 2, fireRadius * 2);
                    // 外部冲击波
                    double shockwaveRadius = maxRadius * progress;
                    double shockwaveAlpha = 0.5 * (1.0 - progress * progress);
                    gc.setGlobalAlpha(Math.max(0, shockwaveAlpha));
                    gc.setStroke(Color.rgb(80, 80, 80));
                    gc.setLineWidth(Math.max(0, 15 * (1.0 - progress)));
                    gc.strokeOval(x - shockwaveRadius, y - shockwaveRadius, shockwaveRadius * 2, shockwaveRadius * 2);
                }
            }
            gc.restore(); // 恢复状态
        } else if ("muzzle".equals(type)) { // 如果是枪口火光
            gc.setFill(Color.ORANGE);
            gc.fillOval(x - 5, y - 5, 10, 10);
        } else if ("trail".equals(type)) { // 如果是子弹轨迹
            gc.setStroke(Color.rgb(255, 255, 150, 0.8));
            gc.setLineWidth(2);

            gc.strokeLine(x, y, getDouble(vfx, "endX"), getDouble(vfx, "endY"));
        }
        // 旧的爆炸，留着
        // else if ("explode_he".equals(type)) { // 如果是手雷爆炸
        // gc.setFill(Color.ORANGERED);
        // gc.fillOval(x - 20, y - 20, 40, 40);
        // gc.setFill(Color.YELLOW);
        // gc.fillOval(x - 10, y - 10, 20, 20);
        // }
    }
    // --- 复杂爆炸效果 START ---
    // 将这整块代码复制到您的 GameClient.java 中，例如放在 ClientGrenade 类的下方

    /**
     * 代表爆炸效果中的一个粒子（碎片、火花等）
     */
    private static class Particle {
        Point2D position;
        Point2D velocity;
        double life;
        double age = 0;
        double startSize, endSize;
        Color startColor, endColor;

        Particle(Point2D pos, Point2D vel, double life, double startSize, double endSize, Color startColor,
                 Color endColor) {
            this.position = pos;
            this.velocity = vel;
            this.life = life;
            this.startSize = startSize;
            this.endSize = endSize;
            this.startColor = startColor;
            this.endColor = endColor;
        }

        public void update(double deltaTime) {
            age += deltaTime;
            position = position.add(velocity.multiply(deltaTime));
        }

        public void draw(GraphicsContext gc, cs2d.client.GameClient.Camera camera) {
            if (isDead())
                return;
            double progress = age / life;
            double currentSize = startSize + (endSize - startSize) * progress;

            // 线性插值计算当前颜色
            Color currentColor = startColor.interpolate(endColor, progress);

            gc.setFill(currentColor);
            gc.fillOval(position.getX() - currentSize / 2, position.getY() - currentSize / 2, currentSize, currentSize);
        }

        public boolean isDead() {
            return age >= life;
        }
    }

    /**
     * 封装一个完整的、有状态的爆炸效果
     */
    private static class ExplosionEffect {
        Point2D position;
        double startTime;
        double duration = 0.5; // 火球和冲击波的持续时间
        List<cs2d.client.GameClient.Particle> particles = new ArrayList<>();
        Random rand = new Random();

        public ExplosionEffect(Point2D pos, double currentTime) {
            this.position = pos;
            this.startTime = currentTime;

            // 生成碎片粒子
            int numParticles = 15 + rand.nextInt(10);
            for (int i = 0; i < numParticles; i++) {
                double angle = rand.nextDouble() * 2 * Math.PI;
                double speed = 100 + rand.nextDouble() * 200; // 碎片速度
                double life = 0.4 + rand.nextDouble() * 0.5; // 碎片生命周期

                particles.add(new cs2d.client.GameClient.Particle(
                        position,
                        new Point2D(Math.cos(angle) * speed, Math.sin(angle) * speed),
                        life, 6, 2, // 初始大小, 结束大小
                        Color.ORANGE, Color.rgb(50, 0, 0, 0.0) // 橙色 -> 暗红色透明
                ));
            }
        }

        public ExplosionEffect(Point2D pos, double currentTime, double duration) {
            this.position = pos;
            this.startTime = currentTime;
            this.duration = duration; // 允许子类或调用者设定持续时间

            // 生成碎片粒子
            int numParticles = 15 + rand.nextInt(10);
            for (int i = 0; i < numParticles; i++) {
                double angle = rand.nextDouble() * 2 * Math.PI;
                double speed = 100 + rand.nextDouble() * 200; // 碎片速度
                double life = 0.4 + rand.nextDouble() * 0.5; // 碎片生命周期

                particles.add(new cs2d.client.GameClient.Particle(
                        position,
                        new Point2D(Math.cos(angle) * speed, Math.sin(angle) * speed),
                        life, 6, 2, // 初始大小, 结束大小
                        Color.ORANGE, Color.rgb(50, 0, 0, 0.0) // 橙色 -> 暗红色透明
                ));
            }
        }

        public void update(double deltaTime) {
            particles.removeIf(cs2d.client.GameClient.Particle::isDead);
            for (cs2d.client.GameClient.Particle p : particles) {
                p.update(deltaTime);
            }
        }

        public void draw(GraphicsContext gc, cs2d.client.GameClient.Camera camera) {
            double elapsed = (System.currentTimeMillis() / 1000.0) - startTime;
            if (elapsed > duration && particles.isEmpty())
                return;

            double progress = Math.min(1.0, elapsed / duration);

            // --- 绘制主火球 (从亮黄 -> 橙红 -> 透明) ---
            if (progress < 1.0) {
                double coreRadius = 50 * progress;
                Color coreColor = Color.YELLOW.interpolate(Color.ORANGERED.deriveColor(1, 1, 1, 0.5), progress);
                gc.setFill(coreColor);
                gc.fillOval(position.getX() - coreRadius, position.getY() - coreRadius, coreRadius * 2, coreRadius * 2);
            }

            // --- 绘制冲击波 (一个快速变大变淡的圆环) ---
            if (progress < 1.0) {
                double shockwaveRadius = 80 * progress;
                double alpha = 1.0 - progress;
                gc.setStroke(Color.rgb(255, 255, 200, alpha * 0.8));
                gc.setLineWidth(5 * (1.0 - progress)); // 冲击波从粗变细
                gc.strokeOval(position.getX() - shockwaveRadius, position.getY() - shockwaveRadius, shockwaveRadius * 2,
                        shockwaveRadius * 2);
            }

            // --- 绘制所有粒子 ---
            for (cs2d.client.GameClient.Particle p : particles) {
                p.draw(gc, camera);
            }
        }

        public boolean isDead() {
            return (System.currentTimeMillis() / 1000.0) - startTime > duration && particles.isEmpty();
        }
    }

    // --- 复杂爆炸效果 END ---
    // 绘制一个障碍物
    private void drawObstacle(JsonObject obs) {
        drawObstacle(gc, obs);
    }

    private static final double PLAYER_SPRITE_EXTENT = 24.0;
    private static final double PLAYER_SPRITE_SCALE = 2.0;

    private void drawCachedPlayerSprite(double x, double y, double angle, Color bodyColor, boolean armed) {
        String key = bodyColor.toString() + ':' + armed;
        Image sprite = playerSpriteCache.computeIfAbsent(key, ignored -> createPlayerSprite(bodyColor, armed));
        gc.save();
        gc.translate(x, y);
        gc.rotate(Math.toDegrees(angle));
        gc.drawImage(sprite, -PLAYER_SPRITE_EXTENT, -PLAYER_SPRITE_EXTENT,
                PLAYER_SPRITE_EXTENT * 2.0, PLAYER_SPRITE_EXTENT * 2.0);
        gc.restore();
        perfPlayerSpriteDraws++;
    }

    private Image createPlayerSprite(Color bodyColor, boolean armed) {
        double pixelSize = PLAYER_SPRITE_EXTENT * 2.0 * PLAYER_SPRITE_SCALE;
        Canvas spriteCanvas = new Canvas(pixelSize, pixelSize);
        GraphicsContext spriteGc = spriteCanvas.getGraphicsContext2D();
        spriteGc.scale(PLAYER_SPRITE_SCALE, PLAYER_SPRITE_SCALE);
        double center = PLAYER_SPRITE_EXTENT;
        spriteGc.setFill(bodyColor);
        spriteGc.fillOval(center - PLAYER_SIZE / 2.0, center - PLAYER_SIZE / 2.0,
                PLAYER_SIZE, PLAYER_SIZE);
        spriteGc.setFill(Color.GOLD);
        spriteGc.fillOval(center + PLAYER_SIZE / 4.0 - PLAYER_SIZE / 8.0,
                center - PLAYER_SIZE / 8.0, PLAYER_SIZE / 4.0, PLAYER_SIZE / 4.0);
        if (armed) {
            spriteGc.setStroke(Color.web("#CBD5E0"));
            spriteGc.setLineWidth(3);
            spriteGc.strokeLine(center, center, center + PLAYER_SIZE / 2.0 + 5, center);
        }
        javafx.scene.SnapshotParameters parameters = new javafx.scene.SnapshotParameters();
        parameters.setFill(Color.TRANSPARENT);
        perfSpriteBuilds++;
        return spriteCanvas.snapshot(parameters, null);
    }

    private void drawObstacle(GraphicsContext targetGc, JsonObject obs) {
        String type = getString(obs, "type"); // 获取障碍物类型

        if ("RECTANGLE".equals(type)) { // 如果是矩形
            targetGc.fillRect(getDouble(obs, "x"), getDouble(obs, "y"), getDouble(obs, "w"), getDouble(obs, "h"));
        } else if ("ELLIPSE".equals(type)) { // 如果是椭圆形
            targetGc.fillOval(getDouble(obs, "x"), getDouble(obs, "y"), getDouble(obs, "w"), getDouble(obs, "h"));
        }

        // --- [还原] ---
        // 烘焙缓存
        else if ("POLYGON".equals(type) && obs.has("xPoints")) { // 如果是多边形
            JsonArray xPointsJson = obs.getAsJsonArray("xPoints"); // 获取X坐标数组
            JsonArray yPointsJson = obs.getAsJsonArray("yPoints"); // 获取Y坐标数组
            double[] xPoints = new double[xPointsJson.size()], yPoints = new double[yPointsJson.size()];
            for (int i = 0; i < xPoints.length; i++)
                xPoints[i] = xPointsJson.get(i).getAsDouble();
            for (int i = 0; i < yPoints.length; i++)
                yPoints[i] = yPointsJson.get(i).getAsDouble();
            targetGc.fillPolygon(xPoints, yPoints, xPoints.length); // 绘制多边形
        }
    }

    // 绘制一个炸弹点
    private void drawBombSite(JsonObject site, String label) {
        double x = getDouble(site, "x"), y = getDouble(site, "y"), w = getDouble(site, "w"), h = getDouble(site, "h");
        gc.setStroke(Color.rgb(255, 165, 0, 0.5)); // 设置描边颜色（半透明橙色）
        gc.setFill(Color.rgb(255, 165, 0, 0.1)); // 设置填充颜色（更透明的橙色）
        gc.setLineWidth(4); // 设置线宽
        gc.fillRect(x, y, w, h); // 填充矩形区域
        gc.strokeRect(x, y, w, h); // 描边矩形区域
        gc.setFill(Color.rgb(255, 165, 0, 0.5)); // 设置文字颜色
        gc.setFont(Font.font("Orbitron", FontWeight.BOLD, 48)); // 设置字体
        gc.setTextAlign(TextAlignment.CENTER); // 设置文本水平居中
        gc.setTextBaseline(VPos.CENTER); // 设置文本垂直居中
        gc.fillText(label, x + w / 2, y + h / 2); // 绘制标签（A或B）
        gc.setTextBaseline(VPos.BASELINE); // 恢复默认的垂直对齐方式
    }

    // 绘制屏幕外目标指示器
    private void drawOffscreenIndicators() {
        // 如果相机模式不是“跟随”或玩家对象为空，则不绘制
        if (!"follow".equals(cameraMode) || me == null)
            return;

        // 获取我自己在屏幕上的位置
        Point2D myScreenPos = camera.worldToScreen(me.renderX, me.renderY);

        // 创建一个列表来存储所有需要检查的实体
        List<JsonObject> allEntities = new ArrayList<>();
        // 将所有玩家添加到实体列表
        clientPlayers.values().forEach(p -> allEntities.add(p.data));
        // 将所有掉落物品添加到实体列表
        droppedItems.values().forEach(allEntities::add);

        // 遍历所有实体
        allEntities.forEach(entity -> {
            // 初始化绘制标志、颜色、指示器类型和位置
            boolean shouldDraw = true;
            Color color = Color.WHITE;
            String indicatorType = "none";
            Point2D pos = null;

            // 如果实体是玩家
            if (entity.has("isAlive")) {
                // 如果是自己或已死亡，则不绘制
                if (getString(entity, "id").equals(myPlayerId) || !getBool(entity, "isAlive"))
                    return;
                cs2d.client.GameClient.ClientPlayer p = clientPlayers.get(getString(entity, "id")); // 获取玩家对象
                if (p == null)
                    return; // 如果玩家对象为空，则不绘制
                pos = new Point2D(p.renderX, p.renderY); // 获取玩家位置
                String mode = (latestGameState != null) ? getString(latestGameState, "mode") : "";
                if (!"DEATHMATCH".equals(mode) && getString(entity, "team").equals(getString(me.data, "team"))) { // 如果是队友
                    indicatorType = "player_team";
                    color = Color.rgb(99, 179, 237, 0.8); // 设置为蓝色队友指示器
                } else { // 如果是敌人 (死斗模式下所有人都是敌人)
                    if (!isCircleVisibleInPolygon(pos, PLAYER_SIZE / 2.0, fovPoints))
                        shouldDraw = false; // 如果敌人不在视野内，则不绘制
                    indicatorType = "player_enemy";
                    color = Color.rgb(245, 101, 101, 0.8); // 设置为红色敌人指示器
                }
            } else if (entity.has("isBomb")) { // 如果实体是C4
                pos = new Point2D(getDouble(entity, "x"), getDouble(entity, "y")); // 获取物品位置
                if (getBool(entity, "isBomb")) { // 确认是炸弹
                    indicatorType = "bomb";
                    color = Color.rgb(255, 255, 0, 0.8); // 设置为黄色炸弹指示器
                } else {
                    shouldDraw = false;
                }
            } else {
                shouldDraw = false;
            }

            // 如果不应绘制或位置为空，则返回
            if (!shouldDraw || pos == null)
                return;
            // 获取实体的屏幕位置
            Point2D screenPos = camera.worldToScreen(pos.getX(), pos.getY());
            // 如果实体在屏幕内，则不绘制指示器
            if (screenPos.getX() > PLAYER_SIZE && screenPos.getX() < CANVAS_WIDTH - PLAYER_SIZE
                    && screenPos.getY() > PLAYER_SIZE && screenPos.getY() < CANVAS_HEIGHT - PLAYER_SIZE)
                return;

            // 计算从我到目标的角度
            double angleToTarget = Math.atan2(screenPos.getY() - myScreenPos.getY(),
                    screenPos.getX() - myScreenPos.getX());
            double padding = 30;
            // 定义屏幕边界矩形 (简化为 (padding, padding) 到 (CANVAS_WIDTH-padding,
            // CANVAS_HEIGHT-padding))
            double minX = padding;
            double minY = padding;
            double maxX = CANVAS_WIDTH - padding;
            double maxY = CANVAS_HEIGHT - padding;

            // 玩家在屏幕上的位置
            double myScreenX = myScreenPos.getX();
            double myScreenY = myScreenPos.getY();

            double bestT = Double.MAX_VALUE;
            double indicatorX = myScreenX;
            double indicatorY = myScreenY;

            // --- 检查与四条边界的交点 ---

            // 检查左右边界 (X = minX 或 X = maxX)
            if (Math.abs(Math.cos(angleToTarget)) > 1e-6) {
                // 检查左边界
                double t = (minX - myScreenX) / Math.cos(angleToTarget);
                if (t > 0)
                    bestT = Math.min(bestT, t);

                // 检查右边界
                t = (maxX - myScreenX) / Math.cos(angleToTarget);
                if (t > 0)
                    bestT = Math.min(bestT, t);
            }

            // 检查上下边界 (Y = minY 或 Y = maxY)
            if (Math.abs(Math.sin(angleToTarget)) > 1e-6) {
                // 检查上边界
                double t = (minY - myScreenY) / Math.sin(angleToTarget);
                if (t > 0)
                    bestT = Math.min(bestT, t);

                // 检查下边界
                t = (maxY - myScreenY) / Math.sin(angleToTarget);
                if (t > 0)
                    bestT = Math.min(bestT, t);
            }

            // 如果找到交点，计算最终的指示器位置
            if (bestT != Double.MAX_VALUE) {
                // 计算射线与最近边界的交点
                indicatorX = myScreenX + Math.cos(angleToTarget) * bestT;
                indicatorY = myScreenY + Math.sin(angleToTarget) * bestT;

                // 由于浮点数误差，再次限制在内边距内
                indicatorX = Math.max(minX, Math.min(maxX, indicatorX));
                indicatorY = Math.max(minY, Math.min(maxY, indicatorY));
            } else {
                // 极端情况 (例如目标正好在玩家身上)，此时不绘制
                return;
            }

            // 绘制指示器
            gc.save(); // 保存状态
            gc.translate(indicatorX, indicatorY); // 平移到指示器位置
            gc.rotate(Math.toDegrees(angleToTarget)); // 旋转以朝向目标
            gc.setFill(color); // 设置填充颜色

            if (indicatorType.startsWith("player"))
                gc.fillPolygon(new double[] { 12, -6, -6 }, new double[] { 0, -8, 8 }, 3); // 如果是玩家，绘制三角形
            else if ("bomb".equals(indicatorType))
                gc.fillRect(-7, -7, 14, 14); // 如果是炸弹，绘制正方形

            gc.restore(); // 恢复状态
        });
    }

    // --- UI 更新逻辑 ---
    // 创建最终赛果标题；比赛结束界面与 TAB 计分板共用同一套内容。
    private Label createFinalResultLabel() {
        Label resultLabel = new Label("Game Over");
        resultLabel.setFont(Font.font("Orbitron", FontWeight.BOLD, 48));
        resultLabel.setTextFill(Color.WHITE);
        if (latestGameState == null)
            return resultLabel;
        String mode = getString(latestGameState, "mode"); // 获取游戏模式
        if ("TEAM_DEATHMATCH".equals(mode) || "DEMOLITION".equals(mode)) { // 如果是TDM或爆破
            int ctScore = getInt(latestGameState, "ctScore"), tScore = getInt(latestGameState, "tScore");
            if (ctScore > tScore) { // CT赢
                resultLabel.setText("Counter-Terrorists Win");
                resultLabel.setTextFill(Color.CYAN);
            } else if (tScore > ctScore) { // T赢
                resultLabel.setText("Terrorists Win");
                resultLabel.setTextFill(Color.RED);
            } else { // 平局
                resultLabel.setText("Draw");
            }
        } else if ("DEATHMATCH".equals(mode)) {
            resultLabel.setText("Game Over - Time's Up!");
        } else if ("ZOMBIE_MODE".equals(mode)) {
            resultLabel.setText("Game Over - Survived " + (getInt(latestGameState, "wave") - 1) + " Waves");
            resultLabel.setTextFill(Color.ORANGE);
        }
        return resultLabel;
    }

    // 更新购买菜单UI
    private void updateBuyMenuUI() {
        if (me == null)
            return; // 如果玩家对象为空，则返回
        JsonObject meData = me.data; // 获取我的数据
        int money = getInt(meData, "money"); // 获取金钱
        String team = getString(meData, "team"); // 获取队伍
        List<String> boughtItems = getJsonStringArray(meData, "itemsBoughtThisFreezeTime"); // 获取本回合已购买物品列表

        // 遍历所有购买按钮
        buyMenuButtons.forEach((key, buyBtn) -> {
            cs2d.client.GameClient.ItemUIData item = ITEM_UI_DATA.get(key);
            cs2d.client.GameClient.WeaponUIData weapon = WEAPON_UI_DATA.get(key);
            // 获取最大可购买数量
            int maxQuantity = (item != null) ? item.maxQuantity() : 1;

            // 获取玩家当前拥有该物品的数量
            int currentCount = 0;
            // 检查装备（手雷等）
            if (meData.has("equipment")) {
                Optional<JsonObject> ownedItem = StreamSupport
                        .stream(meData.getAsJsonArray("equipment").spliterator(), false)
                        .map(JsonElement::getAsJsonObject)
                        .filter(g -> getString(g, "name").equals(key)).findFirst();
                if (ownedItem.isPresent()) {
                    currentCount = getInt(ownedItem.get(), "count");
                }
            }
            // 检查武器
            if (weapon != null && getString(meData, "weaponKey").equals(key)) {
                currentCount = 1;
            }

            // --- 修正护甲和钳子的检查逻辑 ---
            switch (key) {
                case "KEVLAR_HELMET":
                    if (getBool(meData, "hasHelmet"))
                        currentCount = 1; // 只要有头盔，就算拥有大甲
                    break;
                case "KEVLAR":
                    if (getBool(meData, "hasKevlar"))
                        currentCount = 1; // 只要有甲，就算拥有小甲
                    break;
                case "DEFUSE_KIT":
                    if (getBool(meData, "hasDefuseKit"))
                        currentCount = 1; // 如果有拆弹器
                    break;
            }
            // --- 修复结束 ---

            // 检查本回合是否购买过
            boolean wasBoughtThisRound = boughtItems.contains(key);

            // 获取所有相关的按钮
            Button undoBtn = undoMenuButtons.get(key);
            Button undoAllBtn = undoMenuButtons.get(key + "_ALL");

            // --- 在这里添加动态颜色逻辑 ---
            Node iconNode = buyBtn.getGraphic(); // 获取按钮上的图标
            if (iconNode instanceof SVGPath svgPath) {
                // 根据阵营决定图标颜色
                Color iconColor = "CT".equals(team) ? PRIMARY_BLUE : Color.web("#DE9B35");
                svgPath.setFill(iconColor);
            }

            // 根据数量和购买状态，决定显示哪个按钮
            boolean showBuy = true;
            boolean showUndo = false;
            boolean showUndoAll = false;
            if (currentCount >= maxQuantity) { // 如果已达到最大数量
                showBuy = false;
                if (wasBoughtThisRound) { // 如果是本回合买的
                    if (maxQuantity > 1)
                        showUndoAll = true; // 显示全部撤销
                    else
                        showUndo = true; // 显示撤销
                }
            } else if (currentCount > 0 && wasBoughtThisRound) { // 如果没到最大数量，但本回合买过
                showBuy = true; // 仍然可以买
                showUndoAll = true; // 显示全部撤销
            }

            // 应用按钮的可见性
            buyBtn.setVisible(showBuy);
            if (undoBtn != null)
                undoBtn.setVisible(showUndo);
            if (undoAllBtn != null)
                undoAllBtn.setVisible(showUndoAll);

            // 更新“购买”按钮的禁用状态（如果它可见）
            if (showBuy) {
                boolean disabled = false;
                if (weapon != null)
                    disabled = money < weapon.cost(); // 钱不够
                else if (item != null)
                    disabled = money < item.cost(); // 钱不够

                // 禁用逻辑也要同步更新
                if ("KEVLAR_HELMET".equals(key) && getBool(meData, "hasHelmet"))
                    disabled = true; // 已有大甲
                if ("KEVLAR".equals(key) && getBool(meData, "hasKevlar"))
                    disabled = true; // 已有甲
                if ("DEFUSE_KIT".equals(key) && getBool(meData, "hasDefuseKit"))
                    disabled = true; // 已有钳子

                // 如果是阵营专属道具且队伍不符，则隐藏按钮
                if (item != null && item.team() != null && !item.team().equals(team)) {
                    buyBtn.setManaged(false); // 不参与布局
                    buyBtn.setVisible(false); // 隐藏
                } else {
                    buyBtn.setManaged(true); // 参与布局
                }

                buyBtn.setDisable(disabled); // 设置禁用状态
                buyBtn.setStyle(buttonStyle + (disabled ? buttonDisabledStyle : "")); // 应用样式
            }
        });

        // --- 更新地上武器拾取按钮 ---
        Set<String> nearbyItemIds = new HashSet<>(); // 创建一个集合来存储附近的物品ID
        // 筛选出附近的可拾取武器
        droppedItems.values().stream()
                .filter(item -> !getBool(item, "isBomb")) // 过滤掉炸弹
                .filter(item -> new Point2D(getDouble(item, "x"), getDouble(item, "y")).distance(me.renderX,
                        me.renderY) < 150) // 过滤出距离小于150的物品
                .forEach(item -> nearbyItemIds.add(getString(item, "id"))); // 将ID添加到集合

        // 移除已经不在附近的武器按钮
        groundWeaponButtons.entrySet().removeIf(entry -> {
            if (!nearbyItemIds.contains(entry.getKey())) { // 如果按钮对应的物品ID不在附近了
                Platform.runLater(() -> groundWeaponsContainer.getChildren().remove(entry.getValue())); // 从UI中移除按钮
                return true; // 从映射中移除
            }
            return false;
        });

        // 为新出现的附近武器创建按钮
        droppedItems.values().stream()
                .filter(item -> !getBool(item, "isBomb")) // 过滤掉炸弹
                .filter(item -> new Point2D(getDouble(item, "x"), getDouble(item, "y")).distance(me.renderX,
                        me.renderY) < 150) // 过滤出距离小于150的物品
                .forEach(item -> {
                    String itemId = getString(item, "id"); // 获取物品ID
                    if (!groundWeaponButtons.containsKey(itemId)) { // 如果还没有为该物品创建按钮
                        String name = getString(item, "name");
                        cs2d.client.GameClient.WeaponUIData data = WEAPON_UI_DATA.get(name);

                        Button weaponButton = new Button(); // 创建一个空按钮
                        Node weaponIcon = cs2d.client.GameClient.WeaponIcon.getIcon(name, false); // 获取武器图标
                        if (weaponIcon instanceof SVGPath svgPath) {
                            Color iconColor = "CT".equals(team) ? PRIMARY_BLUE : Color.web("#DE9B35"); // 根据阵营设置颜色
                            svgPath.setFill(iconColor);
                            svgPath.setScaleX(1.2);
                            svgPath.setScaleY(1.2);
                            weaponButton.setGraphic(svgPath); // 将图标设置给按钮
                        }

                        weaponButton.setText(data != null ? data.name() : name); // 设置按钮文本
                        weaponButton.setContentDisplay(ContentDisplay.LEFT); // 图标在左，文字在右
                        weaponButton.setGraphicTextGap(10); // 设置图标和文字的间距

                        styleButton(weaponButton); // 应用样式
                        weaponButton.setOnAction(e -> { // 设置点击事件
                            sendMessage(createJsonMessage("pickupWeapon", "itemId", itemId)); // 发送拾取武器的消息
                            buyMenuPane.setVisible(false); // 隐藏购买菜单
                        });
                        groundWeaponButtons.put(itemId, weaponButton); // 将按钮添加到映射中
                        Platform.runLater(() -> groundWeaponsContainer.getChildren().add(weaponButton)); // 将按钮添加到UI容器中
                    }
                });

        // 更新“附近无武器”的提示标签
        Platform.runLater(() -> {
            Node noItemsLabel = groundWeaponsContainer.lookup("#noItemsLabel"); // 查找提示标签
            if (groundWeaponButtons.isEmpty()) { // 如果地上没有武器
                if (noItemsLabel == null) { // 如果还没有提示标签
                    Label label = new Label("No weapons nearby to pick up");
                    label.setId("noItemsLabel");
                    label.setTextFill(Color.GRAY);
                    groundWeaponsContainer.getChildren().add(label);
                }
            } else { // 如果有武器
                if (noItemsLabel != null) { // 如果有提示标签，则移除它
                    groundWeaponsContainer.getChildren().remove(noItemsLabel);
                }
            }
        });
    }

    // 更新记分板UI
    private void updateScoreboardUI() {
        // ScrollPane 和内容根节点始终复用；常规刷新只更新 Label 文本，不触碰滚动位置。
        if (latestGameState == null || (clientPlayers.isEmpty() && clientZombies.isEmpty())) {
            scoreboardContent.getChildren().clear();
            scoreboardStructureKey = null;
            scoreboardSectionLabels.clear();
            scoreboardPlayerLabels.clear();
            return;
        }

        boolean finalScoreboard = isFinalScoreboardState(clientState);

        // 获取并排序所有人类玩家数据
        List<JsonObject> playersData = clientPlayers.values().stream()
                .map(p -> p.data)
                .sorted((p1, p2) -> {
                    // [核心修复] 使用一致的本地计算逻辑进行排序，确保排名与显示分一致
                    int s1 = calculateLocalScoreForSorting(p1);
                    int s2 = calculateLocalScoreForSorting(p2);
                    if (s1 != s2)
                        return Integer.compare(s2, s1);

                    int k1 = getInt(p1, "kills"), k2 = getInt(p2, "kills");
                    if (k1 != k2)
                        return Integer.compare(k2, k1);

                    return Integer.compare(getInt(p1, "deaths"), getInt(p2, "deaths"));
                })
                .collect(Collectors.toList());

        // 获取游戏模式
        String mode = getString(latestGameState, "mode");
        List<ScoreboardSection> sections = new ArrayList<>();
        if ("ZOMBIE_MODE".equals(mode)) {
            // 幸存者列表 (非 ZOMBIE 队伍的人类)
            List<JsonObject> survivors = playersData.stream()
                    .filter(p -> !"ZOMBIE".equals(getString(p, "team")))
                    .collect(Collectors.toList());

            // 僵尸列表 (ZOMBIE 队伍的人类 + AI 僵尸)
            List<JsonObject> zombies = playersData.stream()
                    .filter(p -> "ZOMBIE".equals(getString(p, "team")))
                    .collect(Collectors.toCollection(ArrayList::new));

            synchronized (clientZombies) {
                clientZombies.values().forEach(z -> zombies.add(z.data));
            }
            // 对僵尸进行排序
            zombies.sort((p1, p2) -> {
                int s1 = getInt(p1, "score"), s2 = getInt(p2, "score");
                if (s1 != s2)
                    return Integer.compare(s2, s1);
                return Integer.compare(getInt(p2, "kills"), getInt(p1, "kills"));
            });

            sections.add(new ScoreboardSection("survivors", survivors, "Survivors", "#63b3ed"));
            if (!zombies.isEmpty()) {
                sections.add(new ScoreboardSection("zombies", zombies, "Zombies", "#48BB78"));
            }
        } else if ("DEATHMATCH".equals(mode)) {
            sections.add(new ScoreboardSection("deathmatch", playersData, "Deathmatch", "#F6E05E"));
        } else {
            // TDM, DEMO
            List<JsonObject> ctPlayers = playersData.stream().filter(p -> "CT".equals(getString(p, "team")))
                    .collect(Collectors.toList());
            List<JsonObject> tPlayers = playersData.stream().filter(p -> "T".equals(getString(p, "team")))
                    .collect(Collectors.toList());

            int ctScore = "TEAM_DEATHMATCH".equals(mode) ? ctPlayers.stream().mapToInt(p -> getInt(p, "kills")).sum()
                    : getInt(latestGameState, "ctScore");
            int tScore = "TEAM_DEATHMATCH".equals(mode) ? tPlayers.stream().mapToInt(p -> getInt(p, "kills")).sum()
                    : getInt(latestGameState, "tScore");

            sections.add(new ScoreboardSection("ct", ctPlayers,
                    "Counter-Terrorists   [ " + ctScore + " ]", "#63b3ed"));
            sections.add(new ScoreboardSection("t", tPlayers,
                    "Terrorists   [ " + tScore + " ]", "#f56565"));
        }

        String structureKey = createScoreboardStructureKey(finalScoreboard, mode, sections);
        if (!structureKey.equals(scoreboardStructureKey)) {
            rebuildScoreboardContent(finalScoreboard, mode, sections);
            scoreboardStructureKey = structureKey;
        } else {
            refreshScoreboardData(sections);
        }
    }

    private String createScoreboardStructureKey(boolean finalScoreboard, String mode,
            List<ScoreboardSection> sections) {
        StringBuilder key = new StringBuilder(mode).append('|').append(finalScoreboard);
        for (ScoreboardSection section : sections) {
            key.append('|').append(section.key());
            for (JsonObject player : section.players())
                key.append(':').append(getString(player, "id"));
        }
        return key.toString();
    }

    private void rebuildScoreboardContent(boolean finalScoreboard, String mode,
            List<ScoreboardSection> sections) {
        double retainedScrollPosition = scoreboardScrollPane.getVvalue();
        scoreboardSectionLabels.clear();
        scoreboardPlayerLabels.clear();
        scoreboardContent.getChildren().clear();

        Label title = new Label(finalScoreboard ? "Final Scoreboard" : "Scoreboard");
        title.setFont(titleFont);
        title.setStyle("-fx-text-fill: yellow;");
        scoreboardContent.getChildren().add(title);
        if (finalScoreboard)
            scoreboardContent.getChildren().add(createFinalResultLabel());

        for (ScoreboardSection section : sections)
            addTeamSection(scoreboardContent, section, mode);

        if (finalScoreboard) {
            Button backButton = new Button("Back to Lobby");
            styleButton(backButton);
            backButton.setFont(hudFont);
            backButton.setOnAction(e -> setClientState(cs2d.client.GameClient.ClientState.LOBBY));
            scoreboardContent.getChildren().add(backButton);
        }
        scoreboardScrollPane.setVvalue(retainedScrollPosition);
    }

    private void refreshScoreboardData(List<ScoreboardSection> sections) {
        for (ScoreboardSection section : sections) {
            Label sectionLabel = scoreboardSectionLabels.get(section.key());
            if (sectionLabel != null)
                sectionLabel.setText(section.title());
            for (JsonObject player : section.players())
                updateScoreboardPlayerLabels(player);
        }
    }

    private static boolean isFinalScoreboardState(cs2d.client.GameClient.ClientState state) {
        return state == cs2d.client.GameClient.ClientState.GAME_OVER;
    }

    private void addTeamSection(VBox container, ScoreboardSection section, String mode) {
        Label teamLabel = new Label(section.title());
        teamLabel.setFont(Font.font("Orbitron", FontWeight.BOLD, 24));
        teamLabel.setStyle("-fx-text-fill: " + section.color() + ";");
        scoreboardSectionLabels.put(section.key(), teamLabel);
        container.getChildren().add(teamLabel);
        container.getChildren().add(createTeamTableNode(section.players(), section.color(), mode));
    }

    // 为一个队伍创建一个分数表格节点
    private GridPane createTeamTableNode(List<JsonObject> players, String teamColor, String mode) {
        GridPane grid = new GridPane(); // 创建一个网格布局
        grid.setStyle("-fx-border-color: #4a5568; -fx-border-width: 0 0 1 0;"); // 设置底部边框
        grid.setHgap(10);
        grid.setVgap(5); // 设置水平和垂直间距

        // 核心修正：严格定义 9 列宽度 (新增 Weapon)
        // Name(22) | Score(8) | Weapon(12) | K(7) | D(7) | Damage(10) | Accuracy(11) |
        // HS%(11) | Ping(12)
        double[] colWidths = { 22, 8, 12, 7, 7, 10, 11, 11, 12 };
        grid.getColumnConstraints().clear();
        for (double width : colWidths) {
            ColumnConstraints col = new ColumnConstraints();
            col.setPercentWidth(width);
            grid.getColumnConstraints().add(col);
        }

        // 核心修正：严格定义 9 个表头
        String[] headers = { "Name", "Score", "Weapon", "K", "D", "Damage", "Accuracy", "HS%", "Ping" };

        for (int i = 0; i < headers.length; i++) {
            Label headerLabel = new Label(headers[i]);
            headerLabel.setFont(scoreboardFont);
            headerLabel.setStyle("-fx-font-weight: bold; -fx-text-fill: " + teamColor + ";");
            headerLabel.setMaxWidth(Double.MAX_VALUE);
            if (i > 0)
                headerLabel.setAlignment(Pos.CENTER); // 除名字外居中
            grid.add(headerLabel, i, 0);
        }

        // 填充数据行
        int rowIndex = 1;
        for (JsonObject p : players) {
            String playerId = getString(p, "id");
            Label nameLabel = new Label();
            nameLabel.setFont(scoreboardFont);
            grid.add(nameLabel, 0, rowIndex);

            List<Label> labels = new ArrayList<>(9);
            labels.add(nameLabel);
            for (int column = 1; column < 9; column++) {
                Label statLabel = createStatLabel("");
                labels.add(statLabel);
                grid.add(statLabel, column, rowIndex);
            }
            scoreboardPlayerLabels.put(playerId, labels);
            updateScoreboardPlayerLabels(p);

            rowIndex++;
        }
        return grid;
    }

    private void updateScoreboardPlayerLabels(JsonObject player) {
        String playerId = getString(player, "id");
        List<Label> labels = scoreboardPlayerLabels.get(playerId);
        if (labels == null || labels.size() != 9)
            return;

        String weaponName = getString(player, "weaponName");
        if (weaponName == null || weaponName.isEmpty())
            weaponName = "KNIFE";
        int kills = getInt(player, "kills");
        int deaths = getInt(player, "deaths");
        int damage = (int) getDouble(player, "damageDealt");
        double shotsFired = getDouble(player, "totalShotsFired");
        double shotsHit = getDouble(player, "totalShotsHit");
        int headshots = getInt(player, "totalHeadshots");
        int localScore = damage + (kills * 50) + (headshots * 20) - (deaths * 50);

        labels.get(0).setText(getString(player, "name"));
        String team = getString(player, "team");
        labels.get(0).setTextFill(myPlayerId != null && playerId.equals(myPlayerId) ? PRIMARY_GREEN
                : "CT".equals(team) ? PRIMARY_BLUE
                        : "T".equals(team) ? PRIMARY_RED
                                : "ZOMBIE".equals(team) ? Color.PURPLE : Color.YELLOW);
        labels.get(1).setText(String.valueOf(localScore));
        labels.get(2).setText(weaponName);
        labels.get(3).setText(String.valueOf(kills));
        labels.get(4).setText(String.valueOf(deaths));
        labels.get(5).setText(String.valueOf(damage));
        labels.get(6).setText(shotsFired > 0 ? String.format("%.1f%%", (shotsHit / shotsFired) * 100) : "0.0%");
        labels.get(7).setText(shotsHit > 0 ? String.format("%.1f%%", (headshots / shotsHit) * 100) : "0.0%");
        labels.get(8).setText(myPlayerId != null && playerId.equals(myPlayerId) ? String.valueOf(ping) : "N/A");
    }

    private record ScoreboardSection(String key, List<JsonObject> players, String title, String color) {
    }

    private Label createStatLabel(String text) {
        Label label = new Label(text);
        label.setFont(scoreboardFont);
        label.setTextFill(Color.YELLOW);
        label.setMaxWidth(Double.MAX_VALUE);
        label.setAlignment(Pos.CENTER); // 居中显示，防止挤在一起
        return label;
    }

    // --- UI 创建方法 ---
    // 加载自定义字体的方法
    private static void loadCustomFonts() {
        try {
            String fontPath = "/fonts/Orbitron-VariableFont_wght.ttf"; // 字体文件路径
            InputStream fontStream = cs2d.client.GameClient.class.getResourceAsStream(fontPath); // 获取字体文件的输入流
            if (fontStream == null)
                throw new IOException("Cannot find font file: " + fontPath); // 如果找不到字体文件，则抛出异常
            Font baseFont = Font.loadFont(fontStream, 10); // 加载基础字体
            // 创建各种大小和粗细的字体
            hudFont = Font.font(baseFont.getFamily(), FontWeight.BOLD, 24);
            smallHudFont = Font.font(baseFont.getFamily(), FontWeight.NORMAL, 16);
            tinyHudFont = Font.font(baseFont.getFamily(), FontWeight.NORMAL, 12);
            scoreboardFont = Font.font(baseFont.getFamily(), FontWeight.NORMAL, 18);
            titleFont = Font.font(baseFont.getFamily(), FontWeight.BOLD, 48);
            lobbyTitleFont = Font.font(baseFont.getFamily(), FontWeight.BOLD, 60);
            buttonFont = Font.font(baseFont.getFamily(), FontWeight.SEMI_BOLD, 16);
        } catch (Exception e) { // 捕获异常
            // 打印错误信息，并回退到系统默认字体
            System.err.println(
                    "Failed to load custom Orbitron font. Falling back to system default. Error: " + e.getMessage());
            String defaultFont = "System";
            hudFont = Font.font(defaultFont, FontWeight.BOLD, 24);
            smallHudFont = Font.font(defaultFont, FontWeight.NORMAL, 16);
            tinyHudFont = Font.font(defaultFont, FontWeight.NORMAL, 12);
            scoreboardFont = Font.font(defaultFont, FontWeight.NORMAL, 18);
            titleFont = Font.font(defaultFont, FontWeight.BOLD, 48);
            lobbyTitleFont = Font.font(defaultFont, FontWeight.BOLD, 60);
            buttonFont = Font.font(defaultFont, FontWeight.SEMI_BOLD, 16);
        }
    }

    // 创建服务器浏览器UI的方法
    private void createServerBrowserUI() {
        serverBrowserPane = new VBox(20);
        serverBrowserPane.setAlignment(Pos.CENTER);
        serverBrowserPane.setPrefSize(CANVAS_WIDTH, CANVAS_HEIGHT);
        serverBrowserPane.setStyle("-fx-background-color: " + toCssColor(BACKGROUND_DARK) + ";");
        serverBrowserPane.setPadding(new Insets(40));

        Label title = new Label("LAN SERVER BROWSER");
        title.setFont(lobbyTitleFont);
        title.setTextFill(PRIMARY_BLUE);
        title.setStyle("-fx-effect: dropshadow(gaussian, rgba(99,179,237,0.4), 8, 0, 0, 2);");

        VBox listContainer = new VBox(15);
        listContainer.setAlignment(Pos.TOP_CENTER);
        listContainer.setStyle("-fx-background-color: #2d3748; -fx-padding: 20px; -fx-background-radius: 12px;");
        listContainer.setMinWidth(800);
        listContainer.setMaxHeight(500);

        ScrollPane scrollPane = new ScrollPane(listContainer);
        scrollPane.setFitToWidth(true);
        scrollPane.setStyle("-fx-background: transparent; -fx-background-color: transparent;");
        scrollPane.setMaxWidth(850);

        Button manualIpButton = new Button("Entry Manual IP");
        styleButton(manualIpButton);
        manualIpButton.setOnAction(e -> {
            serverBrowserPane.setVisible(false);
            ipEntryPane.setVisible(true);
        });

        serverBrowserPane.getChildren().addAll(title, scrollPane, manualIpButton);
    }

    private void updateServerBrowserList() {
        if (serverBrowserPane == null)
            return;
        ScrollPane sp = (ScrollPane) serverBrowserPane.getChildren().get(1);
        VBox listContainer = (VBox) sp.getContent();
        listContainer.getChildren().clear();

        if (discoveredServers.isEmpty()) {
            Label searchingLabel = new Label("Searching for LAN servers...");
            searchingLabel.setFont(hudFont);
            searchingLabel.setTextFill(Color.GRAY);
            listContainer.getChildren().add(searchingLabel);
        } else {
            // 按延迟从低到高排序
            discoveredServers.entrySet().stream()
                    .sorted((a, b) -> Long.compare(
                            a.getValue().has("latency") ? a.getValue().get("latency").getAsLong() : Long.MAX_VALUE,
                            b.getValue().has("latency") ? b.getValue().get("latency").getAsLong() : Long.MAX_VALUE))
                    .forEach(entry -> {
                        JsonObject info = entry.getValue();
                        HBox row = new HBox(20);
                        row.setAlignment(Pos.CENTER_LEFT);
                        row.setStyle(
                                "-fx-background-color: #1a202c; -fx-padding: 15px; -fx-background-radius: 8px; -fx-border-color: #4a5568; -fx-border-width: 1px;");

                        VBox infoBox = new VBox(5);
                        Label nameLabel = new Label(getString(info, "serverName"));
                        nameLabel.setFont(hudFont);
                        nameLabel.setTextFill(Color.YELLOW);

                        long latencyMs = info.has("latency") ? info.get("latency").getAsLong() : -1;
                        String bestIp = info.has("best_ip") ? getString(info, "best_ip") : getString(info, "server_ip");
                        String latencyStr = latencyMs >= 0 ? latencyMs + "ms" : "?ms";
                        // 根据延迟选择颜色：<50ms绿，<150ms黄，>=150ms红
                        Color latencyColor = latencyMs < 50 ? Color.LIMEGREEN
                                : latencyMs < 150 ? Color.YELLOW : Color.TOMATO;

                        Label detailLabel = new Label(
                                String.format("Map: %s | Mode: %s | Players: %d | AI: %d | IP: %s",
                                        getString(info, "mapName"), getString(info, "mode"),
                                        getInt(info, "playerCount"), getInt(info, "aiCount"), bestIp));
                        detailLabel.setFont(smallHudFont);
                        detailLabel.setTextFill(Color.LIGHTGRAY);

                        infoBox.getChildren().addAll(nameLabel, detailLabel);

                        Region spacer = new Region();
                        HBox.setHgrow(spacer, Priority.ALWAYS);

                        // 延迟标签
                        Label pingLabel = new Label(latencyStr);
                        pingLabel.setFont(hudFont);
                        pingLabel.setTextFill(latencyColor);
                        pingLabel.setMinWidth(70);
                        pingLabel.setAlignment(Pos.CENTER_RIGHT);

                        Button joinBtn = new Button("JOIN");
                        styleButton(joinBtn);
                        joinBtn.setMinWidth(120);
                        joinBtn.setOnAction(e -> {
                            // 使用延迟最低的实际 IP 连接
                            this.serverIp = bestIp;
                            this.serverPort = getInt(info, "server_port");
                            serverBrowserPane.setVisible(false);
                            lobbyPane.setVisible(true);
                            connect();
                            startGameLoop();
                        });

                        row.getChildren().addAll(infoBox, spacer, pingLabel, joinBtn);
                        listContainer.getChildren().add(row);
                    });
        }
    }

    private void startDiscoveryListener() {
        if (discoveryThread != null && discoveryThread.isAlive())
            return;

        running = true; // [核心修复] 确保发现线程循环能运行
        System.out.println("[Discovery] Starting LAN discovery listener...");

        try {
            discoverySocket = new DatagramSocket();
            discoverySocket.setBroadcast(true);
            discoverySocket.setSoTimeout(2000);
            System.out.println("[Discovery] Socket bound to port: " + discoverySocket.getLocalPort());
        } catch (SocketException e) {
            System.err.println("Failed to setup discovery socket: " + e.getMessage());
            return;
        }

        discoveryThread = new Thread(() -> {
            byte[] buffer = new byte[2048];
            // 增加一个微小的延迟，确保 UI 已经完全渲染且可见性状态已同步
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                return;
            }

            while (running && serverBrowserPane != null) {
                // 仅在浏览器可见时进行收发
                if (!serverBrowserPane.isVisible()) {
                    try {
                        Thread.sleep(500);
                    } catch (InterruptedException e) {
                        break;
                    }
                    continue;
                }

                // 在每次 receive 前主动广播探测包，记录发包时间用于延迟计算
                long probeSentAt = System.currentTimeMillis();
                broadcastDiscoveryProbe();

                try {
                    DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                    discoverySocket.receive(packet); // 阻塞等待，Socket 超时为 2s

                    long latency = System.currentTimeMillis() - probeSentAt;
                    String message = new String(packet.getData(), 0, packet.getLength(), StandardCharsets.UTF_8);
                    JsonObject json = gson.fromJson(message, JsonObject.class);

                    if (json != null && "server_info".equals(getString(json, "type"))) {
                        String actualIp = packet.getAddress().getHostAddress();
                        // [方案A] 用服务器自报的 server_ip 作为去重 key，同一台服务器无论多少网卡只算一条
                        String canonicalIp = getString(json, "server_ip"); // 服务器自报内网IP
                        String serverKey = canonicalIp + ":" + getInt(json, "server_port");

                        JsonObject existing = discoveredServers.get(serverKey);
                        long existingLatency = (existing != null && existing.has("latency"))
                                ? existing.get("latency").getAsLong()
                                : Long.MAX_VALUE;
                        long lastSeen = (existing != null && existing.has("last_seen"))
                                ? existing.get("last_seen").getAsLong()
                                : 0;
                        long nowMs = System.currentTimeMillis();

                        if (existing == null || latency < existingLatency || (nowMs - lastSeen) > 6000) {
                            // 这条路径更快（或首次发现，或旧数据已过期），全面更新为最优路径
                            json.addProperty("best_ip", actualIp); // 实际延迟最低的 IP，用于连接
                            json.addProperty("latency", latency); // 记录延迟，供 UI 显示
                            json.addProperty("last_seen", nowMs);
                            discoveredServers.put(serverKey, json);
                            System.out.println("[Discovery] Server " + serverKey
                                    + " via " + actualIp + " latency=" + latency + "ms");
                            Platform.runLater(this::updateServerBrowserList);
                        } else {
                            // 即使延迟不如以前好（如另一张低速网卡的附带回包），只要是收到了存活信号，
                            // 就必须无条件更新其房名、地图、人数等动态信息，并刷新存活时间戳
                            existing.addProperty("mapName", getString(json, "mapName"));
                            existing.addProperty("mode", getString(json, "mode"));
                            existing.addProperty("playerCount", getInt(json, "playerCount"));
                            existing.addProperty("aiCount", getInt(json, "aiCount"));
                            existing.addProperty("last_seen", nowMs);
                            Platform.runLater(this::updateServerBrowserList);
                        }
                    }
                } catch (SocketTimeoutException ignored) {
                    // 2s 内无回包，继续循环重新发探测
                } catch (IOException e) {
                    if (running) {
                        System.err.println("Discovery listener error: " + e.getMessage());
                        // [硬核修复] Windows 系统独有设定：针对未连接 UDP 套接字的收发引起的 ICMP Port Unreachable，
                        // 会返回持久的“Connection reset by peer”，如果不重新建立 Socket 将会导致永久性的卡死瘫痪。
                        try {
                            if (discoverySocket != null && !discoverySocket.isClosed()) {
                                discoverySocket.close();
                            }
                            discoverySocket = new DatagramSocket();
                            discoverySocket.setBroadcast(true);
                            discoverySocket.setSoTimeout(2000);
                        } catch (SocketException resetEx) {
                            // 忽略重建产生的故障
                        }
                    }
                }
            }
            if (discoverySocket != null) {
                discoverySocket.close();
                System.out.println("[Discovery] Listener socket closed.");
            }
        }, "LAN-Discovery-Thread");
        discoveryThread.setDaemon(true);
        discoveryThread.start();
    }

    private void broadcastDiscoveryProbe() {
        long now = System.currentTimeMillis();

        // [核心修复] 剔除超过 6 秒（丢失两次探针回包）的离线死服务器
        boolean removed = discoveredServers.entrySet().removeIf(entry -> {
            JsonObject info = entry.getValue();
            long lastSeen = info.has("last_seen") ? info.get("last_seen").getAsLong() : 0;
            return (now - lastSeen) > 6000;
        });
        if (removed) {
            Platform.runLater(this::updateServerBrowserList);
        }

        if (now - lastDiscoveryBroadcastTime < DISCOVERY_INTERVAL_MS)
            return;
        lastDiscoveryBroadcastTime = now;

        if (discoverySocket == null || discoverySocket.isClosed())
            return;

        JsonObject probe = new JsonObject();
        probe.addProperty("type", "discovery_probe");
        String message = gson.toJson(probe);
        byte[] buffer = message.getBytes(StandardCharsets.UTF_8);

        try {
            System.out.println("[Discovery] Broadcasting probe...");
            // 1. 尝试 255.255.255.255 全局广播
            discoverySocket
                    .send(new DatagramPacket(buffer, buffer.length, new InetSocketAddress("255.255.255.255", 14726)));

            // 2. 针对本地测试，直接尝试 127.0.0.1
            discoverySocket.send(new DatagramPacket(buffer, buffer.length, new InetSocketAddress("127.0.0.1", 14726)));

            // 3. 遍历所有网卡
            Enumeration<NetworkInterface> interfaces = NetworkInterface.getNetworkInterfaces();
            while (interfaces.hasMoreElements()) {
                NetworkInterface networkInterface = interfaces.nextElement();
                if (networkInterface.isLoopback() || !networkInterface.isUp())
                    continue;

                for (InterfaceAddress interfaceAddress : networkInterface.getInterfaceAddresses()) {
                    InetAddress broadcast = interfaceAddress.getBroadcast();
                    if (broadcast != null) {
                        discoverySocket.send(
                                new DatagramPacket(buffer, buffer.length, new InetSocketAddress(broadcast, 14726)));
                    }
                }
            }
        } catch (IOException e) {
            // 忽略错误
        }
    }

    // 创建IP输入UI的方法
    private void createIpEntryUI() {
        ipEntryPane = new VBox(20); // 创建一个垂直布局容器
        ipEntryPane.setAlignment(Pos.CENTER); // 设置居中对齐
        ipEntryPane.setPrefSize(CANVAS_WIDTH, CANVAS_HEIGHT); // 设置首选大小
        ipEntryPane.setStyle("-fx-background-color: " + toCssColor(BACKGROUND_DARK) + ";"); // 设置背景颜色
        ipEntryPane.setPadding(new Insets(40)); // 设置内边距

        Label title = new Label("CS2D ONLINE"); // 创建一个标题标签
        title.setFont(lobbyTitleFont); // 设置字体
        title.setTextFill(PRIMARY_BLUE); // 设置文本颜色
        title.setStyle("-fx-effect: dropshadow(gaussian, rgba(99,179,237,0.4), 8, 0, 0, 2);"); // 设置阴影效果

        VBox formBox = new VBox(15); // 创建一个表单框
        formBox.setAlignment(Pos.CENTER); // 设置居中对齐
        formBox.setMaxWidth(400); // 设置最大宽度
        formBox.setStyle("-fx-background-color: #2d3748; -fx-padding: 30px; -fx-background-radius: 12px;"); // 设置样式

        TextField ipInput = new TextField(serverIp); // 创建一个IP输入框
        ipInput.setPromptText("Server IP"); // 设置提示文本
        ipInput.setFont(hudFont); // 设置字体
        styleTextField(ipInput); // 设置文本框样式

        TextField portInput = new TextField(String.valueOf(serverPort)); // 创建一个端口输入框
        portInput.setPromptText("Port"); // 设置提示文本
        portInput.setFont(hudFont); // 设置字体
        styleTextField(portInput); // 设置文本框样式

        Button entryButton = new Button("Entry"); // 创建一个进入按钮
        styleButton(entryButton); // 设置按钮样式
        entryButton.setMaxWidth(Double.MAX_VALUE); // 设置最大宽度

        Label errorLabel = new Label(); // 创建一个错误标签
        errorLabel.setId("errorLabel"); // 设置ID
        errorLabel.setTextFill(Color.RED); // 设置文本颜色为红色
        errorLabel.setFont(smallHudFont); // 设置字体

        entryButton.setOnAction(e -> { // 设置进入按钮的点击事件
            String ip = ipInput.getText(); // 获取IP
            String portStr = portInput.getText(); // 获取端口
            if (ip == null || ip.isBlank()) { // 如果IP为空
                errorLabel.setText("IP address cannot be empty."); // 设置错误信息
                return;
            }
            try {
                int port = Integer.parseInt(portStr); // 将端口字符串转换为整数
                if (port <= 0 || port > 65535) { // 如果端口号无效
                    errorLabel.setText("Port number must be between 1 and 65535."); // 设置错误信息
                    return;
                }
                this.serverIp = ip; // 更新服务器IP
                this.serverPort = port; // 更新服务器端口

                ipEntryPane.setVisible(false); // 隐藏IP输入面板
                lobbyPane.setVisible(true); // 显示大厅面板
                connectionStatusLabel.setText("Connecting to server..."); // 更新连接状态标签

                connect(); // 连接服务器
                startGameLoop(); // 启动游戏循环

            } catch (NumberFormatException nfe) { // 捕获数字格式异常
                errorLabel.setText("Invalid port number format."); // 设置错误信息
            }
        });

        // [新增] 返回局域网搜索按钮
        Button backToScanButton = new Button("SCAN LAN SERVERS");
        styleButton(backToScanButton);
        backToScanButton.setMaxWidth(Double.MAX_VALUE);
        backToScanButton.setOnAction(e -> {
            ipEntryPane.setVisible(false);
            serverBrowserPane.setVisible(true);
            discoveredServers.clear(); // 清空列表触发重新扫描
        });

        formBox.getChildren().addAll(ipInput, portInput, entryButton, backToScanButton, errorLabel); // 将控件添加到表单框
        ipEntryPane.getChildren().addAll(title, formBox); // 将标题和表单框添加到IP输入面板
    }

    // 创建游戏UI的方法
    private void createGameUI() {
        gameContainer = new StackPane(); // 创建一个堆栈面板作为游戏容器
        gameContainer.setMaxSize(CANVAS_WIDTH, CANVAS_HEIGHT); // 设置最大大小
        canvas = new Canvas(CANVAS_WIDTH, CANVAS_HEIGHT); // 创建一个画布
        gc = canvas.getGraphicsContext2D(); // 获取画布的图形上下文
        double fogWidth = CANVAS_WIDTH + FOG_TEXTURE_MARGIN * 2.0;
        double fogHeight = CANVAS_HEIGHT + FOG_TEXTURE_MARGIN * 2.0;
        fogPath = new Path(
                new MoveTo(0, 0), new LineTo(fogWidth, 0),
                new LineTo(fogWidth, fogHeight), new LineTo(0, fogHeight),
                new ClosePath(), fogHoleStart, fogHoleClose);
        fogPath.setFillRule(FillRule.EVEN_ODD);
        fogPath.setStroke(null);
        fogPath.setManaged(false);
        fogPath.setMouseTransparent(true);
        fogPath.setVisible(false);
        fogPath.getTransforms().setAll(fogLayerTransform);
        hudCanvas = new Canvas(CANVAS_WIDTH, CANVAS_HEIGHT);
        hudCanvas.setManaged(false);
        hudCanvas.setMouseTransparent(true);
        hudGc = hudCanvas.getGraphicsContext2D();
        System.out.println("[Render] Fog pipeline: pooled vector path + camera transform");
        Pane renderPane = new Pane();
        if (USE_RESIDENT_STATIC_MAP_LAYER) {
            residentStaticMapLayer = new Group();
            residentStaticMapLayer.setManaged(false);
            residentStaticMapLayer.setMouseTransparent(true);
            residentStaticMapLayer.getTransforms().setAll(residentStaticMapTransform);
            renderPane.getChildren().add(residentStaticMapLayer);
            System.out.println("[Render] Static map layer: fixed full-resolution atlas");
        } else {
            System.out.println("[Render] Static map layer: Canvas fallback (enable with -Dcs2d.staticMapLayer=true)");
        }
        renderPane.getChildren().addAll(canvas, fogPath, hudCanvas);
        renderPane.setMinSize(CANVAS_WIDTH, CANVAS_HEIGHT);
        renderPane.setPrefSize(CANVAS_WIDTH, CANVAS_HEIGHT);
        renderPane.setMaxSize(CANVAS_WIDTH, CANVAS_HEIGHT);
        renderPane.setClip(new Rectangle(CANVAS_WIDTH, CANVAS_HEIGHT));
        gameRenderNode = renderPane;

        // 创建各种UI元素
        createBuyMenuUI();
        createScoreboardUI();
        createTdmWeaponSelectorUI();
        Node hudOverlay = createHUDOverlay();
        hudOverlay.setPickOnBounds(false); // 允许鼠标事件穿透HUD的透明区域
        gameContainer.setPickOnBounds(false);

        // 将所有UI元素添加到游戏容器中
        gameContainer.getChildren().addAll(gameRenderNode, hudOverlay, buyMenuPane, scoreboardPane, tdmWeaponSelectorPane);
        gameContainer.setVisible(false); // 初始时隐藏游戏容器
    }

    // 创建设置面板的方法
    private void createSettingsPanel() {
        settingsPane = new VBox(20); // 创建垂直布局
        settingsPane.setAlignment(Pos.CENTER); // 居中对齐
        settingsPane.setStyle(panelStyle + "-fx-background-color: rgba(26, 32, 44, 0.98);"); // 设置样式
        settingsPane.setPadding(new Insets(40)); // 设置内边距
        settingsPane.setVisible(false); // 默认隐藏
        settingsPane.setPrefSize(CANVAS_WIDTH, CANVAS_HEIGHT); // 设置首选大小

        VBox contentBox = new VBox(25); // 内容框
        contentBox.setMaxWidth(600);
        contentBox.setAlignment(Pos.CENTER_LEFT);
        contentBox.setStyle(
                "-fx-background-color: #2d3748; -fx-padding: 30px; -fx-background-radius: 12px; -fx-border-radius: 12px; -fx-effect: dropshadow(gaussian, rgba(0,0,0,0.2), 8, 0, 0, 2);");

        Label title = new Label("Settings"); // 标题
        title.setFont(titleFont);
        title.setStyle(
                "-fx-font: " + titleFont.getSize() + "px '" + titleFont.getFamily() + "'; -fx-text-fill: yellow;");
        title.setAlignment(Pos.CENTER);
        title.setMaxWidth(Double.MAX_VALUE);

        // --- 后坐力跟随开关 ---
        CheckBox followRecoilCheck = new CheckBox("Follow Recoil (CS2 Style)");
        followRecoilCheck.setFont(hudFont);
        followRecoilCheck.setTextFill(TEXT_LIGHT);
        followRecoilCheck.selectedProperty().bindBidirectional(gameSettings.followRecoilProperty()); // 双向绑定
        // [修复] 立即保存设置
        gameSettings.followRecoilProperty().addListener((obs, oldVal, newVal) -> saveSettings());

        // --- 准星颜色自定义 ---
        HBox crosshairColorBox = new HBox(15);
        crosshairColorBox.setAlignment(Pos.CENTER_LEFT);
        Label crosshairColorLabel = new Label("Crosshair Color:");
        crosshairColorLabel.setFont(smallHudFont);
        crosshairColorLabel.setTextFill(TEXT_LIGHT);
        ColorPicker crosshairColorPicker = new ColorPicker(gameSettings.getCrosshairColor());
        crosshairColorPicker.valueProperty().bindBidirectional(gameSettings.crosshairColorProperty()); // 双向绑定
        crosshairColorPicker.valueProperty().addListener((obs, oldVal, newVal) -> saveSettings());
        crosshairColorBox.getChildren().addAll(crosshairColorLabel, crosshairColorPicker);

        // --- 瞄准线设置 ---
        CheckBox showAimLineCheck = new CheckBox("Show Aim Line");
        showAimLineCheck.setFont(hudFont);
        showAimLineCheck.setTextFill(TEXT_LIGHT);
        showAimLineCheck.selectedProperty().bindBidirectional(gameSettings.showAimLineProperty()); // 双向绑定
        showAimLineCheck.selectedProperty().addListener((obs, oldVal, newVal) -> saveSettings());

        CheckBox wallPenPredictionCheck = new CheckBox("Wall Penetration Prediction");
        wallPenPredictionCheck.setFont(hudFont);
        wallPenPredictionCheck.setTextFill(TEXT_LIGHT);
        wallPenPredictionCheck.selectedProperty().bindBidirectional(gameSettings.wallPenetrationPredictionProperty()); // 双向绑定
        wallPenPredictionCheck.selectedProperty().addListener((obs, oldVal, newVal) -> saveSettings());

        // --- 穿墙颜色设置 ---
        VBox wallPenColorBox = new VBox(5);
        Label wallPenColorLabel = new Label("Penetration Colors (Full / None):");
        wallPenColorLabel.setFont(smallHudFont);
        wallPenColorLabel.setTextFill(TEXT_LIGHT);

        HBox wallPenPickers = new HBox(10);
        ColorPicker fullColorPicker = new ColorPicker();
        fullColorPicker.valueProperty().bindBidirectional(gameSettings.wallPenColorFullProperty());
        fullColorPicker.setStyle("-fx-color-label-visible: false;");

        ColorPicker noneColorPicker = new ColorPicker();
        noneColorPicker.valueProperty().bindBidirectional(gameSettings.wallPenColorNoneProperty());
        noneColorPicker.setStyle("-fx-color-label-visible: false;");

        wallPenPickers.getChildren().addAll(fullColorPicker, noneColorPicker);
        wallPenColorBox.getChildren().addAll(wallPenColorLabel, wallPenPickers);
        wallPenColorBox.disableProperty().bind(wallPenPredictionCheck.selectedProperty().not());

        HBox colorBox = new HBox(15);
        colorBox.setAlignment(Pos.CENTER_LEFT);
        Label colorLabel = new Label("Aim Line Color:");
        colorLabel.setFont(smallHudFont);
        colorLabel.setTextFill(TEXT_LIGHT);
        ColorPicker colorPicker = new ColorPicker(gameSettings.getAimLineColor());
        colorPicker.valueProperty().bindBidirectional(gameSettings.aimLineColorProperty()); // 双向绑定
        colorBox.getChildren().addAll(colorLabel, colorPicker);

        VBox opacityBox = new VBox(5);
        Label opacityLabel = new Label("Aim Line Opacity:");
        opacityLabel.setFont(smallHudFont);
        opacityLabel.setTextFill(TEXT_LIGHT);
        Slider opacitySlider = new Slider(0, 1, gameSettings.getAimLineOpacity());
        opacitySlider.valueProperty().bindBidirectional(gameSettings.aimLineOpacityProperty()); // 双向绑定
        opacityBox.getChildren().addAll(opacityLabel, opacitySlider);

        colorBox.disableProperty().bind(showAimLineCheck.selectedProperty().not()); // 绑定禁用状态
        opacityBox.disableProperty().bind(showAimLineCheck.selectedProperty().not()); // 绑定禁用状态

        // --- 击杀信息最大条数 ---
        VBox killFeedBox = new VBox(5);
        Label killFeedLabel = new Label("Max Kill Feed Entries:");
        killFeedLabel.setFont(smallHudFont);
        killFeedLabel.setTextFill(TEXT_LIGHT);

        Slider killFeedSlider = new Slider(0, 10, gameSettings.getMaxKillFeedEntries());
        killFeedSlider.setMajorTickUnit(1);
        killFeedSlider.setMinorTickCount(0);
        killFeedSlider.setShowTickLabels(true);
        killFeedSlider.setShowTickMarks(true);
        killFeedSlider.setSnapToTicks(true);

        Label killFeedValueLabel = new Label();
        killFeedValueLabel.setTextFill(TEXT_LIGHT);
        killFeedValueLabel.textProperty().bind(killFeedSlider.valueProperty().asString("%.0f")); // 绑定滑块值

        killFeedSlider.valueProperty()
                .addListener((obs, oldVal, newVal) -> gameSettings.maxKillFeedEntriesProperty().set(newVal.intValue())); // 监听滑块变化

        HBox sliderBox = new HBox(10, killFeedSlider, killFeedValueLabel);
        sliderBox.setAlignment(Pos.CENTER_LEFT);
        killFeedBox.getChildren().addAll(killFeedLabel, sliderBox);

        // --- 鼠标滚轮缩放开关 ---
        CheckBox mouseWheelZoomCheck = new CheckBox("Mouse Wheel Zoom");
        mouseWheelZoomCheck.setFont(hudFont);
        mouseWheelZoomCheck.setTextFill(TEXT_LIGHT);
        mouseWheelZoomCheck.selectedProperty().bindBidirectional(gameSettings.mouseWheelZoomEnabledProperty());
        mouseWheelZoomCheck.selectedProperty().addListener((obs, oldVal, newVal) -> saveSettings());

        // --- 跟随模式相机缩放 ---
        VBox zoomBox = new VBox(5);
        Label zoomLabel = new Label("Follow Camera Zoom:");
        zoomLabel.setFont(smallHudFont);
        zoomLabel.setTextFill(TEXT_LIGHT);

        // 滑块范围：-1.0 (放大) 到 +2.0 (缩小)，默认 0.0 (不缩放)
        Slider zoomSlider = new Slider(-1.0, 2.0, calculateSliderValue(gameSettings.getFollowZoomFactor()));
        zoomSlider.setMajorTickUnit(0.5); // 主要刻度单位
        zoomSlider.setMinorTickCount(4); // 次要刻度数量
        zoomSlider.setShowTickLabels(true); // 显示刻度标签
        zoomSlider.setShowTickMarks(true); // 显示刻度标记
        zoomSlider.setSnapToTicks(false); // 允许自由拖动

        Label zoomValueLabel = new Label(); // 用于显示具体的缩放倍数
        zoomValueLabel.setFont(smallHudFont);
        zoomValueLabel.setTextFill(TEXT_LIGHT);

        // 监听滑块值的变化
        zoomSlider.valueProperty().addListener((obs, oldVal, newVal) -> {
            double factor = calculateZoomFactor(newVal.doubleValue()); // 将滑块值映射为缩放系数
            gameSettings.setFollowZoomFactor(factor); // 更新设置
            zoomValueLabel.setText(String.format("Current: %.2fx", factor)); // 更新显示标签
        });

        // 初始化显示标签的值
        zoomValueLabel.setText(String.format("Current: %.2fx", gameSettings.getFollowZoomFactor()));

        zoomBox.getChildren().addAll(zoomLabel, zoomSlider, zoomValueLabel);

        // --- 退出连接按钮 ---
        Button disconnectButton = new Button("Disconnect to Menu");
        styleButton(disconnectButton);
        disconnectButton.setOnAction(e -> disconnect());
        disconnectButton.setMaxWidth(Double.MAX_VALUE);

        // --- 关闭按钮 ---
        Button closeButton = new Button("Close (Press ESC)");
        styleButton(closeButton);
        closeButton.setOnAction(e -> settingsPane.setVisible(false));
        closeButton.setMaxWidth(Double.MAX_VALUE);

        // --- 组装UI ---
        contentBox.getChildren().addAll(
                title,
                followRecoilCheck,
                crosshairColorBox,
                showAimLineCheck,
                wallPenPredictionCheck,
                wallPenColorBox,
                colorBox,
                opacityBox,
                killFeedBox,
                zoomBox,
                disconnectButton,
                closeButton);

        ScrollPane scrollPane = new ScrollPane(contentBox);
        scrollPane.setFitToWidth(true);
        scrollPane.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scrollPane.setVbarPolicy(ScrollPane.ScrollBarPolicy.AS_NEEDED);
        scrollPane.setStyle("-fx-background: transparent; -fx-background-color: transparent; -fx-padding: 0;");
        scrollPane.setMaxWidth(650);
        scrollPane.setMaxHeight(CANVAS_HEIGHT * 0.85);

        settingsPane.getChildren().add(scrollPane);
    }

    // 创建大厅UI的方法
    private void createLobbyUI() {
        lobbyPane = new VBox(30); // 创建一个垂直布局容器
        lobbyPane.setAlignment(Pos.TOP_CENTER); // 将整体对齐方式从“居中”改为“顶部居中”

        lobbyPane.setPadding(new Insets(100, 40, 40, 40)); // 为了不让内容完全贴在屏幕顶上，增加一个顶部内边距

        lobbyPane.setPrefSize(CANVAS_WIDTH, CANVAS_HEIGHT); // 设置首选大小
        lobbyPane.setStyle("-fx-background-color: " + toCssColor(BACKGROUND_DARK) + ";"); // 设置背景颜色

        Label title = new Label("CS2D ONLINE"); // 创建一个标题标签
        title.setFont(lobbyTitleFont); // 设置字体
        title.setTextFill(PRIMARY_BLUE); // 设置文本颜色
        title.setStyle("-fx-effect: dropshadow(gaussian, rgba(99,179,237,0.4), 8, 0, 0, 2);"); // 设置阴影效果

        connectionStatusLabel = new Label("Connecting to server..."); // 创建一个连接状态标签
        connectionStatusLabel.setFont(hudFont); // 设置字体
        connectionStatusLabel.setTextFill(Color.YELLOW); // 设置文本颜色
        connectionStatusLabel.setWrapText(true); // 设置自动换行
        connectionStatusLabel.setTextAlignment(TextAlignment.CENTER); // 设置文本居中对齐
        connectionStatusLabel.setMaxWidth(CANVAS_WIDTH * 0.8); // 设置最大宽度

        nameSelectionPane = new VBox(20); // 创建一个名字选择面板
        nameSelectionPane.setAlignment(Pos.CENTER); // 设置居中对齐
        nameSelectionPane.setMaxWidth(600); // 设置最大宽度
        nameSelectionPane.setStyle( // 设置样式
                "-fx-background-color: #2d3748; " +
                        "-fx-background-radius: 12px; " +
                        "-fx-border-radius: 12px; " +
                        "-fx-effect: dropshadow(gaussian, rgba(0,0,0,0.2), 8, 0, 0, 2);");
        nameSelectionPane.setPadding(new Insets(30)); // 设置内边距

        Label nameTitle = new Label("Choose Your Name"); // 创建一个名字标题
        nameTitle.setFont(Font.font("Orbitron", FontWeight.BOLD, 32)); // 设置字体
        nameTitle.setTextFill(Color.YELLOW); // 设置文本颜色

        TextField nameInput = new TextField("Player"); // 创建一个名字输入框
        styleTextField(nameInput); // 设置文本框样式
        nameInput.setOnAction(e -> finalizeName(nameInput.getText())); // 设置回车事件，最终确定名字

        TilePane nameButtons = new TilePane(10, 10); // 创建一个名字按钮面板
        nameButtons.setAlignment(Pos.CENTER); // 设置居中对齐
        nameButtons.setPrefColumns(2); // 设置首选列数
        String[] presetNames = { "Random Name", "LinkWheat", "LinkBeiye", "LinkBarley", "LinkBeyond", "Player",
                "Guest" }; // 预设名字数组
        for (String name : presetNames) { // 遍历预设名字并创建按钮
            Button btn = new Button(name); // 创建一个新的按钮
            styleButton(btn); // 设置按钮样式
            btn.setOnAction(e -> { // 设置点击事件
                String finalName = name; // 设置最终的名字
                if (name.equals("Random Name")) { // 如果是“随机名字”
                    String[] adjectives = { "Swift", "Silent", "Deadly", "Phantom", "Shadow", "Crimson", "Azure",
                            "Iron", "Golden" };
                    String[] nouns = { "Strike", "Blade", "Fury", "Reaper", "Ghost", "Hawk", "Wolf", "Serpent",
                            "Storm" };
                    finalName = adjectives[(int) (Math.random() * adjectives.length)]
                            + nouns[(int) (Math.random() * nouns.length)] + (int) (Math.random() * 100);
                }
                nameInput.setText(finalName); // 设置输入框的文本
                finalizeName(finalName); // 最终确定名字
            });
            nameButtons.getChildren().add(btn); // 将按钮添加到面板
        }
        nameSelectionPane.getChildren().addAll(nameTitle, nameInput, nameButtons); // 将控件添加到名字选择面板
        nameSelectionPane.setVisible(false); // 初始时隐藏

        teamSelectionPane = new VBox(15); // 创建一个队伍选择面板
        teamSelectionPane.setAlignment(Pos.CENTER); // 设置居中对齐
        teamSelectionPane.setVisible(false); // 初始时隐藏

        Button disconnectBtn = new Button("Disconnect");
        styleButton(disconnectBtn);
        disconnectBtn.setOnAction(e -> disconnect());
        disconnectBtn.setMinWidth(200);

        Region spacer = new Region(); // 创建一个看不见的区域作为“弹簧”
        VBox.setVgrow(spacer, Priority.ALWAYS); // 告诉VBox布局，这个“弹簧”应该尽可能地占据所有剩余的垂直空间
        lobbyPane.getChildren().addAll(title, connectionStatusLabel, nameSelectionPane, teamSelectionPane,
                disconnectBtn, spacer); // 将所有面板和这个“弹簧”一起添加到大厅面板中
    }

    // 创建购买菜单UI的方法
    private void createBuyMenuUI() {
        buyMenuPane = new BorderPane(); // 创建一个边框布局面板
        buyMenuPane.setStyle(panelStyle); // 设置样式
        buyMenuPane.setPadding(new Insets(30)); // 设置内边距
        buyMenuPane.setVisible(false); // 初始时隐藏

        // 只创建标题，中心内容留空，等待玩家按B键时动态填充
        Label title = new Label("Buy Menu (Press B to close)");
        title.setFont(titleFont);
        title.setTextFill(Color.YELLOW);
        title.setStyle("-fx-effect: dropshadow(gaussian, rgba(255,255,0,0.3), 6, 0, 0, 2);");
        BorderPane.setAlignment(title, Pos.CENTER);
        BorderPane.setMargin(title, new Insets(0, 0, 20, 0));
        buyMenuPane.setTop(title);
    }

    // 最终确定玩家的名字
    private void finalizeName(String name) {
        playerName = name.isBlank() ? "Player" : name.trim(); // 如果名字为空，则使用默认名"Player"
        nameSelectionPane.setVisible(false); // 隐藏名字选择面板
        teamSelectionPane.setVisible(true); // 显示队伍选择面板
        connectionStatusLabel.setVisible(false); // 在进入队伍选择时，隐藏不再需要的连接状态标签
    }

    /**
     * 方法负责打开并填充独立的初始武器选择面板
     */
    private void openInitialWeaponSelector(String team, String mode) {
        initialWeaponSelectorPane.getChildren().clear(); // 清空面板内容

        String titleText = "ZOMBIE_MODE".equals(mode) ? "Choose Starting Weapon" : "Choose Starting Weapon for " + team;
        Label title = new Label(titleText);
        title.setFont(titleFont);
        title.setTextFill(Color.YELLOW);

        // 定义选择武器后的操作
        Consumer<String> onWeaponSelectAction = weaponKey -> {
            if ("TEAM_DEATHMATCH".equals(mode) || "DEATHMATCH".equals(mode)) {
                // 发送加入队伍/外观的请求
                sendJoinRequest(team);
                // 紧接着发送选择武器的请求（这将触发重生）
                sendChooseWeaponRequest(weaponKey);

            } else if ("DEMOLITION".equals(mode)) {
                // 爆破模式只发送加入请求，由服务器处理出生
                sendJoinRequest(team);

            } else { // 僵尸模式
                // 僵尸模式将武器名作为 "selection" 发送
                sendJoinRequest(weaponKey);
            }

            initialWeaponSelectorPane.setVisible(false); // 隐藏选择面板
        };

        // 调用需要两个参数的 createWeaponGridPane
        VBox weaponGrid = createWeaponGridPane(team, onWeaponSelectAction, mode);

        ScrollPane sp = new ScrollPane(weaponGrid); // 将武器网格放入滚动面板

        sp.setStyle("-fx-background: transparent; -fx-background-color: transparent;"); // 设置透明背景
        sp.setFitToWidth(true); // 宽度自适应

        initialWeaponSelectorPane.getChildren().addAll(title, sp); // 添加标题和滚动面板

        if (!"ZOMBIE_MODE".equals(mode)) { // 如果不是僵尸模式
            Button backButton = new Button("<< Back to Team Selection");
            styleButton(backButton);
            backButton.setOnAction(e -> { // 返回队伍选择
                initialWeaponSelectorPane.setVisible(false);
                lobbyPane.setVisible(true);
                populateTeamSelection(mode);
            });
            initialWeaponSelectorPane.getChildren().add(backButton);
        }

        lobbyPane.setVisible(false); // 隐藏大厅
        initialWeaponSelectorPane.setVisible(true); // 显示武器选择面板
        initialWeaponSelectorPane.toFront(); // 置于顶层
    }

    // 填充队伍选择UI
    private void populateTeamSelection(String mode) {
        // 清空队伍选择面板的现有内容
        teamSelectionPane.getChildren().clear();
        // 将内容居中对齐
        teamSelectionPane.setAlignment(Pos.CENTER);

        // 创建标题标签
        Label title = new Label("Choose Your Faction"); // 默认标题
        title.setFont(Font.font("Orbitron", FontWeight.BOLD, 32));
        title.setTextFill(Color.YELLOW);

        // 创建一个水平盒子来容纳阵营按钮
        HBox buttonBox = new HBox(20);
        buttonBox.setAlignment(Pos.CENTER);

        // 创建“观战”按钮
        Button spectateButton = new Button("Spectate");
        styleButton(spectateButton); // 应用通用按钮样式
        spectateButton.setStyle(spectateButton.getStyle() + "-fx-background-color: #718096;"); // 设置灰色背景
        spectateButton.setOnAction(e -> {
            myPlayerId = null; // 清空自己的ID
            cameraMode = "free"; // 初始观战模式设置为 free
            setClientState(cs2d.client.GameClient.ClientState.PLAYING);
        });

        // 根据不同的游戏模式，显示不同的阵营选项
        if ("ZOMBIE_MODE".equals(mode)) {
            title.setText("Choose Your Starting Faction");

            // 幸存者按钮
            Button survivorButton = new Button("Survivors");
            styleButton(survivorButton);
            survivorButton.setStyle(survivorButton.getStyle() + "-fx-background-color: #3182ce;"); // 设置蓝色背景
            survivorButton.setPrefWidth(250);
            // 点击后，打开武器选择界面，因为幸存者需要选择初始武器
            survivorButton.setOnAction(e -> openInitialWeaponSelector("CT", mode));

            // 僵尸按钮
            Button zombieButton = new Button("Zombies");
            styleButton(zombieButton);
            zombieButton.setStyle(zombieButton.getStyle() + "-fx-background-color: #48BB78;"); // 僵尸使用绿色背景
            zombieButton.setPrefWidth(250);
            // 点击后，直接以僵尸身份加入游戏，无需选择武器
            zombieButton.setOnAction(e -> sendJoinRequest("ZOMBIE"));

            // 将按钮添加到水平盒子
            buttonBox.getChildren().addAll(survivorButton, zombieButton);
            // 将标题、按钮盒子和观战按钮添加到主面板
            teamSelectionPane.getChildren().addAll(title, buttonBox, spectateButton);

        } else { // 适用于团队死斗(TDM)和爆破(DEMO)模式
            // [新增] 也适用于死斗模式
            if ("DEATHMATCH".equals(mode)) {
                title.setText("Choose Your Appearance"); // 死斗模式，选择外观
            } else {
                title.setText("Choose Your Team");
            }

            // CT方按钮
            Button ctButton = new Button("Counter-Terrorists (CT)");
            styleButton(ctButton);
            ctButton.setStyle(ctButton.getStyle() + "-fx-background-color: #3182ce;");
            ctButton.setPrefWidth(250);
            ctButton.setOnAction(e -> {
                // [修改] 增加死斗模式
                if ("DEMOLITION".equals(mode)) {
                    sendJoinRequest("CT");
                } else if ("TEAM_DEATHMATCH".equals(mode) || "DEATHMATCH".equals(mode)) {
                    // TDM 和 死斗 模式，打开初始武器选择界面
                    openInitialWeaponSelector("CT", mode);
                }
            });
            // T方按钮
            Button tButton = new Button("Terrorists (T)");
            styleButton(tButton);
            tButton.setStyle(tButton.getStyle() + "-fx-background-color: #c53030;");
            tButton.setPrefWidth(250);
            tButton.setOnAction(e -> {
                // [修改] 增加死斗模式
                if ("DEMOLITION".equals(mode)) {
                    sendJoinRequest("T");
                } else if ("TEAM_DEATHMATCH".equals(mode) || "DEATHMATCH".equals(mode)) {
                    // TDM 和 死斗 模式，打开初始武器选择界面
                    openInitialWeaponSelector("T", mode);
                }
            });
            // 将按钮添加到水平盒子
            buttonBox.getChildren().addAll(ctButton, tButton);
            // 将标题、按钮盒子和观战按钮添加到主面板
            teamSelectionPane.getChildren().addAll(title, buttonBox, spectateButton);
        }
    }

    // 定义道具的固定切换顺序
    private static final List<String> GRENADE_CYCLE_ORDER = Arrays.asList(
            "HE_GRENADE", "FLASHBANG", "SMOKE_GRENADE", "MOLOTOV", "INCENDIARY", "DECOY");

    /**
     * 核心方法：计算并切换到下一个可用的道具
     */
    private void cycleToNextGrenade() {
        if (me == null || !getBool(me.data, "isAlive"))
            return; // 如果玩家不存在或已死亡，则返回

        JsonObject meData = me.data; // 获取我的数据
        int currentSlot = getInt(meData, "currentSlot"); // 获取当前槽位

        // 获取玩家当前拥有的所有道具
        Set<String> ownedGrenades = new HashSet<>();
        if (meData.has("equipment")) {
            meData.getAsJsonArray("equipment").forEach(e -> {
                JsonObject eq = e.getAsJsonObject();
                if (getInt(eq, "count") > 0) { // 只添加数量大于0的
                    ownedGrenades.add(getString(eq, "name"));
                }
            });
        }

        if (ownedGrenades.isEmpty())
            return; // 如果一个道具都没有，就直接返回

        // 根据玩家拥有的道具，生成一个有效的切换列表
        List<String> availableCycle = GRENADE_CYCLE_ORDER.stream()
                .filter(ownedGrenades::contains)
                .collect(Collectors.toList());

        if (availableCycle.isEmpty())
            return;

        // 决定下一个道具是什么
        String currentGrenadeName = null;
        switch (currentSlot) { // 将当前槽位号映射回道具名称
            case 7:
                currentGrenadeName = "HE_GRENADE";
                break;
            case 6:
                currentGrenadeName = "FLASHBANG";
                break;
            case 8:
                currentGrenadeName = "SMOKE_GRENADE";
                break;
            case 9:
                currentGrenadeName = "CT".equals(getString(meData, "team")) ? "INCENDIARY" : "MOLOTOV";
                break;
            case 10:
                currentGrenadeName = "DECOY";
                break;
        }

        int nextIndex = 0; // 默认切换到列表的第一个
        if (currentGrenadeName != null && availableCycle.contains(currentGrenadeName)) { // 如果当前正拿着一个道具
            int currentIndex = availableCycle.indexOf(currentGrenadeName);
            nextIndex = (currentIndex + 1) % availableCycle.size(); // 使用取模运算实现循环
        }

        String nextGrenadeName = availableCycle.get(nextIndex);
        this.predictedWeaponKey = nextGrenadeName; // 投掷物预测
        // 将道具名称映射回槽位号，并发送给服务器
        int nextSlot = -1;
        switch (nextGrenadeName) {
            case "HE_GRENADE":
                nextSlot = 7;
                break;
            case "FLASHBANG":
                nextSlot = 6;
                break;
            case "SMOKE_GRENADE":
                nextSlot = 8;
                break;
            case "MOLOTOV", "INCENDIARY":
                nextSlot = 9;
                break;
            case "DECOY":
                nextSlot = 10;
                break;
        }

        if (nextSlot != -1) {
            this.predictedSlot = nextSlot; // <-- 在这里添加预测！
            sendMessage(createSlotSwitchMessage(nextSlot));
        }

    }

    private static boolean isSubtickInputKey(KeyCode code) {
        return code == KeyCode.W || code == KeyCode.A || code == KeyCode.S || code == KeyCode.D
                || code == KeyCode.SHIFT || code == KeyCode.X || code == KeyCode.E;
    }

    // 为场景设置输入监听器
    private void setupInputListeners(Scene scene) {
        scene.addEventFilter(KeyEvent.KEY_PRESSED, event -> { // 添加键盘按下事件过滤器
            boolean firstPress = keysDown.add(event.getCode()); // 自动重复按键不会重复触发一次性动作
            if (clientState != cs2d.client.GameClient.ClientState.PLAYING
                    && clientState != cs2d.client.GameClient.ClientState.LOBBY
                    && clientState != cs2d.client.GameClient.ClientState.GAME_OVER)
                return;

            if (event.getCode() == KeyCode.SHIFT || event.getCode() == KeyCode.X)
                isWalking = true; // 脚步声

            switch (event.getCode()) {
                // 处理切换武器和道具的逻辑
                case DIGIT1 -> {
                    sendMessage(createSlotSwitchMessage(1));
                    predictedWeaponKey = null; // 切换到主武器时，清除投掷物预测

                    predictedSlot = 1;
                }
                case DIGIT2 -> {
                    sendMessage(createSlotSwitchMessage(2));
                    predictedWeaponKey = null;

                    predictedSlot = 2;
                }
                case DIGIT3 -> {
                    sendMessage(createSlotSwitchMessage(3));
                    predictedWeaponKey = null;
                    predictedSlot = 3;
                }

                case DIGIT4 -> cycleToNextGrenade(); // 切换到下一个道具

                case SPACE -> {
                    event.consume();
                    cycleSpectatorTarget();
                } // 空格键切换观战

                default -> {
                }
            }

            String mode = (latestGameState != null) ? getString(latestGameState, "mode") : "";

            switch (event.getCode()) { // 其他按键逻辑
                case TAB:
                    if (clientState == cs2d.client.GameClient.ClientState.PLAYING) {
                        event.consume(); // 消耗事件，防止默认行为
                        if (tabPressTime == 0) {
                            tabPressTime = System.currentTimeMillis();
                            tabIsLongPress = false;
                            boolean isVis = scoreboardPane.isVisible();
                            scoreboardPane.setVisible(!isVis);
                            if (!isVis) {
                                updateScoreboardUI();
                                scoreboardPane.toFront();
                            }
                        } else {
                            if (System.currentTimeMillis() - tabPressTime > 300) {
                                tabIsLongPress = true;
                                if (!scoreboardPane.isVisible()) {
                                    scoreboardPane.setVisible(true);
                                    updateScoreboardUI();
                                    scoreboardPane.toFront();
                                }
                            }
                        }
                    }
                    break;
                case ESCAPE:
                    event.consume();
                    boolean isSettingsVisible = !settingsPane.isVisible();
                    settingsPane.setVisible(isSettingsVisible); // 切换设置面板可见性
                    if (isSettingsVisible)
                        settingsPane.toFront();
                    else if (clientState == cs2d.client.GameClient.ClientState.PLAYING)
                        gameContainer.toFront();
                    break;
                case R:
                    if (clientState == cs2d.client.GameClient.ClientState.PLAYING && latestGameState != null)
                        sendMessage(createJsonMessage("requestReload")); // 发送换弹请求
                    break;
                case G:
                    if (clientState == cs2d.client.GameClient.ClientState.PLAYING && latestGameState != null)
                        if (shouldSendDropWeapon(mode, firstPress))
                            sendMessage(createJsonMessage("dropWeapon")); // 发送丢弃武器消息
                    break;
                case B:
                    if (clientState == cs2d.client.GameClient.ClientState.PLAYING && latestGameState != null) {
                        String roundPhase = getString(latestGameState, "roundPhase");

                        // --- DEMOLITION 逻辑：只在 FREEZE_TIME + 10s 打开购买菜单 ---
                        if ("DEMOLITION".equals(mode)) {
                            if ("FREEZE_TIME".equals(roundPhase) || buyMenuPane.isVisible()) {
                                if (!buyMenuPane.isVisible() && buyMenuPane.getCenter() == null) {
                                    Node buyMenuContent = buildBuyMenuContent();
                                    buyMenuPane.setCenter(buyMenuContent);
                                }
                                boolean isBuyMenuVisible = !buyMenuPane.isVisible();
                                buyMenuPane.setVisible(isBuyMenuVisible);
                                if (isBuyMenuVisible) {
                                    buyMenuPane.toFront();
                                    updateBuyMenuUI();
                                }
                            }
                            // 将 TEAM_DEATHMATCH 和 DEATHMATCH 合并到同一个 else if 块中
                        } else if ("TEAM_DEATHMATCH".equals(mode) || "DEATHMATCH".equals(mode)) {
                            boolean isTdmSelectorVisible = !tdmWeaponSelectorPane.isVisible();
                            tdmWeaponSelectorPane.setVisible(isTdmSelectorVisible);
                            if (isTdmSelectorVisible) {
                                tdmWeaponSelectorPane.toFront();
                                ((Label) tdmWeaponSelectorPane.lookup("#weapon-selector-title"))
                                        .setText("Select Weapon for Next Life");
                                String myTeam = (me != null && me.data != null) ? getString(me.data, "team") : "CT";
                                // 使用正确的动作回调：发送选择下一生命武器的请求
                                populateTdmWeaponSelector(myTeam, this::sendSelectNextWeaponRequest);
                            }
                        }
                    }
                    break;
                case E:
                    if (clientState == cs2d.client.GameClient.ClientState.PLAYING && latestGameState != null) {
                        // --- 交互/夺舍逻辑 ---
                        boolean isPlayerAlive = me != null && getBool(me.data, "isAlive");

                        if (isPlayerAlive) {
                            // 活着时，E键用于DEMO模式的安包/拆弹交互
                            if ("DEMOLITION".equals(mode) && !isInteracting) {
                                isInteracting = true;
                                System.out.println("E-key");
                                sendMessage(createJsonMessage("startInteraction"));
                            }
                        } else {
                            // --- 死亡/观战时，发送带目标ID的夺舍请求 ---

                            // 检查我是否正在控制一个BOT
                            boolean isControlling = me != null
                                    && "CONTROLLING_BOT".equals(getString(me.data, "spectatorMode"));

                            if (isControlling) {
                                // 如果正在控制，发送一个不带 targetId 的请求，服务器会将其识别为“释放”
                                sendMessage(createControlBotRequest(null));
                            } else {
                                // 如果不在控制，获取当前观战目标的ID
                                String targetId = (me != null && me.data != null)
                                        ? getString(me.data, "spectatorTargetId")
                                        : null;
                                // TDM 通常没有固定 spectatorTargetId；空目标必须省略，让服务端自动寻找同队 BOT。
                                sendMessage(createControlBotRequest(targetId));
                            }
                        }
                    }
                    break;
                default:
                    break;
            }
            if (firstPress && isSubtickInputKey(event.getCode()))
                sendSubtickInputImmediately();
        });

        scene.addEventFilter(KeyEvent.KEY_RELEASED, event -> { // 键盘释放事件
            keysDown.remove(event.getCode()); // 从集合中移除
            if (clientState != cs2d.client.GameClient.ClientState.PLAYING)
                return;

            if (event.getCode() == KeyCode.SHIFT || event.getCode() == KeyCode.X)
                isWalking = false;

            if (event.getCode() == KeyCode.TAB) {
                long pressDuration = System.currentTimeMillis() - tabPressTime;
                if (tabIsLongPress || pressDuration > 300) {
                    scoreboardPane.setVisible(false); // 隐藏记分板
                }
                tabPressTime = 0;
                tabIsLongPress = false;
            }

            if (event.getCode() == KeyCode.E && isInteracting) { // 如果释放E键
                isInteracting = false; // 停止交互
                sendMessage(createJsonMessage("stopInteraction"));
            }

            if (event.getCode() == KeyCode.E) {
                if (me != null && getBool(me.data, "isAlive")) {
                    // 活着时，释放E键停止安包/拆弹
                    if (isInteracting) {
                        isInteracting = false;
                        sendMessage(createJsonMessage("stopInteraction"));
                    }
                }
                // 死亡时，释放E键不做任何事，释放/夺舍逻辑在 KEY_PRESSED 中处理
            }

            if (isSubtickInputKey(event.getCode()))
                sendSubtickInputImmediately();

        });

        canvas.setOnMousePressed(event -> { // 鼠标按下事件
            if (event.getButton() == MouseButton.PRIMARY)
                isShooting = true; // 左键开火

            if (event.getButton() == MouseButton.SECONDARY) { // 右键逻辑
                if (me != null && me.data != null) {
                    int currentSlot = getInt(me.data, "currentSlot");
                    if (currentSlot >= 1 && currentSlot <= 3) { // 如果是主武器/副武器/刀
                        Point2D mouseWorld = camera.screenToWorld(mouseX, mouseY);
                        JsonObject message = new JsonObject();
                        message.addProperty("type", "requestPing");
                        addProtocolMetadata(message);
                        JsonObject positionJson = new JsonObject();
                        positionJson.addProperty("x", mouseWorld.getX());
                        positionJson.addProperty("y", mouseWorld.getY());
                        message.add("position", positionJson);
                        sendMessage(gson.toJson(message)); // 发送标点请求
                    } else { // 如果是投掷物
                        isUnderhandThrowing = true; // 低抛
                    }
                }
            }
            if (event.getButton() == MouseButton.PRIMARY || event.getButton() == MouseButton.SECONDARY)
                sendSubtickInputImmediately();
        });
        canvas.setOnMouseReleased(event -> { // 鼠标释放事件
            if (event.getButton() == MouseButton.PRIMARY)
                isShooting = false; // 停止开火
            if (event.getButton() == MouseButton.SECONDARY)
                isUnderhandThrowing = false; // 停止低抛
            if (event.getButton() == MouseButton.PRIMARY || event.getButton() == MouseButton.SECONDARY)
                sendSubtickInputImmediately();
        });

        canvas.setOnMouseMoved(event -> {
            mouseX = event.getX();
            mouseY = event.getY();
        }); // 更新鼠标位置
        canvas.setOnMouseDragged(event -> {
            mouseX = event.getX();
            mouseY = event.getY();
        }); // 更新鼠标位置

        // [新增] 鼠标滚轮直接缩放功能
        canvas.setOnScroll(event -> {
            if (gameSettings.isMouseWheelZoomEnabled()) {
                double deltaY = event.getDeltaY();
                double currentFactor = gameSettings.getFollowZoomFactor();

                // 向上滚动 (deltaY > 0) 通常表示放大，向下滚动表示缩小
                // 但在我们的 Camera 类中，zoomFactor 越大，视野越开阔（缩小）
                // 所以：向上滚动 -> 减小 factor (放大)；向下滚动 -> 增大 factor (缩小)
                double change = 0.05; // 每次缩放的步长
                if (deltaY > 0) {
                    gameSettings.setFollowZoomFactor(currentFactor - change);
                } else if (deltaY < 0) {
                    gameSettings.setFollowZoomFactor(currentFactor + change);
                }

                // 更新设置中的缩放系数会自动反映到画面上
                // 同时，如果有设置界面正在显示，滑块也需要同步（这里我们不需要显式同步，因为是 Property 绑定）
                event.consume(); // 消耗事件，防止影响其他可能的滚动组件
            }
        });
    }

    // --- 辅助与工具方法 ---
    // 发送加入游戏的请求
    private void sendJoinRequest(String selection) {
        sendMessage(createJsonMessage("joinGame", "name", playerName, "selection", selection));
        setClientState(cs2d.client.GameClient.ClientState.PLAYING); // 设置客户端状态为PLAYING
    }

    // 创建一个类别网格
    private VBox createCategoryGrid(String title, List<String> itemKeys) {
        return createCategoryGrid(title, itemKeys, 0);
    }

    // 创建一个类别网格（带列数）
    private VBox createCategoryGrid(String title, List<String> itemKeys, int columns) {
        VBox pane = new VBox(8); // 垂直布局
        Label titleLabel = new Label(title); // 标题
        titleLabel.setFont(Font.font("Orbitron", FontWeight.BOLD, 22));
        titleLabel.setTextFill(Color.CYAN);
        pane.getChildren().add(titleLabel);

        TilePane grid = new TilePane(8, 8); // 网格布局
        if (columns > 0)
            grid.setPrefColumns(columns);

        String myTeam = (me != null && me.data != null) ? getString(me.data, "team") : "CT";

        Map<String, Object> allItems = new HashMap<>(); // 存储所有物品
        WEAPON_UI_DATA.forEach(allItems::put);
        ITEM_UI_DATA.forEach(allItems::put);

        for (String key : itemKeys) { // 遍历物品
            Object itemData = allItems.get(key);
            if (itemData == null)
                continue;

            String name;
            int cost;
            int maxQuantity = 1;
            boolean isVisibleForMyTeam = true;
            // 根据物品类型和阵营决定是否显示
            if (itemData instanceof cs2d.client.GameClient.WeaponUIData w) {
                name = w.name();
                cost = w.cost();
                if (!"ANY".equals(w.faction()) && !w.faction().equals(myTeam))
                    isVisibleForMyTeam = false;
            } else if (itemData instanceof cs2d.client.GameClient.ItemUIData i) {
                name = i.name();
                cost = i.cost();
                maxQuantity = i.maxQuantity();
                if (i.team() != null && !i.team().equals(myTeam))
                    isVisibleForMyTeam = false;
            } else {
                continue;
            }
            if (!isVisibleForMyTeam)
                continue;

            // 创建UI元素
            StackPane stack = new StackPane();
            stack.setPrefWidth(220);

            Button buyBtn = new Button(name + "\n$ " + cost);
            buyBtn.setTextAlignment(TextAlignment.LEFT);
            styleButton(buyBtn);
            buyBtn.setMaxWidth(Double.MAX_VALUE);
            buyBtn.setOnAction(e -> { // 购买事件
                if (WEAPON_UI_DATA.containsKey(key))
                    sendMessage(createJsonMessage("chooseWeapon", "weapon", key));
                else
                    sendMessage(createJsonMessage("buyItem", "item", key));
            });

            Node iconNode = cs2d.client.GameClient.WeaponIcon.getIcon(key, false); // 获取图标
            if (iconNode instanceof SVGPath svgPath) {
                Color iconColor = "CT".equals(myTeam) ? PRIMARY_BLUE : Color.web("#DE9B35");
                svgPath.setFill(iconColor);
                svgPath.setScaleX(1.2);
                svgPath.setScaleY(1.2);
                buyBtn.setGraphic(svgPath);
                buyBtn.setContentDisplay(ContentDisplay.LEFT);
                buyBtn.setGraphicTextGap(15);
            }
            buyMenuButtons.put(key, buyBtn);

            Button undoBtn = new Button("Undo: " + name + "\n+$ " + cost); // 撤销按钮
            undoBtn.setTextAlignment(TextAlignment.LEFT);
            styleButton(undoBtn);
            undoBtn.setStyle(undoBtn.getStyle() + "-fx-text-fill: #F6E05E;");
            undoBtn.setVisible(false);
            undoBtn.setOnAction(e -> sendMessage(createJsonMessage("undoPurchase", "item", key)));
            undoMenuButtons.put(key, undoBtn);

            if (maxQuantity > 1) { // 如果可购买多个
                Button undoAllBtn = new Button("<< Undo All");
                styleButton(undoAllBtn);
                undoAllBtn.setFont(tinyHudFont);
                undoAllBtn.setStyle(undoAllBtn.getStyle() + "-fx-text-fill: #F6E05E; -fx-padding: 2px 6px;");
                undoAllBtn.setVisible(false);
                undoAllBtn.setOnAction(e -> {
                    // ... (撤销所有)
                });
                undoMenuButtons.put(key + "_ALL", undoAllBtn);
                StackPane.setAlignment(undoAllBtn, Pos.BOTTOM_LEFT);
                stack.getChildren().add(undoAllBtn);
            }

            stack.getChildren().addAll(buyBtn, undoBtn);
            grid.getChildren().add(stack);
        }
        pane.getChildren().add(grid);
        return pane;
    }

    // 构建购买菜单内容 (爆破模式)
    private Node buildBuyMenuContent() {
        VBox content = new VBox(20);
        content.setPadding(new Insets(20));

        // 地上武器部分
        VBox groundSection = new VBox(10);
        Label groundTitle = new Label("Weapons on Ground (Click to pick up)");
        groundTitle.setFont(Font.font("Orbitron", FontWeight.BOLD, 20));
        groundTitle.setTextFill(Color.YELLOW);
        groundWeaponsContainer = new TilePane(10, 10);
        groundWeaponsContainer.setPrefColumns(2);
        groundWeaponsContainer.setStyle(
                "-fx-background-color: #1a202c; -fx-padding: 12px; -fx-border-radius: 8px; -fx-background-radius: 8px;");
        groundSection.getChildren().addAll(groundTitle, groundWeaponsContainer);
        content.getChildren().add(groundSection);

        // 武器类别
        VBox pistols = createCategoryGrid("Pistols", WEAPONS_BY_CATEGORY.get("Pistols"));
        VBox smgs = createCategoryGrid("SMGs", WEAPONS_BY_CATEGORY.get("SMGs"));
        VBox rifles = createCategoryGrid("Rifles", WEAPONS_BY_CATEGORY.get("Rifles"));
        VBox heavies = createCategoryGrid("Other", Stream.of(WEAPONS_BY_CATEGORY.get("Shotguns"),
                WEAPONS_BY_CATEGORY.get("Snipers"),
                WEAPONS_BY_CATEGORY.get("Machine_Guns")).flatMap(Collection::stream).collect(Collectors.toList()));
        content.getChildren().addAll(pistols, smgs, rifles, heavies);

        // 装备和手雷
        HBox bottomRow = new HBox(30);
        bottomRow.setAlignment(Pos.TOP_LEFT);
        bottomRow.getChildren().addAll(
                createCategoryGrid("Gear", Arrays.asList("KEVLAR", "KEVLAR_HELMET"), 1),
                createCategoryGrid("Grenades", ITEM_UI_DATA.keySet().stream()
                        .filter(k -> k.contains("GRENADE") || k.contains("FLASHBANG") || k.contains("MOLOTOV")
                                || k.contains("INCENDIARY") || k.contains("DECOY"))
                        .collect(Collectors.toList()), 2),
                createCategoryGrid("Equipment", Collections.singletonList("DEFUSE_KIT"), 1));
        content.getChildren().add(bottomRow);

        ScrollPane scrollPane = new ScrollPane(content); // 支持滚动
        scrollPane.setFitToWidth(true);
        scrollPane.setStyle("-fx-background: transparent; -fx-background-color: transparent;");

        return scrollPane;
    }

    // 创建一个武器网格面板 (通用)
    private VBox createWeaponGridPane(String teamForDisplay, Consumer<String> onWeaponSelect, String mode) {
        VBox container = new VBox(15);
        container.setPadding(new Insets(10));
        container.setAlignment(Pos.CENTER);

        String myTeam = (teamForDisplay != null) ? teamForDisplay
                : ((me != null && me.data != null) ? getString(me.data, "team") : "CT");
        Color iconColor = "CT".equals(myTeam) ? PRIMARY_BLUE : Color.web("#DE9B35");

        // [2025-12-01] 检查是否锁派系
        // [修改] 团队死斗 (TEAM_DEATHMATCH) 也不锁派系
        boolean isFactionLocked = !"DEATHMATCH".equals(mode) && !"TEAM_DEATHMATCH".equals(mode);

        WEAPONS_BY_CATEGORY.forEach((category, weapons) -> { // 遍历每个武器类别
            List<String> buyableWeaponsInThisCategory = new ArrayList<>(); // 存储可购买的武器
            for (String weaponKey : weapons) {
                cs2d.client.GameClient.WeaponUIData data = WEAPON_UI_DATA.get(weaponKey);
                // [修改] 使用 isFactionLocked 变量
                if (data != null
                        && (!isFactionLocked || "ANY".equals(data.faction()) || data.faction().equals(myTeam))) {
                    buyableWeaponsInThisCategory.add(weaponKey);
                }
            }
            if (buyableWeaponsInThisCategory.isEmpty())
                return; // 如果没有可买的，跳过

            Label categoryLabel = new Label(category.replace("_", " ")); // 类别标题
            categoryLabel.setFont(hudFont);
            categoryLabel.setTextFill(Color.CYAN);
            container.getChildren().add(categoryLabel);

            TilePane grid = new TilePane(10, 10); // 网格
            grid.setAlignment(Pos.CENTER);

            buyableWeaponsInThisCategory.forEach(weaponKey -> { // 创建每个武器的按钮
                cs2d.client.GameClient.WeaponUIData data = WEAPON_UI_DATA.get(weaponKey);
                if (data != null) {
                    Button btn = new Button();
                    Node weaponIcon = cs2d.client.GameClient.WeaponIcon.getIcon(weaponKey, false);
                    if (weaponIcon instanceof SVGPath svgPath) {
                        svgPath.setFill(iconColor);
                        svgPath.setScaleX(1.2);
                        svgPath.setScaleY(1.2);
                        btn.setGraphic(weaponIcon);
                        btn.setContentDisplay(ContentDisplay.LEFT);
                        btn.setGraphicTextGap(10);
                    }
                    btn.setText(data.name());
                    styleButton(btn);
                    btn.setOnAction(e -> onWeaponSelect.accept(weaponKey));
                    grid.getChildren().add(btn);
                }
            });
            container.getChildren().add(grid);
        });
        return container;
    }

    // 创建TDM武器选择器UI
    private void createTdmWeaponSelectorUI() {
        tdmWeaponSelectorPane = new VBox(20);
        tdmWeaponSelectorPane.setAlignment(Pos.CENTER);
        tdmWeaponSelectorPane.setStyle(panelStyle);
        tdmWeaponSelectorPane.setPadding(new Insets(40));
        tdmWeaponSelectorPane.setVisible(false);
        Label title = new Label("Select Weapon");
        title.setFont(titleFont);
        title.setTextFill(Color.YELLOW);
        title.setId("weapon-selector-title");
        ScrollPane scrollPane = new ScrollPane();
        scrollPane.setFitToWidth(true);
        scrollPane.setStyle("-fx-background: transparent; -fx-background-color: transparent;");
        scrollPane.setMaxHeight(CANVAS_HEIGHT * 0.7);
        tdmWeaponSelectorPane.getChildren().addAll(title, scrollPane);
    }

    // 创建记分板UI
    private void createScoreboardUI() {
        scoreboardPane = new StackPane();
        scoreboardPane.setStyle(panelStyle);
        scoreboardPane.setVisible(false);
        scoreboardContent = new VBox(20);
        scoreboardContent.setPadding(new Insets(40));
        scoreboardContent.setAlignment(Pos.TOP_CENTER);
        scoreboardContent.setMaxWidth(CANVAS_WIDTH * 0.9);
        scoreboardScrollPane = new ScrollPane(scoreboardContent);
        scoreboardScrollPane.setFitToWidth(true);
        scoreboardScrollPane.setHbarPolicy(ScrollPane.ScrollBarPolicy.NEVER);
        scoreboardScrollPane.setStyle("-fx-background: transparent; -fx-background-color: transparent;");
        scoreboardPane.getChildren().add(scoreboardScrollPane);
    }

    static boolean shouldSendDropWeapon(String mode, boolean firstPress) {
        return firstPress && ("DEMOLITION".equals(mode) || "TEAM_DEATHMATCH".equals(mode));
    }

    // 打开初始武器选择界面
    private void openInitialWeaponSelection() {
        ((Label) tdmWeaponSelectorPane.lookup("#weapon-selector-title")).setText("Select Your Starting Weapon");
        populateTdmWeaponSelector(this::sendChooseWeaponRequest);
        tdmWeaponSelectorPane.setVisible(true);
    }

    // 打开初始武器选择界面 (带队伍参数)
    private void openInitialWeaponSelection(String team) {
        // 管理主容器的可见性，进行“场景切换”
        lobbyPane.setVisible(false); // 隐藏大厅界面
        gameContainer.setVisible(true); // 显示游戏主容器

        // 准备并填充武器选择界面
        ((Label) tdmWeaponSelectorPane.lookup("#weapon-selector-title"))
                .setText("Select Your Starting Weapon for " + team);

        Consumer<String> onWeaponSelectAction = weaponKey -> {
            // 当玩家选择武器后，发送加入游戏和选择武器的请求
            sendJoinRequest(team);
            sendChooseWeaponRequest(weaponKey);
        };

        populateTdmWeaponSelector(team, onWeaponSelectAction);

        // 确保游戏容器内只有选枪界面是可见的 ---
        // 注：这一步是为了防止在选枪时，背后出现其他游戏UI元素
        buyMenuPane.setVisible(false);
        scoreboardPane.setVisible(false);

        // 显示选枪界面并把它置于最顶层
        tdmWeaponSelectorPane.setVisible(true);
        tdmWeaponSelectorPane.toFront();
    }

    // 填充TDM武器选择器 (带队伍和动作参数)
    private void populateTdmWeaponSelector(String team, Consumer<String> action) {
        // [新增] 从 latestGameState 获取模式
        String mode = (latestGameState != null) ? getString(latestGameState, "mode") : "TEAM_DEATHMATCH";
        // [修改] 传递 mode
        VBox grid = createWeaponGridPane(team, weaponKey -> {
            action.accept(weaponKey);
            tdmWeaponSelectorPane.setVisible(false);
        }, mode); // <-- 传递 mode
        ((ScrollPane) tdmWeaponSelectorPane.getChildren().get(1)).setContent(grid);
    }

    private void populateTdmWeaponSelector(Consumer<String> action) {
        String currentTeam = (me != null && me.data != null) ? getString(me.data, "team") : "CT";
        // 从 latestGameState 获取模式
        String mode = (latestGameState != null) ? getString(latestGameState, "mode") : "TEAM_DEATHMATCH";

        VBox grid = createWeaponGridPane(currentTeam, weaponKey -> {
            action.accept(weaponKey);
            tdmWeaponSelectorPane.setVisible(false);
        }, mode); // <-- 传递 mode
        ((ScrollPane) tdmWeaponSelectorPane.getChildren().get(1)).setContent(grid);
    }

    // 发送选择武器的请求
    private void sendChooseWeaponRequest(String weaponKey) {
        sendMessage(createJsonMessage("chooseWeapon", "weapon", weaponKey));
    }

    // 发送选择下一生命武器的请求
    private void sendSelectNextWeaponRequest(String weaponKey) {
        sendMessage(createJsonMessage("selectNextWeapon", "weapon", weaponKey));
    }

    // 创建一个JSON消息字符串
    private String createJsonMessage(String type, String... keyVals) {
        JsonObject obj = new JsonObject();
        obj.addProperty("type", type);
        addProtocolMetadata(obj);
        for (int i = 0; i < keyVals.length; i += 2)
            obj.addProperty(keyVals[i], keyVals[i + 1]);
        return gson.toJson(obj);
    }

    private String createSlotSwitchMessage(int slot) {
        JsonObject obj = new JsonObject();
        obj.addProperty("type", "switchToSlot");
        addProtocolMetadata(obj);
        obj.addProperty("slot", slot);
        return gson.toJson(obj);
    }

    private String createControlBotRequest(String targetId) {
        JsonObject obj = new JsonObject();
        obj.addProperty("type", "requestControlBot");
        addProtocolMetadata(obj);
        if (targetId != null && !targetId.isBlank())
            obj.addProperty("targetId", targetId);
        return gson.toJson(obj);
    }

    private void addProtocolMetadata(JsonObject obj) {
        obj.addProperty("protocolVersion", SUPPORTED_PROTOCOL_VERSION);
        if (serverSessionId != null)
            obj.addProperty("sessionId", serverSessionId);
    }

    /**
     * [已修复] 从JSON对象中安全地获取double值。
     * 只 get() 一次，并处理 null 和 JsonNull。
     */
    private static double getDouble(JsonObject obj, String key) {
        if (obj == null || !obj.has(key)) {
            return 0.0;
        }
        JsonElement el = obj.get(key); // Get element ONCE
        if (el == null || el.isJsonNull()) {
            return 0.0;
        }
        try {
            return el.getAsDouble(); // 使用 try-catch 防止 "abc" 这样的值
        } catch (java.lang.NumberFormatException e) {
            System.err.println("GSON Format Error: Key '" + key + "' was not a double.");
            return 0.0;
        }
    }

    /**
     * [已修复] 从JSON对象中安全地获取int值。
     */
    private static int getInt(JsonObject obj, String key) {
        if (obj == null || !obj.has(key)) {
            return 0;
        }
        JsonElement el = obj.get(key); // Get element ONCE
        if (el == null || el.isJsonNull()) {
            return 0;
        }
        try {
            return el.getAsInt();
        } catch (java.lang.NumberFormatException e) {
            System.err.println("GSON Format Error: Key '" + key + "' was not an int.");
            return 0;
        }
    }

    /**
     * [已修复] 从JSON对象中安全地获取String值。
     * (这个方法导致了您的崩溃)
     */
    private static String getString(JsonObject obj, String key) {
        if (obj == null || !obj.has(key)) {
            return "";
        }
        JsonElement el = obj.get(key); // Get element ONCE
        if (el == null || el.isJsonNull()) {
            return "";
        }
        return el.getAsString();
    }

    /**
     * [已修复] 从JSON对象中安全地获取boolean值。
     * (您原来的版本缺少 isJsonNull() 检查，非常危险)
     */
    private static boolean getBool(JsonObject obj, String key) {
        if (obj == null || !obj.has(key)) {
            return false;
        }
        JsonElement el = obj.get(key); // Get element ONCE
        if (el == null || el.isJsonNull()) {
            return false;
        }
        try {
            return el.getAsBoolean();
        } catch (java.lang.UnsupportedOperationException e) {
            System.err.println("GSON Format Error: Key '" + key + "' was not a boolean.");
            return false;
        }
    }

    /**
     * 从JSON对象中安全地获取long值。
     */
    private static long getLong(JsonObject obj, String key) {
        if (obj == null || !obj.has(key)) {
            return 0L;
        }
        JsonElement el = obj.get(key); // Get element ONCE
        if (el == null || el.isJsonNull()) {
            return 0L;
        }
        try {
            return el.getAsLong();
        } catch (java.lang.NumberFormatException e) {
            System.err.println("GSON Format Error: Key '" + key + "' was not a long.");
            return 0L;
        }
    }

    // 从JSON对象中安全地获取字符串数组
    private static List<String> getJsonStringArray(JsonObject obj, String key) {
        List<String> list = new ArrayList<>();
        if (obj != null && obj.has(key) && obj.get(key).isJsonArray())
            obj.getAsJsonArray(key).forEach(e -> list.add(e.getAsString()));
        return list;
    }

    /** 识别同一服务端会话内重复到达的静态地图包。 */
    static String createMapSignature(JsonObject mapJson) {
        if (mapJson == null)
            return "<null-map>";
        JsonArray obstacles = mapJson.has("obstacles") && mapJson.get("obstacles").isJsonArray()
                ? mapJson.getAsJsonArray("obstacles") : new JsonArray();
        return getString(mapJson, "sessionId") + ':'
                + Double.doubleToLongBits(getDouble(mapJson, "width")) + ':'
                + Double.doubleToLongBits(getDouble(mapJson, "height")) + ':'
                + obstacles.size() + ':' + obstacles.hashCode();
    }

    // 判断一个圆是否在多边形内可见。
    private boolean isCircleVisibleInPolygon(Point2D circleCenter, double radius, List<Point2D> polygon) {
        // 如果多边形为空或玩家ID为空，则认为可见。
        if (polygon == null || polygon.isEmpty() || myPlayerId == null)
            return true;
        // 如果圆心在多边形内，则可见。
        if (isPointInPolygon(circleCenter, polygon))
            return true;
        // 计算半径的平方。
        double radiusSq = radius * radius;
        // 遍历多边形的每条边。
        for (int i = 0, j = polygon.size() - 1; i < polygon.size(); j = i++) {
            // 如果圆心到边的距离的平方小于半径的平方，则可见。
            if (distToSegmentSquared(circleCenter, polygon.get(j), polygon.get(i)) < radiusSq)
                return true;
        }
        // 否则不可见。
        return false;
    }

    // 用于管理循环播放的声音，键是发出声音的物体的ID (比如火焰ID)，值是声音片段
    private final Map<String, AudioClip> loopingSounds = new ConcurrentHashMap<>();

    // C4
    private long lastC4BeepTime = 0; // 记录上次播放嘀嘀声的时间
    private boolean wasBombPlantedLastFrame = false; // 记录上一帧炸弹是否已安放
    // 预加载声音文件。

    private void preloadSounds() {
        System.out.println("--- [音频系统] 开始预加载所有音效 ---");
        boolean pcmReady = pcmAudioMixer.start();

        List<String> soundKeys = new ArrayList<>(Arrays.asList(
                "headshot", "kill", "buy", "plant", "defuse",
                "flashbang_explode1", "flashbang_explode2", "flashbang_hit1", "explosion_ring",
                "hegrenade_detonate_02", "hegrenade_detonate_03", "hegrenade_bounce",
                "molotov_detonate_1", "molotov_bounce1", "molotov_bounce2", "incendiary_bounce",
                "fire_loop_1", "molotov_extinguish", "fire_loop_fadeout_01",
                "smokegrenade_hit1", "smoke_emit",
                "ALL_DEMOLITION/c4/bombdef", "ALL_DEMOLITION/c4/bombpl",
                "ALL_DEMOLITION/c4/ctwin", "ALL_DEMOLITION/c4/terwin",
                "weapons/c4/c4_initiate", "weapons/c4/c4_plant",
                "weapons/c4/c4_disarmstart", "weapons/c4/c4_disarmfinish",
                "weapons/c4/c4_beep2", "weapons/c4/c4_beep3",
                "weapons/c4/c4_beep2_10sec", "weapons/c4/c4_beep3_10sec",
                "weapons/c4/c4_explode1", "weapons/c4/c4_exp_deb1", "weapons/c4/c4_exp_deb2"));

        for (int i = 1; i <= 7; i++) {
            soundKeys.add("weapons/c4/key_press" + i);
        }

        for (int i = 1; i <= 7; i++) {
            soundKeys.add("hit" + i);
        }

        WEAPON_UI_DATA.keySet().forEach(w -> {
            soundKeys.add(w + "_fire");
            soundKeys.add(w + "_reload");
        });

        // 回合开始/结束旁白 (现在带ct_和t_前缀)
        List<String> roundVoiceBases = new ArrayList<>();
        for (int i = 1; i <= 10; i++)
            roundVoiceBases.add("radiobotstart" + String.format("%02d", i));
        for (int i = 1; i <= 11; i++)
            roundVoiceBases.add("radiobotendclean" + String.format("%02d", i));
        for (int i = 1; i <= 8; i++)
            roundVoiceBases.add("radiobotendsolid" + String.format("%02d", i));

        for (String baseName : roundVoiceBases) {
            soundKeys.add("ct_" + baseName);
            soundKeys.add("t_" + baseName);
        }

        // 投掷手雷语音 (CT 和 T)
        List<String> grenadeVocalizations = Arrays.asList(
                "decoy01", "decoy02", "decoy03",
                "flashbang01", "flashbang02", "flashbang03", "flashbang04",
                "grenade01", "grenade02", "grenade04", "grenade05", "grenade06",
                "molotov01", "molotov02", "molotov03", "molotov04", "molotov08", "molotov11",
                "smoke01", "smoke02", "smoke03", "smoke04", "smoke05");
        for (String vocalName : grenadeVocalizations) {
            soundKeys.add("ct_" + vocalName);
            soundKeys.add("t_" + vocalName);
        }

        for (String key : soundKeys) {
            if (sounds.containsKey(key))
                continue;

            String basePath;
            if (key.startsWith("ct_")) {
                basePath = "/sounds/ct/" + key.substring(3);
            } else if (key.startsWith("t_")) {
                basePath = "/sounds/t/" + key.substring(2);
            } else {
                basePath = "/sounds/" + key;
            }

            System.out.println("[音频加载器] 正在尝试加载 Key: '" + key + "' | 路径: " + basePath + "[.wav/.mp3]");

            URL resource = getClass().getResource(basePath + ".wav");
            if (resource == null) {
                resource = getClass().getResource(basePath + ".mp3");
            }

            if (resource != null) {
                try {
                    sounds.put(key, new AudioClip(resource.toExternalForm()));
                    boolean mp3 = resource.getPath().toLowerCase(Locale.ROOT).endsWith(".mp3");
                    if (mp3) {
                        mediaBackedSoundKeys.add(key);
                    } else if (pcmReady) {
                        try {
                            pcmSounds.put(key, pcmAudioMixer.load(resource));
                        } catch (IOException | javax.sound.sampled.UnsupportedAudioFileException pcmError) {
                            System.err.println("    -> PCM回退JavaFX: " + key + " | " + pcmError.getMessage());
                        }
                    }
                    System.out.println("    -> 成功加载: " + resource.getPath());
                } catch (Exception e) {
                    System.err.println("    -> !!! 加载失败: " + key + " | 错误: " + e.getMessage());
                }
            } else {
                System.err.println("    -> !!! 文件未找到: " + key);
            }
        }
        System.out.println("--- [音频系统] " + sounds.size() + " 个音效预加载完成, PCM="
                + pcmSounds.size() + " ---");
    }

    /**
     * 处理所有“一次性”音效的主方法，音量逻辑更清晰
     *
     * @param soundKey 在 sounds Map 中定义的声音键名
     * @param soundPos 声音在世界中的位置，如果为 null 则认为是2D私有音效
     */
    private void playSound(String soundKey, Point2D soundPos) {

        // 声音 打印

        // System.out.println("[客户端调试] playSound方法被调用, soundKey = '" + soundKey + "'");
        AudioClip clip = sounds.get(soundKey);
        if (clip == null) {
            // 如果声音未找到，打印一个非常明显的错误信息
            // System.err.println("!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!");
            // System.err.println("!!! [客户端错误] 致命问题：找不到声音文件! Key: '" + soundKey + "'");
            // System.err.println("!!! 请检查 preloadSounds() 方法和你的 aac/wav 文件名是否完全一致！");
            // System.err.println("!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!!");
            return; // 找不到声音，直接返回
        }

        double finalVolume;

        // 如果 soundPos 不为 null，说明是世界中的3D位置音效
        if (soundPos != null && me != null) {
            double distance = soundPos.distance(me.renderX, me.renderY);
            if (distance > MAX_SOUND_DISTANCE) {
                return; // 声音太远，听不到
            }

            // 先根据距离计算一个0到1的基础音量
            double baseVolume = Math.max(0, 1 - (distance / MAX_SOUND_DISTANCE));

            // 设定一个基础的音量“乘数”
            double volumeMultiplier;

            if (soundKey.contains("_fire")) {
                volumeMultiplier = 0.5; // 枪声音量乘数
            } else if (soundKey.contains("detonate") || soundKey.contains("explode")) {
                // 所有爆炸声的基础音量乘数可以设得高一些
                volumeMultiplier = 0.8;
            } else if (soundKey.contains("bounce") || soundKey.contains("hit")) {
                volumeMultiplier = 0.5; // 弹跳声中等
            } else if (soundKey.contains("_reload")) {
                volumeMultiplier = 2.2; // 弹跳声中等
            } else {
                volumeMultiplier = 0.6; // 其他声音 (如冒烟)
            }

            // 在这里对所有道具音效应用 -10dB (乘以0.33) 的衰减
            if (soundKey.startsWith("flashbang_") || soundKey.startsWith("smoke_") || soundKey.startsWith("hegrenade_")
                    || soundKey.startsWith("molotov_") || soundKey.startsWith("incendiary_")
                    || soundKey.startsWith("fire_") || soundKey.startsWith("explosion_")) {
                volumeMultiplier *= 0.33;
            }

            // 一次性计算出最终音量
            finalVolume = baseVolume * volumeMultiplier;

        } else if (soundKey.startsWith("kill")) {
            finalVolume = 15.5;
        } else {
            // 如果是只给自己听的2D私有音效 (如UI、耳鸣)，使用固定的较大音量
            finalVolume = 0.7;
        }

        // 远程MP3语音使用JavaFX MediaPlayer后端；串行化可阻止原生播放器线程群重叠创建。
        // WAV枪声不经过这里的门控，射击反馈频率保持不变。
        if (soundPos != null && mediaBackedSoundKeys.contains(soundKey)) {
            AudioClip active = activeMediaBackedWorldSound;
            boolean activeStillPlaying = active != null && active.isPlaying();
            if (!mediaSoundGate.tryAcquire(System.nanoTime(), activeStillPlaying)) {
                mediaBackedWorldSoundsSuppressed.increment();
                return;
            }
            activeMediaBackedWorldSound = clip;
            mediaBackedWorldSoundsPlayed.increment();
        }

        // 高频WAV优先进入固定混音线程；只有设备/格式不可用时才回退JavaFX媒体后端。
        PcmAudioMixer.Sound pcmSound = pcmSounds.get(soundKey);
        if (pcmSound != null && pcmAudioMixer.play(pcmSound, finalVolume))
            return;
        pcmFallbackPlays.increment();
        clip.play(finalVolume);
    }

    /**
     * 每帧更新所有循环音效的音量
     */
    private void updateLoopingSoundsVolume() {
        if (me == null) { // 如果玩家不存在或死亡，停止所有循环音效
            loopingSounds.values().forEach(AudioClip::stop);
            loopingSounds.clear();
            return;
        }

        // 遍历所有正在循环的声音 (比如火焰燃烧声)
        loopingSounds.forEach((soundId, clip) -> {
            // 尝试在游戏世界的火焰列表中找到这个声音的来源
            JsonObject firePatch = firePatches.get(soundId);

            if (firePatch != null) {
                Point2D soundPos = new Point2D(getDouble(firePatch, "x"), getDouble(firePatch, "y"));
                double distance = soundPos.distance(me.renderX, me.renderY);

                // 根据实时距离重新计算音量
                double finalVolume = Math.max(0, 1 - (distance / MAX_SOUND_DISTANCE)) * 0.6;

                if (finalVolume > 0.01) {
                    clip.setVolume(finalVolume);
                    // 如果因为之前离太远而暂停了，现在重新播放
                    if (!clip.isPlaying()) {
                        clip.play();
                    }
                } else {
                    // 如果离太远听不见了，就暂停播放以节省资源
                    clip.stop();
                }
            }
        });
    }

    /**
     * 这是一个专门用于启动和控制循环音效的重载方法
     *
     * @param clipInstance 要播放的 AudioClip 实例 (已经设置了无限循环)
     * @param soundPos     声音在世界中的位置
     */
    private void playSound(AudioClip clipInstance, Point2D soundPos) {
        if (clipInstance == null)
            return;

        double finalVolume = 1.0;
        if (soundPos != null && me != null) {
            double distance = soundPos.distance(me.renderX, me.renderY);
            // 火焰燃烧声的音量衰减可以缓和一些
            finalVolume = Math.max(0, 1 - (distance / MAX_SOUND_DISTANCE)) * 0.6;
        }

        // 如果计算出的音量大于0才播放
        if (finalVolume > 0) {
            clipInstance.setVolume(finalVolume);
            // 只有在没播放的时候才调用 play()，避免重复播放
            if (!clipInstance.isPlaying()) {
                clipInstance.play();
            }
        } else {
            clipInstance.stop(); // 如果离得太远听不见了，就停掉
        }

    }

    // 设置文本框的样式。
    private void styleTextField(TextField field) {
        // 设置居中对齐。
        field.setAlignment(Pos.CENTER);
        // 设置CSS样式。
        field.setStyle("-fx-background-color: #1a202c; " +
                "-fx-text-fill: white; " +
                "-fx-border-color: #4a5568; " +
                "-fx-border-width: 1; " +
                "-fx-border-radius: 6; " +
                "-fx-background-radius: 6; " +
                "-fx-padding: 12px;");
        // 设置首选宽度。
        field.setPrefWidth(300);
    }

    // 设置按钮的样式。
    private void styleButton(Button btn) {
        // 设置基本样式。
        btn.setStyle(buttonStyle);
        // 设置字体。
        btn.setFont(buttonFont);
        // 设置鼠标进入事件，添加悬停样式和手形光标。
        btn.setOnMouseEntered(e -> {
            btn.setStyle(buttonStyle + buttonHoverStyle);
            btn.setCursor(Cursor.HAND);
        });
        // 设置鼠标离开事件，恢复基本样式和默认光标。
        btn.setOnMouseExited(e -> {
            btn.setStyle(buttonStyle);
            btn.setCursor(Cursor.DEFAULT);
        });
        // 设置鼠标按下事件，改变背景颜色和位置，模拟按下效果。
        btn.setOnMousePressed(e -> btn.setStyle(buttonStyle + "-fx-background-color: #1a202c; -fx-translate-y: 1px;"));
        // 设置鼠标释放事件，恢复悬停样式和位置。
        btn.setOnMouseReleased(e -> {
            btn.setStyle(buttonStyle + buttonHoverStyle);
            btn.setTranslateY(0);
        });
    }

    // 创建武器类别映射。
    private static Map<String, List<String>> createWeaponCategories() {
        // 创建一个有序的哈希映射。
        Map<String, List<String>> map = new LinkedHashMap<>();
        // 添加手枪类别。
        map.put("Pistols", Arrays.asList("GLOCK18", "P2000", "USPS", "DUAL_BERETTAS", "P250", "FIVESEVEN", "TEC9",
                "CZ75", "DEAGLE", "R8"));
        // 添加霰弹枪类别。
        map.put("Shotguns", Arrays.asList("NOVA", "XM1014", "MAG7", "SAWEDOFF"));
        // 添加冲锋枪类别。
        map.put("SMGs", Arrays.asList("MP9", "MAC10", "MP7", "MP5SD", "UMP45", "P90", "BIZON"));
        // 添加步枪类别。
        map.put("Rifles", Arrays.asList("AK47", "M4A4", "M4A1S", "FAMAS", "GALIL", "AUG", "SG553"));
        // 添加狙击枪类别。
        map.put("Snipers", Arrays.asList("AWP", "SSG08", "SCAR20", "G3SG1"));
        // 添加机枪类别。
        map.put("Machine_Guns", Arrays.asList("NEGEV", "M249"));
        // 返回不可修改的映射。
        return Collections.unmodifiableMap(map);
    }
    // 创建武器数据映射。[Client]

    private static Map<String, cs2d.client.GameClient.WeaponUIData> createWeaponData() {
        Map<String, cs2d.client.GameClient.WeaponUIData> map = new HashMap<>();

        // T 阵营武器
        map.put("AK47", new cs2d.client.GameClient.WeaponUIData("AK-47", 2700, "T", 30, false));
        map.put("GALIL", new cs2d.client.GameClient.WeaponUIData("Galil AR", 1800, "T", 35, false));
        map.put("SG553", new cs2d.client.GameClient.WeaponUIData("SG 553", 3000, "T", 30, false));
        map.put("MAC10", new cs2d.client.GameClient.WeaponUIData("MAC-10", 1050, "T", 30, false));
        map.put("TEC9", new cs2d.client.GameClient.WeaponUIData("Tec-9", 500, "T", 18, false));
        map.put("GLOCK18", new cs2d.client.GameClient.WeaponUIData("Glock-18", 200, "T", 20, false));
        map.put("SAWEDOFF", new cs2d.client.GameClient.WeaponUIData("Sawed-Off", 1100, "T", 7, true));
        map.put("G3SG1", new cs2d.client.GameClient.WeaponUIData("G3SG1", 5000, "T", 20, false));

        // CT 阵营武器
        map.put("M4A4", new cs2d.client.GameClient.WeaponUIData("M4A4", 3100, "CT", 30, false));
        map.put("M4A1S", new cs2d.client.GameClient.WeaponUIData("M4A1-S", 2900, "CT", 20, false));
        map.put("FAMAS", new cs2d.client.GameClient.WeaponUIData("FAMAS", 2050, "CT", 25, false));
        map.put("AUG", new cs2d.client.GameClient.WeaponUIData("AUG", 3300, "CT", 30, false));
        map.put("MP9", new cs2d.client.GameClient.WeaponUIData("MP9", 1250, "CT", 30, false));
        map.put("FIVESEVEN", new cs2d.client.GameClient.WeaponUIData("Five-SeveN", 500, "CT", 20, false));
        map.put("USPS", new cs2d.client.GameClient.WeaponUIData("USP-S", 200, "CT", 12, false));
        map.put("P2000", new cs2d.client.GameClient.WeaponUIData("P2000", 200, "CT", 13, false));
        map.put("MAG7", new cs2d.client.GameClient.WeaponUIData("MAG-7", 1300, "CT", 5, false));
        map.put("SCAR20", new cs2d.client.GameClient.WeaponUIData("SCAR-20", 5000, "CT", 20, false));
        map.put("MP5SD", new cs2d.client.GameClient.WeaponUIData("MP5-SD", 1500, "ANY", 30, false)); // 也是CT常用但定义为ANY

        // 双方通用武器
        map.put("AWP", new cs2d.client.GameClient.WeaponUIData("AWP", 4750, "ANY", 5, false));
        map.put("SSG08", new cs2d.client.GameClient.WeaponUIData("SSG 08", 1700, "ANY", 10, false));
        map.put("MP7", new cs2d.client.GameClient.WeaponUIData("MP7", 1500, "ANY", 30, false));
        map.put("UMP45", new cs2d.client.GameClient.WeaponUIData("UMP-45", 1200, "ANY", 25, false));
        map.put("P90", new cs2d.client.GameClient.WeaponUIData("P90", 2350, "ANY", 50, false));
        map.put("BIZON", new cs2d.client.GameClient.WeaponUIData("PP-Bizon", 1400, "ANY", 64, false));
        map.put("NOVA", new cs2d.client.GameClient.WeaponUIData("Nova", 1050, "ANY", 8, true));
        map.put("XM1014", new cs2d.client.GameClient.WeaponUIData("XM1014", 2000, "ANY", 7, true));
        map.put("NEGEV", new cs2d.client.GameClient.WeaponUIData("Negev", 1700, "ANY", 150, false));
        map.put("M249", new cs2d.client.GameClient.WeaponUIData("M249", 5200, "ANY", 100, false));
        map.put("P250", new cs2d.client.GameClient.WeaponUIData("P250", 300, "ANY", 13, false));
        map.put("CZ75", new cs2d.client.GameClient.WeaponUIData("CZ75-Auto", 500, "ANY", 12, false));
        map.put("DEAGLE", new cs2d.client.GameClient.WeaponUIData("Desert Eagle", 700, "ANY", 7, false));
        map.put("R8", new cs2d.client.GameClient.WeaponUIData("R8 Revolver", 600, "ANY", 8, false));
        map.put("DUAL_BERETTAS", new cs2d.client.GameClient.WeaponUIData("Dual Berettas", 300, "ANY", 30, false));

        return Collections.unmodifiableMap(map);
    }

    // 创建物品数据映射。
    private static Map<String, cs2d.client.GameClient.ItemUIData> createItemData() {
        // 创建一个哈希映射。
        Map<String, cs2d.client.GameClient.ItemUIData> map = new HashMap<>();
        // 添加各种物品的数据。
        map.put("HE_GRENADE", new cs2d.client.GameClient.ItemUIData("HE Grenade", 300, 1, null));
        map.put("FLASHBANG", new cs2d.client.GameClient.ItemUIData("Flashbang", 200, 2, null));
        // 添加各种物品的数据。
        map.put("SMOKE_GRENADE", new cs2d.client.GameClient.ItemUIData("Smoke Grenade", 300, 1, null));
        map.put("MOLOTOV", new cs2d.client.GameClient.ItemUIData("Molotov (T)", 500, 1, "T"));
        // 添加各种武器的数据。
        map.put("INCENDIARY", new cs2d.client.GameClient.ItemUIData("Incendiary (CT)", 600, 1, "CT"));
        map.put("DECOY", new cs2d.client.GameClient.ItemUIData("Decoy Grenade", 50, 1, null));
        // 添加各种物品的数据。
        map.put("KEVLAR", new cs2d.client.GameClient.ItemUIData("Kevlar", 650, 1, null));
        map.put("KEVLAR_HELMET", new cs2d.client.GameClient.ItemUIData("Kevlar + Helmet", 1000, 1, null));
        // 添加各种物品的数据。
        map.put("DEFUSE_KIT", new cs2d.client.GameClient.ItemUIData("Defuse Kit", 400, 1, "CT"));
        // 返回不可修改的映射。
        return Collections.unmodifiableMap(map);
    }

    // --- 复杂数学/几何（FOV） ---
    // 判断一个点是否在多边形内。
    private boolean isPointInPolygon(Point2D point, List<Point2D> polygon) {
        // 如果多边形为空，则返回false。
        if (polygon == null || polygon.isEmpty())
            return false;
        // 声明变量。
        int i, j;
        // 初始化标志。
        boolean c = false;
        // 使用射线法判断点是否在多边形内。
        for (i = 0, j = polygon.size() - 1; i < polygon.size(); j = i++) {
            // 如果射线与边相交。
            if (((polygon.get(i).getY() > point.getY()) != (polygon.get(j).getY() > point.getY())) &&
                    (point.getX() < (polygon.get(j).getX() - polygon.get(i).getX())
                            * (point.getY() - polygon.get(i).getY()) / (polygon.get(j).getY() - polygon.get(i).getY())
                            + polygon.get(i).getX()))
                // 翻转标志。
                c = !c;
        }
        // 返回结果。
        return c;
    }

    // 计算点到线段的最短距离的平方。
    private double distToSegmentSquared(Point2D p, Point2D v, Point2D w) {
        // 计算线段长度的平方。
        double l2 = v.distance(w) * v.distance(w);
        // 如果线段长度为0，则返回点到端点的距离的平方。
        if (l2 == 0)
            return p.distance(v) * p.distance(v);
        // 计算投影点在线段上的位置。
        double t = Math.max(0, Math.min(1, p.subtract(v).dotProduct(w.subtract(v)) / l2));
        // 计算投影点。
        Point2D projection = v.add(w.subtract(v).multiply(t));
        // 返回点到投影点的距离的平方。
        return p.distance(projection) * p.distance(projection);
    }

    // 获取两条线的交点。
    private Point2D getLineIntersection(Point2D p1, Point2D p2, Point2D p3, Point2D p4) {
        // 计算行列式。
        double det = (p1.getX() - p2.getX()) * (p3.getY() - p4.getY())
                - (p1.getY() - p2.getY()) * (p3.getX() - p4.getX());
        // 如果平行，则返回null。
        if (det == 0)
            return null;
        // 计算交点参数t。
        double t = ((p1.getX() - p3.getX()) * (p3.getY() - p4.getY())
                - (p1.getY() - p3.getY()) * (p3.getX() - p4.getX())) / det;
        // 计算交点参数u。
        double u = -((p1.getX() - p2.getX()) * (p1.getY() - p3.getY())
                - (p1.getY() - p2.getY()) * (p1.getX() - p3.getX())) / det;
        // 如果交点在线段内，则返回交点坐标。
        if (t > 0 && t < 1 && u > 0 && u < 1)
            return new Point2D(p1.getX() + t * (p2.getX() - p1.getX()), p1.getY() + t * (p2.getY() - p1.getY()));
        // 否则返回null。
        return null;
    }

    // 计算视野。
    private List<Point2D> calculateFOV(Point2D playerPos, JsonArray obstacles) {
        // 如果 'me' 对象不存在或玩家已死亡，则返回一个空列表（不显示视野）
        if (me == null || !getBool(me.data, "isAlive")) {
            return new ArrayList<>();
        }

        // 获取玩家当前朝向，FOV: 106
        final double playerAngle = me.angle;
        final double fovRadians = Math.toRadians(106.0);

        // 收集地图上所有障碍物的唯一顶点（这部分逻辑与原来相同）
        Set<Point2D> uniquePoints = new HashSet<>();
        obstacles.forEach(obsEl -> {
            JsonObject obs = obsEl.getAsJsonObject();
            String type = getString(obs, "type");
            double x = getDouble(obs, "x"), y = getDouble(obs, "y"), w = getDouble(obs, "w"), h = getDouble(obs, "h");
            if ("RECTANGLE".equals(type)) {
                uniquePoints.add(new Point2D(x, y));
                uniquePoints.add(new Point2D(x + w, y));
                uniquePoints.add(new Point2D(x, y + h));
                uniquePoints.add(new Point2D(x + w, y + h));
            } else if ("ELLIPSE".equals(type)) {
                for (int i = 0; i < 16; i++)
                    uniquePoints.add(new Point2D(x + w / 2 + (w / 2) * Math.cos(i / 16.0 * 2 * Math.PI),
                            y + h / 2 + (h / 2) * Math.sin(i / 16.0 * 2 * Math.PI)));
            } else if ("POLYGON".equals(type) && obs.has("xPoints")) {
                JsonArray xP = obs.getAsJsonArray("xPoints"), yP = obs.getAsJsonArray("yPoints");
                for (int i = 0; i < xP.size(); i++)
                    uniquePoints.add(new Point2D(xP.get(i).getAsDouble(), yP.get(i).getAsDouble()));
            }
        });

        // 创建一个列表，用于存储所有需要投射光线的角度
        List<Double> angles = new ArrayList<>();
        // 首先将视野的两个边界角度加进去，确保视野范围被完整绘制
        angles.add(playerAngle - fovRadians / 2.0);
        angles.add(playerAngle + fovRadians / 2.0);

        // 筛选顶点，只将在我们视野锥形区域内的顶点角度加入列表
        uniquePoints.forEach(point -> {
            double angleToPoint = Math.atan2(point.getY() - playerPos.getY(), point.getX() - playerPos.getX());

            // 计算顶点相对于玩家朝向的角度
            double relativeAngle = angleToPoint - playerAngle;

            // 将相对角度“标准化”到 [-PI, PI] 的范围内，以正确处理角度环绕
            while (relativeAngle <= -Math.PI)
                relativeAngle += 2 * Math.PI;
            while (relativeAngle > Math.PI)
                relativeAngle -= 2 * Math.PI;

            // 如果顶点的相对角度在视野范围内，就将其绝对角度加入列表
            if (Math.abs(relativeAngle) <= fovRadians / 2.0) {
                angles.add(angleToPoint - 1e-4);
                angles.add(angleToPoint);
                angles.add(angleToPoint + 1e-4);
            }
        });

        // 对角度进行自定义排序
        // 我们不再使用简单的数字排序，而是根据角度与玩家朝向的“相对差值”来排序。
        // 这能完美解决朝西（或任何方向）时角度环绕导致的排序错误问题。
        angles.sort(Comparator.comparingDouble(angle -> {
            double delta = angle - playerAngle;
            while (delta <= -Math.PI)
                delta += 2 * Math.PI;
            while (delta > Math.PI)
                delta -= 2 * Math.PI;
            return delta;
        }));

        // 构建最终的视野多边形（扇形）
        List<Point2D> fovPolygon = new ArrayList<>();
        // 多边形的第一个顶点必须是玩家自身的位置
        fovPolygon.add(playerPos);

        // 为每个排好序的角度投射光线，并将交点加入多边形
        angles.forEach(angle -> {
            // 这部分的光线投射逻辑与您原来的代码完全相同
            Point2D rayEnd = new Point2D(playerPos.getX() + 4000 * Math.cos(angle),
                    playerPos.getY() + 4000 * Math.sin(angle));
            Point2D closestHit = rayEnd;
            double minDistanceSq = Double.POSITIVE_INFINITY;

            for (JsonElement obsEl : obstacles) {
                List<Point2D[]> lines = new ArrayList<>();
                JsonObject obs = obsEl.getAsJsonObject();
                String type = getString(obs, "type");
                double x = getDouble(obs, "x"), y = getDouble(obs, "y"), w = getDouble(obs, "w"),
                        h = getDouble(obs, "h");

                if ("RECTANGLE".equals(type)) {
                    lines.add(new Point2D[] { new Point2D(x, y), new Point2D(x + w, y) });
                    lines.add(new Point2D[] { new Point2D(x + w, y), new Point2D(x + w, y + h) });
                    lines.add(new Point2D[] { new Point2D(x + w, y + h), new Point2D(x, y + h) });
                    lines.add(new Point2D[] { new Point2D(x, y + h), new Point2D(x, y) });
                } else if ("POLYGON".equals(type) && obs.has("xPoints")) {
                    JsonArray xP = obs.getAsJsonArray("xPoints");
                    JsonArray yP = obs.getAsJsonArray("yPoints");
                    for (int i = 0; i < xP.size(); i++) {
                        Point2D p1 = new Point2D(xP.get(i).getAsDouble(), yP.get(i).getAsDouble());
                        Point2D p2 = new Point2D(xP.get((i + 1) % xP.size()).getAsDouble(),
                                yP.get((i + 1) % yP.size()).getAsDouble());
                        lines.add(new Point2D[] { p1, p2 });
                    }
                } else if ("ELLIPSE".equals(type)) {
                    final int numSegments = 16;
                    final double centerX = x + w / 2, centerY = y + h / 2, radiusX = w / 2, radiusY = h / 2;
                    for (int i = 0; i < numSegments; i++) {
                        double angle1 = (i / (double) numSegments) * 2 * Math.PI;
                        double angle2 = ((i + 1) / (double) numSegments) * 2 * Math.PI;
                        Point2D p1 = new Point2D(centerX + radiusX * Math.cos(angle1),
                                centerY + radiusY * Math.sin(angle1));
                        Point2D p2 = new Point2D(centerX + radiusX * Math.cos(angle2),
                                centerY + radiusY * Math.sin(angle2));
                        lines.add(new Point2D[] { p1, p2 });
                    }
                }

                for (Point2D[] line : lines) {
                    Point2D hit = getLineIntersection(playerPos, rayEnd, line[0], line[1]);
                    if (hit != null) {
                        double distSq = hit.distance(playerPos) * hit.distance(playerPos);
                        if (distSq < minDistanceSq) {
                            minDistanceSq = distSq;
                            closestHit = hit;
                        }
                    }
                }
            }
            fovPolygon.add(closestHit);
        });

        return fovPolygon;
    }

    // 将JavaFX颜色转换为CSS颜色字符串。
    private String toCssColor(Color color) {
        // 格式化为#RRGGBB格式。
        return String.format("#%02x%02x%02x",
                (int) (color.getRed() * 255),
                (int) (color.getGreen() * 255),
                (int) (color.getBlue() * 255));
    }

    /**
     * 当从服务器收到数据时，调用此方法来处理击杀信息
     *
     * @param killFeedArray 从服务器JSON中获取的 "killFeed" 数组
     */
    private void processKillFeed(JsonArray killFeedArray) {
        if (killFeedArray == null || killFeedArray.isEmpty())
            return;

        // [新增打印] 打印从服务器收到的最原始的 killFeed 数据
        // System.out.println("[DEBUG-CLIENT] 收到来自服务器的 killFeed 数组: " +
        // killFeedArray.toString());

        long currentTime = System.currentTimeMillis();

        // 遍历从服务器收到的每一条击杀信息
        for (JsonElement el : killFeedArray) {
            JsonObject entryJson = el.getAsJsonObject();

            // 为每一条信息生成一个唯一的ID，确保不会重复添加
            String uniqueId = getString(entryJson, "killerId") + getString(entryJson, "victimId")
                    + getString(entryJson, "killTimestamp");
            if (uniqueId.isEmpty())
                continue; // 如果ID无效，则跳过

            // 检查这个ID是否已经显示在屏幕上
            boolean alreadyOnScreen = killFeedVBox.getChildren().stream()
                    .anyMatch(node -> uniqueId.equals(node.getProperties().get("uniqueId")));

            // 如果没有显示，就创建并添加它
            if (!alreadyOnScreen) {
                // [新增打印] 确认将要为一条新的击杀信息创建UI
                // System.out.println("[DEBUG-CLIENT] 检测到新的击杀事件 (ID: " + uniqueId +
                // ")，准备创建UI行。");

                HBox row = createKillFeedRow(entryJson);
                // 将唯一ID和创建时间存入UI节点自身，以便后续超时检查
                row.getProperties().put("uniqueId", uniqueId);
                row.getProperties().put("creationTime", currentTime);

                killFeedVBox.getChildren().add(0, row); // 添加到列表顶部

                // 播放渐入动画
                FadeTransition fadeIn = new FadeTransition(Duration.millis(300), row);
                fadeIn.setToValue(1.0);
                fadeIn.play();
            }
            // =================================================================
            // 本地统计数据实时同步逻辑 (核心修复)
            // =================================================================
            if (!processedKillFeedIds.contains(uniqueId)) {
                processedKillFeedIds.add(uniqueId);

                String killerId = getString(entryJson, "killerId");
                String killerName = getString(entryJson, "killerName");
                String victimId = getString(entryJson, "victimId");
                String victimName = getString(entryJson, "victimName");
                boolean isHeadshot = getBool(entryJson, "isHeadshot");

                // 1. 查找杀手 (优先ID，备选名字前缀匹配 - 处理夺舍BOT情况)
                cs2d.client.GameClient.ClientPlayer killer = clientPlayers.get(killerId);
                if (killer == null) {
                    for (cs2d.client.GameClient.ClientPlayer p : clientPlayers.values()) {
                        String pName = getString(p.data, "name");
                        if (!pName.isEmpty() && killerName.startsWith(pName)) {
                            killer = p;
                            break;
                        }
                    }
                }
                if (killer == null)
                    killer = clientZombies.get(killerId);

                // 2. 查找受害者
                cs2d.client.GameClient.ClientPlayer victim = clientPlayers.get(victimId);
                if (victim == null) {
                    for (cs2d.client.GameClient.ClientPlayer p : clientPlayers.values()) {
                        String pName = getString(p.data, "name");
                        if (!pName.isEmpty() && victimName.startsWith(pName)) {
                            victim = p;
                            break;
                        }
                    }
                }
                if (victim == null)
                    victim = clientZombies.get(victimId);

                // 3. 更新杀手统计
                if (killer != null && killer.data != null) {
                    killer.mutateData(snapshot -> {
                        snapshot.addProperty("kills", getInt(snapshot, "kills") + 1);
                        if (isHeadshot)
                            snapshot.addProperty("totalHeadshots", getInt(snapshot, "totalHeadshots") + 1);
                    });
                    // 同步更新 Score (公式: dmg + k*50 + hs*20 - d*50)
                    updateLocalScore(killer);
                }

                // 4. 更新受害者统计
                if (victim != null && victim.data != null) {
                    victim.mutateData(snapshot -> snapshot.addProperty("deaths", getInt(snapshot, "deaths") + 1));
                    updateLocalScore(victim);
                }

                // 检查这次击杀的凶手是不是我自己 (用于HUD特效)
                if (myPlayerId != null
                        && (myPlayerId.equals(killerId) || (killer != null && myPlayerId.equals(killer.id)))) {

                    // --- 增加时间戳检查 ---
                    // 获取击杀发生时的时间戳
                    long killTimestamp = getLong(entryJson, "killTimestamp");
                    // 定义一个时间阈值（例如2秒），超过这个时间的击杀被认为是“旧的”
                    long timeThreshold = 2000;

                    // 只有当这次击杀是“最近”发生的，才会计入本条命的击杀数
                    if (System.currentTimeMillis() - killTimestamp < timeThreshold) {
                        killsThisLife++; // 是我刚刚完成的击杀，计数器+1
                        playKillFlashEffect(); // 播放高光特效
                    }
                }
            }
            // =================================================================
        }
        // 在添加完所有新条目后，检查总量是否超过4条
        // 如果超过了，就从底部（最旧的条目）开始移除，直到剩下4条
        while (killFeedVBox.getChildren().size() > gameSettings.getMaxKillFeedEntries()) {
            // =======================================================
            killFeedVBox.getChildren().remove(killFeedVBox.getChildren().size() - 1);
        }
    }

    /**
     * 创建一个能同时显示武器和爆头图标的UI行
     *
     * @param entryJson 单条击杀的JSON数据
     * @return 创建好的HBox节点
     */
    private HBox createKillFeedRow(JsonObject entryJson) {
        HBox row = new HBox(10); // 击杀者、图标、受害者之间的间距
        row.setAlignment(Pos.CENTER_RIGHT);
        row.setPadding(new Insets(4, 8, 4, 8));
        row.setStyle("-fx-background-color: rgba(45, 55, 72, 0.7); -fx-background-radius: 4;");
        row.setOpacity(0); // 初始透明

        // 创建击杀者标签 (不变)
        Label killerLabel = new Label(getString(entryJson, "killerName"));
        killerLabel.setFont(smallHudFont);
        killerLabel
                .setTextFill(getColorForPlayer(getString(entryJson, "killerId"), getString(entryJson, "killerTeam")));

        // 获取武器名称和爆头状态 (不变)
        String weaponName = getString(entryJson, "weapon");
        boolean isHeadshot = getBool(entryJson, "isHeadshot");

        // [打印日志，保持不变]
        // System.out.println("[DEBUG-CLIENT] createKillFeedRow: 准备为武器 '" + weaponName +
        // "' (爆头: " + isHeadshot + ") 获取图标。");

        // 获取武器图标 (不再传入 isHeadshot)
        Node weaponIcon = cs2d.client.GameClient.WeaponIcon.getIcon(weaponName, false); // <--- 重要修改：这里永远传
        // false，确保获取的是武器图标

        // 创建受害者标签 (不变)
        Label victimLabel = new Label(getString(entryJson, "victimName"));
        victimLabel.setFont(smallHudFont);
        victimLabel
                .setTextFill(getColorForPlayer(getString(entryJson, "victimId"), getString(entryJson, "victimTeam")));

        // 根据是否爆头，决定最终显示的图标组合
        if (isHeadshot) {
            // 如果是爆头，获取爆头图标
            Node headshotIcon = cs2d.client.GameClient.WeaponIcon.getIcon(null, true); // <--- 重要修改：用 true 来专门获取爆头图标

            // 将武器图标和爆头图标并排放在一个新的 HBox 里
            HBox iconsContainer = new HBox(5); // 两个图标之间的间距
            iconsContainer.setAlignment(Pos.CENTER);
            iconsContainer.getChildren().addAll(weaponIcon, headshotIcon);

            // 将整个图标组合添加到主行中
            row.getChildren().addAll(killerLabel, iconsContainer, victimLabel);
        } else {
            // 如果不是爆头，只添加武器图标
            row.getChildren().addAll(killerLabel, weaponIcon, victimLabel);
        }
        return row;
    }

    /**
     * 这个方法会在每一帧被调用，用于检查并移除超时的击杀信息
     */
    private void updateAndCleanKillFeed() {
        long currentTime = System.currentTimeMillis();
        long timeout = 5000; // 5秒超时

        // 创建一个列表来存储需要移除的节点，避免在遍历时直接修改列表
        List<Node> nodesToRemove = new ArrayList<>();

        for (Node node : killFeedVBox.getChildren()) {
            // 安全地获取创建时间
            Object creationTimeObj = node.getProperties().get("creationTime");
            if (creationTimeObj instanceof Long) {
                long creationTime = (Long) creationTimeObj;

                // 如果超时了，并且还没有开始消失动画
                if ((currentTime - creationTime > timeout) && node.getProperties().get("fadingOut") == null) {
                    node.getProperties().put("fadingOut", true); // 标记为正在消失

                    // 在动画开始时就让它不参与布局
                    node.setManaged(false);

                    FadeTransition fadeOut = new FadeTransition(Duration.seconds(1), node);
                    fadeOut.setToValue(0);
                    // 动画结束后，将节点加入待移除列表
                    fadeOut.setOnFinished(e -> nodesToRemove.add(node));
                    fadeOut.play();
                }
            }
        }
        // 统一移除所有已完成消失动画的节点
        if (!nodesToRemove.isEmpty()) {
            killFeedVBox.getChildren().removeAll(nodesToRemove);
        }
    }

    private Color getColorForPlayer(String playerId, String team) {
        if (myPlayerId != null && playerId.equals(myPlayerId))
            return PRIMARY_GREEN;
        return "CT".equals(team) ? PRIMARY_BLUE : PRIMARY_RED;
    }

    // 创建HUD（平视显示器）覆盖层 -
    private Node createHUDOverlay() {
        AnchorPane hudPane = new AnchorPane();
        hudPane.setPickOnBounds(false);

        // --- 左上角 (Top-Left) ---
        VBox topLeft = new VBox(8);
        topLeft.setPadding(new Insets(15));
        AnchorPane.setTopAnchor(topLeft, 10.0);
        AnchorPane.setLeftAnchor(topLeft, 10.0);
        topLeft.setPickOnBounds(false);
        topLeft.setMouseTransparent(true);
        fpsLabel = new Label("FPS: --"); // 初始文本
        fpsLabel.setFont(tinyHudFont); // 使用您定义的“小小”字体
        fpsLabel.setTextFill(Color.GREEN); // 设置为白色

        ctScoreLabel = new Label();
        ctScoreLabel.setFont(hudFont);
        tScoreLabel = new Label();
        tScoreLabel.setFont(hudFont);
        waveLabel = new Label();
        waveLabel.setFont(hudFont);
        zombiesLeftLabel = new Label();
        zombiesLeftLabel.setFont(hudFont);

        topLeft.getChildren().addAll(fpsLabel, ctScoreLabel, tScoreLabel, waveLabel, zombiesLeftLabel);

        // =======================================================================
        // --- 右上角 (Top-Right) ---
        VBox topRight = new VBox(8);
        topRight.setAlignment(Pos.TOP_RIGHT);
        topRight.setPadding(new Insets(15));
        AnchorPane.setTopAnchor(topRight, 10.0);
        AnchorPane.setRightAnchor(topRight, 10.0);
        topRight.setPickOnBounds(false); // <--- 新增
        topRight.setMouseTransparent(true);

        killFeedVBox = new VBox(5);
        killFeedVBox.setAlignment(Pos.TOP_RIGHT);

        // 恢复创建 Button
        Button cameraButton = new Button("[Enter] Toggle View");
        cameraButton.setFont(smallHudFont);
        cameraButton.setTextFill(Color.YELLOW);
        cameraButton.setStyle("-fx-background-color: transparent; -fx-padding: 0;");

        // 恢复按钮原有的点击/回车功能
        cameraButton.setOnAction(e -> {
            // 实现 3 模式循环切换: Follow -> Full -> Free
            if ("follow".equals(cameraMode)) {
                cameraMode = "full";
            } else if ("full".equals(cameraMode)) {
                cameraMode = "free";
                // 切换到 free 模式时，将相机位置初始化为当前位置
            } else {
                cameraMode = "follow";
            }
        });
        // 为按钮添加一个事件过滤器，让它“吃掉”空格键事件
        cameraButton.addEventFilter(KeyEvent.KEY_PRESSED, event -> {
            if (event.getCode() == KeyCode.SPACE) {
                // 当检测到是空格键时，消耗掉这个事件，按钮本身就不会再响应它
                event.consume();
            }
        });

        pingLabel = new Label();
        pingLabel.setFont(smallHudFont);
        pingLabel.setTextFill(Color.WHITE);
        roundLabel = new Label();
        roundLabel.setFont(hudFont);
        timerLabel = new Label();
        timerLabel.setFont(hudFont);

        // 将 cameraButton 加回到布局中
        topRight.getChildren().addAll(killFeedVBox, cameraButton, pingLabel, roundLabel, timerLabel);
        // =======================================================================

        // --- 左下角 (Bottom-Left) ---
        VBox bottomLeft = new VBox(8);
        bottomLeft.setPadding(new Insets(15));
        AnchorPane.setBottomAnchor(bottomLeft, 10.0);
        AnchorPane.setLeftAnchor(bottomLeft, 10.0);
        bottomLeft.setPickOnBounds(false);
        bottomLeft.setMouseTransparent(true);

        // --- 提前创建好所有左下角的UI元素 ---

        // 创建战败补偿UI
        lossBonusBox = new VBox(3);
        Label lossLabel = new Label("Loss Bonus: ");
        lossLabel.setFont(smallHudFont);
        lossLabel.setTextFill(Color.GRAY);
        HBox barsContainer = new HBox(3);
        lossBonusBars = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            Region bar = new Region();
            bar.setPrefSize(16, 16);
            barsContainer.getChildren().add(bar);
            lossBonusBars.add(bar);
        }
        lossBonusBox.getChildren().addAll(lossLabel, barsContainer);

        // =================================================================
        // 创建“本条命击杀数”UI
        // =================================================================
        killsThisLifeContainer = new StackPane();
        killsThisLifeContainer.setAlignment(Pos.CENTER_LEFT);
        killFlashEffect = new Pane();
        killFlashEffect.setPrefHeight(30);
        killFlashEffect.setMaxWidth(0);
        killFlashEffect.setStyle(
                "-fx-background-color: linear-gradient(to right, rgba(255, 235, 59, 0.7), rgba(255, 235, 59, 0));");
        killFlashEffect.setOpacity(0);
        // 统一文本格式
        killsThisLifeLabel = new Label("KILL : 0");
        killsThisLifeLabel.setFont(hudFont);
        killsThisLifeLabel.setTextFill(Color.WHITE);
        killsThisLifeContainer.getChildren().addAll(killFlashEffect, killsThisLifeLabel);

        // 创建其他所有Label
        moneyLabel = new Label();
        moneyLabel.setFont(hudFont);
        hpLabel = new Label();
        hpLabel.setFont(hudFont);
        armorLabel = new Label();
        armorLabel.setFont(hudFont);
        weaponLabel = new Label();
        weaponLabel.setFont(hudFont);
        ammoLabel = new Label();
        ammoLabel.setFont(hudFont);
        equipBox = new HBox(5);
        equipBox.setAlignment(Pos.CENTER_LEFT);
        buyPrompt = new Label();
        buyPrompt.setFont(tinyHudFont);
        changeWeaponPrompt = new Label();
        changeWeaponPrompt.setFont(tinyHudFont);

        // 将所有元素（包括新的killsThisLifeContainer）按正确顺序添加到布局中
        bottomLeft.getChildren().addAll(
                lossBonusBox,
                killsThisLifeContainer, // <--- 添加到这里！
                moneyLabel,
                hpLabel,
                armorLabel,
                weaponLabel,
                ammoLabel,
                equipBox,
                buyPrompt,
                changeWeaponPrompt);

        // --- 右下角 (Bottom-Right) - 伤害日志 ---
        // 在这里初始化你的成员变量
        // 使用 GridPane 初始化和列定义替换:
        damageLogDisplayBox = new GridPane(); // 初始化为 GridPane
        damageLogDisplayBox.setHgap(8); // 列之间的水平间距 (可调整)
        damageLogDisplayBox.setVgap(2); // 行之间的垂直间距
        damageLogDisplayBox.setAlignment(Pos.BOTTOM_RIGHT); // 网格本身在其 AnchorPane 约束内的对齐方式 ，不透明度0.01
        damageLogDisplayBox
                .setStyle("-fx-background-color: rgba(45, 55, 72, 0.01); -fx-background-radius: 4; -fx-padding: 8px;");

        // 定义列约束以控制对齐
        ColumnConstraints colName1 = new ColumnConstraints();
        colName1.setHalignment(HPos.RIGHT); // 将名字1 (你/敌人) 在其单元格内右对齐

        ColumnConstraints colArrow = new ColumnConstraints();
        colArrow.setHalignment(HPos.CENTER); // 居中箭头

        ColumnConstraints colName2 = new ColumnConstraints();
        colName2.setHalignment(HPos.LEFT); // 将名字2 (敌人/你) 左对齐

        ColumnConstraints colDamage = new ColumnConstraints();
        colDamage.setHalignment(HPos.RIGHT); // 将伤害数值右对齐

        ColumnConstraints colHits = new ColumnConstraints();
        colHits.setHalignment(HPos.LEFT); // 将命中数左对齐

        ColumnConstraints colKill = new ColumnConstraints();
        colKill.setHalignment(HPos.LEFT); // 将击杀标记左对齐

        damageLogDisplayBox.getColumnConstraints().addAll(
                colName1, colArrow, colName2, colDamage, colHits, colKill);

        // 定位到右下角
        AnchorPane.setBottomAnchor(damageLogDisplayBox, 10.0);
        AnchorPane.setRightAnchor(damageLogDisplayBox, 10.0);

        // 确保它不会阻挡鼠标点击
        damageLogDisplayBox.setPickOnBounds(false);
        damageLogDisplayBox.setMouseTransparent(true);

        // 默认隐藏
        damageLogDisplayBox.setVisible(false);
        damageLogDisplayBox.setManaged(false);

        // --- 中心 (Center) ---
        VBox center = new VBox(10);
        center.setAlignment(Pos.CENTER);
        AnchorPane.setTopAnchor(center, CANVAS_HEIGHT / 4.0);
        AnchorPane.setLeftAnchor(center, 0.0);
        AnchorPane.setRightAnchor(center, 0.0);
        center.setPickOnBounds(false); // <--- 新增
        center.setMouseTransparent(true);

        winnerLabel = new Label();
        winnerLabel.setFont(Font.font("Orbitron", FontWeight.BOLD, 60));
        reasonLabel = new Label();
        reasonLabel.setFont(hudFont);
        nextWaveLabel = new Label();
        nextWaveLabel.setFont(titleFont);
        center.getChildren().addAll(winnerLabel, reasonLabel, nextWaveLabel);

        // --- 交互进度条 (Interaction Bar) ---
        StackPane interactionBarContainer = new StackPane();
        interactionBarContainer.setMaxSize(200, 20);
        interactionBarContainer.setStyle(
                "-fx-background-color: rgba(0,0,0,0.5); -fx-border-color: white; -fx-border-radius: 5; -fx-background-radius: 5;");
        Pane interactionProgressBar = new Pane();
        interactionProgressBar.setStyle("-fx-background-color: #63B3ED; -fx-background-radius: 4;");
        interactionBarContainer.getChildren().add(interactionProgressBar);
        AnchorPane.setBottomAnchor(interactionBarContainer, CANVAS_HEIGHT / 4.0);
        AnchorPane.setLeftAnchor(interactionBarContainer, (CANVAS_WIDTH / 2.0 - 100.0));

        // --- 组装并启动更新 ---
        hudPane.getChildren().addAll(
                topLeft, topRight, bottomLeft, center,
                interactionBarContainer, damageLogDisplayBox);
        hudFrameUpdater = () -> updateHUDLabels(interactionBarContainer, interactionProgressBar);

        return hudPane;
    }

    /**
     * 播放击杀后的高亮闪光特效动画
     */
    private void playKillFlashEffect() {
        // 创建一个时间轴动画
        javafx.animation.Timeline timeline = new javafx.animation.Timeline();

        // 关键帧1: 动画开始时，特效层瞬间出现，宽度为0
        javafx.animation.KeyFrame kf1 = new javafx.animation.KeyFrame(Duration.ZERO,
                new javafx.animation.KeyValue(killFlashEffect.opacityProperty(), 1),
                new javafx.animation.KeyValue(killFlashEffect.maxWidthProperty(), 0));

        // 关键帧2: 在 150 毫秒内，特效层宽度从左到右扩展到标签的宽度
        javafx.animation.KeyFrame kf2 = new javafx.animation.KeyFrame(Duration.millis(150),
                new javafx.animation.KeyValue(killFlashEffect.maxWidthProperty(), killsThisLifeLabel.getWidth()));

        // 关键帧3: 在接下来的 800 毫秒内，特效层逐渐消失
        javafx.animation.KeyFrame kf3 = new javafx.animation.KeyFrame(Duration.millis(800),
                new javafx.animation.KeyValue(killFlashEffect.opacityProperty(), 0));

        timeline.getKeyFrames().addAll(kf1, kf2, kf3);
        timeline.play();
    }

    /**
     * 这个方法现在只负责更新HUD上的标签文本，不再处理击杀信息的UI逻辑。
     * 击杀信息现在由 processKillFeed() 和 updateAndCleanKillFeed() 独立处理。
     */
    // C4 嘀嘀声
    private void handleC4BeepSound() {
        if (latestGameState == null)
            return;

        boolean isBombPlanted = getBool(latestGameState, "bombPlanted");
        // 如果炸弹刚刚被安放
        if (isBombPlanted && !wasBombPlantedLastFrame) {
            lastC4BeepTime = 0; // 重置计时器，立即播放第一声
        }
        if (isBombPlanted) {
            long bombTimer = (long) getDouble(latestGameState, "bombTimer");
            long currentTime = System.currentTimeMillis();

            // 根据剩余时间决定嘀嘀声的间隔
            long beepInterval = (bombTimer <= 10000) ? 400 : 1000; // 最后10秒间隔变短

            if (currentTime - lastC4BeepTime > beepInterval) {
                // 根据剩余时间选择不同的音效文件
                String beepSound = (bombTimer <= 10000)
                        ? (rand.nextBoolean() ? "weapons/c4/c4_beep2_10sec" : "weapons/c4/c4_beep3_10sec")
                        : (rand.nextBoolean() ? "weapons/c4/c4_beep2" : "weapons/c4/c4_beep3");

                // 从炸弹位置播放声音
                JsonObject bombPosJson = latestGameState.getAsJsonObject("bombPosition");
                if (bombPosJson != null) {
                    Point2D bombPos = new Point2D(getDouble(bombPosJson, "x"), getDouble(bombPosJson, "y"));
                    playSound(beepSound, bombPos);
                }

                lastC4BeepTime = currentTime; // 更新播放时间
            }
        }

        wasBombPlantedLastFrame = isBombPlanted; // 更新上一帧的状态
    }

    /**
     * 相机类，用于管理游戏世界的视野、缩放和坐标转换
     */
    private static class Camera {
        double x = 0, y = 0, scale = 1.0, offsetX = 0, offsetY = 0;
        double targetX = 0, targetY = 0;
        double currentZoomFactor = 1.0; // [新增] 用于平滑缩放动画

        void follow(double targetX, double targetY, double mapWidth, double mapHeight, double zoomFactor,
                    double deltaTime) {
            // 1. 平滑插值 zoomFactor
            double zoomLerp = 0.15;
            double actualZoomLerp = 1.0 - Math.pow(1.0 - zoomLerp, deltaTime * 60.0);
            currentZoomFactor += (zoomFactor - currentZoomFactor) * actualZoomLerp;

            this.scale = 1.0 / currentZoomFactor;
            this.offsetX = 0;
            this.offsetY = 0;

            this.targetX = targetX;
            this.targetY = targetY;

            // 2. 使用插值后的 currentZoomFactor 计算视口
            double virtualWidth = CANVAS_WIDTH * currentZoomFactor;
            double virtualHeight = CANVAS_HEIGHT * currentZoomFactor;
            double targetCameraX = targetX - virtualWidth / 2.0;
            double targetCameraY = targetY - virtualHeight / 2.0;

            // 3. 平滑跟随位置
            double baseLerp = 0.1;
            double actualLerp = 1.0 - Math.pow(1.0 - baseLerp, deltaTime * 60.0);
            x += (targetCameraX - x) * actualLerp;
            y += (targetCameraY - y) * actualLerp;

            // 4. 边界约束
            x = Math.max(0, Math.min(x, mapWidth - virtualWidth));
            y = Math.max(0, Math.min(y, mapHeight - virtualHeight));
        }

        void applyTransform(GraphicsContext gc) {
            // 采用模式无关的通用变换公式：
            // 结果应等同于：screenPos = (worldPos - x) * scale + offsetX
            gc.translate(offsetX, offsetY);
            gc.scale(scale, scale);
            gc.translate(-x, -y);
        }

        // freeMove 和 fullView 方法
        void freeMove() {
            this.scale = 1.0;
            this.currentZoomFactor = 1.0; // 同步重置系数
            this.offsetX = 0;
            this.offsetY = 0;
        }

        void fullView(double mapWidth, double mapHeight) {
            x = 0;
            y = 0;
            scale = Math.min(CANVAS_WIDTH / mapWidth, CANVAS_HEIGHT / mapHeight);
            currentZoomFactor = 1.0 / scale; // 同步缩放系数，防止切回 follow 时画面跳变
            offsetX = (CANVAS_WIDTH - mapWidth * scale) / 2.0;
            offsetY = (CANVAS_HEIGHT - mapHeight * scale) / 2.0;
        }

        Point2D screenToWorld(double screenX, double screenY) {
            // 需要处理 zoomFactor 小于 0 的情况吗？或者在 setting 中限制？ 已在 setting 中限制
            if (scale == 0)
                return new Point2D(x, y); // 防止除零
            return new Point2D((screenX - offsetX) / scale + x, (screenY - offsetY) / scale + y);
        }

        Point2D worldToScreen(double worldX, double worldY) {
            return new Point2D((worldX - x) * scale + offsetX, (worldY - y) * scale + offsetY);
        }
    }

    /**
     * 客户端本地玩家实体类，用于平滑渲染和状态存储
     */
    private static class ClientPlayer {
        String id;
        volatile double renderX, renderY;
        volatile double targetX, targetY;
        volatile double vx, vy;
        volatile double angle; // 当前渲染使用的角度
        volatile double targetAngle; // 服务器发来的目标角度
        volatile double health, predictedRecoilAngle;
        volatile boolean isShooting, isReloading;
        volatile JsonObject data;
        private boolean isInitialized = false;
        // 用于检测换弹状态是否刚开始
        private boolean wasReloadingLastFrame = false;
        private final cs2d.client.GameClient clientInstance;
        private long soundRevealExpireTime = 0;

        public Point2D getPos() { // 获取 xx 的位置
            return new Point2D(renderX, renderY);
        }

        ClientPlayer(JsonObject data, cs2d.client.GameClient clientInstance) { // <--- 增加参数
            this.id = getString(data, "id");
            this.clientInstance = clientInstance; // <--- 接收引用
            updateFull(data);
        }

        /** 记录玩家是被哪种声音暴露的 ("FOOTSTEP", "FIRE", "RELOAD") */
        public String revealSoundType = null;

        /**
         * 添加 synchronized 关键字，确保线程安全
         */
        synchronized void updateFull(JsonObject data) {
            this.data = data.deepCopy();
            this.targetX = getDouble(data, "x");
            this.targetY = getDouble(data, "y");
            this.vx = getDouble(data, "vx");
            this.vy = getDouble(data, "vy");

            if (!isInitialized) {
                this.renderX = this.targetX;
                this.renderY = this.targetY;
                this.angle = getDouble(data, "angle");
                this.targetAngle = this.angle;
                isInitialized = true;
            }
            updateDynamic(data);
        }

        /**
         * [修改] 添加 synchronized 关键字，确保线程安全
         */
        synchronized void updateDynamic(JsonObject data) {

            boolean packetHasX = data.has("x") && data.get("x").isJsonPrimitive()
                    && data.getAsJsonPrimitive("x").isNumber();
            boolean packetHasY = data.has("y") && data.get("y").isJsonPrimitive()
                    && data.getAsJsonPrimitive("y").isNumber();

            // 发布新的完整 JsonObject，渲染线程不会观察到合并一半的状态。
            if (this.data != data) {
                JsonObject mergedData = this.data == null ? new JsonObject() : this.data.deepCopy();
                for (Map.Entry<String, JsonElement> entry : data.entrySet()) {
                    String key = entry.getKey();
                    JsonElement value = entry.getValue();

                    // [防护机制] 如果服务器发来的是 null 或空数据，不要覆盖本地已有的统计数值
                    // 统计字段包括：kills, deaths, score, damageDealt, totalHeadshots, totalShotsFired,
                    // totalShotsHit
                    if (isStatKey(key) && (value == null || value.isJsonNull())) {
                        continue;
                    }

                    mergedData.add(key, value.deepCopy());
                }
                this.data = mergedData;
            }
            JsonObject currentData = this.data;
            // 立即同步关键变量
            // small_update 不带坐标；不能用合并对象中的旧 x/y 反复覆盖预测目标。
            if (packetHasX) {
                double incomingX = getDouble(data, "x");
                if (Double.isFinite(incomingX))
                    this.targetX = incomingX;
            }
            if (packetHasY) {
                double incomingY = getDouble(data, "y");
                if (Double.isFinite(incomingY))
                    this.targetY = incomingY;
            }
            this.vx = getDouble(currentData, "vx");
            this.vy = getDouble(currentData, "vy");
            this.health = getDouble(currentData, "health");
            this.isShooting = getBool(currentData, "isShooting");
            boolean currentIsReloading = getBool(currentData, "isReloading");

            // [修复] 处理角度同步。
            double serverAngle = getDouble(currentData, "angle");
            if (clientInstance.myPlayerId != null && clientInstance.myPlayerId.equals(this.id)) {
                this.targetAngle = serverAngle;
            } else {
                this.targetAngle = serverAngle;
            }
            if (currentIsReloading && !this.wasReloadingLastFrame) {
                String weaponKey = getString(currentData, "weaponKey");
                if (weaponKey != null && !weaponKey.isEmpty()) {
                    String reloadSoundKey = weaponKey + "_reload";
                    Point2D playerPos = new Point2D(getDouble(currentData, "x"), getDouble(currentData, "y"));
                    Platform.runLater(() -> {
                        this.clientInstance.playSound(reloadSoundKey, playerPos);
                    });
                }
            }
            this.isReloading = currentIsReloading;
            this.wasReloadingLastFrame = currentIsReloading;

            this.predictedRecoilAngle = getDouble(currentData, "predictedRecoilAngle");
        }

        synchronized void mutateData(Consumer<JsonObject> mutation) {
            JsonObject updated = data == null ? new JsonObject() : data.deepCopy();
            mutation.accept(updated);
            data = updated;
        }

        synchronized void recomputeScore() {
            JsonObject updated = data == null ? new JsonObject() : data.deepCopy();
            int score = getInt(updated, "damageDealt")
                    + getInt(updated, "kills") * 50
                    + getInt(updated, "totalHeadshots") * 20
                    - getInt(updated, "deaths") * 50;
            updated.addProperty("score", score);
            data = updated;
        }

        synchronized void updateRenderPosition(double deltaTime) {
            if (health > 0) {
                // [修复] 将服务器的速度 (每tick位移) 转换为每秒位移，再乘以实际帧间隔 deltaTime
                double speedMultiplier = deltaTime * LEGACY_PHYSICS_RATE;
                this.targetX += this.vx * speedMultiplier;
                this.targetY += this.vy * speedMultiplier;

                // [修复] 时间无关的平滑插值 (Time-Independent Lerp)
                // 确保在 60Hz, 144Hz 或更高帧率下，平滑效果看起来完全一致。
                // 公式：actualFactor = 1 - (1 - baseFactor)^(deltaTime * 60)
                double baseLerp = 0.25; // 60Hz 时的标准平滑度
                double actualLerp = 1.0 - Math.pow(1.0 - baseLerp, deltaTime * 60.0);

                this.renderX += (this.targetX - this.renderX) * actualLerp;
                this.renderY += (this.targetY - this.renderY) * actualLerp;

                // [修复] 角度平滑插值 (Shortest Path Lerp)
                double angleDiff = this.targetAngle - this.angle;
                while (angleDiff <= -Math.PI)
                    angleDiff += 2 * Math.PI;
                while (angleDiff > Math.PI)
                    angleDiff -= 2 * Math.PI;

                double angleLerpFactor;
                if (clientInstance.myPlayerId != null && clientInstance.myPlayerId.equals(this.id)) {
                    angleLerpFactor = 0.05; // 本地玩家极慢微调
                } else {
                    angleLerpFactor = 0.2; // 其他玩家
                }
                double actualAngleLerp = 1.0 - Math.pow(1.0 - angleLerpFactor, deltaTime * 60.0);
                this.angle += angleDiff * actualAngleLerp;
            }
        }
    }

    private void updateCamera(double deltaTime) {
        if (mapData == null)
            return;
        double mapWidth = getDouble(mapData, "width");
        double mapHeight = getDouble(mapData, "height");
        final double FREE_CAM_SPEED = 10.0;

        cs2d.client.GameClient.ClientPlayer cameraTarget = null;
        boolean isAlive = me != null && getBool(me.data, "isAlive");

        // --- 获取当前的缩放系数 ---
        double currentZoomFactor = gameSettings.getFollowZoomFactor(); // 从设置获取

        if ("follow".equals(cameraMode)) {
            if (isAlive) {
                cameraTarget = me;
            } else {
                cameraTarget = getSpectatorTarget();
            }
        }

        // --- 应用相机变换 ---
        if ("follow".equals(cameraMode) && cameraTarget != null) {
            // [修复] 传入 deltaTime 确保平滑度时间无关
            camera.follow(cameraTarget.renderX, cameraTarget.renderY, mapWidth, mapHeight, currentZoomFactor,
                    deltaTime);
        } else if ("full".equals(cameraMode)) {
            camera.fullView(mapWidth, mapHeight);
        } else if ("free".equals(cameraMode)) {
            // 自由移动逻辑 (保持不变)
            double dx = 0, dy = 0;
            if (keysDown.contains(KeyCode.A))
                dx -= 1;
            if (keysDown.contains(KeyCode.D))
                dx += 1;
            if (keysDown.contains(KeyCode.W))
                dy -= 1;
            if (keysDown.contains(KeyCode.S))
                dy += 1;

            if (dx != 0 || dy != 0) {
                // 注意：自由移动的速度不受 zoomFactor 影响
                // 计算移动距离时，需要考虑当前的相机 scale (反向应用 zoom)
                double effectiveSpeed = FREE_CAM_SPEED / camera.scale; // camera.scale 是 1/zoomFactor
                // ... (标准化 dx, dy) ...
                if (dx != 0 || dy != 0) {
                    double length = Math.sqrt(dx * dx + dy * dy);
                    if (length > 0) {
                        dx /= length;
                        dy /= length;
                    }
                }
                // 计算缩放后的虚拟画布尺寸，用于边界检查
                // double virtualWidthFree = CANVAS_WIDTH / camera.scale; // 使用 camera.scale
                // double virtualHeightFree = CANVAS_HEIGHT / camera.scale; // 使用 camera.scale
                double virtualWidthFree = CANVAS_WIDTH * (1.0 / camera.scale); // 使用 camera.scale 的倒数，即 zoomFactor
                double virtualHeightFree = CANVAS_HEIGHT * (1.0 / camera.scale);

                camera.x = Math.max(0, Math.min(camera.x + dx * effectiveSpeed, mapWidth - virtualWidthFree));
                camera.y = Math.max(0, Math.min(camera.y + dy * effectiveSpeed, mapHeight - virtualHeightFree));
            }
            camera.freeMove(); // freeMove 会重置 scale 为 1.0，所以上面边界检查要用实时的 scale

        } else {
            cameraMode = "follow"; // 默认回到 follow
            // 如果默认回到 follow，也需要应用缩放
            if (me != null) { // 确保 me 不是 null
                camera.follow(me.renderX, me.renderY, mapWidth, mapHeight, currentZoomFactor, deltaTime);
            } else { // 如果 me 是 null (例如初始观战)，可能需要一个默认位置
                camera.follow(mapWidth / 2, mapHeight / 2, mapWidth, mapHeight, currentZoomFactor, deltaTime); // 居中显示
            }
        }
    }

    /**
     * 真正执行 FOV 计算的方法。
     * 此方法将在 fovExecutor (后台线程) 上被调用。
     *
     * @param sourcePos        视野源位置
     * @param sourceAngle      视野源角度
     * @param dynamicObstacles 动态障碍物 (如烟雾)
     */
    private FovComputationResult computeFov(FovRequest request) {
        long totalStartTime = System.nanoTime();
        Point2D sourcePos = request.sourcePos();
        double sourceAngle = request.sourceAngle();
        List<JsonObject> dynamicObstacles = request.dynamicObstacles();

        // --- 计时器（用于日志） ---
        long frameQueryTime = 0;
        long frameEdgeExtractTime = 0;
        long frameIntersectionTime = 0;

        long extractStartTime = System.nanoTime();
        int dynamicEdgeCoordinateCount = prepareDynamicFovEdges(dynamicObstacles);
        frameEdgeExtractTime += (System.nanoTime() - extractStartTime);

        // [性能修复] 1. 一次性查询 FOV 区域的所有静态障碍物
        long queryStartTime = System.nanoTime();
        fovCandidateObstacles.clear();
        if (quadtreeRootNode != null) {
            Rectangle2D fovBounds = new Rectangle2D(
                    sourcePos.getX() - FOV_RAY_LENGTH, sourcePos.getY() - FOV_RAY_LENGTH,
                    FOV_RAY_LENGTH * 2, FOV_RAY_LENGTH * 2);
            quadtreeRootNode.queryBounds(fovCandidateObstacles, fovBounds);
        }
        // 查询结果可能覆盖全图；一次性计算每个障碍物可能覆盖的离散射线范围并缓存。
        // 旧路径先做视锥判断，求交前又重复计算同一组中心/距离/角半径数据。
        ensureFovCandidateRangeCapacity(fovCandidateObstacles.size());
        int retainedCandidates = 0;
        for (int i = 0; i < fovCandidateObstacles.size(); i++) {
            cs2d.client.GameClient.StaticObstacle obstacle = fovCandidateObstacles.get(i);
            long rayRange = fovRayIndexRange(obstacle, sourcePos.getX(), sourcePos.getY(), sourceAngle,
                    FOV_RADIANS / 2.0, FOV_ANGLE_STEP, FOV_RAY_COUNT, FOV_RAY_LENGTH);
            if (rayRange >= 0) {
                fovCandidateObstacles.set(retainedCandidates, obstacle);
                fovCandidateRayRanges[retainedCandidates] = rayRange;
                retainedCandidates++;
            }
        }
        if (retainedCandidates < fovCandidateObstacles.size())
            fovCandidateObstacles.subList(retainedCandidates, fovCandidateObstacles.size()).clear();
        frameQueryTime += (System.nanoTime() - queryStartTime);

        long intersectStartTime = System.nanoTime();
        final double sourceX = sourcePos.getX();
        final double sourceY = sourcePos.getY();
        populateFovRayDirections(sourceAngle, fovRayDirectionsX, fovRayDirectionsY);
        Arrays.fill(fovClosestDistances, FOV_RAY_LENGTH);

        // 每个障碍物只检查其包围圆可能覆盖的连续射线区间。旧实现让每条射线检查
        // 所有候选障碍物；本实现仅跳过数学上不可能命中的射线，最终交点算法不变。
        for (int obstacleIndex = 0; obstacleIndex < fovCandidateObstacles.size(); obstacleIndex++) {
            cs2d.client.GameClient.StaticObstacle obs = fovCandidateObstacles.get(obstacleIndex);
            long rayRange = fovCandidateRayRanges[obstacleIndex];
            int firstRay = (int) (rayRange >>> 32);
            int lastRay = (int) rayRange;
            double[] edges = obs.edgeCoordinates;
            for (int rayIndex = firstRay; rayIndex <= lastRay; rayIndex++) {
                for (int edgeIndex = 0; edgeIndex < edges.length; edgeIndex += 4) {
                    double hitDistance = raySegmentIntersectionDistance(sourceX, sourceY,
                            fovRayDirectionsX[rayIndex], fovRayDirectionsY[rayIndex],
                            fovClosestDistances[rayIndex],
                            edges[edgeIndex], edges[edgeIndex + 1], edges[edgeIndex + 2], edges[edgeIndex + 3]);
                    if (hitDistance < fovClosestDistances[rayIndex])
                        fovClosestDistances[rayIndex] = hitDistance;
                }
            }
        }

        for (int i = 0; i < FOV_RAY_COUNT; i++) {
            for (int edgeIndex = 0; edgeIndex < dynamicEdgeCoordinateCount; edgeIndex += 4) {
                double hitDistance = raySegmentIntersectionDistance(sourceX, sourceY,
                        fovRayDirectionsX[i], fovRayDirectionsY[i], fovClosestDistances[i],
                        dynamicFovEdgeCoordinates[edgeIndex], dynamicFovEdgeCoordinates[edgeIndex + 1],
                        dynamicFovEdgeCoordinates[edgeIndex + 2], dynamicFovEdgeCoordinates[edgeIndex + 3]);
                if (hitDistance < fovClosestDistances[i])
                    fovClosestDistances[i] = hitDistance;
            }
        } // 结束射线循环

        frameIntersectionTime += (System.nanoTime() - intersectStartTime);
        int rawVertexCount = FOV_RAY_COUNT + 1;

        // 直接从基础数组生成最终保留顶点，避免先创建1697个Point2D再丢弃绝大多数。
        List<Point2D> newFovPolygon = simplifyFovRays(sourcePos, fovRayDirectionsX,
                fovRayDirectionsY, fovClosestDistances, FOV_SIMPLIFY_ANGLE_TOLERANCE);

        long totalNanos = System.nanoTime() - totalStartTime;
        return new FovComputationResult(Collections.unmodifiableList(newFovPolygon), totalNanos,
                frameQueryTime, frameEdgeExtractTime, frameIntersectionTime,
                fovCandidateObstacles.size(), rawVertexCount, newFovPolygon.size());
    }

    private void ensureFovCandidateRangeCapacity(int requiredCapacity) {
        if (fovCandidateRayRanges.length >= requiredCapacity)
            return;
        int expandedCapacity = Math.max(requiredCapacity, Math.max(64, fovCandidateRayRanges.length * 2));
        fovCandidateRayRanges = new long[expandedCapacity];
    }

    /**
     * 用覆盖障碍物包围盒的圆做保守视锥判断。返回false时障碍物一定不可能命中FOV射线；
     * 返回true可能是假阳性，但绝不会牺牲遮挡精度。
     */
    static boolean obstacleMayIntersectFov(Rectangle2D bounds, double sourceX, double sourceY,
            double sourceAngle, double halfFovRadians, double rayLength) {
        if (bounds == null)
            return false;
        double centerX = (bounds.getMinX() + bounds.getMaxX()) * 0.5;
        double centerY = (bounds.getMinY() + bounds.getMaxY()) * 0.5;
        double radius = Math.hypot(bounds.getWidth(), bounds.getHeight()) * 0.5;
        double dx = centerX - sourceX;
        double dy = centerY - sourceY;
        double distance = Math.hypot(dx, dy);

        if (distance <= radius)
            return true; // 视点在包围圆内，障碍物可能覆盖任意射线方向
        if (distance - radius > rayLength)
            return false;

        double relativeAngle = normalizeAngle(Math.atan2(dy, dx) - sourceAngle);
        double angularRadius = Math.asin(Math.min(1.0, radius / distance));
        return Math.abs(relativeAngle) <= halfFovRadians + angularRadius;
    }

    /** 返回障碍物包围圆可能覆盖的首尾射线索引，未覆盖任何离散射线时返回-1。 */
    static long fovRayIndexRange(Rectangle2D bounds, double sourceX, double sourceY,
            double sourceAngle, double halfFovRadians, double angleStep, int rayCount, double rayLength) {
        if (bounds == null || rayCount <= 0 || angleStep <= 0)
            return -1L;
        double centerX = (bounds.getMinX() + bounds.getMaxX()) * 0.5;
        double centerY = (bounds.getMinY() + bounds.getMaxY()) * 0.5;
        double radius = Math.hypot(bounds.getWidth(), bounds.getHeight()) * 0.5;
        return fovRayIndexRange(centerX, centerY, radius, sourceX, sourceY,
                sourceAngle, halfFovRadians, angleStep, rayCount, rayLength);
    }

    private static long fovRayIndexRange(StaticObstacle obstacle, double sourceX, double sourceY,
            double sourceAngle, double halfFovRadians, double angleStep, int rayCount, double rayLength) {
        if (obstacle == null || rayCount <= 0 || angleStep <= 0)
            return -1L;
        return fovRayIndexRange(obstacle.centerX, obstacle.centerY, obstacle.boundingRadius,
                sourceX, sourceY, sourceAngle, halfFovRadians, angleStep, rayCount, rayLength);
    }

    private static long fovRayIndexRange(double centerX, double centerY, double radius,
            double sourceX, double sourceY, double sourceAngle, double halfFovRadians,
            double angleStep, int rayCount, double rayLength) {
        double dx = centerX - sourceX;
        double dy = centerY - sourceY;
        double distanceSquared = dx * dx + dy * dy;
        double maximumCenterDistance = rayLength + radius;
        if (distanceSquared > maximumCenterDistance * maximumCenterDistance)
            return -1L;
        if (distanceSquared <= radius * radius)
            return packRayRange(0, rayCount - 1);

        double distance = Math.sqrt(distanceSquared);
        double relativeAngle = normalizeAngle(Math.atan2(dy, dx) - sourceAngle);
        double angularRadius = Math.asin(Math.min(1.0, radius / distance));
        double minimumAngle = Math.max(-halfFovRadians, relativeAngle - angularRadius);
        double maximumAngle = Math.min(halfFovRadians, relativeAngle + angularRadius);
        if (minimumAngle > maximumAngle)
            return -1L;

        int first = Math.max(0, (int) Math.ceil((minimumAngle + halfFovRadians) / angleStep - 1.0e-12));
        int last = Math.min(rayCount - 1,
                (int) Math.floor((maximumAngle + halfFovRadians) / angleStep + 1.0e-12));
        return first <= last ? packRayRange(first, last) : -1L;
    }

    private static long packRayRange(int first, int last) {
        return ((long) first << 32) | (last & 0xffffffffL);
    }

    static double normalizeAngle(double angle) {
        // atan2与玩家朝向之差在正常协议范围内只需一次修正；异常大角度才走低频remainder回退。
        if (angle <= -Math.PI) {
            angle += TWO_PI;
            if (angle <= -Math.PI)
                angle = Math.IEEEremainder(angle, TWO_PI);
        } else if (angle > Math.PI) {
            angle -= TWO_PI;
            if (angle > Math.PI)
                angle = Math.IEEEremainder(angle, TWO_PI);
        }
        return angle;
    }

    private static double[] createFovRelativeDirections(boolean sine) {
        double[] directions = new double[FOV_RAY_COUNT];
        double firstAngle = -FOV_RADIANS * 0.5;
        for (int i = 0; i < directions.length; i++) {
            double angle = firstAngle + i * FOV_ANGLE_STEP;
            directions[i] = sine ? Math.sin(angle) : Math.cos(angle);
        }
        return directions;
    }

    static void populateFovRayDirections(double sourceAngle, double[] directionsX, double[] directionsY) {
        int count = Math.min(FOV_RAY_COUNT, Math.min(directionsX.length, directionsY.length));
        double sourceCosine = Math.cos(sourceAngle);
        double sourceSine = Math.sin(sourceAngle);
        for (int i = 0; i < count; i++) {
            double relativeCosine = FOV_RELATIVE_COSINES[i];
            double relativeSine = FOV_RELATIVE_SINES[i];
            directionsX[i] = sourceCosine * relativeCosine - sourceSine * relativeSine;
            directionsY[i] = sourceSine * relativeCosine + sourceCosine * relativeSine;
        }
    }

    private static double[] createUnitCircleCoordinates(boolean sine) {
        double[] coordinates = new double[DYNAMIC_ELLIPSE_SEGMENTS];
        for (int i = 0; i < coordinates.length; i++) {
            double angle = (i / (double) DYNAMIC_ELLIPSE_SEGMENTS) * Math.PI * 2.0;
            coordinates[i] = sine ? Math.sin(angle) : Math.cos(angle);
        }
        return coordinates;
    }

    /** 将动态障碍直接写入worker复用的基础数组，返回有效坐标数量。 */
    private int prepareDynamicFovEdges(List<JsonObject> obstacles) {
        int edgeCount = 0;
        for (JsonObject obstacle : obstacles) {
            String type = getString(obstacle, "type");
            if ("RECTANGLE".equals(type)) {
                edgeCount += 4;
            } else if ("ELLIPSE".equals(type)) {
                edgeCount += DYNAMIC_ELLIPSE_SEGMENTS;
            } else if ("POLYGON".equals(type) && obstacle.has("xPoints") && obstacle.has("yPoints")) {
                JsonArray xPoints = obstacle.getAsJsonArray("xPoints");
                JsonArray yPoints = obstacle.getAsJsonArray("yPoints");
                if (xPoints != null && yPoints != null && xPoints.size() > 1 && xPoints.size() == yPoints.size())
                    edgeCount += xPoints.size();
            }
        }

        int requiredCoordinates = edgeCount * 4;
        if (dynamicFovEdgeCoordinates.length < requiredCoordinates) {
            int expandedLength = Math.max(requiredCoordinates, Math.max(64, dynamicFovEdgeCoordinates.length * 2));
            dynamicFovEdgeCoordinates = new double[expandedLength];
        }

        int cursor = 0;
        for (JsonObject obstacle : obstacles) {
            String type = getString(obstacle, "type");
            double x = getDouble(obstacle, "x");
            double y = getDouble(obstacle, "y");
            double width = getDouble(obstacle, "w");
            double height = getDouble(obstacle, "h");
            if ("RECTANGLE".equals(type)) {
                cursor = writeEdge(dynamicFovEdgeCoordinates, cursor, x, y, x + width, y);
                cursor = writeEdge(dynamicFovEdgeCoordinates, cursor, x + width, y, x + width, y + height);
                cursor = writeEdge(dynamicFovEdgeCoordinates, cursor, x + width, y + height, x, y + height);
                cursor = writeEdge(dynamicFovEdgeCoordinates, cursor, x, y + height, x, y);
            } else if ("POLYGON".equals(type) && obstacle.has("xPoints") && obstacle.has("yPoints")) {
                JsonArray xPoints = obstacle.getAsJsonArray("xPoints");
                JsonArray yPoints = obstacle.getAsJsonArray("yPoints");
                if (xPoints != null && yPoints != null && xPoints.size() > 1 && xPoints.size() == yPoints.size()) {
                    for (int i = 0; i < xPoints.size(); i++) {
                        int next = (i + 1) % xPoints.size();
                        cursor = writeEdge(dynamicFovEdgeCoordinates, cursor,
                                xPoints.get(i).getAsDouble(), yPoints.get(i).getAsDouble(),
                                xPoints.get(next).getAsDouble(), yPoints.get(next).getAsDouble());
                    }
                }
            } else if ("ELLIPSE".equals(type)) {
                double centerX = x + width * 0.5;
                double centerY = y + height * 0.5;
                double radiusX = width * 0.5;
                double radiusY = height * 0.5;
                for (int i = 0; i < DYNAMIC_ELLIPSE_SEGMENTS; i++) {
                    int next = (i + 1) % DYNAMIC_ELLIPSE_SEGMENTS;
                    cursor = writeEdge(dynamicFovEdgeCoordinates, cursor,
                            centerX + radiusX * DYNAMIC_ELLIPSE_UNIT_X[i],
                            centerY + radiusY * DYNAMIC_ELLIPSE_UNIT_Y[i],
                            centerX + radiusX * DYNAMIC_ELLIPSE_UNIT_X[next],
                            centerY + radiusY * DYNAMIC_ELLIPSE_UNIT_Y[next]);
                }
            }
        }
        return cursor;
    }

    private static int writeEdge(double[] coordinates, int cursor,
            double startX, double startY, double endX, double endY) {
        coordinates[cursor++] = startX;
        coordinates[cursor++] = startY;
        coordinates[cursor++] = endX;
        coordinates[cursor++] = endY;
        return cursor;
    }

    private static double[] flattenEdgeCoordinates(List<Point2D[]> edges) {
        double[] coordinates = new double[edges.size() * 4];
        int index = 0;
        for (Point2D[] edge : edges) {
            coordinates[index++] = edge[0].getX();
            coordinates[index++] = edge[0].getY();
            coordinates[index++] = edge[1].getX();
            coordinates[index++] = edge[1].getY();
        }
        return coordinates;
    }

    /** 返回射线起点到交点的距离；不相交时返回正无穷。 */
    static double raySegmentIntersectionDistance(double originX, double originY,
            double directionX, double directionY, double maxDistance,
            double segmentStartX, double segmentStartY, double segmentEndX, double segmentEndY) {
        double segmentX = segmentEndX - segmentStartX;
        double segmentY = segmentEndY - segmentStartY;
        double denominator = directionX * segmentY - directionY * segmentX;
        if (denominator == 0.0)
            return Double.POSITIVE_INFINITY;

        double offsetX = segmentStartX - originX;
        double offsetY = segmentStartY - originY;
        double rayDistance = (offsetX * segmentY - offsetY * segmentX) / denominator;
        double segmentParameter = (offsetX * directionY - offsetY * directionX) / denominator;
        // 与旧算法保持一致：端点接触不算穿过，且必须落在有限射线内部。
        if (rayDistance > 0.0 && rayDistance < maxDistance
                && segmentParameter > 0.0 && segmentParameter < 1.0) {
            return rayDistance;
        }
        return Double.POSITIVE_INFINITY;
    }

    /** 与旧轮廓抽稀判定等价，但只为最终保留点创建Point2D。 */
    static List<Point2D> simplifyFovRays(Point2D source, double[] directionsX,
            double[] directionsY, double[] distances, double angleTolerance) {
        int rayCount = Math.min(directionsX.length, Math.min(directionsY.length, distances.length));
        List<Point2D> simplified = new ArrayList<>(Math.min(rayCount + 1, 128));
        simplified.add(source);
        if (rayCount == 0)
            return simplified;
        if (rayCount <= 2) {
            for (int i = 0; i < rayCount; i++)
                simplified.add(new Point2D(source.getX() + directionsX[i] * distances[i],
                        source.getY() + directionsY[i] * distances[i]));
            return simplified;
        }

        double previousX = source.getX();
        double previousY = source.getY();
        for (int rayIndex = 0; rayIndex < rayCount - 1; rayIndex++) {
            double currentX = source.getX() + directionsX[rayIndex] * distances[rayIndex];
            double currentY = source.getY() + directionsY[rayIndex] * distances[rayIndex];
            double nextX = source.getX() + directionsX[rayIndex + 1] * distances[rayIndex + 1];
            double nextY = source.getY() + directionsY[rayIndex + 1] * distances[rayIndex + 1];
            double incomingX = currentX - previousX;
            double incomingY = currentY - previousY;
            if (Math.abs(incomingX) < 0.1 && Math.abs(incomingY) < 0.1)
                continue;

            double outgoingX = nextX - currentX;
            double outgoingY = nextY - currentY;
            double cross = incomingX * outgoingY - incomingY * outgoingX;
            double dot = incomingX * outgoingX + incomingY * outgoingY;
            double directionChange = Math.abs(Math.atan2(cross, dot));
            if (directionChange > angleTolerance) {
                simplified.add(new Point2D(currentX, currentY));
                previousX = currentX;
                previousY = currentY;
            }
        }

        int lastRay = rayCount - 1;
        simplified.add(new Point2D(source.getX() + directionsX[lastRay] * distances[lastRay],
                source.getY() + directionsY[lastRay] * distances[lastRay]));
        return simplified;
    }

    // 确保这个辅助方法存在并且正确
    private List<Point2D[]> extractEdgesFromObstacle(JsonObject obs) {
        List<Point2D[]> lines = new ArrayList<>();
        String type = getString(obs, "type");
        double x = getDouble(obs, "x"), y = getDouble(obs, "y"), w = getDouble(obs, "w"), h = getDouble(obs, "h");
        if ("RECTANGLE".equals(type)) {
            Point2D p1 = new Point2D(x, y);
            Point2D p2 = new Point2D(x + w, y);
            Point2D p3 = new Point2D(x + w, y + h);
            Point2D p4 = new Point2D(x, y + h);
            lines.add(new Point2D[] { p1, p2 });
            lines.add(new Point2D[] { p2, p3 });
            lines.add(new Point2D[] { p3, p4 });
            lines.add(new Point2D[] { p4, p1 });
        } else if ("POLYGON".equals(type) && obs.has("xPoints")) {
            JsonArray xP = obs.getAsJsonArray("xPoints");
            JsonArray yP = obs.getAsJsonArray("yPoints");
            if (xP != null && yP != null && xP.size() > 1 && xP.size() == yP.size()) { // 至少需要 2 个点
                for (int i = 0; i < xP.size(); i++) {
                    Point2D p1 = new Point2D(xP.get(i).getAsDouble(), yP.get(i).getAsDouble());
                    Point2D p2 = new Point2D(xP.get((i + 1) % xP.size()).getAsDouble(),
                            yP.get((i + 1) % yP.size()).getAsDouble());
                    lines.add(new Point2D[] { p1, p2 });
                }
            }
        } else if ("ELLIPSE".equals(type)) {
            final int numSegments = 16;
            final double centerX = x + w / 2, centerY = y + h / 2, radiusX = w / 2, radiusY = h / 2;
            for (int i = 0; i < numSegments; i++) {
                double angle1 = (i / (double) numSegments) * 2 * Math.PI;
                double angle2 = ((i + 1) / (double) numSegments) * 2 * Math.PI;
                Point2D p1 = new Point2D(centerX + radiusX * Math.cos(angle1), centerY + radiusY * Math.sin(angle1));
                Point2D p2 = new Point2D(centerX + radiusX * Math.cos(angle2), centerY + radiusY * Math.sin(angle2));
                lines.add(new Point2D[] { p1, p2 });
            }
        }
        return lines;
    }

    /**
     * 使用一条持久Path和可复用LineTo池承载迷雾轮廓。新的FOV快照只更新坐标，
     * 不再清空并重绘一张全屏Canvas；FOV未变化时只更新仿射变换。
     */
    private void updateCachedFogLayer(List<Point2D> currentFovPoints) {
        if (fogPath == null || currentFovPoints == null || currentFovPoints.isEmpty()) {
            clearCachedFogLayer();
            return;
        }

        Color fogColor = currentFogColor();
        boolean geometryChanged = currentFovPoints != rasterizedFogGeometry
                || !Objects.equals(fogColor, fogRasterColor)
                || !cachedFogTransformCoversViewport();
        if (geometryChanged) {
            updateFogPathGeometry(currentFovPoints, fogColor);
            perfFogRasterizations++;
        } else {
            perfFogTransformOnlyFrames++;
        }
        updateFogLayerTransform();
        setVisibleIfChanged(fogPath, true);
    }

    private void updateFogPathGeometry(List<Point2D> currentFovPoints, Color fogColor) {
        fogRasterCameraX = camera.x;
        fogRasterCameraY = camera.y;
        fogRasterScale = Math.max(camera.scale, 0.0001);
        fogRasterOffsetX = camera.offsetX;
        fogRasterOffsetY = camera.offsetY;

        int requiredLines = Math.max(0, currentFovPoints.size() - 1);
        while (fogVertexPool.size() < requiredLines)
            fogVertexPool.add(new LineTo());
        while (activeFogLineCount < requiredLines) {
            fogPath.getElements().add(fogPath.getElements().size() - 1,
                    fogVertexPool.get(activeFogLineCount));
            activeFogLineCount++;
        }
        while (activeFogLineCount > requiredLines) {
            fogPath.getElements().remove(5 + activeFogLineCount);
            activeFogLineCount--;
        }

        Point2D first = currentFovPoints.get(0);
        fogHoleStart.setX(fogScreenCoordinate(first.getX(), fogRasterCameraX, fogRasterScale, fogRasterOffsetX));
        fogHoleStart.setY(fogScreenCoordinate(first.getY(), fogRasterCameraY, fogRasterScale, fogRasterOffsetY));
        for (int i = 1; i < currentFovPoints.size(); i++) {
            Point2D point = currentFovPoints.get(i);
            LineTo line = fogVertexPool.get(i - 1);
            line.setX(fogScreenCoordinate(point.getX(), fogRasterCameraX, fogRasterScale, fogRasterOffsetX));
            line.setY(fogScreenCoordinate(point.getY(), fogRasterCameraY, fogRasterScale, fogRasterOffsetY));
        }
        if (!Objects.equals(fogColor, fogRasterColor))
            fogPath.setFill(fogColor);
        rasterizedFogGeometry = currentFovPoints;
        fogRasterColor = fogColor;
    }

    private static double fogScreenCoordinate(double worldCoordinate, double rasterCameraOrigin,
            double rasterScale, double rasterOffset) {
        return (worldCoordinate - rasterCameraOrigin) * rasterScale + rasterOffset + FOG_TEXTURE_MARGIN;
    }

    private void updateFogLayerTransform() {
        double currentScale = Math.max(camera.scale, 0.0001);
        double ratio = currentScale / fogRasterScale;
        double translateX = fogLayerTranslation(camera.x, currentScale, camera.offsetX,
                fogRasterCameraX, fogRasterScale, fogRasterOffsetX, FOG_TEXTURE_MARGIN);
        double translateY = fogLayerTranslation(camera.y, currentScale, camera.offsetY,
                fogRasterCameraY, fogRasterScale, fogRasterOffsetY, FOG_TEXTURE_MARGIN);
        fogLayerTransform.setToTransform(ratio, 0.0, translateX, 0.0, ratio, translateY);
    }

    private boolean cachedFogTransformCoversViewport() {
        if (rasterizedFogGeometry.isEmpty())
            return false;
        double currentScale = Math.max(camera.scale, 0.0001);
        double ratio = currentScale / fogRasterScale;
        double translateX = fogLayerTranslation(camera.x, currentScale, camera.offsetX,
                fogRasterCameraX, fogRasterScale, fogRasterOffsetX, FOG_TEXTURE_MARGIN);
        double translateY = fogLayerTranslation(camera.y, currentScale, camera.offsetY,
                fogRasterCameraY, fogRasterScale, fogRasterOffsetY, FOG_TEXTURE_MARGIN);
        return translateX <= 0.0 && translateY <= 0.0
                && translateX + (CANVAS_WIDTH + FOG_TEXTURE_MARGIN * 2.0) * ratio >= CANVAS_WIDTH
                && translateY + (CANVAS_HEIGHT + FOG_TEXTURE_MARGIN * 2.0) * ratio >= CANVAS_HEIGHT;
    }

    static double fogLayerTranslation(double currentCameraOrigin, double currentScale, double currentOffset,
            double rasterCameraOrigin, double rasterScale, double rasterOffset, double textureMargin) {
        double ratio = currentScale / rasterScale;
        double rasterIntercept = rasterOffset - rasterCameraOrigin * rasterScale;
        double currentIntercept = currentOffset - currentCameraOrigin * currentScale;
        return currentIntercept - ratio * rasterIntercept - ratio * textureMargin;
    }

    private void clearCachedFogLayer() {
        if (fogPath == null)
            return;
        if (fogPath.isVisible())
            fogPath.setVisible(false);
        rasterizedFogGeometry = List.of();
        fogRasterColor = null;
    }

    private Color currentFogColor() {
        return "follow".equals(cameraMode) ? FOLLOW_FOG_COLOR : GLOBAL_FOG_COLOR;
    }

    private int calculateLocalScoreForSorting(JsonObject p) {
        if (p == null)
            return 0;
        int dmg = getInt(p, "damageDealt");
        int k = getInt(p, "kills");
        int hs = getInt(p, "totalHeadshots");
        int d = getInt(p, "deaths");
        return dmg + (k * 50) + (hs * 20) - (d * 50);
    }

    private static boolean isStatKey(String key) {
        return "kills".equals(key) || "deaths".equals(key) || "score".equals(key) ||
                "damageDealt".equals(key) || "totalHeadshots".equals(key) ||
                "totalShotsFired".equals(key) || "totalShotsHit".equals(key);
    }

    private void updateLocalScore(cs2d.client.GameClient.ClientPlayer p) {
        if (p == null || p.data == null)
            return;
        p.recomputeScore();
    }

    private static void setTextIfChanged(Labeled label, String text) {
        if (!Objects.equals(label.getText(), text))
            label.setText(text);
    }

    private static void setStyleIfChanged(Node node, String style) {
        if (!Objects.equals(node.getStyle(), style))
            node.setStyle(style);
    }

    private static void setVisibleIfChanged(Node node, boolean visible) {
        if (node.isVisible() != visible)
            node.setVisible(visible);
    }

    private void updateHUDLabels(StackPane interactionBarContainer, Pane interactionProgressBar) {
        if (latestGameState == null || (clientState != cs2d.client.GameClient.ClientState.PLAYING
                && clientState != cs2d.client.GameClient.ClientState.GAME_OVER)) {
            // 全局隐藏逻辑
            setHUDVisibility(false);
            return;
        }
        setHUDVisibility(true);

        // --- 确定数据源 (核心修改点) ---
        String spectateMode = (me != null && me.data != null) ? getString(me.data, "spectatorMode") : "NONE";
        JsonObject sourceData = determineHUDDataSource(spectateMode);

        updateGlobalInfo(); // Ping, Mode
        updateTopCornerLabels(); // Time, Score, Round/Wave
        updateCenterLabels();
        // 只有在 PLAYING 或 GAME_OVER 且有数据源时，才处理个人HUD
        // 3. 更新个人状态 HUD (依赖 sourceData)
        if (sourceData != null) {
            boolean sourceIsAlive = getBool(sourceData, "isAlive");

            if (sourceIsAlive || "CONTROLLING_BOT".equals(spectateMode)) {
                // 存活或夺舍状态
                updatePlayerHUD(sourceData, spectateMode, interactionBarContainer, interactionProgressBar);
            } else if (me != null && !getBool(me.data, "isAlive")) {
                // 观战模式
                // 修正：传入 interactionBarContainer 和 interactionProgressBar
                updateSpectatorHUD(spectateMode, interactionBarContainer, interactionProgressBar);
            }
        } else {
            // Fallback for generic spectator or if me is null
            // 修正：传入 interactionBarContainer 和 interactionProgressBar
            updateGenericSpectatorHUD(interactionBarContainer, interactionProgressBar);
        }

        // C4 声音不受 HUD 状态影响，始终调用
        handleC4BeepSound();
    }

    /**
     * 确定 HUD 应该显示玩家自己的数据 (me.data) 还是被控制的 Bot 的数据。
     */
    private JsonObject determineHUDDataSource(String spectateMode) {
        if (me == null || me.data == null) {
            return null;
        }

        // 尝试从 me.data (即服务器发送的 JSON 数据) 中获取 Bot 的 ID。
        // 这解决了 me.spectatorTargetId 编译错误，并兼容了您的 JSON 架构。
        String targetBotId = getString(me.data, "spectatorTargetId");

        // 1. 检查是否正在夺舍人机
        if ("CONTROLLING_BOT".equals(spectateMode) && targetBotId != null) {
            // 假设 clientPlayers 是一个 Map<String, ClientPlayer>，存储了所有客户端可见的玩家和 Bot
            cs2d.client.GameClient.ClientPlayer controlledBot = clientPlayers.get(targetBotId);
            if (controlledBot != null) {
                // 将人机的数据复制到 me.data 上，确保 isInteracting 等状态同步，
                // 且 isAlive 同步是为了在 Bot 死亡时能正确进入观战逻辑。
                // 返回 Bot 的数据作为 HUD 的数据源
                return controlledBot.data;
            }
        }

        // 2. 否则，使用玩家自己的数据作为数据源
        return me.data;
    }

    private void updatePlayerHUD(JsonObject meData, String spectateMode, StackPane interactionBarContainer,
                                 Pane interactionProgressBar) {
        String mode = getString(latestGameState, "mode");
        boolean isDemo = "DEMOLITION".equals(mode);
        boolean isTDM = "TEAM_DEATHMATCH".equals(mode);
        String roundPhase = getString(latestGameState, "roundPhase");

        // 经济状态 (Loss Bonus, Money)
        lossBonusBox.setVisible(isDemo);
        moneyLabel.setVisible(isDemo);
        if (isDemo) {
            int consecutiveLosses = getInt(meData, "consecutiveLosses");
            for (int i = 0; i < lossBonusBars.size(); i++) {
                Region bar = lossBonusBars.get(i);
                String color = (i < consecutiveLosses) ? "#f56565;" : "#4a5568;";
                setStyleIfChanged(bar, "-fx-background-radius:3; -fx-background-color:" + color);
            }
            setTextIfChanged(moneyLabel, "$" + getInt(meData, "money"));
            moneyLabel.setTextFill(Color.GREEN);
        }

        // 杀敌数
        killsThisLifeContainer.setVisible(true);
        setTextIfChanged(killsThisLifeLabel, "KILL : " + killsThisLife);
        String myTeam = getString(meData, "team");
        if ("CT".equals(myTeam)) {
            killsThisLifeLabel.setTextFill(PRIMARY_BLUE);
        } else if ("T".equals(myTeam)) {
            killsThisLifeLabel.setTextFill(Color.web("#DE9B35"));
        } else {
            killsThisLifeLabel.setTextFill(Color.WHITE);
        }

        // HP, Armor, Weapon/Ammo, Equipment Icons
        updateStatusLabels(meData, spectateMode);
        updateEquipmentIcons(meData);

        // B 键/E 键提示逻辑 (核心修改：增加夺舍提示)
        if ("CONTROLLING_BOT".equals(spectateMode)) {
            // meData 此时是 BOT 的数据
            double hpVal = getDouble(meData, "health");
            // 同时显示夺舍状态和BOT的血量
            setTextIfChanged(hpLabel, "CONTROLLING: " + getString(meData, "name") + " [" + (int) hpVal + " HP]");
            hpLabel.setTextFill(hpVal > 50 ? Color.YELLOW : hpVal > 20 ? Color.ORANGE : Color.RED);

            // 强制显示 E 键释放提示 (取代 B 键提示)
            buyPrompt.setVisible(true);
            setTextIfChanged(buyPrompt, "[E] - Release Control");
            buyPrompt.setTextFill(Color.ORANGE);
            changeWeaponPrompt.setVisible(false);
        } else {
            // 恢复正常 HP 显示
            double hpVal = getDouble(meData, "health");
            setTextIfChanged(hpLabel, "Health: " + (int) hpVal);
            hpLabel.setTextFill(hpVal > 50 ? Color.LIGHTGREEN : hpVal > 20 ? Color.YELLOW : Color.RED);

            // B 键提示
            buyPrompt.setVisible(isDemo && "FREEZE_TIME".equals(roundPhase));
            setTextIfChanged(buyPrompt, "[B] - Open Buy Menu");
            buyPrompt.setTextFill(Color.GRAY);

            changeWeaponPrompt.setVisible(isTDM);
            setTextIfChanged(changeWeaponPrompt, "[B] - Change Weapon");
            changeWeaponPrompt.setTextFill(Color.GRAY);
        }

        if (isDemo && buyMenuPane.isVisible()) {
            // 只有当回合阶段不再是 FREEZE_TIME 时，才应该关闭购买菜单
            if (!"FREEZE_TIME".equals(roundPhase)) {
                Platform.runLater(() -> buyMenuPane.setVisible(false));
            }
        }

        // 交互进度条
        updateInteractionBar(meData, interactionBarContainer, interactionProgressBar);
    }

    private void updateStatusLabels(JsonObject meData, String spectateMode) {
        // HP Label 可见性
        hpLabel.setVisible(true);

        // Armor 逻辑
        int armorVal = getInt(meData, "armorValue");
        armorLabel.setVisible(armorVal > 0);
        if (armorVal > 0) {
            setTextIfChanged(armorLabel, "Armor: " + armorVal + (getBool(meData, "hasHelmet") ? " (H)" : ""));
            armorLabel.setTextFill(Color.CYAN);
        }

        // Weapon/Ammo 逻辑
        int currentSlot = getInt(meData, "currentSlot");
        String weaponOrItemNameKey = getString(meData, "weaponKey");
        String displayWeaponName = "";
        if (currentSlot >= 6 && ITEM_UI_DATA.containsKey(weaponOrItemNameKey)) {
            displayWeaponName = ITEM_UI_DATA.get(weaponOrItemNameKey).name();
        } else if (WEAPON_UI_DATA.containsKey(weaponOrItemNameKey)) {
            displayWeaponName = WEAPON_UI_DATA.get(weaponOrItemNameKey).name();
        } else {
            displayWeaponName = weaponOrItemNameKey;
        }
        setTextIfChanged(weaponLabel, displayWeaponName);
        weaponLabel.setTextFill(Color.LIGHTGRAY);
        weaponLabel.setVisible(true);

        if (currentSlot >= 6) {
            setTextIfChanged(ammoLabel, "");
        } else {
            String ammoText = "";
            if (getBool(meData, "isReloading")) {
                ammoText = "Reloading...";
            } else {
                int currentAmmo = getInt(meData, "currentAmmo");
                int reserveAmmo = getInt(meData, "reserveAmmo");
                String reserveStr;

                if (reserveAmmo >= 999) {
                    reserveStr = "∞";
                } else {
                    cs2d.client.GameClient.WeaponUIData data = WEAPON_UI_DATA.get(weaponOrItemNameKey);
                    if (data != null && !data.isIndividual() && data.magSize() > 0) {
                        // 弹匣化显示：将备弹总量除以弹匣容量得到弹匣个数
                        reserveStr = String.valueOf(reserveAmmo / data.magSize());
                    } else {
                        // 逐发装填武器或未知武器直接显示总数
                        reserveStr = String.valueOf(reserveAmmo);
                    }
                }
                ammoText = "Ammo: " + currentAmmo + " / " + reserveStr;
            }
            setTextIfChanged(ammoLabel, ammoText);
            ammoLabel.setTextFill(getBool(meData, "isReloading") ? Color.YELLOW : Color.WHITE);
        }
        ammoLabel.setVisible(true);
    }

    /**
     * 处理玩家死亡或处于正常观战模式时的 HUD 状态。
     */
    private void updateSpectatorHUD(String spectateMode, StackPane interactionBarContainer,
                                    Pane interactionProgressBar) {
        boolean isTDM = "TEAM_DEATHMATCH".equals(getString(latestGameState, "mode"));

        // 1. 人机死亡后，强制切回观战的提示 (me.data.isAlive 此时已为 false)
        if ("CONTROLLING_BOT".equals(spectateMode)) {
            setTextIfChanged(hpLabel, "Target Killed! Releasing Control...");
            hpLabel.setTextFill(Color.RED);
        }
        // 2. 正常观战模式
        else {
            String myTeam = getString(me.data, "team");
            List<cs2d.client.GameClient.ClientPlayer> livingTeammates = clientPlayers.values().stream()
                    .filter(p -> getBool(p.data, "isAlive") && myTeam.equals(getString(p.data, "team")))
                    .sorted(Comparator.comparing(p -> getString(p.data, "name")))
                    .collect(Collectors.toList());

            if (!livingTeammates.isEmpty()) {
                // 确保索引不会越界
                spectateTeammateIndex = spectateTeammateIndex % livingTeammates.size();
                cs2d.client.GameClient.ClientPlayer target = livingTeammates.get(spectateTeammateIndex);
                setTextIfChanged(hpLabel, "Spectating: " + getString(target.data, "name"));
                hpLabel.setTextFill(Color.YELLOW);
            } else {
                setTextIfChanged(hpLabel, "Dead");
                hpLabel.setTextFill(Color.RED);
            }
        }

        // 统一隐藏个人 HUD 元素
        hpLabel.setVisible(true);
        lossBonusBox.setVisible(false);
        moneyLabel.setVisible(false);
        armorLabel.setVisible(false);
        weaponLabel.setVisible(false);
        ammoLabel.setVisible(false);
        equipBox.setVisible(false);
        buyPrompt.setVisible(false);
        changeWeaponPrompt.setVisible(isTDM);
        // 修正：interactionBarContainer 必须作为参数传递进来
        interactionBarContainer.setVisible(false);
    }

    /**
     * 处理通用观战状态（me 为 null 或其他未知状态）。
     */
    private void updateGenericSpectatorHUD(StackPane interactionBarContainer, Pane interactionProgressBar) {
        // 观战（既不是自己死亡也不是控制Bot）
        setTextIfChanged(hpLabel, "Spectating");
        hpLabel.setTextFill(Color.GRAY);
        hpLabel.setVisible(true);
        lossBonusBox.setVisible(false);
        moneyLabel.setVisible(false);
        armorLabel.setVisible(false);
        weaponLabel.setVisible(false);
        ammoLabel.setVisible(false);
        equipBox.setVisible(false);
        buyPrompt.setVisible(false);
        changeWeaponPrompt.setVisible(false);
        // 修正：interactionBarContainer 必须作为参数传递进来
        interactionBarContainer.setVisible(false);
    }

    private void updateEquipmentIcons(JsonObject meData) {
        String equipmentSignature = createEquipmentHudSignature(meData);
        if (Objects.equals(renderedEquipmentHudSignature, equipmentSignature)) {
            setVisibleIfChanged(equipBox, !equipBox.getChildren().isEmpty());
            return;
        }
        renderedEquipmentHudSignature = equipmentSignature;
        perfEquipmentHudRebuilds++;
        equipBox.getChildren().clear();

        String myTeam = getString(meData, "team");
        Color teamIconColor = "CT".equals(myTeam) ? PRIMARY_BLUE : Color.web("#DE9B35");
        int currentSlot = getInt(meData, "currentSlot");

        // 护甲图标
        if (getBool(meData, "hasKevlar")) {
            String armorKey = getBool(meData, "hasHelmet") ? "KEVLAR_HELMET" : "KEVLAR";
            Node armorIcon = cs2d.client.GameClient.WeaponIcon.getIcon(armorKey, false);
            if (armorIcon instanceof SVGPath svg) {
                svg.setFill(Color.WHITE);
                svg.setScaleX(0.7);
                svg.setScaleY(0.7);
                equipBox.getChildren().add(armorIcon);
            }
        }

        // 道具图标
        if (meData.has("equipment")) {
            meData.getAsJsonArray("equipment").forEach(e -> {
                JsonObject eq = e.getAsJsonObject();
                String name = getString(eq, "name");
                int count = getInt(eq, "count");

                Node iconNode = cs2d.client.GameClient.WeaponIcon.getIcon(name, false);
                if (iconNode instanceof SVGPath svgIcon) {
                    boolean isSelected = switch (name) {
                        case "FLASHBANG" -> currentSlot == 6;
                        case "HE_GRENADE" -> currentSlot == 7;
                        case "SMOKE_GRENADE" -> currentSlot == 8;
                        case "MOLOTOV", "INCENDIARY" -> currentSlot == 9;
                        case "DECOY" -> currentSlot == 10;
                        default -> false;
                    };

                    if (isSelected) {
                        svgIcon.setFill(Color.YELLOW);
                        svgIcon.setScaleX(0.8);
                        svgIcon.setScaleY(0.8);
                        svgIcon.setEffect(SELECTED_EQUIP_EFFECT);
                    } else {
                        svgIcon.setFill(teamIconColor);
                        svgIcon.setScaleX(0.6);
                        svgIcon.setScaleY(0.6);
                        svgIcon.setEffect(null);
                    }

                    if (count > 1) {
                        Label countLabel = new Label("x" + count);
                        countLabel.setFont(tinyHudFont);
                        countLabel.setTextFill(Color.WHITE);
                        StackPane iconWithCount = new StackPane(svgIcon, countLabel);
                        StackPane.setAlignment(countLabel, Pos.BOTTOM_RIGHT);
                        equipBox.getChildren().add(iconWithCount);
                    } else {
                        equipBox.getChildren().add(svgIcon);
                    }
                }
            });
        }

        // 拆弹器图标
        if (getBool(meData, "hasDefuseKit")) {
            Node kitIcon = cs2d.client.GameClient.WeaponIcon.getIcon("DEFUSE_KIT", false);
            if (kitIcon instanceof SVGPath svg) {
                svg.setFill(Color.CYAN);
                svg.setScaleX(0.7);
                svg.setScaleY(0.7);
                equipBox.getChildren().add(kitIcon);
            }
        }

        setVisibleIfChanged(equipBox, !equipBox.getChildren().isEmpty());
    }

    /** 只包含装备栏可见状态；玩家位置、血量等变化不会让SVG节点树失效。 */
    static String createEquipmentHudSignature(JsonObject playerData) {
        if (playerData == null)
            return "none";

        StringBuilder signature = new StringBuilder(128);
        signature.append(getString(playerData, "team")).append('|')
                .append(getInt(playerData, "currentSlot")).append('|')
                .append(getBool(playerData, "hasKevlar")).append('|')
                .append(getBool(playerData, "hasHelmet")).append('|')
                .append(getBool(playerData, "hasDefuseKit"));

        JsonArray equipment = playerData.has("equipment") && playerData.get("equipment").isJsonArray()
                ? playerData.getAsJsonArray("equipment")
                : null;
        if (equipment != null) {
            for (JsonElement element : equipment) {
                if (!element.isJsonObject())
                    continue;
                JsonObject item = element.getAsJsonObject();
                signature.append('|').append(getString(item, "name"))
                        .append(':').append(getInt(item, "count"));
            }
        }
        return signature.toString();
    }

    private void updateInteractionBar(JsonObject meData, StackPane interactionBarContainer,
                                      Pane interactionProgressBar) {
        interactionBarContainer.setVisible(getBool(meData, "isInteracting"));
        if (getBool(meData, "isInteracting")) {
            double duration = (double) System.currentTimeMillis() - getDouble(meData, "interactionStart");
            double progress = 0;
            if (getBool(latestGameState, "bombPlanted") && "CT".equals(getString(meData, "team"))) {
                progress = (duration
                        / (getBool(meData, "hasDefuseKit") ? DEMO_DEFUSE_WITH_KIT_TIME_MS : DEMO_DEFUSE_TIME_MS)) * 100;
            } else if (getBool(meData, "hasBomb") && "T".equals(getString(meData, "team"))) {
                progress = (duration / DEMO_PLANT_TIME_MS) * 100;
            }
            interactionProgressBar.setPrefWidth(200 * (Math.min(100, progress) / 100.0));
        }
    }

    private void setHUDVisibility(boolean visible) {
        if (Objects.equals(renderedHudGlobalVisibility, visible))
            return;
        renderedHudGlobalVisibility = visible;
        perfHudVisibilityPasses++;
        for (Node node : ((AnchorPane) gameContainer.getChildren().get(1)).getChildren()) {
            setVisibleIfChanged(node, visible);
        }
    }

    private void updateGlobalInfo() {
        setTextIfChanged(pingLabel, "Ping: " + ping);
    }

    private void updateTopCornerLabels() {
        String mode = getString(latestGameState, "mode");
        boolean isDemo = "DEMOLITION".equals(mode);
        boolean isTDM = "TEAM_DEATHMATCH".equals(mode);
        boolean isZombie = "ZOMBIE_MODE".equals(mode);
        boolean isDeathmatch = "DEATHMATCH".equals(mode);
        int roundRemainingTime = getInt(latestGameState, "time");// 自动关闭，获取时间
        // --- 右上角 (时间/回合) ---
        roundLabel.setVisible(isDemo);
        timerLabel.setVisible(isDemo || isTDM || isZombie || isDeathmatch);

        if (isDemo) {
            setTextIfChanged(roundLabel, "Round: " + getInt(latestGameState, "round") + "/" + DEMOLITION_MAX_ROUNDS);
            long timer = getBool(latestGameState, "bombPlanted") ? (long) getDouble(latestGameState, "bombTimer")
                    : (long) getDouble(latestGameState, "roundTime");
            String timerPrefix = "FREEZE_TIME".equals(getString(latestGameState, "roundPhase")) ? "Prepare: " : "";
            setTextIfChanged(timerLabel, timerPrefix + String.format("%02d", timer / 1000));
            if (getBool(latestGameState, "bombPlanted"))
                timerLabel.setTextFill(Color.RED);
            else if ("FREEZE_TIME".equals(getString(latestGameState, "roundPhase")))
                timerLabel.setTextFill(Color.YELLOW);
            else
                timerLabel.setTextFill(Color.WHITE);
        } else if (isTDM || isZombie || isDeathmatch) {
            long time = getInt(latestGameState, "time");
            setTextIfChanged(timerLabel, "Time: " + time / 60 + ":" + String.format("%02d", time % 60));
            timerLabel.setTextFill(Color.WHITE);
        }

        // --- 左上角 (比分/波次) ---
        ctScoreLabel.setVisible(isDemo || isTDM);
        tScoreLabel.setVisible(isDemo || isTDM);
        waveLabel.setVisible(isZombie);
        zombiesLeftLabel.setVisible(isZombie);
        if (isDemo || isTDM) {
            setTextIfChanged(ctScoreLabel, "CT: " + getInt(latestGameState, "ctScore"));
            ctScoreLabel.setTextFill(Color.CYAN);
            setTextIfChanged(tScoreLabel, "T: " + getInt(latestGameState, "tScore"));
            tScoreLabel.setTextFill(Color.RED);
        } else if (isZombie) {
            setTextIfChanged(waveLabel, "Wave: " + getInt(latestGameState, "wave"));
            waveLabel.setTextFill(Color.ORANGE);
            setTextIfChanged(zombiesLeftLabel, "Zombies Left: " + getInt(latestGameState, "zombiesLeft"));
            zombiesLeftLabel.setTextFill(Color.RED);
        } // [2025-12-01] 死斗模式下，隐藏团队分数
        if (isDeathmatch) {
            ctScoreLabel.setVisible(false);
            tScoreLabel.setVisible(false);
        }
    }

    private void updateCenterLabels() {
        // --- 中心 (回合结束/下一波) ---
        boolean roundOver = "ROUND_OVER".equals(getString(latestGameState, "roundPhase"));
        winnerLabel.setVisible(roundOver);
        reasonLabel.setVisible(roundOver);
        if (roundOver) {
            setTextIfChanged(winnerLabel, getString(latestGameState, "roundWinner") + " Win!");
            winnerLabel.setTextFill("CT".equals(getString(latestGameState, "roundWinner")) ? Color.CYAN : Color.RED);
            setTextIfChanged(reasonLabel, getString(latestGameState, "roundWinReason"));
            reasonLabel.setTextFill(Color.WHITE);
        }
        boolean nextWave = latestGameState.has("nextWaveIn");
        nextWaveLabel.setVisible(nextWave);
        if (nextWave) {
            setTextIfChanged(nextWaveLabel, "Next Wave: " + getInt(latestGameState, "nextWaveIn"));
            nextWaveLabel.setTextFill(Color.YELLOW);
        }
    }

    /** --- 投掷物轨迹计算与绘制 --- */
    private record CollisionResult(Point2D impactPoint, Point2D normal) {
    }

    /**
     * [已修改] 核心辅助方法：执行连续碰撞检测。
     * 查找一条射线与所有障碍物之间最近的碰撞点。
     * 此版本现在从 Quadtree 读取 StaticObstacle 并使用其缓存的边缘。
     *
     * @param rayStart 射线的起点
     * @param rayEnd   射线的终点
     * @return 如果发生碰撞，返回一个包含碰撞点和法线的 CollisionResult；否则返回 null。
     */
    private cs2d.client.GameClient.CollisionResult findClosestCollision(Point2D rayStart, Point2D rayEnd) {
        cs2d.client.GameClient.CollisionResult closestCollision = null;
        double minDistanceSq = Double.POSITIVE_INFINITY;

        // --- VVVV 核心修改 VVVV ---
        // 1. 检查 Quadtree 是否已初始化
        if (quadtreeRootNode == null) {
            // 如果 Quadtree 没准备好，返回 null (不碰撞)
            return null;
        }

        // 2. [修复] 列表类型必须是 StaticObstacle
        // List<JsonObject> candidateObstacles = new ArrayList<>(); // [旧]
        List<cs2d.client.GameClient.StaticObstacle> candidateObstacles = new ArrayList<>(); // [新]
        quadtreeRootNode.queryRay(candidateObstacles, rayStart, rayEnd);
        // --- ^^^^ 结束修改 ^^^^ ---

        // 3. [修复] 遍历 StaticObstacle 列表
        // for (JsonObject obs : candidateObstacles) { // [旧]
        for (cs2d.client.GameClient.StaticObstacle obs : candidateObstacles) { // [新]

            // --- VVVV 核心优化 VVVV ---
            // 4. [修复] 直接从缓存中获取边缘，而不是重新计算！
            // [旧的边缘提取逻辑已被完全删除] ...
            List<Point2D[]> edges = obs.edges; // [新] 直接读取缓存
            // --- ^^^^ 结束优化 ^^^^ ---

            for (Point2D[] edge : edges) {
                Point2D intersection = getLineIntersection(rayStart, rayEnd, edge[0], edge[1]);

                if (intersection != null) {
                    double distSq = rayStart.distance(intersection) * rayStart.distance(intersection);
                    if (distSq < minDistanceSq) {
                        minDistanceSq = distSq;

                        double dx = edge[1].getX() - edge[0].getX();
                        double dy = edge[1].getY() - edge[0].getY();
                        Point2D normal = new Point2D(dy, -dx).normalize();

                        // --- [关键点 1] 确保法线方向永远朝外 ---
                        Point2D velocityVector = rayEnd.subtract(rayStart);
                        if (normal.dotProduct(velocityVector) > 0) {
                            normal = normal.multiply(-1.0);
                        }

                        closestCollision = new cs2d.client.GameClient.CollisionResult(intersection, normal);
                    }
                }
            }
        }
        return closestCollision;
    }

    private String predictedWeaponKey = null;

    /**
     * 计算手雷的飞行轨迹，包括与障碍物的碰撞。
     * 这是一个纯客户端的物理模拟，用于预测落点。
     */
    private void calculateGrenadeTrajectory() {
        grenadePath.clear();
        // 新增检查：如果 Quadtree 还没准备好，也不计算
        if (me == null || mapData == null || !mapData.has("obstacles") || quadtreeRootNode == null) {
            return;
        }

        // ---M初始化物理参数 (逻辑不变) ---
        String currentGrenadeType;
        if (this.predictedWeaponKey != null) {
            currentGrenadeType = this.predictedWeaponKey;
        } else {
            currentGrenadeType = getString(me.data, "weaponKey");
        }
        double flightTime;
        switch (currentGrenadeType) {
            case "SMOKE_GRENADE":
                flightTime = 1.5;
                break;
            case "MOLOTOV":
            case "INCENDIARY":
                flightTime = 1.0;
                break;
            default:
                flightTime = 2.0;
                break;
        }
        final double serverBaseVelocity = 8.0;
        final double bounceAttenuation = 0.85; // 速度衰减 ( -GRENADE_BOUNCE_FRICTION )

        // --- 2. 计算初始速度 (逻辑不变) ---
        Point2D mouseWorld = camera.screenToWorld(mouseX, mouseY);
        Point2D currentPos = me.getPos();
        double angle = Math.atan2(mouseWorld.getY() - currentPos.getY(), mouseWorld.getX() - currentPos.getX());
        double throwVx = Math.cos(angle) * serverBaseVelocity;
        double throwVy = Math.sin(angle) * serverBaseVelocity;
        double vx = throwVx + me.vx;
        double vy = throwVy + me.vy;

        // --- 3. [核心修正] 基于【连续碰撞检测】的物理模拟 ---
        int maxSteps = (int) (flightTime * LEGACY_PHYSICS_RATE);

        // --- VVVV 核心修改 VVVV ---
        // JsonArray obstacles = mapData.getAsJsonArray("obstacles"); // <-- [删除]
        // 不再需要获取完整列表
        // --- ^^^^ 结束修改 ^^^^ ---

        for (int i = 0; i < maxSteps; i++) {
            Point2D nextPos = new Point2D(currentPos.getX() + vx, currentPos.getY() + vy);

            // --- VVVV 核心修改 VVVV ---
            // CollisionResult collision = findClosestCollision(currentPos, nextPos,
            // obstacles); // <-- [旧代码]
            cs2d.client.GameClient.CollisionResult collision = findClosestCollision(currentPos, nextPos); // <-- [新代码]
            // (不再传递
            // obstacles)
            // --- ^^^^ 结束修改 ^^^^ ---

            if (collision != null) {
                Point2D impactPoint = collision.impactPoint();
                Point2D normal = collision.normal();

                // 1. 向量反射
                double dot = vx * normal.getX() + vy * normal.getY();
                vx -= 2 * dot * normal.getX();
                vy -= 2 * dot * normal.getY();

                // *** 核心修复：只在这里进行一次速度衰减 ***
                vx *= bounceAttenuation;
                vy *= bounceAttenuation;

                // Epsilon推离，防止“抽搐”
                currentPos = impactPoint.add(normal.multiply(0.1));

            } else {
                // 没有碰撞，正常移动
                currentPos = nextPos;
            }

            // 4. 检查并处理地图边界（简化版）
            if (currentPos.getX() <= 0 || currentPos.getX() >= getDouble(mapData, "width")) {
                vx *= -bounceAttenuation;
                currentPos = new Point2D(Math.max(1, Math.min(getDouble(mapData, "width") - 1, currentPos.getX())),
                        currentPos.getY());
            }
            if (currentPos.getY() <= 0 || currentPos.getY() >= getDouble(mapData, "height")) {
                vy *= -bounceAttenuation;
                currentPos = new Point2D(currentPos.getX(),
                        Math.max(1, Math.min(getDouble(mapData, "height") - 1, currentPos.getY())));
            }

            grenadePath.add(currentPos);
            if ((vx * vx) + (vy * vy) < 0.1)
                break;
        }
    }

    /**
     * 辅助方法：判断一个点是否在【任何一个】障碍物内部
     */
    private boolean isPointInObstacle(Point2D point, JsonArray obstacles) {
        if (obstacles == null)
            return false;
        for (JsonElement obsEl : obstacles) {
            if (isPointInObstacle(point, obsEl.getAsJsonObject())) {
                return true;
            }
        }
        return false;
    }

    /** 用于客户端预测，存储玩家想要切换到的武器槽位 */
    private int predictedSlot = -1;

    /**
     * 辅助方法：判断一个点是否在单个障碍物内部（支持矩形、圆形和多边形）。
     *
     * @param point 要检测的点
     * @param obs   障碍物的JSON对象
     * @return 如果点在障碍物内，返回true
     */
    private boolean isPointInObstacle(Point2D point, JsonObject obs) {
        String type = getString(obs, "type");

        switch (type) {
            case "RECTANGLE": {
                double x = getDouble(obs, "x"), y = getDouble(obs, "y");
                double w = getDouble(obs, "w"), h = getDouble(obs, "h");
                return (point.getX() >= x && point.getX() <= x + w &&
                        point.getY() >= y && point.getY() <= y + h);
            }

            case "ELLIPSE": {
                double x = getDouble(obs, "x"), y = getDouble(obs, "y");
                double w = getDouble(obs, "w"), h = getDouble(obs, "h");
                double centerX = x + w / 2;
                double centerY = y + h / 2;
                double radiusX = w / 2;
                double radiusY = h / 2;

                if (radiusX == 0 || radiusY == 0)
                    return false;

                double dx = point.getX() - centerX;
                double dy = point.getY() - centerY;

                // 标准椭圆方程：(x/a)^2 + (y/b)^2 <= 1
                return (dx * dx) / (radiusX * radiusX) + (dy * dy) / (radiusY * radiusY) <= 1;
            }

            case "POLYGON": {
                if (!obs.has("xPoints") || !obs.has("yPoints"))
                    return false;

                JsonArray xPointsJson = obs.getAsJsonArray("xPoints");
                JsonArray yPointsJson = obs.getAsJsonArray("yPoints");

                if (xPointsJson.size() != yPointsJson.size())
                    return false;

                // 将JSON数组转换为 List<Point2D>，以匹配 isPointInPolygon 方法的参数要求
                List<Point2D> polygonVertices = new ArrayList<>();
                for (int i = 0; i < xPointsJson.size(); i++) {
                    polygonVertices
                            .add(new Point2D(xPointsJson.get(i).getAsDouble(), yPointsJson.get(i).getAsDouble()));
                }

                // 直接复用你已有的、用于计算视野的多边形判断方法！
                return isPointInPolygon(point, polygonVertices);
            }

            default:
                return false;
        }
    }

    /**
     * 在Canvas上绘制出手雷轨迹和落点。
     */
    private void drawGrenadeTrajectory() {
        if (grenadePath.isEmpty()) {
            return;
        }

        gc.save();
        // 绘制轨迹线
        gc.setLineWidth(2.0);
        gc.setLineDashes(10); // 设置为虚线
        gc.setStroke(Color.rgb(255, 255, 255, 0.7));

        Point2D lastScreenPos = null;
        for (Point2D worldPos : grenadePath) {
            Point2D currentScreenPos = camera.worldToScreen(worldPos.getX(), worldPos.getY());
            if (lastScreenPos != null) {
                gc.strokeLine(lastScreenPos.getX(), lastScreenPos.getY(), currentScreenPos.getX(),
                        currentScreenPos.getY());
            }
            lastScreenPos = currentScreenPos;
        }

        // 绘制最终落点
        if (lastScreenPos != null) {
            gc.setLineDashes(0); // 恢复实线
            gc.setStroke(Color.WHITE);
            gc.strokeOval(lastScreenPos.getX() - 8, lastScreenPos.getY() - 8, 16, 16);
        }
        gc.restore();
    }

    /**
     * 辅助方法：根据武器槽位号获取武器的Key。
     * 主要用于客户端预测。
     */
    private String getWeaponKeyForSlot(int slot) {
        if (me == null || me.data == null)
            return null;

        // 投掷物的槽位是固定的
        switch (slot) {
            case 6:
                return "FLASHBANG";
            case 7:
                return "HE_GRENADE";
            case 8:
                return "SMOKE_GRENADE";
            case 9:
                // 燃烧弹/瓶需要根据阵营判断
                return "CT".equals(getString(me.data, "team")) ? "INCENDIARY" : "MOLOTOV";
            case 10:
                return "DECOY";
            default:
                // 对于其他槽位，直接返回服务器确认的武器Key
                return getString(me.data, "weaponKey");
        }
    }

    /**
     * 更新所有飞行中投掷物的拖尾数据。
     * 只应在观战模式下调用。
     */
    private void updateGrenadeTrails() {
        long currentTime = System.currentTimeMillis();

        // 确保我们遍历的是包含平滑位置的 clientGrenades
        Set<String> activeGrenadeIds = clientGrenades.keySet();

        // 1. 为当前在空中的手雷添加新的轨迹点
        for (String id : activeGrenadeIds) {
            cs2d.client.GameClient.ClientGrenade grenade = clientGrenades.get(id);
            if (grenade == null)
                continue;

            // --- 核心修改：使用平滑后的 renderX/renderY 来创建轨迹点 ---
            // grenade.getPos() 返回的就是基于 (renderX, renderY) 的平滑位置
            Point2D pos = grenade.getPos();

            grenadeTrails.computeIfAbsent(id, k -> new LinkedList<>())
                    .addLast(new cs2d.client.GameClient.TrailPoint(pos, currentTime));
        }

        // 2. 清理逻辑保持不变
        grenadeTrails.entrySet().removeIf(entry -> {
            String id = entry.getKey();
            if (!activeGrenadeIds.contains(id)) {
                return true;
            }
            entry.getValue().removeIf(point -> currentTime - point.creationTime() > GRENADE_TRAIL_DURATION_MS);
            return entry.getValue().isEmpty();
        });
    }

    /** 用于记录投掷物拖尾效果的单个点的信息 */
    private record TrailPoint(javafx.geometry.Point2D pos, long creationTime) {
    }

    /** 用于存储观战模式下的投掷物拖尾效果，键是手雷ID，值是轨迹点队列 */
    private final Map<String, Deque<cs2d.client.GameClient.TrailPoint>> grenadeTrails = new ConcurrentHashMap<>();
    /** 拖尾效果的持续时间（毫秒） */

    /** 拖尾效果的持续时间（毫秒） */
    private static final long GRENADE_TRAIL_DURATION_MS = 750;

    /**
     * 绘制所有投掷物的拖尾效果。
     * 只应在观战模式下调用。
     */
    private void drawGrenadeTrails() {
        long currentTime = System.currentTimeMillis();
        gc.save();

        for (Deque<cs2d.client.GameClient.TrailPoint> trail : grenadeTrails.values()) {
            if (trail.size() < 2) {
                continue;
            }

            List<cs2d.client.GameClient.TrailPoint> currentTrailPoints = new ArrayList<>(trail);

            for (int i = 0; i < currentTrailPoints.size() - 1; i++) {
                cs2d.client.GameClient.TrailPoint p1Data = currentTrailPoints.get(i);
                cs2d.client.GameClient.TrailPoint p2Data = currentTrailPoints.get(i + 1);

                // 【关键修复 ②】: 直接使用世界坐标，不再手动转换！
                // 因为调用此方法的地方 (drawWorldObjects) 已经应用了相机变换。
                Point2D worldP1 = p1Data.pos();
                Point2D worldP2 = p2Data.pos();

                // --- 计算透明度和粗细的逻辑保持不变 ---
                long age = currentTime - p2Data.creationTime();
                double normalizedAge = (double) age / GRENADE_TRAIL_DURATION_MS;
                double alpha = Math.max(0, 1.0 - normalizedAge);
                double lineWidth = Math.max(0.5, 4.0 * alpha);

                gc.setStroke(Color.rgb(255, 255, 150, alpha * 0.7));
                gc.setLineWidth(lineWidth);

                // --- 直接用世界坐标绘制 ---
                gc.beginPath();
                gc.moveTo(worldP1.getX(), worldP1.getY());
                gc.lineTo(worldP2.getX(), worldP2.getY());
                gc.stroke();
                gc.closePath();
            }
        }
        gc.restore();
    }

    /**
     * 客户端本地投掷物实体类，用于平滑渲染和状态存储
     * <插值>
     * </>
     */

    private static class ClientGrenade {
        String id;
        volatile double renderX, renderY; // 当前帧的渲染位置（平滑）
        volatile double targetX, targetY; // 服务器发来的目标位置
        volatile double vx, vy; // 服务器发来的速度
        volatile JsonObject data; // 存储原始JSON数据
        private boolean isInitialized = false;

        private final cs2d.client.GameClient clientInstance;

        ClientGrenade(JsonObject data, cs2d.client.GameClient clientInstance) {
            this.id = getString(data, "id");
            this.clientInstance = clientInstance;
            update(data);

            if (!isInitialized) {
                this.renderX = this.targetX;
                this.renderY = this.targetY;
                isInitialized = true;
            }
        }

        synchronized void update(JsonObject data) {
            this.data = data.deepCopy();
            this.targetX = getDouble(data, "x");
            this.targetY = getDouble(data, "y");
            this.vx = getDouble(data, "vx");
            this.vy = getDouble(data, "vy");
        }

        // [核心] 每帧调用，让渲染位置平滑地追赶目标位置
        synchronized void updateRenderPosition(double deltaTime) {
            // [修复] 时间无关的平滑插值 (Time-Independent Lerp)
            double baseLerp = 0.3; // 60Hz 时的标准平滑度
            double actualLerp = 1.0 - Math.pow(1.0 - baseLerp, deltaTime * 60.0);

            this.renderX += (this.targetX - this.renderX) * actualLerp;
            this.renderY += (this.targetY - this.renderY) * actualLerp;

            // [修复] 预测位移，并增加简易碰撞检测防止穿墙抖动
            double speedMultiplier = deltaTime * LEGACY_PHYSICS_RATE;
            double nextTargetX = this.targetX + this.vx * speedMultiplier;
            double nextTargetY = this.targetY + this.vy * speedMultiplier;

            // 如果地图数据已加载，进行障碍物检测
            if (clientInstance.mapData != null && clientInstance.quadtreeRootNode != null) {
                Point2D nextPos = new Point2D(nextTargetX, nextTargetY);
                boolean hit = false;
                List<cs2d.client.GameClient.StaticObstacle> candidateObstacles = new ArrayList<>();
                clientInstance.quadtreeRootNode.queryBounds(candidateObstacles,
                        new Rectangle2D(nextTargetX - 10, nextTargetY - 10, 20, 20));

                for (cs2d.client.GameClient.StaticObstacle obs : candidateObstacles) {
                    if (clientInstance.isPointInObstacle(nextPos, obs.originalJson)) {
                        hit = true;
                        break;
                    }
                }

                if (!hit) {
                    this.targetX = nextTargetX;
                    this.targetY = nextTargetY;
                } else {
                    // 如果撞墙了，立即停止预测
                    this.vx = 0;
                    this.vy = 0;
                }
            } else {
                this.targetX = nextTargetX;
                this.targetY = nextTargetY;
            }
        }

        public Point2D getPos() {
            return new Point2D(renderX, renderY);
        }
    }

    // --- 燃烧特效 START ---

    /**
     * 代表一个单独的火焰粒子
     */
    private static class FireParticle {
        Point2D position;
        Point2D velocity;
        double life;
        double age = 0;
        double size;
        Color startColor = Color.rgb(255, 220, 100, 0.9); // 亮黄色
        Color midColor = Color.rgb(255, 100, 0, 0.7); // 橙红色
        Color endColor = Color.rgb(40, 40, 40, 0.0); // 暗烟色（透明）

        FireParticle(Point2D position) {
            this.position = position;
            Random rand = new Random();
            double angle = -Math.PI / 2 + (rand.nextDouble() - 0.5) * Math.PI; // 向上方扇形发散
            double speed = 20 + rand.nextDouble() * 30;
            this.velocity = new Point2D(Math.cos(angle) * speed, Math.sin(angle) * speed);
            this.life = 0.5 + rand.nextDouble() * 0.8; // 粒子的生命周期
            this.size = 15 + rand.nextDouble() * 15;
        }

        void update(double deltaTime) {
            age += deltaTime;
            position = position.add(velocity.multiply(deltaTime));
            // 可以给粒子加一点“摇曳”的效果
            velocity = velocity.add((Math.random() - 0.5) * 20 * deltaTime, 0);
        }

        void draw(GraphicsContext gc) {
            if (isDead())
                return;
            double progress = age / life;

            // 计算当前大小（逐渐缩小）
            double currentSize = size * (1 - progress);

            // 计算当前颜色（分段插值）
            Color currentColor;
            if (progress < 0.5) {
                currentColor = startColor.interpolate(midColor, progress * 2);
            } else {
                currentColor = midColor.interpolate(endColor, (progress - 0.5) * 2);
            }

            gc.setFill(currentColor);
            gc.fillOval(position.getX() - currentSize / 2, position.getY() - currentSize / 2, currentSize, currentSize);
        }

        boolean isDead() {
            return age >= life;
        }
    }

    /**
     * 代表一整块燃烧区域，是火焰粒子的发射器
     */
    private static class FireEmitter {
        String id;
        Point2D position;
        List<cs2d.client.GameClient.FireParticle> particles = new ArrayList<>();
        double spawnRate = 40; // 每秒生成40个粒子
        double spawnAccumulator = 0;

        FireEmitter(String id, Point2D position) {
            this.id = id;
            this.position = position;
        }

        void update(double deltaTime) {
            // 更新并移除死掉的粒子
            particles.removeIf(cs2d.client.GameClient.FireParticle::isDead);
            for (cs2d.client.GameClient.FireParticle p : particles) {
                p.update(deltaTime);
            }

            // 根据生成速率，计算本帧需要生成多少新粒子
            spawnAccumulator += deltaTime * spawnRate;
            int particlesToSpawn = (int) spawnAccumulator;
            if (particlesToSpawn > 0) {
                spawnAccumulator -= particlesToSpawn;
                for (int i = 0; i < particlesToSpawn; i++) {
                    // 在发射器半径内随机一个位置生成粒子
                    double angle = Math.random() * 2 * Math.PI;
                    double radius = Math.random() * 40; // FirePatch.RADIUS
                    Point2D spawnPos = position.add(Math.cos(angle) * radius, Math.sin(angle) * radius);
                    particles.add(new cs2d.client.GameClient.FireParticle(spawnPos));
                }
            }
        }

        void draw(GraphicsContext gc) {
            for (cs2d.client.GameClient.FireParticle p : particles) {
                p.draw(gc);
            }
        }
    }

    // --- 燃烧特效 END ---

    /**
     * [新] GZIP解压辅助方法
     *
     * @param compressedData GZIP 压缩过的字节数组
     * @return 解压后的原始字节数组
     * @throws IOException
     */
    private static byte[] decompress(byte[] compressedData) throws IOException {
        if (compressedData == null || compressedData.length == 0) {
            return new byte[0];
        }
        if (compressedData.length > MAX_COMPRESSED_MESSAGE_BYTES)
            throw new IOException("压缩消息超过大小限制");
        // 1. 用压缩数据创建一个字节数组输入流
        ByteArrayInputStream bis = new ByteArrayInputStream(compressedData);
        // 2. 用 GZIP 输入流包裹它
        GZIPInputStream gis = new GZIPInputStream(bis);
        // 3. 创建一个字节数组输出流来接收解压后的数据
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        byte[] buffer = new byte[1024]; // 缓冲区
        int len;
        int total = 0;
        // 4. 循环读取解压后的数据
        while ((len = gis.read(buffer)) != -1) {
            total += len;
            if (total > MAX_DECOMPRESSED_MESSAGE_BYTES)
                throw new IOException("解压消息超过大小限制");
            bos.write(buffer, 0, len);
        }
        // 5. 关闭流
        gis.close();
        bos.close();
        // 6. 返回解压后的字节数组
        return bos.toByteArray();
    }

    /**
     * 一个用于重组服务器发送的分片消息的缓冲区。
     * Key: 大消息的唯一ID。
     * Value: 一个包含分片数组和接收计数器的对象。
     */
    private static final int MAX_CHUNK_COUNT = 8192;
    private static final int MAX_CHUNK_DATA_LENGTH = 1200;
    private static final int MAX_CHUNK_BUFFERS = 128;
    private static final int MAX_COMPRESSED_MESSAGE_BYTES = 8 * 1024 * 1024;
    private static final int MAX_DECOMPRESSED_MESSAGE_BYTES = 32 * 1024 * 1024;
    private static final long CHUNK_TTL_NANOS = TimeUnit.SECONDS.toNanos(5);
    private final Map<String, cs2d.client.GameClient.ChunkBuffer> chunkBuffers = new ConcurrentHashMap<>();

    private void cleanupExpiredChunkBuffers() {
        long now = System.nanoTime();
        chunkBuffers.entrySet().removeIf(entry -> entry.getValue().isExpired(now));
    }

    private void evictOldestChunkBuffer() {
        chunkBuffers.entrySet().stream()
                .min(Comparator.comparingLong(entry -> entry.getValue().createdAtNanos))
                .ifPresent(entry -> chunkBuffers.remove(entry.getKey(), entry.getValue()));
    }

    /**
     * 用于管理单个分片消息重组的辅助类。
     */
    private static class ChunkBuffer {
        final String[] chunks;
        final String checksum;
        final long createdAtNanos = System.nanoTime();
        int receivedCount = 0;

        ChunkBuffer(int total, String checksum) {
            this.chunks = new String[total];
            this.checksum = checksum.toLowerCase(Locale.ROOT);
        }

        boolean matches(int total, String candidateChecksum) {
            return chunks.length == total && checksum.equalsIgnoreCase(candidateChecksum);
        }

        boolean isExpired(long nowNanos) {
            return nowNanos - createdAtNanos > CHUNK_TTL_NANOS;
        }

        /**
         * 将一个分片添加到缓冲区。
         *
         * @param index 此分片的索引。
         * @param data  此分片的 Base64 编码数据。
         * @return 如果所有分片都已收到，则返回 true，否则返回 false。
         */
        synchronized boolean addChunk(int index, String data) {
            if (index < 0 || index >= chunks.length || data == null || data.length() > MAX_CHUNK_DATA_LENGTH)
                return false;
            if (chunks[index] == null) {
                chunks[index] = data;
                receivedCount++;
            }
            return receivedCount == chunks.length;
        }

        /**
         * 连接所有分片，解码它们，并返回完整的消息。
         * 此版本现在支持 GZIP 解压缩。
         *
         * @return 重组后的 JSON 字符串。
         */
        synchronized String getFullMessage() throws IOException {
            if (receivedCount != chunks.length)
                throw new IOException("分片尚未接收完整");
            // 将所有 Base64 字符串连接在一起 (这现在是 *压缩后* 的Base64)
            String fullCompressedBase64 = String.join("", chunks);
            if (fullCompressedBase64.length() > (MAX_COMPRESSED_MESSAGE_BYTES * 4L / 3L) + 4)
                throw new IOException("Base64 分片消息超过大小限制");

            // 将 Base64 解码回 *压缩* 的字节数组
            byte[] compressedData;
            try {
                compressedData = Base64.getDecoder().decode(fullCompressedBase64);
            } catch (IllegalArgumentException e) {
                throw new IOException("Base64 分片数据无效", e);
            }
            if (compressedData.length > MAX_COMPRESSED_MESSAGE_BYTES)
                throw new IOException("压缩消息超过大小限制");

            CRC32 crc32 = new CRC32();
            crc32.update(compressedData);
            if (!Long.toHexString(crc32.getValue()).equalsIgnoreCase(checksum))
                throw new IOException("分片 CRC 校验失败");

            byte[] decompressedData = decompress(compressedData);
            return new String(decompressedData, StandardCharsets.UTF_8);
        }

    }

    /** 代表客户端当前帧实际要绘制的后坐力角度，它会平滑地追赶服务器状态 */
    private double visualRecoilAngle = 0.0;

    /**
     * [修复] 现在接收 deltaTime 参数，确保后坐力恢复在不同帧率下一致。
     */
    private void updateRecoil(double deltaTime) {
        // 定义 60Hz 下的标准修正速度
        final double baseRecoilLerp = 0.15;
        // 计算当前帧的实际修正速度
        double actualRecoilLerp = 1.0 - Math.pow(1.0 - baseRecoilLerp, deltaTime * 60.0);

        // 从服务器获取权威的后坐力目标值
        double serverRecoilAngle = (me != null) ? me.predictedRecoilAngle : 0.0;

        // 使用时间无关的插值公式
        this.visualRecoilAngle += (serverRecoilAngle - this.visualRecoilAngle) * actualRecoilLerp;

        // 如果已经非常接近0，就直接归零
        if (Math.abs(this.visualRecoilAngle) < 0.0001) {
            this.visualRecoilAngle = 0;
        }
    }

    /**
     * 封装一个 C4 爆炸效果，继承自 ExplosionEffect 以便加入同一列表。
     */
    private static class C4ExplosionEffect extends cs2d.client.GameClient.ExplosionEffect {

        // 因为 C4ExplosionEffect 继承了 ExplosionEffect，
        // 它必须调用父类的构造函数。
        // 我们传入 duration = 1.0，但 C4 的绘制逻辑会覆盖它。
        public C4ExplosionEffect(Point2D pos, double currentTime) {
            super(pos, currentTime, 1.0); // 假设 ExplosionEffect 有这个构造函数
        }

        // 重写父类的 draw 方法，使用 C4 自己的复杂绘制逻辑
        @Override
        public void draw(GraphicsContext gc, cs2d.client.GameClient.Camera camera) {
            double elapsed = (System.currentTimeMillis() / 1000.0) - startTime;
            if (elapsed > duration)
                return;

            double progress = elapsed / duration;
            gc.save();

            Point2D currentPos = this.position;
            final double MAX_VISUAL_RADIUS = 450.0;
            final double SHOCKWAVE_DURATION = 0.15; // 瞬时冲击波持续时间

            // --- 1. 核心白色闪光 --- (0 - 0.03秒)
            final double FLASH_DURATION = 0.03;
            if (elapsed < FLASH_DURATION) {
                // 闪光进度：从 0.0 到 1.0
                double flashProgress = elapsed / FLASH_DURATION;
                // 透明度从 1.0 快速降到 0.0
                double flashAlpha = Math.max(0, 1.0 - flashProgress);

                gc.setGlobalAlpha(flashAlpha);
                gc.setFill(Color.WHITE);
                gc.fillOval(currentPos.getX() - 60, currentPos.getY() - 60, 120, 120);
            }

            // --- 2. 快速冲击波 --- (0 - 0.15秒)
            // 冲击波只在最初的瞬间存在，并迅速扩张。
            if (elapsed < SHOCKWAVE_DURATION) {
                double shockwaveProgress = elapsed / SHOCKWAVE_DURATION;
                double currentShockwaveRadius = MAX_VISUAL_RADIUS * 0.5 * shockwaveProgress;
                double shockwaveAlpha = Math.max(0, 1.0 - shockwaveProgress * 2.5); // 快速消失

                gc.setGlobalAlpha(shockwaveAlpha);
                gc.setStroke(Color.WHITE.deriveColor(0, 1.0, 1.0, 0.7));
                gc.setLineWidth(Math.max(1, 15 * (1.0 - shockwaveProgress)));

                gc.strokeOval(currentPos.getX() - currentShockwaveRadius,
                        currentPos.getY() - currentShockwaveRadius,
                        currentShockwaveRadius * 2,
                        currentShockwaveRadius * 2);
            }

            // --- 3. 火焰/能量膨胀层 --- (0 - 0.05秒)
            final double FIRE_DURATION = 0.05;
            if (elapsed < FIRE_DURATION) {
                double fireProgress = elapsed / FIRE_DURATION;
                double fireRadius = MAX_VISUAL_RADIUS * 0.4 * fireProgress;
                double fireAlpha = 0.8 * (1.0 - Math.pow(fireProgress, 0.5));

                gc.setGlobalAlpha(Math.max(0, fireAlpha));
                gc.setFill(Color.ORANGE); // 核心火焰用亮橙色
                gc.fillOval(currentPos.getX() - fireRadius, currentPos.getY() - fireRadius, fireRadius * 2,
                        fireRadius * 2);
            }

            // --- 4. 蘑菇云/烟尘上升层 --- (0.1秒 - 0.2秒)
            final double MUSHROOM_START_TIME = 0.1;
            final double MUSHROOM_DURATION = 0.2;
            final double MUSHROOM_END_TIME = MUSHROOM_START_TIME + MUSHROOM_DURATION; // 0.3秒结束

            if (elapsed >= MUSHROOM_START_TIME && elapsed < MUSHROOM_END_TIME) { // 修正判断：蘑菇云在 0.3 秒时停止
                // 计算蘑菇云自身的动画进度 (从 0.0 到 1.0)
                double mushroomElapsed = elapsed - MUSHROOM_START_TIME;
                double mushroomProgress = mushroomElapsed / MUSHROOM_DURATION;

                // 蘑菇云的底部坐标和高度
                double baseY = currentPos.getY();
                double targetHeight = MAX_VISUAL_RADIUS * 1.5;
                double currentHeight = targetHeight * mushroomProgress;
                double cloudTopY = baseY - currentHeight;

                // 蘑菇云的半径（云冠）从底部开始扩张，然后略微收缩
                // 在 0.5 时达到最大，在 0.0 和 1.0 时最小
                double cloudRadius = MAX_VISUAL_RADIUS * 0.7 * (1.0 - Math.abs(mushroomProgress - 0.5));

                // 蘑菇云的透明度（逐渐变淡）
                double cloudAlpha = Math.max(0, 0.8 * (1.0 - mushroomProgress));

                gc.setGlobalAlpha(cloudAlpha);
                gc.setFill(Color.rgb(100, 100, 100)); // 烟尘的灰色

                // 绘制主蘑菇云冠（简化为圆）
                gc.fillOval(currentPos.getX() - cloudRadius,
                        cloudTopY - cloudRadius * 0.5, // 顶部可以更平坦
                        cloudRadius * 2,
                        cloudRadius * 1.5); // 垂直方向拉伸

                // 绘制连接地面的柱子（简化为矩形）
                double columnWidth = MAX_VISUAL_RADIUS * 0.1;
                gc.fillRect(currentPos.getX() - columnWidth / 2,
                        baseY - currentHeight * 0.2, // 柱子从底部升起一小段
                        columnWidth,
                        currentHeight * 0.2); // 柱子高度是总高度的一小部分
            }

            // --- 5. 外部烟雾/灰尘扩散 --- (0 - 0.2秒)
            final double OUTER_DURATION = 0.2;
            if (elapsed < OUTER_DURATION) {
                // 扩散的自身进度 (从 0.0 到 1.0)
                double outerProgress = elapsed / OUTER_DURATION;

                // 透明度：从 0.5 快速降到 0.0
                double outerAlpha = Math.max(0, 0.5 * (1.0 - outerProgress));

                // 半径：扩张到 MAX_VISUAL_RADIUS * 1.0
                double outerRadius = MAX_VISUAL_RADIUS * outerProgress;

                // 线宽：从粗变细 (20 降到 0)
                double outerLineWidth = Math.max(0, 20 * (1.0 - outerProgress));

                gc.setGlobalAlpha(outerAlpha);
                gc.setStroke(Color.rgb(80, 80, 80));
                gc.setLineWidth(outerLineWidth);
                gc.strokeOval(currentPos.getX() - outerRadius, currentPos.getY() - outerRadius, outerRadius * 2,
                        outerRadius * 2);
            }

            gc.restore();
        }

        // C4 没有粒子，所以直接用 elapsed 来判断死亡
        @Override
        public boolean isDead() {
            return (System.currentTimeMillis() / 1000.0) - startTime > duration;
        }
    }

    // /**
    // * 遍历所有玩家，检查其换弹状态是否刚从 'false' 变为 'true'，并在此时播放音效。
    // */
    // private void handleReloadSound() {
    // // 遍历所有玩家（包括僵尸，如果它们能换弹）
    // clientPlayers.values().forEach(this::checkAndPlayReloadSound);
    // clientZombies.values().forEach(this::checkAndPlayReloadSound);
    // }
    //
    // /**
    // * 单个玩家的换弹音效播放逻辑。
    // */
    // private void checkAndPlayReloadSound(ClientPlayer player) {
    // // 获取玩家本地的上一帧状态，以及服务器的当前帧状态
    // boolean currentIsReloading = getBool(player.data, "isReloading");
    //
    // // 假设您在 ClientPlayer 中添加了 public 访问器（getter）来获取 wasReloadingLastFrame
    // // 如果不想添加 getter，就必须把这个检查逻辑移动到 updateDynamic 内部。
    // // *我们选择移动到 updateDynamic 内部，因为那是状态更新的权威位置。*
    //
    // // 播放逻辑已移到 ClientPlayer.updateDynamic，这里只需要确保调用了 updateDynamic
    // // updateStateFromFull 和 updateStateFromSmall 已经调用了 updateFull/Dynamic。
    // }

    // 记录该玩家被声音暴露的过期时间戳

    private boolean isWalking = false;

    /**
     * 用于在屏幕上临时显示声音来源的视觉标记。
     *
     * @param position   声音在世界坐标中的位置。
     * @param expireTime 该标记应消失的系统时间戳。
     * @param type       声音类型 ("FIRE" 或 "RELOAD")，用于区分颜色。
     */
    private record SoundVisualization(Point2D position, long expireTime, String type) {
    }

    /**
     * 存储所有当前需要显示的临时声音标记。
     * 使用 CopyOnWriteArrayList 保证多线程安全（网络线程添加，UI线程渲染和移除）。
     */
    private final List<cs2d.client.GameClient.SoundVisualization> soundVisualizations = new CopyOnWriteArrayList<>();

    /**
     * 绘制所有临时的声音可视化标记 (如枪声)
     */
    private void drawSoundVisualizations() {
        // 首先，一次性安全地移除所有已过期的可视化标记。
        // 这是修改 CopyOnWriteArrayList 这种线程安全列表的正确方法。
        soundVisualizations.removeIf(viz -> System.currentTimeMillis() > viz.expireTime());

        // 然后，遍历列表中剩余的（未过期的）标记，并将它们绘制出来。
        for (cs2d.client.GameClient.SoundVisualization viz : soundVisualizations) {
            long currentTime = System.currentTimeMillis();

            // 将剩余生命周期计算为百分比（从 1.0 向 0.0 递减）
            double progress = Math.max(0.0, (viz.expireTime() - currentTime) / 1000.0);

            gc.save();
            gc.setGlobalAlpha(progress); // 透明度随 progress 降为 0 而淡出

            double x = viz.position().getX();
            double y = viz.position().getY();

            // 根据声音类型设置颜色
            if ("FIRE".equals(viz.type())) {
                gc.setFill(Color.RED);
            } else {
                gc.setFill(Color.YELLOW); // 备用颜色
            }

            // 标记的大小会随着淡出而缩小
            double size = 20 * progress;
            gc.fillPolygon(
                    new double[] { x, x + size / 2, x, x - size / 2 },
                    new double[] { y - size / 2, y, y + size / 2, y },
                    4);

            gc.restore();
        }
    }

    // ==============================
    // 定义一个record来清晰地存储物理属性
    public record WeaponPhysicsData(double penetrationPower, double penetrationCostPerPixel) {
    }

    // 创建一个静态Map来存储所有武器的物理数据
    private static final Map<String, cs2d.client.GameClient.WeaponPhysicsData> WEAPON_PHYSICS_DATA = new ConcurrentHashMap<>();

    static {
        // --- 步枪 (Rifles) - 穿透消耗 1.5 ---
        WEAPON_PHYSICS_DATA.put("AK47", new cs2d.client.GameClient.WeaponPhysicsData(50, 1.5));
        WEAPON_PHYSICS_DATA.put("AK-47", new cs2d.client.GameClient.WeaponPhysicsData(50, 1.5));
        WEAPON_PHYSICS_DATA.put("M4A4", new cs2d.client.GameClient.WeaponPhysicsData(50, 1.5));
        WEAPON_PHYSICS_DATA.put("M4A1S", new cs2d.client.GameClient.WeaponPhysicsData(45, 1.5));
        WEAPON_PHYSICS_DATA.put("M4A1-S", new cs2d.client.GameClient.WeaponPhysicsData(45, 1.5));
        WEAPON_PHYSICS_DATA.put("FAMAS", new cs2d.client.GameClient.WeaponPhysicsData(40, 1.5));
        WEAPON_PHYSICS_DATA.put("GALIL", new cs2d.client.GameClient.WeaponPhysicsData(42, 1.5));
        WEAPON_PHYSICS_DATA.put("Galil AR", new cs2d.client.GameClient.WeaponPhysicsData(42, 1.5));
        WEAPON_PHYSICS_DATA.put("AUG", new cs2d.client.GameClient.WeaponPhysicsData(55, 1.5));
        WEAPON_PHYSICS_DATA.put("SG553", new cs2d.client.GameClient.WeaponPhysicsData(60, 1.5));
        WEAPON_PHYSICS_DATA.put("SG 553", new cs2d.client.GameClient.WeaponPhysicsData(60, 1.5));

        // --- 狙击枪 (Snipers) ---
        WEAPON_PHYSICS_DATA.put("AWP", new cs2d.client.GameClient.WeaponPhysicsData(100, 1.5));
        WEAPON_PHYSICS_DATA.put("SSG08", new cs2d.client.GameClient.WeaponPhysicsData(70, 1.2));
        WEAPON_PHYSICS_DATA.put("SSG 08", new cs2d.client.GameClient.WeaponPhysicsData(70, 1.2));
        WEAPON_PHYSICS_DATA.put("SCAR20", new cs2d.client.GameClient.WeaponPhysicsData(70, 1.5));
        WEAPON_PHYSICS_DATA.put("SCAR-20", new cs2d.client.GameClient.WeaponPhysicsData(70, 1.5));
        WEAPON_PHYSICS_DATA.put("G3SG1", new cs2d.client.GameClient.WeaponPhysicsData(70, 1.5));

        // --- 冲锋枪 (SMGs) - 穿透消耗 2.0 ---
        WEAPON_PHYSICS_DATA.put("MP9", new cs2d.client.GameClient.WeaponPhysicsData(25, 2.0));
        WEAPON_PHYSICS_DATA.put("MAC10", new cs2d.client.GameClient.WeaponPhysicsData(22, 2.0));
        WEAPON_PHYSICS_DATA.put("MAC-10", new cs2d.client.GameClient.WeaponPhysicsData(22, 2.0));
        WEAPON_PHYSICS_DATA.put("MP7", new cs2d.client.GameClient.WeaponPhysicsData(30, 2.0));
        WEAPON_PHYSICS_DATA.put("MP5SD", new cs2d.client.GameClient.WeaponPhysicsData(30, 2.0));
        WEAPON_PHYSICS_DATA.put("MP5-SD", new cs2d.client.GameClient.WeaponPhysicsData(30, 2.0));
        WEAPON_PHYSICS_DATA.put("UMP45", new cs2d.client.GameClient.WeaponPhysicsData(35, 2.0));
        WEAPON_PHYSICS_DATA.put("UMP-45", new cs2d.client.GameClient.WeaponPhysicsData(35, 2.0));
        WEAPON_PHYSICS_DATA.put("P90", new cs2d.client.GameClient.WeaponPhysicsData(30, 2.0));
        WEAPON_PHYSICS_DATA.put("BIZON", new cs2d.client.GameClient.WeaponPhysicsData(18, 2.0));
        WEAPON_PHYSICS_DATA.put("PP-Bizon", new cs2d.client.GameClient.WeaponPhysicsData(18, 2.0));

        // --- 散弹枪 (Shotguns) - 穿透消耗 2.0 ---
        WEAPON_PHYSICS_DATA.put("NOVA", new cs2d.client.GameClient.WeaponPhysicsData(10, 2.0));
        WEAPON_PHYSICS_DATA.put("XM1014", new cs2d.client.GameClient.WeaponPhysicsData(15, 2.0));
        WEAPON_PHYSICS_DATA.put("MAG7", new cs2d.client.GameClient.WeaponPhysicsData(12, 2.0));
        WEAPON_PHYSICS_DATA.put("MAG-7", new cs2d.client.GameClient.WeaponPhysicsData(12, 2.0));
        WEAPON_PHYSICS_DATA.put("SAWEDOFF", new cs2d.client.GameClient.WeaponPhysicsData(8, 2.0));
        WEAPON_PHYSICS_DATA.put("Sawed-Off", new cs2d.client.GameClient.WeaponPhysicsData(8, 2.0));

        // --- 机枪 (LMGs) ---
        WEAPON_PHYSICS_DATA.put("NEGEV", new cs2d.client.GameClient.WeaponPhysicsData(55, 2.3));
        WEAPON_PHYSICS_DATA.put("M249", new cs2d.client.GameClient.WeaponPhysicsData(50, 1.5));

        // --- 手枪 (Pistols) - 穿透消耗 2.0 ---
        WEAPON_PHYSICS_DATA.put("GLOCK18", new cs2d.client.GameClient.WeaponPhysicsData(10, 2.0));
        WEAPON_PHYSICS_DATA.put("Glock-18", new cs2d.client.GameClient.WeaponPhysicsData(10, 2.0));
        WEAPON_PHYSICS_DATA.put("P2000", new cs2d.client.GameClient.WeaponPhysicsData(15, 2.0));
        WEAPON_PHYSICS_DATA.put("USPS", new cs2d.client.GameClient.WeaponPhysicsData(15, 2.0));
        WEAPON_PHYSICS_DATA.put("USP-S", new cs2d.client.GameClient.WeaponPhysicsData(15, 2.0));
        WEAPON_PHYSICS_DATA.put("DUAL_BERETTAS", new cs2d.client.GameClient.WeaponPhysicsData(12, 2.0));
        WEAPON_PHYSICS_DATA.put("Dual Berettas", new cs2d.client.GameClient.WeaponPhysicsData(12, 2.0));
        WEAPON_PHYSICS_DATA.put("P250", new cs2d.client.GameClient.WeaponPhysicsData(20, 2.0));
        WEAPON_PHYSICS_DATA.put("FIVESEVEN", new cs2d.client.GameClient.WeaponPhysicsData(28, 2.0));
        WEAPON_PHYSICS_DATA.put("Five-SeveN", new cs2d.client.GameClient.WeaponPhysicsData(28, 2.0));
        WEAPON_PHYSICS_DATA.put("TEC9", new cs2d.client.GameClient.WeaponPhysicsData(25, 2.0));
        WEAPON_PHYSICS_DATA.put("Tec-9", new cs2d.client.GameClient.WeaponPhysicsData(25, 2.0));
        WEAPON_PHYSICS_DATA.put("CZ75", new cs2d.client.GameClient.WeaponPhysicsData(20, 2.0));
        WEAPON_PHYSICS_DATA.put("CZ75-Auto", new cs2d.client.GameClient.WeaponPhysicsData(20, 2.0));
        WEAPON_PHYSICS_DATA.put("DEAGLE", new cs2d.client.GameClient.WeaponPhysicsData(40, 2.0));
        WEAPON_PHYSICS_DATA.put("Desert Eagle", new cs2d.client.GameClient.WeaponPhysicsData(40, 2.0));
        WEAPON_PHYSICS_DATA.put("R8", new cs2d.client.GameClient.WeaponPhysicsData(90, 1.1));
        WEAPON_PHYSICS_DATA.put("R8 Revolver", new cs2d.client.GameClient.WeaponPhysicsData(90, 1.1));
    }
    // --- ：用于优化渲染的空间网格 ---
    /**
     * 存储静态障碍物的空间网格。
     * 外层数组代表Y轴单元格，内层数组代表X轴单元格。
     * 每个单元格包含一个在该单元格内的障碍物列表。
     */
    // private List<JsonObject>[][] staticObstacleGrid;
    @SuppressWarnings("unchecked") // 抑制创建泛型数组的警告
    private List<JsonObject>[][] staticObstacleGrid = (List<JsonObject>[][]) new ArrayList<?>[0][0]; // 初始化为空数组
    private int gridCellSize = 500; // 网格单元大小（像素），可以调整
    private int gridWidthInCells;
    private int gridHeightInCells;

    /**
     * 初始化静态障碍物的 Quadtree。
     * 在收到 mapData 后调用一次。
     */

    private void initializeQuadtree() {
        System.out.println("[CLIENT DEBUG] Entering initializeQuadtree...");
        if (mapData == null || !mapData.has("obstacles") || !mapData.has("width") || !mapData.has("height")) {
            quadtreeRootNode = null;
            System.err.println("[CLIENT DEBUG] initializeQuadtree FAILED: mapData is null or missing required fields!");
            System.err.println("[CLIENT DEBUG] mapData content: " + (mapData != null
                    ? mapData.toString().substring(0, Math.min(mapData.toString().length(), 100)) + "..."
                    : "null"));
            System.out.println("[FOV 优化] 地图数据无效或无障碍物，Quadtree 未构建。");
            return;
        }

        double mapWidth = getDouble(mapData, "width");
        double mapHeight = getDouble(mapData, "height");
        JsonArray obstacles = mapData.getAsJsonArray("obstacles");

        // 创建覆盖整个地图的根节点
        Rectangle2D mapBounds = new Rectangle2D(0, 0, mapWidth, mapHeight);
        quadtreeRootNode = new QuadtreeNode(0, mapBounds, QUADTREE_MAX_OBJECTS, QUADTREE_MAX_DEPTH);

        System.out.println("[FOV 优化] 正在为 " + obstacles.size() + " 个障碍物构建 Quadtree...");

        // --- [核心修改] ---
        // 将所有静态障碍物插入树中
        obstacles.forEach(obsEl -> {
            if (obsEl.isJsonObject()) {
                JsonObject obsJson = obsEl.getAsJsonObject();

                // 1. [计算一次] 获取边界
                Rectangle2D bounds = cs2d.client.GameClient.getObstacleBounds(obsJson);
                if (bounds == null)
                    return; // 跳过无效障碍物

                // 2. [计算一次] 提取边缘
                List<Point2D[]> edges = extractEdgesFromObstacle(obsJson);

                // 3. 创建缓存对象
                cs2d.client.GameClient.StaticObstacle staticObs = new cs2d.client.GameClient.StaticObstacle(obsJson,
                        bounds, edges);

                // 4. 将 *缓存对象* 插入四叉树
                quadtreeRootNode.insert(staticObs);
            }
        });
        // --- [修改结束] ---

        if (quadtreeRootNode != null) {
            System.out.println("[CLIENT DEBUG] initializeQuadtree SUCCESS: quadtreeRootNode created.");
        } else {
            System.err.println("[CLIENT DEBUG] initializeQuadtree FAILED: quadtreeRootNode ended up null!");
        }
        System.out.println("[FOV 优化] Quadtree 构建完成。");
        createObstacleCache();
    }

    /** 单个静态障碍物缓存块，坐标均为世界坐标。 */
    private record ObstacleCacheTile(double x, double y, Rectangle2D bounds, Image image,
            int pixelWidth, int pixelHeight, int[] argbPrePixels) {
    }

    private record ResidentObstacleAtlasNode(Rectangle2D bounds, ImageView view) {
    }

    private static final class ViewportPixelSlot {
        final int[] pixels;
        final PixelBuffer<IntBuffer> pixelBuffer;
        final WritableImage image;

        ViewportPixelSlot(int width, int height) {
            pixels = new int[width * height];
            pixelBuffer = new PixelBuffer<>(width, height, IntBuffer.wrap(pixels),
                    PixelFormat.getIntArgbPreInstance());
            image = new WritableImage(pixelBuffer);
        }
    }

    private static final class ViewportBufferSet {
        final int width;
        final int height;
        final ViewportPixelSlot[] slots;
        final AtomicInteger frontIndex = new AtomicInteger(0);

        ViewportBufferSet(int width, int height) {
            this.width = width;
            this.height = height;
            this.slots = new ViewportPixelSlot[] {
                    new ViewportPixelSlot(width, height), new ViewportPixelSlot(width, height)
            };
        }
    }

    private record ViewportBuildRequest(long sequence, String mapSignature,
            double originX, double originY, double width, double height,
            ViewportBufferSet buffers, List<ObstacleCacheTile> tiles) {
    }

    /**
     * 将静态障碍物烘焙成小纹理块。旧实现创建4392x3840整图Canvas并再snapshot一份，
     * 超过JavaFX 4096纹理限制且瞬时占用数百MB显存，重复map_data时会触发RTTexture空指针。
     */
    private void createObstacleCache() {
        if (mapData == null || quadtreeRootNode == null)
            return;

        double mapWidth = getDouble(mapData, "width");
        double mapHeight = getDouble(mapData, "height");
        obstacleCacheTiles.clear();
        int columns = Math.max(1, (int) Math.ceil(mapWidth / OBSTACLE_CACHE_TILE_SIZE));
        int rows = Math.max(1, (int) Math.ceil(mapHeight / OBSTACLE_CACHE_TILE_SIZE));
        System.out.printf("[缓存] 正在创建 %.0fx%.0f 障碍物分块缓存 (%dx%d, tile=%d)...%n",
                mapWidth, mapHeight, columns, rows, OBSTACLE_CACHE_TILE_SIZE);

        javafx.scene.SnapshotParameters params = new javafx.scene.SnapshotParameters();
        params.setFill(Color.TRANSPARENT);
        Canvas tileCanvas = new Canvas(OBSTACLE_CACHE_TILE_SIZE, OBSTACLE_CACHE_TILE_SIZE);
        GraphicsContext tileGc = tileCanvas.getGraphicsContext2D();
        int bakedObstacleReferences = 0;

        for (int row = 0; row < rows; row++) {
            double tileY = row * (double) OBSTACLE_CACHE_TILE_SIZE;
            double tileHeight = Math.min(OBSTACLE_CACHE_TILE_SIZE, mapHeight - tileY);
            for (int column = 0; column < columns; column++) {
                double tileX = column * (double) OBSTACLE_CACHE_TILE_SIZE;
                double tileWidth = Math.min(OBSTACLE_CACHE_TILE_SIZE, mapWidth - tileX);
                Rectangle2D tileBounds = new Rectangle2D(tileX, tileY, tileWidth, tileHeight);

                List<cs2d.client.GameClient.StaticObstacle> queried = new ArrayList<>();
                quadtreeRootNode.queryBounds(queried, tileBounds);
                Set<cs2d.client.GameClient.StaticObstacle> tileObstacles = new HashSet<>(queried);

                tileGc.setTransform(1, 0, 0, 1, 0, 0);
                tileGc.clearRect(0, 0, OBSTACLE_CACHE_TILE_SIZE, OBSTACLE_CACHE_TILE_SIZE);
                tileGc.setFill(Color.web("#4a5568"));
                tileGc.translate(-tileX, -tileY);
                for (cs2d.client.GameClient.StaticObstacle obstacle : tileObstacles)
                    drawObstacle(tileGc, obstacle.originalJson);
                tileGc.setTransform(1, 0, 0, 1, 0, 0);

                params.setViewport(new Rectangle2D(0, 0, tileWidth, tileHeight));
                Image tileImage = tileCanvas.snapshot(params, null);
                if (tileImage != null) {
                    int pixelWidth = Math.max(1, (int) Math.ceil(tileWidth));
                    int pixelHeight = Math.max(1, (int) Math.ceil(tileHeight));
                    int[] pixels = new int[pixelWidth * pixelHeight];
                    tileImage.getPixelReader().getPixels(0, 0, pixelWidth, pixelHeight,
                            PixelFormat.getIntArgbPreInstance(), pixels, 0, pixelWidth);
                    PixelBuffer<IntBuffer> tilePixelBuffer = new PixelBuffer<>(pixelWidth, pixelHeight,
                            IntBuffer.wrap(pixels), PixelFormat.getIntArgbPreInstance());
                    WritableImage residentTileImage = new WritableImage(tilePixelBuffer);
                    obstacleCacheTiles.add(new ObstacleCacheTile(tileX, tileY, tileBounds, residentTileImage,
                            pixelWidth, pixelHeight, pixels));
                    bakedObstacleReferences += tileObstacles.size();
                }
            }
        }

        obstacleOverviewImage = createObstacleOverview(mapWidth, mapHeight);
        rebuildResidentStaticMapLayer(mapWidth, mapHeight);
        System.out.printf("[缓存] 分块烘焙完成: %d块, 障碍物块引用%d。%n",
                obstacleCacheTiles.size(), bakedObstacleReferences);
    }

    private void rebuildResidentStaticMapLayer(double mapWidth, double mapHeight) {
        if (!USE_RESIDENT_STATIC_MAP_LAYER || residentStaticMapLayer == null)
            return;

        residentStaticMapReady = false;
        clearResidentStaticAtlas();
        Rectangle background = new Rectangle(0, 0, mapWidth, mapHeight);
        background.setFill(CARD_BACKGROUND);
        Group atlasLayer = new Group();
        atlasLayer.setManaged(false);
        buildResidentStaticAtlas(mapWidth, mapHeight, atlasLayer);

        ImageView overviewView = null;
        if (obstacleOverviewImage != null) {
            overviewView = new ImageView(obstacleOverviewImage);
            overviewView.setFitWidth(mapWidth);
            overviewView.setFitHeight(mapHeight);
            overviewView.setPreserveRatio(false);
            overviewView.setManaged(false);
            overviewView.setVisible(false);
        }

        residentObstacleAtlasLayer = atlasLayer;
        residentObstacleOverviewView = overviewView;
        residentStaticMapLayer.getChildren().clear();
        residentStaticMapLayer.getChildren().add(background);
        residentStaticMapLayer.getChildren().add(atlasLayer);
        if (overviewView != null)
            residentStaticMapLayer.getChildren().add(overviewView);
        residentStaticMapReady = true;
        System.out.printf("[Render] Resident static map ready: %d fixed atlas nodes, overview=%s%n",
                residentObstacleAtlasNodes.size(), overviewView != null);
    }

    private void buildResidentStaticAtlas(double mapWidth, double mapHeight, Group atlasLayer) {
        int columns = atlasAxisRegionCount(mapWidth);
        int rows = atlasAxisRegionCount(mapHeight);
        for (int row = 0; row < rows; row++) {
            int atlasY = row * OBSTACLE_ATLAS_MAX_SIZE;
            int atlasHeight = Math.max(1, (int) Math.ceil(
                    Math.min(OBSTACLE_ATLAS_MAX_SIZE, mapHeight - atlasY)));
            for (int column = 0; column < columns; column++) {
                int atlasX = column * OBSTACLE_ATLAS_MAX_SIZE;
                int atlasWidth = Math.max(1, (int) Math.ceil(
                        Math.min(OBSTACLE_ATLAS_MAX_SIZE, mapWidth - atlasX)));
                int[] pixels = new int[atlasWidth * atlasHeight];
                int copiedTiles = copyObstacleTilesIntoAtlas(
                        atlasX, atlasY, atlasWidth, atlasHeight, pixels);
                PixelBuffer<IntBuffer> pixelBuffer = new PixelBuffer<>(atlasWidth, atlasHeight,
                        IntBuffer.wrap(pixels), PixelFormat.getIntArgbPreInstance());
                WritableImage image = new WritableImage(pixelBuffer);
                ImageView view = new ImageView(image);
                view.setX(atlasX);
                view.setY(atlasY);
                view.setManaged(false);
                view.setMouseTransparent(true);
                Rectangle2D bounds = new Rectangle2D(atlasX, atlasY, atlasWidth, atlasHeight);
                atlasLayer.getChildren().add(view);
                residentObstacleAtlasNodes.add(new ResidentObstacleAtlasNode(bounds, view));
                perfStaticAtlasBuilds++;
                System.out.printf("[缓存] 固定图集 %d,%d: %dx%d, 合并%d块%n",
                        column, row, atlasWidth, atlasHeight, copiedTiles);
            }
        }
    }

    static int atlasAxisRegionCount(double mapExtent) {
        if (!Double.isFinite(mapExtent) || mapExtent <= 0.0)
            return 1;
        return Math.max(1, (int) Math.ceil(mapExtent / OBSTACLE_ATLAS_MAX_SIZE));
    }

    static int atlasRegionCount(double mapWidth, double mapHeight) {
        return atlasAxisRegionCount(mapWidth) * atlasAxisRegionCount(mapHeight);
    }

    private int copyObstacleTilesIntoAtlas(int atlasX, int atlasY, int atlasWidth, int atlasHeight,
            int[] target) {
        int maxX = atlasX + atlasWidth;
        int maxY = atlasY + atlasHeight;
        int copiedTiles = 0;
        for (ObstacleCacheTile tile : obstacleCacheTiles) {
            int tileX = (int) tile.x();
            int tileY = (int) tile.y();
            int copyMinX = Math.max(atlasX, tileX);
            int copyMinY = Math.max(atlasY, tileY);
            int copyMaxX = Math.min(maxX, tileX + tile.pixelWidth());
            int copyMaxY = Math.min(maxY, tileY + tile.pixelHeight());
            int copyWidth = copyMaxX - copyMinX;
            int copyHeight = copyMaxY - copyMinY;
            if (copyWidth <= 0 || copyHeight <= 0)
                continue;
            int sourceX = copyMinX - tileX;
            int sourceY = copyMinY - tileY;
            int destinationX = copyMinX - atlasX;
            int destinationY = copyMinY - atlasY;
            for (int copyRow = 0; copyRow < copyHeight; copyRow++) {
                System.arraycopy(tile.argbPrePixels(),
                        (sourceY + copyRow) * tile.pixelWidth() + sourceX,
                        target, (destinationY + copyRow) * atlasWidth + destinationX,
                        copyWidth);
            }
            copiedTiles++;
        }
        return copiedTiles;
    }

    private boolean isResidentStaticMapLayerReady() {
        return USE_RESIDENT_STATIC_MAP_LAYER && residentStaticMapReady && residentStaticMapLayer != null;
    }

    private void updateResidentStaticMapLayer() {
        if (!USE_RESIDENT_STATIC_MAP_LAYER || residentStaticMapLayer == null)
            return;

        boolean ready = isResidentStaticMapLayerReady();
        setVisibleIfChanged(residentStaticMapLayer, ready);
        if (!ready)
            return;

        boolean useOverview = "full".equals(cameraMode) && residentObstacleOverviewView != null;
        double safeScale = Math.max(camera.scale, 0.0001);
        double padding = 2.0 / safeScale;
        double minX = camera.x - camera.offsetX / safeScale - padding;
        double minY = camera.y - camera.offsetY / safeScale - padding;
        double maxX = camera.x + (CANVAS_WIDTH - camera.offsetX) / safeScale + padding;
        double maxY = camera.y + (CANVAS_HEIGHT - camera.offsetY) / safeScale + padding;
        setVisibleIfChanged(residentObstacleAtlasLayer, !useOverview);
        if (residentObstacleOverviewView != null)
            setVisibleIfChanged(residentObstacleOverviewView, useOverview);

        if (useOverview) {
            residentVisibleTileCount = 0;
            residentStaticMapMode = "overview";
        } else {
            residentStaticMapMode = "atlas";
            int visibleTiles = 0;
            for (ResidentObstacleAtlasNode atlasNode : residentObstacleAtlasNodes) {
                Rectangle2D bounds = atlasNode.bounds();
                boolean visible = boundsIntersect(bounds.getMinX(), bounds.getMinY(), bounds.getMaxX(), bounds.getMaxY(),
                        minX, minY, maxX, maxY);
                setVisibleIfChanged(atlasNode.view(), visible);
                if (visible)
                    visibleTiles++;
            }
            residentVisibleTileCount = visibleTiles;
        }

        double translateX = staticMapLayerTranslation(camera.x, camera.scale, camera.offsetX);
        double translateY = staticMapLayerTranslation(camera.y, camera.scale, camera.offsetY);
        if (Double.compare(residentStaticMapTransform.getMxx(), camera.scale) != 0
                || Double.compare(residentStaticMapTransform.getMyy(), camera.scale) != 0
                || Double.compare(residentStaticMapTransform.getTx(), translateX) != 0
                || Double.compare(residentStaticMapTransform.getTy(), translateY) != 0) {
            residentStaticMapTransform.setToTransform(
                    camera.scale, 0.0, translateX,
                    0.0, camera.scale, translateY);
        }
    }

    private boolean ensureResidentObstacleViewport(double viewMinX, double viewMinY,
            double viewMaxX, double viewMaxY) {
        if (residentObstacleViewportFrontView == null || residentObstacleViewportBackView == null
                || mapData == null || obstacleCacheTiles.isEmpty())
            return false;
        double mapWidth = getDouble(mapData, "width");
        double mapHeight = getDouble(mapData, "height");
        viewMinX = Math.max(0.0, viewMinX);
        viewMinY = Math.max(0.0, viewMinY);
        viewMaxX = Math.min(mapWidth, viewMaxX);
        viewMaxY = Math.min(mapHeight, viewMaxY);
        double viewWidth = viewMaxX - viewMinX;
        double viewHeight = viewMaxY - viewMinY;
        if (viewWidth <= 0.0 || viewHeight <= 0.0
                || viewWidth > OBSTACLE_VIEWPORT_CACHE_SIZE || viewHeight > OBSTACLE_VIEWPORT_CACHE_SIZE)
            return false;

        double cacheWidth = adaptiveViewportSize(viewWidth, mapWidth);
        double cacheHeight = adaptiveViewportSize(viewHeight, mapHeight);
        if (residentViewportValid && viewportContainsWithMargin(
                residentViewportOriginX, residentViewportOriginY,
                residentViewportWidth, residentViewportHeight,
                viewMinX, viewMinY, viewMaxX, viewMaxY,
                mapWidth, mapHeight, OBSTACLE_VIEWPORT_REBUILD_MARGIN)) {
            return true;
        }

        double originX = clampViewportOrigin(Math.floor((viewMinX + viewMaxX - cacheWidth) * 0.5),
                mapWidth, cacheWidth);
        double originY = clampViewportOrigin(Math.floor((viewMinY + viewMaxY - cacheHeight) * 0.5),
                mapHeight, cacheHeight);
        requestResidentObstacleViewportBuild(originX, originY, cacheWidth, cacheHeight);
        // 相机仍在旧纹理真实覆盖范围内时继续显示前缓冲；后台完成后再无缝切换。
        return residentViewportValid && viewportContainsWithMargin(
                residentViewportOriginX, residentViewportOriginY,
                residentViewportWidth, residentViewportHeight,
                viewMinX, viewMinY, viewMaxX, viewMaxY,
                mapWidth, mapHeight, 0.0);
    }

    private void requestResidentObstacleViewportBuild(double originX, double originY,
            double width, double height) {
        if (width <= 0 || height <= 0)
            return;
        int pixelWidth = Math.max(1, (int) Math.ceil(width));
        int pixelHeight = Math.max(1, (int) Math.ceil(height));
        ViewportBufferSet buffers = residentViewportBuffers;
        if (buffers == null || buffers.width != pixelWidth || buffers.height != pixelHeight) {
            buffers = new ViewportBufferSet(pixelWidth, pixelHeight);
            residentViewportBuffers = buffers;
        }
        ViewportBuildRequest request = new ViewportBuildRequest(viewportBuildSequence.incrementAndGet(),
                initializedMapSignature, originX, originY, width, height,
                buffers, List.copyOf(obstacleCacheTiles));
        if (sameViewportBuild(activeViewportBuild, request)
                || sameViewportBuild(pendingViewportBuild.get(), request))
            return;
        ViewportBuildRequest replaced = pendingViewportBuild.getAndSet(request);
        if (replaced != null)
            perfStaticViewportBuildReplacements.increment();
        ensureViewportWorkerRunning();
    }

    private void ensureViewportWorkerRunning() {
        if (!viewportWorkerRunning.compareAndSet(false, true))
            return;
        try {
            staticViewportExecutor.execute(this::composeLatestViewportInBackground);
        } catch (RejectedExecutionException ignored) {
            viewportWorkerRunning.set(false);
        }
    }

    private void composeLatestViewportInBackground() {
        ViewportBuildRequest request = pendingViewportBuild.getAndSet(null);
        if (request == null) {
            viewportWorkerRunning.set(false);
            return;
        }
        activeViewportBuild = request;
        long start = System.nanoTime();
        ViewportBufferSet buffers = request.buffers();
        int backIndex = 1 - buffers.frontIndex.get();
        int[] target = buffers.slots[backIndex].pixels;
        Arrays.fill(target, 0);
        int blits = composeViewportPixels(request, target, buffers.width, buffers.height);
        long elapsed = System.nanoTime() - start;
        Platform.runLater(() -> publishComposedViewport(request, backIndex, blits, elapsed));
    }

    private static int composeViewportPixels(ViewportBuildRequest request, int[] target,
            int targetWidth, int targetHeight) {
        int originX = (int) request.originX();
        int originY = (int) request.originY();
        int maxX = originX + targetWidth;
        int maxY = originY + targetHeight;
        int blits = 0;
        for (ObstacleCacheTile tile : request.tiles()) {
            int tileX = (int) tile.x();
            int tileY = (int) tile.y();
            int copyMinX = Math.max(originX, tileX);
            int copyMinY = Math.max(originY, tileY);
            int copyMaxX = Math.min(maxX, tileX + tile.pixelWidth());
            int copyMaxY = Math.min(maxY, tileY + tile.pixelHeight());
            int copyWidth = copyMaxX - copyMinX;
            int copyHeight = copyMaxY - copyMinY;
            if (copyWidth <= 0 || copyHeight <= 0)
                continue;
            int sourceX = copyMinX - tileX;
            int sourceY = copyMinY - tileY;
            int destinationX = copyMinX - originX;
            int destinationY = copyMinY - originY;
            for (int row = 0; row < copyHeight; row++) {
                System.arraycopy(tile.argbPrePixels(), (sourceY + row) * tile.pixelWidth() + sourceX,
                        target, (destinationY + row) * targetWidth + destinationX, copyWidth);
            }
            blits++;
        }
        return blits;
    }

    private void publishComposedViewport(ViewportBuildRequest request, int backIndex,
            int blits, long elapsedNanos) {
        try {
            if (residentViewportBuffers != request.buffers()
                    || !Objects.equals(initializedMapSignature, request.mapSignature()))
                return;
            ViewportPixelSlot slot = request.buffers().slots[backIndex];
            slot.pixelBuffer.updateBuffer(ignored -> null);
            ImageView backView = backIndex == 0 ? residentObstacleViewportFrontView : residentObstacleViewportBackView;
            ImageView frontView = backIndex == 0 ? residentObstacleViewportBackView : residentObstacleViewportFrontView;
            backView.setImage(slot.image);
            backView.setX(request.originX());
            backView.setY(request.originY());
            setVisibleIfChanged(backView, residentViewportActive);
            setVisibleIfChanged(frontView, false);
            request.buffers().frontIndex.set(backIndex);
            residentViewportOriginX = request.originX();
            residentViewportOriginY = request.originY();
            residentViewportWidth = request.width();
            residentViewportHeight = request.height();
            residentViewportValid = true;
            residentVisibleTileCount = blits;
            perfStaticViewportRebuilds++;
            perfStaticViewportTileBlits += blits;
            perfStaticViewportBackgroundNanos.add(elapsedNanos);
        } finally {
            activeViewportBuild = null;
            viewportWorkerRunning.set(false);
            if (pendingViewportBuild.get() != null)
                ensureViewportWorkerRunning();
        }
    }

    private static boolean sameViewportBuild(ViewportBuildRequest left, ViewportBuildRequest right) {
        return left != null && right != null && left.buffers() == right.buffers()
                && Double.compare(left.originX(), right.originX()) == 0
                && Double.compare(left.originY(), right.originY()) == 0
                && Double.compare(left.width(), right.width()) == 0
                && Double.compare(left.height(), right.height()) == 0
                && Objects.equals(left.mapSignature(), right.mapSignature());
    }

    private void setViewportViewsVisible(boolean visible) {
        ViewportBufferSet buffers = residentViewportBuffers;
        if (!visible || buffers == null || !residentViewportValid) {
            setVisibleIfChanged(residentObstacleViewportFrontView, false);
            setVisibleIfChanged(residentObstacleViewportBackView, false);
            return;
        }
        int frontIndex = buffers.frontIndex.get();
        setVisibleIfChanged(residentObstacleViewportFrontView, frontIndex == 0);
        setVisibleIfChanged(residentObstacleViewportBackView, frontIndex == 1);
    }

    private void clearResidentStaticAtlas() {
        residentObstacleAtlasNodes.clear();
        if (residentObstacleAtlasLayer != null)
            residentObstacleAtlasLayer.getChildren().clear();
        residentObstacleAtlasLayer = null;
        residentVisibleTileCount = 0;
    }

    private void invalidateResidentStaticViewport() {
        viewportBuildSequence.incrementAndGet();
        pendingViewportBuild.set(null);
        activeViewportBuild = null;
        residentViewportValid = false;
        residentViewportActive = false;
        residentStaticMapMode = "canvas";
        if (residentObstacleViewportFrontView != null) {
            residentObstacleViewportFrontView.setVisible(false);
            residentObstacleViewportFrontView.setImage(null);
        }
        if (residentObstacleViewportBackView != null) {
            residentObstacleViewportBackView.setVisible(false);
            residentObstacleViewportBackView.setImage(null);
        }
        residentViewportBuffers = null;
    }

    static double adaptiveViewportSize(double viewSize, double mapSize) {
        double requested = viewSize + OBSTACLE_VIEWPORT_SAFETY_PADDING * 2.0;
        double quantized = Math.ceil(requested / OBSTACLE_VIEWPORT_SIZE_QUANTUM)
                * OBSTACLE_VIEWPORT_SIZE_QUANTUM;
        return Math.min(mapSize, Math.min(OBSTACLE_VIEWPORT_CACHE_SIZE, quantized));
    }

    static double clampViewportOrigin(double desiredOrigin, double mapSize, double cacheSize) {
        return Math.max(0.0, Math.min(Math.max(0.0, mapSize - cacheSize), desiredOrigin));
    }

    static boolean viewportContainsWithMargin(double originX, double originY, double width, double height,
            double viewMinX, double viewMinY, double viewMaxX, double viewMaxY,
            double mapWidth, double mapHeight, double margin) {
        double leftMargin = originX > 0.0 ? margin : 0.0;
        double topMargin = originY > 0.0 ? margin : 0.0;
        double rightMargin = originX + width < mapWidth ? margin : 0.0;
        double bottomMargin = originY + height < mapHeight ? margin : 0.0;
        return viewMinX >= originX + leftMargin && viewMinY >= originY + topMargin
                && viewMaxX <= originX + width - rightMargin
                && viewMaxY <= originY + height - bottomMargin;
    }

    static double staticMapLayerTranslation(double cameraOrigin, double scale, double offset) {
        return offset - cameraOrigin * scale;
    }

    static boolean boundsIntersect(double minX1, double minY1, double maxX1, double maxY1,
            double minX2, double minY2, double maxX2, double maxY2) {
        return maxX1 >= minX2 && maxX2 >= minX1 && maxY1 >= minY2 && maxY2 >= minY1;
    }

    /** 为全图模式生成一张不超过2048的屏幕级概览纹理，高清跟随模式仍使用原始分块。 */
    private Image createObstacleOverview(double mapWidth, double mapHeight) {
        if (mapWidth <= OBSTACLE_CACHE_TILE_SIZE && mapHeight <= OBSTACLE_CACHE_TILE_SIZE)
            return null; // 小地图本来就只有一个纹理块

        double overviewScale = Math.min(1.0,
                OBSTACLE_OVERVIEW_MAX_SIZE / Math.max(mapWidth, mapHeight));
        double overviewWidth = Math.max(1.0, Math.ceil(mapWidth * overviewScale));
        double overviewHeight = Math.max(1.0, Math.ceil(mapHeight * overviewScale));
        Canvas overviewCanvas = new Canvas(overviewWidth, overviewHeight);
        GraphicsContext overviewGc = overviewCanvas.getGraphicsContext2D();
        overviewGc.setFill(Color.web("#4a5568"));
        overviewGc.scale(overviewScale, overviewScale);

        List<cs2d.client.GameClient.StaticObstacle> queried = new ArrayList<>();
        quadtreeRootNode.queryBounds(queried, new Rectangle2D(0, 0, mapWidth, mapHeight));
        Set<cs2d.client.GameClient.StaticObstacle> allObstacles = new HashSet<>(queried);
        for (cs2d.client.GameClient.StaticObstacle obstacle : allObstacles)
            drawObstacle(overviewGc, obstacle.originalJson);

        javafx.scene.SnapshotParameters overviewParams = new javafx.scene.SnapshotParameters();
        overviewParams.setFill(Color.TRANSPARENT);
        Image overview = overviewCanvas.snapshot(overviewParams, null);
        System.out.printf("[缓存] 全图概览纹理: %.0fx%.0f, 单次提交覆盖%d个障碍物。%n",
                overviewWidth, overviewHeight, allObstacles.size());
        return overview;
    }

    /**
     * 辅助方法：获取障碍物的边界框 (已修正 Rectangle2D 用法)
     */
    public static Rectangle2D getObstacleBounds(JsonObject obs) {
        String type = getString(obs, "type");
        double x = getDouble(obs, "x"), y = getDouble(obs, "y");
        double w = getDouble(obs, "w"), h = getDouble(obs, "h"); // 对矩形/椭圆

        switch (type) {
            case "RECTANGLE":
            case "ELLIPSE": // 椭圆也用其外接矩形
                // *** 修正：直接使用 javafx.geometry.Rectangle2D ***
                return new Rectangle2D(x, y, w, h);
            case "POLYGON":
                if (!obs.has("xPoints"))
                    return null;
                JsonArray xP = obs.getAsJsonArray("xPoints");
                JsonArray yP = obs.getAsJsonArray("yPoints");
                if (xP.isEmpty() || xP.size() != yP.size())
                    return null; // 添加检查

                double minX = xP.get(0).getAsDouble(), maxX = minX;
                double minY = yP.get(0).getAsDouble(), maxY = minY;
                for (int i = 1; i < xP.size(); i++) {
                    double px = xP.get(i).getAsDouble();
                    double py = yP.get(i).getAsDouble();
                    if (px < minX)
                        minX = px;
                    if (px > maxX)
                        maxX = px;
                    if (py < minY)
                        minY = py;
                    if (py > maxY)
                        maxY = py;
                }
                // *** 修正：直接使用 javafx.geometry.Rectangle2D ***
                return new Rectangle2D(minX, minY, maxX - minX, maxY - minY);
            default:
                return null;
        }
    }

    /**
     * 将缩放系数 (例如 0.5 到 3.0) 映射回滑块值 (例如 -1.0 到 2.0)。
     * 用于初始化滑块。
     */
    private double calculateSliderValue(double zoomFactor) {
        if (zoomFactor >= 1.0) {
            // 缩小 (zoomFactor > 1.0) -> 滑块值 > 0.0
            // zoomFactor = 1.0 + sliderValue => sliderValue = zoomFactor - 1.0
            return zoomFactor - 1.0;
        } else {
            // 放大 (zoomFactor < 1.0) -> 滑块值 < 0.0
            // zoomFactor = 1.0 / (1.0 - sliderValue) => 1.0 / zoomFactor = 1.0 -
            // sliderValue => sliderValue = 1.0 - (1.0 / zoomFactor)
            // 限制 zoomFactor 最小为 0.25 (对应 slider -3.0，但我们滑块最小是 -1.0)
            if (zoomFactor <= 0)
                zoomFactor = 0.25; // 防止除零
            return 1.0 - (1.0 / zoomFactor);
        }
    }

    /**
     * 将滑块值 (例如 -1.0 到 2.0) 映射为缩放系数 (例如 0.5 到 3.0)。
     * 用于更新设置。
     */
    private double calculateZoomFactor(double sliderValue) {
        if (sliderValue >= 0) {
            // 缩小 (滑块值 >= 0) -> 系数 >= 1.0
            // 系数 = 1.0 + 滑块值
            return 1.0 + sliderValue;
        } else {
            // 放大 (滑块值 < 0) -> 系数 < 1.0
            // 系数 = 1.0 / (1.0 - 滑块值)
            // 例如 slider = -0.5 => factor = 1 / (1 - (-0.5)) = 1 / 1.5 = 0.66
            // 例如 slider = -1.0 => factor = 1 / (1 - (-1.0)) = 1 / 2.0 = 0.5
            return 1.0 / (1.0 - sliderValue);
        }
    }

    // private boolean wasAliveLastFrame = true; // <-- 你本来就有的，在主循环末尾更新
    private String lastRoundPhase = "INITIAL";
    private int lastWave = -1;
    private boolean wasNextWaveComingLastFrame = false; // 用于检测 nextWaveIn 是否刚出现

    /**
     * 检查游戏状态，根据模式和时机决定是否清除或显示伤害日志。
     */
    private void triggerDamageLogDisplayIfNeeded() {
        if (latestGameState != null && me != null && me.data != null) {
            String mode = getString(latestGameState, "mode");
            boolean isAliveThisFrame = getBool(me.data, "isAlive");

            // --- 清除时机 ---
            // TDM 重生时清除
            if (!wasAliveLastFrame && isAliveThisFrame && "TEAM_DEATHMATCH".equals(mode)) {
                if (!currentDamageLogEntries.isEmpty()) {
                    currentDamageLogEntries.clear();
                    hideDamageLogDisplay();
                }
            }
            // 爆破模式新回合开始时清除 (当从 ROUND_OVER 变为其他状态时，或者从初始状态进入)
            String currentRoundPhase = getString(latestGameState, "roundPhase");
            boolean isNewRoundDemolition = !"ROUND_OVER".equals(currentRoundPhase) &&
                    ("ROUND_OVER".equals(lastRoundPhase) || "INITIAL".equals(lastRoundPhase));

            if ("DEMOLITION".equals(mode) && isNewRoundDemolition) {
                if (!currentDamageLogEntries.isEmpty()) {
                    currentDamageLogEntries.clear();
                    hideDamageLogDisplay();
                }
            }
            // 僵尸模式新一波实际开始时清除 (波数增加时)
            int currentWave = getInt(latestGameState, "wave");
            if ("ZOMBIE_MODE".equals(mode) && currentWave > lastWave && lastWave != -1) {
                if (!currentDamageLogEntries.isEmpty()) {
                    currentDamageLogEntries.clear();
                    hideDamageLogDisplay();
                }
            }

            // --- 显示时机 ---
            boolean nextWaveComing = latestGameState.has("nextWaveIn"); // 当前帧是否有倒计时

            // TDM: 死亡时显示
            if ("TEAM_DEATHMATCH".equals(mode)) {
                if (wasAliveLastFrame && !isAliveThisFrame) {
                    displayDamageLog();
                }
            }
            // 僵尸: 等待下一波开始的倒计时期间显示
            else if ("ZOMBIE_MODE".equals(mode)) {
                // *** 修改后的核心逻辑 ***
                // 当 nextWaveIn 刚刚出现时 (即 !wasNextWaveComingLastFrame && nextWaveComing)
                // 就触发一次显示。displayDamageLog 内部自带10秒计时器会自动隐藏。
                if (nextWaveComing && !wasNextWaveComingLastFrame) {
                    // System.out.println("[客户端调试] 检测到 nextWaveIn 出现，触发伤害日志显示 (ZOMBIE_MODE)");
                    displayDamageLog();
                }
            }
            // 爆破: 回合结束时显示
            else if ("DEMOLITION".equals(mode)) {
                // 如果上一帧不是回合结束，而这一帧是了
                if ("ROUND_OVER".equals(currentRoundPhase) && !"ROUND_OVER".equals(lastRoundPhase)) {
                    displayDamageLog(); // 显示日志 (会一直显示直到新回合开始被清除)
                }
            }

            // --- 在方法末尾更新状态变量 ---
            lastRoundPhase = currentRoundPhase;
            lastWave = currentWave;
            wasNextWaveComingLastFrame = nextWaveComing; // 更新上一帧是否有倒计时

        } else if (!currentDamageLogEntries.isEmpty()) {
            // 如果没有状态了 (比如断线)，也清除一下日志
            currentDamageLogEntries.clear();
            hideDamageLogDisplay();
        }

        // // 更新上一帧存活状态，必须放在所有逻辑之后
        // if (me != null && me.data != null) {
        // wasAliveLastFrame = getBool(me.data, "isAlive");
        // } else {
        // wasAliveLastFrame = false; // 如果 me 为 null，视为上一帧非存活
        // }
    }

    /**
     * 根据当前游戏模式显示伤害日志。
     */
    private void displayDamageLog() {
        // 基本检查保持不变
        if (currentDamageLogEntries.isEmpty() || latestGameState == null) {
            hideDamageLogDisplay();
            return;
        }

        String mode = getString(latestGameState, "mode");
        List<Node[]> formattedRows; // <-- 更改这里的类型

        // 选择正确的格式化函数 (逻辑保持不变)
        if ("DEMOLITION".equals(mode)) {
            formattedRows = formatDetailedDamageLog(currentDamageLogEntries);
        } else if ("TEAM_DEATHMATCH".equals(mode)) {
            formattedRows = formatSummarizedByKillLog(currentDamageLogEntries);
        } else { // ZOMBIE
            formattedRows = formatSummarizedDamageLog(currentDamageLogEntries);
        }

        // 检查格式化后是否有行
        if (formattedRows.isEmpty()) {
            hideDamageLogDisplay();
            return;
        }

        // 在 JavaFX 线程上更新 UI
        Platform.runLater(() -> {
            // [安全检查] damageLogDisplayBox 现在应该是 GridPane
            if (damageLogDisplayBox != null) {
                damageLogDisplayBox.getChildren().clear(); // 清除之前的网格内容

                // 可选：如果行数经常动态增删，可能需要清除行约束
                // damageLogDisplayBox.getRowConstraints().clear();

                // 将新的行和节点添加到 GridPane
                for (int rowIndex = 0; rowIndex < formattedRows.size(); rowIndex++) {
                    Node[] nodesInRow = formattedRows.get(rowIndex);
                    // 如果需要，可以定义行约束 (例如，设置行高)
                    // RowConstraints rowConst = new RowConstraints();
                    // damageLogDisplayBox.getRowConstraints().add(rowConst);

                    for (int colIndex = 0; colIndex < nodesInRow.length; colIndex++) {
                        Node node = nodesInRow[colIndex];
                        if (node != null) { // 检查节点是否存在 (例如，命中数列可能为 null)
                            // 将节点添加到网格的 (列, 行) 位置
                            damageLogDisplayBox.add(node, colIndex, rowIndex);
                        }
                    }
                }

                // 使网格可见并处理计时器/淡出 (逻辑保持不变)
                damageLogDisplayBox.setVisible(true);
                damageLogDisplayBox.setManaged(true);
                damageLogDisplayBox.setOpacity(1.0);

                // 停止任何现有的淡出计时器/过渡
                if (tdmDamageLogFadeTimer != null)
                    tdmDamageLogFadeTimer.stop();
                if (tdmDamageLogFadeOut != null)
                    tdmDamageLogFadeOut.stop();

                // 处理 TDM 淡出
                if ("TEAM_DEATHMATCH".equals(mode)) {
                    tdmDamageLogFadeTimer = new PauseTransition(Duration.seconds(5));
                    tdmDamageLogFadeTimer.setOnFinished(event -> {
                        tdmDamageLogFadeOut = new FadeTransition(Duration.seconds(1), damageLogDisplayBox);
                        tdmDamageLogFadeOut.setToValue(0);
                        tdmDamageLogFadeOut.setOnFinished(e -> hideDamageLogDisplay());
                        tdmDamageLogFadeOut.play();
                    });
                    tdmDamageLogFadeTimer.play();
                }
                // 处理 ZOMBIE 模式显示时长
                else if ("ZOMBIE_MODE".equals(mode)) {
                    tdmDamageLogFadeTimer = new PauseTransition(Duration.seconds(10));
                    tdmDamageLogFadeTimer.setOnFinished(event -> hideDamageLogDisplay());
                    tdmDamageLogFadeTimer.play();
                }
                // DEMO 模式保持可见，直到被其他逻辑清除/隐藏
            }
        });
    }

    private String getPlayerNameById(String id) {
        if (id == null)
            return "Unknown";
        if (myPlayerId != null && id.equals(myPlayerId))
            return playerName; // Use local player name for self

        cs2d.client.GameClient.ClientPlayer player = clientPlayers.get(id);
        if (player != null && player.data != null) {
            return getString(player.data, "name");
        }
        cs2d.client.GameClient.ClientPlayer zombie = clientZombies.get(id);
        if (zombie != null && zombie.data != null) {
            return getString(zombie.data, "name"); // Or maybe just "Zombie"
        }
        // Fallback if player disconnected or ID is weird
        return id.substring(0, Math.min(id.length(), 6)); // Shortened ID as fallback
    }

    /** 辅助方法：通过 ID 获取队伍字符串 */
    private String getTeamById(String id) {
        if (id == null)
            return "";
        cs2d.client.GameClient.ClientPlayer player = clientPlayers.get(id);
        if (player != null && player.data != null)
            return getString(player.data, "team");
        cs2d.client.GameClient.ClientPlayer zombie = clientZombies.get(id);
        if (zombie != null && zombie.data != null)
            return getString(zombie.data, "team");
        return ""; // 默认空字符串
    }

    /**
     * 隐藏伤害日志显示框。
     */
    private void hideDamageLogDisplay() {
        Platform.runLater(() -> {
            if (damageLogDisplayBox != null) { // 现在应该是 GridPane
                if (tdmDamageLogFadeTimer != null)
                    tdmDamageLogFadeTimer.stop();
                if (tdmDamageLogFadeOut != null)
                    tdmDamageLogFadeOut.stop();

                damageLogDisplayBox.getChildren().clear(); // 清除网格内容
                damageLogDisplayBox.setVisible(false);
                damageLogDisplayBox.setManaged(false);
            }
        });
    }

    // 用于概要视图的辅助 record
    private static class DamageSummary {
        String otherPlayerName;
        String otherPlayerId; // <-- 存储ID
        String otherPlayerTeam;
        int damageDealt = 0;
        int damageReceived = 0;
        int hitsDealt = 0; // 新增：命中次数（造成）
        int hitsReceived = 0; // 新增：命中次数（受到）
        boolean dealtKill = false;
        boolean receivedKill = false;

        DamageSummary(String id, String name, String team) {
            this.otherPlayerName = name;
            this.otherPlayerTeam = team;
            this.otherPlayerId = id;
        }
    }

    /**
     * 将伤害日志条目格式化为详细列表 (爆破模式)。
     * 返回 Node 数组列表，用于 GridPane。
     * 示例: [你] [->] [敌人] [30] [ ] [x]
     */
    // (约 第 7717 行)
    private List<Node[]> formatDetailedDamageLog(List<JsonObject> entries) {
        List<Node[]> formattedRows = new ArrayList<>();
        if (myPlayerId == null)
            return formattedRows;

        String myName = playerName;

        // [修复] 汇总显示逻辑：使用一个临时的汇总 Map，按“攻击者_受害者”聚合
        // 虽然爆破模式是“详细”日志，但连续的子弹命中应该汇总，否则屏幕会被同一个名字刷屏。
        Map<String, JsonObject> aggregated = new LinkedHashMap<>();
        for (JsonObject entry : entries) {
            String attackerId = getString(entry, "atk");
            String victimId = getString(entry, "vic");
            // 创建唯一键：攻击者_受害者
            String key = (attackerId != null ? attackerId : "env") + "_" + victimId;

            if (aggregated.containsKey(key)) {
                JsonObject existing = aggregated.get(key);
                int totalDmg = getInt(existing, "dmg") + getInt(entry, "dmg");
                existing.addProperty("dmg", totalDmg);
                // 如果任意一次命中造成了击杀，则标记为击杀
                if (getBool(entry, "kill")) {
                    existing.addProperty("kill", true);
                }
            } else {
                aggregated.put(key, entry.deepCopy());
            }
        }

        for (JsonObject entry : aggregated.values()) {
            String attackerId = getString(entry, "atk");
            String victimId = getString(entry, "vic");
            int damage = getInt(entry, "dmg");
            boolean isKill = getBool(entry, "kill");

            Node[] rowNodes = new Node[6];

            Label name1Label = new Label();
            Label arrowLabel = new Label();
            Label name2Label = new Label();
            Label damageLabel = new Label(" " + damage);
            Label killMarkLabel = new Label(isKill ? " x" : "");

            name1Label.setFont(smallHudFont);
            arrowLabel.setFont(smallHudFont);
            name2Label.setFont(smallHudFont);
            damageLabel.setFont(Font.font(smallHudFont.getFamily(), FontWeight.BOLD, smallHudFont.getSize()));
            killMarkLabel.setFont(smallHudFont);
            killMarkLabel.setTextFill(Color.RED);

            if (myPlayerId.equals(attackerId)) {
                name1Label.setText(myName);
                name1Label.setTextFill(PRIMARY_GREEN);
                arrowLabel.setText(" -> ");
                arrowLabel.setTextFill(Color.WHITE);
                String victimName = getPlayerNameById(victimId);
                name2Label.setText(victimName);
                name2Label.setTextFill(getColorForPlayer(victimId, getTeamById(victimId)));
                damageLabel.setTextFill(Color.YELLOW);
            } else if (myPlayerId.equals(victimId)) {
                name1Label.setText(myName);
                name1Label.setTextFill(Color.ORANGERED);
                arrowLabel.setText(" <- ");
                arrowLabel.setTextFill(Color.WHITE);
                String attackerName = getPlayerNameById(attackerId);
                name2Label.setText(attackerName);
                name2Label.setTextFill(getColorForPlayer(attackerId, getTeamById(attackerId)));
                damageLabel.setTextFill(Color.ORANGERED);
            } else {
                continue;
            }

            rowNodes[0] = name1Label;
            rowNodes[1] = arrowLabel;
            rowNodes[2] = name2Label;
            rowNodes[3] = damageLabel;
            rowNodes[4] = null;
            rowNodes[5] = killMarkLabel;

            formattedRows.add(rowNodes);
        }
        return formattedRows;
    }

    /**
     * 将伤害日志条目格式化为按玩家总结的列表 (僵尸模式)。
     * 返回 Node 数组列表，用于 GridPane。
     * 示例: [你] [->] [敌人] [100] [(5)] [x]
     */
    // (约 第 7919 行)
    private List<Node[]> formatSummarizedDamageLog(List<JsonObject> entries) {
        List<Node[]> formattedRows = new ArrayList<>(); // 返回类型改为 List<Node[]>
        if (myPlayerId == null)
            return formattedRows;

        String myName = playerName;

        // 结构: Map<其他玩家ID, 伤害概要>
        Map<String, cs2d.client.GameClient.DamageSummary> summaries = new HashMap<>();

        for (JsonObject entry : entries) {
            String attackerId = getString(entry, "atk");
            String victimId = getString(entry, "vic");
            int damage = getInt(entry, "dmg");
            boolean isKill = getBool(entry, "kill");

            String otherId;
            boolean dealt; // 标记是造成伤害还是受到伤害

            if (myPlayerId.equals(attackerId)) {
                otherId = victimId;
                dealt = true;
            } else if (myPlayerId.equals(victimId)) {
                otherId = attackerId;
                dealt = false;
            } else {
                continue;
            }

            // 忽略自我伤害的概要
            if (myPlayerId.equals(otherId)) {
                continue;
            }

            // 获取或创建对应玩家的伤害概要对象 (传入ID)
            cs2d.client.GameClient.DamageSummary summary = summaries.computeIfAbsent(otherId,
                    k -> new cs2d.client.GameClient.DamageSummary(k, getPlayerNameById(k), getTeamById(k))); // 确保构造函数接收ID

            // 更新概要信息
            if (dealt) {
                summary.damageDealt += damage;
                summary.hitsDealt++;
                if (isKill)
                    summary.dealtKill = true;
            } else {
                summary.damageReceived += damage;
                summary.hitsReceived++;
                if (isKill)
                    summary.receivedKill = true;
            }
        }

        // 将概要信息格式化为行
        summaries.forEach((otherId, summary) -> {
            // 如果对该玩家造成了伤害
            if (summary.damageDealt > 0) {
                Node[] rowNodesDealt = new Node[6]; // 创建 Node 数组

                // 创建 Label
                Label name1Label = new Label(myName);
                Label arrowLabel = new Label(" -> ");
                Label name2Label = new Label(summary.otherPlayerName);
                Label damageLabel = new Label(" " + summary.damageDealt);
                Label hitsLabel = new Label(" (" + summary.hitsDealt + ")");
                Label killMarkLabel = new Label(summary.dealtKill ? " x" : "");

                // 设置样式
                name1Label.setTextFill(PRIMARY_GREEN);
                name1Label.setFont(smallHudFont);
                arrowLabel.setTextFill(Color.WHITE);
                arrowLabel.setFont(smallHudFont);
                name2Label.setTextFill(getColorForPlayer(otherId, summary.otherPlayerTeam));
                name2Label.setFont(smallHudFont);
                damageLabel.setTextFill(Color.YELLOW);
                damageLabel.setFont(Font.font(smallHudFont.getFamily(), FontWeight.BOLD, smallHudFont.getSize()));
                hitsLabel.setTextFill(Color.GRAY);
                hitsLabel.setFont(tinyHudFont);
                killMarkLabel.setTextFill(Color.RED);
                killMarkLabel.setFont(smallHudFont);

                // 分配到数组
                rowNodesDealt[0] = name1Label;
                rowNodesDealt[1] = arrowLabel;
                rowNodesDealt[2] = name2Label;
                rowNodesDealt[3] = damageLabel;
                rowNodesDealt[4] = hitsLabel;
                rowNodesDealt[5] = killMarkLabel;
                formattedRows.add(rowNodesDealt); // 添加数组到列表
            }
            // 如果从该玩家受到了伤害
            if (summary.damageReceived > 0) {
                Node[] rowNodesReceived = new Node[6]; // 创建 Node 数组

                // 创建 Label
                Label name1Label = new Label(myName);
                Label arrowLabel = new Label(" <- ");
                Label name2Label = new Label(summary.otherPlayerName);
                Label damageLabel = new Label(" " + summary.damageReceived);
                Label hitsLabel = new Label(" (" + summary.hitsReceived + ")");
                Label killMarkLabel = new Label(summary.receivedKill ? " x" : "");

                // 设置样式
                name1Label.setTextFill(Color.ORANGERED);
                name1Label.setFont(smallHudFont);
                arrowLabel.setTextFill(Color.WHITE);
                arrowLabel.setFont(smallHudFont);
                name2Label.setTextFill(getColorForPlayer(otherId, summary.otherPlayerTeam));
                name2Label.setFont(smallHudFont);
                damageLabel.setTextFill(Color.ORANGERED);
                damageLabel.setFont(Font.font(smallHudFont.getFamily(), FontWeight.BOLD, smallHudFont.getSize()));
                hitsLabel.setTextFill(Color.GRAY);
                hitsLabel.setFont(tinyHudFont);
                killMarkLabel.setTextFill(Color.RED);
                killMarkLabel.setFont(smallHudFont);

                // 分配到数组
                rowNodesReceived[0] = name1Label;
                rowNodesReceived[1] = arrowLabel;
                rowNodesReceived[2] = name2Label;
                rowNodesReceived[3] = damageLabel;
                rowNodesReceived[4] = hitsLabel;
                rowNodesReceived[5] = killMarkLabel;
                formattedRows.add(rowNodesReceived); // 添加数组到列表
            }
        });

        return formattedRows; // 返回 Node 数组列表
    }

    /**
     * 将伤害日志条目格式化为按“击杀”分隔的概要列表 (TDM 模式)。
     * 返回 Node 数组列表，用于 GridPane。
     * 示例: [你] [->] [敌人] [100] [(3)] [x]
     */
    // (约 第 8016 行)
    private List<Node[]> formatSummarizedByKillLog(List<JsonObject> entries) {
        List<Node[]> formattedRows = new ArrayList<>(); // 返回类型改为 List<Node[]>
        if (myPlayerId == null)
            return formattedRows;

        String myName = playerName;

        // Map<OtherPlayerID, In-Progress-Summary>
        Map<String, cs2d.client.GameClient.DamageSummary> currentEngagements = new HashMap<>();
        // List to hold completed summaries (after a kill) or non-kill summaries at the
        // end
        List<cs2d.client.GameClient.DamageSummary> finalizedEngagements = new ArrayList<>();

        // 1. 遍历所有伤害事件 (它们是按时间顺序的)
        for (JsonObject entry : entries) {
            String attackerId = getString(entry, "atk");
            String victimId = getString(entry, "vic");
            int damage = getInt(entry, "dmg");
            boolean isKill = getBool(entry, "kill");

            String otherId;
            boolean dealt; // 标记是造成伤害还是受到伤害

            if (myPlayerId.equals(attackerId)) {
                otherId = victimId;
                dealt = true;
            } else if (myPlayerId.equals(victimId)) {
                otherId = attackerId;
                dealt = false;
            } else {
                continue; // Not involving me
            }

            // 忽略自我伤害
            if (myPlayerId.equals(otherId)) {
                continue;
            }

            // 2. 获取或创建 *当前* 的交战概要 (传入ID)
            cs2d.client.GameClient.DamageSummary summary = currentEngagements.computeIfAbsent(otherId,
                    k -> new cs2d.client.GameClient.DamageSummary(k, getPlayerNameById(k), getTeamById(k))); // 确保构造函数接收ID

            // 3. 更新这个概要
            if (dealt) {
                summary.damageDealt += damage;
                summary.hitsDealt++;
            } else {
                summary.damageReceived += damage;
                summary.hitsReceived++;
            }

            // 4. 如果这次是击杀，则 "敲定" 这个概要
            if (isKill) {
                if (dealt) {
                    summary.dealtKill = true;
                } else {
                    summary.receivedKill = true;
                }
                // 将这个已完成的概要添加到最终列表
                finalizedEngagements.add(summary);
                // 从 "进行中" Map 中移除，以便下次对该玩家的伤害能创建新概要
                currentEngagements.remove(otherId);
            }
        }

        // 5. 循环结束后，将所有 "进行中" (未造成击杀) 的概要也添加到最终列表
        finalizedEngagements.addAll(currentEngagements.values());

        // 6. 将所有 "敲定" 的概要格式化为 UI 元素
        finalizedEngagements.forEach(summary -> {
            String otherId = summary.otherPlayerId; // 从 summary 中取回 ID

            // 如果对该玩家造成了伤害
            if (summary.damageDealt > 0) {
                Node[] rowNodesDealt = new Node[6]; // 创建 Node 数组

                // 创建 Label
                Label name1Label = new Label(myName);
                Label arrowLabel = new Label(" -> ");
                Label name2Label = new Label(summary.otherPlayerName);
                Label damageLabel = new Label(" " + summary.damageDealt);
                Label hitsLabel = new Label(" (" + summary.hitsDealt + ")");
                Label killMarkLabel = new Label(summary.dealtKill ? " x" : "");

                // 设置样式
                name1Label.setTextFill(PRIMARY_GREEN);
                name1Label.setFont(smallHudFont);
                arrowLabel.setTextFill(Color.WHITE);
                arrowLabel.setFont(smallHudFont);
                name2Label.setTextFill(getColorForPlayer(otherId, summary.otherPlayerTeam));
                name2Label.setFont(smallHudFont);
                damageLabel.setTextFill(Color.YELLOW);
                damageLabel.setFont(Font.font(smallHudFont.getFamily(), FontWeight.BOLD, smallHudFont.getSize()));
                hitsLabel.setTextFill(Color.GRAY);
                hitsLabel.setFont(tinyHudFont);
                killMarkLabel.setTextFill(Color.RED);
                killMarkLabel.setFont(smallHudFont);

                // 分配到数组
                rowNodesDealt[0] = name1Label;
                rowNodesDealt[1] = arrowLabel;
                rowNodesDealt[2] = name2Label;
                rowNodesDealt[3] = damageLabel;
                rowNodesDealt[4] = hitsLabel;
                rowNodesDealt[5] = killMarkLabel;
                formattedRows.add(rowNodesDealt); // 添加数组到列表
            }
            // 如果从该玩家受到了伤害
            if (summary.damageReceived > 0) {
                Node[] rowNodesReceived = new Node[6]; // 创建 Node 数组

                // 创建 Label
                Label name1Label = new Label(myName);
                Label arrowLabel = new Label(" <- ");
                Label name2Label = new Label(summary.otherPlayerName);
                Label damageLabel = new Label(" " + summary.damageReceived);
                Label hitsLabel = new Label(" (" + summary.hitsReceived + ")");
                Label killMarkLabel = new Label(summary.receivedKill ? " x" : "");

                // 设置样式
                name1Label.setTextFill(Color.ORANGERED);
                name1Label.setFont(smallHudFont);
                arrowLabel.setTextFill(Color.WHITE);
                arrowLabel.setFont(smallHudFont);
                name2Label.setTextFill(getColorForPlayer(otherId, summary.otherPlayerTeam));
                name2Label.setFont(smallHudFont);
                damageLabel.setTextFill(Color.ORANGERED);
                damageLabel.setFont(Font.font(smallHudFont.getFamily(), FontWeight.BOLD, smallHudFont.getSize()));
                hitsLabel.setTextFill(Color.GRAY);
                hitsLabel.setFont(tinyHudFont);
                killMarkLabel.setTextFill(Color.RED);
                killMarkLabel.setFont(smallHudFont);

                // 分配到数组
                rowNodesReceived[0] = name1Label;
                rowNodesReceived[1] = arrowLabel;
                rowNodesReceived[2] = name2Label;
                rowNodesReceived[3] = damageLabel;
                rowNodesReceived[4] = hitsLabel;
                rowNodesReceived[5] = killMarkLabel;
                formattedRows.add(rowNodesReceived); // 添加数组到列表
            }
        });

        return formattedRows; // 返回 Node 数组列表
    }

    /** [性能修复] 预先创建特效，避免在 draw() 循环中 new 对象 */
    private static final javafx.scene.effect.DropShadow SLOWED_EFFECT = new javafx.scene.effect.DropShadow(15,
            Color.RED);

    /** [性能修复] 预先创建特效，避免在 HUD 刷新时 new 对象 */
    private static final javafx.scene.effect.DropShadow SELECTED_EQUIP_EFFECT = new javafx.scene.effect.DropShadow(10,
            Color.YELLOW);

    /**
     * [新] 这是一个缓存类
     * 它在地图加载时被创建，存储了障碍物的预计算几何数据
     * 四叉树 (Quadtree) 将存储这个类的实例，而不是原始的 JsonObject
     */
    public static class StaticObstacle {
        /** 障碍物的原始 JsonObject，用于绘制 (drawObstacle) */
        final JsonObject originalJson;
        /** 障碍物的边界框，用于四叉树查询 (queryBounds) */
        final Rectangle2D bounds;
        /** 障碍物的预计算边缘，用于 FOV 计算 (calculateFOV) */
        final List<Point2D[]> edges;
        /** 同一批边的原始坐标缓存，供高频射线求交使用，避免临时对象分配。 */
        final double[] edgeCoordinates;
        final double centerX;
        final double centerY;
        final double boundingRadius;

        StaticObstacle(JsonObject originalJson, Rectangle2D bounds, List<Point2D[]> edges) {
            this.originalJson = originalJson;
            this.bounds = bounds;
            this.edges = edges;
            this.edgeCoordinates = flattenEdgeCoordinates(edges);
            this.centerX = (bounds.getMinX() + bounds.getMaxX()) * 0.5;
            this.centerY = (bounds.getMinY() + bounds.getMaxY()) * 0.5;
            this.boundingRadius = Math.hypot(bounds.getWidth(), bounds.getHeight()) * 0.5;
        }
    }
}
