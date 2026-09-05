package cs2d.client;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

import javafx.scene.paint.Color;

class DamageNumberSystemTest {
    @Test
    void mergesSameTickHitsWithoutMixingFeedbackTypes() {
        DamageNumberSystem system = new DamageNumberSystem();
        system.add(event("player-1", "zombie-1", 8, DamageNumberType.NORMAL, false, 10, 20, 1000));
        system.add(event("player-1", "zombie-1", 7, DamageNumberType.NORMAL, false, 11, 21, 1020));
        system.add(event("player-1", "zombie-1", 30, DamageNumberType.HEADSHOT, false, 11, 21, 1020));

        assertEquals(2, system.size());
        assertEquals(15, system.snapshots().get(0).damage());
        assertEquals(30, system.snapshots().get(1).damage());
        assertEquals(DamageNumberType.NORMAL, system.snapshots().get(0).type());
        assertEquals(DamageNumberType.HEADSHOT, system.snapshots().get(1).type());
    }

    @Test
    void mapsEveryFeedbackTypeToItsAccessibleColorAndSign() {
        assertEquals(Color.WHITE, DamageNumberSystem.colorFor(DamageNumberType.NORMAL));
        assertEquals(Color.rgb(255, 159, 28), DamageNumberSystem.colorFor(DamageNumberType.HEADSHOT));
        assertEquals(Color.rgb(255, 82, 82), DamageNumberSystem.colorFor(DamageNumberType.INCOMING));
        assertEquals(Color.rgb(72, 219, 124), DamageNumberSystem.colorFor(DamageNumberType.HEALING));
        assertEquals(Color.rgb(190, 110, 255), DamageNumberSystem.colorFor(DamageNumberType.EXPLOSIVE));
        assertEquals(Color.rgb(78, 168, 255), DamageNumberSystem.colorFor(DamageNumberType.MELEE));

        DamageNumberSystem system = new DamageNumberSystem();
        system.add(event("zombie", "player", 12, DamageNumberType.INCOMING, false, 0, 0, 1000));
        system.add(event("medic", "player", 9, DamageNumberType.HEALING, false, 0, 0, 1100));
        assertEquals("-12", system.snapshots().get(0).text());
        assertEquals("+9", system.snapshots().get(1).text());
    }

    @Test
    void boundsActiveEffectsAndExpiresThem() {
        DamageNumberSystem system = new DamageNumberSystem();
        for (int i = 0; i < DamageNumberSystem.MAX_ACTIVE + 10; i++) {
            system.add(event("player-1", "zombie-" + i, 1, DamageNumberType.NORMAL,
                    false, i, i, i * 100L));
        }

        assertEquals(DamageNumberSystem.MAX_ACTIVE, system.size());
        system.advance(DamageNumberSystem.LIFETIME_SECONDS + 0.01);
        assertEquals(0, system.size());
    }

    @Test
    void keepsDifferentAttackersSeparateAndClearsOnlyTeammateDamage() {
        DamageNumberSystem system = new DamageNumberSystem();
        system.add(event("player-1", "zombie-1", 8, DamageNumberType.NORMAL, false, 10, 20, 1000));
        system.add(event("player-2", "zombie-1", 7, DamageNumberType.NORMAL, true, 10, 20, 1000));

        assertEquals(2, system.size());
        assertFalse(system.snapshots().get(0).teammateDamage());
        assertTrue(system.snapshots().get(1).teammateDamage());

        system.clearTeammateDamage();
        assertEquals(1, system.size());
        assertFalse(system.snapshots().get(0).teammateDamage());
    }

    private static DamageNumberEvent event(String attacker, String target, int damage, DamageNumberType type,
            boolean teammate, double x, double y, long timestamp) {
        return new DamageNumberEvent(attacker, target, damage, type, teammate, x, y, timestamp);
    }
}
