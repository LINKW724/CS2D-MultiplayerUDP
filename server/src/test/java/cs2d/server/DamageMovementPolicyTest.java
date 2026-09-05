package cs2d.server;

import cs2d.playerAndAi.Player;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class DamageMovementPolicyTest {

    @Test
    void zombieMeleeDoesNotApplyTheGenericMovementSlow() {
        assertEquals(0, DamageMovementPolicy.slowDurationMillis(
                GameMode.ZOMBIE_MODE, Player.Team.ZOMBIE, "ZOMBIE_CLAW"));
        assertEquals(0, DamageMovementPolicy.slowDurationMillis(
                GameMode.ZOMBIE_MODE, Player.Team.ZOMBIE, "KNIFE"));
    }

    @Test
    void gunfireAndOtherModesKeepTheExistingHitSlow() {
        assertEquals(300, DamageMovementPolicy.slowDurationMillis(
                GameMode.ZOMBIE_MODE, Player.Team.CT, "NEGEV"));
        assertEquals(300, DamageMovementPolicy.slowDurationMillis(
                GameMode.TEAM_DEATHMATCH, Player.Team.ZOMBIE, "KNIFE"));
    }
}
