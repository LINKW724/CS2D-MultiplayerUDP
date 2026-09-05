package cs2d.server;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class HighPrecisionTimerTest {
    @Test
    void coarseWindowsOversleepDisablesParkForAnEightMillisecondTick() {
        long tickBudget = 8_333_333L;

        assertEquals(5_833_333L, HighPrecisionTimer.calculateParkTimeNs(tickBudget, 0));
        assertEquals(0L, HighPrecisionTimer.calculateParkTimeNs(tickBudget, 10_000_000L));
    }

    @Test
    void oversleepCompensationIsClamped() {
        assertEquals(7_500_000L,
                HighPrecisionTimer.calculateParkTimeNs(30_000_000L, Long.MAX_VALUE));
    }
}
