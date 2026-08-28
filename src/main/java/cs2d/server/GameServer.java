package cs2d.server;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import com.google.gson.JsonSyntaxException;
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
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Consumer;

/**
 * 游戏服务器的主处理类，通过 UDP 协议处理客户端网络连接与数据包收发。
 * 负责维护游戏Tick主循环(gameLoop)及超时断线等生命周期控制。
 */
public class GameServer {

    public static final double TPS = 120;
    private static final long CLIENT_TIMEOUT_MS = 10000;
    private static final long TIMEOUT_CHECK_INTERVAL_MS = 2000;

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
        this.rlBridgeService = new cs2d.server.rl.RLBridgeService(this.gameState, this.rlMacroMailbox);
        this.networkBroadcaster = new NetworkBroadcaster(this.gameState, this.logger);
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
            if (rlBridgeService != null)
                rlBridgeService.start(8081);
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
            if (json == null || !json.has("type"))
                return;

            String type = json.get("type").getAsString();
            String playerId = addressToPlayerId.get(address);

            if (playerId == null) {
                if ("discovery_probe".equals(type)) {
                    handleDiscoveryProbe(address);
                    return;
                }
                if ("joinGame".equals(type)) {
                    handleNewPlayer(address, json);
                }
                return;
            }

            playerBytesReceivedInInterval.computeIfAbsent(playerId, k -> new LongAdder()).add(packet.getLength());
            handlePlayerMessage(playerId, type, json);

        } catch (JsonSyntaxException e) {
            logger.accept("从 " + address + " 收到无效的JSON消息");
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
        String newPlayerId = UUID.randomUUID().toString();
        addressToPlayerId.put(address, newPlayerId);
        playerIdToAddress.put(newPlayerId, address);
        logger.accept("新连接: " + address + " -> 分配ID: " + newPlayerId);

        JsonObject initialInfo = new JsonObject();
        initialInfo.addProperty("type", "initialInfo");
        initialInfo.addProperty("playerId", newPlayerId);
        initialInfo.addProperty("mode", gameState.getGameMode().toString());
        send(gson.toJson(initialInfo), address);

        String mapJson = gson.toJson(gameState.getMapDataJson());
        if (mapJson.length() > 1024) {
            sendLargeMessage(mapJson, address);
        } else {
            send(mapJson, address);
        }

        String selection = json.has("selection") ? json.get("selection").getAsString() : "CT";
        String name = json.has("name") ? json.get("name").getAsString() : "Player";
        gameState.addPlayer(newPlayerId, name, selection);
        broadcastFullUpdate();
    }

    private void handlePlayerMessage(String playerId, String type, JsonObject json) {
        switch (type) {
            case "joinGame":
                String selection = json.has("selection") ? json.get("selection").getAsString() : "CT";
                String name = json.has("name") ? json.get("name").getAsString() : "Player";
                gameState.addPlayer(playerId, name, selection);
                broadcastFullUpdate();
                break;
            case "chooseWeapon":
                gameState.playerChooseWeapon(playerId, json.get("weapon").getAsString());
                broadcastFullUpdate();
                break;
            case "buyItem":
                gameState.playerBuyItem(playerId, json.get("item").getAsString());
                break;
            case "undoPurchase":
                gameState.playerUndoPurchase(playerId, json.get("item").getAsString());
                break;
            case "pickupWeapon":
                gameState.playerPickupDroppedWeapon(playerId, json.get("itemId").getAsString());
                break;
            case "selectNextWeapon":
                gameState.playerSelectsNextWeapon(playerId, json.get("weapon").getAsString());
                break;
            case "requestReload":
                gameState.playerRequestReload(playerId);
                break;
            case "dropWeapon":
                gameState.playerDropWeapon(playerId);
                break;
            case "startInteraction":
                gameState.playerStartInteraction(playerId);
                break;
            case "stopInteraction":
                gameState.playerStopInteraction(playerId);
                break;
            case "playerInput":
                gameState.updatePlayerInput(playerId, json);
                break;
            case "dropC4":
                gameState.playerDropC4(playerId);
                break;
            case "switchToSlot":
                if (json.has("slot"))
                    gameState.playerSwitchSlot(playerId, json.get("slot").getAsInt());
                break;
            case "requestPing":
                JsonObject pos = json.getAsJsonObject("position");
                Point2D.Double position = new Point2D.Double(pos.get("x").getAsDouble(), pos.get("y").getAsDouble());
                gameState.playerRequestPing(playerId, position);
                break;
            case "requestControlBot":
                gameState.playerRequestControlBot(playerId, json);
                break;
            case "ping":
                InetSocketAddress address = playerIdToAddress.get(playerId);
                if (address != null)
                    send("{\"type\":\"pong\"}", address);
                break;
        }
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
            gameState.removePlayer(playerId);
            logger.accept("客户端超时: " + address + " (PlayerID: " + playerId + ")");
            broadcastFullUpdate();
        }
    }

    /**
     * 强制向所有在线客户端广播一条当前世界的全量状态包。
     */
    public void broadcastFullUpdate() {
        if (addressToPlayerId.isEmpty())
            return;
        String stateJson = gson.toJson(gameState.getFullUpdateJson());
        for (InetSocketAddress address : addressToPlayerId.keySet()) {
            if (stateJson.length() > 1024)
                sendLargeMessage(stateJson, address);
            else
                send(stateJson, address);
        }
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
        if (gameState != null) {
            gameState.manuallyAddAiPlayer(team);
            broadcastFullUpdate();
        }
    }

    public void executeCommand(String command) {
        if (gameState != null)
            gameState.executeCommand(command);
    }

    public void removeBotByTeam(cs2d.playerAndAi.Player.Team team) {
        if (gameState != null)
            gameState.removeBotByTeam(team);
    }

    public void killAllBots() {
        if (gameState != null)
            gameState.killAllBots();
    }

    public void removePlayer(String playerId) {
        if (gameState != null) {
            InetSocketAddress address = playerIdToAddress.get(playerId);
            if (address != null)
                handleDisconnect(address);
            else {
                gameState.removePlayer(playerId);
                broadcastFullUpdate();
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
            gameState.toggleAiFreeze();
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
