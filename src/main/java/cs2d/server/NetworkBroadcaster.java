package cs2d.server;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import cs2d.playerAndAi.Player;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.LongAdder;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;
import java.util.zip.GZIPOutputStream;
import java.util.zip.CRC32;

/**
 * 专职的网络广播器。
 * [同步优化] 现在不再使用独立线程计时，而是由主游戏循环(gameLoop)直接调用。
 * 这确保了网络包的发送与物理模拟(Tick)完全同步，彻底解决了掉落物和玩家的“周期性抽动”问题。
 */
public class NetworkBroadcaster {

    // 依赖
    private final GameState gameState;
    private final Consumer<String> logger;
    private final Gson gson = new Gson();
    private final String sessionId;
    private final AtomicLong sequenceCounter = new AtomicLong();

    // 从 GameServer 传入的共享资源 (必须是线程安全的)
    private DatagramSocket socket;
    private ConcurrentHashMap<InetSocketAddress, String> addressToPlayerId;
    private ConcurrentHashMap<String, InetSocketAddress> playerIdToAddress;
    private ConcurrentHashMap<String, LongAdder> playerBytesSentInInterval;

    private long tickCounter = 0;

    // --- 性能日志 ---
    private volatile double perfTimeNetworkSend = 0.0;
    private volatile double perfTimeJsonSerialization = 0.0;
    private volatile double perfTimeChunkPreparation = 0.0;
    private volatile double perfTimeParallelSend = 0.0;

    public NetworkBroadcaster(GameState gameState, Consumer<String> logger, String sessionId) {
        this.gameState = gameState;
        this.logger = logger;
        this.sessionId = sessionId;
    }

    public void initialize(DatagramSocket socket,
                           ConcurrentHashMap<InetSocketAddress, String> addressToPlayerId,
                           ConcurrentHashMap<String, InetSocketAddress> playerIdToAddress,
                           ConcurrentHashMap<String, LongAdder> playerBytesSentInInterval) {
        this.socket = socket;
        this.addressToPlayerId = addressToPlayerId;
        this.playerIdToAddress = playerIdToAddress;
        this.playerBytesSentInInterval = playerBytesSentInInterval;
    }

    /**
     * [同步化修复] 现在由 GameServer 的 gameLoop 调用。
     * 这样发送的数据包将与服务器物理 tick 完全同步。
     */
    public void broadcast() {
        try {
            if (addressToPlayerId == null || addressToPlayerId.isEmpty()) {
                return;
            }

            long cycleStartTime = System.nanoTime();
            long stepStartTime = cycleStartTime;

            // --- 2. 序列化 ---
            String stateJson;
            try {
                long serverTick = ++tickCounter;
                JsonObject state;
                if (serverTick % 10 == 0) {
                    state = gameState.getFullUpdateJson();
                } else {
                    state = gameState.getSmallUpdateJson();
                }
                decorateState(state, serverTick);
                stateJson = gson.toJson(state);
            } catch (Exception e) {
                logger.accept("[Broadcaster] JSON 序列化失败 (可能存在并发修改): " + e.toString());
                e.printStackTrace(); // 在服务器终端打印详细堆栈
                return; // 如果序列化失败，跳过这一帧，防止后续逻辑出错
            }
            long stepEndJson = System.nanoTime();
            double jsonSerTimeMs = (stepEndJson - stepStartTime) / 1_000_000.0;
            // --- 3. 预准备数据 ---
            stepStartTime = System.nanoTime();
            final String finalStateJson = stateJson; 
            boolean needsChunking = finalStateJson.length() > 1024;
            List<String> preparedChunks = null; 

            if (needsChunking) {
                preparedChunks = prepareChunks(finalStateJson);
            }
            long stepEndChunkPrep = System.nanoTime();
            double chunkPrepTimeMs = needsChunking ? (stepEndChunkPrep - stepStartTime) / 1_000_000.0 : 0;

            final List<String> finalPreparedChunks = preparedChunks;
            final boolean finalNeedsChunking = needsChunking;

            // --- 4. 并行发送 ---
            stepStartTime = System.nanoTime();
            addressToPlayerId.keySet().parallelStream().forEach(address -> {
                try {
                    if (finalNeedsChunking) {
                        sendLargeMessageChunks(finalPreparedChunks, address);
                    } else {
                        send(finalStateJson, address);
                    }
                } catch (Exception e) {
                    // 并行发送的小错不要中断主线程
                }
            });
            long stepEndSend = System.nanoTime();
            double parallelSendTimeMs = (stepEndSend - stepStartTime) / 1_000_000.0;

            long cycleEndTime = System.nanoTime();
            this.perfTimeNetworkSend = (cycleEndTime - cycleStartTime) / 1_000_000.0;
            this.perfTimeJsonSerialization = jsonSerTimeMs;
            this.perfTimeChunkPreparation = chunkPrepTimeMs;
            this.perfTimeParallelSend = parallelSendTimeMs;

        } catch (Throwable t) {
            // 捕获所有致命错误，防止主循环线程挂掉
            System.err.println("!!! NetworkBroadcaster 发生致命异常 !!!");
            t.printStackTrace();
            logger.accept("NetworkBroadcaster.broadcast 崩溃: " + t.toString());
        }
    }

    /** 仅由游戏主线程调用，立即发送带新序号的完整快照。 */
    public void broadcastFullUpdate() {
        if (addressToPlayerId == null || addressToPlayerId.isEmpty())
            return;
        try {
            JsonObject state = gameState.getFullUpdateJson();
            decorateState(state, tickCounter);
            String stateJson = gson.toJson(state);
            List<String> chunks = stateJson.length() > 1024 ? prepareChunks(stateJson) : null;
            for (InetSocketAddress address : addressToPlayerId.keySet()) {
                if (chunks != null)
                    sendLargeMessageChunks(chunks, address);
                else
                    send(stateJson, address);
            }
        } catch (RuntimeException e) {
            logger.accept("[Broadcaster] 强制全量广播失败: " + e.getMessage());
        }
    }

    private void decorateState(JsonObject state, long serverTick) {
        state.addProperty("protocolVersion", GameServer.PROTOCOL_VERSION);
        state.addProperty("sessionId", sessionId);
        state.addProperty("sequence", sequenceCounter.incrementAndGet());
        state.addProperty("serverTick", serverTick);
    }

    public void sendLargeMessage(String message, InetSocketAddress clientAddress) {
        List<String> chunks = prepareChunks(message);
        if (!chunks.isEmpty()) {
            sendLargeMessageChunks(chunks, clientAddress);
        }
    }

    public List<String> prepareChunks(String message) {
        try {
            byte[] compressedBytes = compress(message);

            String base64Message = Base64.getEncoder().encodeToString(compressedBytes);
            CRC32 crc32 = new CRC32();
            crc32.update(compressedBytes);
            String checksum = Long.toHexString(crc32.getValue());
            final int CHUNK_SIZE = 1024;
            int totalChunks = (int) Math.ceil((double) base64Message.length() / CHUNK_SIZE);
            String messageId = UUID.randomUUID().toString();
            JsonObject sourceMetadata = null;
            try {
                sourceMetadata = gson.fromJson(message, JsonObject.class);
            } catch (RuntimeException ignored) {
            }

            List<String> preparedChunks = new ArrayList<>(totalChunks);
            for (int i = 0; i < totalChunks; i++) {
                int start = i * CHUNK_SIZE;
                int end = Math.min(start + CHUNK_SIZE, base64Message.length());
                String chunkData = base64Message.substring(start, end);

                JsonObject chunkJson = new JsonObject();
                chunkJson.addProperty("type", "chunk");
                chunkJson.addProperty("id", messageId);
                chunkJson.addProperty("index", i);
                chunkJson.addProperty("total", totalChunks);
                chunkJson.addProperty("checksum", checksum);
                copyMetadata(sourceMetadata, chunkJson, "protocolVersion");
                copyMetadata(sourceMetadata, chunkJson, "sessionId");
                copyMetadata(sourceMetadata, chunkJson, "sequence");
                copyMetadata(sourceMetadata, chunkJson, "serverTick");
                chunkJson.addProperty("data", chunkData);
                preparedChunks.add(gson.toJson(chunkJson));
            }
            return preparedChunks;
        } catch (Exception e) {
            logger.accept("[Broadcaster] 准备分片失败: " + e.getMessage());
            return Collections.emptyList();
        }
    }

    private static void copyMetadata(JsonObject source, JsonObject target, String name) {
        if (source != null && source.has(name) && !source.get(name).isJsonNull())
            target.add(name, source.get(name).deepCopy());
    }

    private void sendLargeMessageChunks(List<String> preparedChunks, InetSocketAddress clientAddress) {
        try {
            for (String chunkString : preparedChunks) {
                send(chunkString, clientAddress);
            }
        } catch (Exception e) {
            logger.accept("[Broadcaster] 发送分片出错: " + e.getMessage());
        }
    }

    private void send(String message, InetSocketAddress address) {
        try {
            byte[] data = message.getBytes(StandardCharsets.UTF_8);
            DatagramPacket packet = new DatagramPacket(data, data.length, address);
            if (socket != null && !socket.isClosed()) {
                socket.send(packet);
                String playerId = addressToPlayerId.get(address);
                if (playerId != null) {
                    playerBytesSentInInterval.computeIfAbsent(playerId, k -> new LongAdder()).add(data.length);
                }
            }
        } catch (IOException e) {
            logger.accept("[Broadcaster] 发送出错: " + e.getMessage());
        }
    }

    private byte[] compress(String data) throws IOException {
        ByteArrayOutputStream bos = new ByteArrayOutputStream();
        try (GZIPOutputStream gzip = new GZIPOutputStream(bos)) {
            gzip.write(data.getBytes(StandardCharsets.UTF_8));
        }
        return bos.toByteArray();
    }

    public double getPerfTimeNetworkSend() { return perfTimeNetworkSend; }
    public double getPerfTimeJsonSerialization() { return perfTimeJsonSerialization; }
    public double getPerfTimeChunkPreparation() { return perfTimeChunkPreparation; }
    public double getPerfTimeParallelSend() { return perfTimeParallelSend; }
}
