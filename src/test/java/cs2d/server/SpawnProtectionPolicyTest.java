package cs2d.server;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SpawnProtectionPolicyTest {

    @Test
    void zombieModeDisablesSpawnProtection() {
        assertFalse(SpawnProtectionPolicy.enabled(GameMode.ZOMBIE_MODE));
    }

    @Test
    void otherModesKeepTheirExistingSpawnProtection() {
        assertTrue(SpawnProtectionPolicy.enabled(GameMode.TEAM_DEATHMATCH));
        assertTrue(SpawnProtectionPolicy.enabled(GameMode.DEATHMATCH));
        assertTrue(SpawnProtectionPolicy.enabled(GameMode.DEMOLITION));
    }
}
