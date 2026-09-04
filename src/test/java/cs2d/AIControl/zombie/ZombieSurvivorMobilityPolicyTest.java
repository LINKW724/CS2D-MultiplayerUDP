package cs2d.AIControl.zombie;

import cs2d.playerAndAi.Weapon;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

class ZombieSurvivorMobilityPolicyTest {
    private final ZombieSurvivorMobilityPolicy policy = new ZombieSurvivorMobilityPolicy();

    @Test
    void lmgUsesPistolToRetreatAndRestoresPrimaryOnlyAfterSafeDistance() {
        var enter = policy.decide(Weapon.NEGEV, Weapon.USPS, 250, false, 0, 10_000, false);
        assertTrue(enter.emergency());
        assertTrue(enter.kiting());
        assertEquals(2, enter.desiredWeaponSlot());

        var hold = policy.decide(Weapon.NEGEV, Weapon.USPS, 350, false, 0, 10_100, true);
        assertTrue(hold.kiting());
        assertEquals(2, hold.desiredWeaponSlot());

        var restore = policy.decide(Weapon.NEGEV, Weapon.USPS, 420, false, 0, 10_200, true);
        assertFalse(restore.emergency());
        assertFalse(restore.kiting());
        assertEquals(1, restore.desiredWeaponSlot());
    }

    @Test
    void recentlyDamagedSmgEscapesWithoutPointlessWeaponSwitch() {
        var decision = policy.decide(Weapon.P90, Weapon.USPS, Double.POSITIVE_INFINITY,
                false, 9_500, 10_000, false);
        assertTrue(decision.emergency());
        assertTrue(decision.recentlyDamaged());
        assertEquals(0, decision.desiredWeaponSlot());
    }
}
