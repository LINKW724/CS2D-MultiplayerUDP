package cs2d.server;

import com.google.gson.Gson;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;
import cs2d.playerAndAi.Player;

import java.awt.geom.Point2D;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketException;
import java.nio.charset.StandardCharsets;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Consumer;

/**
 * 游戏服务器的主处理类，通过 UDP 协议处理客户端网络连接与数据包收发。
 * 负责维护游戏Tick主循环(gameLoop)及超时断线等生命周期控制。
 */
public class GameServer {

    public static final double TPS = 120;
    public static final int PROTOCOL_VERSION = 2;
    private static final long CLIENT_TIMEOUT_MS = 10000;
    private static final long TIMEOUT_CHECK_INTERVAL_MS = 2000;
    private static final int MAX_INVALID_PACKETS = 10;
    private static final int MAX_STRING_FIELD_LENGTH = 128;
    private static final int MAX_COMMANDS_PER_TICK = 2048;

    private final GameState gameState;
    private final Gson gson = new Gson();
    private final Consumer<String> logger;
    private final int port;

    private DatagramSocket socket;
    private volatile boolean running = false;
    private Thread gameLoopThread;
    private Thread receiverThread;
    private Thread timeoutThread;
    private NetworkBroadcaster networkBroadcaster;
    private final String sessionId = UUID.randomUUID().toString();
    private final ConcurrentLinkedQueue<Runnable> gameCommandQueue = new ConcurrentLinkedQueue<>();

    private final ConcurrentHashMap<InetSocketAddress, String> addressToPlayerId = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, InetSocketAddress> playerIdToAddress = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<InetSocketAddress, Long> clientLastSeen = new ConcurrentHashMap<>();
    private static String mapName;
    private final String serverName;

    private final ConcurrentHashMap<String, LongAdder> playerBytesSentInInterval = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, LongAdder> playerBytesReceivedInInterval = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Double> playerSendRateBps = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, Double> playerReceiveRateBps = new ConcurrentHashMap<>();
    private ScheduledExecutorService networkStatScheduler;
    private final ConcurrentHashMap<InetSocketAddress, LongAdder> packetRateLimiter = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<InetSocketAddress, AtomicInteger> invalidPacketCounts = new ConcurrentHashMap<>();
    private static final int MAX_PACKETS_PER_SECOND = 250;

    private AIService aiService;
    private final ConcurrentHashMap<String, AIService.AIInput> aiInputMailbox = new ConcurrentHashMap<>();
    private final ConcurrentHashMap<String, cs2d.server.rl.RLMacroCommand> rlMacroMailbox = new ConcurrentHashMap<>();
    private cs2d.server.rl.RLBridgeService rlBridgeService;

    /**
     * 获取当前服务器运行的地图名称。
     * 
     * @return 运行的地图名称
     */
    public static String getMapName() {
        return mapName;
    }

    /**
     * 构造一个新的游戏服务器实例。
     * 
     * @param port         绑定的UDP监听端口
     * @param mode         游戏模式
     * @param difficulty   AI难度
     * @param aiCount      初始开局生成的AI数量
     * @param startingWave 僵尸模式等需要波次控制的起始波次
     * @param mapData      加载完毕的地图数据(物理障碍物等，内含地图名)
     * @param serverName   服务器对外的展示名字
     * @param logger       接收日志输出的Consumer函数回调
     */
    public GameServer(int port, GameMode mode, AIDifficulty difficulty, int aiCount, int startingWave, MapData mapData,
            String serverName, Consumer<String> logger) {
        this.port = port;
        GameServer.mapName = (mapData != null && mapData.getName() != null) ? mapData.getName() : "Unknown_Arena";
        this.serverName = (serverName == null || serverName.isEmpty()) ? "CS2D Server" : serverName;
        this.logger = logger;
        this.gameState = new GameState(mode, difficulty, aiCount, startingWave, mapData, logger, aiInputMailbox);
        this.aiService = new AIService(this.gameState, this.aiInputMailbox, this.rlMacroMailbox, 10, 60, logger);
        boolean rlEnabled = GameState.isRLTrainingMode
                || Boolean.parseBoolean(System.getProperty("cs2d.rl.enabled", "false"));
        if (rlEnabled) {
            String bindHost = System.getProperty("cs2d.rl.host", "127.0.0.1");
            String token = System.getProperty("cs2d.rl.token", System.getenv("CS2D_RL_TOKEN"));
            this.rlBridgeService = new cs2d.server.rl.RLBridgeService(this.gameState, this.rlMacroMailbox,
                    bindHost, token);
        }
        this.networkBroadcaster = new NetworkBroadcaster(this.gameState, this.logger, this.sessionId);
    }

    /**
     * 启动游戏服务端并开启所需的所有线程。
     * 绑定UDP Socket，并生成 Receiver、GameLoop、Timeout-Check 三条独立线程运行。
     */
    public void start() {
        try {
            socket = new DatagramSocket(port);
            running = true;

            receiverThread = new Thread(this::listen, "Server-Receiver");
            gameLoopThread = new Thread(this::gameLoop, "Server-GameLoop");
            timeoutThread = new Thread(this::checkTimeouts, "Server-Timeout-Check");

            aiService.start();
            if (rlBridgeService != null) {
                int rlPort = Integer.getInteger("cs2d.rl.port", 8081);
                rlBridgeService.start(rlPort);
            }
            networkBroadcaster.initialize(socket, addressToPlayerId, playerIdToAddress, playerBytesSentInInterval);

            receiverThread.start();
            gameLoopThread.start();
            timeoutThread.start();

            networkStatScheduler = Executors.newSingleThreadScheduledExecutor();
            networkStatScheduler.scheduleAtFixedRate(this::calculateAndResetRates, 1, 1, TimeUnit.SECONDS);

            logger.accept("UDP 服务器在端口 " + port + " 成功启动");
        } catch (SocketException e) {
            logger.accept("错误: 无法在端口 " + port + " 启动服务器。 " + e.getMessage());
        }
    }

    /**
     * 提供一个安全的对外关服调用接口。
     */
    public void shutdown() {
        stopServer();
        System.out.println("正在关闭...");
    }

    /**
     * 彻底关停服务器，释放端口，中断所有子线程并停止游戏状态。
     */
    public void stopServer() {
        running = false;
        if (socket != null && !socket.isClosed()) {
            socket.close();
        }
        if (gameState != null) {
            gameState.shutdown();
            aiService.stop();
            if (rlBridgeService != null)
                rlBridgeService.stop();
        }
        try {
            if (receiverThread != null)
                receiverThread.join(1000);
            if (gameLoopThread != null)
                gameLoopThread.join(1000);
            if (timeoutThread != null)
                timeoutThread.join(1000);
            if (networkStatScheduler != null && !networkStatScheduler.isShutdown()) {
                networkStatScheduler.shutdown();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            logger.accept("服务器停止时线程被中断。");
        }
        logger.accept("服务器已停止。");
    }

    private void listen() {
        byte[] buffer = new byte[4096];
        while (running) {
            try {
                DatagramPacket packet = new DatagramPacket(buffer, buffer.length);
                socket.receive(packet);
                handlePacket(packet);
            } catch (IOException e) {
                if (running)
                    logger.accept("接收数据时出错: " + e.getMessage());
            } catch (RuntimeException e) {
                // 单个坏包绝不能结束 Server-Receiver 线程。
                if (running)
                    logger.accept("丢弃处理失败的数据包: " + e.getMessage());
            }
        }
    }

    private void handlePacket(DatagramPacket packet) {
        InetSocketAddress address = (InetSocketAddress) packet.getSocketAddress();

        LongAdder counter = packetRateLimiter.computeIfAbsent(address, k -> new LongAdder());
        counter.increment();
        if (counter.sum() > MAX_PACKETS_PER_SECOND)
            return;

        String message = new String(packet.getData(), 0, packet.getLength(), StandardCharsets.UTF_8);
        clientLastSeen.put(address, System.currentTimeMillis());

        try {
            JsonObject json = gson.fromJson(message, JsonObject.class);
            if (json == null)
                throw new IllegalArgumentException("JSON 根对象为空");

            String type = requireString(json, "type", 48);
            String playerId = addressToPlayerId.get(address);

            if (playerId == null) {
                if ("discovery_probe".equals(type)) {
                    handleDiscoveryProbe(address);
                    return;
                }
                if ("joinGame".equals(type)) {
                    handleNewPlayer(address, json);
                } else {
                    recordInvalidPacket(address, "未连接客户端发送了不允许的消息: " + type);
                }
                return;
            }

            playerBytesReceivedInInterval.computeIfAbsent(playerId, k -> new LongAdder()).add(packet.getLength());
            boolean preWelcomeRetry = "joinGame".equals(type) && !json.has("sessionId");
            if (!preWelcomeRetry)
                validateClientSession(json);
            handlePlayerMessage(playerId, type, json);
            invalidPacketCounts.remove(address);

        } catch (JsonParseException | IllegalStateException | IllegalArgumentException e) {
            recordInvalidPacket(address, e.getMessage());
        }
    }

    private void handleDiscoveryProbe(InetSocketAddress address) {
        JsonObject response = new JsonObject();
        response.addProperty("type", "server_info");
        response.addProperty("serverName", serverName);
        response.addProperty("mapName", mapName);
        response.addProperty("mode", gameState.getGameMode().toString());
        response.addProperty("playerCount", addressToPlayerId.size());
        response.addProperty("aiCount", gameState.getAiCount());
        response.addProperty("server_port", port);

        // [核心修复] 尝试获取本机的真实局域网 IP
        String localIp = "127.0.0.1";
        try (DatagramSocket s = new DatagramSocket()) {
            s.connect(InetAddress.getByName("8.8.8.8"), 10002);
            localIp = s.getLocalAddress().getHostAddress();
        } catch (Exception e) {
            // 回退到回环地址
        }
        response.addProperty("server_ip", localIp);

        send(gson.toJson(response), address);
    }

    private void handleNewPlayer(InetSocketAddress address, JsonObject json) {
        String selection = optionalString(json, "selection", "CT", 32);
        String name = optionalString(json, "name", "Player", MAX_STRING_FIELD_LENGTH);
        String newPlayerId = UUID.randomUUID().toString();
        addressToPlayerId.put(address, newPlayerId);
        playerIdToAddress.put(newPlayerId, address);
        logger.accept("新连接: " + address + " -> 分配ID: " + newPlayerId);

        sendInitialInfo(address, newPlayerId);
        sendStaticData(address);

        enqueueGameCommand(() -> {
            gameState.addPlayer(newPlayerId, name, selection);
            broadcastFullUpdateNow();
        });
    }

    private void sendInitialInfo(InetSocketAddress address, String playerId) {
        JsonObject initialInfo = new JsonObject();
        initialInfo.addProperty("type", "initialInfo");
        initialInfo.addProperty("playerId", playerId);
        initialInfo.addProperty("mode", gameState.getGameMode().toString());
        initialInfo.addProperty("protocolVersion", PROTOCOL_VERSION);
        initialInfo.addProperty("sessionId", sessionId);
        send(gson.toJson(initialInfo), address);
    }

    private void sendStaticData(InetSocketAddress address) {
        JsonObject mapData = gameState.getMapDataJson().deepCopy();
        mapData.addProperty("protocolVersion", PROTOCOL_VERSION);
        mapData.addProperty("sessionId", sessionId);
        String mapJson = gson.toJson(mapData);
        if (mapJson.length() > 1024) {
            sendLargeMessage(mapJson, address);
        } else {
            send(mapJson, address);
        }
    }

    private void handlePlayerMessage(String playerId, String type, JsonObject json) {
        switch (type) {
            case "joinGame":
                String selection = optionalString(json, "selection", "CT", 32);
                String name = optionalString(json, "name", "Player", MAX_STRING_FIELD_LENGTH);
                // 客户端在欢迎包丢失时会重复发送 SPECTATOR 握手；每次都重发欢迎包和地图。
                if ("SPECTATOR".equalsIgnoreCase(selection)) {
                    InetSocketAddress joinAddress = playerIdToAddress.get(playerId);
                    if (joinAddress != null) {
                        sendInitialInfo(joinAddress, playerId);
                        sendStaticData(joinAddress);
                    }
                    break;
                }
                enqueueGameCommand(() -> {
                    gameState.addPlayer(playerId, name, selection);
                    broadcastFullUpdateNow();
                });
                break;
            case "welcome_ack":
                // UDP 欢迎包确认无需改变世界状态；收到即证明客户端已获得 playerId。
                break;
            case "request_static_data":
                InetSocketAddress staticDataAddress = playerIdToAddress.get(playerId);
                if (staticDataAddress != null)
                    sendStaticData(staticDataAddress);
                break;
            case "chooseWeapon":
                String weapon = requireString(json, "weapon", 64);
                enqueueGameCommand(() -> {
                    gameState.playerChooseWeapon(playerId, weapon);
                    broadcastFullUpdateNow();
                });
                break;
            case "buyItem":
                String buyItem = requireString(json, "item", 64);
                enqueueGameCommand(() -> gameState.playerBuyItem(playerId, buyItem));
                break;
            case "undoPurchase":
                String undoItem = requireString(json, "item", 64);
                enqueueGameCommand(() -> gameState.playerUndoPurchase(playerId, undoItem));
                break;
            case "pickupWeapon":
                String itemId = requireString(json, "itemId", MAX_STRING_FIELD_LENGTH);
                enqueueGameCommand(() -> gameState.playerPickupDroppedWeapon(playerId, itemId));
                break;
            case "selectNextWeapon":
                String nextWeapon = requireString(json, "weapon", 64);
                enqueueGameCommand(() -> gameState.playerSelectsNextWeapon(playerId, nextWeapon));
                break;
            case "requestReload":
                enqueueGameCommand(() -> gameState.playerRequestReload(playerId));
                break;
            case "dropWeapon":
                enqueueGameCommand(() -> gameState.playerDropWeapon(playerId));
                break;
            case "startInteraction":
                enqueueGameCommand(() -> gameState.playerStartInteraction(playerId));
                break;
            case "stopInteraction":
                enqueueGameCommand(() -> gameState.playerStopInteraction(playerId));
                break;
            case "playerInput":
                JsonObject validatedInput = validatePlayerInput(json);
                enqueueGameCommand(() -> gameState.updatePlayerInput(playerId, validatedInput));
                break;
            case "dropC4":
                enqueueGameCommand(() -> gameState.playerDropC4(playerId));
                break;
            case "switchToSlot":
                int slot = requireCompatibleInt(json, "slot", 0, 16);
                enqueueGameCommand(() -> gameState.playerSwitchSlot(playerId, slot));
                break;
            case "requestPing":
                JsonObject pos = requireObject(json, "position");
                Point2D.Double position = new Point2D.Double(requireFiniteDouble(pos, "x"),
                        requireFiniteDouble(pos, "y"));
                enqueueGameCommand(() -> gameState.playerRequestPing(playerId, position));
                break;
            case "requestControlBot":
                if (json.has("targetId") && !json.get("targetId").isJsonNull())
                    requireString(json, "targetId", MAX_STRING_FIELD_LENGTH);
                JsonObject controlRequest = json.deepCopy();
                enqueueGameCommand(() -> gameState.playerRequestControlBot(playerId, controlRequest));
                break;
            case "ping":
                InetSocketAddress address = playerIdToAddress.get(playerId);
                if (address != null) {
                    JsonObject pong = new JsonObject();
                    pong.addProperty("type", "pong");
                    pong.addProperty("protocolVersion", PROTOCOL_VERSION);
                    pong.addProperty("sessionId", sessionId);
                    send(gson.toJson(pong), address);
                }
                break;
            default:
                throw new IllegalArgumentException("未知消息类型: " + type);
        }
    }

    private void enqueueGameCommand(Runnable command) {
        if (command != null)
            gameCommandQueue.offer(command);
    }

    private void drainGameCommands() {
        int processed = 0;
        Runnable command;
        while (processed < MAX_COMMANDS_PER_TICK && (command = gameCommandQueue.poll()) != null) {
            try {
                command.run();
            } catch (RuntimeException e) {
                logger.accept("游戏命令执行失败，已隔离: " + e.getMessage());
            }
            processed++;
        }
        if (processed == MAX_COMMANDS_PER_TICK && !gameCommandQueue.isEmpty())
            logger.accept("警告: 游戏命令队列积压，剩余约 " + gameCommandQueue.size() + " 条");
    }

    private void recordInvalidPacket(InetSocketAddress address, String reason) {
        if (!addressToPlayerId.containsKey(address)) {
            logger.accept("从未连接地址 " + address + " 丢弃非法数据包: "
                    + (reason == null ? "格式错误" : reason));
            return;
        }
        int count = invalidPacketCounts.computeIfAbsent(address, ignored -> new AtomicInteger()).incrementAndGet();
        logger.accept("从 " + address + " 丢弃非法数据包 (" + count + "/" + MAX_INVALID_PACKETS + "): "
                + (reason == null ? "格式错误" : reason));
        if (count >= MAX_INVALID_PACKETS && addressToPlayerId.containsKey(address)) {
            logger.accept("客户端非法数据包达到阈值，断开连接: " + address);
            handleDisconnect(address);
        }
    }

    private static JsonObject validatePlayerInput(JsonObject json) {
        JsonObject validated = new JsonObject();
        validated.addProperty("type", "playerInput");
        validated.addProperty("angle", requireFiniteDouble(json, "angle"));
        validated.addProperty("shooting", requireBoolean(json, "shooting"));
        validated.addProperty("underhand", optionalBoolean(json, "underhand", false));
        validated.addProperty("walking", optionalBoolean(json, "walking", false));
        JsonElement keysElement = json.get("keys");
        if (keysElement == null || !keysElement.isJsonArray())
            throw new IllegalArgumentException("字段 keys 必须是数组");
        JsonArray keys = keysElement.getAsJsonArray();
        if (keys.size() > 16)
            throw new IllegalArgumentException("字段 keys 数量超限");
        JsonArray validatedKeys = new JsonArray();
        for (JsonElement key : keys) {
            if (!key.isJsonPrimitive() || !key.getAsJsonPrimitive().isString())
                throw new IllegalArgumentException("字段 keys 包含非字符串");
            String value = key.getAsString();
            if (value.length() > 24)
                throw new IllegalArgumentException("按键名称过长");
            validatedKeys.add(value);
        }
        validated.add("keys", validatedKeys);
        return validated;
    }

    private static String requireString(JsonObject json, String field, int maxLength) {
        JsonElement value = json.get(field);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isString())
            throw new IllegalArgumentException("字段 " + field + " 必须是字符串");
        String result = value.getAsString();
        if (result.isBlank() || result.length() > maxLength)
            throw new IllegalArgumentException("字段 " + field + " 为空或长度超限");
        return result;
    }

    private static String optionalString(JsonObject json, String field, String fallback, int maxLength) {
        return json.has(field) && !json.get(field).isJsonNull() ? requireString(json, field, maxLength) : fallback;
    }

    private static int requireInt(JsonObject json, String field, int min, int max) {
        JsonElement value = json.get(field);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber())
            throw new IllegalArgumentException("字段 " + field + " 必须是整数");
        int result = value.getAsInt();
        if (result < min || result > max)
            throw new IllegalArgumentException("字段 " + field + " 超出范围");
        return result;
    }

    /**
     * 槽位号早期客户端按 JSON 字符串发送；新协议使用数字。迁移期间只兼容纯整数字符串，
     * 仍然拒绝小数、指数、空值和越界数据。
     */
    static int requireCompatibleInt(JsonObject json, String field, int min, int max) {
        JsonElement value = json.get(field);
        if (value == null || !value.isJsonPrimitive())
            throw new IllegalArgumentException("字段 " + field + " 必须是整数");
        var primitive = value.getAsJsonPrimitive();
        int result;
        if (primitive.isNumber()) {
            double numericValue = primitive.getAsDouble();
            if (!Double.isFinite(numericValue) || numericValue != Math.rint(numericValue))
                throw new IllegalArgumentException("字段 " + field + " 必须是整数");
            result = primitive.getAsInt();
        } else if (primitive.isString() && primitive.getAsString().matches("-?\\d+")) {
            try {
                result = Integer.parseInt(primitive.getAsString());
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("字段 " + field + " 必须是整数", e);
            }
        } else {
            throw new IllegalArgumentException("字段 " + field + " 必须是整数");
        }
        if (result < min || result > max)
            throw new IllegalArgumentException("字段 " + field + " 超出范围");
        return result;
    }

    private static double requireFiniteDouble(JsonObject json, String field) {
        JsonElement value = json.get(field);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isNumber())
            throw new IllegalArgumentException("字段 " + field + " 必须是数字");
        double result = value.getAsDouble();
        if (!Double.isFinite(result))
            throw new IllegalArgumentException("字段 " + field + " 必须是有限数字");
        return result;
    }

    private static boolean requireBoolean(JsonObject json, String field) {
        JsonElement value = json.get(field);
        if (value == null || !value.isJsonPrimitive() || !value.getAsJsonPrimitive().isBoolean())
            throw new IllegalArgumentException("字段 " + field + " 必须是布尔值");
        return value.getAsBoolean();
    }

    private static boolean optionalBoolean(JsonObject json, String field, boolean fallback) {
        return json.has(field) && !json.get(field).isJsonNull() ? requireBoolean(json, field) : fallback;
    }

    private static JsonObject requireObject(JsonObject json, String field) {
        JsonElement value = json.get(field);
        if (value == null || !value.isJsonObject())
            throw new IllegalArgumentException("字段 " + field + " 必须是对象");
        return value.getAsJsonObject();
    }

    private void validateClientSession(JsonObject json) {
        int version = requireInt(json, "protocolVersion", PROTOCOL_VERSION, PROTOCOL_VERSION);
        String incomingSession = requireString(json, "sessionId", 64);
        if (version != PROTOCOL_VERSION || !sessionId.equals(incomingSession))
            throw new IllegalArgumentException("协议版本或会话标识不匹配");
    }

    private final HighPrecisionTimer precisionTimer = new HighPrecisionTimer();
    private long frameTimeSum = 0;
    private int frameCount = 0;
    private long lastMonitorTime = System.nanoTime();
    private long lastFrameTimeMonitor = System.nanoTime();

    private void monitorFrameTime() {
        long currentTime = System.nanoTime();
        frameTimeSum += currentTime - lastFrameTimeMonitor;
        lastFrameTimeMonitor = currentTime;
        frameCount++;

        if (currentTime - lastMonitorTime >= 5_000_000_000L) {
            double avgFrameTime = (double) frameTimeSum / frameCount / 1_000_000.0;
            double targetFrameTime = 1000.0 / TPS;
            double deviation = avgFrameTime - targetFrameTime;

            System.out.printf("性能监控: 平均帧时间 %.3f ms, 目标 %.3f ms, 偏差 %.3f ms\n",
                    avgFrameTime, targetFrameTime, deviation);

            frameTimeSum = 0;
            frameCount = 0;
            lastMonitorTime = currentTime;
        }
    }

    private void gameLoop() {
        long lastFrameTime = System.nanoTime();
        double nsPerTick = 1000000000.0 / TPS;
        double delta = 0;
        int consecutiveSkippedFrames = 0;
        lastFrameTimeMonitor = lastFrameTime;

        while (running) {
            if (Thread.currentThread().isInterrupted()) {
                running = false;
                break;
            }

            long currentTime = System.nanoTime();
            long frameTime = currentTime - lastFrameTime;
            delta += frameTime / nsPerTick;
            lastFrameTime = currentTime;

            while (delta >= 1) {
                drainGameCommands();
                gameState.update();
                delta -= 1;
                if (networkBroadcaster != null)
                    networkBroadcaster.broadcast();
                consecutiveSkippedFrames = 0;
            }

            if (delta > 2) {
                consecutiveSkippedFrames++;
                if (consecutiveSkippedFrames > 10) {
                    logger.accept("警告: 连续掉帧，可能系统负载过高");
                    consecutiveSkippedFrames = 0;
                }
            }

            long targetFrameTime = lastFrameTime + (long) nsPerTick;
            precisionTimer.preciseSleepUntil(targetFrameTime);

            monitorFrameTime();
        }
    }

    public double getPerfTimeNetworkSend() {
        return (networkBroadcaster != null) ? networkBroadcaster.getPerfTimeNetworkSend() : 0.0;
    }

    public double getPerfTimeJsonSerialization() {
        return (networkBroadcaster != null) ? networkBroadcaster.getPerfTimeJsonSerialization() : 0.0;
    }

    public double getPerfTimeChunkPreparation() {
        return (networkBroadcaster != null) ? networkBroadcaster.getPerfTimeChunkPreparation() : 0.0;
    }

    public double getPerfTimeParallelSend() {
        return (networkBroadcaster != null) ? networkBroadcaster.getPerfTimeParallelSend() : 0.0;
    }

    private void checkTimeouts() {
        while (running) {
            try {
                long now = System.currentTimeMillis();
                for (InetSocketAddress address : clientLastSeen.keySet()) {
                    long lastSeen = clientLastSeen.get(address);
                    if (now - lastSeen > CLIENT_TIMEOUT_MS)
                        handleDisconnect(address);
                }
                Thread.sleep(TIMEOUT_CHECK_INTERVAL_MS);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                running = false;
            }
        }
    }

    private void handleDisconnect(InetSocketAddress address) {
        String playerId = addressToPlayerId.remove(address);
        if (playerId != null) {
            playerIdToAddress.remove(playerId);
            clientLastSeen.remove(address);
            playerBytesSentInInterval.remove(playerId);
            playerBytesReceivedInInterval.remove(playerId);
            playerSendRateBps.remove(playerId);
            playerReceiveRateBps.remove(playerId);
            invalidPacketCounts.remove(address);
            logger.accept("客户端超时: " + address + " (PlayerID: " + playerId + ")");
            enqueueGameCommand(() -> {
                gameState.removePlayer(playerId);
                broadcastFullUpdateNow();
            });
        }
    }

    /**
     * 强制向所有在线客户端广播一条当前世界的全量状态包。
     */
    public void broadcastFullUpdate() {
        if (Thread.currentThread() == gameLoopThread)
            broadcastFullUpdateNow();
        else
            enqueueGameCommand(this::broadcastFullUpdateNow);
    }

    private void broadcastFullUpdateNow() {
        if (networkBroadcaster != null)
            networkBroadcaster.broadcastFullUpdate();
    }

    private void send(String message, InetSocketAddress address) {
        try {
            byte[] data = message.getBytes(StandardCharsets.UTF_8);
            DatagramPacket packet = new DatagramPacket(data, data.length, address);
            if (socket != null && !socket.isClosed()) {
                socket.send(packet);
                String playerIdForStat = addressToPlayerId.get(address);
                if (playerIdForStat != null) {
                    playerBytesSentInInterval.computeIfAbsent(playerIdForStat, k -> new LongAdder()).add(data.length);
                }
            }
        } catch (IOException e) {
            logger.accept("向 " + address + " 发送数据时出错: " + e.getMessage());
        }
    }

    public void addBotToGame(Player.Team team) {
        if (gameState != null)
            enqueueGameCommand(() -> {
            gameState.manuallyAddAiPlayer(team);
                broadcastFullUpdateNow();
            });
    }

    public void executeCommand(String command) {
        if (gameState != null)
            enqueueGameCommand(() -> gameState.executeCommand(command));
    }

    public void removeBotByTeam(cs2d.playerAndAi.Player.Team team) {
        if (gameState != null)
            enqueueGameCommand(() -> gameState.removeBotByTeam(team));
    }

    public void killAllBots() {
        if (gameState != null)
            enqueueGameCommand(gameState::killAllBots);
    }

    public void removePlayer(String playerId) {
        if (gameState != null) {
            InetSocketAddress address = playerIdToAddress.get(playerId);
            if (address != null)
                handleDisconnect(address);
            else {
                enqueueGameCommand(() -> {
                    gameState.removePlayer(playerId);
                    broadcastFullUpdateNow();
                });
            }
        }
    }

    /**
     * 获取服务器当前负责掌控的游戏状态(GameState)大核心。
     * 
     * @return 游戏状态黑板对象
     */
    public GameState getGameState() {
        return this.gameState;
    }

    /**
     * 冻结/解冻所有AI(调试用)。
     */
    public void toggleAiFreeze() {
        if (gameState != null)
            enqueueGameCommand(gameState::toggleAiFreeze);
    }

    public void commandAiMoveTo(String aiId, double x, double y) {
        if (gameState != null && Double.isFinite(x) && Double.isFinite(y))
            enqueueGameCommand(() -> gameState.commandAiMoveTo(aiId, x, y));
    }

    /**
     * 检查当前AI是否已经被冻结。
     * 
     * @return 如果AI被冻结则返回true
     */
    public boolean isAiFrozen() {
        return gameState != null && gameState.isAiFrozen();
    }

    private void calculateAndResetRates() {
        packetRateLimiter.clear();
        for (String playerId : playerIdToAddress.keySet()) {
            LongAdder sentAdder = playerBytesSentInInterval.get(playerId);
            playerSendRateBps.put(playerId, sentAdder != null ? (double) sentAdder.sumThenReset() : 0.0);
            LongAdder receivedAdder = playerBytesReceivedInInterval.get(playerId);
            playerReceiveRateBps.put(playerId, receivedAdder != null ? (double) receivedAdder.sumThenReset() : 0.0);
        }
    }

    public double getSendRateBpsForPlayer(String playerId) {
        return playerSendRateBps.getOrDefault(playerId, 0.0);
    }

    public double getReceiveRateBpsForPlayer(String playerId) {
        return playerReceiveRateBps.getOrDefault(playerId, 0.0);
    }

    private void sendLargeMessage(String message, InetSocketAddress clientAddress) {
        if (networkBroadcaster != null)
            networkBroadcaster.sendLargeMessage(message, clientAddress);
        else
            logger.accept("警告: 尝试发送大包但 NetworkBroadcaster 未初始化。");
    }
}
