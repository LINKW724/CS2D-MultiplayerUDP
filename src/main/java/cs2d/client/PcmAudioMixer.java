package cs2d.client;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URL;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
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
            int queuedVoices, int peakVoices, boolean running) {
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

    private final ConcurrentLinkedQueue<PlayRequest> requests = new ConcurrentLinkedQueue<>();
    private final AtomicBoolean running = new AtomicBoolean(false);
    private final AtomicBoolean clearRequested = new AtomicBoolean(false);
    private final LongAdder requestedVoices = new LongAdder();
    private final LongAdder mixedVoices = new LongAdder();
    private final AtomicInteger activeVoiceCount = new AtomicInteger();
    private final AtomicInteger peakVoiceCount = new AtomicInteger();
    private volatile SourceDataLine outputLine;
    private volatile Thread mixerThread;

    boolean start() {
        if (running.get())
            return true;
        try {
            DataLine.Info info = new DataLine.Info(SourceDataLine.class, OUTPUT_FORMAT);
            SourceDataLine line = (SourceDataLine) AudioSystem.getLine(info);
            // 4个混音块约21ms，兼顾Windows设备稳定性和枪声触发延迟。
            line.open(OUTPUT_FORMAT, FRAMES_PER_BUFFER * OUTPUT_FORMAT.getFrameSize() * 4);
            line.start();
            outputLine = line;
        } catch (LineUnavailableException | IllegalArgumentException e) {
            System.err.println("[PCM-MIXER] 无法打开48kHz立体声音频设备，将使用JavaFX回退: " + e.getMessage());
            return false;
        }

        running.set(true);
        Thread thread = new Thread(this::mixLoop, "CS2D-PCM-Mixer");
        thread.setDaemon(true);
        thread.setPriority(Math.min(Thread.NORM_PRIORITY + 1, Thread.MAX_PRIORITY));
        mixerThread = thread;
        thread.start();
        System.out.println("[PCM-MIXER] 固定混音线程已启动: 48000Hz / 16-bit / stereo");
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
        return new Snapshot(requestedVoices.sumThenReset(), mixedVoices.sumThenReset(),
                activeVoiceCount.get(), requests.size(), peakVoiceCount.getAndSet(activeVoiceCount.get()),
                running.get());
    }

    void stopAll() {
        requests.clear();
        clearRequested.set(true);
    }

    private void mixLoop() {
        List<Voice> voices = new ArrayList<>();
        int[] mix = new int[FRAMES_PER_BUFFER * CHANNELS];
        byte[] output = new byte[FRAMES_PER_BUFFER * OUTPUT_FORMAT.getFrameSize()];
        try {
            while (running.get()) {
                if (clearRequested.getAndSet(false))
                    voices.clear();
                drainRequests(voices);
                Arrays.fill(mix, 0);
                mixVoices(voices, mix, FRAMES_PER_BUFFER);
                encodeLittleEndian16(mix, output);
                SourceDataLine line = outputLine;
                if (line == null)
                    break;
                line.write(output, 0, output.length);
                activeVoiceCount.set(voices.size());
            }
        } catch (RuntimeException e) {
            if (running.get())
                System.err.println("[PCM-MIXER] 混音线程异常: " + e.getMessage());
        } finally {
            activeVoiceCount.set(0);
            closeLine();
            running.set(false);
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
        Thread thread = mixerThread;
        if (thread != null)
            thread.interrupt();
        closeLine();
        requests.clear();
    }

    private void closeLine() {
        SourceDataLine line = outputLine;
        outputLine = null;
        if (line != null) {
            try {
                line.stop();
                line.flush();
            } finally {
                line.close();
            }
        }
    }
}
