package cs2d.client;

import java.util.Arrays;

/** Collects rendered-frame intervals instead of confusing idle time with CPU work. */
final class FramePacingMonitor {
    private final long targetIntervalNanos;
    private final long[] samples;
    private int sampleCount;
    private int nextIndex;

    FramePacingMonitor(int targetRate, int capacity) {
        if (targetRate <= 0 || capacity <= 0)
            throw new IllegalArgumentException("targetRate and capacity must be positive");
        this.targetIntervalNanos = Math.round(1_000_000_000.0 / targetRate);
        this.samples = new long[capacity];
    }

    void record(long intervalNanos) {
        if (intervalNanos <= 0)
            return;
        samples[nextIndex] = intervalNanos;
        nextIndex = (nextIndex + 1) % samples.length;
        if (sampleCount < samples.length)
            sampleCount++;
    }

    Snapshot snapshotAndReset() {
        if (sampleCount == 0)
            return new Snapshot(0, 0, 0, 0, 0, 0);

        long[] ordered = new long[sampleCount];
        int start = sampleCount == samples.length ? nextIndex : 0;
        for (int i = 0; i < sampleCount; i++)
            ordered[i] = samples[(start + i) % samples.length];
        Arrays.sort(ordered);

        long total = 0;
        int severelyLate = 0;
        for (long interval : ordered) {
            total += interval;
            if (interval > targetIntervalNanos * 3 / 2)
                severelyLate++;
        }

        int slowFrameCount = Math.max(1, (int) Math.ceil(ordered.length * 0.01));
        long slowTotal = 0;
        for (int i = ordered.length - slowFrameCount; i < ordered.length; i++)
            slowTotal += ordered[i];

        double averageNanos = total / (double) ordered.length;
        double slowAverageNanos = slowTotal / (double) slowFrameCount;
        long p99Nanos = ordered[Math.min(ordered.length - 1,
                (int) Math.ceil(ordered.length * 0.99) - 1)];
        long maxNanos = ordered[ordered.length - 1];
        Snapshot snapshot = new Snapshot(
                1_000_000_000.0 / averageNanos,
                1_000_000_000.0 / slowAverageNanos,
                p99Nanos / 1_000_000.0,
                maxNanos / 1_000_000.0,
                severelyLate,
                ordered.length);
        sampleCount = 0;
        nextIndex = 0;
        return snapshot;
    }

    record Snapshot(double observedFps, double onePercentLowFps, double p99Millis,
                    double maxMillis, int severelyLateFrames, int sampleCount) {
    }
}
