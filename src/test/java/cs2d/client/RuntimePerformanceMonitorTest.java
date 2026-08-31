package cs2d.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class RuntimePerformanceMonitorTest {
    @Test
    void normalizedCpuPercentUsesThreadCpuDeltaWithoutNativeProcessLoadQuery() {
        assertEquals(25.0,
                RuntimePerformanceMonitor.normalizedCpuPercent(500_000_000L, 1_000_000_000L, 2, true),
                0.0001);
        assertEquals(100.0,
                RuntimePerformanceMonitor.normalizedCpuPercent(4_000_000_000L, 1_000_000_000L, 2, true),
                0.0001);
        assertEquals(0.0,
                RuntimePerformanceMonitor.normalizedCpuPercent(500_000_000L, 0L, 2, true),
                0.0001);
        assertEquals(0.0,
                RuntimePerformanceMonitor.normalizedCpuPercent(500_000_000L, 1_000_000_000L, 2, false),
                0.0001);
    }

    @Test
    void snapshotAlwaysReturnsSafeNonNegativeRuntimeCounters() {
        RuntimePerformanceMonitor.Snapshot snapshot = new RuntimePerformanceMonitor().snapshotAndReset();

        assertTrue(snapshot.fxThreadCpuMillis() >= 0.0);
        assertTrue(snapshot.renderThreadCpuMillis() >= 0.0);
        assertTrue(snapshot.gcCollections() >= 0L);
        assertTrue(snapshot.gcPauseMillis() >= 0L);
        assertTrue(snapshot.heapUsedMiB() >= 0.0);
        assertTrue(snapshot.heapCommittedMiB() >= snapshot.heapUsedMiB());
        assertNotNull(snapshot.fxThreadState());
        assertNotNull(snapshot.renderThreadState());
    }
}
