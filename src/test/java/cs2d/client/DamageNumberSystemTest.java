package cs2d.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import javafx.scene.paint.Color;

class DamageNumberSystemTest {
    @Test
    void mergesSameTickHitsWithoutMixingHeadshots() {
        DamageNumberSystem system = new DamageNumberSystem();
        system.add(new DamageNumberEvent("player-1", "zombie-1", 8, false, false, 10, 20, 1000));
        system.add(new DamageNumberEvent("player-1", "zombie-1", 7, false, false, 11, 21, 1020));
        system.add(new DamageNumberEvent("player-1", "zombie-1", 30, true, false, 11, 21, 1020));

        assertEquals(2, system.size());
        assertEquals(15, system.snapshots().get(0).damage());
        assertEquals(30, system.snapshots().get(1).damage());
        assertEquals(Color.WHITE, DamageNumberSystem.colorFor(false));
        assertEquals(Color.rgb(245, 74, 74), DamageNumberSystem.colorFor(true));
        assertNotEquals(DamageNumberSystem.colorFor(false), DamageNumberSystem.colorFor(true));
    }

    @Test
    void boundsActiveEffectsAndExpiresThem() {
        DamageNumberSystem system = new DamageNumberSystem();
        for (int i = 0; i < DamageNumberSystem.MAX_ACTIVE + 10; i++) {
            system.add(new DamageNumberEvent("player-1", "zombie-" + i, 1, false, false, i, i, i * 100L));
        }

        assertEquals(DamageNumberSystem.MAX_ACTIVE, system.size());
        system.advance(DamageNumberSystem.LIFETIME_SECONDS + 0.01);
        assertEquals(0, system.size());
    }

    @Test
    void keepsDifferentAttackersSeparateAndClearsOnlyTeammateDamage() {
        DamageNumberSystem system = new DamageNumberSystem();
        system.add(new DamageNumberEvent("player-1", "zombie-1", 8, false, false, 10, 20, 1000));
        system.add(new DamageNumberEvent("player-2", "zombie-1", 7, false, true, 10, 20, 1000));

        assertEquals(2, system.size());
        assertFalse(system.snapshots().get(0).teammateDamage());
        assertTrue(system.snapshots().get(1).teammateDamage());

        system.clearTeammateDamage();
        assertEquals(1, system.size());
        assertFalse(system.snapshots().get(0).teammateDamage());
    }
}
