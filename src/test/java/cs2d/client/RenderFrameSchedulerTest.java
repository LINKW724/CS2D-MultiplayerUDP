package cs2d.client;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RenderFrameSchedulerTest {
    @Test
    void producesApproximately165FramesFromAOneKilohertzPulseClock() {
        RenderFrameScheduler scheduler = new RenderFrameScheduler(165);
        int renderedFrames = 0;
        for (long now = 0; now < 1_000_000_000L; now += 1_000_000L) {
            if (scheduler.shouldRender(now))
                renderedFrames++;
        }

        assertEquals(165, renderedFrames);
        assertEquals(6_060_606L, scheduler.frameIntervalNanos());
    }

    @Test
    void skipsCatchUpBurstAfterLongStall() {
        RenderFrameScheduler scheduler = new RenderFrameScheduler(165);
        assertTrue(scheduler.shouldRender(0));
        assertFalse(scheduler.shouldRender(1_000_000L));
        assertTrue(scheduler.shouldRender(100_000_000L));
        assertFalse(scheduler.shouldRender(101_000_000L));
    }
}
