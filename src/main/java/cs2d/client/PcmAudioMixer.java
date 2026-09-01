package cs2d.client;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

import javax.sound.sampled.AudioFormat;
import javax.sound.sampled.AudioInputStream;
import javax.sound.sampled.AudioSystem;
import javax.sound.sampled.DataLine;
import javax.sound.sampled.LineUnavailableException;
import javax.sound.sampled.SourceDataLine;
import javax.sound.sampled.UnsupportedAudioFileException;

/**
 * Fixed-thread PCM mixer for high-frequency game sounds. All WAV files are
 * decoded once to one output format; playing a sound only publishes a small
 * voice request and never creates a media/player thread.
 */
final class PcmAudioMixer implements AutoCloseable {
    static final float SAMPLE_RATE = 48_000.0f;
    static final int CHANNELS = 2;
    static final int FRAMES_PER_BUFFER = 256;
    static final int DEVICE_BATCH_BLOCKS = 4;
    static final int PREBUFFER_BLOCKS = DEVICE_BATCH_BLOCKS;
    static final int PCM_RING_BLOCKS = PREBUFFER_BLOCKS + 1;
    private static final int DEVICE_BUFFER_BATCHES = 2;
    static final long BUFFER_DURATION_NANOS = Math.round(FRAMES_PER_BUFFER * 1_000_000_000.0 / SAMPLE_RATE);
    static final long DEVICE_BATCH_DURATION_NANOS = BUFFER_DURATION_NANOS * DEVICE_BATCH_BLOCKS;
    static final long DEVICE_STALL_TIMEOUT_NANOS = TimeUnit.MILLISECONDS.toNanos(150);
    private static final long DEVICE_POLL_NANOS = TimeUnit.MILLISECONDS.toNanos(1);
    private static final long WATCHDOG_POLL_NANOS = TimeUnit.MILLISECONDS.toNanos(10);
    private static final long REOPEN_RETRY_NANOS = TimeUnit.MILLISECONDS.toNanos(100);
    static final AudioFormat OUTPUT_FORMAT = new AudioFormat(
            AudioFormat.Encoding.PCM_SIGNED, SAMPLE_RATE, 16, CHANNELS,
            CHANNELS * Short.BYTES, SAMPLE_RATE, false);

    record Sound(short[] interleavedSamples) {
        Sound {
            interleavedSamples = interleavedSamples == null ? new short[0] : interleavedSamples;
        }

        int frameCount() {
            return interleavedSamples.length / CHANNELS;
        }
    }

    record Snapshot(long requestedVoices, long mixedVoices, int activeVoices,
            int queuedVoices, int peakVoices, long underruns, int bufferedBlocks,
            int minimumBufferedBlocks, long outputWrites, long outputFrames,
            double outputRealtimePercent, double averageWriteMillis,
            double maximumWriteMillis, long lateWrites, long deviceRecoveries,
            long staleBlocksDropped, boolean running) {
    }

    private record PlayRequest(Sound sound, float gain) {
    }

    static final class Voice {
        final Sound sound;
        final float gain;
        int sampleIndex;

        Voice(Sound sound, float gain) {
            this.sound = sound;
            this.gain = gain;
        }
    }

    /**
     * Fixed reusable PCM block pool. The mixer owns blocks from the free queue,
     * and the device writer owns blocks from the ready queue. No audio block is
     * allocated while the game is running.
     */
    static final class PcmBlockRing {
        private final ArrayBlockingQueue<byte[]> freeBlocks;
        private final ArrayBlockingQueue<byte[]> readyBlocks;

        PcmBlockRing(int blockCount, int readyBlockCount, int blockBytes) {
            if (blockCount < 2 || readyBlockCount < 1 || readyBlockCount >= blockCount || blockBytes <= 0)
                throw new IllegalArgumentException("Invalid PCM ring geometry");
            freeBlocks = new ArrayBlockingQueue<>(blockCount);
            readyBlocks = new ArrayBlockingQueue<>(readyBlockCount);
            for (int i = 0; i < blockCount; i++)
                freeBlocks.add(new byte[blockBytes]);
        }

        byte[] acquireForMix() throws InterruptedException {
            return freeBlocks.take();
        }

        void publish(byte[] block) throws InterruptedException {
            readyBlocks.put(block);
        }

        byte[] pollForWrite() {
            return readyBlocks.poll();
        }

        byte[] awaitForWrite(long timeout, TimeUnit unit) throws InterruptedException {
            return readyBlocks.poll(timeout, unit);
        }

        void recycle(byte[] block) {
            if (block != null && !freeBlocks.offer(block))
                throw new IllegalStateException("PCM block recycled twice");
        }

        int discardReady() {
            int discarded = 0;
            byte[] block;
            while ((block = readyBlocks.poll()) != null) {
                recycle(block);
                discarded++;
            }
            return discarded;
        }

        int readyCount() {
            return readyBlocks.size();
        }

        int freeCount() {
            return freeBlocks.size();
        }
    }

    private final ConcurrentLinkedQueue<PlayRequest> requests = new ConcurrentLinkedQueue<>();
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean clearRequested = new AtomicBoolean(false);
    private final AtomicBoolean flushRequested = new AtomicBoolean(false);
    private final AtomicBoolean recoveryRequested = new AtomicBoolean(false);
    private final LongAdder requestedVoices = new LongAdder();
    private final LongAdder mixedVoices = new LongAdder();
    private final LongAdder underrunCount = new LongAdder();
    private final LongAdder outputWriteCount = new LongAdder();
    private final LongAdder outputFrameCount = new LongAdder();
    private final LongAdder outputWriteNanos = new LongAdder();
    private final LongAdder lateWriteCount = new LongAdder();
    private final LongAdder deviceRecoveryCount = new LongAdder();
    private final LongAdder staleBlockDropCount = new LongAdder();
    private final AtomicInteger activeVoiceCount = new AtomicInteger();
    private final AtomicInteger peakVoiceCount = new AtomicInteger();
    private final AtomicInteger minimumBufferedBlocks = new AtomicInteger(PREBUFFER_BLOCKS);
    private final AtomicLong maximumWriteNanos = new AtomicLong();
    private final AtomicLong writeStartedNanos = new AtomicLong();
    private final AtomicLong statisticsEpochNanos = new AtomicLong(System.nanoTime());
    private final Object lineLifecycleLock = new Object();
    private volatile SourceDataLine outputLine;
    private volatile PcmBlockRing blockRing;
    private volatile Thread mixerThread;
    private volatile Thread writerThread;
    private volatile Thread watchdogThread;

    boolean start() {
        if (running.get())
            return true;
        try {
            installOutputLine(openOutputLine());
        } catch (LineUnavailableException | IllegalArgumentException e) {
            System.err.println("[PCM-MIXER] 无法打开48kHz立体声音频设备，将使用JavaFX回退: " + e.getMessage());
            return false;
        }
        PcmBlockRing ring = new PcmBlockRing(PCM_RING_BLOCKS, PREBUFFER_BLOCKS,
                FRAMES_PER_BUFFER * OUTPUT_FORMAT.getFrameSize());
        CountDownLatch prebufferReady = new CountDownLatch(PREBUFFER_BLOCKS);
        blockRing = ring;
        clearRequested.set(false);
        flushRequested.set(false);
        recoveryRequested.set(false);
        writeStartedNanos.set(0L);
        minimumBufferedBlocks.set(PREBUFFER_BLOCKS);
        statisticsEpochNanos.set(System.nanoTime());
        running.set(true);
        Thread mixThread = new Thread(() -> mixLoop(ring, prebufferReady), "CS2D-PCM-Mixer");
        Thread writeThread = new Thread(() -> writeLoop(ring, prebufferReady), "CS2D-PCM-Writer");
        Thread watchThread = new Thread(() -> watchdogLoop(ring), "CS2D-PCM-Watchdog");
        mixThread.setDaemon(true);
        writeThread.setDaemon(true);
        watchThread.setDaemon(true);
        mixThread.setPriority(Thread.NORM_PRIORITY);
        writeThread.setPriority(Thread.NORM_PRIORITY);
        watchThread.setPriority(Thread.NORM_PRIORITY);
        mixerThread = mixThread;
        writerThread = writeThread;
        watchdogThread = watchThread;
        mixThread.start();
        writeThread.start();
        watchThread.start();
        System.out.println("[PCM-MIXER] 双线程环形缓冲已启动: 48000Hz / 16-bit / stereo / 256-frame");
        return true;
    }

    Sound load(URL resource) throws IOException, UnsupportedAudioFileException {
        if (resource == null)
            throw new IOException("Audio resource is null");
        try (AudioInputStream source = AudioSystem.getAudioInputStream(resource);
                AudioInputStream pcm = convertToOutputFormat(source)) {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            byte[] buffer = new byte[16 * 1024];
            int read;
            while ((read = pcm.read(buffer)) >= 0) {
                if (read > 0)
                    bytes.write(buffer, 0, read);
            }
            return decodeLittleEndian16(bytes.toByteArray());
        }
    }

    private static AudioInputStream convertToOutputFormat(AudioInputStream source) throws IOException {
        AudioFormat input = source.getFormat();
        if (formatsMatch(input, OUTPUT_FORMAT))
            return source;
        if (!AudioSystem.isConversionSupported(OUTPUT_FORMAT, input))
            throw new IOException("Unsupported PCM conversion: " + input + " -> " + OUTPUT_FORMAT);
        return AudioSystem.getAudioInputStream(OUTPUT_FORMAT, source);
    }

    private static boolean formatsMatch(AudioFormat a, AudioFormat b) {
        return a.getEncoding().equals(b.getEncoding())
                && Float.compare(a.getSampleRate(), b.getSampleRate()) == 0
                && a.getSampleSizeInBits() == b.getSampleSizeInBits()
                && a.getChannels() == b.getChannels()
                && a.isBigEndian() == b.isBigEndian();
    }

    static Sound decodeLittleEndian16(byte[] bytes) {
        int sampleCount = bytes == null ? 0 : bytes.length / Short.BYTES;
        short[] samples = new short[sampleCount];
        for (int i = 0, offset = 0; i < sampleCount; i++, offset += 2) {
            samples[i] = (short) ((bytes[offset] & 0xff) | (bytes[offset + 1] << 8));
        }
        return new Sound(samples);
    }

    boolean play(Sound sound, double volume) {
        if (!running.get() || sound == null || sound.frameCount() == 0)
            return false;
        float gain = (float) Math.max(0.0, Math.min(1.0, volume));
        if (gain <= 0.0f)
            return true;
        requests.offer(new PlayRequest(sound, gain));
        requestedVoices.increment();
        return true;
    }

    Snapshot snapshotAndReset() {
        PcmBlockRing ring = blockRing;
        int bufferedBlocks = ring == null ? 0 : ring.readyCount();
        int minBuffered = minimumBufferedBlocks.getAndSet(bufferedBlocks);
        long writes = outputWriteCount.sumThenReset();
        long frames = outputFrameCount.sumThenReset();
        long writeNanos = outputWriteNanos.sumThenReset();
        long now = System.nanoTime();
        long elapsedNanos = Math.max(1L, now - statisticsEpochNanos.getAndSet(now));
        double realtimePercent = frames * 1_000_000_000.0 / SAMPLE_RATE / elapsedNanos * 100.0;
        return new Snapshot(requestedVoices.sumThenReset(), mixedVoices.sumThenReset(),
                activeVoiceCount.get(), requests.size(), peakVoiceCount.getAndSet(activeVoiceCount.get()),
                underrunCount.sumThenReset(), bufferedBlocks, minBuffered, writes, frames, realtimePercent,
                writes == 0 ? 0.0 : writeNanos / (double) writes / 1_000_000.0,
                maximumWriteNanos.getAndSet(0L) / 1_000_000.0,
                lateWriteCount.sumThenReset(), deviceRecoveryCount.sumThenReset(),
                staleBlockDropCount.sumThenReset(), running.get());
    }

    void stopAll() {
        requests.clear();
        clearRequested.set(true);
        flushRequested.set(true);
    }

    private void mixLoop(PcmBlockRing ring, CountDownLatch prebufferReady) {
        List<Voice> voices = new ArrayList<>();
        int[] mix = new int[FRAMES_PER_BUFFER * CHANNELS];
        try {
            while (running.get()) {
                byte[] output = ring.acquireForMix();
                if (!running.get()) {
                    ring.recycle(output);
                    break;
                }
                if (clearRequested.getAndSet(false))
                    voices.clear();
                drainRequests(voices);
                Arrays.fill(mix, 0);
                mixVoices(voices, mix, FRAMES_PER_BUFFER);
                encodeLittleEndian16(mix, output);
                activeVoiceCount.set(voices.size());
                ring.publish(output);
                if (prebufferReady.getCount() > 0)
                    prebufferReady.countDown();
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } catch (RuntimeException e) {
            if (running.get())
                System.err.println("[PCM-MIXER] 混音线程异常: " + e.getMessage());
        } finally {
            activeVoiceCount.set(0);
            stopFromAudioThread(Thread.currentThread());
        }
    }

    private void writeLoop(PcmBlockRing ring, CountDownLatch prebufferReady) {
        int blockBytes = FRAMES_PER_BUFFER * OUTPUT_FORMAT.getFrameSize();
        byte[] deviceBatch = new byte[blockBytes * DEVICE_BATCH_BLOCKS];
        try {
            prebufferReady.await();
            SourceDataLine line = outputLine;
            if (!running.get())
                return;
            if (line != null)
                line.start();
            while (running.get()) {
                if (recoveryRequested.get() || line == null || !line.isOpen()) {
                    line = recoverOutputLine(ring);
                    if (line == null)
                        continue;
                }
                if (flushRequested.getAndSet(false)) {
                    ring.discardReady();
                    line.flush();
                }

                int bufferedBeforeWrite = ring.readyCount();
                minimumBufferedBlocks.accumulateAndGet(bufferedBeforeWrite, Math::min);
                boolean waitedForBlock = false;
                for (int blockIndex = 0; blockIndex < DEVICE_BATCH_BLOCKS && running.get(); blockIndex++) {
                    byte[] block = ring.pollForWrite();
                    while (block == null && running.get()) {
                        if (!waitedForBlock) {
                            underrunCount.increment();
                            waitedForBlock = true;
                        }
                        block = ring.awaitForWrite(100, TimeUnit.MILLISECONDS);
                    }
                    if (block == null)
                        break;
                    copyPcmBlock(block, deviceBatch, blockIndex);
                    ring.recycle(block);
                }
                if (!running.get())
                    break;
                if (recoveryRequested.get())
                    continue;

                long writeStarted = System.nanoTime();
                int written = writeAvailable(line, ring, deviceBatch);
                long writeNanos = System.nanoTime() - writeStarted;
                outputWriteCount.increment();
                outputFrameCount.add(written / OUTPUT_FORMAT.getFrameSize());
                outputWriteNanos.add(writeNanos);
                maximumWriteNanos.accumulateAndGet(writeNanos, Math::max);
                if (writeNanos > DEVICE_BATCH_DURATION_NANOS * 2L)
                    lateWriteCount.increment();
            }
        } catch (InterruptedException e) {
            if (running.get())
                Thread.interrupted();
        } catch (RuntimeException e) {
            if (running.get())
                System.err.println("[PCM-MIXER] 写入线程异常: " + e.getMessage());
        } finally {
            stopFromAudioThread(Thread.currentThread());
        }
    }

    private int writeAvailable(SourceDataLine line, PcmBlockRing ring, byte[] deviceBatch)
            throws InterruptedException {
        int written = 0;
        long lastProgressNanos = System.nanoTime();
        int frameSize = OUTPUT_FORMAT.getFrameSize();
        while (running.get() && written < deviceBatch.length && !recoveryRequested.get()) {
            long now = System.nanoTime();
            int writable;
            try {
                writable = alignedWritableBytes(line.available(), deviceBatch.length - written, frameSize);
            } catch (RuntimeException unavailable) {
                requestRecovery(ring);
                return written;
            }
            if (writable <= 0) {
                if (hasDeviceStalled(lastProgressNanos, now)) {
                    requestRecovery(ring);
                    closeDetachedLine(detachOutputLine(line));
                    return written;
                }
                LockSupport.parkNanos(DEVICE_POLL_NANOS);
                if (Thread.interrupted())
                    throw new InterruptedException();
                continue;
            }

            long callStarted = System.nanoTime();
            writeStartedNanos.set(callStarted);
            int count;
            try {
                count = line.write(deviceBatch, written, writable);
            } catch (RuntimeException writeFailure) {
                requestRecovery(ring);
                return written;
            } finally {
                writeStartedNanos.compareAndSet(callStarted, 0L);
            }
            if (count <= 0) {
                if (hasDeviceStalled(lastProgressNanos, System.nanoTime())) {
                    requestRecovery(ring);
                    closeDetachedLine(detachOutputLine(line));
                    return written;
                }
                continue;
            }
            written += count;
            lastProgressNanos = System.nanoTime();
        }
        return written;
    }

    static int alignedWritableBytes(int availableBytes, int remainingBytes, int frameSize) {
        if (availableBytes <= 0 || remainingBytes <= 0 || frameSize <= 0)
            return 0;
        int writable = Math.min(availableBytes, remainingBytes);
        return writable - writable % frameSize;
    }

    static boolean hasDeviceStalled(long lastProgressNanos, long nowNanos) {
        return lastProgressNanos > 0L && nowNanos - lastProgressNanos >= DEVICE_STALL_TIMEOUT_NANOS;
    }

    private void watchdogLoop(PcmBlockRing ring) {
        while (running.get()) {
            long started = writeStartedNanos.get();
            if (started > 0L && hasDeviceStalled(started, System.nanoTime())) {
                requestRecovery(ring);
                closeDetachedLine(detachOutputLine(outputLine));
            }
            LockSupport.parkNanos(WATCHDOG_POLL_NANOS);
            if (Thread.interrupted() && !running.get())
                return;
        }
    }

    private void requestRecovery(PcmBlockRing ring) {
        if (!recoveryRequested.compareAndSet(false, true))
            return;
        requests.clear();
        clearRequested.set(true);
        flushRequested.set(false);
        staleBlockDropCount.add(ring.discardReady());
    }

    private SourceDataLine recoverOutputLine(PcmBlockRing ring) throws InterruptedException {
        closeDetachedLine(detachOutputLine(null));
        while (running.get()) {
            try {
                SourceDataLine replacement = openOutputLine();
                replacement.start();
                installOutputLine(replacement);
                recoveryRequested.set(false);
                writeStartedNanos.set(0L);
                deviceRecoveryCount.increment();
                System.out.println("[PCM-MIXER] 音频设备已恢复，陈旧PCM已丢弃。");
                return replacement;
            } catch (LineUnavailableException | IllegalArgumentException reopenFailure) {
                requestRecovery(ring);
                requests.clear();
                LockSupport.parkNanos(REOPEN_RETRY_NANOS);
                if (Thread.interrupted())
                    throw new InterruptedException();
            }
        }
        return null;
    }

    private SourceDataLine openOutputLine() throws LineUnavailableException {
        DataLine.Info info = new DataLine.Info(SourceDataLine.class, OUTPUT_FORMAT);
        SourceDataLine line = (SourceDataLine) AudioSystem.getLine(info);
        int requestedBufferBytes = FRAMES_PER_BUFFER * OUTPUT_FORMAT.getFrameSize()
                * DEVICE_BATCH_BLOCKS * DEVICE_BUFFER_BATCHES;
        line.open(OUTPUT_FORMAT, requestedBufferBytes);
        System.out.printf("[PCM-MIXER] 设备缓冲 requested=%dB actual=%dB | batch=%d frames%n",
                requestedBufferBytes, line.getBufferSize(), FRAMES_PER_BUFFER * DEVICE_BATCH_BLOCKS);
        return line;
    }

    private void installOutputLine(SourceDataLine line) {
        synchronized (lineLifecycleLock) {
            outputLine = line;
        }
    }

    private SourceDataLine detachOutputLine(SourceDataLine expected) {
        synchronized (lineLifecycleLock) {
            SourceDataLine current = outputLine;
            if (expected != null && current != expected)
                return null;
            outputLine = null;
            return current;
        }
    }

    private static void closeDetachedLine(SourceDataLine line) {
        if (line == null)
            return;
        // close() is intentionally first: on Windows DirectAudio it is the operation
        // that wakes a native write blocked on a stalled device. stop()/flush() before
        // close can themselves wait behind that write and defeat the watchdog.
        try {
            line.close();
        } catch (RuntimeException ignored) {
        }
    }

    static void copyPcmBlock(byte[] block, byte[] batch, int blockIndex) {
        if (block == null || batch == null || blockIndex < 0)
            throw new IllegalArgumentException("Invalid PCM batch block");
        int offset = Math.multiplyExact(blockIndex, block.length);
        if (offset + block.length > batch.length)
            throw new IllegalArgumentException("PCM block exceeds device batch");
        System.arraycopy(block, 0, batch, offset, block.length);
    }

    private void stopFromAudioThread(Thread thread) {
        if (running.compareAndSet(true, false)) {
            Thread other = thread == mixerThread ? writerThread : mixerThread;
            if (other != null)
                other.interrupt();
            Thread watchdog = watchdogThread;
            if (watchdog != null)
                watchdog.interrupt();
            closeLine();
        }
    }

    private void drainRequests(List<Voice> voices) {
        PlayRequest request;
        while ((request = requests.poll()) != null) {
            voices.add(new Voice(request.sound(), request.gain()));
            mixedVoices.increment();
        }
        int active = voices.size();
        activeVoiceCount.set(active);
        peakVoiceCount.accumulateAndGet(active, Math::max);
    }

    static void mixVoices(List<Voice> voices, int[] mix, int frames) {
        for (Iterator<Voice> iterator = voices.iterator(); iterator.hasNext();) {
            Voice voice = iterator.next();
            short[] samples = voice.sound.interleavedSamples();
            int writableSamples = Math.min(frames * CHANNELS, samples.length - voice.sampleIndex);
            for (int i = 0; i < writableSamples; i++)
                mix[i] += Math.round(samples[voice.sampleIndex + i] * voice.gain);
            voice.sampleIndex += writableSamples;
            if (voice.sampleIndex >= samples.length)
                iterator.remove();
        }
    }

    static void encodeLittleEndian16(int[] mix, byte[] output) {
        int samples = Math.min(mix.length, output.length / 2);
        for (int i = 0, offset = 0; i < samples; i++, offset += 2) {
            int clamped = Math.max(Short.MIN_VALUE, Math.min(Short.MAX_VALUE, mix[i]));
            output[offset] = (byte) clamped;
            output[offset + 1] = (byte) (clamped >>> 8);
        }
    }

    @Override
    public void close() {
        running.set(false);
        Thread mixer = mixerThread;
        Thread writer = writerThread;
        Thread watchdog = watchdogThread;
        if (mixer != null)
            mixer.interrupt();
        if (writer != null)
            writer.interrupt();
        if (watchdog != null)
            watchdog.interrupt();
        closeLine();
        requests.clear();
        PcmBlockRing ring = blockRing;
        if (ring != null)
            ring.discardReady();
    }

    private void closeLine() {
        closeDetachedLine(detachOutputLine(null));
    }
}
