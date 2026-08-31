package cs2d.client;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryUsage;
import java.lang.management.ThreadInfo;
import java.lang.management.ThreadMXBean;
import java.util.List;

/**
 * Low-frequency JVM/JavaFX runtime sampler used to distinguish application
 * work from GC pauses and Prism render-thread pressure.
 */
final class RuntimePerformanceMonitor {
    private static final String FX_THREAD_NAME = "JavaFX Application Thread";
    private static final String RENDER_THREAD_MARKER = "QuantumRenderer";

    private final ThreadMXBean threadBean = ManagementFactory.getThreadMXBean();
    private final List<GarbageCollectorMXBean> garbageCollectors = ManagementFactory.getGarbageCollectorMXBeans();
    private final int availableProcessors = Math.max(1, Runtime.getRuntime().availableProcessors());

    private long previousAllThreadCpuNanos;
    private long previousSampleNanos;
    private long previousFxCpuNanos;
    private long previousRenderCpuNanos;
    private long previousGcCollections;
    private long previousGcMillis;

    RuntimePerformanceMonitor() {
        if (threadBean.isThreadCpuTimeSupported() && !threadBean.isThreadCpuTimeEnabled()) {
            try {
                threadBean.setThreadCpuTimeEnabled(true);
            } catch (SecurityException | UnsupportedOperationException ignored) {
                // Runtime diagnostics remain usable without per-thread CPU time.
            }
        }
    }

    Snapshot snapshotAndReset() {
        long sampleNanos = System.nanoTime();
        long allThreadCpuNanos = 0L;
        long fxCpuNanos = 0L;
        long renderCpuNanos = 0L;
        String fxState = "missing";
        String renderState = "missing";

        long[] threadIds = threadBean.getAllThreadIds();
        ThreadInfo[] threadInfos = threadBean.getThreadInfo(threadIds, 0);
        for (int i = 0; i < threadIds.length; i++) {
            ThreadInfo info = threadInfos[i];
            if (info == null)
                continue;

            String name = info.getThreadName();
            long cpuNanos = threadCpuNanos(threadIds[i]);
            allThreadCpuNanos += cpuNanos;
            if (FX_THREAD_NAME.equals(name)) {
                fxCpuNanos += cpuNanos;
                fxState = info.getThreadState().name();
            } else if (isRenderThread(name)) {
                renderCpuNanos += cpuNanos;
                renderState = info.getThreadState().name();
            }
        }

        long gcCollections = 0L;
        long gcMillis = 0L;
        for (GarbageCollectorMXBean collector : garbageCollectors) {
            gcCollections += nonNegative(collector.getCollectionCount());
            gcMillis += nonNegative(collector.getCollectionTime());
        }

        long fxCpuDelta = positiveDelta(fxCpuNanos, previousFxCpuNanos);
        long renderCpuDelta = positiveDelta(renderCpuNanos, previousRenderCpuNanos);
        long gcCollectionDelta = positiveDelta(gcCollections, previousGcCollections);
        long gcMillisDelta = positiveDelta(gcMillis, previousGcMillis);
        previousFxCpuNanos = fxCpuNanos;
        previousRenderCpuNanos = renderCpuNanos;
        previousGcCollections = gcCollections;
        previousGcMillis = gcMillis;

        MemoryUsage heap = ManagementFactory.getMemoryMXBean().getHeapMemoryUsage();
        double processCpuPercent = normalizedCpuPercent(
                positiveDelta(allThreadCpuNanos, previousAllThreadCpuNanos),
                positiveDelta(sampleNanos, previousSampleNanos),
                availableProcessors,
                previousSampleNanos != 0L && allThreadCpuNanos >= previousAllThreadCpuNanos);
        previousAllThreadCpuNanos = allThreadCpuNanos;
        previousSampleNanos = sampleNanos;
        return new Snapshot(
                processCpuPercent,
                fxCpuDelta / 1_000_000.0,
                renderCpuDelta / 1_000_000.0,
                fxState,
                renderState,
                gcCollectionDelta,
                gcMillisDelta,
                bytesToMiB(heap.getUsed()),
                bytesToMiB(heap.getCommitted()));
    }

    private long threadCpuNanos(long threadId) {
        if (!threadBean.isThreadCpuTimeSupported() || !threadBean.isThreadCpuTimeEnabled())
            return 0L;
        return nonNegative(threadBean.getThreadCpuTime(threadId));
    }

    private static boolean isRenderThread(String name) {
        return name.contains(RENDER_THREAD_MARKER);
    }

    private static long positiveDelta(long current, long previous) {
        return Math.max(0L, current - previous);
    }

    private static long nonNegative(long value) {
        return Math.max(0L, value);
    }

    /**
     * 用Java线程累计CPU时间估算整个客户端CPU占用，避免Windows原生
     * OperatingSystemMXBean.getProcessCpuLoad0()偶发阻塞数秒并拖延Safepoint。
     */
    static double normalizedCpuPercent(long cpuDeltaNanos, long elapsedNanos,
            int processors, boolean hasPreviousSample) {
        if (!hasPreviousSample || elapsedNanos <= 0L || cpuDeltaNanos < 0L)
            return 0.0;
        double normalized = cpuDeltaNanos * 100.0
                / (elapsedNanos * (double) Math.max(1, processors));
        return Math.max(0.0, Math.min(100.0, normalized));
    }

    private static double bytesToMiB(long bytes) {
        return bytes / (1024.0 * 1024.0);
    }

    record Snapshot(
            double processCpuPercent,
            double fxThreadCpuMillis,
            double renderThreadCpuMillis,
            String fxThreadState,
            String renderThreadState,
            long gcCollections,
            long gcPauseMillis,
            double heapUsedMiB,
            double heapCommittedMiB) {
    }
}
