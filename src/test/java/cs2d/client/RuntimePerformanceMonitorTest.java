package cs2d.client;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class RuntimePerformanceMonitorTest {
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
