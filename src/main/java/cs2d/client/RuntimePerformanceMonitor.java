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
    private final com.sun.management.OperatingSystemMXBean osBean;

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
        java.lang.management.OperatingSystemMXBean platformOsBean =
                ManagementFactory.getOperatingSystemMXBean();
        osBean = platformOsBean instanceof com.sun.management.OperatingSystemMXBean extended
                ? extended
                : null;
    }

    Snapshot snapshotAndReset() {
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
        double processCpuLoad = osBean == null ? -1.0 : osBean.getProcessCpuLoad();
        double processCpuPercent = processCpuLoad < 0.0 ? -1.0 : processCpuLoad * 100.0;
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
