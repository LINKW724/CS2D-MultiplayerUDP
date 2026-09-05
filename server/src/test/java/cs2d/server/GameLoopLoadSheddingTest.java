package cs2d.server;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

class GameLoopLoadSheddingTest {

    @Test
    void broadcastsExactlyThirtySnapshotsPerSecondAtSixtyTps() {
        int broadcasts = 0;
        for (long tick = 1; tick <= 60; tick++) {
            if (GameServer.shouldBroadcastNetworkSnapshot(tick)) {
                broadcasts++;
            }
        }
        assertTrue(GameServer.shouldBroadcastNetworkSnapshot(2));
        assertFalse(GameServer.shouldBroadcastNetworkSnapshot(3));
        assertEquals(30, broadcasts, "network snapshot cadence must remain 30Hz");
    }

    @Test
    void dropsOnlyExpiredWholeTicksAndKeepsBoundedCatchUpWork() {
        assertEquals(0, GameServer.expiredCatchUpTicks(4.75));
        assertEquals(1, GameServer.expiredCatchUpTicks(5.75));
        assertEquals(119, GameServer.expiredCatchUpTicks(123.75));
    }
}
