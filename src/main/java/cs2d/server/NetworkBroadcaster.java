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
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.atomic.LongAdder;
import java.util.function.Consumer;
import java.util.zip.GZIPOutputStream;
import java.util.zip.CRC32;

/**
 * 专职的网络广播器。游戏线程只在确定的Tick捕获独立Json快照；序列化、压缩、
 * 分片和UDP发送由单一latest-wins工作线程完成，慢发送不会阻塞权威世界模拟。
 */
public class NetworkBroadcaster {

    // 依赖
    private final GameState gameState;
    private final Consumer<String> logger;
    private final Gson gson = new Gson();
    private final String sessionId;
    private final AtomicLong sequenceCounter = new AtomicLong();
    private final AtomicReference<BroadcastJob> pendingBroadcast = new AtomicReference<>();
    private final AtomicBoolean broadcastWorkerRunning = new AtomicBoolean(false);
    private final LongAdder coalescedBroadcasts = new LongAdder();
    private final ExecutorService broadcastExecutor = Executors.newSingleThreadExecutor(r -> {
        Thread thread = new Thread(r, "Server-Network-Broadcaster");
        thread.setDaemon(true);
        return thread;
    });

    // 从 GameServer 传入的共享资源 (必须是线程安全的)
    private DatagramSocket socket;
    private ConcurrentHashMap<InetSocketAddress, String> addressToPlayerId;
    private ConcurrentHashMap<String, InetSocketAddress> playerIdToAddress;
    private ConcurrentHashMap<String, LongAdder> playerBytesSentInInterval;

    private long latestServerTick = 0;
    private long snapshotCounter = 0;
    private static final int FULL_UPDATE_EVERY_SNAPSHOTS = 3;

    // --- 性能日志 ---
    private volatile double perfTimeNetworkSend = 0.0;
    private volatile double perfTimeJsonSerialization = 0.0;
    private volatile double perfTimeChunkPreparation = 0.0;
    private volatile double perfTimeParallelSend = 0.0;
    private volatile double perfTimeSnapshotCapture = 0.0;

    private record BroadcastJob(JsonObject state, List<InetSocketAddress> recipients, boolean forcedFull) {
    }

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

    /** 由游戏线程调用：只捕获当前Tick的独立快照并投递，不执行阻塞网络工作。 */
    public void broadcast(long serverTick) {
        captureAndEnqueue(serverTick, false);
    }

    private void captureAndEnqueue(long serverTick, boolean forcedFull) {
        if (addressToPlayerId == null || addressToPlayerId.isEmpty())
            return;
        long captureStartedAt = System.nanoTime();
        try {
            latestServerTick = serverTick;
            long currentSnapshot = ++snapshotCounter;
            JsonObject state = forcedFull || currentSnapshot % FULL_UPDATE_EVERY_SNAPSHOTS == 0
                    ? gameState.getFullUpdateJson()
                    : gameState.getSmallUpdateJson();
            decorateState(state, serverTick);
            List<InetSocketAddress> recipients = List.copyOf(addressToPlayerId.keySet());
            enqueueLatest(new BroadcastJob(state, recipients, forcedFull));
        } catch (RuntimeException e) {
            logger.accept("[Broadcaster] 捕获世界快照失败: " + e);
        } finally {
            perfTimeSnapshotCapture = (System.nanoTime() - captureStartedAt) / 1_000_000.0;
        }
    }

    private void enqueueLatest(BroadcastJob job) {
        BroadcastJob existing = pendingBroadcast.get();
        if (shouldPreservePendingForcedFull(existing != null && existing.forcedFull(), job.forcedFull()))
            return;
        BroadcastJob replaced = pendingBroadcast.getAndSet(job);
        if (replaced != null)
            coalescedBroadcasts.increment();
        ensureBroadcastWorkerRunning();
    }

    static boolean shouldPreservePendingForcedFull(boolean existingForcedFull, boolean incomingForcedFull) {
        return existingForcedFull && !incomingForcedFull;
    }

    private void ensureBroadcastWorkerRunning() {
        if (!broadcastWorkerRunning.compareAndSet(false, true))
            return;
        try {
            broadcastExecutor.execute(this::drainLatestBroadcasts);
        } catch (RejectedExecutionException ignored) {
            broadcastWorkerRunning.set(false);
        }
    }

    private void drainLatestBroadcasts() {
        try {
            BroadcastJob job;
            while ((job = pendingBroadcast.getAndSet(null)) != null)
                serializeAndSend(job);
        } finally {
            broadcastWorkerRunning.set(false);
            if (pendingBroadcast.get() != null)
                ensureBroadcastWorkerRunning();
        }
    }

    private void serializeAndSend(BroadcastJob job) {
        long cycleStartedAt = System.nanoTime();
        try {
            long stepStartedAt = cycleStartedAt;
            String stateJson = gson.toJson(job.state());
            long jsonFinishedAt = System.nanoTime();

            boolean needsChunking = stateJson.length() > 1024;
            List<String> preparedChunks = needsChunking ? prepareChunks(stateJson, job.state()) : null;
            long chunksFinishedAt = System.nanoTime();

            for (InetSocketAddress address : job.recipients()) {
                if (needsChunking)
                    sendLargeMessageChunks(preparedChunks, address);
                else
                    send(stateJson, address);
            }
            long sendFinishedAt = System.nanoTime();
            perfTimeJsonSerialization = (jsonFinishedAt - stepStartedAt) / 1_000_000.0;
            perfTimeChunkPreparation = needsChunking
                    ? (chunksFinishedAt - jsonFinishedAt) / 1_000_000.0 : 0.0;
            perfTimeParallelSend = (sendFinishedAt - chunksFinishedAt) / 1_000_000.0;
            perfTimeNetworkSend = (sendFinishedAt - cycleStartedAt) / 1_000_000.0;
        } catch (Throwable t) {
            logger.accept("[Broadcaster] 后台广播失败: " + t);
        }
    }

    /** 仅由游戏主线程调用，捕获一份不会被普通小快照覆盖的完整状态。 */
    public void broadcastFullUpdate() {
        captureAndEnqueue(latestServerTick, true);
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
        JsonObject sourceMetadata = null;
        try {
            sourceMetadata = gson.fromJson(message, JsonObject.class);
        } catch (RuntimeException ignored) {
        }
        return prepareChunks(message, sourceMetadata);
    }

    private List<String> prepareChunks(String message, JsonObject sourceMetadata) {
        try {
            byte[] compressedBytes = compress(message);

            String base64Message = Base64.getEncoder().encodeToString(compressedBytes);
            CRC32 crc32 = new CRC32();
            crc32.update(compressedBytes);
            String checksum = Long.toHexString(crc32.getValue());
            final int CHUNK_SIZE = 1024;
            int totalChunks = (int) Math.ceil((double) base64Message.length() / CHUNK_SIZE);
            String messageId = UUID.randomUUID().toString();
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

    public void shutdown() {
        pendingBroadcast.set(null);
        broadcastExecutor.shutdownNow();
    }

    public double getPerfTimeNetworkSend() { return perfTimeNetworkSend; }
    public double getPerfTimeJsonSerialization() { return perfTimeJsonSerialization; }
    public double getPerfTimeChunkPreparation() { return perfTimeChunkPreparation; }
    public double getPerfTimeParallelSend() { return perfTimeParallelSend; }
    public double getPerfTimeSnapshotCapture() { return perfTimeSnapshotCapture; }
    public long getAndResetCoalescedBroadcasts() { return coalescedBroadcasts.sumThenReset(); }
}
