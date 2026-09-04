package cs2d.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

import org.junit.jupiter.api.Test;

import javafx.scene.paint.Color;

class DamageNumberSystemTest {
    @Test
    void mergesSameTickHitsWithoutMixingHeadshots() {
        DamageNumberSystem system = new DamageNumberSystem();
        system.add(new DamageNumberEvent("zombie-1", 8, false, 10, 20, 1000));
        system.add(new DamageNumberEvent("zombie-1", 7, false, 11, 21, 1020));
        system.add(new DamageNumberEvent("zombie-1", 30, true, 11, 21, 1020));

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
            system.add(new DamageNumberEvent("zombie-" + i, 1, false, i, i, i * 100L));
        }

        assertEquals(DamageNumberSystem.MAX_ACTIVE, system.size());
        system.advance(DamageNumberSystem.LIFETIME_SECONDS + 0.01);
        assertEquals(0, system.size());
    }
}
