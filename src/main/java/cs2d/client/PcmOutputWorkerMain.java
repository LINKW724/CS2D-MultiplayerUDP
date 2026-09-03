package cs2d.client;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.StandardProtocolFamily;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.DatagramChannel;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;

import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.SourceDataLine;

/** Audio-device worker. It intentionally runs outside the game JVM. */
public final class PcmOutputWorkerMain {
    private static final long IDLE_POLL_NANOS = TimeUnit.MILLISECONDS.toNanos(1);
    private static final long PARENT_CHECK_NANOS = TimeUnit.SECONDS.toNanos(1);

    private PcmOutputWorkerMain() {
    }

    public static void main(String[] args) throws Exception {
        if (args.length != 4)
            return;
        int workerPort = Integer.parseInt(args[0]);
        int parentPort = Integer.parseInt(args[1]);
        long token = Long.parseUnsignedLong(args[2]);
        long parentPid = Long.parseLong(args[3]);
        InetAddress loopback = InetAddress.getLoopbackAddress();
        InetSocketAddress parentAddress = new InetSocketAddress(loopback, parentPort);

        try (DatagramChannel channel = DatagramChannel.open(StandardProtocolFamily.INET)) {
            channel.bind(new InetSocketAddress(loopback, workerPort));
            channel.configureBlocking(false);
            ByteBuffer ack = ByteBuffer.allocateDirect(IsolatedPcmOutputTransport.HEADER_BYTES)
                    .order(ByteOrder.BIG_ENDIAN);
            SourceDataLine line = openLine();
            try {
                line.start();
                sendAck(channel, parentAddress, token, 0, ack);
                runLoop(channel, parentAddress, token, parentPid, line, ack);
            } finally {
                line.close();
            }
        }
    }

    private static void runLoop(DatagramChannel channel, InetSocketAddress parentAddress, long token,
            long parentPid, SourceDataLine initialLine, ByteBuffer ack) throws Exception {
        ByteBuffer packet = ByteBuffer.allocateDirect(8192).order(ByteOrder.BIG_ENDIAN);
        byte[] pcm = new byte[8192 - IsolatedPcmOutputTransport.HEADER_BYTES];
        SourceDataLine line = initialLine;
        long nextParentCheck = System.nanoTime() + PARENT_CHECK_NANOS;

        try {
            while (true) {
                int latestSequence = 0;
                int latestLength = 0;
                while (true) {
                    packet.clear();
                    InetSocketAddress source = (InetSocketAddress) channel.receive(packet);
                    if (source == null)
                        break;
                    packet.flip();
                    if (!source.equals(parentAddress)
                            || !IsolatedPcmOutputTransport.isValidHeader(packet, token))
                        continue;
                    int sequence = packet.getInt();
                    if (sequence == IsolatedPcmOutputTransport.STOP_SEQUENCE)
                        return;
                    int length = Math.min(packet.remaining(), pcm.length);
                    packet.get(pcm, 0, length);
                    latestSequence = sequence;
                    latestLength = length;
                }

                if (latestLength > 0) {
                    long writeStarted = System.nanoTime();
                    int written = 0;
                    while (written < latestLength) {
                        int count = line.write(pcm, written, latestLength - written);
                        if (count <= 0)
                            break;
                        written += count;
                    }
                    long writeNanos = System.nanoTime() - writeStarted;
                    if (shouldRecoverOutput(latestLength, written, writeNanos,
                            line.isOpen(), line.isRunning())) {
                        line.close();
                        line = openLine();
                        line.start();
                    }
                    sendAck(channel, parentAddress, token, latestSequence, ack);
                } else {
                    LockSupport.parkNanos(IDLE_POLL_NANOS);
                }

                long now = System.nanoTime();
                if (now >= nextParentCheck) {
                    if (ProcessHandle.of(parentPid).map(ProcessHandle::isAlive).orElse(false)) {
                        nextParentCheck = now + PARENT_CHECK_NANOS;
                    } else {
                        return;
                    }
                }
            }
        } finally {
            line.close();
        }
    }

    static boolean shouldRecoverOutput(int expectedBytes, int writtenBytes, long writeNanos,
            boolean lineOpen, boolean lineRunning) {
        return expectedBytes <= 0 || writtenBytes < expectedBytes
                || writeNanos >= PcmAudioMixer.DEVICE_STALL_TIMEOUT_NANOS
                || !lineOpen || !lineRunning;
    }

    private static SourceDataLine openLine() throws Exception {
        DataLine.Info info = new DataLine.Info(SourceDataLine.class, PcmAudioMixer.OUTPUT_FORMAT);
        SourceDataLine line = (SourceDataLine) AudioSystem.getLine(info);
        int requestedBufferBytes = PcmAudioMixer.FRAMES_PER_BUFFER
                * PcmAudioMixer.OUTPUT_FORMAT.getFrameSize()
                * PcmAudioMixer.DEVICE_BATCH_BLOCKS * 2;
        line.open(PcmAudioMixer.OUTPUT_FORMAT, requestedBufferBytes);
        return line;
    }

    private static void sendAck(DatagramChannel channel, InetSocketAddress parentAddress, long token,
            int sequence, ByteBuffer ack) throws Exception {
        ack.clear();
        ack.putInt(IsolatedPcmOutputTransport.MAGIC).putLong(token).putInt(sequence).flip();
        channel.send(ack, parentAddress);
    }
}
