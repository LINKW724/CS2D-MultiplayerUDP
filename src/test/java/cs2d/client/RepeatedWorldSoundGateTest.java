package cs2d.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class RepeatedWorldSoundGateTest {
    private final RepeatedWorldSoundGate gate = new RepeatedWorldSoundGate();

    @Test
    void gunfireAlwaysBypassesDuplicateGate() {
        assertTrue(gate.shouldPlay("FIRE", "bot", 1L));
        assertTrue(gate.shouldPlay("FIRE", "bot", 2L));
        assertEquals(0L, gate.suppressedThenReset());
    }

    @Test
    void collapsesOnlyImmediateFootstepDuplicateFromSameSource() {
        long start = 1_000L;
        assertTrue(gate.shouldPlay("FOOTSTEP", "bot-a", start));
        assertFalse(gate.shouldPlay("FOOTSTEP", "bot-a",
                start + RepeatedWorldSoundGate.FOOTSTEP_DUPLICATE_NANOS - 1L));
        assertTrue(gate.shouldPlay("FOOTSTEP", "bot-b",
                start + RepeatedWorldSoundGate.FOOTSTEP_DUPLICATE_NANOS - 1L));
        assertTrue(gate.shouldPlay("FOOTSTEP", "bot-a",
                start + RepeatedWorldSoundGate.FOOTSTEP_DUPLICATE_NANOS));
        assertEquals(1L, gate.suppressedThenReset());
    }

    @Test
    void collapsesDuplicateReloadButAllowsNextRealReload() {
        long start = 5_000L;
        assertTrue(gate.shouldPlay("RELOAD", "bot", start));
        assertFalse(gate.shouldPlay("RELOAD", "bot", start + 1L));
        assertTrue(gate.shouldPlay("RELOAD", "bot",
                start + RepeatedWorldSoundGate.RELOAD_DUPLICATE_NANOS));
    }
}
