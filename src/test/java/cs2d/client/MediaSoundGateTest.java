package cs2d.client;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class MediaSoundGateTest {

    @Test
    void preventsOverlappingNativeMediaPlayersAndHonorsCooldown() {
        GameClient.MediaSoundGate gate = new GameClient.MediaSoundGate(250);

        assertTrue(gate.tryAcquire(1_000, false));
        assertFalse(gate.tryAcquire(1_100, true));
        assertFalse(gate.tryAcquire(1_200, false));
        assertTrue(gate.tryAcquire(1_250, false));
    }
}
