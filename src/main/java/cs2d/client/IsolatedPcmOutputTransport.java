package cs2d.client;

import java.io.File;
import java.io.IOException;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.StandardProtocolFamily;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.DatagramChannel;
import java.security.SecureRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.LongAdder;
import java.util.concurrent.locks.LockSupport;

/**
 * Sends mixed PCM to a tiny helper JVM over loopback UDP. DirectAudio can hold
 * a JNI critical array while a Windows driver is stalled; keeping that native
 * call in another JVM prevents it from delaying the game's GC safepoints.
 */
final class IsolatedPcmOutputTransport implements PcmOutputTransport {
    static final int MAGIC = 0x43533244; // CS2D
    static final int HEADER_BYTES = Integer.BYTES + Long.BYTES + Integer.BYTES;
    static final int STOP_SEQUENCE = -1;
    static final long WORKER_TIMEOUT_NANOS = TimeUnit.MILLISECONDS.toNanos(750);
    private static final long START_TIMEOUT_NANOS = TimeUnit.SECONDS.toNanos(2);
    private static final long START_POLL_NANOS = TimeUnit.MILLISECONDS.toNanos(2);

    private final int batchBytes;
    private final long token = new SecureRandom().nextLong();
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final LongAdder recoveryCount = new LongAdder();
    private final LongAdder droppedBatchCount = new LongAdder();
    private DatagramChannel channel;
    private InetSocketAddress workerAddress;
    private ByteBuffer packetBuffer;
    private ByteBuffer controlBuffer;
    private Process worker;
    private int sequence;
    private long lastWorkerAckNanos;

    IsolatedPcmOutputTransport(int batchBytes) {
        if (batchBytes <= 0 || HEADER_BYTES + batchBytes > 65_507)
            throw new IllegalArgumentException("Invalid isolated PCM batch size");
        this.batchBytes = batchBytes;
    }

    @Override
    public boolean start() {
        if (running.get())
            return true;
        try {
            InetAddress loopback = InetAddress.getLoopbackAddress();
            DatagramChannel newChannel = DatagramChannel.open(StandardProtocolFamily.INET);
            newChannel.bind(new InetSocketAddress(loopback, 0));
            newChannel.configureBlocking(false);
            channel = newChannel;
            packetBuffer = ByteBuffer.allocateDirect(HEADER_BYTES + batchBytes).order(ByteOrder.BIG_ENDIAN);
            controlBuffer = ByteBuffer.allocateDirect(HEADER_BYTES).order(ByteOrder.BIG_ENDIAN);
            running.set(true);
            if (!startWorker(loopback)) {
                close();
                return false;
            }
            return true;
        } catch (IOException | RuntimeException error) {
            System.err.println("[PCM-IPC] 无法启动隔离音频进程: " + error.getMessage());
            close();
            return false;
        }
    }

    @Override
    public boolean offer(byte[] pcm, int length) {
        if (!running.get() || pcm == null || length <= 0 || length > batchBytes)
            return false;
        try {
            drainWorkerAcks();
            long now = System.nanoTime();
            if (worker == null || !worker.isAlive() || now - lastWorkerAckNanos >= WORKER_TIMEOUT_NANOS) {
                recoveryCount.increment();
                if (!restartWorker()) {
                    droppedBatchCount.increment();
                    return false;
                }
            }

            ByteBuffer packet = packetBuffer;
            packet.clear();
            packet.putInt(MAGIC).putLong(token).putInt(++sequence).put(pcm, 0, length).flip();
            int sent = channel.send(packet, workerAddress);
            if (sent != HEADER_BYTES + length) {
                droppedBatchCount.increment();
                return false;
            }
            return true;
        } catch (IOException | RuntimeException error) {
            droppedBatchCount.increment();
            return false;
        }
    }

    @Override
    public long recoveriesThenReset() {
        return recoveryCount.sumThenReset();
    }

    @Override
    public long droppedBatchesThenReset() {
        return droppedBatchCount.sumThenReset();
    }

    @Override
    public void close() {
        if (!running.getAndSet(false))
            return;
        sendStop();
        stopWorker();
        DatagramChannel current = channel;
        channel = null;
        if (current != null) {
            try {
                current.close();
            } catch (IOException ignored) {
            }
        }
    }

    private boolean restartWorker() throws IOException {
        stopWorker();
        droppedBatchCount.increment();
        return startWorker(InetAddress.getLoopbackAddress());
    }

    private boolean startWorker(InetAddress loopback) throws IOException {
        int workerPort = reserveLoopbackPort(loopback);
        int parentPort = ((InetSocketAddress) channel.getLocalAddress()).getPort();
        String javaCommand = ProcessHandle.current().info().command()
                .orElseGet(() -> System.getProperty("java.home") + File.separator + "bin" + File.separator + "java");
        ProcessBuilder builder = new ProcessBuilder(javaCommand, "-cp", System.getProperty("java.class.path"),
                PcmOutputWorkerMain.class.getName(), Integer.toString(workerPort), Integer.toString(parentPort),
                Long.toUnsignedString(token), Long.toString(ProcessHandle.current().pid()));
        builder.redirectInput(ProcessBuilder.Redirect.PIPE);
        builder.redirectOutput(ProcessBuilder.Redirect.DISCARD);
        builder.redirectError(ProcessBuilder.Redirect.DISCARD);
        workerAddress = new InetSocketAddress(loopback, workerPort);
        lastWorkerAckNanos = System.nanoTime();
        worker = builder.start();

        long deadline = System.nanoTime() + START_TIMEOUT_NANOS;
        while (running.get() && worker.isAlive() && System.nanoTime() < deadline) {
            if (drainWorkerAcks())
                return true;
            LockSupport.parkNanos(START_POLL_NANOS);
            if (Thread.interrupted()) {
                Thread.currentThread().interrupt();
                break;
            }
        }
        stopWorker();
        return false;
    }

    private boolean drainWorkerAcks() throws IOException {
        boolean received = false;
        ByteBuffer control = controlBuffer;
        while (true) {
            control.clear();
            InetSocketAddress source = (InetSocketAddress) channel.receive(control);
            if (source == null)
                return received;
            control.flip();
            if (source.equals(workerAddress) && isValidHeader(control, token)) {
                lastWorkerAckNanos = System.nanoTime();
                received = true;
            }
        }
    }

    private void sendStop() {
        if (channel == null || workerAddress == null || packetBuffer == null)
            return;
        try {
            ByteBuffer packet = packetBuffer;
            packet.clear();
            packet.putInt(MAGIC).putLong(token).putInt(STOP_SEQUENCE).flip();
            channel.send(packet, workerAddress);
        } catch (IOException ignored) {
        }
    }

    private void stopWorker() {
        Process current = worker;
        worker = null;
        if (current == null)
            return;
        current.destroy();
        try {
            if (!current.waitFor(150, TimeUnit.MILLISECONDS))
                current.destroyForcibly();
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            current.destroyForcibly();
        }
    }

    static boolean isValidHeader(ByteBuffer packet, long expectedToken) {
        return packet != null && packet.remaining() >= HEADER_BYTES
                && packet.getInt() == MAGIC && packet.getLong() == expectedToken;
    }

    private static int reserveLoopbackPort(InetAddress loopback) throws IOException {
        try (DatagramSocket socket = new DatagramSocket(0, loopback)) {
            return socket.getLocalPort();
        }
    }
}
