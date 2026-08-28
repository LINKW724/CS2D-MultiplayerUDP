package cs2d.client;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class FramePacingMonitorTest {
    @Test
    void reportsActualAndOnePercentLowFrameRates() {
        FramePacingMonitor monitor = new FramePacingMonitor(165, 512);
        for (int i = 0; i < 199; i++)
            monitor.record(6_060_606L);
        monitor.record(20_000_000L);

        FramePacingMonitor.Snapshot snapshot = monitor.snapshotAndReset();

        assertEquals(163.1, snapshot.observedFps(), 0.2);
        assertEquals(76.8, snapshot.onePercentLowFps(), 0.2);
        assertEquals(6.061, snapshot.p99Millis(), 0.001);
        assertEquals(20.0, snapshot.maxMillis(), 0.001);
        assertEquals(1, snapshot.severelyLateFrames());
        assertEquals(200, snapshot.sampleCount());
    }

    @Test
    void invalidRenderRateFallsBackTo165() {
        assertEquals(165, GameClient.sanitizeRenderRate(0));
        assertEquals(165, GameClient.sanitizeRenderRate(1000));
        assertEquals(240, GameClient.sanitizeRenderRate(240));
    }
}
